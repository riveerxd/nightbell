package me.river.nightbell.data.check

import android.net.DnsResolver
import android.os.Build
import android.os.CancellationSignal
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import me.river.nightbell.domain.MailDiscovery
import me.river.nightbell.domain.MailDiscovery.Candidate
import me.river.nightbell.domain.MailDiscovery.Probe
import me.river.nightbell.domain.MailDiscovery.Source
import me.river.nightbell.domain.MailSecurity
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.internal.tls.OkHostnameVerifier

/**
 * Finds where an address's mail is read from. No credentials leave the phone
 * here: every candidate is only connected to, greeted, and has its certificate
 * looked at. The password goes once, to the winner, when setup runs Test.
 *
 * Order of evidence: the domain's autoconfig file, its SRV records, its MX
 * records, names a server's certificate gives for itself, then the webmail's
 * host and the usual `imap.` and `mail.` guesses.
 */
class MailDiscoverer(
    private val dns: Dns = Dns.platform(),
    private val fetch: Fetch = Fetch.okhttp(),
    private val prober: Prober = Prober.Imap(),
    private val budgetMs: Long = 15_000,
) {

    /** Raw DNS: one query out, the answer message back, or null. */
    fun interface Dns {
        suspend fun query(name: String, type: Int): ByteArray?

        companion object {
            /** Android's own resolver, which only takes raw queries from API 29. Older phones skip DNS. */
            fun platform(): Dns = Dns { name, type ->
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@Dns null
                withTimeoutOrNull(5_000) { rawQuery(name, type) }
            }

            @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
            private suspend fun rawQuery(name: String, type: Int): ByteArray? = suspendCancellableCoroutine { cont ->
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                runCatching {
                    DnsResolver.getInstance().rawQuery(
                        null,
                        MailDiscovery.dnsQuery(name, type),
                        DnsResolver.FLAG_EMPTY,
                        dnsExecutor,
                        signal,
                        object : DnsResolver.Callback<ByteArray> {
                            override fun onAnswer(answer: ByteArray, rcode: Int) {
                                if (cont.isActive) cont.resume(answer)
                            }

                            override fun onError(error: DnsResolver.DnsException) {
                                if (cont.isActive) cont.resume(null)
                            }
                        },
                    )
                }.onFailure { if (cont.isActive) cont.resume(null) }
            }

            private val dnsExecutor = Executors.newCachedThreadPool()
        }
    }

    /** An HTTPS GET of a small document, or null for anything but a 200. */
    fun interface Fetch {
        suspend fun get(url: String): String?

        companion object {
            fun okhttp(): Fetch {
                val client = OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.SECONDS)
                    .callTimeout(8, TimeUnit.SECONDS)
                    // A settings file that redirects to plain http is not trusted.
                    .followSslRedirects(false)
                    .build()
                return Fetch { url ->
                    runInterruptible(Dispatchers.IO) {
                        runCatching {
                            client.newCall(Request.Builder().url(url).header("Accept", "application/xml, text/xml").build())
                                .execute().use { response ->
                                    if (response.code != 200) return@use null
                                    val body = response.body
                                    if (body.contentLength() > MAX_DOCUMENT) return@use null
                                    body.source().readUtf8(MAX_DOCUMENT).takeIf { it.length < MAX_DOCUMENT }
                                }
                        }.getOrNull()
                    }
                }
            }

            private const val MAX_DOCUMENT = 64L * 1024
        }
    }

    /** Knocks on one candidate. Never signs in. */
    fun interface Prober {
        suspend fun probe(candidate: Candidate, timeoutMs: Int): Probe

        /** The real one: connect, upgrade for STARTTLS, read the greeting, look at the certificate. */
        class Imap : Prober {
            override suspend fun probe(candidate: Candidate, timeoutMs: Int): Probe =
                runInterruptible(Dispatchers.IO) { knock(candidate, timeoutMs) }

            private fun knock(candidate: Candidate, timeoutMs: Int): Probe {
                var socket: Socket? = null
                return try {
                    val plain = Socket().apply {
                        soTimeout = timeoutMs
                        connect(InetSocketAddress(candidate.host, candidate.port), timeoutMs)
                    }
                    socket = plain
                    val recorder = Recording(platformTrust(), candidate.host)
                    val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(recorder), SecureRandom()) }
                    fun upgrade(): SSLSocket =
                        (context.socketFactory.createSocket(plain, candidate.host, candidate.port, true) as SSLSocket)
                            .also { it.startHandshake() }
                    val session: me.river.nightbell.data.check.Imap.Session
                    if (candidate.security == MailSecurity.TLS) {
                        val tls = upgrade()
                        socket = tls
                        session = me.river.nightbell.data.check.Imap.Session(tls.inputStream, tls.outputStream)
                        if (session.greeting().kind == "BYE") return Probe(candidate, imap = false, note = "turned away")
                    } else {
                        val first = me.river.nightbell.data.check.Imap.Session(plain.inputStream, plain.outputStream)
                        if (first.greeting().kind == "BYE") return Probe(candidate, imap = false, note = "turned away")
                        if (first.capabilities.isEmpty()) first.command("CAPABILITY")
                        if (!first.has("STARTTLS")) return Probe(candidate, imap = false, note = "no STARTTLS")
                        if (!first.command("STARTTLS").status.ok) return Probe(candidate, imap = false, note = "STARTTLS refused")
                        val tls = upgrade()
                        socket = tls
                        session = me.river.nightbell.data.check.Imap.Session(tls.inputStream, tls.outputStream)
                    }
                    runCatching { session.command("LOGOUT") }
                    val leaf = recorder.chain.firstOrNull()
                    val names = leaf?.subjectAlternativeNames
                        ?.filter { it.getOrNull(0) == 2 }
                        ?.mapNotNull { it.getOrNull(1)?.toString() }
                        .orEmpty()
                    val nameMatches = leaf != null && OkHostnameVerifier.verify(candidate.host, leaf)
                    Probe(
                        candidate = candidate,
                        imap = true,
                        trusted = recorder.trusted && nameMatches,
                        certificateNames = names,
                        note = when {
                            !recorder.trusted -> "certificate not trusted by this phone"
                            !nameMatches -> "certificate is for another name"
                            else -> ""
                        },
                    )
                } catch (error: Exception) {
                    Probe(candidate, imap = false, note = error.javaClass.simpleName)
                } finally {
                    runCatching { socket?.close() }
                }
            }

            private fun platformTrust(): X509TrustManager =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                    .apply { init(null as KeyStore?) }
                    .trustManagers.filterIsInstance<X509TrustManager>().first()

            /**
             * Asks the platform whether it trusts the chain and remembers the
             * answer instead of failing the handshake, so a self-signed server
             * still says which names it is for.
             */
            private class Recording(private val platform: X509TrustManager, private val host: String) : X509TrustManager {
                @Volatile var chain: List<X509Certificate> = emptyList()
                @Volatile var trusted = false

                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    this.chain = chain?.toList().orEmpty()
                    trusted = trusts(chain?.let { arrayOf(*it) } ?: emptyArray(), authType.orEmpty())
                }

                /**
                 * Hostname aware on Android, and that is not optional here: the
                 * app's network security config has a domain-config, and with one
                 * present the platform refuses the two-argument check outright, so
                 * every certificate read as untrusted on a phone while the JVM
                 * suite passed. Falls back to the plain check where the Android
                 * class is only a stub, which is the JVM.
                 */
                private fun trusts(chain: Array<X509Certificate>, authType: String): Boolean =
                    try {
                        android.net.http.X509TrustManagerExtensions(platform).checkServerTrusted(chain, authType, host)
                        true
                    } catch (refused: java.security.cert.CertificateException) {
                        false
                    } catch (stub: RuntimeException) {
                        runCatching { platform.checkServerTrusted(chain, authType) }.isSuccess
                    }

                override fun getAcceptedIssuers(): Array<X509Certificate> = platform.acceptedIssuers
            }
        }
    }

    suspend fun discover(address: String, webmailUrl: String): MailDiscovery.Found =
        withTimeoutOrNull(budgetMs) { search(address, webmailUrl) } ?: MailDiscovery.Found(null)

    private suspend fun search(address: String, webmailUrl: String): MailDiscovery.Found = coroutineScope {
        val domain = MailDiscovery.domainOf(address)
        val autoconfig = async {
            MailDiscovery.autoconfigUrls(address)
                .map { url -> async { fetch.get(url)?.let(MailDiscovery::parseAutoconfig).orEmpty() } }
                .awaitAll().flatten()
        }
        val srv = async {
            if (domain.isEmpty()) emptyList() else {
                val tls = dns.query("_imaps._tcp.$domain", MailDiscovery.TYPE_SRV)?.let(MailDiscovery::parseSrv).orEmpty()
                    .map { Candidate(it.target, it.port, MailSecurity.TLS, Source.SRV) }
                val starttls = dns.query("_imap._tcp.$domain", MailDiscovery.TYPE_SRV)?.let(MailDiscovery::parseSrv).orEmpty()
                    .map { Candidate(it.target, it.port, MailSecurity.STARTTLS, Source.SRV) }
                tls + starttls
            }
        }
        val mx = async {
            if (domain.isEmpty()) emptyList() else {
                dns.query(domain, MailDiscovery.TYPE_MX)?.let(MailDiscovery::parseMx).orEmpty()
                    .take(2).flatMap { MailDiscovery.both(it.host, Source.MX) }
            }
        }
        val candidates = (autoconfig.await() + srv.await() + mx.await() + MailDiscovery.guesses(address, webmailUrl))
            .distinctBy { it.key }
            .take(MAX_CANDIDATES)
        val first = probeAll(candidates)
        val seen = candidates.map { it.key }.toMutableSet()
        // A host that answered under a name its certificate does not carry, like a
        // webmail host on the same box as the mail server: try the names it gave.
        val origin = mutableMapOf<String, String>()
        val renamed = first.flatMap { probe ->
            MailDiscovery.renamed(probe).map { name ->
                Candidate(name, probe.candidate.port, probe.candidate.security, Source.CERTIFICATE).also {
                    origin.putIfAbsent(it.key, probe.candidate.host)
                }
            }
        }.filter { seen.add(it.key) }.take(4)
        val all = first + probeAll(renamed)
        val best = MailDiscovery.choose(all)
        MailDiscovery.Found(
            probe = best,
            renamedFrom = best?.let { origin[it.candidate.key] }.orEmpty(),
            tried = all.size,
        )
    }

    private suspend fun probeAll(candidates: List<Candidate>): List<Probe> = coroutineScope {
        candidates.map { c -> async { prober.probe(c, PROBE_TIMEOUT_MS) } }.awaitAll()
    }

    private companion object {
        const val MAX_CANDIDATES = 16
        const val PROBE_TIMEOUT_MS = 5_000
    }
}
