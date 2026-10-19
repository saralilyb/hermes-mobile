// Modified from Hy4ri/hermes-mobile for this fork; see NOTICE.
package com.m57.hermescontrol.data.ws

/** Absent/empty offers retain compatibility; malformed-present offers fail closed. */
internal fun parseApprovalChoices(params: Map<*, *>?): List<String>? {
    if (params == null || !params.containsKey("choices")) return emptyList()
    val choices = params["choices"] as? List<*> ?: return null
    if (choices.any { it !is String }) return null
    return choices.filterIsInstance<String>()
}
