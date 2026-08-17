package com.m57.hermescontrol.ui.profiles

import com.m57.hermescontrol.data.model.ActiveProfileResponse
import com.m57.hermescontrol.data.model.CreateProfileRequest
import com.m57.hermescontrol.data.model.ProfileInfo
import com.m57.hermescontrol.data.model.ProfilesResponse
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class ProfilesViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockApi: HermesApiService

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockkObject(ApiClient)
        mockApi = mockk(relaxed = true)
        every { ApiClient.hermesApi } returns mockApi
        coEvery { mockApi.getProfiles() } returns
            Response.success(
                ProfilesResponse(
                    listOf(ProfileInfo(name = "default", is_default = true)),
                ),
            )
        coEvery { mockApi.getActiveProfile() } returns
            Response.success(ActiveProfileResponse(active = "default"))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `clone success uses create endpoint with source and reloads`() {
        coEvery { mockApi.createProfile(any()) } returns Response.success(Unit)
        val viewModel = ProfilesViewModel(ioDispatcher = testDispatcher)

        viewModel.cloneProfile("default", "dev-copy")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify {
            mockApi.createProfile(
                CreateProfileRequest(
                    name = "dev-copy",
                    clone_from = "default",
                ),
            )
        }
        coVerify { mockApi.getProfiles() }
        assertTrue(
            viewModel.uiState.value.toastMessage.orEmpty()
                .contains("cloned successfully"),
        )
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `clone failure surfaces error and clears loading`() {
        coEvery { mockApi.createProfile(any()) } returns
            Response.error(400, "{}".toResponseBody(null))
        val viewModel = ProfilesViewModel(ioDispatcher = testDispatcher)

        viewModel.cloneProfile("default", "dev-copy")
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(
            viewModel.uiState.value.toastMessage.orEmpty()
                .contains("Failed to clone profile"),
        )
        assertFalse(viewModel.uiState.value.isLoading)
    }
}
