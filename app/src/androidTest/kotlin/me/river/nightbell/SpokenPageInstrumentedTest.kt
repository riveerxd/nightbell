package me.river.nightbell

import android.Manifest
import android.media.AudioAttributes
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import me.river.nightbell.NightbellTestSupport.appContext
import me.river.nightbell.NightbellTestSupport.captureScreenshot
import me.river.nightbell.NightbellTestSupport.resetApp
import me.river.nightbell.data.Nightbell
import me.river.nightbell.data.alerts.PageSpeaker
import me.river.nightbell.data.alerts.UrgentAlarm
import me.river.nightbell.domain.AlertPolicy
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.SpokenPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import android.util.Log
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spoken pages, driven through the real UI and the real speech engine.
 *
 * The JVM suite already proves the sentence is built correctly. What can only be
 * proven on a device is the part that broke every previous audio feature in this
 * app: whether an engine is there at all, whether it will answer, and whether the
 * siren comes back afterwards. A page left permanently muted because an
 * announcement threw would be a worse bug than never having spoken.
 *
 * The engine tests are honest about a device that has no engine: they assert the
 * readiness verdict is one Nightbell can explain in Settings, and they say which
 * one it was in logcat, rather than passing on a phone where nothing was said.
 */
@RunWith(AndroidJUnit4::class)
class SpokenPageInstrumentedTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @get:Rule
    val permissions: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun tearDown() {
        scenario?.close()
    }

    private fun openAlerts() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Settings").performClick()
        composeRule.waitForIdle()
    }

    private fun scrollToCard() {
        composeRule.onNodeWithTag("settings-list")
            .performScrollToNode(hasContentDescription("Spoken alerts"))
        composeRule.waitForIdle()
    }

    /**
     * Brings one control of the card into view.
     *
     * Scrolling to the card's own header is not enough: turning the toggle on adds
     * a template field, a second toggle and a button underneath it, and the list
     * leaves all of that below the fold. A test that only scrolled to the header
     * failed on a control that was composed and perfectly reachable, which is a
     * test bug wearing a product bug's clothes.
     */
    private fun scrollTo(tag: String) {
        composeRule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag(tag))
        composeRule.waitForIdle()
    }

    /**
     * Whether this device can actually produce audio, as opposed to claiming it.
     *
     * The emulator's Google engine reports READY with an installed offline en-US
     * voice and then fails every utterance with `ERROR_SERVICE`, because the voice
     * data was never downloaded. A test that asserted through that would be
     * asserting the engine's marketing. The probe is the same one Settings runs.
     */
    private fun canSpeak(): Boolean {
        val speaker = Nightbell.install(appContext).speaker
        val verdict = runBlocking { withTimeout(30_000) { speaker.readiness(probe = true) } }
        if (verdict != PageSpeaker.Readiness.READY) {
            Log.w(TAG, "Skipping: this device cannot speak, verdict is $verdict")
            return false
        }
        return true
    }

    private fun storedSettings(): GlobalSettings =
        runBlocking { Nightbell.install(appContext).store.currentSnapshot().settings }

    // ---- the UI --------------------------------------------------------------

    private fun seedMonitors(speak: Boolean = false, custom: Boolean = false) {
        resetApp(
            GlobalSettings(
                motionIntensity = 0f,
                hasSeenPagerSetup = true,
                defaultAlert = AlertPolicy(speak = speak),
            ),
        )
        runBlocking {
            val store = Nightbell.install(appContext).store
            listOf("Checkout API", "Vault", "Wireguard gateway").forEachIndexed { index, name ->
                store.upsert(
                    Monitor(
                        id = "m$index",
                        name = name,
                        url = "https://example.com/$index",
                        // One monitor deliberately off the global policy: the fleet
                        // button has to reach that one too, and originally would not
                        // have.
                        useGlobalAlerts = !(custom && index == 1),
                        alert = AlertPolicy(speak = speak),
                    ),
                )
            }
        }
    }

    /**
     * The card says where the fleet stands rather than offering a switch of its
     * own.
     *
     * This is the whole reason the card was rebuilt: a global "say pages out loud"
     * toggle read as "make the app talk", and then said nothing, because whether
     * anything speaks is a per-monitor decision and the monitor the user had just
     * made was not urgent.
     */
    @Test
    fun theCardSaysHowManyMonitorsSpeak() {
        seedMonitors(speak = false)
        openAlerts()
        scrollToCard()
        composeRule.onNodeWithContentDescription("Spoken alerts").assertIsDisplayed()
        composeRule.onNodeWithTag("speak-count").assertIsDisplayed()
        composeRule.onNodeWithText(
            "No monitor speaks yet. Each one has its own switch under its " +
                "alert settings, or turn them all on here.",
        ).assertIsDisplayed()
        composeRule.captureScreenshot("speech-01-none")
    }

    @Test
    fun oneButtonTurnsItOnForEveryMonitorIncludingTheCustomOne() {
        seedMonitors(speak = false, custom = true)
        openAlerts()
        scrollTo("speak-all-on")
        composeRule.onNodeWithTag("speak-all-on").performClick()
        NightbellTestSupport.awaitTrue(description = "every monitor to speak") {
            val snap = runBlocking { Nightbell.install(appContext).store.currentSnapshot() }
            SpokenPage.speakingCount(snap.monitors, snap.settings) == snap.monitors.size &&
                snap.monitors.isNotEmpty()
        }
        val snap = runBlocking { Nightbell.install(appContext).store.currentSnapshot() }
        assertTrue("the default policy was skipped", snap.settings.defaultAlert.speak)
        assertTrue(
            "a monitor on its own policy was skipped",
            snap.monitors.filter { !it.useGlobalAlerts }.all { it.alert.speak },
        )
        composeRule.onNodeWithTag("settings-list")
            .performScrollToNode(hasTestTag("speak-count"))
        composeRule.onNodeWithText("All 3 monitors speak.").assertIsDisplayed()
        composeRule.captureScreenshot("speech-02-all-on")
    }

    @Test
    fun theOtherButtonSilencesEveryMonitor() {
        seedMonitors(speak = true, custom = true)
        openAlerts()
        scrollTo("speak-all-off")
        composeRule.onNodeWithTag("speak-all-off").performClick()
        NightbellTestSupport.awaitTrue(description = "no monitor to speak") {
            val snap = runBlocking { Nightbell.install(appContext).store.currentSnapshot() }
            SpokenPage.speakingCount(snap.monitors, snap.settings) == 0
        }
    }

    /** A template typed in the field is what the alert will actually say. */
    @Test
    fun aTypedTemplateIsWhatGetsSpoken() {
        seedMonitors(speak = true)
        openAlerts()
        scrollTo("speak-template")
        // Typed through the field's own semantics rather than the card's test tag:
        // GlassField puts the tag on the labelled column and the text semantics on
        // the editor inside it, so only one of the two can take focus.
        composeRule.onNodeWithContentDescription("What it says")
            .performTextReplacement("{name} fell over. {reason}.")
        composeRule.waitForIdle()
        NightbellTestSupport.awaitTrue(description = "the template to persist") {
            storedSettings().speakTemplate == "{name} fell over. {reason}."
        }
        assertEquals(
            "Checkout API fell over. Timed out.",
            SpokenPage.render(
                template = storedSettings().speakTemplate,
                name = "Checkout API",
                reason = "Timed out",
                downForMs = 90_000L,
            ),
        )
        composeRule.captureScreenshot("speech-03-template")
    }

    /**
     * Typing survives.
     *
     * The field was bound straight to the store, so every keystroke wrote the
     * whole snapshot to DataStore and the value only came back when that write
     * landed, cursor thrown to the end: characters were dropped and reordered, and
     * it was reported as "I cannot type into it". The same mistake, with the same
     * fix, as the proxy fields under Checks, whose comment says so. Typed a
     * character at a time here rather than replaced in one go, because a single
     * `performTextReplacement` is exactly the shape of test that missed it.
     */
    @Test
    fun theSentenceCanBeTypedOneCharacterAtATime() {
        seedMonitors(speak = true)
        openAlerts()
        scrollTo("speak-template")
        val field = composeRule.onNodeWithContentDescription("What it says")
        field.performTextClearance()
        "Wake up {name}".forEach { character ->
            field.performTextInput(character.toString())
        }
        composeRule.waitForIdle()
        field.assertTextContains("Wake up {name}")
        NightbellTestSupport.awaitTrue(description = "the sentence to persist verbatim") {
            storedSettings().speakTemplate == "Wake up {name}"
        }
        composeRule.captureScreenshot("speech-05-typed")
    }

    /**
     * The placeholders are on screen whatever the field holds.
     *
     * Reported as "the placeholders should be displayed all the time": they were a
     * line of hint text under the field, which is documentation, not a control.
     */
    @Test
    fun thePlaceholdersAreAlwaysOnScreenAndAddThemselves() {
        seedMonitors(speak = true)
        openAlerts()
        scrollTo("speak-tokens")
        SpokenPage.Token.all.forEach { token ->
            composeRule.onNodeWithText(token.token).assertIsDisplayed()
        }
        composeRule.onNodeWithContentDescription("Add How long it has been down").performClick()
        NightbellTestSupport.awaitTrue(description = "the placeholder to be added") {
            storedSettings().speakTemplate.contains("{duration}")
        }
        // Still there with the field full, and a second tap is a mis-tap.
        scrollTo("speak-tokens")
        composeRule.onNodeWithText("{duration}").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Add How long it has been down").performClick()
        composeRule.waitForIdle()
        assertEquals(
            1,
            Regex("\\{duration\\}").findAll(storedSettings().speakTemplate).count(),
        )
        composeRule.captureScreenshot("speech-06-tokens")
    }

    /**
     * A voice that cannot speak the sentence's language says so on screen.
     *
     * Reported after picking Vietnamese and hearing English words read with
     * Vietnamese pronunciation. That is what a synthesiser does, and the picker
     * was presented as if it changed the language.
     *
     * Driven through the store rather than by tapping a chip, because the warning
     * is about the voice that will be used, which on a device offering one
     * language cannot be chosen at all. The stored tag is what a device with more
     * languages would have written, so this asserts the same state either way.
     */
    @Test
    fun aVoiceThatCannotSpeakTheSentenceIsFlagged() {
        seedMonitors(speak = true)
        runBlocking {
            Nightbell.install(appContext).store.updateSettings { it.copy(speakVoice = "vi-VN") }
        }
        openAlerts()
        scrollToCard()
        val effective = runBlocking {
            Nightbell.install(appContext).speaker.effectiveVoiceTag("vi-VN")
        }
        if (effective == null || effective.startsWith("en", ignoreCase = true)) {
            // Either no engine, or the stored Vietnamese voice is not installed and
            // the English fallback took over, which is the correct outcome and not
            // something to warn about. The rule itself is asserted in the JVM suite.
            Log.w(TAG, "No mismatch to show: the voice in use is $effective")
            assertTrue(SpokenPage.voiceMismatch(template = "", voiceTag = "vi-VN"))
            return
        }
        composeRule.onNodeWithTag("settings-list")
            .performScrollToNode(hasTestTag("speak-voice-warning"))
        composeRule.onNodeWithTag("speak-voice-warning").assertIsDisplayed()
        composeRule.captureScreenshot("speech-07-voice-mismatch")
    }

    /** The per-monitor switch is in the policy editor, where the rest of them are. */
    @Test
    fun theSwitchItselfLivesWithTheMonitorsOtherAlertSettings() {
        seedMonitors(speak = false)
        openAlerts()
        scrollTo("policy-speak")
        composeRule.onNodeWithTag("policy-speak").assertIsDisplayed()
        composeRule.onNodeWithText("Say it out loud").assertIsDisplayed()
        composeRule.onNodeWithTag("policy-speak").performClick()
        NightbellTestSupport.awaitTrue(description = "the default policy to speak") {
            storedSettings().defaultAlert.speak
        }
        composeRule.captureScreenshot("speech-04-policy-row")
    }

    /**
     * Every placeholder is reachable by a thumb.
     *
     * Hand-rolled, the chip came out 35 dp tall against the app's own
     * [MinTouchTarget] of 48, sitting one row under a set of real chips that are
     * 38 dp inside 48 dp of padding. Measured rather than eyeballed: the whole
     * point of the constant is that nothing in the app is allowed to be smaller
     * than a thumb, and a screenshot cannot tell you whether it is.
     */
    @Test
    fun everyPlaceholderIsAThumbWide() {
        seedMonitors(speak = true)
        openAlerts()
        scrollTo("speak-tokens")
        SpokenPage.Token.all.forEach { token ->
            composeRule.onNodeWithText(token.token)
                .assertHeightIsAtLeast(TOKEN_CHIP_HEIGHT)
                .assertWidthIsAtLeast(TOKEN_CHIP_MIN_WIDTH)
        }
    }

    /**
     * Flattening the fleet can be taken back.
     *
     * The lossy direction is off: a fleet with speech on for the two monitors
     * that matter cannot be restored by pressing "Turn on for all", which turns on
     * all thirty. So the previous per-monitor answers are captured and the toast
     * carries the way back, which is what the rest of the app does instead of
     * asking "are you sure?".
     */
    @Test
    fun turningItOffForAllCanBeUndone() {
        seedMonitors(speak = false, custom = true)
        // A deliberate mix: the custom-policy monitor speaks, the ones on the
        // global default do not. This is exactly the state a fleet button destroys.
        runBlocking {
            val store = Nightbell.install(appContext).store
            val custom = store.currentSnapshot().monitors.first { !it.useGlobalAlerts }
            store.upsert(custom.copy(alert = custom.alert.copy(speak = true)))
        }
        openAlerts()
        scrollTo("speak-all-off")
        composeRule.onNodeWithTag("speak-all-off").performClick()
        NightbellTestSupport.awaitTrue(description = "the fleet to go quiet") {
            val snap = runBlocking { Nightbell.install(appContext).store.currentSnapshot() }
            SpokenPage.speakingCount(snap.monitors, snap.settings) == 0
        }
        composeRule.onNodeWithText("Undo").performClick()
        NightbellTestSupport.awaitTrue(description = "the mix to come back") {
            val snap = runBlocking { Nightbell.install(appContext).store.currentSnapshot() }
            snap.monitors.filter { !it.useGlobalAlerts }.all { it.alert.speak } &&
                !snap.settings.defaultAlert.speak
        }
        val snap = runBlocking { Nightbell.install(appContext).store.currentSnapshot() }
        assertEquals(
            "undo turned everything on instead of putting it back",
            1,
            SpokenPage.speakingCount(snap.monitors, snap.settings),
        )
    }

    // ---- the engine ----------------------------------------------------------

    /**
     * Whatever this device can do, Nightbell has copy for it.
     *
     * The three verdicts are the three warnings the card can show, so a device
     * with no engine and a device with only network voices both end up telling the
     * user something true instead of going quiet.
     */
    @Test
    fun theEngineReportsSomethingTheCardCanExplain() {
        val speaker = PageSpeaker(appContext)
        try {
            val readiness = runBlocking { withTimeout(20_000) { speaker.readiness() } }
            Log.i(TAG, "Speech readiness on this device: $readiness")
            assertTrue(readiness in PageSpeaker.Readiness.entries)
            if (readiness == PageSpeaker.Readiness.READY) {
                val voices = runBlocking { speaker.offlineVoices() }
                Log.i(TAG, "Offline voices offered: ${voices.map { it.tag }}")
                assertTrue("ready with no voice to offer", voices.isNotEmpty())
            }
        } finally {
            speaker.release()
        }
    }

    /**
     * A real sentence, out loud, and the call comes back.
     *
     * The assertion worth having is not that audio was produced, which nothing in
     * a test can hear, but that [PageSpeaker.say] resolves: the whole design hangs
     * on the utterance listener firing, and an engine that never reports
     * completion is the failure that would hold the siren muted.
     */
    @Test
    fun anAnnouncementIsSpokenAndReturns() {
        val speaker = PageSpeaker(appContext)
        try {
            val readiness = runBlocking { withTimeout(30_000) { speaker.readiness(probe = true) } }
            if (readiness != PageSpeaker.Readiness.READY) {
                Log.w(TAG, "Skipping the spoken assertion: engine is $readiness")
                return
            }
            val text = SpokenPage.render(
                name = "Checkout API",
                reason = "Host not found",
                downForMs = 4 * 60_000L,
            )
            val started = System.currentTimeMillis()
            val spoken = runBlocking {
                withTimeout(30_000) {
                    speaker.say(text, AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                }
            }
            val took = System.currentTimeMillis() - started
            Log.i(TAG, "Said \"$text\" in ${took}ms, engine reported $spoken")
            assertTrue("the engine never finished the utterance", spoken)
            // Long enough to have actually been read, short enough to prove it did
            // not sit in the timeout. The sentence is roughly five seconds of speech.
            assertTrue("suspiciously instant: ${took}ms", took in 500..25_000)
        } finally {
            speaker.release()
        }
    }

    /**
     * The siren survives the sentence.
     *
     * This is the regression that matters. Speech mutes the page's own player and
     * restores it in a `finally`, so the failure to guard against is an
     * announcement that leaves the pager silent, or one that tears the player down
     * and never rebuilds it.
     */
    @Test
    fun theSirenIsStillPlayingAfterAnAnnouncement() {
        val alarm = UrgentAlarm(appContext)
        val speaker = PageSpeaker(appContext, alarm)
        try {
            val readiness = runBlocking { withTimeout(30_000) { speaker.readiness(probe = true) } }
            if (readiness != PageSpeaker.Readiness.READY) {
                Log.w(TAG, "Skipping the duck assertion: engine is $readiness")
                return
            }
            alarm.start(
                style = me.river.nightbell.domain.VibrationStyle.TICK,
                vibrate = false,
                respectRinger = false,
            )
            if (!alarm.isPlaying) {
                // No alarm tone on the image at all: `DEFAULT_ALARM_ALERT_URI`
                // resolves to media that some emulator system images simply do not
                // ship, and there is nothing to duck.
                Log.w(TAG, "Skipping the duck assertion: this device has no alarm tone")
                return
            }
            assertTrue("muted before anything spoke", !alarm.isDucked)
            val spoken = runBlocking {
                // On a real dispatcher, not this `runBlocking` thread: the poll
                // below blocks it, and a coroutine started on the same single
                // thread would not get to run until the poll had already given up.
                val speaking = async(Dispatchers.Default) {
                    speaker.say("Nightbell page. Checkout API is down.", AudioAttributes.USAGE_ALARM)
                }
                // Observed while the sentence is in flight, not inferred from the
                // end state: an announcement that restored the volume correctly
                // but never lowered it would pass every after-the-fact assertion
                // and be inaudible in the one place it matters.
                NightbellTestSupport.awaitTrue(timeoutMs = 10_000, description = "the siren to be muted") {
                    alarm.isDucked
                }
                withTimeout(30_000) { speaking.await() }
            }
            assertTrue("nothing was said", spoken)
            assertTrue("the siren stayed muted", !alarm.isDucked)
            assertTrue("the siren did not come back", alarm.isPlaying)
        } finally {
            alarm.stop()
            speaker.release()
        }
    }

    // ---- the whole path ------------------------------------------------------

    /**
     * The case this feature was rebuilt for: an ordinary monitor, not urgent,
     * created and left to fail.
     *
     * The first version spoke only from the foreground service, which exists only
     * while an URGENT monitor is unacknowledged. So a monitor made in the normal
     * way, with the global switch on, was silent for a reason nothing on screen
     * explained. This drives a real check against port 1, where nothing is
     * listening, and asserts the engine reported having read the monitor's name
     * out loud.
     */
    @Test
    fun anOrdinaryFailingMonitorSaysItOutLoud() {
        val graph = Nightbell.install(appContext)
        if (!graph.alarm.ringerAllowsSound()) {
            Log.w(TAG, "Skipping: this device's ringer would silence the alert")
            return
        }
        // Not gated on the engine working. The wiring is the thing that was broken
        // and is worth asserting everywhere: whether the app asks the engine to
        // speak when an ordinary monitor fails. Whether audio came out is asserted
        // on top of that, only where a device can actually produce it.
        val audible = canSpeak()
        resetApp(
            GlobalSettings(
                motionIntensity = 0f,
                hasSeenPagerSetup = true,
                defaultAlert = AlertPolicy(speak = true, failureThreshold = 1),
            ),
        )
        val before = graph.speaker.spokenCount
        val asked = graph.speaker.requestCount
        runBlocking {
            graph.store.upsert(
                Monitor(
                    id = "speak-plain",
                    name = "Checkout API",
                    url = "http://127.0.0.1:1/health",
                    timeoutSeconds = 5,
                ),
            )
            graph.engine.run("speak-plain")
        }
        NightbellTestSupport.awaitTrue(timeoutMs = 40_000, description = "the engine to be asked") {
            graph.speaker.requestCount > asked
        }
        val requested = graph.speaker.lastRequested
        Log.i(TAG, "Handed to the engine: $requested")
        assertTrue("the engine was never asked", requested != null)
        assertTrue("the monitor was not named: $requested", requested!!.contains("Checkout API"))
        if (audible) {
            NightbellTestSupport.awaitTrue(timeoutMs = 40_000, description = "the alert to be spoken") {
                graph.speaker.spokenCount > before
            }
            Log.i(TAG, "Said out loud: ${graph.speaker.lastSpoken}")
            assertEquals(requested, graph.speaker.lastSpoken)
        } else {
            Log.w(TAG, "Audio not asserted: this device cannot synthesise")
        }
        graph.speaker.release()
    }

    /**
     * A monitor that does not speak stays silent, which is the other half of the
     * switch meaning anything.
     */
    @Test
    fun aMonitorWithTheSwitchOffStaysSilent() {
        val graph = Nightbell.install(appContext)
        resetApp(
            GlobalSettings(
                motionIntensity = 0f,
                hasSeenPagerSetup = true,
                defaultAlert = AlertPolicy(speak = false, failureThreshold = 1),
            ),
        )
        val before = graph.speaker.requestCount
        runBlocking {
            graph.store.upsert(
                Monitor(
                    id = "speak-off",
                    name = "Vault",
                    url = "http://127.0.0.1:1/health",
                    timeoutSeconds = 5,
                ),
            )
            graph.engine.run("speak-off")
        }
        // Long enough that a synthesiser starting up would have been asked.
        Thread.sleep(6_000)
        assertEquals("the engine was asked anyway", before, graph.speaker.requestCount)
    }

    /**
     * A real outage, spoken by the real service.
     *
     * Everything above can pass while the shipping path says nothing, which is the
     * mistake the notification harnesses in this suite were written to stop
     * repeating. So this one takes a monitor that genuinely fails, folds it through
     * the real engine, lets the real foreground service page, and watches the siren
     * mute and come back: nothing but [PageSpeaker.say] can duck that player, so a
     * duck observed here is the announcement having actually been made from the
     * loop that ships.
     *
     * Port 1 with nothing listening on it: connection refused, no fixture server,
     * real failure.
     */
    @Test
    fun aRealOutageIsSpokenByTheService() {
        val graph = Nightbell.install(appContext)
        val ringerAllowsIt = graph.alarm.speechUsage(respectRinger = true) != null
        if (!ringerAllowsIt) {
            Log.w(TAG, "Skipping: this device's ringer would silence a page")
            return
        }
        if (!canSpeak()) return
        resetApp(
            GlobalSettings(
                motionIntensity = 0f,
                hasSeenPagerSetup = true,
                strictForegroundMonitoring = true,
                defaultAlert = AlertPolicy(speak = true, failureThreshold = 1),
            ),
        )
        runBlocking {
            graph.store.upsert(
                Monitor(
                    id = "speech-e2e",
                    name = "Checkout API",
                    url = "http://127.0.0.1:1/health",
                    urgent = true,
                    urgentRepeatMinutes = 1,
                    timeoutSeconds = 5,
                    intervalMinutes = 1,
                ),
            )
            graph.engine.run("speech-e2e")
        }
        try {
            me.river.nightbell.data.work.NightbellMonitorService.sync(appContext)
            // Woken as well as started, or a service already running from an
            // earlier class sits out its sleep before it pages.
            me.river.nightbell.data.work.NightbellMonitorService.wake()
            NightbellTestSupport.awaitTrue(timeoutMs = 25_000, description = "the service to page") {
                me.river.nightbell.data.work.NightbellMonitorService.isPaging()
            }
            NightbellTestSupport.awaitTrue(timeoutMs = 30_000, description = "the page to speak") {
                graph.alarm.isDucked
            }
            NightbellTestSupport.awaitTrue(timeoutMs = 30_000, description = "the siren to come back") {
                !graph.alarm.isDucked
            }
            assertTrue("the siren must still be looping", graph.alarm.isPlaying)
        } finally {
            runBlocking { graph.engine.acknowledgeUrgent("speech-e2e") }
            resetApp()
        }
    }

    private companion object {
        const val TAG = "SpokenPageTest"

        /** The house chip geometry, which the placeholder chips have to match. */
        val TOKEN_CHIP_HEIGHT = 38.dp
        val TOKEN_CHIP_MIN_WIDTH = 64.dp
    }
}
