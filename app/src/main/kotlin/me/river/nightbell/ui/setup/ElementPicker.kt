@file:SuppressLint("SetJavaScriptEnabled")

package me.river.nightbell.ui.setup

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import me.river.nightbell.data.check.ElementChecker
import me.river.nightbell.data.check.WebViewProxy
import me.river.nightbell.data.web.PickerScripts
import me.river.nightbell.domain.BrowserState
import me.river.nightbell.domain.ProxyRoute
import me.river.nightbell.domain.TlsTrust
import me.river.nightbell.ui.components.ButtonTone
import me.river.nightbell.ui.components.GlassIconButton
import me.river.nightbell.ui.components.MicroTag
import me.river.nightbell.ui.components.NightbellButton
import me.river.nightbell.ui.components.SpinnerDot
import me.river.nightbell.ui.icons.NightbellIcons
import me.river.nightbell.ui.theme.NightbellColors
import me.river.nightbell.ui.theme.NightbellRadii
import me.river.nightbell.ui.theme.glass
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** `about:blank`, which the picker loads on teardown and must never treat as a page. */
private const val BLANK = "about:blank"

/** What the picker hands back after the user taps a node. */
data class PickedElement(
    val cssSelector: String,
    val xpath: String,
    val elementId: String,
    val tagName: String,
    val classSignature: String,
    val text: String,
    val html: String,
    val matchCount: Int,
    val unique: Boolean,
    /**
     * The page this signature was derived on.
     *
     * Not always the URL the picker was opened with. The preview follows links,
     * which is the point of the browsing mode, and this used to come back with
     * no page attached: the selector was stored against whatever had been typed
     * on the setup screen, the check then loaded a page the element had never
     * been on, and it reported the element missing. That is issue #8.
     */
    val pageUrl: String = "",
)

private val pickerJson = Json { ignoreUnknownKeys = true; isLenient = true }

private fun parsePick(raw: String): PickedElement? {
    val obj = runCatching { pickerJson.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
    fun str(key: String) = obj[key]?.jsonPrimitive?.content.orEmpty()
    return PickedElement(
        cssSelector = str("cssSelector"),
        xpath = str("xpath"),
        elementId = str("elementId"),
        tagName = str("tagName"),
        classSignature = str("classSignature"),
        text = str("text"),
        html = str("html"),
        matchCount = obj["matchCount"]?.jsonPrimitive?.intOrNull ?: 1,
        unique = obj["unique"]?.jsonPrimitive?.booleanOrNull ?: true,
        pageUrl = str("pageUrl"),
    )
}

/**
 * `evaluateJavascript` hands back a JSON-encoded value, so a script that returns
 * a JSON string arrives wrapped in one more layer of quoting than it looks.
 */
private fun unwrapScriptResult(raw: String?): JsonObject? {
    if (raw.isNullOrBlank() || raw == "null") return null
    val unwrapped = runCatching { pickerJson.parseToJsonElement(raw).jsonPrimitive.content }
        .getOrElse { raw }
    return runCatching { pickerJson.parseToJsonElement(unwrapped) as? JsonObject }.getOrNull()
}

/**
 * The only thing reachable from JavaScript in this app, and the whole of it.
 *
 * A WebView that renders a user-supplied URL with script enabled and a bridge
 * attached is worth being explicit about, so: this class is the entire attack
 * surface a loaded page can address, it is three methods wide, and each one takes
 * a String and returns Unit.
 *
 * What the page can do through it: hand back a picked element as JSON, report the
 * document title once, report an error string. Nothing else is annotated, and R8
 * keeps only annotated members, so nothing else is callable by name either.
 *
 * What it cannot do. There is no Context, Activity, File, ContentResolver or
 * ClassLoader on this object, so there is nothing to walk to via the reflection
 * path that made addJavascriptInterface dangerous before API 17. Every parameter
 * is a String, so a page cannot pass an object in. `onPick` runs the JSON through
 * `parsePick`, which returns null on anything malformed and is dropped, so a
 * hostile page gets no further than a discarded parse. Each handler hops to the
 * main thread rather than touching state on the WebView's thread.
 *
 * What it can still do, stated rather than glossed: `onError` and `onReady` take
 * strings that end up on screen, so a page can put text of its choosing in the
 * picker's own UI. It is not a privilege boundary, it is a label, and the page
 * already controls everything else on that screen by definition.
 *
 * The WebView's own settings are trimmed where this class cannot help, notably
 * file and content access. See the settings block below.
 */
private class PickerBridge(
    private val pickHandler: (PickedElement) -> Unit,
    private val readyHandler: (String) -> Unit,
    private val errorHandler: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onPick(json: String) {
        val parsed = parsePick(json) ?: return
        main.post { pickHandler(parsed) }
    }

    @JavascriptInterface
    fun onReady(title: String) {
        main.post { readyHandler(title) }
    }

    @JavascriptInterface
    fun onError(message: String) {
        main.post { errorHandler(message) }
    }
}

/**
 * Full-screen live preview of the target site. The user browses normally, flips
 * on "Tap to select", and picks the node they want watched — the injected
 * script derives a durable selector and streams it back over the JS bridge.
 *
 * [route] is where the monitor being edited says its traffic goes, and this
 * screen obeys it. Nothing here is optional or best-effort: a preview is a real
 * request to the real host, so a monitor routed through a proxy gets a routed
 * preview or no preview at all. See [ProxyRoute.previewRefusal] for the two
 * cases that are refused outright, and [WebViewProxy] for what holding the
 * override costs while this is open.
 */
@Composable
fun ElementPickerOverlay(
    visible: Boolean,
    url: String,
    route: ProxyRoute.Route,
    /** Defaults to the conservative mode, for a caller with no monitor in hand. */
    tlsTrust: TlsTrust = TlsTrust.SYSTEM,
    existingSelector: String,
    onDismiss: () -> Unit,
    /**
     * The pick, plus whatever the browser was carrying when it was made. The
     * second half is what lets a check reach a page that is behind a gate the
     * user has just pressed through. See [me.river.nightbell.domain.BrowserState].
     */
    onConfirm: (PickedElement, BrowserState) -> Unit,
    alreadyWatching: Int = 0,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(spring(dampingRatio = 0.85f)) { it } + fadeIn(),
        exit = slideOutVertically(spring(dampingRatio = 0.9f)) { it } + fadeOut(),
    ) {
        PickerContent(url, route, tlsTrust, existingSelector, alreadyWatching, onDismiss, onConfirm)
    }
}

@Composable
private fun PickerContent(
    url: String,
    route: ProxyRoute.Route,
    tlsTrust: TlsTrust,
    existingSelector: String,
    alreadyWatching: Int,
    onDismiss: () -> Unit,
    onConfirm: (PickedElement, BrowserState) -> Unit,
) {
    val context = LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var pickMode by remember { mutableStateOf(true) }
    var pageTitle by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<PickedElement?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableStateOf(0) }
    // Where the preview actually is, which is not where it started once the user
    // has followed a link. Shown in the toolbar and, on confirm, is what the
    // monitor is pointed at.
    var currentUrl by remember { mutableStateOf(url) }

    // Nothing is loaded until routing has been settled one way or the other, so
    // there is no window in which the page is fetched before the proxy is on.
    var routeReady by remember { mutableStateOf(false) }
    var refusal by remember { mutableStateOf(ProxyRoute.previewRefusal(url, route)) }

    BackHandler(enabled = true) {
        val view = webView
        if (view != null && view.canGoBack()) view.goBack() else onDismiss()
    }

    val bridge = remember {
        PickerBridge(
            pickHandler = { picked = it },
            readyHandler = { pageTitle = it },
            errorHandler = { error = it },
        )
    }

    /**
     * Kills the WebView, once, whoever asks first.
     *
     * Called from two places on purpose, and the order between them is the point:
     * the routed branch below tears the view down *inside* the block that holds
     * the proxy override, so the override is still in place when the last request
     * this view could make becomes impossible. Clearing it first would leave a
     * live WebView on a page that is mid-load with no proxy, which is the leak
     * this screen is being fixed for.
     */
    val released = remember { AtomicBoolean(false) }
    val release: () -> Unit = {
        if (released.compareAndSet(false, true)) {
            webView?.apply {
                stopLoading()
                loadUrl(BLANK)
                removeJavascriptInterface(PickerScripts.BRIDGE_NAME)
                destroy()
            }
        }
    }

    LaunchedEffect(pickMode) {
        webView?.evaluateJavascript(PickerScripts.setPickMode(pickMode), null)
    }

    /**
     * Hands back the pick together with the session that made the page visible.
     *
     * Two round trips and neither is optional. `localStorage` can only be read
     * from inside the page, and cookies can only be read from out here, because
     * the ones a gate cares about are frequently `HttpOnly` and invisible to
     * script. The flush is what makes the capture survive the app being killed
     * before the first check runs.
     */
    val confirm: () -> Unit = confirm@{
        val element = picked ?: return@confirm
        val view = webView
        val page = element.pageUrl.ifBlank { currentUrl }
        val cookies = runCatching { CookieManager.getInstance().getCookie(page) }.getOrNull().orEmpty()
        runCatching { CookieManager.getInstance().flush() }
        val finish = { storage: String ->
            onConfirm(
                element.copy(pageUrl = page),
                BrowserState(
                    origin = BrowserState.originOf(page),
                    cookies = cookies,
                    localStorage = storage,
                    capturedAt = System.currentTimeMillis(),
                ).takeIf { !it.isEmpty } ?: BrowserState(),
            )
        }
        if (view == null) {
            finish("")
        } else {
            view.evaluateJavascript(PickerScripts.CAPTURE_STATE) { raw ->
                val parsed = unwrapScriptResult(raw)
                val storage = parsed?.get("storage")?.jsonPrimitive?.content.orEmpty()
                finish(if (storage == "{}") "" else storage)
            }
        }
    }

    // Keyed on nothing that changes while the picker is up: `url` and `route` are
    // read from the draft when it opens, and the draft cannot be edited from here.
    LaunchedEffect(Unit) {
        if (refusal != null) return@LaunchedEffect
        val endpoint = (route as? ProxyRoute.Route.Via)?.endpoint
        if (endpoint == null) {
            routeReady = true
            return@LaunchedEffect
        }
        try {
            WebViewProxy.routed(endpoint) {
                routeReady = true
                try {
                    // Held for as long as this screen is up. The override is
                    // process-wide, so a routed check finishing in the middle of a
                    // picking session would otherwise clear it under this page.
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) { release() }
                }
            }
        } catch (unavailable: WebViewProxy.Unavailable) {
            routeReady = false
            refusal = unavailable.message
        }
    }

    DisposableEffect(Unit) {
        onDispose { release() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(NightbellColors.Void),
    ) {
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(WindowInsets.statusBars.asPaddingValues().calculateTopPadding()))

            // --- toolbar -----------------------------------------------------
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassIconButton(
                    icon = NightbellIcons.Close,
                    onClick = onDismiss,
                    contentDescription = "Close preview",
                    accent = NightbellColors.TextSecondary,
                    size = 38.dp,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = pageTitle.ifBlank {
                            if (refusal != null) "No preview" else "Loading page…"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = NightbellColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // The live address, not the one that was typed. Following a
                    // link changes what a pick will mean, so it has to change
                    // what the screen says before the pick is made.
                    Text(
                        text = currentUrl.ifBlank { url },
                        style = MaterialTheme.typography.bodySmall,
                        color = NightbellColors.TextTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("picker-url"),
                    )
                }
                Spacer(Modifier.width(12.dp))
                // Nothing to reload on a refused preview, and a button that does
                // nothing is worse than no button.
                if (refusal == null) {
                    GlassIconButton(
                        icon = NightbellIcons.Refresh,
                        onClick = { webView?.reload() },
                        contentDescription = "Reload page",
                        accent = NightbellColors.TextSecondary,
                        size = 38.dp,
                    )
                }
            }

            // --- loading bar --------------------------------------------------
            val barWidth by animateFloatAsState(
                targetValue = progress / 100f,
                animationSpec = spring(stiffness = Spring.StiffnessLow),
                label = "progress",
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(NightbellColors.sheen(0.05f)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(
                            if (loading && refusal == null) barWidth.coerceIn(0.02f, 1f) else 0f,
                        )
                        .height(2.dp)
                        .background(NightbellColors.Aqua),
                )
            }

            // --- web view ------------------------------------------------------
            // Resolved out here: an AndroidView factory runs outside composition,
            // and a WebView painted the dark scheme's ink on a light page flashes
            // black on every load.
            val webBackground = NightbellColors.Ink.toArgb()
            Box(Modifier.weight(1f)) {
                // Composed only once routing is settled. The factory below is what
                // loads the page, so gating the whole view is what makes the fix a
                // fix rather than a race: there is no WebView to load anything
                // through until the proxy override is on.
                if (routeReady) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                // AndroidView gives its child WRAP_CONTENT, and a
                                // wrap-content height is what Chromium turns into a
                                // zero-height initial containing block. Every `vh`
                                // length in the page then resolves against nothing:
                                // `window.innerHeight` reads correctly and `100vh`
                                // comes out as 0. Reported on issue #8 as a preview
                                // that drew only the top strip of an age gate, which
                                // was a card sized `max-height: calc(100vh - 110px)`
                                // computing to 0px and collapsing to its padding.
                                //
                                // Nothing else moves it. Deferring the load until
                                // after the first layout, laying the view out by hand
                                // at the right size before loading, and reloading the
                                // page afterwards were all tried and all still gave
                                // `100vh` of 0. A bare WebView in a bare FrameLayout
                                // never has the problem, whatever order it is sized
                                // and loaded in, because a FrameLayout child gets
                                // real layout params. So the params are the fix.
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                )
                                setBackgroundColor(webBackground)
                                settings.apply {
                                    // Required, and the reason this screen exists. The
                                    // picker injects script into the loaded page so a tap
                                    // can resolve to a selector; without JS there is
                                    // nothing to pick with.
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    useWideViewPort = true
                                    loadWithOverviewMode = true
                                    builtInZoomControls = true
                                    displayZoomControls = false
                                    cacheMode = WebSettings.LOAD_DEFAULT
                                    userAgentString = ElementChecker.MOBILE_UA

                                    // Everything below is closing doors this WebView has
                                    // no use for. It loads a URL the user typed, which
                                    // means it renders arbitrary remote script, and it has
                                    // a bridge attached, so the surface is worth trimming
                                    // even though the bridge itself takes only strings.
                                    //
                                    // allowFileAccess is the one that matters: it defaults
                                    // to true below API 30 and minSdk here is 26, so on
                                    // API 26 to 29 a remote page could reach file:// URLs
                                    // unless it is turned off. There is no local content
                                    // to show, so it goes off on every API level.
                                    allowFileAccess = false
                                    allowContentAccess = false
                                    // Default since API 21, stated so a future edit has to
                                    // remove it deliberately rather than inherit it.
                                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                    // The app holds no location permission, so this could
                                    // only ever produce a prompt or a denial. Off.
                                    setGeolocationEnabled(false)

                                    // allowFileAccessFromFileURLs,
                                    // allowUniversalAccessFromFileURLs and databaseEnabled
                                    // are not set here on purpose. All three are already
                                    // false by default at this minSdk and all three are
                                    // deprecated, so assigning them only adds compiler
                                    // warnings to every build in exchange for nothing.
                                }
                                addJavascriptInterface(bridge, PickerScripts.BRIDGE_NAME)
                                webViewClient = object : WebViewClient() {
                                    override fun onPageStarted(
                                        view: WebView?,
                                        url: String?,
                                        favicon: android.graphics.Bitmap?,
                                    ) {
                                        loading = true
                                        progress = 12
                                        error = ""
                                        if (!url.isNullOrBlank() && url != BLANK) currentUrl = url
                                        // A pick belongs to the page it was made
                                        // on. Leaving one selected across a
                                        // navigation would let "Use this element"
                                        // save a selector for a page that is no
                                        // longer on screen.
                                        picked = null
                                    }

                                    /** Catches a pushState, which never fires onPageStarted. */
                                    override fun doUpdateVisitedHistory(
                                        view: WebView?,
                                        url: String?,
                                        isReload: Boolean,
                                    ) {
                                        if (!url.isNullOrBlank() && url != BLANK) currentUrl = url
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        loading = false
                                        progress = 100
                                        if (!url.isNullOrBlank() && url != BLANK) currentUrl = url
                                        view?.evaluateJavascript(PickerScripts.BOOTSTRAP) {
                                            view.evaluateJavascript(
                                                PickerScripts.setPickMode(pickMode),
                                                null,
                                            )
                                        }
                                    }

                                    /**
                                     * The preview obeys the monitor's own
                                     * certificate setting.
                                     *
                                     * It has to. The check and the picker load the
                                     * same URL from the same device, so a picker
                                     * that refused what the check accepts would
                                     * make a self-signed host impossible to set up
                                     * even though monitoring it works. 3.1.0 had
                                     * the same shape of bug with the proxy.
                                     *
                                     * No pin comparison here, only the mode. A
                                     * monitor being set up has nothing recorded
                                     * yet, and the check that runs afterwards is
                                     * what records it.
                                     */
                                    override fun onReceivedSslError(
                                        view: WebView?,
                                        handler: android.webkit.SslErrorHandler?,
                                        err: android.net.http.SslError?,
                                    ) {
                                        if (tlsTrust == TlsTrust.SYSTEM) {
                                            loading = false
                                            error = "The certificate was refused. If this " +
                                                "server is one you know, set this monitor's " +
                                                "certificate handling before picking."
                                            handler?.cancel()
                                        } else {
                                            handler?.proceed()
                                        }
                                    }

                                    override fun onReceivedError(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                        err: WebResourceError?,
                                    ) {
                                        if (request?.isForMainFrame == true) {
                                            loading = false
                                            error = err?.description?.toString()
                                                ?: "Page failed to load"
                                        }
                                    }
                                }
                                webChromeClient = object : android.webkit.WebChromeClient() {
                                    override fun onProgressChanged(
                                        view: WebView?,
                                        newProgress: Int,
                                    ) {
                                        progress = newProgress
                                    }

                                    override fun onReceivedTitle(view: WebView?, title: String?) {
                                        if (!title.isNullOrBlank()) pageTitle = title
                                    }
                                }
                                loadUrl(url)
                                webView = this
                            }
                        },
                    )
                }

                val blocked = refusal
                if (blocked != null) {
                    RefusedPreview(reason = blocked, onDismiss = onDismiss)
                } else if (!routeReady || (loading && progress < 45)) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(NightbellColors.Void.copy(alpha = 0.86f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            SpinnerDot(color = NightbellColors.Aqua, size = 30.dp)
                            Spacer(Modifier.height(14.dp))
                            Text(
                                if (routeReady) {
                                    "Rendering the real page…"
                                } else {
                                    "Pointing the preview through the proxy…"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = NightbellColors.TextSecondary,
                            )
                        }
                    }
                }

                if (error.isNotBlank() && refusal == null) {
                    Box(
                        Modifier
                            .align(Alignment.TopCenter)
                            .padding(16.dp)
                            .glass(RoundedCornerShape(16.dp), corner = 16.dp, accent = NightbellColors.Rose)
                            .padding(14.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                NightbellIcons.Warning,
                                contentDescription = null,
                                tint = NightbellColors.Rose,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                error,
                                style = MaterialTheme.typography.bodySmall,
                                color = NightbellColors.TextSecondary,
                            )
                        }
                    }
                }
            }

            // --- bottom sheet ---------------------------------------------------
            // No bar on a refused preview: there is no page, so "Tap to select"
            // and "Watch this element" would both be lies.
            if (refusal == null) {
                PickerBottomBar(
                    pickMode = pickMode,
                    onPickModeChange = { pickMode = it },
                    picked = picked,
                    existingSelector = existingSelector,
                    alreadyWatching = alreadyWatching,
                    movedTo = movedPage(url, picked?.pageUrl ?: currentUrl),
                    onClear = {
                        picked = null
                        webView?.evaluateJavascript(PickerScripts.CLEAR_SELECTION, null)
                    },
                    onConfirm = confirm,
                )
            }
        }
    }
}

/**
 * What the picker shows instead of a page when it will not load one.
 *
 * States the reason rather than a generic failure, because both reasons are
 * fixable by the person reading it and neither is the site being down. There is
 * no retry button on purpose: nothing about this screen can change the answer,
 * the monitor's routing switch can.
 */
@Composable
private fun RefusedPreview(reason: String, onDismiss: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(NightbellColors.Void.copy(alpha = 0.94f))
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            // Capped rather than centred alone: a refusal is three lines of prose,
            // and three lines the width of a tablet is harder to read than to skip.
            modifier = Modifier
                .widthIn(max = 420.dp)
                .testTag("picker-refused"),
        ) {
            Icon(
                NightbellIcons.Shield,
                contentDescription = null,
                tint = NightbellColors.Amber,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.height(14.dp))
            Text(
                "The preview was not opened",
                style = MaterialTheme.typography.titleMedium,
                color = NightbellColors.TextPrimary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                reason,
                style = MaterialTheme.typography.bodyMedium,
                color = NightbellColors.TextSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            NightbellButton(
                text = "Back to setup",
                onClick = onDismiss,
                icon = NightbellIcons.Close,
                tone = ButtonTone.Secondary,
                accent = NightbellColors.Amber,
            )
        }
    }
}

/**
 * The page the preview has walked to, or null while it is still on the one the
 * monitor names.
 *
 * Compared on everything but the fragment, because a fragment moves the
 * scrollbar and nothing else, and telling someone their monitor is about to move
 * because they tapped an anchor link would be noise. A trailing slash is the same
 * page as no trailing slash for the same reason.
 */
internal fun movedPage(from: String, to: String): String? {
    fun normalise(value: String) = value.trim().substringBefore('#').trimEnd('/').lowercase()
    if (to.isBlank()) return null
    if (normalise(to) == normalise(from)) return null
    // about:blank is the teardown, not a destination.
    if (to.startsWith(BLANK)) return null
    return to
}

/** How a URL reads in a strip one line tall: the path, or the host if there isn't one. */
internal fun shortPage(url: String): String {
    val afterScheme = url.substringAfter("://", url)
    val path = afterScheme.substringAfter('/', "")
    return if (path.isBlank()) afterScheme.substringBefore('/') else "/${path.substringBefore('#')}"
}

@Composable
private fun PickerBottomBar(
    pickMode: Boolean,
    onPickModeChange: (Boolean) -> Unit,
    picked: PickedElement?,
    existingSelector: String,
    alreadyWatching: Int,
    movedTo: String?,
    onClear: () -> Unit,
    onConfirm: () -> Unit,
) {
    val bottomInset = WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()
    Column(
        Modifier
            .fillMaxWidth()
            .glass(
                shape = RoundedCornerShape(topStart = NightbellRadii.sheet, topEnd = NightbellRadii.sheet),
                corner = NightbellRadii.sheet,
                elevation = 16.dp,
            )
            .padding(start = 18.dp, end = 18.dp, top = 16.dp)
            .padding(bottom = bottomInset + 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (pickMode) NightbellColors.Aqua.copy(alpha = 0.22f)
                        else NightbellColors.sheen(0.06f),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = NightbellIcons.Pointer,
                    contentDescription = null,
                    tint = if (pickMode) NightbellColors.Aqua else NightbellColors.TextTertiary,
                    modifier = Modifier.size(17.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (pickMode) "Tap any element to select it" else "Browsing — links are live",
                    style = MaterialTheme.typography.titleMedium,
                    color = NightbellColors.TextPrimary,
                )
                Text(
                    text = if (pickMode) {
                        "Scrolling still works. Turn off to follow links."
                    } else {
                        "Turn on select mode when you've found the right view."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = NightbellColors.TextTertiary,
                )
            }
            Spacer(Modifier.width(10.dp))
            androidx.compose.material3.Switch(
                checked = pickMode,
                onCheckedChange = onPickModeChange,
                // The row beside it explains the two modes, but the control
                // itself was an unlabelled switch: a screen reader announced
                // "on" with nothing to say what was on.
                modifier = Modifier
                    .testTag("picker-select-mode")
                    .semantics { contentDescription = "Select mode" },
                colors = androidx.compose.material3.SwitchDefaults.colors(
                    checkedThumbColor = NightbellColors.Void,
                    checkedTrackColor = NightbellColors.Aqua,
                    checkedBorderColor = NightbellColors.Aqua,
                    uncheckedThumbColor = NightbellColors.TextTertiary,
                    uncheckedTrackColor = NightbellColors.sheen(0.06f),
                    uncheckedBorderColor = NightbellColors.sheen(0.16f),
                ),
            )
        }

        AnimatedVisibility(visible = picked != null, enter = fadeIn(), exit = fadeOut()) {
            val element = picked
            if (element != null) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(NightbellColors.sheen(0.05f))
                        .border(1.dp, NightbellColors.Aqua.copy(alpha = 0.28f), RoundedCornerShape(16.dp))
                        .padding(13.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MicroTag("<${element.tagName}>", color = NightbellColors.Violet)
                        if (element.unique) {
                            MicroTag("unique", color = NightbellColors.Mint, icon = NightbellIcons.Check)
                        } else {
                            MicroTag(
                                "${element.matchCount} matches",
                                color = NightbellColors.Amber,
                                icon = NightbellIcons.Warning,
                            )
                        }
                    }
                    Text(
                        text = element.cssSelector.ifBlank { element.xpath },
                        style = MaterialTheme.typography.bodySmall,
                        color = NightbellColors.Aqua,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    )
                    if (element.text.isNotBlank()) {
                        Text(
                            text = "“${element.text.take(140)}”",
                            style = MaterialTheme.typography.bodyMedium,
                            color = NightbellColors.TextSecondary,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        if (picked == null && existingSelector.isNotBlank() && existingSelector != "—") {
            Text(
                text = "Replacing: $existingSelector",
                style = MaterialTheme.typography.bodySmall,
                color = NightbellColors.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else if (picked == null && alreadyWatching > 0) {
            Text(
                text = "$alreadyWatching element${if (alreadyWatching == 1) "" else "s"} already " +
                    "watched on this page — all checked in one load.",
                style = MaterialTheme.typography.bodySmall,
                color = NightbellColors.TextTertiary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Says what the button is about to do before it is pressed, because the
        // thing it does is no longer only "save a selector". The monitor's URL
        // moves with it, and a URL changing under someone is the kind of surprise
        // that gets found weeks later in a check result.
        if (movedTo != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.testTag("picker-moved-page"),
            ) {
                Icon(
                    imageVector = NightbellIcons.Link,
                    contentDescription = null,
                    tint = NightbellColors.Amber,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "You've moved to ${shortPage(movedTo)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NightbellColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (alreadyWatching > 0) {
                            "Saving points the monitor here. The $alreadyWatching element" +
                                "${if (alreadyWatching == 1) "" else "s"} already watched were " +
                                "picked on the old page and will be looked for here too."
                        } else {
                            "Saving points the monitor at this page instead."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = NightbellColors.TextTertiary,
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (picked != null) {
                NightbellButton(
                    text = "Clear",
                    onClick = onClear,
                    tone = ButtonTone.Secondary,
                    icon = NightbellIcons.Close,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            NightbellButton(
                text = when {
                    picked == null -> "Pick an element"
                    movedTo != null -> "Watch this page"
                    else -> "Use this element"
                },
                shortText = when {
                    picked == null -> "Pick one"
                    movedTo != null -> "Watch page"
                    else -> "Use this"
                },
                onClick = onConfirm,
                enabled = picked != null,
                icon = NightbellIcons.Check,
                modifier = Modifier.weight(2f),
            )
        }
    }
}
