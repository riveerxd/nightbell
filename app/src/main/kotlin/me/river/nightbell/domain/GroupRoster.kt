package me.river.nightbell.domain

import java.util.Locale

/**
 * What a group card says about its members, as words.
 *
 * The card used to say whether a group was all right and never which member was
 * not: "1 of 3 is down" and a red rim, and the name only after opening it. A
 * group exists to be read shut, so a shut one now names its problems and folds
 * everything that is fine into one line.
 *
 * Pure so the wording can be pinned down on the JVM, and kept next to
 * [GroupRollup] because the two have to agree about what counts as a problem.
 */
object GroupRoster {

    /**
     * Problem rows a shut group shows before folding the rest into a count.
     *
     * Four is what fits under a group header on a phone without the card turning
     * into the open group anyway. A fleet with more than four members down at
     * once is having an outage the banner already shouts about.
     */
    const val PEEK_LIMIT = 4

    /** A shut group's summary: the members worth naming and a line for the rest. */
    data class Peek(
        val problems: List<MonitorCard>,
        /** The rest of the members, as one line. Empty when there is no rest. */
        val rest: String,
        /** The health the rest line's dot is drawn in. */
        val restHealth: Health,
    )

    /**
     * Null when nothing in the group is down or slow, because then the header's
     * own "All 3 operational" is the whole story and a second line saying it again
     * is the noise this replaced.
     *
     * Down before slow, and otherwise in the members' own order, so a row does
     * not jump about between checks.
     */
    fun peek(members: List<MonitorCard>): Peek? {
        val problems = members
            .withIndex()
            .filter { (_, card) -> card.isProblem }
            .sortedWith(compareBy({ if (it.value.effectiveHealth == Health.DOWN) 0 else 1 }, { it.index }))
            .map { it.value }
        if (problems.isEmpty()) return null

        val shown = problems.take(PEEK_LIMIT)
        val hidden = problems.size - shown.size
        val others = members.filterNot { it.isProblem }
        val up = others.count { it.effectiveHealth == Health.UP }
        val paused = others.count { it.effectiveHealth == Health.PAUSED }
        val unknown = others.count { it.effectiveHealth == Health.UNKNOWN }

        val rest = buildList {
            if (hidden > 0) add("$hidden more down or slow")
            if (up > 0) add("$up up")
            if (paused > 0) add("$paused paused")
            if (unknown > 0) add("$unknown not checked yet")
        }.joinToString(", ")

        return Peek(
            problems = shown,
            rest = rest,
            restHealth = when {
                hidden > 0 -> Health.DOWN
                up > 0 -> Health.UP
                unknown > 0 -> Health.UNKNOWN
                else -> Health.PAUSED
            },
        )
    }

    /**
     * One member's state in a few words: "Down 12m", "Slow, 3.1 s", "180 ms".
     *
     * Down carries how long, read off the stored checks the same way the detail
     * screen's outage list reads it, because that is the number someone glancing
     * at a group wants. Up carries the latency, the one figure that moves while a
     * monitor is fine.
     */
    fun detail(card: MonitorCard, nowMs: Long): String {
        val runtime = card.runtime
        val base = when (card.effectiveHealth) {
            Health.PAUSED -> "Paused"
            Health.UNKNOWN -> "Not checked yet"
            Health.DOWN -> {
                val since = Outages.of(runtime.samples).outages.firstOrNull { it.ongoing }?.startAt ?: 0L
                if (since in 1 until nowMs) "Down ${Outages.span(nowMs - since)}" else "Down"
            }
            Health.DEGRADED -> latency(card)?.let { "Slow, $it" } ?: "Slow"
            Health.UP -> latency(card) ?: "Up"
        }
        val muted = runtime.mutedUntil > nowMs && card.isProblem
        return if (muted) "$base, muted" else base
    }

    /** True for a member the shut card names: down or slow, and not paused. */
    val MonitorCard.isProblem: Boolean
        get() = effectiveHealth == Health.DOWN || effectiveHealth == Health.DEGRADED

    /**
     * A ping's own precision when there is one, since a LAN reply is 0.3 ms and
     * the millisecond field rounds it to nothing.
     */
    private fun latency(card: MonitorCard): String? {
        val ping = card.runtime.samples.lastOrNull()?.ping?.takeIf { it.answered }
        if (ping != null) return PingReading.formatUs(ping.avgUs)
        val ms = card.runtime.lastLatencyMs
        return when {
            ms <= 0 -> null
            ms < 1_000 -> "$ms ms"
            else -> String.format(Locale.ROOT, "%.1f s", ms / 1000.0)
        }
    }
}
