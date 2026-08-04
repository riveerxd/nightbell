package me.river.pulse.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import me.river.pulse.data.Pulse
import me.river.pulse.ui.components.AuroraBackground
import me.river.pulse.ui.dashboard.DashboardScreen
import me.river.pulse.ui.detail.DetailScreen
import me.river.pulse.ui.settings.SettingsScreen
import me.river.pulse.ui.setup.SetupScreen
import me.river.pulse.ui.theme.PulseColors
import me.river.pulse.ui.theme.PulseTheme
import me.river.pulse.ui.theme.softShadow
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import me.river.pulse.domain.PagerReadiness
import me.river.pulse.ui.permissions.PagerSetupScreen
import kotlinx.coroutines.delay

/** One reading of what the platform currently allows. */
private fun pagerReadinessNow(graph: Pulse.Graph): PagerReadiness.State {
    val settings = graph.store.snapshot.value.settings
    return PagerReadiness.State(
        notifications = graph.alerts.hasNotificationPermission(),
        batteryExempt = graph.limits.isIgnoringBatteryOptimizations(),
        fullScreen = graph.alerts.canUseFullScreenIntent(),
        dndBypass = graph.alerts.urgentBypassesDnd(),
        audible = graph.alarm.alarmStreamAudible(settings.urgentRespectsRingerMode),
    )
}

object Routes {
    const val PAGER_SETUP = "pager-setup"
    const val DASHBOARD = "dashboard"
    const val SETTINGS = "settings"
    const val SETUP_NEW = "setup"
    const val SETUP_EDIT = "setup/{monitorId}"
    const val DETAIL = "detail/{monitorId}"

    fun setupEdit(id: String) = "setup/$id"
    fun detail(id: String) = "detail/$id"
}

@Composable
fun PulseApp(initialMonitorId: String? = null) {
    val graph = Pulse.require()
    val settings by graph.store.snapshot.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    var toastMessage by remember { mutableStateOf<String?>(null) }

    // Decided once, from the first snapshot that has loaded: making this reactive
    // would yank the user back to the gate the moment a grant was revoked, and
    // re-deciding it after `hasSeenPagerSetup` flips would fight the navigation
    // that flipped it.
    val startDestination = remember {
        val settings = graph.store.snapshot.value.settings
        val state = pagerReadinessNow(graph)
        if (PagerReadiness.shouldGate(state, dismissed = settings.hasSeenPagerSetup)) {
            Routes.PAGER_SETUP
        } else {
            Routes.DASHBOARD
        }
    }

    LaunchedEffect(initialMonitorId) {
        if (!initialMonitorId.isNullOrBlank()) {
            navController.navigate(Routes.detail(initialMonitorId))
        }
    }

    PulseTheme(motionIntensity = settings.settings.motionIntensity) {
        AuroraBackground(
            modifier = Modifier.fillMaxSize(),
            intensity = (0.35f + settings.settings.motionIntensity * 0.65f).coerceIn(0.35f, 1.2f),
        ) {
            PulseNavHost(
                navController = navController,
                startDestination = startDestination,
                onToast = { toastMessage = it },
            )
            GlassToast(
                message = toastMessage,
                onDismissed = { toastMessage = null },
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}

@Composable
private fun PulseNavHost(
    navController: NavHostController,
    startDestination: String,
    onToast: (String) -> Unit,
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = Modifier.fillMaxSize(),
        enterTransition = {
            slideInHorizontally(tween(320)) { it / 5 } + fadeIn(tween(240)) + scaleIn(tween(320), 0.96f)
        },
        exitTransition = {
            slideOutHorizontally(tween(280)) { -it / 8 } + fadeOut(tween(180))
        },
        popEnterTransition = {
            slideInHorizontally(tween(320)) { -it / 6 } + fadeIn(tween(240))
        },
        popExitTransition = {
            slideOutHorizontally(tween(280)) { it / 5 } + fadeOut(tween(200)) + scaleOut(tween(280), 0.96f)
        },
    ) {
        composable(Routes.PAGER_SETUP) {
            val scope = rememberCoroutineScope()
            val graph = Pulse.require()
            PagerSetupScreen(
                onDone = {
                    // Recorded before navigating, so a process death between the
                    // two cannot make the gate reappear over and over.
                    scope.launch {
                        graph.store.updateSettings { it.copy(hasSeenPagerSetup = true) }
                    }
                    navController.navigate(Routes.DASHBOARD) {
                        popUpTo(Routes.PAGER_SETUP) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.DASHBOARD) {
            DashboardScreen(
                onAddMonitor = { navController.navigate(Routes.SETUP_NEW) },
                onOpenMonitor = { navController.navigate(Routes.detail(it)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onToast = onToast,
            )
        }

        composable(
            Routes.SETUP_NEW,
            enterTransition = { slideInVertically(tween(340)) { it / 3 } + fadeIn(tween(240)) },
            popExitTransition = { slideOutVertically(tween(280)) { it / 3 } + fadeOut(tween(200)) },
        ) {
            SetupScreen(
                monitorId = null,
                onClose = { navController.popBackStack() },
                onSaved = {
                    onToast("Monitor created")
                    navController.popBackStack()
                },
            )
        }

        composable(Routes.SETUP_EDIT) { entry ->
            val id = entry.arguments?.getString("monitorId")
            SetupScreen(
                monitorId = id,
                onClose = { navController.popBackStack() },
                onSaved = {
                    onToast("Changes saved")
                    navController.popBackStack()
                },
            )
        }

        composable(Routes.DETAIL) { entry ->
            val id = entry.arguments?.getString("monitorId").orEmpty()
            DetailScreen(
                monitorId = id,
                onBack = { navController.popBackStack() },
                onEdit = { navController.navigate(Routes.setupEdit(it)) },
                onToast = onToast,
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onToast = onToast,
            )
        }
    }
}

/**
 * Confirmation capsule. Slides down from the top and fades itself out.
 *
 * Sized to its text rather than to the screen, and parked over the wordmark
 * row: a transient "saved" message has no business covering the fleet banner's
 * verdict, which is the one line on the screen someone opened Pulse to read.
 */
@Composable
private fun GlassToast(
    message: String?,
    onDismissed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(message) {
        if (message != null) {
            delay(2_400)
            onDismissed()
        }
    }
    val topInset = WindowInsets.systemBars.asPaddingValues().calculateTopPadding()
    val shape = RoundedCornerShape(100)
    AnimatedVisibility(
        visible = message != null,
        enter = slideInVertically(tween(280)) { -it } + fadeIn(tween(200)),
        exit = slideOutVertically(tween(220)) { -it } + fadeOut(tween(180)),
        modifier = modifier
            .zIndex(Float.MAX_VALUE)
            .padding(top = topInset + TOAST_TOP_GAP, start = 20.dp, end = 20.dp),
    ) {
        Row(
            Modifier
                // Deliberately heavier than a card's shadow. The capsule floats
                // over lighter glass surfaces, and without a real pool of dark
                // underneath it reads as part of whatever it happens to cover.
                .softShadow(corner = 100.dp, radius = 26.dp, strength = 2.4f)
                .clip(shape)
                .background(PulseColors.ToastFill)
                .border(1.dp, Color.White.copy(alpha = 0.14f), shape)
                .padding(start = 15.dp, end = 20.dp, top = 11.dp, bottom = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(PulseColors.Mint),
            )
            Spacer(Modifier.width(11.dp))
            Text(
                text = message.orEmpty(),
                style = MaterialTheme.typography.titleMedium,
                color = PulseColors.TextPrimary,
            )
        }
    }
}

/**
 * Parks the capsule over the wordmark row and nothing else.
 *
 * The fleet verdict moved out of a subtitle and into the banner below, so the
 * cheap row to cover is now the app's own name — and the banner, which is the
 * line someone opened Pulse to read, stays clear.
 */
private val TOAST_TOP_GAP = 10.dp
