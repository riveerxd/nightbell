package me.river.nightbell.domain

/**
 * What a WebView load error was actually about.
 *
 * A page monitor that cannot load reported [FailureKind.RENDER] for every
 * reason a load can fail, and RENDER is on the wrong side of the line
 * [Reachability.neverReachedAnything] draws: it means "the page came back
 * broken", which is proof the network worked. So a phone with no signal failed
 * every page monitor with a failure kind that says the network is fine, the
 * reachability probe was never spent, and every one of them paged. That is the
 * underground car park report again, arriving through the one checker the fix
 * did not cover.
 *
 * The error code was there the whole time and was being thrown away in
 * `onReceivedError`, which kept only the description string. The codes below
 * mirror `android.webkit.WebViewClient`'s `ERROR_*` constants. They are copied
 * rather than imported so the mapping can be asserted without a device, which
 * is the only way this ever gets a regression test.
 *
 * Chromium translates its own net errors into these before the WebView client
 * sees them, and the translation is the reason this works: `ERR_CONNECTION_REFUSED`,
 * `ERR_CONNECTION_RESET`, `ERR_ADDRESS_UNREACHABLE` and `ERR_INTERNET_DISCONNECTED`
 * all arrive as [CONNECT], which is exactly the set a dead radio produces.
 */
object PageLoadFailure {

    const val UNKNOWN = -1
    const val HOST_LOOKUP = -2
    const val UNSUPPORTED_AUTH_SCHEME = -3
    const val AUTHENTICATION = -4
    const val PROXY_AUTHENTICATION = -5
    const val CONNECT = -6
    const val IO = -7
    const val TIMEOUT = -8
    const val REDIRECT_LOOP = -9
    const val UNSUPPORTED_SCHEME = -10
    const val FAILED_SSL_HANDSHAKE = -11
    const val BAD_URL = -12
    const val FILE = -13
    const val FILE_NOT_FOUND = -14
    const val TOO_MANY_REQUESTS = -15
    const val UNSAFE_RESOURCE = -16

    /** No main frame error was recorded, so there is nothing to classify. */
    const val NONE = 0

    /**
     * The failure kind a main frame [errorCode] describes.
     *
     * [FailureKind.RENDER] is the answer for everything that is not clearly
     * about the connection, and that is deliberate: RENDER pages, so an
     * unrecognised code fails toward telling the user rather than toward
     * silence. Only the four codes that can only mean "nothing answered" buy
     * the right to be confirmed against the reference endpoint.
     *
     * [IO] is on that list even though it means the connection had been made
     * and then broke, because that is what a radio dying mid transfer looks
     * like and `HttpChecker` already treats the same failure that way.
     */
    fun kindOf(errorCode: Int): FailureKind = when (errorCode) {
        HOST_LOOKUP -> FailureKind.DNS
        CONNECT, IO -> FailureKind.CONNECT
        TIMEOUT -> FailureKind.TIMEOUT
        FAILED_SSL_HANDSHAKE -> FailureKind.TLS
        BAD_URL, UNSUPPORTED_SCHEME -> FailureKind.BAD_CONFIG
        else -> FailureKind.RENDER
    }

    /**
     * The headline for a load that failed with [errorCode].
     *
     * "Page failed to load" was true of all of them and useful about none. What
     * the user needs at three in the morning is which half of the sentence
     * broke: their site, or the thing between the phone and their site.
     */
    fun headline(errorCode: Int): String = when (kindOf(errorCode)) {
        FailureKind.DNS -> "Can't resolve the host"
        FailureKind.CONNECT -> "Couldn't connect to the page"
        FailureKind.TIMEOUT -> "The page never answered"
        FailureKind.TLS -> "The page's certificate was refused"
        FailureKind.BAD_CONFIG -> "That address can't be loaded"
        else -> "Page failed to load"
    }
}
