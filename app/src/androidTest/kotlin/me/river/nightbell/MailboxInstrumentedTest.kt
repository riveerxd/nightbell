package me.river.nightbell

import android.Manifest
import android.app.NotificationManager
import android.service.notification.StatusBarNotification
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.runBlocking
import me.river.nightbell.NightbellTestSupport.appContext
import me.river.nightbell.data.Nightbell
import me.river.nightbell.data.alerts.AlertActionReceiver
import me.river.nightbell.data.auth.CredentialVault
import me.river.nightbell.domain.AlertPolicy
import me.river.nightbell.domain.FailureKind
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.Health
import me.river.nightbell.domain.MailWatch
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.MonitorRuntime
import me.river.nightbell.domain.TlsTrust
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A mailbox monitor through the real engine, vault, store and shade, against a
 * real IMAP conversation on loopback. What the JVM suite cannot show: that new
 * mail actually reaches the notification shade, on which channel, and that a
 * refused password stops costing connections.
 */
@RunWith(AndroidJUnit4::class)
class MailboxInstrumentedTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private val graph get() = Nightbell.install(appContext)
    private val notifications: NotificationManager
        get() = appContext.getSystemService(NotificationManager::class.java)
    private lateinit var imap: TinyImap

    @Before
    fun setUp() {
        notifications.cancelAll()
        NightbellTestSupport.resetApp(
            GlobalSettings(
                motionIntensity = 0f,
                masterAlertsEnabled = true,
                defaultAlert = AlertPolicy(failureThreshold = 1, cooldownMinutes = 0, quickRetry = false),
                confirmOutagesEnabled = false,
                latencyBaselineEnabled = false,
            ),
        )
        imap = TinyImap(implicitTls = TinyTls.selfSigned())
    }

    @After
    fun tearDown() {
        imap.close()
        notifications.cancelAll()
        runBlocking { graph.vault.delete(ID) }
    }

    private fun mailbox(watch: MailWatch = MailWatch()) = Monitor(
        id = ID,
        name = "Work inbox",
        kind = MonitorKind.MAILBOX,
        tlsTrust = TlsTrust.ANY,
        timeoutSeconds = 10,
        mail = watch.copy(host = "127.0.0.1", port = imap.port, username = "me@example.com"),
    )

    private fun save(monitor: Monitor, password: String = "secret") = runBlocking {
        graph.mailCredentials.save(monitor.id, password)
        graph.store.upsert(monitor)
    }

    private fun run() = runBlocking { graph.engine.run(ID, force = true) }

    private fun runtime(): MonitorRuntime = runBlocking { graph.store.currentSnapshot().runtimes[ID] } ?: MonitorRuntime()

    private fun mailRows(): List<StatusBarNotification> =
        notifications.activeNotifications.filter { it.tag == graph.alerts.mailTag(ID) }

    private fun StatusBarNotification.title() = notification.extras.getCharSequence("android.title")?.toString()

    private fun StatusBarNotification.text() = notification.extras.getCharSequence("android.text")?.toString()

    @Test
    fun theFirstCheckIsSilentAndTheNextMessageReachesTheShade() {
        imap.deliver("old@example.com", "Backlog one")
        imap.deliver("old@example.com", "Backlog two")
        save(mailbox())

        val first = run()!!
        assertTrue(first.message, first.ok)
        assertEquals(Health.UP, runtime().health)
        assertTrue(runtime().mail.seeded)
        assertEquals(2, runtime().mail.unseen)
        assertTrue("the backlog was announced", mailRows().isEmpty())

        imap.deliver("ann@example.com", "Invoice for October", fromName = "Ann Novak")
        assertTrue(run()!!.ok)
        NightbellTestSupport.awaitTrue(description = "the new message in the shade") { mailRows().size == 1 }
        val row = mailRows().single()
        assertEquals("Ann Novak", row.title())
        assertEquals("Invoice for October", row.text())
        assertTrue(row.notification.channelId, row.notification.channelId.startsWith("nightbell.mail."))
        assertEquals(1, runtime().mail.recent.size)
        assertEquals(3, runtime().mail.unseen)
    }

    @Test
    fun aMatchGoesOnTheAlertChannelAndMarkSeenClearsEverything() {
        save(mailbox(MailWatch(pageSubject = "down")))
        run()
        imap.deliver("noise@example.com", "Newsletter")
        imap.deliver("alerts@monitor.example.com", "web-3 is DOWN")
        run()
        NightbellTestSupport.awaitTrue(description = "both rows") { mailRows().size == 2 }
        val alert = mailRows().single { it.text() == "web-3 is DOWN" }
        assertTrue(alert.notification.channelId, alert.notification.channelId.startsWith("nightbell.mailalert."))
        val news = mailRows().single { it.text() == "Newsletter" }
        assertTrue(news.notification.channelId.startsWith("nightbell.mail."))

        shadeShot("mail-20-shade")

        AlertActionReceiver.pendingIntent(appContext, AlertActionReceiver.ACTION_MARK_SEEN, ID).send()
        NightbellTestSupport.awaitTrue(description = "Mark seen to clear them") { mailRows().isEmpty() }
    }

    @Test
    fun mutedMeansNothingIsSaidButTheFolderMovesOn() {
        save(mailbox())
        run()
        runBlocking { graph.store.updateRuntime(ID) { it.copy(mutedUntil = System.currentTimeMillis() + 3_600_000) } }
        imap.deliver("ann@example.com", "While muted")
        run()
        Thread.sleep(500)
        assertTrue(mailRows().isEmpty())
        assertEquals("no replay once the mute ends", 2L, runtime().mail.uidNext)
    }

    @Test
    fun aRefusedPasswordIsAnOutageOnceAndThenNoMoreConnections() {
        save(mailbox(), password = "wrong")
        val first = run()!!
        assertFalse(first.ok)
        assertEquals(FailureKind.MAIL_LOGIN, first.failureKind)
        assertEquals(Health.DOWN, runtime().health)
        val entry = runBlocking { graph.vault.entry(ID) }
        assertEquals(CredentialVault.fingerprint("wrong"), entry.refusedFingerprint)

        val connections = imap.connections
        val second = run()
        assertEquals("no verdict without trying", null, second)
        assertEquals(connections, imap.connections)
        assertEquals("the outage stands", Health.DOWN, runtime().health)

        // Editing the password is what lets the next check through.
        save(mailbox(), password = "secret")
        assertTrue(run()!!.ok)
        assertEquals(Health.UP, runtime().health)
    }

    @Test
    fun aDeadServerIsDownWithTheMessage() {
        save(mailbox())
        imap.close()
        val result = run()!!
        assertFalse(result.ok)
        assertEquals(FailureKind.CONNECT, result.failureKind)
        assertEquals(Health.DOWN, runtime().health)
        val down = notifications.activeNotifications.firstOrNull {
            it.notification.extras.getCharSequence("android.title")?.contains("is down") == true
        }
        assertNotNull("the down alert", down)
    }

    @Test
    fun testNowFromSetupTriesARefusedPasswordAgain() {
        save(mailbox(), password = "wrong")
        run()
        val connections = imap.connections
        val tested = runBlocking { graph.engine.dryRun(mailbox()) }
        assertEquals(FailureKind.MAIL_LOGIN, tested.failureKind)
        assertEquals(connections + 1, imap.connections)
    }

    /** The shade as the system draws it, which is the only place these rows are seen. */
    private fun shadeShot(name: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.executeShellCommand("cmd statusbar expand-notifications").close()
        Thread.sleep(1_500)
        val bitmap = automation.takeScreenshot()
        java.io.File(NightbellTestSupport.screenshotDir(), "$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        automation.executeShellCommand("cmd statusbar collapse").close()
    }

    private companion object {
        const val ID = "mailbox-test"
    }
}
