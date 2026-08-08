package me.river.pulse.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Stable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.river.pulse.ui.theme.LocalNightbellMotion
import me.river.pulse.ui.theme.NightbellColors
import me.river.pulse.ui.theme.NightbellRadii
import me.river.pulse.ui.theme.glass
import kotlinx.coroutines.delay
import me.river.pulse.ui.theme.rememberLoopingFloat

/** The workhorse surface. Optionally pressable, with a springy scale response. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(NightbellRadii.card),
    corner: Dp = NightbellRadii.card,
    accent: Color = Color.Transparent,
    elevation: Dp = 12.dp,
    contentPadding: Dp = 18.dp,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val motion = LocalNightbellMotion.current
    val scale by animateFloatAsState(
        targetValue = if (pressed && motion.enabled) 0.975f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
        label = "cardScale",
    )

    Column(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .glass(shape = shape, corner = corner, elevation = elevation, accent = accent)
            .then(
                if (onClick != null) {
                    Modifier.combinedClickable(
                        interactionSource = interaction,
                        indication = ripple(color = if (accent == Color.Transparent) NightbellColors.Aqua else accent),
                        onLongClick = onLongClick,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            )
            .padding(contentPadding),
        content = content,
    )
}

/** Section heading with a hairline rule that fades out to the right. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    accent: Color = NightbellColors.Aqua,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = NightbellColors.TextSecondary,
            // Screen readers spell out ALL-CAPS strings; announce the real title.
            modifier = Modifier.semantics { contentDescription = title },
        )
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(accent.copy(alpha = 0.35f), Color.Transparent),
                    ),
                ),
        )
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

@Composable
fun GlassDivider(modifier: Modifier = Modifier, alpha: Float = 0.10f) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(
                Brush.horizontalGradient(
                    listOf(
                        Color.Transparent,
                        NightbellColors.sheen(alpha),
                        Color.Transparent,
                    ),
                ),
            ),
    )
}

/**
 * Remembers which entrance animations have already run on a screen.
 *
 * A `LazyColumn` throws an item's composition away the moment it scrolls out of
 * view, so state held with `remember` *inside* the item resets and its entrance
 * replays every time it scrolls back. This log lives above the list — the
 * nearest scope that outlives recycling — so an item animates the first time it
 * is seen and stays put on every pass after that.
 */
@Stable
class EntranceLog {
    private val played = mutableSetOf<Any>()

    fun hasPlayed(key: Any): Boolean = key in played

    fun markPlayed(key: Any) {
        played += key
    }
}

@Composable
fun rememberEntranceLog(): EntranceLog = remember { EntranceLog() }

/**
 * Staggered entrance: each item drifts up, scales in and fades on a short delay
 * derived from its index, capped so long lists never feel sluggish.
 *
 * [key] identifies the item within its screen and has to be unique per call
 * site — the entrance plays once per distinct value, and changing it replays.
 */
@Composable
fun StaggeredEntrance(
    index: Int,
    key: Any,
    log: EntranceLog,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val motion = LocalNightbellMotion.current
    // Read while composing, before the effect below records it: the first pass
    // animates, every later one — including after recycling — does not.
    val animate = remember(key) { motion.enabled && !log.hasPlayed(key) }
    var shown by remember(key) { mutableStateOf(!animate) }
    LaunchedEffect(key) {
        // Recorded up front, not after the delay, so scrolling away mid-flight
        // doesn't leave the item eligible to animate again.
        log.markPlayed(key)
        if (animate) delay(index.coerceAtMost(9) * 55L)
        shown = true
    }
    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessLow),
        label = "entrance",
    )
    Box(
        modifier.graphicsLayer {
            alpha = progress
            translationY = (1f - progress) * 46f
            scaleX = 0.94f + progress * 0.06f
            scaleY = 0.94f + progress * 0.06f
        },
    ) { content() }
}

@Composable
private fun rememberShimmerProgress(): Float {
    val value by rememberLoopingFloat(
        initialValue = -1f,
        targetValue = 2f,
        durationMillis = 1_500,
        label = "shimmerSweep",
    )
    return value
}

/** Skeleton block used while the first check is still running. */
@Composable
fun ShimmerBlock(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(10.dp),
) {
    val progress = rememberShimmerProgress()
    Box(
        modifier
            .clip(shape)
            .background(NightbellColors.sheen(0.06f))
            .let { base -> base.shimmerSweep(NightbellColors.sheen(0.14f), progress) }
    )
}

/** The travelling highlight, with its colour resolved outside the draw scope. */
private fun Modifier.shimmerSweep(tint: Color, progress: Float): Modifier =
    this
            .drawWithContent {
                drawContent()
                val width = size.width
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.Transparent,
                            tint,
                            Color.Transparent,
                        ),
                        start = Offset(progress * width - width * 0.4f, 0f),
                        end = Offset(progress * width + width * 0.4f, size.height),
                    ),
                )
            }
            .clearAndSetSemantics { }

/** Small uppercase tag used for kinds, methods and codes. */
@Composable
fun MicroTag(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = NightbellColors.TextSecondary,
    background: Color = NightbellColors.sheen(0.07f),
    icon: ImageVector? = null,
) {
    // Metadata only, deliberately not interactive.
    //
    // It is 22 dp tall and 11.5 sp — the right weight for a latency reading beside a
    // status pill, and completely wrong for a control. Using it as a button is how
    // "My order" and "Clear" ended up as things you could barely see, let alone hit.
    // Anything tappable uses NightbellButton or ChipSelector.
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(NightbellRadii.chip))
            .background(background)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(11.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

/**
 * The rounded tile that fronts a monitor row.
 *
 * [image], when supplied, replaces [icon] — used for site favicons. It is drawn
 * untinted and a little larger than the glyph it stands in for: a real logo
 * carries its own colour, and the accent tint that makes a monochrome stroke
 * icon read would destroy it.
 */
@Composable
fun IconBadge(
    icon: ImageVector,
    accent: Color,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    contentDescription: String? = null,
    image: ImageBitmap? = null,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size / 2.9f))
            .background(
                Brush.linearGradient(
                    listOf(accent.copy(alpha = 0.30f), accent.copy(alpha = 0.08f)),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = contentDescription,
                // Fit, not crop: favicons are already square-ish, and cropping a
                // wordmark logo cuts the word in half.
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(size * 0.62f)
                    .clip(RoundedCornerShape(size / 6f)),
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = accent,
                modifier = Modifier.size(size * 0.48f),
            )
        }
    }
}

/** Local content colour helper so callers can theme icon rows in one place. */
@Composable
fun ProvideMutedContent(content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalContentColor provides NightbellColors.TextSecondary,
        content = content,
    )
}
