package me.river.nightbell.domain

import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** A request ready to send, with nothing left to decide. */
data class WebhookRequest(
    val method: HttpMethod,
    val url: String,
    val headers: List<Pair<String, String>>,
    /** Null for a request with no body. */
    val contentType: String?,
    val body: String?,
)

/**
 * Turns one event into the request a given service expects.
 *
 * Every chat format says the same two things, a [title] and a [text], so a
 * monitor reads identically in Teams and in Telegram and only the envelope
 * differs. Everything is built through the JSON tree rather than by string
 * concatenation, because a monitor name with a quote in it is the first thing
 * that breaks a hand-assembled body, and the receiver answers 400 to a message
 * nobody will ever see.
 */
object WebhookPayload {

    /** Bumped only if a field in the plain JSON body changes meaning. */
    const val SCHEMA = 1

    private val ISO = DateTimeFormatter.ISO_INSTANT
    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")

    // The app's own dark-theme colours, so a Discord embed's stripe is the
    // same rose the card on the phone is.
    private const val ROSE = 0xFF4D57
    private const val AMBER = 0xFFB020
    private const val MINT = 0x2FD98A
    private const val INDIGO = 0x6C7BFF

    fun title(facts: WebhookFacts, targetName: String = ""): String {
        val name = facts.monitorName.ifBlank { "A monitor" }
        return when (facts.event) {
            WebhookEvent.DOWN -> "$name is down"
            WebhookEvent.STILL_DOWN -> "$name is still down"
            WebhookEvent.RECOVERED -> "$name is back up"
            WebhookEvent.DEGRADED -> "$name is slow"
            WebhookEvent.DEGRADED_RECOVERED -> "$name is back to normal speed"
            WebhookEvent.CERTIFICATE -> when {
                facts.certDaysLeft < 0 -> "$name's certificate has expired"
                facts.certDaysLeft == 0 -> "$name's certificate expires today"
                facts.certDaysLeft == 1 -> "$name's certificate expires tomorrow"
                else -> "$name's certificate expires in ${facts.certDaysLeft} days"
            }
            WebhookEvent.GITHUB -> facts.headline.ifBlank { "$name has news" }
            WebhookEvent.ACKNOWLEDGED -> "$name: urgent page answered"
            WebhookEvent.TEST -> if (targetName.isBlank()) "Test from Nightbell" else "Test from Nightbell to $targetName"
        }
    }

    fun text(facts: WebhookFacts): String {
        val why = listOf(facts.reason, facts.message)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(": ")
        val latency = latencyText(facts.latencyMs)
        return when (facts.event) {
            WebhookEvent.DOWN -> why.ifBlank { "The check failed." }
            WebhookEvent.STILL_DOWN -> {
                val span = if (facts.downSinceAt > 0L && facts.at > facts.downSinceAt) {
                    "Down for ${spanText(facts.at - facts.downSinceAt)}. "
                } else {
                    ""
                }
                (span + why).trim().ifBlank { "Still failing." }
            }
            WebhookEvent.RECOVERED -> buildString {
                if (facts.downForMs > 0L) append("Down for ${spanText(facts.downForMs)}. ")
                append("Answered in $latency.")
            }
            WebhookEvent.DEGRADED ->
                if (facts.sloMs > 0) {
                    "Answered in $latency, over its ${latencyText(facts.sloMs.toLong())} budget."
                } else {
                    "Answered in $latency."
                }
            WebhookEvent.DEGRADED_RECOVERED ->
                if (facts.sloMs > 0) {
                    "Answered in $latency, inside its ${latencyText(facts.sloMs.toLong())} budget."
                } else {
                    "Answered in $latency."
                }
            WebhookEvent.CERTIFICATE -> facts.message.ifBlank { "Renew it before it lapses." }
            WebhookEvent.GITHUB -> facts.message
            WebhookEvent.ACKNOWLEDGED ->
                if (facts.sender.isBlank()) {
                    "Someone acknowledged the page on the phone. It is still down."
                } else {
                    "Acknowledged on ${facts.sender}. It is still down."
                }
            WebhookEvent.TEST -> "If you can read this, the webhook works. Nothing is down."
        }
    }

    /** The address the event is about, when there is one a person can open. */
    fun link(facts: WebhookFacts): String {
        val candidate = facts.link.ifBlank { facts.monitorUrl }.trim()
        return if (candidate.startsWith("https://") || candidate.startsWith("http://")) candidate else ""
    }

    /**
     * Every placeholder a custom request can use, with its raw value.
     *
     * The public contract of the custom format, listed in the editor and in
     * docs/reference.md. Adding one is fine; renaming one breaks somebody.
     */
    fun placeholders(
        facts: WebhookFacts,
        deliveryId: String,
        targetName: String = "",
        zone: ZoneId = ZoneId.systemDefault(),
    ): Map<String, String> = linkedMapOf(
        "event" to facts.event.code,
        "event_label" to facts.event.label,
        "severity" to facts.event.severity.name.lowercase(),
        "title" to title(facts, targetName),
        "text" to text(facts),
        "monitor" to facts.monitorName,
        "monitor_id" to facts.monitorId,
        "monitor_url" to facts.monitorUrl,
        "link" to link(facts),
        "kind" to facts.monitorKind,
        "reason" to facts.reason,
        "message" to facts.message,
        "detail" to facts.detail,
        "status_code" to if (facts.statusCode > 0) facts.statusCode.toString() else "",
        "latency_ms" to facts.latencyMs.toString(),
        "latency" to latencyText(facts.latencyMs),
        "slo_ms" to facts.sloMs.toString(),
        "at" to iso(facts.at),
        "at_unix" to (facts.at / 1000).toString(),
        "at_local" to clock(facts.at, zone),
        "down_since" to if (facts.downSinceAt > 0L) iso(facts.downSinceAt) else "",
        "down_for" to downFor(facts)?.let(::spanText).orEmpty(),
        "down_for_s" to downFor(facts)?.let { (it / 1000).toString() }.orEmpty(),
        "cert_days_left" to if (facts.event == WebhookEvent.CERTIFICATE) facts.certDaysLeft.toString() else "",
        "groups" to facts.groups.joinToString(", "),
        "sender" to facts.sender.ifBlank { "Nightbell" },
        "delivery_id" to deliveryId,
        "test" to (facts.event == WebhookEvent.TEST).toString(),
    )

    /**
     * Builds the request.
     *
     * @param sentAtMs the moment this attempt leaves, which is what the
     *   signature's timestamp has to be. A retry is signed afresh, or a receiver
     *   that rejects stale timestamps would reject every retry.
     */
    fun render(
        target: WebhookTarget,
        facts: WebhookFacts,
        deliveryId: String,
        sentAtMs: Long,
        appVersion: String = "",
        zone: ZoneId = ZoneId.systemDefault(),
    ): WebhookRequest {
        val values = placeholders(facts, deliveryId, target.displayName, zone)
        val base = when (target.format) {
            WebhookFormat.NIGHTBELL_JSON -> json(target.url.trim(), nightbellJson(facts, deliveryId, target.displayName))
            WebhookFormat.TEAMS -> json(target.url.trim(), teams(facts, target.displayName, zone))
            WebhookFormat.SLACK -> json(target.url.trim(), slack(facts, target.displayName, zone))
            WebhookFormat.DISCORD -> json(target.url.trim(), discord(facts, target.displayName))
            WebhookFormat.GOOGLE_CHAT -> json(target.url.trim(), googleChat(facts, target.displayName, zone))
            WebhookFormat.NTFY -> ntfy(target.url.trim(), facts, target.displayName)
            WebhookFormat.GOTIFY -> json(gotifyUrl(target.url.trim()), gotify(facts, target.displayName))
            WebhookFormat.TELEGRAM -> json(telegramUrl(target.url.trim()), telegram(target.chatId, facts, target.displayName))
            WebhookFormat.CUSTOM -> custom(target, values, facts, deliveryId)
        }
        val headers = buildList {
            add("User-Agent" to if (appVersion.isBlank()) "Nightbell" else "Nightbell/$appVersion")
            add("X-Nightbell-Event" to facts.event.code)
            add("X-Nightbell-Delivery" to deliveryId)
            val secret = target.signingSecret.trim()
            if (secret.isNotEmpty()) {
                val seconds = (sentAtMs / 1000).toString()
                add("X-Nightbell-Timestamp" to seconds)
                add("X-Nightbell-Signature" to "sha256=" + signature(secret, seconds, base.body.orEmpty()))
            }
            target.headers.filterNot { it.isBlank }.forEach { header ->
                add(header.name.trim() to headerSafe(substitute(header.value.trim(), values, Escape.NONE)))
            }
        }
        return base.copy(headers = headers)
    }

    /**
     * HMAC-SHA256 of `timestamp.body`, hex encoded.
     *
     * The timestamp is inside the signed bytes so a captured request cannot be
     * replayed later with the same signature. The same shape Stripe and Slack
     * use, so a receiver written for either can be pointed at this.
     */
    fun signature(secret: String, timestampSeconds: String, body: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val digest = mac.doFinal("$timestampSeconds.$body".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    // ---- formats -------------------------------------------------------------

    private fun json(url: String, body: JsonObject) = WebhookRequest(
        method = HttpMethod.POST,
        url = url,
        headers = emptyList(),
        contentType = "application/json; charset=utf-8",
        body = body.toString(),
    )

    fun nightbellJson(facts: WebhookFacts, deliveryId: String, targetName: String = ""): JsonObject =
        buildJsonObject {
            put("schema", SCHEMA)
            put("event", facts.event.code)
            put("event_label", facts.event.label)
            put("severity", facts.event.severity.name.lowercase())
            put("title", title(facts, targetName))
            put("text", text(facts))
            putJsonObject("monitor") {
                put("id", facts.monitorId)
                put("name", facts.monitorName)
                put("url", facts.monitorUrl)
                put("kind", facts.monitorKind)
                putJsonArray("groups") { facts.groups.forEach { add(JsonPrimitive(it)) } }
            }
            putJsonObject("check") {
                put("reason", facts.reason)
                put("message", facts.message)
                put("detail", facts.detail)
                put("status_code", if (facts.statusCode > 0) JsonPrimitive(facts.statusCode) else JsonNull)
                put("latency_ms", facts.latencyMs)
                put("slo_ms", if (facts.sloMs > 0) JsonPrimitive(facts.sloMs) else JsonNull)
            }
            put("at", iso(facts.at))
            put("at_unix", facts.at / 1000)
            put("down_since", if (facts.downSinceAt > 0L) JsonPrimitive(iso(facts.downSinceAt)) else JsonNull)
            put("down_for_seconds", downFor(facts)?.let { JsonPrimitive(it / 1000) } ?: JsonNull)
            put(
                "certificate_days_left",
                if (facts.event == WebhookEvent.CERTIFICATE) JsonPrimitive(facts.certDaysLeft) else JsonNull,
            )
            put("link", link(facts).ifBlank { null })
            put("sender", facts.sender.ifBlank { "Nightbell" })
            put("delivery_id", deliveryId)
            put("test", facts.event == WebhookEvent.TEST)
        }

    /**
     * The Workflows "post to a channel" template reads `attachments[].content`
     * as an Adaptive Card. 1.4 is the newest version every Teams client renders.
     */
    private fun teams(facts: WebhookFacts, targetName: String, zone: ZoneId): JsonObject {
        val colour = when (facts.event.severity) {
            WebhookSeverity.DOWN -> "Attention"
            WebhookSeverity.WARNING -> "Warning"
            WebhookSeverity.GOOD -> "Good"
            WebhookSeverity.INFO -> "Default"
        }
        val card = buildJsonObject {
            put("\$schema", "http://adaptivecards.io/schemas/adaptive-card.json")
            put("type", "AdaptiveCard")
            put("version", "1.4")
            putJsonArray("body") {
                add(
                    buildJsonObject {
                        put("type", "TextBlock")
                        put("text", title(facts, targetName))
                        put("weight", "Bolder")
                        put("size", "Medium")
                        put("color", colour)
                        put("wrap", true)
                    },
                )
                add(
                    buildJsonObject {
                        put("type", "TextBlock")
                        put("text", text(facts))
                        put("wrap", true)
                    },
                )
                val facts2 = factRows(facts, zone)
                if (facts2.isNotEmpty()) {
                    add(
                        buildJsonObject {
                            put("type", "FactSet")
                            put(
                                "facts",
                                buildJsonArray {
                                    facts2.forEach { (name, value) ->
                                        add(buildJsonObject { put("title", name); put("value", value) })
                                    }
                                },
                            )
                        },
                    )
                }
            }
            val link = link(facts)
            if (link.isNotEmpty()) {
                putJsonArray("actions") {
                    add(
                        buildJsonObject {
                            put("type", "Action.OpenUrl")
                            put("title", "Open ${hostOf(link)}")
                            put("url", link)
                        },
                    )
                }
            }
        }
        return buildJsonObject {
            put("type", "message")
            put("summary", title(facts, targetName))
            putJsonArray("attachments") {
                add(
                    buildJsonObject {
                        put("contentType", "application/vnd.microsoft.card.adaptive")
                        put("contentUrl", JsonNull)
                        put("content", card)
                    },
                )
            }
        }
    }

    /**
     * `text` is the whole message for Mattermost and Rocket.Chat, which ignore
     * `blocks`, and the notification preview for Slack, which renders them.
     */
    private fun slack(facts: WebhookFacts, targetName: String, zone: ZoneId): JsonObject {
        val emoji = when (facts.event.severity) {
            WebhookSeverity.DOWN -> ":red_circle:"
            WebhookSeverity.WARNING -> ":large_orange_circle:"
            WebhookSeverity.GOOD -> ":large_green_circle:"
            WebhookSeverity.INFO -> ":large_blue_circle:"
        }
        val title = title(facts, targetName)
        val body = text(facts)
        val link = link(facts)
        val context = buildList {
            if (link.isNotEmpty()) add("<${slackEscape(link)}|${slackEscape(hostOf(link))}>")
            if (facts.at > 0L) add(clock(facts.at, zone))
            add("via ${slackEscape(facts.sender.ifBlank { "Nightbell" })}")
        }.joinToString(" · ")
        return buildJsonObject {
            put("text", "$emoji *${slackEscape(title)}*\n${slackEscape(body)}")
            putJsonArray("blocks") {
                add(
                    buildJsonObject {
                        put("type", "section")
                        putJsonObject("text") {
                            put("type", "mrkdwn")
                            put("text", "$emoji *${slackEscape(title)}*\n${slackEscape(body)}")
                        }
                    },
                )
                add(
                    buildJsonObject {
                        put("type", "context")
                        putJsonArray("elements") {
                            add(buildJsonObject { put("type", "mrkdwn"); put("text", context) })
                        }
                    },
                )
            }
        }
    }

    private fun discord(facts: WebhookFacts, targetName: String): JsonObject {
        val colour = when (facts.event.severity) {
            WebhookSeverity.DOWN -> ROSE
            WebhookSeverity.WARNING -> AMBER
            WebhookSeverity.GOOD -> MINT
            WebhookSeverity.INFO -> INDIGO
        }
        val link = link(facts)
        return buildJsonObject {
            put("username", "Nightbell")
            putJsonArray("embeds") {
                add(
                    buildJsonObject {
                        // Discord's own limits. Past them the whole message is refused
                        // with a 400, which is worse than a trimmed line.
                        put("title", title(facts, targetName).take(256))
                        put("description", text(facts).take(4000))
                        put("color", colour)
                        if (link.isNotEmpty()) put("url", link)
                        if (facts.at > 0L) put("timestamp", iso(facts.at))
                        val rows = discordFields(facts)
                        if (rows.isNotEmpty()) {
                            putJsonArray("fields") {
                                rows.forEach { (name, value) ->
                                    add(
                                        buildJsonObject {
                                            put("name", name)
                                            put("value", value.take(1000))
                                            put("inline", true)
                                        },
                                    )
                                }
                            }
                        }
                        putJsonObject("footer") { put("text", "via ${facts.sender.ifBlank { "Nightbell" }}") }
                    },
                )
            }
            // A monitor name like "@everyone is down" must not ping a server.
            putJsonObject("allowed_mentions") { put("parse", JsonArray(emptyList())) }
        }
    }

    private fun googleChat(facts: WebhookFacts, targetName: String, zone: ZoneId): JsonObject {
        val link = link(facts)
        val lines = buildList {
            add("*${title(facts, targetName)}*")
            add(text(facts))
            val tail = listOfNotNull(
                link.ifBlank { null },
                if (facts.at > 0L) clock(facts.at, zone) else null,
                "via ${facts.sender.ifBlank { "Nightbell" }}",
            ).joinToString(" · ")
            add(tail)
        }
        return buildJsonObject { put("text", lines.joinToString("\n")) }
    }

    /**
     * Published as JSON to the server root rather than as a plain body to the
     * topic, because ntfy's title travels in a header otherwise, and a header
     * cannot carry "Café API is down" without an encoding most servers ignore.
     */
    private fun ntfy(url: String, facts: WebhookFacts, targetName: String): WebhookRequest {
        val query = url.substringAfter('?', "")
        val path = url.substringBefore('?').trimEnd('/')
        val topic = path.substringAfterLast('/')
        val root = path.substringBeforeLast('/') + "/" + if (query.isEmpty()) "" else "?$query"
        val priority = when (facts.event.severity) {
            WebhookSeverity.DOWN -> 5
            WebhookSeverity.WARNING -> 4
            WebhookSeverity.GOOD -> 3
            WebhookSeverity.INFO -> 3
        }
        val tag = when (facts.event.severity) {
            WebhookSeverity.DOWN -> "rotating_light"
            WebhookSeverity.WARNING -> "warning"
            WebhookSeverity.GOOD -> "white_check_mark"
            WebhookSeverity.INFO -> "information_source"
        }
        val link = link(facts)
        val body = buildJsonObject {
            put("topic", topic)
            put("title", title(facts, targetName))
            put("message", text(facts))
            put("priority", priority)
            putJsonArray("tags") { add(JsonPrimitive(tag)) }
            if (link.isNotEmpty()) put("click", link)
        }
        return json(root, body)
    }

    /** Accepts the server root, or the full `/message` address Gotify documents. */
    fun gotifyUrl(url: String): String {
        val path = url.substringBefore('?').trimEnd('/')
        val query = url.substringAfter('?', "")
        val full = if (path.endsWith("/message")) path else "$path/message"
        return if (query.isEmpty()) full else "$full?$query"
    }

    private fun gotify(facts: WebhookFacts, targetName: String): JsonObject {
        val priority = when (facts.event.severity) {
            WebhookSeverity.DOWN -> 8
            WebhookSeverity.WARNING -> 5
            WebhookSeverity.GOOD -> 4
            WebhookSeverity.INFO -> 2
        }
        val link = link(facts)
        return buildJsonObject {
            put("title", title(facts, targetName))
            put("message", text(facts))
            put("priority", priority)
            if (link.isNotEmpty()) {
                putJsonObject("extras") {
                    putJsonObject("client::notification") {
                        putJsonObject("click") { put("url", link) }
                    }
                }
            }
        }
    }

    /** A bare token goes to Telegram; a full URL is a self-hosted Bot API server. */
    fun telegramUrl(tokenOrUrl: String): String =
        if (tokenOrUrl.startsWith("http://") || tokenOrUrl.startsWith("https://")) {
            tokenOrUrl
        } else {
            "https://api.telegram.org/bot$tokenOrUrl/sendMessage"
        }

    /** Plain text, no parse mode, so nothing in a monitor name can break the markup. */
    private fun telegram(chatId: String, facts: WebhookFacts, targetName: String): JsonObject {
        val link = link(facts)
        val message = buildString {
            append(title(facts, targetName))
            append("\n")
            append(text(facts))
            if (link.isNotEmpty()) append("\n").append(link)
        }
        return buildJsonObject {
            put("chat_id", chatId.trim())
            put("text", message.take(4000))
            put("disable_web_page_preview", true)
        }
    }

    private fun custom(
        target: WebhookTarget,
        values: Map<String, String>,
        facts: WebhookFacts,
        deliveryId: String,
    ): WebhookRequest {
        val url = substitute(target.url.trim(), values, Escape.URL)
        val method = target.method
        val bodyAllowed = method != HttpMethod.GET && method != HttpMethod.HEAD
        val contentType = target.contentType.trim().ifBlank { "application/json" }
        val body = when {
            !bodyAllowed -> null
            target.bodyTemplate.isBlank() -> nightbellJson(facts, deliveryId, target.displayName).toString()
            else -> substitute(target.bodyTemplate, values, escapeFor(contentType))
        }
        return WebhookRequest(
            method = method,
            url = url,
            headers = emptyList(),
            contentType = if (body == null) null else contentType,
            body = body,
        )
    }

    // ---- templating ----------------------------------------------------------

    enum class Escape { NONE, JSON, URL }

    /**
     * How a value is made safe for where it lands. A JSON body gets string
     * escaping without the quotes, because the template already has them
     * around the placeholder: `"text": "{{title}}"`.
     */
    fun escapeFor(contentType: String): Escape {
        val type = contentType.lowercase()
        return when {
            "json" in type -> Escape.JSON
            "x-www-form-urlencoded" in type -> Escape.URL
            else -> Escape.NONE
        }
    }

    private val PLACEHOLDER = Regex("\\{\\{\\s*([a-z_]+)\\s*\\}\\}")

    /**
     * Fills `{{name}}` placeholders. An unknown name is left exactly as typed, so
     * a typo shows up in the received message instead of vanishing.
     */
    fun substitute(template: String, values: Map<String, String>, escape: Escape): String =
        PLACEHOLDER.replace(template) { match ->
            val value = values[match.groupValues[1]] ?: return@replace match.value
            when (escape) {
                Escape.NONE -> value
                Escape.URL -> URLEncoder.encode(value, "UTF-8").replace("+", "%20")
                Escape.JSON -> JsonPrimitive(value).toString().removeSurrounding("\"")
            }
        }

    /** Any facts at all, for listing which placeholder names exist. */
    val sampleFacts = WebhookFacts(event = WebhookEvent.TEST)

    /** Placeholders the template uses that do not exist. Shown in the editor. */
    fun unknownPlaceholders(template: String): List<String> {
        val known = placeholders(sampleFacts, "").keys
        return PLACEHOLDER.findAll(template).map { it.groupValues[1] }.filter { it !in known }.distinct().toList()
    }

    // ---- small things --------------------------------------------------------

    private fun factRows(facts: WebhookFacts, zone: ZoneId): List<Pair<String, String>> = buildList {
        if (facts.monitorUrl.isNotBlank()) add("Address" to facts.monitorUrl)
        if (facts.statusCode > 0) add("Status" to facts.statusCode.toString())
        if (facts.downSinceAt > 0L && facts.event in setOf(WebhookEvent.DOWN, WebhookEvent.STILL_DOWN)) {
            add("Down since" to clock(facts.downSinceAt, zone))
        }
        if (facts.groups.isNotEmpty()) add("Group" to facts.groups.joinToString(", "))
        if (facts.at > 0L) add("At" to clock(facts.at, zone))
        add("Sent by" to facts.sender.ifBlank { "Nightbell" })
    }

    private fun discordFields(facts: WebhookFacts): List<Pair<String, String>> = buildList {
        if (facts.statusCode > 0) add("Status" to facts.statusCode.toString())
        if (facts.latencyMs > 0L && facts.event != WebhookEvent.DOWN && facts.event != WebhookEvent.STILL_DOWN) {
            add("Latency" to latencyText(facts.latencyMs))
        }
        if (facts.groups.isNotEmpty()) add("Group" to facts.groups.joinToString(", "))
    }

    private fun downFor(facts: WebhookFacts): Long? = when {
        facts.downForMs > 0L -> facts.downForMs
        facts.downSinceAt > 0L && facts.at > facts.downSinceAt -> facts.at - facts.downSinceAt
        else -> null
    }

    fun spanText(ms: Long): String {
        val seconds = ms / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24
        return when {
            seconds < 60 -> "${seconds.coerceAtLeast(1)} s"
            minutes < 60 -> "$minutes min"
            hours < 24 -> if (minutes % 60 == 0L) "$hours h" else "$hours h ${minutes % 60} min"
            else -> if (hours % 24 == 0L) "$days d" else "$days d ${hours % 24} h"
        }
    }

    fun latencyText(ms: Long): String = when {
        ms < 1000 -> "$ms ms"
        else -> "%.1f s".format(java.util.Locale.ROOT, ms / 1000.0)
    }

    private fun iso(ms: Long): String = ISO.format(Instant.ofEpochMilli(ms))

    private fun clock(ms: Long, zone: ZoneId): String = CLOCK.format(Instant.ofEpochMilli(ms).atZone(zone))

    private fun hostOf(url: String): String =
        url.substringAfter("://").substringBefore('/').substringBefore('?').substringAfter('@')

    private fun slackEscape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** A header value cannot carry a line break; OkHttp throws on one. */
    private fun headerSafe(value: String): String = value.replace('\r', ' ').replace('\n', ' ')
}
