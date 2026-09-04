package me.river.nightbell.data.diag

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import me.river.nightbell.domain.DiagnosticHeader
import me.river.nightbell.domain.LogEvent
import me.river.nightbell.domain.LogField
import me.river.nightbell.domain.LogFormat
import me.river.nightbell.domain.LogLevel
import me.river.nightbell.domain.LogRedactor
import me.river.nightbell.domain.LogRetention
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "Nightbell"

/**
 * The diagnostic log, as the app writes it.
 *
 * ### What this is for
 * A user with a phone and no computer cannot read logcat, so until now a report
 * arrived as a sentence and was answered with guesses. Issue 8 ended with three
 * sites timing out, a reporter who could not run `curl`, and no way to find out
 * why. This is the instrument that ends that conversation with evidence instead.
 *
 * ### Three sinks, on purpose
 *  - **logcat**, always, unchanged. Every call still reaches `android.util.Log`
 *    at its original level, so `adb logcat` behaves exactly as it did and the
 *    `-assumenosideeffects` rule in `proguard-rules.pro` still means what it
 *    says. Nothing here logs at `d` or `v`, so nothing here is stripped from a
 *    release build.
 *  - **the ring**, always, in memory only. Five hundred lines, no IO, no file,
 *    nothing on disk. It exists so a crash can carry the minute that preceded
 *    it, which is the part of a crash report that is usually missing.
 *  - **the file**, only while the user has the switch on. This is the one that
 *    leaves the device, and the only one the switch governs.
 *
 * Crashes are the exception and are always written, to a file of their own. A
 * stack trace is the single most useful artefact a report can carry and asking
 * somebody to reproduce a crash with logging on is asking them to crash twice.
 *
 * ### Cold start
 * A check can run in a process that WorkManager started, and the store has not
 * necessarily been read by the time the first line is logged. Waiting for it
 * would lose exactly the window a scheduling bug lives in. So the ring fills
 * from the first line regardless, and the moment the flag is known for the first
 * time the ring is flushed to the file if the answer was yes and dropped if it
 * was no. A user turning the switch on afterwards starts capture from that
 * moment and does not retroactively write out what was in memory, because they
 * turned it on now and that is what "on" means.
 */
// `StaticFieldLeak` does not apply: an application context is the process, so a
// process-scoped singleton holding one cannot outlive it, and the alternative is
// threading a context through every logging call in the app.
@SuppressLint("StaticFieldLeak")
object Diag {

    @Volatile
    private var context: Context? = null

    /**
     * Live values the store considers secret.
     *
     * A function rather than a snapshot: a token pasted into Settings has to
     * apply to the next line, not to the next launch, which is the same reason
     * `HttpChecker` reads the proxy per check.
     */
    @Volatile
    private var secretsFor: () -> Collection<String> = { emptyList() }

    @Volatile
    private var writing = false

    @Volatile
    private var flagKnown = false

    @Volatile
    private var enabledSince = 0L

    /** Newest last. Guarded by itself; every reader copies before it iterates. */
    private val ring = ArrayDeque<String>(LogRetention.RING_LINES)

    private val lines = Channel<String>(Channel.UNLIMITED)

    @Volatile
    private var pumping = false

    /** Whether the file is being written right now. Read by the settings card. */
    val capturing: Boolean get() = writing

    val since: Long get() = enabledSince

    fun install(
        context: Context,
        scope: CoroutineScope,
        secretsFor: () -> Collection<String>,
    ) {
        this.context = context.applicationContext
        this.secretsFor = secretsFor
        startPump(scope)
    }

    /**
     * Adopts the user's answer.
     *
     * Called with every store emission, so it has to be cheap and idempotent.
     * The first call is the one that decides what happens to the cold-start
     * ring; see the note on the class.
     */
    fun setEnabled(enabled: Boolean) {
        val firstAnswer = !flagKnown
        flagKnown = true
        if (enabled == writing && !firstAnswer) return
        writing = enabled
        @Suppress("KotlinConstantConditions")
        if (enabled) {
            enabledSince = System.currentTimeMillis()
            if (firstAnswer) flushRing() else log(LogEvent.APP_LOG_ON)
        } else {
            enabledSince = 0L
            if (!firstAnswer) {
                // Logged *before* the flag flips, or `offer` sees a sink that is
                // already closed and drops it. The file would then simply stop
                // mid-run with nothing in it saying why, which is the one thing
                // a reader of somebody else's log cannot work out for themselves.
                writing = true
                log(LogEvent.APP_LOG_OFF)
            }
            writing = false
        }
    }

    fun log(event: LogEvent, vararg fields: LogField) {
        val line = render(event, fields.toList())
        toLogcat(event, line)
        offer(line)
    }

    /**
     * An event that carries a stack trace.
     *
     * The trace goes in as its own indented block under the line rather than as
     * a field, because a field is truncated and a trace is the one thing in a
     * log that is worth its length.
     */
    fun logError(event: LogEvent, error: Throwable, vararg fields: LogField) {
        val known = knownSecrets()
        val head = render(event, fields.toList() + LogField.error("error", error, known))
        toLogcat(event, head)
        offer(head)
        for (frame in LogFormat.stack(error, known)) offer(frame)
    }

    /** The ring, newest last. What the viewer shows when the file is empty. */
    fun recent(): List<String> = synchronized(ring) { ring.toList() }

    fun ringSize(): Int = synchronized(ring) { ring.size }

    private fun render(event: LogEvent, fields: List<LogField>): String {
        val line = LogFormat.line(System.currentTimeMillis(), event, fields)
        // The second pass, over the assembled line. A call site that reached for
        // the wrong field factory still cannot publish this install's token or
        // session cookie. See LogRedactor.replaceKnown.
        return LogRedactor.replaceKnown(line, knownSecrets())
    }

    private fun knownSecrets(): Collection<String> = runCatching { secretsFor() }.getOrDefault(emptyList())

    /**
     * Keeps logcat behaving exactly as it did before this object existed.
     *
     * The whole line goes through rather than the event code alone, because
     * somebody reading logcat over adb wants the fields too, and they are
     * already censored by the time they get here.
     */
    private fun toLogcat(event: LogEvent, line: String) {
        when (event.level) {
            LogLevel.INFO -> Log.i(TAG, line)
            LogLevel.WARN -> Log.w(TAG, line)
            LogLevel.ERROR -> Log.e(TAG, line)
        }
    }

    private fun offer(line: String) {
        synchronized(ring) {
            if (ring.size >= LogRetention.RING_LINES) ring.removeFirst()
            ring.addLast(line)
        }
        if (writing) lines.trySend(line)
    }

    private fun flushRing() {
        val snapshot = recent()
        if (snapshot.isEmpty()) return
        for (line in snapshot) lines.trySend(line)
    }

    private fun startPump(scope: CoroutineScope) {
        if (pumping) return
        pumping = true
        scope.launch(Dispatchers.IO) {
            for (line in lines) {
                // Checked here as well as at the offer, because a line can sit in
                // the channel across the switch being turned off.
                if (!writing) continue
                runCatching { append(line) }
            }
        }
    }

    // ---- files ---------------------------------------------------------------

    private fun dir(): File? = context?.let { File(it.filesDir, "diagnostics").apply { mkdirs() } }

    private fun live(): File? = dir()?.let { File(it, "nightbell.log") }

    private fun rotated(): File? = dir()?.let { File(it, "nightbell.log.1") }

    private fun crash(): File? = dir()?.let { File(it, "crash.log") }

    private fun append(line: String) {
        val file = live() ?: return
        val bytes = if (file.exists()) file.length() else 0L
        if (LogRetention.shouldRotate(bytes)) rotate(file)
        file.appendText(line + "\n")
    }

    /**
     * One generation back and no more.
     *
     * Two files at 192 KB is a bound a phone will not notice and is still long
     * enough to hold a night of a fifteen minute cadence. Keeping more would
     * mean deciding how much of the user's history to hoard for a bug that may
     * never be filed.
     */
    private fun rotate(file: File) {
        val previous = rotated() ?: return
        runCatching {
            if (previous.exists()) previous.delete()
            file.renameTo(previous)
        }.onSuccess {
            log(LogEvent.APP_LOG_ROTATED, LogField.of("kept_bytes", LogRetention.FILE_BYTES))
        }
    }

    /** Bytes on disk across both generations plus the crash file. */
    fun sizeBytes(): Long {
        val files = listOfNotNull(live(), rotated(), crash())
        return files.filter { it.exists() }.sumOf { it.length() }
    }

    fun hasCapture(): Boolean = sizeBytes() > 0L

    /**
     * The last [LogRetention.VIEW_LINES] lines, oldest first.
     *
     * Read from the file rather than from the ring so that what the viewer shows
     * is what the export writes. A user about to publish this needs to be
     * looking at the thing they are about to publish, not at an approximation of
     * it. When nothing has been captured the ring is offered instead, which is
     * the only honest thing to show a user who has just turned the switch on.
     */
    suspend fun view(): List<String> = withContext(Dispatchers.IO) {
        val captured = readAll()
        when {
            captured.isNotEmpty() -> captured.takeLast(LogRetention.VIEW_LINES)
            // The ring only, and only while capture is running. With the switch
            // off the ring is still filling, because a crash has to be able to
            // carry the minute before it, but showing it in the viewer would
            // contradict the card two rows above saying nothing is being
            // recorded. The switch means what it says.
            writing -> recent()
            else -> emptyList()
        }
    }

    private fun readAll(): List<String> {
        val out = mutableListOf<String>()
        crash()?.takeIf { it.exists() }?.let { out += runCatching { it.readLines() }.getOrDefault(emptyList()) }
        rotated()?.takeIf { it.exists() }?.let { out += runCatching { it.readLines() }.getOrDefault(emptyList()) }
        live()?.takeIf { it.exists() }?.let { out += runCatching { it.readLines() }.getOrDefault(emptyList()) }
        return out
    }

    /**
     * The whole file, header first, as a document to hand to somebody.
     *
     * The header is passed in rather than gathered here so the facts about the
     * device stay in one place and stay testable; see [DiagnosticFacts].
     */
    suspend fun export(header: DiagnosticHeader): String = withContext(Dispatchers.IO) {
        val body = readAll()
        buildString {
            header.render().forEach { appendLine(it) }
            if (body.isEmpty()) {
                appendLine("(nothing captured: the diagnostic log has not been switched on)")
            } else {
                body.forEach { appendLine(it) }
            }
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        // Drained first. Deleting the files while lines were still queued let the
        // pump recreate the file a moment later, so "delete the log" deleted the
        // log and then wrote some of it back.
        while (lines.tryReceive().isSuccess) Unit
        listOfNotNull(live(), rotated(), crash()).forEach { runCatching { it.delete() } }
        synchronized(ring) { ring.clear() }
        if (writing) log(LogEvent.APP_LOG_CLEARED)
    }

    // ---- crashes -------------------------------------------------------------

    /**
     * Records the next uncaught exception, then lets the platform have it.
     *
     * Always installed and not governed by the switch, for the reason on the
     * class: a crash cannot be reproduced on demand, so a switch that has to be
     * on beforehand would record nothing the first time and nothing is exactly
     * what issue 2 arrived with.
     *
     * Written synchronously on the dying thread. The channel and its coroutine
     * are no use here, because nothing guarantees a dispatcher gets to run
     * again before the process goes.
     */
    fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { writeCrash(thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun writeCrash(thread: Thread, error: Throwable) {
        val file = crash() ?: return
        val known = knownSecrets()
        val head = LogRedactor.replaceKnown(
            LogFormat.line(
                System.currentTimeMillis(),
                LogEvent.APP_CRASH,
                listOf(
                    LogField.text("thread", thread.name),
                    LogField.error("error", error, known),
                ),
            ),
            known,
        )
        val document = buildString {
            appendLine(head)
            LogFormat.stack(error, known, maxFrames = 40).forEach { appendLine(it) }
            appendLine("  the ${ringSize()} lines before the crash:")
            recent().forEach { appendLine("  | $it") }
        }
        // Replaced rather than appended. Two crashes in a row are almost always
        // the same crash twice, and the second one has the fresher context.
        file.writeText(document)
    }
}
