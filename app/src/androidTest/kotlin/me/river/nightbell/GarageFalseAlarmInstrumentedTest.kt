package me.river.nightbell

import android.Manifest
import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import java.net.ServerSocket
import me.river.nightbell.NightbellTestSupport.awaitTrue
import me.river.nightbell.data.Nightbell
import me.river.nightbell.domain.AlertPolicy
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.Health
import me.river.nightbell.domain.Monitor
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
}
