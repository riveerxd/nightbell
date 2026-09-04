package me.river.nightbell.data.diag

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.webkit.WebView
import me.river.nightbell.BuildConfig
import me.river.nightbell.data.Nightbell
import me.river.nightbell.data.work.NightbellMonitorService
import me.river.nightbell.domain.DiagnosticHeader
import me.river.nightbell.domain.FleetFacts
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Gathers the block at the top of an exported log.
 *
 * Separate from [Diag] because it is the one part of the log that reads the
 * whole app: the store, the permission grants, the network, the service. Keeping
 * it out of the sink means the sink can stay something a worker calls a hundred
 * times a minute without touching any of that.
 *
 * Every lookup is wrapped. A header that is missing a field is worth having; an
 * export that throws while collecting one is not, and several of these throw on
 * some OEM builds.
 */
object DiagnosticFacts {

    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US)

    fun header(context: Context, nowMs: Long = System.currentTimeMillis()): DiagnosticHeader {
        val graph = Nightbell.install(context)
        val snapshot = graph.store.snapshot.value
        val facts = FleetFacts.of(snapshot.monitors)
        val webView = runCatching { WebView.getCurrentWebViewPackage() }.getOrNull()
        return DiagnosticHeader(
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            buildType = BuildConfig.BUILD_TYPE,
            // R8 has broken this app before in ways no JVM test could see, so
            // whether the build that produced a log was minified is a fact worth
            // reading before anything else in it.
            minified = BuildConfig.BUILD_TYPE != "debug",
            applicationId = BuildConfig.APPLICATION_ID,
            sdkInt = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            webViewPackage = webView?.packageName ?: "unknown",
            webViewVersion = webView?.versionName ?: "unknown",
            batteryOptimised = runCatching { !graph.limits.isIgnoringBatteryOptimizations() }
                .getOrDefault(false),
            notificationsAllowed = runCatching { graph.alerts.hasNotificationPermission() }
                .getOrDefault(false),
            exactAlarmsAllowed = exactAlarms(context),
            fullScreenIntentAllowed = runCatching { graph.alerts.canUseFullScreenIntent() }
                .getOrDefault(false),
            online = runCatching { graph.network.isOnline() }.getOrDefault(true),
            metered = metered(context),
            servicePaging = runCatching { NightbellMonitorService.isPaging() }.getOrDefault(false),
            monitorCount = facts.total,
            enabledCount = facts.enabled,
            urgentCount = facts.urgent,
            pageMonitorCount = facts.page,
            loggingSince = if (Diag.capturing) {
                "on since ${stamp.format(Date(Diag.since))}"
            } else {
                "off"
            },
            capturedAt = stamp.format(Date(nowMs)),
        )
    }

    private fun exactAlarms(context: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return@runCatching true
        val manager = context.getSystemService(android.app.AlarmManager::class.java)
        manager?.canScheduleExactAlarms() ?: false
    }.getOrDefault(false)

    /**
     * Whether the active network is metered.
     *
     * Worth a line because "only on unmetered" is a setting a user can leave on
     * and then forget, and the resulting silence looks exactly like a broken
     * scheduler.
     */
    private fun metered(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        manager?.isActiveNetworkMetered ?: false
    }.getOrDefault(false)
}
