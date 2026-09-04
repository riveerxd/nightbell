package me.river.nightbell.domain

/**
 * How far a page monitor got before its budget ran out.
 *
 * ### Why this exists
 * A page check that overran reported one sentence, "Page did not finish loading
 * in 15s", for every way of overrunning. That sentence was also sometimes
 * false: the budget covers the whole sequence, so a page that loaded fine and
 * then failed to produce the element for four seconds of polling reported that
 * it had never loaded.
 *
 * Issue 8 ended on exactly this. Three sites timed out, the reporter raised the
 * timeout to sixty seconds and one still failed, and there was nothing in the
 * app or in a bug report that could say which part of the sequence was stuck.
 * A stage can.
 *
 * The mapping from a stage to what the user reads is here rather than in the
 * checker so it can be asserted without a device.
 */
enum class LoadStage {
    /** `loadUrl` has been called and the WebView has not reported starting yet. */
    NAVIGATING,

    /** The main frame started. Waiting for the load event, which is what stalls. */
    LOADING,

    /** A second load, because stored session data had to be seeded first. */
    RELOADING,

    /** The page reported finished and the element is being looked for. */
    POLLING,

    /** Every attempt is spent and the page is being asked whether it shows a gate. */
    GATE_PROBE,
}

/**
 * What was true at the moment the budget expired.
 *
 * Every field here answers a question that was asked in issue 8 and could not
 * be answered: whether the load event ever arrived, how far the renderer
 * thought it had got, what the document itself said about its own state, and
 * whether anything failed on the way.
 */
data class PageExpiry(
    val stage: LoadStage,
    /** The renderer's own progress, 0 to 100, or -1 when it never reported. */
    val progress: Int = -1,
    /** `loading`, `interactive`, `complete`, or blank when the document would not answer. */
    val readyState: String = "",
    val pageFinished: Boolean = false,
    val requestsStarted: Int = 0,
    val resourceErrors: Int = 0,
    val consoleErrors: Int = 0,
    val elapsedMs: Long = 0L,
) {
    /**
     * The headline the user reads on the monitor.
     *
     * Each of these names the stage in words rather than in the enum's terms,
     * because the point is a sentence a reporter can screenshot and a maintainer
     * can act on without asking for a log first. That screenshot is the cheapest
     * diagnostic this app has.
     */
    fun headline(timeoutSeconds: Int): String = when (stage) {
        LoadStage.NAVIGATING ->
            "The browser never started loading the page in ${timeoutSeconds}s"

        LoadStage.LOADING, LoadStage.RELOADING -> if (readyState == "complete") {
            "The page rendered but never signalled it had finished in ${timeoutSeconds}s"
        } else {
            "The page never finished loading in ${timeoutSeconds}s"
        }

        LoadStage.POLLING, LoadStage.GATE_PROBE ->
            "The page loaded but the element never appeared in ${timeoutSeconds}s"
    }

    /**
     * The paragraph under the headline.
     *
     * Written for somebody who is going to paste it into an issue, so it says
     * what was observed and, where there is one, the thing to try next. The
     * "still waiting on subresources" case is the one that matters: it is the
     * shape of a page whose load event never fires because something it
     * requested never answered, and raising the timeout does not fix it.
     */
    fun detail(): String = buildString {
        append("Stopped at ")
        append(
            when (stage) {
                LoadStage.NAVIGATING -> "navigation"
                LoadStage.LOADING -> "the page load"
                LoadStage.RELOADING -> "a second load, after restoring the saved session"
                LoadStage.POLLING -> "looking for the element"
                LoadStage.GATE_PROBE -> "checking for a gate"
            },
        )
        append(" after ")
        append(elapsedMs / 1000)
        append("s.")
        if (progress >= 0) append(" The renderer reported $progress%.")
        if (readyState.isNotBlank()) append(" The document said \"$readyState\".")
        append(" Load event: ")
        append(if (pageFinished) "arrived." else "never arrived.")
        if (requestsStarted > 0) append(" $requestsStarted requests were started.")
        if (resourceErrors > 0) append(" $resourceErrors of them failed.")
        if (consoleErrors > 0) append(" The page logged $consoleErrors errors.")
        if (!pageFinished && readyState == "interactive") {
            append(
                " A document that reaches \"interactive\" and stops is usually waiting on " +
                    "something it requested that never answered, so a longer timeout will " +
                    "not help. Turn the diagnostic log on in Settings and check again to see " +
                    "what failed.",
            )
        } else if (!pageFinished && progress in 0..99) {
            append(" Raising this monitor's timeout is worth trying first.")
        }
    }
}
