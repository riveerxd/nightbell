package me.river.nightbell

import me.river.nightbell.data.check.HttpChecker
import me.river.nightbell.domain.FailureKind
import me.river.nightbell.domain.Reachability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Telling a refused certificate apart from a socket that died under one.
 *
 * Conscrypt reports both as an SSLException, and classifying both as TLS meant
 * an https monitor on a phone with no signal reported a failure kind that says
 * the network works. The reachability probe is never spent on those, so every
 * https monitor paged from an underground car park.
 */
class TlsDroppedConnectionTest {

    private val checker = HttpChecker()

    @Test
    fun `a connection reset under the handshake is a connection failure`() {
        val error = SSLException(
            "Read error: ssl=0x0: I/O error during system call, Connection reset by peer",
            SocketException("Connection reset by peer"),
        )
        assertEquals(FailureKind.CONNECT, checker.classify(error))
        assertTrue(Reachability.neverReachedAnything(checker.classify(error)))
    }

    @Test
    fun `a handshake that timed out is a connection failure`() {
        val error = SSLHandshakeException("Read error")
        error.initCause(SocketTimeoutException("timeout"))
        assertEquals(FailureKind.CONNECT, checker.classify(error))
    }

    @Test
    fun `a truncated handshake is a connection failure`() {
        val error = SSLException("Connection closed by peer", EOFException("\\n not found"))
        assertEquals(FailureKind.CONNECT, checker.classify(error))
    }

    @Test
    fun `an untrusted chain is still a certificate failure`() {
        val error = SSLHandshakeException("Trust anchor for certification path not found")
        error.initCause(CertificateException("no trust anchor", CertPathValidatorException("PKIX failed")))
        assertEquals(FailureKind.TLS, checker.classify(error))
    }

    @Test
    fun `a certificate failure with a dead socket underneath is still a certificate failure`() {
        // Order matters: the certificate exception wins wherever it sits in the
        // chain, because reaching a certificate at all proves packets moved.
        val error = SSLHandshakeException("handshake failed")
        error.initCause(CertPathValidatorException("PKIX failed", SocketException("reset")))
        assertEquals(FailureKind.TLS, checker.classify(error))
    }

    @Test
    fun `an unverified peer is still a certificate failure`() {
        assertEquals(
            FailureKind.TLS,
            checker.classify(SSLPeerUnverifiedException("Hostname not verified")),
        )
    }

    @Test
    fun `a bare SSL failure with nothing underneath stays a certificate failure`() {
        // No cause to read, so nothing is claimed. TLS pages, which is the
        // direction to fail in.
        assertEquals(FailureKind.TLS, checker.classify(SSLException("protocol error")))
    }
}
