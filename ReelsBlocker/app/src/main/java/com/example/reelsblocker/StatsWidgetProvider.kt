package com.example.reelsblocker

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.widget.RemoteViews

/**
 * Home-screen widget #1: the time-per-category donut, cut straight out of
 * the app's Home tab and made the whole widget -- the chart (plus its
 * category legend) is rendered as one bitmap that fills the entire widget
 * surface, so resizing the widget resizes the chart with it. Wide shapes
 * get donut-left/legend-right like the in-app layout; square/tall shapes
 * get a full-bleed donut with the legend inside the ring's hole. Rendered
 * per widget instance at that instance's own reported size (see
 * onAppWidgetOptionsChanged), since two instances can be resized
 * differently. Scoped to Instagram only, same as the in-app default view.
 */
class StatsWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        pushUpdate(context, force = true)
    }

    // Fires when the user finishes dragging a resize handle -- re-render
    // at the new dimensions so the chart really does fill the new shape.
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
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

        // Bitmap pixels per reported dp of widget size -- 2x keeps text
        // and arcs crisp without allocating huge bitmaps for big widgets.
        private const val PX_PER_DP = 2

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

                val times = TimeStats.today(context, WIDGET_APP_ID)
                val openIntent = Intent(context, MainActivity::class.java).apply {
                    action = MainActivity.ACTION_OPEN_HOME
                }
                val pending = PendingIntent.getActivity(
                    context, 0, openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                for (id in ids) {
                    val options = manager.getAppWidgetOptions(id)
                    // Portrait home screens use MIN_WIDTH x MAX_HEIGHT as
                    // the widget's actual cell size; 180dp fallback when
                    // the launcher hasn't reported options yet.
                    val wDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)?.takeIf { it > 0 } ?: 180
                    val hDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)?.takeIf { it > 0 } ?: 180

                    val views = RemoteViews(context.packageName, R.layout.widget_donut)
                    views.setImageViewBitmap(R.id.widgetDonutImage, renderChart(context, times, wDp, hDp))
                    views.setOnClickPendingIntent(R.id.widgetDonutRoot, pending)
                    manager.updateAppWidget(id, views)
                }
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

        private fun renderChart(context: Context, times: Map<TimeCategory, Long>, wDp: Int, hDp: Int): Bitmap {
            val w = (wDp * PX_PER_DP).coerceIn(200, 1200)
            val h = (hDp * PX_PER_DP).coerceIn(200, 1200)
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            val entries = listOf(
                Triple(TimeCategory.REELS, context.getString(R.string.time_spent_reels), times[TimeCategory.REELS] ?: 0L),
                Triple(TimeCategory.DM, context.getString(R.string.time_spent_dm), times[TimeCategory.DM] ?: 0L),
                Triple(TimeCategory.FEED, context.getString(R.string.time_spent_feed), times[TimeCategory.FEED] ?: 0L),
                Triple(TimeCategory.STORY, context.getString(R.string.time_spent_stories), times[TimeCategory.STORY] ?: 0L),
                Triple(TimeCategory.OTHER, context.getString(R.string.time_spent_other), times[TimeCategory.OTHER] ?: 0L)
            ).filter { (_, _, value) -> value > 0 }
            val total = entries.sumOf { it.third }

            val wide = w > h * 1.35f
            val donutSize = if (wide) h else minOf(w, h)
            val donutLeft = if (wide) 0f else (w - donutSize) / 2f
            val donutTop = (h - donutSize) / 2f
            val stroke = donutSize * 0.13f
            val inset = stroke / 2f + donutSize * 0.02f
            val ringBounds = RectF(
                donutLeft + inset, donutTop + inset,
                donutLeft + donutSize - inset, donutTop + donutSize - inset
            )

            val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = stroke
            }
            arcPaint.color = Color.parseColor("#2A2A2A")
            canvas.drawArc(ringBounds, 0f, 360f, false, arcPaint)
            if (total > 0) {
                var start = -90f
                for ((category, _, value) in entries) {
                    val sweep = 360f * value / total
                    arcPaint.color = Color.parseColor(CATEGORY_COLORS.getValue(category))
                    canvas.drawArc(ringBounds, start, sweep, false, arcPaint)
                    start += sweep
                }
            }

            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.SERIF
            }

            if (entries.isEmpty()) {
                // Nothing tracked yet today -- just the total in the hole.
                textPaint.color = Color.parseColor("#808080")
                textPaint.textAlign = Paint.Align.CENTER
                textPaint.textSize = donutSize * 0.14f
                canvas.drawText(
                    formatShort(0),
                    donutLeft + donutSize / 2f,
                    donutTop + donutSize / 2f + textPaint.textSize * 0.35f,
                    textPaint
                )
                return bitmap
            }

            if (wide) {
                // Legend column to the right of the donut, like the app.
                val legendLeft = donutLeft + donutSize + w * 0.04f
                val legendWidth = w - legendLeft - w * 0.02f
                val textSize = (h * 0.105f).coerceAtMost(legendWidth * 0.11f)
                drawLegend(canvas, entries, legendLeft, h / 2f, legendWidth, textSize, textPaint)
            } else {
                // Legend inside the ring's hole.
                val holeRadius = donutSize / 2f - stroke - inset
                val maxWidth = holeRadius * 1.7f
                val lineCap = (holeRadius * 1.5f) / entries.size
                val textSize = minOf(lineCap / 1.6f, maxWidth * 0.14f, donutSize * 0.075f)
                drawLegend(
                    canvas, entries,
                    donutLeft + donutSize / 2f - maxWidth / 2f,
                    donutTop + donutSize / 2f,
                    maxWidth, textSize, textPaint
                )
            }
            return bitmap
        }

        // Rows of "• Label   12m", vertically centered on centerY.
        private fun drawLegend(
            canvas: Canvas,
            entries: List<Triple<TimeCategory, String, Long>>,
            left: Float,
            centerY: Float,
            width: Float,
            textSize: Float,
            textPaint: Paint
        ) {
            textPaint.textSize = textSize
            val lineHeight = textSize * 1.5f
            var y = centerY - (entries.size - 1) * lineHeight / 2f + textSize * 0.35f
            val dotRadius = textSize * 0.28f
            val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            for ((category, label, value) in entries) {
                dotPaint.color = Color.parseColor(CATEGORY_COLORS.getValue(category))
                canvas.drawCircle(left + dotRadius, y - textSize * 0.32f, dotRadius, dotPaint)
                textPaint.textAlign = Paint.Align.LEFT
                textPaint.color = Color.parseColor("#B0B0B0")
                canvas.drawText(label, left + dotRadius * 2f + textSize * 0.35f, y, textPaint)
                textPaint.textAlign = Paint.Align.RIGHT
                textPaint.color = Color.WHITE
                canvas.drawText(formatShort(value), left + width, y, textPaint)
                y += lineHeight
            }
        }
    }
}
