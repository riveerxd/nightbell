package me.river.nightbell

import me.river.nightbell.domain.CheckProgress
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.UdpPlan
import me.river.nightbell.domain.UdpProbe
import me.river.nightbell.domain.UdpWatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The figures the Test now panel shows for a UDP monitor, before and while it runs. */
class UdpPlanTest {

    private fun monitor(probe: UdpProbe, attempts: Int = 3, timeout: Int = 15) =
        Monitor(id = "u", kind = MonitorKind.UDP_PORT, udp = UdpWatch(probe = probe, attempts = attempts), timeoutSeconds = timeout)

    @Test
    fun `silent mode at the defaults is three full tries and a ping`() {
        val plan = UdpPlan.of(monitor(UdpProbe.SILENT))
        assertTrue(plan.silent)
        assertEquals(60_000L, plan.worstCaseMs)
    }

    @Test
    fun `a probe that expects an answer has no ping stage`() {
        val plan = UdpPlan.of(monitor(UdpProbe.DNS, attempts = 2, timeout = 4))
        assertFalse(plan.silent)
        assertEquals(8_000L, plan.worstCaseMs)
    }

    @Test
    fun `the plan clamps the same way the checker does`() {
        val plan = UdpPlan.of(monitor(UdpProbe.SILENT, attempts = 99, timeout = 0))
        assertEquals(UdpWatch.MAX_ATTEMPTS, plan.tries)
        assertEquals(1_000L, plan.timeoutMs)
    }

    @Test
    fun `time left counts the rest of this try, the tries after it and the ping`() {
        val plan = UdpPlan.of(monitor(UdpProbe.SILENT, attempts = 3, timeout = 10))
        val second = CheckProgress.UdpTry(attempt = 2, tries = 3, timeoutMs = 10_000, sentAt = 0, silent = true)
        assertEquals(4_000L + 10_000L + 10_000L, plan.remainingMs(second, nowMs = 6_000))
        val ping = CheckProgress.UdpPing(tries = 3, timeoutMs = 10_000, sentAt = 0)
        assertEquals(7_000L, plan.remainingMs(ping, nowMs = 3_000))
        assertEquals(0L, plan.remainingMs(ping, nowMs = 99_000))
    }
}
