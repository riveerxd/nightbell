package me.river.nightbell

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.river.nightbell.NightbellTestSupport.appContext
import me.river.nightbell.NightbellTestSupport.resetApp
import me.river.nightbell.data.Nightbell
import me.river.nightbell.domain.Health
import me.river.nightbell.domain.Monitor
import me.river.nightbell.widget.NightbellWidgetProvider
import me.river.nightbell.widget.WidgetConfig
import me.river.nightbell.widget.WidgetConfigStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The widget as the launcher actually sees it.
 *
 * [WidgetInstrumentedTest] inflates [android.widget.RemoteViews] the provider
 * builds; this binds a real [AppWidgetHost] to the real provider and asserts on
 * what arrives over the AppWidgetService binder. That is the only way to test the
 * part that was broken: not what `build` renders from a given fleet, but *which
 * fleet reaches it* when a check completes.
 *
 * Hosting widgets needs signature-level `BIND_APPWIDGET`, which
 * `appwidget grantbind` hands to this package for the duration of the run.
 */
@RunWith(AndroidJUnit4::class)
class WidgetHostInstrumentedTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val store get() = Nightbell.install(appContext).store

    private lateinit var host: RecordingHost
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private lateinit var hostView: RecordingHostView

    /**
     * A host that hands out [RecordingHostView]s.
     *
     * The view has to come from `onCreateView` rather than be constructed by the
     * test: [AppWidgetHost] only routes pushes to views it created itself, so a
     * hand-rolled one receives nothing at all.
     */
    private class RecordingHost(context: Context, hostId: Int) : AppWidgetHost(context, hostId) {
        @Volatile
        var view: RecordingHostView? = null

        override fun onCreateView(
            context: Context,
            appWidgetId: Int,
            appWidget: android.appwidget.AppWidgetProviderInfo?,
        ): AppWidgetHostView = RecordingHostView(context).also { view = it }
    }

    /** Captures every push the host is handed, so the test can wait for one. */
    private class RecordingHostView(context: Context) : AppWidgetHostView(context) {
        @Volatile
        var updates = 0

        override fun updateAppWidget(remoteViews: android.widget.RemoteViews?) {
            super.updateAppWidget(remoteViews)
            updates++
        }
    }

    @Before
    fun setUp() {
        shell("appwidget grantbind --package ${appContext.packageName}")
        resetApp()

        val manager = AppWidgetManager.getInstance(appContext)
        val provider = ComponentName(appContext, NightbellWidgetProvider::class.java)
        onMain {
            // A previous run's host may still own ids for this host id.
            RecordingHost(appContext, HOST_ID).deleteHost()
            host = RecordingHost(appContext, HOST_ID)
            widgetId = host.allocateAppWidgetId()
        }
        assertTrue(
            "could not bind a widget id; is BIND_APPWIDGET granted?",
            manager.bindAppWidgetIdIfAllowed(widgetId, provider),
        )
        // A config with everything on, so the assertions have text to find. The
        // provider only draws the cog for a real id, which this now is.
        runBlocking { WidgetConfigStore.save(appContext, widgetId, WidgetConfig()) }
        onMain {
            host.startListening()
            hostView = host.createView(appContext, widgetId, manager.getAppWidgetInfo(widgetId))
                as RecordingHostView
            // The launcher reports a size; without one the layout planner assumes a
            // single column, which is fine but makes the assertions size-dependent.
            hostView.updateAppWidgetSize(null, 320, 200, 320, 200)
        }
    }

    @After
    fun tearDown() {
        onMain {
            runCatching { host.stopListening() }
            runCatching { host.deleteAppWidgetId(widgetId) }
            runCatching { host.deleteHost() }
        }
        shell("appwidget revokebind --package ${appContext.packageName}")
    }

    /**
     * The reported bug, end to end: widget says DOWN, app says UP.
     *
     * The recovery is written exactly as a check writes it, and nothing then asks
     * for a render — the store's own collector is what has to notice. So this
     * covers the ordering fix and the "widget follows the data" rewiring together.
     */
    @Test
    fun aRecoveryReachesTheLauncher() {
        runBlocking {
            store.upsert(Monitor(id = "riveer", name = "Riveer.cz", url = "https://riveer.cz"))
            store.updateRuntime("riveer") {
                it.copy(health = Health.DOWN, lastCheckedAt = System.currentTimeMillis())
            }
        }
        awaitText("DOWN", present = true)

        // The write a completed, successful check makes. No refresh call of any kind.
        runBlocking {
            store.updateRuntime("riveer") {
                it.copy(
                    health = Health.UP,
                    lastLatencyMs = 3_240,
                    lastCheckedAt = System.currentTimeMillis(),
                )
            }
        }

        awaitText("3240 ms", present = true)
        awaitText("DOWN", present = false)
    }

    /**
     * A store write the check engine never sees still reaches the home screen.
     *
     * Renaming a paused monitor writes and returns — `notifyStateChanged` is not
     * called, and the engine is never entered, so a push-based widget refresh had
     * nothing to hang off and the old name stayed on the home screen indefinitely.
     */
    @Test
    fun renamingAPausedMonitorReachesTheLauncher() {
        runBlocking {
            store.upsert(
                Monitor(id = "m", name = "Old Name", url = "https://old.example.com", enabled = false),
            )
        }
        awaitText("Old Name", present = true)

        runBlocking {
            store.upsert(
                Monitor(id = "m", name = "New Name", url = "https://old.example.com", enabled = false),
            )
        }
        awaitText("New Name", present = true)
        awaitText("Old Name", present = false)
    }

    /** Whatever else happens, the launcher must never be told there are no monitors. */
    @Test
    fun aPopulatedFleetIsNeverRenderedAsEmpty() {
        runBlocking {
            store.upsert(Monitor(id = "a", name = "Alpha", url = "https://a.example.com"))
            store.updateRuntime("a") { it.copy(health = Health.UP, lastLatencyMs = 100) }
        }
        awaitText("Alpha", present = true)
        awaitText("No monitors yet", present = false)
    }

    // ---- plumbing ------------------------------------------------------------

    private fun awaitText(needle: String, present: Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        var seen = ""
        while (System.currentTimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            seen = onMainResult { texts(hostView).joinToString(" | ") }
            if (seen.contains(needle) == present) return
            Thread.sleep(POLL_MS)
        }
        throw AssertionError(
            "widget ${if (present) "never showed" else "still shows"} \"$needle\" " +
                "after ${TIMEOUT_MS}ms (${hostView.updates} pushes). Showing: [$seen]",
        )
    }

    private fun texts(view: View): List<String> = when (view) {
        is TextView -> if (view.visibility == View.VISIBLE && view.text.isNotBlank()) {
            listOf(view.text.toString())
        } else {
            emptyList()
        }

        is ViewGroup -> (0 until view.childCount)
            .filter { view.visibility == View.VISIBLE }
            .flatMap { texts(view.getChildAt(it)) }

        else -> emptyList()
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun <T> onMainResult(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { fd ->
            java.io.FileInputStream(fd.fileDescriptor).use { it.readBytes() }
        }
    }

    private companion object {
        const val HOST_ID = 0x9051
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 150L
    }
}
