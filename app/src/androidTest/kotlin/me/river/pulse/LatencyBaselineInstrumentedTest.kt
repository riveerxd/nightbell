package me.river.pulse

import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.river.pulse.PulseTestSupport.appContext
import me.river.pulse.PulseTestSupport.resetApp
import me.river.pulse.data.Pulse
import me.river.pulse.domain.AlertPolicy
import me.river.pulse.domain.GlobalSettings
import me.river.pulse.domain.Health
import me.river.pulse.domain.Monitor
import me.river.pulse.domain.NetworkBaseline
import me.river.pulse.domain.ReferenceSample
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The latency compensation driven through the real engine.
 *
 * [NetworkBaselineTest] covers the maths; this covers the wiring — that a stored
 * reference window actually changes what the engine persists and whether it
 * shouts. The reference window is seeded directly rather than probed, so the
 * tests do not depend on the emulator being able to reach anything (it has no
 * working DNS).
 */
@RunWith(AndroidJUnit4::class)
class LatencyBaselineInstrumentedTest {

    private lateinit var server: TinyHttpServer
    private val graph get() = Pulse.install(appContext)

    private val notifications: NotificationManager
        get() = appContext.getSystemService(NotificationManager::class.java)

    /** Milliseconds the fixture endpoint sleeps before answering. */
    @Volatile
    private var delayMs = 0L

    @Before
    fun setUp() {
        delayMs = 0
        server = TinyHttpServer { TinyHttpServer.Response(body = "ok", delayMs = delayMs) }
        notifications.cancelAll()
    }

    @After
    fun tearDown() {
        server.close()
        notifications.cancelAll()
    }

    private fun settings(baselineOn: Boolean, sloMs: Int) = GlobalSettings(
        motionIntensity = 0f,
        masterAlertsEnabled = true,
        defaultLatencySloMs = sloMs,
        latencyBaselineEnabled = baselineOn,
        defaultAlert = AlertPolicy(cooldownMinutes = 0, failureThreshold = 1),
    )

    private fun monitor() = Monitor(
        id = "latency-subject",
        name = "Slow service",
        url = server.url("/health"),
        timeoutSeconds = 20,
    )

    /** A window whose floor is ~40 ms and whose current reading is [currentMs]. */
    private fun seedReference(currentMs: Long) = runBlocking {
        val now = System.currentTimeMillis()
        val good = listOf(40L, 42L, 38L, 41L, 39L)
        val samples = good.mapIndexed { i, rtt ->
            ReferenceSample(at = now - (10 - i) * 60_000L, rttMs = rtt)
        } + List(3) { ReferenceSample(at = now - (2 - it) * 1_000L, rttMs = currentMs) }
        graph.store.updateReference { samples }
    }

    private fun runtime() = runBlocking {
        graph.store.currentSnapshot().runtimes["latency-subject"]
    }

    // ------------------------------------------------------------------- tests

    /**
     * The reported bug: a slow connection made a healthy service look degraded.
     * With the reference showing the same slowness, it must not.
     */
    @Test
    fun aSlowConnectionDoesNotMakeAServiceDegraded() = runBlocking {
        resetApp(settings(baselineOn = true, sloMs = 400))
        graph.store.upsert(monitor())
        // The endpoint answers in ~600 ms, which breaches a 400 ms SLO outright.
        delayMs = 600
        // And the reference is 900 ms above its floor, so the connection alone
        // more than accounts for it.
        seedReference(currentMs = 940)

        graph.engine.run("latency-subject")

        val runtime = runtime()!!
        assertTrue("the raw measurement should still breach", runtime.lastLatencyMs > 400)
        assertEquals(
            "but the monitor must not be called degraded",
            Health.UP,
            runtime.health,
        )
        assertFalse("and it must not be alerting", runtime.degradedAlerting)
    }

    /** The counter-requirement: real slowness still gets through. */
    @Test
    fun aGenuinelySlowServiceIsStillFlaggedOnAMediocreConnection() = runBlocking {
        resetApp(settings(baselineOn = true, sloMs = 400))
        graph.store.upsert(monitor())
        delayMs = 1_500
        // Only ~160 ms of the delay is attributable to the connection.
        seedReference(currentMs = 200)

        graph.engine.run("latency-subject")

        val runtime = runtime()!!
        assertEquals(
            "1.5s is slow even after discounting 160ms",
            Health.DEGRADED,
            runtime.health,
        )
    }

    @Test
    fun anUnusableConnectionSuppressesTheVerdictEntirely() = runBlocking {
        resetApp(settings(baselineOn = true, sloMs = 400))
        graph.store.upsert(monitor())
        delayMs = 700
        // 40 ms floor against a 3 s reference: far enough off to distrust
        // anything measured through this link.
        seedReference(currentMs = 3_000)

        graph.engine.run("latency-subject")

        val runtime = runtime()!!
        assertTrue("should be marked as not judgeable", runtime.lastLatencySuspect)
        assertEquals(Health.UP, runtime.health)
        assertFalse(runtime.degradedAlerting)
    }

    @Test
    fun theExcessIsRecordedSoTheUiCanExplainItself() = runBlocking {
        resetApp(settings(baselineOn = true, sloMs = 400))
        graph.store.upsert(monitor())
        delayMs = 500
        seedReference(currentMs = 340)

        graph.engine.run("latency-subject")

        val runtime = runtime()!!
        assertTrue(
            "expected roughly 300ms of excess, was ${runtime.lastNetworkExcessMs}",
            runtime.lastNetworkExcessMs in 250..350,
        )
        assertFalse(runtime.lastLatencySuspect)
    }

    @Test
    fun turningTheCompensationOffRestoresTheRawVerdict() = runBlocking {
        resetApp(settings(baselineOn = false, sloMs = 400))
        graph.store.upsert(monitor())
        delayMs = 600
        // The same window that suppressed the alert above must now be ignored.
        seedReference(currentMs = 940)

        graph.engine.run("latency-subject")

        val runtime = runtime()!!
        assertEquals(Health.DEGRADED, runtime.health)
        assertEquals("no excess should be recorded", 0L, runtime.lastNetworkExcessMs)
    }

    /**
     * A reference the network blocks yields no readings, and that must leave
     * behaviour exactly as it was rather than suppressing everything.
     */
    @Test
    fun noReferenceDataMeansTheRawVerdictStands() = runBlocking {
        resetApp(settings(baselineOn = true, sloMs = 400))
        graph.store.upsert(monitor())
        delayMs = 600
        graph.store.updateReference { emptyList() }

        graph.engine.run("latency-subject")

        assertEquals(Health.DEGRADED, runtime()!!.health)
    }

    @Test
    fun probingIsRateLimitedAcrossAPass() = runBlocking {
        resetApp(settings(baselineOn = true, sloMs = 0))
        // Point the reference at the fixture so probes are observable, and give
        // the pass several monitors to run.
        graph.store.updateSettings { it.copy(latencyReferenceUrl = server.url("/reference")) }
        repeat(4) { index ->
            graph.store.upsert(monitor().copy(id = "m$index", name = "Monitor $index"))
        }
        graph.store.updateReference { emptyList() }

        graph.engine.runAllDue(force = true)

        val probes = server.received.count { it.path == "/reference" }
        assertEquals(
            "four monitors should share one reference timing, saw $probes",
            1,
            probes,
        )
        assertTrue(
            "and that timing should have been stored",
            graph.store.currentSnapshot().reference.isNotEmpty(),
        )
    }

    @Test
    fun theStoredWindowStaysBounded() = runBlocking {
        resetApp(settings(baselineOn = true, sloMs = 0))
        val now = System.currentTimeMillis()
        graph.store.updateReference {
            List(NetworkBaseline.WINDOW * 2) { ReferenceSample(at = now - it * 1_000L, rttMs = 50) }
        }
        graph.store.updateReference { NetworkBaseline.record(it, 60, now) }

        assertTrue(
            "window must not grow without bound",
            graph.store.currentSnapshot().reference.size <= NetworkBaseline.WINDOW,
        )
    }
}
