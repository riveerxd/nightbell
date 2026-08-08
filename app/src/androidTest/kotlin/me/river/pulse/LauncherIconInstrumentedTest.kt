package me.river.pulse

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.core.content.res.ResourcesCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.river.pulse.NightbellTestSupport.appContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The launcher icon's background.
 *
 * This exists because the same bug shipped twice. The mark was drawn on an opaque ink
 * plate, the plate was reported as ugly, and the obvious fix — an adaptive icon with a
 * transparent `<background>` — silently did nothing: `AdaptiveIconDrawable.draw()` fills
 * its layer bitmap with `Color.BLACK` before compositing its layers, because the format
 * assumes the background is opaque. The icon therefore rendered on solid black in the
 * launcher *and* in Settings' app-info screen, which made it look like a launcher cache
 * problem and cost two rounds of chasing one.
 *
 * So there are two things worth pinning down, and neither is visible by looking at a
 * screenshot of a dark icon on a dark background:
 *
 *  1. the icon the manifest points at is not adaptive, and
 *  2. the drawable it points at actually has transparent pixels.
 *
 * Android still composites a plate behind a legacy icon in most launchers, and that is
 * outside the app's control. What is inside the app's control is not handing the
 * framework something that is guaranteed to come out black.
 */
@RunWith(AndroidJUnit4::class)
class LauncherIconInstrumentedTest {

    @Test
    fun theLauncherIconIsNotAnAdaptiveIcon() {
        val icon = appContext.packageManager.getApplicationIcon(appContext.packageName)
        assertFalse(
            "the launcher icon must not be an AdaptiveIconDrawable: that format fills its " +
                "own background with Color.BLACK, so a transparent background is impossible " +
                "(got ${icon.javaClass.simpleName})",
            icon is AdaptiveIconDrawable,
        )
    }

    @Test
    fun theLauncherIconHasATransparentBackground() {
        val bitmap = render(R.drawable.ic_launcher_mark, size = 108)

        // The corners sit well outside the trace, so nothing the mark draws can reach them.
        for ((x, y) in listOf(2 to 2, 105 to 2, 2 to 105, 105 to 105)) {
            assertEquals(
                "corner ($x,$y) must be fully transparent, not a plate",
                0,
                bitmap.getPixel(x, y).ushr(24),
            )
        }
    }

    @Test
    fun theMarkIsABlueTraceWithNoRing() {
        val bitmap = render(R.drawable.ic_launcher_mark, size = 108)

        // The trace's baseline runs across the middle. Sample the solid interior of the
        // right-hand flat segment: it must be opaque and the brand blue (#2F6BFF, so the
        // blue channel sits far above red and green) — not the old red trace, not empty.
        val px = bitmap.getPixel(90, 54)
        val a = px ushr 24 and 0xFF
        val r = px ushr 16 and 0xFF
        val g = px ushr 8 and 0xFF
        val b = px and 0xFF
        assertTrue("the mark must draw ink on its baseline (found nothing at 90,54)", a > 0)
        assertTrue(
            "the mark must be the brand blue, not the old red trace or a grey — got " +
                "r=$r g=$g b=$b",
            b > 150 && b > r + 60 && b > g + 40,
        )

        // The ring is gone. It used to arc across the very top of the icon; the trace's
        // highest point sits well below the top band, so that band must now be completely
        // empty. Any ink there is exactly the ring this release removed. Scanned as a band
        // rather than one pixel so a stray arc anywhere across the top is caught.
        val topBand = (0 until 108).flatMap { x -> (4..14).map { y -> bitmap.getPixel(x, y) } }
            .count { it.ushr(24) > 0 }
        assertEquals(
            "no ring: the top of the icon must be empty, but found $topBand painted pixels",
            0,
            topBand,
        )
    }

    private fun render(resId: Int, size: Int): Bitmap {
        val drawable = ResourcesCompat.getDrawable(appContext.resources, resId, null)
            ?: error("drawable $resId missing")
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }
}
