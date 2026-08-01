package me.river.pulse

import me.river.pulse.domain.Assertions
import me.river.pulse.domain.AssertionMode
import me.river.pulse.domain.BodyAssertion
import me.river.pulse.domain.ElementMode
import me.river.pulse.domain.ElementTarget
import me.river.pulse.domain.FailureKind
import me.river.pulse.domain.StatusExpectation
import me.river.pulse.domain.StatusMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssertionsTest {

    // ---- status ------------------------------------------------------------

    @Test
    fun `exact status matches only that code`() {
        val expectation = StatusExpectation(StatusMode.EXACT, code = 204)
        assertTrue(Assertions.statusMatches(expectation, 204))
        assertFalse(Assertions.statusMatches(expectation, 200))
        assertFalse(Assertions.statusMatches(expectation, 404))
    }

    @Test
    fun `any success accepts the whole 2xx family`() {
        val expectation = StatusExpectation(StatusMode.ANY_SUCCESS)
        listOf(200, 201, 204, 226, 299).forEach {
            assertTrue("expected $it to pass", Assertions.statusMatches(expectation, it))
        }
        listOf(199, 300, 404, 500).forEach {
            assertFalse("expected $it to fail", Assertions.statusMatches(expectation, it))
        }
    }

    @Test
    fun `range is inclusive and tolerates reversed bounds`() {
        val forward = StatusExpectation(StatusMode.RANGE, rangeStart = 300, rangeEnd = 399)
        assertTrue(Assertions.statusMatches(forward, 300))
        assertTrue(Assertions.statusMatches(forward, 399))
        assertFalse(Assertions.statusMatches(forward, 400))

        val reversed = StatusExpectation(StatusMode.RANGE, rangeStart = 399, rangeEnd = 300)
        assertTrue(Assertions.statusMatches(reversed, 350))
    }

    @Test
    fun `any mode passes on any real response but not on zero`() {
        val expectation = StatusExpectation(StatusMode.ANY)
        assertTrue(Assertions.statusMatches(expectation, 500))
        assertFalse(Assertions.statusMatches(expectation, 0))
    }

    @Test
    fun `status failure reports the expectation in the message`() {
        val verdict = Assertions.checkStatus(StatusExpectation(StatusMode.EXACT, code = 200), 503)
        assertFalse(verdict.passed)
        assertEquals(FailureKind.STATUS, verdict.kind)
        assertTrue(verdict.message.contains("503"))
        assertTrue(verdict.message.contains("200"))
    }

    // ---- body --------------------------------------------------------------

    @Test
    fun `no assertion always passes`() {
        assertTrue(Assertions.checkBody(BodyAssertion(), "literally anything").passed)
    }

    @Test
    fun `contains is case insensitive by default`() {
        val assertion = BodyAssertion(AssertionMode.CONTAINS, value = "STATUS ok")
        assertTrue(Assertions.checkBody(assertion, "the status OK today").passed)
        assertFalse(Assertions.checkBody(assertion, "status broken").passed)
    }

    @Test
    fun `contains honours case sensitivity when asked`() {
        val assertion = BodyAssertion(AssertionMode.CONTAINS, value = "OK", caseSensitive = true)
        assertTrue(Assertions.checkBody(assertion, "everything OK").passed)
        assertFalse(Assertions.checkBody(assertion, "everything ok").passed)
    }

    @Test
    fun `not contains flags forbidden text`() {
        val assertion = BodyAssertion(AssertionMode.NOT_CONTAINS, value = "exception")
        assertTrue(Assertions.checkBody(assertion, "all good").passed)
        val failure = Assertions.checkBody(assertion, "NullPointerException thrown")
        assertFalse(failure.passed)
        assertEquals(FailureKind.BODY, failure.kind)
    }

    @Test
    fun `exact ignores surrounding whitespace`() {
        val assertion = BodyAssertion(AssertionMode.EXACT, value = "pong")
        assertTrue(Assertions.checkBody(assertion, "  pong\n").passed)
        assertFalse(Assertions.checkBody(assertion, "pong pong").passed)
    }

    @Test
    fun `regex matches and reports invalid patterns as misconfiguration`() {
        val good = BodyAssertion(AssertionMode.REGEX, value = "\"status\"\\s*:\\s*\"ok\"")
        assertTrue(Assertions.checkBody(good, """{"status": "ok"}""").passed)
        assertFalse(Assertions.checkBody(good, """{"status": "down"}""").passed)

        val broken = BodyAssertion(AssertionMode.REGEX, value = "([unclosed")
        val verdict = Assertions.checkBody(broken, "anything")
        assertFalse(verdict.passed)
        assertEquals(FailureKind.BAD_CONFIG, verdict.kind)
    }

    @Test
    fun `json field equals resolves nested paths and array indices`() {
        val body = """{"data":{"items":[{"state":"UP"},{"state":"DOWN"}],"count":2}}"""
        val first = BodyAssertion(AssertionMode.JSON_FIELD_EQUALS, value = "UP", jsonPath = "data.items[0].state")
        assertTrue(Assertions.checkBody(first, body).passed)

        val second = BodyAssertion(AssertionMode.JSON_FIELD_EQUALS, value = "UP", jsonPath = "data.items[1].state")
        assertFalse(Assertions.checkBody(second, body).passed)

        val count = BodyAssertion(AssertionMode.JSON_FIELD_EQUALS, value = "2", jsonPath = "data.count")
        assertTrue(Assertions.checkBody(count, body).passed)
    }

    @Test
    fun `json field exists distinguishes missing from present`() {
        val body = """{"health":{"db":"ok"}}"""
        assertTrue(
            Assertions.checkBody(
                BodyAssertion(AssertionMode.JSON_FIELD_EXISTS, jsonPath = "health.db"),
                body,
            ).passed,
        )
        assertFalse(
            Assertions.checkBody(
                BodyAssertion(AssertionMode.JSON_FIELD_EXISTS, jsonPath = "health.cache"),
                body,
            ).passed,
        )
    }

    @Test
    fun `non json body fails json assertions with a clear message`() {
        val verdict = Assertions.checkBody(
            BodyAssertion(AssertionMode.JSON_FIELD_EQUALS, value = "x", jsonPath = "a.b"),
            "<html>nope</html>",
        )
        assertFalse(verdict.passed)
        assertTrue(verdict.message.contains("not valid JSON"))
    }

    @Test
    fun `path resolver returns null for missing hops`() {
        val root = Assertions.parseJson("""{"a":{"b":[1,2]}}""")
        assertNotNull(root)
        assertNotNull(Assertions.resolvePath(root!!, "a.b[1]"))
        assertNull(Assertions.resolvePath(root, "a.b[9]"))
        assertNull(Assertions.resolvePath(root, "a.zzz"))
    }

    // ---- elements ----------------------------------------------------------

    @Test
    fun `element exists mode only cares about presence`() {
        val target = ElementTarget(cssSelector = "#price", mode = ElementMode.EXISTS)
        assertTrue(Assertions.checkElement(target, found = true, text = "").passed)
        val missing = Assertions.checkElement(target, found = false, text = "")
        assertFalse(missing.passed)
        assertEquals(FailureKind.ELEMENT, missing.kind)
    }

    @Test
    fun `element not exists inverts the check`() {
        val target = ElementTarget(cssSelector = ".banner", mode = ElementMode.NOT_EXISTS)
        assertTrue(Assertions.checkElement(target, found = false, text = "").passed)
        assertFalse(Assertions.checkElement(target, found = true, text = "Outage").passed)
    }

    @Test
    fun `element text comparisons collapse whitespace`() {
        val equals = ElementTarget(
            cssSelector = "#state",
            mode = ElementMode.TEXT_EQUALS,
            expectedText = "In stock",
        )
        assertTrue(Assertions.checkElement(equals, found = true, text = "  In    stock \n").passed)
        assertFalse(Assertions.checkElement(equals, found = true, text = "Out of stock").passed)

        val contains = equals.copy(mode = ElementMode.TEXT_CONTAINS, expectedText = "stock")
        assertTrue(Assertions.checkElement(contains, found = true, text = "Back in stock soon").passed)
    }

    @Test
    fun `snapshot mode detects any drift from the captured text`() {
        val target = ElementTarget(
            cssSelector = "#price",
            mode = ElementMode.TEXT_MATCHES_SNAPSHOT,
            textSnippet = "£42.00",
        )
        assertTrue(Assertions.checkElement(target, found = true, text = "£42.00").passed)
        val changed = Assertions.checkElement(target, found = true, text = "£43.50")
        assertFalse(changed.passed)
        assertTrue(changed.message.contains("changed"))
    }

    @Test
    fun `missing element fails text comparisons with a targeted message`() {
        val target = ElementTarget(
            elementId = "price",
            mode = ElementMode.TEXT_CONTAINS,
            expectedText = "42",
        )
        val verdict = Assertions.checkElement(target, found = false, text = "")
        assertFalse(verdict.passed)
        assertTrue(verdict.message.contains("#price"))
    }
}
