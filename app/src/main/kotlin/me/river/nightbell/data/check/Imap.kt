package me.river.nightbell.data.check

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Locale
import me.river.nightbell.domain.MailMessage
import me.river.nightbell.domain.MailText

/**
 * The part of IMAP4rev1 (RFC 3501) a mailbox check needs, and no more.
 *
 * Hand written rather than Jakarta Mail, which does run on Android: a check
 * sends five commands, and a 700 KB dependency with its own R8 keep rules for
 * five commands is the wrong trade in an app that R8 has broken before. What it
 * has to get right is small and is all here: literals inside a response, quoted
 * strings, the continuation request, and the response codes that say why.
 */
internal object Imap {

    /** One parsed value from a server response. */
    sealed interface Value {
        data class Atom(val text: String) : Value

        /** A quoted string or a literal, already decoded to text. */
        data class Str(val text: String) : Value

        data object Nil : Value

        data class Items(val items: List<Value>) : Value
    }

    /** Thrown for anything that is not IMAP at all. Its message is shown to the user. */
    class ProtocolException(message: String) : IOException(message)

    /**
     * A whole response: one line, plus every literal it announced and the rest
     * of the line after each. Bytes are kept as ISO-8859-1 characters so a
     * literal's length in bytes is its length in characters.
     */
    class Reader(input: InputStream, private val maxBytes: Int = 4 * 1024 * 1024) {
        private val input = BufferedInputStream(input)

        fun readResponse(): String {
            val out = StringBuilder()
            while (true) {
                val line = readLine()
                out.append(line)
                val literal = literalAtEnd(line) ?: return out.toString()
                if (out.length + literal > maxBytes) throw ProtocolException("The server sent a response too large to read")
                out.append("\r\n")
                val bytes = ByteArray(literal)
                var read = 0
                while (read < literal) {
                    val n = input.read(bytes, read, literal - read)
                    if (n < 0) throw IOException("The server closed the connection")
                    read += n
                }
                out.append(String(bytes, Charsets.ISO_8859_1))
            }
        }

        private fun readLine(): String {
            val buffer = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) {
                    if (buffer.size() == 0) throw IOException("The server closed the connection")
                    break
                }
                if (b == '\n'.code) break
                buffer.write(b)
                if (buffer.size() > maxBytes) throw ProtocolException("The server sent a line too long to read")
            }
            val bytes = buffer.toByteArray()
            val end = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
            return String(bytes, 0, end, Charsets.ISO_8859_1)
        }

        private fun literalAtEnd(line: String): Int? {
            if (!line.endsWith("}")) return null
            val open = line.lastIndexOf('{')
            if (open < 0) return null
            return line.substring(open + 1, line.length - 1).removeSuffix("+").toIntOrNull()
        }
    }

    /**
     * Parses the values in [text], starting at [from]. Handles atoms, quoted
     * strings, literals, NIL and nested lists. Brackets are atom characters,
     * which is right for every response a check asks for.
     */
    fun parse(text: String, from: Int = 0): List<Value> {
        val parser = Parser(text, from)
        return parser.values(untilClose = false)
    }

    private class Parser(val s: String, var i: Int) {
        fun values(untilClose: Boolean): List<Value> {
            val out = mutableListOf<Value>()
            while (true) {
                skipSpace()
                if (i >= s.length) {
                    if (untilClose) throw ProtocolException("A list in the response was never closed")
                    return out
                }
                val c = s[i]
                when {
                    c == ')' -> {
                        if (!untilClose) throw ProtocolException("Unexpected ) in the response")
                        i++
                        return out
                    }
                    c == '(' -> {
                        i++
                        out += Value.Items(values(untilClose = true))
                    }
                    c == '"' -> out += Value.Str(utf8(quoted()))
                    c == '{' -> out += Value.Str(utf8(literal()))
                    else -> {
                        val atom = atom()
                        out += if (atom.equals("NIL", ignoreCase = true)) Value.Nil else Value.Atom(atom)
                    }
                }
            }
        }

        private fun skipSpace() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\r' || s[i] == '\n')) i++
        }

        private fun quoted(): String {
            val out = StringBuilder()
            i++
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    out.append(s[i + 1])
                    i += 2
                    continue
                }
                if (c == '"') {
                    i++
                    return out.toString()
                }
                out.append(c)
                i++
            }
            throw ProtocolException("A quoted string in the response was never closed")
        }

        private fun literal(): String {
            val close = s.indexOf('}', i)
            if (close < 0) throw ProtocolException("A literal in the response had no length")
            val length = s.substring(i + 1, close).removeSuffix("+").toIntOrNull()
                ?: throw ProtocolException("A literal in the response had no length")
            var start = close + 1
            if (s.startsWith("\r\n", start)) start += 2 else if (s.startsWith("\n", start)) start += 1
            if (start + length > s.length) throw ProtocolException("A literal in the response was cut short")
            i = start + length
            return s.substring(start, start + length)
        }

        private fun atom(): String {
            val start = i
            while (i < s.length && s[i] != ' ' && s[i] != '(' && s[i] != ')' && s[i] != '\r' && s[i] != '\n') i++
            return s.substring(start, i)
        }

        /** Raw 8-bit text from the wire. Most servers send UTF-8 when they send 8-bit at all. */
        private fun utf8(latin1: String): String {
            if (latin1.all { it.code < 0x80 }) return latin1
            val bytes = latin1.toByteArray(Charsets.ISO_8859_1)
            val decoder = Charsets.UTF_8.newDecoder()
            return runCatching { decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString() }.getOrElse { latin1 }
        }
    }

    /** OK, NO, BAD, BYE or PREAUTH, the response code in brackets if any, and the human text. */
    data class Status(val kind: String, val code: String, val text: String) {
        val ok: Boolean get() = kind == "OK" || kind == "PREAUTH"

        /** The first word of [code]: `AUTHENTICATIONFAILED`, `UIDNEXT`, `CAPABILITY`. */
        val codeName: String get() = code.substringBefore(' ').uppercase()

        val codeArgs: String get() = code.substringAfter(' ', "")

        companion object {
            fun parse(rest: String): Status {
                val kind = rest.substringBefore(' ').uppercase()
                var tail = rest.substringAfter(' ', "")
                var code = ""
                if (tail.startsWith("[")) {
                    val close = tail.indexOf(']')
                    if (close > 0) {
                        code = tail.substring(1, close)
                        tail = tail.substring(close + 1).trim()
                    }
                }
                return Status(kind, code, tail.trim())
            }
        }
    }

    /** What came back for one command: its tagged status and every untagged line before it. */
    data class Reply(val status: Status, val untagged: List<String>) {
        /** Untagged lines whose first word after `*` (or the number) is [name]. */
        fun data(name: String): List<String> = untagged.filter { line ->
            val words = line.removePrefix("* ").split(' ', limit = 3)
            words.firstOrNull().equals(name, ignoreCase = true) ||
                (words.size > 1 && words[0].all(Char::isDigit) && words[1].equals(name, ignoreCase = true))
        }

        /** Response codes from the untagged OKs and the tagged status, `UIDNEXT` to `"4392"`. */
        fun codes(): Map<String, String> {
            val out = mutableMapOf<String, String>()
            (untagged.map { it.removePrefix("* ") } + listOf("${status.kind} [${status.code}]"))
                .map(Status::parse)
                .filter { it.code.isNotEmpty() }
                .forEach { out[it.codeName] = it.codeArgs }
            return out
        }
    }

    /**
     * One connection's conversation. Not thread safe and not meant to be: a
     * check is one command after another.
     */
    class Session(input: InputStream, private val output: OutputStream) {
        private val reader = Reader(input)
        private var counter = 0
        var capabilities: Set<String> = emptySet()
            private set

        /** The greeting. Untagged OK, PREAUTH or BYE, or not IMAP at all. */
        fun greeting(): Status {
            val line = reader.readResponse()
            if (!line.startsWith("* ")) {
                throw ProtocolException("This is not an IMAP server. It opened with \"${line.take(60).trim()}\"")
            }
            val status = Status.parse(line.removePrefix("* "))
            if (status.kind !in setOf("OK", "PREAUTH", "BYE")) {
                throw ProtocolException("This is not an IMAP server. It opened with \"${line.take(60).trim()}\"")
            }
            learnCapabilities(status)
            return status
        }

        fun learnCapabilities(status: Status) {
            if (status.codeName == "CAPABILITY") capabilities = words(status.codeArgs)
        }

        private fun words(text: String): Set<String> = text.split(' ').filter { it.isNotBlank() }.map { it.uppercase() }.toSet()

        fun has(capability: String): Boolean = capability.uppercase() in capabilities

        /**
         * Sends one command. [parts] are joined with spaces; a [ByteArray] part
         * goes as a literal, with LITERAL+ when the server offers it and after
         * the continuation request when it does not.
         */
        fun command(vararg parts: Any): Reply {
            val tag = "n" + (++counter)
            val plus = has("LITERAL+") || has("LITERAL-")
            val line = StringBuilder(tag)
            for (part in parts) {
                line.append(' ')
                when (part) {
                    is ByteArray -> {
                        line.append('{').append(part.size).append(if (plus) "+}" else "}").append("\r\n")
                        output.write(line.toString().toByteArray(Charsets.ISO_8859_1))
                        output.flush()
                        line.clear()
                        if (!plus) {
                            val early = awaitContinuation(tag)
                            if (early != null) return early
                        }
                        output.write(part)
                    }
                    else -> line.append(part.toString())
                }
            }
            line.append("\r\n")
            output.write(line.toString().toByteArray(Charsets.ISO_8859_1))
            output.flush()
            return collect(tag)
        }

        /**
         * AUTHENTICATE PLAIN, RFC 4616. The initial response goes on the same line
         * when the server offers SASL-IR, and after an empty challenge when not.
         */
        fun authenticatePlain(user: String, password: String): Reply {
            val token = Base64.getEncoder().encodeToString("\u0000$user\u0000$password".toByteArray(Charsets.UTF_8))
            val tag = "n" + (++counter)
            if (has("SASL-IR")) {
                send("$tag AUTHENTICATE PLAIN $token")
                return finishSasl(tag)
            }
            send("$tag AUTHENTICATE PLAIN")
            val early = awaitContinuation(tag)
            if (early != null) return early
            send(token)
            return finishSasl(tag)
        }

        /** A second challenge to PLAIN is nonsense; cancel it rather than send the password again. */
        private fun finishSasl(tag: String): Reply {
            val untagged = mutableListOf<String>()
            while (true) {
                val line = reader.readResponse()
                when {
                    line.startsWith("+") -> send("*")
                    line.startsWith("* ") -> untagged += line
                    line.startsWith("$tag ") -> return Reply(Status.parse(line.substring(tag.length + 1)), untagged)
                }
            }
        }

        private fun send(line: String) {
            output.write((line + "\r\n").toByteArray(Charsets.ISO_8859_1))
            output.flush()
        }

        /** Null once the server says go on; the reply itself if it answered the command instead. */
        private fun awaitContinuation(tag: String): Reply? {
            val untagged = mutableListOf<String>()
            while (true) {
                val line = reader.readResponse()
                when {
                    line.startsWith("+") -> return null
                    line.startsWith("* ") -> untagged += line
                    line.startsWith("$tag ") -> return Reply(Status.parse(line.substring(tag.length + 1)), untagged)
                    else -> throw ProtocolException("Unexpected line from the server: ${line.take(60)}")
                }
            }
        }

        private fun collect(tag: String): Reply {
            val untagged = mutableListOf<String>()
            while (true) {
                val line = reader.readResponse()
                when {
                    line.startsWith("$tag ") -> {
                        val status = Status.parse(line.substring(tag.length + 1))
                        learnCapabilities(status)
                        return Reply(status, untagged)
                    }
                    line.startsWith("* ") -> {
                        untagged += line
                        val rest = line.removePrefix("* ")
                        if (rest.startsWith("CAPABILITY ", ignoreCase = true)) {
                            capabilities = words(rest.substringAfter(' '))
                        } else if (rest.startsWith("BYE", ignoreCase = true)) {
                            // Keep reading: a LOGOUT is answered with BYE and then its tag.
                            // Anything else that says BYE closes the socket, and the read
                            // that follows reports that.
                            learnCapabilities(Status.parse(rest))
                        }
                    }
                    line.startsWith("+") -> throw ProtocolException("The server asked for more input nobody sent")
                }
            }
        }
    }

    /**
     * An astring for a command: atom-safe text as a quoted string, anything else
     * as a literal. A password with an accent in it is the case this exists for.
     */
    fun astring(text: String): Any = if (MailText.quotable(text)) MailText.quote(text) else text.toByteArray(Charsets.UTF_8)

    /** `* STATUS "INBOX" (MESSAGES 3 UIDNEXT 9)` to `MESSAGES` to 3. */
    fun statusItems(line: String): Map<String, Long> {
        val values = parse(line.removePrefix("* "))
        val list = values.filterIsInstance<Value.Items>().lastOrNull()?.items ?: return emptyMap()
        val out = mutableMapOf<String, Long>()
        list.chunked(2).forEach { pair ->
            val key = (pair.getOrNull(0) as? Value.Atom)?.text?.uppercase() ?: return@forEach
            val value = (pair.getOrNull(1) as? Value.Atom)?.text?.toLongOrNull() ?: return@forEach
            out[key] = value
        }
        return out
    }

    /**
     * `* LIST (\HasNoChildren) "/" "INBOX"` to `INBOX`, still in modified UTF-7.
     * Null for a folder that cannot be opened (\Noselect, \NonExistent), which
     * is how servers list a parent that only holds other folders.
     */
    fun listedFolder(line: String): String? {
        val values = parse(line.removePrefix("* "))
        if ((values.firstOrNull() as? Value.Atom)?.text?.equals("LIST", ignoreCase = true) != true) return null
        val flags = (values.getOrNull(1) as? Value.Items)?.items.orEmpty()
            .mapNotNull { (it as? Value.Atom)?.text?.lowercase() }
        if ("\\noselect" in flags || "\\nonexistent" in flags) return null
        return when (val name = values.getOrNull(3)) {
            is Value.Str -> name.text
            is Value.Atom -> name.text
            else -> null
        }?.takeIf { it.isNotEmpty() }
    }

    /** `* SEARCH 4 5 9` to the numbers. */
    fun searchUids(lines: List<String>): List<Long> = lines.flatMap { line ->
        line.removePrefix("* ").split(' ').drop(1).mapNotNull { it.trim().toLongOrNull() }
    }

    /**
     * `* 12 FETCH (UID 345 FLAGS (\Seen) INTERNALDATE "..." ENVELOPE (...))` to a
     * message, or null for a FETCH that does not carry a uid (an unsolicited flag
     * update, which some servers interleave).
     */
    fun fetchMessage(line: String): MailMessage? {
        val values = parse(line.removePrefix("* "))
        val items = values.filterIsInstance<Value.Items>().firstOrNull()?.items ?: return null
        var uid: Long? = null
        var seen = false
        var received = 0L
        var envelope: List<Value>? = null
        var index = 0
        while (index + 1 < items.size) {
            val key = (items[index] as? Value.Atom)?.text?.uppercase()
            val value = items[index + 1]
            when (key) {
                "UID" -> uid = (value as? Value.Atom)?.text?.toLongOrNull()
                "FLAGS" -> seen = (value as? Value.Items)?.items
                    ?.any { (it as? Value.Atom)?.text.equals("\\Seen", ignoreCase = true) } == true
                "INTERNALDATE" -> received = internalDate((value as? Value.Str)?.text.orEmpty())
                "ENVELOPE" -> envelope = (value as? Value.Items)?.items
            }
            index += 2
        }
        val id = uid ?: return null
        val subject = (envelope?.getOrNull(1) as? Value.Str)?.text.orEmpty()
        val from = ((envelope?.getOrNull(2) as? Value.Items)?.items?.firstOrNull() as? Value.Items)?.items
        val name = (from?.getOrNull(0) as? Value.Str)?.text.orEmpty()
        val mailbox = text(from?.getOrNull(2))
        val host = text(from?.getOrNull(3))
        return MailMessage(
            uid = id,
            fromName = MailText.decodeHeader(name).trim().trim('"'),
            fromAddress = if (mailbox.isNotEmpty() && host.isNotEmpty()) "$mailbox@$host" else mailbox,
            subject = MailText.decodeHeader(subject).replace(Regex("\\s+"), " ").trim(),
            receivedAt = received,
            seen = seen,
        )
    }

    private fun text(value: Value?): String = when (value) {
        is Value.Str -> value.text
        is Value.Atom -> value.text
        else -> ""
    }

    /** `17-Jul-1996 02:44:25 -0700`, day possibly space padded. 0 when unreadable. */
    fun internalDate(text: String): Long =
        runCatching {
            SimpleDateFormat("d-MMM-yyyy HH:mm:ss Z", Locale.US).parse(text.trim())?.time ?: 0L
        }.getOrDefault(0L)
}
