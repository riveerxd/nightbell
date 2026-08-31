package me.river.nightbell

import me.river.nightbell.widget.WidgetConfig
import me.river.nightbell.widget.WidgetDensity
import me.river.nightbell.widget.WidgetLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The widget's column planning.
 *
 * This is the arithmetic that decides whether a monitor appears on someone's home screen
 * or silently does not, and the only way to exercise it by hand is to drag a widget
 * around a launcher — which is to say, never. The cases below are the sizes a widget
 * actually gets dragged to.
 *
 * Sizes are dp, as `AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH` / `MAX_HEIGHT` report
 * them: roughly 4x1 = 250x40, 4x2 = 250x110, 4x3 = 250x180, 2x2 = 110x110.
 */
class WidgetLayoutTest {

    private val compact = WidgetConfig(density = WidgetDensity.COMPACT)
    private val detailed = WidgetConfig(density = WidgetDensity.DETAILED)

    @Test
    fun `a tall widget stacks everything in one column`() {
        val plan = WidgetLayout.plan(compact, wanted = 5, widthDp = 250, heightDp = 300)
        assertEquals(1, plan.columns)
        assertTrue("all five should fit downwards", plan.capacity >= 5)
    }

    @Test
    fun `a short wide widget shows more monitors than one column could`() {
        // 4x2 is about 250x110dp. Once a header and footer are taken out that is one
        // compact row of usable height — which is why the old single-column layout showed
        // one monitor and counted the other four as "+4 more". The gain here is not that
        // all five fit; it is that the spare width stops being wasted.
        val forcedSingle = WidgetLayout.plan(
            compact.copy(columns = 1),
            wanted = 5,
            widthDp = 250,
            heightDp = 110,
        )
        val automatic = WidgetLayout.plan(compact, wanted = 5, widthDp = 250, heightDp = 110)
        assertTrue("should have gone multi-column, got ${automatic.columns}", automatic.columns >= 2)
        assertTrue(
            "columns must show strictly more than a single column could " +
                "(${automatic.capacity} vs ${forcedSingle.capacity})",
            automatic.capacity > forcedSingle.capacity,
        )
    }

    @Test
    fun `flattening a widget adds columns rather than dropping rows`() {
        val tall = WidgetLayout.plan(compact, wanted = 6, widthDp = 400, heightDp = 300)
        val squashed = WidgetLayout.plan(compact, wanted = 6, widthDp = 400, heightDp = 100)
        assertEquals("everything fits downwards when there is height", 1, tall.columns)
        assertTrue(
            "squashing should widen the layout: ${tall.columns} -> ${squashed.columns}",
            squashed.columns > tall.columns,
        )
        assertTrue(
            "and it should not cost visible monitors: ${tall.capacity} -> ${squashed.capacity}",
            squashed.capacity >= 3,
        )
    }

    @Test
    fun `a narrow widget never splits, however short it is`() {
        // 2x1: there is not room for two readable columns at any height.
        val plan = WidgetLayout.plan(compact, wanted = 6, widthDp = 110, heightDp = 60)
        assertEquals("a narrow widget must stay single-column", 1, plan.columns)
    }

    @Test
    fun `detailed rows are taller so they run out of vertical room sooner`() {
        val compactPlan = WidgetLayout.plan(compact, wanted = 4, widthDp = 250, heightDp = 150)
        val detailedPlan = WidgetLayout.plan(detailed, wanted = 4, widthDp = 250, heightDp = 150)
        assertTrue(
            "detailed should need at least as many columns as compact " +
                "(${compactPlan.columns} vs ${detailedPlan.columns})",
            detailedPlan.columns >= compactPlan.columns,
        )
    }

    @Test
    fun `an explicit column count is honoured even when everything would fit downwards`() {
        val plan = WidgetLayout.plan(
            compact.copy(columns = 2),
            wanted = 4,
            widthDp = 300,
            heightDp = 400,
        )
        assertEquals(2, plan.columns)
        assertEquals("four over two columns should balance", 2, plan.perColumn)
    }

    @Test
    fun `an explicit column count still collapses when the widget is too narrow`() {
        val plan = WidgetLayout.plan(
            compact.copy(columns = 3),
            wanted = 6,
            widthDp = 110,
            heightDp = 400,
        )
        assertEquals(
            "an unreadable column is worse than a tall list",
            1,
            plan.columns,
        )
    }

    @Test
    fun `columns never exceed the ceiling`() {
        val plan = WidgetLayout.plan(
            compact.copy(columns = 9),
            wanted = 10,
            widthDp = 2_000,
            heightDp = 40,
        )
        assertTrue(plan.columns <= WidgetLayout.MAX_COLUMNS)
    }

    @Test
    fun `an unmeasured widget falls back to one column`() {
        // The launcher has not reported a size yet, which happens on the first render
        // after a widget is dropped.
        val plan = WidgetLayout.plan(compact, wanted = 5, widthDp = 0, heightDp = 0)
        assertEquals(1, plan.columns)
        assertEquals("nothing should be dropped while the size is unknown", 5, plan.capacity)
    }

    @Test
    fun `no monitors means no columns to fill`() {
        val plan = WidgetLayout.plan(compact, wanted = 0, widthDp = 250, heightDp = 110)
        assertEquals(0, plan.perColumn)
    }

    @Test
    fun `hiding the header gives its height back to the rows`() {
        val withHeader = WidgetLayout.plan(compact, wanted = 6, widthDp = 250, heightDp = 130)
        val without = WidgetLayout.plan(
            compact.copy(showLogo = false, showTitle = false, showHeadline = false),
            wanted = 6,
            widthDp = 250,
            heightDp = 130,
        )
        assertTrue(
            "a headerless widget should fit at least as many rows per column " +
                "(${withHeader.perColumn} vs ${without.perColumn})",
            without.perColumn >= withHeader.perColumn,
        )
    }

    @Test
    fun `a squashed widget drops the footer rather than clipping a monitor`() {
        // 4x2: a header, one row and a footer come to more than 110dp. Something has to
        // give, and it should be "Checked just now" rather than half a row of monitor.
        val plan = WidgetLayout.plan(compact, wanted = 4, widthDp = 250, heightDp = 110)
        assertTrue("the footer should have been sacrificed", plan.suppressFooter)
        assertTrue("and a row should have been bought with it", plan.perColumn >= 1)
    }

    @Test
    fun `a widget with room keeps its footer`() {
        // Three monitors in a widget that holds three and the footer: there is nothing to
        // buy with the timestamp, so it stays.
        val plan = WidgetLayout.plan(compact, wanted = 3, widthDp = 250, heightDp = 200)
        assertFalse("nothing needed sacrificing here", plan.suppressFooter)
        assertTrue("and all three are shown", plan.capacity >= 3)
    }

    @Test
    fun `narrow columns drop the trailing value so names stay readable`() {
        // Two columns in a four-cell widget is about 105dp each — the value was truncating
        // "Marketing site" to "Market…".
        val narrow = WidgetLayout.plan(compact, wanted = 6, widthDp = 250, heightDp = 180)
        assertTrue("expected two columns for this case", narrow.columns >= 2)
        assertFalse("values should be dropped in a narrow column", narrow.showValues)
    }

    @Test
    fun `a single wide column keeps the trailing value`() {
        val wide = WidgetLayout.plan(compact, wanted = 3, widthDp = 250, heightDp = 300)
        assertEquals(1, wide.columns)
        assertTrue("a full-width column has room for both", wide.showValues)
    }

    @Test
    fun `wide enough columns keep their values`() {
        val plan = WidgetLayout.plan(compact.copy(columns = 2), wanted = 4, widthDp = 420, heightDp = 300)
        assertEquals(2, plan.columns)
        assertTrue("210dp a column is plenty for a name and a number", plan.showValues)
    }

    @Test
    fun `a column count is counted with the gutters in`() {
        // 340dp is a five-cell widget. Divided by the 104dp minimum it looks like three
        // columns; take out the two 12dp gaps between them and each one is 96dp, which
        // ellipsises every name. Two columns of 150dp is the honest answer.
        val plan = WidgetLayout.plan(compact, wanted = 12, widthDp = 340, heightDp = 284)
        assertEquals(2, plan.columns)
        assertTrue("150dp a column has room for the latency too", plan.showValues)
    }

    @Test
    fun `a tall widget spends the footer before it spends a column`() {
        // 250x250 holds five compact rows with the footer and six without. Six monitors
        // used to spill into two columns of three, which left half the surface empty and
        // cost every latency reading; the row the timestamp was sitting on is the cheaper
        // thing to spend.
        val plan = WidgetLayout.plan(compact, wanted = 6, widthDp = 250, heightDp = 250)
        assertEquals(1, plan.columns)
        assertTrue("everything should be visible: ${plan.capacity}", plan.capacity >= 6)
        assertTrue("the timestamp is what paid for it", plan.suppressFooter)
        assertTrue("and a full-width column keeps its values", plan.showValues)
    }

    @Test
    fun `the footer survives when dropping it would not show anything more`() {
        // Twenty monitors on a widget that holds twelve either way. Losing the footer here
        // would buy two more rows and take away the "+8 more" that says the list is
        // incomplete, which is the one thing the footer is for.
        val plan = WidgetLayout.plan(compact, wanted = 20, widthDp = 340, heightDp = 284)
        assertFalse("nothing was bought by dropping it", plan.suppressFooter)
        assertTrue("and monitors are still hidden: ${plan.capacity} of 20", plan.capacity < 20)
    }

    @Test
    fun `a capped count still spills into the column it has room for`() {
        // The widget this was reported against: 531x300 holds seven rows a column, and a
        // user who asked for ten monitors must get ten, not seven and a "+6 more".
        val plan = WidgetLayout.plan(compact, wanted = 10, widthDp = 531, heightDp = 300)
        assertEquals(2, plan.columns)
        assertTrue("all ten should be visible: ${plan.capacity}", plan.capacity >= 10)
        assertTrue("245dp a column has room for the latency", plan.showValues)
    }

    @Test
    fun `the reported widget shows a thirteen-monitor fleet whole`() {
        val plan = WidgetLayout.plan(compact, wanted = 13, widthDp = 531, heightDp = 300)
        assertTrue("nothing should be hidden: ${plan.capacity} of 13", plan.capacity >= 13)
        assertEquals("two columns of seven, not three of five", 2, plan.columns)
        assertFalse("and there was room for the timestamp too", plan.suppressFooter)
    }

    @Test
    fun `a spill still happens when a single column would hide most of the fleet`() {
        // The case the columns were built for: flat enough that one column shows almost
        // nothing, and wide enough to put the rest beside it.
        val plan = WidgetLayout.plan(compact, wanted = 8, widthDp = 400, heightDp = 120)
        assertTrue("expected to spill sideways, got ${plan.columns}", plan.columns >= 2)
    }

    @Test
    fun `an unmeasured widget does not build the whole fleet`() {
        // Automatic hands the planner every monitor there is. Until the launcher reports a
        // size there is no height to divide, and drawing forty rows into a widget that
        // might hold two is a RemoteViews the launcher has to carry across a process
        // boundary before it can be corrected.
        val plan = WidgetLayout.plan(compact, wanted = 40, widthDp = 0, heightDp = 0)
        assertEquals(1, plan.columns)
        assertTrue("nothing like forty rows: ${plan.capacity}", plan.capacity <= 8)
    }

    @Test
    fun `larger text means fewer rows in the same widget`() {
        val normal = WidgetLayout.plan(compact, wanted = 8, widthDp = 250, heightDp = 300)
        val large = WidgetLayout.plan(compact, wanted = 8, widthDp = 250, heightDp = 300, fontScale = 1.5f)
        assertTrue(
            "a widget read at 150 per cent holds fewer rows " +
                "(${normal.perColumn} vs ${large.perColumn})",
            large.perColumn < normal.perColumn,
        )
    }

    @Test
    fun `distribute fills each column top to bottom, worst first`() {
        val items = listOf("a", "b", "c", "d", "e")
        val plan = WidgetLayout.Plan(columns = 2, perColumn = 3)
        val columns = WidgetLayout.distribute(items, plan)
        assertEquals(listOf(listOf("a", "b", "c"), listOf("d", "e")), columns)
    }

    @Test
    fun `distribute drops empty columns rather than drawing blank space`() {
        val plan = WidgetLayout.Plan(columns = 3, perColumn = 2)
        val columns = WidgetLayout.distribute(listOf("a", "b"), plan)
        assertEquals(1, columns.size)
    }

    @Test
    fun `distribute never loses or duplicates a monitor`() {
        val items = (1..7).map { "m$it" }
        for (width in listOf(110, 200, 250, 320, 400)) {
            for (height in listOf(40, 70, 110, 180, 300)) {
                val plan = WidgetLayout.plan(compact, items.size, width, height)
                val shown = items.take(plan.capacity)
                val flattened = WidgetLayout.distribute(shown, plan).flatten()
                assertEquals(
                    "every planned monitor must be placed exactly once at ${width}x$height",
                    shown,
                    flattened,
                )
            }
        }
    }
}
