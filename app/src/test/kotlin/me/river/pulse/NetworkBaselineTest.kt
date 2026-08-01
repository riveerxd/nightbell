package me.river.pulse

import me.river.pulse.domain.NetworkBaseline
import me.river.pulse.domain.ReferenceSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The maths that separates "the server is slow" from "this connection is slow".
 *
 * Every case here is written from the direction of the bug that motivated it:
 * a phone on bad wifi reported every monitor as slow at once, and all of those
 * alerts were wrong. The counter-requirement is just as important — a genuinely
 * slow server must still be reported while the connection is merely mediocre.
 */
class NetworkBaselineTest {

    private val now = 1_700_000_000_000L

    /** Readings spaced a minute apart, oldest first, all recent. */
    private fun window(vararg rtts: Long): List<ReferenceSample> =
        rtts.mapIndexed { index, rtt ->
            ReferenceSample(at = now - (rtts.size - index) * 60_000L, rttMs = rtt)
        }

    // ------------------------------------------------------------------ no data

    @Test
    fun `with too few readings the raw latency stands`() {
        val verdict = NetworkBaseline.judge(3_000, window(40, 42, 41), now)

        assertEquals(NetworkBaseline.Trust.UNKNOWN, verdict.trust)
        assertEquals("nothing should be subtracted", 3_000L, verdict.adjustedMs)
        assertFalse(verdict.unreliable)
    }

    @Test
    fun `an empty window behaves exactly as before the feature existed`() {
        val verdict = NetworkBaseline.judge(9_999, emptyList(), now)

        assertEquals(NetworkBaseline.Trust.UNKNOWN, verdict.trust)
        assertEquals(9_999L, verdict.adjustedMs)
    }

    /** A blocked reference is the common case on corporate wifi. */
    @Test
    fun `readings that never succeeded are not evidence of a slow link`() {
        val blocked = listOf(0L, 0L, 0L, 0L, 0L, 0L).mapIndexed { i, rtt ->
            ReferenceSample(at = now - i * 60_000L, rttMs = rtt)
        }

        val verdict = NetworkBaseline.judge(4_000, blocked, now)

        assertEquals(NetworkBaseline.Trust.UNKNOWN, verdict.trust)
        assertEquals(4_000L, verdict.adjustedMs)
    }

    @Test
    fun `stale readings are ignored so a change of network does not linger`() {
        val old = listOf(30L, 31L, 29L, 30L, 32L, 30L).mapIndexed { i, rtt ->
            ReferenceSample(at = now - NetworkBaseline.MAX_AGE_MS - (i + 1) * 60_000L, rttMs = rtt)
        }

        val verdict = NetworkBaseline.judge(2_000, old, now)

        assertEquals(NetworkBaseline.Trust.UNKNOWN, verdict.trust)
    }

    // ------------------------------------------------------------- healthy link

    @Test
    fun `a steady reference leaves the measurement alone`() {
        val verdict = NetworkBaseline.judge(3_000, window(40, 45, 38, 42, 41, 39), now)

        assertEquals(NetworkBaseline.Trust.CLEAR, verdict.trust)
        assertEquals(3_000L, verdict.adjustedMs)
        assertEquals(0L, verdict.excessMs)
        assertFalse(verdict.compensated)
    }

    @Test
    fun `jitter under the noise floor is not treated as network cost`() {
        // Floor lands near 40, current near 70 — real, but only 30 ms of it.
        val verdict = NetworkBaseline.judge(3_000, window(40, 38, 42, 41, 70, 68, 70), now)

        assertEquals(NetworkBaseline.Trust.CLEAR, verdict.trust)
        assertEquals("noise must not be subtracted", 3_000L, verdict.adjustedMs)
    }

    // ------------------------------------------------------------ degraded link

    @Test
    fun `the reference's excess is subtracted from the measurement`() {
        // Sorted: 38 39 40 41 42 440 440 450. The floor is the 25th percentile,
        // so sorted[1] = 39; the current reading is the median of the last three,
        // so 440. That leaves 401 ms of excess to come off the measurement.
        val readings = window(40, 42, 38, 41, 39, 440, 450, 440)

        val verdict = NetworkBaseline.judge(900, readings, now)

        assertEquals(NetworkBaseline.Trust.ADJUSTED, verdict.trust)
        assertTrue("should have found the connection at fault", verdict.compensated)
        assertEquals(39L, verdict.floorMs)
        assertEquals(440L, verdict.referenceMs)
        assertEquals(401L, verdict.excessMs)
        assertEquals(499L, verdict.adjustedMs)
    }

    /** The exact scenario reported: everything looks slow, nothing actually is. */
    @Test
    fun `a monitor that only breached because of the link falls back under its SLO`() {
        val readings = window(45, 40, 44, 42, 41, 1_400, 1_500, 1_450)
        val slo = 2_500L

        val verdict = NetworkBaseline.judge(observedMs = 3_200, readings = readings, nowMs = now)

        assertTrue("raw reading breached", 3_200 > slo)
        assertTrue("adjusted reading should not", verdict.adjustedMs < slo)
    }

    /** The counter-requirement: real slowness survives a mediocre link. */
    @Test
    fun `a genuinely slow server is still reported through a mediocre link`() {
        val readings = window(45, 40, 44, 42, 41, 340, 350, 345)
        val slo = 2_500L

        val verdict = NetworkBaseline.judge(observedMs = 6_000, readings = readings, nowMs = now)

        assertEquals(NetworkBaseline.Trust.ADJUSTED, verdict.trust)
        assertTrue(
            "6s is slow even after discounting 300ms of network",
            verdict.adjustedMs > slo,
        )
    }

    @Test
    fun `adjustment never goes below zero`() {
        val readings = window(40, 41, 39, 42, 2_000, 2_100, 2_050)

        val verdict = NetworkBaseline.judge(observedMs = 120, readings = readings, nowMs = now)

        assertTrue(verdict.adjustedMs >= 0)
    }

    // ------------------------------------------------------------ broken link

    @Test
    fun `a reference far off its floor makes the measurement unreliable`() {
        // 40 ms floor, ~3 s now: both the absolute and the relative test trip.
        val readings = window(40, 42, 38, 41, 3_000, 3_100, 3_050)

        val verdict = NetworkBaseline.judge(8_000, readings, now)

        assertEquals(NetworkBaseline.Trust.UNRELIABLE, verdict.trust)
        assertTrue(verdict.unreliable)
    }

    @Test
    fun `a big absolute jump on an already slow link is not written off`() {
        // Satellite-ish: 900 ms floor, 1.6 s now. 700 ms of excess, but under 4x,
        // so it is compensated rather than declared meaningless.
        val readings = window(900, 920, 880, 910, 1_600, 1_650, 1_600)

        val verdict = NetworkBaseline.judge(4_000, readings, now)

        assertEquals(NetworkBaseline.Trust.ADJUSTED, verdict.trust)
        assertFalse(verdict.unreliable)
    }

    @Test
    fun `a large relative jump on a fast link is not written off on its own`() {
        // 10x, but only 180 ms — nowhere near enough to distrust everything.
        val readings = window(20, 22, 18, 21, 200, 210, 200)

        val verdict = NetworkBaseline.judge(3_000, readings, now)

        assertEquals(NetworkBaseline.Trust.ADJUSTED, verdict.trust)
    }

    // ---------------------------------------------------------------- estimators

    @Test
    fun `the floor resists a window that has mostly gone bad`() {
        // Two good readings, six bad. A median would call 800 normal and stop
        // compensating exactly when it matters.
        val readings = listOf(40L, 45L, 800L, 820L, 810L, 830L, 800L, 815L)

        assertTrue(
            "floor should stay near the good readings, was ${NetworkBaseline.floorOf(readings)}",
            NetworkBaseline.floorOf(readings) < 100,
        )
    }

    @Test
    fun `the floor is not dragged down by one lucky round trip`() {
        val readings = listOf(5L, 300L, 310L, 305L, 295L, 300L, 315L, 300L)

        assertTrue(
            "a single 5ms outlier must not become the floor, was ${NetworkBaseline.floorOf(readings)}",
            NetworkBaseline.floorOf(readings) > 100,
        )
    }

    @Test
    fun `one unlucky round trip cannot swing the current reading`() {
        val steady = window(40, 41, 39, 40, 42, 40, 41)
        val withSpike = window(40, 41, 39, 40, 42, 40, 9_000)

        // Median of the last three absorbs it; a mean or a last-value would not.
        assertEquals(
            NetworkBaseline.Trust.CLEAR,
            NetworkBaseline.judge(3_000, steady, now).trust,
        )
        assertEquals(
            "a lone spike must not wipe out a real degradation",
            NetworkBaseline.Trust.CLEAR,
            NetworkBaseline.judge(3_000, withSpike, now).trust,
        )
    }

    // -------------------------------------------------------------------- window

    @Test
    fun `recording keeps the window bounded and drops stale entries`() {
        val stale = ReferenceSample(at = now - NetworkBaseline.MAX_AGE_MS - 1, rttMs = 99)
        var readings = listOf(stale)
        repeat(NetworkBaseline.WINDOW + 10) { readings = NetworkBaseline.record(readings, 50, now) }

        assertEquals(NetworkBaseline.WINDOW, readings.size)
        assertTrue("stale entry should be gone", readings.none { it.rttMs == 99L })
    }

    @Test
    fun `a failed probe adds nothing but does not discard history`() {
        val before = window(40, 41, 42, 43)

        val after = NetworkBaseline.record(before, null, now)

        assertEquals(before.size, after.size)
    }

    @Test
    fun `probing is rate limited but always happens on an empty window`() {
        assertTrue(NetworkBaseline.needsProbe(emptyList(), now, 45_000))
        assertFalse(
            NetworkBaseline.needsProbe(listOf(ReferenceSample(at = now - 1_000, rttMs = 40)), now, 45_000),
        )
        assertTrue(
            NetworkBaseline.needsProbe(listOf(ReferenceSample(at = now - 60_000, rttMs = 40)), now, 45_000),
        )
    }
}
