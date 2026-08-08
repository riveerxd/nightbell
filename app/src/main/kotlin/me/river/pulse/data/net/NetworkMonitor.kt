package me.river.pulse.data.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether this device can reach anything at all.
 *
 * Exists because a check run with no connectivity fails for a reason that has
 * nothing to do with the thing being monitored, and every monitor fails at once
 * — which is a burst of "your site is down" notifications that are all wrong.
 * Losing signal is not an outage, so Nightbell stops checking instead of reporting
 * one.
 *
 * [isOnline] is the authority for decisions: it asks the framework at the moment
 * of the call, so a check can never run against a stale cached answer. [online]
 * is a callback-driven mirror for the UI, which needs to re-compose the instant
 * connectivity changes rather than at the next check.
 */
class NetworkMonitor(context: Context) {

    private val manager = runCatching {
        context.getSystemService(ConnectivityManager::class.java)
    }.getOrNull()

    private val _online = MutableStateFlow(true)
    val online: StateFlow<Boolean> = _online.asStateFlow()

    /**
     * Fired when connectivity comes back, so monitoring resumes immediately
     * instead of waiting out whatever remains of the current interval.
     */
    var onReconnected: (() -> Unit)? = null

    /**
     * `true` when there is an active network that claims internet access and has
     * not been flagged as a captive portal.
     *
     * **`NET_CAPABILITY_VALIDATED` is deliberately not required**, though it is
     * the obvious choice and was the first attempt. Validation is set only after
     * the framework's own probe to a Google endpoint succeeds, so it is absent in
     * three very different situations: no connectivity, a captive portal, and *a
     * perfectly good network whose probe cannot get out* — a firewalled office
     * LAN, a DNS-filtered network, a pi-hole, or (as caught here) an emulator
     * whose probes fail. Requiring it means Nightbell quietly stops monitoring on
     * networks where it works fine, which is a far worse bug than the one this
     * class fixes: spam is visible and annoying, silence is invisible and lets a
     * real outage pass unnoticed. `CAPTIVE_PORTAL` still catches the hotel-wifi
     * case, because that one Android tells us about explicitly.
     *
     * The residual gap: a network that advertises internet and blackholes
     * traffic without being detected as a portal will still produce failed
     * checks. That is rarer than losing signal, and it fails in the direction
     * that is at least honest about what it observed.
     *
     * **Fails open.** Every unexpected path returns `true`, for the same reason.
     */
    fun isOnline(): Boolean {
        val cm = manager ?: return true
        return try {
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
        } catch (error: Throwable) {
            Log.w(TAG, "Connectivity lookup failed; assuming online", error)
            true
        }
    }

    /** Seeds [online] and starts watching. Safe to call more than once. */
    fun start() {
        _online.value = isOnline()
        val cm = manager ?: return
        if (callback != null) return
        val watcher = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = refresh()
            override fun onLost(network: Network) = refresh()

            // Catches a network that was already "available" changing its mind —
            // notably the captive-portal flag clearing once you sign in.
            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) = refresh()
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { cm.registerNetworkCallback(request, watcher) }
            .onSuccess { callback = watcher }
            .onFailure { Log.w(TAG, "Could not watch connectivity", it) }
    }

    private var callback: ConnectivityManager.NetworkCallback? = null

    private fun refresh() {
        val now = isOnline()
        val was = _online.value
        _online.value = now
        // Worth a line in the log: "why did Nightbell stop checking" is otherwise
        // only answerable by guessing, and this is the answer.
        if (now != was) Log.i(TAG, "Connectivity changed: online=$now")
        if (now && !was) onReconnected?.invoke()
    }

    private companion object {
        const val TAG = "NetworkMonitor"
    }
}
