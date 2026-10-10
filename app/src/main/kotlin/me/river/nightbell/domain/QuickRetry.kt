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
     * the schedule, counted from when the run started rather than from when it
     * began confirming. WorkManager's ten minutes count from the start too, and a
     * sweep that spent two of them on its own pass and then confirmed for seven
     * more was stopped mid-check.
     *
     * The threshold stepper goes to ten, which is nine checks thirty seconds
     * apart: four and a half minutes before the checks themselves take any time.
     * Seven minutes covers that for quick checks and leaves three for the one
     * already running when it ends. Slow checks at a high threshold do not all
     * fit, and what is left over goes back to the schedule, which is all that
     * used to happen anyway.
     */
    const val BUDGET_MS = 7 * 60_000L

    /** Where a failing monitor stands against its threshold. */
    data class Streak(val failed: Int, val needed: Int) {
        /**
         * The clause the card and the detail screen add to the failure message.
         *
         * Says nothing about retrying. Whether a retry is actually running depends
         * on the network, the budget and the platform, none of which the card can
         * see, and a line that said "re-checking" while the phone sat offline was
         * the card describing work that was not happening. The count is always
         * true.
         */
        val line: String get() = "$failed of $needed failures before it alerts"
    }

    /**
     * The streak a monitor is in, or null when it is not counting towards an alert.
     *
     * Null when it is passing, when it is already alerting, when the threshold is
     * one (the first failure already alerted), and when nothing would be posted
     * at the end of it: alerts off for the monitor, the master switch off, or the
     * monitor muted. A count towards a notification that will never come is not
     * worth a line.
     */
    fun streak(
        monitor: Monitor,
        policy: AlertPolicy,
        runtime: MonitorRuntime,
        silenced: Boolean,
    ): Streak? {
        if (silenced || !monitor.enabled || !policy.enabled || !policy.alertOnDown) return null
        val needed = policy.failureThreshold.coerceAtLeast(1)
        val failed = runtime.consecutiveFailures
        if (needed <= 1 || failed <= 0 || failed >= needed || runtime.alerting) return null
        return Streak(failed = failed, needed = needed)
    }

    /**
     * Whether nothing this monitor could reach would ever be posted: the master
     * switch is off, monitoring is paused, or the monitor is muted. Retrying
     * towards that alert would be spending checks, and a WebView boot each for a
     * page monitor, on silence.
     *
     * A pause is passed in on its own because it lives beside the settings, not
     * in them. The engine reads it as the master switch being off, and leaving it
     * out here had the card counting towards an alert the pause would swallow.
     */
    fun silenced(settings: GlobalSettings, paused: Boolean, runtime: MonitorRuntime, nowMs: Long): Boolean =
        paused || !settings.masterAlertsEnabled || runtime.mutedUntil > nowMs

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
    fun pending(
        monitor: Monitor,
        policy: AlertPolicy,
        runtime: MonitorRuntime,
        silenced: Boolean,
    ): Boolean {
        if (!policy.quickRetry) return false
        if (monitor.kind == MonitorKind.GITHUB_REPO) return false
        if (streak(monitor, policy, runtime, silenced) == null) return false
        val last = runtime.samples.lastOrNull() ?: return false
        return !last.ok && last.at == runtime.lastCheckedAt
    }

    /** Whether the confirming check is due now. */
    fun due(
        monitor: Monitor,
        policy: AlertPolicy,
        runtime: MonitorRuntime,
        silenced: Boolean,
        nowMs: Long,
        gapMs: Long = GAP_MS,
    ): Boolean = pending(monitor, policy, runtime, silenced) && nowMs - runtime.lastCheckedAt >= gapMs
}
