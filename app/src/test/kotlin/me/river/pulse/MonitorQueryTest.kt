package me.river.pulse

import me.river.pulse.domain.ElementTarget
import me.river.pulse.domain.Health
import me.river.pulse.domain.Monitor
import me.river.pulse.domain.MonitorCard
import me.river.pulse.domain.MonitorKind
import me.river.pulse.domain.MonitorQuery
import me.river.pulse.domain.MonitorQuery.Filter
import me.river.pulse.domain.MonitorQuery.Sort
import me.river.pulse.domain.MonitorRuntime
import me.river.pulse.domain.UrgentAlerts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Narrowing the dashboard.
 *
 * The two properties worth pinning are that filtering agrees with the card the
 * user is looking at — a disabled monitor reads PAUSED whatever its last verdict
 * was — and that every ordering is *total*, because the list is re-derived after
 * every check and rows that tie on the sort key would otherwise trade places
 * between frames.
 */
class MonitorQueryTest {

    private fun card(
        name: String,
        health: Health = Health.UP,
        enabled: Boolean = true,
        latency: Long = 100,
        checkedAt: Long = 1_000,
        url: String = "https://$name.example.com",
        urgent: Boolean = false,
        nagging: Boolean = false,
        elements: List<ElementTarget> = emptyList(),
    ) = MonitorCard(
        monitor = Monitor(
            id = name,
            name = name,
            url = url,
            enabled = enabled,
            urgent = urgent,
            kind = if (elements.isEmpty()) MonitorKind.HTTP_STATUS else MonitorKind.WEBSITE_ELEMENT,
            elements = elements,
        ),
        runtime = MonitorRuntime(
            health = health,
            lastLatencyMs = latency,
            lastCheckedAt = checkedAt,
            urgentActive = nagging,
            urgentAcknowledged = false,
        ),
    )

    private fun names(cards: List<MonitorCard>) = cards.map { it.monitor.displayName }

    // ---- search ------------------------------------------------------------

    @Test
    fun `search matches name, host and kind, case-insensitively`() {
        val c = card("Checkout", url = "https://shop.example.com/health")
        assertTrue(MonitorQuery.matches(c, "check"))
        assertTrue(MonitorQuery.matches(c, "CHECKOUT"))
        assertTrue(MonitorQuery.matches(c, "shop"))
        assertTrue(MonitorQuery.matches(c, "status check"))
        assertFalse(MonitorQuery.matches(c, "billing"))
    }

    @Test
    fun `search matches an element nickname`() {
        // Often the only thing the user remembers typing on a page monitor.
        val c = card(
            "Landing",
            elements = listOf(ElementTarget(elementId = "hero", label = "Price banner")),
        )
        assertTrue(MonitorQuery.matches(c, "price"))
    }

    @Test
    fun `a blank query matches everything`() {
        assertTrue(MonitorQuery.matches(card("a"), ""))
        assertTrue(MonitorQuery.matches(card("a"), "   "))
    }

    // ---- filters -----------------------------------------------------------

    @Test
    fun `a disabled monitor filters as paused whatever its last verdict was`() {
        // The card shows PAUSED, so the filter has to agree — otherwise "Problems"
        // would list a monitor whose card is grey.
        val c = card("api", health = Health.DOWN, enabled = false)
        assertTrue(MonitorQuery.keep(c, Filter.PAUSED))
        assertFalse(MonitorQuery.keep(c, Filter.PROBLEMS))
    }

    @Test
    fun `problems means down or degraded`() {
        assertTrue(MonitorQuery.keep(card("a", Health.DOWN), Filter.PROBLEMS))
        assertTrue(MonitorQuery.keep(card("b", Health.DEGRADED), Filter.PROBLEMS))
        assertFalse(MonitorQuery.keep(card("c", Health.UP), Filter.PROBLEMS))
        assertFalse(MonitorQuery.keep(card("d", Health.UNKNOWN), Filter.PROBLEMS))
    }

    @Test
    fun `unacknowledged only matches an urgent outage still waiting`() {
        val waiting = card("a", Health.DOWN, urgent = true, nagging = true)
        val notUrgent = card("b", Health.DOWN, urgent = false, nagging = true)
        assertTrue(MonitorQuery.keep(waiting, Filter.UNACKNOWLEDGED))
        assertFalse(MonitorQuery.keep(notUrgent, Filter.UNACKNOWLEDGED))
    }

    // ---- sorting -----------------------------------------------------------

    @Test
    fun `worst first stays the default and matches the fleet ranking`() {
        val cards = listOf(
            card("zeta", Health.UP),
            card("alpha", Health.DOWN),
            card("beta", Health.DEGRADED),
            card("gamma", Health.PAUSED, enabled = false),
        )
        val sorted = MonitorQuery.apply(cards, MonitorQuery.Spec())
        assertEquals(listOf("alpha", "beta", "zeta", "gamma"), names(sorted))
    }

    @Test
    fun `an unacknowledged urgent outage outranks an ordinary one`() {
        val cards = listOf(
            card("aaa", Health.DOWN),
            card("zzz", Health.DOWN, urgent = true, nagging = true),
        )
        val sorted = MonitorQuery.apply(cards, MonitorQuery.Spec())
        assertEquals(listOf("zzz", "aaa"), names(sorted))
    }

    @Test
    fun `slowest sorts descending and pushes unchecked monitors to the end`() {
        // A latency of 0 means "no reading". Floating those to the top of a
        // "slowest" list would be actively misleading.
        val cards = listOf(
            card("quick", latency = 40),
            card("never", latency = 0, checkedAt = 0),
            card("slow", latency = 3_000),
        )
        val sorted = MonitorQuery.apply(cards, MonitorQuery.Spec(sort = Sort.SLOWEST))
        assertEquals(listOf("slow", "quick", "never"), names(sorted))
    }

    @Test
    fun `stalest puts never-checked monitors first`() {
        val cards = listOf(
            card("fresh", checkedAt = 9_000),
            card("never", checkedAt = 0),
            card("old", checkedAt = 100),
        )
        val sorted = MonitorQuery.apply(cards, MonitorQuery.Spec(sort = Sort.STALEST))
        assertEquals(listOf("never", "old", "fresh"), names(sorted))
    }

    @Test
    fun `every computed ordering breaks ties by name so the list cannot jump`() {
        // Identical on every sort key but the name. Each ordering must still be
        // deterministic, because the list is rebuilt after every completed check.
        //
        // MANUAL is excluded on purpose, not overlooked. It is not a comparator: it
        // returns the store's order verbatim, so feeding it a reversed list correctly
        // yields a reversed list. Its own stability guarantee is a different one —
        // that it changes nothing — and `manual order is store order, untouched`
        // pins that.
        val cards = listOf(card("charlie"), card("alpha"), card("bravo"))
        Sort.entries.filter { it != Sort.MANUAL }.forEach { sort ->
            val once = names(MonitorQuery.apply(cards, MonitorQuery.Spec(sort = sort)))
            val twice = names(MonitorQuery.apply(cards.reversed(), MonitorQuery.Spec(sort = sort)))
            assertEquals("unstable order for $sort", once, twice)
            assertEquals("not name-ordered for $sort", listOf("alpha", "bravo", "charlie"), once)
        }
    }

    @Test
    fun `manual order is a faithful passthrough in both directions`() {
        val cards = listOf(card("charlie"), card("alpha"), card("bravo"))
        val spec = MonitorQuery.Spec(sort = Sort.MANUAL)
        assertEquals(listOf("charlie", "alpha", "bravo"), names(MonitorQuery.apply(cards, spec)))
        assertEquals(
            listOf("bravo", "alpha", "charlie"),
            names(MonitorQuery.apply(cards.reversed(), spec)),
        )
    }

    // ---- composition -------------------------------------------------------

    @Test
    fun `filter and search compose`() {
        val cards = listOf(
            card("api-down", Health.DOWN),
            card("api-up", Health.UP),
            card("shop-down", Health.DOWN),
        )
        val spec = MonitorQuery.Spec(query = "api", filter = Filter.PROBLEMS)
        assertEquals(listOf("api-down"), names(MonitorQuery.apply(cards, spec)))
    }

    @Test
    fun `the default spec knows it is the default`() {
        assertTrue(MonitorQuery.Spec().isDefault)
        assertFalse(MonitorQuery.Spec(query = "a").isDefault)
        assertFalse(MonitorQuery.Spec(filter = Filter.PAUSED).isDefault)
        assertFalse(MonitorQuery.Spec(sort = Sort.NAME).isDefault)
    }

    @Test
    fun `the empty message distinguishes no monitors from none matching`() {
        val fresh = MonitorQuery.emptyMessage(MonitorQuery.Spec(), total = 0)
        val filtered = MonitorQuery.emptyMessage(MonitorQuery.Spec(query = "zzz"), total = 4)
        val allWell = MonitorQuery.emptyMessage(
            MonitorQuery.Spec(filter = Filter.PROBLEMS),
            total = 4,
        )
        assertTrue(fresh.contains("first monitor"))
        assertTrue(filtered.contains("zzz"))
        // An empty "Problems" list is good news and should read like it.
        assertTrue(allWell.contains("Nothing is broken"))
    }

    // ---- manual order ------------------------------------------------------

    @Test
    fun `manual order is store order, untouched`() {
        // The whole design rests on this: no comparator, no position field. Whatever
        // order the store hands over is the order shown.
        val cards = listOf(card("zeta"), card("alpha", Health.DOWN), card("mid"))
        val sorted = MonitorQuery.apply(cards, MonitorQuery.Spec(sort = Sort.MANUAL))
        assertEquals(listOf("zeta", "alpha", "mid"), names(sorted))
    }

    @Test
    fun `manual order still filters and searches`() {
        val cards = listOf(card("zeta"), card("alpha", Health.DOWN), card("mid"))
        val spec = MonitorQuery.Spec(sort = Sort.MANUAL, filter = Filter.PROBLEMS)
        assertEquals(listOf("alpha"), names(MonitorQuery.apply(cards, spec)))
    }

    @Test
    fun `dragging is only offered when nothing is hidden`() {
        // Dropping card 2 above card 5 of a filtered view says nothing about where
        // either belongs among the monitors you cannot see.
        assertTrue(MonitorQuery.canReorder(MonitorQuery.Spec(sort = Sort.MANUAL)))
        assertFalse(MonitorQuery.canReorder(MonitorQuery.Spec(sort = Sort.WORST_FIRST)))
        assertFalse(
            MonitorQuery.canReorder(MonitorQuery.Spec(sort = Sort.MANUAL, query = "api")),
        )
        assertFalse(
            MonitorQuery.canReorder(
                MonitorQuery.Spec(sort = Sort.MANUAL, filter = Filter.PROBLEMS),
            ),
        )
    }

    @Test
    fun `reordered moves an item without losing or duplicating any`() {
        val ids = listOf("a", "b", "c", "d")
        assertEquals(listOf("b", "a", "c", "d"), MonitorQuery.reordered(ids, 0, 1))
        assertEquals(listOf("b", "c", "d", "a"), MonitorQuery.reordered(ids, 0, 3))
        assertEquals(listOf("d", "a", "b", "c"), MonitorQuery.reordered(ids, 3, 0))
        assertEquals(ids, MonitorQuery.reordered(ids, 2, 2))
    }

    @Test
    fun `reordered survives indices that went stale mid-drag`() {
        // The caller is a drag gesture reading a lazy layout; an item can be recycled
        // out from under it, and throwing there would crash on a finger movement.
        val ids = listOf("a", "b", "c")
        assertEquals(ids, MonitorQuery.reordered(ids, -1, 1))
        assertEquals(ids, MonitorQuery.reordered(ids, 0, 9))
        assertEquals(emptyList<String>(), MonitorQuery.reordered(emptyList(), 0, 1))
    }

    @Test
    fun `every sort mode has a label, including manual`() {
        Sort.entries.forEach { assertTrue(it.name, it.label.isNotBlank()) }
        assertEquals("My order", Sort.MANUAL.label)
    }

    @Test
    fun `an acknowledged urgent outage no longer counts as unacknowledged`() {
        val acked = MonitorCard(
            monitor = Monitor(id = "a", name = "a", url = "https://a.example.com", urgent = true),
            runtime = MonitorRuntime(health = Health.DOWN).withUrgentState(
                UrgentAlerts.State(active = true, acknowledged = true, lastAlertAt = 1),
            ),
        )
        assertFalse(MonitorQuery.keep(acked, Filter.UNACKNOWLEDGED))
    }
}
