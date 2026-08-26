package me.river.nightbell.ui.settings

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.provider.Settings as AndroidSettings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.river.nightbell.BuildConfig
import me.river.nightbell.data.Nightbell
import me.river.nightbell.domain.CheckerHealth
import me.river.nightbell.domain.CheckerLimit
import me.river.nightbell.domain.PauseChoice
import me.river.nightbell.domain.PauseScope
import me.river.nightbell.domain.ProxyRoute
import me.river.nightbell.domain.ThemeChoice
import me.river.nightbell.domain.Validation
import me.river.nightbell.ui.Transfer
import me.river.nightbell.ui.components.AlertPolicyEditor
import me.river.nightbell.ui.components.ButtonTone
import me.river.nightbell.ui.components.GlassCard
import me.river.nightbell.ui.components.GlassIconButton
import me.river.nightbell.ui.components.IconBadge
import me.river.nightbell.ui.components.NightbellButton
import me.river.nightbell.ui.components.GlassField
import me.river.nightbell.ui.components.GlassDivider
import androidx.compose.ui.text.input.KeyboardType
import me.river.nightbell.ui.components.SectionHeader
import me.river.nightbell.ui.components.SegmentedSelector
import me.river.nightbell.ui.components.StaggeredEntrance
import me.river.nightbell.ui.components.rememberEntranceLog
import me.river.nightbell.ui.components.StepperRow
import me.river.nightbell.ui.components.ToggleRow
import me.river.nightbell.ui.icons.NightbellIcons
import me.river.nightbell.ui.rememberSettingsViewModel
import me.river.nightbell.ui.theme.Backdrop
import me.river.nightbell.ui.theme.NightbellColors
import me.river.nightbell.ui.theme.readableContentPadding
import me.river.nightbell.widget.NightbellWidgetProvider
import me.river.nightbell.widget.WidgetConfigActivity
import androidx.compose.ui.platform.testTag
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(onBack: () -> Unit, onToast: (String) -> Unit) {
    val viewModel = rememberSettingsViewModel()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val checkerHealth by viewModel.checkerHealth.collectAsStateWithLifecycle()
    val checkerLimit by viewModel.checkerLimit.collectAsStateWithLifecycle()
    val batteryOptimised by viewModel.batteryOptimised.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Read once per composition of this screen rather than observed: widgets are
    // placed and removed on the home screen, so this is only ever right at the
    // moment the user opens Settings — which is when they need it.
    val placedWidgetIds = remember {
        runCatching {
            val manager = AppWidgetManager.getInstance(context)
            manager.getAppWidgetIds(ComponentName(context, NightbellWidgetProvider::class.java))
                .toList()
        }.getOrDefault(emptyList())
    }
    // Storage Access Framework: no storage permission, and the user picks the
    // destination — which can be a cloud provider, and needs to be, since the
    // whole point is handing the file to a *different install* of the app.
    var confirmImport by remember { mutableStateOf(false) }
    val exportBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            viewModel.exportBackup { document ->
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.write(document.toByteArray())
                    } ?: error("no output stream for $uri")
                }
            }
        }
    }
    val importBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        confirmImport = false
        if (uri != null) {
            viewModel.importBackup {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)
                        ?.use { stream -> stream.readBytes().decodeToString() }
                        ?: error("no input stream for $uri")
                }
            }
        }
    }
    var notificationsAllowed by remember {
        mutableStateOf(Nightbell.install(context).alerts.hasNotificationPermission())
    }
    val entrance = rememberEntranceLog()

    // Null whenever the address is unusable, which is the same call the checker
    // makes, so the subtitle cannot claim a route the checks would not take.
    val proxyEndpoint = ProxyRoute.endpoint(settings)

    val toast = viewModel.toast
    LaunchedEffect(toast) {
        if (toast != null) {
            onToast(toast)
            viewModel.consumeToast()
        }
    }

    val topInset = WindowInsets.systemBars.asPaddingValues().calculateTopPadding()
    val bottomInset = WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()

    LazyColumn(
        Modifier.fillMaxSize().testTag("settings-list"),
        contentPadding = readableContentPadding(
            top = topInset + 12.dp,
            bottom = bottomInset + 40.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "header") {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                GlassIconButton(
                    icon = NightbellIcons.ArrowLeft,
                    onClick = onBack,
                    contentDescription = "Back",
                    accent = NightbellColors.TextSecondary,
                    size = 38.dp,
                )
                Spacer(Modifier.width(14.dp))
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.displayMedium,
                    color = NightbellColors.TextPrimary,
                )
            }
        }

        item(key = "permission") {
            AnimatedVisibility(
                visible = !notificationsAllowed,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                GlassCard(accent = NightbellColors.Amber) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(NightbellIcons.BellOff, NightbellColors.Amber, size = 40.dp)
                        Spacer(Modifier.width(13.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Notifications are blocked",
                                style = MaterialTheme.typography.titleMedium,
                                color = NightbellColors.TextPrimary,
                            )
                            Text(
                                "Nightbell can still check monitors, but it can't tell you when " +
                                    "something breaks.",
                                style = MaterialTheme.typography.bodySmall,
                                color = NightbellColors.TextTertiary,
                            )
                        }
                    }
                    Spacer(Modifier.height(13.dp))
                    NightbellButton(
                        text = "Open notification settings",
                        onClick = {
                            val intent = Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching { context.startActivity(intent) }
                        },
                        icon = NightbellIcons.Bell,
                        tone = ButtonTone.Secondary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        item(key = "master") {
            StaggeredEntrance(index = 0, key = "master", log = entrance) {
                GlassCard(accent = if (settings.masterAlertsEnabled) Color.Transparent else NightbellColors.Rose) {
                    ToggleRow(
                        title = "All alerts",
                        subtitle = if (settings.masterAlertsEnabled) {
                            "Monitors can notify you"
                        } else {
                            "Everything is muted — nothing will notify you"
                        },
                        checked = settings.masterAlertsEnabled,
                        onCheckedChange = { value ->
                            viewModel.update { it.copy(masterAlertsEnabled = value) }
                        },
                        icon = if (settings.masterAlertsEnabled) NightbellIcons.Bell else NightbellIcons.BellOff,
                        accent = NightbellColors.Aqua,
                    )
                }
            }
        }

        item(key = "defaults") {
            StaggeredEntrance(index = 1, key = "defaults", log = entrance) {
                GlassCard {
                    SectionHeader("Default alert policy", icon = NightbellIcons.Shield, accent = NightbellColors.Aqua)
                    Text(
                        text = "Applies to every monitor set to “use my global alert settings”.",
                        style = MaterialTheme.typography.bodySmall,
                        color = NightbellColors.TextTertiary,
                    )
                    Spacer(Modifier.height(10.dp))
                    AlertPolicyEditor(
                        policy = settings.defaultAlert,
                        onChange = { policy -> viewModel.updateDefaultAlert { policy } },
                        onPreviewVibration = viewModel::previewVibration,
                        onSendTestAlert = {
                            notificationsAllowed = Nightbell.install(context).alerts.hasNotificationPermission()
                            viewModel.sendTestAlert()
                        },
                        showMasterToggle = false,
                    )
                }
            }
        }

        item(key = "scheduling") {
            StaggeredEntrance(index = 2, key = "scheduling", log = entrance) {
                GlassCard {
                    SectionHeader("Background checks", icon = NightbellIcons.Radar, accent = NightbellColors.Violet)
                    ToggleRow(
                        title = "Run checks in the background",
                        subtitle = "Uses WorkManager; Android batches work to save battery",
                        checked = settings.backgroundChecksEnabled,
                        onCheckedChange = { v -> viewModel.update { it.copy(backgroundChecksEnabled = v) } },
                        icon = NightbellIcons.Power,
                        accent = NightbellColors.Violet,
                    )
                    ToggleRow(
                        title = "Wi-Fi only",
                        subtitle = "Skip checks on metered mobile data",
                        checked = settings.onlyOnUnmeteredNetwork,
                        onCheckedChange = { v -> viewModel.update { it.copy(onlyOnUnmeteredNetwork = v) } },
                        icon = NightbellIcons.Wifi,
                        accent = NightbellColors.Violet,
                    )
                    Spacer(Modifier.height(6.dp))
                    StepperRow(
                        title = "Default interval",
                        value = settings.defaultIntervalMinutes,
                        onValueChange = { v -> viewModel.update { it.copy(defaultIntervalMinutes = v) } },
                        range = 1..1440,
                        suffix = "m",
                        icon = NightbellIcons.Clock,
                        accent = NightbellColors.Violet,
                    )
                    StepperRow(
                        title = "Default timeout",
                        value = settings.defaultTimeoutSeconds,
                        onValueChange = { v -> viewModel.update { it.copy(defaultTimeoutSeconds = v) } },
                        range = 1..120,
                        suffix = "s",
                        icon = NightbellIcons.Gauge,
                        accent = NightbellColors.Violet,
                    )
                    StepperRow(
                        title = "History kept",
                        value = settings.historyDepth,
                        onValueChange = { v -> viewModel.update { it.copy(historyDepth = v) } },
                        range = 10..300,
                        step = 10,
                        icon = NightbellIcons.History,
                        accent = NightbellColors.Violet,
                    )
                }
            }
        }

        item(key = "checker-health") {
            StaggeredEntrance(index = 3, key = "checker-health", log = entrance) {
                CheckerHealthCard(
                    health = checkerHealth,
                    limit = checkerLimit,
                    batteryOptimised = batteryOptimised,
                    strict = settings.strictForegroundMonitoring,
                    onOpenBatterySettings = {
                        runCatching { context.startActivity(viewModel.batterySettingsIntent()) }
                    },
                )
            }
        }

        item(key = "strict") {
            StaggeredEntrance(index = 4, key = "strict", log = entrance) {
                GlassCard(accent = if (settings.strictForegroundMonitoring) NightbellColors.Amber else Color.Transparent) {
                    SectionHeader("Strict cadence", icon = NightbellIcons.Zap, accent = NightbellColors.Amber)
                    Text(
                        text = "WorkManager is battery-friendly but Doze can defer a check for " +
                            "a long time, and its periodic minimum is 15 minutes. Strict mode " +
                            "runs a foreground service that keeps your intervals exactly.",
                        style = MaterialTheme.typography.bodySmall,
                        color = NightbellColors.TextTertiary,
                    )
                    Spacer(Modifier.height(8.dp))
                    ToggleRow(
                        title = "Strict foreground monitoring",
                        subtitle = if (settings.strictForegroundMonitoring) {
                            "On — a permanent notification is showing"
                        } else {
                            "Off — checks are best-effort, batched by Android"
                        },
                        checked = settings.strictForegroundMonitoring,
                        onCheckedChange = { v ->
                            viewModel.update { it.copy(strictForegroundMonitoring = v) }
                        },
                        icon = NightbellIcons.Power,
                        accent = NightbellColors.Amber,
                    )
                    AnimatedVisibility(
                        visible = settings.strictForegroundMonitoring,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        Column {
                            Spacer(Modifier.height(6.dp))
                            WarningPanel(
                                "This costs real battery. Nightbell wakes up as often as your " +
                                    "tightest interval needs and holds a persistent " +
                                    "notification Android will not let you dismiss.\n\n" +
                                    "Background checks stay armed underneath as a repair " +
                                    "sweep, so nothing is lost if the OS kills the service.",
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "An unacknowledged URGENT outage starts the same service on its " +
                            "own, whatever this is set to, and stops it once you confirm.",
                        style = MaterialTheme.typography.bodySmall,
                        color = NightbellColors.TextTertiary,
                    )
                }
            }
        }

        item(key = "latency") {
            StaggeredEntrance(index = 5, key = "latency", log = entrance) {
                GlassCard {
                    SectionHeader("Latency budget", icon = NightbellIcons.Gauge, accent = NightbellColors.Amber)
                    Text(
                        text = "The default for monitors that don't set their own. A successful " +
                            "check slower than this is DEGRADED — up, but not well.",
                        style = MaterialTheme.typography.bodySmall,
                        color = NightbellColors.TextTertiary,
                    )
                    Spacer(Modifier.height(8.dp))
                    ToggleRow(
                        title = "Flag slow responses",
                        subtitle = if (settings.defaultLatencySloMs > 0) {
                            "Degraded above ${settings.defaultLatencySloMs} ms"
                        } else {
                            "Off — only up and down, never degraded"
                        },
                        checked = settings.defaultLatencySloMs > 0,
                        onCheckedChange = { on ->
                            viewModel.update { it.copy(defaultLatencySloMs = if (on) 2_500 else 0) }
                        },
                        icon = NightbellIcons.Activity,
                        accent = NightbellColors.Amber,
                    )
                    AnimatedVisibility(
                        visible = settings.defaultLatencySloMs > 0,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        StepperRow(
                            title = "Degraded above",
                            value = settings.defaultLatencySloMs.coerceAtLeast(100),
                            onValueChange = { v -> viewModel.update { it.copy(defaultLatencySloMs = v) } },
                            range = 100..60_000,
                            step = 100,
                            suffix = "ms",
                            icon = NightbellIcons.Gauge,
                            accent = NightbellColors.Amber,
                        )
                    }

                    AnimatedVisibility(
                        visible = settings.defaultLatencySloMs > 0,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        Column {
                            GlassDivider(Modifier.padding(vertical = 12.dp))
                            Text(
                                text = "A latency measured from this phone is your connection " +
                                    "plus the server. On bad wifi that makes everything look " +
                                    "slow at once. Nightbell can time a known-good endpoint " +
                                    "alongside the checks and subtract whatever your " +
                                    "connection is adding.",
                                style = MaterialTheme.typography.bodySmall,
                                color = NightbellColors.TextTertiary,
                            )
                            Spacer(Modifier.height(8.dp))
                            ToggleRow(
                                title = "Discount my connection",
                                subtitle = if (settings.latencyBaselineEnabled) {
                                    "Slow readings are checked against a reference first"
                                } else {
                                    "Off — a slow connection will read as slow services"
                                },
                                checked = settings.latencyBaselineEnabled,
                                onCheckedChange = { on ->
                                    viewModel.update { it.copy(latencyBaselineEnabled = on) }
                                },
                                icon = NightbellIcons.Wifi,
                                accent = NightbellColors.Aqua,
                            )
                            AnimatedVisibility(
                                visible = settings.latencyBaselineEnabled,
                                enter = fadeIn() + expandVertically(),
                                exit = fadeOut() + shrinkVertically(),
                            ) {
                                Column {
                                    Spacer(Modifier.height(8.dp))
                                    GlassField(
                                        value = settings.latencyReferenceUrl,
                                        onValueChange = { v ->
                                            viewModel.update { it.copy(latencyReferenceUrl = v.trim()) }
                                        },
                                        label = "Reference endpoint",
                                        placeholder = "https://www.gstatic.com/generate_204",
                                        helper = "Wants to be always up and cheap to answer. If your " +
                                            "network blocks it, latency is judged raw — nothing breaks.",
                                        leadingIcon = NightbellIcons.Globe,
                                        accent = NightbellColors.Aqua,
                                        keyboardType = KeyboardType.Uri,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        item(key = "pause") {
            StaggeredEntrance(index = 6, key = "pause", log = entrance) {
                GlassCard {
                    SectionHeader("Pause button", icon = NightbellIcons.Pause, accent = NightbellColors.Amber)
                    Text(
                        text = "The pause on the dashboard banner. Nightbell already stops " +
                            "checking when the phone has no connection at all, but one bar in " +
                            "a forest still counts as online: every check times out, every " +
                            "monitor goes down at once, and none of it is about your services.",
                        style = MaterialTheme.typography.bodySmall,
                        color = NightbellColors.TextTertiary,
                    )
                    Spacer(Modifier.height(10.dp))
                    SegmentedSelector(
                        options = PauseChoice.entries.toList(),
                        selected = settings.pauseChoice,
                        onSelect = { choice -> viewModel.update { it.copy(pauseChoice = choice) } },
                        label = { it.label },
                        accent = NightbellColors.Amber,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = when (settings.pauseChoice) {
                            PauseChoice.STOP_CHECKS -> PauseScope.STOP_CHECKS.blurb
                            PauseChoice.ALERTS_ONLY -> PauseScope.ALERTS_ONLY.blurb
                            PauseChoice.ASK -> "The button asks which one every time, then how long."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = NightbellColors.TextTertiary,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "How long is always asked: 30 minutes up to 8 hours, or until you " +
                            "turn it back on. A timed pause lifts itself, which an indefinite one " +
                            "cannot, so it is the safer one to reach for at 2am.",
                        style = MaterialTheme.typography.bodySmall,
                        color = NightbellColors.TextTertiary,
                    )
                }
            }
        }

        item(key = "proxy") {
            StaggeredEntrance(index = 7, key = "proxy", log = entrance) {
                GlassCard {
                    SectionHeader("SOCKS5 proxy", icon = NightbellIcons.Shield, accent = NightbellColors.Aqua)
                    Text(
                        text = "Reach a Tor or I2P hidden service without putting the whole " +
                            "phone in VPN mode. Nothing is routed until a monitor asks for " +
                            "it: the switch is per monitor, on its Cadence step.",
                        style = MaterialTheme.typography.bodySmall,
                        color = NightbellColors.TextTertiary,
                    )
                    Spacer(Modifier.height(8.dp))
                    ToggleRow(
                        title = "Offer a proxy to monitors",
                        subtitle = if (proxyEndpoint != null) {
                            "Available at ${proxyEndpoint.host}:${proxyEndpoint.port}"
                        } else if (settings.socksProxyEnabled) {
                            "Needs a host and a port between 1 and 65535"
                        } else {
                            "Off, every check goes out directly"
                        },
                        checked = settings.socksProxyEnabled,
                        onCheckedChange = { on ->
                            viewModel.update { it.copy(socksProxyEnabled = on) }
                        },
                        icon = NightbellIcons.Shield,
                        accent = NightbellColors.Aqua,
                    )
                    AnimatedVisibility(
                        visible = settings.socksProxyEnabled,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        // Both fields below draw from local state once they have been
                        // touched, and from the store until then.
                        //
                        // Binding a text field straight to the store looks right and
                        // types appallingly: every keystroke writes the whole snapshot
                        // to DataStore, the field's value only comes back when that
                        // write lands, and the cursor jumps to the end when it does.
                        // Typing an address at any speed drops and reorders characters.
                        // "127.0.0.1" arrived as "27.0.0.11" on a device.
                        //
                        // Null means untouched, so the stored value still shows on
                        // first open and after the settings flow has loaded.
                        var typedHost by rememberSaveable { mutableStateOf<String?>(null) }
                        var typedPort by rememberSaveable { mutableStateOf<String?>(null) }
                        Column {
                            Spacer(Modifier.height(8.dp))
                            GlassField(
                                value = typedHost ?: settings.socksProxyHost,
                                onValueChange = { v ->
                                    typedHost = v
                                    viewModel.update { it.copy(socksProxyHost = v.trim()) }
                                },
                                label = "Proxy host",
                                placeholder = "127.0.0.1",
                                helper = "An IPv6 literal works here too, with or without " +
                                    "brackets: ::1 and [::1] both dial the same place.",
                                leadingIcon = NightbellIcons.Server,
                                accent = NightbellColors.Aqua,
                                note = if (settings.socksProxyHost.isBlank()) {
                                    Validation.Note(
                                        Validation.Field.PROXY,
                                        Validation.Severity.ERROR,
                                        "A host is required",
                                    )
                                } else {
                                    null
                                },
                            )
                            Spacer(Modifier.height(8.dp))
                            GlassField(
                                value = typedPort ?: if (settings.socksProxyPort > 0) {
                                    settings.socksProxyPort.toString()
                                } else {
                                    ""
                                },
                                onValueChange = { v ->
                                    val digits = v.filter { it.isDigit() }.take(5)
                                    typedPort = digits
                                    viewModel.update { it.copy(socksProxyPort = digits.toIntOrNull() ?: 0) }
                                },
                                label = "Proxy port",
                                placeholder = "9050",
                                helper = "Tor listens on 9050 by default, and Orbot on 9050 too. " +
                                    "I2P's HTTP proxy is 4444.",
                                leadingIcon = NightbellIcons.Link,
                                accent = NightbellColors.Aqua,
                                keyboardType = KeyboardType.Number,
                                note = if (settings.socksProxyPort !in ProxyRoute.PORTS) {
                                    Validation.Note(
                                        Validation.Field.PROXY,
                                        Validation.Severity.ERROR,
                                        "Ports run from 1 to 65535",
                                    )
                                } else {
                                    null
                                },
                            )
                            Spacer(Modifier.height(8.dp))
                            StepperRow(
                                title = "Allow routed checks",
                                value = settings.proxiedTimeoutSeconds,
                                onValueChange = { v ->
                                    viewModel.update { it.copy(proxiedTimeoutSeconds = v) }
                                },
                                range = 5..180,
                                step = 5,
                                suffix = "s",
                                icon = NightbellIcons.Clock,
                                accent = NightbellColors.Aqua,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "Longer than an ordinary check on purpose. Most of the wait " +
                                    "is Tor building a circuit to the service, which says nothing " +
                                    "about whether the service is healthy, and 15 seconds reports " +
                                    "a working hidden service as down. Tor itself waits 120.",
                                style = MaterialTheme.typography.bodySmall,
                                color = NightbellColors.TextTertiary,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "The proxy resolves the hostname, not this phone, which " +
                                    "is what makes an .onion address work and keeps the name " +
                                    "off the device's own DNS. Page-element monitors are the " +
                                    "exception: they render in a WebView, and Android's WebView " +
                                    "cannot speak SOCKS at all, so those always go out directly.",
                                style = MaterialTheme.typography.bodySmall,
                                color = NightbellColors.TextTertiary,
                            )
                        }
                    }
                }
            }
        }

        item(key = "widgets") {
            StaggeredEntrance(index = 8, key = "widgets", log = entrance) {
                WidgetsCard(
                    ids = placedWidgetIds,
                    onConfigure = { id ->
                        runCatching {
                            context.startActivity(
                                Intent(context, WidgetConfigActivity::class.java)
                                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    },
                )
            }
        }

        item(key = "certificates") {
            StaggeredEntrance(index = 9, key = "certificates", log = entrance) {
                GlassCard {
                    SectionHeader(
                        "TLS certificates",
                        icon = NightbellIcons.Shield,
                        accent = NightbellColors.Mint,
                    )
                    Text(
                        text = "Every HTTPS check already completes a handshake, and the " +
                            "handshake carries the certificate's expiry date. Reading it costs " +
                            "nothing and catches the one outage you can see coming.",
                        style = MaterialTheme.typography.bodySmall,
                        color = NightbellColors.TextTertiary,
                    )
                    Spacer(Modifier.height(10.dp))
                    ToggleRow(
                        title = "Warn before certificates expire",
                        subtitle = if (settings.certAlertsEnabled) {
                            "Advisory only — never wakes you, never bypasses quiet hours"
                        } else {
                            "A cert can lapse without warning"
                        },
                        checked = settings.certAlertsEnabled,
                        onCheckedChange = { v -> viewModel.update { it.copy(certAlertsEnabled = v) } },
                        icon = NightbellIcons.Shield,
                        accent = NightbellColors.Mint,
                    )
                    if (settings.certAlertsEnabled) {
                        Spacer(Modifier.height(6.dp))
                        StepperRow(
                            title = "Start warning",
                            value = settings.certWarnDays,
                            onValueChange = { v -> viewModel.update { it.copy(certWarnDays = v) } },
                            range = 1..90,
                            suffix = "d",
                            icon = NightbellIcons.Clock,
                            accent = NightbellColors.Amber,
                        )
                        StepperRow(
                            title = "Urgent below",
                            value = settings.certCriticalDays,
                            onValueChange = { v -> viewModel.update { it.copy(certCriticalDays = v) } },
                            range = 0..30,
                            suffix = "d",
                            icon = NightbellIcons.Zap,
                            accent = NightbellColors.Rose,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Said once when it crosses each threshold, then at most once " +
                                "a day. A renewal clears the notice on the next check. " +
                                "Plain-HTTP monitors have no certificate and are skipped.",
                            style = MaterialTheme.typography.bodySmall,
                            color = NightbellColors.TextTertiary,
                        )
                    }
                }
            }
        }

        item(key = "favicons") {
            StaggeredEntrance(index = 10, key = "favicons", log = entrance) {
                GlassCard {
                    SectionHeader("Site icons", icon = NightbellIcons.Globe, accent = NightbellColors.Sky)
                    Text(
                        text = "Website-element monitors are badged with the site's own favicon, " +
                            "cached for a month so the dashboard isn't hitting somebody else's " +
                            "server every time you scroll. If a site has changed its mark, this " +
                            "fetches them all again now.",
                        style = MaterialTheme.typography.bodySmall,
                        color = NightbellColors.TextTertiary,
                    )
                    Spacer(Modifier.height(10.dp))
                    NightbellButton(
                        text = if (viewModel.refetchingFavicons) "Refetching…" else "Refetch site icons",
                        onClick = viewModel::refetchFavicons,
                        icon = NightbellIcons.Refresh,
                        tone = ButtonTone.Secondary,
                        loading = viewModel.refetchingFavicons,
                        modifier = Modifier.fillMaxWidth().testTag("refetch-favicons"),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "An icon that can't be fetched keeps the one it had — you won't " +
                            "end up with blank badges because a site was briefly down.",
                        style = MaterialTheme.typography.bodySmall,
                        color = NightbellColors.TextTertiary,
                    )
                }
            }
        }

        item(key = "backup") {
            StaggeredEntrance(index = 11, key = "backup", log = entrance) {
                GlassCard {
                    SectionHeader("Backup and transfer", icon = NightbellIcons.Export, accent = NightbellColors.Violet)
                    Text(
                        text = "Writes every monitor, its history and your settings to a JSON " +
                            "file, wherever you choose to put it. Nothing is uploaded — the file " +
                            "goes where you send it and nowhere else.",
                        style = MaterialTheme.typography.bodySmall,
                        color = NightbellColors.TextTertiary,
                    )
                    Spacer(Modifier.height(10.dp))
                    NightbellButton(
                        text = if (viewModel.transfer == Transfer.EXPORT) {
                            "Writing the file…"
                        } else {
                            "Export to a file"
                        },
                        onClick = { exportBackup.launch(backupFileName()) },
                        icon = NightbellIcons.Export,
                        tone = ButtonTone.Secondary,
                        loading = viewModel.transfer == Transfer.EXPORT,
                        enabled = !viewModel.transferring,
                        modifier = Modifier.fillMaxWidth().testTag("export-backup"),
                    )
                    Spacer(Modifier.height(8.dp))
                    AnimatedVisibility(
                        visible = !confirmImport,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        // Reading, decoding and replacing the store takes long
                        // enough on a real backup to look like nothing happened.
                        // The button says what it is doing rather than just going
                        // grey, which is indistinguishable from a dead tap.
                        NightbellButton(
                            text = if (viewModel.transfer == Transfer.IMPORT) {
                                "Reading the file…"
                            } else {
                                "Import from a file"
                            },
                            onClick = { confirmImport = true },
                            icon = NightbellIcons.Import,
                            tone = ButtonTone.Secondary,
                            loading = viewModel.transfer == Transfer.IMPORT,
                            enabled = !viewModel.transferring,
                            modifier = Modifier.fillMaxWidth().testTag("import-backup"),
                        )
                    }
                    AnimatedVisibility(
                        visible = confirmImport,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        Column {
                            Text(
                                text = "Import replaces everything",
                                style = MaterialTheme.typography.titleMedium,
                                color = NightbellColors.TextPrimary,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "The monitors and history on this device are dropped and " +
                                    "the file's take their place. Export first if you want to " +
                                    "keep them. This can't be undone.",
                                style = MaterialTheme.typography.bodySmall,
                                color = NightbellColors.TextTertiary,
                            )
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                NightbellButton(
                                    text = "Cancel",
                                    onClick = { confirmImport = false },
                                    tone = ButtonTone.Secondary,
                                    modifier = Modifier.weight(1f),
                                )
                                NightbellButton(
                                    text = "Choose file",
                                    // Anything, rather than application/json: mime
                                    // detection for .json is inconsistent across
                                    // storage providers and a filter that hides the
                                    // user's own backup is worse than one that shows
                                    // too much. The codec validates what comes back.
                                    onClick = { importBackup.launch(arrayOf("*/*")) },
                                    tone = ButtonTone.Danger,
                                    icon = NightbellIcons.Import,
                                    modifier = Modifier.weight(1f).testTag("confirm-import"),
                                )
                            }
                        }
                    }
                    GlassDivider(Modifier.padding(vertical = 12.dp))
                    Text(
                        text = "Moving to another phone",
                        style = MaterialTheme.typography.labelMedium,
                        color = NightbellColors.TextSecondary,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Export on the old phone, import on the new one. Android keeps " +
                            "each app's data to itself, so a file you carry across is the only " +
                            "route — and it has to be written before the old install goes away.",
                        style = MaterialTheme.typography.bodySmall,
                        color = NightbellColors.TextTertiary,
                    )
                }
            }
        }

        item(key = "help") {
            StaggeredEntrance(index = 12, key = "help", log = entrance) {
                HelpCard()
            }
        }

        item(key = "appearance") {
            StaggeredEntrance(index = 13, key = "appearance", log = entrance) {
                GlassCard {
                    SectionHeader("Appearance", icon = NightbellIcons.Eye, accent = NightbellColors.Aqua)
                    Text(
                        text = "Nightbell is built dark-first — the glass reads on depth, and depth " +
                            "is easiest to draw on black. The light scheme is a designed " +
                            "counterpart rather than an inversion: same layout, re-picked " +
                            "colours, and the status hues darkened until they stay legible.",
                        style = MaterialTheme.typography.bodySmall,
                        color = NightbellColors.TextTertiary,
                    )
                    Spacer(Modifier.height(12.dp))
                    SegmentedSelector(
                        options = ThemeChoice.entries.toList(),
                        selected = settings.theme,
                        onSelect = { choice -> viewModel.update { it.copy(theme = choice) } },
                        label = { it.label },
                        modifier = Modifier.testTag("theme-selector"),
                    )
                }
            }
        }

        item(key = "motion") {
            StaggeredEntrance(index = 14, key = "motion", log = entrance) {
                GlassCard {
                    SectionHeader("Motion", icon = NightbellIcons.Sparkle, accent = NightbellColors.Mint)
                    Text(
                        text = when {
                            settings.motionIntensity < 0.06f -> "Animations off — everything snaps into place."
                            settings.motionIntensity < 0.7f -> "Subtle motion."
                            settings.motionIntensity < 1.2f -> "Full Nightbell: aurora, sonar rings, the lot."
                            else -> "Extra lively."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = NightbellColors.TextTertiary,
                    )
                    Spacer(Modifier.height(6.dp))
                    Slider(
                        value = settings.motionIntensity,
                        onValueChange = { v -> viewModel.update { it.copy(motionIntensity = v) } },
                        valueRange = 0f..1.4f,
                        colors = SliderDefaults.colors(
                            thumbColor = NightbellColors.Mint,
                            activeTrackColor = NightbellColors.Mint,
                            inactiveTrackColor = NightbellColors.GlassFill,
                        ),
                    )
                    Spacer(Modifier.height(10.dp))
                    ToggleRow(
                        title = "Frosted glass",
                        subtitle = when {
                            !Backdrop.isSupported ->
                                "Needs Android 12 — this device uses solid panes"
                            settings.realBlurEnabled ->
                                "Real backdrop blur behind floating sheets"
                            else ->
                                "Solid panes — cheaper to draw"
                        },
                        checked = settings.realBlurEnabled && Backdrop.isSupported,
                        onCheckedChange = { v -> viewModel.update { it.copy(realBlurEnabled = v) } },
                        icon = NightbellIcons.Layers,
                        accent = NightbellColors.Mint,
                        enabled = Backdrop.isSupported,
                    )
                }
            }
        }

        item(key = "about") {
            StaggeredEntrance(index = 15, key = "about", log = entrance) {
                GlassCard {
                    SectionHeader("About", icon = NightbellIcons.Info, accent = NightbellColors.Sky)
                    AboutRow("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    AboutRow("Android", "API ${Build.VERSION.SDK_INT} · ${Build.MODEL}")
                    AboutRow("Storage", "Local only — nothing leaves the device")
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "Nightbell checks HTTP endpoints, asserts on response bodies, and " +
                            "watches individual elements on real rendered pages. Background " +
                            "cadence is best-effort: Android may delay work in Doze.",
                        style = MaterialTheme.typography.bodySmall,
                        color = NightbellColors.TextTertiary,
                    )
                }
            }
        }
    }
}

/**
 * Checker health, and the honest account of what Android will and will not do.
 *
 * The point of this card is that the app now has somewhere to *show* things it
 * used to notify about. Before 1.6.0 there were two verdicts — fine, or down —
 * so Doze deferring work, a stopped service and a cancelled coroutine all had to
 * be reported as an outage, which is how a healthy fleet ended up buzzing
 * "Checker crashed" six times at once. Delay and restriction are visible here and
 * are never a notification; only a verified, repeated fault inside Nightbell's own
 * code is.
 */
@Composable
private fun CheckerHealthCard(
    health: CheckerHealth.State,
    limit: CheckerLimit,
    batteryOptimised: Boolean,
    strict: Boolean,
    onOpenBatterySettings: () -> Unit,
) {
    val crashed = health.kind == CheckerHealth.Kind.CRASHED
    val accent = when {
        crashed -> NightbellColors.Rose
        limit.isLimited -> NightbellColors.Amber
        else -> Color.Transparent
    }
    GlassCard(accent = accent) {
        SectionHeader(
            "Checker health",
            icon = NightbellIcons.Activity,
            accent = if (accent == Color.Transparent) NightbellColors.Mint else accent,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(
                when {
                    crashed -> NightbellIcons.Warning
                    limit == CheckerLimit.OFFLINE -> NightbellIcons.WifiOff
                    limit.isLimited -> NightbellIcons.Clock
                    else -> NightbellIcons.Check
                },
                if (accent == Color.Transparent) NightbellColors.Mint else accent,
                size = 40.dp,
            )
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (crashed) "Nightbell can't complete its checks" else limit.headline,
                    style = MaterialTheme.typography.titleMedium,
                    color = NightbellColors.TextPrimary,
                )
                Text(
                    text = if (crashed) health.summary else limit.hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = NightbellColors.TextTertiary,
                )
            }
        }

        if (crashed && health.lastSignature.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            WarningPanel(
                "Last internal error: ${health.lastSignature}. This is a fault in " +
                    "Nightbell, not in the sites you are watching — their status is " +
                    "unchanged. It clears as soon as one check completes.",
            )
        }

        GlassDivider(Modifier.padding(vertical = 12.dp))

        Text(
            text = "What Android allows",
            style = MaterialTheme.typography.labelMedium,
            color = NightbellColors.TextSecondary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Background checks run through WorkManager, whose shortest " +
                "possible repeat is 15 minutes — a platform floor, not a Nightbell " +
                "setting. A tighter interval than that is honoured at 15-minute " +
                "granularity in the background, and exactly only while strict " +
                "mode's foreground service is running. Doze can defer any of it " +
                "further. None of that is an outage and Nightbell will never notify " +
                "you about it.",
            style = MaterialTheme.typography.bodySmall,
            color = NightbellColors.TextTertiary,
        )

        if (batteryOptimised && !strict) {
            Spacer(Modifier.height(12.dp))
            NightbellButton(
                text = "Exempt Nightbell from battery optimisation",
                onClick = onOpenBatterySettings,
                icon = NightbellIcons.Power,
                tone = ButtonTone.Secondary,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Optional, and it helps — but it is not a guarantee. Only the " +
                    "foreground service is.",
                style = MaterialTheme.typography.bodySmall,
                color = NightbellColors.TextTertiary,
            )
        }
    }
}

/**
 * A route into a placed widget's settings from inside the app.
 *
 * The third of three, and the one that always works. Android only offers
 * "reconfigure" behind a long-press and only on API 31+; the widget's own cog can
 * be switched off; this cannot be missed. Reported as a real problem: once the
 * widget was on the home screen, its configuration was unreachable.
 */
@Composable
private fun WidgetsCard(ids: List<Int>, onConfigure: (Int) -> Unit) {
    GlassCard {
        SectionHeader("Home-screen widgets", icon = NightbellIcons.Layers, accent = NightbellColors.Sky)
        if (ids.isEmpty()) {
            Text(
                text = "None placed yet. Long-press your home screen, choose Widgets, " +
                    "and look for Nightbell.",
                style = MaterialTheme.typography.bodySmall,
                color = NightbellColors.TextTertiary,
            )
            return@GlassCard
        }
        Text(
            text = "Colours, transparency, density and which monitors appear are set per " +
                "widget. You can also tap the cog on the widget itself.",
            style = MaterialTheme.typography.bodySmall,
            color = NightbellColors.TextTertiary,
        )
        Spacer(Modifier.height(10.dp))
        ids.forEachIndexed { index, id ->
            if (index > 0) Spacer(Modifier.height(8.dp))
            NightbellButton(
                text = if (ids.size == 1) "Configure widget" else "Configure widget ${index + 1}",
                onClick = { onConfigure(id) },
                icon = NightbellIcons.Sliders,
                tone = ButtonTone.Secondary,
                modifier = Modifier.fillMaxWidth().testTag("configure-widget-$id"),
            )
        }
    }
}

/**
 * `nightbell-backup-2026-08-04-0031.json` — chronological, so a folder of them sorts
 * itself, and unambiguous about which export is which.
 */
private fun backupFileName(nowMs: Long = System.currentTimeMillis()): String =
    "nightbell-backup-" + SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date(nowMs)) + ".json"

/** Amber-bordered panel for the "this costs you something" copy. */
@Composable
private fun WarningPanel(text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(NightbellColors.Amber.copy(alpha = 0.10f))
            .padding(13.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            NightbellIcons.Warning,
            contentDescription = null,
            tint = NightbellColors.Amber,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = NightbellColors.TextSecondary,
        )
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = NightbellColors.TextTertiary,
            modifier = Modifier.width(96.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = NightbellColors.TextSecondary,
        )
    }
}
