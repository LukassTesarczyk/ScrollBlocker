package com.example.reelsblocker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Plain ring (not a filled pie) split into colored arc segments by value.
 * Segments with value 0 are skipped so an empty category doesn't draw a
 * zero-width sliver.
 */
class DonutChartView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    data class Segment(val value: Long, val color: Int)

    private var segments: List<Segment> = emptyList()
    private val density = resources.displayMetrics.density
    private val strokeWidthPx = 16 * density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
        color = 0xFF2A2A2A.toInt()
    }
    private val segmentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
        strokeCap = Paint.Cap.BUTT
    }
    private val bounds = RectF()

    fun setSegments(newSegments: List<Segment>) {
        segments = newSegments
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = strokeWidthPx / 2f
        bounds.set(inset, inset, width - inset, height - inset)
        if (bounds.width() <= 0 || bounds.height() <= 0) return

        val total = segments.sumOf { it.value }
        if (total <= 0) {
            canvas.drawArc(bounds, 0f, 360f, false, trackPaint)
            return
        }

        canvas.drawArc(bounds, 0f, 360f, false, trackPaint)
        var startAngle = -90f
        for (segment in segments) {
            if (segment.value <= 0) continue
            val sweep = 360f * segment.value / total
            segmentPaint.color = segment.color
            canvas.drawArc(bounds, startAngle, sweep, false, segmentPaint)
            startAngle += sweep
        }
    }
}
