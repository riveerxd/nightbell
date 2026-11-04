package me.river.nightbell

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread
import okhttp3.tls.HandshakeCertificates

/**
 * A small IMAP server, real sockets, for the mailbox tests.
 *
 * It speaks only what a mailbox check sends, and it speaks it the way real
 * servers do, quirks included: `UID SEARCH UID n:*` past the last uid still
 * matches the last message, a subject with non-ASCII in it comes back as a
 * literal, and LOGIN without LITERAL+ asks for each literal with a `+`.
 * Everything received is kept in [received], so a test can assert what never
 * crossed the wire in clear.
 */
class TinyImap(
    /** Present the certificate from the first byte, port 993 style. */
    private val implicitTls: TinyTls.Identity? = null,
    /** Offer STARTTLS with this certificate. Null leaves STARTTLS out of the capabilities. */
    private val startTls: TinyTls.Identity? = null,
) : AutoCloseable {

    class Message(
        val uid: Long,
        val fromName: String?,
        val fromAddress: String,
        /** As it goes on the wire, so an RFC 2047 encoded subject can be fed in raw. */
        val subject: String,
        val received: Date,
        var seen: Boolean,
    )

    class Folder(var uidValidity: Long) {
        val messages = CopyOnWriteArrayList<Message>()
        var uidNext: Long = 1
    }

    val users = mutableMapOf("me@example.com" to "secret")
    val folders = mutableMapOf("INBOX" to Folder(1700000000))
    var authPlain = true
    var saslIr = true
    var literalPlus = true
    var loginDisabled = false
    var statusOmitsUidNext = false
    /** Replaces the greeting line, without the CRLF. */
    var greeting: String? = null
    /**
     * Untagged lines sent before the greeting. Dovecot does this while its auth
     * process is starting: "* OK Waiting for authentication process to respond.."
     * and only then the greeting that carries the capabilities.
     */
    var preamble: List<String> = emptyList()
    /** Accepts and then says nothing at all, for the timeout path. */
    var silent = false
    /** Parents listed with \Noselect, the way servers list a folder that only holds folders. */
    val noselect = mutableListOf<String>()

    /** A response code and text for every refused login. */
    var refusal = "NO [AUTHENTICATIONFAILED] Authentication failed."

    val received: MutableList<String> = Collections.synchronizedList(mutableListOf())
    @Volatile var connections = 0
        private set

    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort
    @Volatile private var closed = false

    init {
        thread(isDaemon = true, name = "tiny-imap") {
            while (!closed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                connections++
                thread(isDaemon = true, name = "tiny-imap-conn") { runCatching { serve(socket) }; runCatching { socket.close() } }
            }
        }
    }

    fun deliver(
        fromAddress: String,
        subject: String,
        fromName: String? = null,
        seen: Boolean = false,
        folder: String = "INBOX",
        at: Date = Date(),
    ): Long {
        val box = folders.getOrPut(folder) { Folder(1700000001) }
        val uid = box.uidNext++
        box.messages += Message(uid, fromName, fromAddress, subject, at, seen)
        return uid
    }

    private fun serve(raw: Socket) {
        var socket: Socket = raw
        if (implicitTls != null) socket = wrap(raw, implicitTls)
        var input = BufferedInputStream(socket.getInputStream())
        var output = socket.getOutputStream()
        if (silent) {
            Thread.sleep(30_000)
            return
        }
        var tls = implicitTls != null
        var authed = false
        var selected: Folder? = null
        fun say(line: String) = write(output, line)
        preamble.forEach(::say)
        say(greeting ?: "* OK [CAPABILITY ${caps(tls)}] TinyImap ready")
        while (true) {
            val line = readCommand(input, output) ?: return
            received += line
            val tag = line.substringBefore(' ')
            val words = tokens(line.substringAfter(' '))
            val command = words.firstOrNull()?.uppercase() ?: ""
            when {
                command == "CAPABILITY" -> {
                    say("* CAPABILITY ${caps(tls)}")
                    say("$tag OK done")
                }
                command == "STARTTLS" && startTls != null && !tls -> {
                    say("$tag OK Begin TLS")
                    socket = wrap(socket, startTls)
                    input = BufferedInputStream(socket.getInputStream())
                    output = socket.getOutputStream()
                    tls = true
                }
                command == "LOGIN" -> {
                    if (loginDisabled && !tls) {
                        say("$tag NO LOGIN is disabled")
                    } else if (users[words.getOrNull(1)] == words.getOrNull(2)) {
                        authed = true
                        say("$tag OK [CAPABILITY ${caps(tls)}] Logged in")
                    } else {
                        say("$tag $refusal")
                    }
                }
                command == "AUTHENTICATE" && words.getOrNull(1).equals("PLAIN", true) -> {
                    val token = words.getOrNull(2) ?: run {
                        say("+ ")
                        readCommand(input, output)?.also { received += it } ?: return
                    }
                    val parts = String(Base64.getDecoder().decode(token.trim()), Charsets.UTF_8).split('\u0000')
                    if (parts.size == 3 && users[parts[1]] == parts[2]) {
                        authed = true
                        say("$tag OK Logged in")
                    } else {
                        say("$tag $refusal")
                    }
                }
                command == "LOGOUT" -> {
                    say("* BYE Logging out")
                    say("$tag OK Logout completed")
                    return
                }
                !authed -> say("$tag BAD Not authenticated")
                command == "STATUS" -> {
                    val box = folders[words.getOrNull(1)]
                    if (box == null) {
                        say("$tag NO [NONEXISTENT] Mailbox doesn't exist: ${words.getOrNull(1)}")
                    } else {
                        val unseen = box.messages.count { !it.seen }
                        val next = if (statusOmitsUidNext) "" else " UIDNEXT ${box.uidNext}"
                        say("* STATUS \"${words[1]}\" (MESSAGES ${box.messages.size} UNSEEN $unseen$next UIDVALIDITY ${box.uidValidity})")
                        say("$tag OK Status completed")
                    }
                }
                command == "LIST" -> {
                    noselect.forEach { say("* LIST (\\Noselect \\HasChildren) \"/\" \"$it\"") }
                    folders.keys.forEach { name ->
                        // One sent as a literal, which Dovecot does for names with odd characters.
                        if (name.contains(' ')) {
                            say("* LIST (\\HasNoChildren) \"/\" {${name.length}}")
                            write(output, name)
                        } else {
                            say("* LIST (\\HasNoChildren) \"/\" \"$name\"")
                        }
                    }
                    say("$tag OK List completed")
                }
                command == "EXAMINE" -> {
                    val box = folders[words.getOrNull(1)]
                    if (box == null) {
                        say("$tag NO [NONEXISTENT] Mailbox doesn't exist")
                    } else {
                        selected = box
                        say("* FLAGS (\\Answered \\Flagged \\Deleted \\Seen \\Draft)")
                        say("* ${box.messages.size} EXISTS")
                        say("* 0 RECENT")
                        say("* OK [UIDVALIDITY ${box.uidValidity}] UIDs valid")
                        say("* OK [UIDNEXT ${box.uidNext}] Predicted next UID")
                        say("$tag OK [READ-ONLY] Examine completed")
                    }
                }
                command == "UID" && words.getOrNull(1).equals("SEARCH", true) -> {
                    val box = selected ?: run { say("$tag BAD No mailbox selected"); null } ?: continue
                    val uids = if (words.getOrNull(2).equals("ALL", true)) {
                        box.messages.map { it.uid }
                    } else {
                        val from = words.getOrNull(3)?.substringBefore(':')?.toLongOrNull() ?: 1
                        val hits = box.messages.map { it.uid }.filter { it >= from }
                        // RFC 3501: `*` is the largest uid in use, so n:* past it is
                        // max:n, which matches the last message. Dovecot does this.
                        hits.ifEmpty { listOfNotNull(box.messages.maxOfOrNull { it.uid }) }
                    }
                    say("* SEARCH" + uids.joinToString("") { " $it" })
                    say("$tag OK Search completed")
                }
                command == "UID" && words.getOrNull(1).equals("FETCH", true) -> {
                    val box = selected ?: run { say("$tag BAD No mailbox selected"); null } ?: continue
                    val wanted = words.getOrNull(2).orEmpty().split(',').mapNotNull { it.toLongOrNull() }.toSet()
                    box.messages.forEachIndexed { index, message ->
                        if (message.uid in wanted) writeFetch(output, index + 1, message)
                    }
                    say("$tag OK Fetch completed")
                }
                else -> say("$tag BAD Unknown command")
            }
        }
    }

    private fun caps(tls: Boolean): String = buildList {
        add("IMAP4rev1")
        if (literalPlus) add("LITERAL+")
        if (saslIr) add("SASL-IR")
        if (startTls != null && !tls) add("STARTTLS")
        if (loginDisabled && !tls) add("LOGINDISABLED")
        if (authPlain) add("AUTH=PLAIN")
    }.joinToString(" ")

    private fun writeFetch(output: OutputStream, seq: Int, message: Message) {
        val date = SimpleDateFormat("dd-MMM-yyyy HH:mm:ss Z", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(message.received)
        val out = ByteArrayOutputStream()
        fun text(s: String) = out.write(s.toByteArray(Charsets.UTF_8))
        fun astring(s: String?) {
            if (s == null) {
                text("NIL")
                return
            }
            val bytes = s.toByteArray(Charsets.UTF_8)
            if (bytes.all { it in 0x20..0x7e } && !s.contains('"') && !s.contains('\\')) {
                text("\"$s\"")
            } else {
                text("{${bytes.size}}\r\n")
                out.write(bytes)
            }
        }
        val mailbox = message.fromAddress.substringBefore('@')
        val host = message.fromAddress.substringAfter('@', "")
        text("* $seq FETCH (UID ${message.uid} FLAGS (${if (message.seen) "\\Seen" else ""}) INTERNALDATE \"$date\" ENVELOPE (\"$date\" ")
        astring(message.subject)
        text(" ((")
        astring(message.fromName)
        text(" NIL \"$mailbox\" \"$host\")) NIL NIL ((NIL NIL \"me\" \"example.com\")) NIL NIL NIL \"<${message.uid}@tiny>\"))\r\n")
        synchronized(output) {
            output.write(out.toByteArray())
            output.flush()
        }
    }

    private fun write(output: OutputStream, line: String) {
        synchronized(output) {
            output.write((line + "\r\n").toByteArray(Charsets.UTF_8))
            output.flush()
        }
    }

    /** One command, literals inlined as quoted text, or null when the client went away. */
    private fun readCommand(input: InputStream, output: OutputStream): String? {
        val out = StringBuilder()
        while (true) {
            val line = readLine(input) ?: return null
            val literal = Regex("""\{(\d+)(\+?)\}$""").find(line)
            if (literal == null) {
                out.append(line)
                return out.toString()
            }
            out.append(line.substring(0, literal.range.first))
            if (literal.groupValues[2].isEmpty()) write(output, "+ Ready for literal data")
            val n = literal.groupValues[1].toInt()
            val bytes = ByteArray(n)
            var read = 0
            while (read < n) {
                val r = input.read(bytes, read, n - read)
                if (r < 0) return null
                read += r
            }
            out.append('"').append(String(bytes, Charsets.UTF_8).replace("\\", "\\\\").replace("\"", "\\\"")).append('"')
        }
    }

    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buffer.size() == 0) null else buffer.toString(Charsets.UTF_8.name())
            if (b == '\n'.code) break
            if (b != '\r'.code) buffer.write(b)
        }
        return buffer.toString(Charsets.UTF_8.name())
    }

    /** Words, with quoted strings unquoted. Good enough for the commands above. */
    private fun tokens(text: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            when {
                text[i] == ' ' -> i++
                text[i] == '"' -> {
                    val sb = StringBuilder()
                    i++
                    while (i < text.length && text[i] != '"') {
                        if (text[i] == '\\' && i + 1 < text.length) i++
                        sb.append(text[i])
                        i++
                    }
                    i++
                    out += sb.toString()
                }
                text[i] == '(' -> {
                    val close = text.indexOf(')', i)
                    out += text.substring(i, if (close < 0) text.length else close + 1)
                    i = if (close < 0) text.length else close + 1
                }
                else -> {
                    val end = text.indexOf(' ', i).let { if (it < 0) text.length else it }
                    out += text.substring(i, end)
                    i = end
                }
            }
        }
        return out
    }

    private fun wrap(socket: Socket, identity: TinyTls.Identity): SSLSocket {
        val factory: SSLSocketFactory = HandshakeCertificates.Builder()
            .heldCertificate(identity.held)
            .build()
            .sslContext()
            .socketFactory
        val tls = factory.createSocket(socket, socket.inetAddress.hostAddress, socket.port, true) as SSLSocket
        tls.useClientMode = false
        tls.startHandshake()
        return tls
    }

    override fun close() {
        closed = true
        runCatching { server.close() }
    }
}
