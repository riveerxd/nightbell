package me.river.nightbell

import me.river.nightbell.domain.BodyAssertion
import me.river.nightbell.domain.BrowserState
import me.river.nightbell.domain.CheckResult
import me.river.nightbell.domain.MonitorRuntime
import me.river.nightbell.domain.RepoFacts
import me.river.nightbell.domain.ElementTarget
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.HeaderPair
import me.river.nightbell.domain.LogEvent
import me.river.nightbell.domain.LogField
import me.river.nightbell.domain.LogFormat
import me.river.nightbell.domain.LogRedactor
import me.river.nightbell.domain.Monitor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The test that actually protects the user.
 *
 * The adversarial table in [LogRedactorTest] covers the censor. This covers the
 * thing the censor cannot: a line nobody thought about. Every string a monitor
 * or the settings can hold is filled with a unique sentinel, every field factory
 * is pointed at it, and the rendered output has to contain none of them.
 *
 * The second half is the part that keeps this true in six months. It reflects
 * over [Monitor] and [GlobalSettings] and fails when either grows a string field
 * that is not named in [CLASSIFIED]. Adding a field to the model is then a
 * decision about whether it may be published, taken at the moment the field is
 * added rather than the first time somebody logs it.
 */
class LogSentinelTest {

    private val sentinels = listOf(
        "SENTINEL_NAME_a1",
        "SENTINEL_URLQUERY_b2",
        "SENTINEL_HEADERVALUE_c3",
        "SENTINEL_BODY_d4",
        "SENTINEL_COOKIE_e5",
        "SENTINEL_STORAGE_f6",
        "SENTINEL_TOKEN_g7",
        "SENTINEL_SELECTOR_h8",
        "SENTINEL_PROXYHOST_i9",
    )

    private val monitor = Monitor(
        id = "7f3a1c2e-4b6d-4f0a-8c4e-6b8da1b2c3d4",
        name = "SENTINEL_NAME_a1",
        url = "https://user:SENTINEL_TOKEN_g7@example.com/panel?key=SENTINEL_URLQUERY_b2",
        headers = listOf(HeaderPair("X-Api-Key", "SENTINEL_HEADERVALUE_c3")),
        body = "{\"password\":\"SENTINEL_BODY_d4\"}",
        browserState = BrowserState(
            origin = "https://example.com",
            cookies = "session=SENTINEL_COOKIE_e5",
            localStorage = "{\"jwt\":\"SENTINEL_STORAGE_f6\"}",
        ),
        proxyHost = "SENTINEL_PROXYHOST_i9",
    )

    private val settings = GlobalSettings(
        githubToken = "SENTINEL_TOKEN_g7",
        socksProxyHost = "SENTINEL_PROXYHOST_i9",
        latencyReferenceUrl = "https://ref.example.com/?t=SENTINEL_URLQUERY_b2",
    )

    /** What the sink is given as the live secret list, exactly as the app builds it. */
    private val known = buildList {
        add(settings.githubToken)
        add(monitor.browserState.cookies)
        add(monitor.browserState.localStorage)
        monitor.headers.forEach { add(it.value) }
    }.filter { it.isNotBlank() }

    @Test
    fun `no field factory can publish a sentinel`() {
        val rendered = listOf(
            LogField.monitor(monitor.id),
            LogField.route("url", monitor.url),
            LogField.host("host", monitor.url),
            LogField.route("reference", settings.latencyReferenceUrl),
            LogField.secret("cookies", monitor.browserState.cookies),
            LogField.secret("storage", monitor.browserState.localStorage),
            LogField.secret("token", settings.githubToken),
            LogField.present("token", settings.githubToken),
            LogField.tag("proxy", settings.socksProxyHost),
            LogField.tag("name", monitor.name),
            // Bodies, header values and selectors are page or request content
            // and no factory carries them. A length is what a call site may say.
            LogField.count("body_bytes", monitor.body.length),
            LogField.secret("header", monitor.headers.first().value),
            LogField.count("selectors", 1),
            LogField.error("error", IllegalStateException(monitor.url), known),
        ).joinToString(" ") { it.render() }

        for (sentinel in sentinels) {
            assertFalse("$sentinel survived: $rendered", rendered.contains(sentinel))
        }
    }

    @Test
    fun `a whole line survives the second pass with nothing in it`() {
        // The pass the sink applies over the assembled line. This is the one
        // guarantee that does not depend on a call site picking the right
        // factory, so it is asserted against the worst case: a call site that
        // reached for `text` when it should have reached for `secret`.
        val line = LogFormat.line(
            atMs = 0L,
            event = LogEvent.PAGE_SEED,
            fields = listOf(
                LogField.monitor(monitor.id),
                LogField.text("oops", monitor.browserState.cookies),
            ),
        )
        val guarded = LogRedactor.replaceKnown(line, known)
        assertFalse(guarded.contains("SENTINEL_COOKIE_e5"))
    }

    @Test
    fun `a monitor name is never rendered by any factory that a call site uses`() {
        // Names are the field most likely to be reached for by habit, because
        // every other surface in the app shows `displayName`. There is no
        // factory that takes one, and `tag` fingerprints it.
        val tagged = LogField.tag("name", monitor.name).render()
        assertFalse(tagged.contains("SENTINEL_NAME_a1"))
        // And the sink's second pass carries the same guarantee for a line that
        // got a name into it by some other route, because names go into the
        // known list alongside the credentials. See NightbellApplication.
        val leaked = LogRedactor.replaceKnown(
            "check.done monitor=7f3a1c2e verdict=\"${monitor.name} is down\"",
            known + monitor.name,
        )
        assertFalse(leaked.contains("SENTINEL_NAME_a1"))
    }

    @Test
    fun `every string on Monitor is classified`() {
        assertClassified(Monitor::class.java, "Monitor")
    }

    @Test
    fun `every string on GlobalSettings is classified`() {
        assertClassified(GlobalSettings::class.java, "GlobalSettings")
    }

    @Test
    fun `every string on BrowserState is classified`() {
        assertClassified(BrowserState::class.java, "BrowserState")
    }

    @Test
    fun `every string on ElementTarget is classified`() {
        assertClassified(ElementTarget::class.java, "ElementTarget")
    }

    @Test
    fun `every string on HeaderPair is classified`() {
        assertClassified(HeaderPair::class.java, "HeaderPair")
    }

    @Test
    fun `every string on BodyAssertion is classified`() {
        assertClassified(BodyAssertion::class.java, "BodyAssertion")
    }

    @Test
    fun `every string on CheckResult is classified`() {
        assertClassified(CheckResult::class.java, "CheckResult")
    }

    @Test
    fun `every string on MonitorRuntime is classified`() {
        assertClassified(MonitorRuntime::class.java, "MonitorRuntime")
    }

    @Test
    fun `every string on RepoFacts is classified`() {
        assertClassified(RepoFacts::class.java, "RepoFacts")
    }

    /**
     * Fails when a model grows a string field nobody has decided about.
     *
     * The message is the point: it tells whoever added the field what the
     * decision is, and where to record it.
     */
    private fun assertClassified(type: Class<*>, label: String) {
        val strings = type.declaredFields
            .filter { it.type == String::class.java }
            .map { it.name }
            .filterNot { it.startsWith("$") || it == "Companion" }
            .toSet()
        val classified = CLASSIFIED.getValue(label).keys
        val unclassified = strings - classified
        assertTrue(
            "$label has string fields nobody has classified for the diagnostic log: " +
                "$unclassified. Decide whether each may be published, add it to " +
                "LogSentinelTest.CLASSIFIED, and use the matching LogField factory. " +
                "When in doubt the answer is NEVER.",
            unclassified.isEmpty(),
        )
        // And the other way, so a removed field does not leave a stale entry
        // claiming a decision about something that no longer exists.
        assertEquals(
            "$label has classifications for fields that are gone: ${classified - strings}",
            emptySet<String>(),
            classified - strings,
        )
    }

    private companion object {
        /**
         * How every string in the model may appear in a log line.
         *
         * `NEVER` means no factory carries it and none may be added. `HOST` means
         * it goes through [LogField.route] or [LogField.host]. `FINGERPRINT`
         * means [LogField.secret]. `SAFE` means the app chose it from a fixed
         * set and it cannot describe the user.
         */
        val CLASSIFIED: Map<String, Map<String, Rule>> = mapOf(
            "Monitor" to mapOf(
                "id" to Rule.SAFE,
                "name" to Rule.NEVER,
                "url" to Rule.HOST,
                "body" to Rule.NEVER,
                "contentType" to Rule.SAFE,
                "proxyHost" to Rule.HOST,
            ),
            "GlobalSettings" to mapOf(
                "githubToken" to Rule.FINGERPRINT,
                "socksProxyHost" to Rule.HOST,
                "latencyReferenceUrl" to Rule.HOST,
                "speakTemplate" to Rule.NEVER,
                "speakVoice" to Rule.NEVER,
            ),
            "BrowserState" to mapOf(
                "origin" to Rule.HOST,
                "cookies" to Rule.FINGERPRINT,
                "localStorage" to Rule.FINGERPRINT,
            ),
            // Everything a user picked off a page, and everything they typed to
            // describe it. All of it is page content or their own words about
            // their own systems, so none of it has a factory. The one exception
            // is the gate label, which the app already shows on screen in a
            // monitor's verdict and which is a button on a public page.
            "ElementTarget" to mapOf(
                "cssSelector" to Rule.NEVER,
                "xpath" to Rule.NEVER,
                "elementId" to Rule.NEVER,
                "tagName" to Rule.NEVER,
                "classSignature" to Rule.NEVER,
                "textSnippet" to Rule.NEVER,
                "attribute" to Rule.NEVER,
                "expectedText" to Rule.NEVER,
                "label" to Rule.NEVER,
            ),
            "HeaderPair" to mapOf(
                "name" to Rule.SAFE,
                "value" to Rule.FINGERPRINT,
            ),
            "BodyAssertion" to mapOf(
                "value" to Rule.NEVER,
                "jsonPath" to Rule.NEVER,
            ),
            // A verdict. `message` is copy this app composed and can embed a
            // monitor's name, which the sink's second pass fingerprints; the
            // rest is the monitored service's own response.
            "CheckResult" to mapOf(
                "message" to Rule.SAFE,
                "detail" to Rule.NEVER,
                "bodyPreview" to Rule.NEVER,
                "elementText" to Rule.NEVER,
                // An issuer DN is presented by a server on the network and is
                // the answer to "why was this certificate refused", which is
                // what issue 6 was. Same disclosure class as a hostname, which
                // this log carries by design.
                "certIssuer" to Rule.HOST,
                "certSpki" to Rule.SAFE,
            ),
            "MonitorRuntime" to mapOf(
                "lastMessage" to Rule.SAFE,
                "lastDetail" to Rule.NEVER,
                "lastElementText" to Rule.NEVER,
                "certIssuer" to Rule.HOST,
                "certPin" to Rule.SAFE,
            ),
            // A repository's releases, issue titles and the handles of people
            // who commented on them. None of it is this app's to publish.
            "RepoFacts" to mapOf(
                "releaseTag" to Rule.NEVER,
                "issueTitle" to Rule.NEVER,
                "commentAuthor" to Rule.NEVER,
            ),
        )

        enum class Rule { NEVER, HOST, FINGERPRINT, SAFE }
    }
}
