package me.river.nightbell

import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.river.nightbell.data.auth.TokenBroker
import me.river.nightbell.data.auth.TokenBroker.Access
import me.river.nightbell.data.auth.VaultEntry
import me.river.nightbell.data.check.HttpChecker
import me.river.nightbell.domain.AuthProblem
import me.river.nightbell.domain.HealthSignIn
import me.river.nightbell.domain.HealthWatch
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.SsoGrant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The broker against a fake provider on a local socket.
 *
 * Every rule here is one that, broken, either signs Nightbell out by itself
 * (a rotated token lost, a refresh spent twice) or turns its own trouble into a
 * claim about the service. Tokens are synthetic throughout.
 */
class TokenBrokerTest {

    private val clock = AtomicLong(1_800_000_000_000L)
    private val cipher = FakeCipher()
    private val vault = testVault(cipher)

    /** The token endpoint's answers, by grant type. Each call gets the form it was sent. */
    private class Provider {
        val requests = mutableListOf<Map<String, String>>()
        var answer: (Map<String, String>) -> TinyHttpServer.Response = { ok() }

        companion object {
            fun ok(access: String = "access-1", refresh: String? = "refresh-1", expiresIn: Int = 300, refreshExpiresIn: Int = 2_592_000) =
                TinyHttpServer.Response(
                    contentType = "application/json",
                    body = buildString {
                        append("""{"access_token":"$access","expires_in":$expiresIn""")
                        if (refresh != null) append(""","refresh_token":"$refresh","refresh_expires_in":$refreshExpiresIn""")
                        append(""","token_type":"Bearer","scope":"openid email profile"}""")
                    },
                )

            fun error(error: String, code: Int = 400) = TinyHttpServer.Response(
                code = code,
                reason = "Bad Request",
                contentType = "application/json",
                body = """{"error":"$error","error_description":"described $error"}""",
            )
        }
    }

    private fun form(body: String): Map<String, String> = body.split('&').filter { it.isNotEmpty() }.associate {
        URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
    }

    private fun <T> withProvider(provider: Provider, block: (TinyHttpServer, TokenBroker) -> T): T =
        TinyHttpServer { request ->
            if (request.path == "/gone") {
                TinyHttpServer.Response(code = 404, reason = "Not Found", body = "")
            } else if (request.path.endsWith("/.well-known/openid-configuration")) {
                TinyHttpServer.Response(
                    contentType = "application/json",
                    body = """{"issuer":"x","authorization_endpoint":"x/auth","token_endpoint":"http://127.0.0.1:${request.headers["host"]!!.substringAfter(':')}/moved/token"}""",
                )
            } else {
                val sent = form(request.body)
                synchronized(provider) { provider.requests += sent }
                provider.answer(sent)
            }
        }.use { server ->
            block(server, TokenBroker(vault, HttpChecker().provider, nowMs = clock::get))
        }

    private fun monitor(server: TinyHttpServer, grant: SsoGrant, path: String = "/token") = Monitor(
        id = "m1",
        kind = MonitorKind.HEALTH_ENDPOINT,
        url = server.url("/api/health"),
        timeoutSeconds = 5,
        health = HealthWatch(
            signIn = HealthSignIn.SSO,
            grant = grant,
            issuer = server.url("/realms/test"),
            clientId = "fc-admin",
            tokenEndpoint = server.url(path),
            username = "monitor",
        ),
    )

    private fun seed(entry: VaultEntry) = runBlocking { vault.write("m1", entry) }

    private fun stored(): VaultEntry = runBlocking { vault.entry("m1") }

    @Test
    fun `a cached token is used without asking the provider`() {
        val provider = Provider()
        withProvider(provider) { server, broker ->
            seed(VaultEntry(accessToken = "cached", accessExpiresAt = clock.get() + 120_000, refreshToken = "r"))

            val access = runBlocking { broker.access(monitor(server, SsoGrant.BROWSER)) }

            assertEquals("Bearer cached", (access as Access.Granted).authorization)
            assertTrue(provider.requests.isEmpty())
        }
    }

    @Test
    fun `a token is refreshed thirty seconds before it runs out, not after`() {
        val provider = Provider()
        withProvider(provider) { server, broker ->
            seed(VaultEntry(accessToken = "cached", accessExpiresAt = clock.get() + 60_000, refreshToken = "r"))
            val m = monitor(server, SsoGrant.BROWSER)

            clock.addAndGet(29_000)
            runBlocking { broker.access(m) }
            assertTrue(provider.requests.isEmpty())

            clock.addAndGet(2_000)
            val access = runBlocking { broker.access(m) }
            assertEquals("Bearer access-1", (access as Access.Granted).authorization)
            assertEquals("refresh_token", provider.requests.single()["grant_type"])
            assertFalse("a refresh sends no scope", provider.requests.single().containsKey("scope"))
        }
    }

    @Test
    fun `a rotated refresh token is stored and an unrotated one kept`() {
        val provider = Provider()
        withProvider(provider) { server, broker ->
            seed(VaultEntry(refreshToken = "old"))
            val m = monitor(server, SsoGrant.BROWSER)

            provider.answer = { Provider.ok(refresh = "rotated") }
            runBlocking { broker.access(m) }
            assertEquals("rotated", stored().refreshToken)

            provider.answer = { Provider.ok(access = "access-2", refresh = null) }
            runBlocking { broker.access(m, forceRefresh = true) }
            assertEquals("rotated", stored().refreshToken)
            assertEquals("access-2", stored().accessToken)
        }
    }

    @Test
    fun `a dead refresh token ends a browser or pasted sign-in`() {
        for (grant in listOf(SsoGrant.BROWSER, SsoGrant.PASTED)) {
            val provider = Provider().apply { answer = { Provider.error("invalid_grant") } }
            withProvider(provider) { server, broker ->
                seed(VaultEntry(refreshToken = "dead"))

                val access = runBlocking { broker.access(monitor(server, grant)) }

                assertEquals(grant.name, AuthProblem.SESSION_ENDED, (access as Access.SignInNeeded).problem)
                assertEquals("", stored().refreshToken)
            }
        }
    }

    @Test
    fun `a dead refresh token on a password sign-in signs in again with the password, once`() {
        val provider = Provider()
        withProvider(provider) { server, broker ->
            seed(VaultEntry(refreshToken = "dead", password = "pw"))
            provider.answer = { sent ->
                if (sent["grant_type"] == "refresh_token") Provider.error("invalid_grant") else Provider.ok()
            }

            val access = runBlocking { broker.access(monitor(server, SsoGrant.PASSWORD)) }

            assertTrue(access is Access.Granted)
            assertEquals(listOf("refresh_token", "password"), provider.requests.map { it["grant_type"] })
            assertEquals("monitor", provider.requests[1]["username"])
            assertTrue(provider.requests[1]["scope"]!!.split(' ').containsAll(listOf("openid", "offline_access")))
        }
    }

    @Test
    fun `a refused password is tried once and not again on the next check`() {
        val provider = Provider().apply { answer = { Provider.error("invalid_grant") } }
        withProvider(provider) { server, broker ->
            seed(VaultEntry(password = "wrong"))
            val m = monitor(server, SsoGrant.PASSWORD)

            val first = runBlocking { broker.access(m) }
            val second = runBlocking { broker.access(m) }

            assertEquals(AuthProblem.CREDENTIALS_REFUSED, (first as Access.SignInNeeded).problem)
            assertEquals(AuthProblem.CREDENTIALS_REFUSED, (second as Access.SignInNeeded).problem)
            assertEquals(1, provider.requests.size)
        }
    }

    @Test
    fun `signing in deliberately tries a refused password again`() {
        val provider = Provider().apply { answer = { Provider.error("invalid_grant") } }
        withProvider(provider) { server, broker ->
            seed(VaultEntry(password = "pw"))
            val m = monitor(server, SsoGrant.PASSWORD)
            runBlocking { broker.access(m) }

            provider.answer = { Provider.ok() }
            val access = runBlocking { broker.signIn(m, password = "pw") }

            assertTrue(access is Access.Granted)
            assertEquals(2, provider.requests.size)
        }
    }

    @Test
    fun `a refused client is credential trouble, not an outage`() {
        val provider = Provider().apply { answer = { Provider.error("invalid_client", code = 401) } }
        withProvider(provider) { server, broker ->
            seed(VaultEntry(clientSecret = "s"))

            val access = runBlocking { broker.access(monitor(server, SsoGrant.CLIENT_CREDENTIALS)) }

            assertEquals(AuthProblem.CREDENTIALS_REFUSED, (access as Access.SignInNeeded).problem)
            assertTrue(access.detail.contains("invalid_client"))
        }
    }

    @Test
    fun `refused offline access is asked for once more without it`() {
        val provider = Provider()
        withProvider(provider) { server, broker ->
            seed(VaultEntry(password = "pw"))
            provider.answer = { sent ->
                if (sent["scope"]!!.contains("offline_access")) Provider.error("invalid_scope") else Provider.ok()
            }

            val access = runBlocking { broker.access(monitor(server, SsoGrant.PASSWORD)) }

            assertTrue(access is Access.Granted)
            assertEquals(2, provider.requests.size)
            assertFalse(provider.requests[1]["scope"]!!.contains("offline_access"))
            assertFalse((access as Access.Granted).auth.offline)
        }
    }

    @Test
    fun `a provider answering 503 is the provider down`() {
        val provider = Provider().apply {
            answer = { TinyHttpServer.Response(code = 503, reason = "Service Unavailable", body = "down") }
        }
        withProvider(provider) { server, broker ->
            seed(VaultEntry(refreshToken = "r"))

            val access = runBlocking { broker.access(monitor(server, SsoGrant.BROWSER)) }

            assertTrue(access is Access.ProviderDown)
            assertEquals("r", stored().refreshToken)
        }
    }

    @Test
    fun `a provider nobody is listening on is the provider down`() {
        val provider = Provider()
        val port = java.net.ServerSocket(0).use { it.localPort }
        withProvider(provider) { server, broker ->
            seed(VaultEntry(refreshToken = "r"))
            val m = monitor(server, SsoGrant.BROWSER).let {
                it.copy(health = it.health.copy(tokenEndpoint = "http://127.0.0.1:$port/token"))
            }

            val access = runBlocking { broker.access(m) }

            assertTrue(access.toString(), access is Access.ProviderDown)
        }
    }

    @Test
    fun `two callers at once spend one refresh`() {
        val provider = Provider().apply { answer = { Provider.ok(refresh = "rotated") } }
        withProvider(provider) { server, broker ->
            seed(VaultEntry(refreshToken = "r"))
            val m = monitor(server, SsoGrant.BROWSER)

            val results = runBlocking { List(2) { async { broker.access(m) } }.awaitAll() }

            assertTrue(results.all { it is Access.Granted })
            assertEquals(1, provider.requests.size)
        }
    }

    @Test
    fun `a check cancelled after the provider answered still keeps the rotated token`() {
        val provider = Provider().apply {
            answer = { Provider.ok(refresh = "rotated").copy(delayMs = 400) }
        }
        withProvider(provider) { server, broker ->
            seed(VaultEntry(refreshToken = "old"))
            val m = monitor(server, SsoGrant.BROWSER)

            runBlocking {
                val job = launch { broker.access(m) }
                while (server.received.isEmpty()) delay(10)
                job.cancel()
                job.join()
            }

            assertEquals("rotated", stored().refreshToken)
        }
    }

    @Test
    fun `a vault that will not open reads as signed out, never as a crash`() {
        val provider = Provider()
        withProvider(provider) { server, broker ->
            seed(VaultEntry(refreshToken = "r"))
            cipher.broken = true

            val access = runBlocking { broker.access(monitor(server, SsoGrant.BROWSER)) }

            assertEquals(AuthProblem.VAULT_UNREADABLE, (access as Access.SignInNeeded).problem)
            assertTrue(provider.requests.isEmpty())
        }
    }

    @Test
    fun `a token endpoint that moved is found again once`() {
        val provider = Provider()
        withProvider(provider) { server, broker ->
            seed(VaultEntry(refreshToken = "r"))

            val access = runBlocking { broker.access(monitor(server, SsoGrant.BROWSER, path = "/gone")) }

            assertTrue(access.toString(), access is Access.Granted)
            assertTrue(stored().tokenEndpoint.endsWith("/moved/token"))
            assertEquals(listOf("/gone", "/realms/test/.well-known/openid-configuration", "/moved/token"), server.received.map { it.path })
        }
    }

    @Test
    fun `nothing is stored on a monitor that signs in with headers`() {
        val provider = Provider()
        withProvider(provider) { server, broker ->
            val m = monitor(server, SsoGrant.BROWSER).let { it.copy(health = it.health.copy(signIn = HealthSignIn.HEADERS)) }

            assertEquals(Access.Open, runBlocking { broker.access(m) })
        }
    }
}
