package me.river.nightbell.ui.theme

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How wide a single column of content is allowed to get.
 *
 * Nightbell had no size-class handling at all: one `LazyColumn` with 18 dp of padding,
 * so a 10-inch tablet rendered monitor cards a thousand pixels wide and the detail
 * screen put its configuration labels a hand-span away from their values. A line of
 * body text stops being comfortable somewhere around 70 characters, and a card
 * stops being scannable well before that.
 *
 * The dashboard solves this differently — it grows *columns* (see the grid in
 * `DashboardScreen`) because a list of monitors genuinely benefits from more of
 * them being visible. Everything else is a single reading column, so it gets
 * clamped and centred instead.
 */
val ContentMaxWidth = 620.dp

/**
 * Content padding that keeps a single-column screen readable at any width.
 *
 * Returns the ordinary 18 dp gutters on a phone, and symmetric padding that
 * centres a [ContentMaxWidth] column on anything wider. Expressed as padding
 * rather than a width modifier so it composes with a lazy list's
 * `contentPadding`, where a `widthIn` would fight the list's own measurement.
 */
@Composable
@ReadOnlyComposable
fun readableContentPadding(
    top: Dp = 0.dp,
    bottom: Dp = 0.dp,
    minGutter: Dp = 18.dp,
): PaddingValues {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val slack = screenWidth - ContentMaxWidth
    val gutter = if (slack > minGutter * 2) slack / 2 else minGutter
    return PaddingValues(start = gutter, end = gutter, top = top, bottom = bottom)
}
