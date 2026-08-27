package me.river.nightbell.domain

/**
 * The one place a secret is allowed to become a string a human can see.
 *
 * A GitHub token is a bearer credential: whoever holds it is the user, for as
 * long as it lives. So the rule here is absolute rather than best effort. It is
 * never logged, never written into a check's detail line, never put in a
 * notification, and never exported unless the user has explicitly asked for that
 * in the same breath. When it has to appear at all (confirming that a token is
 * saved, and which one), it appears through [redact].
 *
 * [scrub] is the backstop for text this app did not compose: an error message
 * from a library, a response body echoed into a detail field. Neither should ever
 * contain the token, and if one ever does it must not reach the screen intact.
 */
object Secrets {

    /** What a redacted token looks like: enough to recognise, useless to reuse. */
    private const val ELLIPSIS = "…"

    /** `github_pat_` is the longest prefix GitHub issues, ending at index 10. */
    private const val PREFIX_SCAN_LIMIT = 11

    /**
     * `ghp_abcdefghijklmnop` becomes `ghp_…mnop`.
     *
     * The prefix is kept because GitHub's own token types are told apart by it
     * (`ghp_` classic, `github_pat_` fine-grained) and "which kind did I paste"
     * is a real question someone re-reads this line to answer. The last four
     * characters are kept for the same reason a card's last four are: they
     * identify without authorising.
     */
    fun redact(token: String): String {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) return ""
        // The *leading* underscore group, not the last one. GitHub's prefixes are
        // `ghp_` and `github_pat_`, and a fine-grained token's random half
        // contains underscores of its own, so searching from the end keeps most
        // of the secret and calls it a prefix.
        val cut = trimmed.lastIndexOf('_', PREFIX_SCAN_LIMIT)
        val prefix = if (cut in 1..PREFIX_SCAN_LIMIT) trimmed.substring(0, cut + 1) else ""
        val tail = trimmed.takeLast(4)
        // Too short to split without showing most of it. Say nothing about the
        // contents at all rather than most of them.
        if (trimmed.length - prefix.length <= 8) return prefix + ELLIPSIS
        return prefix + ELLIPSIS + tail
    }

    /** Replaces every occurrence of [token] in [text] with its redacted form. */
    fun scrub(text: String, token: String): String {
        val trimmed = token.trim()
        // Four characters is short enough to appear in ordinary prose by accident,
        // and scrubbing those would corrupt the message instead of protecting it.
        if (trimmed.length < 8) return text
        if (!text.contains(trimmed)) return text
        return text.replace(trimmed, redact(trimmed))
    }
}
