package com.m57.hermescontrol.data.remote

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object OkHttpProvider {
    // Single connection pool shared by ALL clients (REST, WS, probes)
    // 5 idle connections, 30s keep-alive — tuned for single-server LAN
    private val connectionPool = ConnectionPool(5, 30, TimeUnit.SECONDS)

    /**
     * Shared [okhttp3.CookieJar] used by every client (REST, WS, probe) so a
     * Set-Cookie from one request is reusable by the others (issue #470).
     *
     * Resolved lazily (per client build) so it is only touched after
     * [CookieManager.initialize] has run at app startup — NOT at
     * [OkHttpProvider] object-init time, which would otherwise throw for unit
     * tests / early access before the app context exists.
     */
    private fun resolveCookieJar(): PersistentCookieJar = CookieManager.cookieJar

    private fun commonBuilder(): OkHttpClient.Builder =
        OkHttpClient
            .Builder()
            .connectionPool(connectionPool)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)

    private fun authenticatedBuilder(): OkHttpClient.Builder =
        commonBuilder()
            .cookieJar(resolveCookieJar())
            .addInterceptor { chain ->
                val boundary = resolveCookieJar().captureCredentialBoundary()
                val request =
                    chain
                        .request()
                        .newBuilder()
                        .tag(CookieCredentialBoundary::class.java, boundary)
                        .build()
                chain.proceed(request)
            }.addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                val boundary = chain.request().tag(CookieCredentialBoundary::class.java)
                if (boundary != null) {
                    resolveCookieJar().saveFromResponse(
                        boundary = boundary,
                        url = chain.request().url,
                        cookies = okhttp3.Cookie.parseAll(chain.request().url, response.headers),
                    )
                }
                // BridgeInterceptor would otherwise persist Set-Cookie after
                // this generation check, reopening a check/use race.
                response.newBuilder().removeHeader("Set-Cookie").build()
            }

    // Base authenticated client: shared pool plus credential-bound cookies.
    // Built lazily so CookieManager is initialized before the jar is resolved.
    // Low-level connection retries are separate from safeApiCall's app-level
    // backoff for 5xx, 429, and timeout responses.
    val base: OkHttpClient by lazy {
        authenticatedBuilder().build()
    }

    /**
     * Client for public image URLs rendered by Coil. Authenticated gateway
     * media is fetched through [GatewayFileClient] before it reaches Coil, so
     * this client deliberately carries no dashboard cookies.
     */
    val publicMedia: OkHttpClient by lazy {
        commonBuilder()
            .cookieJar(CookieJar.NO_COOKIES)
            .build()
    }

    // WebSocket-optimized variant (infinite read timeout, ping interval)
    val websocket: OkHttpClient by lazy {
        base
            .newBuilder()
            .cookieJar(resolveCookieJar())
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    }

    // Short-timeout variant for probes and ticket minting
    val probe: OkHttpClient by lazy {
        base
            .newBuilder()
            .cookieJar(resolveCookieJar())
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
    }

    @OptIn(ExperimentalSerializationApi::class)
    val json: Json =
        Json {
            ignoreUnknownKeys = true
            namingStrategy = JsonNamingStrategy.SnakeCase
        }
}
