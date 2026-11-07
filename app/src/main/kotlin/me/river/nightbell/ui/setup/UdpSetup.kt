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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import me.river.nightbell.domain.DnsType
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.PayloadFormat
import me.river.nightbell.domain.ReplyMatch
import me.river.nightbell.domain.UdpPayload
import me.river.nightbell.domain.UdpProbe
import me.river.nightbell.domain.UdpWatch
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
 * UDP ports people watch, as shortcuts under the port field. A chip also picks
 * the probe that port is spoken to with, and the probe chips below move with
 * it, so the coupling is seen rather than discovered.
 */
internal val commonUdpPorts = listOf(
    53 to "DNS",
    123 to "NTP",
    161 to "SNMP",
    3478 to "STUN",
    5060 to "SIP",
    51820 to "WireGuard",
    1194 to "OpenVPN",
    27015 to "Source",
    19132 to "Bedrock",
)

/** Which probe, what it needs, how many tries, and what counts as up. */
@Composable
internal fun UdpExpectationCard(
    viewModel: SetupViewModel,
    draft: Monitor,
    report: Validation.Report,
    accent: Color,
) {
    val udp = draft.udp
    SectionHeader("Probe", icon = NightbellIcons.Datagram, accent = accent)
    ChipSelector(
        options = UdpProbe.entries.toList(),
        selected = udp.probe,
        onSelect = viewModel::setUdpProbe,
        label = { it.label },
        accent = accent,
        modifier = Modifier.testTag("udp-probe"),
    )
    Hint(udp.probe.blurb)

    when (udp.probe) {
        UdpProbe.DNS -> DnsFields(viewModel, udp, report, accent)
        UdpProbe.NTP -> {
            StepperRow(
                title = "Allowed clock offset",
                value = udp.ntpMaxOffsetMs,
                onValueChange = { v -> viewModel.updateUdp { it.copy(ntpMaxOffsetMs = v) } },
                range = 0..10_000,
                step = 100,
                suffix = "ms",
                zeroLabel = "Off",
                icon = NightbellIcons.Clock,
                accent = accent,
                modifier = Modifier.testTag("udp-ntp-offset"),
            )
            Hint(
                if (udp.ntpMaxOffsetMs == 0) {
                    "The offset is shown, never judged. The phone's own clock can be the wrong one."
                } else {
                    "Degraded when the server's clock and this phone's differ by more than ${udp.ntpMaxOffsetMs} ms."
                },
            )
        }
        UdpProbe.SNMP -> {
            GlassField(
                value = viewModel.mailPasswordInput,
                onValueChange = viewModel::setMailPassword,
                label = "Community",
                placeholder = if (viewModel.mailPasswordHeld) "Saved. Type to replace it." else "",
                note = report.of(Validation.Field.COMMUNITY),
                leadingIcon = NightbellIcons.Shield,
                accent = accent,
                masked = true,
                keyboardType = KeyboardType.Password,
                holdErrorUntilBlur = true,
                modifier = Modifier.testTag("udp-community"),
            )
            Hint("Kept encrypted on this phone, never in a backup. SNMP v2c sends it unencrypted, so anyone on the path can read it.")
            GlassField(
                value = udp.snmpOid,
                onValueChange = { v -> viewModel.updateUdp { it.copy(snmpOid = v.trim()) } },
                label = "OID",
                placeholder = UdpWatch.SYS_UPTIME_OID,
                note = report.of(Validation.Field.OID),
                helper = if (udp.snmpOid == UdpWatch.SYS_UPTIME_OID) "sysUpTime, how long the device has been up." else "",
                leadingIcon = NightbellIcons.Layers,
                accent = accent,
                keyboardType = KeyboardType.Uri,
                modifier = Modifier.testTag("udp-oid"),
            )
        }
        UdpProbe.CUSTOM -> CustomFields(viewModel, udp, report, accent)
        UdpProbe.SILENT -> SilentExplainer(viewModel, udp, report, accent)
        else -> Unit
    }

    SectionHeader("Tries", icon = NightbellIcons.Datagram, accent = accent)
    StepperRow(
        title = "Tries per check",
        value = udp.attempts,
        onValueChange = { v -> viewModel.updateUdp { it.copy(attempts = v) } },
        range = 1..UdpWatch.MAX_ATTEMPTS,
        icon = NightbellIcons.Datagram,
        accent = accent,
        note = report.of(Validation.Field.ATTEMPTS),
        modifier = Modifier.testTag("udp-attempts"),
    )
    Hint("Tries one after another, a second apart. The first good reply counts, so one lost packet is not an outage.")

    GlassCard(Modifier.fillMaxWidth().testTag("udp-up-explainer"), contentPadding = 16.dp) {
        Text(
            text = "What counts as up",
            style = MaterialTheme.typography.titleSmall,
            color = NightbellColors.TextPrimary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = upText(udp),
            style = MaterialTheme.typography.bodySmall,
            color = NightbellColors.TextSecondary,
        )
    }
}

private fun upText(udp: UdpWatch): String {
    val closed = "A refusal from the host is down: nothing listens on that port."
    return when (udp.probe) {
        UdpProbe.DNS -> "Up when the server answers this query with NOERROR or NXDOMAIN. SERVFAIL is degraded. " +
            "REFUSED, or no answer after every try, is down."
        UdpProbe.NTP -> "Up when the server answers with the time and knows it itself. An unsynchronized server " +
            "is down, a kiss-o'-death is degraded. No answer after every try is down."
        UdpProbe.STUN -> "Up when the binding request is answered. An error response or no answer is down."
        UdpProbe.SNMP -> "Up when the agent returns the value. A wrong community gets no answer at all from most " +
            "agents, so it shows as no reply."
        UdpProbe.SIP -> "Up when any SIP response comes back, 401 and 405 included: the server is speaking SIP. " +
            "No answer after every try is down."
        UdpProbe.QUIC -> "Up when the server answers with the QUIC versions it speaks. No answer after every try is down."
        UdpProbe.SOURCE_QUERY -> "Up when the server answers A2S_INFO. No answer after every try is down."
        UdpProbe.BEDROCK -> "Up when the server answers the ping with its MOTD. No answer after every try is down."
        UdpProbe.CUSTOM -> if (udp.expect == ReplyMatch.ANY) {
            "Up when anything comes back. No answer after every try is down. $closed"
        } else {
            "Up when the reply ${udp.expect.label.lowercase()} what is set above. Any other reply, or none, is down."
        }
        UdpProbe.SILENT -> "Up when nothing refuses the datagram and the host answers ping. Down when the host refuses " +
            "it, or when the host doesn't answer ping either."
    } + if (udp.probe != UdpProbe.CUSTOM) " $closed" else ""
}

@Composable
private fun DnsFields(viewModel: SetupViewModel, udp: UdpWatch, report: Validation.Report, accent: Color) {
    GlassField(
        value = udp.dnsName,
        onValueChange = { v -> viewModel.updateUdp { it.copy(dnsName = v.trim()) } },
        label = "Name to ask for",
        placeholder = "example.com",
        note = report.of(Validation.Field.DNS_NAME),
        leadingIcon = NightbellIcons.Globe,
        accent = accent,
        keyboardType = KeyboardType.Uri,
        holdErrorUntilBlur = true,
        modifier = Modifier.testTag("udp-dns-name"),
    )
    ChipSelector(
        options = DnsType.entries.toList(),
        selected = udp.dnsType,
        onSelect = { v -> viewModel.updateUdp { it.copy(dnsType = v) } },
        label = { it.name },
        accent = accent,
    )
    GlassField(
        value = udp.dnsExpected,
        onValueChange = { v -> viewModel.updateUdp { it.copy(dnsExpected = v.trim()) } },
        label = "Expected answer (optional)",
        placeholder = "192.0.2.10",
        helper = "Degraded when the answer doesn't include it. Empty takes any answer.",
        leadingIcon = NightbellIcons.Target,
        accent = accent,
        keyboardType = KeyboardType.Uri,
        modifier = Modifier.testTag("udp-dns-expected"),
    )
}

@Composable
private fun CustomFields(viewModel: SetupViewModel, udp: UdpWatch, report: Validation.Report, accent: Color) {
    PayloadField(viewModel, udp, report, accent, label = "Send")
    SectionHeader("Reply", icon = NightbellIcons.Target, accent = accent)
    ChipSelector(
        options = ReplyMatch.entries.toList(),
        selected = udp.expect,
        onSelect = { v -> viewModel.updateUdp { it.copy(expect = v) } },
        label = { it.label },
        accent = accent,
        modifier = Modifier.testTag("udp-expect"),
    )
    if (udp.expect != ReplyMatch.ANY) {
        GlassField(
            value = udp.expectValue,
            onValueChange = { v -> viewModel.updateUdp { it.copy(expectValue = v) } },
            label = when (udp.expect) {
                ReplyMatch.REGEX -> "Pattern"
                ReplyMatch.HEX_PREFIX -> "Hex bytes"
                else -> "Text"
            },
            placeholder = if (udp.expect == ReplyMatch.HEX_PREFIX) "ff ff ff ff" else "",
            note = report.of(Validation.Field.REPLY),
            helper = if (udp.expect == ReplyMatch.REGEX) "Matched against the reply read as text." else "",
            leadingIcon = NightbellIcons.Target,
            accent = accent,
            modifier = Modifier.testTag("udp-expect-value"),
        )
    }
}

@Composable
private fun PayloadField(viewModel: SetupViewModel, udp: UdpWatch, report: Validation.Report, accent: Color, label: String) {
    GlassField(
        value = udp.payload,
        onValueChange = { v -> viewModel.updateUdp { it.copy(payload = v) } },
        label = label,
        placeholder = when {
            udp.probe == UdpProbe.SILENT -> "Empty sends one zero byte"
            udp.payloadFormat == PayloadFormat.HEX -> "ff ff ff ff 54"
            else -> "PING\\r\\n"
        },
        note = report.of(Validation.Field.PAYLOAD),
        leadingIcon = NightbellIcons.Datagram,
        accent = accent,
        keyboardType = KeyboardType.Ascii,
        modifier = Modifier.testTag("udp-payload"),
    )
    ChipSelector(
        options = PayloadFormat.entries.toList(),
        selected = udp.payloadFormat,
        onSelect = { v -> viewModel.updateUdp { it.copy(payloadFormat = v) } },
        label = { it.label },
        accent = accent,
    )
    val parsed = UdpPayload.parse(udp.payload, udp.payloadFormat)
    if (parsed is UdpPayload.Parsed.Bytes && parsed.value.isNotEmpty()) {
        Text(
            text = "${parsed.value.size} ${if (parsed.value.size == 1) "byte" else "bytes"}: ${UdpPayload.hexOf(parsed.value, 16)}",
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = NightbellColors.TextTertiary,
            modifier = Modifier.testTag("udp-payload-bytes"),
        )
    } else if (udp.payloadFormat == PayloadFormat.TEXT) {
        Hint("Escapes work: \\r \\n \\t \\0 and \\xNN for any byte.")
    }
}

@Composable
private fun SilentExplainer(viewModel: SetupViewModel, udp: UdpWatch, report: Validation.Report, accent: Color) {
    GlassCard(Modifier.fillMaxWidth().testTag("udp-silent-explainer"), contentPadding = 16.dp) {
        Text(
            text = "This service won't answer a stranger",
            style = MaterialTheme.typography.titleSmall,
            color = NightbellColors.TextPrimary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "So Nightbell only listens for a refusal, and checks that the host answers ping. That catches a " +
                "stopped service on a live host, and a host that's gone. It can't catch a service that's hung but " +
                "still holding the port.",
            style = MaterialTheme.typography.bodySmall,
            color = NightbellColors.TextSecondary,
        )
        val echo = viewModel.testResult?.udp?.echo
        if (echo == false) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "This host doesn't answer ping right now, so silent mode will report it down.",
                style = MaterialTheme.typography.bodySmall,
                color = NightbellColors.Amber,
                modifier = Modifier.testTag("udp-silent-no-echo"),
            )
        }
    }
    PayloadField(viewModel, udp, report, accent, label = "Send (optional)")
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = NightbellColors.TextTertiary,
    )
}
