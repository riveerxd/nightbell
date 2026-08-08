package me.river.nightbell.data.check

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.util.Log
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import me.river.nightbell.data.web.PickerScripts
import me.river.nightbell.domain.Assertions
import me.river.nightbell.domain.CheckResult
import me.river.nightbell.domain.ElementTarget
import me.river.nightbell.domain.FailureKind
import me.river.nightbell.domain.Monitor
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Runs website-element monitors by rendering the page in an off-screen
 * [WebView] and re-resolving the stored selector via injected JavaScript.
 *
 * Everything WebView-related must happen on the main thread, so the whole check
 * hops to [Dispatchers.Main] and is torn down in a `finally` to avoid leaking a
 * renderer process per check.
 */
class ElementChecker(
    private val context: Context,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class Located(
        val found: Boolean,
        val text: String = "",
        val strategy: String = "",
        val visible: Boolean = true,
        val attrValue: String = "",
        val html: String = "",
        val pageTitle: String = "",
        val nodeCount: Int = 0,
        val loadError: String = "",
    )

    /** One page load, N lookups. */
    data class PageResult(
        val results: List<Located> = emptyList(),
        val title: String = "",
        val nodeCount: Int = 0,
        val loadError: String = "",
    ) {
        val anyFound: Boolean get() = results.any { it.found }
    }

    suspend fun check(monitor: Monitor): CheckResult {
        val targets = monitor.targets
        if (targets.isEmpty()) {
            return CheckResult(
                ok = false,
                latencyMs = 0,
                failureKind = FailureKind.BAD_CONFIG,
                message = "No element captured yet",
                detail = "Open the preview and tap the element you want to watch.",
                at = nowMs(),
            )
        }
        val started = System.nanoTime()
        val page = locateAll(monitor.url, targets, monitor.timeoutSeconds)
        val latency = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0L)

        if (page == null) {
            return CheckResult(
                ok = false,
                latencyMs = latency,
                failureKind = FailureKind.TIMEOUT,
                message = "Page did not finish loading in ${monitor.timeoutSeconds}s",
                detail = "The embedded browser never reached a usable DOM.",
                at = nowMs(),
            )
        }
        if (page.loadError.isNotBlank()) {
            return CheckResult(
                ok = false,
                latencyMs = latency,
                failureKind = FailureKind.RENDER,
                message = "Page failed to load",
                detail = page.loadError,
                at = nowMs(),
            )
        }

        return evaluate(targets, page, latency)
    }

    /**
     * Folds one page load's worth of lookups into a single [CheckResult].
     * Pure apart from the clock, so the aggregation rules are testable.
     */
    internal fun evaluate(
        targets: List<ElementTarget>,
        page: PageResult,
        latencyMs: Long,
    ): CheckResult {
        val texts = mutableListOf<String>()
        val failures = mutableListOf<Pair<ElementTarget, Assertions.Verdict>>()
        var firstStrategy = ""

        targets.forEachIndexed { index, target ->
            val located = page.results.getOrElse(index) { Located(found = false) }
            val comparison = if (target.attribute.isNotBlank()) located.attrValue else located.text
            texts += comparison
            if (firstStrategy.isBlank()) firstStrategy = located.strategy
            val verdict = Assertions.checkElement(target, located.found && located.visible, comparison)
            if (!verdict.passed) failures += target to verdict
        }

        val ok = failures.isEmpty()
        // One failing element fails the whole check — a page monitor is a
        // conjunction, not a poll. The message names the first offender and
        // counts the rest so the notification stays one line.
        val headlineFailure = failures.firstOrNull()
        val message = when {
            ok && targets.size == 1 ->
                "Element matched via ${firstStrategy.ifBlank { "selector" }} in ${latencyMs}ms"
            ok -> "All ${targets.size} elements matched in ${latencyMs}ms"
            failures.size == 1 -> headlineFailure!!.second.message
            else -> "${headlineFailure!!.second.message} (+${failures.size - 1} more)"
        }

        return CheckResult(
            ok = ok,
            latencyMs = latencyMs,
            statusCode = 0,
            failureKind = headlineFailure?.second?.kind ?: FailureKind.NONE,
            message = message,
            detail = buildString {
                if (ok) {
                    append("Resolved through the \"${firstStrategy.ifBlank { "selector" }}\" strategy.")
                } else {
                    failures.forEach { (target, verdict) ->
                        append("• ${target.displayLabel}: ${verdict.message}\n")
                    }
                }
                if (page.title.isNotBlank()) append("\nPage: ${page.title}")
                if (page.nodeCount > 0) append("\nDOM nodes: ${page.nodeCount}")
                page.results.forEachIndexed { index, located ->
                    if (located.found && !located.visible) {
                        val label = targets.getOrNull(index)?.displayLabel ?: "element ${index + 1}"
                        append("\n$label exists but is not visible.")
                    }
                }
            }.trim(),
            bodyPreview = page.results.firstOrNull { it.html.isNotBlank() }?.html.orEmpty(),
            elementText = texts.firstOrNull().orEmpty(),
            elementTexts = texts,
            at = nowMs(),
        )
    }

    /** Public so the setup flow can dry-run a single capture before saving. */
    suspend fun locate(url: String, target: ElementTarget, timeoutSeconds: Int): Located? {
        val page = locateAll(url, listOf(target), timeoutSeconds) ?: return null
        val head = page.results.firstOrNull() ?: Located(found = false)
        return head.copy(
            pageTitle = page.title,
            nodeCount = page.nodeCount,
            loadError = page.loadError,
        )
    }

    /**
     * Renders [url] once and resolves every target against that one DOM.
     *
     * Polls after `onPageFinished` because SPAs hydrate well after the load
     * event; it stops early as soon as *all* targets resolve, and otherwise
     * returns the best attempt so a partially-rendered page still produces an
     * honest per-element verdict rather than a blanket timeout.
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun locateAll(
        url: String,
        targets: List<ElementTarget>,
        timeoutSeconds: Int,
    ): PageResult? = withContext(Dispatchers.Main) {
        if (targets.isEmpty()) return@withContext PageResult()
        var webView: WebView? = null
        try {
            withTimeoutOrNull(timeoutSeconds * 1000L + SETTLE_BUDGET_MS) {
                val errors = StringBuilder()
                val view = WebView(context).also { webView = it }
                configure(view)

                val pageDone = PageLatch()
                view.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        pageDone.complete()
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?,
                    ) {
                        if (request?.isForMainFrame == true) {
                            val description = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                error?.description?.toString() ?: "unknown"
                            } else {
                                "unknown"
                            }
                            errors.append("main frame: $description")
                            pageDone.complete()
                        }
                    }
                }
                // Give the renderer a real viewport; an unmeasured WebView can
                // skip layout for lazily-rendered content.
                view.measure(VIEWPORT_WIDTH, VIEWPORT_HEIGHT)
                view.layout(0, 0, VIEWPORT_WIDTH, VIEWPORT_HEIGHT)
                view.loadUrl(url)

                pageDone.await()

                val script = PickerScripts.locateMany(targets)
                var attempt = 0
                var best: PageResult? = null
                while (attempt < MAX_ATTEMPTS) {
                    delay(if (attempt == 0) FIRST_SETTLE_MS else RETRY_DELAY_MS)
                    val parsed = parsePage(view.evalJs(script), targets.size)
                    if (parsed != null) {
                        val foundCount = parsed.results.count { it.found }
                        val bestCount = best?.results?.count { it.found } ?: -1
                        if (foundCount > bestCount) best = parsed
                        if (foundCount == targets.size) {
                            return@withTimeoutOrNull parsed.copy(loadError = errors.toString())
                        }
                    }
                    attempt++
                }
                (best ?: PageResult(results = List(targets.size) { Located(found = false) }))
                    .copy(loadError = errors.toString())
            }
        } catch (cancellation: CancellationException) {
            // The page is not broken; we were interrupted. Turning this into a
            // `loadError` produced a "Page failed to load" verdict and a false
            // outage alert — the same bug as "Checker crashed", wearing a
            // different message. `withTimeoutOrNull` still swallows its *own*
            // timeout above, which is a real verdict about a real slow page.
            throw cancellation
        } catch (error: Throwable) {
            Log.e(TAG, "Element check threw", error)
            PageResult(
                results = List(targets.size) { Located(found = false) },
                loadError = error.message ?: error::class.java.simpleName,
            )
        } finally {
            webView?.let { view ->
                view.stopLoading()
                view.webViewClient = WebViewClient()
                view.loadUrl("about:blank")
                view.destroy()
            }
        }
    }

    private fun configure(view: WebView) {
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadsImagesAutomatically = false
            blockNetworkImage = true
            mediaPlaybackRequiresUserGesture = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            userAgentString = MOBILE_UA
            useWideViewPort = true
            loadWithOverviewMode = true
            javaScriptCanOpenWindowsAutomatically = false
        }
        view.setBackgroundColor(0)
        view.isVerticalScrollBarEnabled = false
        @Suppress("DEPRECATION")
        view.setLayerType(android.view.View.LAYER_TYPE_NONE, null)
    }

    /**
     * `evaluateJavascript` hands back a *JSON-encoded* value, so a script that
     * returns a JSON string arrives double-encoded. Unwrap one layer when it is
     * there, and fall back to the raw text when it isn't.
     */
    private fun unwrap(raw: String?): JsonObject? {
        if (raw.isNullOrBlank() || raw == "null") return null
        val unwrapped = runCatching { json.parseToJsonElement(raw).jsonPrimitive.content }
            .getOrElse { raw }
        return runCatching { json.parseToJsonElement(unwrapped) as? JsonObject }.getOrNull()
    }

    private fun located(obj: JsonObject): Located {
        fun str(key: String) = obj[key]?.jsonPrimitive?.content.orEmpty()
        fun bool(key: String, default: Boolean) = obj[key]?.jsonPrimitive?.booleanOrNull ?: default
        return Located(
            found = runCatching { obj["found"]!!.jsonPrimitive.boolean }.getOrDefault(false),
            text = str("text"),
            strategy = str("how"),
            visible = bool("visible", true),
            attrValue = str("attrValue"),
            html = str("html"),
            pageTitle = str("title"),
            nodeCount = str("nodes").toIntOrNull() ?: 0,
        )
    }

    private fun parsePage(raw: String?, expected: Int): PageResult? {
        val obj = unwrap(raw) ?: return null
        val array = obj["results"] as? JsonArray ?: return null
        val results = array.mapNotNull { (it as? JsonObject)?.let(::located) }
        // Pad defensively: a script that returned fewer entries than we asked
        // for must not silently shift every later target's verdict.
        val padded = if (results.size >= expected) {
            results.take(expected)
        } else {
            results + List(expected - results.size) { Located(found = false) }
        }
        return PageResult(
            results = padded,
            title = obj["title"]?.jsonPrimitive?.content.orEmpty(),
            nodeCount = obj["nodes"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
        )
    }

    /** One-shot latch that survives being completed more than once. */
    private class PageLatch {
        private var continuation: CancellableContinuation<Unit>? = null
        private var done = false

        fun complete() {
            if (done) return
            done = true
            continuation?.let { if (it.isActive) it.resume(Unit) }
            continuation = null
        }

        suspend fun await() {
            if (done) return
            suspendCancellableCoroutine { cont ->
                if (done) {
                    cont.resume(Unit)
                } else {
                    continuation = cont
                    cont.invokeOnCancellation { continuation = null }
                }
            }
        }
    }

    companion object {
        private const val TAG = "ElementChecker"
        private const val VIEWPORT_WIDTH = 1080
        private const val VIEWPORT_HEIGHT = 1920
        private const val FIRST_SETTLE_MS = 700L
        private const val RETRY_DELAY_MS = 900L
        private const val MAX_ATTEMPTS = 5
        private const val SETTLE_BUDGET_MS = 6_000L
        const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/125.0.0.0 Mobile Safari/537.36 NightbellMonitor/1.0"
    }
}

/** Suspending bridge over the callback-based [WebView.evaluateJavascript]. */
suspend fun WebView.evalJs(script: String): String? = suspendCancellableCoroutine { cont ->
    try {
        evaluateJavascript(script) { value -> if (cont.isActive) cont.resume(value) }
    } catch (error: Throwable) {
        if (cont.isActive) cont.resume(null)
    }
}
