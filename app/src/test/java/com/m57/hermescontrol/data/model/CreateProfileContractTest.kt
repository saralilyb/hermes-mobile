package com.m57.hermescontrol.data.model

import com.m57.hermescontrol.data.remote.OkHttpProvider
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CreateProfileContractTest {
    private val json = OkHttpProvider.json

    private fun roundTrip(raw: String) =
        json.parseToJsonElement(json.encodeToString(json.decodeFromString<CreateProfileRequest>(raw))).jsonObject

    @Test
    fun retainedSkillsAreStringListsIncludingEmptyList() {
        for (skills in listOf("[]", "[\"weather\",\"research\"]")) {
            val raw = """{"name":"test","keep_skills":$skills}"""
            assertEquals(json.parseToJsonElement(raw).jsonObject, roundTrip(raw))
        }
    }

    @Test
    fun optOutAndExplicitFalseSurviveAlongsideCloneHubAndMcpOptions() {
        for (optOut in listOf(true, false)) {
            val raw =
                """{"name":"test","no_skills":$optOut,"hub_skills":["hub:weather"],"clone_from":"source",
                "clone_all":true,"clone_from_default":false,"provider":"p","model":"m",
                "mcp_servers":[{"name":"local","transport":"stdio","command":"tool","args":["serve"]}]}"""
            assertEquals(json.parseToJsonElement(raw).jsonObject, roundTrip(raw))
        }
    }

    @Test
    fun defaultsOmitBothSkillOptions() {
        val payload = roundTrip("""{"name":"test"}""")
        assertFalse(payload.containsKey("keep_skills"))
        assertFalse(payload.containsKey("no_skills"))
    }
}
