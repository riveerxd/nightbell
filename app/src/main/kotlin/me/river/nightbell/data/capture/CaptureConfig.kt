package me.river.nightbell.data.capture

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Which HTTPS hosts the local proxy is allowed to inspect.
 *
 * Decryption is OFF and the allowlist is empty until the user turns it on. That
 * is deliberate: an installed user CA is not trusted by most apps on Android 7+,
 * so blindly intercepting every CONNECT would break banking, mail and push
 * traffic on the whole phone. Only the intranet hosts the user names are
 * terminated locally; every other CONNECT is still tunnelled byte-for-byte.
 */
data class CaptureConfig(
    val decryptTls: Boolean = false,
    val hosts: List<String> = emptyList(),
) {
    /** Rules as the proxy actually sees them, for display in the UI. */
    fun effectiveHosts(): List<String> = hosts.mapNotNull { rule ->
        val (wildcard, host) = canonicalRule(rule)
        if (host.isEmpty()) null else if (wildcard) "*.$host" else host
    }.distinct()

    /** Exact host, `*.suffix`, or a bare suffix that also matches subdomains.
     *  Both sides are canonicalised so `https://host:8443/path`, `HOST:8443`
     *  and a bare host all mean the same thing. */
    fun matches(host: String): Boolean {
        if (!decryptTls || hosts.isEmpty()) return false
        val candidate = canonicalHost(host)
        if (candidate.isEmpty()) return false
        return effectiveHosts().any { rule ->
            if (rule.startsWith("*.")) {
                candidate.endsWith("." + rule.removePrefix("*."))
            } else {
                candidate == rule || candidate.endsWith(".$rule")
            }
        }
    }

    /** Why a CONNECT would NOT be decrypted. Null means it will be. */
    fun blockReason(host: String): String? = when {
        !decryptTls -> "off"
        effectiveHosts().isEmpty() -> "empty"
        !matches(host) -> "mismatch"
        else -> null
    }

    companion object {
        /** Turns anything the user might paste into a bare, lowercase hostname. */
        fun canonicalHost(raw: String): String {
            var value = raw.trim().lowercase()
            val scheme = value.indexOf("://")
            if (scheme >= 0) value = value.substring(scheme + 3)
            value = value.substringAfterLast('@')
            value = value.substringBefore('/').substringBefore('?').substringBefore('#')
            value = if (value.startsWith("[")) {
                val end = value.indexOf(']')
                if (end > 1) value.substring(1, end) else value
            } else {
                val colon = value.lastIndexOf(':')
                if (colon > 0 && value.substring(colon + 1).all { it.isDigit() }) {
                    value.substring(0, colon)
                } else {
                    value
                }
            }
            return value.trim().trim('.')
        }

        private fun canonicalRule(raw: String): Pair<Boolean, String> {
            val trimmed = raw.trim().lowercase()
            val wildcard = trimmed.startsWith("*.")
            return wildcard to canonicalHost(trimmed.removePrefix("*."))
        }
    }
}

/** Small SharedPreferences-backed setting, readable from the proxy's worker threads. */
object CaptureSettings {
    private const val PREF = "nightbell_capture"
    private const val KEY_DECRYPT = "decrypt_tls"
    private const val KEY_HOSTS = "decrypt_hosts"

    private val _state = MutableStateFlow(CaptureConfig())
    val state: StateFlow<CaptureConfig> = _state

    /** Text can be comma-, space- or newline-separated. */
    fun parseHosts(text: String): List<String> =
        text.split(',', ' ', '\n', '\r', '\t', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    fun init(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        _state.value = CaptureConfig(
            decryptTls = prefs.getBoolean(KEY_DECRYPT, false),
            hosts = parseHosts(prefs.getString(KEY_HOSTS, "").orEmpty()),
        )
    }

    fun save(context: Context, decryptTls: Boolean, hostsText: String) {
        val hosts = parseHosts(hostsText)
        _state.value = CaptureConfig(decryptTls, hosts)
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DECRYPT, decryptTls)
            .putString(KEY_HOSTS, hosts.joinToString(","))
            .apply()
    }

    /** Typing a host is itself an intent to inspect it: turn decryption on so
     *  a forgotten checkbox cannot silently leave every CONNECT tunnelled. */
    fun addHosts(context: Context, vararg hosts: String) {
        val current = _state.value
        val merged = (current.effectiveHosts() +
            hosts.map { CaptureConfig.canonicalHost(it) })
            .filter { it.isNotEmpty() }
            .distinct()
        save(context, true, merged.joinToString("\n"))
    }
}
