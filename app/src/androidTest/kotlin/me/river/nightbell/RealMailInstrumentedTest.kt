package me.river.nightbell

import android.Manifest
import android.app.NotificationManager
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.runBlocking
import me.river.nightbell.NightbellTestSupport.appContext
import me.river.nightbell.NightbellTestSupport.awaitTrue
import me.river.nightbell.NightbellTestSupport.captureScreenshot
import me.river.nightbell.data.Nightbell
import me.river.nightbell.domain.AlertPolicy
import me.river.nightbell.domain.FailureKind
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.Health
import me.river.nightbell.domain.MailSecurity
import me.river.nightbell.domain.MailWatch
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.MonitorRuntime
import me.river.nightbell.domain.ThemeChoice
import me.river.nightbell.domain.TlsTrust
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The mailbox kind against a real mail server, driven through the real UI.
 *
 * Skipped unless the run names a server, because the suite must not depend on
 * one. The host runs it in docker and the emulator reaches it on 10.0.2.2:
 *
 * ```
 * adb shell am instrument -w -e class me.river.nightbell.RealMailInstrumentedTest \
 *   -e mailPort 10993 -e mailUser me@example.com -e mailPassword pass -e mailStartTlsPort 10143 \
 *   me.river.nightbell.debug.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 *
 * New mail is put in with APPEND over its own connection, which every IMAP
 * server accepts, so the same test drives Dovecot and GreenMail alike.
 */
@RunWith(AndroidJUnit4::class)
class RealMailInstrumentedTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private val args = InstrumentationRegistry.getArguments()
    private val host = args.getString("mailHost") ?: "10.0.2.2"
    private val port = args.getString("mailPort")?.toIntOrNull() ?: 0
    private val user = args.getString("mailUser") ?: "me@example.com"
    private val password = args.getString("mailPassword") ?: ""
    private val startTlsPort = args.getString("mailStartTlsPort")?.toIntOrNull() ?: 0
    private val label = args.getString("mailLabel") ?: "real"

    private val graph get() = Nightbell.install(appContext)
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        assumeTrue("no mail server named for this run", port > 0)
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
    }

    @After
    fun tearDown() {
        scenario?.close()
        runBlocking { graph.store.currentSnapshot().monitors.forEach { graph.vault.delete(it.id) } }
        appContext.getSystemService(NotificationManager::class.java).cancelAll()
    }

    private fun monitors(): List<Monitor> = runBlocking { graph.store.currentSnapshot().monitors }

    private fun runtime(id: String): MonitorRuntime =
        runBlocking { graph.store.currentSnapshot().runtimes[id] } ?: MonitorRuntime()

    private fun type(field: String, text: String) {
        composeRule.onNodeWithContentDescription(field).performScrollTo().performTextInput(text)
        Espresso.closeSoftKeyboard()
        composeRule.waitForIdle()
    }

    private fun waitForText(text: String, timeoutMs: Long = 30_000) {
        composeRule.waitUntil(timeoutMs) {
            composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun aRealServerIsWatchedFromTheWizardToTheShade() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Add a monitor").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("setup-scroll").performScrollToNode(hasTestTag("setup-bottom"))
        composeRule.onNodeWithText("Mailbox").performClick()
        composeRule.onNodeWithText("Continue").performClick()
        composeRule.waitForIdle()

        type("Name", "Real $label")
        type("Address or user name", user)
        type("Password", password)
        type("IMAP server", host)
        type("Port", port.toString())
        type("Webmail address (optional)", "https://webmail.example.com")
        composeRule.onNodeWithText("Any certificate").performScrollTo().performClick()
        composeRule.onNodeWithText("Continue").performClick()
        composeRule.waitForIdle()
        type("Alert when the subject has", "down")
        composeRule.onNodeWithText("Continue").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("setup-scroll").performScrollToNode(hasTestTag("setup-bottom"))
        composeRule.onNodeWithText("Test now").performClick()
        waitForText("Signed in.")
        composeRule.onNodeWithTag("setup-scroll").performScrollToNode(hasTestTag("setup-bottom"))
        composeRule.captureScreenshot("real-mail-$label-01-test-now")
        composeRule.onNodeWithText("Create monitor").performClick()
        composeRule.waitForIdle()

        val saved = monitors().single()
        awaitTrue(timeoutMs = 30_000, description = "the first check to seed") { runtime(saved.id).mail.seeded }
        assertEquals(Health.UP, runtime(saved.id).health)
        val unreadBefore = runtime(saved.id).mail.unseen

        val stamp = System.currentTimeMillis()
        append("Monitor Bot <alerts@monitor.example.com>", "=?UTF-8?Q?web-3_is_DOWN_=C4=8Dek=C3=A1_$stamp?=")
        append("Ann Novák <ann@example.com>", "Lunch $stamp", seen = true)
        append("Bob <bob@example.com>", "Report $stamp")

        composeRule.onNodeWithText("Real $label").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Check now").performClick()
        composeRule.onNodeWithTag("detail-list").performScrollToNode(hasTestTag("mailbox-card"))
        waitForText("web-3 is DOWN čeká $stamp")
        composeRule.onNodeWithText("Report $stamp").assertExists()
        composeRule.captureScreenshot("real-mail-$label-02-detail")
        assertEquals(unreadBefore + 2, runtime(saved.id).mail.unseen)

        val rows = appContext.getSystemService(NotificationManager::class.java).activeNotifications
            .filter { it.tag == graph.alerts.mailTag(saved.id) }
        val texts = rows.map { it.notification.extras.getCharSequence("android.text")?.toString() }
        assertTrue(texts.toString(), "web-3 is DOWN čeká $stamp" in texts)
        assertTrue(texts.toString(), "Report $stamp" in texts)
        assertTrue("a message already read was announced: $texts", "Lunch $stamp" !in texts)
        val alert = rows.single { it.notification.extras.getCharSequence("android.text")?.contains("DOWN") == true }
        assertTrue(alert.notification.channelId.startsWith("nightbell.mailalert."))
        assertEquals("Monitor Bot", alert.notification.extras.getCharSequence("android.title")?.toString())

        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.executeShellCommand("cmd statusbar expand-notifications").close()
        Thread.sleep(1_500)
        java.io.File(NightbellTestSupport.screenshotDir(), "real-mail-$label-03-shade.png").outputStream().use {
            automation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        automation.executeShellCommand("cmd statusbar collapse").close()
    }

    @Test
    fun theRealServerListsItsFolders() {
        val outcome = runBlocking {
            graph.mailCredentials.save("real-folders", password)
            graph.engine.mailFolders(monitor("real-folders", port, MailSecurity.TLS))
        }
        val folders = outcome?.folders
        assertTrue("${outcome?.result?.message}: $folders", folders != null && folders.first() == "INBOX")
    }

    @Test
    fun aWrongPasswordIsRefusedByTheRealServer() {
        val result = runBlocking {
            graph.mailCredentials.save("real-wrong", password + "-not")
            graph.engine.dryRun(monitor("real-wrong", port, MailSecurity.TLS))
        }
        assertEquals(result.message, FailureKind.MAIL_LOGIN, result.failureKind)
    }

    @Test
    fun startTlsOnTheRealServer() {
        assumeTrue("no STARTTLS port for this server", startTlsPort > 0)
        val result = runBlocking {
            graph.mailCredentials.save("real-starttls", password)
            graph.engine.dryRun(monitor("real-starttls", startTlsPort, MailSecurity.STARTTLS))
        }
        assertTrue(result.message, result.ok)
    }

    @Test
    fun theSystemTrustStoreRefusesASelfSignedServer() {
        val result = runBlocking {
            graph.mailCredentials.save("real-system", password)
            graph.engine.dryRun(monitor("real-system", port, MailSecurity.TLS).copy(tlsTrust = TlsTrust.SYSTEM))
        }
        assertEquals(result.message, FailureKind.TLS, result.failureKind)
    }

    private fun monitor(id: String, port: Int, security: MailSecurity) = Monitor(
        id = id,
        kind = MonitorKind.MAILBOX,
        tlsTrust = TlsTrust.ANY,
        timeoutSeconds = 20,
        mail = MailWatch(host = host, port = port, security = security, username = user),
    )

    /** APPEND over TLS, trusting anything: this is the test's own delivery, not the checker. */
    private fun append(from: String, subject: String, seen: Boolean = false) {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), SecureRandom()) }
        // Named outside the apply: inside it `port` is the socket's own, still 0.
        val target = InetSocketAddress(host, port)
        val plain = Socket().apply { connect(target, 10_000); soTimeout = 10_000 }
        (context.socketFactory.createSocket(plain, host, target.port, true) as SSLSocket).use { socket ->
            val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
            val out = socket.outputStream
            fun send(line: String) {
                out.write((line + "\r\n").toByteArray(Charsets.UTF_8))
                out.flush()
            }
            fun until(tag: String): String {
                while (true) {
                    val line = reader.readLine() ?: error("closed")
                    if (line.startsWith("$tag ")) return line
                }
            }
            reader.readLine()
            send("a1 LOGIN \"$user\" \"$password\"")
            check(until("a1").startsWith("a1 OK")) { "test delivery could not sign in" }
            val body = "From: $from\r\nTo: $user\r\nSubject: $subject\r\nMIME-Version: 1.0\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n\r\nSent by RealMailInstrumentedTest.\r\n"
            val bytes = body.toByteArray(Charsets.UTF_8)
            send("a2 APPEND INBOX ${if (seen) "(\\Seen) " else ""}{${bytes.size}+}")
            out.write(bytes)
            send("")
            check(until("a2").startsWith("a2 OK")) { "APPEND refused" }
            send("a3 LOGOUT")
        }
    }
}
