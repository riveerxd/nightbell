package me.river.nightbell.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.util.Base64

/**
 * Reads a JWT's payload without verifying it.
 *
 * Only ever to fill in a form and to say something useful about a pasted
 * token: which provider and client it came from, whether it is an offline one,
 * when it runs out. Nothing is trusted on the strength of it. The refresh that
 * follows a paste is what decides whether the token is any good, and the expiry
 * of an access token is judged from the phone's own clock at the moment the
 * provider answered, never from `exp`, because the two clocks can disagree.
 */
object JwtPeek {

    private val json = Json { ignoreUnknownKeys = true }

    data class Claims(
        val issuer: String = "",
        /** `azp`, the client the token was issued to. Keycloak always sets it. */
        val authorizedParty: String = "",
        /** Keycloak's `typ`: `Refresh`, `Offline`, `Bearer`, `ID`. */
        val type: String = "",
        val subject: String = "",
        val username: String = "",
        /** Epoch seconds, 0 when absent. */
        val expiresAt: Long = 0L,
    ) {
        val isRefresh: Boolean get() = type.equals("Refresh", true) || type.equals("Offline", true)
        val isOffline: Boolean get() = type.equals("Offline", true)
        val isAccess: Boolean get() = type.equals("Bearer", true)

        /** What "Signed in as" shows. */
        val account: String get() = username.ifBlank { subject }
    }

    /** Null for anything that is not three dot-separated parts with a JSON object in the middle. */
    fun claims(token: String): Claims? {
        val parts = token.trim().split('.')
        if (parts.size != 3 || parts[1].isEmpty()) return null
        val payload = runCatching {
            String(Base64.getUrlDecoder().decode(parts[1].trimEnd('=')), Charsets.UTF_8)
        }.getOrNull() ?: return null
        val obj = runCatching { json.parseToJsonElement(payload) }.getOrNull() as? JsonObject ?: return null
        fun text(key: String) = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull.orEmpty()
        return Claims(
            issuer = text("iss"),
            authorizedParty = text("azp"),
            type = text("typ"),
            subject = text("sub"),
            username = text("preferred_username"),
            expiresAt = (obj["exp"] as? JsonPrimitive)?.longOrNull ?: 0L,
        )
    }
}
