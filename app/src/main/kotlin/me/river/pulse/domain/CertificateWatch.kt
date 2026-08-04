package me.river.pulse.domain

/**
 * The TLS certificate track.
 *
 * A certificate about to expire is the one outage you can see coming, and Pulse
 * was throwing the evidence away: the expiry date arrives free with every HTTPS
 * handshake the checker already performs, and until now nothing read it. The
 * failure mode it prevents is specific and common — a cert lapses at 03:00, every
 * client starts refusing the connection at once, and the only signal the app
 * could give was the TLS error *after* the fact.
 *
 * Deliberately its own track, for the same reason latency is:
 *
 *  - It is not an outage. The site is up, answers correctly, and will keep doing
 *    so for days. Folding this into the down track would put a red card on a
 *    healthy service, and a dashboard that cries wolf about next Tuesday is a
 *    dashboard people stop reading.
 *  - It is not urgent in the paging sense. There is nothing to do at 03:00 about
 *    a cert with nine days left, so it must never wake anyone.
 *  - Its useful cadence is days, not minutes. Every other track's cooldown is
 *    measured against how fast a service can flap; this one is measured against
 *    how fast a human can renew a certificate.
 *
 * Pure, so the whole escalation is testable without a device or a socket.
 */
object CertificateWatch {

    enum class Level {
        /** No certificate seen — a plain-HTTP monitor, or nothing checked yet. */
        UNKNOWN,

        /** Comfortably in date. */
        OK,

        /** Inside the warning window. Worth knowing about, worth ignoring until Monday. */
        WARN,

        /** Close enough that renewing it is today's problem. */
        CRITICAL,

        /** Already past its notAfter. Clients are refusing this right now. */
        EXPIRED,
        ;

        /** Ordering for "has this got worse since we last said something". */
        val rank: Int get() = ordinal

        val label: String
            get() = when (this) {
                UNKNOWN -> "No certificate"
                OK -> "Certificate valid"
                WARN -> "Certificate expiring"
                CRITICAL -> "Certificate expiring very soon"
                EXPIRED -> "Certificate expired"
            }
    }

    /**
     * Whole days until [expiresAt], rounded *down*, and negative once past.
     *
     * Rounding down is the safe direction: a certificate with 23 hours left has
     * "0 days", not "1 day", because telling someone they have a day when they
     * have an evening is exactly the error this feature exists to prevent.
     */
    fun daysLeft(expiresAt: Long, nowMs: Long): Long {
        if (expiresAt <= 0L) return 0L
        return Math.floorDiv(expiresAt - nowMs, DAY_MS)
    }

    /**
     * @param warnDays days before expiry at which to start saying something. 0 disables the track.
     * @param criticalDays days before expiry at which it stops being advisory.
     */
    fun level(expiresAt: Long, nowMs: Long, warnDays: Int, criticalDays: Int): Level {
        if (expiresAt <= 0L) return Level.UNKNOWN
        if (warnDays <= 0) return Level.OK
        if (nowMs >= expiresAt) return Level.EXPIRED
        val days = daysLeft(expiresAt, nowMs)
        // Clamped so a critical threshold set above the warning one cannot create
        // a window where nothing is ever said.
        val critical = criticalDays.coerceIn(0, warnDays)
        return when {
            days <= critical -> Level.CRITICAL
            days <= warnDays -> Level.WARN
            else -> Level.OK
        }
    }

    /**
     * Whether to raise the certificate notice now.
     *
     * Speaks on escalation, and then at most once a day while it stays bad. The
     * alternative — announcing on every check — is fourteen days of identical
     * notifications about something that changes once, which trains people to
     * swipe the one that matters.
     *
     * @param alertedLevel the [Level.rank] most recently announced for this monitor
     * @param reminderHours gap between repeat notices at an unchanged level
     */
    fun shouldAlert(
        level: Level,
        alertedLevel: Int,
        lastAlertAt: Long,
        nowMs: Long,
        reminderHours: Int = 24,
    ): Boolean {
        if (level == Level.UNKNOWN || level == Level.OK) return false
        if (level.rank > alertedLevel) return true
        if (level.rank < alertedLevel) return false
        if (lastAlertAt <= 0L) return true
        return nowMs - lastAlertAt >= reminderHours.coerceAtLeast(1) * 60L * 60L * 1000L
    }

    /**
     * The level to remember as announced.
     *
     * Ratchets down as well as up: a renewed certificate has to reset the memory,
     * or the next genuine expiry a year later would be judged against a rank that
     * is already at CRITICAL and never announced at all.
     */
    fun alertedLevelAfter(level: Level): Int =
        if (level == Level.UNKNOWN || level == Level.OK) Level.OK.rank else level.rank

    /** One line, for a notification title. */
    fun headline(level: Level, daysLeft: Long, host: String): String = when (level) {
        Level.EXPIRED -> "$host: certificate has expired"
        Level.CRITICAL, Level.WARN -> when {
            daysLeft <= 0L -> "$host: certificate expires today"
            daysLeft == 1L -> "$host: certificate expires tomorrow"
            else -> "$host: certificate expires in $daysLeft days"
        }
        Level.OK, Level.UNKNOWN -> ""
    }

    /** Short form for a card tag: "cert 9d", "cert today", "cert expired". */
    fun tag(level: Level, daysLeft: Long): String = when (level) {
        Level.EXPIRED -> "cert expired"
        Level.CRITICAL, Level.WARN -> if (daysLeft <= 0L) "cert today" else "cert ${daysLeft}d"
        Level.OK, Level.UNKNOWN -> ""
    }

    const val DAY_MS = 24L * 60 * 60 * 1000
}
