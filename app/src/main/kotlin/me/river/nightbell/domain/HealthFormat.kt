package me.river.nightbell.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * Reads one response from a [MonitorKind.HEALTH_ENDPOINT] monitor.
 *
 * The public contract is `docs/health-format.md`. The body decides, not the
 * status code: a `503` carrying a valid document is that document, because the
 * health libraries that answer 503 when unhealthy put the useful part in the
 * body. The status code only speaks when nothing readable came back.
 */
object HealthFormat {

    /** A health document is never this big. Anything over it is not read at all. */
    const val MAX_BODY_BYTES = 256 * 1024

    /** What the documentation promises is shown, and no more. */
    const val COMPONENTS_KEPT = 50
    const val ID_CHARS = 64
    const val NAME_CHARS = 60
    const val MESSAGE_CHARS = 200
    const val UNIT_CHARS = 16
    const val EXCERPT_CHARS = 200
    const val CONTEXT_CHARS = 40
    const val SERVICE_CHARS = 60
    const val CONDITIONS_PER_PART = 20
    const val LOG_PER_PART = 50
    const val LOG_MESSAGE_CHARS = 500
    const val DATA_KEYS = 20
    const val DATA_VALUE_CHARS = 200
    const val OBSERVED_CHARS = 80
    const val RULE_CHARS = 80

    /**
     * Across the whole report. The report is written back to the store after
     * every check, and fifty parts with fifty long log entries each would put
     * over a megabyte through that write every minute.
     */
    const val CONDITIONS_KEPT = 200
    const val LOG_KEPT = 200

    /** The key that marks a document as this format. */
    const val MARKER = "healthReport"

    /** Notes are for a person reading the detail screen, not a log of every bad entry. */
    private const val NOTES_KEPT = 10

    /**
     * With no `Date` header the phone's clock is all there is, and a phone a
     * minute out is ordinary. Two minutes keeps that from reading as a stale report.
     */
    private const val PHONE_CLOCK_TOLERANCE_MS = 2 * 60_000L

    private val json = Json { isLenient = false }

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

    private fun JsonObject?.orEmpty(): Map<String, JsonElement> = this ?: emptyMap()

    private val UP_WORDS = setOf("up", "ok", "pass", "healthy")
    private val DEGRADED_WORDS = setOf("degraded", "warn", "warning")
    private val DOWN_WORDS = setOf("down", "fail", "error", "unhealthy")

    /** What one response said. */
    sealed interface Reading {
        /** A document was read. [verdict] is what pages. */
        data class Report(
            val verdict: HealthStatus,
            /** Why it is down, in the order of `docs/health-format.md`. Blank unless it is. */
            val message: String,
            val state: HealthState,
            /** The body was ASP.NET's plain `Healthy`, `Degraded` or `Unhealthy`. */
            val plainText: Boolean = false,
            /** Set when the verdict comes from Nightbell rather than the document: an unknown word or a stale report. */
            val override: String = "",
        ) : Reading

        /** Something answered, and it was not this format. */
        data class NotHealthFormat(val message: String, val excerpt: String) : Reading

        /** `401` or `403` with nothing readable. What that means depends on how the monitor signs in. */
        data class Refused(val code: Int, val excerpt: String) : Reading

        /** Any other status with nothing readable: an ordinary failed check. */
        data class HttpError(val code: Int, val excerpt: String) : Reading
    }

    /**
     * @param dateHeader the response's own `Date`, which is the server's clock.
     * @param oversize the body was longer than [MAX_BODY_BYTES] and [body] is only its start.
     */
    fun parse(
        status: Int,
        body: String?,
        dateHeader: Instant?,
        now: Instant,
        staleAfterMinutes: Int = 0,
        oversize: Boolean = false,
    ): Reading {
        val text = body.orEmpty()
        if (oversize || text.length > MAX_BODY_BYTES) {
            return Reading.NotHealthFormat("Response too large to be a health document", "")
        }
        plainText(text)?.let { word ->
            return Reading.Report(
                verdict = word,
                message = if (word == HealthStatus.DOWN) "Reported down" else "",
                state = HealthState(),
                plainText = true,
            )
        }
        val parsed = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
        val marker = parsed?.get(MARKER) as? JsonPrimitive
        if (parsed != null && marker != null && !marker.isString) {
            val version = marker.contentOrNull?.toDoubleOrNull()
            if (version == 1.0) return document(parsed, dateHeader, now, staleAfterMinutes)
            if (version != null && version > 1.0) {
                val shown = if (version == Math.rint(version)) version.toLong().toString() else version.toString()
                return Reading.NotHealthFormat("Health report version $shown is newer than this app reads", "")
            }
        }
        val excerpt = excerpt(text)
        return when (status) {
            401, 403 -> Reading.Refused(status, excerpt)
            in 200..299 -> Reading.NotHealthFormat(
                if (text.isBlank()) "The endpoint answered with an empty body" else "Not a health document",
                excerpt,
            )
            else -> Reading.HttpError(status, excerpt)
        }
    }

    /** One of the plain words, or null. */
    private fun plainText(text: String): HealthStatus? = when (text.trim().lowercase()) {
        "healthy" -> HealthStatus.UP
        "degraded" -> HealthStatus.DEGRADED
        "unhealthy" -> HealthStatus.DOWN
        else -> null
    }

    fun word(raw: String): HealthStatus? = when (raw.trim().lowercase()) {
        in UP_WORDS -> HealthStatus.UP
        in DEGRADED_WORDS -> HealthStatus.DEGRADED
        in DOWN_WORDS -> HealthStatus.DOWN
        else -> null
    }

    private fun document(
        root: JsonObject,
        dateHeader: Instant?,
        now: Instant,
        staleAfterMinutes: Int,
    ): Reading.Report {
        val notes = mutableListOf<String>()
        fun note(text: String) {
            if (notes.size < NOTES_KEPT) notes += text
        }

        val components = mutableListOf<HealthComponent>()
        val seen = mutableSetOf<String>()
        var total = 0
        val budget = Budget()
        when (val list = root["components"]) {
            null, is kotlinx.serialization.json.JsonNull -> Unit
            is JsonArray -> list.forEachIndexed { index, entry ->
                val component = component(entry, index, ::note, budget) ?: return@forEachIndexed
                if (!seen.add(component.id)) {
                    note("Component \"${component.id}\" appears more than once. The first is shown.")
                    return@forEachIndexed
                }
                total++
                if (components.size < COMPONENTS_KEPT) components += component
            }
            else -> note("components is not a list, so no parts are shown.")
        }

        val checkedAtText = string(root["checkedAt"])
        val checkedAt = checkedAtText?.let { timestamp(it) }
        if (checkedAtText != null && checkedAt == null) note("checkedAt \"${cut(checkedAtText, 40)}\" is not a time Nightbell can read.")

        val rawStatus = string(root["status"])
        val word = rawStatus?.let(::word)
        val documentMessage = cut(string(root["message"]).orEmpty().trim(), MESSAGE_CHARS)

        val downCount = components.count { it.status == HealthStatus.DOWN }
        val state = HealthState(
            components = components,
            componentsTotal = total,
            disagreement = if (word == HealthStatus.UP && downCount > 0) {
                val first = components.first { it.status == HealthStatus.DOWN }
                if (downCount == 1) {
                    "The report says up while ${first.displayName} says down."
                } else {
                    "The report says up while $downCount parts say down."
                }
            } else {
                ""
            },
            notes = notes,
            reportedAt = checkedAt?.toEpochMilli() ?: 0L,
            service = cut(string(root["service"]).orEmpty().trim(), SERVICE_CHARS),
            version = cut(string(root["version"]).orEmpty().trim(), CONTEXT_CHARS),
            environment = cut(string(root["environment"]).orEmpty().trim(), CONTEXT_CHARS),
        )

        if (word == null) {
            val said = if (rawStatus == null) {
                "The report has no status"
            } else {
                "Status \"${cut(rawStatus, 40)}\" is not one Nightbell knows"
            }
            return Reading.Report(HealthStatus.DOWN, said, state, override = said)
        }

        if (staleAfterMinutes > 0 && checkedAt != null) {
            val reference = dateHeader ?: now
            val tolerance = if (dateHeader == null) PHONE_CLOCK_TOLERANCE_MS else 0L
            // Clamped because the server stamps `Date` before its writer runs, so a
            // fresh report routinely claims to be a second younger than the response
            // that carried it.
            val ageMs = maxOf(0L, reference.toEpochMilli() - checkedAt.toEpochMilli())
            if (ageMs > staleAfterMinutes * 60_000L + tolerance) {
                val said = "Report is ${ageMs / 60_000} minutes old"
                return Reading.Report(HealthStatus.DOWN, said, state, override = said)
            }
        }

        return Reading.Report(
            verdict = word,
            message = if (word == HealthStatus.DOWN) downMessage(documentMessage, components) else "",
            state = state,
        )
    }

    /** The document's own message, else the worst part's, else the bare fact. */
    private fun downMessage(documentMessage: String, components: List<HealthComponent>): String {
        if (documentMessage.isNotBlank()) return documentMessage
        val worst = components.firstOrNull { it.status == HealthStatus.DOWN }
            ?: return "Reported down"
        return if (worst.message.isBlank()) "${worst.displayName} down" else "${worst.displayName}: ${worst.message}"
    }

    /** What is left of the whole-report limits as the parts are read in order. */
    private class Budget(var conditions: Int = CONDITIONS_KEPT, var log: Int = LOG_KEPT)

    private fun component(
        entry: JsonElement,
        index: Int,
        note: (String) -> Unit,
        budget: Budget,
    ): HealthComponent? {
        val item = entry as? JsonObject ?: run {
            note("Component ${index + 1} is not an object and was skipped.")
            return null
        }
        val id = string(item["id"])?.trim().orEmpty()
        if (id.isEmpty()) {
            note("Component ${index + 1} has no id and was skipped.")
            return null
        }
        val shownId = cut(id, ID_CHARS)
        val rawStatus = string(item["status"])
        if (rawStatus == null) {
            note("Component \"$shownId\" has no status and was skipped.")
            return null
        }
        val status = word(rawStatus)
        val message = cut(string(item["message"]).orEmpty().trim(), MESSAGE_CHARS)
        val sinceText = string(item["since"])
        val since = sinceText?.let { timestamp(it) }
        if (sinceText != null && since == null) note("since on \"$shownId\" is not a time Nightbell can read.")
        val value = number(item["value"])
        val conditions = (item["conditions"] as? JsonArray).orEmpty()
            .mapNotNull { condition(it) }
            .take(minOf(CONDITIONS_PER_PART, budget.conditions))
        budget.conditions -= conditions.size
        val log = (item["log"] as? JsonArray).orEmpty()
            .mapNotNull { logEntry(it) }
            .take(minOf(LOG_PER_PART, budget.log))
        budget.log -= log.size
        return HealthComponent(
            id = shownId,
            name = cut(string(item["name"]).orEmpty().trim(), NAME_CHARS),
            // A word Nightbell cannot read is not good news, so it reads as down,
            // quoted, rather than being guessed at.
            status = status ?: HealthStatus.DOWN,
            message = if (status != null) {
                message
            } else {
                cut("Unknown status \"${cut(rawStatus, 40)}\"" + if (message.isBlank()) "" else ". $message", MESSAGE_CHARS)
            },
            since = since?.toEpochMilli() ?: 0L,
            value = value,
            unit = cut(string(item["unit"]).orEmpty().trim(), UNIT_CHARS),
            durationMs = number(item["durationMs"])?.takeIf { it >= 0 } ?: -1.0,
            conditions = conditions,
            log = log,
        )
    }

    /** A condition, or null when it has no id or no status. Unknown words read as down, like a component's. */
    private fun condition(element: JsonElement): HealthCondition? {
        val item = element as? JsonObject ?: return null
        val id = string(item["id"])?.trim().orEmpty()
        val rawStatus = string(item["status"]) ?: return null
        if (id.isEmpty()) return null
        val status = word(rawStatus)
        val message = cut(string(item["message"]).orEmpty().trim(), MESSAGE_CHARS)
        return HealthCondition(
            id = cut(id, ID_CHARS),
            name = cut(string(item["name"]).orEmpty().trim(), NAME_CHARS),
            status = status ?: HealthStatus.DOWN,
            observed = cut(scalar(item["observed"]), OBSERVED_CHARS),
            rule = cut(string(item["rule"]).orEmpty().trim(), RULE_CHARS),
            unit = cut(string(item["unit"]).orEmpty().trim(), UNIT_CHARS),
            window = cut(string(item["window"]).orEmpty().trim(), UNIT_CHARS),
            message = if (status != null) {
                message
            } else {
                cut("Unknown status \"${cut(rawStatus, 40)}\"" + if (message.isBlank()) "" else ". $message", MESSAGE_CHARS)
            },
        )
    }

    /** A log entry, or null without a readable time or a message. An unknown level reads as info. */
    private fun logEntry(element: JsonElement): HealthLogEntry? {
        val item = element as? JsonObject ?: return null
        val at = string(item["at"])?.let { timestamp(it) } ?: return null
        val message = string(item["message"])?.trim().orEmpty()
        if (message.isEmpty()) return null
        val level = when (string(item["level"])?.trim()?.lowercase()) {
            "error" -> HealthLogLevel.ERROR
            "warn", "warning" -> HealthLogLevel.WARN
            else -> HealthLogLevel.INFO
        }
        val data = (item["data"] as? JsonObject).orEmpty().entries
            .filter { (_, v) -> v is JsonPrimitive }
            .take(DATA_KEYS)
            .map { (k, v) -> cut(k, ID_CHARS) to cut(scalar(v), DATA_VALUE_CHARS) }
        return HealthLogEntry(at = at.toEpochMilli(), level = level, message = cut(message, LOG_MESSAGE_CHARS), data = data)
    }

    private fun number(element: JsonElement?): Double? =
        (element as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

    /** A JSON scalar as a person would write it: `14`, `0.5`, `true`, the string itself, `null`. */
    private fun scalar(element: JsonElement?): String {
        val primitive = element as? JsonPrimitive ?: return ""
        if (primitive is kotlinx.serialization.json.JsonNull) return "null"
        if (primitive.isString) return primitive.content.trim()
        val number = primitive.doubleOrNull ?: return primitive.content
        return if (number == Math.rint(number) && kotlin.math.abs(number) < 1e15) number.toLong().toString() else primitive.content
    }

    private fun string(element: JsonElement?): String? =
        (element as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    /** RFC 3339 with an offset. Anything else is null, never a failure. */
    fun timestamp(text: String): Instant? = try {
        OffsetDateTime.parse(text.trim()).toInstant()
    } catch (_: DateTimeParseException) {
        null
    }

    private fun cut(text: String, limit: Int): String = if (text.length <= limit) text else text.take(limit)

    /** The start of a body, on one line, with anything shaped like a secret taken out. */
    fun excerpt(body: String): String {
        if (body.isBlank()) return ""
        val flat = LogRedactor.scrub(body.take(EXCERPT_CHARS * 2)).replace(Regex("\\s+"), " ").trim()
        return cut(flat, EXCERPT_CHARS)
    }

    /**
     * The check result for one reading.
     *
     * A refused request with no document comes back as a plain status failure.
     * Whether that is "this endpoint needs a sign-in" or "the API refused a
     * fresh token" depends on how the monitor signs in, and only the caller
     * holding the token knows which: see [isRefusal].
     */
    fun result(
        reading: Reading,
        statusCode: Int,
        latencyMs: Long,
        at: Long,
        signIn: HealthSignIn = HealthSignIn.NONE,
    ): CheckResult = when (reading) {
        is Reading.Report -> {
            val down = reading.verdict == HealthStatus.DOWN
            val troubles = troubleLines(reading.state.components)
            CheckResult(
                ok = !down,
                latencyMs = latencyMs,
                statusCode = statusCode,
                failureKind = if (down) FailureKind.REPORTED_DOWN else FailureKind.NONE,
                message = if (down) {
                    reading.message
                } else {
                    "Reported ${reading.verdict.label.lowercase()} in ${latencyMs}ms"
                },
                detail = troubles.joinToString("\n"),
                degraded = reading.verdict == HealthStatus.DEGRADED,
                health = reading.state,
                at = at,
            )
        }

        is Reading.NotHealthFormat -> CheckResult(
            ok = false,
            latencyMs = latencyMs,
            statusCode = statusCode,
            failureKind = FailureKind.NOT_HEALTH_FORMAT,
            message = reading.message,
            detail = reading.excerpt,
            bodyPreview = reading.excerpt,
            health = HealthState(),
            at = at,
        )

        is Reading.Refused -> CheckResult(
            ok = false,
            latencyMs = latencyMs,
            statusCode = statusCode,
            failureKind = FailureKind.STATUS,
            message = if (reading.code == 401) {
                "This endpoint needs a sign-in (401)"
            } else {
                "Access refused (403)"
            },
            detail = reading.excerpt,
            hint = if (signIn == HealthSignIn.NONE) {
                "Set up how Nightbell signs in, in the monitor's settings."
            } else {
                "The sign-in this monitor sends was not accepted."
            },
            bodyPreview = reading.excerpt,
            health = HealthState(),
            at = at,
        )

        is Reading.HttpError -> CheckResult(
            ok = false,
            latencyMs = latencyMs,
            statusCode = statusCode,
            failureKind = FailureKind.STATUS,
            message = "HTTP ${reading.code}",
            detail = reading.excerpt,
            // Seen on the first real target: its admin policy throws on a missing
            // role, and that surfaces as a 500, not a 403.
            hint = if (reading.code == 500 && signIn != HealthSignIn.NONE) {
                "Some apps answer 500 when the signed-in account lacks a role."
            } else {
                ""
            },
            bodyPreview = reading.excerpt,
            health = HealthState(),
            at = at,
        )
    }

    /** A 401 or 403 that carried no document, which the sign-in layer may retry once. */
    /**
     * The address answered with a report, so it is readable without a sign-in.
     *
     * Not `health != null`: [result] puts an empty state on every answer, a
     * login page and a 404 included, and setup once read that as "No sign-in
     * needed" for a web app that had redirected to its own login form.
     */
    fun isReport(result: CheckResult): Boolean =
        result.health != null &&
            (result.failureKind == FailureKind.NONE || result.failureKind == FailureKind.REPORTED_DOWN)

    fun isRefusal(result: CheckResult): Boolean =
        result.health != null &&
            result.failureKind == FailureKind.STATUS &&
            (result.statusCode == 401 || result.statusCode == 403)

    /** One line per part that is not up, worst first, for a notification or a webhook. */
    fun troubleLines(components: List<HealthComponent>): List<String> =
        components
            .filter { it.status != HealthStatus.UP }
            .sortedByDescending { it.status.rank }
            .map { part ->
                val head = "${part.displayName} ${part.status.label.lowercase()}"
                if (part.message.isBlank()) head else "$head: ${part.message}"
            }

    /**
     * The card's second line for a report that is not down.
     *
     * "Payments degraded · 2 of 3 OK" names the part to look at, "3 parts OK"
     * says there was something to read and all of it was fine.
     */
    fun summary(state: HealthState): String {
        val parts = state.components
        if (parts.isEmpty()) return ""
        val ok = parts.count { it.status == HealthStatus.UP }
        val total = maxOf(state.componentsTotal, parts.size)
        val worst = parts.filter { it.status != HealthStatus.UP }.maxByOrNull { it.status.rank }
            ?: return if (total == 1) "1 part OK" else "$total parts OK"
        return "${worst.displayName} ${worst.status.label.lowercase()} · $ok of $total OK"
    }
}
