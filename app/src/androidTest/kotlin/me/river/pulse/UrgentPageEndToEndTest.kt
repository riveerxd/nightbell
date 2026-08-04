package me.river.pulse

import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.river.pulse.data.Pulse
import me.river.pulse.data.alerts.AlertCenter
import me.river.pulse.data.work.PulseMonitorService
import me.river.pulse.domain.AlertPolicy
import me.river.pulse.domain.GlobalSettings
import me.river.pulse.domain.Monitor
import me.river.pulse.domain.SoundChoice
import me.river.pulse.domain.VibrationStyle
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the URGENT page is actually wired to a real outage, end to end: a
 * monitor that genuinely fails, folded through the real engine, reaching the real
 * foreground service, and arriving as the red card.
 *
 * The design harnesses next to this file only ever posted hand-built
 * notifications. Everything they showed could be true while the shipping path
 * still posted the old one — which it did, right up until this test existed.
 */
@RunWith(AndroidJUnit4::class)
class UrgentPageEndToEndTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val manager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        PulseTestSupport.resetApp(
            GlobalSettings(
                motionIntensity = 0f,
                strictForegroundMonitoring = true,
                defaultAlert = AlertPolicy(
                    sound = SoundChoice.ALARM,
                    vibrate = true,
                    vibrationStyle = VibrationStyle.HEARTBEAT,
                    failureThreshold = 1,
                ),
            ),
        )
        manager.cancelAll()
    }

    @After
    fun tearDown() {
        // No `stopService`: stopping a service that has not yet promoted itself
        // kills the process (see PulseMonitorService.sync). Clearing the store is
        // enough — the loop stands down on its next tick.
        runBlocking { PulseTestSupport.resetApp() }
        manager.cancelAll()
    }

    /**
     * Port 1 with nothing on it: a connection refused arrives in milliseconds and
     * needs no fixture server, so the failure is real and the test is fast.
     */
    private fun addFailingUrgentMonitor(id: String = "e2e-urgent") {
        val graph = Pulse.install(context)
        runBlocking {
            graph.store.upsert(
                Monitor(
                    id = id,
                    name = "Checkout API",
                    url = "http://127.0.0.1:1/health",
                    urgent = true,
                    urgentRepeatMinutes = 1,
                    timeoutSeconds = 5,
                    intervalMinutes = 1,
                ),
            )
        }
    }

    @Test
    fun aRealOutagePagesAndCountsItsReminders() {
        addFailingUrgentMonitor()
        val graph = Pulse.install(context)

        runBlocking { graph.engine.run("e2e-urgent") }

        val runtime = runBlocking { graph.store.currentSnapshot().runtimes["e2e-urgent"] }
        assertNotNull("the check must have produced a verdict", runtime)
        assertTrue("the monitor must be nagging", runtime!!.urgentState.nagging)
        assertEquals("the first page must be counted", 1, runtime.urgentPageCount)
        assertTrue("the outage clock must have started", runtime.urgentSinceAt > 0L)
    }

    /**
     * The counter is what makes the page able to say "reminder 3" honestly. Up to
     * 2.0.0 it was hardcoded to "Reminder #1" forever.
     */
    @Test
    fun acknowledgingResetsTheCounterAndTheClock() {
        addFailingUrgentMonitor()
        val graph = Pulse.install(context)
        runBlocking {
            graph.engine.run("e2e-urgent")
            graph.engine.acknowledgeUrgent("e2e-urgent")
        }
        val runtime = runBlocking { graph.store.currentSnapshot().runtimes["e2e-urgent"] }!!
        assertTrue("acknowledged", runtime.urgentAcknowledged)
        assertEquals("counter cleared with the outage", 0, runtime.urgentPageCount)
        assertEquals("clock cleared with the outage", 0L, runtime.urgentSinceAt)
    }

    /** Pausing a monitor must end its page, not leave one nothing can dismiss. */
    @Test
    fun pausingAMonitorEndsItsPage() {
        addFailingUrgentMonitor()
        val graph = Pulse.install(context)
        runBlocking {
            graph.engine.run("e2e-urgent")
            graph.store.setEnabled("e2e-urgent", false)
        }
        val runtime = runBlocking { graph.store.currentSnapshot().runtimes["e2e-urgent"] }!!
        assertTrue("no longer nagging", !runtime.urgentState.nagging)
        assertEquals("no page owed", 0, runtime.urgentPageCount)
    }

    /**
     * The service's foreground notification must *become* the page — that is the
     * only place the platform honours colorisation, so it is the only place the
     * card is red.
     */
    @Test
    fun theServiceNotificationBecomesThePage() {
        addFailingUrgentMonitor()
        val graph = Pulse.install(context)
        runBlocking { graph.engine.run("e2e-urgent") }

        PulseMonitorService.sync(context)
        PulseTestSupport.awaitTrue(timeoutMs = 20_000, description = "service is paging") {
            PulseMonitorService.isPaging() &&
                manager.activeNotifications.any { it.id == AlertCenter.SERVICE_NOTIFICATION_ID }
        }

        val page = manager.activeNotifications
            .first { it.id == AlertCenter.SERVICE_NOTIFICATION_ID }
        assertTrue(
            "the page must be colorised, or it is not the red card",
            page.notification.extras.getBoolean("android.colorized", false),
        )
        assertEquals(
            "the page must carry the down colour",
            0xFFB3121F.toInt(),
            page.notification.color,
        )

        // Captured as proof, since "is it actually red" is not a thing an
        // assertion can see.
        instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
        Thread.sleep(1_500)
        val shot: Bitmap = instrumentation.uiAutomation.takeScreenshot()
        val dir = File(context.filesDir, "screenshots").apply { mkdirs() }
        File(dir, "e2e-urgent-page.png").outputStream().use {
            shot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
