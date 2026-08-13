package com.m57.hermescontrol.data.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap

internal data class CookieCredentialBoundary(
    val serverId: String,
    val generation: Long,
    val acceptsCookies: Boolean,
)

/**
 * OkHttp [CookieJar] backed by an in-memory [ConcurrentHashMap] cache plus
 * encrypted [CookieStore] persistence.
 *
 * Design notes (issue #470):
 * - **Memory cache** keyed by host for O(1) reads on every request.
 * - **Async persistence** — writes are dispatched to [storeScope] and never
 *   block the calling OkHttp thread.
 * - **Atomic server scoping** via [useStore] — callers (login, profile switch)
 *   flip the active server id; subsequent load/save target that scope. Guarded
 *   by [scopeMutex] so a swap can't race a mid-flight load.
 * - **Lazy load** — the first request for a server scope triggers a one-shot
 *   [CookieStore.load] (double-checked through [loadedScopes]).
 * - **Pruning** — [pruneServerCache] evicts expired cookies to bound growth.
 *
 * Dashboard access-cookie variants are explicitly retained across pruning
 * because their lifetime is owned server-side, not by client expiry.
 */
class PersistentCookieJar(
    private val store: CookieStore,
    private val storeScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    initialServerId: String = DEFAULT_SERVER_ID,
) : CookieJar {
    // serverId -> (host -> cookies)
    private val cache = ConcurrentHashMap<String, MutableMap<String, MutableList<Cookie>>>()
    private val loadedScopes = ConcurrentHashMap.newKeySet<String>()
    private val clearedScopes = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var allScopesCleared = false

    @Volatile
    private var credentialBoundary =
        CookieCredentialBoundary(
            serverId = initialServerId,
            generation = 0,
            acceptsCookies = true,
        )

    private val scopeMutex = Mutex()
    private val credentialLock = Any()
    private val persistenceLock = Any()

    @Volatile
    private var persistenceTail: Job? = null

    /** Atomically switch the active server scope and ensure its cookies are loaded. */
    suspend fun useStore(serverId: String) {
        scopeMutex.withLock {
            synchronized(credentialLock) {
                if (credentialBoundary.serverId != serverId) {
                    credentialBoundary =
                        CookieCredentialBoundary(
                            serverId = serverId,
                            generation = credentialBoundary.generation + 1,
                            acceptsCookies = cookieWritesEnabled(serverId),
                        )
                }
            }
            ensureLoaded(serverId)
        }
    }

    /** Current active server scope id. */
    fun currentServer(): String = credentialBoundary.serverId

    internal fun captureCredentialBoundary(): CookieCredentialBoundary = credentialBoundary

    internal fun acceptsCredentialBoundary(boundary: CookieCredentialBoundary): Boolean =
        boundary.acceptsCookies && boundary == credentialBoundary

    /** Begin a new explicit authentication attempt after a credential clear. */
    fun beginAuthentication() {
        synchronized(credentialLock) {
            val current = credentialBoundary
            allScopesCleared = false
            clearedScopes.remove(current.serverId)
            credentialBoundary =
                current.copy(
                    generation = current.generation + 1,
                    acceptsCookies = true,
                )
        }
    }

    /**
     * Return the live [Cookie] with [name] from the active server scope, or
     * null if absent. Scans every host bucket; the session cookie is typically
     * host-scoped to the dashboard host.
     */
    fun getCookie(name: String): Cookie? {
        val serverId = credentialBoundary.serverId
        if (!loadedScopes.contains(serverId)) {
            kotlinx.coroutines.runBlocking { ensureLoaded(serverId) }
        }
        val hosts = cache[serverId] ?: return null
        for (bucket in hosts.values) {
            synchronized(bucket) {
                val match = bucket.firstOrNull { it.name == name }
                if (match != null) return match
            }
        }
        return null
    }

    /**
     * Value of the active dashboard access cookie, including the `__Host-`
     * and `__Secure-` variants used by HTTPS deployments.
     */
    fun getSessionCookieValue(): String? =
        SESSION_COOKIE_NAMES.firstNotNullOfOrNull { name ->
            getCookie(name)?.value
        }

    override fun saveFromResponse(
        url: HttpUrl,
        cookies: List<Cookie>,
    ) = saveFromResponse(credentialBoundary, url, cookies)

    internal fun saveFromResponse(
        boundary: CookieCredentialBoundary,
        url: HttpUrl,
        cookies: List<Cookie>,
    ) {
        if (cookies.isEmpty()) return
        synchronized(credentialLock) {
            if (!acceptsCredentialBoundary(boundary)) return
            val serverId = boundary.serverId
            val hostKey = url.host
            val bucket =
                cache
                    .getOrPut(serverId) { ConcurrentHashMap() }
                    .getOrPut(hostKey) { mutableListOf() }
            synchronized(bucket) {
                for (cookie in cookies) {
                    val idx = bucket.indexOfFirst { it.name == cookie.name && it.path == cookie.path }
                    if (cookie.expiresAt <= System.currentTimeMillis()) {
                        if (idx >= 0) bucket.removeAt(idx)
                    } else if (idx >= 0) {
                        bucket[idx] = cookie
                    } else {
                        bucket.add(cookie)
                    }
                }
            }
            // Persist the whole server scope asynchronously.
            persist(serverId)
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val serverId = credentialBoundary.serverId
        // Best-effort synchronous load on first touch (tests/first call).
        if (!loadedScopes.contains(serverId)) {
            kotlinx.coroutines.runBlocking { ensureLoaded(serverId) }
        }
        val hostKey = url.host
        val hosts = cache[serverId] ?: return emptyList()
        val now = System.currentTimeMillis()
        val buckets = listOfNotNull(hosts[hostKey], hosts[WILDCARD_HOST])
        synchronized(buckets) {
            return buckets.flatMap { bucket ->
                synchronized(bucket) {
                    bucket.filter { it.matches(url) && it.expiresAt > now }
                }
            }
        }
    }

    private suspend fun ensureLoaded(serverId: String) {
        if (loadedScopes.contains(serverId)) return
        if (allScopesCleared || clearedScopes.contains(serverId)) {
            cache.remove(serverId)
            loadedScopes.add(serverId)
            return
        }
        val boundary = credentialBoundary
        val persisted = store.load(serverId)
        val byHost = ConcurrentHashMap<String, MutableList<Cookie>>()
        for (cookie in persisted) {
            // Blank domain => host-only (e.g. legacy migrated session cookie).
            // Bucket it under a wildcard "*" so it is returned for every host.
            val host = cookie.domain.removePrefix(".").ifBlank { WILDCARD_HOST }
            byHost.getOrPut(host) { mutableListOf() }.add(cookie)
        }
        synchronized(credentialLock) {
            if (boundary != credentialBoundary ||
                allScopesCleared ||
                clearedScopes.contains(serverId)
            ) {
                return
            }
            cache[serverId] = byHost
            loadedScopes.add(serverId)
        }
    }

    private fun persist(serverId: String) {
        val snapshot =
            cache[serverId]?.values?.flatten()?.toList() ?: return
        enqueuePersistence { store.save(serverId, snapshot) }
    }

    /** Preserve save/clear call order even when [storeScope] is multi-threaded. */
    private fun enqueuePersistence(operation: suspend () -> Unit) {
        synchronized(persistenceLock) {
            val previous = persistenceTail
            persistenceTail =
                storeScope.launch {
                    previous?.join()
                    operation()
                }
        }
    }

    /**
     * Evict expired cookies for the active (or all) server scopes to prevent
     * unbounded growth. OkHttp represents cookies without an explicit expiry
     * with a future sentinel, so no session-cookie exception is needed.
     */
    fun pruneServerCache(allScopes: Boolean = false) {
        val now = System.currentTimeMillis()
        val scopeKeys =
            if (allScopes) cache.keys.toList() else listOf(credentialBoundary.serverId)
        for (scope in scopeKeys) {
            val hosts = cache[scope] ?: continue
            for ((host, bucket) in hosts) {
                synchronized(bucket) {
                    bucket.removeAll { it.expiresAt <= now }
                }
                if (bucket.isEmpty()) hosts.remove(host)
            }
            persist(scope)
        }
    }

    /** Clear the active server scope's in-memory + persisted cookies. */
    fun clearActive() {
        synchronized(credentialLock) {
            val scope = credentialBoundary.serverId
            credentialBoundary =
                credentialBoundary.copy(
                    generation = credentialBoundary.generation + 1,
                    acceptsCookies = false,
                )
            cache.remove(scope)
            clearedScopes.add(scope)
            loadedScopes.add(scope)
            enqueuePersistence { store.clear(scope) }
        }
    }

    /** Clear every server scope (logout / full reset). */
    fun clearAll() {
        synchronized(credentialLock) {
            cache.clear()
            loadedScopes.clear()
            clearedScopes.clear()
            allScopesCleared = true
            credentialBoundary =
                credentialBoundary.copy(
                    generation = credentialBoundary.generation + 1,
                    acceptsCookies = false,
                )
            enqueuePersistence { store.clearAll() }
        }
    }

    private fun cookieWritesEnabled(serverId: String): Boolean = !allScopesCleared && !clearedScopes.contains(serverId)

    companion object {
        const val DEFAULT_SERVER_ID = "default"

        /** Bucket key for host-only (blank-domain) cookies, returned for any host. */
        const val WILDCARD_HOST = "*"
    }
}
