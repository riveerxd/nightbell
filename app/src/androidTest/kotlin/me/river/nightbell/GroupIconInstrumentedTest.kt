package me.river.nightbell

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import me.river.nightbell.NightbellTestSupport.appContext
import me.river.nightbell.data.icons.GroupIcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

/**
 * The picture a user picks for a group.
 *
 * On device rather than on the JVM because every line of it is `Bitmap`,
 * `BitmapFactory` and `Base64`, the three things a Robolectric-free unit test
 * cannot have.
 *
 * What is worth pinning is the size. This string is written into the store, and
 * the store is one JSON document rewritten in full on every check that lands, so
 * a full-resolution photo in it would be paid for again every few minutes. The
 * downscale is the feature; the round-trip is just the proof it still decodes
 * afterwards.
 */
@RunWith(AndroidJUnit4::class)
class GroupIconInstrumentedTest {

    private fun solid(width: Int, height: Int, color: Int = Color.RED): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    @Test
    fun aPictureSurvivesEncodingAndDecoding() {
        val encoded = GroupIcon.encode(solid(64, 64))
        assertNotNull(encoded)
        val decoded = GroupIcon.decode(encoded!!)
        assertNotNull(decoded)
        assertEquals(64, decoded!!.width)
        assertEquals(64, decoded.height)
        assertEquals(Color.RED, decoded.getPixel(32, 32))
    }

    @Test
    fun aLargePictureIsScaledDownToTheBadgeSize() {
        val decoded = GroupIcon.decode(GroupIcon.encode(solid(2048, 2048))!!)!!
        assertEquals(GroupIcon.MAX_EDGE, decoded.width)
        assertEquals(GroupIcon.MAX_EDGE, decoded.height)
    }

    @Test
    fun aTallPictureKeepsItsAspectRatio() {
        val decoded = GroupIcon.decode(GroupIcon.encode(solid(400, 1200))!!)!!
        assertEquals(GroupIcon.MAX_EDGE, decoded.height)
        // 400/1200 of 128 rounds to 42; what matters is that it is neither
        // stretched to a square nor collapsed to nothing.
        assertTrue("width was ${decoded.width}", decoded.width in 40..44)
    }

    @Test
    fun aPictureSmallerThanTheBadgeIsLeftAlone() {
        val decoded = GroupIcon.decode(GroupIcon.encode(solid(24, 24))!!)!!
        assertEquals(24, decoded.width)
    }

    /**
     * The number this whole design rests on.
     *
     * If a picked picture cost hundreds of kilobytes, holding it in the store
     * would be the wrong call and it would have to become a file with a path, at
     * the cost of never surviving a backup. A photograph is the worst case, so
     * this measures noise rather than a flat colour, which PNG would compress to
     * almost nothing and prove nothing about.
     */
    @Test
    fun evenAPhotographEncodesToAFewKilobytes() {
        val noisy = Bitmap.createBitmap(2048, 1536, Bitmap.Config.ARGB_8888)
        var seed = 987654321
        for (x in 0 until noisy.width step 4) {
            for (y in 0 until noisy.height step 4) {
                seed = seed * 1103515245 + 12345
                noisy.setPixel(x, y, Color.rgb((seed ushr 16) and 255, (seed ushr 8) and 255, seed and 255))
            }
        }
        val encoded = GroupIcon.encode(noisy)
        assertNotNull(encoded)
        assertTrue(
            "a downscaled photo encoded to ${encoded!!.length} bytes",
            encoded.length < 40_000,
        )
    }

    @Test
    fun somethingThatIsNotAPictureDecodesToNothingRatherThanThrowing() {
        assertNull(GroupIcon.decode("not base64 at all !!!"))
        assertNull(GroupIcon.decode(""))
        assertNull(GroupIcon.decode("aGVsbG8gd29ybGQ="))
    }

    @Test
    fun aPictureIsReadFromTheUriThePickerHandsBack() {
        val file = File(appContext.cacheDir, "group-icon-test.png").also { target ->
            target.outputStream().use { out ->
                solid(600, 600, Color.GREEN).compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }
        val encoded = runBlocking { GroupIcon.encodeFrom(appContext, Uri.fromFile(file)) }
        assertNotNull(encoded)
        val decoded = GroupIcon.decode(encoded!!)!!
        assertEquals(GroupIcon.MAX_EDGE, decoded.width)
        assertEquals(Color.GREEN, decoded.getPixel(64, 64))
        file.delete()
    }

    @Test
    fun aUriThatLeadsNowhereIsRefusedRatherThanCrashing() {
        val missing = Uri.fromFile(File(appContext.cacheDir, "does-not-exist.png"))
        assertNull(runBlocking { GroupIcon.encodeFrom(appContext, missing) })
    }

    // ---- the orientation tag ------------------------------------------------

    /**
     * A photo that says "rotate 90" arrives the right way up.
     *
     * The bug: a portrait phone photo is stored landscape with an EXIF tag, every
     * gallery applies that tag, and `BitmapFactory` does not, so a picked picture
     * landed in the badge on its side. Asserted by shape rather than by pixels: a
     * 240×120 landscape source tagged ROTATE_90 has to come back taller than wide.
     */
    @Test
    fun aPhotoTaggedRotate90ComesBackUpright() {
        val file = writeTaggedJpeg(240, 120, ExifInterface.ORIENTATION_ROTATE_90)
        val decoded = GroupIcon.decode(
            runBlocking { GroupIcon.encodeFrom(appContext, Uri.fromFile(file)) }!!,
        )!!
        assertTrue(
            "expected portrait after rotation, got ${decoded.width}x${decoded.height}",
            decoded.height > decoded.width,
        )
        file.delete()
    }

    @Test
    fun aPhotoTaggedRotate270AlsoComesBackUpright() {
        val file = writeTaggedJpeg(240, 120, ExifInterface.ORIENTATION_ROTATE_270)
        val decoded = GroupIcon.decode(
            runBlocking { GroupIcon.encodeFrom(appContext, Uri.fromFile(file)) }!!,
        )!!
        assertTrue(decoded.height > decoded.width)
        file.delete()
    }

    /**
     * 180 is the case a naive "swap width and height" fix would get wrong.
     *
     * The picture is upside down and the shape is unchanged, so shape alone cannot
     * tell whether the rotation was applied, this pins the aspect instead.
     */
    @Test
    fun aPhotoTaggedRotate180KeepsItsShape() {
        val file = writeTaggedJpeg(240, 120, ExifInterface.ORIENTATION_ROTATE_180)
        val decoded = GroupIcon.decode(
            runBlocking { GroupIcon.encodeFrom(appContext, Uri.fromFile(file)) }!!,
        )!!
        assertTrue(decoded.width > decoded.height)
        file.delete()
    }

    @Test
    fun aPhotoWithNoTagIsLeftAlone() {
        val file = writeTaggedJpeg(240, 120, ExifInterface.ORIENTATION_NORMAL)
        val decoded = GroupIcon.decode(
            runBlocking { GroupIcon.encodeFrom(appContext, Uri.fromFile(file)) }!!,
        )!!
        assertTrue(decoded.width > decoded.height)
        assertEquals(GroupIcon.MAX_EDGE, decoded.width)
        file.delete()
    }

    /** A JPEG of the given size carrying [orientation] in its EXIF. */
    private fun writeTaggedJpeg(width: Int, height: Int, orientation: Int): File {
        val file = File(appContext.cacheDir, "group-icon-exif-$orientation.jpg")
        file.outputStream().use { out ->
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                .apply { eraseColor(Color.BLUE) }
                .compress(Bitmap.CompressFormat.JPEG, 92, out)
        }
        ExifInterface(file.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            saveAttributes()
        }
        return file
    }
}
