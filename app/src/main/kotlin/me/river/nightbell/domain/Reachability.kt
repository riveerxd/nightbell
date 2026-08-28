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
        FailureKind.NONE,
        FailureKind.TLS,
        FailureKind.STATUS,
        FailureKind.BODY,
        FailureKind.ELEMENT,
        FailureKind.RENDER,
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
