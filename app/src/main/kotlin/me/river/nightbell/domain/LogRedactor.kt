package me.river.nightbell.domain

import java.security.MessageDigest

/**
 * The censor every diagnostic line passes through on its way to the log file.
 *
 * The log exists so a user can hand evidence to somebody else, which means the
 * file is going to end up in a public issue thread. That inverts the usual
 * threat model: the danger is not an attacker reading the device, it is the
 * owner publishing their own credentials by accident because a log line they
 * never read carried one.
 *
 * So censoring is an allowlist and this object is its implementation, not its
 * policy. Policy lives in [LogField], where every value entering a line has to
 * name its disclosure class before it can be constructed. What is here is the
 * machinery those classes call, plus [scrub] as a backstop for the one class
 * that cannot be reasoned about ahead of time: text this app did not author.
 *
 * There is no way to switch any of it off. A flag for "log the real values"
 * would be a flag that reaches a release build by accident, and the person who
 * needs raw values has `adb logcat`.
 */
object LogRedactor {

    /** Stands in for anything the backstop matched. */
    const val MASK = "<redacted>"

    /** How much of a sha256 a fingerprint keeps. Six hex is plenty to compare two reports. */
    private const val FINGERPRINT_HEX = 6

    /** Above this, a path segment or token is assumed to be an identifier rather than a word. */
    private const val OPAQUE_LENGTH = 20

    /**
     * Patterns applied to free text, most specific first.
     *
     * The last entry is the catch-all for a credential shaped like nothing on
     * this list, which is why the specific ones have to run before it: an
     * `Authorization` header matched generically would lose the header name,
     * and the name is the diagnostically useful half.
     */
    private val patterns: List<Pair<Regex, (MatchResult) -> String>> = listOf(
        // A named header, keeping the name. Covers a library echoing a request
        // back inside an exception message, which is how a token would most
        // plausibly arrive here.
        Regex("(?i)\\b(authorization|cookie|set-cookie|x-api-key|x-auth-token|proxy-authorization)\\b\\s*[:=]\\s*[^,;\"'\\n]+") to
            { m -> "${m.groupValues[1]}: $MASK" },
        // A named query parameter, keeping the name for the same reason.
        Regex("(?i)([?&])(token|key|api[_-]?key|access[_-]?token|id[_-]?token|refresh[_-]?token|auth|password|passwd|pwd|secret|sig|signature|session|sessionid|jwt)=[^&\\s\"'<>]+") to
            { m -> "${m.groupValues[1]}${m.groupValues[2]}=$MASK" },
        // Scheme-level credentials in a URL, which survive every later copy of
        // the line and are trivially reusable.
        Regex("//[^/\\s:@]+:[^/\\s@]+@") to { _ -> "//$MASK@" },
        Regex("(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]{8,}") to { _ -> "Bearer $MASK" },
        Regex("(?i)\\bbasic\\s+[A-Za-z0-9+/=]{8,}") to { _ -> "Basic $MASK" },
        // GitHub's own prefixes. Redacted rather than masked, because "which
        // token" is a question worth being able to answer across two reports.
        Regex("gh[pousr]_[A-Za-z0-9]{16,}") to { m -> Secrets.redact(m.value) },
        Regex("github_pat_[A-Za-z0-9_]{20,}") to { m -> Secrets.redact(m.value) },
        // A JWT carries its subject in the clear once anyone base64-decodes it,
        // so the whole thing goes.
        Regex("eyJ[A-Za-z0-9_-]{6,}\\.[A-Za-z0-9_-]{6,}\\.[A-Za-z0-9_-]{4,}") to { _ -> MASK },
        Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}") to { _ -> MASK },
        // Whatever is left that looks like an identifier rather than prose. This
        // is the entry that catches a credential nobody anticipated, and it is
        // last so the named cases keep their names.
        Regex("[A-Za-z0-9+/=_-]{32,}") to { m -> fingerprint(m.value) },
    )

    /**
     * `[41:9f3a1c]`, from the length and a hash prefix.
     *
     * Enough to say "the token is the same one as in your last report" or "the
     * cookie changed", which is most of what a secret is ever asked in a bug
     * thread, and useless to anybody who wants to replay it.
     */
    fun fingerprint(value: String): String {
        if (value.isEmpty()) return "[empty]"
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        val hex = digest.take(FINGERPRINT_HEX / 2).joinToString("") { "%02x".format(it) }
        return "[${value.length}:$hex]"
    }

    /**
     * A URL reduced to the part that is the operator's own infrastructure.
     *
     * Scheme, host and port survive. The path becomes a segment count and the
     * query becomes a parameter count, because both routinely carry a
     * credential or a person's name and neither is needed to diagnose anything:
     * "the monitor watches a three segment path and the preview ended on a one
     * segment path" is the whole diagnostic value of a path in a bug report,
     * and a count says it.
     *
     * Deliberately does not use `java.net.URI`, which throws on plenty of
     * strings a user will paste into the address field, and this runs on the
     * logging path where an exception is not an option.
     */
    fun route(url: String): String {
        val raw = url.trim()
        if (raw.isEmpty()) return "(blank)"
        val schemeEnd = raw.indexOf("://")
        val scheme = if (schemeEnd > 0) raw.substring(0, schemeEnd).lowercase() else ""
        val rest = if (schemeEnd > 0) raw.substring(schemeEnd + 3) else raw
        val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val authority = if (authorityEnd >= 0) rest.substring(0, authorityEnd) else rest
        val tail = if (authorityEnd >= 0) rest.substring(authorityEnd) else ""
        // Userinfo goes without being counted or described. There is nothing
        // about "this URL had a password in it" worth writing down.
        val hostPort = authority.substringAfterLast('@')
        // A host has no spaces in it. Without this, "not a url" came back as
        // itself, which is the one thing this function exists to prevent: it is
        // handed whatever a user typed into the address field.
        if (hostPort.isBlank() || hostPort.any { it.isWhitespace() }) return "(unparsed)"
        val query = tail.substringAfter('?', "").substringBefore('#')
        val path = tail.substringBefore('?').substringBefore('#')
        val segments = path.split('/').count { it.isNotBlank() }
        val params = if (query.isBlank()) 0 else query.split('&').count { it.isNotBlank() }
        return buildString {
            if (scheme.isNotEmpty()) append(scheme).append("://")
            append(hostPort.lowercase())
            if (segments > 0) append("/*").append(segments)
            if (params > 0) append("?*").append(params)
        }
    }

    /** Just the host, for a line that is about reachability rather than a request. */
    fun host(url: String): String {
        val route = route(url)
        return route.substringAfter("://").substringBefore('/').substringBefore('?')
    }

    /**
     * The backstop, for strings this app did not compose.
     *
     * An exception message, a WebView console line, a snippet of a response
     * body. None of them should contain a credential and any of them can, so
     * every pattern above runs over all of them. Newlines and tabs collapse
     * because a log line that spans two lines stops being parseable, and a
     * multi-line value is exactly how a stack trace or an HTML body would
     * arrive.
     *
     * @param known values from the live store that are secret by definition,
     *   replaced before the patterns run so a cookie whose shape matches
     *   nothing on the list still cannot survive.
     */
    fun scrub(raw: String, known: Collection<String> = emptyList()): String {
        if (raw.isEmpty()) return raw
        var text = replaceKnown(raw, known)
        for ((pattern, replacement) in patterns) {
            text = pattern.replace(text) { match -> replacement(match) }
        }
        return text.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim()
    }

    /**
     * Replaces values the store says are secret, wherever they appear.
     *
     * Split out from [scrub] because it runs a second time over the finished
     * line, after the fields have been assembled. That pass is the one guarantee
     * that does not depend on a call site choosing the right factory: whatever
     * else a line contains, it cannot contain the token or the session cookie
     * this install is currently holding.
     */
    fun replaceKnown(raw: String, known: Collection<String>): String {
        if (raw.isEmpty() || known.isEmpty()) return raw
        var text = raw
        for (secret in known) {
            val trimmed = secret.trim()
            // Short enough to occur in ordinary prose by accident, and replacing
            // those would corrupt the message rather than protect anything.
            if (trimmed.length < 8) continue
            if (text.contains(trimmed)) text = text.replace(trimmed, fingerprint(trimmed))
        }
        return text
    }

    /** Caps a scrubbed value so one long body cannot push the rest of a run out of the file. */
    fun truncate(text: String, limit: Int): String =
        if (text.length <= limit) text else text.take(limit) + "…"

    /**
     * Whether a token is long enough that it is probably an identifier.
     *
     * Used by the field constructors to decide whether a value the app believes
     * is safe should be fingerprinted anyway.
     */
    fun looksOpaque(value: String): Boolean =
        value.length >= OPAQUE_LENGTH && value.any { it.isDigit() }
}
