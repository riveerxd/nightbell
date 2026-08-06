package me.river.pulse.data.alerts

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
import me.river.pulse.R
import me.river.pulse.domain.LiveTimeline

/**
 * Turns a [LiveTimeline.Timeline] into `ProgressStyle` on the strict-monitoring
 * notification — the ride-card treatment, with the check history where the route
 * would be.
 *
 * Everything here is API 36 and up. On anything older the caller keeps the plain
 * `BigTextStyle` notice: `NotificationCompat.ProgressStyle` compiles and posts
 * fine on older releases, but the compat library does not back-port the drawing —
 * `apply()` checks `SDK_INT >= 36` and otherwise falls through to
 * `Notification.Builder.setProgress(max, progress, indeterminate)`. That trades a
 * paragraph of text the user can read for a featureless bar that reads as a
 * download in progress, which is a downgrade, not a fallback.
 *
 * ### What the platform will and will not promote
 *
 * The line renders in the shade either way. *Promotion* is the extra: the
 * status-bar chip, and the fully expanded card on the lock screen and always-on
 * display. Whether the system grants it turns out to depend on the release in a
 * way the documentation does not describe, and the difference was found by asking
 * the device instead — `hasPromotableCharacteristics()` returned false for a
 * notification that satisfied every published rule, and true for one that broke
 * the headline one.
 *
 * Android 16.0's own implementation, verbatim from
 * `frameworks/base` at tag `android-16.0.0_r1`:
 *
 * ```java
 * public boolean hasPromotableCharacteristics() {
 *     if (!isOngoingEvent() || isGroupSummary() || containsCustomViews() || !hasTitle()) {
 *         return false;
 *     }
 *     if (isOngoingCallStyle()) return true;
 *     return isColorizedRequested() && hasPromotableStyle();
 * }
 * ```
 *
 * So on Android 16 a colorised card is not merely permitted, it is *the signal* —
 * this is the "rich ongoing" mechanism the feature shipped behind, and
 * `setRequestPromotedOngoing` did not become public platform API until API 37,
 * which is also where developer.android.com's "must NOT `setColorized(true)`"
 * rule starts applying. Both are set here, each for the release that reads it.
 *
 * What holds across both, and so must never appear on this builder:
 *
 *  - **no custom content view** of any kind (`setCustomContentView`,
 *    `setCustomBigContentView`, `setCustomHeadsUpContentView`). This is the reason
 *    the graph is segments rather than the bitmap sparkline the dashboard draws:
 *    `containsCustomViews()` is checked first on every release, so the two are
 *    mutually exclusive on one notification.
 *  - a `contentTitle`, `ongoing`, not a group summary, one of the promotable
 *    styles, and a channel above `IMPORTANCE_MIN`.
 *
 * The strict-monitoring notice satisfies the lot: it is ongoing, titled, silent,
 * has no custom view, and its channel is `IMPORTANCE_LOW`.
 */
object LiveCard {

    /**
     * Whether this device can draw the line at all.
     *
     * `Notification.hasPromotableCharacteristics()` answers the same question
     * about a *built* notification and is what [promotable] uses; this is the
     * cheap check callers use before assembling one.
     */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA

    /**
     * Adds the line to [builder] and asks for promotion.
     *
     * Returns false and leaves [builder] untouched below API 36, so the caller's
     * own style stays in place.
     */
    fun apply(
        context: Context,
        builder: NotificationCompat.Builder,
        timeline: LiveTimeline.Timeline,
    ): Boolean {
        if (!supported) return false

        val style = NotificationCompat.ProgressStyle()
            // False, or the platform splits the bar at the tracker and greys out
            // everything past it. Here the whole line is meaningful: the elapsed
            // part carries outcomes and the tail is the wait for the next check.
            .setStyledByProgress(false)
            .setProgress(timeline.progress)
            .setProgressSegments(
                timeline.bands.map { band ->
                    NotificationCompat.ProgressStyle.Segment(band.length)
                        .setColor(colorFor(context, band.tone))
                },
            )
            .setProgressPoints(
                timeline.markers.map { marker ->
                    NotificationCompat.ProgressStyle.Point(marker.position)
                        .setColor(colorFor(context, marker.tone))
                },
            )
            // Drawn, not a resource. A status drawable is a bare stroke path meant
            // for a 24dp status bar, and dropping one into a slot the platform
            // scales for itself is what produced the large white triangle in
            // CALL_AVATAR. A filled disc fills its own bounds at any size.
            .setProgressTrackerIcon(
                IconCompat.createWithBitmap(trackerBitmap(colorFor(context, timeline.current))),
            )
            // The countdown, at the end of the line the tail belongs to. The bar
            // itself takes no text — segments and points carry a colour and nothing
            // else — so an icon slot is the only place on the line a number can go
            // without a custom content view, which would cost promotion outright.
            .setProgressEndIcon(
                IconCompat.createWithBitmap(
                    countdownBitmap(
                        label = timeline.countdownLabel,
                        ink = ContextCompat.getColor(context, R.color.live_label),
                    ),
                ),
            )
        builder
            .setStyle(style)
            // Read by API 37 and up. On Android 16 it is inert — the platform had
            // no such API yet — which is what [earnPromotion] is for.
            .setRequestPromotedOngoing(true)
            // The chip has room for a few characters beside the clock and nothing
            // else. Without this the chip falls back to the notification's `when`,
            // which on a permanently-posted notice is meaningless.
            .setShortCriticalText(timeline.chip)
        return true
    }

    /**
     * Builds [builder], colourising it only if that is what this device wants in
     * exchange for promotion.
     *
     * Asked rather than assumed. The rule inverted between releases — Android 16
     * requires `EXTRA_COLORIZED` and API 37 refuses it — and OEMs are permitted to
     * add criteria of their own on top, so a version check here would be a
     * prediction. `hasPromotableCharacteristics()` is the device's own answer, and
     * building a notification twice to consult it costs nothing.
     *
     * The tint is reverted when it buys nothing, so no release ends up with a
     * colourised card *and* no chip to show for it.
     */
    fun earnPromotion(
        context: Context,
        builder: NotificationCompat.Builder,
        timeline: LiveTimeline.Timeline,
    ): Notification {
        val plain = builder.build()
        if (!supported || promotable(plain)) return plain
        // Nothing to buy. On stock Android 16.0 the *UI* half of rich ongoing is
        // off — `canPostPromotedNotifications()` returns false, because the
        // per-package default is `Flags.uiRichOngoing()` — so colourising here
        // would repaint the card for a chip that is never going to appear. The line
        // itself does not need promotion; it renders in the shade regardless.
        if (!allowedByUser(context)) return plain

        builder
            .setColorized(true)
            .setColor(cardColor(context))
        val colorised = builder.build()
        if (promotable(colorised)) return colorised

        // Both back out together. `setColor` on an uncolourised notification still
        // tints the app name and small icon, and this one had no accent colour
        // before the line existed.
        builder.setColorized(false).setColor(Notification.COLOR_DEFAULT)
        return builder.build()
    }

    /**
     * Whether the system will actually promote [notification] — as opposed to
     * whether we asked it to.
     *
     * Worth asserting in a test rather than trusting: the disqualifiers listed on
     * this class are silent. A notification that fails one of them still posts,
     * still looks right in the shade, and simply never becomes a chip.
     */
    fun promotable(notification: Notification): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA &&
            notification.hasPromotableCharacteristics()

    /** Whether the user has left live updates switched on for this app. */
    fun allowedByUser(context: Context): Boolean {
        if (!supported) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return manager.canPostPromotedNotifications()
    }

    /**
     * The colour a colourised card takes: the app's own surface, whatever the fleet
     * is doing.
     *
     * Emphatically *not* the current tone, which is what it was first built as, on
     * the reasoning that the dashboard's fleet banner takes the worst monitor's
     * colour. On a device it destroyed the thing the card exists to show: behind a
     * deep-red card, a line whose outages are drawn in red rendered them as pale
     * pink smudges, indistinguishable from the track. The card is background and the
     * line is the message, so the background holds still and the line keeps every
     * colour it has.
     *
     * A colourised card is drawn edge to edge and the platform picks black or white
     * text from its luminance with no API to override it — ink is dark enough to
     * get white, which is the same reason [UrgentPageStyles.DEEP_DOWN_COLOR] exists.
     */
    private fun cardColor(context: Context): Int =
        ContextCompat.getColor(context, R.color.pulse_ink)

    private fun colorFor(context: Context, tone: LiveTimeline.Tone): Int = ContextCompat.getColor(
        context,
        when (tone) {
            LiveTimeline.Tone.UP -> R.color.live_up
            LiveTimeline.Tone.DEGRADED -> R.color.live_degraded
            LiveTimeline.Tone.DOWN -> R.color.live_down
            LiveTimeline.Tone.UNKNOWN -> R.color.live_unknown
            LiveTimeline.Tone.AHEAD -> R.color.live_ahead
        },
    )

    /**
     * The tracker: a filled disc in the current tone with a white core, which is
     * the dashboard's leading "now" dot at notification scale.
     */
    /**
     * The countdown, drawn as the line's end icon: "15m", "4m", "now".
     *
     * ### Square, because the slot crops to square
     * The slot is a fixed 20dp square with `centerCrop`, so a bitmap sized to its text
     * is trimmed from both ends — measured on a device, "1h20m" rendered as "h20" and
     * "now" as "how". The canvas is square and the *text* is fitted into it instead:
     * measured once, then scaled by the ratio it overflows by. A short label therefore
     * renders larger than a long one, which is the right way round.
     *
     * ### No container, so the ink has to carry the contrast
     * There were two earlier versions. The first punched the glyphs out of a filled
     * pill with `PorterDuff.CLEAR`, which put a colour this code cannot measure — the
     * card behind — on one side of the contrast ratio; it came out at 2.5:1 and was
     * reported from a device, accurately, as unreadable. The second drew filled text on
     * a pill, which is legible but reads as a chip bolted onto the end of the line.
     *
     * This one drops the container, which means nothing stands between the glyphs and
     * whatever surface the shade is using. `R.color.live_label` therefore follows
     * uiMode exactly as the tones do — dark slate on the light shade, light grey on the
     * dark one. Losing the pill also buys back its margin, so the glyphs are drawn
     * larger, which is the thing legibility actually turned on.
     *
     * Worth stating as a known limit: a *colourised* card is pinned to `pulse_ink`
     * whatever the system theme is, so on a light-themed phone whose card wins
     * promotion the ink resolves light-for-dark and the label loses contrast. The same
     * inversion already affects the segment colours; fixing it means deciding
     * colourisation before the style is built rather than after.
     */
    private fun countdownBitmap(label: String, ink: Int): Bitmap {
        val size = COUNTDOWN_SIZE_PX
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            color = ink
        }

        // Fit to whichever constraint binds: the width the glyphs need, or the height
        // one may be. Scaling by the measured overflow lands in one step where a loop
        // would only converge on the same number slowly.
        val usable = size * COUNTDOWN_INSET
        paint.textSize = usable
        val bounds = Rect()
        paint.getTextBounds(label, 0, label.length, bounds)
        paint.textSize = minOf(usable, usable * usable / bounds.width().coerceAtLeast(1))

        val bitmap = createBitmap(size, size)
        Canvas(bitmap).drawText(
            label,
            size / 2f,
            size / 2f - (paint.descent() + paint.ascent()) / 2f,
            paint,
        )
        return bitmap
    }

    private fun trackerBitmap(color: Int, size: Int = 96): Bitmap {
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply {
            isAntiAlias = true
        }
        paint.color = color
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(size / 2f, size / 2f, size * 0.20f, paint)
        return bitmap
    }


    /** Nominal edge of the countdown square before the platform scales it. */
    private const val COUNTDOWN_SIZE_PX = 144

    /**
     * Share of the square the glyphs may occupy.
     *
     * Higher than it was with a pill behind it: that version had to leave room for
     * the container, and this one only has to stay clear of the slot's own edge.
     */
    private const val COUNTDOWN_INSET = 0.92f
}
