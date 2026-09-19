package me.river.nightbell

import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.TlsTrust
import me.river.nightbell.domain.UrlInput
import me.river.nightbell.domain.Validation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scheme completion, which is the first of the three things issue #16 reported.
 *
 * Typing `status.example.com` used to turn the field red and grey out Continue.
 */
class UrlInputTest {

    @Test
    fun aBareHostGetsHttps() {
        assertEquals("https://example.com", UrlInput.completed("example.com"))
    }

    @Test
    fun theRestOfTheUrlSurvivesTheCompletion() {
        assertEquals(
            "https://api.example.com:8443/v1/health?deep=1",
            UrlInput.completed("api.example.com:8443/v1/health?deep=1"),
        )
    }

    /** The one thing the issue asked for by name. */
    @Test
    fun anExplicitHttpIsLeftAlone() {
        assertEquals("http://example.com", UrlInput.completed("http://example.com"))
        assertEquals("http://10.0.0.4:9090/metrics", UrlInput.completed("http://10.0.0.4:9090/metrics"))
    }

    @Test
    fun anExistingHttpsIsLeftAlone() {
        assertEquals("https://example.com", UrlInput.completed("https://example.com"))
    }

    /**
     * Not our business to fix. `urlNote` rejects it with a message that names the
     * problem, and rewriting it to `https://ftp://…` would replace that message
     * with one about a host name nobody typed.
     */
    @Test
    fun anUnsupportedSchemeIsLeftForTheValidatorToRefuse() {
        assertEquals("ftp://example.com", UrlInput.completed("ftp://example.com"))
        assertEquals(
            Validation.Severity.ERROR,
            Validation.urlNote(UrlInput.completed("ftp://example.com"))?.severity,
        )
    }

    @Test
    fun blankStaysBlank() {
        assertEquals("", UrlInput.completed(""))
        assertEquals("", UrlInput.completed("   "))
    }

    @Test
    fun whitespaceIsTrimmedBeforeAnythingElse() {
        assertEquals("https://example.com", UrlInput.completed("  example.com  "))
    }

    /**
     * Completion fires on blur, so these are what somebody who tabbed away
     * mid-scheme left behind. Every one of them produces nonsense if completed.
     */
    @Test
    fun aHalfTypedSchemeIsLeftAlone() {
        for (partial in listOf("http:", "https:", "https:/", "http", "https", "HTTPS")) {
            assertEquals(partial, UrlInput.completed(partial))
        }
    }

    @Test
    fun aProtocolRelativeUrlOnlyNeedsTheScheme() {
        assertEquals("https://example.com/health", UrlInput.completed("//example.com/health"))
    }

    /**
     * No CA issues certificates for these names, so completing to https hands the
     * user a monitor that cannot pass, carrying a warning about a scheme they did
     * not choose. The circuit is already encrypted, which is why `urlNote` does
     * not object to plain http here either.
     */
    @Test
    fun aHiddenServiceGetsHttpBecauseHttpsCanNeverWorkThere() {
        assertTrue(UrlInput.completed("abcdefgh.onion").startsWith("http://"))
        assertTrue(UrlInput.completed("something.i2p/status").startsWith("http://"))
        assertNull(Validation.urlNote(UrlInput.completed("abcdefgh.onion")))
    }

    /** A local name with no dot in it is still a host. */
    @Test
    fun aDotlessHostIsStillAHost() {
        assertEquals("https://localhost:9090", UrlInput.completed("localhost:9090"))
    }

    // ---- what the form does with it -----------------------------------------

    private fun draft(url: String) = Monitor(
        id = "m",
        name = "Probe",
        kind = MonitorKind.HTTP_STATUS,
        url = url,
    )

    /**
     * The half of the fix that stops the field going red while somebody types.
     * `report` judges the URL the draft is about to have; `urlNote` stays strict
     * because `HttpChecker` uses it as the last gate before a real request.
     */
    @Test
    fun theFormDoesNotComplainAboutAMissingSchemeItIsAboutToAdd() {
        val report = Validation.report(draft("status.example.com"))
        assertNull(report.of(Validation.Field.URL))
        assertTrue(report.isValid)

        assertEquals(
            Validation.Severity.ERROR,
            Validation.urlNote("status.example.com")?.severity,
        )
    }

    @Test
    fun theFormStillComplainsAboutAUrlNoCompletionCanSave() {
        assertTrue(Validation.report(draft("not a url")).errors.isNotEmpty())
        assertTrue(Validation.report(draft("")).errors.isNotEmpty())
    }

    /**
     * Completion decides the scheme, so the warnings that key off the scheme have
     * to read the completed value too. A schemeless .onion is an http monitor,
     * and telling its author that trust anchors can never be satisfied would be
     * advice about a certificate there is no longer any request for.
     */
    @Test
    fun theHiddenServiceWarningsAgreeWithTheCompletedScheme() {
        val onion = draft("abcdefgh.onion").copy(tlsTrust = TlsTrust.SYSTEM, useProxy = true)
        assertNull(Validation.report(onion).of(Validation.Field.TLS))
    }
}
