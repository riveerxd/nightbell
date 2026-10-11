package me.river.nightbell

import android.Manifest
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToLog
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import me.river.nightbell.NightbellTestSupport.captureScreenshot
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.ThemeChoice
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Setting up a health monitor the way a person does, against a real socket in
 * the app's own process: the probe that decides the sign-in card, the open
 * endpoint that needs nothing, the protected one that asks, and a fixed key.
 */
@RunWith(AndroidJUnit4::class)
class HealthEndpointSetupInstrumentedTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        NightbellTestSupport.resetApp(GlobalSettings(motionIntensity = 0f, theme = ThemeChoice.DARK))
    }

    @After
    fun tearDown() {
        scenario?.close()
    }

    private fun openWizard() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Add a monitor").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Health endpoint").performScrollTo().performClick()
        composeRule.onNodeWithText("Continue").performClick()
        composeRule.waitForIdle()
    }

    /** Types the address, then leaves the field the way a thumb does, which is what starts the probe. */
    private fun enterUrl(url: String) {
        composeRule.onNodeWithContentDescription("Health endpoint URL").performScrollTo().performTextInput(url)
        composeRule.onNodeWithContentDescription("Name").performScrollTo().performClick()
        Espresso.closeSoftKeyboard()
        composeRule.waitForIdle()
    }

    private fun awaitTag(tag: String) {
        try {
            composeRule.waitUntil(20_000) { composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        } catch (error: Throwable) {
            composeRule.captureScreenshot("health-setup-timeout-$tag")
            composeRule.onRoot().printToLog("healthSetup")
            throw error
        }
    }

    private fun testNowUntil(text: String) {
        composeRule.onNodeWithTag("setup-scroll").performScrollToNode(hasTestTag("setup-bottom"))
        composeRule.onNodeWithText("Test now").performClick()
        composeRule.waitUntil(20_000) {
            composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("setup-scroll").performScrollToNode(hasTestTag("setup-bottom"))
        composeRule.waitForIdle()
    }

    @Test
    fun anOpenEndpointSaysNoSignInIsNeededAndTestsUp() {
        TinyHttpServer { TinyHttpServer.Response(body = HealthPayloads.FULL.replace("\"status\": \"down\",\n  \"message\"", "\"status\": \"up\",\n  \"message\"")) }.use { server ->
            openWizard()
            composeRule.captureScreenshot("health-setup-01-empty")
            enterUrl(server.url("/health"))

            awaitTag("health-no-sign-in")
            composeRule.onNodeWithTag("health-no-sign-in").performScrollTo()
            composeRule.captureScreenshot("health-setup-02-no-sign-in")

            testNowUntil("Check passed")
            composeRule.captureScreenshot("health-setup-03-tested")
        }
    }

    @Test
    fun aProtectedEndpointOffersASignIn() {
        TinyHttpServer {
            TinyHttpServer.Response(code = 401, reason = "Unauthorized", body = "Missing or invalid Authorization header")
        }.use { server ->
            openWizard()
            enterUrl(server.url("/api/health"))

            awaitTag("health-needs-sign-in")
            composeRule.onNodeWithText("This endpoint needs a sign-in.").assertExists()
            composeRule.captureScreenshot("health-setup-04-needs-sign-in")

            composeRule.onNodeWithTag("health-other-ways").performClick()
            composeRule.waitForIdle()
            composeRule.captureScreenshot("health-setup-05-other-ways")
        }
    }

    @Test
    fun aFixedKeyGoesOutAsAHeader() {
        TinyHttpServer { request ->
            if (request.headers["x-api-key"] == "k-123") {
                TinyHttpServer.Response(body = HealthPayloads.MINIMAL)
            } else {
                TinyHttpServer.Response(code = 401, reason = "Unauthorized", body = "no key")
            }
        }.use { server ->
            openWizard()
            enterUrl(server.url("/health"))
            awaitTag("health-needs-sign-in")
            composeRule.onNodeWithTag("health-other-ways").performClick()
            composeRule.onNodeWithText("Headers").performClick()
            composeRule.onNodeWithContentDescription("Add header").performScrollTo().performClick()
            // The monitor's own Name field is above, so the header's is the last one.
            composeRule.onAllNodesWithContentDescription("Name").onLast().performScrollTo().performTextInput("X-Api-Key")
            composeRule.onAllNodesWithContentDescription("Value").onLast().performScrollTo().performTextInput("k-123")
            Espresso.closeSoftKeyboard()

            testNowUntil("Check passed")
            composeRule.captureScreenshot("health-setup-06-header-key")
            assertEquals("k-123", server.received.last().headers["x-api-key"])
        }
    }

    /**
     * Reported from the maintainer's phone: typed the address, put the keyboard
     * away with Back, and the sign-in card never came. Back hides the keyboard
     * but leaves the field focused, so a probe that waited for the field to be
     * left never ran until a trip back a step and forward again.
     */
    @Test
    fun typingTheAddressIsEnoughForTheSignInToAppear() {
        TinyHttpServer {
            TinyHttpServer.Response(code = 401, reason = "Unauthorized", body = "Missing or invalid Authorization header")
        }.use { server ->
            openWizard()
            composeRule.onNodeWithContentDescription("Health endpoint URL").performScrollTo()
                .performTextInput(server.url("/health"))
            Espresso.closeSoftKeyboard()

            awaitTag("health-needs-sign-in")
            composeRule.onNodeWithTag("health-sign-in").assertIsDisplayed()
            composeRule.captureScreenshot("health-setup-07-typed-only")
        }
    }

    /**
     * A web app behind SSO tends to answer an anonymous request with its login
     * page, not a 401. The methods used to show only after a refusal, so that
     * address left nothing to tap.
     */
    @Test
    fun aLoginPageInsteadOfAReportStillOffersASignIn() {
        TinyHttpServer {
            TinyHttpServer.Response(
                contentType = "text/html",
                body = "<!doctype html><html><body><form><input name=password></form></body></html>",
            )
        }.use { server ->
            openWizard()
            enterUrl(server.url("/health"))

            awaitTag("health-no-report")
            composeRule.onNodeWithTag("health-no-report").performScrollTo()
            composeRule.captureScreenshot("health-setup-09-login-page")
            composeRule.onNodeWithTag("health-choose-sign-in").performClick()
            composeRule.onNodeWithTag("health-method").performScrollTo().assertIsDisplayed()
            composeRule.captureScreenshot("health-setup-10-login-page-methods")
        }
    }

    /** The card answers for the address in the field, not for one that used to be there. */
    @Test
    fun changingTheAddressReplacesTheOldAnswer() {
        TinyHttpServer { request ->
            if (request.path.startsWith("/open")) {
                TinyHttpServer.Response(body = HealthPayloads.MINIMAL)
            } else {
                TinyHttpServer.Response(code = 401, reason = "Unauthorized", body = "no key")
            }
        }.use { server ->
            openWizard()
            enterUrl(server.url("/closed"))
            awaitTag("health-needs-sign-in")

            composeRule.onNodeWithContentDescription("Health endpoint URL").performScrollTo()
                .performTextReplacement(server.url("/open"))
            // The old verdict goes the moment the address changes.
            composeRule.onAllNodesWithTag("health-needs-sign-in").assertCountEquals(0)
            Espresso.closeSoftKeyboard()

            awaitTag("health-no-sign-in")
            composeRule.onAllNodesWithTag("health-needs-sign-in").assertCountEquals(0)
            composeRule.captureScreenshot("health-setup-08-address-changed")
        }
    }
}
