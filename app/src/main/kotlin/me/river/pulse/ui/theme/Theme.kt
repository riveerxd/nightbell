package me.river.pulse.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.river.pulse.domain.Health

/** Core palette. Pulse is dark-first by design — the glass only reads on depth. */
object PulseColors {
    val Void = Color(0xFF000000)
    val Ink = Color(0xFF090909)
    val Slate = Color(0xFF141414)

    // Brand. One blue family — chrome, charts and per-monitor identity.
    val Aqua = Color(0xFF2F6BFF)
    val Violet = Color(0xFF2F6BFF)
    val Indigo = Color(0xFF1647C7)

    // Status. These carry *meaning*, so they must never be folded into the brand
    // blue: a monitor that is down has to read as red from across the room, and
    // "degraded" has to be distinguishable from both.
    val Mint = Color(0xFF2FD98A) // UP / passing
    val Amber = Color(0xFFFFB020) // DEGRADED / warnings / non-2xx codes
    val Rose = Color(0xFFFF4D57) // DOWN / errors / destructive actions
    val Sky = Color(0xFF6AA8FF) // UNKNOWN — never checked yet
    val Coral = Color(0xFFFF7A59)

    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFFD6D6D6)
    val TextTertiary = Color(0xFF8A8A8A)

    // Opaque on purpose: the toast capsule floats over cards and charts, and a
    // translucent fill lets whatever it covers show through as noise.
    val ToastFill = Color(0xFF171717)

    val GlassFill = Color(0xF20A0A0A)
    val GlassFillStrong = Color(0xFF111111)
    val GlassStroke = Color(0x40FFFFFF)
    val GlassStrokeSoft = Color(0x22FFFFFF)
}

/**
 * Per-monitor accent pairs. The palette is deliberately monochrome here —
 * monitor identity is carried by name and icon, and colour is reserved for
 * health, so a red card always means one thing.
 */
val AccentPairs: List<Pair<Color, Color>> = listOf(
    PulseColors.Aqua to PulseColors.Indigo,
)

fun accentFor(index: Int): Pair<Color, Color> = AccentPairs[((index % AccentPairs.size) + AccentPairs.size) % AccentPairs.size]

fun healthColor(health: Health): Color = when (health) {
    Health.UP -> PulseColors.Mint
    Health.DOWN -> PulseColors.Rose
    Health.DEGRADED -> PulseColors.Amber
    Health.PAUSED -> PulseColors.TextTertiary
    Health.UNKNOWN -> PulseColors.Sky
}

/**
 * Rim colour for a card representing [health].
 *
 * Only the states worth interrupting someone for get a tint. If every card in
 * the list is outlined, the one that is actually broken stops standing out —
 * healthy monitors already say so with their pill and orb.
 */
fun healthRim(health: Health): Color = when (health) {
    Health.DOWN -> PulseColors.Rose
    Health.DEGRADED -> PulseColors.Amber
    else -> Color.Transparent
}

@Immutable
data class PulseMotion(
    /** 0f = reduced motion, 1f = full show-off mode. */
    val intensity: Float = 1f,
) {
    val enabled: Boolean get() = intensity > 0.05f
    fun scale(durationMs: Int): Int =
        if (!enabled) 0 else (durationMs / intensity.coerceIn(0.35f, 1.5f)).toInt()
}

val LocalPulseMotion = staticCompositionLocalOf { PulseMotion() }

private val PulseDarkScheme = darkColorScheme(
    primary = PulseColors.Aqua,
    onPrimary = PulseColors.Void,
    primaryContainer = PulseColors.Indigo,
    onPrimaryContainer = PulseColors.TextPrimary,
    secondary = PulseColors.Violet,
    onSecondary = PulseColors.Void,
    tertiary = PulseColors.Mint,
    onTertiary = PulseColors.Void,
    background = PulseColors.Void,
    onBackground = PulseColors.TextPrimary,
    surface = PulseColors.Ink,
    onSurface = PulseColors.TextPrimary,
    surfaceVariant = PulseColors.Slate,
    onSurfaceVariant = PulseColors.TextSecondary,
    outline = PulseColors.GlassStroke,
    outlineVariant = PulseColors.GlassStrokeSoft,
    error = PulseColors.Rose,
    onError = PulseColors.Void,
)

private val Sans = FontFamily.SansSerif

val PulseTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Black, fontSize = 44.sp,
        lineHeight = 48.sp, letterSpacing = (-1.4).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.ExtraBold, fontSize = 34.sp,
        lineHeight = 38.sp, letterSpacing = (-1.0).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 27.sp,
        lineHeight = 32.sp, letterSpacing = (-0.7).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 22.sp,
        lineHeight = 27.sp, letterSpacing = (-0.4).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 18.sp,
        lineHeight = 23.sp, letterSpacing = (-0.2).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 15.5.sp,
        lineHeight = 20.sp, letterSpacing = (-0.1).sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 15.sp,
        lineHeight = 21.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 13.5.sp,
        lineHeight = 19.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp,
        letterSpacing = 0.2.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 11.5.sp,
        letterSpacing = 0.6.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 10.sp,
        letterSpacing = 1.1.sp, textAlign = TextAlign.Start,
    ),
)

/** Shared radii — everything in Pulse is generously rounded. */
object PulseRadii {
    val card = 26.dp
    val panel = 22.dp
    val field = 16.dp
    val chip = 100.dp
    val sheet = 32.dp
}

@Composable
fun PulseTheme(
    motionIntensity: Float = 1f,
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalPulseMotion provides PulseMotion(motionIntensity)) {
        MaterialTheme(
            colorScheme = PulseDarkScheme,
            typography = PulseTypography,
            content = content,
        )
    }
}
