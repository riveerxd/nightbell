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
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import me.river.nightbell.NightbellTestSupport.captureScreenshot
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.ThemeChoice
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Test now on a silent UDP monitor, at the default three tries of fifteen
 * seconds, against a port on loopback that is held and never answers: what
 * WireGuard looks like from outside.
 *
 * This was most of a minute behind "Running check..." and nothing else. Now the
 * screen says how long it can take before the tap, shows which try is running
 * and how long is left while it waits, and gets out of the way for the result.
 */
@RunWith(AndroidJUnit4::class)
class UdpTestProgressInstrumentedTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private var scenario: ActivityScenario<MainActivity>? = null
    private val quiet = TinyUdp.quiet()

    @Before
    fun setUp() {
        NightbellTestSupport.resetApp(GlobalSettings(motionIntensity = 0f, theme = ThemeChoice.DARK))
    }

    @After
    fun tearDown() {
        scenario?.close()
        quiet.close()
    }

    private fun type(field: String, text: String, clear: Boolean = false) {
        val node = composeRule.onNodeWithContentDescription(field).performScrollTo()
        if (clear) node.performTextClearance()
        node.performTextInput(text)
        Espresso.closeSoftKeyboard()
        composeRule.waitForIdle()
    }

    private fun waitForText(text: String, timeoutMs: Long) {
        composeRule.waitUntil(timeoutMs) {
            composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * The panel ticks on a delay, and in a Compose test that delay runs on the
     * test's clock, which only moves when the test moves it. Without this a
     * capture shows the panel as of the last wait: seven seconds into try two
     * it read "45 s left" over an empty bar. On a phone the delay is real time.
     */
    private fun tickThenCapture(name: String) {
        composeRule.mainClock.advanceTimeBy(400)
        composeRule.waitForIdle()
        composeRule.captureScreenshot(name)
    }

    private fun toBottom() {
        composeRule.onNodeWithTag("setup-scroll").performScrollToNode(hasTestTag("setup-bottom"))
        composeRule.waitForIdle()
    }

    @Test
    fun silentModeSaysHowLongItTakesAndShowsEachTryWhileItWaits() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Add a monitor").performClick()
        composeRule.waitForIdle()
        toBottom()
        composeRule.onNodeWithText("UDP port").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Continue").performClick()
        composeRule.waitForIdle()

        type("Name", "VPN")
        type("Host", "127.0.0.1")
        composeRule.onNodeWithText("51820 WireGuard").performScrollTo().performClick()
        composeRule.waitForIdle()
        type("Port", quiet.port.toString(), clear = true)
        composeRule.onNodeWithText("Continue").performClick()
        composeRule.waitForIdle()

        // Before the tap: the whole wait, said in the terms the form uses.
        toBottom()
        composeRule.onNodeWithText("Takes up to 60 s: 3 tries of 15 s each, then a ping.").assertExists()
        composeRule.captureScreenshot("udp-progress-01-before")

        composeRule.onNodeWithText("Test now").performClick()
        waitForText("TRY 1 OF 3", 5_000)
        toBottom()
        composeRule.onNodeWithText("Listening for a refusal").assertExists()
        composeRule.onNodeWithTag("udp-test-duration").assertDoesNotExist()
        Thread.sleep(6_000)
        tickThenCapture("udp-progress-02-try-one")

        waitForText("TRY 2 OF 3", 20_000)
        Thread.sleep(7_000)
        toBottom()
        tickThenCapture("udp-progress-03-try-two")
        // Seven seconds into a fifteen second try, with the third try and the
        // ping still to come: between 36 and 40 seconds left, never a whole try.
        val left = composeRule.onAllNodesWithText(" s left", substring = true).fetchSemanticsNodes()
            .single().config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString().filter(Char::isDigit).toInt()
        assertTrue("seconds left was $left", left in 36..40)
        assertTrue("the try in flight was sent", quiet.received >= 2)

        // The ping on loopback answers in microseconds, so the ping stage is too
        // short to photograph. What matters is that the panel hands over to the
        // result and goes away.
        waitForText("Not refused, host answers ping", 50_000)
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("udp-test-progress").fetchSemanticsNodes().isEmpty()
        }
        toBottom()
        composeRule.onNodeWithTag("udp-test-duration").assertExists()
        composeRule.captureScreenshot("udp-progress-04-result")
    }
}
