// Modified from Hy4ri/hermes-mobile for this fork; see NOTICE.

package com.m57.hermescontrol.data.ws

import android.util.Log

/**
 * Converts raw [JsonRpcResponse] objects into typed [WsEvent] instances.
 *
 * The Hermes TUI gateway sends events as JSON-RPC **notifications** (no `id`
 * field). The `method` is always `"event"` and the event type lives in
 * `params.type`. The event payload is in `params.payload`.
 *
 * Regular RPC responses have an `id` and either a `result` or `error`.
 */
object EventParser {
    private const val TAG = "EventParser"

    fun parse(
        response: JsonRpcResponse,
        rawJson: String = "",
    ): WsEvent {
        // JSON-RPC is peer-to-peer: an inbound frame with both `id` and `method`
        // is a request from the gateway, not a response to one of our calls.
        val id = response.id
        if (response.method != null && id != null) {
            @Suppress("UNCHECKED_CAST")
            val requestParams = response.params?.toAny() as? Map<String, Any?> ?: emptyMap()
            return WsEvent.ServerRequest(id, response.method, requestParams)
        }

        // A response has no method and carries a result or error member.
        if (response.method == null && id != null && (response.result != null || response.error != null)) {
            return if (response.error != null) {
                WsEvent.RpcError(id, response.error)
            } else {
                WsEvent.RpcResult(id, response.result?.toAny())
            }
        }

        // ── Notification / event (no id, has method) ─────────────────────
        @Suppress("UNCHECKED_CAST")
        val params = response.params?.toAny() as? Map<String, Any?> ?: return WsEvent.Unknown(rawJson)
        val eventType = params["type"] as? String ?: return WsEvent.Unknown(rawJson)

        @Suppress("UNCHECKED_CAST")
        val payload = params["payload"] as? Map<String, Any?>

        // B7 (Jun 21 2026, kanban t_240): extract session_id from params first, fallback to payload
        val sessionId = params["session_id"] as? String ?: payload?.get("session_id") as? String
        val privilegedSessionId = exactPrivilegedSessionId(params, payload)

        return when (eventType) {
            "projects.changed" -> WsEvent.ProjectsChanged
            "gateway.ready" -> {
                WsEvent.GatewayReady(payload)
            }

            "session.info" -> {
                WsEvent.SessionInfo(payload, sessionId)
            }

            "message.start" -> {
                WsEvent.MessageStart(sessionId)
            }

            "message.token", "message.delta" -> {
                val token = payload?.get("text") as? String ?: ""
                WsEvent.MessageToken(token, sessionId)
            }

            "thinking.delta" -> {
                val token = payload?.get("text") as? String ?: ""
                WsEvent.ThinkingDelta(token, sessionId)
            }

            "reasoning.delta" -> {
                val token = payload?.get("text") as? String ?: ""
                WsEvent.ReasoningDelta(token, sessionId)
            }

            "reasoning.available" -> {
                WsEvent.ReasoningAvailable(sessionId)
            }

            "message.complete" -> {
                val text = payload?.get("text") as? String ?: ""

                @Suppress("UNCHECKED_CAST")
                val usage = payload?.get("usage") as? Map<String, Any?>
                WsEvent.MessageComplete(text, sessionId, usage)
            }

            "message.done" -> {
                WsEvent.MessageDone(sessionId)
            }

            "tool.start" -> {
                val name = payload?.get("name") as? String
                WsEvent.ToolStart(name, payload, sessionId)
            }

            "tool.complete" -> {
                val name = payload?.get("name") as? String
                WsEvent.ToolComplete(name, payload, sessionId)
            }

            "tool.progress" -> {
                val toolId = payload?.get("tool_id") as? String
                val name = payload?.get("name") as? String
                val preview = payload?.get("preview") as? String
                WsEvent.ToolProgress(name, preview, sessionId, toolId)
            }

            "tool.generating" -> {
                val toolId = payload?.get("tool_id") as? String
                val name = payload?.get("name") as? String
                WsEvent.ToolGenerating(name, sessionId, toolId)
            }

            "subagent.spawn_requested", "subagent.start", "subagent.progress", "subagent.complete" -> {
                WsEvent.SubagentEvent(eventType, payload, sessionId)
            }

            "tool.output_risk" -> {
                val toolId = payload?.get("tool_id") as? String ?: ""
                val name = payload?.get("name") as? String ?: ""
                val risk = (payload?.get("risk") as? String)?.lowercase() ?: "low"
                val redacted = payload?.get("redacted") as? Boolean ?: false

                @Suppress("UNCHECKED_CAST")
                val findings = (payload?.get("findings") as? List<*>)?.filterIsInstance<String>() ?: emptyList()

                WsEvent.ToolOutputRisk(toolId, name, risk, findings, redacted, sessionId)
            }

            "clarify.request" -> {
                val clarifySessionId = privilegedSessionId ?: return WsEvent.Unknown(rawJson)
                // Gateway sends "question"/"choices" — fall back to "text"/"options" for any
                // older client or test that still uses the legacy field names. (Issue #206)
                val text =
                    payload?.get("question") as? String
                        ?: payload?.get("text") as? String
                val rawOptions =
                    payload?.get("choices")
                        ?: payload?.get("options")
                val clarifyId = exactClarifyRequestId(payload) ?: return WsEvent.Unknown(rawJson)
                val questionId = payload?.get("qid") as? String ?: payload?.get("question_id") as? String
                val multiSelect = payload?.get("multi_select") as? Boolean ?: false

                @Suppress("UNCHECKED_CAST")
                val options = (rawOptions as? List<*>)?.filterIsInstance<String>()
                val questions =
                    (payload?.get("questions") as? List<*>)
                        .orEmpty()
                        .mapIndexedNotNull { index, raw ->
                            val question = raw as? Map<*, *> ?: return@mapIndexedNotNull null
                            val qid = question["qid"] as? String ?: "q$index"
                            val prompt = question["question"] as? String ?: return@mapIndexedNotNull null
                            WsEvent.ClarifyQuestion(
                                qid = qid,
                                question = prompt,
                                choices = (question["choices"] as? List<*>)?.filterIsInstance<String>().orEmpty(),
                                multiSelect = question["multi_select"] as? Boolean ?: false,
                            )
                        }
                WsEvent.ClarifyRequest(text, options, clarifyId, clarifySessionId, questionId, multiSelect, questions)
            }

            "clarify.expire" -> {
                val clarifyId = exactClarifyRequestId(payload) ?: return WsEvent.Unknown(rawJson)
                val clarifySessionId = privilegedSessionId ?: return WsEvent.Unknown(rawJson)
                WsEvent.ClarifyExpire(clarifyId, clarifySessionId)
            }

            "request.cancel" -> {
                val requestId =
                    (payload?.get("id") as? String)?.takeIf { it.isNotBlank() }
                        ?: return WsEvent.Unknown(rawJson)
                val requestMethod =
                    (payload["method"] as? String)?.takeIf { it.isNotBlank() }
                        ?: return WsEvent.Unknown(rawJson)
                val cancelSessionId = privilegedSessionId ?: return WsEvent.Unknown(rawJson)
                val reason = payload["reason"] as? String ?: ""
                WsEvent.ServerRequestCancelled(requestId, requestMethod, reason, cancelSessionId)
            }

            "status.update" -> {
                val status = payload?.get("status") as? String
                WsEvent.StatusUpdate(status, payload)
            }

            "error" -> {
                val message =
                    payload?.get("message") as? String
                        ?: payload?.get("error") as? String
                WsEvent.GatewayError(message)
            }

            "background.complete" -> {
                WsEvent.BackgroundComplete(payload)
            }

            "review.summary" -> {
                val text = (payload?.get("text") as? String)?.trim() ?: ""
                WsEvent.ReviewSummary(text, sessionId)
            }

            "session.updated" -> {
                WsEvent.SessionUpdated(payload)
            }

            "session.usage" -> {
                WsEvent.SessionUsage(payload, sessionId)
            }

            "reaction" -> {
                val kind = payload?.get("kind") as? String ?: ""
                WsEvent.ReactionEvent(kind)
            }

            "approval.request" -> {
                val requestId = privilegedRequestId(payload)
                val timeoutSeconds = approvalTimeoutSeconds(payload)
                if (requestId == null || timeoutSeconds == null || privilegedSessionId == null) {
                    reject(eventType, sessionId)
                } else {
                    val command = payload?.get("command") as? String
                    val description = payload?.get("description") as? String

                    @Suppress("UNCHECKED_CAST")
                    val patternKeys = (payload?.get("pattern_keys") as? List<*>)?.filterIsInstance<String>()
                    WsEvent.ApprovalRequest(
                        command = command,
                        description = description,
                        patternKeys = patternKeys,
                        sessionId = privilegedSessionId,
                        requestId = requestId,
                        timeoutSeconds = timeoutSeconds,
                        allowSession = payload?.get("allow_session") as? Boolean,
                        choices = parseApprovalChoices(payload),
                        smartDenied = payload?.get("smart_denied") == true,
                    )
                }
            }

            "sudo.request" -> {
                val requestId = privilegedRequestId(payload)
                if (requestId == null || privilegedSessionId == null) {
                    reject(eventType, sessionId)
                } else {
                    WsEvent.SudoRequest(requestId, privilegedSessionId)
                }
            }

            "sudo.expire" -> {
                val requestId = privilegedRequestId(payload)
                if (requestId == null || privilegedSessionId == null) {
                    reject(eventType, sessionId)
                } else {
                    WsEvent.SudoExpire(requestId, privilegedSessionId)
                }
            }

            "secret.request" -> {
                val requestId = privilegedRequestId(payload)
                if (requestId == null || privilegedSessionId == null) {
                    reject(eventType, sessionId)
                } else {
                    val envVar = payload?.get("env_var") as? String
                    val prompt = payload?.get("prompt") as? String
                    WsEvent.SecretRequest(requestId, privilegedSessionId, envVar, prompt)
                }
            }

            "secret.expire" -> {
                val requestId = privilegedRequestId(payload)
                if (requestId == null || privilegedSessionId == null) {
                    reject(eventType, sessionId)
                } else {
                    WsEvent.SecretExpire(requestId, privilegedSessionId)
                }
            }

            else -> {
                Log.w(TAG, "Unknown WebSocket event type")
                WsEvent.Unknown(rawJson)
            }
        }
    }

    /**
     * The opaque request id every privileged frame must carry. Blank, missing,
     * or non-string ids identify a legacy gateway whose responses cannot be
     * bound to one exact pending request.
     */
    private fun privilegedRequestId(payload: Map<String, Any?>?): String? =
        (payload?.get("request_id") as? String)?.takeIf { it.isNotBlank() }

    /** Require one non-blank, non-conflicting clarification id across both aliases. */
    private fun exactClarifyRequestId(payload: Map<String, Any?>?): String? {
        val clarifyId = payload?.get("clarify_id")?.let { it as? String ?: return null }
        val requestId = payload?.get("request_id")?.let { it as? String ?: return null }
        if (clarifyId.isNullOrBlank() && requestId.isNullOrBlank()) return null
        if (clarifyId != null && requestId != null && clarifyId != requestId) return null
        return (clarifyId ?: requestId)?.takeIf { it.isNotBlank() }
    }

    /** Require one non-blank runtime session, with no conflicting payload alias. */
    private fun exactPrivilegedSessionId(
        params: Map<String, Any?>,
        payload: Map<String, Any?>?,
    ): String? {
        val outer = (params["session_id"] as? String)?.takeIf { it.isNotBlank() } ?: return null
        val inner = payload?.get("session_id") ?: return outer
        return (inner as? String)?.takeIf { it == outer && it.isNotBlank() }
    }

    /**
     * The exact relative approval lifetime the gateway's waiting thread uses.
     * Only a finite, strictly positive value can drive a local expiry, so
     * anything else rejects the request rather than leaving controls live past
     * the server's own deadline.
     */
    private fun approvalTimeoutSeconds(payload: Map<String, Any?>?): Double? =
        (payload?.get("timeout_seconds") as? Number)
            ?.toDouble()
            ?.takeIf { it.isFinite() && it > 0.0 }

    private fun reject(
        eventType: String,
        sessionId: String?,
    ): WsEvent {
        Log.w(TAG, "Rejecting unbindable privileged event: $eventType")
        return WsEvent.PrivilegedRequestRejected(eventType, sessionId)
    }
}
