package me.river.nightbell

import me.river.nightbell.domain.FailureKind
import me.river.nightbell.domain.NetworkBaseline
import me.river.nightbell.domain.ReachEvidence
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

    /**
     * Issue 23. Flight mode with Wi-Fi left on, nothing answering for seven
     * hours. The window of readings is long gone, but the reference was working
     * right up to the moment everything went quiet, so it still speaks for it.
     */
    @Test
    fun `a reference that worked until the network went dark keeps vouching past six hours`() {
        val now = 1_800_000_000_000L
        val wentDark = now - 7 * HOUR
        val evidence = ReachEvidence(referenceAnsweredAt = wentDark - 3 * 60_000, networkProvenAt = wentDark)
        assertFalse("the window alone has aged out", Reachability.hasVouched(emptyList(), now))
        assertTrue(Reachability.stillVouches(evidence, nowMs = now))
        assertTrue(
            Reachability.isLocalOutage(
                Reachability.Verdict.UNREACHABLE,
                Reachability.stillVouches(evidence, nowMs = now),
            ),
        )
    }

    /**
     * The office that blocks the reference. Other monitors keep passing there,
     * so the last proof keeps moving while the reference answer stays at home,
     * and once that gap passes six hours the reference loses its say.
     */
    @Test
    fun `a network that keeps working without the reference takes the vouch away`() {
        val home = 1_800_000_000_000L
        val stillPassing = home + NetworkBaseline.MAX_AGE_MS + 60_000
        val evidence = ReachEvidence(referenceAnsweredAt = home, networkProvenAt = stillPassing)
        assertFalse(Reachability.stillVouches(evidence, nowMs = stillPassing + 60_000))
    }

    @Test
    fun `a reference that has never answered cannot be held over`() {
        val t = 1_800_000_000_000L
        assertFalse(Reachability.stillVouches(ReachEvidence(), nowMs = t))
        assertFalse(Reachability.stillVouches(ReachEvidence(networkProvenAt = t), nowMs = t))
    }

    /**
     * The bound. A network that blocks the reference while every monitor fails
     * too looks exactly like a dead one, and must not be held quiet forever.
     */
    @Test
    fun `the hold lapses two days after the network last proved itself`() {
        val t = 1_800_000_000_000L
        val evidence = ReachEvidence(t, t)
        assertTrue(Reachability.stillVouches(evidence, nowMs = t + Reachability.MAX_HOLD_MS))
        assertFalse(Reachability.stillVouches(evidence, nowMs = t + Reachability.MAX_HOLD_MS + 1))
    }

    @Test
    fun `passes and answered failures prove the network, silence and our own mistakes do not`() {
        val host = "example.com"
        assertTrue(Reachability.provesNetwork(ok = true, kind = FailureKind.NONE, host = host))
        listOf(
            FailureKind.STATUS, FailureKind.TLS, FailureKind.BODY, FailureKind.PORT_CLOSED,
            FailureKind.BAD_REPLY, FailureKind.ELEMENT, FailureKind.RENDER,
        ).forEach {
            assertTrue("$it came back from somewhere", Reachability.provesNetwork(ok = false, kind = it, host = host))
        }
        listOf(
            FailureKind.DNS, FailureKind.CONNECT, FailureKind.TIMEOUT, FailureKind.NO_REPLY,
            FailureKind.SIGN_IN_SERVER, FailureKind.UNKNOWN, FailureKind.BAD_CONFIG,
        ).forEach {
            assertFalse("$it proves nothing about the network", Reachability.provesNetwork(ok = false, kind = it, host = host))
        }
    }

    /**
     * The router still answers when the uplink is dead, so a monitor on it
     * passing must not end a hold that the internet monitors depend on.
     */
    @Test
    fun `a pass on the local network proves nothing about the internet`() {
        listOf(
            "192.168.1.1", "10.0.0.2", "172.16.5.4", "172.31.255.255", "127.0.0.1", "169.254.10.10",
            "100.64.0.1", "::1", "fe80::1", "fd12:3456::1", "[fd00::2]", "nas.local", "router.lan",
            "printer.home.arpa", "localhost", "pihole", "box.internal.",
        ).forEach {
            assertTrue("$it is local", Reachability.isLocalTarget(it))
            assertFalse("a pass on $it is not internet proof", Reachability.provesNetwork(ok = true, kind = FailureKind.NONE, host = it))
        }
        listOf(
            "example.com", "8.8.8.8", "172.32.0.1", "192.169.0.1", "100.128.0.1", "2606:4700::1",
            "fdroid.org", "fc.example.com", "",
        ).forEach {
            assertFalse("$it is not local", Reachability.isLocalTarget(it))
        }
    }

    private companion object {
        const val HOUR = 60L * 60 * 1000
    }
}
