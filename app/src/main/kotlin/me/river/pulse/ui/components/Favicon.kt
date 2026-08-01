package me.river.pulse.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import me.river.pulse.data.Pulse

/**
 * The site icon for [pageUrl], or null until (or unless) one is available.
 *
 * Keyed on the URL rather than the monitor, because two monitors on one site
 * share an icon and should share the fetch — the caching that makes that true
 * lives in [me.river.pulse.data.icons.FaviconStore].
 *
 * Returns null on the first frame and updates when the icon arrives, so callers
 * must have something to draw in the meantime.
 */
@Composable
fun rememberFavicon(pageUrl: String, enabled: Boolean = true): ImageBitmap? {
    val context = LocalContext.current
    var image by remember(pageUrl) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(pageUrl, enabled) {
        if (!enabled || pageUrl.isBlank()) return@LaunchedEffect
        // A cache hit resolves before the next frame; a miss resolves whenever
        // the network does, and the badge simply keeps its fallback glyph.
        image = Pulse.from(context).favicons.load(pageUrl)?.asImageBitmap()
    }
    return image
}
