package me.river.nightbell

import me.river.nightbell.domain.IpFamily
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.NetworkTarget
import me.river.nightbell.domain.NetworkWatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the host field makes of what people paste into it. Issue 21. */
class NetworkTargetTest {

    @Test
    fun `a bare host has no port`() {
        assertEquals(NetworkTarget.Parsed("db.lan", null), NetworkTarget.parse("db.lan"))
        assertEquals(NetworkTarget.Parsed("192.0.2.10", null), NetworkTarget.parse("  192.0.2.10 "))
    }

    @Test
    fun `host and port are split`() {
        assertEquals(NetworkTarget.Parsed("db.lan", 5432), NetworkTarget.parse("db.lan:5432"))
        assertEquals(NetworkTarget.Parsed("192.0.2.10", 443), NetworkTarget.parse("192.0.2.10:443"))
    }

    @Test
    fun `a URL keeps its host and its written port`() {
        assertEquals(NetworkTarget.Parsed("example.com", null), NetworkTarget.parse("https://example.com/health?x=1"))
        assertEquals(NetworkTarget.Parsed("example.com", 8443), NetworkTarget.parse("https://admin:pw@example.com:8443/x"))
    }

    @Test
    fun `a bare IPv6 literal is never split on its last colon`() {
        assertEquals(NetworkTarget.Parsed("2001:db8::1", null), NetworkTarget.parse("2001:db8::1"))
        assertEquals(NetworkTarget.Parsed("::1", null), NetworkTarget.parse("::1"))
    }

    @Test
    fun `a bracketed IPv6 literal may carry a port`() {
        assertEquals(NetworkTarget.Parsed("2001:db8::1", 443), NetworkTarget.parse("[2001:db8::1]:443"))
        assertEquals(NetworkTarget.Parsed("2001:db8::1", null), NetworkTarget.parse("[2001:db8::1]"))
        assertEquals(NetworkTarget.Parsed("2001:db8::1", 8080), NetworkTarget.parse("http://[2001:db8::1]:8080/"))
    }

    @Test
    fun `literal families`() {
        assertEquals(IpFamily.V4, NetworkTarget.literalFamily("192.0.2.10"))
        assertEquals(IpFamily.V6, NetworkTarget.literalFamily("2001:db8::1"))
        assertEquals(IpFamily.V6, NetworkTarget.literalFamily("[::1]"))
        assertEquals(IpFamily.V6, NetworkTarget.literalFamily("::ffff:192.0.2.1"))
        assertEquals(IpFamily.V6, NetworkTarget.literalFamily("fe80::1%wlan0"))
        assertNull(NetworkTarget.literalFamily("db.lan"))
        assertNull(NetworkTarget.literalFamily("300.1.1.1"))
    }

    @Test
    fun `malformed addresses are not addresses`() {
        assertFalse(NetworkTarget.isIpv4("256.0.0.1"))
        assertFalse(NetworkTarget.isIpv4("01.2.3.4"))
        assertFalse(NetworkTarget.isIpv4("1.2.3"))
        assertFalse(NetworkTarget.isIpv6("2001:db8:::1"))
        assertFalse(NetworkTarget.isIpv6("1::2::3"))
        assertFalse(NetworkTarget.isIpv6("12345::1"))
        assertFalse(NetworkTarget.isIpv6("1:2:3:4:5:6:7"))
        assertFalse(NetworkTarget.isIpv6(":1:2:3:4:5:6:7"))
        assertTrue(NetworkTarget.isIpv6("1:2:3:4:5:6:7:8"))
        assertTrue(NetworkTarget.isIpv6("1::"))
        assertTrue(NetworkTarget.isIpv6("::"))
    }

    @Test
    fun `host names`() {
        assertTrue(NetworkTarget.isHostName("db"))
        assertTrue(NetworkTarget.isHostName("db-1.internal.example.com"))
        assertTrue(NetworkTarget.isHostName("example.com."))
        assertFalse(NetworkTarget.isHostName("-bad.example"))
        assertFalse(NetworkTarget.isHostName("bad..example"))
        assertFalse("a failed IPv4 is a typo, not a name", NetworkTarget.isHostName("300.1.1.1"))
    }

    @Test
    fun `display brackets IPv6 only next to a port`() {
        assertEquals("2001:db8::1", NetworkTarget.display("2001:db8::1", null))
        assertEquals("[2001:db8::1]:443", NetworkTarget.display("2001:db8::1", 443))
        assertEquals("db.lan:5432", NetworkTarget.display("db.lan", 5432))
    }

    @Test
    fun `monitors share a host whatever their kind`() {
        val web = Monitor(id = "w", url = "https://Example.com/health")
        val ping = Monitor(id = "p", kind = MonitorKind.PING, network = NetworkWatch(host = "example.com."))
        val port = Monitor(id = "t", kind = MonitorKind.TCP_PORT, network = NetworkWatch(host = "example.com:443", port = 443))
        assertEquals("example.com", NetworkTarget.hostOf(web))
        assertEquals("example.com", NetworkTarget.hostOf(ping))
        assertEquals("example.com", NetworkTarget.hostOf(port))
        assertEquals("", NetworkTarget.hostOf(Monitor(id = "g", kind = MonitorKind.GITHUB_REPO, url = "https://github.com/a/b")))
    }

    @Test
    fun `the address is what a person reads`() {
        val ping = Monitor(id = "p", kind = MonitorKind.PING, network = NetworkWatch(host = "[2001:db8::1]"))
        val port = Monitor(id = "t", kind = MonitorKind.TCP_PORT, network = NetworkWatch(host = "2001:db8::1", port = 22))
        assertEquals("2001:db8::1", ping.address)
        assertEquals("2001:db8::1", ping.displayName)
        assertEquals("[2001:db8::1]:22", port.address)
        assertEquals("https://x.test", Monitor(id = "h", url = "https://x.test").address)
    }

    @Test
    fun `a name decides nothing about family until it is resolved`() {
        assertTrue(NetworkWatch(host = "db.lan").isName)
        assertFalse(NetworkWatch(host = "192.0.2.1").isName)
        assertFalse(NetworkWatch(host = "").isName)
    }
}
