package me.river.nightbell

import androidx.test.ext.junit.runners.AndroidJUnit4
import me.river.nightbell.data.Nightbell
import me.river.nightbell.data.NightbellSnapshot
import me.river.nightbell.data.check.ElementChecker
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.ElementTarget
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the data saver actually does to a page load, counted at the server.
 *
 * The report was 7.14 GB in a month from ten page monitors, because every check
 * fetched every script again. These pin the three things the fix has to hold at
 * once: the bundle is fetched once, the page itself is still asked every time,
 * and a dead origin still fails the check rather than being answered from cache.
 */
@RunWith(AndroidJUnit4::class)
class DataSaverInstrumentedTest {

    private lateinit var server: TinyHttpServer

    // The worst case on purpose: the document itself says it may be cached for an
    // hour, which is exactly when a careless cache would stop asking the origin.
    private val page = """
        <!doctype html>
        <html><head><title>Saver fixture</title>
        <link rel="preload" href="/inter.woff2" as="font" type="font/woff2" crossorigin>
        <script src="/app.js"></script>
        </head>
        <body><main id="root">loading</main>
        <video src="/hero.mp4" preload="auto" muted></video>
        </body></html>
    """.trimIndent()

    // The element only exists once the script has run, so a found element proves
    // the cached script executed rather than merely arrived.
    private val script = """
        document.addEventListener('DOMContentLoaded', function () {
          var p = document.createElement('p'); p.id = 'ready'; p.textContent = 'Booked';
          document.getElementById('root').appendChild(p);
        });
    """.trimIndent()

    @Before
    fun setUp() {
        server = TinyHttpServer { request ->
            when (request.path.substringBefore('?')) {
                "/" -> TinyHttpServer.Response(
                    body = page,
                    contentType = "text/html; charset=utf-8",
                    extraHeaders = mapOf("Cache-Control" to "max-age=3600"),
                )
                "/app.js" -> TinyHttpServer.Response(
                    body = script,
                    contentType = "application/javascript",
                    extraHeaders = mapOf("Cache-Control" to "public, max-age=31536000, immutable"),
                )
                "/slow" -> TinyHttpServer.Response(
                    body = "<!doctype html><html><head><title>Slow</title>" +
                        "<script async src=\"/slow.js\"></script></head>" +
                        "<body><p id=\"ready\">Booked</p></body></html>",
                    contentType = "text/html; charset=utf-8",
                )
                "/slow.js" -> TinyHttpServer.Response(
                    body = "void 0",
                    contentType = "application/javascript",
                    delayMs = 8_000,
                )
                "/inter.woff2" -> TinyHttpServer.Response(bytes = ByteArray(40_000), contentType = "font/woff2")
                "/hero.mp4" -> TinyHttpServer.Response(bytes = ByteArray(200_000), contentType = "video/mp4")
                else -> TinyHttpServer.Response(code = 404, reason = "Not Found", body = "no")
            }
        }
    }

    @After
    fun tearDown() = server.close()

    private fun monitor() = Monitor(
        id = "saver-${server.port}",
        name = "Saver",
        kind = MonitorKind.WEBSITE_ELEMENT,
        url = server.url("/"),
        element = ElementTarget(cssSelector = "#ready", textSnippet = "Booked"),
        timeoutSeconds = 20,
    )

    private fun hits(path: String) = server.received.count { it.path.substringBefore('?') == path }

    @Test
    fun theBundleIsFetchedOnceAndThePageEveryTime() = runBlocking {
        val checker = ElementChecker(NightbellTestSupport.appContext)
        repeat(3) { round ->
            val result = checker.check(monitor(), cached = true)
            assertTrue("round $round: ${result.message} ${result.detail}", result.ok)
        }
        assertEquals("the page has to reach its server on every check", 3, hits("/"))
        assertEquals("the script should come from cache after the first load", 1, hits("/app.js"))
        assertEquals("fonts are never fetched under the saver", 0, hits("/inter.woff2"))
        assertEquals("media is never fetched under the saver", 0, hits("/hero.mp4"))
    }

    @Test
    fun withTheSaverOffEveryLoadIsColdAsBefore() = runBlocking {
        val checker = ElementChecker(NightbellTestSupport.appContext)
        repeat(2) {
            assertTrue(checker.check(monitor(), cached = false).ok)
        }
        assertEquals(2, hits("/"))
        assertEquals(2, hits("/app.js"))
    }

    @Test
    fun aPassingPageIsStoppedBeforeItsStragglersFinish() = runBlocking {
        val checker = ElementChecker(NightbellTestSupport.appContext)
        val slow = monitor().copy(url = server.url("/slow"))

        val saver = checker.check(slow, cached = true)
        assertTrue("${saver.message} ${saver.detail}", saver.ok)
        assertTrue("the saver waited for the straggler: ${saver.latencyMs}ms", saver.latencyMs < 5_000)

        // The old path is untouched: it still waits for the load event.
        val cold = checker.check(slow, cached = false)
        assertTrue("${cold.message} ${cold.detail}", cold.ok)
        assertTrue("the cold load did not wait: ${cold.latencyMs}ms", cold.latencyMs >= 7_500)
    }

    /**
     * The engine end to end: the first check of a process is the daily cold load
     * and is metered as the full cost, the second is warm and metered as the
     * saver's. Both have to land on the runtime or the estimate has nothing to say.
     */
    @Test
    fun theEngineMetersAColdThenAWarmCheck() = runBlocking {
        val graph = Nightbell.install(NightbellTestSupport.appContext)
        val target = monitor()
        graph.store.replaceAll(
            NightbellSnapshot(
                monitors = listOf(target),
                settings = GlobalSettings(
                    motionIntensity = 0f,
                    backgroundChecksEnabled = false,
                    hasSeenPagerSetup = true,
                    pagerSetupSilenced = true,
                    dataSaver = true,
                ),
            ),
        )
        assertTrue(graph.engine.run(target.id)!!.ok)
        assertTrue(graph.engine.run(target.id)!!.ok)
        val runtime = graph.store.currentSnapshot().runtimes.getValue(target.id)
        assertTrue("no cold cost recorded: $runtime", runtime.bytesFull > 0L)
        assertTrue("no warm cost recorded: $runtime", runtime.bytesSaver > 0L)
        assertTrue(
            "warm ${runtime.bytesSaver} should cost less than cold ${runtime.bytesFull}",
            runtime.bytesSaver < runtime.bytesFull,
        )
        assertEquals("the second check still asked the server for the page", 2, hits("/"))
        assertEquals(1, hits("/app.js"))
    }

    @Test
    fun aDeadOriginStillFailsAfterACachedLoad() = runBlocking {
        val checker = ElementChecker(NightbellTestSupport.appContext)
        val target = monitor()
        assertTrue(checker.check(target, cached = true).ok)
        server.close()
        val after = checker.check(target, cached = true)
        assertFalse("a cached page answered for a server that is gone: ${after.message}", after.ok)
    }
}
