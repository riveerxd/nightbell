package me.river.nightbell.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import me.river.nightbell.MainActivity
import me.river.nightbell.R
import me.river.nightbell.data.Nightbell
import me.river.nightbell.domain.Health
import me.river.nightbell.domain.Summary
import kotlinx.coroutines.launch

/**
 * The home-screen widget.
 *
 * Built with plain [RemoteViews] and `addView` rather than a collection widget:
 * a `RemoteViewsService` buys scrolling at the cost of a second process hop, a
 * factory lifecycle and a class of "widget stuck on stale data" bugs. A short
 * worst-first list is what someone actually wants glanceable on a home screen,
 * so the rows are built inline: as many as the size the launcher reports can
 * hold, or fewer where [WidgetConfig.maxRows] pins a number.
 *
 * ### Launcher limitations worth knowing
 *  - `updatePeriodMillis` is clamped to 30 minutes by the platform, so the
 *    periodic refresh is a floor, not a cadence. Real freshness comes from
 *    [refresh], which every completed check calls.
 *  - Some launchers (and all of them, during a restore) hand out new
 *    `appWidgetId`s. [onRestored] remaps saved configs onto the new ids.
 *  - Row taps deep-link into the detail screen. A handful of third-party
 *    launchers swallow per-child click intents inside a widget; the whole-widget
 *    tap target always works as a fallback.
 */
class NightbellWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { id -> render(context, manager, id) }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle?,
    ) {
        render(context, manager, appWidgetId)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val graph = Nightbell.install(context)
        graph.appScope.launch { WidgetConfigStore.delete(context.applicationContext, appWidgetIds) }
    }

    override fun onRestored(context: Context, oldWidgetIds: IntArray, newWidgetIds: IntArray) {
        val graph = Nightbell.install(context)
        graph.appScope.launch {
            WidgetConfigStore.remap(context.applicationContext, oldWidgetIds, newWidgetIds)
            refresh(context)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) refresh(context)
    }

    companion object {
        private const val TAG = "NightbellWidget"
        const val ACTION_REFRESH = "me.river.nightbell.action.WIDGET_REFRESH"

        /** Re-renders every placed widget. Cheap and safe to call after any check. */
        fun refresh(context: Context) {
            val app = context.applicationContext
            runCatching {
                val manager = AppWidgetManager.getInstance(app) ?: return
                val ids = manager.getAppWidgetIds(ComponentName(app, NightbellWidgetProvider::class.java))
                ids.forEach { render(app, manager, it) }
            }.onFailure { Log.w(TAG, "Widget refresh failed", it) }
        }

        private fun render(context: Context, manager: AppWidgetManager, appWidgetId: Int) {
            val app = context.applicationContext
            val config = WidgetConfigStore.loadBlocking(app, appWidgetId)
            val graph = Nightbell.install(app)

            // "Not loaded yet" is not "no monitors".
            //
            // `onUpdate` arrives in whatever process the launcher wakes, and the
            // 30-minute `updatePeriodMillis` is enough to make that a cold one. The
            // store's first read from disk is asynchronous, so rendering here would
            // paint "No monitors yet — tap to add one." over a working fleet — and
            // nothing re-rendered until the next check completed.
            //
            // Leaving the previous render in place is the correct fallback: the
            // store's own collector fires a refresh the moment the load lands, which
            // is milliseconds away.
            if (!graph.store.loaded) {
                Log.i(TAG, "Store still loading; leaving widget $appWidgetId as it is")
                return
            }
            val snapshot = graph.store.snapshot.value
            val fleet = Summary.of(
                snapshot.monitors,
                snapshot.runtimes,
                fleetPaused = snapshot.pause.stopsChecks(System.currentTimeMillis()),
            )
            val size = measure(manager, appWidgetId)
            runCatching {
                manager.updateAppWidget(
                    appWidgetId,
                    build(app, config, fleet, appWidgetId, size.first, size.second),
                )
            }.onFailure { Log.e(TAG, "Could not update widget $appWidgetId", it) }
        }

        /**
         * The widget's size in dp, as width to height.
         *
         * MIN_WIDTH with MAX_HEIGHT is the portrait box: the launcher reports the two
         * extremes of each axis for the two orientations, and a widget is nearly always
         * viewed in the one the phone is held in. Zeroes mean the launcher has not
         * reported yet — [WidgetLayout.plan] treats that as "one column", and
         * `onAppWidgetOptionsChanged` arrives with real numbers moments later.
         */
        private fun measure(manager: AppWidgetManager, appWidgetId: Int): Pair<Int, Int> =
            runCatching {
                val options = manager.getAppWidgetOptions(appWidgetId) ?: return 0 to 0
                options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) to
                    options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
            }.getOrDefault(0 to 0)

        @JvmOverloads
        internal fun build(
            context: Context,
            config: WidgetConfig,
            fleet: Summary.Fleet,
            appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID,
            widthDp: Int = 0,
            heightDp: Int = 0,
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_nightbell)
            val palette = config.palette

            // Colour and opacity are applied to a tintable ImageView rather than set
            // as a background resource, which is the only way to get an arbitrary
            // surface on API 26+. `setColorFilter` wants opaque RGB and
            // `setImageAlpha` carries the transparency, so the two are split apart
            // here — passing a translucent colour to setColorFilter would tint by
            // the *filter's* alpha and look nothing like the swatch.
            views.setInt(R.id.widget_surface, "setColorFilter", opaque(palette.background))
            views.setInt(R.id.widget_surface, "setImageAlpha", alphaOf(palette.background))
            views.setInt(R.id.widget_surface_border, "setColorFilter", opaque(palette.border))
            views.setInt(R.id.widget_surface_border, "setImageAlpha", alphaOf(palette.border))

            // The header row survives every piece of it being switched off, so the cog does
            // not disappear with them — a hidden title must not also hide the only route
            // back into the widget's own configuration on API 26–30.
            val showCog = config.showSettingsButton && appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID
            views.setViewVisibility(
                R.id.widget_header,
                if (config.headerVisible || showCog) VISIBLE else GONE,
            )
            views.setViewVisibility(R.id.widget_logo, if (config.showLogo) VISIBLE else GONE)
            views.setViewVisibility(R.id.widget_title, if (config.showTitle) VISIBLE else GONE)
            views.setViewVisibility(R.id.widget_headline, if (config.showHeadline) VISIBLE else GONE)
            views.setViewVisibility(R.id.widget_settings, if (showCog) VISIBLE else GONE)
            views.setInt(R.id.widget_settings, "setColorFilter", opaque(palette.tertiary))
            views.setInt(R.id.widget_settings, "setImageAlpha", alphaOf(palette.tertiary))
            if (showCog) {
                views.setOnClickPendingIntent(R.id.widget_settings, openConfig(context, appWidgetId))
            }

            views.setTextColor(R.id.widget_title, palette.primary)
            views.setTextColor(R.id.widget_headline, palette.secondary)
            views.setTextColor(R.id.widget_footer, palette.tertiary)
            views.setTextColor(R.id.widget_empty, palette.tertiary)
            views.setTextViewText(R.id.widget_headline, fleet.headline)

            views.setOnClickPendingIntent(R.id.widget_root, openDashboard(context))

            views.removeAllViews(R.id.widget_columns)
            val candidates = fleet.ranked
                .filter { !config.onlyProblems || it.health != Health.UP }
                .take(rowCap(config))

            // Plan against what the user asked for, then trim to what actually fits. Doing
            // it the other way round would let the plan size itself to a list it had
            // already truncated, and the widget would settle on one column however short
            // it got dragged.
            val plan = WidgetLayout.plan(
                config = config,
                wanted = candidates.size,
                widthDp = widthDp,
                heightDp = heightDp,
                // A footer only exists if there is a timestamp or something hidden, and
                // whether anything is hidden depends on the plan. Assuming a footer when
                // the timestamp is on is the safe direction: over-reserving costs one row
                // of height, under-reserving clips the last row of every column.
                mightHaveFooter = config.showTimestamp || candidates.size < fleet.ranked.size,
                // Every text size in the widget is sp, so someone reading at 150 per cent
                // gets rows half again as tall in the same box. Without this the plan
                // keeps counting normal-sized rows and the bottom one is drawn off the
                // edge of the surface.
                fontScale = context.resources.configuration.fontScale,
            )
            val shown = candidates.take(plan.capacity)
            val gapPx = (WidgetLayout.COLUMN_GAP_DP * context.resources.displayMetrics.density).toInt()
            WidgetLayout.distribute(shown, plan).forEachIndexed { index, column ->
                val columnViews = RemoteViews(context.packageName, R.layout.widget_column)
                // The gutter goes on every column but the first, as padding rather than a
                // margin: RemoteViews can set padding on a view it owns, but not
                // LayoutParams. Without it a column's "4100 ms" touches the next one's dot.
                if (index > 0) columnViews.setViewPadding(R.id.column_rows, gapPx, 0, 0, 0)
                column.forEach { entry ->
                    columnViews.addView(
                        R.id.column_rows,
                        row(context, config, palette, entry, plan.showValues),
                    )
                }
                views.addView(R.id.widget_columns, columnViews)
            }

            val emptyText = when {
                fleet.total == 0 -> "No monitors yet — tap to add one."
                shown.isEmpty() -> "Everything is healthy."
                else -> ""
            }
            views.setViewVisibility(R.id.widget_empty, if (emptyText.isEmpty()) GONE else VISIBLE)
            if (emptyText.isNotEmpty()) views.setTextViewText(R.id.widget_empty, emptyText)

            val hidden = fleet.ranked.size - shown.size
            val footer = if (plan.suppressFooter) "" else buildString {
                if (config.showTimestamp) {
                    val newest = fleet.entries.maxOfOrNull { it.lastCheckedAt } ?: 0L
                    append(if (newest > 0L) "Checked ${relative(newest)}" else "Not checked yet")
                }
                if (hidden > 0) {
                    if (isNotEmpty()) append(" · ")
                    append("+$hidden more")
                }
            }
            views.setViewVisibility(R.id.widget_footer, if (footer.isBlank()) GONE else VISIBLE)
            if (footer.isNotBlank()) views.setTextViewText(R.id.widget_footer, footer)
            return views
        }

        /**
         * How many monitors are worth handing to the planner.
         *
         * On automatic this is only a safety rail, not a look: the plan trims to what the
         * widget's measured size can hold, and [AUTO_ROWS] is there so a fleet of two
         * hundred does not build two hundred rows into a RemoteViews that has to cross a
         * process boundary. Nothing on a phone-sized widget gets near it.
         */
        internal fun rowCap(config: WidgetConfig): Int =
            if (config.maxRows <= 0) AUTO_ROWS else config.maxRows.coerceIn(1, MAX_ROWS)

        private fun row(
            context: Context,
            config: WidgetConfig,
            palette: WidgetPalette,
            entry: Summary.Entry,
            showValue: Boolean = true,
        ): RemoteViews {
            val row = RemoteViews(context.packageName, R.layout.widget_row)
            val color = healthColor(entry.health)
            row.setInt(R.id.row_dot, "setColorFilter", color)
            row.setTextViewText(
                R.id.row_name,
                if (entry.urgentNagging) "⚠ ${entry.name}" else entry.name,
            )
            row.setTextColor(R.id.row_name, palette.primary)
            // In a narrow column the name is worth more than the number: the dot already
            // says whether this monitor is healthy, and the latency is one tap away.
            //
            // A repository row is the exception, and has to be. Its dot only says the
            // poll is working, so dropping the value leaves a row carrying a name and
            // nothing else, which is what three columns did to every repo on the
            // widget. The number is short ("13" against "4100 ms"), so it costs the
            // name very little to keep it.
            val valueVisible = showValue || entry.isRepo
            row.setViewVisibility(R.id.row_value, if (valueVisible) VISIBLE else GONE)
            row.setTextColor(R.id.row_value, if (entry.health == Health.UP) palette.secondary else color)
            // A repository row reports the repository, not the round trip.
            //
            // "491 ms" on a GitHub row is the time api.github.com took to answer,
            // which is a fact about GitHub's servers and not about the repo anybody
            // added the monitor for. A failure still wins the slot: when the poll is
            // broken, how broken is the only thing worth the space.
            val stars = entry.stars.takeIf { entry.isRepo && it >= 0 && entry.health == Health.UP }
            row.setViewVisibility(
                R.id.row_value_icon,
                if (valueVisible && stars != null) VISIBLE else GONE,
            )
            if (stars != null) {
                row.setInt(R.id.row_value_icon, "setColorFilter", opaque(palette.star))
                row.setInt(R.id.row_value_icon, "setImageAlpha", alphaOf(palette.star))
            }
            row.setTextViewText(
                R.id.row_value,
                when {
                    entry.health == Health.DOWN -> "DOWN"
                    entry.health == Health.PAUSED -> "PAUSED"
                    entry.health == Health.UNKNOWN -> "—"
                    stars != null -> stars.toString()
                    entry.isRepo -> repoValue(entry)
                    entry.latencyMs > 0 -> "${entry.latencyMs} ms"
                    else -> entry.health.label
                },
            )
            if (config.density == WidgetDensity.DETAILED) {
                row.setViewVisibility(R.id.row_meta, VISIBLE)
                row.setTextColor(R.id.row_meta, palette.tertiary)
                row.setTextViewText(
                    R.id.row_meta,
                    if (entry.isRepo) repoMeta(entry) else entry.message.ifBlank { entry.host },
                )
            } else {
                row.setViewVisibility(R.id.row_meta, GONE)
            }
            row.setOnClickPendingIntent(R.id.row_root, openMonitor(context, entry.id))
            return row
        }

        /**
         * The trailing value for a repository row with no star count to show.
         *
         * A monitor watching only issues or only releases still has something
         * worth a glance; falling through to a latency reading would put the
         * wrong number in the one slot this row has.
         */
        private fun repoValue(entry: Summary.Entry): String = when {
            entry.openIssues >= 0 -> "${entry.openIssues} open"
            entry.releaseTag.isNotBlank() -> entry.releaseTag
            else -> "—"
        }

        /** The detailed row's second line: whatever the star count did not say. */
        private fun repoMeta(entry: Summary.Entry): String = buildString {
            if (entry.openIssues >= 0 && entry.stars >= 0) append(entry.openIssues).append(" open")
            if (entry.releaseTag.isNotBlank()) {
                if (isNotEmpty()) append(" · ")
                append(entry.releaseTag)
            }
            if (isEmpty()) append(entry.host)
        }

        private fun openDashboard(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            return PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun openMonitor(context: Context, monitorId: String): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                // A distinct data URI per monitor: PendingIntent equality ignores
                // extras, so without this every row would share one intent and
                // open whichever monitor happened to be created last.
                .setData(android.net.Uri.parse("nightbell://monitor/$monitorId"))
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_MONITOR_ID, monitorId)
            return PendingIntent.getActivity(
                context,
                monitorId.hashCode() and 0xFFFF,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /** Alpha channel as 0..255, for `setImageAlpha`. */
        internal fun alphaOf(argb: Int): Int = (argb ushr 24) and 0xFF

        /** The same colour at full alpha, for `setColorFilter`. */
        internal fun opaque(argb: Int): Int = argb or 0xFF000000.toInt()

        /** Opens this widget's own configuration. See `widget_nightbell_info.xml`. */
        private fun openConfig(context: Context, appWidgetId: Int): PendingIntent {
            val intent = Intent(context, WidgetConfigActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                // PendingIntent equality ignores extras, so without a distinct data
                // URI every placed widget's cog would share one intent and configure
                // whichever widget was rendered last.
                .setData(android.net.Uri.parse("nightbell://widget/$appWidgetId"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            return PendingIntent.getActivity(
                context,
                appWidgetId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /** Mirrors `NightbellColors`; RemoteViews needs plain ints, not Compose Colors. */
        internal fun healthColor(health: Health): Int = when (health) {
            Health.UP -> 0xFF2FD98A.toInt()
            Health.DEGRADED -> 0xFFFFB020.toInt()
            Health.DOWN -> 0xFFFF4D57.toInt()
            Health.PAUSED -> 0xFF8A8A8A.toInt()
            Health.UNKNOWN -> 0xFF6AA8FF.toInt()
        }

        internal fun relative(epochMs: Long, nowMs: Long = System.currentTimeMillis()): String {
            val delta = (nowMs - epochMs).coerceAtLeast(0L)
            val minutes = delta / 60_000L
            return when {
                minutes < 1 -> "just now"
                minutes < 60 -> "${minutes}m ago"
                minutes < 1_440 -> "${minutes / 60}h ago"
                else -> "${minutes / 1_440}d ago"
            }
        }

        private const val VISIBLE = android.view.View.VISIBLE
        private const val GONE = android.view.View.GONE

        /** The highest number the "Monitors" stepper offers. Above it lies automatic. */
        const val MAX_ROWS = 10

        /** The ceiling automatic will not build past, whatever size the widget is. */
        const val AUTO_ROWS = 30
    }
}
