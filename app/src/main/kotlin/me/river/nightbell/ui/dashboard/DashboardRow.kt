package me.river.nightbell.ui.dashboard

import me.river.nightbell.domain.GroupRollup
import me.river.nightbell.domain.MonitorCard
import me.river.nightbell.domain.MonitorGroup

/**
 * One entry in the dashboard list.
 *
 * The list used to be `List<MonitorCard>` and the grid iterated it directly.
 * Groups make it two kinds of thing, and this says so in the type rather than in
 * a nullable field the renderer has to interpret.
 *
 * Members of an expanded group are *not* nested inside [Group]'s items, the
 * screen emits them as their own grid items straight after the group card. That
 * keeps a member a real `MonitorRowCard` with its own long-press, its own
 * re-check and its own drag handle, and keeps the grid virtualising a flat list
 * instead of measuring one enormous item.
 */
sealed interface DashboardRow {

    /** A monitor that belongs to no group. */
    data class Single(val card: MonitorCard) : DashboardRow

    /** A group and its rolled-up verdict. */
    data class Group(val rolled: GroupRollup.Rolled) : DashboardRow {
        val group: MonitorGroup get() = rolled.group
    }
}

/**
 * A group being created or edited.
 *
 * [creating] is what tells the editor whether to say "Create" or "Save", and
 * whether cancelling should leave anything behind, it also decides whether the
 * selection that started it gets cleared on save.
 */
data class GroupDraft(
    val group: MonitorGroup,
    val creating: Boolean,
)

/**
 * A selection on its way into a group, waiting for the user to say which one.
 *
 * [leavingGroups] is the titles of groups that will lose a member to this, so the
 * sheet can warn about the one consequence nobody expects: a monitor belongs to
 * at most one group, so adding it to another moves it out of the one it is in.
 */
data class GroupTarget(
    val monitorIds: List<String>,
    val leavingGroups: List<String>,
)
