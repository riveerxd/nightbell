package me.river.pulse.domain

/**
 * Everything that has to be true before an URGENT page can actually reach
 * someone, and what is missing right now.
 *
 * Kept free of Android types so the ordering and the verdicts are unit-testable;
 * the platform lookups live in
 * [me.river.pulse.data.alerts.AlertCenter] and
 * [me.river.pulse.data.SystemLimits].
 *
 * ### Why this needs a screen of its own
 * Only one of these is a runtime permission with a dialog. The other two grants
 * are "special app access" toggles, and Android has no API that asks for them —
 * an app may only *open the exact settings page* and wait. There is no
 * grant-everything call, and any UI promising one is lying. What is achievable is
 * removing the hunting: deep-link straight to each toggle, in order, and notice
 * when it flips.
 */
object PagerReadiness {

    /**
     * Ordered by cost to the user, cheapest first: the two that show a system
     * dialog in place come before the two that navigate away to Settings, so the
     * walkthrough gets the easy grants done before it starts bouncing the user
     * between apps.
     */
    enum class Requirement {
        /** Runtime permission. A dialog, in place. Without it nothing is posted. */
        NOTIFICATIONS,

        /**
         * Exemption from Doze. A system dialog, in place. Without it Android may
         * refuse the foreground service that owns the page and its repeat loop.
         */
        BATTERY_EXEMPTION,

        /**
         * Special access. Settings only. Without it a page arriving on a locked
         * phone cannot wake the screen — it is just another row on the lockscreen.
         */
        FULL_SCREEN,

        /**
         * Special access. Settings only. Without it Do Not Disturb — bedtime mode
         * included — mutes the page completely, which is the exact situation it
         * exists for.
         */
        DND_BYPASS,
        ;

        /** Whether granting this navigates away rather than showing a dialog. */
        val leavesTheApp: Boolean
            get() = this == FULL_SCREEN || this == DND_BYPASS

        /**
         * Whether a page is useless without it. Only [NOTIFICATIONS] is fatal:
         * nothing at all is posted. The rest each degrade the page in a specific,
         * survivable way, so the walkthrough must never hold the app hostage over
         * them.
         */
        val essential: Boolean get() = this == NOTIFICATIONS
    }

    /** One live reading of the platform. */
    data class State(
        val notifications: Boolean,
        val batteryExempt: Boolean,
        val fullScreen: Boolean,
        val dndBypass: Boolean,
        /**
         * Whether the stream the page will actually use has any volume. Not a
         * permission and not blockable — reported so a muted phone is a visible
         * fact rather than a silent failure.
         */
        val audible: Boolean = true,
    ) {
        fun granted(requirement: Requirement): Boolean = when (requirement) {
            Requirement.NOTIFICATIONS -> notifications
            Requirement.BATTERY_EXEMPTION -> batteryExempt
            Requirement.FULL_SCREEN -> fullScreen
            Requirement.DND_BYPASS -> dndBypass
        }

        val missing: List<Requirement>
            get() = Requirement.entries.filterNot { granted(it) }

        /** The next thing the walkthrough should ask for, or null when done. */
        val next: Requirement? get() = missing.firstOrNull()

        val allGranted: Boolean get() = missing.isEmpty()

        /** True once the page can be delivered at all, however degraded. */
        val canPageAtAll: Boolean get() = notifications

        /**
         * How many of the four are satisfied — for the progress line, and so
         * "3 of 4" is a fact rather than a vibe.
         */
        val grantedCount: Int get() = Requirement.entries.count { granted(it) }

        val total: Int get() = Requirement.entries.size
    }

    /**
     * Whether the setup screen should stand in front of the dashboard.
     *
     * Shown while anything is missing and the user has not dismissed it. Once
     * dismissed it never gates again — it stays reachable from Settings — because
     * a monitoring app that will not let you see your monitors is worse than one
     * with a degraded pager.
     */
    fun shouldGate(state: State, dismissed: Boolean): Boolean = !dismissed && !state.allGranted
}
