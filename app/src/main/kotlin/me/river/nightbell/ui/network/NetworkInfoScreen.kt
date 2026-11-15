package me.river.nightbell.ui.network

import android.Manifest
import android.content.ClipData
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.river.nightbell.data.network.NetworkInfoReader
import me.river.nightbell.data.network.NetworkSnapshot
import me.river.nightbell.data.network.firstIpAddress
import me.river.nightbell.data.network.formatBytes
import me.river.nightbell.data.network.formatRate
import me.river.nightbell.data.network.signalBars
import me.river.nightbell.data.network.signalQuality
import me.river.nightbell.ui.components.ButtonTone
import me.river.nightbell.ui.components.GlassCard
import me.river.nightbell.ui.components.GlassIconButton
import me.river.nightbell.ui.components.NightbellButton
import me.river.nightbell.ui.components.ToastMessage
import me.river.nightbell.ui.i18n.L10n
import me.river.nightbell.ui.i18n.Text
import me.river.nightbell.ui.icons.NightbellIcons
import me.river.nightbell.ui.theme.NightbellColors
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

/**
 * A read-only dashboard for the connection the phone is actually using.
 *
 * Nothing here is persisted or sent anywhere: every value comes from a platform
 * service (ConnectivityManager, WifiManager, TelephonyManager, TrafficStats),
 * so the page samples them on a timer instead of subscribing to a flow. The
 * optional public-IP lookup is the only network request and is user-triggered.
 */
@Composable
fun NetworkInfoScreen(
    onBack: () -> Unit,
    onToast: (ToastMessage) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf(NetworkInfoReader.read(context)) }
    var publicIp by rememberSaveable { mutableStateOf<String?>(null) }
    var publicIpError by rememberSaveable { mutableStateOf<String?>(null) }
    var publicIpBusy by remember { mutableStateOf(false) }
    var trafficOpen by rememberSaveable { mutableStateOf(false) }
    var publicOpen by rememberSaveable { mutableStateOf(false) }
    var advancedOpen by rememberSaveable { mutableStateOf(false) }

    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        // Re-read immediately rather than waiting for the next tick.
        snapshot = NetworkInfoReader.read(context)
    }

    LaunchedEffect(Unit) {
        while (true) {
            snapshot = NetworkInfoReader.read(context)
            delay(2_000L)
        }
    }

    val s = snapshot
    val topInset = WindowInsets.systemBars.asPaddingValues().calculateTopPadding()
    val bottomInset = WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()

    val refresh: () -> Unit = { snapshot = NetworkInfoReader.read(context) }
    val copyAll: () -> Unit = {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
            as? android.content.ClipboardManager
        clipboard?.setPrimaryClip(
            ClipData.newPlainText("Nightbell network", reportText(s, publicIp)),
        )
        onToast(ToastMessage.success(L10n.pick("Network details copied", "网络信息已复制")))
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(top = topInset, bottom = bottomInset)
            .padding(horizontal = 16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(
                icon = NightbellIcons.ArrowLeft,
                onClick = onBack,
                contentDescription = L10n.pick("Back", "返回"),
                accent = NightbellColors.TextSecondary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = L10n.pick("Network info", "当前网络"),
                style = MaterialTheme.typography.titleLarge,
                color = NightbellColors.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            GlassIconButton(
                icon = NightbellIcons.Copy,
                onClick = copyAll,
                contentDescription = L10n.pick("Copy all", "复制全部"),
                accent = NightbellColors.TextSecondary,
            )
            GlassIconButton(
                icon = NightbellIcons.Refresh,
                onClick = refresh,
                contentDescription = L10n.pick("Refresh", "刷新"),
                accent = NightbellColors.TextSecondary,
            )
        }

        Spacer(Modifier.height(8.dp))

        LazyColumn(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "summary") { SummaryCard(s) }

            if (!s.hasLocationPermission) {
                item(key = "permission") {
                    GlassCard(contentPadding = 14.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                NightbellIcons.Shield, null,
                                tint = NightbellColors.Amber,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = L10n.pick("Show more detail", "显示更多信息"),
                                style = MaterialTheme.typography.bodyMedium,
                                color = NightbellColors.TextPrimary,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = L10n.pick(
                                "Android hides the Wi-Fi name, BSSID and cellular signal unless location permission is granted.",
                                "安卓要求位置权限才会提供 Wi-Fi 名称、BSSID 和蜂窝信号强度。其它信息不受影响。",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = NightbellColors.TextSecondary,
                        )
                        Spacer(Modifier.height(10.dp))
                        NightbellButton(
                            text = L10n.pick("Grant location permission", "授予位置权限"),
                            shortText = L10n.pick("Grant", "授权"),
                            onClick = { permission.launch(Manifest.permission.ACCESS_FINE_LOCATION) },
                            icon = NightbellIcons.Shield,
                            tone = ButtonTone.Secondary,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            item(key = "connection") { ConnectionCard(s) }
            item(key = "addresses") { AddressCard(s) }
            if (s.wifi != null) item(key = "wifi") { WifiCard(s) }
            if (s.cellular != null && (s.transports.contains("cellular") || s.cellular.cells.isNotEmpty())) {
                item(key = "cellular") { CellularCard(s) }
            }

            item(key = "traffic") {
                FoldableCard(
                    title = L10n.pick("Traffic", "流量统计"),
                    subtitle = L10n.pick(
                        "Since boot · live rate", "开机以来累计 · 实时速率",
                    ),
                    icon = NightbellIcons.Chart,
                    accent = NightbellColors.Sky,
                    expanded = trafficOpen,
                    onToggle = { trafficOpen = !trafficOpen },
                ) {
                    InfoRows(
                        listOf(
                            L10n.pick("Total download", "累计下载") to formatBytes(s.totalRx),
                            L10n.pick("Total upload", "累计上传") to formatBytes(s.totalTx),
                            L10n.pick("Cellular download", "蜂窝下载") to formatBytes(s.mobileRx),
                            L10n.pick("Cellular upload", "蜂窝上传") to formatBytes(s.mobileTx),
                            L10n.pick("Current download", "当前下行") to formatRate(s.rxPerSecond),
                            L10n.pick("Current upload", "当前上行") to formatRate(s.txPerSecond),
                        ),
                    )
                }
            }

            item(key = "public") {
                FoldableCard(
                    title = L10n.pick("Public exit IP", "公网出口 IP"),
                    subtitle = publicIp
                        ?: L10n.pick("Tap to query an external echo service", "点击后联网查询，仅这一次"),
                    icon = NightbellIcons.Globe,
                    accent = NightbellColors.Aqua,
                    expanded = publicOpen,
                    onToggle = { publicOpen = !publicOpen },
                ) {
                    Text(
                        text = L10n.pick(
                            "This is the only value on this page that needs the internet. It is fetched once, on demand, and is not stored.",
                            "本页唯一需要联网的数值，点击才查询一次，不会保存。",
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = NightbellColors.TextTertiary,
                    )
                    Spacer(Modifier.height(8.dp))
                    NightbellButton(
                        text = L10n.pick("Query public IP", "查询公网 IP"),
                        shortText = L10n.pick("Query", "查询"),
                        onClick = {
                            if (publicIpBusy) return@NightbellButton
                            publicIpBusy = true
                            publicIpError = null
                            scope.launch {
                                val found = fetchPublicIp()
                                publicIp = found
                                publicIpError = if (found == null) {
                                    L10n.pick(
                                        "Unreachable (offline LAN, blocked DNS or no internet)",
                                        "无法获取（纯内网、DNS 被拦截或无外网）",
                                    )
                                } else {
                                    null
                                }
                                publicIpBusy = false
                            }
                        },
                        icon = NightbellIcons.Globe,
                        tone = ButtonTone.Secondary,
                        loading = publicIpBusy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (publicIp != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = publicIp.orEmpty(),
                            style = MaterialTheme.typography.titleLarge,
                            fontFamily = FontFamily.Monospace,
                            color = NightbellColors.Mint,
                        )
                    } else if (publicIpError != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = publicIpError.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = NightbellColors.Amber,
                        )
                    }
                }
            }

            item(key = "advanced") {
                FoldableCard(
                    title = L10n.pick("Advanced", "高级明细"),
                    subtitle = L10n.pick(
                        "Routes, DHCP, all networks", "路由表、DHCP、全部网络",
                    ),
                    icon = NightbellIcons.Layers,
                    accent = NightbellColors.Violet,
                    expanded = advancedOpen,
                    onToggle = { advancedOpen = !advancedOpen },
                ) {
                    if (s.routes.isNotEmpty()) {
                        Text(
                            text = L10n.pick("Routes", "路由表"),
                            style = MaterialTheme.typography.labelSmall,
                            color = NightbellColors.TextSecondary,
                        )
                        s.routes.forEach {
                            MonoLine(it)
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    if (s.wifi?.dhcp?.isNotEmpty() == true) {
                        Text(
                            text = L10n.pick("DHCP detail", "DHCP 明细"),
                            style = MaterialTheme.typography.labelSmall,
                            color = NightbellColors.TextSecondary,
                        )
                        s.wifi.dhcp.forEach { MonoLine(it) }
                        Spacer(Modifier.height(8.dp))
                    }
                    Text(
                        text = L10n.pick("All networks", "全部网络"),
                        style = MaterialTheme.typography.labelSmall,
                        color = NightbellColors.TextSecondary,
                    )
                    if (s.networks.isEmpty()) {
                        Text(
                            text = "-",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = NightbellColors.TextTertiary,
                        )
                    } else {
                        s.networks.forEach { net ->
                            MonoLine(
                                "${net.id} · ${net.transports.joinToString("+").ifEmpty { "-" }} · " +
                                    if (net.validated) "validated" else "unvalidated",
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = L10n.pick("Capabilities", "网络能力") + ": " +
                            s.capabilities.joinToString(", ").ifEmpty { "-" },
                        style = MaterialTheme.typography.labelSmall,
                        color = NightbellColors.TextSecondary,
                    )
                }
            }

            item(key = "note") {
                Text(
                    text = L10n.pick(
                        "All values are read locally from Android system services. Nothing is uploaded or stored. Some fields need a newer Android version or location permission.",
                        "所有数值均在本机读取安卓系统接口，不上传、不保存。部分字段受系统版本或权限限制会显示 —。",
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = NightbellColors.TextTertiary,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun SummaryCard(s: NetworkSnapshot) {
    val connected = s.connected
    val captive = "captive_portal" in s.capabilities
    val tone = when {
        !connected -> NightbellColors.Rose
        captive -> NightbellColors.Amber
        "validated" in s.capabilities -> NightbellColors.Mint
        else -> NightbellColors.Amber
    }
    val status = when {
        !connected -> L10n.pick("No internet connection", "当前无网络连接")
        captive -> L10n.pick("Captive portal — sign in required", "需要网页认证登录")
        "validated" in s.capabilities -> L10n.pick("Internet validated", "互联网连接已验证")
        else -> L10n.pick("Internet not validated", "互联网未验证")
    }
    val typeLabel = s.transports.joinToString(" + ") { transportLabel(it) }
        .ifEmpty { L10n.pick("Offline", "离线") }
    val identity = s.wifi?.ssid
        ?: s.cellular?.operator
        ?: s.cellular?.simOperator
        ?: s.addresses.firstOrNull { it.ipv4 }?.text?.substringBefore('/')
        ?: "-"

    GlassCard(accent = tone, contentPadding = 14.dp) {
        Text(
            text = L10n.pick("Current connection", "当前连接"),
            style = MaterialTheme.typography.labelSmall,
            color = NightbellColors.TextSecondary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = typeLabel,
            style = MaterialTheme.typography.headlineSmall,
            color = NightbellColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(tone),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = tone,
                modifier = Modifier.weight(1f),
            )
            val level = s.wifi?.level ?: s.cellular?.signalLevel
            if (level != null) {
                SignalBars(level, 4, tone)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = identity,
            style = MaterialTheme.typography.bodyMedium,
            color = NightbellColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = L10n.pick("Updated ", "更新于 ") + timeFormat.format(Date(s.capturedAt)) +
                L10n.pick(" · refreshes every 2s", " · 每 2 秒自动刷新"),
            style = MaterialTheme.typography.labelSmall,
            color = NightbellColors.TextTertiary,
        )
    }
}

@Composable
private fun ConnectionCard(s: NetworkSnapshot) {
    val validation = when {
        "captive_portal" in s.capabilities -> L10n.pick("Captive portal", "需要认证")
        "validated" in s.capabilities -> L10n.pick("Validated", "已验证")
        else -> L10n.pick("Not validated", "未验证")
    }
    val rows = buildList {
        add(L10n.pick("Transport", "接入方式") to s.transports.joinToString(" + ") { transportLabel(it) }.ifEmpty { "-" })
        add(L10n.pick("Internet", "互联网验证") to validation)
        add(L10n.pick("Metered", "按流量计费") to yesNo("not_metered" !in s.capabilities))
        add(L10n.pick("Roaming", "漫游") to yesNo("not_roaming" !in s.capabilities))
        add(
            L10n.pick("Bandwidth estimate", "带宽估计") to
                (L10n.pick("down ", "下行 ") + (s.downstreamKbps?.let { "$it Kbps" } ?: "-") +
                    " · " + L10n.pick("up ", "上行 ") + (s.upstreamKbps?.let { "$it Kbps" } ?: "-")),
        )
        if (s.cellularSignalStrength != null) {
            add(L10n.pick("Cellular signal", "蜂窝信号") to "${s.cellularSignalStrength} dBm")
        }
        add(L10n.pick("Private DNS", "私人 DNS") to (s.privateDns ?: "-") +
            if (s.privateDnsActive) " (${L10n.pick("active", "已启用")})" else "")
        add(L10n.pick("Interface / MTU", "接口 / MTU") to
            "${s.interfaceName ?: "-"} / ${s.mtu?.toString() ?: "-"}")
        add(L10n.pick("VPN", "VPN") to yesNo(s.vpnActive))
        add(
            L10n.pick("Capture proxy", "抓包代理") to
                (s.captureProxy ?: L10n.pick("not running", "未开启")),
        )
    }
    InfoCard(
        title = L10n.pick("Connection", "连接概况"),
        icon = NightbellIcons.Activity,
        accent = NightbellColors.Aqua,
        rows = rows,
    )
}

@Composable
private fun AddressCard(s: NetworkSnapshot) {
    val ipv4 = s.addresses.filter { it.ipv4 }.map { it.text }
    val ipv6 = s.addresses.filterNot { it.ipv4 }.map { it.text }
    InfoCard(
        title = L10n.pick("IP addresses", "IP 地址"),
        icon = NightbellIcons.Globe,
        accent = NightbellColors.Mint,
        rows = buildList {
            if (ipv4.isEmpty()) add(L10n.pick("Local IPv4", "本机 IPv4") to "-")
            else ipv4.forEachIndexed { i, value ->
                val label = L10n.pick("Local IPv4", "本机 IPv4") + if (i > 0) " $i" else ""
                add(label to value)
            }
            if (ipv6.isEmpty()) add(L10n.pick("Local IPv6", "本机 IPv6") to "-")
            else ipv6.forEachIndexed { i, value ->
                val label = L10n.pick("Local IPv6", "本机 IPv6") + if (i > 0) " $i" else ""
                add(label to value)
            }
            add(L10n.pick("DNS servers", "DNS 服务器") to joinedOrDash(s.dnsServers))
            add(L10n.pick("Default gateway", "默认网关") to joinedOrDash(s.gateways))
            add(L10n.pick("DHCP server", "DHCP 服务器") to (s.dhcpServer ?: "-"))
        },
    )
}

@Composable
private fun WifiCard(s: NetworkSnapshot) {
    val w = s.wifi ?: return
    val grade = w.level?.let { signalBars(it, w.maxLevel) }
    val quality = grade?.let { signalQuality(it) } ?: "-"
    InfoCard(
        title = L10n.pick("Wi-Fi", "Wi-Fi"),
        icon = NightbellIcons.Wifi,
        accent = NightbellColors.Sky,
        rows = buildList {
            add(L10n.pick("SSID", "网络名称") to (w.ssid ?: L10n.pick("hidden (location permission)", "需位置权限")))
            add(L10n.pick("BSSID", "AP 地址") to (w.bssid ?: L10n.pick("hidden (location permission)", "需位置权限")))
            add(L10n.pick("RSSI", "信号强度") to
                ((w.rssi?.let { "$it dBm" } ?: "-") + " · " + qualityWord(quality)))
            add(L10n.pick("Signal grade", "信号等级") to (grade?.let { "$it/${w.maxLevel}" } ?: "-"))
            add(L10n.pick("Link speed", "连接速率") to
                ((w.linkSpeedMbps?.let { "$it Mbps" } ?: "-") +
                    (w.rxLinkSpeedMbps?.let { " ↓$it" } ?: "") +
                    (w.txLinkSpeedMbps?.let { " ↑$it" } ?: "")))
            add(L10n.pick("Frequency", "频率 / 信道") to
                ("${if (w.frequencyMhz > 0) "${w.frequencyMhz} MHz" else "-"} · " +
                    "${w.channel?.toString() ?: "-"} · ${w.band}"))
            add(L10n.pick("Standard", "协议标准") to w.standard)
            add(L10n.pick("Wi-Fi enabled", "Wi-Fi 开关") to yesNo(w.enabled))
        },
    )
}

@Composable
private fun CellularCard(s: NetworkSnapshot) {
    val c = s.cellular ?: return
    InfoCard(
        title = L10n.pick("Cellular", "蜂窝网络"),
        icon = NightbellIcons.Zap,
        accent = NightbellColors.Violet,
        rows = buildList {
            add(L10n.pick("Operator", "运营商") to (c.operator ?: c.simOperator ?: "-"))
            add(L10n.pick("MCC / MNC", "MCC / MNC") to (c.mccMnc ?: "-"))
            add(L10n.pick("SIM state", "SIM 状态") to c.simState)
            add(L10n.pick("Data type", "数据网络") to (c.dataNetworkType ?: "-"))
            add(L10n.pick("Roaming", "漫游") to yesNo(c.roaming == true))
            add(L10n.pick("Signal dBm", "信号 dBm") to (c.signalDbm?.toString() ?: "-"))
            add(L10n.pick("Signal grade", "信号等级") to (c.signalLevel?.let { "$it/4" } ?: "-"))
            c.cells.forEachIndexed { index, cell ->
                val prefix = "${cell.kind} ${index + 1}"
                add(prefix to listOfNotNull(
                    cell.dbm?.let { "${it}dBm" },
                    cell.level?.let { "L$it" },
                    cell.asu?.let { "ASU$it" },
                ).joinToString(" · ").ifEmpty { "-" })
                cell.details.chunked(2).forEach { pair ->
                    add("  ↳" to pair.joinToString(" · "))
                }
            }
        },
    )
}

@Composable
private fun InfoCard(
    title: String,
    icon: ImageVector,
    accent: Color,
    rows: List<Pair<String, String>>,
) {
    GlassCard(contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = NightbellColors.TextSecondary,
            )
        }
        Spacer(Modifier.height(8.dp))
        InfoRows(rows)
    }
}

@Composable
private fun InfoRows(rows: List<Pair<String, String>>) {
    rows.forEach { (label, value) ->
        Row(
            Modifier.fillMaxWidth().padding(vertical = 3.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = NightbellColors.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(116.dp),
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = NightbellColors.TextPrimary,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun MonoLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        color = NightbellColors.TextSecondary,
        modifier = Modifier.padding(vertical = 1.dp),
    )
}

@Composable
private fun SignalBars(level: Int, maxLevel: Int, color: Color) {
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        repeat(maxLevel + 1) { index ->
            Box(
                Modifier
                    .width(8.dp)
                    .height((8 + index * 4).dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        if (index <= level) color
                        else NightbellColors.TextTertiary.copy(alpha = 0.25f),
                    ),
            )
        }
    }
}

@Composable
private fun FoldableCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassCard(onClick = onToggle, contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NightbellColors.TextPrimary,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = NightbellColors.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = if (expanded) NightbellIcons.ChevronUp else NightbellIcons.ChevronDown,
                contentDescription = null,
                tint = NightbellColors.TextTertiary,
                modifier = Modifier.size(18.dp),
            )
        }
        if (expanded) {
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

private fun transportLabel(key: String): String = when (key) {
    "wifi" -> "Wi-Fi"
    "cellular" -> L10n.pick("Cellular", "蜂窝数据")
    "ethernet" -> L10n.pick("Ethernet", "以太网")
    "vpn" -> "VPN"
    "bluetooth" -> L10n.pick("Bluetooth", "蓝牙")
    else -> L10n.pick("Other", "其它")
}

private fun yesNo(value: Boolean): String =
    L10n.pick(if (value) "Yes" else "No", if (value) "是" else "否")

private fun qualityWord(quality: String): String = when (quality) {
    "Excellent" -> L10n.pick("Excellent", "极好")
    "Good" -> L10n.pick("Good", "良好")
    "Fair" -> L10n.pick("Fair", "一般")
    "Weak" -> L10n.pick("Weak", "较弱")
    else -> "-"
}

private fun joinedOrDash(values: List<String>): String =
    values.joinToString(", ").ifEmpty { "-" }

/** Plain-text report for the clipboard. Kept short: one line per value. */
private fun reportText(s: NetworkSnapshot, publicIp: String?): String = buildString {
    appendLine("Nightbell ${L10n.pick("Network info", "当前网络")} · ${timeFormat.format(Date(s.capturedAt))}")
    appendLine("${L10n.pick("Transport", "接入方式")}: ${s.transports.joinToString("+")}")
    appendLine("${L10n.pick("Internet", "互联网验证")}: ${"validated" in s.capabilities}")
    s.addresses.forEach { appendLine("IP: ${it.text}") }
    appendLine("DNS: ${joinedOrDash(s.dnsServers)}")
    appendLine("${L10n.pick("Default gateway", "默认网关")}: ${joinedOrDash(s.gateways)}")
    appendLine("${L10n.pick("Interface / MTU", "接口 / MTU")}: ${s.interfaceName ?: "-"} / ${s.mtu ?: "-"}")
    s.wifi?.let {
        appendLine("Wi-Fi: ${it.ssid ?: "-"} / ${it.rssi ?: "-"} dBm / ${it.band} / ${it.standard}")
    }
    s.cellular?.let {
        appendLine("${L10n.pick("Cellular", "蜂窝网络")}: ${it.operator ?: "-"} / ${it.dataNetworkType ?: "-"} / ${it.signalDbm ?: "-"} dBm")
    }
    appendLine("${L10n.pick("Traffic", "流量")}: ↓${formatBytes(s.totalRx)} ↑${formatBytes(s.totalTx)}")
    appendLine("${L10n.pick("Capture proxy", "抓包代理")}: ${s.captureProxy ?: "-"}")
    publicIp?.let { appendLine("${L10n.pick("Public exit IP", "公网出口 IP")}: $it") }
}

/** Two independent echo services; returns null when neither is reachable. */
private suspend fun fetchPublicIp(): String? = withContext(Dispatchers.IO) {
    val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .build()
    listOf("https://api.ipify.org?format=text", "https://ipv4.icanhazip.com")
        .firstNotNullOfOrNull { url ->
            runCatching {
                client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) null
                    else firstIpAddress(response.body?.string().orEmpty())
                }
            }.getOrNull()
        }
}
