package me.river.pulse

import android.Manifest
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.up
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import me.river.pulse.NightbellTestSupport.captureScreenshot
import me.river.pulse.data.Nightbell
import me.river.pulse.data.NightbellSnapshot
import me.river.pulse.domain.CertificateWatch
import me.river.pulse.domain.GlobalSettings
import me.river.pulse.domain.Health
import me.river.pulse.domain.Monitor
import me.river.pulse.domain.MonitorKind
import me.river.pulse.domain.MonitorRuntime
import me.river.pulse.domain.Sample
import me.river.pulse.domain.ThemeChoice
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives every surface added or changed in this revision and captures it.
 *
 * The unit tests cover the rules; this covers the thing the rules are for. Each
 * case is written so that a *silent* regression is impossible: the assertions name
 * text that only exists if the feature rendered, and the screenshots exist so the
 * result can be looked at rather than inferred.
 */
@RunWith(AndroidJUnit4::class)
class RevisionVerificationTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @get:Rule
    val permissions: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun tearDown() {
        scenario?.close()
    }

    private val now = System.currentTimeMillis()
    private val day = CertificateWatch.DAY_MS

    /** Enough monitors to push past the controls threshold, with mixed health. */
    private fun seed(theme: ThemeChoice = ThemeChoice.DARK, monitorCount: Int = 6) {
        fun history(count: Int, failures: Set<Int> = emptySet()) = (0 until count).map { i ->
            val ok = i !in failures
            Sample(
                at = now - (count - i) * 10 * 60_000L,
                ok = ok,
                latencyMs = if (ok) 120L + i * 7 else 2_400L,
                code = if (ok) 200 else 503,
                note = if (ok) "" else "Got 503, expected = 200",
            )
        }

        val names = listOf(
            "Checkout API" to Health.UP,
            "Marketing site" to Health.DOWN,
            "Asset CDN" to Health.DEGRADED,
            "Billing worker" to Health.UP,
            "Legacy redirect" to Health.PAUSED,
            "Search cluster" to Health.UP,
        ).take(monitorCount)

        val monitors = names.mapIndexed { index, (name, health) ->
            Monitor(
                id = "m$index",
                name = name,
                kind = MonitorKind.HTTP_STATUS,
                url = "https://${name.substringBefore(' ').lowercase()}.example.com",
                enabled = health != Health.PAUSED,
                intervalMinutes = 15,
                createdAt = now,
            )
        }
        val runtimes = names.mapIndexed { index, (_, health) ->
            "m$index" to MonitorRuntime(
                health = if (health == Health.PAUSED) Health.UP else health,
                lastCheckedAt = now - 120_000,
                lastLatencyMs = if (health == Health.DEGRADED) 3_200 else 180,
                lastCode = if (health == Health.DOWN) 503 else 200,
                lastMessage = if (health == Health.DOWN) "Got 503, expected = 200" else "",
                lastDetail = if (health == Health.DOWN) "HTTP/1.1 · 503 Service Unavailable" else "",
                consecutiveFailures = if (health == Health.DOWN) 2 else 0,
                alerting = health == Health.DOWN,
                // Index 1 (the down one) carries a certificate three days out, so
                // the card tag and the detail card both have something to show.
                // Six hours of margin: daysLeft floors, so `now + 3*day` becomes
                // "2d" the instant any time passes. The flooring is correct — an
                // evening must never be called a day — so the fixture gives it room
                // rather than the assertion pretending otherwise.
                certExpiresAt = if (index == 1) now + 3 * day + 6 * 60 * 60_000L else 0L,
                certIssuer = if (index == 1) "Example Trust CA" else "",
                samples = history(40, failures = if (health == Health.DOWN) setOf(37, 38, 39) else emptySet()),
            )
        }.toMap()

        runBlocking {
            Nightbell.install(NightbellTestSupport.appContext).store.replaceAll(
                NightbellSnapshot(
                    monitors = monitors,
                    runtimes = runtimes,
                    settings = GlobalSettings(
                        motionIntensity = 0f,
                        hasSeenPagerSetup = true,
                        theme = theme,
                    ),
                ),
            )
        }
    }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()
    }

    /**
     * Scroll a monitor into view, then open it.
     *
     * A lazy grid only composes what is visible, so a card below the fold is not
     * "hidden" — it does not exist in the semantics tree at all. Worst-first
     * ordering also means the card you want is rarely the one on screen.
     */
    private fun openMonitor(name: String) {
        composeRule.onNodeWithTag("dashboard-list").performScrollToNode(hasText(name))
        composeRule.waitForIdle()
        composeRule.onNodeWithText(name).performClick()
        composeRule.waitForIdle()
    }

    private fun scrollDashboardTo(name: String) {
        composeRule.onNodeWithTag("dashboard-list").performScrollToNode(hasText(name))
        composeRule.waitForIdle()
    }

    // ---- light scheme ------------------------------------------------------

    @Test
    fun theLightSchemeRendersEveryScreen() {
        seed(theme = ThemeChoice.LIGHT)
        launch()
        composeRule.onNodeWithText("NIGHTBELL").assertIsDisplayed()
        // If the scheme had failed to resolve, the dashboard would still render —
        // in dark. So assert on content and capture the colours for review.
        composeRule.onNodeWithText("Marketing site").assertIsDisplayed()
        composeRule.captureScreenshot("light-01-dashboard")

        openMonitor("Marketing site")
        composeRule.captureScreenshot("light-02-detail")

        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Settings").performClick()
        composeRule.waitForIdle()
        composeRule.captureScreenshot("light-03-settings")
    }

    @Test
    fun theDarkSchemeStillRendersEveryScreen() {
        seed(theme = ThemeChoice.DARK)
        launch()
        composeRule.onNodeWithText("Marketing site").assertIsDisplayed()
        composeRule.captureScreenshot("dark-01-dashboard")

        openMonitor("Marketing site")
        composeRule.captureScreenshot("dark-02-detail")
    }

    @Test
    fun theThemeSelectorSwitchesSchemeLive() {
        seed(theme = ThemeChoice.DARK)
        launch()
        composeRule.onNodeWithContentDescription("Settings").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings-list")
            .performScrollToNode(hasContentDescription("Appearance"))
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Light").performClick()
        composeRule.waitForIdle()
        composeRule.captureScreenshot("theme-01-switched-to-light")

        assertEquals(
            ThemeChoice.LIGHT,
            runBlocking { Nightbell.require().store.currentSnapshot().settings.theme },
        )
    }

    // ---- dashboard narrowing ----------------------------------------------

    @Test
    fun searchAndFilterNarrowTheList() {
        seed()
        launch()
        // Nothing on screen until asked for.
        assertEquals(
            0,
            composeRule.onAllNodesWithContentDescription("Search").fetchSemanticsNodes().size,
        )
        openSearchPanel()
        composeRule.captureScreenshot("query-01-controls")

        composeRule.onNodeWithContentDescription("Search").performTextInput("checkout")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Checkout API").assertIsDisplayed()
        // A name that does not match must be gone, not merely re-ordered.
        assertEquals(0, composeRule.onAllNodesWithText("Marketing site").fetchSemanticsNodes().size)
        composeRule.captureScreenshot("query-02-searched")

        composeRule.onNodeWithContentDescription("Clear search").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Close panel").performClick()
        composeRule.waitForIdle()
        openTunePanel()
        composeRule.onNodeWithText("Problems").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Close panel").performClick()
        composeRule.waitForIdle()
        // Down + degraded stay; healthy and paused go.
        composeRule.onNodeWithText("Marketing site").assertIsDisplayed()
        scrollDashboardTo("Asset CDN")
        composeRule.onNodeWithText("Asset CDN").assertIsDisplayed()
        // The healthy ones must be gone from the tree entirely, not merely below
        // the fold — that is the difference between filtering and re-ordering.
        assertEquals(0, composeRule.onAllNodesWithText("Billing worker").fetchSemanticsNodes().size)
        composeRule.captureScreenshot("query-03-problems-filter")
    }

    @Test
    fun anEmptyFilterExplainsItselfAndOffersAWayBack() {
        // Nothing is broken, so "Problems" is empty — and that is good news, which
        // the copy is supposed to say out loud rather than reading as an error.
        seed(monitorCount = 6)
        runBlocking {
            val store = Nightbell.install(NightbellTestSupport.appContext).store
            val snap = store.currentSnapshot()
            store.replaceAll(
                snap.copy(
                    runtimes = snap.runtimes.mapValues { (_, r) ->
                        r.copy(health = Health.UP, lastMessage = "", alerting = false)
                    },
                ),
            )
        }
        launch()
        openTunePanel()
        composeRule.onNodeWithText("Problems").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Close panel").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Nothing is broken. That is the answer you wanted.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Clear filters").assertIsDisplayed()
        composeRule.captureScreenshot("query-04-empty-filter")

        composeRule.onNodeWithText("Clear filters").performClick()
        composeRule.waitForIdle()
        // Clearing restores the full list; the card may sit below the fold, so
        // scroll to it rather than asserting it happens to be on screen.
        scrollDashboardTo("Checkout API")
        composeRule.onNodeWithText("Checkout API").assertIsDisplayed()
    }

    // ---- bulk selection ----------------------------------------------------

    @Test
    fun longPressSelectsAndBulkPauseApplies() {
        seed()
        launch()
        // Long-press a card to enter selection mode.
        scrollDashboardTo("Checkout API")
        composeRule.onNodeWithText("Checkout API").performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("1 selected").assertIsDisplayed()
        composeRule.captureScreenshot("bulk-01-selected")

        scrollDashboardTo("Billing worker")
        composeRule.onNodeWithText("Billing worker").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("2 selected").assertIsDisplayed()
        composeRule.captureScreenshot("bulk-02-two-selected")

        composeRule.onNodeWithText("Pause").performClick()
        composeRule.waitForIdle()
        NightbellTestSupport.awaitTrue(description = "both monitors paused") {
            runBlocking {
                val monitors = Nightbell.require().store.currentSnapshot().monitors
                monitors.count { !it.enabled } == 3 // the two just paused + the seeded one
            }
        }
        composeRule.captureScreenshot("bulk-03-after-pause")
    }

    @Test
    fun bulkDeleteAsksFirstAndThenRemovesThem() {
        seed()
        launch()
        scrollDashboardTo("Checkout API")
        composeRule.onNodeWithText("Checkout API").performTouchInput { longClick() }
        composeRule.waitForIdle()
        // "Delete" is a labelled button rather than a bare trash icon, and it is
        // unique within each state of the bar: alongside "Mute 1h" before, and
        // alongside "Keep them" once the confirmation is up.
        composeRule.onNodeWithText("Delete").performClick()
        composeRule.waitForIdle()
        // The confirmation must exist — this destroys history for several monitors.
        composeRule.onNodeWithText("Keep them").assertIsDisplayed()
        composeRule.captureScreenshot("bulk-04-delete-confirm")

        composeRule.onNodeWithText("Keep them").performClick()
        composeRule.waitForIdle()
        assertEquals(6, runBlocking { Nightbell.require().store.currentSnapshot().monitors.size })

        composeRule.onNodeWithText("Delete").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Delete").performClick()
        NightbellTestSupport.awaitTrue(description = "one monitor deleted") {
            runBlocking { Nightbell.require().store.currentSnapshot().monitors.size } == 5
        }
        composeRule.captureScreenshot("bulk-05-after-delete")
    }

    // ---- certificate -------------------------------------------------------

    @Test
    fun theCertificateCardAndCardTagRender() {
        seed()
        launch()
        // The tag on the dashboard card: three days out.
        scrollDashboardTo("Marketing site")
        composeRule.onNodeWithText("cert 3d").assertIsDisplayed()
        composeRule.captureScreenshot("cert-01-card-tag")

        openMonitor("Marketing site")
        composeRule.onNodeWithTag("detail-list")
            .performScrollToNode(hasContentDescription("TLS certificate"))
        composeRule.waitForIdle()
        composeRule.onNodeWithText("3 days left").assertIsDisplayed()
        composeRule.onNodeWithText("Example Trust CA").assertIsDisplayed()
        composeRule.captureScreenshot("cert-02-detail-card")
    }

    @Test
    fun aMonitorWithNoCertificateShowsNoCertificateCard() {
        seed()
        launch()
        // Checkout API was seeded with no certificate at all.
        openMonitor("Checkout API")
        assertEquals(
            0,
            composeRule.onAllNodesWithText("TLS certificate").fetchSemanticsNodes().size,
        )
        composeRule.captureScreenshot("cert-03-absent")
    }

    // ---- uptime honesty ----------------------------------------------------

    @Test
    fun theUptimeRingDisclosesItsWindow() {
        seed()
        launch()
        openMonitor("Checkout API")
        // Seeded history spans 40 samples at 10-minute spacing — under seven hours,
        // so the ring must say how far back it can see rather than claim a day.
        composeRule.onNodeWithContentDescription("100 percent, past 6h").assertIsDisplayed()
        composeRule.captureScreenshot("uptime-01-partial-window")
    }

    @Test
    fun aFullDayOfHistoryReportsTwentyFourHourUptime() {
        seed()
        runBlocking {
            val store = Nightbell.install(NightbellTestSupport.appContext).store
            val snap = store.currentSnapshot()
            val spread = (0 until 40).map { i ->
                Sample(at = now - (40 - i) * 60 * 60_000L, ok = true, latencyMs = 150, code = 200)
            }
            store.replaceAll(
                snap.copy(runtimes = snap.runtimes.mapValues { (_, r) -> r.copy(samples = spread) }),
            )
        }
        launch()
        openMonitor("Checkout API")
        composeRule.onNodeWithContentDescription("100 percent, 24h uptime").assertIsDisplayed()
        composeRule.captureScreenshot("uptime-02-full-day")
    }

    // ---- setup back gesture ------------------------------------------------

    @Test
    fun systemBackWalksTheWizardBackAndGuardsTheDraft() {
        seed()
        launch()
        composeRule.onNodeWithContentDescription("Add a monitor").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("1/4").assertIsDisplayed()

        composeRule.onNodeWithText("Continue").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("2/4").assertIsDisplayed()

        // Type something worth losing.
        composeRule.onNodeWithContentDescription("URL").performTextInput("https://example.com")
        composeRule.waitForIdle()

        // System Back must step the wizard, not leave it.
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("1/4").assertIsDisplayed()
        composeRule.captureScreenshot("back-01-stepped-back")

        // Back again, from step 0 with a dirty draft, must ask before discarding.
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Discard this monitor?").assertIsDisplayed()
        composeRule.captureScreenshot("back-02-discard-prompt")

        composeRule.onNodeWithText("Keep editing").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("1/4").assertIsDisplayed()

        // And discarding really does leave, without saving.
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Discard").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("NIGHTBELL").assertIsDisplayed()
        assertEquals(6, runBlocking { Nightbell.require().store.currentSnapshot().monitors.size })
    }

    @Test
    fun anUntouchedWizardClosesWithoutArgument() {
        // A confirmation you always get is a confirmation you stop reading.
        seed()
        launch()
        composeRule.onNodeWithContentDescription("Add a monitor").performClick()
        composeRule.waitForIdle()
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("NIGHTBELL").assertIsDisplayed()
        assertEquals(
            0,
            composeRule.onAllNodesWithText("Discard this monitor?").fetchSemanticsNodes().size,
        )
    }

    // ---- help --------------------------------------------------------------

    @Test
    fun theHelpSectionExpandsAnAnswer() {
        seed()
        launch()
        composeRule.onNodeWithContentDescription("Settings").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings-list")
            .performScrollToNode(hasText("Why is my monitor checked less often than I set?"))
        composeRule.waitForIdle()
        composeRule.captureScreenshot("help-01-collapsed")

        composeRule.onNodeWithText("Why is my monitor checked less often than I set?").performClick()
        composeRule.waitForIdle()
        composeRule.onNode(hasText("fifteen-minute floor", substring = true)).assertIsDisplayed()
        composeRule.captureScreenshot("help-02-expanded")
    }

    // ---- templates ---------------------------------------------------------

    @Test
    fun aTemplateFillsInTheExpectationsItPromises() {
        runBlocking {
            Nightbell.install(NightbellTestSupport.appContext).store.replaceAll(
                NightbellSnapshot(settings = GlobalSettings(motionIntensity = 0f, hasSeenPagerSetup = true)),
            )
        }
        launch()
        composeRule.onNodeWithText("Watch something").assertIsDisplayed()
        composeRule.captureScreenshot("template-01-first-run")

        composeRule.onNodeWithText("An API, with a budget").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("2/4").assertIsDisplayed()

        // Step through to the cadence step and confirm the budget really arrived.
        composeRule.onNodeWithContentDescription("URL").performTextInput("https://api.example.com")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Continue").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Continue").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("setup-scroll").performScrollToNode(hasText("Degraded above"))
        composeRule.waitForIdle()
        composeRule.captureScreenshot("template-02-budget-prefilled")
        assertTrue(
            "the template's 1.5s budget should be on the draft",
            composeRule.onAllNodesWithText("1500ms").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("1500").fetchSemanticsNodes().isNotEmpty(),
        )
    }

    /** Captures the redesigned control surface in both schemes, for review. */
    @Test
    fun captureTheControlSurface() {
        seed(theme = ThemeChoice.DARK)
        launch()
        composeRule.captureScreenshot("ui-01-dark-collapsed")
        openTunePanel()
        composeRule.captureScreenshot("ui-02-dark-tune-panel")
        composeRule.onNodeWithText("Problems").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Close panel").performClick()
        composeRule.waitForIdle()
        composeRule.captureScreenshot("ui-03-dark-narrowing-strip")
        composeRule.onNodeWithText("Show all").performClick()
        composeRule.waitForIdle()
        openSearchPanel()
        composeRule.captureScreenshot("ui-04-dark-search-panel")
    }

    @Test
    fun captureTheControlSurfaceLight() {
        seed(theme = ThemeChoice.LIGHT)
        launch()
        composeRule.captureScreenshot("ui-05-light-collapsed")
        openTunePanel()
        composeRule.captureScreenshot("ui-06-light-tune-panel")
    }

    // ---- drag to reorder ---------------------------------------------------

    /** Ids in stored order — the order "My order" shows and dragging rewrites. */
    private fun storedOrder(): List<String> =
        runBlocking { Nightbell.require().store.currentSnapshot().monitors.map { it.id } }

    /**
     * Scroll until a monitor's grip is genuinely on screen, and hand it back.
     *
     * Scrolling to the *card* is not enough: the grip lives in the card's bottom
     * action row, so a card whose top is visible can still have its handle below the
     * fold — and injected touches at a position outside the viewport hit nothing at
     * all, silently.
     */
    /**
     * Grid index of the first monitor card with no panel open.
     *
     * Only the header and the fleet banner precede it now — the filter/sort controls
     * live behind header buttons rather than in a permanent card.
     */
    private val firstCardIndex = 2

    private fun handleFor(name: String, scrollToIndex: Int? = null): SemanticsNodeInteraction {
        if (scrollToIndex != null) {
            // Pin the card to the top of the viewport so the card *below* it is
            // composed too. A lazy grid only lays out what is visible, and
            // performScrollToNode scrolls the minimum needed — which leaves the
            // dragged card at the bottom edge with no drop target beneath it. A real
            // finger gets past that because edge auto-scroll runs between frames;
            // injected touches all arrive inside a few milliseconds with no frames in
            // between, so the test has to set the stage instead.
            composeRule.onNodeWithTag("dashboard-list").performScrollToIndex(scrollToIndex)
            composeRule.waitForIdle()
        }
        composeRule.onNodeWithTag("dashboard-list")
            .performScrollToNode(hasContentDescription("Reorder $name"))
        composeRule.waitForIdle()
        // Unmerged, and this matters more than it looks.
        //
        // The card's combinedClickable sets mergeDescendants, so in the merged tree
        // the grip's own contentDescription is folded into the card's node. Selecting
        // it merged therefore returns the *card*, and injecting a touch at that node's
        // centre lands in the middle of the card — nowhere near the handle, where it
        // is swallowed by the card's click and the grid's scroll. The gesture looks
        // broken when only the selector is.
        return composeRule.onNode(hasContentDescription("Reorder $name"), useUnmergedTree = true)
            .also { it.assertIsDisplayed() }
    }

    /**
     * Drag a grip far enough to matter.
     *
     * A generous fixed distance in small steps, rather than one card height: the drop
     * target is recomputed per movement exactly as it is for a real finger, and a
     * distance measured off the wrong node is how the first version of this test
     * passed a 23 dp "card height" and moved nothing.
     */
    private fun SemanticsNodeInteraction.dragDown(pixels: Float) {
        performTouchInput {
            down(center)
            val step = pixels / 20f
            repeat(20) { moveBy(Offset(0f, step)) }
            up()
        }
        composeRule.waitForIdle()
    }

    private fun openTunePanel() {
        composeRule.onNodeWithContentDescription("Filter and sort").performClick()
        composeRule.waitForIdle()
    }

    private fun openSearchPanel() {
        composeRule.onNodeWithContentDescription("Search monitors").performClick()
        composeRule.waitForIdle()
    }

    private fun switchToMyOrder() {
        openTunePanel()
        composeRule.onNodeWithText("My order").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Close panel").performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun theDragHandleOnlyExistsInMyOrderWithNothingHidden() {
        seed()
        launch()
        // Worst-first: no handles at all, because the next check would re-sort.
        assertEquals(
            0,
            composeRule.onAllNodes(
                hasContentDescription("Reorder Checkout API"),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().size,
        )

        switchToMyOrder()
        handleFor("Checkout API")
        composeRule.captureScreenshot("reorder-01-handles-visible")

        // Filtering hides monitors, so dropping between two visible ones would be
        // ambiguous — the handles go away rather than guessing.
        openTunePanel()
        composeRule.onNodeWithText("Problems").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Close panel").performClick()
        composeRule.waitForIdle()
        assertEquals(
            0,
            composeRule.onAllNodes(
                hasContentDescription("Reorder Marketing site"),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().size,
        )
        composeRule.captureScreenshot("reorder-02-hidden-while-filtered")
    }

    @Test
    fun theHandleDoesNotCostTheCardItsActions() {
        // Regression guard. The grip first went into the action row, whose 48 dp of
        // extra width silently pushed the re-check button past the card's edge — the
        // per-card actions were simply gone in the one mode that shows the handle.
        seed()
        launch()
        switchToMyOrder()
        handleFor("Checkout API", scrollToIndex = firstCardIndex)
        // Every visible card has its own pair, so scope to the top one — the only
        // card guaranteed to be fully on screen after scrolling to it. A button
        // pushed off the card's edge fails assertIsDisplayed even though its node
        // still exists, which is exactly the failure being guarded against.
        composeRule.onAllNodesWithContentDescription("Check now")[0].assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription("Pause monitor")[0].assertIsDisplayed()
        // And it must sit inside the screen, not merely exist somewhere to the right.
        val screenWidth = NightbellTestSupport.appContext.resources.displayMetrics.widthPixels /
            NightbellTestSupport.appContext.resources.displayMetrics.density
        val right = composeRule.onAllNodesWithContentDescription("Check now")[0]
            .getUnclippedBoundsInRoot().right.value
        assertTrue("re-check button runs off the screen at ${right}dp", right <= screenWidth)
        composeRule.captureScreenshot("reorder-06-actions-intact")
    }

    @Test
    fun draggingACardDownRewritesTheStoredOrder() {
        seed()
        launch()
        val before = storedOrder()
        assertEquals(listOf("m0", "m1", "m2", "m3", "m4", "m5"), before)

        switchToMyOrder()
        val handle = handleFor("Checkout API", scrollToIndex = firstCardIndex)
        composeRule.captureScreenshot("reorder-03-before-drag")

        // Drag the first card's grip past its neighbour.
        handle.dragDown(700f)

        NightbellTestSupport.awaitTrue(description = "store order changed") {
            storedOrder() != before
        }
        val after = storedOrder()
        composeRule.captureScreenshot("reorder-04-after-drag")

        // Same monitors, none lost or duplicated, and the dragged one moved later.
        assertEquals(before.sorted(), after.sorted())
        assertTrue(
            "m0 should have moved later, order is $after",
            after.indexOf("m0") > 0,
        )
    }

    @Test
    fun aDraggedOrderSurvivesARestart() {
        seed()
        launch()
        switchToMyOrder()
        handleFor("Checkout API", scrollToIndex = firstCardIndex).dragDown(700f)
        NightbellTestSupport.awaitTrue(description = "order committed") {
            storedOrder() != listOf("m0", "m1", "m2", "m3", "m4", "m5")
        }
        val committed = storedOrder()

        // Relaunch: manual order is persisted state, not a view-model detail.
        scenario?.close()
        launch()
        assertEquals(committed, storedOrder())
        // And the sort choice persisted with it, so the arrangement is still shown.
        assertEquals(
            me.river.pulse.domain.MonitorQuery.Sort.MANUAL,
            runBlocking { Nightbell.require().store.currentSnapshot().settings.dashboardSort },
        )
        composeRule.captureScreenshot("reorder-05-after-restart")
    }

    @Test
    fun theHandleOffersMoveUpAndDownForScreenReaders() {
        // A drag gesture is unusable with TalkBack, so the same capability has to
        // exist as a custom action. This asserts the actions are actually attached
        // and that invoking one really moves the monitor.
        seed()
        launch()
        switchToMyOrder()
        val node = handleFor("Marketing site").fetchSemanticsNode()
        val actions = node.config.getOrNull(SemanticsActions.CustomActions).orEmpty()
        val labels = actions.map { it.label }
        assertTrue("expected move actions, got $labels", labels.contains("Move up"))
        assertTrue("expected move actions, got $labels", labels.contains("Move down"))

        val before = storedOrder()
        composeRule.runOnUiThread { actions.first { it.label == "Move up" }.action() }
        NightbellTestSupport.awaitTrue(description = "moved up") { storedOrder() != before }
        val after = storedOrder()
        assertEquals(before.sorted(), after.sorted())
        assertEquals("m1", after.first())
    }

    // ---- touch targets -----------------------------------------------------

    @Test
    fun theCardActionButtonsMeetTheTouchFloor() {
        seed()
        launch()
        // Measured off the laid-out tree rather than trusted from the source, in dp,
        // which is the unit the guideline is written in.
        listOf("Check now", "Pause monitor", "Settings").forEach { label ->
            // Unclipped: getBoundsInRoot() is intersected with the viewport, so a
            // control half-scrolled off the bottom measures half its real height.
            // The guideline is about the laid-out target, not the visible sliver.
            val bounds = composeRule.onAllNodesWithContentDescription(label)[0]
                .getUnclippedBoundsInRoot()
            val widthDp = (bounds.right - bounds.left).value
            val heightDp = (bounds.bottom - bounds.top).value
            assertTrue(
                "$label is ${widthDp}x${heightDp} dp, below the 48 dp floor",
                widthDp >= 47.5f && heightDp >= 47.5f,
            )
        }
    }
}
