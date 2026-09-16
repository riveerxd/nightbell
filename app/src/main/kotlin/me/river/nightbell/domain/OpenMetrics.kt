package me.river.nightbell.domain

/**
 * The OpenMetrics and Prometheus text exposition format, read back into samples.
 *
 * Pure text in, data out: no Android types, no network, so the whole thing is
 * unit-testable and the awkward parts are pinned by tests rather than by hope.
 *
 * Both formats are handled because in practice both are on the wire. OpenMetrics
 * is the standard the issue linked; what node_exporter actually serves to a
 * client that did not negotiate otherwise is the older Prometheus text format.
 * They differ in small ways that matter here: the terminating `# EOF` line, and
 * whether a timestamp is integer milliseconds or floating point seconds.
 */
object OpenMetrics {

    /** One line of exposition, once it has been read. */
    data class Sample(
        val name: String,
        val labels: Map<String, String> = emptyMap(),
        val value: Double,
        /** Milliseconds, whichever unit the line carried. Null when it carried none. */
        val timestampMs: Long? = null,
    ) {
        /** `node_cpu_seconds_total{cpu="0",mode="idle"}`, for a message. */
        val display: String
            get() = if (labels.isEmpty()) {
                name
            } else {
                name + labels.entries
                    .sortedBy { it.key }
                    .joinToString(",", prefix = "{", postfix = "}") { (k, v) -> "$k=\"$v\"" }
            }
    }

    /**
     * One test against one label.
     *
     * The four operators are PromQL's, spelled the same way, because the person
     * typing this already knows PromQL and inventing a fifth syntax for them to
     * learn would be a decision taken for no one's benefit.
     */
    data class LabelMatcher(val name: String, val op: Op, val value: String) {
        enum class Op(val symbol: String) {
            EQUALS("="),
            NOT_EQUALS("!="),
            MATCHES("=~"),
            NOT_MATCHES("!~"),
        }

        /**
         * Anchored, the way Prometheus anchors a regex matcher.
         *
         * `device=~"sd"` matching `sda` would be a different language from the
         * one the user is copying from, and the difference would only show up as
         * a monitor quietly watching more series than it was asked to.
         */
        private val regex: Regex? by lazy(LazyThreadSafetyMode.NONE) {
            if (op != Op.MATCHES && op != Op.NOT_MATCHES) {
                null
            } else {
                runCatching { Regex("^(?:$value)$") }.getOrNull()
            }
        }

        val isValid: Boolean
            get() = name.isNotBlank() &&
                (op == Op.EQUALS || op == Op.NOT_EQUALS || regex != null)

        fun matches(labels: Map<String, String>): Boolean {
            // Absent reads as empty, which is how Prometheus reads it too, so
            // `job=""` selects the series that carry no job at all.
            val actual = labels[name].orEmpty()
            return when (op) {
                Op.EQUALS -> actual == value
                Op.NOT_EQUALS -> actual != value
                Op.MATCHES -> regex?.matches(actual) == true
                Op.NOT_MATCHES -> regex?.matches(actual) == false
            }
        }

        override fun toString(): String = "$name${op.symbol}\"$value\""
    }

    /** A parsed label filter, plus whatever was wrong with it. */
    data class Selector(val matchers: List<LabelMatcher>, val error: String? = null) {
        val isValid: Boolean get() = error == null

        fun matches(labels: Map<String, String>): Boolean = matchers.all { it.matches(labels) }
    }

    // ------------------------------------------------------------ exposition

    /**
     * Every sample in a scrape, comment lines and blanks dropped.
     *
     * A line this cannot read is skipped rather than failing the scrape. An
     * exporter is free to expose things this does not model, and one unreadable
     * line among four thousand is not a reason to report a host down. The caller
     * distinguishes "nothing here was parseable", which is what pointing a
     * monitor at an HTML error page looks like, by finding the list empty.
     */
    fun parse(body: String): List<Sample> {
        val samples = mutableListOf<Sample>()
        for (raw in body.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            // `# EOF` terminates an OpenMetrics body. Anything after it is not
            // part of the exposition, and a proxy that appends its own trailer
            // is a real thing to meet.
            if (line == "# EOF") break
            if (line.startsWith("#")) continue
            parseLine(line)?.let { samples += it }
        }
        return samples
    }

    /** One exposition line, or null when it is not one. */
    fun parseLine(line: String): Sample? {
        var index = 0
        val text = line
        fun skipSpace() {
            while (index < text.length && (text[index] == ' ' || text[index] == '\t')) index++
        }

        skipSpace()
        val nameStart = index
        if (index >= text.length || !isNameStart(text[index])) return null
        index++
        while (index < text.length && isNameChar(text[index])) index++
        val name = text.substring(nameStart, index)

        var labels: Map<String, String> = emptyMap()
        skipSpace()
        if (index < text.length && text[index] == '{') {
            val parsed = readLabels(text, index) ?: return null
            labels = parsed.first
            index = parsed.second
        }

        skipSpace()
        if (index >= text.length) return null
        val valueStart = index
        while (index < text.length && text[index] != ' ' && text[index] != '\t') index++
        val value = parseValue(text.substring(valueStart, index)) ?: return null

        skipSpace()
        val timestamp = if (index < text.length) {
            val tsStart = index
            while (index < text.length && text[index] != ' ' && text[index] != '\t') index++
            parseTimestampMs(text.substring(tsStart, index))
        } else {
            null
        }

        return Sample(name = name, labels = labels, value = value, timestampMs = timestamp)
    }

    /**
     * The label block starting at `{`, and where it ended.
     *
     * Hand-rolled rather than a regex because a label value may legally contain
     * a comma, a brace and an escaped quote, and every regex that looks like it
     * handles that one stops at the first `"` inside `path="/a,b"`.
     */
    private fun readLabels(text: String, open: Int): Pair<Map<String, String>, Int>? {
        var index = open + 1
        val labels = linkedMapOf<String, String>()
        fun skipSpace() {
            while (index < text.length && (text[index] == ' ' || text[index] == '\t')) index++
        }
        while (true) {
            skipSpace()
            if (index >= text.length) return null
            if (text[index] == '}') return labels to index + 1
            if (text[index] == ',') {
                index++
                continue
            }
            if (!isNameStart(text[index])) return null
            val nameStart = index
            while (index < text.length && isNameChar(text[index])) index++
            val name = text.substring(nameStart, index)
            skipSpace()
            if (index >= text.length || text[index] != '=') return null
            index++
            skipSpace()
            if (index >= text.length || text[index] != '"') return null
            index++
            val builder = StringBuilder()
            while (true) {
                if (index >= text.length) return null
                when (val ch = text[index]) {
                    '\\' -> {
                        index++
                        if (index >= text.length) return null
                        // The three escapes the format defines. Anything else
                        // keeps its backslash, because inventing an unescape for
                        // a sequence the spec does not list would corrupt a
                        // Windows path that is simply a value.
                        when (val escaped = text[index]) {
                            'n' -> builder.append('\n')
                            '"' -> builder.append('"')
                            '\\' -> builder.append('\\')
                            else -> builder.append('\\').append(escaped)
                        }
                        index++
                    }

                    '"' -> {
                        index++
                        labels[name] = builder.toString()
                        break
                    }

                    else -> {
                        builder.append(ch)
                        index++
                    }
                }
            }
        }
    }

    /**
     * A sample value.
     *
     * `+Inf`, `-Inf` and `Inf` are the format's spelling and Kotlin reads none
     * of them, which matters more than it sounds: `+Inf` is the value of every
     * histogram's last bucket, so a parser that drops it drops a line from
     * nearly every scrape it will ever see.
     */
    fun parseValue(token: String): Double? = when (token.trim()) {
        "" -> null
        "NaN", "nan" -> Double.NaN
        "+Inf", "Inf", "inf", "+inf" -> Double.POSITIVE_INFINITY
        "-Inf", "-inf" -> Double.NEGATIVE_INFINITY
        else -> token.trim().toDoubleOrNull()
    }

    /**
     * A trailing timestamp, in milliseconds however it was written.
     *
     * The two formats disagree here and both are on the wire: Prometheus text
     * writes integer milliseconds, OpenMetrics writes seconds with a fractional
     * part. A decimal point is the only thing that tells them apart, and reading
     * one as the other is off by a factor of a thousand.
     */
    fun parseTimestampMs(token: String): Long? {
        val text = token.trim()
        if (text.isEmpty()) return null
        if (text.contains('.') || text.contains('e') || text.contains('E')) {
            val seconds = text.toDoubleOrNull() ?: return null
            if (seconds.isNaN() || seconds.isInfinite()) return null
            return (seconds * 1000.0).toLong()
        }
        return text.toLongOrNull()
    }

    // ---------------------------------------------------------- user filters

    /**
     * A label filter as a person typed it.
     *
     * Deliberately more forgiving than [parse] is about a scrape. This is a text
     * field, so it takes the braces somebody pasted along with the selector,
     * single quotes, no quotes at all, and a trailing comma left behind while
     * deleting the last clause. The exposition parser stays strict, because
     * there the input is a machine's and being liberal would hide a body that
     * is not exposition at all.
     */
    fun parseSelector(raw: String): Selector {
        val text = raw.trim().removeSurrounding("{", "}").trim()
        if (text.isEmpty()) return Selector(emptyList())
        val matchers = mutableListOf<LabelMatcher>()
        for (clause in splitClauses(text)) {
            val piece = clause.trim().trim(',').trim()
            if (piece.isEmpty()) continue
            val matcher = parseClause(piece)
                ?: return Selector(emptyList(), "\"$piece\" isn't a label filter. Use name=\"value\".")
            if (!matcher.isValid) {
                return Selector(emptyList(), "\"${matcher.value}\" isn't a valid regular expression")
            }
            matchers += matcher
        }
        return Selector(matchers)
    }

    private fun parseClause(clause: String): LabelMatcher? {
        // Longest operator first: `!=` and `=~` both contain a character that
        // would otherwise be read as a bare `=` and split the clause in the
        // wrong place.
        val op = when {
            clause.contains("!~") -> LabelMatcher.Op.NOT_MATCHES
            clause.contains("!=") -> LabelMatcher.Op.NOT_EQUALS
            clause.contains("=~") -> LabelMatcher.Op.MATCHES
            clause.contains("=") -> LabelMatcher.Op.EQUALS
            else -> return null
        }
        val at = clause.indexOf(op.symbol)
        val name = clause.substring(0, at).trim()
        if (name.isEmpty() || !name.all { isNameChar(it) } || !isNameStart(name[0])) return null
        val value = clause.substring(at + op.symbol.length).trim()
            .removeSurrounding("\"")
            .removeSurrounding("'")
        return LabelMatcher(name, op, value)
    }

    /** Splits on commas that are not inside a quoted value. */
    private fun splitClauses(text: String): List<String> {
        val out = mutableListOf<String>()
        val builder = StringBuilder()
        var quote: Char? = null
        var index = 0
        while (index < text.length) {
            val ch = text[index]
            when {
                quote != null && ch == '\\' && index + 1 < text.length -> {
                    builder.append(ch).append(text[index + 1])
                    index++
                }

                quote != null && ch == quote -> {
                    quote = null
                    builder.append(ch)
                }

                quote == null && (ch == '"' || ch == '\'') -> {
                    quote = ch
                    builder.append(ch)
                }

                quote == null && ch == ',' -> {
                    out += builder.toString()
                    builder.clear()
                }

                else -> builder.append(ch)
            }
            index++
        }
        out += builder.toString()
        return out
    }

    // ------------------------------------------------------------- selection

    /** Every sample called [name] that satisfies [selector]. */
    fun select(samples: List<Sample>, name: String, selector: Selector): List<Sample> {
        val wanted = name.trim()
        return samples.filter {
            (wanted.isEmpty() || it.name == wanted) && selector.matches(it.labels)
        }
    }

    /**
     * Metric names in this scrape that look like what was asked for.
     *
     * Exists for one failure the format guarantees somebody will hit. A counter
     * declared `# TYPE http_requests counter` is exposed on the wire as
     * `http_requests_total`, so the name in the dashboard and the name in the
     * scrape are different strings, and a monitor typed from the dashboard finds
     * nothing with no explanation. Saying "did you mean" costs one pass over
     * names this code already has in hand.
     */
    fun suggestNames(samples: List<Sample>, name: String, limit: Int = 3): List<String> {
        val wanted = name.trim()
        if (wanted.isEmpty()) return emptyList()
        val names = samples.map { it.name }.distinct()
        if (names.contains(wanted)) return emptyList()
        val lower = wanted.lowercase()
        return names.filter { it.lowercase().contains(lower) || lower.contains(it.lowercase()) }
            .sortedBy { it.length }
            .take(limit)
    }

    // ASCII only, which is what the format defines. `isLetter` would also accept
    // every Unicode letter, and the first job of this parser is to be able to say
    // that an HTML error page is not a scrape.
    private fun isNameStart(ch: Char): Boolean =
        ch in 'a'..'z' || ch in 'A'..'Z' || ch == '_' || ch == ':'

    private fun isNameChar(ch: Char): Boolean = isNameStart(ch) || ch in '0'..'9'
}
