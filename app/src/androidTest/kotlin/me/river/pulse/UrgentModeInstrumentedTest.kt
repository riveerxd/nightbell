package me.river.pulse

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.river.pulse.NightbellTestSupport.appContext
import me.river.pulse.NightbellTestSupport.awaitTrue
import me.river.pulse.NightbellTestSupport.resetApp
import me.river.pulse.data.Nightbell
import me.river.pulse.data.alerts.AlertActionReceiver
import me.river.pulse.domain.AlertPolicy
import me.river.pulse.domain.GlobalSettings
import me.river.pulse.domain.Health
import me.river.pulse.domain.Monitor
import me.river.pulse.domain.MonitorRuntime
import me.river.pulse.domain.StatusExpectation
import me.river.pulse.domain.StatusMode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * URGENT mode driven through the real [me.river.pulse.data.check.CheckEngine]
 * against a local server, so the whole chain is exercised: check → decision →
 * persisted urgent state → notification action → acknowledgement.
 *
 * The pure state machine is covered by `UrgentAlertsTest`; this is about the
 * wiring around it.
 */
@RunWith(AndroidJUnit4::class)
class UrgentModeInstrumentedTest {

    private lateinit var server: TinyHttpServer
    private val graph get() = Nightbell.install(appContext)

    /** Flipped by the tests to take the endpoint up and down mid-run. */
    @Volatile
    private var status = 500

    @Before
    fun setUp() {
        status = 500
        server = TinyHttpServer { TinyHttpServer.Response(code = status, body = "body") }
        resetApp(
            GlobalSettings(
                motionIntensity = 0f,
                masterAlertsEnabled = true,
                // Keep the default 2.5 s budget out of the way: these tests are
                // about the urgent track, and a slow emulator must not flip a
                // monitor to DEGRADED mid-assertion.
                defaultLatencySloMs = 0,
                defaultAlert = AlertPolicy(cooldownMinutes = 0),
            ),
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun urgentMonitor(path: String, repeatMinutes: Int = 5) = Monitor(
        id = "urgent-monitor",
        name = "Urgent monitor",
        url = server.url(path),
        status = StatusExpectation(mode = StatusMode.EXACT, code = 200),
        intervalMinutes = 15,
        timeoutSeconds = 10,
        urgent = true,
        urgentRepeatMinutes = repeatMinutes,
        useGlobalAlerts = true,
    )

    private fun runtime(): MonitorRuntime = runBlocking {
        graph.store.currentSnapshot().runtimes["urgent-monitor"] ?: MonitorRuntime()
    }

    @Test
    fun goingDownStartsTheUrgentLoop() = runBlocking {
        status = 500
        graph.store.upsert(urgentMonitor("/health"))

        graph.engine.run("urgent-monitor")

        val state = runtime()
        assertEquals(Health.DOWN, state.health)
        assertTrue("an urgent outage should be nagging", state.urgentState.nagging)
        assertTrue(state.urgentActive)
        assertFalse(state.urgentAcknowledged)
    }

    @Test
    fun aNonUrgentMonitorNeverEntersTheLoop() = runBlocking {
        status = 500
        graph.store.upsert(urgentMonitor("/health").copy(urgent = false))

        graph.engine.run("urgent-monitor")

        val state = runtime()
        assertEquals(Health.DOWN, state.health)
        assertFalse("non-urgent monitors must not nag", state.urgentState.nagging)
        assertFalse(state.urgentActive)
    }

    @Test
    fun theNotificationActionAcknowledges() = runBlocking {
        status = 500
        graph.store.upsert(urgentMonitor("/health"))
        graph.engine.run("urgent-monitor")
        assertTrue(runtime().urgentState.nagging)

        // Exactly what tapping "Acknowledge" on the notification does.
        AlertActionReceiver().onReceive(
            appContext,
            Intent(AlertActionReceiver.ACTION_ACK_URGENT)
                .putExtra(AlertActionReceiver.EXTRA_MONITOR_ID, "urgent-monitor"),
        )

        awaitTrue(description = "urgent acknowledged from the notification") {
            runtime().urgentAcknowledged
        }
        val state = runtime()
        assertFalse("the loop must stop", state.urgentState.nagging)
        assertEquals("but the monitor stays down", Health.DOWN, state.health)
    }

    @Test
    fun theInAppAcknowledgeStopsTheLoop() = runBlocking {
        status = 500
        graph.store.upsert(urgentMonitor("/health"))
        graph.engine.run("urgent-monitor")
        assertTrue(runtime().urgentState.nagging)

        graph.engine.acknowledgeUrgent("urgent-monitor")

        val state = runtime()
        assertFalse(state.urgentState.nagging)
        assertTrue(state.urgentAcknowledged)
        assertEquals(Health.DOWN, state.health)
    }

    @Test
    fun anAcknowledgedOutageStaysQuietOnFurtherChecks() = runBlocking {
        status = 500
        graph.store.upsert(urgentMonitor("/health", repeatMinutes = 1))
        graph.engine.run("urgent-monitor")
        graph.engine.acknowledgeUrgent("urgent-monitor")

        // Re-check while still down. The acknowledgement has to survive it.
        graph.engine.run("urgent-monitor")

        val state = runtime()
        assertTrue(state.urgentAcknowledged)
        assertFalse(state.urgentActive)
        assertEquals(0, graph.engine.tickUrgent())
    }

    @Test
    fun recoveryResetsTheAcknowledgement() = runBlocking {
        status = 500
        graph.store.upsert(urgentMonitor("/health"))
        graph.engine.run("urgent-monitor")
        graph.engine.acknowledgeUrgent("urgent-monitor")
        assertTrue(runtime().urgentAcknowledged)

        status = 200
        graph.engine.run("urgent-monitor")

        val recovered = runtime()
        assertEquals(Health.UP, recovered.health)
        assertFalse("recovery must clear the acknowledgement", recovered.urgentAcknowledged)
        assertFalse(recovered.urgentActive)

        // The next outage shouts again.
        status = 503
        graph.engine.run("urgent-monitor")
        assertTrue("a fresh outage re-arms the loop", runtime().urgentState.nagging)
    }

    @Test
    fun mutingSuspendsTheLoopWithoutLosingTheAcknowledgement() = runBlocking {
        status = 500
        graph.store.upsert(urgentMonitor("/health"))
        graph.engine.run("urgent-monitor")
        assertTrue(runtime().urgentState.nagging)

        graph.engine.mute("urgent-monitor", 60 * 60 * 1000L)
        graph.engine.run("urgent-monitor")

        val muted = runtime()
        assertFalse("a muted monitor must not nag", muted.urgentState.nagging)
        assertEquals(Health.DOWN, muted.health)
    }

    /**
     * Regression, 1.1.0: an urgent notification could outlive the outage.
     *
     * Reproduced on a real device — six monitors all reporting Operational with
     * two `category=alarm` notifications still posted, and `ongoing` set, so
     * they could not be swiped away either. The state said idle, so nothing
     * would ever cancel them: `run()` returned NONE from an already-idle state
     * and `tickUrgent()` skipped non-nagging monitors outright.
     */
    @Test
    fun aHealthyCheckClearsAnOrphanedUrgentNotification() = runBlocking {
        status = 200
        graph.store.upsert(urgentMonitor("/health"))
        // The shape the bug leaves behind: notification posted, state idle.
        graph.store.updateRuntime("urgent-monitor") {
            it.copy(health = Health.UP, urgentActive = false, urgentAcknowledged = false)
        }

        graph.engine.run("urgent-monitor")

        // The check must have issued a cancel rather than reasoning that an
        // idle state means there is nothing to cancel.
        val state = runtime()
        assertFalse(state.urgentState.nagging)
        assertEquals(Health.UP, state.health)
        assertEquals("nothing left to re-alert", 0, graph.engine.tickUrgent())
    }

    @Test
    fun tickUrgentReconcilesMonitorsThatAreNoLongerNagging() = runBlocking {
        status = 200
        graph.store.upsert(urgentMonitor("/health"))
        graph.engine.run("urgent-monitor")

        // tickUrgent used to `continue` past these, which is what made an
        // orphan permanent. It now sweeps them; it must still fire nothing.
        assertEquals(0, graph.engine.tickUrgent())
        assertFalse(runtime().urgentState.nagging)
    }

    @Test
    fun concurrentChecksOfOneMonitorDoNotInterleave() = runBlocking {
        status = 500
        graph.store.upsert(urgentMonitor("/health"))

        // The race that produced the orphan: overlapping runs of one monitor,
        // each deriving its notification action from a snapshot read before the
        // other had written. The per-monitor lock serialises them.
        val jobs = List(4) { async { graph.engine.run("urgent-monitor") } }
        jobs.awaitAll()

        val state = runtime()
        assertEquals(Health.DOWN, state.health)
        assertTrue("state must be self-consistent after concurrent runs", state.urgentState.nagging)

        // And recovery still fully clears it.
        status = 200
        graph.engine.run("urgent-monitor")
        assertFalse(runtime().urgentState.nagging)
    }

    /**
     * Regression, 1.1.0: a deleted monitor left its urgent notification behind.
     *
     * Found on a real device — a notification whose monitor no longer existed,
     * so no per-monitor loop could ever visit it, and `ongoing` meant the user
     * could not swipe it away either. The sweep reconciles against the
     * notifications that are actually posted, not against monitor state, which
     * is the only way to see this class of orphan.
     */
    @Test
    fun theSweepClearsNotificationsBelongingToNoMonitor() = runBlocking {
        status = 500
        graph.store.upsert(urgentMonitor("/health"))
        graph.engine.run("urgent-monitor")
        val urgentId = graph.alerts.urgentIdOf("urgent-monitor")
        // Posting and cancelling are both binder round-trips to system_server,
        // so every assertion here has to poll rather than read once.
        awaitTrue(description = "urgent notification posted") {
            urgentId in graph.alerts.activeAlertIds()
        }

        // Delete straight from the store, simulating the pre-fix delete path
        // that only cancelled the down notification.
        graph.store.delete("urgent-monitor")

        graph.engine.tickUrgent()

        awaitTrue(description = "orphan from a deleted monitor is swept") {
            urgentId !in graph.alerts.activeAlertIds()
        }
    }

    @Test
    fun deletingAMonitorCancelsAllThreeOfItsNotifications() = runBlocking {
        status = 500
        graph.store.upsert(urgentMonitor("/health"))
        graph.engine.run("urgent-monitor")

        graph.alerts.cancelAll("urgent-monitor")

        awaitTrue(description = "all three notification ids cleared") {
            val live = graph.alerts.activeAlertIds()
            graph.alerts.urgentIdOf("urgent-monitor") !in live &&
                graph.alerts.downIdOf("urgent-monitor") !in live &&
                graph.alerts.degradedIdOf("urgent-monitor") !in live
        }
    }

    @Test
    fun tickUrgentRespectsTheRepeatGap() = runBlocking {
        status = 500
        graph.store.upsert(urgentMonitor("/health", repeatMinutes = 60))
        graph.engine.run("urgent-monitor")

        // The first alert just fired, so an immediate tick has nothing to do.
        assertEquals(0, graph.engine.tickUrgent())

        // Rewind the clock on the stored state past the repeat gap.
        graph.store.updateRuntime("urgent-monitor") {
            it.copy(lastUrgentAlertAt = System.currentTimeMillis() - 61 * 60_000L)
        }
        assertEquals(1, graph.engine.tickUrgent())
    }
}
