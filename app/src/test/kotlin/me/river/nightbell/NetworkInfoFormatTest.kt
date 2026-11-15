package me.river.nightbell

import me.river.nightbell.data.network.bytesPerSecond
import me.river.nightbell.data.network.dataNetworkTypeLabel
import me.river.nightbell.data.network.firstIpAddress
import me.river.nightbell.data.network.formatBytes
import me.river.nightbell.data.network.formatRate
import me.river.nightbell.data.network.intToIpv4
import me.river.nightbell.data.network.rssiGrade
import me.river.nightbell.data.network.signalBars
import me.river.nightbell.data.network.signalQuality
import me.river.nightbell.data.network.wifiBand
import me.river.nightbell.data.network.wifiChannel
import me.river.nightbell.data.network.wifiStandardLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkInfoFormatTest {

    @Test
    fun `byte counts use binary prefixes and stay short`() {
        assertEquals("512 B", formatBytes(512))
        assertEquals("1.0 KB", formatBytes(1024))
        assertEquals("1.5 KB", formatBytes(1536))
        assertEquals("1.0 MB", formatBytes(1024L * 1024))
        assertEquals("2.0 GB", formatBytes(2L * 1024 * 1024 * 1024))
        assertEquals("-", formatBytes(-1))
        assertEquals("1.0 KB/s", formatRate(1024))
    }

    @Test
    fun `wi-fi channels cover 2_4 5 and 6 GHz`() {
        assertEquals(1, wifiChannel(2412))
        assertEquals(6, wifiChannel(2437))
        assertEquals(11, wifiChannel(2462))
        assertEquals(14, wifiChannel(2484))
        assertEquals(36, wifiChannel(5180))
        assertEquals(149, wifiChannel(5745))
        assertEquals(1, wifiChannel(5955))
        assertNull(wifiChannel(0))
        assertNull(wifiChannel(2413))
        assertEquals("2.4 GHz", wifiBand(2437))
        assertEquals("5 GHz", wifiBand(5180))
        assertEquals("6 GHz", wifiBand(5955))
        assertEquals("-", wifiBand(0))
    }

    @Test
    fun `standards and data network types are labelled`() {
        assertEquals("802.11ax / Wi-Fi 6", wifiStandardLabel(6))
        assertEquals("802.11be / Wi-Fi 7", wifiStandardLabel(8))
        assertEquals("-", wifiStandardLabel(0))
        assertEquals("LTE", dataNetworkTypeLabel(13))
        assertEquals("5G NR", dataNetworkTypeLabel(20))
        assertEquals("-", dataNetworkTypeLabel(999))
    }

    @Test
    fun `signal grades stay inside the drawable bar range`() {
        assertEquals(4, rssiGrade(-50))
        assertEquals(3, rssiGrade(-60))
        assertEquals(2, rssiGrade(-70))
        assertEquals(1, rssiGrade(-85))
        assertEquals(0, rssiGrade(-100))
        assertEquals(0, signalBars(-2, 4))
        assertEquals(4, signalBars(9, 4))
        assertEquals("Excellent", signalQuality(4))
        assertEquals("None", signalQuality(0))
    }

    @Test
    fun `dhcp little-endian integers become readable addresses`() {
        assertEquals("192.168.1.1", intToIpv4(0x0101A8C0))
        assertEquals("10.0.0.1", intToIpv4(0x0100000A))
        assertEquals("-", intToIpv4(0))
    }

    @Test
    fun `public ip echo text yields exactly one address`() {
        assertEquals("203.0.113.7", firstIpAddress("203.0.113.7\n"))
        assertEquals("203.0.113.7", firstIpAddress("当前 IP：203.0.113.7 来自于：中国"))
        assertNull(firstIpAddress("no address here"))
    }

    @Test
    fun `live rates ignore clock jumps and counter wraps`() {
        // 2048 bytes over 1000 ms -> 2048 B/s.
        assertEquals(2048L, bytesPerSecond(2_000, 3_072, 1_000, 1_024))
        assertEquals(1024L, bytesPerSecond(2_000, 2_048, 1_000, 1_024))
        assertEquals(0L, bytesPerSecond(1_000, 5_000, 2_000, 1_000))
        assertEquals(0L, bytesPerSecond(2_000, 10, 1_000, 5_000))
        assertEquals(0L, bytesPerSecond(2_000, (1L shl 32) + 100, 1_000, 100))
    }
}
