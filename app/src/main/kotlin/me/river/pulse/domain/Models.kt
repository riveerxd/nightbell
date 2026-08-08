package me.river.pulse.domain

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
    ;

    val label: String
        get() = when (this) {
            HTTP_STATUS -> "Status check"
            ADVANCED_REQUEST -> "Request & response"
            WEBSITE_ELEMENT -> "Page element"
        }

    val blurb: String
        get() = when (this) {
            HTTP_STATUS -> "Ping a URL and expect a status code."
            ADVANCED_REQUEST -> "Send a crafted request, assert on what comes back."
            WEBSITE_ELEMENT -> "Watch one element on a real rendered page."
        }
}

@Serializable
enum class HttpMethod { GET, POST, PUT, PATCH, DELETE, HEAD;

    /** HEAD/GET cannot carry a request body. */
    val allowsBody: Boolean get() = this != GET && this != HEAD
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
    val timeoutSeconds: Int = 15,
    val intervalMinutes: Int = 15,
    val followRedirects: Boolean = true,
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
    val displayName: String get() = name.ifBlank { prettyHost }

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
    val at: Long = 0L,
)

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
     */
    val latencyReferenceUrl: String = "https://www.gstatic.com/generate_204",
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
     * [me.river.pulse.data.alerts.UrgentAlarm].
     */
    val urgentRespectsRingerMode: Boolean = true,
    /**
     * The user has been through (or dismissed) the pager-setup screen.
     *
     * Gates that screen exactly once. It stays reachable from Settings
     * afterwards — a monitoring app that will not show you your monitors until
     * you have flipped four system toggles is worse than one with a degraded
     * pager. See [me.river.pulse.domain.PagerReadiness.shouldGate].
     */
    val hasSeenPagerSetup: Boolean = false,

    /**
     * Watch TLS certificate expiry alongside the checks.
     *
     * On by default and cheap: the date comes back with a handshake the checker is
     * already paying for, so the only cost is the notification, and the failure it
     * catches is one nobody wants to meet at three in the morning. See
     * [me.river.pulse.domain.CertificateWatch].
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

    val certAlertsEnabled: Boolean = true,
    /** Days before expiry at which the advisory starts. 0 turns the track off. */
    val certWarnDays: Int = 14,
    /** Days before expiry at which it stops being merely advisory. */
    val certCriticalDays: Int = 2,
)

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
