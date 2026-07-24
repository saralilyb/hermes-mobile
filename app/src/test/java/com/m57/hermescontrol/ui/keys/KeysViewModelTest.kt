package com.m57.hermescontrol.ui.keys

import com.m57.hermescontrol.data.model.EnvVarConfig
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class KeysViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val mockApi = mockk<HermesApiService>()

    private fun createViewModel(): KeysViewModel {
        val vm = KeysViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        return vm
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val testMainDispatcher = Dispatchers.Main
        mockkStatic(Dispatchers::class)
        every { Dispatchers.IO } returns testDispatcher
        every { Dispatchers.Main } returns testMainDispatcher
        mockkObject(ApiClient)
        every { ApiClient.hermesApi } returns mockApi
        coEvery { mockApi.getEnvVars() } returns Response.success(emptyMap())
    }

    @After
    fun tearDown() {
        unmockkAll()
        Dispatchers.resetMain()
    }

    @Test
    fun `setNewKeyName updates state flow correctly`() {
        val viewModel = createViewModel()

        assertEquals("", viewModel.uiState.value.newKeyName)

        viewModel.setNewKeyName("MY_NEW_API_KEY")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("MY_NEW_API_KEY", viewModel.uiState.value.newKeyName)
    }

    @Test
    fun `setNewKeyValue updates state flow correctly`() {
        val viewModel = createViewModel()

        assertEquals("", viewModel.uiState.value.newKeyValue)

        viewModel.setNewKeyValue("dummy_token_value_that_is_long_enough")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("dummy_token_value_that_is_long_enough", viewModel.uiState.value.newKeyValue)
    }

    @Test
    fun dismissAddDialog_clearsDraftSecret() {
        val viewModel = createViewModel()
        viewModel.openAddDialog()
        viewModel.setNewKeyName("EXAMPLE_API_KEY")
        viewModel.setNewKeyValue("example-secret-value")

        viewModel.dismissAddDialog()

        assertFalse(viewModel.uiState.value.showAddDialog)
        assertEquals("", viewModel.uiState.value.newKeyName)
        assertEquals("", viewModel.uiState.value.newKeyValue)
    }

    @Test
    fun deleteRequiresRequestAndConfirmationState() {
        val viewModel = createViewModel()

        viewModel.requestDeleteKey("EXAMPLE_API_KEY")

        assertEquals("EXAMPLE_API_KEY", viewModel.uiState.value.deleteTargetKey)

        viewModel.dismissDeleteDialog()

        assertEquals(null, viewModel.uiState.value.deleteTargetKey)
    }

    @Test
    fun updateKey_clearsPreviouslyRevealedSecret() {
        val viewModel = createViewModel()
        seedRevealedValue(viewModel, "EXAMPLE_API_KEY", "old-secret")
        coEvery { mockApi.updateEnvVar(any()) } returns Response.success(Unit)

        viewModel.updateKey("EXAMPLE_API_KEY", "new-secret")
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse("EXAMPLE_API_KEY" in viewModel.uiState.value.revealedValues)
    }

    @Test
    fun deleteKey_clearsPreviouslyRevealedSecret() {
        val viewModel = createViewModel()
        seedRevealedValue(viewModel, "EXAMPLE_API_KEY", "old-secret")
        coEvery { mockApi.deleteEnvVar(any()) } returns Response.success(Unit)

        viewModel.requestDeleteKey("EXAMPLE_API_KEY")
        viewModel.confirmDeleteKey()
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse("EXAMPLE_API_KEY" in viewModel.uiState.value.revealedValues)
    }

    @Test
    fun loadKeys_prunesRevealedSecretsMissingFromServer() {
        val viewModel = createViewModel()
        seedRevealedValue(viewModel, "REMOVED_API_KEY", "stale-secret")

        viewModel.loadKeys()
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse("REMOVED_API_KEY" in viewModel.uiState.value.revealedValues)
    }

    @Test
    fun testToggleCategory() {
        // Arrange
        val viewModel = createViewModel()

        // Seed initial categories via reflection (no test-only seeding path exists in source)
        val uiStateField = KeysViewModel::class.java.getDeclaredField("_uiState")
        uiStateField.isAccessible = true
        val uiStateFlow =
            uiStateField.get(viewModel) as MutableStateFlow<KeysUiState>

        val initialCategories =
            listOf(
                CategorySection(
                    name = "LLM Providers",
                    vars =
                        mapOf(
                            "OPENAI_API_KEY" to
                                EnvVarConfig(
                                    isSet = true,
                                    category = "LLM Providers",
                                    isPassword = true,
                                ),
                        ),
                    expanded = false,
                ),
                CategorySection(
                    name = "Tool API Keys",
                    vars =
                        mapOf(
                            "WEATHER_API_KEY" to
                                EnvVarConfig(
                                    isSet = true,
                                    category = "Tool API Keys",
                                    isPassword = true,
                                ),
                        ),
                    expanded = true,
                ),
            )
        uiStateFlow.update { it.copy(categories = initialCategories) }

        // Act - expand an unexpanded category
        viewModel.toggleCategory("LLM Providers")

        // Assert
        var categories = viewModel.uiState.value.categories
        assertEquals(2, categories.size)
        assertTrue(
            "LLM Providers should be expanded",
            categories.first { it.name == "LLM Providers" }.expanded,
        )
        assertTrue(
            "Tool API Keys should remain expanded",
            categories.first { it.name == "Tool API Keys" }.expanded,
        )

        // Act - collapse it again
        viewModel.toggleCategory("LLM Providers")

        // Assert
        categories = viewModel.uiState.value.categories
        assertEquals(2, categories.size)
        assertFalse(
            "LLM Providers should be collapsed",
            categories.first { it.name == "LLM Providers" }.expanded,
        )
        assertTrue(
            "Tool API Keys should remain expanded",
            categories.first { it.name == "Tool API Keys" }.expanded,
        )

        // Act - toggle the other category, ensure first stays untouched
        viewModel.toggleCategory("Tool API Keys")

        // Assert
        categories = viewModel.uiState.value.categories
        assertFalse(
            "LLM Providers should stay collapsed",
            categories.first { it.name == "LLM Providers" }.expanded,
        )
        assertFalse(
            "Tool API Keys should now be collapsed",
            categories.first { it.name == "Tool API Keys" }.expanded,
        )
    }

    private fun seedRevealedValue(
        viewModel: KeysViewModel,
        key: String,
        value: String,
    ) {
        val uiStateField = KeysViewModel::class.java.getDeclaredField("_uiState")
        uiStateField.isAccessible = true
        val uiStateFlow = uiStateField.get(viewModel) as MutableStateFlow<KeysUiState>
        uiStateFlow.update {
            it.copy(revealedValues = it.revealedValues + (key to value))
        }
    }
}
