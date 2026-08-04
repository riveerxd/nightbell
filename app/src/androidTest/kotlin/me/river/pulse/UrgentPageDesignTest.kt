package me.river.pulse

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.river.pulse.PulseTestSupport.captureScreenshot
import me.river.pulse.ui.theme.PulseTheme
import me.river.pulse.ui.urgent.UrgentAlertScreen
import me.river.pulse.ui.urgent.UrgentAlertUi
import me.river.pulse.ui.urgent.UrgentAlertVariant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Not an assertion suite — renders each candidate URGENT page and writes a PNG,
 * so the five designs can be compared side by side before one is chosen.
 *
 * `animate = false` throughout: the pages loop forever by design, and an
 * infinite animation never lets the Compose test clock idle.
 */
@RunWith(AndroidJUnit4::class)
class UrgentPageDesignTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val sample = UrgentAlertUi(
        monitorName = "Checkout API",
        url = "https://api.river.com/v1/health",
        headline = "Connection refused",
        detail = "Connect to api.river.com:443 failed after 10.0s\n" +
            "java.net.ConnectException: failed to connect\nLast success 00:41 · 4 attempts since",
        statusCode = 0,
        lastLatencyMs = 10_004,
        downForMs = 22 * 60_000L + 14_000L,
        failedChecks = 4,
        reminderNumber = 3,
        repeatMinutes = 5,
    )

    private fun capture(variant: UrgentAlertVariant, name: String) {
        composeRule.setContent {
            PulseTheme(motionIntensity = 0f) {
                UrgentAlertScreen(
                    variant = variant,
                    ui = sample,
                    onAcknowledge = {},
                    onOpen = {},
                    onRecheck = {},
                    modifier = Modifier.fillMaxSize(),
                    animate = false,
                )
            }
        }
        composeRule.captureScreenshot(name)
    }

    @Test
    fun klaxon() = capture(UrgentAlertVariant.KLAXON, "urgent-1-klaxon")

    @Test
    fun call() = capture(UrgentAlertVariant.CALL, "urgent-2-call")

    @Test
    fun incident() = capture(UrgentAlertVariant.INCIDENT, "urgent-3-incident")

    @Test
    fun beacon() = capture(UrgentAlertVariant.BEACON, "urgent-4-beacon")

    @Test
    fun brief() = capture(UrgentAlertVariant.BRIEF, "urgent-5-brief")
}
