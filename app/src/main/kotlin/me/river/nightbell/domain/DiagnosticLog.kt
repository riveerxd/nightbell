package me.river.nightbell.domain

import java.util.TimeZone

/**
 * The shape of the diagnostic log: what an event is, what a value is, and how a
 * line renders.
 *
 * Pure, so the format and the censoring are covered by JVM tests rather than by
 * an instrumented one. The Android half is `data/diag/Diagnostics.kt`.
 *
 * ### Why there is no message parameter
 * A logging call cannot be handed a string. [log] takes an [LogEvent] whose text
 * is a compile-time constant plus a list of [LogField]s, and a field cannot be
 * constructed without naming what kind of value it holds. That is the whole
 * safety property: the log is a file the user is going to publish, and the leak
 * in that situation never comes from the line somebody thought about. It comes
 * from `Log.i(TAG, "checking $url")` written at one in the morning eight months
 * later. There is no overload that accepts that line, so it cannot be written.
 *
 * A consequence worth knowing before adding an event: lines are not prose and do
 * not read like sentences. They read like this, and they are meant to be grepped
 * rather than skimmed.
 *
 * ```
 * 12:04:31.882 W PAGE  page.expired  monitor=7f3a1c2e stage=LOAD_EVENT progress=43 ready=interactive inflight=3
 * ```
 */
enum class LogLevel(val marker: Char) {
    INFO('I'),
    WARN('W'),
    ERROR('E'),
}

/**
 * Which part of the app a line came from.
 *
 * Five of these map onto the five surfaces the app has ever been reported
 * broken on, which is what makes an area column worth its width: the first
 * question about any log is which half of the app to read.
 */
enum class LogArea {
    APP,
    SCHED,
    CHECK,
    HTTP,
    PAGE,
    ALERT,
    STORE,
    NET,
    WIDGET,
    UPDATE,
}

/**
 * Every line the app can write.
 *
 * Adding one here is the only way to add a line. The [code] is what a reader
 * greps for, so it is stable: renaming one breaks every saved log and every
 * issue thread quoting it, which is a cost worth paying only for a code that is
 * actually wrong.
 */
enum class LogEvent(
    val code: String,
    val area: LogArea,
    val level: LogLevel = LogLevel.INFO,
) {
    // The app as a process.
    APP_START("app.start", LogArea.APP),
    APP_CRASH("app.crash", LogArea.APP, LogLevel.ERROR),
    APP_LOG_ON("app.log.on", LogArea.APP),
    APP_LOG_OFF("app.log.off", LogArea.APP),
    APP_LOG_CLEARED("app.log.cleared", LogArea.APP),
    APP_LOG_ROTATED("app.log.rotated", LogArea.APP),
    APP_REPAIR("app.repair", LogArea.APP),
    APP_SCHEDULE_REARM_FAILED("app.rearm.failed", LogArea.APP, LogLevel.ERROR),

    // Scheduling and background delivery. Historically the most reported class,
    // and the one a user can least observe for themselves.
    SCHED_SYNC("sched.sync", LogArea.SCHED),
    SCHED_WORKER_START("sched.worker.start", LogArea.SCHED),
    SCHED_WORKER_NOT_DUE("sched.worker.not_due", LogArea.SCHED),
    SCHED_WORKER_STOPPED("sched.worker.stopped", LogArea.SCHED, LogLevel.WARN),
    SCHED_WORKER_FAILED("sched.worker.failed", LogArea.SCHED, LogLevel.ERROR),
    SCHED_WORKER_REARMED("sched.worker.rearmed", LogArea.SCHED, LogLevel.WARN),
    SCHED_SWEEP_START("sched.sweep.start", LogArea.SCHED),
    SCHED_SWEEP_DONE("sched.sweep.done", LogArea.SCHED),
    SCHED_SWEEP_STOPPED("sched.sweep.stopped", LogArea.SCHED, LogLevel.WARN),
    SCHED_SWEEP_FAILED("sched.sweep.failed", LogArea.SCHED, LogLevel.ERROR),
    SCHED_SERVICE_START("sched.service.start", LogArea.SCHED),
    SCHED_SERVICE_STOP("sched.service.stop", LogArea.SCHED),
    SCHED_SERVICE_REFUSED("sched.service.refused", LogArea.SCHED, LogLevel.WARN),
    SCHED_SERVICE_TICK("sched.service.tick", LogArea.SCHED),
    SCHED_BOOT("sched.boot", LogArea.SCHED),
    SCHED_LIMIT("sched.limit", LogArea.SCHED, LogLevel.WARN),

    // A check, above the protocol that carries it.
    CHECK_START("check.start", LogArea.CHECK),
    CHECK_DONE("check.done", LogArea.CHECK),
    CHECK_FAILED("check.failed", LogArea.CHECK, LogLevel.WARN),
    CHECK_SKIPPED("check.skipped", LogArea.CHECK),
    CHECK_CANCELLED("check.cancelled", LogArea.CHECK),
    CHECK_HEALTH("check.health", LogArea.CHECK, LogLevel.WARN),

    // HTTP and TLS. Issue 3 was an IOException with no request context and issue
    // 6 was a certificate the app would not name.
    HTTP_REQUEST("http.request", LogArea.HTTP),
    HTTP_RESPONSE("http.response", LogArea.HTTP),
    HTTP_RETRY("http.retry", LogArea.HTTP, LogLevel.WARN),
    HTTP_ERROR("http.error", LogArea.HTTP, LogLevel.WARN),
    HTTP_TLS("http.tls", LogArea.HTTP),
    HTTP_TLS_REFUSED("http.tls.refused", LogArea.HTTP, LogLevel.WARN),
    HTTP_PROXY("http.proxy", LogArea.HTTP),

    // The embedded browser. Everything here exists because issue 8 could not be
    // diagnosed: the checker had no chrome client, so progress, console output
    // and subresource failures were all unobservable.
    PAGE_LOAD_START("page.load.start", LogArea.PAGE),
    PAGE_CONFIG("page.config", LogArea.PAGE),
    PAGE_SEED("page.seed", LogArea.PAGE),
    PAGE_PROGRESS("page.progress", LogArea.PAGE),
    PAGE_FINISHED("page.finished", LogArea.PAGE),
    PAGE_READY_STATE("page.ready", LogArea.PAGE),
    PAGE_RESOURCE_ERROR("page.resource.error", LogArea.PAGE, LogLevel.WARN),
    PAGE_HTTP_ERROR("page.http.error", LogArea.PAGE, LogLevel.WARN),
    PAGE_SSL_ERROR("page.ssl.error", LogArea.PAGE, LogLevel.WARN),
    PAGE_CONSOLE("page.console", LogArea.PAGE, LogLevel.WARN),
    PAGE_POLL("page.poll", LogArea.PAGE),
    PAGE_GATE("page.gate", LogArea.PAGE, LogLevel.WARN),
    PAGE_EXPIRED("page.expired", LogArea.PAGE, LogLevel.WARN),
    PAGE_DONE("page.done", LogArea.PAGE),
    PAGE_THREW("page.threw", LogArea.PAGE, LogLevel.ERROR),

    // Alerts and the pager. No issue has been filed against this yet, and a
    // pager that stayed silent is the report that would be hardest to answer
    // without a trace, which is why it is instrumented before it is asked for.
    ALERT_DECIDED("alert.decided", LogArea.ALERT),
    ALERT_POSTED("alert.posted", LogArea.ALERT),
    ALERT_SUPPRESSED("alert.suppressed", LogArea.ALERT),
    ALERT_URGENT_START("alert.urgent.start", LogArea.ALERT),
    ALERT_URGENT_STOP("alert.urgent.stop", LogArea.ALERT),
    ALERT_URGENT_FAILED("alert.urgent.failed", LogArea.ALERT, LogLevel.ERROR),
    ALERT_SPEAK("alert.speak", LogArea.ALERT),
    ALERT_SPEAK_FAILED("alert.speak.failed", LogArea.ALERT, LogLevel.WARN),
    ALERT_PERMISSION("alert.permission", LogArea.ALERT),
    ALERT_RINGER("alert.ringer", LogArea.ALERT),

    // The store, which has had two data-shaped bugs of its own.
    STORE_READ_FAILED("store.read.failed", LogArea.STORE, LogLevel.ERROR),
    STORE_CORRUPT("store.corrupt", LogArea.STORE, LogLevel.ERROR),
    STORE_REPAIR("store.repair", LogArea.STORE, LogLevel.WARN),
    STORE_IMPORT("store.import", LogArea.STORE),
    STORE_EXPORT("store.export", LogArea.STORE),

    // Connectivity, because half of "it stopped checking" is the network.
    NET_CHANGED("net.changed", LogArea.NET),
    NET_LOOKUP_FAILED("net.lookup.failed", LogArea.NET, LogLevel.WARN),
    NET_WATCH_FAILED("net.watch.failed", LogArea.NET, LogLevel.WARN),

    // The home-screen widget, which renders from a cold process and has had two
    // bugs that only showed there.
    WIDGET_REFRESH_FAILED("widget.refresh.failed", LogArea.WIDGET, LogLevel.WARN),
    WIDGET_NOT_LOADED("widget.not_loaded", LogArea.WIDGET),
    WIDGET_UPDATE_FAILED("widget.update.failed", LogArea.WIDGET, LogLevel.ERROR),

    // Nightbell's own updates, and the icon cache that rides along with them.
    UPDATE_CHECK_FAILED("update.check.failed", LogArea.UPDATE),
    UPDATE_DOWNLOAD_FAILED("update.download.failed", LogArea.UPDATE, LogLevel.WARN),
    UPDATE_INSTALL_STATUS("update.install.status", LogArea.UPDATE),
    UPDATE_NOTICE_DISMISSED("update.notice.dismissed", LogArea.UPDATE),
    ICON_CACHE_FAILED("icon.cache.failed", LogArea.UPDATE, LogLevel.WARN),
    ICON_PICTURE_REFUSED("icon.picture.refused", LogArea.UPDATE, LogLevel.WARN),
    ICON_PICTURE_FAILED("icon.picture.failed", LogArea.UPDATE, LogLevel.WARN),

    // Latency reference probing, which decides whether latency is judged raw.
    REFERENCE_BAD_URL("reference.bad_url", LogArea.CHECK, LogLevel.WARN),
    REFERENCE_FAILED("reference.failed", LogArea.CHECK),
    REFERENCE_LOCAL("reference.local", LogArea.CHECK),

    // The proxy override the page checker installs around a routed load.
    PROXY_CLEAR_FAILED("proxy.clear.failed", LogArea.HTTP, LogLevel.WARN),
    ;
}

/**
 * One `key=value` pair in a line.
 *
 * The constructor is private and every factory on the companion states the
 * disclosure class of what it accepts. That is the allowlist: a value with no
 * factory to carry it does not go in the log, and adding a factory is a visible
 * decision about what may be published rather than an accident inside a format
 * string.
 */
class LogField private constructor(val key: String, val value: String) {

    /** Text values are quoted, because a scrubbed sentence keeps its spaces. */
    fun render(): String = if (quoted) "$key=\"$value\"" else "$key=$value"

    private val quoted: Boolean get() = value.any { it == ' ' || it == '"' }

    companion object {
        /** Anything the app itself computed and that cannot describe the user. */
        fun of(key: String, value: Int): LogField = LogField(key, value.toString())
        fun of(key: String, value: Long): LogField = LogField(key, value.toString())
        fun of(key: String, value: Boolean): LogField = LogField(key, value.toString())
        fun of(key: String, value: Enum<*>): LogField = LogField(key, value.name)

        /** A duration, always in milliseconds, always named so. */
        fun ms(key: String, value: Long): LogField = LogField("${key}_ms", value.toString())

        /** A count, so an empty list is distinguishable from a missing field. */
        fun count(key: String, value: Int): LogField = LogField(key, value.toString())

        /**
         * A URL, reduced by [LogRedactor.route] before it can be stored.
         *
         * There is no factory that keeps a full URL. The path and query of a
         * monitor's address are the operator's business and routinely carry a
         * credential, and every question a log has ever needed to answer about
         * an address is answered by scheme, host, port and a shape.
         */
        fun route(key: String, url: String): LogField =
            LogField(key, LogRedactor.route(url))

        /** Host only, for a line about reachability rather than a request. */
        fun host(key: String, url: String): LogField =
            LogField(key, LogRedactor.host(url))

        /**
         * A monitor, by a short prefix of its id.
         *
         * The id and not the name. Users name monitors after the systems they
         * run, so a name is a description of somebody's private infrastructure,
         * while the id is a UUID this app generated and means nothing to anyone
         * who did not make it. A reporter can map an id back to a name from
         * their own screen, which is the only place that mapping needs to exist.
         */
        fun monitor(id: String): LogField = LogField("monitor", id.take(8))

        /**
         * A value that is secret by definition, reduced to a fingerprint.
         *
         * Never the content, not once, not truncated. A truncated cookie is
         * still a cookie to whoever wants the first half.
         */
        fun secret(key: String, value: String): LogField =
            LogField(key, if (value.isEmpty()) "none" else LogRedactor.fingerprint(value))

        /** Whether a secret is set, when even its fingerprint is more than the line needs. */
        fun present(key: String, value: String): LogField =
            LogField(key, if (value.isBlank()) "absent" else "present")

        /**
         * Text this app did not author: an exception message, a console line, a
         * response snippet. Scrubbed by the backstop and hard truncated.
         *
         * @param known live secret values to replace before the patterns run.
         */
        fun text(
            key: String,
            raw: String,
            known: Collection<String> = emptyList(),
            limit: Int = TEXT_LIMIT,
        ): LogField = LogField(key, LogRedactor.truncate(LogRedactor.scrub(raw, known), limit))

        /**
         * A throwable, as its class name plus a scrubbed message.
         *
         * The class name carries most of the diagnostic weight and is always
         * safe, since it names this app's code or the framework's. The message
         * is somebody else's string and goes through the backstop.
         */
        fun error(
            key: String,
            error: Throwable,
            known: Collection<String> = emptyList(),
        ): LogField {
            val type = error::class.java.simpleName.ifBlank { error::class.java.name }
            val message = error.message?.takeIf { it.isNotBlank() }
            val rendered = if (message == null) {
                type
            } else {
                "$type: " + LogRedactor.truncate(LogRedactor.scrub(message, known), TEXT_LIMIT)
            }
            return LogField(key, rendered)
        }

        /**
         * A short label this app chose from a fixed set: a reason code, a stage,
         * a version string.
         *
         * Strict on purpose. Anything that is not a lowercase slug is
         * fingerprinted rather than kept, which is what stops this factory from
         * being the hole in the allowlist. It was one: a monitor's name went
         * straight through it, because a name is short and looks like a word.
         * The shape check is the enforcement, and the codes in this file were
         * written as lowercase slugs so that the check costs nothing.
         *
         * For a string the platform handed over rather than one this app chose,
         * use [text].
         */
        fun tag(key: String, value: String): LogField = LogField(
            key,
            when {
                value.isBlank() -> "none"
                // A lowercase hex identifier is a legal slug and is not a
                // constant anybody wrote, so the shape check alone was not
                // enough. Every code in this file is short, and the ones with
                // digits in them are version strings.
                SLUG.matches(value) && !LogRedactor.looksOpaque(value) -> value
                else -> LogRedactor.fingerprint(value)
            },
        )

        /** What a constant this app authored is allowed to look like. */
        private val SLUG = Regex("[a-z0-9_.\\-]{1,40}")

        const val TEXT_LIMIT = 160
    }
}

/**
 * Renders lines and the file's header.
 *
 * The timestamp is wall clock and local, deliberately. A log read by the person
 * who produced it is read against "it stopped alerting around eleven", and an
 * epoch millisecond cannot be compared to that by eye.
 */
object LogFormat {

    /** `12:04:31.882 W PAGE  page.expired  monitor=… stage=…` */
    fun line(
        atMs: Long,
        event: LogEvent,
        fields: List<LogField>,
        /**
         * The device's offset from UTC at [atMs], in milliseconds.
         *
         * A parameter so a test can pin it, and defaulted to the device's own
         * because that is the only value that makes the column useful: a log is
         * read against "it went quiet around eleven", and eleven means eleven
         * where the phone was. Getting this wrong shifted every line by two
         * hours against the same events in logcat, which is exactly the kind of
         * discrepancy that makes somebody distrust the whole file.
         */
        offsetMs: Int = TimeZone.getDefault().getOffset(atMs),
    ): String {
        val stamp = clock(atMs + offsetMs)
        val area = event.area.name.padEnd(5)
        return buildString {
            append(stamp).append(' ')
            append(event.level.marker).append(' ')
            append(area).append(' ')
            append(event.code)
            for (field in fields) {
                append(' ')
                append(field.render())
            }
        }
    }

    private fun clock(atMs: Long): String {
        val dayMs = atMs % 86_400_000L
        val hours = dayMs / 3_600_000L
        val minutes = (dayMs % 3_600_000L) / 60_000L
        val seconds = (dayMs % 60_000L) / 1_000L
        val millis = dayMs % 1_000L
        return "%02d:%02d:%02d.%03d".format(hours, minutes, seconds, millis)
    }

    /**
     * Renders a stack trace as indented lines under the event that reported it.
     *
     * Frames are class, method, file and line, all of which name this app or the
     * framework, so they pass through untouched. Messages belong to whoever
     * threw and go through the backstop.
     */
    fun stack(error: Throwable, known: Collection<String> = emptyList(), maxFrames: Int = 24): List<String> {
        val out = mutableListOf<String>()
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 4) {
            val head = if (depth == 0) "  " else "  caused by "
            val message = current.message?.takeIf { it.isNotBlank() }
                ?.let { ": " + LogRedactor.truncate(LogRedactor.scrub(it, known), 200) }
                ?: ""
            out += head + current::class.java.name + message
            current.stackTrace.take(maxFrames).forEach { frame ->
                out += "    at ${frame.className}.${frame.methodName}(${frame.fileName}:${frame.lineNumber})"
            }
            val remaining = current.stackTrace.size - maxFrames
            if (remaining > 0) out += "    … $remaining more"
            current = current.cause?.takeIf { it !== current }
            depth++
        }
        return out
    }
}

/**
 * What the app is, gathered once and written at the top of every export.
 *
 * This block exists because of what the issue templates keep failing to
 * collect. Of the first reports from strangers, none carried a build number and
 * one was a crash on an Android version nobody had named. Every field here is a
 * question that has actually been asked in a thread, [webViewVersion] most
 * pointedly: issue 8 asked which WebView the app uses, and the answer is per
 * device and not knowable from anywhere else.
 *
 * Pure and parameterised rather than reading `Build` itself, so the format is
 * testable off device.
 */
data class DiagnosticHeader(
    val versionName: String,
    val versionCode: Int,
    val buildType: String,
    val minified: Boolean,
    val applicationId: String,
    val sdkInt: Int,
    val manufacturer: String,
    val model: String,
    val webViewPackage: String,
    val webViewVersion: String,
    val batteryOptimised: Boolean,
    val notificationsAllowed: Boolean,
    val exactAlarmsAllowed: Boolean,
    val fullScreenIntentAllowed: Boolean,
    val online: Boolean,
    val metered: Boolean,
    val servicePaging: Boolean,
    val monitorCount: Int,
    val enabledCount: Int,
    val urgentCount: Int,
    val pageMonitorCount: Int,
    val loggingSince: String,
    val capturedAt: String,
) {
    fun render(): List<String> = listOf(
        "# Nightbell diagnostic log",
        "# Everything below was written on the device and nothing was uploaded.",
        "# Addresses are reduced to scheme, host and port. Credentials, cookies,",
        "# monitor names and page content are never written. See docs/reference.md.",
        "",
        "captured    $capturedAt",
        "app         $versionName ($versionCode)" +
            // The debug build's versionName already ends in its build type, so
            // naming it again read as "3.8.0-debug (37) debug".
            (if (versionName.endsWith(buildType)) "" else " $buildType") +
            (if (minified) " minified" else ""),
        "package     $applicationId",
        "android     API $sdkInt",
        "device      $manufacturer $model",
        "webview     $webViewPackage $webViewVersion",
        "battery     ${if (batteryOptimised) "optimised, checks may be delayed" else "unrestricted"}",
        "permissions notifications=$notificationsAllowed exact_alarms=$exactAlarmsAllowed full_screen=$fullScreenIntentAllowed",
        "network     online=$online metered=$metered",
        "service     paging=$servicePaging",
        "fleet       $monitorCount monitor${if (monitorCount == 1) "" else "s"}, " +
            "$enabledCount enabled, $urgentCount urgent, $pageMonitorCount page",
        "logging     $loggingSince",
        "",
    )
}

/**
 * The fleet, counted rather than described.
 *
 * A log says how many monitors there are and how many are page monitors. It
 * never says what they are called or where they point, because a monitor's name
 * is a description of somebody's private infrastructure and a count answers
 * every question a bug report has ever needed: whether this is a one monitor
 * install or a forty monitor one, and whether the browser is involved at all.
 */
data class FleetFacts(
    val total: Int,
    val enabled: Int,
    val urgent: Int,
    val page: Int,
) {
    companion object {
        fun of(monitors: List<Monitor>): FleetFacts = FleetFacts(
            total = monitors.size,
            enabled = monitors.count { it.enabled },
            urgent = monitors.count { it.urgent },
            page = monitors.count { it.kind == MonitorKind.WEBSITE_ELEMENT },
        )
    }
}

/** The caps, in one place so a test can assert them and a reader can find them. */
object LogRetention {
    /** Lines held in memory. Feeds the crash block and the flush that follows enabling. */
    const val RING_LINES = 500

    /** Bytes per file. Two files exist, so the worst case on disk is twice this. */
    const val FILE_BYTES = 192 * 1024

    /** Lines the viewer reads back, newest last. */
    const val VIEW_LINES = 1_000

    /** True when the live file has earned a rotation. */
    fun shouldRotate(currentBytes: Long): Boolean = currentBytes >= FILE_BYTES
}
