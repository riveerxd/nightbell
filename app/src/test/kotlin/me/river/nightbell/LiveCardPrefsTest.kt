package me.river.nightbell

import kotlinx.serialization.json.Json
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.Health
import me.river.nightbell.domain.LiveCardPrefs
import me.river.nightbell.domain.LiveFollow
import me.river.nightbell.domain.LiveLabel
import me.river.nightbell.domain.LiveNotice
import me.river.nightbell.domain.LiveScope
import me.river.nightbell.domain.LiveTimeline
import me.river.nightbell.domain.LiveWindow
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorGroup
import me.river.nightbell.domain.MonitorRuntime
import me.river.nightbell.domain.Sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The live card's three choices: whose line, how far back, which figure.
 *
 * What is worth pinning is the part a screenshot cannot show: that following a
 * group draws only that group's history, that a deleted target falls back to
 * everything instead of to a calm empty card, and that each label states the
 * number it claims to.
 */
class LiveCardPrefsTest {

    private val now = 1_700_000_000_000L
    private val minute = 60_000L

    private fun monitor(id: String, intervalMinutes: Int = 5) =
        Monitor(id = id, name = id, url = "https://$id.example", intervalMinutes = intervalMinutes)

    private fun runtime(health: Health = Health.UP, latency: Long = 140, vararg minutesAgoToOk: Pair<Long, Boolean>) =
        MonitorRuntime(
            health = health,
            lastLatencyMs = latency,
            lastCheckedAt = now - minute,
            samples = minutesAgoToOk.map { (ago, ok) -> Sample(at = now - ago * minute, ok = ok, latencyMs = latency) }
                .sortedBy { it.at },
        )

    private val site = monitor("site")
    private val repo = monitor("repo")
    private val nas = monitor("nas")
    private val all = listOf(site, repo, nas)
    private val groups = listOf(MonitorGroup(id = "g", title = "Nightbell", memberIds = listOf("site", "repo")))

    // ---- scope -------------------------------------------------------------

    @Test
    fun everythingIsEveryMonitorWithNoName() {
        val scope = LiveScope.resolve(LiveCardPrefs(), all, groups)
        assertEquals(all, scope.monitors)
        assertNull(scope.name)
        assertFalse(scope.fellBack)
    }

    @Test
    fun aGroupIsItsMembersUnderItsTitle() {
        val scope = LiveScope.resolve(LiveCardPrefs(follows = LiveFollow.GROUP, targetId = "g"), all, groups)
        assertEquals(listOf("site", "repo"), scope.monitors.map { it.id })
        assertEquals("Nightbell", scope.name)
        assertEquals(LiveFollow.GROUP, scope.follows)
    }

    @Test
    fun oneMonitorIsJustThatMonitor() {
        val scope = LiveScope.resolve(LiveCardPrefs(follows = LiveFollow.MONITOR, targetId = "nas"), all, groups)
        assertEquals(listOf("nas"), scope.monitors.map { it.id })
        assertEquals("nas", scope.name)
    }

    @Test
    fun aDeletedGroupFallsBackToEverythingAndSaysSo() {
        val scope = LiveScope.resolve(LiveCardPrefs(follows = LiveFollow.GROUP, targetId = "gone"), all, groups)
        assertEquals(all, scope.monitors)
        assertNull(scope.name)
        assertTrue(scope.fellBack)
    }

    @Test
    fun anEmptiedGroupFallsBackRatherThanDrawingNothing() {
        val empty = listOf(MonitorGroup(id = "g", title = "Nightbell", memberIds = listOf("deleted")))
        val scope = LiveScope.resolve(LiveCardPrefs(follows = LiveFollow.GROUP, targetId = "g"), all, empty)
        assertEquals(all, scope.monitors)
        assertTrue(scope.fellBack)
    }

    @Test
    fun aDeletedMonitorFallsBackToEverything() {
        val scope = LiveScope.resolve(LiveCardPrefs(follows = LiveFollow.MONITOR, targetId = "gone"), all, groups)
        assertEquals(all, scope.monitors)
        assertTrue(scope.fellBack)
    }

    // ---- the line follows the scope ----------------------------------------

    @Test
    fun followingAGroupLeavesAnOutageOutsideItOffTheLine() {
        val runtimes = mapOf(
            "site" to runtime(Health.UP, 140, 30L to true, 0L to true),
            "repo" to runtime(Health.UP, 140, 30L to true, 0L to true),
            "nas" to runtime(Health.DOWN, 0, 30L to false, 0L to false),
        )
        val scope = LiveScope.resolve(LiveCardPrefs(follows = LiveFollow.GROUP, targetId = "g"), all, groups)
        val line = LiveTimeline.of(scope.monitors, runtimes, now)!!
        assertTrue(line.bands.none { it.tone == LiveTimeline.Tone.DOWN })
        assertEquals(LiveTimeline.Tone.UP, line.current)

        // And the whole fleet, for contrast, does carry it.
        val fleet = LiveTimeline.of(all, runtimes, now)!!
        assertTrue(fleet.bands.any { it.tone == LiveTimeline.Tone.DOWN })
    }

    // ---- window ------------------------------------------------------------

    @Test
    fun autoIsWhatTheCardAlwaysDid() {
        val runtimes = mapOf("site" to runtime(Health.UP, 140, 90L to true, 0L to true))
        assertEquals(90 * minute, LiveTimeline.of(listOf(site), runtimes, now)!!.spanMs)
        assertEquals(
            LiveTimeline.of(listOf(site), runtimes, now),
            LiveTimeline.of(listOf(site), runtimes, now, window = LiveWindow.AUTO),
        )
    }

    @Test
    fun aChosenWindowIsExactlyThatLongWhateverTheHistory() {
        val runtimes = mapOf("site" to runtime(Health.UP, 140, 90L to true, 0L to true))
        assertEquals(LiveWindow.HOUR.ms, LiveTimeline.of(listOf(site), runtimes, now, window = LiveWindow.HOUR)!!.spanMs)
        assertEquals(LiveWindow.DAY.ms, LiveTimeline.of(listOf(site), runtimes, now, window = LiveWindow.DAY)!!.spanMs)
    }

    @Test
    fun aShortWindowCarriesInTheVerdictFromBeforeIt() {
        // Checked every two hours, last at 50 minutes ago and failing. On a one
        // hour window the only sample inside it is that failure, and the slice
        // before it must not be grey: the monitor was known to be up before.
        val slow = monitor("slow", intervalMinutes = 120)
        val runtimes = mapOf("slow" to runtime(Health.DOWN, 0, 170L to true, 50L to false))
        val line = LiveTimeline.of(listOf(slow), runtimes, now, window = LiveWindow.HOUR)!!
        assertEquals(LiveTimeline.Tone.UP, line.bands.first().tone)
        assertTrue(line.bands.any { it.tone == LiveTimeline.Tone.DOWN })
        assertTrue(line.bands.none { it.tone == LiveTimeline.Tone.UNKNOWN })
    }

    @Test
    fun aChosenWindowWithNothingEverCheckedStillDrawsNothing() {
        assertNull(LiveTimeline.of(listOf(site), mapOf("site" to MonitorRuntime()), now, window = LiveWindow.SIX_HOURS))
    }

    // ---- label -------------------------------------------------------------

    @Test
    fun nextCheckIsTheCountdownItAlwaysWas() {
        val runtimes = mapOf("site" to runtime(Health.UP, 140, 30L to true, 1L to true))
        val line = LiveTimeline.of(listOf(site), runtimes, now)!!
        assertEquals(line.countdownLabel, line.endLabel)
        assertTrue(line.labelSentence.startsWith("Next check"))
    }

    @Test
    fun uptimeCountsPassingChecksInsideTheWindowOnly() {
        // Three in the hour, one failed; the failure two hours ago is outside it.
        val runtimes = mapOf(
            "site" to runtime(Health.UP, 140, 120L to false, 40L to true, 20L to false, 1L to true),
        )
        val line = LiveTimeline.of(listOf(site), runtimes, now, window = LiveWindow.HOUR, label = LiveLabel.UPTIME)!!
        assertEquals(200.0 / 3, line.uptimePercent!!, 0.001)
        assertEquals("66%", line.endLabel)
        assertEquals("Up 66% over the last 1h", line.labelSentence)
    }

    @Test
    fun uptimeNeverRoundsUpToARecordItDoesNotHave() {
        assertEquals("99.9%", LiveTimeline.shortPercent(99.96))
        assertEquals("99.4%", LiveTimeline.shortPercent(99.45))
        assertEquals("100%", LiveTimeline.shortPercent(100.0))
        assertEquals("87%", LiveTimeline.shortPercent(87.9))
    }

    @Test
    fun latencyIsTheSlowestMonitorThatIsAnswering() {
        val runtimes = mapOf(
            "site" to runtime(Health.UP, 180, 1L to true),
            "repo" to runtime(Health.DEGRADED, 1_900, 1L to true),
            // Down, with a timeout's worth of milliseconds. Not a latency.
            "nas" to runtime(Health.DOWN, 15_000, 1L to false),
        )
        val line = LiveTimeline.of(all, runtimes, now, label = LiveLabel.LATENCY)!!
        assertEquals(1_900L, line.slowestMs)
        assertEquals("1.9s", line.endLabel)
        assertEquals("Slowest reply 1.9 s", line.labelSentence)
    }

    @Test
    fun latencyWithNobodyAnsweringSaysSoInsteadOfADash() {
        val runtimes = mapOf("nas" to runtime(Health.DOWN, 0, 1L to false))
        val line = LiveTimeline.of(listOf(nas), runtimes, now, label = LiveLabel.LATENCY)!!
        assertEquals("n/a", line.endLabel)
        assertEquals("No reply to time yet", line.labelSentence)
    }

    // ---- the words, shared by the notice and the preview --------------------

    private val mixed = mapOf(
        "site" to runtime(Health.UP, 180, 30L to true, 1L to true),
        "repo" to runtime(Health.UP, 240, 30L to true, 1L to true),
        "nas" to runtime(Health.DOWN, 0, 30L to true, 1L to false),
    )

    @Test
    fun everythingIsTitledWithTheVerdictAloneSoTheCountdownHasRoom() {
        val notice = LiveNotice.of(all, mixed, groups, LiveCardPrefs(), now, offline = false, lineSupported = true)
        assertEquals("1 of 3 is down", notice.title)
        assertNotNull(notice.line)
    }

    @Test
    fun aFollowedGroupTitlesTheNoticeAndStillReportsTheOutageOutsideIt() {
        val prefs = LiveCardPrefs(follows = LiveFollow.GROUP, targetId = "g")
        val notice = LiveNotice.of(all, mixed, groups, prefs, now, offline = false, lineSupported = true)
        assertEquals("Nightbell · All 2 operational", notice.title)
        assertTrue(notice.body, notice.body.endsWith("Elsewhere: 1 more is down."))
    }

    @Test
    fun aFollowedMonitorIsTitledWithItsOwnState() {
        val prefs = LiveCardPrefs(follows = LiveFollow.MONITOR, targetId = "nas")
        val notice = LiveNotice.of(all, mixed, groups, prefs, now, offline = false, lineSupported = true)
        assertEquals("nas · Down", notice.title)
        // Only itself, so no count of one monitor and no elsewhere line.
        assertFalse(notice.body, notice.body.contains("monitors ·"))
        assertFalse(notice.body, notice.body.contains("Elsewhere"))
    }

    @Test
    fun theLineIsOnlyHandedOverWhereItCanBeDrawn() {
        val prefs = LiveCardPrefs(window = LiveWindow.HOUR, label = LiveLabel.UPTIME)
        val drawn = LiveNotice.of(all, mixed, groups, prefs, now, offline = false, lineSupported = true)
        assertTrue(drawn.body, drawn.body.contains("last 1h · 83% up"))
        val plain = LiveNotice.of(all, mixed, groups, prefs, now, offline = false, lineSupported = false)
        assertNull(plain.line)
        // Below Android 16 the chosen figure still reaches the words.
        assertTrue(plain.body, plain.body.contains("Up 83% over the last 1h."))
    }

    @Test
    fun offlineWinsOverEveryChoice() {
        val prefs = LiveCardPrefs(follows = LiveFollow.GROUP, targetId = "g")
        val notice = LiveNotice.of(all, mixed, groups, prefs, now, offline = true, lineSupported = true)
        assertEquals("Monitoring paused · offline", notice.title)
    }

    // ---- stored ------------------------------------------------------------

    @Test
    fun settingsWrittenBeforeTheLiveCardChoicesStillReadAndGetTheOldBehaviour() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val old = """{"strictForegroundMonitoring":true}"""
        val settings = json.decodeFromString(GlobalSettings.serializer(), old)
        assertEquals(LiveCardPrefs(), settings.liveCard)
        assertEquals(LiveWindow.AUTO, settings.liveCard.window)
        assertEquals(LiveLabel.NEXT_CHECK, settings.liveCard.label)
        assertNotNull(settings)
    }

    @Test
    fun theChoicesSurviveARoundTrip() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val prefs = LiveCardPrefs(LiveFollow.GROUP, "g", LiveWindow.SIX_HOURS, LiveLabel.UPTIME)
        val back = json.decodeFromString(
            GlobalSettings.serializer(),
            json.encodeToString(GlobalSettings.serializer(), GlobalSettings(liveCard = prefs)),
        )
        assertEquals(prefs, back.liveCard)
    }

    @Test
    fun aChipChoiceSavedByAnEarlierBuildIsIgnored() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val saved = """{"liveCard":{"follows":"GROUP","targetId":"g","chip":false}}"""
        val settings = json.decodeFromString(GlobalSettings.serializer(), saved)
        assertEquals(LiveCardPrefs(follows = LiveFollow.GROUP, targetId = "g"), settings.liveCard)
    }
}
