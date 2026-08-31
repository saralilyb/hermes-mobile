package com.m57.hermescontrol.data.model

import com.m57.hermescontrol.data.remote.OkHttpProvider
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileBotsTest {
    private val json = OkHttpProvider.json

    @Test
    fun `legacy profile response remains compatible`() {
        val response = json.decodeFromString<ProfilesResponse>("""{"profiles":[{"name":"default"}]}""")
        assertNull(response.bot_mode_protocol)
        assertEquals("default", response.profiles.single().effectiveTitle)
        assertFalse(response.profiles.single().isHidden)
    }

    @Test
    fun `extended metadata and canonical identity decode`() {
        val response =
            json.decodeFromString<ProfilesResponse>(
                """
                {"bot_mode_protocol":true,"profiles":[{"name":"researcher","display_name":"Researcher",
                "description":"fallback","canonical_session":{"id":"root","resolved_id":"tip","last_active":42},
                "ui_meta":{"hermes-bots":{"title":"Scout","description":"Research bot","hidden":true,
                "avatar":{"shape":"hexagon","color":"#123ABC","icon":"science","image_url":"https://bad.invalid/x"}}}}]}
                """.trimIndent(),
            )
        val profile = response.profiles.single()
        assertTrue(response.bot_mode_protocol == true)
        assertEquals("Scout", profile.effectiveTitle)
        assertEquals("Research bot", profile.effectiveDescription)
        assertTrue(profile.isHidden)
        assertEquals("tip", profile.canonicalSessionId)
        assertEquals("science", profile.botMeta(json)?.avatar?.icon)
    }

    @Test
    fun `canonical identity falls back to id and rejects blanks`() {
        assertEquals(
            "root",
            ProfileInfo("bot", canonical_session = CanonicalSessionInfo("root", " ")).canonicalSessionId,
        )
        assertNull(ProfileInfo("bot", canonical_session = CanonicalSessionInfo(" ", null)).canonicalSessionId)
    }

    @Test
    fun `malformed or wrong typed bot metadata fails closed`() {
        val malformed =
            json.decodeFromString<ProfileInfo>(
                """{"name":"bot","display_name":"Display","description":"safe","ui_meta":{"hermes-bots":"oops"}}""",
            )
        assertNull(malformed.botMeta(json))
        assertEquals("Display", malformed.effectiveTitle)
        assertEquals("safe", malformed.effectiveDescription)
        assertFalse(malformed.isHidden)
    }
}
