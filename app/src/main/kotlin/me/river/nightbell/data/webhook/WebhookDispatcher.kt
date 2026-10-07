package me.river.nightbell.data.webhook

import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.river.nightbell.data.NightbellStore
import me.river.nightbell.data.diag.Diag
import me.river.nightbell.domain.LogEvent
import me.river.nightbell.domain.LogField
import me.river.nightbell.domain.PendingDelivery
import me.river.nightbell.domain.WebhookEvent
import me.river.nightbell.domain.WebhookFacts
import me.river.nightbell.domain.WebhookOutbox
import me.river.nightbell.domain.WebhookTarget
import me.river.nightbell.domain.WebhookTrack

/**
 * Owns the outbox: queues events, sends what is due, and arranges to come back
 * for what is not.
 *
 * Everything is queued before anything is sent, even when the network is fine
 * and the send will take a quarter of a second. A check pass may be running in
 * a WorkManager process that Android reclaims the moment the worker returns,
 * and an event held only in memory dies with it. Persisted first, it is
 * delivered by whichever of this process or the next wake gets there.
 *
 * Deliveries to one target go strictly in the order they were queued. A
 * receiver that is down for ten minutes then gets "down" before "back up",
 * never the other way round, which is the one ordering mistake a channel full
 * of people would actually misread.
 */
class WebhookDispatcher(
    private val store: NightbellStore,
    private val sender: WebhookSender,
    private val isOnline: () -> Boolean = { true },
    /**
     * Asks to be woken at an absolute time, for a retry or as a backstop. Null
     * is "nothing to wake for". Wired to WorkManager by the graph; tests pass
     * a recorder.
     */
    private val wakeAt: (Long?) -> Unit = {},
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val lock = Mutex()

    /**
     * Persists [sends] (target id to facts) and the [tracks] that produced them.
     *
     * Not suspending past the write on purpose: the caller is a check pass that
     * must not wait on somebody's webhook to answer.
     */
    suspend fun enqueue(
        sends: List<Pair<String, WebhookFacts>>,
        tracks: Map<String, WebhookTrack> = emptyMap(),
    ) {
        if (sends.isEmpty() && tracks.isEmpty()) return
        val now = nowMs()
        val deliveries = sends.map { (targetId, facts) ->
            PendingDelivery(id = newId(), targetId = targetId, facts = facts, createdAt = now, nextAttemptAt = now)
        }
        // Tracks and queue in one write. Split, a process death between the two
        // either loses the event with the track already saying it was sent, or
        // sends it twice because the track never heard.
        withContext(NonCancellable) {
            store.updateWebhookState { WebhookOutbox.enqueue(it.copy(tracks = it.tracks + tracks), deliveries) }
        }
        if (deliveries.isEmpty()) return
        deliveries.forEach {
            Diag.log(
                LogEvent.WEBHOOK_QUEUED,
                LogField.tag("target", it.targetId.take(8)),
                LogField.tag("event", it.facts.event.code),
                LogField.monitor(it.facts.monitorId),
            )
        }
        // The backstop. If this process is gone before the flush below finishes,
        // the wake still happens and the delivery still goes.
        wakeAt(now + BACKSTOP_MS)
    }

    /**
     * Sends everything that is due. Returns how many were delivered.
     *
     * Serialised, so the in-process flush after a check and a WorkManager wake
     * landing at the same moment cannot both post the same delivery.
     */
    suspend fun flush(): Int = lock.withLock {
        var delivered = 0
        val snapshot = store.currentSnapshot()
        if (!snapshot.settings.webhooksEnabled) {
            wakeAt(null)
            return@withLock 0
        }
        if (!isOnline()) {
            // Not an attempt, so it costs no retry. WorkManager's network
            // constraint brings the wake back once there is something to send on.
            wakeAt(WebhookOutbox.nextWakeAt(snapshot.webhookState)?.coerceAtLeast(nowMs()))
            return@withLock 0
        }
        val waiting = mutableSetOf<String>()
        var rounds = 0
        while (rounds++ < MAX_PER_FLUSH) {
            val snap = store.currentSnapshot()
            val now = nowMs()
            val targets = snap.settings.webhooks.associateBy { it.id }
            // The head of each target's queue, and only the head: a later delivery
            // never overtakes an earlier one that is still backing off.
            val heads = snap.webhookState.outbox
                .groupBy { it.targetId }
                .mapNotNull { (_, queue) -> queue.firstOrNull() }
            val next = heads.firstOrNull { it.targetId !in waiting && it.nextAttemptAt <= now }
                ?: break
            val target = targets[next.targetId]
            if (target == null || !target.enabled || target.needsAddress || WebhookOutbox.isExpired(next, now)) {
                drop(next, target, now)
                continue
            }
            val attempt = sender.send(target, next.facts, next.id)
            withContext(NonCancellable) {
                store.updateWebhookState {
                    WebhookOutbox.afterAttempt(it, next, attempt.code, attempt.error, nowMs(), attempt.retryAfterMs)
                }
            }
            log(target, next, attempt)
            when (WebhookOutbox.verdict(attempt.code)) {
                WebhookOutbox.Verdict.DELIVERED -> delivered++
                WebhookOutbox.Verdict.RETRY -> waiting += target.id
                WebhookOutbox.Verdict.REFUSED -> Unit
            }
        }
        wakeAt(WebhookOutbox.nextWakeAt(store.currentSnapshot().webhookState))
        delivered
    }

    /**
     * Sends one test message straight away, outside the outbox.
     *
     * Not queued: the person tapping the button is waiting for the answer, and
     * a test that quietly retries for a day is not a test.
     */
    suspend fun sendTest(target: WebhookTarget, sender: String): WebhookSender.Attempt =
        this.sender.send(target, testFacts(sender, nowMs()), newId())

    /** Throws away everything waiting, for the master switch going off. */
    suspend fun clear() {
        store.updateWebhookState { it.copy(outbox = emptyList()) }
        wakeAt(null)
    }

    private suspend fun drop(delivery: PendingDelivery, target: WebhookTarget?, now: Long) {
        withContext(NonCancellable) {
            store.updateWebhookState { WebhookOutbox.expire(it, delivery, now) }
        }
        Diag.log(
            LogEvent.WEBHOOK_DROPPED,
            LogField.tag("target", delivery.targetId.take(8)),
            LogField.tag("event", delivery.facts.event.code),
            LogField.tag(
                "why",
                when {
                    target == null -> "target_deleted"
                    !target.enabled -> "target_off"
                    target.needsAddress -> "no_address"
                    else -> "expired"
                },
            ),
        )
    }

    private fun log(target: WebhookTarget, delivery: PendingDelivery, attempt: WebhookSender.Attempt) {
        if (attempt.delivered) {
            Diag.log(
                LogEvent.WEBHOOK_SENT,
                LogField.tag("target", target.id.take(8)),
                LogField.tag("format", target.format.name.lowercase()),
                LogField.tag("event", delivery.facts.event.code),
                LogField.of("code", attempt.code),
                LogField.ms("took", attempt.ms),
            )
        } else {
            Diag.log(
                LogEvent.WEBHOOK_FAILED,
                LogField.tag("target", target.id.take(8)),
                LogField.tag("format", target.format.name.lowercase()),
                LogField.tag("event", delivery.facts.event.code),
                LogField.of("code", attempt.code),
                LogField.of("attempt", delivery.attempts + 1),
                LogField.text("why", attempt.error, known = target.secrets),
            )
        }
    }

    companion object {
        /** A runaway queue is cut off here and picked up again on the next wake. */
        private const val MAX_PER_FLUSH = 50

        private const val BACKSTOP_MS = 60_000L

        /**
         * What the test button sends. Shaped like a real event, with a monitor
         * and numbers in it, so a custom template's placeholders come out filled
         * and the person testing can see what an outage will look like.
         */
        fun testFacts(sender: String, nowMs: Long) = WebhookFacts(
            event = WebhookEvent.TEST,
            monitorId = "test",
            monitorName = "Example monitor",
            monitorUrl = "https://example.com/health",
            monitorKind = "Status check",
            reason = "Test",
            message = "Sent from the Nightbell settings screen",
            statusCode = 200,
            latencyMs = 120,
            at = nowMs,
            sender = sender,
        )
    }
}
