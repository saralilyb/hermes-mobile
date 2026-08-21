package com.m57.hermescontrol.data.remote

import com.m57.hermescontrol.data.local.AuthManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer

internal object DashboardSessionTokenRefresher {
    private val tokenPattern = Regex("""__HERMES_SESSION_TOKEN__\s*=\s*"([^"]+)"""")

    // Synchronized so concurrent 401s / reconnect storms don't each fire a
    // redundant GET at the dashboard SPA (thundering-herd guard).
    @Synchronized
    fun refresh(): String? =
        try {
            val token = fetch(AuthManager.baseUrl(), OkHttpProvider.probe) ?: return null
            AuthManager.setToken(token)
            token
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }

    internal fun fetch(
        baseUrl: String,
        client: OkHttpClient,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): String? =
        try {
            val request =
                Request
                    .Builder()
                    .url(baseUrl)
                    .get()
                    .build()
            runBlocking {
                runInterruptible(dispatcher) {
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            null
                        } else {
                            // Keep both execute and the socket-backed body read off the caller thread.
                            val html =
                                response.body.source().use { source ->
                                    val buffer = Buffer()
                                    source.read(buffer, MAX_BODY_BYTES)
                                    buffer.readUtf8()
                                }
                            tokenPattern
                                .find(html)
                                ?.groupValues
                                ?.getOrNull(1)
                                ?.takeIf { it.isNotBlank() }
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }

    private const val MAX_BODY_BYTES: Long = 64 * 1024
}
