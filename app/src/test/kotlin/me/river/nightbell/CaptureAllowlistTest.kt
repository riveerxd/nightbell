package me.river.nightbell

import me.river.nightbell.data.capture.CaptureRepository
import me.river.nightbell.data.capture.CapturedEndpoint
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Regression guard for "the domain disappeared after I allowlisted it".
 * Allowlisting must only annotate the captured rows; the rows may only be
 * retired once a real decrypted interface has actually been recorded.
 */
class CaptureAllowlistTest {

    @Before
    fun reset() = CaptureRepository.clear()

    private fun connect(host: String, key: String, reason: String? = "mismatch") =
        CapturedEndpoint(
            key = key,
            method = "CONNECT",
            scheme = "https",
            host = host,
            port = 443,
            path = "/",
            url = "https://$host/",
            tlsOnly = true,
            blockReason = reason,
        )

    private fun decrypted(host: String, path: String) = CapturedEndpoint(
        key = "GET https://$host:443$path",
        method = "GET",
        scheme = "https",
        host = host,
        port = 443,
        path = path,
        url = "https://$host$path",
        decrypted = true,
    )

    private fun plain(host: String, path: String) = CapturedEndpoint(
        key = "GET http://$host:80$path",
        method = "GET",
        scheme = "http",
        host = host,
        port = 80,
        path = path,
        url = "http://$host$path",
    )

    @Test
    fun `allowlisting keeps every captured row of the host`() {
        CaptureRepository.record(connect("api.test", "CONNECT https://api.test:443/ #1"))
        CaptureRepository.record(connect("api.test", "CONNECT https://api.test:443/ #2"))
        CaptureRepository.record(connect("other.test", "CONNECT https://other.test:443/ #1"))
        val before = CaptureRepository.state.value.endpoints.size

        CaptureRepository.markAllowlisted(setOf("api.test"))
        val rows = CaptureRepository.state.value.endpoints

        assertEquals(before, rows.size)
        assertEquals(2, rows.count { it.host == "api.test" && it.blockReason == "allowlisted" })
        assertEquals("mismatch", rows.first { it.host == "other.test" }.blockReason)
        assertTrueAllVisible(rows, "api.test")
    }

    @Test
    fun `allowlisting never rewrites a TLS handshake diagnostic`() {
        CaptureRepository.record(connect("api.test", "CONNECT https://api.test:443/ #1", "handshake"))
        CaptureRepository.markAllowlisted(setOf("api.test"))
        val row = CaptureRepository.state.value.endpoints.single()
        assertEquals("handshake", row.blockReason)
    }

    @Test
    fun `a decrypted interface retires placeholders but keeps diagnostics`() {
        CaptureRepository.record(connect("api.test", "CONNECT https://api.test:443/ #1"))
        CaptureRepository.markAllowlisted(setOf("api.test"))
        CaptureRepository.record(connect("api.test", "CONNECT https://api.test:443/ #9", "handshake"))
        CaptureRepository.record(connect("other.test", "CONNECT https://other.test:443/ #1"))

        CaptureRepository.record(decrypted("api.test", "/api/health"))
        val rows = CaptureRepository.state.value.endpoints

        // The domain is still there, now as a real, individually testable interface.
        assertEquals(1, rows.count { it.host == "api.test" && it.decrypted })
        assertEquals(0, rows.count { it.host == "api.test" && it.blockReason == "allowlisted" })
        // Diagnostics and other hosts are untouched.
        assertEquals(1, rows.count { it.host == "api.test" && it.blockReason == "handshake" })
        assertEquals(1, rows.count { it.host == "other.test" })
    }

    @Test
    fun `a plain HTTP request never retires a host's placeholders`() {
        CaptureRepository.record(connect("api.test", "CONNECT https://api.test:443/ #1"))
        CaptureRepository.record(plain("api.test", "/health"))
        val rows = CaptureRepository.state.value.endpoints
        assertEquals(1, rows.count { it.tlsOnly && it.host == "api.test" })
        assertEquals(1, rows.count { !it.tlsOnly && it.host == "api.test" })
    }

    private fun assertTrueAllVisible(
        rows: List<CapturedEndpoint>,
        host: String,
    ) {
        check(rows.any { it.host == host && it.url.contains(host) }) {
            "the allowlisted host must still be visible in the interface list"
        }
    }
}
