package me.river.nightbell

import android.Manifest
import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import me.river.nightbell.NightbellTestSupport.awaitTrue
import me.river.nightbell.data.Nightbell
import me.river.nightbell.data.alerts.AlertActionReceiver
import me.river.nightbell.data.alerts.AlertCenter
import me.river.nightbell.domain.AlertPolicy
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.SoundChoice
import me.river.nightbell.domain.VibrationStyle
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies the part users actually feel: a real notification lands, on a channel
 * carrying the requested sound and vibration pattern, and the escalation rules
 * (threshold, cooldown, recovery, mute) hold on-device.
 */
@RunWith(AndroidJUnit4::class)
class AlertsInstrumentedTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private lateinit var server: TinyHttpServer
    private val graph get() = Nightbell.install(NightbellTestSupport.appContext)
    private val notificationManager: NotificationManager
        get() = NightbellTestSupport.appContext.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        notificationManager.cancelAll()
        server = TinyHttpServer { request ->
            if (request.path.startsWith("/ok")) {
                TinyHttpServer.Response(body = "fine")
            } else {
                TinyHttpServer.Response(code = 500, reason = "Server Error", body = "boom")
            }
        }
    }

    @After
    fun tearDown() {
        notificationManager.cancelAll()
        server.close()
    }

    private fun seed(policy: AlertPolicy, path: String, masterEnabled: Boolean = true): Monitor {
        NightbellTestSupport.resetApp(
            GlobalSettings(
                motionIntensity = 0f,
                masterAlertsEnabled = masterEnabled,
                defaultAlert = policy,
            ),
        )
        val monitor = Monitor(
            id = "alert-test",
            name = "Alert Target",
            url = server.url(path),
            timeoutSeconds = 10,
            useGlobalAlerts = true,
        )
        runBlocking { graph.store.upsert(monitor) }
        return monitor
    }

    private fun activeTitles(): List<String> =
        notificationManager.activeNotifications.mapNotNull {
            it.notification.extras.getCharSequence("android.title")?.toString()
        }

    @Test
    fun downNotificationIsRaisedWithTheMonitorName() {
        seed(AlertPolicy(), "/broken")
        runBlocking { graph.engine.run("alert-test") }

        awaitTrue(description = "a down notification is posted") {
            activeTitles().any { it.contains("Alert Target") && it.contains("down") }
        }
        val posted = notificationManager.activeNotifications.first()
        val text = posted.notification.extras.getCharSequence("android.text")?.toString().orEmpty()
        assertTrue("notification body should explain the failure, was '$text'", text.contains("500"))
        assertTrue("should carry inline actions", (posted.notification.actions?.size ?: 0) >= 2)
    }

    @Test
    fun channelCarriesTheRequestedSoundAndVibrationPattern() {
        val policy = AlertPolicy(
            sound = SoundChoice.ALARM,
            vibrate = true,
            vibrationStyle = VibrationStyle.SOS,
        )
        seed(policy, "/broken")
        runBlocking { graph.engine.run("alert-test") }
        awaitTrue(description = "notification posted") { activeTitles().isNotEmpty() }

        val channelId = graph.alerts.channelFor(policy, AlertCenter.Severity.DOWN, silent = false)
        val channel = notificationManager.getNotificationChannel(channelId)
        assertNotNull("channel $channelId was not created", channel)
        assertTrue("channel should vibrate", channel!!.shouldVibrate())
        assertArrayEqualsLong(VibrationStyle.SOS.pattern, channel.vibrationPattern)
        assertNotNull("alarm channel should have a sound", channel.sound)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
    }

    @Test
    fun silentPolicyProducesASoundlessChannel() {
        val policy = AlertPolicy(sound = SoundChoice.SILENT, vibrate = false)
        seed(policy, "/broken")
        runBlocking { graph.engine.run("alert-test") }
        awaitTrue(description = "notification posted") { activeTitles().isNotEmpty() }

        val channelId = graph.alerts.channelFor(policy, AlertCenter.Severity.DOWN, silent = false)
        val channel = notificationManager.getNotificationChannel(channelId)!!
        assertNull("silent channel must have no sound", channel.sound)
        assertFalse("silent channel must not vibrate", channel.shouldVibrate())
    }

    /**
     * Comments get a channel of their own, at the importance news gets.
     *
     * Both halves can only be seen here. The importance arm is the one branch in
     * `channelFor` the compiler does not demand, so without it a comment would
     * peek over the screen with a sound; and importance freezes when the channel
     * is created, so shipping it wrong once cannot be repaired by fixing the code.
     */
    @Test
    fun commentsGetTheirOwnChannelAtNewsImportance() {
        val policy = AlertPolicy(sound = SoundChoice.DEFAULT_NOTIFICATION, vibrate = true)
        val comments = graph.alerts.channelFor(policy, AlertCenter.Severity.COMMENTS, silent = false)
        val news = graph.alerts.channelFor(policy, AlertCenter.Severity.NEWS, silent = false)
        assertTrue("comments must be mutable without touching news", comments != news)

        val channel = notificationManager.getNotificationChannel(comments)
        assertNotNull("channel $comments was not created", channel)
        assertEquals(
            "a reply must never peek over the screen",
            NotificationManager.IMPORTANCE_DEFAULT,
            channel!!.importance,
        )
        // Its own group, because a group whose children sit on two channels has
        // no defined alerting behaviour.
        assertEquals("nightbell.group.comments", channel.group)
        assertTrue(
            "the id must name the family it belongs to",
            comments.startsWith("nightbell.comments."),
        )
        // Turning comments all the way down must leave the news channel alone.
        assertEquals(
            NotificationManager.IMPORTANCE_DEFAULT,
            notificationManager.getNotificationChannel(news)!!.importance,
        )
        assertEquals("nightbell.group.news", notificationManager.getNotificationChannel(news)!!.group)
    }

    @Test
    fun differentPoliciesGetDistinctChannels() {
        val a = AlertPolicy(sound = SoundChoice.DEFAULT_NOTIFICATION, vibrationStyle = VibrationStyle.TICK)
        val b = AlertPolicy(sound = SoundChoice.ALARM, vibrationStyle = VibrationStyle.HEARTBEAT)
        val idA = graph.alerts.channelFor(a, AlertCenter.Severity.DOWN, silent = false)
        val idB = graph.alerts.channelFor(b, AlertCenter.Severity.DOWN, silent = false)
        val idRecovery = graph.alerts.channelFor(a, AlertCenter.Severity.RECOVERY, silent = false)
        assertTrue(idA != idB)
        assertTrue(idA != idRecovery)
        assertArrayEqualsLong(
            VibrationStyle.HEARTBEAT.pattern,
            notificationManager.getNotificationChannel(idB)!!.vibrationPattern,
        )
        assertEquals(
            NotificationManager.IMPORTANCE_DEFAULT,
            notificationManager.getNotificationChannel(idRecovery)!!.importance,
        )
    }

    @Test
    fun failureThresholdDelaysTheFirstAlert() {
        seed(AlertPolicy(failureThreshold = 3), "/broken")

        runBlocking { graph.engine.run("alert-test") }
        Thread.sleep(400)
        assertTrue("should stay quiet on failure 1", activeTitles().isEmpty())

        runBlocking { graph.engine.run("alert-test") }
        Thread.sleep(400)
        assertTrue("should stay quiet on failure 2", activeTitles().isEmpty())

        runBlocking { graph.engine.run("alert-test") }
        awaitTrue(description = "alert on the third consecutive failure") {
            activeTitles().any { it.contains("Alert Target") }
        }
    }

    @Test
    fun cooldownStopsASecondAlertForTheSameOutage() {
        seed(AlertPolicy(cooldownMinutes = 30, repeatEnabled = false), "/broken")
        runBlocking { graph.engine.run("alert-test") }
        awaitTrue(description = "first alert") { activeTitles().isNotEmpty() }
        val firstAlertAt = runBlocking {
            graph.store.currentSnapshot().runtimes.getValue("alert-test").lastAlertAt
        }
        assertTrue(firstAlertAt > 0)

        runBlocking { graph.engine.run("alert-test") }
        Thread.sleep(500)
        val secondAlertAt = runBlocking {
            graph.store.currentSnapshot().runtimes.getValue("alert-test").lastAlertAt
        }
        assertEquals("no second alert should have been raised", firstAlertAt, secondAlertAt)
    }

    @Test
    fun recoveryNotificationReplacesTheOutage() {
        val monitor = seed(AlertPolicy(), "/broken")
        runBlocking { graph.engine.run("alert-test") }
        awaitTrue(description = "down alert") {
            activeTitles().any { it.contains("down") }
        }
        assertTrue(runBlocking { graph.store.currentSnapshot().runtimes.getValue("alert-test").alerting })

        // Point the same monitor at a healthy endpoint and re-check.
        runBlocking { graph.store.upsert(monitor.copy(url = server.url("/ok"))) }
        runBlocking { graph.engine.run("alert-test") }

        awaitTrue(description = "recovery alert") {
            activeTitles().any { it.contains("is back") }
        }
        val runtime = runBlocking { graph.store.currentSnapshot().runtimes.getValue("alert-test") }
        assertFalse("should no longer be alerting", runtime.alerting)
    }

    @Test
    fun masterMuteSuppressesEverything() {
        seed(AlertPolicy(), "/broken", masterEnabled = false)
        runBlocking { graph.engine.run("alert-test") }
        Thread.sleep(700)
        assertTrue("master mute must silence alerts", activeTitles().isEmpty())
        assertTrue(
            "the check itself must still run",
            runBlocking { graph.store.currentSnapshot().runtimes.getValue("alert-test").samples.isNotEmpty() },
        )
    }

    @Test
    fun mutingAMonitorSuppressesItsAlerts() {
        seed(AlertPolicy(), "/broken")
        runBlocking { graph.engine.mute("alert-test", 60 * 60 * 1000L) }
        runBlocking { graph.engine.run("alert-test") }
        Thread.sleep(700)
        assertTrue("muted monitor must not notify", activeTitles().isEmpty())

        runBlocking { graph.engine.unmute("alert-test") }
        runBlocking { graph.engine.run("alert-test") }
        awaitTrue(description = "alert after un-mute") { activeTitles().isNotEmpty() }
    }

    @Test
    fun previewFiresAHapticAndANotification() {
        NightbellTestSupport.resetApp()
        graph.alerts.previewPolicy(AlertPolicy(vibrate = true, vibrationStyle = VibrationStyle.DOUBLE_PULSE))
        awaitTrue(description = "preview notification") {
            activeTitles().any { it.contains("Preview monitor") }
        }
    }

    @Test
    fun notificationActionsRecheckAndMute() {
        val monitor = seed(AlertPolicy(), "/broken")
        runBlocking { graph.engine.run("alert-test") }
        awaitTrue(description = "down alert") { activeTitles().isNotEmpty() }

        // "Mute 1h" — dismisses the alert and stops further ones.
        AlertActionReceiver().onReceive(
            NightbellTestSupport.appContext,
            android.content.Intent(AlertActionReceiver.ACTION_MUTE_1H)
                .putExtra(AlertActionReceiver.EXTRA_MONITOR_ID, monitor.id),
        )
        awaitTrue(description = "mute applied") {
            runBlocking {
                graph.store.currentSnapshot().runtimes.getValue("alert-test").mutedUntil > System.currentTimeMillis()
            }
        }
        awaitTrue(description = "notification dismissed by mute") { activeTitles().isEmpty() }

        // "Re-check now" — runs the check again even while muted.
        val samplesBefore = runBlocking {
            graph.store.currentSnapshot().runtimes.getValue("alert-test").samples.size
        }
        AlertActionReceiver().onReceive(
            NightbellTestSupport.appContext,
            android.content.Intent(AlertActionReceiver.ACTION_RECHECK)
                .putExtra(AlertActionReceiver.EXTRA_MONITOR_ID, monitor.id),
        )
        awaitTrue(description = "re-check ran") {
            runBlocking {
                graph.store.currentSnapshot().runtimes.getValue("alert-test").samples.size
            } > samplesBefore
        }
        assertTrue("muted monitor must stay silent", activeTitles().isEmpty())
    }

    private fun assertArrayEqualsLong(expected: LongArray, actual: LongArray?) {
        assertNotNull("vibration pattern missing", actual)
        assertEquals(
            "pattern mismatch: expected ${expected.toList()} but was ${actual!!.toList()}",
            expected.toList(),
            actual.toList(),
        )
    }
}
