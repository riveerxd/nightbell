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
 * rounded rects costs a handful of draw ops, renders identically everywhere,
 * and lets the shadow take the card's accent colour — which is what a glass
 * surface actually wants.
 */
fun Modifier.softShadow(
    corner: Dp,
    color: Color = Color.Black,
    radius: Dp = 18.dp,
    strength: Float = 1f,
): Modifier = drawBehind {
    if (radius.value <= 0f || strength <= 0f) return@drawBehind
    val maxSpread = radius.toPx()
    val cornerPx = corner.toPx()
    // Punch the surface's own silhouette out of the bloom. Without this the
    // halo sits *behind* a translucent pane and floods it with colour.
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
        val layers = 6
        repeat(layers) { index ->
            val t = (index + 1) / layers.toFloat()
            val spread = maxSpread * t
            val alpha = (1f - t) * 0.16f * strength
            if (alpha <= 0.002f) return@repeat
            drawRoundRect(
                color = color.copy(alpha = alpha),
                topLeft = Offset(-spread * 0.45f, spread * 0.18f),
                size = Size(size.width + spread * 0.9f, size.height + spread * 0.6f),
                cornerRadius = CornerRadius(cornerPx + spread * 0.5f),
            )
        }
    }
}

/**
 * The house style: a translucent pane, a light-catching top edge, a soft
 * coloured bloom underneath, and a diagonal specular sweep. Composed as a
 * modifier so any surface can become glass.
 */
fun Modifier.glass(
    shape: Shape = RoundedCornerShape(PulseRadii.card),
    corner: Dp = PulseRadii.card,
    fill: Color = PulseColors.GlassFill,
    fillEnd: Color = PulseColors.Ink,
    strokeTop: Color = PulseColors.GlassStroke,
    strokeBottom: Color = Color.White.copy(alpha = 0.05f),
    elevation: Dp = 18.dp,
    glow: Color = Color.Transparent,
    specular: Boolean = true,
): Modifier = this
    .softShadow(
        corner = corner,
        color = if (glow == Color.Transparent) Color.Black else glow,
        radius = elevation,
        strength = if (glow == Color.Transparent) 1f else 0.85f,
    )
    .clip(shape)
    .background(Brush.verticalGradient(listOf(fill, fillEnd)))
    .then(
        if (specular) {
            Modifier.drawWithCache {
                val sweep = Brush.linearGradient(
                    colorStops = arrayOf(
                        0.0f to Color.White.copy(alpha = 0.045f),
                        0.28f to Color.White.copy(alpha = 0.012f),
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
            brush = Brush.verticalGradient(listOf(strokeTop, strokeBottom)),
        ),
        shape,
    )

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
        strokeTop = strokeTop,
        strokeBottom = strokeBottom,
        elevation = if (focused) 14.dp else 5.dp,
        glow = when {
            error -> PulseColors.Rose
            focused -> accent
            else -> Color.Transparent
        },
        specular = false,
    )
}

/** Radial bloom used behind status rings and hero numbers. */
fun Modifier.bloom(color: Color, alpha: Float = 0.35f, radiusScale: Float = 0.9f): Modifier =
    drawWithCache {
        val brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = alpha), Color.Transparent),
            center = Offset(size.width / 2f, size.height / 2f),
            radius = maxOf(size.width, size.height) * radiusScale,
        )
        onDrawBehind { drawRect(brush, size = Size(size.width, size.height)) }
    }
