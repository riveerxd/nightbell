package me.river.nightbell

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import me.river.nightbell.data.Nightbell
import me.river.nightbell.domain.Monitor
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What happens after a page is tapped in the shade.
 *
 * Reported against 3.5.0: the notification opens the monitor, and then the back
 * arrow on that screen does nothing. The three cases below are the three ways
 * the deep link can arrive, and each of them has to leave the dashboard sitting
 * behind the arrow, because that is the only place a monitor detail can go back
 * to and the user has no other way out of it.
 */
@RunWith(AndroidJUnit4::class)
class NotificationNavigationInstrumentedTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private var scenario: ActivityScenario<MainActivity>? = null
    private val context: Context get() = NightbellTestSupport.appContext
    private val graph get() = Nightbell.install(context)

    @Before
    fun setUp() {
        NightbellTestSupport.resetApp()
        runBlocking {
            graph.store.upsert(
                Monitor(id = "paged", name = "Paged Monitor", url = "https://paged.example"),
            )
            graph.store.upsert(
                Monitor(id = "other", name = "Other Monitor", url = "https://other.example"),
            )
        }
    }

    @After
    fun tearDown() {
        // `deliver` starts the activity through the platform rather than through
        // the scenario, so the scenario can be left holding an instance it is no
        // longer the owner of and its close times out waiting for DESTROYED.
        runCatching { scenario?.close() }
        scenario = null
    }

    /**
     * The shade's own intent, copied field for field from `AlertCenter`.
     *
     * Reproducing the flags matters: `CLEAR_TOP or SINGLE_TOP` on a `singleTop`
     * activity is what turns the second page into `onNewIntent` rather than a
     * fresh `onCreate`, and the two took different paths through the navigation.
     */
    private fun pageIntent(monitorId: String) = Intent(context, MainActivity::class.java).apply {
        action = Intent.ACTION_VIEW
        data = Uri.parse("nightbell://monitor/$monitorId")
        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        putExtra(MainActivity.EXTRA_MONITOR_ID, monitorId)
    }

    /**
     * Hands the intent to the running activity the way the shade does.
     *
     * `startActivity` from a non-activity context, which is what
     * `PendingIntent.send` amounts to, so the platform's own `singleTop` and
     * `CLEAR_TOP` handling decides what happens rather than the test calling
     * `onNewIntent` directly and pretending it was delivered.
     */
    private fun deliver(monitorId: String) {
        context.startActivity(pageIntent(monitorId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun awaitText(text: String) {
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun back() {
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun a_cold_page_leaves_the_dashboard_behind_the_back_arrow() {
        scenario = ActivityScenario.launch(pageIntent("paged"))
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Paged Monitor").assertIsDisplayed()

        back()
        composeRule.onNodeWithTag("dashboard-list").assertIsDisplayed()
    }

    @Test
    fun a_page_delivered_to_a_running_app_leaves_the_dashboard_behind_the_back_arrow() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("dashboard-list").assertIsDisplayed()

        deliver("paged")
        awaitText("Paged Monitor")

        back()
        composeRule.onNodeWithTag("dashboard-list").assertIsDisplayed()
    }

    /**
     * The same page tapped twice.
     *
     * Two notifications for one monitor is the normal case rather than an edge
     * one: a repeat page re-posts under the same id. Coming back to the dashboard
     * and tapping the shade again has to open the monitor a second time.
     */
    @Test
    fun the_same_page_tapped_again_opens_the_monitor_again() {
        scenario = ActivityScenario.launch(pageIntent("paged"))
        composeRule.waitForIdle()
        back()
        composeRule.onNodeWithTag("dashboard-list").assertIsDisplayed()

        deliver("paged")
        awaitText("Paged Monitor")
    }

    /**
     * Two different monitors paging one after the other must not stack two detail
     * screens, or the arrow walks back through an outage history nobody asked for
     * instead of returning to the fleet.
     */
    @Test
    fun a_second_page_replaces_the_first_rather_than_stacking_on_it() {
        scenario = ActivityScenario.launch(pageIntent("paged"))
        composeRule.waitForIdle()

        deliver("other")
        awaitText("Other Monitor")

        back()
        composeRule.onNodeWithTag("dashboard-list").assertIsDisplayed()
    }
}
