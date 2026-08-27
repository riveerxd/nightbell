package me.river.nightbell.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Where Nightbell looks for a newer Nightbell. */
@Serializable
enum class UpdateSource {
    /** The APKs the maintainer signs and attaches to a tag. */
    @SerialName("github")
    GITHUB,

    /**
     * F-Droid's package index.
     *
     * Behind GitHub by design: F-Droid builds from source and publishes on its own
     * cadence, so its newest version is the newest one an F-Droid client can
     * actually install. Anyone who installed from there wants to be told about
     * that one rather than about a tag their updater cannot see yet.
     */
    @SerialName("fdroid")
    FDROID,
    ;

    val label: String
        get() = when (this) {
            GITHUB -> "GitHub releases"
            FDROID -> "F-Droid"
        }

    val blurb: String
        get() = when (this) {
            GITHUB -> "The APK the maintainer signs, available the moment a release goes out."
            FDROID -> "Matches what your F-Droid client can install, which trails GitHub a little."
        }
}

/** What Nightbell knows about newer versions of itself. */
@Serializable
data class UpdateState(
    val lastCheckedAt: Long = 0L,
    val latestVersion: String = "",
    val latestUrl: String = "",
    val latestSource: UpdateSource = UpdateSource.GITHUB,
    val latestNotes: String = "",
    /** The version the user has already been told about, so it is said once. */
    val notifiedVersion: String = "",
    /** "Not this one." Suppresses exactly this version and nothing later. */
    val ignoredVersion: String = "",
    /** "Not now." Suppresses every version until this moment passes. */
    val remindAfter: Long = 0L,
    val etag: String = "",
)

/**
 * Whether a newer Nightbell exists, and whether saying so would be welcome.
 *
 * Kept pure and away from the network for the usual reason: the interesting part
 * is not fetching a version string, it is the four ways a user can have already
 * answered this question (installed it, ignored it, deferred it, been told once
 * already), and each of those is a test rather than a release to sit through.
 *
 * Nothing here downloads or installs anything. Android package installation is a
 * user action behind a system prompt, and an uptime monitor quietly replacing its
 * own APK would be indistinguishable from the thing every user is told to be
 * afraid of.
 */
object AppUpdate {

    const val REPO_OWNER = "riveerxd"
    const val REPO_NAME = "nightbell"
    const val FDROID_PACKAGE = "me.river.nightbell"

    /** Where "Open download" goes when a release carries no page of its own. */
    const val DOWNLOAD_URL = "https://nightbell.app/download"
    const val FDROID_URL = "https://f-droid.org/en/packages/me.river.nightbell/"

    /** Shortest gap between two version checks. One a day is generous already. */
    const val CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000

    /** How long "Remind later" holds its tongue. */
    const val REMIND_LATER_MS = 24L * 60 * 60 * 1000

    /** One release as either source describes it. */
    data class Release(
        val version: String,
        val url: String,
        val source: UpdateSource,
        val notes: String = "",
    )

    enum class Action { NONE, NOTIFY }

    data class Decision(
        val action: Action,
        val state: UpdateState,
        val release: Release? = null,
    )

    /**
     * Compares two version names the way a human reads them.
     *
     * Numeric segment by numeric segment, so 3.10.0 is newer than 3.9.0 (a string
     * comparison gets that backwards). A leading `v` and any suffix after the
     * numbers are ignored: the tag is `v3.1.1` and the installed name on a debug
     * build is `3.1.1-debug`, and those are the same release.
     */
    fun compare(left: String, right: String): Int {
        val a = segments(left)
        val b = segments(right)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    fun isNewer(candidate: String, installed: String): Boolean {
        if (candidate.isBlank()) return false
        // An unparseable candidate is not evidence of anything. Silence beats a
        // notification about a version that may not exist.
        if (segments(candidate).isEmpty()) return false
        return compare(candidate, installed) > 0
    }

    private fun segments(raw: String): List<Int> {
        val trimmed = raw.trim().removePrefix("v").removePrefix("V")
        val core = trimmed.takeWhile { it.isDigit() || it == '.' }
        return core.split('.')
            .mapNotNull { it.toIntOrNull() }
    }

    /**
     * Folds one fetched release into the persisted state and says whether to speak.
     *
     * @param release what the source reported, or null when the check could not
     *   complete. A failed check records only that the attempt happened: an
     *   update notice is not urgent enough to justify guessing.
     */
    fun decide(
        release: Release?,
        installedVersion: String,
        previous: UpdateState,
        nowMs: Long,
    ): Decision {
        if (release == null) {
            return Decision(Action.NONE, previous.copy(lastCheckedAt = nowMs))
        }
        var state = previous.copy(
            lastCheckedAt = nowMs,
            latestVersion = release.version,
            latestUrl = release.url,
            latestSource = release.source,
            latestNotes = release.notes,
        )
        if (!isNewer(release.version, installedVersion)) {
            // Caught up. Anything the user deferred or refused was about a version
            // they are now running, so the answers go with it.
            return Decision(
                Action.NONE,
                state.copy(notifiedVersion = "", ignoredVersion = "", remindAfter = 0L),
            )
        }
        if (release.version == previous.ignoredVersion) return Decision(Action.NONE, state)
        if (nowMs < previous.remindAfter) return Decision(Action.NONE, state)
        if (release.version == previous.notifiedVersion) return Decision(Action.NONE, state)

        state = state.copy(notifiedVersion = release.version)
        return Decision(Action.NOTIFY, state, release)
    }

    /** "Remind later": quiet for a day, then this same version may speak again. */
    fun remindLater(state: UpdateState, nowMs: Long): UpdateState = state.copy(
        remindAfter = nowMs + REMIND_LATER_MS,
        notifiedVersion = "",
    )

    /** "Ignore this version": quiet about this one forever, loud about the next. */
    fun ignore(state: UpdateState, version: String): UpdateState = state.copy(
        ignoredVersion = version.ifBlank { state.latestVersion },
        notifiedVersion = "",
        remindAfter = 0L,
    )

    /** Whether enough time has passed to ask the network again. */
    fun isDue(state: UpdateState, nowMs: Long): Boolean =
        state.lastCheckedAt <= 0L || nowMs - state.lastCheckedAt >= CHECK_INTERVAL_MS
}
