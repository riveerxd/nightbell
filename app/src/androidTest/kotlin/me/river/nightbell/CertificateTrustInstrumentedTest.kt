package me.river.nightbell

import androidx.test.ext.junit.runners.AndroidJUnit4
import me.river.nightbell.NightbellTestSupport.appContext
import me.river.nightbell.NightbellTestSupport.resetApp
import me.river.nightbell.data.Nightbell
import me.river.nightbell.domain.CertificateWatch
import me.river.nightbell.domain.FailureKind
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.Health
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.TlsTrust
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The certificate trust modes driven through the real engine, on a device.
 *
 * [TlsTrustTest] covers the checker on the JVM, and stops where the store begins.
 * Everything here needs both: the pin is recorded by `CheckEngine` into
 * `MonitorRuntime` on the first successful check and read back out on the next
 * one, so the lifecycle only exists once there is somewhere to persist it.
 *
 * On a device rather than Robolectric for one more reason as well. Android's TLS
 * is Conscrypt, not the JVM's JSSE, and the whole trust override is a custom
 * `X509TrustManager` handed to an `SSLContext`. That either behaves the same on
 * both stacks or the feature does not work on the only stack that ships.
 */
@RunWith(AndroidJUnit4::class)
class CertificateTrustInstrumentedTest {

    @Before
    fun setUp() {
        resetApp(GlobalSettings(motionIntensity = 0f))
    }

    private fun graph() = Nightbell.install(appContext)

    private fun monitor(url: String, trust: TlsTrust, id: String = "tls") = Monitor(
        id = id,
        name = "Self-signed",
        kind = MonitorKind.HTTP_STATUS,
        url = url,
        timeoutSeconds = 20,
        tlsTrust = trust,
    )

    private fun pinOf(id: String): String = runBlocking {
        graph().store.currentSnapshot().runtimes[id]?.certPin.orEmpty()
    }

    @Test
    fun systemTrustRefusesASelfSignedCertificateOnConscrypt() {
        TinyHttpServer(TinyTls.selfSigned().serverSocketFactory()) {
            TinyHttpServer.Response(body = "ok")
        }.use { server ->
            val graph = graph()
            runBlocking {
                graph.store.upsert(monitor(server.url("/"), TlsTrust.SYSTEM))
                val result = graph.engine.run("tls")!!

                assertFalse(result.message, result.ok)
                assertEquals(FailureKind.TLS, result.failureKind)
                assertEquals("Certificate not trusted", result.message)
                // Conscrypt's own wording, which is what the reporter saw. The copy
                // above is chosen without reading it, and this is the assertion that
                // says the two really do coexist.
                assertTrue(
                    result.detail,
                    result.detail.contains("Trust anchor for certification path not found"),
                )
                // Nothing recorded from a failed handshake.
                assertEquals("", pinOf("tls"))
            }
        }
    }

    @Test
    fun anyTrustAcceptsItAndKeepsReadingTheExpiry() {
        val identity = TinyTls.selfSigned()
        TinyHttpServer(identity.serverSocketFactory()) {
            TinyHttpServer.Response(body = "ok")
        }.use { server ->
            val graph = graph()
            runBlocking {
                graph.store.upsert(monitor(server.url("/"), TlsTrust.ANY))
                val result = graph.engine.run("tls")!!

                assertTrue(result.message, result.ok)
                val runtime = graph.store.currentSnapshot().runtimes["tls"]!!
                assertEquals(Health.UP, runtime.health)
                // The expiry is still observed and still shown on the detail screen.
                assertEquals(identity.leaf.notAfter.time, runtime.certExpiresAt)
                // The alert is what is suppressed, and this is how: the level is
                // forced to UNKNOWN, so nothing escalates and any stale notice is
                // cancelled.
                assertEquals(
                    CertificateWatch.alertedLevelAfter(CertificateWatch.Level.UNKNOWN),
                    runtime.certAlertedLevel,
                )
                // Accepting anything is not pinning anything.
                assertEquals("", pinOf("tls"))
            }
        }
    }

    @Test
    fun pinnedTrustRecordsTheKeyOnFirstSuccessAndEnforcesItAfter() {
        val identity = TinyTls.selfSigned()
        val recorded: String
        TinyHttpServer(identity.serverSocketFactory()) {
            TinyHttpServer.Response(body = "ok")
        }.use { server ->
            val graph = graph()
            runBlocking {
                graph.store.upsert(monitor(server.url("/"), TlsTrust.PINNED))

                val first = graph.engine.run("tls")!!
                assertTrue(first.message, first.ok)
                recorded = pinOf("tls")
                assertTrue("expected a pin to be recorded, got <$recorded>", recorded.startsWith("sha256/"))

                // Second check, same key. Enforced now rather than armed, and it
                // passes, and it does not re-record anything.
                val second = graph.engine.run("tls")!!
                assertTrue(second.message, second.ok)
                assertEquals(recorded, pinOf("tls"))
            }
        }
    }

    @Test
    fun pinnedTrustFailsWhenTheKeyChangesAndDoesNotSilentlyRePin() {
        val original = TinyTls.selfSigned()
        val replacement = TinyTls.selfSigned()
        assertNotEquals(original.leaf.publicKey, replacement.leaf.publicKey)

        val graph = graph()
        val recorded: String
        // Arm the pin against the first key.
        TinyHttpServer(original.serverSocketFactory()) {
            TinyHttpServer.Response(body = "ok")
        }.use { server ->
            runBlocking {
                graph.store.upsert(monitor(server.url("/"), TlsTrust.PINNED))
                assertTrue(graph.engine.run("tls")!!.ok)
                recorded = pinOf("tls")
            }
        }
        assertTrue(recorded.startsWith("sha256/"))

        // The same monitor, a server on the same port with a different key. A port
        // collision is unlikely enough to ignore and the URL is rewritten anyway.
        TinyHttpServer(replacement.serverSocketFactory()) {
            TinyHttpServer.Response(body = "ok")
        }.use { server ->
            runBlocking {
                val stored = graph.store.currentSnapshot().monitors.single()
                graph.store.upsert(stored.copy(url = server.url("/")))

                val result = graph.engine.run("tls")!!
                assertFalse(result.message, result.ok)
                assertEquals(FailureKind.TLS, result.failureKind)
                assertEquals("The certificate key changed", result.message)
                // The whole point. A failed check must not adopt the key that
                // failed it, or the pin quietly follows whatever answers and
                // protects nothing.
                assertEquals(recorded, pinOf("tls"))
                assertEquals(Health.DOWN, graph.store.currentSnapshot().runtimes["tls"]!!.health)
            }
        }
    }

    @Test
    fun trustingTheNewKeyReArmsThePin() {
        val original = TinyTls.selfSigned()
        val replacement = TinyTls.selfSigned()
        val graph = graph()

        TinyHttpServer(original.serverSocketFactory()) {
            TinyHttpServer.Response(body = "ok")
        }.use { server ->
            runBlocking {
                graph.store.upsert(monitor(server.url("/"), TlsTrust.PINNED))
                assertTrue(graph.engine.run("tls")!!.ok)
            }
        }
        val first = pinOf("tls")
        assertTrue(first.startsWith("sha256/"))

        // The documented way out, and the one the failure message names. Without it
        // a pin is a dead end: a certificate renewed on purpose would leave no move
        // except deleting the monitor and building it again.
        runBlocking { graph.engine.repinCertificate("tls") }
        assertEquals("", pinOf("tls"))

        TinyHttpServer(replacement.serverSocketFactory()) {
            TinyHttpServer.Response(body = "ok")
        }.use { server ->
            runBlocking {
                val stored = graph.store.currentSnapshot().monitors.single()
                graph.store.upsert(stored.copy(url = server.url("/")))
                val result = graph.engine.run("tls")!!
                assertTrue(result.message, result.ok)
            }
        }
        val second = pinOf("tls")
        assertTrue(second.startsWith("sha256/"))
        assertNotEquals(first, second)
    }

    @Test
    fun theReportedShapeIsFixedByChoosingAMode() {
        // Issue #6 end to end: an http monitor, a redirect to https, a
        // self-signed certificate at the other end. Under SYSTEM it fails and
        // explains both halves; under PINNED it works.
        val identity = TinyTls.selfSigned()
        TinyHttpServer(identity.serverSocketFactory()) {
            TinyHttpServer.Response(body = "the real page")
        }.use { secure ->
            TinyHttpServer { request ->
                TinyHttpServer.Response(
                    code = 301,
                    reason = "Moved Permanently",
                    extraHeaders = mapOf("Location" to secure.url(request.path)),
                )
            }.use { plain ->
                val graph = graph()
                runBlocking {
                    graph.store.upsert(monitor(plain.url("/"), TlsTrust.SYSTEM))
                    val failed = graph.engine.run("tls")!!

                    assertFalse(failed.message, failed.ok)
                    assertEquals(FailureKind.TLS, failed.failureKind)
                    // The sentence that was missing from the report.
                    assertTrue(failed.detail, failed.detail.contains("This monitor's URL is http"))
                    assertTrue(failed.detail, failed.detail.contains("Follow redirects"))

                    val stored = graph.store.currentSnapshot().monitors.single()
                    graph.store.upsert(stored.copy(tlsTrust = TlsTrust.PINNED))
                    val passed = graph.engine.run("tls")!!

                    assertTrue(passed.message, passed.ok)
                    assertEquals("the real page", passed.bodyPreview)
                    assertTrue(pinOf("tls").startsWith("sha256/"))
                }
            }
        }
    }
}
