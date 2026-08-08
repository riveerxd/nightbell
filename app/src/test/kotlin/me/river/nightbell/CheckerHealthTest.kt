package me.river.nightbell

import me.river.nightbell.domain.CheckerHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The checker-health state machine — the fix for the bug this release exists for.
 *
 * Reported from real use: six monitors, six simultaneous `URGENT · … is down /
 * Checker crashed` notifications, `ongoing` and DND-bypassing, timestamped the
 * same minute the foreground service was reporting "All 6 operational" — and the
 * monitors' own histories showed nothing but successful checks. Nothing had
 * crashed and nothing was down. Every one of those alerts came from a coroutine
 * cancellation being caught by `catch (Throwable)` and dressed up as a failed
 * check.
 *
 * So the first and most important thing asserted here is a negative:
 * cancellation produces nothing.
 */
class CheckerHealthTest {

    private val now = 1_700_000_000_000L
    private val minute = 60_000L

    private fun error(
        previous: CheckerHealth.State = CheckerHealth.State.Healthy,
        monitorId: String = "m1",
        signature: String = "IllegalStateException",
        at: Long = now,
    ) = CheckerHealth.recordInternalError(previous, monitorId, signature, "boom", at)

    /** Walks [count] internal errors, one minute apart, and hands back the last outcome. */
    private fun streak(count: Int, monitorId: String = "m1"): CheckerHealth.Outcome {
        var outcome = error(monitorId = monitorId)
        repeat(count - 1) { index ->
            outcome = error(
                previous = outcome.state,
                monitorId = monitorId,
                at = now + (index + 1) * minute,
            )
        }
        return outcome
    }

    // ---- cancellation is not evidence of anything ---------------------------

    @Test
    fun `cancellation never raises, never counts, never changes anything`() {
        val outcome = CheckerHealth.recordCancellation(CheckerHealth.State.Healthy, now)
        assertEquals(CheckerHealth.Action.NONE, outcome.action)
        assertEquals(CheckerHealth.State.Healthy, outcome.state)
        assertEquals(CheckerHealth.Kind.HEALTHY, outcome.state.kind)
    }

    @Test
    fun `a thousand cancellations still raise nothing`() {
        // The regression, stated as bluntly as possible. WorkManager replacing
        // unique work, a foreground service stopping and a viewModelScope closing
        // are ordinary events that happen constantly; the old code turned every
        // single one into a full-volume alert.
        var state = CheckerHealth.State.Healthy
        repeat(1_000) { index ->
            val outcome = CheckerHealth.recordCancellation(state, now + index * minute)
            assertEquals(CheckerHealth.Action.NONE, outcome.action)
            state = outcome.state
        }
        assertEquals(CheckerHealth.State.Healthy, state)
    }

    @Test
    fun `cancellation does not disturb a streak in progress`() {
        val two = streak(2)
        assertEquals(2, two.state.consecutiveErrors)
        val cancelled = CheckerHealth.recordCancellation(two.state, now + 3 * minute)
        // Neither advances the streak towards a crash claim nor clears it.
        assertEquals(2, cancelled.state.consecutiveErrors)
        assertEquals(CheckerHealth.Action.NONE, cancelled.action)
    }

    // ---- the bar for calling the checker broken -----------------------------

    @Test
    fun `one internal error raises nothing`() {
        val outcome = error()
        assertEquals(CheckerHealth.Action.NONE, outcome.action)
        assertEquals(1, outcome.state.consecutiveErrors)
        assertEquals(CheckerHealth.Kind.SUSPECT, outcome.state.kind)
        assertFalse(outcome.state.raised)
    }

    @Test
    fun `two internal errors still raise nothing`() {
        assertEquals(CheckerHealth.Action.NONE, streak(2).action)
    }

    @Test
    fun `the third consecutive internal error raises once`() {
        val third = streak(CheckerHealth.MIN_CONSECUTIVE_ERRORS)
        assertEquals(CheckerHealth.Action.RAISE, third.action)
        assertTrue(third.state.raised)
        assertEquals(CheckerHealth.Kind.CRASHED, third.state.kind)
        assertEquals(now + 2 * minute, third.state.raisedAt)
    }

    @Test
    fun `a raised crash does not re-raise on every further error`() {
        val raised = streak(3)
        val fourth = error(previous = raised.state, at = now + 3 * minute)
        assertEquals(CheckerHealth.Action.NONE, fourth.action)
        assertTrue(fourth.state.raised)
        assertEquals(4, fourth.state.consecutiveErrors)
    }

    @Test
    fun `a still-broken checker is re-stated on the gap and never raised twice`() {
        // Walked as it really happens: a broken checker erroring on the 15-minute
        // WorkManager floor for two hours. It should keep its one notification
        // refreshed at the repeat cadence and never RAISE again — a second RAISE
        // is the only path that vibrates.
        val period = 15 * minute
        var state = streak(3).state
        var at = state.lastErrorAt
        val repeats = mutableListOf<Long>()

        repeat(8) {
            at += period
            val outcome = error(previous = state, at = at)
            if (outcome.action == CheckerHealth.Action.REPEAT) repeats += at
            assertNotEquals(
                "a raised claim must never raise a second time",
                CheckerHealth.Action.RAISE,
                outcome.action,
            )
            assertTrue("the claim must survive the whole run", outcome.state.raised)
            state = outcome.state
        }

        assertTrue("a two-hour outage should refresh the notice at least twice", repeats.size >= 2)
        val gaps = repeats.zipWithNext { a, b -> b - a }
        gaps.forEach { gap ->
            assertTrue("repeats must be at least a gap apart, saw ${gap}ms", gap >= CheckerHealth.REPEAT_GAP_MS)
        }
    }

    @Test
    fun `the repeat gap is reachable before the evidence expires`() {
        // Otherwise REPEAT is dead code: the claim would always be withdrawn by
        // `expireIfStale` before its own repeat came due.
        assertTrue(
            "REPEAT_GAP_MS must be shorter than EVIDENCE_TTL_MS",
            CheckerHealth.REPEAT_GAP_MS < CheckerHealth.EVIDENCE_TTL_MS,
        )
        // And the streak has to be able to outlive Doze on the platform floor.
        assertTrue(
            "STREAK_WINDOW_MS must span more than one WorkManager period",
            CheckerHealth.STREAK_WINDOW_MS > 15 * minute,
        )
        assertTrue(CheckerHealth.STREAK_WINDOW_MS < CheckerHealth.EVIDENCE_TTL_MS)
    }

    @Test
    fun `a Doze-broken streak does not withdraw and re-vibrate a live claim`() {
        // The regression this design exists for. If restarting the streak also
        // dropped `raised`, a checker broken across a Doze window would re-raise
        // from scratch — and re-vibrate — every few errors.
        val raised = streak(3)
        val late = error(
            previous = raised.state,
            at = raised.state.lastErrorAt + CheckerHealth.STREAK_WINDOW_MS + minute,
        )
        assertEquals("the streak restarts", 1, late.state.consecutiveErrors)
        assertTrue("but the claim is kept", late.state.raised)
        assertNotEquals(
            "and it is refreshed, not re-raised",
            CheckerHealth.Action.RAISE,
            late.action,
        )

        // Nor do further errors from a restarted count produce a second RAISE, even
        // once the count passes the threshold again.
        var state = late.state
        repeat(4) { index ->
            val outcome = error(previous = state, at = late.state.lastErrorAt + (index + 1) * minute)
            assertNotEquals(CheckerHealth.Action.RAISE, outcome.action)
            assertTrue(outcome.state.raised)
            state = outcome.state
        }
    }

    @Test
    fun `errors spread wider than the window never accumulate into a crash`() {
        // Three unrelated hiccups across a day are not a broken checker, and the
        // third must not inherit the credibility of one from the morning.
        var outcome = error()
        repeat(10) { index ->
            outcome = error(
                previous = outcome.state,
                at = now + (index + 1) * (CheckerHealth.STREAK_WINDOW_MS + minute),
            )
            assertEquals(CheckerHealth.Action.NONE, outcome.action)
            assertEquals("streak must restart from 1", 1, outcome.state.consecutiveErrors)
        }
        assertFalse(outcome.state.raised)
    }

    @Test
    fun `a streak that resumes just inside the window keeps counting`() {
        val first = error()
        val second = error(previous = first.state, at = now + CheckerHealth.STREAK_WINDOW_MS)
        assertEquals(2, second.state.consecutiveErrors)
        val third = error(previous = second.state, at = now + CheckerHealth.STREAK_WINDOW_MS + minute)
        assertEquals(CheckerHealth.Action.RAISE, third.action)
    }

    // ---- clearing ----------------------------------------------------------

    @Test
    fun `any completed check clears a raised crash and cancels the notification`() {
        val raised = streak(3)
        val cleared = CheckerHealth.recordVerdict(raised.state, now + 5 * minute)
        assertEquals(CheckerHealth.Action.CLEAR, cleared.action)
        assertEquals(CheckerHealth.State.Healthy, cleared.state)
        assertEquals(CheckerHealth.Kind.HEALTHY, cleared.state.kind)
    }

    @Test
    fun `a completed check clears a streak that had not yet raised`() {
        val two = streak(2)
        val cleared = CheckerHealth.recordVerdict(two.state, now + 3 * minute)
        // Nothing on screen, so nothing to cancel — but the count has to go, or
        // the next error would be counted as the third.
        assertEquals(CheckerHealth.Action.NONE, cleared.action)
        assertEquals(0, cleared.state.consecutiveErrors)

        val next = error(previous = cleared.state, at = now + 4 * minute)
        assertEquals(CheckerHealth.Action.NONE, next.action)
        assertEquals(1, next.state.consecutiveErrors)
    }

    @Test
    fun `a failing check clears just as well as a passing one`() {
        // A classified failure proves the checker reached the network, understood
        // the answer and had an opinion about it. That is a working checker.
        val raised = streak(3)
        assertEquals(
            CheckerHealth.Action.CLEAR,
            CheckerHealth.recordVerdict(raised.state, now + minute).action,
        )
    }

    @Test
    fun `reset clears everything and is idempotent`() {
        val raised = streak(3)
        val first = CheckerHealth.reset(raised.state)
        assertEquals(CheckerHealth.Action.CLEAR, first.action)
        assertEquals(CheckerHealth.State.Healthy, first.state)

        val again = CheckerHealth.reset(first.state)
        assertEquals(CheckerHealth.Action.NONE, again.action)
        assertEquals(CheckerHealth.State.Healthy, again.state)
    }

    @Test
    fun `a fresh process starts healthy`() {
        // The state is deliberately not persisted, so "clear stale crash state
        // after app restart" is a property of the type rather than of any code
        // path that could be forgotten.
        assertEquals(CheckerHealth.Kind.HEALTHY, CheckerHealth.State().kind)
        assertFalse(CheckerHealth.State().raised)
        assertEquals(0, CheckerHealth.State().consecutiveErrors)
    }

    // ---- monitors going away ------------------------------------------------

    @Test
    fun `deleting the only implicated monitor withdraws the crash claim`() {
        val raised = streak(3, monitorId = "gone")
        assertEquals(setOf("gone"), raised.state.affectedMonitorIds)

        val forgotten = CheckerHealth.forget(raised.state, "gone")
        assertEquals(CheckerHealth.Action.CLEAR, forgotten.action)
        assertEquals(CheckerHealth.State.Healthy, forgotten.state)
    }

    @Test
    fun `deleting one of several implicated monitors keeps the claim`() {
        var outcome = error(monitorId = "a")
        outcome = error(previous = outcome.state, monitorId = "b", at = now + minute)
        outcome = error(previous = outcome.state, monitorId = "c", at = now + 2 * minute)
        assertEquals(CheckerHealth.Action.RAISE, outcome.action)

        val forgotten = CheckerHealth.forget(outcome.state, "b")
        assertEquals(CheckerHealth.Action.NONE, forgotten.action)
        assertTrue(forgotten.state.raised)
        assertEquals(setOf("a", "c"), forgotten.state.affectedMonitorIds)
    }

    @Test
    fun `forgetting an unrelated monitor changes nothing`() {
        val raised = streak(3, monitorId = "a")
        val forgotten = CheckerHealth.forget(raised.state, "somebody-else")
        assertEquals(CheckerHealth.Action.NONE, forgotten.action)
        assertEquals(raised.state, forgotten.state)
    }

    // ---- staying current ----------------------------------------------------

    @Test
    fun `a crash claim expires when its newest evidence ages out`() {
        val raised = streak(3)
        assertTrue(CheckerHealth.isCurrent(raised.state, raised.state.lastErrorAt))
        assertTrue(
            CheckerHealth.isCurrent(
                raised.state,
                raised.state.lastErrorAt + CheckerHealth.EVIDENCE_TTL_MS,
            ),
        )
        assertFalse(
            CheckerHealth.isCurrent(
                raised.state,
                raised.state.lastErrorAt + CheckerHealth.EVIDENCE_TTL_MS + 1,
            ),
        )

        val expired = CheckerHealth.expireIfStale(
            raised.state,
            raised.state.lastErrorAt + CheckerHealth.EVIDENCE_TTL_MS + 1,
        )
        assertEquals(CheckerHealth.Action.CLEAR, expired.action)
        assertEquals(CheckerHealth.State.Healthy, expired.state)
    }

    @Test
    fun `expiry leaves a current claim alone`() {
        val raised = streak(3)
        val kept = CheckerHealth.expireIfStale(raised.state, raised.state.lastErrorAt + minute)
        assertEquals(CheckerHealth.Action.NONE, kept.action)
        assertEquals(raised.state, kept.state)
    }

    @Test
    fun `nothing was ever raised so nothing can expire`() {
        val two = streak(2)
        val outcome = CheckerHealth.expireIfStale(two.state, now + 10 * 60 * minute)
        assertEquals(CheckerHealth.Action.NONE, outcome.action)
        assertEquals(two.state, outcome.state)
    }

    // ---- withdrawing a claim always takes its notification with it ----------

    @Test
    fun `an error that ages the claim out withdraws it explicitly`() {
        // The one transition where `raised` goes true -> false. It must emit CLEAR:
        // otherwise state says "no claim" while "Nightbell can't complete its checks" is
        // still on screen, and nothing left in the process would take it down —
        // `expireIfStale` only runs from the strict-mode service loop, and
        // `recordVerdict` skips the cancel because `raised` is already false.
        val raised = streak(3)
        val late = error(
            previous = raised.state,
            at = raised.state.lastErrorAt + CheckerHealth.EVIDENCE_TTL_MS + minute,
        )
        assertEquals(CheckerHealth.Action.CLEAR, late.action)
        assertFalse(late.state.raised)
        assertEquals("and the count starts over", 1, late.state.consecutiveErrors)
    }

    @Test
    fun `every path that drops a raised claim emits CLEAR`() {
        // Enumerated deliberately: a withdrawal that does not cancel its
        // notification is the failure mode this whole release is about.
        val raised = streak(3).state
        assertTrue(raised.raised)
        listOf<Pair<String, CheckerHealth.Outcome>>(
            "verdict" to CheckerHealth.recordVerdict(raised, now + minute),
            "reset" to CheckerHealth.reset(raised),
            "forget-last-monitor" to CheckerHealth.forget(raised, "m1"),
            "expiry" to CheckerHealth.expireIfStale(
                raised,
                raised.lastErrorAt + CheckerHealth.EVIDENCE_TTL_MS + 1,
            ),
            "aged-out error" to error(
                previous = raised,
                at = raised.lastErrorAt + CheckerHealth.EVIDENCE_TTL_MS + 1,
            ),
        ).forEach { (name, outcome) ->
            assertEquals(
                "$name dropped the claim without cancelling the notification",
                CheckerHealth.Action.CLEAR,
                outcome.action,
            )
            assertFalse("$name left the claim raised", outcome.state.raised)
        }
    }

    // ---- surviving a background process wake --------------------------------

    @Test
    fun `the streak round-trips through the persisted form`() {
        val two = streak(2).state
        val restored = CheckerHealth.hydrate(
            CheckerHealth.toStreak(two),
            CheckerHealth.State.Healthy,
            two.lastErrorAt,
        )
        assertEquals(two.consecutiveErrors, restored.consecutiveErrors)
        assertEquals(two.firstErrorAt, restored.firstErrorAt)
        assertEquals(two.lastErrorAt, restored.lastErrorAt)
        assertEquals(two.lastSignature, restored.lastSignature)
        assertEquals(two.affectedMonitorIds, restored.affectedMonitorIds)
    }

    @Test
    fun `a raise can be earned across three separate background processes`() {
        // `Application.onCreate` runs on every WorkManager-spawned process, so with
        // one monitor and background checks on, each wake contributes exactly one
        // error. A purely in-memory streak could never reach the bar and a genuinely
        // broken checker would report nothing at all.
        var streakOnDisk = CheckerHealth.toStreak(CheckerHealth.State.Healthy)
        var lastAction = CheckerHealth.Action.NONE
        var at = now

        repeat(3) {
            // Fresh process: no claim, no in-memory streak. Only disk carries over.
            val hydrated = CheckerHealth.hydrate(streakOnDisk, CheckerHealth.State.Healthy, at)
            val outcome = CheckerHealth.recordInternalError(hydrated, "only-one", "Boom", "boom", at)
            streakOnDisk = CheckerHealth.toStreak(outcome.state)
            lastAction = outcome.action
            at += 15 * minute // the WorkManager floor
        }

        assertEquals(CheckerHealth.Action.RAISE, lastAction)
        assertEquals(3, streakOnDisk.consecutiveErrors)
    }

    @Test
    fun `a claim is never restored from disk, only the evidence`() {
        // "Clear stale crash state after app restart" has to hold even though the
        // evidence is persisted: a fresh process must not inherit a notification
        // claim it cannot see.
        val raised = streak(3).state
        assertTrue(raised.raised)
        val onDisk = CheckerHealth.toStreak(raised)
        val newProcess = CheckerHealth.hydrate(onDisk, CheckerHealth.State.Healthy, raised.lastErrorAt)
        assertFalse("a restart must not inherit the claim", newProcess.raised)
        assertEquals(0L, newProcess.raisedAt)
        assertEquals("but it keeps the evidence", 3, newProcess.consecutiveErrors)
    }

    @Test
    fun `evidence older than the streak window is not revived`() {
        val old = CheckerHealth.toStreak(streak(2).state)
        val hydrated = CheckerHealth.hydrate(
            old,
            CheckerHealth.State.Healthy,
            old.lastErrorAt + CheckerHealth.STREAK_WINDOW_MS + 1,
        )
        assertEquals(CheckerHealth.State.Healthy, hydrated)
    }

    @Test
    fun `hydrate keeps this process's own count when it is further along`() {
        val onDisk = CheckerHealth.toStreak(streak(1).state)
        val inMemory = streak(2).state
        val hydrated = CheckerHealth.hydrate(onDisk, inMemory, inMemory.lastErrorAt)
        assertEquals(2, hydrated.consecutiveErrors)
    }

    @Test
    fun `an empty streak hydrates to healthy while preserving a live claim`() {
        val liveClaim = CheckerHealth.State(raised = true, raisedAt = now)
        val hydrated = CheckerHealth.hydrate(
            CheckerHealth.toStreak(CheckerHealth.State.Healthy),
            liveClaim,
            now,
        )
        assertTrue(hydrated.raised)
        assertEquals(now, hydrated.raisedAt)
        assertEquals(0, hydrated.consecutiveErrors)
    }

    // ---- what the user is shown ---------------------------------------------

    @Test
    fun `the summary names the count and never says crashed while healthy`() {
        assertEquals("Checks are running normally", CheckerHealth.State.Healthy.summary)
        val raised = streak(3)
        assertTrue(raised.state.summary.contains("3"))
        assertNotEquals(CheckerHealth.State.Healthy.summary, raised.state.summary)
    }

    @Test
    fun `the last error signature and monitor survive into the raise`() {
        var outcome = error(monitorId = "m1", signature = "FirstBoom")
        outcome = error(previous = outcome.state, monitorId = "m1", signature = "SecondBoom", at = now + minute)
        outcome = error(previous = outcome.state, monitorId = "m2", signature = "ThirdBoom", at = now + 2 * minute)
        assertEquals(CheckerHealth.Action.RAISE, outcome.action)
        assertEquals("ThirdBoom", outcome.state.lastSignature)
        assertEquals(setOf("m1", "m2"), outcome.state.affectedMonitorIds)
        assertEquals(now, outcome.state.firstErrorAt)
        assertEquals(now + 2 * minute, outcome.state.lastErrorAt)
    }
}
