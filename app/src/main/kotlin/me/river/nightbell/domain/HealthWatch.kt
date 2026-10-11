package me.river.nightbell.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How a [MonitorKind.HEALTH_ENDPOINT] monitor gets past the endpoint's own
 * protection. The format and the sign-in rules are in `docs/health-format.md`.
 */
@Serializable
enum class HealthSignIn {
    /** The endpoint answers anybody. */
    @SerialName("none")
    NONE,

    /** A fixed key, sent as one of the monitor's ordinary headers. */
    @SerialName("headers")
    HEADERS,

    /** HTTP basic auth. The password lives in the credential vault. */
    @SerialName("basic")
    BASIC,

    /** A bearer token from an OpenID Connect provider, kept fresh by Nightbell. */
    @SerialName("sso")
    SSO,
}

/** How the first tokens of an [HealthSignIn.SSO] sign-in were got, which decides what happens when they run out. */
@Serializable
enum class SsoGrant {
    /** Authorization code with PKCE, through the web app in a WebView. */
    @SerialName("browser")
    BROWSER,

    /** The OAuth password grant, with a stored password. */
    @SerialName("password")
    PASSWORD,

    /** A confidential client's own credentials. No refresh token exists. */
    @SerialName("client_credentials")
    CLIENT_CREDENTIALS,

    /** A refresh token the user pasted from a session they already had. */
    @SerialName("pasted")
    PASTED,
}

/**
 * What a [MonitorKind.HEALTH_ENDPOINT] monitor reads and how it signs in.
 *
 * Nothing secret is here. Tokens, the password and the client secret are in the
 * credential vault, outside the snapshot, so that saving setup cannot write an
 * old token over one the worker has just rotated, and a backup never carries one.
 */
@Serializable
data class HealthWatch(
    val signIn: HealthSignIn = HealthSignIn.NONE,
    val grant: SsoGrant = SsoGrant.BROWSER,
    /** The provider, as its discovery document names itself. */
    val issuer: String = "",
    val clientId: String = "",
    /** Only ever used for [SsoGrant.BROWSER], and must be exactly what the provider has registered. */
    val redirectUri: String = "",
    /** What the web app asked for, kept so Nightbell asks for at least as much. */
    val scope: String = "",
    /** From discovery at setup, so a check does not fetch discovery every time. */
    val tokenEndpoint: String = "",
    val authorizationEndpoint: String = "",
    /** Where the browser sign-in starts. Blank means the scheme and host of the health URL. */
    val webAppUrl: String = "",
    /** For [HealthSignIn.BASIC] and [SsoGrant.PASSWORD]. */
    val username: String = "",
    /** Treat a report whose `checkedAt` is older than this as down. 0 is off. */
    val staleAfterMinutes: Int = 0,
) {
    val usesVault: Boolean
        get() = signIn == HealthSignIn.BASIC || signIn == HealthSignIn.SSO
}

/** One status word, after the aliases are folded in. */
@Serializable
enum class HealthStatus(val rank: Int) {
    @SerialName("up")
    UP(0),

    @SerialName("degraded")
    DEGRADED(1),

    @SerialName("down")
    DOWN(2),
    ;

    val label: String
        get() = when (this) {
            UP -> "Up"
            DEGRADED -> "Degraded"
            DOWN -> "Down"
        }
}

/**
 * One of the service's own rules, with what it measured and what it decided.
 * The point of carrying it is that "down" arrives with its reason: 14 against a
 * line at 10 is something a person can judge, a red dot is not.
 */
@Serializable
data class HealthCondition(
    val id: String,
    val name: String = "",
    val status: HealthStatus = HealthStatus.UP,
    /** Already rendered: a number without a needless decimal point, `true`, or the string as sent. */
    val observed: String = "",
    val rule: String = "",
    val unit: String = "",
    val window: String = "",
    val message: String = "",
) {
    val displayName: String get() = name.ifBlank { id }
}

@Serializable
enum class HealthLogLevel {
    @SerialName("info")
    INFO,

    @SerialName("warn")
    WARN,

    @SerialName("error")
    ERROR,
}

/** One piece of evidence the service logged while checking a part. */
@Serializable
data class HealthLogEntry(
    /** Epoch millis. */
    val at: Long,
    val level: HealthLogLevel = HealthLogLevel.INFO,
    val message: String = "",
    /** Flat pairs, values already rendered as text, in document order. */
    val data: List<Pair<String, String>> = emptyList(),
)

/** One part of a service's report, as read. Strings are already cut to the documented lengths. */
@Serializable
data class HealthComponent(
    val id: String,
    val name: String = "",
    val status: HealthStatus = HealthStatus.UP,
    val message: String = "",
    /** Epoch millis, 0 when the document did not say or said something unreadable. */
    val since: Long = 0L,
    val value: Double? = null,
    val unit: String = "",
    /** How long the service's own check of this part took, or -1 when it did not say. */
    val durationMs: Double = -1.0,
    val conditions: List<HealthCondition> = emptyList(),
    val log: List<HealthLogEntry> = emptyList(),
) {
    val displayName: String get() = name.ifBlank { id }

    /** `value unit`, or blank. A whole number is printed without a decimal point. */
    val reading: String
        get() {
            val number = value ?: return ""
            val text = if (number == Math.rint(number) && kotlin.math.abs(number) < 1e15) {
                number.toLong().toString()
            } else {
                number.toString()
            }
            return if (unit.isBlank()) text else "$text $unit"
        }
}

/**
 * Why a monitor is not checking because of its own sign-in.
 *
 * None of these is a claim about the service, which is why none of them pages:
 * see `docs/health-format.md`, "How Nightbell tells sign-in trouble apart".
 */
@Serializable
enum class AuthProblem {
    @SerialName("none")
    NONE,

    /** Never signed in, signed out, or restored from a backup that carried no tokens. */
    @SerialName("signed_out")
    SIGNED_OUT,

    /** The provider said the refresh token is no longer valid. */
    @SerialName("session_ended")
    SESSION_ENDED,

    /** The provider refused the stored password or client secret. */
    @SerialName("credentials_refused")
    CREDENTIALS_REFUSED,

    /** The vault could not decrypt what it held, usually after a restore. */
    @SerialName("vault_unreadable")
    VAULT_UNREADABLE,
    ;

    val headline: String
        get() = when (this) {
            NONE -> ""
            SIGNED_OUT -> "Not signed in"
            SESSION_ENDED -> "The sign-in has ended"
            CREDENTIALS_REFUSED -> "The provider refused the stored password or secret"
            VAULT_UNREADABLE -> "The stored sign-in could not be read"
        }
}

/** Nightbell's own sign-in, as last seen. Never a token. */
@Serializable
data class HealthAuth(
    val problem: AuthProblem = AuthProblem.NONE,
    /** The provider's own words, scrubbed, for the detail line. */
    val detail: String = "",
    /** When [problem] began, for the once-a-day reminder. */
    val since: Long = 0L,
    val lastNotifiedAt: Long = 0L,
    /** `preferred_username` or `sub` from the access token, for "Signed in as". */
    val account: String = "",
    val lastRefreshAt: Long = 0L,
    /** When the provider said the refresh token expires, 0 when it did not say or never does. */
    val refreshExpiresAt: Long = 0L,
    /** The refresh token is an offline one, and outlives the browser session. */
    val offline: Boolean = false,
)

/** What a [MonitorKind.HEALTH_ENDPOINT] monitor last read. */
@Serializable
data class HealthState(
    /**
     * Replaced on every check that read a document and cleared on one that did
     * not, the same rule as Alertmanager's list: a component carried forward
     * from a report that no longer exists would be showing an outage that might
     * have ended.
     */
    val components: List<HealthComponent> = emptyList(),
    /** How many the document had, which is more than [components] when it was capped. */
    val componentsTotal: Int = 0,
    /** Set when the verdict says up and a component says down. */
    val disagreement: String = "",
    /** What could not be read, for the detail screen. */
    val notes: List<String> = emptyList(),
    /** `checkedAt` of the last document, epoch millis, 0 when it had none. */
    val reportedAt: Long = 0L,
    val service: String = "",
    val version: String = "",
    val environment: String = "",
    val auth: HealthAuth = HealthAuth(),
)
