package me.river.nightbell.data.check

import me.river.nightbell.domain.CheckResult
import me.river.nightbell.domain.FailureKind
import me.river.nightbell.domain.GitHubEtags
import me.river.nightbell.domain.GitHubItem
import me.river.nightbell.domain.GitHubRate
import me.river.nightbell.domain.GitHubRelease
import me.river.nightbell.domain.GitHubSnapshot
import me.river.nightbell.domain.GitHubState
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.ProxyRoute
import me.river.nightbell.domain.Secrets
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
    suspend fun poll(monitor: Monitor, previous: GitHubState): Outcome =
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

            // ---- releases -----------------------------------------------------
            var release: GitHubRelease? = null
            var releaseChanged = false
            var releasesEtag = previous.releasesEtag
            if (watch.watchReleases) {
                // `releases/latest` skips drafts and prereleases outright, which is
                // the right answer nearly always and the wrong one for somebody
                // watching a beta channel. That case lists instead.
                val url = if (watch.includePrereleases) {
                    "$apiBase/repos/${repo.owner}/${repo.name}/releases?per_page=$RELEASES_PER_PAGE"
                } else {
                    "$apiBase/repos/${repo.owner}/${repo.name}/releases/latest"
                }
                val answer = call(client, url, previous.releasesEtag, token)
                answer.rate?.let { rate = it }
                when (answer) {
                    is Answer.Limited -> return@withContext limited(previous, answer, rate)
                    is Answer.Ok -> {
                        release = if (watch.includePrereleases) {
                            parseReleases(answer.array).firstOrNull { !it.draft }
                        } else {
                            answer.body?.let(::parseRelease)
                        }
                        releaseChanged = true
                        releasesEtag = answer.etag.ifBlank { previous.releasesEtag }
                    }
                    is Answer.NotModified -> releasesEtag = answer.etag.ifBlank { previous.releasesEtag }
                    is Answer.Failed ->
                        // 404 here is "no releases yet", which is a fact about the
                        // repository rather than a failure to read it.
                        if (answer.code == 404) releaseChanged = true
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
                etags = GitHubEtags(repo = repoEtag, issues = issuesEtag, releases = releasesEtag),
                rate = rate,
            )

            Outcome(
                result = CheckResult(
                    ok = true,
                    latencyMs = latency,
                    statusCode = if (repoChanged) 200 else 304,
                    message = summary(stars, openIssues, repoChanged),
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
        )
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
        )
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
