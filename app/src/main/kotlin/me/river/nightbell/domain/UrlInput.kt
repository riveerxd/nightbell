package me.river.nightbell.domain

/**
 * Finishing a URL somebody only half typed.
 *
 * Nobody types a scheme. They type `status.example.com`, the field turns red and
 * says "Start with http:// or https://", and the first thing this app ever tells
 * a new user is that they did it wrong. Issue #16 is that report.
 *
 * Completion happens on blur and nowhere else, which is the whole design. A field
 * that rewrites what is in it while the user is still typing is the thing
 * `RequestPreview` calls a bug, and mid-word every one of these values is
 * temporarily nonsense: `h`, `htt`, `https:/`. Waiting until the field is left
 * means the user sees the finished value appear once, in the field, and can
 * argue with it.
 */
object UrlInput {

    /** Same shape [Validation] uses, so the two agree about what a scheme is. */
    private val scheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")

    /** `http:` and `https:/`: a scheme that has been started and not finished. */
    private val halfScheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:/?$")

    /**
     * What the field should hold once the user has finished with it.
     *
     * Returns [raw] unchanged whenever there is no confident answer, because
     * leaving a field alone is always recoverable and guessing wrong is not.
     */
    fun completed(raw: String): String {
        val url = raw.trim()
        if (url.isEmpty()) return url
        if (scheme.containsMatchIn(url)) return url
        if (halfScheme.matches(url)) return url
        // Someone who has typed the word and nothing else is mid-scheme, not
        // naming a host called "https". Completing this produces `https://https`,
        // which is the one output here that reads as the app malfunctioning.
        if (url.equals("http", ignoreCase = true) || url.equals("https", ignoreCase = true)) {
            return url
        }
        // Protocol relative, which is what copying a link out of a browser's
        // network panel gives you. The authority is already there and only the
        // scheme is missing, so this is the same completion with a different
        // amount of punctuation.
        if (url.startsWith("//")) return "${preferredScheme(url)}:$url"
        return "${preferredScheme(url)}://$url"
    }

    /**
     * https everywhere except where https cannot work.
     *
     * No CA issues certificates for a .onion or .i2p name, so completing a hidden
     * service to https hands the user a monitor that can only fail, with the
     * warning [Validation] raises about trust anchors attached to a scheme this
     * app chose for them. Those services are served over http inside a circuit
     * that is already encrypted, which is why [Validation.urlNote] does not warn
     * about plain http there either.
     */
    private fun preferredScheme(url: String): String =
        if (ProxyRoute.isHiddenService(url)) "http" else "https"

    /** Said under the field, once, right after [completed] changed something. */
    fun completionNote(url: String): Validation.Note = Validation.Note(
        Validation.Field.URL,
        Validation.Severity.HINT,
        if (url.startsWith("http://", ignoreCase = true)) {
            "Added http://, because .onion and .i2p addresses have no certificate to check."
        } else {
            "Added https://. Type http:// yourself if this endpoint is plain."
        },
    )
}
