package me.river.nightbell

import java.net.URI
import java.net.URLDecoder
import me.river.nightbell.domain.OidcErrors
import me.river.nightbell.domain.OidcRequests
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The string work behind the browser sign-in, and reading a token endpoint's answer. */
class OidcRequestsTest {

    private val auth = "https://sso.example.com/auth/realms/main/protocol/openid-connect/auth"
    private val redirect = "https://app.example.com/auth/callback"

    private fun query(url: String): Map<String, String> = URI(url).rawQuery.split('&').associate {
        URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
    }

    @Test
    fun theRfc7636AppendixVector() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            OidcRequests.challengeOf("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun aVerifierIsFortyThreeUrlSafeCharactersAndNeverRepeats() {
        val a = OidcRequests.newVerifier()
        assertEquals(43, a.length)
        assertTrue(a.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertNotEquals(a, OidcRequests.newVerifier())
    }

    @Test
    fun nightbellsOwnRequestAsksForOfflineAccessAndAFreshLogin() {
        val pending = OidcRequests.authorization(auth, "admin-app", redirect, "openid email profile", verifier = "v".repeat(43))
        val q = query(pending.url)

        assertEquals("code", q["response_type"])
        assertEquals("admin-app", q["client_id"])
        assertEquals(redirect, q["redirect_uri"])
        assertEquals("openid email profile offline_access", q["scope"])
        assertEquals("S256", q["code_challenge_method"])
        assertEquals(OidcRequests.challengeOf("v".repeat(43)), q["code_challenge"])
        assertEquals("login", q["prompt"])
        assertEquals("query", q["response_mode"])
        assertEquals(pending.state, q["state"])
        assertEquals(pending.nonce, q["nonce"])
    }

    @Test
    fun withoutOfflineTheScopeLosesIt() {
        val q = query(OidcRequests.authorization(auth, "c", redirect, "openid offline_access", offline = false).url)

        assertEquals("openid", q["scope"])
    }

    @Test
    fun theWebAppsSignInIsRecognised() {
        val url = "$auth?client_id=admin-app&redirect_uri=https%3A%2F%2Fapp.example.com%2Fauth%2Fcallback" +
            "&response_type=code&scope=openid%20email&code_challenge=abc&code_challenge_method=S256&state=s"

        val seen = OidcRequests.intercept(url, mainFrame = true)!!

        assertEquals(auth, seen.endpoint)
        assertEquals("admin-app", seen.clientId)
        assertEquals(redirect, seen.redirectUri)
        assertEquals("openid email", seen.scope)
        assertTrue(seen.usesPkce)
    }

    @Test
    fun aSilentSessionCheckAndASubframeAreLeftAlone() {
        val url = "$auth?client_id=a&redirect_uri=https%3A%2F%2Fx%2Fcb&response_type=code&prompt=none"

        assertNull(OidcRequests.intercept(url, mainFrame = true))
        assertNull(OidcRequests.intercept(url.replace("&prompt=none", ""), mainFrame = false))
        assertNull(OidcRequests.intercept("https://app.example.com/dashboard", mainFrame = true))
    }

    @Test
    fun noPkceIsNoted() {
        val url = "$auth?client_id=a&redirect_uri=https%3A%2F%2Fx%2Fcb&response_type=code"

        assertFalse(OidcRequests.intercept(url, mainFrame = true)!!.usesPkce)
    }

    @Test
    fun keycloakIsFoundByStrippingItsPath() {
        assertEquals(listOf("https://sso.example.com/auth/realms/main"), OidcRequests.issuerCandidates(auth))
    }

    @Test
    fun anythingElseIsWalkedUpwards() {
        assertEquals(
            listOf("https://id.example.com/tenant/oauth2", "https://id.example.com/tenant", "https://id.example.com"),
            OidcRequests.issuerCandidates("https://id.example.com/tenant/oauth2/authorize"),
        )
    }

    @Test
    fun onlyADiscoveryDocumentNamingTheSameEndpointIsAccepted() {
        val seen = OidcRequests.intercept("$auth?client_id=a&redirect_uri=https%3A%2F%2Fx%2Fcb&response_type=code", true)!!
        val right = OidcRequests.discovery(
            """{"issuer":"https://sso.example.com/auth/realms/main","authorization_endpoint":"$auth","token_endpoint":"https://sso.example.com/t"}""",
        )!!
        val wrong = right.copy(authorizationEndpoint = "https://evil.example.com/auth")

        assertTrue(OidcRequests.matches(right, seen))
        assertFalse(OidcRequests.matches(wrong, seen))
        assertEquals("Found realm main", right.label)
    }

    @Test
    fun theCallbackIsReadAndAnythingElseIgnored() {
        val code = OidcRequests.callback("$redirect?state=s1&session_state=x&code=c1", redirect)
        val error = OidcRequests.callback("$redirect?error=access_denied&error_description=no&state=s1", redirect)

        assertEquals(OidcRequests.Callback.Code("c1", "s1"), code)
        assertEquals("access_denied", (error as OidcRequests.Callback.Error).error)
        assertNull(OidcRequests.callback("https://app.example.com/auth/other?code=c", redirect))
        assertTrue(OidcRequests.isCallback("$redirect?code=c", redirect))
        assertFalse(OidcRequests.isCallback("https://app.example.com/", redirect))
    }

    @Test
    fun tokenAnswersAreClassified() {
        assertEquals(OidcErrors.Kind.OK, OidcErrors.read(200, """{"access_token":"a","expires_in":300}""").kind)
        assertEquals(OidcErrors.Kind.GRANT_REFUSED, OidcErrors.read(400, """{"error":"invalid_grant"}""").kind)
        assertEquals(OidcErrors.Kind.CLIENT_REFUSED, OidcErrors.read(401, """{"error":"invalid_client"}""").kind)
        assertEquals(OidcErrors.Kind.CLIENT_REFUSED, OidcErrors.read(400, """{"error":"unauthorized_client"}""").kind)
        assertEquals(OidcErrors.Kind.SCOPE_REFUSED, OidcErrors.read(400, """{"error":"invalid_scope"}""").kind)
        assertEquals(OidcErrors.Kind.UNREACHABLE, OidcErrors.read(503, "").kind)
        assertEquals(OidcErrors.Kind.UNREACHABLE, OidcErrors.read(0, null).kind)
        assertEquals(OidcErrors.Kind.NOT_FOUND, OidcErrors.read(404, "").kind)
        assertEquals(OidcErrors.Kind.OTHER, OidcErrors.read(200, "{}").kind)
    }
}
