package me.river.nightbell.data.alerts

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap

/**
 * Draws the URGENT page's action pills as bitmaps.
 *
 * ### Why bitmaps
 * The obvious implementation — a `<shape>` with `<corners>` behind a `TextView` —
 * cannot be made to render correctly inside a heads-up notification. Measured on
 * a 420dpi device: a pill declared 38dp tall came out 61px (so the *view* was
 * resolved at 2.625 px/dp and the whole layout then scaled by ~0.61), while its
 * declared 19dp corner radius came out about 10px — consistent with the drawable
 * being inflated at 1 px/dp and then scaled by that same 0.61. Two different
 * densities for the same widget, so no dp value can be correct for both. An
 * `oval` came out a rounded square for the same reason, and
 * `setViewOutlinePreferredRadius` did not move it either.
 *
 * A bitmap has no dp to re-resolve. The corners are pixels, and an `ImageView`
 * with `adjustViewBounds` plus `fitCenter` scales them uniformly, so the ends
 * stay exactly semicircular at any size the renderer picks.
 *
 * The cost is real and worth stating: text baked into a bitmap does not follow
 * the user's font-size setting and is not selectable. That is acceptable for
 * three fixed one-word labels on an emergency surface, and every pill carries a
 * `contentDescription` so a screen reader still gets the full wording.
 */
internal object UrgentPills {

    /** Drawn at 3× the 38dp target so downscaling never softens the edges. */
    private const val HEIGHT_PX = 114
    private const val TEXT_PX = 42f
    private const val SIDE_PADDING_PX = 40f
    private const val ICON_PX = 52
    private const val ICON_GAP_PX = 14

    /** A label-only pill, e.g. "Ack" or "Re-check". */
    fun label(text: String, fill: Int, textColor: Int): Bitmap {
        val paint = textPaint(textColor)
        val width = (SIDE_PADDING_PX * 2 + paint.measureText(text)).toInt().coerceAtLeast(HEIGHT_PX)
        val bitmap = createBitmap(width, HEIGHT_PX)
        val canvas = Canvas(bitmap)
        drawPill(canvas, width, fill)
        drawCentredText(canvas, text, paint, width, offsetX = 0f)
        return bitmap
    }

    /**
     * A circular icon-only pill, e.g. the crossed bell.
     *
     * @param slash draws a diagonal cut through the glyph in the pill's own fill
     *   colour. Needed because the shipped mute drawable strikes its bell with a
     *   *white* line — right for a status-bar silhouette, invisible the moment the
     *   bell is tinted to sit on a light pill. Cutting in the fill colour works
     *   whatever colour the pill is.
     */
    fun icon(
        context: Context,
        iconRes: Int,
        fill: Int,
        tint: Int? = null,
        slash: Boolean = false,
    ): Bitmap {
        val bitmap = createBitmap(HEIGHT_PX, HEIGHT_PX)
        val canvas = Canvas(bitmap)
        drawPill(canvas, HEIGHT_PX, fill)
        val drawable = ContextCompat.getDrawable(context, iconRes) ?: return bitmap
        tint?.let { drawable.setTint(it) }
        val inset = (HEIGHT_PX - ICON_PX) / 2
        drawable.setBounds(inset, inset, inset + ICON_PX, inset + ICON_PX)
        drawable.draw(canvas)
        if (slash) {
            val gap = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = fill
                strokeWidth = ICON_PX * 0.17f
                strokeCap = Paint.Cap.ROUND
            }
            val a = inset + ICON_PX * 0.10f
            val b = inset + ICON_PX * 0.90f
            canvas.drawLine(a, a, b, b, gap)
            val mark = Paint(gap).apply {
                color = tint ?: 0xFF000000.toInt()
                strokeWidth = ICON_PX * 0.09f
            }
            canvas.drawLine(a, a, b, b, mark)
        }
        return bitmap
    }

    /** A pill carrying an icon and a label, laid out like the platform's own. */
    fun iconLabel(
        context: Context,
        iconRes: Int,
        text: String,
        fill: Int,
        textColor: Int,
        tint: Int? = null,
    ): Bitmap {
        val paint = textPaint(textColor)
        val textWidth = paint.measureText(text)
        val width = (SIDE_PADDING_PX * 2 + ICON_PX + ICON_GAP_PX + textWidth).toInt()
        val bitmap = createBitmap(width, HEIGHT_PX)
        val canvas = Canvas(bitmap)
        drawPill(canvas, width, fill)

        val contentWidth = ICON_PX + ICON_GAP_PX + textWidth
        val startX = (width - contentWidth) / 2f
        val drawable = ContextCompat.getDrawable(context, iconRes)
        if (drawable != null) {
            tint?.let { drawable.setTint(it) }
            val top = (HEIGHT_PX - ICON_PX) / 2
            drawable.setBounds(
                startX.toInt(),
                top,
                startX.toInt() + ICON_PX,
                top + ICON_PX,
            )
            drawable.draw(canvas)
        }
        val textStart = startX + ICON_PX + ICON_GAP_PX
        val baseline = HEIGHT_PX / 2f - (paint.descent() + paint.ascent()) / 2f
        paint.textAlign = Paint.Align.LEFT
        canvas.drawText(text, textStart, baseline, paint)
        return bitmap
    }

    private fun textPaint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = TEXT_PX
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    /** Radius is exactly half the height, so the ends are true semicircles. */
    private fun drawPill(canvas: Canvas, width: Int, fill: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill }
        val radius = HEIGHT_PX / 2f
        canvas.drawRoundRect(
            RectF(0f, 0f, width.toFloat(), HEIGHT_PX.toFloat()),
            radius,
            radius,
            paint,
        )
    }

    private fun drawCentredText(
        canvas: Canvas,
        text: String,
        paint: Paint,
        width: Int,
        offsetX: Float,
    ) {
        val baseline = HEIGHT_PX / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(text, width / 2f + offsetX, baseline, paint)
    }
}
