package me.river.nightbell.domain

import kotlinx.serialization.Serializable

/**
 * The part of [CheckerHealth.State] that has to survive process death: the
 * evidence, and nothing else.
 *
 * ### Why any of this is persisted
 * `Application.onCreate` runs on **every** process creation, including the ones
 * WorkManager spawns for a background check. With background checks on, strict
 * mode off and one or two monitors — the ordinary configuration — the process is
 * created, runs one or two checks, and is reclaimed. A purely in-memory streak
 * therefore cannot reach [CheckerHealth.MIN_CONSECUTIVE_ERRORS] across wakes, so a
 * genuinely broken checker would report *nothing*, forever, while quietly failing
 * every check. The Settings card would say "Checks are running normally" — which is
 * the same class of untruth this release exists to remove, pointing the other way.
 *
 * ### What is deliberately *not* here
 * `raised`/`raisedAt` — the claim itself, and the notification behind it. Those
 * stay in memory, so "clear stale crash state after app restart" remains a
 * property of the types rather than a code path that could be forgotten: a fresh
 * process can never inherit a claim, only the evidence, and only while that
 * evidence is still current ([CheckerHealth.STREAK_WINDOW_MS]).
 *
 * Every field defaults, so a store written by 1.5.0 decodes into an empty streak
 * and a store written here still decodes on 1.5.0.
 */
@Serializable
data class CheckerStreak(
    val consecutiveErrors: Int = 0,
    val firstErrorAt: Long = 0L,
    val lastErrorAt: Long = 0L,
    val lastSignature: String = "",
    val lastDetail: String = "",
    /** A list rather than a set so the serialised form is stable and ordered. */
    val affectedMonitorIds: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = consecutiveErrors <= 0

    companion object {
        val Empty = CheckerStreak()
    }
}

/**
 * The health of the *checker*, kept strictly apart from the health of the things
 * it checks.
 *
 * ### Why this exists
 * Up to 1.5.0, `CheckEngine` wrapped every check in `catch (Throwable)` and
 * turned whatever it caught into a failed [CheckResult] whose message was
 * literally `"Checker crashed"`. That result then went down the ordinary
 * down-alert track: a HIGH-importance notification, with vibration, per the
 * user's alert policy.
 *
 * The problem is what else lands in `catch (Throwable)`:
 * [kotlin.coroutines.cancellation.CancellationException]. Coroutine cancellation
 * is not an error on Android, it is the platform working correctly — WorkManager
 * replacing a unique work request, a foreground service stopping, a
 * `viewModelScope` closing when a screen goes away, an execution window ending,
 * a process being reclaimed. Every one of those produced a "Checker crashed"
 * notification and a buzz. And because the very next thing `run()` did was a
 * suspending DataStore write — which throws immediately on a cancelled
 * coroutine — the cooldown and `alerting` flag were never persisted, so the
 * *next* cancellation fired a fresh full-volume alert instead of being
 * suppressed. That is the "it keeps vibrating" the bug report describes.
 *
 * ### What replaces it
 * Three states that mean different things and are allowed to interrupt the user
 * differently:
 *
 *  - **a monitor failing** — the site is down, the selector is gone, the status
 *    code is wrong. Owned by [AlertDecider]; alerts and vibrates as configured.
 *  - **[CheckerLimit] — the checker is limited by the system.** No connectivity,
 *    Doze deferring work, battery saver, background restriction, the user's own
 *    "background checks off". Real, worth *showing*, never worth a notification:
 *    it is not news and there is nothing to react to at 3am.
 *  - **[CheckerHealth] — the checker itself is broken.** An exception escaped
 *    checker code, repeatedly, right now. Both checkers classify their own
 *    failures into [FailureKind] and do not throw, so anything reaching the
 *    engine really is a bug in Nightbell. That is worth saying out loud — once
 *    verified.
 *
 * ### What "verified and current" means here
 * A single escaped exception raises nothing. The bar is
 * [MIN_CONSECUTIVE_ERRORS] internal errors in a row, with no check completing
 * normally in between, all inside [STREAK_WINDOW_MS]. Anything that proves the
 * checker still works — any classified verdict, pass or fail — clears the state
 * immediately.
 *
 * ### What survives a restart, and what cannot
 * The state is split down the middle, and the split is the whole design:
 *
 *  - the **claim** (`raised`/`raisedAt`, and the notification behind it) is held in
 *    memory by [me.river.nightbell.data.check.CheckEngine] and dies with the
 *    process. "Clear stale crash state after app restart" is therefore a property
 *    of the types, not a code path that could be forgotten.
 *  - the **evidence** (the error streak) is persisted as [CheckerStreak], because
 *    `Application.onCreate` runs on every WorkManager-spawned process and an
 *    in-memory streak could never reach [MIN_CONSECUTIVE_ERRORS] across background
 *    wakes — a genuinely broken checker would report nothing at all. It is revived
 *    only while still inside [STREAK_WINDOW_MS]; see [hydrate].
 *
 * So a restart can never inherit a claim, only recent evidence, and the claim has
 * to be re-earned against that evidence before it can interrupt anyone.
 *
 * Kept free of Android types so every transition is unit-testable.
 */
object CheckerHealth {

    /** How the checker is doing, worst last. */
    enum class Kind {
        /** Checks are completing and producing verdicts. */
        HEALTHY,

        /**
         * Errors have been seen but not enough, or not recently enough, to call
         * the checker broken. Nothing is shown for this.
         */
        SUSPECT,

        /** Verified, current, repeated failure inside checker code. */
        CRASHED,
    }

    /**
     * Everything the machine remembers between observations.
     *
     * @param consecutiveErrors internal errors with no classified verdict since
     * @param affectedMonitorIds which monitors were being checked when they hit
     * @param raised whether the user has been told
     */
    data class State(
        val consecutiveErrors: Int = 0,
        val affectedMonitorIds: Set<String> = emptySet(),
        val firstErrorAt: Long = 0L,
        val lastErrorAt: Long = 0L,
        /** Exception class name — the closest thing to "which bug is this". */
        val lastSignature: String = "",
        val lastDetail: String = "",
        val raised: Boolean = false,
        val raisedAt: Long = 0L,
    ) {
        val kind: Kind
            get() = when {
                raised -> Kind.CRASHED
                consecutiveErrors > 0 -> Kind.SUSPECT
                else -> Kind.HEALTHY
            }

        /** One line for the settings card. */
        val summary: String
            get() = when (kind) {
                Kind.HEALTHY -> "Checks are running normally"
                Kind.SUSPECT ->
                    "$consecutiveErrors internal error(s) since the last completed check"
                Kind.CRASHED ->
                    "$consecutiveErrors checks in a row failed inside Nightbell itself"
            }

        companion object {
            val Healthy = State()
        }
    }

    enum class Action {
        /** Say nothing and change nothing on screen. */
        NONE,

        /** First time we are telling the user the checker is broken. */
        RAISE,

        /** Still broken, and the repeat gap has elapsed. Never vibrates. */
        REPEAT,

        /** Take the checker-health notification down. */
        CLEAR,
    }

    data class Outcome(val action: Action, val state: State)

    /**
     * Internal errors required in a row before the checker is called broken.
     *
     * Three, not one. One escaped exception is a bad page, an OEM WebView having
     * a moment, an OOM under memory pressure — transient things that fix
     * themselves and must not wake anybody up.
     */
    const val MIN_CONSECUTIVE_ERRORS = 3

    /**
     * A streak only counts while it stays tight. Three errors spread over a day
     * are three unrelated hiccups, not a broken checker, and the third arriving
     * must not inherit the credibility of one from this morning.
     *
     * Three WorkManager periods, not one: the background floor is 15 minutes and
     * Doze can stretch that, so a window of exactly one period would break the
     * streak of a genuinely broken checker every time the platform batched it.
     */
    const val STREAK_WINDOW_MS = 45 * 60_000L

    /**
     * A raised crash whose newest evidence is older than this is no longer
     * *current*, and is withdrawn. Comfortably longer than [STREAK_WINDOW_MS] so
     * the claim outlives the streak that earned it — see [recordInternalError].
     */
    const val EVIDENCE_TTL_MS = 90 * 60_000L

    /**
     * Minimum gap before re-posting a crash the user has already been told about.
     *
     * Deliberately shorter than [EVIDENCE_TTL_MS], or it would be unreachable:
     * the claim would always expire before its own repeat came due. A repeat
     * re-posts the same notification id **silently** — it exists so a still-broken
     * checker's notification does not quietly rot after being swiped away, not to
     * nag.
     */
    const val REPEAT_GAP_MS = 30 * 60_000L

    /**
     * The message 1.5.0 and earlier wrote into [MonitorRuntime.lastMessage] when
     * a check was cancelled.
     *
     * Kept as a constant because those strings are *persisted*, on real phones,
     * right now — attached to a `DOWN` health and an `alerting` flag that no
     * check ever justified. Left alone they keep the fake outage on the card,
     * keep the down notification alive through the reconciliation sweep, and get
     * re-hydrated verbatim into urgent re-nags. `NightbellStore.migrate` scrubs them
     * on read; this is the needle it looks for.
     */
    const val LEGACY_CRASH_MESSAGE = "Checker crashed"

    /**
     * An exception escaped checker code while checking [monitorId].
     *
     * Callers must have already ruled out cancellation — see
     * [recordCancellation] and [runCatchingCancellable].
     */
    fun recordInternalError(
        previous: State,
        monitorId: String,
        signature: String,
        detail: String,
        nowMs: Long,
    ): Outcome {
        // A gap wider than the window means the earlier errors have expired.
        // Start the count again from this one rather than letting a stale streak
        // be topped up into a crash claim it did not earn.
        val continues = previous.consecutiveErrors > 0 &&
            nowMs - previous.lastErrorAt <= STREAK_WINDOW_MS

        // The *claim* outlives the *streak*, deliberately. Restarting the count is
        // a statement about counting; it must not silently withdraw a notification
        // the user is still looking at and then re-raise — and re-vibrate — three
        // errors later. Only a completed check, a reset, or expiry withdraws a
        // claim.
        val stillRaised = previous.raised && nowMs - previous.lastErrorAt <= EVIDENCE_TTL_MS

        val next = State(
            consecutiveErrors = if (continues) previous.consecutiveErrors + 1 else 1,
            affectedMonitorIds = if (continues || stillRaised) {
                previous.affectedMonitorIds + monitorId
            } else {
                setOf(monitorId)
            },
            firstErrorAt = if (continues) previous.firstErrorAt else nowMs,
            lastErrorAt = nowMs,
            lastSignature = signature,
            lastDetail = detail,
            raised = stillRaised,
            raisedAt = if (stillRaised) previous.raisedAt else 0L,
        )

        if (next.raised) {
            // Already told them. Keep the notification from rotting, silently.
            return if (nowMs - next.raisedAt >= REPEAT_GAP_MS) {
                Outcome(Action.REPEAT, next.copy(raisedAt = nowMs))
            } else {
                Outcome(Action.NONE, next)
            }
        }
        // The claim was raised and has just been withdrawn for age
        // (`stillRaised` went false). That is the one transition where `raised`
        // goes true → false, so it has to emit CLEAR: without it the state says
        // "no claim" while the notification saying "Nightbell can't complete its
        // checks" is still on screen, and nothing left in the process would ever
        // take it down — `expireIfStale` only runs from the strict-mode service
        // loop, and `recordVerdict` skips the cancel because `raised` is already
        // false. Stranding a withdrawn claim is the exact failure this release
        // exists to remove.
        if (previous.raised) {
            return Outcome(Action.CLEAR, next)
        }
        if (next.consecutiveErrors < MIN_CONSECUTIVE_ERRORS) {
            return Outcome(Action.NONE, next)
        }
        return Outcome(Action.RAISE, next.copy(raised = true, raisedAt = nowMs))
    }

    /**
     * A check produced a real verdict — passing *or* failing.
     *
     * A classified failure is just as much proof that the checker works as a
     * pass is: it reached the network, got an answer it understood, and had an
     * opinion about it. Both clear the slate.
     */
    fun recordVerdict(previous: State, nowMs: Long): Outcome = clear(previous)

    /**
     * The check was cancelled.
     *
     * Exists to be called, and to be the place this is written down: cancellation
     * is *not evidence of anything*. It does not add to the streak, it does not
     * clear it, and it never notifies. This is the bug fix, expressed as a
     * function.
     */
    @Suppress("UNUSED_PARAMETER")
    fun recordCancellation(previous: State, nowMs: Long = 0L): Outcome =
        Outcome(Action.NONE, previous)

    /**
     * A monitor was deleted, disabled, or otherwise stopped being checked.
     *
     * If it was the only thing the crash claim rested on, the claim has nothing
     * left to stand on and comes down.
     */
    fun forget(previous: State, monitorId: String): Outcome {
        if (monitorId !in previous.affectedMonitorIds) return Outcome(Action.NONE, previous)
        val remaining = previous.affectedMonitorIds - monitorId
        if (remaining.isEmpty()) return clear(previous)
        return Outcome(Action.NONE, previous.copy(affectedMonitorIds = remaining))
    }

    /**
     * Wipe the slate: process start, background checks switched off, the
     * scheduler re-armed, the strict service replaced.
     *
     * All of those change *how* checks run, which invalidates a claim about how
     * they were failing. Idempotent, and safe to over-call — the worst case is a
     * genuinely broken checker having to prove itself over another three checks.
     */
    fun reset(previous: State): Outcome = clear(previous)

    /**
     * Whether a raised crash still has current evidence behind it.
     *
     * Used to stop re-asserting a claim whose last supporting error has aged
     * out, without waiting for a successful check that may never come.
     */
    fun isCurrent(state: State, nowMs: Long): Boolean =
        state.raised && nowMs - state.lastErrorAt <= EVIDENCE_TTL_MS

    /** Drops a claim whose evidence has expired. */
    fun expireIfStale(state: State, nowMs: Long): Outcome =
        if (state.raised && !isCurrent(state, nowMs)) clear(state) else Outcome(Action.NONE, state)

    /** The evidence half of a state, for persisting. */
    fun toStreak(state: State): CheckerStreak =
        if (state.consecutiveErrors <= 0) {
            CheckerStreak.Empty
        } else {
            CheckerStreak(
                consecutiveErrors = state.consecutiveErrors,
                firstErrorAt = state.firstErrorAt,
                lastErrorAt = state.lastErrorAt,
                lastSignature = state.lastSignature,
                lastDetail = state.lastDetail,
                affectedMonitorIds = state.affectedMonitorIds.sorted(),
            )
        }

    /**
     * Rebuilds a working state from persisted evidence plus this process's claim.
     *
     * Evidence older than [STREAK_WINDOW_MS] is dropped rather than carried: it
     * could no longer have contributed to a streak anyway, and reviving it would be
     * exactly the "stale crash state after restart" this design refuses.
     *
     * @param inMemory the claim held by *this* process. Never taken from disk.
     */
    fun hydrate(streak: CheckerStreak, inMemory: State, nowMs: Long): State {
        val fresh = !streak.isEmpty && nowMs - streak.lastErrorAt <= STREAK_WINDOW_MS
        if (!fresh) return State(raised = inMemory.raised, raisedAt = inMemory.raisedAt)
        // The in-memory streak wins when it is further along: it is this process's
        // own first-hand count, and it has already been written back.
        if (inMemory.consecutiveErrors >= streak.consecutiveErrors) return inMemory
        return State(
            consecutiveErrors = streak.consecutiveErrors,
            affectedMonitorIds = streak.affectedMonitorIds.toSet(),
            firstErrorAt = streak.firstErrorAt,
            lastErrorAt = streak.lastErrorAt,
            lastSignature = streak.lastSignature,
            lastDetail = streak.lastDetail,
            raised = inMemory.raised,
            raisedAt = inMemory.raisedAt,
        )
    }

    private fun clear(previous: State): Outcome =
        if (previous.raised) {
            Outcome(Action.CLEAR, State.Healthy)
        } else if (previous == State.Healthy) {
            Outcome(Action.NONE, State.Healthy)
        } else {
            // Never raised, so nothing on screen to cancel — but the streak
            // still has to go, or the next error would be counted as the third.
            Outcome(Action.NONE, State.Healthy)
        }
}

/**
 * Why background checks may not be keeping their cadence.
 *
 * This is the middle category the bug report was missing: real, worth showing in
 * the app, and never worth a notification. Android delaying work is not a fault,
 * and telling somebody about it at 3am helps nobody.
 */
enum class CheckerLimit {
    NONE,

    /** The user turned background checks off. Not a fault. */
    BACKGROUND_CHECKS_OFF,

    NO_ENABLED_MONITORS,

    /** No connectivity. Checks are paused on purpose — see `NetworkMonitor`. */
    OFFLINE,

    /** "Wi-Fi only" is on and the device is on metered data. */
    METERED_BLOCKED,

    /** The user (or the OEM) restricted this app's background execution. */
    BACKGROUND_RESTRICTED,

    /** Battery saver is on; Android defers deferrable work aggressively. */
    BATTERY_SAVER,

    /** Nothing is switched off, and yet checks are running late anyway. */
    DELAYED_BY_ANDROID,
    ;

    val isLimited: Boolean get() = this != NONE

    val headline: String
        get() = when (this) {
            NONE -> "Checks are on schedule"
            BACKGROUND_CHECKS_OFF -> "Background checks are off"
            NO_ENABLED_MONITORS -> "No monitor is enabled"
            OFFLINE -> "No connection"
            METERED_BLOCKED -> "Waiting for Wi-Fi"
            BACKGROUND_RESTRICTED -> "Background use is restricted"
            BATTERY_SAVER -> "Battery saver is on"
            DELAYED_BY_ANDROID -> "Android is delaying checks"
        }

    val hint: String
        get() = when (this) {
            NONE -> "Every enabled monitor has been checked within its interval."
            BACKGROUND_CHECKS_OFF ->
                "Nightbell only checks while you have it open. Turn background checks " +
                    "back on above."
            NO_ENABLED_MONITORS -> "Nothing to check. Enable a monitor to start."
            OFFLINE ->
                "Losing signal is not an outage, so Nightbell stops checking instead " +
                    "of reporting one. Checks resume by themselves."
            METERED_BLOCKED ->
                "Turn off “Wi-Fi only” to keep checking on mobile data."
            BACKGROUND_RESTRICTED ->
                "Android will not run Nightbell in the background at all. Allow " +
                    "background activity in the app's battery settings."
            BATTERY_SAVER ->
                "Deferrable work is batched hard in battery saver. Strict mode " +
                    "still keeps its cadence; ordinary background checks will not."
            DELAYED_BY_ANDROID ->
                "Doze is batching Nightbell's work. Exempting Nightbell from battery " +
                    "optimisation helps; strict mode is the only guarantee."
        }
}

/**
 * One enabled monitor's cadence, and how long ago it was actually checked.
 *
 * A pair, per monitor, rather than two fleet-wide aggregates. The aggregate form
 * compared the *loosest* monitor's check age against the *tightest* monitor's
 * interval, so a perfectly healthy fleet of one 15-minute and one 2-hour monitor
 * was diagnosed "Android is delaying checks" for most of every two hours.
 * Lateness is a per-monitor question and is now asked per monitor.
 */
data class MonitorCadence(
    val intervalMinutes: Int,
    /** Age of the last completed check. Monitors never checked are simply omitted. */
    val ageMs: Long,
)

/** The Android-side facts a limit verdict is derived from. Filled in by `SystemLimits`. */
data class CheckerFacts(
    val backgroundChecksEnabled: Boolean = true,
    val enabledMonitors: Int = 1,
    val online: Boolean = true,
    val unmeteredOnly: Boolean = false,
    val onUnmeteredNetwork: Boolean = true,
    val backgroundRestricted: Boolean = false,
    val powerSaveMode: Boolean = false,
    val ignoringBatteryOptimizations: Boolean = true,
    val strictMode: Boolean = false,
    /**
     * One entry per enabled monitor that has been checked at least once. A monitor
     * that has never been checked is not "late", it is new, so it is left out.
     */
    val cadences: List<MonitorCadence> = emptyList(),
)

/** Turns [CheckerFacts] into one [CheckerLimit]. Pure, so the precedence is testable. */
object CheckerLimits {

    /**
     * How far past its interval a monitor has to be before Android is the
     * likelier explanation than timing.
     *
     * Three intervals, floored at [DELAY_FLOOR_MS]. The floor matters because
     * WorkManager's periodic minimum is 15 minutes: a monitor asking for one
     * minute is *always* three intervals late in the background, and calling
     * that "Android is delaying checks" would be true but useless — it is the
     * documented floor, not a fault. See the README.
     */
    const val DELAY_TOLERANCE_FACTOR = 3

    /** Below this, lateness is ordinary batching rather than something to show. */
    const val DELAY_FLOOR_MS = 50 * 60_000L

    fun diagnose(facts: CheckerFacts): CheckerLimit {
        // `&& !strictMode`, because the two settings are independent: the strict
        // toggle is not gated on background checks, and `NightbellMonitorService`
        // never consults `backgroundChecksEnabled`. With background checks off and
        // strict mode on, every monitor is being checked on its exact interval —
        // and this card used to announce "Nightbell only checks while you have it
        // open", which is the opposite of what was happening.
        if (!facts.backgroundChecksEnabled && !facts.strictMode) {
            return CheckerLimit.BACKGROUND_CHECKS_OFF
        }
        if (facts.enabledMonitors <= 0) return CheckerLimit.NO_ENABLED_MONITORS
        if (!facts.online) return CheckerLimit.OFFLINE
        if (facts.unmeteredOnly && !facts.onUnmeteredNetwork) return CheckerLimit.METERED_BLOCKED
        if (facts.backgroundRestricted) return CheckerLimit.BACKGROUND_RESTRICTED
        // Strict mode runs a foreground service, which battery saver does not
        // defer — so saying "battery saver is delaying you" would be wrong.
        if (facts.powerSaveMode && !facts.strictMode) return CheckerLimit.BATTERY_SAVER
        if (isRunningLate(facts)) return CheckerLimit.DELAYED_BY_ANDROID
        return CheckerLimit.NONE
    }

    /** True when *any* monitor is overdue against **its own** interval. */
    fun isRunningLate(facts: CheckerFacts): Boolean = facts.cadences.any(::isLate)

    fun isLate(cadence: MonitorCadence): Boolean {
        if (cadence.ageMs <= 0L) return false
        val interval = cadence.intervalMinutes.coerceAtLeast(1) * 60_000L
        val tolerance = maxOf(interval * DELAY_TOLERANCE_FACTOR, DELAY_FLOOR_MS)
        return cadence.ageMs > tolerance
    }
}
