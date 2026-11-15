package me.river.nightbell.data.capture

/**
 * Process-local handle on the running proxy's connections.
 *
 * The proxy lives inside [CaptureVpnService], but the capture screen needs to
 * be able to say "drop every live tunnel now" when the list is cleared or the
 * HTTPS allowlist changes. Going through a service intent would risk starting a
 * service that the user had stopped; a plain in-process callback cannot.
 */
object CaptureProxyControl {

    @Volatile
    private var closeTunnels: (() -> Int)? = null

    /** Installed by the service once the proxy is bound; cleared on shutdown. */
    fun bind(close: () -> Int) {
        closeTunnels = close
    }

    fun unbind() {
        closeTunnels = null
    }

    /** Returns how many open CONNECT sockets were dropped. */
    fun resetTunnels(): Int = runCatching { closeTunnels?.invoke() ?: 0 }.getOrDefault(0)
}
