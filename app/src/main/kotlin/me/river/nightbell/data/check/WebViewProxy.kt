package me.river.nightbell.data.check

import android.util.Log
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.river.nightbell.domain.ProxyRoute

/**
 * Routes the app's WebViews through a SOCKS5 proxy for the length of one load.
 *
 * WebView can be proxied, contrary to a claim made in this app's own UI for a
 * while: `ProxyConfig.Builder.addProxyRule` documents the scheme as HTTP, HTTPS
 * or SOCKS, and Chromium's SOCKS5 support resolves the hostname at the proxy,
 * which is exactly what a `.onion` address needs.
 *
 * Two things make this awkward, and both are handled here rather than by the
 * caller.
 *
 * The override is **process-wide**. There is one Chromium network context behind
 * every WebView in the app, so switching it for a routed check switches it for
 * the element picker and any other load happening at the same time. Everything
 * therefore goes through [gate], one load at a time, and the override is cleared
 * in a `finally` so a thrown check cannot leave the whole app pinned to a proxy.
 *
 * The feature is **not on every device**. It rides the WebView package rather
 * than the platform, so an old or stripped WebView simply does not have it. In
 * that case [routed] refuses instead of loading direct, for the same reason the
 * HTTP checker refuses: silently going out in the clear is how a hidden service
 * name reaches an ISP's resolver.
 */
object WebViewProxy {

    /** Raised when a routed page load cannot be honoured. Never means "site down". */
    class Unavailable(message: String) : Exception(message)

    private val gate = Mutex()

    val isSupported: Boolean
        get() = WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)

    /**
     * Runs [block] with every WebView in this process pointed at [endpoint].
     *
     * Serialised against other routed loads, and always cleared afterwards. The
     * clear runs `NonCancellable`: a cancelled check that left the override in
     * place would silently route the next unrelated page load.
     */
    suspend fun <T> routed(endpoint: ProxyRoute.Endpoint, block: suspend () -> T): T {
        if (!isSupported) {
            throw Unavailable(
                "This device's WebView cannot be pointed at a proxy, so the page was not loaded.",
            )
        }
        return gate.withLock {
            apply(config(endpoint))
            try {
                block()
            } finally {
                withContext(NonCancellable) { clear() }
            }
        }
    }

    /**
     * `socks5://` and not `socks://`, deliberately.
     *
     * Chromium treats bare `socks` as SOCKS4, which resolves the hostname on this
     * device before dialling. For an onion address that fails outright, and for a
     * clearnet host routed on purpose it leaks the lookup, so the version has to
     * be spelled out.
     */
    private fun config(endpoint: ProxyRoute.Endpoint): ProxyConfig =
        ProxyConfig.Builder()
            .addProxyRule("socks5://${endpoint.host}:${endpoint.port}")
            // No direct fallback. A rule list ending in `addDirect()` would send
            // the request in the clear the moment the proxy was unreachable.
            .removeImplicitRules()
            .build()

    private suspend fun apply(config: ProxyConfig) = suspendCoroutine { cont ->
        ProxyController.getInstance().setProxyOverride(config, immediate) {
            cont.resume(Unit)
        }
    }

    private suspend fun clear() = suspendCoroutine { cont ->
        runCatching {
            ProxyController.getInstance().clearProxyOverride(immediate) { cont.resume(Unit) }
        }.onFailure {
            Log.w(TAG, "Could not clear the WebView proxy override", it)
            cont.resume(Unit)
        }
    }

    /** The callbacks only resume a coroutine, so there is nothing to hand off. */
    private val immediate = Executor { it.run() }

    private const val TAG = "WebViewProxy"
}
