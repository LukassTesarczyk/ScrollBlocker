package com.example.reelsblocker

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.widget.RemoteViews

/**
 * Home-screen widget #1: the same time-per-category donut the Home tab
 * shows (rendered into a bitmap -- RemoteViews can't host DonutChartView
 * directly, but they can host an ImageView) plus a compact legend of real
 * TextViews so labels stay localized and crisp. Scoped to Instagram only,
 * same as the in-app Home tab's default view.
 *
 * Refreshes: on the system's periodic schedule (widget_donut_info.xml),
 * when the app opens, and throttled from the accessibility service as time
 * accumulates.
 */
class StatsWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        pushUpdate(context, force = true)
    }

    companion object {
        private const val WIDGET_APP_ID = "instagram"

        // Same vivid palette as MainActivity.renderTimeChart.
        val CATEGORY_COLORS = mapOf(
            TimeCategory.REELS to "#A855F7",
            TimeCategory.DM to "#22C55E",
            TimeCategory.FEED to "#3B82F6",
            TimeCategory.STORY to "#EC4899",
            TimeCategory.OTHER to "#70706C"
        )

        // TimeStats.today fires on nearly every accessibility event --
        // redrawing the widget that often would burn battery for nothing,
        // so service-driven refreshes are capped to one per interval.
        private const val MIN_PUSH_INTERVAL_MS = 30_000L
        @Volatile private var lastPushAt = 0L

        fun pushUpdate(context: Context, force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (!force && now - lastPushAt < MIN_PUSH_INTERVAL_MS) return
            try {
                val manager = AppWidgetManager.getInstance(context) ?: return
                val ids = manager.getAppWidgetIds(ComponentName(context, StatsWidgetProvider::class.java))
                if (ids.isEmpty()) return
                lastPushAt = now

                val views = RemoteViews(context.packageName, R.layout.widget_donut)
                val times = TimeStats.today(context, WIDGET_APP_ID)
                views.setImageViewBitmap(R.id.widgetDonutImage, renderDonut(times))

                val entries = listOf(
                    Triple(TimeCategory.REELS, context.getString(R.string.time_spent_reels), 0),
                    Triple(TimeCategory.DM, context.getString(R.string.time_spent_dm), 1),
                    Triple(TimeCategory.FEED, context.getString(R.string.time_spent_feed), 2),
                    Triple(TimeCategory.STORY, context.getString(R.string.time_spent_stories), 3),
                    Triple(TimeCategory.OTHER, context.getString(R.string.time_spent_other), 4)
                )
                val rowIds = listOf(R.id.legendRow0, R.id.legendRow1, R.id.legendRow2, R.id.legendRow3, R.id.legendRow4)
                val dotIds = listOf(R.id.legendDot0, R.id.legendDot1, R.id.legendDot2, R.id.legendDot3, R.id.legendDot4)
                val labelIds = listOf(R.id.legendLabel0, R.id.legendLabel1, R.id.legendLabel2, R.id.legendLabel3, R.id.legendLabel4)
                val valueIds = listOf(R.id.legendValue0, R.id.legendValue1, R.id.legendValue2, R.id.legendValue3, R.id.legendValue4)

                for ((category, label, i) in entries) {
                    val value = times[category] ?: 0L
                    if (value <= 0) {
                        views.setViewVisibility(rowIds[i], View.GONE)
                        continue
                    }
                    views.setViewVisibility(rowIds[i], View.VISIBLE)
                    views.setInt(dotIds[i], "setBackgroundColor", Color.parseColor(CATEGORY_COLORS.getValue(category)))
                    views.setTextViewText(labelIds[i], label)
                    views.setTextViewText(valueIds[i], formatShort(value))
                }

                val openIntent = Intent(context, MainActivity::class.java).apply {
                    action = MainActivity.ACTION_OPEN_HOME
                }
                views.setOnClickPendingIntent(
                    R.id.widgetDonutRoot,
                    PendingIntent.getActivity(
                        context, 0, openIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )

                manager.updateAppWidget(ids, views)
            } catch (_: Exception) {
                // A widget refresh must never take the service down with it.
            }
        }

        private fun formatShort(ms: Long): String {
            val totalMinutes = ms / 60000
            val hours = totalMinutes / 60
            val minutes = totalMinutes % 60
            return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
        }

        // Same palette as the in-app chart (see MainActivity.renderTimeChart).
        private fun renderDonut(times: Map<TimeCategory, Long>): Bitmap {
            val size = 220
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val stroke = 30f
            val inset = stroke / 2f + 4f
            val bounds = RectF(inset, inset, size - inset, size - inset)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = stroke
            }

            paint.color = Color.parseColor("#2A2A2A")
            canvas.drawArc(bounds, 0f, 360f, false, paint)

            val total = times.values.sum()
            if (total > 0) {
                var start = -90f
                for ((category, color) in CATEGORY_COLORS) {
                    val value = times[category] ?: 0L
                    if (value <= 0) continue
                    val sweep = 360f * value / total
                    paint.color = Color.parseColor(color)
                    canvas.drawArc(bounds, start, sweep, false, paint)
                    start += sweep
                }
            }
            return bitmap
        }
    }
}
