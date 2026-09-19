package me.river.nightbell

import me.river.nightbell.data.NightbellSnapshot
import me.river.nightbell.data.transfer.BackupCodec
import me.river.nightbell.data.transfer.toImportableSnapshot
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorQuery
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a backup has to carry about arrangement, reported as missing.
 *
 * The order monitors sit in is not decoration: it is the `MANUAL` sort's entire
 * content, it survives restarts, and a person who has dragged twelve cards into
 * the shape they think in has done real work. A backup that drops it is not the
 * copy of the configuration it claims to be.
 */
class BackupOrderTest {

    private fun monitor(id: String) = Monitor(
        id = id,
        name = id.uppercase(),
        url = "https://$id.example.com",
        createdAt = 1_000L,
    )

    /** Deliberately not alphabetical and not creation order. */
    private val arranged = listOf("delta", "alpha", "charlie", "bravo")

    private fun roundTrip(snapshot: NightbellSnapshot): NightbellSnapshot {
        val document = BackupCodec.encode(
            snapshot = snapshot,
            applicationId = "me.river.nightbell",
            versionName = "3.12.0",
            versionCode = 41,
            nowMs = 1_700_000_000_000L,
        )
        return BackupCodec.decode(document).getOrThrow().toImportableSnapshot()
    }

    @Test
    fun `the order monitors were dragged into survives a round trip`() {
        val snapshot = NightbellSnapshot(
            monitors = arranged.map(::monitor),
            settings = GlobalSettings(dashboardSort = MonitorQuery.Sort.MANUAL),
        )

        val imported = roundTrip(snapshot)

        assertEquals(arranged, imported.monitors.map { it.id })
    }

    /**
     * The order alone is not enough. It is only ever *shown* under the manual
     * sort, so a backup that carries the list but not the sort restores a fleet
     * that is arranged and does not look it.
     */
    @Test
    fun `the sort that shows that order survives with it`() {
        val snapshot = NightbellSnapshot(
            monitors = arranged.map(::monitor),
            settings = GlobalSettings(dashboardSort = MonitorQuery.Sort.MANUAL),
        )

        val imported = roundTrip(snapshot)

        assertEquals(MonitorQuery.Sort.MANUAL, imported.settings.dashboardSort)
    }
}
