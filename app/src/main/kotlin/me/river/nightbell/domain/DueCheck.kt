package me.river.nightbell.domain

/**
 * Whether a monitor's interval has elapsed.
 *
 * Its own object, free of Android, because three callers have to agree about it
 * exactly and getting it wrong in either direction is a real bug:
 *
 *  - too permissive and a monitor is checked several times within seconds. The
 *    WorkManager worker and the 15-minute repair sweep routinely share one
 *    wake-up, and on a real device that produced three samples one second apart.
 *  - too strict and a monitor is never checked at all. There is no unconditional
 *    check path left in the background, so anything that makes this return `false`
 *    forever is silent non-monitoring — the worst failure this app has.
 */
object DueCheck {

    /**
     * Slack, so a check that lands a few seconds early is not deferred a whole
     * interval. WorkManager's own scheduling jitter is comfortably inside this.
     */
    const val SLACK_MS = 30_000L

    /**
     * @param lastCheckedAt wall-clock millis of the last completed check; 0 means
     *   never checked, which is always due.
     */
    fun isDue(intervalMinutes: Int, lastCheckedAt: Long, nowMs: Long): Boolean =
        isDueMillis(intervalMinutes.coerceAtLeast(1) * 60_000L, lastCheckedAt, nowMs)

    /**
     * Slack, scaled to the interval. The flat 30 seconds is right for a
     * quarter-hour cadence but nonsense for a one-second one: subtract 30 seconds
     * from a 1,000ms interval and the monitor is permanently due, so the loop
     * would check it on every single tick no matter what the user chose. Half the
     * interval is the most a check may land early, and for anything a minute or
     * longer this is still the original 30 seconds.
     */
    fun slackFor(intervalMs: Long): Long = minOf(SLACK_MS, intervalMs / 2L)

    /** The millisecond form, shared by minute and second cadences. */
    fun isDueMillis(intervalMs: Long, lastCheckedAt: Long, nowMs: Long): Boolean {
        if (lastCheckedAt <= 0L) return true
        val since = nowMs - lastCheckedAt
        // A negative age means the wall clock moved *backwards* — someone changed
        // the date, or time sync corrected an RTC that had been running fast.
        // `lastCheckedAt` is wall-clock, so waiting for the clock to catch up would
        // silence every monitor for the whole length of the jump, and an outage in
        // that window would never alert. A stamp in the future is due now; the
        // check that follows re-bases it.
        if (since < 0L) return true
        val interval = intervalMs.coerceAtLeast(1_000L)
        return since >= interval - slackFor(interval)
    }
}
