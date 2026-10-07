package me.river.nightbell

import me.river.nightbell.domain.AlertPolicy
import me.river.nightbell.domain.MonitorGroup
import me.river.nightbell.domain.WebhookEvent
import me.river.nightbell.domain.WebhookFacts
import me.river.nightbell.domain.WebhookRouting
import me.river.nightbell.domain.WebhookScope
import me.river.nightbell.domain.WebhookState
import me.river.nightbell.domain.WebhookTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-target escalation, driven check by check the way the engine drives it.
 */
class WebhookRoutingTest {

    private val minute = 60_000L
    private val noon = 12 * 60

    private fun target(id: String = "t", block: WebhookTarget.() -> WebhookTarget = { this }) =
        WebhookTarget(id = id, url = "https://hooks.example.com/$id").block()

    /** Feeds a run of checks through the tracks and collects every event sent. */
    private class Run(
        val targets: List<WebhookTarget>,
        val policy: AlertPolicy = AlertPolicy(failureThreshold = 1, cooldownMinutes = 0),
        val groups: List<MonitorGroup> = emptyList(),
        val monitorId: String = "m",
    ) {
        var state = WebhookState()
        val sent = mutableListOf<Pair<String, WebhookFacts>>()
        private var failures = 0

        fun check(
            ok: Boolean,
            at: Long,
            degraded: Boolean = false,
            muted: Boolean = false,
            master: Boolean = true,
            minuteOfDay: Int = 12 * 60,
        ): List<Pair<String, WebhookEvent>> {
            failures = if (ok) 0 else failures + 1
            val outcome = WebhookRouting.decide(
                targets = targets,
                state = state,
                monitorId = monitorId,
                groups = groups,
                check = WebhookRouting.Check(ok = ok, consecutiveFailures = failures, degraded = degraded, at = at),
                phone = WebhookRouting.Phone(policy, master, muted, minuteOfDay),
                base = WebhookFacts(event = WebhookEvent.DOWN, monitorId = monitorId, at = at),
            )
            state = state.copy(tracks = state.tracks + outcome.tracks)
            sent += outcome.sends
            return outcome.sends.map { it.first to it.second.event }
        }
    }

    @Test
    fun `an outage posts once and its end posts once`() {
        val run = Run(listOf(target()))
        assertEquals(emptyList<Any>(), run.check(ok = true, at = 0))
        assertEquals(listOf("t" to WebhookEvent.DOWN), run.check(ok = false, at = minute))
        assertEquals(emptyList<Any>(), run.check(ok = false, at = 2 * minute))
        assertEquals(emptyList<Any>(), run.check(ok = false, at = 3 * minute))
        assertEquals(listOf("t" to WebhookEvent.RECOVERED), run.check(ok = true, at = 4 * minute))
        assertEquals(emptyList<Any>(), run.check(ok = true, at = 5 * minute))
    }

    @Test
    fun `the failure threshold holds back a blip`() {
        val run = Run(listOf(target()), policy = AlertPolicy(failureThreshold = 3, cooldownMinutes = 0))
        assertEquals(emptyList<Any>(), run.check(ok = false, at = minute))
        assertEquals(emptyList<Any>(), run.check(ok = false, at = 2 * minute))
        assertEquals("a recovery with no announced outage is not news", emptyList<Any>(), run.check(ok = true, at = 3 * minute))
        run.check(ok = false, at = 4 * minute)
        run.check(ok = false, at = 5 * minute)
        assertEquals(listOf("t" to WebhookEvent.DOWN), run.check(ok = false, at = 6 * minute))
    }

    @Test
    fun `the recovery says how long the outage lasted, counted from the first failure`() {
        val run = Run(listOf(target()), policy = AlertPolicy(failureThreshold = 2, cooldownMinutes = 0))
        run.check(ok = false, at = 10 * minute)
        val down = run.check(ok = false, at = 11 * minute)
        assertEquals(listOf("t" to WebhookEvent.DOWN), down)
        assertEquals(10 * minute, run.sent.last().second.downSinceAt)
        run.check(ok = true, at = 25 * minute)
        val recovery = run.sent.last().second
        assertEquals(WebhookEvent.RECOVERED, recovery.event)
        assertEquals(15 * minute, recovery.downForMs)
    }

    @Test
    fun `quiet hours silence the phone but not a channel`() {
        val quiet = AlertPolicy(
            failureThreshold = 1,
            cooldownMinutes = 0,
            quietHoursEnabled = true,
            quietStartMinute = 22 * 60,
            quietEndMinute = 7 * 60,
        )
        val run = Run(listOf(target("channel"), target("mine") { copy(followPhone = true) }), policy = quiet)
        val night = 3 * 60
        assertEquals(listOf("channel" to WebhookEvent.DOWN), run.check(ok = false, at = minute, minuteOfDay = night))
        // Still quiet, still down: the channel was told once, the follower nothing.
        assertEquals(emptyList<Any>(), run.check(ok = false, at = 2 * minute, minuteOfDay = night))
        assertEquals(emptyList<Any>(), run.check(ok = false, at = 3 * minute, minuteOfDay = night))
        // Morning: the follower now hears about the outage that is still going on.
        assertEquals(listOf("mine" to WebhookEvent.DOWN), run.check(ok = false, at = 4 * minute, minuteOfDay = 8 * 60))
    }

    @Test
    fun `a mute and the master switch only stop targets that follow the phone`() {
        val run = Run(listOf(target("channel"), target("mine") { copy(followPhone = true) }))
        assertEquals(listOf("channel" to WebhookEvent.DOWN), run.check(ok = false, at = minute, muted = true))
        assertEquals(emptyList<Any>(), run.check(ok = false, at = 2 * minute, master = false))
    }

    @Test
    fun `alerts switched off for the monitor still reach a channel that does not follow the phone`() {
        val off = AlertPolicy(enabled = false)
        val run = Run(listOf(target("channel"), target("mine") { copy(followPhone = true) }), policy = off)
        assertEquals(listOf("channel" to WebhookEvent.DOWN), run.check(ok = false, at = minute))
    }

    @Test
    fun `a target that only wants recoveries still learns there was an outage`() {
        val run = Run(listOf(target { copy(events = setOf(WebhookEvent.RECOVERED)) }))
        assertEquals(emptyList<Any>(), run.check(ok = false, at = minute))
        assertEquals(listOf("t" to WebhookEvent.RECOVERED), run.check(ok = true, at = 2 * minute))
    }

    @Test
    fun `asking for still down turns the repeat on at the monitor's cadence`() {
        val policy = AlertPolicy(failureThreshold = 1, cooldownMinutes = 0, repeatEnabled = false, repeatEveryMinutes = 30)
        // Not zero: a last-alert time of zero means "never alerted" to the decider.
        val t0 = 1_700_000_000_000L
        val run = Run(listOf(target { copy(events = setOf(WebhookEvent.DOWN, WebhookEvent.STILL_DOWN)) }), policy = policy)
        assertEquals(listOf("t" to WebhookEvent.DOWN), run.check(ok = false, at = t0))
        assertEquals(emptyList<Any>(), run.check(ok = false, at = t0 + 20 * minute))
        assertEquals(listOf("t" to WebhookEvent.STILL_DOWN), run.check(ok = false, at = t0 + 31 * minute))
        assertEquals(emptyList<Any>(), run.check(ok = false, at = t0 + 40 * minute))
    }

    @Test
    fun `cooldown keeps a flapping endpoint from flooding the channel`() {
        val policy = AlertPolicy(failureThreshold = 1, cooldownMinutes = 10)
        val t0 = 1_700_000_000_000L
        val run = Run(listOf(target()), policy = policy)
        assertEquals(listOf("t" to WebhookEvent.DOWN), run.check(ok = false, at = t0))
        assertEquals(listOf("t" to WebhookEvent.RECOVERED), run.check(ok = true, at = t0 + minute))
        assertEquals(emptyList<Any>(), run.check(ok = false, at = t0 + 2 * minute))
        assertEquals(emptyList<Any>(), run.check(ok = true, at = t0 + 3 * minute))
        assertEquals(listOf("t" to WebhookEvent.DOWN), run.check(ok = false, at = t0 + 12 * minute))
    }

    @Test
    fun `slow and back to speed are their own events`() {
        val policy = AlertPolicy(failureThreshold = 1, cooldownMinutes = 0, degradedCooldownMinutes = 0)
        val run = Run(listOf(target { copy(events = setOf(WebhookEvent.DEGRADED, WebhookEvent.DEGRADED_RECOVERED)) }), policy = policy)
        assertEquals(listOf("t" to WebhookEvent.DEGRADED), run.check(ok = true, at = 0, degraded = true))
        assertEquals(emptyList<Any>(), run.check(ok = true, at = minute, degraded = true))
        assertEquals(listOf("t" to WebhookEvent.DEGRADED_RECOVERED), run.check(ok = true, at = 2 * minute))
    }

    @Test
    fun `scope by group follows the group's members as they are now`() {
        val groups = listOf(MonitorGroup(id = "prod", memberIds = listOf("m")))
        val byGroup = target("g") { copy(scope = WebhookScope(all = false, groupIds = setOf("prod"))) }
        val byMonitor = target("x") { copy(scope = WebhookScope(all = false, monitorIds = setOf("other"))) }
        val run = Run(listOf(byGroup, byMonitor), groups = groups)
        assertEquals(listOf("g" to WebhookEvent.DOWN), run.check(ok = false, at = minute))
    }

    @Test
    fun `a switched off target or one with no address is skipped entirely`() {
        val run = Run(listOf(target("off") { copy(enabled = false) }, target("blank") { copy(url = "") }))
        assertEquals(emptyList<Any>(), run.check(ok = false, at = minute))
        assertTrue("no track should be created for them", run.state.tracks.isEmpty())
    }

    @Test
    fun `a target added in the middle of an outage hears about it on the next check`() {
        val outcome = WebhookRouting.decide(
            targets = listOf(target()),
            state = WebhookState(),
            monitorId = "m",
            groups = emptyList(),
            check = WebhookRouting.Check(ok = false, consecutiveFailures = 9, degraded = false, at = minute),
            phone = WebhookRouting.Phone(AlertPolicy(failureThreshold = 3), true, false, noon),
            base = WebhookFacts(event = WebhookEvent.DOWN, monitorId = "m"),
        )
        assertEquals(listOf(WebhookEvent.DOWN), outcome.sends.map { it.second.event })
    }

    @Test
    fun `pass through events honour following only`() {
        val all = listOf(
            target("channel") { copy(events = setOf(WebhookEvent.CERTIFICATE)) },
            target("mine") { copy(events = setOf(WebhookEvent.CERTIFICATE), followPhone = true) },
            target("other") { copy(events = setOf(WebhookEvent.DOWN)) },
        )
        assertEquals(
            listOf("channel", "mine"),
            WebhookRouting.passThrough(all, "m", emptyList(), WebhookEvent.CERTIFICATE, phoneAllowed = true).map { it.id },
        )
        assertEquals(
            listOf("channel"),
            WebhookRouting.passThrough(all, "m", emptyList(), WebhookEvent.CERTIFICATE, phoneAllowed = false).map { it.id },
        )
    }

    @Test
    fun `pruning drops the queue and tracks of a deleted target or monitor`() {
        val run = Run(listOf(target("a"), target("b")))
        run.check(ok = false, at = minute)
        val pruned = run.state.pruned(targetIds = setOf("a"), monitorIds = setOf("m"))
        assertEquals(setOf(WebhookState.trackKey("a", "m")), pruned.tracks.keys)
        assertTrue(run.state.pruned(setOf("a", "b"), emptySet()).tracks.isEmpty())
    }
}
