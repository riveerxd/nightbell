package me.river.nightbell

import me.river.nightbell.data.capture.CaptureConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureConfigTest {

    @Test
    fun `nothing is decrypted until the user turns it on`() {
        val off = CaptureConfig(decryptTls = false, hosts = listOf("api.internal.test"))
        assertFalse(off.matches("api.internal.test"))
        assertFalse(CaptureConfig().matches("anything.example.com"))
    }

    @Test
    fun `an exact host and its subdomains are allowlisted`() {
        val config = CaptureConfig(
            decryptTls = true,
            hosts = listOf("api.internal.test", "*.intranet.example.com"),
        )
        assertTrue(config.matches("api.internal.test"))
        assertTrue(config.matches("API.INTERNAL.TEST"))
        assertTrue(config.matches("v2.api.internal.test"))
        assertTrue(config.matches("nas.intranet.example.com"))
    }

    @Test
    fun `unrelated hosts are tunnelled untouched`() {
        val config = CaptureConfig(
            decryptTls = true,
            hosts = listOf("api.internal.test", "*.intranet.example.com"),
        )
        assertFalse(config.matches("intranet.example.com"))
        assertFalse(config.matches("bank.example.com"))
        assertFalse(config.matches("api.internal.test.attacker.com"))
        assertFalse(config.matches(""))
    }

    @Test
    fun `a pasted URL or host with a port is reduced to its hostname`() {
        val config = CaptureConfig(
            decryptTls = true,
            hosts = listOf("https://API.Internal.Test:8443/api/v1", "192.168.1.50:8443"),
        )
        assertEquals(listOf("api.internal.test", "192.168.1.50"), config.effectiveHosts())
        assertTrue(config.matches("api.internal.test"))
        assertTrue(config.matches("https://api.internal.test:9443/anything"))
        assertTrue(config.matches("192.168.1.50"))
        assertTrue(config.matches("[192.168.1.50]:443"))
    }

    @Test
    fun `the block reason names the actual misconfiguration`() {
        assertEquals("off", CaptureConfig(false, listOf("a.test")).blockReason("a.test"))
        assertEquals("empty", CaptureConfig(true, emptyList()).blockReason("a.test"))
        assertEquals(
            "mismatch",
            CaptureConfig(true, listOf("a.test")).blockReason("b.test"),
        )
        assertNull(CaptureConfig(true, listOf("a.test")).blockReason("a.test"))
        assertNull(
            CaptureConfig(true, listOf("*.a.test")).blockReason("sub.a.test"),
        )
    }
}
