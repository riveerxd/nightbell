package me.river.nightbell.domain

import kotlinx.serialization.json.Json

/**
 * What is wrong with a webhook target, field by field.
 *
 * Errors block the save. Warnings are things that will probably not do what the
 * person meant but might be deliberate, and they are said once, beside the
 * field, rather than refusing a configuration nobody here can fully judge.
 */
object WebhookValidation {

    enum class Field { NAME, ADDRESS, CHAT_ID, METHOD, BODY, HEADERS, EVENTS, SCOPE, SECRET }

    data class Problem(val field: Field, val severity: Validation.Severity, val message: String)

    data class Report(val problems: List<Problem>) {
        fun of(field: Field): Problem? = problems.filter { it.field == field }.maxByOrNull { it.severity.ordinal }
        val isValid: Boolean get() = problems.none { it.severity == Validation.Severity.ERROR }
        val blockingMessage: String? get() = problems.firstOrNull { it.severity == Validation.Severity.ERROR }?.message
    }

    private val TELEGRAM_TOKEN = Regex("^\\d{3,}:[A-Za-z0-9_-]{20,}$")
    private val TELEGRAM_CHAT = Regex("^(-?\\d{3,}|@[A-Za-z][A-Za-z0-9_]{3,})$")
    private val HEADER_NAME = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")

    fun check(target: WebhookTarget): Report {
        val problems = mutableListOf<Problem>()
        fun error(field: Field, message: String) = problems.add(Problem(field, Validation.Severity.ERROR, message))
        fun warn(field: Field, message: String) = problems.add(Problem(field, Validation.Severity.WARNING, message))

        val address = target.url.trim()
        when {
            address.isEmpty() -> error(
                Field.ADDRESS,
                if (target.format == WebhookFormat.TELEGRAM) "Paste the bot's token" else "Paste the webhook's address",
            )
            target.format == WebhookFormat.TELEGRAM && !address.startsWith("http") ->
                if (!TELEGRAM_TOKEN.matches(address)) {
                    error(Field.ADDRESS, "A bot token looks like 123456789:AA… with no spaces")
                }
            else -> {
                // Placeholders are legal in a custom address and are not a URL yet,
                // so they are filled with something URL-shaped before judging it.
                val probe = WebhookPayload.substitute(
                    address,
                    WebhookPayload.placeholders(WebhookFacts(event = WebhookEvent.TEST), "x").mapValues { "x" },
                    WebhookPayload.Escape.URL,
                )
                Validation.urlNote(probe)?.let { note ->
                    if (note.severity == Validation.Severity.ERROR) error(Field.ADDRESS, note.message)
                }
            }
        }
        if (address.isNotEmpty() && target.format == WebhookFormat.TEAMS) {
            val host = address.substringAfter("://").substringBefore('/').lowercase()
            if (host.endsWith("webhook.office.com") || host == "outlook.office.com") {
                warn(
                    Field.ADDRESS,
                    "This is an Office 365 connector address, which Microsoft has retired. " +
                        "Make a Workflows webhook in the channel instead.",
                )
            }
        }
        if (address.isNotEmpty() && target.format == WebhookFormat.DISCORD && !address.contains("/api/webhooks/")) {
            warn(Field.ADDRESS, "Discord webhook addresses contain /api/webhooks/. Check it was copied whole.")
        }

        if (target.format == WebhookFormat.TELEGRAM) {
            val chat = target.chatId.trim()
            when {
                chat.isEmpty() -> error(Field.CHAT_ID, "Which chat should the bot write to?")
                !TELEGRAM_CHAT.matches(chat) -> error(
                    Field.CHAT_ID,
                    "A chat ID is a number, negative for groups, or a channel's @name",
                )
            }
        }

        target.headers.filterNot { it.isBlank }.forEach { header ->
            val name = header.name.trim()
            if (name.isEmpty()) {
                error(Field.HEADERS, "A header with a value needs a name")
            } else if (!HEADER_NAME.matches(name)) {
                error(Field.HEADERS, "\"$name\" can't be a header name: no spaces or punctuation like : or ,")
            }
        }

        if (target.format == WebhookFormat.CUSTOM) {
            val unknown = (
                WebhookPayload.unknownPlaceholders(target.bodyTemplate) +
                    WebhookPayload.unknownPlaceholders(target.url) +
                    target.headers.flatMap { WebhookPayload.unknownPlaceholders(it.value) }
                ).distinct()
            if (unknown.isNotEmpty()) {
                warn(
                    Field.BODY,
                    unknown.joinToString(", ") { "{{$it}}" } +
                        (if (unknown.size == 1) " is not a placeholder" else " are not placeholders") +
                        " and will be sent exactly as typed",
                )
            }
            val bodyless = target.method == HttpMethod.GET || target.method == HttpMethod.HEAD
            if (bodyless && target.bodyTemplate.isNotBlank()) {
                warn(Field.METHOD, "${target.method.name} sends no body, so the body below is ignored")
            }
            if (!bodyless && target.bodyTemplate.isNotBlank() &&
                WebhookPayload.escapeFor(target.contentType) == WebhookPayload.Escape.JSON &&
                !isJsonOnceFilled(target)
            ) {
                warn(Field.BODY, "This is not valid JSON once the placeholders are filled in")
            }
        }

        if (target.events.isEmpty()) error(Field.EVENTS, "Pick at least one thing to send")
        if (target.scope.isEmpty) error(Field.SCOPE, "Pick at least one monitor or group, or send for all of them")
        if (target.signingSecret.isNotBlank() && target.signingSecret.trim().length < 16) {
            warn(Field.SECRET, "Short secrets are easy to guess. 32 random characters is a good length")
        }
        return Report(problems)
    }

    private fun isJsonOnceFilled(target: WebhookTarget): Boolean {
        val facts = WebhookFacts(
            event = WebhookEvent.DOWN,
            monitorName = "Name with \"quotes\" and a\nnewline",
            message = "message",
            at = 1L,
        )
        val body = WebhookPayload.substitute(
            target.bodyTemplate,
            WebhookPayload.placeholders(facts, "id"),
            WebhookPayload.Escape.JSON,
        )
        return runCatching { Json.parseToJsonElement(body) }.isSuccess
    }
}

/** What the settings row says about a target, and in which tone. */
object WebhookStatusLine {

    enum class Tone { QUIET, WAITING, FAILING }

    data class Line(val text: String, val tone: Tone)

    fun of(target: WebhookTarget, status: WebhookStatus?, queued: Int, nowMs: Long): Line {
        if (!target.enabled) return Line("Off, nothing is sent", Tone.QUIET)
        if (target.needsAddress) {
            return Line("Needs its address. A backup leaves it out, so paste it again", Tone.WAITING)
        }
        val dropped = status?.dropped ?: 0
        val droppedNote = if (dropped > 0) " · $dropped given up on" else ""
        val queuedNote = if (queued > 0) " · $queued waiting" else ""
        if (status != null && status.failuresInARow > 0) {
            val why = status.lastError.ifBlank { "No answer" }
            return Line("Failing: $why$queuedNote$droppedNote", Tone.FAILING)
        }
        if (queued > 0) return Line("$queued waiting to send$droppedNote", Tone.WAITING)
        if (status != null && status.lastDeliveredAt > 0L) {
            return Line("Last delivered ${ago(nowMs - status.lastDeliveredAt)}$droppedNote", Tone.QUIET)
        }
        return Line("Nothing sent yet. Use the test button to try it", Tone.QUIET)
    }

    private fun ago(ms: Long): String = when {
        ms < 60_000 -> "just now"
        ms < 3_600_000 -> "${ms / 60_000} min ago"
        ms < 86_400_000 -> "${ms / 3_600_000} h ago"
        else -> "${ms / 86_400_000} d ago"
    }
}
