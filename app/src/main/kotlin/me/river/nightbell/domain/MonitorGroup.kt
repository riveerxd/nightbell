package me.river.nightbell.domain

import kotlinx.serialization.Serializable

/**
 * Several monitors that answer one question.
 *
 * "Is Nightbell up" is not a question about a website or about a repository. It
 * is a question about both, and before this the dashboard could only answer it
 * one row at a time, two cards, two verdicts, and the reader doing the
 * and-ing. A group is a place to put that and-ing so the list can state the
 * answer directly.
 *
 * A monitor belongs to at most one group. Tags would allow more and buy nothing
 * here: the dashboard has to decide where a card is *drawn*, and a card in two
 * groups has no answer to that. [GroupRollup.of] enforces it on read as well,
 * so a store hand-edited into an overlap still renders sanely.
 *
 * Nothing about a group changes what gets checked or what alerts. Members keep
 * their own intervals, their own policies and their own urgent flags, grouping
 * is presentation, and treating it as anything more would mean two places that
 * decide whether you get woken up.
 */
@Serializable
data class MonitorGroup(
    val id: String,
    val title: String = "",
    /**
     * The page whose favicon represents the group.
     *
     * Offered first because the mark people want is almost always a site's own
     * and Nightbell can already fetch those, see
     * [me.river.nightbell.data.icons.FaviconStore]. Blank falls back to the
     * first member that has an icon, then to a glyph.
     */
    val iconUrl: String = "",
    /**
     * A picture the user picked, as base64 PNG.
     *
     * Kept even while [iconChoice] points at a site, so switching back to it is
     * free and switching away is not destructive. Carried in the group rather
     * than as a file path so it survives a backup, see
     * [me.river.nightbell.data.icons.GroupIcon] for why, and for the size it is
     * held down to.
     */
    val iconImage: String = "",
    /**
     * Which of the two the user actually chose.
     *
     * Needed the moment both can be set at once. "Picture wins if there is one"
     * was fine while the only way back was deleting it; with a picker offering
     * both, tapping a site while a picture existed would have done nothing
     * visible. [GroupIconChoice.AUTO] is the old rule exactly, so a group written
     * before this decodes to the behaviour it had.
     */
    val iconChoice: GroupIconChoice = GroupIconChoice.AUTO,
    /** Member monitor ids, in the order they should be drawn. */
    val memberIds: List<String> = emptyList(),
    /** Index into the accent palette, matching [Monitor.accent]. */
    val accent: Int = 0,
    /**
     * Whether the group is drawn shut on the dashboard.
     *
     * Persisted, not screen state: a group exists to collapse four rows into
     * one, and a collapse that forgets itself on the next launch does not.
     */
    val collapsed: Boolean = true,
) {
    val displayTitle: String get() = title.trim().ifBlank { "Untitled group" }

    val size: Int get() = memberIds.size
}

/**
 * Which source a group's mark comes from.
 *
 * [AUTO] is not a third option in the picker, it is the state of a group nobody
 * has decided about yet, and it resolves to whatever is available. The picker
 * only ever writes [SITE] or [PICTURE].
 */
@Serializable
enum class GroupIconChoice {
    /** Picture if there is one, then the site, then a glyph. The original rule. */
    AUTO,
    SITE,
    PICTURE,
}

/** Where a group's mark is drawn from, once the choice has been resolved. */
sealed interface GroupMark {
    /** Fetch the favicon for this page. */
    data class Site(val url: String) : GroupMark

    /** Draw [MonitorGroup.iconImage]. */
    data object Picture : GroupMark

    /** Nothing to draw: fall back to the layers glyph. */
    data object Glyph : GroupMark
}

/**
 * Resolves [MonitorGroup.iconChoice] against what the group actually has.
 *
 * Total by construction: every combination of choice, picture and member list has
 * an answer, and the answer is never "blank". [memberUrls] is in draw order, so
 * the fallback is the first member, the one at the top of the group.
 *
 * Pure, and it is what both the card and the editor's preview read, so the tile
 * shown as selected cannot disagree with the badge on the dashboard.
 */
fun MonitorGroup.mark(memberUrls: List<String>): GroupMark {
    val picture = iconImage.isNotBlank()
    val site = iconUrl.ifBlank { memberUrls.firstOrNull { it.isNotBlank() }.orEmpty() }
    return when (iconChoice) {
        GroupIconChoice.PICTURE ->
            // Falls through rather than drawing nothing: a group whose picture was
            // removed by an import that dropped it still has a site to show.
            if (picture) GroupMark.Picture else siteOrGlyph(site)
        GroupIconChoice.SITE -> siteOrGlyph(site)
        GroupIconChoice.AUTO -> if (picture) GroupMark.Picture else siteOrGlyph(site)
    }
}

private fun siteOrGlyph(url: String): GroupMark =
    if (url.isBlank()) GroupMark.Glyph else GroupMark.Site(url)

/**
 * The part of [url] a favicon belongs to, host and port, lowercased.
 *
 * Favicons are fetched per origin, so two monitors on different pages of one site
 * share a mark and must offer the user one choice rather than two identical ones.
 * Deliberately does *not* strip `www.`: that is a different origin, and if
 * somebody is watching both then showing both is the honest answer.
 *
 * Blank for anything unusable, which callers treat as "no site here".
 */
fun iconOriginOf(url: String): String {
    val withoutScheme = url.trim()
        .removePrefix("https://")
        .removePrefix("http://")
    if (withoutScheme.isBlank()) return ""
    val authority = withoutScheme.substringBefore('/')
        // A URL is allowed credentials before the host, and they are not part of
        // the origin. Rare, but a monitor on an internal box may well carry them.
        .substringAfterLast('@')
    return authority.lowercase()
}

/** Whether two URLs would resolve to the same favicon. */
fun sameIconSite(a: String, b: String): Boolean {
    val left = iconOriginOf(a)
    return left.isNotBlank() && left == iconOriginOf(b)
}

/**
 * The groups holding any of [monitorIds], in stored order.
 *
 * What the selection bar needs to know before it can offer the right verb: a
 * monitor already in a group wants pulling *out* far more often than it wants
 * adding to the group it is already in.
 */
fun groupsHolding(groups: List<MonitorGroup>, monitorIds: Set<String>): List<MonitorGroup> =
    groups.filter { group -> group.memberIds.any { it in monitorIds } }

/** How many of [monitorIds] belong to some group. */
fun groupedCount(groups: List<MonitorGroup>, monitorIds: Set<String>): Int {
    val held = groups.flatMap { it.memberIds }.toSet()
    return monitorIds.count { it in held }
}

/**
 * What a group is currently reporting.
 *
 * Pure, and in `domain` for the same reason [Summary] is: the dashboard is the
 * first reader but the widget is the obvious second one, and a group that reads
 * "operational" in the app and "down" on the home screen would be worse than no
 * grouping at all.
 */
object GroupRollup {

    /** A group, its members, and the single verdict they add up to. */
    data class Rolled(
        val group: MonitorGroup,
        val members: List<MonitorCard>,
        val health: Health,
        val down: Int,
        val degraded: Int,
        val unknown: Int,
        val paused: Int,
        val urgentPending: Int,
        /** Slowest member reading, which is the honest one for a group. */
        val slowestLatencyMs: Long,
        /** Oldest member check, a group is only as fresh as its stalest member. */
        val lastCheckedAt: Long,
        val checking: Boolean,
    ) {
        val total: Int get() = members.size

        val operational: Boolean get() = health == Health.UP

        /**
         * One line, worded like [Summary.Fleet.headline] on purpose.
         *
         * The fleet banner and a group card are the same claim at two scales, and
         * two phrasings for it would read as two different facts.
         */
        val headline: String
            get() = when {
                total == 0 -> "Nothing in this group"
                down == 1 -> "1 of $total is down"
                down > 1 -> "$down of $total are down"
                degraded == 1 -> "1 of $total is slow"
                degraded > 1 -> "$degraded of $total are slow"
                paused == total -> if (total == 1) "Paused" else "All $total paused"
                unknown == total -> if (total == 1) "Not checked yet" else "None checked yet"
                unknown > 0 -> "$unknown of $total not checked yet"
                total == 1 -> "Operational"
                else -> "All $total operational"
            }
    }

    /**
     * Rolls every group up against [cards].
     *
     * Members that no longer exist are dropped rather than counted as unknown: a
     * deleted monitor is not a monitor in an unknown state, and counting it would
     * park a group permanently at "1 of 3 not checked yet". Groups keep their
     * stored order; members are ordered by [MonitorGroup.memberIds] so a drag
     * inside a group means something.
     *
     * A monitor listed by two groups lands in the first one only, so the sum of
     * the groups plus [ungrouped] is always exactly the fleet.
     */
    fun of(groups: List<MonitorGroup>, cards: List<MonitorCard>): List<Rolled> {
        val byId = cards.associateBy { it.monitor.id }
        val claimed = mutableSetOf<String>()
        return groups.map { group ->
            val members = group.memberIds
                .filter { claimed.add(it) }
                .mapNotNull(byId::get)
            rolled(group, members)
        }
    }

    /** The cards no group has claimed, in their stored order. */
    fun ungrouped(groups: List<MonitorGroup>, cards: List<MonitorCard>): List<MonitorCard> {
        val grouped = groups.flatMap { it.memberIds }.toSet()
        return cards.filterNot { it.monitor.id in grouped }
    }

    /**
     * The verdict for one group.
     *
     * Paused members are excluded from the worst-of unless *every* member is
     * paused. Pausing one monitor in a group of four is a decision about that
     * monitor; letting it decide the group's colour would have a group report
     * "paused" while three live members were being checked, and could hide an
     * outage behind a pause somebody set last week.
     */
    fun rolled(group: MonitorGroup, members: List<MonitorCard>): Rolled {
        val healths = members.map { it.effectiveHealth }
        val active = healths.filterNot { it == Health.PAUSED }
        val health = when {
            healths.isEmpty() -> Health.UNKNOWN
            active.isEmpty() -> Health.PAUSED
            else -> active.minByOrNull(Summary::severity) ?: Health.UNKNOWN
        }
        return Rolled(
            group = group,
            members = members,
            health = health,
            down = healths.count { it == Health.DOWN },
            degraded = healths.count { it == Health.DEGRADED },
            unknown = healths.count { it == Health.UNKNOWN },
            paused = healths.count { it == Health.PAUSED },
            urgentPending = members.count { it.monitor.urgent && it.runtime.urgentState.nagging },
            slowestLatencyMs = members.maxOfOrNull { it.runtime.lastLatencyMs } ?: 0L,
            // Zero means "never", which is older than any timestamp, so it has to
            // win the min rather than be skipped by it.
            lastCheckedAt = members.minOfOrNull { it.runtime.lastCheckedAt } ?: 0L,
            checking = members.any { it.checking },
        )
    }

    /**
     * Applies [memberIds] to [groupId] and strips those monitors from every other
     * group, keeping the one-group-per-monitor rule true after any edit.
     *
     * Also drops ids that are not in [knownIds], so a group cannot keep a hold on
     * a monitor that has been deleted.
     */
    fun assign(
        groups: List<MonitorGroup>,
        groupId: String,
        memberIds: List<String>,
        knownIds: Set<String>,
    ): List<MonitorGroup> {
        val members = memberIds.filter { it in knownIds }.distinct()
        val taken = members.toSet()
        return groups.map { group ->
            when {
                group.id == groupId -> group.copy(memberIds = members)
                group.memberIds.none { it in taken } -> group
                else -> group.copy(memberIds = group.memberIds.filterNot { it in taken })
            }
        }
    }

    /** Every group with [monitorId] removed. Used when a monitor is deleted. */
    fun withoutMonitor(groups: List<MonitorGroup>, monitorId: String): List<MonitorGroup> =
        groups.map { group ->
            if (monitorId in group.memberIds) {
                group.copy(memberIds = group.memberIds - monitorId)
            } else {
                group
            }
        }

    /**
     * A title for a group about to be made out of [members].
     *
     * Takes the longest run of leading words the names share, so a Nightbell
     * website monitor and a Nightbell repository monitor suggest "Nightbell"
     * rather than the first one's whole name. Falls back to the count, never to
     * an empty field the user has to notice is empty.
     */
    fun suggestedTitle(members: List<Monitor>): String {
        if (members.isEmpty()) return ""
        val words = members.map { monitor ->
            monitor.displayName.trim().split(Regex("[\\s\\-–—:/]+")).filter { it.isNotBlank() }
        }
        val shared = mutableListOf<String>()
        val shortest = words.minOf { it.size }
        for (i in 0 until shortest) {
            val word = words.first()[i]
            if (words.all { it[i].equals(word, ignoreCase = true) }) shared += word else break
        }
        if (shared.isNotEmpty()) return shared.joinToString(" ")
        return "${members.size} monitors"
    }
}

/**
 * The health a card should be *read* as, paused monitors included.
 *
 * `runtime.health` is what the last check saw; a monitor switched off after that
 * check still holds it. Every roll-up needs the same answer to "so what is this
 * one right now", and the dashboard was already computing it inline.
 */
val MonitorCard.effectiveHealth: Health
    get() = if (!monitor.enabled) Health.PAUSED else runtime.health
