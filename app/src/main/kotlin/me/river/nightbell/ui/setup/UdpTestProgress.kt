package me.river.nightbell.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import me.river.nightbell.domain.CheckProgress
import me.river.nightbell.domain.UdpPlan
import me.river.nightbell.ui.components.GlassCard
import me.river.nightbell.ui.theme.NightbellColors

/**
 * The line under Test now for a silent UDP monitor, before it is tapped.
 *
 * Said up front because the wait is the design, not a fault: a person told it
 * takes up to fifty seconds reads the wait as the app doing what it said.
 */
@Composable
internal fun UdpTestDuration(plan: UdpPlan) {
    if (!plan.silent) return
    Text(
        text = "Takes up to ${seconds(plan.worstCaseMs)}: ${plan.tries} " +
            "${if (plan.tries == 1) "try" else "tries"} of ${seconds(plan.timeoutMs)} each, then a ping.",
        style = MaterialTheme.typography.bodySmall,
        color = NightbellColors.TextTertiary,
        modifier = Modifier.testTag("udp-test-duration"),
    )
}

/**
 * Where a running UDP test has got to: one segment per try and, in silent
 * mode, one for the ping, the running one filling against its own timeout.
 *
 * The segments are the checker's real stages, reported as each one starts, and
 * only the fill inside the running one is timed here. Colour is the screen's
 * accent throughout. Nothing has passed or failed yet, so mint and rose would be
 * claims the check has not made.
 */
@Composable
internal fun UdpTestProgress(plan: UdpPlan, progress: CheckProgress?, accent: Color) {
    // Five ticks a second on the wall clock, not one per frame. A per-frame loop
    // never lets Compose go idle, which hung every UI test that touched this
    // screen, and against a fifteen second try a fifth of a second is about one
    // percent of the bar: nobody sees the steps.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(200)
        }
    }

    val stage = progress
    val pinging = stage is CheckProgress.UdpPing
    val attempt = when (stage) {
        is CheckProgress.UdpTry -> stage.attempt
        is CheckProgress.UdpPing -> plan.tries + 1
        null -> 0
    }
    val fill = when (stage) {
        is CheckProgress.UdpTry -> fraction(now - stage.sentAt, stage.timeoutMs)
        is CheckProgress.UdpPing -> fraction(now - stage.sentAt, stage.timeoutMs)
        null -> 0f
    }
    val left = stage?.let { plan.remainingMs(it, now) } ?: plan.worstCaseMs

    val eyebrow = when {
        stage == null -> "STARTING"
        pinging -> "PING"
        else -> "TRY $attempt OF ${plan.tries}"
    }
    val headline = when {
        stage == null -> "Sending the first try"
        pinging -> "Asking the host for a ping"
        plan.silent -> "Listening for a refusal"
        else -> "Waiting for a reply"
    }
    val explainer = when {
        pinging -> "Nothing refused the port. A reply here means the host is up and something holds it."
        plan.silent -> "Silence is the answer this service gives. A closed port would be refused straight away."
        else -> "No reply yet. Each try waits up to ${seconds(plan.timeoutMs)}, and the first good one ends the check."
    }

    GlassCard(accent = accent, contentPadding = 16.dp, modifier = Modifier.testTag("udp-test-progress")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = eyebrow,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.4.sp,
                color = accent,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "up to ${seconds(left)} left",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = NightbellColors.TextTertiary,
                textAlign = TextAlign.End,
            )
        }
        Spacer(Modifier.height(6.dp))
        // Its own live region, so a screen reader hears each stage once as it
        // starts, and not the countdown beside it every second.
        Text(
            text = headline,
            style = MaterialTheme.typography.titleMedium,
            color = NightbellColors.TextPrimary,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            val segments = plan.tries + if (plan.silent) 1 else 0
            for (index in 1..segments) {
                val isPing = plan.silent && index == segments
                Column(Modifier.weight(if (isPing) 0.8f else 1f)) {
                    Segment(
                        done = index < attempt,
                        running = index == attempt,
                        fill = fill,
                        accent = accent,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = if (isPing) "ping" else "$index",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = if (index <= attempt) NightbellColors.TextSecondary else NightbellColors.TextTertiary,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = explainer,
            style = MaterialTheme.typography.bodySmall,
            color = NightbellColors.TextSecondary,
        )
    }
}

@Composable
private fun Segment(done: Boolean, running: Boolean, fill: Float, accent: Color) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(NightbellColors.sheen(0.12f)),
    ) {
        val width = when {
            done -> 1f
            running -> fill
            else -> 0f
        }
        if (width > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(width)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (done) accent.copy(alpha = 0.55f) else accent),
            )
        }
    }
}

private fun fraction(elapsedMs: Long, totalMs: Long): Float =
    if (totalMs <= 0) 1f else (elapsedMs.toFloat() / totalMs).coerceIn(0f, 1f)

private fun seconds(ms: Long): String = "${(ms + 999) / 1000} s"
