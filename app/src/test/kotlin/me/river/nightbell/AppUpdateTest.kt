package me.river.nightbell

import me.river.nightbell.domain.AppUpdate
import me.river.nightbell.domain.UpdateSource
import me.river.nightbell.domain.UpdateState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val HOUR = 60L * 60 * 1000
        const val DAY = 24 * HOUR
    }
}
