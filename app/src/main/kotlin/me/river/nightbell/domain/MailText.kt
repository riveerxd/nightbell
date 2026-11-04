package me.river.nightbell.domain

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.Base64

/**
 * The three text encodings IMAP makes a client deal with, none of which is
 * UTF-8 on the wire.
 */
object MailText {

    private val encodedWord = Regex("""=\?([^?]+)\?([bBqQ])\?([^?]*)\?=""")

    /**
     * RFC 2047: `=?UTF-8?B?w4l0w6k=?=` to `Été`.
     *
     * Whitespace between two encoded words is dropped, because that is how a
     * long subject is folded and keeping it puts spaces in the middle of words.
     * An unknown charset or broken base64 leaves that word as it was rather than
     * losing the subject.
     */
    fun decodeHeader(raw: String): String {
        if (!raw.contains("=?")) return raw
        val out = StringBuilder()
        var cursor = 0
        var lastWasEncoded = false
        for (match in encodedWord.findAll(raw)) {
            val between = raw.substring(cursor, match.range.first)
            if (!(lastWasEncoded && between.isBlank())) out.append(between)
            val decoded = decodeWord(match.groupValues[1], match.groupValues[2], match.groupValues[3])
            if (decoded == null) {
                out.append(match.value)
                lastWasEncoded = false
            } else {
                out.append(decoded)
                lastWasEncoded = true
            }
            cursor = match.range.last + 1
        }
        out.append(raw.substring(cursor))
        return out.toString()
    }

    private fun decodeWord(charsetName: String, encoding: String, text: String): String? {
        // RFC 2231 lets a language ride on the charset: `UTF-8*cs`.
        val charset = runCatching { Charset.forName(charsetName.substringBefore('*')) }.getOrNull() ?: return null
        val bytes = when (encoding.uppercase()) {
            // The MIME decoder skips characters outside the alphabet instead of
            // failing, which turns a broken word into an empty subject.
            "B" -> if (!text.all { it.isLetterOrDigit() && it.code < 0x80 || it == '+' || it == '/' || it == '=' }) {
                return null
            } else {
                runCatching { Base64.getDecoder().decode(text) }.getOrNull() ?: return null
            }
            else -> qBytes(text)
        }
        return String(bytes, charset)
    }

    private fun qBytes(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '_' -> out.write(' '.code)
                c == '=' && i + 2 < text.length && text[i + 1].isHex() && text[i + 2].isHex() -> {
                    out.write(text.substring(i + 1, i + 3).toInt(16))
                    i += 2
                }
                else -> out.write(c.code and 0xFF)
            }
            i++
        }
        return out.toByteArray()
    }

    private fun Char.isHex(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    /**
     * A folder name as IMAP wants it: modified UTF-7, RFC 3501 section 5.1.3.
     * `Doručená pošta` becomes `Doru&AQ0-en&AOE- po&AWE-ta`.
     */
    fun encodeFolder(name: String): String {
        val out = StringBuilder()
        val pending = StringBuilder()
        fun flush() {
            if (pending.isEmpty()) return
            val bytes = pending.toString().toByteArray(Charsets.UTF_16BE)
            val b64 = Base64.getEncoder().withoutPadding().encodeToString(bytes).replace('/', ',')
            out.append('&').append(b64).append('-')
            pending.clear()
        }
        for (c in name) {
            if (c.code in 0x20..0x7E) {
                flush()
                if (c == '&') out.append("&-") else out.append(c)
            } else {
                pending.append(c)
            }
        }
        flush()
        return out.toString()
    }

    /** The other way, for names a server sends back. Broken input is returned as it came. */
    fun decodeFolder(name: String): String {
        if (!name.contains('&')) return name
        val out = StringBuilder()
        var i = 0
        while (i < name.length) {
            val c = name[i]
            if (c != '&') {
                out.append(c)
                i++
                continue
            }
            val end = name.indexOf('-', i + 1)
            if (end < 0) return name
            if (end == i + 1) {
                out.append('&')
            } else {
                val b64 = name.substring(i + 1, end).replace(',', '/')
                val bytes = runCatching { Base64.getDecoder().decode(b64.padEnd((b64.length + 3) / 4 * 4, '=')) }
                    .getOrNull() ?: return name
                out.append(String(bytes, Charsets.UTF_16BE))
            }
            i = end + 1
        }
        return out.toString()
    }

    /**
     * Whether [text] can go out as an IMAP quoted string. It cannot carry a line
     * break, a NUL or anything past ASCII; those need a literal.
     */
    fun quotable(text: String): Boolean = text.all { it.code in 1..0x7F && it != '\r' && it != '\n' }

    /** `"pa\"ss"`. Only for text [quotable] accepts. */
    fun quote(text: String): String =
        buildString {
            append('"')
            text.forEach { c ->
                if (c == '"' || c == '\\') append('\\')
                append(c)
            }
            append('"')
        }
}
