package me.river.pulse.data

import android.content.Context
import me.river.pulse.data.alerts.AlertCenter
import me.river.pulse.data.check.CheckEngine
import me.river.pulse.data.check.ElementChecker
import me.river.pulse.data.check.HttpChecker
import me.river.pulse.data.check.LatencyReference
import me.river.pulse.data.health.SystemLimits
import me.river.pulse.data.icons.FaviconStore
import me.river.pulse.data.net.NetworkMonitor
import me.river.pulse.data.work.MonitorScheduler
import me.river.pulse.data.work.PulseMonitorService
import me.river.pulse.domain.runCatchingCancellable
import me.river.pulse.widget.PulseWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Hand-rolled service locator. A DI framework would be ceremony for a graph this
 * small, and workers/receivers can bootstrap it from any [Context].
 */
object Pulse {

    @Volatile
    private var graph: Graph? = null

    class Graph(private val context: Context) {
        val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = PulseStore(context, appScope)
        val alerts = AlertCenter(context)
        val http = HttpChecker()
        val element = ElementChecker(context)
        val reference = LatencyReference()
        val engine = CheckEngine(store, http, element, alerts, reference)
        val scheduler = MonitorScheduler(context)
        val network = NetworkMonitor(context)
        val favicons = FaviconStore(context, isOnline = network::isOnline)
        val limits = SystemLimits(context, isOnline = network::isOnline)

        /**
         * Pushes current state to the two surfaces that live outside the
         * activity: placed widgets, and the strict/urgent foreground service
         * (which may need to start or stop as a result).
         */
        fun notifyStateChanged() {
            PulseWidgetProvider.refresh(context)
            PulseMonitorService.sync(context)
        }

        init {
            // Wired here rather than inside CheckEngine so the engine itself
            // stays free of Android plumbing and remains unit-testable.
            engine.onStateChanged = ::notifyStateChanged
            engine.isOnline = network::isOnline
            // Lets the engine skip its fallback post when the service is already
            // showing the (fully red, looping) page for that outage.
            engine.serviceIsPaging = PulseMonitorService::isPaging

            // Coming back online should feel immediate rather than "sometime
            // within the next interval" — the phone was in a tunnel, and the
            // first thing you do on the other side is look at the app.
            network.onReconnected = {
                appScope.launch {
                    runCatchingCancellable { engine.runAllDue() }
                    notifyStateChanged()
                }
            }
            network.start()
        }
    }

    fun install(context: Context): Graph = graph ?: synchronized(this) {
        graph ?: Graph(context.applicationContext).also { graph = it }
    }

    fun require(): Graph = graph ?: error("Pulse.install() has not been called yet")

    fun from(context: Context): Graph = install(context)

    val store: PulseStore get() = require().store
    val engine: CheckEngine get() = require().engine
    val alerts: AlertCenter get() = require().alerts
    val scheduler: MonitorScheduler get() = require().scheduler
    val network: NetworkMonitor get() = require().network
    val favicons: FaviconStore get() = require().favicons
    val scope: CoroutineScope get() = require().appScope
}
