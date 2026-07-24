// Modified from Hy4ri/hermes-mobile for this fork; see NOTICE.

package com.m57.hermescontrol.data.config

import com.m57.hermescontrol.data.remote.CleartextPolicy
import com.m57.hermescontrol.data.remote.ServerEndpoint
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class ConnectionProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    /** Legacy fields retained only so pre-base-URL profiles can migrate. */
    val host: String = "127.0.0.1",
    val port: Int = 9119,
    val baseUrl: String? = null,
    /** WebSocket query parameter used by the profile's authentication mode. */
    val wsAuthParam: String? = null,
) {
    /**
     * Resolve this profile without store context. Store and UI callers should
     * prefer [resolveBaseUrl] so an unstamped legacy profile can inherit the
     * URL used for the most recent successful login.
     */
    val resolvedBaseUrl: String
        get() = resolveBaseUrl(null)
}

/**
 * Resolve the server URL for this profile. Prefer the profile-specific URL,
 * then an explicit legacy host/port pair, then the top-level login URL. The
 * default legacy loopback values are placeholders, so they remain the final
 * fallback rather than masking the URL used for a successful login.
 */
fun ConnectionProfile.resolveBaseUrl(topLevelBaseUrl: String?): String {
    baseUrl?.let {
        return ServerEndpoint.parse(
            it,
            CleartextPolicy.ALLOW_WITH_WARNING,
        ).baseUrl.toString()
    }
    if (host.isNotBlank() && port in 1..65535 &&
        (host != "127.0.0.1" || port != 9119)
    ) {
        return ServerEndpoint.fromLegacy(host, port).baseUrl.toString()
    }
    return topLevelBaseUrl
        ?.let {
            ServerEndpoint.parse(
                it,
                CleartextPolicy.ALLOW_WITH_WARNING,
            ).baseUrl.toString()
        }
        ?: ServerEndpoint.fromLegacy(host, port).baseUrl.toString()
}
