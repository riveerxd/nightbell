package me.river.nightbell.domain

/**
 * What to tell someone whose check failed on a certificate.
 *
 * Pure, and out here rather than in the checker, for a reason the reproduction
 * turned up: Conscrypt on a device reports an untrusted chain as "Trust anchor for
 * certification path not found" and the JVM reports the identical condition as
 * "PKIX path building failed". Any copy chosen by matching on that text is wrong
 * on one of the two platforms, so none of it is chosen that way. The checker
 * classifies the exception into a [Cause] and everything the user reads is decided
 * here, from things the app already knows about the monitor.
 *
 * Being pure also means the wording is testable, which for this feature is most of
 * the work. Issue #6 was not a broken handshake, it was a correct handshake
 * failure described so badly that the reporter could not tell what had happened:
 * "TLS/certificate error" over a line guessing between expired, self-signed and
 * hostname-mismatched, on a monitor watching a Tor hidden service where a
 * CA-less certificate is simply how the world works.
 */
object TlsFailure {

    /** Why the handshake was refused, as far as the app is concerned. */
    sealed interface Cause {

        /** Nothing the device trusts signed it. The self-signed case. */
        data object UntrustedChain : Cause

        /** A pinned monitor was answered by a different public key. */
        data class PinMismatch(val expected: String, val actual: String) : Cause

        /** Anything else the TLS layer threw: protocol, cipher, truncation. */
        data object Other : Cause
    }

    /**
     * The one-line version, which is what the dashboard and the notification show.
     *
     * Says which of the three it is, because the app knows and the user cannot
     * find out. "TLS/certificate error" was the whole of it until this landed.
     */
    fun headline(cause: Cause, hiddenService: Boolean): String = when {
        cause is Cause.PinMismatch -> "The certificate key changed"
        cause is Cause.UntrustedChain && hiddenService -> "No CA vouches for this certificate"
        cause is Cause.UntrustedChain -> "Certificate not trusted"
        else -> "TLS handshake failed"
    }

    /**
     * The paragraph under it.
     *
     * @param schemeUpgradedTo the `https` origin an `http` monitor was redirected
     *   to, when that is how it ended up making a TLS request at all. This is the
     *   sentence that was missing from issue #6: nothing in a URL beginning
     *   `http://` suggests a handshake, and the hop is two exchanges in the past by
     *   the time the failure arrives.
     */
    fun explanation(
        cause: Cause,
        hiddenService: Boolean,
        schemeUpgradedTo: String? = null,
    ): String {
        val body = when {
            cause is Cause.PinMismatch ->
                "This monitor is pinned to one public key and the server presented a " +
                    "different one. That happens when a certificate is replaced with a new " +
                    "key, and it is also what interception looks like, so the check fails " +
                    "instead of quietly trusting the new key. If you replaced it, use " +
                    "\"Trust the new key\" on this monitor.\n\n" +
                    "Expected ${cause.expected}\nReceived ${cause.actual}"

            cause is Cause.UntrustedChain && hiddenService ->
                "No certificate authority issues for .onion or .i2p addresses, so a " +
                    "certificate here can never be signed by one this phone trusts. The " +
                    "address is already the service's identity and the circuit already " +
                    "encrypts the request, so a CA has nothing to add. Set this monitor's " +
                    "certificate handling to \"Pinned key\"."

            cause is Cause.UntrustedChain ->
                "The certificate is not signed by any CA this phone trusts. That is normal " +
                    "for a self-signed certificate or a private CA. If you recognise this " +
                    "server, set its certificate handling to \"Pinned key\"; if you " +
                    "do not, treat it as a real failure."

            else ->
                "The connection was encrypted but the handshake did not complete. This is " +
                    "usually a protocol or cipher disagreement rather than anything about " +
                    "the certificate."
        }
        val hop = schemeUpgradedTo?.let {
            "\n\nThis monitor's URL is http, and the server redirected to $it, so the " +
                "check ended up making a TLS request. Turning off \"Follow redirects\" " +
                "keeps it on http."
        }.orEmpty()
        return body + hop
    }
}
