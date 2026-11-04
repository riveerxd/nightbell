package me.river.nightbell

import android.Manifest
import android.app.NotificationManager
import android.content.pm.ActivityInfo
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import java.io.File
import kotlinx.coroutines.runBlocking
import me.river.nightbell.NightbellTestSupport.appContext
import me.river.nightbell.NightbellTestSupport.awaitTrue
import me.river.nightbell.NightbellTestSupport.captureScreenshot
import me.river.nightbell.data.Nightbell
import me.river.nightbell.domain.AlertPolicy
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.Health
import me.river.nightbell.domain.MailPreset
import me.river.nightbell.domain.MailWatch
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.MonitorRuntime
import me.river.nightbell.domain.ThemeChoice
import me.river.nightbell.domain.TlsTrust
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The mailbox kind the way a person sets one up: the wizard, Test now, the
 * dashboard card, the detail screen, and the states nobody asks for: a refused
 * password, nothing arrived yet, many arrivals, and a turned phone.
 */
@RunWith(AndroidJUnit4::class)
class MailboxSetupInstrumentedTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private val graph get() = Nightbell.install(appContext)
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var imap: TinyImap

    @Before
    fun setUp() {
        appContext.getSystemService(NotificationManager::class.java).cancelAll()
        NightbellTestSupport.resetApp(
            GlobalSettings(
                motionIntensity = 0f,
                theme = ThemeChoice.DARK,
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
        scenario?.close()
        imap.close()
        runBlocking { monitors().forEach { graph.vault.delete(it.id) } }
        appContext.getSystemService(NotificationManager::class.java).cancelAll()
    }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()
    }

    private fun monitors(): List<Monitor> = runBlocking { graph.store.currentSnapshot().monitors }

    private fun runtime(id: String): MonitorRuntime =
        runBlocking { graph.store.currentSnapshot().runtimes[id] } ?: MonitorRuntime()

    private fun type(field: String, text: String) {
        composeRule.onNodeWithContentDescription(field).performScrollTo().performTextInput(text)
        Espresso.closeSoftKeyboard()
        composeRule.waitForIdle()
    }

    private fun continueStep() {
        composeRule.onNodeWithText("Continue").performClick()
        composeRule.waitForIdle()
    }

    private fun waitForText(text: String, timeoutMs: Long = 20_000) {
        composeRule.waitUntil(timeoutMs) {
            composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun openWizard() {
        composeRule.onNodeWithContentDescription("Add a monitor").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("setup-scroll").performScrollToNode(hasTestTag("setup-bottom"))
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Mailbox").performClick()
        composeRule.waitForIdle()
    }

    private fun seeded(watch: MailWatch = MailWatch(), password: String = "secret"): Monitor {
        val monitor = Monitor(
            id = "mbx",
            name = "Work inbox",
            kind = MonitorKind.MAILBOX,
            tlsTrust = TlsTrust.ANY,
            timeoutSeconds = 10,
            mail = watch.copy(host = "127.0.0.1", port = imap.port, username = "me@example.com"),
        )
        runBlocking {
            graph.mailCredentials.save(monitor.id, password)
            graph.store.upsert(monitor)
        }
        return monitor
    }

    @Test
    fun aMailboxIsMadeThroughTheWizardTestedAndWatched() {
        imap.deliver("old@example.com", "Already here")
        launch()
        openWizard()
        composeRule.captureScreenshot("mail-01-kind-picker")
        continueStep()

        // Routing is not offered: IMAP goes straight out.
        assertTrue(composeRule.onAllNodesWithText("Route through SOCKS5", substring = true).fetchSemanticsNodes().isEmpty())
        type("Name", "Work inbox")
        type("Address or user name", "me@example.com")
        type("Password", "secret")
        type("IMAP server", "127.0.0.1")
        type("Port", imap.port.toString())
        composeRule.onNodeWithText("Any certificate").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("mail-user").performScrollTo()
        composeRule.captureScreenshot("mail-02-target")
        composeRule.onNodeWithText("Any certificate").performScrollTo()
        composeRule.captureScreenshot("mail-03-target-bottom")
        continueStep()

        type("Alert when the subject has", "down, firing")
        composeRule.onNodeWithTag("mail-explainer").performScrollTo()
        composeRule.captureScreenshot("mail-04-expectations")
        continueStep()

        composeRule.onAllNodesWithText("Follow redirects").fetchSemanticsNodes().let {
            assertTrue("follow redirects on a mailbox", it.isEmpty())
        }
        composeRule.onNodeWithTag("setup-scroll").performScrollToNode(hasTestTag("setup-bottom"))
        composeRule.onNodeWithText("Test now").performClick()
        waitForText("Signed in. 1 unread of 1 in the inbox")
        composeRule.onNodeWithTag("setup-scroll").performScrollToNode(hasTestTag("setup-bottom"))
        composeRule.captureScreenshot("mail-05-test-now")

        composeRule.onNodeWithText("Create monitor").performClick()
        composeRule.waitForIdle()
        val saved = monitors().single()
        assertEquals(MonitorKind.MAILBOX, saved.kind)
        assertEquals("127.0.0.1", saved.mail.host)
        assertEquals("down, firing", saved.mail.pageSubject)
        assertEquals("", saved.url)
        assertEquals(TlsTrust.ANY, saved.tlsTrust)
        // The password lives in the vault and nowhere in the store.
        val stored = File(appContext.filesDir.parentFile, "files/datastore").walk()
            .filter { it.isFile }.map { it.readBytes().toString(Charsets.ISO_8859_1) }.joinToString()
        assertFalse("the password reached the store", stored.contains("secret"))

        awaitTrue(description = "the first check") { runtime(saved.id).mail.seeded }
        assertEquals(Health.UP, runtime(saved.id).health)
        waitForText("1 unread")
        composeRule.captureScreenshot("mail-06-dashboard")

        imap.deliver("alerts@monitor.example.com", "web-3 is DOWN", fromName = "Monitor")
        imap.deliver("ann@example.com", "Lunch on Friday?", fromName = "Ann Novak")
        runBlocking { graph.engine.run(saved.id, force = true) }
        waitForText("latest from Ann Novak")
        composeRule.captureScreenshot("mail-07-dashboard-arrived")

        composeRule.onNodeWithText("Work inbox").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("detail-list").performScrollToNode(hasTestTag("mailbox-card"))
        waitForText("web-3 is DOWN")
        composeRule.onNodeWithText("Lunch on Friday?").assertExists()
        composeRule.onNodeWithText("Alert").assertExists()
        composeRule.captureScreenshot("mail-08-detail")

        scenario!!.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        composeRule.waitForIdle()
        Thread.sleep(800)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("detail-list").performScrollToNode(hasTestTag("mailbox-card"))
        composeRule.onNodeWithText("Lunch on Friday?").assertExists()
        composeRule.captureScreenshot("mail-09-detail-landscape")
        scenario!!.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    @Test
    fun aFolderIsPickedFromWhatTheServerHas() {
        imap.folders["Alerts"] = TinyImap.Folder(9)
        imap.folders["Archive"] = TinyImap.Folder(10)
        imap.noselect += "Projects"
        imap.deliver("pager@example.com", "In alerts", folder = "Alerts")
        launch()
        openWizard()
        continueStep()
        composeRule.onNodeWithTag("mail-folders-load").performScrollTo()
        composeRule.onNodeWithText("Fill in the address, password and server first", substring = true).assertExists()
        type("Address or user name", "me@example.com")
        type("Password", "secret")
        type("IMAP server", "127.0.0.1")
        type("Port", imap.port.toString())

        // System CAs against a self-signed server: the list fails and says why.
        composeRule.onNodeWithTag("mail-folders-load").performScrollTo().performClick()
        composeRule.waitUntil(20_000) {
            composeRule.onAllNodesWithTag("mail-folders-note").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("mail-folders-note").performScrollTo()
        composeRule.captureScreenshot("mail-17-folders-tls-refused")

        composeRule.onNodeWithText("Any certificate").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("mail-folders-load").performScrollTo().performClick()
        waitForText("Archive")
        composeRule.onAllNodesWithText("Projects").fetchSemanticsNodes().let {
            assertTrue("a folder that cannot be opened was offered", it.isEmpty())
        }
        composeRule.onNodeWithText("Alerts").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("mail-folder-chips").performScrollTo()
        composeRule.captureScreenshot("mail-18-folders-picked")

        // Another account's folders are not this one's.
        type("IMAP server", "x")
        assertTrue(composeRule.onAllNodesWithTag("mail-folder-chips").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithContentDescription("IMAP server").performTextClearance()
        type("IMAP server", "127.0.0.1")

        continueStep()
        continueStep()
        composeRule.onNodeWithTag("setup-scroll").performScrollToNode(hasTestTag("setup-bottom"))
        composeRule.onNodeWithText("Test now").performClick()
        waitForText("Signed in. 1 unread of 1 in Alerts")
        composeRule.onNodeWithText("Create monitor").performClick()
        composeRule.waitForIdle()
        assertEquals("Alerts", monitors().single().mail.folder)
    }

    @Test
    fun aKnownProviderFillsTheServerAndSaysWhichPasswordItWants() {
        launch()
        openWizard()
        continueStep()
        // Nothing typed yet, so nothing in red: only the Test panel says what is missing.
        composeRule.onNodeWithTag("setup-scroll").performScrollToNode(hasTestTag("setup-bottom"))
        // The server's note is the first error, so the Test panel carries it once.
        assertEquals(1, composeRule.onAllNodesWithText("The mail server is required").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("The user name is required", substring = true).fetchSemanticsNodes().size)
        composeRule.captureScreenshot("mail-16-fresh-target-bottom")
        type("Address or user name", "someone@gmail.com")
        composeRule.onNodeWithText("imap.gmail.com").assertExists()
        composeRule.onNodeWithContentDescription("App password").assertExists()
        composeRule.onNodeWithText(MailPreset.GMAIL.passwordHint).assertExists()
        // Once, in the Test panel saying why Test is off, and not in red under
        // a field nobody has touched yet.
        assertEquals(
            "the password shouted at before it was touched",
            1,
            composeRule.onAllNodesWithText("The password is required").fetchSemanticsNodes().size,
        )
        composeRule.onNodeWithTag("mail-user").performScrollTo()
        composeRule.captureScreenshot("mail-10-gmail-preset")

        composeRule.onNodeWithContentDescription("Address or user name").performTextClearance()
        type("Address or user name", "someone@outlook.com")
        waitForText("Outlook and Hotmail only accept a Microsoft sign-in")
        composeRule.onNodeWithTag("mail-user").performScrollTo()
        composeRule.captureScreenshot("mail-11-outlook-refused")
    }

    @Test
    fun aRefusedPasswordReadsAsDownWithWhatToDo() {
        val monitor = seeded(password = "wrong")
        runBlocking { graph.engine.run(monitor.id, force = true) }
        launch()
        waitForText("The server refused the sign-in")
        composeRule.captureScreenshot("mail-12-refused-dashboard")
        composeRule.onNodeWithText("Work inbox").performClick()
        composeRule.waitForIdle()
        composeRule.captureScreenshot("mail-13-refused-detail")
        waitForText("Not tried again until the password is edited")
    }

    @Test
    fun beforeTheFirstCheckTheCardSaysWhatWillHappen() {
        seeded()
        launch()
        composeRule.onNodeWithText("Work inbox").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("detail-list").performScrollToNode(hasTestTag("mailbox-card"))
        composeRule.onNodeWithText("Not read yet", substring = true).assertExists()
        composeRule.captureScreenshot("mail-14-not-read-yet")
    }

    @Test
    fun aFloodIsCountedAndTheCardStaysReadable() {
        val monitor = seeded()
        runBlocking { graph.engine.run(monitor.id, force = true) }
        repeat(30) { imap.deliver("bulk$it@lists.example.com", "Digest number $it with a long subject line that wraps") }
        runBlocking { graph.engine.run(monitor.id, force = true) }
        val rows = appContext.getSystemService(NotificationManager::class.java).activeNotifications
            .filter { it.tag == graph.alerts.mailTag(monitor.id) }
        assertEquals(1, rows.size)
        assertEquals("30 new messages", rows.single().notification.extras.getCharSequence("android.title")?.toString())
        launch()
        composeRule.onNodeWithText("Work inbox").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("detail-list").performScrollToNode(hasTestTag("mailbox-card"))
        waitForText("Digest number 29")
        composeRule.captureScreenshot("mail-15-flood-detail")
    }
}
