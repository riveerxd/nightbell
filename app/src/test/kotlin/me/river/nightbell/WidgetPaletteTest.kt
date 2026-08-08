package me.river.nightbell

import me.river.nightbell.widget.WidgetConfig
import me.river.nightbell.widget.WidgetTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Widget colour resolution.
 *
 * All of it is arithmetic done ahead of time because `RemoteViews` cannot compute
 * anything, and it is shared by the real widget and the Compose preview — so the
 * preview cannot drift from what the home screen will actually show.
 */
class WidgetPaletteTest {

    private fun alpha(argb: Int) = (argb ushr 24) and 0xFF

    private fun rgb(argb: Int) = argb and 0x00FFFFFF

    private val custom = WidgetConfig(theme = WidgetTheme.CUSTOM)

    // ---- presets ------------------------------------------------------------

    @Test
    fun `every preset is opaque enough to read and has a border`() {
        listOf(WidgetTheme.BLACK, WidgetTheme.WHITE, WidgetTheme.BLUE).forEach { theme ->
            val palette = WidgetConfig(theme = theme).palette
            assertTrue("$theme surface must be near-opaque", alpha(palette.background) > 0xE0)
            assertTrue("$theme needs a visible edge", alpha(palette.border) > 0)
            assertEquals("$theme primary text must be fully opaque", 0xFF, alpha(palette.primary))
        }
    }

    @Test
    fun `presets ignore the opacity slider`() {
        // A preset is a defined surface with known contrast. Letting a stray opacity
        // value apply to it would quietly turn a legible preset illegible.
        val opaque = WidgetConfig(theme = WidgetTheme.BLACK).palette
        val dragged = WidgetConfig(theme = WidgetTheme.BLACK, backgroundOpacity = 0f).palette
        assertEquals(opaque, dragged)
    }

    @Test
    fun `the white preset uses dark text and the dark presets use light text`() {
        assertTrue(rgb(WidgetConfig(theme = WidgetTheme.WHITE).palette.primary) < 0x333333)
        assertTrue(rgb(WidgetConfig(theme = WidgetTheme.BLACK).palette.primary) > 0xCCCCCC)
        assertTrue(rgb(WidgetConfig(theme = WidgetTheme.BLUE).palette.primary) > 0xCCCCCC)
    }

    // ---- custom colours -----------------------------------------------------

    @Test
    fun `a custom background keeps its hue and takes its alpha from the slider`() {
        val palette = custom.copy(customBackgroundRgb = 0x123456, backgroundOpacity = 0.5f).palette
        assertEquals(0x123456, rgb(palette.background))
        assertEquals(127, alpha(palette.background))
    }

    @Test
    fun `fully transparent means fully transparent, border included`() {
        // The headline feature. A visible ring around an invisible widget would read
        // as a rendering bug, so the edge fades with the surface.
        val palette = custom.copy(backgroundOpacity = 0f).palette
        assertEquals(0, alpha(palette.background))
        assertEquals(0, alpha(palette.border))
        // …and the text stays fully readable, which is the entire point.
        assertEquals(0xFF, alpha(palette.primary))
    }

    @Test
    fun `fully opaque is reachable`() {
        assertEquals(0xFF, alpha(custom.copy(backgroundOpacity = 1f).palette.background))
    }

    @Test
    fun `opacity is clamped rather than wrapping around`() {
        assertEquals(0, alpha(custom.copy(backgroundOpacity = -3f).palette.background))
        assertEquals(0xFF, alpha(custom.copy(backgroundOpacity = 9f).palette.background))
    }

    @Test
    fun `the text colour drives three legible shades`() {
        val palette = custom.copy(customTextRgb = 0x2FD98A).palette
        listOf(palette.primary, palette.secondary, palette.tertiary).forEach {
            assertEquals("hue must be preserved across all three", 0x2FD98A, rgb(it))
        }
        assertTrue(alpha(palette.primary) > alpha(palette.secondary))
        assertTrue(alpha(palette.secondary) > alpha(palette.tertiary))
        assertTrue("even the faintest shade must stay visible", alpha(palette.tertiary) > 0x60)
    }

    @Test
    fun `an alpha accidentally baked into a custom colour is ignored`() {
        // The picker stores RGB, but a config round-tripped through an older build,
        // or hand-edited, could carry alpha. Opacity is the only thing allowed to
        // set it, or the slider would silently stop working.
        val withStrayAlpha = custom.copy(
            customBackgroundRgb = 0x40123456,
            customTextRgb = 0x00FFFFFF,
            backgroundOpacity = 1f,
        ).palette
        assertEquals(0xFF, alpha(withStrayAlpha.background))
        assertEquals(0x123456, rgb(withStrayAlpha.background))
        assertEquals(0xFF, alpha(withStrayAlpha.primary))
    }

    // ---- the readable-default heuristic -------------------------------------

    @Test
    fun `a pale background suggests dark text and a dark one suggests light text`() {
        assertEquals(0x0A0A0A, WidgetConfig.suggestedTextRgb(0xFFFFFF))
        assertEquals(0x0A0A0A, WidgetConfig.suggestedTextRgb(0xF6F6F8))
        assertEquals(0xFFFFFF, WidgetConfig.suggestedTextRgb(0x000000))
        assertEquals(0xFFFFFF, WidgetConfig.suggestedTextRgb(0x0C1A3A))
        assertEquals(0xFFFFFF, WidgetConfig.suggestedTextRgb(0x3A3A3C))
    }

    @Test
    fun `every offered swatch produces text that contrasts with itself`() {
        // The guard on the swatch list: no shipped background may suggest a text
        // colour close to itself.
        WidgetConfig.BACKGROUND_SWATCHES.forEach { bg ->
            val suggested = WidgetConfig.suggestedTextRgb(bg)
            assertNotEquals("$bg suggests its own colour", bg, suggested)
            val palette = custom.copy(customBackgroundRgb = bg, customTextRgb = suggested).palette
            assertEquals(0xFF, alpha(palette.primary))
        }
    }

    @Test
    fun `no swatch list is empty or contains an alpha channel`() {
        assertTrue(WidgetConfig.BACKGROUND_SWATCHES.isNotEmpty())
        assertTrue(WidgetConfig.TEXT_SWATCHES.isNotEmpty())
        (WidgetConfig.BACKGROUND_SWATCHES + WidgetConfig.TEXT_SWATCHES).forEach {
            assertEquals("swatches are RGB, not ARGB", it, it and 0x00FFFFFF)
        }
    }

    // ---- compatibility -------------------------------------------------------

    @Test
    fun `an existing widget keeps its look and gains a settings button`() {
        // A widget placed by 1.5.0 decodes with defaults for every new field, so its
        // appearance must be byte-identical to before — and the cog it needs to be
        // reconfigurable at all must be on.
        val migrated = WidgetConfig(theme = WidgetTheme.BLACK)
        assertEquals(0xF00B0B0B.toInt(), migrated.palette.background)
        assertTrue(migrated.showSettingsButton)
        assertEquals(WidgetTheme.BLACK, WidgetConfig().theme)
    }
}
