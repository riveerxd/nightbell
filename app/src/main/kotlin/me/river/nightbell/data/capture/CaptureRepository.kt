package me.river.nightbell.data.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Identity of one captured interface: method + scheme + host:port + the full
 * path AND query, plus a fingerprint of the request body.
 *
 * Nothing is normalised away. Masking "volatile" query values or ignoring the
 * body folded several different interfaces of one host into a single row that
 * could only be tested as a whole — exactly what the interface list is not for.
 * A repeated call of the *same* URL with the *same* body still lands on one row
 * and just increments its hit count, so polling does not flood the list.
 */
fun capturedEndpointKey(
    method: String,
    scheme: String,
    host: String,
    port: Int,
    rawPath: String,
    body: String = "",
): String {
    val path = rawPath.substringBefore('?').ifEmpty { "/" }
    val query = rawPath.substringAfter('?', "")
    val base = "$method $scheme://$host:$port$path" + if (query.isEmpty()) "" else "?$query"
    if (body.isBlank()) return base
    // Same URL, different POST payload = a different interface on most intranet
    // APIs (the action lives in the body). Keep the key short and stable.
    return "$base #body=" + body.take(2048).hashCode().toString(16)
}

/**
 * One interface the phone actually called while capture was on.
 *
 * [key] comes from [capturedEndpointKey]: method + scheme + authority + the
 * complete path/query + a body fingerprint. Every distinct interface of a host
 * is therefore its own row and can be ticked, tested and monitored on its own;
 * only an identical repeated call increments [hits]. The first concrete [url]
 * is kept so a monitor is created from a request that really happened.
 *
 * [tlsOnly] means the entry came from an HTTP CONNECT: TLS hides the path, so all
 * that can honestly be claimed is "this host answers on this port". Those become
 * TCP port monitors rather than a made-up URL check.
 */
data class CapturedEndpoint(
    val key: String,
    val method: String,
    val scheme: String,
    val host: String,
    val port: Int,
    val path: String,
    val url: String,
    val headers: List<Pair<String, String>> = emptyList(),
    val bodySample: String = "",
    /** First lines of the response body, when the exchange was visible. */
    val responseSample: String = "",
    /** TLS was terminated locally, so path/headers/body are real, not inferred. */
    val decrypted: Boolean = false,
    val tlsOnly: Boolean = false,
    /** Why this CONNECT was not decrypted: off / empty / mismatch / handshake,
     * or "allowlisted" once the host is whitelisted but not revisited yet. */
    val blockReason: String? = null,
    val hits: Int = 1,
    val firstSeen: Long = System.currentTimeMillis(),
    val lastSeen: Long = System.currentTimeMillis(),
    val statusCode: Int? = null,
    val latencyMs: Long? = null,
    val error: String? = null,
)

data class CaptureState(
    val running: Boolean = false,
    val proxyPort: Int = 0,
    /** True when the proxy is up but Android is too old to announce it via VPN. */
    val manual: Boolean = false,
    val endpoints: List<CapturedEndpoint> = emptyList(),
    val totalRequests: Int = 0,
    val error: String? = null,
)

/**
 * Process-local capture log. It is deliberately not persisted: request headers
 * routinely carry Authorization/Cookie, and this app's whole pitch is that
 * nothing leaves the device. Stopping capture and leaving the screen drops it.
 *
 * A StateFlow rather than a callback list so the Compose screen simply collects
 * it, and because [record] is called from the proxy's worker threads.
 * [MutableStateFlow.update] is atomic, so two requests arriving at once cannot
 * lose one another.
 */
object CaptureRepository {

    /** Oldest entries fall off the end once the log gets long. */
    private const val MAX_ENDPOINTS = 300

    private val _state = MutableStateFlow(CaptureState())
    val state: StateFlow<CaptureState> = _state

    fun setRunning(port: Int, manual: Boolean = false) = _state.update {
        it.copy(running = true, proxyPort = port, manual = manual, error = null)
    }

    fun setStopped() = _state.update {
        it.copy(running = false, proxyPort = 0, manual = false)
    }

    fun fail(message: String) = _state.update {
        it.copy(running = false, proxyPort = 0, manual = false, error = message)
    }

    fun clearError() = _state.update { it.copy(error = null) }

    fun clear() = _state.update {
        it.copy(endpoints = emptyList(), totalRequests = 0, error = null)
    }

    /**
     * Marks the captured CONNECT rows of [hosts] as allowlisted WITHOUT removing
     * them. Allowlisting only changes how the *next* connection is handled: the
     * decrypted interface does not exist until the user visits that host again.
     * Deleting the rows here used to leave the interface list with no trace of
     * that domain at all, which looked like the capture had lost the request.
     *
     * Handshake-failure rows are diagnostics of a different problem (the app
     * does not trust the user CA) and keep their own reason.
     */
    fun markAllowlisted(hosts: Set<String>) = _state.update { state ->
        state.copy(
            endpoints = state.endpoints.map { row ->
                if (row.tlsOnly && row.host in hosts && row.blockReason != "handshake") {
                    row.copy(blockReason = "allowlisted")
                } else {
                    row
                }
            },
        )
    }

    /**
     * Retires the bare CONNECT placeholders of a host, but ONLY once a real
     * decrypted interface for it has been recorded. Handshake-failure rows stay:
     * they tell the user why some requests still cannot be decrypted.
     */
    private fun retirePlaceholders(
        rows: List<CapturedEndpoint>,
        host: String,
        port: Int,
    ): List<CapturedEndpoint> = rows.filterNot { row ->
        row.tlsOnly && row.host == host && row.port == port &&
            row.blockReason != "handshake"
    }

    /**
     * Inserts an interface the user typed in by hand (or derived from a CONNECT
     * host by adding paths). Neither a real request nor a hit, so unlike
     * [record] it does not touch totalRequests and does not bump hits.
     */
    fun put(endpoint: CapturedEndpoint) = _state.update { state ->
        if (state.endpoints.any { it.key == endpoint.key }) {
            state.copy(
                endpoints = state.endpoints.map { row ->
                    if (row.key == endpoint.key) row.copy(url = endpoint.url) else row
                },
            )
        } else {
            state.copy(endpoints = (listOf(endpoint) + state.endpoints).take(MAX_ENDPOINTS))
        }
    }

    fun record(endpoint: CapturedEndpoint) = _state.update { state ->
        val now = System.currentTimeMillis()
        // A decrypted exchange is the replacement for the host's CONNECT
        // placeholders. Retire them here (not when the allowlist is edited) so
        // the domain never disappears from the list before its real interfaces
        // have arrived. Plain HTTP rows never retire anything.
        val base = if (endpoint.decrypted) {
            retirePlaceholders(state.endpoints, endpoint.host, endpoint.port)
        } else {
            state.endpoints
        }
        val existing = base.firstOrNull { it.key == endpoint.key }
        val next = if (existing == null) {
            listOf(endpoint) + base
        } else {
            base.map { row ->
                if (row.key != endpoint.key) return@map row
                row.copy(
                    hits = row.hits + 1,
                    lastSeen = now,
                    url = endpoint.url,
                    headers = endpoint.headers.ifEmpty { row.headers },
                    bodySample = endpoint.bodySample.ifEmpty { row.bodySample },
                    responseSample = endpoint.responseSample.ifEmpty { row.responseSample },
                    decrypted = endpoint.decrypted || row.decrypted,
                    tlsOnly = if (endpoint.decrypted) false else row.tlsOnly,
                    blockReason = endpoint.blockReason ?: row.blockReason,
                    statusCode = endpoint.statusCode ?: row.statusCode,
                    latencyMs = endpoint.latencyMs ?: row.latencyMs,
                    error = endpoint.error,
                )
            }
        }
        state.copy(endpoints = next.take(MAX_ENDPOINTS), totalRequests = state.totalRequests + 1)
    }

    fun updateResult(key: String, statusCode: Int?, latencyMs: Long?, error: String?) = _state.update { state ->
        state.copy(
            endpoints = state.endpoints.map { row ->
                if (row.key != key) return@map row
                row.copy(
                    statusCode = statusCode ?: row.statusCode,
                    latencyMs = latencyMs ?: row.latencyMs,
                    error = error ?: row.error,
                )
            },
        )
    }
}
