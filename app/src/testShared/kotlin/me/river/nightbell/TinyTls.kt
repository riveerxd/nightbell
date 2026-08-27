package me.river.nightbell

import java.security.cert.X509Certificate
import javax.net.ServerSocketFactory
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate

/**
 * Throwaway certificates for the TLS trust tests.
 *
 * Generated per run rather than checked in. A keystore in the repository would
 * work today and then expire on some morning years from now, failing a suite
 * nobody had touched, with no instructions anywhere for regenerating it. These
 * last ten minutes and are gone when the JVM exits.
 *
 * Every certificate here is self-signed and issued for `localhost`, which is the
 * shape of what issue #6 is about: an endpoint whose certificate is real, matches
 * the host, and has no CA behind it. That is a NAS, a homelab box, a printer, and
 * a Tor hidden service, where no CA will ever issue at all.
 */
object TinyTls {

    /** A server's key material, and the socket factory that presents it. */
    class Identity(val held: HeldCertificate) {

        val leaf: X509Certificate get() = held.certificate

        private val handshake: HandshakeCertificates =
            HandshakeCertificates.Builder()
                .heldCertificate(held)
                .build()

        /** Hand this to [TinyHttpServer] to make it serve HTTPS. */
        fun serverSocketFactory(): ServerSocketFactory =
            handshake.sslContext().serverSocketFactory
    }

    /**
     * A fresh self-signed identity for `localhost`.
     *
     * Two calls give two different keys, which is what the pin-mismatch test
     * needs: the same hostname, the same validity, and a key that a pin recorded
     * from the first identity cannot match.
     */
    fun selfSigned(): Identity = Identity(
        HeldCertificate.Builder()
            .commonName("Nightbell test")
            .addSubjectAlternativeName("localhost")
            .build(),
    )
}
