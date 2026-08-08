package me.river.nightbell

import androidx.test.ext.junit.runners.AndroidJUnit4
import me.river.nightbell.NightbellTestSupport.appContext
import me.river.nightbell.NightbellTestSupport.awaitTrue
import me.river.nightbell.NightbellTestSupport.resetApp
import me.river.nightbell.data.Nightbell
import me.river.nightbell.data.alerts.AlertCenter
import me.river.nightbell.domain.AlertPolicy
import me.river.nightbell.domain.CheckerHealth
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.Health
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.StatusExpectation
import me.river.nightbell.domain.StatusMode
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The reported bug, driven end to end through the real [Nightbell] graph on a device.
 *
 * Reproduces the exact shape of the report: a check is cancelled while it is in
 * flight — as WorkManager's `REPLACE`, a stopping foreground service and a closing
 * `viewModelScope` all do routinely — and asserts that afterwards there is no
 * notification, no `DOWN` health, no failure streak, and no urgent state. Before
 * 1.6.0 every one of those was set, and the notification said
 * "URGENT · … is down / Checker crashed".
 *
 * Device-only: it needs the real `AlertCenter`, `NotificationManager` and
 * DataStore.
 */
@RunWith(AndroidJUnit4::class)
class CheckerCancellationInstrumentedTest {

    private val graph get() = Nightbell.install(appContext)

    /** A URL that connects and then stalls, so the check is reliably mid-flight. */
    private lateinit var server: TinyHttpServer

    private fun monitor(id: String, path: String) = Monitor(
        id = id,
        name = id,
        kind = MonitorKind.HTTP_STATUS,
        url = server.url(path),
        status = StatusExpectation(mode = StatusMode.ANY_SUCCESS),
        timeoutSeconds = 30,
        intervalMinutes = 15,
        // Urgent on, threshold 1, repeat every minute: the configuration on the
        // device that reported this, and the one that turned a cancelled check
        // into an un-dismissable DND-bypassing alarm.
        urgent = true,
        urgentRepeatMinutes = 1,
        useGlobalAlerts = true,
    )

    @Before
    fun setUp() {
        resetApp(
            GlobalSettings(
                motionIntensity = 0f,
                masterAlertsEnabled = true,
                defaultAlert = AlertPolicy(failureThreshold = 1, vibrate = true),
            ),
        )
        graph.engine.resetCheckerHealth("test setup")
        graph.alerts.cancelEverything()
    }

    @Test
    fun cancellingACheckInFlightRecordsAndAnnouncesNothing() {
        TinyHttpServer { TinyHttpServer.Response(body = "ok", delayMs = 4_000) }.use { srv ->
            server = srv
            val target = monitor("cancel-me", "/stall")
            runBlocking { graph.store.upsert(target) }

            val scope = CoroutineScope(Job() + Dispatchers.Default)
            var caught: Throwable? = null
            val job = scope.launch {
                try {
                    graph.engine.run(target.id)
                } catch (error: Throwable) {
                    caught = error
                }
            }
            runBlocking {
                delay(800) // the request is out, the response is not back
                job.cancel()
                job.join()
            }

            assertTrue(
                "cancellation must reach the caller, got ${caught?.let { it::class.java.name }}",
                caught is CancellationException,
            )

            val runtime = runBlocking { graph.store.currentSnapshot() }.runtimes[target.id]
            // Nothing was learned, so nothing may be recorded.
            assertTrue(
                "a cancelled check must not write a DOWN verdict, saw ${runtime?.health}",
                runtime == null || runtime.health != Health.DOWN,
            )
            assertEquals(0, runtime?.consecutiveFailures ?: 0)
            assertFalse(runtime?.alerting ?: false)
            assertFalse("the urgent loop must not start", runtime?.urgentActive ?: false)
            assertTrue(
                "the fabricated message must be gone for good",
                runtime?.lastMessage.orEmpty() != CheckerHealth.LEGACY_CRASH_MESSAGE,
            )

            // And nothing on screen: not the down id, not the urgent id, not the
            // checker-health id.
            val active = graph.alerts.activeAlertIds()
            assertFalse(graph.alerts.downIdOf(target.id) in active)
            assertFalse(graph.alerts.urgentIdOf(target.id) in active)
            assertNull(
                "no checker-health notification for a cancellation",
                activeById(AlertCenter.CHECKER_HEALTH_NOTIFICATION_ID),
            )

            // The checker itself is still considered healthy.
            assertEquals(CheckerHealth.Kind.HEALTHY, graph.engine.checkerHealth.value.kind)
        }
    }

    @Test
    fun cancellingAWholeFleetPassAnnouncesNothing() {
        // The reported shape: six monitors, one cancellation event, six alerts.
        TinyHttpServer { TinyHttpServer.Response(body = "ok", delayMs = 2_500) }.use { srv ->
            server = srv
            val ids = (1..6).map { "fleet-$it" }
            runBlocking { ids.forEach { graph.store.upsert(monitor(it, "/stall-$it")) } }

            val scope = CoroutineScope(Job() + Dispatchers.Default)
            val job = scope.launch { graph.engine.runAllDue(force = true) }
            runBlocking {
                delay(900)
                job.cancel()
                job.join()
            }

            val snapshot = runBlocking { graph.store.currentSnapshot() }
            val active = graph.alerts.activeAlertIds()
            ids.forEach { id ->
                val runtime = snapshot.runtimes[id]
                assertTrue(
                    "$id must not be marked down by a cancelled pass",
                    runtime == null || runtime.health != Health.DOWN,
                )
                assertFalse("$id must hold no urgent state", runtime?.urgentActive ?: false)
                assertFalse("$id must have no urgent notification", graph.alerts.urgentIdOf(id) in active)
                assertFalse("$id must have no down notification", graph.alerts.downIdOf(id) in active)
            }
            assertEquals(CheckerHealth.Kind.HEALTHY, graph.engine.checkerHealth.value.kind)
        }
    }

    @Test
    fun aRealOutageStillAlertsAfterTheFix() {
        // The guard that matters most: none of this may have made Nightbell quiet
        // about genuine failures.
        TinyHttpServer { TinyHttpServer.Response(code = 503, reason = "Service Unavailable") }.use { srv ->
            server = srv
            val target = monitor("really-down", "/down").copy(urgent = false)
            runBlocking {
                graph.store.upsert(target)
                graph.engine.run(target.id)
            }
            val runtime = runBlocking { graph.store.currentSnapshot() }.runtimes.getValue(target.id)
            assertEquals(Health.DOWN, runtime.health)
            assertEquals(1, runtime.consecutiveFailures)
            assertTrue(runtime.alerting)
            assertTrue(runtime.lastMessage.contains("503"))
            awaitTrue(description = "the down notification is posted") {
                graph.alerts.downIdOf(target.id) in graph.alerts.activeAlertIds()
            }
        }
    }

    @Test
    fun aCancelledCheckLeavesTheMonitorDueSoThatItIsRetried() {
        // The flip side of recording nothing: a cancelled check must not look like a
        // completed one, or the monitor would wait out a whole interval before
        // being retried.
        TinyHttpServer { TinyHttpServer.Response(body = "ok", delayMs = 3_000) }.use { srv ->
            server = srv
            val target = monitor("retry-me", "/stall")
            runBlocking { graph.store.upsert(target) }

            val scope = CoroutineScope(Job() + Dispatchers.Default)
            val job = scope.launch { graph.engine.run(target.id) }
            runBlocking {
                delay(700)
                job.cancel()
                job.join()
                assertTrue(
                    "a cancelled check must leave the monitor due for retry",
                    graph.engine.isDue(target.id),
                )
            }
        }
    }

    @Test
    fun aStoppedStrictServiceLeavesNoCrashClaimBehind() {
        val engine = graph.engine
        engine.resetCheckerHealth("baseline")
        assertEquals(CheckerHealth.Kind.HEALTHY, engine.checkerHealth.value.kind)
        // resetCheckerHealth is what the service calls on the way down; it must be
        // safe to call repeatedly and must always leave the notification cancelled.
        repeat(3) { engine.resetCheckerHealth("repeat $it") }
        assertNull(activeById(AlertCenter.CHECKER_HEALTH_NOTIFICATION_ID))
        assertEquals(CheckerHealth.State.Healthy, engine.checkerHealth.value)
    }

    private fun activeById(id: Int): Int? {
        val manager = appContext.getSystemService(android.app.NotificationManager::class.java)
        return runCatching {
            manager.activeNotifications.firstOrNull { it.id == id }?.id
        }.getOrNull()
    }
}
