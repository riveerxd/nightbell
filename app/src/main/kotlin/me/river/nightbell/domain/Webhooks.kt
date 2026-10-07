package me.river.nightbell.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Who a webhook is shaped for.
 *
 * One target type with a format rather than one type per service, because the
 * only thing that differs between them is the body and where a couple of
 * fields go. The address, the events, the scope, the retry and the secrets
 * are the same machinery whichever chat app is on the other end.
 *
 * Serial names are the stored values. Renaming one orphans every target saved
 * under the old name, so they stay put even if a label changes.
 */
@Serializable
enum class WebhookFormat(val label: String) {
    /** Nightbell's own documented JSON, for anything that reads JSON. */
    @SerialName("nightbell_json")
    NIGHTBELL_JSON("Plain JSON"),

    /** A Teams Workflows webhook, which wants an Adaptive Card envelope. */
    @SerialName("teams")
    TEAMS("Microsoft Teams"),

    /** Slack incoming webhooks, and Mattermost and Rocket.Chat, which copy them. */
    @SerialName("slack")
    SLACK("Slack"),

    @SerialName("discord")
    DISCORD("Discord"),

    @SerialName("google_chat")
    GOOGLE_CHAT("Google Chat"),

    @SerialName("ntfy")
    NTFY("ntfy"),

    @SerialName("gotify")
    GOTIFY("Gotify"),

    @SerialName("telegram")
    TELEGRAM("Telegram"),

    /** Any method, any body, any headers. For whatever the presets miss. */
    @SerialName("custom")
    CUSTOM("Custom request"),
    ;

    /** What the address field is called for this format. */
    val addressLabel: String
        get() = when (this) {
            TELEGRAM -> "Bot token"
            NTFY -> "Topic URL"
            GOTIFY -> "Server URL with app token"
            else -> "Webhook URL"
        }

    val addressPlaceholder: String
        get() = when (this) {
            NIGHTBELL_JSON -> "https://example.com/hooks/nightbell"
            TEAMS -> "https://prod-00.westeurope.logic.azure.com/workflows/…"
            SLACK -> "https://hooks.slack.com/services/…"
            DISCORD -> "https://discord.com/api/webhooks/…"
            GOOGLE_CHAT -> "https://chat.googleapis.com/v1/spaces/…"
            NTFY -> "https://ntfy.sh/your-topic"
            GOTIFY -> "https://gotify.example.com/message?token=…"
            TELEGRAM -> "123456789:AA…"
            CUSTOM -> "https://example.com/anything?text={{title}}"
        }

    /** Where the user gets the address from. One or two sentences, read beside the other app. */
    val howTo: String
        get() = when (this) {
            NIGHTBELL_JSON ->
                "Posts one JSON object per event. Good for n8n, Node-RED, Home Assistant, " +
                    "Zapier, a script of your own, or anything else that reads JSON."
            TEAMS ->
                "In the channel, open Workflows and pick \"Send webhook alerts to a channel\". " +
                    "Copy the URL it gives you at the end. The old Office 365 connector URLs are retired."
            SLACK ->
                "Create an incoming webhook for the channel in your Slack app settings. " +
                    "Mattermost and Rocket.Chat incoming webhooks take the same message."
            DISCORD ->
                "Channel settings, Integrations, Webhooks, New Webhook, then Copy Webhook URL."
            GOOGLE_CHAT ->
                "In the space, open Apps and integrations, Webhooks, and add one."
            NTFY ->
                "The topic's full address, on ntfy.sh or your own server. For a protected topic " +
                    "add an Authorization header below."
            GOTIFY ->
                "Create an application in Gotify and paste the server address with its token, " +
                    "or add the token as an X-Gotify-Key header instead."
            TELEGRAM ->
                "Make a bot with @BotFather and paste its token. The chat ID is the user, group " +
                    "or channel it should write to, and the bot has to be a member there."
            CUSTOM ->
                "You choose the method, the headers and the body. Placeholders like {{title}} " +
                    "are filled in when an event is sent, in the address too."
        }

    /** Telegram's address is a token, every other format's is a URL. */
    val addressIsUrl: Boolean get() = this != TELEGRAM
}

/**
 * Something that can be sent to a webhook.
 *
 * The serial name doubles as the `event` value in every payload, so it is the
 * public contract for anyone who wrote a receiver. Never rename one.
 */
@Serializable
enum class WebhookEvent(val label: String, val blurb: String) {
    @SerialName("down")
    DOWN("Down", "Once the failure threshold is reached"),

    @SerialName("still_down")
    STILL_DOWN("Still down", "On the repeat schedule, while it stays down"),

    @SerialName("recovered")
    RECOVERED("Back up", "When a monitor that was reported down answers again"),

    @SerialName("degraded")
    DEGRADED("Slow", "Answering, but over its latency budget"),

    @SerialName("degraded_recovered")
    DEGRADED_RECOVERED("Speed back to normal", "Under its latency budget again"),

    @SerialName("certificate")
    CERTIFICATE("Certificate expiring", "When the phone itself warns about it"),

    @SerialName("github")
    GITHUB("GitHub activity", "Stars, issues, releases and the rest, from repository monitors"),

    @SerialName("acknowledged")
    ACKNOWLEDGED("Urgent page answered", "When someone acknowledges an urgent page on this phone"),

    /** Only ever sent by the test button, never chosen. */
    @SerialName("test")
    TEST("Test", "Sent by the test button"),
    ;

    val code: String get() = when (this) {
        DOWN -> "down"
        STILL_DOWN -> "still_down"
        RECOVERED -> "recovered"
        DEGRADED -> "degraded"
        DEGRADED_RECOVERED -> "degraded_recovered"
        CERTIFICATE -> "certificate"
        GITHUB -> "github"
        ACKNOWLEDGED -> "acknowledged"
        TEST -> "test"
    }

    /** How bad this is, which every format turns into a colour or a priority. */
    val severity: WebhookSeverity get() = when (this) {
        DOWN, STILL_DOWN -> WebhookSeverity.DOWN
        DEGRADED, CERTIFICATE -> WebhookSeverity.WARNING
        RECOVERED, DEGRADED_RECOVERED -> WebhookSeverity.GOOD
        GITHUB, ACKNOWLEDGED, TEST -> WebhookSeverity.INFO
    }

    companion object {
        /** What a user can tick. [TEST] is the button's, not a choice. */
        val choosable: List<WebhookEvent> = entries.filter { it != TEST }

        /** Outage and recovery: what almost everyone means by "tell the channel". */
        val defaults: Set<WebhookEvent> = setOf(DOWN, RECOVERED)
    }
}

/** Rose, amber, mint, or neither. The same four meanings the app's colours carry. */
enum class WebhookSeverity { DOWN, WARNING, GOOD, INFO }

/**
 * Which monitors a target hears about.
 *
 * A group is followed by reference rather than expanded into its members when
 * the target is saved, so a monitor added to the group later is covered without
 * anybody having to come back here.
 */
@Serializable
data class WebhookScope(
    val all: Boolean = true,
    val groupIds: Set<String> = emptySet(),
    val monitorIds: Set<String> = emptySet(),
) {
    fun covers(monitorId: String, groups: List<MonitorGroup>): Boolean {
        if (all) return true
        if (monitorId in monitorIds) return true
        return groups.any { it.id in groupIds && monitorId in it.memberIds }
    }

    val isEmpty: Boolean get() = !all && groupIds.isEmpty() && monitorIds.isEmpty()
}

/**
 * One place events are sent.
 *
 * [url], every header value and [signingSecret] are credentials: a Teams,
 * Slack or Discord webhook address is the whole of the permission to post into
 * that channel. They get the same treatment as the GitHub token. See
 * [WebhookSecrets].
 */
@Serializable
data class WebhookTarget(
    val id: String,
    val name: String = "",
    val format: WebhookFormat = WebhookFormat.NIGHTBELL_JSON,
    /** The address, or for [WebhookFormat.TELEGRAM] the bot token. */
    val url: String = "",
    /** Telegram's chat. Not a secret: it is useless without the token. */
    val chatId: String = "",
    /** Only read for [WebhookFormat.CUSTOM]. Every preset POSTs. */
    val method: HttpMethod = HttpMethod.POST,
    /** Only read for [WebhookFormat.CUSTOM]. */
    val contentType: String = "application/json",
    /** Only read for [WebhookFormat.CUSTOM]. Blank sends the plain JSON body. */
    val bodyTemplate: String = "",
    /** Sent with every format. Values may hold placeholders. */
    val headers: List<HeaderPair> = emptyList(),
    /**
     * Signs each body with HMAC-SHA256 so a receiver can prove it came from
     * here. Blank sends no signature. See [WebhookPayload.signature].
     */
    val signingSecret: String = "",
    val events: Set<WebhookEvent> = WebhookEvent.defaults,
    val scope: WebhookScope = WebhookScope(),
    /**
     * Only send when this phone would have notified as well.
     *
     * Off by default, because a channel is usually read by people who are not
     * asleep when the phone's owner is: quiet hours, a mute and the master
     * switch are about this phone. Threshold, cooldown and repeat apply either
     * way, so a flapping endpoint does not flood the channel.
     */
    val followPhone: Boolean = false,
    /** For a receiver on a homelab box with a self-signed certificate. */
    val acceptAnyCertificate: Boolean = false,
    /** Send through the SOCKS proxy in settings, for a receiver on a hidden service. */
    val useProxy: Boolean = false,
    val enabled: Boolean = true,
    val createdAt: Long = 0L,
) {
    val displayName: String get() = name.trim().ifBlank { format.label }

    /** Address missing, usually because a backup left it behind. */
    val needsAddress: Boolean get() = url.isBlank()

    /** Every value here that must never reach a log line or the screen intact. */
    val secrets: List<String>
        get() = buildList {
            if (url.isNotBlank()) add(url.trim())
            if (signingSecret.isNotBlank()) add(signingSecret.trim())
            headers.forEach { if (it.value.isNotBlank()) add(it.value.trim()) }
            // A token in a query string or a path segment shows up on its own in
            // an exception message often enough that the whole URL is not enough.
            WebhookSecrets.urlParts(url).forEach { add(it) }
        }

    fun wants(event: WebhookEvent): Boolean = event == WebhookEvent.TEST || event in events
}

/**
 * Everything a payload is built from, frozen at the moment the event happened.
 *
 * Persisted in the outbox, so a delivery that waits an hour for the network
 * still says what was true at the time rather than what is true when it leaves.
 * The target's format is not frozen: an address corrected while a delivery is
 * queued should apply to that delivery, which is the reason it is waiting.
 */
@Serializable
data class WebhookFacts(
    val event: WebhookEvent,
    val monitorId: String = "",
    val monitorName: String = "",
    val monitorUrl: String = "",
    val monitorKind: String = "",
    /** The check's own headline, "Timed out", "Unexpected status". */
    val reason: String = "",
    val message: String = "",
    val detail: String = "",
    val statusCode: Int = 0,
    val latencyMs: Long = 0L,
    val sloMs: Int = 0,
    val at: Long = 0L,
    /** When the current outage began, 0 when there is none. */
    val downSinceAt: Long = 0L,
    /** Set on [WebhookEvent.RECOVERED], how long the outage lasted. */
    val downForMs: Long = 0L,
    val certDaysLeft: Int = 0,
    /** For [WebhookEvent.GITHUB], the notification's own wording. */
    val headline: String = "",
    val link: String = "",
    val groups: List<String> = emptyList(),
    val sender: String = "",
)

/**
 * The per-target, per-monitor alert state.
 *
 * Its own track rather than a read of the phone's, because the phone's track
 * stops advancing during quiet hours and while muted. A target that ignores
 * quiet hours would otherwise see "not alerting yet" on every check of a
 * night-long outage and post DOWN every time.
 */
@Serializable
data class WebhookTrack(
    val alerting: Boolean = false,
    val lastAlertAt: Long = 0L,
    val degradedAlerting: Boolean = false,
    val lastDegradedAlertAt: Long = 0L,
    /** First failing check of the current run, for "down since". */
    val failingSinceAt: Long = 0L,
)

/** One delivery waiting to go, or waiting to go again. */
@Serializable
data class PendingDelivery(
    /** Also the `delivery_id` a receiver deduplicates on, so it never changes across retries. */
    val id: String,
    val targetId: String,
    val facts: WebhookFacts,
    val createdAt: Long,
    val attempts: Int = 0,
    val nextAttemptAt: Long = 0L,
)

/** What the settings list says under each target. */
@Serializable
data class WebhookStatus(
    val lastAttemptAt: Long = 0L,
    val lastDeliveredAt: Long = 0L,
    val lastCode: Int = 0,
    /** Blank after a success. Scrubbed before it is stored. */
    val lastError: String = "",
    val failuresInARow: Int = 0,
    /** Deliveries given up on since the last success, so a silent loss is visible. */
    val dropped: Int = 0,
)

/**
 * Everything about webhooks that is state rather than configuration.
 *
 * Top level in the snapshot and deliberately not in a backup: none of it is
 * true on another phone, and an outbox carried across would post a stale
 * outage into a channel from a device that never saw it.
 */
@Serializable
data class WebhookState(
    /** Keyed by [trackKey]. */
    val tracks: Map<String, WebhookTrack> = emptyMap(),
    val outbox: List<PendingDelivery> = emptyList(),
    val status: Map<String, WebhookStatus> = emptyMap(),
) {
    fun track(targetId: String, monitorId: String): WebhookTrack =
        tracks[trackKey(targetId, monitorId)] ?: WebhookTrack()

    fun queuedFor(targetId: String): Int = outbox.count { it.targetId == targetId }

    /**
     * Drops anything that belongs to a target or a monitor that no longer exists.
     *
     * Run on every write that touches this state, so deleting a target cannot
     * leave its queue behind to be retried forever against nothing.
     */
    fun pruned(targetIds: Set<String>, monitorIds: Set<String>): WebhookState {
        val keptTracks = tracks.filterKeys { key ->
            val (target, monitor) = splitKey(key)
            target in targetIds && monitor in monitorIds
        }
        val keptOutbox = outbox.filter { it.targetId in targetIds }
        val keptStatus = status.filterKeys { it in targetIds }
        if (keptTracks.size == tracks.size && keptOutbox.size == outbox.size && keptStatus.size == status.size) {
            return this
        }
        return copy(tracks = keptTracks, outbox = keptOutbox, status = keptStatus)
    }

    companion object {
        /** A pipe cannot appear in either id: both are UUIDs or slugs this app made. */
        fun trackKey(targetId: String, monitorId: String) = "$targetId|$monitorId"

        private fun splitKey(key: String): Pair<String, String> =
            key.substringBefore('|') to key.substringAfter('|')
    }
}

/**
 * Keeping webhook credentials off the screen and out of logs.
 *
 * [Secrets] is shaped around GitHub's token prefixes, and a webhook address is
 * a different animal: the secret is part of a URL, and the host is the part a
 * person needs to see to know which service it points at.
 */
object WebhookSecrets {

    private const val ELLIPSIS = "…"

    /**
     * `https://hooks.slack.com/services/T0/B0/abcdefgh` becomes
     * `hooks.slack.com/…efgh`. The host says which service, the last four say
     * which hook, and nothing in between survives.
     */
    fun redactAddress(raw: String, format: WebhookFormat): String {
        val value = raw.trim()
        if (value.isEmpty()) return ""
        if (!format.addressIsUrl) {
            // A bot token is `<bot id>:<secret>`. The id is public, it is in every
            // message the bot sends, and it is how a person tells two bots apart.
            val botId = value.substringBefore(':', missingDelimiterValue = "")
            return if (botId.isNotBlank() && value.length > 12) {
                "$botId:$ELLIPSIS${value.takeLast(4)}"
            } else {
                ELLIPSIS
            }
        }
        val host = value.substringAfter("://", value).substringBefore('/').substringBefore('?')
            .substringAfter('@')
        // Nothing secret-sized after the host means there is nothing to hide
        // and nothing worth the last four either.
        if (value.substringAfter(host, "").trim('/').length <= 8) return host
        return "$host/$ELLIPSIS${value.takeLast(4)}"
    }

    /**
     * The pieces of a URL long enough to be a token on their own: path segments
     * and query values. Short ones are left alone, because scrubbing "v1" out of
     * an error message corrupts it without protecting anything.
     */
    fun urlParts(raw: String): List<String> {
        val value = raw.trim()
        if (value.isEmpty()) return emptyList()
        val afterHost = value.substringAfter("://", value).substringAfter('/', "")
        val path = afterHost.substringBefore('?').split('/')
        val query = afterHost.substringAfter('?', "").split('&').map { it.substringAfter('=') }
        return (path + query).filter { it.length >= 12 }
    }

    /** Replaces every secret of [target] that appears in [text]. */
    fun scrub(text: String, target: WebhookTarget): String {
        var out = text
        target.secrets.sortedByDescending { it.length }.forEach { secret ->
            if (secret.length >= 8 && out.contains(secret)) out = out.replace(secret, ELLIPSIS)
        }
        return out
    }
}
