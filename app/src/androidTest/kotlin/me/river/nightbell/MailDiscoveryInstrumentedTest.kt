package me.river.nightbell

import android.Manifest
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
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.runBlocking
import me.river.nightbell.NightbellTestSupport.appContext
import me.river.nightbell.NightbellTestSupport.captureScreenshot
import me.river.nightbell.data.Nightbell
import me.river.nightbell.data.check.MailDiscoverer
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.MailDiscovery
import me.river.nightbell.domain.MailSecurity
import me.river.nightbell.domain.ThemeChoice
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "Find the server": an address and a webmail address in, the IMAP server out,
 * through the real setup screen. Once with fakes for DNS and the settings file
 * and a real IMAP conversation on loopback, and once, when the run asks for it,
 * against the real internet with the platform resolver.
 */
@RunWith(AndroidJUnit4::class)
class MailDiscoveryInstrumentedTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private val graph get() = Nightbell.install(appContext)
    private var scenario: ActivityScenario<MainActivity>? = null
    private val original = Nightbell.install(appContext).mailDiscoverer

    @Before
    fun setUp() {
        NightbellTestSupport.resetApp(GlobalSettings(motionIntensity = 0f, theme = ThemeChoice.DARK))
    }

    @After
    fun tearDown() {
        scenario?.close()
        graph.mailDiscoverer = original
        runBlocking { graph.store.currentSnapshot().monitors.forEach { graph.vault.delete(it.id) } }
    }

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

    private fun openTarget() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Add a monitor").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("setup-scroll").performScrollToNode(hasTestTag("setup-bottom"))
        composeRule.onNodeWithText("Mailbox").performClick()
        composeRule.onNodeWithText("Continue").performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun findTheServerFillsTheFormAndTestsIt() {
        TinyImap(implicitTls = TinyTls.selfSigned()).use { imap ->
            imap.users["info@flirtycall.me"] = "secret"
            // The domain's MX points at loopback; the webmail host does not answer.
            graph.mailDiscoverer = MailDiscoverer(
                dns = MailDiscoverer.Dns { _, _ -> null },
                fetch = MailDiscoverer.Fetch { url ->
                    if (!url.startsWith("https://autoconfig.flirtycall.me/")) {
                        null
                    } else {
                        "<?xml version=\"1.0\"?><clientConfig><emailProvider><incomingServer type=\"imap\">" +
                            "<hostname>127.0.0.1</hostname><port>${imap.port}</port><socketType>SSL</socketType>" +
                            "<username>%EMAILADDRESS%</username></incomingServer></emailProvider></clientConfig>"
                    }
                },
            )
            openTarget()
            composeRule.onNodeWithTag("mail-discover").performScrollTo()
            composeRule.captureScreenshot("mail-30-find-before")
            type("Address or user name", "info@flirtycall.me")
            type("Password", "secret")
            type("Webmail address (optional)", "https://rc.zionbit.example")
            // Self-signed on loopback, so the rule has to allow it for the Test that follows.
            composeRule.onNodeWithText("Any certificate").performScrollTo().performClick()
            composeRule.onNodeWithTag("mail-discover").performScrollTo().performClick()
            waitForText("Found 127.0.0.1, TLS ${imap.port}, from the domain's mail settings file.")
            composeRule.onNodeWithText("pick Pinned key below", substring = true).assertExists()
            waitForText("Signed in. 0 unread of 0 in the inbox")
            composeRule.onNodeWithTag("mail-discovery-note").performScrollTo()
            composeRule.captureScreenshot("mail-31-found")
            composeRule.onNodeWithTag("mail-host").assertExists()
            assertTrue(
                composeRule.onAllNodesWithText("127.0.0.1").fetchSemanticsNodes().isNotEmpty(),
            )
            assertTrue(imap.received.any { it.contains("AUTHENTICATE PLAIN") })
        }
    }

    @Test
    fun nothingFoundSaysWhatToDo() {
        graph.mailDiscoverer = MailDiscoverer(
            dns = MailDiscoverer.Dns { _, _ -> null },
            fetch = MailDiscoverer.Fetch { null },
            prober = MailDiscoverer.Prober { c, _ -> MailDiscovery.Probe(c, imap = false) },
        )
        openTarget()
        type("Address or user name", "me@nowhere.example")
        composeRule.onNodeWithTag("mail-discover").performScrollTo().performClick()
        waitForText("Nothing answered IMAP for this address")
        composeRule.onNodeWithTag("mail-discovery-note").performScrollTo()
        composeRule.captureScreenshot("mail-32-not-found")
    }

    @Test
    fun theButtonIsOnlyForOtherAndWaitsForAnAddress() {
        openTarget()
        composeRule.onNodeWithTag("mail-discover").performScrollTo()
        // Disabled with no address: a tap changes nothing.
        composeRule.onNodeWithTag("mail-discover").performClick()
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithTag("mail-discovery-note").fetchSemanticsNodes().isEmpty())
        type("Address or user name", "someone@gmail.com")
        assertTrue("Gmail is already known", composeRule.onAllNodesWithTag("mail-discover").fetchSemanticsNodes().isEmpty())
    }

    /**
     * The check's own system trust path against a real CA-signed server. Only the
     * handshake and the name, never a sign-in, so nothing is sent to anybody's
     * brute force counter. Run with `-e discoverReal 1`.
     */
    @Test
    fun systemTrustAcceptsARealCertificateOnThisPhone() {
        assumeTrue("not asked for", InstrumentationRegistry.getArguments().getString("discoverReal") != null)
        val factory = javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory
        val plain = java.net.Socket().apply { connect(java.net.InetSocketAddress("mx1.zionbit.eu", 993), 10_000) }
        (factory.createSocket(plain, "mx1.zionbit.eu", 993, true) as javax.net.ssl.SSLSocket).use { tls ->
            tls.startHandshake()
            assertTrue(okhttp3.internal.tls.OkHostnameVerifier.verify("mx1.zionbit.eu", tls.session))
            assertTrue(!okhttp3.internal.tls.OkHostnameVerifier.verify("rc.zionbit.eu", tls.session))
        }
    }

    /**
     * The maintainer's own mailbox, over the real internet: Android's resolver
     * for the MX record, real TLS for the certificate. Signs in to nothing.
     * Run with `-e discoverReal 1`.
     */
    @Test
    fun theRealDomainIsFoundThroughItsMxRecord() {
        assumeTrue("not asked for", InstrumentationRegistry.getArguments().getString("discoverReal") != null)
        val found = runBlocking { MailDiscoverer().discover("info@flirtycall.me", "https://rc.zionbit.eu") }
        val c = found.probe!!.candidate
        assertEquals("mx1.zionbit.eu", c.host)
        assertEquals(993, c.port)
        assertEquals(MailSecurity.TLS, c.security)
        assertTrue("the real certificate is trusted", found.probe!!.trusted)

        openTarget()
        type("Name", "Fc mail")
        type("Address or user name", "info@flirtycall.me")
        type("Webmail address (optional)", "https://rc.zionbit.eu")
        composeRule.onNodeWithTag("mail-discover").performScrollTo().performClick()
        waitForText("Found mx1.zionbit.eu, TLS 993")
        composeRule.onNodeWithTag("mail-discovery-note").performScrollTo()
        composeRule.captureScreenshot("mail-33-real-found")
        composeRule.onNodeWithText("System CAs").performScrollTo()
        composeRule.captureScreenshot("mail-34-real-found-cert")
    }
}
