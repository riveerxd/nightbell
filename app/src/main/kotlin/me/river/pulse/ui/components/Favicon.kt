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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.river.pulse.data.Nightbell

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
    val store = Nightbell.from(context).favicons
    // Re-asks after a purge. Without this the store can refetch a changed icon
    // and the badge on screen would still be showing the one it resolved once,
    // for as long as the screen stays composed.
    val generation by store.generation.collectAsStateWithLifecycle()
    // Keyed on the URL only, so the previous icon stays put while a refetch runs
    // rather than blinking back to the fallback glyph.
    var image by remember(pageUrl) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(pageUrl, enabled, generation) {
        if (!enabled || pageUrl.isBlank()) return@LaunchedEffect
        // A cache hit resolves before the next frame; a miss resolves whenever
        // the network does, and the badge simply keeps its fallback glyph.
        image = store.load(pageUrl)?.asImageBitmap()
    }
    return image
}
