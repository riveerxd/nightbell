package me.river.nightbell.domain

/**
 * What a check costs in mobile data, and the rules that make it cost less.
 *
 * Measured from a real fleet before any of this existed: ten page element
 * monitors at fifteen minutes put 7.14 GB on one phone's mobile data in a month,
 * about 240 KB per page load, because every load fetched every script, stylesheet
 * and font again with the cache switched off. The saver keeps the verdict and
 * drops the repeats.
 *
 * Two numbers are kept per monitor because the toggle has two answers. A check
 * that ran the expensive way is a [Mode.FULL] sample, and one that ran the cheap
 * way is a [Mode.SAVER] sample. With the saver on, a page monitor still loads
 * cold once a day, which is what keeps the full number honest and is also what
 * keeps a broken asset from hiding behind the cache for longer than a day.
 */
object DataSaver {

    enum class Mode { FULL, SAVER }

    /** How often a page monitor loads with no cache while the saver is on. */
    const val COLD_LOAD_EVERY_MS = 24L * 60 * 60 * 1000

    /** The shortest gap between two download count reads while the saver is on. */
    const val DOWNLOADS_FLOOR_MS = 60L * 60 * 1000

    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val MONTH_DAYS = 30L

    fun coldDue(lastColdAt: Long?, nowMs: Long): Boolean =
        lastColdAt == null || nowMs - lastColdAt >= COLD_LOAD_EVERY_MS || nowMs < lastColdAt

    /**
     * Whether a page load may skip this request while the saver is on.
     *
     * Only things that cannot change what a selector finds or whether it is
     * visible. Fonts change glyphs, not the DOM. Media is never read. Analytics and
     * ad tags run after the page is built and report on it. Video and map embeds
     * are iframes, and the picker only ever chooses elements in the main document.
     * Stylesheets stay, because visibility is computed from them.
     */
    fun shouldBlock(url: String): Boolean {
        val lower = url.lowercase()
        if (!lower.startsWith("http")) return false
        val path = lower.substringBefore('#').substringBefore('?')
        if (BLOCKED_EXTENSIONS.any { path.endsWith(it) }) return true
        val host = lower.substringAfter("://").substringBefore('/').substringBefore(':')
        if (BLOCKED_HOSTS.any { host == it || host.endsWith(".$it") }) return true
        return BLOCKED_PATHS.any { (h, p) -> (host == h || host.endsWith(".$h")) && path.contains(p) }
    }

    private val BLOCKED_EXTENSIONS = listOf(
        ".woff2", ".woff", ".ttf", ".otf", ".eot",
        ".mp4", ".webm", ".m4v", ".mov", ".m3u8", ".ts", ".mp3", ".m4a", ".ogg", ".wav",
    )

    private val BLOCKED_HOSTS = listOf(
        "google-analytics.com",
        "googletagmanager.com",
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "connect.facebook.net",
        "hotjar.com",
        "clarity.ms",
        "cdn.segment.com",
        "static.cloudflareinsights.com",
        "fonts.googleapis.com",
        "use.typekit.net",
        "player.vimeo.com",
        "youtube-nocookie.com",
    )

    private val BLOCKED_PATHS = listOf(
        "youtube.com" to "/embed/",
        "google.com" to "/maps/embed",
        "facebook.com" to "/tr",
    )

    /**
     * A running average of bytes per check, weighted towards the newest.
     *
     * A quarter of the newest sample, so one check that pulled a fresh deploy's
     * bundle moves the estimate without taking it over.
     */
    fun fold(previous: Long, sample: Long): Long =
        if (previous <= 0L) sample else (previous * 3 + sample) / 4

    data class Estimate(
        /** Bytes a month with the saver off, over the monitors measured that way. */
        val offBytes: Long,
        val offUnmeasured: Int,
        /** Bytes a month with the saver on, likewise. */
        val onBytes: Long,
        val onUnmeasured: Int,
        val monitors: Int,
    )

    fun monthly(monitors: List<Monitor>, runtimes: Map<String, MonitorRuntime>): Estimate {
        var off = 0L
        var on = 0L
        var offMissing = 0
        var onMissing = 0
        var counted = 0
        for (monitor in monitors) {
            if (!monitor.enabled) continue
            counted++
            val (eachOff, eachOn) = perMonth(monitor, runtimes[monitor.id] ?: MonitorRuntime())
            if (eachOff == null) offMissing++ else off += eachOff
            if (eachOn == null) onMissing++ else on += eachOn
        }
        return Estimate(off, offMissing, on, onMissing, counted)
    }

    /** (off, on) bytes a month for one monitor, each null until it has been measured. */
    internal fun perMonth(monitor: Monitor, runtime: MonitorRuntime): Pair<Long?, Long?> {
        val checks = MONTH_DAYS * DAY_MS / (monitor.intervalMinutes.coerceAtLeast(1) * 60_000L)
        val full = runtime.bytesFull.takeIf { it > 0L }
        val saver = runtime.bytesSaver.takeIf { it > 0L }
        val expensiveRuns = when {
            monitor.kind == MonitorKind.WEBSITE_ELEMENT -> minOf(checks, MONTH_DAYS)
            monitor.kind == MonitorKind.GITHUB_REPO && monitor.github.trackDownloads ->
                minOf(checks, MONTH_DAYS * DAY_MS / DOWNLOADS_FLOOR_MS)
            // Nothing else changes shape with the saver, so either number is both.
            else -> {
                val any = full ?: saver ?: return null to null
                return checks * any to checks * any
            }
        }
        val off = full?.let { checks * it }
        val on = if (full != null && saver != null) {
            (checks - expensiveRuns) * saver + expensiveRuns * full
        } else {
            null
        }
        return off to on
    }

    /**
     * The line under the switch. Says what is known and what is not, and never
     * puts a number on a side nothing has been measured for.
     */
    fun summary(estimate: Estimate?, saverOn: Boolean): String {
        val what = "Loads pages from cache and skips fonts, video and trackers. Same verdicts."
        if (estimate == null || estimate.monitors == 0) return what
        val off = if (estimate.offUnmeasured < estimate.monitors) {
            "about ${format(estimate.offBytes)} a month off"
        } else {
            null
        }
        val on = if (estimate.onUnmeasured < estimate.monitors) {
            "about ${format(estimate.onBytes)} on"
        } else {
            null
        }
        val numbers = listOfNotNull(off, on).joinToString(", ").replaceFirstChar { it.uppercase() }
        val pending = maxOf(estimate.offUnmeasured, estimate.onUnmeasured)
        val tail = when {
            numbers.isEmpty() && saverOn -> "Measuring: an estimate appears once each monitor has run twice."
            numbers.isEmpty() -> "Measuring: an estimate appears after the next checks."
            on == null && !saverOn -> "$numbers. Turn it on to measure what it saves."
            pending > 0 -> "$numbers. ${plural(pending)} still being measured."
            else -> "$numbers."
        }
        return "$what $tail"
    }

    private fun plural(n: Int) = if (n == 1) "1 monitor" else "$n monitors"

    /** "7.1 GB", "640 MB", "12 KB". Decimal units, the way Android's own data screen counts. */
    fun format(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> "%.1f GB".format(java.util.Locale.ROOT, bytes / 1e9)
        bytes >= 10_000_000L -> "${bytes / 1_000_000L} MB"
        bytes >= 1_000_000L -> "%.1f MB".format(java.util.Locale.ROOT, bytes / 1e6)
        bytes >= 1_000L -> "${bytes / 1_000L} KB"
        else -> "$bytes B"
    }
}
