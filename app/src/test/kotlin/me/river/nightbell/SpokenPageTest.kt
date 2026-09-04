package me.river.nightbell

import me.river.nightbell.domain.AlertPolicy
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.SpokenPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpokenPageTest {

    @Test
    fun `default template reads as a sentence`() {
        val spoken = SpokenPage.render(
            name = "Wireguard gateway",
            reason = "Host not found",
            downForMs = 4 * 60_000L,
        )
        assertEquals("Nightbell alert. Wireguard gateway is down. Host not found.", spoken)
    }

    @Test
    fun `a duration can be added for a page that wants one`() {
        assertEquals(
            "Checkout API is down. Timed out. Down for 4 minutes.",
            SpokenPage.render(
                template = "{name} is down. {reason}. Down for {duration}.",
                name = "Checkout API",
                reason = "Timed out",
                downForMs = 4 * 60_000L,
            ),
        )
    }

    @Test
    fun `a url instead of a name is read as a host`() {
        assertEquals("api.example.com", SpokenPage.speakableName("https://api.example.com/healthz?deep=1"))
        assertEquals("example.com", SpokenPage.speakableName("http://www.example.com"))
        assertEquals("10.0.0.5", SpokenPage.speakableName("https://10.0.0.5:8443/status"))
    }

    @Test
    fun `separators in a name become pauses`() {
        assertEquals("wg gateway prod", SpokenPage.speakableName("wg-gateway_prod"))
    }

    @Test
    fun `an unnamed monitor still says something`() {
        assertEquals("A monitor", SpokenPage.speakableName("   "))
    }

    @Test
    fun `durations are words`() {
        assertEquals("just now", SpokenPage.spokenDuration(0L))
        assertEquals("30 seconds", SpokenPage.spokenDuration(30_000L))
        assertEquals("1 minute", SpokenPage.spokenDuration(61_000L))
        assertEquals("2 minutes", SpokenPage.spokenDuration(2 * 60_000L + 59_000L))
        assertEquals("1 hour", SpokenPage.spokenDuration(3_600_000L))
        assertEquals("1 hour 5 minutes", SpokenPage.spokenDuration(3_600_000L + 5 * 60_000L))
        assertEquals("3 hours 1 minute", SpokenPage.spokenDuration(3 * 3_600_000L + 60_000L))
    }

    @Test
    fun `a negative duration cannot be spoken as a negative number`() {
        assertEquals("just now", SpokenPage.spokenDuration(-5_000L))
    }

    @Test
    fun `a json failure message is cut at a word boundary`() {
        val body = "Body assertion failed: expected \"ok\" but the response was " +
            "{\"status\":\"degraded\",\"queue\":184123,\"workers\":0,\"detail\":\"replication lag\"}"
        val spoken = SpokenPage.speakableReason(body)
        assertTrue("was ${spoken.length} chars", spoken.length <= 110)
        assertTrue(spoken.startsWith("Body assertion failed"))
        assertTrue("not a prefix of the message: $spoken", body.startsWith(spoken))
        assertEquals(' ', body[spoken.length])
    }

    @Test
    fun `a blank reason does not leave a gap in the sentence`() {
        val spoken = SpokenPage.render(name = "Vault", reason = "  ", downForMs = 60_000L)
        assertEquals("Nightbell alert. Vault is down. Check failed.", spoken)
    }

    @Test
    fun `one monitor down leaves no dangling punctuation`() {
        val spoken = SpokenPage.render(
            template = "{name} is down. {reason}. {others}.",
            name = "Vault",
            reason = "Timed out",
            downForMs = 60_000L,
            otherPending = 0,
        )
        assertEquals("Vault is down. Timed out.", spoken)
    }

    @Test
    fun `others is counted out loud`() {
        val one = SpokenPage.render(
            template = "{others}",
            name = "Vault",
            reason = "Timed out",
            downForMs = 0L,
            otherPending = 1,
        )
        val many = SpokenPage.render(
            template = "{others}",
            name = "Vault",
            reason = "Timed out",
            downForMs = 0L,
            otherPending = 3,
        )
        assertEquals("and one more is down", one)
        assertEquals("and 3 more are down", many)
    }

    @Test
    fun `a template with no placeholders is spoken as written`() {
        val spoken = SpokenPage.render(
            template = "Wake up.",
            name = "Vault",
            reason = "Timed out",
            downForMs = 0L,
        )
        assertEquals("Wake up.", spoken)
    }

    @Test
    fun `a blank template falls back to the default`() {
        val spoken = SpokenPage.render(
            template = "",
            name = "Vault",
            reason = "Timed out",
            downForMs = 60_000L,
        )
        assertEquals("Nightbell alert. Vault is down. Timed out.", spoken)
    }

    @Test
    fun `no announcement runs longer than the cap`() {
        val spoken = SpokenPage.render(
            template = "{reason} ".repeat(40),
            name = "Vault",
            reason = "Body assertion failed on a very long response body indeed",
            downForMs = 0L,
        )
        assertTrue("was ${spoken.length} chars", spoken.length <= 220)
    }

    // ---- when an announcement is owed ---------------------------------------

    private fun due(
        enabled: Boolean = true,
        audible: Boolean = true,
        onRepeats: Boolean = true,
        pageCount: Int = 1,
        last: String? = null,
    ) = SpokenPage.isDue(
        enabled = enabled,
        audible = audible,
        onRepeats = onRepeats,
        pageCount = pageCount,
        key = SpokenPage.keyOf("m1", pageCount),
        lastSpokenKey = last,
    )

    @Test
    fun `the first page of an outage is spoken`() {
        assertTrue(due())
    }

    @Test
    fun `the same page is not spoken twice`() {
        assertTrue(!due(last = SpokenPage.keyOf("m1", 1)))
    }

    @Test
    fun `a repeat is a new announcement`() {
        assertTrue(due(pageCount = 2, last = SpokenPage.keyOf("m1", 1)))
    }

    @Test
    fun `repeats can be turned off without silencing the first page`() {
        assertTrue(due(onRepeats = false, pageCount = 1))
        assertTrue(!due(onRepeats = false, pageCount = 2, last = SpokenPage.keyOf("m1", 1)))
    }

    @Test
    fun `a silenced ringer silences the announcement`() {
        assertTrue(!due(audible = false))
    }

    @Test
    fun `the feature being off is the first thing checked`() {
        assertTrue(!due(enabled = false))
    }

    @Test
    fun `two monitors paging do not share an announcement`() {
        val first = SpokenPage.keyOf("m1", 1)
        val second = SpokenPage.keyOf("m2", 1)
        assertTrue(first != second)
        assertTrue(
            SpokenPage.isDue(
                enabled = true,
                audible = true,
                onRepeats = true,
                pageCount = 1,
                key = second,
                lastSpokenKey = first,
            ),
        )
    }

    // ---- which monitors speak ------------------------------------------------

    private fun monitor(id: String, global: Boolean, speak: Boolean) = Monitor(
        id = id,
        name = id,
        url = "https://example.com/$id",
        useGlobalAlerts = global,
        alert = AlertPolicy(speak = speak),
    )

    @Test
    fun `a monitor on the global policy follows the global switch`() {
        val settings = GlobalSettings(defaultAlert = AlertPolicy(speak = true))
        assertTrue(SpokenPage.speaks(monitor("m1", global = true, speak = false), settings))
    }

    @Test
    fun `a monitor on its own policy answers for itself`() {
        val settings = GlobalSettings(defaultAlert = AlertPolicy(speak = true))
        assertTrue(!SpokenPage.speaks(monitor("m1", global = false, speak = false), settings))
        assertTrue(SpokenPage.speaks(monitor("m2", global = false, speak = true), settings))
    }

    @Test
    fun `alerts switched off anywhere above mean silence`() {
        val master = GlobalSettings(
            masterAlertsEnabled = false,
            defaultAlert = AlertPolicy(speak = true),
        )
        assertTrue(!SpokenPage.speaks(monitor("m1", global = true, speak = true), master))
        val policyOff = GlobalSettings(defaultAlert = AlertPolicy(enabled = false, speak = true))
        assertTrue(!SpokenPage.speaks(monitor("m1", global = true, speak = true), policyOff))
    }

    @Test
    fun `the count is what settings shows`() {
        val settings = GlobalSettings(defaultAlert = AlertPolicy(speak = true))
        val monitors = listOf(
            monitor("a", global = true, speak = false),
            monitor("b", global = false, speak = true),
            monitor("c", global = false, speak = false),
        )
        assertEquals(2, SpokenPage.speakingCount(monitors, settings))
        assertEquals(0, SpokenPage.speakingCount(emptyList(), settings))
    }

    // ---- adding a placeholder from the chips ---------------------------------

    @Test
    fun `a placeholder is appended with a space`() {
        assertEquals(
            "Nightbell alert. {name} is down. {reason}. {duration}",
            SpokenPage.withToken(SpokenPage.DEFAULT_TEMPLATE, SpokenPage.Token.DURATION),
        )
    }

    @Test
    fun `an empty template takes the placeholder on its own`() {
        assertEquals("{name}", SpokenPage.withToken("", SpokenPage.Token.NAME))
        assertEquals("{name}", SpokenPage.withToken("   ", SpokenPage.Token.NAME))
    }

    @Test
    fun `tapping the same placeholder twice does not add it twice`() {
        val once = SpokenPage.withToken("Wake up. {name}", SpokenPage.Token.NAME)
        assertEquals("Wake up. {name}", once)
    }

    @Test
    fun `a trailing space is not doubled`() {
        assertEquals("Wake up. {name}", SpokenPage.withToken("Wake up. ", SpokenPage.Token.NAME))
    }

    // ---- a voice cannot translate --------------------------------------------

    @Test
    fun `a non-english voice with nightbell's own words is a mismatch`() {
        assertTrue(SpokenPage.voiceMismatch(template = "", voiceTag = "vi-VN"))
        assertTrue(SpokenPage.voiceMismatch(template = SpokenPage.DEFAULT_TEMPLATE, voiceTag = "vi-VN"))
        assertTrue(
            SpokenPage.voiceMismatch(template = "{name} bị lỗi. {reason}.", voiceTag = "vi-VN"),
        )
    }

    @Test
    fun `a sentence the user wrote themselves is their business`() {
        // No {reason}, so nothing in it came from the app: whatever language it is
        // in, the matching voice is the right one and there is nothing to warn about.
        assertTrue(!SpokenPage.voiceMismatch(template = "{name} bị lỗi rồi.", voiceTag = "vi-VN"))
    }

    @Test
    fun `an english voice is never a mismatch`() {
        assertTrue(!SpokenPage.voiceMismatch(template = "", voiceTag = "en-GB"))
        assertTrue(!SpokenPage.voiceMismatch(template = "", voiceTag = "en_US"))
        assertTrue(!SpokenPage.voiceMismatch(template = "", voiceTag = "EN-au"))
    }

    @Test
    fun `no chosen voice is no mismatch`() {
        assertTrue(!SpokenPage.voiceMismatch(template = "", voiceTag = ""))
    }

    @Test
    fun `the default sentence and reason are app words`() {
        assertTrue(SpokenPage.carriesAppWords(""))
        assertTrue(SpokenPage.carriesAppWords(SpokenPage.DEFAULT_TEMPLATE))
        assertTrue(SpokenPage.carriesAppWords("Alarma. {reason}"))
        assertTrue(!SpokenPage.carriesAppWords("{name} nie działa."))
    }
}
