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

    /** The auth state after a check that signed in. The trouble, if any, is over. */
    fun signedIn(before: HealthAuth, granted: HealthAuth): HealthAuth =
        granted.copy(problem = AuthProblem.NONE, detail = "", since = 0L, lastNotifiedAt = 0L)
            .let { if (it.account.isBlank()) it.copy(account = before.account) else it }
}
