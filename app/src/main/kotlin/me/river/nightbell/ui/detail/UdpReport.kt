package me.river.nightbell.ui.detail

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorRuntime
import me.river.nightbell.domain.UdpProbe
import me.river.nightbell.ui.components.GlassCard
import me.river.nightbell.ui.components.LabelledRow
import me.river.nightbell.ui.components.SectionHeader
import me.river.nightbell.ui.icons.NightbellIcons
import me.river.nightbell.ui.theme.NightbellColors

/**
 * What the newest UDP check read: the probe's own facts, the round trip and
 * which try answered. Reply bytes are shown in monospace, because "ff ff ff ff"
 * in a proportional face is four widths of the same thing.
 */
@Composable
internal fun UdpReadoutCard(monitor: Monitor, runtime: MonitorRuntime, accent: Color) {
    val facts = runtime.lastUdpFacts
    if (facts.isEmpty()) return
    GlassCard(Modifier.fillMaxWidth().testTag("udp-readout")) {
        SectionHeader(
            if (monitor.udp.probe == UdpProbe.SILENT) "What silent mode saw" else "${monitor.udp.probe.label} reply",
            icon = NightbellIcons.Datagram,
            accent = accent,
        )
        facts.forEach { fact ->
            val raw = fact.label == "Hex" || fact.label == "Text"
            LabelledRow(
                labelWidth = 112.dp,
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                label = { mod ->
                    Text(text = fact.label, style = MaterialTheme.typography.labelMedium, color = NightbellColors.TextTertiary, modifier = mod)
                },
                value = { mod ->
                    Text(
                        text = fact.value,
                        style = if (raw) {
                            MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                        } else {
                            MaterialTheme.typography.bodySmall
                        },
                        color = NightbellColors.TextSecondary,
                        modifier = mod,
                    )
                },
            )
        }
        if (monitor.udp.probe == UdpProbe.SILENT) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Silent mode proves nothing refused the port and the host is there. It can't see a service " +
                    "that is hung but still holding the port.",
                style = MaterialTheme.typography.bodySmall,
                color = NightbellColors.TextTertiary,
            )
        }
    }
}
