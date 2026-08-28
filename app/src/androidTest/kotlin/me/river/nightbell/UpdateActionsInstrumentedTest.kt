package me.river.nightbell

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.river.nightbell.data.update.UpdateInstaller
import me.river.nightbell.ui.theme.NightbellTheme
import me.river.nightbell.ui.update.UpdateActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The update offer in each state it can be in.
 *
 * Rendered directly rather than through the dashboard, because half of these
 * states cannot be produced from a running app on demand: a transfer at 40 per
 * cent lasts a fraction of a second over loopback, and the missing-permission
 * state is an app op that cannot be changed from inside the process it applies
 * to without the platform restarting it.
 *
 * [UpdateInstallInstrumentedTest] covers the same component wired to the real
 * installer and a real download; this covers what it looks like meanwhile.
 */
@RunWith(AndroidJUnit4::class)
class UpdateActionsInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun render(
        stage: UpdateInstaller.Stage,
        canRequestInstall: Boolean = true,
        apkUrl: String = "https://example.com/nightbell-9.0.0.apk",
        onWhatsNew: (String) -> Unit = {},
        onInstall: () -> Unit = {},
        onOpenInstallSettings: () -> Unit = {},
    ) {
        composeRule.setContent {
            NightbellTheme(motionIntensity = 0f) {
                UpdateActions(
                    version = "9.0.0",
                    releaseUrl = "https://example.com/releases/9.0.0",
                    apkUrl = apkUrl,
                    stage = stage,
                    canRequestInstall = canRequestInstall,
                    onWhatsNew = onWhatsNew,
                    onInstall = onInstall,
                    onOpenInstallSettings = onOpenInstallSettings,
                )
            }
        }
    }

    @Test
    fun both_offers_are_present_when_there_is_an_apk_and_the_grant() {
        render(UpdateInstaller.Stage.Idle)
        composeRule.onNodeWithTag("update-whats-new").assertIsDisplayed()
        composeRule.onNodeWithTag("update-install").assertIsDisplayed()
    }

    /**
     * A source with no APK still gets the notes.
     *
     * F-Droid before the version code is readable, or a tag published with
     * nothing attached. The button is absent rather than present and broken.
     */
    @Test
    fun a_release_with_no_apk_offers_only_the_notes() {
        render(UpdateInstaller.Stage.Idle, apkUrl = "")
        composeRule.onNodeWithTag("update-whats-new").assertIsDisplayed()
        assertTrue(
            composeRule.onAllNodesWithTag("update-install").fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun without_the_grant_the_offer_is_the_round_trip_and_says_why() {
        render(UpdateInstaller.Stage.Idle, canRequestInstall = false)
        composeRule.onNodeWithTag("update-install-settings").assertIsDisplayed()
        assertTrue(
            "an Install button here would open Settings instead of installing",
            composeRule.onAllNodesWithTag("update-install").fetchSemanticsNodes().isEmpty(),
        )
        composeRule.onNodeWithText("allow Nightbell to install apps", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun a_running_transfer_reports_both_numbers() {
        render(UpdateInstaller.Stage.Downloading(received = 6_200_000, total = 15_400_000))
        composeRule.onNodeWithTag("update-progress").assertIsDisplayed()
        composeRule.onNodeWithText("6.2 MB of 15.4 MB").assertIsDisplayed()
        // The label under the finger does not change while the numbers move.
        composeRule.onNodeWithText("Downloading").assertIsDisplayed()
    }

    @Test
    fun a_transfer_with_no_length_reports_what_has_arrived_and_claims_nothing_else() {
        render(UpdateInstaller.Stage.Downloading(received = 2_000_000, total = 0))
        composeRule.onNodeWithText("2.0 MB").assertIsDisplayed()
    }

    @Test
    fun a_failure_says_what_went_wrong_where_the_button_was() {
        render(UpdateInstaller.Stage.Failed("The download arrived empty. Try again."))
        composeRule.onNodeWithTag("update-failure").assertIsDisplayed()
        composeRule.onNodeWithText("The download arrived empty. Try again.").assertIsDisplayed()
        // And the offer is still there, because the answer to a failed download
        // is to try it again rather than to go and find a browser.
        composeRule.onNodeWithTag("update-install").assertIsDisplayed()
    }

    @Test
    fun the_notes_button_opens_the_release_page() {
        var opened = ""
        render(UpdateInstaller.Stage.Idle, onWhatsNew = { opened = it })
        composeRule.onNodeWithTag("update-whats-new").performClick()
        assertEquals("https://example.com/releases/9.0.0", opened)
    }

    @Test
    fun the_install_button_is_inert_while_a_transfer_is_running() {
        var taps = 0
        render(UpdateInstaller.Stage.Downloading(1, 10), onInstall = { taps++ })
        composeRule.onNodeWithTag("update-install").performClick()
        assertEquals("a second tap must not start a second download", 0, taps)
    }
}
