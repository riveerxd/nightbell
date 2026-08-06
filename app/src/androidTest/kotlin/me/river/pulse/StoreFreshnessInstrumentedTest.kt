package me.river.pulse

import androidx.test.ext.junit.runners.AndroidJUnit4
import me.river.pulse.PulseTestSupport.appContext
import me.river.pulse.PulseTestSupport.resetApp
import me.river.pulse.data.Pulse
import me.river.pulse.domain.Health
import me.river.pulse.domain.Monitor
import me.river.pulse.domain.MonitorRuntime
import me.river.pulse.domain.Summary
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Read-after-write on [me.river.pulse.data.PulseStore.snapshot].
 *
 * Needs a real DataStore, hence on-device: the bug being pinned here was
 * entirely about *when* DataStore's flow delivers, so a fake would assert
 * nothing. Every check does `updateRuntime` then immediately reads
 * `snapshot.value` to render the widget, and that read used to observe the state
 * from before the write — so a monitor that had just recovered was drawn DOWN on
 * the home screen, and stayed DOWN until the next check while the app showed it
 * up.
 */
@RunWith(AndroidJUnit4::class)
class StoreFreshnessInstrumentedTest {

    private val store get() = Pulse.install(appContext).store

    private fun monitor(id: String) =
        Monitor(id = id, name = id, url = "https://$id.example.com")

    @Before
    fun setUp() {
        resetApp()
        runBlocking {
            store.upsert(monitor("riveer"))
            store.upsert(monitor("videre"))
        }
    }

    @Test
    fun aWriteIsVisibleToTheVeryNextRead() = runBlocking {
        store.updateRuntime("riveer") { it.copy(health = Health.DOWN, lastLatencyMs = 0) }
        assertEquals(Health.DOWN, store.snapshot.value.runtimes["riveer"]?.health)

        store.updateRuntime("riveer") { it.copy(health = Health.UP, lastLatencyMs = 3_240) }
        // The assertion the widget needs, and the one that used to fail: no
        // suspension, no delay, no yield between the write and the read.
        assertEquals(Health.UP, store.snapshot.value.runtimes["riveer"]?.health)
        assertEquals(3_240L, store.snapshot.value.runtimes["riveer"]?.lastLatencyMs)
    }

    /**
     * The bug as the user reported it: widget says DOWN, app says UP.
     *
     * Drives the same fold the widget does — `Summary.of` over `snapshot.value`,
     * exactly as `PulseWidgetProvider.render` does it — immediately after a
     * recovery lands.
     */
    @Test
    fun aRecoveredMonitorIsNotStillDownInTheFleetTheWidgetRenders() = runBlocking {
        store.updateRuntime("riveer") { it.copy(health = Health.DOWN) }
        store.updateRuntime("videre") { it.copy(health = Health.UP, lastLatencyMs = 2_150) }

        val duringOutage = fleet()
        assertEquals(1, duringOutage.down)
        assertEquals("1 of 2 is down", duringOutage.headline)

        // The recovery, then the render — back to back, as a check does it.
        store.updateRuntime("riveer") { it.copy(health = Health.UP, lastLatencyMs = 3_240) }
        val afterRecovery = fleet()
        assertEquals(0, afterRecovery.down)
        assertEquals("All 2 operational", afterRecovery.headline)
        assertEquals(Health.UP, afterRecovery.ranked.first { it.id == "riveer" }.health)
    }

    /** Every mutation lands on the end of one history, whichever entry point wrote it. */
    @Test
    fun revisionsIncreaseMonotonicallyAcrossWrites() = runBlocking {
        val start = store.snapshot.value.revision
        store.updateRuntime("riveer") { it.copy(health = Health.DOWN) }
        val afterRuntime = store.snapshot.value.revision
        store.updateSettings { it.copy(historyDepth = 90) }
        val afterSettings = store.snapshot.value.revision
        store.upsert(monitor("third"))
        val afterUpsert = store.snapshot.value.revision

        assertTrue("$start -> $afterRuntime", afterRuntime > start)
        assertTrue("$afterRuntime -> $afterSettings", afterSettings > afterRuntime)
        assertTrue("$afterSettings -> $afterUpsert", afterUpsert > afterSettings)
    }

    /**
     * An imported backup is renumbered rather than trusted.
     *
     * `replaceAll` takes a whole document, and a file written months ago carries
     * whatever counter it had then — which may be *lower* than what is on disk. If
     * that number were adopted, the restore would be treated as the older state
     * and the next DataStore emission would roll the user's import straight back.
     */
    @Test
    fun aRestoreWithAStaleRevisionStillWins() = runBlocking {
        store.updateRuntime("riveer") { it.copy(health = Health.DOWN) }
        store.updateRuntime("videre") { it.copy(health = Health.DOWN) }
        val current = store.snapshot.value.revision
        assertTrue(current > 1L)

        store.replaceAll(
            store.snapshot.value.copy(
                revision = 0L,
                runtimes = mapOf("riveer" to MonitorRuntime(health = Health.UP)),
            ),
        )
        assertTrue(store.snapshot.value.revision > current)
        assertEquals(Health.UP, store.snapshot.value.runtimes["riveer"]?.health)
        assertEquals(0, fleet().down)
    }

    /** The store has finished its first read by the time any test body runs. */
    @Test
    fun theStoreReportsItselfLoaded() {
        assertTrue(store.loaded)
    }

    private fun fleet(): Summary.Fleet =
        store.snapshot.value.let { Summary.of(it.monitors, it.runtimes) }
}
