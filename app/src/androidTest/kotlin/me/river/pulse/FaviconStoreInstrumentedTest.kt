package me.river.pulse

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.river.pulse.PulseTestSupport.appContext
import me.river.pulse.data.icons.FaviconStore
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Favicons for page-element monitors.
 *
 * The caching is the point — the dashboard recomposes constantly and a
 * `LazyColumn` re-runs an item's effects every time it scrolls back into view, so
 * an uncached implementation means a request to somebody else's server per
 * scroll. Every test here therefore asserts on the *delta* in requests the server
 * saw, which is the only thing that actually proves a cache works.
 */
@RunWith(AndroidJUnit4::class)
class FaviconStoreInstrumentedTest {

    private lateinit var server: TinyHttpServer
    private var store: FaviconStore? = null

    @After
    fun tearDown() {
        store?.clear()
        if (this::server.isInitialized) server.close()
    }

    private fun newStore(online: Boolean = true) =
        FaviconStore(appContext, isOnline = { online }).also { store = it }

    // ------------------------------------------------------------------ fixtures

    /** A real PNG, so the production decode path is the one under test. */
    private fun pngBytes(size: Int = 64, color: Int = Color.rgb(20, 160, 90)): ByteArray {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
    }

    /**
     * Wraps [png] in a single-entry ICO container, the way real `.ico` files have
     * been built since Vista. `BitmapFactory` cannot decode the container, which
     * is exactly what [FaviconStore] has to work around.
     */
    private fun icoWrapping(png: ByteArray, width: Int = 64): ByteArray {
        val out = ByteArrayOutputStream()
        fun le16(value: Int) {
            out.write(value and 0xFF)
            out.write((value shr 8) and 0xFF)
        }
        fun le32(value: Int) {
            out.write(value and 0xFF)
            out.write((value shr 8) and 0xFF)
            out.write((value shr 16) and 0xFF)
            out.write((value shr 24) and 0xFF)
        }
        le16(0) // reserved
        le16(1) // type: icon
        le16(1) // one entry
        out.write(if (width >= 256) 0 else width) // width (0 means 256)
        out.write(if (width >= 256) 0 else width) // height
        out.write(0) // palette size
        out.write(0) // reserved
        le16(1) // colour planes
        le16(32) // bits per pixel
        le32(png.size)
        le32(6 + 16) // payload starts right after the directory
        out.write(png)
        return out.toByteArray()
    }

    private fun page(vararg links: String) =
        "<!doctype html><html><head><title>Shop</title>${links.joinToString("")}</head>" +
            "<body><span data-testid=\"price\">42</span></body></html>"

    // --------------------------------------------------------------------- tests

    @Test
    fun usesTheIconDeclaredByThePageAndThenServesItFromMemory() = runBlocking {
        val png = pngBytes()
        server = TinyHttpServer { request ->
            when (request.path) {
                "/icon.png" -> TinyHttpServer.Response(contentType = "image/png", bytes = png)
                else -> TinyHttpServer.Response(
                    contentType = "text/html",
                    body = page("""<link rel="icon" href="/icon.png" sizes="64x64">"""),
                )
            }
        }
        val subject = newStore()

        val first = subject.load(server.url("/widget"))
        assertNotNull("should have resolved the declared icon", first)
        assertTrue(
            "the declared icon should have been requested",
            server.received.any { it.path == "/icon.png" },
        )

        val afterFirst = server.received.size
        repeat(5) { assertNotNull(subject.load(server.url("/widget"))) }
        assertEquals(
            "repeat loads must not touch the network",
            afterFirst,
            server.received.size,
        )
    }

    @Test
    fun prefersTheLargestDeclaredIcon() = runBlocking {
        server = TinyHttpServer { request ->
            when {
                request.path.endsWith(".png") ->
                    TinyHttpServer.Response(contentType = "image/png", bytes = pngBytes(size = 32))
                else -> TinyHttpServer.Response(
                    contentType = "text/html",
                    body = page(
                        """<link rel="icon" href="/small.png" sizes="16x16">""",
                        """<link rel="icon" href="/big.png" sizes="180x180">""",
                    ),
                )
            }
        }
        newStore().load(server.url("/page"))

        // Upscaling a 16px icon into a 42dp badge looks like a bug, so the larger
        // declaration has to win.
        val firstIcon = server.received.first { it.path.endsWith(".png") }
        assertEquals("/big.png", firstIcon.path)
    }

    /**
     * The case that matters most: `/favicon.ico` is the one path every site has,
     * and `BitmapFactory` cannot decode the ICO container at all.
     */
    @Test
    fun unwrapsAnIcoContainerWhenThereIsNoDeclaredIcon() = runBlocking {
        val ico = icoWrapping(pngBytes(size = 48), width = 48)
        server = TinyHttpServer { request ->
            when (request.path) {
                "/favicon.ico" -> TinyHttpServer.Response(
                    contentType = "image/x-icon",
                    bytes = ico,
                )
                // No <link rel="icon"> anywhere.
                else -> TinyHttpServer.Response(contentType = "text/html", body = page())
            }
        }

        val icon = newStore().load(server.url("/page"))

        assertNotNull("an ICO-wrapped PNG must still decode", icon)
        assertTrue("and it should be a real image", (icon?.width ?: 0) > 0)
    }

    @Test
    fun survivesAColdStartByReadingTheDiskCache() = runBlocking {
        val png = pngBytes()
        server = TinyHttpServer { request ->
            if (request.path == "/icon.png") {
                TinyHttpServer.Response(contentType = "image/png", bytes = png)
            } else {
                TinyHttpServer.Response(
                    contentType = "text/html",
                    body = page("""<link rel="icon" href="/icon.png">"""),
                )
            }
        }

        assertNotNull(newStore().load(server.url("/page")))
        val afterWarm = server.received.size

        // A brand-new instance shares nothing but the files directory, which is
        // what a process restart looks like.
        val cold = FaviconStore(appContext, isOnline = { true })
        store = cold
        assertNotNull("disk cache should satisfy a cold start", cold.load(server.url("/page")))
        assertEquals("a cold start must not touch the network", afterWarm, server.received.size)
    }

    @Test
    fun remembersThatASiteHasNoIcon() = runBlocking {
        server = TinyHttpServer { request ->
            if (request.path == "/page") {
                TinyHttpServer.Response(contentType = "text/html", body = page())
            } else {
                TinyHttpServer.Response(code = 404, reason = "Not Found")
            }
        }
        val subject = newStore()

        assertNull(subject.load(server.url("/page")))
        val afterMiss = server.received.size
        assertTrue("should have tried the well-known paths", afterMiss > 1)

        // Without negative caching this would re-probe every well-known path on
        // every scroll, forever, for a site that simply has no icon.
        repeat(3) { assertNull(subject.load(server.url("/page"))) }
        assertEquals("a known miss must not be retried", afterMiss, server.received.size)
    }

    @Test
    fun doesNotTouchTheNetworkWhileOffline() = runBlocking {
        server = TinyHttpServer {
            TinyHttpServer.Response(contentType = "image/png", bytes = pngBytes())
        }

        assertNull(newStore(online = false).load(server.url("/page")))
        assertEquals("offline must issue no requests at all", 0, server.received.size)
    }

    /**
     * An offline failure must not be recorded as "this site has no icon" — one
     * tunnel would otherwise blank every badge for the length of the negative TTL.
     */
    @Test
    fun offlineDoesNotPoisonTheNegativeCache() = runBlocking {
        val png = pngBytes()
        server = TinyHttpServer { request ->
            if (request.path == "/icon.png") {
                TinyHttpServer.Response(contentType = "image/png", bytes = png)
            } else {
                TinyHttpServer.Response(
                    contentType = "text/html",
                    body = page("""<link rel="icon" href="/icon.png">"""),
                )
            }
        }

        assertNull(newStore(online = false).load(server.url("/page")))
        store?.clear()

        val back = FaviconStore(appContext, isOnline = { true })
        store = back
        assertNotNull(
            "an icon must still be fetchable after an offline attempt",
            back.load(server.url("/page")),
        )
    }

    @Test
    fun sharesOneIconBetweenMonitorsOnTheSameSite() = runBlocking {
        val png = pngBytes()
        server = TinyHttpServer { request ->
            if (request.path == "/icon.png") {
                TinyHttpServer.Response(contentType = "image/png", bytes = png)
            } else {
                TinyHttpServer.Response(
                    contentType = "text/html",
                    body = page("""<link rel="icon" href="/icon.png">"""),
                )
            }
        }
        val subject = newStore()

        assertNotNull(subject.load(server.url("/product/one")))
        val afterFirst = server.received.size

        // Different page, same origin — the cache key is the origin, so this must
        // be free.
        assertNotNull(subject.load(server.url("/product/two")))
        assertEquals(
            "a second monitor on the same site must reuse the icon",
            afterFirst,
            server.received.size,
        )
    }
}
