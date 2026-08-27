package me.river.nightbell.data.check

import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import me.river.nightbell.domain.TlsTrust
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient

/**
 * Turns a [TlsTrust] into what OkHttp needs to enforce it.
 *
 * ## Why the pin is checked here and not by CertificatePinner
 *
 * OkHttp has a perfectly good pinner, and it is the wrong tool for this. It runs
 * on a *cleaned* chain, and the cleaner works by building a path back to a trusted
 * root. A self-signed certificate has no such path, so the cleaner rejects it
 * before the pinner is ever consulted, and the pin never gets to be the thing that
 * decides.
 *
 * Which is the crux of pinning a self-signed endpoint: path validation has to be
 * switched off for the certificate to be seen at all, and the pin then has to be
 * what replaces it. Two separate mechanisms cannot express that. One trust manager
 * can, and it is the shorter code as well.
 *
 * [CertificatePinner.pin] is still used, for the string format only. It is the
 * `sha256/…` spelling everyone else uses, hashes the public key rather than the
 * whole certificate, and getting that wrong by hand is exactly the kind of mistake
 * that stays invisible until a renewal breaks a monitor.
 *
 * ## Why the certificate is recorded here too
 *
 * Because there is nowhere else to read it. Under a custom trust manager OkHttp's
 * `Response.handshake` reports `peerCertificates` as an empty list: it computes
 * them lazily through the same chain cleaner, which has no trusted roots to build
 * a path to, so it yields nothing. The trust manager, meanwhile, is handed the
 * real chain the server sent. Measured, not assumed, and it is why the expiry and
 * the pin come from [Session] rather than from the response for these two modes.
 *
 * A fresh [SSLSocketFactory] per check is load-bearing for that. OkHttp's route
 * equality includes the socket factory, so a new instance means a pooled
 * connection is never reused across two checks, which means the handshake really
 * happens and the trust manager really runs. Those monitors give up keep-alive
 * between checks, which costs nothing when checks are minutes apart.
 *
 * ## Why hostname verification goes too
 *
 * Under [TlsTrust.PINNED] the key is the identity, so the name on the certificate
 * has nothing left to prove. It also usually does not match: a box on
 * `192.168.1.20` presents a certificate for `nas.local`, and a hidden service
 * presents whatever its operator typed. Keeping the hostname check would refuse the
 * exact endpoints this mode exists to reach, while adding nothing the pin is not
 * already providing.
 */
internal object TlsTrustConfig {

    /**
     * Thrown when a pinned monitor is answered by a different key.
     *
     * Its own type so the failure can be reported as what it is. Through JSSE it
     * arrives wrapped in an `SSLHandshakeException`, hence [pinMismatch].
     */
    class PinMismatch(val expected: String, val actual: String) : CertificateException(
        "Pinned key does not match: expected $expected, got $actual",
    )

    /**
     * What one check's trust manager saw.
     *
     * Null from [apply] under [TlsTrust.SYSTEM], where nothing is overridden and
     * the certificate is read from the response the way it always was.
     */
    class Session {

        @Volatile
        var leaf: X509Certificate? = null
            internal set

        /** The `sha256/…` pin of what answered, or empty if nothing did. */
        val pin: String get() = pinOf(leaf)
    }

    /**
     * Applies [trust] to a client builder.
     *
     * [pin] is the key already recorded for this monitor, empty on the first check
     * under [TlsTrust.PINNED]. Empty means "accept, and let the caller record what
     * answered", which is the first half of trust on first use.
     *
     * [TlsTrust.SYSTEM] touches nothing at all, and returns no session. Not
     * "installs the platform default again": a client that has never had its socket
     * factory overridden keeps Android's own, including the session cache and
     * whatever the platform does about newer protocol versions, none of which
     * should be re-implemented here for the mode that wants the default.
     */
    fun apply(
        builder: OkHttpClient.Builder,
        trust: TlsTrust,
        pin: String,
    ): Session? {
        if (trust == TlsTrust.SYSTEM) return null
        val session = Session()
        val manager = when {
            trust == TlsTrust.PINNED && pin.isNotBlank() -> PinnedTo(pin, session)
            else -> AcceptAny(session)
        }
        builder.sslSocketFactory(socketFactory(manager), manager)
        // Every mode that gets here has already given up path validation, so the
        // name has nothing left to prove. See the class comment.
        builder.hostnameVerifier { _, _ -> true }
        return session
    }

    /** The `sha256/…` pin for a certificate, or empty when there is none to read. */
    fun pinOf(certificate: X509Certificate?): String =
        certificate?.let { CertificatePinner.pin(it) } ?: ""

    /** The mismatch itself, for the message, or null when [error] is not one. */
    fun pinMismatch(error: Throwable): PinMismatch? =
        generateSequence(error) { if (it.cause === it) null else it.cause }
            .filterIsInstance<PinMismatch>()
            .firstOrNull()

    private fun socketFactory(manager: X509TrustManager): SSLSocketFactory =
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(manager), java.security.SecureRandom())
        }.socketFactory

    /**
     * Accepts any chain, and remembers the leaf.
     *
     * Used by [TlsTrust.ANY], and by [TlsTrust.PINNED] on the one check that has no
     * pin to compare against yet.
     */
    private class AcceptAny(private val session: Session) : X509TrustManager {

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            session.leaf = chain?.firstOrNull()
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /** Accepts exactly one public key, whatever else is wrong with the chain. */
    private class PinnedTo(
        private val expected: String,
        private val session: Session,
    ) : X509TrustManager {

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull()
                ?: throw CertificateException("The server presented no certificate")
            // Recorded before the comparison, so a rejected certificate is still
            // available to say what was actually offered.
            session.leaf = leaf
            val actual = CertificatePinner.pin(leaf)
            if (actual != expected) throw PinMismatch(expected, actual)
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
