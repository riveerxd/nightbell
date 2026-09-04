package me.river.nightbell.data.check

import android.util.Log
import me.river.nightbell.data.NightbellStore
import me.river.nightbell.data.alerts.AlertCenter
import me.river.nightbell.domain.AlertDecider
import me.river.nightbell.domain.AlertPolicy
import me.river.nightbell.domain.AppUpdate
import me.river.nightbell.domain.CertificateWatch
import me.river.nightbell.domain.CheckResult
import me.river.nightbell.domain.CheckerHealth
import me.river.nightbell.domain.CheckerStreak
import me.river.nightbell.domain.DueCheck
import me.river.nightbell.domain.FailureKind
import me.river.nightbell.domain.GitHubEvent
import me.river.nightbell.domain.GitHubEvents
import me.river.nightbell.domain.GitHubState
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.Health
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.MonitorRuntime
import me.river.nightbell.domain.PauseState
import me.river.nightbell.domain.NetworkBaseline
import me.river.nightbell.domain.TlsTrust
import me.river.nightbell.domain.UrgentAlerts
import me.river.nightbell.domain.runCatchingCancellable
import me.river.nightbell.domain.Reachability
import me.river.nightbell.domain.StatusExpectation
import me.river.nightbell.domain.StatusMode
import me.river.nightbell.domain.HttpMethod
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The heart of the app: runs a check, folds the outcome into persisted state,
 * and decides whether the human deserves to be interrupted.
 *
 * Four independent alert tracks run off one check:
 *  - **down** — the original outage/recovery escalation ([AlertDecider.decide])
 *  - **degraded** — latency-SLO breaches ([AlertDecider.decideDegraded])
 *  - **urgent** — the nag-until-acknowledged loop ([UrgentAlerts])
 *  - **checker health** — the checker itself being broken ([CheckerHealth])
 *
 * They deliberately don't share cooldowns or notification ids, so silencing one
 * never silences another. The fourth is the newest and the most important
 * separation: up to 1.5.0 a fault *inside Nightbell* was reported through the down
 * track as though the monitored site had gone down, and a cancelled check
 * counted as a fault. See [CheckerHealth] for the whole story.
 */
class CheckEngine(
    private val store: NightbellStore,
    private val http: HttpChecker,
    private val element: ElementChecker,
    private val alerts: AlertCenter,
    /**
     * Times a known-good endpoint so a slow *connection* is not reported as a
     * slow service. Null disables the compensation entirely, which is what the
     * unit-level tests want.
     */
    private val reference: LatencyReference? = null,
    /**
     * Polls GitHub for the repository monitors. Null leaves that kind
     * unsupported, which is what the unit-level tests want.
     */
    private val github: GitHubChecker? = null,
    /** Looks for a newer Nightbell. Null switches the whole track off. */
    private val updates: UpdateChecker? = null,
    /**
     * The version this build reports, for the update comparison. A lambda so the
     * engine never has to reach for `BuildConfig` and stays testable with an
     * arbitrary "installed" version.
     */
    private val installedVersion: () -> String = { "" },
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val minuteOfDay: () -> Int = {
        Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
    },
) {

    /**
     * Called after anything that could change what a widget or a foreground
     * service shows. Wired up by [me.river.nightbell.data.Nightbell]; a plain
     * lambda keeps this class free of Android plumbing.
     */
    var onStateChanged: (() -> Unit)? = null

    /**
     * Reads an alert out loud. Wired by [me.river.nightbell.data.Nightbell]; a
     * lambda for the same reason as [onStateChanged], and null in the unit tests,
     * which have no speech engine and no business starting one.
     *
     * Called only for an alert that actually made a sound, so speech inherits
     * quiet hours, mute, the failure threshold and the master switch by
     * construction rather than by repeating their rules here.
     */
    var announceAlert: ((Monitor, CheckResult, Boolean) -> Unit)? = null

    /**
     * Whether the device can reach anything. Wired to
     * [me.river.nightbell.data.net.NetworkMonitor] by the graph — a lambda
     * for the same reason as [onStateChanged], and defaulting to online so the
     * engine stays usable and testable with no Android around it.
     *
     * When this is false the engine does not check, does not record a sample and
     * does not alert. A check with no connectivity fails for a reason that says
     * nothing about the monitored thing, and it fails for *every* monitor at
     * once — so running them would turn a walk through a tunnel into a screenful
     * of false outages, and would poison the uptime history with them too.
     */
    var isOnline: () -> Boolean = { true }

    /**
     * Runs the check without touching persisted state, for "Test now".
     *
     * @param certPin the key this monitor is already pinned to, if any. Empty from
     *   "Test now" on a monitor that has never been saved, which is correct: a
     *   test of a brand new monitor has nothing to compare against and reports
     *   what the server presented.
     */
    suspend fun dryRun(monitor: Monitor, certPin: String = ""): CheckResult = when (monitor.kind) {
        MonitorKind.WEBSITE_ELEMENT -> element.check(monitor, certPin)
        // A GitHub poll against a blank state: it reads the repository and reports
        // what is there, and because nothing is persisted it cannot seed a
        // baseline or announce anything. Which is exactly what "Test now" means.
        MonitorKind.GITHUB_REPO -> githubDryRun(monitor)
        else -> http.check(monitor, certPin)
    }

    /**
     * When each monitor's certificate was last probed. In memory, on purpose.
     *
     * Persisting it would mean a new runtime field threaded through
     * [me.river.nightbell.domain.AlertDecider], and the only cost of losing it is
     * one extra HEAD request after a process restart. A cheap wrong answer beats
     * an expensive right one here.
     */
    private val certProbedAt = mutableMapOf<String, Long>()

    /**
     * Fills in the certificate expiry for a check that could not see it itself.
     *
     * Only for [MonitorKind.WEBSITE_ELEMENT] with
     * [Monitor.watchCertificate] on, and only once a day. A WebView never reports
     * the certificate of a page that loaded cleanly, so without this an element
     * monitor on an https page can never warn that the certificate is about to
     * expire: see the field's own comment.
     *
     * The probe is a HEAD through [HttpChecker] rather than a hand-rolled socket,
     * which is the point of doing it this way. That path already honours the
     * monitor's [TlsTrust] mode, its SOCKS routing, its timeout and its redirect
     * setting, and a second TLS stack next to the first would be a second set of
     * answers to all four.
     *
     * Only the certificate fields are taken from it. The verdict, the status code
     * and the latency belong to the page-element check that just ran, and letting
     * a HEAD to the same host overwrite them would report on the wrong thing
     * entirely: a page whose element is missing is down even when a HEAD to its
     * front door says 200.
     *
     * Once a day, not once a check. An expiry date does not move between two
     * checks fifteen minutes apart, and the warning thresholds are in days.
     */
    private suspend fun withCertificateExpiry(
        monitor: Monitor,
        before: MonitorRuntime,
        result: CheckResult,
    ): CheckResult {
        if (monitor.kind != MonitorKind.WEBSITE_ELEMENT) return result
        if (!monitor.watchCertificate) return result
        if (!monitor.url.startsWith("https://", ignoreCase = true)) return result
        val now = nowMs()
        val last = certProbedAt[monitor.id] ?: 0L
        // Carried forward rather than dropped between probes, so the card and the
        // alert see a date on every check and not one a day.
        if (last > 0L && now - last < CERT_PROBE_INTERVAL_MS) {
            return result.copy(
                certExpiresAt = before.certExpiresAt,
                certIssuer = before.certIssuer,
                certSpki = before.certPin,
            )
        }
        val probe = runCatchingCancellable {
            http.check(
                monitor.copy(
                    kind = MonitorKind.HTTP_STATUS,
                    method = HttpMethod.HEAD,
                    // The front door, whatever the element check was told to look
                    // at. A path that needs JavaScript to exist may well 404 to a
                    // HEAD, and the certificate is the host's, not the page's.
                    status = StatusExpectation(mode = StatusMode.ANY),
                    // The element assertions describe a node in a rendered page,
                    // which a HEAD has no body to satisfy. Cleared so the probe
                    // cannot fail for a reason that has nothing to do with TLS.
                    elements = emptyList(),
                ),
                before.certPin,
            )
        }.getOrNull()
        // A failed probe leaves the previous date standing rather than zeroing it.
        // Losing the expiry because the network blinked would take the card off the
        // screen and silence the warning at the same time.
        val seen = probe?.takeIf { it.certExpiresAt > 0L } ?: return result.copy(
            certExpiresAt = before.certExpiresAt,
            certIssuer = before.certIssuer,
            certSpki = before.certPin,
        )
        certProbedAt[monitor.id] = now
        return result.copy(
            certExpiresAt = seen.certExpiresAt,
            certIssuer = seen.certIssuer,
            certSpki = seen.certSpki,
        )
    }

    private suspend fun githubDryRun(monitor: Monitor): CheckResult {
        val checker = github ?: return CheckResult(
            ok = false,
            latencyMs = 0,
            failureKind = FailureKind.BAD_CONFIG,
            message = "GitHub checks are not available in this build",
            at = nowMs(),
        )
        val outcome = checker.poll(monitor, GitHubState())
        return outcome.result ?: CheckResult(
            ok = false,
            latencyMs = 0,
            failureKind = FailureKind.BAD_CONFIG,
            message = "GitHub is rate limiting this device",
            detail = "No repository data was returned. A token in Settings raises the limit " +
                "from 60 requests an hour to 5,000.",
            at = nowMs(),
        )
    }

    /**
     * One lock per monitor.
     *
     * `run()` reads the runtime, spends seconds doing network or WebView work,
     * then writes state derived from that read. Two overlapping runs of the
     * same monitor therefore clobber each other, and — worse — the losing run
     * can post a notification the winning run has already decided not to
     * cancel. Strict mode makes this easy to hit: the service loop and a manual
     * "Check now" can land on the same monitor seconds apart.
     *
     * Different monitors still run concurrently; only same-monitor overlap is
     * serialised.
     */
    private val locks = ConcurrentHashMap<String, Mutex>()

    /**
     * The checker's own health. See [CheckerHealth] — process-scoped on purpose,
     * so a restart cannot inherit a stale crash claim.
     */
    private val _checkerHealth = MutableStateFlow(CheckerHealth.State.Healthy)
    val checkerHealth: StateFlow<CheckerHealth.State> = _checkerHealth.asStateFlow()

    /**
     * Full pipeline for one monitor: check → persist → maybe alert.
     *
     * Returns null when the monitor no longer exists, when the device is offline
     * and the check was skipped, or when an exception escaped checker code — in
     * all three cases nothing was recorded about the monitor, because nothing was
     * learned about it.
     *
     * **Throws [CancellationException]** rather than reporting anything if the
     * check is cancelled. That is the whole fix: a cancelled check has no verdict,
     * and inventing one is what made the phone buzz about crashes that never
     * happened.
     */
    suspend fun run(monitorId: String, force: Boolean = true): CheckResult? =
        run(monitorId, force, PassWitness())

    private suspend fun run(monitorId: String, force: Boolean, witness: PassWitness): CheckResult? =
        locks.getOrPut(monitorId) { Mutex() }.withLock { runLocked(monitorId, force, witness) }

    /**
     * One reading of whether this phone can reach anything, shared for as long
     * as the object lives.
     *
     * A pass makes one and hands it to every check in it, the same way
     * [refreshReference] takes one reading of the latency control for the whole
     * pass. Losing signal fails every monitor at once, and without this a car
     * park with ten monitors in it meant ten identical probes to the same host
     * inside one sweep, all of them asking a question the first had answered.
     *
     * Lazy, which is the half that matters for cost: a pass where nothing fails
     * never calls [of] at all and so never spends a request. A pass where four
     * monitors fail spends exactly one.
     *
     * Deliberately not shared beyond the pass. A hand-driven re-check builds its
     * own and probes afresh, because it is rare, it is somebody waiting for an
     * answer, and reusing a verdict from a sweep minutes ago would be answering
     * a question nobody asked.
     */
    private class PassWitness {
        private var asked = false
        private var verdict = Reachability.Verdict.UNKNOWN

        suspend fun of(probe: suspend () -> Reachability.Verdict): Reachability.Verdict {
            if (!asked) {
                verdict = probe()
                asked = true
            }
            return verdict
        }
    }

    private suspend fun runLocked(
        monitorId: String,
        force: Boolean,
        witness: PassWitness,
    ): CheckResult? {
        val snapshot = store.currentSnapshot()
        val monitor = snapshot.monitors.firstOrNull { it.id == monitorId } ?: return null
        val before = snapshot.runtimes[monitorId] ?: MonitorRuntime()

        // A pause is felt as the master alert switch being off for its duration.
        //
        // Every alert track in this class already honours that one gate, so
        // routing a pause through it means a paused fleet cannot page from some
        // track nobody remembered to teach about pausing. The copy is local and
        // read-only: nothing here writes settings back.
        val paused = snapshot.pause.isActive(nowMs())
        val settings = if (paused) {
            snapshot.settings.copy(masterAlertsEnabled = false)
        } else {
            snapshot.settings
        }

        // Re-checked *inside* the lock, not only by the caller. Every scheduled
        // caller evaluates due-ness before waiting on this mutex, and what it waits
        // behind is another check of this same monitor — so by the time the lock is
        // free the interval has usually just been satisfied. Without this, the sweep
        // and a monitor's own worker sharing one wake-up produced two checks a
        // second apart, which is the duplicate-sample symptom the outer gate was
        // added to fix.
        if (!force && !isDue(monitor, before, nowMs())) {
            Log.i(TAG, "${monitor.displayName} became not-due while waiting; skipping")
            return null
        }

        // Before `markChecking`, deliberately: bailing after it would leave the
        // monitor spinning forever. Callers gate too — this is the backstop that
        // makes it impossible for any of them to record an offline failure.
        if (!isOnline()) {
            Log.i(TAG, "Offline — skipping ${monitor.displayName}")
            return null
        }

        // Before `markChecking` for the same reason the offline gate is: bailing
        // after it leaves the monitor spinning forever.
        //
        // `force` is the escape hatch, and it has to be. A pause stops the
        // *schedule*; it is not a lock on the app. Every hand-driven check comes
        // through here with force set: the re-check button on a card, the detail
        // screen's "Check now", the first check of a monitor the user just saved.
        // Refusing those left the button saying "Checking…" and then nothing at
        // all, which is indistinguishable from a dead tap and is the exact
        // complaint this engine has been answering since 1.6.0.
        if (!force && snapshot.pause.stopsChecks(nowMs())) {
            Log.i(TAG, "Paused: skipping ${monitor.displayName}")
            return null
        }

        store.markChecking(monitorId, true)
        var githubOutcome: GitHubChecker.Outcome? = null
        val result = try {
            if (monitor.kind == MonitorKind.GITHUB_REPO && github != null) {
                // Polled here rather than through `dryRun`, because the interesting
                // half of a repository check is not the verdict: it is the ETags,
                // the rate-limit headers and the diff against last time, none of
                // which fit in a CheckResult.
                // `force` is a user gesture every time it is true, and it is the
                // way past a backed-off comment track: somebody who taps
                // re-check has asked to look now, whatever the ladder says.
                val outcome = github.poll(monitor, before.github, force = force)
                githubOutcome = outcome
                outcome.result
            } else {
                withCertificateExpiry(monitor, before, dryRun(monitor, before.certPin))
            }
        } catch (cancellation: CancellationException) {
            // The single most important catch in this app.
            //
            // Cancellation here is Android working correctly, not a failure:
            // WorkManager replacing or stopping work, a foreground service
            // shutting down, a `viewModelScope` closing because the user left the
            // screen, an execution window ending, the process being reclaimed.
            // 1.5.0 and earlier caught this with the `Throwable` clause below,
            // fabricated a failed result labelled "Checker crashed", and ran it
            // through the down-alert track — notification, vibration and all. The
            // persist that would have applied a cooldown was the *next*
            // suspending call on the same cancelled coroutine, so it threw too,
            // and the next cancellation alerted at full volume all over again.
            //
            // A cancelled check has no verdict. Record nothing, say nothing, and
            // let cancellation finish travelling up the stack where it belongs.
            //
            // Folded through the machine rather than simply skipped, so the
            // "cancellation is not evidence" rule lives in one tested place
            // ([CheckerHealth.recordCancellation]) instead of being re-assumed
            // here. It is a no-op today; if that ever changes, it changes here too.
            _checkerHealth.value = CheckerHealth.recordCancellation(_checkerHealth.value, nowMs()).state
            Log.i(TAG, "Check for ${monitor.displayName} was cancelled; no verdict recorded")
            throw cancellation
        } catch (error: Throwable) {
            // A real escape from checker code. Both checkers classify their own
            // failures into FailureKind and do not throw, so anything arriving
            // here is a bug in ours — which is a statement about Nightbell, not about
            // the monitored service. It goes to the checker-health track and
            // *nowhere near* this monitor's health: "our code broke" has never
            // been evidence that somebody's website is down.
            Log.e(TAG, "Checker threw while checking ${monitor.displayName}", error)
            noteInternalError(monitor, settings, snapshot.checkerStreak, error)
            null
        } finally {
            store.markChecking(monitorId, false)
        }
        // Between the checker and the record: a failure that never reached
        // anything might be this phone rather than the service.
        //
        // Folded into the existing no-verdict path rather than given one of its
        // own, because "the network could not carry this check" and "the checker
        // could not produce a verdict" want exactly the same treatment: no
        // health, no sample, no alert, only the note that an attempt happened.
        // Writing a second version of that would be writing a second set of ways
        // to get it subtly wrong.
        val worthConfirming = result != null && reference != null && Reachability.shouldConfirm(
            enabled = settings.confirmOutagesEnabled,
            referenceEnabled = settings.latencyBaselineEnabled,
            referenceUrl = settings.latencyReferenceUrl,
            ok = result.ok,
            kind = result.failureKind,
        )
        // A missing reference is not evidence, so it pages: every path out of
        // this that is not a proven-dead network ends with UNKNOWN.
        val probe = if (worthConfirming) {
            witness.of { reference.reach(settings.latencyReferenceUrl) }
        } else {
            Reachability.Verdict.UNKNOWN
        }
        val localOutage = Reachability.isLocalOutage(
            probe = probe,
            // Same readings the latency verdict below uses, and no new request:
            // a reference that has not answered this phone in six hours does not
            // get to claim the network is dead.
            referenceHasVouched = Reachability.hasVouched(snapshot.reference, nowMs()),
        )
        if (localOutage) {
            Log.i(
                TAG,
                "${monitor.displayName} reached nothing and neither did the reference; " +
                    "recording nothing rather than paging for this phone's network",
            )
        }

        if (result == null || localOutage) {
            // Record *only* that an attempt happened at this time — no health, no
            // sample, no message. Without this the monitor stays permanently due,
            // and `nextWakeDelayMs` floors to MIN_TICK_MS, so a checker that
            // throws every time would be retried every 15 seconds forever. The old
            // code got interval back-off for free because it fabricated a full
            // verdict; not fabricating one means paying for the back-off honestly.
            //
            // A rate-limited GitHub poll lands here too, and deliberately: being
            // refused a request is not evidence about the repository, so it must
            // not touch health, samples or any alert track. What it *is* evidence
            // of is the budget, so that much is written down and shown on the card.
            val rateState = githubOutcome?.takeIf { it.rateLimited }?.state
            store.updateRuntime(monitorId) {
                it.copy(
                    lastCheckedAt = nowMs(),
                    github = rateState ?: it.github,
                )
            }
            if (rateState != null) {
                Log.i(TAG, "${monitor.displayName}: GitHub is rate limiting, nothing recorded")
                onStateChanged?.invoke()
            }
            return null
        }

        // The check reached a verdict, so the checker demonstrably works —
        // whatever that verdict was. A classified failure is proof of a working
        // checker just as much as a pass is.
        noteVerdict(snapshot.checkerStreak)

        val slo = monitor.sloMs(settings)

        // Discount whatever this phone's own connection is adding before calling
        // anything slow. Only consulted for the latency verdict: a bad connection
        // is a reason to doubt a *slow* reading, never a reason to stay quiet
        // about an outage.
        val verdict = NetworkBaseline.judge(
            observedMs = result.latencyMs,
            readings = if (settings.latencyBaselineEnabled) snapshot.reference else emptyList(),
            nowMs = result.at,
        )
        val judgedLatency = if (settings.latencyBaselineEnabled) verdict.adjustedMs else result.latencyMs
        val suspect = settings.latencyBaselineEnabled && verdict.unreliable

        val after = AlertDecider.advance(
            previous = before,
            result = result,
            historyDepth = settings.historyDepth,
            // The health tint follows the same judgement as the alert, or a card
            // would sit amber explaining nothing while no notification arrived.
            degradedAboveMs = if (slo > 0 && !suspect) slo.toLong() else Long.MAX_VALUE,
            judgedLatencyMs = judgedLatency,
        )
        val degraded = !suspect && AlertDecider.isDegraded(result.ok, judgedLatency, slo.toLong())
        val policy: AlertPolicy = if (monitor.useGlobalAlerts) settings.defaultAlert else monitor.alert
        val muted = before.mutedUntil > nowMs()
        val minute = minuteOfDay()

        // ---- down track -----------------------------------------------------
        val decision = if (muted) {
            AlertDecider.Decision.none(AlertDecider.Suppression.POLICY_DISABLED)
        } else {
            AlertDecider.decide(
                wasAlerting = before.alerting,
                ok = result.ok,
                consecutiveFailures = after.consecutiveFailures,
                lastAlertAt = before.lastAlertAt,
                policy = policy,
                masterEnabled = settings.masterAlertsEnabled,
                nowMs = result.at,
                minuteOfDay = minute,
            )
        }

        when (decision.kind) {
            AlertDecider.Kind.DOWN, AlertDecider.Kind.REPEAT -> {
                alerts.notifyDown(
                    monitor = monitor,
                    result = result,
                    policy = policy,
                    silent = decision.forceSilent,
                    repeat = decision.kind == AlertDecider.Kind.REPEAT,
                )
                // Speech follows the notification's own verdict: a silenced alert
                // stays silent, and an alert that never fired says nothing. An
                // URGENT monitor is deliberately left out here, because its page
                // is spoken by the service that owns the siren, and saying it
                // twice over the top of itself is worse than not saying it.
                if (policy.speak && !decision.forceSilent && !monitor.urgent) {
                    announceAlert?.invoke(monitor, result, decision.kind == AlertDecider.Kind.REPEAT)
                }
            }

            AlertDecider.Kind.RECOVERY -> alerts.notifyRecovery(
                monitor = monitor,
                result = result,
                policy = policy,
                silent = decision.forceSilent,
            )

            else -> Unit
        }

        val alertingNow = when (decision.kind) {
            AlertDecider.Kind.DOWN, AlertDecider.Kind.REPEAT -> true
            AlertDecider.Kind.RECOVERY -> false
            else -> if (result.ok) false else before.alerting
        }
        // Clear the stale "down" notification — unless we just replaced it with a
        // recovery notification, which reuses the same id and would be wiped out.
        //
        // Not gated on `before.alerting`: that is read before the check runs and
        // can be stale, which is exactly how a down notification outlives the
        // outage it describes. Healthy and no recovery posted ⇒ nothing should
        // be showing, so cancel and don't reason about how it got there.
        if (result.ok && decision.kind != AlertDecider.Kind.RECOVERY) {
            alerts.cancel(monitor.id)
        }

        // ---- degraded track --------------------------------------------------
        val degradedDecision = if (muted) {
            AlertDecider.Decision.none(AlertDecider.Suppression.POLICY_DISABLED)
        } else {
            AlertDecider.decideDegraded(
                wasDegradedAlerting = before.degradedAlerting,
                ok = result.ok,
                degraded = degraded,
                lastDegradedAlertAt = before.lastDegradedAlertAt,
                policy = policy,
                masterEnabled = settings.masterAlertsEnabled,
                nowMs = result.at,
                minuteOfDay = minute,
            )
        }

        when (degradedDecision.kind) {
            AlertDecider.Kind.DEGRADED, AlertDecider.Kind.DEGRADED_REPEAT -> alerts.notifyDegraded(
                monitor = monitor,
                result = result,
                policy = policy,
                sloMs = slo,
                silent = degradedDecision.forceSilent,
                repeat = degradedDecision.kind == AlertDecider.Kind.DEGRADED_REPEAT,
            )

            AlertDecider.Kind.DEGRADED_RECOVERY -> alerts.notifyDegradedRecovery(
                monitor = monitor,
                result = result,
                policy = policy,
                sloMs = slo,
                silent = degradedDecision.forceSilent,
            )

            else -> Unit
        }

        val degradedAlertingNow = when (degradedDecision.kind) {
            AlertDecider.Kind.DEGRADED, AlertDecider.Kind.DEGRADED_REPEAT -> true
            AlertDecider.Kind.DEGRADED_RECOVERY -> false
            // An outage supersedes slowness; the down notification is the story.
            else -> if (!result.ok) false else if (degraded) before.degradedAlerting else false
        }
        if (!degraded && before.degradedAlerting &&
            degradedDecision.kind != AlertDecider.Kind.DEGRADED_RECOVERY
        ) {
            alerts.cancelDegraded(monitor.id)
        }

        // ---- certificate track -----------------------------------------------
        //
        // Reads `after`, not `result`: a check that failed to complete a handshake
        // carries no expiry date, and the fold above deliberately keeps the last
        // one we saw. Judging from the raw result would silently drop the warning
        // for any monitor that happened to time out.
        //
        // A monitor set to accept any certificate is excluded, and only from the
        // *alert*. The expiry is still read and still shown on the detail screen,
        // because knowing when it lapses is free and occasionally useful. What it
        // must not do is wake anyone: the user has said in as many words that this
        // certificate is not something they want judged, and nagging them about
        // next Tuesday's expiry on a cert they told the app to ignore is the app
        // arguing with a decision it was told about. Pinned monitors keep their
        // alerts, because a pinned self-signed certificate still expires and
        // someone still has to go and regenerate it.
        val certLevel = if (settings.certAlertsEnabled && monitor.tlsTrust != TlsTrust.ANY) {
            CertificateWatch.level(
                expiresAt = after.certExpiresAt,
                nowMs = result.at,
                warnDays = settings.certWarnDays,
                criticalDays = settings.certCriticalDays,
            )
        } else {
            CertificateWatch.Level.UNKNOWN
        }
        val certQuiet = policy.quietHoursEnabled &&
            AlertDecider.inQuietHours(minute, policy.quietStartMinute, policy.quietEndMinute)
        // Nothing about a renewal deadline justifies overriding quiet hours, mute
        // or the master switch, so this track has no bypass at all — not even the
        // silent-but-still-posted one the down track gets.
        val certShouldAlert = !muted &&
            settings.masterAlertsEnabled &&
            policy.enabled &&
            !certQuiet &&
            CertificateWatch.shouldAlert(
                level = certLevel,
                alertedLevel = before.certAlertedLevel,
                lastAlertAt = before.lastCertAlertAt,
                nowMs = result.at,
            )
        if (certShouldAlert) {
            alerts.notifyCertExpiry(
                monitor = monitor,
                level = certLevel,
                daysLeft = CertificateWatch.daysLeft(after.certExpiresAt, result.at),
                expiresAt = after.certExpiresAt,
                issuer = after.certIssuer,
                policy = policy,
                silent = false,
            )
        }
        // A renewed certificate has to take its notice down with it. Cancelling on
        // the level rather than on a transition means a notice left behind by a
        // process death gets cleared by the next healthy check too.
        val certResolved = certLevel == CertificateWatch.Level.OK ||
            certLevel == CertificateWatch.Level.UNKNOWN
        if (certResolved && before.certAlertedLevel > CertificateWatch.Level.OK.rank) {
            alerts.cancelCert(monitor.id)
        }
        // Trust on first use, second half. The first successful handshake under
        // TlsTrust.PINNED is the one that records the key, and every check after it
        // is compared against what this stores.
        //
        // Three conditions, each load-bearing. The check succeeded, so a key seen
        // during a failure is never armed. The mode asks for a pin, so turning
        // pinning on later does not inherit a key recorded under some other mode.
        // And nothing is pinned yet, because re-recording on every success is not
        // pinning at all: the mismatch that this exists to catch would overwrite
        // itself and report nothing.
        val armPin = result.ok &&
            monitor.tlsTrust == TlsTrust.PINNED &&
            before.certPin.isBlank() &&
            result.certSpki.isNotBlank()
        val certMutation: (MonitorRuntime) -> MonitorRuntime = { runtime ->
            runtime.copy(
                certAlertedLevel = if (certShouldAlert || certResolved) {
                    CertificateWatch.alertedLevelAfter(certLevel)
                } else {
                    runtime.certAlertedLevel
                },
                lastCertAlertAt = if (certShouldAlert) result.at else runtime.lastCertAlertAt,
                certPin = if (armPin) result.certSpki else runtime.certPin,
            )
        }

        // ---- urgent track ----------------------------------------------------
        val urgentOutcome = UrgentAlerts.evaluate(
            previous = before.urgentState,
            eligible = urgentEligible(monitor, policy, settings, after, muted, minute),
            down = !result.ok,
            nowMs = result.at,
            repeatMinutes = monitor.urgentRepeatMinutes,
        )
        val urgentMutation =
            applyUrgent(monitor, urgentOutcome, result, policy, after, settings.urgentRespectsRingerMode)

        // ---- github track ----------------------------------------------------
        val githubMutation = githubOutcome?.let {
            applyGitHub(monitor, it, before, policy, settings, muted, minute)
        } ?: { runtime: MonitorRuntime -> runtime }

        // NonCancellable, and this is load-bearing.
        //
        // Every alert side effect above — notifyDown, notifyRecovery, cancel,
        // cancelDegraded, cancelUrgent, notifyUrgent — is a plain function call, so
        // there is no suspension point between them and this write. Cancellation
        // can therefore only ever be observed *here*, after the shade has already
        // been changed. Letting it through leaves the store asserting the state the
        // notifications no longer show: a recovered monitor still recorded DOWN with
        // `urgentActive = true`, which `tickUrgent` then re-shouts about — ongoing,
        // DND-bypassing, "URGENT · X is down" for a monitor that is up. Which is
        // precisely the symptom this release was opened for.
        //
        // The write is a single small DataStore commit, so making it uninterruptible
        // is bounded and cheap.
        withContext(NonCancellable) {
            store.updateRuntime(monitorId) {
                after
                    .copy(
                        lastNetworkExcessMs = if (settings.latencyBaselineEnabled) verdict.excessMs else 0L,
                        lastLatencySuspect = suspect,
                        alerting = alertingNow,
                        lastAlertAt = if (decision.shouldNotify) result.at else before.lastAlertAt,
                        degradedAlerting = degradedAlertingNow,
                        lastDegradedAlertAt = if (degradedDecision.shouldNotify) {
                            result.at
                        } else {
                            before.lastDegradedAlertAt
                        },
                        mutedUntil = before.mutedUntil,
                        health = if (!monitor.enabled) Health.PAUSED else after.health,
                    )
                    .withUrgentState(urgentOutcome.state)
                    .let(urgentMutation)
                    .let(certMutation)
                    .let(githubMutation)
            }
        }
        onStateChanged?.invoke()
        return result
    }

    /** Serialises probing so a burst of passes shares one round trip. */
    private val referenceLock = Mutex()

    /**
     * Consecutive failed probes, for backing off.
     *
     * In memory on purpose: a fresh process retrying sooner is harmless, and this
     * has no business in the persisted store.
     */
    private var referenceFailures = 0

    /**
     * Times the reference endpoint if the newest reading has gone stale.
     *
     * Called once per **pass**, never per check. It used to run inside
     * `runLocked`, which meant a reference the network blocks added its whole
     * timeout to a check — the measurement itself was unaffected, but the pass
     * got slower for no benefit. One timing per pass is all the maths wants
     * anyway, since the window is a rolling estimate and tolerates a reading a
     * little older than the check it informs.
     *
     * Backs off exponentially while probes fail, so a network that blocks the
     * endpoint settles at one wasted request every forty-eight minutes rather
     * than one per pass.
     */
    private suspend fun refreshReference() {
        val settings = store.currentSnapshot().settings
        if (!settings.latencyBaselineEnabled) return
        val probe = reference ?: return
        if (!isOnline()) return

        referenceLock.withLock {
            val interval = REFERENCE_MIN_INTERVAL_MS shl referenceFailures.coerceAtMost(6)
            val readings = store.currentSnapshot().reference
            if (!NetworkBaseline.needsProbe(readings, nowMs(), interval)) return
            val rtt = probe.probe(settings.latencyReferenceUrl)
            referenceFailures = if (rtt == null) (referenceFailures + 1).coerceAtMost(6) else 0
            store.updateReference { NetworkBaseline.record(it, rtt, nowMs()) }
        }
    }

    /**
     * Whether the urgent loop is allowed to shout right now.
     *
     * Urgent overrides cooldown and the repeat toggle — that is the feature —
     * but it deliberately still honours the master switch, the per-monitor
     * alert switch, mute, the failure threshold and quiet hours. Urgent means
     * "don't let me miss it", not "ignore everything I configured".
     */
    private fun urgentEligible(
        monitor: Monitor,
        policy: AlertPolicy,
        settings: GlobalSettings,
        after: MonitorRuntime,
        muted: Boolean,
        minute: Int,
    ): Boolean {
        if (!monitor.urgent) return false
        if (muted) return false
        if (!settings.masterAlertsEnabled) return false
        if (!policy.enabled || !policy.alertOnDown) return false
        if (after.consecutiveFailures < policy.failureThreshold.coerceAtLeast(1)) return false
        val quiet = policy.quietHoursEnabled &&
            AlertDecider.inQuietHours(minute, policy.quietStartMinute, policy.quietEndMinute)
        return !quiet || policy.criticalBypassesQuiet
    }

    /**
     * Applies one urgent outcome.
     *
     * The page itself is *not* posted here any more. It is the foreground
     * service's own notification, because `setColorized` — the only way to have
     * the platform paint the whole card in the down colour — is honoured for
     * nothing else; verified on a device. So this records that a page is owed and
     * lets [me.river.nightbell.data.work.NightbellMonitorService] render it, falling back
     * to an ordinary post when that service could not be started.
     *
     * @return the runtime mutation this outcome implies.
     */
    private fun applyUrgent(
        monitor: Monitor,
        outcome: UrgentAlerts.Outcome,
        result: CheckResult?,
        policy: AlertPolicy,
        runtime: MonitorRuntime,
        respectRinger: Boolean,
    ): (MonitorRuntime) -> MonitorRuntime {
        when (outcome.action) {
            UrgentAlerts.Action.START, UrgentAlerts.Action.REPEAT -> {
                val at = nowMs()
                val evidence = result ?: CheckResult(
                    ok = false,
                    latencyMs = runtime.lastLatencyMs,
                    failureKind = FailureKind.UNKNOWN,
                    message = runtime.lastMessage.ifBlank { "Still down" },
                    at = at,
                )
                pageWanted = true
                // The service owns the loud copy. If it is not running — Android 12+
                // refuses a background foreground-service start without an
                // exemption — this at least still interrupts the user.
                if (!serviceIsPaging()) {
                    val since = if (runtime.urgentSinceAt == 0L) at else runtime.urgentSinceAt
                    alerts.postUrgentPageFallback(
                        monitor = monitor,
                        result = evidence,
                        policy = policy,
                        downForMs = at - since,
                        pageCount = runtime.urgentPageCount + 1,
                        respectRinger = respectRinger,
                    )
                }
                return { it.withUrgentPaged(at) }
            }

            UrgentAlerts.Action.CLEAR -> {
                alerts.cancelUrgent(monitor.id)
                return { it }
            }

            UrgentAlerts.Action.NONE -> return { it }
        }
    }

    // ---- github track --------------------------------------------------------

    /**
     * Turns one repository poll into notifications, and returns the state to keep.
     *
     * The decision about *what happened* is [GitHubEvents], which is pure and
     * tested. What is decided here is the only part that needs the world: whether
     * the user wants to hear about it right now.
     *
     * Repository news honours the master switch, the monitor's alert switch and
     * mute, and goes silent (rather than quiet) during quiet hours. It has no
     * bypass of any kind, because nothing about a star is worth waking anyone.
     *
     * The state advances whether or not anything is announced. A mute is "stop
     * shouting", not "save it all up for me": replaying a muted day's worth of
     * stars the moment the mute lifts would be the opposite of what was asked.
     */
    private fun applyGitHub(
        monitor: Monitor,
        outcome: GitHubChecker.Outcome,
        before: MonitorRuntime,
        policy: AlertPolicy,
        settings: GlobalSettings,
        muted: Boolean,
        minute: Int,
    ): (MonitorRuntime) -> MonitorRuntime {
        val snapshot = outcome.snapshot ?: return { it }
        val evaluation = GitHubEvents.evaluate(
            watch = monitor.github,
            previous = before.github,
            snapshot = snapshot,
            nowMs = nowMs(),
        )
        val quiet = policy.quietHoursEnabled &&
            AlertDecider.inQuietHours(minute, policy.quietStartMinute, policy.quietEndMinute)
        val allowed = !muted && settings.masterAlertsEnabled && policy.enabled
        if (allowed) {
            evaluation.events.forEach { event ->
                Log.i(TAG, "${monitor.displayName}: ${event.title(monitor.github.slug)}")
                alerts.notifyGitHub(monitor, event, policy, silent = quiet)
            }
        } else if (evaluation.events.isNotEmpty()) {
            Log.i(TAG, "${monitor.displayName}: ${evaluation.events.size} repo event(s), alerts are off")
        }
        val state = evaluation.state
        return { runtime -> runtime.copy(github = state) }
    }

    // ---- nightbell's own updates ---------------------------------------------

    /**
     * Asks whether a newer Nightbell exists, at most once every six hours.
     *
     * Driven from the sweep and the service loop rather than from a check, because
     * it has nothing to do with any one monitor and a device with no monitors at
     * all should still be told. Everything about *whether to speak* is
     * [AppUpdate.decide]; everything here is plumbing and the master switch.
     *
     * @return whether a notification was posted.
     */
    suspend fun checkForAppUpdate(force: Boolean = false): Boolean {
        val checker = updates ?: return false
        if (!isOnline()) return false
        val snapshot = store.currentSnapshot()
        val settings = snapshot.settings
        if (!settings.updateChecksEnabled) return false
        // A pause is the user saying they do not want to hear from this app.
        if (snapshot.pause.isActive(nowMs())) return false
        if (!force && !AppUpdate.isDue(snapshot.update, nowMs())) return false

        val installed = installedVersion()
        val release = runCatchingCancellable { checker.latest(settings.updateSource) }.getOrNull()
        val decision = AppUpdate.decide(release, installed, snapshot.update, nowMs())
        val speaking = decision.action == AppUpdate.Action.NOTIFY &&
            decision.release != null &&
            settings.masterAlertsEnabled

        // `notifiedVersion` records that the user *was told*. Writing it while the
        // master switch is off would mean the one notification this version ever
        // gets was spent on a shade nothing reached, and turning alerts back on
        // would never produce it.
        val state = if (decision.action == AppUpdate.Action.NOTIFY && !speaking) {
            decision.state.copy(notifiedVersion = snapshot.update.notifiedVersion)
        } else {
            decision.state
        }
        withContext(NonCancellable) { store.updateAppUpdate { state } }

        if (!speaking) return false
        val notice = decision.release ?: return false
        Log.i(TAG, "Nightbell $installed is behind ${notice.version} on ${notice.source.label}")
        alerts.notifyUpdate(notice, installed, settings.defaultAlert)
        onStateChanged?.invoke()
        return true
    }

    /**
     * Whether the foreground service is currently the thing showing the page.
     *
     * Wired by the graph. Defaults to false so the engine stays testable and so
     * the fallback post — the safe direction — is what happens when nothing has
     * told us otherwise.
     */
    var serviceIsPaging: () -> Boolean = { false }

    /**
     * Set whenever a page is owed, so the service knows to re-render and start
     * making noise without re-deriving the decision. Read-and-cleared by
     * [consumePageWanted].
     */
    @Volatile
    private var pageWanted: Boolean = false

    fun consumePageWanted(): Boolean {
        val wanted = pageWanted
        pageWanted = false
        return wanted
    }

    // ---- checker health ------------------------------------------------------

    /**
     * Folds one escaped exception into [CheckerHealth] and acts on the verdict.
     *
     * Honours the master alert switch and quiet hours, because a broken checker
     * is still an interruption and the user's settings still mean what they say.
     * Vibration only on the first [CheckerHealth.Action.RAISE], never on a
     * repeat: repeats exist so the notification does not silently rot, not to
     * nag.
     */
    private suspend fun noteInternalError(
        monitor: Monitor,
        settings: GlobalSettings,
        persisted: CheckerStreak,
        error: Throwable,
    ) {
        val now = nowMs()
        // Evidence from disk, claim from this process. `Application.onCreate` runs
        // on every WorkManager-spawned process, so a purely in-memory streak could
        // never reach the raise threshold in background-only operation — see
        // [CheckerStreak].
        val previous = CheckerHealth.hydrate(persisted, _checkerHealth.value, now)
        val outcome = CheckerHealth.recordInternalError(
            previous = previous,
            monitorId = monitor.id,
            signature = error::class.java.simpleName,
            detail = error.message ?: error::class.java.simpleName,
            nowMs = now,
        )
        _checkerHealth.value = outcome.state
        store.updateCheckerStreak { CheckerHealth.toStreak(outcome.state) }
        when (outcome.action) {
            CheckerHealth.Action.RAISE, CheckerHealth.Action.REPEAT -> {
                // The monitor's *effective* policy, resolved the same way every
                // other track resolves it. Reading `settings.defaultAlert`
                // directly meant a user whose monitors all carry their own policy
                // was judged by a global default they had never opened: vibration
                // they had switched off, and quiet hours they had switched on,
                // both ignored.
                val policy = if (monitor.useGlobalAlerts) settings.defaultAlert else monitor.alert
                val quiet = policy.quietHoursEnabled && AlertDecider.inQuietHours(
                    minuteOfDay(),
                    policy.quietStartMinute,
                    policy.quietEndMinute,
                )
                // `policy.enabled` as well as the master switch. Every other
                // track honours it (AlertDecider returns POLICY_DISABLED,
                // urgentEligible returns false); the editor renders it as "You
                // won't be notified at all", so buzzing through it would make that
                // sentence a lie.
                if (!settings.masterAlertsEnabled || !policy.enabled) {
                    Log.i(TAG, "Checker crash verified but alerts are switched off")
                } else {
                    alerts.notifyCheckerCrash(
                        state = outcome.state,
                        monitorName = monitor.displayName,
                        policy = policy,
                        silent = quiet,
                        repeat = outcome.action == CheckerHealth.Action.REPEAT,
                    )
                }
            }

            CheckerHealth.Action.CLEAR -> alerts.cancelCheckerHealth()
            CheckerHealth.Action.NONE -> Unit
        }
        onStateChanged?.invoke()
    }

    /** A check produced a verdict, so the checker works. Clears any crash claim. */
    private suspend fun noteVerdict(persisted: CheckerStreak) {
        val nothingToClear = persisted.isEmpty && _checkerHealth.value == CheckerHealth.State.Healthy
        // The overwhelmingly common case, and it must not cost a DataStore write on
        // every single successful check.
        if (nothingToClear) return
        val outcome = CheckerHealth.recordVerdict(_checkerHealth.value, nowMs())
        _checkerHealth.value = outcome.state
        if (!persisted.isEmpty) store.updateCheckerStreak { CheckerStreak.Empty }
        if (outcome.action == CheckerHealth.Action.CLEAR) alerts.cancelCheckerHealth()
    }

    /**
     * Drops any crash claim and takes its notification down.
     *
     * Called from everything that changes *how* checks run — process start,
     * background checks being switched off, the scheduler re-arming, the strict
     * service starting or stopping — because a claim about how checks were
     * failing does not survive the checks being rebuilt. Cheap and idempotent;
     * a genuinely broken checker re-earns the claim over the next three checks.
     */
    fun resetCheckerHealth(reason: String) {
        if (_checkerHealth.value != CheckerHealth.State.Healthy) {
            Log.i(TAG, "Dropping this process's checker-health claim ($reason)")
        }
        _checkerHealth.value = CheckerHealth.reset(_checkerHealth.value).state
        // Unconditional: the notification is the thing the user actually sees, and
        // a process that no longer holds the claim must not leave it on screen.
        alerts.cancelCheckerHealth()
    }

    /**
     * [resetCheckerHealth], and the persisted evidence with it.
     *
     * For the events that change *how* checks run — a reboot, a settings write that
     * re-arms the scheduler — where a streak accumulated under the old arrangement
     * says nothing about the new one. Deliberately **not** used at process start:
     * the evidence surviving a background wake is the entire reason it is persisted
     * (see [CheckerStreak]), and only the claim is process-scoped.
     */
    suspend fun clearCheckerHealth(reason: String) {
        resetCheckerHealth(reason)
        store.updateCheckerStreak { CheckerStreak.Empty }
    }

    /**
     * Forgets a pinned key so the next successful check records the current one.
     *
     * The only way out of a pin, and there has to be one. A mismatch deliberately
     * fails the check instead of adopting the new key, which is correct and would
     * be a trap on its own: someone who replaced a certificate on purpose would
     * have no move left but deleting the monitor and building it again.
     *
     * Deliberate rather than automatic, and that is the whole design. Re-pinning on
     * mismatch is indistinguishable from not pinning, so the decision has to be a
     * person saying "yes, that was me".
     */
    suspend fun repinCertificate(monitorId: String) {
        store.updateRuntime(monitorId) { it.copy(certPin = "") }
    }

    /** A monitor was deleted or disabled — it can no longer support a crash claim. */
    suspend fun forgetMonitor(monitorId: String) {
        val outcome = CheckerHealth.forget(_checkerHealth.value, monitorId)
        _checkerHealth.value = outcome.state
        if (outcome.action == CheckerHealth.Action.CLEAR) alerts.cancelCheckerHealth()
        store.updateCheckerStreak { streak ->
            if (monitorId !in streak.affectedMonitorIds) {
                streak
            } else {
                val remaining = streak.affectedMonitorIds - monitorId
                if (remaining.isEmpty()) {
                    CheckerStreak.Empty
                } else {
                    streak.copy(affectedMonitorIds = remaining)
                }
            }
        }
    }

    /**
     * Retires a crash claim whose newest supporting error has aged out.
     *
     * Called from the reconciliation tick rather than from a check, because the
     * situation this covers is precisely "no check is completing" — waiting for a
     * successful one to clear the notification could mean waiting forever.
     */
    private fun expireCheckerHealth() {
        val outcome = CheckerHealth.expireIfStale(_checkerHealth.value, nowMs())
        if (outcome.action == CheckerHealth.Action.NONE) return
        Log.i(TAG, "Checker crash claim expired; no supporting error in the last hour")
        _checkerHealth.value = outcome.state
        alerts.cancelCheckerHealth()
    }

    /**
     * Re-alerts every urgent outage whose repeat gap has elapsed, *without*
     * running a network check. Driven by [me.river.nightbell.data.work.NightbellMonitorService]
     * so the nag keeps its cadence even when a monitor's own interval is hours.
     *
     * @return how many monitors were re-alerted
     */
    suspend fun tickUrgent(): Int {
        val snapshot = store.currentSnapshot()
        val now = nowMs()
        val minute = minuteOfDay()
        // Reconciliation runs offline on purpose: it only ever *cancels*
        // notifications nothing can justify, which stays correct with no network
        // and is the one path that can clear an un-dismissable urgent orphan.
        reconcileNotifications(snapshot)
        expireCheckerHealth()

        // The re-nag, however, is an assertion that the outage is still
        // happening — and offline we have not checked, so we do not know. This is
        // the second spam source: with no checks running at all, a monitor that
        // was down when signal dropped would keep shouting every few minutes.
        // The standing notification is left alone; only the repeat pauses.
        if (!isOnline()) return 0

        // Same argument as offline, one step stronger: the user has said out loud
        // that they do not want to hear from this app right now. Reconciliation
        // above still runs, because it only ever cancels.
        if (snapshot.pause.isActive(now)) return 0

        var fired = 0
        for (monitor in snapshot.monitors) {
            val runtime = snapshot.runtimes[monitor.id] ?: continue
            if (!monitor.urgent || !runtime.urgentState.nagging) continue
            val policy = if (monitor.useGlobalAlerts) snapshot.settings.defaultAlert else monitor.alert
            val muted = runtime.mutedUntil > now
            val outcome = UrgentAlerts.evaluate(
                previous = runtime.urgentState,
                eligible = urgentEligible(monitor, policy, snapshot.settings, runtime, muted, minute),
                down = runtime.health == Health.DOWN,
                nowMs = now,
                repeatMinutes = monitor.urgentRepeatMinutes,
            )
            if (outcome.action == UrgentAlerts.Action.NONE) continue

            // A repeat asserts "this is *still* down", and a page is a much
            // stronger claim than a row in the shade. Re-check first when the last
            // verdict is older than the repeat gap: with strict mode off no checks
            // are running between ticks, so the old code could page three times off
            // one observation a quarter of an hour stale — and page about a monitor
            // that had already come back.
            if (outcome.action == UrgentAlerts.Action.REPEAT && staleEvidence(monitor, runtime, now)) {
                Log.i(TAG, "Re-checking ${monitor.displayName} before paging again")
                val fresh = runCatchingCancellable { run(monitor.id, force = true) }.getOrNull()
                if (fresh == null || fresh.ok) continue
                // `run` has already folded the failure through this same machine,
                // paged if it was due, and persisted. Nothing left to do here.
                fired++
                continue
            }
            val mutation = applyUrgent(
                monitor,
                outcome,
                lastResultFor(runtime),
                policy,
                runtime,
                snapshot.settings.urgentRespectsRingerMode,
            )
            // `applyUrgent` has already posted and vibrated. Losing this write
            // would drop the repeat cooldown it just earned, so the next tick — or
            // the next process to start the service — would nag again immediately
            // instead of after `urgentRepeatMinutes`.
            withContext(NonCancellable) {
                store.updateRuntime(monitor.id) {
                    it.withUrgentState(outcome.state).let(mutation)
                }
            }
            if (outcome.action == UrgentAlerts.Action.REPEAT) fired++
        }
        if (fired > 0) onStateChanged?.invoke()
        return fired
    }

    /**
     * Cancels every alert notification that no monitor can justify.
     *
     * Reconciles against what is *on screen* rather than against what state
     * says should be on screen, because the two can diverge in ways state
     * cannot see:
     *
     *  - a monitor was deleted while it had notifications standing, so no
     *    per-monitor loop will ever visit it again;
     *  - an older build left something behind;
     *  - overlapping checks posted from a stale read (fixed separately by the
     *    per-monitor lock, but this is the backstop).
     *
     * That matters most for urgent, which is `ongoing` — an orphan there is not
     * merely wrong, it is un-dismissable.
     *
     * A monitor may legitimately hold: the urgent id while nagging, the
     * degraded id while a latency alert stands, and the down id while it is
     * down *or* has just recovered — recovery reuses the down id, and eating
     * someone's all-clear a minute after it arrives would be its own bug.
     */
    private fun reconcileNotifications(snapshot: me.river.nightbell.data.NightbellSnapshot) {
        val active = alerts.activeAlertIds()
        if (active.isEmpty()) return
        val legitimate = mutableSetOf<Int>()
        for (monitor in snapshot.monitors) {
            val runtime = snapshot.runtimes[monitor.id] ?: continue
            if (monitor.urgent && runtime.urgentState.nagging) {
                legitimate += alerts.urgentIdOf(monitor.id)
            }
            if (runtime.degradedAlerting) {
                legitimate += alerts.degradedIdOf(monitor.id)
            }
            // The certificate notice outlives the check that posted it by days, so
            // the sweep has to know it is justified. Without this the next tick
            // would cancel a warning nobody has read yet — the same class of bug
            // the sweep was added to fix, in the opposite direction.
            if (runtime.certAlertedLevel > CertificateWatch.Level.OK.rank) {
                legitimate += alerts.certIdOf(monitor.id)
            }
            // `consecutiveSuccesses <= 1` keeps a just-posted recovery notice.
            if (runtime.alerting || runtime.health == Health.DOWN || runtime.consecutiveSuccesses <= 1) {
                legitimate += alerts.downIdOf(monitor.id)
            }
        }
        for (id in active) {
            if (id !in legitimate) alerts.cancelById(id)
        }
    }

    /**
     * Whether the newest verdict is too old to justify another page.
     *
     * "Too old" is one repeat gap: if the monitor is being checked at least as
     * often as it is paged, the evidence behind every page is fresh by
     * construction and this never fires.
     */
    private fun staleEvidence(monitor: Monitor, runtime: MonitorRuntime, nowMs: Long): Boolean {
        if (runtime.lastCheckedAt <= 0L) return true
        val gap = monitor.urgentRepeatMinutes.coerceAtLeast(1) * 60_000L
        return nowMs - runtime.lastCheckedAt >= gap
    }

    /** Re-hydrates enough of the last failure for the page to describe it. */
    fun pageEvidence(runtime: MonitorRuntime): CheckResult = lastResultFor(runtime)

    /** Re-hydrates enough of the last failure to re-post a useful notification. */
    private fun lastResultFor(runtime: MonitorRuntime): CheckResult = CheckResult(
        ok = false,
        latencyMs = runtime.lastLatencyMs,
        statusCode = runtime.lastCode,
        failureKind = FailureKind.UNKNOWN,
        message = runtime.lastMessage.ifBlank { "Still down" },
        detail = runtime.lastDetail,
        at = nowMs(),
    )

    /**
     * In-app acknowledgement. Same code path as the notification action, and
     * behind the same per-monitor lock as [run] so a check in flight cannot
     * resurrect the state this just cleared.
     */
    suspend fun acknowledgeUrgent(monitorId: String) {
        locks.getOrPut(monitorId) { Mutex() }.withLock {
            val snapshot = store.currentSnapshot()
            val monitor = snapshot.monitors.firstOrNull { it.id == monitorId } ?: return
            val runtime = snapshot.runtimes[monitorId] ?: return
            // Cancel first and unconditionally: if the notification is already
            // gone this is a no-op, and if the state was somehow out of step
            // the user still gets the silence they just asked for.
            alerts.cancelUrgent(monitor.id)
            val outcome = UrgentAlerts.acknowledge(runtime.urgentState)
            if (outcome.action == UrgentAlerts.Action.NONE) return
            // The notification is already gone. If this write is lost — the user
            // presses Back and takes `viewModelScope` with it — the store still
            // says `active = true, acknowledged = false`, and the service re-posts
            // the alarm the user just dismissed.
            withContext(NonCancellable) {
                store.updateRuntime(monitorId) { it.withUrgentState(outcome.state) }
            }
        }
        onStateChanged?.invoke()
    }

    /**
     * Runs every enabled monitor whose interval has elapsed. Returns how many ran.
     *
     * Returns 0 immediately while offline. `force` does not override that: the
     * flag exists so a human can say "check now", not "check even though it
     * cannot possibly succeed".
     */
    suspend fun runAllDue(force: Boolean = false): Int {
        if (!isOnline()) {
            Log.i(TAG, "Offline — check pass skipped")
            return 0
        }
        // Unlike the single-monitor path above, a whole pass is never something
        // the user asks for while paused: the banner replaces "Check all now"
        // with "Resume monitoring", and resuming lifts the pause before it calls
        // this. So a pass arriving here during a pause is the scheduler.
        if (store.currentSnapshot().pause.stopsChecks(nowMs())) {
            Log.i(TAG, "Paused: check pass skipped")
            return 0
        }
        // Once, before the loop: the whole pass shares one reading of the control.
        refreshReference()
        val snapshot = store.currentSnapshot()
        val now = nowMs()
        var ran = 0
        // And one witness for the pass, for the same reason. Nothing is probed
        // unless something in here fails without reaching anything.
        val witness = PassWitness()
        for (monitor in snapshot.monitors) {
            if (!monitor.enabled) continue
            if (!force && !isDue(monitor, snapshot.runtimes[monitor.id], now)) continue
            run(monitor.id, force, witness)
            ran++
        }
        return ran
    }

    /**
     * Whether a monitor's interval has elapsed.
     *
     * Public because the WorkManager path needs it: periodic work fires on
     * Android's schedule rather than on the monitor's, so the worker asks this
     * before checking. Without the gate, a monitor on a two-hour interval sharing
     * a wake-up with the repair sweep would be checked twice — visible on a real
     * device as three samples one second apart.
     *
     * Prefer the [Monitor]/[MonitorRuntime] overload when the caller already holds
     * a snapshot; this one re-reads the store, and decoding it is not free.
     */
    fun isDue(monitor: Monitor, runtime: MonitorRuntime?, nowMs: Long = nowMs()): Boolean {
        if (!monitor.enabled) return false
        return DueCheck.isDue(monitor.intervalMinutes, runtime?.lastCheckedAt ?: 0L, nowMs)
    }

    suspend fun isDue(monitorId: String, nowMs: Long = nowMs()): Boolean {
        val snapshot = store.currentSnapshot()
        val monitor = snapshot.monitors.firstOrNull { it.id == monitorId } ?: return false
        return isDue(monitor, snapshot.runtimes[monitorId], nowMs)
    }

    /**
     * Millis until the earliest of: the next monitor becoming due, or the next
     * urgent repeat. Used by the foreground service to sleep exactly as long as
     * it can rather than polling on a fixed tick.
     */
    suspend fun nextWakeDelayMs(): Long {
        val snapshot = store.currentSnapshot()
        val now = nowMs()

        // Nothing is due while checks are stopped, so the only thing left worth
        // waking for is the pause lifting. In practice that means the loop idles
        // at MAX_IDLE_MS instead of the MIN_TICK_MS floor it would otherwise sit
        // on: every monitor reads as overdue during a pause, and an overdue fleet
        // is what drags this function down to the floor. The ceiling still
        // applies, so this is a quieter loop rather than a sleeping one.
        if (snapshot.pause.stopsChecks(now)) {
            val remaining = snapshot.pause.remainingMs(now) ?: return MAX_IDLE_MS
            return remaining.coerceIn(MIN_TICK_MS, MAX_IDLE_MS)
        }

        var soonest = Long.MAX_VALUE
        for (monitor in snapshot.monitors) {
            val runtime = snapshot.runtimes[monitor.id]
            if (monitor.enabled) {
                val due = if (runtime == null || runtime.lastCheckedAt <= 0L) {
                    0L
                } else {
                    runtime.lastCheckedAt + monitor.intervalMinutes * 60_000L - DUE_SLACK_MS - now
                }
                soonest = minOf(soonest, due)
            }
            if (monitor.urgent && runtime != null) {
                UrgentAlerts.nextRepeatDelayMs(runtime.urgentState, now, monitor.urgentRepeatMinutes)
                    ?.let { soonest = minOf(soonest, it) }
            }
        }
        if (soonest == Long.MAX_VALUE) return MAX_IDLE_MS
        return soonest.coerceIn(MIN_TICK_MS, MAX_IDLE_MS)
    }

    /**
     * Pauses the whole fleet, and shuts up anything already shouting.
     *
     * Written the way [mute] is, and for the same reason: one uncancellable block
     * covering both the state and the notifications. Persisting the pause and then
     * losing the cancel would leave the phone paging through a pause the user can
     * see on screen, which is the worst of both.
     *
     * The alert *flags* are cleared across the fleet, because the notifications
     * are. `urgentActive` has to go or the foreground service keeps looping its
     * alarm with no monitor left willing to explain it. `alerting` and
     * `degradedAlerting` have to go for a subtler reason: the down track treats
     * them as "this monitor already has a notification up" and stays quiet
     * accordingly. Cancel the notification and leave the flag, and a monitor that
     * is still down when the pause lifts has nothing on screen and a track that
     * believes it already said so, so it never speaks again until the monitor
     * recovers and breaks a second time.
     *
     * The evidence is untouched. Health, failure streaks, samples and history all
     * stay exactly as they were, so what comes back after a pause is the outage
     * that was already running rather than a fresh one.
     */
    suspend fun pauseAll(state: PauseState) {
        withContext(NonCancellable) {
            store.setPause(state)
            store.updateAllRuntimes {
                it.copy(urgentActive = false, alerting = false, degradedAlerting = false)
            }
            alerts.cancelEverything()
        }
        onStateChanged?.invoke()
    }

    /**
     * Lifts a pause immediately and checks everything that is due.
     *
     * The check is the point. Coming back into signal and being told "up" by a
     * dashboard that has not looked in four hours is worse than being told
     * nothing, and this is the moment the user is actually holding the phone.
     */
    suspend fun resumeAll() {
        store.setPause(PauseState())
        onStateChanged?.invoke()
        runCatchingCancellable { runAllDue(force = true) }
    }

    suspend fun mute(monitorId: String, durationMs: Long) {
        locks.getOrPut(monitorId) { Mutex() }.withLock {
            // One write, not two. Muting used to persist `mutedUntil`, cancel,
            // then persist `urgentActive = false` — and a check landing between
            // those two writes could re-post the notification after the cancel.
            // Both halves or neither. A lost write leaves the user's "Mute 1h"
            // silently undone with the urgent alarm still nagging every minute for
            // the hour they asked to be left alone; a lost *cancel* leaves
            // notifications up that state says should be gone.
            withContext(NonCancellable) {
                store.updateRuntime(monitorId) {
                    // Muting is "stop shouting", not "I've triaged this": the urgent
                    // loop is suspended by the eligibility gate and resumes on unmute.
                    it.copy(mutedUntil = nowMs() + durationMs, urgentActive = false)
                }
                alerts.cancel(monitorId)
                alerts.cancelDegraded(monitorId)
                alerts.cancelUrgent(monitorId)
            }
        }
        onStateChanged?.invoke()
    }

    suspend fun unmute(monitorId: String) {
        store.updateRuntime(monitorId) { it.copy(mutedUntil = 0L) }
        onStateChanged?.invoke()
    }

    companion object {
        private const val TAG = "CheckEngine"

        /** A certificate's expiry does not move between two checks. Once a day is enough. */
        private const val CERT_PROBE_INTERVAL_MS = 24L * 60 * 60 * 1000
        private const val DUE_SLACK_MS = DueCheck.SLACK_MS

        /** Never wake more often than this, however tight the configured cadence. */
        const val MIN_TICK_MS = 15_000L

        /** Wake at least this often so a store edit made elsewhere is picked up. */
        const val MAX_IDLE_MS = 60_000L

        /** Shortest gap between two timings of the latency reference. */
        const val REFERENCE_MIN_INTERVAL_MS = 45_000L
    }
}
