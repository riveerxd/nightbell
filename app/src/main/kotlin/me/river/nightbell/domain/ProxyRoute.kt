package me.river.nightbell.domain

/**
 * Which checks go through the SOCKS5 proxy, and where it is listening.
 *
 * Pure decision, deliberately kept out of the checker so it can be argued about
 * without a socket. Three things have to line up before a request is routed: the
 * proxy is switched on, its address is one that can actually be dialled, and the
 * monitor asked to use it.
 */
object ProxyRoute {

    /** A resolved place to send traffic. Nothing here is validated as reachable. */
    data class Endpoint(val host: String, val port: Int)

    /** Dialable TCP ports. A typo outside this range is refused, not attempted. */
    val PORTS = 1..65_535

    /** The suffix of a Tor hidden service. I2P uses `.i2p` and routes the same way. */
    private val HIDDEN_SUFFIXES = listOf(".onion", ".i2p")

    /**
     * Where a check is allowed to go.
     *
     * Three states, not two, and the third is the whole point. A monitor that
     * asked to be routed and has nowhere to route through must not quietly fall
     * back to going out directly: for a hidden service that publishes the exact
     * hostname the user is hiding, to their own resolver and their ISP, once per
     * interval, silently. It is a failed check, and it says so.
     */
    sealed interface Route {

        /** Straight out of the device, because nothing asked otherwise. */
        data object Direct : Route

        /** Through [endpoint]. */
        data class Via(val endpoint: Endpoint) : Route

        /** Asked for a proxy; there is no usable address to send it through. */
        data object Unconfigured : Route
    }

    /**
     * The shared proxy, or null when there is nothing usable to route through.
     *
     * Null rather than an exception for a blank host or a nonsense port, because
     * this is read on the check path and a half-typed address in Settings must
     * leave the monitors that do not use it completely alone.
     */
    fun endpoint(settings: GlobalSettings): Endpoint? {
        if (!settings.socksProxyEnabled) return null
        val host = settings.socksProxyHost.trim()
        if (host.isBlank()) return null
        if (settings.socksProxyPort !in PORTS) return null
        return Endpoint(host, settings.socksProxyPort)
    }

    /**
     * The address this monitor uses instead of the shared one, if it names one.
     *
     * Per monitor because one address cannot serve every hidden network: Tor
     * listens on 9050 and I2P's SOCKS proxy on 4447, and watching one service on
     * each is an ordinary thing to want. A blank host inherits the shared
     * address; a host with no port of its own borrows the shared port.
     */
    fun override(monitor: Monitor, settings: GlobalSettings): Endpoint? {
        val host = monitor.proxyHost.trim()
        if (host.isBlank()) return null
        val port = if (monitor.proxyPort in PORTS) monitor.proxyPort else settings.socksProxyPort
        if (port !in PORTS) return null
        return Endpoint(host, port)
    }

    /**
     * Where [monitor] should be checked from.
     *
     * Page-element monitors are routed too. They render in a WebView, which this
     * app used to claim could not be proxied; `ProxyConfig` documents the scheme
     * as HTTP, HTTPS or SOCKS, and Chromium resolves a SOCKS5 hostname at the
     * proxy, which is what an onion address needs. See
     * [me.river.nightbell.data.check.WebViewProxy] for what that costs: the
     * override is process-wide, so those loads are serialised.
     */
    fun forMonitor(monitor: Monitor, settings: GlobalSettings): Route {
        if (!monitor.useProxy) return Route.Direct
        val endpoint = override(monitor, settings) ?: endpoint(settings)
        return endpoint?.let(Route::Via) ?: Route.Unconfigured
    }

    /**
     * Whether [url] names a hidden service.
     *
     * Worth knowing on its own: these addresses have no public DNS record, so a
     * direct check cannot fail in any way except at the lookup, and the resulting
     * "can't resolve" tells the user nothing about what is wrong.
     */
    fun isHiddenService(url: String): Boolean {
        val host = hostOf(url).lowercase()
        return HIDDEN_SUFFIXES.any { host.endsWith(it) }
    }

    /** Host portion of a URL, without scheme, credentials, port, path or query. */
    fun hostOf(url: String): String =
        url.trim()
            .substringAfter("://", url.trim())
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('@')
            .substringBefore(':')
}
