package me.river.pulse.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Pure, side-effect free evaluation of everything a monitor can assert.
 * Deliberately free of Android types so it is fully unit-testable.
 */
object Assertions {

    private val lenientJson = Json { isLenient = true; ignoreUnknownKeys = true }

    /** Outcome of one assertion: pass/fail plus human-readable framing. */
    data class Verdict(
        val passed: Boolean,
        val kind: FailureKind = FailureKind.NONE,
        val message: String = "",
        val detail: String = "",
    ) {
        companion object {
            val Pass = Verdict(true)
            fun fail(kind: FailureKind, message: String, detail: String = "") =
                Verdict(false, kind, message, detail)
        }
    }

    fun statusMatches(expectation: StatusExpectation, code: Int): Boolean = when (expectation.mode) {
        StatusMode.EXACT -> code == expectation.code
        StatusMode.ANY_SUCCESS -> code in 200..299
        StatusMode.RANGE -> code in minOf(expectation.rangeStart, expectation.rangeEnd)..
            maxOf(expectation.rangeStart, expectation.rangeEnd)
        StatusMode.ANY -> code > 0
    }

    fun checkStatus(expectation: StatusExpectation, code: Int): Verdict =
        if (statusMatches(expectation, code)) {
            Verdict.Pass
        } else {
            Verdict.fail(
                FailureKind.STATUS,
                "Got $code, expected ${expectation.summary}",
                "HTTP status $code did not satisfy ${expectation.summary}.",
            )
        }

    fun checkBody(assertion: BodyAssertion, body: String): Verdict {
        if (!assertion.isActive) return Verdict.Pass
        val needle = assertion.value
        val haystack = if (assertion.caseSensitive) body else body.lowercase()
        val target = if (assertion.caseSensitive) needle else needle.lowercase()

        return when (assertion.mode) {
            AssertionMode.NONE -> Verdict.Pass

            AssertionMode.CONTAINS ->
                if (haystack.contains(target)) Verdict.Pass
                else Verdict.fail(
                    FailureKind.BODY,
                    "Response does not contain \"${needle.ellipsize()}\"",
                    "Searched ${body.length} characters of the response body.",
                )

            AssertionMode.NOT_CONTAINS ->
                if (!haystack.contains(target)) Verdict.Pass
                else Verdict.fail(
                    FailureKind.BODY,
                    "Response contains forbidden text \"${needle.ellipsize()}\"",
                    "Found \"$needle\" at index ${haystack.indexOf(target)}.",
                )

            AssertionMode.EXACT ->
                if (haystack.trim() == target.trim()) Verdict.Pass
                else Verdict.fail(
                    FailureKind.BODY,
                    "Response body is not an exact match",
                    "Expected ${needle.length} chars, received ${body.length} chars.",
                )

            AssertionMode.REGEX -> {
                val regex = runCatching {
                    Regex(needle, if (assertion.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE))
                }.getOrNull()
                    ?: return Verdict.fail(
                        FailureKind.BAD_CONFIG,
                        "Invalid regular expression",
                        "\"$needle\" is not a valid regex.",
                    )
                if (regex.containsMatchIn(body)) Verdict.Pass
                else Verdict.fail(
                    FailureKind.BODY,
                    "Regex found no match",
                    "Pattern /$needle/ did not match the response body.",
                )
            }

            AssertionMode.JSON_FIELD_EXISTS -> {
                val element = parseJson(body)
                    ?: return Verdict.fail(
                        FailureKind.BODY,
                        "Response is not valid JSON",
                        "Could not parse the body as JSON to look up \"${assertion.jsonPath}\".",
                    )
                val found = resolvePath(element, assertion.jsonPath)
                if (found != null && found !is JsonNull) Verdict.Pass
                else Verdict.fail(
                    FailureKind.BODY,
                    "JSON field \"${assertion.jsonPath}\" is missing",
                    "The path resolved to nothing.",
                )
            }

            AssertionMode.JSON_FIELD_EQUALS -> {
                val element = parseJson(body)
                    ?: return Verdict.fail(
                        FailureKind.BODY,
                        "Response is not valid JSON",
                        "Could not parse the body as JSON to look up \"${assertion.jsonPath}\".",
                    )
                val found = resolvePath(element, assertion.jsonPath)
                    ?: return Verdict.fail(
                        FailureKind.BODY,
                        "JSON field \"${assertion.jsonPath}\" is missing",
                        "Expected it to equal \"$needle\".",
                    )
                val actual = found.scalarText()
                val matches = if (assertion.caseSensitive) actual == needle else actual.equals(needle, true)
                if (matches) Verdict.Pass
                else Verdict.fail(
                    FailureKind.BODY,
                    "${assertion.jsonPath} = \"${actual.ellipsize()}\", expected \"${needle.ellipsize()}\"",
                    "JSON value mismatch at path ${assertion.jsonPath}.",
                )
            }
        }
    }

    fun checkElement(target: ElementTarget, found: Boolean, text: String): Verdict {
        val actual = text.collapseWhitespace()
        return when (target.mode) {
            ElementMode.EXISTS ->
                if (found) Verdict.Pass
                else Verdict.fail(
                    FailureKind.ELEMENT,
                    "Element ${target.displaySelector} not found",
                    "No node matched any of the stored selector strategies.",
                )

            ElementMode.NOT_EXISTS ->
                if (!found) Verdict.Pass
                else Verdict.fail(
                    FailureKind.ELEMENT,
                    "Element ${target.displaySelector} is still present",
                    "Expected the node to be gone, but it rendered with text \"${actual.ellipsize()}\".",
                )

            ElementMode.TEXT_EQUALS -> when {
                !found -> Verdict.fail(
                    FailureKind.ELEMENT,
                    "Element ${target.displaySelector} not found",
                    "Cannot compare text: the node is missing.",
                )
                actual.equals(target.expectedText.collapseWhitespace(), ignoreCase = true) -> Verdict.Pass
                else -> Verdict.fail(
                    FailureKind.ELEMENT,
                    "Text is \"${actual.ellipsize()}\", expected \"${target.expectedText.ellipsize()}\"",
                    "Element text did not match exactly.",
                )
            }

            ElementMode.TEXT_CONTAINS -> when {
                !found -> Verdict.fail(
                    FailureKind.ELEMENT,
                    "Element ${target.displaySelector} not found",
                    "Cannot search text: the node is missing.",
                )
                actual.contains(target.expectedText.collapseWhitespace(), ignoreCase = true) -> Verdict.Pass
                else -> Verdict.fail(
                    FailureKind.ELEMENT,
                    "Text does not contain \"${target.expectedText.ellipsize()}\"",
                    "Element text was \"${actual.ellipsize(160)}\".",
                )
            }

            ElementMode.TEXT_MATCHES_SNAPSHOT -> when {
                !found -> Verdict.fail(
                    FailureKind.ELEMENT,
                    "Element ${target.displaySelector} not found",
                    "Cannot compare against the captured snapshot.",
                )
                actual.equals(target.textSnippet.collapseWhitespace(), ignoreCase = false) -> Verdict.Pass
                else -> Verdict.fail(
                    FailureKind.ELEMENT,
                    "Element text changed",
                    "Snapshot was \"${target.textSnippet.ellipsize()}\", now \"${actual.ellipsize()}\".",
                )
            }
        }
    }

    /** Runs status then body assertions in order and returns the first failure. */
    fun evaluateHttp(monitor: Monitor, code: Int, body: String): Verdict {
        val statusVerdict = checkStatus(monitor.status, code)
        if (!statusVerdict.passed) return statusVerdict
        return checkBody(monitor.assertion, body)
    }

    // ---- JSON helpers -------------------------------------------------------

    /**
     * Parses a payload as JSON. Only objects and arrays count: lenient mode would
     * happily read `<html>` back as a bare string primitive, which would then
     * fail path lookup with a confusing "field missing" message instead of an
     * honest "that isn't JSON".
     */
    fun parseJson(raw: String): JsonElement? =
        runCatching { lenientJson.parseToJsonElement(raw.trim()) }
            .getOrNull()
            ?.takeIf { it is JsonObject || it is JsonArray }

    /**
     * Resolves a lightweight dot/bracket path such as `data.items[0].status`
     * against a parsed JSON tree. Returns null when any hop is missing.
     */
    fun resolvePath(root: JsonElement, path: String): JsonElement? {
        if (path.isBlank()) return root
        var current: JsonElement = root
        for (token in tokenizePath(path)) {
            current = when {
                token.index != null -> (current as? JsonArray)?.getOrNull(token.index) ?: return null
                else -> (current as? JsonObject)?.get(token.key) ?: return null
            }
        }
        return current
    }

    private data class PathToken(val key: String = "", val index: Int? = null)

    private fun tokenizePath(path: String): List<PathToken> {
        val tokens = mutableListOf<PathToken>()
        for (rawSegment in path.split('.')) {
            if (rawSegment.isBlank()) continue
            var name = rawSegment
            val bracket = name.indexOf('[')
            if (bracket >= 0) {
                val head = name.substring(0, bracket)
                if (head.isNotBlank()) tokens += PathToken(key = head)
                var rest = name.substring(bracket)
                while (rest.startsWith("[")) {
                    val close = rest.indexOf(']')
                    if (close < 0) break
                    val idx = rest.substring(1, close).trim().toIntOrNull()
                    if (idx != null) tokens += PathToken(index = idx)
                    rest = rest.substring(close + 1)
                }
            } else {
                tokens += PathToken(key = name)
            }
            name = ""
        }
        return tokens
    }

    private fun JsonElement.scalarText(): String = when (this) {
        is JsonPrimitive -> content
        else -> toString()
    }
}

internal fun String.ellipsize(max: Int = 48): String =
    if (length <= max) this else take(max - 1).trimEnd() + "…"

internal fun String.collapseWhitespace(): String = trim().replace(Regex("\\s+"), " ")
