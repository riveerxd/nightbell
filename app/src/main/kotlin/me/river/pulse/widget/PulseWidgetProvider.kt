package me.river.pulse.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import me.river.pulse.MainActivity
import me.river.pulse.R
import me.river.pulse.data.Pulse
import me.river.pulse.domain.Health
import me.river.pulse.domain.Summary
import kotlinx.coroutines.launch

/**
 * The home-screen widget.
 *
 * Built with plain [RemoteViews] and `addView` rather than a collection widget:
 * a `RemoteViewsService` buys scrolling at the cost of a second process hop, a
 * factory lifecycle and a class of "widget stuck on stale data" bugs. A short
 * worst-first list is what someone actually wants glanceable on a home screen,
 * so the rows are built inline and capped by [WidgetConfig.maxRows].
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
class PulseWidgetProvider : AppWidgetProvider() {

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
        val graph = Pulse.install(context)
        graph.appScope.launch { WidgetConfigStore.delete(context.applicationContext, appWidgetIds) }
    }

    override fun onRestored(context: Context, oldWidgetIds: IntArray, newWidgetIds: IntArray) {
        val graph = Pulse.install(context)
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
        private const val TAG = "PulseWidget"
        const val ACTION_REFRESH = "me.river.pulse.action.WIDGET_REFRESH"

        /** Re-renders every placed widget. Cheap and safe to call after any check. */
        fun refresh(context: Context) {
            val app = context.applicationContext
            runCatching {
                val manager = AppWidgetManager.getInstance(app) ?: return
                val ids = manager.getAppWidgetIds(ComponentName(app, PulseWidgetProvider::class.java))
                ids.forEach { render(app, manager, it) }
            }.onFailure { Log.w(TAG, "Widget refresh failed", it) }
        }

        private fun render(context: Context, manager: AppWidgetManager, appWidgetId: Int) {
            val app = context.applicationContext
            val config = WidgetConfigStore.loadBlocking(app, appWidgetId)
            val graph = Pulse.install(app)
            val snapshot = graph.store.snapshot.value
            val fleet = Summary.of(snapshot.monitors, snapshot.runtimes)
            runCatching {
                manager.updateAppWidget(appWidgetId, build(app, config, fleet, appWidgetId))
            }.onFailure { Log.e(TAG, "Could not update widget $appWidgetId", it) }
        }

        @JvmOverloads
        internal fun build(
            context: Context,
            config: WidgetConfig,
            fleet: Summary.Fleet,
            appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID,
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_pulse)
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

            // The header row stays when the title is off, so the settings cog does
            // not disappear with it — a hidden title must not also hide the only
            // route back into the widget's own configuration.
            val showCog = config.showSettingsButton && appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID
            views.setViewVisibility(
                R.id.widget_header,
                if (config.showTitle || showCog) VISIBLE else GONE,
            )
            views.setViewVisibility(R.id.widget_logo, if (config.showTitle) VISIBLE else GONE)
            views.setViewVisibility(R.id.widget_title, if (config.showTitle) VISIBLE else GONE)
            views.setViewVisibility(R.id.widget_headline, if (config.showTitle) VISIBLE else GONE)
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

            views.removeAllViews(R.id.widget_rows)
            val shown = fleet.ranked
                .filter { !config.onlyProblems || it.health != Health.UP }
                .take(config.maxRows.coerceIn(1, MAX_ROWS))

            shown.forEach { entry -> views.addView(R.id.widget_rows, row(context, config, palette, entry)) }

            val emptyText = when {
                fleet.total == 0 -> "No monitors yet — tap to add one."
                shown.isEmpty() -> "Everything is healthy."
                else -> ""
            }
            views.setViewVisibility(R.id.widget_empty, if (emptyText.isEmpty()) GONE else VISIBLE)
            if (emptyText.isNotEmpty()) views.setTextViewText(R.id.widget_empty, emptyText)

            val hidden = fleet.ranked.size - shown.size
            val footer = buildString {
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

        private fun row(
            context: Context,
            config: WidgetConfig,
            palette: WidgetPalette,
            entry: Summary.Entry,
        ): RemoteViews {
            val row = RemoteViews(context.packageName, R.layout.widget_row)
            val color = healthColor(entry.health)
            row.setInt(R.id.row_dot, "setColorFilter", color)
            row.setTextViewText(
                R.id.row_name,
                if (entry.urgentNagging) "⚠ ${entry.name}" else entry.name,
            )
            row.setTextColor(R.id.row_name, palette.primary)
            row.setTextColor(R.id.row_value, if (entry.health == Health.UP) palette.secondary else color)
            row.setTextViewText(
                R.id.row_value,
                when {
                    entry.health == Health.DOWN -> "DOWN"
                    entry.health == Health.PAUSED -> "PAUSED"
                    entry.health == Health.UNKNOWN -> "—"
                    entry.latencyMs > 0 -> "${entry.latencyMs} ms"
                    else -> entry.health.label
                },
            )
            if (config.density == WidgetDensity.DETAILED) {
                row.setViewVisibility(R.id.row_meta, VISIBLE)
                row.setTextColor(R.id.row_meta, palette.tertiary)
                row.setTextViewText(
                    R.id.row_meta,
                    entry.message.ifBlank { entry.host },
                )
            } else {
                row.setViewVisibility(R.id.row_meta, GONE)
            }
            row.setOnClickPendingIntent(R.id.row_root, openMonitor(context, entry.id))
            return row
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
                .setData(android.net.Uri.parse("pulse://monitor/$monitorId"))
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

        /** Opens this widget's own configuration. See `widget_pulse_info.xml`. */
        private fun openConfig(context: Context, appWidgetId: Int): PendingIntent {
            val intent = Intent(context, WidgetConfigActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                // PendingIntent equality ignores extras, so without a distinct data
                // URI every placed widget's cog would share one intent and configure
                // whichever widget was rendered last.
                .setData(android.net.Uri.parse("pulse://widget/$appWidgetId"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            return PendingIntent.getActivity(
                context,
                appWidgetId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /** Mirrors `PulseColors`; RemoteViews needs plain ints, not Compose Colors. */
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
        const val MAX_ROWS = 10
    }
}
