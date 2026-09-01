package me.river.nightbell.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** How often a digest is allowed to speak. */
@Serializable
enum class DigestMode {
    @SerialName("off")
    OFF,

    @SerialName("hourly")
    HOURLY,

    @SerialName("daily")
    DAILY,
    ;

    val label: String
        get() = when (this) {
            OFF -> "Every change"
            HOURLY -> "Hourly"
            DAILY -> "Daily"
        }

    val windowMs: Long
        get() = when (this) {
            OFF -> 0L
            HOURLY -> 60L * 60 * 1000
            DAILY -> 24L * 60 * 60 * 1000
        }

    val isOn: Boolean get() = this != OFF
}

/**
 * What a GitHub monitor is watching, and how loudly.
 *
 * Every star by default, because that is what the feature was asked for. The
 * milestone and digest modes are noise controls for a repository that has grown
 * past the point where a plus one is news, and they are deliberately not the
 * default: a repo with eleven stars gets one notification a week, and turning
 * that into a daily summary would be turning the feature off.
 */
@Serializable
data class GitHubWatch(
    val owner: String = "",
    val repo: String = "",

    val notifyOnStars: Boolean = true,
    /** A plain "+1 star" notice for any increase that crosses no milestone. */
    val notifyOnEveryStar: Boolean = true,
    val notifyOnStarMilestones: Boolean = true,
    val starMilestones: List<Int> = DEFAULT_MILESTONES,
    val digestMode: DigestMode = DigestMode.OFF,

    val notifyOnIssues: Boolean = true,
    /** Case-insensitive substrings. Any match passes; empty means everything passes. */
    val issueKeywords: List<String> = emptyList(),
    /** GitHub logins. Any match passes; empty means everything passes. */
    val issueAuthors: List<String> = emptyList(),

    val watchReleases: Boolean = true,
    /**
     * Include prereleases and, with them, a different endpoint.
     *
     * `releases/latest` skips drafts and prereleases by design, so watching for
     * one means listing releases instead. Off by default: a prerelease is a thing
     * you go looking for, not a thing you want waking you.
     */
    val includePrereleases: Boolean = false,

    /** Separate from the issue watcher on purpose. See [GitHubEvents]. */
    val watchPullRequests: Boolean = false,

    /**
     * Comments on issues, closed ones included.
     *
     * Off by default, and that is load bearing rather than taste. A watch written
     * by an older build carries no key for this, so it takes the default when the
     * update lands, and defaulting to true would switch a fourth endpoint on for
     * every repository monitor already on a phone. It is also the loudest thing
     * here: [notifyOnIssues] asks GitHub for open issues only, while comments
     * arrive from every thread the repository has ever had.
     */
    val notifyOnComments: Boolean = false,

    /**
     * Let comments posted by GitHub Apps through.
     *
     * The test is App identity and never the text of the login. Five of the six
     * automation accounts on one page of rust-lang/rust reported `type: "User"`,
     * because they are machine accounts driven by a token rather than Apps, so a
     * substring test is the only thing that reaches them and that same test also
     * reaches talbot and botond. Silently skipping a person's comment is the one
     * failure this track cannot have, so the exact signals are the default and
     * the rest goes in [commentMutedText] where the user can see it.
     */
    val notifyOnBotComments: Boolean = false,

    /**
     * Logins whose comments never page, exactly as the user typed them.
     *
     * Raw text, which inverts the arrangement [issueKeywords] uses, because
     * re-parsing on every keystroke erases the comma that separates two entries
     * on the keystroke that produced it and the second login cannot be typed.
     */
    val commentMutedText: String = "",
) {
    val repository: GitHubRepo get() = GitHubRepo(owner, repo)

    val slug: String get() = repository.slug

    /** Comma-separated, for the text field that edits the list. */
    val keywordsText: String get() = issueKeywords.joinToString(", ")

    val authorsText: String get() = issueAuthors.joinToString(", ")

    /** Parsed on read, so the stored string stays whatever was typed. */
    val commentMutedAuthors: List<String> get() = splitTerms(commentMutedText)

    fun withKeywordsText(raw: String): GitHubWatch = copy(issueKeywords = splitTerms(raw))

    fun withAuthorsText(raw: String): GitHubWatch = copy(issueAuthors = splitTerms(raw))

    /** Whether an issue or PR survives the optional filters. */
    fun accepts(item: GitHubItem): Boolean {
        if (issueAuthors.isNotEmpty()) {
            val allowed = issueAuthors.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            if (item.author.lowercase() !in allowed) return false
        }
        if (issueKeywords.isNotEmpty()) {
            val haystack = (item.title + "\n" + item.body).lowercase()
            val terms = issueKeywords.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            if (terms.isNotEmpty() && terms.none { haystack.contains(it) }) return false
        }
        return true
    }

    /**
     * Whether a comment survives the bot rules and the filters.
     *
     * Its own function rather than a [GitHubItem] with a blank title, because
     * [accepts] reads [issueAuthors] as an allowlist and a user who typed one
     * login there to narrow the issue track must not silently stop hearing every
     * comment from everyone else. That list is deliberately not consulted here.
     */
    fun acceptsComment(comment: GitHubComment): Boolean {
        // GitHub has already folded this one away as off topic or spam. No
        // setting: a comment the repository hid is not news.
        if (comment.minimized) return false
        if (comment.isApp && !notifyOnBotComments) return false
        val muted = commentMutedAuthors.map { it.lowercase() }
        if (comment.author.lowercase() in muted) return false
        if (issueKeywords.isNotEmpty()) {
            // The body alone. A comment has no title, unlike the issues this
            // keyword list is shared with.
            val haystack = comment.body.lowercase()
            val terms = issueKeywords.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            if (terms.isNotEmpty() && terms.none { haystack.contains(it) }) return false
        }
        return true
    }

    /** One line for the setup screen and the monitor's configuration card. */
    val summary: String
        get() = buildList {
            if (notifyOnStars) {
                add(
                    when {
                        digestMode.isOn -> "stars ${digestMode.label.lowercase()}"
                        notifyOnEveryStar -> "every star"
                        notifyOnStarMilestones -> "star milestones"
                        else -> "stars"
                    },
                )
            }
            if (notifyOnIssues) add("issues")
            if (watchPullRequests) add("pull requests")
            if (notifyOnComments) add("comments")
            if (watchReleases) add("releases")
        }.joinToString(" · ").ifBlank { "Nothing selected" }

    companion object {
        /**
         * GitHub's fine-grained token page.
         *
         * The generic one, not a prefilled one. GitHub offers no reliable way to
         * pre-select scopes through a link, and a URL that silently dropped the
         * parts it could not honour would leave the user on a page that does not
         * match the instructions printed next to the button.
         */
        const val TOKEN_PAGE_URL = "https://github.com/settings/personal-access-tokens/new"

        /**
         * Round numbers a maintainer actually mentions out loud. Deliberately
         * sparse at the top: past a few thousand, every hundred is noise.
         */
        val DEFAULT_MILESTONES: List<Int> =
            listOf(10, 25, 50, 100, 250, 500, 1_000, 2_500, 5_000, 10_000, 25_000, 50_000)

        private fun splitTerms(raw: String): List<String> = raw
            .split(',', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }
}

/**
 * Everything a GitHub monitor has to carry between checks.
 *
 * Per monitor rather than global: two monitors on two repositories have nothing
 * to say to each other, and a shared last-seen would make the second one report
 * the first one's issues.
 */
@Serializable
data class GitHubState(
    /**
     * The first successful poll has happened.
     *
     * Its own flag rather than inferred from the counters, because zero is a real
     * answer for all of them: a repository with no stars, no open issues and no
     * releases is otherwise indistinguishable from one nobody has looked at yet,
     * and the difference decides whether the user's phone buzzes.
     */
    val seeded: Boolean = false,

    /**
     * The issue and release tracks seed separately from the star track.
     *
     * One flag for all three looked right and was wrong. A monitor created with
     * issues switched off never requests that endpoint at all, so the day the
     * user turns it on is the day the track sees its first response, with a
     * last-seen id of zero and five perfectly old issues waiting to be announced
     * as news. Each track therefore records its own first sighting.
     */
    val issuesSeeded: Boolean = false,
    val releasesSeeded: Boolean = false,

    /**
     * The comment track's own first sighting, for the reason above.
     *
     * Worse here than anywhere else if it is missed: the endpoint answers with a
     * page of up to a hundred, and a repository with years of conversation would
     * have the whole first page announced as news.
     */
    val commentsSeeded: Boolean = false,

    val lastStarCount: Int = 0,
    val lastIssueId: Long = 0L,
    val lastIssueNumber: Int = 0,
    val lastIssueTitle: String = "",
    val lastIssueUrl: String = "",
    val lastIssueCreatedAt: String = "",
    val lastPullId: Long = 0L,
    val lastPullNumber: Int = 0,
    val lastPullTitle: String = "",
    val lastPullUrl: String = "",
    /**
     * Two comment watermarks, split the way [lastIssueId] and [lastPullId] are.
     *
     * Both advance on every poll whatever the toggles say, so the day the user
     * switches pull requests on is not the day a handful of old pull request
     * comments arrive as news. Ids rather than timestamps: `updated_at` moves
     * every time anyone edits, and the heaviest editors are bots rewriting one
     * progress comment, so a timestamp watermark would page continuously.
     */
    val lastIssueCommentId: Long = 0L,
    val lastPullCommentId: Long = 0L,
    val lastReleaseId: Long = 0L,
    val lastReleaseTag: String = "",
    val lastReleaseName: String = "",
    val lastReleaseUrl: String = "",

    // ---- conditional GETs ---------------------------------------------------
    // One per endpoint. An authenticated 304 costs nothing against the primary
    // rate limit, which is the whole reason these are persisted rather than held
    // in memory: a check pass usually runs in a process that did not run the last
    // one, and an ETag that died with that process would never be sent.
    val repoEtag: String = "",
    val issuesEtag: String = "",
    val releasesEtag: String = "",
    val commentsEtag: String = "",

    // ---- comment track back-off ---------------------------------------------
    // Persisted rather than held in a field, for the same reason the ETags are:
    // a check pass usually runs in a process that did not run the last one, so an
    // in-memory counter would reset before it backed anything off. Set only by a
    // refusal that will still be a refusal next time (403, 404, 410), never by a
    // timeout or a 5xx: backing a track off because the wifi dropped is wrong.
    val commentsFailures: Int = 0,
    val commentsRetryAt: Long = 0L,
    val commentsFailedCode: Int = 0,

    // ---- repo health card ---------------------------------------------------
    val openIssues: Int = 0,
    val forks: Int = 0,
    val watchers: Int = 0,
    val pushedAt: String = "",

    // ---- rate limit ---------------------------------------------------------
    /** -1 until GitHub has told us. See [GitHubRate]. */
    val rateRemaining: Int = -1,
    val rateLimit: Int = 0,
    val rateResetAt: Long = 0L,
    /** The last poll was refused for budget reasons rather than answered. */
    val rateLimited: Boolean = false,
    val lastRateLimitAt: Long = 0L,

    // ---- digest -------------------------------------------------------------
    /** Star count when the open digest window started, or -1 when none is open. */
    val digestStarsFrom: Int = -1,
    val digestSince: Long = 0L,

    /** When the user last said they had read this monitor's news. */
    val seenAt: Long = 0L,
    val lastPolledAt: Long = 0L,
) {
    val hasRateInfo: Boolean get() = rateRemaining >= 0

    /** Short line for the detail card, e.g. `57/60 left, resets in 12m`. */
    fun rateSummary(nowMs: Long): String = when {
        !hasRateInfo -> "Not measured yet"
        rateLimited -> "Rate limited" + resetSuffix(nowMs)
        rateLimit > 0 -> "$rateRemaining of $rateLimit left" + resetSuffix(nowMs)
        else -> "$rateRemaining left" + resetSuffix(nowMs)
    }

    private fun resetSuffix(nowMs: Long): String {
        if (rateResetAt <= nowMs) return ""
        val minutes = ((rateResetAt - nowMs) + 59_999) / 60_000
        return ", resets in ${minutes}m"
    }
}

/**
 * GitHub's ISO-8601 timestamps as epoch millis, or 0 when absent or unreadable.
 *
 * Zero rather than a throw: a `pushed_at` this app cannot parse is a line the
 * detail card leaves out, and nothing more than that.
 */
fun githubInstantMs(raw: String): Long =
    if (raw.isBlank()) {
        0L
    } else {
        runCatching { java.time.Instant.parse(raw).toEpochMilli() }.getOrDefault(0L)
    }

/** One issue or pull request as the issues endpoint returns it. */
data class GitHubItem(
    val id: Long,
    val number: Int,
    val title: String,
    val body: String,
    val author: String,
    val createdAt: String,
    val url: String,
    /**
     * The issues endpoint returns pull requests too, flagged by a `pull_request`
     * object. Anything carrying one is a PR wearing an issue's shape.
     */
    val isPullRequest: Boolean,
)

/**
 * One comment, as the repository-wide issue comments endpoint returns it.
 *
 * No timestamp field, because nothing here sorts or renders by one: the id is the
 * ordering. The payload carries no parent issue title and no parent state either,
 * only [issueNumber], which is parsed out of `issue_url`.
 */
data class GitHubComment(
    val id: Long,
    val issueNumber: Int,
    val author: String,
    val body: String,
    val url: String,
    /**
     * The parent is a pull request rather than an issue.
     *
     * GitHub answers both from this one endpoint and there is no flag on the
     * comment saying which, so the only signal is the path segment in `html_url`.
     * The `pull_request` object the issues endpoint carries does not exist here.
     */
    val onPullRequest: Boolean,
    /** GitHub has hidden it as off topic, spam or a duplicate. */
    val minimized: Boolean,
    /** Posted by a GitHub App. See [GitHubWatch.notifyOnBotComments]. */
    val isApp: Boolean,
)

/** One release, from either `releases/latest` or the releases list. */
data class GitHubRelease(
    val id: Long,
    val tag: String,
    val name: String,
    val url: String,
    val prerelease: Boolean = false,
    val draft: Boolean = false,
    val publishedAt: String = "",
) {
    /** What the notification calls it: the release name, or the tag. */
    val displayName: String get() = name.ifBlank { tag }
}

/** Rate-limit headers as GitHub sent them on the last response. */
data class GitHubRate(
    val remaining: Int = -1,
    val limit: Int = 0,
    val resetAt: Long = 0L,
)

/**
 * One poll's worth of answers.
 *
 * The `changed` flags are what a `304 Not Modified` looks like from here: the
 * values are carried forward from the previous state so every reader sees a
 * complete picture, and the flag says the endpoint had nothing new. Both are
 * checked before anything is announced, which is belt and braces on purpose.
 */
data class GitHubSnapshot(
    val stars: Int,
    val openIssues: Int,
    val forks: Int,
    val watchers: Int,
    val pushedAt: String,
    val repoChanged: Boolean,
    val issues: List<GitHubItem> = emptyList(),
    val issuesChanged: Boolean = false,
    val release: GitHubRelease? = null,
    val releaseChanged: Boolean = false,
    val comments: List<GitHubComment> = emptyList(),
    /** A 200 arrived and [comments] is what it said. */
    val commentsChanged: Boolean = false,
    /**
     * A 200 or a 304 arrived, which is what clears the back-off.
     *
     * Separate from [commentsChanged] because a 304 is a successful look that
     * learned nothing: it must reset the failure ladder without being mistaken
     * for a page of comments.
     */
    val commentsAnswered: Boolean = false,
    /** The code of a refusal that should back the track off, or 0. */
    val commentsRefusedCode: Int = 0,
    val etags: GitHubEtags = GitHubEtags(),
    val rate: GitHubRate = GitHubRate(),
)

data class GitHubEtags(
    val repo: String = "",
    val issues: String = "",
    val releases: String = "",
    val comments: String = "",
)
