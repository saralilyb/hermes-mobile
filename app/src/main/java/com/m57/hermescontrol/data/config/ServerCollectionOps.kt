// Modified from Hy4ri/hermes-mobile for this fork; see NOTICE.

package com.m57.hermescontrol.data.config

import com.m57.hermescontrol.data.remote.CleartextPolicy
import com.m57.hermescontrol.data.remote.ServerEndpoint

fun ServerStoreState.addOrUpdate(profile: ConnectionProfile): ServerStoreState {
    val updated = connectionProfiles.filter { it.id != profile.id } + profile
    return copy(connectionProfiles = updated)
}

fun ServerStoreState.selfHealed(): ServerStoreState {
    val hasActive = connectionProfiles.any { it.id == selectedProfileId }
    val newSelected = if (selectedProfileId != null && !hasActive) null else selectedProfileId

    return copy(
        selectedProfileId = newSelected,
    )
}

val ServerStoreState.resolvedBaseUrl: String
    get() {
        val selected = connectionProfiles.firstOrNull { it.id == selectedProfileId }
        if (selected != null) return selected.resolveBaseUrl(baseUrl)
        return baseUrl
            ?.let {
                ServerEndpoint.parse(
                    it,
                    CleartextPolicy.ALLOW_WITH_WARNING,
                ).baseUrl.toString()
            }
            ?: ServerEndpoint.fromLegacy(host, port).baseUrl.toString()
    }

val ServerStoreState.resolvedHost: String
    get() =
        ServerEndpoint.parse(
            resolvedBaseUrl,
            CleartextPolicy.ALLOW_WITH_WARNING,
        ).baseUrl.host

val ServerStoreState.resolvedPort: Int
    get() =
        ServerEndpoint.parse(
            resolvedBaseUrl,
            CleartextPolicy.ALLOW_WITH_WARNING,
        ).baseUrl.port
