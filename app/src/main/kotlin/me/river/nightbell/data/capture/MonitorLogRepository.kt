package me.river.nightbell.data.capture

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.river.nightbell.domain.CheckResult
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The request/response evidence behind a monitor's checks.
 *
 * [Sample] is deliberately a summary — one line per check, kept for uptime and
 * sparklines. The parts somebody actually needs when an interface misbehaves
 * (status, latency, the server's message, the body preview) were thrown away
 * after the notification was decided. This keeps a short, bounded history per
 * monitor in its own file, so a busy 1-second monitor cannot flush the capture
 * session's 200-entry log out of existence.
 */
@Serializable
data class MonitorLogEntry(
    val id: String = UUID.randomUUID().toString(),
    val monitorId: String,
    val at: Long = System.currentTimeMillis(),
    val ok: Boolean,
    val statusCode: Int = 0,
    val latencyMs: Long = 0L,
    val message: String = "",
    val detail: String = "",
    val bodyPreview: String = "",
    /** The exchange captured for this monitor, when there is one. Credentials
     *  have already been stripped before these fields are ever filled. */
    val method: String = "",
    val url: String = "",
    val requestHeaders: List<String> = emptyList(),
    val requestBody: String = "",
    val responseHeaders: List<String> = emptyList(),
    val responseBody: String = "",
    /** `check` for a scheduled/manual run, `capture` for a first check from the capture screen. */
    val source: String = "check",
)

object MonitorLogRepository {
    private const val PER_MONITOR = 20
    private const val MAX_TOTAL = 400
    private const val MAX_TEXT = 16 * 1024

    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow<List<MonitorLogEntry>>(emptyList())
    val state: StateFlow<List<MonitorLogEntry>> = _state

    private val writer = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "nightbell-monitor-log").apply { isDaemon = true }
    }
    private val lock = Any()
    private var pending: ScheduledFuture<*>? = null
    @Volatile
    private var file: File? = null

    /** Called from Application.onCreate: checks also run in processes with no UI. */
    fun init(context: Context) {
        if (file != null) return
        val dir = File(context.applicationContext.filesDir, "capture").apply { mkdirs() }
        val target = File(dir, "monitor-logs.json")
        file = target
        if (target.exists()) {
            runCatching { json.decodeFromString<List<MonitorLogEntry>>(target.readText()) }
                .onSuccess { loaded ->
                    // Older builds stored compressed bytes decoded as UTF-8. They
                    // cannot be recovered, and showing them as "the response" is
                    // worse than showing nothing while new checks fill the list.
                    _state.value = loaded.take(MAX_TOTAL).map { entry ->
                        val body = entry.bodyPreview
                        val response = entry.responseBody
                        if (isMojibake(body) || isMojibake(response)) {
                            entry.copy(
                                bodyPreview = if (isMojibake(body)) "" else body,
                                responseBody = if (isMojibake(response)) "" else response,
                            )
                        } else {
                            entry
                        }
                    }
                }
        }
    }

    fun forMonitor(id: String, entries: List<MonitorLogEntry> = _state.value): List<MonitorLogEntry> =
        entries.filter { it.monitorId == id }

    fun record(entry: MonitorLogEntry) {
        _state.update { current ->
            val kept = ArrayList<MonitorLogEntry>(minOf(current.size + 1, MAX_TOTAL))
            kept.add(entry)
            var forThisMonitor = 1
            for (old in current) {
                if (old.monitorId == entry.monitorId) {
                    if (forThisMonitor >= PER_MONITOR) continue
                    forThisMonitor++
                }
                kept.add(old)
                if (kept.size >= MAX_TOTAL) break
            }
            kept
        }
        schedule()
    }

    fun recordCheck(monitorId: String, result: CheckResult) {
        record(
            MonitorLogEntry(
                monitorId = monitorId,
                at = result.at.takeIf { it > 0L } ?: System.currentTimeMillis(),
                ok = result.ok,
                statusCode = result.statusCode,
                latencyMs = result.latencyMs,
                message = result.message.take(MAX_TEXT),
                detail = result.detail.take(MAX_TEXT),
                bodyPreview = result.bodyPreview.take(MAX_TEXT),
                // The detail screen reads responseBody first; a scheduled check
                // must show the body it actually received, not an empty block.
                responseBody = result.bodyPreview.take(MAX_TEXT),
            ),
        )
    }

    fun clear(monitorId: String) {
        _state.update { it.filterNot { entry -> entry.monitorId == monitorId } }
        schedule()
    }

    fun flush() = writeNow()

    private fun schedule() = synchronized(lock) {
        pending?.cancel(false)
        pending = writer.schedule({ writeNow() }, 400, TimeUnit.MILLISECONDS)
    }

    private fun writeNow() {
        val target = file ?: return
        runCatching {
            val tmp = File(target.parentFile, "monitor-logs.json.tmp")
            tmp.writeText(json.encodeToString(_state.value))
            if (!tmp.renameTo(target)) target.writeText(tmp.readText())
        }
    }
}
