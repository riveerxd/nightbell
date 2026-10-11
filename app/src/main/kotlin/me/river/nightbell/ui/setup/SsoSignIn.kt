package me.river.nightbell.ui.setup

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.river.nightbell.data.check.ElementChecker
import me.river.nightbell.data.diag.Diag
import me.river.nightbell.domain.LogEvent
import me.river.nightbell.domain.LogField
import me.river.nightbell.domain.OidcRequests
import me.river.nightbell.ui.SetupViewModel
import me.river.nightbell.ui.components.GlassIconButton
import me.river.nightbell.ui.components.SpinnerDot
import me.river.nightbell.ui.icons.NightbellIcons
import me.river.nightbell.ui.theme.NightbellColors

private const val BLANK = "about:blank"

/**
 * One profile for every sign-in, deleted when the overlay closes, so the
 * provider's cookies never reach the page element checker's shared cookie jar.
 */
private const val PROFILE = "nightbell.sso"

/**
 * The browser sign-in, full screen over setup, built like the element picker.
 *
 * Two halves. If the provider is not known yet, the web app is loaded and the
 * user taps its own Sign in button; its authorization request is caught before
 * it leaves, the provider is found from it, and that request is cancelled.
 * Then Nightbell makes its own request to the same provider, with the same
 * client and return address, its own PKCE pair and `offline_access`, and
 * catches the provider's answer on the way back to the web app's callback page,
 * which is never allowed to load: it would spend the single-use code with a
 * verifier only the web app has.
 */
@Composable
internal fun SsoSignInOverlay(viewModel: SetupViewModel) {
    AnimatedVisibility(
        visible = viewModel.browserSignInOpen,
        enter = slideInVertically(spring(dampingRatio = 0.85f)) { it } + fadeIn(),
        exit = slideOutVertically(spring(dampingRatio = 0.9f)) { it } + fadeOut(),
    ) {
        SignInContent(viewModel)
    }
}

private enum class Phase { APP, FINDING, PROVIDER, EXCHANGING, FAILED }

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SignInContent(viewModel: SetupViewModel) {
    val scope = rememberCoroutineScope()
    val watch = viewModel.draft.health
    val known = watch.authorizationEndpoint.isNotBlank() && watch.clientId.isNotBlank() && watch.redirectUri.isNotBlank()
    var phase by remember { mutableStateOf(if (known) Phase.PROVIDER else Phase.APP) }
    var failure by remember { mutableStateOf("") }
    var currentUrl by remember { mutableStateOf("") }
    var webView by remember { mutableStateOf<WebView?>(null) }

    // Read from WebView threads, so not Compose state.
    val pending = remember { AtomicReference<OidcRequests.Pending?>(null) }
    val redirect = remember { AtomicReference(watch.redirectUri) }
    val offlineAsked = remember { AtomicBoolean(true) }
    val usesPkce = remember { AtomicBoolean(true) }
    val handled = remember { AtomicBoolean(false) }
    val profiled = remember { AtomicBoolean(false) }

    val close = { viewModel.closeBrowserSignIn() }
    BackHandler(enabled = true) {
        val view = webView
        if (phase == Phase.APP && view != null && view.canGoBack()) view.goBack() else close()
    }

    fun start(view: WebView, offline: Boolean) {
        val details = viewModel.draft.health
        val request = OidcRequests.authorization(
            authorizationEndpoint = details.authorizationEndpoint,
            clientId = details.clientId,
            redirectUri = details.redirectUri,
            appScope = details.scope,
            offline = offline,
        )
        redirect.set(details.redirectUri)
        offlineAsked.set(offline)
        handled.set(false)
        pending.set(request)
        phase = Phase.PROVIDER
        view.loadUrl(request.url)
    }

    fun fail(message: String) {
        failure = message
        phase = Phase.FAILED
        Diag.log(LogEvent.AUTH_REFUSED, LogField.tag("where", "browser"))
    }

    fun answer(view: WebView, callback: OidcRequests.Callback) {
        val request = pending.get() ?: return
        if (!handled.compareAndSet(false, true)) return
        if (callback is OidcRequests.Callback.Code) {
            if (callback.state != request.state) {
                fail("The sign-in came back with a state Nightbell did not send, so it was stopped. Try again.")
                return
            }
            phase = Phase.EXCHANGING
            scope.launch(Dispatchers.Main) {
                val message = viewModel.finishBrowserSignIn(callback.code, request.verifier)
                if (message == null) {
                    close()
                } else {
                    fail(
                        message + if (!usesPkce.get()) {
                            ". This web app signs in without PKCE, so its client probably needs a secret, " +
                                "which a browser sign-in cannot supply. Use a service account instead."
                        } else {
                            ""
                        },
                    )
                }
            }
            return
        }
        val error = callback as OidcRequests.Callback.Error
        // Refused offline access is the one answer worth a second request: the
        // sign-in still works, it only ends with the provider's session.
        if (error.error == "invalid_scope" && offlineAsked.get()) {
            start(view, offline = false)
            return
        }
        fail(
            when (error.error) {
                "access_denied" -> "The sign-in was refused or cancelled on the provider's page."
                else -> "The provider answered ${error.error}" +
                    if (error.description.isBlank()) "." else ": ${error.description}"
            },
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.apply {
                stopLoading()
                loadUrl(BLANK)
                destroy()
            }
            if (profiled.get()) runCatching { ProfileStore.getInstance().deleteProfile(PROFILE) }
        }
    }

    val webBackground = NightbellColors.Ink.toArgb()
    Box(Modifier.fillMaxSize().background(NightbellColors.Void).testTag("sso-overlay")) {
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(WindowInsets.statusBars.asPaddingValues().calculateTopPadding()))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassIconButton(
                    icon = NightbellIcons.Close,
                    onClick = close,
                    contentDescription = "Close sign-in",
                    accent = NightbellColors.TextSecondary,
                    size = 38.dp,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = when (phase) {
                            Phase.APP -> "Tap Sign in on this page"
                            Phase.FINDING -> "Finding the provider…"
                            Phase.PROVIDER -> "Sign in"
                            Phase.EXCHANGING -> "Finishing the sign-in…"
                            Phase.FAILED -> "Sign-in did not finish"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = NightbellColors.TextPrimary,
                        modifier = Modifier.testTag("sso-banner"),
                    )
                    Text(
                        text = currentUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = NightbellColors.TextTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (phase == Phase.FINDING || phase == Phase.EXCHANGING) {
                    SpinnerDot(color = NightbellColors.Aqua)
                }
            }
            if (phase == Phase.FAILED) {
                Text(
                    text = failure,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NightbellColors.TextSecondary,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp).testTag("sso-failure"),
                )
            }
            Box(Modifier.weight(1f)) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            // Before anything else touches the view: a profile can
                            // only be set on a WebView that has not been used.
                            if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                                runCatching {
                                    ProfileStore.getInstance().getOrCreateProfile(PROFILE)
                                    WebViewCompat.setProfile(this, PROFILE)
                                    profiled.set(true)
                                }
                            }
                            Diag.log(
                                LogEvent.AUTH_DISCOVERY,
                                LogField.tag("where", "browser"),
                                LogField.of("multi_profile", profiled.get()),
                            )
                            // See ElementPicker: wrap-content collapses `100vh` to zero.
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            setBackgroundColor(webBackground)
                            settings.apply {
                                // The web app and the provider's login page are both
                                // script-driven; there is no sign-in without it.
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                useWideViewPort = true
                                loadWithOverviewMode = true
                                cacheMode = WebSettings.LOAD_NO_CACHE
                                userAgentString = ElementChecker.MOBILE_UA
                                allowFileAccess = false
                                allowContentAccess = false
                                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                setGeolocationEnabled(false)
                            }
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    val url = request.url.toString()
                                    val awaiting = pending.get()
                                    if (awaiting != null) {
                                        val callback = OidcRequests.callback(url, redirect.get()) ?: return false
                                        answer(view, callback)
                                        return true
                                    }
                                    val intercepted = OidcRequests.intercept(url, request.isForMainFrame) ?: return false
                                    usesPkce.set(intercepted.usesPkce)
                                    phase = Phase.FINDING
                                    // Main, explicitly: the lookup suspends on IO, and every
                                    // WebView call after it has to come back to this thread.
                                    scope.launch(Dispatchers.Main) {
                                        val found = viewModel.adoptWebAppRequest(intercepted)
                                        if (found == null) {
                                            fail(
                                                "Nightbell could not confirm which provider this sign-in goes to. " +
                                                    "Use Enter provider details under Other ways to sign in.",
                                            )
                                        } else {
                                            start(view, offline = true)
                                        }
                                    }
                                    return true
                                }

                                // The fence. Should a navigation to the callback get past
                                // the check above, the page still never loads, so it can
                                // never spend the code with its own verifier.
                                override fun shouldInterceptRequest(
                                    view: WebView,
                                    request: WebResourceRequest,
                                ): WebResourceResponse? {
                                    if (pending.get() == null) return null
                                    if (!OidcRequests.isCallback(request.url.toString(), redirect.get())) return null
                                    if (request.isForMainFrame) {
                                        OidcRequests.callback(request.url.toString(), redirect.get())?.let { callback ->
                                            view.post { answer(view, callback) }
                                        }
                                    }
                                    return WebResourceResponse(
                                        "text/plain",
                                        "utf-8",
                                        204,
                                        "No Content",
                                        emptyMap(),
                                        ByteArrayInputStream(ByteArray(0)),
                                    )
                                }

                                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                                    if (url != BLANK) currentUrl = url
                                }
                            }
                            webView = this
                            if (known) start(this, offline = true) else loadUrl(viewModel.webAppAddress)
                        }
                    },
                )
            }
        }
    }
}
