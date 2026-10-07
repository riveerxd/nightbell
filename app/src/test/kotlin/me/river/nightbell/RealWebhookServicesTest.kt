package me.river.nightbell

import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.river.nightbell.data.webhook.WebhookSender
import me.river.nightbell.domain.HeaderPair
import me.river.nightbell.domain.WebhookEvent
import me.river.nightbell.domain.WebhookFacts
import me.river.nightbell.domain.WebhookFormat
import me.river.nightbell.domain.WebhookTarget
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Sends to real ntfy and Gotify servers and reads back what they stored.
 *
 * Skipped unless the servers are named in the environment, the same way the
 * `Real*InstrumentedTest` classes only run where their network is. Local
 * containers are enough and post nothing to anybody:
 *
 * ```
 * docker run -d --rm -p 18080:80 binwiederhier/ntfy serve
 * docker run -d --rm -p 18081:80 gotify/server
 * NIGHTBELL_NTFY_URL=http://127.0.0.1:18080 \
 * NIGHTBELL_GOTIFY_URL=http://127.0.0.1:18081 NIGHTBELL_GOTIFY_APP_TOKEN=… NIGHTBELL_GOTIFY_CLIENT_TOKEN=… \
 *   ./gradlew :app:testDebugUnitTest --tests '*RealWebhookServicesTest'
 * ```
 */
class RealWebhookServicesTest {

    private val http = OkHttpClient()
    private val sender = WebhookSender()

    private val facts = WebhookFacts(
        event = WebhookEvent.DOWN,
        monitorId = "m1",
        monitorName = "Café API",
        monitorUrl = "https://cafe.example.com/health",
        reason = "Unexpected status",
        message = "HTTP 503",
        statusCode = 503,
        at = System.currentTimeMillis(),
    )

    private fun get(url: String, header: Pair<String, String>? = null): String =
        http.newCall(
            Request.Builder().url(url).apply { header?.let { header(it.first, it.second) } }.build(),
        ).execute().use { it.body.string() }

    @Test
    fun ntfyStoresTheTitlePriorityTagsAndClick() {
        val base = System.getenv("NIGHTBELL_NTFY_URL")
        assumeTrue("NIGHTBELL_NTFY_URL not set", !base.isNullOrBlank())
        val topic = "nightbell-" + UUID.randomUUID().toString().take(8)
        val attempt = runBlocking {
            sender.send(WebhookTarget(id = "t", format = WebhookFormat.NTFY, url = "$base/$topic"), facts, "d1")
        }
        assertTrue(attempt.error, attempt.delivered)

        val stored = get("$base/$topic/json?poll=1").lines().filter { it.isNotBlank() }
            .map { Json.parseToJsonElement(it).jsonObject }
            .single { it["event"]!!.jsonPrimitive.content == "message" }
        assertEquals("Café API is down", stored["title"]!!.jsonPrimitive.content)
        assertEquals("Unexpected status: HTTP 503", stored["message"]!!.jsonPrimitive.content)
        assertEquals(5, stored["priority"]!!.jsonPrimitive.int)
        assertEquals("rotating_light", stored["tags"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("https://cafe.example.com/health", stored["click"]!!.jsonPrimitive.content)
    }

    @Test
    fun gotifyStoresTheTitleMessageAndPriority() {
        val base = System.getenv("NIGHTBELL_GOTIFY_URL")
        val app = System.getenv("NIGHTBELL_GOTIFY_APP_TOKEN")
        val client = System.getenv("NIGHTBELL_GOTIFY_CLIENT_TOKEN")
        assumeTrue("Gotify not configured", !base.isNullOrBlank() && !app.isNullOrBlank() && !client.isNullOrBlank())

        // Both ways the editor allows: token in the address, and token as a header.
        val inUrl = runBlocking {
            sender.send(WebhookTarget(id = "a", format = WebhookFormat.GOTIFY, url = "$base?token=$app"), facts, "d1")
        }
        assertTrue(inUrl.error, inUrl.delivered)
        val inHeader = runBlocking {
            sender.send(
                WebhookTarget(
                    id = "b",
                    format = WebhookFormat.GOTIFY,
                    url = base,
                    headers = listOf(HeaderPair("X-Gotify-Key", app)),
                ),
                facts.copy(event = WebhookEvent.RECOVERED, latencyMs = 212, downForMs = 300_000),
                "d2",
            )
        }
        assertTrue(inHeader.error, inHeader.delivered)

        val messages = Json.parseToJsonElement(get("$base/message?limit=10", "X-Gotify-Key" to client))
            .jsonObject["messages"]!!.jsonArray.map { it.jsonObject }
        val down = messages.first { it["title"]!!.jsonPrimitive.content == "Café API is down" }
        assertEquals(8, down["priority"]!!.jsonPrimitive.int)
        val up = messages.first { it["title"]!!.jsonPrimitive.content == "Café API is back up" }
        assertEquals("Down for 5 min. Answered in 212 ms.", up["message"]!!.jsonPrimitive.content)
    }

    @Test
    fun aWrongGotifyTokenIsARefusalWithTheServersReason() {
        val base = System.getenv("NIGHTBELL_GOTIFY_URL")
        assumeTrue("Gotify not configured", !base.isNullOrBlank())
        val attempt = runBlocking {
            sender.send(WebhookTarget(id = "a", format = WebhookFormat.GOTIFY, url = "$base?token=wrong"), facts, "d")
        }
        assertEquals(401, attempt.code)
        assertTrue(attempt.error, attempt.error.startsWith("HTTP 401. The address or its key was refused"))
    }
}
