package me.river.nightbell

import me.river.nightbell.data.NightbellSnapshot
import me.river.nightbell.data.transfer.BackupCodec
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorRuntime
import me.river.nightbell.domain.TlsTrust
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * That the trust mode and the recorded key both survive being stored and exported.
 *
 * The export half was written the other way round first, asserting that the pin is
 * stripped, on the reasoning that the mode is a decision and the key is only an
 * observation. The test failed, because an export carries runtimes whole, and on
 * looking at it properly the failure was right and the reasoning was wrong.
 *
 * Trust on first use is weakest at the first use. A fresh install that re-pins
 * whatever answers would trust an impostor without hesitation if one had turned up
 * in the meantime; carrying the key across removes that moment instead of
 * repeating it. And there is nothing to leak, because a public key hash is
 * available to anyone who opens a connection to the server.
 */
class TlsTrustPersistenceTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `the trust mode survives a store round trip`() {
        val monitor = Monitor(
            id = "m1",
            name = "NAS",
            url = "https://192.168.1.20",
            tlsTrust = TlsTrust.PINNED,
        )
        val encoded = json.encodeToString(Monitor.serializer(), monitor)
        val decoded = json.decodeFromString(Monitor.serializer(), encoded)
        assertEquals(TlsTrust.PINNED, decoded.tlsTrust)
    }

    @Test
    fun `a monitor written before this existed decodes as SYSTEM`() {
        // The shape of every stored monitor in 3.2.1 and earlier: no tlsTrust key
        // at all. It has to come back as the mode that matches the old behaviour,
        // not as the first enum entry by accident.
        val legacy = """{"id":"old","name":"Old","url":"https://example.com"}"""
        val decoded = json.decodeFromString(Monitor.serializer(), legacy)
        assertEquals(TlsTrust.SYSTEM, decoded.tlsTrust)
    }

    @Test
    fun `a runtime written before this existed has no pin`() {
        val legacy = """{"health":"UP","lastLatencyMs":120}"""
        val decoded = json.decodeFromString(MonitorRuntime.serializer(), legacy)
        assertEquals("", decoded.certPin)
    }

    @Test
    fun `an export carries both the mode and the pinned key`() {
        val snapshot = NightbellSnapshot(
            monitors = listOf(
                Monitor(id = "m1", name = "NAS", url = "https://nas.local", tlsTrust = TlsTrust.PINNED),
            ),
            runtimes = mapOf("m1" to MonitorRuntime(certPin = "sha256/EXAMPLEKEYHASH=")),
        )
        val text = BackupCodec.encode(
            snapshot = snapshot,
            applicationId = "me.river.nightbell",
            versionName = "3.2.2",
            versionCode = 32,
            nowMs = 1_700_000_000_000L,
        )

        assertTrue(text, text.contains("PINNED"))
        assertTrue(text, text.contains("sha256/EXAMPLEKEYHASH="))

        val restored = BackupCodec.decode(text).getOrThrow().snapshot
        assertEquals(TlsTrust.PINNED, restored.monitors.single().tlsTrust)
        // The imported monitor is pinned to the same key on the new device, so its
        // first check there enforces the pin rather than re-arming it.
        assertEquals("sha256/EXAMPLEKEYHASH=", restored.runtimes["m1"]?.certPin)
    }
}
