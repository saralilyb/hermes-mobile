package com.m57.hermescontrol.data.remote

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AuthPayloadsTest {
    @Test
    fun `password login payload safely encodes quotes and controls`() {
        val username = "user\"name\nnext"
        val password = "p\\ass\tword\u0000"

        val encoded = AuthPayloads.passwordLogin(username, password)
        val decoded = OkHttpProvider.json.parseToJsonElement(encoded).jsonObject

        assertEquals("basic", decoded.getValue("provider").jsonPrimitive.content)
        assertEquals(username, decoded.getValue("username").jsonPrimitive.content)
        assertEquals(password, decoded.getValue("password").jsonPrimitive.content)
        assertEquals("", decoded.getValue("next").jsonPrimitive.content)
        assertFalse(encoded.contains("\"username\":\"user\"name"))
    }

    @Test
    fun `ticket response is decoded with the JSON serializer`() {
        assertEquals(
            "ticket-value",
            AuthPayloads.webSocketTicket("""{"ticket":"ticket-value"}"""),
        )
    }
}
