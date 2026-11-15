package me.river.nightbell.data.capture

import android.content.Context
import okhttp3.tls.HeldCertificate
import java.io.File
import java.math.BigInteger
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509KeyManager

/**
 * The root CA used only for optional, on-device HTTPS inspection.
 *
 * Nothing is hard-coded and nothing leaves the phone: the key pair is generated
 * on first use, kept in the app's private files directory, and the public
 * certificate is offered to Android's certificate installer by the user. A
 * separate leaf certificate is minted per host so the SAN matches what the
 * client asked for. Certificates are cached because RSA key generation is not
 * something to repeat on every connection.
 */
object CaptureCa {
    private const val DIR = "capture"
    private const val CA_FILE = "root-ca.pem"
    private const val EXPORT_FILE = "nightbell-ca.pem"

    private val lock = Any()
    @Volatile
    private var root: HeldCertificate? = null

    private val factories = object : LinkedHashMap<String, SSLSocketFactory>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SSLSocketFactory>?): Boolean =
            size > 32
    }

    private fun dir(context: Context): File =
        File(context.applicationContext.filesDir, DIR).apply { mkdirs() }

    fun root(context: Context): HeldCertificate = synchronized(lock) {
        root?.let { return it }
        val file = File(dir(context), CA_FILE)
        val restored = if (file.exists()) {
            runCatching { HeldCertificate.decode(file.readText()) }.getOrNull()
        } else {
            null
        }
        val ca = restored ?: HeldCertificate.Builder()
            .commonName("Nightbell Capture CA")
            .organizationalUnit("On-device debugging only")
            .certificateAuthority(0)
            .rsa2048()
            .duration(3650, TimeUnit.DAYS)
            .build()
            .also { file.writeText(it.certificatePem() + it.privateKeyPkcs8Pem()) }
        // Only the public certificate goes into the export directory. The
        // private key stays in CA_FILE, which FileProvider never exposes.
        runCatching { writeExportFile(context, ca) }
        root = ca
        ca
    }

    private fun exportDir(context: Context): File =
        File(dir(context), "export").apply { mkdirs() }

    /** Rewrites the public certificate every time. An upgrade, a cleared cache
     *  or a failed first write must never leave a missing/empty share target. */
    private fun writeExportFile(context: Context, ca: HeldCertificate): File {
        val file = File(exportDir(context), EXPORT_FILE)
        file.writeText(ca.certificatePem())
        return file
    }

    fun certificateDer(context: Context): ByteArray = root(context).certificate.encoded

    /** Public CA certificate as PEM. This is what gets written to a user-picked
     *  folder; it contains no private key and is safe to move off the device. */
    fun certificatePem(context: Context): String = root(context).certificatePem()

    /** PEM containing only the public CA certificate, safe to share/install. */
    fun exportFile(context: Context): File =
        writeExportFile(context, root(context))

    fun fingerprint(context: Context): String =
        MessageDigest.getInstance("SHA-256").digest(certificateDer(context))
            .joinToString(":") { "%02X".format(it.toInt() and 0xff) }

    /** A server-mode socket factory that presents a leaf signed by our CA. */
    fun serverFactory(context: Context, host: String): SSLSocketFactory = synchronized(lock) {
        factories[host]?.let { return it }
        val ca = root(context)
        val serial = BigInteger.valueOf((host.hashCode().toLong() and 0x7fff_ffffL) + 1L)
        val leaf = HeldCertificate.Builder()
            .commonName(host.take(64))
            .addSubjectAlternativeName(host)
            .signedBy(ca)
            .rsa2048()
            .serialNumber(serial)
            .duration(365, TimeUnit.DAYS)
            .build()
        val manager = LeafKeyManager(
            chain = arrayOf(leaf.certificate, ca.certificate),
            key = leaf.keyPair.private,
        )
        val ssl = SSLContext.getInstance("TLS")
        ssl.init(arrayOf<KeyManager>(manager), null, SecureRandom())
        val factory = ssl.socketFactory
        factories[host] = factory
        factory
    }

    /** One alias, one chain: the host was already chosen when CONNECT arrived. */
    private class LeafKeyManager(
        private val chain: Array<X509Certificate>,
        private val key: PrivateKey,
    ) : X509KeyManager {
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null

        override fun chooseClientAlias(
            keyType: Array<String>?,
            issuers: Array<out Principal>?,
            socket: java.net.Socket?,
        ): String? = null

        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
            arrayOf("nightbell")

        override fun chooseServerAlias(
            keyType: String?,
            issuers: Array<out Principal>?,
            socket: java.net.Socket?,
        ): String? = "nightbell"

        override fun getCertificateChain(alias: String?): Array<X509Certificate> = chain

        override fun getPrivateKey(alias: String?): PrivateKey = key
    }
}
