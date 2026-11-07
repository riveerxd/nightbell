package me.river.nightbell

import kotlinx.serialization.json.Json
import me.river.nightbell.domain.AlertDecider
import me.river.nightbell.domain.CheckResult
import me.river.nightbell.domain.DataSaver
import me.river.nightbell.domain.FailureKind
import me.river.nightbell.domain.Health
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.MonitorRuntime
import me.river.nightbell.domain.NetworkTarget
import me.river.nightbell.domain.NetworkWatch
import me.river.nightbell.domain.PayloadFormat
import me.river.nightbell.domain.ReplyMatch
import me.river.nightbell.domain.SameHost
import me.river.nightbell.domain.UdpFact
import me.river.nightbell.domain.UdpPayload
import me.river.nightbell.domain.UdpProbe
import me.river.nightbell.domain.UdpReading
import me.river.nightbell.domain.UdpWatch
import me.river.nightbell.domain.Validation
import me.river.nightbell.domain.WebhookEvent
import me.river.nightbell.domain.WebhookFacts
import me.river.nightbell.domain.WebhookPayload
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The UDP kind's model: storage, payload parsing, validation, and the words other parts say about it. */
class UdpWatchModelTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun udp(watch: UdpWatch = UdpWatch(), port: Int = 53, host: String = "dns.lan", timeout: Int = 5) =
        Monitor(id = "u", name = "DNS", kind = MonitorKind.UDP_PORT, network = NetworkWatch(host = host, port = port), udp = watch, timeoutSeconds = timeout)

    // ---- storage ------------------------------------------------------------

    @Test
    fun `a monitor stored before udp existed decodes with the defaults`() {
        val old = """{"id":"t","kind":"tcp_port","network":{"host":"db.lan","port":5432}}"""
        val monitor = json.decodeFromString(Monitor.serializer(), old)
        assertEquals(UdpWatch(), monitor.udp)
        assertEquals(UdpProbe.DNS, monitor.udp.probe)
    }

    @Test
    fun `a udp monitor round trips`() {
        val monitor = udp(UdpWatch(probe = UdpProbe.NTP, attempts = 2, ntpMaxOffsetMs = 500))
        val back = json.decodeFromString(Monitor.serializer(), json.encodeToString(Monitor.serializer(), monitor))
        assertEquals(monitor, back)
        assertTrue(json.encodeToString(Monitor.serializer(), monitor).contains("\"kind\":\"udp_port\""))
    }

    @Test
    fun `an unknown probe from a newer build runs as custom and goes back out unchanged`() {
        val newer = """{"id":"u","kind":"udp_port","network":{"host":"h","port":1},"udp":{"probe":"dtls"}}"""
        val monitor = json.decodeFromString(Monitor.serializer(), newer)
        assertEquals(UdpProbe.CUSTOM, monitor.udp.probe)
        assertEquals("dtls", monitor.udp.unknownProbe)
        val out = json.encodeToString(Monitor.serializer(), monitor)
        assertTrue(out, out.contains("\"probe\":\"dtls\""))
        assertTrue(out, !out.contains("unknownProbe"))
    }

    @Test
    fun `a 3_21 build meets udp_port as a kind it does not know, and keeps it`() {
        // What the existing guard does with any unknown kind, checked with this one.
        val raw = """{"id":"u","kind":"udp_port_v9"}"""
        val monitor = json.decodeFromString(Monitor.serializer(), raw)
        assertEquals(MonitorKind.UNSUPPORTED, monitor.kind)
    }

    // ---- payload ------------------------------------------------------------

    @Test
    fun `text escapes and hex both parse to bytes`() {
        assertArrayEquals(byteArrayOf(0x41, 0x0d, 0x0a, 0x00, 0x7f, 0x5c), bytes(UdpPayload.escaped("A\\r\\n\\0\\x7f\\\\")))
        assertArrayEquals(byteArrayOf(-1, 0x10, 0x0a), bytes(UdpPayload.hex("ff 10:0A")))
        assertArrayEquals(byteArrayOf(-1), bytes(UdpPayload.hex("0xff")))
    }

    @Test
    fun `broken payloads say what is wrong`() {
        assertEquals("Hex needs two digits per byte", (UdpPayload.hex("abc") as UdpPayload.Parsed.Invalid).message)
        assertTrue(UdpPayload.hex("zz") is UdpPayload.Parsed.Invalid)
        assertTrue(UdpPayload.escaped("bad\\q") is UdpPayload.Parsed.Invalid)
        assertTrue(UdpPayload.escaped("end\\") is UdpPayload.Parsed.Invalid)
        assertTrue(UdpPayload.escaped("\\x4") is UdpPayload.Parsed.Invalid)
    }

    @Test
    fun `reply matching, and a broken regex is null rather than false`() {
        val reply = "PONG 42".toByteArray()
        assertEquals(true, UdpPayload.matches(reply, ReplyMatch.CONTAINS, "42"))
        assertEquals(false, UdpPayload.matches(reply, ReplyMatch.STARTS_WITH, "PING"))
        assertEquals(true, UdpPayload.matches(reply, ReplyMatch.REGEX, "^PONG \\d+$"))
        assertNull(UdpPayload.matches(reply, ReplyMatch.REGEX, "("))
        assertEquals(true, UdpPayload.matches(reply, ReplyMatch.HEX_PREFIX, "50 4f"))
    }

    private fun bytes(parsed: UdpPayload.Parsed) = (parsed as UdpPayload.Parsed.Bytes).value

    // ---- validation ---------------------------------------------------------

    private fun errors(monitor: Monitor) = Validation.report(monitor).errors.map { it.field }

    @Test
    fun `a dns monitor with a host and port is valid`() {
        assertTrue(Validation.report(udp()).errors.toString(), Validation.report(udp()).isValid)
    }

    @Test
    fun `udp needs a port`() {
        assertTrue(Validation.Field.PORT in errors(udp(port = 0)))
    }

    @Test
    fun `tries times timeout may not pass a minute`() {
        assertTrue(Validation.Field.TIMEOUT in errors(udp(UdpWatch(attempts = 5), timeout = 15)))
        assertTrue(Validation.report(udp(UdpWatch(attempts = 4), timeout = 15)).isValid)
        assertTrue(Validation.Field.ATTEMPTS in errors(udp(UdpWatch(attempts = 6))))
    }

    @Test
    fun `custom needs a payload and a readable expectation`() {
        assertTrue(Validation.Field.PAYLOAD in errors(udp(UdpWatch(probe = UdpProbe.CUSTOM))))
        assertTrue(Validation.Field.PAYLOAD in errors(udp(UdpWatch(probe = UdpProbe.CUSTOM, payload = "abc", payloadFormat = PayloadFormat.HEX))))
        assertTrue(Validation.Field.REPLY in errors(udp(UdpWatch(probe = UdpProbe.CUSTOM, payload = "x", expect = ReplyMatch.REGEX, expectValue = "("))))
        assertTrue(Validation.Field.REPLY in errors(udp(UdpWatch(probe = UdpProbe.CUSTOM, payload = "x", expect = ReplyMatch.CONTAINS))))
        assertTrue(Validation.report(udp(UdpWatch(probe = UdpProbe.CUSTOM, payload = "x"))).isValid)
    }

    @Test
    fun `silent may send nothing in particular`() {
        assertTrue(Validation.report(udp(UdpWatch(probe = UdpProbe.SILENT), port = 51820)).isValid)
    }

    @Test
    fun `dns name and snmp oid are checked`() {
        assertTrue(Validation.Field.DNS_NAME in errors(udp(UdpWatch(dnsName = ""))))
        assertTrue(Validation.Field.DNS_NAME in errors(udp(UdpWatch(dnsName = "not a name"))))
        assertTrue(Validation.report(udp(UdpWatch(dnsName = "."))).isValid)
        assertTrue(Validation.Field.OID in errors(udp(UdpWatch(probe = UdpProbe.SNMP, snmpOid = "sysUpTime"))))
        assertTrue(Validation.snmpCommunityNote("", held = false) != null)
        assertNull(Validation.snmpCommunityNote("", held = true))
    }

    // ---- where it is, and what siblings say ----------------------------------

    @Test
    fun `address and host name the udp target`() {
        assertEquals("dns.lan:53/udp", udp().address)
        assertEquals("dns.lan", NetworkTarget.hostOf(udp()))
        assertEquals("port 53/udp", SameHost.nameOf(udp()))
    }

    @Test
    fun `ping up and udp down reads as the service`() {
        val ping = Monitor(id = "p", kind = MonitorKind.PING, network = NetworkWatch(host = "dns.lan"))
        val line = SameHost.reading(listOf(ping to Health.UP, udp() to Health.DOWN))
        assertEquals("The host answers ping, but port 53/udp is down. The network is fine; look at the service.", line)
    }

    @Test
    fun `tcp and udp on one port that disagree get their own sentence`() {
        val tcp = Monitor(id = "t", kind = MonitorKind.TCP_PORT, network = NetworkWatch(host = "dns.lan", port = 53))
        assertEquals(
            "53/tcp accepts connections but 53/udp does not answer. The service may only be listening on TCP.",
            SameHost.reading(listOf(tcp to Health.UP, udp() to Health.DOWN)),
        )
        assertEquals(
            "53/udp answers but 53/tcp does not. The service may only be listening on UDP.",
            SameHost.reading(listOf(tcp to Health.DOWN, udp() to Health.UP)),
        )
        assertNull(SameHost.reading(listOf(tcp to Health.UP, udp() to Health.UP)))
    }

    @Test
    fun `a silent sibling is never said to answer`() {
        val ping = Monitor(id = "p", kind = MonitorKind.PING, network = NetworkWatch(host = "dns.lan"))
        val silent = udp(UdpWatch(probe = UdpProbe.SILENT), port = 51820)
        val line = SameHost.reading(listOf(ping to Health.DOWN, silent to Health.UP))!!
        assertTrue(line, line.contains("port 51820/udp is not refused"))
    }

    // ---- runtime, data saver, webhook words ---------------------------------

    @Test
    fun `facts land on the runtime and a later reading replaces them`() {
        val first = CheckResult(ok = true, latencyMs = 1, message = "m", udp = UdpReading(1, 1, 400), udpFacts = listOf(UdpFact("Stratum", "2")), at = 1)
        val after = AlertDecider.advance(MonitorRuntime(), first, historyDepth = 10)
        assertEquals(listOf(UdpFact("Stratum", "2")), after.lastUdpFacts)
        assertEquals(400L, after.samples.last().udp!!.rttUs)
        val silence = CheckResult(ok = false, latencyMs = 0, message = "x", udp = UdpReading(3), at = 2)
        assertTrue(AlertDecider.advance(after, silence, historyDepth = 10).lastUdpFacts.isEmpty())
    }

    @Test
    fun `a check that failed before sending anything clears the old readout`() {
        val up = CheckResult(ok = true, latencyMs = 1, message = "m", udp = UdpReading(1, 1, 400), udpFacts = listOf(UdpFact("Stratum", "2")), at = 1)
        val after = AlertDecider.advance(MonitorRuntime(), up, historyDepth = 10)
        // No DNS answer for the host, so no datagram and no reading.
        val noName = CheckResult(ok = false, latencyMs = 0, message = "Can't resolve ntp.lan", failureKind = FailureKind.DNS, at = 2)
        assertTrue(AlertDecider.advance(after, noName, historyDepth = 10).lastUdpFacts.isEmpty())
    }

    @Test
    fun `data saver counts a udp exchange without waiting for a measurement`() {
        val (off, on) = DataSaver.perMonth(udp().copy(intervalMinutes = 60), MonitorRuntime())
        assertTrue(off != null && off > 0 && off == on)
    }

    @Test
    fun `webhook words use the probe's own reason`() {
        val facts = WebhookFacts(event = WebhookEvent.DEGRADED, monitorName = "Clock", message = "Clock off by +1.4 s", reported = true, udp = true)
        assertEquals("Clock is degraded", WebhookPayload.title(facts))
        val back = facts.copy(event = WebhookEvent.DEGRADED_RECOVERED, reported = false, message = "Stratum 2, offset +3 ms")
        assertEquals("Clock is answering normally again", WebhookPayload.title(back))
    }

    @Test
    fun `a port picks the probe it is usually spoken to with, 443 included`() {
        assertEquals(UdpProbe.QUIC, UdpProbe.forPort(443))
        assertEquals(UdpProbe.NTP, UdpProbe.forPort(123))
        assertEquals(UdpProbe.SILENT, UdpProbe.forPort(51820))
        assertNull(UdpProbe.forPort(9999))
    }
}
