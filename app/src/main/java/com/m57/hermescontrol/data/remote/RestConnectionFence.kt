package com.m57.hermescontrol.data.remote

import com.m57.hermescontrol.data.local.AuthManager

/**
 * Admission/publication fence, not an immutable credential snapshot. Resolve the
 * current service only after checking on the dispatch thread; never retain it
 * across a dispatcher hop. The shared REST interceptor still reads live auth.
 */
internal data class RestConnectionFence(
    private val profileId: String?,
    private val generation: Long,
) {
    fun isCurrent(): Boolean =
        profileId == AuthManager.getSelectedProfileId() && generation == ApiClient.connectionGeneration

    companion object {
        fun capture(): RestConnectionFence =
            RestConnectionFence(AuthManager.getSelectedProfileId(), ApiClient.connectionGeneration)
    }
}
