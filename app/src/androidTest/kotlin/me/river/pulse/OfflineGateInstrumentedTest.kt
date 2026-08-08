package me.river.pulse

import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.river.pulse.NightbellTestSupport.appContext
import me.river.pulse.NightbellTestSupport.resetApp
import me.river.pulse.data.Nightbell
import me.river.pulse.domain.AlertPolicy
import me.river.pulse.domain.GlobalSettings
import me.river.pulse.domain.Health
import me.river.pulse.domain.Monitor
import me.river.pulse.domain.MonitorRuntime
import me.river.pulse.domain.UrgentAlerts
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Losing signal must not look like an outage.
 *
 * Reported from real use: with no wifi or data every monitor failed at once and
 * the phone filled up with "down" notifications that were all wrong. These tests
 * pin the two paths that produced them — the check pass and the urgent re-nag —
 * by driving the real engine with connectivity forced off.
 *
 * The monitor deliberately points at a port nothing is listening on, so if the
 * gate ever stops working these tests fail by *recording a real failure* rather
 * than by timing out on something ambiguous.
 */
@RunWith(AndroidJUnit4::class)
class OfflineGateInstrumentedTest {

    private val graph get() = Nightbell.install(appContext)

    private val notifications: NotificationManager
        get() = appContext.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        resetApp(
            GlobalSettings(
                motionIntensity = 0f,
                masterAlertsEnabled = true,
                defaultLatencySloMs = 0,
                defaultAlert = AlertPolicy(cooldownMinutes = 0, failureThreshold = 1),
            ),
        )
        notifications.cancelAll()
    }

    @After
    fun tearDown() {
        // The graph is a process-wide singleton, so a leaked override would make
        // every later test in this process silently stop checking.
        graph.engine.isOnline = graph.network::isOnline
        notifications.cancelAll()
    }

    private fun deadMonitor() = Monitor(
        id = "offline-subject",
        name = "Unreachable service",
        // Nothing listens here; reaching it would fail in ~milliseconds.
        url = "http://127.0.0.1:1/health",
        timeoutSeconds = 5,
        intervalMinutes = 1,
    )

    @Test
    fun offlineCheckRecordsNothingAndAlertsNobody() = runBlocking {
        graph.store.upsert(deadMonitor())
        graph.engine.isOnline = { false }

        val result = graph.engine.run("offline-subject")

        assertNull("offline check should not produce a result", result)
        val runtime = graph.store.currentSnapshot().runtimes["offline-subject"]
        // A null runtime is also a pass — the point is that no failure landed.
        assertTrue(
            "offline check must not record a sample, had ${runtime?.samples?.size}",
            runtime == null || runtime.samples.isEmpty(),
        )
        assertEquals(
            "health must stay unknown, not DOWN",
            Health.UNKNOWN,
            runtime?.health ?: Health.UNKNOWN,
        )
        assertTrue(
            "offline check must post no notification",
            notifications.activeNotifications.none { it.id >= 100_000 },
        )
    }

    @Test
    fun offlineCheckDoesNotLeaveTheMonitorSpinning() = runBlocking {
        graph.store.upsert(deadMonitor())
        graph.engine.isOnline = { false }

        graph.engine.run("offline-subject")

        // Regression guard for where the gate sits: bailing out *after*
        // `markChecking(true)` would strand the spinner on the card forever,
        // because the matching `false` lives in the `finally` of a block we
        // never entered.
        val card = graph.store.cards.first().first { it.monitor.id == "offline-subject" }
        assertFalse("monitor must not be left marked as checking", card.checking)
    }

    @Test
    fun forcedPassIsStillSkippedWhileOffline() = runBlocking {
        graph.store.upsert(deadMonitor())
        graph.engine.isOnline = { false }

        // `force` means "don't wait for the interval", not "check even though it
        // cannot succeed" — a pull-to-refresh offline must stay silent.
        assertEquals(0, graph.engine.runAllDue(force = true))

        val runtime = graph.store.currentSnapshot().runtimes["offline-subject"]
        assertTrue(runtime == null || runtime.samples.isEmpty())
    }

    @Test
    fun offlineUrgentTickDoesNotRepeatTheNag() = runBlocking {
        // A monitor already nagging when signal dropped: the state says "down",
        // but offline we have not verified that and must not keep shouting.
        graph.store.upsert(deadMonitor().copy(urgent = true, urgentRepeatMinutes = 1))
        graph.store.updateRuntime("offline-subject") {
            MonitorRuntime(
                health = Health.DOWN,
                lastCheckedAt = System.currentTimeMillis() - 600_000,
                consecutiveFailures = 5,
                alerting = true,
                lastMessage = "Connection refused",
            ).withUrgentState(
                UrgentAlerts.State(
                    active = true,
                    acknowledged = false,
                    lastAlertAt = System.currentTimeMillis() - 600_000,
                ),
            )
        }
        graph.engine.isOnline = { false }

        assertEquals("urgent must not re-fire while offline", 0, graph.engine.tickUrgent())
    }

    /**
     * The gate must not become a way to permanently disable monitoring: with
     * connectivity restored the very same setup checks and records normally.
     */
    @Test
    fun checksResumeWhenConnectivityReturns() = runBlocking {
        graph.store.upsert(deadMonitor())
        graph.engine.isOnline = { false }
        assertEquals(0, graph.engine.runAllDue(force = true))

        graph.engine.isOnline = { true }
        assertEquals(1, graph.engine.runAllDue(force = true))

        val runtime = graph.store.currentSnapshot().runtimes["offline-subject"]
        assertTrue("online check should record a sample", runtime!!.samples.isNotEmpty())
        assertEquals("and it should be the real failure", Health.DOWN, runtime.health)
    }
}
