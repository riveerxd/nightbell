package me.river.nightbell

import androidx.test.ext.junit.runners.AndroidJUnit4
import me.river.nightbell.NightbellTestSupport.appContext
import me.river.nightbell.NightbellTestSupport.awaitTrue
import me.river.nightbell.NightbellTestSupport.resetApp
import me.river.nightbell.data.Nightbell
import me.river.nightbell.data.NightbellSnapshot
import me.river.nightbell.data.transfer.BackupCodec
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.ui.SettingsViewModel
import me.river.nightbell.ui.Transfer
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Importing a backup, and what the screen is able to say while it happens.
 *
 * Two separate complaints live here. One is that the button gave no sign of
 * working, which is a rendering question and is answered by the state the button
 * binds to. The other is that the app appeared to hang for seconds, which was not
 * the import at all: it was the full check pass that used to run inside it,
 * before anything was reported back.
 */
@RunWith(AndroidJUnit4::class)
class ImportProgressInstrumentedTest {

    private lateinit var server: HangingServer

    @Before
    fun setUp() {
        resetApp(GlobalSettings(motionIntensity = 0f))
        // Deliberately a server that accepts and never answers, not a closed
        // port. A refused connection on loopback comes back in microseconds and
        // never reaches the timeout, which is what made the first version of the
        // timing test below pass against the very behaviour it was written to
        // catch. Checks have to actually cost their timeout for the arithmetic to
        // mean anything.
        server = HangingServer()
    }

    @After
    fun tearDown() {
        server.close()
    }

    /** A backup carrying monitors that can only fail, and slowly. */
    private fun backupOf(count: Int): String = BackupCodec.encode(
        snapshot = NightbellSnapshot(
            monitors = (1..count).map { index ->
                Monitor(
                    id = "imported-$index",
                    name = "Imported $index",
                    kind = MonitorKind.HTTP_STATUS,
                    url = server.url,
                    timeoutSeconds = SLOW_SECONDS,
                )
            },
            settings = GlobalSettings(motionIntensity = 0f, hasSeenPagerSetup = true),
        ),
        applicationId = "me.river.nightbell",
        versionName = "3.0.5",
        versionCode = 27,
        nowMs = System.currentTimeMillis(),
    )

    @Test
    fun theButtonHasSomethingToSayWhileTheFileIsBeingRead() {
        val graph = Nightbell.install(appContext).also { it.engine.isOnline = { true } }
        val viewModel = SettingsViewModel(graph)
        val holdTheFileOpen = CountDownLatch(1)
        val readingStarted = CountDownLatch(1)

        runBlocking(Dispatchers.Main) {
            viewModel.importBackup {
                withContext(Dispatchers.IO) {
                    readingStarted.countDown()
                    // Stands in for a slow provider: a file on a cloud-backed
                    // document provider is fetched over the network on open.
                    holdTheFileOpen.await(10, TimeUnit.SECONDS)
                    backupOf(3)
                }
            }
        }

        assertTrue("the read never started", readingStarted.await(10, TimeUnit.SECONDS))
        // The exact condition the button reads for its spinner and its label. It
        // has to name the direction: one flag for both meant an export made the
        // import button claim to be working.
        assertEquals(Transfer.IMPORT, viewModel.transfer)
        assertTrue(viewModel.transferring)

        holdTheFileOpen.countDown()
        awaitTrue(description = "the import to finish") { viewModel.transfer == null }
        assertNull(viewModel.transfer)
        assertEquals(3, graph.store.snapshot.value.monitors.size)
    }

    @Test
    fun theImportStopsBeingBusyWithoutWaitingForEveryMonitorToBeChecked() {
        val graph = Nightbell.install(appContext).also { it.engine.isOnline = { true } }
        val viewModel = SettingsViewModel(graph)
        val document = backupOf(4)

        val started = System.currentTimeMillis()
        runBlocking(Dispatchers.Main) { viewModel.importBackup { document } }
        awaitTrue(description = "the import to report itself done") { viewModel.transfer == null }
        val elapsed = System.currentTimeMillis() - started

        // Four monitors that each hang for their full timeout, checked one after
        // another: with the pass still inside the import this cannot finish in
        // under 16 seconds, and that arithmetic is the whole reason importing
        // felt like a freeze.
        assertTrue("the import took ${elapsed}ms, so it is still waiting on the checks", elapsed < BUDGET_MS)
        assertEquals(4, graph.store.snapshot.value.monitors.size)
    }

    private companion object {
        /** Long enough that four of them cannot hide inside [BUDGET_MS]. */
        const val SLOW_SECONDS = 4

        /** Comfortably above a real import, far below four hanging checks. */
        const val BUDGET_MS = 8_000L
    }

    @Test
    fun abadFileFailsLoudlyAndLetsGoOfTheButton() {
        val graph = Nightbell.install(appContext).also { it.engine.isOnline = { true } }
        val viewModel = SettingsViewModel(graph)

        runBlocking(Dispatchers.Main) { viewModel.importBackup { "this is not a backup" } }
        awaitTrue(description = "the failed import to release the button") { viewModel.transfer == null }

        // A spinner that never stops is worse than no spinner: the screen is left
        // with both buttons disabled and no way to try again.
        assertNull(viewModel.transfer)
        assertTrue("nothing was said about the failure", viewModel.toast != null)
    }
}

/**
 * Accepts connections and never answers them.
 *
 * The point is a check that costs its whole timeout rather than failing fast, so
 * the sockets are held open and deliberately never read from or written to.
 */
private class HangingServer : AutoCloseable {

    private val server = ServerSocket(0)
    private val held = CopyOnWriteArrayList<Socket>()

    val url: String get() = "http://127.0.0.1:${server.localPort}/health"

    init {
        thread(isDaemon = true, name = "HangingServer") {
            while (true) {
                val socket = try {
                    server.accept()
                } catch (_: Throwable) {
                    break
                }
                // Kept referenced so nothing closes it early. No reply, ever.
                held += socket
            }
        }
    }

    override fun close() {
        held.forEach { runCatching { it.close() } }
        held.clear()
        runCatching { server.close() }
    }
}
