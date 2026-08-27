package me.river.nightbell

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.river.nightbell.NightbellTestSupport.appContext
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
    fun theMarkIsTheBrandInkWithACutoutAndNoRing() {
        val bitmap = render(R.drawable.ic_launcher_mark, size = 108)
        val expected = ContextCompat.getColor(appContext, R.color.brand_mark)

        // Every fully covered pixel has to be exactly @color/brand_mark: the brand blue in
        // a release build, the debug yellow in this one. Checked against the resource
        // rather than a literal, because the icon is generated from that colour and a
        // literal here would fail every debug run instead of catching a drawable that
        // stopped using it. Partially covered pixels are skipped, since a premultiplied
        // edge is the rasteriser's business and not the mark's.
        //
        // Counted over the whole bitmap rather than sampled at one coordinate. The old
        // version of this test read (90,54) as "the solid interior of the trace", which
        // stopped being ink in 3.0.2 when the trace became a real hole and the icon was
        // refitted. That point is outside the bell now, and the test had been failing on
        // it ever since.
        var ink = 0
        var foreign = 0
        for (x in 0 until 108) {
            for (y in 0 until 108) {
                val px = bitmap.getPixel(x, y)
                if (px ushr 24 != 0xFF) continue
                if (px == expected) ink++ else foreign++
            }
        }
        assertTrue("the mark must paint something (no opaque pixels at all)", ink > 1000)
        assertEquals(
            "every opaque pixel must be @color/brand_mark, not the old red trace or a grey",
            0,
            foreign,
        )

        // The trace is a hole, not a painted line, which is the whole reason the themed and
        // status-bar copies survive being tinted flat. So the row through the bell's middle
        // has to read ink, gap, ink. Expressed as "there is a transparent pixel between the
        // outermost two" rather than as a coordinate, so the next refit does not move it.
        val row = (0 until 108).map { bitmap.getPixel(it, 54) ushr 24 != 0 }
        val first = row.indexOfFirst { it }
        val last = row.indexOfLast { it }
        assertTrue("the bell must cross its own middle (found no ink on row 54)", first >= 0)
        assertTrue(
            "the trace must be a hole in the bell, but row 54 is solid ink from $first to $last",
            (first..last).any { !row[it] },
        )

        // The ring is gone. It used to arc across the very top of the icon, so anything out
        // at the shoulders of the top band is that ring coming back.
        //
        // The band is not empty and cannot be: the 3.0.2 refit grew the mark to 0.92 of the
        // canvas and lifted the crown ball into rows 4 to 14, which is about 120 pixels of
        // legitimate ink around x=54. This test asserted an empty band and had been failing
        // on the crown since. What separates the two is width, so that is what is measured:
        // the crown is a 13px ball on the centre line, a ring reached the edges.
        val shoulders = (0 until 108)
            .filter { it < 40 || it > 68 }
            .flatMap { x -> (4..14).map { y -> bitmap.getPixel(x, y) } }
            .count { it.ushr(24) > 0 }
        assertEquals(
            "no ring: the top of the icon must hold nothing but the crown, but found " +
                "$shoulders painted pixels out at the shoulders",
            0,
            shoulders,
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
