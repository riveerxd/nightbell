package me.river.nightbell

import me.river.nightbell.domain.Health
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.MonitorTemplates
import me.river.nightbell.domain.NetworkWatch
import me.river.nightbell.domain.Outages
import me.river.nightbell.domain.PingReading
import me.river.nightbell.domain.SameHost
import me.river.nightbell.domain.Sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The outage list and the same-host reading on the detail screen. Issue 21. */
class OutagesAndSameHostTest {

    private val minute = 60_000L

    private fun s(at: Long, ok: Boolean, note: String = "", ping: PingReading? = null) =
        Sample(at = at * minute, ok = ok, latencyMs = 5, note = note, ping = ping)

    @Test
    fun `runs of failures become outages, newest first`() {
        val summary = Outages.of(
            listOf(
                s(0, true), s(1, false, "No reply"), s(2, false), s(3, true),
                s(4, true), s(5, false, "Refused"), s(6, true),
            ),
        )
        assertEquals(2, summary.outages.size)
        assertEquals(5 * minute, summary.outages[0].startAt)
        assertEquals(minute, summary.outages[0].durationMs(0))
        assertEquals("Refused", summary.outages[0].reason)
        assertEquals(2, summary.outages[1].failedChecks)
        assertEquals(2 * minute, summary.outages[1].durationMs(0))
        assertEquals(3 * minute, summary.downMs(0))
        assertEquals(6 * minute, summary.lastSuccessAt)
        assertEquals(5 * minute, summary.lastFailureAt)
        assertEquals(0L, summary.sinceAt)
    }

    @Test
    fun `an outage still going runs up to now`() {
        val summary = Outages.of(listOf(s(0, true), s(10, false), s(11, false)))
        val open = summary.outages.single()
        assertTrue(open.ongoing)
        assertEquals(20 * minute, open.durationMs(30 * minute))
    }

    @Test
    fun `no history is empty, and an all green one has no outages`() {
        assertTrue(Outages.of(emptyList()).isEmpty)
        val green = Outages.of(listOf(s(0, true), s(1, true)))
        assertTrue(green.outages.isEmpty())
        assertEquals(0L, green.lastFailureAt)
    }

    @Test
    fun `loss is counted across every stored ping`() {
        val samples = listOf(
            s(0, true, ping = PingReading(3, 3)),
            s(1, true, ping = PingReading(3, 2)),
            s(2, false, ping = PingReading(4, 0)),
        )
        assertEquals(50, Outages.lossPercent(samples))
        assertNull(Outages.lossPercent(listOf(s(0, true))))
    }

    @Test
    fun `spans read like a person would say them`() {
        assertEquals("under 1s", Outages.span(400))
        assertEquals("40s", Outages.span(40_000))
        assertEquals("4m 20s", Outages.span(260_000))
        assertEquals("45m", Outages.span(45 * minute))
        assertEquals("3h 12m", Outages.span(192 * minute))
        assertEquals("2d 3h", Outages.span((51 * 60) * minute))
    }

    // ---- same host ----------------------------------------------------------

    private val ping = Monitor(id = "p", kind = MonitorKind.PING, network = NetworkWatch(host = "192.0.2.10"))
    private val https = Monitor(id = "t", kind = MonitorKind.TCP_PORT, network = NetworkWatch(host = "192.0.2.10", port = 443))
    private val ssh = Monitor(id = "s", kind = MonitorKind.TCP_PORT, network = NetworkWatch(host = "192.0.2.10", port = 22))
    private val elsewhere = Monitor(id = "e", kind = MonitorKind.PING, network = NetworkWatch(host = "192.0.2.11"))
    private val site = Monitor(id = "w", name = "Shop", url = "https://192.0.2.10/")

    @Test
    fun `siblings share the host and leave out the monitor itself`() {
        val all = listOf(ping, https, ssh, elsewhere, site)
        assertEquals(listOf("t", "s", "w"), SameHost.siblings(ping, all).map { it.id })
        assertEquals(listOf("p", "s", "w"), SameHost.siblings(https, all).map { it.id })
        assertTrue(SameHost.siblings(elsewhere, all).isEmpty())
    }

    @Test
    fun `the issue's four rows`() {
        assertEquals(
            "The host answers ping and the other check answers too.",
            SameHost.reading(listOf(ping to Health.UP, https to Health.UP)),
        )
        assertEquals(
            "The host answers ping, but port 443 is down. The network is fine; look at the service.",
            SameHost.reading(listOf(ping to Health.UP, https to Health.DOWN)),
        )
        assertEquals(
            "No ping reply, but port 443 answers. ICMP is probably filtered on the way.",
            SameHost.reading(listOf(ping to Health.DOWN, https to Health.UP)),
        )
        assertEquals(
            "Neither ping nor port 443 answers. The host, or the route to it, is down.",
            SameHost.reading(listOf(ping to Health.DOWN, https to Health.DOWN)),
        )
    }

    @Test
    fun `several services are listed, and a slow one still answered`() {
        assertEquals(
            "The host answers ping, but port 443 and port 22 are down. The network is fine; look at the service.",
            SameHost.reading(listOf(ping to Health.DEGRADED, https to Health.DOWN, ssh to Health.DOWN)),
        )
        assertEquals(
            "No ping reply, but port 22 and Shop answer. ICMP is probably filtered on the way.",
            SameHost.reading(listOf(ping to Health.DOWN, https to Health.DOWN, ssh to Health.UP, site to Health.DEGRADED)),
        )
    }

    @Test
    fun `no pair to read is no sentence`() {
        assertNull("no ping", SameHost.reading(listOf(https to Health.UP, ssh to Health.DOWN)))
        assertNull("only ping", SameHost.reading(listOf(ping to Health.UP)))
        assertNull("not checked yet", SameHost.reading(listOf(ping to Health.UNKNOWN, https to Health.UP)))
        assertNull("paused", SameHost.reading(listOf(ping to Health.UP, https to Health.PAUSED)))
    }

    @Test
    fun `adding a check on a host starts from the host and the complementary kind`() {
        val blank = Monitor(id = "new")
        val fromPing = MonitorTemplates.sameHost(blank, ping)
        assertEquals(MonitorKind.TCP_PORT, fromPing.kind)
        assertEquals("192.0.2.10", fromPing.network.host)
        assertEquals("", fromPing.url)

        val fromSite = MonitorTemplates.sameHost(blank, Monitor(id = "w", url = "https://user:pw@shop.example:8443/health"))
        assertEquals(MonitorKind.PING, fromSite.kind)
        assertEquals("shop.example", fromSite.network.host)
        assertEquals("the origin, without the password", "https://shop.example:8443", fromSite.url)

        assertEquals("p", MonitorTemplates.sameHostSource(MonitorTemplates.sameHostId("p")))
        assertNull(MonitorTemplates.sameHostSource("website"))
        assertNull(MonitorTemplates.sameHostSource(null))
    }
}
