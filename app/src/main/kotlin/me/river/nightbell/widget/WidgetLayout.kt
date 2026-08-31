package me.river.nightbell.widget

/**
 * How many columns of monitors a widget of a given size should draw, and how many rows
 * go in each.
 *
 * Separate from [NightbellWidgetProvider] and free of Android types on purpose. The whole
 * point of this file is arithmetic that decides whether a monitor is visible at all, and
 * arithmetic that can only be exercised by resizing a widget by hand on a home screen is
 * arithmetic that never gets exercised.
 *
 * ### Why columns exist
 * A widget's height is whatever the user dragged it to. Squash one flat and the old
 * layout simply stopped drawing monitors past the fold — they were in the list, counted
 * in "+3 more", and invisible. Spilling them into a second column uses the width that a
 * short widget has going spare, which is the whole reason someone made it short.
 */
object WidgetLayout {

    /**
     * Three is the ceiling.
     *
     * At four columns on a phone-width widget each monitor gets under 90dp, which
     * ellipsises every name to "Market…" and turns a glanceable list into a puzzle.
     */
    const val MAX_COLUMNS = 3

    /**
     * Narrowest a column may get before it stops being worth having.
     *
     * A row is a 9dp dot, a 10dp gap, the name, and a value like "1240 ms". Below roughly
     * this the name has no room left.
     *
     * 104 rather than a rounder 120 because a four-cell-wide widget reports about 250dp,
     * and at 120 that arithmetic allowed exactly one column — which quietly disabled this
     * whole feature at the most common widget size there is.
     */
    private const val MIN_COLUMN_WIDTH_DP = 104

    /** `widget_nightbell.xml`'s padding, both sides. */
    private const val CHROME_WIDTH_DP = 28

    /**
     * The heights this arithmetic divides by, as a line through the font scale.
     *
     * They were four numbers read off the XML, and all four were short: a compact row
     * measures 28.95dp against the 27 that was assumed, so a widget with room for six rows
     * planned seven and drew the last one off the bottom edge. That shipped, and the only
     * reason it was never obvious is that what got clipped was usually the footer.
     *
     * Fitted to the views measured on a device rather than added up from the XML, because
     * a TextView is not the sum of its attributes: text lands on whole pixels and a line
     * of 13sp is 20.4dp of one, not 13. Two of the four are barely linear (the header
     * holds still until the title outgrows the 28dp cog), so each line is the steepest
     * segment of what was measured, which over-reserves in the middle and never under.
     *
     * `WidgetInstrumentedTest.theHeightConstantsCoverWhatIsDrawn` measures the real views
     * against these at 100, 130 and 200 per cent font, and is the reason to trust them.
     */
    private const val HEADER_FIXED_DP = 30.5f
    private const val HEADER_PER_SCALE_DP = 7.7f
    private const val FOOTER_FIXED_DP = 8.4f
    private const val FOOTER_PER_SCALE_DP = 13.8f
    private const val COMPACT_FIXED_DP = 8.6f
    private const val COMPACT_PER_SCALE_DP = 20.4f
    private const val DETAILED_FIXED_DP = 9.7f
    private const val DETAILED_PER_SCALE_DP = 33f

    /** Every height the planner needs, at one particular font scale. */
    internal data class Metrics(
        val header: Int,
        val footer: Int,
        val compactRow: Int,
        val detailedRow: Int,
    )

    internal fun metrics(fontScale: Float): Metrics {
        val scale = fontScale.coerceAtLeast(1f)
        fun height(fixed: Float, perScale: Float): Int = kotlin.math.ceil(fixed + perScale * scale).toInt()
        return Metrics(
            header = height(HEADER_FIXED_DP, HEADER_PER_SCALE_DP),
            footer = height(FOOTER_FIXED_DP, FOOTER_PER_SCALE_DP),
            compactRow = height(COMPACT_FIXED_DP, COMPACT_PER_SCALE_DP),
            detailedRow = height(DETAILED_FIXED_DP, DETAILED_PER_SCALE_DP),
        )
    }

    /** Gutter between columns, so one column's value does not touch the next one's dot. */
    const val COLUMN_GAP_DP = 12

    /**
     * How many rows to draw before the launcher has said how big the widget is.
     *
     * That first render has no height to divide, and it used to draw the whole list on the
     * grounds that a list capped at ten could not do much damage. With the cap on
     * automatic the list is the fleet, so the same code would build thirty rows into a
     * widget that might have room for two, and the overflow would be visible for the
     * moment it takes `onAppWidgetOptionsChanged` to arrive with real numbers. Six is
     * about what the common sizes hold.
     */
    private const val ROWS_WHEN_UNMEASURED = 6

    /**
     * Below this much column width, the trailing value ("DOWN", "4100 ms") is dropped.
     *
     * Two columns in a four-cell widget leaves about 105dp each, and the value was eating
     * enough of that to truncate "Marketing site" to "Market…". The dot already carries
     * health, and the number is one tap away, so the name wins the space. Above the
     * threshold there is room for both and nothing is hidden.
     */
    private const val VALUE_MIN_COLUMN_WIDTH_DP = 150

    /**
     * The plan: [columns] side by side, at most [perColumn] monitors down each.
     *
     * [capacity] is what the widget can actually show, which is not always
     * `columns * perColumn` — the caller trims to it before distributing, and reports
     * the remainder as "+n more".
     */
    data class Plan(
        val columns: Int,
        val perColumn: Int,
        /**
         * Drop the footer to buy back a row.
         *
         * A four-cell-by-two widget is 110dp, and a header, one row and a footer come to
         * 113. Something has to go, and "Checked just now" is worth less than the monitor
         * it was pushing off the bottom — especially since the alternative was not a
         * missing footer but a *clipped* one, which looks broken rather than deliberate.
         */
        val suppressFooter: Boolean = false,
        /** Whether columns are wide enough to carry the trailing value as well as the name. */
        val showValues: Boolean = true,
    ) {
        val capacity: Int get() = columns * perColumn
    }

    /**
     * Decide the shape for [wanted] monitors in a widget measured [widthDp] × [heightDp].
     *
     * Sizes of zero mean "the launcher has not told us yet", which happens on the very
     * first render after a widget is dropped. Falling back to a single column is right:
     * it is the layout that cannot be wrong, and `onAppWidgetOptionsChanged` fires with
     * real numbers a moment later.
     */
    fun plan(
        config: WidgetConfig,
        wanted: Int,
        widthDp: Int,
        heightDp: Int,
        mightHaveFooter: Boolean = true,
        fontScale: Float = 1f,
    ): Plan {
        if (wanted <= 0) return Plan(columns = 1, perColumn = 0)

        val metrics = metrics(fontScale)
        val rowHeight = if (config.density == WidgetDensity.DETAILED) {
            metrics.detailedRow
        } else {
            metrics.compactRow
        }

        val chromeWithoutFooter = CHROME_WIDTH_DP + if (config.headerVisible) metrics.header else 0
        val chrome = chromeWithoutFooter + if (mightHaveFooter) metrics.footer else 0

        // How many rows the height holds, with the footer and without it.
        fun budget(chromeDp: Int): Int = if (heightDp <= 0) {
            wanted.coerceAtMost(ROWS_WHEN_UNMEASURED)
        } else {
            (heightDp - chromeDp) / rowHeight
        }
        val withFooter = budget(chrome)
        val withoutFooter = budget(chromeWithoutFooter)

        // A widget with no room for even one row and a footer loses the footer: one row is
        // drawn regardless, and on a widget squashed to a single line of dots the footer
        // was taking a third of the height off the only monitor anybody could see.
        val squashed = mightHaveFooter && withFooter < 1

        // At least one row always. A widget too short for even that is one the launcher
        // will not let the user create, and drawing nothing would look broken.
        val keepRows = (if (squashed) withoutFooter else withFooter).coerceAtLeast(1)
        val dropRows = withoutFooter.coerceAtLeast(1)

        // Width is the hard limit: no number of monitors justifies a column too narrow to
        // read, so this caps both the automatic and the manual choice.
        val fits = if (widthDp <= 0) {
            1
        } else {
            // Counted with the gutters in, which they were not: a 340dp widget divided by
            // a 104dp minimum said three columns, and three columns of a 340dp widget are
            // 96dp each once the two 12dp gaps are taken out. Every name in them
            // ellipsised, which is the exact outcome the minimum exists to prevent.
            (MAX_COLUMNS downTo 2).firstOrNull { columnWidthDp(widthDp, it) >= MIN_COLUMN_WIDTH_DP } ?: 1
        }

        val choices = if (config.columns > 0) {
            listOf(config.columns.coerceIn(1, MAX_COLUMNS).coerceAtMost(fits))
        } else {
            (1..fits).toList()
        }

        // The cheapest shape that shows every monitor asked for, in that order of cost:
        // one column before two, and the footer before a second column.
        //
        // Fewest columns first, because a column costs width, and a narrow column costs the
        // latency reading beside every name. Then the footer, because "Checked just now" is
        // the least of what the widget has to say and it is worth exactly one row: at 250
        // square that row is the difference between six monitors and five with a "+1 more".
        //
        // The footer only goes when losing it means nothing is hidden. Dropping it to show
        // fourteen of twenty would take away the one line that says the list is
        // incomplete, which is a worse widget than twelve and an honest "+8 more".
        var columns = choices.last()
        var perColumn = keepRows
        var suppressFooter = squashed
        for (count in choices) {
            if (count * keepRows >= wanted) {
                columns = count
                perColumn = keepRows
                suppressFooter = squashed
                break
            }
            if (!squashed && mightHaveFooter && count * dropRows >= wanted) {
                columns = count
                perColumn = dropRows
                suppressFooter = true
                break
            }
        }

        // Rebalance so the columns are even rather than one full column and a stub: five
        // monitors over two columns reads better as 3+2 than as 4+1.
        val balanced = ceilDiv(wanted.coerceAtMost(columns * perColumn), columns)

        val columnWidth = if (widthDp <= 0) Int.MAX_VALUE else columnWidthDp(widthDp, columns)

        return Plan(
            columns = columns,
            perColumn = balanced.coerceIn(1, perColumn),
            suppressFooter = suppressFooter,
            showValues = columnWidth >= VALUE_MIN_COLUMN_WIDTH_DP,
        )
    }

    /**
     * Split [items] down each column in turn.
     *
     * Column-major, so the worst-first ordering still reads top-to-bottom in the first
     * column before continuing in the second. Row-major would scatter the three most
     * broken monitors across the top of the widget in an order nobody asked for.
     */
    fun <T> distribute(items: List<T>, plan: Plan): List<List<T>> =
        (0 until plan.columns)
            .map { column ->
                val from = column * plan.perColumn
                items.subList(from.coerceAtMost(items.size), ((column + 1) * plan.perColumn).coerceAtMost(items.size))
            }
            .filter { it.isNotEmpty() }

    private fun ceilDiv(a: Int, b: Int): Int = if (b <= 0) a else (a + b - 1) / b

    /** What one of [columns] columns gets, once padding and gutters are gone. */
    private fun columnWidthDp(widthDp: Int, columns: Int): Int =
        (widthDp - CHROME_WIDTH_DP - COLUMN_GAP_DP * (columns - 1)) / columns
}

/** Whether anything in the header row is switched on. */
val WidgetConfig.headerVisible: Boolean
    get() = showLogo || showTitle || showHeadline
