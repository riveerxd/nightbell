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
    fun isDue(intervalMinutes: Int, lastCheckedAt: Long, nowMs: Long): Boolean {
        if (lastCheckedAt <= 0L) return true
        val since = nowMs - lastCheckedAt
        // A negative age means the wall clock moved *backwards* — someone changed
        // the date, or time sync corrected an RTC that had been running fast.
        // `lastCheckedAt` is wall-clock, so waiting for the clock to catch up would
        // silence every monitor for the whole length of the jump, and an outage in
        // that window would never alert. A stamp in the future is due now; the
        // check that follows re-bases it.
        if (since < 0L) return true
        return since >= intervalMinutes.coerceAtLeast(1) * 60_000L - SLACK_MS
    }
}
