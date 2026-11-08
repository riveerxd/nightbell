package me.river.nightbell

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.runBlocking
import me.river.nightbell.NightbellTestSupport.appContext
import me.river.nightbell.NightbellTestSupport.awaitTrue
import me.river.nightbell.NightbellTestSupport.captureScreenshot
import me.river.nightbell.NightbellTestSupport.openSettingsTab
import me.river.nightbell.data.Nightbell
import me.river.nightbell.data.NightbellSnapshot
import me.river.nightbell.data.alerts.AlertCenter
import me.river.nightbell.data.alerts.LiveCard
import me.river.nightbell.data.work.NightbellMonitorService
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.Health
import me.river.nightbell.domain.LiveCardPrefs
import me.river.nightbell.domain.LiveFollow
import me.river.nightbell.domain.LiveLabel
import me.river.nightbell.domain.LiveWindow
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorGroup
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.MonitorRuntime
import me.river.nightbell.domain.PauseScope
import me.river.nightbell.domain.PauseState
import me.river.nightbell.domain.Sample
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The live card's choices, tapped in Settings and read back off the real
 * strict monitoring notification.
 *
 * The feature's promise is that a choice is on the notification straight away,
 * so every assertion here is against what the system is actually showing, not
 * against the store. A store-level test would pass with the service never told.
 */
@RunWith(AndroidJUnit4::class)
class LiveCardSettingsInstrumentedTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @get:Rule
    val permissions: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private var scenario: ActivityScenario<MainActivity>? = null
    private val manager = appContext.getSystemService(NotificationManager::class.java)
    private val now = System.currentTimeMillis()

    @After
    fun tearDown() {
        scenario?.close()
        // No stopService, for the reason UrgentPageEndToEndTest gives: clearing the
        // store is enough, the loop stands down on its next tick.
        runBlocking { NightbellTestSupport.resetApp() }
        manager.cancelAll()
    }

    private fun seed() {
        val monitors = listOf("site" to "Nightbell website", "repo" to "Nightbell repository", "nas" to "NAS")
            .map { (id, name) ->
                Monitor(
                    id = id,
                    name = name,
                    kind = MonitorKind.HTTP_STATUS,
                    // Port 1 with nothing on it: if a check does run, it fails in
                    // milliseconds instead of reaching the internet.
                    url = "http://127.0.0.1:1/$id",
                    intervalMinutes = 60,
                    createdAt = now,
                )
            }
        // Checked a minute ago, an hour apart, so nothing falls due while the test runs.
        fun runtime(ok: Boolean, latency: Long) = MonitorRuntime(
            health = if (ok) Health.UP else Health.DOWN,
            lastCheckedAt = now - 60_000,
            lastLatencyMs = latency,
            samples = listOf(
                Sample(at = now - 30 * 60_000, ok = true, latencyMs = latency),
                Sample(at = now - 60_000, ok = ok, latencyMs = latency),
            ),
        )
        runBlocking {
            Nightbell.install(appContext).store.replaceAll(
                NightbellSnapshot(
                    monitors = monitors,
                    runtimes = mapOf(
                        "site" to runtime(true, 180),
                        "repo" to runtime(true, 240),
                        "nas" to runtime(false, 0),
                    ),
                    groups = listOf(MonitorGroup(id = "g", title = "Nightbell", memberIds = listOf("site", "repo"))),
                    settings = GlobalSettings(
                        motionIntensity = 0f,
                        hasSeenPagerSetup = true,
                        pagerSetupSilenced = true,
                        strictForegroundMonitoring = true,
                    ),
                ),
            )
        }
        NightbellMonitorService.sync(appContext)
    }

    private fun notice(): Notification? =
        manager.activeNotifications.firstOrNull { it.id == AlertCenter.SERVICE_NOTIFICATION_ID }?.notification

    /** The posted title without the gap One UI needs before the countdown. */
    private fun title(): String = rawTitle().trimEnd()

    private fun rawTitle(): String = notice()?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()

    private fun text(): String = notice()?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()

    private fun awaitTitle(expected: String) =
        awaitTrue(description = "the notice is titled \"$expected\", it is \"${title()}\"") { title() == expected }

    private fun inCard(text: String): SemanticsMatcher = hasText(text) and hasAnyAncestor(hasTestTag("live-card"))

    private fun tap(text: String) {
        composeRule.onNodeWithTag("settings-list").performScrollToNode(inCard(text))
        composeRule.onNode(inCard(text)).performClick()
        composeRule.waitForIdle()
    }

    private fun openLiveCard() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Settings").performClick()
        composeRule.waitForIdle()
        composeRule.openSettingsTab("Checks")
        composeRule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("live-card"))
        composeRule.waitForIdle()
    }

    private companion object {
        // Platform extras keys, spelled out so the test compiles against any SDK.
        const val SHORT_CRITICAL_TEXT = "android.shortCriticalText"
        const val REQUEST_PROMOTED = "android.requestPromotedOngoing"
    }

    private fun storedPrefs() = runBlocking { Nightbell.require().store.currentSnapshot().settings.liveCard }

    @Test
    fun followingAGroupRetitlesTheNoticeAndStillMentionsTheOutageOutsideIt() {
        seed()
        openLiveCard()
        awaitTitle("1 of 3 is down")
        composeRule.captureScreenshot("live-card-01-everything")
        composeRule.onNode(hasTestTag("live-card-preview") and hasText("1 of 3 is down"))
            .assertExists()

        tap("One group")
        // The first group is picked on the same tap, so the card changes at once
        // rather than waiting for a second choice.
        assertEquals(LiveFollow.GROUP, storedPrefs().follows)
        assertEquals("g", storedPrefs().targetId)
        awaitTitle("Nightbell · All 2 operational")
        // With the line drawn the platform puts a countdown after the title, and
        // One UI butts it against the last letter. The gap has to be in the text.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            assertTrue("no gap before the countdown in \"${rawTitle()}\"", rawTitle().endsWith("\u2002"))
        }
        // The preview in Settings says the same thing as the shade, on the same tap.
        composeRule.onNode(hasTestTag("live-card-preview") and hasText("Nightbell · All 2 operational"))
            .assertExists()
        awaitTrue(description = "the NAS outage is still mentioned, text is \"${text()}\"") {
            text().contains("Elsewhere: 1 more is down.")
        }
        composeRule.captureScreenshot("live-card-02-one-group")
    }

    /**
     * The bug this feature exposed. `sync` re-delivers `onStartCommand` to a
     * service that is already running, and that used to post the "Starting…"
     * placeholder over the real notice while the loop slept on, so a tap in
     * Settings could leave the card saying "Starting…" for up to a minute.
     */
    @Test
    fun syncingARunningServiceDoesNotPutTheNoticeBackToStarting() {
        seed()
        awaitTitle("1 of 3 is down")

        NightbellMonitorService.sync(appContext)
        // Long enough for the start command to land; far shorter than a tick.
        Thread.sleep(3_000)
        assertEquals("1 of 3 is down", title())
    }

    /**
     * Every combination of the three choices, against a day and a half of
     * history with outages in it, each one read back off the posted notice.
     *
     * Reported from a phone: strict on, everything, 24 h, uptime, and no notice in
     * the shade at all. The service swallows a failed post, so a choice that made
     * the notification unbuildable would look exactly like that.
     */
    @Test
    fun everyCombinationIsPosted() {
        seed()
        // A day and a half of checks every fifteen minutes, with two outages.
        runBlocking {
            listOf("site", "repo", "nas").forEach { id ->
                Nightbell.require().store.updateRuntime(id) { runtime ->
                    val samples = (0 until 144).map { i ->
                        val at = now - (143 - i) * 15 * 60_000L
                        val ok = !(id == "nas" && i in 40..47) && !(id == "site" && i in 120..121) && !(id == "nas" && i == 143)
                        Sample(at = at, ok = ok, latencyMs = if (ok) 150L + i else 0L)
                    }
                    runtime.copy(samples = samples, lastCheckedAt = samples.last().at)
                }
            }
        }
        val combos = LiveFollow.entries.flatMap { follows ->
            LiveWindow.entries.flatMap { window -> LiveLabel.entries.map { label -> Triple(follows, window, label) } }
        }
        combos.forEach { (follows, window, label) ->
            val target = when (follows) {
                LiveFollow.EVERYTHING -> ""
                LiveFollow.GROUP -> "g"
                LiveFollow.MONITOR -> "nas"
            }
            runBlocking {
                Nightbell.require().store.updateSettings {
                    it.copy(liveCard = LiveCardPrefs(follows, target, window, label))
                }
            }
            Nightbell.require().notifyStateChanged()
            val expected = when (follows) {
                LiveFollow.EVERYTHING -> "1 of 3 is down"
                LiveFollow.GROUP -> "Nightbell · All 2 operational"
                LiveFollow.MONITOR -> "NAS · Down"
            }
            awaitTrue(description = "$follows $window $label posted, title is \"${title()}\"") {
                title() == expected
            }
        }
    }

    /**
     * The phone this was reported from had strict mode on and no notification,
     * because a fleet pause set a month earlier was still stopping checks, and with
     * them strict mode. Settings went on saying a notification was showing.
     */
    @Test
    fun aPauseThatStopsChecksIsSaidAndCanBeLiftedFromHere() {
        seed()
        runBlocking {
            Nightbell.require().store.setPause(
                PauseState(indefinite = true, scope = PauseScope.STOP_CHECKS, since = now - 86_400_000L),
            )
        }
        Nightbell.require().notifyStateChanged()
        openLiveCard()
        composeRule.onNode(
            hasText("Checks are paused", substring = true) and hasAnyAncestor(hasTestTag("live-card")),
        ).assertExists()
        awaitTrue(description = "no notice while paused") { notice() == null }
        composeRule.captureScreenshot("live-card-05-paused")

        tap("Resume checks")
        awaitTrue(description = "the pause is lifted") {
            runBlocking { !Nightbell.require().store.currentSnapshot().pause.isActive(System.currentTimeMillis()) }
        }
        awaitTitle("1 of 3 is down")
    }

    /**
     * Every choice still asks for the chip, the way the card did before there were
     * choices, and none of them brings back the Samsung key that relabelled the
     * whole card "Gemini Intelligence".
     */
    @Test
    fun theCardAlwaysAsksForTheChipAndNeverAsGemini() {
        assumeTrue("the line and its chip are Android 16 and up", Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA)
        seed()
        openLiveCard()
        awaitTitle("1 of 3 is down")
        tap("Uptime")
        awaitTrue(description = "the chip text and the promotion request are set") {
            val extras = notice()?.extras
            extras != null &&
                extras.getCharSequence(SHORT_CRITICAL_TEXT) != null &&
                extras.getBoolean(REQUEST_PROMOTED, false)
        }
        assertEquals(false, notice()?.extras?.getBoolean(LiveCard.SAMSUNG_AUTOMATION, false))
        assertTrue(composeRule.onAllNodesWithText("Status bar chip").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun followingOneMonitorNamesIt() {
        seed()
        openLiveCard()
        tap("One monitor")
        tap("NAS")
        assertEquals(LiveFollow.MONITOR, storedPrefs().follows)
        assertEquals("nas", storedPrefs().targetId)
        awaitTitle("NAS · Down")
        composeRule.captureScreenshot("live-card-03-one-monitor")
    }

    @Test
    fun theLabelAndWindowReachTheNoticeText() {
        seed()
        openLiveCard()
        tap("1 h")
        tap("Uptime")
        assertEquals(LiveWindow.HOUR, storedPrefs().window)
        assertEquals(LiveLabel.UPTIME, storedPrefs().label)
        // Six checks in the hour, one failed. Worded as a phrase beside the line
        // on Android 16 and as a sentence in the plain notice below it.
        awaitTrue(description = "uptime over the hour is in the text, it is \"${text()}\"") {
            text().contains("last 1h · 83% up") || text().contains("Up 83% over the last 1h")
        }

        tap("Latency")
        awaitTrue(description = "latency is in the text, it is \"${text()}\"") {
            text().contains("slowest 240 ms") || text().contains("Slowest reply 240 ms")
        }
        composeRule.captureScreenshot("live-card-04-window-and-label")
    }
}
