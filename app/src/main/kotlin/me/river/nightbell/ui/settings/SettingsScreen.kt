package me.river.nightbell.ui.settings

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import androidx.core.net.toUri
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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ripple
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import kotlinx.coroutines.launch
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
import me.river.nightbell.domain.AppUpdate
import me.river.nightbell.domain.CheckerHealth
import me.river.nightbell.domain.CheckerLimit
import me.river.nightbell.domain.PauseChoice
import me.river.nightbell.domain.PauseScope
import me.river.nightbell.domain.ProxyRoute
import me.river.nightbell.domain.ConnectivityReference
import me.river.nightbell.domain.GitHubWatch
import me.river.nightbell.domain.ThemeChoice
import me.river.nightbell.domain.UpdateSource
import me.river.nightbell.ui.update.UpdateActions
import me.river.nightbell.ui.update.rememberUpdateInstall
import me.river.nightbell.domain.Validation
import me.river.nightbell.ui.Transfer
import me.river.nightbell.ui.components.AlertPolicyEditor
import me.river.nightbell.ui.components.ButtonTone
import me.river.nightbell.ui.components.ChipSelector
import me.river.nightbell.ui.components.GlassCard
import me.river.nightbell.ui.components.LabelledRow
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
import me.river.nightbell.ui.theme.LocalNightbellMotion
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
import me.river.nightbell.ui.theme.NightbellRadii

@Composable
fun SettingsScreen(onBack: () -> Unit, onToast: (String) -> Unit) {
    val viewModel = rememberSettingsViewModel()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val checkerHealth by viewModel.checkerHealth.collectAsStateWithLifecycle()
    val checkerLimit by viewModel.checkerLimit.collectAsStateWithLifecycle()
    val batteryOptimised by viewModel.batteryOptimised.collectAsStateWithLifecycle()
    val githubTokenRedacted by viewModel.githubTokenRedacted.collectAsStateWithLifecycle()
    val appUpdate by viewModel.appUpdate.collectAsStateWithLifecycle()
    val updateInstall = rememberUpdateInstall()
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

    // ---- what lives on which tab -------------------------------------------
    //
    // One screen of twenty cards was a screen nobody read to the bottom of. The
    // split is by the question being asked, not by how the code is organised:
    //  - **Alerts**, what gets announced, and how loudly.
    //  - **Checks**, how the checking itself runs, and whether it is keeping up.
    //  - **Look**, this app's appearance, and the surfaces outside it.
    //  - **About**, the app as a thing you installed: updates, your data, help.
    //
    // Held as `LazyListScope` lambdas rather than as composables so every card
    // keeps reading the screen's own locals. The alternative was threading a
    // dozen parameters through four call sites for no gain.
    val alertsItems: LazyListScope.() -> Unit = {
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

            item(key = "confirm-outages") {
                StaggeredEntrance(index = 1, key = "confirm-outages", log = entrance) {
                    GlassCard {
                        ToggleRow(
                            title = "Check my connection first",
                            subtitle = when {
                                // Named, not silently ignored. The switch that
                                // governs whether this app talks to the reference
                                // host at all lives on another tab, and a control
                                // that quietly does nothing is worse than one that
                                // says why it cannot.
                                !settings.latencyBaselineEnabled ->
                                    "Needs the reference endpoint, which is off " +
                                        "under Checks, discount my connection"

                                settings.confirmOutagesEnabled ->
                                    "A monitor that cannot be reached at all is only " +
                                        "paged once the reference endpoint answers"

                                else -> "Off, a lost signal pages for every monitor at once"
                            },
                            enabled = settings.latencyBaselineEnabled,
                            checked = settings.confirmOutagesEnabled && settings.latencyBaselineEnabled,
                            onCheckedChange = { value ->
                                viewModel.update { it.copy(confirmOutagesEnabled = value) }
                            },
                            icon = NightbellIcons.Wifi,
                            accent = NightbellColors.Aqua,
                            modifier = Modifier.testTag("confirm-outages"),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            // Says what it will not do, because that is the part
                            // worth trusting: a feature that can mute an outage
                            // has to explain where its limits are.
                            text = "For a failure that never reached anything: no DNS, no route, " +
                                "no reply. A server that answers badly still pages, and so does " +
                                "everything else if the reference cannot be checked. The endpoint " +
                                "is the one under Checks, latency baseline.",
                            style = MaterialTheme.typography.bodySmall,
                            color = NightbellColors.TextTertiary,
                        )
                    }
                }
            }

            item(key = "defaults") {
                StaggeredEntrance(index = 2, key = "defaults", log = entrance) {
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

            item(key = "pause") {
                StaggeredEntrance(index = 3, key = "pause", log = entrance) {
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

            item(key = "certificates") {
                StaggeredEntrance(index = 4, key = "certificates", log = entrance) {
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
    }
    val checksItems: LazyListScope.() -> Unit = {
            item(key = "scheduling") {
                StaggeredEntrance(index = 0, key = "scheduling", log = entrance) {
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
                StaggeredEntrance(index = 1, key = "checker-health", log = entrance) {
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
                StaggeredEntrance(index = 2, key = "strict", log = entrance) {
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
                StaggeredEntrance(index = 3, key = "latency", log = entrance) {
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
                                            placeholder = ConnectivityReference.DEFAULT_URL,
                                            helper = "Wants to be always up and cheap to answer. If your " +
                                                "network blocks it, latency is judged raw and nothing breaks.",
                                            leadingIcon = NightbellIcons.Globe,
                                            accent = NightbellColors.Aqua,
                                            keyboardType = KeyboardType.Uri,
                                            modifier = Modifier.testTag("reference-endpoint"),
                                            corner = NightbellRadii.inCard,
                                        )
                                        Spacer(Modifier.height(8.dp))
                                        ChipSelector(
                                            options = ConnectivityReference.presets.map { it.url },
                                            selected = settings.latencyReferenceUrl,
                                            onSelect = { url ->
                                                viewModel.update { it.copy(latencyReferenceUrl = url) }
                                            },
                                            label = { url ->
                                                ConnectivityReference.presets
                                                    .first { it.url == url }.label
                                            },
                                            accent = NightbellColors.Aqua,
                                        )
                                        Spacer(Modifier.height(8.dp))
                                        Text(
                                            text = "The default is GrapheneOS's connectivity check " +
                                                "rather than Google's. It answers the same empty 204, " +
                                                "and an app that keeps everything on your device had " +
                                                "no business handing an advertising company a timing " +
                                                "signal from your phone. It goes out with your checks " +
                                                "rather than on a clock of its own. Your own " +
                                                "always-up endpoint is better still.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = NightbellColors.TextTertiary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item(key = "proxy") {
                StaggeredEntrance(index = 4, key = "proxy", log = entrance) {
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
                                    corner = NightbellRadii.inCard,
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
                                    corner = NightbellRadii.inCard,
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
                                        "off the device's own DNS. Page-element monitors are " +
                                        "routed as well, and so is the live preview you pick their " +
                                        "elements in. Those loads run one at a time, because the " +
                                        "WebView proxy setting belongs to the whole app rather " +
                                        "than to one page, and a WebView too old to accept one at " +
                                        "all refuses the check instead of loading it in the clear.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = NightbellColors.TextTertiary,
                                )
                            }
                        }
                    }
                }
            }

            item(key = "github") {
                StaggeredEntrance(index = 5, key = "github", log = entrance) {
                    GitHubTokenCard(
                        redactedToken = githubTokenRedacted,
                        onSave = viewModel::setGitHubToken,
                        onOpenTokenPage = { openLink(context, GitHubWatch.TOKEN_PAGE_URL) },
                    )
                }
            }
    }
    val lookItems: LazyListScope.() -> Unit = {
            item(key = "appearance") {
                StaggeredEntrance(index = 0, key = "appearance", log = entrance) {
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
                StaggeredEntrance(index = 1, key = "motion", log = entrance) {
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

            item(key = "favicons") {
                StaggeredEntrance(index = 2, key = "favicons", log = entrance) {
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

            item(key = "widgets") {
                StaggeredEntrance(index = 3, key = "widgets", log = entrance) {
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
    }
    val aboutItems: LazyListScope.() -> Unit = {
            item(key = "updates") {
                StaggeredEntrance(index = 0, key = "updates", log = entrance) {
                    GlassCard {
                        SectionHeader("Nightbell updates", icon = NightbellIcons.Import, accent = NightbellColors.Sky)
                        Text(
                            text = "Nightbell can look for a newer version of itself, notify you once " +
                                "and show a notice on the dashboard until you dismiss it. Nothing is " +
                                "fetched until you tap Install, and Android still asks before it " +
                                "replaces the app.",
                            style = MaterialTheme.typography.bodySmall,
                            color = NightbellColors.TextTertiary,
                        )
                        Spacer(Modifier.height(10.dp))
                        ToggleRow(
                            title = "Tell me about new versions",
                            subtitle = if (settings.updateChecksEnabled) {
                                // Both paths, because the launch one is what makes this
                                // work at all for anyone who turned background checks
                                // off, and the six hours is the cap on both together.
                                "Checked when you open the app and in the background, " +
                                    "at most once every six hours"
                            } else {
                                "Off, your installer handles it"
                            },
                            checked = settings.updateChecksEnabled,
                            onCheckedChange = { v -> viewModel.update { it.copy(updateChecksEnabled = v) } },
                            icon = NightbellIcons.Bell,
                            accent = NightbellColors.Sky,
                        )
                        AnimatedVisibility(
                            visible = settings.updateChecksEnabled,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically(),
                        ) {
                            Column {
                                Spacer(Modifier.height(8.dp))
                                SegmentedSelector(
                                    options = UpdateSource.entries.toList(),
                                    selected = settings.updateSource,
                                    onSelect = { choice ->
                                        viewModel.update {
                                            // Chosen, so the guess never runs again.
                                            it.copy(updateSource = choice, updateSourceChosen = true)
                                        }
                                    },
                                    label = { it.label },
                                    accent = NightbellColors.Sky,
                                    modifier = Modifier.testTag("update-source"),
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = settings.updateSource.blurb,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = NightbellColors.TextTertiary,
                                )
                                Spacer(Modifier.height(12.dp))
                                // Two labelled numbers rather than one sentence. This
                                // card exists to answer "am I behind", and "Newest
                                // seen: 3.2.2" reads as trivia unless you happen to
                                // remember what you are running. A comparison needs
                                // both halves of it on screen.
                                VersionRow("Installed", viewModel.installedVersion)
                                Spacer(Modifier.height(6.dp))
                                VersionRow(
                                    label = "Latest",
                                    value = when {
                                        appUpdate.lastCheckedAt <= 0L -> "not checked yet"
                                        appUpdate.latestVersion.isBlank() ->
                                            "couldn't reach ${settings.updateSource.label}"
                                        else -> appUpdate.latestVersion
                                    },
                                    highlight = AppUpdate.isNewer(
                                        appUpdate.latestVersion,
                                        viewModel.installedVersion,
                                    ),
                                )
                                if (appUpdate.ignoredVersion.isNotBlank()) {
                                    Spacer(Modifier.height(10.dp))
                                    Text(
                                        text = "Not showing ${appUpdate.ignoredVersion}. A later " +
                                            "version will still be announced.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = NightbellColors.TextTertiary,
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    // The undo. Dismissing is now one tap on a dashboard
                                    // banner, so a mis-tap can silence a release
                                    // forever, and the place to recover from it is next
                                    // to the line that admits it happened.
                                    NightbellButton(
                                        text = "Show it again",
                                        onClick = viewModel::unignoreUpdate,
                                        icon = NightbellIcons.Bell,
                                        tone = ButtonTone.Secondary,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("unignore-update"),
                                    )
                                }
                                // The same offer the dashboard notice makes, in
                                // the place someone goes when they came looking for
                                // it rather than when it came looking for them. One
                                // composable behind both, so the two cannot drift.
                                if (AppUpdate.isNewer(appUpdate.latestVersion, viewModel.installedVersion)) {
                                    Spacer(Modifier.height(12.dp))
                                    UpdateActions(
                                        version = appUpdate.latestVersion,
                                        releaseUrl = appUpdate.latestUrl.ifBlank { AppUpdate.DOWNLOAD_URL },
                                        apkUrl = appUpdate.latestApkUrl,
                                        stage = updateInstall.stage,
                                        canRequestInstall = updateInstall.canRequestInstall,
                                        onWhatsNew = { url ->
                                            val intent = Intent(Intent.ACTION_VIEW, url.toUri())
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            runCatching { context.startActivity(intent) }
                                        },
                                        onInstall = {
                                            updateInstall.start(
                                                appUpdate.latestApkUrl,
                                                appUpdate.latestVersion,
                                                appUpdate.latestApkSize,
                                            )
                                        },
                                        onOpenInstallSettings = updateInstall::openSettings,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                                Spacer(Modifier.height(10.dp))
                                NightbellButton(
                                    text = if (viewModel.checkingForUpdate) "Checking…" else "Check now",
                                    onClick = viewModel::checkForUpdateNow,
                                    icon = NightbellIcons.Refresh,
                                    tone = ButtonTone.Secondary,
                                    loading = viewModel.checkingForUpdate,
                                    modifier = Modifier.fillMaxWidth().testTag("check-for-update"),
                                )
                            }
                        }
                    }
                }
            }

            item(key = "backup") {
                StaggeredEntrance(index = 1, key = "backup", log = entrance) {
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
                        ToggleRow(
                            title = "Include the GitHub token",
                            subtitle = if (settings.includeSecretsInExport) {
                                "The file will carry a working credential"
                            } else {
                                "Left out, you'll paste it again on the new phone"
                            },
                            checked = settings.includeSecretsInExport,
                            onCheckedChange = { v ->
                                viewModel.update { it.copy(includeSecretsInExport = v) }
                            },
                            icon = NightbellIcons.Shield,
                            accent = NightbellColors.Rose,
                        )
                        AnimatedVisibility(
                            visible = settings.includeSecretsInExport,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically(),
                        ) {
                            Column {
                                Spacer(Modifier.height(4.dp))
                                WarningPanel(
                                    "Anyone who opens the file can use the token as you until you " +
                                        "revoke it, and that stays true of every copy the file makes " +
                                        "on the way to the other phone. Off is the right answer " +
                                        "unless you are moving the file by hand and deleting it after.",
                                )
                            }
                        }
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
                StaggeredEntrance(index = 2, key = "help", log = entrance) {
                    HelpCard()
                }
            }

            item(key = "about") {
                StaggeredEntrance(index = 3, key = "about", log = entrance) {
                    GlassCard {
                        SectionHeader("About", icon = NightbellIcons.Info, accent = NightbellColors.Sky)
                        AboutRow("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                        AboutRow("Android", "API ${Build.VERSION.SDK_INT} · ${Build.MODEL}")
                        AboutRow("Storage", "Local only, nothing leaves the device")
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

    val tabItems = mapOf(
        SettingsTab.ALERTS to alertsItems,
        SettingsTab.CHECKS to checksItems,
        SettingsTab.LOOK to lookItems,
        SettingsTab.ABOUT to aboutItems,
    )

    val pagerState = rememberPagerState(pageCount = { SettingsTab.entries.size })
    val scope = rememberCoroutineScope()
    val motion = LocalNightbellMotion.current
    // One scroll position per tab. Coming back to a tab you had scrolled down and
    // finding it at the top is the small betrayal that makes tabs feel like four
    // screens instead of one.
    val listStates = SettingsTab.entries.map { rememberLazyListState() }
    val gutter = readableContentPadding()

    Column(Modifier.fillMaxSize()) {
        // Title and the permission warning sit above the tabs, not on one of them.
        // A blocked-notifications banner is true on every tab, and hiding it behind
        // the wrong one would be the single worst place in this app to hide it.
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = gutter.calculateStartPadding(LocalLayoutDirection.current))
                .padding(end = gutter.calculateEndPadding(LocalLayoutDirection.current))
                .padding(top = topInset + 12.dp),
        ) {
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
            Spacer(Modifier.height(14.dp))
        }

        SettingsTabBar(
            selected = pagerState.currentPage,
            // Fractional, so the underline tracks a swiping finger instead of
            // snapping when the page settles.
            position = pagerState.currentPage + pagerState.currentPageOffsetFraction,
            scrolled = listStates[pagerState.currentPage].canScrollBackward,
            onSelect = { page ->
                scope.launch {
                    // "Animations off" means off here too. A tab that glides while
                    // every other transition in the app snaps is the inconsistency
                    // the setting exists to remove.
                    if (motion.enabled) {
                        pagerState.animateScrollToPage(page)
                    } else {
                        pagerState.scrollToPage(page)
                    }
                }
            },
        )

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            // Only the page on screen is composed. Settings holds a heavy set of
            // cards, and pre-composing three of them to save a frame on a swipe is
            // the wrong trade.
            beyondViewportPageCount = 0,
            key = { SettingsTab.entries[it].name },
        ) { page ->
            val tab = SettingsTab.entries[page]
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    // The tag names the list the user is actually looking at, so a
                    // test that scrolls "the settings list" cannot silently drive a
                    // page that is off screen.
                    .then(
                        if (page == pagerState.settledPage) {
                            Modifier.testTag("settings-list")
                        } else {
                            Modifier
                        },
                    ),
                state = listStates[page],
                contentPadding = readableContentPadding(
                    top = 14.dp,
                    bottom = bottomInset + 40.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                tabItems.getValue(tab)(this)
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
/**
 * The four questions Settings answers.
 *
 * Order is not alphabetical and not the order the code was written in: it is how
 * often a setting here gets changed. Alerts first because that is what people
 * come to Settings to adjust; About last because it is the tab you visit once.
 */
private enum class SettingsTab(val label: String, val icon: ImageVector) {
    ALERTS("Alerts", NightbellIcons.Bell),
    CHECKS("Checks", NightbellIcons.Radar),
    LOOK("Look", NightbellIcons.Sparkle),
    ABOUT("About", NightbellIcons.Info),
}

/** Each tab borrows the accent its own cards already use. */
@Composable
private fun tabAccent(tab: SettingsTab): Color = when (tab) {
    SettingsTab.ALERTS -> NightbellColors.Aqua
    SettingsTab.CHECKS -> NightbellColors.Violet
    SettingsTab.LOOK -> NightbellColors.Mint
    SettingsTab.ABOUT -> NightbellColors.Sky
}

/**
 * Navigation between the four tabs.
 *
 * Deliberately *not* a [SegmentedSelector], which this app uses everywhere to
 * pick a value. Reusing that shape for navigation would make "which page am I
 * on" and "which option did I choose" look like the same kind of answer. So this
 * is the other familiar shape: icon over label, with a rule underneath that
 * marks the page.
 *
 * [position] is fractional and comes from the pager, so the rule follows a
 * swiping finger the whole way rather than jumping when the page settles. That
 * one detail is most of what makes a tab bar feel attached to its content.
 */
@Composable
private fun SettingsTabBar(
    selected: Int,
    position: Float,
    scrolled: Boolean,
    onSelect: (Int) -> Unit,
) {
    val tabs = SettingsTab.entries
    val gutter = readableContentPadding()
    val startGutter = gutter.calculateStartPadding(LocalLayoutDirection.current)
    val endGutter = gutter.calculateEndPadding(LocalLayoutDirection.current)

    Column(Modifier.fillMaxWidth()) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .padding(start = startGutter, end = endGutter),
        ) {
            val slot = maxWidth / tabs.size
            val ruleWidth = slot * 0.44f

            Row(Modifier.fillMaxWidth()) {
                tabs.forEachIndexed { index, tab ->
                    val active = index == selected
                    val accent = tabAccent(tab)
                    // The whole slot is the target, not the glyph: a 17 dp icon
                    // with a caption under it is a 24 dp thing to hit otherwise.
                    Column(
                        modifier = Modifier
                            .width(slot)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable(
                                indication = ripple(color = accent),
                                interactionSource = remember { MutableInteractionSource() },
                            ) { onSelect(index) }
                            .padding(vertical = 9.dp)
                            .semantics {
                                contentDescription = "${tab.label} tab"
                                stateDescription = if (active) "Selected" else "Not selected"
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            imageVector = tab.icon,
                            contentDescription = null,
                            tint = if (active) accent else NightbellColors.TextTertiary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.height(5.dp))
                        Text(
                            text = tab.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (active) {
                                NightbellColors.TextPrimary
                            } else {
                                NightbellColors.TextTertiary
                            },
                            maxLines = 1,
                        )
                    }
                }
            }

            // Drawn under the row rather than inside a tab, so it can sit between
            // two of them mid-swipe. Colour lerps as well as position: arriving at
            // a tab whose accent is already the right one is what makes the
            // movement read as one object rather than as a rule that teleports.
            val fraction = position.coerceIn(0f, (tabs.size - 1).toFloat())
            val lower = fraction.toInt().coerceIn(0, tabs.lastIndex)
            val upper = (lower + 1).coerceAtMost(tabs.lastIndex)
            val blend = fraction - lower
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .offset(x = slot * fraction + (slot - ruleWidth) / 2)
                    .width(ruleWidth)
                    .height(2.5.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        lerp(tabAccent(tabs[lower]), tabAccent(tabs[upper]), blend),
                    ),
            )
        }
        // Appears only once there is something above the fold, which is the one
        // moment a reader needs telling that the bar is not the top of the page.
        val hairline by animateFloatAsState(
            targetValue = if (scrolled) 0.14f else 0f,
            label = "tabHairline",
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(NightbellColors.sheen(hairline)),
        )
    }
}

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
            .clip(RoundedCornerShape(NightbellRadii.inCard))
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
    // No fixed height either: 28dp was a line and a half of the default size and
    // exactly one line short at 150 per cent, which clipped the value's descenders.
    LabelledRow(
        labelWidth = 96.dp,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        label = { mod ->
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = NightbellColors.TextTertiary,
                modifier = mod,
            )
        },
        value = { mod ->
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = NightbellColors.TextSecondary,
                modifier = mod,
            )
        },
    )
}

/**
 * The GitHub token, and the case for not bothering.
 *
 * Genuinely optional, and the copy says so first: two or three repositories on a
 * quarter-hour cadence fit inside the anonymous budget with room to spare. The
 * token is for someone watching eight repos, or sharing an address with a office
 * full of other GitHub traffic.
 *
 * The token itself never reaches this composable. It takes a redacted string to
 * display and a callback to save, so there is no path from the view layer to the
 * credential and no later edit to this screen can put it into a screenshot.
 */
@Composable
private fun GitHubTokenCard(
    redactedToken: String,
    onSave: (String) -> Unit,
    onOpenTokenPage: () -> Unit,
) {
    // Null when not editing. A saved token is shown redacted and replaced
    // wholesale rather than edited in place, because there is nothing in a
    // redacted string to edit.
    var typed by rememberSaveable { mutableStateOf<String?>(null) }
    val saved = redactedToken.isNotBlank()

    GlassCard {
        SectionHeader("GitHub", icon = NightbellIcons.Repo, accent = NightbellColors.Sky)
        Text(
            text = "Repository monitors poll GitHub straight from this phone. Without a token " +
                "that is 60 requests an hour for the whole device, and one check spends up to " +
                "three of them, which is plenty for a few repositories on a quarter-hour " +
                "cadence.",
            style = MaterialTheme.typography.bodySmall,
            color = NightbellColors.TextTertiary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "A token raises that to 5,000 an hour and lets an unchanged check use " +
                "GitHub's cache for free. Nightbell stores it only on this device.",
            style = MaterialTheme.typography.bodySmall,
            color = NightbellColors.TextTertiary,
        )
        Spacer(Modifier.height(12.dp))

        if (saved && typed == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(NightbellIcons.Shield, NightbellColors.Mint, size = 34.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Token saved",
                        style = MaterialTheme.typography.titleMedium,
                        color = NightbellColors.TextPrimary,
                    )
                    Text(
                        text = redactedToken,
                        style = MaterialTheme.typography.bodySmall,
                        color = NightbellColors.TextTertiary,
                        modifier = Modifier.testTag("github-token-redacted"),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NightbellButton(
                    text = "Replace",
                    onClick = { typed = "" },
                    icon = NightbellIcons.Pencil,
                    tone = ButtonTone.Secondary,
                    modifier = Modifier.weight(1f),
                )
                NightbellButton(
                    text = "Remove",
                    onClick = { onSave("") },
                    icon = NightbellIcons.Trash,
                    tone = ButtonTone.Danger,
                    modifier = Modifier.weight(1f).testTag("remove-github-token"),
                )
            }
        } else {
            GlassField(
                value = typed.orEmpty(),
                onValueChange = { typed = it },
                label = "Personal access token",
                placeholder = "github_pat_… or ghp_…",
                helper = "Optional. Pasted here it stays here: never in a backup unless you " +
                    "ask, never in a notification, never in a log.",
                leadingIcon = NightbellIcons.Shield,
                accent = NightbellColors.Sky,
                modifier = Modifier.testTag("github-token-field"),
                corner = NightbellRadii.inCard,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NightbellButton(
                    text = "Save token",
                    onClick = {
                        onSave(typed.orEmpty())
                        typed = null
                    },
                    enabled = !typed.isNullOrBlank(),
                    icon = NightbellIcons.Check,
                    accent = NightbellColors.Sky,
                    modifier = Modifier.weight(1f).testTag("save-github-token"),
                )
                if (saved) {
                    NightbellButton(
                        text = "Cancel",
                        onClick = { typed = null },
                        tone = ButtonTone.Secondary,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }
        }

        GlassDivider(Modifier.padding(vertical = 12.dp))
        Text(
            text = "Making one",
            style = MaterialTheme.typography.labelMedium,
            color = NightbellColors.TextSecondary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Choose a fine-grained token. Under Repository access pick " +
                "\"Public repositories\", or \"Only select repositories\" and choose the " +
                "ones you watch.",
            style = MaterialTheme.typography.bodySmall,
            color = NightbellColors.TextTertiary,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "PERMISSIONS TO TICK",
            style = MaterialTheme.typography.labelSmall,
            color = NightbellColors.TextTertiary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "For a public repository, none. \"Metadata: Read-only\" is ticked for you " +
                "and that is the whole list: a public repo answers Nightbell without any " +
                "token at all, so yours is doing nothing but raising the limit.",
            style = MaterialTheme.typography.bodySmall,
            color = NightbellColors.TextSecondary,
            modifier = Modifier.testTag("token-scopes-public"),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "For a private repository, set these to Read-only:",
            style = MaterialTheme.typography.bodySmall,
            color = NightbellColors.TextTertiary,
        )
        Spacer(Modifier.height(8.dp))
        TokenScopeRow("Contents", "the latest release")
        TokenScopeRow("Issues", "new issues")
        TokenScopeRow("Pull requests", "only if you watch pull requests")
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Nothing else on that list, and never write access to anything. If you " +
                "are unsure, tick less: a token that cannot read something makes one check " +
                "fail loudly rather than doing any harm.",
            style = MaterialTheme.typography.bodySmall,
            color = NightbellColors.TextTertiary,
        )
        Spacer(Modifier.height(12.dp))
        NightbellButton(
            text = "Create a GitHub token",
            onClick = onOpenTokenPage,
            icon = NightbellIcons.Export,
            tone = ButtonTone.Secondary,
            modifier = Modifier.fillMaxWidth().testTag("create-github-token"),
        )
    }
}

/**
 * One checkbox on GitHub's permissions list, and why Nightbell wants it.
 *
 * Named exactly as GitHub labels it, because the value of this block is being
 * able to read down it with the token page open beside you. "Least privilege"
 * is advice; "Contents, Issues, Pull requests" is an instruction.
 */
@Composable
private fun TokenScopeRow(permission: String, purpose: String) {
    LabelledRow(
        labelWidth = 112.dp,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        label = { mod ->
            Text(
                text = permission,
                style = MaterialTheme.typography.bodySmall,
                color = NightbellColors.Sky,
                modifier = mod,
            )
        },
        value = { mod ->
            Text(
                text = purpose,
                style = MaterialTheme.typography.bodySmall,
                color = NightbellColors.TextTertiary,
                modifier = mod,
            )
        },
    )
}

/** Hands a URL to whatever the user browses with. */
private fun openLink(context: android.content.Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

/**
 * One labelled version number, for the update card's Installed and Latest lines.
 *
 * [highlight] tints the value when it is a version the user does not have yet,
 * which is the only state on this card worth drawing the eye to.
 */
@Composable
private fun VersionRow(label: String, value: String, highlight: Boolean = false) {
    LabelledRow(
        labelWidth = 88.dp,
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        label = { mod ->
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = NightbellColors.TextTertiary,
                modifier = mod,
            )
        },
        value = { mod ->
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = if (highlight) NightbellColors.Sky else NightbellColors.TextPrimary,
                modifier = mod,
            )
        },
    )
}
