package me.river.nightbell.domain

/**
 * Inline form validation for the monitor setup flow. Produces per-field notes so
 * the UI can show precise, friendly guidance instead of one generic error.
 */
object Validation {

    enum class Severity { HINT, WARNING, ERROR }

    enum class Field {
        NAME, URL, METHOD, HEADERS, BODY, STATUS, ASSERTION, JSON_PATH,
        INTERVAL, TIMEOUT, ELEMENT, ELEMENT_TEXT, LATENCY_SLO, URGENT, PROXY,
        REPO, GITHUB, TOKEN,
    }

    /** Past this many watched elements the settle loop is worth warning about. */
    private const val MANY_ELEMENTS = 8

    data class Note(val field: Field, val severity: Severity, val message: String)

    data class Report(val notes: List<Note>) {
        fun of(field: Field): Note? =
            notes.filter { it.field == field }.minByOrNull { -it.severity.ordinal }

        val errors: List<Note> get() = notes.filter { it.severity == Severity.ERROR }
        val isValid: Boolean get() = errors.isEmpty()
        val blockingMessage: String? get() = errors.firstOrNull()?.message
    }

    private val schemeRegex = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
    private val hostRegex = Regex("^[a-zA-Z0-9]([a-zA-Z0-9._-]*[a-zA-Z0-9])?(:\\d{1,5})?$")
    private val headerNameRegex = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")

    fun urlNote(raw: String): Note? {
        val url = raw.trim()
        if (url.isEmpty()) return Note(Field.URL, Severity.ERROR, "A URL is required")
        if (url.contains(' ')) return Note(Field.URL, Severity.ERROR, "URLs can't contain spaces")
        if (!schemeRegex.containsMatchIn(url)) {
            return Note(Field.URL, Severity.ERROR, "Start with http:// or https://")
        }
        val scheme = url.substringBefore("://").lowercase()
        if (scheme != "http" && scheme != "https") {
            return Note(Field.URL, Severity.ERROR, "Only http and https are supported")
        }
        val afterScheme = url.substringAfter("://")
        val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.isEmpty()) return Note(Field.URL, Severity.ERROR, "Missing host name")
        val hostOnly = authority.substringBefore('@').let { if (authority.contains('@')) authority.substringAfter('@') else it }
        if (!hostRegex.matches(hostOnly)) {
            return Note(Field.URL, Severity.ERROR, "\"$hostOnly\" doesn't look like a valid host")
        }
        if (scheme == "http") {
            // A hidden service is encrypted by the circuit that carries it, so the
            // usual plain-http warning would be telling the user something untrue.
            if (ProxyRoute.isHiddenService(url)) return null
            return Note(Field.URL, Severity.WARNING, "Plain http — traffic isn't encrypted")
        }
        return null
    }

    /**
     * What is wrong with a repository someone has typed, if anything.
     *
     * Separate from [urlNote] because the two answer different questions. A
     * GitHub monitor's URL is derived rather than entered: what the user types is
     * `owner/repo` or something that contains it, and "start with https://" would
     * be advice about a field they were never shown.
     */
    fun repoNote(raw: String): Note? {
        val text = raw.trim()
        if (text.isEmpty()) return Note(Field.REPO, Severity.ERROR, "A repository is required")
        if (GitHubRepo.parse(text) != null) return null
        return Note(
            Field.REPO,
            Severity.ERROR,
            "Use owner/repo, or paste any github.com link to the repository",
        )
    }

    fun report(monitor: Monitor): Report {
        val notes = mutableListOf<Note>()

        if (monitor.kind == MonitorKind.GITHUB_REPO) {
            reportGitHub(monitor, notes)
        } else {
            urlNote(monitor.url)?.let { notes += it }
        }

        if (monitor.name.isBlank()) {
            notes += Note(Field.NAME, Severity.HINT, "Optional — we'll use the host name")
        } else if (monitor.name.length > 48) {
            notes += Note(Field.NAME, Severity.WARNING, "Long names get truncated on the dashboard")
        }

        // Headers
        val nonEmptyHeaders = monitor.headers.filterNot { it.isBlank }
        nonEmptyHeaders.forEachIndexed { _, h ->
            if (h.name.isBlank()) {
                notes += Note(Field.HEADERS, Severity.ERROR, "A header has a value but no name")
            } else if (!headerNameRegex.matches(h.name.trim())) {
                notes += Note(Field.HEADERS, Severity.ERROR, "\"${h.name}\" isn't a valid header name")
            }
        }
        val dupes = nonEmptyHeaders.groupBy { it.name.trim().lowercase() }.filter { it.value.size > 1 }.keys
        if (dupes.isNotEmpty()) {
            notes += Note(Field.HEADERS, Severity.WARNING, "Duplicate header: ${dupes.first()}")
        }

        // Body
        if (monitor.body.isNotBlank() && !monitor.method.allowsBody) {
            notes += Note(Field.BODY, Severity.WARNING, "${monitor.method} requests ignore the body")
        }
        if (monitor.body.isNotBlank() && monitor.contentType.contains("json", ignoreCase = true)) {
            if (Assertions.parseJson(monitor.body) == null) {
                notes += Note(Field.BODY, Severity.WARNING, "Body isn't valid JSON — sending it as-is")
            }
        }

        // Status expectation. A GitHub monitor has no expectation to state: the
        // API answers 200 or it does not, and what the check is *for* is the
        // difference between two answers rather than any one of them.
        if (monitor.kind == MonitorKind.GITHUB_REPO) return Report(notes + cadenceNotes(monitor))
        when (monitor.status.mode) {
            StatusMode.EXACT -> if (monitor.status.code !in 100..599) {
                notes += Note(Field.STATUS, Severity.ERROR, "Status codes run from 100 to 599")
            }
            StatusMode.RANGE -> {
                if (monitor.status.rangeStart !in 100..599 || monitor.status.rangeEnd !in 100..599) {
                    notes += Note(Field.STATUS, Severity.ERROR, "Range must stay within 100–599")
                } else if (monitor.status.rangeStart > monitor.status.rangeEnd) {
                    notes += Note(Field.STATUS, Severity.WARNING, "Range is reversed — we'll swap it")
                }
            }
            StatusMode.ANY -> notes += Note(
                Field.STATUS, Severity.HINT, "Only connectivity is checked — any code passes",
            )
            StatusMode.ANY_SUCCESS -> Unit
        }

        // Body assertion
        val assertion = monitor.assertion
        if (assertion.mode.needsValue && assertion.value.isBlank()) {
            notes += Note(Field.ASSERTION, Severity.ERROR, "${assertion.mode.label} needs a value")
        }
        if (assertion.mode == AssertionMode.REGEX && assertion.value.isNotBlank()) {
            val bad = runCatching { Regex(assertion.value) }.isFailure
            if (bad) notes += Note(Field.ASSERTION, Severity.ERROR, "That regular expression doesn't compile")
        }
        if (assertion.mode.needsPath) {
            if (assertion.jsonPath.isBlank()) {
                notes += Note(Field.JSON_PATH, Severity.ERROR, "Add a JSON path like data.status")
            } else if (!Regex("^[A-Za-z0-9_$\\[\\]. -]+$").matches(assertion.jsonPath)) {
                notes += Note(Field.JSON_PATH, Severity.WARNING, "Unusual characters in the path")
            }
        }

        // Reaching a hidden service at all.
        //
        // There is no public DNS record behind a .onion or .i2p name, so a direct
        // check cannot get past the lookup: it reports "can't resolve", which is
        // true and completely unhelpful. Worth saying at setup time rather than
        // letting the first check say it badly.
        if (ProxyRoute.isHiddenService(monitor.url) && !monitor.useProxy) {
            notes += Note(
                Field.URL, Severity.WARNING,
                "Only reachable through a SOCKS5 proxy. Set one up in Settings, then route this monitor through it.",
            )
        }
        if (monitor.useProxy && monitor.kind == MonitorKind.WEBSITE_ELEMENT) {
            notes += Note(
                Field.PROXY, Severity.HINT,
                "The page is loaded through the proxy. Routed page loads run one at a time, " +
                    "because the WebView setting is shared by the whole app.",
            )
        }

        // Cadence
        if (monitor.intervalMinutes < 1) {
            notes += Note(Field.INTERVAL, Severity.ERROR, "Interval must be at least 1 minute")
        } else if (monitor.intervalMinutes < 15) {
            notes += Note(
                Field.INTERVAL, Severity.HINT,
                "Android may batch background work; sub-15-minute checks are best-effort",
            )
        }
        if (monitor.timeoutSeconds < 1 || monitor.timeoutSeconds > 120) {
            notes += Note(Field.TIMEOUT, Severity.ERROR, "Timeout must be 1–120 seconds")
        } else if (monitor.timeoutSeconds > monitor.intervalMinutes * 60) {
            notes += Note(Field.TIMEOUT, Severity.WARNING, "Timeout is longer than the check interval")
        }

        // Latency SLO
        if (monitor.latencySloMs != 0) {
            if (monitor.latencySloMs < 0) {
                notes += Note(Field.LATENCY_SLO, Severity.ERROR, "A latency budget can't be negative")
            } else if (monitor.latencySloMs > monitor.timeoutSeconds * 1000) {
                notes += Note(
                    Field.LATENCY_SLO, Severity.WARNING,
                    "Budget is longer than the timeout — the check fails before it can go degraded",
                )
            }
        }

        // Urgent
        if (monitor.urgent && monitor.urgentRepeatMinutes < 1) {
            notes += Note(Field.URGENT, Severity.ERROR, "Urgent repeats must be at least a minute apart")
        }

        // Element monitor — one page load, N assertions.
        if (monitor.kind == MonitorKind.WEBSITE_ELEMENT) {
            val elements = monitor.targets
            if (elements.isEmpty()) {
                notes += Note(Field.ELEMENT, Severity.ERROR, "Open the preview and tap an element to watch")
            }
            elements.forEachIndexed { index, element ->
                val where = if (elements.size == 1) "" else " (${element.displayLabel})"
                if (element.mode == ElementMode.TEXT_EQUALS || element.mode == ElementMode.TEXT_CONTAINS) {
                    if (element.expectedText.isBlank()) {
                        notes += Note(
                            Field.ELEMENT_TEXT, Severity.ERROR,
                            "${element.mode.label}$where needs text to compare",
                        )
                    }
                } else if (element.mode == ElementMode.TEXT_MATCHES_SNAPSHOT && element.textSnippet.isBlank()) {
                    notes += Note(
                        Field.ELEMENT_TEXT, Severity.WARNING,
                        "No text was captured$where — re-pick the element to snapshot it",
                    )
                }
                // Two slots resolving to the same node is almost always a
                // double-tap, and it doubles the work for nothing.
                val duplicate = elements.subList(0, index).any {
                    it.displaySelector == element.displaySelector && element.isCaptured
                }
                if (duplicate) {
                    notes += Note(
                        Field.ELEMENT, Severity.WARNING,
                        "Element ${index + 1} watches the same node as an earlier one",
                    )
                }
            }
            if (elements.size > MANY_ELEMENTS) {
                notes += Note(
                    Field.ELEMENT, Severity.HINT,
                    "${elements.size} elements on one page — still a single load, but the " +
                        "check gets slower to settle",
                )
            }
        }

        return Report(notes)
    }

    /**
     * Everything a GitHub monitor can get wrong before a request is sent.
     *
     * The rate-limit hint is the one worth being loud about. Each poll is up to
     * three conditional GETs, and an unauthenticated client gets 60 an hour for
     * the whole device, so two repositories on a five-minute cadence exhaust the
     * budget and every check after that learns nothing.
     */
    private fun reportGitHub(monitor: Monitor, notes: MutableList<Note>) {
        val watch = monitor.github
        repoNote(watch.repository.slug.takeIf { watch.repository.isSet } ?: "")?.let { notes += it }

        if (!watch.notifyOnStars && !watch.notifyOnIssues &&
            !watch.watchReleases && !watch.watchPullRequests
        ) {
            notes += Note(
                Field.GITHUB, Severity.WARNING,
                "Nothing is being watched, so this monitor will never have anything to say",
            )
        }
        if (watch.notifyOnStars && !watch.notifyOnEveryStar && !watch.notifyOnStarMilestones) {
            notes += Note(
                Field.GITHUB, Severity.HINT,
                "Star watching is on with neither every-star nor milestones, so nothing " +
                    "will be announced about stars",
            )
        }
        if (watch.issueAuthors.isNotEmpty() && !watch.notifyOnIssues && !watch.watchPullRequests) {
            notes += Note(
                Field.GITHUB, Severity.HINT,
                "The author filter only applies to issues and pull requests",
            )
        }
        if (monitor.intervalMinutes < 15) {
            notes += Note(
                Field.INTERVAL, Severity.WARNING,
                "GitHub allows 60 requests an hour without a token, and one check spends up " +
                    "to three. Add a token in Settings, or check less often.",
            )
        }
        if (monitor.useProxy) {
            notes += Note(
                Field.PROXY, Severity.HINT,
                "api.github.com is reached through the proxy as well.",
            )
        }
    }

    /** The cadence rules, shared by every kind including GitHub. */
    private fun cadenceNotes(monitor: Monitor): List<Note> {
        val notes = mutableListOf<Note>()
        if (monitor.intervalMinutes < 1) {
            notes += Note(Field.INTERVAL, Severity.ERROR, "Interval must be at least 1 minute")
        }
        if (monitor.timeoutSeconds < 1 || monitor.timeoutSeconds > 120) {
            notes += Note(Field.TIMEOUT, Severity.ERROR, "Timeout must be 1 to 120 seconds")
        }
        if (monitor.urgent && monitor.urgentRepeatMinutes < 1) {
            notes += Note(Field.URGENT, Severity.ERROR, "Urgent repeats must be at least a minute apart")
        }
        return notes
    }
}
