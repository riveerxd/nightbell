package me.river.nightbell

import android.Manifest
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import me.river.nightbell.NightbellTestSupport.captureScreenshot
import me.river.nightbell.data.Nightbell
import me.river.nightbell.data.NightbellSnapshot
import me.river.nightbell.domain.FailureKind
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.Health
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.MonitorRuntime
import me.river.nightbell.domain.Sample
import me.river.nightbell.domain.ThemeChoice
import me.river.nightbell.domain.TlsFailure
import me.river.nightbell.domain.TlsTrust
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The certificate settings as a person actually meets them.
 *
 * Two things worth asserting on screen rather than in the engine. The selector has
 * to be reachable from the setup flow, because a mode nobody can find fixes
 * nothing; and a monitor left on "accept any certificate" has to keep saying so on
 * its detail screen, because the risk of offering that mode at all is somebody
 * switching it on to get past a problem and never thinking about it again.
 *
 * Also writes the PNGs for reviewing the wording outside the emulator.
 */
@RunWith(AndroidJUnit4::class)
class CertificateTrustUiTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun tearDown() {
        scenario?.close()
    }

    private val now = System.currentTimeMillis()

    private fun settings() = GlobalSettings(
        motionIntensity = 0f,
        theme = ThemeChoice.DARK,
        hasSeenPagerSetup = true,
    )

    /** A monitor that is failing exactly the way issue #6 failed. */
    private fun seedFailingHiddenService() {
        val monitor = Monitor(
            id = "onion",
            name = "Pitch",
            kind = MonitorKind.HTTP_STATUS,
            url = "http://pitchprash4aqilfr7sbmuwve3pnkpylqwxjbj2q5o4szcfeea6d27yd.onion",
            useProxy = true,
            timeoutSeconds = 60,
            createdAt = now,
        )
        val message = TlsFailure.headline(TlsFailure.Cause.UntrustedChain, hiddenService = true)
        val detail = TlsFailure.explanation(
            cause = TlsFailure.Cause.UntrustedChain,
            hiddenService = true,
            schemeUpgradedTo = "https://pitchprash4aqilfr7sbmuwve3pnkpylqwxjbj2q5o4szcfeea6d27yd.onion",
        )
        seed(
            monitor,
            MonitorRuntime(
                health = Health.DOWN,
                lastCheckedAt = now - 90_000,
                lastLatencyMs = 7_540,
                lastMessage = message,
                lastDetail = detail,
                consecutiveFailures = 3,
                samples = List(6) {
                    Sample(at = now - (6 - it) * 900_000L, ok = false, latencyMs = 7_540, note = message)
                },
            ),
        )
    }

    private fun seed(monitor: Monitor, runtime: MonitorRuntime) {
        runBlocking {
            Nightbell.install(NightbellTestSupport.appContext).store.replaceAll(
                NightbellSnapshot(
                    monitors = listOf(monitor),
                    runtimes = mapOf(monitor.id to runtime),
                    settings = settings(),
                ),
            )
        }
    }

    @Test
    fun theFailureExplainsItselfOnTheDetailScreen() {
        seedFailingHiddenService()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Pitch").performClick()
        composeRule.waitForIdle()

        // The headline the reporter would now see instead of "TLS/certificate error".
        composeRule.onNodeWithText("No CA vouches for this certificate").assertExists()
        composeRule.captureScreenshot("60-onion-tls-failure")

        composeRule.onNodeWithTag("detail-list")
            .performScrollToNode(hasText("No certificate authority issues", substring = true))
        composeRule.waitForIdle()
        composeRule.captureScreenshot("61-onion-tls-explanation")
    }

    @Test
    fun theSelectorIsReachableFromSetup() {
        runBlocking {
            Nightbell.install(NightbellTestSupport.appContext).store.replaceAll(
                NightbellSnapshot(settings = settings()),
            )
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Add a monitor").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Continue").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Name").performTextInput("NAS")
        composeRule.onNodeWithContentDescription("URL").performTextInput("https://192.168.1.20")
        // Put the keyboard away first. It halves the scroll viewport, and with the
        // sticky footer taking the bottom of what is left there is not enough room
        // to bring the chips clear of it, so a tap lands on the footer instead.
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        composeRule.waitForIdle()

        // Scrolled to the last chip rather than to the section header, and the
        // difference matters. Stopping at the header leaves the chips themselves
        // under the sticky Back/Continue footer, where a synthetic tap lands on the
        // footer instead of the chip. A finger has the same problem, which is why
        // the step ends with a footer-height spacer; the test has to scroll like a
        // person would rather than to the first thing it can name.
        composeRule.onNodeWithTag("setup-scroll")
            .performScrollToNode(hasText("Any certificate"))
        composeRule.waitForIdle()
        composeRule.onNodeWithText("CERTIFICATE").assertExists()
        composeRule.onNodeWithText("System CAs").assertExists()
        composeRule.captureScreenshot("62-setup-certificate-default")

        composeRule.onNodeWithText("Pinned key").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Records the key on the next successful check", substring = true)
            .assertExists()
        composeRule.captureScreenshot("63-setup-certificate-pinned")

        composeRule.onNodeWithText("Any certificate").performClick()
        composeRule.waitForIdle()
        // The warning is not optional here. This mode is the one that can quietly
        // cost someone their traffic, so it says so in the palette that means
        // trouble everywhere else in the app.
        composeRule.onNodeWithText("No checks at all", substring = true).assertExists()
        composeRule.captureScreenshot("64-setup-certificate-any")
    }

    @Test
    fun aPinnedMonitorShowsItsKeyAndOffersAWayOut() {
        seed(
            Monitor(
                id = "nas",
                name = "NAS",
                kind = MonitorKind.HTTP_STATUS,
                url = "https://192.168.1.20",
                tlsTrust = TlsTrust.PINNED,
                createdAt = now,
            ),
            MonitorRuntime(
                health = Health.UP,
                lastCheckedAt = now - 60_000,
                lastLatencyMs = 84,
                lastCode = 200,
                certExpiresAt = now + 400L * 86_400_000L,
                certIssuer = "river-homelab-ca",
                certPin = "sha256/47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=",
                samples = List(8) {
                    Sample(at = now - (8 - it) * 900_000L, ok = true, latencyMs = 80L + it, code = 200)
                },
            ),
        )
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("NAS").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("detail-list")
            .performScrollToNode(hasContentDescription("TLS certificate"))
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Pinned to sha256/", substring = true).assertExists()
        composeRule.onNodeWithText("Trust the new key").assertExists()
        composeRule.captureScreenshot("65-detail-pinned-key")
    }

    @Test
    fun anAcceptAnythingMonitorKeepsSayingSo() {
        seed(
            Monitor(
                id = "any",
                name = "Old printer",
                kind = MonitorKind.HTTP_STATUS,
                url = "https://192.168.1.31",
                tlsTrust = TlsTrust.ANY,
                createdAt = now,
            ),
            MonitorRuntime(
                health = Health.UP,
                lastCheckedAt = now - 60_000,
                lastLatencyMs = 210,
                lastCode = 200,
                certExpiresAt = now + 30L * 86_400_000L,
                certIssuer = "printer.local",
                samples = List(8) {
                    Sample(at = now - (8 - it) * 900_000L, ok = true, latencyMs = 200L + it, code = 200)
                },
            ),
        )
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Old printer").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("detail-list")
            .performScrollToNode(hasContentDescription("TLS certificate"))
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Anything on the network path", substring = true).assertExists()
        // No re-pin button here. There is no pin, and offering one would suggest
        // this monitor is checking something it is not.
        composeRule.onNodeWithText("Trust the new key").assertDoesNotExist()
        composeRule.captureScreenshot("66-detail-accept-any")

        // Still there in the configuration list, so a monitor whose handshake never
        // succeeds and therefore has no certificate card still says what it is set to.
        composeRule.onNodeWithTag("detail-list")
            .performScrollToNode(hasContentDescription("Configuration"))
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Any certificate").assertExists()
        composeRule.captureScreenshot("67-detail-config-certificate-row")
    }
}
