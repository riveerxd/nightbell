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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.shadow
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
import me.river.pulse.ui.icons.PulseIcons
import me.river.pulse.ui.settings.SettingsScreen
import me.river.pulse.ui.setup.SetupScreen
import me.river.pulse.ui.theme.PulseColors
import me.river.pulse.ui.theme.PulseTheme
import me.river.pulse.ui.theme.glass
import kotlinx.coroutines.delay

object Routes {
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
private fun PulseNavHost(navController: NavHostController, onToast: (String) -> Unit) {
    NavHost(
        navController = navController,
        startDestination = Routes.DASHBOARD,
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

/** Glass toast that slides in from the top and fades itself out. */
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
    AnimatedVisibility(
        visible = message != null,
        enter = slideInVertically(tween(280)) { -it } + fadeIn(tween(200)),
        exit = slideOutVertically(tween(220)) { -it } + fadeOut(tween(180)),
        modifier = modifier
            .zIndex(Float.MAX_VALUE)
            .padding(top = topInset + 10.dp, start = 20.dp, end = 20.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .shadow(18.dp, RoundedCornerShape(18.dp), clip = false)
                .glass(
                    RoundedCornerShape(18.dp),
                    corner = 18.dp,
                    fill = PulseColors.GlassFillStrong,
                    fillEnd = PulseColors.Ink,
                    strokeTop = PulseColors.Aqua,
                    strokeBottom = PulseColors.GlassStroke,
                    elevation = 24.dp,
                    glow = PulseColors.Aqua,
                    specular = false,
                )
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            Icon(
                imageVector = PulseIcons.Sparkle,
                contentDescription = null,
                tint = PulseColors.Aqua,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(11.dp))
            Text(
                text = message.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = PulseColors.TextPrimary,
            )
        }
    }
}
