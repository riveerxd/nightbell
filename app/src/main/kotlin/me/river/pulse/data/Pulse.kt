package me.river.pulse.data

import android.content.Context
import me.river.pulse.data.alerts.AlertCenter
import me.river.pulse.data.alerts.UrgentAlarm
import me.river.pulse.data.check.CheckEngine
import me.river.pulse.data.check.ElementChecker
import me.river.pulse.data.check.HttpChecker
import me.river.pulse.data.check.LatencyReference
import me.river.pulse.data.health.SystemLimits
import me.river.pulse.data.icons.FaviconStore
import me.river.pulse.data.net.NetworkMonitor
import me.river.pulse.data.work.MonitorScheduler
import me.river.pulse.data.work.PulseMonitorService
import me.river.pulse.domain.Summary
import me.river.pulse.domain.runCatchingCancellable
import me.river.pulse.widget.PulseWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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

        /**
         * Shared so the pager-setup screen can report what the ringer currently
         * allows without constructing its own player. Only
         * [me.river.pulse.data.work.PulseMonitorService] ever calls `start`.
         */
        val alarm = UrgentAlarm(context)
        val http = HttpChecker()
        val element = ElementChecker(context)
        val reference = LatencyReference()
        val engine = CheckEngine(store, http, element, alerts, reference)
        val scheduler = MonitorScheduler(context)
        val network = NetworkMonitor(context)
        val favicons = FaviconStore(context, isOnline = network::isOnline)
        val limits = SystemLimits(context, isOnline = network::isOnline)

        /**
         * Pushes current state to the strict/urgent foreground service, which may
         * need to start or stop as a result.
         *
         * Placed widgets are deliberately **not** refreshed from here any more.
         * They follow [PulseStore.snapshot] instead — see the collector in `init`.
         * Being pushed meant a refresh happened once per *check*, which missed
         * every change that was only a store write: saving a paused monitor never
         * calls into the engine, so a rename could sit stale on the home screen
         * indefinitely.
         */
        fun notifyStateChanged() {
            // Silence first, and directly. The loop is what normally stops the
            // alarm, and it only checks between sleeps — so acknowledging left the
            // phone vibrating for up to a minute. This is best-effort: the snapshot
            // flow can still be a beat behind the write that just happened, which
            // is why the loop is also woken below rather than relied on to notice
            // on its own schedule.
            if (!anythingPaging()) alarm.stop()

            PulseMonitorService.sync(context)
            PulseMonitorService.wake()
        }

        /** Whether any monitor still owes the user a page. */
        private fun anythingPaging(): Boolean {
            val snap = store.snapshot.value
            return snap.monitors.any { monitor ->
                monitor.urgent && snap.runtimes[monitor.id]?.urgentState?.nagging == true
            }
        }

        init {
            // Widgets follow the data.
            //
            // Every placed widget renders exactly what `Summary.of` returns, so that
            // is what is watched: `distinctUntilChanged` over the fleet means a write
            // the widget cannot show — a latency-reference sample, a checker streak,
            // a settings toggle — costs no RemoteViews IPC, while anything it *can*
            // show is guaranteed to reach it. Includes `lastCheckedAt`, so the
            // footer's "Checked 2m ago" still moves with each check.
            //
            // Pulling rather than being pushed also removes the ordering problem
            // outright: there is no longer a caller that writes and then asks for a
            // render, so there is no window in which the render can precede the write
            // becoming visible.
            appScope.launch {
                store.snapshot
                    .map { Summary.of(it.monitors, it.runtimes) }
                    .distinctUntilChanged()
                    .collect { PulseWidgetProvider.refresh(context) }
            }

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
