package me.river.nightbell

import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.river.nightbell.NightbellTestSupport.appContext
import me.river.nightbell.NightbellTestSupport.captureScreenshot
import me.river.nightbell.data.Nightbell
import me.river.nightbell.domain.CheckResult
import me.river.nightbell.domain.FailureKind
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The full-screen page a locked phone gets, opened with the intent the
 * notification actually carries.
 *
 * That intent used to hold the name, the address and the repeat interval and
 * nothing else, so the page fell back to its defaults on every real alarm:
 * "Not responding", one failed check, HTTP none, no latency. Only the
 * screenshot harness, which builds its own intent, ever showed the real values.
 */
@RunWith(AndroidJUnit4::class)
class UrgentPageIntentInstrumentedTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private var scenario: ActivityScenario<UrgentAlertActivity>? = null

    @After
    fun tearDown() {
        scenario?.close()
    }

    private val kuma = Monitor(
        id = "kuma",
        name = "Uptime Kuma",
        kind = MonitorKind.PROMETHEUS,
        url = "https://kuma.example.net/metrics",
    )

    // A Kuma monitor down: Kuma itself answered, fast and with a 200, and the
    // value it read back is what failed.
    private val result = CheckResult(
        ok = false,
        latencyMs = 61,
        statusCode = 200,
        failureKind = FailureKind.UNKNOWN,
        message = "monitor_status{monitor_name=\"API\"} = 0, expected != 0",
        at = 0L,
    )

    @Test
    fun thePageALockedPhoneOpensShowsWhatTheCheckSaw() {
        val intent = Nightbell.install(appContext).alerts.urgentPageIntent(
            monitor = kuma,
            result = result,
            downForMs = 125_000L,
            failedChecks = 3,
        )
        assertEquals(200, intent.getIntExtra(UrgentAlertActivity.EXTRA_STATUS_CODE, -1))
        assertEquals(61L, intent.getLongExtra(UrgentAlertActivity.EXTRA_LATENCY_MS, -1L))
        assertEquals(3, intent.getIntExtra(UrgentAlertActivity.EXTRA_FAILED_CHECKS, -1))

        // The flags start a new task for a notification; a test scenario wants
        // the activity in its own.
        scenario = ActivityScenario.launch(Intent(intent).setFlags(0))
        composeRule.waitForIdle()
        composeRule.onNodeWithText(result.message).assertExists()
        composeRule.onNodeWithText("200").assertExists()
        composeRule.onNodeWithText("61 ms").assertExists()
        composeRule.onNodeWithText("3 failed").assertExists()
        composeRule.onNodeWithText("2m 5s").assertExists()
        composeRule.captureScreenshot("urgent-page-from-real-intent")
    }
}
