package me.river.nightbell.domain

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The OpenID Connect requests a browser sign-in is made of, as pure functions.
 *
 * Everything here is string work, kept out of the WebView code so it can be
 * tested on the JVM: building the authorization URL, recognising the web app's
 * own sign-in request when it tries to make one, finding the provider from it,
 * and reading the answer off the redirect.
 */
object OidcRequests {

    private val json = Json { ignoreUnknownKeys = true }
    private val random = SecureRandom()
    private const val KEYCLOAK_AUTH_SUFFIX = "/protocol/openid-connect/auth"

    // ---- PKCE ----------------------------------------------------------------

    /** 32 random bytes, base64url without padding: 43 characters, as RFC 7636 recommends. */
    fun newVerifier(): String = randomToken(32)

    fun challengeOf(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    fun randomToken(bytes: Int = 16): String {
        val raw = ByteArray(bytes).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
    }

    // ---- Nightbell's own request -----------------------------------------------

    data class Pending(
        val url: String,
        val verifier: String,
        val state: String,
        val nonce: String,
    )

    /**
     * Nightbell's authorization request.
     *
     * `prompt=login` so a session left in the WebView by an earlier sign-in can
     * never sign in as the wrong person without asking. The scope is the web
     * app's own plus `openid` and, unless [offline] is off, `offline_access`.
     */
    fun authorization(
        authorizationEndpoint: String,
        clientId: String,
        redirectUri: String,
        appScope: String,
        offline: Boolean = true,
        verifier: String = newVerifier(),
        state: String = randomToken(),
        nonce: String = randomToken(),
    ): Pending {
        val scope = linkedSetOf("openid").apply {
            appScope.split(' ').filter { it.isNotBlank() }.forEach { add(it) }
            if (offline) add("offline_access") else remove("offline_access")
        }.joinToString(" ")
        val query = listOf(
            "response_type" to "code",
            "client_id" to clientId,
            "redirect_uri" to redirectUri,
            "scope" to scope,
            "state" to state,
            "nonce" to nonce,
            "code_challenge" to challengeOf(verifier),
            "code_challenge_method" to "S256",
            "response_mode" to "query",
            "prompt" to "login",
        ).joinToString("&") { (k, v) -> "$k=${encode(v)}" }
        val joiner = if (authorizationEndpoint.contains('?')) "&" else "?"
        return Pending(authorizationEndpoint + joiner + query, verifier, state, nonce)
    }

    // ---- the web app's request ---------------------------------------------------

    /** What the web app was about to ask the provider for. */
    data class Intercepted(
        /** The URL without its query. */
        val endpoint: String,
        val clientId: String,
        val redirectUri: String,
        val scope: String,
        /** Carried a PKCE challenge. Without one the client is probably confidential. */
        val usesPkce: Boolean,
    )

    /**
     * The web app's authorization request, or null when [url] is not one.
     *
     * Only a main-frame request counts, and never one with `prompt=none`: that is
     * a silent session check a single-page app makes on load, not a sign-in, and
     * cancelling it would break the page before the user has touched anything.
     */
    fun intercept(url: String, mainFrame: Boolean): Intercepted? {
        if (!mainFrame) return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val query = queryOf(uri.rawQuery)
        if (query["response_type"]?.split(' ')?.contains("code") != true) return null
        val clientId = query["client_id"].orEmpty()
        val redirect = query["redirect_uri"].orEmpty()
        if (clientId.isBlank() || redirect.isBlank()) return null
        if (query["prompt"]?.split(' ')?.contains("none") == true) return null
        return Intercepted(
            endpoint = url.substringBefore('?').substringBefore('#'),
            clientId = clientId,
            redirectUri = redirect,
            scope = query["scope"].orEmpty(),
            usesPkce = !query["code_challenge"].isNullOrBlank(),
        )
    }

    /**
     * Where to look for the provider's discovery document, most likely first.
     *
     * Keycloak's authorization endpoint sits at a fixed path under its issuer,
     * so stripping that path is the answer and there is nothing else to try. For
     * anything else the path is walked upwards, because providers disagree about
     * where they put the endpoint relative to the issuer. Every candidate is
     * checked against its own discovery document by [matches] before it is used.
     */
    fun issuerCandidates(authorizationEndpoint: String): List<String> {
        val base = authorizationEndpoint.substringBefore('?').trimEnd('/')
        if (base.endsWith(KEYCLOAK_AUTH_SUFFIX)) return listOf(base.removeSuffix(KEYCLOAK_AUTH_SUFFIX))
        val uri = runCatching { URI(base) }.getOrNull() ?: return emptyList()
        val origin = "${uri.scheme}://${uri.rawAuthority}"
        val segments = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }
        return (segments.size - 1 downTo 0).map { keep ->
            if (keep == 0) origin else origin + "/" + segments.take(keep).joinToString("/")
        }
    }

    fun discoveryUrl(issuer: String): String = issuer.trimEnd('/') + "/.well-known/openid-configuration"

    data class Discovery(
        val issuer: String,
        val authorizationEndpoint: String,
        val tokenEndpoint: String,
    ) {
        /** "Found realm main" for Keycloak, the host for anything else. */
        val label: String
            get() {
                val realm = issuer.substringAfter("/realms/", "").substringBefore('/')
                if (realm.isNotBlank()) return "Found realm $realm"
                return "Found ${runCatching { URI(issuer).host }.getOrNull() ?: issuer}"
            }

        val host: String get() = runCatching { URI(issuer).host }.getOrNull().orEmpty()
    }

    /** A discovery document, or null when it is not one or lacks either endpoint. */
    fun discovery(body: String): Discovery? {
        val doc = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
        fun text(key: String) = (doc[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val issuer = text("issuer")
        val auth = text("authorization_endpoint")
        val token = text("token_endpoint")
        if (issuer.isBlank() || !token.startsWith("http")) return null
        return Discovery(issuer, auth, token)
    }

    /**
     * Whether a candidate's discovery document vouches for the endpoint the web
     * app was sending the user to. Never accept an issuer that does not: a
     * document that names some other authorization endpoint is some other
     * provider, and its tokens would be refused, or worse, accepted somewhere
     * they were not meant for.
     */
    fun matches(discovery: Discovery, intercepted: Intercepted): Boolean =
        discovery.authorizationEndpoint.substringBefore('?').trimEnd('/') == intercepted.endpoint.trimEnd('/')

    // ---- the answer ----------------------------------------------------------------

    sealed interface Callback {
        data class Code(val code: String, val state: String) : Callback

        data class Error(val error: String, val description: String, val state: String) : Callback
    }

    /** The provider's answer, when [url] is a navigation to [redirectUri]. Null for any other URL. */
    fun callback(url: String, redirectUri: String): Callback? {
        if (redirectUri.isBlank()) return null
        val bare = url.substringBefore('?').substringBefore('#')
        if (bare.trimEnd('/') != redirectUri.substringBefore('?').trimEnd('/')) return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val query = queryOf(uri.rawQuery)
        val state = query["state"].orEmpty()
        val error = query["error"]
        if (!error.isNullOrBlank()) {
            return Callback.Error(error, LogRedactor.scrub(query["error_description"].orEmpty()).take(200), state)
        }
        val code = query["code"]
        if (code.isNullOrBlank()) return Callback.Error("no_code", "The provider came back without a code", state)
        return Callback.Code(code, state)
    }

    /** The request [OidcRequests.callback] answers, and anything under it, which the WebView must never load. */
    fun isCallback(url: String, redirectUri: String): Boolean =
        redirectUri.isNotBlank() && url.substringBefore('?').substringBefore('#').trimEnd('/') ==
            redirectUri.substringBefore('?').trimEnd('/')

    // ---- helpers ---------------------------------------------------------------

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun queryOf(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        val out = linkedMapOf<String, String>()
        for (pair in raw.split('&')) {
            if (pair.isEmpty()) continue
            val key = URLDecoder.decode(pair.substringBefore('='), "UTF-8")
            val value = URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
            if (key !in out) out[key] = value
        }
        return out
    }

    /** The scheme and host of [url], for the web app address the browser sign-in starts from. */
    fun originOf(url: String): String {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return ""
        if (uri.scheme.isNullOrBlank() || uri.rawAuthority.isNullOrBlank()) return ""
        return "${uri.scheme}://${uri.rawAuthority}"
    }
}
