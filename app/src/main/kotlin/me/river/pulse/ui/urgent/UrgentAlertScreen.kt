package me.river.pulse.ui.urgent

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.river.pulse.ui.icons.PulseIcons
import me.river.pulse.ui.theme.PulseColors

/**
 * Everything a full-screen page needs to say, with no Android types in it.
 *
 * Deliberately flat and pre-formatted: the alert surface can be shown from a
 * full-screen intent while the process has just started for it, so it must not
 * have to reach into the store to render.
 */
data class UrgentAlertUi(
    val monitorName: String,
    val url: String,
    /** One-line verdict, e.g. "Connection refused". */
    val headline: String,
    /** Longer evidence, shown where a variant has room for it. */
    val detail: String = "",
    val statusCode: Int = 0,
    val lastLatencyMs: Long = 0L,
    /** How long the monitor has been failing, in millis. */
    val downForMs: Long = 0L,
    val failedChecks: Int = 1,
    /** 0 for the first page, then 1, 2, 3… */
    val reminderNumber: Int = 0,
    val repeatMinutes: Int = 5,
)

/** The five candidate looks for the page. One of these ships. */
enum class UrgentAlertVariant { KLAXON, CALL, INCIDENT, BEACON, BRIEF }

/**
 * The full-screen URGENT page.
 *
 * @param animate off for screenshots and for reduced motion — an infinite
 *   animation never lets the test clock idle, and the design has to read
 *   without motion anyway.
 */
@Composable
fun UrgentAlertScreen(
    variant: UrgentAlertVariant,
    ui: UrgentAlertUi,
    onAcknowledge: () -> Unit,
    onOpen: () -> Unit,
    onRecheck: () -> Unit,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
) {
    when (variant) {
        UrgentAlertVariant.KLAXON -> Klaxon(ui, onAcknowledge, onOpen, modifier, animate)
        UrgentAlertVariant.CALL -> CallStyle(ui, onAcknowledge, onOpen, modifier, animate)
        UrgentAlertVariant.INCIDENT -> Incident(ui, onAcknowledge, onRecheck, modifier)
        UrgentAlertVariant.BEACON -> Beacon(ui, onAcknowledge, onOpen, modifier, animate)
        UrgentAlertVariant.BRIEF -> Brief(ui, onAcknowledge, onRecheck, modifier)
    }
}

// ---- 1 · KLAXON ------------------------------------------------------------
// Full-bleed red, black type, no ornament. The loudest thing the platform will
// let us draw: if this is on your lockscreen you are not going to mistake it
// for anything else.

@Composable
private fun Klaxon(
    ui: UrgentAlertUi,
    onAcknowledge: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier,
    animate: Boolean,
) {
    val phase = pulsePhase(animate, periodMs = 1_100)
    Box(
        modifier
            .fillMaxSize()
            .background(PulseColors.Rose),
    ) {
        // Sweeping siren wash, brightest at the top where the notification
        // shade would have come from.
        Canvas(Modifier.fillMaxSize()) {
            val radius = size.height * (0.55f + 0.25f * phase)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0x33FFFFFF), Color(0x00FFFFFF)),
                    center = androidx.compose.ui.geometry.Offset(size.width / 2f, 0f),
                    radius = radius,
                ),
                radius = radius,
                center = androidx.compose.ui.geometry.Offset(size.width / 2f, 0f),
            )
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 26.dp, vertical = 34.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    PulseIcons.Warning,
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(9.dp))
                Text(
                    "URGENT MONITOR",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Black,
                    letterSpacing = 2.2.sp,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    downFor(ui.downForMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xCC000000),
                    letterSpacing = 1.4.sp,
                )
            }

            Spacer(Modifier.weight(1f))

            Text(
                ui.monitorName,
                style = MaterialTheme.typography.displayLarge,
                fontSize = 46.sp,
                lineHeight = 48.sp,
                fontWeight = FontWeight.Black,
                color = Color.Black,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "IS DOWN",
                style = MaterialTheme.typography.displayLarge,
                fontSize = 46.sp,
                lineHeight = 50.sp,
                fontWeight = FontWeight.Black,
                color = Color(0x59000000),
            )
            Spacer(Modifier.height(20.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0x1F000000))
                    .padding(horizontal = 14.dp, vertical = 11.dp),
            ) {
                Text(
                    ui.headline,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.Black,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                ui.url,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0x99000000),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(18.dp))
            Text(
                buildString {
                    append("${ui.failedChecks} failed checks")
                    append(" · ")
                    append(if (ui.statusCode > 0) "HTTP ${ui.statusCode}" else "no response")
                    if (ui.reminderNumber > 0) append(" · reminder ${ui.reminderNumber}")
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color(0x99000000),
            )

            Spacer(Modifier.weight(1f))

            FlatButton(
                label = "Acknowledge",
                fill = Color.Black,
                content = Color.White,
                onClick = onAcknowledge,
            )
            Spacer(Modifier.height(10.dp))
            FlatButton(
                label = "Open Pulse",
                fill = Color.Transparent,
                content = Color.Black,
                border = Color(0x59000000),
                onClick = onOpen,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                repeatFooter(ui),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0x99000000),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ---- 2 · CALL --------------------------------------------------------------
// The literal ask: it looks like somebody is ringing you. Same geometry an
// incoming call uses — avatar, name, subtitle, two round buttons at the bottom
// — so the muscle memory of "answer this now" transfers.

@Composable
private fun CallStyle(
    ui: UrgentAlertUi,
    onAcknowledge: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier,
    animate: Boolean,
) {
    val phase = pulsePhase(animate, periodMs = 1_400)
    Box(
        modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to Color(0xFF2A0308),
                    0.45f to Color(0xFF12080A),
                    1f to PulseColors.Void,
                ),
            ),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 26.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "PULSE · URGENT",
                style = MaterialTheme.typography.labelSmall,
                color = PulseColors.Rose,
                letterSpacing = 2.4.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Service not responding",
                style = MaterialTheme.typography.bodyMedium,
                color = PulseColors.TextTertiary,
            )

            Spacer(Modifier.weight(0.9f))

            // Avatar with the same expanding halo a call screen uses.
            Box(contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(210.dp)) {
                    val base = size.minDimension / 2f * 0.46f
                    listOf(0f, 0.33f, 0.66f).forEach { offset ->
                        val p = (phase + offset) % 1f
                        drawCircle(
                            color = PulseColors.Rose.copy(alpha = 0.30f * (1f - p)),
                            radius = base + (size.minDimension / 2f - base) * p,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()),
                        )
                    }
                }
                Box(
                    Modifier
                        .size(104.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1A0509))
                        .border(2.dp, PulseColors.Rose, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        PulseIcons.Server,
                        contentDescription = null,
                        tint = PulseColors.Rose,
                        modifier = Modifier.size(44.dp),
                    )
                }
            }

            Spacer(Modifier.height(26.dp))
            Text(
                ui.monitorName,
                style = MaterialTheme.typography.headlineLarge,
                fontSize = 30.sp,
                color = PulseColors.TextPrimary,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "${ui.headline} · ${ui.failedChecks} failed checks",
                style = MaterialTheme.typography.bodyLarge,
                color = PulseColors.TextSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "down for ${downFor(ui.downForMs)}",
                style = MaterialTheme.typography.bodyMedium,
                color = PulseColors.TextTertiary,
            )

            Spacer(Modifier.weight(1.1f))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                CallAction(
                    label = "Acknowledge",
                    fill = PulseColors.Rose,
                    icon = PulseIcons.Check,
                    onClick = onAcknowledge,
                )
                CallAction(
                    label = "Open",
                    fill = Color(0xFF1F1F1F),
                    icon = PulseIcons.ArrowRight,
                    onClick = onOpen,
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                repeatFooter(ui),
                style = MaterialTheme.typography.bodySmall,
                color = PulseColors.TextTertiary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun CallAction(
    label: String,
    fill: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(74.dp)
                .clip(CircleShape)
                .background(fill)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = Color.White,
                modifier = Modifier.size(30.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = PulseColors.TextSecondary,
        )
    }
}

// ---- 3 · INCIDENT ----------------------------------------------------------
// For the 3am case where the question is not "is something wrong" but "what
// exactly". Everything needed to decide whether to get out of bed, on one
// screen, in a monospace grid.

@Composable
private fun Incident(
    ui: UrgentAlertUi,
    onAcknowledge: () -> Unit,
    onRecheck: () -> Unit,
    modifier: Modifier,
) {
    Row(
        modifier
            .fillMaxSize()
            .background(PulseColors.Void),
    ) {
        Box(
            Modifier
                .width(7.dp)
                .fillMaxHeight()
                .background(
                    Brush.verticalGradient(
                        listOf(PulseColors.Rose, PulseColors.Rose.copy(alpha = 0.25f)),
                    ),
                ),
        )
        Column(
            Modifier
                .fillMaxSize()
                .padding(start = 22.dp, end = 22.dp, top = 36.dp, bottom = 32.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "INCIDENT · OPEN",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = PulseColors.Rose,
                    letterSpacing = 2.sp,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (ui.reminderNumber > 0) "PAGE #${ui.reminderNumber + 1}" else "FIRST PAGE",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = PulseColors.TextTertiary,
                    letterSpacing = 1.6.sp,
                )
            }
            Spacer(Modifier.height(22.dp))
            Text(
                ui.monitorName,
                style = MaterialTheme.typography.displayMedium,
                fontSize = 32.sp,
                lineHeight = 36.sp,
                color = PulseColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                ui.headline.uppercase(),
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                color = PulseColors.Rose,
            )

            Spacer(Modifier.height(28.dp))

            FactRow("DOWN FOR", downFor(ui.downForMs))
            FactRow("FAILED CHECKS", "${ui.failedChecks} consecutive")
            FactRow("HTTP", if (ui.statusCode > 0) ui.statusCode.toString() else "no response")
            FactRow("LAST LATENCY", if (ui.lastLatencyMs > 0) "${ui.lastLatencyMs} ms" else "—")
            FactRow("TARGET", ui.url)

            if (ui.detail.isNotBlank()) {
                Spacer(Modifier.height(20.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF0F0F0F))
                        .padding(14.dp),
                ) {
                    Text(
                        ui.detail,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = PulseColors.TextSecondary,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            FlatButton(
                label = "Acknowledge",
                fill = PulseColors.Rose,
                content = Color.White,
                onClick = onAcknowledge,
            )
            Spacer(Modifier.height(10.dp))
            FlatButton(
                label = "Re-check now",
                fill = Color.Transparent,
                content = PulseColors.TextPrimary,
                border = PulseColors.GlassStroke,
                onClick = onRecheck,
            )
        }
    }
}

@Composable
private fun FactRow(label: String, value: String) {
    Column {
        Row(
            Modifier.padding(vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = PulseColors.TextTertiary,
                letterSpacing = 1.2.sp,
                modifier = Modifier.width(126.dp),
            )
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = PulseColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color(0x14FFFFFF)),
        )
    }
}

// ---- 4 · BEACON ------------------------------------------------------------
// The quiet one. A single red pulse on black — reads instantly from across a
// dark room without shouting a wall of text at someone who just woke up.

@Composable
private fun Beacon(
    ui: UrgentAlertUi,
    onAcknowledge: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier,
    animate: Boolean,
) {
    val phase = pulsePhase(animate, periodMs = 1_800)
    Box(
        modifier
            .fillMaxSize()
            .background(PulseColors.Void),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 30.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(0.8f))
            // The halo is laid out rather than drawn behind the whole screen, so
            // it can never end up underneath the text on a short display.
            Canvas(Modifier.size(230.dp)) {
                val centre = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
                val max = size.minDimension / 2f
                listOf(0f, 0.5f).forEach { offset ->
                    val p = (phase + offset) % 1f
                    drawCircle(
                        color = PulseColors.Rose.copy(alpha = 0.22f * (1f - p)),
                        radius = max * (0.34f + 0.66f * p),
                        center = centre,
                    )
                }
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(PulseColors.Rose, Color(0xFF7A1420)),
                        center = centre,
                        radius = max * 0.34f,
                    ),
                    radius = max * 0.34f,
                    center = centre,
                )
            }
            Spacer(Modifier.height(34.dp))
            Text(
                "SERVICE DOWN",
                style = MaterialTheme.typography.labelSmall,
                color = PulseColors.Rose,
                letterSpacing = 4.sp,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                ui.monitorName,
                style = MaterialTheme.typography.displayMedium,
                fontSize = 34.sp,
                lineHeight = 38.sp,
                color = PulseColors.TextPrimary,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "${ui.headline} · down for ${downFor(ui.downForMs)}",
                style = MaterialTheme.typography.bodyLarge,
                color = PulseColors.TextSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.weight(1f))
            FlatButton(
                label = "Acknowledge",
                fill = PulseColors.Rose,
                content = Color.White,
                onClick = onAcknowledge,
            )
            Spacer(Modifier.height(18.dp))
            Text(
                "Open Pulse",
                style = MaterialTheme.typography.labelLarge,
                color = PulseColors.TextSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onOpen)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                repeatFooter(ui),
                style = MaterialTheme.typography.bodySmall,
                color = PulseColors.TextTertiary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ---- 5 · BRIEF -------------------------------------------------------------
// Split screen: the verdict in red above the fold, the evidence in dark below
// it. The compromise variant — as unmistakable as KLAXON at a glance, as
// informative as INCIDENT if you keep reading.

@Composable
private fun Brief(
    ui: UrgentAlertUi,
    onAcknowledge: () -> Unit,
    onRecheck: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .background(PulseColors.Void),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .weight(0.46f)
                .background(PulseColors.Rose)
                .padding(horizontal = 26.dp, vertical = 30.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    PulseIcons.Warning,
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "URGENT",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Black,
                    letterSpacing = 2.4.sp,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (ui.reminderNumber > 0) {
                        "reminder ${ui.reminderNumber}"
                    } else {
                        "first alert"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xB3000000),
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                "DOWN",
                style = MaterialTheme.typography.displayLarge,
                fontSize = 54.sp,
                lineHeight = 56.sp,
                fontWeight = FontWeight.Black,
                color = Color.Black,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                ui.monitorName,
                style = MaterialTheme.typography.headlineMedium,
                color = Color(0xE6000000),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                ui.headline,
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xB3000000),
            )
        }

        Column(
            Modifier
                .fillMaxWidth()
                .weight(0.54f)
                .padding(horizontal = 22.dp, vertical = 24.dp),
        ) {
            Row(Modifier.fillMaxWidth()) {
                Metric("DOWN FOR", downFor(ui.downForMs), Modifier.weight(1f))
                Metric("CHECKS", "${ui.failedChecks} failed", Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth()) {
                Metric(
                    "HTTP",
                    if (ui.statusCode > 0) ui.statusCode.toString() else "none",
                    Modifier.weight(1f),
                )
                Metric(
                    "LATENCY",
                    if (ui.lastLatencyMs > 0) "${ui.lastLatencyMs} ms" else "—",
                    Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                ui.url,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = PulseColors.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.weight(1f))

            FlatButton(
                label = "Acknowledge",
                fill = PulseColors.Rose,
                content = Color.White,
                onClick = onAcknowledge,
            )
            Spacer(Modifier.height(10.dp))
            FlatButton(
                label = "Re-check now",
                fill = Color.Transparent,
                content = PulseColors.TextPrimary,
                border = PulseColors.GlassStroke,
                onClick = onRecheck,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                repeatFooter(ui),
                style = MaterialTheme.typography.bodySmall,
                color = PulseColors.TextTertiary,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF101010))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = PulseColors.TextTertiary,
            letterSpacing = 1.4.sp,
        )
        Spacer(Modifier.height(5.dp))
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            color = PulseColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---- shared ----------------------------------------------------------------

/**
 * A deliberately plain 56dp action bar.
 *
 * The page is the one screen in Pulse that does *not* use the glass idiom: a
 * translucent control over a full-bleed emergency colour is harder to hit and
 * harder to read, and this is a surface someone taps half-awake.
 */
@Composable
private fun FlatButton(
    label: String,
    fill: Color,
    content: Color,
    onClick: () -> Unit,
    border: Color? = null,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(fill)
            .then(
                if (border != null) {
                    Modifier.border(1.5.dp, border, RoundedCornerShape(18.dp))
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = content,
        )
    }
}

/** 0f→1f ramp, or a fixed mid-phase when motion is off. */
@Composable
private fun pulsePhase(animate: Boolean, periodMs: Int): Float {
    if (!animate) return 0.35f
    val transition = rememberInfiniteTransition(label = "urgent-pulse")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(periodMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    ).value
}

internal fun downFor(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}

private fun repeatFooter(ui: UrgentAlertUi): String {
    val every = ui.repeatMinutes.coerceAtLeast(1)
    return "Repeats every $every min until acknowledged"
}
