package me.river.pulse.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import me.river.pulse.ui.theme.PulseColors
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import me.river.pulse.ui.theme.rememberLoopingFloat

private data class Blob(
    val color: Color,
    val baseX: Float,
    val baseY: Float,
    val radius: Float,
    val driftX: Float,
    val driftY: Float,
    val phase: Float,
    val alpha: Float,
)

/**
 * The living backdrop: slow-drifting coloured blooms under a film of grain.
 * Everything is procedural — no bitmaps, so it stays razor sharp at any density
 * and costs a few hundred bytes instead of a few hundred kilobytes.
 */
@Composable
fun AuroraBackground(
    modifier: Modifier = Modifier,
    tint: Color = PulseColors.Aqua,
    secondary: Color = PulseColors.Violet,
    intensity: Float = 1f,
    content: @Composable BoxScope.() -> Unit,
) {
    val drift by rememberLoopingFloat(
        initialValue = 0f,
        targetValue = (2f * Math.PI).toFloat(),
        durationMillis = 26_000,
        label = "drift",
    )
    val breathe by rememberLoopingFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        durationMillis = 9_000,
        repeatMode = RepeatMode.Reverse,
        label = "breathe",
    )

    val blobs = remember(tint, secondary) {
        listOf(
            Blob(tint, 0.18f, 0.12f, 0.62f, 0.04f, 0.03f, 0f, 0.18f),
            Blob(PulseColors.Indigo, 0.86f, 0.24f, 0.58f, 0.03f, 0.04f, 1.9f, 0.13f),
            Blob(PulseColors.Aqua, 0.30f, 0.78f, 0.70f, 0.04f, 0.03f, 3.4f, 0.10f),
        )
    }
    val grain = remember {
        val rng = Random(7_411)
        List(420) { Offset(rng.nextFloat(), rng.nextFloat()) to rng.nextFloat() }
    }

    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(
                Brush.verticalGradient(
                    0f to PulseColors.Void,
                    0.55f to PulseColors.Ink,
                    1f to Color.Black,
                ),
            )
            blobs.forEach { blob -> drawBlob(blob, drift, breathe, intensity) }
            drawGrain(grain)
            // Vignette keeps the eye centred and hides blob edges.
            drawRect(
                Brush.radialGradient(
                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f)),
                    center = Offset(size.width / 2f, size.height * 0.42f),
                    radius = size.maxDimension * 0.78f,
                ),
            )
        }
        content()
    }
}

private fun DrawScope.drawBlob(blob: Blob, drift: Float, breathe: Float, intensity: Float) {
    val cx = (blob.baseX + cos(drift + blob.phase) * blob.driftX) * size.width
    val cy = (blob.baseY + sin(drift * 0.8f + blob.phase) * blob.driftY) * size.height
    val radius = blob.radius * size.minDimension * breathe
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                blob.color.copy(alpha = blob.alpha * intensity),
                blob.color.copy(alpha = blob.alpha * 0.35f * intensity),
                Color.Transparent,
            ),
            center = Offset(cx, cy),
            radius = radius,
        ),
        radius = radius,
        center = Offset(cx, cy),
    )
}

private fun DrawScope.drawGrain(points: List<Pair<Offset, Float>>) {
    points.forEach { (position, seed) ->
        drawCircle(
            color = Color.White.copy(alpha = 0.012f + seed * 0.022f),
            radius = 0.6f + seed * 0.9f,
            center = Offset(position.x * size.width, position.y * size.height),
        )
    }
}
