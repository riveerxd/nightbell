package me.river.nightbell

import me.river.nightbell.domain.PagerReadiness
import me.river.nightbell.domain.PagerReadiness.Requirement
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
        assertFalse(PagerReadiness.shouldGate(muted, silenced = false))
    }

    @Test
    fun `only an explicit silence stops the gate returning`() {
        // This is the whole point of the change. Skipping the screen used to set
        // the flag that stops it coming back, so one tap on the way past cost the
        // full-screen permission permanently: that grant is reachable from this
        // screen and from nowhere else in the app.
        val missing = state(dnd = false)
        assertTrue(PagerReadiness.shouldGate(missing, silenced = false))
        assertFalse(PagerReadiness.shouldGate(missing, silenced = true))
        // And it is not a one-way door either: clearing the flag brings the check
        // back, which is what the switch in Settings does.
        assertTrue(PagerReadiness.shouldGate(missing, silenced = false))
    }

    @Test
    fun `a grant lost after silencing still leaves the gate silent`() {
        // Silence means silence. Anyone who said "don't ask again" and then had
        // a grant revoked by the system gets nothing at launch, which is why the
        // switch that reverses it has to be somewhere they can find it.
        assertFalse(PagerReadiness.shouldGate(state(notifications = false), silenced = true))
        assertFalse(PagerReadiness.shouldGate(state(fullScreen = false), silenced = true))
    }

    @Test
    fun `a fully granted install is never gated`() {
        assertFalse(PagerReadiness.shouldGate(state(), silenced = false))
        assertEquals(4, state().grantedCount)
        assertEquals(4, state().total)
    }
}
