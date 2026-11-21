package com.ghostblade.task

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/** Canvas-drawn v5 data views. All three draw cells/bars directly in
 *  onDraw — never hundreds of child views (SHADOW-THREE-DESIGN §1.3). */

/** The habit year tick-grid: 53 columns x 7 rows (row 0 = Monday),
 *  fed by HabitLogic.gridCells (column-major: index = col*7 + row). */
class YearGridView(ctx: Context) : View(ctx) {

    var cells: List<HabitLogic.Cell> = emptyList()
    var colorHex: String = "#d9a441"

    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Float) = (v * density + 0.5f).toInt()
    private val gapPx get() = dp(2f)
    private var cellPx = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        cellPx = if (width > 0) ((width - 52 * gapPx) / 53).coerceAtLeast(1) else dp(8f)
        setMeasuredDimension(width, 7 * cellPx + 6 * gapPx)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val color = try { Color.parseColor(colorHex) } catch (e: Exception) { V5.ACCENT }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = dp(2f).toFloat()
        cells.forEachIndexed { idx, cell ->
            val col = idx / 7
            val row = idx % 7
            val x = (col * (cellPx + gapPx)).toFloat()
            val y = (row * (cellPx + gapPx)).toFloat()
            val rect = RectF(x, y, x + cellPx, y + cellPx)
            when (cell.state) {
                HabitLogic.CellState.DONE -> {
                    paint.style = Paint.Style.FILL
                    paint.color = color
                    canvas.drawRoundRect(rect, r, r, paint)
                }
                HabitLogic.CellState.MISSED -> {
                    paint.style = Paint.Style.FILL
                    paint.color = V5.tint(color, 64)
                    canvas.drawRoundRect(rect, r, r, paint)
                }
                HabitLogic.CellState.OPEN -> {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = dp(1f).toFloat()
                    paint.color = color
                    canvas.drawRoundRect(rect, r, r, paint)
                }
                HabitLogic.CellState.REST -> {
                    paint.style = Paint.Style.FILL
                    paint.color = Color.argb(20, 255, 255, 255)
                    canvas.drawRoundRect(rect, r, r, paint)
                }
                HabitLogic.CellState.FUTURE -> { /* invisible */ }
                HabitLogic.CellState.NONE -> { /* invisible — before the habit existed */ }
            }
        }
    }
}

/** Annual completions heatmap: one year of Db.doneCountByDay data,
 *  weeks as columns, Monday-first rows. Intensity scale per
 *  SHADOW-FOUR-DESIGN §3.7.6 (the governing visual spec): DONE-green
 *  5-step scale — 0 = TRACK, then green at 25% / 45% / 70% / 100%.
 *  (Shadow Three's §3.1 table proposed accent tints but annotated the
 *  choice "[FOUR: … tints]" — Four's written spec wins.) Tapping a
 *  cell reports its date via [onCellTap]. */
class HeatmapView(ctx: Context) : View(ctx) {

    var onCellTap: ((String) -> Unit)? = null

    private var year = 0
    private var counts: Map<String, Int> = emptyMap()
    private var startMonday = ""
    private var cols = 53

    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Float) = (v * density + 0.5f).toInt()
    private val gapPx get() = dp(2f)
    private val labelH get() = dp(14f)
    private val labelW get() = dp(12f)
    private var cellPx = 0

    fun setData(newYear: Int, newCounts: Map<String, Int>) {
        year = newYear
        counts = newCounts
        val jan1 = "%04d-01-01".format(year)
        startMonday = Stats.weekStart(jan1)
        val dec31 = "%04d-12-31".format(year)
        cols = ((Dates.daysBetween(startMonday, dec31) + 7) / 7).coerceAtLeast(1)
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val avail = width - labelW
        cellPx = if (avail > 0) ((avail - (cols - 1) * gapPx) / cols).coerceAtLeast(1) else dp(8f)
        setMeasuredDimension(width, labelH + 7 * cellPx + 6 * gapPx)
    }

    private fun cellColor(count: Int): Int = when {
        count <= 0 -> V5.TRACK
        count == 1 -> V5.tint(V5.DONE, 64)
        count == 2 -> V5.tint(V5.DONE, 115)
        count == 3 -> V5.tint(V5.DONE, 179)
        else -> V5.DONE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (year == 0 || startMonday.isEmpty()) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val text = Paint(Paint.ANTI_ALIAS_FLAG)
        text.color = V5.FAINT
        text.textSize = dp(8f).toFloat()
        val r = dp(2f).toFloat()
        // Month labels above the first column containing each month.
        val months = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        for (m in 0..11) {
            val first = "%04d-%02d-01".format(year, m + 1)
            val col = Dates.daysBetween(startMonday, first) / 7
            if (col < 0) continue
            val x = labelW + col * (cellPx + gapPx)
            canvas.drawText(months[m], x.toFloat(), dp(10f).toFloat(), text)
        }
        // Weekday initials (Mon / Wed / Fri rows).
        val rowLabels = mapOf(0 to "M", 2 to "W", 4 to "F")
        for ((row, s) in rowLabels) {
            val y = labelH + row * (cellPx + gapPx) + cellPx * 0.8f
            canvas.drawText(s, dp(1f).toFloat(), y, text)
        }
        for (col in 0 until cols) {
            for (row in 0..6) {
                val date = Dates.addDays(startMonday, col * 7 + row)
                if (!date.startsWith(year.toString())) continue
                val x = labelW + col * (cellPx + gapPx)
                val y = labelH + row * (cellPx + gapPx)
                paint.style = Paint.Style.FILL
                paint.color = cellColor(counts[date] ?: 0)
                canvas.drawRoundRect(
                    RectF(x.toFloat(), y.toFloat(), (x + cellPx).toFloat(), (y + cellPx).toFloat()),
                    r, r, paint
                )
            }
        }
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_UP && year != 0 && cellPx > 0) {
            val col = ((ev.x - labelW) / (cellPx + gapPx)).toInt()
            val row = ((ev.y - labelH) / (cellPx + gapPx)).toInt()
            if (col in 0 until cols && row in 0..6) {
                val date = Dates.addDays(startMonday, col * 7 + row)
                if (date.startsWith(year.toString())) {
                    onCellTap?.invoke(date)
                    performClick()
                    return true
                }
            }
        }
        return super.onTouchEvent(ev)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}

/** Daily Completed bar chart (SHADOW-THREE-DESIGN §3.2): 7 bars for
 *  one week (Monday-first), count above each bar, day initial below.
 *  [fraction] animates bar heights 0→1 on first show. */
class DailyBarsView(ctx: Context) : View(ctx) {

    var counts: List<Int> = List(7) { 0 }
    var labels: List<String> = listOf("M", "T", "W", "T", "F", "S", "S")
    var fraction: Float = 1f

    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Float) = (v * density + 0.5f).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(118f))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        if (w <= 0f) return
        val max = (counts.maxOrNull() ?: 0).coerceAtLeast(1)
        val topPad = dp(16f).toFloat()
        val bottomPad = dp(16f).toFloat()
        val chartH = height - topPad - bottomPad
        val slotW = w / 7f
        val barW = slotW * 0.46f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val text = Paint(Paint.ANTI_ALIAS_FLAG)
        text.textSize = dp(10f).toFloat()
        text.textAlign = Paint.Align.CENTER
        for (i in 0..6) {
            val cx = slotW * i + slotW / 2f
            val v = counts.getOrElse(i) { 0 }
            val bh = (chartH * (v.toFloat() / max) * fraction).coerceAtLeast(dp(2f).toFloat())
            paint.style = Paint.Style.FILL
            paint.color = if (v == max && v > 0) V5.ACCENT else Color.rgb(58, 58, 65)
            val rect = RectF(cx - barW / 2f, topPad + (chartH - bh), cx + barW / 2f, topPad + chartH)
            canvas.drawRoundRect(rect, dp(4f).toFloat(), dp(4f).toFloat(), paint)
            text.color = if (v == max && v > 0) V5.TEXT else V5.FAINT
            canvas.drawText(v.toString(), cx, topPad - dp(4f).toFloat(), text)
            text.color = V5.FAINT
            canvas.drawText(labels.getOrElse(i) { "" }, cx, height - dp(3f).toFloat(), text)
        }
    }
}

/** Productivity combo chart (SHADOW-FOUR-DESIGN §3.7.3): bars =
 *  completions per period (current/last period ACCENT, the rest
 *  #3A3A41), a trend line in TEXT running over the bars with the
 *  endpoint picked out as an ACCENT ring dot + hairline drop, and a
 *  dashed average line in ACCENT at 50% with its value printed at the
 *  right in ACCENT. Axis labels FAINT; one baseline hairline; no
 *  gridlines, no box chrome (ref-1 discipline). All flat colours. */
class StatsChartView(ctx: Context) : View(ctx) {

    /** Bar values, oldest → newest. */
    var bars: List<Int> = emptyList()
    /** Trend line values aligned with [bars] (same scale). */
    var line: List<Float> = emptyList()
    /** Axis label per bar; empty strings are skipped. */
    var labels: List<String> = emptyList()

    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Float) = (v * density + 0.5f).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(176f))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val n = bars.size
        if (n == 0 || width == 0) return
        val padT = dp(18f).toFloat()   // room for the average value
        val padB = dp(18f).toFloat()   // axis labels
        val base = height - padB
        val chartH = base - padT
        val maxV = maxOf(bars.maxOrNull() ?: 0, (line.maxOrNull() ?: 0f).toInt())
        val scaleMax = maxOf(1, maxV)
        val slotW = width.toFloat() / n
        val barW = (slotW * 0.52f).coerceAtMost(dp(26f).toFloat())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val text = Paint(Paint.ANTI_ALIAS_FLAG)
        text.textSize = dp(10f).toFloat()

        // Bars.
        for (i in 0 until n) {
            val v = bars[i]
            val bh = (chartH * v / scaleMax).coerceAtLeast(if (v > 0) dp(2f).toFloat() else 0f)
            val cx = slotW * i + slotW / 2f
            paint.style = Paint.Style.FILL
            paint.color = if (i == n - 1) V5.ACCENT else Color.rgb(58, 58, 65)
            val rect = RectF(cx - barW / 2f, base - bh, cx + barW / 2f, base)
            canvas.drawRoundRect(rect, dp(3f).toFloat(), dp(3f).toFloat(), paint)
        }
        // Baseline hairline.
        paint.style = Paint.Style.FILL
        paint.color = V5.HAIRLINE
        canvas.drawRect(0f, base, width.toFloat(), base + 1f, paint)

        // Dashed average line + value at the right.
        val avg = bars.average().toFloat()
        if (avg > 0f) {
            val ay = base - chartH * avg / scaleMax
            val dash = Paint(Paint.ANTI_ALIAS_FLAG)
            dash.style = Paint.Style.STROKE
            dash.strokeWidth = dp(1f).toFloat()
            dash.color = V5.tint(V5.ACCENT, 128)
            dash.pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(5f).toFloat(), dp(4f).toFloat()), 0f)
            canvas.drawLine(0f, ay, width.toFloat(), ay, dash)
            text.color = V5.ACCENT
            text.textAlign = Paint.Align.RIGHT
            val avgTxt = "avg " + (if (avg % 1f == 0f) avg.toInt().toString()
                else String.format("%.1f", avg))
            canvas.drawText(avgTxt, width.toFloat(), (ay - dp(4f)).coerceAtLeast(dp(10f).toFloat()), text)
            text.textAlign = Paint.Align.LEFT
        }

        // Trend line + endpoint ring dot with hairline drop.
        if (line.size == n && n > 1) {
            val path = android.graphics.Path()
            for (i in 0 until n) {
                val cx = slotW * i + slotW / 2f
                val y = base - chartH * (line[i] / scaleMax)
                if (i == 0) path.moveTo(cx, y) else path.lineTo(cx, y)
            }
            val lp2 = Paint(Paint.ANTI_ALIAS_FLAG)
            lp2.style = Paint.Style.STROKE
            lp2.strokeWidth = dp(2f).toFloat()
            lp2.strokeJoin = Paint.Join.ROUND
            lp2.strokeCap = Paint.Cap.ROUND
            lp2.color = V5.TEXT
            canvas.drawPath(path, lp2)
            val ex = slotW * (n - 1) + slotW / 2f
            val ey = base - chartH * (line[n - 1] / scaleMax)
            val drop = Paint(Paint.ANTI_ALIAS_FLAG)
            drop.style = Paint.Style.STROKE
            drop.strokeWidth = dp(1f).toFloat()
            drop.color = V5.HAIRLINE
            canvas.drawLine(ex, ey, ex, base, drop)
            paint.style = Paint.Style.FILL
            paint.color = V5.BG
            canvas.drawCircle(ex, ey, dp(4.5f).toFloat(), paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2f).toFloat()
            paint.color = V5.ACCENT
            canvas.drawCircle(ex, ey, dp(4.5f).toFloat(), paint)
        }

        // Axis labels.
        text.color = V5.FAINT
        text.textAlign = Paint.Align.CENTER
        for (i in 0 until n) {
            val lbl = labels.getOrElse(i) { "" }
            if (lbl.isEmpty()) continue
            val cx = slotW * i + slotW / 2f
            canvas.drawText(lbl, cx, height - dp(4f).toFloat(), text)
        }
        text.textAlign = Paint.Align.LEFT
    }
}

/** Goal ring for the Stats §3.7.5 tiles: white-8% track, coloured
 *  arc, centred % in TEXT bold. (GoalRingView draws medal/trophy/pin
 *  centre icons for the legacy Productivity cards — this is the v5
 *  number-centred variant the spec asks for.) */
class V5RingView(ctx: Context) : View(ctx) {

    var fraction: Float = 0f
    var arcColor: Int = V5.ACCENT

    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Float) = (v * density + 0.5f).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = minOf(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
        val s = if (size > 0) size else dp(64f)
        setMeasuredDimension(s, s)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = dp(4f).toFloat()
        val rect = RectF(inset, inset, width - inset, height - inset)
        val track = Paint(Paint.ANTI_ALIAS_FLAG)
        track.style = Paint.Style.STROKE
        track.strokeWidth = dp(5f).toFloat()
        track.color = Color.argb(20, 255, 255, 255)
        canvas.drawArc(rect, 0f, 360f, false, track)
        if (fraction > 0f) {
            val arc = Paint(Paint.ANTI_ALIAS_FLAG)
            arc.style = Paint.Style.STROKE
            arc.strokeWidth = dp(5f).toFloat()
            arc.strokeCap = Paint.Cap.ROUND
            arc.color = arcColor
            canvas.drawArc(rect, -90f, 360f * fraction.coerceIn(0f, 1f), false, arc)
        }
        val text = Paint(Paint.ANTI_ALIAS_FLAG)
        text.color = V5.TEXT
        text.textSize = dp(14f).toFloat()
        text.typeface = android.graphics.Typeface.DEFAULT_BOLD
        text.textAlign = Paint.Align.CENTER
        val label = Math.round(fraction.coerceIn(0f, 1f) * 100).toString() + "%"
        val y = height / 2f - (text.descent() + text.ascent()) / 2f
        canvas.drawText(label, width / 2f, y, text)
    }
}
