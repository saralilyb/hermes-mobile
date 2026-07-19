package com.m57.hermescontrol.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerEndpointTest {
    @Test
    fun `normalizes an HTTPS base URL and trailing slash`() {
        val endpoint =
            ServerEndpoint.parse(
                "https://hermes.example.com:9119/proxy",
            )

        assertEquals(
            "https://hermes.example.com:9119/proxy/",
            endpoint.baseUrl.toString(),
        )
        assertNull(endpoint.securityWarning)
    }

    @Test
    fun `derives WSS from HTTPS without losing path prefix`() {
        val endpoint =
            ServerEndpoint.parse(
                "https://hermes.example.com:9119/hermes/",
            )

        assertEquals(
            "wss://hermes.example.com:9119/hermes/api/ws?ticket=t%20value",
            endpoint.webSocketUrl("ticket", "t value"),
        )
    }

    @Test
    fun `derives WS from explicitly allowed HTTP`() {
        val endpoint =
            ServerEndpoint.parse(
                "http://hermes.example.com:9119",
                CleartextPolicy.ALLOW_WITH_WARNING,
            )

        assertEquals(
            "ws://hermes.example.com:9119/api/ws?token=abc",
            endpoint.webSocketUrl("token", "abc"),
        )
        assertNotNull(endpoint.securityWarning)
    }

    @Test
    fun `uses HTTPS and HTTP default ports`() {
        val https = ServerEndpoint.parse("https://hermes.example.com")
        val http =
            ServerEndpoint.parse(
                "http://hermes.example.com",
                CleartextPolicy.ALLOW_WITH_WARNING,
            )

        assertEquals(443, https.baseUrl.port)
        assertEquals("https://hermes.example.com/", https.baseUrl.toString())
        assertEquals(80, http.baseUrl.port)
        assertEquals("http://hermes.example.com/", http.baseUrl.toString())
    }

    @Test
    fun `preserves explicit ports`() {
        val endpoint =
            ServerEndpoint.parse(
                "https://hermes.example.com:9443",
            )

        assertEquals(9443, endpoint.baseUrl.port)
    }

    @Test
    fun `supports IPv6 literals`() {
        val endpoint = ServerEndpoint.parse("https://[2001:db8::1]:9119/root")

        assertEquals("2001:db8::1", endpoint.baseUrl.host)
        assertEquals(
            "https://[2001:db8::1]:9119/root/api/status",
            endpoint.resolve("api/status").toString(),
        )
    }

    @Test
    fun `resolves API paths below the reverse proxy prefix`() {
        val endpoint =
            ServerEndpoint.parse(
                "https://hermes.example.com:9119/prefix/",
            )

        assertEquals(
            "https://hermes.example.com:9119/prefix/api/auth/ws-ticket",
            endpoint.resolve("/api/auth/ws-ticket").toString(),
        )
    }

    @Test
    fun `rejects malformed and unsupported base URLs`() {
        assertThrows(IllegalArgumentException::class.java) {
            ServerEndpoint.parse("hermes.example.com:9119")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ServerEndpoint.parse("ftp://hermes.example.com")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ServerEndpoint.parse("https://")
        }
    }

    @Test
    fun `rejects credentials query and fragment in a base URL`() {
        assertThrows(IllegalArgumentException::class.java) {
            ServerEndpoint.parse("https://user:pass@hermes.example.com")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ServerEndpoint.parse("https://hermes.example.com/?token=nope")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ServerEndpoint.parse("https://hermes.example.com/#fragment")
        }
    }

    @Test
    fun `rejects cleartext when production policy is used`() {
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                ServerEndpoint.parse(
                    "http://hermes.example.com:9119",
                    CleartextPolicy.DENY,
                )
            }

        assertTrue(error.message.orEmpty().contains("Cleartext HTTP"))
    }

    @Test
    fun `redacted WebSocket log URL never contains a ticket`() {
        val endpoint =
            ServerEndpoint.parse(
                "https://hermes.example.com:9119/prefix",
            )
        val socketUrl = endpoint.webSocketUrl("ticket", "secret-ticket")

        assertEquals(
            "wss://hermes.example.com:9119/prefix/api/ws",
            ServerEndpoint.redactWebSocketUrlForLog(socketUrl),
        )
        assertTrue(
            !ServerEndpoint.redactWebSocketUrlForLog(socketUrl)
                .contains("secret-ticket"),
        )
    }
}
