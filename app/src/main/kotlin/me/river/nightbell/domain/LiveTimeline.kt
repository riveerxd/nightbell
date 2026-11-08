package me.river.nightbell.domain

/**
 * The strict-monitoring notice as a journey the platform can draw itself.
 *
 * Android 16 added `Notification.ProgressStyle` for rideshare, delivery and
 * navigation: a horizontal line built from coloured segments, with milestone
 * points marked on it and a tracker icon riding at the current position. It is
 * the shape a ride card uses — and it is the only template the system will
 * promote to a status-bar chip and expand on the lock screen.
 *
 * It is also the only way to get a chart into a notification that SystemUI draws
 * rather than us. The `RemoteViews` route can hold a real bitmap sparkline, but
 * it is rescaled non-uniformly in the slot it lands in — measured on a device
 * when [me.river.nightbell.data.alerts.UrgentPageStyle.CALL_CUSTOM] came out with
 * faceted pills — and a custom content view disqualifies the notification from
 * promotion outright.
 *
 * The line is time. Oldest retained check on the left, now under the tracker, and
 * past the tracker the wait until the next check — the destination, in rideshare
 * terms. Segments are runs of one outcome, so an outage is a red stretch as long
 * as the outage actually was, rather than a dot that looks the same whether it
 * lasted one check or forty. That is the same decision the dashboard sparkline
 * made in 1.2.0, for the same reason.
 *
 * Pure domain, no Android types. The template's own classes take plain ints, and
 * none of the arithmetic below wants to be tested on a device.
 */
object LiveTimeline {

    /**
     * How many equal slices of wall time the history is measured in.
     *
     * The unit the platform gets is "one bucket", not one check: segment lengths
     * have to be proportional to *time* or a fifteen-minute outage during hourly
     * checks would draw the same width as a fifteen-hour one. 48 is enough that a
     * single bad check inside a day-long window is still a visible sliver, and
     * small enough that the shortest possible band stays wide enough to see.
     */
    const val BUCKETS: Int = 48

    /**
     * Ceiling on how many segments are handed over.
     *
     * A monitor that flaps every other check would otherwise produce 48 of them,
     * which renders as a striped smear rather than as a history. Outages are never
     * the ones merged away — see [capBands].
     */
    const val MAX_BANDS: Int = 16

    /** Ceiling on outage markers, newest kept. Beyond this they stop being marks. */
    const val MAX_MARKERS: Int = 6

    /**
     * A fresh install has a couple of minutes of history, and a bar whose whole
     * span is ninety seconds re-labels itself every time a check lands. Floor the
     * window so the line settles.
     */
    const val MIN_SPAN_MS: Long = 10L * 60 * 1000

    /** Ceiling on the window. Past a day the bar stops being about right now. */
    const val MAX_SPAN_MS: Long = UptimeWindows.DAY_MS

    /**
     * What a stretch of the line means.
     *
     * There is deliberately no DEGRADED band. A [Sample] records `ok` and a
     * latency, not the SLO verdict that made a check "slow" — that verdict needs
     * the monitor's policy *as it was at the time*, which is not retained. So the
     * line carries outages, which are unambiguous, and slowness reaches the user
     * through the title and the chip, where it can be stated in words. [AHEAD] is
     * the unelapsed tail, and [current] can be [DEGRADED] because the fleet's
     * health right now is known.
     */
    enum class Tone { UNKNOWN, UP, DEGRADED, DOWN, AHEAD }

    /** One run of the line. [length] is in buckets. */
    data class Band(val length: Int, val tone: Tone)

    /** A milestone. [position] is in buckets from the left edge. */
    data class Marker(val position: Int, val tone: Tone)

    data class Timeline(
        val bands: List<Band>,
        val markers: List<Marker>,
        /** Where the tracker rides: the boundary between elapsed and [Tone.AHEAD]. */
        val progress: Int,
        /** Wall time the elapsed part of the line covers. */
        val spanMs: Long,
        /** The fleet's health now, for the tracker icon. */
        val current: Tone,
        /** Status-bar chip text. A handful of characters beside the clock. */
        val chip: String,
        /**
         * How long until the next check, in millis. Zero means due now.
         *
         * The same number the [Tone.AHEAD] tail's width comes from, so a label drawn
         * from it agrees with the grey it sits at the end of. Without that they would
         * be two independent claims about the same wait.
         */
        val nextCheckInMs: Long,
        /** Which figure the right-hand end of the line carries. */
        val label: LiveLabel = LiveLabel.NEXT_CHECK,
        /**
         * Share of the checks inside the window that passed, 0 to 100, across every
         * monitor the line is about. Null with no checks to count.
         */
        val uptimePercent: Double? = null,
        /** The slowest current reading among monitors that answered, or null. */
        val slowestMs: Long? = null,
    ) {
        /**
         * The platform derives the line's length from its segments and takes no
         * setter for it, so this is a read-out rather than an input.
         */
        val max: Int get() = bands.sumOf { it.length }

        /**
         * How much history the line covers, for the one line of text beside it.
         *
         * Worth stating: `ProgressStyle` draws no axis and no labels, so without it
         * a bar with an outage a third of the way along could be a bad ten minutes
         * or a bad eight hours.
         */
        val spanLabel: String
            get() {
                val minutes = spanMs / 60_000
                return when {
                    minutes < 60 -> "${minutes}m"
                    minutes % 60 == 0L -> "${minutes / 60}h"
                    else -> "${minutes / 60}h ${minutes % 60}m"
                }
            }

        /**
         * How far back the line reaches, short enough to draw inside an icon.
         *
         * The coarser sibling of [spanLabel]: "23h" where that says "23h 12m". The
         * icon slot at the start of the line is a 20dp square that crops to square, so
         * anything past three characters arrives unreadable — the minutes have to go.
         * Rounded down, because the line genuinely does not reach further back than
         * this, and a label that overstates its own window is the bug this file spent
         * a release fixing.
         */
        val spanShortLabel: String
            get() {
                val minutes = spanMs / 60_000
                return if (minutes < 60) "${minutes}m" else "${minutes / 60}h"
            }

        /**
         * The countdown, short enough to draw inside an icon.
         *
         * Whole minutes, rounded up, and never seconds. The card is redrawn by a loop
         * that sleeps 15 to 60 seconds, so a seconds display would be wrong for most
         * of its life — it would tick down, freeze, then jump. A minute is the finest
         * unit this notification can honestly claim.
         *
         * Rounded *up* so it never reads "0m" while a check is still pending: the last
         * minute counts as one, and zero is reserved for actually due.
         */
        val countdownLabel: String
            get() {
                if (nextCheckInMs <= 0L) return "now"
                val minutes = (nextCheckInMs + 59_999L) / 60_000L
                return when {
                    minutes < 60 -> "${minutes}m"
                    minutes % 60 == 0L -> "${minutes / 60}h"
                    else -> "${minutes / 60}h${minutes % 60}m"
                }
            }

        /**
         * What is drawn at the end of the line: the countdown, the uptime or the
         * latency, whichever was chosen in Settings.
         *
         * The same square slot as the countdown, so the same budget of about five
         * characters. "n/a" rather than a dash when there is nothing to show,
         * because a lone dash in a 20dp square reads as a rendering fault.
         */
        val endLabel: String
            get() = when (label) {
                LiveLabel.NEXT_CHECK -> countdownLabel
                LiveLabel.UPTIME -> uptimePercent?.let(::shortPercent) ?: "n/a"
                LiveLabel.LATENCY -> slowestMs?.let(::shortLatency) ?: "n/a"
            }

        /**
         * The figure as a short phrase, for beside the line where "last 6h" already
         * names the window: "next check in 4m", "83% up", "slowest 240 ms".
         */
        val labelPhrase: String
            get() = when (label) {
                LiveLabel.NEXT_CHECK ->
                    if (nextCheckInMs <= 0L) "next check due now" else "next check in $countdownLabel"
                LiveLabel.UPTIME -> uptimePercent?.let { "${shortPercent(it)} up" } ?: "no checks yet"
                LiveLabel.LATENCY -> slowestMs?.let { "slowest ${spacedLatency(it)}" } ?: "no reply to time yet"
            }

        /**
         * The same figure as a sentence, for the text of the notice.
         *
         * Below Android 16 there is no line to put [endLabel] on, so this is the
         * only place the choice shows. Above it, it says in words what the end of
         * the line says in five characters.
         */
        val labelSentence: String
            get() = when (label) {
                LiveLabel.NEXT_CHECK ->
                    if (nextCheckInMs <= 0L) "Next check due now" else "Next check in $countdownLabel"
                LiveLabel.UPTIME -> uptimePercent?.let { "Up ${shortPercent(it)} over the last $spanLabel" }
                    ?: "No checks in the last $spanLabel"
                LiveLabel.LATENCY -> slowestMs?.let { "Slowest reply ${spacedLatency(it)}" }
                    ?: "No reply to time yet"
            }
    }

    /**
     * Builds the line, or null when there is nothing truthful to draw.
     *
     * Null on no enabled monitors and on no retained check inside the window: an
     * empty bar reads as "all clear over the whole span", which is a claim, and a
     * monitor nobody has checked yet has not earned it. Callers fall back to the
     * plain notification.
     */
    fun of(
        monitors: List<Monitor>,
        runtimes: Map<String, MonitorRuntime>,
        nowMs: Long,
        offline: Boolean = false,
        window: LiveWindow = LiveWindow.AUTO,
        label: LiveLabel = LiveLabel.NEXT_CHECK,
    ): Timeline? {
        val active = monitors.filter { it.enabled }
        if (active.isEmpty()) return null
        if (window != LiveWindow.AUTO) return fixed(active, monitors, runtimes, nowMs, offline, window, label)

        // Only checks that have actually happened, and only ones the line can draw.
        // `at` is 0 on the placeholder runtime; a sample stamped in the future would
        // anchor the window somewhere the tracker cannot be; and anything older than
        // the ceiling is dropped *before* the window is measured rather than after.
        //
        // That last one was a bug worth naming. The span used to be measured from the
        // oldest retained check of any age, then clamped to MAX_SPAN_MS — so a single
        // straggler older than a day stretched the line to a full day and was then
        // skipped for falling before `windowStart`. Every bucket between the window's
        // left edge and the first drawable check stayed UNKNOWN, and carrying forward
        // cannot rescue them: it propagates left to right, and there is no earlier
        // bucket to inherit from. On a device that was half a bar of grey under a
        // label claiming a full day of history.
        val cutoff = nowMs - MAX_SPAN_MS
        val samples = active.flatMap { runtimes[it.id]?.samples ?: emptyList() }
            .filter { it.at in 1..nowMs && it.at >= cutoff }
        if (samples.isEmpty()) return null

        // Measured from the oldest check that survives that filter, so the label
        // describes the line actually drawn.
        //
        // One case still opens with grey, and it is the floor rather than the
        // ceiling: [MIN_SPAN_MS] raises the span above the real history whenever a
        // fleet has been watched for less than ten minutes, which puts `windowStart`
        // before the oldest sample again. A brand-new install draws four fifths of
        // its bar in UNKNOWN for its first ten minutes. That is left deliberately —
        // grey there is *true*, nothing was checked yet, and the floor exists so the
        // axis stops relabelling itself every time a check lands on a ninety-second
        // window. It is worth knowing about rather than rediscovering: see
        // `a brand new fleet is mostly unknown, and says so` in LiveTimelineTest.
        val spanMs = (nowMs - samples.minOf { it.at }).coerceIn(MIN_SPAN_MS, MAX_SPAN_MS)
        val windowStart = nowMs - spanMs
        // Integer division, so the last bucket absorbs the remainder. Rounding it
        // the other way would put `now` past the end of the line.
        val bucketMs = (spanMs / BUCKETS).coerceAtLeast(1L)

        // Each monitor is bucketed and carried forward on its own, and only then are
        // they merged worst-wins.
        //
        // Doing it the other way round — one shared array, carry forward the merged
        // tone — let a fast healthy monitor erase a slow one's *ongoing* outage. A
        // monitor checked hourly fails; a monitor checked every five minutes passes
        // into every bucket after it; the merged tone in those buckets is UP because
        // the failing monitor left no sample there, so the carry-forward had nothing
        // to preserve. The line drew green all the way to the right edge under a red
        // tracker and a "1 DOWN" chip. Carrying each monitor's own last verdict is
        // what the app already believes about `Health`, and it has to happen before
        // the merge for the belief to survive it.
        val perMonitor = active.map { monitor ->
            val own = MutableList(BUCKETS) { Tone.UNKNOWN }
            runtimes[monitor.id]?.samples.orEmpty().forEach { sample ->
                if (sample.at < windowStart || sample.at > nowMs) return@forEach
                val index = ((sample.at - windowStart) / bucketMs).toInt().coerceIn(0, BUCKETS - 1)
                val tone = if (sample.ok) Tone.UP else Tone.DOWN
                // Worst wins inside one monitor's own bucket too: a failed check is
                // the thing worth seeing, however many of its neighbours passed.
                if (rank(tone) > rank(own[index])) own[index] = tone
            }
            for (i in 1 until BUCKETS) {
                if (own[i] == Tone.UNKNOWN) own[i] = own[i - 1]
            }
            own
        }

        // Worst wins across the fleet: one monitor failing in a slice of time is what
        // the line is for, however many others were fine in the same slice.
        val tones = MutableList(BUCKETS) { i ->
            perMonitor.map { it[i] }.maxByOrNull { rank(it) } ?: Tone.UNKNOWN
        }

        return assemble(tones, active, monitors, runtimes, nowMs, offline, spanMs, label)
    }

    /**
     * A window the user chose rather than one measured from the history.
     *
     * Two differences from [of]'s own window. The span is exactly what was asked
     * for, so a fresh install on "24 h" draws mostly grey, which is true: nothing
     * was checked yet. And each monitor's last verdict from *before* the window is
     * carried into its first bucket, because on a one hour window a monitor checked
     * every two hours would otherwise be unknown for most of the line while its
     * state is perfectly well known.
     */
    private fun fixed(
        active: List<Monitor>,
        monitors: List<Monitor>,
        runtimes: Map<String, MonitorRuntime>,
        nowMs: Long,
        offline: Boolean,
        window: LiveWindow,
        label: LiveLabel,
    ): Timeline? {
        val spanMs = window.ms
        val windowStart = nowMs - spanMs
        val bucketMs = (spanMs / BUCKETS).coerceAtLeast(1L)
        var anything = false
        val perMonitor = active.map { monitor ->
            val own = MutableList(BUCKETS) { Tone.UNKNOWN }
            val samples = runtimes[monitor.id]?.samples.orEmpty().filter { it.at in 1..nowMs }
            samples.lastOrNull { it.at < windowStart }?.let { before ->
                own[0] = if (before.ok) Tone.UP else Tone.DOWN
                anything = true
            }
            samples.forEach { sample ->
                if (sample.at < windowStart) return@forEach
                val index = ((sample.at - windowStart) / bucketMs).toInt().coerceIn(0, BUCKETS - 1)
                val tone = if (sample.ok) Tone.UP else Tone.DOWN
                if (rank(tone) > rank(own[index])) own[index] = tone
                anything = true
            }
            for (i in 1 until BUCKETS) {
                if (own[i] == Tone.UNKNOWN) own[i] = own[i - 1]
            }
            own
        }
        if (!anything) return null
        val tones = MutableList(BUCKETS) { i ->
            perMonitor.map { it[i] }.maxByOrNull { rank(it) } ?: Tone.UNKNOWN
        }
        return assemble(tones, active, monitors, runtimes, nowMs, offline, spanMs, label)
    }

    private fun assemble(
        tones: List<Tone>,
        active: List<Monitor>,
        monitors: List<Monitor>,
        runtimes: Map<String, MonitorRuntime>,
        nowMs: Long,
        offline: Boolean,
        spanMs: Long,
        label: LiveLabel,
    ): Timeline {
        val elapsed = capBands(coalesce(tones))
        val fleet = Summary.of(monitors, runtimes)
        val pacing = pacing(active, runtimes, nowMs)
        return Timeline(
            bands = elapsed + Band(aheadLength(pacing), Tone.AHEAD),
            markers = markers(elapsed),
            // Merging preserves total length, so the elapsed part is still exactly
            // BUCKETS wide however many bands it ended up as.
            progress = BUCKETS,
            spanMs = spanMs,
            current = current(fleet, offline),
            chip = chip(fleet, offline),
            nextCheckInMs = pacing.waitMs,
            label = label,
            uptimePercent = uptime(active, runtimes, nowMs - spanMs, nowMs),
            slowestMs = slowest(active, runtimes),
        )
    }

    /** Passing checks over all checks in the window, across [active]. */
    private fun uptime(
        active: List<Monitor>,
        runtimes: Map<String, MonitorRuntime>,
        fromMs: Long,
        nowMs: Long,
    ): Double? {
        var total = 0
        var passed = 0
        active.forEach { monitor ->
            runtimes[monitor.id]?.samples.orEmpty().forEach { sample ->
                if (sample.at in fromMs..nowMs) {
                    total++
                    if (sample.ok) passed++
                }
            }
        }
        return if (total == 0) null else passed * 100.0 / total
    }

    /**
     * The slowest last reading among monitors that are answering.
     *
     * A monitor that is down has no latency worth the name, its last reading is a
     * timeout or zero, so it is left out rather than allowed to win.
     */
    private fun slowest(active: List<Monitor>, runtimes: Map<String, MonitorRuntime>): Long? =
        active.mapNotNull { monitor ->
            val runtime = runtimes[monitor.id] ?: return@mapNotNull null
            runtime.lastLatencyMs.takeIf {
                it > 0 && (runtime.health == Health.UP || runtime.health == Health.DEGRADED)
            }
        }.maxOrNull()

    /**
     * "100%", "99.4%", "87%". Floored, never rounded, so 99.96 reads 99.9 and not a
     * clean record the history does not have.
     */
    internal fun shortPercent(value: Double): String = when {
        value >= 100.0 -> "100%"
        value >= 99.0 -> "${kotlin.math.floor(value * 10) / 10}%"
        else -> "${kotlin.math.floor(value).toInt()}%"
    }

    /** "180 ms", "1.9 s": the sentence form of [shortLatency]. */
    internal fun spacedLatency(ms: Long): String = when {
        ms < 1_000 -> "$ms ms"
        else -> String.format(java.util.Locale.ROOT, "%.1f s", ms / 1000.0)
    }

    /** "180ms", "1.9s": five characters at most, for the end of the line. */
    internal fun shortLatency(ms: Long): String = when {
        ms < 1_000 -> "${ms}ms"
        else -> String.format(java.util.Locale.ROOT, "%.1fs", ms / 1000.0)
    }

    /**
     * How long the tail past the tracker is: the wait until the next check is due.
     *
     * The next check is the only future this app can honestly draw. This used to
     * return the shortest *interval* in the fleet, which is a constant — it did not
     * read the clock or the last check at all, so the tail never changed width and,
     * because the elapsed part is always exactly [BUCKETS] wide, the tracker never
     * moved either. A destination you never get closer to is not a journey, and on a
     * device it read as a dead grey stub on the end of the bar.
     *
     * It now counts down from the interval to zero as the next check approaches, and
     * resets when one lands. Due-ness is [DueCheck]'s definition, slack included, so
     * the tail empties exactly when the checker considers the monitor due rather than
     * a slack-width later.
     *
     * Drawn as a fraction of one interval rather than to the history's scale, which
     * is the only way it can move at all.
     *
     * To scale it cannot. A bucket is `spanMs / BUCKETS`; on the fifteen hours of
     * history a real fleet accumulates that is nearly nineteen minutes, so a
     * fifteen-minute countdown is less than one bucket and floors to the same value
     * at every point in the cycle. Measured on the reported card: interval 15, tail 1
     * with 14 minutes to go and tail 1 with none, tracker pinned at 97.96% either way.
     * A countdown that renders identically full and empty is not a countdown.
     *
     * So the tail spends a fixed budget — the same sixth of the bar it was already
     * ceilinged at — and empties across it as the check falls due. The bar's left is
     * still a time axis; the tail is a gauge, and it is the only part of the drawing
     * that is not to scale. That is a deliberate trade for the one thing this card
     * exists to be: alive.
     *
     * The floor of one keeps the tracker off the extreme edge, where the platform
     * draws it half outside the bar.
     */
    /** The wait the tail draws and the label states, so the two cannot disagree. */
    private data class Pacing(val waitMs: Long, val intervalMs: Long) {
        val fraction: Double get() = waitMs.toDouble() / intervalMs
    }

    /**
     * The countdown to the next check, paced by one monitor — the fastest — rather
     * than by whichever of them is due soonest.
     *
     * "Soonest across the fleet" is the literal next check and it is useless as a
     * gauge. Eight monitors on a fifteen-minute interval are staggered, so one of them
     * is always nearly due: the minimum sits near zero and resets every time *any* of
     * them fires, about every two minutes. Reported from a device as the tail "not
     * moving at all" — it was moving, in a fast erratic sawtooth around the floor,
     * which is indistinguishable from stuck.
     *
     * One monitor's own cycle gives a countdown with a period you can actually see,
     * and the fastest is the right one: it is the fleet's real cadence, and the
     * monitor whose next check arrives first on average. Ties broken by id so the
     * choice does not wander between renders and make the tail jump.
     */
    private fun pacing(
        active: List<Monitor>,
        runtimes: Map<String, MonitorRuntime>,
        nowMs: Long,
    ): Pacing {
        val pacer = active.minWithOrNull(compareBy({ it.intervalMinutes }, { it.id }))
            ?: return Pacing(waitMs = 0L, intervalMs = 60_000L)
        val intervalMs = pacer.intervalMinutes.coerceAtLeast(1) * 60_000L
        val last = runtimes[pacer.id]?.lastCheckedAt ?: 0L
        // Never checked, or a stamp in the future because the wall clock moved
        // backwards: due now, same as DueCheck decides.
        val waitMs = if (last <= 0L || last > nowMs) {
            0L
        } else {
            (last + intervalMs - DueCheck.SLACK_MS - nowMs).coerceIn(0L, intervalMs)
        }
        return Pacing(waitMs = waitMs, intervalMs = intervalMs)
    }

    private fun aheadLength(pacing: Pacing): Int {
        val budget = BUCKETS / 6
        return Math.round(budget * pacing.fraction).toInt().coerceIn(1, budget)
    }

    private fun rank(tone: Tone): Int = when (tone) {
        Tone.UNKNOWN -> 0
        Tone.AHEAD -> 0
        Tone.UP -> 1
        Tone.DEGRADED -> 2
        Tone.DOWN -> 3
    }

    /** Consecutive buckets of one tone become one band. */
    private fun coalesce(tones: List<Tone>): List<Band> {
        val bands = mutableListOf<Band>()
        for (tone in tones) {
            val last = bands.lastOrNull()
            if (last != null && last.tone == tone) {
                bands[bands.size - 1] = last.copy(length = last.length + 1)
            } else {
                bands += Band(1, tone)
            }
        }
        return bands
    }

    /**
     * Compresses a busy line towards [MAX_BANDS] without inventing an outage.
     *
     * This used to absorb the shortest non-outage band into a neighbour. The
     * neighbours of an UP band are DOWN by construction — [coalesce] has already
     * fused same-tone runs — so the absorbed uptime could only ever be handed to an
     * outage, and the adjacency pass then fused the two red runs into one. Every
     * iteration turned uptime into downtime and grew the claimed outage.
     *
     * Measured on a monitor that alternated pass/fail every thirty minutes for a
     * day: 24 buckets genuinely failed, the line drew 40, and the longest drawn run
     * was 33 buckets — a claimed sixteen and a half hours of continuous downtime
     * that never happened. The old comment promised outages were never merged away,
     * which was true and beside the point: nothing stopped them being fabricated,
     * and on a monitoring product that is the worse direction to be wrong in.
     *
     * So compression now reports composition instead of absorbing. The line is cut
     * into equal groups and each group states what it actually held, in the order
     * the tones first appeared, at their real bucket counts. Totals per tone survive
     * exactly — a day that was down for a quarter of its buckets still draws a
     * quarter red — at the cost of the *order within a group* being approximated.
     * That is a bounded lie about when, in exchange for no lie at all about how much,
     * and a flapping monitor now reads as flapping rather than as one long outage.
     */
    private fun capBands(bands: List<Band>): List<Band> {
        if (bands.size <= MAX_BANDS) return bands
        val tones = bands.flatMap { band -> List(band.length) { band.tone } }
        // Two tones per group in the ordinary case (UP and DOWN), so half the budget
        // is the number of groups. UNKNOWN can only ever lead, so a third tone can
        // appear in the first group alone.
        val groups = (MAX_BANDS / 2).coerceAtLeast(1)
        val groupSize = ((tones.size + groups - 1) / groups).coerceAtLeast(1)
        val out = mutableListOf<Band>()
        tones.chunked(groupSize).forEach { chunk ->
            chunk.distinct().forEach { tone -> out += Band(chunk.count { it == tone }, tone) }
        }
        return fuse(out)
    }

    /** Fuses adjacent same-tone bands, which grouping can leave behind. */
    private fun fuse(bands: List<Band>): List<Band> {
        val out = mutableListOf<Band>()
        for (band in bands) {
            val last = out.lastOrNull()
            if (last != null && last.tone == band.tone) {
                out[out.size - 1] = last.copy(length = last.length + band.length)
            } else {
                out += band
            }
        }
        return out
    }

    /**
     * One marker where each outage began, newest kept.
     *
     * Not redundant with the red band it sits on. The platform draws a point as a
     * squared block noticeably taller than the line, and the shortest possible
     * outage band is one bucket — a couple of per cent of the width, which on
     * screen is a sliver easy to read as a rendering artefact. The block is what
     * makes a single failed check findable; the band is what makes a long one
     * measurable.
     */
    private fun markers(bands: List<Band>): List<Marker> {
        val marks = mutableListOf<Marker>()
        var position = 0
        for (band in bands) {
            if (band.tone == Tone.DOWN) marks += Marker(position, Tone.DOWN)
            position += band.length
        }
        return marks.takeLast(MAX_MARKERS)
    }

    private fun current(fleet: Summary.Fleet, offline: Boolean): Tone = when {
        // Offline is not a verdict about the fleet — 1.3.0 stopped treating losing
        // signal as an outage, and the tracker must not re-introduce it as one.
        offline -> Tone.UNKNOWN
        fleet.total == 0 -> Tone.UNKNOWN
        fleet.down > 0 -> Tone.DOWN
        fleet.degraded > 0 -> Tone.DEGRADED
        fleet.entries.all { it.health == Health.PAUSED } -> Tone.UNKNOWN
        else -> Tone.UP
    }

    /**
     * The chip, which sits beside the clock and gets a handful of characters.
     *
     * Counts rather than names: the shortest monitor name in a real fleet does not
     * fit, and a truncated one is worse than a number. Uppercase DOWN because it
     * is the one state worth reading from across a room.
     */
    private fun chip(fleet: Summary.Fleet, offline: Boolean): String = when {
        offline -> "offline"
        fleet.urgentPending > 0 -> "${fleet.urgentPending} URGENT"
        fleet.down > 0 -> "${fleet.down} DOWN"
        fleet.degraded > 0 -> "${fleet.degraded} slow"
        fleet.total == 0 -> "idle"
        fleet.entries.all { it.health == Health.PAUSED } -> "paused"
        else -> "${fleet.total} up"
    }
}
