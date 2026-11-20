package com.ghostblade.task

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** Circular countdown ring for the Focus Timer, drawn natively on Canvas. */
class FocusRing(ctx: Context) : View(ctx) {
    /** Fraction of time remaining, 0f..1f. */
    var fraction: Float = 1f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var ringColor: Int = Color.WHITE
        set(v) { field = v; invalidate() }
    /** Track (un-elapsed) ring colour — theme-aware via the setter in
     *  renderFocus; the default matches the original dark-theme look. */
    var trackColor: Int = Color.argb(30, 255, 255, 255)
        set(v) { field = v; bgPaint.color = v; invalidate() }

    private val stroke = Glass.dp(ctx, 10f).toFloat()
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        color = Color.argb(30, 255, 255, 255)
        strokeCap = Paint.Cap.ROUND
    }
    private val fgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rect = RectF(stroke, stroke, width - stroke, height - stroke)
        canvas.drawArc(rect, 0f, 360f, false, bgPaint)
        fgPaint.strokeWidth = stroke
        fgPaint.color = ringColor
        canvas.drawArc(rect, -90f, 360f * fraction, false, fgPaint)
    }
}
