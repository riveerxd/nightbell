package me.river.nightbell.domain

import java.net.URLEncoder
import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Everything a Prometheus monitor does that is not an HTTP request.
 *
 * The request itself is [me.river.nightbell.data.check.HttpChecker]'s, unchanged
 * and on purpose. That path already carries the SOCKS routing, the TLS trust
 * mode, the pooled-connection retry, the timeout split for a routed check and
 * the failure classification, and a second HTTP client for Prometheus would be a
 * second set of answers to all five. What is here is the two ends: which URL to
 * ask for, and what the body that comes back means.
 *
 * Pure, so the formats can be pinned by JVM tests against real payloads.
 */
object PrometheusCheck {

    /** Where `/api/v1/query` lives on a Prometheus, Mimir, Thanos or VictoriaMetrics server. */
    private const val QUERY_PATH = "/api/v1/query"

    /** Alertmanager's v2 alert list. v1 was removed in Alertmanager 0.27. */
    private const val ALERTS_PATH = "/api/v2/alerts"

    /**
     * The URL this monitor actually fetches.
     *
     * Only [PrometheusSource.METRICS] uses the URL as typed, because there the
     * URL is the endpoint. The other two are typed as a server address and the
     * path is this app's business, which is what keeps the field answerable:
     * "which Prometheus" is a question somebody can answer, "what is the query
     * API path on your Prometheus" is one they should not have to.
     *
     * Pasting the full API path anyway is the obvious thing to do, and doing it
     * used to produce `/api/v1/query/api/v1/query`, so a path that is already
     * there is taken off before it is put back on.
     */
    fun requestUrl(monitor: Monitor): String {
        val watch = monitor.prometheus
        val typed = monitor.url.trim()
        return when (watch.source) {
            PrometheusSource.METRICS -> typed

            PrometheusSource.QUERY -> {
                val base = baseOf(typed, QUERY_PATH)
                "$base$QUERY_PATH?query=${encode(watch.query.trim())}"
            }

            PrometheusSource.ALERTMANAGER -> {
                val base = baseOf(typed, ALERTS_PATH)
                // Asked of the server as well as filtered here. The parameters
                // are the cheap way, and the client-side check below is the one
                // that still works against an Alertmanager or a gateway that
                // ignores them.
                "$base$ALERTS_PATH?active=true" +
                    "&silenced=${watch.includeSilenced}" +
                    "&inhibited=${watch.includeInhibited}" +
                    "&unprocessed=false"
            }
        }
    }

    private fun baseOf(typed: String, path: String): String {
        var base = typed.trimEnd('/')
        if (base.endsWith(path, ignoreCase = true)) {
            base = base.dropLast(path.length).trimEnd('/')
        }
        return base
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    /**
     * The `Authorization` value for a monitor with basic credentials, or null.
     *
     * UTF-8 rather than the ISO-8859-1 the original RFC named. Every server in
     * this ecosystem reads UTF-8, and a password with an accent in it going out
     * as mojibake fails authentication with a message that blames the password.
     */
    fun basicAuthHeader(watch: PrometheusWatch): String? {
        if (!watch.hasBasicAuth) return null
        val raw = "${watch.username}:${watch.password}".toByteArray(Charsets.UTF_8)
        return "Basic " + Base64.getEncoder().encodeToString(raw)
    }

    // ---------------------------------------------------------------- verdict

    /**
     * What the response means, for whichever source this monitor is.
     *
     * The status code is judged here rather than through [StatusExpectation],
     * which every other HTTP kind uses. A Prometheus monitor has no status
     * expectation worth asking a user for: these APIs answer 200 or they have
     * failed. Judging it here is what lets a 401 say "check the username and
     * password" instead of "got 401, expected 2xx", which is true and useless.
     */
    fun evaluate(monitor: Monitor, code: Int, body: String): Assertions.Verdict {
        val watch = monitor.prometheus
        transportVerdict(watch, code)?.let { return it }
        return when (watch.source) {
            PrometheusSource.METRICS -> metricsVerdict(watch, body)
            PrometheusSource.QUERY -> queryVerdict(watch, body)
            PrometheusSource.ALERTMANAGER -> alertsVerdict(watch, body)
        }
    }

    /** Non-2xx, said in terms of the thing that was being asked. */
    private fun transportVerdict(watch: PrometheusWatch, code: Int): Assertions.Verdict? {
        if (code in 200..299) return null
        val what = when (watch.source) {
            PrometheusSource.METRICS -> "The metrics endpoint"
            PrometheusSource.QUERY -> "The Prometheus server"
            PrometheusSource.ALERTMANAGER -> "Alertmanager"
        }
        val hint = when (code) {
            401, 407 -> "Authentication was refused. Check the username and password, " +
                "or add the token this server wants as a header."
            403 -> "The credentials were accepted and this endpoint was still refused. " +
                "Check what the account is allowed to read."
            404 -> when (watch.source) {
                PrometheusSource.METRICS -> "Nothing is exposed at that path. Most exporters serve /metrics."
                PrometheusSource.QUERY -> "No query API at that address. Give the server's base URL, " +
                    "without $QUERY_PATH."
                PrometheusSource.ALERTMANAGER -> "No v2 API at that address. Give Alertmanager's " +
                    "base URL, without $ALERTS_PATH."
            }
            422 -> "The server understood the request and refused the query itself."
            in 500..599 -> "The server is answering, and answering with an error of its own."
            else -> "Check the URL and anything sitting in front of it."
        }
        return Assertions.Verdict.fail(
            kind = FailureKind.STATUS,
            message = "$what answered $code",
            detail = "Expected a 2xx from ${what.lowercase()}.",
            hint = hint,
        )
    }

    // ---------------------------------------------------------------- metrics

    private fun metricsVerdict(watch: PrometheusWatch, body: String): Assertions.Verdict {
        val selector = OpenMetrics.parseSelector(watch.labelFilter)
        if (!selector.isValid) {
            return Assertions.Verdict.fail(
                FailureKind.BAD_CONFIG,
                "Label filter can't be read",
                selector.error.orEmpty(),
                hint = "Write it the way PromQL does: mode=\"idle\", cpu!=\"0\".",
            )
        }

        val samples = OpenMetrics.parse(body)
        if (samples.isEmpty()) {
            return Assertions.Verdict.fail(
                FailureKind.BODY,
                "That isn't a metrics endpoint",
                "Read ${body.length} characters and found no exposition lines in them.",
                hint = "Check the URL points at the exporter's /metrics path rather than " +
                    "at a dashboard or a login page.",
            )
        }

        val matched = OpenMetrics.select(samples, watch.metricName, selector)
        if (matched.isEmpty()) {
            val suggestions = OpenMetrics.suggestNames(samples, watch.metricName)
            val named = samples.count { it.name == watch.metricName.trim() }
            return Assertions.Verdict.fail(
                FailureKind.METRIC,
                "Nothing matched ${watch.selectorText}",
                if (named > 0) {
                    "The scrape has $named series called ${watch.metricName.trim()}, and the " +
                        "label filter excluded all of them."
                } else {
                    buildString {
                        append(
                            "The scrape exposed ${samples.size} samples and none of them is " +
                                "called ${watch.metricName.trim()}.",
                        )
                        // The closest names go here rather than in the hint. They
                        // are read off the monitored server's own response, and
                        // `hint` is classified as copy this app ships from a fixed
                        // set, which is what lets it be logged. See LogSentinelTest.
                        if (suggestions.isNotEmpty()) {
                            append("\nClosest names in this scrape: ")
                            append(suggestions.joinToString(", "))
                        }
                    }
                },
                hint = when {
                    named > 0 -> "Loosen the label filter, or check the labels on the series you want."
                    suggestions.isNotEmpty() -> "A counter is exposed with a _total suffix that its " +
                        "rule name doesn't carry, so a name copied off a dashboard often finds " +
                        "nothing. The closest names in this scrape are listed above."
                    else -> "Check the metric name against the scrape, and that the exporter still " +
                        "has the collector that produces it switched on."
                },
            )
        }

        val breach = matched.firstOrNull { !watch.comparison.holds(it.value, watch.threshold) }
        val expected = "${watch.comparison.symbol} ${PrometheusWatch.formatValue(watch.threshold)}"
        if (breach != null) {
            return Assertions.Verdict.fail(
                FailureKind.METRIC,
                "${breach.display} = ${PrometheusWatch.formatValue(breach.value)}, expected $expected",
                if (matched.size == 1) {
                    "Read straight off the exposition, no server in between."
                } else {
                    "${matched.size} series matched and this is the first one outside the range."
                },
                hint = if (breach.value.isNaN()) {
                    "The exporter reported NaN, which means it had no reading to give rather " +
                        "than a reading of zero."
                } else {
                    "Adjust the threshold if this value is actually fine."
                },
            )
        }

        return Assertions.Verdict(
            passed = true,
            message = if (matched.size == 1) {
                "${matched.first().display} = ${PrometheusWatch.formatValue(matched.first().value)}"
            } else {
                "${matched.size} series, all $expected"
            },
            detail = if (matched.size == 1) {
                // The one-series case already said the value in the message, and
                // a detail line repeating it word for word reads as a bug rather
                // than as emphasis. Say what it was judged against instead.
                "Healthy while $expected. Read straight off the exposition, with no server in between."
            } else {
                readingLines(matched.map { it.display to it.value })
            },
        )
    }

    // ------------------------------------------------------------------ promql

    private fun queryVerdict(watch: PrometheusWatch, body: String): Assertions.Verdict {
        val root = Assertions.parseJson(body) as? JsonObject
            ?: return Assertions.Verdict.fail(
                FailureKind.BODY,
                "That isn't a Prometheus API response",
                "Expected a JSON object with a status field, got ${body.length} characters that " +
                    "don't parse as one.",
                hint = "Give the server's base URL. Nightbell adds $QUERY_PATH itself.",
            )

        // Prometheus reports a rejected query as 200 with status "error", so the
        // status code alone never sees the most common thing a user gets wrong.
        if (root.text("status") == "error") {
            val type = root.text("errorType").orEmpty()
            val message = root.text("error").orEmpty()
            return Assertions.Verdict.fail(
                FailureKind.BAD_CONFIG,
                "The server rejected the query",
                listOfNotNull(type.ifBlank { null }, message.ifBlank { null }).joinToString(": "),
                hint = "The query reached Prometheus and Prometheus would not run it. " +
                    "Try it in the expression browser first.",
            )
        }

        val data = root["data"] as? JsonObject
            ?: return Assertions.Verdict.fail(
                FailureKind.BODY,
                "The response carried no data",
                "status was \"${root.text("status").orEmpty()}\" and there is no data object " +
                    "under it.",
                hint = "Check that the URL is a Prometheus-compatible query API.",
            )

        val series = readSeries(data)
        val rule = watch.queryRule

        if (rule == PromQueryRule.RETURNS_NOTHING) {
            if (series.isEmpty()) {
                return Assertions.Verdict(
                    passed = true,
                    message = "Query matched nothing",
                    detail = "Which is what this monitor is watching for.",
                )
            }
            return Assertions.Verdict.fail(
                FailureKind.METRIC,
                "${series.size} series matched: ${series.first().display}" +
                    if (series.size > 1) " and ${series.size - 1} more" else "",
                seriesLines(series),
                hint = "This query is written as an alerting rule, so anything it returns is " +
                    "the alert. Check what changed on the series above.",
            )
        }

        if (series.isEmpty()) {
            return Assertions.Verdict.fail(
                FailureKind.METRIC,
                "The query returned nothing",
                "Prometheus answered successfully and the result set is empty.",
                hint = if (rule == PromQueryRule.RETURNS_SOMETHING) {
                    "This monitor expects at least one series. An empty result usually means the " +
                        "target stopped being scraped, or a label in the query no longer exists."
                } else {
                    "There is nothing to compare against the threshold."
                },
            )
        }

        if (rule == PromQueryRule.RETURNS_SOMETHING) {
            return Assertions.Verdict(
                passed = true,
                message = if (series.size == 1) {
                    "${series.first().display} = ${PrometheusWatch.formatValue(series.first().value)}"
                } else {
                    "${series.size} series returned"
                },
                detail = if (series.size == 1) {
                    "The query returned one series, which is what this monitor is watching for."
                } else {
                    seriesLines(series)
                },
            )
        }

        val expected = "${watch.comparison.symbol} ${PrometheusWatch.formatValue(watch.threshold)}"
        val breach = series.firstOrNull { !watch.comparison.holds(it.value, watch.threshold) }
        if (breach != null) {
            return Assertions.Verdict.fail(
                FailureKind.METRIC,
                "${breach.display} = ${PrometheusWatch.formatValue(breach.value)}, expected $expected",
                seriesLines(series),
                hint = if (breach.value.isNaN()) {
                    "The query evaluated to NaN, which usually means a division by zero or a " +
                        "range with no samples in it."
                } else {
                    "Adjust the threshold if this value is actually fine."
                },
            )
        }

        return Assertions.Verdict(
            passed = true,
            message = if (series.size == 1) {
                "${series.first().display} = ${PrometheusWatch.formatValue(series.first().value)}"
            } else {
                "${series.size} series, all $expected"
            },
            detail = if (series.size == 1) {
                "Healthy while $expected."
            } else {
                seriesLines(series)
            },
        )
    }

    /** One result series, flattened out of whichever result type came back. */
    data class Series(val labels: Map<String, String>, val value: Double) {
        val display: String
            get() {
                val name = labels["__name__"].orEmpty()
                val rest = labels.filterKeys { it != "__name__" }
                if (rest.isEmpty()) return name.ifBlank { "result" }
                return name + rest.entries.sortedBy { it.key }
                    .joinToString(",", prefix = "{", postfix = "}") { (k, v) -> "$k=\"$v\"" }
            }
    }

    /**
     * Every series in a query response, whatever `resultType` says.
     *
     * A vector is what an alerting-shaped query returns and is the case that
     * matters. Matrix and scalar are here because the expression browser hands
     * people those without telling them it did: pasting a query with a `[5m]`
     * range in it produces a matrix, and a monitor that answered "not a
     * Prometheus response" to a query that plainly works in Grafana would be
     * blaming the user for this parser's gap. A matrix series is read at its
     * newest sample, which is the value the same query would show on a graph's
     * right-hand edge.
     */
    private fun readSeries(data: JsonObject): List<Series> {
        val result = data["result"] ?: return emptyList()
        return when (data.text("resultType")) {
            "scalar", "string" -> listOfNotNull(pairValue(result)?.let { Series(emptyMap(), it) })

            "matrix" -> (result as? JsonArray).orEmpty().mapNotNull { entry ->
                val obj = entry as? JsonObject ?: return@mapNotNull null
                val newest = (obj["values"] as? JsonArray)?.lastOrNull() ?: return@mapNotNull null
                pairValue(newest)?.let { Series(labelsOf(obj), it) }
            }

            else -> (result as? JsonArray).orEmpty().mapNotNull { entry ->
                val obj = entry as? JsonObject ?: return@mapNotNull null
                pairValue(obj["value"])?.let { Series(labelsOf(obj), it) }
            }
        }
    }

    /** `[1435781451.781, "1"]`, of which only the second half is wanted. */
    private fun pairValue(element: JsonElement?): Double? {
        val array = element as? JsonArray ?: return null
        val raw = (array.getOrNull(1) as? JsonPrimitive)?.content ?: return null
        return OpenMetrics.parseValue(raw)
    }

    private fun labelsOf(entry: JsonObject): Map<String, String> =
        (entry["metric"] as? JsonObject)?.mapValues { (_, v) -> (v as? JsonPrimitive)?.content.orEmpty() }
            ?: emptyMap()

    private fun seriesLines(series: List<Series>): String =
        readingLines(series.map { it.display to it.value })

    /** One `name = value` line per reading, truncated, for a detail pane. */
    private fun readingLines(readings: List<Pair<String, Double>>): String =
        readings.take(SERIES_IN_DETAIL).joinToString("\n") { (name, value) ->
            "$name = ${PrometheusWatch.formatValue(value)}"
        } + if (readings.size > SERIES_IN_DETAIL) {
            "\n… and ${readings.size - SERIES_IN_DETAIL} more"
        } else {
            ""
        }

    // ----------------------------------------------------------- alertmanager

    /** A parsed alert plus the labels the filter needs, which are not persisted. */
    private data class Held(val alert: FiringAlert, val labels: Map<String, String>, val state: String)

    private fun alertsVerdict(watch: PrometheusWatch, body: String): Assertions.Verdict {
        val selector = OpenMetrics.parseSelector(watch.alertLabelFilter)
        if (!selector.isValid) {
            return Assertions.Verdict.fail(
                FailureKind.BAD_CONFIG,
                "Label filter can't be read",
                selector.error.orEmpty(),
                hint = "Write it the way PromQL does: severity=\"critical\", team=\"platform\".",
            )
        }

        val array = Assertions.parseJson(body) as? JsonArray
            ?: return Assertions.Verdict.fail(
                FailureKind.BODY,
                "That isn't an Alertmanager response",
                "Expected a JSON array of alerts, got ${body.length} characters that don't " +
                    "parse as one.",
                hint = "Give Alertmanager's base URL. Nightbell adds $ALERTS_PATH itself.",
            )

        val all = array.mapNotNull { readAlert(it) }
        val firing = all.filter { held ->
            if (held.alert.silenced && !watch.includeSilenced) return@filter false
            if (held.alert.inhibited && !watch.includeInhibited) return@filter false
            if (held.state.equals("suppressed", ignoreCase = true) &&
                !watch.includeSilenced && !watch.includeInhibited
            ) {
                return@filter false
            }
            if (!selector.matches(held.labels)) return@filter false
            severityPasses(held.alert.rank, watch.minimumSeverity)
        }.map { it.alert }.sortedWith(
            // Worst first, then oldest first inside a severity, which is the
            // order Alertmanager's own view uses and the order somebody reads in:
            // the thing that has been broken longest is the thing to look at.
            compareByDescending<FiringAlert> { it.sortKey }.thenBy { it.startedAt },
        )

        if (firing.isEmpty()) {
            return Assertions.Verdict(
                passed = true,
                message = if (all.isEmpty()) {
                    "Nothing firing"
                } else {
                    "${all.size} held, none past the filter"
                },
                detail = if (all.isEmpty()) {
                    "Alertmanager is holding no alerts at all."
                } else {
                    "Alertmanager is holding ${all.size}, and the severity and label filters " +
                        "on this monitor exclude every one of them."
                },
            )
        }

        val headline = firing.take(NAMES_IN_MESSAGE).joinToString(", ") { alert ->
            if (alert.severity.isBlank()) alert.name else "${alert.name} (${alert.severity})"
        }
        return Assertions.Verdict.fail(
            FailureKind.METRIC,
            if (firing.size == 1) "Firing: $headline" else "${firing.size} firing: $headline",
            // The detail stays readable on its own for the notification and the
            // log. The screen uses `alerts` below and renders a list instead.
            firing.take(SERIES_IN_DETAIL).joinToString("\n") { alert ->
                buildString {
                    append(alert.name)
                    if (alert.severity.isNotBlank()) append(" · ").append(alert.severity)
                    if (alert.summary.isNotBlank()) append("\n").append(alert.summary)
                }
            } + if (firing.size > SERIES_IN_DETAIL) "\n… and ${firing.size - SERIES_IN_DETAIL} more" else "",
            hint = "This is Alertmanager's verdict, not Nightbell's. Silence it there and this " +
                "monitor goes quiet with it.",
            alerts = firing,
        )
    }

    private fun readAlert(element: JsonElement): Held? {
        val obj = element as? JsonObject ?: return null
        val labels = (obj["labels"] as? JsonObject)
            ?.mapValues { (_, v) -> (v as? JsonPrimitive)?.content.orEmpty() }
            ?: emptyMap()
        val status = obj["status"] as? JsonObject
        val annotations = obj["annotations"] as? JsonObject
        return Held(
            alert = FiringAlert(
                name = labels["alertname"].orEmpty().ifBlank { "unnamed alert" },
                severity = labels["severity"].orEmpty(),
                summary = annotations?.text("summary")
                    ?: annotations?.text("description").orEmpty(),
                startedAt = parseTimestamp(obj.text("startsAt")),
                silenced = (status?.get("silencedBy") as? JsonArray).orEmpty().isNotEmpty(),
                inhibited = (status?.get("inhibitedBy") as? JsonArray).orEmpty().isNotEmpty(),
            ),
            labels = labels,
            state = status?.text("state").orEmpty(),
        )
    }

    /**
     * Alertmanager's RFC 3339 `startsAt`, in millis, or 0.
     *
     * Parsed with the JDK rather than by hand because the field arrives with a
     * variable number of fractional digits and an offset that is usually but not
     * always `Z`. A date this cannot read sorts as oldest, which puts it at the
     * top of its severity rather than hiding it.
     */
    private fun parseTimestamp(raw: String?): Long {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return 0L
        return runCatching { java.time.Instant.parse(text).toEpochMilli() }
            .recoverCatching { java.time.OffsetDateTime.parse(text).toInstant().toEpochMilli() }
            .getOrDefault(0L)
    }

    /**
     * Whether an alert is loud enough to count.
     *
     * An alert whose severity this app cannot rank passes every minimum, and
     * that asymmetry is deliberate. Labelling severity `page` and `ticket` is a
     * common house style, and plenty of rules carry no severity label at all, so
     * a filter that dropped what it did not recognise would hide real outages to
     * keep a chip selector tidy. Letting one extra alert through is a noisy
     * night. Swallowing one is the failure this app exists to not have.
     */
    private fun severityPasses(severity: AlertSeverity?, minimum: AlertSeverity): Boolean {
        if (minimum == AlertSeverity.ANY) return true
        if (severity == null) return true
        return severity.rank >= minimum.rank
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.content

    private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())

    private const val SERIES_IN_DETAIL = 8

    private const val NAMES_IN_MESSAGE = 3
}
