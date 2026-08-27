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
