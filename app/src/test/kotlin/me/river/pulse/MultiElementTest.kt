package me.river.pulse

import me.river.pulse.domain.ElementMode
import me.river.pulse.domain.ElementTarget
import me.river.pulse.domain.FailureKind
import me.river.pulse.domain.Monitor
import me.river.pulse.domain.MonitorKind
import me.river.pulse.domain.Validation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Multi-element page monitors: the target list, its migration from the 1.0.0
 * single-element format, and the validation that guards it.
 *
 * The aggregation itself (N lookups → one verdict) needs a WebView and lives in
 * `ElementMonitorTest` on-device.
 */
class MultiElementTest {

    private fun captured(id: String, mode: ElementMode = ElementMode.EXISTS) = ElementTarget(
        cssSelector = "#$id",
        elementId = id,
        tagName = "span",
        textSnippet = "value of $id",
        mode = mode,
    )

    private fun pageMonitor(vararg targets: ElementTarget) = Monitor(
        id = "m1",
        kind = MonitorKind.WEBSITE_ELEMENT,
        url = "https://example.com",
    ).withTargets(targets.toList())

    // ---- migration ----------------------------------------------------------

    @Test
    fun `a 1_0_0 monitor with one element migrates into the list`() {
        // What a store written by the previous release decodes to: `element`
        // set, `elements` absent and therefore empty.
        val legacy = Monitor(
            id = "m1",
            kind = MonitorKind.WEBSITE_ELEMENT,
            url = "https://example.com",
            element = captured("price"),
        )
        assertTrue(legacy.elements.isEmpty())
        assertEquals(1, legacy.targets.size)
        assertEquals("price", legacy.targets.first().elementId)

        val migrated = legacy.migrated
        assertEquals(1, migrated.elements.size)
        assertEquals("price", migrated.elements.first().elementId)
        // `element` is kept in step so a downgrade still finds a target.
        assertEquals(migrated.elements.first(), migrated.element)
    }

    @Test
    fun `migration is idempotent`() {
        val once = pageMonitor(captured("a"), captured("b")).migrated
        assertEquals(once, once.migrated)
        assertEquals(2, once.targets.size)
    }

    @Test
    fun `the legacy field mirrors the head of the list`() {
        val monitor = pageMonitor(captured("first"), captured("second"))
        assertEquals("first", monitor.element?.elementId)

        val reordered = monitor.withTargets(monitor.targets.reversed())
        assertEquals("second", reordered.element?.elementId)
    }

    @Test
    fun `clearing the targets clears the legacy field too`() {
        val cleared = pageMonitor(captured("a")).withTargets(emptyList())
        assertNull(cleared.element)
        assertTrue(cleared.targets.isEmpty())
    }

    @Test
    fun `uncaptured placeholders are not treated as targets`() {
        // The setup flow can hold a blank ElementTarget before the user picks.
        val blank = Monitor(id = "m1", kind = MonitorKind.WEBSITE_ELEMENT, element = ElementTarget())
        assertTrue(blank.targets.isEmpty())
        assertNull(blank.migrated.element)
    }

    // ---- validation ---------------------------------------------------------

    @Test
    fun `a page monitor with no elements is invalid`() {
        val report = Validation.report(pageMonitor())
        assertFalse(report.isValid)
        assertEquals(Validation.Severity.ERROR, report.of(Validation.Field.ELEMENT)?.severity)
    }

    @Test
    fun `every element needs its own expected text`() {
        val monitor = pageMonitor(
            captured("a", ElementMode.EXISTS),
            captured("b", ElementMode.TEXT_EQUALS).copy(expectedText = ""),
        )
        val report = Validation.report(monitor)
        assertFalse(report.isValid)
        val note = report.of(Validation.Field.ELEMENT_TEXT)
        assertNotNull(note)
        assertTrue(
            "the message should say which element is at fault: ${note?.message}",
            note!!.message.contains("<span>") || note.message.contains("#b"),
        )
    }

    @Test
    fun `a fully configured multi-element monitor validates`() {
        val monitor = pageMonitor(
            captured("a", ElementMode.EXISTS),
            captured("b", ElementMode.TEXT_CONTAINS).copy(expectedText = "In stock", label = "Stock"),
            captured("c", ElementMode.NOT_EXISTS),
        )
        val report = Validation.report(monitor)
        assertTrue(report.errors.joinToString { it.message }, report.isValid)
    }

    @Test
    fun `watching the same node twice is a warning, not an error`() {
        val duplicate = captured("a")
        val report = Validation.report(pageMonitor(duplicate, duplicate.copy(label = "again")))
        assertTrue(report.isValid)
        assertEquals(Validation.Severity.WARNING, report.of(Validation.Field.ELEMENT)?.severity)
    }

    // ---- labels -------------------------------------------------------------

    @Test
    fun `display label falls back through nickname, tag, then selector`() {
        assertEquals("Price", captured("a").copy(label = "Price").displayLabel)
        assertEquals("<span>", captured("a").displayLabel)
        assertEquals(
            "#a",
            captured("a").copy(tagName = "", label = "").displayLabel,
        )
    }

    // ---- SLO validation -----------------------------------------------------

    @Test
    fun `a latency budget longer than the timeout is a warning`() {
        val monitor = Monitor(
            id = "m1",
            url = "https://example.com",
            timeoutSeconds = 5,
            latencySloMs = 30_000,
        )
        val report = Validation.report(monitor)
        assertTrue(report.isValid)
        assertEquals(Validation.Severity.WARNING, report.of(Validation.Field.LATENCY_SLO)?.severity)
    }

    @Test
    fun `a sane latency budget produces no note`() {
        val monitor = Monitor(
            id = "m1",
            url = "https://example.com",
            timeoutSeconds = 15,
            latencySloMs = 2_500,
        )
        assertNull(Validation.report(monitor).of(Validation.Field.LATENCY_SLO))
    }

    @Test
    fun `urgent repeats below a minute are rejected`() {
        val monitor = Monitor(
            id = "m1",
            url = "https://example.com",
            urgent = true,
            urgentRepeatMinutes = 0,
        )
        val report = Validation.report(monitor)
        assertFalse(report.isValid)
        assertEquals(Validation.Severity.ERROR, report.of(Validation.Field.URGENT)?.severity)
    }

    // ---- failure kinds ------------------------------------------------------

    @Test
    fun `element failures keep their own failure kind`() {
        assertEquals("Element check failed", FailureKind.ELEMENT.headline)
        assertTrue(FailureKind.ELEMENT.hint.isNotBlank())
    }
}
