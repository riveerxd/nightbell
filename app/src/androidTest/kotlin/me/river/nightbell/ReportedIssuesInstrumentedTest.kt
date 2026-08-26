package me.river.nightbell

import android.os.Build
import android.os.Vibrator
import android.os.VibratorManager
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioAttributes
import android.os.RecordingVibrator
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.river.nightbell.NightbellTestSupport.appContext
import me.river.nightbell.NightbellTestSupport.resetApp
import me.river.nightbell.data.Nightbell
import me.river.nightbell.data.alerts.UrgentAlarm
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.Health
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.VibrationStyle
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The three issues reported against 3.0.5, driven on a device.
 *
 * All of them are things the JVM suite can only half-cover: one is a platform
 * version behaviour, one needs a real connection pool living across two checks,
 * and one needs Android's own networking stack to agree about how a SOCKS route
 * is dialled. Every test here is written so that it cannot pass by accident on a
 * device that could not reach the code under test.
 */
@RunWith(AndroidJUnit4::class)
class ReportedIssuesInstrumentedTest {

    @Before
    fun setUp() {
        resetApp()
    }

    // ---- crash on Android 10 and below -------------------------------------

    /**
     * Starting the urgent haptics must not take the process down, and must still
     * actually buzz on the versions where the crash lived.
     *
     * `VibrationAttributes` arrived in API 30 and this app's minSdk is 26, so on
     * API 26 to 29 the page reached for a class that is not on the device. It was
     * built above the branch that used it and above the `runCatching` that would
     * have caught it, so it took the process with it.
     *
     * The vibrator is supplied by the test. Emulators report no vibrator at all,
     * and [UrgentAlarm] returns two lines before the interesting one when that is
     * the case, so against a stock emulator this would pass on any API level while
     * proving nothing. Everything that matters is still real: a real API level, a
     * real absent class, and the app's own branch deciding what to do about it.
     */
    @Test
    fun urgentHapticsStartWithoutTakingTheProcessDown() {
        // Below R, which is where the bug lives. The stand-in is read below S,
        // but an API 30 run would exercise a platform that ships the class and so
        // would pass without the missing-class condition ever being present.
        assumeTrue(
            "VibrationAttributes exists from API 30, so there is no crash to catch here",
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R,
        )
        val vibrator = RecordingVibrator()
        val alarm = UrgentAlarm(VibratorContext(appContext, vibrator))

        try {
            // respectRinger off, so the output is decided without asking the
            // ringer: sound on, haptics on, which is the path that used to throw.
            alarm.start(VibrationStyle.DOUBLE_PULSE, vibrate = true, respectRinger = false)
        } finally {
            alarm.stop()
        }

        // Not just "it did not crash". The fix had to keep the pre-R branch, and
        // the obvious patch for this bug deletes it: guard the whole block on
        // API 30 and Android 10 stops vibrating instead of stopping crashing.
        assertTrue(
            "nothing vibrated on API ${Build.VERSION.SDK_INT}",
            vibrator.vibrations.get() > 0,
        )
        // And that it went out on the pre-R overload, with alarm usage. Counting
        // calls alone could not tell the two branches apart, which made the claim
        // above rest on the API gate rather than on anything observed.
        assertEquals(
            AudioAttributes.USAGE_ALARM,
            vibrator.lastAudioAttributes?.usage,
        )
    }

    /**
     * The class really is missing here, which is what makes the test above a test.
     *
     * If a future image ships `VibrationAttributes` on an API level below 30, this
     * fails and says so, rather than letting the suite quietly stop covering the
     * bug it was written for.
     */
    @Test
    fun theClassBehindTheCrashIsAbsentBelowApi30() {
        val present = runCatching { Class.forName("android.os.VibrationAttributes") }.isSuccess
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            assertFalse("VibrationAttributes is present on API ${Build.VERSION.SDK_INT}", present)
        } else {
            assertTrue("VibrationAttributes is missing on API ${Build.VERSION.SDK_INT}", present)
        }
    }

    /** Every haptic pattern the user can pick, on whatever API level this is. */
    @Test
    fun everyVibrationStyleStartsOnThisApiLevel() {
        // Below R, which is where the bug lives. The stand-in is read below S,
        // but an API 30 run would exercise a platform that ships the class and so
        // would pass without the missing-class condition ever being present.
        assumeTrue(
            "VibrationAttributes exists from API 30, so there is no crash to catch here",
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R,
        )
        val vibrator = RecordingVibrator()
        val alarm = UrgentAlarm(VibratorContext(appContext, vibrator))
        try {
            VibrationStyle.entries.forEach { style ->
                alarm.start(style, vibrate = true, respectRinger = false)
                alarm.stop()
            }
        } finally {
            alarm.stop()
        }
        assertEquals(VibrationStyle.entries.size, vibrator.vibrations.get())
    }

    /**
     * The real platform vibrator, when the device running this has one.
     *
     * Covers the API 31 and 33 branches, which the stand-in cannot reach, and it
     * covers them against the actual platform rather than a substitute.
     */
    @Test
    fun theRealVibratorAlsoStartsWithoutCrashing() {
        val real = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            appContext.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Vibrator::class.java)
        }
        assumeTrue("this device has no vibrator of its own", real?.hasVibrator() == true)

        val alarm = UrgentAlarm(appContext)
        try {
            VibrationStyle.entries.forEach { style ->
                alarm.start(style, vibrate = true, respectRinger = false)
                alarm.reassertVibration()
                alarm.stop()
            }
        } finally {
            alarm.stop()
        }
    }

    // ---- unexpected end of stream ------------------------------------------

    /**
     * A keep-alive connection the far end reaped between two checks.
     *
     * Runs through the real graph, so the checker is the process-wide instance
     * whose connection pool is what makes this reachable in the first place.
     */
    @Test
    fun aReapedKeepAliveConnectionIsNotReportedAsAnOutage() {
        KeepAliveServer().use { server ->
            val graph = Nightbell.install(appContext)
            val monitor = Monitor(
                id = "stale",
                name = "Stale",
                kind = MonitorKind.HTTP_STATUS,
                url = "${server.baseUrl}/health",
                timeoutSeconds = 10,
            )

            val first = runBlocking { graph.engine.dryRun(monitor) }
            assertTrue(first.message, first.ok)
            assertEquals(1, server.served.get())

            server.dropOpenConnections()
            Thread.sleep(SETTLE_MS)

            val second = runBlocking { graph.engine.dryRun(monitor) }
            assertTrue(
                "a reaped keep-alive connection read as an outage: ${second.message} / ${second.detail}",
                second.ok,
            )
            // The dead attempt never reached anyone, so the server saw two.
            assertEquals(2, server.served.get())
        }
    }

    // ---- SOCKS5 support ----------------------------------------------------

    /**
     * A monitor routed through a SOCKS5 proxy, wired the way the app wires it.
     *
     * The settings go through the real store and the check goes through the real
     * graph, so this covers the whole path the feature actually takes rather than
     * the checker in isolation. What it asserts at the far end is the one detail
     * a hidden service depends on: the proxy was handed a *name* to resolve.
     */
    @Test
    fun anOnionAddressIsRoutedThroughTheProxyByName() {
        SocksServer().use { proxy ->
            val graph = Nightbell.install(appContext)
            resetApp(
                GlobalSettings(
                    motionIntensity = 0f,
                    socksProxyEnabled = true,
                    socksProxyHost = "127.0.0.1",
                    socksProxyPort = proxy.port,
                ),
            )

            val monitor = Monitor(
                id = "onion",
                name = "Hidden",
                kind = MonitorKind.HTTP_STATUS,
                url = "http://nightbelltestaddressabcdefghijklmnopqrstuvwxyz234567abcd.onion/status",
                timeoutSeconds = 10,
                useProxy = true,
            )

            val result = runBlocking { graph.engine.dryRun(monitor) }

            assertTrue("check through the proxy failed: ${result.message} / ${result.detail}", result.ok)
            assertEquals(200, result.statusCode)
            assertEquals(ADDRESS_TYPE_NAME, proxy.requestedAddressType)
            assertEquals(
                "nightbelltestaddressabcdefghijklmnopqrstuvwxyz234567abcd.onion",
                proxy.requestedHost,
            )
        }
    }

    /** A proxied monitor all the way through `run`, so the verdict lands in the store. */
    @Test
    fun aProxiedMonitorRunsAndRecordsItsHealth() {
        SocksServer().use { proxy ->
            val graph = Nightbell.install(appContext)
            resetApp(
                GlobalSettings(
                    motionIntensity = 0f,
                    socksProxyEnabled = true,
                    socksProxyHost = "127.0.0.1",
                    socksProxyPort = proxy.port,
                ),
            )
            graph.engine.isOnline = { true }

            val monitor = Monitor(
                id = "onion-run",
                name = "Hidden",
                kind = MonitorKind.HTTP_STATUS,
                url = "http://nightbellrunaddressabcdefghijklmnopqrstuvwxyz234567abcde.onion/status",
                timeoutSeconds = 10,
                useProxy = true,
            )
            runBlocking { graph.store.upsert(monitor) }

            val result = runBlocking { graph.engine.run(monitor.id) }

            assertTrue("proxied run failed: ${result?.message} / ${result?.detail}", result?.ok == true)
            val runtime = graph.store.snapshot.value.runtimes[monitor.id]
            assertEquals(Health.UP, runtime?.health)
            assertEquals(1, proxy.connections.size)
        }
    }

    /** With the proxy switched off in settings, the same monitor goes out directly. */
    @Test
    fun turningTheProxyOffRefusesTheCheckRatherThanSendingItInTheClear() {
        SocksServer().use { proxy ->
            TinyHttpServer { TinyHttpServer.Response(body = "direct") }.use { site ->
                val graph = Nightbell.install(appContext)
                resetApp(
                    GlobalSettings(
                        motionIntensity = 0f,
                        socksProxyEnabled = false,
                        socksProxyHost = "127.0.0.1",
                        socksProxyPort = proxy.port,
                    ),
                )

                val monitor = Monitor(
                    id = "direct",
                    name = "Direct",
                    kind = MonitorKind.HTTP_STATUS,
                    url = site.url("/health"),
                    timeoutSeconds = 10,
                    useProxy = true,
                )

                val result = runBlocking { graph.engine.dryRun(monitor) }

                // Refused, not downgraded. This asserted the opposite until an
                // audit pointed out that quietly sending a routed monitor in the
                // clear publishes the hostname the proxy was hiding.
                assertFalse("a routed monitor was sent out in the clear", result.ok)
                assertTrue("a monitor was proxied while the proxy was off", proxy.connections.isEmpty())
                assertTrue("the site was reached anyway", site.received.isEmpty())
            }
        }
    }

    /** Orbot is not running. That is a failed check, not a crash and not a checker fault. */
    @Test
    fun aMissingProxyIsAnOrdinaryFailedCheck() {
        val deadPort = ServerSocket(0).use { it.localPort }
        val graph = Nightbell.install(appContext)
        resetApp(
            GlobalSettings(
                motionIntensity = 0f,
                socksProxyEnabled = true,
                socksProxyHost = "127.0.0.1",
                socksProxyPort = deadPort,
            ),
        )

        val monitor = Monitor(
            id = "no-proxy",
            name = "Hidden",
            kind = MonitorKind.HTTP_STATUS,
            url = "http://nightbellabsentaddrabcdefghijklmnopqrstuvwxyz234567abcde.onion/",
            timeoutSeconds = 10,
            useProxy = true,
        )

        val result = runBlocking { graph.engine.dryRun(monitor) }

        assertFalse(result.ok)
        assertTrue("expected a connection failure, got ${result.failureKind}", result.message.isNotBlank())
    }

    private companion object {
        const val SETTLE_MS = 250L
        const val ADDRESS_TYPE_NAME = 3
    }
}

/** See the JVM twin of this in `StaleConnectionRetryTest`. */
private class KeepAliveServer : AutoCloseable {

    private val server = ServerSocket(0)
    private val open = CopyOnWriteArrayList<Socket>()

    val served = AtomicInteger(0)

    val baseUrl: String get() = "http://127.0.0.1:${server.localPort}"

    init {
        thread(isDaemon = true, name = "KeepAliveServer") {
            while (true) {
                val socket = try {
                    server.accept()
                } catch (_: Throwable) {
                    break
                }
                open += socket
                thread(isDaemon = true) { serve(socket) }
            }
        }
    }

    private fun serve(socket: Socket) {
        runCatching {
            val reader = socket.getInputStream().bufferedReader()
            val out = socket.getOutputStream()
            while (!socket.isClosed) {
                val requestLine = reader.readLine() ?: return
                if (requestLine.isBlank()) continue
                while (true) {
                    val header = reader.readLine() ?: return
                    if (header.isEmpty()) break
                }
                served.incrementAndGet()
                val body = "ok"
                val head = buildString {
                    append("HTTP/1.1 200 OK\r\n")
                    append("Content-Type: text/plain; charset=utf-8\r\n")
                    append("Content-Length: ${body.length}\r\n")
                    append("Connection: keep-alive\r\n\r\n")
                }
                out.write(head.toByteArray(Charsets.US_ASCII))
                out.write(body.toByteArray(Charsets.UTF_8))
                out.flush()
            }
        }
    }

    fun dropOpenConnections() {
        open.forEach { runCatching { it.close() } }
        open.clear()
    }

    override fun close() {
        dropOpenConnections()
        runCatching { server.close() }
    }
}

/** See the JVM twin of this in `SocksProxyTest`. */
private class SocksServer : AutoCloseable {

    private val server = ServerSocket(0)

    val connections = CopyOnWriteArrayList<String>()

    @Volatile var requestedAddressType: Int = -1
        private set

    @Volatile var requestedHost: String = ""
        private set

    val port: Int get() = server.localPort

    init {
        thread(isDaemon = true, name = "SocksServer") {
            while (true) {
                val socket = try {
                    server.accept()
                } catch (_: Throwable) {
                    break
                }
                thread(isDaemon = true) { runCatching { serve(socket) } }
            }
        }
    }

    private fun serve(socket: Socket) {
        socket.use { client ->
            val input = DataInputStream(client.getInputStream())
            val out = client.getOutputStream()

            require(input.readUnsignedByte() == 5) { "not SOCKS5" }
            repeat(input.readUnsignedByte()) { input.readUnsignedByte() }
            out.write(byteArrayOf(5, 0))
            out.flush()

            require(input.readUnsignedByte() == 5) { "not SOCKS5" }
            require(input.readUnsignedByte() == 1) { "not CONNECT" }
            input.readUnsignedByte()
            val type = input.readUnsignedByte()
            val host = when (type) {
                1 -> ByteArray(4).also { input.readFully(it) }
                    .joinToString(".") { (it.toInt() and 0xFF).toString() }
                3 -> ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
                    .toString(Charsets.US_ASCII)
                4 -> ByteArray(16).also { input.readFully(it) }.joinToString(":")
                else -> error("unknown address type $type")
            }
            val targetPort = input.readUnsignedShort()

            requestedAddressType = type
            requestedHost = host
            connections += "$host:$targetPort"

            out.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            out.flush()

            val reader = client.getInputStream().bufferedReader()
            val requestLine = reader.readLine() ?: return
            if (requestLine.isBlank()) return
            while (true) {
                val header = reader.readLine() ?: return
                if (header.isEmpty()) break
            }
            val body = "hidden"
            val head = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: text/plain; charset=utf-8\r\n")
                append("Content-Length: ${body.length}\r\n")
                append("Connection: close\r\n\r\n")
            }
            out.write(head.toByteArray(Charsets.US_ASCII))
            out.write(body.toByteArray(Charsets.UTF_8))
            out.flush()
        }
    }

    override fun close() {
        runCatching { server.close() }
    }
}

/**
 * Hands [RecordingVibrator] to anything that asks this context for a vibrator.
 *
 * Only useful below API 31. Above it `UrgentAlarm.resolveVibrator` reads
 * `VibratorManager` instead, whose `vibrate` is final and delegates to a method
 * no subclass outside the framework can supply, so the tests that need a
 * guaranteed vibrator stay on the old service and the modern branch is covered
 * against the device's real one.
 */
private class VibratorContext(
    base: Context,
    private val vibrator: RecordingVibrator,
) : ContextWrapper(base) {

    override fun getSystemService(name: String): Any? =
        if (name == VIBRATOR_SERVICE) vibrator else super.getSystemService(name)
}
