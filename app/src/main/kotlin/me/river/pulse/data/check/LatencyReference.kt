package me.river.pulse.data.check

import android.util.Log
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Times a known-good endpoint, so the phone's own network cost can be told apart
 * from a server being slow. The maths lives in
 * [me.river.pulse.domain.NetworkBaseline]; this only measures.
 *
 * Not an ICMP ping, despite being the same idea — raw sockets need root on
 * Android. This is the round trip to the first byte of an HTTP response, which
 * includes DNS, TCP and TLS. That is *good* for the purpose: those are exactly
 * the costs a monitor's own request pays, so the control resembles the thing it
 * is controlling for.
 */
class LatencyReference(
    baseClient: OkHttpClient? = null,
    private val elapsedNanos: () -> Long = System::nanoTime,
) {

    private val client: OkHttpClient = (baseClient ?: OkHttpClient())
        .newBuilder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(TIMEOUT_SECONDS + 2, TimeUnit.SECONDS)
        // A redirect would be measuring two round trips as one.
        .followRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    /**
     * Round trip to [url] in millis, or null if it could not be measured.
     *
     * Null is a first-class answer, not an error to shout about: a great many
     * networks block whatever endpoint is configured here, and
     * [me.river.pulse.domain.NetworkBaseline] treats absent readings as
     * "judge the raw latency" rather than as evidence of a slow connection.
     */
    suspend fun probe(url: String): Long? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        val request = try {
            Request.Builder()
                .url(url)
                // HEAD would be cheaper still, but plenty of endpoints answer it
                // with 405 after the same round trip, and some CDNs handle it on
                // a different path. GET of a 204 is the honest measurement.
                .get()
                .header("User-Agent", USER_AGENT)
                .header("Cache-Control", "no-cache")
                .build()
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "Bad reference URL: $url", error)
            return@withContext null
        }

        val started = elapsedNanos()
        return@withContext try {
            client.newCall(request).execute().use { response ->
                // Any answer at all is a completed round trip. A 204, a 301 or a
                // 500 all prove the network carried a request and a reply, which
                // is the only thing being measured.
                val elapsed = (elapsedNanos() - started) / 1_000_000
                if (response.code <= 0) null else elapsed.coerceAtLeast(1L)
            }
        } catch (cancellation: CancellationException) {
            // Not a failed probe — an interrupted one. Swallowing it would count
            // against the backoff and would report the network as blocking an
            // endpoint it never got to ask about.
            throw cancellation
        } catch (error: Throwable) {
            Log.i(TAG, "Reference probe failed (${error::class.java.simpleName}); latency will be judged raw")
            null
        }
    }

    private companion object {
        const val TAG = "LatencyReference"
        const val TIMEOUT_SECONDS = 4L
        const val USER_AGENT = "Nightbell-Monitor/1.0 (+latency-reference)"
    }
}
