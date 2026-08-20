package com.m57.hermescontrol.ui.common

import androidx.lifecycle.ViewModel
import com.m57.hermescontrol.data.remote.NetworkResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ViewModelExtTest {
    private val mainScheduler = TestCoroutineScheduler()
    private val mainDispatcher = StandardTestDispatcher(mainScheduler)
    private val ioScheduler = TestCoroutineScheduler()
    private val ioDispatcher = StandardTestDispatcher(ioScheduler)

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `safeLaunchLoad uses the supplied IO dispatcher`() {
        val viewModel = object : ViewModel() {}
        var apiCalled = false
        var successCalled = false

        viewModel.safeLaunchLoad(
            ioDispatcher = ioDispatcher,
            apiCall = {
                apiCalled = true
                NetworkResult.Success(Unit)
            },
            onStart = {},
            onSuccess = { successCalled = true },
            onError = {},
        )

        mainScheduler.runCurrent()
        assertFalse(apiCalled)

        ioScheduler.runCurrent()
        assertTrue(apiCalled)
        assertFalse(successCalled)

        mainScheduler.runCurrent()
        assertTrue(successCalled)
    }
}
