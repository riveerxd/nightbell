package me.river.nightbell

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import me.river.nightbell.NightbellTestSupport.appContext
import me.river.nightbell.NightbellTestSupport.awaitTrue
import me.river.nightbell.data.Nightbell
import me.river.nightbell.data.NightbellSnapshot
import me.river.nightbell.domain.AlertPolicy
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.PauseScope
import me.river.nightbell.domain.PauseState
import me.river.nightbell.domain.StatusExpectation
import me.river.nightbell.domain.WebhookEvent
import me.river.nightbell.domain.WebhookFormat
import me.river.nightbell.domain.WebhookTarget
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Webhooks driven by the real engine on a device: a monitor checked against a
 * local server that can be made to fail, and a receiver that records what it was
 * sent. Nothing here calls the routing or the outbox directly; every post comes
 * out of a real check pass, through the store, the dispatcher and OkHttp.
 */
@RunWith(AndroidJUnit4::class)
class WebhookDeliveryInstrumentedTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private val graph get() = Nightbell.install(appContext)

    @Volatile
    private var serviceUp = true
    private val receiverCode = AtomicInteger(200)

    private lateinit var service: TinyHttpServer
    private lateinit var receiver: TinyHttpServer

    @Before
    fun setUp() {
        service = TinyHttpServer {
            if (serviceUp) TinyHttpServer.Response(body = "ok") else TinyHttpServer.Response(code = 503, reason = "Down", body = "down")
        }
        receiver = TinyHttpServer { TinyHttpServer.Response(code = receiverCode.get(), reason = "R") }
    }

    @After
    fun tearDown() {
        service.close()
        receiver.close()
        NightbellTestSupport.resetApp()
    }

    private fun seed(
        target: WebhookTarget = WebhookTarget(id = "hook", name = "Receiver", url = receiver.url("/hook")),
        policy: AlertPolicy = AlertPolicy(failureThreshold = 1, cooldownMinutes = 0),
        pause: PauseState = PauseState(),
    ) {
        runBlocking {
            graph.store.replaceAll(
                NightbellSnapshot(
                    monitors = listOf(
                        Monitor(
                            id = "api",
                            name = "Payments API",
                            kind = MonitorKind.HTTP_STATUS,
                            url = service.url("/health"),
                            status = StatusExpectation(),
                            timeoutSeconds = 5,
                            useGlobalAlerts = false,
                            alert = policy,
                        ),
                    ),
                    settings = GlobalSettings(
                        motionIntensity = 0f,
                        hasSeenPagerSetup = true,
                        pagerSetupSilenced = true,
                        updateChecksEnabled = false,
                        latencyBaselineEnabled = false,
                        webhooks = listOf(target),
                        webhookSender = "Test emulator",
                    ),
                    pause = pause,
                ),
            )
        }
    }

    private fun check() = runBlocking { graph.engine.run("api", force = true) }

    private fun posts(): List<JsonObject> =
        receiver.received.map { Json.parseToJsonElement(it.body).jsonObject }

    private fun events(): List<String> = posts().map { it["event"]!!.jsonPrimitive.content }

    @Test
    fun anOutageAndItsRecoveryArriveOnceEachInOrder() {
        seed()
        check()
        assertTrue("a healthy check posts nothing", receiver.received.isEmpty())

        serviceUp = false
        check()
        awaitTrue(description = "the down post") { receiver.received.size == 1 }
        check()
        check()

        serviceUp = true
        check()
        awaitTrue(description = "the recovery post") { receiver.received.size == 2 }
        check()
        Thread.sleep(1_500)

        assertEquals(listOf("down", "recovered"), events())
        val down = posts()[0]
        assertEquals("Payments API is down", down["title"]!!.jsonPrimitive.content)
        assertEquals("Test emulator", down["sender"]!!.jsonPrimitive.content)
        assertEquals(503, down["check"]!!.jsonObject["status_code"]!!.jsonPrimitive.content.toInt())
        val recovered = posts()[1]
        assertTrue(recovered["down_for_seconds"]!!.jsonPrimitive.long >= 0)

        val ids = receiver.received.map { it.headers["x-nightbell-delivery"] }
        assertEquals("each event has its own delivery id", 2, ids.toSet().size)
        val status = runBlocking { graph.store.currentSnapshot().webhookState.status["hook"] }!!
        assertEquals(0, status.failuresInARow)
        assertTrue(status.lastDeliveredAt > 0)
    }

    @Test
    fun aReceiverThatIsDownGetsBothEventsLaterAndStillInOrder() {
        seed()
        receiverCode.set(503)
        serviceUp = false
        check()
        awaitTrue(description = "the first failed attempt") {
            runBlocking { graph.store.currentSnapshot().webhookState.status["hook"]?.failuresInARow ?: 0 } >= 1
        }
        serviceUp = true
        check()

        val queued = runBlocking { graph.store.currentSnapshot().webhookState.outbox }
        assertEquals(listOf(WebhookEvent.DOWN, WebhookEvent.RECOVERED), queued.map { it.facts.event })
        assertTrue("the recovery must not overtake the down that is backing off", queued.first().attempts >= 1)

        // The receiver comes back. Backoff is shortened by hand rather than waited out.
        receiverCode.set(200)
        runBlocking {
            graph.store.updateWebhookState { state -> state.copy(outbox = state.outbox.map { it.copy(nextAttemptAt = 0) }) }
            graph.webhooks.flush()
        }
        val delivered = receiver.received.filter { it.headers["x-nightbell-event"] != null }
            .map { it.headers["x-nightbell-event"] }
        // The 503s were received too; what matters is the order of the successes at the end.
        assertEquals(listOf("down", "recovered"), delivered.takeLast(2))
        assertTrue(runBlocking { graph.store.currentSnapshot().webhookState.outbox }.isEmpty())
        val firstId = receiver.received.first().headers["x-nightbell-delivery"]
        assertEquals(
            "a retry carries the same delivery id, so a receiver can drop the duplicate",
            firstId,
            receiver.received.filter { it.headers["x-nightbell-event"] == "down" }.last().headers["x-nightbell-delivery"],
        )
    }

    @Test
    fun quietHoursSilenceAFollowingTargetButNotAChannel() {
        // Quiet all day except the minute before midnight, so the test does not
        // depend on when it runs unless it runs at 23:59.
        val quiet = AlertPolicy(
            failureThreshold = 1,
            cooldownMinutes = 0,
            quietHoursEnabled = true,
            quietStartMinute = 0,
            quietEndMinute = 23 * 60 + 59,
        )
        seed(target = WebhookTarget(id = "hook", url = receiver.url("/hook"), followPhone = true), policy = quiet)
        serviceUp = false
        check()
        Thread.sleep(1_500)
        assertTrue("following the phone, quiet hours hold it back", receiver.received.isEmpty())

        seed(target = WebhookTarget(id = "hook", url = receiver.url("/hook"), followPhone = false), policy = quiet)
        check()
        awaitTrue(description = "the channel still hears") { receiver.received.size == 1 }
        assertEquals(listOf("down"), events())
    }

    @Test
    fun aPauseStopsEveryWebhook() {
        seed(pause = PauseState(scope = PauseScope.ALERTS_ONLY, until = System.currentTimeMillis() + 3_600_000))
        serviceUp = false
        check()
        check()
        Thread.sleep(1_500)
        assertTrue(receiver.received.isEmpty())
        assertTrue(runBlocking { graph.store.currentSnapshot().webhookState.outbox }.isEmpty())
    }

    @Test
    fun theMasterSwitchOffSendsNothing() {
        seed()
        runBlocking { graph.store.updateSettings { it.copy(webhooksEnabled = false) } }
        serviceUp = false
        check()
        Thread.sleep(1_500)
        assertTrue(receiver.received.isEmpty())
    }

    @Test
    fun aRefusedMessageIsDroppedAndTheRowSaysWhy() {
        seed(target = WebhookTarget(id = "hook", format = WebhookFormat.SLACK, url = receiver.url("/hook")))
        receiverCode.set(404)
        serviceUp = false
        check()
        awaitTrue(description = "the refusal recorded") {
            runBlocking { graph.store.currentSnapshot().webhookState.status["hook"]?.dropped ?: 0 } == 1
        }
        val status = runBlocking { graph.store.currentSnapshot().webhookState.status["hook"] }!!
        assertTrue(status.lastError, status.lastError.startsWith("HTTP 404"))
        assertTrue(runBlocking { graph.store.currentSnapshot().webhookState.outbox }.isEmpty())
    }

    @Test
    fun acknowledgingAnUrgentPageTellsTheChannel() {
        seed(target = WebhookTarget(id = "hook", url = receiver.url("/hook"), events = setOf(WebhookEvent.ACKNOWLEDGED)))
        runBlocking { graph.store.upsert(graph.store.currentSnapshot().monitors.single().copy(urgent = true)) }
        serviceUp = false
        check()
        runBlocking { graph.engine.acknowledgeUrgent("api") }
        awaitTrue(description = "the acknowledgement post") { receiver.received.size == 1 }
        assertEquals(listOf("acknowledged"), events())
        assertEquals("Payments API: urgent page answered", posts().single()["title"]!!.jsonPrimitive.content)
    }
}
