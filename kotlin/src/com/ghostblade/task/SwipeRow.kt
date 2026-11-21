package com.ghostblade.task

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Swipe-reveal wrapper for v5 task cards (SHADOW-THREE-DESIGN §2).
 *
 *  Structure: [revealLeft] (complete, shown by a RIGHT swipe) and
 *  [revealRight] (action buttons, shown by a LEFT swipe) sit underneath;
 *  the card content sits on top and translates on X. The wrapper clips
 *  to a rounded outline so the reveal never shows square corners, and
 *  the content the caller installs must be FULLY OPAQUE (Jervis ruling:
 *  no bleed-through mid-swipe).
 *
 *  Gestures are observed in onInterceptTouchEvent so child click targets
 *  (checkbox, star, body) keep working untouched: a tap is never
 *  intercepted, a horizontal-dominant move past touch slop is.
 *  One row open at a time is enforced through the companion registry.
 *  Long-press (400 ms, near-stationary) switches the row into drag mode
 *  for drag-sort: the activity receives drag callbacks and drives
 *  neighbour swaps; [dragBase] lets it re-base the translation when the
 *  row's layout slot changes mid-drag. */
class SwipeRow(ctx: Context) : FrameLayout(ctx) {

    data class Action(val id: String, val glyph: String, val fill: Int, val glyphColor: Int, val label: String = "")

    var onComplete: (() -> Unit)? = null
    var onAction: ((String) -> Unit)? = null
    var onDragStart: (() -> Unit)? = null
    /** Current drag translationY (after any re-basing). */
    var onDragMove: ((Float) -> Unit)? = null
    var onDragEnd: (() -> Unit)? = null
    var dragEnabled: Boolean = false

    /** Added to translationY while dragging; the activity subtracts a
     *  row height here each time the dragged row swaps a layout slot. */
    var dragBase: Float = 0f

    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Float) = (v * density + 0.5f).toInt()

    private val revealLeft = FrameLayout(ctx)
    private val revealRight = LinearLayout(ctx)
    private var contentView: View? = null

    private val slop = dp(8f)
    private val panelWidth get() = dp(60f) * actionCount
    private var actionCount = 0

    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    /** True when the current touch stream began on the revealed action
     *  panel while the row was open. Such a stream belongs to the panel
     *  buttons and must never be intercepted — intercepting it (the old
     *  behaviour: ACTION_DOWN returned isOpen unconditionally) starved
     *  the buttons of every tap, leaving Star/Skip/Date/Focus/Delete
     *  completely dead. */
    private var downOnPanel = false
    private var startTx = 0f
    private var engaged = false
    private var dragging = false
    private var dragStartRawY = 0f
    private var lastMoveX = 0f
    private var lastMoveT = 0L
    private var velocityX = 0f
    var isOpen = false
        private set

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var longPressRun: Runnable? = null

    init {
        revealLeft.visibility = View.INVISIBLE
        revealRight.orientation = LinearLayout.HORIZONTAL
        revealRight.visibility = View.INVISIBLE
        addView(revealLeft, LayoutParams(-1, -1))
        addView(revealRight, LayoutParams(-2, -1).apply { gravity = Gravity.END })
        // Rounded clipping for the whole wrapper (radius matches the v5
        // task card, V5.R_CARD = 20dp).
        clipToOutline = true
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(20f).toFloat())
            }
        }
    }

    /** Configure the left-edge reveal (right swipe = complete). */
    fun setCompleteReveal(glyph: String, fill: Int) {
        revealLeft.setBackgroundColor(fill)
        revealLeft.removeAllViews()
        val t = TextView(context)
        t.text = glyph
        t.textSize = 20f
        t.setTextColor(Color.WHITE)
        t.gravity = Gravity.CENTER
        t.layoutParams = LayoutParams(dp(56f), -1)
        revealLeft.addView(t)
    }

    /** Configure the right-edge action buttons (left swipe). Buttons are
     *  laid out left→right in list order starting at the row's right
     *  edge going inward — pass them in the order they should appear
     *  from the edge: the FIRST item sits closest to the edge. */
    fun setActions(actions: List<Action>) {
        actionCount = actions.size
        revealRight.removeAllViews()
        // The panel is anchored END; adding views in reverse makes the
        // first-listed action the rightmost (closest to the edge).
        for (a in actions.asReversed()) {
            // Glyph over a small label (SHADOW-FOUR §2.6.9 / Three §2:
            // the panel tiles are labelled, not bare glyphs). The whole
            // tile is the click target — the Senior Engineer's panel
            // touch contract is untouched: the stream still starts on
            // this child and is never intercepted.
            val b = LinearLayout(context)
            b.orientation = LinearLayout.VERTICAL
            b.gravity = Gravity.CENTER
            b.setBackgroundColor(a.fill)
            b.contentDescription = a.id
            b.layoutParams = LinearLayout.LayoutParams(dp(60f), -1)
            val g = TextView(context)
            g.text = a.glyph
            g.textSize = 17f
            g.setTextColor(a.glyphColor)
            g.gravity = Gravity.CENTER
            b.addView(g)
            if (a.label.isNotEmpty()) {
                val l = TextView(context)
                l.text = a.label
                l.textSize = 9f
                l.setTextColor(a.glyphColor)
                l.gravity = Gravity.CENTER
                l.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2f) }
                b.addView(l)
            }
            b.setOnClickListener {
                onAction?.invoke(a.id)
                close()
            }
            revealRight.addView(b)
        }
    }

    fun setContent(v: View) {
        contentView?.let { removeView(it) }
        contentView = v
        addView(v, LayoutParams(-1, -2))
    }

    fun close(animate: Boolean = true) {
        if (!isOpen && contentView?.translationX == 0f) return
        isOpen = false
        if (openRow === this) openRow = null
        animateTx(0f, animate)
    }

    private fun openPanel() {
        isOpen = true
        openRow?.takeIf { it !== this }?.close()
        openRow = this
        animateTx(-panelWidth.toFloat(), true)
    }

    private fun animateTx(to: Float, animate: Boolean) {
        val c = contentView ?: return
        if (animate) {
            c.animate().translationX(to).setDuration(180)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction { updateRevealVisibility() }.start()
        } else {
            c.translationX = to
            updateRevealVisibility()
        }
    }

    private fun updateRevealVisibility() {
        val tx = contentView?.translationX ?: 0f
        revealLeft.visibility = if (tx > 1f) View.VISIBLE else View.INVISIBLE
        revealRight.visibility = if (tx < -1f) View.VISIBLE else View.INVISIBLE
    }

    private fun cancelLongPressTimer() {
        longPressRun?.let { handler.removeCallbacks(it) }
        longPressRun = null
    }

    /** Left edge (in this row's coordinates) of the revealed action
     *  panel. The panel is anchored END and its width is exactly
     *  [panelWidth]; prefer its laid-out position when available. */
    private fun panelLeft(): Float =
        if (revealRight.width > 0) revealRight.left.toFloat()
        else (width - panelWidth).toFloat()

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        val c = contentView ?: return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; downTime = ev.eventTime
                startTx = c.translationX
                engaged = false; dragging = false
                velocityX = 0f; lastMoveX = ev.x; lastMoveT = ev.eventTime
                cancelLongPressTimer()
                // A touch that lands on the revealed panel belongs to
                // its buttons: do not intercept this stream at all.
                downOnPanel = isOpen && actionCount > 0 && ev.x >= panelLeft()
                if (dragEnabled && !isOpen) {
                    longPressRun = Runnable {
                        dragging = true
                        dragBase = 0f
                        dragStartRawY = ev.rawY
                        parent?.requestDisallowInterceptTouchEvent(true)
                        onDragStart?.invoke()
                    }
                    handler.postDelayed(longPressRun!!, 400)
                }
                // An open row swallows the next tap on the CARD (it
                // just closes) — but never a tap on the panel itself.
                return isOpen && !downOnPanel
            }
            MotionEvent.ACTION_MOVE -> {
                if (downOnPanel) return false
                if (dragging) return true
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (Math.abs(dx) > slop || Math.abs(dy) > slop) cancelLongPressTimer()
                if (!engaged && Math.abs(dx) > slop && Math.abs(dx) > 1.5f * Math.abs(dy)) {
                    engaged = true
                    cancelLongPressTimer()
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                return engaged
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelLongPressTimer()
                // Intercepting the UP of a panel stream would deliver
                // ACTION_CANCEL to the button and kill its click.
                return engaged || dragging || (isOpen && !downOnPanel)
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val c = contentView ?: return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; startTx = c.translationX
                lastMoveX = ev.x; lastMoveT = ev.eventTime; velocityX = 0f
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging) {
                    translationY = dragBase + (ev.rawY - dragStartRawY)
                    onDragMove?.invoke(translationY)
                    return true
                }
                val dt = (ev.eventTime - lastMoveT).coerceAtLeast(1)
                velocityX = 0.7f * velocityX + 0.3f * ((ev.x - lastMoveX) / dt * 1000f)
                lastMoveX = ev.x; lastMoveT = ev.eventTime
                val nx = (startTx + (ev.x - downX)).coerceIn(-panelWidth.toFloat(), width * 0.75f)
                c.translationX = nx
                updateRevealVisibility()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelLongPressTimer()
                if (dragging) {
                    dragging = false
                    translationY = 0f
                    onDragEnd?.invoke()
                    return true
                }
                val tx = c.translationX
                when {
                    // Open row + tap (no real movement): just close.
                    isOpen && Math.abs(ev.x - downX) < slop && Math.abs(ev.y - downY) < slop -> close()
                    tx >= width * 0.35f -> {
                        // Complete: leave the row where it is; the
                        // activity re-renders the list without it.
                        onComplete?.invoke()
                    }
                    tx <= -dp(120f) || velocityX < -400f -> openPanel()
                    else -> close()
                }
                return true
            }
        }
        return true
    }

    companion object {
        private var openRow: SwipeRow? = null
        fun closeAny() { openRow?.close(); openRow = null }
    }
}
