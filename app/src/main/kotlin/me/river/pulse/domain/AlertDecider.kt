package me.river.pulse.domain

/**
 * Decides whether a check transition should actually bother the human.
 * Pure function of (previous runtime, new outcome, policy, clock) so the whole
 * escalation matrix — thresholds, cooldown, repeat nagging, quiet hours,
 * recovery — is unit-testable without Android.
 */
object AlertDecider {

    enum class Kind { NONE, DOWN, REPEAT, RECOVERY }

    enum class Suppression {
        NOT_SUPPRESSED,
        MASTER_MUTED,
        POLICY_DISABLED,
        DOWN_ALERTS_OFF,
        RECOVERY_ALERTS_OFF,
        BELOW_THRESHOLD,
        COOLDOWN,
        QUIET_HOURS,
        NO_TRANSITION,
        ;

        val explanation: String
            get() = when (this) {
                NOT_SUPPRESSED -> ""
                MASTER_MUTED -> "All alerts are muted in settings"
                POLICY_DISABLED -> "Alerts are off for this monitor"
                DOWN_ALERTS_OFF -> "Down alerts are off for this monitor"
                RECOVERY_ALERTS_OFF -> "Recovery alerts are off for this monitor"
                BELOW_THRESHOLD -> "Waiting for the failure threshold"
                COOLDOWN -> "Inside the alert cooldown window"
                QUIET_HOURS -> "Quiet hours are active"
                NO_TRANSITION -> "Nothing changed"
            }
    }

    data class Decision(
        val kind: Kind,
        val forceSilent: Boolean = false,
        val suppression: Suppression = Suppression.NOT_SUPPRESSED,
    ) {
        val shouldNotify: Boolean get() = kind != Kind.NONE

        companion object {
            fun none(reason: Suppression) = Decision(Kind.NONE, suppression = reason)
        }
    }

    /**
     * @param wasAlerting whether this monitor was already in an alerting state
     * @param ok outcome of the check that just finished
     * @param consecutiveFailures failure streak *including* the check that just finished
     * @param lastAlertAt epoch millis of the last alert we raised (0 = never)
     * @param nowMs current epoch millis
     * @param minuteOfDay local wall-clock minute, 0..1439, used for quiet hours
     */
    fun decide(
        wasAlerting: Boolean,
        ok: Boolean,
        consecutiveFailures: Int,
        lastAlertAt: Long,
        policy: AlertPolicy,
        masterEnabled: Boolean,
        nowMs: Long,
        minuteOfDay: Int,
    ): Decision {
        if (!masterEnabled) return Decision.none(Suppression.MASTER_MUTED)
        if (!policy.enabled) return Decision.none(Suppression.POLICY_DISABLED)

        val quiet = policy.quietHoursEnabled &&
            inQuietHours(minuteOfDay, policy.quietStartMinute, policy.quietEndMinute)

        if (ok) {
            if (!wasAlerting) return Decision.none(Suppression.NO_TRANSITION)
            if (!policy.alertOnRecovery) return Decision.none(Suppression.RECOVERY_ALERTS_OFF)
            // Recovery is good news: never nag-blocked by cooldown, but stays
            // quiet during quiet hours unless the user opted into bypass.
            if (quiet && !policy.criticalBypassesQuiet) return Decision.none(Suppression.QUIET_HOURS)
            return Decision(Kind.RECOVERY, forceSilent = quiet)
        }

        if (!policy.alertOnDown) return Decision.none(Suppression.DOWN_ALERTS_OFF)
        if (consecutiveFailures < policy.failureThreshold.coerceAtLeast(1)) {
            return Decision.none(Suppression.BELOW_THRESHOLD)
        }
        if (quiet && !policy.criticalBypassesQuiet) return Decision.none(Suppression.QUIET_HOURS)

        val sinceLastAlert = if (lastAlertAt <= 0L) Long.MAX_VALUE else nowMs - lastAlertAt

        if (!wasAlerting) {
            if (sinceLastAlert < policy.cooldownMinutes.coerceAtLeast(0) * 60_000L) {
                return Decision.none(Suppression.COOLDOWN)
            }
            return Decision(Kind.DOWN, forceSilent = quiet)
        }

        // Already alerting: only speak again if the user asked to be nagged.
        if (!policy.repeatEnabled) return Decision.none(Suppression.NO_TRANSITION)
        val repeatGap = policy.repeatEveryMinutes.coerceAtLeast(1) * 60_000L
        if (sinceLastAlert < repeatGap) return Decision.none(Suppression.COOLDOWN)
        return Decision(Kind.REPEAT, forceSilent = quiet)
    }

    /** Handles windows that wrap past midnight, e.g. 22:00 → 07:00. */
    fun inQuietHours(minuteOfDay: Int, startMinute: Int, endMinute: Int): Boolean {
        if (startMinute == endMinute) return false
        return if (startMinute < endMinute) {
            minuteOfDay >= startMinute && minuteOfDay < endMinute
        } else {
            minuteOfDay >= startMinute || minuteOfDay < endMinute
        }
    }

    /** Folds a check outcome into the persisted runtime state for a monitor. */
    fun advance(
        previous: MonitorRuntime,
        result: CheckResult,
        historyDepth: Int,
        degradedAboveMs: Long = 2_500L,
    ): MonitorRuntime {
        val sample = Sample(
            at = result.at,
            ok = result.ok,
            latencyMs = result.latencyMs,
            code = result.statusCode,
            note = if (result.ok) "" else result.message,
        )
        val history = (previous.samples + sample).takeLast(historyDepth.coerceAtLeast(1))
        val failures = if (result.ok) 0 else previous.consecutiveFailures + 1
        val successes = if (result.ok) previous.consecutiveSuccesses + 1 else 0
        return previous.copy(
            health = when {
                !result.ok -> Health.DOWN
                result.latencyMs > degradedAboveMs -> Health.DEGRADED
                else -> Health.UP
            },
            lastCheckedAt = result.at,
            lastLatencyMs = result.latencyMs,
            lastCode = result.statusCode,
            lastMessage = if (result.ok) "" else result.message,
            lastDetail = result.detail,
            consecutiveFailures = failures,
            consecutiveSuccesses = successes,
            lastElementText = result.elementText.ifBlank { previous.lastElementText },
            samples = history,
        )
    }
}
