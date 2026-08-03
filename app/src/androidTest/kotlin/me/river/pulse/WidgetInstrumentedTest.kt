package me.river.pulse

import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.river.pulse.PulseTestSupport.appContext
import me.river.pulse.domain.Health
import me.river.pulse.domain.Monitor
import me.river.pulse.domain.MonitorRuntime
import me.river.pulse.domain.Summary
import me.river.pulse.domain.UrgentAlerts
import me.river.pulse.widget.PulseWidgetProvider
import me.river.pulse.widget.WidgetConfig
import me.river.pulse.widget.WidgetConfigStore
import me.river.pulse.widget.WidgetDensity
import me.river.pulse.widget.WidgetTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The widget, verified by actually inflating its [android.widget.RemoteViews].
 *
 * This is the test that matters for a RemoteViews widget: `apply()` runs the
 * same inflation the launcher's process runs, so an unsupported view, a method
 * that isn't `@RemotableViewMethod`, or a missing resource fails here instead
 * of silently showing "Problem loading widget" on someone's home screen.
 *
 * Placing a real widget needs the launcher and the signature-level
 * BIND_APPWIDGET permission, so that step stays manual — see HANDOFF.
 */
@RunWith(AndroidJUnit4::class)
class WidgetInstrumentedTest {

    private fun monitor(id: String, name: String, urgent: Boolean = false, enabled: Boolean = true) =
        Monitor(id = id, name = name, url = "https://$id.example.com", urgent = urgent, enabled = enabled)

    private fun runtime(
        health: Health,
        latency: Long = 120,
        message: String = "",
        urgentState: UrgentAlerts.State = UrgentAlerts.State.Idle,
    ) = MonitorRuntime(
        health = health,
        lastLatencyMs = latency,
        lastCheckedAt = System.currentTimeMillis() - 60_000,
        lastMessage = message,
    ).withUrgentState(urgentState)

    private val fleet = Summary.of(
        listOf(
            monitor("a", "Alpha"),
            monitor("b", "Bravo"),
            monitor("c", "Charlie", urgent = true),
            monitor("d", "Delta", enabled = false),
        ),
        mapOf(
            "a" to runtime(Health.UP),
            "b" to runtime(Health.DEGRADED, latency = 4_100),
            "c" to runtime(
                Health.DOWN,
                message = "Connection refused",
                urgentState = UrgentAlerts.State(active = true),
            ),
            "d" to runtime(Health.UP),
        ),
    )

    /** Inflates the RemoteViews the way the launcher would. */
    private fun inflate(config: WidgetConfig, appWidgetId: Int = 7): View {
        val views = PulseWidgetProvider.build(appContext, config, fleet, appWidgetId)
        val parent = FrameLayout(appContext)
        return views.apply(appContext, parent)
    }

    /**
     * Every string the user would actually see.
     *
     * Stops at a hidden container rather than only checking each TextView:
     * a `TextView` inside a `GONE` `LinearLayout` still reports itself as
     * `VISIBLE`, so a naive walk "finds" text that never reaches the screen.
     */
    private fun texts(root: View): List<String> = buildList {
        fun walk(view: View) {
            if (view.visibility != View.VISIBLE) return
            if (view is TextView) {
                view.text?.toString()?.takeIf { it.isNotBlank() }?.let { add(it) }
            }
            if (view is android.view.ViewGroup) {
                for (i in 0 until view.childCount) walk(view.getChildAt(i))
            }
        }
        walk(root)
    }

    @Test
    fun everyThemeInflates() {
        WidgetTheme.entries.forEach { theme ->
            val root = inflate(WidgetConfig(theme = theme))
            assertNotNull("theme $theme failed to inflate", root)
            assertTrue("theme $theme rendered nothing", texts(root).isNotEmpty())
        }
    }

    @Test
    fun bothDensitiesInflate() {
        WidgetDensity.entries.forEach { density ->
            val root = inflate(WidgetConfig(density = density, maxRows = 4))
            assertTrue(texts(root).any { it.contains("Charlie") })
        }
    }

    @Test
    fun monitorsAreListedWorstFirst() {
        val root = inflate(WidgetConfig(maxRows = 4))
        val rendered = texts(root)
        val charlie = rendered.indexOfFirst { it.contains("Charlie") }
        val bravo = rendered.indexOfFirst { it.contains("Bravo") }
        val alpha = rendered.indexOfFirst { it.contains("Alpha") }
        assertTrue("down should come first: $rendered", charlie in 0 until bravo)
        assertTrue("degraded should beat healthy: $rendered", bravo < alpha)
    }

    @Test
    fun anUnacknowledgedUrgentOutageIsMarked() {
        val rendered = texts(inflate(WidgetConfig(maxRows = 4)))
        assertTrue(
            "urgent rows should carry a marker: $rendered",
            rendered.any { it.contains("Charlie") && it.contains("⚠") },
        )
    }

    @Test
    fun theHeadlineMatchesTheFleet() {
        val rendered = texts(inflate(WidgetConfig()))
        assertTrue("expected the fleet headline: $rendered", rendered.any { it == fleet.headline })
    }

    @Test
    fun detailedRowsShowTheFailureMessage() {
        val rendered = texts(inflate(WidgetConfig(density = WidgetDensity.DETAILED, maxRows = 4)))
        assertTrue(
            "detailed rows should explain the failure: $rendered",
            rendered.any { it.contains("Connection refused") },
        )
    }

    @Test
    fun compactRowsHideTheDetailLine() {
        val rendered = texts(inflate(WidgetConfig(density = WidgetDensity.COMPACT, maxRows = 4)))
        assertFalse(rendered.any { it.contains("Connection refused") })
    }

    @Test
    fun hidingTheTitleRemovesTheTitle() {
        val withTitle = texts(inflate(WidgetConfig(showTitle = true)))
        val without = texts(inflate(WidgetConfig(showTitle = false)))
        assertTrue(withTitle.any { it == "Pulse" })
        assertFalse(without.any { it == "Pulse" })
    }

    // ---- reaching the settings again ---------------------------------------

    @Test
    fun theSettingsCogSurvivesHidingTheTitle() {
        // The header row has to stay for the cog even with the title off, or the
        // only route back into a placed widget's configuration disappears with it —
        // which is exactly the bug reported: "I can't find the settings of the
        // widget after I placed it".
        val root = inflate(WidgetConfig(showTitle = false, showSettingsButton = true))
        val cog = root.findViewById<View>(me.river.pulse.R.id.widget_settings)
        assertNotNull(cog)
        assertEquals(View.VISIBLE, cog.visibility)
        assertTrue("the cog must be tappable", cog.hasOnClickListeners())
    }

    @Test
    fun theSettingsCogCanBeTurnedOff() {
        val root = inflate(WidgetConfig(showSettingsButton = false))
        val cog = root.findViewById<View>(me.river.pulse.R.id.widget_settings)
        assertEquals(View.GONE, cog.visibility)
    }

    @Test
    fun aWidgetWithNoIdShowsNoCog() {
        // The preview the widget picker renders has no real appWidgetId, so a cog
        // there would open a configuration screen for nothing.
        val root = inflate(
            WidgetConfig(showSettingsButton = true),
            appWidgetId = android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID,
        )
        val cog = root.findViewById<View>(me.river.pulse.R.id.widget_settings)
        assertEquals(View.GONE, cog.visibility)
    }

    // ---- custom colours ----------------------------------------------------

    @Test
    fun customColoursAndOpacityInflate() {
        // The point of the test: `setColorFilter` and `setImageAlpha` must really be
        // @RemotableViewMethod. If either is not, apply() throws here rather than
        // showing "Problem loading widget" on somebody's home screen.
        listOf(0f, 0.35f, 1f).forEach { opacity ->
            val root = inflate(
                WidgetConfig(
                    theme = WidgetTheme.CUSTOM,
                    customBackgroundRgb = 0x102A43,
                    customTextRgb = 0x2FD98A,
                    backgroundOpacity = opacity,
                    maxRows = 4,
                ),
            )
            assertNotNull("opacity $opacity failed to inflate", root)
            assertTrue("opacity $opacity rendered nothing", texts(root).isNotEmpty())
        }
    }

    @Test
    fun aFullyTransparentWidgetStillRendersItsText() {
        val rendered = texts(
            inflate(
                WidgetConfig(
                    theme = WidgetTheme.CUSTOM,
                    backgroundOpacity = 0f,
                    maxRows = 4,
                ),
            ),
        )
        assertTrue("text must survive a transparent surface: $rendered", rendered.any { it.contains("Charlie") })
    }

    @Test
    fun hidingTheTimestampRemovesTheFooter() {
        val with = texts(inflate(WidgetConfig(showTimestamp = true, maxRows = 4)))
        val without = texts(inflate(WidgetConfig(showTimestamp = false, maxRows = 4)))
        assertTrue(with.any { it.startsWith("Checked ") })
        assertFalse(without.any { it.startsWith("Checked ") })
    }

    @Test
    fun onlyProblemsHidesHealthyMonitors() {
        val rendered = texts(inflate(WidgetConfig(onlyProblems = true, maxRows = 6)))
        assertTrue(rendered.any { it.contains("Charlie") })
        assertTrue(rendered.any { it.contains("Bravo") })
        assertFalse("healthy monitors should be hidden: $rendered", rendered.any { it.contains("Alpha") })
    }

    @Test
    fun rowCapIsRespectedAndOverflowIsDisclosed() {
        val rendered = texts(inflate(WidgetConfig(maxRows = 2)))
        assertTrue(rendered.any { it.contains("Charlie") })
        assertFalse("only two rows should render: $rendered", rendered.any { it.contains("Alpha") })
        // Silently truncating would read as "that's everything".
        assertTrue("overflow should be disclosed: $rendered", rendered.any { it.contains("+2 more") })
    }

    @Test
    fun anEmptyFleetStillInflatesWithAPrompt() {
        val views = PulseWidgetProvider.build(appContext, WidgetConfig(), Summary.Fleet())
        val root = views.apply(appContext, FrameLayout(appContext))
        assertTrue(texts(root).any { it.contains("No monitors yet") })
    }

    @Test
    fun everythingHealthySaysSoRatherThanShowingNothing() {
        val healthy = Summary.of(
            listOf(monitor("a", "Alpha")),
            mapOf("a" to runtime(Health.UP)),
        )
        val views = PulseWidgetProvider.build(appContext, WidgetConfig(onlyProblems = true), healthy)
        val root = views.apply(appContext, FrameLayout(appContext))
        assertTrue(texts(root).any { it.contains("Everything is healthy") })
    }

    // ---- configuration persistence ------------------------------------------

    @Test
    fun configRoundTripsAndSurvivesUnknownWidgetIds() = runBlocking {
        val id = 987_001
        WidgetConfigStore.delete(appContext, intArrayOf(id))
        // An id that was never saved must read as defaults, not crash.
        assertEquals(WidgetConfig(), WidgetConfigStore.load(appContext, id))

        val config = WidgetConfig(
            theme = WidgetTheme.BLUE,
            density = WidgetDensity.DETAILED,
            showTitle = false,
            showTimestamp = false,
            onlyProblems = true,
            maxRows = 7,
        )
        WidgetConfigStore.save(appContext, id, config)
        assertEquals(config, WidgetConfigStore.load(appContext, id))

        WidgetConfigStore.delete(appContext, intArrayOf(id))
        assertEquals(WidgetConfig(), WidgetConfigStore.load(appContext, id))
    }

    @Test
    fun configsAreRemappedWhenTheLauncherReassignsIds() = runBlocking {
        val oldIds = intArrayOf(987_010, 987_011)
        val newIds = intArrayOf(987_020, 987_021)
        WidgetConfigStore.delete(appContext, oldIds + newIds)

        WidgetConfigStore.save(appContext, oldIds[0], WidgetConfig(theme = WidgetTheme.WHITE))
        WidgetConfigStore.save(appContext, oldIds[1], WidgetConfig(maxRows = 9))

        WidgetConfigStore.remap(appContext, oldIds, newIds)

        assertEquals(WidgetTheme.WHITE, WidgetConfigStore.load(appContext, newIds[0]).theme)
        assertEquals(9, WidgetConfigStore.load(appContext, newIds[1]).maxRows)
        // The old ids are gone, so a stale entry can't resurrect later.
        assertEquals(WidgetConfig(), WidgetConfigStore.load(appContext, oldIds[0]))

        WidgetConfigStore.delete(appContext, oldIds + newIds)
    }

    @Test
    fun eachWidgetInstanceKeepsItsOwnConfiguration() = runBlocking {
        val a = 987_030
        val b = 987_031
        WidgetConfigStore.save(appContext, a, WidgetConfig(theme = WidgetTheme.BLACK, maxRows = 2))
        WidgetConfigStore.save(appContext, b, WidgetConfig(theme = WidgetTheme.BLUE, maxRows = 8))

        assertEquals(WidgetTheme.BLACK, WidgetConfigStore.load(appContext, a).theme)
        assertEquals(2, WidgetConfigStore.load(appContext, a).maxRows)
        assertEquals(WidgetTheme.BLUE, WidgetConfigStore.load(appContext, b).theme)
        assertEquals(8, WidgetConfigStore.load(appContext, b).maxRows)

        WidgetConfigStore.delete(appContext, intArrayOf(a, b))
    }

    @Test
    fun refreshIsSafeWithNoWidgetsPlaced() {
        // The common case: nobody has added the widget. Must not throw.
        PulseWidgetProvider.refresh(appContext)
    }
}
