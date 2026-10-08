package me.river.nightbell.domain

/**
 * Re-checking a failing monitor straight away instead of on its schedule.
 *
 * A failure threshold of three used to mean three *scheduled* checks, and in the
 * background the schedule is WorkManager's fifteen-minute floor, so a site that
 * went down was paged about forty-five minutes later at best. Issue 20. The
 * threshold exists to ride out blips, and a blip is over in seconds, not in half
 * an hour, so the failures that confirm an outage are now taken [GAP_MS] apart.
 *
 * Pure, because three callers have to agree about it exactly: the due check, the
 * strict service's sleep and the loop that background work runs before it lets
 * the phone go back to sleep.
 */
object QuickRetry {

    /**
     * Between two confirming checks.
     *
     * Not zero. A retry the instant after a failure mostly sees the same dropped
     * packet or the same restarting process again, and filtering those is the only
     * reason anyone raises the threshold. Thirty seconds outlasts most of them and
     * still pages a threshold of three about a minute after the first failure.
     */
    const val GAP_MS = 30_000L

    /**
     * How long one background run may spend confirming before it hands back to
     * the schedule.
     *
     * WorkManager stops a worker after ten minutes. Element checks boot a WebView
     * each time and a threshold of ten is nine retries, so this leaves room for
     * the checks themselves inside the window. Whatever is left over is picked up
     * by the next scheduled check, which is exactly what happened before.
     */
    const val BUDGET_MS = 4 * 60_000L

    /** Where a failing monitor stands against its threshold. */
    data class Streak(
        val failed: Int,
        val needed: Int,
        /** When the next confirming check is due, or null when it waits for the schedule. */
        val retryAt: Long?,
    ) {
        /**
         * The clause the card and the detail screen add to the failure message.
         *
         * No countdown. The UI clock ticks every twenty seconds, so "retrying in
         * 25s" would sit on 25 for most of the wait and then jump past zero.
         */
        val line: String
            get() = if (retryAt == null) {
                "$failed of $needed failures before it alerts"
            } else {
                "$failed of $needed failures, re-checking every ${GAP_MS / 1000}s"
            }
    }

    /**
     * The streak a monitor is in, or null when it is not counting towards an alert.
     *
     * Null when it is passing, when it is already alerting, when the threshold is
     * one (the first failure already alerted), and when its alerts are off: a
     * count towards a notification that will never be posted is not worth a line.
     */
    fun streak(
        monitor: Monitor,
        policy: AlertPolicy,
        runtime: MonitorRuntime,
        gapMs: Long = GAP_MS,
    ): Streak? {
        if (!monitor.enabled || !policy.enabled || !policy.alertOnDown) return null
        val needed = policy.failureThreshold.coerceAtLeast(1)
        val failed = runtime.consecutiveFailures
        if (needed <= 1 || failed <= 0 || failed >= needed || runtime.alerting) return null
        return Streak(
            failed = failed,
            needed = needed,
            retryAt = if (pending(monitor, policy, runtime)) runtime.lastCheckedAt + gapMs else null,
        )
    }

    /**
     * Whether a confirming check is owed for this monitor.
     *
     * Only after a check that produced a failing verdict. The no-verdict path, an
     * offline phone, a dead local network or a checker that threw, stamps
     * `lastCheckedAt` without a sample, and that stamp must not start a thirty
     * second loop: a car park would otherwise retry every monitor twice a minute
     * for as long as the phone sat in it. The last sample carrying the same
     * timestamp as the last attempt is how the two are told apart.
     *
     * GitHub repositories are left on the schedule. Their checks spend a rate
     * limit shared with everything else on the token, and what they report is
     * activity rather than an outage anybody needs paging about within a minute.
     */
    fun pending(monitor: Monitor, policy: AlertPolicy, runtime: MonitorRuntime): Boolean {
        if (!policy.quickRetry) return false
        if (monitor.kind == MonitorKind.GITHUB_REPO) return false
        if (!monitor.enabled || !policy.enabled || !policy.alertOnDown) return false
        val needed = policy.failureThreshold.coerceAtLeast(1)
        val failed = runtime.consecutiveFailures
        if (failed <= 0 || failed >= needed || runtime.alerting) return false
        val last = runtime.samples.lastOrNull() ?: return false
        return !last.ok && last.at == runtime.lastCheckedAt
    }

    /** Whether the confirming check is due now. */
    fun due(
        monitor: Monitor,
        policy: AlertPolicy,
        runtime: MonitorRuntime,
        nowMs: Long,
        gapMs: Long = GAP_MS,
    ): Boolean = pending(monitor, policy, runtime) && nowMs - runtime.lastCheckedAt >= gapMs
}
