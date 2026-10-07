package me.river.nightbell

import java.net.ServerSocket
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.river.nightbell.data.webhook.WebhookSender
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.HeaderPair
import me.river.nightbell.domain.WebhookEvent
import me.river.nightbell.domain.WebhookFacts
import me.river.nightbell.domain.WebhookFormat
import me.river.nightbell.domain.WebhookPayload
import me.river.nightbell.domain.WebhookTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sender over a real socket: what actually arrives, and what a failure reports. */
class WebhookSenderTest {

    private val facts = WebhookFacts(
        event = WebhookEvent.DOWN,
        monitorId = "m1",
        monitorName = "Café API",
        monitorUrl = "https://cafe.example.com",
        reason = "Timed out",
        at = 1_791_381_720_000L,
    )

    private fun sender(settings: GlobalSettings = GlobalSettings(webhookSender = "Test phone")) =
        WebhookSender(settingsFor = { settings }, appVersion = "9.9")

    @Test
    fun `a delivered event arrives as the receiver expects it, signed and identified`() {
        TinyHttpServer { TinyHttpServer.Response(code = 204) }.use { server ->
            val target = WebhookTarget(
                id = "t",
                url = server.url("/hook"),
                signingSecret = "whsec_0123456789abcdef",
                headers = listOf(HeaderPair("X-Monitor", "{{monitor}}")),
            )
            val attempt = runBlocking { sender().send(target, facts, "delivery-1") }
            assertTrue(attempt.error, attempt.delivered)
            assertEquals(204, attempt.code)

            val request = server.received.single()
            assertEquals("POST", request.method)
            assertEquals("/hook", request.path)
            assertTrue(request.headers["content-type"]!!.startsWith("application/json"))
            assertEquals("Nightbell/9.9", request.headers["user-agent"])
            assertEquals("delivery-1", request.headers["x-nightbell-delivery"])
            assertEquals("down", request.headers["x-nightbell-event"])
            assertEquals("non-ASCII survives in a header", "Café API", request.headers["x-monitor"])

            // The receiver's side of the signature check, done the way a receiver would.
            val stamp = request.headers.getValue("x-nightbell-timestamp")
            val expected = "sha256=" + WebhookPayload.signature("whsec_0123456789abcdef", stamp, request.body)
            assertEquals(expected, request.headers["x-nightbell-signature"])

            val body = Json.parseToJsonElement(request.body).jsonObject
            assertEquals("Café API is down", body["title"]!!.jsonPrimitive.content)
            assertEquals("the phone's name from settings signs the message", "Test phone", body["sender"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `a refusal reports the code, what it means and the receiver's own reason`() {
        TinyHttpServer {
            TinyHttpServer.Response(code = 400, reason = "Bad Request", body = "{\"ok\":false,\"description\":\"Bad Request: chat not found\"}")
        }.use { server ->
            val attempt = runBlocking { sender().send(WebhookTarget(id = "t", url = server.url("/x")), facts, "d") }
            assertFalse(attempt.delivered)
            assertEquals(400, attempt.code)
            assertTrue(attempt.error, attempt.error.startsWith("HTTP 400. The service did not accept the message"))
            assertTrue(attempt.snippet, attempt.snippet.contains("chat not found"))
        }
    }

    @Test
    fun `retry after from a throttling receiver is passed on`() {
        TinyHttpServer {
            TinyHttpServer.Response(code = 429, reason = "Too Many", extraHeaders = mapOf("Retry-After" to "17"))
        }.use { server ->
            val attempt = runBlocking { sender().send(WebhookTarget(id = "t", url = server.url("/x")), facts, "d") }
            assertEquals(429, attempt.code)
            assertEquals(17_000L, attempt.retryAfterMs)
        }
    }

    @Test
    fun `nothing listening is an answer of zero, worth retrying`() {
        val port = ServerSocket(0).use { it.localPort }
        val attempt = runBlocking { sender().send(WebhookTarget(id = "t", url = "http://127.0.0.1:$port/x"), facts, "d") }
        assertEquals(0, attempt.code)
        assertEquals("Could not connect", attempt.error)
    }

    @Test
    fun `a failure message never carries the address's secret`() {
        val secret = "AbCdEfGhIjKlMnOpQr"
        val attempt = runBlocking {
            sender().send(
                WebhookTarget(id = "t", url = "https://no-such-host.invalid/hooks/$secret"),
                facts,
                "d",
            )
        }
        assertEquals(0, attempt.code)
        assertFalse(attempt.error, attempt.error.contains(secret))
    }

    @Test
    fun `asking for a proxy that is not set up refuses rather than going direct`() {
        TinyHttpServer { TinyHttpServer.Response() }.use { server ->
            val attempt = runBlocking {
                sender().send(WebhookTarget(id = "t", url = server.url("/x"), useProxy = true), facts, "d")
            }
            assertEquals(-1, attempt.code)
            assertTrue(server.received.isEmpty())
        }
    }

    @Test
    fun `a self signed receiver needs the switch, and works with it`() {
        val identity = TinyTls.selfSigned()
        TinyHttpServer(identity.serverSocketFactory()) { TinyHttpServer.Response() }.use { server ->
            val strict = runBlocking { sender().send(WebhookTarget(id = "t", url = server.url("/x")), facts, "d") }
            assertEquals(0, strict.code)
            assertTrue(strict.error, strict.error.startsWith("TLS handshake failed"))
            val relaxed = runBlocking {
                sender().send(WebhookTarget(id = "t", url = server.url("/x"), acceptAnyCertificate = true), facts, "d")
            }
            assertTrue(relaxed.error, relaxed.delivered)
        }
    }

    @Test
    fun `every preset format is accepted by a plain receiver`() {
        TinyHttpServer { TinyHttpServer.Response() }.use { server ->
            WebhookFormat.entries.forEach { format ->
                val url = if (format == WebhookFormat.TELEGRAM) server.url("/bot/sendMessage") else server.url("/f/${format.name}")
                val target = WebhookTarget(id = "t", format = format, url = url, chatId = "-100123")
                val attempt = runBlocking { sender().send(target, facts, "d") }
                assertTrue("$format: ${attempt.error}", attempt.delivered)
            }
            assertEquals(WebhookFormat.entries.size, server.received.size)
        }
    }

    @Test
    fun `a web page answering instead of a webhook is named by its title, not quoted`() {
        val page = "<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">\n<title>404:  Page not\n found</title></head>"
        assertEquals("a web page titled \"404: Page not found\"", WebhookSender.readable(page))
        assertEquals("a web page, not a webhook", WebhookSender.readable("<html><body>nope</body></html>"))
        assertEquals("{\"ok\":false,\"description\":\"chat not found\"}", WebhookSender.readable("{\"ok\":false,\"description\":\"chat not found\"}\nmore"))
        assertEquals(
            "HTTP 404. Nothing at that address. Check it was copied whole: a web page titled \"404: Page not found\"",
            WebhookSender.describe(404, page),
        )
    }
}
