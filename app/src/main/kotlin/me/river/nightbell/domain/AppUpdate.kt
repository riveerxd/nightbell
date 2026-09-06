package me.river.nightbell.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Where Nightbell looks for a newer Nightbell. */
@Serializable
enum class UpdateSource {
    /**
     * A small file on the app's own site, listing the newest release.
     *
     * The default, and the reason it is the default is worth writing down. Every
     * copy of this app already asked a stranger for a version number four times a
     * day: GitHub's API, or F-Droid's, from every install with checks turned on.
     * That is a third party learning an address and a rhythm, four times a day,
     * for a number that fits in a sentence.
     *
     * This reads the same number off nightbell.app instead, which is the one host
     * that has a reason to be told. It also happens to answer the only question
     * about this app that had no answer at all: how many copies are running. The
     * request carries the version and nothing else, the reply is a static file,
     * and what is kept is described in `website/deploy/nginx/nightbell.app.conf`
     * under the census log format. No identifier is generated, sent or stored, and the
     * address the request came from is never written down.
     *
     * [GITHUB] stays selectable for anyone who would rather tell GitHub than tell
     * me. That is a real preference and removing the option would make this a
     * decision taken on someone's behalf.
     */
    @SerialName("direct")
    DIRECT,

    /** The APKs the maintainer signs and attaches to a tag, read from GitHub. */
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

    /**
     * Short on purpose. These are three segments of one control now, and the
     * segment clips rather than wraps, so "GitHub releases" in a third of a
     * phone's width was a label with its end cut off.
     */
    val label: String
        get() = when (this) {
            DIRECT -> "nightbell.app"
            GITHUB -> "GitHub"
            FDROID -> "F-Droid"
        }

    val blurb: String
        get() = when (this) {
            DIRECT -> "Reads the version from the app's own site. The request says which " +
                "version you run, and it is counted so I know how many installs are " +
                "live. Nothing identifies you and your address is not kept."

            GITHUB -> "Asks GitHub's API instead. The same release, and GitHub sees the " +
                "request rather than me."

            FDROID -> "Matches what your F-Droid client can install, which trails the tags " +
                "a little."
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

    /**
     * Whether this install has ever completed a check against its own site.
     *
     * Exists so the first one can say so and no later one can. See
     * [AppUpdate.censusAgent]: this is the flag behind the `new` word, and the
     * reason a count of installs needs no identifier attached to a device.
     *
     * Set only when a manifest actually came back parsed, not merely when a
     * request was attempted, so an install that is offline for its first three
     * days is still counted as new on the fourth. The gap that leaves is a reply
     * that reached the origin and then failed to parse, which logs `new` and
     * tries again later, counting one install twice. That needs a broken deploy
     * to happen at all and it is preferred over the opposite mistake, which is
     * losing a real install because one byte was wrong.
     */
    val censusSent: Boolean = false,
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

    /** The site's origin, and the release manifest [UpdateSource.DIRECT] reads. */
    const val SITE_BASE = "https://nightbell.app"

    /**
     * Versioned in the path, and it has to be.
     *
     * Every published APK reads whichever path was compiled into it, forever, and
     * the oldest install in the wild is the one that decides when a payload shape
     * can change. A path with a version in it means a future schema can ship at
     * /v2/ while /v1/ keeps answering the copies that only understand it. Without
     * that, changing a field name is a choice between breaking old installs and
     * never changing anything.
     */
    const val MANIFEST_PATH = "/v1/release.json"

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
     * Where the update notification should send somebody who taps it.
     *
     * It sent them to a web page, always, and that was wrong from the release the
     * in-app installer shipped in: the app can fetch and hand over the APK
     * itself, the dashboard banner has offered exactly that ever since, and the
     * notification was still routing around it into a browser. The user then
     * downloaded the file by hand and installed it by hand, twice as much work
     * as the app already knew how to do for them.
     *
     * [OpenApp] whenever there is an APK to install. Not the install itself:
     * "Nightbell never installs anything by itself" is a promise the app makes
     * out loud, so a notification tap opens the screen where Install is a
     * deliberate press, and Android still asks after that.
     *
     * [OpenLink] only when the release has no APK behind it, which is the one
     * case where a page is the only route there is.
     */
    sealed interface NoticeRoute {
        /** Open Nightbell, where the banner offers Install. */
        data object OpenApp : NoticeRoute

        /** Open a page, because there is nothing to install from here. */
        data class OpenLink(val url: String) : NoticeRoute
    }

    fun noticeRoute(release: Release): NoticeRoute =
        if (release.apkUrl.isNotBlank()) {
            NoticeRoute.OpenApp
        } else {
            NoticeRoute.OpenLink(release.url.ifBlank { DOWNLOAD_URL })
        }

    /**
     * What the notification's body says, which has to match where it goes.
     *
     * Kept next to [noticeRoute] rather than in `AlertCenter`, because the two
     * were allowed to disagree once already: the body promised "this opens the
     * download page" while the app had been able to install the update itself
     * for several releases.
     */
    fun noticeBody(release: Release, installedVersion: String): String = buildString {
        append("You are on ").append(installedVersion)
        append(". Version ").append(release.version)
        append(" is available from ").append(release.source.label).append(".")
        append("\n\n")
        when (noticeRoute(release)) {
            is NoticeRoute.OpenApp -> append(
                "Tap to open Nightbell and install it from there. Nothing is " +
                    "downloaded until you press Install, and Android asks before " +
                    "anything is installed.",
            )

            is NoticeRoute.OpenLink -> append(
                "This release has no APK to install from here, so this opens its " +
                    "page.",
            )
        }
    }

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

        else -> UpdateSource.DIRECT
    }

    /**
     * Which source this install should watch, at startup, given everything
     * already known about it.
     *
     * One function rather than two branches at the call site, because the three
     * inputs interact and the interactions are the part that is easy to get
     * wrong. It answers, in order:
     *
     *  - Nobody has touched the switch: guess from whatever installed this copy.
     *    A fresh install lands here, and so does an install that has never opened
     *    the Settings card.
     *  - The switch has been touched but this install predates
     *    [UpdateSource.DIRECT]: move it off GitHub and leave every other answer
     *    alone. An F-Droid user is not moved, because the site's newest tag is as
     *    unreachable for them as GitHub's was.
     *  - Otherwise: whatever it already says.
     *
     * Both `chosen` and `migrated` are true afterwards regardless of the branch,
     * which is what makes this run once per install rather than once per launch.
     *
     * @param installerPackage the package that installed this copy, as Android
     *   reports it, and null for a sideload or an adb push.
     */
    fun sourceOnStartup(
        current: UpdateSource,
        chosen: Boolean,
        migrated: Boolean,
        installerPackage: String?,
    ): UpdateSource = when {
        !chosen -> sourceForInstaller(installerPackage)
        !migrated && current == UpdateSource.GITHUB -> UpdateSource.DIRECT
        else -> current
    }

    /**
     * The User-Agent the [UpdateSource.DIRECT] request carries, and the only
     * place in this app that sends a version number anywhere.
     *
     * It goes to nightbell.app and nowhere else. Monitor checks keep
     * `HttpChecker.USER_AGENT`, which names no version, because the sites a user
     * chose to watch have no business knowing which build is watching them.
     *
     * `new` is appended by the first check an install ever completes, and never
     * again. That is the whole mechanism behind an install count: one request per
     * install, ever, carrying one word. It is not an identifier and cannot become
     * one. Nothing distinguishes two installs that send it on the same day, the
     * flag says nothing about which install sent it, and it is gone from the log
     * the moment the line is counted.
     *
     * Clearing the app's data resets it, so a wipe and a reinstall count as two.
     * That is a known overcount and the alternative is a durable identifier,
     * which is the thing this design exists to avoid.
     *
     * `tap` marks a check the user asked for, and it exists because the count
     * depends on the cadence being known. Active installs are read as requests
     * per day over four, four being the most [CHECK_INTERVAL_MS] allows in a day,
     * which makes the result a floor rather than a guess. "Check now" passes
     * `force` and skips [isDue] entirely, so without this marker one person
     * pressing that button forty times in an afternoon reads as ten more installs
     * and the floor stops being a floor. Marked rather than suppressed: the
     * request is a real check and belongs in the version histogram, it just has
     * no place in the arithmetic.
     */
    fun censusAgent(version: String, firstEver: Boolean, forced: Boolean = false): String {
        val name = version.trim().ifBlank { "unknown" }
        val notes = buildList {
            if (firstEver) add("new")
            if (forced) add("tap")
        }
        val suffix = if (notes.isEmpty()) "" else notes.joinToString(prefix = "; ", separator = "; ")
        return "Nightbell/$name (Android$suffix)"
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

    /**
     * The user tapped the notice and is asking to see the update now.
     *
     * Clears the deferral, and it has to: "Remind later" quietens the surfaces
     * that appear on their own, and a tap on the notification is not one of
     * those. Without this the notice led to the dashboard and the banner it
     * leads to was suppressed by [remindLater], so the tap was a dead end for
     * anybody who had ever dismissed the banner once.
     *
     * `ignoredVersion` is deliberately left alone. Refusing a version is a
     * standing answer about that version, and a stale notification for one the
     * user has since refused should not undo it.
     */
    fun showNow(state: UpdateState): UpdateState = state.copy(remindAfter = 0L)

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
