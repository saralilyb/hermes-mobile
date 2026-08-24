package com.m57.hermescontrol.ui.common

import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class SecureGatewayMediaPlayerPolicyTest {
    @Test
    fun `prepared media only auto starts in a visible lifecycle`() {
        assertFalse(shouldAutoStartMedia(Lifecycle.State.DESTROYED))
        assertFalse(shouldAutoStartMedia(Lifecycle.State.INITIALIZED))
        assertFalse(shouldAutoStartMedia(Lifecycle.State.CREATED))
        assertTrue(shouldAutoStartMedia(Lifecycle.State.STARTED))
        assertTrue(shouldAutoStartMedia(Lifecycle.State.RESUMED))
    }

    @Test
    fun `media initialization runs on the supplied background dispatcher`() {
        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "secure-media-init") }
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            val callerThread = Thread.currentThread().name
            val initializationThread =
                runBlocking {
                    initializeSecureMedia(dispatcher) { Thread.currentThread().name }
                }

            assertEquals("secure-media-init", initializationThread)
            assertFalse(initializationThread == callerThread)
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `boundary probe runs off caller and stale result terminates playback path`() {
        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "secure-media-boundary") }
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            val callerThread = Thread.currentThread().name
            var probeThread = ""
            var currentCount = 0
            var staleCount = 0

            runBlocking {
                monitorSecureMediaBoundary(
                    dispatcher = dispatcher,
                    pollIntervalMillis = 0,
                    isCurrent = {
                        probeThread = Thread.currentThread().name
                        false
                    },
                    onCurrent = { currentCount++ },
                    onStale = { staleCount++ },
                )
            }

            assertEquals("secure-media-boundary", probeThread)
            assertFalse(probeThread == callerThread)
            assertEquals(0, currentCount)
            assertEquals(1, staleCount)
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `cancelled boundary monitor cannot publish a completed probe`() =
        runBlocking {
            val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "secure-media-boundary") }
            val dispatcher = executor.asCoroutineDispatcher()
            try {
                val probeStarted = CompletableDeferred<Unit>()
                val finishProbe = CompletableDeferred<Unit>()
                val callbackCount = AtomicInteger()
                val job =
                    launch {
                        monitorSecureMediaBoundary(
                            dispatcher = dispatcher,
                            pollIntervalMillis = 0,
                            isCurrent = {
                                probeStarted.complete(Unit)
                                runBlocking { finishProbe.await() }
                                true
                            },
                            onCurrent = { callbackCount.incrementAndGet() },
                            onStale = { callbackCount.incrementAndGet() },
                        )
                    }

                probeStarted.await()
                job.cancel()
                finishProbe.complete(Unit)
                job.join()

                assertEquals(0, callbackCount.get())
            } finally {
                dispatcher.close()
                executor.shutdownNow()
            }
        }

    @Test
    fun `initialized media remains owned when cancelled immediately after publication`() =
        runBlocking {
            val published = CompletableDeferred<Unit>()
            val closeCount = AtomicInteger()
            val owner = Any()
            val job =
                launch {
                    initializeAndOwnSecureMedia(
                        dispatcher = Dispatchers.Unconfined,
                        initializer = { owner },
                        publish = { published.complete(Unit) },
                        close = { closeCount.incrementAndGet() },
                    )
                }

            published.await()
            job.cancelAndJoin()

            assertEquals(1, closeCount.get())
        }
}
