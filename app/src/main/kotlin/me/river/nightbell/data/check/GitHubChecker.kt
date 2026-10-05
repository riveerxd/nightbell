package me.river.nightbell.data.check

import me.river.nightbell.domain.DataSaver
import me.river.nightbell.domain.CheckResult
import me.river.nightbell.domain.FailureKind
import me.river.nightbell.domain.GitHubAsset
import me.river.nightbell.domain.GitHubComment
import me.river.nightbell.domain.DownloadReading
import me.river.nightbell.domain.GitHubDownloads
import me.river.nightbell.domain.GitHubWatch
import me.river.nightbell.domain.FileDownloads
import me.river.nightbell.domain.GitHubEtags
import me.river.nightbell.domain.GitHubItem
import me.river.nightbell.domain.GitHubRate
import me.river.nightbell.domain.GitHubRelease
import me.river.nightbell.domain.GitHubSnapshot
import me.river.nightbell.domain.GitHubState
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.ProxyRoute
import me.river.nightbell.domain.RepoFacts
import me.river.nightbell.domain.Secrets
import me.river.nightbell.domain.githubInstantMs
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Polls one GitHub repository over the REST API.
 *
 * Three constraints shape all of this, and none of them are optional.
 *
 * **The budget is tiny.** Sixty requests an hour per IP without a token, for the
 * whole device, shared with every other app behind the same address. One poll is
 * up to three requests, so every one of them carries an `If-None-Match` and a
 * `304 Not Modified` is the expected answer rather than the exception. An
 * authenticated 304 costs nothing against the primary limit at all.
 *
 * **Being refused is not an outage.** A `403` with no budget left, or a `429`
 * with a `Retry-After`, means Nightbell learned nothing about the repository. It
 * is recorded as rate-limit state and shown as such; it never becomes a failed
 * check, because a failed check is a claim about the thing being watched.
 *
 * **Calls go out one at a time.** Six repositories waking together and firing
 * eighteen requests in the same second is exactly the shape GitHub's secondary
 * limits exist to stop, and being told off for it costs the next hour.
 */
class GitHubChecker(
    baseClient: OkHttpClient? = null,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val settingsFor: () -> GlobalSettings = { GlobalSettings() },
    /** Overridable so tests can point the whole checker at a local server. */
    private val apiBase: String = API_BASE,
    /** Overridable so a test does not pay a real pacing delay per request. */
    private val minGapMs: Long = MIN_GAP_MS,
    private val elapsedNanos: () -> Long = System::nanoTime,
) {

    private val base: OkHttpClient = baseClient ?: OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * One GitHub call at a time, across every monitor.
     *
     * Shared rather than per monitor, and that is the point: the limit is per
     * address, so serialising within one repository would not stop six of them
     * bursting together.
     */
    private val queue = Mutex()

    @Volatile
    private var lastCallAt = 0L

    /** What one poll learned, and what has to be persisted about it either way. */
    data class Outcome(
        /**
         * The ordinary check verdict, or null when there is none.
         *
         * Null means "no verdict, and none was expected": the poll was refused
         * for budget reasons. The engine records that an attempt happened and
         * changes nothing else, which is the same treatment a check that could
         * not run gets everywhere else in this app.
         */
        val result: CheckResult?,
        val snapshot: GitHubSnapshot? = null,
        /** Rate-limit and ETag bookkeeping, worth keeping even with no verdict. */
        val state: GitHubState,
        val rateLimited: Boolean = false,
    )

    /**
     * One poll of one repository.
     *
     * [previous] supplies the ETags and the values a `304` carries forward, so
     * the caller always receives a complete snapshot and never has to reason
     * about which endpoint answered.
     */
    suspend fun poll(monitor: Monitor, previous: GitHubState, force: Boolean = false): Outcome =
        withContext(Dispatchers.IO) {
            val settings = settingsFor()
            val token = settings.githubToken.trim()
            val repo = monitor.github.repository
            if (!repo.isSet) {
                return@withContext Outcome(
                    result = CheckResult(
                        ok = false,
                        latencyMs = 0,
                        failureKind = FailureKind.BAD_CONFIG,
                        message = "No repository set",
                        detail = "This monitor has no owner/repo to poll, so nothing was sent.",
                        at = nowMs(),
                    ),
                    state = previous,
                )
            }

            val route = ProxyRoute.forMonitor(monitor, settings)
            if (route is ProxyRoute.Route.Unconfigured) {
                return@withContext Outcome(
                    result = CheckResult(
                        ok = false,
                        latencyMs = 0,
                        failureKind = FailureKind.BAD_CONFIG,
                        message = "No proxy to route through",
                        detail = "This monitor is set to use a SOCKS5 proxy and no usable " +
                            "address is configured, so the check was not sent.",
                        at = nowMs(),
                    ),
                    state = previous,
                )
            }
            val endpoint = (route as? ProxyRoute.Route.Via)?.endpoint
            val timeout = monitor.effectiveTimeoutSeconds(settings, proxied = endpoint != null)
            val client = base.newBuilder()
                .connectTimeout(timeout.toLong(), TimeUnit.SECONDS)
                .readTimeout(timeout.toLong(), TimeUnit.SECONDS)
                .callTimeout((timeout + 5).toLong(), TimeUnit.SECONDS)
                .followRedirects(true)
                .apply { if (endpoint != null) proxy(socksProxy(endpoint)) }
                .build()

            val watch = monitor.github
            var rate = GitHubRate(previous.rateRemaining, previous.rateLimit, previous.rateResetAt)

            // ---- the repository itself ---------------------------------------
            val started = elapsedNanos()
            val repoCall = call(client, "$apiBase/repos/${repo.owner}/${repo.name}", previous.repoEtag, token)
            val latency = ((elapsedNanos() - started) / 1_000_000L).coerceAtLeast(1L)
            repoCall.rate?.let { rate = it }

            when (repoCall) {
                is Answer.Limited -> return@withContext limited(previous, repoCall, rate)
                is Answer.Failed -> return@withContext Outcome(
                    result = repoCall.toResult(nowMs(), token, "GitHub"),
                    state = previous.copy(
                        rateRemaining = rate.remaining,
                        rateLimit = rate.limit,
                        rateResetAt = rate.resetAt,
                        rateLimited = false,
                    ),
                )
                else -> Unit
            }

            val repoChanged = repoCall is Answer.Ok
            val body = (repoCall as? Answer.Ok)?.body
            val stars = body?.int("stargazers_count") ?: previous.lastStarCount
            val openIssues = body?.int("open_issues_count") ?: previous.openIssues
            val forks = body?.int("forks_count") ?: previous.forks
            val watchers = body?.int("subscribers_count") ?: previous.watchers
            val pushedAt = body?.string("pushed_at") ?: previous.pushedAt
            val repoEtag = repoCall.etag.ifBlank { previous.repoEtag }

            // ---- issues and pull requests ------------------------------------
            var issues = emptyList<GitHubItem>()
            var issuesChanged = false
            var issuesEtag = previous.issuesEtag
            if (watch.notifyOnIssues || watch.watchPullRequests) {
                val url = "$apiBase/repos/${repo.owner}/${repo.name}/issues" +
                    "?state=open&sort=created&direction=desc&per_page=$ITEMS_PER_PAGE"
                val answer = call(client, url, previous.issuesEtag, token)
                answer.rate?.let { rate = it }
                when (answer) {
                    is Answer.Limited -> return@withContext limited(previous, answer, rate)
                    is Answer.Ok -> {
                        issues = parseItems(answer.array)
                        issuesChanged = true
                        issuesEtag = answer.etag.ifBlank { previous.issuesEtag }
                    }
                    // A 304 preserves everything: no items, no change, same ETag.
                    is Answer.NotModified -> issuesEtag = answer.etag.ifBlank { previous.issuesEtag }
                    // One endpoint failing does not invalidate the others. The
                    // repository answered, so the check has a verdict; the issue
                    // track simply learns nothing this time round.
                    is Answer.Failed -> Unit
                }
            }

            // ---- releases and their downloads ---------------------------------
            //
            // One request serves both tracks, and that is the whole reason the
            // download counts cost nothing on an ordinary repository. The counts
            // live inside the release payload, so asking for the list at a larger
            // page size is the same call the release watcher was making anyway.
            //
            // The budget floor and the refresh gate below decide whether the wide
            // form is affordable this time; when it is not, the narrow form still
            // answers the release watcher and the counts keep their last reading.
            var release: GitHubRelease? = null
            var releaseChanged = false
            var releasesEtag = previous.releasesEtag
            var downloads: DownloadReading? = null
            var assetTypes = emptyList<String>()
            // The saver holds the wide call to once an hour whatever the refresh
            // setting says. It is about 51 KB compressed for this repository and
            // never answers 304, because the counts move on their own.
            val saverHolds = settings.dataSaver &&
                nowMs() - previous.downloadsReadAt in 0 until DataSaver.DOWNLOADS_FLOOR_MS
            val downloadsDue = watch.trackDownloads &&
                (force || (nowMs() >= previous.downloadsRetryAt && !saverHolds)) &&
                (rate.remaining < 0 || rate.remaining >= DOWNLOADS_BUDGET_FLOOR)
            if (watch.watchReleases || downloadsDue) {
                // Three shapes, in order of what they cost. `releases/latest`
                // skips drafts and prereleases outright, which is the right
                // answer nearly always and the wrong one for somebody watching a
                // beta channel or counting files across every tag.
                val base = "$apiBase/repos/${repo.owner}/${repo.name}/releases"
                val url = when {
                    downloadsDue -> "$base?per_page=$DOWNLOADS_PER_PAGE"
                    watch.includePrereleases -> "$base?per_page=$RELEASES_PER_PAGE"
                    else -> "$base/latest"
                }
                val answer = call(client, url, previous.releasesEtag, token)
                answer.rate?.let { rate = it }
                when (answer) {
                    is Answer.Limited -> return@withContext limited(previous, answer, rate)
                    is Answer.Ok -> {
                        val listed = if (url.endsWith("/latest")) {
                            listOfNotNull(answer.body?.let(::parseRelease))
                        } else {
                            parseReleases(answer.array)
                        }
                        release = listed.firstOrNull { !it.draft }
                        // Learned whether or not anybody is counting, because
                        // the payload carries them either way and the setup
                        // screen needs them before counting is switched on.
                        assetTypes = GitHubDownloads.extensionsIn(listed)
                        releaseChanged = true
                        releasesEtag = answer.etag.ifBlank { previous.releasesEtag }
                        if (downloadsDue) {
                            val walked = walkDownloads(
                                client = client,
                                token = token,
                                watch = watch,
                                firstPage = listed,
                                nextPage = answer.nextPage,
                                rateIn = rate,
                            )
                            downloads = walked.reading
                            rate = walked.rate
                        }
                    }
                    is Answer.NotModified -> releasesEtag = answer.etag.ifBlank { previous.releasesEtag }
                    is Answer.Failed ->
                        // 404 here is "no releases yet", which is a fact about the
                        // repository rather than a failure to read it. It is also
                        // a real download reading: a repository with no releases
                        // has no downloads, and zero is the honest answer.
                        if (answer.code == 404) {
                            releaseChanged = true
                            if (downloadsDue) downloads = DownloadReading.EMPTY
                        }
                }
            }

            // ---- comments on issues ------------------------------------------
            //
            // Last of the four on purpose. An unauthenticated device gets 60 an
            // hour for every app behind its address, so a fleet of repository
            // monitors lives close to the ceiling and something has to be the
            // endpoint that yields. It is this one: the other three shipped in
            // 3.2.0 and nobody asked for them to get quieter.
            var comments = emptyList<GitHubComment>()
            var commentsChanged = false
            var commentsAnswered = false
            var commentsRefusedCode = 0
            var commentsEtag = previous.commentsEtag
            val commentsDue = force || nowMs() >= previous.commentsRetryAt
            val budgetLeft = rate.remaining < 0 || rate.remaining >= COMMENTS_BUDGET_FLOOR
            if (watch.notifyOnComments && commentsDue && budgetLeft) {
                // One request, and deliberately never a second one. A bounded
                // page walk reads as the safe choice and is not: page two of a
                // newest-first list holds only rows older than every row on page
                // one, and the decider announces the newest few above the
                // watermark, so a page-two row can only matter when page one had
                // already reached back past the previous poll. The walk would fire
                // exactly where its results are guaranteed to be discarded, and
                // `page=2` is a separate resource whose ETag has nowhere to live.
                //
                // No `since` either: it filters on `updated_at`, so it drags
                // years-old comments back up every time somebody edits one.
                val url = "$apiBase/repos/${repo.owner}/${repo.name}/issues/comments" +
                    "?sort=created&direction=desc&per_page=$COMMENTS_PER_PAGE"
                val answer = call(client, url, previous.commentsEtag, token)
                answer.rate?.let { rate = it }
                when (answer) {
                    // Not `limited()`. Being refused the last of four requests
                    // must not throw away the stars, issues and releases this
                    // pass already read, which is what discarding the poll would
                    // do to every check once a device sits near its ceiling.
                    // The reset clock is still recorded, because a secondary
                    // limit has its own and walking back into it costs the hour.
                    is Answer.Limited -> {
                        rate = rate.copy(resetAt = answer.retryAt ?: rate.resetAt)
                    }
                    is Answer.Ok -> {
                        comments = parseComments(answer.array)
                        commentsChanged = true
                        commentsAnswered = true
                        commentsEtag = answer.etag.ifBlank { previous.commentsEtag }
                    }
                    is Answer.NotModified -> {
                        commentsAnswered = true
                        commentsEtag = answer.etag.ifBlank { previous.commentsEtag }
                    }
                    // Deliberately not the releases rule, where a 404 counts as
                    // having looked. `releases/latest` answers with at most one
                    // release, so seeding off its absence can only ever announce
                    // one genuinely new thing; this endpoint answers with a
                    // hundred, and seeding off a refusal would hand the first
                    // working response a watermark of zero and a page of old
                    // conversation to call news.
                    is Answer.Failed ->
                        if (answer.code == 403 || answer.code == 404 || answer.code == 410) {
                            commentsRefusedCode = answer.code
                        }
                }
            }

            val snapshot = GitHubSnapshot(
                stars = stars,
                openIssues = openIssues,
                forks = forks,
                watchers = watchers,
                pushedAt = pushedAt,
                repoChanged = repoChanged,
                issues = issues,
                issuesChanged = issuesChanged,
                release = release,
                releaseChanged = releaseChanged,
                downloads = downloads,
                assetTypes = assetTypes,
                comments = comments,
                commentsChanged = commentsChanged,
                commentsAnswered = commentsAnswered,
                commentsRefusedCode = commentsRefusedCode,
                etags = GitHubEtags(
                    repo = repoEtag,
                    issues = issuesEtag,
                    releases = releasesEtag,
                    comments = commentsEtag,
                ),
                rate = rate,
            )

            // What this check saw, for the history to difference later.
            //
            // Carried forward from [previous] wherever the endpoint answered 304 or
            // was not asked at all, because a sample that recorded "unknown" for a
            // count that simply had not changed would read as the repository losing
            // its stars and getting them back.
            val newestIssue = issues.filter { !it.isPullRequest }.maxByOrNull { it.number }
            // Never the first row: sorting by creation does not tie-break by id,
            // so a page is not reliably ordered by id even when it looks it.
            val newestComment = comments.maxByOrNull { it.id }
            val facts = RepoFacts(
                stars = stars,
                openIssues = openIssues,
                forks = forks,
                releaseTag = release?.tag ?: previous.lastReleaseTag,
                issueNumber = newestIssue?.number?.coerceAtLeast(previous.lastIssueNumber)
                    ?: previous.lastIssueNumber,
                issueTitle = if (newestIssue != null && newestIssue.number >= previous.lastIssueNumber) {
                    newestIssue.title
                } else {
                    previous.lastIssueTitle
                },
                pushedAt = githubInstantMs(pushedAt),
                // The newest comment the repository has, whatever the toggles
                // say, because this list is a record of the repository and not of
                // what was announced. Carried forward from the watermarks when
                // the endpoint answered 304 or was never asked, so a sample
                // cannot read as the repository losing a comment and regaining
                // it. Only the id has to survive: the issue number and the author
                // are read on the poll the value rises, and that poll has the
                // page in hand.
                commentId = newestComment?.id
                    ?: maxOf(previous.lastIssueCommentId, previous.lastPullCommentId),
                commentIssue = newestComment?.issueNumber ?: 0,
                commentAuthor = newestComment?.author.orEmpty(),
                // Carried forward on a poll that did not ask, exactly as the
                // comment id above is, so the history cannot read as the
                // repository losing its downloads and regaining them on the
                // next wide call.
                latestDownloads = downloads?.latest ?: previous.latestDownloads,
                totalDownloads = downloads?.total ?: previous.totalDownloads,
                downloadsComplete = downloads?.complete ?: previous.totalComplete,
            )

            Outcome(
                result = CheckResult(
                    ok = true,
                    latencyMs = latency,
                    statusCode = if (repoChanged) 200 else 304,
                    message = summary(stars, openIssues, repoChanged),
                    repo = facts,
                    detail = buildString {
                        append(repo.slug)
                        if (pushedAt.isNotBlank()) append(" · pushed ").append(pushedAt)
                        if (rate.remaining >= 0) {
                            append(" · ").append(rate.remaining)
                            if (rate.limit > 0) append(" of ").append(rate.limit)
                            append(" API calls left")
                        }
                    },
                    at = nowMs(),
                ),
                snapshot = snapshot,
                state = previous,
            )
        }

    private fun summary(stars: Int, openIssues: Int, changed: Boolean): String = buildString {
        append(stars).append(if (stars == 1) " star" else " stars")
        append(" · ").append(openIssues).append(if (openIssues == 1) " open issue" else " open issues")
        if (!changed) append(" · unchanged")
    }

    private fun limited(previous: GitHubState, answer: Answer.Limited, rate: GitHubRate) = Outcome(
        result = null,
        state = previous.copy(
            rateRemaining = rate.remaining,
            rateLimit = rate.limit,
            // A `Retry-After` is the authority when there is one: a secondary
            // limit has its own clock and does not touch the primary counters.
            rateResetAt = answer.retryAt ?: rate.resetAt,
            rateLimited = true,
            lastRateLimitAt = nowMs(),
            lastPolledAt = nowMs(),
        ),
        rateLimited = true,
    )

    // ---- one request ---------------------------------------------------------

    private sealed interface Answer {
        val etag: String
        val rate: GitHubRate?

        data class Ok(
            val body: JsonObject?,
            val array: JsonArray?,
            override val etag: String,
            override val rate: GitHubRate?,
            /** `rel="next"` out of the Link header, or blank when this is the last page. */
            val nextPage: String = "",
        ) : Answer

        data class NotModified(override val etag: String, override val rate: GitHubRate?) : Answer

        data class Limited(
            val retryAt: Long?,
            override val etag: String,
            override val rate: GitHubRate?,
        ) : Answer

        data class Failed(
            val code: Int,
            val kind: FailureKind,
            val message: String,
            val detail: String,
            override val etag: String = "",
            override val rate: GitHubRate? = null,
        ) : Answer {
            fun toResult(at: Long, token: String, prefix: String) = CheckResult(
                ok = false,
                latencyMs = 0,
                statusCode = code,
                failureKind = kind,
                message = Secrets.scrub(message, token),
                detail = Secrets.scrub("$prefix: $detail", token),
                at = at,
            )
        }
    }

    private suspend fun call(
        client: OkHttpClient,
        url: String,
        etag: String,
        token: String,
    ): Answer {
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", API_VERSION)
            .header("User-Agent", HttpChecker.USER_AGENT)
            .apply {
                if (etag.isNotBlank()) header("If-None-Match", etag)
                // The one place the token is used, and it never leaves this line:
                // no logging here, and every message that reaches the UI goes
                // through Secrets.scrub on the way out.
                if (token.isNotBlank()) header("Authorization", "Bearer $token")
            }
            .build()

        return queue.withLock {
            spaceOutCalls()
            try {
                client.newCall(request).execute().use { response -> read(response) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                Answer.Failed(
                    code = 0,
                    kind = classify(error),
                    message = describe(error),
                    detail = "${error::class.java.simpleName}: ${error.message ?: "no message"}",
                )
            } finally {
                lastCallAt = nowMs()
            }
        }
    }

    /** Keeps two calls at least [minGapMs] apart, however many monitors are due. */
    private suspend fun spaceOutCalls() {
        if (minGapMs <= 0L) return
        val since = nowMs() - lastCallAt
        if (lastCallAt > 0L && since in 0 until minGapMs) delay(minGapMs - since)
    }

    private fun read(response: Response): Answer {
        val etag = response.header("ETag").orEmpty()
        val rate = rateOf(response)
        val retryAfter = response.header("Retry-After")?.trim()?.toLongOrNull()

        if (response.code == 304) return Answer.NotModified(etag, rate)

        // The two shapes of "you have asked too often". A 403 is also how GitHub
        // says "your token may not read this", so the counter decides which one
        // this is: no budget left, or a `Retry-After`, means the limiter. Anything
        // else with budget remaining is a permissions answer and has to be
        // reported as a real problem rather than hidden behind a rate-limit chip.
        val outOfBudget = rate != null && rate.remaining == 0
        if (response.code == 429 || (response.code == 403 && (outOfBudget || retryAfter != null))) {
            val retryAt = retryAfter?.let { nowMs() + it * 1_000 }
            return Answer.Limited(retryAt, etag, rate)
        }

        if (!response.isSuccessful) {
            return Answer.Failed(
                code = response.code,
                kind = if (response.code in 400..499) FailureKind.BAD_CONFIG else FailureKind.STATUS,
                message = messageFor(response.code),
                detail = "HTTP ${response.code} ${response.message}",
                etag = etag,
                rate = rate,
            )
        }

        val text = runCatching { response.body.string() }.getOrDefault("")
        val element = runCatching { json.parseToJsonElement(text) }.getOrNull()
            ?: return Answer.Failed(
                code = response.code,
                kind = FailureKind.BODY,
                message = "GitHub sent something that isn't JSON",
                detail = text.take(200),
                etag = etag,
                rate = rate,
            )
        return Answer.Ok(
            body = element as? JsonObject,
            array = element as? JsonArray,
            etag = etag,
            rate = rate,
            nextPage = nextPageOf(response.header("Link")),
        )
    }

    /**
     * The `rel="next"` URL out of a Link header, or blank.
     *
     * Parsed rather than constructed, because the page after this one is
     * GitHub's opinion and not arithmetic on a page number: it carries the
     * cursor and the page size already agreed, and rebuilding it by hand is how
     * a walk silently re-reads page one forever.
     */
    private fun nextPageOf(link: String?): String {
        if (link.isNullOrBlank()) return ""
        return link.split(',')
            .firstOrNull { it.contains("rel=\"next\"") }
            ?.substringAfter('<', "")
            ?.substringBefore('>', "")
            ?.trim()
            .orEmpty()
    }

    private fun messageFor(code: Int): String = when (code) {
        401 -> "GitHub rejected the token"
        403 -> "GitHub refused the request"
        404 -> "Repository not found, or not visible to this token"
        451 -> "Repository unavailable for legal reasons"
        in 500..599 -> "GitHub is having trouble (HTTP $code)"
        else -> "GitHub answered HTTP $code"
    }

    private fun rateOf(response: Response): GitHubRate? {
        val remaining = response.header("x-ratelimit-remaining")?.trim()?.toIntOrNull() ?: return null
        val limit = response.header("x-ratelimit-limit")?.trim()?.toIntOrNull() ?: 0
        // Sent in epoch seconds, which is not what anything else in this app uses.
        val reset = response.header("x-ratelimit-reset")?.trim()?.toLongOrNull()?.times(1_000) ?: 0L
        return GitHubRate(remaining, limit, reset)
    }

    private fun classify(error: Throwable): FailureKind = when (error) {
        is UnknownHostException -> FailureKind.DNS
        is SocketTimeoutException, is InterruptedIOException -> FailureKind.TIMEOUT
        is SSLException -> FailureKind.TLS
        is ConnectException -> FailureKind.CONNECT
        is IllegalArgumentException -> FailureKind.BAD_CONFIG
        is IOException -> FailureKind.CONNECT
        else -> FailureKind.UNKNOWN
    }

    private fun describe(error: Throwable): String = when (classify(error)) {
        FailureKind.DNS -> "Can't reach api.github.com"
        FailureKind.TIMEOUT -> "GitHub didn't answer in time"
        FailureKind.TLS -> "TLS/certificate error talking to GitHub"
        FailureKind.CONNECT -> "Connection to GitHub refused or dropped"
        else -> error.message ?: "Unexpected failure"
    }

    private fun socksProxy(route: ProxyRoute.Endpoint): Proxy =
        Proxy(Proxy.Type.SOCKS, InetSocketAddress(route.host, route.port))

    // ---- parsing --------------------------------------------------------------

    private fun parseItems(array: JsonArray?): List<GitHubItem> {
        if (array == null) return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj.long("id") ?: return@mapNotNull null
            GitHubItem(
                id = id,
                number = obj.int("number") ?: 0,
                title = obj.string("title").orEmpty(),
                body = obj.string("body").orEmpty(),
                author = (obj["user"] as? JsonObject)?.string("login").orEmpty(),
                createdAt = obj.string("created_at").orEmpty(),
                url = obj.string("html_url").orEmpty(),
                isPullRequest = obj["pull_request"] != null,
            )
        }
    }

    private fun parseComments(array: JsonArray?): List<GitHubComment> {
        if (array == null) return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj.long("id") ?: return@mapNotNull null
            val user = obj["user"] as? JsonObject
            val login = user?.string("login").orEmpty()
            val htmlUrl = obj.string("html_url").orEmpty()
            GitHubComment(
                id = id,
                // The payload has no number field. `issue_url` uses the shared
                // number space, so this is right for a pull request parent too.
                issueNumber = obj.string("issue_url")?.substringAfterLast('/')?.toIntOrNull() ?: 0,
                author = login,
                body = obj.string("body").orEmpty(),
                url = htmlUrl,
                onPullRequest = isPullThread(htmlUrl),
                // Present on every row with a null value, so a bare presence
                // check reads every comment on GitHub as hidden and the track
                // then announces nothing at all, silently.
                minimized = obj["minimized"].isPresent(),
                isApp = user?.string("type") == "Bot" ||
                    // Same shape as `minimized`, failing the other way: read as a
                    // presence check it marks every comment as an App, and with
                    // bot comments off the track is silent again.
                    obj["performed_via_github_app"].isPresent() ||
                    user?.string("node_id")?.startsWith("BOT_") == true ||
                    login.endsWith("[bot]") ||
                    user?.string("html_url")?.contains("/apps/") == true,
            )
        }
    }

    /**
     * Whether a comment's `html_url` points at a pull request thread.
     *
     * Tests the path segment rather than searching for "/pull/", because
     * github.com/wei/pull is a real repository whose issue comments live at
     * /wei/pull/issues/1#issuecomment-393287464. A substring test reads that as a
     * pull request and silently drops every comment for anyone watching it.
     */
    private fun isPullThread(htmlUrl: String): Boolean =
        htmlUrl.substringAfter("github.com/", "")
            .split('/')
            .let { it.size > 2 && it[2] == "pull" }

    /** A [DownloadReading] and the budget left after taking it. */
    private data class Walked(val reading: DownloadReading, val rate: GitHubRate)

    /**
     * Sums the release files, following Link headers under a cap.
     *
     * The comment track's rule, one request and deliberately never a second,
     * cannot apply here: page two of a release list holds real downloads, and
     * dropping it produces a number that is wrong rather than partial. So the
     * walk continues, and what stops it is recorded instead of hidden.
     *
     * It stops for three reasons, and all three mean the same thing to the
     * caller. The cap is reached, GitHub stops offering a next page, or the
     * budget falls to the floor, which is the one that protects the other
     * monitors: a five hundred release repository must not be able to spend an
     * unauthenticated device's whole hour on its own back catalogue.
     */
    private suspend fun walkDownloads(
        client: OkHttpClient,
        token: String,
        watch: GitHubWatch,
        firstPage: List<GitHubRelease>,
        nextPage: String,
        rateIn: GitHubRate,
    ): Walked {
        val filters = watch.downloadFilters
        val gathered = firstPage.toMutableList()
        var rate = rateIn
        var next = nextPage
        var pages = 1
        var complete = next.isBlank()

        while (watch.downloadsAcrossAllReleases && next.isNotBlank()) {
            if (pages >= DOWNLOADS_PAGE_CAP) break
            if (rate.remaining in 0 until DOWNLOADS_BUDGET_FLOOR) break
            // No ETag on the tail pages. One is held for this endpoint and it
            // belongs to page one; sending it against page two would compare a
            // validator to a body it was never computed from, and a 304 there
            // would silently drop a page of releases from the total.
            val answer = call(client, next, "", token)
            answer.rate?.let { rate = it }
            if (answer !is Answer.Ok) break
            gathered += parseReleases(answer.array)
            next = answer.nextPage
            pages++
            complete = next.isBlank()
        }

        val counted = if (watch.downloadsAcrossAllReleases) gathered else gathered.take(1)
        val newest = gathered.firstOrNull { !it.draft }
        val reading = DownloadReading(
            latest = newest?.let { GitHubDownloads.sum(it.assets, filters) } ?: 0,
            total = GitHubDownloads.total(counted, filters),
            releases = counted.count { !it.draft },
            // A latest-only reading is always complete: it covers exactly the
            // one release it claims to, whatever the rest of the list does.
            complete = if (watch.downloadsAcrossAllReleases) complete else true,
            byFile = GitHubDownloads.group(counted, filters),
        )
        return Walked(reading, rate)
    }

    private fun parseReleases(array: JsonArray?): List<GitHubRelease> {
        if (array == null) return emptyList()
        return array.mapNotNull { (it as? JsonObject)?.let(::parseRelease) }
    }

    private fun parseRelease(obj: JsonObject): GitHubRelease? {
        val id = obj.long("id") ?: return null
        return GitHubRelease(
            id = id,
            tag = obj.string("tag_name").orEmpty(),
            name = obj.string("name").orEmpty(),
            url = obj.string("html_url").orEmpty(),
            prerelease = obj.bool("prerelease") ?: false,
            draft = obj.bool("draft") ?: false,
            publishedAt = obj.string("published_at").orEmpty(),
            assets = parseAssets(obj["assets"] as? JsonArray),
        )
    }

    /**
     * The files on one release.
     *
     * An asset with no name is dropped rather than counted under a blank one:
     * every reader downstream groups by name, and a nameless row would collect
     * every malformed asset in the repository into one meaningless total. A
     * missing `download_count` reads as zero, which is what an asset nobody has
     * fetched reports anyway.
     */
    private fun parseAssets(array: JsonArray?): List<GitHubAsset> {
        if (array == null) return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val name = obj.string("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            GitHubAsset(name = name, downloads = obj.int("download_count") ?: 0)
        }
    }

    companion object {
        const val API_BASE = "https://api.github.com"
        const val API_VERSION = "2022-11-28"

        /**
         * Newest issues per poll.
         *
         * Enough that a busy quarter of an hour is covered without a second page,
         * small enough that the response stays cheap to read on mobile data.
         */
        const val ITEMS_PER_PAGE = 20
        const val RELEASES_PER_PAGE = 10

        /**
         * Releases per page when the download counts are being read.
         *
         * GitHub's maximum, and the reason is arithmetic rather than greed: the
         * counts only exist in the release payload, so a smaller page means a
         * second request to reach releases the first one left out. This
         * repository's 38 tags fit in one call at this size.
         */
        const val DOWNLOADS_PER_PAGE = 100

        /**
         * Pages the download walk will follow before it stops and says so.
         *
         * Three hundred releases, which covers all but a handful of projects.
         * Past it the total is reported as a floor rather than quietly short.
         */
        const val DOWNLOADS_PAGE_CAP = 3

        /**
         * Requests that must remain before the download call is considered.
         *
         * The same guard [COMMENTS_BUDGET_FLOOR] applies to the comment track,
         * for the same reason: a wide call on a device near its hourly ceiling
         * must not take another monitor's repository call down with it.
         */
        const val DOWNLOADS_BUDGET_FLOOR = 3

        /**
         * Comments per poll, and the reason it is the maximum GitHub allows.
         *
         * The server does not filter this list. On one page of a hundred from a
         * busy repository, 76 rows were pull request conversation and 31 were
         * bots, which put the fifth comment on an actual issue at row 44. Twenty
         * rows would have found two of them. The page is a ceiling rather than a
         * fixed cost, so a quiet repository still pays for what it has.
         */
        const val COMMENTS_PER_PAGE = 100

        /**
         * Requests that must be left when the comment call is considered.
         *
         * One of headroom, so the last of four on a device near its hourly
         * ceiling cannot take another monitor's repository call down with it.
         */
        const val COMMENTS_BUDGET_FLOOR = 2

        /** Shortest gap between two calls, so a due fleet never bursts. */
        const val MIN_GAP_MS = 350L
    }
}

// ---- small JSON readers ------------------------------------------------------
//
// Hand-rolled rather than a dozen @Serializable DTOs: GitHub's payloads are large
// and mostly irrelevant here, and a data class per endpoint would be a lot of
// surface for the eight fields this app actually reads.

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.content?.toIntOrNull()

private fun JsonObject.long(key: String): Long? =
    (this[key] as? JsonPrimitive)?.content?.toLongOrNull()

private fun JsonObject.bool(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

/**
 * A key that is there and carries something.
 *
 * GitHub sends `minimized` and `performed_via_github_app` on every comment with a
 * null value, so `!= null` on the element is true for all of them and answers the
 * opposite of the question anyone is asking.
 */
private fun JsonElement?.isPresent(): Boolean = this != null && this !is JsonNull
