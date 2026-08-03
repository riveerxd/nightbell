package me.river.pulse.domain

/**
 * Erases the fake "Checker crashed" verdicts that 1.5.0 and earlier persisted.
 *
 * ### What is actually on disk
 * When a check was cancelled, `CheckEngine` fabricated a failed [CheckResult]
 * whose message was [CheckerHealth.LEGACY_CRASH_MESSAGE], folded it into the
 * monitor's runtime, and alerted on it. On a real device this left, per monitor: a
 * `DOWN` health, a raised `alerting` flag, a non-zero failure streak, a
 * `lastMessage` of "Checker crashed", `urgentActive`, and one `Sample` recording
 * a failure that never happened.
 *
 * Upgrading without touching that data would keep three separate lies running:
 *
 *  1. the dashboard card stays red for a site that is fine;
 *  2. `CheckEngine.reconcileNotifications` sees `health == DOWN`, decides the
 *     down notification is legitimate, and preserves it;
 *  3. `lastResultFor` re-hydrates the string verbatim into every urgent re-nag —
 *     and the urgent notification is `ongoing` and bypasses Do Not Disturb, so
 *     the user cannot even swipe it away. Six of those were standing at once on
 *     the reported device.
 *
 * ### Why the samples go too
 * Deleting history is not normally acceptable, and nothing else here does it.
 * These particular entries are failures that did not occur, so keeping them means
 * an uptime percentage and a p95 computed partly from fiction. A sample is only
 * dropped when it is *both* a failure *and* carries the sentinel note, which no
 * genuine verdict ever does.
 *
 * Pure and idempotent, so `PulseStore` can apply it on every read — in force from
 * the first moment the new build runs, with no write to schedule and no race
 * against a worker that starts before a startup repair would have finished.
 */
object LegacyCrashRepair {

    /** True when this runtime is carrying a verdict no check ever produced. */
    fun isFabricated(runtime: MonitorRuntime): Boolean =
        runtime.lastMessage == CheckerHealth.LEGACY_CRASH_MESSAGE ||
            runtime.samples.any { !it.ok && it.note == CheckerHealth.LEGACY_CRASH_MESSAGE }

    fun needsRepair(runtimes: Map<String, MonitorRuntime>): Boolean =
        runtimes.values.any(::isFabricated)

    fun scrub(runtimes: Map<String, MonitorRuntime>): Map<String, MonitorRuntime> {
        if (!needsRepair(runtimes)) return runtimes
        return runtimes.mapValues { (_, runtime) -> scrub(runtime) }
    }

    fun scrub(runtime: MonitorRuntime): MonitorRuntime {
        if (!isFabricated(runtime)) return runtime
        val samples = runtime.samples.filterNot {
            !it.ok && it.note == CheckerHealth.LEGACY_CRASH_MESSAGE
        }
        // A monitor whose *latest* message is the sentinel is currently sitting on
        // a fabricated verdict, and everything derived from it goes. One that only
        // has older fabricated samples has since had a real check, so its current
        // state is real and is left exactly as it is — only the fake history goes.
        val currentVerdictIsFake = runtime.lastMessage == CheckerHealth.LEGACY_CRASH_MESSAGE
        if (!currentVerdictIsFake) return runtime.copy(samples = samples)
        return runtime.copy(
            // UNKNOWN, not UP. The last thing actually known about this monitor is
            // nothing; the next check fills it in. Claiming health nobody observed
            // would be the same mistake pointing the other way.
            health = if (runtime.health == Health.PAUSED) Health.PAUSED else Health.UNKNOWN,
            lastMessage = "",
            lastDetail = "",
            consecutiveFailures = 0,
            alerting = false,
            // Zero, not the fake timestamp. `lastCheckedAt` is the due-clock, and
            // leaving the fabricated time on it would make the monitor wait out a
            // whole interval before anyone found out what is actually true.
            lastCheckedAt = 0L,

            // ---- every alert-suppression field the fake verdict set ------------
            // These are the subtle half of the repair. Clearing `alerting` without
            // clearing `lastAlertAt` just moves the silence from the
            // already-alerting branch of AlertDecider.decide to its cooldown
            // branch: a genuine outage arriving within `cooldownMinutes` of the
            // fabricated alert is suppressed, and that window is up to four hours
            // at the maximum the UI offers. The fake alert must leave no clock
            // behind at all.
            lastAlertAt = 0L,
            degradedAlerting = false,
            lastDegradedAlertAt = 0L,

            // Stops the urgent loop dead. An orphaned urgent notification is the
            // worst thing to leave behind: ongoing, DND-bypassing, un-dismissable.
            urgentActive = false,
            // And the acknowledgement of it, which is the field that would
            // otherwise silence urgent *permanently*. Tapping "I've got it" on an
            // ongoing fake nag is the only way to get rid of it, so it is the
            // expected state on an affected device — and `UrgentAlerts.evaluate`
            // returns NONE for an acknowledged monitor and only clears the
            // acknowledgement on a *successful* check, which never arrives while a
            // site is genuinely down. An acknowledgement of an outage that never
            // happened means nothing and does not survive.
            urgentAcknowledged = false,
            lastUrgentAlertAt = 0L,
            samples = samples,
        )
    }
}
