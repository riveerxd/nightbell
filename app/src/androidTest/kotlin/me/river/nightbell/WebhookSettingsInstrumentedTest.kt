package me.river.nightbell

import android.Manifest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.runBlocking
import me.river.nightbell.NightbellTestSupport.appContext
import me.river.nightbell.NightbellTestSupport.awaitTrue
import me.river.nightbell.NightbellTestSupport.captureScreenshot
import me.river.nightbell.NightbellTestSupport.openSettingsTab
import me.river.nightbell.data.Nightbell
import me.river.nightbell.data.NightbellSnapshot
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.Health
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorGroup
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.MonitorRuntime
import me.river.nightbell.domain.WebhookEvent
import me.river.nightbell.domain.WebhookFormat
import me.river.nightbell.domain.WebhookState
import me.river.nightbell.domain.WebhookStatus
import me.river.nightbell.domain.WebhookTarget
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The webhook screens, driven the way a person would: from Settings, through the
 * editor, with a real test message sent to a server on the device.
 */
@RunWith(AndroidJUnit4::class)
class WebhookSettingsInstrumentedTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var receiver: TinyHttpServer
    private val graph get() = Nightbell.install(appContext)

    @Before
    fun setUp() {
        receiver = TinyHttpServer { TinyHttpServer.Response(code = 202, reason = "Accepted", body = "{\"ok\":true}") }
    }

    @After
    fun tearDown() {
        scenario?.close()
        receiver.close()
        NightbellTestSupport.resetApp()
    }

    private val monitors = listOf(
        Monitor(id = "m1", name = "Checkout API", kind = MonitorKind.HTTP_STATUS, url = "https://checkout.example.com"),
        Monitor(id = "m2", name = "Status page", kind = MonitorKind.HTTP_STATUS, url = "https://status.example.com"),
    )

    private fun seed(
        webhooks: List<WebhookTarget> = emptyList(),
        state: WebhookState = WebhookState(),
        groups: List<MonitorGroup> = emptyList(),
    ) {
        runBlocking {
            graph.store.replaceAll(
                NightbellSnapshot(
                    monitors = monitors,
                    runtimes = monitors.associate { it.id to MonitorRuntime(health = Health.UP) },
                    groups = groups,
                    settings = GlobalSettings(
                        motionIntensity = 0f,
                        hasSeenPagerSetup = true,
                        pagerSetupSilenced = true,
                        updateChecksEnabled = false,
                        webhooks = webhooks,
                    ),
                    webhookState = state,
                ),
            )
        }
    }

    private fun openSettings() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Settings").performClick()
        composeRule.waitForIdle()
        composeRule.openSettingsTab("Alerts")
    }

    private fun scrollSettingsTo(tag: String) {
        composeRule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag(tag))
        composeRule.waitForIdle()
    }

    private fun scrollEditorTo(tag: String) {
        composeRule.onNodeWithTag("webhook-scroll").performScrollToNode(hasTestTag(tag))
        composeRule.waitForIdle()
    }

    /** The inline note, not the toast that repeats it on a failed save. */
    private fun inline(text: String) = composeRule.onNode(
        hasText(text, substring = true) and !hasContentDescription("Failed", substring = true),
    )

    /** Closes the keyboard first, as a person does: the footer steps aside while it is up. */
    private fun save() {
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("webhook-save").performClick()
        composeRule.waitForIdle()
    }

    private fun targets() = runBlocking { graph.store.currentSnapshot().settings.webhooks }

    @Test
    fun addingAWebhookFromNothingTestingItAndSavingIt() {
        seed()
        openSettings()
        scrollSettingsTo("webhook-add")
        composeRule.captureScreenshot("webhook-01-empty-card")

        composeRule.onNodeWithTag("webhook-add").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("New webhook").assertIsDisplayed()
        composeRule.captureScreenshot("webhook-02-new-editor")

        // Save with nothing filled: the address error appears and nothing is stored.
        save()
        composeRule.waitForIdle()
        inline("Paste the webhook's address").assertIsDisplayed()
        assertTrue(targets().isEmpty())
        composeRule.captureScreenshot("webhook-03-address-required")

        composeRule.onNodeWithContentDescription("Name").performTextInput("Ops receiver")
        composeRule.onNodeWithContentDescription("Webhook URL").performTextInput(receiver.url("/hook"))
        composeRule.waitForIdle()

        scrollEditorTo("webhook-test")
        composeRule.onNodeWithTag("webhook-test").performClick()
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithTagExists("webhook-test-result")
        }
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        scrollEditorTo("webhook-bottom")
        composeRule.onNodeWithText("Delivered. HTTP 202", substring = true).assertIsDisplayed()
        // The bar used to float over the form, and the result is the last thing
        // on it, so at the bottom of the scroll it sat under the Save button.
        val result = composeRule.onNodeWithTag("webhook-test-result").fetchSemanticsNode().boundsInRoot
        val bar = composeRule.onNodeWithTag("webhook-save").fetchSemanticsNode().boundsInRoot
        assertTrue("result ends at ${result.bottom}, bar starts at ${bar.top}", result.bottom <= bar.top)
        composeRule.captureScreenshot("webhook-04-test-delivered")
        val testPost = receiver.received.single()
        assertEquals("test", testPost.headers["x-nightbell-event"])
        assertTrue("a test is not a save", targets().isEmpty())

        save()
        composeRule.waitForIdle()
        awaitTrue(description = "the target saved") { targets().size == 1 }
        val saved = targets().single()
        assertEquals("Ops receiver", saved.name)
        assertEquals(receiver.url("/hook"), saved.url)
        assertEquals(WebhookEvent.defaults, saved.events)

        scrollSettingsTo("webhook-row-${saved.id}")
        composeRule.onNodeWithText("Ops receiver").assertIsDisplayed()
        composeRule.onNodeWithText("Nothing sent yet", substring = true).assertIsDisplayed()
        composeRule.captureScreenshot("webhook-05-saved-row")
    }

    @Test
    fun aWebPageInsteadOfAWebhookIsNamedNotDumped() {
        TinyHttpServer {
            TinyHttpServer.Response(
                code = 404,
                reason = "Not Found",
                contentType = "text/html; charset=utf-8",
                body = "<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\"><title>404: Page not found | Example Shop</title></head><body>" +
                    "x".repeat(3000) + "</body></html>",
            )
        }.use { site ->
            seed()
            openSettings()
            scrollSettingsTo("webhook-add")
            composeRule.onNodeWithTag("webhook-add").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("Webhook URL").performTextInput(site.url("/not-a-hook"))
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            scrollEditorTo("webhook-test")
            composeRule.onNodeWithTag("webhook-test").performClick()
            composeRule.waitUntil(15_000) { composeRule.onAllNodesWithTagExists("webhook-test-result") }
            scrollEditorTo("webhook-bottom")
            composeRule.onNodeWithText("HTTP 404. Nothing at that address", substring = true).assertIsDisplayed()
            composeRule.onAllNodes(hasText("a web page titled \"404: Page not found | Example Shop\"", substring = true))
                .fetchSemanticsNodes().let { assertTrue("the title is shown", it.isNotEmpty()) }
            assertTrue(
                "no markup on screen",
                composeRule.onAllNodes(hasText("<!DOCTYPE", substring = true)).fetchSemanticsNodes().isEmpty(),
            )
            val result = composeRule.onNodeWithTag("webhook-test-result").fetchSemanticsNode().boundsInRoot
            val bar = composeRule.onNodeWithTag("webhook-save").fetchSemanticsNode().boundsInRoot
            assertTrue("result ends at ${result.bottom}, bar starts at ${bar.top}", result.bottom <= bar.top)
            composeRule.captureScreenshot("webhook-14-web-page-404")
        }
    }

    @Test
    fun aSavedAddressIsHiddenUntilReplaced() {
        val target = WebhookTarget(
            id = "t1",
            name = "Team channel",
            format = WebhookFormat.SLACK,
            url = "https://hooks.slack.com/services/T000/B000/abcdefQx7TokenPartwxyz",
        )
        seed(webhooks = listOf(target))
        openSettings()
        scrollSettingsTo("webhook-row-t1")
        composeRule.onNodeWithTag("webhook-row-t1").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Edit webhook").assertIsDisplayed()
        composeRule.onNodeWithTag("webhook-address-redacted").assertIsDisplayed()
        composeRule.onNodeWithText("hooks.slack.com/…wxyz").assertIsDisplayed()
        val leaks = composeRule.onAllNodes(hasText("Qx7TokenPart", substring = true), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .map { node -> node.config.toString() }
        assertTrue("the secret part never reaches the screen, found: $leaks", leaks.isEmpty())
        composeRule.captureScreenshot("webhook-06-redacted-address")

        composeRule.onNodeWithTag("webhook-address-replace").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Webhook URL").assertIsDisplayed()
        composeRule.captureScreenshot("webhook-07-replacing-address")
    }

    @Test
    fun aFailingWebhookIsShownInRoseWithTheReason() {
        seed(
            webhooks = listOf(
                WebhookTarget(id = "ok", name = "Discord ops", format = WebhookFormat.DISCORD, url = "https://discord.com/api/webhooks/1/x"),
                WebhookTarget(id = "bad", name = "Teams on call", format = WebhookFormat.TEAMS, url = "https://x.logic.azure.com/w"),
                WebhookTarget(id = "off", name = "Old ntfy", format = WebhookFormat.NTFY, url = "https://ntfy.sh/x", enabled = false),
                WebhookTarget(id = "new", name = "From backup", format = WebhookFormat.TELEGRAM, url = ""),
            ),
            state = WebhookState(
                status = mapOf(
                    "ok" to WebhookStatus(lastDeliveredAt = System.currentTimeMillis() - 4 * 60_000, lastCode = 204),
                    "bad" to WebhookStatus(failuresInARow = 3, lastCode = 404, lastError = "HTTP 404. Nothing at that address. Check it was copied whole"),
                ),
            ),
        )
        openSettings()
        scrollSettingsTo("webhook-sender")
        composeRule.onNodeWithText("Failing: HTTP 404", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Last delivered 4 min ago").assertIsDisplayed()
        composeRule.onNodeWithText("Off, nothing is sent").assertIsDisplayed()
        composeRule.onNodeWithText("Needs its address", substring = true).assertIsDisplayed()
        scrollSettingsTo("webhooks-enabled")
        composeRule.captureScreenshot("webhook-08-many-states")
    }

    @Test
    fun telegramAsksForItsChatAndCustomShowsTheRequest() {
        seed()
        openSettings()
        scrollSettingsTo("webhook-add")
        composeRule.onNodeWithTag("webhook-add").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Telegram").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Bot token").performTextInput("123456789:AAbbccddeeffgghhiijjkk")
        save()
        composeRule.waitForIdle()
        // No scroll here on purpose: a refused save has to bring the field into view itself.
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Chat ID").assertIsDisplayed()
        inline("Which chat should the bot write to?").assertIsDisplayed()
        composeRule.captureScreenshot("webhook-09-telegram-chat-required")
        assertTrue(targets().isEmpty())

        composeRule.onNodeWithText("Custom request").performScrollTo().performClick()
        composeRule.waitForIdle()
        scrollEditorTo("webhook-body")
        composeRule.onNodeWithContentDescription("Body").performTextInput("{\"t\": \"{{titel}}\"}")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("{{titel}} is not a placeholder", substring = true).assertIsDisplayed()
        composeRule.captureScreenshot("webhook-10-custom-request")
    }

    @Test
    fun choosingSomeMonitorsAndAGroup() {
        seed(groups = listOf(MonitorGroup(id = "g", title = "Public", memberIds = listOf("m2"))))
        openSettings()
        scrollSettingsTo("webhook-add")
        composeRule.onNodeWithTag("webhook-add").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Webhook URL").performTextInput(receiver.url("/x"))
        scrollEditorTo("webhook-scope")
        composeRule.onNodeWithText("Only some").performClick()
        composeRule.waitForIdle()
        save()
        composeRule.waitForIdle()
        inline("Pick at least one monitor or group").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithText("Public").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Covered by a group you picked").performScrollTo().assertIsDisplayed()
        composeRule.captureScreenshot("webhook-11-scope-group")
        save()
        awaitTrue(description = "saved") { targets().size == 1 }
        val scope = targets().single().scope
        assertFalse(scope.all)
        assertEquals(setOf("g"), scope.groupIds)
    }

    @Test
    fun theMonitorScreenLinksToItsWebhookAndDeletingRemovesIt() {
        seed(webhooks = listOf(WebhookTarget(id = "t1", name = "Ops channel", url = receiver.url("/x"))))
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Checkout API").performClick()
        composeRule.waitForIdle()
        composeRule.onNode(hasTestTag("detail-webhook-t1")).performScrollTo()
        composeRule.captureScreenshot("webhook-12-detail-link")
        composeRule.onNodeWithTag("detail-webhook-t1").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Edit webhook").assertIsDisplayed()

        scrollEditorTo("webhook-delete")
        composeRule.mainClock.autoAdvance = false
        val node = composeRule.onNodeWithTag("webhook-delete")
        node.performTouchInput { down(center) }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeBy(1_500)
        composeRule.mainClock.advanceTimeByFrame()
        node.performTouchInput { up() }
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        awaitTrue(description = "deleted") { targets().isEmpty() }
    }

    @Test
    fun leavingWithChangesAsksFirst() {
        seed()
        openSettings()
        scrollSettingsTo("webhook-add")
        composeRule.onNodeWithTag("webhook-add").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Name").performTextInput("Half done")
        composeRule.onNodeWithContentDescription("Close without saving").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Discard this webhook?").assertIsDisplayed()
        composeRule.captureScreenshot("webhook-13-discard-prompt")
        composeRule.onNodeWithTag("webhook-discard").performClick()
        composeRule.waitForIdle()
        assertTrue(targets().isEmpty())
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTagExists(tag: String): Boolean =
        onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
}
