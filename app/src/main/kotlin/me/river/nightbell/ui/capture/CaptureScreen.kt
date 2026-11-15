package me.river.nightbell.ui.capture

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.security.KeyChain
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.river.nightbell.data.Nightbell
import me.river.nightbell.data.capture.CaptureCa
import me.river.nightbell.data.capture.CaptureLogRepository
import me.river.nightbell.data.capture.CaptureProxyControl
import me.river.nightbell.data.capture.CaptureRepository
import me.river.nightbell.data.capture.CaptureSettings
import me.river.nightbell.data.capture.CaptureVpnService
import me.river.nightbell.data.capture.MonitorLogEntry
import me.river.nightbell.data.capture.MonitorLogRepository
import me.river.nightbell.data.capture.CapturedEndpoint
import me.river.nightbell.data.capture.RequestLogEntry
import me.river.nightbell.data.capture.readableBody
import me.river.nightbell.domain.BodyAssertion
import me.river.nightbell.domain.HeaderPair
import me.river.nightbell.domain.HttpMethod
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.NetworkWatch
import me.river.nightbell.domain.StatusExpectation
import me.river.nightbell.domain.StatusMode
import me.river.nightbell.ui.components.ButtonTone
import me.river.nightbell.ui.components.GlassCard
import me.river.nightbell.ui.components.GlassIconButton
import me.river.nightbell.ui.components.NightbellButton
import me.river.nightbell.ui.components.SectionHeader
import me.river.nightbell.ui.components.ToastMessage
import me.river.nightbell.ui.i18n.L10n
import me.river.nightbell.ui.i18n.Text
import me.river.nightbell.ui.icons.NightbellIcons
import me.river.nightbell.ui.theme.NightbellColors
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetSocketAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

private enum class CaptureTab { ENDPOINTS, LOGS }

/** Headers belonging to the captured connection, never to a replayed request. */
private val NON_REPLAY_HEADERS = setOf(
    "accept-encoding", "content-length", "transfer-encoding", "host",
    "connection", "proxy-connection", "proxy-authorization", "keep-alive",
    "te", "trailer", "upgrade", "expect",
)

/** One line when closed, the whole configuration when open. */
@Composable
private fun FoldableSection(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassCard(onClick = onToggle, contentPadding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(18.dp),
            )
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

/**
 * Capture -> decrypt (optional) -> pick -> test -> read the log.
 *
 * Nightbell on its own can only check a URL somebody typed. This screen lists
 * the interfaces the phone actually called, lets the user tick the ones they
 * care about, and either probes them once or turns them into monitors. Every
 * exchange, whether seen through the proxy or sent by a test, lands in the
 * request log so the response that made a test pass or fail is inspectable.
 */
@Composable
fun CaptureScreen(
    onBack: () -> Unit,
    onToast: (ToastMessage) -> Unit = {},
) {
    val context = LocalContext.current
    val graph = Nightbell.require()
    val state by CaptureRepository.state.collectAsStateWithLifecycle()
    val config by CaptureSettings.state.collectAsStateWithLifecycle()
    val logs by CaptureLogRepository.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(CaptureTab.ENDPOINTS) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var query by remember { mutableStateOf("") }
    var hideTls by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf(emptyMap<String, String>()) }
    var busy by remember { mutableStateOf(false) }
    var hostsText by remember { mutableStateOf("") }
    var fingerprint by remember { mutableStateOf("") }
    var expandedLog by remember { mutableStateOf<String?>(null) }
    var tlsExpanded by rememberSaveable { mutableStateOf(false) }
    var notesExpanded by rememberSaveable { mutableStateOf(false) }
    var expandedEndpoint by remember { mutableStateOf<String?>(null) }
    var logQuery by remember { mutableStateOf("") }
    /** Debounces "the allowlist changed" into one tunnel reset per edit burst. */
    var resetJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(Unit) {
        CaptureSettings.init(context.applicationContext)
        CaptureLogRepository.init(context.applicationContext)
        hostsText = CaptureSettings.state.value.hosts.joinToString("\n")
        fingerprint = withContext(Dispatchers.IO) {
            runCatching { CaptureCa.fingerprint(context.applicationContext) }.getOrDefault("")
        }
    }

    val consent = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            CaptureVpnService.start(context)
        } else {
            CaptureRepository.fail(
                L10n.pick("VPN consent was declined", "未授予 VPN 权限"),
            )
        }
    }

    // ACTION_SEND only offers the certificate to apps that declare a matching
    // MIME type, and most phones have none (and no "save to Downloads" target).
    // The Storage Access Framework writes a real file wherever the user points,
    // needs no storage permission, and works with the system certificate
    // installer's "Install from storage" flow too.
    val saveCa = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-pem-file"),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val outcome = withContext(Dispatchers.IO) {
                    runCatching {
                        val pem = CaptureCa.certificatePem(context.applicationContext)
                        context.contentResolver.openOutputStream(uri)?.use { stream ->
                            stream.write(pem.toByteArray(Charsets.US_ASCII))
                        } ?: error("no output stream for $uri")
                    }
                }
                onToast(
                    if (outcome.isSuccess) {
                        ToastMessage.success(L10n.pick("Root CA saved", "根证书已保存"))
                    } else {
                        ToastMessage.error(
                            L10n.pick("Could not save: ", "保存失败：") +
                                (outcome.exceptionOrNull()?.message ?: ""),
                        )
                    },
                )
            }
        }
    }

    val shown = remember(state.endpoints, query, hideTls) {
        state.endpoints.filter { endpoint ->
            (!hideTls || !endpoint.tlsOnly) &&
                (query.isBlank() || endpoint.url.contains(query, ignoreCase = true) ||
                    endpoint.method.contains(query, ignoreCase = true))
        }
    }

    val filteredLogs = remember(logs, logQuery) {
        if (logQuery.isBlank()) logs else logs.filter { entry ->
            entry.url.contains(logQuery, ignoreCase = true) ||
                entry.method.contains(logQuery, ignoreCase = true) ||
                entry.error.contains(logQuery, ignoreCase = true) ||
                entry.statusCode?.toString()?.contains(logQuery) == true
        }
    }

    // Hosts the phone actually CONNECTed to but that never reached the MITM
    // branch. Handshake failures are excluded (already allowed, different
    // problem), and so are the rows already allowlisted by the user.
    val pendingHosts = remember(state.endpoints) {
        state.endpoints
            .filter { it.tlsOnly && it.blockReason != "handshake" && it.blockReason != "allowlisted" }
            .map { it.host }
            .distinct()
    }

    /**
     * A new allowlist cannot reach a TLS tunnel that is already open: apps reuse
     * those connections for minutes, and the proxy only ever sees the initial
     * CONNECT. Dropping them forces a fresh CONNECT, which is both discoverable
     * again and subject to the new decryption rules. [waitMs] debounces typing.
     */
    val scheduleTunnelReset: (Long) -> Unit = { waitMs ->
        resetJob?.cancel()
        resetJob = scope.launch {
            if (waitMs > 0) delay(waitMs)
            CaptureProxyControl.resetTunnels()
        }
    }

    /**
     * One-tap allowlist. Used both from a single interface row (the host of the
     * request you are looking at) and from the "all captured hosts" shortcut.
     *
     * The captured rows are KEPT. Allowlisting only changes what happens to the
     * next connection; the decrypted interfaces appear when the host is visited
     * again. Dropping the CONNECT rows here used to make the whole domain vanish
     * from the list in the meantime. They are marked "allowlisted" instead and
     * are retired by CaptureRepository.record() once a real decrypted interface
     * arrives. Selection is kept too, so the rows stay immediately testable.
     */
    val allowHosts: (List<String>) -> Unit = { hosts ->
        if (hosts.isNotEmpty()) {
            CaptureSettings.addHosts(context, *hosts.toTypedArray())
            hostsText = CaptureSettings.state.value.effectiveHosts().joinToString("\n")
            CaptureRepository.markAllowlisted(hosts.toSet())
            scheduleTunnelReset(0)
            onToast(
                ToastMessage.success(
                    L10n.pick(
                        "Allowlist updated — live connections were dropped. Revisit the interface (or tap play) to see the decrypted request",
                        "白名单已生效并断开旧连接。重新访问该接口或点 ▶ 测试即可看到完整请求",
                    ),
                ),
            )
        }
    }
    val addHost: (CapturedEndpoint) -> Unit = { endpoint -> allowHosts(listOf(endpoint.host)) }

    /** Test exactly one row, without having to tick it and run the batch. */
    val testSingle: (CapturedEndpoint) -> Unit = { endpoint ->
        busy = true
        scope.launch {
            val outcome = probe(endpoint)
            results = results + (endpoint.key to outcome)
            busy = false
            tab = CaptureTab.LOGS
            onToast(
                ToastMessage.success(L10n.pick("Tested 1 interface", "已测试 1 个接口")),
            )
        }
    }

    /** Same one-row shortcut for turning an interface into a monitor. */
    val addSingle: (CapturedEndpoint) -> Unit = { endpoint ->
        busy = true
        scope.launch {
            addMonitors(graph, listOf(endpoint))
            busy = false
            tab = CaptureTab.LOGS
            onToast(
                ToastMessage.success(
                    L10n.pick(
                        "Added 1 monitor and ran its first check",
                        "已创建 1 个监控并执行首次检测",
                    ),
                ),
            )
        }
    }

    val topInset = WindowInsets.systemBars.asPaddingValues().calculateTopPadding()
    val bottomInset = WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()

    Column(
        Modifier
            .fillMaxSize()
            .padding(top = topInset, bottom = bottomInset)
            .padding(horizontal = 16.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassIconButton(
                icon = NightbellIcons.ArrowLeft,
                onClick = onBack,
                contentDescription = "Back",
                accent = NightbellColors.TextSecondary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = L10n.pick("Interface capture", "接口抓包"),
                style = MaterialTheme.typography.titleLarge,
                color = NightbellColors.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            GlassIconButton(
                icon = NightbellIcons.Trash,
                onClick = {
                    if (tab == CaptureTab.ENDPOINTS) {
                        CaptureRepository.clear()
                        selected = emptySet()
                        results = emptyMap()
                    } else {
                        CaptureLogRepository.clear()
                        expandedLog = null
                    }
                    // Clearing the list otherwise makes already-open TLS tunnels
                    // invisible forever: the client keeps reusing them and never
                    // sends another CONNECT. Force it to reconnect.
                    val dropped = CaptureProxyControl.resetTunnels()
                    onToast(
                        ToastMessage.success(
                            L10n.pick(
                                "List cleared · $dropped live connections dropped — visit the interface again",
                                "已清空 · 断开 $dropped 条长连接，请重新访问接口",
                            ),
                        ),
                    )
                },
                contentDescription = L10n.pick("Clear list and reconnect", "清空并重新连接"),
                accent = NightbellColors.TextSecondary,
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NightbellButton(
                text = L10n.pick("Interfaces (${state.endpoints.size})", "接口 (${state.endpoints.size})"),
                shortText = L10n.pick("Interfaces", "接口"),
                onClick = { tab = CaptureTab.ENDPOINTS },
                tone = if (tab == CaptureTab.ENDPOINTS) ButtonTone.Primary else ButtonTone.Secondary,
                modifier = Modifier.weight(1f),
            )
            NightbellButton(
                text = L10n.pick("Request log (${logs.size})", "请求日志 (${logs.size})"),
                shortText = L10n.pick("Logs", "日志"),
                onClick = { tab = CaptureTab.LOGS },
                tone = if (tab == CaptureTab.LOGS) ButtonTone.Primary else ButtonTone.Secondary,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(8.dp))

        LazyColumn(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (tab == CaptureTab.ENDPOINTS) {
                item(key = "status") {
                    GlassCard {
                        SectionHeader(
                            L10n.pick("Capture", "抓包"),
                            icon = NightbellIcons.Radar,
                            accent = NightbellColors.Aqua,
                        )
                        Text(
                            text = if (state.running) {
                                L10n.pick("Capturing on 127.0.0.1", "正在 127.0.0.1 上抓包")
                            } else {
                                L10n.pick("Capture is off", "抓包未开启")
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (state.running) NightbellColors.Mint else NightbellColors.TextSecondary,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = L10n.pick(
                                "${state.endpoints.size} interfaces · ${state.totalRequests} requests",
                                "已发现 ${state.endpoints.size} 个接口 · ${state.totalRequests} 次请求",
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = NightbellColors.TextTertiary,
                        )
                        if (state.running && state.manual) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = L10n.pick(
                                    "Android 9 or older: set the Wi-Fi proxy by hand to 127.0.0.1:" + state.proxyPort,
                                    "Android 9 及以下：请在 Wi-Fi 设置中手动把代理设为 127.0.0.1:" + state.proxyPort,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = NightbellColors.Amber,
                            )
                        }
                        if (state.error != null) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = state.error.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = NightbellColors.Rose,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        NightbellButton(
                            text = L10n.pick(
                                if (state.running) "Stop capture" else "Start capture",
                                if (state.running) "停止抓包" else "开启抓包",
                            ),
                            onClick = {
                                if (state.running) {
                                    CaptureVpnService.stop(context)
                                } else {
                                    CaptureRepository.clearError()
                                    val intent = CaptureVpnService.prepare(context)
                                    if (intent == null) CaptureVpnService.start(context)
                                    else consent.launch(intent)
                                }
                            },
                            icon = if (state.running) NightbellIcons.Pause else NightbellIcons.Play,
                            tone = if (state.running) ButtonTone.Secondary else ButtonTone.Primary,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                item(key = "tls") {
                    FoldableSection(
                        title = L10n.pick("HTTPS decryption", "HTTPS 解密"),
                        subtitle = if (config.decryptTls && config.effectiveHosts().isNotEmpty()) {
                            L10n.pick("On · ", "已开启 · ") + config.effectiveHosts().joinToString(", ")
                        } else {
                            L10n.pick("Off · tap to configure", "未开启 · 点开配置")
                        },
                        icon = NightbellIcons.Shield,
                        accent = NightbellColors.Violet,
                        expanded = tlsExpanded,
                        onToggle = { tlsExpanded = !tlsExpanded },
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = config.decryptTls,
                                onCheckedChange = { checked ->
                                    CaptureSettings.save(context, checked, hostsText)
                                    scheduleTunnelReset(0)
                                },
                            )
                            Text(
                                text = L10n.pick(
                                    "Decrypt HTTPS for the hosts below",
                                    "仅对下列主机解密 HTTPS",
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = NightbellColors.TextSecondary,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = L10n.pick(
                                "Host only. URL, :port and *.wildcards are fine; typing one enables decryption.",
                                "只填主机名，URL/端口/通配符均可；填入即自动开启解密。",
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = NightbellColors.TextTertiary,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = hostsText,
                            onValueChange = {
                                hostsText = it
                                // An empty list must not silently disable decryption
                                // while the user is still typing the first host.
                                val enable = config.decryptTls || it.isNotBlank()
                                CaptureSettings.save(context, enable, it)
                                // Removing a host has to take effect on the next
                                // request, not ten minutes later when the client
                                // happens to open a new tunnel.
                                scheduleTunnelReset(700)
                            },
                            placeholder = {
                                Text(L10n.pick("api.internal.test", "api.internal.test"))
                            },
                            minLines = 2,
                            maxLines = 4,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = L10n.pick("Active rule: ", "实际生效：") +
                                if (config.decryptTls && config.effectiveHosts().isNotEmpty()) {
                                    config.effectiveHosts().joinToString(", ")
                                } else {
                                    L10n.pick(
                                        "none (all HTTPS is tunnelled)",
                                        "无（所有 HTTPS 都只是透传）",
                                    )
                                },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (config.decryptTls && config.effectiveHosts().isNotEmpty()) {
                                NightbellColors.Mint
                            } else {
                                NightbellColors.Amber
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(10.dp))
                        NightbellButton(
                            text = L10n.pick("Save root CA to storage", "保存根证书到本机"),
                            shortText = L10n.pick("Save", "保存"),
                            onClick = {
                                runCatching { saveCa.launch("nightbell-ca.pem") }.onFailure {
                                    onToast(
                                        ToastMessage.error(
                                            L10n.pick("Could not open the file picker: ", "无法打开文件选择器：") +
                                                (it.message ?: ""),
                                        ),
                                    )
                                }
                            },
                            icon = NightbellIcons.Download,
                            tone = ButtonTone.Primary,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NightbellButton(
                                text = L10n.pick("Install root CA", "安装根证书"),
                                shortText = L10n.pick("Install", "安装"),
                                onClick = {
                                    runCatching {
                                        val intent = KeyChain.createInstallIntent().apply {
                                            putExtra(
                                                KeyChain.EXTRA_CERTIFICATE,
                                                CaptureCa.certificateDer(context.applicationContext),
                                            )
                                            putExtra(KeyChain.EXTRA_NAME, "Nightbell Capture CA")
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        context.startActivity(intent)
                                    }.onFailure {
                                        onToast(
                                            ToastMessage.error(
                                                L10n.pick(
                                                    "Could not open the certificate installer: ",
                                                    "无法打开证书安装界面：",
                                                ) + (it.message ?: ""),
                                            ),
                                        )
                                    }
                                },
                                icon = NightbellIcons.Shield,
                                modifier = Modifier.weight(1f),
                            )
                            NightbellButton(
                                text = L10n.pick("Share PEM", "分享证书"),
                                shortText = L10n.pick("Share", "分享"),
                                onClick = {
                                    runCatching {
                                        val file = CaptureCa.exportFile(context.applicationContext)
                                        val uri = FileProvider.getUriForFile(
                                            context,
                                            context.packageName + ".capturefiles",
                                            file,
                                        )
                                        val send = Intent(Intent.ACTION_SEND).apply {
                                            type = "application/x-pem-file"
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            // Some receivers only honour the grant when the
                                            // URI is also attached as ClipData.
                                            clipData = ClipData.newRawUri("Nightbell CA", uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        val chooser = Intent.createChooser(
                                            send,
                                            L10n.pick("Share root CA", "分享根证书"),
                                        ).apply {
                                            // The flag belongs to the intent that actually
                                            // leaves this app, not just to its EXTRA_INTENT.
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(chooser)
                                    }.onFailure {
                                        onToast(
                                            ToastMessage.error(
                                                L10n.pick("Could not export: ", "导出失败：") + (it.message ?: ""),
                                            ),
                                        )
                                    }
                                },
                                icon = NightbellIcons.Export,
                                tone = ButtonTone.Secondary,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { notesExpanded = !notesExpanded },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = L10n.pick("Notes & limits", "说明与限制"),
                                style = MaterialTheme.typography.labelSmall,
                                color = NightbellColors.TextSecondary,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                imageVector = if (notesExpanded) {
                                    NightbellIcons.ChevronUp
                                } else {
                                    NightbellIcons.ChevronDown
                                },
                                contentDescription = null,
                                tint = NightbellColors.TextTertiary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        if (notesExpanded) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = L10n.pick(
                                    "Plain HTTP is fully visible. Allowlisted HTTPS is decrypted locally; other HTTPS " +
                                        "shows host and port only. Apps ignoring the system proxy, UDP and QUIC are not " +
                                        "seen. Credentials are stripped before saving.",
                                    "明文 HTTP 完整可见；白名单 HTTPS 在本机解密，其余只看域名端口。" +
                                        "忽略系统代理的 App、UDP/QUIC 抓不到，凭据保存前已剔除。",
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = NightbellColors.TextTertiary,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = L10n.pick(
                                    "Install the CA as a user credential, then reopen the target app. Chrome trusts " +
                                        "user CAs; most Android 7+ apps and all certificate-pinned apps do not — those " +
                                        "handshakes fail and cannot be decrypted without root. Save the .pem to any " +
                                        "folder, or install it from Settings > Security > Install from storage.",
                                    "把证书装为「用户凭据」后重新打开目标 App。Chrome 信任用户 CA，Android 7+ 多数 App " +
                                        "与所有证书固定 App 不信任 —— 这些握手会失败，无 root 无法解密。" +
                                        "证书可保存到任意文件夹，或在 设置 > 安全 > 从存储设备安装 里选 .pem。",
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = NightbellColors.Amber,
                            )
                        }
                        if (fingerprint.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = L10n.pick("CA SHA-256: ", "根证书 SHA-256：") + fingerprint,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = NightbellColors.TextTertiary,
                            )
                        }
                    }
                }

                item(key = "filter") {
                    GlassCard {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = {
                                Text(L10n.pick("Filter by host or path", "按域名或路径筛选"))
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = hideTls, onCheckedChange = { hideTls = it })
                            Text(
                                text = L10n.pick(
                                    "Hide HTTPS (CONNECT) entries",
                                    "隐藏 HTTPS (CONNECT) 条目",
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = NightbellColors.TextSecondary,
                            )
                        }
                        // Always visible, no need to open the TLS section first.
                        if (pendingHosts.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            NightbellButton(
                                text = L10n.pick(
                                    "Add ${pendingHosts.size} captured hosts to the allowlist",
                                    "把抓到的 ${pendingHosts.size} 个域名加入白名单",
                                ),
                                shortText = L10n.pick("Add hosts", "一键加入"),
                                onClick = { allowHosts(pendingHosts) },
                                icon = NightbellIcons.Shield,
                                tone = ButtonTone.Secondary,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = L10n.pick(
                                    "Visit the interface again to see each request as its own row.",
                                    "重新访问接口，就能看到每条请求单独成行。",
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = NightbellColors.TextTertiary,
                            )
                        }
                    }
                }

                if (shown.isEmpty()) {
                    item(key = "empty") {
                        GlassCard {
                            Text(
                                text = L10n.pick(
                                    "Nothing captured yet. Start capture, then use the app whose interfaces you want to test.",
                                    "还没有捕获到接口。先开启抓包，再操作一下你要测试的那个 App。",
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = NightbellColors.TextTertiary,
                            )
                        }
                    }
                }

                items(shown, key = { it.key }) { endpoint ->
                    EndpointRow(
                        endpoint = endpoint,
                        checked = endpoint.key in selected,
                        result = results[endpoint.key],
                        onToggle = {
                            selected = if (endpoint.key in selected) {
                                selected - endpoint.key
                            } else {
                                selected + endpoint.key
                            }
                        },
                        expanded = expandedEndpoint == endpoint.key,
                        onExpand = {
                            expandedEndpoint = if (expandedEndpoint == endpoint.key) null else endpoint.key
                        },
                        onAllowHost = { addHost(endpoint) },
                        onTestSingle = { testSingle(endpoint) },
                        onAddSingle = { addSingle(endpoint) },
                        testEnabled = !busy,
                    )
                }
            } else {
                item(key = "log-header") {
                    GlassCard(contentPadding = 12.dp) {
                        OutlinedTextField(
                            value = logQuery,
                            onValueChange = { logQuery = it },
                            placeholder = {
                                Text(L10n.pick("Filter by host, path or status", "按域名 / 路径 / 状态筛选"))
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = L10n.pick(
                                "${filteredLogs.size} / ${logs.size} entries · tap to expand",
                                "${filteredLogs.size} / ${logs.size} 条 · 点开看详情",
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = NightbellColors.TextTertiary,
                        )
                    }
                }

                if (filteredLogs.isEmpty()) {
                    item(key = "log-empty") {
                        GlassCard {
                            Text(
                                text = L10n.pick(
                                    "No matching requests. Capture traffic or run a test.",
                                    "没有匹配的请求。先抓包或执行一次测试。",
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = NightbellColors.TextTertiary,
                            )
                        }
                    }
                }

                items(filteredLogs, key = { it.id }) { entry ->
                    LogRow(
                        entry = entry,
                        expanded = expandedLog == entry.id,
                        onToggle = {
                            expandedLog = if (expandedLog == entry.id) null else entry.id
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        if (tab == CaptureTab.ENDPOINTS) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NightbellButton(
                    text = L10n.pick("Test (${selected.size})", "测试 (${selected.size})"),
                    shortText = L10n.pick("Test", "测试"),
                    onClick = {
                        val chosen = state.endpoints.filter { it.key in selected }
                        busy = true
                        scope.launch {
                            val next = chosen.associate { it.key to probe(it) }
                            results = results + next
                            busy = false
                            tab = CaptureTab.LOGS
                            onToast(
                                ToastMessage.success(
                                    L10n.pick("Tested ${chosen.size} interfaces", "已测试 ${chosen.size} 个接口"),
                                ),
                            )
                        }
                    },
                    enabled = selected.isNotEmpty() && !busy,
                    tone = ButtonTone.Secondary,
                    modifier = Modifier.weight(1f),
                )
                NightbellButton(
                    text = L10n.pick("Add & test (${selected.size})", "加入并测试 (${selected.size})"),
                    shortText = L10n.pick("Add", "加入"),
                    onClick = {
                        val chosen = state.endpoints.filter { it.key in selected }
                        busy = true
                        scope.launch {
                            addMonitors(graph, chosen)
                            selected = emptySet()
                            busy = false
                            tab = CaptureTab.LOGS
                            onToast(
                                ToastMessage.success(
                                    L10n.pick(
                                        "Added ${chosen.size} monitors and started their first check",
                                        "已创建 ${chosen.size} 个监控并立即执行首次检测",
                                    ),
                                ),
                            )
                        }
                    },
                    enabled = selected.isNotEmpty() && !busy,
                    icon = NightbellIcons.Plus,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            NightbellButton(
                text = L10n.pick("Back to interfaces", "返回接口列表"),
                onClick = { tab = CaptureTab.ENDPOINTS },
                tone = ButtonTone.Secondary,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun EndpointRow(
    endpoint: CapturedEndpoint,
    checked: Boolean,
    result: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
    onExpand: () -> Unit,
    onAllowHost: () -> Unit,
    onTestSingle: () -> Unit,
    onAddSingle: () -> Unit,
    testEnabled: Boolean,
) {
    GlassCard(onClick = onExpand, contentPadding = 12.dp) {
        val seenAt = remember(endpoint.firstSeen) {
            SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(endpoint.firstSeen))
        }
        Row(verticalAlignment = Alignment.Top) {
            Checkbox(checked = checked, onCheckedChange = { onToggle() })
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = endpoint.method,
                        style = MaterialTheme.typography.labelSmall,
                        color = when {
                            endpoint.decrypted -> NightbellColors.Mint
                            endpoint.tlsOnly -> NightbellColors.Violet
                            else -> NightbellColors.Aqua
                        },
                        modifier = Modifier.width(64.dp),
                    )
                    Text(
                        text = endpoint.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = NightbellColors.TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(4.dp))
                // One compact line when closed; the rest lives behind a tap.
                Text(
                    text = listOfNotNull(
                        seenAt,
                        L10n.pick("${endpoint.hits} hits", "${endpoint.hits} 次"),
                        endpoint.statusCode?.let { "HTTP $it" },
                        endpoint.latencyMs?.let { "${it}ms" },
                        when {
                            endpoint.decrypted -> L10n.pick("Decrypted", "已解密")
                            endpoint.blockReason == "allowlisted" -> L10n.pick("Allowlisted", "已加白名单")
                            endpoint.tlsOnly -> L10n.pick("TLS only", "仅 TLS")
                            else -> null
                        },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = NightbellColors.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (expanded) {
                // Restored as a first-class action on every row, not only on
                // the "TLS only" ones: allowlisting is how a host's hidden
                // CONNECTs turn into real, individually selectable interfaces.
                Spacer(Modifier.height(8.dp))
                NightbellButton(
                    text = if (endpoint.blockReason == "allowlisted") {
                        L10n.pick(
                            "Host allowlisted — revisit it to see the decrypted request",
                            "已加入白名单 — 重新访问即可看到解密请求",
                        )
                    } else {
                        L10n.pick("Add this host to the allowlist", "把当前域名加入白名单")
                    },
                    shortText = if (endpoint.blockReason == "allowlisted") {
                        L10n.pick("Allowlisted", "已加白名单")
                    } else {
                        L10n.pick("Add host", "加白名单")
                    },
                    onClick = onAllowHost,
                    icon = NightbellIcons.Shield,
                    tone = ButtonTone.Secondary,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = L10n.pick("Host: ", "域名：") + endpoint.host,
                    style = MaterialTheme.typography.labelSmall,
                    color = NightbellColors.TextTertiary,
                )
                Spacer(Modifier.height(6.dp))
                val code = endpoint.statusCode
                val latency = endpoint.latencyMs
                Text(
                    text = L10n.pick(
                        "${endpoint.hits} hits" +
                            (code?.let { " · HTTP $it" }.orEmpty()) +
                            (latency?.let { " · ${it}ms" }.orEmpty()) +
                            (endpoint.error?.let { " · $it" }.orEmpty()),
                        "${endpoint.hits} 次" +
                            (code?.let { " · HTTP $it" }.orEmpty()) +
                            (latency?.let { " · ${it}ms" }.orEmpty()) +
                            (endpoint.error?.let { " · $it" }.orEmpty()),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = NightbellColors.TextTertiary,
                )
                if (endpoint.decrypted) {
                    Text(
                        text = L10n.pick("TLS decrypted · full request visible", "已解密 TLS · 完整请求可见"),
                        style = MaterialTheme.typography.labelSmall,
                        color = NightbellColors.Mint,
                    )
                } else if (endpoint.tlsOnly) {
                    val reason = endpoint.blockReason
                    val hint = when (reason) {
                        "off" -> L10n.pick("Decryption is switched off", "解密开关未开启")
                        "empty" -> L10n.pick("Allowlist is empty", "白名单为空")
                        "mismatch" -> L10n.pick(
                            "Host not in the allowlist: ",
                            "该主机不在白名单：",
                        ) + endpoint.host
                        "allowlisted" -> L10n.pick(
                            "Allowlisted — revisit this interface (or tap play to test) to see the decrypted request",
                            "已加入白名单 — 重新访问该接口或点 ▶ 测试，即可看到解密后的完整请求",
                        )
                        "handshake" -> L10n.pick(
                            "TLS handshake failed — this app does not trust user CAs (or pins its certificate). " +
                                "Open the URL in Chrome, or use the in-app interface test instead.",
                            "TLS 握手失败 — 该 App 不信任用户根证书（或做了证书固定）。" +
                                "请改用 Chrome 打开该地址，或用 App 内的接口测试功能。",
                        )
                        else -> L10n.pick(
                            "TLS only — add the host to the allowlist to see the request",
                            "仅 TLS — 把主机加入白名单即可看到完整请求",
                        )
                    }
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.labelSmall,
                        color = when (reason) {
                            "handshake" -> NightbellColors.Rose
                            "allowlisted" -> NightbellColors.Mint
                            else -> NightbellColors.Violet
                        },
                    )
                }
                if (endpoint.bodySample.isNotBlank()) {
                    Text(
                        text = L10n.pick("Request: ", "请求：") +
                            endpoint.bodySample.replace('\n', ' ').replace('\r', ' ').take(160),
                        style = MaterialTheme.typography.labelSmall,
                        color = NightbellColors.TextTertiary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (endpoint.responseSample.isNotBlank()) {
                    Text(
                        text = L10n.pick("Response: ", "响应：") +
                            endpoint.responseSample.replace('\n', ' ').replace('\r', ' ').take(160),
                        style = MaterialTheme.typography.labelSmall,
                        color = NightbellColors.TextTertiary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (result != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = result,
                        style = MaterialTheme.typography.labelSmall,
                        color = NightbellColors.Mint,
                    )
                }
                Spacer(Modifier.height(8.dp))
                NightbellButton(
                    text = L10n.pick("Add & test just this one", "只加入并测试这一个"),
                    shortText = L10n.pick("Add & test", "加入并测"),
                    onClick = onAddSingle,
                    icon = NightbellIcons.Plus,
                    tone = ButtonTone.Secondary,
                    enabled = testEnabled,
                    modifier = Modifier.fillMaxWidth(),
                )
                }
            }
            IconButton(
                onClick = onTestSingle,
                enabled = testEnabled,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = NightbellIcons.Play,
                    contentDescription = L10n.pick("Test this interface", "单独测试这个接口"),
                    tint = NightbellColors.Aqua,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun LogRow(
    entry: RequestLogEntry,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val time = remember(entry.at) {
        SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(entry.at))
    }
    GlassCard(onClick = onToggle, contentPadding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = time,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = NightbellColors.TextTertiary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = entry.method,
                style = MaterialTheme.typography.labelSmall,
                color = NightbellColors.Aqua,
                modifier = Modifier.width(60.dp),
            )
            val code = entry.statusCode
            Text(
                text = code?.let { "HTTP $it" } ?: L10n.pick("no response", "无响应"),
                style = MaterialTheme.typography.labelSmall,
                color = if (code != null && code in 200..299) NightbellColors.Mint else NightbellColors.Rose,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "${entry.latencyMs}ms",
                style = MaterialTheme.typography.labelSmall,
                color = NightbellColors.TextTertiary,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = entry.url,
            style = MaterialTheme.typography.bodySmall,
            color = NightbellColors.TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Row {
            Text(
                text = when (entry.source) {
                    "capture-https" -> L10n.pick("captured · HTTPS decrypted", "抓包 · HTTPS 已解密")
                    "capture-http" -> L10n.pick("captured · plain HTTP", "抓包 · 明文 HTTP")
                    "monitor" -> L10n.pick("monitor first check", "监控首次检测")
                    // A direct test uses the system trust store rather than the
                    // local CA, so `decrypted` is false even though the response
                    // is complete plaintext. Labelling it "not decrypted" made
                    // readable JSON look like an undecrypted TLS payload.
                    else -> if (entry.statusCode != null) {
                        L10n.pick("manual test · full response", "手动测试 · 完整响应")
                    } else {
                        L10n.pick("manual test", "手动测试")
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (entry.decrypted || (entry.source == "test" && entry.statusCode != null)) {
                    NightbellColors.Mint
                } else {
                    NightbellColors.TextTertiary
                },
                modifier = Modifier.weight(1f),
            )
            if (entry.error.isNotBlank()) {
                Text(
                    text = entry.error.take(60),
                    style = MaterialTheme.typography.labelSmall,
                    color = NightbellColors.Rose,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (expanded) {
            Spacer(Modifier.height(10.dp))
            LogBlock(
                L10n.pick("REQUEST HEADERS", "请求头"),
                entry.requestHeaders.joinToString("\n").ifBlank {
                    L10n.pick("(none)", "（无）")
                },
            )
            if (entry.requestBody.isNotBlank()) {
                LogBlock(L10n.pick("REQUEST BODY", "请求体"), entry.requestBody)
            }
            LogBlock(
                L10n.pick("RESPONSE HEADERS", "响应头"),
                entry.responseHeaders.joinToString("\n").ifBlank {
                    L10n.pick("(none)", "（无）")
                },
            )
            LogBlock(
                L10n.pick("RESPONSE BODY", "响应体"),
                entry.responseBody.ifBlank { L10n.pick("(empty)", "（空）") },
            )
            if (entry.error.isNotBlank()) {
                LogBlock(L10n.pick("ERROR", "错误"), entry.error)
            }
        }
    }
}

@Composable
private fun LogBlock(title: String, body: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        color = NightbellColors.Violet,
    )
    Spacer(Modifier.height(2.dp))
    Text(
        text = body.take(6000),
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        color = NightbellColors.TextSecondary,
    )
    Spacer(Modifier.height(8.dp))
}

/** One-shot probe that never writes to the store, but always writes a request log. */
private suspend fun probe(endpoint: CapturedEndpoint): String = withContext(Dispatchers.IO) {
    val started = System.currentTimeMillis()
    if (endpoint.tlsOnly) {
        return@withContext runCatching {
            java.net.Socket().use { socket ->
                socket.connect(InetSocketAddress(endpoint.host, endpoint.port), 5_000)
            }
            val elapsed = System.currentTimeMillis() - started
            CaptureLogRepository.record(
                RequestLogEntry(
                    at = started,
                    source = "test",
                    method = "TCP",
                    url = "${endpoint.host}:${endpoint.port}",
                    latencyMs = elapsed,
                    responseBody = L10n.pick("TCP connect succeeded", "TCP 连接成功"),
                ),
            )
            L10n.pick("TCP open · ${elapsed}ms", "TCP 可连通 · ${elapsed}ms")
        }.getOrElse {
            val message = it.message ?: ""
            CaptureLogRepository.record(
                RequestLogEntry(
                    at = started,
                    source = "test",
                    method = "TCP",
                    url = "${endpoint.host}:${endpoint.port}",
                    latencyMs = System.currentTimeMillis() - started,
                    error = message,
                ),
            )
            L10n.pick("TCP failed · ", "TCP 失败 · ") + message
        }
    }
    runCatching {
        val client = OkHttpClient.Builder()
            .callTimeout(15, TimeUnit.SECONDS)
            .followRedirects(false)
            // Tests go straight to the origin, never back through our own
            // capture proxy (which would otherwise MITM them with the local CA).
            .proxy(java.net.Proxy.NO_PROXY)
            .build()
        // Replaying a captured Accept-Encoding switches off OkHttp's transparent
        // gzip: the client then hands us compressed bytes, and turning those into
        // a String produced the mojibake that looked like "HTTPS not decrypted".
        // Hop-by-hop headers are the request's too, not something to re-send.
        val replayHeaders = endpoint.headers.filterNot { (name, _) ->
            name.lowercase() in NON_REPLAY_HEADERS
        }
        val contentType = replayHeaders
            .firstOrNull { it.first.equals("Content-Type", ignoreCase = true) }
            ?.second ?: "application/json"
        val httpMethod = endpoint.method.uppercase()
        val bodyText = if (httpMethod == "POST" || httpMethod == "PUT" || httpMethod == "PATCH") {
            endpoint.bodySample.ifBlank { "{}" }
        } else {
            ""
        }
        val request = Request.Builder().url(endpoint.url).apply {
            replayHeaders.forEach { addHeader(it.first, it.second) }
            val body = if (bodyText.isNotBlank()) {
                bodyText.toRequestBody(contentType.toMediaTypeOrNull())
            } else {
                null
            }
            method(httpMethod, body)
        }.build()
        client.newCall(request).execute().use { response ->
            // Keep the bytes: decoding compressed bytes with .string() destroys
            // them before any decompressor can run. OkHttp normally strips
            // Content-Encoding when it did the gzip itself; the explicit header
            // is the fallback for a server that compressed anyway.
            val raw = runCatching { response.peekBody(64 * 1024L).bytes() }
                .getOrDefault(ByteArray(0))
            val responseBody = readableBody(raw, response.header("Content-Encoding"))
            val elapsed = System.currentTimeMillis() - started
            CaptureLogRepository.record(
                RequestLogEntry(
                    at = started,
                    source = "test",
                    method = httpMethod,
                    url = endpoint.url,
                    requestHeaders = headerLines(request.headers),
                    requestBody = bodyText.take(16 * 1024),
                    statusCode = response.code,
                    responseHeaders = headerLines(response.headers),
                    responseBody = responseBody.take(16 * 1024),
                    latencyMs = elapsed,
                    // A direct test terminates TLS with the system trust store,
                    // not the local CA. The body is complete plaintext either
                    // way; the flag stays reserved for proxy-side decryption.
                    decrypted = endpoint.decrypted,
                ),
            )
            L10n.pick("HTTP ${response.code} · ${elapsed}ms", "HTTP ${response.code} · ${elapsed}ms")
        }
    }.getOrElse {
        val message = it.message ?: ""
        CaptureLogRepository.record(
            RequestLogEntry(
                at = started,
                source = "test",
                method = endpoint.method,
                url = endpoint.url,
                latencyMs = System.currentTimeMillis() - started,
                error = message,
                decrypted = endpoint.decrypted,
            ),
        )
        L10n.pick("Failed · ", "失败 · ") + message
    }
}

private fun headerLines(headers: okhttp3.Headers): List<String> {
    val secret = listOf("authorization", "cookie", "set-cookie", "x-api-key", "api-key", "x-auth-token")
    return (0 until headers.size)
        .map { headers.name(it) to headers.value(it) }
        .filterNot { (name, _) -> name.lowercase() in secret }
        .map { (name, value) -> "$name: $value" }
}

/**
 * Turns captured requests into real monitors using exactly the same three steps
 * the setup wizard uses: upsert, schedule, and run one check immediately. The
 * first check's result is written to the request log, with no extra request.
 */
private suspend fun addMonitors(graph: Nightbell.Graph, endpoints: List<CapturedEndpoint>) {
    val snapshot = graph.store.currentSnapshot()
    for (endpoint in endpoints) {
        val monitor = endpoint.toMonitor()
        graph.store.upsert(monitor)
        graph.scheduler.schedule(monitor, snapshot.settings)
        val result = if (monitor.enabled) graph.engine.run(monitor.id) else null
        val started = System.currentTimeMillis()
        val note = when {
            !monitor.enabled -> L10n.pick(
                "Saved as a disabled monitor; no request was replayed",
                "已保存为停用监控，未重放请求",
            )
            result == null -> L10n.pick("No result returned", "未返回结果")
            !result.ok -> result.message
            else -> ""
        }
        CaptureLogRepository.record(
            RequestLogEntry(
                at = started,
                source = "monitor",
                method = if (endpoint.tlsOnly) "TCP" else endpoint.method,
                url = if (endpoint.tlsOnly) "${endpoint.host}:${endpoint.port}" else endpoint.url,
                statusCode = result?.statusCode?.takeIf { it > 0 },
                responseBody = result?.bodyPreview.orEmpty().take(16 * 1024),
                latencyMs = result?.latencyMs ?: 0L,
                error = note,
            ),
        )
        // Keep the captured exchange on the monitor itself, so the detail
        // screen still shows the request/response after the rolling 200-entry
        // capture log has moved on. The engine.run above already logged the
        // check summary through the same per-monitor store.
        MonitorLogRepository.record(
            MonitorLogEntry(
                monitorId = monitor.id,
                at = started,
                ok = result?.ok ?: true,
                statusCode = result?.statusCode ?: 0,
                latencyMs = result?.latencyMs ?: 0L,
                message = note,
                detail = result?.detail.orEmpty().take(16 * 1024),
                bodyPreview = result?.bodyPreview.orEmpty().take(16 * 1024),
                method = endpoint.method,
                url = endpoint.url,
                requestHeaders = endpoint.headers.map { "${it.first}: ${it.second}" },
                requestBody = endpoint.bodySample.take(16 * 1024),
                responseBody = endpoint.responseSample.take(16 * 1024),
                source = "capture",
            ),
        )
    }
    graph.scheduler.ensureSweep(snapshot.settings)
}

private fun CapturedEndpoint.toMonitor(): Monitor {
    val id = UUID.randomUUID().toString()
    val now = System.currentTimeMillis()
    if (tlsOnly) {
        return Monitor(
            id = id,
            name = "$host:$port".take(48),
            kind = MonitorKind.TCP_PORT,
            network = NetworkWatch(host = host, port = port),
            intervalMinutes = 15,
            createdAt = now,
        )
    }
    val httpMethod = runCatching { HttpMethod.valueOf(this.method.uppercase()) }
        .getOrDefault(HttpMethod.GET)
    val write = httpMethod == HttpMethod.POST || httpMethod == HttpMethod.PUT ||
        httpMethod == HttpMethod.PATCH || httpMethod == HttpMethod.DELETE
    // Do not carry a captured Accept-Encoding into the monitor: OkHttp then
    // stops transparently gunzipping, and every scheduled check logs binary
    // noise instead of the response body. Framing/hop-by-hop headers are the
    // old connection's too.
    val safeHeaders = headers.filterNot { (name, _) ->
        name.lowercase() in NON_REPLAY_HEADERS
    }
    val contentType = safeHeaders
        .firstOrNull { it.first.equals("Content-Type", ignoreCase = true) }
        ?.second ?: "application/json"
    return Monitor(
        id = id,
        // Keep the query in the label: two interfaces on one path but with
        // different parameters must be distinguishable in the monitor list too.
        name = "$httpMethod ${url.removePrefix("$scheme://$host")}".take(48),
        kind = if (write) MonitorKind.ADVANCED_REQUEST else MonitorKind.HTTP_STATUS,
        url = url,
        method = httpMethod,
        headers = safeHeaders.map { HeaderPair(it.first, it.second) },
        body = if (write) bodySample else "",
        contentType = contentType,
        status = StatusExpectation(mode = StatusMode.ANY_SUCCESS),
        assertion = BodyAssertion(),
        intervalMinutes = 15,
        // Replaying a captured POST every 15 minutes would keep writing to
        // somebody's server. It is saved so the request can be edited and run,
        // but it does not start polling until the user enables it.
        enabled = !write,
        createdAt = now,
    )
}
