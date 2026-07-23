// Modified from Hy4ri/hermes-mobile for this fork; see NOTICE.

package com.m57.hermescontrol.ui.authlogin

import android.app.Application
import android.util.Log
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.AuthSessionState
import com.m57.hermescontrol.data.remote.OkHttpProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Response
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.IOException

class AuthLoginViewModelTest {
    private lateinit var viewModel: AuthLoginViewModel
    private val app = mockk<Application>(relaxed = true)

    @Before
    fun setup() {
        AuthSessionState.markAuthenticated()
        mockkObject(AuthManager)
        every { AuthManager.getBaseUrl() } returns "https://localhost:9119/"
        every { AuthManager.getConnectionProfiles() } returns emptyList()

        mockkObject(OkHttpProvider)
        every { OkHttpProvider.probe } returns mockk<OkHttpClient>(relaxed = true)

        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.d(any<String>(), any<String>()) } returns 0

        every { app.getString(R.string.auth_login_error_unreachable) } returns "Dashboard unreachable"

        viewModel = AuthLoginViewModel(app)
    }

    @After
    fun teardown() {
        AuthSessionState.markAuthenticated()
        unmockkAll()
    }

    @Test
    fun `onBaseUrlChange updates URL and clears error and auth mode`() {
        viewModel.onBaseUrlChange(" https://192.168.1.100:9443/proxy ")

        val state = viewModel.uiState.value
        assertEquals("https://192.168.1.100:9443/proxy", state.baseUrl)
        assertNull(state.errorMessage)
        assertNull(state.authMode)
        assertNull(state.transportWarning)
    }

    @Test
    fun `clearConnectionState resets connectionSuccess errorMessage and isLoading`() {
        viewModel.clearConnectionState()

        val state = viewModel.uiState.value
        assertFalse(state.connectionSuccess)
        assertFalse(state.isLoading)
        assertNull(state.errorMessage)
    }

    @Test
    fun `expired authentication blocks existing profile shortcut`() {
        AuthSessionState.requireSignIn()

        viewModel.useExistingProfile("expired-profile")

        verify(exactly = 0) { AuthManager.setSelectedProfileId(any()) }
        assertFalse(viewModel.uiState.value.connectionSuccess)
    }

    @Test
    fun `probe when status probe throws IOException updates error state`() {
        val mockCall = mockk<okhttp3.Call>()
        every { OkHttpProvider.probe.newCall(any()) } returns mockCall
        every { mockCall.execute() } throws IOException("Connection refused")

        viewModel.probe()

        val state = runBlocking { viewModel.uiState.first { !it.probing } }
        assertFalse(state.probing)
        assertNull(state.authMode)
        assertEquals("Dashboard unreachable", state.errorMessage)
    }

    @Test
    fun `probe when status probe returns unsuccessful updates error state`() {
        val mockCall = mockk<okhttp3.Call>()
        val mockResponse = mockk<Response>()
        every { OkHttpProvider.probe.newCall(any()) } returns mockCall
        every { mockCall.execute() } returns mockResponse
        every { mockResponse.isSuccessful } returns false
        every { mockResponse.close() } returns Unit

        viewModel.probe()

        val state = runBlocking { viewModel.uiState.first { !it.probing } }
        assertFalse(state.probing)
        assertNull(state.authMode)
        assertEquals("Dashboard unreachable", state.errorMessage)
    }
}
