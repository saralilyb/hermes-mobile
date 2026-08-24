package com.m57.hermescontrol.ui.common

import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors

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
}
