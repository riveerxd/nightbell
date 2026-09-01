package me.river.nightbell.ui

import android.app.Activity
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import me.river.nightbell.data.Nightbell
import me.river.nightbell.ui.components.AuroraBackground
import me.river.nightbell.ui.components.DismissKeyboardOnOutsideTap
import me.river.nightbell.ui.components.ToastHost
import me.river.nightbell.ui.components.ToastMessage
import me.river.nightbell.ui.dashboard.DashboardScreen
import me.river.nightbell.ui.detail.DetailScreen
import me.river.nightbell.ui.settings.SettingsScreen
import me.river.nightbell.ui.setup.SetupScreen
import me.river.nightbell.ui.theme.LocalNowMs
import me.river.nightbell.ui.theme.NightbellColors
import me.river.nightbell.ui.theme.NightbellTheme
import me.river.nightbell.ui.theme.rememberNowMs
import me.river.nightbell.ui.theme.rememberSystemAnimationsEnabled
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import me.river.nightbell.domain.PagerReadiness
import me.river.nightbell.ui.permissions.PagerSetupScreen

/** One reading of what the platform currently allows. */
private fun pagerReadinessNow(graph: Nightbell.Graph): PagerReadiness.State {
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
    /**
     * New monitor, optionally seeded from a template.
     *
     * The template travels as a query parameter rather than a path segment so the
     * plain "setup" route keeps working untouched — a deep link or a saved back
     * stack entry from an earlier build still resolves.
     */
    const val SETUP_NEW = "setup?template={template}"
    const val SETUP_EDIT = "setup/{monitorId}"
    const val DETAIL = "detail/{monitorId}"

    fun setupNew(templateId: String? = null) =
        if (templateId == null) "setup?template=" else "setup?template=$templateId"

    fun setupEdit(id: String) = "setup/$id"
    fun detail(id: String) = "detail/$id"
}

/**
 * Puts a monitor that a page or a widget row asked for on the screen.
 *
 * The dashboard has to end up directly behind it, and nothing else may. Until
 * this existed the call was a bare `navigate`, so a second page arriving while
 * the first monitor was still open stacked one detail screen on another, and the
 * back arrow then walked through the night's outages one at a time instead of
 * going home. Two monitors down at once is the normal case for anyone who owns
 * this app, so that was most of the time.
 *
 * The [Routes.DASHBOARD] insertion covers the other way in: a cold start from a
 * page can land on the pager gate, and a monitor opened on top of the gate has
 * nowhere to go back to at all.
 */
private fun openPagedMonitor(navController: NavHostController, monitorId: String) {
    // `getBackStackEntry` and not `currentBackStack`: the latter reads the list
    // directly and is marked RestrictedApi, which lint fails the release build on.
    // Asking for the entry and catching the miss is the supported question.
    val dashboardIsBehind = runCatching { navController.getBackStackEntry(Routes.DASHBOARD) }
        .isSuccess
    if (!dashboardIsBehind) {
        navController.navigate(Routes.DASHBOARD) {
            popUpTo(navController.graph.findStartDestination().id) { inclusive = true }
        }
    }
    navController.navigate(Routes.detail(monitorId)) {
        popUpTo(Routes.DASHBOARD)
        launchSingleTop = true
    }
}

/**
 * @param pagedMonitorId the monitor a notification or widget row is asking for,
 *   or null when the app was opened normally.
 * @param onPagedMonitorOpened clears that request. It has to be a one-shot: the
 *   same monitor paging twice is the same value arriving twice, and an effect
 *   keyed on an unchanged value never runs a second time, so the second tap on
 *   the shade used to do nothing at all.
 */
@Composable
fun NightbellApp(
    pagedMonitorId: String? = null,
    onPagedMonitorOpened: () -> Unit = {},
) {
    val graph = Nightbell.require()
    val settings by graph.store.snapshot.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    var toastMessage by remember { mutableStateOf<ToastMessage?>(null) }

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

    LaunchedEffect(pagedMonitorId) {
        val id = pagedMonitorId?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        // Navigate first, clear second. Clearing flips the key to null and
        // cancels this coroutine, and neither call suspends, so doing them in
        // this order is what makes the cancellation land after the work.
        openPagedMonitor(navController, id)
        onPagedMonitorOpened()
    }

    val nowMs by rememberNowMs()

    // The system toggle is a veto, not another input to average in: "Remove
    // animations" is off-means-off, and the in-app slider only gets to choose how
    // much motion there is when the platform has not already said none.
    val systemAnimations = rememberSystemAnimationsEnabled()
    val motionIntensity = if (systemAnimations) settings.settings.motionIntensity else 0f

    NightbellTheme(motionIntensity = motionIntensity, theme = settings.settings.theme) {
        // The bars are transparent and the content runs under them, so their icons
        // have to be told which way to contrast. Without this the clock and the
        // back gesture hint stay white and vanish against the light scheme.
        SyncSystemBarIcons(light = !NightbellColors.isDark)
        CompositionLocalProvider(LocalNowMs provides nowMs) {
            AuroraBackground(
                modifier = Modifier.fillMaxSize(),
                intensity = (0.35f + motionIntensity * 0.65f).coerceIn(0.35f, 1.2f),
            ) {
                DismissKeyboardOnOutsideTap(Modifier.fillMaxSize()) {
                    NightbellNavHost(
                        navController = navController,
                        startDestination = startDestination,
                        onToast = { toastMessage = it },
                    )
                }
                ToastHost(
                    message = toastMessage,
                    onDismissed = { toastMessage = null },
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        }
    }
}

@Composable
private fun NightbellNavHost(
    navController: NavHostController,
    startDestination: String,
    onToast: (ToastMessage) -> Unit,
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
            val graph = Nightbell.require()
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
                onAddMonitor = { navController.navigate(Routes.setupNew()) },
                onPickTemplate = { navController.navigate(Routes.setupNew(it)) },
                onOpenMonitor = { navController.navigate(Routes.detail(it)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onToast = onToast,
            )
        }

        composable(
            Routes.SETUP_NEW,
            arguments = listOf(
                navArgument("template") { defaultValue = ""; nullable = false },
            ),
            enterTransition = { slideInVertically(tween(340)) { it / 3 } + fadeIn(tween(240)) },
            popExitTransition = { slideOutVertically(tween(280)) { it / 3 } + fadeOut(tween(200)) },
        ) { entry ->
            SetupScreen(
                monitorId = null,
                templateId = entry.arguments?.getString("template").orEmpty().ifBlank { null },
                onClose = { navController.popBackStack() },
                onToast = onToast,
                onSaved = {
                    onToast(ToastMessage.success("Monitor created"))
                    navController.popBackStack()
                },
            )
        }

        composable(Routes.SETUP_EDIT) { entry ->
            val id = entry.arguments?.getString("monitorId")
            SetupScreen(
                monitorId = id,
                onClose = { navController.popBackStack() },
                onToast = onToast,
                onSaved = {
                    onToast(ToastMessage.success("Changes saved"))
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
 * Points the status- and navigation-bar icons at the active scheme.
 *
 * Edge-to-edge means the bars are transparent and app content passes beneath
 * them, so nothing but this decides whether the clock is legible. A no-op when
 * the composable is previewed outside an Activity.
 */
@Composable
private fun SyncSystemBarIcons(light: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    val window = (view.context as? Activity)?.window ?: return
    LaunchedEffect(light, window) {
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }
}
