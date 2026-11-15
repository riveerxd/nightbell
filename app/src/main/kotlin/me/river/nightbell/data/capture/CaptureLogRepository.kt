package me.river.nightbell.data.capture

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/** One HTTP exchange, either seen through the proxy or sent by a test. */
@Serializable
data class RequestLogEntry(
    val id: String = UUID.randomUUID().toString(),
    val at: Long = System.currentTimeMillis(),
    /** `capture-http`, `capture-https`, `test` or `monitor`. */
    val source: String,
    val method: String,
    val url: String,
    /** `Name: value`, already stripped of credentials and hop-by-hop headers. */
    val requestHeaders: List<String> = emptyList(),
    val requestBody: String = "",
    val statusCode: Int? = null,
    val responseHeaders: List<String> = emptyList(),
    val responseBody: String = "",
    val latencyMs: Long = 0L,
    val error: String = "",
    /** True only for a request whose TLS was terminated by the local CA. */
    val decrypted: Boolean = false,
)

/**
 * A rolling request log kept in the app's private files directory.
 *
 * It is deliberately local-only and capped. Credentials are removed before an
 * entry reaches here, and bodies are truncated, so a debugging session cannot
 * quietly turn into a permanent store of somebody's tokens. The debounced write
 * keeps the proxy's worker threads off the disk on every request.
 */
object CaptureLogRepository {
    private const val MAX_ENTRIES = 200

    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow<List<RequestLogEntry>>(emptyList())
    val state: StateFlow<List<RequestLogEntry>> = _state

    private val writer = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "nightbell-capture-log").apply { isDaemon = true }
    }
    private val lock = Any()
    private var pending: ScheduledFuture<*>? = null
    @Volatile
    private var file: File? = null

    fun init(context: Context) {
        // Re-reading on every service start would clobber entries recorded
        // earlier in this process. One load per process is enough.
        if (file != null) return
        val dir = File(context.applicationContext.filesDir, "capture").apply { mkdirs() }
        val target = File(dir, "request-logs.json")
        file = target
        if (target.exists()) {
            runCatching { json.decodeFromString<List<RequestLogEntry>>(target.readText()) }
                .onSuccess { loaded ->
                    _state.value = loaded.take(MAX_ENTRIES).map { entry ->
                        entry.copy(
                            requestBody = if (isMojibake(entry.requestBody)) "" else entry.requestBody,
                            responseBody = if (isMojibake(entry.responseBody)) "" else entry.responseBody,
                        )
                    }
                }
        }
    }

    fun record(entry: RequestLogEntry) {
        _state.update { (listOf(entry) + it).take(MAX_ENTRIES) }
        schedule()
    }

    fun clear() {
        _state.value = emptyList()
        schedule()
    }

    /** Called when capture stops so the last few hundred milliseconds survive. */
    fun flush() = writeNow()

    private fun schedule() = synchronized(lock) {
        pending?.cancel(false)
        pending = writer.schedule({ writeNow() }, 400, TimeUnit.MILLISECONDS)
    }

    private fun writeNow() {
        val target = file ?: return
        runCatching {
            val tmp = File(target.parentFile, "request-logs.json.tmp")
            tmp.writeText(json.encodeToString(_state.value))
            if (!tmp.renameTo(target)) target.writeText(tmp.readText())
        }
    }
}

/**
 * Keeps a body sample printable and bounded.
 *
 * Two things used to turn a perfectly readable JSON response into mojibake:
 * compressed bytes decoded as UTF-8, and GBK/GB2312 Chinese decoded as UTF-8.
 * Both are common on intranet APIs. The Content-Encoding header is the normal
 * hint, but a caller that replayed a captured `Accept-Encoding` can be handed
 * compressed bytes with no usable header, so the magic bytes are checked too.
 */
internal fun readableBody(bytes: ByteArray, encoding: String?, limit: Int = BODY_SAMPLE_LIMIT): String {
    val hint = encoding.orEmpty().lowercase()
    val looksGzip = bytes.size >= 2 &&
        bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
    // zlib wrapper: CM must be 8 and (CMF << 8 | FLG) divisible by 31.
    val looksZlib = bytes.size >= 2 &&
        (bytes[0].toInt() and 0x0f) == 8 &&
        ((bytes[0].toInt() and 0xff) * 256 + (bytes[1].toInt() and 0xff)) % 31 == 0
    val raw = when {
        hint.contains("gzip") || looksGzip -> inflate(bytes, limit, gzip = true)
        hint.contains("deflate") || looksZlib -> inflate(bytes, limit, gzip = false)
        else -> bytes
    }
    val text = decodeText(raw, limit)
    return buildString(text.length) {
        for (char in text) {
            append(if (char == '\n' || char == '\t' || char.code >= 32) char else ' ')
        }
    }
}

private fun inflate(bytes: ByteArray, limit: Int, gzip: Boolean): ByteArray {
    val open: () -> java.io.InputStream = {
        if (gzip) {
            GZIPInputStream(ByteArrayInputStream(bytes))
        } else {
            java.util.zip.InflaterInputStream(ByteArrayInputStream(bytes))
        }
    }
    // Some servers answer `deflate` with a raw stream (no zlib wrapper).
    val rawFallback: () -> java.io.InputStream = {
        java.util.zip.InflaterInputStream(
            ByteArrayInputStream(bytes),
            java.util.zip.Inflater(true),
        )
    }
    for (factory in listOf(open, rawFallback)) {
        val out = runCatching {
            val collected = ByteArrayOutputStream()
            factory().use { input ->
                val buffer = ByteArray(4096)
                while (collected.size() < limit) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    collected.write(buffer, 0, read)
                }
            }
            collected.toByteArray()
        }.getOrNull()
        if (out != null && out.isNotEmpty()) return out
    }
    return bytes
}

/** UTF-8 when it really is UTF-8, GBK for the many legacy Chinese endpoints. */
private fun decodeText(raw: ByteArray, limit: Int): String {
    val slice = if (raw.size > limit) raw.copyOf(limit) else raw
    val strict = runCatching {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(slice))
            .toString()
    }.getOrNull()
    if (strict != null) return strict
    return runCatching { String(slice, java.nio.charset.Charset.forName("GBK")) }
        .getOrDefault(String(slice, Charsets.UTF_8))
}

private const val BODY_SAMPLE_LIMIT = 16 * 1024

/**
 * True when a stored body is binary noise (the pre-fix builds wrote compressed
 * bytes decoded as UTF-8). Such text cannot be repaired, so both log stores
 * drop it on load instead of presenting it as "the server's response".
 */
internal fun isMojibake(text: String): Boolean {
    if (text.isBlank()) return false
    var bad = 0
    for (char in text) {
        if (char == '\uFFFD' ||
            (char.code < 32 && char != '\n' && char != '\t' && char != '\r')
        ) {
            bad++
        }
    }
    return bad * 20 > text.length
}
