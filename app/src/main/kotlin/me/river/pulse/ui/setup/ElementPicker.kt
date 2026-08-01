@file:SuppressLint("SetJavaScriptEnabled")

package me.river.pulse.ui.setup

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import me.river.pulse.data.check.ElementChecker
import me.river.pulse.data.web.PickerScripts
import me.river.pulse.ui.components.ButtonTone
import me.river.pulse.ui.components.GlassIconButton
import me.river.pulse.ui.components.MicroTag
import me.river.pulse.ui.components.PulseButton
import me.river.pulse.ui.components.SpinnerDot
import me.river.pulse.ui.icons.PulseIcons
import me.river.pulse.ui.theme.PulseColors
import me.river.pulse.ui.theme.PulseRadii
import me.river.pulse.ui.theme.glass
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

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
    )
}

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
 */
@Composable
fun ElementPickerOverlay(
    visible: Boolean,
    url: String,
    existingSelector: String,
    onDismiss: () -> Unit,
    onConfirm: (PickedElement) -> Unit,
    alreadyWatching: Int = 0,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(spring(dampingRatio = 0.85f)) { it } + fadeIn(),
        exit = slideOutVertically(spring(dampingRatio = 0.9f)) { it } + fadeOut(),
    ) {
        PickerContent(url, existingSelector, alreadyWatching, onDismiss, onConfirm)
    }
}

@Composable
private fun PickerContent(
    url: String,
    existingSelector: String,
    alreadyWatching: Int,
    onDismiss: () -> Unit,
    onConfirm: (PickedElement) -> Unit,
) {
    val context = LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var pickMode by remember { mutableStateOf(true) }
    var pageTitle by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<PickedElement?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableStateOf(0) }

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

    LaunchedEffect(pickMode) {
        webView?.evaluateJavascript(PickerScripts.setPickMode(pickMode), null)
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.apply {
                stopLoading()
                removeJavascriptInterface(PickerScripts.BRIDGE_NAME)
                destroy()
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(PulseColors.Void),
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
                    icon = PulseIcons.Close,
                    onClick = onDismiss,
                    contentDescription = "Close preview",
                    accent = PulseColors.TextSecondary,
                    size = 38.dp,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = pageTitle.ifBlank { "Loading page…" },
                        style = MaterialTheme.typography.titleMedium,
                        color = PulseColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = url,
                        style = MaterialTheme.typography.bodySmall,
                        color = PulseColors.TextTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(12.dp))
                GlassIconButton(
                    icon = PulseIcons.Refresh,
                    onClick = { webView?.reload() },
                    contentDescription = "Reload page",
                    accent = PulseColors.TextSecondary,
                    size = 38.dp,
                )
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
                    .background(Color.White.copy(alpha = 0.05f)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(if (loading) barWidth.coerceIn(0.02f, 1f) else 0f)
                        .height(2.dp)
                        .background(PulseColors.Aqua),
                )
            }

            // --- web view ------------------------------------------------------
            Box(Modifier.weight(1f)) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            setBackgroundColor(PulseColors.Ink.toArgb())
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                useWideViewPort = true
                                loadWithOverviewMode = true
                                builtInZoomControls = true
                                displayZoomControls = false
                                cacheMode = WebSettings.LOAD_DEFAULT
                                userAgentString = ElementChecker.MOBILE_UA
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
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    loading = false
                                    progress = 100
                                    view?.evaluateJavascript(PickerScripts.BOOTSTRAP) {
                                        view.evaluateJavascript(PickerScripts.setPickMode(pickMode), null)
                                    }
                                }

                                override fun onReceivedError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    err: WebResourceError?,
                                ) {
                                    if (request?.isForMainFrame == true) {
                                        loading = false
                                        error = err?.description?.toString() ?: "Page failed to load"
                                    }
                                }
                            }
                            webChromeClient = object : android.webkit.WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
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

                if (loading && progress < 45) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(PulseColors.Void.copy(alpha = 0.86f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            SpinnerDot(color = PulseColors.Aqua, size = 30.dp)
                            Spacer(Modifier.height(14.dp))
                            Text(
                                "Rendering the real page…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = PulseColors.TextSecondary,
                            )
                        }
                    }
                }

                if (error.isNotBlank()) {
                    Box(
                        Modifier
                            .align(Alignment.TopCenter)
                            .padding(16.dp)
                            .glass(RoundedCornerShape(16.dp), corner = 16.dp, accent = PulseColors.Rose)
                            .padding(14.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                PulseIcons.Warning,
                                contentDescription = null,
                                tint = PulseColors.Rose,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                error,
                                style = MaterialTheme.typography.bodySmall,
                                color = PulseColors.TextSecondary,
                            )
                        }
                    }
                }
            }

            // --- bottom sheet ---------------------------------------------------
            PickerBottomBar(
                pickMode = pickMode,
                onPickModeChange = { pickMode = it },
                picked = picked,
                existingSelector = existingSelector,
                alreadyWatching = alreadyWatching,
                onClear = {
                    picked = null
                    webView?.evaluateJavascript(PickerScripts.CLEAR_SELECTION, null)
                },
                onConfirm = { picked?.let(onConfirm) },
            )
        }
    }
}

@Composable
private fun PickerBottomBar(
    pickMode: Boolean,
    onPickModeChange: (Boolean) -> Unit,
    picked: PickedElement?,
    existingSelector: String,
    alreadyWatching: Int,
    onClear: () -> Unit,
    onConfirm: () -> Unit,
) {
    val bottomInset = WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()
    Column(
        Modifier
            .fillMaxWidth()
            .glass(
                shape = RoundedCornerShape(topStart = PulseRadii.sheet, topEnd = PulseRadii.sheet),
                corner = PulseRadii.sheet,
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
                        if (pickMode) PulseColors.Aqua.copy(alpha = 0.22f)
                        else Color.White.copy(alpha = 0.06f),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = PulseIcons.Pointer,
                    contentDescription = null,
                    tint = if (pickMode) PulseColors.Aqua else PulseColors.TextTertiary,
                    modifier = Modifier.size(17.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (pickMode) "Tap any element to select it" else "Browsing — links are live",
                    style = MaterialTheme.typography.titleMedium,
                    color = PulseColors.TextPrimary,
                )
                Text(
                    text = if (pickMode) {
                        "Scrolling still works. Turn off to follow links."
                    } else {
                        "Turn on select mode when you've found the right view."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = PulseColors.TextTertiary,
                )
            }
            Spacer(Modifier.width(10.dp))
            androidx.compose.material3.Switch(
                checked = pickMode,
                onCheckedChange = onPickModeChange,
                colors = androidx.compose.material3.SwitchDefaults.colors(
                    checkedThumbColor = PulseColors.Void,
                    checkedTrackColor = PulseColors.Aqua,
                    checkedBorderColor = PulseColors.Aqua,
                    uncheckedThumbColor = PulseColors.TextTertiary,
                    uncheckedTrackColor = Color.White.copy(alpha = 0.06f),
                    uncheckedBorderColor = Color.White.copy(alpha = 0.16f),
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
                        .background(Color.White.copy(alpha = 0.05f))
                        .border(1.dp, PulseColors.Aqua.copy(alpha = 0.28f), RoundedCornerShape(16.dp))
                        .padding(13.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MicroTag("<${element.tagName}>", color = PulseColors.Violet)
                        if (element.unique) {
                            MicroTag("unique", color = PulseColors.Mint, icon = PulseIcons.Check)
                        } else {
                            MicroTag(
                                "${element.matchCount} matches",
                                color = PulseColors.Amber,
                                icon = PulseIcons.Warning,
                            )
                        }
                    }
                    Text(
                        text = element.cssSelector.ifBlank { element.xpath },
                        style = MaterialTheme.typography.bodySmall,
                        color = PulseColors.Aqua,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    )
                    if (element.text.isNotBlank()) {
                        Text(
                            text = "“${element.text.take(140)}”",
                            style = MaterialTheme.typography.bodyMedium,
                            color = PulseColors.TextSecondary,
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
                color = PulseColors.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else if (picked == null && alreadyWatching > 0) {
            Text(
                text = "$alreadyWatching element${if (alreadyWatching == 1) "" else "s"} already " +
                    "watched on this page — all checked in one load.",
                style = MaterialTheme.typography.bodySmall,
                color = PulseColors.TextTertiary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (picked != null) {
                PulseButton(
                    text = "Clear",
                    onClick = onClear,
                    tone = ButtonTone.Secondary,
                    icon = PulseIcons.Close,
                )
            }
            PulseButton(
                text = if (picked != null) "Use this element" else "Pick an element",
                onClick = onConfirm,
                enabled = picked != null,
                icon = PulseIcons.Check,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
