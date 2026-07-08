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
import android.widget.RemoteViews

/**
 * Home-screen widget #2: the same 7-day blocked-count bar chart the Home
 * tab shows, rendered into a bitmap for the same RemoteViews-can't-host-
 * custom-views reason as the donut widget. Scoped to Instagram only, same
 * as the in-app Home tab's default view.
 */
class BarsWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        pushUpdate(context, force = true)
    }

    companion object {
        private const val WIDGET_APP_ID = "instagram"

        private const val MIN_PUSH_INTERVAL_MS = 30_000L
        @Volatile private var lastPushAt = 0L

        fun pushUpdate(context: Context, force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (!force && now - lastPushAt < MIN_PUSH_INTERVAL_MS) return
            try {
                val manager = AppWidgetManager.getInstance(context) ?: return
                val ids = manager.getAppWidgetIds(ComponentName(context, BarsWidgetProvider::class.java))
                if (ids.isEmpty()) return
                lastPushAt = now

                val views = RemoteViews(context.packageName, R.layout.widget_bars)
                views.setTextViewText(R.id.widgetBarsTitle, context.getString(R.string.last_7_days))
                views.setImageViewBitmap(R.id.widgetBarsImage, renderBars(Stats.last7Days(context, WIDGET_APP_ID)))

                val openIntent = Intent(context, MainActivity::class.java).apply {
                    action = MainActivity.ACTION_OPEN_HOME
                }
                views.setOnClickPendingIntent(
                    R.id.widgetBarsRoot,
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

        // Mirrors MainActivity.renderChart's look (rounded teal bars, gray
        // count/day labels) but drawn onto a bitmap since RemoteViews can't
        // host the dynamically-built LinearLayout of bars the in-app chart
        // uses.
        private fun renderBars(data: List<Pair<String, Int>>): Bitmap {
            val density = 3f
            val width = (280 * density).toInt()
            val height = (150 * density).toInt()
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            val maxCount = (data.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)
            val maxBarHeight = 88f * density
            val labelSpace = 34f * density
            val columnWidth = width / data.size.coerceAtLeast(1).toFloat()
            val barWidth = columnWidth * 0.5f
            val barRadius = 5f * density

            val countPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#808080")
                textSize = 10f * density
                textAlign = Paint.Align.CENTER
            }
            val labelPaint = Paint(countPaint)
            val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)

            for ((i, entry) in data.withIndex()) {
                val (label, count) = entry
                val centerX = columnWidth * i + columnWidth / 2f
                val barHeight = if (count == 0) 4f * density else (10f * density + (count.toFloat() / maxCount) * maxBarHeight)
                val barBottom = height - labelSpace
                val barTop = barBottom - barHeight

                barPaint.color = Color.parseColor(if (count == 0) "#2E2E2E" else "#26A69A")
                val rect = RectF(centerX - barWidth / 2f, barTop, centerX + barWidth / 2f, barBottom)
                canvas.drawRoundRect(rect, barRadius, barRadius, barPaint)
                // Round only the top corners: cover the bottom rounding
                // with a plain rect so the bar meets the label row flat.
                canvas.drawRect(centerX - barWidth / 2f, barBottom - barRadius, centerX + barWidth / 2f, barBottom, barPaint)

                canvas.drawText(count.toString(), centerX, barTop - 6f * density, countPaint)
                canvas.drawText(label, centerX, height - 8f * density, labelPaint)
            }
            return bitmap
        }
    }
}
