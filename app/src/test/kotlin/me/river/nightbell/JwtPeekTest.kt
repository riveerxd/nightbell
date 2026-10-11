package me.river.nightbell

import me.river.nightbell.domain.JwtPeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reading a pasted token to fill a form. Synthetic tokens only. */
class JwtPeekTest {

    @Test
    fun aKeycloakRefreshTokenPrefillsTheProviderAndClient() {
        val token = syntheticJwt(
            """{"iss":"https://sso.example.com/auth/realms/main","azp":"admin-app","typ":"Refresh","sub":"u-1","exp":1830000000}""",
        )

        val claims = JwtPeek.claims(token)!!

        assertEquals("https://sso.example.com/auth/realms/main", claims.issuer)
        assertEquals("admin-app", claims.authorizedParty)
        assertTrue(claims.isRefresh)
        assertFalse(claims.isOffline)
        assertEquals(1830000000L, claims.expiresAt)
    }

    @Test
    fun anOfflineTokenSaysSo() {
        val claims = JwtPeek.claims(syntheticJwt("""{"typ":"Offline"}"""))!!

        assertTrue(claims.isOffline)
        assertTrue(claims.isRefresh)
    }

    @Test
    fun anAccessTokenIsRecognisedSoItCanBeRefused() {
        val claims = JwtPeek.claims(syntheticJwt("""{"typ":"Bearer","preferred_username":"alice"}"""))!!

        assertTrue(claims.isAccess)
        assertEquals("alice", claims.account)
    }

    @Test
    fun anythingElseIsNull() {
        assertNull(JwtPeek.claims("not a token"))
        assertNull(JwtPeek.claims("a.b"))
        assertNull(JwtPeek.claims("aaa.!!!.ccc"))
        assertNull(JwtPeek.claims("aaa.${java.util.Base64.getUrlEncoder().encodeToString("[1,2]".toByteArray())}.ccc"))
    }
}
