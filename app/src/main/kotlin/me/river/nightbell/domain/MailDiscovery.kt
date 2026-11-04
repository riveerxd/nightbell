package me.river.nightbell.domain

import java.io.ByteArrayOutputStream
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

/**
 * Finding a mailbox's IMAP server from an address and, optionally, a webmail
 * address, without anything changed on the server.
 *
 * The pure half: reading DNS answers and autoconfig documents, listing what to
 * try, and choosing among what answered. The half that touches the network is
 * `data/check/MailDiscoverer`.
 */
object MailDiscovery {

    /** Where a candidate came from, best first. Shown to the user, so it reads as a reason. */
    enum class Source(val reason: String, val rank: Int) {
        AUTOCONFIG("from the domain's mail settings file", 0),
        SRV("from the domain's IMAP record", 1),
        MX("from the domain's MX record", 2),
        CERTIFICATE("named by the server's certificate", 3),
        WEBMAIL("the webmail's own host", 4),
        GUESS("a usual name for the server", 5),
    }

    data class Candidate(
        val host: String,
        val port: Int,
        val security: MailSecurity,
        val source: Source,
        /** From autoconfig: `%EMAILADDRESS%`, `%EMAILLOCALPART%`, or empty for "the address". */
        val username: String = "",
    ) {
        val key: String get() = "${host.lowercase().trimEnd('.')}:$port"
    }

    /** What one candidate did when it was knocked on. No credentials are sent. */
    data class Probe(
        val candidate: Candidate,
        /** Opened, upgraded if STARTTLS, and greeted in IMAP. */
        val imap: Boolean,
        /** The chain is trusted by this phone and the certificate is for [Candidate.host]. */
        val trusted: Boolean = false,
        /** Names the certificate is for, for a host that answered under another name. */
        val certificateNames: List<String> = emptyList(),
        val note: String = "",
    )

    /** The answer, or why there is none. */
    data class Found(
        val probe: Probe?,
        /** A trusted server under another name than the one that was tried. */
        val renamedFrom: String = "",
        val tried: Int = 0,
    )

    // ---- what to try ---------------------------------------------------------

    fun domainOf(address: String): String = address.substringAfterLast('@', "").trim().trimEnd('.').lowercase()

    /** `https://rc.example.eu/?_task=mail` to `rc.example.eu`. */
    fun webmailHost(url: String): String =
        url.trim().substringAfter("://", url.trim()).substringBefore('/').substringBefore('?')
            .substringBefore('#').substringAfterLast('@').substringBefore(':').trimEnd('.').lowercase()

    /** `rc.zionbit.eu` to `zionbit.eu`. Two labels kept, which is wrong for co.uk and fine as a guess. */
    fun parentDomain(host: String): String {
        val labels = host.trimEnd('.').split('.').filter { it.isNotEmpty() }
        return if (labels.size <= 2) labels.joinToString(".") else labels.takeLast(2).joinToString(".")
    }

    fun autoconfigUrls(address: String): List<String> {
        val domain = domainOf(address)
        if (domain.isEmpty()) return emptyList()
        val q = "?emailaddress=" + java.net.URLEncoder.encode(address.trim(), "UTF-8")
        // HTTPS only. Thunderbird also tries plain http, and a settings file
        // fetched in the clear could point the password at anybody.
        return listOf(
            "https://autoconfig.$domain/mail/config-v1.1.xml$q",
            "https://$domain/.well-known/autoconfig/mail/config-v1.1.xml$q",
        )
    }

    /** Both ports for one host. */
    fun both(host: String, source: Source): List<Candidate> = listOf(
        Candidate(host, MailSecurity.TLS.defaultPort, MailSecurity.TLS, source),
        Candidate(host, MailSecurity.STARTTLS.defaultPort, MailSecurity.STARTTLS, source),
    )

    /** The guesses, after anything a record said. Webmail first, because it is a fact the user typed. */
    fun guesses(address: String, webmailUrl: String): List<Candidate> {
        val domain = domainOf(address)
        val web = webmailHost(webmailUrl)
        val out = mutableListOf<Candidate>()
        if (web.isNotEmpty()) out += both(web, Source.WEBMAIL)
        val bases = listOf(domain, parentDomain(web)).filter { it.contains('.') }.distinct()
        for (base in bases) {
            out += both("imap.$base", Source.GUESS)
            out += both("mail.$base", Source.GUESS)
        }
        return out
    }

    // ---- choosing ------------------------------------------------------------

    /**
     * The best of what answered. A certificate this phone trusts for the name is
     * worth more than where the name came from: the point is a check that will
     * pass under the default certificate rule. TLS beats STARTTLS, then source.
     */
    fun choose(probes: List<Probe>): Probe? =
        probes.filter { it.imap }
            .sortedWith(
                compareBy<Probe> { if (it.trusted) 0 else 1 }
                    .thenBy { if (it.candidate.security == MailSecurity.TLS) 0 else 1 }
                    .thenBy { it.candidate.source.rank },
            )
            .firstOrNull()

    /**
     * Names worth trying because a server answered under a name its certificate
     * is not for. `rc.zionbit.eu` presents a certificate for `mx1.zionbit.eu`,
     * and that is the name to use. Wildcards and autoconfig helper names are
     * not servers anybody signs in to.
     */
    fun renamed(probe: Probe): List<String> =
        if (probe.trusted || !probe.imap) {
            emptyList()
        } else {
            probe.certificateNames
                .map { it.trim().trimEnd('.').lowercase() }
                .filter { it.isNotEmpty() && !it.startsWith("*.") && it != probe.candidate.host.lowercase() }
                .filterNot { it.startsWith("autoconfig.") || it.startsWith("autodiscover.") }
                .distinct()
                .take(4)
        }

    /** The user name a found server wants, from the autoconfig template. */
    fun username(template: String, address: String): String = when (template.trim()) {
        "", "%EMAILADDRESS%" -> address.trim()
        "%EMAILLOCALPART%" -> address.trim().substringBefore('@')
        else -> template.replace("%EMAILADDRESS%", address.trim())
            .replace("%EMAILLOCALPART%", address.trim().substringBefore('@'))
            .replace("%EMAILDOMAIN%", domainOf(address))
    }

    // ---- autoconfig ------------------------------------------------------------

    /**
     * The IMAP servers in a Mozilla autoconfig document, in its order. Empty for
     * anything that is not one: a site that answers every path with its own
     * home page and a 200 is common, and has to read as "no file" rather than
     * as an error.
     */
    fun parseAutoconfig(xml: String): List<Candidate> {
        val text = xml.trim()
        if (!text.startsWith("<?xml") && !text.startsWith("<clientConfig")) return emptyList()
        // No DTD, no entities: a document from somebody's web server does not
        // get to make this parser fetch or expand anything.
        if (text.contains("<!DOCTYPE", ignoreCase = true) || text.contains("<!ENTITY", ignoreCase = true)) return emptyList()
        val doc = runCatching {
            DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                isExpandEntityReferences = false
            }.newDocumentBuilder().parse(InputSource(StringReader(text)))
        }.getOrNull() ?: return emptyList()
        val servers = doc.getElementsByTagName("incomingServer")
        val out = mutableListOf<Candidate>()
        for (i in 0 until servers.length) {
            val server = servers.item(i) as? Element ?: continue
            if (!server.getAttribute("type").equals("imap", ignoreCase = true)) continue
            fun child(name: String) = (server.getElementsByTagName(name).item(0)?.textContent ?: "").trim()
            val host = child("hostname")
            val port = child("port").toIntOrNull() ?: continue
            val security = when (child("socketType").uppercase()) {
                "SSL", "TLS" -> MailSecurity.TLS
                "STARTTLS" -> MailSecurity.STARTTLS
                // Plain IMAP is never offered, so a file that only lists it gives nothing.
                else -> continue
            }
            if (host.isEmpty() || port !in 1..65535) continue
            out += Candidate(host, port, security, Source.AUTOCONFIG, username = child("username"))
        }
        return out
    }

    // ---- DNS -----------------------------------------------------------------

    const val TYPE_MX = 15
    const val TYPE_SRV = 33

    data class Mx(val preference: Int, val host: String)

    data class Srv(val priority: Int, val weight: Int, val port: Int, val target: String)

    /** A standard query for one name and type, id 0, recursion desired. */
    fun dnsQuery(name: String, type: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0))
        name.trimEnd('.').split('.').filter { it.isNotEmpty() }.forEach { label ->
            val bytes = label.toByteArray(Charsets.US_ASCII)
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
        out.write(type shr 8)
        out.write(type and 0xFF)
        out.write(0)
        out.write(1)
        return out.toByteArray()
    }

    /** MX answers, lowest preference first. Empty for anything malformed. */
    fun parseMx(message: ByteArray): List<Mx> = answers(message, TYPE_MX) { at, _ ->
        Mx(u16(message, at), readName(message, at + 2).first)
    }.sortedBy { it.preference }

    /** SRV answers, lowest priority first. A target of "." means the service is not offered. */
    fun parseSrv(message: ByteArray): List<Srv> = answers(message, TYPE_SRV) { at, _ ->
        Srv(u16(message, at), u16(message, at + 2), u16(message, at + 4), readName(message, at + 6).first)
    }.filter { it.target.isNotEmpty() && it.port > 0 }.sortedWith(compareBy({ it.priority }, { -it.weight }))

    private fun <T> answers(message: ByteArray, type: Int, read: (Int, Int) -> T): List<T> = runCatching {
        if (message.size < 12) return emptyList()
        val rcode = message[3].toInt() and 0x0F
        if (rcode != 0) return emptyList()
        val questions = u16(message, 4)
        val count = u16(message, 6)
        var at = 12
        repeat(questions) { at = readName(message, at).second + 4 }
        val out = mutableListOf<T>()
        repeat(count) {
            at = readName(message, at).second
            val rrType = u16(message, at)
            val length = u16(message, at + 8)
            val data = at + 10
            if (data + length > message.size) return out
            if (rrType == type) out += read(data, length)
            at = data + length
        }
        out
    }.getOrDefault(emptyList())

    private fun u16(b: ByteArray, at: Int): Int = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    /** A name at [start], following compression pointers. Returns the name and where the field ends. */
    private fun readName(b: ByteArray, start: Int): Pair<String, Int> {
        val labels = mutableListOf<String>()
        var at = start
        var end = -1
        var jumps = 0
        while (true) {
            val len = b[at].toInt() and 0xFF
            when {
                len == 0 -> {
                    if (end < 0) end = at + 1
                    return labels.joinToString(".") to end
                }
                len and 0xC0 == 0xC0 -> {
                    if (end < 0) end = at + 2
                    at = ((len and 0x3F) shl 8) or (b[at + 1].toInt() and 0xFF)
                    if (++jumps > 32) error("a name that points at itself")
                }
                else -> {
                    labels += String(b, at + 1, len, Charsets.US_ASCII)
                    at += len + 1
                }
            }
        }
    }
}
