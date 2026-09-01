package me.river.nightbell

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.river.nightbell.NightbellTestSupport.appContext
import me.river.nightbell.NightbellTestSupport.awaitTrue
import me.river.nightbell.NightbellTestSupport.captureScreenshot
import me.river.nightbell.data.Nightbell
import me.river.nightbell.domain.ElementTarget
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.ui.components.AuroraBackground
import me.river.nightbell.ui.setup.SetupScreen
import me.river.nightbell.ui.theme.NightbellTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The certificate expiry option, which only one kind of monitor needs.
 *
 * A page-element check runs in a WebView, and WebView hands an app the certificate
 * it negotiated only inside `onReceivedSslError`. So that kind of monitor can be
 * told a certificate is broken and can never see a good one, which meant it could
 * never warn anybody that a working certificate was about to expire, silently,
 * while the setup screen still asked how much that certificate had to prove. The
 * switch buys one extra HEAD request a day to close that.
 *
 * An HTTP status check reads the leaf on every single pass, so the switch must not
 * appear there: it would be a control for something already happening, which is
 * the class of button this pass through the app was about removing.
 */
@RunWith(AndroidJUnit4::class)
class CertificateOptionInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val store get() = Nightbell.install(appContext).store

    private val label = "Watch certificate expiry"

    @Before
    fun setUp() {
        NightbellTestSupport.resetApp()
    }

    private fun seed(id: String, kind: MonitorKind, url: String) = runBlocking {
        store.upsert(
            Monitor(
                id = id,
                name = "Status page",
                kind = kind,
                url = url,
                intervalMinutes = 15,
                elements = if (kind == MonitorKind.WEBSITE_ELEMENT) {
                    listOf(ElementTarget(elementId = "status"))
                } else {
                    emptyList()
                },
            ),
        )
    }

    /** Opens the wizard on an existing monitor and steps past the kind page. */
    private fun editorFor(id: String) {
        composeRule.setContent {
            NightbellTheme(motionIntensity = 0f) {
                AuroraBackground(modifier = Modifier.fillMaxSize()) {
                    SetupScreen(monitorId = id, onClose = {}, onSaved = {})
                }
            }
        }
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithText("Continue").fetchSemanticsNodes().isNotEmpty()
        }
        // The certificate section is drawn on every step past the first, which is
        // where "Test now" lives and therefore where trust decisions belong.
        composeRule.onNodeWithText("Continue").performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun aPageElementMonitorOnHttpsIsOfferedTheExpiryWatch() {
        seed("e1", MonitorKind.WEBSITE_ELEMENT, "https://status.example.com/")
        editorFor("e1")

        // Scrolled past rather than to: `performScrollTo` brings a node just
        // inside the viewport, which for this one means underneath the sticky
        // footer, and the tap then lands on "Continue" instead. Pulling the panel
        // below it into view puts the switch somewhere a thumb could actually
        // reach, which is also the only version of this worth asserting.
        composeRule.onNodeWithText("Test now").performScrollTo()
        composeRule.waitForIdle()
        composeRule.captureScreenshot("cert-option-01-element-https")
        composeRule.onNodeWithText(label).performClick()
        composeRule.waitForIdle()

        // Driven all the way to the save, because the wizard writes a draft and
        // nothing else: asserting the store straight after the tap would be
        // asserting that this screen skips its own save step.
        repeat(4) {
            val more = composeRule.onAllNodesWithText("Continue").fetchSemanticsNodes().isNotEmpty()
            if (!more) return@repeat
            composeRule.onNodeWithText("Continue").performClick()
            composeRule.waitForIdle()
        }
        composeRule.onNodeWithText("Save changes").performClick()

        awaitTrue(description = "the choice to be stored") {
            runBlocking { store.currentSnapshot() }.monitors.single().watchCertificate
        }
    }

    @Test
    fun aStatusCheckIsNotOfferedItBecauseItAlreadyReadsTheCertificate() {
        seed("h1", MonitorKind.HTTP_STATUS, "https://status.example.com/health")
        editorFor("h1")

        // The trust chips are here, so the certificate section itself rendered and
        // this is a real absence rather than a screen that never got that far.
        composeRule.onNodeWithText("CERTIFICATE").performScrollTo()
        assertTrue(
            "the trust section should still be offered",
            composeRule.onAllNodesWithText("CERTIFICATE").fetchSemanticsNodes().isNotEmpty(),
        )
        assertEquals(
            "a status check already reads the leaf on every pass",
            0,
            composeRule.onAllNodesWithText(label).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun aPlainHttpPageElementMonitorIsNotOfferedItEither() {
        // No certificate to have an opinion about, and "Follow redirects" is on by
        // default, so the whole section is hidden the way it always was for http.
        seed("e2", MonitorKind.WEBSITE_ELEMENT, "http://status.example.com/")
        runBlocking {
            store.upsert(store.currentSnapshot().monitors.single().copy(followRedirects = false))
        }
        editorFor("e2")

        assertEquals(
            "plain http has no certificate to watch",
            0,
            composeRule.onAllNodesWithText(label).fetchSemanticsNodes().size,
        )
    }
}
