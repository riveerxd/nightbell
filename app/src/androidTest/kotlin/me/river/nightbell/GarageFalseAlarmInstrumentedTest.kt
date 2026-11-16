package me.river.nightbell

import android.Manifest
import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import java.net.ServerSocket
import me.river.nightbell.NightbellTestSupport.awaitTrue
import me.river.nightbell.data.Nightbell
import me.river.nightbell.domain.AlertPolicy
import me.river.nightbell.domain.ElementTarget
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.Health
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.NetworkWatch
import me.river.nightbell.domain.ReachEvidence
import me.river.nightbell.domain.ReferenceSample
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The underground car park, reproduced.
 *
 * Reported from real use: no traffic gets out, every monitor fails at once, and
 * every one of them pages. Nothing was down. The two tests below are the same
 * failed check with one thing changed between them, which is the only thing the
 * feature is allowed to care about: whether the reference endpoint answers.
 *
 * A closed port stands in for the dead network. It is the honest stand-in
 * because it produces the same class of error a garage does, a connection that
 * cannot be established, and it does so without a flaky dependency on anything
 * outside this device.
 */
@RunWith(AndroidJUnit4::class)
class GarageFalseAlarmInstrumentedTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private val graph get() = Nightbell.install(NightbellTestSupport.appContext)
    private val notifications: NotificationManager
        get() = NightbellTestSupport.appContext.getSystemService(NotificationManager::class.java)

    private var reference: TinyHttpServer? = null

    /** A port with nothing behind it, which is what "no network" looks like. */
    private val deadUrl: String by lazy {
        val port = ServerSocket(0).use { it.localPort }
        "http://127.0.0.1:$port/gone"
    }

    @Before
    fun setUp() {
        notifications.cancelAll()
    }

    @After
    fun tearDown() {
        notifications.cancelAll()
        reference?.close()
        reference = null
    }

    private fun seed(referenceUrl: String, confirm: Boolean = true) {
        NightbellTestSupport.resetApp(
            GlobalSettings(
                motionIntensity = 0f,
                masterAlertsEnabled = true,
                defaultAlert = AlertPolicy(),
                confirmOutagesEnabled = confirm,
                latencyReferenceUrl = referenceUrl,
            ),
        )
        runBlocking {
            graph.store.upsert(
                Monitor(
                    id = "garage",
                    name = "Checkout API",
                    url = deadUrl,
                    timeoutSeconds = 5,
                    useGlobalAlerts = true,
                ),
            )
        }
    }

    private fun runCheck() = runBlocking { graph.engine.run("garage") }

    /** Recent enough that the latency control will not re-probe this pass. */
    private fun freshReading() = ReferenceSample(at = System.currentTimeMillis(), rttMs = 40L)

    private fun runtime() = runBlocking {
        graph.store.currentSnapshot().runtimes["garage"]
    }

    private fun paged(): Boolean = notifications.activeNotifications.any {
        it.notification.extras.getCharSequence("android.title")?.contains("Checkout API") == true
    }

    /**
     * The bug. Unreachable monitor, unreachable reference: this phone is off the
     * network, so the check never happened.
     *
     * The seeded reading is not scaffolding, it is the case. A phone that walks
     * into an underground car park was on a working network minutes earlier and
     * still holds the reference timings to prove it, which is precisely what
     * separates this from a reference that is simply blocked. Without one, this
     * test asserted the defect below as the correct outcome and passed for two
     * hours while doing it.
     */
    @Test
    fun a_dead_network_records_nothing_and_pages_nobody() {
        seed(referenceUrl = deadUrl)
        runBlocking { graph.store.updateReference { listOf(freshReading()) } }
        runCheck()

        assertEquals("no page should have gone out", false, paged())
        val after = runtime()
        assertEquals(
            "a check the network could not carry must not become a verdict",
            Health.UNKNOWN,
            after?.health,
        )
        assertTrue("no sample should have been recorded", after?.samples.isNullOrEmpty())
        // The attempt is still written down, or the monitor stays permanently due
        // and the engine retries it every fifteen seconds forever.
        assertTrue("the attempt should still be timestamped", (after?.lastCheckedAt ?: 0L) > 0L)
    }

    /**
     * The defect, kept as a test so it cannot come back.
     *
     * Same dead monitor and same dead reference as above, with one thing removed:
     * this phone has never successfully reached the reference. That is a
     * firewalled corporate LAN, a mistyped endpoint, a host having an outage, or
     * one merely slower than the four second budget. The network is fine and the
     * outage is real, so it must page. Before the vouching rule it was dropped in
     * silence, and would have gone on being dropped for as long as the reference
     * stayed unreachable.
     */
    @Test
    fun a_reference_that_has_never_worked_cannot_silence_a_real_outage() {
        seed(referenceUrl = deadUrl)
        runBlocking { graph.store.updateReference { emptyList() } }
        runCheck()

        awaitTrue(description = "the outage pages, because nothing vouched for the network") { paged() }
        assertEquals(Health.DOWN, runtime()?.health)
    }

    /**
     * The same failure with a working network. Nothing is suppressed, because
     * the reference answered and the monitor did not.
     */
    @Test
    fun the_same_failure_pages_when_the_reference_answers() {
        reference = TinyHttpServer { TinyHttpServer.Response(code = 204, reason = "No Content") }
        seed(referenceUrl = reference!!.url("/generate_204"))
        runCheck()

        awaitTrue(description = "the outage is paged") { paged() }
        assertEquals("the monitor is genuinely down", Health.DOWN, runtime()?.health)
    }

    /**
     * A reference having a bad day is not a reason to go quiet.
     *
     * 500 still proves the packets went out and came back, so the monitor's own
     * failure is real and must page. Reading any answer as "network is broken"
     * would turn one third-party host into a mute switch for the whole app.
     */
    @Test
    fun a_reference_answering_500_still_lets_the_outage_page() {
        reference = TinyHttpServer { TinyHttpServer.Response(code = 500, reason = "Server Error") }
        seed(referenceUrl = reference!!.url("/generate_204"))
        runCheck()

        awaitTrue(description = "the outage is paged despite the reference erroring") { paged() }
        assertEquals(Health.DOWN, runtime()?.health)
    }

    /** Switched off, the old behaviour returns exactly. */
    @Test
    fun switching_it_off_pages_from_the_car_park_again() {
        seed(referenceUrl = deadUrl, confirm = false)
        runCheck()

        awaitTrue(description = "the page goes out with confirmation off") { paged() }
        assertEquals(Health.DOWN, runtime()?.health)
    }

    /**
     * A whole pass asks the reference once, however many monitors fail.
     *
     * Losing signal fails every monitor at once, so the naive shape spends one
     * probe per failure: ten monitors in a car park, ten identical requests to
     * the same host inside one sweep, nine of them asking a question already
     * answered. The count is the assertion, because the behaviour is invisible
     * otherwise, and it is exactly the kind of waste that only shows up on the
     * connection least able to afford it.
     */
    @Test
    fun a_pass_asks_the_reference_once_no_matter_how_many_monitors_fail() {
        val probes = TinyHttpServer { TinyHttpServer.Response(code = 204, reason = "No Content") }
        try {
            NightbellTestSupport.resetApp(
                GlobalSettings(
                    motionIntensity = 0f,
                    masterAlertsEnabled = true,
                    defaultAlert = AlertPolicy(),
                    confirmOutagesEnabled = true,
                    latencyReferenceUrl = probes.url("/generate_204"),
                ),
            )
            runBlocking {
                // The latency control shares this endpoint and would land in the
                // count. It cannot simply be switched off any more, because that
                // switch now gates the probe as well, so it is silenced the only
                // honest way left: give it a reading young enough that
                // NetworkBaseline.needsProbe says no.
                graph.store.updateReference { listOf(freshReading()) }
                repeat(4) { index ->
                    graph.store.upsert(
                        Monitor(
                            id = "dead-$index",
                            name = "Dead $index",
                            url = deadUrl,
                            timeoutSeconds = 5,
                            useGlobalAlerts = true,
                        ),
                    )
                }
                assertEquals("the pass should have run all four", 4, graph.engine.runAllDue(force = true))
            }

            assertEquals(
                "four failures in one pass should have asked the reference once, got ${probes.received.size}",
                1,
                probes.received.size,
            )
        } finally {
            probes.close()
        }
    }

    /**
     * A pass where nothing fails never asks at all.
     *
     * The lazy half. If this ever regresses to probing up front, every healthy
     * sweep starts paying for a feature that only exists for the unhealthy ones.
     */
    @Test
    fun a_healthy_pass_never_touches_the_reference() {
        val probes = TinyHttpServer { TinyHttpServer.Response(code = 204, reason = "No Content") }
        val service = TinyHttpServer { TinyHttpServer.Response(body = "fine") }
        try {
            NightbellTestSupport.resetApp(
                GlobalSettings(
                    motionIntensity = 0f,
                    masterAlertsEnabled = true,
                    defaultAlert = AlertPolicy(),
                    confirmOutagesEnabled = true,
                    latencyReferenceUrl = probes.url("/generate_204"),
                ),
            )
            runBlocking {
                graph.store.updateReference { listOf(freshReading()) }
                graph.store.upsert(
                    Monitor(
                        id = "healthy",
                        name = "Healthy",
                        url = service.url("/ok"),
                        timeoutSeconds = 5,
                        useGlobalAlerts = true,
                    ),
                )
                assertEquals("the pass should have run the monitor", 1, graph.engine.runAllDue(force = true))
            }

            // Non-vacuous: the monitor really was checked and really passed, so
            // zero probes means the laziness worked rather than that nothing ran.
            assertEquals(
                Health.UP,
                runBlocking { graph.store.currentSnapshot().runtimes["healthy"]?.health },
            )
            assertEquals("a healthy pass must cost no probes", 0, probes.received.size)
        } finally {
            probes.close()
            service.close()
        }
    }

    /**
     * The switch that governs whether this app talks to the reference host at
     * all governs the probe too.
     *
     * Somebody who turned "discount my connection" off did so about a host. A
     * second caller ignoring that would reverse a decision they had already made
     * deliberately, through a control that did not exist when they made it. So
     * off means off: the endpoint is untouched, and the failure pages exactly as
     * it did before this feature existed.
     */
    @Test
    fun the_reference_switch_being_off_means_the_endpoint_is_never_touched() {
        val probes = TinyHttpServer { TinyHttpServer.Response(code = 204, reason = "No Content") }
        try {
            NightbellTestSupport.resetApp(
                GlobalSettings(
                    motionIntensity = 0f,
                    masterAlertsEnabled = true,
                    defaultAlert = AlertPolicy(),
                    confirmOutagesEnabled = true,
                    latencyBaselineEnabled = false,
                    latencyReferenceUrl = probes.url("/generate_204"),
                ),
            )
            runBlocking {
                graph.store.upsert(
                    Monitor(
                        id = "garage",
                        name = "Checkout API",
                        url = deadUrl,
                        timeoutSeconds = 5,
                        useGlobalAlerts = true,
                    ),
                )
            }
            runCheck()

            assertEquals("the endpoint must not be contacted at all", 0, probes.received.size)
            awaitTrue(description = "and the failure pages as it always did") { paged() }
            assertEquals(Health.DOWN, runtime()?.health)
        } finally {
            probes.close()
        }
    }

    /**
     * The same car park, for a page monitor.
     *
     * This is the hole the first version of the feature left, and it was wide
     * enough that somebody living in it kept being paged from a garage after the
     * fix shipped. Every monitor in the tests above is an http one, so the whole
     * suite agreed the feature worked while the browser checker reported
     * `RENDER` for every way of failing to load, including "there is no
     * network". RENDER means the page came back broken, which is a claim that
     * the connection worked, so the probe was never spent and the page went out.
     */
    @Test
    fun a_page_monitor_in_the_car_park_records_nothing_and_pages_nobody() {
        NightbellTestSupport.resetApp(
            GlobalSettings(
                motionIntensity = 0f,
                masterAlertsEnabled = true,
                defaultAlert = AlertPolicy(),
                confirmOutagesEnabled = true,
                latencyReferenceUrl = deadUrl,
            ),
        )
        runBlocking {
            graph.store.updateReference { listOf(freshReading()) }
            graph.store.upsert(
                Monitor(
                    id = "garage",
                    name = "Checkout API",
                    kind = MonitorKind.WEBSITE_ELEMENT,
                    url = deadUrl,
                    element = ElementTarget(elementId = "price"),
                    timeoutSeconds = 15,
                    useGlobalAlerts = true,
                ),
            )
        }
        runCheck()

        assertEquals("no page should have gone out", false, paged())
        val after = runtime()
        assertEquals(
            "a page the network could not carry must not become a verdict",
            Health.UNKNOWN,
            after?.health,
        )
        assertTrue("no sample should have been recorded", after?.samples.isNullOrEmpty())
    }

    /**
     * The other half, and the one that keeps the fix honest.
     *
     * A page that loads and is missing its element is a real failure observed
     * over a working connection, so it pages whatever the reference is doing.
     * Without this, classifying load errors more generously could quietly turn
     * into classifying everything as the network's fault.
     */
    @Test
    fun a_page_that_loads_without_its_element_pages_even_off_network() {
        val site = TinyHttpServer {
            TinyHttpServer.Response(
                body = "<!doctype html><html><body><p>nothing here</p></body></html>",
                contentType = "text/html; charset=utf-8",
            )
        }
        try {
            NightbellTestSupport.resetApp(
                GlobalSettings(
                    motionIntensity = 0f,
                    masterAlertsEnabled = true,
                    defaultAlert = AlertPolicy(),
                    confirmOutagesEnabled = true,
                    latencyReferenceUrl = deadUrl,
                ),
            )
            runBlocking {
                graph.store.updateReference { listOf(freshReading()) }
                graph.store.upsert(
                    Monitor(
                        id = "garage",
                        name = "Checkout API",
                        kind = MonitorKind.WEBSITE_ELEMENT,
                        url = site.url("/shop"),
                        element = ElementTarget(elementId = "price"),
                        timeoutSeconds = 15,
                        useGlobalAlerts = true,
                    ),
                )
            }
            runCheck()

            awaitTrue(description = "a missing element pages whatever the reference says") { paged() }
            assertEquals(Health.DOWN, runtime()?.health)
        } finally {
            site.close()
        }
    }

    /**
     * A monitor that answers badly is never confirmed away, even off-network.
     *
     * This is the case that would lose a real outage: the service replies 500
     * while the reference happens to be unreachable. The failure arrived over a
     * working connection, so it is proof in itself and must page regardless of
     * what the reference says.
     */
    @Test
    fun a_server_error_pages_even_when_the_reference_cannot_be_reached() {
        val service = TinyHttpServer { TinyHttpServer.Response(code = 500, reason = "Server Error") }
        try {
            NightbellTestSupport.resetApp(
                GlobalSettings(
                    motionIntensity = 0f,
                    masterAlertsEnabled = true,
                    defaultAlert = AlertPolicy(),
                    confirmOutagesEnabled = true,
                    latencyReferenceUrl = deadUrl,
                ),
            )
            runBlocking {
                graph.store.upsert(
                    Monitor(
                        id = "garage",
                        name = "Checkout API",
                        url = service.url("/broken"),
                        timeoutSeconds = 5,
                        useGlobalAlerts = true,
                    ),
                )
            }
            runCheck()

            awaitTrue(description = "an answered failure pages whatever the reference says") { paged() }
            assertEquals(Health.DOWN, runtime()?.health)
        } finally {
            service.close()
        }
    }

    /**
     * Issue 23, as reported: a ping monitor on a phone in flight mode with Wi-Fi
     * left on. Quiet for six hours, then "Can't resolve" for a domain that was
     * fine, because the last reference reading had aged out of its window.
     *
     * Seven hours dark here. The reference answered until the phone went dark
     * and nothing has reached anything since, so it still speaks for it.
     */
    @Test
    fun a_phone_dark_for_seven_hours_still_pages_nobody() {
        seedPing(referenceUrl = deadUrl)
        val dark = System.currentTimeMillis() - 7 * HOUR
        darkSince(dark)
        runPing()

        assertEquals("no page should have gone out", false, pinged())
        assertEquals(Health.UNKNOWN, pingRuntime()?.health)
        assertTrue("the attempt is still timestamped", (pingRuntime()?.lastCheckedAt ?: 0L) > 0L)
    }

    /**
     * The office that blocks the reference. Its other monitors kept passing a
     * minute ago, long after the reference last answered at home, so the
     * reference has nothing to vouch with.
     */
    @Test
    fun a_network_that_kept_working_without_the_reference_pages() {
        seedPing(referenceUrl = deadUrl)
        val now = System.currentTimeMillis()
        runBlocking {
            graph.store.updateReference { emptyList() }
            graph.store.updateReach { ReachEvidence(referenceAnsweredAt = now - 7 * HOUR, networkProvenAt = now - 60_000) }
        }
        runPing()

        awaitTrue(description = "the network kept carrying checks, so this failure is real") { pinged() }
        assertEquals(Health.DOWN, pingRuntime()?.health)
    }

    /**
     * The evidence is written by the engine itself, not only seeded by tests: a
     * pass with an answering reference stamps both times. The reference answering
     * is itself proof the network works; the healthy monitor is on loopback and
     * adds nothing, see the local monitor test below.
     */
    @Test
    fun a_working_pass_writes_down_that_the_network_works() {
        val probes = TinyHttpServer { TinyHttpServer.Response(code = 204, reason = "No Content") }
        val service = TinyHttpServer { TinyHttpServer.Response(body = "fine") }
        try {
            NightbellTestSupport.resetApp(
                GlobalSettings(
                    motionIntensity = 0f,
                    masterAlertsEnabled = true,
                    defaultAlert = AlertPolicy(),
                    confirmOutagesEnabled = true,
                    latencyReferenceUrl = probes.url("/generate_204"),
                ),
            )
            val before = System.currentTimeMillis()
            runBlocking {
                graph.store.upsert(
                    Monitor(id = "healthy", name = "Healthy", url = service.url("/ok"), timeoutSeconds = 5, useGlobalAlerts = true),
                )
                graph.engine.runAllDue(force = true)
            }
            val reach = runBlocking { graph.store.currentSnapshot().reach }
            assertTrue("the reference answer is recorded, got $reach", reach.referenceAnsweredAt >= before)
            assertTrue("the answer is recorded as proof, got $reach", reach.networkProvenAt >= before)
        } finally {
            probes.close()
            service.close()
        }
    }

    /** The hold is bounded: two days after the network last proved itself, failures page again. */
    @Test
    fun a_phone_dark_for_more_than_two_days_pages_again() {
        seedPing(referenceUrl = deadUrl)
        darkSince(System.currentTimeMillis() - 49 * HOUR)
        runPing()

        awaitTrue(description = "the hold has lapsed, so the failure pages") { pinged() }
        assertEquals(Health.DOWN, pingRuntime()?.health)
    }

    /**
     * A monitor on this side of the uplink passing is not proof of the internet.
     * The fake service here listens on 127.0.0.1, which is exactly the router or
     * NAS case: it keeps answering when the uplink is gone.
     */
    @Test
    fun a_passing_local_monitor_does_not_count_as_proof() {
        val service = TinyHttpServer { TinyHttpServer.Response(body = "fine") }
        try {
            NightbellTestSupport.resetApp(
                GlobalSettings(motionIntensity = 0f, masterAlertsEnabled = true, defaultAlert = AlertPolicy(), confirmOutagesEnabled = true),
            )
            runBlocking {
                graph.store.upsert(
                    Monitor(id = "lan", name = "Router", url = service.url("/ok"), timeoutSeconds = 5, useGlobalAlerts = true),
                )
                graph.engine.run("lan")
            }
            val after = runBlocking { graph.store.currentSnapshot() }
            assertEquals("the check itself passed", Health.UP, after.runtimes["lan"]?.health)
            assertEquals("but it proved nothing about the internet", 0L, after.reach.networkProvenAt)
        } finally {
            service.close()
        }
    }

    /**
     * Evidence stamped by a clock that was later set back must not freeze. Found
     * on the emulator: a throttle reading a future time as "noted a moment ago"
     * skipped every write until the real clock caught up with it.
     */
    @Test
    fun evidence_from_a_clock_that_was_set_back_is_rewritten() {
        val probes = TinyHttpServer { TinyHttpServer.Response(code = 204, reason = "No Content") }
        try {
            NightbellTestSupport.resetApp(
                GlobalSettings(
                    motionIntensity = 0f,
                    masterAlertsEnabled = true,
                    defaultAlert = AlertPolicy(),
                    confirmOutagesEnabled = true,
                    latencyReferenceUrl = probes.url("/generate_204"),
                ),
            )
            val tomorrow = System.currentTimeMillis() + 24 * HOUR
            runBlocking {
                graph.store.updateReach { ReachEvidence(referenceAnsweredAt = tomorrow, networkProvenAt = tomorrow) }
                graph.engine.runAllDue(force = true)
            }
            val reach = runBlocking { graph.store.currentSnapshot().reach }
            assertTrue("today's answer replaces tomorrow's, got $reach", reach.referenceAnsweredAt < tomorrow)
            assertTrue("and so does today's proof, got $reach", reach.networkProvenAt < tomorrow)
        } finally {
            probes.close()
        }
    }

    private fun seedPing(referenceUrl: String) {
        NightbellTestSupport.resetApp(
            GlobalSettings(
                motionIntensity = 0f,
                masterAlertsEnabled = true,
                defaultAlert = AlertPolicy(),
                confirmOutagesEnabled = true,
                latencyReferenceUrl = referenceUrl,
            ),
        )
        runBlocking {
            graph.store.upsert(
                Monitor(
                    id = "ping",
                    name = "Flight ping",
                    kind = MonitorKind.PING,
                    // .invalid never resolves, which is what every name does on a
                    // network that carries nothing.
                    network = NetworkWatch(host = "mydomain.invalid"),
                    timeoutSeconds = 5,
                    useGlobalAlerts = true,
                ),
            )
        }
    }

    private fun darkSince(at: Long) {
        runBlocking {
            graph.store.updateReference { emptyList() }
            graph.store.updateReach { ReachEvidence(referenceAnsweredAt = at - 3 * 60_000, networkProvenAt = at) }
        }
    }

    private fun runPing() = runBlocking { graph.engine.run("ping") }

    private fun pingRuntime() = runBlocking { graph.store.currentSnapshot().runtimes["ping"] }

    private fun pinged(): Boolean = notifications.activeNotifications.any {
        it.notification.extras.getCharSequence("android.title")?.contains("Flight ping") == true
    }

    private companion object {
        const val HOUR = 60L * 60 * 1000
    }
}
