package me.river.pulse.domain

/**
 * The URGENT escalation state machine.
 *
 * A monitor flagged urgent keeps shouting while it is down and nobody has said
 * "I've seen it". Three rules make that bearable rather than abusive:
 *
 *  1. **One notification, re-posted.** Every repeat reuses the same id, so the
 *     shade never fills with duplicates — it re-alerts in place.
 *  2. **Acknowledgement is sticky for the outage.** Once acknowledged the loop
 *     stops even though the monitor is still down; the card stays red.
 *  3. **Recovery re-arms it.** Coming back up clears the acknowledgement, so
 *     the *next* outage shouts again.
 *
 * Kept free of Android types so every transition is unit-testable.
 */
object UrgentAlerts {

    /** Everything the loop needs to remember between checks. */
    data class State(
        val active: Boolean = false,
        val acknowledged: Boolean = false,
        val lastAlertAt: Long = 0L,
    ) {
        /** True while the loop still owes the user a notification. */
        val nagging: Boolean get() = active && !acknowledged

        companion object {
            val Idle = State()
        }
    }

    enum class Action {
        /** Nothing to do — either quiet, or waiting out the repeat gap. */
        NONE,

        /** First urgent notification for this outage. */
        START,

        /** Re-post the same notification because the gap elapsed. */
        REPEAT,

        /** Tear the urgent notification down. */
        CLEAR,
    }

    data class Outcome(val action: Action, val state: State)

    /**
     * Folds one observation into the loop.
     *
     * @param eligible whether urgent alerting is allowed at all right now —
     *   the monitor has urgent switched on, is not muted, and its alert policy
     *   passes (master switch, per-monitor enable, failure threshold, quiet
     *   hours). Callers compute this; the machine only sequences.
     * @param down whether the monitor is currently failing
     * @param repeatMinutes gap between re-alerts, floored at one minute
     */
    fun evaluate(
        previous: State,
        eligible: Boolean,
        down: Boolean,
        nowMs: Long,
        repeatMinutes: Int,
    ): Outcome {
        // Recovery — or urgent being switched off — wipes the slate, including
        // the acknowledgement, so the next outage is loud again.
        //
        // CLEAR unconditionally, even from an already-idle state. This is a
        // reconciliation, not a transition: cancelling a notification that was
        // never posted costs nothing, whereas returning NONE here assumes the
        // caller's `previous` is a truthful record of what is on screen. It
        // isn't always — `run()` reads it before a check that can take seconds,
        // so two overlapping runs of one monitor can post a notification from
        // the stale-losing run and then persist idle state from the other. The
        // notification is `ongoing`, so nothing — not even the user — could
        // then remove it. Shipped that way in 1.1.0; see HANDOFF.
        if (!down) return Outcome(Action.CLEAR, State.Idle)
        if (!eligible) {
            // Still down, but we're not allowed to shout (muted, quiet hours,
            // below threshold…). Drop the notification but keep the
            // acknowledgement: un-muting shouldn't re-nag about a seen outage.
            return if (previous.active) {
                Outcome(Action.CLEAR, previous.copy(active = false))
            } else {
                Outcome(Action.NONE, previous)
            }
        }
        if (previous.acknowledged) return Outcome(Action.NONE, previous)
        if (!previous.active) {
            return Outcome(Action.START, State(active = true, lastAlertAt = nowMs))
        }
        val gap = repeatMinutes.coerceAtLeast(1) * 60_000L
        return if (nowMs - previous.lastAlertAt >= gap) {
            Outcome(Action.REPEAT, previous.copy(lastAlertAt = nowMs))
        } else {
            Outcome(Action.NONE, previous)
        }
    }

    /**
     * The user tapped "I've got it" — in the app or on the notification.
     * Idempotent: acknowledging twice is a no-op.
     */
    fun acknowledge(previous: State): Outcome {
        if (!previous.active && !previous.acknowledged) return Outcome(Action.NONE, previous)
        if (previous.acknowledged && !previous.active) return Outcome(Action.NONE, previous)
        return Outcome(Action.CLEAR, previous.copy(active = false, acknowledged = true))
    }

    /** Milliseconds until the next repeat is due, or null when nothing is pending. */
    fun nextRepeatDelayMs(state: State, nowMs: Long, repeatMinutes: Int): Long? {
        if (!state.nagging) return null
        val gap = repeatMinutes.coerceAtLeast(1) * 60_000L
        return (state.lastAlertAt + gap - nowMs).coerceAtLeast(0L)
    }
}
