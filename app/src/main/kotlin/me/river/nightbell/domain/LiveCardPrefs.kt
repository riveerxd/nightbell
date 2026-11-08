package me.river.nightbell.domain

import kotlinx.serialization.Serializable

/** What the live card's line is about. */
@Serializable
enum class LiveFollow {
    EVERYTHING,
    GROUP,
    MONITOR,
    ;

    val label: String
        get() = when (this) {
            EVERYTHING -> "Everything"
            GROUP -> "One group"
            MONITOR -> "One monitor"
        }
}

/**
 * How far back the line reaches.
 *
 * [AUTO] is what the card did before there was a choice: as far back as the
 * stored checks go, floored at ten minutes and capped at a day. It stays the
 * default so nobody's card changes under them on an update.
 */
@Serializable
enum class LiveWindow(val ms: Long) {
    AUTO(0L),
    HOUR(60L * 60 * 1000),
    SIX_HOURS(6L * 60 * 60 * 1000),
    DAY(24L * 60 * 60 * 1000),
    ;

    val label: String
        get() = when (this) {
            AUTO -> "Auto"
            HOUR -> "1 h"
            SIX_HOURS -> "6 h"
            DAY -> "24 h"
        }
}

/** The figure drawn at the right-hand end of the line. */
@Serializable
enum class LiveLabel {
    NEXT_CHECK,
    UPTIME,
    LATENCY,
    ;

    val label: String
        get() = when (this) {
            NEXT_CHECK -> "Next check"
            UPTIME -> "Uptime"
            LATENCY -> "Latency"
        }
}

/**
 * The live card's three choices, kept together in settings.
 *
 * [targetId] is a group id when [follows] is [LiveFollow.GROUP] and a monitor id
 * when it is [LiveFollow.MONITOR]. Kept when switching back to everything, so
 * flipping between the modes does not make someone pick their monitor again.
 */
@Serializable
data class LiveCardPrefs(
    val follows: LiveFollow = LiveFollow.EVERYTHING,
    val targetId: String = "",
    val window: LiveWindow = LiveWindow.AUTO,
    val label: LiveLabel = LiveLabel.NEXT_CHECK,
)

/**
 * Which monitors the live card is about, worked out from [LiveCardPrefs].
 *
 * A group or monitor that has since been deleted, or a group with nobody left in
 * it, falls back to everything rather than to an empty card. An empty card on a
 * permanent notification would claim nothing is wrong, which is the one claim a
 * monitoring app must not make by accident. [fellBack] lets Settings say it
 * happened.
 */
object LiveScope {

    data class Scope(
        val monitors: List<Monitor>,
        /** The group or monitor name, or null when the card is about everything. */
        val name: String?,
        val follows: LiveFollow,
        val fellBack: Boolean,
    )

    fun resolve(prefs: LiveCardPrefs, monitors: List<Monitor>, groups: List<MonitorGroup>): Scope {
        val everything = Scope(monitors, name = null, follows = LiveFollow.EVERYTHING, fellBack = false)
        return when (prefs.follows) {
            LiveFollow.EVERYTHING -> everything
            LiveFollow.GROUP -> {
                val group = groups.firstOrNull { it.id == prefs.targetId }
                val byId = monitors.associateBy { it.id }
                val members = group?.memberIds?.mapNotNull(byId::get).orEmpty()
                if (group == null || members.isEmpty()) {
                    everything.copy(fellBack = true)
                } else {
                    Scope(members, group.displayTitle, LiveFollow.GROUP, fellBack = false)
                }
            }
            LiveFollow.MONITOR -> {
                val monitor = monitors.firstOrNull { it.id == prefs.targetId }
                if (monitor == null) {
                    everything.copy(fellBack = true)
                } else {
                    Scope(listOf(monitor), monitor.displayName, LiveFollow.MONITOR, fellBack = false)
                }
            }
        }
    }
}
