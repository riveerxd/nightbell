package me.river.nightbell

import me.river.nightbell.domain.LatencyChart
import me.river.nightbell.domain.Sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatencyChartTest {

    private var clock = 1_700_000_000_000L

    /** One sample per call, an arbitrary minute apart. Only latency matters here. */
    private fun sample(latencyMs: Long, ok: Boolean = true): Sample {
        clock += 60_000L
        return Sample(at = clock, ok = ok, latencyMs = latencyMs)
    }

    private fun samples(vararg latencies: Long) = latencies.map { sample(it) }

    @Test
    fun `with no budget the chart scales to its tallest sample`() {
        val list = samples(100, 400, 250)
        assertEquals(400L, LatencyChart.scaleMax(list, sloMs = 0))
        assertEquals(0f, LatencyChart.budgetFraction(list, sloMs = 0), 0f)
        assertFalse(LatencyChart.budgetIsCapped(list, sloMs = 0))
    }

    @Test
    fun `a budget under the worst sample does not move the scale`() {
        val list = samples(100, 400, 250)
        assertEquals(400L, LatencyChart.scaleMax(list, sloMs = 300))
        assertEquals(0.75f, LatencyChart.budgetFraction(list, sloMs = 300), 0.0001f)
        assertFalse(LatencyChart.budgetIsCapped(list, sloMs = 300))
    }

    @Test
    fun `a budget just above the worst sample raises the scale to itself`() {
        val list = samples(100, 400, 250)
        // 480 is inside the 1.3x headroom of 400, so the line is drawn where it
        // really is and the tallest bar gives up a little height for it.
        assertEquals(480L, LatencyChart.scaleMax(list, sloMs = 480))
        assertEquals(1f, LatencyChart.budgetFraction(list, sloMs = 480), 0.0001f)
        assertFalse(LatencyChart.budgetIsCapped(list, sloMs = 480))
    }

    @Test
    fun `the exact headroom boundary is still drawn where it belongs`() {
        val list = samples(400)
        assertEquals(520L, LatencyChart.scaleMax(list, sloMs = 520))
        assertFalse(LatencyChart.budgetIsCapped(list, sloMs = 520))

        // One millisecond past it, and the line can no longer be placed honestly.
        assertTrue(LatencyChart.budgetIsCapped(list, sloMs = 521))
    }

    @Test
    fun `a budget nobody is near is capped instead of flattening the bars`() {
        val list = samples(180, 210, 200)
        val slo = 30_000

        assertTrue(LatencyChart.budgetIsCapped(list, slo))
        // Without the cap the scale would be 30000 and the tallest bar would draw
        // at 0.7% of the height. With it, the bars keep three-quarters of the
        // chart and the line goes to the top edge.
        assertEquals(273L, LatencyChart.scaleMax(list, slo))
        assertEquals(1f, LatencyChart.budgetFraction(list, slo), 0.0001f)
        assertEquals(
            0.769f,
            210f / LatencyChart.scaleMax(list, slo),
            0.001f,
        )
    }

    @Test
    fun `the reported case draws without distortion`() {
        // From issue 5: a 5300ms budget under a p95 of 5.68s. The point of the
        // headroom rule is that this, the case the feature is for, is untouched
        // by it.
        val list = samples(3_770, 4_250, 5_680)
        assertEquals(5_680L, LatencyChart.scaleMax(list, sloMs = 5_300))
        assertFalse(LatencyChart.budgetIsCapped(list, sloMs = 5_300))
        assertEquals(0.933f, LatencyChart.budgetFraction(list, sloMs = 5_300), 0.001f)
    }

    @Test
    fun `only successful checks count as over budget`() {
        val list = listOf(
            sample(1_000),
            sample(3_000),
            sample(9_000, ok = false),
            sample(2_500),
        )
        // The 9s failure is a failure, not a slow success, and is already drawn in
        // rose. Counting it here would report the same bad minute twice.
        assertEquals(2, LatencyChart.overBudget(list, sloMs = 2_000))
        assertFalse(LatencyChart.isOverBudget(list[2], sloMs = 2_000))
        assertTrue(LatencyChart.isOverBudget(list[1], sloMs = 2_000))
    }

    @Test
    fun `a sample exactly on the budget is inside it`() {
        val list = samples(2_000)
        assertEquals(0, LatencyChart.overBudget(list, sloMs = 2_000))
        assertEquals(1, LatencyChart.overBudget(samples(2_001), sloMs = 2_000))
    }

    @Test
    fun `no budget means nothing is over budget`() {
        val list = samples(1_000, 60_000)
        assertEquals(0, LatencyChart.overBudget(list, sloMs = 0))
    }

    @Test
    fun `an empty chart has a usable scale`() {
        // Nothing draws this, but a divide-by-zero here would be a crash on a
        // monitor that has never been checked, so it is worth holding.
        assertEquals(1L, LatencyChart.scaleMax(emptyList(), sloMs = 0))
        assertEquals(1L, LatencyChart.scaleMax(emptyList(), sloMs = 0).coerceAtLeast(1L))
        assertEquals(0, LatencyChart.overBudget(emptyList(), sloMs = 500))
        assertTrue(LatencyChart.budgetIsCapped(emptyList(), sloMs = 500))
    }

    @Test
    fun `a single sample below its budget still scales sanely`() {
        val list = samples(120)
        assertEquals(156L, LatencyChart.scaleMax(list, sloMs = 5_000))
        assertTrue(LatencyChart.budgetIsCapped(list, sloMs = 5_000))
    }

    @Test
    fun `all-failed samples do not divide by zero`() {
        // A monitor that has never once answered records zero-latency failures.
        val list = listOf(sample(0, ok = false), sample(0, ok = false))
        assertEquals(1L, LatencyChart.scaleMax(list, sloMs = 0))
        assertEquals(0, LatencyChart.overBudget(list, sloMs = 1_000))
    }

    @Test
    fun `a negative budget is treated as no budget`() {
        // Validation refuses one, but sloMs arrives from two places and a stored
        // snapshot from an older build is not something this can assume about.
        val list = samples(100, 200)
        assertEquals(200L, LatencyChart.scaleMax(list, sloMs = -5))
        assertEquals(0f, LatencyChart.budgetFraction(list, sloMs = -5), 0f)
        assertEquals(0, LatencyChart.overBudget(list, sloMs = -5))
    }
}
