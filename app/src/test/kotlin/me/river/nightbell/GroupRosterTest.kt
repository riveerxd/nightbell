package me.river.nightbell

import me.river.nightbell.domain.GroupRoster
import me.river.nightbell.domain.Health
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorCard
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.MonitorRuntime
import me.river.nightbell.domain.PingReading
import me.river.nightbell.domain.Sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The words a shut group card uses for its members.
 *
 * The redesign exists because a shut group said "1 of 3 is down" and never
 * which one. These pin the two halves of the fix: the problem members are
 * named, in an order that does not shuffle, and everything else is one line
 * that adds up to the rest of the group.
 */
class GroupRosterTest {

    private val now = 10_000_000L

    private fun card(
        id: String,
        health: Health = Health.UP,
        enabled: Boolean = true,
        latency: Long = 180,
        samples: List<Sample> = emptyList(),
        mutedUntil: Long = 0L,
    ) = MonitorCard(
        monitor = Monitor(id = id, name = id, url = "https://$id.example.com", kind = MonitorKind.HTTP_STATUS, enabled = enabled),
        runtime = MonitorRuntime(health = health, lastLatencyMs = latency, samples = samples, mutedUntil = mutedUntil),
    )

    // ---- the peek -----------------------------------------------------------

    @Test
    fun aHealthyGroupHasNothingToNameBecauseTheHeaderAlreadySaysSo() {
        assertNull(GroupRoster.peek(listOf(card("a"), card("b"))))
    }

    @Test
    fun anUncheckedOrPausedMemberIsNotAProblemWorthNaming() {
        assertNull(
            GroupRoster.peek(
                listOf(card("a", Health.UNKNOWN), card("b", enabled = false), card("c")),
            ),
        )
    }

    @Test
    fun theDownMemberIsNamedAndTheRestFoldIntoOneLine() {
        val peek = GroupRoster.peek(listOf(card("site", Health.DOWN), card("repo"), card("feed")))!!
        assertEquals(listOf("site"), peek.problems.map { it.monitor.id })
        assertEquals("2 up", peek.rest)
        assertEquals(Health.UP, peek.restHealth)
    }

    @Test
    fun downComesBeforeSlowAndOtherwiseTheMembersKeepTheirOrder() {
        val peek = GroupRoster.peek(
            listOf(
                card("slow1", Health.DEGRADED),
                card("down1", Health.DOWN),
                card("ok"),
                card("down2", Health.DOWN),
                card("slow2", Health.DEGRADED),
            ),
        )!!
        assertEquals(listOf("down1", "down2", "slow1", "slow2"), peek.problems.map { it.monitor.id })
    }

    @Test
    fun aPausedMemberIsCountedInTheRestLineNotHidden() {
        // The group test's own case: one down, one paused. The pause is a
        // decision about that monitor, so it is said, and it does not get to
        // hide the outage beside it.
        val peek = GroupRoster.peek(listOf(card("site", Health.DOWN), card("repo", enabled = false)))!!
        assertEquals(listOf("site"), peek.problems.map { it.monitor.id })
        assertEquals("1 paused", peek.rest)
        assertEquals(Health.PAUSED, peek.restHealth)
    }

    @Test
    fun everyKindOfRestIsCountedSoTheLinesAddUpToTheGroup() {
        val peek = GroupRoster.peek(
            listOf(
                card("down", Health.DOWN),
                card("up1"),
                card("up2"),
                card("paused", enabled = false),
                card("new", Health.UNKNOWN),
            ),
        )!!
        assertEquals("2 up, 1 paused, 1 not checked yet", peek.rest)
    }

    @Test
    fun anAllDownGroupHasNoRestLine() {
        val peek = GroupRoster.peek(listOf(card("a", Health.DOWN), card("b", Health.DOWN)))!!
        assertEquals(2, peek.problems.size)
        assertEquals("", peek.rest)
    }

    @Test
    fun pastTheLimitTheExtraProblemsAreCountedRatherThanDropped() {
        val members = (1..6).map { card("d$it", Health.DOWN) } + card("ok")
        val peek = GroupRoster.peek(members)!!
        assertEquals(GroupRoster.PEEK_LIMIT, peek.problems.size)
        assertEquals("2 more down or slow, 1 up", peek.rest)
        // The dot beside that line is red, because it is carrying an outage.
        assertEquals(Health.DOWN, peek.restHealth)
    }

    // ---- one member in words ------------------------------------------------

    @Test
    fun downSaysForHowLongFromTheFirstFailedCheck() {
        val samples = listOf(
            Sample(at = now - 30 * 60_000, ok = true, latencyMs = 180),
            Sample(at = now - 12 * 60_000, ok = false, latencyMs = 0),
            Sample(at = now - 2 * 60_000, ok = false, latencyMs = 0),
        )
        assertEquals("Down 12m", GroupRoster.detail(card("a", Health.DOWN, samples = samples), now))
    }

    @Test
    fun downWithNoStoredFailureSaysDownAndInventsNoDuration() {
        assertEquals("Down", GroupRoster.detail(card("a", Health.DOWN), now))
    }

    @Test
    fun slowCarriesTheReadingThatMadeItSlow() {
        assertEquals("Slow, 3.1 s", GroupRoster.detail(card("a", Health.DEGRADED, latency = 3_100), now))
    }

    @Test
    fun upCarriesItsLatencyAndFallsBackToUpWithoutOne() {
        assertEquals("180 ms", GroupRoster.detail(card("a"), now))
        assertEquals("Up", GroupRoster.detail(card("a", latency = 0), now))
    }

    @Test
    fun aPingUsesItsOwnPrecision() {
        val samples = listOf(
            Sample(at = now - 60_000, ok = true, latencyMs = 0, ping = PingReading(sent = 3, received = 3, avgUs = 280)),
        )
        assertEquals("0.28 ms", GroupRoster.detail(card("a", latency = 0, samples = samples), now))
    }

    @Test
    fun pausedWinsOverWhateverTheLastCheckSaid() {
        assertEquals("Paused", GroupRoster.detail(card("a", Health.DOWN, enabled = false), now))
    }

    @Test
    fun aMutedProblemSaysItIsMutedSoTheRowDoesNotLookLikeItWillPage() {
        assertEquals("Down, muted", GroupRoster.detail(card("a", Health.DOWN, mutedUntil = now + 60_000), now))
        // A mute on a healthy monitor is not news on a one-line row.
        assertEquals("180 ms", GroupRoster.detail(card("a", mutedUntil = now + 60_000), now))
    }

    @Test
    fun anUncheckedMemberSaysSo() {
        assertEquals("Not checked yet", GroupRoster.detail(card("a", Health.UNKNOWN), now))
    }
}
