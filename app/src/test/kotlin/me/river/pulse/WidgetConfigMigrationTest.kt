package me.river.pulse

import me.river.pulse.widget.WidgetConfig
import me.river.pulse.widget.WidgetConfigStore
import me.river.pulse.widget.WidgetDensity
import me.river.pulse.widget.WidgetTheme
import me.river.pulse.widget.headerVisible
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading a widget config written by an older build.
 *
 * `showTitle` used to be one flag covering the mark, the wordmark and the fleet headline.
 * It is three flags now, all defaulting to true — so without a migration every widget
 * whose owner had deliberately switched the header *off* would come back from an update
 * with it on. That is the kind of regression nobody reports as a bug; they just quietly
 * stop using the widget.
 *
 * The JSON strings below are what those builds actually wrote.
 */
class WidgetConfigMigrationTest {

    @Test
    fun `a widget with the old header switched off stays switched off`() {
        val legacy = """
            {"theme":"BLACK","density":"COMPACT","showTitle":false,"showTimestamp":true,
             "onlyProblems":false,"maxRows":5,"customBackgroundRgb":720895,
             "customTextRgb":16777215,"backgroundOpacity":0.94,"showSettingsButton":true}
        """.trimIndent()

        val config = WidgetConfigStore.decode(legacy)

        assertFalse("the mark must stay hidden", config.showLogo)
        assertFalse("the wordmark must stay hidden", config.showTitle)
        assertFalse("the fleet headline must stay hidden", config.showHeadline)
        assertFalse("so the header row is empty", config.headerVisible)
    }

    @Test
    fun `a widget with the old header switched on keeps all three pieces`() {
        val legacy = """{"theme":"WHITE","density":"DETAILED","showTitle":true,"maxRows":3}"""

        val config = WidgetConfigStore.decode(legacy)

        assertTrue(config.showLogo)
        assertTrue(config.showTitle)
        assertTrue(config.showHeadline)
        // And the rest of the document still reads normally.
        assertEquals(WidgetTheme.WHITE, config.theme)
        assertEquals(WidgetDensity.DETAILED, config.density)
        assertEquals(3, config.maxRows)
    }

    @Test
    fun `a config written since the split is read verbatim`() {
        // showLogo present means this document already knows about the three flags, so the
        // migration must keep its hands off — including the mixed state that a migration
        // from showTitle could never produce.
        val current = """
            {"theme":"BLACK","density":"COMPACT","showLogo":true,"showTitle":false,
             "showHeadline":true,"showTimestamp":true,"onlyProblems":false,"maxRows":5,
             "columns":2,"showSettingsButton":false}
        """.trimIndent()

        val config = WidgetConfigStore.decode(current)

        assertTrue("logo was explicitly on", config.showLogo)
        assertFalse("wordmark was explicitly off", config.showTitle)
        assertTrue("headline was explicitly on", config.showHeadline)
        assertEquals(2, config.columns)
        assertFalse(config.showSettingsButton)
    }

    @Test
    fun `an unreadable or missing document falls back to the defaults`() {
        for (raw in listOf(null, "", "   ", "{not json", """{"theme":"NOPE"}""")) {
            val config = WidgetConfigStore.decode(raw)
            assertEquals(
                "a corrupt config must not strand a widget on a broken layout: $raw",
                WidgetConfig(),
                config,
            )
        }
    }

    @Test
    fun `columns defaults to automatic for every older document`() {
        val legacy = """{"theme":"BLACK","showTitle":true,"maxRows":5}"""
        assertEquals(0, WidgetConfigStore.decode(legacy).columns)
    }
}
