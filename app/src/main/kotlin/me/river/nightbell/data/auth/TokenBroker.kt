package me.river.nightbell.data.auth

import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.river.nightbell.data.diag.Diag
import me.river.nightbell.domain.AuthProblem
import me.river.nightbell.domain.HealthAuth
import me.river.nightbell.domain.HealthSignIn
import me.river.nightbell.domain.JwtPeek
import me.river.nightbell.domain.LogEvent
import me.river.nightbell.domain.LogField
import me.river.nightbell.domain.LogRedactor
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.OidcErrors
import me.river.nightbell.domain.OidcRequests
import me.river.nightbell.domain.SsoGrant

/**
 * Holds every health monitor's sign-in and hands out a usable `Authorization`.
 *
 * One per process. Checks, "Test" in setup and the sign-in screen all come
 * through here, and each monitor has its own lock held across "read the vault,
 * maybe ask the provider, write the vault". That lock is separate from the
 * engine's check lock on purpose: setup's Test runs outside the check lock, and
 * without a lock of its own here, a Test and a scheduled check could both
 * refresh one token, and with rotation on the provider keeps only the second.
 */
class TokenBroker(
    private val vault: CredentialVault,
    private val http: ProviderHttp,
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** Bounds the uncancellable part, so a provider that never answers cannot hold a worker forever. */
    private val callTimeoutMs: Long = 60_000L,
) {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val json = Json { ignoreUnknownKeys = true }

    sealed interface Access {
        /** Nothing to add: the monitor signs in with its own headers, or not at all. */
        data object Open : Access

        data class Granted(val authorization: String, val auth: HealthAuth) : Access

        /** Nightbell's own sign-in has run out. Not a claim about the service. */
        data class SignInNeeded(val problem: AuthProblem, val detail: String = "") : Access

        /** The provider could not be reached or answered 5xx. The service's users cannot sign in either. */
        data class ProviderDown(val message: String, val detail: String = "") : Access
    }

    private fun lockFor(id: String) = locks.getOrPut(id) { Mutex() }

    /**
     * A usable `Authorization` for [monitor], refreshing first if the stored
     * access token is within 30 seconds of running out, or always when [forceRefresh].
     */
    suspend fun access(monitor: Monitor, forceRefresh: Boolean = false): Access = when (monitor.health.signIn) {
        HealthSignIn.NONE, HealthSignIn.HEADERS -> Access.Open
        HealthSignIn.BASIC -> lockFor(monitor.id).withLock { basic(monitor) }
        HealthSignIn.SSO -> lockFor(monitor.id).withLock { sso(monitor, forceRefresh) }
    }

    /**
     * Stores what the user just entered or pasted and signs in with it at once.
     *
     * A deliberate sign-in, so a credential refused before is tried again: the
     * once-per-change rule is about Nightbell retrying by itself, not about the
     * user tapping Sign in.
     */
    suspend fun signIn(
        monitor: Monitor,
        password: String = "",
        clientSecret: String = "",
        pastedRefreshToken: String = "",
        pastedAccessToken: String = "",
    ): Access = lockFor(monitor.id).withLock {
        val previous = vault.entry(monitor.id)
        val fresh = VaultEntry(
            password = password.ifBlank { previous.password },
            clientSecret = clientSecret.ifBlank { previous.clientSecret },
            refreshToken = pastedRefreshToken.trim(),
            accessToken = pastedAccessToken.trim(),
            // A pasted access token's own lifetime is unknown from this side, so
            // it is never trusted for a check: the refresh below replaces it.
            accessExpiresAt = 0L,
            offline = JwtPeek.claims(pastedRefreshToken)?.isOffline == true,
        )
        vault.write(monitor.id, fresh)
        if (monitor.health.signIn == HealthSignIn.BASIC) return@withLock basic(monitor)
        sso(monitor, forceRefresh = true)
    }

    /** The browser sign-in's last step: an authorization code and the verifier it was asked for with. */
    suspend fun exchangeCode(
        monitor: Monitor,
        code: String,
        verifier: String,
        redirectUri: String,
        clientSecret: String = "",
    ): Access = lockFor(monitor.id).withLock {
        val previous = vault.entry(monitor.id)
        val secret = clientSecret.ifBlank { previous.clientSecret }
        val form = buildMap {
            put("grant_type", "authorization_code")
            put("code", code)
            put("redirect_uri", redirectUri)
            put("client_id", monitor.health.clientId)
            put("code_verifier", verifier)
            if (secret.isNotBlank()) put("client_secret", secret)
        }
        val start = VaultEntry(clientSecret = secret)
        when (val outcome = request(monitor, start, form, "authorization_code")) {
            is Outcome.Stored -> granted(outcome.entry)
            is Outcome.Answered -> when (outcome.answer.kind) {
                OidcErrors.Kind.UNREACHABLE -> Access.ProviderDown("Sign-in server unreachable", outcome.answer.detail)
                OidcErrors.Kind.CLIENT_REFUSED -> Access.SignInNeeded(AuthProblem.CREDENTIALS_REFUSED, outcome.answer.detail)
                else -> Access.SignInNeeded(AuthProblem.SIGNED_OUT, outcome.answer.detail)
            }
        }
    }

    suspend fun signOut(monitorId: String) = lockFor(monitorId).withLock { vault.delete(monitorId) }

    /**
     * The provider's discovery document at [issuer], for "Enter provider
     * details" and for the browser sign-in finding its provider.
     */
    suspend fun discover(monitor: Monitor, issuer: String): OidcRequests.Discovery? {
        val raw = http.get(monitor, OidcRequests.discoveryUrl(issuer))
        val found = if (raw.status in 200..299) OidcRequests.discovery(raw.body) else null
        Diag.log(LogEvent.AUTH_DISCOVERY, LogField.monitor(monitor.id), LogField.of("found", found != null))
        return found
    }

    /** What the vault says about a monitor's sign-in right now, without asking the provider anything. */
    suspend fun status(monitor: Monitor): HealthAuth? = lockFor(monitor.id).withLock {
        when (val read = vault.read(monitor.id)) {
            is CredentialVault.Read.Found -> if (read.entry.hasTokens) authOf(read.entry) else null
            else -> null
        }
    }

    // ---- basic -----------------------------------------------------------------

    private suspend fun basic(monitor: Monitor): Access {
        val entry = when (val read = vault.read(monitor.id)) {
            is CredentialVault.Read.Unreadable -> return Access.SignInNeeded(AuthProblem.VAULT_UNREADABLE)
            is CredentialVault.Read.Empty -> VaultEntry()
            is CredentialVault.Read.Found -> read.entry
        }
        if (entry.password.isEmpty()) return Access.SignInNeeded(AuthProblem.SIGNED_OUT, "No password is stored")
        val user = monitor.health.username.trim()
        val pair = Base64.getEncoder().encodeToString("$user:${entry.password}".toByteArray(Charsets.UTF_8))
        return Access.Granted("Basic $pair", HealthAuth(account = user))
    }

    // ---- single sign-on --------------------------------------------------------

    private suspend fun sso(monitor: Monitor, forceRefresh: Boolean): Access {
        var entry = when (val read = vault.read(monitor.id)) {
            is CredentialVault.Read.Unreadable -> return Access.SignInNeeded(AuthProblem.VAULT_UNREADABLE)
            is CredentialVault.Read.Empty -> VaultEntry()
            is CredentialVault.Read.Found -> read.entry
        }
        val now = nowMs()
        if (!forceRefresh && entry.accessToken.isNotBlank() && entry.accessExpiresAt - EARLY_MS > now) {
            return granted(entry)
        }
        val hadTokens = entry.hasTokens
        if (entry.refreshToken.isNotBlank()) {
            val form = buildMap {
                put("grant_type", "refresh_token")
                put("client_id", monitor.health.clientId)
                put("refresh_token", entry.refreshToken)
                if (entry.clientSecret.isNotBlank()) put("client_secret", entry.clientSecret)
            }
            when (val outcome = request(monitor, entry, form, "refresh_token")) {
                is Outcome.Stored -> return granted(outcome.entry)
                is Outcome.Answered -> {
                    val answer = outcome.answer
                    when (answer.kind) {
                        OidcErrors.Kind.UNREACHABLE ->
                            return Access.ProviderDown("Sign-in server unreachable", answer.detail)
                        // Still missing after a rediscovery, so the service's own
                        // clients cannot get tokens either.
                        OidcErrors.Kind.NOT_FOUND ->
                            return Access.ProviderDown("Sign-in server has no token endpoint", answer.detail)
                        OidcErrors.Kind.CLIENT_REFUSED -> {
                            refuse(monitor, entry, entry.clientSecret)
                            return Access.SignInNeeded(AuthProblem.CREDENTIALS_REFUSED, answer.detail)
                        }
                        else -> {
                            // The refresh token is dead. It is cleared so the next
                            // check does not spend a request learning that again.
                            entry = entry.withoutTokens()
                            vault.write(monitor.id, entry)
                            Diag.log(
                                LogEvent.AUTH_REFUSED,
                                LogField.monitor(monitor.id),
                                LogField.tag("grant", "refresh_token"),
                                LogField.text("error", answer.error),
                            )
                            if (monitor.health.grant == SsoGrant.BROWSER || monitor.health.grant == SsoGrant.PASTED) {
                                return Access.SignInNeeded(AuthProblem.SESSION_ENDED, answer.detail)
                            }
                        }
                    }
                }
            }
        }
        return when (monitor.health.grant) {
            SsoGrant.PASSWORD -> passwordGrant(monitor, entry)
            SsoGrant.CLIENT_CREDENTIALS -> clientCredentials(monitor, entry)
            SsoGrant.BROWSER, SsoGrant.PASTED -> Access.SignInNeeded(
                if (hadTokens) AuthProblem.SESSION_ENDED else AuthProblem.SIGNED_OUT,
            )
        }
    }

    private suspend fun passwordGrant(monitor: Monitor, entry: VaultEntry): Access {
        if (entry.password.isEmpty()) return Access.SignInNeeded(AuthProblem.SIGNED_OUT, "No password is stored")
        if (entry.refusedFingerprint.isNotEmpty() &&
            entry.refusedFingerprint == CredentialVault.fingerprint(entry.password)
        ) {
            return Access.SignInNeeded(AuthProblem.CREDENTIALS_REFUSED, "Edit the password or sign in again")
        }
        val form = { offline: Boolean ->
            buildMap {
                put("grant_type", "password")
                put("client_id", monitor.health.clientId)
                put("username", monitor.health.username.trim())
                put("password", entry.password)
                put("scope", scopeFor(monitor.health.scope, offline))
                if (entry.clientSecret.isNotBlank()) put("client_secret", entry.clientSecret)
            }
        }
        return firstTokens(monitor, entry, form, "password", entry.password)
    }

    private suspend fun clientCredentials(monitor: Monitor, entry: VaultEntry): Access {
        if (entry.clientSecret.isEmpty()) return Access.SignInNeeded(AuthProblem.SIGNED_OUT, "No client secret is stored")
        if (entry.refusedFingerprint.isNotEmpty() &&
            entry.refusedFingerprint == CredentialVault.fingerprint(entry.clientSecret)
        ) {
            return Access.SignInNeeded(AuthProblem.CREDENTIALS_REFUSED, "Edit the client secret to try again")
        }
        // No offline_access: there is no user session for it to outlive, and
        // some providers refuse it outright for this grant.
        val form = { _: Boolean ->
            buildMap {
                put("grant_type", "client_credentials")
                put("client_id", monitor.health.clientId)
                put("client_secret", entry.clientSecret)
                put("scope", scopeFor(monitor.health.scope, offline = false))
            }
        }
        return firstTokens(monitor, entry, form, "client_credentials", entry.clientSecret)
    }

    /**
     * One grant that starts a sign-in from a stored credential, with the one
     * retry without `offline_access` that a provider refusing it earns.
     */
    private suspend fun firstTokens(
        monitor: Monitor,
        entry: VaultEntry,
        form: (Boolean) -> Map<String, String>,
        grant: String,
        credential: String,
    ): Access {
        var offline = true
        repeat(2) {
            when (val outcome = request(monitor, entry, form(offline), grant)) {
                is Outcome.Stored -> return granted(outcome.entry)
                is Outcome.Answered -> {
                    val answer = outcome.answer
                    when (answer.kind) {
                        OidcErrors.Kind.SCOPE_REFUSED -> if (offline) {
                            offline = false
                            return@repeat
                        }
                        OidcErrors.Kind.UNREACHABLE ->
                            return Access.ProviderDown("Sign-in server unreachable", answer.detail)
                        OidcErrors.Kind.GRANT_REFUSED, OidcErrors.Kind.CLIENT_REFUSED -> {
                            refuse(monitor, entry, credential)
                            return Access.SignInNeeded(AuthProblem.CREDENTIALS_REFUSED, answer.detail)
                        }
                        else -> Unit
                    }
                    return Access.SignInNeeded(AuthProblem.SIGNED_OUT, answer.detail)
                }
            }
        }
        return Access.SignInNeeded(AuthProblem.SIGNED_OUT)
    }

    private suspend fun refuse(monitor: Monitor, entry: VaultEntry, credential: String) {
        vault.write(monitor.id, entry.withoutTokens().copy(refusedFingerprint = CredentialVault.fingerprint(credential)))
        Diag.log(LogEvent.AUTH_REFUSED, LogField.monitor(monitor.id), LogField.tag("what", "credential"))
    }

    private sealed interface Outcome {
        data class Stored(val entry: VaultEntry) : Outcome

        data class Answered(val answer: OidcErrors.Answer) : Outcome
    }

    /**
     * One token request, and on success the vault write, as one uncancellable step.
     *
     * Uncancellable because of rotation. Once the provider has answered a refresh
     * the old refresh token is spent, and a worker cancelled between that answer
     * and the write would leave the vault holding the spent one: signed out, by
     * the act of checking. Process death in that window is still possible and
     * ends in "sign in needed" at worst. Bounded by [callTimeoutMs] so a provider
     * that never answers cannot pin a worker.
     */
    private suspend fun request(
        monitor: Monitor,
        entry: VaultEntry,
        form: Map<String, String>,
        grant: String,
        rediscovered: Boolean = false,
    ): Outcome {
        val endpoint = entry.tokenEndpoint.ifBlank { monitor.health.tokenEndpoint }
        val result = withContext(NonCancellable) {
            try {
                withTimeout(callTimeoutMs) {
                    val raw = http.post(monitor, endpoint, form)
                    val answer = OidcErrors.read(raw.status, raw.body).let {
                        if (raw.status == 0) it.copy(description = LogRedactor.scrub(raw.error).take(200)) else it
                    }
                    val tokens = answer.tokens
                    if (answer.kind == OidcErrors.Kind.OK && tokens != null) {
                        val now = nowMs()
                        val next = entry.copy(
                            accessToken = tokens.accessToken,
                            accessExpiresAt = now + tokens.expiresIn.coerceAtLeast(0L) * 1000L,
                            // Kept when the provider does not rotate.
                            refreshToken = tokens.refreshToken.ifBlank { entry.refreshToken },
                            refreshExpiresAt = when {
                                tokens.refreshExpiresIn > 0L -> now + tokens.refreshExpiresIn * 1000L
                                tokens.refreshToken.isNotBlank() -> 0L
                                else -> entry.refreshExpiresAt
                            },
                            offline = if (tokens.refreshToken.isNotBlank()) {
                                JwtPeek.claims(tokens.refreshToken)?.isOffline == true ||
                                    tokens.scope.split(' ').contains("offline_access")
                            } else {
                                entry.offline
                            },
                            refusedFingerprint = "",
                            refreshedAt = now,
                        )
                        vault.write(monitor.id, next)
                        Diag.log(
                            if (grant == "refresh_token") LogEvent.AUTH_REFRESH else LogEvent.AUTH_TOKEN,
                            LogField.monitor(monitor.id),
                            LogField.tag("grant", grant),
                            LogField.of("expires_in_s", tokens.expiresIn),
                            LogField.of("rotated", tokens.refreshToken.isNotBlank() && tokens.refreshToken != entry.refreshToken),
                            LogField.of("offline", next.offline),
                        )
                        Outcome.Stored(next)
                    } else {
                        Outcome.Answered(answer)
                    }
                }
            } catch (_: TimeoutCancellationException) {
                Outcome.Answered(OidcErrors.Answer(OidcErrors.Kind.UNREACHABLE, error = "timeout"))
            }
        }
        if (result is Outcome.Answered) {
            val answer = result.answer
            if (answer.kind == OidcErrors.Kind.UNREACHABLE) {
                Diag.log(
                    LogEvent.AUTH_PROVIDER_DOWN,
                    LogField.monitor(monitor.id),
                    LogField.tag("grant", grant),
                    LogField.text("error", answer.detail),
                )
            }
            if (answer.kind == OidcErrors.Kind.NOT_FOUND && !rediscovered) {
                val found = rediscover(monitor) ?: return result
                val moved = entry.copy(tokenEndpoint = found)
                vault.write(monitor.id, moved)
                return request(monitor, moved, form, grant, rediscovered = true)
            }
        }
        return result
    }

    /** The token endpoint, from the issuer's discovery document. Null when it cannot be found. */
    private suspend fun rediscover(monitor: Monitor): String? {
        val issuer = monitor.health.issuer.trimEnd('/')
        if (issuer.isBlank()) return null
        val raw = http.get(monitor, "$issuer/.well-known/openid-configuration")
        if (raw.status !in 200..299) return null
        val doc = runCatching { json.parseToJsonElement(raw.body) }.getOrNull() as? JsonObject ?: return null
        val endpoint = (doc["token_endpoint"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.startsWith("http") }
        Diag.log(LogEvent.AUTH_DISCOVERY, LogField.monitor(monitor.id), LogField.of("found", endpoint != null))
        return endpoint
    }

    private fun granted(entry: VaultEntry): Access.Granted =
        Access.Granted("Bearer ${entry.accessToken}", authOf(entry))

    private fun authOf(entry: VaultEntry): HealthAuth = HealthAuth(
        account = JwtPeek.claims(entry.accessToken)?.account.orEmpty().take(80),
        lastRefreshAt = entry.refreshedAt,
        refreshExpiresAt = entry.refreshExpiresAt,
        offline = entry.offline,
    )

    companion object {
        /** An access token this close to its end is refreshed rather than sent. */
        const val EARLY_MS = 30_000L

        /** The web app's scope, plus what Nightbell needs on top of it. */
        fun scopeFor(appScope: String, offline: Boolean): String {
            val words = linkedSetOf<String>()
            words += "openid"
            appScope.split(' ').filter { it.isNotBlank() }.forEach { words += it }
            if (offline) words += "offline_access" else words -= "offline_access"
            return words.joinToString(" ")
        }
    }
}
