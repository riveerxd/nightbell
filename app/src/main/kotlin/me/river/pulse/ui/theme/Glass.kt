package me.river.pulse.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Hand-drawn drop shadow.
 *
 * Deliberately not [androidx.compose.ui.draw.shadow]: the platform elevation
 * shadow is rasterised by the GPU driver, and on software renderers (the
 * headless emulator, some low-end devices) it degenerates into a hard-edged
 * dark rectangle behind translucent surfaces. Stacking a few translucent
 * rounded rects costs a handful of draw ops and renders identically everywhere.
 *
 * It is a *shadow*, not a glow: the layers stay black and sit below the surface.
 * Tinting this with an accent turns every card into a neon sign, so accent
 * colour belongs on the edge (see [glass]) instead.
 */
fun Modifier.softShadow(
    corner: Dp,
    color: Color = Color.Black,
    radius: Dp = 12.dp,
    strength: Float = 1f,
): Modifier = drawBehind {
    if (radius.value <= 0f || strength <= 0f) return@drawBehind
    val maxSpread = radius.toPx()
    val cornerPx = corner.toPx()
    // Punch the surface's own silhouette out of the shadow. Without this it
    // sits *behind* a translucent pane and muddies the fill.
    val silhouette = Path().apply {
        addRoundRect(
            RoundRect(
                left = 0f,
                top = 0f,
                right = size.width,
                bottom = size.height,
                cornerRadius = CornerRadius(cornerPx),
            ),
        )
    }
    clipPath(silhouette, ClipOp.Difference) {
        val layers = 5
        repeat(layers) { index ->
            val t = (index + 1) / layers.toFloat()
            val spread = maxSpread * t
            val alpha = (1f - t) * 0.10f * strength
            if (alpha <= 0.002f) return@repeat
            drawRoundRect(
                color = color.copy(alpha = alpha),
                // Biased downward so it reads as cast light, not a halo.
                topLeft = Offset(-spread * 0.30f, spread * 0.40f),
                size = Size(size.width + spread * 0.6f, size.height + spread * 0.45f),
                cornerRadius = CornerRadius(cornerPx + spread * 0.5f),
            )
        }
    }
}

/**
 * The house style: a translucent pane, a light-catching top edge, a plain black
 * drop shadow, and a diagonal specular sweep. Composed as a modifier so any
 * surface can become glass.
 *
 * [accent] tints the surface's *rim*. A down monitor gets a red edge, not a red
 * halo — the status still reads instantly across a dark list, and the screen
 * doesn't turn into a wall of bloom.
 */
fun Modifier.glass(
    shape: Shape = RoundedCornerShape(PulseRadii.card),
    corner: Dp = PulseRadii.card,
    fill: Color = PulseColors.GlassFill,
    fillEnd: Color = PulseColors.Ink,
    strokeTop: Color = PulseColors.GlassStroke,
    strokeBottom: Color = Color.White.copy(alpha = 0.05f),
    elevation: Dp = 12.dp,
    accent: Color = Color.Transparent,
    specular: Boolean = true,
): Modifier {
    val tinted = accent != Color.Transparent
    return this
        .softShadow(corner = corner, radius = elevation)
        .clip(shape)
        .background(Brush.verticalGradient(listOf(fill, fillEnd)))
        .then(
            if (specular) {
                Modifier.drawWithCache {
                    val sweep = Brush.linearGradient(
                        colorStops = arrayOf(
                            0.0f to Color.White.copy(alpha = 0.028f),
                            0.28f to Color.White.copy(alpha = 0.008f),
                            0.55f to Color.Transparent,
                            1.0f to Color.Transparent,
                        ),
                        start = Offset.Zero,
                        end = Offset(size.width * 0.9f, size.height * 1.6f),
                    )
                    onDrawBehind { drawRect(sweep) }
                }
            } else {
                Modifier
            },
        )
        .border(
            BorderStroke(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    listOf(
                        if (tinted) accent.copy(alpha = 0.70f) else strokeTop,
                        if (tinted) accent.copy(alpha = 0.16f) else strokeBottom,
                    ),
                ),
            ),
            shape,
        )
}

/** A brighter variant for interactive surfaces (fields, chips, buttons). */
fun Modifier.glassInteractive(
    shape: Shape = RoundedCornerShape(PulseRadii.field),
    corner: Dp = PulseRadii.field,
    focused: Boolean = false,
    accent: Color = PulseColors.Aqua,
    error: Boolean = false,
): Modifier {
    val strokeTop = when {
        error -> PulseColors.Rose.copy(alpha = 0.75f)
        focused -> accent.copy(alpha = 0.85f)
        else -> PulseColors.GlassStroke
    }
    val strokeBottom = when {
        error -> PulseColors.Rose.copy(alpha = 0.35f)
        focused -> accent.copy(alpha = 0.30f)
        else -> Color.White.copy(alpha = 0.06f)
    }
    return glass(
        shape = shape,
        corner = corner,
        fill = if (focused) PulseColors.GlassFillStrong else PulseColors.GlassFill,
        fillEnd = PulseColors.Ink,
        // The stroke above already carries the error/focus tint, so no rim
        // accent — otherwise a focused field would be outlined twice.
        strokeTop = strokeTop,
        strokeBottom = strokeBottom,
        elevation = if (focused) 10.dp else 4.dp,
        specular = false,
    )
}
