package me.river.nightbell

import me.river.nightbell.domain.FailureKind
import me.river.nightbell.domain.NetworkBaseline
import me.river.nightbell.domain.ReferenceSample
import me.river.nightbell.domain.Reachability
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a failed check is this phone's fault rather than the service's.
 *
 * The rule being protected is asymmetric on purpose: suppressing needs proof,
 * paging needs none. Nearly every case below is therefore a case that must still
 * page, because those are the ones where getting it wrong loses an outage.
 */
class ReachabilityTest {

    private val reference = "https://connectivitycheck.grapheneos.network/generate_204"

    /** A reference that answered a minute ago, which is the normal case. */
    private val vouched = true

    @Test
    fun `a failure that reached nothing is worth confirming`() {
        listOf(FailureKind.DNS, FailureKind.CONNECT, FailureKind.TIMEOUT).forEach { kind ->
            assertTrue(
                "$kind never reached anything, so the reference can settle it",
                Reachability.shouldConfirm(true, true, reference, ok = false, kind = kind),
            )
        }
    }

    /**
     * The other half of the split, and the one that keeps a real outage safe.
     *
     * Every one of these arrived over a working connection. A 500 is a server
     * answering; a body mismatch is a body that was delivered. Asking the
     * reference about them would spend a request to learn what the failure
     * already proved, and would put a genuine outage one flaky probe away from
     * being thrown out.
     */
    @Test
    fun `a failure that got an answer is never confirmed away`() {
        listOf(
            FailureKind.STATUS,
            FailureKind.BODY,
            FailureKind.ELEMENT,
            FailureKind.RENDER,
            FailureKind.BAD_CONFIG,
            FailureKind.UNKNOWN,
        ).forEach { kind ->
            assertFalse(
                "$kind proves the network carried a reply",
                Reachability.shouldConfirm(true, true, reference, ok = false, kind = kind),
            )
        }
    }

    /**
     * TLS reads like a connection failure and is not one.
     *
     * A handshake can only fail after TCP has connected, so the packets reached
     * the host. An expired or swapped certificate is exactly the kind of outage
     * this app exists to shout about, and it must never be filed under "bad
     * signal".
     */
    @Test
    fun `a tls failure still pages, because the packets got there`() {
        assertFalse(Reachability.neverReachedAnything(FailureKind.TLS))
        assertFalse(Reachability.shouldConfirm(true, true, reference, ok = false, kind = FailureKind.TLS))
    }

    @Test
    fun `a passing check is never probed`() {
        assertFalse(Reachability.shouldConfirm(true, true, reference, ok = true, kind = FailureKind.NONE))
    }

    @Test
    fun `switching it off pages exactly as before`() {
        assertFalse(Reachability.shouldConfirm(false, true, reference, ok = false, kind = FailureKind.DNS))
    }

    @Test
    fun `with no reference configured there is nothing to ask, so it pages`() {
        assertFalse(Reachability.shouldConfirm(true, true, "", ok = false, kind = FailureKind.DNS))
        assertFalse(Reachability.shouldConfirm(true, true, "   ", ok = false, kind = FailureKind.DNS))
    }

    /**
     * The switch that has always governed whether this app talks to that host.
     *
     * Turning "discount my connection" off is the only thing that has ever
     * stopped the reference being contacted, and people used it for that reason
     * rather than for anything to do with latency arithmetic. A second caller
     * that ignored it would quietly reverse a decision they had already made.
     */
    @Test
    fun `the reference switch stops the probe too, whatever the new one says`() {
        assertFalse(
            Reachability.shouldConfirm(true, false, reference, ok = false, kind = FailureKind.DNS),
        )
        assertFalse(
            Reachability.shouldConfirm(true, false, reference, ok = false, kind = FailureKind.CONNECT),
        )
        assertFalse(
            Reachability.shouldConfirm(true, false, reference, ok = false, kind = FailureKind.TIMEOUT),
        )
    }

    @Test
    fun `only a proven dead network buys silence`() {
        assertTrue(Reachability.isLocalOutage(Reachability.Verdict.UNREACHABLE, vouched))
        assertFalse(Reachability.isLocalOutage(Reachability.Verdict.REACHABLE, vouched))
        // The one that matters most: an answer that settles nothing must not be
        // read as permission to stay quiet.
        assertFalse(Reachability.isLocalOutage(Reachability.Verdict.UNKNOWN, vouched))
    }

    /**
     * A reference nobody can reach cannot report a dead network.
     *
     * The defect this closes turned the product off. `reach` maps every
     * IOException to UNREACHABLE, so a reference blocked by a corporate LAN,
     * mistyped, briefly down, or merely slower than the four second budget
     * looked exactly like a dead network. Every connection-shaped outage in the
     * app would then be dropped in silence, on a phone whose network was fine,
     * for as long as that lasted.
     */
    @Test
    fun `an unreachable reference that has never worked cannot silence anything`() {
        assertFalse(Reachability.isLocalOutage(Reachability.Verdict.UNREACHABLE, referenceHasVouched = false))
    }

    @Test
    fun `a reference that answered recently may vouch, one that never did may not`() {
        val now = 1_800_000_000_000L
        assertTrue(Reachability.hasVouched(listOf(ReferenceSample(at = now - 60_000, rttMs = 40)), now))
        assertFalse(Reachability.hasVouched(emptyList(), now))
        // Stale beyond the window the store keeps, so it proves nothing about now.
        assertFalse(
            Reachability.hasVouched(
                listOf(ReferenceSample(at = now - NetworkBaseline.MAX_AGE_MS - 1, rttMs = 40)),
                now,
            ),
        )
        // A recorded failure is not a reading. Only a real round trip counts.
        assertFalse(Reachability.hasVouched(listOf(ReferenceSample(at = now, rttMs = 0)), now))
    }

    /**
     * The reference being broken must not silence the app.
     *
     * A reference that answers 500 is still a reference this phone reached, so
     * it proves the network works and the monitor's own failure is real. Getting
     * this backwards would make one third-party host's bad day into a global
     * mute, which is the obvious way a feature like this ruins a monitoring app.
     */
    @Test
    fun `a reference that answers badly still proves the network works`() {
        assertFalse(Reachability.isLocalOutage(Reachability.Verdict.REACHABLE, vouched))
    }
}
