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

    /** Header: an 18dp mark on a row with 10dp of padding under it. */
    private const val HEADER_HEIGHT_DP = 38

    /** Footer: one 10sp line plus its top padding. */
    private const val FOOTER_HEIGHT_DP = 20

    /** A compact row is one 13sp line; detailed adds a 10sp line under it. */
    private const val COMPACT_ROW_HEIGHT_DP = 27
    private const val DETAILED_ROW_HEIGHT_DP = 41

    /** Gutter between columns, so one column's value does not touch the next one's dot. */
    const val COLUMN_GAP_DP = 12

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
    ): Plan {
        if (wanted <= 0) return Plan(columns = 1, perColumn = 0)

        val rowHeight = if (config.density == WidgetDensity.DETAILED) {
            DETAILED_ROW_HEIGHT_DP
        } else {
            COMPACT_ROW_HEIGHT_DP
        }

        val chromeWithoutFooter = CHROME_WIDTH_DP + if (config.headerVisible) HEADER_HEIGHT_DP else 0
        val chrome = chromeWithoutFooter + if (mightHaveFooter) FOOTER_HEIGHT_DP else 0

        // Try it with the footer; if that leaves room for nothing at all, spend the footer
        // on a row rather than clipping both.
        var suppressFooter = false
        var rows = if (heightDp <= 0) wanted else (heightDp - chrome) / rowHeight
        if (heightDp > 0 && rows < 1 && mightHaveFooter) {
            val withoutFooter = (heightDp - chromeWithoutFooter) / rowHeight
            if (withoutFooter >= 1) {
                suppressFooter = true
                rows = withoutFooter
            }
        }
        // At least one row always. A widget too short for even that is one the launcher
        // will not let the user create, and drawing nothing would look broken.
        val perColumn = rows.coerceAtLeast(1)

        // Width is the hard limit: no number of monitors justifies a column too narrow to
        // read, so this caps both the automatic and the manual choice.
        val fits = if (widthDp <= 0) {
            1
        } else {
            ((widthDp - CHROME_WIDTH_DP) / MIN_COLUMN_WIDTH_DP).coerceIn(1, MAX_COLUMNS)
        }

        val columns = if (config.columns > 0) {
            config.columns.coerceIn(1, MAX_COLUMNS).coerceAtMost(fits)
        } else {
            // Only spill sideways once the monitors genuinely do not fit downwards.
            val needed = ceilDiv(wanted, perColumn)
            needed.coerceIn(1, fits)
        }

        // Rebalance so the columns are even rather than one full column and a stub: five
        // monitors over two columns reads better as 3+2 than as 4+1.
        val balanced = ceilDiv(wanted.coerceAtMost(columns * perColumn), columns)

        val columnWidth = if (widthDp <= 0) {
            Int.MAX_VALUE
        } else {
            (widthDp - CHROME_WIDTH_DP - COLUMN_GAP_DP * (columns - 1)) / columns
        }

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
}

/** Whether anything in the header row is switched on. */
val WidgetConfig.headerVisible: Boolean
    get() = showLogo || showTitle || showHeadline
