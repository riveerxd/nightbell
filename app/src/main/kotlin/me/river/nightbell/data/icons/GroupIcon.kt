package me.river.nightbell.data.icons

import me.river.nightbell.data.diag.Diag
import me.river.nightbell.domain.LogEvent
import me.river.nightbell.domain.LogField
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.river.nightbell.domain.runCatchingCancellable

/**
 * A picture the user picked, small enough to live in the store.
 *
 * Held as base64 PNG inside [me.river.nightbell.domain.MonitorGroup] rather than
 * as a file on disk with a path in the group. A file would have been cheaper per
 * byte and worse in every way that matters here:
 *
 *  - a backup is the store serialised, so a path would export a reference to a
 *    file the new phone does not have, and a restored group would silently lose
 *    its icon;
 *  - the file would need a lifecycle, deleted when the group is, when the
 *    picture is replaced, and when an import brings a group whose picture is
 *    already there under another name, every one of which is a way to leak
 *    files or to blank an icon that is still in use.
 *
 * The size is what makes that affordable. [MAX_EDGE] is the largest the badge is
 * ever drawn at any font scale, and a 128 px PNG of a logo is a few kilobytes;
 * base64 adds a third. Anything the user picks is downscaled to it before
 * encoding, so a 12-megapixel photo costs the same as an icon.
 */
object GroupIcon {

    /**
     * Longest edge kept, in pixels.
     *
     * The badge is 46 dp in the editor and 42 dp on the card, so 128 px covers a
     * 2.75× display with room to spare and nothing the user can pick will look
     * soft. Larger buys no visible quality and goes straight into every future
     * write of the store, since the whole document is rewritten each time.
     */
    const val MAX_EDGE = 128

    /**
     * Refuses anything that would still be big after downscaling.
     *
     * A guard against the pathological rather than the large: a 20000×20 banner
     * downscales to 128×0 and a malformed file can decode to something enormous.
     * The store is one JSON document rewritten on every mutation, so a megabyte
     * of base64 in it would be paid again on every check that lands.
     */
    private const val MAX_ENCODED_BYTES = 96 * 1024

    /** Reads [uri], turns it the right way up, downscales it, and base64-encodes it. */
    suspend fun encodeFrom(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        val decoded = decodeSampled(context, uri) ?: return@withContext null
        val upright = orient(context, uri, decoded)
        try {
            encode(upright)
        } finally {
            // These are ours and nothing else holds them. `orient` returns its
            // input untouched when there is nothing to do, so guard the double
            // recycle rather than assuming two objects.
            if (upright !== decoded) upright.recycle()
            decoded.recycle()
        }
    }

    /**
     * Applies the picture's own orientation tag.
     *
     * The bug this fixes: a portrait photo off a phone camera is almost never
     * stored portrait. The sensor writes it landscape and records "rotate 90" in
     * EXIF, and every gallery applies that tag on the way to the screen.
     * `BitmapFactory` does not, it hands back the pixels as stored, so a picked
     * photo arrived in the badge lying on its side.
     *
     * Mirrored transposes are handled too, because the same tag encodes them and a
     * front-camera shot routinely carries one. `ImageDecoder` would do all of this
     * for free, but only from API 28, and minSdk here is 26.
     */
    private fun orient(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = runCatchingCancellable {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        return runCatchingCancellable {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.onFailure {
            Diag.log(LogEvent.ICON_PICTURE_FAILED, LogField.tag("at", "orientation"), LogField.error("why", it))
        }.getOrDefault(bitmap)
    }

    /** Downscales and encodes an already-decoded bitmap. */
    fun encode(bitmap: Bitmap): String? {
        val scaled = downscale(bitmap)
        val bytes = ByteArrayOutputStream().use { out ->
            // PNG, not JPEG: these are logos and screenshots of logos, where a
            // JPEG's ringing around hard edges is exactly the artefact that shows.
            // Transparency has to survive too, a mark on a white JPEG block would
            // sit in a light square on the dark scheme.
            scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        if (scaled !== bitmap) scaled.recycle()
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        if (encoded.length > MAX_ENCODED_BYTES) {
            Diag.log(LogEvent.ICON_PICTURE_REFUSED, LogField.of("bytes", encoded.length))
            return null
        }
        return encoded
    }

    /** Decodes what [encode] produced, or null if the string is not one of ours. */
    fun decode(encoded: String): Bitmap? {
        if (encoded.isBlank()) return null
        return runCatchingCancellable {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }

    /**
     * Decodes [uri] at roughly the size we want rather than at full size.
     *
     * Two passes, which is the standard shape and not an optimisation: the first
     * reads the header only, so a 12-megapixel photo never has to fit in memory
     * to find out it is going to be thrown away. `inSampleSize` halves, so this
     * lands within a factor of two above [MAX_EDGE] and [downscale] finishes the
     * job exactly.
     */
    private fun decodeSampled(context: Context, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatchingCancellable {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
        }.onFailure { Diag.log(LogEvent.ICON_PICTURE_FAILED, LogField.tag("at", "header"), LogField.error("why", it)) }
        val longest = max(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longest > 0 && longest / (sample * 2) >= MAX_EDGE) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return runCatchingCancellable {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
        }.onFailure { Diag.log(LogEvent.ICON_PICTURE_FAILED, LogField.tag("at", "decode"), LogField.error("why", it)) }.getOrNull()
    }

    /** Scales so the longest edge is [MAX_EDGE], never up, never below one pixel. */
    private fun downscale(bitmap: Bitmap): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= MAX_EDGE || longest == 0) return bitmap
        val ratio = MAX_EDGE.toFloat() / longest
        val width = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val height = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    private const val TAG = "GroupIcon"
}
