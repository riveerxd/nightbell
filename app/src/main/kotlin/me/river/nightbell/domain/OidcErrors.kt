package me.river.nightbell.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * What a token endpoint's answer means for a monitor's sign-in.
 *
 * Pure, because the whole of "is this Nightbell's trouble or the service's" is
 * decided here, and that is the line the health format promises to hold:
 * Nightbell's own credentials going bad is never a claim about the service.
 */
object OidcErrors {

    private val json = Json { ignoreUnknownKeys = true }

    enum class Kind {
        /** Tokens came back. */
        OK,

        /** `invalid_grant`: the refresh token is dead, or for the password grant, the password is wrong. */
        GRANT_REFUSED,

        /** `invalid_client` or `unauthorized_client`: the client ID or secret is wrong. */
        CLIENT_REFUSED,

        /** `invalid_scope`, which for us means `offline_access` was not allowed. */
        SCOPE_REFUSED,

        /** No answer, or a 5xx. The service's users cannot sign in either. */
        UNREACHABLE,

        /** The token endpoint is not where discovery said. Worth one rediscovery. */
        NOT_FOUND,

        /** Anything else the provider said, kept for the detail line. */
        OTHER,
    }

    data class Tokens(
        val accessToken: String,
        /** Seconds, as the provider said. 0 when it did not say. */
        val expiresIn: Long,
        val refreshToken: String,
        val refreshExpiresIn: Long,
        val scope: String,
    )

    data class Answer(
        val kind: Kind,
        val tokens: Tokens? = null,
        val error: String = "",
        /** The provider's `error_description`, already scrubbed. */
        val description: String = "",
    ) {
        /** For a person: the provider's own words where it gave some. */
        val detail: String
            get() = listOf(error, description).filter { it.isNotBlank() }.joinToString(": ")
    }

    /** Reads one token endpoint response. [status] 0 means the request never got an answer. */
    fun read(status: Int, body: String?): Answer {
        if (status == 0) return Answer(Kind.UNREACHABLE)
        if (status >= 500) return Answer(Kind.UNREACHABLE, error = "HTTP $status")
        if (status == 404) return Answer(Kind.NOT_FOUND, error = "HTTP 404")
        val obj = runCatching { json.parseToJsonElement(body.orEmpty()) }.getOrNull() as? JsonObject
        fun text(key: String): String = (obj?.get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
        fun number(key: String): Long =
            (obj?.get(key) as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() } ?: 0L

        if (status in 200..299) {
            val access = text("access_token")
            if (access.isBlank()) {
                return Answer(Kind.OTHER, error = "no_access_token", description = "The provider answered without a token")
            }
            return Answer(
                Kind.OK,
                Tokens(
                    accessToken = access,
                    expiresIn = number("expires_in"),
                    refreshToken = text("refresh_token"),
                    refreshExpiresIn = number("refresh_expires_in"),
                    scope = text("scope"),
                ),
            )
        }
        val error = text("error").take(64)
        val description = LogRedactor.scrub(text("error_description")).take(200)
        val kind = when (error) {
            "invalid_grant" -> Kind.GRANT_REFUSED
            "invalid_client", "unauthorized_client" -> Kind.CLIENT_REFUSED
            "invalid_scope" -> Kind.SCOPE_REFUSED
            else -> Kind.OTHER
        }
        return Answer(kind, error = error.ifBlank { "HTTP $status" }, description = description)
    }
}
