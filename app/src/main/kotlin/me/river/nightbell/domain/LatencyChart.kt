package me.river.nightbell.domain

import kotlin.math.roundToLong

/**
 * Where the latency budget line sits on the response-time bar chart.
 *
 * Pure, and separate from the drawing for the usual reason: the arithmetic that
 * decides where a line lands is the part that can be wrong in a way nobody
 * notices, and a Canvas cannot be read by a JVM test. The composable in
 * `ui/components/Status.kt` asks these three questions and draws the answers.
 *
 * The problem this solves is that the chart normalises to its own tallest sample.
 * That is right for bars, since a chart of latencies should use its full height,
 * and it means a budget can easily fall outside the range being drawn. Three
 * things could happen at that point and only one of them is honest:
 *
 *  - Scale to the budget whenever it is taller. Correct, and useless: a 30s budget
 *    over 200ms responses squashes every bar onto the floor, which is the
 *    *ordinary* case for someone who set a generous budget rather than a corner
 *    case.
 *  - Leave the scale alone and clip. The line then vanishes exactly when the user
 *    most wants to see it, which is when everything is comfortably inside budget.
 *  - Let the budget claim a bounded amount of extra headroom, and when it wants
 *    more than that, pin it to the top and *say so*. That is [HEADROOM].
 *
 * The third one is this. A capped line is drawn differently from a placed one and
 * the legend prints the real figure either way, so the chart never claims the
 * budget is somewhere it is not.
 */
object LatencyChart {

    /**
     * How much taller than the worst sample the budget may push the scale.
     *
     * 1.3 rather than something rounder because it has to be big enough that a
     * budget sitting just above a spike gets drawn where it really is, and small
     * enough that a budget nobody is anywhere near cannot flatten the bars. At
     * this value a chart whose worst sample is right on the budget loses nothing,
     * and one whose budget is double the worst sample keeps its bars at
     * three-quarters height.
     */
    const val HEADROOM = 1.3

    /**
     * The latency the top of the chart represents.
     *
     * Never zero, so callers can divide by it without checking.
     */
    fun scaleMax(samples: List<Sample>, sloMs: Int): Long {
        val tallest = tallest(samples)
        if (sloMs <= 0) return tallest
        val allowed = (tallest * HEADROOM).roundToLong()
        return maxOf(tallest, minOf(sloMs.toLong(), allowed))
    }

    /**
     * Height of the budget line as a fraction of the chart, measured from the
     * bottom. 0 when there is no budget to draw.
     */
    fun budgetFraction(samples: List<Sample>, sloMs: Int): Float {
        if (sloMs <= 0) return 0f
        return (sloMs.toFloat() / scaleMax(samples, sloMs)).coerceIn(0f, 1f)
    }

    /**
     * Whether the budget is further above the samples than the chart can show, so
     * the line is pinned to the top edge rather than drawn where it belongs.
     *
     * The caller uses this to draw the pinned line differently. A line that means
     * "the budget is at least this high" must not look identical to one that means
     * "the budget is exactly here".
     */
    fun budgetIsCapped(samples: List<Sample>, sloMs: Int): Boolean {
        if (sloMs <= 0) return false
        return sloMs > (tallest(samples) * HEADROOM).roundToLong()
    }

    /**
     * How many checks in [samples] answered, and answered slower than the budget.
     *
     * A failed check is not over budget, it is failed, and counting it as both
     * would double-report the same bad minute. [AlertDecider.isDegraded] is the
     * comparison so this cannot drift from what the alert track calls degraded.
     */
    fun overBudget(samples: List<Sample>, sloMs: Int): Int =
        samples.count { isOverBudget(it, sloMs) }

    /** Whether one sample succeeded but missed the budget. */
    fun isOverBudget(sample: Sample, sloMs: Int): Boolean =
        AlertDecider.isDegraded(sample.ok, sample.latencyMs, sloMs.toLong())

    private fun tallest(samples: List<Sample>): Long =
        maxOf(1L, samples.maxOfOrNull { it.latencyMs } ?: 1L)
}
