package me.river.nightbell

import android.Manifest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import me.river.nightbell.NightbellTestSupport.appContext
import me.river.nightbell.NightbellTestSupport.awaitTrue
import me.river.nightbell.NightbellTestSupport.captureScreenshot
import me.river.nightbell.NightbellTestSupport.openSettingsTab
import me.river.nightbell.NightbellTestSupport.resetApp
import me.river.nightbell.data.Nightbell
import me.river.nightbell.data.alerts.AlertCenter
import me.river.nightbell.data.check.CheckEngine
import me.river.nightbell.data.check.ElementChecker
import me.river.nightbell.data.check.HttpChecker
import me.river.nightbell.data.check.UpdateChecker
import me.river.nightbell.domain.AppUpdate
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.UpdateSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The version check that counts, driven on a device.
 *
 * The JVM tests cover the parser and the header. Three things they cannot cover
 * are the three that would make this feature a lie:
 *
 *  1. **The request leaves a real device with the right header on it.** OkHttp on
 *     Android is not OkHttp on a JVM: there is a platform trust store, a
 *     different socket factory and a `User-Agent` that Android will supply for
 *     you if the app does not. A header asserted against a fake client proves
 *     nothing about what a phone sends.
 *  2. **`new` is said once per install and not once per check.** That is the
 *     whole install count. The flag lives in the persisted store, so the only
 *     honest test of it involves a real DataStore surviving a real write.
 *  3. **The switch is reachable and reads correctly.** The source selector now
 *     has three segments instead of two, in a control that clips its labels
 *     rather than wrapping them, so "does the third one fit" is a question only a
 *     screenshot can answer.
 */
@RunWith(AndroidJUnit4::class)
class UpdateCensusInstrumentedTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @get:Rule
    val permissions: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var server: TinyHttpServer

    /** The payload the site actually serves, as gen-version-manifest.mjs writes it. */
    private val manifest = """
        {
          "schema": 1,
          "version": "9.9.9",
          "versionCode": 999,
          "tag": "v9.9.9",
          "url": "https://github.com/riveerxd/nightbell/releases/tag/v9.9.9",
          "notes": "Nightbell 9.9.9",
          "apkUrl": "https://github.com/riveerxd/nightbell/releases/download/v9.9.9/Nightbell-9.9.9-release.apk",
          "apkSize": 2469692,
          "apkSha256": "574530103f1ad85cacba4dba0650a01912e80f68ced431c6ba95c46037aa50e6"
        }
    """.trimIndent()

    @Before
    fun setUp() {
        server = TinyHttpServer {
            TinyHttpServer.Response(body = manifest, contentType = "application/json")
        }
    }

    @After
    fun tearDown() {
        scenario?.close()
        server.close()
    }

    private fun engine(installed: String = "3.9.0") = CheckEngine(
        store = Nightbell.install(appContext).store,
        http = HttpChecker(),
        element = ElementChecker(appContext),
        alerts = Nightbell.install(appContext).alerts,
        updates = UpdateChecker(siteBase = server.baseUrl, installedVersion = { installed }),
        installedVersion = { installed },
    )

    private fun store() = Nightbell.install(appContext).store

    private fun segment(source: UpdateSource) = composeRule.onNode(
        hasText(source.label) and hasAnyAncestor(hasTestTag("update-source")),
        useUnmergedTree = true,
    )

    /**
     * How wide a segment label drew, against how much room it was given.
     *
     * Two dead ends are worth recording, because both are the obvious thing to
     * reach for and both are wrong.
     *
     * Node bounds cannot answer it. The label sits in a `Box` fixed to a third of
     * the track, so `Text` measures against that as its maximum and reports
     * min(intrinsic, third). A label that does not fit reports exactly the same
     * width as one that fits perfectly, and the difference is only in the glyphs
     * the clip threw away.
     *
     * `TextLayoutResult.hasVisualOverflow` cannot answer it either, and that cost
     * a wrong conclusion on the way through here. It reported true for
     * "nightbell.app" laying out 104 px of glyphs inside a 145 px constraint: one
     * line, nothing cut, and the screenshot showing the label whole. Whatever it
     * compares, it is not "did a glyph get clipped", so it condemns a control
     * that is fine and would have had this label shortened for no reason.
     *
     * The line's own extent against the constraint it was measured under is the
     * question, so that is what this returns.
     */
    private fun fit(source: UpdateSource): Pair<Float, Int> {
        val layouts = mutableListOf<TextLayoutResult>()
        segment(source).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.first()
        assertEquals("the \"${source.label}\" label wrapped onto two lines", 1, layout.lineCount)
        return (layout.getLineRight(0) - layout.getLineLeft(0)) to layout.layoutInput.constraints.maxWidth
    }

    // ---- what leaves the device ---------------------------------------------

    /**
     * Makes the next check due without touching anything else.
     *
     * What six hours passing does, in one line. Every test below that wants a
     * second scheduled check uses this rather than `force`, because `force` is a
     * different shape on the wire: it marks the request `tap` so a button press
     * stays out of the cadence arithmetic, which means a suite that forced
     * everything would never once exercise the request the census is actually
     * built on.
     */
    private fun makeDue() = runBlocking {
        store().updateAppUpdate { it.copy(lastCheckedAt = 0L) }
    }

    @Test
    fun theFirstScheduledCheckSaysNewAndTheSecondDoesNot() {
        resetApp(GlobalSettings(motionIntensity = 0f, updateSource = UpdateSource.DIRECT))
        val engine = engine()

        // No force. A fresh install has lastCheckedAt at zero, so this is due on
        // its own, which is exactly how the first check of a real install happens.
        runBlocking { engine.checkForAppUpdate() }
        assertEquals(1, server.received.size)
        assertEquals(
            "Nightbell/3.9.0 (Android; new)",
            server.received[0].headers["user-agent"],
        )
        // Persisted, because the next check is a different process as far as this
        // guarantee is concerned: an install that says `new` on every launch is
        // not an install count, it is a launch count.
        awaitTrue(description = "the census flag was written to the store") {
            store().snapshot.value.update.censusSent
        }

        makeDue()
        runBlocking { engine.checkForAppUpdate() }
        assertEquals(2, server.received.size)
        assertEquals("Nightbell/3.9.0 (Android)", server.received[1].headers["user-agent"])
    }

    @Test
    fun aCheckTheUserAskedForSaysSo() {
        // The floor in census.sh is scheduled checks over four, four being the
        // most six hours allows in a day. Check now skips the interval, so without
        // this marker one person pressing it repeatedly would read as extra
        // installs and the floor would bound nothing.
        resetApp(GlobalSettings(motionIntensity = 0f, updateSource = UpdateSource.DIRECT))
        val engine = engine()

        runBlocking { engine.checkForAppUpdate(force = true) }
        assertEquals(
            "Nightbell/3.9.0 (Android; new; tap)",
            server.received[0].headers["user-agent"],
        )
        runBlocking { engine.checkForAppUpdate(force = true) }
        assertEquals("Nightbell/3.9.0 (Android; tap)", server.received[1].headers["user-agent"])
    }

    @Test
    fun overlappingChecksSayNewOnlyOnce() {
        // Three callers can arrive together on a fresh install: the sweep, the
        // view model on init, and Check now. Without a lock they all read
        // censusSent as false and all say `new`, and the install count stops being
        // a count of installs. Ten at once here, which is more than production can
        // produce and cheap to assert.
        resetApp(GlobalSettings(motionIntensity = 0f, updateSource = UpdateSource.DIRECT))
        val engine = engine()

        runBlocking {
            (1..10).map { async { engine.checkForAppUpdate(force = true) } }.awaitAll()
        }
        val agents = server.received.mapNotNull { it.headers["user-agent"] }
        assertEquals(10, agents.size)
        assertEquals(
            "exactly one request should have said new, saw $agents",
            1,
            agents.count { it.contains("; new") },
        )
    }

    @Test
    fun theRequestCarriesNothingThatCouldIdentifyThisPhone() {
        // The invariant the whole design rests on, asserted against what a real
        // Android device actually put on the wire rather than against intent.
        // A cookie, a client hint, an install id or an Android-supplied default
        // agent would all show up here.
        resetApp(GlobalSettings(motionIntensity = 0f, updateSource = UpdateSource.DIRECT))
        runBlocking { engine().checkForAppUpdate() }

        val request = server.received.single()
        assertEquals("GET", request.method)
        assertEquals(AppUpdate.MANIFEST_PATH, request.path)
        assertEquals("", request.body)
        assertEquals("Nightbell/3.9.0 (Android; new)", request.headers["user-agent"])

        val allowed = setOf("host", "user-agent", "accept", "connection", "accept-encoding")
        val extra = request.headers.keys - allowed
        assertTrue("the request carried unexpected headers: $extra", extra.isEmpty())
        listOf("cookie", "authorization", "x-request-id", "sec-ch-ua").forEach {
            assertFalse("$it was sent", request.headers.containsKey(it))
        }
    }

    @Test
    fun turningTheSwitchOffStopsTheRequest() {
        // The promise the Settings copy makes, tested as a promise: no request at
        // all, not a request that is discarded afterwards.
        resetApp(
            GlobalSettings(
                motionIntensity = 0f,
                updateSource = UpdateSource.DIRECT,
                updateChecksEnabled = false,
            ),
        )
        runBlocking { engine().checkForAppUpdate(force = true) }
        assertEquals(0, server.received.size)
        assertFalse(store().snapshot.value.update.censusSent)
    }

    @Test
    fun choosingGithubSendsNoVersionAndNoFlag() {
        // The other half of the choice being real. Someone who moves the switch
        // to GitHub must stop being counted, and must also stop telling anybody
        // which build they run.
        resetApp(
            GlobalSettings(
                motionIntensity = 0f,
                updateSource = UpdateSource.GITHUB,
                updateSourceChosen = true,
                updateSourceMigratedToSite = true,
            ),
        )
        val engine = CheckEngine(
            store = store(),
            http = HttpChecker(),
            element = ElementChecker(appContext),
            alerts = Nightbell.install(appContext).alerts,
            updates = UpdateChecker(
                githubBase = server.baseUrl,
                siteBase = server.baseUrl,
                installedVersion = { "3.9.0" },
            ),
            installedVersion = { "3.9.0" },
        )
        runBlocking { engine.checkForAppUpdate(force = true) }

        val agent = server.received.single().headers["user-agent"]
        assertEquals(HttpChecker.USER_AGENT, agent)
        assertFalse(agent!!.contains("3.9.0"))
        assertFalse(store().snapshot.value.update.censusSent)
    }

    @Test
    fun aManifestThatNeverArrivesLeavesTheInstallUncounted() {
        // So the count is of installs and not of installs that happened to have
        // signal on the day they were installed.
        resetApp(GlobalSettings(motionIntensity = 0f, updateSource = UpdateSource.DIRECT))
        val engine = CheckEngine(
            store = store(),
            http = HttpChecker(),
            element = ElementChecker(appContext),
            alerts = Nightbell.install(appContext).alerts,
            updates = UpdateChecker(siteBase = "http://127.0.0.1:1", installedVersion = { "3.9.0" }),
            installedVersion = { "3.9.0" },
        )
        runBlocking { engine.checkForAppUpdate() }
        assertFalse(store().snapshot.value.update.censusSent)

        // And it is still said as the first thing on the next attempt that works.
        // A failed check still records that it happened, so the next one has to be
        // made due the same way the passage of time would.
        makeDue()
        runBlocking { engine().checkForAppUpdate() }
        assertEquals(
            "Nightbell/3.9.0 (Android; new)",
            server.received.single().headers["user-agent"],
        )
    }

    // ---- the screen ----------------------------------------------------------

    @Test
    fun theSourceSelectorShowsThreeReachableChoices() {
        resetApp(GlobalSettings(motionIntensity = 0f, updateSource = UpdateSource.DIRECT))
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Settings").performClick()
        composeRule.waitForIdle()
        composeRule.openSettingsTab("About")
        composeRule.onNodeWithTag("settings-list")
            .performScrollToNode(hasTestTag("update-source"))
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("update-source").assertIsDisplayed()
        UpdateSource.entries.forEach { source ->
            segment(source).assertIsDisplayed()
            // The third segment is what made this a question. `SegmentedSelector`
            // gives each label a third of the track, one line, and clips what does
            // not fit rather than wrapping or shrinking it, so a label one word
            // too long loses its end with nothing anywhere reporting a problem.
            // "GitHub releases" was that label until this change.
            val (drew, room) = fit(source)
            assertTrue(
                "the \"${source.label}\" segment label needs ${drew}px of ${room}px and is clipped",
                drew <= room,
            )
        }
        composeRule.captureScreenshot("census-01-source-default")

        // Each segment, tapped, and the blurb underneath has to change with it:
        // three segments that all look selectable and say the same thing would be
        // a control a user has to try in order to understand.
        UpdateSource.entries.forEach { source ->
            composeRule.onNode(
                hasText(source.label) and hasAnyAncestor(hasTestTag("update-source")),
                useUnmergedTree = true,
            ).performClick()
            composeRule.waitForIdle()
            awaitTrue(description = "the store recorded ${source.label}") {
                store().snapshot.value.settings.updateSource == source
            }
            composeRule.onNodeWithText(source.blurb, substring = true).assertIsDisplayed()
            composeRule.captureScreenshot("census-02-source-${source.name.lowercase()}")
        }

        // Touching the switch is an answer to both questions the startup decision
        // asks, so both flags have to be set by the tap. `updateSourceChosen` is
        // not the assertion: `resetApp` sets it for every test in this suite, so
        // checking it here could not fail and proved nothing. The migration flag
        // is the one that matters, and without it a deliberate pick of GitHub
        // would be moved to the site on the next launch.
        val settled = store().snapshot.value.settings
        assertTrue("the tap did not settle updateSourceChosen", settled.updateSourceChosen)
        assertTrue(
            "the tap did not settle updateSourceMigratedToSite, so this choice " +
                "would be migrated away on the next launch",
            settled.updateSourceMigratedToSite,
        )
    }

    @Test
    fun theCardSaysWhatTheCheckSends() {
        // Copy is part of the interface, and this is the sentence that stops the
        // census being something a user finds out about from a log file.
        resetApp(GlobalSettings(motionIntensity = 0f, updateSource = UpdateSource.DIRECT))
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Settings").performClick()
        composeRule.waitForIdle()
        composeRule.openSettingsTab("About")
        // By the list's own item key rather than by the header text. The card is
        // the last item on the page and scrolling to a node inside it lands with
        // the paragraph pushed off the top, which is a passing assertion about
        // text nobody can read.
        composeRule.onNodeWithTag("settings-list").performScrollToKey("updates")
        composeRule.waitForIdle()

        composeRule.onNodeWithText("says which version you are running", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("it is counted so I know how many installs", substring = true)
            .assertIsDisplayed()
        composeRule.captureScreenshot("census-03-update-card")
    }
}
