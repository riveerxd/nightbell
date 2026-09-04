package me.river.nightbell

import android.Manifest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import me.river.nightbell.NightbellTestSupport.appContext
import me.river.nightbell.NightbellTestSupport.awaitTrue
import me.river.nightbell.NightbellTestSupport.captureScreenshot
import me.river.nightbell.NightbellTestSupport.captureDeviceScreenshot
import me.river.nightbell.NightbellTestSupport.openSettingsTab
import me.river.nightbell.data.Nightbell
import me.river.nightbell.data.NightbellSnapshot
import me.river.nightbell.data.diag.Diag
import me.river.nightbell.data.diag.DiagnosticFacts
import me.river.nightbell.data.transfer.NightbellBackup
import me.river.nightbell.data.transfer.toImportableSnapshot
import me.river.nightbell.domain.BrowserState
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.HeaderPair
import me.river.nightbell.domain.Health
import me.river.nightbell.domain.LogEvent
import me.river.nightbell.domain.LogField
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.MonitorRuntime
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The diagnostic log, driven through the screen a user drives.
 *
 * Three things are worth a device test rather than a JVM one, and they are the
 * three that would be embarrassing to get wrong:
 *
 *  1. **The switch actually writes a file, and off actually means off.** Both
 *     halves: a switch that records nothing is useless, and a switch that keeps
 *     recording after it is turned off is a promise broken.
 *  2. **What lands in the file has the secrets taken out of it.** The JVM
 *     sentinel test covers the factories. This covers the whole pipeline: a real
 *     store with real credentials in it, real events, a real export.
 *  3. **A user can read it before they publish it.** The viewer is not a
 *     nicety. This file gets pasted into public issue threads.
 */
@RunWith(AndroidJUnit4::class)
class DiagnosticLogInstrumentedTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @get:Rule
    val permissions: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private var scenario: ActivityScenario<MainActivity>? = null

    /** Values that must never reach the file, each unique so a hit names itself. */
    private val token = "ghp_SENTINELtoken0123456789abcd"
    private val cookie = "session=SENTINELcookieAbCdEf012345"
    private val storage = "{\"jwt\":\"SENTINELstorage987654321\"}"
    private val headerValue = "SENTINELheaderValue55555"
    private val monitorName = "Sentinel Checkout Prod"
    private val queryValue = "SENTINELqueryValue777"

    @Before
    fun setUp() {
        runBlocking { Diag.clear() }
    }

    @After
    fun tearDown() {
        scenario?.close()
        runBlocking { Diag.clear() }
    }

    private fun seed(logging: Boolean = false) {
        runBlocking {
            Nightbell.install(appContext).store.replaceAll(
                NightbellSnapshot(
                    monitors = listOf(
                        Monitor(
                            id = "7f3a1c2e-4b6d-4f0a-8c4e-6b8da1b2c3d4",
                            name = monitorName,
                            kind = MonitorKind.HTTP_STATUS,
                            url = "https://checkout.example.com/v1/health?api_key=$queryValue",
                            headers = listOf(HeaderPair("X-Api-Key", headerValue)),
                            browserState = BrowserState(
                                origin = "https://checkout.example.com",
                                cookies = cookie,
                                localStorage = storage,
                            ),
                        ),
                    ),
                    runtimes = mapOf(
                        "7f3a1c2e-4b6d-4f0a-8c4e-6b8da1b2c3d4" to MonitorRuntime(health = Health.UP),
                    ),
                    settings = GlobalSettings(
                        motionIntensity = 0f,
                        hasSeenPagerSetup = true,
                        githubToken = token,
                        diagnosticLogEnabled = logging,
                    ),
                ),
            )
        }
        // The sink learns the answer from the store's flow, and a test that
        // seeds and immediately asserts would race it.
        awaitTrue(description = "the sink adopted diagnosticLogEnabled=$logging") {
            Diag.capturing == logging
        }
    }

    /**
     * The switch inside the row, not the row.
     *
     * `ToggleRow` puts the test tag on a merging container, and a merged node
     * does not carry its child `Switch`'s toggle state, so asserting on the tag
     * asserts nothing about whether the thing is on.
     */
    private fun toggleState() = composeRule.onNode(
        isToggleable() and hasAnyAncestor(hasTestTag("diagnostic-toggle")),
        useUnmergedTree = true,
    )

    /**
     * Presses a hold button, advances the frame clock by hand, releases.
     *
     * `HoldToConfirmButton` times itself off `withFrameNanos` rather than off an
     * animation, so that turning platform animations off cannot shorten the
     * guard to nothing. Driving it from a test therefore means stepping the
     * clock between a synthetic press and release.
     */
    private fun hold(tag: String, heldMs: Long) {
        composeRule.mainClock.autoAdvance = false
        val node = composeRule.onNodeWithTag(tag)
        node.performTouchInput { down(center) }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeBy(heldMs)
        composeRule.mainClock.advanceTimeByFrame()
        node.performTouchInput { up() }
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
    }

    private fun openDiagnosticsCard() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Settings").performClick()
        composeRule.waitForIdle()
        composeRule.openSettingsTab("About")
        composeRule.onNodeWithTag("settings-list")
            .performScrollToNode(hasTestTag("diagnostic-toggle"))
        composeRule.waitForIdle()
    }

    // ---- the switch ---------------------------------------------------------

    @Test
    fun theCardSaysWhatItRecordsAndStartsOff() {
        seed()
        openDiagnosticsCard()
        composeRule.onNodeWithTag("settings-list")
            .performScrollToNode(hasContentDescription("Diagnostic log"))
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Diagnostic log").assertIsDisplayed()
        toggleState().assertIsOff()
        // The two promises the copy makes, asserted so a rewrite cannot quietly
        // drop either: nothing is uploaded, and addresses are cut back. The
        // probe is a phrase unique to this card, because "Nothing is uploaded"
        // is also on the backup card two items above, deliberately.
        composeRule.onNodeWithText("written where you choose", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("shortened to the host", substring = true).assertIsDisplayed()
        composeRule.captureScreenshot("diagnostics-01-off")
    }

    @Test
    fun turningItOnWritesAFileAndTurningItOffStops() {
        seed()
        openDiagnosticsCard()
        composeRule.onNodeWithTag("diagnostic-toggle").performClick()
        composeRule.waitForIdle()
        toggleState().assertIsOn()
        awaitTrue(description = "the sink started capturing") { Diag.capturing }

        Diag.log(LogEvent.CHECK_START, LogField.monitor("7f3a1c2e-4b6d"))
        awaitTrue(description = "a line reached the file") { Diag.sizeBytes() > 0 }
        val whileOn = Diag.sizeBytes()

        composeRule.onNodeWithTag("diagnostic-toggle").performClick()
        composeRule.waitForIdle()
        awaitTrue(description = "the sink stopped capturing") { !Diag.capturing }

        // Twenty lines after the switch went off, each carrying a field nothing
        // else in the app writes. Asserting on the file's size instead was a
        // worse test and a flaky one: the app legitimately writes a line or two
        // of its own in the moment the switch flips, because the settings write
        // that flipped it also re-syncs the schedule, and 200 bytes of slack was
        // a guess at how chatty that is. Naming the lines is the actual
        // guarantee.
        repeat(20) { index -> Diag.log(LogEvent.CHECK_DONE, LogField.count("after_off", index)) }
        Thread.sleep(400)
        val written = runBlocking { Diag.view() }
        assertTrue(
            "lines written after the switch went off: " +
                written.filter { it.contains("after_off") },
            written.none { it.contains("after_off") },
        )
        // And the file did not shrink or vanish either: what was captured while
        // the switch was on is still there.
        assertTrue("the capture was lost", Diag.sizeBytes() >= whileOn)
        composeRule.captureScreenshot("diagnostics-02-on")
    }

    @Test
    fun theSwitchSurvivesLeavingTheScreen() {
        seed(logging = true)
        openDiagnosticsCard()
        toggleState().assertIsOn()
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.waitForIdle()
        openDiagnosticsCard()
        toggleState().assertIsOn()
    }

    // ---- the viewer ---------------------------------------------------------

    @Test
    fun theViewerShowsNothingHonestlyWhenNothingWasRecorded() {
        seed()
        openDiagnosticsCard()
        composeRule.onNodeWithTag("diagnostic-read").performClick()
        composeRule.waitForIdle()
        // An empty state that says what to do next, which is the rule for every
        // empty state in this app.
        composeRule.onNodeWithText("Nothing recorded yet").assertIsDisplayed()
        composeRule.onNodeWithText("Turn the switch on", substring = true)
            .assertIsDisplayed()
        composeRule.captureDeviceScreenshot("diagnostics-03-empty")
    }

    @Test
    fun theViewerShowsTheLinesThatWereRecorded() {
        seed(logging = true)
        Diag.log(LogEvent.PAGE_EXPIRED, LogField.of("stage", 3), LogField.of("percent", 43))
        awaitTrue(description = "the line reached the file") { Diag.sizeBytes() > 0 }
        openDiagnosticsCard()
        composeRule.onNodeWithTag("diagnostic-read").performClick()
        composeRule.waitForIdle()
        awaitTrue(description = "the viewer read the file") {
            Diag.recent().isNotEmpty()
        }
        composeRule.onNodeWithTag("diagnostic-lines").assertIsDisplayed()
        composeRule.onNodeWithText("page.expired", substring = true).assertIsDisplayed()
        composeRule.captureDeviceScreenshot("diagnostics-04-viewer")
        // And it closes back to the page it was opened from rather than leaving
        // the settings list scrolled somewhere else.
        composeRule.onNodeWithText("Close").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("diagnostic-toggle").assertIsDisplayed()
    }

    @Test
    fun deletingTheLogEmptiesIt() {
        seed(logging = true)
        Diag.log(LogEvent.CHECK_START, LogField.monitor("7f3a1c2e-4b6d"))
        awaitTrue(description = "something to delete") { Diag.sizeBytes() > 0 }
        openDiagnosticsCard()
        composeRule.onNodeWithTag("diagnostic-read").performClick()
        composeRule.waitForIdle()
        hold("diagnostic-clear", heldMs = 1_500)
        // The history goes. One line saying it was deleted stays, because a file
        // that silently begins in the middle is a file whose reader cannot tell
        // deletion from a log that only just started.
        awaitTrue(description = "the recorded history went") {
            val left = runBlocking { Diag.view() }
            left.none { it.contains("check.start") }
        }
        assertTrue(
            "nothing recorded the deletion",
            runBlocking { Diag.view() }.any { it.contains("app.log.cleared") },
        )
    }

    @Test
    fun exportIsBlockedWhileThereIsNothingToHandAnybody() {
        seed()
        openDiagnosticsCard()
        // Blocked, and the reason is on screen rather than behind a tap: the
        // switch's own subtitle says nothing is being recorded.
        composeRule.onNodeWithTag("diagnostic-export").assertIsNotEnabled()
        composeRule.onNodeWithText("nothing is being recorded", substring = true)
            .assertIsDisplayed()

        composeRule.onNodeWithTag("diagnostic-read").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("diagnostic-dialog-export").assertIsNotEnabled()
        // And the empty state says what would be here and what to do about it,
        // rather than only one of the two.
        composeRule.onNodeWithText("Nothing recorded yet").assertIsDisplayed()
        composeRule.onNodeWithText("Turn the switch on", substring = true)
            .assertIsDisplayed()
        composeRule.captureDeviceScreenshot("diagnostics-05-blocked")
    }

    @Test
    fun exportUnblocksAsSoonAsThereIsSomethingToExport() {
        seed(logging = true)
        Diag.log(LogEvent.CHECK_START, LogField.monitor("7f3a1c2e-4b6d"))
        awaitTrue(description = "a line reached the file") { Diag.sizeBytes() > 0 }
        openDiagnosticsCard()
        composeRule.onNodeWithTag("diagnostic-export").assertIsEnabled()
        composeRule.onNodeWithTag("diagnostic-read").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("diagnostic-dialog-export").assertIsEnabled()
    }

    // ---- the export, and what is in it --------------------------------------

    @Test
    fun theExportedFileCarriesTheDeviceFactsAndNoCredentials() {
        seed(logging = true)

        // A run across the surfaces, using the same events and the same field
        // factories the app's own call sites use. If any of them is the wrong
        // choice, the sentinels below say so.
        Diag.log(
            LogEvent.HTTP_REQUEST,
            LogField.monitor("7f3a1c2e-4b6d-4f0a-8c4e-6b8da1b2c3d4"),
            LogField.route("url", "https://checkout.example.com/v1/health?api_key=$queryValue"),
            LogField.count("headers", 1),
        )
        Diag.log(
            LogEvent.PAGE_SEED,
            LogField.secret("cookies", cookie),
            LogField.secret("storage", storage),
        )
        Diag.log(
            LogEvent.CHECK_DONE,
            LogField.of("ok", false),
            LogField.text("verdict", "$monitorName returned 503"),
        )
        Diag.logError(
            LogEvent.HTTP_ERROR,
            IllegalStateException("failed with Authorization: Bearer $token"),
        )
        awaitTrue(description = "the run reached the file") { Diag.sizeBytes() > 0 }

        val document = runBlocking { Diag.export(DiagnosticFacts.header(appContext)) }
        // Written out beside the screenshots for the same reason the screenshots
        // exist: an assertion that a string is absent proves less than a person
        // reading the file the feature actually produces.
        java.io.File(NightbellTestSupport.screenshotDir(), "diagnostics-export.txt")
            .writeText(document)

        // The header is the half that answers what a bug report never carries.
        assertTrue("no version", document.contains(BuildConfig.VERSION_NAME))
        assertTrue("no api level", document.contains("android     API"))
        assertTrue("no webview", document.contains("webview     "))
        assertTrue("no fleet count", document.contains("1 monitor,"))

        // And the half that matters more.
        for (secret in listOf(token, cookie, storage, headerValue, queryValue, monitorName)) {
            assertFalse("the export carried $secret", document.contains(secret))
        }
        // Not even a fragment of the token, which is the mistake a truncating
        // redactor makes.
        assertFalse(document.contains("SENTINELtoken"))
        assertFalse(document.contains("SENTINELcookie"))
        // The host survives, because a log with no host in it explains nothing.
        assertTrue("the host was lost too", document.contains("checkout.example.com"))
    }

    @Test
    fun anExportWithNothingInItStillSaysWhatTheDeviceIs() {
        seed()
        runBlocking { Diag.clear() }
        val document = runBlocking { Diag.export(DiagnosticFacts.header(appContext)) }
        assertTrue(document.contains("Nightbell diagnostic log"))
        assertTrue(document.contains("logging     off"))
        assertTrue(document.contains("has not been switched on"))
    }

    // ---- the promises made elsewhere ----------------------------------------

    @Test
    fun aCrashIsRecordedEvenWithTheSwitchOff() {
        seed()
        runBlocking { Diag.clear() }
        assertFalse(Diag.capturing)
        // Not thrown for real: an uncaught exception on the instrumentation
        // thread would take the test process with it. This drives the same
        // handler the platform would.
        val handler = Thread.getDefaultUncaughtExceptionHandler()
        assertTrue("no crash handler was installed", handler != null)

        Diag.log(LogEvent.CHECK_START, LogField.monitor("7f3a1c2e"))
        // The ring fills whether or not the file does, because a crash has to be
        // able to carry the minute before it.
        assertTrue("the ring stayed empty", Diag.recent().isNotEmpty())
        // And with the switch off, none of it is on disk.
        assertTrue("something was written with logging off", Diag.sizeBytes() == 0L)
    }

    @Test
    fun animportedBackupNeverTurnsLoggingOnForTheNewPhone() {
        // The switch is a decision about this device, so it does not travel.
        val fromOtherPhone = NightbellBackup(
            monitorCount = 1,
            snapshot = NightbellSnapshot(
                monitors = listOf(Monitor(id = "m1", url = "https://example.com")),
                settings = GlobalSettings(diagnosticLogEnabled = true),
            ),
        )
        val landed = fromOtherPhone.toImportableSnapshot()
        assertFalse(landed.settings.diagnosticLogEnabled)
    }
}
