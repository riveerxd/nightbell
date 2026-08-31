package me.river.nightbell

import me.river.nightbell.data.NightbellSnapshot
import me.river.nightbell.data.transfer.BackupCodec
import me.river.nightbell.data.transfer.withoutSecrets
import me.river.nightbell.domain.BrowserState
import me.river.nightbell.domain.ElementTarget
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.ui.setup.movedPage
import me.river.nightbell.ui.setup.shortPage
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parts of issue #8 that are decisions rather than page loads: which page a
 * pick belongs to, which site a captured session is allowed to be replayed at,
 * and what leaves the app in an export.
 *
 * The rest of it needs a real renderer and lives in
 * `GatedElementInstrumentedTest`.
 */
class GatedPageTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun gated() = Monitor(
        id = "gated",
        kind = MonitorKind.WEBSITE_ELEMENT,
        url = "https://shop.example/cellar",
        browserState = BrowserState(
            origin = "https://shop.example",
            cookies = "entered=1",
            capturedAt = 1_700_000_000_000L,
        ),
    ).withTargets(listOf(ElementTarget(elementId = "stock")))

    // ---- which page a pick belongs to ---------------------------------------

    @Test
    fun stayingOnTheSamePageIsNotAMove() {
        assertNull(movedPage("https://shop.example/", "https://shop.example/"))
        assertNull(movedPage("https://shop.example/", "https://shop.example"))
        assertNull(movedPage("https://shop.example", "https://shop.example/"))
    }

    /** An anchor scrolls the page. It must not read as pointing the monitor elsewhere. */
    @Test
    fun aFragmentIsNotAMove() {
        assertNull(movedPage("https://shop.example/cellar", "https://shop.example/cellar#stock"))
    }

    @Test
    fun followingALinkIsAMove() {
        assertEquals(
            "https://shop.example/cellar",
            movedPage("https://shop.example/", "https://shop.example/cellar"),
        )
        assertEquals(
            "https://other.example/",
            movedPage("https://shop.example/", "https://other.example/"),
        )
    }

    /** The teardown load is not somewhere the user went. */
    @Test
    fun aboutBlankIsNeverAMove() {
        assertNull(movedPage("https://shop.example/", "about:blank"))
    }

    @Test
    fun aPageReadsAsItsPathInOneLine() {
        assertEquals("/cellar", shortPage("https://shop.example/cellar"))
        assertEquals("/cellar?page=2", shortPage("https://shop.example/cellar?page=2"))
        assertEquals("shop.example", shortPage("https://shop.example"))
        assertEquals("shop.example", shortPage("https://shop.example/"))
    }

    // ---- where a captured session may be replayed ---------------------------

    @Test
    fun aSessionAppliesToItsOwnOriginAndNoOther() {
        val state = BrowserState(origin = "https://shop.example", cookies = "entered=1")
        assertTrue(state.appliesTo("https://shop.example/cellar"))
        assertTrue(state.appliesTo("https://SHOP.example/cellar?page=2#top"))
        // The suffix trick, which a naive startsWith would wave through.
        assertFalse(state.appliesTo("https://shop.example.attacker.test/"))
        assertFalse(state.appliesTo("https://other.example/"))
        // Scheme is part of the origin: a session taken over TLS is not replayed
        // in the clear.
        assertFalse(state.appliesTo("http://shop.example/"))
        // A port is part of it too.
        assertFalse(state.appliesTo("https://shop.example:8443/"))
    }

    @Test
    fun aStateWithNoOriginAppliesNowhere() {
        assertFalse(BrowserState(cookies = "entered=1").appliesTo("https://shop.example/"))
    }

    @Test
    fun originsAreReadOffTheUrlRatherThanGuessed() {
        assertEquals("https://shop.example", BrowserState.originOf("https://shop.example/a/b?c#d"))
        assertEquals("http://127.0.0.1:8080", BrowserState.originOf("http://127.0.0.1:8080/shop"))
        assertEquals("", BrowserState.originOf("shop.example/a"))
        assertEquals("", BrowserState.originOf(""))
    }

    @Test
    fun anEmptyCaptureIsRecognisedAsEmpty() {
        assertTrue(BrowserState().isEmpty)
        assertTrue(BrowserState(origin = "https://shop.example", capturedAt = 5L).isEmpty)
        assertFalse(BrowserState(cookies = "a=1").isEmpty)
        assertFalse(BrowserState(localStorage = """{"a":"1"}""").isEmpty)
    }

    // ---- what leaves the app ------------------------------------------------

    @Test
    fun anExportCarriesTheMonitorAndNotTheSession() {
        val snapshot = NightbellSnapshot(
            monitors = listOf(gated()),
            settings = GlobalSettings(githubToken = "ghp_secret"),
        )
        val stripped = snapshot.withoutSecrets()

        assertEquals("https://shop.example/cellar", stripped.monitors.single().url)
        assertEquals("stock", stripped.monitors.single().targets.single().elementId)
        assertTrue(stripped.monitors.single().browserState.isEmpty)
        assertEquals("", stripped.settings.githubToken)
    }

    @Test
    fun theDefaultExportHasNoSessionInTheFile() {
        val raw = BackupCodec.encode(
            snapshot = NightbellSnapshot(monitors = listOf(gated())),
            applicationId = "me.river.nightbell",
            versionName = "test",
            versionCode = 1,
            nowMs = 0L,
        )
        assertFalse("the cookie was written into the export", raw.contains("entered=1"))
    }

    /** Opting into secrets is the one way it travels, same as the GitHub token. */
    @Test
    fun anExportWithSecretsKeepsTheSession() {
        val raw = BackupCodec.encode(
            snapshot = NightbellSnapshot(monitors = listOf(gated())),
            applicationId = "me.river.nightbell",
            versionName = "test",
            versionCode = 1,
            nowMs = 0L,
            includeSecrets = true,
        )
        assertTrue(raw.contains("entered=1"))
    }

    // ---- the store ----------------------------------------------------------

    @Test
    fun aSessionSurvivesARoundTripThroughTheStore() {
        val decoded = json.decodeFromString<Monitor>(json.encodeToString(gated()))
        assertEquals("entered=1", decoded.browserState.cookies)
        assertEquals("https://shop.example", decoded.browserState.origin)
        assertEquals(1_700_000_000_000L, decoded.browserState.capturedAt)
    }

    /** A store written before this existed has to keep decoding. */
    @Test
    fun aMonitorWrittenBeforeSessionsExistedStillDecodes() {
        val legacy = """{"id":"old","kind":"website_element","url":"https://shop.example/"}"""
        val decoded = json.decodeFromString<Monitor>(legacy)
        assertEquals("old", decoded.id)
        assertTrue(decoded.browserState.isEmpty)
    }
}
