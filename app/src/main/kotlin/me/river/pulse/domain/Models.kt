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
    val degradedAlerting: Boolean = false,
    val lastDegradedAlertAt: Long = 0L,

    // ---- urgent track -------------------------------------------------------
    /** An urgent outage is in progress and has *not* been acknowledged. */
    val urgentActive: Boolean = false,
    /** The user acknowledged the current outage; stays true until recovery. */
    val urgentAcknowledged: Boolean = false,
    val lastUrgentAlertAt: Long = 0L,

    val samples: List<Sample> = emptyList(),
) {
    /** Pure-domain view of the urgent state machine's inputs. */
    val urgentState: UrgentAlerts.State
        get() = UrgentAlerts.State(urgentActive, urgentAcknowledged, lastUrgentAlertAt)

    fun withUrgentState(state: UrgentAlerts.State): MonitorRuntime = copy(
        urgentActive = state.active,
        urgentAcknowledged = state.acknowledged,
        lastUrgentAlertAt = state.lastAlertAt,
    )

    val uptimePercent: Float
        get() = if (samples.isEmpty()) 0f else samples.count { it.ok } * 100f / samples.size

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
    val at: Long = 0L,
)

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
    val hasSeenOnboarding: Boolean = false,
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
)
