package me.river.nightbell

import android.Manifest
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import me.river.nightbell.NightbellTestSupport.appContext
import me.river.nightbell.NightbellTestSupport.captureDeviceScreenshot
import me.river.nightbell.NightbellTestSupport.openSettingsTab
import me.river.nightbell.data.Nightbell
import me.river.nightbell.data.NightbellSnapshot
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.Health
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.MonitorRuntime
import me.river.nightbell.domain.Sample
import me.river.nightbell.domain.ThemeChoice
import me.river.nightbell.domain.UpdateState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals

/**
 * The update banner on the dashboard, and getting rid of it.
 *
 * [AppUpdateTest] owns every rule about when it should appear, so nothing here
 * re-tests those. What is left is the part only a running app can answer: that the
 * banner is wired to the store at all, that dismissing it writes something that
 * survives, and that Settings can take the dismissal back.
 *
 * `UpdateState` is seeded straight into the store rather than fetched. A live
 * check would spend the anonymous GitHub budget, which is 60 an hour for the whole
 * device and shared with every repository monitor, so a test that hits it fails
 * for the next person rather than for itself.
 */
@RunWith(AndroidJUnit4::class)
class UpdateBannerInstrumentedTest {

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

    /** A version that is newer than whatever this build is, whatever it is. */
    private val newer: String
        get() {
            val core = BuildConfig.VERSION_NAME.trim().removePrefix("v")
                .takeWhile { it.isDigit() || it == '.' }
            val parts = core.split('.').mapNotNull { it.toIntOrNull() }
            val major = parts.getOrElse(0) { 0 }
            return "${major + 1}.0.0"
        }

    private fun seed(update: UpdateState, checksEnabled: Boolean = true) {
        runBlocking {
            Nightbell.install(appContext).store.replaceAll(
                NightbellSnapshot(
                    // One healthy monitor, so the dashboard is the real dashboard
                    // and not the empty state, which has its own layout.
                    monitors = listOf(
                        Monitor(
                            id = "site",
                            name = "Marketing site",
                            kind = MonitorKind.HTTP_STATUS,
                            url = "https://example.com",
                            createdAt = now,
                        ),
                    ),
                    runtimes = mapOf(
                        "site" to MonitorRuntime(
                            health = Health.UP,
                            lastCheckedAt = now - 60_000,
                            lastLatencyMs = 180,
                            lastCode = 200,
                            samples = List(6) {
                                Sample(at = now - (6 - it) * 900_000L, ok = true, latencyMs = 180, code = 200)
                            },
                        ),
                    ),
                    settings = GlobalSettings(
                        motionIntensity = 0f,
                        theme = ThemeChoice.DARK,
                        hasSeenPagerSetup = true,
                        updateChecksEnabled = checksEnabled,
                    ),
                    update = update,
                ),
            )
        }
    }

    private fun storedUpdate(): UpdateState = runBlocking {
        Nightbell.install(appContext).store.currentSnapshot().update
    }

    @Test
    fun aNewerVersionShowsABannerUnderTheFleetVerdict() {
        val version = newer
        seed(
            UpdateState(
                lastCheckedAt = now,
                latestVersion = version,
                latestUrl = "https://github.com/riveerxd/nightbell/releases/tag/v$version",
                // Already notified, which is the case 3.2.0 got wrong: the shade
                // was written to once and the app then went quiet forever.
                notifiedVersion = version,
            ),
        )
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Nightbell $version is available").assertExists()
        composeRule.onNodeWithText("What's new").assertExists()
        // The fleet verdict is still the first thing on the screen.
        composeRule.onNodeWithText("Marketing site").assertExists()
        composeRule.captureDeviceScreenshot("70-update-banner")
    }

    @Test
    fun theBannerIsAbsentWhenUpdateChecksAreOff() {
        val version = newer
        seed(
            UpdateState(lastCheckedAt = now, latestVersion = version),
            checksEnabled = false,
        )
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Nightbell $version is available").assertDoesNotExist()
    }

    /**
     * Closing the modal defers the version. It does not refuse it.
     *
     * The distinction is what lets this be a modal at all: every way out of a
     * dialog is easy to hit by accident, so none of them may cost anything. A
     * regression to `ignore` here would mean a stray tap on the scrim silences a
     * release for good, which is what the close button used to do when it sat a
     * few dp from the Settings gear.
     */
    @Test
    fun closingItDefersTheVersionRatherThanRefusingIt() {
        val version = newer
        seed(UpdateState(lastCheckedAt = now, latestVersion = version))
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Not now").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Nightbell $version is available").assertDoesNotExist()
        // Written through to the store, so it survives the process rather than
        // living in a composable's memory until the next launch.
        NightbellTestSupport.awaitTrue(description = "the deferral was persisted") {
            storedUpdate().remindAfter > now
        }
        assertEquals(
            "closing must not refuse the version",
            "",
            storedUpdate().ignoredVersion,
        )
    }

    @Test
    fun settingsCanTakeTheDismissalBack() {
        val version = newer
        seed(
            UpdateState(
                lastCheckedAt = now,
                latestVersion = version,
                ignoredVersion = version,
            ),
        )
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()

        // Dismissed, so nothing on the dashboard.
        composeRule.onNodeWithText("Nightbell $version is available").assertDoesNotExist()

        composeRule.onNodeWithContentDescription("Settings").performClick()
        composeRule.waitForIdle()
        // Scrolled to the button, not to the sentence above it. The sentence being
        // on screen does not put the button on screen, and a tap on a node outside
        // the viewport lands nowhere at all: the first version of this scrolled to
        // the text, clicked, and silently did nothing.
        composeRule.openSettingsTab("About")
        composeRule.onNodeWithTag("settings-list")
            .performScrollToNode(hasTestTag("unignore-update"))
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Not showing $version", substring = true).assertExists()
        composeRule.captureDeviceScreenshot("71-settings-update-ignored")

        composeRule.onNodeWithTag("unignore-update").performClick()
        composeRule.waitForIdle()
        NightbellTestSupport.awaitTrue(description = "the refusal was lifted") {
            storedUpdate().ignoredVersion.isEmpty()
        }

        // Back to the top before reaching for Back: the header is a LazyColumn item
        // and is recycled once the list has scrolled, which ScreenshotTest already
        // says out loud in a comment.
        composeRule.onNodeWithTag("settings-list").performScrollToIndex(0)
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.waitForIdle()
        // And it comes back, which is the whole reason the button exists.
        composeRule.onNodeWithText("Nightbell $version is available").assertExists()
        composeRule.captureDeviceScreenshot("72-update-banner-restored")
    }

    @Test
    fun theSettingsCardComparesInstalledAgainstLatest() {
        val version = newer
        // Deferred rather than merely known, so the dashboard notice stays down
        // and the Settings gear is reachable. The card itself is unaffected: it
        // reads `latestVersion`, which "remind later" does not touch. Without
        // this the notice covers the header, and the tap meant for Settings
        // lands on the notice's own close button.
        seed(
            UpdateState(
                lastCheckedAt = now,
                latestVersion = version,
                remindAfter = now + 60 * 60 * 1000,
            ),
        )
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Settings").performClick()
        composeRule.waitForIdle()
        composeRule.openSettingsTab("About")
        composeRule.onNodeWithTag("settings-list").performScrollToNode(hasText("Installed"))
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Installed").assertExists()
        composeRule.onNodeWithText(BuildConfig.VERSION_NAME).assertExists()
        composeRule.onNodeWithText("Latest").assertExists()
        composeRule.onNodeWithText(version).assertExists()
        composeRule.captureDeviceScreenshot("73-settings-update-card")
    }
}
