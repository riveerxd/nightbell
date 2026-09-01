package me.river.nightbell.domain

/**
 * A repository monitor's history, as a list of things that happened.
 *
 * The check list is the wrong answer on this kind of monitor. Sixty rows of
 * "Healthy · 304 · 487 ms" describe the round trip to api.github.com, which is
 * not what anybody added the monitor to find out, and the one fact they did add
 * it for, the star count going from 11 to 13, was not written down anywhere
 * that could say *when*.
 *
 * ### Derived, not stored
 * Every row here is the difference between two consecutive [Sample]s. Nothing is
 * persisted as an event, and that is deliberate: history is trimmed to
 * [GlobalSettings.historyDepth], and a stored event list would either be trimmed
 * on a different schedule than the samples it describes or outlive them and
 * claim things the history can no longer support. Differencing what is there
 * cannot go out of sync with what is there.
 *
 * ### Quiet checks collapse
 * Most polls change nothing: that is what a 304 is. Those are counted, not
 * listed, so the rows that remain are all rows somebody would want to read.
 * Failures are never collapsed: being refused by GitHub is the one reason the
 * counts on screen could be lying about the repository.
 */
object GitHubActivity {

    sealed interface Row {
        /** When it was first seen. Rows are returned newest first. */
        val at: Long
    }

    /** The star count moved. [from] is the previous reading, not zero. */
    data class Stars(val from: Int, val to: Int, override val at: Long) : Row {
        val delta: Int get() = to - from
    }

    /** A new issue appeared, with the highest number seen at the time. */
    data class Issue(val number: Int, val title: String, override val at: Long) : Row

    /**
     * The open-issue count moved without a new issue arriving, which means one
     * was closed (or reopened). Worth a row: on a repository you maintain, an
     * issue closing is news of the same size as one opening.
     */
    data class IssueCount(val from: Int, val to: Int, override val at: Long) : Row {
        val closed: Int get() = from - to
    }

    /**
     * A comment arrived on a thread.
     *
     * No count, deliberately. GitHub publishes no repository-wide comment total,
     * so a number here would be Nightbell counting its own polls rather than
     * anything that can be checked against the repository. The issue track
     * already behaves this way: three new issues in one poll are three
     * notifications and one row.
     */
    data class Comment(
        val issue: Int,
        val author: String,
        override val at: Long,
    ) : Row

    data class Release(val tag: String, override val at: Long) : Row

    data class Forks(val from: Int, val to: Int, override val at: Long) : Row

    /** `pushed_at` moved: someone pushed to the repository. */
    data class Push(override val at: Long) : Row

    /**
     * A check that did not come back with an answer.
     *
     * [note] is the failure the checker recorded: a rejected token, a rate
     * limit, no network. Never collapsed, because for as long as these were
     * arriving the counts on the card above were not being verified by anything.
     */
    data class Failed(val note: String, val code: Int, override val at: Long) : Row

    /**
     * [checks] consecutive polls that found nothing new, ending at [at].
     *
     * Only emitted for a run of at least [QUIET_MIN] checks. A single quiet poll
     * between two events is a line saying nothing happened for fifteen minutes,
     * which is the noise this list exists to remove, and the timestamps on the
     * rows either side of it already say the same thing.
     */
    data class Quiet(val checks: Int, val since: Long, override val at: Long) : Row

    /**
     * The oldest sample carrying facts, which is where the history starts rather
     * than something that happened. Shown so the list cannot be read as "the
     * repository has always had 13 stars".
     */
    data class Baseline(val facts: RepoFacts, override val at: Long) : Row

    /**
     * Rows for [samples], newest first.
     *
     * [samples] arrive oldest first, as [MonitorRuntime.samples] stores them.
     * Samples with no [Sample.repo], every check written before this existed,
     * count as quiet rather than as a change, so an update does not announce a
     * repository's entire history the first time this screen is opened.
     */
    fun of(samples: List<Sample>): List<Row> {
        val rows = mutableListOf<Row>()
        var previous: RepoFacts? = null
        var quiet = 0
        var quietSince = 0L
        var quietAt = 0L

        fun flushQuiet() {
            if (quiet >= QUIET_MIN) rows += Quiet(checks = quiet, since = quietSince, at = quietAt)
            quiet = 0
        }

        fun noteQuiet(at: Long) {
            if (quiet == 0) quietSince = at
            quiet++
            quietAt = at
        }

        samples.forEach { sample ->
            if (!sample.ok) {
                flushQuiet()
                rows += Failed(note = sample.note, code = sample.code, at = sample.at)
                return@forEach
            }
            val facts = sample.repo?.takeIf { it.measured }
            if (facts == null) {
                noteQuiet(sample.at)
                return@forEach
            }
            val before = previous
            previous = facts
            if (before == null) {
                flushQuiet()
                rows += Baseline(facts, sample.at)
                return@forEach
            }

            // Order within one poll is fixed rather than by size: a release and the
            // stars it brought in should read the same way every time.
            val changes = mutableListOf<Row>()
            if (facts.releaseTag.isNotBlank() && facts.releaseTag != before.releaseTag) {
                changes += Release(facts.releaseTag, sample.at)
            }
            if (facts.issueNumber > before.issueNumber) {
                changes += Issue(facts.issueNumber, facts.issueTitle, sample.at)
            } else if (facts.openIssues != before.openIssues) {
                // Only when no new issue arrived. A new issue already moved this
                // count, and saying so twice for one event is the noise this list
                // exists to remove.
                changes += IssueCount(before.openIssues, facts.openIssues, sample.at)
            }
            // Guarded on the previous reading being a real one, not just on the
            // value moving. Without that, the first poll after the comment track
            // is switched on takes the id from zero to a real one and posts a row
            // about a conversation that may be years old.
            if (before.commentId > 0L && facts.commentId > before.commentId) {
                changes += Comment(facts.commentIssue, facts.commentAuthor, sample.at)
            }
            if (facts.stars != before.stars) {
                changes += Stars(before.stars, facts.stars, sample.at)
            }
            if (facts.forks != before.forks) {
                changes += Forks(before.forks, facts.forks, sample.at)
            }
            // A push that arrived with a release is the release being tagged, and the
            // release row already said it.
            if (facts.pushedAt > before.pushedAt && changes.none { it is Release }) {
                changes += Push(sample.at)
            }

            if (changes.isEmpty()) {
                noteQuiet(sample.at)
            } else {
                flushQuiet()
                rows += changes
            }
        }
        flushQuiet()
        return rows.asReversed()
    }

    /** Whether this monitor has anything to show beyond where it started. */
    fun hasNews(rows: List<Row>): Boolean = rows.any { it !is Quiet && it !is Baseline }

    /** Shortest run of unchanged checks that earns a line of its own. */
    const val QUIET_MIN = 2
}
