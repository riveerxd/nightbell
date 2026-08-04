package me.river.pulse.data.alerts

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.util.TypedValue
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
import me.river.pulse.R

/**
 * The five candidate looks for the URGENT heads-up — the container that drops
 * out of the top of the screen when a paged monitor goes down.
 *
 * All five are *notifications*, not activities: the ask is the shape an incoming
 * call uses in the shade, not a screen takeover. Only one of these ships; this
 * file exists so they can be posted on a device and compared as they actually
 * render, because several of the levers involved (colorisation, CallStyle,
 * custom views) are quietly ignored by the platform in ways no amount of reading
 * the docs settles.
 *
 * What each one is betting on:
 *
 *  - [CALL_INCOMING] / [CALL_ONGOING] — `CallStyle`, the exact layout Google's
 *    Phone app gets. The platform draws the round action buttons and gives the
 *    notification call-grade ranking. **Requires** the notification to be tied
 *    to a foreground service or carry a full-screen intent, or the system
 *    silently demotes it to an ordinary notification.
 *  - [RED_BANNER] — `setColorized(true)` with the down colour. Only honoured
 *    for foreground-service, media and call notifications, which is why the
 *    existing `notifyUrgent` never actually renders red.
 *  - [LOUD_STANDARD] — no special mechanism at all. The baseline that behaves
 *    identically on every OEM skin and needs no permission. Whatever ships, this
 *    is the fallback when the chosen mechanism is unavailable.
 *  - [CUSTOM_DECORATED] — a `RemoteViews` heads-up. Android 12 stopped letting
 *    apps own the whole notification area, so this is here to show exactly how
 *    much of the custom layout survives the system's decoration.
 */
enum class UrgentPageStyle {
    CALL_INCOMING,
    CALL_ONGOING,
    RED_BANNER,
    LOUD_STANDARD,
    CUSTOM_DECORATED,

    /**
     * [CALL_INCOMING] with a drawn circular avatar instead of the app's status
     * icon. The status icon is a bare stroke path meant to be rendered 24dp in
     * the status bar; dropped into the call layout's avatar slot the platform
     * scaled it up into a large white triangle with no container, which read as a
     * broken image rather than as an alert.
     */
    CALL_AVATAR,

    /**
     * A custom heads-up view with no `DecoratedCustomViewStyle` wrapper, to see
     * how much of the notification area an app can still paint on API 31+.
     */
    RED_FULL_BLEED,

    /**
     * The call card rebuilt as a `RemoteViews`: red, white text, and our own
     * three actions — Acknowledge, Re-check and a crossed-bell Mute 1h.
     *
     * `CallStyle` hands over the layout for free but keeps its button labels
     * ("Decline", "Answer") and takes no third action, and the wording is the
     * point of this alert, so the layout is ours.
     */
    CALL_CUSTOM,

    /**
     * The system `CallStyle` card on a darker red.
     *
     * Colorised cards do not take a text colour — the platform picks black or
     * white from the background's luminance, and #FF4D57 is light enough that it
     * chooses black. A deeper red flips that to white without a custom layout.
     * Kept for comparison; the buttons still say "Decline" and "Answer".
     */
    CALL_DARK_RED,

    /**
     * The shipping candidate: a colorised deep-red card drawn entirely by the
     * platform, carrying our own three actions.
     *
     * Chosen over the hand-built [CALL_CUSTOM] layout after measuring it on a
     * device. A custom `RemoteViews` is rescaled non-uniformly inside the
     * heads-up slot — a 38dp pill with a 19dp corner radius came out 23dp tall
     * with a ~4dp radius, so the controls rendered as faceted rounded rectangles
     * however the radius was specified, and an `oval` came out a rounded square.
     * System-drawn buttons have none of that, and the labels were never the part
     * the platform owned: only `CallStyle`'s are fixed.
     *
     * Deep red rather than the brand red because a colorised card takes no text
     * colour — the platform picks black or white from the background's luminance,
     * and #FF4D57 is light enough to get black.
     */
    RED_CARD,

    /**
     * [CALL_DARK_RED], then the action titles rewritten after `build()`.
     *
     * `CallStyle` generates its own decline/answer actions from platform string
     * resources, so there is no API that sets those labels. But `actions` is a
     * plain public field on the finished [Notification], and whether rewriting it
     * survives depends on where the call template's RemoteViews are inflated: in
     * this process at build time it works, in SystemUI via `recoverBuilder` it is
     * regenerated and the platform labels come back. Only a device answers that.
     */
    CALL_RELABELLED,
}

/** Everything the heads-up needs to say, with no Android types in it. */
data class UrgentPageContent(
    val monitorName: String,
    val headline: String,
    val url: String = "",
    val downFor: String = "",
    val failedChecks: Int = 1,
    val reminderNumber: Int = 0,
    val repeatMinutes: Int = 5,
)

/**
 * The intents the page's buttons fire. Passed in rather than built here so the
 * screenshot harness can hand over no-op intents without reaching into the
 * engine.
 */
data class UrgentPageActions(
    val acknowledge: PendingIntent,
    val open: PendingIntent,
    val recheck: PendingIntent,
    /** "Stop shouting for an hour" — the crossed-bell control. */
    val mute: PendingIntent? = null,
    /**
     * Attached so `CallStyle` is not demoted. It only actually takes over the
     * screen while the device is locked; unlocked, the heads-up above is what
     * the user sees.
     */
    val fullScreen: PendingIntent? = null,
)

object UrgentPageStyles {

    const val DOWN_COLOR: Int = 0xFFFF4D57.toInt()

    /**
     * The same red taken dark enough that the platform's own contrast maths picks
     * white text for a colorised card instead of black. Only used where the
     * system owns the type colour.
     */
    const val DEEP_DOWN_COLOR: Int = 0xFFB3121F.toInt()

    /** The two colours the platform's own call card uses for its action pills. */
    private const val DECLINE_SLOT_COLOR: Int = 0xFFE8402C.toInt()
    private const val ANSWER_SLOT_COLOR: Int = 0xFF157F3C.toInt()

    fun build(
        context: Context,
        style: UrgentPageStyle,
        channelId: String,
        content: UrgentPageContent,
        actions: UrgentPageActions,
    ): Notification = when (style) {
        UrgentPageStyle.CALL_INCOMING -> callIncoming(context, channelId, content, actions)
        UrgentPageStyle.CALL_ONGOING -> callOngoing(context, channelId, content, actions)
        UrgentPageStyle.RED_BANNER -> redBanner(context, channelId, content, actions)
        UrgentPageStyle.LOUD_STANDARD -> loudStandard(context, channelId, content, actions)
        UrgentPageStyle.CUSTOM_DECORATED -> customDecorated(context, channelId, content, actions)
        UrgentPageStyle.CALL_AVATAR -> callAvatar(context, channelId, content, actions)
        UrgentPageStyle.RED_FULL_BLEED -> redFullBleed(context, channelId, content, actions)
        UrgentPageStyle.CALL_CUSTOM -> callCustom(context, channelId, content, actions)
        UrgentPageStyle.CALL_DARK_RED -> callDarkRed(context, channelId, content, actions)
        UrgentPageStyle.RED_CARD -> redCard(context, channelId, content, actions)
        UrgentPageStyle.CALL_RELABELLED -> callRelabelled(context, channelId, content, actions)
    }

    // ---- 11 · CALL_RELABELLED -----------------------------------------------

    /**
     * The call card with our own words on its buttons.
     *
     * `CallStyle` builds its two actions itself, from platform strings, with no
     * setter — so they are rewritten on the built [Notification] instead.
     * `Notification.actions` is a public mutable field, and `Action.title` is
     * final, so each action is rebuilt around the original icon and intent with a
     * new title.
     */
    private fun callRelabelled(
        context: Context,
        channelId: String,
        content: UrgentPageContent,
        actions: UrgentPageActions,
    ): Notification {
        val notification = callDarkRed(context, channelId, content, actions)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return notification
        val existing = notification.actions ?: return notification
        val ack = context.getString(R.string.urgent_action_acknowledge_short)
        val recheck = context.getString(R.string.urgent_action_recheck)
        notification.actions = existing.mapIndexed { index, action ->
            val title = when (index) {
                // CallStyle emits negative-then-positive, and appends the app's
                // own actions after them — so index 2 is already "Mute 1h".
                0 -> ack
                1 -> recheck
                else -> action.title
            }
            Notification.Action.Builder(action.getIcon(), title, action.actionIntent)
                .build()
        }.toTypedArray()
        return notification
    }

    // ---- 10 · RED_CARD ------------------------------------------------------

    private fun redCard(
        context: Context,
        channelId: String,
        content: UrgentPageContent,
        actions: UrgentPageActions,
    ): Notification {
        val builder = base(context, channelId, content, actions)
            .setColor(DEEP_DOWN_COLOR)
            .setColorized(true)
            .setContentTitle("${content.monitorName} is down")
            .setContentText(
                buildString {
                    append(content.headline)
                    if (content.downFor.isNotBlank()) append(" · down for ").append(content.downFor)
                },
            )
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText(content)))
            .addAction(
                R.drawable.ic_stat_ok,
                context.getString(R.string.urgent_action_acknowledge_short),
                actions.acknowledge,
            )
            .addAction(
                R.drawable.ic_stat_refresh,
                context.getString(R.string.urgent_action_recheck),
                actions.recheck,
            )
        actions.mute?.let {
            builder.addAction(R.drawable.ic_stat_mute, "Mute 1h", it)
        }
        return builder.build()
    }

    // ---- 8 · CALL_CUSTOM ----------------------------------------------------

    private fun callCustom(
        context: Context,
        channelId: String,
        content: UrgentPageContent,
        actions: UrgentPageActions,
    ): Notification {
        val views = RemoteViews(context.packageName, R.layout.notification_urgent_call).apply {
            setTextViewText(R.id.urgent_name, "${content.monitorName} is down")
            setTextViewText(
                R.id.urgent_detail,
                buildString {
                    append(content.headline)
                    if (content.downFor.isNotBlank()) append(" · down for ").append(content.downFor)
                },
            )
            // The pills the call card would have drawn, with our wording. Same
            // three colours the platform uses: its decline red, its answer green,
            // and white for the neutral one.
            setImageViewBitmap(
                R.id.urgent_ack,
                UrgentPills.label(
                    text = context.getString(R.string.urgent_action_acknowledge_short),
                    fill = DECLINE_SLOT_COLOR,
                    textColor = 0xFFFFFFFF.toInt(),
                ),
            )
            setImageViewBitmap(
                R.id.urgent_recheck,
                UrgentPills.label(
                    text = context.getString(R.string.urgent_action_recheck),
                    fill = ANSWER_SLOT_COLOR,
                    textColor = 0xFFFFFFFF.toInt(),
                ),
            )
            setImageViewBitmap(
                R.id.urgent_mute,
                UrgentPills.icon(
                    context = context,
                    iconRes = R.drawable.ic_stat_mute,
                    fill = 0xFFFFFFFF.toInt(),
                    tint = 0xFF14040A.toInt(),
                    slash = true,
                ),
            )
            // The taps live on the views themselves; a custom layout gets no
            // system action buttons to hang them off.
            setOnClickPendingIntent(R.id.urgent_ack, actions.acknowledge)
            setOnClickPendingIntent(R.id.urgent_recheck, actions.recheck)
            actions.mute?.let { setOnClickPendingIntent(R.id.urgent_mute, it) }
            // A shape drawable's corner radius does not survive the heads-up
            // slot: measured on a device, a 38dp pill with a 19dp radius came
            // out 23dp tall with roughly a 4dp radius, so the controls read as
            // faceted rectangles and an `oval` came out a rounded square. This
            // clips the view itself, which the renderer does honour.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                listOf(R.id.urgent_ack, R.id.urgent_recheck, R.id.urgent_mute).forEach { id ->
                    setViewOutlinePreferredRadius(id, 19f, TypedValue.COMPLEX_UNIT_DIP)
                }
            }
        }
        return base(context, channelId, content, actions)
            .setContentTitle("${content.monitorName} is down")
            .setContentText(content.headline)
            .setCustomContentView(views)
            .setCustomHeadsUpContentView(views)
            .setCustomBigContentView(views)
            .setColor(DEEP_DOWN_COLOR)
            // Paints the strip around our layout to match it, so the card does not
            // read as a red panel floating in a white one.
            .setColorized(true)
            .build()
    }

    // ---- 9 · CALL_DARK_RED --------------------------------------------------

    private fun callDarkRed(
        context: Context,
        channelId: String,
        content: UrgentPageContent,
        actions: UrgentPageActions,
    ): Notification {
        val person = Person.Builder()
            .setName(content.monitorName)
            .setImportant(true)
            .setIcon(IconCompat.createWithBitmap(avatarBitmap()))
            .build()
        val builder = base(context, channelId, content, actions)
            .setColor(DEEP_DOWN_COLOR)
            .setContentTitle(content.monitorName)
            .setContentText("${content.headline} · down for ${content.downFor}")
            .setColorized(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setStyle(
                NotificationCompat.CallStyle.forIncomingCall(
                    person,
                    actions.acknowledge,
                    actions.open,
                ).setIsVideo(false),
            )
        }
        // CallStyle appends app actions after its own two, so the mute control can
        // ride along even though the style will not let its labels be changed.
        actions.mute?.let { builder.addAction(R.drawable.ic_stat_mute, "Mute 1h", it) }
        return builder.build()
    }

    // ---- 6 · CALL_AVATAR ----------------------------------------------------

    private fun callAvatar(
        context: Context,
        channelId: String,
        content: UrgentPageContent,
        actions: UrgentPageActions,
    ): Notification {
        val person = Person.Builder()
            .setName(content.monitorName)
            .setImportant(true)
            .setIcon(IconCompat.createWithBitmap(avatarBitmap()))
            .build()
        val builder = base(context, channelId, content, actions)
            .setContentTitle(content.monitorName)
            .setContentText("${content.headline} · down for ${content.downFor}")
            // Only honoured when this is posted as a foreground-service
            // notification; harmless (and ignored) otherwise.
            .setColorized(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setStyle(
                NotificationCompat.CallStyle.forIncomingCall(
                    person,
                    actions.acknowledge,
                    actions.open,
                ).setIsVideo(false),
            )
        } else {
            builder
                .setStyle(NotificationCompat.BigTextStyle().bigText(bigText(content)))
                .addAction(R.drawable.ic_stat_ok, "Acknowledge", actions.acknowledge)
                .addAction(R.drawable.ic_stat_refresh, "Open", actions.open)
        }
        return builder.build()
    }

    /**
     * A black disc with a white exclamation mark, drawn rather than shipped as a
     * drawable so it is exactly the shape the call layout's avatar slot wants: a
     * filled circle that fills its own bounds.
     *
     * Black rather than the down colour, because this notification may be
     * colorised — a red disc on a red card is an invisible disc, which is what
     * the first attempt produced.
     */
    private fun avatarBitmap(size: Int = 168): Bitmap {
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply {
            isAntiAlias = true
            color = 0xFF120306.toInt()
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        paint.color = 0xFFFFFFFF.toInt()
        val barWidth = size * 0.11f
        val top = size * 0.26f
        canvas.drawRoundRect(
            size / 2f - barWidth / 2f,
            top,
            size / 2f + barWidth / 2f,
            size * 0.60f,
            barWidth / 2f,
            barWidth / 2f,
            paint,
        )
        canvas.drawCircle(size / 2f, size * 0.72f, barWidth * 0.62f, paint)
        return bitmap
    }

    // ---- 7 · RED_FULL_BLEED -------------------------------------------------

    private fun redFullBleed(
        context: Context,
        channelId: String,
        content: UrgentPageContent,
        actions: UrgentPageActions,
    ): Notification {
        val views = urgentViews(context, content)
        return base(context, channelId, content, actions)
            .setContentTitle("${content.monitorName} is down")
            .setContentText(content.headline)
            .setCustomContentView(views)
            .setCustomHeadsUpContentView(views)
            .setCustomBigContentView(views)
            .setColorized(true)
            .build()
    }

    // ---- 1 · CALL_INCOMING --------------------------------------------------

    private fun callIncoming(
        context: Context,
        channelId: String,
        content: UrgentPageContent,
        actions: UrgentPageActions,
    ): Notification {
        val person = monitorAsPerson(context, content)
        val builder = base(context, channelId, content, actions)
            .setContentTitle(content.monitorName)
            .setContentText(content.headline)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setStyle(
                NotificationCompat.CallStyle.forIncomingCall(
                    person,
                    // "Decline" is Acknowledge: the red button silences the page.
                    actions.acknowledge,
                    // "Answer" opens the monitor.
                    actions.open,
                ).setIsVideo(false),
            )
        } else {
            builder
                .setStyle(NotificationCompat.BigTextStyle().bigText(bigText(content)))
                .addAction(R.drawable.ic_stat_ok, "Acknowledge", actions.acknowledge)
                .addAction(R.drawable.ic_stat_refresh, "Open", actions.open)
        }
        return builder.build()
    }

    // ---- 2 · CALL_ONGOING ---------------------------------------------------

    private fun callOngoing(
        context: Context,
        channelId: String,
        content: UrgentPageContent,
        actions: UrgentPageActions,
    ): Notification {
        val person = monitorAsPerson(context, content)
        val builder = base(context, channelId, content, actions)
            .setContentTitle(content.monitorName)
            .setContentText("Down for ${content.downFor} · ${content.headline}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setStyle(
                NotificationCompat.CallStyle.forOngoingCall(person, actions.acknowledge),
            ).addAction(R.drawable.ic_stat_refresh, "Re-check", actions.recheck)
        } else {
            builder
                .setStyle(NotificationCompat.BigTextStyle().bigText(bigText(content)))
                .addAction(R.drawable.ic_stat_ok, "Acknowledge", actions.acknowledge)
        }
        return builder.build()
    }

    // ---- 3 · RED_BANNER -----------------------------------------------------

    private fun redBanner(
        context: Context,
        channelId: String,
        content: UrgentPageContent,
        actions: UrgentPageActions,
    ): Notification = base(context, channelId, content, actions)
        .setContentTitle("${content.monitorName} is DOWN")
        .setContentText("${content.headline} · down for ${content.downFor}")
        .setStyle(NotificationCompat.BigTextStyle().bigText(bigText(content)))
        .setColorized(true)
        .setSubText("URGENT")
        .addAction(R.drawable.ic_stat_ok, "Acknowledge", actions.acknowledge)
        .addAction(R.drawable.ic_stat_refresh, "Re-check", actions.recheck)
        .build()

    // ---- 4 · LOUD_STANDARD --------------------------------------------------

    private fun loudStandard(
        context: Context,
        channelId: String,
        content: UrgentPageContent,
        actions: UrgentPageActions,
    ): Notification = base(context, channelId, content, actions)
        .setContentTitle("🔴 ${content.monitorName} is down")
        .setContentText("${content.headline} · ${content.failedChecks} failed checks")
        .setStyle(NotificationCompat.BigTextStyle().bigText(bigText(content)))
        .setSubText("URGENT · repeats every ${content.repeatMinutes.coerceAtLeast(1)} min")
        .addAction(R.drawable.ic_stat_ok, "Acknowledge", actions.acknowledge)
        .addAction(R.drawable.ic_stat_refresh, "Re-check", actions.recheck)
        .build()

    // ---- 5 · CUSTOM_DECORATED -----------------------------------------------

    private fun customDecorated(
        context: Context,
        channelId: String,
        content: UrgentPageContent,
        actions: UrgentPageActions,
    ): Notification {
        val heads = urgentViews(context, content)
        return base(context, channelId, content, actions)
            .setContentTitle("${content.monitorName} is down")
            .setContentText(content.headline)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(heads)
            .setCustomHeadsUpContentView(heads)
            .setCustomBigContentView(heads)
            .addAction(R.drawable.ic_stat_ok, "Acknowledge", actions.acknowledge)
            .addAction(R.drawable.ic_stat_refresh, "Re-check", actions.recheck)
            .build()
    }

    // ---- shared -------------------------------------------------------------

    /**
     * Everything the five have in common.
     *
     * `setOnlyAlertOnce(false)` plus a stable id is what makes a repeat re-alert
     * in place instead of stacking; `CATEGORY_ALARM` is what lets a DND rule let
     * it through once policy access exists.
     */
    private fun base(
        context: Context,
        channelId: String,
        content: UrgentPageContent,
        actions: UrgentPageActions,
    ): NotificationCompat.Builder = NotificationCompat.Builder(context, channelId)
        .setSmallIcon(R.drawable.ic_stat_alert)
        .setColor(DOWN_COLOR)
        .setCategory(NotificationCompat.CATEGORY_CALL)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setContentIntent(actions.open)
        .setOngoing(true)
        .setAutoCancel(false)
        .setOnlyAlertOnce(false)
        .setShowWhen(true)
        .setWhen(System.currentTimeMillis())
        .also { builder ->
            actions.fullScreen?.let { builder.setFullScreenIntent(it, true) }
        }

    /** The red band shared by the two custom-view variants. */
    private fun urgentViews(context: Context, content: UrgentPageContent): RemoteViews =
        RemoteViews(context.packageName, R.layout.notification_urgent).apply {
            setTextViewText(R.id.urgent_name, "${content.monitorName} is DOWN")
            setTextViewText(
                R.id.urgent_detail,
                "${content.headline} · down for ${content.downFor}",
            )
            setTextViewText(
                R.id.urgent_meta,
                "${content.failedChecks} failed checks · repeats every " +
                    "${content.repeatMinutes.coerceAtLeast(1)} min",
            )
        }

    private fun monitorAsPerson(context: Context, content: UrgentPageContent): Person =
        Person.Builder()
            .setName(content.monitorName)
            .setImportant(true)
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_stat_alert))
            .build()

    private fun bigText(content: UrgentPageContent): String = buildString {
        append(content.headline)
        append("\nDown for ").append(content.downFor)
        append(" · ").append(content.failedChecks).append(" failed checks")
        if (content.reminderNumber > 0) append("\nReminder #").append(content.reminderNumber)
        if (content.url.isNotBlank()) append("\n\n").append(content.url)
    }
}
