package me.river.nightbell.data.auth

import me.river.nightbell.domain.Monitor

/**
 * Requests to a monitor's sign-in provider.
 *
 * An interface so the broker can be driven against a fake provider on a local
 * socket in the JVM suite. The phone's implementation is [me.river.nightbell.data.check.HttpChecker],
 * so the provider is reached through the same proxy and trust settings as the
 * endpoint it signs in for.
 */
interface ProviderHttp {
    /** A form POST. [Answer.status] 0 means no answer at all. */
    suspend fun post(monitor: Monitor, url: String, form: Map<String, String>): Answer

    suspend fun get(monitor: Monitor, url: String): Answer

    data class Answer(val status: Int, val body: String, val error: String = "")
}
