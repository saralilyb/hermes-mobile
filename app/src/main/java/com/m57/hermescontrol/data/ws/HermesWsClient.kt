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
import com.m57.hermescontrol.data.session.ActiveSessionHolder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
    val connectionGeneration: Int,
    val storedSessionId: String? = null,
)

data class PrivilegedRequestBinding(
    val requestId: String,
    val runtimeSessionId: String,
    val profileId: String,
    val connectionGeneration: Int,
)

/** Immutable identity of one live WebSocket connection. */
class ConnectionBinding internal constructor(
    val profileId: String,
    val generation: Int,
    internal val socket: WebSocket,
)

data class ServerRequestBinding(
    val requestId: String,
    val runtimeSessionId: String,
    val profileId: String,
    val connectionGeneration: Int,
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

    /** Keep a quiet socket alive across brief app switches. */
    private const val BACKGROUND_IDLE_GRACE_MS = 120_000L
    private const val HEARTBEAT_INTERVAL_MS = 15_000L
    private const val STALE_THRESHOLD_MS = 45_000L
    private const val LIVENESS_PROBE_TIMEOUT_MS = 5_000L

    private fun monotonicTimeMs(): Long = System.nanoTime() / 1_000_000L

    // ── Internal state (all access through synchronized / atomic) ────────

    private val requestId = AtomicInteger(0)
    private val connectionGeneration = AtomicInteger(0)
    private val socketGeneration = AtomicInteger(0)
    private val connected = AtomicBoolean(false)
    private val intentionalClose = AtomicBoolean(false)
    private val ticketAuthRetryUsed = AtomicBoolean(false)
    private val appInForeground = AtomicBoolean(true)
    private val messageQueue = ConcurrentLinkedQueue<String>()

    /** Prompt request id to session id, guarded by [connectionLock]. */
    private val pendingPromptSessions = linkedMapOf<String, String>()
    private val backgroundIdleClosed = AtomicBoolean(false)

    @Volatile
    var pendingReply: Boolean = false
        private set

    /**
     * Guards outbound-queue mutation and reconnect-job scheduling so a send, a
     * disconnect, and the [NetworkMonitor] collector cannot interleave. Never
     * held across ticket minting. Held briefly across non-blocking socket
     * construction and publication so callbacks cannot outrun assignment.
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
    private var backgroundIdleJob: Job? = null

    private val backgroundIdleScheduleGeneration = AtomicInteger(0)

    @Volatile
    private var webSocket: WebSocket? = null

    /** Identity of [webSocket], guarded by [connectionLock]. */
    private var activeConnectionProfileId: String? = null
    private var activeConnectionGeneration: Int = -1

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
        get() = isConnected && (monotonicTimeMs() - lastPongTimestamp < STALE_THRESHOLD_MS)

    private var healthJob: Job? = null

    private fun startHealthTracking() {
        healthJob?.cancel()
        lastPongTimestamp = monotonicTimeMs()
        healthJob =
            wsScope.launch {
                while (connected.get()) {
                    delay(HEARTBEAT_INTERVAL_MS)
                    if (!connected.get()) break
                    val alive = runCatching { ping(LIVENESS_PROBE_TIMEOUT_MS) }.isSuccess
                    if (!alive && monotonicTimeMs() - lastPongTimestamp > STALE_THRESHOLD_MS) {
                        synchronized(connectionLock) {
                            if (connected.get()) webSocket?.cancel()
                        }
                        break
                    }
                }
            }
    }

    suspend fun ping(timeoutMs: Long = LIVENESS_PROBE_TIMEOUT_MS): Long {
        val start = monotonicTimeMs()
        val pendingRequest = request(WsMethods.GATEWAY_PING, timeoutMs = timeoutMs)
        try {
            pendingRequest.await()
        } finally {
            cancelPendingRequest(pendingRequest)
        }
        val latency = (monotonicTimeMs() - start).coerceAtLeast(0L)
        lastPongTimestamp = monotonicTimeMs()
        return latency
    }

    private fun stopHealthTracking() {
        healthJob?.cancel()
        healthJob = null
    }

    // ── Public observable stream ─────────────────────────────────────────

    private val parsedEvents =
        MutableSharedFlow<SourcedWsEvent>(
            extraBufferCapacity = 512,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    internal val sourcedEvents: SharedFlow<SourcedWsEvent> = parsedEvents.asSharedFlow()

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
                if (connected && shouldReconnectAfterNetworkRestore(autoReconnect)) {
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
                        val generation = connectionGeneration.get()
                        reconnectJob = wsScope.launch { openSocket(generation) }
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

    fun setAppForeground(foreground: Boolean) {
        synchronized(connectionLock) {
            appInForeground.set(foreground)
            if (foreground) {
                cancelBackgroundIdleCloseLocked()
            } else {
                disconnectIfIdleInBackground()
            }
        }
    }

    private fun disconnectIfIdleInBackground() {
        synchronized(connectionLock) {
            if (isIdleInBackgroundLocked()) {
                scheduleBackgroundIdleCloseLocked()
            } else {
                cancelBackgroundIdleCloseLocked()
            }
        }
    }

    private fun isIdleInBackgroundLocked(): Boolean =
        !appInForeground.get() &&
            webSocket != null &&
            !pendingReply &&
            pendingCalls.isEmpty() &&
            messageQueue.isEmpty()

    private fun scheduleBackgroundIdleCloseLocked() {
        if (backgroundIdleJob?.isActive == true) return
        val scheduleGeneration = backgroundIdleScheduleGeneration.incrementAndGet()
        val socketGeneration = connectionGeneration.get()
        backgroundIdleJob =
            wsScope.launch {
                delay(BACKGROUND_IDLE_GRACE_MS)
                synchronized(connectionLock) {
                    if (scheduleGeneration != backgroundIdleScheduleGeneration.get() ||
                        socketGeneration != connectionGeneration.get()
                    ) {
                        return@synchronized
                    }
                    backgroundIdleJob = null
                    if (isIdleInBackgroundLocked()) closeIdleBackgroundSocket()
                }
            }
    }

    private fun cancelBackgroundIdleCloseLocked() {
        backgroundIdleScheduleGeneration.incrementAndGet()
        backgroundIdleJob?.cancel()
        backgroundIdleJob = null
    }

    @VisibleForTesting
    internal fun hasScheduledBackgroundIdleCloseForTest(): Boolean =
        synchronized(connectionLock) { backgroundIdleJob?.isActive == true }

    @VisibleForTesting
    internal fun expireBackgroundIdleGraceForTest() {
        synchronized(connectionLock) {
            cancelBackgroundIdleCloseLocked()
            if (isIdleInBackgroundLocked()) closeIdleBackgroundSocket()
        }
    }

    /**
     * Close an idle background socket without disabling future authenticated
     * connections. Unlike [disconnect], this is a lifecycle optimization, not
     * a user-requested stop or credential boundary.
     */
    private fun closeIdleBackgroundSocket() {
        cancelBackgroundIdleCloseLocked()
        connectionGeneration.incrementAndGet()
        backgroundIdleClosed.set(true)
        reconnectJob?.cancel()
        reconnectJob = null
        outboundDrainJob?.cancel()
        outboundDrainJob = null
        stopHealthTracking()
        rejectAllPending()
        webSocket?.close(1000, "Background idle")
        webSocket = null
        connected.set(false)
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
    }

    @VisibleForTesting
    internal fun shouldReconnectAfterNetworkRestore(autoReconnect: Boolean): Boolean =
        !isConnected &&
            !intentionalClose.get() &&
            !backgroundIdleClosed.get() &&
            autoReconnect

    /** Open a WebSocket connection using settings from [AuthManager]. */
    fun connect() {
        val generation =
            synchronized(connectionLock) {
                if (connected.get()) {
                    Log.d(TAG, "Already connected — skipping")
                    return
                }
                // Guard against re-entrant connect() while a connection is
                // already in flight. Only start from a terminal state.
                if (_connectionStatus.value == ConnectionStatus.CONNECTING ||
                    _connectionStatus.value == ConnectionStatus.RECONNECTING
                ) {
                    Log.d(
                        TAG,
                        "Connection already in flight (${_connectionStatus.value}) — skipping",
                    )
                    return
                }
                // AUTH_EXPIRED cannot be resolved by reconnecting alone.
                if (_connectionStatus.value == ConnectionStatus.AUTH_EXPIRED) {
                    Log.d(TAG, "Connection is AUTH_EXPIRED — skipping reconnect; re-auth required")
                    return
                }
                cancelBackgroundIdleCloseLocked()
                intentionalClose.set(false)
                backgroundIdleClosed.set(false)
                ticketAuthRetryUsed.set(false)
                acceptQueuedMessages.set(true)
                currentBackoff = INITIAL_BACKOFF_MS
                _connectionStatus.value = ConnectionStatus.CONNECTING
                connectionGeneration.incrementAndGet()
            }
        openSocket(generation)
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
            cancelBackgroundIdleCloseLocked()
            connectionGeneration.incrementAndGet()
            intentionalClose.set(true)
            backgroundIdleClosed.set(false)
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
            pendingReplayResponses.clear()
            webSocket?.close(1000, "Client closed")
            webSocket = null
            activeConnectionProfileId = null
            activeConnectionGeneration = -1
            connected.set(false)
            if (clearPendingMessages) {
                messageQueue.clear()
                pendingPromptSessions.clear()
                pendingReply = false
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
        val code: Int? = null,
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
        val replayAdmission: ReplayAdmission? = null,
        var timeoutJob: Job? = null,
    )

    private data class ReplayAdmission(
        val method: String,
        val profileId: String,
        val connectionGeneration: Int,
        val runtimeSessionId: String,
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
    ): CompletableDeferred<Any?> =
        synchronized(connectionLock) {
            val deferred = CompletableDeferred<Any?>()
            val id =
                send(method, params) { reqId ->
                    pendingCalls[reqId] =
                        PendingCall(
                            method,
                            deferred,
                            replayAdmission = replayAdmission(method, params),
                        )
                }
            // Arm the per-request timeout (fires if the server never answers).
            pendingCalls[id]?.timeoutJob =
                wsScope.launch {
                    delay(timeoutMs)
                    resolvePending(id, null, JsonRpcError(-1, "Request timed out: $method"))
                }
            deferred
        }

    /**
     * Answer or cancel exactly one privileged gateway request.
     *
     * Unlike [send] this has no queue, no reconnect retry, and no fallback: the
     * frame either goes out on the very socket generation that dispatched the
     * request, under the same profile, or the call fails. A privileged frame
     * replayed onto a replacement connection would authorize a request the user
     * never saw, so a stale [binding] must never reach the wire.
     *
     * The returned deferred completes only on the gateway's own acknowledgement
     * (or its error / the request timeout), so callers can gate destructive UI
     * transitions on a real ack.
     */
    fun privilegedRequest(
        method: String,
        binding: PrivilegedRequestBinding,
        params: Map<String, String> = emptyMap(),
    ): CompletableDeferred<Any?> =
        synchronized(connectionLock) {
            val deferred = CompletableDeferred<Any?>()
            val ws = webSocket
            if (!appInForeground.get() || !connected.get() || ws == null ||
                binding.runtimeSessionId.isBlank() ||
                binding.connectionGeneration != activeConnectionGeneration ||
                binding.profileId != activeConnectionProfileId ||
                binding.profileId != AuthManager.getSelectedProfileId() ||
                ActiveSessionHolder.activeSessionId.value != binding.runtimeSessionId
            ) {
                deferred.completeExceptionally(HermesRpcException("Privileged request is no longer active"))
                return@synchronized deferred
            }
            val id = requestId.incrementAndGet().toString()
            val allParams =
                params +
                    mapOf(
                        "session_id" to binding.runtimeSessionId,
                        "request_id" to binding.requestId,
                    )
            val request =
                JsonRpcRequest(id = id, method = method, params = allParams.mapValues { it.value.toJsonElement() })
            val json = OkHttpProvider.json.encodeToString(request)
            val pending = PendingCall(method, deferred)
            pendingCalls[id] = pending
            if (!ws.send(json)) {
                pendingCalls.remove(id)
                deferred.completeExceptionally(HermesRpcException("Privileged request was not sent"))
            } else {
                pending.timeoutJob =
                    wsScope.launch {
                        delay(REQUEST_TIMEOUT_MS)
                        resolvePending(id, null, JsonRpcError(-1, "Request timed out: $method"))
                    }
            }
            deferred
        }

    /** Respond only on the socket/profile/session that originated this request. */
    fun respondToServerRequest(
        binding: ServerRequestBinding,
        result: JsonElement,
    ): Boolean =
        synchronized(connectionLock) {
            val ws = webSocket
            if (!appInForeground.get() || !connected.get() || ws == null ||
                binding.requestId.isBlank() || binding.runtimeSessionId.isBlank() ||
                binding.connectionGeneration != activeConnectionGeneration ||
                binding.profileId != activeConnectionProfileId ||
                binding.profileId != AuthManager.getSelectedProfileId() ||
                ActiveSessionHolder.activeSessionId.value != binding.runtimeSessionId
            ) {
                return@synchronized false
            }
            ws.send(
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", binding.requestId)
                    put("result", result)
                }.toString(),
            )
        }

    /** Capture the exact live profile/socket identity for a later bound request. */
    fun connectionBinding(expectedProfileId: String): ConnectionBinding? =
        synchronized(connectionLock) {
            val socket = webSocket
            if (!connected.get() ||
                socket == null ||
                activeConnectionProfileId != expectedProfileId ||
                activeConnectionGeneration < 0
            ) {
                null
            } else {
                ConnectionBinding(expectedProfileId, activeConnectionGeneration, socket)
            }
        }

    /** Synchronously validate that [binding] still identifies the exact live socket. */
    fun isConnectionBindingCurrent(binding: ConnectionBinding): Boolean =
        synchronized(connectionLock) {
            connected.get() &&
                webSocket === binding.socket &&
                activeConnectionProfileId == binding.profileId &&
                activeConnectionGeneration == binding.generation
        }

    /**
     * Atomically send an awaited request on an exact live foreground socket.
     *
     * This bound path is intentionally separate from [request]/[send]: a control
     * request must fail closed rather than enter [messageQueue] or trigger a
     * reconnect when its captured profile, runtime session, or socket changes.
     */
    fun requestForConnection(
        binding: ConnectionBinding,
        method: String,
        params: Map<String, Any> = emptyMap(),
        timeoutMs: Long = REQUEST_TIMEOUT_MS,
    ): CompletableDeferred<Any?> =
        synchronized(connectionLock) {
            val deferred = CompletableDeferred<Any?>()
            val runtimeSessionId = params["session_id"] as? String
            if (!appInForeground.get() ||
                !connected.get() ||
                webSocket !== binding.socket ||
                activeConnectionProfileId != binding.profileId ||
                activeConnectionGeneration != binding.generation ||
                AuthManager.getSelectedProfileId() != binding.profileId ||
                runtimeSessionId == null ||
                ActiveSessionHolder.activeSessionId.value != runtimeSessionId
            ) {
                deferred.completeExceptionally(
                    HermesRpcException("WebSocket binding changed — request cancelled"),
                )
                return@synchronized deferred
            }

            val id = requestId.incrementAndGet().toString()
            val request =
                JsonRpcRequest(
                    id = id,
                    method = method,
                    params = params.mapValues { it.value.toJsonElement() },
                )
            val pending = PendingCall(method, deferred)
            pendingCalls[id] = pending
            if (!binding.socket.send(OkHttpProvider.json.encodeToString(request))) {
                pendingCalls.remove(id)
                deferred.completeExceptionally(HermesRpcException("Bound request was not sent"))
            } else {
                pending.timeoutJob =
                    wsScope.launch {
                        delay(timeoutMs)
                        resolvePending(id, null, JsonRpcError(-1, "Request timed out: $method"))
                    }
            }
            deferred
        }

    /**
     * Atomically send an awaited profile-scoped request on an exact live socket.
     *
     * Unlike [requestForConnection], this path does not require an active chat
     * session. It is for profile-level reads such as `session.active_list` and
     * still fails closed across profile, generation, or socket changes.
     */
    fun requestForProfileConnection(
        binding: ConnectionBinding,
        method: String,
        params: Map<String, Any> = emptyMap(),
        timeoutMs: Long = REQUEST_TIMEOUT_MS,
    ): CompletableDeferred<Any?> =
        synchronized(connectionLock) {
            val deferred = CompletableDeferred<Any?>()
            if (!appInForeground.get() ||
                !connected.get() ||
                webSocket !== binding.socket ||
                activeConnectionProfileId != binding.profileId ||
                activeConnectionGeneration != binding.generation ||
                AuthManager.getSelectedProfileId() != binding.profileId
            ) {
                deferred.completeExceptionally(
                    HermesRpcException("WebSocket binding changed — request cancelled"),
                )
                return@synchronized deferred
            }

            val id = requestId.incrementAndGet().toString()
            val request =
                JsonRpcRequest(
                    id = id,
                    method = method,
                    params = params.mapValues { it.value.toJsonElement() },
                )
            val pending = PendingCall(method, deferred)
            pendingCalls[id] = pending
            if (!binding.socket.send(OkHttpProvider.json.encodeToString(request))) {
                pendingCalls.remove(id)
                deferred.completeExceptionally(HermesRpcException("Bound request was not sent"))
            } else {
                pending.timeoutJob =
                    wsScope.launch {
                        delay(timeoutMs)
                        resolvePending(id, null, JsonRpcError(-1, "Request timed out: $method"))
                    }
            }
            deferred
        }

    suspend fun requestForProfileConnectionAwaited(
        binding: ConnectionBinding,
        method: String,
        params: Map<String, Any> = emptyMap(),
        timeoutMs: Long = REQUEST_TIMEOUT_MS,
    ): Any? {
        val pendingRequest = requestForProfileConnection(binding, method, params, timeoutMs)
        return try {
            pendingRequest.await()
        } finally {
            cancelPendingRequest(pendingRequest)
        }
    }

    /** Complete (or fail) a single pending call and cancel its timer. */
    private fun resolvePending(
        id: String,
        result: Any?,
        error: JsonRpcError?,
    ): PendingCall? {
        val call = pendingCalls.remove(id) ?: return null
        call.timeoutJob?.cancel()
        if (error != null) {
            call.deferred.completeExceptionally(HermesRpcException(error.message, error.code))
        } else {
            call.deferred.complete(result)
        }
        disconnectIfIdleInBackground()
        return call
    }

    /** Remove a request abandoned by its owning coroutine and cancel its timeout. */
    private fun cancelPendingRequest(deferred: CompletableDeferred<Any?>) {
        val entry = pendingCalls.entries.firstOrNull { it.value.deferred === deferred } ?: return
        if (pendingCalls.remove(entry.key, entry.value)) {
            entry.value.timeoutJob?.cancel()
            deferred.cancel()
            disconnectIfIdleInBackground()
        }
    }

    private val pendingReplayResponses = ConcurrentHashMap<String, ReplayAdmission>()

    private fun replayAdmission(
        method: String,
        params: Map<String, Any>,
    ): ReplayAdmission? {
        if (method != WsMethods.SESSION_RESUME) return null
        val sessionId = (params["session_id"] as? String)?.takeIf { it.isNotBlank() } ?: return null
        val profileId = activeConnectionProfileId ?: return null
        if (!connected.get() || activeConnectionGeneration < 0) return null
        return ReplayAdmission(method, profileId, activeConnectionGeneration, sessionId)
    }

    private fun ReplayAdmission.isCurrent(
        profileId: String,
        generation: Int,
    ): Boolean =
        method == WsMethods.SESSION_RESUME &&
            this.profileId == profileId &&
            connectionGeneration == generation &&
            AuthManager.getSelectedProfileId() == profileId &&
            ActiveSessionHolder.activeSessionId.value == runtimeSessionId

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
        val snapshot =
            synchronized(connectionLock) {
                if (pendingCalls.isEmpty()) return
                pendingCalls.toList().also { pendingCalls.clear() }
            }
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
        var accepted = false
        synchronized(connectionLock) {
            cancelBackgroundIdleCloseLocked()
            if (method == WsMethods.PROMPT_SUBMIT) {
                pendingPromptSessions[id] = params["session_id"] as? String ?: ""
                pendingReply = true
            }
            val ws = webSocket
            if (ws != null && connected.get()) {
                if (!pendingCalls.containsKey(id)) {
                    replayAdmission(method, params)?.let { pendingReplayResponses[id] = it }
                }
                // OkHttp returns false when the frame could not be enqueued —
                // the socket is closing or its outgoing buffer is full. The
                // previous code ignored that and silently dropped the message.
                if (ws.send(json)) {
                    accepted = true
                } else {
                    if (webSocket !== ws || !acceptQueuedMessages.get()) {
                        Log.w(TAG, "WS identity changed while sending — dropping message")
                    } else if (isRetryableMessage(json)) {
                        Log.w(TAG, "WS rejected outgoing message — queuing for reconnect")
                        messageQueue.add(json)
                        accepted = true
                        recoverRejectedSocket(ws)
                    } else {
                        Log.w(TAG, "WS rejected oversized outgoing message — not retrying")
                    }
                }
            } else if (acceptQueuedMessages.get()) {
                if (isRetryableMessage(json)) {
                    Log.d(TAG, "WS disconnected — queuing message")
                    messageQueue.add(json)
                    accepted = true
                } else {
                    Log.w(TAG, "WS disconnected with oversized outgoing message — not queueing")
                }
            } else {
                Log.w(TAG, "WS credentials cleared — dropping outgoing message")
            }
            if (!accepted && method == WsMethods.PROMPT_SUBMIT) {
                pendingPromptSessions.remove(id)
                pendingReply = pendingPromptSessions.isNotEmpty()
            }
            if (!accepted) pendingReplayResponses.remove(id)
            if (accepted && backgroundIdleClosed.compareAndSet(true, false)) {
                startConnectionLocked()
            }
            if (accepted) disconnectIfIdleInBackground()
        }
        return id
    }

    /**
     * Sends a clarification answer only on the exact socket that delivered the
     * prompt. Control replies are never queued across reconnect/profile changes.
     */
    fun respondToClarify(
        sessionId: String,
        clarifyRequestId: String,
        questionId: String?,
        answer: String,
        sourceProfileId: String,
        sourceConnectionGeneration: Int,
    ): Boolean =
        synchronized(connectionLock) {
            val ws = webSocket
            if (!appInForeground.get() ||
                !connected.get() ||
                ws == null ||
                sessionId.isBlank() ||
                clarifyRequestId.isBlank() ||
                sourceConnectionGeneration != activeConnectionGeneration ||
                sourceProfileId != activeConnectionProfileId ||
                sourceProfileId != AuthManager.getSelectedProfileId() ||
                ActiveSessionHolder.activeSessionId.value != sessionId
            ) {
                return@synchronized false
            }
            val params =
                buildMap<String, Any> {
                    put("session_id", sessionId)
                    put("clarify_id", clarifyRequestId)
                    put("request_id", clarifyRequestId)
                    put("response", answer)
                    put("answer", answer)
                    questionId?.let { put("question_id", it) }
                }
            val id = requestId.incrementAndGet().toString()
            val request =
                JsonRpcRequest(
                    id = id,
                    method = WsMethods.CLARIFY_RESPOND,
                    params = params.mapValues { it.value.toJsonElement() },
                )
            ws.send(OkHttpProvider.json.encodeToString(request))
        }

    /** Start an idle-close recovery without reopening a credential boundary. */
    private fun startConnectionLocked() {
        if (intentionalClose.get() || !acceptQueuedMessages.get() || connected.get()) return
        if (_connectionStatus.value == ConnectionStatus.CONNECTING ||
            _connectionStatus.value == ConnectionStatus.RECONNECTING ||
            _connectionStatus.value == ConnectionStatus.AUTH_EXPIRED
        ) {
            return
        }
        ticketAuthRetryUsed.set(false)
        currentBackoff = INITIAL_BACKOFF_MS
        _connectionStatus.value = ConnectionStatus.CONNECTING
        val generation = connectionGeneration.incrementAndGet()
        reconnectJob?.cancel()
        reconnectJob = wsScope.launch { openSocket(generation) }
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
        val generation = connectionGeneration.get()
        connected.set(false)
        _connectionStatus.value = ConnectionStatus.RECONNECTING
        if (ws.queueSize() == 0L) {
            ws.cancel()
            scheduleReconnect(generation)
            return
        }
        if (intentionalClose.get()) return
        outboundDrainJob?.cancel()
        outboundDrainJob =
            wsScope.launch {
                delay(OUTBOUND_DRAIN_TIMEOUT_MS)
                synchronized(connectionLock) {
                    if (generation == connectionGeneration.get() &&
                        !connected.get() && !intentionalClose.get() && webSocket === ws
                    ) {
                        ws.cancel()
                        outboundDrainJob = null
                        scheduleReconnect(generation)
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
            disconnectIfIdleInBackground()
        }
    }

    /** Convenience: submit a user prompt to an existing session. */
    fun sendMessage(
        sessionId: String,
        text: String,
        onSent: ((String) -> Unit)? = null,
        queued: Boolean = false,
    ): String {
        val params =
            buildMap<String, Any> {
                put("session_id", sessionId)
                put("text", text)
                if (queued) put("queued", true)
            }
        return send(
            method = WsMethods.PROMPT_SUBMIT,
            params = params,
            onSent = onSent,
        )
    }

    /**
     * Submit a prompt only if the profile, runtime session, and concrete socket
     * captured when the user accepted the send are still current. This path
     * deliberately never queues or replays across a connection.
     */
    fun sendMessageForConnection(
        binding: ConnectionBinding,
        sessionId: String,
        text: String,
        onSent: ((String) -> Unit)? = null,
    ): Boolean =
        sendForConnection(
            binding = binding,
            runtimeSessionId = sessionId,
            method = WsMethods.PROMPT_SUBMIT,
            params = mapOf("session_id" to sessionId, "text" to text),
            onSent = onSent,
        )

    /** Send an active-turn redirect on the same immutable transport binding. */
    fun sendRedirectForConnection(
        binding: ConnectionBinding,
        sessionId: String,
        text: String,
        onSent: ((String) -> Unit)? = null,
    ): Boolean =
        sendForConnection(
            binding = binding,
            runtimeSessionId = sessionId,
            method = WsMethods.SESSION_REDIRECT,
            params = mapOf("session_id" to sessionId, "text" to text),
            onSent = onSent,
        )

    private fun sendForConnection(
        binding: ConnectionBinding,
        runtimeSessionId: String,
        method: String,
        params: Map<String, Any>,
        onSent: ((String) -> Unit)?,
    ): Boolean =
        synchronized(connectionLock) {
            val ws = webSocket
            if (!connected.get() ||
                ws == null ||
                ws !== binding.socket ||
                activeConnectionProfileId != binding.profileId ||
                activeConnectionGeneration != binding.generation ||
                AuthManager.getSelectedProfileId() != binding.profileId ||
                ActiveSessionHolder.activeSessionId.value != runtimeSessionId
            ) {
                return@synchronized false
            }
            val id = requestId.incrementAndGet().toString()
            val request =
                JsonRpcRequest(
                    id = id,
                    method = method,
                    params = params.mapValues { it.value.toJsonElement() },
                )
            val json = OkHttpProvider.json.encodeToString(request)
            if (!ws.send(json)) return@synchronized false
            if (method == WsMethods.PROMPT_SUBMIT) {
                pendingPromptSessions[id] = runtimeSessionId
                pendingReply = true
            }
            onSent?.invoke(id)
            true
        }

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

    private fun openSocket(generation: Int) {
        if (generation != connectionGeneration.get()) return
        val profileId = AuthManager.getSelectedProfileId()
        val ticketResult = refreshWsTicketIfNeeded()
        if (generation != connectionGeneration.get()) return
        if (profileId != AuthManager.getSelectedProfileId()) {
            restartAfterProfileChange(generation)
            return
        }
        when (ticketResult.status) {
            TicketRefreshStatus.READY -> Unit

            TicketRefreshStatus.AUTHENTICATION_FAILED -> {
                Log.w(TAG, "Aborting openSocket: dashboard authentication rejected")
                synchronized(connectionLock) {
                    if (generation != connectionGeneration.get()) return
                    intentionalClose.set(true)
                    _connectionStatus.value = ConnectionStatus.AUTH_EXPIRED
                }
                return
            }

            TicketRefreshStatus.TRANSIENT_FAILURE -> {
                Log.w(TAG, "Deferring openSocket: WS ticket refresh temporarily unavailable")
                synchronized(connectionLock) {
                    if (generation != connectionGeneration.get()) return
                    _connectionStatus.value = ConnectionStatus.RECONNECTING
                    scheduleReconnect(generation)
                }
                return
            }
        }
        val url =
            try {
                ticketResult.ticket?.let(AuthManager::wsUrlWithCredential)
                    ?: AuthManager.wsUrl()
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "WebSocket blocked by transport policy")
                synchronized(connectionLock) {
                    if (generation != connectionGeneration.get()) return
                    _connectionStatus.value = ConnectionStatus.DISCONNECTED
                }
                return
            }
        if (BuildConfig.DEBUG) Log.d(TAG, "Connecting to WebSocket endpoint")

        val request = Request.Builder().url(url).build()
        var restartForProfileChange = false
        synchronized(connectionLock) {
            when {
                generation != connectionGeneration.get() || intentionalClose.get() -> return
                profileId != AuthManager.getSelectedProfileId() -> {
                    restartForProfileChange = true
                }
                else -> {
                    val eventSocketGeneration = socketGeneration.incrementAndGet()
                    webSocket =
                        OkHttpProvider.websocket.newWebSocket(
                            request,
                            WsListenerImpl(profileId, generation, eventSocketGeneration),
                        )
                    activeConnectionProfileId = profileId
                    activeConnectionGeneration = eventSocketGeneration
                }
            }
        }
        if (restartForProfileChange) restartAfterProfileChange(generation)
    }

    private fun restartAfterProfileChange(generation: Int) {
        val shouldReconnect =
            synchronized(connectionLock) {
                if (generation != connectionGeneration.get()) return
                Log.d(TAG, "Connection profile changed during WebSocket setup; restarting")
                val reconnect = !intentionalClose.get()
                disconnect(clearPendingMessages = true)
                reconnect
            }
        if (shouldReconnect) {
            wsScope.launch { connect() }
        }
    }

    private fun scheduleReconnect(generation: Int) {
        // Reentrant: recoverRejectedSocket() and drainQueue() already hold the
        // lock when they land here.
        synchronized(connectionLock) {
            if (generation != connectionGeneration.get()) return
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
                        openSocket(generation)
                    }
                }
        }
    }

    /**
     * A WebSocket ticket is single-use and can be rejected after the dashboard
     * session has already refreshed successfully. In gated mode, mint and try
     * one fresh ticket before declaring the dashboard session expired.
     */
    private fun handleAuthenticationRejected(generation: Int) {
        synchronized(connectionLock) {
            if (generation != connectionGeneration.get()) return
            val isGated =
                runCatching {
                    AuthManager.isGatedMode()
                }.getOrDefault(false)
            if (isGated && !intentionalClose.get() &&
                ticketAuthRetryUsed.compareAndSet(false, true)
            ) {
                Log.w(TAG, "WebSocket ticket rejected; retrying once with a fresh ticket")
                _connectionStatus.value = ConnectionStatus.RECONNECTING
                wsScope.launch { openSocket(generation) }
                return
            }

            intentionalClose.set(true)
            _connectionStatus.value = ConnectionStatus.AUTH_EXPIRED
        }
    }

    /**
     * Detach a terminal socket and fail requests that were awaiting its
     * responses. The identity check and pending-call drain share
     * [connectionLock] with socket assignment and [request], so a stale
     * callback cannot reject calls registered on a replacement socket.
     */
    private fun detachTerminalSocket(
        socket: WebSocket,
        generation: Int,
    ): Boolean =
        synchronized(connectionLock) {
            if (generation != connectionGeneration.get() || socket !== webSocket) {
                return@synchronized false
            }
            webSocket = null
            activeConnectionProfileId = null
            activeConnectionGeneration = -1
            connected.set(false)
            stopHealthTracking()
            rejectAllPending()
            pendingReplayResponses.clear()
            true
        }

    // ── Listener ─────────────────────────────────────────────────────────

    private class WsListenerImpl(
        private val profileId: String?,
        private val generation: Int,
        private val eventSocketGeneration: Int,
    ) : WebSocketListener() {
        override fun onOpen(
            webSocket: WebSocket,
            response: Response,
        ) {
            synchronized(connectionLock) {
                if (generation != connectionGeneration.get() ||
                    webSocket !== HermesWsClient.webSocket
                ) {
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
        }

        override fun onMessage(
            webSocket: WebSocket,
            text: String,
        ) {
            synchronized(connectionLock) {
                if (generation != connectionGeneration.get() ||
                    webSocket !== HermesWsClient.webSocket
                ) {
                    Log.d(TAG, "Ignoring stale WebSocket message")
                    return
                }
            }
            // Resolve any in-flight `request()` awaiting this RPC result/error
            // (issue #526) before fanning the parsed event out to collectors.
            val parsedEvent =
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
            // Stamp the dispatching profile and socket generation onto every
            // privileged frame — request *and* expiry. Provenance has to come
            // from the connection that delivered the frame; re-deriving it when
            // the user acts would let a profile switch or reconnect rebind a
            // live request (or let a stale expiry clear a fresh dialog).
            val event =
                when (parsedEvent) {
                    is WsEvent.ServerRequest ->
                        parsedEvent.copy(sourceProfileId = profileId, connectionGeneration = eventSocketGeneration)
                    is WsEvent.ServerRequestCancelled ->
                        parsedEvent.copy(sourceProfileId = profileId, connectionGeneration = eventSocketGeneration)
                    is WsEvent.ApprovalRequest ->
                        parsedEvent.copy(sourceProfileId = profileId, connectionGeneration = eventSocketGeneration)
                    is WsEvent.SudoRequest ->
                        parsedEvent.copy(sourceProfileId = profileId, connectionGeneration = eventSocketGeneration)
                    is WsEvent.SudoExpire ->
                        parsedEvent.copy(sourceProfileId = profileId, connectionGeneration = eventSocketGeneration)
                    is WsEvent.SecretRequest ->
                        parsedEvent.copy(sourceProfileId = profileId, connectionGeneration = eventSocketGeneration)
                    is WsEvent.SecretExpire ->
                        parsedEvent.copy(sourceProfileId = profileId, connectionGeneration = eventSocketGeneration)
                    is WsEvent.ClarifyExpire ->
                        parsedEvent.copy(sourceProfileId = profileId, connectionGeneration = eventSocketGeneration)
                    else -> parsedEvent
                }
            synchronized(connectionLock) {
                if (generation != connectionGeneration.get() ||
                    webSocket !== HermesWsClient.webSocket
                ) {
                    Log.d(TAG, "Ignoring stale WebSocket message after parsing")
                    return
                }
                lastPongTimestamp = monotonicTimeMs()
                // A parsed gateway frame proves that the fresh ticket established
                // a usable session. Do not reset on unknown/auth-noise frames: that
                // could otherwise permit an immediate rejection loop.
                if (event !is WsEvent.Unknown) {
                    ticketAuthRetryUsed.set(false)
                }
                when (event) {
                    is WsEvent.RpcResult -> {
                        val replayAdmission =
                            resolvePending(event.id, event.result, null)?.replayAdmission
                                ?: pendingReplayResponses.remove(event.id)
                        val admittedReplay =
                            replayAdmission?.takeIf { admission ->
                                profileId != null && admission.isCurrent(profileId, eventSocketGeneration)
                            }
                        val replaySessionId = admittedReplay?.runtimeSessionId
                        val openRequests =
                            if (replaySessionId != null) {
                                (event.result as? Map<*, *>)?.get("open_requests") as? List<*>
                            } else {
                                null
                            }
                        openRequests?.forEach { entry ->
                            val openRequest = entry as? Map<*, *> ?: return@forEach
                            val openId =
                                (openRequest["id"] as? String)?.takeIf { it.isNotBlank() } ?: return@forEach
                            val method =
                                (openRequest["method"] as? String)?.takeIf { it.isNotBlank() } ?: return@forEach

                            @Suppress("UNCHECKED_CAST")
                            val params = openRequest["params"] as? Map<String, Any?> ?: return@forEach
                            if (params["session_id"] != replaySessionId) return@forEach
                            parsedEvents.tryEmit(
                                SourcedWsEvent(
                                    event =
                                        WsEvent.ServerRequest(
                                            id = openId,
                                            method = method,
                                            params = params,
                                            replayed = true,
                                            sourceProfileId = profileId,
                                            connectionGeneration = eventSocketGeneration,
                                        ),
                                    profileId = profileId,
                                    connectionGeneration = eventSocketGeneration,
                                ),
                            )
                        }
                    }
                    is WsEvent.RpcError -> {
                        pendingPromptSessions.remove(event.id)
                        pendingReplayResponses.remove(event.id)
                        pendingReply = pendingPromptSessions.isNotEmpty()
                        resolvePending(event.id, null, event.error)
                    }
                    is WsEvent.MessageComplete -> {
                        val completed =
                            pendingPromptSessions.entries.firstOrNull {
                                it.value == event.sessionId
                            }
                        if (completed != null) pendingPromptSessions.remove(completed.key)
                        pendingReply = pendingPromptSessions.isNotEmpty()
                    }
                    else -> Unit
                }
                val emitted =
                    parsedEvents.tryEmit(
                        SourcedWsEvent(
                            event =
                                if (event is WsEvent.ClarifyRequest) {
                                    event.copy(
                                        sourceProfileId = profileId,
                                        connectionGeneration = eventSocketGeneration,
                                    )
                                } else {
                                    event
                                },
                            profileId = profileId,
                            connectionGeneration = eventSocketGeneration,
                            storedSessionId =
                                (event as? WsEvent.MessageComplete)?.sessionId?.let(
                                    ActiveSessionHolder::resolveStoredSessionId,
                                ),
                        ),
                    )
                if (!emitted && BuildConfig.DEBUG) {
                    Log.w(TAG, "WebSocket message dropped due to buffer overflow")
                }
            }
            disconnectIfIdleInBackground()
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
            if (!detachTerminalSocket(webSocket, generation)) {
                Log.d(TAG, "Ignoring stale WebSocket onClosed callback")
                return
            }
            Log.i(TAG, "WebSocket closed: $code")
            if (code == 4001 || code == 4401 ||
                reason.contains("unauthorized", ignoreCase = true) ||
                reason.startsWith("auth:", ignoreCase = true)
            ) {
                handleAuthenticationRejected(generation)
            } else {
                synchronized(connectionLock) {
                    if (generation != connectionGeneration.get()) return
                    _connectionStatus.value = ConnectionStatus.RECONNECTING
                    scheduleReconnect(generation)
                }
            }
        }

        override fun onFailure(
            webSocket: WebSocket,
            t: Throwable,
            response: Response?,
        ) {
            if (!detachTerminalSocket(webSocket, generation)) {
                Log.d(TAG, "Ignoring stale WebSocket onFailure callback")
                return
            }
            Log.e(TAG, "WebSocket failure (${t.javaClass.simpleName})")
            val code = response?.code ?: 0
            if (code == 401 || t.message?.contains(
                    "401",
                ) == true || t.message?.contains("unauthorized", ignoreCase = true) == true
            ) {
                handleAuthenticationRejected(generation)
            } else {
                synchronized(connectionLock) {
                    if (generation != connectionGeneration.get()) return
                    _connectionStatus.value = ConnectionStatus.RECONNECTING
                    scheduleReconnect(generation)
                }
            }
        }
    }
}
