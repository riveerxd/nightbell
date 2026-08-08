package me.river.pulse

import me.river.pulse.data.NightbellSnapshot
import me.river.pulse.data.transfer.BackupCodec
import me.river.pulse.data.transfer.BackupError
import me.river.pulse.data.transfer.NightbellBackup
import me.river.pulse.data.transfer.toImportableSnapshot
import me.river.pulse.domain.CheckerStreak
import me.river.pulse.domain.GlobalSettings
import me.river.pulse.domain.Health
import me.river.pulse.domain.Monitor
import me.river.pulse.domain.MonitorRuntime
import me.river.pulse.domain.ReferenceSample
import me.river.pulse.domain.Sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The migration bridge's format and its import rules.
 *
 * Worth real coverage rather than a smoke test: this is the only path that
 * carries a user's fleet across the `applicationId` change, it runs exactly once
 * per user, and there is no second chance if it silently drops something.
 */
class BackupTest {

    private fun monitor(id: String, name: String = "Site", enabled: Boolean = true) = Monitor(
        id = id,
        name = name,
        url = "https://example.com/$id",
        enabled = enabled,
        createdAt = 1_000L,
    )

    private fun encoded(snapshot: NightbellSnapshot) = BackupCodec.encode(
        snapshot = snapshot,
        applicationId = "me.river.pulse",
        versionName = "1.7.0",
        versionCode = 10,
        nowMs = 1_700_000_000_000L,
    )

    // ---- envelope -----------------------------------------------------------

    @Test
    fun `a store round-trips through the envelope`() {
        val snapshot = NightbellSnapshot(
            monitors = listOf(monitor("a"), monitor("b")),
            runtimes = mapOf("a" to MonitorRuntime(health = Health.UP)),
            settings = GlobalSettings(defaultIntervalMinutes = 42),
        )

        val backup = BackupCodec.decode(encoded(snapshot)).getOrThrow()

        assertEquals(NightbellBackup.FORMAT_VERSION, backup.format)
        assertEquals("me.river.pulse", backup.app)
        assertEquals("1.7.0", backup.versionName)
        assertEquals(10, backup.versionCode)
        assertEquals(1_700_000_000_000L, backup.exportedAt)
        assertEquals(2, backup.monitorCount)
        assertEquals(listOf("a", "b"), backup.snapshot.monitors.map { it.id })
        assertEquals(42, backup.snapshot.settings.defaultIntervalMinutes)
    }

    @Test
    fun `the recorded count matches what is in the file`() {
        val snapshot = NightbellSnapshot(monitors = listOf(monitor("a"), monitor("b"), monitor("c")))
        val backup = BackupCodec.decode(encoded(snapshot)).getOrThrow()
        assertEquals(backup.snapshot.monitors.size, backup.monitorCount)
    }

    // ---- refusals -----------------------------------------------------------

    @Test
    fun `nonsense is refused rather than half-imported`() {
        for (raw in listOf("", "   ", "not json at all", "{", "[1,2,3]")) {
            val error = BackupCodec.decode(raw).exceptionOrNull()
            assertTrue("expected a failure for <$raw>", error is BackupCodec.BackupFailure)
            assertEquals(BackupError.Unreadable, (error as BackupCodec.BackupFailure).error)
        }
    }

    @Test
    fun `another app's json is refused`() {
        // Parses as an object, has none of our fields — which after defaults means
        // an empty snapshot, and that is the case Empty exists to catch.
        val error = BackupCodec.decode("""{"hello":"world"}""").exceptionOrNull()
        assertEquals(BackupError.Empty, (error as BackupCodec.BackupFailure).error)
    }

    @Test
    fun `a newer format is refused instead of best-efforted`() {
        val raw = encoded(NightbellSnapshot(monitors = listOf(monitor("a"))))
            .replace("\"format\": 1", "\"format\": 2")

        val error = BackupCodec.decode(raw).exceptionOrNull()

        assertEquals(BackupError.TooNew(2), (error as BackupCodec.BackupFailure).error)
        assertTrue(error.error.message.contains("newer version"))
    }

    @Test
    fun `an empty fleet is not a valid backup`() {
        val error = BackupCodec.decode(encoded(NightbellSnapshot())).exceptionOrNull()
        assertEquals(BackupError.Empty, (error as BackupCodec.BackupFailure).error)
    }

    @Test
    fun `an unknown field from a future build is ignored, not fatal`() {
        val raw = encoded(NightbellSnapshot(monitors = listOf(monitor("a"))))
            .replaceFirst("{", """{"somethingAddedLater": true,""")

        assertNotNull(BackupCodec.decode(raw).getOrNull())
    }

    // ---- what lands in a fresh install --------------------------------------

    @Test
    fun `monitors settings mutes and history survive the import`() {
        val samples = listOf(Sample(at = 5L, ok = true, latencyMs = 120L))
        val backup = NightbellBackup(
            snapshot = NightbellSnapshot(
                monitors = listOf(monitor("a")),
                runtimes = mapOf(
                    "a" to MonitorRuntime(mutedUntil = 9_999L, samples = samples),
                ),
                settings = GlobalSettings(defaultIntervalMinutes = 7),
            ),
        )

        val imported = backup.toImportableSnapshot()

        assertEquals(listOf("a"), imported.monitors.map { it.id })
        assertEquals(7, imported.settings.defaultIntervalMinutes)
        assertEquals(9_999L, imported.runtimes.getValue("a").mutedUntil)
        assertEquals(samples, imported.runtimes.getValue("a").samples)
    }

    @Test
    fun `nothing is claimed about health until this install has checked`() {
        val backup = NightbellBackup(
            snapshot = NightbellSnapshot(
                monitors = listOf(monitor("a")),
                runtimes = mapOf(
                    "a" to MonitorRuntime(
                        health = Health.UP,
                        lastCheckedAt = 8_000L,
                        lastLatencyMs = 90L,
                        lastCode = 200,
                        lastMessage = "OK",
                        lastDetail = "detail",
                        consecutiveSuccesses = 12,
                    ),
                ),
            ),
        )

        val runtime = backup.toImportableSnapshot().runtimes.getValue("a")

        assertEquals(Health.UNKNOWN, runtime.health)
        assertEquals(0L, runtime.lastCheckedAt)
        assertEquals(0L, runtime.lastLatencyMs)
        assertEquals(0, runtime.lastCode)
        assertEquals("", runtime.lastMessage)
        assertEquals("", runtime.lastDetail)
        assertEquals(0, runtime.consecutiveSuccesses)
    }

    @Test
    fun `a paused monitor imports paused rather than unknown`() {
        val backup = NightbellBackup(
            snapshot = NightbellSnapshot(
                monitors = listOf(monitor("a", enabled = false)),
                runtimes = mapOf("a" to MonitorRuntime(health = Health.PAUSED)),
            ),
        )

        assertEquals(Health.PAUSED, backup.toImportableSnapshot().runtimes.getValue("a").health)
    }

    /**
     * The trap [me.river.pulse.domain.LegacyCrashRepair] exists to undo,
     * arriving by a different door. The down track is transition-driven, so an
     * imported `alerting = true` means the first genuine outage on the new install
     * is never announced.
     */
    @Test
    fun `alert bookkeeping does not cross over`() {
        val backup = NightbellBackup(
            snapshot = NightbellSnapshot(
                monitors = listOf(monitor("a")),
                runtimes = mapOf(
                    "a" to MonitorRuntime(
                        alerting = true,
                        lastAlertAt = 500L,
                        consecutiveFailures = 4,
                        degradedAlerting = true,
                        lastDegradedAlertAt = 600L,
                        urgentActive = true,
                        urgentAcknowledged = true,
                        lastUrgentAlertAt = 700L,
                    ),
                ),
            ),
        )

        val runtime = backup.toImportableSnapshot().runtimes.getValue("a")

        assertFalse(runtime.alerting)
        assertEquals(0L, runtime.lastAlertAt)
        assertEquals(0, runtime.consecutiveFailures)
        assertFalse(runtime.degradedAlerting)
        assertEquals(0L, runtime.lastDegradedAlertAt)
        assertFalse(runtime.urgentActive)
        assertFalse(runtime.urgentAcknowledged)
        assertEquals(0L, runtime.lastUrgentAlertAt)
    }

    @Test
    fun `evidence about the old install's checker and connection is dropped`() {
        val backup = NightbellBackup(
            snapshot = NightbellSnapshot(
                monitors = listOf(monitor("a")),
                reference = listOf(ReferenceSample(at = 1L, rttMs = 30L)),
                checkerStreak = CheckerStreak(consecutiveErrors = 3, firstErrorAt = 1L, lastErrorAt = 2L),
            ),
        )

        val imported = backup.toImportableSnapshot()

        assertTrue(imported.reference.isEmpty())
        assertEquals(CheckerStreak(), imported.checkerStreak)
    }

    @Test
    fun `a runtime with no monitor behind it is not carried`() {
        val backup = NightbellBackup(
            snapshot = NightbellSnapshot(
                monitors = listOf(monitor("a")),
                runtimes = mapOf("a" to MonitorRuntime(), "ghost" to MonitorRuntime()),
            ),
        )

        val imported = backup.toImportableSnapshot()

        assertNotNull(imported.runtimes["a"])
        assertNull(imported.runtimes["ghost"])
    }

    @Test
    fun `element baselines are re-established here rather than inherited`() {
        val backup = NightbellBackup(
            snapshot = NightbellSnapshot(
                monitors = listOf(monitor("a")),
                runtimes = mapOf(
                    "a" to MonitorRuntime(
                        lastElementText = "In stock",
                        lastElementTexts = listOf("In stock"),
                    ),
                ),
            ),
        )

        val runtime = backup.toImportableSnapshot().runtimes.getValue("a")

        assertEquals("", runtime.lastElementText)
        assertTrue(runtime.lastElementTexts.isEmpty())
    }

    @Test
    fun `export then import is stable across a second trip`() {
        val snapshot = NightbellSnapshot(
            monitors = listOf(monitor("a"), monitor("b", enabled = false)),
            runtimes = mapOf(
                "a" to MonitorRuntime(samples = listOf(Sample(at = 1L, ok = true, latencyMs = 10L))),
                "b" to MonitorRuntime(health = Health.PAUSED),
            ),
            settings = GlobalSettings(defaultTimeoutSeconds = 11),
        )

        val once = BackupCodec.decode(encoded(snapshot)).getOrThrow().toImportableSnapshot()
        val twice = BackupCodec.decode(encoded(once)).getOrThrow().toImportableSnapshot()

        assertEquals(once, twice)
    }
}
