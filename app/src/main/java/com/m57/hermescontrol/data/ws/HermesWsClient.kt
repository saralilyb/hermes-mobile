// Modified from Hy4ri/hermes-mobile for this fork; see NOTICE.

package com.m57.hermescontrol.data.ws

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.m57.hermescontrol.BuildConfig
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.AuthPayloads
import com.m57.hermescontrol.data.remote.DashboardSessionTokenRefresher
import com.m57.hermescontrol.data.remote.NetworkMonitor
import com.m57.hermescontrol.data.remote.OkHttpProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Connection status for the WebSocket client.
 */
enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    NO_NETWORK,
    AUTH_EXPIRED,
}

internal data class SourcedWsEvent(
    val event: WsEvent,
    val profileId: String?,
)

/**
 * WebSocket client for the Hermes Dashboard JSON-RPC 2.0 interface.
 *
 * Connects to the scheme-aware endpoint derived by [AuthManager],
 * auto-reconnects with exponential backoff, and emits parsed [WsEvent]s via [events] SharedFlow
 * as well as direct callbacks.
 */
object HermesWsClient {
    private const val TAG = "HermesWsClient"

    // ── Backoff settings ─────────────────────────────────────────────────

    private const val INITIAL_BACKOFF_MS = 1_000L
    private const val MAX_BACKOFF_MS = 30_000L
    private const val BACKOFF_MULTIPLIER = 2.0

    // ── Outbound queue settings ──────────────────────────────────────────

    /**
     * OkHttp refuses a send once the outgoing buffer would exceed 16 MiB. A
     * frame that large is rejected again on every retry, so it is dropped
     * rather than queued into a livelock.
     */
    private const val MAX_OUTBOUND_MESSAGE_BYTES = 16 * 1024 * 1024

    /**
     * Grace period for a congested — but still live — socket to drain after a
     * rejected send, before it is cancelled and reconnected.
     */
    private const val OUTBOUND_DRAIN_TIMEOUT_MS = 5_000L

    // ── Internal state (all access through synchronized / atomic) ────────

    private val requestId = AtomicInteger(0)
    private val connected = AtomicBoolean(false)
    private val intentionalClose = AtomicBoolean(false)
    private val ticketAuthRetryUsed = AtomicBoolean(false)
    private val messageQueue = ConcurrentLinkedQueue<String>()

    /**
     * Guards outbound-queue mutation and reconnect-job scheduling so a send, a
     * disconnect, and the [NetworkMonitor] collector cannot interleave. Never
     * held across ticket minting or socket construction.
     */
    private val connectionLock = Any()

    /**
     * False while a credential-clearing [disconnect] is in effect, so a send
     * racing that disconnect cannot re-populate the queue that was just
     * cleared. Reset by [connect].
     */
    private val acceptQueuedMessages = AtomicBoolean(true)

    @Volatile
    private var outboundDrainJob: Job? = null

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var currentBackoff = INITIAL_BACKOFF_MS

    private val wsScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var reconnectJob: Job? = null

    // ── Health and Ping/Pong tracking ────────────────────────────────────

    @Volatile
    var lastPongTimestamp: Long = 0L
        private set

    val isHealthy: Boolean
        get() = isConnected && (System.currentTimeMillis() - lastPongTimestamp < 60_000L)

    private var healthJob: Job? = null

    private fun startHealthTracking() {
        healthJob?.cancel()
        lastPongTimestamp = System.currentTimeMillis()
        healthJob =
            wsScope.launch {
                while (connected.get()) {
                    delay(30_000L)
                    if (connected.get() && System.currentTimeMillis() - lastPongTimestamp > 60_000L) {
                        Log.w(TAG, "WebSocket connection appears unhealthy (no frames received for > 60s)")
                    }
                }
            }
    }

    private fun stopHealthTracking() {
        healthJob?.cancel()
        healthJob = null
    }

    // ── Public observable stream ─────────────────────────────────────────

    private data class RawWsMessage(
        val text: String,
        val profileId: String?,
    )

    private val rawMessages =
        MutableSharedFlow<RawWsMessage>(
            extraBufferCapacity = 512,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    internal val sourcedEvents: SharedFlow<SourcedWsEvent> =
        rawMessages
            .buffer(Channel.BUFFERED)
            .map { raw ->
                val event =
                    try {
                        val rpc =
                            OkHttpProvider.json
                                .decodeFromString<JsonRpcResponse>(raw.text)
                        EventParser.parse(rpc, raw.text)
                    } catch (e: Exception) {
                        Log.e(
                            TAG,
                            "Failed to parse WebSocket message (${e.javaClass.simpleName})",
                        )
                        WsEvent.Unknown(raw.text)
                    }
                SourcedWsEvent(event = event, profileId = raw.profileId)
            }.flowOn(Dispatchers.Default) // CPU-bound
            .shareIn(wsScope, SharingStarted.Eagerly)

    /** Collect this from ViewModels to receive all parsed [WsEvent]s. */
    val events: SharedFlow<WsEvent> =
        sourcedEvents
            .map { sourced -> sourced.event }
            .shareIn(wsScope, SharingStarted.Eagerly)

    // ── Connection status flow ──────────────────────────────────────────
    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)

    /** Observable connection status */
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    // ── Credential warning (issue #534) ─────────────────────────────────
    // Backend surfaces `credential_warning` in `gateway.ready` / `session.info`
    // WS payloads (desktop `requestDesktopOnboarding`). Mobile has no equivalent
    // at the auth layer, so we extract it here once, globally, and let any
    // screen render a banner that deep-links to ProvidersScreen.
    private val credentialWarningState = CredentialWarningState()

    /** Non-null when the backend reports a credential warning to resolve. */
    val credentialWarning: StateFlow<String?> = credentialWarningState.warning

    fun clearCredentialWarning() {
        credentialWarningState.dismiss()
    }

    init {
        // Monitor network state to trigger immediate reconnect when network is restored
        wsScope.launch {
            NetworkMonitor.isConnected.collect { connected ->
                val autoReconnect =
                    runCatching { AuthManager.isAutoReconnect() }
                        .getOrDefault(false)
                if (connected && !isConnected && !intentionalClose.get() && autoReconnect) {
                    Log.d(TAG, "Network restored — triggering immediate reconnect")
                    // Serialize against scheduleReconnect(): both cancel and
                    // replace reconnectJob, so without the lock the collector
                    // and a backoff timer can each open a socket onto the same
                    // `webSocket` field. openSocket() is dispatched rather than
                    // called inline so the lock is never held across ticket
                    // minting or the handshake.
                    synchronized(connectionLock) {
                        if (intentionalClose.get() || isConnected) return@synchronized
                        currentBackoff = INITIAL_BACKOFF_MS
                        reconnectJob?.cancel()
                        reconnectJob = wsScope.launch { openSocket() }
                    }
                }
            }
        }
        // Extract credential_warning from gateway.ready / session.info payloads.
        wsScope.launch {
            events.collect { event ->
                val data: Map<String, Any?>? =
                    when (event) {
                        is WsEvent.GatewayReady -> event.data
                        is WsEvent.SessionInfo -> event.data
                        else -> null
                    }
                val warning = data?.get("credential_warning") as? String
                credentialWarningState.update(warning)
            }
        }
    }

    // ── Connection helpers ────────────────────────────────────────────────

    @VisibleForTesting
    val isConnected: Boolean get() = connected.get()

    /** Open a WebSocket connection using settings from [AuthManager]. */
    fun connect() {
        if (connected.get()) {
            Log.d(TAG, "Already connected — skipping")
            return
        }
        // Guard against re-entrant connect() while a connection is already in
        // flight. The singleton may be CONNECTING (mid handshake) or RECONNECTING
        // (a scheduled reconnect is pending). Opening a second socket on the same
        // `webSocket` field races the in-flight one and can leave the status
        // stuck on RECONNECTING (e.g. the chat tab calls connect() on every open
        // while the app-level reconnect is already running). Only start a fresh
        // socket from a terminal state.
        if (_connectionStatus.value == ConnectionStatus.CONNECTING ||
            _connectionStatus.value == ConnectionStatus.RECONNECTING
        ) {
            Log.d(TAG, "Connection already in flight (${_connectionStatus.value}) — skipping")
            return
        }
        // AUTH_EXPIRED cannot be resolved by reconnecting alone — caller must
        // re-authenticate. Leave the status as-is so the UI can surface sign-in.
        if (_connectionStatus.value == ConnectionStatus.AUTH_EXPIRED) {
            Log.d(TAG, "Connection is AUTH_EXPIRED — skipping reconnect; re-auth required")
            return
        }
        intentionalClose.set(false)
        ticketAuthRetryUsed.set(false)
        acceptQueuedMessages.set(true)
        currentBackoff = INITIAL_BACKOFF_MS
        _connectionStatus.value = ConnectionStatus.CONNECTING
        openSocket()
    }

    /**
     * In gated mode, mint a fresh WebSocket ticket from the dashboard. The
     * ticket is single-use and has a 30-second time-to-live (TTL), so a new
     * one is required on every connect and reconnect.
     *
     * Dashboard access and refresh cookies are attached automatically by the
     * shared CookieJar on [OkHttpProvider.probe]. The ticket endpoint decides
     * whether those cookies represent a live or refreshable session.
     *
     * Distinguishes a definitive authentication rejection from a retryable
     * transport/server failure. Only the former should force the user to sign
     * in again; treating every ticket-mint failure as expired authentication
     * turns brief gateway or network interruptions into logout loops.
     */
    private fun refreshWsTicketIfNeeded(): TicketRefreshResult {
        val isGated =
            try {
                AuthManager.isGatedMode()
            } catch (_: IllegalStateException) {
                // serverStore not initialized yet (transient early call); treat
                // as loopback so a stale-token refresh still runs. Log so a real
                // misconfiguration isn't silently swallowed.
                Log.w(TAG, "serverStore uninitialized during WS handshake; assuming non-gated")
                false
            }
        if (!isGated) {
            // The loopback dashboard token is regenerated on every server
            // restart. Refresh it before each WebSocket handshake so automatic
            // reconnect does not get stuck in AUTH_EXPIRED with a stale token.
            DashboardSessionTokenRefresher.refresh()
            return TicketRefreshResult(TicketRefreshStatus.READY)
        }

        // Do not preflight on a client-known access-cookie name. HTTPS
        // deployments prefix that cookie with `__Host-` or `__Secure-`, and
        // the access cookie may legitimately be absent while the server still
        // has a valid refresh cookie. The authenticated ticket endpoint is the
        // authority: the shared CookieJar attaches every applicable cookie and
        // the server either refreshes the session or returns an auth failure.
        try {
            val client = OkHttpProvider.probe
            val request =
                Request
                    .Builder()
                    .url(
                        AuthManager.endpointForBuild().resolve(
                            "api/auth/ws-ticket",
                        ),
                    )
                    .post("{}".toRequestBody())
                    .build()

            return kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(
                            TAG,
                            "WS ticket refresh failed: HTTP ${response.code}",
                        )
                        return@use if (response.code == 401 || response.code == 403) {
                            TicketRefreshResult(TicketRefreshStatus.AUTHENTICATION_FAILED)
                        } else {
                            TicketRefreshResult(TicketRefreshStatus.TRANSIENT_FAILURE)
                        }
                    }

                    val ticket =
                        AuthPayloads.webSocketTicket(response.body.string())
                    if (ticket.isNullOrBlank()) {
                        Log.w(
                            TAG,
                            "WS ticket refresh failed: invalid response",
                        )
                        return@use TicketRefreshResult(TicketRefreshStatus.TRANSIENT_FAILURE)
                    }

                    if (BuildConfig.DEBUG) Log.d(TAG, "WS ticket refreshed")
                    TicketRefreshResult(TicketRefreshStatus.READY, ticket)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "WS ticket refresh failed: ${e.javaClass.simpleName}")
            return TicketRefreshResult(TicketRefreshStatus.TRANSIENT_FAILURE)
        }
    }

    private data class TicketRefreshResult(
        val status: TicketRefreshStatus,
        val ticket: String? = null,
    )

    private enum class TicketRefreshStatus {
        READY,
        AUTHENTICATION_FAILED,
        TRANSIENT_FAILURE,
    }

    /**
     * Cleanly close the WebSocket and stop auto-reconnect.
     *
     * Pass [clearPendingMessages] whenever the credentials behind the
     * connection are being discarded — logout, sign-in-required routing, or a
     * successful login that may target a different profile. Queued frames were
     * composed against the previous identity; without clearing them the next
     * [onOpen] would flush them into whichever session opens next.
     *
     * A plain reconnect (same credentials, same profile) must keep the queue,
     * which is the whole reason it exists.
     */
    fun disconnect(clearPendingMessages: Boolean = false) {
        synchronized(connectionLock) {
            intentionalClose.set(true)
            ticketAuthRetryUsed.set(false)
            acceptQueuedMessages.set(!clearPendingMessages)
            reconnectJob?.cancel()
            reconnectJob = null
            outboundDrainJob?.cancel()
            outboundDrainJob = null
            stopHealthTracking()
            // Fail in-flight RPC callers now; otherwise they hang until the
            // 120 s per-request timeout after a profile transition.
            rejectAllPending()
            webSocket?.close(1000, "Client closed")
            webSocket = null
            connected.set(false)
            if (clearPendingMessages) {
                messageQueue.clear()
            }
            _connectionStatus.value = ConnectionStatus.DISCONNECTED
        }
    }

    // ── Awaited RPC request layer (issue #526) ─────────────────────────
    // Mirrors desktop apps/shared JsonRpcGatewayClient.request(): an in-flight
    // map with a per-call timeout and rejectAllPending on socket close, so a
    // dropped RPC response can't leave a caller awaiting forever.

    /** Thrown when an awaited [request] is neither answered nor rejected within [REQUEST_TIMEOUT_MS], or is rejected by a disconnect. */
    class HermesRpcException(
        message: String,
    ) : Exception(message)

    /**
     * Default per-request timeout. Matches the desktop
     * `apps/shared` `JsonRpcGatewayClient.DEFAULT_REQUEST_TIMEOUT_MS` (120s),
     * so legitimately long agent turns are not pruned early.
     */
    const val REQUEST_TIMEOUT_MS: Long = 120_000L

    /** A single in-flight [request] awaiting its RPC result/error. */
    private data class PendingCall(
        val method: String,
        val deferred: CompletableDeferred<Any?>,
        var timeoutJob: Job? = null,
    )

    /** Tracks in-flight [request] calls by their JSON-RPC id. */
    private val pendingCalls = ConcurrentHashMap<String, PendingCall>()

    /**
     * Send a JSON-RPC request that expects a result and returns a
     * [CompletableDeferred] for it — mirroring the desktop
     * `JsonRpcGatewayClient.request()`. Fire-and-forget notifications should
     * keep using [send].
     *
     * The deferred is completed on the matching [WsEvent.RpcResult] /
     * [WsEvent.RpcError], rejected after [timeoutMs] (no response), or
     * rejected by [rejectAllPending] when the socket closes.
     */
    fun request(
        method: String,
        params: Map<String, Any> = emptyMap(),
        timeoutMs: Long = REQUEST_TIMEOUT_MS,
    ): CompletableDeferred<Any?> {
        val deferred = CompletableDeferred<Any?>()
        val id =
            send(method, params) { reqId ->
                pendingCalls[reqId] = PendingCall(method, deferred)
            }
        // Arm the per-request timeout (fires if the server never answers).
        pendingCalls[id]?.timeoutJob =
            wsScope.launch {
                delay(timeoutMs)
                resolvePending(id, null, JsonRpcError(-1, "Request timed out: $method"))
            }
        return deferred
    }

    /** Complete (or fail) a single pending call and cancel its timer. */
    private fun resolvePending(
        id: String,
        result: Any?,
        error: JsonRpcError?,
    ) {
        val call = pendingCalls.remove(id) ?: return
        call.timeoutJob?.cancel()
        if (error != null) {
            call.deferred.completeExceptionally(HermesRpcException(error.message))
        } else {
            call.deferred.complete(result)
        }
    }

    /**
     * Fail and clear every in-flight [request]. Called on disconnect /
     * reconnect so callers awaiting a result don't hang across a socket
     * close — mirrors desktop `JsonRpcGatewayClient.rejectAllPending(error)`
     * invoked on socket close.
     */
    fun rejectAllPending(
        error: HermesRpcException =
            HermesRpcException("Connection lost — request cancelled"),
    ) {
        if (pendingCalls.isEmpty()) return
        val snapshot = pendingCalls.toList()
        pendingCalls.clear()
        for ((id, call) in snapshot) {
            Log.w(TAG, "Rejecting pending request on disconnect: ${call.method} (id=$id)")
            call.timeoutJob?.cancel()
            call.deferred.completeExceptionally(error)
        }
    }

    // ── Send helpers ─────────────────────────────────────────────────────

    /**
     * Send a JSON-RPC request with the given [method] and optional [params].
     * @return the request id used (can be matched against [WsEvent.RpcResult]).
     */
    fun send(
        method: String,
        params: Map<String, Any> = emptyMap(),
        onSent: ((String) -> Unit)? = null,
    ): String {
        val id = requestId.incrementAndGet().toString()
        onSent?.invoke(id)
        val request = JsonRpcRequest(id = id, method = method, params = params.mapValues { it.value.toJsonElement() })
        val json = OkHttpProvider.json.encodeToString(request)
        synchronized(connectionLock) {
            val ws = webSocket
            if (ws != null && connected.get()) {
                // OkHttp returns false when the frame could not be enqueued —
                // the socket is closing or its outgoing buffer is full. The
                // previous code ignored that and silently dropped the message.
                if (!ws.send(json)) {
                    if (webSocket !== ws || !acceptQueuedMessages.get()) return@synchronized
                    if (isRetryableMessage(json)) {
                        Log.w(TAG, "WS rejected outgoing message — queuing for reconnect")
                        messageQueue.add(json)
                        recoverRejectedSocket(ws)
                    } else {
                        Log.w(TAG, "WS rejected oversized outgoing message — not retrying")
                    }
                }
            } else if (acceptQueuedMessages.get()) {
                if (isRetryableMessage(json)) {
                    Log.d(TAG, "WS disconnected — queuing message")
                    messageQueue.add(json)
                } else {
                    Log.w(TAG, "WS disconnected with oversized outgoing message — not queueing")
                }
            } else {
                Log.w(TAG, "WS credentials cleared — dropping outgoing message")
            }
        }
        return id
    }

    /**
     * A frame larger than OkHttp's outgoing-buffer ceiling is rejected again on
     * every retry, so queueing it would livelock the drain loop. The cheap
     * length checks avoid encoding the string except in the ambiguous band.
     */
    private fun isRetryableMessage(json: String): Boolean {
        if (json.length > MAX_OUTBOUND_MESSAGE_BYTES) return false
        if (json.length <= MAX_OUTBOUND_MESSAGE_BYTES / 4) return true
        return json.toByteArray(Charsets.UTF_8).size <= MAX_OUTBOUND_MESSAGE_BYTES
    }

    /**
     * Recover from a send OkHttp refused. The socket is no longer usable for
     * new frames, but it may still be draining what it already accepted, so
     * give it a bounded grace period before cancelling and reconnecting.
     *
     * Caller must hold [connectionLock].
     */
    private fun recoverRejectedSocket(ws: WebSocket) {
        connected.set(false)
        _connectionStatus.value = ConnectionStatus.RECONNECTING
        if (ws.queueSize() == 0L) {
            ws.cancel()
            scheduleReconnect()
            return
        }
        if (intentionalClose.get()) return
        outboundDrainJob?.cancel()
        outboundDrainJob =
            wsScope.launch {
                delay(OUTBOUND_DRAIN_TIMEOUT_MS)
                synchronized(connectionLock) {
                    if (!connected.get() && !intentionalClose.get() && webSocket === ws) {
                        ws.cancel()
                        outboundDrainJob = null
                        scheduleReconnect()
                    }
                }
            }
    }

    /**
     * Flush queued frames onto a freshly opened socket.
     *
     * Peeks rather than polls: a frame OkHttp refuses stays at the head of the
     * queue so the next connection can retry it, instead of being dropped the
     * way the previous unconditional `poll()` loop did. An oversized frame can
     * never succeed, so it is discarded to avoid livelocking the loop.
     */
    private fun drainQueue(ws: WebSocket) {
        synchronized(connectionLock) {
            while (true) {
                val msg = messageQueue.peek() ?: break
                if (!isRetryableMessage(msg)) {
                    Log.w(TAG, "Dropping oversized queued message")
                    messageQueue.poll()
                    continue
                }
                if (!ws.send(msg)) {
                    Log.w(TAG, "WS rejected queued message — retaining for next connection")
                    recoverRejectedSocket(ws)
                    return
                }
                messageQueue.poll()
            }
        }
    }

    /** Convenience: submit a user prompt to an existing session. */
    fun sendMessage(
        sessionId: String,
        text: String,
        onSent: ((String) -> Unit)? = null,
    ): String =
        send(
            method = WsMethods.PROMPT_SUBMIT,
            params = mapOf("session_id" to sessionId, "text" to text),
            onSent = onSent,
        )

    /**
     * Convenience: steer the active model turn while it is still generating
     * (backend `session.redirect`).
     *
     * Fire-and-forget. The backend either rewrites the live turn, queues the
     * correction as the next turn, or rejects it with `4010` when the running
     * agent cannot be redirected. The caller's ViewModel is responsible for
     * falling back to [sendMessage] on `4010` so the typed text is never lost.
     */
    fun sendRedirect(
        sessionId: String,
        text: String,
        onSent: ((String) -> Unit)? = null,
    ): String =
        send(
            method = WsMethods.SESSION_REDIRECT,
            params = mapOf("session_id" to sessionId, "text" to text),
            onSent = onSent,
        )

    // ── Internal ─────────────────────────────────────────────────────────

    private fun openSocket() {
        val profileId = AuthManager.getSelectedProfileId()
        val ticketResult = refreshWsTicketIfNeeded()
        if (profileId != AuthManager.getSelectedProfileId()) {
            restartAfterProfileChange()
            return
        }
        when (ticketResult.status) {
            TicketRefreshStatus.READY -> Unit

            TicketRefreshStatus.AUTHENTICATION_FAILED -> {
                Log.w(TAG, "Aborting openSocket: dashboard authentication rejected")
                intentionalClose.set(true)
                _connectionStatus.value = ConnectionStatus.AUTH_EXPIRED
                return
            }

            TicketRefreshStatus.TRANSIENT_FAILURE -> {
                Log.w(TAG, "Deferring openSocket: WS ticket refresh temporarily unavailable")
                _connectionStatus.value = ConnectionStatus.RECONNECTING
                scheduleReconnect()
                return
            }
        }
        val url =
            try {
                ticketResult.ticket?.let(AuthManager::wsUrlWithCredential)
                    ?: AuthManager.wsUrl()
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "WebSocket blocked by transport policy")
                _connectionStatus.value = ConnectionStatus.DISCONNECTED
                return
            }
        if (profileId != AuthManager.getSelectedProfileId()) {
            restartAfterProfileChange()
            return
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "Connecting to WebSocket endpoint")

        val request = Request.Builder().url(url).build()
        webSocket =
            OkHttpProvider.websocket.newWebSocket(
                request,
                WsListenerImpl(profileId),
            )
    }

    private fun restartAfterProfileChange() {
        Log.d(TAG, "Connection profile changed during WebSocket setup; restarting")
        val shouldReconnect = !intentionalClose.get()
        disconnect(clearPendingMessages = true)
        if (shouldReconnect) {
            wsScope.launch { connect() }
        }
    }

    private fun scheduleReconnect() {
        // Reentrant: recoverRejectedSocket() and drainQueue() already hold the
        // lock when they land here.
        synchronized(connectionLock) {
            if (intentionalClose.get()) return
            if (!AuthManager.isAutoReconnect()) {
                if (BuildConfig.DEBUG) Log.d(TAG, "Auto-reconnect disabled")
                _connectionStatus.value = ConnectionStatus.DISCONNECTED
                return
            }
            if (!NetworkMonitor.isConnected.value) {
                Log.d(TAG, "No network available — delaying reconnect scheduling")
                _connectionStatus.value = ConnectionStatus.NO_NETWORK
                return
            }
            val delay = currentBackoff
            currentBackoff =
                (currentBackoff * BACKOFF_MULTIPLIER)
                    .toLong()
                    .coerceAtMost(MAX_BACKOFF_MS)
            if (BuildConfig.DEBUG) Log.d(TAG, "Reconnecting in ${delay}ms …")

            reconnectJob?.cancel()
            reconnectJob =
                wsScope.launch {
                    delay(delay)
                    if (!intentionalClose.get() && !connected.get()) {
                        openSocket()
                    }
                }
        }
    }

    /**
     * A WebSocket ticket is single-use and can be rejected after the dashboard
     * session has already refreshed successfully. In gated mode, mint and try
     * one fresh ticket before declaring the dashboard session expired.
     */
    private fun handleAuthenticationRejected() {
        val isGated =
            runCatching {
                AuthManager.isGatedMode()
            }.getOrDefault(false)
        if (isGated && !intentionalClose.get() &&
            ticketAuthRetryUsed.compareAndSet(false, true)
        ) {
            Log.w(TAG, "WebSocket ticket rejected; retrying once with a fresh ticket")
            _connectionStatus.value = ConnectionStatus.RECONNECTING
            wsScope.launch { openSocket() }
            return
        }

        intentionalClose.set(true)
        _connectionStatus.value = ConnectionStatus.AUTH_EXPIRED
    }

    // ── Listener ─────────────────────────────────────────────────────────

    private class WsListenerImpl(
        private val profileId: String?,
    ) : WebSocketListener() {
        override fun onOpen(
            webSocket: WebSocket,
            response: Response,
        ) {
            if (webSocket !== HermesWsClient.webSocket) {
                Log.d(TAG, "Ignoring stale WebSocket onOpen callback")
                webSocket.close(1000, "Superseded connection")
                return
            }
            Log.i(TAG, "WebSocket opened")
            connected.set(true)
            _connectionStatus.value = ConnectionStatus.CONNECTED
            currentBackoff = INITIAL_BACKOFF_MS
            startHealthTracking()

            drainQueue(webSocket)
        }

        override fun onMessage(
            webSocket: WebSocket,
            text: String,
        ) {
            if (webSocket !== HermesWsClient.webSocket) {
                Log.d(TAG, "Ignoring stale WebSocket message")
                return
            }
            lastPongTimestamp = System.currentTimeMillis()
            // Resolve any in-flight `request()` awaiting this RPC result/error
            // (issue #526) before fanning the parsed event out to collectors.
            val event =
                try {
                    val rpc = OkHttpProvider.json.decodeFromString<JsonRpcResponse>(text)
                    EventParser.parse(rpc, text)
                } catch (e: Exception) {
                    Log.e(
                        TAG,
                        "Failed to parse WebSocket message (${e.javaClass.simpleName})",
                    )
                    WsEvent.Unknown(text)
                }
            // A parsed gateway frame proves that the fresh ticket established
            // a usable session. Do not reset on unknown/auth-noise frames: that
            // could otherwise permit an immediate rejection loop.
            if (event !is WsEvent.Unknown) {
                ticketAuthRetryUsed.set(false)
            }
            when (event) {
                is WsEvent.RpcResult -> resolvePending(event.id, event.result, null)
                is WsEvent.RpcError -> resolvePending(event.id, null, event.error)
                else -> Unit
            }
            val emitted =
                rawMessages.tryEmit(
                    RawWsMessage(text = text, profileId = profileId),
                )
            if (!emitted && BuildConfig.DEBUG) {
                Log.w(TAG, "WebSocket message dropped due to buffer overflow")
            }
        }

        override fun onClosing(
            webSocket: WebSocket,
            code: Int,
            reason: String,
        ) {
            Log.d(TAG, "WebSocket closing: $code")
            webSocket.close(code, reason)
            if (webSocket !== HermesWsClient.webSocket) {
                Log.d(TAG, "Ignoring stale WebSocket onClosing callback")
            }
        }

        override fun onClosed(
            webSocket: WebSocket,
            code: Int,
            reason: String,
        ) {
            if (webSocket !== HermesWsClient.webSocket) {
                Log.d(TAG, "Ignoring stale WebSocket onClosed callback")
                return
            }
            HermesWsClient.webSocket = null
            Log.i(TAG, "WebSocket closed: $code")
            connected.set(false)
            stopHealthTracking()
            if (code == 4001 || code == 4401 ||
                reason.contains("unauthorized", ignoreCase = true) ||
                reason.startsWith("auth:", ignoreCase = true)
            ) {
                handleAuthenticationRejected()
            } else {
                _connectionStatus.value = ConnectionStatus.RECONNECTING
                scheduleReconnect()
            }
        }

        override fun onFailure(
            webSocket: WebSocket,
            t: Throwable,
            response: Response?,
        ) {
            if (webSocket !== HermesWsClient.webSocket) {
                Log.d(TAG, "Ignoring stale WebSocket onFailure callback")
                return
            }
            HermesWsClient.webSocket = null
            Log.e(TAG, "WebSocket failure (${t.javaClass.simpleName})")
            connected.set(false)
            stopHealthTracking()
            val code = response?.code ?: 0
            if (code == 401 || t.message?.contains(
                    "401",
                ) == true || t.message?.contains("unauthorized", ignoreCase = true) == true
            ) {
                handleAuthenticationRejected()
            } else {
                _connectionStatus.value = ConnectionStatus.RECONNECTING
                scheduleReconnect()
            }
        }
    }
}
