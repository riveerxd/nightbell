package me.river.nightbell.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.river.nightbell.domain.Health
import me.river.nightbell.domain.ThemeChoice
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/**
 * One palette, resolved per scheme.
 *
 * Nightbell was dark-only, and not by a flag — [NightbellTheme] took a `darkTheme`
 * parameter and suppressed it. The reason was real: the glass system is built on
 * depth, and depth here is drawn as *white at a low alpha over black*, which has
 * no meaning on a light surface. A light mode is therefore not an inversion, it is
 * a second set of answers to the same questions, which is why every one of those
 * hand-tuned white overlays now goes through [sheen] instead of naming a colour.
 *
 * Health colours are the interesting part. They cannot simply carry over: mint
 * `#2FD98A` on white has a contrast ratio of about 1.9, so "operational" would be
 * legible in one scheme and invisible in the other. The light scheme keeps each
 * status's *hue* and darkens it until it passes, so a red still reads as red and
 * still reads at all.
 */
@Immutable
class NightbellColorScheme(
    /** True when this scheme paints light-on-dark. */
    val isDark: Boolean,

    val Void: Color,
    val Ink: Color,
    val Slate: Color,

    // Brand. One blue family — chrome, controls and per-monitor identity.
    //
    // Briefly this was green, to match the mark. That went too far: with chrome and
    // status sharing one hue, a button, a chip and a healthy monitor were all the same
    // colour, and "green" stopped meaning anything in particular. Blue is the app's
    // colour; green is reserved for the thing it measures.
    //
    // What that means in practice is that charts do *not* take their colour from here
    // — see [Mint].
    val Aqua: Color,
    val Violet: Color,
    val Indigo: Color,

    // Status. These carry *meaning*, so they must never be folded into the brand
    // blue: a monitor that is down has to read as red from across the room, and
    // "degraded" has to be distinguishable from both.

    /**
     * Operational. "Up", and the colour data is drawn in.
     *
     * Doing double duty on purpose: a latency line is a picture of something working,
     * so drawing it in the brand blue said nothing, while drawing it green makes the
     * chart agree with the orb, the pill and the mark above it. [Sparkline] and
     * [LatencyBars] default to this rather than to [Aqua] — that default is the single
     * place "charts are green" is decided.
     *
     * The failure colour still overrides it per sample: a chart bleeds to [Rose] at a
     * failed check, which is the whole reason the line is worth looking at.
     */
    val Mint: Color,
    val Amber: Color,

    /**
     * Stars, and nothing else.
     *
     * Its own colour rather than [Amber] because amber means *degraded* everywhere
     * else in this app, and a repository's star count is not a warning about
     * anything. Keeping them apart is what lets the star glyph be gold without a
     * repo card reading as a monitor in trouble.
     */
    val Gold: Color,
    val Rose: Color,
    val Sky: Color,
    val Coral: Color,

    val TextPrimary: Color,
    val TextSecondary: Color,
    val TextTertiary: Color,

    val ToastFill: Color,

    /**
     * The toast's own fill, and the reason it is not [ToastFill].
     *
     * [ToastFill] is `#171717`, which is nine per cent lighter than the black the
     * app is painted on. Over a card it was invisible, over the aurora it was
     * invisible, and the one report that started this said the confirmation
     * "splits into the app". That value is still right for the two places it is
     * left in: the pull puck and the icon well are meant to sit *in* the surface
     * under them. A message that arrives unasked has to sit clearly above it.
     *
     * Dark in both schemes, which is the one place Nightbell gives up on
     * following the scheme it is in. A white capsule on `#F3F4F7` has exactly the
     * same problem as `#171717` on black, and every other answer to it needs the
     * toast to carry its own text and status palette anyway. So it does: the
     * light scheme's value is a shade *below* the page instead of above it, the
     * text is white in both, and [me.river.nightbell.ui.components.ToastHost]
     * takes its accents off the dark anchors regardless of scheme, because the
     * darkened mint and amber were picked to pass against white and do not pass
     * against this.
     */
    val ToastSurface: Color,
    val SheetScrim: Color,

    val GlassFill: Color,
    val GlassFillStrong: Color,
    val GlassStroke: Color,
    val GlassStrokeSoft: Color,

    /**
     * Multiplier applied to [sheen] alphas.
     *
     * Black over a light surface reads considerably stronger than white over a
     * black one at the same alpha, so the light scheme needs *less* of it to land
     * at the same perceived weight, not more. Tuned by eye against the dark
     * original rather than derived, because the goal is matching how heavy an edge
     * looks, and no formula gets that right.
     */
    val sheenScale: Float,

    /**
     * Multiplier applied to [Modifier.softShadow] strength.
     *
     * A cast shadow is nearly free on black and very loud on off-white. Same
     * geometry, less of it.
     */
    val shadowScale: Float,
) {
    /**
     * The "raised surface" overlay: a hairline, a control fill, a specular sweep.
     *
     * Every one of these was `Color.White.copy(alpha = …)` inline, which is the
     * single thing that made a light scheme impossible — 45 separate hard-coded
     * assumptions that the surface underneath was black.
     */
    fun sheen(alpha: Float): Color =
        if (isDark) {
            Color.White.copy(alpha = alpha)
        } else {
            Color.Black.copy(alpha = (alpha * sheenScale).coerceIn(0f, 1f))
        }

    /**
     * Per-monitor accent pairs. Deliberately monochrome — monitor identity is
     * carried by name and icon, and colour is reserved for health, so a red card
     * always means one thing.
     */
    val accentPairs: List<Pair<Color, Color>> get() = listOf(Aqua to Indigo)
}

val NightbellDarkColors = NightbellColorScheme(
    isDark = true,
    Void = Color(0xFF000000),
    Ink = Color(0xFF090909),
    Slate = Color(0xFF141414),
    Aqua = Color(0xFF2F6BFF),
    Violet = Color(0xFF2F6BFF),
    Indigo = Color(0xFF1647C7),
    Mint = Color(0xFF2FD98A),
    Amber = Color(0xFFFFB020),
    Gold = Color(0xFFFFC53D),
    Rose = Color(0xFFFF4D57),
    Sky = Color(0xFF6AA8FF),
    Coral = Color(0xFFFF7A59),
    TextPrimary = Color(0xFFFFFFFF),
    TextSecondary = Color(0xFFD6D6D6),
    TextTertiary = Color(0xFF8A8A8A),
    // Opaque on purpose: the toast capsule floats over cards and charts, and a
    // translucent fill lets whatever it covers show through as noise.
    ToastFill = Color(0xFF171717),
    // Lifted well clear of the black ground and of a card's #0A0A0A. Slightly
    // cool, so it does not read as a lighter patch of the same grey.
    ToastSurface = Color(0xFF22232A),
    // Painted over a real backdrop blur. Translucent enough to see the blur move
    // underneath, opaque enough that body text on top never fights it.
    SheetScrim = Color(0xD40C0C0C),
    GlassFill = Color(0xF20A0A0A),
    GlassFillStrong = Color(0xFF111111),
    GlassStroke = Color(0x40FFFFFF),
    GlassStrokeSoft = Color(0x22FFFFFF),
    sheenScale = 1f,
    shadowScale = 1f,
)

/**
 * The light scheme.
 *
 * Depth inverts rather than translating: on black, a card is *lighter* than its
 * background and lifts itself with a white top edge; on off-white, the card is the
 * white one and the background is the tinted one, with the edge doing the lifting
 * in the opposite direction. That is why [Void] is not white here — a white card
 * on a white page has nothing to be raised out of.
 */
val NightbellLightColors = NightbellColorScheme(
    isDark = false,
    Void = Color(0xFFF3F4F7),
    Ink = Color(0xFFFFFFFF),
    Slate = Color(0xFFE8EAEF),
    // Darkened until they pass on white while staying recognisably the same blue.
    Aqua = Color(0xFF1B4FD9),
    Violet = Color(0xFF1B4FD9),
    Indigo = Color(0xFF12379B),
    // Hue preserved, luminance dropped to clear 4.5:1 against Void and Ink.
    Mint = Color(0xFF07834B),
    Amber = Color(0xFF8A5200),
    // Same treatment as the rest of the light scheme: hue kept, luminance dropped
    // until it clears 4.5:1 on white. A gold that stays gold on paper does not
    // exist, and an unreadable glyph is worse than a dark one.
    Gold = Color(0xFF7A5600),
    Rose = Color(0xFFC4111F),
    Sky = Color(0xFF1D4FD8),
    Coral = Color(0xFFB63A18),
    TextPrimary = Color(0xFF0B0D12),
    TextSecondary = Color(0xFF3C424E),
    // 5.3:1 on Void — the old #8A8A8A would have been 2.5:1 and unreadable.
    TextTertiary = Color(0xFF5E6573),
    ToastFill = Color(0xFFFFFFFF),
    ToastSurface = Color(0xFF14161B),
    SheetScrim = Color(0xD9F7F8FA),
    GlassFill = Color(0xF7FFFFFF),
    GlassFillStrong = Color(0xFFFFFFFF),
    GlassStroke = Color(0x1F000000),
    GlassStrokeSoft = Color(0x12000000),
    sheenScale = 0.85f,
    shadowScale = 0.5f,
)

val LocalNightbellColors = staticCompositionLocalOf { NightbellDarkColors }

/**
 * The active palette.
 *
 * A composable property so every existing `NightbellColors.Rose` call site keeps
 * reading, and now reads the scheme in force rather than a compile-time constant.
 */
val NightbellColors: NightbellColorScheme
    @Composable
    @ReadOnlyComposable
    get() = LocalNightbellColors.current

@Composable
@ReadOnlyComposable
fun accentFor(index: Int): Pair<Color, Color> {
    val pairs = NightbellColors.accentPairs
    return pairs[((index % pairs.size) + pairs.size) % pairs.size]
}

@Composable
@ReadOnlyComposable
fun healthColor(health: Health): Color = when (health) {
    Health.UP -> NightbellColors.Mint
    Health.DOWN -> NightbellColors.Rose
    Health.DEGRADED -> NightbellColors.Amber
    Health.PAUSED -> NightbellColors.TextTertiary
    Health.UNKNOWN -> NightbellColors.Sky
}

/**
 * Where an uptime reading sits on the red to green ramp.
 *
 * Off the palette rather than off fixed hex, so the light scheme's darkened
 * anchors are the ones its dial uses. The work is in [uptimeRamp].
 */
@Composable
@ReadOnlyComposable
fun uptimeColor(percent: Float): Color = uptimeRamp(
    rose = NightbellColors.Rose,
    amber = NightbellColors.Amber,
    mint = NightbellColors.Mint,
    percent = percent,
)

/**
 * Rose at 0, amber at 50, mint from 70 up.
 *
 * Three things here are deliberate and all three were arrived at by drawing the
 * dial and looking at it, so changing one of them means doing that again.
 *
 * **Red holds.** The lower half is weighted [RED_HOLD] rather than linear. A
 * monitor at 18% spent twenty hours of the day unreachable, and a straight
 * interpolation had already turned that orange.
 *
 * **The top of the range is one colour.** Everything from [MINT_FROM] up is
 * exactly mint, the colour the rest of the app uses for a monitor that is up.
 * The alternative, a ramp that keeps travelling to 100, spends its last stretch
 * in yellow-greens, and a dial at 74% came out a vivid grass green that had
 * nothing to do with the mint at 100 two steps along from it. Length says how
 * good a good reading is; colour only says which of the three it is.
 *
 * **The fade between them crosses Oklab, not the hue wheel.** Rotating the hue
 * from amber to mint goes the long way round through chartreuse, which is the
 * loud green that was wrong. A straight line between the two in Oklab passes
 * through muted golds instead, and weighting it [YELLOW_HOLD] holds it amber
 * until the last few points of the band, so what a person sees is yellow turning
 * into green and not a light green stage of its own.
 *
 * The lower half does rotate the hue, because there the wheel is going the right
 * way anyway: rose to amber through orange is exactly the arc between them.
 */
internal fun uptimeRamp(rose: Color, amber: Color, mint: Color, percent: Float): Color {
    val value = percent.coerceIn(0f, 100f)
    return when {
        value >= MINT_FROM -> mint
        value <= 50f -> aroundTheWheel(rose, amber, (value / 50f).pow(RED_HOLD))
        else -> straightAcross(amber, mint, ((value - 50f) / (MINT_FROM - 50f)).pow(YELLOW_HOLD))
    }
}

/** The reading at which the dial is simply mint, and stays mint. */
private const val MINT_FROM = 70f

/** Weighting of the red half. Above 1 keeps it red for longer. */
private const val RED_HOLD = 2.2f

/**
 * Weighting of the fade into mint. Above 1 keeps it amber for longer.
 *
 * At 4 the band is amber to about 64 and green by 68, so the sage in between is
 * two or three readings wide rather than ten. Lower values spread it out, and
 * spread out it reads as a light green stage of its own, which is the thing this
 * ramp is not allowed to have.
 */
private const val YELLOW_HOLD = 4f

/**
 * Interpolates by rotating the hue, keeping the chroma the anchors were chosen
 * with. The short way round: rose sits just past zero, so its path to amber
 * crosses it and read literally would sweep backwards through green and blue.
 */
private fun aroundTheWheel(start: Color, end: Color, fraction: Float): Color {
    val from = start.convert(ColorSpaces.Oklab)
    val to = end.convert(ColorSpaces.Oklab)
    val fromHue = atan2(from.blue, from.green)
    var arc = atan2(to.blue, to.green) - fromHue
    if (arc > PI.toFloat()) arc -= TAU
    if (arc < -PI.toFloat()) arc += TAU
    val fromChroma = hypot(from.green, from.blue)
    val toChroma = hypot(to.green, to.blue)
    return inGamut(
        lightness = from.red + (to.red - from.red) * fraction,
        chroma = fromChroma + (toChroma - fromChroma) * fraction,
        hue = fromHue + arc * fraction,
    )
}

/** Interpolates in a straight line through Oklab, which dips in chroma rather
 * than travelling round the wheel. */
private fun straightAcross(start: Color, end: Color, fraction: Float): Color {
    val from = start.convert(ColorSpaces.Oklab)
    val to = end.convert(ColorSpaces.Oklab)
    val a = from.green + (to.green - from.green) * fraction
    val b = from.blue + (to.blue - from.blue) * fraction
    return inGamut(
        lightness = from.red + (to.red - from.red) * fraction,
        chroma = hypot(a, b),
        hue = atan2(b, a),
    )
}

/**
 * An Oklch colour, walked back into what the display can show.
 *
 * Out of gamut the conversion clamps each channel on its own, and clamping a
 * channel moves the hue, so an over-bright colour arrives as a different one
 * rather than as a duller version of what was asked for. Extended sRGB is linear
 * and unclamped, so it can be asked whether a colour fits before anything is
 * rounded, and chroma comes off in small steps until it does. Nothing on this
 * ramp currently needs more than a step or two of that, and the guard is here so
 * that a repainted palette cannot quietly bend a hue instead.
 */
private fun inGamut(lightness: Float, chroma: Float, hue: Float): Color {
    var paint = chroma
    repeat(GAMUT_STEPS) {
        val candidate = Color(
            red = lightness,
            green = paint * cos(hue),
            blue = paint * sin(hue),
            colorSpace = ColorSpaces.Oklab,
        )
        val linear = candidate.convert(ColorSpaces.LinearExtendedSrgb)
        if (linear.red in 0f..1f && linear.green in 0f..1f && linear.blue in 0f..1f) {
            return candidate.convert(ColorSpaces.Srgb)
        }
        paint *= 0.97f
    }
    return Color(
        red = lightness,
        green = paint * cos(hue),
        blue = paint * sin(hue),
        colorSpace = ColorSpaces.Oklab,
    ).convert(ColorSpaces.Srgb)
}

/** 0.97 to the fortieth is about 0.3 of the chroma asked for, which is further
 * than any anchor in this palette reaches. */
private const val GAMUT_STEPS = 40

private const val TAU = (PI * 2).toFloat()

/**
 * Rim colour for a card representing [health].
 *
 * Only the states worth interrupting someone for get a tint. If every card in
 * the list is outlined, the one that is actually broken stops standing out —
 * healthy monitors already say so with their pill and orb.
 */
@Composable
@ReadOnlyComposable
fun healthRim(health: Health): Color = when (health) {
    Health.DOWN -> NightbellColors.Rose
    Health.DEGRADED -> NightbellColors.Amber
    else -> Color.Transparent
}

@Immutable
data class NightbellMotion(
    /** 0f = reduced motion, 1f = full show-off mode. */
    val intensity: Float = 1f,
) {
    val enabled: Boolean get() = intensity > 0.05f
    fun scale(durationMs: Int): Int =
        if (!enabled) 0 else (durationMs / intensity.coerceIn(0.35f, 1.5f)).toInt()
}

val LocalNightbellMotion = staticCompositionLocalOf { NightbellMotion() }

private fun materialScheme(colors: NightbellColorScheme) = if (colors.isDark) {
    darkColorScheme(
        primary = colors.Aqua,
        onPrimary = colors.Void,
        primaryContainer = colors.Indigo,
        onPrimaryContainer = colors.TextPrimary,
        secondary = colors.Violet,
        onSecondary = colors.Void,
        tertiary = colors.Mint,
        onTertiary = colors.Void,
        background = colors.Void,
        onBackground = colors.TextPrimary,
        surface = colors.Ink,
        onSurface = colors.TextPrimary,
        surfaceVariant = colors.Slate,
        onSurfaceVariant = colors.TextSecondary,
        outline = colors.GlassStroke,
        outlineVariant = colors.GlassStrokeSoft,
        error = colors.Rose,
        onError = colors.Void,
    )
} else {
    lightColorScheme(
        primary = colors.Aqua,
        // White text on the brand blue, not near-black: `Void` is the page
        // background in both schemes, and on light that happens to be the right
        // colour for content sitting on a saturated fill.
        onPrimary = Color.White,
        primaryContainer = colors.Indigo,
        onPrimaryContainer = Color.White,
        secondary = colors.Violet,
        onSecondary = Color.White,
        tertiary = colors.Mint,
        onTertiary = Color.White,
        background = colors.Void,
        onBackground = colors.TextPrimary,
        surface = colors.Ink,
        onSurface = colors.TextPrimary,
        surfaceVariant = colors.Slate,
        onSurfaceVariant = colors.TextSecondary,
        outline = colors.GlassStroke,
        outlineVariant = colors.GlassStrokeSoft,
        error = colors.Rose,
        onError = Color.White,
    )
}

private val Sans = FontFamily.SansSerif

val NightbellTypography = Typography(
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

/** Shared radii — everything in Nightbell is generously rounded. */
object NightbellRadii {
    val card = 26.dp
    val panel = 22.dp
    val field = 16.dp
    val chip = 100.dp
    val sheet = 32.dp

    /**
     * The radius a shape needs when it sits [gap] inside a shape of radius
     * [outer], so the two curves stay parallel.
     *
     * Two nested rounded shapes read as one object inside another only when the
     * inner radius is the outer radius minus the distance between them. Get it
     * wrong and the corners pinch or splay: the inner shape looks like it was
     * dropped in rather than fitted, which is what every panel in this app looked
     * like until 3.6, because they all carried a radius picked by eye.
     *
     * A capsule gets this for free, since its radius is half its height and a
     * uniform gap therefore subtracts itself. Everything with a chosen radius has
     * to be told.
     */
    fun inside(outer: Dp, gap: Dp): Dp = (outer - gap).coerceAtLeast(4.dp)

    /** A panel inside a [card] at GlassCard's default 18 dp padding. */
    val inCard = 8.dp

    /** A panel inside a dialog [sheet] at its 20 dp padding. */
    val inSheet = 12.dp
}

@Composable
fun NightbellTheme(
    motionIntensity: Float = 1f,
    theme: ThemeChoice = ThemeChoice.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = when (theme) {
        ThemeChoice.SYSTEM -> isSystemInDarkTheme()
        ThemeChoice.DARK -> true
        ThemeChoice.LIGHT -> false
    }
    val colors = if (dark) NightbellDarkColors else NightbellLightColors
    CompositionLocalProvider(
        LocalNightbellMotion provides NightbellMotion(motionIntensity),
        LocalNightbellColors provides colors,
    ) {
        MaterialTheme(
            colorScheme = materialScheme(colors),
            typography = NightbellTypography,
            content = content,
        )
    }
}