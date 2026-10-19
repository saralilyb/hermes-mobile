package com.m57.hermescontrol.data.ws

/** Parsed WebSocket events emitted by [HermesWsClient]. */
sealed class WsEvent {
    // ── Gateway lifecycle ────────────────────────────────────────────────

    data class GatewayReady(
        val data: Map<String, Any?>?,
    ) : WsEvent()

    // ── Session information ──────────────────────────────────────────────

    data class SessionInfo(
        val data: Map<String, Any?>?,
        val sessionId: String? = null,
    ) : WsEvent()

    // ── Message streaming ────────────────────────────────────────────────

    data class MessageStart(
        val sessionId: String?,
    ) : WsEvent()

    data class MessageToken(
        val token: String,
        val sessionId: String?,
    ) : WsEvent()

    data class ThinkingDelta(
        val token: String,
        val sessionId: String?,
    ) : WsEvent()

    data class ReasoningDelta(
        val token: String,
        val sessionId: String?,
    ) : WsEvent()

    data class ReasoningAvailable(
        val sessionId: String?,
    ) : WsEvent()

    data class MessageComplete(
        val text: String,
        val sessionId: String?,
        /**
         * Gateway `usage` block for the finished turn (`tui_gateway/server.py`,
         * `_get_usage`). Carries the live current-window occupancy the context
         * meter reads. Also present on the terminal error frame, so a failed
         * turn still refreshes the gauge. Null on frames from older gateways.
         */
        val usage: Map<String, Any?>? = null,
    ) : WsEvent()

    data class MessageDone(
        val sessionId: String?,
    ) : WsEvent()

    // ── Tool execution ───────────────────────────────────────────────────

    data class ToolStart(
        val name: String?,
        val data: Map<String, Any?>?,
        val sessionId: String? = null,
    ) : WsEvent()

    data class ToolComplete(
        val name: String?,
        val data: Map<String, Any?>?,
        val sessionId: String? = null,
    ) : WsEvent()

    /**
     * Backend flagged tool output as having potential risk (secrets, PII).
     * Emitted alongside tool.progress — carries risk level, findings, and
     * whether content was redacted.
     *
     * Events: `tool.output_risk`
     * Payload: `{ tool_id, name, risk, findings[], redacted }`
     */
    data class ToolOutputRisk(
        val toolId: String = "",
        val name: String = "",
        val risk: String = "low",
        val findings: List<String> = emptyList(),
        val redacted: Boolean = false,
        val sessionId: String? = null,
    ) : WsEvent()

    /**
     * Live tool execution progress with optional preview content.
     *
     * Events: `tool.progress`
     * Payload: `{ name?: string, preview?: string }`
     */
    data class ToolProgress(
        val name: String? = null,
        val preview: String? = null,
        val sessionId: String? = null,
        val toolId: String? = null,
    ) : WsEvent()

    /**
     * Tool generation active state.
     *
     * Events: `tool.generating`
     * Payload: `{ name?: string }`
     */
    data class ToolGenerating(
        val name: String? = null,
        val sessionId: String? = null,
        val toolId: String? = null,
    ) : WsEvent()

    /**
     * Subagent execution and delegation events.
     *
     * Events: `subagent.spawn_requested`, `subagent.start`, `subagent.progress`, `subagent.complete`
     * Payload includes goal, task_index, task_count, subagent_id, child_session_id, text, status, summary, duration_seconds.
     */
    data class SubagentEvent(
        val type: String,
        val payload: Map<String, Any?>?,
        val sessionId: String? = null,
    ) : WsEvent()

    // ── Interactive ──────────────────────────────────────────────────────

    data class ClarifyQuestion(
        val qid: String,
        val question: String,
        val choices: List<String> = emptyList(),
        val multiSelect: Boolean = false,
    )

    data class ClarifyRequest(
        val text: String?,
        val options: List<String>?,
        val clarifyId: String? = null,
        val sessionId: String? = null,
        val questionId: String? = null,
        val multiSelect: Boolean = false,
        val questions: List<ClarifyQuestion> = emptyList(),
        val sourceProfileId: String? = null,
        val connectionGeneration: Int? = null,
        val serverRequestId: String? = null,
        val lockedAnswers: Map<String, String?> = emptyMap(),
    ) : WsEvent()

    data class ClarifyExpire(
        val clarifyId: String,
        val sessionId: String? = null,
        val sourceProfileId: String? = null,
        val connectionGeneration: Int? = null,
    ) : WsEvent()

    /**
     * Self-improvement background review summary event.
     * Emitted when background review patches a skill or saves a memory.
     * Payload: `{ text: "Self-improvement review: ..." }`
     */
    data class ReviewSummary(
        val text: String,
        val sessionId: String? = null,
    ) : WsEvent()

    // ── Status ───────────────────────────────────────────────────────────

    data class StatusUpdate(
        val status: String?,
        val data: Map<String, Any?>?,
    ) : WsEvent()

    data class SessionUpdated(
        val data: Map<String, Any?>?,
    ) : WsEvent()

    /** Live context usage snapshot emitted while a session turn is running. */
    data class SessionUsage(
        val data: Map<String, Any?>?,
        val sessionId: String? = null,
    ) : WsEvent()

    // ── RPC responses ────────────────────────────────────────────────────

    data class RpcResult(
        val id: String,
        val result: Any?,
    ) : WsEvent()

    data class RpcError(
        val id: String,
        val error: JsonRpcError,
    ) : WsEvent()

    // ── Approval request ────────────────────────────────────────────────

    /**
     * The gateway blocks a turn on an explicit user decision.
     *
     * [requestId] and [timeoutSeconds] are mandatory: the gateway binds every
     * `approval.respond` / `approval.cancel` to the exact opaque request id
     * (hermes-agent `d90045be2`) and publishes the exact relative lifetime the
     * waiting thread uses (`a77692158`). A frame without both is a legacy
     * request this client cannot answer safely, so [EventParser] rejects it
     * instead of surfacing an unbindable approval.
     */
    data class ApprovalRequest(
        val command: String?,
        val description: String?,
        val patternKeys: List<String>?,
        val sessionId: String?,
        val requestId: String,
        val timeoutSeconds: Double,
        val allowSession: Boolean? = null,
        // null means malformed-present; empty means absent or explicitly empty.
        val choices: List<String>? = emptyList(),
        val smartDenied: Boolean = false,
        val sourceProfileId: String? = null,
        val connectionGeneration: Int? = null,
        val serverRequestId: String? = null,
    ) : WsEvent()

    // ── Sudo / secret requests ─────────────────────────────────────────

    /**
     * Backend needs the user's sudo password to continue a turn
     * (desktop: `sudo.request` → `sudo.respond {request_id, password}`).
     * Mobile previously dropped this and the agent hung forever.
     */
    data class SudoRequest(
        val requestId: String,
        val sessionId: String?,
        val sourceProfileId: String? = null,
        val connectionGeneration: Int? = null,
        val serverRequestId: String? = null,
    ) : WsEvent()

    data class SudoExpire(
        val requestId: String,
        val sessionId: String?,
        val sourceProfileId: String? = null,
        val connectionGeneration: Int? = null,
    ) : WsEvent()

    /**
     * Backend needs a secret value (password / token) to continue a turn
     * (desktop: `secret.request` → `secret.respond {request_id, value}`).
     * Mobile previously dropped this and the agent hung forever.
     */
    data class SecretRequest(
        val requestId: String,
        val sessionId: String?,
        val envVar: String? = null,
        val prompt: String? = null,
        val sourceProfileId: String? = null,
        val connectionGeneration: Int? = null,
        val serverRequestId: String? = null,
    ) : WsEvent()

    data class SecretExpire(
        val requestId: String,
        val sessionId: String?,
        val sourceProfileId: String? = null,
        val connectionGeneration: Int? = null,
    ) : WsEvent()

    data class ServerRequest(
        val id: String,
        val method: String,
        val params: Map<String, Any?> = emptyMap(),
        val replayed: Boolean = false,
        val sourceProfileId: String? = null,
        val connectionGeneration: Int? = null,
    ) : WsEvent()

    data class ServerRequestCancelled(
        val id: String,
        val method: String,
        val reason: String,
        val sessionId: String? = null,
        val sourceProfileId: String? = null,
        val connectionGeneration: Int? = null,
    ) : WsEvent()

    /**
     * A privileged frame that cannot be bound to an exact request, and is
     * therefore never surfaced or answered.
     *
     * Carries only the event type and session so the rejection is observable
     * without retaining the rejected payload. Every consumer treats it as a
     * no-op — that is the point.
     */
    data class PrivilegedRequestRejected(
        val eventType: String,
        val sessionId: String?,
    ) : WsEvent()

    // ── Gateway-level errors ───────────────────────────────────────────

    /**
     * Backend/unhandled failure surfaced by the gateway.
     *
     * Desktop shows these as red toasts (the gateway also writes
     * `[gateway.error]` lines to the console). Mobile was previously
     * dropping this event, so a crashing turn just silently stopped with no
     * explanation. Issue #527 surfaces it in the existing error banner.
     */
    data class GatewayError(
        val message: String?,
    ) : WsEvent()

    // ── Background job completion ──────────────────────────────────────

    /**
     * A scheduled/background job finished on the gateway.
     *
     * Desktop shows a "Background job finished" toast. Mobile surfaces this as
     * a non-blocking snackbar. The payload may carry `label`/`name` describing
     * the job. Issue #527.
     */
    data class BackgroundComplete(
        val data: Map<String, Any?>?,
    ) : WsEvent()

    // ── Reaction event ────────────────────────────────────────────────────

    /**
     * Backend emitted an affection reaction (ily / <3 / good bot).
     * Payload: `{ "kind": "<str>" }`. Purely cosmetic — play a hearts
     * animation in the chat UI; no persistence needed.
     */
    data class ReactionEvent(
        val kind: String = "",
    ) : WsEvent()

    // ── Replay resync (internal) ──────────────────────────────────────────

    /** Internal: replay could not cover the reconnect gap (truncated or epoch change) — UI must refetch history. */
    data class TranscriptResyncRequired(
        val sessionId: String,
    ) : WsEvent()

    // ── Fallback ─────────────────────────────────────────────────────────

    data class Unknown(
        val raw: String,
    ) : WsEvent()
}
