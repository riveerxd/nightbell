package me.river.nightbell.domain

/**
 * When a health monitor that cannot sign in says so.
 *
 * Once when the trouble starts or changes, then once a day for as long as it
 * lasts. A monitor that has quietly stopped checking is the risk on this side,
 * so it is not allowed to go silent; it is also not allowed to buzz every
 * check, because it is not an outage.
 */
object SignInReminder {

    const val EVERY_MS = 24L * 60 * 60 * 1000

    fun shouldNotify(before: HealthAuth, problem: AuthProblem, nowMs: Long): Boolean {
        if (problem == AuthProblem.NONE) return false
        if (before.problem != problem) return true
        return nowMs - before.lastNotifiedAt >= EVERY_MS
    }

    /** The auth state after a check that could not sign in. */
    fun failed(before: HealthAuth, problem: AuthProblem, detail: String, nowMs: Long, notified: Boolean): HealthAuth =
        before.copy(
            problem = problem,
            detail = detail,
            since = if (before.problem == problem && before.since > 0L) before.since else nowMs,
            lastNotifiedAt = if (notified) nowMs else before.lastNotifiedAt,
        )

    /**
     * The auth state after a check that reached a verdict, or null to leave it.
     *
     * [granted] is what the broker handed over with a token. A monitor that
     * signs in with headers or not at all has no token to show, and reaching a
     * verdict is the proof that nothing is wrong with its sign-in, which matters
     * after the sign-in method was changed away from one that had ended. A
     * provider that was down hands over nothing either, and says nothing about
     * whether the session is good, so that leaves the state alone.
     *
     * Missing in 3.18.0: [signedIn] existed and nothing called it, so a monitor
     * that had once lost its sign-in said "Not checking" for ever, over a
     * history of checks that had passed since.
     */
    fun afterVerdict(before: HealthAuth, granted: HealthAuth?, signIn: HealthSignIn): HealthAuth? = when {
        granted != null -> signedIn(before, granted)
        signIn == HealthSignIn.NONE || signIn == HealthSignIn.HEADERS ->
            if (before.problem != AuthProblem.NONE) signedIn(before, HealthAuth()) else null
        else -> null
    }

    /** The auth state after a check that signed in. The trouble, if any, is over. */
    fun signedIn(before: HealthAuth, granted: HealthAuth): HealthAuth =
        granted.copy(problem = AuthProblem.NONE, detail = "", since = 0L, lastNotifiedAt = 0L)
            .let { if (it.account.isBlank()) it.copy(account = before.account) else it }
}
