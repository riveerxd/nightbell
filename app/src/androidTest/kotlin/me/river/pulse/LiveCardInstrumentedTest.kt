package me.river.pulse

import android.app.Notification
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.river.pulse.PulseTestSupport.appContext
import me.river.pulse.data.Pulse
import me.river.pulse.data.PulseSnapshot
import me.river.pulse.data.alerts.AlertCenter
import me.river.pulse.data.alerts.LiveCard
import me.river.pulse.domain.GlobalSettings
import me.river.pulse.domain.Health
import me.river.pulse.domain.LiveTimeline
import me.river.pulse.domain.Monitor
import me.river.pulse.domain.MonitorRuntime
import me.river.pulse.domain.Sample
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * The strict-monitoring notice as a Live Update, on a device.
 *
 * Everything this file asserts is invisible in the shade when it goes wrong. A
 * notification that fails one of promotion's disqualifying rules still posts and
 * still looks right expanded — it simply never becomes a status-bar chip and never
 * expands on the lock screen, with nothing logged. `hasPromotableCharacteristics()`
 * is the platform's own answer to "would you promote this", so it is worth asking
 * it here rather than trusting the rules stay as documented.
 *
 * The last test leaves a card posted on purpose, so the rendering can be captured
 * from the host:
 *   adb shell cmd statusbar expand-notifications
 *   adb exec-out screencap -p > live-card.png
 */
@RunWith(AndroidJUnit4::class)
class LiveCardInstrumentedTest {

    private val now = System.currentTimeMillis()
    private val minute = 60_000L

    private fun monitor(id: String, name: String, interval: Int = 5) = Monitor(
        id = id,
        name = name,
        url = "https://$id.example.com/health",
        intervalMinutes = interval,
    )

    /**
     * Six hours of five-minute checks, with [downFrom]..[downTo] minutes ago
     * failing — a real outage in the middle of a real history rather than one
     * sample flipped, so the segment it produces has a length worth looking at.
     */
    private fun history(
        health: Health = Health.UP,
        downFrom: Long = -1,
        downTo: Long = -1,
    ): MonitorRuntime {
        val samples = (0L..71L).map { step ->
            val minutesAgo = 355 - step * 5
            val down = downFrom >= 0 && minutesAgo in downTo..downFrom
            Sample(
                at = now - minutesAgo * minute,
                ok = !down,
                latencyMs = if (down) 0 else 90 + (step % 11) * 14,
                code = if (down) 0 else 200,
                note = if (down) "Connection refused" else "",
            )
        }
        return MonitorRuntime(
            health = health,
            lastCheckedAt = now - minute,
            lastLatencyMs = samples.last().latencyMs,
            lastMessage = if (health == Health.DOWN) "Connection refused" else "200 OK",
            samples = samples,
        )
    }

    private fun seed(strict: Boolean = true): PulseSnapshot {
        val monitors = listOf(
            monitor("api", "api.pulse"),
            monitor("checkout", "checkout"),
            monitor("cdn", "cdn edge", interval = 15),
        )
        val snapshot = PulseSnapshot(
            monitors = monitors,
            runtimes = mapOf(
                // One clean, one that fell over 40 minutes ago and is still down,
                // one that had a blip two hours back and recovered.
                "api" to history(),
                "checkout" to history(health = Health.DOWN, downFrom = 40, downTo = 0),
                "cdn" to history(downFrom = 130, downTo = 100),
            ),
            settings = GlobalSettings(
                motionIntensity = 0f,
                hasSeenPagerSetup = true,
                strictForegroundMonitoring = strict,
            ),
        )
        val graph = Pulse.install(appContext)
        runBlocking { graph.store.replaceAll(snapshot) }
        return snapshot
    }

    private fun timelineOf(snapshot: PulseSnapshot) = LiveTimeline.of(
        monitors = snapshot.monitors,
        runtimes = snapshot.runtimes,
        nowMs = now,
    )

    /**
     * Not a `@Before`: the two tests that assert what *older* releases do have to
     * run on those releases, and a class-wide assumption would skip exactly them.
     */
    private fun requireBaklava() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA)
    }

    private fun requirePreBaklava() {
        assumeTrue(Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA)
    }

    @Test
    fun beforeApi36TheLineIsSkippedRatherThanDegraded() {
        requirePreBaklava()
        // `NotificationCompat.ProgressStyle` posts fine on older releases, but the
        // compat library does not back-port the drawing: `apply()` gates on
        // `SDK_INT >= 36` and otherwise falls through to
        // `Notification.Builder.setProgress(max, progress, indeterminate)`. That
        // would trade a paragraph the user can read for a featureless bar that
        // reads as a download, so the line is not applied at all.
        val timeline = timelineOf(seed())!!
        val notification = AlertCenter(appContext)
            .serviceNotification("Strict monitoring · 1 of 3 is down", "body", null, timeline)
        assertEquals(
            Notification.BigTextStyle::class.java.name,
            notification.extras.getString(Notification.EXTRA_TEMPLATE),
        )
        assertFalse(LiveCard.supported)
    }

    @Test
    fun theLineBecomesAProgressStyleNotification() {
        requireBaklava()
        val timeline = timelineOf(seed())!!
        val notification = AlertCenter(appContext)
            .serviceNotification("Strict monitoring · 1 of 3 is down", "body", null, timeline)

        assertEquals(
            Notification.ProgressStyle::class.java.name,
            notification.extras.getString(Notification.EXTRA_TEMPLATE),
        )
        assertEquals(timeline.chip, notification.shortCriticalText)
        assertTrue(NotificationCompat.isRequestPromotedOngoing(notification))
    }

    @Test
    fun promotionIsEarnedWhereItIsOnOfferAndNotPaidForWhereItIsNot() {
        requireBaklava()
        val timeline = timelineOf(seed())!!
        val notification = AlertCenter(appContext)
            .serviceNotification("Strict monitoring · 1 of 3 is down", "body", null, timeline)

        if (LiveCard.allowedByUser(appContext)) {
            assertTrue(
                "promotion is on offer on API ${Build.VERSION.SDK_INT} and was not taken",
                LiveCard.promotable(notification),
            )
        } else {
            // Stock Android 16.0 lands here: `canPostPromotedNotifications()` is
            // false because the per-package default is `Flags.uiRichOngoing()`, and
            // that flag is compiled off in the release build — confirmed by trying
            // to flip it through `device_config` on a rooted emulator, which the
            // read-only flag ignored. Colourising the card would then repaint it in
            // exchange for a chip that cannot appear, so it must not happen.
            assertFalse(
                "the card was colourised for a chip this device will never show",
                notification.extras.getBoolean(Notification.EXTRA_COLORIZED, false),
            )
        }
    }

    @Test
    fun colourisingIsWhatAndroid16WantsInExchangeForAChip() {
        requireBaklava()
        // Not what developer.android.com says — it lists `setColorized(true)` as a
        // disqualifier. Android 16.0's own `hasPromotableCharacteristics()` ends in
        // `return isColorizedRequested() && hasPromotableStyle()`, so on this
        // release the tint *is* the request. The docs describe API 37, where
        // `setRequestPromotedOngoing` became platform API and the tint became a
        // disqualifier instead.
        //
        // Which way round this device has it is exactly what `earnPromotion` works
        // out at runtime, so what is pinned here is only that one of the two is an
        // answer — if neither is, the mechanism has changed again and the runtime
        // probe is choosing between two failures.
        val timeline = timelineOf(seed())!!
        val bare = NotificationCompat.Builder(appContext, AlertCenter(appContext).ensureServiceChannel())
            .setSmallIcon(R.drawable.ic_stat_brand)
            .setContentTitle("Strict monitoring")
            .setOngoing(true)
        LiveCard.apply(appContext, bare, timeline)
        val plainWorks = LiveCard.promotable(bare.build())
        val colorisedWorks = LiveCard.promotable(bare.setColorized(true).build())
        assertTrue("neither plain nor colourised is promotable", plainWorks || colorisedWorks)
    }

    @Test
    fun aCustomContentViewIsRefusedPromotion() {
        requireBaklava()
        // The other half of the same trade: a RemoteViews layout could hold the
        // dashboard's actual bitmap sparkline, and holding one costs promotion.
        val timeline = timelineOf(seed())!!
        val builder = NotificationCompat.Builder(appContext, AlertCenter(appContext).ensureServiceChannel())
            .setSmallIcon(R.drawable.ic_stat_brand)
            .setContentTitle("Strict monitoring")
            .setOngoing(true)
            .setCustomBigContentView(
                android.widget.RemoteViews(appContext.packageName, R.layout.notification_urgent),
            )
        LiveCard.apply(appContext, builder, timeline)
        assertFalse(LiveCard.promotable(builder.build()))
    }

    @Test
    fun plainNoticeIsUnchangedWithoutATimeline() {
        // No history yet, or an older release: the notice keeps the paragraph of
        // text it has always had rather than degrading to a featureless bar.
        val notification = AlertCenter(appContext)
            .serviceNotification("Starting…", "Working out what needs checking.", null, null)
        assertEquals(
            Notification.BigTextStyle::class.java.name,
            notification.extras.getString(Notification.EXTRA_TEMPLATE),
        )
        assertFalse(NotificationCompat.isRequestPromotedOngoing(notification))
    }

    @Test
    fun theOutageIsOnTheLineAndInTheChip() {
        requireBaklava()
        val timeline = timelineOf(seed())!!
        assertEquals("1 DOWN", timeline.chip)
        assertEquals(LiveTimeline.Tone.DOWN, timeline.current)
        // Two outages went into the fixture: one still running, one that recovered
        // two hours ago.
        assertEquals(2, timeline.bands.count { it.tone == LiveTimeline.Tone.DOWN })
        assertEquals(2, timeline.markers.size)
        assertNotNull(timeline.bands.lastOrNull { it.tone == LiveTimeline.Tone.AHEAD })
    }

    /**
     * Posts the card and leaves it up, for capturing from the host.
     *
     * Named last on purpose — JUnit runs methods in a deterministic but
     * unspecified order, so this asserts what it needs rather than relying on
     * having run after the others.
     */
    @Test
    fun zzPostsALiveCardForEyeballing() {
        requireBaklava()
        val timeline = timelineOf(seed())!!
        val alerts = AlertCenter(appContext)
        val notification = alerts.serviceNotification(
            title = "Strict monitoring · 1 of 3 is down",
            body = "3 monitors · 1 down · 0 slow · last ${timeline.spanLabel}",
            stopIntent = null,
            timeline = timeline,
        )
        // No assertion about promotion here on purpose — the shade renders the line
        // whether or not the card is promoted, and this test exists to put one on
        // screen. `promotionIsEarnedWhereItIsOnOfferAndNotPaidForWhereItIsNot` owns
        // that question.
        assertEquals(
            Notification.ProgressStyle::class.java.name,
            notification.extras.getString(Notification.EXTRA_TEMPLATE),
        )
        NotificationManagerCompat.from(appContext)
            .notify(AlertCenter.SERVICE_NOTIFICATION_ID, notification)
    }
}
