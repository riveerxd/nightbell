package me.river.nightbell.data.check

import me.river.nightbell.data.diag.Diag
import me.river.nightbell.domain.LogEvent
import me.river.nightbell.domain.LogField
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
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
 *
 * Because [gate] is held for as long as the caller's block runs, and the element
 * picker holds it for as long as a person is looking at the page, waiting for it
 * is **bounded**. A check that cannot have the override within [DEFAULT_WAIT_MS]
 * is refused with [Unavailable] rather than left suspended: the check pass runs
 * one monitor at a time, so an unbounded wait here would stop the whole fleet
 * for as long as the picker stayed open.
 */
object WebViewProxy {

    /**
     * Raised when a routed page load cannot be honoured. Never means "site down".
     *
     * [headline] is the short form for a check result or a refusal panel, because
     * the two reasons a load is refused are not the same fault: a WebView that
     * has no proxy support at all is a device limit, and a busy override is a
     * moment to wait out.
     */
    class Unavailable(val headline: String, message: String) : Exception(message)

    private val gate = Mutex()

    val isSupported: Boolean
        get() = WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)

    /**
     * Runs [block] with every WebView in this process pointed at [endpoint].
     *
     * Serialised against other routed loads, and always cleared afterwards. The
     * clear runs `NonCancellable`: a cancelled check that left the override in
     * place would silently route the next unrelated page load.
     *
     * @param waitMs how long to wait for another routed load to hand the override
     *   back. Refused rather than queued past it, so a caller on a schedule cannot
     *   be parked behind one that is waiting for a person.
     */
    suspend fun <T> routed(
        endpoint: ProxyRoute.Endpoint,
        waitMs: Long = DEFAULT_WAIT_MS,
        block: suspend () -> T,
    ): T {
        if (!isSupported) {
            throw Unavailable(
                headline = "This WebView cannot be routed",
                message = "This device's WebView cannot be pointed at a proxy, so the page was " +
                    "not loaded.",
            )
        }
        if (!acquire(waitMs)) {
            throw Unavailable(
                headline = "Another routed page load is in progress",
                message = "The WebView proxy setting is shared by the whole app, so routed page " +
                    "loads run one at a time. Something else held it for longer than " +
                    "${waitMs / 1_000}s, which the live preview does while it is open.",
            )
        }
        try {
            apply(config(endpoint))
            return try {
                block()
            } finally {
                withContext(NonCancellable) { clear() }
            }
        } finally {
            gate.unlock()
        }
    }

    /**
     * Takes [gate] within [waitMs], or gives up and says so.
     *
     * Polled rather than a timeout around `lock()` on purpose: cancelling a
     * suspended `lock()` at the instant it succeeds is the one way to lose the
     * mutex for the rest of the process, and `tryLock` cannot be caught in that
     * state.
     */
    private suspend fun acquire(waitMs: Long): Boolean {
        val deadline = System.nanoTime() + waitMs * 1_000_000L
        while (true) {
            if (gate.tryLock()) return true
            if (System.nanoTime() >= deadline) return false
            delay(POLL_MS)
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
            Diag.log(LogEvent.PROXY_CLEAR_FAILED, LogField.error("why", it))
            cont.resume(Unit)
        }
    }

    /** The callbacks only resume a coroutine, so there is nothing to hand off. */
    private val immediate = Executor { it.run() }

    /**
     * Long enough for an ordinary routed check to finish and hand the override
     * back, short enough that a check pass is not visibly stalled by one.
     */
    const val DEFAULT_WAIT_MS = 15_000L

    private const val POLL_MS = 50L

    private const val TAG = "WebViewProxy"
}
