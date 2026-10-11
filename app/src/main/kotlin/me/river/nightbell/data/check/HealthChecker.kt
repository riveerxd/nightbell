package me.river.nightbell.data.check

import me.river.nightbell.data.auth.TokenBroker
import me.river.nightbell.data.diag.Diag
import me.river.nightbell.domain.CheckResult
import me.river.nightbell.domain.FailureKind
import me.river.nightbell.domain.HealthAuth
import me.river.nightbell.domain.HealthFormat
import me.river.nightbell.domain.HealthSignIn
import me.river.nightbell.domain.HealthState
import me.river.nightbell.domain.LogEvent
import me.river.nightbell.domain.LogField
import me.river.nightbell.domain.Monitor

/**
 * One check of a [me.river.nightbell.domain.MonitorKind.HEALTH_ENDPOINT] monitor,
 * sign-in included.
 *
 * The rule it holds is the one in `docs/health-format.md`: Nightbell's own
 * credential trouble is not a claim about the service, and everything the
 * service or its provider answers once Nightbell holds valid credentials is.
 */
class HealthChecker(
    private val broker: TokenBroker,
    /** The request itself: monitor, pinned key, and an `Authorization` value or null. */
    private val fetch: suspend (Monitor, String, String?) -> CheckResult,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    /**
     * @property result null when Nightbell could not sign in and so learned nothing.
     * @property auth what to record about the sign-in, or null to leave it as it was.
     */
    data class Outcome(val result: CheckResult?, val auth: HealthAuth?)

    suspend fun check(monitor: Monitor, certPin: String = ""): Outcome {
        val first = when (val access = broker.access(monitor)) {
            is TokenBroker.Access.Open -> return Outcome(fetch(monitor, certPin, null), null)
            is TokenBroker.Access.SignInNeeded -> return signInNeeded(monitor, access)
            is TokenBroker.Access.ProviderDown -> return Outcome(providerDown(access), null)
            is TokenBroker.Access.Granted -> access
        }
        val result = fetch(monitor, certPin, first.authorization)
        if (!HealthFormat.isRefusal(result) || monitor.health.signIn != HealthSignIn.SSO) {
            return Outcome(result, first.auth)
        }
        // Refused with a token Nightbell believed was good. One forced refresh and
        // one retry, because a token revoked early on the provider's side looks
        // exactly like this and a fresh one fixes it. Twice refused is the API's
        // answer, and real users' tokens would be refused the same way.
        Diag.log(LogEvent.AUTH_REFUSED, LogField.monitor(monitor.id), LogField.of("status", result.statusCode))
        val second = when (val access = broker.access(monitor, forceRefresh = true)) {
            is TokenBroker.Access.Granted -> access
            is TokenBroker.Access.SignInNeeded -> return signInNeeded(monitor, access)
            is TokenBroker.Access.ProviderDown -> return Outcome(providerDown(access), null)
            is TokenBroker.Access.Open -> return Outcome(result, null)
        }
        val again = fetch(monitor, certPin, second.authorization)
        if (!HealthFormat.isRefusal(again)) return Outcome(again, second.auth)
        return Outcome(refused(again), second.auth)
    }

    private fun signInNeeded(monitor: Monitor, access: TokenBroker.Access.SignInNeeded): Outcome {
        Diag.log(
            LogEvent.AUTH_SIGN_IN_NEEDED,
            LogField.monitor(monitor.id),
            LogField.of("problem", access.problem),
        )
        return Outcome(null, HealthAuth(problem = access.problem, detail = access.detail))
    }

    private fun providerDown(access: TokenBroker.Access.ProviderDown): CheckResult = CheckResult(
        ok = false,
        latencyMs = 0,
        failureKind = FailureKind.SIGN_IN_SERVER,
        message = access.message,
        detail = access.detail,
        health = HealthState(),
        at = nowMs(),
    )

    private fun refused(result: CheckResult): CheckResult = if (result.statusCode == 401) {
        result.copy(
            failureKind = FailureKind.TOKEN_REFUSED,
            message = "Signed in, but the API refused the token",
            hint = "",
        )
    } else {
        result.copy(
            failureKind = FailureKind.TOKEN_REFUSED,
            message = "Access refused (403)",
            hint = "The account Nightbell signs in with may lack a role the API requires.",
        )
    }
}
