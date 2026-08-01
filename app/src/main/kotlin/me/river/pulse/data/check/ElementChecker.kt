package me.river.pulse.data.check

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.util.Log
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import me.river.pulse.data.web.PickerScripts
import me.river.pulse.domain.Assertions
import me.river.pulse.domain.CheckResult
import me.river.pulse.domain.ElementTarget
import me.river.pulse.domain.FailureKind
import me.river.pulse.domain.Monitor
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
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

    suspend fun check(monitor: Monitor): CheckResult {
        val target = monitor.element
        if (target == null || !target.isCaptured) {
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
        val located = locate(monitor.url, target, monitor.timeoutSeconds)
        val latency = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0L)

        if (located == null) {
            return CheckResult(
                ok = false,
                latencyMs = latency,
                failureKind = FailureKind.TIMEOUT,
                message = "Page did not finish loading in ${monitor.timeoutSeconds}s",
                detail = "The embedded browser never reached a usable DOM.",
                at = nowMs(),
            )
        }
        if (located.loadError.isNotBlank()) {
            return CheckResult(
                ok = false,
                latencyMs = latency,
                failureKind = FailureKind.RENDER,
                message = "Page failed to load",
                detail = located.loadError,
                at = nowMs(),
            )
        }

        val comparisonText = if (target.attribute.isNotBlank()) located.attrValue else located.text
        val verdict = Assertions.checkElement(target, located.found && located.visible, comparisonText)
        return CheckResult(
            ok = verdict.passed,
            latencyMs = latency,
            statusCode = 0,
            failureKind = verdict.kind,
            message = if (verdict.passed) {
                "Element matched via ${located.strategy.ifBlank { "selector" }} in ${latency}ms"
            } else {
                verdict.message
            },
            detail = buildString {
                append(verdict.detail.ifBlank { "Resolved through the \"${located.strategy}\" strategy." })
                if (located.pageTitle.isNotBlank()) append("\nPage: ${located.pageTitle}")
                if (located.nodeCount > 0) append("\nDOM nodes: ${located.nodeCount}")
                if (located.found && !located.visible) append("\nNode exists but is not visible.")
            },
            bodyPreview = located.html,
            elementText = comparisonText,
            at = nowMs(),
        )
    }

    /** Public so the setup flow can dry-run a capture before saving. */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun locate(url: String, target: ElementTarget, timeoutSeconds: Int): Located? =
        withContext(Dispatchers.Main) {
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

                    // Poll: SPAs finish onPageFinished long before hydration lands.
                    var attempt = 0
                    var last: Located? = null
                    while (attempt < MAX_ATTEMPTS) {
                        delay(if (attempt == 0) FIRST_SETTLE_MS else RETRY_DELAY_MS)
                        val raw = view.evalJs(PickerScripts.locate(target))
                        last = parseLocated(raw)?.copy(loadError = errors.toString())
                        if (last?.found == true) return@withTimeoutOrNull last
                        attempt++
                    }
                    last ?: Located(found = false, loadError = errors.toString())
                }
            } catch (error: Throwable) {
                Log.e(TAG, "Element check crashed", error)
                Located(found = false, loadError = error.message ?: error::class.java.simpleName)
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

    private fun parseLocated(raw: String?): Located? {
        if (raw.isNullOrBlank() || raw == "null") return null
        val unwrapped = runCatching { json.parseToJsonElement(raw).jsonPrimitive.content }
            .getOrElse { raw }
        val obj = runCatching { json.parseToJsonElement(unwrapped) as? JsonObject }.getOrNull() ?: return null
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
                "Chrome/125.0.0.0 Mobile Safari/537.36 PulseMonitor/1.0"
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
