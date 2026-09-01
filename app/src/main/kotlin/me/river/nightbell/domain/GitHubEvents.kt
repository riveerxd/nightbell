package me.river.nightbell.domain

/** Something worth telling the user about a watched repository. */
sealed interface GitHubEvent {

    /** The title line of the notification this event becomes. */
    fun title(slug: String): String

    /** The single line under it. */
    val body: String

    /** Where a tap should land. */
    val url: String

    /**
     * What the expanded notification shows, when that differs from [body].
     *
     * Every shipped event says the same thing either way: one line about a count
     * or a tag has no second paragraph to give.
     */
    val expanded: String get() = body

    /**
     * Stable identity for one piece of news.
     *
     * What decides whether two notifications share a row. Three new issues are
     * three separate things and must not overwrite one another, while a second
     * star notice about the same repository has nothing to add to the first and
     * should replace it.
     */
    val key: String

    data class Stars(val from: Int, val to: Int, val repoUrl: String) : GitHubEvent {
        val delta: Int get() = to - from

        override fun title(slug: String): String =
            if (delta == 1) "New star on $slug" else "New stars on $slug"

        override val body: String
            get() = "$from to $to stars (+$delta since the last check)"

        override val url: String get() = repoUrl

        override val key: String get() = "stars"
    }

    data class Milestone(val milestone: Int, val stars: Int, val repoUrl: String) : GitHubEvent {
        override fun title(slug: String): String = "$slug passed $milestone stars"

        override val body: String get() = "$stars stars now"

        override val url: String get() = repoUrl

        override val key: String get() = "milestone-$milestone"
    }

    data class Digest(
        val from: Int,
        val to: Int,
        val spanMs: Long,
        val repoUrl: String,
    ) : GitHubEvent {
        val delta: Int get() = to - from

        override fun title(slug: String): String = "+$delta stars on $slug"

        override val body: String get() = "$from to $to stars over the last ${spanLabel(spanMs)}"

        override val url: String get() = repoUrl

        override val key: String get() = "stars"
    }

    data class NewIssue(val item: GitHubItem) : GitHubEvent {
        override fun title(slug: String): String = "New issue on $slug"

        override val body: String get() = "#${item.number} ${item.title}"

        override val url: String get() = item.url

        override val key: String get() = "issue-${item.number}"
    }

    data class NewPull(val item: GitHubItem) : GitHubEvent {
        override fun title(slug: String): String = "New pull request on $slug"

        override val body: String get() = "#${item.number} ${item.title}"

        override val url: String get() = item.url

        override val key: String get() = "pull-${item.number}"
    }

    /**
     * Replies on one thread since the last poll.
     *
     * One event per parent rather than per comment, because five replies to the
     * same issue are one conversation with one tap target and the fifth notice
     * has nothing the fourth did not. That is the argument [key] already makes
     * for stars being a single replacing row, applied one level down.
     *
     * No parent issue title anywhere in here. The payload does not carry one, and
     * fetching it costs a request per thread out of an hourly budget that a
     * refusal mid-poll would then spend on nothing.
     */
    data class NewComments(
        val issueNumber: Int,
        val count: Int,
        val author: String,
        val text: String,
        val commentUrl: String,
    ) : GitHubEvent {
        override fun title(slug: String): String =
            if (count == 1) "New comment on $slug" else "$count new comments on $slug"

        override val body: String get() = buildString {
            append('#').append(issueNumber)
            append(if (count == 1) " by " else ", latest by ").append(author)
            val line = text.excerpt(COMMENT_EXCERPT)
            if (line.isNotBlank()) append(": ").append(line)
        }

        /** The comment anchor, so a tap lands on the reply and not the thread top. */
        override val url: String get() = commentUrl

        /**
         * Pulling the row down is the one place a comment's own words earn more
         * than the single line the shade shows collapsed.
         */
        override val expanded: String get() = buildString {
            append('#').append(issueNumber)
            append(if (count == 1) " by " else ", latest by ").append(author)
            val line = text.excerpt(COMMENT_EXPANDED)
            if (line.isNotBlank()) append('\n').append('\n').append(line)
        }

        /**
         * Per thread, never constant.
         *
         * Issue and pull request numbers share one space per repository, so this
         * cannot collide between the two sub-tracks.
         */
        override val key: String get() = "comment-issue-$issueNumber"
    }

    data class NewRelease(val release: GitHubRelease) : GitHubEvent {
        override fun title(slug: String): String = "New release on $slug"

        override val body: String get() = buildString {
            append(release.tag)
            if (release.name.isNotBlank() && release.name != release.tag) {
                append(' ').append(release.name)
            }
            if (release.prerelease) append(" (prerelease)")
        }

        override val url: String get() = release.url

        override val key: String get() = "release-${release.id}"
    }
}

/** How much of a comment reaches the collapsed row, and the expanded one. */
private const val COMMENT_EXCERPT = 120
private const val COMMENT_EXPANDED = 400

/**
 * A comment body as one readable line.
 *
 * Quoted lines go first, because a reply that opens by quoting the message above
 * it would otherwise show the user their own words back. Markdown is left exactly
 * as it arrived: a flattener that half understands it reads worse than the raw
 * text and cannot be tested against every bot that will ever post.
 */
private fun String.excerpt(max: Int): String {
    val unquoted = lineSequence()
        .filterNot { it.trimStart().startsWith(">") }
        .joinToString(" ")
        .collapseWhitespace()
    return unquoted.ifBlank { collapseWhitespace() }.ellipsize(max)
}

/** "2h", "1d". Only ever spans a digest window covers. */
private fun spanLabel(ms: Long): String {
    val minutes = ms / 60_000
    return when {
        minutes < 90 -> "${minutes.coerceAtLeast(1)}m"
        minutes < 60 * 36 -> "${minutes / 60}h"
        else -> "${minutes / (60 * 24)}d"
    }
}

/**
 * Turns one poll into notifications and the state the next poll compares against.
 *
 * Pure, and that is the point: every rule anyone actually argues about lives here
 * (does a baseline notify, does a plus one notify, does a pull request reach the
 * issue watcher, does a rollback in the star count look like growth) and every
 * one of them is a JVM test rather than something to reproduce by starring a
 * repository from a second account.
 */
object GitHubEvents {

    /**
     * Most items announced from one poll.
     *
     * A repository that receives eleven issues between two checks has had a
     * moment, and eleven notifications about it is not help. The cap is on what
     * is announced; the state still advances past all of them, so the twelfth
     * issue is new and the first eleven never come back.
     */
    const val MAX_ITEMS_PER_CHECK = 5

    /** First wait after the comment endpoint refuses, and how many doublings. */
    const val COMMENTS_RETRY_BASE_MS = 15L * 60 * 1000
    const val COMMENTS_BACKOFF_STEPS = 6

    data class Outcome(val events: List<GitHubEvent>, val state: GitHubState)

    fun evaluate(
        watch: GitHubWatch,
        previous: GitHubState,
        snapshot: GitHubSnapshot,
        nowMs: Long,
    ): Outcome {
        val repo = watch.repository
        val events = mutableListOf<GitHubEvent>()
        // The star track's own first sighting. The issue and release tracks each
        // carry theirs, because they can start being watched later. See
        // [GitHubState.issuesSeeded].
        val seeding = !previous.seeded

        var state = previous.copy(
            seeded = true,
            lastStarCount = snapshot.stars,
            openIssues = snapshot.openIssues,
            forks = snapshot.forks,
            watchers = snapshot.watchers,
            pushedAt = snapshot.pushedAt,
            repoEtag = snapshot.etags.repo,
            issuesEtag = snapshot.etags.issues,
            releasesEtag = snapshot.etags.releases,
            rateRemaining = snapshot.rate.remaining,
            rateLimit = snapshot.rate.limit,
            rateResetAt = snapshot.rate.resetAt,
            rateLimited = false,
            lastPolledAt = nowMs,
        )

        // ---- stars ----------------------------------------------------------
        //
        // Only an increase is news. A count that went *down* is somebody
        // un-starring, which is real but is not the thing anyone asked to be
        // told about, and reporting it as growth (or as an outage) would be a
        // false positive in the one direction that matters.
        val from = previous.lastStarCount
        val to = snapshot.stars
        val grew = !seeding && snapshot.repoChanged && to > from
        if (grew && watch.notifyOnStars) {
            val milestone = watch.starMilestones
                .filter { it in (from + 1)..to }
                .maxOrNull()
            when {
                // A milestone supersedes the plain notice rather than joining it.
                // Both are about the same three new stars, and two buzzes for one
                // event is exactly the noise the milestone mode exists to reduce.
                watch.notifyOnStarMilestones && milestone != null ->
                    events += GitHubEvent.Milestone(milestone, to, repo.url)

                watch.digestMode.isOn -> Unit // folded into the window below

                watch.notifyOnEveryStar -> events += GitHubEvent.Stars(from, to, repo.url)
            }
        }

        // A digest window opens on the first increase it swallows and closes when
        // it is older than the chosen span. Held open across process deaths, which
        // is why the anchor is a persisted star count rather than a running total.
        if (watch.notifyOnStars && watch.digestMode.isOn) {
            if (grew && state.digestStarsFrom < 0) {
                state = state.copy(digestStarsFrom = from, digestSince = nowMs)
            }
            val opened = state.digestSince
            if (state.digestStarsFrom >= 0 && opened > 0L &&
                nowMs - opened >= watch.digestMode.windowMs
            ) {
                if (to > state.digestStarsFrom) {
                    events += GitHubEvent.Digest(state.digestStarsFrom, to, nowMs - opened, repo.url)
                }
                state = state.copy(digestStarsFrom = -1, digestSince = 0L)
            }
        } else if (state.digestStarsFrom >= 0) {
            // Digest was switched off with a window open. Drop it rather than
            // holding a summary nobody will ever be sent.
            state = state.copy(digestStarsFrom = -1, digestSince = 0L)
        }

        // ---- issues and pull requests ---------------------------------------
        //
        // One endpoint answers both, so the split is here rather than in another
        // request: `pull_request` on an item means GitHub is showing a PR through
        // the issues API, and letting those into the issue watcher is the single
        // most common way a repo monitor cries wolf.
        if (snapshot.issuesChanged) {
            val issues = snapshot.issues.filter { !it.isPullRequest }
            val pulls = snapshot.issues.filter { it.isPullRequest }
            val known = previous.issuesSeeded
            state = state.copy(issuesSeeded = true)

            if (known && watch.notifyOnIssues) {
                issues.freshSince(previous.lastIssueId)
                    .filter(watch::accepts)
                    .forEach { events += GitHubEvent.NewIssue(it) }
            }
            if (known && watch.watchPullRequests) {
                pulls.freshSince(previous.lastPullId)
                    .filter(watch::accepts)
                    .forEach { events += GitHubEvent.NewPull(it) }
            }

            // Advanced past everything seen, filtered out or not. A keyword filter
            // is "do not tell me about this", not "ask me again every quarter of
            // an hour", and a watcher that is switched off must not flood the day
            // it is switched on.
            val newestIssue = issues.maxByOrNull { it.id }
            if (newestIssue != null && newestIssue.id > state.lastIssueId) {
                state = state.copy(
                    lastIssueId = newestIssue.id,
                    lastIssueNumber = newestIssue.number,
                    lastIssueTitle = newestIssue.title,
                    lastIssueUrl = newestIssue.url,
                    lastIssueCreatedAt = newestIssue.createdAt,
                )
            }
            val newestPull = pulls.maxByOrNull { it.id }
            if (newestPull != null && newestPull.id > state.lastPullId) {
                state = state.copy(
                    lastPullId = newestPull.id,
                    lastPullNumber = newestPull.number,
                    lastPullTitle = newestPull.title,
                    lastPullUrl = newestPull.url,
                )
            }
        }

        // ---- comments on issues ----------------------------------------------
        //
        // One endpoint answers issue threads and pull request threads alike, so
        // the split is here rather than in another request, and the pull half is
        // gated on the switch the user already set for pull requests.
        //
        // Both watermarks advance whatever the toggles say, exactly as the issue
        // and pull tracks do above, so switching pull requests on later cannot
        // replay a backlog of old conversation.
        if (snapshot.commentsAnswered) {
            state = state.copy(
                commentsSeeded = true,
                commentsFailures = 0,
                commentsRetryAt = 0L,
                commentsFailedCode = 0,
            )
        } else if (snapshot.commentsRefusedCode != 0) {
            // 15m, 30m, 1h, 2h, 4h, then 8h for as long as it keeps refusing.
            // A repository whose issue and pull request tabs are both off answers
            // 404 forever, and asking every quarter of an hour wastes a budget
            // that the other three tracks need.
            val failures = (previous.commentsFailures + 1).coerceAtMost(COMMENTS_BACKOFF_STEPS)
            state = state.copy(
                commentsFailures = failures,
                commentsRetryAt = nowMs + (COMMENTS_RETRY_BASE_MS shl (failures - 1)),
                commentsFailedCode = snapshot.commentsRefusedCode,
            )
        }

        if (snapshot.commentsChanged) {
            val issueComments = snapshot.comments.filter { !it.onPullRequest }
            val pullComments = snapshot.comments.filter { it.onPullRequest }
            val known = previous.commentsSeeded

            if (known) {
                val fresh = buildList {
                    addAll(issueComments.filter { it.id > previous.lastIssueCommentId })
                    if (watch.watchPullRequests) {
                        addAll(pullComments.filter { it.id > previous.lastPullCommentId })
                    }
                }
                fresh.filter(watch::acceptsComment)
                    .groupBy { it.issueNumber }
                    // One cap across both halves rather than one each: comments
                    // are a single toggle and a single request, so five threads
                    // is five threads however they are split.
                    .entries
                    .sortedBy { entry -> entry.value.maxOf { it.id } }
                    .takeLast(MAX_ITEMS_PER_CHECK)
                    .forEach { (issueNumber, group) ->
                        val newest = group.maxByOrNull { it.id } ?: return@forEach
                        events += GitHubEvent.NewComments(
                            issueNumber = issueNumber,
                            count = group.size,
                            author = newest.author,
                            text = newest.body,
                            commentUrl = newest.url,
                        )
                    }
            }

            // Guarded by a comparison rather than assigned, so deleting the
            // newest comment cannot walk a watermark backwards. Row order is
            // never trusted: sorting by creation does not tie-break by id, so a
            // page is not reliably id-descending.
            issueComments.maxOfOrNull { it.id }?.let { newest ->
                if (newest > state.lastIssueCommentId) {
                    state = state.copy(lastIssueCommentId = newest)
                }
            }
            pullComments.maxOfOrNull { it.id }?.let { newest ->
                if (newest > state.lastPullCommentId) {
                    state = state.copy(lastPullCommentId = newest)
                }
            }
        }

        // ---- releases --------------------------------------------------------
        //
        // `releaseChanged` is also true for a 404, which is how GitHub says a
        // repository has no releases at all. That still counts as having looked,
        // so the track is seeded and the first real release is news.
        if (snapshot.releaseChanged) {
            val known = previous.releasesSeeded
            state = state.copy(releasesSeeded = true)
            val release = snapshot.release
            if (release != null && !release.draft) {
                val wanted = !release.prerelease || watch.includePrereleases
                // Strictly greater, so a deleted release making an older one
                // "latest" is not announced as a new one.
                val isNew = release.id > previous.lastReleaseId
                if (known && watch.watchReleases && wanted && isNew) {
                    events += GitHubEvent.NewRelease(release)
                }
                if (wanted && isNew) {
                    state = state.copy(
                        lastReleaseId = release.id,
                        lastReleaseTag = release.tag,
                        lastReleaseName = release.name,
                        lastReleaseUrl = release.url,
                    )
                }
            }
        }

        return Outcome(events, state)
    }

    /** Newest-first input, oldest-first output, capped. */
    private fun List<GitHubItem>.freshSince(lastId: Long): List<GitHubItem> =
        filter { it.id > lastId }
            .sortedBy { it.id }
            .takeLast(MAX_ITEMS_PER_CHECK)
}
