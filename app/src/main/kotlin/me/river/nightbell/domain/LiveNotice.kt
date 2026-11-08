package me.river.nightbell.domain

/**
 * The strict monitoring notice's words and line, worked out in one place.
 *
 * Two readers: the service that posts the notice, and the preview in Settings
 * that shows what a choice will do before anyone pulls the shade down. They used
 * to be one inline block in the service, and a preview with its own copy of the
 * wording would have been a second claim about the same notification, free to
 * drift from the first.
 */
object LiveNotice {

    data class Notice(
        val title: String,
        val body: String,
        /** The line, when this release draws one. Null means the plain text notice. */
        val line: LiveTimeline.Timeline?,
    )

    /**
     * [lineSupported] is whether the platform can draw the line, Android 16 and up.
     * [strict] says why the service is running at all, strict mode or an urgent
     * page being kept alive, which decides the fallback sentence when there is
     * nothing better to say.
     */
    fun of(
        monitors: List<Monitor>,
        runtimes: Map<String, MonitorRuntime>,
        groups: List<MonitorGroup>,
        prefs: LiveCardPrefs,
        nowMs: Long,
        offline: Boolean,
        lineSupported: Boolean,
        strict: Boolean = true,
    ): Notice {
        val fleet = Summary.of(monitors, runtimes)
        val scope = LiveScope.resolve(prefs, monitors, groups)
        val scoped = Summary.of(scope.monitors, runtimes)
        val timeline = LiveTimeline.of(
            monitors = scope.monitors,
            runtimes = runtimes,
            nowMs = nowMs,
            offline = offline,
            window = prefs.window,
            label = prefs.label,
        )
        val drawn = timeline?.takeIf { lineSupported }
        // Problems the card is not about. Following one monitor must not make the
        // permanent notice look calm while something else is down.
        val elsewhere = if (scope.name != null) (fleet.down - scoped.down).coerceAtLeast(0) else 0

        val title = when {
            // Ahead of the fleet verdict: while offline that verdict is a record of
            // the past, and this notification is permanently on screen claiming to
            // describe the present.
            offline -> "Monitoring paused · offline"
            fleet.urgentPending > 0 -> "URGENT · ${fleet.urgentPending} unacknowledged"
            scope.follows == LiveFollow.MONITOR ->
                "${scope.name} · ${scoped.entries.firstOrNull()?.health?.label ?: "Not checked"}"
            scope.name != null -> "${scope.name} · ${scoped.headline}"
            // The verdict alone. "Strict monitoring · " in front of it was long
            // enough to run into the countdown the platform draws right after the
            // title, which no API positions or pads: on a Samsung shade it read
            // "1 of 15 is down09:57". The brand mark already says whose card it is.
            else -> fleet.headline
        }

        val body = buildString {
            if (offline) {
                append("No connection, checks resume automatically.")
            } else if (drawn != null && scoped.total > 0) {
                // With the line drawn there is no room for the paragraph explaining
                // strict mode: ProgressStyle draws no big text. What is worth the
                // space is what the bar covers, because it has no axis.
                if (scope.follows != LiveFollow.MONITOR) {
                    append("${scoped.total} monitors · ${scoped.down} down · ${scoped.degraded} slow · ")
                }
                append("last ${drawn.spanLabel} · ${drawn.labelPhrase}")
            } else if (strict) {
                append("Checking on schedule instead of waiting for Android to batch work.")
            } else {
                append("Keeping the urgent alert alive until it's acknowledged.")
            }
            if (drawn == null && scoped.total > 0) {
                append("\n${scoped.total} monitor(s) · ${scoped.down} down · ${scoped.degraded} slow")
                timeline?.let { append("\n${it.labelSentence}.") }
            }
            if (!offline && elsewhere > 0) {
                append(if (elsewhere == 1) "\nElsewhere: 1 more is down." else "\nElsewhere: $elsewhere more are down.")
            }
        }
        return Notice(title = title, body = body, line = drawn)
    }
}
