package me.river.nightbell.domain

/**
 * Tells "that server is slow" apart from "this phone's connection is slow".
 *
 * A latency measured from a phone is the sum of two things: the round trip
 * across whatever network the phone is on, and the time the server actually
 * spent. Those are indistinguishable from one measurement, which is why bad
 * wifi makes *every* monitor breach its SLO at once — and every one of those
 * alerts is wrong.
 *
 * The fix is a control: time a reference endpoint that is always up, in the same
 * conditions, and use it to estimate what the network is contributing right now.
 *
 * The estimate is deliberately of the network's **excess**, not its total. A
 * connection with a 300 ms floor is not "slow" — that is simply what that
 * connection costs, and the SLO the user set was set while living with it.
 * What matters is the reference being slower *than it usually is*, because that
 * difference is the part that has nothing to do with the server:
 *
 * ```
 * excess   = current reference RTT − the reference's own good-conditions floor
 * adjusted = measured latency − excess
 * ```
 *
 * Every threshold here is chosen to fail toward *reporting* slowness rather than
 * hiding it, because a missed "slow" alert costs less than a real degradation
 * silently written off as bad wifi.
 */
object NetworkBaseline {

    /**
     * How much to trust a latency measurement given what the reference is doing.
     */
    enum class Trust {
        /**
         * No usable reference data — too few readings, all stale, or the
         * reference is unreachable (plenty of networks block it). Judge the raw
         * number, exactly as before this existed.
         */
        UNKNOWN,

        /** The reference is behaving normally, so the measurement is the server's. */
        CLEAR,

        /** The reference is slower than usual; its excess has been subtracted. */
        ADJUSTED,

        /**
         * The reference is so far off its floor that nothing measured through
         * this connection means anything. Callers should not claim degradation.
         */
        UNRELIABLE,
    }

    data class Verdict(
        /** Measured latency with the network's excess removed. */
        val adjustedMs: Long,
        /** What the connection appears to be adding right now. */
        val excessMs: Long,
        /** Most recent reference round trip, smoothed. 0 when unknown. */
        val referenceMs: Long,
        /** The reference's good-conditions floor. 0 when unknown. */
        val floorMs: Long,
        val trust: Trust,
    ) {
        /** True when the raw number would have breached but the adjusted one may not. */
        val compensated: Boolean get() = excessMs > 0L

        /** The connection is in no state to judge anything through. */
        val unreliable: Boolean get() = trust == Trust.UNRELIABLE
    }

    /** Below this many usable readings there is no floor worth estimating. */
    const val MIN_READINGS = 4

    /** Readings older than this describe a network the phone may have left. */
    const val MAX_AGE_MS = 6L * 60 * 60 * 1000

    /** Keep the window short enough to track a change of network. */
    const val WINDOW = 24

    /**
     * Excess under this is ordinary jitter. Subtracting it would inflate every
     * verdict with noise and start hiding genuine slowness.
     */
    const val NOISE_FLOOR_MS = 40L

    /** Both of these must hold before a connection is written off entirely. */
    const val UNRELIABLE_FACTOR = 4.0
    const val UNRELIABLE_EXCESS_MS = 750L

    /**
     * The reference's good-conditions floor, as the 25th percentile of the window.
     *
     * Not the median: if the connection has been poor for most of the window the
     * median is poor too, the excess collapses to zero and the compensation
     * quietly stops working exactly when it is needed. Not the minimum either —
     * one unusually lucky round trip would drag the floor down, inflate the
     * excess for every later reading and start suppressing real alerts. A low
     * percentile keeps a recent good reading in view while still needing more
     * than one of them.
     */
    fun floorOf(readings: List<Long>): Long {
        val sorted = readings.filter { it > 0 }.sorted()
        if (sorted.isEmpty()) return 0L
        val index = ((sorted.size - 1) * 0.25).toInt()
        return sorted[index]
    }

    /**
     * The current reference RTT: the median of the three most recent readings.
     *
     * A single sample is far too jumpy to subtract from anything — one unlucky
     * round trip would wipe out a real degradation.
     */
    fun currentOf(readings: List<Long>): Long {
        val recent = readings.filter { it > 0 }.takeLast(3).sorted()
        if (recent.isEmpty()) return 0L
        return recent[recent.size / 2]
    }

    /**
     * Judges [observedMs] against the reference [readings], newest last.
     *
     * [nowMs] and each reading's timestamp are used to drop stale readings, so a
     * phone that moved from office wifi to cellular is not held to the office's
     * floor.
     */
    fun judge(
        observedMs: Long,
        readings: List<ReferenceSample>,
        nowMs: Long,
    ): Verdict {
        val fresh = readings
            .filter { it.rttMs > 0 && nowMs - it.at <= MAX_AGE_MS }
            .takeLast(WINDOW)
            .map { it.rttMs }

        if (fresh.size < MIN_READINGS) {
            return Verdict(observedMs, 0L, 0L, 0L, Trust.UNKNOWN)
        }

        val floor = floorOf(fresh)
        val current = currentOf(fresh)
        val excess = current - floor

        if (excess <= NOISE_FLOOR_MS) {
            return Verdict(observedMs, 0L, current, floor, Trust.CLEAR)
        }

        // Both a large absolute excess and a large relative jump. Either alone
        // fires too easily: 4x a 30 ms floor is still only 120 ms, and 750 ms on
        // a satellite link may be a normal amount of variation.
        val unreliable = excess >= UNRELIABLE_EXCESS_MS && current >= floor * UNRELIABLE_FACTOR

        return Verdict(
            adjustedMs = (observedMs - excess).coerceAtLeast(0L),
            excessMs = excess,
            referenceMs = current,
            floorMs = floor,
            trust = if (unreliable) Trust.UNRELIABLE else Trust.ADJUSTED,
        )
    }

    /**
     * Folds a new reading into the stored window, dropping stale and surplus ones.
     *
     * A failed probe contributes nothing rather than a zero: the reference being
     * unreachable usually means the network blocks it, not that the connection is
     * infinitely slow, and treating it as data would suppress every alert.
     */
    fun record(
        readings: List<ReferenceSample>,
        rttMs: Long?,
        nowMs: Long,
    ): List<ReferenceSample> {
        val kept = readings.filter { it.rttMs > 0 && nowMs - it.at <= MAX_AGE_MS }
        val next = if (rttMs != null && rttMs > 0) {
            kept + ReferenceSample(at = nowMs, rttMs = rttMs)
        } else {
            kept
        }
        return next.takeLast(WINDOW)
    }

    /** Whether a fresh probe is worth making, given the newest reading. */
    fun needsProbe(readings: List<ReferenceSample>, nowMs: Long, minIntervalMs: Long): Boolean {
        val newest = readings.maxOfOrNull { it.at } ?: return true
        return nowMs - newest >= minIntervalMs
    }
}
