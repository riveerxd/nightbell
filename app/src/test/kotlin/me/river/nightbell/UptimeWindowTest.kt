package me.river.nightbell

import me.river.nightbell.domain.MonitorRuntime
import me.river.nightbell.domain.Sample
import me.river.nightbell.domain.UptimeWindow
import me.river.nightbell.domain.UptimeWindows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Uptime over a span of wall time, rather than over however many checks happen
 * to be sitting in the buffer.
 *
 * The figure the detail screen used to show was passing-checks over
 * retained-checks, which is a different quantity wearing the name of the one
 * people read it as: the same 93% covered fifteen hours at a fifteen-minute
 * cadence and twenty-five days at ten-hourly, and nothing on screen said which.
 * These tests pin the replacement, and especially the two cases the old figure
 * could not express at all — "we cannot see back that far" and "we have not
 * looked yet".
 */
class UptimeWindowTest {

    private val now = 1_700_000_000_000L
    private val hour = 60L * 60 * 1000

    private fun runtime(vararg samples: Sample) = MonitorRuntime(samples = samples.toList())

    private fun at(hoursAgo: Long, ok: Boolean) =
        Sample(at = now - hoursAgo * hour, ok = ok, latencyMs = 120)

    @Test
    fun `no samples at all is unknown, not zero percent`() {
        assertNull(runtime().uptimeWithin(now, UptimeWindows.DAY_MS))
    }

    @Test
    fun `samples entirely outside the window are unknown, not zero percent`() {
        // A monitor nobody has checked today is not a monitor that was down all
        // day, and 0% would say exactly that.
        val runtime = runtime(at(40, ok = true), at(30, ok = true))
        assertNull(runtime.uptimeWithin(now, UptimeWindows.DAY_MS))
    }

    @Test
    fun `percentage counts only the checks inside the window`() {
        val runtime = runtime(
            at(40, ok = false), // outside — must not drag the figure down
            at(20, ok = true),
            at(10, ok = true),
            at(5, ok = false),
            at(1, ok = true),
        )
        val window = runtime.uptimeWithin(now, UptimeWindows.DAY_MS)!!
        assertEquals(4, window.checks)
        assertEquals(75f, window.percent, 0.01f)
    }

    @Test
    fun `an older sample outside the window proves the window is covered`() {
        val runtime = runtime(at(30, ok = true), at(2, ok = true))
        val window = runtime.uptimeWithin(now, UptimeWindows.DAY_MS)!!
        assertTrue(window.complete)
    }

    @Test
    fun `history that starts inside the window is reported as incomplete`() {
        // Two hours old. It may honestly report two hours; it may not call that
        // a day's uptime.
        val runtime = runtime(at(2, ok = true), at(1, ok = true))
        val window = runtime.uptimeWithin(now, UptimeWindows.DAY_MS)!!
        assertFalse(window.complete)
        assertEquals(2 * hour, window.spanMs)
    }

    @Test
    fun `history reaching exactly to the window edge counts as complete`() {
        val runtime = runtime(at(24, ok = true), at(1, ok = true))
        assertTrue(runtime.uptimeWithin(now, UptimeWindows.DAY_MS)!!.complete)
    }

    @Test
    fun `a sample stamped in the future is ignored rather than trusted`() {
        // Wall clocks jump backwards; a check recorded "ahead" of now must not
        // land in the window and must not count as a reading.
        val ahead = Sample(at = now + 5 * hour, ok = false, latencyMs = 10)
        assertNull(runtime(ahead).uptimeWithin(now, UptimeWindows.DAY_MS))

        val mixed = runtime(ahead, at(3, ok = true))
        val window = mixed.uptimeWithin(now, UptimeWindows.DAY_MS)!!
        assertEquals(1, window.checks)
        assertEquals(100f, window.percent, 0.01f)
    }

    @Test
    fun `the buffer-wide figure is left alone for the callers that want it`() {
        // uptimePercent still means "of everything retained" — the alert and
        // repair paths compare against it and must not shift under them.
        val runtime = runtime(at(40, ok = false), at(2, ok = true))
        assertEquals(50f, runtime.uptimePercent, 0.01f)
    }
}

/**
 * The label inside the uptime ring.
 *
 * Reported from a device: a monitor checked once showed "PAST UNDER A MINU…",
 * truncated and sitting on top of the percentage above it. The string was
 * nineteen characters and the arc is about thirteen wide.
 */
class UptimeRingLabelTest {

    private fun window(
        spanMs: Long,
        checks: Int = 2,
        complete: Boolean = false,
        percent: Float = 100f,
    ) = UptimeWindow(
        percent = percent,
        checks = checks,
        spanMs = spanMs,
        complete = complete,
    )

    @Test
    fun `no history says so`() {
        assertEquals("no checks yet", UptimeWindows.ringLabel(null))
    }

    @Test
    fun `a full day is named as one`() {
        assertEquals(
            "24h uptime",
            UptimeWindows.ringLabel(window(DAY, complete = true)),
        )
    }

    @Test
    fun `under a minute of history reports the checks behind the figure`() {
        // The span is not the useful fact here, and "past under a minute" is both
        // longer than the ring and less informative than the count. A 100% that
        // rests on one check should say so.
        assertEquals("1 check", UptimeWindows.ringLabel(window(9_000, checks = 1)))
        assertEquals("3 checks", UptimeWindows.ringLabel(window(40_000, checks = 3)))
    }

    @Test
    fun `longer spans are named compactly`() {
        assertEquals("past 1m", UptimeWindows.ringLabel(window(60_000)))
        assertEquals("past 12m", UptimeWindows.ringLabel(window(12 * 60_000)))
        assertEquals("past 3h", UptimeWindows.ringLabel(window(3 * 3_600_000)))
        assertEquals("past 2d", UptimeWindows.ringLabel(window(2 * DAY)))
    }

    @Test
    fun `no label can outgrow the ring`() {
        // The invariant, rather than the six strings above. The ring is 124dp with
        // a 9dp stroke, which is about thirteen characters of labelSmall; anything
        // past that truncates, and before this was fixed it also collided with the
        // percentage. Swept across every span a real monitor can produce.
        val spans = listOf(0L, 1_000L, 59_999L, 60_000L, 90_000L, 3_599_999L, 3_600_000L) +
            listOf(5 * 3_600_000L, 23 * 3_600_000L, DAY, 9 * DAY, 400 * DAY)
        val labels = mutableListOf<String>()
        spans.forEach { span ->
            listOf(1, 2, 60, 300).forEach { checks ->
                listOf(true, false).forEach { complete ->
                    labels += UptimeWindows
                        .ringLabel(window(span, checks = checks, complete = complete))
                }
            }
        }
        labels += UptimeWindows.ringLabel(null)
        val tooLong = labels.filter { it.length > MAX_RING_LABEL }
        assertTrue("these do not fit the ring: ${tooLong.distinct()}", tooLong.isEmpty())
    }

    @Test
    fun `the short span never returns a phrase`() {
        // The UI's own formatSpan is allowed prose because it lands mid-sentence.
        // This one is read where width is fixed, so it must not.
        listOf(0L, 30_000L, 59_999L, 60_000L, 3_600_000L, DAY).forEach { ms ->
            val text = UptimeWindows.shortSpan(ms)
            assertTrue("\"$text\" is too wide", text.length <= 8)
            assertFalse("\"$text\" reads as prose", text.contains("minute"))
        }
    }

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000

        /** Characters of labelSmall that fit inside a 124dp ring. */
        const val MAX_RING_LABEL = 13
    }
}
