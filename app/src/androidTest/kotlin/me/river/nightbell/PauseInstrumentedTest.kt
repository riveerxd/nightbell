package me.river.nightbell

import androidx.test.ext.junit.runners.AndroidJUnit4
import me.river.nightbell.NightbellTestSupport.appContext
import me.river.nightbell.NightbellTestSupport.resetApp
import me.river.nightbell.data.Nightbell
import me.river.nightbell.domain.AlertPolicy
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.Health
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.PauseScope
import me.river.nightbell.domain.PauseState
import java.net.ServerSocket
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The forest.
 *
 * The case this feature exists for is not being offline. Nightbell already stops
 * checking when the device has no connectivity at all, and has since 1.3.0. It is
 * having *one bar*: online by every test the platform offers, and not enough
 * signal to finish a request. Every monitor fails at once, every one of them is
 * urgent, and none of the alerts are about the services.
 *
 * A dead port stands in for that here. It is the same thing from the checker's
 * point of view: the device is up, the network is up, and the request does not
 * complete.
 */
@RunWith(AndroidJUnit4::class)
class PauseInstrumentedTest {

    private lateinit var deadUrl: String

    @Before
    fun setUp() {
        resetApp(GlobalSettings(motionIntensity = 0f))
        // A port nothing is listening on: reachable device, unreachable service.
        deadUrl = "http://127.0.0.1:${ServerSocket(0).use { it.localPort }}/health"
    }

    private fun graph() = Nightbell.install(appContext).also { it.engine.isOnline = { true } }

    private fun downMonitor(id: String = "forest") = Monitor(
        id = id,
        name = "Forest",
        kind = MonitorKind.HTTP_STATUS,
        url = deadUrl,
        timeoutSeconds = 2,
        urgent = true,
        useGlobalAlerts = false,
        alert = AlertPolicy(enabled = true, alertOnDown = true, failureThreshold = 1),
    )

    @Test
    fun pausingStopsTheChecksAndTakesTheFleetQuiet() {
        val graph = graph()
        val monitor = downMonitor()
        runBlocking { graph.store.upsert(monitor) }

        // It screams first, which is correct behaviour and the whole problem.
        runBlocking { graph.engine.run(monitor.id) }
        val down = graph.store.snapshot.value.runtimes[monitor.id]
        assertEquals(Health.DOWN, down?.health)
        assertTrue("the monitor was expected to be alerting before the pause", down?.alerting == true)
        val checkedBeforePause = down?.lastCheckedAt ?: 0L

        runBlocking {
            graph.engine.pauseAll(
                PauseState.timed(System.currentTimeMillis(), 60, PauseScope.STOP_CHECKS),
            )
        }

        // Anything already paging is stood down, not left ringing behind a screen
        // that says paused.
        val paused = graph.store.snapshot.value.runtimes[monitor.id]
        assertFalse("an urgent page survived the pause", paused?.urgentActive == true)

        // The schedule stops, so nothing false lands in the history. `force = false`
        // is what every scheduled caller passes: the worker, the sweep, the service
        // loop.
        runBlocking { graph.engine.run(monitor.id, force = false) }
        assertEquals(
            "a scheduled check ran while paused",
            checkedBeforePause,
            graph.store.snapshot.value.runtimes[monitor.id]?.lastCheckedAt,
        )
        assertEquals(
            "a sample was recorded while paused",
            down?.samples?.size,
            graph.store.snapshot.value.runtimes[monitor.id]?.samples?.size,
        )
    }

    @Test
    fun aHandDrivenCheckStillRunsWhileTheFleetIsPaused() {
        val graph = graph()
        val monitor = downMonitor("by-hand")
        runBlocking { graph.store.upsert(monitor) }
        runBlocking { graph.engine.run(monitor.id) }
        val before = graph.store.snapshot.value.runtimes[monitor.id]?.lastCheckedAt

        runBlocking {
            graph.engine.pauseAll(
                PauseState.timed(System.currentTimeMillis(), 60, PauseScope.STOP_CHECKS),
            )
        }
        Thread.sleep(SETTLE_MS)

        // A pause stops the schedule. It is not a lock on the app, and the
        // re-check button on a card, the detail screen's "Check now" and the first
        // check of a monitor the user just saved all arrive here forced. Refusing
        // them left the button saying "Checking..." and then doing nothing, which
        // cannot be told apart from a broken checker.
        val result = runBlocking { graph.engine.run(monitor.id, force = true) }

        assertNotNull("a hand-driven check was refused during a pause", result)
        assertNotEquals(
            "a hand-driven check did not actually run",
            before,
            graph.store.snapshot.value.runtimes[monitor.id]?.lastCheckedAt,
        )
        // It still says nothing, because the pause is about being left alone.
        assertFalse(
            "a hand-driven check paged during a pause",
            graph.store.snapshot.value.runtimes[monitor.id]?.urgentActive == true,
        )
    }

    @Test
    fun theSilentPauseKeepsCheckingAndSaysNothing() {
        val graph = graph()
        val monitor = downMonitor("silent")
        runBlocking { graph.store.upsert(monitor) }
        runBlocking { graph.engine.run(monitor.id) }
        val before = graph.store.snapshot.value.runtimes[monitor.id]

        runBlocking {
            graph.engine.pauseAll(
                PauseState.timed(System.currentTimeMillis(), 60, PauseScope.ALERTS_ONLY),
            )
        }
        Thread.sleep(SETTLE_MS)
        runBlocking { graph.engine.run(monitor.id) }

        val after = graph.store.snapshot.value.runtimes[monitor.id]
        // The dashboard stays live: this is the half of the trade the user chose.
        assertNotEquals("no check ran under a silent pause", before?.lastCheckedAt, after?.lastCheckedAt)
        assertEquals(Health.DOWN, after?.health)
        // And it still says nothing. `lastAlertAt` is the load-bearing one: it
        // only moves when an alert is actually posted, so an unchanged value is
        // the proof that the failing check went out unannounced.
        assertEquals("a silent pause posted an alert", before?.lastAlertAt, after?.lastAlertAt)
        assertFalse("a silent pause left the down track alerting", after?.alerting == true)
        assertFalse("a silent pause paged", after?.urgentActive == true)
    }

    @Test
    fun resumingChecksImmediatelyRatherThanWaitingForTheInterval() {
        val graph = graph()
        val monitor = downMonitor("resume")
        runBlocking { graph.store.upsert(monitor) }
        runBlocking { graph.engine.run(monitor.id) }
        runBlocking {
            graph.engine.pauseAll(
                PauseState.timed(System.currentTimeMillis(), 60, PauseScope.STOP_CHECKS),
            )
        }
        val whilePaused = graph.store.snapshot.value.runtimes[monitor.id]?.lastCheckedAt

        Thread.sleep(SETTLE_MS)
        runBlocking { graph.engine.resumeAll() }

        // Coming back into signal and being told "up" by a dashboard that has not
        // looked in four hours is worse than being told nothing.
        assertFalse(graph.store.snapshot.value.pause.isActive(System.currentTimeMillis()))
        assertNotEquals(
            "resuming did not re-check",
            whilePaused,
            graph.store.snapshot.value.runtimes[monitor.id]?.lastCheckedAt,
        )
    }

    @Test
    fun aPauseThatHasRunOutBlocksNothing() {
        val graph = graph()
        val monitor = downMonitor("expired")
        runBlocking { graph.store.upsert(monitor) }

        // Written straight to the store already expired, which is what the engine
        // sees on the first wake-up after the deadline passes.
        runBlocking {
            graph.store.setPause(
                PauseState(
                    until = System.currentTimeMillis() - 1_000L,
                    scope = PauseScope.STOP_CHECKS,
                    since = System.currentTimeMillis() - 60_000L,
                ),
            )
        }
        runBlocking { graph.engine.run(monitor.id) }

        val runtime = graph.store.snapshot.value.runtimes[monitor.id]
        assertEquals("an expired pause was still blocking checks", Health.DOWN, runtime?.health)
        assertTrue((runtime?.lastCheckedAt ?: 0L) > 0L)
    }

    @Test
    fun anIndefinitePauseSurvivesAReadBackFromTheStore() {
        val graph = graph()
        runBlocking {
            graph.engine.pauseAll(PauseState.forever(System.currentTimeMillis(), PauseScope.ALERTS_ONLY))
        }

        // Read through the store rather than off the in-memory snapshot: the check
        // paths run in whatever process WorkManager hands them, so a pause that
        // only existed in memory would not be a pause at all.
        val persisted = runBlocking { graph.store.currentSnapshot() }.pause

        assertTrue(persisted.indefinite)
        assertTrue(persisted.isActive(System.currentTimeMillis() + 365L * 24 * 60 * 60_000L))
        assertEquals(PauseScope.ALERTS_ONLY, persisted.scope)
        assertFalse("a silent pause must not stop the checks", persisted.stopsChecks(System.currentTimeMillis()))
    }

    @Test
    fun theServiceIdlesAtItsCeilingThroughAPauseRatherThanSpinningAtTheFloor() {
        val graph = graph()
        runBlocking { graph.store.upsert(downMonitor("sleepy")) }
        runBlocking {
            graph.engine.pauseAll(
                PauseState.timed(System.currentTimeMillis(), 60, PauseScope.STOP_CHECKS),
            )
        }

        val delay = runBlocking { graph.engine.nextWakeDelayMs() }

        // Every monitor reads as overdue during a pause, and an overdue fleet is
        // what normally drags this to the 15s floor. The loop's own 60s ceiling
        // still applies, so what is asserted is the ceiling and not a full sleep.
        assertEquals("the service would have spun at the floor through the pause", 60_000L, delay)
    }

    private companion object {
        const val SETTLE_MS = 1_100L
    }
}
