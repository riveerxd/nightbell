package me.river.nightbell.ui.setup

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import me.river.nightbell.domain.IpFamily
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.NetworkTarget
import me.river.nightbell.domain.Validation
import me.river.nightbell.ui.SetupViewModel
import me.river.nightbell.ui.components.ChipSelector
import me.river.nightbell.ui.components.GlassCard
import me.river.nightbell.ui.components.GlassField
import me.river.nightbell.ui.components.SectionHeader
import me.river.nightbell.ui.components.StepperRow
import me.river.nightbell.ui.icons.NightbellIcons
import me.river.nightbell.ui.theme.NightbellColors

/**
 * Ports people actually watch, as shortcuts under the port field. Named, because
 * "5432" is a number to most people and "5432 Postgres" is a decision.
 */
private val commonPorts = listOf(
    22 to "SSH",
    80 to "HTTP",
    443 to "HTTPS",
    3306 to "MySQL",
    5432 to "Postgres",
    6379 to "Redis",
    3389 to "RDP",
)

/** Host, then which family when the host is a name, then the port for a port check. */
@Composable
internal fun NetworkTargetCard(
    viewModel: SetupViewModel,
    draft: Monitor,
    report: Validation.Report,
    accent: Color,
) {
    val tcp = draft.kind == MonitorKind.TCP_PORT
    val udp = draft.kind == MonitorKind.UDP_PORT
    val port = tcp || udp
    GlassField(
        value = draft.network.host,
        onValueChange = viewModel::setHost,
        onBlur = { viewModel.tidyHost() },
        label = "Host",
        placeholder = if (port) "db.example.com or 192.0.2.10" else "192.0.2.10, 2001:db8::1 or a name",
        note = report.of(Validation.Field.HOST),
        helper = if (port) {
            "Pasting host:port or a URL works too."
        } else {
            "An IPv4 or IPv6 address, or a name to look up."
        },
        leadingIcon = NightbellIcons.Globe,
        accent = accent,
        keyboardType = KeyboardType.Uri,
        holdErrorUntilBlur = true,
        modifier = Modifier.testTag("network-host"),
    )
    // Only for a name. A literal is the family it is written in, and chips that
    // could be set against it would be a control that does nothing.
    if (draft.network.isName) {
        SectionHeader("Address family", icon = NightbellIcons.Layers, accent = accent)
        ChipSelector(
            options = IpFamily.entries.toList(),
            selected = draft.network.family,
            onSelect = { value -> viewModel.updateNetwork { it.copy(family = value) } },
            label = { it.label },
            accent = accent,
        )
        Text(
            text = when (draft.network.family) {
                IpFamily.ANY -> "Whichever address the name resolves to first."
                IpFamily.V4 -> "Only the name's IPv4 address. Down if it has none."
                IpFamily.V6 -> "Only the name's IPv6 address. Down if it has none, or if this network has no IPv6."
            },
            style = MaterialTheme.typography.bodySmall,
            color = NightbellColors.TextTertiary,
        )
    } else {
        NetworkTarget.literalFamily(draft.network.cleanHost)?.let { family ->
            Text(
                text = "${family.label} address. Checked over ${family.label} only.",
                style = MaterialTheme.typography.bodySmall,
                color = NightbellColors.TextTertiary,
            )
        }
    }
    if (port) {
        GlassField(
            value = if (draft.network.port > 0) draft.network.port.toString() else "",
            onValueChange = viewModel::setPort,
            label = "Port",
            placeholder = if (udp) draft.udp.probe.defaultPort.takeIf { it > 0 }?.toString() ?: "53" else "443",
            note = report.of(Validation.Field.PORT),
            leadingIcon = if (udp) NightbellIcons.Datagram else NightbellIcons.Plug,
            accent = accent,
            keyboardType = KeyboardType.Number,
            holdErrorUntilBlur = true,
            modifier = Modifier.testTag("network-port"),
        )
        val chips = if (udp) commonUdpPorts else commonPorts
        ChipSelector(
            options = chips,
            selected = chips.firstOrNull { it.first == draft.network.port } ?: (0 to ""),
            onSelect = { (number, _) -> if (udp) viewModel.pickUdpPort(number) else viewModel.setPort(number.toString()) },
            label = { (number, name) -> "$number $name" },
            accent = accent,
        )
    }
}

/**
 * What counts as up. For a ping that is a number of packets and what losing some
 * of them means; for a port there is nothing to set, and the card says so rather
 * than leaving a step that looks unfinished.
 */
@Composable
internal fun NetworkExpectationCard(
    viewModel: SetupViewModel,
    draft: Monitor,
    report: Validation.Report,
    accent: Color,
) {
    if (draft.kind == MonitorKind.UDP_PORT) {
        UdpExpectationCard(viewModel, draft, report, accent)
    } else if (draft.kind == MonitorKind.PING) {
        SectionHeader("Echo requests", icon = NightbellIcons.Echo, accent = accent)
        StepperRow(
            title = "Packets per check",
            value = draft.network.packets,
            onValueChange = { v -> viewModel.updateNetwork { it.copy(packets = v) } },
            range = 1..Validation.NETWORK_MAX_PACKETS,
            icon = NightbellIcons.Echo,
            accent = accent,
            note = report.of(Validation.Field.PACKETS),
            modifier = Modifier.testTag("network-packets"),
        )
        GlassCard(Modifier.fillMaxWidth(), contentPadding = 16.dp) {
            Text(
                text = "What counts as up",
                style = MaterialTheme.typography.titleSmall,
                color = NightbellColors.TextPrimary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (draft.network.packets == 1) {
                    "Up when the reply comes back, down when it does not. With one packet, a single " +
                        "lost reply is an outage."
                } else {
                    "Up when every reply comes back. Degraded when some are lost, down when all " +
                        "${draft.network.packets} are. They go out one second apart, and each waits " +
                        "for its reply up to the timeout."
                },
                style = MaterialTheme.typography.bodySmall,
                color = NightbellColors.TextSecondary,
            )
        }
    } else {
        GlassCard(Modifier.fillMaxWidth().testTag("network-tcp-explainer"), contentPadding = 16.dp) {
            Text(
                text = "Up when the port accepts a connection.",
                style = MaterialTheme.typography.titleSmall,
                color = NightbellColors.TextPrimary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Nothing is sent over it, and it is closed straight away. A refused connection " +
                    "or no answer within the timeout is down. The time to connect is what the " +
                    "latency budget judges.",
                style = MaterialTheme.typography.bodySmall,
                color = NightbellColors.TextSecondary,
            )
        }
    }
}
