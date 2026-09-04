package me.river.nightbell

import android.app.Application
import me.river.nightbell.data.Nightbell
import me.river.nightbell.data.diag.Diag
import me.river.nightbell.domain.LogEvent
import me.river.nightbell.domain.LogField
import me.river.nightbell.domain.runCatchingCancellable
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class NightbellApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        val graph = Nightbell.install(this)

        // Before anything else in the process can log. The sink holds a ring in
        // memory from its first line whether or not the user has the switch on,
        // and a check can run in a process WorkManager started, so waiting for
        // the store to be read would lose exactly the window a scheduling bug
        // lives in. See Diag for what the first store emission then does with it.
        Diag.install(
            context = this,
            scope = graph.appScope,
            secretsFor = { secretsIn(graph) },
        )
        Diag.installCrashHandler()
        Diag.log(
            LogEvent.APP_START,
            LogField.tag("version", BuildConfig.VERSION_NAME),
            LogField.of("code", BuildConfig.VERSION_CODE),
            // `text`, not `tag`: `releaseTest` has a capital in it, and `tag`
            // fingerprints anything that is not a lowercase slug, which is the
            // guard that keeps a monitor's name out of a line. The build type
            // is a constant Gradle chose, so it goes through the scrubber
            // instead and comes out readable.
            LogField.text("build", BuildConfig.BUILD_TYPE),
            LogField.of("api", android.os.Build.VERSION.SDK_INT),
        )
        Diag.log(
            LogEvent.ALERT_PERMISSION,
            LogField.of("notifications", graph.alerts.hasNotificationPermission()),
            LogField.of("full_screen", graph.alerts.canUseFullScreenIntent()),
        )
        graph.appScope.launch {
            // Waits for the first real read. `snapshot` starts on
            // `NightbellSnapshot()` defaults, so collecting it straight away
            // would hand the sink a false that means "not known yet", and the
            // sink would take it for the user's answer and drop the ring.
            graph.store.loadedFlow.first { it }
            graph.store.snapshot
                .map { it.settings.diagnosticLogEnabled }
                .distinctUntilChanged()
                .collect { Diag.setEnabled(it) }
        }

        // Synchronously, before any worker or receiver in this process can run: a
        // checker-crash claim is process-scoped by design (see CheckerHealth), so
        // one left on screen by a process that no longer exists is stale by
        // definition. This is "clear stale crash state after app restart",
        // and it costs one cancel() call.
        graph.engine.resetCheckerHealth("process start")

        graph.appScope.launch {
            runCatchingCancellable {
                val snapshot = graph.store.currentSnapshot()
                repairNotificationsIfNeeded(graph, snapshot.settings.notificationsRepairedForVersion)
                graph.scheduler.syncAll(snapshot.monitors, snapshot.settings)
            }.onFailure { Diag.logError(LogEvent.APP_SCHEDULE_REARM_FAILED, it) }
        }
    }


    /**
     * Everything the sink must never let through, from the live store.
     *
     * Read per line rather than captured, so a token pasted into Settings
     * applies to the next line instead of the next launch, and cached on
     * `revision` because this is the logging path: without the cache a forty
     * monitor fleet assembles a hundred and sixty strings for every line
     * written. `revision` is a total order on committed state, so it is exactly
     * the right key.
     *
     * Monitor and group names are in here and are not credentials. Users name
     * monitors after the systems they run, so a name is a description of
     * somebody's private infrastructure. No `LogField` factory carries one, and
     * putting them through this pass makes that true of every line rather than
     * of every line somebody remembered.
     */
    private fun secretsIn(graph: Nightbell.Graph): Collection<String> {
        val snap = graph.store.snapshot.value
        val cached = knownSecrets
        if (cached != null && knownSecretsRevision == snap.revision) return cached
        val built = buildList {
            add(snap.settings.githubToken)
            snap.monitors.forEach { monitor ->
                add(monitor.browserState.cookies)
                add(monitor.browserState.localStorage)
                monitor.headers.forEach { add(it.value) }
                add(monitor.name)
            }
            snap.groups.forEach { add(it.title) }
        }.filter { it.isNotBlank() }
        knownSecrets = built
        knownSecretsRevision = snap.revision
        return built
    }

    @Volatile
    private var knownSecrets: Collection<String>? = null

    @Volatile
    private var knownSecretsRevision = -1L

    /**
     * One-time cleanup after upgrading from a build that could strand alert
     * notifications.
     *
     * Runs before the schedule sync so the first check of the new version
     * re-posts from a clean slate. Guarded by a persisted version so it happens
     * exactly once per upgrade, not on every launch.
     *
     * Repair 4 covered 1.1.0's stranded alerts. Repair 5 covers 1.5.0 and
     * earlier, which could leave a whole fleet's worth of `ongoing`,
     * DND-bypassing "URGENT · … is down / Checker crashed" notifications behind —
     * one per monitor, none of them describing anything that happened. The
     * persisted state that fed them is scrubbed separately and on every read; see
     * `NightbellStore.scrubFakeCrashState`.
     */
    private suspend fun repairNotificationsIfNeeded(graph: Nightbell.Graph, repairedFor: Int) {
        if (repairedFor >= REPAIR_VERSION) return
        Diag.log(
            LogEvent.APP_REPAIR,
            LogField.of("from", repairedFor),
            LogField.of("to", REPAIR_VERSION),
        )
        graph.alerts.cancelEverything()

        // `cancelEverything` is indiscriminate — it has to be, since the whole
        // point is recovering from a state we cannot enumerate. But it also takes
        // down notifications describing outages that are happening *right now*, and
        // the claim that "anything genuinely current re-posts on the next check" is
        // simply not true of the down and degraded tracks: they are
        // transition-driven, and `AlertDecider.decide` with `wasAlerting = true` and
        // `repeatEnabled = false` (the shipped default) returns NO_TRANSITION for
        // as long as the outage lasts. A live outage would have gone completely
        // silent — no notification on screen and none coming — until it recovered.
        //
        // Clearing the bookkeeping turns the next check back into a transition, so
        // whatever is still true is raised again within one interval. `lastAlertAt`
        // goes with it or the cooldown would swallow that re-raise instead.
        // `urgentActive` is deliberately left alone: a genuine nag re-posts itself
        // from `tickUrgent` within its repeat gap, and the fabricated ones have
        // already been cleared by `LegacyCrashRepair`.
        graph.store.updateAllRuntimes {
            it.copy(
                alerting = false,
                lastAlertAt = 0L,
                degradedAlerting = false,
                lastDegradedAlertAt = 0L,
            )
        }
        graph.store.updateSettings { it.copy(notificationsRepairedForVersion = REPAIR_VERSION) }
    }

    private companion object {
        /**
         * Bump alongside `versionCode` whenever a release needs to clear
         * notifications left behind by its predecessor.
         */
        const val REPAIR_VERSION = 5
    }
}
