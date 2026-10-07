package me.river.nightbell

import me.river.nightbell.data.NightbellSnapshot
import me.river.nightbell.data.transfer.BackupCodec
import me.river.nightbell.data.transfer.toImportableSnapshot
import me.river.nightbell.data.transfer.withoutSecrets
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.HeaderPair
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.PendingDelivery
import me.river.nightbell.domain.WebhookEvent
import me.river.nightbell.domain.WebhookFacts
import me.river.nightbell.domain.WebhookFormat
import me.river.nightbell.domain.WebhookState
import me.river.nightbell.domain.WebhookStatus
import me.river.nightbell.domain.WebhookTarget
import me.river.nightbell.domain.WebhookTrack
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebhookBackupTest {

    private val target = WebhookTarget(
        id = "t",
        name = "Ops channel",
        format = WebhookFormat.TEAMS,
        url = "https://prod.westeurope.logic.azure.com/workflows/abc/triggers/manual?sig=SECRETSIG123456",
        headers = listOf(HeaderPair("Authorization", "Bearer tk_secret_value")),
        signingSecret = "whsec_signing_secret",
        events = setOf(WebhookEvent.DOWN, WebhookEvent.RECOVERED, WebhookEvent.DEGRADED),
    )

    private val snapshot = NightbellSnapshot(
        monitors = listOf(Monitor(id = "m", url = "https://example.com")),
        settings = GlobalSettings(webhooks = listOf(target), webhookSender = "Pixel"),
        webhookState = WebhookState(
            tracks = mapOf(WebhookState.trackKey("t", "m") to WebhookTrack(alerting = true)),
            outbox = listOf(PendingDelivery("d", "t", WebhookFacts(event = WebhookEvent.DOWN), 0)),
            status = mapOf("t" to WebhookStatus(failuresInARow = 2)),
        ),
    )

    private fun export(includeSecrets: Boolean) = BackupCodec.encode(
        snapshot = snapshot,
        applicationId = "me.river.nightbell",
        versionName = "3.16.0",
        versionCode = 1,
        nowMs = 0,
        includeSecrets = includeSecrets,
    )

    @Test
    fun `an export keeps the target and leaves its credentials behind`() {
        val file = export(includeSecrets = false)
        assertFalse(file.contains("SECRETSIG123456"))
        assertFalse(file.contains("tk_secret_value"))
        assertFalse(file.contains("whsec_signing_secret"))
        val restored = BackupCodec.decode(file).getOrThrow().snapshot.settings.webhooks.single()
        assertEquals("Ops channel", restored.name)
        assertEquals(target.events, restored.events)
        assertEquals("Authorization", restored.headers.single().name)
        assertTrue("the row then asks for the address again", restored.needsAddress)
    }

    @Test
    fun `asked for, the credentials travel`() {
        val restored = BackupCodec.decode(export(includeSecrets = true)).getOrThrow().snapshot.settings.webhooks.single()
        assertEquals(target, restored)
    }

    @Test
    fun `queues, tracks and delivery history never cross to another phone`() {
        val imported = BackupCodec.decode(export(includeSecrets = true)).getOrThrow().toImportableSnapshot()
        assertEquals(WebhookState(), imported.webhookState)
        assertEquals("Pixel", imported.settings.webhookSender)
    }

    @Test
    fun `withoutSecrets is the one place the rule lives`() {
        val stripped = snapshot.withoutSecrets().settings.webhooks.single()
        assertEquals("", stripped.url)
        assertEquals("", stripped.signingSecret)
        assertEquals("", stripped.headers.single().value)
    }

    @Test
    fun `a store written before webhooks existed still decodes`() {
        val old = """{"schema":1,"monitors":[{"id":"m","url":"https://example.com"}],"settings":{"masterAlertsEnabled":true}}"""
        val decoded = Json { ignoreUnknownKeys = true }.decodeFromString<NightbellSnapshot>(old)
        assertTrue(decoded.settings.webhooks.isEmpty())
        assertTrue(decoded.settings.webhooksEnabled)
        assertEquals(WebhookState(), decoded.webhookState)
    }
}
