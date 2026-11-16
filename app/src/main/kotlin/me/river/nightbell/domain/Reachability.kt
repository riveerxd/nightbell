package me.river.nightbell.domain

/**
 * Telling "the service is down" apart from "this phone is not on a network".
 *
 * Reported from real use: standing in an underground garage, every monitor fails
 * at once and every one of them pages. Nothing was down. The phone had a bar of
 * signal that carried no traffic, which is the residual gap
 * [me.river.nightbell.data.net.NetworkMonitor] documents and deliberately leaves
 * open: it refuses to require `NET_CAPABILITY_VALIDATED` because that also goes
 * dark on firewalled LANs and filtered DNS, and a monitor that silently stops
 * monitoring is worse than one that cries wolf.
 *
 * So the network is not asked whether it works. It is asked to prove it, once,
 * at the only moment the answer changes anything: a check has just failed
 * without reaching anything at all.
 *
 * Three rules hold this together, and each of them is a decision that could
 * have gone the other way:
 *
 *  1. **Only failures that never reached anything are worth confirming.** A 500,
 *     a body that did not match, an element that vanished: all of those arrived
 *     over a working connection and are proof in themselves that this phone can
 *     reach the internet. Asking the reference about them would spend a request
 *     to learn nothing, and would put a real outage one flaky probe away from
 *     being swallowed.
 *  2. **The reference is asked whether it answers, not whether it is healthy.**
 *     Any HTTP reply counts, including 500 and 403. The question is "can this
 *     phone reach anything", and a server erroring at you is a server you
 *     reached. This is what keeps the feature from turning the reference host's
 *     own uptime into a single point of failure for every alert in the app.
 *  3. **Silence needs proof; noise does not.** Only [Verdict.UNREACHABLE], which
 *     means the reference failed the same connection-shaped way the monitor did,
 *     suppresses anything. No reference configured, an ambiguous answer, the
 *     feature switched off: all page as before. Spam is visible and annoying,
 *     silence is invisible and lets a real outage pass unnoticed.
 */
object Reachability {

    /** What asking the reference endpoint settled. */
    enum class Verdict {
        /** Something answered. Whatever else is true, this phone has a network. */
        REACHABLE,

        /** Nothing answered, and it failed the way a dead network fails. */
        UNREACHABLE,

        /**
         * Nothing was learned: not asked, no reference configured, or a failure
         * that proves neither one thing nor the other.
         */
        UNKNOWN,
    }

    /**
     * Whether [kind] means the check never got a reply from anything.
     *
     * TLS is on the reachable side of the line, which reads wrong for half a
     * second and is right: a handshake can only fail after TCP has connected, so
     * the packets got somewhere. The same goes for every assertion failure.
     * `UNKNOWN` is also excluded, because an unclassified failure is not evidence
     * and rule three says only evidence buys silence.
     */
    fun neverReachedAnything(kind: FailureKind): Boolean = when (kind) {
        FailureKind.DNS, FailureKind.CONNECT, FailureKind.TIMEOUT -> true
        // Not one echo came back, which a dead phone network produces as well as
        // a dead host does.
        FailureKind.NO_REPLY -> true
        // Usually a transport failure on the way to the provider, which a dead
        // phone network produces just as well. A provider answering 5xx spends
        // one probe that then vouches for the network, and pages as it should.
        FailureKind.SIGN_IN_SERVER -> true
        // The host itself said nothing listens there, or something answered
        // with the wrong thing. Either way the packets got there.
        FailureKind.PORT_CLOSED,
        FailureKind.BAD_REPLY,
        FailureKind.NONE,
        FailureKind.TLS,
        FailureKind.STATUS,
        FailureKind.BODY,
        FailureKind.ELEMENT,
        FailureKind.RENDER,
        FailureKind.METRIC,
        FailureKind.REPORTED_DOWN,
        FailureKind.MAIL_LOGIN,
        FailureKind.NOT_HEALTH_FORMAT,
        FailureKind.TOKEN_REFUSED,
        FailureKind.BAD_CONFIG,
        FailureKind.UNKNOWN,
        -> false
    }

    /**
     * Whether this result is worth spending a probe on.
     *
     * False for every passing check, which is almost all of them, so the cost of
     * the whole feature in normal running is nothing at all.
     *
     * [referenceEnabled] is `GlobalSettings.latencyBaselineEnabled`, and it is
     * here because that switch is the only thing that has ever stopped this app
     * contacting the reference host. Somebody who turned it off did so about a
     * host, not about latency arithmetic, and shipping a second caller that
     * ignored it would reverse a decision they made deliberately, through a
     * control that did not exist when they made it. Off means off, for every
     * path, which is also the only version of this that can be explained in one
     * sentence.
     */
    fun shouldConfirm(
        enabled: Boolean,
        referenceEnabled: Boolean,
        referenceUrl: String,
        ok: Boolean,
        kind: FailureKind,
    ): Boolean = enabled &&
        referenceEnabled &&
        !ok &&
        referenceUrl.isNotBlank() &&
        neverReachedAnything(kind)

    /**
     * Whether the reference has earned the right to speak for the network.
     *
     * This is the hole rule two left open, and it was wide enough to turn the
     * product off. Rule two stops a reference that *answers* badly from silencing
     * anything, because any reply proves the network works. It said nothing about
     * a reference that does not answer at all, and `reach` maps every IOException
     * to [Verdict.UNREACHABLE]: host not found, connection refused, handshake
     * failed, and a four second timeout with no retry. So a reference blocked by
     * a corporate LAN, mistyped into the field, briefly down, or merely slow on a
     * train would report a dead network on a phone whose network was fine, and
     * every connection-shaped outage in the app would be dropped in silence, for
     * as long as that lasted.
     *
     * The evidence to close it was already being stored. `refreshReference`
     * writes a [ReferenceSample] every time the endpoint answers, kept for
     * [NetworkBaseline.MAX_AGE_MS]. A reference with a recent one has
     * demonstrably worked from this phone lately; a blocked or mistyped one never
     * accumulates any, and so never gets to vouch for anything.
     *
     * That the two cases separate on this is the whole point: a phone in an
     * underground car park still holds readings from before it went down there,
     * so the case the feature exists for keeps working, while the case that
     * would have silenced the app cannot arise.
     */
    fun hasVouched(readings: List<ReferenceSample>, nowMs: Long): Boolean =
        readings.any { it.rttMs > 0 && nowMs - it.at <= NetworkBaseline.MAX_AGE_MS }

    /**
     * Whether a reference that has gone quiet for longer than [hasVouched]
     * allows may still speak for the network.
     *
     * Reported against 3.25.0 (issue 23): a phone in flight mode with Wi-Fi left
     * on was quiet for exactly six hours and then paged "Can't resolve" for a
     * domain that was fine. Nothing had answered since it took off, so the last
     * reference reading aged out of the window and the reference lost its right
     * to vouch at the one moment it mattered most. The longer a phone is off the
     * network, the more certain it becomes that it is off the network, and the
     * six hour rule had that backwards.
     *
     * The age limit exists for a phone that moved onto a network which blocks
     * the reference: a reading from home must not silence every outage at the
     * office forever. Two things tell that case apart from a dead network
     * without asking anyone new.
     *
     *  1. **The reference was credible when the silence began.** The age is
     *     measured to the last moment anything public reached the network, not
     *     to now. A phone that went dark had a working reference right up to the
     *     end. A phone on a network that blocks the reference keeps passing its
     *     other checks there, so that moment keeps moving and the gap past six
     *     hours opens as it did before this rule.
     *  2. **The hold ends.** [MAX_HOLD_MS] after the last proof it lapses
     *     whatever else is true, and failures page as they did before. That
     *     bounds the one case the first rule cannot see: a network that blocks
     *     the reference where every monitor is failing too, which reads the
     *     same as a dead one.
     *
     * Whether Android has validated the network is deliberately not asked. It
     * was, in the first version of this fix, and on the emulator it turned out
     * to exclude the very case that was reported: with strict mode off,
     * background checks only run on a network JobScheduler counts as connected,
     * which means validated, so the page most likely came from a network
     * Android still vouched for. An always-on VPN with nothing under it, or validation that
     * has not caught up with a dead uplink yet. On GrapheneOS it was weaker
     * still, because the validation host is the default reference itself.
     *
     * A fresh reading still vouches through [hasVouched] exactly as before, so
     * the car park case is unchanged.
     */
    fun stillVouches(evidence: ReachEvidence, nowMs: Long): Boolean =
        evidence.referenceAnsweredAt > 0L &&
            evidence.networkProvenAt - evidence.referenceAnsweredAt <= NetworkBaseline.MAX_AGE_MS &&
            nowMs - evidence.networkProvenAt <= MAX_HOLD_MS

    /** How long [stillVouches] may keep a phone quiet after the network last proved itself. */
    const val MAX_HOLD_MS = 48L * 60 * 60 * 1000

    /**
     * Whether a check result proves that this phone's packets get past the local
     * network.
     *
     * The complement of [neverReachedAnything] for failures, minus the kinds that
     * prove nothing either way: [FailureKind.UNKNOWN] and [FailureKind.BAD_CONFIG]
     * never left the phone. A pass counts, even a UDP one, which only passes once
     * something answered. A RENDER failure counts too, since a page that drew
     * wrong was a page that arrived; a renderer that crashed on its own is rare
     * and errs towards paging, which is the safe side.
     *
     * Nothing aimed at [isLocalTarget] counts. When the uplink dies the router
     * and the NAS keep answering, and a monitor on one of them passing every few
     * minutes would otherwise prove an internet that is gone and end the hold.
     */
    fun provesNetwork(ok: Boolean, kind: FailureKind, host: String): Boolean = when {
        isLocalTarget(host) -> false
        ok -> true
        kind == FailureKind.UNKNOWN || kind == FailureKind.BAD_CONFIG -> false
        else -> !neverReachedAnything(kind)
    }

    /**
     * Whether [host] is on this side of the uplink: loopback, link local, a
     * private or carrier range, or a name that only a local resolver answers.
     *
     * From the text alone. A public name that a split resolver points at a LAN
     * address is not caught, because finding out would be a DNS lookup per
     * check, and missing it only means paging as before.
     */
    fun isLocalTarget(host: String): Boolean {
        val h = host.trim().removeSuffix(".").removePrefix("[").removeSuffix("]").lowercase()
        if (h.isEmpty()) return false
        if (h == "localhost" || LOCAL_SUFFIXES.any { h.endsWith(it) }) return true
        val v4 = h.split('.').takeIf { it.size == 4 }?.map { it.toIntOrNull() ?: -1 }
        if (v4 != null && v4.all { it in 0..255 }) {
            val (a, b) = v4
            return a == 10 || a == 127 ||
                (a == 172 && b in 16..31) ||
                (a == 192 && b == 168) ||
                (a == 169 && b == 254) ||
                (a == 100 && b in 64..127)
        }
        if (':' in h) {
            return h == "::1" || h.startsWith("fe8") || h.startsWith("fe9") ||
                h.startsWith("fea") || h.startsWith("feb") || h.startsWith("fc") || h.startsWith("fd")
        }
        return '.' !in h
    }

    private val LOCAL_SUFFIXES = listOf(".local", ".lan", ".home", ".internal", ".home.arpa", ".localdomain")

    /**
     * Whether to throw this check away rather than record it.
     *
     * Dropped, not recorded as a pass and not recorded as a failure. A check made
     * on a network that could not carry it did not happen, and the engine already
     * has that concept for a checker that threw: no health, no sample, no alert,
     * only the note that an attempt was made. The next check once the phone is
     * back on a real network reports whatever is actually true.
     *
     * Both halves required. An unreachable reference is not evidence on its own,
     * only an unreachable reference that is known to work otherwise.
     */
    fun isLocalOutage(probe: Verdict, referenceHasVouched: Boolean): Boolean =
        probe == Verdict.UNREACHABLE && referenceHasVouched
}
