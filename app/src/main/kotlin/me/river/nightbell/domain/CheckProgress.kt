package me.river.nightbell.domain

/**
 * Where a slow check has got to, for Test now to draw while it waits.
 *
 * Only UDP reports it. Every other kind answers or fails inside a second or
 * two. A UDP check cannot promise that, and in silent mode it is slow by design:
 * it hears nothing on purpose, so every try runs to its timeout before the ping.
 * At the defaults that was most of a minute behind a button reading "Running
 * check…" and nothing else, which looked exactly like a hang.
 */
sealed interface CheckProgress {

    /** Try [attempt] of [tries] went out at [sentAt] and waits up to [timeoutMs]. */
    data class UdpTry(
        val attempt: Int,
        val tries: Int,
        val timeoutMs: Long,
        val sentAt: Long,
        val silent: Boolean,
    ) : CheckProgress

    /** Silent mode heard nothing, which is the expected case, and is asking the host for an echo. */
    data class UdpPing(val tries: Int, val timeoutMs: Long, val sentAt: Long) : CheckProgress
}

/**
 * How long a UDP check can take, worked out before it runs from the same
 * numbers [me.river.nightbell.data.check.UdpChecker] uses, so the figure on the
 * screen and the wait behind it cannot drift apart.
 */
data class UdpPlan(val tries: Int, val timeoutMs: Long, val silent: Boolean) {

    /** The echo silent mode asks for after the last try, with the same timeout. */
    val pingMs: Long get() = if (silent) timeoutMs else 0L

    /**
     * Every try heard nothing, then the ping took its whole timeout. A try that
     * waited out its timeout is always past the one second gap between tries, so
     * the gap never adds anything here.
     */
    val worstCaseMs: Long get() = tries * timeoutMs + pingMs

    /** What is left of the worst case once [progress] is the stage running, [nowMs] into it. */
    fun remainingMs(progress: CheckProgress, nowMs: Long): Long = when (progress) {
        is CheckProgress.UdpTry -> {
            val inThisTry = (progress.timeoutMs - (nowMs - progress.sentAt)).coerceIn(0, progress.timeoutMs)
            inThisTry + (progress.tries - progress.attempt) * progress.timeoutMs + pingMs
        }
        is CheckProgress.UdpPing -> (progress.timeoutMs - (nowMs - progress.sentAt)).coerceIn(0, progress.timeoutMs)
    }

    companion object {
        fun of(monitor: Monitor): UdpPlan = UdpPlan(
            tries = monitor.udp.attempts.coerceIn(1, UdpWatch.MAX_ATTEMPTS),
            timeoutMs = monitor.timeoutSeconds.coerceIn(1, UdpWatch.MAX_WAIT_SECONDS) * 1000L,
            silent = monitor.udp.probe == UdpProbe.SILENT,
        )
    }
}
