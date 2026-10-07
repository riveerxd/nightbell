package me.river.nightbell.domain

/**
 * Which targets hear about a check, and what each one is told.
 *
 * Every target runs the same escalation matrix the phone does, through
 * [AlertDecider], on a track of its own. What differs is the policy it is
 * judged against: a target that does not follow the phone keeps the monitor's
 * threshold, cooldown and repeat cadence and drops everything that is about the
 * phone's owner, which is quiet hours, a mute, the master switch and the
 * per-monitor alert switches. Which events actually leave is the target's own
 * list, applied after the decision so that state still advances for an event
 * the target does not want: a target that only wants recoveries must still
 * know there was an outage to recover from.
 */
object WebhookRouting {

    /** What the phone's side of the check knew, so a following target can mirror it. */
    data class Phone(
        val policy: AlertPolicy,
        val masterEnabled: Boolean,
        val muted: Boolean,
        val minuteOfDay: Int,
    )

    /** One finished check, reduced to what the tracks read. */
    data class Check(
        val ok: Boolean,
        val consecutiveFailures: Int,
        /** Up but over the latency budget. False when no budget applies. */
        val degraded: Boolean,
        val at: Long,
    )

    data class Outcome(
        val sends: List<Pair<String, WebhookFacts>>,
        val tracks: Map<String, WebhookTrack>,
    )

    fun targetsFor(
        targets: List<WebhookTarget>,
        monitorId: String,
        groups: List<MonitorGroup>,
        event: WebhookEvent,
    ): List<WebhookTarget> = targets.filter { target ->
        target.enabled && !target.needsAddress && target.wants(event) && target.scope.covers(monitorId, groups)
    }

    /**
     * Runs every target's track for one monitor's check.
     *
     * @param base the facts every event from this check shares. The event, the
     *   outage start and its length are filled in per target, because each
     *   target's track has its own idea of when the outage began.
     * @return the deliveries to queue and the new state of every track touched.
     */
    fun decide(
        targets: List<WebhookTarget>,
        state: WebhookState,
        monitorId: String,
        groups: List<MonitorGroup>,
        check: Check,
        phone: Phone,
        base: WebhookFacts,
    ): Outcome {
        val sends = mutableListOf<Pair<String, WebhookFacts>>()
        val tracks = mutableMapOf<String, WebhookTrack>()
        for (target in targets) {
            if (!target.enabled || target.needsAddress) continue
            if (!target.scope.covers(monitorId, groups)) continue
            val before = state.track(target.id, monitorId)

            val follow = target.followPhone
            val policy = if (follow) {
                phone.policy
            } else {
                phone.policy.copy(
                    enabled = true,
                    alertOnDown = true,
                    alertOnRecovery = true,
                    alertOnDegraded = true,
                    alertOnDegradedRecovery = true,
                    quietHoursEnabled = false,
                    // Asking for "still down" is asking for the repeat. The cadence
                    // stays the monitor's, so the channel and the phone nag together.
                    repeatEnabled = phone.policy.repeatEnabled || WebhookEvent.STILL_DOWN in target.events,
                )
            }
            val master = if (follow) phone.masterEnabled && !phone.muted else true

            val down = AlertDecider.decide(
                wasAlerting = before.alerting,
                ok = check.ok,
                consecutiveFailures = check.consecutiveFailures,
                lastAlertAt = before.lastAlertAt,
                policy = policy,
                masterEnabled = master,
                nowMs = check.at,
                minuteOfDay = phone.minuteOfDay,
            )
            val slow = AlertDecider.decideDegraded(
                wasDegradedAlerting = before.degradedAlerting,
                ok = check.ok,
                degraded = check.degraded,
                lastDegradedAlertAt = before.lastDegradedAlertAt,
                policy = policy,
                masterEnabled = master,
                nowMs = check.at,
                minuteOfDay = phone.minuteOfDay,
            )

            val failingSince = when {
                check.ok -> 0L
                before.failingSinceAt > 0L -> before.failingSinceAt
                else -> check.at
            }

            val event = when (down.kind) {
                AlertDecider.Kind.DOWN -> WebhookEvent.DOWN
                AlertDecider.Kind.REPEAT -> WebhookEvent.STILL_DOWN
                AlertDecider.Kind.RECOVERY -> WebhookEvent.RECOVERED
                else -> null
            }
            if (event != null && target.wants(event)) {
                sends += target.id to base.copy(
                    event = event,
                    downSinceAt = if (event == WebhookEvent.RECOVERED) before.failingSinceAt else failingSince,
                    downForMs = if (event == WebhookEvent.RECOVERED && before.failingSinceAt > 0L) {
                        (check.at - before.failingSinceAt).coerceAtLeast(0L)
                    } else {
                        0L
                    },
                )
            }
            val slowEvent = when (slow.kind) {
                AlertDecider.Kind.DEGRADED, AlertDecider.Kind.DEGRADED_REPEAT -> WebhookEvent.DEGRADED
                AlertDecider.Kind.DEGRADED_RECOVERY -> WebhookEvent.DEGRADED_RECOVERED
                else -> null
            }
            if (slowEvent != null && target.wants(slowEvent)) {
                sends += target.id to base.copy(event = slowEvent)
            }

            val alertingNow = when (down.kind) {
                AlertDecider.Kind.DOWN, AlertDecider.Kind.REPEAT -> true
                AlertDecider.Kind.RECOVERY -> false
                else -> if (check.ok) false else before.alerting
            }
            val degradedNow = when (slow.kind) {
                AlertDecider.Kind.DEGRADED, AlertDecider.Kind.DEGRADED_REPEAT -> true
                AlertDecider.Kind.DEGRADED_RECOVERY -> false
                else -> if (!check.ok) false else if (check.degraded) before.degradedAlerting else false
            }
            val after = WebhookTrack(
                alerting = alertingNow,
                lastAlertAt = if (down.shouldNotify) check.at else before.lastAlertAt,
                degradedAlerting = degradedNow,
                lastDegradedAlertAt = if (slow.shouldNotify) check.at else before.lastDegradedAlertAt,
                failingSinceAt = failingSince,
            )
            if (after != before) tracks[WebhookState.trackKey(target.id, monitorId)] = after
        }
        return Outcome(sends, tracks)
    }

    /**
     * For the events that ride on the phone's own decision: certificate notices,
     * repository news and an acknowledged page.
     *
     * Those have no escalation of their own to run per target. A following
     * target hears them when the phone would; any other target hears them
     * whatever the phone's quiet hours and mute say.
     *
     * @param phoneAllowed whether the phone's own switches, quiet hours and mute
     *   let this event through.
     */
    fun passThrough(
        targets: List<WebhookTarget>,
        monitorId: String,
        groups: List<MonitorGroup>,
        event: WebhookEvent,
        phoneAllowed: Boolean,
    ): List<WebhookTarget> = targetsFor(targets, monitorId, groups, event)
        .filter { !it.followPhone || phoneAllowed }
}

/**
 * The outbox's rules: what to keep, when to try again, when to give up.
 *
 * Delivery is at least once. A delivery keeps its id across retries and every
 * payload carries it, so a receiver that cares can drop a duplicate; one that
 * does not will at worst see the same outage twice, which beats not at all.
 */
object WebhookOutbox {

    /** Past this a queued outage is history, and posting it would read as current. */
    const val MAX_AGE_MS = 24L * 60 * 60 * 1000

    /**
     * Total queue length. A receiver that has been down for a day while forty
     * monitors flap would otherwise grow the store without bound, and the store
     * is one document rewritten on every check.
     */
    const val MAX_QUEUED = 200

    private const val FIRST_RETRY_MS = 30_000L
    private const val LONGEST_RETRY_MS = 30L * 60 * 1000
    private const val LONGEST_RETRY_AFTER_MS = 60L * 60 * 1000

    enum class Verdict { DELIVERED, RETRY, REFUSED }

    /**
     * What an HTTP answer means for the delivery.
     *
     * 0 is "never got an answer": no route, DNS, a timeout, a refused handshake.
     * Those are worth retrying because the phone's own network is the usual
     * cause. A 4xx other than the throttling ones is the receiver saying no to
     * this message, and the same message will be refused the same way forever.
     */
    fun verdict(code: Int): Verdict = when {
        code in 200..299 -> Verdict.DELIVERED
        code == 0 -> Verdict.RETRY
        code == 408 || code == 425 || code == 429 -> Verdict.RETRY
        code >= 500 -> Verdict.RETRY
        else -> Verdict.REFUSED
    }

    /** 30 s, 1 min, 2, 4, 8, 16, then every 30 min until [MAX_AGE_MS]. */
    fun backoffMs(attempts: Int): Long {
        val shift = (attempts - 1).coerceIn(0, 10)
        return (FIRST_RETRY_MS shl shift).coerceAtMost(LONGEST_RETRY_MS)
    }

    fun enqueue(
        state: WebhookState,
        additions: List<PendingDelivery>,
    ): WebhookState {
        if (additions.isEmpty()) return state
        val combined = state.outbox + additions
        if (combined.size <= MAX_QUEUED) return state.copy(outbox = combined)
        // Oldest go first, and each loss is counted against its target so the
        // settings row can say that something was dropped rather than hiding it.
        val overflow = combined.take(combined.size - MAX_QUEUED)
        val status = state.status.toMutableMap()
        overflow.groupingBy { it.targetId }.eachCount().forEach { (target, lost) ->
            val current = status[target] ?: WebhookStatus()
            status[target] = current.copy(dropped = current.dropped + lost)
        }
        return state.copy(outbox = combined.drop(overflow.size), status = status)
    }

    /** What is due now, in queue order. */
    fun due(state: WebhookState, nowMs: Long): List<PendingDelivery> =
        state.outbox.filter { it.nextAttemptAt <= nowMs }

    fun isExpired(delivery: PendingDelivery, nowMs: Long): Boolean =
        nowMs - delivery.createdAt > MAX_AGE_MS

    /** Earliest moment anything in the queue wants another try, or null for an empty queue. */
    fun nextWakeAt(state: WebhookState): Long? = state.outbox.minOfOrNull { it.nextAttemptAt }

    /**
     * Folds one attempt's result back into the state.
     *
     * @param retryAfterMs the receiver's own Retry-After, when it sent one. It
     *   wins over the backoff, within reason: an hour at most, because a
     *   misconfigured proxy answering "retry after a week" should not park an
     *   outage notice past the point where it means anything.
     */
    fun afterAttempt(
        state: WebhookState,
        delivery: PendingDelivery,
        code: Int,
        error: String,
        nowMs: Long,
        retryAfterMs: Long? = null,
    ): WebhookState {
        val current = state.status[delivery.targetId] ?: WebhookStatus()
        val verdict = verdict(code)
        val attempts = delivery.attempts + 1
        val next = nowMs + (retryAfterMs?.coerceIn(0L, LONGEST_RETRY_AFTER_MS) ?: backoffMs(attempts))
        val givingUp = verdict == Verdict.REFUSED ||
            (verdict == Verdict.RETRY && next - delivery.createdAt > MAX_AGE_MS)
        val outbox = when {
            verdict == Verdict.DELIVERED || givingUp -> state.outbox.filterNot { it.id == delivery.id }
            else -> state.outbox.map {
                if (it.id == delivery.id) it.copy(attempts = attempts, nextAttemptAt = next) else it
            }
        }
        val status = when (verdict) {
            Verdict.DELIVERED -> WebhookStatus(
                lastAttemptAt = nowMs,
                lastDeliveredAt = nowMs,
                lastCode = code,
            )
            else -> current.copy(
                lastAttemptAt = nowMs,
                lastCode = code,
                lastError = error,
                failuresInARow = current.failuresInARow + 1,
                dropped = current.dropped + if (givingUp) 1 else 0,
            )
        }
        return state.copy(outbox = outbox, status = state.status + (delivery.targetId to status))
    }

    /** Removes a delivery without trying it, counted as dropped. */
    fun expire(state: WebhookState, delivery: PendingDelivery, nowMs: Long): WebhookState {
        val current = state.status[delivery.targetId] ?: WebhookStatus()
        return state.copy(
            outbox = state.outbox.filterNot { it.id == delivery.id },
            status = state.status + (
                delivery.targetId to current.copy(
                    lastAttemptAt = nowMs,
                    lastError = current.lastError.ifBlank { "Gave up after a day of trying" },
                    dropped = current.dropped + 1,
                )
                ),
        )
    }

    /**
     * Parses a Retry-After header: delta seconds, the only form anyone sends to
     * a webhook client. An HTTP date is read as absent rather than misread.
     */
    fun retryAfterMs(header: String?): Long? =
        header?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.times(1000)
}
