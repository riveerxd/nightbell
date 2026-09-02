package me.river.nightbell

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.river.nightbell.NightbellTestSupport.appContext
import me.river.nightbell.NightbellTestSupport.resetApp
import me.river.nightbell.data.Nightbell
import me.river.nightbell.domain.ElementTarget
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The certificate probe, against a real certificate.
 *
 * A `Real*` class because it needs the network, and it skips rather than fails
 * when there is none, the same as the other three.
 *
 * This is the half of the feature that nothing else can cover. A page element
 * check runs in a WebView, and WebView hands an app a certificate only inside
 * `onReceivedSslError`, so an element monitor could never see the certificate of
 * a page that loaded cleanly and could never warn that one was about to expire.
 * The engine now does one HEAD a day to read the expiry. Whether the switch
 * appears, persists and stays hidden where it should is covered on device by
 * [CertificateOptionInstrumentedTest]; whether a real handshake actually lands a
 * real date in the store is only answerable here.
 *
 * `example.com` on purpose: it exists to be requested, it is not somebody's
 * service, and one HEAD against it is the smallest real thing this can ask for.
 */
@RunWith(AndroidJUnit4::class)
class RealCertificateProbeInstrumentedTest {

    private val graph get() = Nightbell.install(appContext)

    @Before
    fun setUp() {
        assumeTrue("device is offline", graph.network.isOnline())
        resetApp(GlobalSettings(motionIntensity = 0f))
    }

    private fun elementMonitor(watch: Boolean) = Monitor(
        id = "cert-probe",
        name = "Example",
        kind = MonitorKind.WEBSITE_ELEMENT,
        url = "https://example.com/",
        // Deliberately a selector that is not there. The page-element verdict is
        // not what this is about, and a check that fails on its element is the
        // case that proves the probe is separate from it: the certificate has to
        // be recorded even when the monitor is down.
        elements = listOf(ElementTarget(elementId = "not-a-real-element")),
        watchCertificate = watch,
        timeoutSeconds = 30,
        intervalMinutes = 15,
    )

    @Test
    fun aPageElementMonitorRecordsARealExpiryDate() {
        runBlocking { graph.store.upsert(elementMonitor(watch = true)) }

        val result = runBlocking { graph.engine.run("cert-probe") }
        assumeTrue("the check did not complete", result != null)

        val runtime = runBlocking { graph.store.currentSnapshot() }.runtimes["cert-probe"]
        val expiry = runtime?.certExpiresAt ?: 0L
        assertTrue(
            "a real handshake must land a real expiry, got $expiry",
            expiry > System.currentTimeMillis(),
        )
        // A certificate nobody renews for a decade does not exist. This catches a
        // date that parsed into something absurd rather than one that is simply
        // present.
        val tenYears = System.currentTimeMillis() + 10L * 365 * 24 * 60 * 60 * 1000
        assertTrue("an expiry ten years out is a parsing bug, got $expiry", expiry < tenYears)
        assertTrue("the issuer should be recorded too", runtime?.certIssuer?.isNotBlank() == true)
    }

    @Test
    fun theSwitchIsWhatDecidesWhetherTheProbeHappens() {
        runBlocking { graph.store.upsert(elementMonitor(watch = false)) }

        val result = runBlocking { graph.engine.run("cert-probe") }
        assumeTrue("the check did not complete", result != null)

        val runtime = runBlocking { graph.store.currentSnapshot() }.runtimes["cert-probe"]
        // Off by default and off means off: a second request against somebody's
        // server should not happen because a monitor happens to be https.
        assertEquals(
            "no probe should have run with the switch off",
            0L,
            runtime?.certExpiresAt ?: 0L,
        )
    }
}
