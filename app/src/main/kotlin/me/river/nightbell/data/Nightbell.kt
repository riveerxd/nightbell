package me.river.nightbell.data

import android.content.Context
import me.river.nightbell.data.alerts.AlertCenter
import me.river.nightbell.data.alerts.UrgentAlarm
import me.river.nightbell.data.check.CheckEngine
import me.river.nightbell.data.check.ElementChecker
import me.river.nightbell.data.check.GitHubChecker
import me.river.nightbell.data.check.HttpChecker
import me.river.nightbell.data.check.LatencyReference
import me.river.nightbell.data.check.UpdateChecker
import me.river.nightbell.data.update.UpdateInstaller
import me.river.nightbell.data.health.SystemLimits
import me.river.nightbell.data.icons.FaviconStore
import me.river.nightbell.data.net.NetworkMonitor
import me.river.nightbell.data.work.MonitorScheduler
import me.river.nightbell.data.work.NightbellMonitorService
import me.river.nightbell.BuildConfig
import me.river.nightbell.domain.Summary
import me.river.nightbell.domain.runCatchingCancellable
import me.river.nightbell.widget.NightbellWidgetProvider
import me.river.nightbell.domain.AppUpdate
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
object Nightbell {

    @Volatile
    private var graph: Graph? = null

    class Graph(private val context: Context) {
        val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = NightbellStore(context, appScope)
        val alerts = AlertCenter(context)

        /**
         * Shared so the pager-setup screen can report what the ringer currently
         * allows without constructing its own player. Only
         * [me.river.nightbell.data.work.NightbellMonitorService] ever calls `start`.
         */
        val alarm = UrgentAlarm(context)
        /**
         * Reads the proxy settings per check rather than capturing them, so an
         * address corrected in Settings applies to the next check instead of the
         * next launch. `snapshot.value` and not `currentSnapshot()` because this
         * is called from a non-suspending path.
         */
        val http = HttpChecker(settingsFor = { store.snapshot.value.settings })
        val element = ElementChecker(context, settingsFor = { store.snapshot.value.settings })
        val reference = LatencyReference()

        /**
         * Reads the token per call rather than capturing it, for the same reason
         * [http] reads the proxy per check: a token pasted into Settings has to
         * apply to the next poll, not to the next launch.
         */
        val github = GitHubChecker(settingsFor = { store.snapshot.value.settings })
        val updates = UpdateChecker()

        /**
         * Fetching and installing a release, on a tap and never otherwise.
         *
         * Graph-scoped rather than owned by a screen: the download outlives the
         * dashboard banner that starts it, because the dashboard is exactly what
         * a user leaves when they go and read the release notes, and a transfer
         * that restarts every time they come back is worse than no button.
         */
        val installer = UpdateInstaller(context, appScope)
        val engine = CheckEngine(
            store = store,
            http = http,
            element = element,
            alerts = alerts,
            reference = reference,
            github = github,
            updates = updates,
            installedVersion = { BuildConfig.VERSION_NAME },
        )
        val scheduler = MonitorScheduler(context)
        val network = NetworkMonitor(context)
        val favicons = FaviconStore(context, isOnline = network::isOnline)
        val limits = SystemLimits(context, isOnline = network::isOnline)

        /**
         * Pushes current state to the strict/urgent foreground service, which may
         * need to start or stop as a result.
         *
         * Placed widgets are deliberately **not** refreshed from here any more.
         * They follow [NightbellStore.snapshot] instead — see the collector in `init`.
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

            NightbellMonitorService.sync(context)
            NightbellMonitorService.wake()
        }

        /** Whether any monitor still owes the user a page. */
        private fun anythingPaging(): Boolean {
            val snap = store.snapshot.value
            return snap.monitors.any { monitor ->
                monitor.urgent && snap.runtimes[monitor.id]?.urgentState?.nagging == true
            }
        }

        init {
            // Point the update switch at wherever this copy came from, once.
            //
            // Only while it is still a guess: the moment the user touches the
            // switch it becomes their answer and this stops running. Doing it
            // here rather than at the first check means the Settings card is
            // already right the first time anyone opens it.
            appScope.launch {
                if (!store.currentSnapshot().settings.updateSourceChosen) {
                    val guess = AppUpdate.sourceForInstaller(installer.installerPackage())
                    store.updateSettings {
                        it.copy(updateSource = guess, updateSourceChosen = true)
                    }
                }
            }

            // The installer reports through a broadcast and the first thing it
            // reports is usually "somebody has to show the confirmation dialog".
            // Registered here rather than from a screen because the answer can
            // arrive after the screen that asked has gone.
            installer.registerStatusReceiver()

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
                    .map { snap ->
                        Summary.of(
                            snap.monitors,
                            snap.runtimes,
                            fleetPaused = snap.pause.stopsChecks(System.currentTimeMillis()),
                        )
                    }
                    .distinctUntilChanged()
                    .collect { NightbellWidgetProvider.refresh(context) }
            }

            // Wired here rather than inside CheckEngine so the engine itself
            // stays free of Android plumbing and remains unit-testable.
            engine.onStateChanged = ::notifyStateChanged
            engine.isOnline = network::isOnline
            // Lets the engine skip its fallback post when the service is already
            // showing the (fully red, looping) page for that outage.
            engine.serviceIsPaging = NightbellMonitorService::isPaging

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

    fun require(): Graph = graph ?: error("Nightbell.install() has not been called yet")

    fun from(context: Context): Graph = install(context)

    val store: NightbellStore get() = require().store
    val engine: CheckEngine get() = require().engine
    val alerts: AlertCenter get() = require().alerts
    val scheduler: MonitorScheduler get() = require().scheduler
    val network: NetworkMonitor get() = require().network
    val favicons: FaviconStore get() = require().favicons
    val scope: CoroutineScope get() = require().appScope
}
