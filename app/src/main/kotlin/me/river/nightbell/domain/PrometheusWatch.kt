package me.river.nightbell.domain

import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.abs
import kotlin.math.max
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Which of the three Prometheus-shaped endpoints a monitor talks to.
 *
 * Three sources under one kind rather than three kinds, because the alternative
 * put "Prometheus metrics", "Prometheus query" and "Alertmanager" next to each
 * other in the kind picker, where the only way to find out which one you wanted
 * was to pick one and read the next screen. They also share the URL, the
 * sign-in, the cadence, the alert policy and the TLS settings, which is most of
 * a monitor.
 */
@Serializable
enum class PrometheusSource {
    /**
     * Scrape an exposition endpoint directly: node_exporter, cAdvisor, an app's
     * own `/metrics`. No Prometheus server involved, which is the point.
     */
    @SerialName("metrics")
    METRICS,

    /** Ask a Prometheus, Mimir, Thanos or VictoriaMetrics server a PromQL question. */
    @SerialName("query")
    QUERY,

    /** Read what Alertmanager is already firing about. */
    @SerialName("alertmanager")
    ALERTMANAGER,
    ;

    val label: String
        get() = when (this) {
            METRICS -> "Metrics"
            QUERY -> "PromQL"
            ALERTMANAGER -> "Alerts"
        }

    val title: String
        get() = when (this) {
            METRICS -> "Scrape a metrics endpoint"
            QUERY -> "Query a Prometheus server"
            ALERTMANAGER -> "Watch an Alertmanager"
        }

    val blurb: String
        get() = when (this) {
            METRICS -> "Read one series straight off /metrics and compare its value."
            QUERY -> "Run PromQL against Prometheus, Mimir, Thanos or VictoriaMetrics."
            ALERTMANAGER -> "Page this phone when Alertmanager has something firing."
        }

    /** What to put in the URL field. */
    val urlLabel: String
        get() = when (this) {
            METRICS -> "Metrics URL"
            QUERY -> "Prometheus URL"
            ALERTMANAGER -> "Alertmanager URL"
        }

    val urlPlaceholder: String
        get() = when (this) {
            METRICS -> "http://node.example.com:9100/metrics"
            QUERY -> "https://prometheus.example.com"
            ALERTMANAGER -> "https://alertmanager.example.com"
        }
}

/**
 * How a sample's value is judged against the threshold.
 *
 * Phrased as what healthy looks like, not as what to alert on, because that is
 * the direction the rest of the app already reads in: an expected status code is
 * the one you want back. So "below 2" means the monitor is up while the value
 * stays under 2, and the page fires when it does not.
 */
@Serializable
enum class MetricComparison {
    @SerialName("gt")
    ABOVE,

    @SerialName("gte")
    AT_LEAST,

    @SerialName("lt")
    BELOW,

    @SerialName("lte")
    AT_MOST,

    @SerialName("eq")
    EQUAL_TO,

    @SerialName("ne")
    NOT_EQUAL_TO,
    ;

    val symbol: String
        get() = when (this) {
            ABOVE -> ">"
            AT_LEAST -> ">="
            BELOW -> "<"
            AT_MOST -> "<="
            EQUAL_TO -> "=="
            NOT_EQUAL_TO -> "!="
        }

    val label: String
        get() = when (this) {
            ABOVE -> "Above"
            AT_LEAST -> "At least"
            BELOW -> "Below"
            AT_MOST -> "At most"
            EQUAL_TO -> "Equals"
            NOT_EQUAL_TO -> "Not equal"
        }

    /**
     * Whether [value] is the healthy side of [threshold].
     *
     * Every comparison is false for NaN, including [NOT_EQUAL_TO], and that is
     * deliberate rather than an accident of IEEE 754 leaking through. A NaN in
     * this format means the exporter had no reading to give: a disk that has
     * gone away, a collector that failed, a recording rule dividing by zero.
     * Treating "no reading" as healthy is how a monitor goes quiet at exactly
     * the moment it had something to say, so a sample nobody can compare fails
     * and the message says it was NaN.
     *
     * Equality gets a tolerance because these values arrive as decimal text and
     * come back out of a binary double: 0.1 + 0.2 is a real thing to find in a
     * counter, and asking a user to know that is asking the wrong person. The
     * tolerance is relative so it still means something at 1e9.
     */
    fun holds(value: Double, threshold: Double): Boolean {
        if (value.isNaN() || threshold.isNaN()) return false
        return when (this) {
            ABOVE -> value > threshold
            AT_LEAST -> value >= threshold
            BELOW -> value < threshold
            AT_MOST -> value <= threshold
            EQUAL_TO -> nearlyEqual(value, threshold)
            NOT_EQUAL_TO -> !nearlyEqual(value, threshold)
        }
    }

    private fun nearlyEqual(a: Double, b: Double): Boolean {
        if (a == b) return true
        if (a.isInfinite() || b.isInfinite()) return false
        return abs(a - b) <= RELATIVE_TOLERANCE * max(1.0, max(abs(a), abs(b)))
    }

    private companion object {
        const val RELATIVE_TOLERANCE = 1e-9
    }
}

/**
 * What a PromQL monitor counts as healthy.
 *
 * [RETURNS_NOTHING] is the default and is the shape issue 13 asked for: write
 * the query as the alert condition, the way a Prometheus alerting rule is
 * written, and any series coming back means it fired. The other two are here
 * because the same endpoint answers a different question just as well, and both
 * were one branch each once the first existed.
 */
@Serializable
enum class PromQueryRule {
    @SerialName("empty")
    RETURNS_NOTHING,

    @SerialName("non_empty")
    RETURNS_SOMETHING,

    @SerialName("compare")
    VALUE_PASSES,
    ;

    val label: String
        get() = when (this) {
            RETURNS_NOTHING -> "Returns nothing"
            RETURNS_SOMETHING -> "Returns something"
            VALUE_PASSES -> "Value passes"
        }

    val blurb: String
        get() = when (this) {
            RETURNS_NOTHING -> "Healthy while the query matches no series. Write it like an alerting rule."
            RETURNS_SOMETHING -> "Healthy while the query matches at least one series."
            VALUE_PASSES -> "Healthy while every series the query returns is inside the range below."
        }
}

/** Alertmanager's severity label, ranked so "at least" means something. */
@Serializable
enum class AlertSeverity {
    @SerialName("any")
    ANY,

    @SerialName("info")
    INFO,

    @SerialName("warning")
    WARNING,

    @SerialName("critical")
    CRITICAL,
    ;

    val label: String
        get() = when (this) {
            ANY -> "Any"
            INFO -> "Info"
            WARNING -> "Warning"
            CRITICAL -> "Critical"
        }

    /** Where this sits in the usual ordering. [ANY] is below everything. */
    val rank: Int
        get() = when (this) {
            ANY -> 0
            INFO -> 1
            WARNING -> 2
            CRITICAL -> 3
        }

    companion object {
        /**
         * Reads a `severity` label, or null when it is one nobody ranked.
         *
         * Null is not "ignore this alert". An alert whose severity this app does
         * not recognise, or that carries no severity label at all, passes every
         * minimum: see the comment on the filter. Plenty of working setups label
         * severity `page` and `ticket`, or do not label it, and a filter that
         * silently swallowed those would be hiding outages to keep a chip
         * selector honest.
         */
        fun parse(raw: String?): AlertSeverity? = when (raw?.trim()?.lowercase()) {
            "critical", "crit", "fatal", "emergency", "page" -> CRITICAL
            "warning", "warn" -> WARNING
            "info", "information", "informational", "none" -> INFO
            else -> null
        }
    }
}

/**
 * One alert Alertmanager is holding, reduced to what a screen needs to say.
 *
 * Persisted rather than rendered into a sentence and thrown away, because issue
 * 14 asked for a view of the alerts and the apps it named are alert viewers. A
 * paragraph saying "2 firing: DiskFillingUp (critical), HighLatency (warning)"
 * answers "should I get up"; it does not answer "what is going on", which is the
 * question somebody has once they are up.
 */
@Serializable
data class FiringAlert(
    val name: String = "",
    /** Exactly as Alertmanager labelled it, including a word nobody ranks. */
    val severity: String = "",
    val summary: String = "",
    /** `startsAt` as epoch millis, or 0 when it could not be read. */
    val startedAt: Long = 0L,
    val silenced: Boolean = false,
    val inhibited: Boolean = false,
) {
    val rank: AlertSeverity? get() = AlertSeverity.parse(severity)

    /** Where this sorts, with anything unranked below everything ranked. */
    val sortKey: Int get() = rank?.rank ?: 0
}

/**
 * Everything a Prometheus monitor is pointed at, for all three sources.
 *
 * One flat record rather than three nested ones. Only one source's fields are
 * ever read, and a user who tries Alertmanager and comes back to metrics finds
 * the metric name still typed where they left it, which is worth more than the
 * tidiness of three empty objects.
 *
 * The label filters are two fields rather than one shared one on purpose. They
 * mean different things, metric labels and alert labels, and carrying a
 * half-written metric selector over into the alert filter would apply it
 * silently.
 */
@Serializable
data class PrometheusWatch(
    val source: PrometheusSource = PrometheusSource.METRICS,

    /** The series to read, e.g. `node_load1`. Exposition name, no braces. */
    val metricName: String = "",

    /** PromQL-style selector, e.g. `mode="idle", cpu!="0"`. Braces optional. */
    val labelFilter: String = "",

    val query: String = "",
    val queryRule: PromQueryRule = PromQueryRule.RETURNS_NOTHING,

    /** Shared by [PrometheusSource.METRICS] and [PromQueryRule.VALUE_PASSES]. */
    val comparison: MetricComparison = MetricComparison.BELOW,
    val threshold: Double = 1.0,

    val minimumSeverity: AlertSeverity = AlertSeverity.ANY,
    val alertLabelFilter: String = "",

    /**
     * Count alerts somebody has already silenced, or that another alert has
     * inhibited.
     *
     * Both off, because both mean a person has already said they know. Waking
     * that same person at 04:00 about an alert they silenced at 23:00 is the
     * fastest way to get the app uninstalled.
     */
    val includeSilenced: Boolean = false,
    val includeInhibited: Boolean = false,

    /**
     * HTTP basic credentials, which is what issue 13 asked for by name and what
     * Grafana Cloud, Mimir and a reverse proxy in front of Prometheus all take.
     *
     * Anything else goes in the monitor's headers: a bearer token, a Mimir
     * `X-Scope-OrgID`, an API gateway's own key. That editor already exists and
     * already works, and inventing a field per authentication scheme would mean
     * inventing one more every time somebody's stack differs.
     */
    val username: String = "",
    val password: String = "",
) {
    val hasBasicAuth: Boolean get() = username.isNotBlank() || password.isNotBlank()

    /** One line for the detail screen and the setup summary. */
    val summary: String
        get() = when (source) {
            PrometheusSource.METRICS ->
                "${selectorText.ifBlank { "no metric" }} ${comparison.symbol} ${formatValue(threshold)}"

            PrometheusSource.QUERY -> when (queryRule) {
                PromQueryRule.VALUE_PASSES ->
                    "value ${comparison.symbol} ${formatValue(threshold)}"
                else -> queryRule.label.lowercase()
            }

            PrometheusSource.ALERTMANAGER -> buildList {
                add(if (minimumSeverity == AlertSeverity.ANY) "any severity" else "${minimumSeverity.label} and above")
                if (alertLabelFilter.isNotBlank()) add(alertLabelFilter.trim())
                if (includeSilenced) add("silenced included")
                if (includeInhibited) add("inhibited included")
            }.joinToString(" · ")
        }

    /** `node_load1{mode="idle"}`, the way it would be written in PromQL. */
    val selectorText: String
        get() {
            val name = metricName.trim()
            val filter = labelFilter.trim().removeSurrounding("{", "}").trim().trim(',')
            if (name.isEmpty()) return if (filter.isEmpty()) "" else "{$filter}"
            return if (filter.isEmpty()) name else "$name{$filter}"
        }

    companion object {
        /**
         * A double as somebody would have typed it.
         *
         * `toString` on a whole number gives "2.0", and a threshold of 2 written
         * back as 2.0 in the message under the field the user typed "2" into
         * reads as the app having changed it.
         */
        fun formatValue(value: Double): String = when {
            value.isNaN() -> "NaN"
            value == Double.POSITIVE_INFINITY -> "+Inf"
            value == Double.NEGATIVE_INFINITY -> "-Inf"
            value == value.toLong().toDouble() && abs(value) < 1e15 -> value.toLong().toString()
            // `toString` switches to exponent notation somewhere above a billion,
            // and "3.4359738368E9" is not a number anybody reads off a list. It
            // is also seeded into the threshold field, where it has to be
            // something a person could have typed and could edit.
            //
            // Rounded to twelve significant figures first, which is inside a
            // double's honest precision and is what stops 0.29 coming back as
            // 0.28999999999999998. `toPlainString` after `stripTrailingZeros`,
            // because stripping alone is what produces "1E+2" from 100.0000.
            else -> BigDecimal(value)
                .round(MathContext(12))
                .stripTrailingZeros()
                .toPlainString()
        }
    }
}
