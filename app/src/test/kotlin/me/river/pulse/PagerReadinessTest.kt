package me.river.pulse

import me.river.pulse.domain.PagerReadiness
import me.river.pulse.domain.PagerReadiness.Requirement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PagerReadinessTest {

    private fun state(
        notifications: Boolean = true,
        battery: Boolean = true,
        fullScreen: Boolean = true,
        dnd: Boolean = true,
        audible: Boolean = true,
    ) = PagerReadiness.State(notifications, battery, fullScreen, dnd, audible)

    @Test
    fun `nothing granted asks for notifications first`() {
        val missing = state(false, false, false, false)
        assertEquals(Requirement.NOTIFICATIONS, missing.next)
        assertEquals(0, missing.grantedCount)
        assertFalse(missing.canPageAtAll)
    }

    /**
     * The two in-app dialogs come before the two that navigate away, so the
     * cheap grants are done before the user starts bouncing between apps.
     */
    @Test
    fun `the walkthrough does the in-app dialogs before the settings trips`() {
        val order = Requirement.entries.map { it.leavesTheApp }
        assertEquals(listOf(false, false, true, true), order)
    }

    @Test
    fun `only notifications is fatal`() {
        assertEquals(
            listOf(Requirement.NOTIFICATIONS),
            Requirement.entries.filter { it.essential },
        )
        // Everything else missing still leaves a pager that can reach someone.
        assertTrue(state(notifications = true, battery = false, fullScreen = false, dnd = false).canPageAtAll)
    }

    @Test
    fun `the walkthrough advances as each grant lands`() {
        assertEquals(Requirement.BATTERY_EXEMPTION, state(battery = false, fullScreen = false).next)
        assertEquals(Requirement.FULL_SCREEN, state(fullScreen = false).next)
        assertEquals(Requirement.DND_BYPASS, state(dnd = false).next)
        assertEquals(null, state().next)
        assertTrue(state().allGranted)
    }

    /** Audibility is reported, never gated on: it is not a permission. */
    @Test
    fun `a muted phone does not count as a missing grant`() {
        val muted = state(audible = false)
        assertTrue(muted.allGranted)
        assertFalse(PagerReadiness.shouldGate(muted, dismissed = false))
    }

    @Test
    fun `the gate never returns once dismissed`() {
        val missing = state(dnd = false)
        assertTrue(PagerReadiness.shouldGate(missing, dismissed = false))
        assertFalse(PagerReadiness.shouldGate(missing, dismissed = true))
    }

    @Test
    fun `a fully granted install is never gated`() {
        assertFalse(PagerReadiness.shouldGate(state(), dismissed = false))
        assertEquals(4, state().grantedCount)
        assertEquals(4, state().total)
    }
}
