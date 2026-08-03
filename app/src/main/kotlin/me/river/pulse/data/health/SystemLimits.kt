package me.river.pulse.data.health

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import me.river.pulse.data.PulseSnapshot
import me.river.pulse.domain.CheckerFacts
import me.river.pulse.domain.CheckerLimit
import me.river.pulse.domain.CheckerLimits
import me.river.pulse.domain.MonitorCadence

/**
 * Reads the platform for the reasons background checks may be running late, and
 * hands them to [CheckerLimits] as plain data.
 *
 * This is the third state the app was missing. Before 1.6.0 there were only two
 * verdicts available — "the monitor is fine" and "the monitor is down" — so
 * anything that stopped a check from producing an answer had to be squeezed into
 * one of them, and it was squeezed into *down*. Doze deferring work, a service
 * being stopped, battery saver, a cancelled coroutine: all of it came out as an
 * outage notification.
 *
 * Everything here is queried at the moment of the call and nothing is cached: the
 * answers change while the app is open, and a stale answer displayed as current
 * is the failure mode this whole release is about. Every lookup fails open —
 * "not limited" — because claiming a restriction that isn't there would send the
 * user to fix a setting that is already correct.
 */
class SystemLimits(
    private val context: Context,
    private val isOnline: () -> Boolean,
) {

    fun facts(snapshot: PulseSnapshot, nowMs: Long = System.currentTimeMillis()): CheckerFacts {
        val enabled = snapshot.monitors.filter { it.enabled }
        // One entry per monitor, each carrying its *own* interval. Aggregating this
        // into "the oldest age" and "the tightest interval" compared two different
        // monitors against each other, so a healthy 15-minute-plus-2-hour fleet was
        // permanently diagnosed as delayed by Android.
        val cadences = enabled.mapNotNull { monitor ->
            // Monitors never checked are omitted: brand new is not late. A resumed
            // monitor also lands here, because `setEnabled` clears `lastCheckedAt`.
            val last = snapshot.runtimes[monitor.id]?.lastCheckedAt?.takeIf { it > 0L }
                ?: return@mapNotNull null
            MonitorCadence(
                intervalMinutes = monitor.intervalMinutes,
                ageMs = (nowMs - last).coerceAtLeast(0L),
            )
        }
        return CheckerFacts(
            backgroundChecksEnabled = snapshot.settings.backgroundChecksEnabled,
            enabledMonitors = enabled.size,
            online = isOnline(),
            unmeteredOnly = snapshot.settings.onlyOnUnmeteredNetwork,
            onUnmeteredNetwork = isOnUnmetered(),
            backgroundRestricted = isBackgroundRestricted(),
            powerSaveMode = isPowerSaveMode(),
            ignoringBatteryOptimizations = isIgnoringBatteryOptimizations(),
            strictMode = snapshot.settings.strictForegroundMonitoring,
            cadences = cadences,
        )
    }

    fun diagnose(snapshot: PulseSnapshot, nowMs: Long = System.currentTimeMillis()): CheckerLimit =
        CheckerLimits.diagnose(facts(snapshot, nowMs))

    /**
     * Whether Android has been told to stop deferring Pulse's work.
     *
     * Queried, never requested: asking for the exemption needs
     * `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, which is a policy-sensitive
     * permission Pulse does not need — [batterySettingsIntent] opens the list and
     * lets the user decide, which is also the honest way round.
     */
    fun isIgnoringBatteryOptimizations(): Boolean = runCatching {
        context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) ?: true
    }.getOrDefault(true)

    fun isPowerSaveMode(): Boolean = runCatching {
        context.getSystemService(PowerManager::class.java)?.isPowerSaveMode ?: false
    }.getOrDefault(false)

    /** The hard one: Android will not run this app in the background at all. */
    fun isBackgroundRestricted(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            false
        } else {
            context.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted ?: false
        }
    }.getOrDefault(false)

    private fun isOnUnmetered(): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return@runCatching true
        val network = cm.activeNetwork ?: return@runCatching true
        val caps = cm.getNetworkCapabilities(network) ?: return@runCatching true
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }.getOrDefault(true)

    /**
     * The per-app battery screen, which is where the exemption actually lives.
     *
     * `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` (the system-wide list) is the
     * documented no-permission route, with the app's own details screen as the
     * fallback for OEM builds that do not answer it.
     */
    fun batterySettingsIntent(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun appDetailsIntent(): Intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(android.net.Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
