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
    /** The APK itself, when the source publishes one Nightbell can fetch. */
    val latestApkUrl: String = "",
    /** Bytes, as the source reported them. 0 when it did not say. */
    val latestApkSize: Long = 0L,
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
 * Nothing here downloads or installs anything either; that is
 * `data.update.UpdateInstaller`, and it only ever runs because a user tapped a
 * button that says so. An uptime monitor replacing its own APK on its own
 * initiative would be indistinguishable from the thing every user is told to be
 * afraid of, so the decision to fetch stays a tap and the install itself stays
 * behind Android's own prompt.
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
        /** Direct link to the APK, or blank when the source does not offer one. */
        val apkUrl: String = "",
        val apkSize: Long = 0L,
    )

    enum class Action { NONE, NOTIFY }

    data class Decision(
        val action: Action,
        val state: UpdateState,
        val release: Release? = null,
    )

    /** What the dashboard banner says, when there is one to show. */
    data class Banner(
        val latestVersion: String,
        val installedVersion: String,
        /** The release page, for "What's new". */
        val url: String,
        /** The APK, for the install button. Blank hides that button entirely. */
        val apkUrl: String = "",
        val apkSize: Long = 0L,
    )

    /**
     * Whether the dashboard should be carrying an update banner, and what it says.
     *
     * 3.2.0 shipped the check and gave it one surface: a notification, once per
     * version, ever. Seven gates stand between a release and the user and six of
     * them fail silently, the worst being `backgroundChecksEnabled`, which stops
     * `SweepWorker` and therefore the update check with it. Someone who turned
     * background checks off was never going to hear about a new version again, and
     * nothing said so.
     *
     * Two gates are deliberately absent here, and they are the whole point:
     *
     *  - **[UpdateState.notifiedVersion] is not consulted.** That field is the
     *    notification's bookkeeping, recording that the shade was written to once.
     *    Reusing it would make the banner vanish after a single sighting, which is
     *    the behaviour being fixed rather than a rule to carry forward.
     *  - **`masterAlertsEnabled` is not consulted.** Alerts off means "do not
     *    interrupt me", not "never tell me anything". A banner on a screen the
     *    user chose to open interrupts nothing.
     *
     * What is consulted is every answer the user has actually given about this
     * version: turned the feature off, ignored this one, deferred all of them, or
     * already installed it.
     */
    fun bannerFor(
        state: UpdateState,
        installedVersion: String,
        enabled: Boolean,
        nowMs: Long,
    ): Banner? {
        if (!enabled) return null
        if (!isNewer(state.latestVersion, installedVersion)) return null
        if (state.latestVersion == state.ignoredVersion) return null
        if (nowMs < state.remindAfter) return null
        return Banner(
            latestVersion = state.latestVersion,
            installedVersion = installedVersion,
            // A release with no page of its own still needs somewhere to send
            // someone, or the banner's only action is a dead end.
            url = state.latestUrl.ifBlank { DOWNLOAD_URL },
            apkUrl = state.latestApkUrl,
            apkSize = state.latestApkSize,
        )
    }

    /**
     * Which source a copy of Nightbell should watch, given whatever installed it.
     *
     * The signature is what actually matters. F-Droid builds Nightbell
     * reproducibly and republishes the maintainer's own signed APK, so either
     * channel can update the other, but their publishing runs behind the tags by
     * design: telling an F-Droid user about a GitHub release is telling them
     * about something their client cannot give them for another week.
     *
     * The third-party clients are here because an app pulled from the F-Droid
     * repository through Droid-ify or Neo Store is an F-Droid install in every
     * way that counts. They can be pointed at other repositories, which is the
     * case this guesses wrong, and it stays a guess the user can overrule.
     *
     * A sideload reports null, or the shell on a device being driven by adb, and
     * both land on GitHub, which is where a sideloaded APK came from.
     */
    fun sourceForInstaller(installerPackage: String?): UpdateSource = when (installerPackage) {
        "org.fdroid.fdroid",
        "org.fdroid.basic",
        "com.looker.droidify",
        "com.machiav3lli.fdroid",
        -> UpdateSource.FDROID

        else -> UpdateSource.GITHUB
    }

    /** Undoes [ignore], for the Settings card. A mis-tap has to be recoverable. */
    fun unignore(state: UpdateState): UpdateState = state.copy(ignoredVersion = "")

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
            latestApkUrl = release.apkUrl,
            latestApkSize = release.apkSize,
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
