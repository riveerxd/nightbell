package me.river.nightbell

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.river.nightbell.NightbellTestSupport.appContext
import me.river.nightbell.data.Nightbell
import me.river.nightbell.data.NightbellSnapshot
import me.river.nightbell.data.transfer.BackupCodec
import me.river.nightbell.data.transfer.toImportableSnapshot
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.Health
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.MonitorQuery
import me.river.nightbell.ui.DashboardViewModel
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * An imported arrangement has to be the arrangement on screen.
 *
 * Reported as "export and import does not include the order of the monitors". The
 * file always carried it, which is covered on the JVM side by `BackupOrderTest`;
 * what did not carry was the sort that shows it. `DashboardViewModel` read
 * `dashboardSort` once when it was constructed, and it outlives a trip into
 * Settings, so an import there replaced the store underneath a dashboard that had
 * already decided it was ranking by severity. The monitors arrived in the right
 * order and were immediately re-ranked, which from the outside is a backup that
 * lost the arrangement.
 */
@RunWith(AndroidJUnit4::class)
class ImportedOrderInstrumentedTest {

    @Before
    fun setUp() = NightbellTestSupport.resetApp()

    private fun monitor(id: String) = Monitor(
        id = id,
        name = id.uppercase(),
        kind = MonitorKind.HTTP_STATUS,
        url = "https://$id.example.com",
    )

    /**
     * Healths chosen so worst-first and the arranged order disagree about every
     * position. Without that a test can pass on a coincidence.
     */
    private fun seedLocalFleet() = runBlocking {
        val store = Nightbell.install(appContext).store
        listOf("alpha" to Health.UP, "bravo" to Health.DOWN, "charlie" to Health.DEGRADED)
            .forEach { (id, health) ->
                store.upsert(monitor(id))
                store.updateRuntime(id) { it.copy(health = health) }
            }
    }

    /** The file a user would carry over: a hand-dragged order, kept by manual sort. */
    private fun backupDocument(order: List<String>) = BackupCodec.encode(
        snapshot = NightbellSnapshot(
            monitors = order.map(::monitor),
            settings = GlobalSettings(dashboardSort = MonitorQuery.Sort.MANUAL),
        ),
        applicationId = "me.river.nightbell",
        versionName = "3.12.0",
        versionCode = 41,
        nowMs = System.currentTimeMillis(),
    )

    @Test
    fun anImportedOrderIsTheOrderTheDashboardShows() {
        seedLocalFleet()

        val viewModel = DashboardViewModel(Nightbell.install(appContext))
        // `cards` is WhileSubscribed, so without a collector it never leaves its
        // empty initial value.
        val subscriber = CoroutineScope(Dispatchers.Default)
        subscriber.launch { viewModel.cards.collect { } }
        try {
            NightbellTestSupport.awaitTrue(description = "local fleet loaded") {
                viewModel.visible.size == 3
            }
            // Ranked by severity to begin with, which is the default and is what
            // the view model latched onto when it was built.
            assertEquals(
                listOf("bravo", "charlie", "alpha"),
                viewModel.visible.map { it.monitor.id },
            )

            // The import, exactly as `importBackup` performs it.
            val arranged = listOf("charlie", "alpha", "bravo")
            val imported = BackupCodec.decode(backupDocument(arranged))
                .getOrThrow()
                .toImportableSnapshot()
            runBlocking { Nightbell.install(appContext).store.replaceAll(imported) }

            // The assertion the bug fails: before the fix this stayed ranked by
            // severity, because the dashboard never heard that the sort had
            // changed underneath it.
            NightbellTestSupport.awaitTrue(description = "imported order on screen") {
                viewModel.visible.map { it.monitor.id } == arranged
            }
            assertEquals(MonitorQuery.Sort.MANUAL, viewModel.spec.sort)
        } finally {
            subscriber.cancel()
        }
    }

    /**
     * And the user's own choice still wins while they are making it. The collector
     * mirrors the store, and `setSort` writes to the store, so the two must not
     * fight.
     */
    @Test
    fun choosingASortOnScreenStillSticks() {
        seedLocalFleet()
        val viewModel = DashboardViewModel(Nightbell.install(appContext))
        val subscriber = CoroutineScope(Dispatchers.Default)
        subscriber.launch { viewModel.cards.collect { } }
        try {
            NightbellTestSupport.awaitTrue(description = "fleet loaded") {
                viewModel.visible.size == 3
            }
            viewModel.setSort(MonitorQuery.Sort.NAME)
            assertEquals(MonitorQuery.Sort.NAME, viewModel.spec.sort)
            NightbellTestSupport.awaitTrue(description = "sort persisted") {
                runBlocking {
                    Nightbell.install(appContext).store.currentSnapshot().settings.dashboardSort
                } == MonitorQuery.Sort.NAME
            }
            // Still NAME a moment later: the echo from the store must not undo it.
            assertEquals(MonitorQuery.Sort.NAME, viewModel.spec.sort)
            assertEquals(
                listOf("alpha", "bravo", "charlie"),
                viewModel.visible.map { it.monitor.id },
            )
        } finally {
            subscriber.cancel()
        }
    }
}
