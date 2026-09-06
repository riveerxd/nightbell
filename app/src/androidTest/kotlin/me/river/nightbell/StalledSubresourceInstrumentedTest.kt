package me.river.nightbell

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.river.nightbell.data.check.ElementChecker
import me.river.nightbell.domain.ElementTarget
import me.river.nightbell.domain.FailureKind
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The end of issue 8, on a real WebView.
 *
 * The reporter's page stopped around 80 per cent with four addresses his
 * firewall would not resolve, and the verdict told him to raise a timeout he had
 * already taken to sixty seconds. Nothing in a JVM test can prove that shape is
 * what a real renderer produces, because the numbers in the verdict come from
 * the WebView's own callbacks.
 *
 * The fixture is that page with the network removed from the story. One script
 * comes from a host no resolver will answer for, which is the failed request.
 * A second script never answers at all, which is what keeps the load event from
 * ever arriving. Together they are a page that will still be loading tomorrow.
 */
@RunWith(AndroidJUnit4::class)
class StalledSubresourceInstrumentedTest {

    private lateinit var server: TinyHttpServer

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
            when {
                // Accepted, then held open past any timeout the monitor has. A
                // parser-blocking script that never answers is the cheapest way
                // to reproduce a load event that never arrives.
                request.path.startsWith("/never-answers.js") -> TinyHttpServer.Response(
                    body = "",
                    contentType = "application/javascript",
                    delayMs = 120_000,
                )

                else -> TinyHttpServer.Response(
                    body = stalling,
                    contentType = "text/html; charset=utf-8",
                )
            }
        }
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun stalledMonitor() = Monitor(
        id = "stalled",
        name = "Stalled shop",
        kind = MonitorKind.WEBSITE_ELEMENT,
        url = server.url("/"),
        timeoutSeconds = 15,
    ).withTargets(listOf(ElementTarget(elementId = "price")))

    @Test
    fun aPageHeldUpByAFailedRequestIsNotSentBackToTheTimeout() {
        val result = runBlocking { ElementChecker(NightbellTestSupport.appContext).check(stalledMonitor()) }

        assertFalse("the stalled page somehow passed: ${result.message}", result.ok)
        assertEquals(FailureKind.TIMEOUT, result.failureKind)

        val detail = result.detail
        assertTrue("the load event was not reported missing: $detail", detail.contains("never arrived"))
        assertTrue("no failed request was counted: $detail", detail.contains("of them failed"))
        assertTrue(
            "the verdict did not rule the timeout out: $detail",
            detail.contains("longer timeout will not help"),
        )
        assertFalse(
            "the verdict sent the reporter back to the timeout: $detail",
            detail.contains("Raising this monitor's timeout"),
        )
    }

    /**
     * The verdict travels as an alert body, which is cut at 320 characters, and
     * as six lines on the setup screen. The sentence that says a longer timeout
     * will not help is the whole point of the verdict, so it has to survive both
     * cuts rather than being the part that falls off the end.
     */
    @Test
    fun theAdviceSurvivesTheAlertBodysBudget() {
        val result = runBlocking { ElementChecker(NightbellTestSupport.appContext).check(stalledMonitor()) }
        val carried = result.detail.take(320)
        assertTrue(
            "the advice was cut off in the alert body, which is where it is read: $carried",
            carried.contains("longer timeout will not help"),
        )
    }
}
