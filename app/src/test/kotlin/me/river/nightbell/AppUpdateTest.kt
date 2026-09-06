package me.river.nightbell

import me.river.nightbell.domain.AppUpdate
import me.river.nightbell.domain.UpdateSource
import me.river.nightbell.domain.UpdateState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When Nightbell is allowed to mention that Nightbell has moved on.
 *
 * Four ways a user can already have answered this question (installed it,
 * ignored it, deferred it, been told once), and getting any of them wrong turns
 * a helpful notice into the thing people uninstall an app over.
 */
class AppUpdateTest {

    private fun release(version: String, source: UpdateSource = UpdateSource.GITHUB) =
        AppUpdate.Release(
            version = version,
            url = "https://github.com/riveerxd/nightbell/releases/tag/v$version",
            source = source,
        )

    // ---- comparison ----------------------------------------------------------

    @Test
    fun `a higher version is newer`() {
        assertTrue(AppUpdate.isNewer("3.2.0", "3.1.1"))
        assertTrue(AppUpdate.isNewer("4.0.0", "3.9.9"))
        assertTrue(AppUpdate.isNewer("3.1.2", "3.1.1"))
    }

    @Test
    fun `the same version is not newer`() {
        assertFalse(AppUpdate.isNewer("3.1.1", "3.1.1"))
    }

    @Test
    fun `an older version is not newer`() {
        assertFalse(AppUpdate.isNewer("3.1.0", "3.1.1"))
        assertFalse(AppUpdate.isNewer("2.9.9", "3.0.0"))
    }

    @Test
    fun `segments are compared as numbers, not as text`() {
        // The classic. As strings, "3.10.0" sorts before "3.9.0".
        assertTrue(AppUpdate.isNewer("3.10.0", "3.9.0"))
        assertFalse(AppUpdate.isNewer("3.9.0", "3.10.0"))
    }

    @Test
    fun `a v prefix and a build suffix are noise`() {
        // The tag is `v3.2.0` and a debug install reports `3.2.0-debug`. Same
        // release, and telling a developer to update to what they are running
        // would be a good way to have the feature turned off.
        assertTrue(AppUpdate.isNewer("v3.2.0", "3.1.1"))
        assertFalse(AppUpdate.isNewer("v3.1.1", "3.1.1-debug"))
        assertEquals(0, AppUpdate.compare("v3.1.1", "3.1.1-minified"))
    }

    @Test
    fun `missing segments count as zero`() {
        assertEquals(0, AppUpdate.compare("3.1", "3.1.0"))
        assertTrue(AppUpdate.isNewer("3.2", "3.1.9"))
    }

    @Test
    fun `an unreadable version is never newer than anything`() {
        // Silence beats a notification about a version that may not exist.
        assertFalse(AppUpdate.isNewer("", "3.1.1"))
        assertFalse(AppUpdate.isNewer("nightly", "3.1.1"))
    }

    // ---- the decision --------------------------------------------------------

    @Test
    fun `a newer version is announced once`() {
        val first = AppUpdate.decide(release("3.2.0"), "3.1.1", UpdateState(), NOW)
        assertEquals(AppUpdate.Action.NOTIFY, first.action)
        assertEquals("3.2.0", first.release?.version)
        assertEquals("3.2.0", first.state.notifiedVersion)
        assertEquals(NOW, first.state.lastCheckedAt)

        // The next check finds the same thing and stays quiet about it.
        val second = AppUpdate.decide(release("3.2.0"), "3.1.1", first.state, NOW + HOUR)
        assertEquals(AppUpdate.Action.NONE, second.action)
    }

    @Test
    fun `the version already installed is never announced`() {
        val same = AppUpdate.decide(release("3.1.1"), "3.1.1", UpdateState(), NOW)
        assertEquals(AppUpdate.Action.NONE, same.action)
        val older = AppUpdate.decide(release("3.0.5"), "3.1.1", UpdateState(), NOW)
        assertEquals(AppUpdate.Action.NONE, older.action)
    }

    @Test
    fun `a failed check records only that it happened`() {
        val previous = UpdateState(latestVersion = "3.2.0", notifiedVersion = "3.2.0")
        val decision = AppUpdate.decide(null, "3.1.1", previous, NOW)
        assertEquals(AppUpdate.Action.NONE, decision.action)
        assertEquals(NOW, decision.state.lastCheckedAt)
        // And nothing it knew is thrown away on the strength of one failure.
        assertEquals("3.2.0", decision.state.latestVersion)
        assertEquals("3.2.0", decision.state.notifiedVersion)
    }

    @Test
    fun `ignore this version suppresses that version and only that version`() {
        val told = AppUpdate.decide(release("3.2.0"), "3.1.1", UpdateState(), NOW)
        val ignored = AppUpdate.ignore(told.state, "3.2.0")

        // Silent about 3.2.0 for as long as it is the newest thing going.
        val again = AppUpdate.decide(release("3.2.0"), "3.1.1", ignored, NOW + DAY)
        assertEquals(AppUpdate.Action.NONE, again.action)

        // Loud about the one after it, which is the whole difference between
        // "not this one" and "never again".
        val next = AppUpdate.decide(release("3.3.0"), "3.1.1", again.state, NOW + 2 * DAY)
        assertEquals(AppUpdate.Action.NOTIFY, next.action)
        assertEquals("3.3.0", next.release?.version)
    }

    @Test
    fun `remind later delays without disabling anything`() {
        val told = AppUpdate.decide(release("3.2.0"), "3.1.1", UpdateState(), NOW)
        val deferred = AppUpdate.remindLater(told.state, NOW)
        assertEquals(NOW + AppUpdate.REMIND_LATER_MS, deferred.remindAfter)

        // Inside the window, nothing, not even for a newer version: the user said
        // "not now" rather than "not this".
        val soon = AppUpdate.decide(release("3.2.0"), "3.1.1", deferred, NOW + HOUR)
        assertEquals(AppUpdate.Action.NONE, soon.action)
        val newerSoon = AppUpdate.decide(release("3.3.0"), "3.1.1", deferred, NOW + HOUR)
        assertEquals(AppUpdate.Action.NONE, newerSoon.action)

        // Past it, the same version speaks again, because nothing was refused.
        val later = AppUpdate.decide(release("3.2.0"), "3.1.1", deferred, NOW + DAY + HOUR)
        assertEquals(AppUpdate.Action.NOTIFY, later.action)
    }

    @Test
    fun `installing the update clears everything the user answered about it`() {
        var state = AppUpdate.decide(release("3.2.0"), "3.1.1", UpdateState(), NOW).state
        state = AppUpdate.ignore(state, "3.2.0")
        state = AppUpdate.remindLater(state, NOW)

        // They went and installed it. None of those answers describe anything any
        // more, and leaving `ignoredVersion` behind would be harmless until the
        // day a version number came round again.
        val decision = AppUpdate.decide(release("3.2.0"), "3.2.0", state, NOW + DAY)
        assertEquals(AppUpdate.Action.NONE, decision.action)
        assertEquals("", decision.state.ignoredVersion)
        assertEquals("", decision.state.notifiedVersion)
        assertEquals(0L, decision.state.remindAfter)
    }

    @Test
    fun `ignore with no version named falls back to the newest seen`() {
        val told = AppUpdate.decide(release("3.2.0"), "3.1.1", UpdateState(), NOW)
        assertEquals("3.2.0", AppUpdate.ignore(told.state, "").ignoredVersion)
    }

    // ---- the throttle --------------------------------------------------------

    @Test
    fun `a check that has never run is due`() {
        assertTrue(AppUpdate.isDue(UpdateState(), NOW))
    }

    @Test
    fun `checks are six hours apart`() {
        val checked = UpdateState(lastCheckedAt = NOW)
        assertFalse(AppUpdate.isDue(checked, NOW + HOUR))
        assertFalse(AppUpdate.isDue(checked, NOW + 5 * HOUR))
        assertTrue(AppUpdate.isDue(checked, NOW + AppUpdate.CHECK_INTERVAL_MS))
    }

    @Test
    fun `the source carries into the state so the notice can name it`() {
        val decision = AppUpdate.decide(release("3.2.0", UpdateSource.FDROID), "3.1.1", UpdateState(), NOW)
        assertEquals(UpdateSource.FDROID, decision.state.latestSource)
        assertEquals("F-Droid", decision.state.latestSource.label)
        assertNull(UpdateState().latestVersion.ifBlank { null })
    }

    // ---- the dashboard banner ------------------------------------------------
    //
    // A separate surface with separate rules, and the two rules it does *not*
    // share with the notification are the reason this exists at all.

    private fun banner(
        state: UpdateState,
        installed: String = "3.2.1",
        enabled: Boolean = true,
        nowMs: Long = NOW,
    ) = AppUpdate.bannerFor(state, installed, enabled, nowMs)

    private fun seen(
        version: String,
        ignored: String = "",
        notified: String = "",
        remindAfter: Long = 0L,
        url: String = "https://github.com/riveerxd/nightbell/releases/tag/v3.2.2",
    ) = UpdateState(
        latestVersion = version,
        latestUrl = url,
        ignoredVersion = ignored,
        notifiedVersion = notified,
        remindAfter = remindAfter,
        lastCheckedAt = NOW,
    )

    @Test
    fun `a newer version raises a banner`() {
        val shown = banner(seen("3.2.2"))
        assertEquals("3.2.2", shown?.latestVersion)
        assertEquals("3.2.1", shown?.installedVersion)
        assertEquals("https://github.com/riveerxd/nightbell/releases/tag/v3.2.2", shown?.url)
    }

    @Test
    fun `an f-droid install watches f-droid`() {
        // The default of GitHub told these users about tags their client cannot
        // hand them for another week, and nobody knew there was a switch.
        assertEquals(UpdateSource.FDROID, AppUpdate.sourceForInstaller("org.fdroid.fdroid"))
        assertEquals(UpdateSource.FDROID, AppUpdate.sourceForInstaller("org.fdroid.basic"))
        assertEquals(UpdateSource.FDROID, AppUpdate.sourceForInstaller("com.looker.droidify"))
        assertEquals(UpdateSource.FDROID, AppUpdate.sourceForInstaller("com.machiav3lli.fdroid"))
    }

    @Test
    fun `everything else watches the site the app is published from`() {
        // Was GitHub until 3.10.0, on the reasoning that a sideload came from
        // there. True and beside the point: the site serves the identical release
        // and asking it tells one fewer stranger where the request came from.
        assertEquals(UpdateSource.DIRECT, AppUpdate.sourceForInstaller(null))
        assertEquals(UpdateSource.DIRECT, AppUpdate.sourceForInstaller(""))
        assertEquals(UpdateSource.DIRECT, AppUpdate.sourceForInstaller("com.android.shell"))
        assertEquals(UpdateSource.DIRECT, AppUpdate.sourceForInstaller("com.google.android.packageinstaller"))
    }

    @Test
    fun `the play store is not mistaken for f-droid`() {
        // Nightbell is not on Play, so this can only be a repackage. Reading the
        // site is the honest answer rather than guessing at a store listing.
        assertEquals(UpdateSource.DIRECT, AppUpdate.sourceForInstaller("com.android.vending"))
    }

    // ---- the one-time move onto the app's own site ---------------------------

    @Test
    fun `an install nobody has configured takes the guess from its installer`() {
        assertEquals(
            UpdateSource.DIRECT,
            AppUpdate.sourceOnStartup(UpdateSource.GITHUB, chosen = false, migrated = false, installerPackage = null),
        )
        assertEquals(
            UpdateSource.FDROID,
            AppUpdate.sourceOnStartup(
                UpdateSource.GITHUB,
                chosen = false,
                migrated = false,
                installerPackage = "org.fdroid.fdroid",
            ),
        )
    }

    @Test
    fun `an existing install on github is moved to the site once`() {
        // The whole reason updateSourceMigratedToSite exists. Without it every
        // install that predates 3.10.0 would sit on GitHub forever, because
        // updateSourceChosen was already true for all of them.
        assertEquals(
            UpdateSource.DIRECT,
            AppUpdate.sourceOnStartup(
                UpdateSource.GITHUB,
                chosen = true,
                migrated = false,
                installerPackage = null,
            ),
        )
    }

    @Test
    fun `an f-droid install is not moved, because the site is no more use to it`() {
        assertEquals(
            UpdateSource.FDROID,
            AppUpdate.sourceOnStartup(
                UpdateSource.FDROID,
                chosen = true,
                migrated = false,
                installerPackage = "org.fdroid.fdroid",
            ),
        )
    }

    @Test
    fun `once moved, a deliberate choice of github is left alone forever`() {
        // The move happens once and never argues with the user afterwards. Anyone
        // who reads the blurb and decides they would rather tell GitHub than tell
        // the maintainer has made a real choice, and re-running the migration on
        // the next launch would quietly overrule it every time.
        assertEquals(
            UpdateSource.GITHUB,
            AppUpdate.sourceOnStartup(
                UpdateSource.GITHUB,
                chosen = true,
                migrated = true,
                installerPackage = null,
            ),
        )
        assertEquals(
            UpdateSource.FDROID,
            AppUpdate.sourceOnStartup(
                UpdateSource.FDROID,
                chosen = true,
                migrated = true,
                installerPackage = null,
            ),
        )
        assertEquals(
            UpdateSource.DIRECT,
            AppUpdate.sourceOnStartup(
                UpdateSource.DIRECT,
                chosen = true,
                migrated = true,
                installerPackage = "org.fdroid.fdroid",
            ),
        )
    }

    @Test
    fun `a fresh f-droid install is never moved off f-droid by the migration`() {
        // The two flags land together on a fresh install, so the guess is the
        // last word. This is the ordering bug the combined function exists to
        // make impossible: guess F-Droid, then immediately migrate it to the site
        // because the migration ran second and only looked at the source.
        val guessed = AppUpdate.sourceOnStartup(
            UpdateSource.DIRECT,
            chosen = false,
            migrated = false,
            installerPackage = "com.looker.droidify",
        )
        assertEquals(UpdateSource.FDROID, guessed)
        assertEquals(
            UpdateSource.FDROID,
            AppUpdate.sourceOnStartup(guessed, chosen = true, migrated = true, installerPackage = null),
        )
    }

    // ---- the census agent ----------------------------------------------------

    @Test
    fun `the first check ever appends new and no other check does`() {
        assertEquals("Nightbell/3.9.0 (Android; new)", AppUpdate.censusAgent("3.9.0", firstEver = true))
        assertEquals("Nightbell/3.9.0 (Android)", AppUpdate.censusAgent("3.9.0", firstEver = false))
    }

    @Test
    fun `a check the user asked for says so, so it can be left out of the arithmetic`() {
        // Active installs are read as scheduled checks over four, four being the
        // most the six-hour interval allows in a day. "Check now" passes force and
        // skips the interval, so without this marker one person pressing it forty
        // times reads as ten more installs and the floor is not a floor.
        assertEquals(
            "Nightbell/3.9.0 (Android; tap)",
            AppUpdate.censusAgent("3.9.0", firstEver = false, forced = true),
        )
        assertEquals(
            "Nightbell/3.9.0 (Android; new; tap)",
            AppUpdate.censusAgent("3.9.0", firstEver = true, forced = true),
        )
        // Order matters to nothing but the reader, and the reader is `census.sh`,
        // which matches `; new` followed by either a semicolon or the paren for
        // exactly this reason.
        assertTrue(
            AppUpdate.censusAgent("3.9.0", firstEver = true, forced = true).contains("; new;"),
        )
    }

    @Test
    fun `a scheduled check is still the default shape`() {
        // The parameter is defaulted, so every existing caller keeps sending the
        // unmarked agent and only the forced path opts in.
        assertEquals(
            AppUpdate.censusAgent("3.9.0", firstEver = false),
            AppUpdate.censusAgent("3.9.0", firstEver = false, forced = false),
        )
    }

    @Test
    fun `the agent carries a version and nothing that could identify an install`() {
        // Written as the property rather than as the string, because the string
        // is already asserted above and the property is the thing that must not
        // regress: the version is the only part that varies between two installs.
        // Blank the version out of each and what is left has to be identical, so
        // a future edit that adds a device model, a locale, a build fingerprint
        // or an id fails here.
        val one = AppUpdate.censusAgent("3.9.0", firstEver = true).replace("3.9.0", "")
        val two = AppUpdate.censusAgent("41.2.7", firstEver = true).replace("41.2.7", "")
        assertEquals(one, two)
        // And two installs on the same version send byte-identical headers, so
        // there is nothing in the request to tell them apart with at all.
        assertEquals(
            AppUpdate.censusAgent("3.9.0", firstEver = true),
            AppUpdate.censusAgent("3.9.0", firstEver = true),
        )
    }

    @Test
    fun `a debug build reports its own suffix rather than hiding it`() {
        // The suffix is real and the log is allowed to say so: a stream of
        // -debug checks is the maintainer's emulator, and subtracting it from
        // the count requires being able to see it.
        assertEquals(
            "Nightbell/3.9.0-debug (Android)",
            AppUpdate.censusAgent("3.9.0-debug", firstEver = false),
        )
    }

    @Test
    fun `a blank version still produces a parseable agent`() {
        // BuildConfig cannot be blank, but the injection point can be in a test
        // or a future caller, and an agent of "Nightbell/ (Android)" would land
        // in the version histogram as an empty column nobody could explain.
        assertEquals("Nightbell/unknown (Android)", AppUpdate.censusAgent("", firstEver = false))
        assertEquals("Nightbell/unknown (Android)", AppUpdate.censusAgent("   ", firstEver = false))
    }

    @Test
    fun `being counted survives everything the user can answer about a version`() {
        // censusSent is not part of the update conversation and must not be reset
        // by any part of it. Ignoring a version, deferring one, or catching up
        // are all answers about a release; whether this install has ever been
        // seen is not, and clearing it would count one install twice.
        val counted = UpdateState(censusSent = true, lastCheckedAt = 1_000L)
        val release = AppUpdate.Release(
            version = "3.9.0",
            url = "https://nightbell.app/download",
            source = UpdateSource.DIRECT,
        )
        assertTrue(AppUpdate.decide(release, "3.8.0", counted, 2_000L).state.censusSent)
        // Caught up, which resets the deferrals. Not this.
        assertTrue(AppUpdate.decide(release, "3.9.0", counted, 2_000L).state.censusSent)
        // A failed check, which records only that the attempt happened.
        assertTrue(AppUpdate.decide(null, "3.8.0", counted, 2_000L).state.censusSent)
        assertTrue(AppUpdate.ignore(counted, "3.9.0").censusSent)
        assertTrue(AppUpdate.remindLater(counted, 2_000L).censusSent)
        assertTrue(AppUpdate.showNow(counted).censusSent)
        assertTrue(AppUpdate.unignore(counted).censusSent)
    }

    @Test
    fun `the banner carries the apk so the install button has something to fetch`() {
        val state = seen("3.2.2").copy(
            latestApkUrl = "https://example.com/nightbell-3.2.2.apk",
            latestApkSize = 15_400_000L,
        )
        val shown = banner(state)
        assertEquals("https://example.com/nightbell-3.2.2.apk", shown?.apkUrl)
        assertEquals(15_400_000L, shown?.apkSize)
    }

    @Test
    fun `a release with no apk leaves the banner with nothing to install`() {
        // Not a fabricated download link. The surface hides the button rather than
        // offering a URL nobody published.
        assertEquals("", banner(seen("3.2.2"))?.apkUrl)
    }

    @Test
    fun `the fetched apk address is folded into the state the banner reads`() {
        val release = AppUpdate.Release(
            version = "3.2.2",
            url = "https://example.com/r",
            source = UpdateSource.GITHUB,
            apkUrl = "https://example.com/nightbell-3.2.2.apk",
            apkSize = 15_400_000L,
        )
        val decided = AppUpdate.decide(release, "3.2.1", UpdateState(), NOW)
        assertEquals("https://example.com/nightbell-3.2.2.apk", decided.state.latestApkUrl)
        assertEquals(15_400_000L, decided.state.latestApkSize)
    }

    @Test
    fun `the banner still shows after the notification has been posted`() {
        // The whole point of the change, named so it cannot be quietly undone.
        // `notifiedVersion` means "the shade was written to once", and reusing it
        // here is exactly what made a new release visible for one glance and then
        // never again.
        assertNotNull(banner(seen("3.2.2", notified = "3.2.2")))
    }

    @Test
    fun `the version you are running raises nothing`() {
        assertNull(banner(seen("3.2.1")))
    }

    @Test
    fun `an older version raises nothing`() {
        assertNull(banner(seen("3.1.9")))
    }

    @Test
    fun `an unreadable version raises nothing`() {
        // A check that came back with something that is not a version is not
        // evidence of a release. Same rule as the notification's.
        assertNull(banner(seen("nightly")))
        assertNull(banner(seen("")))
    }

    @Test
    fun `an ignored version raises nothing, and the one after it does`() {
        assertNull(banner(seen("3.2.2", ignored = "3.2.2")))
        assertNotNull(banner(seen("3.2.3", ignored = "3.2.2")))
    }

    @Test
    fun `remind later holds the banner too`() {
        val deferred = seen("3.2.2", remindAfter = NOW + DAY)
        assertNull(banner(deferred, nowMs = NOW))
        assertNull(banner(deferred, nowMs = NOW + DAY - 1))
        assertNotNull(banner(deferred, nowMs = NOW + DAY))
    }

    @Test
    fun `turning update checks off hides the banner`() {
        assertNull(banner(seen("3.2.2"), enabled = false))
    }

    @Test
    fun `a release with no page of its own still has somewhere to send you`() {
        // Otherwise the banner's only action does nothing, which is worse than
        // the banner not being there.
        assertEquals(AppUpdate.DOWNLOAD_URL, banner(seen("3.2.2", url = ""))?.url)
    }

    @Test
    fun `a debug build compares as its release version`() {
        // The installed name on a debug build carries a suffix, and 3.2.1-debug is
        // the same release as 3.2.1. Without this every debug launch would claim an
        // update was available.
        assertNull(banner(seen("3.2.1"), installed = "3.2.1-debug"))
        assertNotNull(banner(seen("3.2.2"), installed = "3.2.1-debug"))
    }

    @Test
    fun `show it again clears the refusal and nothing else`() {
        val ignored = AppUpdate.ignore(seen("3.2.2"), "3.2.2")
        assertNull(banner(ignored))

        val restored = AppUpdate.unignore(ignored)
        assertNotNull(banner(restored))
        // Only the refusal is lifted. The version it found and when it last looked
        // are observations, not decisions, and must survive.
        assertEquals("3.2.2", restored.latestVersion)
        assertEquals(NOW, restored.lastCheckedAt)
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val HOUR = 60L * 60 * 1000
        const val DAY = 24 * HOUR
    }

    // ---- where the notification sends somebody -----------------------------

    /**
     * The bug: the update notification opened a web page even though the app had
     * been able to fetch and install the APK itself for several releases. The
     * user downloaded by hand and installed by hand, twice the work the app
     * already knew how to do.
     */
    @Test
    fun `a release with an apk routes into the app rather than into a browser`() {
        val release = AppUpdate.Release(
            version = "3.9.0",
            url = "https://github.com/riveerxd/nightbell/releases/tag/v3.9.0",
            source = UpdateSource.GITHUB,
            apkUrl = "https://github.com/riveerxd/nightbell/releases/download/v3.9.0/app.apk",
            apkSize = 15_000_000L,
        )
        assertEquals(AppUpdate.NoticeRoute.OpenApp, AppUpdate.noticeRoute(release))
    }

    @Test
    fun `an f-droid release with an apk routes into the app too`() {
        // Both sources publish an APK, so both can be installed from inside the
        // app. Routing on the source rather than on whether there is a file
        // would have fixed this for GitHub only.
        val release = AppUpdate.Release(
            version = "3.9.0",
            url = AppUpdate.FDROID_URL,
            source = UpdateSource.FDROID,
            apkUrl = "https://f-droid.org/repo/me.river.nightbell_38.apk",
        )
        assertEquals(AppUpdate.NoticeRoute.OpenApp, AppUpdate.noticeRoute(release))
    }

    @Test
    fun `a release with nothing to install still has somewhere to send somebody`() {
        val release = AppUpdate.Release(
            version = "3.9.0",
            url = "https://example.com/notes",
            source = UpdateSource.GITHUB,
            apkUrl = "",
        )
        assertEquals(
            AppUpdate.NoticeRoute.OpenLink("https://example.com/notes"),
            AppUpdate.noticeRoute(release),
        )
    }

    @Test
    fun `a release with no page and no apk falls back to the download page`() {
        val release = AppUpdate.Release(version = "3.9.0", url = "", source = UpdateSource.GITHUB)
        assertEquals(
            AppUpdate.NoticeRoute.OpenLink(AppUpdate.DOWNLOAD_URL),
            AppUpdate.noticeRoute(release),
        )
    }

    /**
     * The body and the destination are not allowed to disagree.
     *
     * They did: the notification said "this opens the download page" while the
     * app could install the update itself, and the sentence was the only thing
     * telling the user what to expect.
     */
    @Test
    fun `the notification body says what the tap will actually do`() {
        val installable = AppUpdate.Release(
            version = "3.9.0",
            url = "https://example.com/notes",
            source = UpdateSource.GITHUB,
            apkUrl = "https://example.com/app.apk",
        )
        val body = AppUpdate.noticeBody(installable, installedVersion = "3.8.0")
        assertTrue(body.contains("open Nightbell and install it from there"))
        assertFalse(body.contains("opens the download page"))
        // And the promise the app makes everywhere else about installing is kept.
        assertTrue(body.contains("Android asks before anything is installed"))
        assertTrue(body.contains("3.8.0"))
        assertTrue(body.contains("3.9.0"))

        val pageOnly = installable.copy(apkUrl = "")
        val pageBody = AppUpdate.noticeBody(pageOnly, installedVersion = "3.8.0")
        assertTrue(pageBody.contains("no APK to install from here"))
        assertFalse(pageBody.contains("open Nightbell and install"))
    }

    /**
     * Tapping the notice overrides a deferral.
     *
     * This is the bug the first version of the notification fix still had: the
     * tap led to the dashboard, and the banner it led to was hidden by
     * `remindLater`, so anybody who had dismissed the banner once got a
     * notification that opened a screen with nothing on it.
     */
    @Test
    fun `tapping the notice clears a remind-later deferral`() {
        val deferred = AppUpdate.remindLater(
            UpdateState(latestVersion = "3.9.0", latestUrl = "https://example.com"),
            nowMs = 1_000L,
        )
        // Proof the deferral is what hides the banner in the first place.
        assertNull(
            AppUpdate.bannerFor(
                state = deferred,
                installedVersion = "3.8.0",
                enabled = true,
                nowMs = 2_000L,
            ),
        )
        val asked = AppUpdate.showNow(deferred)
        assertNotNull(
            AppUpdate.bannerFor(
                state = asked,
                installedVersion = "3.8.0",
                enabled = true,
                nowMs = 2_000L,
            ),
        )
    }

    @Test
    fun `tapping the notice does not undo a refused version`() {
        // "Ignore this version" is a standing answer about that version. A stale
        // notification for one the user has since refused must not revive it.
        val refused = AppUpdate.ignore(
            UpdateState(latestVersion = "3.9.0", latestUrl = "https://example.com"),
            version = "3.9.0",
        )
        val asked = AppUpdate.showNow(refused)
        assertEquals("3.9.0", asked.ignoredVersion)
        assertNull(
            AppUpdate.bannerFor(
                state = asked,
                installedVersion = "3.8.0",
                enabled = true,
                nowMs = 2_000L,
            ),
        )
    }
}
