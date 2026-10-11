package me.river.nightbell

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import me.river.nightbell.data.auth.TokenBroker
import me.river.nightbell.data.auth.VaultEntry
import me.river.nightbell.data.check.HealthChecker
import me.river.nightbell.data.check.HttpChecker
import me.river.nightbell.domain.AuthProblem
import me.river.nightbell.domain.FailureKind
import me.river.nightbell.domain.HealthSignIn
import me.river.nightbell.domain.HealthWatch
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.SsoGrant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A signed-in health check, provider and API on one local server, through the
 * same three pieces the phone uses: [HealthChecker], [TokenBroker], [HttpChecker].
 *
 * What it holds is the line between "Nightbell's sign-in is in trouble", which
 * learns nothing about the service, and "the service refused a good token",
 * which is an outage real users would see too.
 */
class HealthEndToEndTest {

    private val vault = testVault()
    private val http = HttpChecker()

    private fun health(broker: TokenBroker) =
        HealthChecker(broker, fetch = { m, pin, auth -> http.check(m, pin, authorization = auth) })

    private fun monitor(server: TinyHttpServer) = Monitor(
        id = "m1",
        kind = MonitorKind.HEALTH_ENDPOINT,
        url = server.url("/api/health"),
        timeoutSeconds = 5,
        health = HealthWatch(
            signIn = HealthSignIn.SSO,
            grant = SsoGrant.BROWSER,
            clientId = "fc-admin",
            tokenEndpoint = server.url("/token"),
        ),
    )

    @Test
    fun `a refused token gets exactly one refresh and one retry`() {
        val refreshes = AtomicInteger()
        TinyHttpServer { request ->
            when (request.path) {
                "/token" -> {
                    val n = refreshes.incrementAndGet()
                    TinyHttpServer.Response(
                        contentType = "application/json",
                        body = """{"access_token":"fresh-$n","expires_in":300,"refresh_token":"r$n"}""",
                    )
                }
                else -> if (request.headers["authorization"] == "Bearer fresh-1") {
                    TinyHttpServer.Response(body = HealthPayloads.MINIMAL)
                } else {
                    TinyHttpServer.Response(code = 401, reason = "Unauthorized", body = "Token validation failed")
                }
            }
        }.use { server ->
            runBlocking { vault.write("m1", VaultEntry(accessToken = "revoked", accessExpiresAt = Long.MAX_VALUE, refreshToken = "r0")) }

            val outcome = runBlocking { health(TokenBroker(vault, http.provider)).check(monitor(server)) }

            assertTrue(outcome.result!!.message, outcome.result!!.ok)
            assertEquals(1, refreshes.get())
            assertEquals(2, server.received.count { it.path == "/api/health" })
        }
    }

    @Test
    fun `twice refused with a fresh token is the API refusing it, and pages`() {
        TinyHttpServer { request ->
            if (request.path == "/token") {
                TinyHttpServer.Response(contentType = "application/json", body = """{"access_token":"fresh","expires_in":300}""")
            } else {
                TinyHttpServer.Response(code = 401, reason = "Unauthorized", body = "Token validation failed")
            }
        }.use { server ->
            runBlocking { vault.write("m1", VaultEntry(accessToken = "a", accessExpiresAt = Long.MAX_VALUE, refreshToken = "r")) }

            val result = runBlocking { health(TokenBroker(vault, http.provider)).check(monitor(server)) }.result!!

            assertEquals(FailureKind.TOKEN_REFUSED, result.failureKind)
            assertEquals("Signed in, but the API refused the token", result.message)
            assertEquals("Token validation failed", result.detail)
            assertEquals(3, server.received.size)
        }
    }

    @Test
    fun `a 403 with a fresh token says a role may be missing`() {
        TinyHttpServer { request ->
            if (request.path == "/token") {
                TinyHttpServer.Response(contentType = "application/json", body = """{"access_token":"fresh","expires_in":300}""")
            } else {
                TinyHttpServer.Response(code = 403, reason = "Forbidden", body = "")
            }
        }.use { server ->
            runBlocking { vault.write("m1", VaultEntry(accessToken = "a", accessExpiresAt = Long.MAX_VALUE, refreshToken = "r")) }

            val result = runBlocking { health(TokenBroker(vault, http.provider)).check(monitor(server)) }.result!!

            assertEquals("Access refused (403)", result.message)
            assertTrue(result.advice.contains("role"))
        }
    }

    @Test
    fun `no sign-in is no verdict and nothing sent to the endpoint`() {
        TinyHttpServer { TinyHttpServer.Response(body = HealthPayloads.MINIMAL) }.use { server ->
            val outcome = runBlocking { health(TokenBroker(vault, http.provider)).check(monitor(server)) }

            assertNull(outcome.result)
            assertEquals(AuthProblem.SIGNED_OUT, outcome.auth!!.problem)
            assertTrue(server.received.isEmpty())
        }
    }

    @Test
    fun `an unreachable provider is an outage headlined as the sign-in server`() {
        TinyHttpServer { request ->
            if (request.path == "/token") {
                TinyHttpServer.Response(code = 502, reason = "Bad Gateway", body = "")
            } else {
                TinyHttpServer.Response(body = HealthPayloads.MINIMAL)
            }
        }.use { server ->
            runBlocking { vault.write("m1", VaultEntry(refreshToken = "r")) }

            val result = runBlocking { health(TokenBroker(vault, http.provider)).check(monitor(server)) }.result!!

            assertEquals(FailureKind.SIGN_IN_SERVER, result.failureKind)
            assertEquals("Sign-in server unreachable", result.message)
            assertEquals(1, server.received.size)
        }
    }

    @Test
    fun `a basic sign-in sends the stored password and nothing else`() {
        TinyHttpServer { TinyHttpServer.Response(body = HealthPayloads.MINIMAL) }.use { server ->
            runBlocking { vault.write("m1", VaultEntry(password = "s3cret")) }
            val m = monitor(server).copy(health = HealthWatch(signIn = HealthSignIn.BASIC, username = "ops"))

            val outcome = runBlocking { health(TokenBroker(vault, http.provider)).check(m) }

            assertTrue(outcome.result!!.ok)
            val expected = "Basic " + java.util.Base64.getEncoder().encodeToString("ops:s3cret".toByteArray())
            assertEquals(expected, server.received.single().headers["authorization"])
        }
    }
}
