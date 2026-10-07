package me.river.nightbell

import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import me.river.nightbell.domain.HeaderPair
import me.river.nightbell.domain.HttpMethod
import me.river.nightbell.domain.WebhookEvent
import me.river.nightbell.domain.WebhookFacts
import me.river.nightbell.domain.WebhookFormat
import me.river.nightbell.domain.WebhookPayload
import me.river.nightbell.domain.WebhookTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebhookPayloadTest {

    private val utc = ZoneId.of("UTC")

    // 2026-10-07T14:02:00Z
    private val at = 1_791_381_720_000L

    private val down = WebhookFacts(
        event = WebhookEvent.DOWN,
        monitorId = "m1",
        monitorName = "Payments API",
        monitorUrl = "https://pay.example.com/health",
        monitorKind = "Status check",
        reason = "Unexpected status",
        message = "HTTP 503",
        statusCode = 503,
        latencyMs = 412,
        at = at,
        downSinceAt = at - 5 * 60_000,
        groups = listOf("Production"),
        sender = "On-call Pixel",
    )

    private fun target(format: WebhookFormat, url: String = "https://hooks.example.com/abc", block: WebhookTarget.() -> WebhookTarget = { this }) =
        WebhookTarget(id = "t1", name = "Ops", format = format, url = url).block()

    private fun render(target: WebhookTarget, facts: WebhookFacts = down) =
        WebhookPayload.render(target, facts, deliveryId = "d-1", sentAtMs = at, appVersion = "3.16.0", zone = utc)

    private fun body(target: WebhookTarget, facts: WebhookFacts = down): JsonObject =
        Json.parseToJsonElement(render(target, facts).body!!).jsonObject

    @Test
    fun `every event has a title and a sentence a stranger can read`() {
        val lines = WebhookEvent.entries.associateWith { event ->
            val facts = down.copy(event = event, sloMs = 2500, latencyMs = 3200, downForMs = 7 * 60_000, certDaysLeft = 3)
            WebhookPayload.title(facts) to WebhookPayload.text(facts)
        }
        assertEquals("Payments API is down" to "Unexpected status: HTTP 503", lines[WebhookEvent.DOWN])
        assertEquals("Payments API is back up", lines[WebhookEvent.RECOVERED]!!.first)
        assertEquals("Down for 7 min. Answered in 3.2 s.", lines[WebhookEvent.RECOVERED]!!.second)
        assertEquals("Answered in 3.2 s, over its 2.5 s budget.", lines[WebhookEvent.DEGRADED]!!.second)
        assertEquals("Payments API's certificate expires in 3 days", lines[WebhookEvent.CERTIFICATE]!!.first)
        assertTrue(lines[WebhookEvent.STILL_DOWN]!!.second.startsWith("Down for 5 min."))
        lines.values.forEach { (title, text) ->
            assertTrue(title.isNotBlank())
            assertTrue(text.isNotBlank())
        }
    }

    @Test
    fun `plain JSON carries every documented field with the right types`() {
        val json = body(target(WebhookFormat.NIGHTBELL_JSON))
        assertEquals(1, json["schema"]!!.jsonPrimitive.int)
        assertEquals("down", json["event"]!!.jsonPrimitive.content)
        assertEquals("down", json["severity"]!!.jsonPrimitive.content)
        assertEquals("Payments API", json["monitor"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("Production", json["monitor"]!!.jsonObject["groups"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals(503, json["check"]!!.jsonObject["status_code"]!!.jsonPrimitive.int)
        assertEquals("2026-10-07T14:02:00Z", json["at"]!!.jsonPrimitive.content)
        assertEquals(at / 1000, json["at_unix"]!!.jsonPrimitive.long)
        assertEquals("2026-10-07T13:57:00Z", json["down_since"]!!.jsonPrimitive.content)
        assertEquals(300L, json["down_for_seconds"]!!.jsonPrimitive.long)
        assertEquals(JsonNull, json["certificate_days_left"])
        assertEquals("d-1", json["delivery_id"]!!.jsonPrimitive.content)
        assertFalse(json["test"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `a monitor name with quotes, a newline and an emoji still makes valid JSON in every format`() {
        val nasty = down.copy(monitorName = "The \"main\" API\nline two 🔥 Café <b>&</b>")
        WebhookFormat.entries.filter { it != WebhookFormat.CUSTOM }.forEach { format ->
            val t = target(format, url = if (format == WebhookFormat.TELEGRAM) "123456789:AAbbccddeeffgghhiijjkk" else "https://h.example.com/x") {
                copy(chatId = "-100123")
            }
            val parsed = runCatching { Json.parseToJsonElement(render(t, nasty).body!!) }
            assertTrue("$format did not produce JSON", parsed.isSuccess)
            assertTrue("$format lost the name", parsed.getOrThrow().toString().contains("Caf"))
        }
    }

    @Test
    fun `teams gets an adaptive card inside a message envelope`() {
        val json = body(target(WebhookFormat.TEAMS))
        assertEquals("message", json["type"]!!.jsonPrimitive.content)
        val attachment = json["attachments"]!!.jsonArray[0].jsonObject
        assertEquals("application/vnd.microsoft.card.adaptive", attachment["contentType"]!!.jsonPrimitive.content)
        val card = attachment["content"]!!.jsonObject
        assertEquals("AdaptiveCard", card["type"]!!.jsonPrimitive.content)
        assertEquals("1.4", card["version"]!!.jsonPrimitive.content)
        val title = card["body"]!!.jsonArray[0].jsonObject
        assertEquals("Payments API is down", title["text"]!!.jsonPrimitive.content)
        assertEquals("Attention", title["color"]!!.jsonPrimitive.content)
        val action = card["actions"]!!.jsonArray[0].jsonObject
        assertEquals("Action.OpenUrl", action["type"]!!.jsonPrimitive.content)
        assertEquals("https://pay.example.com/health", action["url"]!!.jsonPrimitive.content)
    }

    @Test
    fun `slack escapes its three control characters and keeps a plain text fallback`() {
        val json = body(target(WebhookFormat.SLACK), down.copy(monitorName = "A<B>&C"))
        val text = json["text"]!!.jsonPrimitive.content
        assertTrue(text, text.contains("A&lt;B&gt;&amp;C is down"))
        assertTrue(text.startsWith(":red_circle:"))
        assertEquals("section", json["blocks"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `discord never lets a monitor name ping the server`() {
        val json = body(target(WebhookFormat.DISCORD), down.copy(monitorName = "@everyone"))
        assertEquals(JsonArray(emptyList()), json["allowed_mentions"]!!.jsonObject["parse"])
        val embed = json["embeds"]!!.jsonArray[0].jsonObject
        assertEquals(0xFF4D57, embed["color"]!!.jsonPrimitive.int)
        assertEquals("2026-10-07T14:02:00Z", embed["timestamp"]!!.jsonPrimitive.content)
    }

    @Test
    fun `discord trims to its own limits instead of being refused`() {
        val json = body(target(WebhookFormat.DISCORD), down.copy(monitorName = "x".repeat(400)))
        assertTrue(json["embeds"]!!.jsonArray[0].jsonObject["title"]!!.jsonPrimitive.content.length <= 256)
    }

    @Test
    fun `ntfy publishes JSON to the server root with the topic in the body`() {
        val request = render(target(WebhookFormat.NTFY, url = "https://ntfy.sh/nightbell-alerts"))
        assertEquals("https://ntfy.sh/", request.url)
        val json = Json.parseToJsonElement(request.body!!).jsonObject
        assertEquals("nightbell-alerts", json["topic"]!!.jsonPrimitive.content)
        assertEquals(5, json["priority"]!!.jsonPrimitive.int)
        assertEquals("https://pay.example.com/health", json["click"]!!.jsonPrimitive.content)
    }

    @Test
    fun `ntfy on a self hosted path keeps the path and the query`() {
        val request = render(target(WebhookFormat.NTFY, url = "https://home.lan/ntfy/alerts?auth=abc"))
        assertEquals("https://home.lan/ntfy/?auth=abc", request.url)
    }

    @Test
    fun `gotify takes either the root or the message address`() {
        assertEquals("https://g.lan/message?token=x", WebhookPayload.gotifyUrl("https://g.lan?token=x"))
        assertEquals("https://g.lan/message?token=x", WebhookPayload.gotifyUrl("https://g.lan/message?token=x"))
        assertEquals("https://g.lan/message", WebhookPayload.gotifyUrl("https://g.lan/"))
    }

    @Test
    fun `telegram builds the bot API address from a bare token`() {
        val request = render(target(WebhookFormat.TELEGRAM, url = "123456789:AAbbccddeeffgghhiijjkk") { copy(chatId = "-100123") })
        assertEquals("https://api.telegram.org/bot123456789:AAbbccddeeffgghhiijjkk/sendMessage", request.url)
        val json = Json.parseToJsonElement(request.body!!).jsonObject
        assertEquals("-100123", json["chat_id"]!!.jsonPrimitive.content)
        assertNull("no parse mode, so no markup can break", json["parse_mode"])
    }

    @Test
    fun `custom JSON body escapes values but leaves the template's own quotes alone`() {
        val t = target(WebhookFormat.CUSTOM) {
            copy(bodyTemplate = """{"msg": "{{title}}", "code": {{status_code}}}""")
        }
        val json = body(t, down.copy(monitorName = "Say \"hi\""))
        assertEquals("Say \"hi\" is down", json["msg"]!!.jsonPrimitive.content)
        assertEquals(503, json["code"]!!.jsonPrimitive.int)
    }

    @Test
    fun `custom form body and address are URL encoded`() {
        val t = target(WebhookFormat.CUSTOM, url = "https://api.example.com/send?text={{title}}") {
            copy(
                method = HttpMethod.POST,
                contentType = "application/x-www-form-urlencoded",
                bodyTemplate = "message={{title}}&id={{monitor_id}}",
            )
        }
        val request = render(t)
        assertEquals("https://api.example.com/send?text=Payments%20API%20is%20down", request.url)
        assertEquals("message=Payments%20API%20is%20down&id=m1", request.body)
        assertEquals("application/x-www-form-urlencoded", request.contentType)
    }

    @Test
    fun `custom GET sends no body even with a template`() {
        val t = target(WebhookFormat.CUSTOM) { copy(method = HttpMethod.GET, bodyTemplate = "ignored") }
        val request = render(t)
        assertNull(request.body)
        assertNull(request.contentType)
    }

    @Test
    fun `custom with an empty body falls back to the plain JSON`() {
        val json = body(target(WebhookFormat.CUSTOM))
        assertEquals("down", json["event"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an unknown placeholder is sent exactly as typed`() {
        val out = WebhookPayload.substitute("{{titel}} {{title}}", mapOf("title" to "X"), WebhookPayload.Escape.NONE)
        assertEquals("{{titel}} X", out)
        assertEquals(listOf("titel"), WebhookPayload.unknownPlaceholders("{{titel}} {{ title }}"))
    }

    @Test
    fun `signature is HMAC SHA256 over timestamp dot body`() {
        // Computed independently with Python's hmac module.
        assertEquals(
            "e89dcbfdce3392fb275033fb083d8c81e095a63bf687d9a2cd50e7d5f55e21f6",
            WebhookPayload.signature("whsec_test_secret_123", "1700000000", "{\"a\":1}"),
        )
    }

    @Test
    fun `signed requests carry the timestamp the signature was made with`() {
        val request = render(target(WebhookFormat.NIGHTBELL_JSON) { copy(signingSecret = "whsec_test_secret_123") })
        val headers = request.headers.toMap()
        val stamp = headers.getValue("X-Nightbell-Timestamp")
        assertEquals((at / 1000).toString(), stamp)
        assertEquals(
            "sha256=" + WebhookPayload.signature("whsec_test_secret_123", stamp, request.body!!),
            headers.getValue("X-Nightbell-Signature"),
        )
    }

    @Test
    fun `identity headers and user headers with placeholders are sent`() {
        val request = render(target(WebhookFormat.NIGHTBELL_JSON) { copy(headers = listOf(HeaderPair("X-Monitor", "{{monitor}}\nnext"))) })
        val headers = request.headers.toMap()
        assertEquals("Nightbell/3.16.0", headers["User-Agent"])
        assertEquals("down", headers["X-Nightbell-Event"])
        assertEquals("d-1", headers["X-Nightbell-Delivery"])
        assertEquals("a header cannot carry a newline", "Payments API next", headers["X-Monitor"])
        assertFalse(headers.containsKey("X-Nightbell-Signature"))
    }

    @Test
    fun `a link is only offered when it can be opened`() {
        assertEquals("", WebhookPayload.link(down.copy(monitorUrl = "github.com/a/b")))
        assertEquals("https://x.y", WebhookPayload.link(down.copy(link = "https://x.y")))
    }

    @Test
    fun `spans and latencies read like a person wrote them`() {
        assertEquals("1 s", WebhookPayload.spanText(200))
        assertEquals("12 min", WebhookPayload.spanText(12 * 60_000L))
        assertEquals("3 h 5 min", WebhookPayload.spanText((3 * 60 + 5) * 60_000L))
        assertEquals("2 d", WebhookPayload.spanText(48 * 3_600_000L))
        assertEquals("340 ms", WebhookPayload.latencyText(340))
        assertEquals("3.2 s", WebhookPayload.latencyText(3_200))
    }
}
