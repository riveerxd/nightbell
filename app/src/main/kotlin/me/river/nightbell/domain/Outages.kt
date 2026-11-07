package me.river.nightbell.domain

/**
 * The outages in a monitor's stored history, and the two dates people ask for
 * first: when it last worked and when it last did not.
 *
 * Read off the samples, so it covers exactly what [GlobalSettings.historyDepth]
 * keeps and says how far back that is rather than implying it is everything.
 * An outage starts at the first failed check and ends at the first passing one
 * after it. The real start was somewhere between the last pass and that first
 * failure, and the screen says "first failed check" rather than pretending to
 * know which moment.
 */
object Outages {

    data class Outage(
        val startAt: Long,
        /** The first passing check after it, or null while it is still going. */
        val endAt: Long?,
        val failedChecks: Int,
        /** What the first failed check said. */
        val reason: String,
    ) {
        val ongoing: Boolean get() = endAt == null

        fun durationMs(nowMs: Long): Long = ((endAt ?: nowMs) - startAt).coerceAtLeast(0L)
    }

    data class Summary(
        /** Newest first. */
        val outages: List<Outage>,
        val lastSuccessAt: Long,
        val lastFailureAt: Long,
        /** The oldest stored check, which is as far back as any of this sees. */
        val sinceAt: Long,
        val checks: Int,
    ) {
        val isEmpty: Boolean get() = checks == 0

        /** Time spent down across the stored history, the ongoing one counted up to now. */
        fun downMs(nowMs: Long): Long = outages.sumOf { it.durationMs(nowMs) }
    }

    fun of(samples: List<Sample>): Summary {
        val ordered = samples.sortedBy { it.at }
        val outages = mutableListOf<Outage>()
        var open: Outage? = null
        for (sample in ordered) {
            if (!sample.ok) {
                open = open?.copy(failedChecks = open.failedChecks + 1)
                    ?: Outage(startAt = sample.at, endAt = null, failedChecks = 1, reason = sample.note)
            } else if (open != null) {
                outages += open.copy(endAt = sample.at)
                open = null
            }
        }
        open?.let { outages += it }
        return Summary(
            outages = outages.asReversed(),
            lastSuccessAt = ordered.lastOrNull { it.ok }?.at ?: 0L,
            lastFailureAt = ordered.lastOrNull { !it.ok }?.at ?: 0L,
            sinceAt = ordered.firstOrNull()?.at ?: 0L,
            checks = ordered.size,
        )
    }

    /** Packet loss over the stored pings, as a whole per cent, or null with no pings to count. */
    fun lossPercent(samples: List<Sample>): Int? {
        val pings = samples.mapNotNull { it.ping }
        val sent = pings.sumOf { it.sent }
        if (sent == 0) return null
        val lost = pings.sumOf { it.lost }
        return Math.round(lost * 100.0 / sent).toInt()
    }

    /** "3h 12m", "45m", "40s": an outage length, two units at most. */
    fun span(ms: Long): String {
        val s = ms / 1000
        return when {
            s < 1 -> "under 1s"
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60}m" + if (s % 60 != 0L && s < 600) " ${s % 60}s" else ""
            s < 86_400 -> "${s / 3600}h" + if ((s % 3600) / 60 != 0L) " ${(s % 3600) / 60}m" else ""
            else -> "${s / 86_400}d" + if ((s % 86_400) / 3600 != 0L) " ${(s % 86_400) / 3600}h" else ""
        }
    }
}

/**
 * Every monitor pointed at one host, and what their verdicts mean together.
 *
 * Issue 21's table, made into a sentence: ping up and a port down is a service
 * problem, ping down and a port up is ICMP being filtered, both down is the
 * host or the path to it. The point of watching a host two ways is reading the
 * two answers side by side, and leaving that to the user means leaving them to
 * open two screens at three in the morning.
 */
object SameHost {

    fun siblings(monitor: Monitor, all: List<Monitor>): List<Monitor> {
        val host = NetworkTarget.hostOf(monitor)
        if (host.isEmpty()) return emptyList()
        return all.filter { it.id != monitor.id && NetworkTarget.hostOf(it) == host }
    }

    /** How a sibling is named in the sentence: a port by its number, anything else by its name. */
    fun nameOf(monitor: Monitor): String = when (monitor.kind) {
        MonitorKind.TCP_PORT -> "port ${monitor.network.port}"
        MonitorKind.UDP_PORT -> "port ${monitor.network.port}/udp"
        MonitorKind.PING -> "ping"
        else -> monitor.displayName
    }

    /**
     * A TCP and a UDP check on one port that disagree. The confusion issue 21's
     * reporter ran into from the other side: a service on one of the two
     * protocols reads as down to a check on the other.
     */
    private fun protocolSplit(judged: List<Pair<Monitor, Health>>): String? {
        val tcp = judged.filter { it.first.kind == MonitorKind.TCP_PORT }
        val udp = judged.filter { it.first.kind == MonitorKind.UDP_PORT }
        for ((t, tHealth) in tcp) {
            val (_, uHealth) = udp.firstOrNull { it.first.network.port == t.network.port } ?: continue
            val port = t.network.port
            val tUp = tHealth != Health.DOWN
            val uUp = uHealth != Health.DOWN
            if (tUp && !uUp) return "$port/tcp accepts connections but $port/udp does not answer. The service may only be listening on TCP."
            if (!tUp && uUp) return "$port/udp answers but $port/tcp does not. The service may only be listening on UDP."
        }
        return null
    }

    /** How an up sibling is said: a silent UDP check only proves nothing refused it. */
    private fun answering(monitor: Monitor, one: String, many: String, count: Int): String =
        if (monitor.kind == MonitorKind.UDP_PORT && monitor.udp.probe == UdpProbe.SILENT && count == 1) {
            "is not refused"
        } else if (count == 1) {
            one
        } else {
            many
        }

    /**
     * One line reading the pair, or null when there is no pair to read: no ping
     * among them, no service check among them, or a verdict missing on either side.
     *
     * Degraded counts as answering, because it did. Paused and not yet checked
     * are left out, since neither is a verdict.
     */
    fun reading(group: List<Pair<Monitor, Health>>): String? {
        val judged = group.filter { (_, health) -> health != Health.UNKNOWN && health != Health.PAUSED }
        val split = protocolSplit(judged)
        val pings = judged.filter { it.first.kind == MonitorKind.PING }
        val services = judged.filter { it.first.kind != MonitorKind.PING }
        if (pings.isEmpty() || services.isEmpty()) return split
        val pingUp = pings.any { it.second != Health.DOWN }
        val down = services.filter { it.second == Health.DOWN }.map { nameOf(it.first) }
        val upMonitors = services.filter { it.second != Health.DOWN }.map { it.first }
        val up = upMonitors.map { nameOf(it) }
        val sentence = when {
            pingUp && down.isEmpty() -> "The host answers ping and ${everything(services.size)}."
            pingUp -> "The host answers ping, but ${list(down)} ${verb(down, "is", "are")} down. " +
                "The network is fine; look at the service."
            up.isNotEmpty() -> "No ping reply, but ${list(up)} ${answering(upMonitors.first(), "answers", "answer", up.size)}. " +
                "ICMP is probably filtered on the way."
            else -> "Neither ping nor ${list(down)} ${verb(down, "answers", "answer")}. " +
                "The host, or the route to it, is down."
        }
        return if (split != null) "$split $sentence" else sentence
    }

    private fun everything(count: Int) = if (count == 1) "the other check answers too" else "the other $count checks answer too"

    private fun verb(names: List<String>, one: String, many: String) = if (names.size == 1) one else many

    private fun list(names: List<String>): String = when (names.size) {
        0 -> ""
        1 -> names[0]
        2 -> "${names[0]} and ${names[1]}"
        else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
    }
}
