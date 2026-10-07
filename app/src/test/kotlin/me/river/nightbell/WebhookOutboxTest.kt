package me.river.nightbell

import me.river.nightbell.domain.HeaderPair
import me.river.nightbell.domain.PendingDelivery
import me.river.nightbell.domain.Validation
import me.river.nightbell.domain.WebhookEvent
import me.river.nightbell.domain.WebhookFacts
import me.river.nightbell.domain.WebhookFormat
import me.river.nightbell.domain.WebhookOutbox
import me.river.nightbell.domain.WebhookScope
import me.river.nightbell.domain.WebhookSecrets
import me.river.nightbell.domain.WebhookState
import me.river.nightbell.domain.WebhookStatus
import me.river.nightbell.domain.WebhookStatusLine
import me.river.nightbell.domain.WebhookTarget
import me.river.nightbell.domain.WebhookValidation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebhookOutboxTest {

    private val hour = 3_600_000L

    private fun delivery(id: String, target: String = "t", createdAt: Long = 0L, attempts: Int = 0) =
        PendingDelivery(id, target, WebhookFacts(event = WebhookEvent.DOWN), createdAt, attempts, createdAt)

    @Test
    fun `what each answer means for the delivery`() {
        assertEquals(WebhookOutbox.Verdict.DELIVERED, WebhookOutbox.verdict(200))
        assertEquals(WebhookOutbox.Verdict.DELIVERED, WebhookOutbox.verdict(204))
        assertEquals(WebhookOutbox.Verdict.RETRY, WebhookOutbox.verdict(0))
        assertEquals(WebhookOutbox.Verdict.RETRY, WebhookOutbox.verdict(429))
        assertEquals(WebhookOutbox.Verdict.RETRY, WebhookOutbox.verdict(503))
        assertEquals(WebhookOutbox.Verdict.REFUSED, WebhookOutbox.verdict(400))
        assertEquals(WebhookOutbox.Verdict.REFUSED, WebhookOutbox.verdict(404))
        assertEquals(WebhookOutbox.Verdict.REFUSED, WebhookOutbox.verdict(-1))
    }

    @Test
    fun `backoff doubles from thirty seconds and settles at thirty minutes`() {
        assertEquals(30_000L, WebhookOutbox.backoffMs(1))
        assertEquals(60_000L, WebhookOutbox.backoffMs(2))
        assertEquals(480_000L, WebhookOutbox.backoffMs(5))
        assertEquals(1_800_000L, WebhookOutbox.backoffMs(7))
        assertEquals(1_800_000L, WebhookOutbox.backoffMs(40))
    }

    @Test
    fun `a delivered message leaves the queue and clears the failure streak`() {
        val d = delivery("a")
        val failing = WebhookState(outbox = listOf(d), status = mapOf("t" to WebhookStatus(failuresInARow = 3, lastError = "x")))
        val after = WebhookOutbox.afterAttempt(failing, d, 202, "", nowMs = 1000)
        assertTrue(after.outbox.isEmpty())
        val status = after.status.getValue("t")
        assertEquals(0, status.failuresInARow)
        assertEquals("", status.lastError)
        assertEquals(1000L, status.lastDeliveredAt)
    }

    @Test
    fun `a retryable failure stays queued with its next attempt pushed back`() {
        val d = delivery("a")
        val after = WebhookOutbox.afterAttempt(WebhookState(outbox = listOf(d)), d, 0, "Timed out", nowMs = 1000)
        val queued = after.outbox.single()
        assertEquals(1, queued.attempts)
        assertEquals(31_000L, queued.nextAttemptAt)
        assertEquals("a", queued.id)
        assertEquals("Timed out", after.status.getValue("t").lastError)
    }

    @Test
    fun `retry after from the receiver wins, within an hour`() {
        val d = delivery("a")
        val polite = WebhookOutbox.afterAttempt(WebhookState(outbox = listOf(d)), d, 429, "", 0, retryAfterMs = 120_000)
        assertEquals(120_000L, polite.outbox.single().nextAttemptAt)
        val rude = WebhookOutbox.afterAttempt(WebhookState(outbox = listOf(d)), d, 429, "", 0, retryAfterMs = 7 * 24 * hour)
        assertEquals(hour, rude.outbox.single().nextAttemptAt)
        assertEquals(120_000L, WebhookOutbox.retryAfterMs(" 120 "))
        assertNull(WebhookOutbox.retryAfterMs("Wed, 21 Oct 2015 07:28:00 GMT"))
    }

    @Test
    fun `a refused message is dropped and counted, not retried forever`() {
        val d = delivery("a")
        val after = WebhookOutbox.afterAttempt(WebhookState(outbox = listOf(d)), d, 404, "HTTP 404", nowMs = 1)
        assertTrue(after.outbox.isEmpty())
        assertEquals(1, after.status.getValue("t").dropped)
    }

    @Test
    fun `retrying stops once the next try would land past a day`() {
        val d = delivery("a", createdAt = 0, attempts = 20)
        val after = WebhookOutbox.afterAttempt(WebhookState(outbox = listOf(d)), d, 503, "", nowMs = 23 * hour + 50 * 60_000)
        assertTrue(after.outbox.isEmpty())
        assertEquals(1, after.status.getValue("t").dropped)
        assertTrue(WebhookOutbox.isExpired(d, 25 * hour))
        assertFalse(WebhookOutbox.isExpired(d, 23 * hour))
    }

    @Test
    fun `an overflowing queue sheds its oldest and says so per target`() {
        val full = WebhookState(outbox = (1..WebhookOutbox.MAX_QUEUED).map { delivery("old$it", target = "slow") })
        val after = WebhookOutbox.enqueue(full, listOf(delivery("new1", target = "fast"), delivery("new2", target = "fast")))
        assertEquals(WebhookOutbox.MAX_QUEUED, after.outbox.size)
        assertEquals("new2", after.outbox.last().id)
        assertFalse(after.outbox.any { it.id == "old1" || it.id == "old2" })
        assertEquals(2, after.status.getValue("slow").dropped)
    }

    @Test
    fun `next wake is the earliest due delivery`() {
        val state = WebhookState(outbox = listOf(delivery("a").copy(nextAttemptAt = 500), delivery("b").copy(nextAttemptAt = 200)))
        assertEquals(200L, WebhookOutbox.nextWakeAt(state))
        assertNull(WebhookOutbox.nextWakeAt(WebhookState()))
    }

    // ---- secrets -----------------------------------------------------------------

    @Test
    fun `a saved address is shown as its host and last four`() {
        assertEquals(
            "hooks.slack.com/…WXYZ",
            WebhookSecrets.redactAddress("https://hooks.slack.com/services/T000/B000/abcdefWXYZ", WebhookFormat.SLACK),
        )
        assertEquals("ntfy.sh", WebhookSecrets.redactAddress("https://ntfy.sh/alerts", WebhookFormat.NTFY))
        assertEquals(
            "123456789:…kkzz",
            WebhookSecrets.redactAddress("123456789:AAbbccddeeffgghhiijjkkzz", WebhookFormat.TELEGRAM),
        )
    }

    @Test
    fun `scrub removes the address, its token parts, header values and the signing secret`() {
        val target = WebhookTarget(
            id = "t",
            url = "https://gotify.lan/message?token=AbCdEfGhIjKlMn",
            headers = listOf(HeaderPair("Authorization", "Bearer tk_supersecretvalue")),
            signingSecret = "whsec_signing_secret",
        )
        val raw = "failed https://gotify.lan/message?token=AbCdEfGhIjKlMn with AbCdEfGhIjKlMn, " +
            "Bearer tk_supersecretvalue and whsec_signing_secret"
        val scrubbed = WebhookSecrets.scrub(raw, target)
        assertFalse(scrubbed, scrubbed.contains("AbCdEfGhIjKlMn"))
        assertFalse(scrubbed, scrubbed.contains("tk_supersecretvalue"))
        assertFalse(scrubbed, scrubbed.contains("whsec_signing_secret"))
        assertTrue(scrubbed.startsWith("failed"))
    }

    // ---- validation --------------------------------------------------------------

    private fun valid(block: WebhookTarget.() -> WebhookTarget = { this }) =
        WebhookValidation.check(WebhookTarget(id = "t", url = "https://hooks.example.com/x").block())

    @Test
    fun `a plain target with an address is valid`() {
        assertTrue(valid().isValid)
    }

    @Test
    fun `missing address, no events and an empty scope each block the save`() {
        val report = valid { copy(url = "", events = emptySet(), scope = WebhookScope(all = false)) }
        assertFalse(report.isValid)
        assertEquals(Validation.Severity.ERROR, report.of(WebhookValidation.Field.ADDRESS)!!.severity)
        assertEquals(Validation.Severity.ERROR, report.of(WebhookValidation.Field.EVENTS)!!.severity)
        assertEquals(Validation.Severity.ERROR, report.of(WebhookValidation.Field.SCOPE)!!.severity)
    }

    @Test
    fun `telegram wants a token shaped token and a chat`() {
        val bad = valid { copy(format = WebhookFormat.TELEGRAM, url = "not a token", chatId = "") }
        assertEquals(Validation.Severity.ERROR, bad.of(WebhookValidation.Field.ADDRESS)!!.severity)
        assertEquals(Validation.Severity.ERROR, bad.of(WebhookValidation.Field.CHAT_ID)!!.severity)
        assertTrue(valid { copy(format = WebhookFormat.TELEGRAM, url = "123456789:AAbbccddeeffgghhiijjkk", chatId = "-1001234") }.isValid)
        assertTrue(valid { copy(format = WebhookFormat.TELEGRAM, url = "123456789:AAbbccddeeffgghhiijjkk", chatId = "@my_channel") }.isValid)
    }

    @Test
    fun `a retired teams connector address is warned about but allowed`() {
        val report = valid { copy(format = WebhookFormat.TEAMS, url = "https://contoso.webhook.office.com/webhookb2/x") }
        assertTrue(report.isValid)
        assertEquals(Validation.Severity.WARNING, report.of(WebhookValidation.Field.ADDRESS)!!.severity)
    }

    @Test
    fun `custom templates are checked for typos and for JSON that breaks`() {
        val typo = valid { copy(format = WebhookFormat.CUSTOM, bodyTemplate = """{"t":"{{titel}}"}""") }
        assertTrue(typo.isValid)
        assertTrue(typo.of(WebhookValidation.Field.BODY)!!.message.contains("{{titel}}"))
        val broken = valid { copy(format = WebhookFormat.CUSTOM, bodyTemplate = """{"t": {{title}}}""") }
        assertTrue(broken.of(WebhookValidation.Field.BODY)!!.message.contains("not valid JSON"))
        val fine = valid { copy(format = WebhookFormat.CUSTOM, bodyTemplate = """{"t": "{{title}}", "n": {{latency_ms}}}""") }
        assertNull(fine.of(WebhookValidation.Field.BODY))
    }

    @Test
    fun `placeholders in a custom address do not fail URL validation`() {
        assertTrue(valid { copy(format = WebhookFormat.CUSTOM, url = "https://api.example.com/{{monitor_id}}?t={{title}}") }.isValid)
    }

    @Test
    fun `a header name with a space is refused`() {
        val report = valid { copy(headers = listOf(HeaderPair("Bad Name", "v"))) }
        assertFalse(report.isValid)
    }

    // ---- the settings row ----------------------------------------------------------

    @Test
    fun `the status line says what a person needs to know first`() {
        val t = WebhookTarget(id = "t", url = "https://x.y/z")
        assertEquals(WebhookStatusLine.Tone.QUIET, WebhookStatusLine.of(t.copy(enabled = false), null, 0, 0).tone)
        assertEquals(WebhookStatusLine.Tone.WAITING, WebhookStatusLine.of(t.copy(url = ""), null, 0, 0).tone)
        val failing = WebhookStatusLine.of(t, WebhookStatus(failuresInARow = 2, lastError = "HTTP 404. Nothing at that address"), 3, 0)
        assertEquals(WebhookStatusLine.Tone.FAILING, failing.tone)
        assertEquals("Failing: HTTP 404. Nothing at that address · 3 waiting", failing.text)
        assertEquals("2 waiting to send", WebhookStatusLine.of(t, null, 2, 0).text)
        assertEquals(
            "Last delivered 4 min ago · 1 given up on",
            WebhookStatusLine.of(t, WebhookStatus(lastDeliveredAt = 1_000, dropped = 1), 0, 1_000 + 4 * 60_000).text,
        )
        assertTrue(WebhookStatusLine.of(t, null, 0, 0).text.startsWith("Nothing sent yet"))
    }
}
