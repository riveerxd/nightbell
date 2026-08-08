package me.river.nightbell.data.icons

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import me.river.nightbell.domain.runCatchingCancellable
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Site icons for page-element monitors, cached in memory and on disk.
 *
 * A page-element monitor is defined by *which page* it watches, so the site's
 * own mark identifies it far faster than a generic cursor glyph repeated down
 * the list.
 *
 * Caching is not an optimisation here, it is a requirement: the dashboard
 * recomposes constantly and a `LazyColumn` re-runs an item's effects every time
 * it scrolls back into view. Uncached, that is a network request per scroll, to
 * somebody else's server.
 *
 * Three layers, checked in order:
 *  1. **memory** — an [LruCache], so scrolling never touches disk;
 *  2. **disk** — `filesDir/favicons`, so a cold start never touches network;
 *  3. **network** — at most once per origin per [POSITIVE_TTL_MS], and once per
 *     [NEGATIVE_TTL_MS] for origins that turned out not to have one.
 *
 * Negative caching matters as much as positive: plenty of sites have no icon at
 * all, and without remembering the failure every scroll would retry it.
 */
class FaviconStore(
    context: Context,
    baseClient: OkHttpClient? = null,
    /**
     * Skips network while the device is offline — see
     * [me.river.nightbell.data.net.NetworkMonitor]. A failure to reach the
     * site is deliberately *not* cached as "no icon" in that case, otherwise one
     * tunnel would blank every badge for days.
     */
    private val isOnline: () -> Boolean = { true },
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    private val dir = File(context.filesDir, "favicons")

    private val memory = object : LruCache<String, Bitmap>(MEMORY_ENTRIES) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    /** Origins with no usable icon, and when we concluded that. */
    private val misses = ConcurrentHashMap<String, Long>()

    /**
     * One lock per origin. Several cards can watch the same site, and they all
     * compose at once — without this they would each fire the same request.
     */
    private val locks = ConcurrentHashMap<String, Mutex>()

    private val generations = MutableStateFlow(0)

    /**
     * Bumped whenever cached icons are deliberately thrown away.
     *
     * A composable that has already resolved an icon holds it for the life of its
     * composition, so a purge is invisible without something to re-ask on — see
     * [me.river.nightbell.ui.components.rememberFavicon].
     */
    val generation: StateFlow<Int> = generations.asStateFlow()

    private val client: OkHttpClient = (baseClient ?: OkHttpClient())
        .newBuilder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    /**
     * The site icon for [pageUrl], or null when there isn't one we can render.
     *
     * Cheap and safe to call from a composition effect: repeat calls for the
     * same origin hit memory, and concurrent ones coalesce behind a lock.
     */
    suspend fun load(pageUrl: String): Bitmap? {
        val origin = originOf(pageUrl) ?: return null
        val key = keyFor(origin)

        memory.get(key)?.let { return it }

        return locks.getOrPut(key) { Mutex() }.withLock {
            // Re-check inside the lock: whoever we queued behind has very likely
            // just populated it.
            memory.get(key)?.let { return@withLock it }
            withContext(Dispatchers.IO) { resolve(origin, key) }
        }
    }

    /**
     * Ignores every cache layer and fetches the icons for [pageUrls] again.
     *
     * The TTLs above are long on purpose: a site's mark almost never changes, and
     * revalidating one per scroll would be rude to somebody else's server. That
     * trade is wrong exactly once — the day a site *does* change its icon, when
     * the app will happily show the old one for a month. This is the way out, and
     * the only path here that goes to the network for an icon it already has.
     *
     * A cached file is *expired* rather than deleted, so a fetch that fails
     * leaves the previous icon showing instead of blanking the badge.
     */
    suspend fun refetch(pageUrls: List<String>): Refetch {
        // Everything on one site shares an icon, so two monitors on the same host
        // are one fetch, not two.
        val origins = pageUrls.mapNotNull { originOf(it) }.distinctBy { keyFor(it) }
        var changed = 0
        for (origin in origins) {
            val key = keyFor(origin)
            val file = File(dir, "$key.png")
            val fresh = locks.getOrPut(key) { Mutex() }.withLock {
                memory.remove(key)
                misses.remove(key)
                withContext(Dispatchers.IO) {
                    val before = fingerprint(file)
                    expire(file)
                    // The negative cache lasts three days. A human asking for this
                    // outranks a conclusion we drew before they did.
                    runCatching { missMarker(key).delete() }
                    resolve(origin, key)
                    fingerprint(file) != before
                }
            }
            if (fresh) changed++
        }
        generations.update { it + 1 }
        return Refetch(sites = origins.size, changed = changed)
    }

    /** What a [refetch] did, so the caller has something honest to report. */
    data class Refetch(val sites: Int, val changed: Int)

    /** Marks a cached icon stale without giving up its value as a fallback. */
    private fun expire(file: File) {
        if (!file.isFile) return
        if (runCatching { file.setLastModified(0) }.getOrDefault(false)) return
        // Some filesystems refuse the timestamp. Dropping the file is then the
        // only way to force a refetch, and the stale fallback is what we lose.
        runCatching { file.delete() }
    }

    /** "Is this still the same icon?" — a digest of the cached bytes, or null if none. */
    private fun fingerprint(file: File): String? {
        if (!file.isFile) return null
        return runCatching {
            MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                .joinToString("") { "%02x".format(it) }
        }.getOrNull()
    }

    private fun resolve(origin: HttpUrl, key: String): Bitmap? {
        val file = File(dir, "$key.png")
        if (file.isFile && nowMs() - file.lastModified() < POSITIVE_TTL_MS) {
            decodeFile(file)?.let {
                memory.put(key, it)
                return it
            }
            // Unreadable cache entry: drop it and fall through to a refetch.
            file.delete()
        }

        val missAt = misses[key] ?: missMarker(key).takeIf { it.isFile }?.lastModified()
        if (missAt != null && nowMs() - missAt < NEGATIVE_TTL_MS) {
            misses[key] = missAt
            return null
        }

        if (!isOnline()) return staleFallback(file, key)

        val bitmap = candidates(origin).firstNotNullOfOrNull { candidate ->
            // Cancellation-aware: swallowing it here would move on to the next
            // candidate on a dead coroutine and then cache a negative result for a
            // fetch that never actually failed.
            runCatchingCancellable { fetch(candidate) }.getOrNull()
        }

        if (bitmap == null) {
            // Serve an expired icon rather than nothing — a site's mark rarely
            // changes, and a blank badge is worse than a slightly old one.
            staleFallback(file, key)?.let { return it }
            markMiss(key)
            return null
        }

        val scaled = downscale(bitmap)
        runCatching { write(file, scaled) }
            .onFailure { Log.w(TAG, "Could not cache icon for ${origin.host}", it) }
        misses.remove(key)
        runCatching { missMarker(key).delete() }
        memory.put(key, scaled)
        return scaled
    }

    /** A cached icon past its TTL. Better than empty, and it stops a retry storm. */
    private fun staleFallback(file: File, key: String): Bitmap? {
        if (!file.isFile) return null
        return decodeFile(file)?.also { memory.put(key, it) }
    }

    /**
     * Where to look, best first.
     *
     * The page's own `<link rel="icon">` comes first because it is the only
     * authoritative answer — plenty of sites serve an unrelated placeholder, or
     * an HTML error page, at `/favicon.ico`.
     */
    private fun candidates(origin: HttpUrl): List<HttpUrl> {
        val declared = runCatchingCancellable { declaredIcons(origin) }.getOrDefault(emptyList())
        val wellKnown = listOfNotNull(
            origin.resolve("/favicon.ico"),
            origin.resolve("/apple-touch-icon.png"),
            origin.resolve("/favicon.png"),
        )
        return (declared + wellKnown).distinct()
    }

    /** Scrapes `<link rel="…icon…" href="…">` out of the page head. */
    private fun declaredIcons(origin: HttpUrl): List<HttpUrl> {
        val html = get(origin)?.let { String(it, Charsets.UTF_8) } ?: return emptyList()
        // Only the head matters, and stopping there keeps the regex off a
        // megabyte of body markup.
        val head = html.substringBefore("</head>", html).take(HEAD_LIMIT)
        return LINK_TAG.findAll(head)
            .mapNotNull { match ->
                val attrs = match.groupValues[1]
                val rel = ATTR_REL.find(attrs)?.groupValues?.get(2)?.lowercase() ?: return@mapNotNull null
                if (!rel.contains("icon")) return@mapNotNull null
                val href = ATTR_HREF.find(attrs)?.groupValues?.get(2)?.trim() ?: return@mapNotNull null
                if (href.isBlank() || href.startsWith("data:")) return@mapNotNull null
                // SVG is not something BitmapFactory can ever decode.
                if (href.substringBefore('?').endsWith(".svg", ignoreCase = true)) return@mapNotNull null
                val resolved = origin.resolve(href) ?: return@mapNotNull null
                // Bigger is better: these get downscaled, and upscaling a 16px
                // icon into a 42dp badge looks like a mistake.
                val score = ATTR_SIZES.find(attrs)?.groupValues?.get(2)
                    ?.substringBefore('x')?.toIntOrNull() ?: 0
                val bonus = if (rel.contains("apple-touch")) 180 else 0
                resolved to score + bonus
            }
            .sortedByDescending { it.second }
            .map { it.first }
            .toList()
    }

    private fun fetch(url: HttpUrl): Bitmap? = get(url)?.let { decodeIcon(it) }

    private fun get(url: HttpUrl): ByteArray? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body ?: return null
            // Hard cap: this is somebody else's server and we only need an icon.
            val bytes = body.source().let { source ->
                source.request(MAX_BYTES + 1)
                source.buffer.snapshot().toByteArray()
            }
            return if (bytes.size > MAX_BYTES) null else bytes
        }
    }

    /**
     * Decodes an icon, unwrapping `.ico` first.
     *
     * **[BitmapFactory] cannot decode ICO at all** — which is the whole problem
     * with favicons, since `/favicon.ico` is the one path every site has. Modern
     * ICO files are containers whose entries are usually complete PNGs, so the
     * directory is parsed and the largest PNG entry handed to the decoder.
     * Entries in the older DIB encoding are skipped rather than half-supported:
     * reconstructing a BMP header and its AND mask is a lot of fiddly code for
     * icons that a `<link rel="icon">` almost always supersedes anyway.
     */
    private fun decodeIcon(bytes: ByteArray): Bitmap? {
        if (isIco(bytes)) {
            unwrapIco(bytes)?.let { inner ->
                BitmapFactory.decodeByteArray(inner, 0, inner.size)?.let { return it }
            }
            return null
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun isIco(bytes: ByteArray): Boolean =
        bytes.size > ICO_HEADER && bytes[0] == 0.toByte() && bytes[1] == 0.toByte() &&
            bytes[2] == 1.toByte() && bytes[3] == 0.toByte()

    private fun unwrapIco(bytes: ByteArray): ByteArray? {
        val count = le16(bytes, 4)
        if (count <= 0) return null
        var best: ByteArray? = null
        var bestWidth = -1
        for (i in 0 until count) {
            val entry = ICO_HEADER + i * ICO_ENTRY
            if (entry + ICO_ENTRY > bytes.size) break
            // A stored 0 means 256 — the field is a single byte.
            val width = (bytes[entry].toInt() and 0xFF).let { if (it == 0) 256 else it }
            val size = le32(bytes, entry + 8)
            val offset = le32(bytes, entry + 12)
            if (size <= 0 || offset < 0 || offset + size > bytes.size) continue
            val slice = bytes.copyOfRange(offset, offset + size)
            if (!isPng(slice)) continue
            if (width > bestWidth) {
                bestWidth = width
                best = slice
            }
        }
        return best
    }

    private fun isPng(bytes: ByteArray): Boolean =
        bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte()

    private fun le16(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    private fun le32(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or
            ((bytes[at + 3].toInt() and 0xFF) shl 24)

    /** Caps what we hold in memory; a 512px apple-touch-icon in a 42dp badge is waste. */
    private fun downscale(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= TARGET_PX || longest <= 0) return bitmap
        val ratio = TARGET_PX.toFloat() / longest
        val width = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val height = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return runCatching { Bitmap.createScaledBitmap(bitmap, width, height, true) }
            .getOrDefault(bitmap)
    }

    private fun write(file: File, bitmap: Bitmap) {
        dir.mkdirs()
        // Via a temp file: a process death mid-write would otherwise leave a
        // truncated PNG that decodes to null on every future launch.
        val temp = File(dir, "${file.name}.tmp")
        temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (!temp.renameTo(file)) {
            temp.delete()
        }
    }

    private fun decodeFile(file: File): Bitmap? =
        runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()

    private fun markMiss(key: String) {
        misses[key] = nowMs()
        runCatching {
            dir.mkdirs()
            missMarker(key).writeBytes(ByteArray(0))
        }
    }

    private fun missMarker(key: String) = File(dir, "$key.miss")

    /** Everything on one site shares an icon, so the origin is the cache key. */
    private fun originOf(pageUrl: String): HttpUrl? {
        val url = pageUrl.trim().let {
            if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it"
        }
        return url.toHttpUrlOrNull()?.newBuilder()?.encodedPath("/")?.query(null)?.fragment(null)?.build()
    }

    private fun keyFor(origin: HttpUrl): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${origin.scheme}://${origin.host}:${origin.port}".toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    /** Test seam: forget everything, including on disk. */
    fun clear() {
        memory.evictAll()
        misses.clear()
        runCatching { dir.deleteRecursively() }
        generations.update { it + 1 }
    }

    private companion object {
        const val TAG = "FaviconStore"
        const val MEMORY_ENTRIES = 48
        const val TARGET_PX = 96
        const val MAX_BYTES = 512L * 1024
        const val HEAD_LIMIT = 64 * 1024
        const val ICO_HEADER = 6
        const val ICO_ENTRY = 16
        val POSITIVE_TTL_MS = TimeUnit.DAYS.toMillis(30)
        val NEGATIVE_TTL_MS = TimeUnit.DAYS.toMillis(3)
        const val USER_AGENT = "Nightbell-Monitor/1.0 (+favicon)"

        val LINK_TAG = Regex("""<link\s+([^>]*)>""", RegexOption.IGNORE_CASE)
        val ATTR_REL = Regex("""rel\s*=\s*(["'])(.*?)\1""", RegexOption.IGNORE_CASE)
        val ATTR_HREF = Regex("""href\s*=\s*(["'])(.*?)\1""", RegexOption.IGNORE_CASE)
        val ATTR_SIZES = Regex("""sizes\s*=\s*(["'])(.*?)\1""", RegexOption.IGNORE_CASE)
    }
}
