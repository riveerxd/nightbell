package me.river.nightbell

import me.river.nightbell.domain.LogRedactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The censor, tested adversarially.
 *
 * Every case here is a way a credential or a person could reach a log line that
 * somebody then pastes into a public issue. The table is meant to grow: when a
 * new field or a new kind of secret shows up, it gets a row before it gets a
 * field factory.
 */
class LogRedactorTest {

    @Test
    fun `a url keeps its host and loses everything that identifies`() {
        assertEquals(
            "https://example.com",
            LogRedactor.route("https://example.com"),
        )
        assertEquals(
            "https://example.com:8443",
            LogRedactor.route("https://example.com:8443/"),
        )
        // The path is a count. It routinely carries a reset token or a person's
        // name, and a count answers every question a report has ever asked.
        assertEquals(
            "https://example.com/*3",
            LogRedactor.route("https://example.com/reset/token/abc123"),
        )
        assertEquals(
            "https://example.com/*1?*2",
            LogRedactor.route("https://example.com/status?api_key=secret&debug=1"),
        )
    }

    @Test
    fun `credentials in a url never survive`() {
        val route = LogRedactor.route("https://admin:hunter2@internal.example.com/panel")
        assertEquals("https://internal.example.com/*1", route)
        assertFalse(route.contains("admin"))
        assertFalse(route.contains("hunter2"))
    }

    @Test
    fun `a url the address field would accept but a parser would not still redacts`() {
        // These reach the logging path, and an exception there is not an option.
        for (input in listOf("", "   ", "not a url", "http://", "://x", "ht!tp://a b c")) {
            val route = LogRedactor.route(input)
            assertFalse("leaked $input", route.contains(" "))
        }
    }

    @Test
    fun `a bearer token is masked and a github token is fingerprinted`() {
        val text = "GET failed: Authorization: Bearer abcdefghijklmnopqrstuvwxyz012345"
        val out = LogRedactor.scrub(text)
        assertFalse(out.contains("abcdefghijklmnopqrstuvwxyz"))
        assertTrue(out.contains(LogRedactor.MASK))

        val gh = LogRedactor.scrub("token ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ012345 rejected")
        assertFalse(gh.contains("ABCDEFGHIJKLMNOPQRSTUVWXYZ"))
        // The prefix survives, because "which kind of token" is a real question.
        assertTrue(gh.contains("ghp_"))
    }

    @Test
    fun `a named header keeps its name and loses its value`() {
        val out = LogRedactor.scrub("Cookie: session=9f3a1c2e4b6d8f0a2c4e6b8d")
        assertTrue(out.startsWith("Cookie: "))
        assertFalse(out.contains("9f3a1c2e4b6d8f0a2c4e6b8d"))
    }

    @Test
    fun `a query parameter keeps its name and loses its value`() {
        val out = LogRedactor.scrub("https://api.example.com/v1?access_token=s3cr3tvalue&page=2")
        assertFalse(out.contains("s3cr3tvalue"))
        assertTrue(out.contains("access_token="))
        // Something that is not a credential is left readable.
        assertTrue(out.contains("page=2"))
    }

    @Test
    fun `a jwt goes whole`() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJsdWthc0BleGFtcGxlLmNvbSJ9.QWERTYUIOP"
        val out = LogRedactor.scrub("upstream said $jwt")
        assertFalse(out.contains("eyJ"))
    }

    @Test
    fun `an email address goes`() {
        val out = LogRedactor.scrub("login failed for someone@example.com")
        assertFalse(out.contains("someone@example.com"))
    }

    @Test
    fun `an unrecognised long identifier is fingerprinted rather than kept`() {
        val opaque = "Zm9vYmFyYmF6cXV1eGNvcmdlZ3JhdWx0Z2FybGl4"
        val out = LogRedactor.scrub("body began $opaque")
        assertFalse(out.contains(opaque))
        assertTrue(out.contains("[${opaque.length}:"))
    }

    @Test
    fun `values the store calls secret are replaced even when they look like nothing`() {
        // The point of the known pass: a session cookie is whatever a site
        // accepts, and it does not have to look like a credential.
        val cookie = "wp_age_gate_ok=yes; visitor=friendly"
        val out = LogRedactor.scrub("replayed $cookie", known = listOf(cookie))
        assertFalse(out.contains("wp_age_gate_ok"))
    }

    @Test
    fun `the known pass ignores values too short to be a secret`() {
        // Replacing a four character value would corrupt ordinary prose rather
        // than protect anything.
        val out = LogRedactor.replaceKnown("the page said no", known = listOf("no"))
        assertEquals("the page said no", out)
    }

    @Test
    fun `a multi line value collapses to one line`() {
        val out = LogRedactor.scrub("first\nsecond\rthird\tfourth")
        assertFalse(out.contains("\n"))
        assertFalse(out.contains("\r"))
        assertFalse(out.contains("\t"))
    }

    @Test
    fun `a fingerprint identifies without authorising`() {
        val a = LogRedactor.fingerprint("ghp_thesamevalue0000")
        val b = LogRedactor.fingerprint("ghp_thesamevalue0000")
        val c = LogRedactor.fingerprint("ghp_adifferentvalue0")
        assertEquals(a, b)
        assertTrue(a != c)
        assertFalse(a.contains("thesamevalue"))
    }

    @Test
    fun `truncation marks itself`() {
        assertEquals("abc", LogRedactor.truncate("abc", 8))
        assertEquals("abcdefgh…", LogRedactor.truncate("abcdefghijkl", 8))
    }
}
