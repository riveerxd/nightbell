package me.river.nightbell

import me.river.nightbell.domain.Health
import me.river.nightbell.domain.LiveTimeline
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorRuntime
import me.river.nightbell.domain.Sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The strict-monitoring notice's ride-card line.
 *
 * The invariants worth pinning are the ones a screenshot cannot check. The
 * platform draws whatever segments it is handed, so nothing here catches a bar
 * that *looks* wrong — but a bar whose lengths do not sum to the length the
 * tracker's position assumes puts "now" somewhere other than now, and a merge
 * that drops an outage claims uptime that never happened.
 */
class LiveTimelineTest {

    private val now = 1_700_000_000_000L
    private val minute = 60_000L

    private fun monitor(id: String, intervalMinutes: Int = 5, enabled: Boolean = true) =
        Monitor(id = id, name = id, url = "https://$id.example", intervalMinutes = intervalMinutes, enabled = enabled)

    private fun samples(vararg minutesAgoToOk: Pair<Long, Boolean>) =
        MonitorRuntime(
            health = if (minutesAgoToOk.lastOrNull()?.second == false) Health.DOWN else Health.UP,
            samples = minutesAgoToOk.map { (minutesAgo, ok) ->
                Sample(at = now - minutesAgo * minute, ok = ok, latencyMs = 140)
            }.sortedBy { it.at },
        )

    /** A straight hour of passing checks, one every five minutes. */
    private fun healthyHour(ok: Boolean = true) =
        samples(*(0L..11L).map { (it * 5) to ok }.toTypedArray())

    @Test
    fun `no monitors at all draws nothing`() {
        assertNull(LiveTimeline.of(emptyList(), emptyMap(), now))
    }

    @Test
    fun `a monitor with no checks yet draws nothing`() {
        // An empty bar reads as "all clear across the whole span", which is a claim
        // this monitor has not earned. The caller keeps the plain notice.
        val monitors = listOf(monitor("a"))
        assertNull(LiveTimeline.of(monitors, mapOf("a" to MonitorRuntime()), now))
    }

    @Test
    fun `paused monitors do not contribute their history`() {
        val monitors = listOf(monitor("a", enabled = false))
        assertNull(LiveTimeline.of(monitors, mapOf("a" to healthyHour()), now))
    }

    @Test
    fun `samples stamped in the future are ignored`() {
        val monitors = listOf(monitor("a"))
        val future = MonitorRuntime(samples = listOf(Sample(at = now + 60 * minute, ok = true, latencyMs = 10)))
        assertNull(LiveTimeline.of(monitors, mapOf("a" to future), now))
    }

    @Test
    fun `the elapsed part is exactly as long as the tracker's position`() {
        // The one arithmetic error that cannot be seen in a screenshot: if the
        // segments before the tail do not sum to `progress`, the tracker is not
        // sitting on "now" and every outage on the line is drawn at the wrong time.
        val monitors = listOf(monitor("a"))
        val timeline = LiveTimeline.of(monitors, mapOf("a" to healthyHour()), now)!!
        val elapsed = timeline.bands.filter { it.tone != LiveTimeline.Tone.AHEAD }
        assertEquals(timeline.progress, elapsed.sumOf { it.length })
        assertEquals(LiveTimeline.BUCKETS, timeline.progress)
    }

    @Test
    fun `there is always a tail past the tracker, and it never dominates`() {
        val monitors = listOf(monitor("a", intervalMinutes = 600))
        val timeline = LiveTimeline.of(monitors, mapOf("a" to healthyHour()), now)!!
        val tail = timeline.bands.last()
        assertEquals(LiveTimeline.Tone.AHEAD, tail.tone)
        assertTrue("tail was ${tail.length}", tail.length in 1..(LiveTimeline.BUCKETS / 6))
        // Ten-hourly checks over an hour of history: the wait is longer than
        // everything known, and a bar that is mostly destination says nothing.
        assertTrue(tail.length < timeline.progress)
    }

    @Test
    fun `the tail counts down as the next check approaches`() {
        // Reported from a device: "the right grey line is not moving at all". It
        // could not — aheadLength returned the shortest interval in the fleet, a
        // constant that read neither the clock nor the last check. And because the
        // elapsed part is always exactly BUCKETS wide, a fixed tail pins the tracker
        // in place too.
        // Ten-minute checks over an hour of history. The span is 55 minutes, so a
        // bucket is a little over a minute — which is the resolution the countdown
        // has to fit inside for it to be visible at all. A ten-HOUR interval here
        // would pin both ends of the comparison to the BUCKETS/6 ceiling and prove
        // nothing.
        val monitors = listOf(monitor("a", intervalMinutes = 10))
        val justChecked = MonitorRuntime(
            health = Health.UP,
            lastCheckedAt = now,
            samples = healthyHour().samples,
        )
        val nearlyDue = justChecked.copy(lastCheckedAt = now - 8 * minute)

        val fresh = LiveTimeline.of(monitors, mapOf("a" to justChecked), now)!!
        val late = LiveTimeline.of(monitors, mapOf("a" to nearlyDue), now)!!

        assertTrue(
            "the tail must shrink as the check falls due: ${fresh.bands.last().length} " +
                "-> ${late.bands.last().length}",
            late.bands.last().length < fresh.bands.last().length,
        )
    }

    @Test
    fun `a monitor that is already due has the shortest possible tail`() {
        val monitors = listOf(monitor("a", intervalMinutes = 600))
        val overdue = MonitorRuntime(
            health = Health.UP,
            lastCheckedAt = now - 20 * 60 * minute,
            samples = healthyHour().samples,
        )
        val timeline = LiveTimeline.of(monitors, mapOf("a" to overdue), now)!!
        // Floored at one rather than zero: the platform draws a tracker sitting on the
        // extreme edge half outside the bar.
        assertEquals(1, timeline.bands.last().length)
    }

    @Test
    fun `a monitor that has never been checked is drawn as due now`() {
        val monitors = listOf(monitor("a", intervalMinutes = 600))
        val neverChecked = MonitorRuntime(
            health = Health.UP,
            lastCheckedAt = 0L,
            samples = healthyHour().samples,
        )
        val timeline = LiveTimeline.of(monitors, mapOf("a" to neverChecked), now)!!
        assertEquals(1, timeline.bands.last().length)
    }

    @Test
    fun `the tracker moves as the tail counts down`() {
        // The tracker's position is progress out of the whole bar, so a shrinking
        // tail is what makes it advance. Same history, different distance to the
        // next check.
        val monitors = listOf(monitor("a", intervalMinutes = 10))
        val base = MonitorRuntime(health = Health.UP, samples = healthyHour().samples)
        val fresh = LiveTimeline.of(monitors, mapOf("a" to base.copy(lastCheckedAt = now)), now)!!
        val late = LiveTimeline.of(
            monitors,
            mapOf("a" to base.copy(lastCheckedAt = now - 8 * minute)),
            now,
        )!!

        fun fraction(t: LiveTimeline.Timeline) = t.progress.toDouble() / t.max
        assertTrue(
            "the tracker must advance: ${fraction(fresh)} -> ${fraction(late)}",
            fraction(late) > fraction(fresh),
        )
    }

    @Test
    fun `compressing a flapping history never invents downtime`() {
        // Measured before the fix: 24 buckets genuinely failed, the line drew 40, and
        // the longest drawn run was 33 — a claimed 16.5-hour continuous outage on a
        // monitor that was never down for more than thirty minutes.
        val monitors = listOf(monitor("a", intervalMinutes = 30))
        val flapping = samples(*(0L..47L).map { (it * 30) to (it % 2 == 0L) }.toTypedArray())

        val timeline = LiveTimeline.of(monitors, mapOf("a" to flapping), now)!!

        val drawnDown = timeline.bands.filter { it.tone == LiveTimeline.Tone.DOWN }.sumOf { it.length }
        assertEquals("the drawn downtime must equal the real downtime", 24, drawnDown)
        assertTrue(
            "no single drawn outage may exceed the longest real one: ${timeline.bands}",
            timeline.bands.filter { it.tone == LiveTimeline.Tone.DOWN }.all { it.length <= 3 },
        )
        assertTrue("compression should still bound the band count", timeline.bands.size <= LiveTimeline.MAX_BANDS + 1)
    }

    @Test
    fun `compression preserves the total length the tracker depends on`() {
        val monitors = listOf(monitor("a", intervalMinutes = 30))
        val flapping = samples(*(0L..47L).map { (it * 30) to (it % 2 == 0L) }.toTypedArray())
        val timeline = LiveTimeline.of(monitors, mapOf("a" to flapping), now)!!
        val elapsed = timeline.bands.filter { it.tone != LiveTimeline.Tone.AHEAD }.sumOf { it.length }
        assertEquals(LiveTimeline.BUCKETS, elapsed)
        assertEquals(timeline.progress, elapsed)
    }

    @Test
    fun `a still-running outage is not erased by a healthier faster monitor`() {
        // Before the fix the carry-forward ran on the fleet-merged tone, so "b"'s
        // passing checks filled every bucket after "a"'s failure and the line drew
        // green to the right edge under a red tracker and a "1 DOWN" chip.
        val monitors = listOf(monitor("a", intervalMinutes = 60), monitor("b", intervalMinutes = 5))
        val downHourly = samples(180L to true, 120L to true, 60L to true, 45L to false)
        val healthyFast = samples(*(0L..47L).map { (it * 5) to true }.toTypedArray())

        val timeline = LiveTimeline.of(
            monitors,
            mapOf("a" to downHourly.copy(health = Health.DOWN), "b" to healthyFast),
            now,
        )!!

        assertEquals(
            "the line must still be down at the right edge: ${timeline.bands}",
            LiveTimeline.Tone.DOWN,
            timeline.bands.filter { it.tone != LiveTimeline.Tone.AHEAD }.last().tone,
        )
    }

    @Test
    fun `the tail empties across its budget as the check falls due`() {
        // The countdown is drawn as a fraction of one interval rather than to the
        // history's scale, because to scale it cannot move: on fifteen hours of
        // history a bucket is nearly nineteen minutes, so a fifteen-minute wait
        // floors to the same value full and empty alike.
        val monitors = listOf(monitor("a", intervalMinutes = 15))
        val history = samples(*(0L..60L).map { (it * 15) to true }.toTypedArray())
        fun tailAfter(minutesSinceCheck: Long): Int {
            val runtime = history.copy(lastCheckedAt = now - minutesSinceCheck * minute)
            return LiveTimeline.of(monitors, mapOf("a" to runtime), now)!!.bands.last().length
        }

        val justChecked = tailAfter(0)
        val halfway = tailAfter(7)
        val nearlyDue = tailAfter(14)

        assertEquals("a fresh check should fill the whole budget", LiveTimeline.BUCKETS / 6, justChecked)
        assertTrue("halfway should be about half: $halfway", halfway in 2..(justChecked - 2))
        assertTrue("nearly due should be near empty: $nearlyDue", nearlyDue < halfway)
    }

    @Test
    fun `the tracker advances measurably as the check falls due`() {
        val monitors = listOf(monitor("a", intervalMinutes = 15))
        val history = samples(*(0L..60L).map { (it * 15) to true }.toTypedArray())
        fun dotFraction(minutesSinceCheck: Long): Double {
            val runtime = history.copy(lastCheckedAt = now - minutesSinceCheck * minute)
            val t = LiveTimeline.of(monitors, mapOf("a" to runtime), now)!!
            return t.progress.toDouble() / t.max
        }
        // The reported symptom was 97.96% at every point in the cycle.
        assertTrue(
            "the tracker must move by a visible fraction of the bar: " +
                "${dotFraction(0)} -> ${dotFraction(14)}",
            dotFraction(14) - dotFraction(0) > 0.05,
        )
    }

    @Test
    fun `a staggered fleet still gets a countdown with a visible period`() {
        // Reported from a device with eight monitors: the tail never moved. It was
        // moving — "soonest across the whole fleet" resets every time any monitor
        // fires, roughly every two minutes with eight of them, so the value sat near
        // its floor and jittered. Pacing off one monitor gives a sweep you can see.
        val monitors = (1..8).map { monitor("m$it", intervalMinutes = 15) }
        val history = samples(*(0L..60L).map { (it * 15) to true }.toTypedArray())
        // Staggered, as a real fleet is: each monitor checked at a different offset.
        fun runtimesAt(pacerMinutesAgo: Long) = monitors.mapIndexed { index, m ->
            val since = if (index == 0) pacerMinutesAgo else (index * 2L) % 15
            m.id to history.copy(lastCheckedAt = now - since * minute)
        }.toMap()

        val fresh = LiveTimeline.of(monitors, runtimesAt(0), now)!!.bands.last().length
        val mid = LiveTimeline.of(monitors, runtimesAt(7), now)!!.bands.last().length
        val late = LiveTimeline.of(monitors, runtimesAt(14), now)!!.bands.last().length

        assertEquals("a just-checked pacer should fill the budget", LiveTimeline.BUCKETS / 6, fresh)
        assertTrue("mid-cycle should be roughly half: $mid", mid in 2..(fresh - 2))
        assertTrue("nearly due should be near empty: $late", late < mid)
    }

    @Test
    fun `the countdown label agrees with the tail it sits at the end of`() {
        val monitors = listOf(monitor("a", intervalMinutes = 15))
        val history = samples(*(0L..60L).map { (it * 15) to true }.toTypedArray())
        fun at(minutesSinceCheck: Long) =
            LiveTimeline.of(monitors, mapOf("a" to history.copy(lastCheckedAt = now - minutesSinceCheck * minute)), now)!!

        // Rounded up, so a check still pending never reads as zero.
        assertEquals("15m", at(0).countdownLabel)
        assertEquals("8m", at(7).countdownLabel)
        assertEquals("1m", at(14).countdownLabel)
        assertEquals("now", at(15).countdownLabel)

        // And the label shrinks in step with the grey it labels.
        assertTrue(
            "a wider tail must mean a longer countdown",
            at(0).bands.last().length > at(7).bands.last().length,
        )
        assertTrue(at(0).nextCheckInMs > at(7).nextCheckInMs)
    }

    @Test
    fun `an overdue or never-checked monitor reads as due now`() {
        val monitors = listOf(monitor("a", intervalMinutes = 15))
        val history = samples(*(0L..60L).map { (it * 15) to true }.toTypedArray())

        val overdue = LiveTimeline.of(monitors, mapOf("a" to history.copy(lastCheckedAt = now - 40 * minute)), now)!!
        assertEquals("now", overdue.countdownLabel)
        assertEquals(0L, overdue.nextCheckInMs)

        val never = LiveTimeline.of(monitors, mapOf("a" to history.copy(lastCheckedAt = 0L)), now)!!
        assertEquals("now", never.countdownLabel)
    }

    @Test
    fun `a long interval stays short enough to draw in an icon`() {
        // The label goes inside a notification icon slot, so it has a couple of
        // characters to work with. Nothing may render as "1h 23m" there.
        val history = samples(*(0L..60L).map { (it * 60) to true }.toTypedArray())
        for (interval in listOf(1, 5, 15, 60, 180, 720, 1440)) {
            val monitors = listOf(monitor("a", intervalMinutes = interval))
            val label = LiveTimeline.of(
                monitors,
                mapOf("a" to history.copy(lastCheckedAt = now)),
                now,
            )!!.countdownLabel
            assertTrue("\"$label\" is too long to draw at icon size", label.length <= 5)
            assertFalse("the label must not contain spaces: \"$label\"", label.contains(" "))
        }
    }

    @Test
    fun `the short span label fits an icon at every window size`() {
        // Drawn into the 20dp square at the start of the line, which crops to square:
        // past three characters it arrives unreadable.
        val monitors = listOf(monitor("a", intervalMinutes = 15))
        for (historyMinutes in listOf(5L, 45L, 90L, 600L, 1_439L)) {
            val history = samples(
                *(0L..(historyMinutes / 15)).map { (it * 15) to true }.toTypedArray(),
            )
            val timeline = LiveTimeline.of(monitors, mapOf("a" to history), now)!!
            val label = timeline.spanShortLabel
            assertTrue("\"$label\" is too long for the slot", label.length <= 3)
            assertFalse("the label must not contain spaces: \"$label\"", label.contains(" "))
        }
    }

    @Test
    fun `an unbroken healthy history is one green run`() {
        val monitors = listOf(monitor("a"))
        val timeline = LiveTimeline.of(monitors, mapOf("a" to healthyHour()), now)!!
        assertEquals(
            listOf(LiveTimeline.Tone.UP, LiveTimeline.Tone.AHEAD),
            timeline.bands.map { it.tone },
        )
        assertEquals(LiveTimeline.Tone.UP, timeline.current)
        assertEquals("1 up", timeline.chip)
    }

    @Test
    fun `an outage is a run as long as the outage was`() {
        // Twenty minutes down inside an hour: a third of the elapsed line, not a
        // dot. Same decision as the dashboard sparkline in 1.2.0.
        val history = samples(
            60L to true, 55L to true, 50L to true, 45L to true, 40L to true,
            35L to false, 30L to false, 25L to false, 20L to false,
            15L to true, 10L to true, 5L to true, 0L to true,
        )
        val monitors = listOf(monitor("a"))
        val timeline = LiveTimeline.of(monitors, mapOf("a" to history), now)!!
        val down = timeline.bands.filter { it.tone == LiveTimeline.Tone.DOWN }
        assertEquals(1, down.size)
        val elapsed = timeline.progress.toFloat()
        val share = down.single().length / elapsed
        assertTrue("outage took $share of the line", share in 0.20f..0.45f)
    }

    @Test
    fun `gaps between checks inherit the last known outcome rather than reading unknown`() {
        // Hourly checks over a day: most of the 48 buckets hold nothing at all.
        // Left as UNKNOWN the line came out speckled grey; a monitor's health
        // persists between checks, which is what the Health field already means.
        val hourly = samples(*(0L..23L).map { (it * 60) to true }.toTypedArray())
        val monitors = listOf(monitor("a", intervalMinutes = 60))
        val timeline = LiveTimeline.of(monitors, mapOf("a" to hourly), now)!!
        assertTrue(
            "tones were ${timeline.bands.map { it.tone }}",
            timeline.bands.none { it.tone == LiveTimeline.Tone.UNKNOWN },
        )
    }

    @Test
    fun `one monitor failing beats however many passed in the same slice of time`() {
        val monitors = listOf(monitor("a"), monitor("b"), monitor("c"))
        val runtimes = mapOf(
            "a" to healthyHour(),
            "b" to healthyHour(),
            "c" to samples(60L to true, 30L to false, 0L to false),
        )
        val timeline = LiveTimeline.of(monitors, runtimes, now)!!
        assertTrue(timeline.bands.any { it.tone == LiveTimeline.Tone.DOWN })
        assertEquals(LiveTimeline.Tone.DOWN, timeline.current)
        assertEquals("1 DOWN", timeline.chip)
    }

    @Test
    fun `a flapping monitor is merged down to a readable number of runs`() {
        val flapping = samples(
            *(0L..59L).map { (it * 2) to (it % 2 == 0L) }.toTypedArray(),
        )
        val monitors = listOf(monitor("a", intervalMinutes = 2))
        val timeline = LiveTimeline.of(monitors, mapOf("a" to flapping), now)!!
        val elapsed = timeline.bands.filter { it.tone != LiveTimeline.Tone.AHEAD }
        assertTrue("got ${elapsed.size} bands", elapsed.size <= LiveTimeline.MAX_BANDS)
        // Merging must not invent uptime: the failures are still on the line, and
        // the elapsed length is untouched by however much merging happened.
        assertTrue(elapsed.any { it.tone == LiveTimeline.Tone.DOWN })
        assertEquals(LiveTimeline.BUCKETS, elapsed.sumOf { it.length })
    }

    @Test
    fun `merging never absorbs an outage`() {
        // Alternating single-bucket outages, more of them than MAX_BANDS allows.
        // The cap has to give up rather than quietly report a clean line.
        val alternating = samples(
            *(0L..47L).map { (it * 30) to (it % 2 == 0L) }.toTypedArray(),
        )
        val monitors = listOf(monitor("a", intervalMinutes = 30))
        val timeline = LiveTimeline.of(monitors, mapOf("a" to alternating), now)!!
        val elapsed = timeline.bands.filter { it.tone != LiveTimeline.Tone.AHEAD }
        val downBuckets = elapsed.filter { it.tone == LiveTimeline.Tone.DOWN }.sumOf { it.length }
        assertTrue("no outage survived", downBuckets > 0)
        assertEquals(LiveTimeline.BUCKETS, elapsed.sumOf { it.length })
    }

    @Test
    fun `each outage gets a marker, capped and newest-first`() {
        val many = samples(
            *(0L..47L).map { (it * 30) to (it % 4 != 0L) }.toTypedArray(),
        )
        val monitors = listOf(monitor("a", intervalMinutes = 30))
        val timeline = LiveTimeline.of(monitors, mapOf("a" to many), now)!!
        assertTrue(timeline.markers.size <= LiveTimeline.MAX_MARKERS)
        assertTrue(timeline.markers.all { it.position <= timeline.progress })
        assertEquals(timeline.markers.map { it.position }.sorted(), timeline.markers.map { it.position })
    }

    @Test
    fun `a fresh install does not draw a ninety-second window`() {
        val monitors = listOf(monitor("a", intervalMinutes = 1))
        val timeline = LiveTimeline.of(monitors, mapOf("a" to samples(1L to true, 0L to true)), now)!!
        assertEquals(LiveTimeline.MIN_SPAN_MS, timeline.spanMs)
    }

    @Test
    fun `a check older than the ceiling does not leave the line grey at the left edge`() {
        // Reported from a device: half the bar was empty grey under a "last 24h"
        // label. One retained check older than MAX_SPAN_MS was measuring the
        // window, and was then dropped for being before `windowStart` — so the
        // line was stretched to a full day by a sample it could not draw, and the
        // buckets before the first drawable check had nothing in them. Carrying
        // forward cannot fill those: it propagates left to right, and there is no
        // earlier bucket to inherit from.
        val monitors = listOf(monitor("a", intervalMinutes = 15))
        val recent = (0L..56L).map { (it * 15) to true }
        val history = samples(*(listOf(1_800L to true) + recent).toTypedArray())

        val timeline = LiveTimeline.of(monitors, mapOf("a" to history), now)!!

        assertTrue(
            "the line must not open with a run of unknown: ${timeline.bands}",
            timeline.bands.first().tone != LiveTimeline.Tone.UNKNOWN,
        )
        assertTrue(
            "no bucket before the tracker may be unknown: ${timeline.bands}",
            timeline.bands.none { it.tone == LiveTimeline.Tone.UNKNOWN },
        )
    }

    @Test
    fun `the window is measured from the oldest check the line can actually draw`() {
        // The label has to describe the drawn line. Fourteen hours of checks plus
        // one thirty-hour-old straggler is a fourteen-hour line, not a day-long one
        // that happens to be two-thirds empty.
        val monitors = listOf(monitor("a", intervalMinutes = 15))
        val recent = (0L..56L).map { (it * 15) to true }
        val history = samples(*(listOf(1_800L to true) + recent).toTypedArray())

        val timeline = LiveTimeline.of(monitors, mapOf("a" to history), now)!!

        assertEquals(14 * 60 * minute, timeline.spanMs)
        assertEquals("14h", timeline.spanLabel)
    }

    @Test
    fun `a brand new fleet is mostly unknown, and says so`() {
        // Pins a known limit rather than a fixed bug. MIN_SPAN_MS floors the window
        // at ten minutes so the axis stops relabelling itself on every check, which
        // means a fleet two minutes old is drawn on a ten-minute axis with eight
        // minutes of nothing in it. Grey there is true — nothing was checked — and it
        // clears itself within ten minutes of install.
        //
        // Here so that the leading-grey fix above is not mistaken for a guarantee
        // that UNKNOWN can never appear: it cannot appear above the floor, and it
        // certainly can below it.
        val monitors = listOf(monitor("a", intervalMinutes = 1))
        val timeline = LiveTimeline.of(monitors, mapOf("a" to samples(2L to true, 0L to true)), now)!!

        assertEquals(LiveTimeline.MIN_SPAN_MS, timeline.spanMs)
        assertEquals(LiveTimeline.Tone.UNKNOWN, timeline.bands.first().tone)
        assertTrue(
            "the unknown run should be the leading one only: ${timeline.bands}",
            timeline.bands.drop(1).none { it.tone == LiveTimeline.Tone.UNKNOWN },
        )
    }

    @Test
    fun `history older than a day does not stretch the window past one`() {
        val monitors = listOf(monitor("a", intervalMinutes = 60))
        val ancient = samples(*(0L..40L).map { (it * 120) to true }.toTypedArray())
        val timeline = LiveTimeline.of(monitors, mapOf("a" to ancient), now)!!
        assertEquals(LiveTimeline.MAX_SPAN_MS, timeline.spanMs)
    }

    @Test
    fun `offline is not an outage on the line or in the chip`() {
        // 1.3.0 stopped treating lost signal as an outage; the tracker must not
        // re-introduce it as one.
        val monitors = listOf(monitor("a"))
        val timeline = LiveTimeline.of(monitors, mapOf("a" to healthyHour()), now, offline = true)!!
        assertEquals(LiveTimeline.Tone.UNKNOWN, timeline.current)
        assertEquals("offline", timeline.chip)
        assertTrue(timeline.bands.any { it.tone == LiveTimeline.Tone.UP })
    }

    @Test
    fun `the chip stays short enough for a status bar`() {
        val monitors = (1..12).map { monitor("m$it") }
        val runtimes = monitors.associate { it.id to healthyHour() }
        val timeline = LiveTimeline.of(monitors, runtimes, now)!!
        assertEquals("12 up", timeline.chip)
        assertTrue(timeline.chip.length <= 9)
    }

    @Test
    fun `an unacknowledged urgent outage outranks the down count in the chip`() {
        val monitors = listOf(monitor("a").copy(urgent = true))
        val runtime = healthyHour(ok = false).copy(
            health = Health.DOWN,
            urgentActive = true,
            urgentAcknowledged = false,
        )
        val timeline = LiveTimeline.of(monitors, mapOf("a" to runtime), now)!!
        assertEquals("1 URGENT", timeline.chip)
        assertNotNull(timeline.markers.firstOrNull())
    }

    @Test
    fun `the span label says what the line covers`() {
        val monitors = listOf(monitor("a", intervalMinutes = 60))
        val sixHours = samples(*(0L..11L).map { (it * 30) to true }.toTypedArray())
        assertEquals("5h 30m", LiveTimeline.of(monitors, mapOf("a" to sixHours), now)!!.spanLabel)

        val halfHour = samples(30L to true, 0L to true)
        assertEquals("30m", LiveTimeline.of(monitors, mapOf("a" to halfHour), now)!!.spanLabel)

        val exactlyADay = samples(*(0L..24L).map { (it * 60) to true }.toTypedArray())
        assertEquals("24h", LiveTimeline.of(monitors, mapOf("a" to exactlyADay), now)!!.spanLabel)
    }
}
