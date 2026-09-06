package me.river.nightbell

import android.Manifest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.runBlocking
import me.river.nightbell.NightbellTestSupport.captureScreenshot
import me.river.nightbell.data.Nightbell
import me.river.nightbell.domain.ElementTarget
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The stalled verdict as it is actually read: on the setup screen, after the
 * button the reporter in issue 8 pressed.
 *
 * The wording is asserted elsewhere. What only a screen can answer is whether
 * the sentence that rules the timeout out is still on it. The card clamps this
 * paragraph at six lines and ellipsises the rest, and the paragraph opens with
 * six facts before it reaches any advice, so the advice being present in the
 * string is not the same as the advice being readable.
 */
@RunWith(AndroidJUnit4::class)
class StalledVerdictUiInstrumentedTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @get:Rule
    val permissions: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private lateinit var server: TinyHttpServer
    private var scenario: ActivityScenario<MainActivity>? = null

    private val stalling = """
        <!doctype html>
        <html><head><title>Stalled</title>
          <script src="http://nightbell.invalid/tracker.js"></script>
          <script src="/never-answers.js"></script>
        </head>
        <body><span id="price">GBP 42.00</span></body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        NightbellTestSupport.resetApp()
        server = TinyHttpServer { request ->
            if (request.path.startsWith("/never-answers.js")) {
                TinyHttpServer.Response(
                    body = "",
                    contentType = "application/javascript",
                    delayMs = 120_000,
                )
            } else {
                TinyHttpServer.Response(body = stalling, contentType = "text/html; charset=utf-8")
            }
        }
        runBlocking {
            Nightbell.install(NightbellTestSupport.appContext).store.upsert(
                Monitor(
                    id = "stalled",
                    name = "Stalled shop",
                    kind = MonitorKind.WEBSITE_ELEMENT,
                    url = server.url("/"),
                    timeoutSeconds = 15,
                ).withTargets(listOf(ElementTarget(elementId = "price"))),
            )
        }
    }

    @After
    fun tearDown() {
        scenario?.close()
        server.close()
    }

    @Test
    fun theVerdictReachesTheScreenWithItsAdviceIntact() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Stalled shop").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Edit monitor").performClick()
        composeRule.waitForIdle()

        // Test only exists past the kind step, which the wizard opens on even
        // when it is editing something that already has a kind.
        composeRule.onNodeWithText("Continue").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Test now").performScrollTo().performClick()
        composeRule.waitUntil(90_000) {
            composeRule.onAllNodesWithText("never finished loading", substring = true)
                .fetchSemanticsNodes().size == 1
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("never finished loading", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        // The amber line above the paragraph, which is read first and used to
        // say "Raise the timeout, or the service is genuinely slow." on every
        // page that ran out of time, including this one, which had already
        // spent its budget waiting on a request that failed.
        composeRule.onNodeWithText("Raise the timeout", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Something the page asked for never answered", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.captureScreenshot("stalled-01-verdict-on-the-setup-screen")

        // performScrollTo stops as soon as the node is inside the scroll
        // viewport, and the viewport runs on underneath the footer. The column
        // carries a footer-height spacer for exactly this reason, so reaching
        // the end is what puts the last of the card above the buttons.
        repeat(3) {
            composeRule.onNodeWithTag("setup-scroll").performTouchInput { swipeUp() }
            composeRule.waitForIdle()
        }
        composeRule.onNodeWithText("A longer timeout will not help", substring = true)
            .assertIsDisplayed()
        composeRule.captureScreenshot("stalled-02-the-paragraph-under-it")

        val shown = composeRule.onNodeWithText("Stopped at the page load", substring = true)
            .fetchSemanticsNode()
        assertTrue("the verdict rendered with no height", shown.size.height > 0)
    }
}
