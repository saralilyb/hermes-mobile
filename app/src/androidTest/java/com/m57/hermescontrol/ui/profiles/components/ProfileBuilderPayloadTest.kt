package com.m57.hermescontrol.ui.profiles.components

import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.CreateProfileRequest
import com.m57.hermescontrol.ui.profiles.ProfilesUiState
import com.m57.hermescontrol.ui.profiles.ProfilesViewModel
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class ProfileBuilderPayloadTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun optingOutSendsNoSkillsRatherThanBooleanKeepSkills() = submit(optOut = true)

    @Test
    fun defaultsOmitBothSkillPolicyFields() = submit(optOut = false)

    private fun submit(optOut: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val viewModel = mockk<ProfilesViewModel>(relaxed = true)
        composeRule.setContent { ProfileBuilderView(ProfilesUiState(), viewModel, onCancel = {}) }
        composeRule.onNodeWithText(context.getString(R.string.profiles_builder_label_profile_name))
            .performTextInput("regression-profile")

        fun next() {
            composeRule.onNodeWithText(context.getString(R.string.profiles_builder_action_next)).performClick()
        }
        next()
        next()
        if (optOut) composeRule.onAllNodes(isToggleable())[0].performClick()
        next()
        next()
        composeRule.onNodeWithText(context.getString(R.string.profiles_builder_action_create)).performClick()
        val request = slot<CreateProfileRequest>()
        verify(exactly = 1) { viewModel.createProfile(capture(request), any()) }
        assertEquals("regression-profile", request.captured.name)
        assertEquals(if (optOut) true else null, request.captured.no_skills)
        assertNull(request.captured.keep_skills)
    }
}
