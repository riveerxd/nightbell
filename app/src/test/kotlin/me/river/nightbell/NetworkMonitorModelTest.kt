package me.river.nightbell

import kotlinx.serialization.json.Json
import me.river.nightbell.domain.AlertPolicy
import me.river.nightbell.domain.DataSaver
import me.river.nightbell.domain.FailureKind
import me.river.nightbell.domain.IpFamily
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.MonitorRuntime
import me.river.nightbell.domain.NetworkWatch
import me.river.nightbell.domain.PingReading
import me.river.nightbell.domain.Reachability
import me.river.nightbell.domain.Sample
import me.river.nightbell.domain.Validation
import me.river.nightbell.domain.WebhookEvent
import me.river.nightbell.domain.WebhookFacts
import me.river.nightbell.domain.WebhookPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Storage, validation and the words around the two new kinds. Issue 21. */
class NetworkMonitorModelTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun ping(host: String = "192.0.2.10", packets: Int = 3) =
        Monitor(id = "p", kind = MonitorKind.PING, network = NetworkWatch(host = host, packets = packets))

    private fun tcp(host: String = "db.lan", port: Int = 5432) =
        Monitor(id = "t", kind = MonitorKind.TCP_PORT, network = NetworkWatch(host = host, port = port))

    @Test
    fun `both kinds survive a round trip with their names on the wire`() {
        val monitors = listOf(ping(), tcp().copy(network = NetworkWatch("2001:db8::1", 22, family = IpFamily.V6)))
        val text = json.encodeToString(monitors)
        assertTrue(text, text.contains("\"kind\":\"ping\""))
        assertTrue(text, text.contains("\"kind\":\"tcp_port\""))
        assertTrue(text, text.contains("\"family\":\"v6\""))
        assertEquals(monitors, json.decodeFromString<List<Monitor>>(text))
    }

    @Test
    fun `a store from before them still decodes, with no network target`() {
        val old = """{"id":"a","kind":"http_status","url":"https://x.test","alert":{"failureThreshold":2}}"""
        val monitor = json.decodeFromString<Monitor>(old)
        assertEquals(NetworkWatch(), monitor.network)
        assertEquals(1, monitor.alert.recoveryThreshold)
        assertEquals(2, monitor.alert.failureThreshold)
    }

    @Test
    fun `a sample from before pings decodes without one`() {
        val sample = json.decodeFromString<Sample>("""{"at":1,"ok":true,"latencyMs":5}""")
        assertNull(sample.ping)
        val withPing = sample.copy(ping = PingReading(3, 2, 100, 200, 300))
        assertEquals(withPing, json.decodeFromString<Sample>(json.encodeToString(withPing)))
    }

    @Test
    fun `a valid ping and port check report nothing blocking`() {
        assertTrue(Validation.report(ping()).isValid)
        assertTrue(Validation.report(ping(host = "2001:db8::1")).isValid)
        assertTrue(Validation.report(tcp()).isValid)
        assertTrue("a pasted host and port is fine", Validation.report(tcp(host = "db.lan:5432")).isValid)
    }

    @Test
    fun `nothing about URLs is asked of them`() {
        val report = Validation.report(ping())
        assertNull(report.of(Validation.Field.URL))
        assertNull(report.of(Validation.Field.STATUS))
    }

    @Test
    fun `bad hosts, ports and packet counts block`() {
        assertEquals(Validation.Severity.ERROR, Validation.report(ping(host = "")).of(Validation.Field.HOST)?.severity)
        assertEquals("That isn't a valid IPv4 address", Validation.report(ping(host = "300.1.1.1")).of(Validation.Field.HOST)?.message)
        assertEquals("That isn't a valid IPv6 address", Validation.report(ping(host = "2001:db8:::1")).of(Validation.Field.HOST)?.message)
        assertEquals("That isn't a valid host name", Validation.report(ping(host = "bad..name")).of(Validation.Field.HOST)?.message)
        assertEquals(Validation.Severity.ERROR, Validation.report(ping(host = "abc.onion")).of(Validation.Field.HOST)?.severity)
        assertEquals("A port is required", Validation.report(tcp(port = 0)).of(Validation.Field.PORT)?.message)
        assertEquals("Ports run from 1 to 65535", Validation.report(tcp(port = 70_000)).of(Validation.Field.PORT)?.message)
        assertEquals(Validation.Severity.ERROR, Validation.report(ping(packets = 0)).of(Validation.Field.PACKETS)?.severity)
        assertEquals(Validation.Severity.ERROR, Validation.report(ping(packets = 11)).of(Validation.Field.PACKETS)?.severity)
    }

    @Test
    fun `a family chosen against a literal is pointed out, not blocked`() {
        val report = Validation.report(ping(host = "192.0.2.1").copy(network = NetworkWatch("192.0.2.1", family = IpFamily.V6)))
        assertTrue(report.isValid)
        assertEquals(Validation.Severity.HINT, report.of(Validation.Field.HOST)?.severity)
    }

    @Test
    fun `no reply is evidence worth checking the phone's own network for`() {
        assertTrue(Reachability.neverReachedAnything(FailureKind.NO_REPLY))
    }

    @Test
    fun `the recovery threshold shows in the policy summary`() {
        assertTrue(AlertPolicy(recoveryThreshold = 3).summary.contains("clears after 3 passes"))
    }

    @Test
    fun `a ping is costed from its packets before anything is measured`() {
        val month = 30L * 24 * 60
        val (off, on) = DataSaver.perMonth(ping(packets = 3).copy(intervalMinutes = 1), MonitorRuntime())
        assertEquals(month * 3 * 128, off)
        assertEquals(off, on)
        val (portOff, _) = DataSaver.perMonth(tcp().copy(intervalMinutes = 1), MonitorRuntime())
        assertEquals(month * 480, portOff)
    }

    @Test
    fun `a channel hears that packets are being lost, not that it is slow`() {
        val facts = WebhookFacts(
            event = WebhookEvent.DEGRADED,
            monitorName = "Edge router",
            message = "2 of 3 replies, 33% lost, 1.00 ms average",
            latencyMs = 1,
            sloMs = 500,
            lossPercent = 33,
        )
        assertEquals("Edge router is losing packets", WebhookPayload.title(facts))
        assertEquals("2 of 3 replies, 33% lost, 1.00 ms average", WebhookPayload.text(facts))
        val back = facts.copy(event = WebhookEvent.DEGRADED_RECOVERED, lossPercent = 0, message = "3 of 3 replies, 1.00 ms average")
        assertEquals("Edge router is answering normally again", WebhookPayload.title(back))
        assertEquals("not a ping: unchanged", "Edge router is slow", WebhookPayload.title(facts.copy(lossPercent = -1)))
    }
}
