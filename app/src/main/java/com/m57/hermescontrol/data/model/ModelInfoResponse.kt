package com.m57.hermescontrol.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Response from `GET /api/model/info`.
 *
 * Verified against the live gateway (2026-07-29): the payload carries `model`,
 * `provider`, `auto_context_length`, `config_context_length`,
 * `effective_context_length`, and a nested `capabilities` object.
 * [effectiveContextLength] is the authoritative window for the active model.
 *
 * This is only the meter's *denominator fallback* — used before the first turn
 * of a session reports a live `context_max` over the WebSocket. The numerator
 * never comes from REST; see [com.m57.hermescontrol.ui.chat.ContextUsage].
 *
 * Decoded with `Json { ignoreUnknownKeys = true }` (see
 * [com.m57.hermescontrol.data.remote.OkHttpProvider.json]), so `capabilities`
 * and any later backend additions are tolerated without modelling them.
 */
@Serializable
data class ModelInfoResponse(
    val model: String? = null,
    val provider: String? = null,
    @SerialName("effective_context_length")
    val effectiveContextLength: Long? = null,
    @SerialName("auto_context_length")
    val autoContextLength: Long? = null,
    @SerialName("config_context_length")
    val configContextLength: Long? = null,
)
