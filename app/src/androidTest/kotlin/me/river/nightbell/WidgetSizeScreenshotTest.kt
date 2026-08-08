package me.river.nightbell

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.river.nightbell.NightbellTestSupport.appContext
import me.river.nightbell.NightbellTestSupport.screenshotDir
import me.river.nightbell.domain.Health
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorRuntime
import me.river.nightbell.domain.Summary
import me.river.nightbell.widget.NightbellWidgetProvider
import me.river.nightbell.widget.WidgetConfig
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Renders the widget at the sizes a launcher actually hands out, and writes the results to
 * the app's files dir for eyeballing.
 *
 * Not a substitute for [WidgetInstrumentedTest]'s assertions — those are what fail a
 * build. This exists because the column layout is the kind of change whose bugs are
 * geometric ("the last row is clipped", "the second column is 12dp wide"), and no
 * assertion about view counts catches that. It also measures and lays the widget out at a
 * fixed pixel size, which is the only way to find out whether content actually fits rather
 * than whether it was added to a container.
 *
 * Pull them with:
 *   adb exec-out run-as me.river.nightbell.debug cat files/screenshots/widget-4x2.png > out.png
 */
@RunWith(AndroidJUnit4::class)
class WidgetSizeScreenshotTest {

    private fun monitor(id: String, name: String) = Monitor(
        id = id,
        name = name,
        url = "https://$id.example.com/health",
    )

    private fun runtime(health: Health, latency: Long = 120, message: String = "") = MonitorRuntime(
        health = health,
        lastCheckedAt = System.currentTimeMillis(),
        lastLatencyMs = latency,
        lastMessage = message,
    )

    private val fleet = Summary.of(
        listOf(
            monitor("a", "Marketing site"),
            monitor("b", "Checkout API"),
            monitor("c", "Auth service"),
            monitor("d", "Search index"),
            monitor("e", "CDN edge"),
            monitor("f", "Webhooks"),
        ),
        mapOf(
            "a" to runtime(Health.DOWN, message = "Connection refused"),
            "b" to runtime(Health.DEGRADED, latency = 4_100L),
            "c" to runtime(Health.UP, latency = 88L),
            "d" to runtime(Health.UP, latency = 143L),
            "e" to runtime(Health.UP, latency = 61L),
            "f" to runtime(Health.UP, latency = 204L),
        ),
    )

    /** dp of widget, as the launcher reports it, to a real pixel bitmap. */
    private fun capture(name: String, config: WidgetConfig, widthDp: Int, heightDp: Int): File {
        val density = appContext.resources.displayMetrics.density
        val widthPx = (widthDp * density).toInt()
        val heightPx = (heightDp * density).toInt()

        val views = NightbellWidgetProvider.build(appContext, config, fleet, 7, widthDp, heightDp)
        val root = views.apply(appContext, FrameLayout(appContext))
        root.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, widthPx, heightPx)

        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val file = File(screenshotDir(), "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }

    @Test
    fun renderEveryInterestingWidgetSize() {
        val sizes = listOf(
            // name, width, height — the cell sizes a phone launcher offers
            Triple("widget-4x1", 250, 60),
            Triple("widget-4x2", 250, 110),
            Triple("widget-4x3", 250, 180),
            Triple("widget-4x4", 250, 250),
            Triple("widget-5x2", 320, 110),
            Triple("widget-wide-flat", 400, 90),
        )
        val written = sizes.map { (name, w, h) ->
            capture(name, WidgetConfig(maxRows = 6), w, h)
        }

        // Clean header variants, which is what the toggles are for.
        written + listOf(
            capture(
                "widget-clean",
                WidgetConfig(
                    maxRows = 6,
                    showTitle = false,
                    showHeadline = false,
                    showTimestamp = false,
                ),
                250,
                180,
            ),
            capture(
                "widget-two-columns",
                WidgetConfig(maxRows = 6, columns = 2),
                320,
                180,
            ),
        )

        written.forEach { file ->
            assertTrue("${file.name} was not written", file.isFile && file.length() > 0)
        }
    }
}
