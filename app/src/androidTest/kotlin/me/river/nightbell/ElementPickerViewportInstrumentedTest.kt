package me.river.nightbell

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.river.nightbell.NightbellTestSupport.awaitTrue
import me.river.nightbell.domain.ProxyRoute
import me.river.nightbell.ui.setup.ElementPickerOverlay
import me.river.nightbell.ui.theme.NightbellTheme
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The preview lays a page out for the size the page is actually shown at.
 *
 * Reported on issue #8 against winestore-online.com: the preview drew the top
 * strip of an age gate and nothing else. The gate is a card sized
 * `max-height: calc(100vh - 110px)`, and inside the preview that computed to
 * `0px` while `window.innerHeight` was correct, so the card shrank to its own
 * padding and the buttons overflowed out of it.
 *
 * The cause was `loadUrl` in the `AndroidView` factory, which runs before Compose
 * has measured anything. The document was created in a view that was still zero
 * high, `vh` resolved against that, and the later layout moved
 * `window.innerHeight` without moving the initial containing block.
 *
 * The page here measures itself and posts the numbers back, because the assertion
 * has to be about what the page was told rather than about pixels in a
 * screenshot. Before the fix `vh` comes back as 0 and this fails.
 */
@RunWith(AndroidJUnit4::class)
class ElementPickerViewportInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var site: TinyHttpServer

    @After
    fun tearDown() {
        if (::site.isInitialized) site.close()
    }

    @Test
    fun aPageSizedInViewportUnitsGetsTheRealViewport() {
        site = TinyHttpServer { request ->
            when {
                request.path.startsWith("/measured") ->
                    TinyHttpServer.Response(body = "ok")

                else -> TinyHttpServer.Response(
                    body = FIXTURE,
                    contentType = "text/html; charset=utf-8",
                )
            }
        }

        composeRule.setContent {
            NightbellTheme(motionIntensity = 0f) {
                ElementPickerOverlay(
                    visible = true,
                    url = site.url("/gate"),
                    route = ProxyRoute.Route.Direct,
                    existingSelector = "",
                    onDismiss = {},
                    onConfirm = { _, _ -> },
                )
            }
        }

        awaitTrue(timeoutMs = 30_000, description = "the fixture to report its viewport") {
            site.received.any { it.path.startsWith("/measured") }
        }

        val report = site.received.last { it.path.startsWith("/measured") }.path
        val innerHeight = report.param("innerH")
        val hundredVh = report.param("vh")
        val capped = report.param("capped")

        assertTrue(
            "the preview never gave the page a viewport at all: $report",
            innerHeight > MIN_SANE_VIEWPORT,
        )
        // The whole bug in one line. `100vh` and `window.innerHeight` are the same
        // measurement and a page is entitled to assume so.
        assertTrue(
            "100vh resolved to ${hundredVh}px in a ${innerHeight}px viewport: $report",
            kotlin.math.abs(hundredVh - innerHeight) <= TOLERANCE_PX,
        )
        // The shape winestore-online.com actually uses, which fails at zero rather
        // than merely being wrong.
        assertTrue(
            "max-height: calc(100vh - 110px) computed to ${capped}px: $report",
            capped >= innerHeight - CAP_INSET - TOLERANCE_PX,
        )
    }

    private fun String.param(name: String): Int =
        substringAfter("$name=", "").substringBefore('&').toIntOrNull() ?: -1

    private companion object {
        const val TOLERANCE_PX = 4
        const val CAP_INSET = 110
        const val MIN_SANE_VIEWPORT = 200

        /**
         * Two shapes: a plain `100vh` block, and the capped scroller from the site
         * in the report. Both are read after the load event has settled, which is
         * when the preview has certainly been laid out.
         */
        val FIXTURE = """
            <!doctype html>
            <html>
            <head>
              <title>Viewport fixture</title>
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <style>
                html, body { margin: 0; padding: 0; }
                #tall { height: 100vh; background: #123; }
                #capped { max-height: calc(100vh - 110px); overflow-y: auto; background: #eee; }
                #filler { height: 900px; }
              </style>
            </head>
            <body>
              <div id="tall"></div>
              <div id="capped"><div id="filler">filler</div></div>
              <script>
                function report() {
                  var tall = document.getElementById('tall').getBoundingClientRect();
                  var capped = document.getElementById('capped').getBoundingClientRect();
                  new Image().src = '/measured?innerH=' + window.innerHeight +
                    '&vh=' + Math.round(tall.height) +
                    '&capped=' + Math.round(capped.height);
                }
                window.addEventListener('load', function () { setTimeout(report, 1200); });
              </script>
            </body>
            </html>
        """.trimIndent()
    }
}
