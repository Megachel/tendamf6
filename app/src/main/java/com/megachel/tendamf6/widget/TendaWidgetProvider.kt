package com.megachel.tendamf6.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.megachel.tendamf6.R
import com.megachel.tendamf6.data.Prefs
import com.megachel.tendamf6.net.TendaStatus
import com.megachel.tendamf6.net.formatBytes
import com.megachel.tendamf6.ui.MainActivity
import com.megachel.tendamf6.ui.SmsActivity
import com.megachel.tendamf6.work.Scheduler
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "TendaWidget"

class TendaWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        appWidgetIds.forEach { render(context, manager, it) }
        // A widget update is a good moment to make sure polling is scheduled.
        Scheduler.schedulePeriodic(context)
    }

    override fun onEnabled(context: Context) {
        Scheduler.schedulePeriodic(context)
        Scheduler.pollNow(context)
    }

    override fun onDisabled(context: Context) {
        Scheduler.cancelAll(context)
    }

    /** Resizing changes which rows fit, so the widget has to be redrawn. */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle,
    ) {
        super.onAppWidgetOptionsChanged(context, manager, appWidgetId, newOptions)
        render(context, manager, appWidgetId)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            Scheduler.pollNow(context)
            refreshAll(context)
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.megachel.tendamf6.REFRESH"

        /** Below this reported height the operator name is dropped to avoid clipping. */
        private const val COMPACT_BELOW_DP = 72

        /** Below this reported width the type is scaled down; 3x1 measures 276dp. */
        private const val NARROW_BELOW_DP = 220

        /** Charge rounded to the nearest 20%; the lowest two steps fill in red. */
        private val BATTERY_ICONS = intArrayOf(
            R.drawable.ic_battery_0,
            R.drawable.ic_battery_20,
            R.drawable.ic_battery_40,
            R.drawable.ic_battery_60,
            R.drawable.ic_battery_80,
            R.drawable.ic_battery_100,
        )

        /** The modem reports 0..3, so level N lights N+1 of the four bars. */
        private val SIGNAL_ICONS = intArrayOf(
            R.drawable.ic_signal_0,
            R.drawable.ic_signal_1,
            R.drawable.ic_signal_2,
            R.drawable.ic_signal_3,
        )

        /** Redraw every placed widget from the cache. */
        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, TendaWidgetProvider::class.java)
            )
            ids.forEach { render(context, manager, it) }
        }

        private fun render(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_tenda)
            val status = Prefs.lastStatus
            val error = Prefs.lastError

            // Measured on a Pixel: 3x1 reports 276x94dp and 2x1 reports 179x94dp,
            // so a cell row is 94dp and three tight rows fit at either width.
            // OPTION_APPWIDGET_MIN_HEIGHT is the height in LANDSCAPE and MAX_HEIGHT the
            // one in PORTRAIT — not a range. Measuring by the wrong one hid the operator
            // name on a 3x1 widget that had 94dp of room and only needed ~71dp.
            val options = manager.getAppWidgetOptions(widgetId)
            val landscape = context.resources.configuration.orientation ==
                Configuration.ORIENTATION_LANDSCAPE
            val height = options.getInt(
                if (landscape) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT
                else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,
                0,
            )
            // Width is the mirror image: MAX_WIDTH is the landscape one.
            val width = options.getInt(
                if (landscape) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH
                else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,
                0,
            )
            val compact = height in 1 until COMPACT_BELOW_DP
            // At two cells the first row has roughly 164dp of usable width for
            // icon + mode + battery + unread + refresh, which is right at the
            // edge at 15sp — so shrink the type and the padding instead of
            // letting anything ellipsize away.
            val narrow = width in 1 until NARROW_BELOW_DP
            Log.d(
                TAG,
                "widget $widgetId ${width}x$height landscape=$landscape " +
                    "compact=$compact narrow=$narrow"
            )

            when {
                !Prefs.isConfigured -> showMessage(context, views, R.string.widget_setup_needed)
                status == null -> showMessage(context, views, R.string.widget_no_data)
                else -> showStatus(context, views, status, error, compact)
            }

            applyScale(context, views, narrow)
            views.setInt(
                R.id.background, "setImageAlpha", Prefs.backgroundOpacity * 255 / 100
            )

            bindClicks(context, views)
            manager.updateAppWidget(widgetId, views)
        }

        /** RemoteViews cannot change margins below API 31, but type size and padding it can. */
        private fun applyScale(context: Context, views: RemoteViews, narrow: Boolean) {
            val primary = if (narrow) 13f else 15f
            val secondary = if (narrow) 10f else 11f
            for (id in intArrayOf(R.id.mode, R.id.battery, R.id.sms, R.id.refresh)) {
                views.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_SP, primary)
            }
            for (id in intArrayOf(R.id.operator, R.id.traffic, R.id.footer)) {
                views.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_SP, secondary)
            }
            val pad = dp(context, if (narrow) 8 else 10)
            views.setViewPadding(R.id.panel, pad, pad, pad, pad)
        }

        private fun dp(context: Context, value: Int): Int =
            (value * context.resources.displayMetrics.density).toInt()

        private fun showMessage(context: Context, views: RemoteViews, textRes: Int) {
            views.setViewVisibility(R.id.content, View.GONE)
            views.setViewVisibility(R.id.placeholder, View.VISIBLE)
            views.setTextViewText(R.id.placeholder, context.getString(textRes))
        }

        private fun showStatus(
            context: Context,
            views: RemoteViews,
            status: TendaStatus,
            error: String?,
            compact: Boolean,
        ) {
            views.setViewVisibility(R.id.content, View.VISIBLE)
            views.setViewVisibility(R.id.placeholder, View.GONE)

            val bolt = if (status.charging) " ⚡" else ""
            views.setTextViewText(R.id.battery, "${status.battery}%$bolt")
            // Rounded to the nearest 20%: the icon is nine dp tall, so finer
            // steps would not be visible anyway.
            val step = ((status.battery.coerceIn(0, 100) + 10) / 20)
                .coerceIn(0, BATTERY_ICONS.lastIndex)
            views.setImageViewResource(R.id.battery_icon, BATTERY_ICONS[step])

            // The platform's own status-bar indicator is SystemUI-internal
            // (com.android.settingslib.graph.SignalDrawable) and not in the public
            // SDK, so the staircase is our own vector set.
            views.setImageViewResource(
                R.id.signal,
                if (status.online) SIGNAL_ICONS[status.signal.coerceIn(0, 3)]
                else R.drawable.ic_signal_none
            )
            views.setTextViewText(
                R.id.mode,
                if (status.online) status.mode else context.getString(R.string.no_service)
            )
            views.setTextViewText(R.id.operator, if (status.online) status.operator else "")
            views.setViewVisibility(R.id.operator, if (compact) View.GONE else View.VISIBLE)

            views.setTextViewText(R.id.sms, status.unread.toString())

            views.setTextViewText(
                R.id.traffic,
                if (status.hasLimit)
                    context.getString(R.string.traffic_left, formatBytes(status.remainingBytes))
                else
                    context.getString(R.string.traffic_used, formatBytes(status.usedBytes))
            )

            val clock = SimpleDateFormat("HH:mm", Locale.getDefault())
                .format(Date(status.fetchedAt))
            views.setTextViewText(
                R.id.footer,
                // Keep showing cached data even when the fresh poll failed — otherwise
                // the widget would flash an error every time the phone is off that Wi-Fi.
                when {
                    error != null -> context.getString(R.string.footer_stale_short, clock)
                    compact -> context.getString(R.string.footer_short, clock)
                    else -> context.getString(R.string.footer_ok, clock)
                }
            )
        }


        private fun bindClicks(context: Context, views: RemoteViews) {
            views.setOnClickPendingIntent(
                R.id.sms_block, activityIntent(context, SmsActivity::class.java, 1)
            )
            views.setOnClickPendingIntent(
                R.id.root, activityIntent(context, MainActivity::class.java, 2)
            )

            val refresh = Intent(context, TendaWidgetProvider::class.java).setAction(ACTION_REFRESH)
            views.setOnClickPendingIntent(
                R.id.refresh,
                PendingIntent.getBroadcast(
                    context, 3, refresh,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        }

        private fun activityIntent(context: Context, cls: Class<*>, code: Int): PendingIntent {
            val intent = Intent(context, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return PendingIntent.getActivity(
                context, code, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
