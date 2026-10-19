// Modified from Hy4ri/hermes-mobile for this fork; see NOTICE.

package com.m57.hermescontrol.ui.chat

import android.app.Application
import android.net.Uri
import android.util.Base64
import android.util.Base64OutputStream
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.AuthSessionState
import com.m57.hermescontrol.data.local.HermesDatabase
import com.m57.hermescontrol.data.local.SlashUsageStore
import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.data.model.AttachmentSource
import com.m57.hermescontrol.data.model.ModelProvider
import com.m57.hermescontrol.data.model.PinnedModel
import com.m57.hermescontrol.data.model.SessionMessage
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.AuthPayloads
import com.m57.hermescontrol.data.remote.GatewayFile
import com.m57.hermescontrol.data.remote.GatewayFileClient
import com.m57.hermescontrol.data.remote.GatewayFileResult
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.OkHttpProvider
import com.m57.hermescontrol.data.remote.readBytesLimited
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.data.session.ActiveSessionHolder
import com.m57.hermescontrol.data.ws.CommandBlocklist
import com.m57.hermescontrol.data.ws.CommandCatalog
import com.m57.hermescontrol.data.ws.ConnectionBinding
import com.m57.hermescontrol.data.ws.ConnectionStatus
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.JsonRpcError
import com.m57.hermescontrol.data.ws.PrivilegedRequestBinding
import com.m57.hermescontrol.data.ws.ServerRequestBinding
import com.m57.hermescontrol.data.ws.WsEvent
import com.m57.hermescontrol.data.ws.WsMethods
import com.m57.hermescontrol.data.ws.toAny
import com.m57.hermescontrol.data.ws.toJsonElement
import com.m57.hermescontrol.ui.common.SecureGatewayMediaRequest
import com.m57.hermescontrol.ui.common.SecureMediaOpenRoute
import com.m57.hermescontrol.ui.common.routeAttachmentOpen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "ChatViewModel"
private const val MESSAGE_PAGE_SIZE = 150

/**
 * How long an unacknowledged `session.create` waits before it is retried.
 *
 * `session.create` is fire-and-forget: [HermesWsClient.send] can queue it for a
 * later socket, or drop it outright once credentials were cleared. Chat cannot
 * recover on its own from a dropped create — it has already discarded the old
 * session id — so the request needs its own liveness deadline.
 */
private const val SESSION_CREATE_TIMEOUT_MS = 10_000L

/** Total `session.create` attempts, including the first, before giving up. */
private const val SESSION_CREATE_MAX_ATTEMPTS = 3

/**
 * Gateway error code for "agent does not support active-turn redirect"
 * (hermes-agent `tui_gateway/server.py`, `session.redirect` handler).
 */
private const val REDIRECT_UNSUPPORTED_CODE = 4010
private const val MAX_RETIRED_RESUME_REQUESTS = 64

private data class CatalogFence(
    val binding: ConnectionBinding,
    val profileId: String,
    val runtimeSessionId: String?,
    val generation: Long,
    val requestGeneration: Long,
)

private data class PendingRpcRequest(
    val method: String,
    val catalogFence: CatalogFence? = null,
    val resumeSessionId: String? = null,
    val resumeFence: ResumeFence? = null,
    /** Runtime session that initiated a branch, used to fence late responses. */
    val branchSessionId: String? = null,
    /** Session/text captured for a session.redirect so a 4010 rejection can resend. */
    val redirectSessionId: String? = null,
    val redirectText: String? = null,
    val redirectConnectionBinding: ConnectionBinding? = null,
    /** Attempt generation for a session.create, used to fence retried answers. */
    val createGeneration: Long? = null,
)

private data class ResumeFence(
    val storageSessionId: String,
    val runtimeSessionId: String?,
    val profileId: String,
    val connectionBinding: ConnectionBinding,
    val generation: Long,
    val transcriptRevision: Long,
)

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val currentSessionId: String? = null,
    val isSessionReady: Boolean = false,
    val sessions: List<SessionUi> = emptyList(),
    val chatTitle: String = "Hermes",
    val connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val isAgentTyping: Boolean = false,
    val isThinking: Boolean = false,
    val thinkingText: String = "",
    val isLoading: Boolean = false,
    val isLoadingOlder: Boolean = false,
    val hasOlderMessages: Boolean = false,
    /** Standalone streaming message — rendered after the main list. */
    val streamingMessage: ChatMessage? = null,
    val errorMessage: String? = null,
    // Background job completion toast (issue #527) — non-blocking snackbar
    val backgroundCompleteMessage: String? = null,
    // Attachment open failure — surfaced as a non-blocking snackbar (issue #724)
    val openError: String? = null,
    val openingAttachmentPath: String? = null,
    val mediaPlayerRequest: SecureGatewayMediaRequest? = null,
    val clarifyRequest: ClarifyUi? = null,
    // Sudo / secret prompts — surfaced as dialogs (issue #524)
    val sudoPrompt: SudoPromptUi? = null,
    val secretPrompt: SecretPromptUi? = null,
    val vaultPrompt: VaultPromptUi? = null,
    val showSessionPicker: Boolean = false,
    // Search state
    val isSearchActive: Boolean = false,
    val searchQuery: String = "",
    val searchMatchIndices: List<Int> = emptyList(),
    val currentSearchMatchIndex: Int = -1,
    // Cached settings
    val typingEffectEnabled: Boolean = true,
    val typingEffectDelayMs: Int = 30,
    // Commands catalog
    val commandCatalog: CommandCatalog = CommandCatalog(),
    val slashUsageCounts: Map<String, Int> = emptyMap(),
    val openHistoryRequested: Boolean = false,
    // In-session model picker (issue #589) — surfaced when the user types /model
    // or taps the composer model chip. Mirror of the global model screen's
    // picker, but the selection hot-swaps the CURRENT session via config.set.
    val showModelPicker: Boolean = false,
    val modelPickerProviders: List<ModelProvider> = emptyList(),
    val modelPickerPinned: List<PinnedModel> = emptyList(),
    val modelPickerLoading: Boolean = false,
    val modelInventoryResolved: Boolean = false,
    // Current session's active provider/model label, used by the composer
    // model chip and picker title.
    val currentSessionModel: String? = null,
    val modelSwitchConfirmation: ModelSwitchConfirmation? = null,
    // Reasoning effort level for the current session
    val reasoningLevel: String? = null,
    val terminalBackend: String? = null,
    // Attachment state
    val pendingAttachments: List<Attachment> = emptyList(),
    // Reaction animation — set when a reaction WS event arrives, auto-clears
    val reactionKind: String? = null,
    /** Monotonic trigger ID so consecutive same-kind reactions re-animate. */
    val reactionTriggerId: Long = 0L,
    /** Subagent delegation indicators (issue #538) — transient UI state. */
    val subagentIndicators: List<SubagentIndicator> = emptyList(),
    /**
     * Context-window meter state. Fed by the gateway `usage` block on
     * `session.info` / `message.complete`; null until the session reports one.
     */
    val contextUsage: ContextUsage? = null,
    /**
     * Denominator fallback from `GET /api/model/info`, used only while the
     * session has not yet reported a live `context_max` over the WebSocket.
     */
    val modelContextLength: Long? = null,
    /** Provider-qualified model identity associated with [modelContextLength]. */
    val modelContextLengthModel: String? = null,
    /** Whether the tappable context breakdown sheet is open. */
    val showContextDetail: Boolean = false,
    /** Agent todo / plan items (issue #736). */
    val todos: List<TodoItem> = emptyList(),
    /** One-shot composer restoration produced by a successful `/undo`. */
    val pendingPrefillText: String? = null,
    val undoConfirmCount: Int? = null,
) {
    /** Convenience — derived from [connectionStatus]. */
    val isConnected: Boolean get() = connectionStatus == ConnectionStatus.CONNECTED
}

data class SessionUi(
    val id: String,
    val title: String,
    val messageCount: Int = 0,
    val parentSessionId: String? = null,
    val depth: Int = 0,
)

data class ClarifyUi(
    val text: String,
    val options: List<String>,
    val clarifyId: String? = null,
    val questionId: String? = null,
    val multiSelect: Boolean = false,
    val questions: List<ClarifyQuestionUi> = emptyList(),
    val sessionId: String? = null,
    val sourceProfileId: String? = null,
    val connectionGeneration: Int? = null,
    val serverRequestBinding: ServerRequestBinding? = null,
    val lockedAnswers: Map<String, String> = emptyMap(),
) {
    val resolvedQuestions: List<ClarifyQuestionUi>
        get() =
            questions.ifEmpty {
                listOf(
                    ClarifyQuestionUi(
                        qid = questionId ?: "q0",
                        question = text,
                        choices = options,
                        multiSelect = multiSelect,
                    ),
                )
            }
}

data class ClarifyQuestionUi(
    val qid: String,
    val question: String,
    val choices: List<String> = emptyList(),
    val multiSelect: Boolean = false,
)

/**
 * String sent to the agent when a clarify prompt is dismissed (the Dismiss
 * button). This is a *reject* — "I'm not answering this question" — NOT an
 * instruction to proceed. Deliberately NOT the CLI's interrupt sentinel
 * ("...Use your best judgement to proceed."): a mobile Dismiss is a
 * skip-the-question gesture, not an interrupt of the whole turn. The agent is
 * unblocked but told no answer was given, so it re-asks or backs off rather
 * than charging ahead.
 */
private const val CLARIFY_DISMISS_RESPONSE = "The user cancelled — no answer provided."

/**
 * Transient — not persisted. Holds a pending sudo.password request.
 *
 * Carries only the routing binding, never the password: the entered value lives
 * in the dialog's own composition and in the single transient RPC call.
 */
data class SudoPromptUi(
    val binding: PrivilegedRequestBinding,
    val serverRequestBinding: ServerRequestBinding? = null,
    val isSubmitting: Boolean = false,
) {
    val requestId: String get() = binding.requestId
    val fullBinding: Any get() = serverRequestBinding ?: binding
}

/**
 * Transient — not persisted. Holds a pending secret (token/password) request.
 *
 * Like [SudoPromptUi], this holds no secret value — only the request's routing
 * binding and the gateway's own non-secret prompt labels.
 */
data class SecretPromptUi(
    val binding: PrivilegedRequestBinding,
    val envVar: String? = null,
    val prompt: String? = null,
    val serverRequestBinding: ServerRequestBinding? = null,
    val isSubmitting: Boolean = false,
) {
    val fullBinding: Any get() = serverRequestBinding ?: binding
    val requestId: String get() = binding.requestId
}

data class VaultPromptUi(
    val binding: ServerRequestBinding,
    val method: String,
    val title: String? = null,
    val prompt: String? = null,
    val identifier: String? = null,
    val requestedOrigin: String? = null,
    val isSubmitting: Boolean = false,
) {
    val hasValidRequestedOrigin: Boolean
        get() = method != "vault.save_login" || requestedOrigin.isValidWebOrigin()
}

private fun String?.isValidWebOrigin(): Boolean {
    val value = this ?: return false
    if (value.isBlank() || value != value.trim()) return false
    val uri = runCatching { java.net.URI(value) }.getOrNull() ?: return false
    return (uri.scheme.equals("https", ignoreCase = true) || uri.scheme.equals("http", ignoreCase = true)) &&
        uri.host != null &&
        uri.rawAuthority?.endsWith(":") == false &&
        (uri.port == -1 || uri.port in 1..65535) &&
        uri.rawUserInfo == null &&
        uri.rawPath.isNullOrEmpty() &&
        uri.rawQuery == null &&
        uri.rawFragment == null
}

/** Expensive-model confirmation returned by the gateway's config.set RPC. */
data class ModelSwitchConfirmation(
    val message: String,
    val spec: String,
    val displayLabel: String?,
    val sessionId: String,
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChatViewModel(
    application: Application,
    private val startCleanup: Boolean,
    repo: ChatPersistenceRepository =
        ChatPersistenceRepository(
            HermesDatabase.get(application).chatMessageDao(),
        ),
    slashUsageStore: SlashUsageStore = SlashUsageStore(application.applicationContext),
    searchDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default,
    /**
     * Retry delay for an unacknowledged `session.create`.
     *
     * `null` (production) means [SESSION_CREATE_TIMEOUT_MS], still subject to
     * the historical unit-test skip. Tests inject a small value to opt into the
     * retry path explicitly, so existing suites keep their previous timing.
     */
    private val sessionCreateRetryDelayMs: Long? = null,
    private val selectedProfileId: () -> String = {
        AuthManager.getSelectedProfileId() ?: AuthManager.DEFAULT_PROFILE_ID
    },
    selectedProfileIds: kotlinx.coroutines.flow.Flow<String> = AuthManager.selectedProfileIdFlow,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, startCleanup = true)

    // ── Internal state ───────────────────────────────────────────────────
    private val _uiState = MutableStateFlow(ChatUiState())

    private val _streamingState = MutableStateFlow(StreamingState())

    /** Maps an in-flight RPC id to the context needed to handle its result. */
    private val pendingRequests = ConcurrentHashMap<String, PendingRpcRequest>()
    private var catalogRequestGeneration = 0L
    private val resumeRequestLock = Any()
    private val retiredResumeRequests = LinkedHashSet<String>()

    /** Runtime TUI session returned by session.resume; Desktop storage keeps the original ID. */
    private var runtimeSessionId: String? = null
    private var loadedMessageOffset = 0
    private var latestPaging = false
    private var isSyncingMessages = false
    private var syncCounter = 0L

    @Volatile private var activeSyncOwner: Long? = null
    private var syncJob: Job? = null
    private var conversationGeneration = 0L
    private val historyLoadJobs = mutableSetOf<Job>()
    private var historyRefreshCounter = 0L

    @Volatile private var activeHistoryRefreshOwner: Long? = null
    private var historyRefreshJob: Job? = null
    private var cacheLoadCounter = 0L

    @Volatile private var activeCacheLoadOwner: Long? = null
    private var cacheLoadJob: Job? = null
    private var activeCacheMessageIds: Set<String> = emptySet()
    private var olderLoadCounter = 0L

    @Volatile private var activeOlderLoadOwner: Long? = null
    private var olderLoadJob: Job? = null
    val streamingState: StateFlow<StreamingState> = _streamingState.asStateFlow()

    /** Tracks the auto-clear coroutine for reaction animations. */
    private var reactionClearJob: Job? = null

    /**
     * Local expiry timers for live approval cards. Request ids can be reused,
     * so timer ownership includes the message and complete transport binding.
     */
    private data class ApprovalTimerKey(
        val messageId: String,
        val binding: PrivilegedRequestBinding,
    )

    private val approvalExpiryJobs = ConcurrentHashMap<ApprovalTimerKey, Job>()

    private val wsClient = HermesWsClient

    // ── Session persistence ──────────────────────────────────────────────
    private val repo: ChatPersistenceRepository = repo
    private val slashUsageStore: SlashUsageStore = slashUsageStore
    private val slashUsageProfileIds = selectedProfileIds.distinctUntilChanged()
    private val slashDispatcher = SlashCommandDispatcher()
    private val searchDelegate =
        ChatSearchDelegate(
            scope = viewModelScope,
            uiState = _uiState,
            dispatcher = searchDispatcher,
        )
    private val attachmentsDelegate = ChatAttachmentsDelegate(uiState = _uiState)

    /**
     * Model options cached from GET /api/model/options so the in-session model
     * picker (issue #589) opens instantly when the user types /model or taps the
     * top-bar chip. Preloaded at GatewayReady; refreshed on open if empty.
     */
    private var cachedModelOptions: List<ModelProvider> = emptyList()

    private val streamingController =
        ChatStreamingController(
            scope = viewModelScope,
            uiState = _uiState,
            streamingState = _streamingState,
            isCurrentSession = { sessionId -> isCurrentSession(sessionId) },
            isTestEnvironment = { isTestEnvironment() },
        )

    // ── Public state ─────────────────────────────────────────────────────

    /**
     * Combined UI state: merges internal state with the WS connection status
     * flow so there is a single source of truth for connection state.
     */
    val uiState: StateFlow<ChatUiState> =
        combine(
            _uiState,
            wsClient.connectionStatus,
        ) { state, connStatus ->
            state.copy(connectionStatus = connStatus)
        }.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            _uiState.value,
        )

    /**
     * Session ID to resume when the WebSocket connects. Set synchronously by
     * [ChatScreen] via `SideEffect` during composition — before any WS event
     * can be processed. This prevents the race where [GatewayReady] fires
     * before ChatScreen's `LaunchedEffect` can call [switchSession], causing
     * [createNewSession] to create an empty chat that overwrites the
     * notification session (issue #240).
     */
    var initialSessionId: String? = null

    init {
        refreshSettings()

        viewModelScope.launch {
            slashUsageProfileIds.flatMapLatest(slashUsageStore::counts).collect { counts ->
                _uiState.update { it.copy(slashUsageCounts = counts) }
            }
        }

        connectWebSocket(setLoading = false)
        viewModelScope.launch {
            wsClient.events.collect { event ->
                try {
                    handleWsEvent(event)
                } catch (e: Exception) {
                    android.util.Log.e("ChatVM", "Uncaught in event loop", e)
                    _uiState.update { it.copy(isLoading = false) }
                }
            }
        }
        // B7 (Jun 30 2026, kanban t_connection_loading): clear loading state on connection failure or status change
        viewModelScope.launch {
            wsClient.connectionStatus.collect { status ->
                if (status == ConnectionStatus.DISCONNECTED ||
                    status == ConnectionStatus.RECONNECTING ||
                    status == ConnectionStatus.NO_NETWORK ||
                    status == ConnectionStatus.AUTH_EXPIRED
                ) {
                    retireSync()
                    _uiState.value.currentSessionId?.let(repo::invalidateReplacementWrites)
                    conversationGeneration++
                    invalidateResumeRequests()
                    clearPrivilegedControls()
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isSessionReady = false,
                        )
                    }
                    // Fail any in-flight awaited RPCs so callers don't hang
                    // across the disconnect (delegated to HermesWsClient, issue #526).
                    wsClient.rejectAllPending()
                }
            }
        }
        if (wsClient.connectionStatus.value == ConnectionStatus.CONNECTED) {
            handleGatewayReady()
        }
    }

    // ── Connection ───────────────────────────────────────────────────────

    private fun connectWebSocket(setLoading: Boolean = false) {
        // In loopback (token) mode the session token is the WS credential and
        // must be present before connecting. In gated (ticket) mode the ticket
        // is minted fresh by HermesWsClient.refreshWsTicketIfNeeded() from the
        // persisted session cookie, so getToken() is expected to be empty here
        // and must NOT block the connect (issue #640: chat showed "reconnect"
        // immediately after basic-auth login because this guard returned early).
        val isGated =
            runCatching { AuthManager.isGatedMode() }
                .getOrNull() ?: false
        if (!isGated) {
            val token = AuthManager.getToken() ?: return
            if (token.isBlank()) return
        }

        // Don't disturb an already-working (or already-recovering) connection.
        // HermesWsClient is a global singleton shared by every tab; the chat tab
        // is recreated on every open, so calling connect() here must be a no-op
        // unless the singleton is in a terminal state. Re-entering connect() while
        // it is CONNECTING/RECONNECTING races the in-flight socket and can leave
        // the status stuck on RECONNECTING (see HermesWsClient.connect).
        val status = wsClient.connectionStatus.value
        if (status == ConnectionStatus.CONNECTING ||
            status == ConnectionStatus.RECONNECTING ||
            status == ConnectionStatus.AUTH_EXPIRED
        ) {
            return
        }

        if (setLoading) {
            _uiState.update { it.copy(isLoading = true) }
        }

        viewModelScope.launch(Dispatchers.IO) {
            wsClient.connect()
        }

        // B7 (Jun 30 2026, kanban t_connection_loading): safety timeout to clear spinner if connection hangs
        if (!isTestEnvironment()) {
            viewModelScope.launch {
                delay(10_000L)
                if (_uiState.value.isLoading) {
                    _uiState.update { it.copy(isLoading = false) }
                }
            }
        }
    }

    // ── WS Event Handling ────────────────────────────────────────────────

    private fun handleGatewayReady() {
        _uiState.update { it.copy(isLoading = false) }
        loadSessions()
        fetchCommandCatalog()
        fetchModelContextLength()
        preloadModelOptions()
        val currentId = _uiState.value.currentSessionId
        if (currentId != null) {
            val resumeFence = captureResumeFence(currentId)
            viewModelScope.launch(Dispatchers.IO) {
                if (resumeFence != null && isResumeFenceCurrent(resumeFence)) {
                    wsClient.send(
                        WsMethods.SESSION_RESUME,
                        mapOf("session_id" to currentId, "omit_messages" to true),
                        onSent = { id -> trackResumeRequest(id, resumeFence) },
                    )
                }
            }
            loadSessionMessages(currentId)
        } else {
            val initial = initialSessionId
            if (!initial.isNullOrBlank()) {
                initialSessionId = null
                switchSession(initial)
            } else {
                createNewSession(setLoading = false)
            }
        }
    }

    private fun handleWsEvent(event: WsEvent) {
        val rpcId =
            when (event) {
                is WsEvent.RpcResult -> event.id
                is WsEvent.RpcError -> event.id
                else -> null
            }
        if (rpcId != null && consumeRetiredResumeRequest(rpcId)) return
        val pending = rpcId?.let(pendingRequests::get)
        // Fence both results and errors before the generic reducer can mutate UI.
        if (pending?.method == WsMethods.COMMANDS_CATALOG &&
            pending.catalogFence?.let(::isCatalogFenceCurrent) != true
        ) {
            pendingRequests.remove(rpcId)
            return
        }
        if (pending?.method == WsMethods.SESSION_RESUME &&
            pending.resumeFence?.let(::isResumeFenceCurrent) != true
        ) {
            pendingRequests.remove(rpcId)
            return
        }
        if (
            pending?.method == WsMethods.SESSION_BRANCH &&
            pending.branchSessionId != null &&
            pending.branchSessionId != runtimeSessionId
        ) {
            pendingRequests.remove(rpcId)
            return
        }

        val transitionSessionId =
            when (event) {
                is WsEvent.MessageStart -> event.sessionId
                is WsEvent.MessageComplete -> event.sessionId
                is WsEvent.MessageDone -> event.sessionId
                is WsEvent.ToolStart -> event.sessionId
                else -> null
            }
        if (transitionSessionId != null && !isCurrentSession(transitionSessionId)) {
            return
        }

        when (event) {
            is WsEvent.MessageStart,
            is WsEvent.MessageComplete,
            is WsEvent.MessageDone,
            is WsEvent.ToolStart,
            -> streamingController.flushPendingReasoning()

            else -> Unit
        }

        // First, let the reducer compute the new state and any effects
        val result =
            ChatWsEventReducer.reduce(
                _uiState.value,
                _streamingState.value,
                event,
                runtimeSessionId ?: _uiState.value.currentSessionId,
            )

        // Apply the new state
        _uiState.update { result.state }
        _streamingState.update { result.streamingState }

        // Process side-effects from the reducer
        for (effect in result.effects) {
            when (effect) {
                is ReducerEffect.PersistMessage -> {
                    val acceptedRevision = repo.replacementGeneration(effect.sessionId)
                    viewModelScope.launch(Dispatchers.IO) {
                        repo.persistMessage(effect.message, effect.sessionId, acceptedRevision)
                    }
                }

                is ReducerEffect.CreateNewSession -> {
                    createNewSession()
                }

                is ReducerEffect.LoadSessions -> {
                    loadSessions()
                }

                is ReducerEffect.RefreshSessions -> {
                    loadSessions()
                }
                is ReducerEffect.AttachHostMedia -> {
                    // Issue #724: turn host-path MEDIA: directives into real
                    // attachments (images inline, every other file tappable)
                    // via the gateway /api/files/download endpoint. Works on a
                    // remote phone too.
                    val acceptedRevision = repo.replacementGeneration(effect.sessionId)
                    viewModelScope.launch(Dispatchers.IO) {
                        attachHostMedia(effect.sessionId, effect.messageId, acceptedRevision)
                    }
                }
            }
        }

        // Handle complex events that need ViewModel-specific context
        when (event) {
            is WsEvent.ServerRequest -> handleServerRequest(event)

            is WsEvent.ServerRequestCancelled -> handleServerRequestCancelled(event)

            is WsEvent.GatewayReady -> {
                handleGatewayReady()
            }

            is WsEvent.SessionInfo -> {
                if (isCurrentSession(event.sessionId)) {
                    val info = event.data

                    val usage = info?.get("usage") as? Map<*, *>
                    val model = (info?.get("model") as? String)?.trim().orEmpty()
                    val provider = (info?.get("provider") as? String)?.trim().orEmpty()
                    val reasoningEffort = (info?.get("reasoning_effort") as? String)?.trim()
                    val terminalBackend = (info?.get("terminal_backend") as? String)?.trim()
                    _uiState.update { state ->
                        state.copy(
                            currentSessionModel =
                                if (model.isNotEmpty()) {
                                    if (provider.isEmpty() || model.startsWith("$provider/")) {
                                        model
                                    } else {
                                        "$provider/$model"
                                    }
                                } else {
                                    state.currentSessionModel
                                },
                            reasoningLevel = reasoningEffort?.takeIf { it.isNotEmpty() },
                            terminalBackend = terminalBackend?.takeIf { it.isNotEmpty() } ?: state.terminalBackend,
                            contextUsage = parseContextUsage(usage, state.contextUsage),
                        )
                    }
                }
            }

            is WsEvent.MessageToken -> {
                streamingController.handleMessageToken(event)
            }

            is WsEvent.ThinkingDelta -> {
                streamingController.handleThinkingDelta(event)
            }

            is WsEvent.ReasoningDelta -> {
                streamingController.handleReasoningDelta(event)
            }

            is WsEvent.MessageStart -> {
                streamingController.beginStreamingMessage()
            }

            is WsEvent.MessageComplete -> {
                // Buffers cleared before reduce; ViewModel resets them after
                streamingController.resetStreaming()
                if (isCurrentSession(event.sessionId)) {
                    // End of turn is when the gateway recomputes window
                    // occupancy, so this frame carries the freshest gauge the
                    // client will see before the next `session.info`.
                    _uiState.update { state ->
                        state.copy(contextUsage = parseContextUsage(event.usage, state.contextUsage))
                    }
                }
            }

            is WsEvent.MessageDone -> {
                streamingController.resetStreaming()
            }

            is WsEvent.ToolStart -> {
                // Reset streaming state when a tool starts
                streamingController.resetStreaming()
            }

            is WsEvent.RpcResult -> {
                handleRpcResult(event.id, event.result)
            }

            is WsEvent.RpcError -> {
                handleRpcError(event.id, event.error)
            }

            is WsEvent.SessionUpdated -> {
                loadSessions()
            }

            is WsEvent.SessionUsage -> {
                if (isCurrentSession(event.sessionId)) {
                    val usage =
                        (event.data?.get("usage") as? Map<*, *>)
                            ?: event.data
                    _uiState.update { state ->
                        state.copy(
                            contextUsage =
                                parseContextUsage(usage, state.contextUsage),
                        )
                    }
                }
            }

            is WsEvent.ClarifyRequest -> {
                _uiState.update {
                    it.copy(
                        isAgentTyping = false,
                    )
                }
                _streamingState.update { StreamingState() }
                streamingController.resetStreaming()
            }

            is WsEvent.ApprovalRequest -> {
                handleApprovalRequest(event)
            }

            is WsEvent.SudoRequest -> {
                handleSudoRequest(event)
            }

            is WsEvent.SudoExpire -> {
                _uiState.update { state ->
                    val expired =
                        state.sudoPrompt?.binding?.let {
                            expiryMatches(
                                binding = it,
                                requestId = event.requestId,
                                sessionId = event.sessionId,
                                profileId = event.sourceProfileId,
                                generation = event.connectionGeneration,
                            )
                        } ?: false
                    if (expired) state.copy(sudoPrompt = null) else state
                }
            }

            is WsEvent.SecretRequest -> {
                handleSecretRequest(event)
            }

            is WsEvent.SecretExpire -> {
                _uiState.update { state ->
                    val expired =
                        state.secretPrompt?.binding?.let {
                            expiryMatches(
                                binding = it,
                                requestId = event.requestId,
                                sessionId = event.sessionId,
                                profileId = event.sourceProfileId,
                                generation = event.connectionGeneration,
                            )
                        } ?: false
                    if (expired) state.copy(secretPrompt = null) else state
                }
            }

            is WsEvent.PrivilegedRequestRejected -> {
                // A privileged frame without a bindable request id. Never
                // surfaced and never answered — the parser already dropped it.
            }

            is WsEvent.GatewayError -> {
                // Reducer already set errorMessage; no extra VM work needed.
            }

            is WsEvent.BackgroundComplete -> {
                // Reducer already set backgroundCompleteMessage; the UI observes
                // it via a LaunchedEffect and triggers the snackbar.
            }

            is WsEvent.ReactionEvent -> {
                // Cancel any previous auto-clear to avoid race (agy finding #1)
                reactionClearJob?.cancel()
                _uiState.update {
                    it.copy(
                        reactionKind = event.kind,
                        reactionTriggerId = it.reactionTriggerId + 1L,
                    )
                }
                // Auto-clear after the animation duration
                reactionClearJob =
                    viewModelScope.launch {
                        delay(2_000L)
                        _uiState.update { it.copy(reactionKind = null) }
                    }
            }

            else -> { /* reducer handles these */ }
        }
    }

    // ── Message streaming ────────────────────────────────────────────────

    /**
     * Checks if an incoming WS event belongs to the currently active
     * session. Returns true if the event should be processed.
     */
    private fun isCurrentSession(eventSessionId: String?): Boolean {
        // If the event has no session ID, process it (legacy compatibility)
        if (eventSessionId == null) return true
        return eventSessionId == runtimeSessionId || eventSessionId == _uiState.value.currentSessionId
    }

    // ── RPC response handling ────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun handleRpcResult(
        id: String,
        result: Any?,
    ) {
        val request = pendingRequests.remove(id) ?: return
        val method = request.method
        when (method) {
            WsMethods.SESSION_CREATE -> {
                // A retried create can be answered twice, and a create issued
                // before a session switch can be answered after it. Only the
                // newest attempt may install a session.
                val generation = request.createGeneration
                if (generation != null && generation != sessionCreateCounter) {
                    Log.d(TAG, "Ignoring session.create result from a superseded attempt")
                    return
                }
                val resultMap = result as? Map<String, Any?> ?: return
                val runtimeId = resultMap["session_id"] as? String ?: return
                val storageId = resultMap["stored_session_id"] as? String ?: runtimeId
                // The request is answered — stand the retry deadline down.
                sessionCreateJob?.cancel()
                sessionCreateJob = null
                runtimeSessionId = runtimeId
                fetchCommandCatalog()
                _uiState.update {
                    it.copy(
                        currentSessionId = storageId,
                        isSessionReady = true,
                        isLoading = false,
                        messages = emptyList(),
                        subagentIndicators = emptyList(),
                        todos = emptyList(),
                        chatTitle = "Hermes",
                        currentSessionModel = null,
                        terminalBackend = null,
                        modelSwitchConfirmation = null,
                    )
                }
                // Mirror the active session id app-wide so session-scoped
                // drawer screens (e.g. Processes, issue #532) can issue
                // session-scoped RPCs. See ActiveSessionHolder.
                ActiveSessionHolder.set(runtimeId, storageId)
                _streamingState.update { StreamingState() }
                addSystemMessage("Session created", persist = true)
                loadSessions()
            }

            WsMethods.SESSION_BRANCH -> {
                val resultMap = result as? Map<String, Any?> ?: return
                val newId = resultMap["session_id"] as? String ?: return

                val info = resultMap["info"] as? Map<*, *>
                val usage = info?.get("usage") as? Map<*, *>

                val model = (info?.get("model") as? String)?.trim().orEmpty()
                val provider = (info?.get("provider") as? String)?.trim().orEmpty()
                val reasoningEffort = (info?.get("reasoning_effort") as? String)?.trim()
                val terminalBackend = (info?.get("terminal_backend") as? String)?.trim()
                clearPrivilegedControls()
                runtimeSessionId = newId
                fetchCommandCatalog()
                _uiState.update {
                    it.copy(
                        currentSessionId = newId,
                        isSessionReady = true,
                        isLoading = false,
                        messages = emptyList(),
                        subagentIndicators = emptyList(),
                        todos = emptyList(),
                        chatTitle = (resultMap["title"] as? String)?.takeIf { t -> t.isNotBlank() } ?: "Hermes",
                        currentSessionModel =
                            model.takeIf { it.isNotEmpty() }?.let { resolvedModel ->
                                if (provider.isEmpty() || resolvedModel.startsWith("$provider/")) {
                                    resolvedModel
                                } else {
                                    "$provider/$resolvedModel"
                                }
                            },
                        reasoningLevel = reasoningEffort?.takeIf { it.isNotEmpty() },
                        terminalBackend = terminalBackend?.takeIf { it.isNotEmpty() },
                        // Never use the parent snapshot as the previous value:
                        // the branch response carries child-scoped info.
                        contextUsage = parseContextUsage(usage, previous = null),
                    )
                }
                ActiveSessionHolder.set(newId)
                _streamingState.update { StreamingState() }
                addSystemMessage("Session branched", persist = true)
                loadSessionMessages(newId)
                loadSessions()
            }

            WsMethods.SESSION_LIST -> {
                val resultMap = result as? Map<String, Any?> ?: return
                val sessionsList = resultMap["sessions"] as? List<Map<String, Any?>> ?: return
                val sessions =
                    sessionsList.map { s ->
                        SessionUi(
                            id = s["id"] as? String ?: "",
                            title = s["title"] as? String ?: "Untitled",
                            messageCount = (s["message_count"] as? Double)?.toInt() ?: 0,
                        )
                    }
                _uiState.update { state ->
                    val newTitle = sessions.find { s -> s.id == state.currentSessionId }?.title
                    state.copy(
                        sessions = sessions,
                        chatTitle = newTitle ?: state.chatTitle,
                    )
                }
            }

            WsMethods.SESSION_RESUME -> {
                val resumeFence = request.resumeFence ?: return
                if (!isResumeFenceCurrent(resumeFence)) return
                val resultMap = result as? Map<String, Any?>
                val requestedSessionId = request.resumeSessionId
                val selectedSessionId = _uiState.value.currentSessionId
                if (
                    requestedSessionId != null &&
                    selectedSessionId != requestedSessionId
                ) {
                    return
                }
                val sessionId =
                    (resultMap?.get("resumed") as? String)
                        ?: requestedSessionId
                        ?: selectedSessionId
                runtimeSessionId = resultMap?.get("session_id") as? String
                fetchCommandCatalog()

                // Parse the session-scoped snapshot returned by the backend.
                val infoMap = resultMap?.get("info") as? Map<*, *>
                val model = infoMap?.get("model") as? String
                val provider = infoMap?.get("provider") as? String
                val reasoningEffort = infoMap?.get("reasoning_effort") as? String
                val terminalBackend = infoMap?.get("terminal_backend") as? String
                val usage = infoMap?.get("usage") as? Map<*, *>

                // B8 (Jun 20 2026, kanban t_session_resume): do NOT reload
                // cached messages here — switchSession() already did so before
                // the WS round-trip. Calling loadCachedMessages() here would
                // overwrite any message the user sent between switchSession() and
                // the server ack, making the chat appear to go blank.
                _uiState.update {
                    if (!isResumeFenceCurrent(
                            resumeFence,
                            allowRuntimeChange = true,
                            allowSessionChange = true,
                        )
                    ) {
                        return@update it
                    }
                    it.copy(
                        isLoading = false,
                        currentSessionId = sessionId,
                        isSessionReady = runtimeSessionId != null,
                        currentSessionModel =
                            if (model != null && provider != null) {
                                "$provider/$model"
                            } else {
                                model ?: it.currentSessionModel
                            },
                        reasoningLevel =
                            if (reasoningEffort.isNullOrEmpty()) {
                                null
                            } else {
                                reasoningEffort
                            },
                        terminalBackend = terminalBackend?.trim()?.takeIf { it.isNotEmpty() },
                        // `switchSession()` cleared the previous session's
                        // reading. Hydrate only from this resume response.
                        contextUsage = parseContextUsage(usage, previous = null),
                    )
                }
                if (sessionId != null) {
                    if (!isResumeFenceCurrent(resumeFence, allowRuntimeChange = true, allowSessionChange = true)) return
                    hydrateResumeMessages(
                        sessionId = sessionId,
                        payload = resultMap?.get("messages"),
                        replaceExisting =
                            requestedSessionId != null &&
                                requestedSessionId != sessionId,
                    )
                }
                if (!isResumeFenceCurrent(resumeFence, allowRuntimeChange = true, allowSessionChange = true)) return
                // Mirror the active runtime session id app-wide (issue #532).
                ActiveSessionHolder.set(runtimeSessionId ?: sessionId, sessionId)
                if (!isResumeFenceCurrent(resumeFence, allowRuntimeChange = true, allowSessionChange = true)) return
            }

            WsMethods.SESSION_INTERRUPT -> {
                _uiState.update {
                    it.copy(
                        isAgentTyping = false,
                    )
                }
                _streamingState.update { StreamingState() }
                streamingController.resetStreaming()
                addSystemMessage("Session interrupted")
            }

            WsMethods.COMMANDS_CATALOG -> {
                val fence = request.catalogFence ?: return
                if (!isCatalogFenceCurrent(fence)) return
                val map = result as? Map<*, *> ?: return
                val catalog = parseCommandCatalog(map)
                if (catalog != null) {
                    _uiState.update { it.copy(commandCatalog = catalog) }
                }
            }

            WsMethods.COMMAND_DISPATCH -> {
                handleDispatchResult(result)
            }

            WsMethods.APPROVAL_RESPOND -> {
                val map = result as? Map<*, *>
                val resolved = (map?.get("resolved") as? Number)?.toInt() ?: 0
                if (resolved > 0) {
                    addSystemMessage("Approval submitted")
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleDispatchResult(result: Any?) {
        val map = result as? Map<*, *> ?: return
        val type = map["type"] as? String ?: return
        when (type) {
            "send" -> {
                val message = map["message"] as? String ?: ""
                submitPrompt(message)
            }

            "exec" -> {
                val output = map["output"] as? String ?: map["message"] as? String ?: ""
                addAssistantMessage(output)
            }

            "skill" -> {
                val message = map["message"] as? String ?: ""
                submitPrompt(message)
            }

            "plugin" -> {
                val output = map["output"] as? String ?: ""
                addAssistantMessage(output)
            }

            "alias" -> {
                val target = map["target"] as? String ?: return
                handleSlashCommand(target)
            }

            else -> {
                val output = map["output"] as? String ?: map.toString()
                addAssistantMessage(output)
            }
        }
    }

    private fun handleRpcError(
        id: String,
        error: Any?,
    ) {
        val request = pendingRequests.remove(id) ?: return
        val method = request.method
        val errorCode =
            when (error) {
                is JsonRpcError -> error.code
                is Map<*, *> -> (error["code"] as? Number)?.toInt()
                else -> null
            }
        val errorMsg =
            when (error) {
                is JsonRpcError -> error.message
                is Map<*, *> -> error["message"] as? String ?: error.toString()
                else -> error.toString()
            }

        // A gateway whose running agent cannot steer an in-flight turn answers
        // session.redirect with 4010. The user's text is already on screen but
        // was never delivered, so resend it as a normal prompt rather than
        // surfacing an error and losing the message (issue #710).
        if (method == WsMethods.SESSION_REDIRECT) {
            val sessionId = request.redirectSessionId
            val text = request.redirectText
            val connectionBinding = request.redirectConnectionBinding
            if (errorCode == REDIRECT_UNSUPPORTED_CODE &&
                sessionId != null &&
                !text.isNullOrBlank() &&
                connectionBinding != null
            ) {
                Log.d(TAG, "session.redirect unsupported — resending as prompt.submit")
                // ChatWsEventReducer.onRpcError already ran for this event and
                // parked a generic error banner. The rejection is recoverable,
                // so clear it — the user should see the resent turn, not a
                // failure they cannot act on.
                _uiState.update { it.copy(errorMessage = null) }
                viewModelScope.launch(Dispatchers.IO) {
                    wsClient.sendMessageForConnection(
                        binding = connectionBinding,
                        sessionId = sessionId,
                        text = text,
                        onSent = { retryId -> trackRequest(retryId, WsMethods.PROMPT_SUBMIT) },
                    )
                }
                return
            }
        }

        // Surface error in UI (these are server-pushed RpcError for
        // fire-and-forget RPCs — awaited RPCs handle their own failure
        // via the HermesWsClient.request() deferred).
        _uiState.update {
            it.copy(
                isLoading = false,
                errorMessage = "Error ($method): $errorMsg",
            )
        }
    }

    // ── Send message ─────────────────────────────────────────────────────

    /**
     * Send a user prompt, uploading any pending attachments to the backend
     * first via their dedicated RPC methods.
     *
     * Flow:
     * 1. Snapshot pending attachments (then clear them from UI)
     * 2. Add user message to UI + DB immediately (optimistic UX)
     * 3. For each image → await `image.attach_bytes` (requires session_id)
     * 4. For each file → await `file.attach` (requires session_id), collect @file: refs
     * 5. Send `prompt.submit` with text + @file: refs — images auto-picked up by backend
     */
    fun sendMessage(text: String): Boolean {
        val state = _uiState.value
        if (!state.isSessionReady) return false
        if (text.isBlank() && state.pendingAttachments.isEmpty()) return false
        val storageSessionId = state.currentSessionId ?: return false
        val agentSessionId = runtimeSessionId ?: return false
        val dispatchGeneration = conversationGeneration
        val dispatchConnection = wsClient.connectionBinding(selectedProfileId()) ?: return false

        val trimmed = text.trim()
        if (trimmed.startsWith("/", ignoreCase = true)) {
            // Issue #589: a bare "/model" (no argument) opens the picker instead
            // of requiring the user to hand-type the provider/model.
            if (isModelPickerCommand(trimmed)) {
                openModelPicker()
                return true
            }
            handleSlashCommand(trimmed)
            return true
        }

        // Snapshot + clear attachments so the input bar empties immediately
        val attachments = _uiState.value.pendingAttachments.toList()
        clearAttachments()

        // Read the streaming flag BEFORE the optimistic update below sets
        // isAgentTyping = true, otherwise every send would look mid-turn and
        // route through session.redirect (issue #710).
        val wasStreaming = _uiState.value.isAgentTyping

        val userMessage =
            ChatMessage(
                role = MessageRole.USER,
                content = text,
                attachments = if (attachments.isNotEmpty()) attachments else null,
            )

        // Update UI immediately
        _uiState.update { state ->
            state.copy(
                messages = state.messages + userMessage,
                isAgentTyping = true,
            )
        }

        // Persist under the original Desktop session ID.
        val acceptedRevision = repo.replacementGeneration(storageSessionId)
        viewModelScope.launch(Dispatchers.IO) {
            repo.persistMessage(userMessage, storageSessionId, acceptedRevision)
        }

        // Upload attachments then submit prompt
        viewModelScope.launch(Dispatchers.IO) {
            val fileRefs = mutableListOf<String>()

            for (attachment in attachments) {
                val b64 = readContentUriBase64(attachment.uri)
                if (b64 == null) {
                    Log.w(TAG, "Skipping unreadable attachment")
                    continue
                }

                try {
                    if (attachment.isImage) {
                        // Await so the backend stages the image into
                        // session["attached_images"] BEFORE prompt.submit runs
                        // (a fire-and-forget send raced prompt.submit and the
                        // image was dropped). Requires session_id or the gateway
                        // 4001s "session not found" (desktop passes it too).
                        val response =
                            sendRpcAndAwait(
                                method = WsMethods.IMAGE_ATTACH_BYTES,
                                params =
                                    mapOf(
                                        "session_id" to agentSessionId,
                                        "content_base64" to "data:${attachment.mimeType};base64,$b64",
                                        "filename" to attachment.name,
                                        "ext" to attachment.fileExtension,
                                    ),
                            )
                        if (response != null) {
                            val result = if (response is JsonElement) response.toAny() else response
                            val ok = (result as? Map<*, *>)?.get("attached") as? Boolean
                            if (ok != true) {
                                Log.w(TAG, "Image attachment request failed")
                            }
                        }
                    } else {
                        // Await the @file: ref text so we can embed it in the prompt.
                        // file.attach also requires session_id or the gateway 4001s
                        // "session not found" (same resolver as image.attach_bytes).
                        sendRpcAndAwait(
                            method = WsMethods.FILE_ATTACH,
                            params =
                                mapOf(
                                    "session_id" to agentSessionId,
                                    "data_url" to "data:${attachment.mimeType};base64,$b64",
                                    "name" to attachment.name,
                                ),
                        )?.let { response ->
                            val result = if (response is JsonElement) response.toAny() else response
                            val refText = (result as? Map<*, *>)?.get("ref_text") as? String
                            if (!refText.isNullOrBlank()) fileRefs.add(refText)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to upload attachment (${e.javaClass.simpleName})")
                    _uiState.update {
                        it.copy(errorMessage = "Upload failed: ${attachment.name}")
                    }
                }
            }

            // Build prompt text — prepend @file: refs for non-image files
            val fullText =
                if (fileRefs.isEmpty()) {
                    text
                } else {
                    fileRefs.joinToString("\n") +
                        if (text.isNotBlank()) "\n\n$text" else ""
                }

            // Attachment preparation can suspend while the active conversation
            // changes. Fence by generation as well as ID so switching away and
            // back to the same session cannot dispatch the stale prompt.
            if (dispatchGeneration != conversationGeneration) return@launch

            // While a turn is still streaming and the prompt carries no
            // attachments, steer the in-flight turn via session.redirect
            // instead of queueing a second prompt.submit (issue #710).
            // session.redirect is text-only, so any attachment send stays on
            // the normal path. A backend that cannot redirect answers 4010;
            // handleRpcError re-sends the text as a normal prompt so the
            // typed message is never silently lost.
            if (wasStreaming && attachments.isEmpty()) {
                wsClient.sendRedirectForConnection(
                    binding = dispatchConnection,
                    sessionId = agentSessionId,
                    text = fullText,
                    onSent = { id ->
                        trackRedirectRequest(id, agentSessionId, fullText, dispatchConnection)
                    },
                )
            } else {
                wsClient.sendMessageForConnection(
                    binding = dispatchConnection,
                    sessionId = agentSessionId,
                    text = fullText,
                    onSent = { id -> trackRequest(id, WsMethods.PROMPT_SUBMIT) },
                )
            }
        }
        return true
    }

    /** Read and encode a `content://` or `file://` URI to Base64 via ContentResolver, avoiding large allocations. */
    private suspend fun readContentUriBase64(uriString: String): String? =
        try {
            val context = getApplication<Application>()
            val uri = Uri.parse(uriString)
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val baos = ByteArrayOutputStream()
                val b64os = Base64OutputStream(baos, Base64.NO_WRAP)
                val buffer = ByteArray(1024 * 128) // 128KB chunk
                var bytesRead: Int

                while (stream.read(buffer).also { bytesRead = it } != -1) {
                    b64os.write(buffer, 0, bytesRead)
                    yield() // Prevent blocking the thread during large reads
                }
                b64os.close()
                baos.toString("UTF-8")
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Failed to read and encode attachment (${e.javaClass.simpleName})")
            null
        }

    /**
     * Send a JSON-RPC call and suspend until the response arrives, delegating
     * the deferred + 120s timeout to [HermesWsClient.request] (issue #526).
     * Throws [HermesWsClient.HermesRpcException] on RPC error, or
     * [kotlinx.coroutines.TimeoutCancellationException] if the server never
     * answers within the timeout.
     */
    private suspend fun sendRpcAndAwait(
        method: String,
        params: Map<String, Any>,
    ): Any? = HermesWsClient.request(method, params).await()

    // ── Attachment management ─────────────────────────────────────────────

    /**
     * Add a picked file as a pending attachment.
     * [uri] should be a content:// URI string; the ViewModel will read
     * the content and encode it for sending.
     */
    fun addAttachment(
        uri: String,
        name: String,
        mimeType: String,
        size: Long,
    ) = attachmentsDelegate.addAttachment(uri, name, mimeType, size)

    fun addAttachments(attachments: List<Attachment>) = attachmentsDelegate.addAttachments(attachments)

    fun removeAttachment(index: Int) = attachmentsDelegate.removeAttachment(index)

    fun clearAttachments() = attachmentsDelegate.clearAttachments()

    private fun handleSlashCommand(command: String) {
        val result = slashDispatcher.dispatch(command)
        val displayContent =
            if (result is SlashResult.QueuePrompt) result.displayContent else command
        val userMsg = ChatMessage(role = MessageRole.USER, content = displayContent)
        val sessionId = _uiState.value.currentSessionId

        _uiState.update { it.copy(messages = it.messages + userMsg) }

        // Persist — OUTSIDE update{}
        if (sessionId != null) {
            val acceptedRevision = repo.replacementGeneration(sessionId)
            viewModelScope.launch(Dispatchers.IO) {
                repo.persistMessage(userMsg, sessionId, acceptedRevision)
            }
        }

        // Block desktop/CLI-only + TUI-only commands that don't function on
        // mobile (issue #576, deliverable #3). These are also hidden from the
        // suggestion menu, but a user can still type one — intercept it here
        // (before any RPC fires) with a clear message instead of a doomed call.
        if (CommandBlocklist.contains(command)) {
            addAssistantMessage(
                "${command.split(" ", limit = 2)[0]} is not supported on mobile",
            )
            return
        }

        when (result) {
            is SlashResult.Interrupt -> {
                interruptSession { recordAcceptedSlash(command) }
            }

            is SlashResult.NewSession -> {
                createNewSession(onDispatched = { recordAcceptedSlash(command) })
            }

            is SlashResult.SessionBranch -> {
                branchSession(command) { recordAcceptedSlash(command) }
            }

            is SlashResult.ModelSwitch -> {
                handleModelSwitch(command, recordUsage = true)
            }

            is SlashResult.QueueManage -> {
                addAssistantMessage("/queue ${result.verb} is not supported on mobile; use /queue <prompt> to add one")
            }

            is SlashResult.OpenHistory -> {
                _uiState.update { it.copy(openHistoryRequested = true) }
                recordAcceptedSlash(command)
            }

            is SlashResult.QueuePrompt -> {
                handleQueueCommand(command)
            }

            is SlashResult.Undo -> {
                handleUndoCommand(result.count, command)
            }

            is SlashResult.RpcDispatch -> {
                dispatchViaRpc(command)
            }
        }
    }

    private data class UndoFence(
        val storageSessionId: String,
        val runtimeSessionId: String,
        val profileId: String,
        val connectionBinding: ConnectionBinding,
        val generation: Long,
        val persistenceGeneration: Long,
    )

    private data class HistoryLoadFence(
        val storageSessionId: String,
        val runtimeSessionId: String?,
        val profileId: String,
        val connectionBinding: ConnectionBinding,
        val generation: Long,
        val transcriptRevision: Long,
    )

    private data class CacheLoadFence(
        val storageSessionId: String,
        val runtimeSessionId: String?,
        val profileId: String,
        val generation: Long,
    )

    private fun captureCacheLoadFence(sessionId: String) =
        CacheLoadFence(
            storageSessionId = sessionId,
            runtimeSessionId = runtimeSessionId,
            profileId = selectedProfileId(),
            generation = conversationGeneration,
        )

    private fun isCacheLoadCurrent(
        fence: CacheLoadFence,
        owner: Long,
    ): Boolean =
        activeCacheLoadOwner == owner &&
            fence.generation == conversationGeneration &&
            fence.profileId == selectedProfileId() &&
            fence.storageSessionId == _uiState.value.currentSessionId &&
            fence.runtimeSessionId == runtimeSessionId

    private fun captureHistoryLoadFence(sessionId: String): HistoryLoadFence? {
        val profileId = selectedProfileId()
        val connectionBinding = wsClient.connectionBinding(profileId) ?: return null
        return HistoryLoadFence(
            storageSessionId = sessionId,
            runtimeSessionId = runtimeSessionId,
            profileId = profileId,
            connectionBinding = connectionBinding,
            generation = conversationGeneration,
            transcriptRevision = repo.replacementGeneration(sessionId),
        )
    }

    private fun isHistoryLoadFenceCurrent(fence: HistoryLoadFence): Boolean =
        fence.generation == conversationGeneration &&
            fence.profileId == selectedProfileId() &&
            fence.storageSessionId == _uiState.value.currentSessionId &&
            fence.runtimeSessionId == runtimeSessionId &&
            fence.transcriptRevision == repo.replacementGeneration(fence.storageSessionId) &&
            wsClient.isConnectionBindingCurrent(fence.connectionBinding)

    private fun isHistoryRefreshCurrent(
        fence: HistoryLoadFence,
        owner: Long,
    ): Boolean = activeHistoryRefreshOwner == owner && isHistoryLoadFenceCurrent(fence)

    private fun invalidateHistoryRefresh() {
        retireSync()
        retireCacheLoad()
        activeHistoryRefreshOwner = null
        historyRefreshCounter++
        historyRefreshJob?.cancel()
        historyRefreshJob = null
    }

    private fun retireCacheLoad() {
        activeCacheLoadOwner = null
        cacheLoadCounter++
        cacheLoadJob?.cancel()
        cacheLoadJob = null
        activeCacheMessageIds = emptySet()
    }

    private fun launchHistoryLoad(block: suspend () -> Unit): Job {
        val job = viewModelScope.launch { block() }
        synchronized(historyLoadJobs) { historyLoadJobs += job }
        job.invokeOnCompletion { synchronized(historyLoadJobs) { historyLoadJobs -= job } }
        return job
    }

    private fun invalidateHistoryLoads(sessionId: String) {
        invalidateHistoryRefresh()
        repo.invalidateReplacementWrites(sessionId)
        invalidateResumeRequests()
        val jobs = synchronized(historyLoadJobs) { historyLoadJobs.toList().also { historyLoadJobs.clear() } }
        jobs.forEach(Job::cancel)
    }

    private data class PendingUndo(val count: String, val command: String, val fence: UndoFence)

    private var pendingUndo: PendingUndo? = null

    fun dismissUndoConfirm() {
        pendingUndo = null
        _uiState.update { it.copy(undoConfirmCount = null) }
    }

    fun confirmUndo() {
        val pending = pendingUndo ?: return
        dismissUndoConfirm()
        if (isUndoFenceCurrent(pending.fence)) performUndo(pending.count, pending.command, pending.fence)
    }

    private fun handleUndoCommand(
        count: String,
        command: String,
    ) {
        val storageSessionId = _uiState.value.currentSessionId ?: return
        val agentSessionId = runtimeSessionId ?: return
        val profileId = selectedProfileId()
        val connectionBinding = wsClient.connectionBinding(profileId) ?: return
        val fence =
            UndoFence(
                storageSessionId = storageSessionId,
                runtimeSessionId = agentSessionId,
                profileId = profileId,
                connectionBinding = connectionBinding,
                generation = conversationGeneration,
                persistenceGeneration = repo.replacementGeneration(storageSessionId),
            )
        val turns = count.substringBefore(' ').toIntOrNull()
        if (turns != null && turns > 1) {
            pendingUndo = PendingUndo(count, command, fence)
            _uiState.update { it.copy(undoConfirmCount = turns) }
            return
        }
        performUndo(count, command, fence)
    }

    private fun performUndo(
        count: String,
        command: String,
        fence: UndoFence,
    ) {
        if (!isUndoFenceCurrent(fence)) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result =
                    wsClient
                        .requestForConnection(
                            fence.connectionBinding,
                            WsMethods.COMMAND_DISPATCH,
                            mapOf("name" to "undo", "arg" to count, "session_id" to fence.runtimeSessionId),
                        ).await()
                if (!isUndoFenceCurrent(fence)) return@launch
                recordAcceptedSlash(command)
                handleUndoResult(result, fence)
            } catch (e: Exception) {
                if (isUndoFenceCurrent(fence)) {
                    addAssistantMessage(e.message ?: "Failed to undo.")
                }
            }
        }
    }

    private fun isUndoFenceCurrent(fence: UndoFence): Boolean =
        fence.generation == conversationGeneration &&
            fence.profileId == selectedProfileId() &&
            fence.storageSessionId == _uiState.value.currentSessionId &&
            fence.runtimeSessionId == runtimeSessionId &&
            wsClient.isConnectionBindingCurrent(fence.connectionBinding)

    private suspend fun handleUndoResult(
        result: Any?,
        fence: UndoFence,
    ) {
        val map = result as? Map<*, *> ?: return
        if (map["type"] != "prefill") {
            if (isUndoFenceCurrent(fence)) handleDispatchResult(result)
            return
        }
        val prefill = map["message"] as? String ?: ""
        val notice = (map["notice"] as? String).orEmpty().ifBlank { "Rewound conversation" }
        invalidateHistoryLoads(fence.storageSessionId)
        val authoritativeFence =
            fence.copy(persistenceGeneration = repo.replacementGeneration(fence.storageSessionId))
        val transcript = fetchCompleteSessionHistory(authoritativeFence) ?: return
        if (!isUndoFenceCurrent(authoritativeFence)) return
        val feedback = ChatMessage(role = MessageRole.SYSTEM, content = notice)
        val reconciled = transcript + feedback
        if (
            !repo.replaceMessagesIfCurrent(
                reconciled,
                fence.storageSessionId,
                authoritativeFence.persistenceGeneration,
            )
        ) {
            return
        }
        if (!isUndoFenceCurrent(authoritativeFence)) return
        loadedMessageOffset = 0
        latestPaging = true
        _uiState.update { state ->
            if (!isUndoFenceCurrent(fence)) return@update state
            state.copy(
                messages = reconciled,
                todos = restoredTodos(emptyList(), transcript),
                hasOlderMessages = false,
                isLoadingOlder = false,
                pendingPrefillText = prefill.takeIf { it.isNotBlank() },
            )
        }
    }

    private suspend fun fetchCompleteSessionHistory(fence: UndoFence): List<ChatMessage>? {
        val pages = mutableListOf<List<ChatMessage>>()
        var offset = 0
        while (true) {
            if (!isUndoFenceCurrent(fence)) return null
            val result = fetchMessagePage(fence.storageSessionId, offset, MESSAGE_PAGE_SIZE, order = "latest")
            if (!isUndoFenceCurrent(fence)) return null
            if (result !is NetworkResult.Success) return null
            val pagination = result.data.pagination
            val effectiveOffset = pagination?.offset ?: offset
            pages += mapServerMessages(fence.storageSessionId, result.data.messages.orEmpty(), effectiveOffset)
            val returned = pagination?.returned ?: result.data.messages.size
            val total = pagination?.total
            if (returned <= 0 ||
                (total != null && effectiveOffset + returned >= total) ||
                (total == null && returned < MESSAGE_PAGE_SIZE)
            ) {
                break
            }
            offset = effectiveOffset + returned
        }
        return if (isUndoFenceCurrent(fence)) pages.asReversed().flatten().distinctBy { it.id } else null
    }

    fun consumePendingPrefill() {
        _uiState.update { it.copy(pendingPrefillText = null) }
    }

    private fun handleQueueCommand(command: String) {
        val arg = command.split(" ", limit = 2).getOrElse(1) { "" }.trim()
        if (arg.isBlank()) {
            addAssistantMessage("usage: /queue <prompt>")
            return
        }
        submitPrompt(
            text = arg,
            queued = true,
            onDispatched = { recordAcceptedSlash(command) },
        )
    }

    /**
     * Fork the active conversation via the session.branch WS RPC (issue #533).
     * The backend already supports session.branch; the mobile previously had
     * no client surface, so `/fork` fell through to command.dispatch and 4018'd.
     * The optional arg becomes the new branch's title.
     */
    private fun branchSession(
        command: String,
        onDispatched: (() -> Unit)? = null,
    ) {
        val sessionId = runtimeSessionId
        if (sessionId == null) {
            addAssistantMessage("No active session. Use `/new` to create one.")
            return
        }
        val arg = command.split(" ", limit = 2).getOrElse(1) { "" }.trim()
        val params = mutableMapOf<String, Any>("session_id" to sessionId)
        if (arg.isNotBlank()) params["name"] = arg
        viewModelScope.launch(Dispatchers.IO) {
            wsClient.send(
                WsMethods.SESSION_BRANCH,
                params,
                onSent = { id ->
                    trackBranchRequest(id, sessionId)
                    onDispatched?.invoke()
                },
            )
        }
    }

    private fun dispatchViaRpc(command: String) {
        val sessionId = runtimeSessionId
        if (sessionId == null) {
            addAssistantMessage("No active session. Use `/new` to create one.")
            return
        }
        val parts = command.split(" ", limit = 2)
        val name = parts[0].lowercase().removePrefix("/")
        val arg = parts.getOrElse(1) { "" }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Primary path: command.dispatch handles quick/plugin/bundle/
                // skill commands + a few hardcoded ones. It returns a hard 4018
                // "not a ... command" for everything that lives only in the TUI
                // slash worker (the 29 commands that 4018'd on mobile — issue
                // #576). For those we fall back to slash.exec, which runs the
                // full COMMAND_REGISTRY through the worker.
                val result =
                    wsClient
                        .request(
                            WsMethods.COMMAND_DISPATCH,
                            mapOf("name" to name, "arg" to arg, "session_id" to sessionId),
                        ).await()
                recordAcceptedSlash(command)
                handleDispatchResult(result)
            } catch (e: HermesWsClient.HermesRpcException) {
                val msg = e.message.orEmpty()
                // Registry miss on command.dispatch: the backend emits exactly
                // "not a quick/plugin/bundle/skill command: <name>" (tui_gateway
                // server.py L12408). Match that precise phrase so unrelated
                // errors can't accidentally trigger the slash.exec fallback.
                if (msg.contains("not a quick/plugin/bundle/skill command")) {
                    // Registry miss on command.dispatch -> retry via slash.exec,
                    // which routes the full CLI command set through the worker.
                    try {
                        val result =
                            wsClient
                                .request(
                                    WsMethods.SLASH_EXEC,
                                    mapOf(
                                        "command" to "/$name${if (arg.isNotEmpty()) " $arg" else ""}",
                                        "session_id" to sessionId,
                                    ),
                                ).await()
                        recordAcceptedSlash(command)
                        val output = (result as? Map<*, *>)?.get("output") as? String
                        if (!output.isNullOrBlank()) addAssistantMessage(output)
                    } catch (e2: HermesWsClient.HermesRpcException) {
                        addAssistantMessage("/$name: ${e2.message}")
                    }
                } else {
                    // Legit error from command.dispatch (busy, no history, etc.)
                    addAssistantMessage("/$name: ${e.message}")
                }
            }
        }
    }

    /**
     * Hot-swap the current session's model via the backend's model-switch
     * mechanism (issue #589).
     *
     * The TUI gateway's `prompt.submit` does NOT parse slash commands (it would
     * make the LLM treat "/model ..." as a chat message), and `command.dispatch`
     * only knows quick/plugin/bundle/skill commands (4018s on /model). The
     * correct RPC is `config.set` with `key="model"` — the gateway (server.py
     * `config.set`, L10253) routes `key=="model"` straight to `_apply_model_switch`
     * using the same `_sessions.get(session_id)` lookup that the working
     * `command.dispatch` uses.
     *
     * IMPORTANT: `config.set` key=model passes the value DIRECTLY to
     * `parse_model_flags` (it does NOT strip a leading "/model" like slash.exec /
     * prompt.submit do). So we strip the "/model" prefix here and send the bare
     * spec `parse_model_flags` understands:
     *   `<model> --provider <slug> --session`
     * (matching the TUI client's `modelValueForConfigSet`).
     */
    private fun handleModelSwitch(
        command: String,
        recordUsage: Boolean,
    ) {
        // Strip a leading "/model" (and any following whitespace) — config.set
        // key=model expects the bare spec, not a slash command. Match the
        // dispatcher's case-insensitive "/model" detection so a typed "/MODEL"
        // (or any casing) doesn't forward the literal slash prefix to the
        // backend, where parse_model_flags wouldn't recognize it.
        val spec =
            if (command.startsWith("/model", ignoreCase = true)) {
                command.substring(6).trim()
            } else {
                command.trim()
            }
        applySessionModel(spec, recordUsage = recordUsage)
    }

    /**
     * Submits [text] as a prompt to the current session via WS, without
     * adding a duplicate user message. Used by [handleDispatchResult] when
     * a slash command resolves to a normal user prompt.
     */
    private fun submitPrompt(
        text: String,
        queued: Boolean = false,
        onDispatched: (() -> Unit)? = null,
    ) {
        if (text.isBlank()) return
        val sessionId = runtimeSessionId ?: return
        _uiState.update { it.copy(isAgentTyping = true) }
        viewModelScope.launch(Dispatchers.IO) {
            wsClient.sendMessage(
                sessionId,
                text,
                onSent = { id ->
                    trackRequest(id, WsMethods.PROMPT_SUBMIT)
                    onDispatched?.invoke()
                },
                queued = queued,
            )
        }
    }

    private fun addAssistantMessage(text: String) {
        val msg = ChatMessage(role = MessageRole.ASSISTANT, content = text)
        _uiState.update { it.copy(messages = it.messages + msg) }

        // Persist — OUTSIDE update{}
        val sessionId = _uiState.value.currentSessionId
        if (sessionId != null) {
            val acceptedRevision = repo.replacementGeneration(sessionId)
            viewModelScope.launch(Dispatchers.IO) {
                repo.persistMessage(msg, sessionId, acceptedRevision)
            }
        }
    }

    // ── Session management ───────────────────────────────────────────────

    fun interruptSession(onDispatched: (() -> Unit)? = null) {
        val sessionId = runtimeSessionId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            wsClient.send(
                WsMethods.SESSION_INTERRUPT,
                mapOf("session_id" to sessionId),
                onSent = { id ->
                    trackRequest(id, WsMethods.SESSION_INTERRUPT)
                    onDispatched?.invoke()
                },
            )
        }
    }

    private var sessionCreateCounter = 0L

    /** Liveness timer for the newest unacknowledged `session.create`. */
    private var sessionCreateJob: Job? = null

    /**
     * Ask the gateway for a fresh conversation.
     *
     * `session.create` is fire-and-forget over a socket that may be mid-drop.
     * [HermesWsClient.send] queues the frame while the socket is down and drops
     * it outright once credentials were cleared, and neither path reports back
     * — so an unlucky tap could leave Chat with no session at all:
     * `currentSessionId` and `runtimeSessionId` already discarded,
     * `isSessionReady` false, Send disabled, and nothing in flight to recover
     * it. That is the "leave the chat screen and come back a few times" state;
     * re-entering only helped because a later `gateway.ready` happened to issue
     * another create.
     *
     * The old code only cleared the spinner after ten seconds, which hid the
     * symptom without producing a session. Retry the request on a deadline
     * instead, and surface an actionable error once the attempts are spent.
     */
    fun createNewSession(
        setLoading: Boolean = true,
        onDispatched: (() -> Unit)? = null,
    ) {
        clearPrivilegedControls()
        invalidateHistoryRefresh()
        _uiState.value.currentSessionId?.let(repo::invalidateReplacementWrites)
        conversationGeneration++
        invalidateResumeRequests()
        val generation = ++sessionCreateCounter
        sessionCreateJob?.cancel()
        sessionCreateJob = null
        runtimeSessionId = null
        loadedMessageOffset = 0
        latestPaging = false
        ActiveSessionHolder.set(null)
        _uiState.update {
            it.copy(
                isLoading = setLoading,
                currentSessionId = null,
                isSessionReady = false,
                messages = emptyList(),
                subagentIndicators = emptyList(),
                todos = emptyList(),
                chatTitle = "Hermes",
                currentSessionModel = null,
                terminalBackend = null,
                contextUsage = null,
                showContextDetail = false,
                pendingPrefillText = null,
            )
        }
        _streamingState.update { StreamingState() }
        streamingController.resetStreaming()
        sendSessionCreate(generation = generation, attempt = 1, onDispatched = onDispatched)
    }

    /**
     * Issue one `session.create` attempt and arm its liveness deadline.
     *
     * The deadline is fenced on [generation] so a superseded attempt can never
     * retry on behalf of a newer one, and it stands down as soon as the session
     * is ready — an ack, not a fixed sleep, ends the cycle.
     */
    private fun sendSessionCreate(
        generation: Long,
        attempt: Int,
        onDispatched: (() -> Unit)? = null,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            wsClient.send(
                WsMethods.SESSION_CREATE,
                params = mapOf("source" to "desktop"),
                onSent = { id ->
                    trackCreateRequest(id, generation)
                    onDispatched?.invoke()
                },
            )
        }

        // Historical behavior: unit tests never armed the safety timer. Keep
        // that unless a test opts into the retry path by injecting a delay.
        val injectedDelay = sessionCreateRetryDelayMs
        if (injectedDelay == null && isTestEnvironment()) return
        val delayMs = injectedDelay ?: SESSION_CREATE_TIMEOUT_MS

        sessionCreateJob =
            viewModelScope.launch {
                delay(delayMs)
                if (generation != sessionCreateCounter) return@launch
                if (_uiState.value.isSessionReady) return@launch
                if (attempt < SESSION_CREATE_MAX_ATTEMPTS) {
                    Log.w(TAG, "session.create unacknowledged — retrying (attempt ${attempt + 1})")
                    sendSessionCreate(generation = generation, attempt = attempt + 1)
                } else {
                    Log.w(TAG, "session.create gave up after $attempt attempts")
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage =
                                getApplication<Application>()
                                    .getString(R.string.chat_new_session_failed),
                        )
                    }
                }
            }
    }

    fun loadSessions() {
        viewModelScope.launch(Dispatchers.IO) {
            wsClient.send(
                WsMethods.SESSION_LIST,
                onSent = { id -> trackRequest(id, WsMethods.SESSION_LIST) },
            )
        }
    }

    private fun isCatalogFenceCurrent(fence: CatalogFence): Boolean =
        fence.generation == conversationGeneration &&
            fence.requestGeneration == catalogRequestGeneration &&
            fence.profileId == selectedProfileId() &&
            fence.runtimeSessionId == runtimeSessionId &&
            wsClient.isConnectionBindingCurrent(fence.binding)

    private fun fetchCommandCatalog() {
        val profile = selectedProfileId()
        val binding = wsClient.connectionBinding(profile) ?: return
        val fence = CatalogFence(binding, profile, runtimeSessionId, conversationGeneration, ++catalogRequestGeneration)
        viewModelScope.launch(Dispatchers.IO) {
            if (!isCatalogFenceCurrent(fence)) return@launch
            wsClient.send(
                WsMethods.COMMANDS_CATALOG,
                params = fence.runtimeSessionId?.let { mapOf("session_id" to it) } ?: emptyMap(),
                onSent = { id ->
                    if (isCatalogFenceCurrent(fence)) {
                        pendingRequests[id] = PendingRpcRequest(WsMethods.COMMANDS_CATALOG, catalogFence = fence)
                    }
                },
            )
        }
    }

    /**
     * Top up the context meter's denominator from `GET /api/model/info`.
     *
     * Only a fallback: it covers the window between opening a session and its
     * first turn, before the gateway has pushed a live `context_max`. The
     * numerator is never sourced from REST — see [ContextUsage].
     */
    private fun fetchModelContextLength() {
        viewModelScope.launch(Dispatchers.IO) {
            when (val result = safeApiCall { ApiClient.hermesApi.getModelInfo() }) {
                is NetworkResult.Success -> {
                    val length =
                        result.data.effectiveContextLength
                            ?: result.data.autoContextLength
                    if (length != null && length > 0L) {
                        _uiState.update {
                            it.copy(
                                modelContextLength = length,
                                modelContextLengthModel = result.data.qualifiedModel,
                            )
                        }
                    }
                }

                is NetworkResult.Failure -> Unit
            }
        }
    }

    /** Open the tappable context breakdown sheet. */
    fun openContextDetail() {
        _uiState.update { it.copy(showContextDetail = true) }
    }

    /** Dismiss the context breakdown sheet. */
    fun closeContextDetail() {
        _uiState.update { it.copy(showContextDetail = false) }
    }

    fun refreshCurrentSession() {
        val sessionId = _uiState.value.currentSessionId ?: return
        loadSessionMessages(sessionId)
    }

    fun refreshSettings() {
        _uiState.update { state ->
            state.copy(
                typingEffectEnabled = AuthManager.isTypingEffectEnabled(),
                typingEffectDelayMs = AuthManager.getTypingEffectDelayMs(),
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseCommandCatalog(map: Map<*, *>): CommandCatalog? =
        try {
            val jsonElement = map.toJsonElement()
            OkHttpProvider.json.decodeFromJsonElement<CommandCatalog>(jsonElement)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse command catalog (${e.javaClass.simpleName})")
            null
        }

    // ── In-session model picker (issue #589) ─────────────────────────────

    /**
     * Whether [command] should open the in-session model picker instead of being
     * dispatched as a normal slash command. True for a bare `/model` (with no
     * trailing model argument) — the picker supplies the argument interactively.
     * A fully-typed `/model provider/model` is forwarded straight to the backend.
     */
    private fun isModelPickerCommand(command: String): Boolean {
        val trimmed = command.trim()
        if (!trimmed.startsWith("/", ignoreCase = true)) return false
        val body = trimmed.removePrefix("/").trimStart()
        // Must be exactly "model" with no argument (or just whitespace).
        return body.equals("model", ignoreCase = true) ||
            (
                body.startsWith("model ", ignoreCase = true) &&
                    body.substringAfter("model").trim().isEmpty()
            )
    }

    /** Preload model options so the picker opens instantly (no spinner on /model). */
    private fun preloadModelOptions() {
        viewModelScope.launch(Dispatchers.IO) {
            val result =
                safeApiCall {
                    ApiClient.hermesApi.getModelOptions(refresh = false)
                }
            when (result) {
                is NetworkResult.Success -> {
                    cachedModelOptions = result.data.providers.orEmpty()
                    _uiState.update {
                        it.copy(
                            modelPickerProviders = cachedModelOptions,
                            modelPickerPinned = AuthManager.getPinnedModels(),
                            modelInventoryResolved = true,
                        )
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update { it.copy(modelInventoryResolved = true) }
                }
            }
        }
    }

    /**
     * Open the in-session model picker. Uses the preloaded options if available
     * (instant open); otherwise shows a loading state and fetches them. The
     * `/model` slash command is the supported session hot-swap mechanism per the
     * backend contract (issue #589).
     */
    fun openModelPicker() {
        val hasCached = cachedModelOptions.isNotEmpty()
        _uiState.update {
            it.copy(
                showModelPicker = true,
                modelPickerProviders = if (hasCached) cachedModelOptions else emptyList(),
                modelPickerPinned = AuthManager.getPinnedModels(),
                modelPickerLoading = !hasCached,
            )
        }
        if (!hasCached) {
            refreshModelOptions()
        }
    }

    /** Re-fetch options (pull-to-refresh style) when the picker is already open. */
    fun refreshModelOptions() {
        _uiState.update { it.copy(modelPickerLoading = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val result =
                safeApiCall {
                    ApiClient.hermesApi.getModelOptions(refresh = true)
                }
            when (result) {
                is NetworkResult.Success -> {
                    cachedModelOptions = result.data.providers.orEmpty()
                    _uiState.update {
                        it.copy(
                            modelPickerProviders = cachedModelOptions,
                            modelPickerLoading = false,
                            modelInventoryResolved = true,
                        )
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            modelPickerLoading = false,
                            modelInventoryResolved = true,
                            errorMessage = "Failed to load models: ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

    fun closeModelPicker() {
        _uiState.update { it.copy(showModelPicker = false, modelPickerLoading = false) }
    }

    fun togglePinModel(
        providerSlug: String,
        modelName: String,
    ) {
        val currentPinned = AuthManager.getPinnedModels().toMutableList()
        val target = PinnedModel(providerSlug, modelName)
        if (currentPinned.contains(target)) {
            currentPinned.remove(target)
        } else {
            currentPinned.add(target)
        }
        AuthManager.savePinnedModels(currentPinned)
        _uiState.update { it.copy(modelPickerPinned = currentPinned) }
    }

    /** Hot-swap only the current live session through the awaited config.set RPC. */
    fun sendSlashModel(
        provider: String,
        model: String,
    ) {
        _uiState.update {
            it.copy(
                showModelPicker = false,
                modelPickerLoading = false,
            )
        }
        applySessionModel(
            spec = "$model --provider $provider --session",
            displayLabel = "$provider/$model",
        )
    }

    fun confirmModelSwitch() {
        val pending = _uiState.value.modelSwitchConfirmation ?: return
        if (runtimeSessionId != pending.sessionId) {
            _uiState.update {
                it.copy(
                    modelSwitchConfirmation = null,
                    errorMessage =
                        getApplication<Application>().getString(
                            R.string.chat_model_switch_session_changed,
                        ),
                )
            }
            return
        }
        _uiState.update { it.copy(modelSwitchConfirmation = null) }
        applySessionModel(
            spec = pending.spec,
            displayLabel = pending.displayLabel,
            confirmExpensive = true,
        )
    }

    fun cancelModelSwitchConfirmation() {
        _uiState.update { it.copy(modelSwitchConfirmation = null) }
    }

    private fun applySessionModel(
        spec: String,
        displayLabel: String? = null,
        confirmExpensive: Boolean = false,
        recordUsage: Boolean = false,
    ) {
        val sessionId = runtimeSessionId
        if (sessionId == null) {
            _uiState.update {
                it.copy(
                    errorMessage =
                        getApplication<Application>().getString(
                            R.string.chat_model_switch_no_session,
                        ),
                )
            }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val params =
                mutableMapOf<String, Any>(
                    "session_id" to sessionId,
                    "key" to "model",
                    "value" to spec,
                )
            if (confirmExpensive) {
                params["confirm_expensive_model"] = true
            }

            try {
                val result = wsClient.request(WsMethods.CONFIG_SET, params).await()
                val resultMap = result as? Map<*, *>
                if (resultMap?.get("confirm_required") == true) {
                    val message =
                        (resultMap["confirm_message"] as? String)
                            ?.takeIf { it.isNotBlank() }
                            ?: getApplication<Application>().getString(
                                R.string.chat_model_confirm_default_message,
                            )
                    _uiState.update {
                        if (runtimeSessionId != sessionId) return@update it
                        it.copy(
                            modelSwitchConfirmation =
                                ModelSwitchConfirmation(
                                    message = message,
                                    spec = spec,
                                    displayLabel = displayLabel,
                                    sessionId = sessionId,
                                ),
                        )
                    }
                    return@launch
                }

                val provider = resultMap?.get("provider") as? String
                val model = resultMap?.get("model") as? String
                val confirmedLabel =
                    if (!provider.isNullOrBlank() && !model.isNullOrBlank()) {
                        "$provider/$model"
                    } else {
                        displayLabel
                    }
                _uiState.update {
                    if (runtimeSessionId != sessionId) return@update it
                    it.copy(
                        currentSessionModel = confirmedLabel ?: it.currentSessionModel,
                        contextUsage = it.contextUsage?.copy(maxTokens = null),
                        modelSwitchConfirmation = null,
                    )
                }
                if (recordUsage) recordAcceptedSlash("/model")
            } catch (e: HermesWsClient.HermesRpcException) {
                _uiState.update {
                    if (runtimeSessionId != sessionId) return@update it
                    it.copy(
                        errorMessage =
                            getApplication<Application>().getString(
                                R.string.chat_model_switch_failed,
                                e.message,
                            ),
                        modelSwitchConfirmation = null,
                    )
                }
            }
        }
    }

    private fun recordAcceptedSlash(command: String) {
        val commandName = command.split(" ", limit = 2)[0].lowercase()
        viewModelScope.launch {
            slashUsageStore.recordUse(selectedProfileId(), commandName)
        }
    }

    fun consumeOpenHistoryRequest() {
        _uiState.update { it.copy(openHistoryRequested = false) }
    }

    /**
     * Set the reasoning effort level for the current session.
     *
     * Updates the UI optimistically and sends a `config.set` RPC to the
     * backend. The level applies per-session via the runtime session ID.
     * If [level] is null it resets to the model's default.
     *
     * @param level One of "low", "medium", "high", or null for default.
     */
    fun setReasoningLevel(level: String?) {
        _uiState.update { it.copy(reasoningLevel = level) }
        val sessionId = runtimeSessionId ?: return
        if (level == null) return // null = model default, no need to send WS
        viewModelScope.launch(Dispatchers.IO) {
            wsClient.send(
                WsMethods.CONFIG_SET,
                mapOf(
                    "key" to "reasoning",
                    "value" to level,
                    "session_id" to sessionId,
                ),
                onSent = { id -> trackRequest(id, WsMethods.CONFIG_SET) },
            )
        }
    }

    fun switchSession(sessionId: String) {
        if (sessionId == _uiState.value.currentSessionId) return

        clearPrivilegedControls()
        invalidateHistoryRefresh()
        _uiState.value.currentSessionId?.let(repo::invalidateReplacementWrites)
        conversationGeneration++
        invalidateResumeRequests()
        // A pending session.create belongs to the conversation the user just
        // left. Retiring the generation makes any late answer inert, and the
        // timer must go with it or it would retry a create into this session.
        sessionCreateCounter++
        sessionCreateJob?.cancel()
        sessionCreateJob = null

        // Reset streaming and pagination state before resuming the Desktop session.
        runtimeSessionId = null
        loadedMessageOffset = 0
        latestPaging = false
        streamingController.resetStreaming()
        _uiState.update {
            val title = it.sessions.find { s -> s.id == sessionId }?.title ?: "Hermes"
            it.copy(
                isLoading = true,
                isSessionReady = false,
                isLoadingOlder = false,
                hasOlderMessages = false,
                currentSessionId = sessionId,
                messages = emptyList(),
                subagentIndicators = emptyList(),
                todos = emptyList(),
                chatTitle = title,
                showSessionPicker = false,
                isAgentTyping = false,
                currentSessionModel = null,
                terminalBackend = null,
                modelSwitchConfirmation = null,
                // The gauge is per-session occupancy; carrying the previous
                // session's fill into a new one would misreport it until the
                // first turn lands.
                contextUsage = null,
                showContextDetail = false,
                pendingPrefillText = null,
            )
        }
        // Mirror the active session id app-wide (issue #532).
        ActiveSessionHolder.set(sessionId)
        _streamingState.update { StreamingState() }
        val resumeFence = captureResumeFence(sessionId)
        viewModelScope.launch {
            // Resume the selected desktop session, then load its complete transcript.
            launch(Dispatchers.IO) {
                if (resumeFence != null && isResumeFenceCurrent(resumeFence)) {
                    wsClient.send(
                        WsMethods.SESSION_RESUME,
                        mapOf("session_id" to sessionId, "omit_messages" to true),
                        onSent = { id -> trackResumeRequest(id, resumeFence) },
                    )
                }
            }
            loadSessionMessages(sessionId)
            loadSessions()
        }
    }

    private fun loadCachedMessages(sessionId: String) {
        val fence = captureCacheLoadFence(sessionId)
        val owner = ++cacheLoadCounter
        activeCacheLoadOwner = owner
        cacheLoadJob?.cancel()
        cacheLoadJob =
            launchHistoryLoad {
                val cachedMessages = repo.loadMessages(sessionId)
                _uiState.update { state ->
                    // Cache is only a blank-transcript fallback. A live, resume,
                    // or server mutation that wins the race owns the transcript.
                    if (isCacheLoadCurrent(fence, owner) && state.messages.isEmpty()) {
                        activeCacheMessageIds = cachedMessages.mapTo(mutableSetOf()) { it.id }
                        state.copy(
                            messages = cachedMessages,
                            todos = restoredTodos(state.todos, cachedMessages),
                        )
                    } else {
                        state
                    }
                }
                if (activeCacheLoadOwner == owner) cacheLoadJob = null
            }
    }

    private fun loadSessionMessages(sessionId: String) {
        // A full refresh supersedes any incremental sync. Retire it before the
        // repository FIFO barrier and refresh-fence capture so a sync already
        // queued for persistence cannot become authoritative afterward.
        retireSync()
        retireOlderLoad()
        val owner = ++historyRefreshCounter
        activeHistoryRefreshOwner = owner
        historyRefreshJob?.cancel()
        retireCacheLoad()
        val baselineMessageIds = _uiState.value.messages.mapTo(mutableSetOf()) { it.id }
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        loadCachedMessages(sessionId)
        historyRefreshJob =
            launchHistoryLoad {
                try {
                    withContext(Dispatchers.IO) {
                        repo.awaitSessionOperations(sessionId)
                    }
                    if (activeHistoryRefreshOwner != owner) return@launchHistoryLoad
                    val fence = captureHistoryLoadFence(sessionId) ?: return@launchHistoryLoad
                    if (!isHistoryRefreshCurrent(fence, owner)) return@launchHistoryLoad
                    val latestResult =
                        fetchMessagePage(
                            sessionId,
                            offset = 0,
                            limit = MESSAGE_PAGE_SIZE,
                            order = "latest",
                        )
                    if (!isHistoryRefreshCurrent(fence, owner)) return@launchHistoryLoad
                    val useLatestPaging: Boolean
                    val (result, requestedOffset) =
                        if (
                            latestResult is NetworkResult.Success &&
                            latestResult.data.pagination?.order == "latest"
                        ) {
                            useLatestPaging = true
                            latestResult to 0
                        } else if (latestResult is NetworkResult.Success) {
                            useLatestPaging = false
                            val messageCount =
                                fetchServerMessageCount(sessionId, fence, owner)
                                    ?: return@launchHistoryLoad
                            val offset = (messageCount - MESSAGE_PAGE_SIZE).coerceAtLeast(0)
                            if (!isHistoryRefreshCurrent(fence, owner)) return@launchHistoryLoad
                            fetchMessagePage(sessionId, offset, MESSAGE_PAGE_SIZE) to offset
                        } else {
                            useLatestPaging = false
                            latestResult to 0
                        }
                    if (!isHistoryRefreshCurrent(fence, owner)) return@launchHistoryLoad
                    latestPaging = useLatestPaging
                    when (result) {
                        is NetworkResult.Success -> {
                            val offset = result.data.pagination?.offset ?: requestedOffset
                            val chatMessages =
                                mapServerMessages(
                                    sessionId,
                                    result.data.messages.orEmpty(),
                                    offset,
                                    useLatestPaging,
                                )
                            val persisted =
                                withContext(Dispatchers.IO) {
                                    repo.persistMessagesIfCurrent(
                                        chatMessages,
                                        sessionId,
                                        fence.transcriptRevision,
                                    ) {
                                        isHistoryRefreshCurrent(fence, owner)
                                    }
                                }
                            if (!persisted || !isHistoryRefreshCurrent(fence, owner)) return@launchHistoryLoad
                            val cachedMessageIds = activeCacheMessageIds
                            retireCacheLoad()
                            _uiState.update { state ->
                                if (!isHistoryRefreshCurrent(fence, owner)) return@update state
                                val hasResumeHistory = state.messages.any { it.id.startsWith("resume-$sessionId-") }
                                if (chatMessages.isEmpty() && hasResumeHistory) {
                                    state.copy(isLoading = false, isLoadingOlder = false)
                                } else {
                                    val authoritativeIds = chatMessages.mapTo(mutableSetOf()) { it.id }
                                    val newerMessages =
                                        state.messages.filter { message ->
                                            message.id !in baselineMessageIds &&
                                                message.id !in cachedMessageIds &&
                                                message.id !in authoritativeIds
                                        }
                                    val refreshedMessages = chatMessages + newerMessages
                                    val hasOlder =
                                        if (useLatestPaging) {
                                            val returned = result.data.pagination?.returned ?: chatMessages.size
                                            returned >= MESSAGE_PAGE_SIZE && chatMessages.isNotEmpty()
                                        } else {
                                            offset > 0 && chatMessages.isNotEmpty()
                                        }
                                    state.copy(
                                        messages = refreshedMessages,
                                        todos = restoredTodos(state.todos, refreshedMessages),
                                        isLoading = false,
                                        hasOlderMessages = hasOlder,
                                        isLoadingOlder = false,
                                    )
                                }
                            }
                            if (isHistoryRefreshCurrent(fence, owner)) {
                                val hasRestHistory =
                                    _uiState.value.messages.any { serverMessageIndex(it.id, sessionId) != null }
                                if (chatMessages.isNotEmpty() && hasRestHistory) loadedMessageOffset = offset
                            }
                        }

                        is NetworkResult.Failure -> {
                            _uiState.update {
                                if (!isHistoryRefreshCurrent(fence, owner)) return@update it
                                it.copy(
                                    isLoading = false,
                                    isLoadingOlder = false,
                                    errorMessage = "Failed to load messages: ${result.error.message}",
                                )
                            }
                        }
                    }
                } finally {
                    if (activeHistoryRefreshOwner == owner) historyRefreshJob = null
                }
            }
    }

    fun loadOlderMessages() {
        val state = _uiState.value
        val sessionId = state.currentSessionId ?: return
        if (!state.hasOlderMessages || state.isLoadingOlder) return
        if (!latestPaging && loadedMessageOffset <= 0) return
        val oldOffset = loadedMessageOffset
        val newOffset =
            if (latestPaging) oldOffset + MESSAGE_PAGE_SIZE else (oldOffset - MESSAGE_PAGE_SIZE).coerceAtLeast(0)
        val limit = if (latestPaging) MESSAGE_PAGE_SIZE else oldOffset - newOffset
        val useLatestPaging = latestPaging
        val fence = captureHistoryLoadFence(sessionId) ?: return
        val owner = ++olderLoadCounter
        activeOlderLoadOwner = owner
        _uiState.update { it.copy(isLoadingOlder = true) }
        olderLoadJob =
            launchHistoryLoad {
                try {
                    val result =
                        fetchMessagePage(
                            sessionId,
                            newOffset,
                            limit,
                            order = if (useLatestPaging) "latest" else null,
                        )
                    if (result !is NetworkResult.Success || !isOlderLoadCurrent(fence, owner)) {
                        return@launchHistoryLoad
                    }
                    val effectiveOffset = result.data.pagination?.offset ?: newOffset
                    val older =
                        mapServerMessages(
                            sessionId,
                            result.data.messages.orEmpty(),
                            effectiveOffset,
                            useLatestPaging,
                        )
                    val persisted =
                        withContext(Dispatchers.IO) {
                            repo.persistMessagesIfCurrent(
                                older,
                                sessionId,
                                fence.transcriptRevision,
                            ) {
                                isOlderLoadCurrent(fence, owner)
                            }
                        }
                    if (!persisted || !isOlderLoadCurrent(fence, owner)) return@launchHistoryLoad
                    _uiState.update { current ->
                        if (!isOlderLoadCurrent(fence, owner)) return@update current
                        loadedMessageOffset = effectiveOffset
                        val mergedMessages = (older + current.messages).distinctBy { it.id }
                        val hasOlder =
                            if (useLatestPaging) {
                                val returned = result.data.pagination?.returned ?: older.size
                                returned >= limit && older.isNotEmpty()
                            } else {
                                effectiveOffset > 0 && older.isNotEmpty()
                            }
                        current.copy(
                            messages = mergedMessages,
                            todos = restoredTodos(current.todos, mergedMessages),
                            hasOlderMessages = hasOlder,
                        )
                    }
                } finally {
                    releaseOlderLoad(owner)
                }
            }
    }

    private fun isOlderLoadCurrent(
        fence: HistoryLoadFence,
        owner: Long,
    ): Boolean = activeOlderLoadOwner == owner && isHistoryLoadFenceCurrent(fence)

    private fun retireOlderLoad() {
        val owner = activeOlderLoadOwner ?: return
        if (activeOlderLoadOwner != owner) return
        activeOlderLoadOwner = null
        olderLoadJob?.cancel()
        olderLoadJob = null
        _uiState.update { it.copy(isLoadingOlder = false) }
    }

    private fun releaseOlderLoad(owner: Long) {
        if (activeOlderLoadOwner != owner) return
        activeOlderLoadOwner = null
        olderLoadJob = null
        _uiState.update { it.copy(isLoadingOlder = false) }
    }

    fun syncCurrentSession() {
        val state = _uiState.value
        val sessionId = state.currentSessionId ?: return
        if (isSyncingMessages || state.isLoading || state.isLoadingOlder || state.isAgentTyping ||
            _streamingState.value.streamingMessage != null
        ) {
            return
        }
        val nextOffset =
            if (latestPaging) {
                0
            } else {
                state.messages
                    .mapNotNull { serverMessageIndex(it.id, sessionId) }
                    .maxOrNull()
                    ?.plus(1)
                    ?: loadedMessageOffset
            }
        val fence = captureHistoryLoadFence(sessionId) ?: return
        val owner = ++syncCounter
        activeSyncOwner = owner
        isSyncingMessages = true
        syncJob =
            launchHistoryLoad {
                try {
                    val result =
                        fetchMessagePage(
                            sessionId,
                            nextOffset,
                            MESSAGE_PAGE_SIZE,
                            order = if (latestPaging) "latest" else null,
                        )
                    if (!isSyncCurrent(fence, owner)) return@launchHistoryLoad
                    when (result) {
                        is NetworkResult.Success -> {
                            if (!isSyncCurrent(fence, owner)) return@launchHistoryLoad
                            val incoming = mapServerMessages(sessionId, result.data.messages.orEmpty(), nextOffset)
                            if (!isSyncCurrent(fence, owner)) return@launchHistoryLoad
                            if (incoming.isEmpty()) return@launchHistoryLoad
                            val persisted =
                                withContext(Dispatchers.IO) {
                                    repo.persistMessagesIfCurrent(
                                        incoming,
                                        sessionId,
                                        fence.transcriptRevision,
                                    ) {
                                        isSyncCurrent(fence, owner)
                                    }
                                }
                            if (!persisted || !isSyncCurrent(fence, owner)) return@launchHistoryLoad
                            _uiState.update { current ->
                                if (!isSyncCurrent(fence, owner)) return@update current
                                val merged =
                                    mergeSyncedMessages(
                                        current = current.messages,
                                        incoming = incoming,
                                        isServerMessage = { id ->
                                            serverMessageIndex(id, sessionId) != null
                                        },
                                    )
                                if (sameMessages(current.messages, merged)) {
                                    current
                                } else {
                                    current.copy(
                                        messages = merged,
                                        todos = restoredTodos(current.todos, merged),
                                    )
                                }
                            }
                        }

                        is NetworkResult.Failure -> {}
                    }
                } finally {
                    releaseSync(owner)
                }
            }
    }

    private fun isSyncCurrent(
        fence: HistoryLoadFence,
        owner: Long,
    ): Boolean = activeSyncOwner == owner && isHistoryLoadFenceCurrent(fence)

    private fun retireSync() {
        activeSyncOwner = null
        syncCounter++
        syncJob?.cancel()
        syncJob = null
        isSyncingMessages = false
    }

    private fun releaseSync(owner: Long) {
        if (activeSyncOwner != owner) return
        activeSyncOwner = null
        syncJob = null
        isSyncingMessages = false
    }

    private fun restoredTodos(
        currentTodos: List<TodoItem>,
        messages: List<ChatMessage>,
    ): List<TodoItem> = hydrateTodosFromMessages(messages).ifEmpty { currentTodos }

    private suspend fun fetchServerMessageCount(
        sessionId: String,
        fence: HistoryLoadFence,
        owner: Long,
    ): Int? {
        if (!isHistoryRefreshCurrent(fence, owner)) return null
        val known =
            _uiState.value.sessions
                .find { it.id == sessionId }
                ?.messageCount
        if (known != null) return known.takeIf { isHistoryRefreshCurrent(fence, owner) }
        val result =
            withContext(Dispatchers.IO) {
                safeApiCall { ApiClient.hermesApi.getSessions(limit = 500, offset = 0, order = "recent") }
            }
        if (result is NetworkResult.Success) {
            if (!isHistoryRefreshCurrent(fence, owner)) return null
            val sessions = result.data.sessions.orEmpty()
            val count = sessions.find { it.id == sessionId }?.message_count
            if (count != null) {
                _uiState.update { current ->
                    if (!isHistoryRefreshCurrent(fence, owner)) return@update current
                    current.copy(
                        sessions =
                            current.sessions.map {
                                if (it.id == sessionId) it.copy(messageCount = count) else it
                            },
                    )
                }
                return count.takeIf { isHistoryRefreshCurrent(fence, owner) }
            }
        }
        return (_uiState.value.messages.size).takeIf { isHistoryRefreshCurrent(fence, owner) }
    }

    private suspend fun fetchMessagePage(
        sessionId: String,
        offset: Int,
        limit: Int,
        order: String? = null,
    ) = withContext(Dispatchers.IO) {
        safeApiCall {
            ApiClient.hermesApi.getSessionMessages(
                sessionId = sessionId,
                limit = limit,
                offset = offset,
                includeCompacted = true,
                order = order,
            )
        }
    }

    private fun mapServerMessages(
        sessionId: String,
        messages: List<SessionMessage>,
        offset: Int,
        useLatestPaging: Boolean = latestPaging,
    ): List<ChatMessage> {
        val existingWithReasoning =
            _uiState.value.messages.filter { it.reasoningText.isNotBlank() }
        val existingReasoningById = existingWithReasoning.associateBy { it.id }
        val uniqueReasoningByContent =
            existingWithReasoning
                .groupBy { it.role to it.content }
                .mapNotNull { (key, matches) ->
                    matches.singleOrNull()?.let { key to it }
                }.toMap()
        val incomingRoleAndContentCounts =
            messages
                .filter(::isDisplayableServerMessage)
                .map { msg ->
                    val role =
                        when (msg.role?.lowercase()) {
                            "user" -> MessageRole.USER
                            "system" -> MessageRole.SYSTEM
                            "tool" -> MessageRole.TOOL
                            else -> MessageRole.ASSISTANT
                        }
                    role to msg.contentText
                }.groupingBy { it }
                .eachCount()

        return messages.mapIndexedNotNull { index, msg ->
            if (!isDisplayableServerMessage(msg)) {
                return@mapIndexedNotNull null
            }
            val role =
                when (msg.role?.lowercase()) {
                    "user" -> MessageRole.USER
                    "system" -> MessageRole.SYSTEM
                    "tool" -> MessageRole.TOOL
                    else -> MessageRole.ASSISTANT
                }
            val globalIndex = offset + index
            val id =
                if (useLatestPaging) {
                    msg.id?.let { "rest-$sessionId-$it" }
                        ?: "rest-$sessionId-$globalIndex"
                } else {
                    "rest-$sessionId-$globalIndex"
                }
            val content = msg.contentText
            val roleAndContent = role to content
            val preservedReasoning =
                existingReasoningById[id]
                    ?.takeIf { existing ->
                        existing.role == role && existing.content == content
                    }
                    ?: uniqueReasoningByContent[roleAndContent]
                        ?.takeIf { incomingRoleAndContentCounts[roleAndContent] == 1 }
            val reasoning =
                msg.reasoningText.ifBlank {
                    preservedReasoning?.reasoningText.orEmpty()
                }
            val timestamp =
                msg.timestampText
                    ?.toDoubleOrNull()
                    ?.times(1000)
                    ?.toLong()
                    ?: System.currentTimeMillis()
            var finalContent = content
            var attachments: List<Attachment>? = null
            if (role == MessageRole.ASSISTANT && content.contains("MEDIA:")) {
                val items = HostMediaExtractor.extract(content)
                if (items.isNotEmpty()) {
                    finalContent = HostMediaExtractor.strip(content)
                    attachments =
                        items
                            .mapNotNull { item ->
                                val path = GatewayFileClient.normalizePath(item.path) ?: return@mapNotNull null
                                Attachment(
                                    uri = path,
                                    name = mediaNameFromPath(item.path),
                                    mimeType = mediaMimeForPath(item.path),
                                    size = 0,
                                    gatewayPath = path,
                                    source = AttachmentSource.GATEWAY,
                                )
                            }.takeIf { it.isNotEmpty() }
                }
            }
            ChatMessage(
                id = id,
                role = role,
                content = finalContent,
                reasoningText = reasoning,
                attachments = attachments,
                timestamp = timestamp,
                isStreaming = false,
                toolName = msg.toolName,
                toolCallId = msg.toolCallId,
                toolStatus = if (role == MessageRole.TOOL) ToolStatus.COMPLETED else null,
                displayKind = msg.displayKind,
            )
        }
    }

    private fun hydrateResumeMessages(
        sessionId: String,
        payload: Any?,
        replaceExisting: Boolean,
    ) {
        val rawMessages = payload as? List<*> ?: return
        val resumedMessages =
            rawMessages.mapIndexedNotNull { index, item ->
                val message =
                    item as? Map<*, *> ?: return@mapIndexedNotNull null
                val roleName = (message["role"] as? String)?.lowercase()
                val role =
                    when (roleName) {
                        "user" -> MessageRole.USER
                        "system" -> MessageRole.SYSTEM
                        "tool" -> MessageRole.TOOL
                        "assistant" -> MessageRole.ASSISTANT
                        else -> return@mapIndexedNotNull null
                    }
                val content =
                    (message["text"] as? String)
                        ?: (message["content"] as? String)
                        ?: if (role == MessageRole.TOOL) {
                            (message["context"] as? String)
                                ?.takeIf { it.isNotBlank() }
                                ?: (message["name"] as? String).orEmpty()
                        } else {
                            ""
                        }
                if (content.isBlank()) return@mapIndexedNotNull null
                val reasoning =
                    (message["reasoning"] as? String)
                        ?: (message["reasoning_text"] as? String).orEmpty()
                val timestampSeconds =
                    when (val timestamp = message["timestamp"]) {
                        is Number -> timestamp.toDouble()
                        is String -> timestamp.toDoubleOrNull()
                        else -> null
                    }
                val mediaItems =
                    if (role == MessageRole.ASSISTANT) {
                        HostMediaExtractor.extract(content)
                    } else {
                        emptyList()
                    }
                val attachments =
                    mediaItems
                        .map { item ->
                            Attachment(
                                uri = item.path,
                                name = mediaNameFromPath(item.path),
                                mimeType = mediaMimeForPath(item.path),
                                size = 0,
                                gatewayPath = item.path,
                                source = AttachmentSource.GATEWAY,
                            )
                        }.takeIf { it.isNotEmpty() }
                ChatMessage(
                    id = "resume-$sessionId-$index",
                    role = role,
                    content =
                        if (mediaItems.isEmpty()) {
                            content
                        } else {
                            HostMediaExtractor.strip(content)
                        },
                    reasoningText = reasoning,
                    attachments = attachments,
                    timestamp =
                        timestampSeconds
                            ?.times(1000)
                            ?.toLong()
                            ?: System.currentTimeMillis() + index,
                    isStreaming = false,
                    toolName =
                        if (role == MessageRole.TOOL) {
                            (message["tool_name"] as? String)
                                ?: (message["name"] as? String)
                        } else {
                            null
                        },
                    toolCallId =
                        if (role == MessageRole.TOOL) {
                            (message["tool_call_id"] as? String)
                                ?: (message["tool_id"] as? String)
                        } else {
                            null
                        },
                    displayKind = message["display_kind"] as? String,
                    toolStatus = if (role == MessageRole.TOOL) ToolStatus.COMPLETED else null,
                )
            }
        if (resumedMessages.isEmpty()) return

        _uiState.update { state ->
            if (state.currentSessionId != sessionId) return@update state
            val hasTranscript =
                !replaceExisting &&
                    state.messages.any { message ->
                        serverMessageIndex(message.id, sessionId) != null ||
                            message.role != MessageRole.SYSTEM
                    }
            if (hasTranscript) {
                state.copy(isLoading = false)
            } else {
                state.copy(
                    messages = resumedMessages,
                    isLoading = false,
                    hasOlderMessages = false,
                    isLoadingOlder = false,
                )
            }
        }
        val hasResumeHistory =
            _uiState.value.messages.any {
                it.id.startsWith("resume-$sessionId-")
            }
        if (hasResumeHistory) {
            loadedMessageOffset = 0
        }
    }

    // ── Issue #724: attach host-path MEDIA: files as real attachments ────
    //
    // The gateway's WebSocket stream delivers the raw `MEDIA:<path>` directive.
    // We retain the path as opaque gateway state and fetch through
    // [GatewayFileClient], which uses the normal authenticated Retrofit client.
    // The directive text is stripped from the message body. Pure parsing lives
    // in [HostMediaExtractor].

    /**
     * ViewModel-side handler for [ReducerEffect.AttachHostMedia]: find the local
     * message by id, convert any `MEDIA:<path>` directives into [Attachment]s
     * (via the authenticated gateway client) and strip them from the text. Role,
     * reasoning, timestamp and existing attachments are preserved; new gateway
     * attachments are appended. Idempotent — skips if gateway attachments for
     * the same paths already exist.
     */
    private suspend fun attachHostMedia(
        sessionId: String,
        messageId: String,
        acceptedRevision: Long,
    ) {
        val current = _uiState.value.messages.find { it.id == messageId } ?: return
        val content = current.content
        val items = HostMediaExtractor.extract(content)
        if (items.isEmpty()) {
            repo.persistMessage(current, sessionId, acceptedRevision)
            return
        }

        val existingPaths =
            current.attachments
                .orEmpty()
                .mapNotNull { it.gatewayPath }
                .toSet()
        val newAttachments =
            items.mapNotNull { item ->
                val path = GatewayFileClient.normalizePath(item.path) ?: return@mapNotNull null
                if (path in existingPaths) return@mapNotNull null
                Attachment(
                    uri = path,
                    name = mediaNameFromPath(item.path),
                    mimeType = mediaMimeForPath(item.path),
                    size = 0,
                    gatewayPath = path,
                    source = AttachmentSource.GATEWAY,
                )
            }
        if (newAttachments.isEmpty()) {
            repo.persistMessage(current, sessionId, acceptedRevision)
            return
        }

        val updatedMessage =
            current.copy(
                content = HostMediaExtractor.strip(content),
                attachments =
                    (current.attachments.orEmpty() + newAttachments)
                        .distinctBy { it.gatewayPath ?: it.uri },
            )
        _uiState.update { state ->
            state.copy(
                messages =
                    state.messages.map { msg ->
                        if (msg.id == messageId) updatedMessage else msg
                    },
            )
        }
        repo.persistMessage(updatedMessage, sessionId, acceptedRevision)
    }

    /**
     * Open an attachment when its chip/thumbnail is tapped.
     *
     * - LOCAL (user-picked) files: open the original `content://` URI
     *   directly via [android.content.Intent.ACTION_VIEW] — the resolver
     *   already grants read access for the picked document. If that fails
     *   (e.g. the permission lapsed), we copy to cache and retry via
     *   FileProvider so the tap is never a silent no-op.
     * - GATEWAY audio/video: hand a profile-fenced range session to the in-app
     *   player, without materializing the full file or exposing credentials.
     * - Other GATEWAY (agent `MEDIA:`) files: fetch the bytes via
     *   [GatewayFileClient], write them to a cache file, and open with
     *   [android.content.Intent.ACTION_VIEW] through FileProvider.
     *
     * Failures surface through [ChatUiState.openError] (non-blocking
     * snackbar); the tap is never swallowed.
     */
    fun openAttachment(attachment: Attachment) {
        val application = getApplication<Application>()
        val ctx = application.applicationContext
        val cacheDir = application.cacheDir
        when (val route = routeAttachmentOpen(attachment)) {
            is SecureMediaOpenRoute.Player -> {
                _uiState.update { it.copy(mediaPlayerRequest = route.request) }
                return
            }

            SecureMediaOpenRoute.OpenLocal -> Unit
            SecureMediaOpenRoute.DownloadAndOpen -> Unit
        }
        if (attachment.source == AttachmentSource.LOCAL) {
            // Best-effort direct open of the picked content URI.
            runCatching { openWithView(ctx, android.net.Uri.parse(attachment.uri), attachment.mimeType) }
                .onSuccess { return }
                .onFailure { /* fall through to local cache-copy below */ }
            viewModelScope.launch(Dispatchers.IO) {
                val localFile =
                    runCatching {
                        val uri = android.net.Uri.parse(attachment.uri)
                        val bytes =
                            ctx.contentResolver.openInputStream(uri)?.use { it.readBytesLimited() }
                                ?: error("Could not read local attachment")
                        val localCacheDir = java.io.File(cacheDir, "local_attachments").apply { mkdirs() }
                        val localCache =
                            java.io.File(
                                localCacheDir,
                                "${GatewayFileClient.sha256(attachment.uri)}.media",
                            )
                        localCache.writeBytes(bytes)
                        GatewayFile(
                            name = attachment.name,
                            mimeType = attachment.mimeType,
                            cacheFile = localCache,
                        )
                    }.getOrElse {
                        showOpenError(R.string.attachment_error_open, attachment.name)
                        return@launch
                    }
                openBytes(ctx, localFile)
            }
            return
        }
        val path =
            attachment.gatewayPath ?: run {
                showOpenError(R.string.attachment_error_missing_gateway_path, attachment.name)
                return
            }
        viewModelScope.launch {
            _uiState.update { it.copy(openingAttachmentPath = path) }
            try {
                when (val result = GatewayFileClient.fetch(path, java.io.File(cacheDir, "gateway_media"))) {
                    is GatewayFileResult.Success -> {
                        openBytes(ctx, result.file)
                    }

                    is GatewayFileResult.NotFound -> {
                        showOpenError(R.string.attachment_error_not_found, attachment.name)
                    }

                    is GatewayFileResult.Forbidden -> {
                        showOpenError(R.string.attachment_error_access_denied, attachment.name)
                    }

                    is GatewayFileResult.TooLarge -> {
                        showOpenError(R.string.attachment_error_too_large, attachment.name)
                    }

                    is GatewayFileResult.Unauthorized -> {
                        showOpenError(R.string.attachment_error_session_expired, attachment.name)
                    }

                    is GatewayFileResult.Failure -> {
                        showOpenError(R.string.attachment_error_open, attachment.name)
                    }
                }
            } finally {
                _uiState.update { state ->
                    if (state.openingAttachmentPath == path) state.copy(openingAttachmentPath = null) else state
                }
            }
        }
    }

    /** Open bytes written to a cache file via FileProvider + ACTION_VIEW. */
    private fun openBytes(
        ctx: android.content.Context,
        file: GatewayFile,
    ) {
        runCatching {
            val uri =
                androidx.core.content.FileProvider.getUriForFile(
                    ctx,
                    "${ctx.packageName}.fileprovider",
                    file.cacheFile,
                )
            openWithView(ctx, uri, file.mimeType)
        }.onFailure { showOpenError(R.string.attachment_error_open, file.name) }
    }

    /** Fire an ACTION_VIEW intent; throws if no activity can handle the type. */
    private fun openWithView(
        ctx: android.content.Context,
        uri: android.net.Uri,
        mimeType: String,
    ) {
        val viewIntent =
            android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType.ifBlank { "*/*" })
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        try {
            ctx.startActivity(viewIntent)
        } catch (e: Throwable) {
            val fallbackIntent =
                android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "*/*")
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            val chooser =
                android.content.Intent.createChooser(
                    fallbackIntent,
                    ctx.getString(R.string.attachment_chooser_open),
                ).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            ctx.startActivity(chooser)
        }
    }

    private fun showOpenError(
        @androidx.annotation.StringRes resourceId: Int,
        vararg args: Any,
    ) {
        val message = getApplication<Application>().getString(resourceId, *args)
        _uiState.update { it.copy(openError = message) }
    }

    fun clearOpenError() {
        _uiState.update { it.copy(openError = null) }
    }

    fun closeMediaPlayer() {
        _uiState.update { it.copy(mediaPlayerRequest = null) }
    }

    private fun serverMessageIndex(
        id: String,
        sessionId: String,
    ): Int? = id.removePrefix("rest-$sessionId-").takeIf { it != id }?.toIntOrNull()

    internal fun sameMessages(
        left: List<ChatMessage>,
        right: List<ChatMessage>,
    ): Boolean =
        left.size == right.size &&
            left.zip(right).all { (a, b) ->
                a.id == b.id &&
                    a.role == b.role &&
                    a.content == b.content &&
                    a.reasoningText == b.reasoningText &&
                    a.attachments == b.attachments &&
                    a.toolName == b.toolName &&
                    a.toolCallId == b.toolCallId &&
                    a.toolStatus == b.toolStatus &&
                    a.displayKind == b.displayKind
            }

    // ── UI actions ───────────────────────────────────────────────────────

    /**
     * Dismiss the active clarify prompt and reject it (tell the agent no answer
     * was given).
     *
     * The backend's clarify tool blocks the agent thread waiting for a response
     * (CLI timeout is 120s). A silent dismiss would leave the agent hanging
     * until that timeout, so we send a cancel sentinel
     * ([CLARIFY_DISMISS_RESPONSE]) over `clarify.respond` to unblock it.
     *
     * This is a *reject*, not an instruction to proceed — the agent is told no
     * answer was provided and should re-ask or back off, NOT charge ahead.
     *
     * Unlike [respondToClarify] we do NOT append a user chat bubble: a dismiss
     * is not something the user typed, so faking a USER message would be
     * dishonest. We instead surface a short SYSTEM note so the dismissal is
     * visible in the transcript.
     */
    fun dismissClarify() {
        val state = _uiState.value
        if (state.currentSessionId == null) return
        val runtimeId = runtimeSessionId ?: return
        val expected = state.clarifyRequest ?: return
        val requestId = expected.clarifyId ?: return
        val profileId = expected.sourceProfileId ?: return
        val generation = expected.connectionGeneration ?: return
        if (expected.sessionId != runtimeId) return
        viewModelScope.launch(Dispatchers.IO) {
            expected.serverRequestBinding?.let { serverBinding ->
                if (wsClient.respondToServerRequest(serverBinding, buildJsonObject {})) {
                    if (replaceClarifyIfCurrent(expected, null)) {
                        addSystemMessage("Clarify dismissed — no answer sent", persist = true)
                    }
                }
                return@launch
            }
            val dismissedIds = mutableSetOf<String>()
            for (question in expected.resolvedQuestions) {
                if (!clarifyRequestIsCurrent(expected, state.currentSessionId, runtimeId)) break
                val sent =
                    wsClient.respondToClarify(
                        sessionId = runtimeId,
                        clarifyRequestId = requestId,
                        questionId = expected.wireQuestionId(question),
                        answer = CLARIFY_DISMISS_RESPONSE,
                        sourceProfileId = profileId,
                        sourceConnectionGeneration = generation,
                    )
                if (!sent) break
                dismissedIds += question.qid
            }
            if (dismissedIds.isEmpty()) return@launch

            val remaining = expected.resolvedQuestions.filterNot { it.qid in dismissedIds }
            val completedCurrentRequest =
                replaceClarifyIfCurrent(expected, expected.withRemainingQuestions(remaining))
            if (completedCurrentRequest && remaining.isEmpty()) {
                addSystemMessage("Clarify dismissed — no answer sent", persist = true)
            }
        }
    }

    fun respondToClarify(option: String) {
        val clarify = _uiState.value.clarifyRequest ?: return
        val qid = clarify.questionId ?: clarify.questions.singleOrNull()?.qid
        respondToClarifyBatch(clarify, mapOf((qid ?: "q0") to option))
    }

    fun respondToClarifyBatch(
        expected: ClarifyUi,
        answers: Map<String, String>,
    ) {
        val state = _uiState.value
        val sessionId = state.currentSessionId ?: return
        val runtimeId = runtimeSessionId ?: return
        val profileId = expected.sourceProfileId ?: return
        val generation = expected.connectionGeneration ?: return
        if (state.clarifyRequest != expected ||
            expected.sessionId != runtimeId
        ) {
            return
        }
        val questions = expected.resolvedQuestions
        val normalized = answers.mapValues { it.value.trim() }.filterValues { it.isNotEmpty() }
        if (normalized.isEmpty() || normalized.keys.any { answerId -> questions.none { it.qid == answerId } }) return
        val requestId = expected.clarifyId ?: return
        val acceptedRevision = repo.replacementGeneration(sessionId)
        viewModelScope.launch(Dispatchers.IO) {
            expected.serverRequestBinding?.let { serverBinding ->
                val merged = normalized + expected.lockedAnswers
                val result =
                    if (expected.questions.isNotEmpty()) {
                        buildJsonObject {
                            put("answers", buildJsonObject { merged.forEach { (id, answer) -> put(id, answer) } })
                        }
                    } else {
                        buildJsonObject { put("answer", normalized.values.first()) }
                    }
                if (!wsClient.respondToServerRequest(serverBinding, result)) return@launch
                val displayAnswer = normalized.values.joinToString("\n")
                val userMessage = ChatMessage(role = MessageRole.USER, content = displayAnswer)
                if (replaceClarifyIfCurrent(expected, null)) {
                    _uiState.update { it.copy(messages = it.messages + userMessage, isAgentTyping = true) }
                    repo.persistMessage(userMessage, sessionId, acceptedRevision)
                }
                return@launch
            }
            val answeredIds = mutableSetOf<String>()
            for (question in questions) {
                val answer = normalized[question.qid] ?: continue
                if (!clarifyRequestIsCurrent(expected, sessionId, runtimeId)) break
                val sent =
                    wsClient.respondToClarify(
                        sessionId = runtimeId,
                        clarifyRequestId = requestId,
                        questionId = expected.wireQuestionId(question),
                        answer = answer,
                        sourceProfileId = profileId,
                        sourceConnectionGeneration = generation,
                    )
                if (!sent) break
                answeredIds += question.qid
            }
            if (answeredIds.isEmpty()) return@launch

            val remaining = questions.filterNot { it.qid in answeredIds }
            val displayAnswer =
                questions.filter { it.qid in answeredIds }.joinToString(
                    "\n",
                ) { normalized.getValue(it.qid) }
            val userMessage = ChatMessage(role = MessageRole.USER, content = displayAnswer)
            _uiState.update { current ->
                if (current.clarifyRequest == expected) {
                    current.copy(
                        clarifyRequest = expected.withRemainingQuestions(remaining),
                        messages = current.messages + userMessage,
                        isAgentTyping = remaining.isEmpty(),
                    ).let { updated ->
                        if (remaining.isEmpty()) updated.copy(clarifyRequest = null) else updated
                    }
                } else {
                    current
                }
            }
            if (_uiState.value.messages.any { it.id == userMessage.id }) {
                repo.persistMessage(userMessage, sessionId, acceptedRevision)
            }
        }
    }

    private fun ClarifyUi.wireQuestionId(question: ClarifyQuestionUi): String? =
        if (questions.isNotEmpty()) question.qid else questionId

    private fun clarifyRequestIsCurrent(
        expected: ClarifyUi,
        sessionId: String,
        runtimeId: String,
    ): Boolean =
        _uiState.value.clarifyRequest == expected &&
            _uiState.value.currentSessionId == sessionId &&
            runtimeSessionId == runtimeId

    private fun replaceClarifyIfCurrent(
        expected: ClarifyUi,
        replacement: ClarifyUi?,
    ): Boolean {
        while (true) {
            val current = _uiState.value
            if (current.clarifyRequest != expected) return false
            if (_uiState.compareAndSet(current, current.copy(clarifyRequest = replacement))) return true
        }
    }

    private fun ClarifyUi.withRemainingQuestions(remaining: List<ClarifyQuestionUi>): ClarifyUi? =
        when {
            remaining.isEmpty() -> null
            questions.isEmpty() -> this
            else -> copy(questions = remaining)
        }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun clearBackgroundComplete() {
        _uiState.update { it.copy(backgroundCompleteMessage = null) }
    }

    // ── Approval flow ───────────────────────────────────────────────────

    private fun handleServerRequest(request: WsEvent.ServerRequest) {
        val sessionId = request.params["session_id"] as? String ?: return
        val binding =
            privilegedBinding(
                request.id,
                sessionId,
                request.sourceProfileId,
                request.connectionGeneration,
            ) ?: return
        when (request.method) {
            "sudo" -> {
                val serverBinding =
                    ServerRequestBinding(request.id, sessionId, binding.profileId, binding.connectionGeneration)
                _uiState.update { state ->
                    if (state.sudoPrompt?.serverRequestBinding == serverBinding) {
                        state
                    } else {
                        state.copy(
                            sudoPrompt = SudoPromptUi(binding = binding, serverRequestBinding = serverBinding),
                            isAgentTyping = false,
                        )
                    }
                }
            }
            "secret" -> {
                val serverBinding =
                    ServerRequestBinding(request.id, sessionId, binding.profileId, binding.connectionGeneration)
                _uiState.update { state ->
                    if (state.secretPrompt?.serverRequestBinding == serverBinding) {
                        state
                    } else {
                        state.copy(
                            secretPrompt =
                                SecretPromptUi(
                                    binding = binding,
                                    envVar = request.params["env_var"] as? String,
                                    prompt = request.params["prompt"] as? String,
                                    serverRequestBinding = serverBinding,
                                ),
                            isAgentTyping = false,
                        )
                    }
                }
            }
            "vault.code", "vault.unlock_prompt", "vault.save_login" -> {
                val serverBinding =
                    ServerRequestBinding(request.id, sessionId, binding.profileId, binding.connectionGeneration)
                _uiState.update { state ->
                    if (state.vaultPrompt?.binding == serverBinding && state.vaultPrompt.method == request.method) {
                        state
                    } else {
                        state.copy(
                            vaultPrompt =
                                VaultPromptUi(
                                    binding = serverBinding,
                                    method = request.method,
                                    title = request.params["title"] as? String,
                                    prompt = request.params["prompt"] as? String,
                                    identifier = request.params["identifier"] as? String,
                                    requestedOrigin =
                                        (request.params["origin"] as? String)
                                            ?: (request.params["site"] as? String),
                                ),
                            isAgentTyping = false,
                        )
                    }
                }
            }
            "approval" -> {
                val serverBinding =
                    ServerRequestBinding(request.id, sessionId, binding.profileId, binding.connectionGeneration)
                val duplicate =
                    _uiState.value.messages.any {
                        it.approvalInfo?.serverRequestBinding == serverBinding
                    }
                if (duplicate) return
                _uiState.update { state ->
                    state.copy(
                        messages =
                            state.messages.filterNot {
                                val prior = it.approvalInfo?.serverRequestBinding
                                prior?.requestId == request.id && prior != serverBinding
                            },
                    )
                }
                handleApprovalRequest(
                    WsEvent.ApprovalRequest(
                        command = request.params["command"] as? String,
                        description = request.params["description"] as? String,
                        patternKeys = (request.params["pattern_keys"] as? List<*>)?.filterIsInstance<String>(),
                        sessionId = sessionId,
                        requestId = request.params["request_id"] as? String ?: request.id,
                        timeoutSeconds = (request.params["timeout_seconds"] as? Number)?.toDouble() ?: 120.0,
                        sourceProfileId = binding.profileId,
                        connectionGeneration = binding.connectionGeneration,
                        serverRequestId = request.id,
                    ),
                )
            }
            "clarify" -> {
                val locked =
                    (request.params["answers"] as? Map<*, *>)?.entries
                        ?.mapNotNull { (k, v) -> if (k is String && v is String) k to v else null }?.toMap().orEmpty()
                val questions =
                    (request.params["questions"] as? List<*>)?.mapIndexedNotNull { index, raw ->
                        val map = raw as? Map<*, *> ?: return@mapIndexedNotNull null
                        val text = map["question"] as? String ?: return@mapIndexedNotNull null
                        WsEvent.ClarifyQuestion(
                            qid = map["qid"] as? String ?: "q$index",
                            question = text,
                            choices = (map["choices"] as? List<*>)?.filterIsInstance<String>().orEmpty(),
                            multiSelect = map["multi_select"] as? Boolean ?: false,
                        )
                    }.orEmpty()
                val first = questions.firstOrNull()
                val current = _uiState.value.clarifyRequest
                val serverBinding =
                    ServerRequestBinding(request.id, sessionId, binding.profileId, binding.connectionGeneration)
                if (current?.serverRequestBinding == serverBinding) {
                    if (!request.replayed || current.questions.isNotEmpty()) return
                }
                handleWsEvent(
                    WsEvent.ClarifyRequest(
                        text = first?.question ?: request.params["question"] as? String,
                        options = first?.choices ?: (request.params["choices"] as? List<*>)?.filterIsInstance<String>(),
                        clarifyId = request.params["request_id"] as? String ?: request.id,
                        sessionId = sessionId,
                        questionId = first?.qid,
                        multiSelect = first?.multiSelect ?: false,
                        questions = questions,
                        sourceProfileId = binding.profileId,
                        connectionGeneration = binding.connectionGeneration,
                        serverRequestId = request.id,
                        lockedAnswers = locked,
                    ),
                )
            }
        }
    }

    private fun handleServerRequestCancelled(event: WsEvent.ServerRequestCancelled) {
        if (event.sourceProfileId != selectedProfileId() || event.connectionGeneration == null) return
        when (event.method) {
            "approval" ->
                _uiState.update { state ->
                    state.copy(
                        messages =
                            state.messages.map { message ->
                                val binding = message.approvalInfo?.serverRequestBinding
                                if (binding?.requestId == event.id &&
                                    binding.runtimeSessionId == event.sessionId &&
                                    binding.profileId == event.sourceProfileId &&
                                    binding.connectionGeneration == event.connectionGeneration
                                ) {
                                    message.copy(
                                        approvalInfo = null,
                                    )
                                } else {
                                    message
                                }
                            },
                    )
                }
            "clarify" ->
                _uiState.update { state ->
                    val binding = state.clarifyRequest?.serverRequestBinding
                    if (binding?.requestId == event.id &&
                        binding.runtimeSessionId == event.sessionId &&
                        binding.profileId == event.sourceProfileId &&
                        binding.connectionGeneration == event.connectionGeneration
                    ) {
                        state.copy(
                            clarifyRequest = null,
                        )
                    } else {
                        state
                    }
                }
            "vault.code", "vault.unlock_prompt", "vault.save_login" ->
                _uiState.update { state ->
                    val prompt = state.vaultPrompt
                    if (prompt?.method == event.method && serverCancellationMatches(prompt.binding, event)) {
                        state.copy(vaultPrompt = null)
                    } else {
                        state
                    }
                }
            "sudo" ->
                _uiState.update { state ->
                    if (serverCancellationMatches(state.sudoPrompt?.serverRequestBinding, event)) {
                        state.copy(sudoPrompt = null)
                    } else {
                        state
                    }
                }
            "secret" ->
                _uiState.update { state ->
                    if (serverCancellationMatches(state.secretPrompt?.serverRequestBinding, event)) {
                        state.copy(secretPrompt = null)
                    } else {
                        state
                    }
                }
        }
    }

    private fun serverCancellationMatches(
        binding: ServerRequestBinding?,
        event: WsEvent.ServerRequestCancelled,
    ): Boolean =
        binding?.requestId == event.id &&
            binding.runtimeSessionId == event.sessionId &&
            binding.profileId == event.sourceProfileId &&
            binding.connectionGeneration == event.connectionGeneration

    private fun clearPrivilegedControls() {
        catalogRequestGeneration++
        _uiState.update { it.copy(commandCatalog = CommandCatalog()) }
        dismissUndoConfirm()
        approvalExpiryJobs.values.forEach(Job::cancel)
        approvalExpiryJobs.clear()
        _uiState.update { state ->
            state.copy(
                messages = state.messages.map { message -> message.copy(approvalInfo = null) },
                sudoPrompt = null,
                secretPrompt = null,
                vaultPrompt = null,
            )
        }
    }

    private fun handleApprovalRequest(event: WsEvent.ApprovalRequest) {
        val binding =
            privilegedBinding(
                requestId = event.requestId,
                eventSessionId = event.sessionId,
                profileId = event.sourceProfileId,
                generation = event.connectionGeneration,
            ) ?: return
        val description = event.description ?: event.command ?: "Unknown command"
        val content = "**Approval Required**\n$description"
        val msg =
            ChatMessage(
                role = MessageRole.SYSTEM,
                content = content,
                approvalInfo =
                    ApprovalInfo(
                        command = event.command,
                        description = event.description,
                        patternKeys = event.patternKeys,
                        privilegedBinding = binding,
                        serverRequestBinding =
                            event.serverRequestId?.let {
                                ServerRequestBinding(
                                    it,
                                    binding.runtimeSessionId,
                                    binding.profileId,
                                    binding.connectionGeneration,
                                )
                            },
                    ),
            )
        _uiState.update { state ->
            state.copy(
                messages = state.messages + msg,
                isAgentTyping = false,
            )
        }
        scheduleApprovalExpiry(msg.id, binding, event.timeoutSeconds)
    }

    /**
     * Retire the controls once the gateway's own approval deadline has elapsed
     * locally. The gateway publishes the exact lifetime its waiting thread uses
     * (`timeout_seconds`), so the buttons disappear when the request they would
     * answer is already gone, rather than lingering and resolving nothing.
     */
    private fun scheduleApprovalExpiry(
        messageId: String,
        binding: PrivilegedRequestBinding,
        timeoutSeconds: Double,
    ) {
        val key = ApprovalTimerKey(messageId, binding)
        val job =
            viewModelScope.launch {
                delay((timeoutSeconds * 1_000.0).toLong().coerceAtLeast(1L))
                approvalExpiryJobs.remove(key, coroutineContext[Job])
                clearApprovalControls(messageId, binding)
            }
        approvalExpiryJobs.put(key, job)?.cancel()
    }

    /** Approve exactly once. Session-wide and permanent allows are not offered. */
    fun respondToApproval(
        messageId: String,
        binding: PrivilegedRequestBinding,
        action: String,
    ) {
        val choice =
            when (action) {
                "approve" -> "once"
                "deny" -> "deny"
                // "session" / "always" are deliberately unreachable: a phone
                // cannot show what a standing allow would later authorize.
                else -> return
            }
        submitApproval(messageId, binding, WsMethods.APPROVAL_RESPOND, mapOf("choice" to choice))
    }

    /** Explicit Cancel — a typed `approval.cancel`, not a denial and not a dismissal. */
    fun cancelApproval(
        messageId: String,
        binding: PrivilegedRequestBinding,
    ) {
        submitApproval(messageId, binding, WsMethods.APPROVAL_CANCEL, emptyMap())
    }

    private fun submitApproval(
        messageId: String,
        binding: PrivilegedRequestBinding,
        method: String,
        params: Map<String, String>,
    ) {
        if (!claimApprovalSubmission(messageId, binding)) return

        viewModelScope.launch(Dispatchers.IO) {
            val serverBinding =
                _uiState.value.messages.firstOrNull { it.id == messageId }
                    ?.approvalInfo?.serverRequestBinding
            var failureMessage: String? = null
            val accepted =
                if (serverBinding != null) {
                    wsClient.respondToServerRequest(
                        serverBinding,
                        buildJsonObject {
                            put(
                                "choice",
                                params["choice"] ?: if (method == WsMethods.APPROVAL_CANCEL) "deny" else "once",
                            )
                            put("all", false)
                        },
                    ).also { if (!it) failureMessage = "Request was not accepted" }
                } else {
                    runCatching {
                        wsClient.privilegedRequest(method = method, binding = binding, params = params).await()
                    }.fold(
                        onSuccess = { true },
                        onFailure = {
                            failureMessage = it.message
                            false
                        },
                    )
                }
            if (accepted) {
                approvalExpiryJobs.remove(ApprovalTimerKey(messageId, binding))?.cancel()
                clearApprovalControls(messageId, binding)
            } else {
                restoreApprovalControls(messageId, binding, failureMessage)
            }
        }
    }

    private fun claimApprovalSubmission(
        messageId: String,
        binding: PrivilegedRequestBinding,
    ): Boolean {
        while (true) {
            val state = _uiState.value
            var matched = false
            val messages =
                state.messages.map { message ->
                    val info = message.approvalInfo
                    if (message.id == messageId && info?.privilegedBinding == binding && !info.isSubmitting) {
                        matched = true
                        message.copy(approvalInfo = info.copy(isSubmitting = true))
                    } else {
                        message
                    }
                }
            if (!matched) return false
            if (_uiState.compareAndSet(state, state.copy(messages = messages))) return true
        }
    }

    private fun restoreApprovalControls(
        messageId: String,
        binding: PrivilegedRequestBinding,
        errorMessage: String?,
    ) {
        _uiState.update { state ->
            state.copy(
                messages =
                    state.messages.map { message ->
                        val info = message.approvalInfo
                        if (message.id == messageId && info?.privilegedBinding == binding) {
                            message.copy(approvalInfo = info.copy(isSubmitting = false))
                        } else {
                            message
                        }
                    },
                errorMessage = errorMessage,
            )
        }
    }

    private fun clearApprovalControls(
        messageId: String,
        binding: PrivilegedRequestBinding,
    ) {
        _uiState.update { state ->
            state.copy(
                messages =
                    state.messages.map { message ->
                        if (message.id == messageId && message.approvalInfo?.privilegedBinding == binding) {
                            message.copy(approvalInfo = null)
                        } else {
                            message
                        }
                    },
            )
        }
    }

    // ── Sudo / secret prompt flow (issue #524) ──────────────────────────

    /**
     * The agent needs the user's sudo password. Previously dropped → agent
     * hung forever. Now we surface a secure dialog and reply via sudo.respond.
     */
    private fun handleSudoRequest(event: WsEvent.SudoRequest) {
        val binding =
            privilegedBinding(
                requestId = event.requestId,
                eventSessionId = event.sessionId,
                profileId = event.sourceProfileId,
                generation = event.connectionGeneration,
            ) ?: return
        _uiState.update {
            it.copy(
                sudoPrompt = SudoPromptUi(binding),
                isAgentTyping = false,
            )
        }
    }

    /**
     * The agent needs a secret value (token/password). Previously dropped →
     * agent hung forever. Now we surface a secure dialog and reply via
     * secret.respond.
     */
    private fun handleSecretRequest(event: WsEvent.SecretRequest) {
        val binding =
            privilegedBinding(
                requestId = event.requestId,
                eventSessionId = event.sessionId,
                profileId = event.sourceProfileId,
                generation = event.connectionGeneration,
            ) ?: return
        _uiState.update {
            it.copy(
                secretPrompt = SecretPromptUi(binding, event.envVar, event.prompt),
                isAgentTyping = false,
            )
        }
    }

    /**
     * Back gesture or a tap outside the dialog. Deliberately a no-op: an
     * incidental dismissal is neither an answer nor a cancellation, and hiding
     * the dialog would strand a gateway thread that is still blocked. The user
     * cancels through the explicit Cancel action.
     */
    fun dismissSudo() = Unit

    /** Ordinary dismissal of the secret dialog. A no-op, for the same reason as [dismissSudo]. */
    fun dismissSecret() = Unit

    /** Send the user's sudo password back to the gateway on its exact bound request. */
    fun respondToSudo(password: String) {
        val prompt = _uiState.value.sudoPrompt ?: return
        if (password.isBlank()) return
        submitSudo(prompt, WsMethods.SUDO_RESPOND, mapOf("password" to password))
    }

    /** Explicit Cancel — a typed `sudo.cancel`, awaited, never a silent dismissal. */
    fun cancelSudo() {
        val prompt = _uiState.value.sudoPrompt ?: return
        submitSudo(prompt, WsMethods.SUDO_CANCEL, emptyMap())
    }

    /** Send the user's secret value back to the gateway on its exact bound request. */
    fun respondToSecret(value: String) {
        val prompt = _uiState.value.secretPrompt ?: return
        if (value.isBlank()) return
        submitSecret(prompt, WsMethods.SECRET_RESPOND, mapOf("value" to value))
    }

    /** Explicit Cancel — a typed `secret.cancel`, awaited, never a silent dismissal. */
    fun cancelSecret() {
        val prompt = _uiState.value.secretPrompt ?: return
        submitSecret(prompt, WsMethods.SECRET_CANCEL, emptyMap())
    }

    fun respondToVault(value: String) {
        val prompt = _uiState.value.vaultPrompt ?: return
        if (value.isBlank()) return
        submitVault(prompt, value)
    }

    fun respondToVaultLogin(
        identifier: String,
        password: String,
    ) {
        val prompt = _uiState.value.vaultPrompt ?: return
        if (prompt.method != "vault.save_login" ||
            !prompt.hasValidRequestedOrigin ||
            identifier.isBlank() ||
            password.isBlank()
        ) {
            return
        }
        submitVault(
            prompt,
            buildJsonObject {
                put("identifier", identifier)
                put("password", password)
            }.toString(),
        )
    }

    fun cancelVault() {
        _uiState.value.vaultPrompt?.let { submitVault(it, "") }
    }

    fun dismissVault() = Unit

    private fun submitVault(
        expected: VaultPromptUi,
        value: String,
    ) {
        val claimed = expected.copy(isSubmitting = true)
        while (true) {
            val state = _uiState.value
            if (state.vaultPrompt != expected || expected.isSubmitting) return
            if (_uiState.compareAndSet(state, state.copy(vaultPrompt = claimed))) break
        }
        viewModelScope.launch(Dispatchers.IO) {
            val accepted = wsClient.respondToServerRequest(claimed.binding, buildJsonObject { put("value", value) })
            _uiState.update { state ->
                if (state.vaultPrompt != claimed) {
                    state
                } else if (accepted) {
                    state.copy(vaultPrompt = null)
                } else {
                    state.copy(
                        vaultPrompt = claimed.copy(isSubmitting = false),
                        errorMessage = privilegedFailureMessage(),
                    )
                }
            }
        }
    }

    private fun submitSudo(
        prompt: SudoPromptUi,
        method: String,
        params: Map<String, String>,
    ) {
        val claimed = claimSudoSubmission(prompt) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val accepted =
                claimed.serverRequestBinding?.let {
                    wsClient.respondToServerRequest(
                        it,
                        buildJsonObject { put("value", params["password"].orEmpty()) },
                    )
                } ?: runCatching {
                    wsClient.privilegedRequest(method = method, binding = claimed.binding, params = params).await()
                }.isSuccess
            if (accepted) {
                _uiState.update { if (it.sudoPrompt == claimed) it.copy(sudoPrompt = null) else it }
            } else {
                _uiState.update {
                    if (it.sudoPrompt == claimed) {
                        it.copy(
                            sudoPrompt = claimed.copy(isSubmitting = false),
                            errorMessage = privilegedFailureMessage(),
                        )
                    } else {
                        it
                    }
                }
            }
        }
    }

    private fun claimSudoSubmission(expected: SudoPromptUi): SudoPromptUi? {
        val claimed = expected.copy(isSubmitting = true)
        while (true) {
            val state = _uiState.value
            if (state.sudoPrompt != expected || expected.isSubmitting) return null
            if (_uiState.compareAndSet(state, state.copy(sudoPrompt = claimed))) return claimed
        }
    }

    private fun submitSecret(
        prompt: SecretPromptUi,
        method: String,
        params: Map<String, String>,
    ) {
        val claimed = claimSecretSubmission(prompt) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val accepted =
                claimed.serverRequestBinding?.let {
                    wsClient.respondToServerRequest(
                        it,
                        buildJsonObject { put("value", params["value"].orEmpty()) },
                    )
                } ?: runCatching {
                    wsClient.privilegedRequest(method = method, binding = claimed.binding, params = params).await()
                }.isSuccess
            if (accepted) {
                _uiState.update { if (it.secretPrompt == claimed) it.copy(secretPrompt = null) else it }
            } else {
                _uiState.update {
                    if (it.secretPrompt == claimed) {
                        it.copy(
                            secretPrompt = claimed.copy(isSubmitting = false),
                            errorMessage = privilegedFailureMessage(),
                        )
                    } else {
                        it
                    }
                }
            }
        }
    }

    private fun claimSecretSubmission(expected: SecretPromptUi): SecretPromptUi? {
        val claimed = expected.copy(isSubmitting = true)
        while (true) {
            val state = _uiState.value
            if (state.secretPrompt != expected || expected.isSubmitting) return null
            if (_uiState.compareAndSet(state, state.copy(secretPrompt = claimed))) return claimed
        }
    }

    /**
     * Fixed failure text for the two secret-bearing verbs.
     *
     * A gateway error string is attacker- or bug-reachable and could echo the
     * submitted value; `errorMessage` is durable UI state. So sudo and secret
     * failures never render the throwable — only this constant.
     */
    private fun privilegedFailureMessage(): String =
        getApplication<Application>().getString(R.string.chat_privileged_not_acknowledged)

    /**
     * Bind a privileged event to the exact request it can answer.
     *
     * Every component has to be present and current at dispatch: the opaque
     * request id, the runtime session showing the request, the profile whose
     * connection delivered it, and that connection's generation. Anything
     * missing or stale yields `null`, and the caller drops the event rather than
     * surfacing controls that would resolve some other request.
     */
    private fun privilegedBinding(
        requestId: String,
        eventSessionId: String?,
        profileId: String?,
        generation: Int?,
    ): PrivilegedRequestBinding? {
        if (requestId.isBlank()) return null
        if (generation == null) return null
        if (profileId.isNullOrBlank() || profileId != selectedProfileId()) return null
        val runtimeId = eventSessionId?.takeIf { it.isNotBlank() } ?: return null
        if (runtimeId != runtimeSessionId) return null
        return PrivilegedRequestBinding(
            requestId = requestId,
            runtimeSessionId = runtimeId,
            profileId = profileId,
            connectionGeneration = generation,
        )
    }

    /**
     * A server expiry clears a live prompt only when it names that same exact
     * request on the same session, profile, and socket generation.
     */
    private fun expiryMatches(
        binding: PrivilegedRequestBinding,
        requestId: String,
        sessionId: String?,
        profileId: String?,
        generation: Int?,
    ): Boolean =
        requestId == binding.requestId &&
            profileId == binding.profileId &&
            generation == binding.connectionGeneration &&
            !sessionId.isNullOrBlank() &&
            sessionId == binding.runtimeSessionId

    fun reconnect() {
        retireSync()
        AuthSessionState.markAuthenticated()
        _uiState.update {
            it.copy(
                isLoading = true,
                errorMessage = null,
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            wsClient.rejectAllPending()
            wsClient.disconnect()
        }
        viewModelScope.launch {
            delay(500)
            connectWebSocket(setLoading = true)
        }
    }

    fun relogin(
        username: String,
        password: String,
        onResult: (Boolean, String?) -> Unit,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val endpoint =
                try {
                    AuthManager.endpointForBuild()
                } catch (e: IllegalArgumentException) {
                    withContext(Dispatchers.Main) {
                        onResult(false, e.message)
                    }
                    return@launch
                }
            val jsonMediaType = "application/json; charset=utf-8".toMediaType()
            val jsonBody = AuthPayloads.passwordLogin(username, password)

            try {
                val loginClient =
                    com.m57.hermescontrol.data.remote.OkHttpProvider.probe
                        .newBuilder()
                        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .build()

                val loginReq =
                    Request
                        .Builder()
                        .url(endpoint.resolve("auth/password-login"))
                        .header("Content-Type", "application/json")
                        .post(jsonBody.toRequestBody(jsonMediaType))
                        .build()
                loginClient.newCall(loginReq).execute().use { loginResp ->
                    if (!loginResp.isSuccessful) {
                        val msg =
                            when (loginResp.code) {
                                401 -> "Invalid username or password (401)"
                                403 -> "Forbidden (403)"
                                else -> "HTTP error code: ${loginResp.code}"
                            }
                        withContext(Dispatchers.Main) {
                            onResult(false, msg)
                        }
                        return@launch
                    }
                }

                // The login response updated the shared CookieJar. Keep the
                // one-use WebSocket ticket out of persistent token state;
                // reconnect() lets HermesWsClient mint it for the handshake.
                AuthManager.setToken(null)
                AuthManager.setWsAuthParam("ticket")
                AuthSessionState.markAuthenticated()

                withContext(Dispatchers.Main) {
                    onResult(true, null)
                    reconnect()
                }
            } catch (e: java.io.IOException) {
                withContext(Dispatchers.Main) {
                    onResult(false, "Connection failed: ${e.message}")
                }
            }
        }
    }

    private fun addSystemMessage(
        text: String,
        persist: Boolean = false,
    ) {
        val msg = ChatMessage(role = MessageRole.SYSTEM, content = text)
        val sessionId = _uiState.value.currentSessionId

        _uiState.update { it.copy(messages = it.messages + msg) }

        // Persist — OUTSIDE update{}
        if (persist && sessionId != null) {
            val acceptedRevision = repo.replacementGeneration(sessionId)
            viewModelScope.launch(Dispatchers.IO) {
                repo.persistMessage(msg, sessionId, acceptedRevision)
            }
        }
    }

    // ── Pending request tracking ─────────────────────────────────────────

    private fun trackRequest(
        id: String,
        method: String,
    ) {
        pendingRequests[id] = PendingRpcRequest(method)
    }

    /**
     * Track a `session.redirect` alongside the text it carried, so a `4010`
     * rejection (running agent cannot be redirected) can resend the same text
     * as a normal prompt instead of dropping it.
     */
    private fun trackRedirectRequest(
        id: String,
        sessionId: String,
        text: String,
        connectionBinding: ConnectionBinding,
    ) {
        pendingRequests[id] =
            PendingRpcRequest(
                method = WsMethods.SESSION_REDIRECT,
                redirectSessionId = sessionId,
                redirectText = text,
                redirectConnectionBinding = connectionBinding,
            )
    }

    private fun captureResumeFence(sessionId: String): ResumeFence? {
        val profileId = selectedProfileId()
        val binding = wsClient.connectionBinding(profileId) ?: return null
        return ResumeFence(
            storageSessionId = sessionId,
            runtimeSessionId = runtimeSessionId,
            profileId = profileId,
            connectionBinding = binding,
            generation = conversationGeneration,
            transcriptRevision = repo.replacementGeneration(sessionId),
        )
    }

    private fun isResumeFenceCurrent(
        fence: ResumeFence,
        allowRuntimeChange: Boolean = false,
        allowSessionChange: Boolean = false,
    ): Boolean =
        fence.generation == conversationGeneration &&
            fence.profileId == selectedProfileId() &&
            (allowSessionChange || fence.storageSessionId == _uiState.value.currentSessionId) &&
            (allowRuntimeChange || fence.runtimeSessionId == runtimeSessionId) &&
            fence.transcriptRevision == repo.replacementGeneration(fence.storageSessionId) &&
            wsClient.isConnectionBindingCurrent(fence.connectionBinding)

    private fun trackResumeRequest(
        id: String,
        fence: ResumeFence,
    ) {
        synchronized(resumeRequestLock) {
            invalidateResumeRequestsLocked()
            pendingRequests[id] =
                PendingRpcRequest(
                    method = WsMethods.SESSION_RESUME,
                    resumeSessionId = fence.storageSessionId,
                    resumeFence = fence,
                )
        }
    }

    private fun invalidateResumeRequests() {
        synchronized(resumeRequestLock) {
            invalidateResumeRequestsLocked()
        }
    }

    private fun invalidateResumeRequestsLocked() {
        pendingRequests.entries.removeIf { (id, request) ->
            if (request.method != WsMethods.SESSION_RESUME) return@removeIf false
            retiredResumeRequests += id
            while (retiredResumeRequests.size > MAX_RETIRED_RESUME_REQUESTS) {
                retiredResumeRequests.remove(retiredResumeRequests.first())
            }
            true
        }
    }

    private fun consumeRetiredResumeRequest(id: String): Boolean =
        synchronized(resumeRequestLock) {
            retiredResumeRequests.remove(id)
        }

    /**
     * Track a `session.create` alongside the attempt generation that issued it,
     * so a late answer from a superseded attempt cannot install itself over a
     * newer session, and so an answered attempt can stand its retry timer down.
     */
    private fun trackCreateRequest(
        id: String,
        generation: Long,
    ) {
        pendingRequests[id] =
            PendingRpcRequest(
                method = WsMethods.SESSION_CREATE,
                createGeneration = generation,
            )
    }

    private fun trackBranchRequest(
        id: String,
        sessionId: String,
    ) {
        pendingRequests[id] =
            PendingRpcRequest(
                method = WsMethods.SESSION_BRANCH,
                branchSessionId = sessionId,
            )
    }

    // ── Search ────────────────────────────────────────────────────────────
    // Compatibility façade: stable public API around ChatSearchDelegate.
    // These thin delegates keep ChatViewModel's public surface intact while
    // the search logic now lives in the delegate. Safe to remove once all
    // callers migrate directly to the delegate.

    fun toggleSearch() = searchDelegate.toggleSearch()

    fun setSearchQuery(query: String) = searchDelegate.setSearchQuery(query)

    fun navigateSearchMatch(direction: Int) = searchDelegate.navigateSearchMatch(direction)

    fun clearSearch() = searchDelegate.clearSearch()

    private var isTestEnv: Boolean? = null

    private fun isTestEnvironment(): Boolean {
        if (isTestEnv == null) {
            isTestEnv =
                try {
                    Class.forName("org.junit.Test")
                    true
                } catch (e: ClassNotFoundException) {
                    false
                }
        }
        return isTestEnv == true
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        // PERF-16: Don't disconnect the global HermesWsClient singleton when
        // leaving the Chat screen — it's used by background notification reply.
    }

    companion object {
    }
}
