package me.river.nightbell

import me.river.nightbell.data.NightbellSnapshot
import me.river.nightbell.data.transfer.BackupCodec
import me.river.nightbell.data.transfer.withoutSecrets
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.Secrets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules about the GitHub token, stated where they can be enforced.
 *
 * A personal access token is a bearer credential: whoever holds it is the user
 * until it is revoked. So "probably fine" is not a standard any of this can be
 * held to, and each rule gets a test rather than a comment.
 */
class SecretsTest {

    @Test
    fun `a classic token keeps its prefix and its last four`() {
        assertEquals("ghp_…mnop", Secrets.redact("ghp_abcdefghijklmnop"))
    }

    @Test
    fun `a fine-grained token keeps the prefix that identifies its kind`() {
        val token = "github_pat_11ABCDEFG0aBcDeFgHiJkL_mNoPqRsTuVwXyZ0123456789abcdEFGH"
        // The prefix says which kind of token it is, and the last four tell two
        // saved tokens apart. Everything between them is the secret.
        assertEquals("github_pat_…EFGH", Secrets.redact(token))
        assertFalse(Secrets.redact(token).contains("11ABCDEFG0aBcDeFgHiJkL"))
    }

    @Test
    fun `a token with no prefix still hides its middle`() {
        val redacted = Secrets.redact("abcdefghijklmnopqrst")
        assertEquals("…qrst", redacted)
        assertFalse(redacted.contains("abcdefghijklmnop"))
    }

    @Test
    fun `something too short to hide is hidden completely`() {
        // Showing the last four of a twelve-character secret would be showing a
        // third of it. Say nothing about the contents instead.
        assertEquals("ghp_…", Secrets.redact("ghp_abcd"))
        assertEquals("…", Secrets.redact("abcdef"))
    }

    @Test
    fun `nothing in means nothing out`() {
        assertEquals("", Secrets.redact(""))
        assertEquals("", Secrets.redact("   "))
    }

    @Test
    fun `a redacted token is never the token`() {
        val token = "ghp_abcdefghijklmnopqrstuvwxyz0123456789"
        assertFalse(Secrets.redact(token).contains(token))
        assertTrue(Secrets.redact(token).length < token.length)
    }

    @Test
    fun `scrub replaces a token that leaked into someone else's text`() {
        val token = "ghp_abcdefghijklmnopqrstuvwxyz"
        val message = "Bad credentials: $token (request 42)"
        val scrubbed = Secrets.scrub(message, token)
        assertFalse(scrubbed, scrubbed.contains(token))
        assertTrue(scrubbed, scrubbed.contains("ghp_…wxyz"))
        assertTrue(scrubbed, scrubbed.contains("(request 42)"))
    }

    @Test
    fun `scrub leaves ordinary text alone`() {
        val text = "HTTP 500 Internal Server Error"
        assertEquals(text, Secrets.scrub(text, "ghp_abcdefghijklmnop"))
        // And a short "token" is not allowed to corrupt a message by matching
        // some ordinary word inside it.
        assertEquals("an error", Secrets.scrub("an error", "err"))
    }

    // ---- export --------------------------------------------------------------

    private fun snapshotWithToken() = NightbellSnapshot(
        monitors = listOf(Monitor(id = "m", url = "https://example.com")),
        settings = GlobalSettings(githubToken = TOKEN),
    )

    @Test
    fun `an export leaves the token out by default`() {
        val document = BackupCodec.encode(
            snapshot = snapshotWithToken(),
            applicationId = "me.river.nightbell",
            versionName = "3.2.0",
            versionCode = 30,
            nowMs = 1_000L,
        )
        assertFalse(document, document.contains(TOKEN))
        // And the file is still a working backup of everything else.
        val decoded = BackupCodec.decode(document).getOrThrow()
        assertEquals(1, decoded.snapshot.monitors.size)
        assertEquals("", decoded.snapshot.settings.githubToken)
    }

    @Test
    fun `an export includes the token only when explicitly asked`() {
        val document = BackupCodec.encode(
            snapshot = snapshotWithToken(),
            applicationId = "me.river.nightbell",
            versionName = "3.2.0",
            versionCode = 30,
            nowMs = 1_000L,
            includeSecrets = true,
        )
        assertTrue(document.contains(TOKEN))
        assertEquals(TOKEN, BackupCodec.decode(document).getOrThrow().snapshot.settings.githubToken)
    }

    @Test
    fun `stripping secrets changes nothing else about the store`() {
        val original = snapshotWithToken()
        val stripped = original.withoutSecrets()
        assertEquals("", stripped.settings.githubToken)
        assertEquals(original, stripped.copy(settings = stripped.settings.copy(githubToken = TOKEN)))
    }

    @Test
    fun `the export default is off in the shipped settings`() {
        // The setting the export reads. If this ever defaults to true, every
        // backup anyone has ever taken starts carrying a live credential.
        assertFalse(GlobalSettings().includeSecretsInExport)
        assertEquals("", GlobalSettings().githubToken)
    }

    private companion object {
        const val TOKEN = "github_pat_11ABCDEFG0aBcDeFgHiJkL_mNoPqRsTuVwXyZ0123456789abcdEFGH"
    }
}
