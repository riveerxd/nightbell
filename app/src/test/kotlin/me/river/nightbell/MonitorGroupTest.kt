package me.river.nightbell

import me.river.nightbell.domain.GroupIconChoice
import me.river.nightbell.domain.GroupMark
import me.river.nightbell.domain.GroupRollup
import me.river.nightbell.domain.Health
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorCard
import me.river.nightbell.domain.MonitorGroup
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.MonitorRuntime
import me.river.nightbell.domain.iconOriginOf
import me.river.nightbell.domain.mark
import me.river.nightbell.domain.sameIconSite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a group of monitors reports.
 *
 * The roll-up is the whole feature: a group card exists to answer "is this
 * operational" in one line, and every way that line could lie is worth pinning.
 * Three properties carry most of the weight, the worst member decides the
 * verdict, a *pause* on one member is not allowed to decide it, and a group plus
 * the ungrouped remainder is always exactly the fleet.
 */
class MonitorGroupTest {

    private fun card(
        id: String,
        health: Health = Health.UP,
        enabled: Boolean = true,
        latency: Long = 100,
        checkedAt: Long = 1_000,
        url: String = "https://$id.example.com",
        urgent: Boolean = false,
        nagging: Boolean = false,
        checking: Boolean = false,
    ) = MonitorCard(
        monitor = Monitor(
            id = id,
            name = id,
            url = url,
            kind = MonitorKind.HTTP_STATUS,
            enabled = enabled,
            urgent = urgent,
        ),
        runtime = MonitorRuntime(
            health = health,
            lastLatencyMs = latency,
            lastCheckedAt = checkedAt,
            urgentActive = nagging,
            urgentAcknowledged = false,
        ),
        checking = checking,
    )

    private fun group(vararg members: String, title: String = "Nightbell") =
        MonitorGroup(id = "g1", title = title, memberIds = members.toList())

    // ---- the verdict --------------------------------------------------------

    @Test
    fun aGroupOfHealthyMonitorsIsOperational() {
        val rolled = GroupRollup.rolled(group("a", "b"), listOf(card("a"), card("b")))
        assertEquals(Health.UP, rolled.health)
        assertTrue(rolled.operational)
        assertEquals("All 2 operational", rolled.headline)
    }

    @Test
    fun oneDownMemberTakesTheWholeGroupDown() {
        val rolled = GroupRollup.rolled(
            group("a", "b"),
            listOf(card("a"), card("b", health = Health.DOWN)),
        )
        assertEquals(Health.DOWN, rolled.health)
        assertEquals("1 of 2 is down", rolled.headline)
    }

    @Test
    fun downOutranksSlow() {
        val rolled = GroupRollup.rolled(
            group("a", "b", "c"),
            listOf(
                card("a"),
                card("b", health = Health.DEGRADED),
                card("c", health = Health.DOWN),
            ),
        )
        assertEquals(Health.DOWN, rolled.health)
        // The count in the headline is the *down* count, not the trouble count:
        // "2 of 3 are down" would be a claim about c that is not true.
        assertEquals("1 of 3 is down", rolled.headline)
    }

    @Test
    fun slowIsReportedWhenNothingIsDown() {
        val rolled = GroupRollup.rolled(
            group("a", "b"),
            listOf(card("a"), card("b", health = Health.DEGRADED)),
        )
        assertEquals(Health.DEGRADED, rolled.health)
        assertEquals("1 of 2 is slow", rolled.headline)
    }

    /**
     * The one that would have shipped as a bug.
     *
     * Pausing one monitor in a group is a decision about that monitor. Letting it
     * win the roll-up would have a group read "paused" while its other members
     * were being checked, and worse, it could hide a live outage behind a pause
     * somebody set last week.
     */
    @Test
    fun aPausedMemberDoesNotDecideTheGroup() {
        val rolled = GroupRollup.rolled(
            group("a", "b"),
            listOf(card("a", enabled = false), card("b", health = Health.DOWN)),
        )
        assertEquals(Health.DOWN, rolled.health)
        assertEquals(1, rolled.paused)
        assertEquals("1 of 2 is down", rolled.headline)
    }

    @Test
    fun aPausedMemberAlongsideHealthyOnesStillReadsOperational() {
        val rolled = GroupRollup.rolled(
            group("a", "b"),
            listOf(card("a", enabled = false), card("b")),
        )
        assertEquals(Health.UP, rolled.health)
    }

    @Test
    fun aGroupWithEveryMemberPausedIsPaused() {
        val rolled = GroupRollup.rolled(
            group("a", "b"),
            listOf(card("a", enabled = false), card("b", enabled = false)),
        )
        assertEquals(Health.PAUSED, rolled.health)
        assertEquals("All 2 paused", rolled.headline)
    }

    @Test
    fun anUncheckedMemberIsSaidToBeUnchecked() {
        val rolled = GroupRollup.rolled(
            group("a", "b"),
            listOf(card("a"), card("b", health = Health.UNKNOWN)),
        )
        assertEquals(Health.UNKNOWN, rolled.health)
        assertEquals("1 of 2 not checked yet", rolled.headline)
    }

    @Test
    fun anEmptyGroupSaysSoRatherThanClaimingAVerdict() {
        val rolled = GroupRollup.rolled(group(), emptyList())
        assertEquals(Health.UNKNOWN, rolled.health)
        assertEquals("Nothing in this group", rolled.headline)
    }

    // ---- the numbers on the card -------------------------------------------

    @Test
    fun theLatencyShownIsTheSlowestMemberAndTheFreshnessIsTheStalest() {
        val rolled = GroupRollup.rolled(
            group("a", "b"),
            listOf(
                card("a", latency = 120, checkedAt = 9_000),
                card("b", latency = 940, checkedAt = 3_000),
            ),
        )
        // An average would describe nothing anybody is watching.
        assertEquals(940L, rolled.slowestLatencyMs)
        // A group is only as freshly checked as its stalest member.
        assertEquals(3_000L, rolled.lastCheckedAt)
    }

    @Test
    fun aMemberMidCheckMakesTheGroupReadAsChecking() {
        val rolled = GroupRollup.rolled(
            group("a", "b"),
            listOf(card("a"), card("b", checking = true)),
        )
        assertTrue(rolled.checking)
    }

    @Test
    fun unacknowledgedUrgentMembersAreCounted() {
        val rolled = GroupRollup.rolled(
            group("a", "b", "c"),
            listOf(
                card("a"),
                card("b", health = Health.DOWN, urgent = true, nagging = true),
                card("c", health = Health.DOWN, urgent = true, nagging = true),
            ),
        )
        assertEquals(2, rolled.urgentPending)
    }

    // ---- membership --------------------------------------------------------

    @Test
    fun everyMonitorIsDrawnExactlyOnce() {
        val cards = listOf(card("a"), card("b"), card("c"))
        val groups = listOf(group("a", "b"))
        val grouped = GroupRollup.of(groups, cards).flatMap { it.members }.map { it.monitor.id }
        val loose = GroupRollup.ungrouped(groups, cards).map { it.monitor.id }
        assertEquals(listOf("a", "b"), grouped)
        assertEquals(listOf("c"), loose)
        assertEquals(cards.size, grouped.size + loose.size)
    }

    /**
     * A monitor claimed by two groups lands in the first only.
     *
     * The store's own writes cannot produce this, see `GroupRollup.assign`, but
     * an imported backup or a hand-edited store can, and drawing a card twice
     * would double-count it in the list and in both roll-ups.
     */
    @Test
    fun aMonitorClaimedTwiceIsDrawnInTheFirstGroupOnly() {
        val cards = listOf(card("a"), card("b"))
        val groups = listOf(
            MonitorGroup(id = "g1", title = "First", memberIds = listOf("a", "b")),
            MonitorGroup(id = "g2", title = "Second", memberIds = listOf("b")),
        )
        val rolled = GroupRollup.of(groups, cards)
        assertEquals(listOf("a", "b"), rolled[0].members.map { it.monitor.id })
        assertEquals(emptyList<String>(), rolled[1].members.map { it.monitor.id })
        assertEquals(emptyList<String>(), GroupRollup.ungrouped(groups, cards).map { it.monitor.id })
    }

    @Test
    fun aDeletedMemberIsDroppedRatherThanCountedAsUnchecked() {
        val rolled = GroupRollup.of(listOf(group("a", "gone")), listOf(card("a"))).single()
        assertEquals(1, rolled.total)
        assertEquals(0, rolled.unknown)
        assertEquals(Health.UP, rolled.health)
        assertEquals("Operational", rolled.headline)
    }

    @Test
    fun membersAreDrawnInTheStoredMembershipOrder() {
        val cards = listOf(card("a"), card("b"), card("c"))
        val rolled = GroupRollup.of(listOf(group("c", "a")), cards).single()
        assertEquals(listOf("c", "a"), rolled.members.map { it.monitor.id })
    }

    // ---- assignment --------------------------------------------------------

    @Test
    fun assigningAMonitorTakesItOutOfWhicheverGroupHadIt() {
        val groups = listOf(
            MonitorGroup(id = "g1", memberIds = listOf("a", "b")),
            MonitorGroup(id = "g2", memberIds = listOf("c")),
        )
        val next = GroupRollup.assign(groups, "g2", listOf("c", "b"), setOf("a", "b", "c"))
        assertEquals(listOf("a"), next[0].memberIds)
        assertEquals(listOf("c", "b"), next[1].memberIds)
    }

    @Test
    fun assignmentDropsIdsWithNoMonitorBehindThem() {
        val groups = listOf(MonitorGroup(id = "g1"))
        val next = GroupRollup.assign(groups, "g1", listOf("a", "ghost"), setOf("a"))
        assertEquals(listOf("a"), next.single().memberIds)
    }

    @Test
    fun assignmentDeduplicatesARepeatedId() {
        val groups = listOf(MonitorGroup(id = "g1"))
        val next = GroupRollup.assign(groups, "g1", listOf("a", "a"), setOf("a"))
        assertEquals(listOf("a"), next.single().memberIds)
    }

    @Test
    fun untouchedGroupsAreLeftAloneRatherThanRebuilt() {
        val other = MonitorGroup(id = "g2", memberIds = listOf("c"))
        val groups = listOf(MonitorGroup(id = "g1"), other)
        val next = GroupRollup.assign(groups, "g1", listOf("a"), setOf("a", "c"))
        assertSame(other, next[1])
    }

    @Test
    fun removingAMonitorClearsItFromEveryGroup() {
        val groups = listOf(
            MonitorGroup(id = "g1", memberIds = listOf("a", "b")),
            MonitorGroup(id = "g2", memberIds = listOf("b", "c")),
        )
        val next = GroupRollup.withoutMonitor(groups, "b")
        assertEquals(listOf("a"), next[0].memberIds)
        assertEquals(listOf("c"), next[1].memberIds)
    }

    // ---- the suggested title ----------------------------------------------

    @Test
    fun theSuggestedTitleIsTheSharedLeadingWords() {
        val suggestion = GroupRollup.suggestedTitle(
            listOf(
                Monitor(id = "1", name = "Nightbell website"),
                Monitor(id = "2", name = "Nightbell repository"),
            ),
        )
        assertEquals("Nightbell", suggestion)
    }

    @Test
    fun theSuggestedTitleIgnoresCaseWhenComparingButKeepsTheFirstSpelling() {
        val suggestion = GroupRollup.suggestedTitle(
            listOf(
                Monitor(id = "1", name = "Nightbell app"),
                Monitor(id = "2", name = "nightbell api"),
            ),
        )
        assertEquals("Nightbell", suggestion)
    }

    @Test
    fun namesWithNothingInCommonFallBackToTheCount() {
        val suggestion = GroupRollup.suggestedTitle(
            listOf(
                Monitor(id = "1", name = "Checkout"),
                Monitor(id = "2", name = "Warehouse"),
            ),
        )
        assertEquals("2 monitors", suggestion)
    }

    @Test
    fun aGroupWithoutATitleStillHasSomethingToDraw() {
        assertEquals("Untitled group", MonitorGroup(id = "g", title = "   ").displayTitle)
    }
    // ---- where the mark comes from -----------------------------------------

    private val siteA = "https://nightbell.app/status"
    private val siteB = "https://github.com/river/nightbell"

    @Test
    fun anOriginIsTheHostAndPortAndNothingElse() {
        assertEquals("nightbell.app", iconOriginOf("https://nightbell.app/status?x=1"))
        assertEquals("nightbell.app", iconOriginOf("http://Nightbell.App"))
        assertEquals("box.local:8443", iconOriginOf("https://box.local:8443/health"))
        // Credentials are not part of an origin, and an internal box may carry them.
        assertEquals("box.local", iconOriginOf("https://user:pw@box.local/health"))
        assertEquals("", iconOriginOf("   "))
    }

    @Test
    fun twoPagesOfOneSiteShareAMark() {
        assertTrue(sameIconSite("https://nightbell.app/a", "https://nightbell.app/b"))
        assertFalse(sameIconSite(siteA, siteB))
        // Blank is not "the same as blank": there is no site here to share.
        assertFalse(sameIconSite("", ""))
    }

    /**
     * A group written before the picker existed keeps the behaviour it had.
     *
     * [GroupIconChoice.AUTO] is what those decode to, and it has to mean exactly
     * the old rule, picture first, then the named site, then the first member.
     */
    @Test
    fun autoIsTheOriginalPrecedenceExactly() {
        val bare = MonitorGroup(id = "g", memberIds = listOf("a"))
        assertEquals(GroupMark.Site(siteA), bare.mark(listOf(siteA, siteB)))
        assertEquals(
            GroupMark.Site(siteB),
            bare.copy(iconUrl = siteB).mark(listOf(siteA)),
        )
        assertEquals(
            GroupMark.Picture,
            bare.copy(iconUrl = siteB, iconImage = "AAAA").mark(listOf(siteA)),
        )
        assertEquals(GroupMark.Glyph, bare.mark(emptyList()))
    }

    /**
     * The reason the choice had to be stored at all.
     *
     * With "picture wins if there is one", tapping a site tile while a picture
     * existed did nothing visible. An explicit SITE has to beat a picture that is
     * still sitting in the group waiting to be switched back to.
     */
    @Test
    fun choosingASiteBeatsAPictureThatIsStillStored() {
        val group = MonitorGroup(
            id = "g",
            iconUrl = siteB,
            iconImage = "AAAA",
            iconChoice = GroupIconChoice.SITE,
        )
        assertEquals(GroupMark.Site(siteB), group.mark(listOf(siteA)))
        // And the picture is still there to go back to.
        assertEquals(
            GroupMark.Picture,
            group.copy(iconChoice = GroupIconChoice.PICTURE).mark(listOf(siteA)),
        )
    }

    @Test
    fun choosingASiteWithoutNamingOneFallsBackToTheFirstMember() {
        val group = MonitorGroup(id = "g", iconChoice = GroupIconChoice.SITE)
        assertEquals(GroupMark.Site(siteA), group.mark(listOf(siteA, siteB)))
    }

    /**
     * A choice of PICTURE with no picture must not draw nothing.
     *
     * Reachable by import: a backup read by an older build drops `iconImage` and
     * keeps `iconChoice`, so this combination arrives from outside.
     */
    @Test
    fun aPictureChoiceWithNoPictureFallsThroughRatherThanGoingBlank() {
        val group = MonitorGroup(
            id = "g",
            iconUrl = siteB,
            iconChoice = GroupIconChoice.PICTURE,
        )
        assertEquals(GroupMark.Site(siteB), group.mark(listOf(siteA)))
        assertEquals(
            GroupMark.Glyph,
            group.copy(iconUrl = "").mark(emptyList()),
        )
    }

    @Test
    fun membersWithNoUrlAreSkippedRatherThanChosen() {
        val group = MonitorGroup(id = "g", iconChoice = GroupIconChoice.SITE)
        assertEquals(GroupMark.Site(siteB), group.mark(listOf("", "  ", siteB)))
    }
}
