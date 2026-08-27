package me.river.nightbell.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What kind of thing a monitor watches. */
@Serializable
enum class MonitorKind {
    /** Plain reachability + status-code check. */
    @SerialName("http_status")
    HTTP_STATUS,

    /** Full request control: method, headers, body, response assertions. */
    @SerialName("advanced_request")
    ADVANCED_REQUEST,

    /** Loads a real page and inspects one DOM element the user picked. */
    @SerialName("website_element")
    WEBSITE_ELEMENT,

    /**
     * Polls one GitHub repository and reports what changed about it.
     *
     * The odd one out, and worth saying why it belongs here anyway. Every other
     * kind answers "is this up"; this one answers "what happened". They share the
     * cadence, the store, the alert policy and the notification plumbing, which is
     * most of a monitor, so the alternative was a second app with a second copy of
     * all of it.
     */
    @SerialName("github_repo")
    GITHUB_REPO,
    ;

    val label: String
        get() = when (this) {
            HTTP_STATUS -> "Status check"
            ADVANCED_REQUEST -> "Request & response"
            WEBSITE_ELEMENT -> "Page element"
            GITHUB_REPO -> "GitHub repo"
        }

    val blurb: String
        get() = when (this) {
            HTTP_STATUS -> "Ping a URL and expect a status code."
            ADVANCED_REQUEST -> "Send a crafted request, assert on what comes back."
            WEBSITE_ELEMENT -> "Watch one element on a real rendered page."
            GITHUB_REPO -> "Stars, issues and releases on one repository."
        }
}

@Serializable
enum class HttpMethod { GET, POST, PUT, PATCH, DELETE, HEAD;

    /** HEAD/GET cannot carry a request body. */
    val allowsBody: Boolean get() = this != GET && this != HEAD

    /**
     * Whether sending this twice is defined to be the same as sending it once.
     *
     * Straight out of RFC 9110. It gates the checker's retry: a request that a
     * server may have read and acted on before the connection died must not be
     * replayed, because nothing on the wire can tell that case apart from one the
     * server never saw.
     */
    val isIdempotent: Boolean get() = this != POST && this != PATCH
}

@Serializable
data class HeaderPair(val name: String = "", val value: String = "") {
    val isBlank: Boolean get() = name.isBlank() && value.isBlank()
}

@Serializable
enum class StatusMode {
    /** Exactly one code, e.g. 200 or 204 or 301. */
    EXACT,

    /** Any 2xx. */
    ANY_SUCCESS,

    /** Inclusive custom range. */
    RANGE,

    /** Any response at all — only reachability matters. */
    ANY,
    ;

    val label: String
        get() = when (this) {
            EXACT -> "Exact code"
            ANY_SUCCESS -> "Any 2xx"
            RANGE -> "Range"
            ANY -> "Any response"
        }
}

@Serializable
data class StatusExpectation(
    val mode: StatusMode = StatusMode.EXACT,
    val code: Int = 200,
    val rangeStart: Int = 200,
    val rangeEnd: Int = 299,
) {
    val summary: String
        get() = when (mode) {
            StatusMode.EXACT -> "= $code"
            StatusMode.ANY_SUCCESS -> "2xx"
            StatusMode.RANGE -> "$rangeStart–$rangeEnd"
            StatusMode.ANY -> "any"
        }
}

@Serializable
enum class AssertionMode {
    NONE,
    CONTAINS,
    NOT_CONTAINS,
    EXACT,
    REGEX,
    JSON_FIELD_EQUALS,
    JSON_FIELD_EXISTS,
    ;

    val label: String
        get() = when (this) {
            NONE -> "No body check"
            CONTAINS -> "Contains"
            NOT_CONTAINS -> "Does not contain"
            EXACT -> "Exactly equals"
            REGEX -> "Matches regex"
            JSON_FIELD_EQUALS -> "JSON field equals"
            JSON_FIELD_EXISTS -> "JSON field exists"
        }

    val needsValue: Boolean get() = this != NONE && this != JSON_FIELD_EXISTS
    val needsPath: Boolean get() = this == JSON_FIELD_EQUALS || this == JSON_FIELD_EXISTS
}

@Serializable
data class BodyAssertion(
    val mode: AssertionMode = AssertionMode.NONE,
    val value: String = "",
    val jsonPath: String = "",
    val caseSensitive: Boolean = false,
) {
    val isActive: Boolean get() = mode != AssertionMode.NONE
}

@Serializable
enum class ElementMode {
    EXISTS,
    NOT_EXISTS,
    TEXT_EQUALS,
    TEXT_CONTAINS,
    TEXT_MATCHES_SNAPSHOT,
    ;

    val label: String
        get() = when (this) {
            EXISTS -> "Element exists"
            NOT_EXISTS -> "Element is gone"
            TEXT_EQUALS -> "Text equals"
            TEXT_CONTAINS -> "Text contains"
            TEXT_MATCHES_SNAPSHOT -> "Text unchanged"
        }
}

/**
 * A robust signature for one DOM node, captured by tapping it in the in-app
 * preview. Several strategies are stored so a check can degrade gracefully when
 * a site reshuffles its markup: id → css path → xpath → text signature.
 */
@Serializable
data class ElementTarget(
    val cssSelector: String = "",
    val xpath: String = "",
    val elementId: String = "",
    val tagName: String = "",
    val classSignature: String = "",
    val textSnippet: String = "",
    val attribute: String = "",
    val mode: ElementMode = ElementMode.EXISTS,
    val expectedText: String = "",
    /** User-supplied nickname, shown wherever several elements are listed. */
    val label: String = "",
) {
    val isCaptured: Boolean
        get() = cssSelector.isNotBlank() || xpath.isNotBlank() || elementId.isNotBlank()

    val displaySelector: String
        get() = when {
            elementId.isNotBlank() -> "#$elementId"
            cssSelector.isNotBlank() -> cssSelector
            xpath.isNotBlank() -> xpath
            else -> "—"
        }

    /** What to call this element in a list: label → tag → selector. */
    val displayLabel: String
        get() = label.ifBlank {
            when {
                tagName.isNotBlank() -> "<$tagName>"
                else -> displaySelector
            }
        }
}

@Serializable
enum class SoundChoice {
    SILENT,
    DEFAULT_NOTIFICATION,
    ALARM,
    RINGTONE,
    ;

    val label: String
        get() = when (this) {
            SILENT -> "Silent"
            DEFAULT_NOTIFICATION -> "Notification tone"
            ALARM -> "Alarm tone"
            RINGTONE -> "Ringtone"
        }
}

/**
 * Named haptic personalities. Patterns are `off, on, off, on…` in millis, which
 * is what both [android.os.Vibrator] and notification channels expect.
 */
/** What a pause switches off. */
@Serializable
enum class PauseScope {
    /**
     * Nothing runs at all: no checks, no samples, no alerts.
     *
     * The same shape as the offline gate, and for the same reason. A phone with
     * one bar in a forest is technically online, so every check times out, every
     * monitor goes down at once, and all of it is written into the uptime history
     * as fact. Not checking is the only way to not record that.
     */
    @SerialName("stop_checks")
    STOP_CHECKS,

    /**
     * Checks keep running, nothing is announced.
     *
     * Costs the false history that [STOP_CHECKS] avoids, and buys a dashboard
     * that is still live: useful when the outage is real and known and the only
     * thing you want back is silence.
     */
    @SerialName("alerts_only")
    ALERTS_ONLY,
    ;

    val label: String
        get() = when (this) {
            STOP_CHECKS -> "Stop checking"
            ALERTS_ONLY -> "Stay silent"
        }

    val blurb: String
        get() = when (this) {
            STOP_CHECKS -> "No checks run, so nothing false lands in your history."
            ALERTS_ONLY -> "Checks keep running, the dashboard stays live, nothing pages you."
        }
}

/** What the pause button does when it is tapped. */
@Serializable
enum class PauseChoice {
    @SerialName("stop_checks")
    STOP_CHECKS,

    @SerialName("alerts_only")
    ALERTS_ONLY,

    /** Ask which one, every time. One more tap, no assumptions. */
    @SerialName("ask")
    ASK,
    ;

    /** The scope this implies, or null when the user has to be asked. */
    val scope: PauseScope?
        get() = when (this) {
            STOP_CHECKS -> PauseScope.STOP_CHECKS
            ALERTS_ONLY -> PauseScope.ALERTS_ONLY
            ASK -> null
        }

    val label: String
        get() = when (this) {
            STOP_CHECKS -> "Stop checking"
            ALERTS_ONLY -> "Stay silent"
            ASK -> "Ask me"
        }
}

/**
 * A standing instruction to leave the user alone.
 *
 * Separate from muting, which is per monitor and per outage. This is the whole
 * fleet, and it exists for the case the offline gate cannot catch: a phone with
 * just enough signal to count as online and not enough to complete a request.
 * Every monitor fails at once, and every one of those alerts is about the walk,
 * not the services.
 *
 * Persisted rather than held in memory because the check paths run in whatever
 * process WorkManager gives them, and a pause that forgot itself on process
 * death would be no pause at all.
 */
@Serializable
data class PauseState(
    /** Epoch millis at which monitoring resumes. Ignored when [indefinite]. */
    val until: Long = 0L,
    /** No end. Stays paused until the user says otherwise. */
    val indefinite: Boolean = false,
    val scope: PauseScope = PauseScope.STOP_CHECKS,
    /** When it began, so the banner can say more than "paused". */
    val since: Long = 0L,
) {
    fun isActive(nowMs: Long): Boolean = indefinite || until > nowMs

    /**
     * Whether checks should be skipped outright.
     *
     * Note the asymmetry with alerts: *any* active pause silences, and only
     * [PauseScope.STOP_CHECKS] stops the checking. A pause that could still page
     * would not be one.
     */
    fun stopsChecks(nowMs: Long): Boolean = isActive(nowMs) && scope == PauseScope.STOP_CHECKS

    /** Millis until this lifts, or null when nothing will lift it. */
    fun remainingMs(nowMs: Long): Long? =
        if (!isActive(nowMs) || indefinite) null else (until - nowMs).coerceAtLeast(0L)

    companion object {
        /** The durations the button offers. Null is the indefinite entry. */
        val OFFERED_MINUTES: List<Int?> = listOf(30, 60, 240, 480, null)

        fun timed(nowMs: Long, minutes: Int, scope: PauseScope): PauseState = PauseState(
            until = nowMs + minutes * 60_000L,
            indefinite = false,
            scope = scope,
            since = nowMs,
        )

        fun forever(nowMs: Long, scope: PauseScope): PauseState = PauseState(
            until = 0L,
            indefinite = true,
            scope = scope,
            since = nowMs,
        )
    }
}

@Serializable
enum class VibrationStyle {
    TICK,
    DOUBLE_PULSE,
    LONG_BUZZ,
    HEARTBEAT,
    SOS,
    ESCALATING,
    ;

    val label: String
        get() = when (this) {
            TICK -> "Tick"
            DOUBLE_PULSE -> "Double pulse"
            LONG_BUZZ -> "Long buzz"
            HEARTBEAT -> "Heartbeat"
            SOS -> "S · O · S"
            ESCALATING -> "Escalating"
        }

    val pattern: LongArray
        get() = when (this) {
            TICK -> longArrayOf(0, 35)
            DOUBLE_PULSE -> longArrayOf(0, 90, 110, 90)
            LONG_BUZZ -> longArrayOf(0, 700)
            HEARTBEAT -> longArrayOf(0, 120, 90, 240, 380, 120, 90, 240)
            SOS -> longArrayOf(0, 90, 90, 90, 90, 90, 250, 320, 130, 320, 130, 320, 250, 90, 90, 90, 90, 90)
            ESCALATING -> longArrayOf(0, 60, 120, 130, 120, 220, 120, 340, 120, 520)
        }

    /** Per-step amplitudes for `VibrationEffect.createWaveform`, aligned with [pattern]. */
    val amplitudes: IntArray
        get() = when (this) {
            TICK -> intArrayOf(0, 140)
            DOUBLE_PULSE -> intArrayOf(0, 200, 0, 255)
            LONG_BUZZ -> intArrayOf(0, 210)
            HEARTBEAT -> intArrayOf(0, 255, 0, 150, 0, 255, 0, 150)
            SOS -> IntArray(pattern.size) { if (it % 2 == 0) 0 else 255 }
            ESCALATING -> intArrayOf(0, 90, 0, 130, 0, 170, 0, 210, 0, 255)
        }
}

@Serializable
data class AlertPolicy(
    val enabled: Boolean = true,
    val alertOnDown: Boolean = true,
    val alertOnRecovery: Boolean = true,
    /** Consecutive failures required before shouting. Kills flaky-network noise. */
    val failureThreshold: Int = 1,
    val sound: SoundChoice = SoundChoice.DEFAULT_NOTIFICATION,
    val vibrate: Boolean = true,
    val vibrationStyle: VibrationStyle = VibrationStyle.DOUBLE_PULSE,
    /** Keep nagging while it stays down. */
    val repeatEnabled: Boolean = false,
    val repeatEveryMinutes: Int = 30,
    /** Minimum gap between two alerts for the same monitor. */
    val cooldownMinutes: Int = 10,
    val quietHoursEnabled: Boolean = false,
    val quietStartMinute: Int = 22 * 60,
    val quietEndMinute: Int = 7 * 60,
    /** When true, a down alert still fires during quiet hours (muted sound). */
    val criticalBypassesQuiet: Boolean = false,

    // ---- degraded / latency-SLO track ---------------------------------------
    // Deliberately independent of the down track: "slow" and "broken" are
    // different incidents, and someone who wants to know about a 3-second API
    // usually does not want that alert on the same cooldown as an outage.
    /** Fire an alert when a monitor is UP but slower than its latency SLO. */
    val alertOnDegraded: Boolean = false,
    /** All-clear when latency drops back under the SLO. */
    val alertOnDegradedRecovery: Boolean = true,
    /** Minimum gap between two degraded alerts for the same monitor. */
    val degradedCooldownMinutes: Int = 30,
    val degradedRepeatEnabled: Boolean = false,
    val degradedRepeatEveryMinutes: Int = 60,
) {
    val summary: String
        get() = buildList {
            if (!enabled) return "Alerts off"
            add(sound.label)
            if (vibrate) add(vibrationStyle.label)
            if (repeatEnabled) add("repeat ${repeatEveryMinutes}m")
            if (failureThreshold > 1) add("after $failureThreshold fails")
            if (alertOnDegraded) add("degraded alerts")
        }.joinToString(" · ")
}

@Serializable
data class Monitor(
    val id: String,
    val name: String = "",
    val kind: MonitorKind = MonitorKind.HTTP_STATUS,
    val url: String = "",
    val method: HttpMethod = HttpMethod.GET,
    val headers: List<HeaderPair> = emptyList(),
    val body: String = "",
    val contentType: String = "application/json",
    val status: StatusExpectation = StatusExpectation(),
    val assertion: BodyAssertion = BodyAssertion(),
    /**
     * The first watched element.
     *
     * Kept as its own field — rather than folded into [elements] — so a store
     * written by 1.0.0 still decodes. [migrated] lifts it into the list; the
     * list is what every checker and screen reads.
     */
    val element: ElementTarget? = null,
    /** Every element watched on one page load. See [targets]. */
    val elements: List<ElementTarget> = emptyList(),
    /**
     * What a [MonitorKind.GITHUB_REPO] monitor watches. Ignored by every other
     * kind, and defaulted so a store written before this existed still decodes.
     */
    val github: GitHubWatch = GitHubWatch(),
    val timeoutSeconds: Int = 15,
    val intervalMinutes: Int = 15,
    /**
     * Follow 3xx responses.
     *
     * On by default, and worth knowing that this is what can change a monitor's
     * scheme underneath it: an endpoint answering plain HTTP with a redirect to
     * `https://` turns an http monitor into a TLS check. That is how issue #6
     * reached a certificate error from a URL with no `https` anywhere in it. See
     * [tlsTrust], and `SelfSignedCertificateTest` for the shape of it.
     */
    val followRedirects: Boolean = true,
    /** How much this monitor's certificate has to prove. See [TlsTrust]. */
    val tlsTrust: TlsTrust = TlsTrust.SYSTEM,
    /**
     * Send this monitor's requests through the SOCKS5 proxy set up in settings
     * rather than straight out of the device.
     *
     * Per monitor, not global, and that is the whole point: the ask was to watch
     * one onion service without putting the entire phone behind Tor, and a single
     * global switch would drag every clearnet check through it as well.
     *
     * Does nothing unless [GlobalSettings.socksProxyEnabled] is on, and nothing
     * for a page-element monitor, which is rendered by a WebView that has no
     * SOCKS support to offer. See [ProxyRoute].
     */
    val useProxy: Boolean = false,
    /**
     * A proxy address for this monitor alone, overriding the shared one.
     *
     * Blank inherits [GlobalSettings.socksProxyHost], which is the common case:
     * one Tor daemon on the device, every routed monitor through it. It exists
     * because one address cannot serve every hidden network, and the app offers
     * both: Tor listens on 9050 and I2P's SOCKS proxy on 4447, so watching one
     * service on each needs two addresses. See [ProxyRoute.override].
     */
    val proxyHost: String = "",
    /** 0 borrows [GlobalSettings.socksProxyPort]. Only read when [proxyHost] is set. */
    val proxyPort: Int = 0,
    /**
     * Seconds to allow a routed check, instead of [timeoutSeconds].
     *
     * A hidden service is not slow in the way a slow website is slow. Reaching one
     * means Tor building a rendezvous circuit through six relays and fetching a
     * descriptor first, which routinely takes tens of seconds on mobile and is
     * bounded by Tor's own SocksTimeout of 120s, not by anything the server does.
     * The 15s default that suits a clearnet endpoint reads that as an outage.
     *
     * 0 uses [GlobalSettings.proxiedTimeoutSeconds], which is what most people
     * want. See [effectiveTimeoutSeconds].
     */
    val proxyTimeoutSeconds: Int = 0,
    val enabled: Boolean = true,
    /** When true this monitor inherits the global alert policy. */
    val useGlobalAlerts: Boolean = true,
    val alert: AlertPolicy = AlertPolicy(),
    /**
     * Nag until acknowledged while this monitor is down. See
     * [UrgentAlerts] for the state machine.
     */
    val urgent: Boolean = false,
    /** Gap between two urgent re-alerts for an unacknowledged outage. */
    val urgentRepeatMinutes: Int = 5,
    /**
     * Latency budget in millis. A successful response slower than this is
     * [Health.DEGRADED]. 0 means "inherit [GlobalSettings.defaultLatencySloMs]".
     */
    val latencySloMs: Int = 0,
    val accent: Int = 0,
    val createdAt: Long = 0L,
) {
    val displayName: String
        get() = name.ifBlank {
            // A repository is named `owner/repo` everywhere else in the world, so
            // falling back to `github.com/owner/repo` would be the one place it
            // isn't.
            if (kind == MonitorKind.GITHUB_REPO && github.repository.isSet) {
                github.slug
            } else {
                prettyHost
            }
        }

    val prettyHost: String
        get() = url
            .removePrefix("https://")
            .removePrefix("http://")
            .removeSuffix("/")
            .ifBlank { "new monitor" }

    /** Every captured element this monitor watches, oldest store format included. */
    val targets: List<ElementTarget>
        get() = when {
            elements.isNotEmpty() -> elements.filter { it.isCaptured }
            element != null && element.isCaptured -> listOf(element)
            else -> emptyList()
        }

    /**
     * Normalised copy: [elements] is authoritative and [element] mirrors its
     * head so an older build reading the same store still finds a target.
     */
    val migrated: Monitor
        get() {
            val list = targets
            return if (list == elements && element == list.firstOrNull()) {
                this
            } else {
                copy(elements = list, element = list.firstOrNull())
            }
        }

    fun withTargets(list: List<ElementTarget>): Monitor =
        copy(elements = list, element = list.firstOrNull())

    /** Effective latency budget, or 0 when neither monitor nor global sets one. */
    fun sloMs(settings: GlobalSettings): Int =
        if (latencySloMs > 0) latencySloMs else settings.defaultLatencySloMs

    /**
     * How long this check is allowed to take, in seconds.
     *
     * A routed check gets its own budget, because the circuit is most of the wait
     * and none of it says anything about the monitored service. Precedence runs
     * monitor override, then the global proxied default, then the monitor's own
     * ordinary timeout: a monitor that has deliberately raised [timeoutSeconds]
     * past the proxied default keeps the larger of the two rather than being
     * quietly cut back.
     */
    fun effectiveTimeoutSeconds(settings: GlobalSettings, proxied: Boolean): Int {
        if (!proxied) return timeoutSeconds
        if (proxyTimeoutSeconds > 0) return proxyTimeoutSeconds
        return maxOf(timeoutSeconds, settings.proxiedTimeoutSeconds)
    }
}

@Serializable
enum class Health {
    UNKNOWN,
    UP,
    DOWN,
    DEGRADED,
    PAUSED,
    ;

    val label: String
        get() = when (this) {
            UNKNOWN -> "Not checked"
            UP -> "Operational"
            DOWN -> "Down"
            DEGRADED -> "Degraded"
            PAUSED -> "Paused"
        }
}

@Serializable
data class Sample(
    val at: Long,
    val ok: Boolean,
    val latencyMs: Long,
    val code: Int = 0,
    val note: String = "",
)

@Serializable
data class MonitorRuntime(
    val health: Health = Health.UNKNOWN,
    val lastCheckedAt: Long = 0L,
    val lastLatencyMs: Long = 0L,
    val lastCode: Int = 0,
    val lastMessage: String = "",
    val lastDetail: String = "",
    val consecutiveFailures: Int = 0,
    val consecutiveSuccesses: Int = 0,
    val lastAlertAt: Long = 0L,
    val alerting: Boolean = false,
    /** Epoch millis until which alerts for this monitor are snoozed. */
    val mutedUntil: Long = 0L,
    val lastElementText: String = "",
    /** One entry per watched element, aligned with [Monitor.targets]. */
    val lastElementTexts: List<String> = emptyList(),

    /**
     * Last-seen counts, ids and ETags for a [MonitorKind.GITHUB_REPO] monitor.
     *
     * Per monitor rather than global, because two monitors on two repositories
     * share nothing: a single last-seen issue id would have the second monitor
     * announce the first one's issues and then go permanently quiet.
     */
    val github: GitHubState = GitHubState(),

    // ---- degraded track -----------------------------------------------------
    /**
     * What the phone's own connection appeared to be adding on the last check,
     * per [NetworkBaseline]. Kept so the UI can explain a discounted reading;
     * [lastLatencyMs] stays the number actually measured.
     */
    val lastNetworkExcessMs: Long = 0L,
    /** The connection was in no state to judge slowness through. */
    val lastLatencySuspect: Boolean = false,
    val degradedAlerting: Boolean = false,
    val lastDegradedAlertAt: Long = 0L,

    // ---- certificate track --------------------------------------------------
    /** `notAfter` of the leaf certificate last seen, 0 if none. */
    val certExpiresAt: Long = 0L,
    val certIssuer: String = "",
    /**
     * The public key this monitor is pinned to, in `sha256/…` form, or empty.
     *
     * Recorded on the first successful check under [TlsTrust.PINNED] and required
     * by every check after it.
     *
     * On the runtime rather than on [Monitor] because it is something the app
     * observed rather than something the user chose. The practical payoff is that
     * clearing a monitor's history re-arms the pin, which is exactly what you want
     * after deliberately replacing a certificate, and it needs no separate button
     * to say so.
     *
     * It does travel in a backup, along with the rest of the runtime, and that is
     * deliberate. The weak moment in trust on first use is the first use: a fresh
     * install re-pinning whatever answers would trust an impostor without a
     * murmur if one had appeared in the meantime. Carrying the key across removes
     * that moment rather than repeating it. Nothing is given away by doing so
     * either, since a public key hash is not a secret and is visible to anyone who
     * connects to the server.
     */
    val certPin: String = "",
    /**
     * [CertificateWatch.Level.rank] most recently announced.
     *
     * Persisted rather than derived: the point of the track is to speak once per
     * escalation, and a counter held in memory would re-announce the same expiry
     * on every process start for a fortnight.
     */
    val certAlertedLevel: Int = 0,
    val lastCertAlertAt: Long = 0L,

    // ---- urgent track -------------------------------------------------------
    /** An urgent outage is in progress and has *not* been acknowledged. */
    val urgentActive: Boolean = false,
    /** The user acknowledged the current outage; stays true until recovery. */
    val urgentAcknowledged: Boolean = false,
    val lastUrgentAlertAt: Long = 0L,
    /**
     * How many times this outage has been paged, counting the first.
     *
     * Persisted rather than derived so the page can say "reminder 4" truthfully
     * across a process restart. Up to 2.0.0 the notification hardcoded
     * "Reminder #1" on every repeat, which made a working escalation look stuck.
     * Reset by recovery and by acknowledgement, both of which end the outage as
     * far as paging is concerned.
     */
    val urgentPageCount: Int = 0,
    /** When this outage was first observed, for the "down for …" line. */
    val urgentSinceAt: Long = 0L,

    val samples: List<Sample> = emptyList(),
) {
    /** Pure-domain view of the urgent state machine's inputs. */
    val urgentState: UrgentAlerts.State
        get() = UrgentAlerts.State(urgentActive, urgentAcknowledged, lastUrgentAlertAt)

    fun withUrgentState(state: UrgentAlerts.State): MonitorRuntime = copy(
        urgentActive = state.active,
        urgentAcknowledged = state.acknowledged,
        lastUrgentAlertAt = state.lastAlertAt,
        // The counter and the clock belong to one outage, so they die with it —
        // whether it ended by recovery (Idle) or by the user saying they have seen
        // it (acknowledged).
        //
        // Deliberately *not* keyed on `nagging`: being muted, in quiet hours or
        // below the failure threshold also stops the nag, but the outage is still
        // running. Resetting there would restart "down for" at zero every time a
        // mute expired, and re-number the reminders from one.
        urgentPageCount = if (state.ended) 0 else urgentPageCount,
        urgentSinceAt = if (state.ended) 0L else urgentSinceAt,
    )

    /** Records that a page just went out. */
    fun withUrgentPaged(atMs: Long): MonitorRuntime = copy(
        urgentPageCount = urgentPageCount + 1,
        urgentSinceAt = if (urgentSinceAt == 0L) atMs else urgentSinceAt,
    )

    /**
     * Share of every retained check that passed.
     *
     * Deliberately *not* what the UI labels "uptime". The buffer holds
     * [GlobalSettings.historyDepth] checks, so the span it covers is a function
     * of the monitor's interval — the same number means the last fifteen hours at
     * a fifteen-minute cadence and the last twenty-five days at ten-hourly. Use
     * [uptimeWithin] for anything a user reads as an uptime figure.
     */
    val uptimePercent: Float
        get() = if (samples.isEmpty()) 0f else samples.count { it.ok } * 100f / samples.size

    /**
     * Uptime over a real span of wall time.
     *
     * Returns null when no check falls inside the window at all, which is a
     * genuinely different answer from 0% and has to stay distinguishable: a
     * monitor nobody has checked today is not a monitor that was down all day.
     */
    fun uptimeWithin(nowMs: Long, windowMs: Long): UptimeWindow? {
        val inWindow = samples.filter { nowMs - it.at in 0..windowMs }
        if (inWindow.isEmpty()) return null
        val oldest = inWindow.minOf { it.at }
        return UptimeWindow(
            percent = inWindow.count { it.ok } * 100f / inWindow.size,
            checks = inWindow.size,
            spanMs = nowMs - oldest,
            // The window is only covered in full if the history reaches past its
            // far edge, or happens to start exactly on it. An install two hours
            // old cannot report twenty-four-hour uptime, and saying so is the
            // whole point of carrying this flag around.
            complete = samples.any { nowMs - it.at > windowMs } || nowMs - oldest >= windowMs,
        )
    }

    val averageLatencyMs: Long
        get() = samples.filter { it.ok }.map { it.latencyMs }.average().let {
            if (it.isNaN()) 0L else it.toLong()
        }

    val p95LatencyMs: Long
        get() {
            val ok = samples.filter { it.ok }.map { it.latencyMs }.sorted()
            if (ok.isEmpty()) return 0L
            val idx = ((ok.size - 1) * 0.95).toInt()
            return ok[idx]
        }
}

/**
 * An uptime figure that knows what it is a figure *of*.
 *
 * The percentage on its own is not reportable — the same 93% means something
 * different over four hours than over four weeks — so the span and the check
 * count travel with it and the UI is expected to show them.
 */
data class UptimeWindow(
    val percent: Float,
    val checks: Int,
    /** Wall time from the oldest check in the window until now. */
    val spanMs: Long,
    /** The history reaches all the way back across the requested window. */
    val complete: Boolean,
)

/** Windows the UI reports uptime over. */
object UptimeWindows {
    const val DAY_MS = 24L * 60 * 60 * 1000

    /**
     * A span in as few characters as it can honestly be put.
     *
     * Distinct from the UI's `formatSpan`, which is allowed prose ("under a
     * minute") because it lands mid-sentence. This one is read in places with a
     * hard width, so it never returns a phrase.
     */
    fun shortSpan(ms: Long): String = when {
        ms < 60_000 -> "under 1m"
        ms < 3_600_000 -> "${ms / 60_000}m"
        ms < 86_400_000 -> "${ms / 3_600_000}h"
        else -> "${ms / 86_400_000}d"
    }

    /**
     * What the uptime ring calls the figure inside it.
     *
     * Constrained by the arc it sits in, which is about thirteen characters wide,
     * and "past under a minute" is nineteen: it truncated to "PAST UNDER A MINU…"
     * and pushed itself over the percentage above it.
     *
     * Under a minute of history the span is not the useful fact anyway. A 100%
     * that rests on a single check is worth labelling as a single check, which is
     * both shorter and a better warning against reading anything into it.
     */
    fun ringLabel(window: UptimeWindow?): String {
        if (window == null) return "no checks yet"
        if (window.complete) return "24h uptime"
        if (window.spanMs < 60_000) {
            return if (window.checks == 1) "1 check" else "${window.checks} checks"
        }
        return "past ${shortSpan(window.spanMs)}"
    }
}

/** A monitor plus everything we know about how it has been behaving. */
data class MonitorCard(
    val monitor: Monitor,
    val runtime: MonitorRuntime,
    val checking: Boolean = false,
)

/** Why a check failed — drives the copy shown to the user. */
enum class FailureKind {
    NONE,
    DNS,
    CONNECT,
    TIMEOUT,
    TLS,
    STATUS,
    BODY,
    ELEMENT,
    RENDER,
    BAD_CONFIG,
    UNKNOWN,
    ;

    val headline: String
        get() = when (this) {
            NONE -> "Healthy"
            DNS -> "Host not found"
            CONNECT -> "Could not connect"
            TIMEOUT -> "Timed out"
            TLS -> "TLS handshake failed"
            STATUS -> "Unexpected status"
            BODY -> "Response body mismatch"
            ELEMENT -> "Element check failed"
            RENDER -> "Page did not render"
            BAD_CONFIG -> "Monitor misconfigured"
            UNKNOWN -> "Check failed"
        }

    /** Actionable next step, shown under the headline. */
    val hint: String
        get() = when (this) {
            NONE -> ""
            DNS -> "Double-check the hostname, or the device may be offline."
            CONNECT -> "The server refused or dropped the connection."
            TIMEOUT -> "Raise the timeout, or the service is genuinely slow."
            TLS -> "Certificate may be expired, self-signed, or hostname-mismatched."
            STATUS -> "Adjust the expected status if this code is actually fine."
            BODY -> "Compare the assertion with the response preview below."
            ELEMENT -> "The page rendered but the element or its text didn't match."
            RENDER -> "The page failed to load in the embedded browser."
            BAD_CONFIG -> "Fix the monitor's URL or assertion and try again."
            UNKNOWN -> "See the technical detail below."
        }
}

/** Result of a single check run. Never persisted wholesale — see [Sample]. */
data class CheckResult(
    val ok: Boolean,
    val latencyMs: Long,
    val statusCode: Int = 0,
    val failureKind: FailureKind = FailureKind.NONE,
    val message: String = "",
    val detail: String = "",
    val bodyPreview: String = "",
    val elementText: String = "",
    /** One entry per watched element, in [Monitor.targets] order. */
    val elementTexts: List<String> = emptyList(),
    /**
     * `notAfter` of the leaf certificate the handshake presented, or 0 for a
     * plain-HTTP monitor and for any check that never got as far as a handshake.
     *
     * Free with the connection the checker is already making, which is the whole
     * argument for reading it: the expiry that takes a site down at 03:00 was
     * visible in every successful check for the previous ninety days.
     */
    val certExpiresAt: Long = 0L,
    /** Who signed it, for the detail screen. Common name only, not the full DN. */
    val certIssuer: String = "",
    /**
     * SHA-256 of the leaf's public key, in OkHttp's `sha256/…` pin format, or
     * empty when there was no handshake to read one from.
     *
     * The public key rather than the whole certificate, so renewing a certificate
     * with the same key does not read as a different server. That is the ordinary
     * way a self-signed endpoint gets renewed: same key, new dates.
     */
    val certSpki: String = "",
    val at: Long = 0L,
)

/**
 * How much a monitor's TLS certificate has to prove.
 *
 * Nightbell had no answer here at all, which made a whole class of endpoint
 * unmonitorable: a NAS with a self-signed certificate, a homelab box behind a
 * private CA, a Tor hidden service where no CA will ever issue. Issue #6 is one
 * of those, and the only workaround was to stop using HTTPS.
 *
 * Three modes rather than a checkbox, because the interesting answer is the
 * middle one and a boolean cannot express it.
 */
@Serializable
enum class TlsTrust(val label: String, val summary: String) {

    /** A CA the device trusts has to vouch for it. */
    SYSTEM(
        "System CAs",
        "The certificate must be signed by a CA this phone trusts, including any you installed yourself.",
    ),

    /**
     * Trust the key seen on the first successful handshake, and require it after.
     *
     * The honest default for a box you own, and stronger than [SYSTEM] rather than
     * weaker: a CA can be talked into issuing for a name it should not, and no
     * amount of that produces the key this monitor already recorded. What it gives
     * up is the ability to change keys without saying so, which for an endpoint
     * the user administers themselves is a feature.
     *
     * The recorded key lives on [MonitorRuntime], not here. It is something the
     * app observed, not something the user chose, and it must not travel in a
     * backup looking like a decision.
     */
    PINNED(
        "Pinned key",
        "Records the key on the next successful check and requires that exact key afterwards. " +
            "Works with self-signed certificates, and notices if the key ever changes.",
    ),

    /**
     * Check nothing. No chain, no hostname, no pin.
     *
     * Here because sometimes it is genuinely what someone needs, and because
     * without it people reach for plain HTTP instead, which is worse. Off by
     * default, and the detail screen says so on every check rather than letting it
     * be set once and forgotten.
     */
    ANY(
        "Any certificate",
        "No checks at all. Anything on the network path can read and change these requests.",
    ),
}

/**
 * Which colour scheme to paint in.
 *
 * Nightbell shipped dark-only, which was a defensible design decision right up until
 * it became an unstated one — the theme function took a `darkTheme` flag and
 * ignored it. Following the system is the default because a monitoring app is
 * something you open at 3am and also at noon outdoors, and the OS already knows
 * which of those it is.
 */
@Serializable
enum class ThemeChoice {
    @SerialName("system")
    SYSTEM,

    @SerialName("dark")
    DARK,

    @SerialName("light")
    LIGHT,
    ;

    val label: String
        get() = when (this) {
            SYSTEM -> "System"
            DARK -> "Dark"
            LIGHT -> "Light"
        }
}

@Serializable
data class GlobalSettings(
    val masterAlertsEnabled: Boolean = true,
    val defaultAlert: AlertPolicy = AlertPolicy(),
    val backgroundChecksEnabled: Boolean = true,
    val onlyOnUnmeteredNetwork: Boolean = false,
    val defaultIntervalMinutes: Int = 15,
    val defaultTimeoutSeconds: Int = 15,
    val historyDepth: Int = 60,
    val motionIntensity: Float = 1f,
    /**
     * Run a foreground service so checks keep their cadence in Doze.
     * Costs a persistent notification and real battery — see the README.
     */
    val strictForegroundMonitoring: Boolean = false,
    /**
     * Fallback latency budget for monitors that don't set their own.
     * 2500 ms matches the behaviour shipped in 1.0.0; 0 disables DEGRADED.
     */
    val defaultLatencySloMs: Int = 2_500,
    /** Real backdrop blur on API 31+. Falls back to opaque glass when off. */
    val realBlurEnabled: Boolean = true,
    /**
     * Highest `versionCode` whose one-time notification repair has run.
     *
     * 1.1.0 could strand alert notifications that no longer matched any
     * monitor — and urgent ones are `ongoing`, so the user could not clear them
     * by hand. The repair cannot find those by reasoning about state, so on
     * first launch after upgrading it wipes the slate and lets the next check
     * re-post whatever is genuinely current.
     */
    val notificationsRepairedForVersion: Int = 0,
    /**
     * Time a known-good endpoint alongside the checks, and discount whatever the
     * phone's own connection is adding before calling a monitor slow.
     *
     * On by default: without it, bad wifi makes every monitor breach its SLO at
     * once, and every one of those alerts is wrong. See [NetworkBaseline].
     */
    val latencyBaselineEnabled: Boolean = true,
    /**
     * The control endpoint. Wants to be something always up, close to every
     * network, and cheap: a 204 has no body, so the round trip is connect plus
     * TLS plus first byte rather than transfer time.
     *
     * If a network blocks it the probe simply fails, no readings accumulate, and
     * latency is judged raw exactly as it was before this existed.
     *
     * The default is GrapheneOS's connectivity check rather than Google's. See
     * [ConnectivityReference] for the argument and for the other presets.
     */
    val latencyReferenceUrl: String = ConnectivityReference.DEFAULT_URL,
    /**
     * Let the phone's ringer switch decide whether an URGENT page makes noise.
     *
     * The page loops on the **alarm** stream, which is deliberately exempt from
     * the ringer — an alarm you set for 6am is meant to go off whether or not you
     * silenced your ringer at midnight. Correct for alarms, wrong for this: a
     * phone set to vibrate got a full-volume siren.
     *
     * On (the default) the page follows the ringer: sound and haptics on Normal,
     * haptics only on Vibrate and on Silent. Off restores the alarm-stream
     * behaviour, which is louder and answers to nothing.
     *
     * Silent still vibrates on purpose. A page that produces no sound *and* no
     * buzz is indistinguishable from a pager that is simply broken, and the whole
     * feature exists so an outage cannot be missed. See
     * [me.river.nightbell.data.alerts.UrgentAlarm].
     */
    val urgentRespectsRingerMode: Boolean = true,
    /**
     * The user has been through (or dismissed) the pager-setup screen.
     *
     * Gates that screen exactly once. It stays reachable from Settings
     * afterwards — a monitoring app that will not show you your monitors until
     * you have flipped four system toggles is worse than one with a degraded
     * pager. See [me.river.nightbell.domain.PagerReadiness.shouldGate].
     */
    val hasSeenPagerSetup: Boolean = false,

    /**
     * Watch TLS certificate expiry alongside the checks.
     *
     * On by default and cheap: the date comes back with a handshake the checker is
     * already paying for, so the only cost is the notification, and the failure it
     * catches is one nobody wants to meet at three in the morning. See
     * [me.river.nightbell.domain.CertificateWatch].
     */
    /** Dark, light, or whatever the system is doing. */
    val theme: ThemeChoice = ThemeChoice.SYSTEM,

    /**
     * The dashboard's sort order.
     *
     * Persisted, unlike the search text and the state filter, and the asymmetry is
     * deliberate. A filter that survived a restart would hide monitors on launch,
     * which is indistinguishable from having lost them. A sort is an *arrangement* —
     * and once a user has dragged their monitors into an order by hand, forgetting
     * it on the next launch throws away work they did deliberately and leaves no
     * trace that it ever existed.
     */
    val dashboardSort: MonitorQuery.Sort = MonitorQuery.Sort.WORST_FIRST,

    /**
     * Make a SOCKS5 proxy available to the monitors that ask for it.
     *
     * Off by default, and even on it changes nothing by itself: a monitor is
     * routed only when it sets [Monitor.useProxy]. Written for reaching Tor and
     * I2P hidden services from a device that is otherwise on the clear net, where
     * the alternative is putting the whole phone in VPN mode.
     *
     * A master switch, not a default: turning it off leaves a routed monitor with
     * nowhere to go, and such a check fails rather than quietly going out direct.
     */
    val socksProxyEnabled: Boolean = false,
    /** Where the shared proxy is listening. Tor's own default is 127.0.0.1:9050. */
    val socksProxyHost: String = "127.0.0.1",
    val socksProxyPort: Int = 9_050,
    /**
     * Seconds to allow any check that goes through the proxy.
     *
     * 60 rather than the ordinary 15. Building a rendezvous circuit to a v3 onion
     * takes tens of seconds often enough that a 15s budget reports a healthy
     * hidden service as down, and Tor itself waits up to 120s before giving up.
     * Overridable per monitor with [Monitor.proxyTimeoutSeconds].
     */
    val proxiedTimeoutSeconds: Int = 60,

    /**
     * What the dashboard's pause button does when tapped.
     *
     * Defaults to stopping the checks rather than asking, because the case it is
     * for is one where the user is somewhere with no signal and wants one tap,
     * not a questionnaire. [PauseChoice.ASK] is there for anyone who genuinely
     * uses both.
     */
    val pauseChoice: PauseChoice = PauseChoice.STOP_CHECKS,

    val certAlertsEnabled: Boolean = true,
    /** Days before expiry at which the advisory starts. 0 turns the track off. */
    val certWarnDays: Int = 14,
    /** Days before expiry at which it stops being merely advisory. */
    val certCriticalDays: Int = 2,

    // ---- GitHub -------------------------------------------------------------
    /**
     * Optional GitHub personal access token, stored on this device and nowhere
     * else.
     *
     * Raises the REST limit from 60 requests an hour per IP to 5,000, and lets an
     * unchanged check answer 304 without spending any of that budget at all.
     * Entirely optional: a couple of repositories on a fifteen-minute cadence fit
     * inside 60 comfortably.
     *
     * Never logged, never put in a notification, never written into a check's
     * detail line, and left out of an export unless
     * [includeSecretsInExport] says otherwise. See
     * [me.river.nightbell.domain.Secrets].
     */
    val githubToken: String = "",
    /**
     * Write the token into an exported backup.
     *
     * Off, and it has to be: an export is a file the user then moves through a
     * cloud provider or a chat app, and a bearer credential riding along inside it
     * is a leak the user never agreed to. On is a deliberate answer to a warning,
     * for someone moving to a new phone who would rather not re-issue the token.
     */
    val includeSecretsInExport: Boolean = false,

    // ---- Nightbell's own updates --------------------------------------------
    /**
     * Look for a newer Nightbell and say so once.
     *
     * On by default and cheap (one request every six hours), but easy to turn off
     * for anyone whose F-Droid client already handles this. Nothing is downloaded
     * and nothing is installed: the notification opens a page.
     */
    val updateChecksEnabled: Boolean = true,
    val updateSource: UpdateSource = UpdateSource.GITHUB,
)

/**
 * Where the latency probe times its round trip, and why it is not Google's.
 *
 * The probe needs an endpoint that is up, close to every network and cheap to
 * answer. `www.gstatic.com/generate_204` fits, which is why it shipped, and it
 * carries a cost that was never argued for: an app whose pitch is no server, no
 * account and no third party was quietly telling Google's edge where the phone
 * was, once every forty-five seconds, for a measurement the user never asked
 * Google to be part of.
 *
 * GrapheneOS runs the same 204 for the same purpose and answers to nobody with an
 * advertising business, so it is the default now. The field stays free text: a
 * homelab with its own always-up endpoint is a better reference than any of these,
 * and a network that blocks all of them simply produces no readings, which the
 * baseline maths already treats as "judge the latency raw".
 */
object ConnectivityReference {

    const val DEFAULT_URL = "https://connectivitycheck.grapheneos.network/generate_204"

    /** What shipped up to 3.1.1, kept only so the migration can recognise it. */
    const val LEGACY_GOOGLE_URL = "https://www.gstatic.com/generate_204"

    data class Preset(val label: String, val url: String, val blurb: String)

    val presets: List<Preset> = listOf(
        Preset(
            label = "GrapheneOS",
            url = DEFAULT_URL,
            blurb = "The default. Same 204, run by a project with no advertising business.",
        ),
        Preset(
            label = "Ubuntu",
            url = "https://connectivity-check.ubuntu.com/",
            blurb = "Canonical's connectivity check. A second opinion if the first is blocked.",
        ),
    )

    /**
     * Rewrites a stored endpoint that is only there because it used to be the
     * default.
     *
     * A value equal to the old default was never a choice, it was an absence of
     * one, so leaving it in place would mean the fix reached new installs only.
     * Anything else the user has typed is left exactly as typed, including
     * gstatic typed deliberately.
     */
    fun migrate(url: String): String = if (url.trim() == LEGACY_GOOGLE_URL) DEFAULT_URL else url
}

/**
 * One timing of [GlobalSettings.latencyReferenceUrl].
 *
 * Timestamped because readings age out: a phone that moved from office wifi to
 * cellular must not be held to the office's floor.
 */
@Serializable
data class ReferenceSample(
    val at: Long = 0L,
    val rttMs: Long = 0L,
)
