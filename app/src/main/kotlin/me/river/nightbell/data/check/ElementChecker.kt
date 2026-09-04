package me.river.nightbell.data.check

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.util.Log
import android.net.http.SslCertificate
import android.webkit.CookieManager
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import me.river.nightbell.data.diag.Diag
import me.river.nightbell.data.web.PickerScripts
import me.river.nightbell.domain.Assertions
import me.river.nightbell.domain.BrowserState
import me.river.nightbell.domain.CheckResult
import me.river.nightbell.domain.ElementTarget
import me.river.nightbell.domain.FailureKind
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.LoadStage
import me.river.nightbell.domain.LogEvent
import me.river.nightbell.domain.LogField
import me.river.nightbell.domain.PageExpiry
import me.river.nightbell.domain.ProxyRoute
import me.river.nightbell.domain.TlsTrust
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
    /**
     * The settings a check is judged against, read per check. Mirrors
     * [HttpChecker] so both kinds of monitor agree about where traffic goes.
     */
    private val settingsFor: () -> GlobalSettings = { GlobalSettings() },
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
        /**
         * The words on the control that appears to be standing over the page,
         * when a lookup failed and one was found. See [PickerScripts.GATE_PROBE].
         */
        val gateLabel: String = "",
        /**
         * Pin of the certificate the page presented, when it was readable.
         *
         * Only ever non-empty on a load where the WebView objected to the
         * certificate, because that is the one moment Chromium hands one over. That
         * is not a gap for the mode that needs it: a self-signed certificate always
         * objects.
         */
        val certSpki: String = "",
    ) {
        val anyFound: Boolean get() = results.any { it.found }
    }

    suspend fun check(monitor: Monitor, certPin: String = ""): CheckResult {
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
        val settings = settingsFor()
        val route = ProxyRoute.forMonitor(monitor, settings)
        // Refused rather than downgraded, exactly as in HttpChecker: a page
        // element on a hidden service must not be fetched in the clear because
        // the proxy happens to be misconfigured.
        if (route is ProxyRoute.Route.Unconfigured) {
            return CheckResult(
                ok = false,
                latencyMs = 0,
                failureKind = FailureKind.BAD_CONFIG,
                message = "No proxy to route through",
                detail = "This monitor is set to use a SOCKS5 proxy and no usable address is " +
                    "configured, so the page was not loaded. Set one in Settings, or on the " +
                    "monitor itself, or turn routing off.",
                at = nowMs(),
            )
        }
        val endpoint = (route as? ProxyRoute.Route.Via)?.endpoint
        val timeout = monitor.effectiveTimeoutSeconds(settings, proxied = endpoint != null)

        val started = System.nanoTime()
        var expiry: PageExpiry? = null
        val record: (PageExpiry) -> Unit = { expiry = it }
        val page = try {
            if (endpoint == null) {
                locateAll(
                    monitor.url,
                    targets,
                    timeout,
                    monitor.tlsTrust,
                    certPin,
                    monitor.browserState,
                    record,
                )
            } else {
                WebViewProxy.routed(endpoint) {
                    locateAll(
                        monitor.url,
                        targets,
                        timeout,
                        monitor.tlsTrust,
                        certPin,
                        monitor.browserState,
                        record,
                    )
                }
            }
        } catch (unavailable: WebViewProxy.Unavailable) {
            return CheckResult(
                ok = false,
                latencyMs = 0,
                failureKind = FailureKind.BAD_CONFIG,
                message = unavailable.headline,
                detail = unavailable.message.orEmpty(),
                at = nowMs(),
            )
        }
        val latency = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0L)

        if (page == null) {
            // Named by the stage that ran out rather than by one sentence for
            // every way of running out. The old message claimed the page had not
            // finished loading even when it had loaded and the element was what
            // never arrived, and that claim sent at least one reporter looking
            // in the wrong place. See PageExpiry.
            val stalled = expiry
            return CheckResult(
                ok = false,
                latencyMs = latency,
                failureKind = FailureKind.TIMEOUT,
                message = stalled?.headline(timeout)
                    ?: "Page did not finish loading in ${timeout}s",
                detail = stalled?.detail()
                    ?: "The embedded browser never reached a usable DOM.",
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

        return evaluate(targets, page, latency).copy(certSpki = page.certSpki)
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
                    // The difference between "your element is gone" and "we never
                    // got to see your element" is the whole of issue #8, and the
                    // person reading this at 3am cannot tell them apart from a
                    // selector. What the page is showing instead can.
                    if (page.gateLabel.isNotBlank()) {
                        append(
                            "\nThe page is showing a “${page.gateLabel}” button over its " +
                                "content, so it may be asking to be let in again. Open the live " +
                                "preview, press through it, and pick the element once more.\n",
                        )
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

    /**
     * The `sha256/…` pin of the certificate an [SslError] is about, or empty.
     *
     * `SslCertificate.getX509Certificate` arrived in API 29 and this app supports
     * 26, so on the two oldest releases there is nothing to compare and the caller
     * says so rather than inventing a comparison.
     */
    private fun webViewLeafPin(error: SslError?): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return ""
        val certificate: SslCertificate = error?.certificate ?: return ""
        val x509 = runCatching { certificate.x509Certificate }.getOrNull() ?: return ""
        return runCatching { TlsTrustConfig.pinOf(x509) }.getOrDefault("")
    }

    /** Which of Chromium's certificate complaints this was, in words. */
    private fun sslErrorText(error: SslError?): String = when (error?.primaryError) {
        SslError.SSL_UNTRUSTED -> "no CA this phone trusts signed it"
        SslError.SSL_EXPIRED -> "it has expired"
        SslError.SSL_NOTYETVALID -> "it is not valid yet"
        SslError.SSL_IDMISMATCH -> "it was issued for a different hostname"
        SslError.SSL_DATE_INVALID -> "its dates are invalid"
        SslError.SSL_INVALID -> "it is malformed"
        else -> "reason unknown"
    }

    /** Public so the setup flow can dry-run a single capture before saving. */
    suspend fun locate(
        url: String,
        target: ElementTarget,
        timeoutSeconds: Int,
        tlsTrust: TlsTrust = TlsTrust.SYSTEM,
    ): Located? {
        val page = locateAll(url, listOf(target), timeoutSeconds, tlsTrust) ?: return null
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
        tlsTrust: TlsTrust = TlsTrust.SYSTEM,
        pin: String = "",
        /**
         * What the picker was carrying when this monitor was set up. Replayed
         * before the load so a page behind a gate shows the same thing it showed
         * the person who chose the element. See [BrowserState].
         */
        state: BrowserState = BrowserState(),
        onExpiry: (PageExpiry) -> Unit = {},
    ): PageResult? = withContext(Dispatchers.Main) {
        if (targets.isEmpty()) return@withContext PageResult()
        var webView: WebView? = null
        // Declared out here so the expiry snapshot survives the block being
        // cancelled. `withTimeoutOrNull` discards everything inside it, and what
        // was true at the moment it gave up is the whole diagnostic.
        val trace = LoadTrace(startedAtMs = System.currentTimeMillis())
        try {
            val outcome = withTimeoutOrNull(timeoutSeconds * 1000L + SETTLE_BUDGET_MS) {
                val errors = StringBuilder()
                val view = WebView(context).also { webView = it }
                configure(view)
                Diag.log(
                    LogEvent.PAGE_LOAD_START,
                    LogField.route("url", url),
                    LogField.of("timeout_s", timeoutSeconds),
                    LogField.of("targets", targets.size),
                    LogField.of("trust", tlsTrust),
                    LogField.of("session", state.appliesTo(url)),
                )

                // Reassigned by the storage fallback below, so the client has to
                // close over the variable rather than over the first latch.
                var pageDone = PageLatch()
                // What the page's certificate hashed to, when the WebView objected
                // to it and therefore let us see it. Only ever set on the path that
                // can read a real certificate; see onReceivedSslError.
                var presentedPin = ""
                // Progress and console output are only observable through a chrome
                // client, and the checker never had one. That is why issue 8 could
                // not be diagnosed: the picker has one, so the preview could be
                // watched while the check itself was a black box.
                view.webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        trace.progress = newProgress
                        // Every decile at most. A page reports progress dozens of
                        // times and a log nobody can read is not a log.
                        if (newProgress / 10 > trace.loggedProgress / 10 || newProgress == 100) {
                            trace.loggedProgress = newProgress
                            Diag.log(LogEvent.PAGE_PROGRESS, LogField.of("percent", newProgress))
                        }
                    }

                    override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                        val level = message?.messageLevel() ?: return false
                        if (level != ConsoleMessage.MessageLevel.ERROR &&
                            level != ConsoleMessage.MessageLevel.WARNING
                        ) {
                            return false
                        }
                        if (level == ConsoleMessage.MessageLevel.ERROR) trace.consoleErrors++
                        // Capped, because one broken script in a loop would
                        // otherwise be the whole file.
                        if (trace.consoleLogged < CONSOLE_LINE_CAP) {
                            trace.consoleLogged++
                            Diag.log(
                                LogEvent.PAGE_CONSOLE,
                                LogField.of("level", level),
                                LogField.text("text", message.message().orEmpty()),
                                LogField.of("line", message.lineNumber()),
                            )
                        }
                        return false
                    }
                }
                view.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(
                        view: WebView?,
                        url: String?,
                        favicon: android.graphics.Bitmap?,
                    ) {
                        trace.stage = if (trace.stage == LoadStage.RELOADING) {
                            LoadStage.RELOADING
                        } else {
                            LoadStage.LOADING
                        }
                    }

                    /**
                     * Counted, not intercepted.
                     *
                     * Returning null leaves the WebView to fetch the resource
                     * itself, so this costs a counter and changes nothing about
                     * the load. Counting completions would mean handling every
                     * response in this process, which is a different feature
                     * with a different risk profile.
                     */
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): WebResourceResponse? {
                        trace.requestsStarted++
                        return null
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        trace.pageFinished = true
                        Diag.log(
                            LogEvent.PAGE_FINISHED,
                            LogField.ms("after", System.currentTimeMillis() - trace.startedAtMs),
                            LogField.of("requests", trace.requestsStarted),
                        )
                        pageDone.complete()
                    }

                    override fun onReceivedHttpError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        errorResponse: WebResourceResponse?,
                    ) {
                        val main = request?.isForMainFrame == true
                        if (!main) trace.resourceErrors++
                        Diag.log(
                            LogEvent.PAGE_HTTP_ERROR,
                            LogField.of("status", errorResponse?.statusCode ?: 0),
                            LogField.of("main_frame", main),
                            LogField.host("host", request?.url?.toString().orEmpty()),
                        )
                    }

                    /**
                     * A certificate the WebView will not accept on its own.
                     *
                     * Default behaviour is `handler.cancel()`, which is why a page
                     * monitor on a self-signed host failed with a bare load error
                     * and no mention of a certificate anywhere. The three trust
                     * modes have to mean the same thing here as they do for a
                     * status check, or turning one on would fix one kind of monitor
                     * and silently not the other.
                     *
                     * Chromium hands over an `SslCertificate` rather than the X.509
                     * chain, so the key cannot be compared to a pin from here. On
                     * API 29 and up the real certificate is reachable and the pin is
                     * enforced; below that PINNED accepts the page and the message
                     * says so, rather than pretending a check happened.
                     */
                    override fun onReceivedSslError(
                        view: WebView?,
                        handler: SslErrorHandler?,
                        error: SslError?,
                    ) {
                        Diag.log(
                            LogEvent.PAGE_SSL_ERROR,
                            LogField.of("code", error?.primaryError ?: -1),
                            LogField.of("trust", tlsTrust),
                            LogField.present("pin", pin),
                        )
                        when (tlsTrust) {
                            TlsTrust.SYSTEM -> {
                                errors.append(
                                    "main frame: the certificate was refused (" +
                                        sslErrorText(error) + "). Set this monitor's " +
                                        "certificate handling if this server is one you know.",
                                )
                                handler?.cancel()
                                pageDone.complete()
                            }

                            TlsTrust.ANY -> {
                                val presented = webViewLeafPin(error)
                                if (presented.isNotBlank()) presentedPin = presented
                                handler?.proceed()
                            }

                            TlsTrust.PINNED -> {
                                val presented = webViewLeafPin(error)
                                if (presented.isNotBlank()) presentedPin = presented
                                when {
                                    pin.isBlank() -> handler?.proceed()
                                    presented.isBlank() -> handler?.proceed()
                                    presented == pin -> handler?.proceed()
                                    else -> {
                                        errors.append(
                                            "main frame: the certificate key changed. " +
                                                "Expected $pin, received $presented.",
                                        )
                                        handler?.cancel()
                                        pageDone.complete()
                                    }
                                }
                            }
                        }
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?,
                    ) {
                        val main = request?.isForMainFrame == true
                        val description = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            error?.description?.toString() ?: "unknown"
                        } else {
                            "unknown"
                        }
                        if (!main) trace.resourceErrors++
                        // A subresource failure was invisible before, and it is
                        // the most likely reason a load event never arrives: the
                        // page is waiting on something that will not answer.
                        if (main || trace.resourceErrorsLogged < RESOURCE_ERROR_CAP) {
                            if (!main) trace.resourceErrorsLogged++
                            Diag.log(
                                LogEvent.PAGE_RESOURCE_ERROR,
                                LogField.of("main_frame", main),
                                LogField.text("why", description),
                                LogField.host("host", request?.url?.toString().orEmpty()),
                            )
                        }
                        if (main) {
                            errors.append("main frame: $description")
                            pageDone.complete()
                        }
                    }
                }
                // Give the renderer a real viewport; an unmeasured WebView can
                // skip layout for lazily-rendered content.
                view.measure(VIEWPORT_WIDTH, VIEWPORT_HEIGHT)
                view.layout(0, 0, VIEWPORT_WIDTH, VIEWPORT_HEIGHT)

                Diag.log(
                    LogEvent.PAGE_CONFIG,
                    LogField.tag("cache", "no_cache"),
                    LogField.of("images", false),
                    LogField.of("js", true),
                    LogField.of("viewport_w", VIEWPORT_WIDTH),
                    LogField.of("viewport_h", VIEWPORT_HEIGHT),
                )
                val applies = state.appliesTo(url)
                if (applies) seedCookies(url, state.cookies)
                // Storage has to exist before the page's own scripts read it, and
                // the only way to guarantee that is a document-start script. Where
                // the WebView is too old to have them, the page runs once seeing
                // nothing, gets the storage, and is loaded again. One extra load
                // on an old device beats a gate that never opens.
                val seededEarly = applies && state.localStorage.isNotBlank() &&
                    seedStorageAtDocumentStart(view, state)
                if (applies) {
                    Diag.log(
                        LogEvent.PAGE_SEED,
                        LogField.secret("cookies", state.cookies),
                        LogField.secret("storage", state.localStorage),
                        LogField.of("at_document_start", seededEarly),
                    )
                }
                trace.stage = LoadStage.NAVIGATING
                view.loadUrl(url)
                pageDone.await()

                if (applies && state.localStorage.isNotBlank() && !seededEarly) {
                    view.evalJs(PickerScripts.seedStorage(state.localStorage))
                    pageDone = PageLatch()
                    trace.stage = LoadStage.RELOADING
                    trace.pageFinished = false
                    view.reload()
                    pageDone.await()
                }
                trace.readyState = readyState(view)
                Diag.log(
                    LogEvent.PAGE_READY_STATE,
                    LogField.tag("state", trace.readyState),
                    LogField.ms("after", System.currentTimeMillis() - trace.startedAtMs),
                )

                val script = PickerScripts.locateMany(targets)
                var attempt = 0
                var best: PageResult? = null
                trace.stage = LoadStage.POLLING
                while (attempt < MAX_ATTEMPTS) {
                    delay(if (attempt == 0) FIRST_SETTLE_MS else RETRY_DELAY_MS)
                    val parsed = parsePage(view.evalJs(script), targets.size)
                    if (parsed != null) {
                        val foundCount = parsed.results.count { it.found }
                        val bestCount = best?.results?.count { it.found } ?: -1
                        if (foundCount > bestCount) best = parsed
                        Diag.log(
                            LogEvent.PAGE_POLL,
                            LogField.of("attempt", attempt + 1),
                            LogField.of("found", foundCount),
                            LogField.of("of", targets.size),
                            LogField.of("nodes", parsed.nodeCount),
                        )
                        if (foundCount == targets.size) {
                            Diag.log(
                                LogEvent.PAGE_DONE,
                                LogField.of("found", foundCount),
                                LogField.of("of", targets.size),
                                LogField.ms("total", System.currentTimeMillis() - trace.startedAtMs),
                                LogField.of("requests", trace.requestsStarted),
                            )
                            return@withTimeoutOrNull parsed.copy(
                                loadError = errors.toString(),
                                certSpki = presentedPin,
                            )
                        }
                    }
                    attempt++
                }
                // Only asked once the page has had every chance to produce the
                // elements, because the answer is only interesting as an
                // explanation for their absence.
                trace.stage = LoadStage.GATE_PROBE
                val gate = gateLabel(view)
                if (gate.isNotBlank()) Diag.log(LogEvent.PAGE_GATE, LogField.text("label", gate))
                Diag.log(
                    LogEvent.PAGE_DONE,
                    LogField.of("found", best?.results?.count { it.found } ?: 0),
                    LogField.of("of", targets.size),
                    LogField.ms("total", System.currentTimeMillis() - trace.startedAtMs),
                    LogField.of("requests", trace.requestsStarted),
                    LogField.of("resource_errors", trace.resourceErrors),
                )
                (best ?: PageResult(results = List(targets.size) { Located(found = false) }))
                    .copy(loadError = errors.toString(), certSpki = presentedPin, gateLabel = gate)
            }
            if (outcome == null) {
                // The one place the expiry is observable. Reading the document
                // one last time is worth the half second: whether it says
                // "interactive" or "complete" is the difference between a page
                // that is stuck waiting on a request and a page whose load event
                // simply never fired, and no other field distinguishes them.
                val ready = webView?.let { view ->
                    withTimeoutOrNull(EXPIRY_PROBE_MS) { readyState(view) }
                }.orEmpty()
                val expiry = PageExpiry(
                    stage = trace.stage,
                    progress = trace.progress,
                    readyState = ready.ifBlank { trace.readyState },
                    pageFinished = trace.pageFinished,
                    requestsStarted = trace.requestsStarted,
                    resourceErrors = trace.resourceErrors,
                    consoleErrors = trace.consoleErrors,
                    elapsedMs = System.currentTimeMillis() - trace.startedAtMs,
                )
                Diag.log(
                    LogEvent.PAGE_EXPIRED,
                    LogField.of("stage", expiry.stage),
                    LogField.of("percent", expiry.progress),
                    LogField.tag("ready", expiry.readyState),
                    LogField.of("load_event", expiry.pageFinished),
                    LogField.of("requests", expiry.requestsStarted),
                    LogField.of("resource_errors", expiry.resourceErrors),
                    LogField.of("console_errors", expiry.consoleErrors),
                    LogField.ms("elapsed", expiry.elapsedMs),
                )
                onExpiry(expiry)
            }
            outcome
        } catch (cancellation: CancellationException) {
            // The page is not broken; we were interrupted. Turning this into a
            // `loadError` produced a "Page failed to load" verdict and a false
            // outage alert — the same bug as "Checker crashed", wearing a
            // different message. `withTimeoutOrNull` still swallows its *own*
            // timeout above, which is a real verdict about a real slow page.
            throw cancellation
        } catch (error: Throwable) {
            Diag.logError(LogEvent.PAGE_THREW, error, LogField.of("stage", trace.stage))
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

    /**
     * Puts the captured cookies back in the shared store before the load.
     *
     * `Path=/` is forced. What was captured is a site session, and the picker
     * reads it back through `CookieManager.getCookie`, which does not report the
     * path a cookie was set with. Re-setting it at the page's own directory would
     * quietly narrow a site-wide gate flag to one folder, so the wider of the two
     * is chosen and said out loud here rather than discovered later.
     *
     * Nothing is logged. These are session credentials; see [BrowserState].
     */
    private fun seedCookies(url: String, cookies: String) {
        if (cookies.isBlank()) return
        val manager = runCatching { CookieManager.getInstance() }.getOrNull() ?: return
        runCatching {
            manager.setAcceptCookie(true)
            cookies.split(';').forEach { pair ->
                val trimmed = pair.trim()
                if (trimmed.contains('=')) manager.setCookie(url, "$trimmed; Path=/")
            }
        }.onFailure { Log.w(TAG, "Could not seed the captured session") }
    }

    /**
     * Installs the captured `localStorage` as a document-start script, scoped to
     * the origin it came from, and says whether it took.
     *
     * The origin rule is not a formality. Without it the script would run on
     * every page this WebView loads, and a redirect off the monitored site would
     * hand another host a copy of the session.
     */
    private fun seedStorageAtDocumentStart(view: WebView, state: BrowserState): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return false
        if (state.origin.isBlank()) return false
        return runCatching {
            WebViewCompat.addDocumentStartJavaScript(
                view,
                PickerScripts.seedStorage(state.localStorage),
                setOf(state.origin),
            )
            true
        }.getOrDefault(false)
    }

    /** The label on whatever is standing over the page, or blank. */
    /**
     * What the document says about its own state.
     *
     * `loading`, `interactive` or `complete`. This is the field that separates
     * "nothing rendered" from "rendered but the load event never fired", and the
     * second of those is invisible from every other signal the WebView offers.
     */
    private suspend fun readyState(view: WebView): String {
        val raw = runCatching { view.evalJs("document.readyState") }.getOrNull().orEmpty()
        return raw.trim().trim('"').take(16)
    }

    /**
     * Mutable state about one load, kept outside the timeout so it survives it.
     *
     * Not a data class and deliberately not immutable: it is written from the
     * WebView's callbacks, which arrive on the main thread while the coroutine
     * that started the load is suspended, and read once after the budget expires.
     */
    private class LoadTrace(val startedAtMs: Long) {
        var stage: LoadStage = LoadStage.NAVIGATING
        var progress: Int = -1
        var loggedProgress: Int = -1
        var readyState: String = ""
        var pageFinished: Boolean = false
        var requestsStarted: Int = 0
        var resourceErrors: Int = 0
        var resourceErrorsLogged: Int = 0
        var consoleErrors: Int = 0
        var consoleLogged: Int = 0
    }

    private suspend fun gateLabel(view: WebView): String {
        val obj = unwrap(view.evalJs(PickerScripts.GATE_PROBE)) ?: return ""
        val isGate = obj["gate"]?.jsonPrimitive?.booleanOrNull ?: false
        if (!isGate) return ""
        return obj["label"]?.jsonPrimitive?.content.orEmpty().take(60)
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

        /** Console lines kept per load. One broken script in a loop is not a log. */
        private const val CONSOLE_LINE_CAP = 12

        /** Subresource failures kept per load, for the same reason. */
        private const val RESOURCE_ERROR_CAP = 12

        /** How long the final `readyState` read may take once the budget is gone. */
        private const val EXPIRY_PROBE_MS = 500L
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
