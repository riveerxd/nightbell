package me.river.pulse

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.core.content.res.ResourcesCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.river.pulse.PulseTestSupport.appContext
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

        // The corners sit well outside the ring, so nothing the mark draws can reach them.
        for ((x, y) in listOf(2 to 2, 105 to 2, 2 to 105, 105 to 105)) {
            assertEquals(
                "corner ($x,$y) must be fully transparent, not a plate",
                0,
                bitmap.getPixel(x, y).ushr(24),
            )
        }
    }

    @Test
    fun theRingIsOpenWhereTheTracePassesThrough() {
        val bitmap = render(R.drawable.ic_launcher_mark, size = 108)

        // Not centre-height itself: the trace runs along it, which is the whole point of the
        // opening. The clear band sits just above the trace and just inside the ring's
        // outer edge — between half a trace-width and a full trace-width above centre, on
        // the ring's own radius. Scanned as a patch rather than probed as a single pixel so
        // antialiasing on the arc ends cannot decide the result.
        val clear = (92..102).flatMap { x -> (43..49).map { y -> bitmap.getPixel(x, y) } }
            .count { it.ushr(24) == 0 }
        assertTrue(
            "the ring must be genuinely open where the trace crosses it — found no fully " +
                "transparent pixels in the gap, which is what a casing stroke painted in a " +
                "background colour would look like",
            clear > 8,
        )

        // The ring still has to be there either side of that opening.
        assertTrue(
            "the ring should be drawn across the top of the icon",
            bitmap.getPixel(54, 8).ushr(24) > 0,
        )
        assertTrue(
            "the ring should still be drawn just beyond the gap",
            bitmap.getPixel(96, 40).ushr(24) > 0,
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
