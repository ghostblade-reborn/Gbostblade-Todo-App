package com.ghostblade.task

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.Shader
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream

/**
 * GhostBlade Task — fully native Kotlin UI (Phase 2: core screens).
 * No WebView, no HTML/JS: every view is built in Kotlin code.
 */
class MainActivity : Activity() {

    private var view = "today"
    private var sub: String? = null
    private var lastTab = "today"
    /** Screen identity at the last render — entrance animations run only
     *  when it changes, never on same-screen state refreshes. */
    private var lastRenderKey: String? = null
    /** iOS-style sheet easing (cubic-bezier 0.32, 0.72, 0, 1). */
    private val sheetEase = android.view.animation.PathInterpolator(0.32f, 0.72f, 0f, 1f)
    private var editingId: String? = null
    /** Back-stack for sheet sub-navigation (Quick Add → Date picker, …).
     *  Each entry rebuilds the sheet to return to. Cleared whenever a sheet
     *  closes; empty means Back closes the sheet. */
    private val sheetStack = ArrayDeque<() -> View>()
    /** Snapshot of the task as last saved when the editor opened (or after a
     *  stash+save rebuild); the discard guard compares the editor fields
     *  against it. */
    private var editorSnapshot: TaskItem? = null
    /** Set by buildEditor: true when the editor's fields differ from the
     *  snapshot above. Null when no editor is open. */
    private var editorDirtyCheck: (() -> Boolean)? = null
    private var lastDeleted: Pair<TaskItem, Int>? = null

    /* Phase 3 screen state */
    private var boardMode = false
    /* ----- Todoist exact tokens (TEAMONE-ANALYSIS-SCREENS §0.1) — inline in
       MainActivity because Ui.kt is owned by Implementer A in parallel. ----- */
    private val TD_BG = 0xFF212121.toInt()
    private val TD_CARD = 0xFF282828.toInt()
    private val TD_NAV = 0xFF262626.toInt()
    private val TD_PILL = 0xFF472524.toInt()
    private val TD_RED = 0xFFD04349.toInt()
    private val TD_SALMON = 0xFFE56864.toInt()
    private val TD_GREEN = 0xFF2FB457.toInt()
    private val TD_AMBER = 0xFFF09C1F.toInt()
    private val TD_GREY = 0xFF9E9E9E.toInt()
    private val TD_EMPHASIS = 0xFF363636.toInt()
    /* Display-sheet state, per view key (sub ?: view). Group/Sort/Filter ride
       on Team Two's DisplayEngine; Layout + Completed now persist per
       view (B2 — see the disp*For helpers below). */
    /* Display state is PER VIEW (Team Two addendum item 1: Todoist keeps a
       different config per view — Inbox Manual, Today Smart + filters…).
       Layout / Completed / Grouping / Sorting persist per view id in
       Prefs, seeded from Team Two's persisted State.displayLayout /
       displayShowCompleted (which also receive every write, as the
       last-used global). Filter rows stay per-view for the session. */
    private fun dispLayoutFor(key: String): String =
        Prefs.prefs(this).getString("disp_layout_$key", null) ?: Store.state.displayLayout
    private fun setDispLayout(key: String, v: String) {
        Prefs.set(this, "disp_layout_$key", v)
        Store.setDisplayLayout(v)
    }
    private fun dispShowFor(key: String): Boolean =
        if (Prefs.prefs(this).contains("disp_show_$key"))
            Prefs.prefs(this).getBoolean("disp_show_$key", true)
        else Store.state.displayShowCompleted
    private fun setDispShow(key: String, v: Boolean) {
        Prefs.setBool(this, "disp_show_$key", v)
        Store.setDisplayShowCompleted(v)
    }
    private fun dispGroupFor(key: String): GroupMode {
        val n = Prefs.prefs(this).getString("disp_group_$key", null) ?: return GroupMode.NONE
        return try { GroupMode.valueOf(n) } catch (e: Exception) { GroupMode.NONE }
    }
    private fun setDispGroup(key: String, m: GroupMode) = Prefs.set(this, "disp_group_$key", m.name)
    private fun dispSortFor(key: String): SortMode {
        val n = Prefs.prefs(this).getString("disp_sort_$key", null) ?: return SortMode.MANUAL
        return try { SortMode.valueOf(n) } catch (e: Exception) { SortMode.MANUAL }
    }
    private fun setDispSort(key: String, m: SortMode) = Prefs.set(this, "disp_sort_$key", m.name)
    private val dispPrio = mutableMapOf<String, MutableSet<Int>>()
    private val dispDate = mutableMapOf<String, String>()
    private val dispLabel = mutableMapOf<String, String>()
    private val dispAssignee = mutableMapOf<String, String>()
    private var todayOverdueCollapsed = false
    private var upcomingExpanded = false
    private var browseProjectsCollapsed = false
    private var lastReschedule: List<Pair<String, String?>>? = null
    /* ----- B2 state ----- */
    /** Multi-select mode (⋮ → Select tasks): chrome-only crossfade, the
     *  list itself never moves (TEAMONE-ANALYSIS-VIDEO §3.7). */
    private var selectMode = false
    private val selectedIds = mutableSetOf<String>()
    /** Productivity screen tab: "daily" | "weekly" | "karma". */
    private var prodTab = "daily"
    /** True while the open sheet is the Quick Add floating card (its
     *  presenter differs from showSheet: scale/fade, scrim α≈0.50). */
    private var sheetIsQa = false
    private lateinit var mainScroll: ScrollView
    private lateinit var headerHolder: FrameLayout
    private lateinit var headerActions: LinearLayout
    private var calCursor: java.util.Calendar = java.util.Calendar.getInstance()
    private var selDay: String? = null
    private var focusTaskId: String? = null
    private var focusTotal = 25 * 60
    private var focusLeft = 25 * 60
    private var focusRunning = false
    private var focusEndAt = 0L
    private var focusTicker: Runnable? = null
    private var focusTimeText: TextView? = null
    private var focusRing: FocusRing? = null
    private var focusStateText: TextView? = null

    private lateinit var root: FrameLayout
    private lateinit var wallpaperHolder: FrameLayout
    private lateinit var headerTitle: TextView
    private lateinit var headerSub: TextView
    private lateinit var headerProg: View
    private lateinit var headerProgWrap: View
    private lateinit var content: LinearLayout
    private lateinit var navBar: LinearLayout
    private lateinit var sheetRoot: FrameLayout
    private lateinit var undoBar: LinearLayout
    private lateinit var mainColumn: LinearLayout
    private lateinit var fabBtn: TextView
    private lateinit var headerCard: LinearLayout
    private var undoWasVisible = false
    private val handler = android.os.Handler(Looper.getMainLooper())
    private var undoHide: Runnable? = null

    private fun dp(v: Float) = Glass.dp(this, v)

    /* ---------------- view builders ---------------- */

    private fun tv(text: String, size: Float = 15f, color: Int = Glass.text(this@MainActivity), bold: Boolean = false): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = size
        t.setTextColor(color)
        if (bold) t.setTypeface(t.typeface, android.graphics.Typeface.BOLD)
        return t
    }

    private fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
                   l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(w, h)
        p.setMargins(l, t, r, b)
        return p
    }

    private fun glassPanel(radius: Float = 22f, strong: Boolean = false): View {
        val v = View(this)
        v.background = Glass.panel(this, radius, strong)
        return v
    }

    private fun card(padDp: Int = 15): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        l.background = Glass.panel(this, 24f)
        l.setPadding(dp(padDp.toFloat()), dp(padDp.toFloat()), dp(padDp.toFloat()), dp(padDp.toFloat()))
        l.layoutParams = lp(b = dp(10f))
        Glass.elevate(this, l, 24f, 4f)
        return l
    }

    private fun chipBtn(label: String, on: Boolean, click: () -> Unit): TextView {
        val c = tv(label, 13f)
        c.background = Glass.chip(this, on)
        c.setPadding(dp(11f), dp(8f), dp(11f), dp(8f))
        c.setOnClickListener { click() }
        return c
    }

    private fun accentBtn(label: String, click: () -> Unit): TextView {
        val b = tv(label, 15f, 0xFFFFFFFF.toInt(), true)
        b.gravity = Gravity.CENTER
        b.background = Glass.accentGradient(this, 999f)
        b.setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
        b.setOnClickListener { click() }
        return b
    }

    private fun ghostBtn(label: String, click: () -> Unit): TextView {
        val b = tv(label, 15f)
        b.gravity = Gravity.CENTER
        b.background = Glass.chip(this, false)
        b.setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
        b.setOnClickListener { click() }
        return b
    }

    private fun input(hint: String, value: String = "", multi: Boolean = false): EditText {
        val e = EditText(this)
        e.hint = hint
        e.setText(value)
        e.textSize = 15f
        e.setTextColor(Glass.text(this@MainActivity))
        e.setHintTextColor(Glass.muted(this@MainActivity))
        e.background = Glass.panel(this, 13f)
        e.setPadding(dp(12f), dp(11f), dp(12f), dp(11f))
        if (!multi) e.setSingleLine(true)
        e.layoutParams = lp(b = dp(10f))
        return e
    }

    private fun spinner(items: List<String>, selected: Int): Spinner {
        val sp = Spinner(this)
        // Style the selected-item view with the theme text colour — the stock
        // adapter inherits the dark Material theme's near-white text, which is
        // invisible on the Frost White theme's light glass panels. The dropdown
        // popup keeps the system dark styling (readable in every theme).
        val ad = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
            override fun getView(pos: Int, convert: View?, parent: ViewGroup): View {
                val v = super.getView(pos, convert, parent) as TextView
                v.setTextColor(Glass.text(this@MainActivity))
                v.textSize = 15f
                return v
            }
        }
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        sp.adapter = ad
        sp.setSelection(selected.coerceIn(0, items.size - 1))
        sp.background = Glass.panel(this, 13f)
        sp.layoutParams = lp(b = dp(10f))
        return sp
    }

    private fun label(text: String): TextView {
        val l = tv(text, 13f, Glass.muted(this@MainActivity))
        l.layoutParams = lp(t = dp(8f), b = dp(4f))
        return l
    }

    private fun dayGroup(text: String): TextView {
        val l = tv(text, 13f, Glass.muted(this@MainActivity), true)
        l.layoutParams = lp(t = dp(16f), b = dp(8f))
        return l
    }

    private fun emptyView(emoji: String, text: String): View {
        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.VERTICAL
        wrap.gravity = Gravity.CENTER
        wrap.setPadding(dp(20f), dp(38f), dp(20f), dp(38f))
        val glyph = tv(emoji, 30f, Glass.muted(this@MainActivity))
        glyph.gravity = Gravity.CENTER
        val caption = tv(text, 13f, Glass.muted(this@MainActivity))
        caption.gravity = Gravity.CENTER
        caption.layoutParams = lp(t = dp(10f))
        wrap.addView(glyph); wrap.addView(caption)
        return wrap
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    /* ---------------- lifecycle ---------------- */

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        ReminderReceiver.ensureChannel(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 42)
        window.statusBarColor = 0xFF06060A.toInt()
        window.navigationBarColor = 0xFF06060A.toInt()
        Reminders.syncAll(this)
        try { HabitReminders.syncAll(this) } catch (e: Exception) { /* habits re-sync on next open */ }
        LocationReceiver.rearmAll(this)
        buildRoot()
        render()
        lastSeenDay = Dates.today()
    }

    /** The app can stay alive across midnight (left open, or restored from
     *  recents without recreation) — when the day changes under it, roll the
     *  Performance analytics over and re-render instead of showing
     *  yesterday's state until the next cold start. */
    override fun onResume() {
        super.onResume()
        val today = Dates.today()
        if (lastSeenDay.isNotEmpty() && today != lastSeenDay) {
            lastSeenDay = today
            try { Db.rollover(Store.state) } catch (e: Exception) { /* analytics only */ }
            render()
        }
    }

    /** Stop the focus ticker and the undo auto-hide when the activity is
     *  destroyed, so no handler callback fires against dead views or
     *  saves/vibrates after the user has left the app. */
    override fun onDestroy() {
        focusTicker?.let { handler.removeCallbacks(it) }
        undoHide?.let { handler.removeCallbacks(it) }
        super.onDestroy()
    }

    private fun buildRoot() {
        root = FrameLayout(this)
        wallpaperHolder = FrameLayout(this)
        // Todoist list screens are a flat #212121 (ANALYSIS §0.1) — no aurora
        // wallpaper behind them in the replicated default look.
        val bgView = View(this)
        bgView.setBackgroundColor(if (Glass.isV5(this)) V5.BG else TD_BG)
        wallpaperHolder.addView(bgView, FrameLayout.LayoutParams(-1, -1))
        root.addView(wallpaperHolder, FrameLayout.LayoutParams(-1, -1))

        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        mainColumn = column
        root.addView(column, FrameLayout.LayoutParams(-1, -1))

        // header — flat Todoist chrome (ANALYSIS §0.5): the title sits
        // directly on the #212121 background at ~27sp regular weight, with
        // the Display icon and the ⋮ overflow at the right. No card.
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        headerCard = header
        // v5 (§2.1b): the header aligns with the 20dp screen padding.
        if (Glass.isV5(this)) header.setPadding(dp(20f), dp(12f), dp(20f), dp(8f))
        else header.setPadding(dp(16f), dp(12f), dp(8f), dp(8f))
        val hp = FrameLayout.LayoutParams(-1, -2)
        header.layoutParams = hp
        val titleCol = LinearLayout(this)
        titleCol.orientation = LinearLayout.VERTICAL
        titleCol.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        headerTitle = tv("Today", 27f, Glass.text(this@MainActivity), false)
        if (Glass.isV5(this)) {
            // v5 screen titles are T_TITLE: 24sp bold (SHADOW-FOUR
            // §1.6 / §2.1b) — not the Todoist skin's 27sp regular.
            headerTitle.setTextSize(24f)
            headerTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
        }
        headerSub = tv("", 14f, Glass.muted(this@MainActivity))
        headerSub.layoutParams = lp(t = dp(2f))
        val progWrap = FrameLayout(this)
        progWrap.visibility = View.GONE
        val pw = LinearLayout.LayoutParams(-1, dp(6f)); pw.topMargin = dp(10f)
        progWrap.layoutParams = pw
        headerProgWrap = progWrap
        headerProg = View(this)
        progWrap.addView(headerProg, FrameLayout.LayoutParams(0, -1))
        titleCol.addView(headerTitle); titleCol.addView(headerSub); titleCol.addView(progWrap)
        header.addView(titleCol)
        val actions = LinearLayout(this)
        actions.orientation = LinearLayout.HORIZONTAL
        actions.gravity = Gravity.CENTER_VERTICAL
        headerActions = actions
        val dispBtn = tv("▤", 22f, Glass.text(this@MainActivity))
        dispBtn.gravity = Gravity.CENTER
        dispBtn.contentDescription = "Display"
        dispBtn.layoutParams = LinearLayout.LayoutParams(dp(40f), dp(40f))
        dispBtn.setOnClickListener { showDisplaySheet() }
        val overBtn = tv("⋮", 24f, Glass.text(this@MainActivity), true)
        overBtn.gravity = Gravity.CENTER
        overBtn.contentDescription = "More options"
        overBtn.layoutParams = LinearLayout.LayoutParams(dp(40f), dp(40f))
        overBtn.setOnClickListener { showOverflowMenu(it) }
        actions.addView(dispBtn); actions.addView(overBtn)
        header.addView(actions)
        val hh = FrameLayout(this)
        hh.addView(header)
        headerHolder = hh
        column.addView(hh)

        val scroll = ScrollView(this)
        mainScroll = scroll
        scroll.layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        // v5: 20dp screen padding and ONE bottom inset (106dp) so no
        // content ever hides under the floating dock (SHADOW-FOUR §1.4).
        if (Glass.isV5(this)) content.setPadding(dp(20f), dp(6f), dp(20f), dp(V5.CONTENT_BOTTOM_INSET.toFloat()))
        else content.setPadding(dp(8f), dp(6f), dp(8f), dp(120f))
        scroll.addView(content)
        column.addView(scroll)

        // bottom nav — flat full-width bar #262626 attached to the screen
        // bottom (ANALYSIS §0.5): no margins, radius, elevation or frost.
        navBar = LinearLayout(this)
        navBar.orientation = LinearLayout.HORIZONTAL
        navBar.setBackgroundColor(TD_NAV)
        navBar.setPadding(dp(4f), dp(6f), dp(4f), dp(6f))
        val np = FrameLayout.LayoutParams(-1, -2)
        np.gravity = Gravity.BOTTOM
        navBar.layoutParams = np
        root.addView(navBar)
        styleNavBar()

        // FAB — rounded square, flat #D04349 (ANALYSIS §0.2: ~52x50dp,
        // corner radius ~14dp), floating just above the nav bar.
        val fab = tv("+", 24f, 0xFFFFFFFF.toInt(), false)
        fab.gravity = Gravity.CENTER
        fab.background = roundedRect(TD_RED, 14f)
        val fp = FrameLayout.LayoutParams(dp(52f), dp(50f))
        fp.gravity = Gravity.BOTTOM or Gravity.END
        fp.setMargins(0, 0, dp(16f), dp(84f))
        fab.layoutParams = fp
        fab.setOnClickListener { openQuickAdd(null) }
        fab.contentDescription = "Add task"
        Glass.elevate(this, fab, 14f, 8f)
        fabBtn = fab
        // v5 has NO floating FAB (Jervis ruling): Quick Add is the ghost
        // pill on Today + the dock's fixed centre "+".
        if (Glass.isV5(this)) fab.visibility = View.GONE
        root.addView(fab)

        // undo bar
        undoBar = LinearLayout(this)
        undoBar.orientation = LinearLayout.HORIZONTAL
        undoBar.gravity = Gravity.CENTER_VERTICAL
        undoBar.background = Glass.panel(this, 22f, strong = true)
        undoBar.setPadding(dp(16f), dp(10f), dp(10f), dp(10f))
        val up = FrameLayout.LayoutParams(-2, -2)
        up.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        up.bottomMargin = dp(96f)
        undoBar.layoutParams = up
        undoBar.visibility = View.GONE
        Glass.elevate(this, undoBar, 22f, 12f)
        root.addView(undoBar)

        sheetRoot = FrameLayout(this)
        sheetRoot.visibility = View.GONE
        root.addView(sheetRoot, FrameLayout.LayoutParams(-1, -1))

        applyWallpaperFrost()
        applyStatusBar()
        setContentView(root)
    }

    /* ---------------- render ---------------- */

    private val TITLES = mapOf("inbox" to "Inbox", "today" to "Today", "upcoming" to "Upcoming", "browse" to "Browse")

    private fun render() {
        if (sub != "focus") pauseFocus()
        renderHeader()
        // Productivity repaints this with Todoist.PROD_BG; every other
        // screen sits directly on the flat #212121 wallpaper layer.
        content.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        content.removeAllViews()
        val s = sub
        when {
            s != null -> renderSub(s)
            view == "today" -> renderToday()
            view == "inbox" -> {
                val inboxTasks = Store.sortTasks(Store.state.tasks.filter { it.projectId == null })
                if (isV5()) {
                    // §3.2: Inbox is a capture surface — it earns the
                    // ghost pill, and its empty state is the §2.11 one.
                    val ghost = v5GhostPill()
                    ghost.layoutParams = lp(h = dp(52f), t = dp(8f), b = dp(16f))
                    content.addView(ghost)
                    if (inboxTasks.isEmpty()) {
                        content.addView(v5EmptyState("▤", "Inbox zero", "Quick Add drops everything here first."))
                        if (dispShowFor(displayKey())) completedGroup(null)
                    } else {
                        renderTaskList(inboxTasks, "📥", "Inbox is empty.")
                    }
                } else {
                    renderTaskList(inboxTasks, "📥", "Inbox is empty.")
                }
            }
            view == "upcoming" -> renderUpcoming()
            view == "browse" -> renderBrowse()
        }
        // Tab switches CROSSFADE only (150–250ms) with NO lateral slide —
        // frame-verified in COMPARE-REPORT R0-7 / checklist K1. The fade
        // runs only when the screen identity changes, never on a
        // same-screen state refresh.
        val order = listOf("inbox", "today", "upcoming", "browse")
        if (order.contains(view) && s == null) lastTab = view
        // Browse has no header (profile header instead); v5 Today has
        // none either — the greeting lives inside the screen (§3.1).
        headerHolder.visibility = if ((s == null && view == "browse") ||
            (isV5() && s == null && view == "today")) View.GONE else View.VISIBLE
        val renderKey = s ?: view
        if (renderKey != lastRenderKey) {
            lastRenderKey = renderKey
            content.animate().cancel()
            content.alpha = 0f
            content.translationX = 0f
            content.translationY = 0f
            content.animate().alpha(1f).setDuration(200).setInterpolator(sheetEase).start()
        } else {
            content.animate().cancel()
            content.alpha = 1f
            content.translationX = 0f
        }
        renderNav()
    }

    /** Rebuild the header's right-hand actions: Display + ⋮ on list
     *  screens, a single close ✕ while multi-select is active (the header
     *  itself becomes "N tasks selected" — VIDEO §3.7). */
    private fun buildHeaderActions() {
        headerActions.removeAllViews()
        if (selectMode) {
            val close = tv("✕", 20f, Glass.text(this@MainActivity))
            close.gravity = Gravity.CENTER
            close.contentDescription = "Exit select mode"
            close.layoutParams = LinearLayout.LayoutParams(dp(40f), dp(40f))
            close.setOnClickListener { exitSelectMode() }
            headerActions.addView(close)
            return
        }
        val dispBtn = tv("▤", 22f, Glass.text(this@MainActivity))
        dispBtn.gravity = Gravity.CENTER
        dispBtn.contentDescription = "Display"
        dispBtn.layoutParams = LinearLayout.LayoutParams(dp(40f), dp(40f))
        dispBtn.setOnClickListener { showDisplaySheet() }
        val overBtn = tv("⋮", 24f, Glass.text(this@MainActivity), true)
        overBtn.gravity = Gravity.CENTER
        overBtn.contentDescription = "More options"
        overBtn.layoutParams = LinearLayout.LayoutParams(dp(40f), dp(40f))
        overBtn.setOnClickListener { showOverflowMenu(it) }
        if (isV5()) {
            // §2.1b: the two actions live in ONE grouped icon pill
            // (POPUP fill + HAIRLINE), the same container the Today
            // greeting header uses — never two bare buttons.
            val pill = LinearLayout(this)
            pill.orientation = LinearLayout.HORIZONTAL
            pill.gravity = Gravity.CENTER
            pill.background = roundedRect(V5.POPUP, 999f, V5.HAIRLINE)
            pill.layoutParams = LinearLayout.LayoutParams(-2, dp(40f))
            dispBtn.setTextColor(V5.TEXT); dispBtn.textSize = 19f
            overBtn.setTextColor(V5.TEXT); overBtn.textSize = 19f
            pill.addView(dispBtn); pill.addView(overBtn)
            headerActions.addView(pill)
            return
        }
        headerActions.addView(dispBtn); headerActions.addView(overBtn)
    }

    private fun renderHeader() {
        val s = sub
        headerProgWrap.visibility = View.GONE
        val listScreen = s == null || s.startsWith("project:") || s.startsWith("filter:") || s.startsWith("label:")
        headerActions.visibility = if (listScreen || selectMode) View.VISIBLE else View.GONE
        buildHeaderActions()
        if (selectMode) {
            headerTitle.text = selectedIds.size.toString() + if (selectedIds.size == 1) " task selected" else " tasks selected"
            headerSub.text = ""
            return
        }
        when {
            s != null -> {
                headerTitle.text = subTitle(s)
                headerSub.text = ""
            }
            view == "today" -> {
                headerTitle.text = "Today"
                // Todoist shows a plain muted sub-count: "5 tasks" (§4).
                val total = Store.state.tasks.count { it.dueDate == Dates.today() || Dates.isOverdue(it) }
                headerSub.text = "$total tasks"
            }
            view == "inbox" -> {
                headerTitle.text = "Inbox"
                headerSub.text = ""
            }
            view == "upcoming" -> {
                headerTitle.text = "Upcoming"
                headerSub.text = ""
            }
            else -> {
                // Browse has no title at all — it opens with the profile
                // header instead, and render() hides this header there.
                headerTitle.text = ""
                headerSub.text = ""
            }
        }
    }

    private fun subTitle(s: String): String {
        if (s.startsWith("filter:")) return "Filter · " + (Store.state.filters.find { it.id == s.substring(7) }?.name ?: "")
        if (s.startsWith("project:")) return "# " + (Store.state.projects.find { it.id == s.substring(8) }?.name ?: "")
        if (s.startsWith("label:")) return "@ " + (Store.state.labels.find { it.id == s.substring(6) }?.name ?: "")
        if (s.startsWith("habit:")) return Store.habitById(s.substring(6))?.name ?: "Habit"
        if (s == "habitform") return if (habitFormId != null) "Edit habit" else "New habit"
        return mapOf(
            "calendar" to "Calendar", "ramble" to "Ramble", "assist" to "Task & Email Assist",
            "history" to "History", "insights" to "Project Insights", "backups" to "Backups & Sync", "filterslabels" to "Filters & Labels",
            "stats" to "Productivity", "search" to "Search", "focus" to "Focus Timer",
            "smartplan" to "Smart Plan", "performance" to "Performance",
            "habits" to "Habits", "matrix" to "Eisenhower Matrix", "matrixlist" to "Eisenhower Matrix",
            "countdowns" to "Countdowns", "dock" to "Dock", "starred" to "Starred"
        )[s] ?: "GhostBlade Task"
    }

    private fun renderNav() {
        navBar.removeAllViews()
        if (selectMode) { renderSelectBar(); return }
        if (isV5()) { renderDock(); return }
        // Today's glyph embeds the current day number (ANALYSIS §0.4).
        val todayNum = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_MONTH).toString()
        val items = listOf(
            Triple("inbox", "▭", "Inbox"), Triple("today", todayNum, "Today"),
            Triple("upcoming", "▦", "Upcoming"), Triple("browse", "☰", "Browse")
        )
        items.forEach { (v, ic, lbl) ->
            val on = sub == null && view == v
            val b = LinearLayout(this)
            b.orientation = LinearLayout.VERTICAL
            b.gravity = Gravity.CENTER_HORIZONTAL
            b.setPadding(dp(4f), dp(2f), dp(4f), dp(2f))
            b.contentDescription = lbl
            // Selected pill (#472524 oval, ~51x28dp) sits behind the ICON
            // ONLY and crossfades/scales in — it never slides (K1b).
            val pill = FrameLayout(this)
            pill.layoutParams = LinearLayout.LayoutParams(dp(51f), dp(28f))
            if (on) {
                val pd = android.graphics.drawable.GradientDrawable()
                pd.shape = android.graphics.drawable.GradientDrawable.OVAL
                pd.setColor(TD_PILL)
                pill.background = pd
                pill.alpha = 0f
                pill.scaleX = 0.9f; pill.scaleY = 0.9f
                pill.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(190).start()
            }
            val icT = tv(ic, if (v == "today") 14f else 17f, if (on) TD_SALMON else TD_GREY, v == "today")
            icT.gravity = Gravity.CENTER
            icT.layoutParams = FrameLayout.LayoutParams(-1, -1)
            pill.addView(icT)
            b.addView(pill)
            val lbT = tv(lbl, 12.5f, if (on) TD_SALMON else TD_GREY, false)
            lbT.gravity = Gravity.CENTER
            lbT.layoutParams = lp(t = dp(2f))
            b.addView(lbT)
            b.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            b.setOnClickListener {
                Sfx.nav()
                view = v; sub = null; render()
            }
            navBar.addView(b)
        }
    }

    /* ---------------- multi-select mode (B2) ---------------- */

    /** Select-mode action bar replacing the bottom nav (VIDEO §3.7):
     *  move to project / label / priority flag / delete, acting on the
     *  selection through Store's bulk APIs. All actions sit dimmed at
     *  0 selected, exactly as the recording shows. */
    private fun renderSelectBar() {
        val enabled = selectedIds.isNotEmpty()
        val actions = listOf(
            Triple("▣", "Project") { bulkChooseProject() },
            Triple("▤", "Section") { bulkChooseSection() },
            Triple("🏷", "Label") { bulkChooseLabel() },
            Triple("⚑", "Priority") { bulkChoosePriority() },
            Triple("🗑", "Delete") { bulkDeleteSelected() }
        )
        actions.forEach { (icon, lbl, act) ->
            val b = LinearLayout(this)
            b.orientation = LinearLayout.VERTICAL
            b.gravity = Gravity.CENTER_HORIZONTAL
            b.setPadding(dp(4f), dp(2f), dp(4f), dp(2f))
            b.alpha = if (enabled) 1f else 0.4f
            b.contentDescription = lbl
            val icT = tv(icon, 17f, if (enabled) Glass.text(this@MainActivity) else TD_GREY)
            icT.gravity = Gravity.CENTER
            b.addView(icT)
            val lbT = tv(lbl, 12.5f, if (enabled) Glass.text(this@MainActivity) else TD_GREY)
            lbT.gravity = Gravity.CENTER
            lbT.layoutParams = lp(t = dp(2f))
            b.addView(lbT)
            b.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            b.setOnClickListener { if (enabled) act() else toast("Select a task first") }
            navBar.addView(b)
        }
    }

    /** Chrome-only crossfade (~180ms total): header + nav fade out, the
     *  mode flips and everything re-renders, chrome fades back in. The
     *  list content is never animated (VIDEO §3.7). */
    private fun crossfadeChrome(flip: () -> Unit) {
        headerHolder.animate().alpha(0f).setDuration(90).start()
        navBar.animate().alpha(0f).setDuration(90).withEndAction {
            flip()
            render()
            headerHolder.animate().alpha(1f).setDuration(90).start()
            navBar.animate().alpha(1f).setDuration(90).start()
        }.start()
    }

    private fun enterSelectMode() {
        if (selectMode) return
        crossfadeChrome { selectMode = true; selectedIds.clear() }
    }

    private fun exitSelectMode() {
        if (!selectMode) return
        crossfadeChrome { selectMode = false; selectedIds.clear() }
    }

    private fun toggleSelected(id: String) {
        if (id in selectedIds) selectedIds.remove(id) else selectedIds.add(id)
        Sfx.tick()
        render()
    }

    private fun bulkChooseProject() {
        val names = listOf("Inbox") + Store.state.projects.map { it.name }
        val ids = listOf<String?>(null) + Store.state.projects.map { it.id }
        AlertDialog.Builder(this).setTitle("Move to project")
            .setItems(names.toTypedArray()) { _, w ->
                Store.bulkMoveToProject(selectedIds, ids[w])
                toast("Moved ${selectedIds.size} task(s)")
                exitSelectMode()
            }.show()
    }

    /** Move-to-section for the multi-select bar (Correction Round 2,
     *  F-2): gives Store.bulkSetSection its UI caller. Sections belong to
     *  one project, so the picker only opens when the whole selection
     *  sits in a single project that has sections. */
    private fun bulkChooseSection() {
        val pids = Store.state.tasks.filter { it.id in selectedIds }.map { it.projectId }.distinct()
        if (pids.size != 1 || pids[0] == null) {
            toast("Pick tasks from a single project to set a section")
            return
        }
        val secs = Store.state.sections.filter { it.projectId == pids[0] }.sortedBy { it.order }
        if (secs.isEmpty()) {
            toast("This project has no sections yet — add one from the project menu")
            return
        }
        val names = listOf("No section") + secs.map { it.name }
        val ids = listOf<String?>(null) + secs.map { it.id }
        AlertDialog.Builder(this).setTitle("Move to section")
            .setItems(names.toTypedArray()) { _, w ->
                Store.bulkSetSection(selectedIds, ids[w])
                toast("Section set for ${selectedIds.size} task(s)")
                exitSelectMode()
            }.show()
    }

    private fun bulkChooseLabel() {
        if (Store.state.labels.isEmpty()) {
            promptText("Label name:") { name ->
                val l = Store.labelByName(name)
                Store.bulkAddLabel(selectedIds, l.id)
                Store.save()
                toast("Labelled ${selectedIds.size} task(s)")
                exitSelectMode()
            }
            return
        }
        AlertDialog.Builder(this).setTitle("Add label")
            .setItems(Store.state.labels.map { "@" + it.name }.toTypedArray()) { _, w ->
                Store.bulkAddLabel(selectedIds, Store.state.labels[w].id)
                toast("Labelled ${selectedIds.size} task(s)")
                exitSelectMode()
            }.show()
    }

    private fun bulkChoosePriority() {
        AlertDialog.Builder(this).setTitle("Set priority")
            .setItems(arrayOf("Priority 1", "Priority 2", "Priority 3", "Priority 4")) { _, w ->
                Store.bulkSetPriority(selectedIds, w + 1)
                toast("Priority set for ${selectedIds.size} task(s)")
                exitSelectMode()
            }.show()
    }

    private fun bulkDeleteSelected() {
        val removed = Store.bulkDelete(selectedIds)
        if (removed.isEmpty()) return
        selectMode = false
        selectedIds.clear()
        render()
        // Undo bar for the bulk delete (Store returned the removed tasks).
        undoBar.removeAllViews()
        val lbl = tv("Deleted ${removed.size} task(s)", 13f)
        lbl.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(10f) }
        undoBar.addView(lbl)
        val btn = tv("UNDO", 15f, Glass.gold(this@MainActivity), true)
        btn.setPadding(dp(8f), dp(10f), dp(8f), dp(10f))
        btn.setOnClickListener {
            removed.forEach { t ->
                Store.state.tasks.add(t)
                Reminders.sync(this, t); Reminders.syncGeo(this, t)
            }
            Store.save()
            undoBar.visibility = View.GONE
            render(); toast("Restored ↩️")
        }
        undoBar.addView(btn)
        undoHide?.let { handler.removeCallbacks(it) }
        undoHide = Runnable { undoBar.visibility = View.GONE }
        undoBar.visibility = View.VISIBLE
        handler.postDelayed(undoHide!!, 5000)
    }

    /* ---------------- task rows ---------------- */

    /* ---------------- Todoist row helpers (B1) ---------------- */

    private fun roundedRect(color: Int, radiusDp: Float, strokeColor: Int? = null): android.graphics.drawable.GradientDrawable {
        val g = android.graphics.drawable.GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(radiusDp).toFloat()
        if (strokeColor != null) g.setStroke(dp(1f), strokeColor)
        return g
    }

    private fun ovalDrawable(color: Int): android.graphics.drawable.GradientDrawable {
        val g = android.graphics.drawable.GradientDrawable()
        g.shape = android.graphics.drawable.GradientDrawable.OVAL
        g.setColor(color)
        return g
    }

    private fun hairline(): View {
        val v = View(this)
        v.setBackgroundColor(if (isV5()) V5.HAIRLINE else 0xFF1F1F1F.toInt())
        v.layoutParams = LinearLayout.LayoutParams(-1, Math.max(1, dp(0.5f)))
        return v
    }

    /** P1 ring is ALWAYS Todoist red — never the user accent (binding
     *  Monitoring correction; Ui.kt's checkCircle follows the accent). */
    private fun priorityColor(pr: Int): Int = when (pr) {
        1 -> 0xFFE44332.toInt()
        2 -> 0xFFFF9F0A.toInt()
        3 -> 0xFF4A90D9.toInt()
        else -> TD_GREY
    }

    /** 22dp circle, 3dp ring (2dp for P4) with a faint priority-tinted fill
     *  inside for P1–P3 (ANALYSIS §1). Filled = solid priority colour. */
    private fun checkDrawable(priority: Int, filled: Boolean): android.graphics.drawable.GradientDrawable {
        val g = android.graphics.drawable.GradientDrawable()
        g.shape = android.graphics.drawable.GradientDrawable.OVAL
        val c = priorityColor(priority)
        if (filled) {
            g.setColor(c)
        } else {
            if (priority in 1..3) {
                g.setColor(android.graphics.Color.argb(52, android.graphics.Color.red(c), android.graphics.Color.green(c), android.graphics.Color.blue(c)))
            } else {
                g.setColor(android.graphics.Color.TRANSPARENT)
            }
            g.setStroke(dp(if (priority == 4) 2f else 3f), c)
        }
        return g
    }

    /** "Nov 20" — the meta-line / overdue date format. */
    private fun fmtMonDay(s: String): String {
        return try {
            val mon = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
            val c = Dates.parse(s)
            mon[c.get(java.util.Calendar.MONTH)] + " " + c.get(java.util.Calendar.DAY_OF_MONTH)
        } catch (e: Exception) { s }
    }

    /** "Friday, Nov 20" — the Todoist date-section header format. */
    private fun fmtDayLong(s: String): String {
        return try {
            val days = arrayOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
            val c = Dates.parse(s)
            days[Dates.jsDayOfWeek(c)] + ", " + fmtMonDay(s)
        } catch (e: Exception) { s }
    }

    /** Semantic date colours: green Today / amber Tomorrow / salmon
     *  overdue / grey for none + future (binding correction). */
    private fun dateColorFor(t: TaskItem): Int = when {
        t.dueDate == null -> TD_GREY
        Dates.isOverdue(t) -> TD_SALMON
        t.dueDate == Dates.today() -> TD_GREEN
        t.dueDate == Dates.addDays(Dates.today(), 1) -> TD_AMBER
        else -> TD_GREY
    }

    private fun dateWordFor(t: TaskItem): String = when {
        t.dueDate == null -> ""
        Dates.isOverdue(t) -> fmtMonDay(t.dueDate!!)
        t.dueDate == Dates.today() -> "Today"
        t.dueDate == Dates.addDays(Dates.today(), 1) -> "Tomorrow"
        else -> fmtMonDay(t.dueDate!!)
    }

    /** Title at 17sp regular; a URL inside the title is underlined
     *  (ANALYSIS §4 — the "(Watch)" link case). */
    private fun titleView(t: TaskItem): TextView {
        val v = tv(t.title, 17f)
        val link = Regex("""(https?://|www\.)\S+""").find(t.title)
        if (link != null) {
            val sp = android.text.SpannableString(t.title)
            sp.setSpan(android.text.style.UnderlineSpan(), link.range.first, link.range.last + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            v.text = sp
        }
        return v
    }

    /** Segmented meta line (ANALYSIS §1/§4): left = link icon, calendar
     *  icon + semantically coloured date word, repeat icon (same colour),
     *  then grey time / duration / labels / subtasks / reminder / location;
     *  right = project path, right-aligned, ending in a trailing "#" — or
     *  "Inbox" with a red tray glyph for project-less tasks. When
     *  [iconOnly] is set and the task is due today, the date word is
     *  dropped: icons only, in neutral grey. */
    private fun buildMeta(t: TaskItem, showProject: Boolean, iconOnly: Boolean): LinearLayout? {
        val hasDate = t.dueDate != null
        val hasBits = hasDate || t.dueTime != null || t.durationMin != null ||
            t.labelIds.isNotEmpty() || t.subtasks.isNotEmpty() || t.reminderOffsetMin != null || t.locLat != null
        if (!hasBits && !showProject) return null
        val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val left = LinearLayout(this); left.orientation = LinearLayout.HORIZONTAL
        left.gravity = Gravity.CENTER_VERTICAL
        left.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        var first = true
        fun gap(): Float = if (first) { first = false; 0f } else 8f
        fun seg(text: String, color: Int, gapDp: Float) {
            val s = tv(text, 13.5f, color)
            if (gapDp > 0f) s.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(gapDp) }
            left.addView(s)
        }
        if (t.title.contains("http") || t.notes.contains("http")) seg("🔗", TD_GREY, gap())
        if (hasDate) {
            val iconOnlyToday = iconOnly && t.dueDate == Dates.today()
            val col = if (iconOnlyToday) TD_GREY else dateColorFor(t)
            seg("▦", col, gap())
            if (!iconOnlyToday) seg(dateWordFor(t), col, 4f)
            if (t.recurrence != null) seg("↻", col, gap())
        } else if (t.recurrence != null) {
            seg("↻", TD_GREY, gap())
        }
        if (t.dueTime != null) seg(t.dueTime!!, TD_GREY, gap())
        if (t.durationMin != null) seg("⏱ " + t.durationMin + "m", TD_GREY, gap())
        t.labelIds.forEach { id -> Store.state.labels.find { it.id == id }?.let { seg("@" + it.name, TD_GREY, gap()) } }
        if (t.subtasks.isNotEmpty()) seg("☰ " + t.subtasks.count { it.done } + "/" + t.subtasks.size, TD_GREY, gap())
        if (t.reminderOffsetMin != null) seg("🔔", TD_GREY, gap())
        if (t.locLat != null) seg("📍", TD_GREY, gap())
        row.addView(left)
        if (showProject) {
            val pr = Store.state.projects.find { it.id == t.projectId }
            val right = LinearLayout(this); right.orientation = LinearLayout.HORIZONTAL
            right.gravity = Gravity.CENTER_VERTICAL
            val nameT = tv(if (pr != null) pr.name + " " else "Inbox ", 13.5f, TD_GREY)
            nameT.maxLines = 1
            nameT.ellipsize = android.text.TextUtils.TruncateAt.END
            nameT.maxWidth = dp(120f)
            right.addView(nameT)
            right.addView(tv(if (pr != null) "#" else "▭", 13.5f, if (pr != null) TD_GREY else TD_RED, pr != null))
            row.addView(right)
        }
        return row
    }

    /** One task row with NO background/margin of its own — rows live inside
     *  a grouped #282828 container (see groupedCard). */
    private fun taskRowInner(t: TaskItem, showProject: Boolean, iconOnly: Boolean): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(dp(16f), dp(14f), dp(16f), dp(12f))
        row.gravity = Gravity.TOP
        val check = tv("", 12f, 0xFFFFFFFF.toInt(), true)
        check.gravity = Gravity.CENTER
        check.layoutParams = LinearLayout.LayoutParams(dp(22f), dp(22f))
        if (selectMode) {
            // Selection indicator replaces the completion circle: filled
            // red with a ✓ when selected, plain grey ring when not.
            val isSel = t.id in selectedIds
            check.text = if (isSel) "✓" else ""
            check.contentDescription = "Select task"
            check.background = if (isSel) ovalDrawable(TD_RED) else {
                val g = android.graphics.drawable.GradientDrawable()
                g.shape = android.graphics.drawable.GradientDrawable.OVAL
                g.setColor(android.graphics.Color.TRANSPARENT)
                g.setStroke(dp(2f), TD_GREY)
                g
            }
            check.setOnClickListener { toggleSelected(t.id) }
        } else {
            check.background = checkDrawable(t.priority, false)
            check.contentDescription = "Complete task"
            check.setOnClickListener { completeWithAnim(t, row, check) }
        }
        row.addView(check)
        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        body.layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12f) }
        body.addView(titleView(t))
        if (t.notes.isNotBlank()) {
            // Description preview: one muted line between title and meta.
            val d = tv(t.notes.trim().lineSequence().firstOrNull() ?: "", 14f, 0xFFC8C8C8.toInt())
            d.maxLines = 1
            d.ellipsize = android.text.TextUtils.TruncateAt.END
            d.layoutParams = lp(t = dp(2f))
            body.addView(d)
        }
        val meta = buildMeta(t, showProject, iconOnly)
        if (meta != null) {
            meta.layoutParams = lp(t = dp(6f))
            body.addView(meta)
        }
        body.setOnClickListener { if (selectMode) toggleSelected(t.id) else openEditor(t.id) }
        row.addView(body)
        attachPressFx(row)
        return row
    }

    /** The grouped container: one flat #282828 surface, radius on the
     *  group's outer corners only, hairline separators between rows,
     *  no per-row margins (ANALYSIS §1 — the core structural pattern). */
    private fun groupedCard(tasks: List<TaskItem>, showProject: Boolean, iconOnlyFor: (TaskItem) -> Boolean): LinearLayout {
        if (isV5()) {
            // v5 lists are separate opaque cards with gaps, never a
            // grouped container (SHADOW-FOUR §2.6). Drag-sort is wired
            // for project lists; Today wires its own (renderTodayV5).
            val drag = sub?.startsWith("project:") == true
            return v5CardList(tasks, showProject, swipe = true, drag = drag)
        }
        val g = LinearLayout(this)
        g.orientation = LinearLayout.VERTICAL
        g.background = roundedRect(cardFill(), 14f)
        g.layoutParams = lp(b = dp(10f))
        tasks.forEachIndexed { i, t ->
            if (i > 0) {
                val h = hairline()
                h.layoutParams = LinearLayout.LayoutParams(-1, Math.max(1, dp(0.5f))).apply { leftMargin = dp(16f); rightMargin = dp(16f) }
                g.addView(h)
            }
            g.addView(taskRowInner(t, showProject, iconOnlyFor(t)))
        }
        return g
    }

    /** Standalone row for the remaining single-row callers (Search results,
     *  Calendar day list): the same anatomy in its own one-row group. */
    private fun taskRow(t: TaskItem): LinearLayout {
        if (isV5()) {
            val wrap = LinearLayout(this)
            wrap.orientation = LinearLayout.VERTICAL
            val c = v5TaskCard(t, showProject = false, swipe = sub == "search")
            c.layoutParams = lp(b = dp(10f))
            wrap.addView(c)
            return wrap
        }
        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.VERTICAL
        wrap.background = roundedRect(cardFill(), 14f)
        wrap.layoutParams = lp(b = dp(10f))
        wrap.addView(taskRowInner(t, showProject = false, iconOnly = false))
        return wrap
    }

    private fun attachPressFx(v: View) {
        v.setOnTouchListener { view2, ev ->
            when (ev.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    view2.animate().scaleX(0.98f).scaleY(0.98f).setDuration(120).start()
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                    view2.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
            }
            false
        }
    }

    private fun completeWithAnim(t: TaskItem, row: View, check: TextView) {
        // Re-entry guard: a fast double-tap during the feedback window used
        // to complete a recurring task twice. The checkbox is dead until
        // the completion lands.
        check.isEnabled = false
        // Feedback is ONLY the subtle priority-coloured fill: the reference
        // recording contains no completion animation at all (COMPARE K8),
        // so no slide/fade-out is invented here.
        check.background = checkDrawable(t.priority, true)
        check.text = "✓"
        Sfx.complete()
        vibrateOnce()
        handler.postDelayed({
            val msg = Store.completeTask(t.id)
            if (msg.isNotEmpty()) toast(msg)
            render()
        }, 260)
    }

    /** One short haptic tick (the completion/drag feedback used in
     *  several places — a single helper keeps the deprecated
     *  VIBRATOR_SERVICE lookup in exactly one spot). */
    private fun vibrateOnce() {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                val vib = getSystemService(VIBRATOR_SERVICE) as? android.os.Vibrator
                vib?.vibrate(android.os.VibrationEffect.createOneShot(15, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } catch (e: Exception) {}
        }
    }

    /* ---------------- display pipeline (DisplayEngine wiring) -------- */

    private fun displayKey(): String = sub ?: view

    private fun buildDisplayFilter(key: String): DisplayFilter {
        val today = Dates.today()
        var from: String? = null; var to: String? = null
        when (dispDate[key] ?: "All") {
            "Today" -> { from = today; to = today }
            "Tomorrow" -> { from = Dates.addDays(today, 1); to = Dates.addDays(today, 1) }
            "Overdue" -> { to = Dates.addDays(today, -1) }
            "Upcoming" -> { from = Dates.addDays(today, 1) }
        }
        // Label rides the engine's DisplayFilter.labelIds (Correction
        // Round 1, R1-2). Assignee deliberately stays state-only: the
        // engine predicate exists, but no task in this single-user app
        // carries an assignee value, so applying "Me" would filter out
        // every (unassigned) task — Todoist's row default is "Me and
        // unassigned", which is exactly the no-op. See TEAMONE-IMPL-B2.
        val labelIds = dispLabel[key]?.let { setOf(it) } ?: emptySet()
        return DisplayFilter(from, to, dispPrio[key] ?: emptySet(), labelIds)
    }

    /** Filter then sort, both through Team Two's DisplayEngine. MANUAL
     *  keeps the app's standard task order; every other mode — SMART
     *  included — goes through DisplayEngine.sortTasks (Team Two
     *  addendum item 2: no local SMART overlay remains). */
    private fun applyDisplay(list: List<TaskItem>, key: String): List<TaskItem> {
        var out = list
        if ((dispDate[key] ?: "All") == "No date") out = out.filter { it.dueDate == null }
        else out = DisplayEngine.applyFilter(out, buildDisplayFilter(key))
        val mode = dispSortFor(key)
        out = if (mode == SortMode.MANUAL) Store.sortTasks(out) else DisplayEngine.sortTasks(out, mode)
        return out
    }

    /** Project path shows on cross-project views (Today/Upcoming/filters/
     *  labels) but not in Inbox or inside the project itself (A6). */
    private fun showProjectForList(): Boolean = when {
        sub == null && view == "inbox" -> false
        sub?.startsWith("project:") == true -> false
        else -> true
    }

    private fun sectionHeader(text: String): TextView {
        val l = tv(text, 16.5f, Glass.text(this@MainActivity), true)
        l.layoutParams = lp(t = dp(20f), b = dp(8f))
        return l
    }

    /** Completed-tasks group for the Display sheet's toggle. Completions
     *  keep only a title/date, so these rows are read-only. */
    private fun completedGroup(dateFilter: String?) {
        val comps = Store.state.completions.filter { dateFilter == null || it.date == dateFilter }.take(20)
        if (comps.isEmpty()) return
        if (isV5()) {
            // §2.6 applies to completed tasks too: separate opaque
            // cards, DONE-green left bar, DONE-green filled check —
            // never the legacy grouped container (CHECK1 P2-3).
            val head = LinearLayout(this); head.orientation = LinearLayout.HORIZONTAL
            head.gravity = Gravity.CENTER_VERTICAL
            head.layoutParams = lp(t = dp(20f), b = dp(8f))
            val ht = tv("Completed", 20f, V5.TEXT, true)
            ht.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            head.addView(ht)
            head.addView(tv(comps.size.toString(), 14f, V5.FAINT))
            content.addView(head)
            val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
            col.layoutParams = lp(b = dp(4f))
            comps.forEach { c ->
                val cardL = LinearLayout(this); cardL.orientation = LinearLayout.HORIZONTAL
                cardL.background = v5CardBg()
                cardL.setPadding(dp(16f), dp(14f), dp(14f), dp(14f))
                cardL.gravity = Gravity.TOP
                cardL.layoutParams = lp(b = dp(V5.CARD_GAP.toFloat()))
                val bar = View(this)
                bar.background = roundedRect(V5.DONE, 2f)
                bar.layoutParams = LinearLayout.LayoutParams(dp(4f), -1).apply { rightMargin = dp(12f) }
                cardL.addView(bar)
                val ck = tv("✓", 12f, 0xFFFFFFFF.toInt(), true); ck.gravity = Gravity.CENTER
                ck.background = ovalDrawable(V5.DONE)
                ck.layoutParams = LinearLayout.LayoutParams(dp(24f), dp(24f))
                cardL.addView(ck)
                val body = LinearLayout(this); body.orientation = LinearLayout.VERTICAL
                body.layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12f) }
                val tt = tv(c.title, 16f, V5.MUTED, true)
                tt.paintFlags = tt.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                tt.maxLines = 2
                tt.ellipsize = android.text.TextUtils.TruncateAt.END
                body.addView(tt)
                if (dateFilter == null) {
                    val dt = tv(fmtMonDay(c.date), 12f, V5.FAINT)
                    dt.layoutParams = lp(t = dp(3f))
                    body.addView(dt)
                }
                cardL.addView(body)
                col.addView(cardL)
            }
            content.addView(col)
            return
        }
        content.addView(sectionHeader("Completed"))
        val g = LinearLayout(this); g.orientation = LinearLayout.VERTICAL
        g.background = roundedRect(cardFill(), 14f); g.layoutParams = lp(b = dp(10f))
        comps.forEachIndexed { i, c ->
            if (i > 0) {
                val h = hairline()
                h.layoutParams = LinearLayout.LayoutParams(-1, Math.max(1, dp(0.5f))).apply { leftMargin = dp(16f); rightMargin = dp(16f) }
                g.addView(h)
            }
            val r = LinearLayout(this); r.orientation = LinearLayout.HORIZONTAL
            r.setPadding(dp(16f), dp(12f), dp(16f), dp(12f)); r.gravity = Gravity.CENTER_VERTICAL
            val ck = tv("✓", 11f, 0xFFFFFFFF.toInt(), true); ck.gravity = Gravity.CENTER
            ck.background = checkDrawable(4, true); ck.layoutParams = LinearLayout.LayoutParams(dp(22f), dp(22f))
            r.addView(ck)
            val tt = tv(c.title, 15f, TD_GREY)
            tt.paintFlags = tt.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            tt.layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12f) }
            r.addView(tt)
            g.addView(r)
        }
        content.addView(g)
    }

    private fun renderTaskList(list: List<TaskItem>, emoji: String, emptyText: String) {
        val key = displayKey()
        val l = applyDisplay(list, key)
        if (l.isEmpty()) {
            content.addView(emptyView(emoji, emptyText))
        } else {
            val gm = dispGroupFor(key)
            if (gm == GroupMode.NONE) {
                content.addView(groupedCard(l, showProjectForList()) { false })
            } else {
                DisplayEngine.groupTasks(Store.state, l, gm).forEach { grp ->
                    if (grp.title.isNotEmpty()) content.addView(sectionHeader(grp.title))
                    content.addView(groupedCard(grp.tasks, showProjectForList()) { false })
                }
            }
        }
        if (dispShowFor(key)) completedGroup(null)
    }

    /* ---------------- project sections (Correction Round 2, F-2) ----- */

    /** Collapsible section header for the project view: chevron + name +
     *  "— N" count (reference shape, ANALYSIS F24) and a ⋮ menu wired to
     *  the Store's rename/delete. Tapping the row toggles collapse via
     *  Store.setSectionCollapsed. */
    private fun sectionGroupHeader(sec: SectionItem, count: Int): View {
        val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.layoutParams = lp(t = dp(18f), b = dp(8f))
        row.setPadding(dp(2f), dp(4f), dp(2f), dp(4f))
        val chev = tv(if (sec.collapsed) "▸" else "▾", 14f, Glass.muted(this@MainActivity), true)
        chev.gravity = Gravity.CENTER
        chev.layoutParams = LinearLayout.LayoutParams(dp(22f), -2)
        row.addView(chev)
        row.addView(tv(sec.name, 16.5f, Glass.text(this@MainActivity), true))
        row.addView(tv(" — $count", 14f, Glass.muted(this@MainActivity)))
        val spacer = View(this); spacer.layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        row.addView(spacer)
        val dots = tv("⋮", 17f, Glass.muted(this@MainActivity), true)
        dots.setPadding(dp(10f), dp(2f), dp(4f), dp(2f))
        dots.contentDescription = "Section options"
        dots.setOnClickListener { sectionMenu(sec) }
        row.addView(dots)
        row.setOnClickListener {
            Sfx.tick()
            Store.setSectionCollapsed(sec.id, !sec.collapsed)
            render()
        }
        return row
    }

    private fun sectionMenu(sec: SectionItem) {
        AlertDialog.Builder(this).setTitle(sec.name)
            .setItems(arrayOf("Rename section", "Delete section")) { _, w ->
                when (w) {
                    0 -> promptText("Section name:") { name ->
                        if (name.isNotBlank()) { Store.renameSection(sec.id, name.trim()); render() }
                    }
                    1 -> {
                        Store.deleteSection(sec.id)
                        toast("Section deleted — its tasks stay in the project")
                        render()
                    }
                }
            }.show()
    }

    /** Project list grouped by section: unsectioned tasks first with no
     *  header (the reference project screen shows the loose task above
     *  the first section), then each section in its saved order with a
     *  collapsible header. Display filters/sorts still apply; the
     *  Completed group still follows when the Display toggle is on. */
    private fun renderProjectSections(p: ProjectItem, tasks: List<TaskItem>) {
        val key = displayKey()
        val secs = Store.state.sections.filter { it.projectId == p.id }.sortedBy { it.order }
        val l = applyDisplay(tasks, key)
        if (l.isEmpty() && secs.isEmpty()) {
            content.addView(emptyView("📁", "No open tasks in this project."))
            return
        }
        val secIds = secs.map { it.id }.toSet()
        val unsectioned = l.filter { it.sectionId == null || it.sectionId !in secIds }
        if (unsectioned.isNotEmpty()) content.addView(groupedCard(unsectioned, false) { false })
        secs.forEach { sec ->
            val mine = l.filter { it.sectionId == sec.id }
            content.addView(sectionGroupHeader(sec, mine.size))
            if (!sec.collapsed && mine.isNotEmpty()) content.addView(groupedCard(mine, false) { false })
        }
        if (dispShowFor(key)) completedGroup(null)
    }

    /* ---------------- Today ---------------- */

    /** The Overdue block shared by Today and Upcoming: header row with the
     *  salmon Reschedule action + collapse chevron, then the grouped rows. */
    private fun addOverdueSection() {
        val key = displayKey()
        val overdue = applyDisplay(Store.state.tasks.filter { Dates.isOverdue(it) }, key)
        if (overdue.isEmpty()) return
        val head = LinearLayout(this); head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.layoutParams = lp(t = dp(16f), b = dp(8f))
        val title = tv("Overdue", 16.5f, Glass.text(this@MainActivity), true)
        title.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        head.addView(title)
        val resched = tv("Reschedule", 15.5f, TD_SALMON, false)
        resched.setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
        resched.contentDescription = "Reschedule all overdue tasks to today"
        resched.setOnClickListener {
            val prev = Store.rescheduleOverdue()
            if (prev.isNotEmpty()) {
                lastReschedule = prev
                toast("Rescheduled ${prev.size} task(s) to today")
                showRescheduleUndo(prev)
                render()
            }
        }
        head.addView(resched)
        val chev = tv(if (todayOverdueCollapsed) "⌄" else "∧", 18f, Glass.text(this@MainActivity))
        chev.setPadding(dp(12f), dp(4f), dp(2f), dp(4f))
        chev.contentDescription = "Collapse Overdue section"
        chev.setOnClickListener { todayOverdueCollapsed = !todayOverdueCollapsed; render() }
        head.addView(chev)
        content.addView(head)
        if (!todayOverdueCollapsed) content.addView(groupedCard(overdue, true) { false })
    }

    private fun showRescheduleUndo(prev: List<Pair<String, String?>>) {
        undoBar.removeAllViews()
        val lbl = tv("Rescheduled ${prev.size} overdue task(s)", 13f)
        lbl.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(10f) }
        undoBar.addView(lbl)
        val btn = tv("UNDO", 15f, Glass.gold(this@MainActivity), true)
        btn.setPadding(dp(8f), dp(10f), dp(8f), dp(10f))
        btn.setOnClickListener {
            Store.restoreDueDates(prev)
            lastReschedule = null
            undoBar.visibility = View.GONE
            render(); toast("Undone ↩️")
        }
        undoBar.addView(btn)
        undoHide?.let { handler.removeCallbacks(it) }
        undoHide = Runnable { undoBar.visibility = View.GONE }
        undoBar.visibility = View.VISIBLE
        handler.postDelayed(undoHide!!, 5000)
    }

    private fun renderToday() {
        if (isV5()) { renderTodayV5(); return }
        addOverdueSection()
        val key = displayKey()
        val todayList = applyDisplay(Store.state.tasks.filter { it.dueDate == Dates.today() }, key)
        val hasOverdue = Store.state.tasks.any { Dates.isOverdue(it) }
        if (todayList.isEmpty() && !hasOverdue) {
            content.addView(emptyView("🎉", "All clear for today.\nTap + to add a task — try \"Gym tomorrow at 7am every day\"."))
            return
        }
        if (todayList.isNotEmpty()) {
            content.addView(sectionHeader(fmtDayLong(Dates.today())))
            // Tasks due today show the icon-only grey meta form (§4).
            content.addView(groupedCard(todayList, true) { true })
        }
        if (dispShowFor(key)) completedGroup(Dates.today())
    }

    private fun briefCard(): LinearLayout {
        val c = card()
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val greet = if (h < 12) "Good morning" else if (h < 17) "Good afternoon" else "Good evening"
        val icon = if (h < 12) "☀️" else if (h < 17) "🌤️" else "🌙"
        c.addView(tv("$icon $greet, Sir", 16f, Glass.text(this@MainActivity), true))
        val over = Store.state.tasks.count { Dates.isOverdue(it) }
        val todayN = Store.state.tasks.count { it.dueDate == Dates.today() }
        val sub2 = tv(
            "$todayN tasks scheduled today" + if (over > 0) " · $over overdue" else " · nothing overdue 🎉",
            13f, Glass.muted(this@MainActivity)
        )
        sub2.layoutParams = lp(t = dp(4f))
        c.addView(sub2)
        val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL
        row.layoutParams = lp(t = dp(10f))
        val plan = ghostBtn("🤖 Smart plan my day") { sub = "smartplan"; render() }
        plan.layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(8f) }
        val focus = ghostBtn("🎯 Focus timer") { sub = "focus"; render() }
        focus.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        row.addView(plan); row.addView(focus)
        c.addView(row)
        return c
    }

    /* ---------------- Upcoming + week strip ---------------- */

    private fun mondayOf(dateStr: String): String {
        val c = Dates.parse(dateStr)
        val sinceMonday = (c.get(java.util.Calendar.DAY_OF_WEEK) - java.util.Calendar.MONDAY + 7) % 7
        c.add(java.util.Calendar.DAY_OF_MONTH, -sinceMonday)
        return Dates.dstr(c)
    }

    /** One Mon–Sun strip row: grey weekday letters; numbers grey in the
     *  past, white in the future; today = white bold number inside a solid
     *  #D04349 circle; a small grey dot under days that have tasks (not
     *  under today — R0-7 nuance). */
    private fun weekStripRow(monday: String): LinearLayout {
        val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL
        val letters = listOf("M", "T", "W", "T", "F", "S", "S")
        val today = Dates.today()
        for (i in 0 until 7) {
            val ds = Dates.addDays(monday, i)
            val colL = LinearLayout(this); colL.orientation = LinearLayout.VERTICAL
            colL.gravity = Gravity.CENTER_HORIZONTAL
            colL.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            val lt = tv(letters[i], 13f, TD_GREY); lt.gravity = Gravity.CENTER
            colL.addView(lt)
            val isToday = ds == today
            val isPast = ds < today
            val numWrap = FrameLayout(this)
            numWrap.layoutParams = LinearLayout.LayoutParams(dp(40f), dp(40f)).apply { topMargin = dp(4f) }
            if (isToday) numWrap.background = ovalDrawable(TD_RED)
            val num = tv(Dates.parse(ds).get(java.util.Calendar.DAY_OF_MONTH).toString(), 17f,
                if (isPast && !isToday) TD_GREY else 0xFFFFFFFF.toInt(), isToday)
            num.gravity = Gravity.CENTER
            num.layoutParams = FrameLayout.LayoutParams(-1, -1)
            numWrap.addView(num)
            colL.addView(numWrap)
            val hasTasks = Store.state.tasks.any { it.dueDate == ds }
            val dot = View(this)
            dot.layoutParams = LinearLayout.LayoutParams(dp(5f), dp(5f)).apply { topMargin = dp(6f) }
            if (hasTasks && !isToday) dot.background = ovalDrawable(TD_GREY)
            colL.addView(dot)
            val dd = ds
            colL.setOnClickListener { Sfx.tick(); toast(fmtDayLong(dd) + " · " + Store.state.tasks.count { it.dueDate == dd } + " task(s)") }
            row.addView(colL)
        }
        return row
    }

    private fun renderUpcoming() {
        if (isV5()) { renderUpcomingV5(); return }
        val today = Dates.today()
        // Month selector — the title turns RED while the strip is expanded
        // and its chevron flips (COMPARE K4).
        val monthNames = arrayOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
        val monAbbr = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        val nowC = Dates.parse(today)
        val mRow = LinearLayout(this); mRow.orientation = LinearLayout.HORIZONTAL
        mRow.gravity = Gravity.CENTER_VERTICAL
        mRow.layoutParams = lp(t = dp(4f), b = dp(10f))
        val mCol = if (upcomingExpanded) TD_RED else Glass.text(this@MainActivity)
        val mt = tv(monthNames[nowC.get(java.util.Calendar.MONTH)] + " " + nowC.get(java.util.Calendar.YEAR), 17f, mCol, true)
        mRow.addView(mt)
        val mChev = tv(if (upcomingExpanded) "▲" else "▼", 12f, mCol)
        mChev.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6f) }
        mRow.addView(mChev)
        mRow.setOnClickListener { upcomingExpanded = !upcomingExpanded; render() }
        content.addView(mRow)
        val monday = mondayOf(today)
        if (!upcomingExpanded) {
            content.addView(weekStripRow(monday))
            // Centred drag handle: the affordance that opens the stream.
            val handleWrap = LinearLayout(this); handleWrap.orientation = LinearLayout.VERTICAL
            handleWrap.gravity = Gravity.CENTER_HORIZONTAL
            val handle = View(this)
            handle.background = roundedRect(0xFF595959.toInt(), 2f)
            handle.layoutParams = LinearLayout.LayoutParams(dp(36f), dp(4f)).apply { topMargin = dp(10f); bottomMargin = dp(10f) }
            handleWrap.addView(handle)
            handleWrap.setOnClickListener { upcomingExpanded = true; render() }
            content.addView(handleWrap)
            content.addView(hairline())
        } else {
            // Expanded: a continuous stream of week rows that scrolls with
            // the page (Nov 16 → Dec 20 in the reference), with an inline
            // red month tag when the month changes.
            var lastMonth = -1
            for (w in 0 until 5) {
                val wkMonday = Dates.addDays(monday, w * 7)
                val mo = Dates.parse(wkMonday).get(java.util.Calendar.MONTH)
                if (mo != lastMonth) {
                    lastMonth = mo
                    if (w > 0) {
                        val tag = tv(monAbbr[mo], 13f, TD_SALMON, true)
                        tag.layoutParams = lp(t = dp(10f), b = dp(2f))
                        content.addView(tag)
                    }
                }
                content.addView(weekStripRow(wkMonday))
            }
            content.addView(hairline())
        }
        addOverdueSection()
        val key = displayKey()
        val dated = Store.state.tasks.filter { it.dueDate != null && it.dueDate!! >= today }
            .groupBy { it.dueDate!! }.toSortedMap()
        if (dated.isEmpty() && Store.state.tasks.none { Dates.isOverdue(it) }) {
            content.addView(emptyView("🗓️", "Nothing coming up yet."))
            return
        }
        dated.forEach { (ds, tasks) ->
            val suffix = when (ds) {
                today -> " • Today"
                Dates.addDays(today, 1) -> " • Tomorrow"
                else -> ""
            }
            content.addView(sectionHeader(fmtDayLong(ds) + suffix))
            val sorted = applyDisplay(tasks, key)
            if (sorted.isNotEmpty()) content.addView(groupedCard(sorted, true) { it.dueDate == today })
        }
        if (dispShowFor(key)) completedGroup(today)
    }

    /** Upcoming, v5 (§3.3): the shared week day-pills on top (same
     *  selection state as Today, §2.3), the Overdue group pinned first
     *  with its label in OVERDUE, then day groups in date order from
     *  the selected day onward. Selecting a pill scrolls the list to
     *  that day's group. Replaces the v4 month selector + week strip
     *  under v5 (CHECK1 P2-5); the legacy path above is untouched for
     *  the rollback skins. */
    private fun renderUpcomingV5() {
        val today = Dates.today()
        val key = displayKey()
        if (v5DaySel < today) v5DaySel = today
        val pills = v5DayPills(v5DaySel) { d ->
            upcomingScrollDay = d
            v5DaySel = d
            render()
        }
        pills.layoutParams = lp(t = dp(4f), b = dp(6f))
        content.addView(pills)

        val overdue = applyDisplay(Store.state.tasks.filter { Dates.isOverdue(it) }, key)
        if (overdue.isNotEmpty()) {
            val head = LinearLayout(this); head.orientation = LinearLayout.HORIZONTAL
            head.gravity = Gravity.CENTER_VERTICAL
            head.layoutParams = lp(t = dp(10f), b = dp(8f))
            val title = tv("Overdue", 12f, V5.OVERDUE, true)
            title.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            head.addView(title)
            val resched = tv("Reschedule", 13f, V5.OVERDUE)
            resched.setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
            resched.contentDescription = "Reschedule all overdue tasks to today"
            resched.setOnClickListener {
                val prev = Store.rescheduleOverdue()
                if (prev.isNotEmpty()) {
                    lastReschedule = prev
                    toast("Rescheduled ${prev.size} task(s) to today")
                    showRescheduleUndo(prev)
                    render()
                }
            }
            head.addView(resched)
            val chev = tv(if (todayOverdueCollapsed) "⌄" else "∧", 18f, V5.TEXT)
            chev.setPadding(dp(12f), dp(4f), dp(2f), dp(4f))
            chev.contentDescription = "Collapse Overdue section"
            chev.setOnClickListener { todayOverdueCollapsed = !todayOverdueCollapsed; render() }
            head.addView(chev)
            content.addView(head)
            if (!todayOverdueCollapsed)
                content.addView(v5CardList(overdue, showProject = true, swipe = true, drag = false))
        }

        val dated = Store.state.tasks.filter { it.dueDate != null && it.dueDate!! >= v5DaySel }
            .groupBy { it.dueDate!! }.toSortedMap()
        if (dated.isEmpty() && overdue.isEmpty()) {
            content.addView(v5EmptyState("▦", "Nothing coming up", "Future tasks will line up here."))
            return
        }
        var scrollTarget: View? = null
        dated.forEach { (ds, tasks) ->
            val suffix = when (ds) {
                today -> " • Today"
                Dates.addDays(today, 1) -> " • Tomorrow"
                else -> ""
            }
            val head = LinearLayout(this); head.orientation = LinearLayout.HORIZONTAL
            head.gravity = Gravity.CENTER_VERTICAL
            head.layoutParams = lp(t = dp(18f), b = dp(8f))
            val ht = tv(fmtDayLong(ds) + suffix, 16f, V5.TEXT, true)
            ht.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            head.addView(ht)
            head.addView(tv(tasks.size.toString(), 13f, V5.FAINT))
            content.addView(head)
            if (ds == upcomingScrollDay) scrollTarget = head
            val sorted = applyDisplay(tasks, key)
            if (sorted.isNotEmpty())
                content.addView(v5CardList(sorted, showProject = true, swipe = true, drag = false))
        }
        val target = scrollTarget
        if (target != null) {
            upcomingScrollDay = null
            content.post { mainScroll.smoothScrollTo(0, (target.top - dp(6f)).coerceAtLeast(0)) }
        }
        if (dispShowFor(key)) completedGroup(today)
    }

    /* ---------------- browse ---------------- */

    private fun menuItem(icon: String, title: String, smallTxt: String?, tag: String?, click: () -> Unit): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.background = Glass.panel(this, 22f)
        row.setPadding(dp(14f), dp(13f), dp(14f), dp(13f))
        row.layoutParams = lp(b = dp(10f))
        val ic = tv(icon, 19f); ic.layoutParams = LinearLayout.LayoutParams(dp(30f), -2)
        row.addView(ic)
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
        col.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        col.addView(tv(title, 15f))
        if (smallTxt != null) col.addView(tv(smallTxt, 13f, Glass.muted(this@MainActivity)))
        row.addView(col)
        if (tag != null) row.addView(tv(tag, 11f, Glass.gold(this@MainActivity), true))
        row.setOnClickListener { Sfx.tap(); click() }
        attachPressFx(row)
        return row
    }

    /* ---------------- Browse helpers (B1) ---------------- */

    /** One grouped #282828 container with hairline separators — the Browse
     *  groups are single cards, not separate rows (ANALYSIS §6). */
    private fun groupedMenuCard(rows: List<View>): LinearLayout {
        val g = LinearLayout(this); g.orientation = LinearLayout.VERTICAL
        g.background = roundedRect(cardFill(), 14f)
        g.layoutParams = lp(b = dp(10f))
        rows.forEachIndexed { i, r ->
            if (i > 0) {
                val h = hairline()
                h.layoutParams = LinearLayout.LayoutParams(-1, Math.max(1, dp(0.5f))).apply { leftMargin = dp(20f) }
                g.addView(h)
            }
            g.addView(r)
        }
        return g
    }

    private fun browseRow(icon: String, iconColor: Int, title: String, rightText: String?, click: () -> Unit): LinearLayout {
        val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(20f), dp(16f), dp(20f), dp(16f))
        val ic = tv(icon, 20f, iconColor)
        if (isV5()) {
            // §2.9: the glyph sits in a 32dp rounded-square well tinted
            // with its own semantic colour (~15%), glyph solid.
            ic.gravity = Gravity.CENTER
            ic.textSize = 16f
            ic.background = roundedRect(V5.tint(iconColor, 38), V5.R_ICONSQ)
            ic.layoutParams = LinearLayout.LayoutParams(dp(32f), dp(32f)).apply { rightMargin = dp(12f) }
        } else {
            ic.layoutParams = LinearLayout.LayoutParams(dp(40f), -2)
        }
        row.addView(ic)
        val tt = tv(title, 16f)
        tt.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        row.addView(tt)
        if (rightText != null) row.addView(tv(rightText, 14f, TD_GREY))
        row.setOnClickListener { Sfx.tap(); click() }
        attachPressFx(row)
        return row
    }

    private fun renderBrowse() {
        if (isV5()) { renderBrowseV5(); return }
        // Profile header (ANALYSIS §6): avatar with a karma progress arc,
        // name at 20sp bold, bell + gear at the right. No "Browse" title.
        val prof = LinearLayout(this); prof.orientation = LinearLayout.HORIZONTAL
        prof.gravity = Gravity.CENTER_VERTICAL
        prof.setPadding(dp(8f), dp(8f), dp(8f), dp(12f))
        val avatarWrap = FrameLayout(this)
        avatarWrap.layoutParams = LinearLayout.LayoutParams(dp(44f), dp(44f)).apply { rightMargin = dp(12f) }
        val ring = FocusRing(this)
        ring.ringColor = TD_SALMON
        ring.trackColor = android.graphics.Color.argb(60, 255, 255, 255)
        ring.fraction = Karma.levelFor(Store.state.karmaPoints).progress
        ring.layoutParams = FrameLayout.LayoutParams(-1, -1)
        avatarWrap.addView(ring)
        val av = tv("S", 17f, 0xFFFFFFFF.toInt(), true); av.gravity = Gravity.CENTER
        av.background = ovalDrawable(0xFF3A3A44.toInt())
        av.layoutParams = FrameLayout.LayoutParams(dp(34f), dp(34f)).apply { gravity = Gravity.CENTER }
        avatarWrap.addView(av)
        prof.addView(avatarWrap)
        val nm = tv("Shadow", 20f, Glass.text(this@MainActivity), true)
        nm.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        prof.addView(nm)
        val bell = tv("🔔", 20f); bell.gravity = Gravity.CENTER
        bell.layoutParams = LinearLayout.LayoutParams(dp(40f), dp(40f))
        bell.contentDescription = "Activity"
        bell.setOnClickListener { sub = "history"; render() }
        prof.addView(bell)
        val gear = tv("⚙", 22f); gear.gravity = Gravity.CENTER
        gear.layoutParams = LinearLayout.LayoutParams(dp(40f), dp(40f))
        gear.contentDescription = "Settings"
        gear.setOnClickListener { showSheet(appearanceCard()) }
        prof.addView(gear)
        content.addView(prof)
        content.addView(hairline())

        // Emphasis slot (#363636 — the old Try Pro banner's fill): the
        // Morning Brief / Smart Plan card lives here now (§15.3/§15.6).
        val brief = LinearLayout(this); brief.orientation = LinearLayout.VERTICAL
        brief.background = roundedRect(TD_EMPHASIS, 14f)
        brief.setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
        brief.layoutParams = lp(t = dp(12f), b = dp(10f))
        val hh2 = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val greet = if (hh2 < 12) "Good morning" else if (hh2 < 17) "Good afternoon" else "Good evening"
        brief.addView(tv("$greet, Sir", 16f, Glass.text(this@MainActivity), true))
        val over = Store.state.tasks.count { Dates.isOverdue(it) }
        val todayN = Store.state.tasks.count { it.dueDate == Dates.today() }
        val bsub = tv("$todayN tasks scheduled today" + if (over > 0) " · $over overdue" else " · nothing overdue", 13f, TD_GREY)
        bsub.layoutParams = lp(t = dp(4f))
        brief.addView(bsub)
        val brow = LinearLayout(this); brow.orientation = LinearLayout.HORIZONTAL
        brow.layoutParams = lp(t = dp(10f))
        val plan = ghostBtn("🤖 Smart plan my day") { sub = "smartplan"; render() }
        plan.layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(8f) }
        val focus = ghostBtn("🎯 Focus timer") { sub = "focus"; render() }
        focus.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        brow.addView(plan); brow.addView(focus)
        brief.addView(brow)
        content.addView(brief)

        // Primary group — red outline icons.
        content.addView(groupedMenuCard(listOf(
            browseRow("⭐", 0xFFFFD60A.toInt(), "Starred", Store.state.tasks.count { it.starred }.toString()) { sub = "starred"; render() },
            browseRow("⌕", TD_RED, "Search", null) { sub = "search"; render() },
            browseRow("▦", TD_RED, "Filters & Labels", null) { sub = "filterslabels"; render() },
            browseRow("◉", TD_RED, "Reporting", null) { sub = "history"; render() }
        )))

        // My Projects: header with + and collapse chevron, grouped rows
        // with right-aligned counts, then Manage projects.
        val mpHead = LinearLayout(this); mpHead.orientation = LinearLayout.HORIZONTAL
        mpHead.gravity = Gravity.CENTER_VERTICAL
        mpHead.layoutParams = lp(t = dp(14f), b = dp(8f))
        val mpT = tv("My Projects", 17f, Glass.text(this@MainActivity), true)
        mpT.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        mpHead.addView(mpT)
        val mpAdd = tv("+", 22f, Glass.text(this@MainActivity))
        mpAdd.setPadding(dp(10f), dp(2f), dp(10f), dp(2f))
        mpAdd.contentDescription = "New project"
        mpAdd.setOnClickListener { promptText("Project name:") { name -> Store.projByName(name); Store.save(); render() } }
        mpHead.addView(mpAdd)
        val mpChev = tv(if (browseProjectsCollapsed) "⌄" else "∧", 18f, Glass.text(this@MainActivity))
        mpChev.setPadding(dp(6f), dp(2f), dp(2f), dp(2f))
        mpChev.setOnClickListener { browseProjectsCollapsed = !browseProjectsCollapsed; render() }
        mpHead.addView(mpChev)
        content.addView(mpHead)
        if (!browseProjectsCollapsed) {
            val rows = mutableListOf<View>()
            if (Store.state.projects.isEmpty()) {
                rows.add(browseRow("#", TD_GREY, "No projects yet — tap + to add one", null) {})
            }
            Store.state.projects.forEach { pr ->
                val n = Store.state.tasks.count { it.projectId == pr.id }
                rows.add(browseRow("#", TD_GREY, pr.name, n.toString()) { sub = "project:" + pr.id; render() })
            }
            rows.add(browseRow("✎", Glass.text(this@MainActivity), "Manage projects", null) { showSheet(buildManageProjectsSheet()) })
            content.addView(groupedMenuCard(rows))
        }

        // Secondary group — grey-white icons (the red→grey hierarchy shift).
        content.addView(groupedMenuCard(listOf(
            browseRow("◎", Glass.text(this@MainActivity), "Add a team", null) {
                toast("Teams need an account — GhostBlade is fully offline, so this stays local-only")
            },
            browseRow("▤", Glass.text(this@MainActivity), "Browse templates", null) { showSheet(buildTemplatesSheet()) },
            browseRow("?", Glass.text(this@MainActivity), "Help & resources", null) { showSheet(buildHelpSheet()) }
        )))

        // More tools — formerly the "PRO — ALL UNLOCKED" group; the PRO
        // tags are gone because every feature ships unlocked.
        content.addView(dayGroup("MORE"))
        val more = listOf(
            Triple("calendar", "🗓️", "Calendar view"),
            Triple("stats", "📈", "Productivity"),
            Triple("performance", "📊", "Performance"),
            Triple("insights", "💡", "Project insights"),
            Triple("ramble", "🎙️", "Ramble"),
            Triple("assist", "🤖", "Task & Email Assist"),
            Triple("backups", "💾", "Backups & Sync")
        )
        content.addView(groupedMenuCard(more.map { (k, ic, n) ->
            browseRow(ic, Glass.text(this@MainActivity), n, null) { sub = k; render() }
        }))
        content.addView(appearanceCard())
        val about = LinearLayout(this); about.orientation = LinearLayout.VERTICAL
        about.background = roundedRect(cardFill(), 14f)
        about.setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
        about.layoutParams = lp(b = dp(10f))
        about.addView(tv("GhostBlade Task", 16f, Glass.text(this@MainActivity), true))
        val ab = tv("Kotlin edition · data lives on this device · reminders fire even when the app is closed and survive reboot.", 13f, TD_GREY)
        ab.layoutParams = lp(t = dp(4f))
        about.addView(ab)
        content.addView(about)
    }

    /** Combined Filters & Labels screen behind the Browse row of that
     *  name (ANALYSIS §6): the old separate Browse groups, regrouped. */
    private fun renderFiltersLabels() {
        content.addView(dayGroup("FILTERS"))
        val frows = mutableListOf<View>()
        Store.state.filters.forEach { f ->
            frows.add(browseRow("🔎", TD_RED, f.name, null) { sub = "filter:" + f.id; render() })
        }
        frows.add(browseRow("+", Glass.text(this@MainActivity), "New filter", null) {
            promptText("Filter name:") { name ->
                promptText("Filter query:") { q ->
                    Store.state.filters.add(FilterItem(uid(), name, q)); Store.save(); render()
                }
            }
        })
        content.addView(groupedMenuCard(frows))
        content.addView(dayGroup("LABELS"))
        if (Store.state.labels.isEmpty()) {
            content.addView(tv("Tag any task with @label in Quick Add — e.g. \"Call mom @family\".", 13f, Glass.muted(this@MainActivity)))
        }
        val lrows = mutableListOf<View>()
        Store.state.labels.forEach { l ->
            val n = Store.state.tasks.count { it.labelIds.contains(l.id) }
            lrows.add(browseRow("🏷️", TD_RED, "@" + l.name, n.toString()) { sub = "label:" + l.id; render() })
        }
        if (lrows.isNotEmpty()) content.addView(groupedMenuCard(lrows))
    }

    private fun buildManageProjectsSheet(): View {
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
        col.addView(tv("Manage projects", 17f, Glass.text(this@MainActivity), true))
        if (Store.state.projects.isEmpty()) col.addView(tv("No projects yet.", 13f, Glass.muted(this@MainActivity)))
        Store.state.projects.forEach { pr ->
            val n = Store.state.tasks.count { it.projectId == pr.id }
            val row = tv("#  " + pr.name + "  ·  $n open tasks", 15f)
            row.setPadding(dp(4f), dp(12f), dp(4f), dp(12f))
            row.setOnClickListener { closeSheet(); sub = "project:" + pr.id; render() }
            col.addView(row)
        }
        val add = ghostBtn("＋ New project") {
            promptText("Project name:") { name -> Store.projByName(name); Store.save(); closeSheet(); render() }
        }
        add.layoutParams = lp(t = dp(10f))
        col.addView(add)
        return col
    }

    private fun buildTemplatesSheet(): View {
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
        col.addView(tv("Browse templates", 17f, Glass.text(this@MainActivity), true))
        val note = tv("Templates live on this device only (offline rule). Tap one to create its project.", 13f, Glass.muted(this@MainActivity))
        note.layoutParams = lp(t = dp(4f), b = dp(6f))
        col.addView(note)
        if (Store.state.templates.isEmpty()) col.addView(tv("No templates yet — save a project as a template below.", 13f, Glass.muted(this@MainActivity)))
        Store.state.templates.forEach { tpl ->
            val row = tv("▤  " + tpl.name + "  →  " + tpl.projectName, 15f)
            row.setPadding(dp(4f), dp(12f), dp(4f), dp(12f))
            row.setOnClickListener {
                val np = Store.applyTemplate(tpl.id)
                closeSheet()
                if (np != null) { sub = "project:" + np.id; toast("Project created from template") }
                render()
            }
            col.addView(row)
        }
        Store.state.projects.forEach { pr ->
            val save = ghostBtn("Save “" + pr.name + "” as template") {
                promptText("Template name:") { name ->
                    Store.saveProjectAsTemplate(pr.id, name)
                    toast("Template saved")
                    showSheet(buildTemplatesSheet())
                }
            }
            save.layoutParams = lp(t = dp(8f))
            col.addView(save)
        }
        // Checklist templates (v5, SHADOW-THREE-DESIGN §8.3): applying
        // one creates a single task whose subtasks are the items.
        col.addView(dayGroup("CHECKLISTS"))
        if (Store.state.checklistTemplates.isEmpty()) {
            col.addView(tv("No checklist templates yet — save one from a task's subtasks in its editor.", 13f, Glass.muted(this@MainActivity)))
        }
        Store.state.checklistTemplates.forEach { tpl ->
            val block = LinearLayout(this); block.orientation = LinearLayout.VERTICAL
            block.setPadding(dp(4f), dp(10f), dp(4f), dp(10f))
            val headR = LinearLayout(this); headR.orientation = LinearLayout.HORIZONTAL
            headR.gravity = Gravity.CENTER_VERTICAL
            val nm = tv(tpl.name, 15f, Glass.text(this@MainActivity), true)
            nm.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            headR.addView(nm)
            val del = tv("✕", 14f, Glass.muted(this@MainActivity))
            del.contentDescription = "Delete checklist template"
            del.setOnClickListener { Store.deleteChecklistTemplate(tpl.id); showSheet(buildTemplatesSheet()) }
            headR.addView(del)
            block.addView(headR)
            val prev = tv(
                tpl.items.take(3).joinToString(" · ") + "\n" + tpl.items.size + " items total",
                13f, Glass.muted(this@MainActivity))
            prev.layoutParams = lp(t = dp(2f))
            block.addView(prev)
            val apply = ghostBtn("＋ Use this checklist") {
                Store.applyChecklistTemplate(tpl.id, null)
                Sfx.add(); closeSheet(); toast("Checklist added to Inbox"); render()
            }
            apply.layoutParams = lp(t = dp(6f))
            block.addView(apply)
            col.addView(block)
        }
        return col
    }

    private fun buildHelpSheet(): View {
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
        col.addView(tv("Help & resources", 17f, Glass.text(this@MainActivity), true))
        val t1 = tv("GhostBlade Task is fully offline: no account, no internet permission, no tracking. Your tasks, comments and backups never leave this device.", 14f, Glass.muted(this@MainActivity))
        t1.layoutParams = lp(t = dp(6f))
        col.addView(t1)
        val t2 = tv("Quick Add understands plain words: \"Gym tomorrow at 7am every day p1 #Health @home\". Type !! for priority, # for a project. The Display icon at the top right changes layout, grouping, sorting and filters for each list.", 14f, Glass.muted(this@MainActivity))
        t2.layoutParams = lp(t = dp(6f))
        col.addView(t2)
        return col
    }

    private fun appearanceCard(): LinearLayout {
        val c = card()
        c.addView(tv("🪟 Appearance", 16f, Glass.text(this@MainActivity), true))
        c.addView(label("Theme"))
        val tRow = LinearLayout(this); tRow.orientation = LinearLayout.HORIZONTAL
        listOf("v5" to "✦ V5", "todoist" to "✓ Todoist", "aurora" to "🌌 Aurora", "black" to "⬛ Black", "white" to "⬜ White").forEach { (v, l) ->
            val ch = chipBtn(l, Prefs.theme(this) == v) { Prefs.set(this, "theme", v); applyTheme() }
            ch.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6f) }
            tRow.addView(ch)
        }
        c.addView(tRow)
        val tNote = tv("V5 = the new flat dark (default) · Todoist = flat dark · Black = Apple Music dark · White = frosted light · Aurora = colour wallpapers below", 13f, Glass.muted(this@MainActivity))
        tNote.layoutParams = lp(t = dp(4f), b = dp(2f))
        c.addView(tNote)
        c.addView(label("Glass style"))
        val gRow = LinearLayout(this); gRow.orientation = LinearLayout.HORIZONTAL
        listOf("clear" to "Fully Clear", "default" to "Default", "tinted" to "Fully Tinted").forEach { (v, l) ->
            val ch = chipBtn(l, Prefs.glass(this) == v) {
                Prefs.set(this, "glass", v); refreshWallpaper(); render()
            }
            ch.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6f) }
            gRow.addView(ch)
        }
        c.addView(gRow)
        c.addView(label("Sound effects"))
        val sRow = LinearLayout(this); sRow.orientation = LinearLayout.HORIZONTAL
        listOf(true to "🔊 On", false to "🔇 Off").forEach { (v, l) ->
            val ch = chipBtn(l, Sfx.enabled() == v) {
                Prefs.setBool(this, "sounds", v)
                if (v) Sfx.add()
                render()
            }
            ch.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6f) }
            sRow.addView(ch)
        }
        c.addView(sRow)
        c.addView(label("Accent color"))
        val aRow = LinearLayout(this); aRow.orientation = LinearLayout.HORIZONTAL
        Accents.MAP.keys.forEach { name ->
            val sw = View(this)
            val g = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(Accents.MAP[name]!![1], Accents.MAP[name]!![2])
            )
            g.cornerRadius = dp(22f).toFloat()
            if (Prefs.accent(this) == name) g.setStroke(dp(2f), 0xFFFFFFFF.toInt())
            sw.background = g
            sw.layoutParams = LinearLayout.LayoutParams(dp(40f), dp(40f)).apply { rightMargin = dp(8f) }
            sw.setOnClickListener { Prefs.set(this, "accent", name); Sfx.tick(); render() }
            aRow.addView(sw)
        }
        c.addView(aRow)
        c.addView(label("Red text"))
        val rRow = LinearLayout(this); rRow.orientation = LinearLayout.HORIZONTAL
        listOf(true to "🔴 On", false to "⚪ Off").forEach { (v, l) ->
            val ch = chipBtn(l, Prefs.redText(this) == v) { Prefs.setBool(this, "red_text", v); render() }
            ch.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6f) }
            rRow.addView(ch)
        }
        c.addView(rRow)
        val rNote = tv("Detail lines (dates, subtitles, meta) in your accent colour — the Apple Music look. Pairs best with crimson or pink.", 13f, Glass.muted(this@MainActivity))
        rNote.layoutParams = lp(t = dp(4f), b = dp(2f))
        c.addView(rNote)
        c.addView(label("Background wallpaper"))
        val wRow = LinearLayout(this); wRow.orientation = LinearLayout.HORIZONTAL
        listOf("aurora", "sunset", "ocean", "forest", "crimson", "mono").forEach { name ->
            val sw = View(this)
            sw.background = miniWallpaper(name, Prefs.wallpaper(this) == name)
            sw.layoutParams = LinearLayout.LayoutParams(dp(40f), dp(40f)).apply { rightMargin = dp(8f) }
            sw.setOnClickListener { Prefs.set(this, "wallpaper", name); refreshWallpaper(); render() }
            wRow.addView(sw)
        }
        if (File(filesDir, "wallpaper.jpg").exists()) {
            val sw = tv("🖼️", 17f); sw.gravity = Gravity.CENTER
            sw.background = Glass.chip(this, Prefs.wallpaper(this) == "custom")
            sw.layoutParams = LinearLayout.LayoutParams(dp(40f), dp(40f))
            sw.setOnClickListener { Prefs.set(this, "wallpaper", "custom"); refreshWallpaper(); render() }
            wRow.addView(sw)
        }
        c.addView(wRow)
        val up = ghostBtn("🖼️ Upload photo") {
            val i = Intent(Intent.ACTION_PICK)
            i.type = "image/*"
            startActivityForResult(i, 55)
        }
        up.layoutParams = lp(t = dp(10f))
        c.addView(up)
        return c
    }

    private fun miniWallpaper(name: String, on: Boolean): android.graphics.drawable.GradientDrawable {
        val colors = when (name) {
            "sunset" -> intArrayOf(0xFFFF8C3A.toInt(), 0xFFFF3C6E.toInt(), 0xFF7828B4.toInt())
            "ocean" -> intArrayOf(0xFF00C8FF.toInt(), 0xFF005AFF.toInt(), 0xFF00FFBE.toInt())
            "forest" -> intArrayOf(0xFF78FFA0.toInt(), 0xFF00A06E.toInt(), 0xFFBEFF5A.toInt())
            "crimson" -> intArrayOf(0xFFFF463C.toInt(), 0xFFA00028.toInt(), 0xFFFF7828.toInt())
            "mono" -> intArrayOf(0xFFC8C8DC.toInt(), 0xFF3C3C4C.toInt(), 0xFF23232E.toInt())
            else -> intArrayOf(0xFF5E5CE6.toInt(), 0xFF0A84FF.toInt(), 0xFFFF375F.toInt())
        }
        val g = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR, colors)
        g.cornerRadius = dp(20f).toFloat()
        if (on) g.setStroke(dp(2f), 0xFFFFFFFF.toInt())
        return g
    }

    private fun refreshWallpaper() {
        wallpaperHolder.removeAllViews()
        wallpaperHolder.addView(Glass.wallpaperView(this), FrameLayout.LayoutParams(-1, -1))
    }

    /** Status bar follows the theme: dark icons on the white frost theme;
     *  in the Todoist theme the status bar is the screen colour #212121
     *  (TEAMONE-IMPL-A note for B2). */
    private fun applyStatusBar() {
        val white = Glass.isWhite(this)
        val todoist = Glass.isTodoist(this)
        window.statusBarColor = when {
            white -> 0xFFF2F2F6.toInt()
            todoist -> Todoist.BG
            else -> 0xFF06060A.toInt()
        }
        window.navigationBarColor = when {
            white -> 0xFFF2F2F6.toInt()
            todoist -> Todoist.NAV
            else -> 0xFF06060A.toInt()
        }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (white) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else 0
    }

    /** Re-skin the persistent chrome (header, nav, undo bar) after a theme
     *  change; screens and sheets pick the theme up as they render. */
    private fun applyTheme() {
        refreshWallpaper()
        headerCard.background = null
        styleNavBar()
        undoBar.background = Glass.panel(this, 22f, strong = true)
        applyStatusBar()
        render()
        // The home-screen widget follows the theme too (light layout exists).
        TaskWidget.updateAll(this)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 56 && resultCode == RESULT_OK && data?.data != null) {
            try {
                val text = contentResolver.openInputStream(data.data!!)?.readBytes()?.toString(Charsets.UTF_8) ?: ""
                if (Store.importData(text)) {
                    toast("Sync file imported ✅")
                    view = "today"; sub = null; render()
                } else toast("That file doesn't look like a GhostBlade sync file")
            } catch (e: Exception) {
                toast("Could not read that file")
            }
            return
        }
        if (requestCode == 57 && resultCode == RESULT_OK && data != null) {
            val results = data.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
            val spoken = results?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                // Land the words in the Ramble box for review instead of
                // creating a task instantly — mis-heard speech can be fixed.
                // Append to anything already typed rather than replacing it.
                rambleText = if (rambleText.isBlank()) spoken else rambleText.trimEnd('\n') + "\n" + spoken
                Sfx.tick()
                toast("Review your words, then tap “Add all tasks” 🎙️")
                sub = "ramble"; render()
            }
            return
        }
        if (requestCode == 55 && resultCode == RESULT_OK && data?.data != null) {
            try {
                val uri = data.data!!
                val src = contentResolver.openInputStream(uri) ?: return
                val opts = BitmapFactory.Options()
                opts.inSampleSize = 2
                val bmp = BitmapFactory.decodeStream(src, null, opts) ?: return
                src.close()
                val scale = Math.min(1.0, 1080.0 / Math.max(bmp.width, bmp.height))
                val out = if (scale < 1.0) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
                val fos = FileOutputStream(File(filesDir, "wallpaper.jpg"))
                out.compress(Bitmap.CompressFormat.JPEG, 82, fos)
                fos.close()
                Prefs.set(this, "wallpaper", "custom")
                refreshWallpaper(); render()
                toast("Wallpaper set 🖼️")
            } catch (e: Exception) {
                toast("Could not read that photo")
            }
        }
    }

    /* ---------------- sub views ---------------- */

    private fun renderSub(s: String) {
        when {
            s.startsWith("project:") -> {
                boardMode = dispLayoutFor(s) == "board"
                val p = Store.state.projects.find { it.id == s.substring(8) }
                if (p == null) { content.addView(emptyView("📁", "Project not found.")); return }
                if (isV5()) {
                    // §3.5.1/§3.5.2 under v5: a project meta row (12dp
                    // colour dot + open count — the name itself lives
                    // in the §2.1b global header) and the List|Board
                    // switch as the §2.10 segmented control, not
                    // chipBtn pills.
                    val prow = LinearLayout(this); prow.orientation = LinearLayout.HORIZONTAL
                    prow.gravity = Gravity.CENTER_VERTICAL
                    prow.layoutParams = lp(b = dp(10f))
                    val dot = View(this)
                    dot.background = ovalDrawable(try {
                        android.graphics.Color.parseColor(p.color)
                    } catch (e: Exception) { V5.FAINT })
                    dot.layoutParams = LinearLayout.LayoutParams(dp(12f), dp(12f)).apply { rightMargin = dp(8f) }
                    prow.addView(dot)
                    val openN = Store.state.tasks.count { it.projectId == p.id }
                    prow.addView(tv(openN.toString() + if (openN == 1) " open task" else " open tasks", 13f, V5.FAINT))
                    content.addView(prow)
                    val seg = v5Segmented(listOf("list" to "List", "board" to "Board"),
                        if (boardMode) "board" else "list") { mode ->
                        boardMode = mode == "board"; Sfx.tick(); render()
                    }
                    seg.layoutParams = LinearLayout.LayoutParams(-1, dp(36f)).apply { bottomMargin = dp(12f) }
                    content.addView(seg)
                } else {
                    val toggle = LinearLayout(this); toggle.orientation = LinearLayout.HORIZONTAL
                    toggle.layoutParams = lp(b = dp(10f))
                    val lBtn = chipBtn("☰ List", !boardMode) { boardMode = false; render() }
                    val bBtn = chipBtn("▦ Board", boardMode) { boardMode = true; render() }
                    lBtn.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6f) }
                    toggle.addView(lBtn); toggle.addView(bBtn)
                    content.addView(toggle)
                }
                if (boardMode) renderBoard(p)
                else {
                    val projTasks = Store.state.tasks.filter { it.projectId == p.id }
                    // Sections own the manual grouping (Correction Round 2,
                    // F-2): when the project has sections and the Display
                    // sheet's grouping is None, render by section; any other
                    // Display grouping keeps the standard pipeline.
                    val hasSections = Store.state.sections.any { it.projectId == p.id }
                    if (hasSections && dispGroupFor(displayKey()) == GroupMode.NONE) {
                        renderProjectSections(p, projTasks)
                    } else {
                        renderTaskList(Store.sortTasks(projTasks), "📁", "No open tasks in this project.")
                    }
                }
            }
            s.startsWith("filter:") -> {
                val f = Store.state.filters.find { it.id == s.substring(7) }
                if (f == null) { content.addView(emptyView("🔎", "Filter not found.")); return }
                val list = Store.sortTasks(Store.state.tasks.filter { Filters.eval(Store.state, it, f.query) })
                renderTaskList(list, "🔎", "No tasks match \"${f.query}\".")
                val del = tv("Delete this filter", 15f, Glass.overdue(this@MainActivity))
                del.gravity = Gravity.CENTER
                del.setPadding(dp(12f), dp(14f), dp(12f), dp(14f))
                del.setOnClickListener {
                    Sfx.del()
                    Store.state.filters.removeAll { it.id == f.id }
                    sub = null; Store.save(); render()
                }
                content.addView(del)
            }
            s.startsWith("label:") -> {
                val l = Store.state.labels.find { it.id == s.substring(6) }
                if (l == null) { content.addView(emptyView("🏷️", "Label not found.")); return }
                val list = Store.sortTasks(Store.state.tasks.filter { it.labelIds.contains(l.id) })
                renderTaskList(list, "🏷️", "No open tasks with @${l.name}.")
                val del = tv("Delete this label", 15f, Glass.overdue(this@MainActivity))
                del.gravity = Gravity.CENTER
                del.setPadding(dp(12f), dp(14f), dp(12f), dp(14f))
                del.setOnClickListener {
                    Sfx.del()
                    Store.state.labels.removeAll { it.id == l.id }
                    Store.state.tasks.forEach { t -> t.labelIds.removeAll { it == l.id } }
                    sub = null; Store.save(); render(); toast("Label deleted")
                }
                content.addView(del)
            }
            s.startsWith("habit:") -> renderHabitDetail(s.substring(6))
            s == "habits" -> renderHabits()
            s == "habitform" -> renderHabitForm()
            s == "matrix" -> renderMatrix()
            s == "matrixlist" -> renderTaskList(
                Store.sortTasks(Store.state.tasks.filter { quadrantOf(it) == matrixQuadSel }),
                "⊞", "Nothing in this quadrant.")
            s == "countdowns" -> renderCountdowns()
            s == "dock" -> renderDockSettings()
            s == "starred" -> renderTaskList(
                Store.sortTasks(Store.state.tasks.filter { it.starred }),
                "⭐", "No starred tasks yet — star one from its editor or with a left-swipe.")
            s == "filterslabels" -> renderFiltersLabels()
            s == "search" -> renderSearch()
            s == "calendar" -> renderCalendar()
            s == "focus" -> renderFocus()
            s == "stats" -> renderStats()
            s == "performance" -> renderPerformance()
            s == "insights" -> renderInsights()
            s == "history" -> renderHistory()
            s == "ramble" -> renderRamble()
            s == "assist" -> renderAssist()
            s == "backups" -> renderBackups()
            s == "smartplan" -> renderSmartPlan()
            else -> content.addView(emptyView("🛠️", "Unknown screen."))
        }
    }

    /** Day the activity last saw — onResume rolls the analytics over when
     *  the date changes under a still-open app (see Db.rollover). */
    private var lastSeenDay = ""
    /** Text state for Search / Ramble / Assist lives in fields, not in the
     *  views: render() rebuilds screens constantly, and render-local text
     *  was silently wiped by every rebuild. */
    private var searchQuery = ""
    private var rambleText = ""
    private var assistText = ""

    /* ----- v5 state (SHADOW-THREE / SHADOW-FOUR designs) ----- */
    private var v5DaySel: String = Dates.today()
    /** One-shot scroll target for Upcoming (§3.3): set by a day-pill
     *  tap, consumed by the next renderUpcomingV5 pass. */
    private var upcomingScrollDay: String? = null
    /** Productivity range for the v5 Stats screen (§3.7):
     *  week | month | year | all. */
    private var statsRange = "week"
    /** Countdown sheet's chosen date (the date-sheet picker writes
     *  here; the sheet's Save reads it). */
    private var cdDate: String = Dates.today()
    private var habitDaySel: String = Dates.today()
    private var habitFilterDue = false
    private var habitLibQuery = ""
    private var adoptTpl: HabitTemplate? = null
    private val adoptDays = mutableSetOf<Int>()
    private val adoptTimes = mutableListOf<String>()
    private var adoptPhraseIn: EditText? = null
    private var habitFormId: String? = null
    private var hfName = ""
    private var hfDesc = ""
    private var hfColor = "#d9a441"
    private var hfIcon = "circle"
    private var hfKind = "build"
    private var hfFreqType = "daily"
    private val hfDays = mutableSetOf<Int>()
    private val hfMonthDays = mutableSetOf<Int>()
    private var hfFreqCount = 3
    private var hfFreqPeriod = 7
    private var hfTracking = "tick"
    private var hfTarget = 1
    private var hfUnit = ""
    private val hfChecklist = mutableListOf<String>()
    private var hfPhrase = ""
    private val hfTimes = mutableListOf<String>()
    private var hfExpandedHabit: String? = null
    private var matrixQuadSel = 0
    private var statsYearSel: Int = Dates.parse(Dates.today()).get(java.util.Calendar.YEAR)
    private var statsWeekStartSel: String = Stats.weekStart(Dates.today())
    private var heatCaption = ""
    private val qaSubtasks = mutableListOf<String>()
    private var qaShowSubtasks = false
    private var qaHintIdx = 0
    private var qaHintRun: Runnable? = null
    private var taskDateEditId: String? = null
    private var cdColor = "#d9a441"
    private val qaHints = listOf(
        "Yoga class at 6 tonight", "Call Mom tonight at 8",
        "Dentist appointment next Tuesday", "Write script tomorrow 9am #Must",
        "What's today's plan?"
    )

    private fun renderSearch() {
        val inputE = input("Search tasks & notes…", searchQuery)
        val results = LinearLayout(this); results.orientation = LinearLayout.VERTICAL
        fun showResults() {
            val q = searchQuery.trim().lowercase()
            results.removeAllViews()
            if (q.isEmpty()) { results.addView(emptyView("🔍", "Type to search across all your tasks.")); return }
            val list = Store.sortTasks(Store.state.tasks.filter { (it.title + " " + it.notes).lowercase().contains(q) })
            if (list.isEmpty()) results.addView(emptyView("🫥", "No tasks match \"$q\"."))
            else list.forEach { results.addView(taskRow(it)) }
        }
        showResults()
        inputE.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(e: Editable?) {
                searchQuery = e?.toString() ?: ""
                showResults()
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        content.addView(inputE)
        content.addView(results)
        inputE.requestFocus()
        inputE.setSelection(inputE.text.length)
    }

    /* ---------------- overflow menu / comments (B1) ---------------- */

    private fun currentListTasks(): List<TaskItem> = when {
        sub?.startsWith("project:") == true -> Store.state.tasks.filter { it.projectId == sub!!.substring(8) }
        sub == null && view == "inbox" -> Store.state.tasks.filter { it.projectId == null }
        sub == null && view == "today" -> Store.state.tasks.filter { it.dueDate == Dates.today() || Dates.isOverdue(it) }
        else -> Store.state.tasks
    }

    private fun copyToClipboard(labelText: String, text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText(labelText, text))
    }

    /** Per-view ⋮ contents (COMPARE F2): Inbox gets the full 6 actions;
     *  Today gets only Select tasks / View activity. The menu is an
     *  anchored system popup under the ⋮ button. */
    private fun showOverflowMenu(anchor: View) {
        val pm = android.widget.PopupMenu(this, anchor)
        val isProject = sub?.startsWith("project:") == true
        val isToday = sub == null && view == "today"
        val isInbox = sub == null && view == "inbox"
        var nextId = 1
        val ids = mutableMapOf<Int, String>()
        fun add(action: String, labelText: String) {
            pm.menu.add(0, nextId, 0, labelText); ids[nextId] = action; nextId++
        }
        if (isToday) {
            add("select", "Select tasks"); add("activity", "View activity")
        } else if (isInbox || isProject) {
            add("section", "Add section")
            add("copy", if (isProject) "Copy link to project" else "Copy link to Inbox")
            add("select", "Select tasks")
            add("email", if (isProject) "Email tasks" else "Email tasks to Inbox")
            add("comments", "Comments")
            add("activity", "View activity")
        } else {
            add("select", "Select tasks"); add("activity", "View activity")
        }
        pm.setOnMenuItemClickListener { item ->
            when (ids[item.itemId]) {
                "section" -> {
                    if (isProject) {
                        val pid = sub!!.substring(8)
                        promptText("Section name:") { name -> Store.addSection(pid, name); toast("Section added"); render() }
                    } else {
                        // Store sections are project-scoped only, so the
                        // closest faithful Inbox behaviour (B2): pick the
                        // target project first, then name the section.
                        if (Store.state.projects.isEmpty()) {
                            toast("Create a project first — sections live inside projects")
                        } else {
                            AlertDialog.Builder(this).setTitle("Add section to…")
                                .setItems(Store.state.projects.map { "# " + it.name }.toTypedArray()) { _, w ->
                                    val pr = Store.state.projects[w]
                                    promptText("Section name:") { name ->
                                        Store.addSection(pr.id, name)
                                        toast("Section added to #" + pr.name)
                                    }
                                }.show()
                        }
                    }
                }
                "copy" -> {
                    val link = if (isProject) "ghostblade://project/" + sub!!.substring(8) else "ghostblade://inbox"
                    copyToClipboard("GhostBlade link", link); toast("Link copied")
                }
                "select" -> enterSelectMode()
                "email" -> {
                    // Offline equivalent of Todoist's email-the-list action.
                    val text = currentListTasks().joinToString("\n") { "• " + it.title }
                    copyToClipboard("GhostBlade tasks", text.ifEmpty { "No tasks" })
                    toast("Tasks copied — paste them into any email")
                }
                "comments" -> showSheet(buildCommentsSheet(if (isProject) sub!!.substring(8) else null))
                "activity" -> { sub = "history"; render() }
            }
            true
        }
        pm.show()
    }

    private fun buildCommentsSheet(projectId: String?): View {
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
        col.addView(tv("Comments", 17f, Glass.text(this@MainActivity), true))
        val comments = Store.commentsFor(projectId)
        if (comments.isEmpty()) col.addView(tv("No comments yet. Add the first one below.", 13f, Glass.muted(this@MainActivity)))
        comments.forEach { c ->
            val r = LinearLayout(this); r.orientation = LinearLayout.VERTICAL
            r.layoutParams = lp(t = dp(10f))
            r.addView(tv(c.text, 15f))
            val metaRow = LinearLayout(this); metaRow.orientation = LinearLayout.HORIZONTAL
            val whenT = tv(fmtWhen(c.at), 13f, Glass.muted(this@MainActivity))
            whenT.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            metaRow.addView(whenT)
            val del = tv("Delete", 13f, Glass.overdue(this@MainActivity))
            del.setOnClickListener { Store.deleteComment(c.id); showSheet(buildCommentsSheet(projectId)) }
            metaRow.addView(del)
            r.addView(metaRow)
            col.addView(r)
        }
        val add = accentBtn("＋ Add comment") {
            promptText("Comment:") { text ->
                Store.addComment(projectId, null, text)
                showSheet(buildCommentsSheet(projectId))
            }
        }
        add.layoutParams = lp(t = dp(14f))
        col.addView(add)
        return col
    }

    /* ---------------- Display sheet (B1) ---------------- */

    /** Full-height, edge-to-edge sheet variant for Display (ANALYSIS §2:
     *  it occupies the entire screen on a flat #212121, no scrim view of
     *  the list behind it). Mirrors showSheet's bookkeeping. */
    private fun showFullSheet(sheetContent: View) {
        sheetGen++
        sheetClosing = false
        sheetIsQa = false
        sheetRoot.removeAllViews()
        val scrim = View(this)
        // v5: the full-height sheet (Display) sits on V5.BG — TD_BG is
        // the Todoist skin's colour and off-palette under v5.
        scrim.setBackgroundColor(if (isV5()) V5.BG else TD_BG)
        sheetRoot.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.setBackgroundColor(if (isV5()) V5.BG else TD_BG)
        panel.setPadding(dp(20f), dp(10f), dp(20f), dp(16f))
        panel.layoutParams = FrameLayout.LayoutParams(-1, -1)
        val handle = View(this)
        val hg = android.graphics.drawable.GradientDrawable()
        hg.cornerRadius = dp(2f).toFloat()
        hg.setColor(if (isV5()) V5.HANDLE else 0xFF595959.toInt())
        handle.background = hg
        handle.layoutParams = LinearLayout.LayoutParams(dp(36f), dp(4f)).apply {
            gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(12f)
        }
        panel.addView(handle)
        val scroll = ScrollView(this)
        scroll.layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        scroll.addView(sheetContent)
        panel.addView(scroll)
        sheetRoot.addView(panel)
        sheetRoot.visibility = View.VISIBLE
        fabBtn.visibility = View.GONE
        navBar.visibility = View.GONE
        undoWasVisible = undoBar.visibility == View.VISIBLE
        if (undoWasVisible) undoBar.visibility = View.GONE
        Sfx.open()
        panel.translationY = dp(40f).toFloat()
        panel.alpha = 0.4f
        panel.animate().translationY(0f).alpha(1f).setDuration(300).setInterpolator(sheetEase).start()
    }

    private fun showDisplaySheet() { showFullSheet(buildDisplaySheet()) }

    /** Radio: ring with a centre dot when selected (ANALYSIS §2 — not a
     *  filled circle). */
    private fun radioView(on: Boolean): FrameLayout {
        val wrap = FrameLayout(this)
        wrap.layoutParams = LinearLayout.LayoutParams(dp(22f), dp(22f))
        val ring = View(this)
        val rd = android.graphics.drawable.GradientDrawable()
        rd.shape = android.graphics.drawable.GradientDrawable.OVAL
        rd.setColor(android.graphics.Color.TRANSPARENT)
        rd.setStroke(dp(2f), if (on) TD_RED else TD_GREY)
        ring.background = rd
        ring.layoutParams = FrameLayout.LayoutParams(-1, -1)
        wrap.addView(ring)
        if (on) {
            val dot = View(this)
            dot.background = ovalDrawable(TD_RED)
            dot.layoutParams = FrameLayout.LayoutParams(dp(10f), dp(10f)).apply { gravity = Gravity.CENTER }
            wrap.addView(dot)
        }
        return wrap
    }

    /** Switch: OFF = hollow track (transparent fill, grey outline) with a
     *  solid light-grey knob at the left; ON = red track, white knob right. */
    private fun switchView(on: Boolean): FrameLayout {
        val wrap = FrameLayout(this)
        wrap.layoutParams = LinearLayout.LayoutParams(dp(52f), dp(32f))
        val track = View(this)
        val td = android.graphics.drawable.GradientDrawable()
        td.cornerRadius = dp(16f).toFloat()
        // §3.8.2: under v5 a toggle's ON state is DONE green (the
        // platform convention; green is outside the accent budget).
        if (on) td.setColor(if (isV5()) V5.DONE else TD_RED) else {
            td.setColor(android.graphics.Color.TRANSPARENT)
            td.setStroke(dp(2f), TD_GREY)
        }
        track.background = td
        track.layoutParams = FrameLayout.LayoutParams(-1, -1)
        wrap.addView(track)
        val knob = View(this)
        knob.background = ovalDrawable(if (on) 0xFFFFFFFF.toInt() else 0xFFB0B0B0.toInt())
        knob.layoutParams = FrameLayout.LayoutParams(dp(24f), dp(24f)).apply {
            gravity = if (on) Gravity.CENTER_VERTICAL or Gravity.END else Gravity.CENTER_VERTICAL or Gravity.START
            if (on) rightMargin = dp(4f) else leftMargin = dp(4f)
        }
        wrap.addView(knob)
        return wrap
    }

    private fun displayRowCard(icon: String, labelText: String, value: String, click: () -> Unit): LinearLayout {
        val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.background = roundedRect(cardFill(), 14f)
        row.setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
        row.layoutParams = lp(b = dp(8f))
        val ic = tv(icon, 20f, Glass.text(this@MainActivity))
        ic.layoutParams = LinearLayout.LayoutParams(dp(36f), -2)
        row.addView(ic)
        val colL = LinearLayout(this); colL.orientation = LinearLayout.VERTICAL
        colL.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        colL.addView(tv(labelText, 16f))
        colL.addView(tv(value, 14f, TD_GREY))
        row.addView(colL)
        row.setOnClickListener { click() }
        attachPressFx(row)
        return row
    }

    private fun buildDisplaySheet(): View {
        val key = displayKey()
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
        val titleRow = LinearLayout(this); titleRow.orientation = LinearLayout.HORIZONTAL
        titleRow.gravity = Gravity.CENTER_VERTICAL
        val dt = tv("Display", 24f, Glass.text(this@MainActivity), false)
        dt.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        titleRow.addView(dt)
        val help = tv("?", 15f, Glass.text(this@MainActivity))
        help.gravity = Gravity.CENTER
        val hd = android.graphics.drawable.GradientDrawable()
        hd.shape = android.graphics.drawable.GradientDrawable.OVAL
        hd.setStroke(dp(1.5f), TD_GREY)
        hd.setColor(android.graphics.Color.TRANSPARENT)
        help.background = hd
        help.layoutParams = LinearLayout.LayoutParams(dp(26f), dp(26f))
        help.setOnClickListener { toast("Layout, grouping, sorting and filters for this list. Every feature is unlocked.") }
        titleRow.addView(help)
        col.addView(titleRow)

        // Layout — three visual options with mini phone mock-ups + radios.
        val layLbl = tv("Layout", 17f, Glass.text(this@MainActivity), true)
        layLbl.layoutParams = lp(t = dp(24f), b = dp(10f))
        col.addView(layLbl)
        val layCard = LinearLayout(this); layCard.orientation = LinearLayout.HORIZONTAL
        layCard.background = roundedRect(cardFill(), 14f)
        layCard.setPadding(dp(12f), dp(18f), dp(12f), dp(18f))
        val curLayout = dispLayoutFor(key)
        listOf("list" to "List", "board" to "Board", "calendar" to "Calendar").forEach { (v, lbl) ->
            val on = curLayout == v
            val opt = LinearLayout(this); opt.orientation = LinearLayout.VERTICAL
            opt.gravity = Gravity.CENTER_HORIZONTAL
            opt.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            val phone = FrameLayout(this)
            val pd2 = android.graphics.drawable.GradientDrawable()
            pd2.cornerRadius = dp(8f).toFloat()
            pd2.setStroke(dp(2f), if (on) TD_RED else TD_GREY)
            pd2.setColor(if (on) 0xFF3A2323.toInt() else android.graphics.Color.TRANSPARENT)
            phone.background = pd2
            phone.layoutParams = LinearLayout.LayoutParams(dp(34f), dp(62f))
            val lines = LinearLayout(this); lines.orientation = LinearLayout.VERTICAL
            lines.setPadding(dp(6f), dp(8f), dp(6f), dp(8f))
            for (i in 0 until 3) {
                val ln = View(this)
                ln.background = roundedRect(if (on) TD_RED else TD_GREY, 2f)
                ln.layoutParams = LinearLayout.LayoutParams(-1, dp(4f)).apply { bottomMargin = dp(5f) }
                lines.addView(ln)
            }
            phone.addView(lines)
            opt.addView(phone)
            val lb = tv(lbl, 15f, if (on) TD_RED else Glass.text(this@MainActivity))
            lb.layoutParams = lp(t = dp(8f), b = dp(8f))
            opt.addView(lb)
            opt.addView(radioView(on))
            opt.setOnClickListener { setDispLayout(key, v); Sfx.tick(); showDisplaySheet() }
            layCard.addView(opt)
        }
        col.addView(layCard)

        // Completed tasks toggle (persisted per view — B2).
        val compCard = LinearLayout(this); compCard.orientation = LinearLayout.HORIZONTAL
        compCard.gravity = Gravity.CENTER_VERTICAL
        compCard.background = roundedRect(cardFill(), 14f)
        compCard.setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
        compCard.layoutParams = lp(t = dp(10f))
        val compIcon = tv("✓", 14f, Glass.text(this@MainActivity), true)
        compIcon.gravity = Gravity.CENTER
        val cd = android.graphics.drawable.GradientDrawable()
        cd.shape = android.graphics.drawable.GradientDrawable.OVAL
        cd.setStroke(dp(1.5f), TD_GREY)
        cd.setColor(android.graphics.Color.TRANSPARENT)
        compIcon.background = cd
        compIcon.layoutParams = LinearLayout.LayoutParams(dp(24f), dp(24f)).apply { rightMargin = dp(12f) }
        compCard.addView(compIcon)
        val compLbl = tv("Completed tasks", 16f)
        compLbl.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        compCard.addView(compLbl)
        compCard.addView(switchView(dispShowFor(key)))
        compCard.setOnClickListener {
            setDispShow(key, !dispShowFor(key))
            Sfx.tick(); showDisplaySheet()
        }
        col.addView(compCard)

        // Sort: Grouping + Sorting rows wired to DisplayEngine state.
        val sortLbl = tv("Sort", 17f, Glass.text(this@MainActivity), true)
        sortLbl.layoutParams = lp(t = dp(24f), b = dp(10f))
        col.addView(sortLbl)
        val groupVal = when (dispGroupFor(key)) {
            GroupMode.NONE -> "None"; GroupMode.DATE -> "Date"
            GroupMode.PROJECT -> "Project"; GroupMode.PRIORITY -> "Priority"
        }
        col.addView(displayRowCard("▦", "Grouping", groupVal) {
            val opts = arrayOf("None", "Date", "Project", "Priority")
            AlertDialog.Builder(this).setTitle("Grouping").setItems(opts) { _, w ->
                setDispGroup(key, listOf(GroupMode.NONE, GroupMode.DATE, GroupMode.PROJECT, GroupMode.PRIORITY)[w])
                showDisplaySheet()
            }.show()
        })
        val sortVal = when (dispSortFor(key)) {
            SortMode.MANUAL -> "Manual"; SortMode.DATE -> "Date"
            SortMode.PRIORITY -> "Priority"; SortMode.TITLE -> "Title"
            SortMode.SMART -> "Smart"
        }
        col.addView(displayRowCard("⇅", "Sorting", sortVal) {
            val opts = arrayOf("Manual", "Smart", "Date", "Priority", "Title")
            AlertDialog.Builder(this).setTitle("Sorting").setItems(opts) { _, w ->
                setDispSort(key, listOf(SortMode.MANUAL, SortMode.SMART, SortMode.DATE, SortMode.PRIORITY, SortMode.TITLE)[w])
                showDisplaySheet()
            }.show()
        })

        // Filter: Date + Priority on every list; Today adds Assignee + Label.
        val filterLbl = tv("Filter", 17f, Glass.text(this@MainActivity), true)
        filterLbl.layoutParams = lp(t = dp(24f), b = dp(10f))
        col.addView(filterLbl)
        col.addView(displayRowCard("▤", "Date", dispDate[key] ?: "All") {
            val opts = arrayOf("All", "Today", "Tomorrow", "Overdue", "Upcoming", "No date")
            AlertDialog.Builder(this).setTitle("Date").setItems(opts) { _, w ->
                dispDate[key] = opts[w]
                showDisplaySheet()
            }.show()
        })
        val prioSet = dispPrio[key] ?: emptySet()
        val prioVal = if (prioSet.isEmpty()) "All" else prioSet.sorted().joinToString(", ") { "P$it" }
        col.addView(displayRowCard("⚑", "Priority", prioVal) {
            val items = arrayOf("Priority 1", "Priority 2", "Priority 3", "Priority 4")
            val checked = BooleanArray(4) { (it + 1) in prioSet }
            val temp = prioSet.toMutableSet()
            AlertDialog.Builder(this).setTitle("Priority")
                .setMultiChoiceItems(items, checked) { _, w, isChecked ->
                    if (isChecked) temp.add(w + 1) else temp.remove(w + 1)
                }
                .setPositiveButton("Done") { _, _ -> dispPrio[key] = temp; showDisplaySheet() }
                .setNegativeButton("Clear") { _, _ -> dispPrio[key] = mutableSetOf(); showDisplaySheet() }
                .show()
        })
        if (key == "today") {
            col.addView(displayRowCard("◉", "Assignee", dispAssignee[key] ?: "All") {
                val opts = arrayOf("All", "Me", "Unassigned")
                AlertDialog.Builder(this).setTitle("Assignee").setItems(opts) { _, w ->
                    dispAssignee[key] = opts[w]
                    showDisplaySheet()
                }.show()
            })
            val labVal = dispLabel[key]?.let { id -> Store.state.labels.find { it.id == id }?.let { "@" + it.name } } ?: "All"
            col.addView(displayRowCard("◈", "Label", labVal) {
                val labelOpts = listOf("All") + Store.state.labels.map { "@" + it.name }
                AlertDialog.Builder(this).setTitle("Label").setItems(labelOpts.toTypedArray()) { _, w ->
                    if (w == 0) dispLabel.remove(key) else dispLabel[key] = Store.state.labels[w - 1].id
                    showDisplaySheet()
                }.show()
            })
        }

        // Done — rounded-rectangle red button, bottom-right (not a pill).
        val doneRow = LinearLayout(this); doneRow.orientation = LinearLayout.HORIZONTAL
        doneRow.layoutParams = lp(t = dp(24f))
        val spacer = View(this)
        spacer.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        doneRow.addView(spacer)
        val done = tv("Done", 15f, 0xFFFFFFFF.toInt(), false)
        done.gravity = Gravity.CENTER
        done.background = roundedRect(TD_RED, 10f)
        done.layoutParams = LinearLayout.LayoutParams(dp(72f), dp(40f))
        done.setOnClickListener {
            when (dispLayoutFor(key)) {
                "board" -> if (sub?.startsWith("project:") == true) boardMode = true
                "list" -> boardMode = false
                "calendar" -> { closeSheet(); sub = "calendar"; render(); return@setOnClickListener }
            }
            closeSheet(); render()
        }
        doneRow.addView(done)
        col.addView(doneRow)
        return col
    }

    /* ---------------- sheets ---------------- */

    /** One blur+saturation frost effect (Android 12+ RenderEffect). */
    private fun frostEffect(radius: Float, saturation: Float): RenderEffect {
        val cm = ColorMatrix().apply { setSaturation(saturation) }
        val sat = RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(cm))
        val blur = RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
        return RenderEffect.createChainEffect(blur, sat)
    }

    /** Permanent gentle frost on the wallpaper layer, always on: every glass
     *  panel in the app sits over it, so the whole UI reads as frosted glass. */
    private fun applyWallpaperFrost() {
        // v5 bans backdrop blur ANYWHERE (SHADOW-FOUR §0): under v5 the
        // wallpaper layer is a flat colour and no RenderEffect is ever
        // attached to it (Senior Designer gate, CHECK1 P1).
        if (isV5()) return
        if (Build.VERSION.SDK_INT < 31) return
        try { wallpaperHolder.setRenderEffect(frostEffect(18f, 1.22f)) } catch (e: Exception) { /* visual only */ }
    }

    /** Heavy frost over everything behind a sheet: a double-pass blur for
     *  depth + a saturation lift — the iOS recipe. Closing the sheet restores
     *  the permanent wallpaper frost. */
    private fun setBackdropBlur(on: Boolean) {
        // v5: sheets open over the flat V5.SCRIM only — no blur, no
        // saturation lift, on either the open or the close path.
        if (isV5()) return
        if (Build.VERSION.SDK_INT < 31) return
        try {
            if (on) {
                val sat = RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.45f) }))
                val b1 = RenderEffect.createBlurEffect(30f, 30f, Shader.TileMode.CLAMP)
                val b2 = RenderEffect.createBlurEffect(30f, 30f, Shader.TileMode.CLAMP)
                val fx = RenderEffect.createChainEffect(b2, RenderEffect.createChainEffect(b1, sat))
                wallpaperHolder.setRenderEffect(fx)
                mainColumn.setRenderEffect(fx)
                navBar.setRenderEffect(fx)
            } else {
                mainColumn.setRenderEffect(null)
                navBar.setRenderEffect(null)
                applyWallpaperFrost()
            }
        } catch (e: Exception) { /* visual only */ }
    }

    /** Incremented on every showSheet so a stale exit animation from a
     *  previous sheet can never tear down a newer one. */
    private var sheetGen = 0
    private var sheetClosing = false

    /** Quick Add presenter (B2, VIDEO §3.8): a FLOATING card — 12dp side
     *  margins, all four corners rounded (~20dp), Todoist.SHEET fill —
     *  that scales 0.9→1 + fades in over ~150ms above a scrim at
     *  α≈0.50 (pixel-measured). No slide: the card materialises low and
     *  grows into place while the IME rises beneath it. Framework note:
     *  without AndroidX WindowInsetsAnimation the card cannot track the
     *  IME frame-perfectly; it docks at the bottom with a 12dp margin
     *  and the keyboard slides under it — the one documented motion
     *  approximation (TEAMONE-ANALYSIS-VIDEO §6). */
    private fun showQaSheet(sheetContent: View) {
        sheetGen++
        sheetClosing = false
        sheetIsQa = true
        sheetRoot.removeAllViews()
        val scrim = View(this)
        scrim.setBackgroundColor(if (isV5()) V5.SCRIM else 0xFF000000.toInt())
        scrim.alpha = 0f
        scrim.setOnClickListener { attemptCloseSheet() }
        sheetRoot.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        // v5 (SHADOW-FOUR §2.8): the Quick Add card sits on V5.SHEET at
        // R_SHEET 28 — not the Todoist #1F1F1F / radius-20 surface
        // (Senior Designer gate, CHECK1 P2-1). The floating-card
        // geometry (12dp margins, IME behaviour) is unchanged.
        panel.background = if (isV5()) roundedRect(V5.SHEET, V5.R_SHEET)
            else Glass.todoistSheetBg(this, 20f, Todoist.SHEET)
        panel.setPadding(dp(18f), dp(10f), dp(18f), dp(16f))
        val pp = FrameLayout.LayoutParams(-1, -2)
        pp.gravity = Gravity.BOTTOM
        pp.setMargins(dp(12f), 0, dp(12f), dp(12f))
        panel.layoutParams = pp
        Glass.elevate(this, panel, if (isV5()) V5.R_SHEET else 20f, 24f)
        val handleWrap = LinearLayout(this)
        handleWrap.orientation = LinearLayout.VERTICAL
        handleWrap.gravity = Gravity.CENTER_HORIZONTAL
        val handle = Glass.dragHandle(this)
        if (isV5()) handle.background = roundedRect(V5.HANDLE, 999f)
        handle.layoutParams = LinearLayout.LayoutParams(dp(36f), dp(4f)).apply { bottomMargin = dp(12f) }
        handleWrap.addView(handle)
        panel.addView(handleWrap)
        val scroll = ScrollView(this)
        scroll.layoutParams = LinearLayout.LayoutParams(-1, -2)
        scroll.addView(sheetContent)
        panel.addView(scroll)
        sheetRoot.addView(panel)
        sheetRoot.visibility = View.VISIBLE
        fabBtn.visibility = View.GONE
        navBar.visibility = View.GONE
        undoWasVisible = undoBar.visibility == View.VISIBLE
        if (undoWasVisible) undoBar.visibility = View.GONE
        Sfx.open()
        // v5's SCRIM token carries its own alpha (160/255); legacy QA
        // scrim is solid black faded to the measured 0.50.
        scrim.animate().alpha(if (isV5()) 1f else 0.5f).setDuration(150).start()
        panel.scaleX = 0.9f; panel.scaleY = 0.9f; panel.alpha = 0f
        panel.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(150)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
    }

    /** Swap the Quick Add card's content with NO animation at all — the
     *  one-frame in-place reset the send behaviour requires (VIDEO
     *  §3.11), and the flicker-free path for popup picks / draft
     *  restores while the card stays open. Falls back to the full
     *  presenter when the QA card is not the open sheet. */
    private fun refreshQaSheet() {
        if (!sheetIsQa || sheetRoot.visibility != View.VISIBLE) { showQaSheet(buildQuickAdd()); return }
        val panel = sheetRoot.getChildAt(1) as? LinearLayout ?: run { showQaSheet(buildQuickAdd()); return }
        val scroll = panel.getChildAt(1) as? ScrollView ?: run { showQaSheet(buildQuickAdd()); return }
        scroll.removeAllViews()
        scroll.addView(buildQuickAdd())
    }

    /** v5 sheet surface (SHADOW-FOUR §2.8): flat V5.SHEET, R_SHEET on
     *  the TOP corners only — the sheet is attached to the screen's
     *  bottom edge, so the bottom corners stay square. */
    private fun v5SheetBg(): android.graphics.drawable.GradientDrawable {
        val g = android.graphics.drawable.GradientDrawable()
        val r = dp(V5.R_SHEET).toFloat()
        g.cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        g.setColor(V5.SHEET)
        return g
    }

    private fun showSheet(sheetContent: View) {
        sheetGen++
        sheetClosing = false
        sheetIsQa = false
        sheetRoot.removeAllViews()
        val scrim = View(this)
        scrim.setBackgroundColor(if (isV5()) V5.SCRIM
            else if (Glass.isWhite(this)) 0x66020208 else 0xB3020208.toInt())
        scrim.setOnClickListener { attemptCloseSheet() }
        sheetRoot.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        if (isV5()) {
            // §2.8 sheet: attached bottom sheet, SHEET fill, R_SHEET top
            // corners, 20dp content padding, HANDLE drag pill. Flat —
            // and setBackdropBlur() is a no-op under v5 (CHECK1 P1).
            panel.background = v5SheetBg()
            panel.setPadding(dp(20f), dp(10f), dp(20f), dp(20f))
        } else {
            panel.background = Glass.sheetPanel(this, 30f)
            panel.setPadding(dp(18f), dp(14f), dp(18f), dp(18f))
        }
        val pp = FrameLayout.LayoutParams(-1, -2)
        pp.gravity = Gravity.BOTTOM
        if (isV5()) pp.setMargins(0, 0, 0, 0)
        else pp.setMargins(dp(8f), 0, dp(8f), dp(8f))
        panel.layoutParams = pp
        Glass.elevate(this, panel, if (isV5()) V5.R_SHEET else 30f, 28f)
        val handle = View(this)
        val hg = android.graphics.drawable.GradientDrawable()
        hg.cornerRadius = dp(3f).toFloat()
        hg.setColor(if (isV5()) V5.HANDLE
            else if (Glass.isWhite(this)) android.graphics.Color.argb(64, 20, 20, 35) else 0x59FFFFFF)
        handle.background = hg
        handle.layoutParams = LinearLayout.LayoutParams(
            dp(if (isV5()) 36f else 38f), dp(if (isV5()) 4f else 5f)).apply {
            gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(14f)
        }
        panel.addView(handle)
        val scroll = ScrollView(this)
        scroll.layoutParams = LinearLayout.LayoutParams(-1, -2)
        scroll.addView(sheetContent)
        panel.addView(scroll)
        sheetRoot.addView(panel)
        sheetRoot.visibility = View.VISIBLE
        fabBtn.visibility = View.GONE
        navBar.visibility = View.GONE
        undoWasVisible = undoBar.visibility == View.VISIBLE
        if (undoWasVisible) undoBar.visibility = View.GONE
        setBackdropBlur(true)
        Sfx.open()
        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(250).start()
        panel.translationY = dp(60f).toFloat()
        panel.alpha = 0.4f
        panel.animate().translationY(0f).alpha(1f).setDuration(420).setInterpolator(sheetEase).start()
    }

    private fun closeSheet() {
        if (sheetRoot.visibility != View.VISIBLE || sheetClosing) return
        // A closed sheet forgets its sub-navigation; the next sheet starts fresh.
        sheetStack.clear()
        Sfx.close()
        val scrim = sheetRoot.getChildAt(0)
        val panel = sheetRoot.getChildAt(1)
        if (scrim == null || panel == null) { finishCloseSheet(); return }
        sheetClosing = true
        val gen = sheetGen
        scrim.animate().alpha(0f).setDuration(240).start()
        panel.animate().translationY(dp(60f).toFloat()).alpha(0.4f).setDuration(240).setInterpolator(sheetEase)
            .withEndAction { if (gen == sheetGen) finishCloseSheet() }.start()
    }

    private fun finishCloseSheet() {
        sheetClosing = false
        sheetIsQa = false
        sheetRoot.visibility = View.GONE
        sheetRoot.removeAllViews()
        qaHintRun?.let { handler.removeCallbacks(it) }
        qaHintRun = null
        fabBtn.visibility = if (isV5()) View.GONE else View.VISIBLE
        navBar.visibility = View.VISIBLE
        if (undoWasVisible) { undoBar.visibility = View.VISIBLE; undoWasVisible = false }
        setBackdropBlur(false)
        editingId = null
        editorSnapshot = null
        editorDirtyCheck = null
    }

    /** Open a sub-sheet, remembering how to get back to `backTo`. */
    private fun pushSheet(sheetContent: View, backTo: () -> View) {
        sheetStack.addLast(backTo)
        showSheet(sheetContent)
    }

    /** Step back one sheet level. Returns true if a previous sheet was shown. */
    private fun popSheet(): Boolean {
        if (sheetStack.isEmpty()) return false
        showSheet(sheetStack.removeLast()())
        return true
    }

    /** Return to a parent sheet, dropping the stacked entry that led here
     *  (used by Done/Save buttons inside sub-sheets). */
    private fun returnToSheet(sheetContent: View) {
        if (sheetStack.isNotEmpty()) sheetStack.removeLast()
        showSheet(sheetContent)
    }

    /** Return to the Quick Add root, dropping any stacked sub-sheets. */
    private fun returnToQuickAdd() {
        sheetStack.clear()
        showQaSheet(buildQuickAdd())
    }

    /** Back / scrim dismissal. If the editor is open with unsaved edits,
     *  ask first instead of silently discarding them. */
    private fun attemptCloseSheet() {
        if (editingId != null && editorDirtyCheck?.invoke() == true) {
            AlertDialog.Builder(this)
                .setTitle("Discard changes?")
                .setMessage("You have unsaved edits to this task.")
                .setPositiveButton("Discard") { _, _ -> closeSheet() }
                .setNegativeButton("Keep editing", null)
                .show()
            return
        }
        closeSheet()
    }

    private fun promptText(title: String, cb: (String) -> Unit) {
        val e = EditText(this)
        e.setSingleLine(true)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(e)
            .setPositiveButton("OK") { _, _ -> val v = e.text.toString().trim(); if (v.isNotEmpty()) cb(v) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /* ---------------- quick add (Todoist-style) ---------------- */

    private var qaText = ""
    private var qaPresetDate: String? = null
    private var qaDate: String? = null
    private var qaTime: String? = null
    private var qaRecurrence: String? = null
    private var qaRecSet = false
    private var qaPriority: Int? = null
    private var qaProjectSet = false
    private var qaProjectId: String? = null
    private var qaReminder: Int? = null
    private var qaRemSet = false
    private var qaLabelIds = mutableSetOf<String>()
    private var qaNotes = ""
    private var qaShowNotes = false
    private var qaLocLat: Double? = null
    private var qaLocLng: Double? = null
    /** Deadline is a field SEPARATE from the due date (Team Two model):
     *  set via the "+" popup / deadline chip, never via the Date chip. */
    private var qaDeadline: String? = null
    /** True while the date picker edits the deadline instead of the due date. */
    private var qaPickingDeadline = false
    /** Placeholder rotation (VIDEO §3.11): the example rotates PER SEND,
     *  never per open. Pool = the two video-observed examples, the
     *  screenshot example, and the app's original one. */
    private val qaPlaceholders = listOf(
        "e.g. Schedule review by Mar 15 at 11am p1",
        "e.g. Replace lightbulb tomorrow at 3pm",
        "e.g. Take kids to the park after work tomorrow",
        "e.g. Fix bike tire this weekend"
    )
    private var qaPlaceholderIdx = 0
    private var qaCursor: java.util.Calendar = java.util.Calendar.getInstance()
    private var qaUpdating = false
    /** True when the currently open Quick Add sheet is showing a draft
     *  resumed from a previous open (Batch 9) — drives the "Discard draft"
     *  row; cleared by clearQaDraft(). */
    private var qaDraftResumed = false

    /** True when the Quick Add fields hold an unfinished draft (Batch 9). */
    private fun qaHasDraft(): Boolean =
        qaText.isNotBlank() || qaDate != null || qaTime != null || qaRecSet ||
        qaPriority != null || qaProjectSet || qaRemSet || qaLabelIds.isNotEmpty() ||
        qaNotes.isNotBlank() || qaLocLat != null || qaDeadline != null || qaSubtasks.isNotEmpty()

    /** Reset every Quick Add field to a blank draft (Batch 9). */
    private fun clearQaDraft() {
        qaText = ""
        qaPresetDate = null
        qaDate = null
        qaTime = null
        qaRecurrence = null; qaRecSet = false
        qaPriority = null
        qaProjectSet = false; qaProjectId = null
        qaReminder = null; qaRemSet = false
        qaLabelIds = mutableSetOf()
        qaNotes = ""; qaShowNotes = false
        qaLocLat = null; qaLocLng = null
        qaDeadline = null; qaPickingDeadline = false
        qaCursor = java.util.Calendar.getInstance()
        qaDraftResumed = false
        qaSubtasks.clear(); qaShowSubtasks = false
    }

    private fun openQuickAdd(presetDate: String?) {
        // Batch 9 — a half-typed draft survives closing the sheet: a FAB tap
        // resumes it instead of silently discarding it (Batch 7 follow-up).
        // An explicit preset date (Calendar "Add task on this day") always
        // starts fresh, since it carries its own intent.
        if (presetDate != null) {
            clearQaDraft()
            qaPresetDate = presetDate
            qaDate = presetDate
        } else if (qaHasDraft()) {
            qaDraftResumed = true
        } else {
            clearQaDraft()
            // Customize Quick Add's "Show description" preference decides
            // whether a fresh sheet opens with the description field.
            qaShowNotes = Prefs.prefs(this).getBoolean("qa_show_desc", false)
        }
        showQaSheet(buildQuickAdd())
    }

    private fun qaPreviewParse(): Parser.Parsed {
        val p = Parser.parse(qaText)
        if (qaDate != null) p.dueDate = qaDate
        if (qaTime != null) p.dueTime = qaTime
        if (qaRecSet) p.recurrence = qaRecurrence
        if (qaPriority != null) p.priority = qaPriority!!
        if (qaProjectSet && qaProjectId != null) p.projectName = Store.state.projects.find { it.id == qaProjectId }?.name
        if (qaDeadline != null) p.deadline = qaDeadline
        return p
    }

    private fun qaPill(label: String, on: Boolean, click: () -> Unit): TextView {
        val b = tv(label, 13f, Glass.text(this@MainActivity), on)
        b.background = Glass.sheetChip(this, on)
        b.setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
        b.setOnClickListener { click() }
        b.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(7f) }
        return b
    }

    /** Waveform glyph for the Ramble button: five white bars of varying
     *  height centred in the red rounded square (ANALYSIS §11 — the
     *  button carries a voice waveform, not a mic emoji). */
    private fun waveformView(): View {
        return object : View(this) {
            override fun onDraw(canvas: android.graphics.Canvas) {
                super.onDraw(canvas)
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                paint.color = 0xFFFFFFFF.toInt()
                paint.style = android.graphics.Paint.Style.FILL
                val heights = floatArrayOf(0.36f, 0.62f, 1f, 0.62f, 0.36f)
                val barW = width / 9f
                val gap = barW * 0.8f
                var x = (width - (barW * 5 + gap * 4)) / 2f
                for (h in heights) {
                    val bh = height * 0.56f * h
                    val top = (height - bh) / 2f
                    val rect = android.graphics.RectF(x, top, x + barW, top + bh)
                    canvas.drawRoundRect(rect, barW / 2f, barW / 2f, paint)
                    x += barW + gap
                }
            }
        }
    }

    private fun buildQuickAdd(): View {
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
        var notesIn: EditText? = null
        val showLabels = Prefs.prefs(this).getBoolean("qa_show_labels", true)
        var dateChip: TextView? = null
        var prioChip: TextView? = null
        var projChip: TextView? = null
        var rambleView: View? = null
        var sendView: View? = null
        var actionIsSend = qaText.isNotEmpty()
        var popupKind: String? = null

        // Borderless ~20sp multi-line field directly on the sheet
        // (ANALYSIS §11 — the reference has no input box at all).
        val inp = EditText(this)
        inp.hint = qaPlaceholders[qaPlaceholderIdx % qaPlaceholders.size]
        if (isV5()) {
            // Rotating example hints (SHADOW-THREE-DESIGN §8.1): cycle
            // every 3.5s while the field is empty. The loop stops itself
            // on the first keystroke (qaText non-empty) and
            // finishCloseSheet cancels it when the sheet closes.
            inp.hint = qaHints[qaHintIdx % qaHints.size]
            qaHintRun?.let { handler.removeCallbacks(it) }
            qaHintRun = null
            if (qaText.isEmpty()) {
                val run = object : Runnable {
                    override fun run() {
                        if (qaText.isNotEmpty() || sheetRoot.visibility != View.VISIBLE) return
                        qaHintIdx++
                        inp.hint = qaHints[qaHintIdx % qaHints.size]
                        handler.postDelayed(this, 3500)
                    }
                }
                qaHintRun = run
                handler.postDelayed(run, 3500)
            }
        }
        inp.setText(qaText)
        inp.setSelection(inp.text.length)
        inp.textSize = 20f
        inp.setTextColor(Glass.text(this@MainActivity))
        inp.setHintTextColor(Todoist.PLACEHOLDER)
        inp.background = null
        inp.setPadding(0, dp(2f), 0, dp(2f))
        inp.gravity = Gravity.TOP
        inp.inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        inp.layoutParams = lp(b = dp(6f))
        col.addView(inp)

        // Parsed-token pill row (B2 item 1e). DOCUMENTED CHOICE: the
        // framework EditText can carry BackgroundSpans, but re-spanning
        // on every keystroke fights the IME's composition state and
        // shifts offsets as tokens are consumed; the faithful, stable
        // equivalent is a coloured pill row directly above the toolbar,
        // fed by Team Two's Parser token ranges (R1-6). Picking P1 turns
        // the text token into "!!1" and the row shows the red "!!1" pill
        // while the Priority chip mirrors "P1" — the same information
        // the reference renders inline.
        val pillRow = LinearLayout(this); pillRow.orientation = LinearLayout.HORIZONTAL
        pillRow.layoutParams = lp(b = dp(6f))
        pillRow.visibility = View.GONE
        col.addView(pillRow)

        if (qaShowNotes) {
            notesIn = input("Description / notes", qaNotes, multi = true)
            col.addView(notesIn)
        }

        if (qaShowSubtasks) {
            // Inline subtask editor (v5, §8.2): committed rows live in
            // the qaSubtasks field so they survive sheet rebuilds; on
            // submit they attach to the created task in qaSubmit.
            qaSubtasks.forEach { st ->
                val r = LinearLayout(this); r.orientation = LinearLayout.HORIZONTAL
                r.gravity = Gravity.CENTER_VERTICAL
                r.setPadding(0, dp(4f), 0, dp(4f))
                r.addView(tv("○", 14f, Glass.muted(this@MainActivity)))
                val stT = tv(st, 15f)
                stT.layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8f) }
                r.addView(stT)
                val del = tv("✕", 14f, Glass.muted(this@MainActivity))
                del.contentDescription = "Remove subtask"
                del.setOnClickListener { qaSubtasks.remove(st); refreshQaSheet() }
                r.addView(del)
                col.addView(r)
            }
            val addRow = LinearLayout(this); addRow.orientation = LinearLayout.HORIZONTAL
            val subIn = input("Add a subtask")
            subIn.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            val subAddBtn = ghostBtn("Add") {
                val v = subIn.text.toString().trim()
                if (v.isNotEmpty()) { qaSubtasks.add(v); refreshQaSheet() }
            }
            addRow.addView(subIn); addRow.addView(subAddBtn)
            col.addView(addRow)
        }

        // Floating popup card host (priority / projects / "+" /
        // attachment): Todoist.POPUP fill, sitting directly above the
        // chip row, fade+scale ~120ms in (VIDEO §3.10).
        val popupBox = LinearLayout(this); popupBox.orientation = LinearLayout.VERTICAL
        popupBox.background = Glass.flatCard(this, 16f, Todoist.POPUP)
        popupBox.setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
        popupBox.layoutParams = lp(b = dp(8f))
        popupBox.visibility = View.GONE
        Glass.elevate(this, popupBox, 16f, 10f)
        col.addView(popupBox)

        fun refreshDynamic() {
            val parsed = qaPreviewParse()
            pillRow.removeAllViews()
            var anyPill = false
            fun pill(labelText: String, color: Int) {
                anyPill = true
                val p = tv(labelText, 12.5f, color, true)
                p.background = roundedRect(
                    android.graphics.Color.argb(64, android.graphics.Color.red(color),
                        android.graphics.Color.green(color), android.graphics.Color.blue(color)), 8f)
                p.setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
                p.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6f) }
                pillRow.addView(p)
            }
            var hasPrioTok = false; var hasProjTok = false
            for (t in Parser.parse(qaText).tokens) {
                when (t.kind) {
                    "priority" -> { hasPrioTok = true; pill(t.text, priorityColor(t.value.toIntOrNull() ?: 4)) }
                    "project" -> { hasProjTok = true; pill(t.text, Glass.text(this@MainActivity)) }
                    "date" -> pill(t.text, Todoist.DATE_TODAY)
                    "deadline" -> pill(t.text, Todoist.SALMON)
                    "recurrence" -> pill(t.text, Todoist.DATE_TOMORROW)
                    "label" -> pill(t.text, TD_GREY)
                }
            }
            if (qaPriority != null && !hasPrioTok) pill("P" + qaPriority, priorityColor(qaPriority!!))
            if (qaProjectSet && !hasProjTok) pill(
                "#" + (qaProjectId?.let { id -> Store.state.projects.find { it.id == id }?.name } ?: "Inbox"),
                Glass.text(this@MainActivity))
            if (qaDeadline != null) pill("⚑ Deadline " + fmtMonDay(qaDeadline!!), Todoist.SALMON)
            if (qaRemSet) pill("🔔 Reminder", TD_GREY)
            if (qaLocLat != null) pill("📍 Location", TD_GREY)
            pillRow.visibility = if (anyPill) View.VISIBLE else View.GONE
            // Parsed-date chip is GREEN — icon + label (ANALYSIS §11).
            val effDate = qaDate ?: parsed.dueDate
            dateChip?.let { chip ->
                val lbl = when (effDate) {
                    null -> "Date"
                    Dates.today() -> "Today"
                    Dates.addDays(Dates.today(), 1) -> "Tomorrow"
                    else -> Dates.fmtDay(effDate!!)
                }
                chip.text = if (showLabels) "📅 $lbl" else "📅"
                chip.setTextColor(if (effDate != null) Todoist.DATE_TODAY else Glass.text(this@MainActivity))
            }
            val effPrio = qaPriority ?: parsed.priority
            prioChip?.let { chip ->
                if (effPrio < 4) {
                    chip.text = if (showLabels) "⚑ P$effPrio" else "⚑"
                    chip.setTextColor(priorityColor(effPrio))
                } else {
                    chip.text = if (showLabels) "⚑ Priority" else "⚑"
                    chip.setTextColor(Glass.text(this@MainActivity))
                }
            }
            projChip?.let { chip ->
                val nm = if (qaProjectSet) qaProjectId?.let { id -> Store.state.projects.find { it.id == id }?.name } ?: "Inbox"
                    else parsed.projectName ?: "Inbox"
                chip.text = if (showLabels) "▣ $nm" else "▣"
            }
            // Ramble ⇄ Send: swaps on the FIRST typed character, ~100ms
            // crossfade, no size/shape change (VIDEO §3.9).
            val hasText = qaText.isNotEmpty()
            if (hasText != actionIsSend) {
                actionIsSend = hasText
                rambleView?.animate()?.alpha(if (hasText) 0f else 1f)?.setDuration(100)?.start()
                sendView?.animate()?.alpha(if (hasText) 1f else 0f)?.setDuration(100)?.start()
                rambleView?.isClickable = !hasText
                sendView?.isClickable = hasText
            }
        }

        fun hidePopup() {
            if (popupBox.visibility != View.VISIBLE) return
            popupKind = null
            // Reference exit is two-step (content ~50ms, card ~100ms);
            // approximated as a single 100ms card fade.
            popupBox.animate().alpha(0f).setDuration(100).withEndAction {
                popupBox.visibility = View.GONE; popupBox.alpha = 1f
            }.start()
        }

        fun showPopup(kind: String) {
            popupKind = kind
            popupBox.removeAllViews()
            when (kind) {
                "priority" -> {
                    // Only the FLAGS are coloured (P1 red / P2 orange /
                    // P3 blue filled; P4 outline); labels stay white.
                    // No header, no checkmark (ANALYSIS §13).
                    for (n in 1..4) {
                        val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL
                        row.gravity = Gravity.CENTER_VERTICAL
                        row.setPadding(dp(10f), dp(13f), dp(10f), dp(13f))
                        val fl = tv(if (n == 4) "⚐" else "⚑", 17f, priorityColor(n))
                        fl.layoutParams = LinearLayout.LayoutParams(dp(36f), -2)
                        row.addView(fl)
                        row.addView(tv("Priority $n", 16f, 0xFFFFFFFF.toInt()))
                        row.setOnClickListener {
                            qaPriority = n
                            qaUpdating = true
                            qaText = if (Regex("""!!(?![1-4])""").containsMatchIn(qaText))
                                qaText.replaceFirst(Regex("""!!(?![1-4])"""), "!!" + n)
                            else qaText.trimEnd() + " !!" + n
                            inp.setText(qaText); inp.setSelection(qaText.length)
                            qaUpdating = false
                            hidePopup()
                            refreshDynamic()
                        }
                        popupBox.addView(row)
                    }
                }
                "project" -> {
                    popupBox.addView(tv("My Projects", 11f, Glass.muted(this@MainActivity), true))
                    val q = (qaText.split(" ", "\n").lastOrNull() ?: "").removePrefix("#").lowercase()
                    val rows = mutableListOf<Pair<String, String?>>("Inbox" to null)
                    Store.state.projects.forEach { rows.add(it.name to it.id) }
                    rows.filter { q.isEmpty() || it.first.lowercase().contains(q) }.forEach { (nm, pid) ->
                        val row = tv((if (pid == null) "▣  " else "#  ") + nm, 15f)
                        row.setPadding(dp(10f), dp(11f), dp(10f), dp(11f))
                        row.setOnClickListener {
                            qaProjectSet = true; qaProjectId = pid
                            qaUpdating = true
                            val lw = qaText.split(" ", "\n").lastOrNull() ?: ""
                            qaText = qaText.removeSuffix(lw).trimEnd()
                            inp.setText(qaText); inp.setSelection(qaText.length)
                            qaUpdating = false
                            hidePopup()
                            refreshDynamic()
                        }
                        popupBox.addView(row)
                    }
                }
                "plus" -> {
                    popupBox.addView(qaPopupRow("✂️", "Extract tasks…") { hidePopup(); closeSheet(); sub = "assist"; render() })
                    popupBox.addView(qaPopupRow("☰", "Description") { qaShowNotes = true; refreshQaSheet() })
                    popupBox.addView(qaPopupRow("☰", "Subtasks") { qaShowSubtasks = true; refreshQaSheet() })
                    popupBox.addView(qaPopupRow("▤", "Templates") { pushSheet(buildQaTemplates()) { buildQuickAdd() } })
                    popupBox.addView(qaPopupRow("🏷️", "Labels") { pushSheet(buildQaLabels()) { buildQuickAdd() } })
                    popupBox.addView(qaPopupRow("🎯", "Deadline") { qaPickingDeadline = true; pushSheet(buildQaDatePicker()) { buildQuickAdd() } })
                    popupBox.addView(qaPopupRow("📍", "Location") { captureLocationQa() })
                    val div = hairline()
                    div.layoutParams = LinearLayout.LayoutParams(-1, Math.max(1, dp(0.5f)))
                    popupBox.addView(div)
                    popupBox.addView(qaPopupRow("✎", "Customize Quick Add") { showFullSheet(buildCustomizeQa()) })
                }
                "attachment" -> {
                    listOf("File", "Camera", "Audio").forEach { kindName ->
                        val row = tv(kindName, 15f)
                        row.setPadding(dp(10f), dp(11f), dp(10f), dp(11f))
                        row.setOnClickListener {
                            hidePopup()
                            toast("Attachments aren't stored in the offline build — put the detail in the description instead")
                        }
                        popupBox.addView(row)
                    }
                }
            }
            popupBox.visibility = View.VISIBLE
            popupBox.alpha = 0f; popupBox.scaleX = 0.95f; popupBox.scaleY = 0.95f
            popupBox.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(120)
                .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        }

        fun autoPopup() {
            val lastWord = qaText.split(" ", "\n").lastOrNull() ?: ""
            when {
                qaText.contains("!!") && qaPriority == null && !Regex("""!![1-4]""").containsMatchIn(qaText) -> {
                    if (popupKind != "priority") showPopup("priority")
                }
                lastWord.startsWith("#") -> {
                    if (popupKind != "project") showPopup("project")
                }
                else -> if (popupKind == "priority" || popupKind == "project") hidePopup()
            }
        }

        inp.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(e: Editable?) {
                if (qaUpdating) return
                qaText = e?.toString() ?: ""
                refreshDynamic()
                autoPopup()
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        inp.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { qaSubmit(inp, notesIn); true } else false
        }

        // Chip row: "+" first, then the persisted Customize order as
        // rounded-rectangle #3F3F3F chips in a horizontal scroller; the
        // red rounded-square action button is pinned OUTSIDE the scroll.
        val barOuter = LinearLayout(this); barOuter.orientation = LinearLayout.HORIZONTAL
        barOuter.gravity = Gravity.CENTER_VERTICAL
        barOuter.layoutParams = lp(t = dp(6f))
        val bar = LinearLayout(this); bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        fun qaChip(id: String): TextView {
            val c = tv("", 14f)
            c.background = Glass.qaChipBg(this, false)
            c.setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            c.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(7f) }
            when (id) {
                "date" -> { dateChip = c; c.setOnClickListener { qaPickingDeadline = false; pushSheet(buildQaDatePicker()) { buildQuickAdd() } } }
                "attachment" -> {
                    c.text = if (showLabels) "📎 Attachment" else "📎"
                    c.setOnClickListener { if (popupKind == "attachment") hidePopup() else showPopup("attachment") }
                }
                "priority" -> { prioChip = c; c.setOnClickListener { pushSheet(buildQaPriority()) { buildQuickAdd() } } }
                "reminders" -> {
                    c.text = if (showLabels) "⏰ Reminders" else "⏰"
                    if (qaRemSet) c.setTextColor(Todoist.SALMON)
                    c.setOnClickListener { pushSheet(buildQaReminders()) { buildQuickAdd() } }
                }
                "project" -> { projChip = c; c.setOnClickListener { pushSheet(buildQaProjects()) { buildQuickAdd() } } }
                "labels" -> {
                    c.text = if (showLabels) "🏷 Labels" else "🏷"
                    c.setOnClickListener { pushSheet(buildQaLabels()) { buildQuickAdd() } }
                }
                "description" -> {
                    c.text = if (showLabels) "☰ Description" else "☰"
                    c.setOnClickListener { qaShowNotes = !qaShowNotes; refreshQaSheet() }
                }
                "deadline" -> {
                    c.text = if (showLabels) "🎯 Deadline" else "🎯"
                    if (qaDeadline != null) {
                        if (showLabels) c.text = "🎯 " + fmtMonDay(qaDeadline!!)
                        c.setTextColor(Todoist.SALMON)
                    }
                    c.setOnClickListener { qaPickingDeadline = true; pushSheet(buildQaDatePicker()) { buildQuickAdd() } }
                }
                "location" -> {
                    c.text = if (showLabels) "📍 Location" else "📍"
                    if (qaLocLat != null) c.setTextColor(Todoist.SALMON)
                    c.setOnClickListener { captureLocationQa() }
                }
                else -> c.text = id
            }
            return c
        }
        val plusChip = tv("+", 16f)
        plusChip.background = Glass.qaChipBg(this, false)
        plusChip.setPadding(dp(13f), dp(9f), dp(13f), dp(9f))
        plusChip.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(7f) }
        plusChip.contentDescription = "More actions"
        plusChip.setOnClickListener { if (popupKind == "plus") hidePopup() else showPopup("plus") }
        bar.addView(plusChip)
        Store.quickAddChipOrder()
            .filter { it !in Store.state.quickAddHiddenChips }
            .forEach { id -> bar.addView(qaChip(id)) }
        val barScroll = HorizontalScrollView(this)
        barScroll.isHorizontalScrollBarEnabled = false
        barScroll.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        barScroll.addView(bar)
        barOuter.addView(barScroll)

        // Ramble ⇄ Send: ONE red rounded square (Glass.accentSquareBg —
        // the FAB's shape), waveform when the field is empty, ↑ when it
        // holds any text at all, "!!" included (ANALYSIS §13).
        val actionStack = FrameLayout(this)
        actionStack.layoutParams = LinearLayout.LayoutParams(dp(40f), dp(38f)).apply { leftMargin = dp(8f) }
        val rambleBtn = FrameLayout(this)
        rambleBtn.background = Glass.accentSquareBg(this, 10f)
        rambleBtn.layoutParams = FrameLayout.LayoutParams(-1, -1)
        val wave = waveformView()
        wave.layoutParams = FrameLayout.LayoutParams(dp(22f), dp(22f)).apply { gravity = Gravity.CENTER }
        rambleBtn.addView(wave)
        rambleBtn.contentDescription = "Ramble — dictate tasks"
        rambleBtn.alpha = if (actionIsSend) 0f else 1f
        rambleBtn.isClickable = !actionIsSend
        rambleBtn.setOnClickListener { closeSheet(); sub = "ramble"; render() }
        val sendBtn = tv("↑", 20f, 0xFFFFFFFF.toInt(), true)
        sendBtn.gravity = Gravity.CENTER
        sendBtn.background = Glass.accentSquareBg(this, 10f)
        sendBtn.layoutParams = FrameLayout.LayoutParams(-1, -1)
        sendBtn.contentDescription = "Add task"
        sendBtn.alpha = if (actionIsSend) 1f else 0f
        sendBtn.isClickable = actionIsSend
        sendBtn.setOnClickListener { qaSubmit(inp, notesIn) }
        rambleView = rambleBtn; sendView = sendBtn
        actionStack.addView(rambleBtn); actionStack.addView(sendBtn)
        barOuter.addView(actionStack)
        col.addView(barOuter)

        if (qaDraftResumed) {
            val discard = tv("✕  Discard draft", 13f, Glass.muted(this@MainActivity))
            discard.gravity = Gravity.CENTER
            discard.layoutParams = lp(t = dp(10f))
            discard.setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
            discard.setOnClickListener { Sfx.tick(); clearQaDraft(); refreshQaSheet() }
            col.addView(discard)
        }
        refreshDynamic()
        col.post { inp.requestFocus() }
        return col
    }

    private fun qaSubmit(inp: EditText, notesIn: EditText?) {
        qaText = inp.text.toString()
        if (notesIn != null) qaNotes = notesIn.text.toString()
        val raw = qaText.trim()
        if (raw.isEmpty()) return
        val p = Parser.parse(raw)
        if (p.title.isEmpty()) { toast("Add a title too"); return }
        val base = TaskItem(id = uid(), title = p.title)
        if (qaPresetDate != null && p.dueDate == null) base.dueDate = qaPresetDate
        val t = Store.addTaskFromParse(p, base)
        if (qaDate != null) t.dueDate = qaDate
        if (qaTime != null) t.dueTime = qaTime
        if (qaRecSet) t.recurrence = qaRecurrence
        if (qaPriority != null) t.priority = qaPriority!!
        if (qaProjectSet) t.projectId = qaProjectId
        if (qaRemSet) t.reminderOffsetMin = qaReminder
        if (qaDeadline != null) t.deadline = qaDeadline
        qaLabelIds.forEach { if (!t.labelIds.contains(it)) t.labelIds.add(it) }
        if (qaNotes.isNotEmpty()) t.notes = qaNotes
        if (qaLocLat != null) { t.locLat = qaLocLat; t.locLng = qaLocLng }
        qaSubtasks.forEach { st -> t.subtasks.add(Subtask(uid(), st, false)) }
        Reminders.sync(this, t)
        Reminders.syncGeo(this, t)
        Store.save()
        Sfx.add()
        // SEND (VIDEO §3.11): a one-frame in-place reset — the sheet does
        // NOT close and nothing about it animates. The draft clears, the
        // placeholder ROTATES to the next example, chips reset, Send
        // swaps back to Ramble. Behind the card the list re-renders,
        // smooth-scrolls (~300–400ms) to reveal the new task, and the
        // new card fades in as it arrives.
        clearQaDraft()
        qaPlaceholderIdx++
        refreshQaSheet()
        render()
        handler.postDelayed({
            try {
                mainScroll.smoothScrollTo(0, content.height)
                val lastCard = content.getChildAt(content.childCount - 1)
                if (lastCard != null) {
                    lastCard.alpha = 0f
                    lastCard.animate().alpha(1f).setDuration(200).start()
                }
            } catch (e: Exception) { /* list shorter than expected */ }
        }, 60)
    }

    /* ----- Quick Add: + menu ----- */

    private fun qaMenuRow(icon: String, title: String, tag: String?, click: () -> Unit): LinearLayout {
        val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(6f), dp(12f), dp(6f), dp(12f))
        val ic = tv(icon, 16f); ic.layoutParams = LinearLayout.LayoutParams(dp(34f), -2)
        row.addView(ic)
        val tt = tv(title, 15f); tt.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        row.addView(tt)
        if (tag != null) row.addView(tv(tag, 11f, Glass.gold(this@MainActivity), true))
        row.setOnClickListener { click() }
        return row
    }

    /** One row of a Quick Add floating popup ("+" menu): outline icon +
     *  white 16sp label. No PRO badges — every feature ships unlocked. */
    private fun qaPopupRow(icon: String, title: String, click: () -> Unit): LinearLayout {
        val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(10f), dp(12f), dp(10f), dp(12f))
        val ic = tv(icon, 16f); ic.layoutParams = LinearLayout.LayoutParams(dp(34f), -2)
        row.addView(ic)
        row.addView(tv(title, 16f))
        row.setOnClickListener { click() }
        return row
    }

    /** Customize Quick Add (B2 item 1i) — a full screen over the sheet
     *  (showFullSheet); the Quick Add draft lives in the qa* fields, so
     *  going Back reopens the card with the draft untouched, exactly as
     *  the recording shows (VIDEO F28). Driven by the persisted model:
     *  Store.quickAddChipOrder() + State.quickAddHiddenChips, written
     *  through Store.setQuickAddChips on every change.
     *  DOCUMENTED APPROXIMATION: reorder uses ↑/↓ steppers instead of
     *  drag handles — framework-only drag-reorder inside a ScrollView
     *  sheet fights the sheet's own scroll/dismiss gestures; the ≡
     *  handle glyph is kept for shape fidelity. */
    private fun buildCustomizeQa(): View {
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
        val titleRow = LinearLayout(this); titleRow.orientation = LinearLayout.HORIZONTAL
        titleRow.gravity = Gravity.CENTER_VERTICAL
        val back = tv("←", 22f, Glass.text(this@MainActivity))
        back.setPadding(dp(2f), dp(4f), dp(14f), dp(4f))
        back.contentDescription = "Back to Quick Add"
        back.setOnClickListener { returnToQuickAdd() }
        titleRow.addView(back)
        titleRow.addView(tv("Customize Quick Add", 20f, Glass.text(this@MainActivity), true))
        col.addView(titleRow)

        fun toggleRow(labelText: String, note: String?, on: Boolean, flip: (Boolean) -> Unit) {
            val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.background = roundedRect(cardFill(), 14f)
            row.setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
            row.layoutParams = lp(t = dp(12f))
            val colL = LinearLayout(this); colL.orientation = LinearLayout.VERTICAL
            colL.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            colL.addView(tv(labelText, 16f))
            if (note != null) colL.addView(tv(note, 13f, TD_GREY))
            row.addView(colL)
            row.addView(switchView(on))
            row.setOnClickListener { flip(!on); Sfx.tick(); showFullSheet(buildCustomizeQa()) }
            col.addView(row)
        }
        val showLabelsNow = Prefs.prefs(this).getBoolean("qa_show_labels", true)
        toggleRow("Show action labels", null, showLabelsNow) { v -> Prefs.setBool(this, "qa_show_labels", v) }
        val showDescNow = Prefs.prefs(this).getBoolean("qa_show_desc", false)
        toggleRow("Show description", if (showDescNow) null else "The description field is hidden", showDescNow) { v ->
            Prefs.setBool(this, "qa_show_desc", v)
            qaShowNotes = v
        }

        // Live Preview card: the chip row as Quick Add will render it.
        val prevLbl = tv("Preview", 17f, Glass.text(this@MainActivity), true)
        prevLbl.layoutParams = lp(t = dp(24f), b = dp(10f))
        col.addView(prevLbl)
        val prevCard = LinearLayout(this); prevCard.orientation = LinearLayout.VERTICAL
        prevCard.background = Glass.todoistSheetBg(this, 16f, Todoist.SHEET)
        prevCard.setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
        prevCard.addView(tv("Task name", 16f, Todoist.PLACEHOLDER))
        val prevRow = LinearLayout(this); prevRow.orientation = LinearLayout.HORIZONTAL
        prevRow.layoutParams = lp(t = dp(10f))
        val chipNames = mapOf(
            "date" to "📅 Date", "attachment" to "📎 Attachment", "priority" to "⚑ Priority",
            "reminders" to "⏰ Reminders", "project" to "▣ Project", "labels" to "🏷 Labels",
            "description" to "☰ Description", "deadline" to "🎯 Deadline", "location" to "📍 Location"
        )
        Store.quickAddChipOrder().filter { it !in Store.state.quickAddHiddenChips }.take(4).forEach { id ->
            val ch = tv(if (showLabelsNow) chipNames[id] ?: id else (chipNames[id]?.take(2) ?: id), 13f)
            ch.background = Glass.qaChipBg(this, false)
            ch.setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
            ch.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6f) }
            prevRow.addView(ch)
        }
        prevCard.addView(prevRow)
        col.addView(prevCard)

        val arrLbl = tv("Task actions arrangement", 17f, Glass.text(this@MainActivity), true)
        arrLbl.layoutParams = lp(t = dp(24f), b = dp(10f))
        col.addView(arrLbl)
        val visible = Store.quickAddChipOrder().filter { it !in Store.state.quickAddHiddenChips }.toMutableList()
        val hidden = Store.state.quickAddHiddenChips.toMutableList()
        fun persist() { Store.setQuickAddChips(visible.toList(), hidden.toList()) }
        visible.forEachIndexed { idx, id ->
            val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.background = roundedRect(cardFill(), 14f)
            row.setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            row.layoutParams = lp(b = dp(8f))
            if (id == "project") {
                // Project is padlocked in the reference — it cannot be removed.
                val lock = tv("🔒", 14f, TD_GREY)
                lock.layoutParams = LinearLayout.LayoutParams(dp(34f), -2)
                row.addView(lock)
            } else {
                val minus = tv("−", 18f, 0xFFFFFFFF.toInt(), true)
                minus.gravity = Gravity.CENTER
                minus.background = ovalDrawable(TD_RED)
                minus.layoutParams = LinearLayout.LayoutParams(dp(26f), dp(26f)).apply { rightMargin = dp(10f) }
                minus.contentDescription = "Remove " + id
                minus.setOnClickListener {
                    visible.remove(id); hidden.add(id); persist()
                    showFullSheet(buildCustomizeQa())
                }
                row.addView(minus)
            }
            val nm = tv(chipNames[id]?.substringAfter(" ") ?: id, 16f)
            nm.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            row.addView(nm)
            val up = tv("↑", 16f, if (idx > 0) Glass.text(this@MainActivity) else TD_GREY)
            up.setPadding(dp(8f), dp(4f), dp(4f), dp(4f))
            up.setOnClickListener {
                if (idx > 0) {
                    val tmp = visible[idx - 1]; visible[idx - 1] = visible[idx]; visible[idx] = tmp
                    persist(); showFullSheet(buildCustomizeQa())
                }
            }
            row.addView(up)
            val down = tv("↓", 16f, if (idx < visible.size - 1) Glass.text(this@MainActivity) else TD_GREY)
            down.setPadding(dp(4f), dp(4f), dp(8f), dp(4f))
            down.setOnClickListener {
                if (idx < visible.size - 1) {
                    val tmp = visible[idx + 1]; visible[idx + 1] = visible[idx]; visible[idx] = tmp
                    persist(); showFullSheet(buildCustomizeQa())
                }
            }
            row.addView(down)
            row.addView(tv("≡", 18f, TD_GREY))
            col.addView(row)
        }

        if (hidden.isNotEmpty()) {
            val moreLbl = tv("More task actions", 17f, Glass.text(this@MainActivity), true)
            moreLbl.layoutParams = lp(t = dp(16f), b = dp(10f))
            col.addView(moreLbl)
            hidden.forEach { id ->
                val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL
                row.gravity = Gravity.CENTER_VERTICAL
                row.background = roundedRect(cardFill(), 14f)
                row.setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
                row.layoutParams = lp(b = dp(8f))
                val plus = tv("+", 16f, 0xFFBF5AF2.toInt(), true)
                plus.gravity = Gravity.CENTER
                plus.layoutParams = LinearLayout.LayoutParams(dp(26f), dp(26f)).apply { rightMargin = dp(10f) }
                plus.contentDescription = "Add " + id
                plus.setOnClickListener {
                    hidden.remove(id); visible.add(id); persist()
                    showFullSheet(buildCustomizeQa())
                }
                row.addView(plus)
                row.addView(tv(chipNames[id]?.substringAfter(" ") ?: id, 16f))
                col.addView(row)
            }
        }
        return col
    }

    private fun buildQaLabels(): View {
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
        col.addView(tv("Labels", 16f, Glass.text(this@MainActivity), true))
        if (Store.state.labels.isEmpty()) col.addView(tv("No labels yet — create one below, or type @name in the task text.", 13f, Glass.muted(this@MainActivity)))
        Store.state.labels.forEach { l ->
            val on = qaLabelIds.contains(l.id)
            val row = tv((if (on) "☑ " else "☐ ") + "@" + l.name, 15f)
            row.setPadding(dp(4f), dp(11f), dp(4f), dp(11f))
            row.setOnClickListener {
                if (on) qaLabelIds.remove(l.id) else qaLabelIds.add(l.id)
                Sfx.tick()
                showSheet(buildQaLabels())
            }
            col.addView(row)
        }
        val add = ghostBtn("＋ New label") {
            promptText("Label name:") { name ->
                val l = Store.labelByName(name)
                qaLabelIds.add(l.id)
                Store.save()
                showSheet(buildQaLabels())
            }
        }
        add.layoutParams = lp(t = dp(8f), b = dp(8f))
        col.addView(add)
        val done = accentBtn("Done") { returnToQuickAdd() }
        col.addView(done)
        return col
    }

    /* ----- Quick Add: priority ----- */

    private fun buildQaPriority(): View {
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
        col.addView(tv("Priority", 16f, Glass.text(this@MainActivity), true))
        val flags = listOf(1 to 0xFFFF453A.toInt(), 2 to 0xFFFF9F0A.toInt(), 3 to 0xFF0A84FF.toInt(), 4 to 0xFF98989F.toInt())
        flags.forEach { (n, color) ->
            val row = tv("⚑  Priority $n" + if (qaPriority == n) "  ✓" else "", 15f, color)
            row.setPadding(dp(4f), dp(12f), dp(4f), dp(12f))
            row.setOnClickListener { qaPriority = n; Sfx.tick(); returnToQuickAdd() }
            col.addView(row)
        }
        val back = ghostBtn("← Back") { if (!popSheet()) returnToQuickAdd() }
        back.layoutParams = lp(t = dp(10f))
        col.addView(back)
        return col
    }

    /* ----- Quick Add: projects ----- */

    private fun buildQaProjects(): View {
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
        col.addView(tv("My Projects", 16f, Glass.text(this@MainActivity), true))
        val inboxRow = tv("▣  Inbox" + if (qaProjectSet && qaProjectId == null) "  ✓" else "", 15f)
        inboxRow.setPadding(dp(4f), dp(12f), dp(4f), dp(12f))
        inboxRow.setOnClickListener { qaProjectSet = true; qaProjectId = null; returnToQuickAdd() }
        col.addView(inboxRow)
        Store.state.projects.forEach { p ->
            val row = tv("#  " + p.name + if (qaProjectSet && qaProjectId == p.id) "  ✓" else "", 15f)
            row.setPadding(dp(4f), dp(12f), dp(4f), dp(12f))
            row.setOnClickListener { qaProjectSet = true; qaProjectId = p.id; returnToQuickAdd() }
            col.addView(row)
        }
        val add = ghostBtn("＋ New project") {
            promptText("Project name:") { name ->
                val p = Store.projByName(name)
                qaProjectSet = true; qaProjectId = p.id
                Store.save()
                returnToQuickAdd()
            }
        }
        add.layoutParams = lp(t = dp(8f))
        col.addView(add)
        val back = ghostBtn("← Back") { if (!popSheet()) returnToQuickAdd() }
        back.layoutParams = lp(t = dp(10f))
        col.addView(back)
        return col
    }

    /* ----- Quick Add: reminders ----- */

    /** Tappable hint shown when exact alarms are off (Android 12+): reminders fall
     *  back to inexact alarms and may arrive late. Returns null when exact alarms
     *  are allowed — so the hint hides itself once the user grants the permission.
     *  No new permission is needed; this only opens the system settings screen. */
    private fun exactAlarmHint(): View? {
        if (AlarmHelper.exactAlarmsAllowed(this)) return null
        val hint = tv("⏰ Exact alarms are off — reminders may arrive late. Tap to allow on-time reminders.", 13f, Glass.overdue(this@MainActivity))
        hint.setPadding(dp(4f), dp(10f), dp(4f), dp(10f))
        hint.setOnClickListener {
            try {
                val i = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                i.data = Uri.parse("package:" + packageName)
                startActivity(i)
            } catch (e: Exception) {
                toast("Open Settings → Apps → GhostBlade Task → Alarms & reminders to allow exact alarms")
            }
        }
        return hint
    }

    private fun buildQaReminders(): View {
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
        col.addView(tv("Reminders", 16f, Glass.text(this@MainActivity), true))
        val parsed = Parser.parse(qaText)
        if (qaDate == null && parsed.dueDate == null) {
            val hint = tv("Add a day and time to the task first — pick a date, then choose a reminder.", 13f, Glass.overdue(this@MainActivity))
            hint.layoutParams = lp(t = dp(6f), b = dp(6f))
            col.addView(hint)
        }
        val opts = listOf<Pair<String, Int?>>(
            "No reminder" to null, "At time of task" to 0, "10 minutes before" to 10,
            "30 minutes before" to 30, "1 hour before" to 60, "1 day before" to 1440
        )
        opts.forEach { (lbl, v) ->
            val on = qaRemSet && qaReminder == v
            val row = tv("⏰  $lbl" + if (on) "  ✓" else "", 15f)
            row.setPadding(dp(4f), dp(12f), dp(4f), dp(12f))
            row.setOnClickListener { qaRemSet = true; qaReminder = v; Sfx.tick(); returnToQuickAdd() }
            col.addView(row)
        }
        exactAlarmHint()?.let { col.addView(it) }
        val back = ghostBtn("← Back") { if (!popSheet()) returnToQuickAdd() }
        back.layoutParams = lp(t = dp(10f))
        col.addView(back)
        return col
    }

    /* ----- Quick Add: date picker ----- */

    /** One month grid for the date sheet (B2): today is a FILLED red
     *  circle with a white number, the selected day is filled red too,
     *  and a small grey dot sits under days that have tasks. Cells set
     *  the active target (due date, or deadline while
     *  qaPickingDeadline) and rebuild the sheet. */
    private fun qaMonthGrid(year: Int, month: Int): View {
        val wrap = LinearLayout(this); wrap.orientation = LinearLayout.VERTICAL
        val monthNames = arrayOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
        val mt = tv(monthNames[month] + " " + year, 15f, Glass.text(this@MainActivity), true)
        mt.layoutParams = lp(t = dp(14f), b = dp(6f))
        wrap.addView(mt)
        val dowRow = LinearLayout(this); dowRow.orientation = LinearLayout.HORIZONTAL
        listOf("M", "T", "W", "T", "F", "S", "S").forEach { d ->
            val t = tv(d, 11f, Glass.muted(this@MainActivity), true); t.gravity = Gravity.CENTER
            t.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            dowRow.addView(t)
        }
        wrap.addView(dowRow)
        val first = java.util.Calendar.getInstance(); first.set(year, month, 1, 0, 0, 0)
        val startDow = (Dates.jsDayOfWeek(first) + 6) % 7 // Monday-first
        val dim = first.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
        val active = if (qaPickingDeadline) qaDeadline else qaDate
        var day = 1
        for (week in 0 until 6) {
            if (day > dim) break
            val rowL = LinearLayout(this); rowL.orientation = LinearLayout.HORIZONTAL
            for (dowI in 0 until 7) {
                val cell = LinearLayout(this); cell.orientation = LinearLayout.VERTICAL
                cell.gravity = Gravity.CENTER_HORIZONTAL
                cell.layoutParams = LinearLayout.LayoutParams(0, dp(46f), 1f)
                if (!(week == 0 && dowI < startDow) && day <= dim) {
                    val ds = year.toString() + "-" + (month + 1).toString().padStart(2, '0') + "-" + day.toString().padStart(2, '0')
                    val isToday = ds == Dates.today()
                    val isSel = ds == active
                    val numWrap = FrameLayout(this)
                    numWrap.layoutParams = LinearLayout.LayoutParams(dp(30f), dp(30f))
                    if (isToday || isSel) numWrap.background = ovalDrawable(TD_RED)
                    val num = tv(day.toString(), 14f,
                        if (isToday || isSel) 0xFFFFFFFF.toInt()
                        else if (ds < Dates.today()) TD_GREY else Glass.text(this@MainActivity), isToday)
                    num.gravity = Gravity.CENTER
                    num.layoutParams = FrameLayout.LayoutParams(-1, -1)
                    numWrap.addView(num)
                    cell.addView(numWrap)
                    val dot = View(this)
                    dot.layoutParams = LinearLayout.LayoutParams(dp(4f), dp(4f)).apply { topMargin = dp(3f) }
                    if (Store.state.tasks.any { it.dueDate == ds }) dot.background = ovalDrawable(TD_GREY)
                    else dot.visibility = View.INVISIBLE
                    cell.addView(dot)
                    val dd = ds
                    cell.setOnClickListener {
                        if (qaPickingDeadline) qaDeadline = dd else qaDate = dd
                        Sfx.tick()
                        showSheet(buildQaDatePicker())
                    }
                    day++
                }
                rowL.addView(cell)
            }
            wrap.addView(rowL)
        }
        return wrap
    }

    private fun buildQaDatePicker(): View {
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
        col.addView(tv(if (qaPickingDeadline) "Deadline" else "Date", 17f, Glass.text(this@MainActivity), true))
        fun activeDate(): String? = if (qaPickingDeadline) qaDeadline else qaDate
        fun setActiveDate(d: String?) { if (qaPickingDeadline) qaDeadline = d else qaDate = d }

        // Typeable natural-language field (VIDEO F30: the sheet carries
        // the typed recurrence, e.g. "ev 2 days starting monday"). Ours
        // accepts every phrase the Parser understands — "every day",
        // "every monday", "tomorrow", "5 jan", "next week" — applied to
        // the recurrence + the active date on Apply.
        val recPhrase = when {
            !qaRecSet || qaRecurrence == null -> ""
            qaRecurrence == "daily" -> "every day"
            qaRecurrence == "weekly" -> "every week"
            qaRecurrence == "monthly" -> "every month"
            qaRecurrence!!.startsWith("weekly:") -> "every " + listOf("sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday")[qaRecurrence!!.substringAfter(':').toIntOrNull() ?: 1]
            else -> qaRecurrence!!
        }
        val nlIn = input("Type a date or repeat — e.g. every monday", recPhrase)
        col.addView(nlIn)
        val nlApply = tv("Apply", 14f, TD_SALMON, true)
        nlApply.setPadding(dp(4f), dp(2f), dp(4f), dp(8f))
        nlApply.setOnClickListener {
            val parsedNl = Parser.parse("Task " + nlIn.text.toString().trim())
            if (parsedNl.recurrence != null) {
                qaRecSet = true; qaRecurrence = parsedNl.recurrence
                if (parsedNl.dueDate != null && !qaPickingDeadline) qaDate = parsedNl.dueDate
            }
            if (parsedNl.dueDate != null) setActiveDate(parsedNl.dueDate)
            if (parsedNl.deadline != null && qaPickingDeadline) qaDeadline = parsedNl.deadline
            Sfx.tick()
            showSheet(buildQaDatePicker())
        }
        col.addView(nlApply)

        // Quick rows with red icons (VIDEO F30).
        val dayNames = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        fun quickRow(icon: String, lbl: String, date: String) {
            val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(4f), dp(12f), dp(4f), dp(12f))
            val ic = tv(icon, 16f, TD_RED); ic.layoutParams = LinearLayout.LayoutParams(dp(34f), -2)
            row.addView(ic)
            val tt = tv(lbl + if (activeDate() == date) "  ✓" else "", 15f)
            tt.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            row.addView(tt)
            row.addView(tv(dayNames[Dates.jsDayOfWeek(Dates.parse(date))], 13f, Glass.muted(this@MainActivity)))
            row.setOnClickListener { setActiveDate(date); Sfx.tick(); showSheet(buildQaDatePicker()) }
            col.addView(row)
        }
        quickRow("📅", "Today", Dates.today())
        quickRow("🌅", "Tomorrow", Dates.addDays(Dates.today(), 1))
        quickRow("⏭️", "Next week", Dates.addDays(Dates.today(), 7))
        quickRow("📆", "Next Monday", Dates.nextWeekday(1))
        if (activeDate() != null) {
            val clr = tv("✕  Clear date", 15f, Glass.overdue(this@MainActivity))
            clr.setPadding(dp(4f), dp(10f), dp(4f), dp(10f))
            clr.setOnClickListener { setActiveDate(null); if (!qaPickingDeadline) qaTime = null; showSheet(buildQaDatePicker()) }
            col.addView(clr)
        }

        // Multi-month scroll: three stacked months in the sheet's own
        // ScrollView (the reference shows two; three keeps the same
        // continuous-scroll shape with more reach).
        val base = Dates.parse(activeDate() ?: Dates.today())
        for (mOff in 0..2) {
            val mc = base.clone() as java.util.Calendar
            mc.set(java.util.Calendar.DAY_OF_MONTH, 1)
            mc.add(java.util.Calendar.MONTH, mOff)
            col.addView(qaMonthGrid(mc.get(java.util.Calendar.YEAR), mc.get(java.util.Calendar.MONTH)))
        }

        if (!qaPickingDeadline) {
            val timeRow = qaMenuRow("🕐", if (qaTime != null) "Time · " + qaTime else "Add time", null) { pushSheet(buildQaTimePicker()) { buildQaDatePicker() } }
            col.addView(timeRow)
            // The summary is generated from the due date itself
            // (RepeatLabels, §8.4): "Repeat · Monthly on the 21 day",
            // with a ✕ that clears back to No repeat.
            val recLbl = if (!qaRecSet || qaRecurrence == null) "Repeat"
                else "Repeat · " + RepeatLabels.summaryLabel(qaRecurrence, activeDate() ?: Dates.today())
            val recRow = LinearLayout(this); recRow.orientation = LinearLayout.HORIZONTAL
            recRow.gravity = Gravity.CENTER_VERTICAL
            val recMenu = qaMenuRow("🔁", recLbl, null) { pushSheet(buildQaRepeat()) { buildQaDatePicker() } }
            recMenu.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            recRow.addView(recMenu)
            if (qaRecSet) {
                val clrRec = tv("✕", 15f, Glass.muted(this@MainActivity), true)
                clrRec.gravity = Gravity.CENTER
                clrRec.setPadding(dp(12f), dp(12f), dp(6f), dp(12f))
                clrRec.contentDescription = "Clear repeat"
                clrRec.setOnClickListener { qaRecSet = false; qaRecurrence = null; showSheet(buildQaDatePicker()) }
                recRow.addView(clrRec)
            }
            col.addView(recRow)
        }
        // Save — red rounded-rectangle, bottom-right (not a pill).
        val saveRow = LinearLayout(this); saveRow.orientation = LinearLayout.HORIZONTAL
        saveRow.layoutParams = lp(t = dp(14f))
        val spacer = View(this)
        spacer.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        saveRow.addView(spacer)
        val save = tv("Save", 15f, 0xFFFFFFFF.toInt(), false)
        save.gravity = Gravity.CENTER
        save.background = roundedRect(TD_RED, 10f)
        save.layoutParams = LinearLayout.LayoutParams(dp(72f), dp(40f))
        save.setOnClickListener {
            val editId = taskDateEditId
            if (editId != null) {
                // Opened from a task's swipe Date action: the pick writes
                // straight onto that task, not the Quick Add draft.
                val t = Store.byId(editId)
                if (t != null) {
                    t.dueDate = qaDate
                    t.dueTime = qaTime
                    // The sheet was preloaded with the task's own
                    // recurrence: write back whatever the repeat row
                    // now holds (unchanged, changed, or cleared).
                    t.recurrence = if (qaRecSet) qaRecurrence else null
                    Reminders.sync(this, t)
                    Store.save()
                }
                taskDateEditId = null
                qaDate = null; qaTime = null
                qaPickingDeadline = false
                closeSheet(); render()
            } else {
                qaPickingDeadline = false; returnToQuickAdd()
            }
        }
        saveRow.addView(save)
        col.addView(saveRow)
        val back = ghostBtn("← Back") {
            qaPickingDeadline = false
            if (taskDateEditId != null) {
                taskDateEditId = null; qaDate = null; qaTime = null; closeSheet()
            } else if (!popSheet()) returnToQuickAdd()
        }
        back.layoutParams = lp(t = dp(10f))
        col.addView(back)
        return col
    }

    private fun buildQaTimePicker(): View {
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
        col.addView(tv("Time", 16f, Glass.text(this@MainActivity), true))
        val inp = input("HH:mm — e.g. 18:30", qaTime ?: "")
        col.addView(inp)
        val chipsRow = LinearLayout(this); chipsRow.orientation = LinearLayout.HORIZONTAL
        listOf("07:00", "09:00", "12:00", "18:00", "21:00").forEach { tm ->
            val ch = chipBtn(tm, qaTime == tm) { qaTime = tm; returnToSheet(buildQaDatePicker()) }
            ch.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6f) }
            chipsRow.addView(ch)
        }
        col.addView(chipsRow)
        val save = accentBtn("Save time") {
            val v = inp.text.toString().trim()
            qaTime = if (Regex("""^([01]?\d|2[0-3]):[0-5]\d$""").matches(v)) {
                val parts = v.split(":")
                parts[0].padStart(2, '0') + ":" + parts[1]
            } else qaTime
            returnToSheet(buildQaDatePicker())
        }
        save.layoutParams = lp(t = dp(10f), b = dp(6f))
        col.addView(save)
        if (qaTime != null) {
            val clr = ghostBtn("Clear time") { qaTime = null; returnToSheet(buildQaDatePicker()) }
            col.addView(clr)
        }
        val back = ghostBtn("← Back") { if (!popSheet()) returnToSheet(buildQaDatePicker()) }
        back.layoutParams = lp(t = dp(10f))
        col.addView(back)
        return col
    }

    private fun buildQaRepeat(): View {
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
        col.addView(tv("Repeat", 16f, Glass.text(this@MainActivity), true))
        val opts = listOf<Pair<String, String?>>(
            "No repeat" to null, "Every day" to "daily", "Every week" to "weekly", "Every month" to "monthly",
            "Every Sunday" to "weekly:0", "Every Monday" to "weekly:1", "Every Tuesday" to "weekly:2",
            "Every Wednesday" to "weekly:3", "Every Thursday" to "weekly:4", "Every Friday" to "weekly:5",
            "Every Saturday" to "weekly:6"
        )
        opts.forEach { (lbl, v) ->
            val on = qaRecSet && qaRecurrence == v
            // Menu labels are generated from the due date (RepeatLabels):
            // "Weekly (Sat)", "Monthly (The 21 day)"… (§8.4).
            val row = tv("🔁  " + (if (v == null) lbl else RepeatLabels.menuLabel(v, qaDate ?: Dates.today())) + if (on) "  ✓" else "", 15f)
            row.setPadding(dp(4f), dp(11f), dp(4f), dp(11f))
            row.setOnClickListener {
                qaRecSet = true; qaRecurrence = v
                if (v != null && v.startsWith("weekly:") && qaDate == null) {
                    qaDate = Dates.nextWeekday(v.substringAfter(':').toInt())
                }
                Sfx.tick()
                returnToSheet(buildQaDatePicker())
            }
            col.addView(row)
        }
        val back = ghostBtn("← Back") { if (!popSheet()) returnToSheet(buildQaDatePicker()) }
        back.layoutParams = lp(t = dp(10f))
        col.addView(back)
        return col
    }

    /* ----- Quick Add: location capture ----- */

    private fun captureLocationQa() {
        if (Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 77)
            toast("Allow location permission, then tap again")
            returnToQuickAdd()
            return
        }
        val lm = getSystemService(LOCATION_SERVICE) as? LocationManager
        if (lm == null) { toast("Location unavailable"); returnToQuickAdd(); return }
        toast("Getting your location…")
        // One-shot: the same listener is registered with both providers,
        // so without this the first fix would attach the location twice
        // (double toast + double sheet rebuild).
        var done = false
        fun apply(lat: Double, lng: Double) {
            if (done) return
            done = true
            qaLocLat = lat; qaLocLng = lng
            toast("Location attached 📍")
            returnToQuickAdd()
        }
        try {
            var best: Location? = null
            for (p in lm.getProviders(true)) {
                val l = lm.getLastKnownLocation(p)
                if (l != null && (best == null || l.time > best!!.time)) best = l
            }
            val b = best
            if (b != null && System.currentTimeMillis() - b.time < 10 * 60 * 1000) {
                apply(b.latitude, b.longitude)
                return
            }
            val listener = object : LocationListener {
                override fun onLocationChanged(l: Location) {
                    if (done) return
                    try { lm.removeUpdates(this) } catch (e: Exception) {}
                    apply(l.latitude, l.longitude)
                }
                override fun onStatusChanged(p: String?, s: Int, bundle: Bundle?) {}
                override fun onProviderEnabled(p: String) {}
                override fun onProviderDisabled(p: String) {}
            }
            var any = false
            try { lm.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, listener, Looper.getMainLooper()); any = true } catch (e: Exception) {}
            try { lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, listener, Looper.getMainLooper()); any = true } catch (e: Exception) {}
            if (!any) { toast("Could not get location — check GPS"); returnToQuickAdd() }
        } catch (e: SecurityException) {
            toast("Location permission needed")
            returnToQuickAdd()
        }
    }

    /* ---------------- editor ---------------- */

    private fun openEditor(id: String) {
        val t = Store.byId(id) ?: return
        editingId = id
        // Snapshot the last saved state — the discard guard compares the
        // editor's fields against this, not against the live (stash-mutated) task.
        editorSnapshot = t.copy()
        showSheet(buildEditor(t))
    }

    private fun buildEditor(t: TaskItem): View {
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
        col.addView(tv("Edit task", 17f, Glass.text(this@MainActivity), true))
        col.addView(label("Title"))
        val edTitle = input("", t.title); col.addView(edTitle)
        col.addView(label("Notes"))
        val edNotes = input("Notes", t.notes, multi = true); col.addView(edNotes)
        col.addView(label("Due date (yyyy-MM-dd, blank = none)"))
        val edDate = input("2026-11-20", t.dueDate ?: ""); col.addView(edDate)
        val dateChips = LinearLayout(this); dateChips.orientation = LinearLayout.HORIZONTAL
        listOf("Today" to Dates.today(), "Tomorrow" to Dates.addDays(Dates.today(), 1), "Clear" to "").forEach { (l, v) ->
            val ch = chipBtn(l, false) { edDate.setText(v) }
            ch.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6f) }
            dateChips.addView(ch)
        }
        col.addView(dateChips)
        col.addView(label("Time (HH:mm, blank = none)"))
        val edTime = input("09:00", t.dueTime ?: ""); col.addView(edTime)

        val recLabels = listOf("No repeat", "Every day", "Every week", "Every month",
            "Every Sunday", "Every Monday", "Every Tuesday", "Every Wednesday", "Every Thursday", "Every Friday", "Every Saturday")
        val recValues = listOf<String?>(null, "daily", "weekly", "monthly",
            "weekly:0", "weekly:1", "weekly:2", "weekly:3", "weekly:4", "weekly:5", "weekly:6")
        col.addView(label("Repeat"))
        val edRec = spinner(recLabels, recValues.indexOf(t.recurrence).coerceAtLeast(0)); col.addView(edRec)

        col.addView(label("Priority"))
        val edPri = spinner(listOf("P1 — highest", "P2", "P3", "P4"), t.priority - 1); col.addView(edPri)

        col.addView(label("Project"))
        val projNames = listOf("Inbox (no project)") + Store.state.projects.map { it.name }
        val projIds = listOf<String?>(null) + Store.state.projects.map { it.id }
        val edProj = spinner(projNames, projIds.indexOf(t.projectId).coerceAtLeast(0)); col.addView(edProj)

        // Section picker (Correction Round 2, F-2): lists the sections of
        // the task's project; rebuilt when the project changes (listener
        // below) so the options always match the chosen project.
        col.addView(label("Section"))
        val secItems = Store.state.sections.filter { it.projectId == t.projectId }.sortedBy { it.order }
        val secNames = listOf("No section") + secItems.map { it.name }
        val secIds = listOf<String?>(null) + secItems.map { it.id }
        val edSec = spinner(secNames, secIds.indexOf(t.sectionId).coerceAtLeast(0)); col.addView(edSec)

        col.addView(label("Duration (minutes)"))
        val edDur = input("e.g. 30", t.durationMin?.toString() ?: ""); col.addView(edDur)

        val remLabels = listOf("No reminder", "At due time", "10 min before", "30 min before", "1 hour before", "1 day before")
        val remValues = listOf<Int?>(null, 0, 10, 30, 60, 1440)
        col.addView(label("Reminder (fires even if app is closed)"))
        val edRem = spinner(remLabels, remValues.indexOf(t.reminderOffsetMin).coerceAtLeast(0)); col.addView(edRem)
        exactAlarmHint()?.let { col.addView(it) }
        // Starred (v5): local field, folded into stash/dirty/Save below.
        var edStarred = t.starred

        // Copy in-progress field edits into the task before any action that
        // rebuilds this sheet (subtasks, location), so typed text isn't lost.
        fun stash() {
            t.title = edTitle.text.toString().trim().ifEmpty { t.title }
            t.notes = edNotes.text.toString()
            t.dueDate = edDate.text.toString().trim().ifEmpty { null }
            t.dueTime = edTime.text.toString().trim().ifEmpty { null }
            t.recurrence = recValues[edRec.selectedItemPosition]
            t.priority = edPri.selectedItemPosition + 1
            t.projectId = projIds[edProj.selectedItemPosition]
            // A section only survives if it belongs to the (possibly just
            // changed) project — the guard keeps a stale cross-project
            // section from being stashed onto the task.
            t.sectionId = secIds[edSec.selectedItemPosition]?.takeIf { sid ->
                Store.state.sections.any { it.id == sid && it.projectId == t.projectId }
            }
            t.durationMin = edDur.text.toString().trim().toIntOrNull()?.takeIf { it > 0 }
            t.reminderOffsetMin = remValues[edRem.selectedItemPosition]
            t.starred = edStarred
            // Stash+save flows persist the fields, so the discard guard's
            // snapshot must track the last saved state too.
            editorSnapshot = t.copy()
        }

        // Discard guard: true when any field differs from the last saved
        // state. Spinner values are compared against what the spinner
        // actually displays for the snapshot (an unrecognised imported value
        // falls back to the first option, so it must not count as an edit).
        editorDirtyCheck = {
            val s = editorSnapshot
            if (s == null) false else
                edTitle.text.toString().trim().ifEmpty { s.title } != s.title ||
                edNotes.text.toString() != s.notes ||
                edDate.text.toString().trim().ifEmpty { null } != s.dueDate ||
                edTime.text.toString().trim().ifEmpty { null } != s.dueTime ||
                recValues[edRec.selectedItemPosition] != recValues[recValues.indexOf(s.recurrence).coerceAtLeast(0)] ||
                edPri.selectedItemPosition + 1 != s.priority.coerceIn(1, 4) ||
                projIds[edProj.selectedItemPosition] != projIds[projIds.indexOf(s.projectId).coerceAtLeast(0)] ||
                secIds[edSec.selectedItemPosition] != secIds[secIds.indexOf(s.sectionId).coerceAtLeast(0)] ||
                edDur.text.toString().trim().toIntOrNull()?.takeIf { it > 0 } != s.durationMin ||
                remValues[edRem.selectedItemPosition] != remValues[remValues.indexOf(s.reminderOffsetMin).coerceAtLeast(0)] ||
                edStarred != s.starred
        }

        // When the project changes, stash everything typed so far (the
        // stash guard drops a section that belongs to the old project)
        // and rebuild the sheet so the Section spinner lists the new
        // project's sections. The init flag swallows the spinner's
        // initial selection callback.
        var projListenerInit = true
        edProj.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (projListenerInit) { projListenerInit = false; return }
                if (projIds[pos] == t.projectId) return
                stash()
                editorSnapshot = t.copy()
                Store.save()
                showSheet(buildEditor(t))
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        col.addView(label("Starred"))
        val starRow = LinearLayout(this); starRow.orientation = LinearLayout.HORIZONTAL
        starRow.gravity = Gravity.CENTER_VERTICAL
        starRow.setPadding(0, dp(4f), 0, dp(4f))
        val starGlyph = tv(if (edStarred) "★" else "☆", 20f, if (edStarred) 0xFFFFD60A.toInt() else Glass.muted(this@MainActivity))
        starGlyph.layoutParams = LinearLayout.LayoutParams(dp(32f), -2)
        starRow.addView(starGlyph)
        val starLbl = tv("Starred", 15f)
        starLbl.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        starRow.addView(starLbl)
        starRow.addView(switchView(edStarred))
        starRow.setOnClickListener {
            edStarred = !edStarred
            stash()
            Store.save()
            Sfx.tick()
            showSheet(buildEditor(t))
        }
        col.addView(starRow)

        col.addView(label("Location reminder"))
        if (t.locLat != null) {
            col.addView(tv("📍 Set — you'll be reminded when you arrive at the saved place.", 13f, Glass.muted(this@MainActivity)))
            val clear = ghostBtn("Clear location reminder") {
                stash()
                t.locLat = null; t.locLng = null
                LocationReceiver.disarm(this, t.id)
                Reminders.sync(this, t)
                Store.save(); showSheet(buildEditor(t)); toast("Location reminder cleared")
            }
            col.addView(clear)
        } else {
            val setBtn = ghostBtn("📍 Remind me at this place") { stash(); captureLocation(t) }
            col.addView(setBtn)
            col.addView(tv("Stand at the place (home, office, gym), tap the button — the app remembers it and reminds you when you arrive.", 13f, Glass.muted(this@MainActivity)))
        }

        col.addView(dayGroup("SUBTASKS"))
        t.subtasks.forEach { st ->
            val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(0, dp(6f), 0, dp(6f))
            val box = tv(if (st.done) "✓" else "", 12f, 0xFFFFFFFF.toInt(), true)
            box.gravity = Gravity.CENTER
            val bg = android.graphics.drawable.GradientDrawable()
            bg.cornerRadius = dp(6f).toFloat()
            if (st.done) bg.setColor(Glass.GREEN) else bg.setStroke(dp(2f), 0x66FFFFFF)
            box.background = bg
            box.layoutParams = LinearLayout.LayoutParams(dp(21f), dp(21f))
            box.setOnClickListener { stash(); st.done = !st.done; Sfx.tick(); Store.save(); showSheet(buildEditor(t)) }
            row.addView(box)
            val stT = tv(st.title, 15f, if (st.done) Glass.muted(this@MainActivity) else Glass.text(this@MainActivity))
            if (st.done) stT.paintFlags = stT.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            stT.layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(9f) }
            row.addView(stT)
            val del = tv("✕", 14f, Glass.muted(this@MainActivity))
            del.setOnClickListener { stash(); t.subtasks.removeAll { it.id == st.id }; Store.save(); showSheet(buildEditor(t)) }
            row.addView(del)
            col.addView(row)
        }
        val subRow = LinearLayout(this); subRow.orientation = LinearLayout.HORIZONTAL
        val subIn = input("Add a subtask"); subIn.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        val subAdd = ghostBtn("Add") {
            val v = subIn.text.toString().trim()
            if (v.isNotEmpty()) { stash(); t.subtasks.add(Subtask(uid(), v, false)); Store.save(); showSheet(buildEditor(t)) }
        }
        subRow.addView(subIn); subRow.addView(subAdd)
        col.addView(subRow)
        val breakdown = ghostBtn("🤖 Break it down (auto subtasks)") {
            stash()
            breakdownFor(t.title).forEach { x -> t.subtasks.add(Subtask(uid(), x, false)) }
            Store.save(); showSheet(buildEditor(t)); toast("Subtasks added 🤖")
        }
        breakdown.layoutParams = lp(t = dp(6f), b = dp(10f))
        col.addView(breakdown)
        if (t.subtasks.isNotEmpty()) {
            // v5: the editor's subtasks can become a reusable checklist
            // template (SHADOW-THREE-DESIGN §8.3).
            val saveTpl = ghostBtn("▤ Save subtasks as checklist template") {
                stash()
                promptText("Checklist name:") { name ->
                    Store.addChecklistTemplate(name, t.subtasks.map { it.title })
                    toast("Checklist template saved")
                }
            }
            saveTpl.layoutParams = lp(b = dp(10f))
            col.addView(saveTpl)
        }

        val save = accentBtn("Save") {
            val dateTxt = edDate.text.toString().trim()
            if (dateTxt.isNotEmpty() && !isValidDateStr(dateTxt)) {
                toast("Date must be a real date like 2026-11-20")
                return@accentBtn
            }
            val timeTxt = edTime.text.toString().trim()
            if (timeTxt.isNotEmpty() && !Regex("""^([01]\d|2[0-3]):[0-5]\d$""").matches(timeTxt)) {
                toast("Time must be HH:mm like 18:30")
                return@accentBtn
            }
            t.title = edTitle.text.toString().trim().ifEmpty { t.title }
            t.notes = edNotes.text.toString()
            t.dueDate = dateTxt.ifEmpty { null }
            t.dueTime = timeTxt.ifEmpty { null }
            t.recurrence = recValues[edRec.selectedItemPosition]
            t.priority = edPri.selectedItemPosition + 1
            t.projectId = projIds[edProj.selectedItemPosition]
            t.sectionId = secIds[edSec.selectedItemPosition]?.takeIf { sid ->
                Store.state.sections.any { it.id == sid && it.projectId == t.projectId }
            }
            t.durationMin = edDur.text.toString().trim().toIntOrNull()?.takeIf { it > 0 }
            t.reminderOffsetMin = remValues[edRem.selectedItemPosition]
            t.starred = edStarred
            Reminders.sync(this, t)
            Reminders.syncGeo(this, t)
            Store.save()
            Sfx.tick(); closeSheet(); render(); toast("Saved ✅")
        }
        col.addView(save)
        val del = tv("Delete task", 15f, Glass.overdue(this@MainActivity))
        del.gravity = Gravity.CENTER
        del.setPadding(dp(12f), dp(14f), dp(12f), dp(6f))
        del.setOnClickListener {
            Sfx.del()
            // Capture the position BEFORE deleting — afterwards the task is
            // gone and indexOfFirst can only ever return -1.
            val idx = Store.state.tasks.indexOfFirst { it.id == t.id }
            val removed = Store.deleteTask(t.id)
            closeSheet(); render()
            if (removed != null) showUndo(removed, idx)
        }
        col.addView(del)
        return col
    }

    private fun showUndo(task: TaskItem, index: Int) {
        lastDeleted = task to index
        undoBar.removeAllViews()
        val lbl = tv("Deleted \"${task.title}\"", 13f)
        lbl.maxLines = 1
        lbl.ellipsize = android.text.TextUtils.TruncateAt.END
        lbl.maxWidth = dp(220f)
        lbl.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(10f) }
        undoBar.addView(lbl)
        val btn = tv("UNDO", 15f, Glass.gold(this@MainActivity), true)
        btn.setPadding(dp(8f), dp(10f), dp(8f), dp(10f))
        btn.setOnClickListener {
            val (task2, idx2) = lastDeleted ?: return@setOnClickListener
            // Restore at the original position, not the end of the list.
            Store.state.tasks.add(idx2.coerceIn(0, Store.state.tasks.size), task2)
            Reminders.sync(this, task2); Reminders.syncGeo(this, task2)
            lastDeleted = null
            undoBar.visibility = View.GONE
            Store.save(); render(); toast("Restored ↩️")
        }
        undoBar.addView(btn)
        undoHide?.let { handler.removeCallbacks(it) }
        undoHide = Runnable { undoBar.visibility = View.GONE; lastDeleted = null }
        // If a sheet is still sliding away (the editor-delete path), let it
        // clear first so the bar doesn't flash underneath the sheet.
        val showDelay = if (sheetRoot.visibility == View.VISIBLE) 280L else 0L
        handler.postDelayed({
            undoBar.visibility = View.VISIBLE
            handler.postDelayed(undoHide!!, 5000)
        }, showDelay)
    }

    private fun breakdownFor(title: String): List<String> {
        val t = title.lowercase()
        val generic = listOf("Research & gather what you need", "Plan the steps", "Do the first small step", "Review & fix", "Finish & wrap up")
        return when {
            Regex("video|reel|short").containsMatchIn(t) -> listOf("Write the script", "Record / generate the footage", "Edit the video", "Add captions & thumbnail", "Upload & schedule")
            Regex("script|write|blog|essay").containsMatchIn(t) -> listOf("Outline the main points", "Write a rough first draft", "Edit for clarity", "Proofread", "Publish / submit")
            Regex("study|exam|learn|read|chapter").containsMatchIn(t) -> listOf("Skim the material first", "Study in 25-minute focus blocks", "Write summary notes", "Self-test / practice questions", "Review weak spots")
            Regex("buy|shop|order").containsMatchIn(t) -> listOf("Make the list", "Compare prices", "Place the order", "Check delivery")
            Regex("clean|room|organize").containsMatchIn(t) -> listOf("Clear the clutter", "Clean surfaces", "Organize what remains", "Take out the trash")
            Regex("project|build|app|code").containsMatchIn(t) -> listOf("Define the scope", "Set up the project", "Build the core feature", "Test it", "Ship / share it")
            else -> generic
        }
    }

    /* ---------------- Phase 3 screens ---------------- */

    /** Strict yyyy-MM-dd check: right shape AND a real calendar date. */
    private fun isValidDateStr(s: String): Boolean {
        if (!Regex("""^\d{4}-\d{2}-\d{2}$""").matches(s)) return false
        return try {
            val c = Dates.parse(s)
            Dates.dstr(c) == s
        } catch (e: Exception) { false }
    }

    private fun fmtWhen(at: Long): String {
        val d = Dates.dstr(java.util.Calendar.getInstance().apply { timeInMillis = at })
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = at }
        val hh = cal.get(java.util.Calendar.HOUR_OF_DAY).toString().padStart(2, '0')
        val mm = cal.get(java.util.Calendar.MINUTE).toString().padStart(2, '0')
        return when (d) {
            Dates.today() -> "Today $hh:$mm"
            Dates.addDays(Dates.today(), -1) -> "Yesterday $hh:$mm"
            else -> Dates.fmtDay(d) + " $hh:$mm"
        }
    }

    private fun progressBar(pct: Float): FrameLayout {
        val wrap = FrameLayout(this)
        wrap.background = Glass.chip(this, false)
        wrap.layoutParams = lp(t = dp(8f))
        val fill = View(this)
        fill.background = Glass.accentGradient(this, 3f)
        wrap.addView(fill, FrameLayout.LayoutParams(0, dp(7f)))
        // Batch 9 (P2-10) — sweep the fill in over 300ms instead of snapping.
        wrap.post {
            val target = (wrap.width * pct).toInt()
            ValueAnimator.ofInt(0, target).apply {
                duration = 300
                interpolator = sheetEase
                addUpdateListener { fill.layoutParams = FrameLayout.LayoutParams(it.animatedValue as Int, dp(7f)) }
                start()
            }
        }
        return wrap
    }

    /* ----- Calendar ----- */

    /* ================= v5 (SHADOW-THREE + SHADOW-FOUR) =================
       Everything below in this section is new v5 code. v5 surfaces are
       FLAT and OPAQUE — roundedRect fills only, never Glass.panel /
       sheetPanel gradients (Jervis ruling 2). The v4.0 screens above
       keep working untouched when a rollback theme is selected. */

    private fun isV5() = Glass.isV5(this)

    /** The one card fill: V5.CARD under the v5 theme, TD_CARD otherwise,
     *  so shared builders (grouped containers, prod cards, sheets) pick
     *  up the v5 surface without forking their code. */
    private fun cardFill(): Int = if (isV5()) V5.CARD else TD_CARD

    /** Chrome for the bottom bar: under v5 it becomes the floating pill
     *  dock (POPUP fill, 64dp, 20dp side margins, 14dp above the screen
     *  edge); other themes keep the attached flat bar. Called from
     *  buildRoot and applyTheme so a runtime theme switch restyles it. */
    private fun styleNavBar() {
        if (isV5()) {
            navBar.background = roundedRect(V5.POPUP, 999f, V5.HAIRLINE)
            navBar.setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
            val np = FrameLayout.LayoutParams(-1, dp(64f))
            np.gravity = Gravity.BOTTOM
            np.setMargins(dp(20f), 0, dp(20f), dp(14f))
            navBar.layoutParams = np
            Glass.elevate(this, navBar, 999f, 8f)
        } else {
            navBar.background = null
            navBar.setBackgroundColor(TD_NAV)
            navBar.setPadding(dp(4f), dp(6f), dp(4f), dp(6f))
            val np = FrameLayout.LayoutParams(-1, -2)
            np.gravity = Gravity.BOTTOM
            navBar.layoutParams = np
        }
    }

    private fun v5CardBg(radius: Float = V5.R_CARD, fill: Int = V5.CARD): android.graphics.drawable.GradientDrawable =
        roundedRect(fill, radius, V5.HAIRLINE)

    /** Tinted icon square (§2.9): ~15% hue tint fill, solid glyph. */
    private fun iconWell(glyph: String, color: Int, boxDp: Float = 28f, glyphSp: Float = 14f): TextView {
        val t = tv(glyph, glyphSp, color, true)
        t.gravity = Gravity.CENTER
        t.background = roundedRect(V5.tint(color, 38), 9f)
        t.layoutParams = LinearLayout.LayoutParams(dp(boxDp), dp(boxDp))
        return t
    }

    /** Empty state (§2.11): accent-tint circle glyph, title, caption. */
    private fun v5EmptyState(glyph: String, title: String, caption: String): LinearLayout {
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.setPadding(dp(24f), dp(40f), dp(24f), dp(24f))
        val ic = tv(glyph, 26f, V5.ACCENT_BRAND, true)
        ic.gravity = Gravity.CENTER
        ic.background = ovalDrawable(V5.ACCENT_TINT)
        ic.layoutParams = LinearLayout.LayoutParams(dp(56f), dp(56f))
        col.addView(ic)
        val tt = tv(title, 17f, V5.TEXT, true)
        tt.gravity = Gravity.CENTER
        tt.layoutParams = lp(t = dp(14f))
        col.addView(tt)
        val cc = tv(caption, 13f, V5.MUTED)
        cc.gravity = Gravity.CENTER
        cc.layoutParams = lp(t = dp(4f))
        col.addView(cc)
        return col
    }

    /* ---------------- v5 Today ---------------- */

    /** Week day-pills (§2.4): tall pills for the week containing
     *  [selected]; selected = accent fill, today = accent ring, task
     *  dot underneath the number. */
    private fun v5DayPills(selected: String, onPick: (String) -> Unit): LinearLayout {
        val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL
        val monday = Stats.weekStart(selected)
        val letters = listOf("M", "T", "W", "T", "F", "S", "S")
        for (i in 0..6) {
            val d = Dates.addDays(monday, i)
            val isSel = d == selected
            val isToday = d == Dates.today()
            val cell = LinearLayout(this); cell.orientation = LinearLayout.VERTICAL
            cell.gravity = Gravity.CENTER
            cell.layoutParams = LinearLayout.LayoutParams(0, dp(68f), 1f).apply { if (i > 0) leftMargin = dp(6f) }
            cell.background = when {
                isSel -> roundedRect(V5.ACCENT, V5.R_DAYPILL)
                isToday -> roundedRect(V5.CARD, V5.R_DAYPILL, V5.ACCENT)
                else -> roundedRect(V5.CARD, V5.R_DAYPILL)
            }
            val lt = tv(letters[i], 11f, if (isSel) 0xFFFFFFFF.toInt() else V5.MUTED, true)
            lt.gravity = Gravity.CENTER
            cell.addView(lt)
            val num = tv(Dates.parse(d).get(java.util.Calendar.DAY_OF_MONTH).toString(), 18f,
                if (isSel) 0xFFFFFFFF.toInt() else V5.TEXT, true)
            num.gravity = Gravity.CENTER
            cell.addView(num)
            val has = Store.state.tasks.any { it.dueDate == d }
            val dot = View(this)
            dot.background = ovalDrawable(
                if (has) (if (isSel) 0xFFFFFFFF.toInt() else V5.FAINT) else android.graphics.Color.TRANSPARENT)
            dot.layoutParams = LinearLayout.LayoutParams(dp(4f), dp(4f)).apply { topMargin = dp(4f) }
            cell.addView(dot)
            val dd = d
            cell.setOnClickListener { Sfx.tick(); onPick(dd) }
            row.addView(cell)
        }
        return row
    }

    /** The 4 stat tiles (§2.5) for the selected day. */
    private fun v5StatTiles(day: String): LinearLayout {
        val today = Dates.today()
        val due = Store.state.tasks.filter { it.dueDate == day }
        val doneN = Stats.countOn(Store.state.completions, day)
        val urgent = due.count { it.priority == 1 } +
            if (day == today) Store.state.tasks.count { Dates.isOverdue(it) } else 0
        data class Tile(val g: String, val c: Int, val v: String, val l: String)
        val tiles = listOf(
            // §2.4: the Total well is tinted with ACCENT (the earlier
            // build used P3 blue — CHECK1 §3 P3).
            Tile("▦", V5.ACCENT, (due.size + doneN).toString(), "Total"),
            Tile("✓", V5.DONE, doneN.toString(), "Done"),
            Tile("◷", V5.P2, due.size.toString(), "Pending"),
            Tile("!", V5.ACCENT_BRAND, urgent.toString(), "Urgent")
        )
        val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL
        tiles.forEachIndexed { i, tile ->
            val cell = LinearLayout(this); cell.orientation = LinearLayout.VERTICAL
            cell.background = v5CardBg(V5.R_TILE)
            cell.setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            // Spec height is ~92dp; 96 is the floor that fits the
            // anatomy (10 + 28 well + 6 + 24sp number + 11sp label
            // + 10) without clipping the label.
            cell.layoutParams = LinearLayout.LayoutParams(0, dp(96f), 1f).apply { if (i > 0) leftMargin = dp(V5.TILE_GAP.toFloat()) }
            cell.addView(iconWell(tile.g, tile.c, 28f, 14f))
            val n = tv(tile.v, 24f, V5.TEXT, true)
            n.layoutParams = lp(t = dp(6f))
            cell.addView(n)
            cell.addView(tv(tile.l, 11f, V5.MUTED))
            row.addView(cell)
        }
        return row
    }

    /** Hero card (§2.7): accent-tint surface; label → Karma statement →
     *  points (muted suffix) → goal-fraction bar + big % → meta line;
     *  streak chip and round CTA on the right. */
    private fun v5Hero(): LinearLayout {
        val today = Dates.today()
        val comps = Store.state.completions
        val info = Karma.levelFor(Store.state.karmaPoints)
        val gp = Stats.goalProgress(comps, today, Store.state.dailyGoal, Store.state.weeklyGoal)
        val hero = LinearLayout(this); hero.orientation = LinearLayout.HORIZONTAL
        hero.background = roundedRect(V5.ACCENT_TINT, V5.R_HERO, V5.ACCENT_TINT_DEEP)
        hero.setPadding(dp(18f), dp(18f), dp(18f), dp(18f))
        hero.layoutParams = lp(t = dp(16f))
        val left = LinearLayout(this); left.orientation = LinearLayout.VERTICAL
        left.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        val cap = tv("TODAY'S PROGRESS", 11f, V5.ACCENT_BRAND, true)
        cap.letterSpacing = 0.08f
        left.addView(cap)
        val stmt = tv(info.name, 22f, V5.TEXT, true)
        stmt.layoutParams = lp(t = dp(6f))
        left.addView(stmt)
        val ptsStr = Store.state.karmaPoints.toString()
        val suffix = if (info.nextName != null) " pts · " + info.pointsToNext + " to " + info.nextName
            else " pts · top level"
        val span = android.text.SpannableString(ptsStr + suffix)
        span.setSpan(android.text.style.ForegroundColorSpan(V5.MUTED),
            ptsStr.length, ptsStr.length + suffix.length, 0)
        val pts = tv("", 13f, V5.TEXT, true)
        pts.text = span
        pts.layoutParams = lp(t = dp(2f))
        left.addView(pts)
        val frac = if (Store.state.dailyGoal > 0) gp.dailyFraction else info.progress
        val prow = LinearLayout(this); prow.orientation = LinearLayout.HORIZONTAL
        prow.gravity = Gravity.CENTER_VERTICAL
        prow.layoutParams = lp(t = dp(12f))
        val track = FrameLayout(this)
        track.background = roundedRect(android.graphics.Color.argb(26, 255, 255, 255), 999f)
        track.layoutParams = LinearLayout.LayoutParams(0, dp(6f), 1f)
        val fill = View(this)
        fill.background = roundedRect(V5.ACCENT, 999f)
        fill.layoutParams = FrameLayout.LayoutParams(0, -1)
        track.addView(fill)
        track.post {
            fill.layoutParams = FrameLayout.LayoutParams((track.width * frac).toInt().coerceAtLeast(0), -1)
            fill.requestLayout()
        }
        prow.addView(track)
        val pct = tv(Math.round(frac * 100).toString() + "%", 28f, V5.TEXT, true)
        pct.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12f) }
        prow.addView(pct)
        left.addView(prow)
        val meta = tv(
            if (Store.state.dailyGoal > 0) gp.dailyDone.toString() + " of " + Store.state.dailyGoal + " tasks done today"
            else "Level progress · set a daily goal in Productivity",
            12f, V5.MUTED)
        meta.layoutParams = lp(t = dp(8f))
        left.addView(meta)
        hero.addView(left)
        val right = LinearLayout(this); right.orientation = LinearLayout.VERTICAL
        right.gravity = Gravity.END
        right.layoutParams = LinearLayout.LayoutParams(-2, -1).apply { leftMargin = dp(12f) }
        val streak = Stats.currentStreak(comps, today)
        val chip = tv("🔥 " + streak + if (streak == 1) " day" else " days", 12f, V5.DONE, true)
        chip.background = roundedRect(V5.tint(V5.DONE, 38), 999f)
        chip.setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
        right.addView(chip)
        val spacer = View(this)
        spacer.layoutParams = LinearLayout.LayoutParams(1, 0, 1f)
        right.addView(spacer)
        val cta = tv("›", 22f, 0xFFFFFFFF.toInt(), true)
        cta.gravity = Gravity.CENTER
        cta.background = ovalDrawable(V5.ACCENT)
        cta.layoutParams = LinearLayout.LayoutParams(dp(40f), dp(40f))
        cta.contentDescription = "Open Productivity"
        cta.setOnClickListener { Sfx.nav(); sub = "stats"; render() }
        right.addView(cta)
        hero.addView(right)
        return hero
    }

    /** Quick Add ghost pill (§2.2): CARD fill, HAIRLINE, full pill,
     *  "+" in ACCENT + "Add new task" in PLACEHOLDER. Shared by Today
     *  and Inbox (both are capture surfaces). Caller sets the height /
     *  margins via layoutParams. */
    private fun v5GhostPill(): LinearLayout {
        val ghost = LinearLayout(this); ghost.orientation = LinearLayout.HORIZONTAL
        ghost.gravity = Gravity.CENTER_VERTICAL
        ghost.background = roundedRect(V5.CARD, 999f, V5.HAIRLINE)
        ghost.setPadding(dp(18f), 0, dp(18f), 0)
        ghost.addView(tv("+", 20f, V5.ACCENT, true))
        val gh = tv("Add new task", 14f, V5.PLACEHOLDER)
        gh.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10f) }
        ghost.addView(gh)
        ghost.contentDescription = "Add new task"
        ghost.setOnClickListener { openQuickAdd(null) }
        return ghost
    }

    private fun renderTodayV5() {
        val today = Dates.today()
        // Greeting header (§2.1): greeting + name left; grouped icon
        // pill and the accent "S" medallion right.
        val head = LinearLayout(this); head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val greet = if (h < 12) "Good morning" else if (h < 17) "Good afternoon" else "Good evening"
        val gcol = LinearLayout(this); gcol.orientation = LinearLayout.VERTICAL
        gcol.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        gcol.addView(tv(greet + ",", 12f, V5.MUTED))
        val name = tv("Sir", 30f, V5.TEXT, true)
        name.layoutParams = lp(t = dp(2f))
        gcol.addView(name)
        head.addView(gcol)
        val pill = LinearLayout(this); pill.orientation = LinearLayout.HORIZONTAL
        pill.gravity = Gravity.CENTER
        pill.background = roundedRect(V5.POPUP, 999f, V5.HAIRLINE)
        pill.layoutParams = LinearLayout.LayoutParams(-2, dp(40f))
        val sI = tv("⌕", 19f, V5.TEXT)
        sI.gravity = Gravity.CENTER
        sI.layoutParams = LinearLayout.LayoutParams(dp(40f), dp(40f))
        sI.contentDescription = "Search"
        sI.setOnClickListener { sub = "search"; render() }
        val oI = tv("⋮", 19f, V5.TEXT, true)
        oI.gravity = Gravity.CENTER
        oI.layoutParams = LinearLayout.LayoutParams(dp(40f), dp(40f))
        oI.contentDescription = "More options"
        oI.setOnClickListener { showOverflowMenu(it) }
        pill.addView(sI); pill.addView(oI)
        val med = tv("S", 17f, V5.ACCENT_BRAND, true)
        med.gravity = Gravity.CENTER
        med.background = ovalDrawable(V5.ACCENT_TINT)
        med.layoutParams = LinearLayout.LayoutParams(dp(40f), dp(40f)).apply { rightMargin = dp(10f) }
        // §2.1 order: the medallion sits to the LEFT of the grouped pill.
        head.addView(med)
        head.addView(pill)
        content.addView(head)

        // Quick Add ghost pill (§2.2) — no floating FAB anywhere in v5.
        val ghost = v5GhostPill()
        ghost.layoutParams = lp(h = dp(52f), t = dp(16f))
        content.addView(ghost)

        val pills = v5DayPills(v5DaySel) { d -> v5DaySel = d; render() }
        pills.layoutParams = lp(t = dp(20f))
        content.addView(pills)

        val tiles = v5StatTiles(v5DaySel)
        tiles.layoutParams = lp(t = dp(16f))
        content.addView(tiles)

        content.addView(v5Hero())

        val isToday = v5DaySel == today
        val key = displayKey()
        val overdue = if (isToday) applyDisplay(Store.state.tasks.filter { Dates.isOverdue(it) }, key)
            else emptyList()
        val dayTasks = applyDisplay(Store.state.tasks.filter { it.dueDate == v5DaySel }, key)
        // Section header (§3.1.6): title + task count in FAINT + the
        // Display icon circle.
        val planHead = LinearLayout(this); planHead.orientation = LinearLayout.HORIZONTAL
        planHead.gravity = Gravity.CENTER_VERTICAL
        planHead.layoutParams = lp(t = dp(24f), b = dp(12f))
        val pt = tv(if (isToday) "Today's Plan" else fmtDayLong(v5DaySel), 20f, V5.TEXT, true)
        pt.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        planHead.addView(pt)
        val cnt = tv((overdue.size + dayTasks.size).toString(), 15f, V5.FAINT, true)
        cnt.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(10f) }
        planHead.addView(cnt)
        val dI = tv("▤", 18f, V5.TEXT)
        dI.gravity = Gravity.CENTER
        dI.background = roundedRect(V5.POPUP, 999f)
        dI.layoutParams = LinearLayout.LayoutParams(dp(36f), dp(36f))
        dI.contentDescription = "Display"
        dI.setOnClickListener { showDisplaySheet() }
        planHead.addView(dI)
        content.addView(planHead)

        if (overdue.isNotEmpty()) {
            val ol = tv("Overdue", 12f, V5.OVERDUE, true)
            ol.layoutParams = lp(b = dp(8f))
            content.addView(ol)
            content.addView(v5CardList(overdue, showProject = true, swipe = true, drag = false))
        }
        if (dayTasks.isEmpty()) {
            // "All clear" only when there is genuinely nothing on the
            // day — an overdue group above already fills the plan.
            if (overdue.isEmpty())
                content.addView(v5EmptyState("✓", "All clear", "Nothing due this day. Enjoy the space."))
        } else {
            content.addView(v5CardList(dayTasks, showProject = true, swipe = true, drag = true))
        }
        if (dispShowFor(key)) completedGroup(v5DaySel)
    }

    /* ---------------- v5 task cards + swipe + drag ---------------- */

    /** Meta row under the card title (§2.6): label chip + "+n", due
     *  chip in semantic colour, recurrence/reminder glyphs, project
     *  name on cross-project lists. */
    private fun v5MetaRow(t: TaskItem, showProject: Boolean): LinearLayout? {
        val parts = mutableListOf<View>()
        if (t.labelIds.isNotEmpty()) {
            val l0 = Store.state.labels.find { it.id == t.labelIds[0] }
            if (l0 != null) {
                val chip = tv("🏷 " + l0.name, 11f, V5.MUTED)
                chip.background = roundedRect(android.graphics.Color.argb(15, 255, 255, 255), 999f)
                chip.setPadding(dp(8f), dp(3f), dp(8f), dp(3f))
                parts.add(chip)
                if (t.labelIds.size > 1) {
                    // §2.6.5: the "+n" extra-labels indicator is a
                    // second chip, not bare text.
                    val more = tv("+" + (t.labelIds.size - 1), 11f, V5.MUTED)
                    more.background = roundedRect(android.graphics.Color.argb(15, 255, 255, 255), 999f)
                    more.setPadding(dp(8f), dp(3f), dp(8f), dp(3f))
                    parts.add(more)
                }
            }
        }
        if (t.dueDate != null) {
            val seg = tv("▦ " + dateWordFor(t) + if (t.dueTime != null) " " + t.dueTime else "",
                12f, V5.dateColor(t.dueDate))
            parts.add(seg)
        }
        if (t.recurrence != null) parts.add(tv("↻", 12f, V5.FAINT))
        if (t.reminderOffsetMin != null) parts.add(tv("🔔", 11f, V5.FAINT))
        if (showProject && t.projectId != null) {
            val pn = Store.state.projects.find { it.id == t.projectId }?.name
            if (pn != null) parts.add(tv(pn, 11f, V5.FAINT))
        }
        if (parts.isEmpty()) return null
        val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        parts.forEachIndexed { i, v ->
            if (i > 0) v.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10f) }
            row.addView(v)
        }
        return row
    }

    /** The v5 task card (§2.6): project-colour left bar, priority-ring
     *  checkbox, title with the reserved star slot + "!" badge, meta
     *  row, subtask progress at the bottom. Fully OPAQUE (ruling 2).
     *  With [swipe] the card is wrapped in a SwipeRow. */
    private fun v5TaskCard(t: TaskItem, showProject: Boolean, compact: Boolean = false, swipe: Boolean = false): View {
        val card = LinearLayout(this); card.orientation = LinearLayout.HORIZONTAL
        card.background = v5CardBg()
        card.setPadding(dp(16f), dp(14f), dp(14f), dp(14f))
        card.gravity = Gravity.TOP
        val barColor = if (t.projectId == null) V5.FAINT else try {
            android.graphics.Color.parseColor(
                Store.state.projects.find { it.id == t.projectId }?.color ?: "#9e9ea8")
        } catch (e: Exception) { V5.FAINT }
        val bar = View(this)
        bar.background = roundedRect(barColor, 2f)
        bar.layoutParams = LinearLayout.LayoutParams(dp(4f), -1).apply { rightMargin = dp(12f) }
        card.addView(bar)
        val check = tv("", 12f, 0xFFFFFFFF.toInt(), true)
        check.gravity = Gravity.CENTER
        check.layoutParams = LinearLayout.LayoutParams(dp(24f), dp(24f))
        if (selectMode) {
            val isSel = t.id in selectedIds
            check.text = if (isSel) "✓" else ""
            check.background = if (isSel) ovalDrawable(V5.ACCENT) else {
                val g = android.graphics.drawable.GradientDrawable()
                g.shape = android.graphics.drawable.GradientDrawable.OVAL
                g.setColor(android.graphics.Color.TRANSPARENT)
                g.setStroke(dp(2f), V5.FAINT)
                g
            }
            check.contentDescription = "Select task"
            check.setOnClickListener { toggleSelected(t.id) }
        } else {
            check.background = Glass.checkCircle(this, t.priority, false)
            check.contentDescription = "Complete task"
            check.setOnClickListener { v5CompleteAnim(t, check) }
        }
        card.addView(check)
        val body = LinearLayout(this); body.orientation = LinearLayout.VERTICAL
        body.layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12f) }
        val titleRow = LinearLayout(this); titleRow.orientation = LinearLayout.HORIZONTAL
        titleRow.gravity = Gravity.CENTER_VERTICAL
        val title = tv(t.title, 16f, V5.TEXT, true)
        title.maxLines = 2
        title.ellipsize = android.text.TextUtils.TruncateAt.END
        title.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        titleRow.addView(title)
        // Star slot: fixed 24dp at the right edge, BEFORE the due chip
        // zone (ruling 4); empty when unstarred so titles never shift.
        // Filled star = P2 amber (§2.6.7), not gold.
        val star = tv(if (t.starred) "★" else "", 17f, V5.P2)
        star.gravity = Gravity.CENTER
        star.layoutParams = LinearLayout.LayoutParams(dp(24f), dp(24f))
        star.contentDescription = "Toggle star"
        star.setOnClickListener { Store.toggleStar(t.id); Sfx.tick(); render() }
        titleRow.addView(star)
        if (t.priority < 4) {
            val badge = tv("!", 11f, 0xFFFFFFFF.toInt(), true)
            badge.gravity = Gravity.CENTER
            badge.background = ovalDrawable(priorityColor(t.priority))
            badge.layoutParams = LinearLayout.LayoutParams(dp(18f), dp(18f))
            titleRow.addView(badge)
        }
        body.addView(titleRow)
        if (compact && t.dueDate != null) {
            // §2.6 compact variant: description and the full meta row
            // are omitted, but the due chip survives on its own.
            val seg = tv("▦ " + dateWordFor(t) + if (t.dueTime != null) " " + t.dueTime else "",
                12f, V5.dateColor(t.dueDate))
            seg.layoutParams = lp(t = dp(6f))
            body.addView(seg)
        }
        if (!compact && t.notes.isNotBlank()) {
            val d = tv(t.notes.trim().lineSequence().firstOrNull() ?: "", 12f, V5.MUTED)
            d.maxLines = 1
            d.ellipsize = android.text.TextUtils.TruncateAt.END
            d.layoutParams = lp(t = dp(3f))
            body.addView(d)
        }
        if (!compact) {
            val meta = v5MetaRow(t, showProject)
            if (meta != null) {
                meta.layoutParams = lp(t = dp(8f))
                body.addView(meta)
            }
            if (t.subtasks.isNotEmpty()) {
                val doneN = t.subtasks.count { it.done }
                val srow = LinearLayout(this); srow.orientation = LinearLayout.HORIZONTAL
                srow.gravity = Gravity.CENTER_VERTICAL
                srow.layoutParams = lp(t = dp(10f))
                val track = FrameLayout(this)
                track.background = roundedRect(V5.TRACK, 999f)
                track.layoutParams = LinearLayout.LayoutParams(0, dp(3f), 1f)
                val fillv = View(this)
                fillv.background = roundedRect(V5.ACCENT, 999f)
                fillv.layoutParams = FrameLayout.LayoutParams(0, -1)
                track.addView(fillv)
                val frac = doneN.toFloat() / t.subtasks.size
                track.post {
                    fillv.layoutParams = FrameLayout.LayoutParams((track.width * frac).toInt().coerceAtLeast(0), -1)
                    fillv.requestLayout()
                }
                srow.addView(track)
                val cnt = tv("☰ $doneN/${t.subtasks.size}", 11f, V5.FAINT)
                cnt.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8f) }
                srow.addView(cnt)
                body.addView(srow)
            }
        }
        body.setOnClickListener { if (selectMode) toggleSelected(t.id) else openEditor(t.id) }
        card.addView(body)
        if (!swipe || selectMode) return card
        val sr = SwipeRow(this)
        sr.tag = t.id
        sr.setCompleteReveal("✓", V5.DONE)
        // Panel order from the screen edge inward: Delete (far edge),
        // Focus, Date, Skip, Star (SHADOW-THREE §2). Delete is the P1
        // family at 85% (CHECK1 §7.6); every tile carries its label
        // under the glyph; the star glyph is P2 amber like the card's.
        sr.setActions(listOf(
            SwipeRow.Action("delete", "🗑", V5.tint(V5.P1, 217), 0xFFFFFFFF.toInt(), "Delete"),
            SwipeRow.Action("focus", "⏳", V5.POPUP, V5.TEXT, "Focus"),
            SwipeRow.Action("date", "▦", V5.POPUP, V5.TEXT, "Date"),
            SwipeRow.Action("skip", "⤵", V5.POPUP, V5.TEXT, "Skip"),
            SwipeRow.Action("star", "★", V5.POPUP, V5.P2, "Star")
        ))
        sr.onComplete = { swipeComplete(t) }
        sr.onAction = { id -> swipeAction(t, id) }
        sr.setContent(card)
        return sr
    }

    /** Checkbox completion with the v5 feedback: green fill + ✓
     *  (Glass.checkCircle's v5 filled state), then the shared flow. */
    private fun v5CompleteAnim(t: TaskItem, check: TextView) {
        check.isEnabled = false
        check.background = Glass.checkCircle(this, t.priority, true)
        check.text = "✓"
        Sfx.complete()
        vibrateOnce()
        handler.postDelayed({
            val msg = Store.completeTask(t.id)
            if (msg.isNotEmpty()) toast(msg)
            render()
        }, 260)
    }

    private fun swipeComplete(t: TaskItem) {
        Sfx.complete()
        val msg = Store.completeTask(t.id)
        if (msg.isNotEmpty()) toast(msg)
        render()
    }

    private fun swipeAction(t: TaskItem, id: String) {
        when (id) {
            "star" -> { Store.toggleStar(t.id); Sfx.tick(); render() }
            "skip" -> { Store.skipTask(t.id); Sfx.tick(); toast("Skipped — moved one day out"); render() }
            "date" -> {
                taskDateEditId = t.id
                qaPickingDeadline = false
                qaDate = t.dueDate
                qaTime = t.dueTime
                // Preload the task's own recurrence so the sheet shows
                // (and Save preserves) its real repeat instead of a
                // blank "Repeat" that silently dropped it.
                qaRecurrence = t.recurrence
                qaRecSet = t.recurrence != null
                showSheet(buildQaDatePicker())
            }
            "focus" -> { focusTaskId = t.id; sub = "focus"; render() }
            "delete" -> {
                val idx = Store.state.tasks.indexOfFirst { it.id == t.id }
                val removed = Store.deleteTask(t.id)
                render()
                if (removed != null) showUndo(removed, idx)
            }
        }
    }

    /** A vertical stack of v5 cards with CARD_GAP spacing — the v5
     *  replacement for the grouped container in every list. */
    private fun v5CardList(tasks: List<TaskItem>, showProject: Boolean, swipe: Boolean = true, drag: Boolean = false): LinearLayout {
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
        col.layoutParams = lp(b = dp(4f))
        tasks.forEach { t ->
            val v = v5TaskCard(t, showProject, swipe = swipe)
            v.layoutParams = lp(b = dp(V5.CARD_GAP.toFloat()))
            col.addView(v)
            if (drag && v is SwipeRow) wireDrag(v, col)
        }
        return col
    }

    /** Long-press drag-sort wiring (SHADOW-THREE §2): the SwipeRow
     *  detects the press and the vertical drag; here we swap neighbours
     *  live and persist manualOrder on drop. */
    private fun wireDrag(sr: SwipeRow, container: LinearLayout) {
        sr.dragEnabled = true
        sr.onDragStart = {
            Sfx.hold()
            sr.elevation = dp(8f).toFloat()
            sr.animate().scaleX(1.02f).scaleY(1.02f).setDuration(120).start()
            vibrateOnce()
        }
        sr.onDragMove = { ty -> dragSwap(container, sr, ty) }
        sr.onDragEnd = {
            sr.elevation = 0f
            sr.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            var idx = 0
            for (i in 0 until container.childCount) {
                val id = container.getChildAt(i).tag as? String ?: continue
                Store.byId(id)?.manualOrder = idx * 10
                idx++
            }
            Store.save()
        }
    }

    private fun dragSwap(container: LinearLayout, sr: SwipeRow, ty: Float) {
        val rowH = sr.height + dp(V5.CARD_GAP.toFloat())
        if (rowH <= 0) return
        val idx = container.indexOfChild(sr)
        if (ty > rowH / 2f && idx >= 0 && idx < container.childCount - 1) {
            container.removeView(sr)
            container.addView(sr, idx + 1)
            sr.dragBase -= rowH
            sr.translationY = ty - rowH
        } else if (ty < -rowH / 2f && idx > 0) {
            container.removeView(sr)
            container.addView(sr, idx - 1)
            sr.dragBase += rowH
            sr.translationY = ty + rowH
        }
    }

    /* ---------------- v5 modular dock ---------------- */

    /** glyph, label, isSubScreen for a dock module id. */
    private fun dockModuleMeta(id: String): Triple<String, String, Boolean> = when (id) {
        "today" -> Triple(java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_MONTH).toString(), "Today", false)
        "upcoming" -> Triple("▦", "Upcoming", false)
        "calendar" -> Triple("▤", "Calendar", true)
        "habits" -> Triple("🔥", "Habits", true)
        "focus" -> Triple("◎", "Focus", true)
        "stats" -> Triple("↗", "Stats", true)
        "matrix" -> Triple("⊞", "Matrix", true)
        "countdowns" -> Triple("⏳", "Countdowns", true)
        "browse" -> Triple("☰", "Browse", false)
        else -> Triple("•", id, true)
    }

    /** The floating pill dock (§2.8 + SHADOW-THREE §7 as amended):
     *  up to 5 configured modules with the FIXED Quick Add "+" inserted
     *  at the centre; active module gets the accent-tint-deep pill. */
    private fun renderDock() {
        val mods = Store.dockModules()
        val cells = mutableListOf<String?>()
        val insertAt = (mods.size + 1) / 2
        mods.forEachIndexed { i, m ->
            if (i == insertAt) cells.add(null)
            cells.add(m)
        }
        if (insertAt >= mods.size) cells.add(null)
        cells.forEach { m ->
            if (m == null) {
                val wrap = LinearLayout(this); wrap.orientation = LinearLayout.VERTICAL
                wrap.gravity = Gravity.CENTER
                wrap.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                val add = tv("+", 24f, 0xFFFFFFFF.toInt(), true)
                add.gravity = Gravity.CENTER
                add.background = ovalDrawable(V5.ACCENT)
                add.layoutParams = LinearLayout.LayoutParams(dp(48f), dp(48f))
                add.contentDescription = "Add task"
                add.setOnClickListener { openQuickAdd(null) }
                wrap.addView(add)
                navBar.addView(wrap)
            } else {
                val (glyph, lbl, isSub) = dockModuleMeta(m)
                val on = if (isSub) sub == m else (sub == null && view == m)
                val cellL = LinearLayout(this); cellL.orientation = LinearLayout.VERTICAL
                cellL.gravity = Gravity.CENTER_HORIZONTAL
                cellL.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                cellL.setPadding(dp(2f), dp(4f), dp(2f), dp(4f))
                if (on) cellL.background = roundedRect(V5.ACCENT_TINT_DEEP, 999f)
                val ic = tv(glyph, if (m == "today") 14f else 18f, if (on) V5.ACCENT_BRAND else V5.FAINT, m == "today")
                ic.gravity = Gravity.CENTER
                cellL.addView(ic)
                val lb = tv(lbl, 10f, if (on) V5.TEXT else V5.FAINT)
                lb.gravity = Gravity.CENTER
                lb.layoutParams = lp(t = dp(2f))
                cellL.addView(lb)
                cellL.contentDescription = lbl
                cellL.setOnClickListener {
                    Sfx.nav()
                    if (isSub) sub = m else { view = m; sub = null }
                    render()
                }
                navBar.addView(cellL)
            }
        }
    }

    private fun dockSettingRow(labelText: String, actions: List<Triple<String, Boolean, () -> Unit>>): LinearLayout {
        val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(16f), dp(12f), dp(10f), dp(12f))
        val t = tv(labelText, 15f)
        t.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        row.addView(t)
        actions.forEach { (g, enabled, act) ->
            val b = tv(g, 16f, if (enabled) V5.TEXT else V5.FAINT, true)
            b.gravity = Gravity.CENTER
            b.background = roundedRect(V5.POPUP, 999f)
            b.layoutParams = LinearLayout.LayoutParams(dp(34f), dp(34f)).apply { leftMargin = dp(6f) }
            b.alpha = if (enabled) 1f else 0.4f
            if (enabled) b.setOnClickListener { act() }
            row.addView(b)
        }
        return row
    }

    /** Settings → Dock (SHADOW-THREE §7): reorder/remove the modules in
     *  the dock, add from the catalogue. Applies immediately. */
    private fun renderDockSettings() {
        content.addView(tv("The dock holds up to 5 modules. Quick Add (+) is fixed in the centre, and Today can't be removed.", 13f, V5.MUTED))
        content.addView(dayGroup("IN DOCK"))
        val inDock = Store.dockModules()
        val inRows = mutableListOf<View>()
        inDock.forEachIndexed { i, m ->
            val (_, lbl, _) = dockModuleMeta(m)
            inRows.add(dockSettingRow(lbl, listOf(
                Triple("↑", i > 0) {
                    val cur = Store.dockModules().toMutableList()
                    val tmp = cur[i]; cur[i] = cur[i - 1]; cur[i - 1] = tmp
                    Store.setDockModules(cur); render()
                },
                Triple("↓", i < inDock.size - 1) {
                    val cur = Store.dockModules().toMutableList()
                    val tmp = cur[i]; cur[i] = cur[i + 1]; cur[i + 1] = tmp
                    Store.setDockModules(cur); render()
                },
                Triple("−", m != "today") {
                    val cur = Store.dockModules().toMutableList()
                    cur.remove(m)
                    Store.setDockModules(cur); render()
                }
            )))
        }
        content.addView(groupedMenuCard(inRows))
        content.addView(dayGroup("MORE MODULES"))
        val full = inDock.size >= 5
        val moreRows = mutableListOf<View>()
        Store.DOCK_MODULE_IDS.filter { it !in inDock }.forEach { m ->
            val (_, lbl, _) = dockModuleMeta(m)
            moreRows.add(dockSettingRow(lbl, listOf(
                Triple("+", !full) {
                    val cur = Store.dockModules().toMutableList()
                    cur.add(m)
                    Store.setDockModules(cur); render()
                }
            )))
        }
        if (moreRows.isNotEmpty()) content.addView(groupedMenuCard(moreRows))
        if (full) {
            val cap = tv("Dock is full — remove one first.", 12f, V5.MUTED)
            cap.layoutParams = lp(t = dp(6f))
            content.addView(cap)
        }
    }

    /* ---------------- v5 Habits (SHADOW-THREE §1) ---------------- */

    private val HF_COLORS = listOf(
        "#d9a441", "#e44332", "#ff9f0a", "#3ecf6f", "#34c759", "#4a90d9",
        "#bf5af2", "#ff4d7e", "#2fb457", "#00c8ff", "#ffd60a", "#9e9ea8")
    private val HF_ICONS = listOf(
        "drop", "sparkle", "droplets", "moon", "sun", "pill", "pause", "apple",
        "book", "pan", "broom", "bed", "phone", "bookmark", "dumbbell", "steps",
        "stretch", "wave", "lotus", "bike", "hands", "pen", "phone-off", "video",
        "lotus-mind", "circle")

    private fun habitGlyph(icon: String): String = when (icon) {
        "drop" -> "💧"; "sparkle" -> "✨"; "droplets" -> "🚿"; "moon" -> "🌙"
        "sun" -> "☀️"; "pill" -> "💊"; "pause" -> "⏸"; "apple" -> "🍎"
        "book" -> "📖"; "pan" -> "🍳"; "broom" -> "🧹"; "bed" -> "🛏"
        "phone" -> "📞"; "bookmark" -> "🔖"; "dumbbell" -> "🏋"; "steps" -> "🚶"
        "stretch" -> "🤸"; "wave" -> "🏊"; "lotus", "lotus-mind" -> "🧘"
        "bike" -> "🚴"; "hands" -> "🙏"; "pen" -> "✍"; "phone-off" -> "📵"
        "video" -> "🎬"
        else -> "●"
    }

    private fun habitColor(hex: String): Int =
        try { android.graphics.Color.parseColor(hex) } catch (e: Exception) { 0xFFD9A441.toInt() }

    private fun habitFromTemplate(tpl: HabitTemplate): HabitItem = HabitItem(
        id = uid(), name = tpl.name, description = tpl.tagline, icon = tpl.icon,
        color = tpl.color, kind = tpl.kind, libraryKey = tpl.key)

    private fun renderHabits() {
        val today = Dates.today()
        val active = Store.state.habits.filter { !it.archived }
        // Score pill + Library + new-habit actions.
        val top = LinearLayout(this); top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        var denom = 0
        active.forEach { hb ->
            // Same existence bound HabitLogic.score applies, so the
            // "New" gate never disagrees with the score it labels.
            val startBound = HabitLogic.existenceStart(hb, Store.state.habitLogs, today)
            for (b in 0 until 28) {
                val d = Dates.addDays(today, -b)
                if (d < startBound) continue
                if (hb.freqType == "period" || HabitLogic.isDueOn(hb, d)) denom++
            }
        }
        val pill = tv(if (denom == 0) "◔ New" else "◔ " + Store.habitScore().toString() + "%", 14f, V5.TEXT, true)
        pill.background = roundedRect(V5.POPUP, 999f, V5.HAIRLINE)
        pill.setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
        pill.contentDescription = "Habit Score"
        top.addView(pill)
        val sp = View(this)
        sp.layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        top.addView(sp)
        top.addView(ghostBtn("Library") { habitLibQuery = ""; showSheet(buildHabitLibrary()) })
        val add = tv("+", 20f, V5.TEXT, true)
        add.gravity = Gravity.CENTER
        add.background = roundedRect(V5.POPUP, 999f)
        add.layoutParams = LinearLayout.LayoutParams(dp(38f), dp(38f)).apply { leftMargin = dp(8f) }
        add.contentDescription = "New habit"
        add.setOnClickListener { startHabitForm(null) }
        top.addView(add)
        content.addView(top)
        val cap = tv(
            if (denom == 0) "Pick a habit from the Library or add your own — streaks start with one tick."
            else "Habit Score · last 28 days",
            12f, V5.MUTED)
        cap.layoutParams = lp(t = dp(8f))
        content.addView(cap)

        renderMustsStrip()

        val chips = LinearLayout(this); chips.orientation = LinearLayout.HORIZONTAL
        chips.layoutParams = lp(t = dp(16f))
        val c1 = chipBtn("All (" + active.size + ")", !habitFilterDue) { habitFilterDue = false; render() }
        c1.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6f) }
        val c2 = chipBtn("Due (" + Store.habitDueCount(today) + ")", habitFilterDue) { habitFilterDue = true; render() }
        chips.addView(c1); chips.addView(c2)
        content.addView(chips)

        val pills = v5DayPills(habitDaySel) { d -> habitDaySel = d; render() }
        pills.layoutParams = lp(t = dp(14f))
        content.addView(pills)
        if (habitDaySel != today) {
            val note = tv("Viewing " + fmtDayLong(habitDaySel) + " — history is read-only.", 12f, V5.MUTED)
            note.layoutParams = lp(t = dp(8f))
            content.addView(note)
        }

        var shown = active.sortedBy { it.order }
        if (habitFilterDue) shown = shown.filter { HabitLogic.isDueOn(it, today) }
        if (active.isEmpty()) {
            content.addView(v5EmptyState("🔥", "No habits yet", "Add one from the Library or create your own."))
            val libBtn = accentBtn("Browse the Library") { habitLibQuery = ""; showSheet(buildHabitLibrary()) }
            libBtn.layoutParams = lp(t = dp(8f))
            content.addView(libBtn)
        } else if (shown.isEmpty()) {
            val none = tv("Nothing due today.", 13f, V5.MUTED)
            none.layoutParams = lp(t = dp(16f))
            content.addView(none)
        } else {
            shown.forEach { hb ->
                val c = v5HabitCard(hb, habitDaySel)
                c.layoutParams = lp(t = dp(12f))
                content.addView(c)
            }
        }

        val arch = Store.state.habits.filter { it.archived }
        if (arch.isNotEmpty()) {
            content.addView(dayGroup("ARCHIVED"))
            content.addView(groupedMenuCard(arch.map { hb ->
                browseRow(habitGlyph(hb.icon), habitColor(hb.color), hb.name, null) { sub = "habit:" + hb.id; render() }
            }))
        }
    }

    /** The "Your Daily Musts" adopt strip (§1.8): shown only while any
     *  of the four Musts is unadopted; one tap adopts with no sheet.
     *  Reminder times start EMPTY — never invented. */
    private fun renderMustsStrip() {
        data class MustDef(val title: String, val key: String)
        val defs = listOf(
            MustDef("Meditation", "meditation"),
            MustDef("Workout", "go-exercising"),
            MustDef("Write 1 YouTube Script", "must:script"),
            MustDef("Make 1 AI Video", "must:video")
        )
        val pending = defs.filter { d -> Store.state.habits.none { it.libraryKey == d.key } }
        if (pending.isEmpty()) return
        content.addView(sectionHeader("Your Daily Musts"))
        pending.forEach { d ->
            val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.background = v5CardBg(18f)
            row.setPadding(dp(14f), dp(12f), dp(12f), dp(12f))
            row.layoutParams = lp(b = dp(10f))
            val name = tv(d.title, 15f, V5.TEXT, true)
            name.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            row.addView(name)
            val addB = tv("Add", 13f, 0xFFFFFFFF.toInt(), true)
            addB.gravity = Gravity.CENTER
            addB.background = roundedRect(V5.ACCENT, 999f)
            addB.setPadding(dp(14f), dp(7f), dp(14f), dp(7f))
            addB.setOnClickListener {
                val hb = when (d.key) {
                    "meditation" -> habitFromTemplate(HabitLibrary.byKey("meditation")!!)
                    "go-exercising" -> habitFromTemplate(HabitLibrary.byKey("go-exercising")!!)
                    "must:script" -> HabitItem(
                        id = uid(), name = "Write 1 YouTube Script", icon = "pen", color = "#ff9f0a",
                        tracking = "count", targetCount = 1, unit = "script", libraryKey = "must:script")
                    else -> HabitItem(
                        id = uid(), name = "Make 1 AI Video", icon = "video", color = "#bf5af2",
                        tracking = "count", targetCount = 1, unit = "video", libraryKey = "must:video")
                }
                Store.addHabit(hb)
                Sfx.add()
                toast("Added to Habits")
                render()
            }
            row.addView(addB)
            content.addView(row)
        }
    }

    /** The habit card (§1.3): its OWN component — header row, year
     *  tick-grid body, tracking strip for count/checklist habits. */
    private fun v5HabitCard(h: HabitItem, day: String): LinearLayout {
        val today = Dates.today()
        val editable = day == today
        val color = habitColor(h.color)
        val cardL = LinearLayout(this); cardL.orientation = LinearLayout.VERTICAL
        cardL.background = v5CardBg()
        cardL.setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
        val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val well = iconWell(habitGlyph(h.icon), color, 40f, 19f)
        well.background = roundedRect(V5.tint(color, 38), V5.R_ICONSQ)
        row.addView(well)
        val colT = LinearLayout(this); colT.orientation = LinearLayout.VERTICAL
        colT.layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12f) }
        colT.addView(tv(h.name, 16f, V5.TEXT, true))
        val streak = HabitLogic.streak(h, Store.state.habitLogs, today)
        val sub2 = tv((if (h.kind == "quit") "Clean: " else "Streak: ") + streak +
            if (streak == 1) " day" else " days", 12f, V5.MUTED)
        sub2.layoutParams = lp(t = dp(2f))
        colT.addView(sub2)
        row.addView(colT)
        val det = tv("›", 20f, V5.MUTED, true)
        det.gravity = Gravity.CENTER
        det.background = roundedRect(V5.POPUP, 999f)
        det.layoutParams = LinearLayout.LayoutParams(dp(32f), dp(32f)).apply { rightMargin = dp(8f) }
        det.contentDescription = "Habit details"
        det.setOnClickListener { sub = "habit:" + h.id; render() }
        row.addView(det)
        if (h.tracking == "tick") {
            val doneToday = HabitLogic.isDoneOn(h, Store.state.habitLogs, day)
            val tick = tv("✓", 18f, if (doneToday) 0xFFFFFFFF.toInt() else color, true)
            tick.gravity = Gravity.CENTER
            tick.background = if (doneToday) ovalDrawable(color) else {
                val g = android.graphics.drawable.GradientDrawable()
                g.shape = android.graphics.drawable.GradientDrawable.OVAL
                g.setColor(android.graphics.Color.TRANSPARENT)
                g.setStroke(dp(2f), color)
                g
            }
            tick.layoutParams = LinearLayout.LayoutParams(dp(44f), dp(44f))
            tick.contentDescription = if (h.kind == "quit") "Stayed clean today" else "Tick today"
            if (editable) tick.setOnClickListener {
                val nowDone = !HabitLogic.isDoneOn(h, Store.state.habitLogs, day)
                Store.habitSetTick(h.id, day, nowDone)
                if (nowDone) Sfx.complete() else Sfx.tick()
                tick.animate().scaleX(0.85f).scaleY(0.85f).setDuration(75).withEndAction {
                    tick.animate().scaleX(1f).scaleY(1f).setDuration(75).start()
                    render()
                }.start()
            }
            row.addView(tick)
        }
        cardL.addView(row)
        val grid = YearGridView(this)
        grid.cells = HabitLogic.gridCells(h, Store.state.habitLogs, today)
        grid.colorHex = h.color
        grid.layoutParams = lp(t = dp(12f))
        cardL.addView(grid)
        if (h.tracking == "count") {
            val cnt = HabitLogic.logFor(h, Store.state.habitLogs, day)?.count ?: 0
            val srow = LinearLayout(this); srow.orientation = LinearLayout.HORIZONTAL
            srow.gravity = Gravity.CENTER_VERTICAL
            srow.layoutParams = lp(t = dp(12f))
            val ct = tv(cnt.toString() + " / " + h.targetCount + if (h.unit.isNotBlank()) " " + h.unit else "", 14f, V5.TEXT, true)
            ct.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            srow.addView(ct)
            if (editable) {
                val minus = tv("−", 18f, V5.TEXT, true)
                minus.gravity = Gravity.CENTER
                minus.background = roundedRect(V5.POPUP, 999f)
                minus.layoutParams = LinearLayout.LayoutParams(dp(32f), dp(32f)).apply { rightMargin = dp(8f) }
                minus.setOnClickListener { Store.habitAddCount(h.id, day, -1); Sfx.tick(); render() }
                val plus = tv("+", 18f, V5.TEXT, true)
                plus.gravity = Gravity.CENTER
                plus.background = roundedRect(V5.POPUP, 999f)
                plus.layoutParams = LinearLayout.LayoutParams(dp(32f), dp(32f))
                plus.setOnClickListener { Store.habitAddCount(h.id, day, 1); Sfx.tick(); render() }
                srow.addView(minus); srow.addView(plus)
            }
            cardL.addView(srow)
        }
        if (h.tracking == "checklist") {
            val log = HabitLogic.logFor(h, Store.state.habitLogs, day)
            val doneItems = log?.doneItems ?: emptyList()
            val doneN = h.checklist.count { it.id in doneItems }
            val srow = LinearLayout(this); srow.orientation = LinearLayout.HORIZONTAL
            srow.gravity = Gravity.CENTER_VERTICAL
            srow.layoutParams = lp(t = dp(12f))
            val ct = tv(doneN.toString() + " of " + h.checklist.size + " done", 14f, V5.TEXT, true)
            ct.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            srow.addView(ct)
            val exp = tv(if (hfExpandedHabit == h.id) "∧" else "›", 16f, V5.MUTED, true)
            exp.gravity = Gravity.CENTER
            exp.layoutParams = LinearLayout.LayoutParams(dp(32f), dp(32f))
            exp.contentDescription = "Expand checklist"
            exp.setOnClickListener { hfExpandedHabit = if (hfExpandedHabit == h.id) null else h.id; render() }
            srow.addView(exp)
            cardL.addView(srow)
            if (hfExpandedHabit == h.id) {
                h.checklist.forEach { item ->
                    val irow = LinearLayout(this); irow.orientation = LinearLayout.HORIZONTAL
                    irow.gravity = Gravity.CENTER_VERTICAL
                    irow.setPadding(0, dp(6f), 0, dp(6f))
                    val isDone = item.id in doneItems
                    val box = tv(if (isDone) "✓" else "", 11f, 0xFFFFFFFF.toInt(), true)
                    box.gravity = Gravity.CENTER
                    box.background = if (isDone) roundedRect(color, 6f)
                        else roundedRect(android.graphics.Color.TRANSPARENT, 6f, V5.FAINT)
                    box.layoutParams = LinearLayout.LayoutParams(dp(21f), dp(21f))
                    if (editable) box.setOnClickListener { Store.habitToggleChecklist(h.id, day, item.id); Sfx.tick(); render() }
                    irow.addView(box)
                    val it2 = tv(item.title, 14f, if (isDone) V5.MUTED else V5.TEXT)
                    it2.layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(9f) }
                    irow.addView(it2)
                    cardL.addView(irow)
                }
            }
        }
        if (h.kind == "quit" && h.tracking == "tick") {
            val q = tv("Stayed clean today", 11f, V5.FAINT)
            q.layoutParams = lp(t = dp(8f))
            cardL.addView(q)
        }
        return cardL
    }

    /** Habit Library sheet (§1.4/§1.5): grouped catalogue + search;
     *  adopted entries jump to their detail instead of re-adopting. */
    private fun buildHabitLibrary(): View {
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
        col.addView(tv("Habit Library", 17f, Glass.text(this@MainActivity), true))
        val results = LinearLayout(this); results.orientation = LinearLayout.VERTICAL
        fun fill() {
            results.removeAllViews()
            val q = habitLibQuery.trim().lowercase()
            HabitLibrary.GROUPS.forEach { g ->
                val items = HabitLibrary.byGroup(g).filter { q.isEmpty() || it.name.lowercase().contains(q) }
                if (items.isEmpty()) return@forEach
                val gh = tv(g.uppercase(), 11f, Glass.muted(this@MainActivity), true)
                gh.layoutParams = lp(t = dp(14f), b = dp(6f))
                results.addView(gh)
                items.forEach { tpl ->
                    val adoptedHabit = Store.state.habits.find { it.libraryKey == tpl.key }
                    val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL
                    row.gravity = Gravity.CENTER_VERTICAL
                    row.setPadding(0, dp(8f), 0, dp(8f))
                    val well = iconWell(habitGlyph(tpl.icon), habitColor(tpl.color), 36f, 17f)
                    well.background = roundedRect(V5.tint(habitColor(tpl.color), 38), V5.R_ICONSQ)
                    row.addView(well)
                    val nc = LinearLayout(this); nc.orientation = LinearLayout.VERTICAL
                    nc.layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(10f) }
                    val nameRow = LinearLayout(this); nameRow.orientation = LinearLayout.HORIZONTAL
                    nameRow.addView(tv(tpl.name, 15f, Glass.text(this@MainActivity), true))
                    if (tpl.popular) nameRow.addView(tv(" 🔥", 13f))
                    nc.addView(nameRow)
                    val tg = tv(tpl.tagline, 12f, Glass.muted(this@MainActivity))
                    tg.maxLines = 1
                    tg.ellipsize = android.text.TextUtils.TruncateAt.END
                    nc.addView(tg)
                    row.addView(nc)
                    row.addView(tv(if (adoptedHabit != null) "Added ✓" else "›", 13f,
                        if (adoptedHabit != null) V5.FAINT else Glass.muted(this@MainActivity), adoptedHabit != null))
                    row.setOnClickListener {
                        if (adoptedHabit != null) {
                            closeSheet(); sub = "habit:" + adoptedHabit.id; render()
                        } else {
                            adoptTpl = tpl
                            adoptDays.clear(); adoptDays.addAll(listOf(1, 2, 3, 4, 5, 6, 7))
                            adoptTimes.clear()
                            adoptPhraseIn = null
                            pushSheet(buildHabitAdopt()) { buildHabitLibrary() }
                        }
                    }
                    results.addView(row)
                }
            }
            if (results.childCount == 0) {
                results.addView(tv("No habits match.", 13f, Glass.muted(this@MainActivity)))
            }
        }
        val search = input("Search habits…", habitLibQuery)
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(e: Editable?) { habitLibQuery = e?.toString() ?: ""; fill() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        col.addView(search)
        col.addView(results)
        fill()
        return col
    }

    /** The ONE-sheet adopt flow (§1.5): phrase, repeat day circles,
     *  reminder time chips, "Add to My List". */
    private fun buildHabitAdopt(): View {
        val tpl = adoptTpl ?: return buildHabitLibrary()
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
        val headRow = LinearLayout(this); headRow.orientation = LinearLayout.HORIZONTAL
        headRow.gravity = Gravity.CENTER_VERTICAL
        val well = iconWell(habitGlyph(tpl.icon), habitColor(tpl.color), 44f, 21f)
        well.background = roundedRect(V5.tint(habitColor(tpl.color), 38), V5.R_ICONSQ)
        headRow.addView(well)
        val hc = LinearLayout(this); hc.orientation = LinearLayout.VERTICAL
        hc.layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12f) }
        hc.addView(tv(tpl.name, 17f, Glass.text(this@MainActivity), true))
        val tg = tv(tpl.tagline, 13f, Glass.muted(this@MainActivity))
        tg.layoutParams = lp(t = dp(2f))
        hc.addView(tg)
        headRow.addView(hc)
        col.addView(headRow)
        col.addView(label("Reminder phrase"))
        val phrase = input("Reminder phrase", tpl.name)
        adoptPhraseIn = phrase
        col.addView(phrase)
        col.addView(label("Repeat on"))
        val dayRow = LinearLayout(this); dayRow.orientation = LinearLayout.HORIZONTAL
        // Sunday first (S M T W T F S), as in the reference app; values
        // are HabitLogic weekdays (1=Mon..7=Sun).
        listOf(7 to "S", 1 to "M", 2 to "T", 3 to "W", 4 to "T", 5 to "F", 6 to "S").forEach { (dow, l) ->
            val on = dow in adoptDays
            val c = tv(l, 13f, if (on) 0xFFFFFFFF.toInt() else Glass.text(this@MainActivity), true)
            c.gravity = Gravity.CENTER
            c.background = if (on) ovalDrawable(V5.ACCENT) else ovalDrawable(Todoist.POPUP)
            c.layoutParams = LinearLayout.LayoutParams(dp(36f), dp(36f)).apply { rightMargin = dp(6f) }
            c.setOnClickListener {
                if (dow in adoptDays) adoptDays.remove(dow) else adoptDays.add(dow)
                showSheet(buildHabitAdopt())
            }
            dayRow.addView(c)
        }
        col.addView(dayRow)
        col.addView(label("Reminder at"))
        val timesRow = LinearLayout(this); timesRow.orientation = LinearLayout.HORIZONTAL
        adoptTimes.sorted().forEach { tm ->
            val ch = tv(tm + "  ✕", 13f)
            ch.background = Glass.sheetChip(this, true)
            ch.setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
            ch.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6f) }
            ch.setOnClickListener { adoptTimes.remove(tm); showSheet(buildHabitAdopt()) }
            timesRow.addView(ch)
        }
        val addT = tv("＋ Add time", 13f)
        addT.background = Glass.sheetChip(this, false)
        addT.setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
        addT.setOnClickListener {
            promptHabitTime { tm ->
                if (tm !in adoptTimes) adoptTimes.add(tm)
                showSheet(buildHabitAdopt())
            }
        }
        timesRow.addView(addT)
        col.addView(timesRow)
        val add = accentBtn("Add to My List") {
            if (adoptDays.isEmpty()) { toast("Pick at least one day"); return@accentBtn }
            val h = HabitItem(
                id = uid(), name = tpl.name, description = tpl.tagline, icon = tpl.icon,
                color = tpl.color, kind = tpl.kind,
                freqType = if (adoptDays.size == 7) "daily" else "weekdays",
                freqDays = adoptDays.sorted().toMutableList(),
                reminderPhrase = adoptPhraseIn?.text?.toString()?.trim()?.ifEmpty { tpl.name } ?: tpl.name,
                reminderTimes = adoptTimes.sorted().toMutableList(),
                libraryKey = tpl.key)
            Store.addHabit(h)
            Sfx.add()
            closeSheet()
            sub = "habits"; habitFilterDue = false; render()
            toast("Habit added")
        }
        add.layoutParams = lp(t = dp(16f))
        col.addView(add)
        val back = ghostBtn("← Back") { if (!popSheet()) showSheet(buildHabitLibrary()) }
        back.layoutParams = lp(t = dp(8f))
        col.addView(back)
        return col
    }

    private fun promptHabitTime(cb: (String) -> Unit) {
        val e = EditText(this)
        e.setSingleLine(true)
        e.hint = "HH:mm — e.g. 07:30"
        AlertDialog.Builder(this).setTitle("Reminder time").setView(e)
            .setPositiveButton("Add") { _, _ ->
                val v = e.text.toString().trim()
                if (Regex("""^([01]?\d|2[0-3]):[0-5]\d$""").matches(v)) {
                    val p = v.split(":")
                    cb(p[0].padStart(2, '0') + ":" + p[1])
                } else toast("Time must be HH:mm")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /* ---------------- custom habit form (§1.6) ---------------- */

    private fun startHabitForm(editId: String?) {
        val h = editId?.let { Store.habitById(it) }
        habitFormId = editId
        hfName = h?.name ?: ""
        hfDesc = h?.description ?: ""
        hfColor = h?.color ?: "#d9a441"
        hfIcon = h?.icon ?: "circle"
        hfKind = h?.kind ?: "build"
        hfFreqType = h?.freqType ?: "daily"
        hfDays.clear(); hfMonthDays.clear()
        if (h != null) {
            if (h.freqType == "monthdays") hfMonthDays.addAll(h.freqDays) else hfDays.addAll(h.freqDays)
        }
        hfFreqCount = h?.freqCount ?: 3
        hfFreqPeriod = h?.freqPeriodDays ?: 7
        hfTracking = h?.tracking ?: "tick"
        hfTarget = h?.targetCount ?: 1
        hfUnit = h?.unit ?: ""
        hfChecklist.clear()
        hfChecklist.addAll(h?.checklist?.map { it.title } ?: emptyList())
        hfPhrase = h?.reminderPhrase ?: ""
        hfTimes.clear()
        hfTimes.addAll(h?.reminderTimes ?: emptyList())
        sub = "habitform"
        render()
    }

    /** Text input that mirrors every keystroke into a field, so the
     *  form's chip/stepper re-renders never lose typed text. */
    private fun watchedInput(hint: String, value: String, multi: Boolean = false, onChange: (String) -> Unit): EditText {
        val e = input(hint, value, multi)
        e.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { onChange(s?.toString() ?: "") }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        return e
    }

    private fun stepperRow(labelText: String, value: Int, onChange: (Int) -> Unit): LinearLayout {
        val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(6f), 0, dp(6f))
        val l = tv(labelText, 15f)
        l.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        row.addView(l)
        val minus = tv("−", 17f, V5.TEXT, true)
        minus.gravity = Gravity.CENTER
        minus.background = roundedRect(V5.POPUP, 999f)
        minus.layoutParams = LinearLayout.LayoutParams(dp(34f), dp(34f)).apply { rightMargin = dp(8f) }
        minus.setOnClickListener { onChange(value - 1) }
        row.addView(minus)
        val valT = tv(value.toString(), 16f, V5.TEXT, true)
        valT.gravity = Gravity.CENTER
        valT.layoutParams = LinearLayout.LayoutParams(dp(30f), -2)
        row.addView(valT)
        val plus = tv("+", 17f, V5.TEXT, true)
        plus.gravity = Gravity.CENTER
        plus.background = roundedRect(V5.POPUP, 999f)
        plus.layoutParams = LinearLayout.LayoutParams(dp(34f), dp(34f)).apply { leftMargin = dp(8f) }
        plus.setOnClickListener { onChange(value + 1) }
        row.addView(plus)
        return row
    }

    private fun renderHabitForm() {
        val saveTop = accentBtn("✓ Save habit") { saveHabitForm() }
        content.addView(saveTop)
        content.addView(label("Name"))
        content.addView(watchedInput("Eg. Wake Up Early, Quit Drinking, etc.", hfName) { hfName = it })
        content.addView(label("Description"))
        content.addView(watchedInput("What is it about? (optional)", hfDesc, multi = true) { hfDesc = it })
        content.addView(label("Colour"))
        val crow = LinearLayout(this); crow.orientation = LinearLayout.HORIZONTAL
        HF_COLORS.forEach { hex ->
            val sw = tv(if (hex == hfColor) "✓" else "", 13f, 0xFFFFFFFF.toInt(), true)
            sw.gravity = Gravity.CENTER
            sw.background = ovalDrawable(habitColor(hex))
            sw.layoutParams = LinearLayout.LayoutParams(dp(34f), dp(34f)).apply { rightMargin = dp(7f) }
            sw.setOnClickListener { hfColor = hex; render() }
            crow.addView(sw)
        }
        val crowScroll = HorizontalScrollView(this)
        crowScroll.isHorizontalScrollBarEnabled = false
        crowScroll.addView(crow)
        content.addView(crowScroll)
        content.addView(label("Icon"))
        HF_ICONS.chunked(6).forEach { rowIcons ->
            val irow = LinearLayout(this); irow.orientation = LinearLayout.HORIZONTAL
            irow.layoutParams = lp(b = dp(8f))
            rowIcons.forEach { icon ->
                val on = icon == hfIcon
                val cell = tv(habitGlyph(icon), 18f)
                cell.gravity = Gravity.CENTER
                cell.background = if (on) roundedRect(V5.tint(habitColor(hfColor), 60), V5.R_ICONSQ, habitColor(hfColor))
                    else roundedRect(V5.POPUP, V5.R_ICONSQ)
                cell.layoutParams = LinearLayout.LayoutParams(0, dp(44f), 1f).apply { rightMargin = dp(8f) }
                cell.setOnClickListener { hfIcon = icon; render() }
                irow.addView(cell)
            }
            repeat(6 - rowIcons.size) {
                val sp = View(this)
                sp.layoutParams = LinearLayout.LayoutParams(0, dp(44f), 1f).apply { rightMargin = dp(8f) }
                irow.addView(sp)
            }
            content.addView(irow)
        }
        content.addView(label("Type"))
        val typeRow = LinearLayout(this); typeRow.orientation = LinearLayout.HORIZONTAL
        val bBuild = chipBtn("Build", hfKind == "build") { hfKind = "build"; render() }
        bBuild.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6f) }
        val bQuit = chipBtn("Quit", hfKind == "quit") { hfKind = "quit"; render() }
        typeRow.addView(bBuild); typeRow.addView(bQuit)
        content.addView(typeRow)
        val typeCap = tv(
            if (hfKind == "quit") "Quit — something to stop. The tick means “stayed clean”."
            else "Build — something to do every time it's due.",
            12f, V5.MUTED)
        typeCap.layoutParams = lp(t = dp(4f))
        content.addView(typeCap)
        content.addView(label("Frequency"))
        listOf(
            "daily" to "Everyday", "weekdays" to "Specific days of week",
            "monthdays" to "Specific days of month", "period" to "Some days per period"
        ).forEach { (v, l) ->
            val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(0, dp(7f), 0, dp(7f))
            val radio = tv(if (hfFreqType == v) "◉" else "○", 16f, if (hfFreqType == v) V5.ACCENT_BRAND else V5.MUTED)
            radio.layoutParams = LinearLayout.LayoutParams(dp(28f), -2)
            row.addView(radio)
            row.addView(tv(l, 15f))
            row.setOnClickListener { hfFreqType = v; render() }
            content.addView(row)
        }
        when (hfFreqType) {
            "weekdays" -> {
                val dayRow = LinearLayout(this); dayRow.orientation = LinearLayout.HORIZONTAL
                dayRow.layoutParams = lp(t = dp(6f))
                listOf(1 to "M", 2 to "T", 3 to "W", 4 to "T", 5 to "F", 6 to "S", 7 to "S").forEach { (dow, l) ->
                    val on = dow in hfDays
                    val c = tv(l, 13f, if (on) 0xFFFFFFFF.toInt() else V5.TEXT, true)
                    c.gravity = Gravity.CENTER
                    c.background = if (on) ovalDrawable(V5.ACCENT) else ovalDrawable(V5.POPUP)
                    c.layoutParams = LinearLayout.LayoutParams(dp(36f), dp(36f)).apply { rightMargin = dp(6f) }
                    c.setOnClickListener {
                        if (dow in hfDays) hfDays.remove(dow) else hfDays.add(dow)
                        render()
                    }
                    dayRow.addView(c)
                }
                content.addView(dayRow)
            }
            "monthdays" -> {
                (1..31).chunked(7).forEach { chunk ->
                    val mrow = LinearLayout(this); mrow.orientation = LinearLayout.HORIZONTAL
                    mrow.layoutParams = lp(t = dp(6f))
                    chunk.forEach { d ->
                        val c = chipBtn(d.toString(), d in hfMonthDays) {
                            if (d in hfMonthDays) hfMonthDays.remove(d) else hfMonthDays.add(d)
                            render()
                        }
                        c.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6f) }
                        mrow.addView(c)
                    }
                    content.addView(mrow)
                }
            }
            "period" -> {
                content.addView(stepperRow("Days needed", hfFreqCount) { hfFreqCount = it.coerceIn(1, 31); render() })
                content.addView(stepperRow("Per days", hfFreqPeriod) { hfFreqPeriod = it.coerceIn(2, 31); render() })
            }
        }
        content.addView(label("Tracking"))
        val trRow = LinearLayout(this); trRow.orientation = LinearLayout.HORIZONTAL
        listOf("tick" to "Tick", "count" to "Count", "checklist" to "Checklist").forEach { (v, l) ->
            val ch = chipBtn(l, hfTracking == v) { hfTracking = v; render() }
            ch.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6f) }
            trRow.addView(ch)
        }
        content.addView(trRow)
        val trCap = tv(when (hfTracking) {
            "count" -> "Count up to a daily target."
            "checklist" -> "The day is done when every item is ticked."
            else -> "One tap marks the day done."
        }, 12f, V5.MUTED)
        trCap.layoutParams = lp(t = dp(4f))
        content.addView(trCap)
        when (hfTracking) {
            "count" -> {
                content.addView(stepperRow("Daily target", hfTarget) { hfTarget = it.coerceIn(1, 999); render() })
                content.addView(label("Unit"))
                content.addView(watchedInput("e.g. glasses, pages, minutes", hfUnit) { hfUnit = it })
            }
            "checklist" -> {
                hfChecklist.forEachIndexed { i, item ->
                    val r = LinearLayout(this); r.orientation = LinearLayout.HORIZONTAL
                    r.gravity = Gravity.CENTER_VERTICAL
                    val inp = watchedInput("Checklist item", item) { hfChecklist[i] = it }
                    inp.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                    r.addView(inp)
                    val del = tv("✕", 14f, V5.MUTED)
                    del.setPadding(dp(8f), dp(8f), dp(4f), dp(8f))
                    del.contentDescription = "Remove item"
                    del.setOnClickListener { hfChecklist.removeAt(i); render() }
                    r.addView(del)
                    content.addView(r)
                }
                val addItem = ghostBtn("＋ Add item") { hfChecklist.add(""); render() }
                addItem.layoutParams = lp(t = dp(4f))
                content.addView(addItem)
            }
        }
        content.addView(label("Reminders"))
        content.addView(watchedInput("Reminder phrase", hfPhrase) { hfPhrase = it })
        val timesRow = LinearLayout(this); timesRow.orientation = LinearLayout.HORIZONTAL
        hfTimes.sorted().forEach { tm ->
            val ch = tv(tm + "  ✕", 13f)
            ch.background = Glass.sheetChip(this, true)
            ch.setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
            ch.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6f) }
            ch.setOnClickListener { hfTimes.remove(tm); render() }
            timesRow.addView(ch)
        }
        val addT = tv("＋ Add time", 13f)
        addT.background = Glass.sheetChip(this, false)
        addT.setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
        addT.setOnClickListener {
            promptHabitTime { tm -> if (tm !in hfTimes) hfTimes.add(tm); render() }
        }
        timesRow.addView(addT)
        content.addView(timesRow)
        val saveBottom = accentBtn("✓ Save habit") { saveHabitForm() }
        saveBottom.layoutParams = lp(t = dp(18f))
        content.addView(saveBottom)
        val editId = habitFormId
        if (editId != null) {
            val cancel = ghostBtn("Cancel") { sub = "habit:" + editId; render() }
            cancel.layoutParams = lp(t = dp(8f))
            content.addView(cancel)
        }
    }

    private fun saveHabitForm() {
        if (hfName.isBlank()) { toast("Give the habit a name"); return }
        if (hfFreqType == "weekdays" && hfDays.isEmpty()) { toast("Pick at least one weekday"); return }
        if (hfFreqType == "monthdays" && hfMonthDays.isEmpty()) { toast("Pick at least one day of the month"); return }
        if (hfTracking == "checklist" && hfChecklist.none { it.isNotBlank() }) { toast("Add at least one checklist item"); return }
        if (hfTracking == "count" && hfTarget < 1) { toast("Target must be at least 1"); return }
        val freqDays = when (hfFreqType) {
            "weekdays" -> hfDays.sorted().toMutableList()
            "monthdays" -> hfMonthDays.sorted().toMutableList()
            else -> mutableListOf()
        }
        val items = hfChecklist.filter { it.isNotBlank() }.map { it.trim() }
        val existing = habitFormId?.let { Store.habitById(it) }
        if (existing != null) {
            existing.name = hfName.trim()
            existing.description = hfDesc.trim()
            existing.icon = hfIcon
            existing.color = hfColor
            existing.kind = hfKind
            existing.freqType = hfFreqType
            existing.freqDays = freqDays
            existing.freqCount = hfFreqCount
            existing.freqPeriodDays = hfFreqPeriod
            existing.tracking = hfTracking
            existing.targetCount = hfTarget
            existing.unit = hfUnit.trim()
            // Preserve checklist item ids by title so existing day logs
            // (which reference item ids) stay valid across the edit.
            val oldByTitle = existing.checklist.associateBy { it.title }
            existing.checklist = items.map { title -> oldByTitle[title] ?: Subtask(uid(), title, false) }.toMutableList()
            existing.reminderPhrase = hfPhrase.trim()
            existing.reminderTimes = hfTimes.sorted().toMutableList()
            Store.updateHabit(existing)
            sub = "habit:" + existing.id
        } else {
            val h = HabitItem(
                id = uid(), name = hfName.trim(), description = hfDesc.trim(), icon = hfIcon,
                color = hfColor, kind = hfKind, freqType = hfFreqType, freqDays = freqDays,
                freqCount = hfFreqCount, freqPeriodDays = hfFreqPeriod, tracking = hfTracking,
                targetCount = hfTarget, unit = hfUnit.trim(),
                checklist = items.map { Subtask(uid(), it, false) }.toMutableList(),
                reminderPhrase = hfPhrase.trim(), reminderTimes = hfTimes.sorted().toMutableList())
            Store.addHabit(h)
            sub = "habits"
        }
        Sfx.add()
        render()
        toast("Habit saved")
    }

    /* ---------------- habit detail (§1.3) ---------------- */

    private fun habitFreqText(h: HabitItem): String = when (h.freqType) {
        "daily" -> "Every day"
        "weekdays" -> {
            val names = arrayOf("", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
            h.freqDays.sorted().joinToString(" ") { names.getOrElse(it) { "" } }
        }
        "monthdays" -> "Monthly on " + h.freqDays.sorted().joinToString(", ")
        "period" -> h.freqCount.toString() + " days per " + h.freqPeriodDays + " days"
        else -> h.freqType
    }

    private fun habitTrackingText(h: HabitItem): String = when (h.tracking) {
        "count" -> "Count · " + h.targetCount + if (h.unit.isNotBlank()) " " + h.unit else ""
        "checklist" -> "Checklist · " + h.checklist.size + " items"
        else -> "Tick"
    }

    private fun renderHabitDetail(id: String) {
        val h = Store.habitById(id)
        if (h == null) { content.addView(emptyView("🔥", "Habit not found.")); return }
        val today = Dates.today()
        val color = habitColor(h.color)
        val logs = Store.state.habitLogs
        val head = LinearLayout(this); head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val well = iconWell(habitGlyph(h.icon), color, 48f, 23f)
        well.background = roundedRect(V5.tint(color, 38), V5.R_ICONSQ)
        head.addView(well)
        val hc = LinearLayout(this); hc.orientation = LinearLayout.VERTICAL
        hc.layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12f) }
        hc.addView(tv(h.name, 20f, V5.TEXT, true))
        val meta = tv(habitFreqText(h) + " · " + habitTrackingText(h) + if (h.archived) " · Archived" else "", 13f, V5.MUTED)
        meta.layoutParams = lp(t = dp(2f))
        hc.addView(meta)
        if (h.description.isNotBlank()) {
            val d = tv(h.description, 12f, V5.FAINT)
            d.layoutParams = lp(t = dp(2f))
            hc.addView(d)
        }
        head.addView(hc)
        content.addView(head)

        val gc = LinearLayout(this); gc.orientation = LinearLayout.VERTICAL
        gc.background = v5CardBg()
        gc.setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
        gc.layoutParams = lp(t = dp(16f))
        val grid = YearGridView(this)
        grid.cells = HabitLogic.gridCells(h, logs, today)
        grid.colorHex = h.color
        gc.addView(grid)
        content.addView(gc)

        val startedCal = java.util.Calendar.getInstance()
        startedCal.timeInMillis = h.createdAt
        val statsRow = LinearLayout(this); statsRow.orientation = LinearLayout.HORIZONTAL
        statsRow.layoutParams = lp(t = dp(12f))
        listOf(
            HabitLogic.streak(h, logs, today).toString() to "Current streak",
            HabitLogic.bestStreak(h, logs, today).toString() to "Best streak",
            logs.count { it.habitId == h.id && HabitLogic.resolveDone(h, it) }.toString() to "Days done",
            fmtMonDay(Dates.dstr(startedCal)) to "Started"
        ).forEachIndexed { i, (v, l) ->
            val c = LinearLayout(this); c.orientation = LinearLayout.VERTICAL
            c.background = v5CardBg(V5.R_TILE)
            c.setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
            c.layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = dp(10f) }
            c.addView(tv(v, 18f, V5.TEXT, true))
            val lt = tv(l, 10f, V5.MUTED)
            lt.layoutParams = lp(t = dp(2f))
            c.addView(lt)
            statsRow.addView(c)
        }
        content.addView(statsRow)

        content.addView(sectionHeader("Milestones"))
        val mc = LinearLayout(this); mc.orientation = LinearLayout.VERTICAL
        mc.background = v5CardBg()
        mc.setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
        HabitLogic.milestones(h, logs, today).forEach { m ->
            val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(0, dp(8f), 0, dp(8f))
            val node = tv(when {
                m.label == "Started" -> "🚩"
                m.label.contains("streak") -> "🔥"
                else -> "✓"
            }, 13f)
            node.gravity = Gravity.CENTER
            node.background = ovalDrawable(V5.ACCENT_TINT)
            node.layoutParams = LinearLayout.LayoutParams(dp(26f), dp(26f)).apply { rightMargin = dp(10f) }
            row.addView(node)
            val ml = tv(m.label, 14f, V5.TEXT, true)
            ml.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            row.addView(ml)
            row.addView(tv(fmtMonDay(m.date), 12f, V5.MUTED))
            mc.addView(row)
        }
        content.addView(mc)

        if (h.reminderTimes.isNotEmpty()) {
            val ri = tv("🔔 " + h.reminderTimes.joinToString(", ") +
                if (h.reminderPhrase.isNotBlank()) " · \"" + h.reminderPhrase + "\"" else "", 13f, V5.MUTED)
            ri.layoutParams = lp(t = dp(12f))
            content.addView(ri)
        }
        val edit = accentBtn("Edit habit") { startHabitForm(h.id) }
        edit.layoutParams = lp(t = dp(16f))
        content.addView(edit)
        val arch = ghostBtn(if (h.archived) "Unarchive" else "Archive") {
            Store.archiveHabit(h.id, !h.archived)
            toast(if (h.archived) "Habit unarchived" else "Habit archived")
            sub = "habits"; render()
        }
        arch.layoutParams = lp(t = dp(8f))
        content.addView(arch)
        if (isV5()) {
            // §2.9 row anatomy for the destructive action: glyph in a
            // tinted icon well + label, not a bare red text line.
            val delRow = LinearLayout(this); delRow.orientation = LinearLayout.HORIZONTAL
            delRow.gravity = Gravity.CENTER_VERTICAL
            delRow.layoutParams = lp(t = dp(14f))
            delRow.addView(iconWell("🗑", V5.P1, 32f, 15f))
            val dt = tv("Delete habit", 15f, Glass.overdue(this@MainActivity))
            dt.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12f) }
            delRow.addView(dt)
            delRow.setOnClickListener {
                val removed = Store.deleteHabit(h.id)
                sub = "habits"; render()
                if (removed != null) showHabitUndo(removed.first, removed.second)
            }
            content.addView(delRow)
        } else {
            val del = tv("Delete habit", 15f, Glass.overdue(this@MainActivity))
            del.gravity = Gravity.CENTER
            del.setPadding(dp(12f), dp(14f), dp(12f), dp(6f))
            del.setOnClickListener {
                val removed = Store.deleteHabit(h.id)
                sub = "habits"; render()
                if (removed != null) showHabitUndo(removed.first, removed.second)
            }
            content.addView(del)
        }
    }

    private fun showHabitUndo(h: HabitItem, logs: List<HabitLog>) {
        undoBar.removeAllViews()
        val lbl = tv("Deleted habit \"" + h.name + "\"", 13f)
        lbl.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(10f) }
        undoBar.addView(lbl)
        val btn = tv("UNDO", 15f, Glass.gold(this@MainActivity), true)
        btn.setPadding(dp(8f), dp(10f), dp(8f), dp(10f))
        btn.setOnClickListener {
            Store.restoreHabit(h, logs)
            undoBar.visibility = View.GONE
            render(); toast("Restored ↩️")
        }
        undoBar.addView(btn)
        undoHide?.let { handler.removeCallbacks(it) }
        undoHide = Runnable { undoBar.visibility = View.GONE }
        undoBar.visibility = View.VISIBLE
        handler.postDelayed(undoHide!!, 5000)
    }

    /* ---------------- v5 Eisenhower Matrix (SHADOW-THREE §5) -------- */

    /** Important = priority ≤ 2. Urgent = due today/tomorrow, or a
     *  deadline of today or earlier. */
    private fun quadrantOf(t: TaskItem): Int {
        val today = Dates.today()
        val imp = t.priority <= 2
        val urg = (t.dueDate != null && t.dueDate!! <= Dates.addDays(today, 1)) ||
            (t.deadline != null && t.deadline!! <= today)
        return when {
            imp && urg -> 1
            imp -> 2
            urg -> 3
            else -> 4
        }
    }

    private fun renderMatrix() {
        val quads = listOf(
            Triple(1, "Do Now", "Urgent · Important"),
            Triple(2, "Schedule", "Important · Not urgent"),
            Triple(3, "Batch", "Urgent · Not important"),
            Triple(4, "Later", "Not urgent · Not important")
        )
        var rowIdx = 0
        while (rowIdx < 4) {
            val rowL = LinearLayout(this); rowL.orientation = LinearLayout.HORIZONTAL
            rowL.layoutParams = lp(t = if (rowIdx == 0) 0 else dp(12f))
            for (k in 0..1) {
                val (q, qname, qsub) = quads[rowIdx + k]
                val tasks = Store.sortTasks(Store.state.tasks.filter { quadrantOf(it) == q })
                val cardL = LinearLayout(this); cardL.orientation = LinearLayout.VERTICAL
                cardL.background = v5CardBg()
                cardL.setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
                cardL.layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { if (k == 1) leftMargin = dp(12f) }
                cardL.addView(tv(qname, 15f, if (q == 1) V5.ACCENT_BRAND else V5.TEXT, true))
                val hs = tv(qsub + " · " + tasks.size, 11f, V5.FAINT)
                hs.layoutParams = lp(t = dp(2f), b = dp(6f))
                cardL.addView(hs)
                if (tasks.isEmpty()) {
                    cardL.addView(tv("Nothing here.", 12f, V5.FAINT))
                } else {
                    tasks.take(5).forEach { t ->
                        val r = LinearLayout(this); r.orientation = LinearLayout.HORIZONTAL
                        r.gravity = Gravity.CENTER_VERTICAL
                        r.setPadding(0, dp(5f), 0, dp(5f))
                        val ck = tv("", 10f, 0xFFFFFFFF.toInt(), true)
                        ck.gravity = Gravity.CENTER
                        ck.background = Glass.checkCircle(this, t.priority, false)
                        ck.layoutParams = LinearLayout.LayoutParams(dp(18f), dp(18f))
                        ck.contentDescription = "Complete task"
                        ck.setOnClickListener {
                            val msg = Store.completeTask(t.id)
                            if (msg.isNotEmpty()) toast(msg)
                            render()
                        }
                        r.addView(ck)
                        val tt = tv(t.title, 13f, V5.TEXT)
                        tt.maxLines = 1
                        tt.ellipsize = android.text.TextUtils.TruncateAt.END
                        tt.layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8f) }
                        r.addView(tt)
                        r.setOnClickListener { openEditor(t.id) }
                        cardL.addView(r)
                    }
                    if (tasks.size > 5) {
                        val more = tv("+" + (tasks.size - 5) + " more", 12f, V5.ACCENT_BRAND, true)
                        more.setPadding(0, dp(6f), 0, 0)
                        more.setOnClickListener { matrixQuadSel = q; sub = "matrixlist"; render() }
                        cardL.addView(more)
                    }
                }
                rowL.addView(cardL)
            }
            content.addView(rowL)
            rowIdx += 2
        }
    }

    /* ---------------- v5 Countdowns (SHADOW-THREE §6) ---------------- */

    private fun fmtDateWords(s: String): String = try {
        val months = arrayOf("January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December")
        val c = Dates.parse(s)
        c.get(java.util.Calendar.DAY_OF_MONTH).toString() + " " +
            months[c.get(java.util.Calendar.MONTH)] + " " + c.get(java.util.Calendar.YEAR)
    } catch (e: Exception) { s }

    private fun renderCountdowns() {
        val today = Dates.today()
        val add = accentBtn("＋ Add a countdown") {
            cdColor = "#d9a441"; cdDate = Dates.today(); showSheet(buildCountdownSheet(null))
        }
        content.addView(add)
        if (Store.state.countdowns.isEmpty()) {
            // No seeds, no pre-filled dates, ever (§6).
            content.addView(v5EmptyState("⏳", "Count down to what matters.", "Deadlines, launches, big days — add your first countdown."))
            return
        }
        val future = Store.state.countdowns.filter { it.targetDate >= today }.sortedBy { it.targetDate }
        val past = Store.state.countdowns.filter { it.targetDate < today }.sortedByDescending { it.targetDate }
        (future + past).forEach { c ->
            val days = Dates.daysBetween(today, c.targetDate)
            val cardL = LinearLayout(this); cardL.orientation = LinearLayout.HORIZONTAL
            cardL.gravity = Gravity.CENTER_VERTICAL
            cardL.background = v5CardBg()
            cardL.setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
            cardL.layoutParams = lp(t = dp(12f))
            val bar = View(this)
            bar.background = roundedRect(habitColor(c.color), 2f)
            bar.layoutParams = LinearLayout.LayoutParams(dp(4f), -1).apply { rightMargin = dp(12f) }
            cardL.addView(bar)
            val colT = LinearLayout(this); colT.orientation = LinearLayout.VERTICAL
            colT.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            colT.addView(tv(c.title, 16f, if (days < 0) V5.MUTED else V5.TEXT, true))
            val dw = tv(fmtDateWords(c.targetDate), 12f, V5.MUTED)
            dw.layoutParams = lp(t = dp(2f))
            colT.addView(dw)
            cardL.addView(colT)
            val numCol = LinearLayout(this); numCol.orientation = LinearLayout.VERTICAL
            numCol.gravity = Gravity.END
            val num = tv(Math.abs(days).toString(), 30f, if (days < 0) V5.MUTED else V5.TEXT, true)
            num.gravity = Gravity.END
            numCol.addView(num)
            val capT = tv(when {
                days > 0 -> "days left"
                days == 0 -> "Today!"
                else -> "days since"
            }, 11f, V5.MUTED)
            capT.gravity = Gravity.END
            numCol.addView(capT)
            cardL.addView(numCol)
            cardL.setOnClickListener { cdColor = c.color; cdDate = c.targetDate; showSheet(buildCountdownSheet(c)) }
            content.addView(cardL)
        }
    }

    private fun buildCountdownSheet(existing: CountdownItem?): View {
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
        col.addView(tv(if (existing == null) "New countdown" else "Edit countdown", 17f, Glass.text(this@MainActivity), true))
        col.addView(label("Title"))
        val titleIn = input("e.g. Channel launch", existing?.title ?: "")
        col.addView(titleIn)
        col.addView(label("Target date"))
        // The date comes from the date-sheet picker (quick rows +
        // month grids — the same picker grammar as Quick Add's),
        // never a typed yyyy-MM-dd field (Check Team 2 polish item).
        val dateRow = LinearLayout(this); dateRow.orientation = LinearLayout.HORIZONTAL
        dateRow.gravity = Gravity.CENTER_VERTICAL
        dateRow.background = roundedRect(if (isV5()) V5.POPUP else Todoist.CHIP, 12f)
        dateRow.setPadding(dp(14f), dp(12f), dp(10f), dp(12f))
        dateRow.layoutParams = lp(b = dp(10f))
        val dv = tv("▦  " + fmtDateWords(cdDate), 15f, Glass.text(this@MainActivity), true)
        dv.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        dateRow.addView(dv)
        val pick = tv("Pick date", 13f, if (isV5()) V5.ACCENT_BRAND else TD_SALMON, true)
        pick.setPadding(dp(8f), dp(4f), dp(4f), dp(4f))
        dateRow.addView(pick)
        dateRow.setOnClickListener {
            pushSheet(buildCountdownDatePicker(existing)) { buildCountdownSheet(existing) }
        }
        col.addView(dateRow)
        col.addView(label("Colour"))
        val crow = LinearLayout(this); crow.orientation = LinearLayout.HORIZONTAL
        HF_COLORS.forEachIndexed { i, hex ->
            val sw = tv(if (hex == cdColor) "✓" else "", 12f, 0xFFFFFFFF.toInt(), true)
            sw.gravity = Gravity.CENTER
            sw.background = ovalDrawable(habitColor(hex))
            sw.layoutParams = LinearLayout.LayoutParams(dp(32f), dp(32f)).apply { rightMargin = dp(6f) }
            sw.setOnClickListener {
                cdColor = hex
                for (j in 0 until crow.childCount) {
                    (crow.getChildAt(j) as? TextView)?.text = if (HF_COLORS[j] == hex) "✓" else ""
                }
            }
            crow.addView(sw)
        }
        val crowScroll = HorizontalScrollView(this)
        crowScroll.isHorizontalScrollBarEnabled = false
        crowScroll.addView(crow)
        col.addView(crowScroll)
        val save = accentBtn("Save countdown") {
            val title = titleIn.text.toString().trim()
            val date = cdDate
            if (title.isEmpty()) { toast("Give it a title"); return@accentBtn }
            if (!Dates.isValidDateStr(date)) { toast("Pick a valid date"); return@accentBtn }
            if (existing == null) {
                Store.addCountdown(CountdownItem(uid(), title, date, cdColor))
            } else {
                existing.title = title
                existing.targetDate = date
                existing.color = cdColor
                Store.save()
            }
            Sfx.add(); closeSheet(); render()
        }
        save.layoutParams = lp(t = dp(14f))
        col.addView(save)
        if (existing != null) {
            val del = tv("Delete countdown", 15f, Glass.overdue(this@MainActivity))
            del.gravity = Gravity.CENTER
            del.setPadding(dp(12f), dp(14f), dp(12f), dp(6f))
            del.setOnClickListener {
                val removed = Store.deleteCountdown(existing.id)
                closeSheet(); render()
                if (removed != null) showCountdownUndo(removed)
            }
            col.addView(del)
        }
        return col
    }

    /** Countdown date picker: the app's date-sheet grammar (quick
     *  rows + stacked month grids, modelled on buildQaDatePicker)
     *  driving [cdDate]. Picking a day returns to the countdown sheet
     *  with the new date in its date row. */
    private fun buildCountdownDatePicker(existing: CountdownItem?): View {
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
        col.addView(tv("Target date", 17f, Glass.text(this@MainActivity), true))
        val dayNames = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        fun quickRow(icon: String, lbl: String, date: String) {
            val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(4f), dp(12f), dp(4f), dp(12f))
            val ic = tv(icon, 16f, if (isV5()) V5.ACCENT else TD_RED)
            ic.layoutParams = LinearLayout.LayoutParams(dp(34f), -2)
            row.addView(ic)
            val tt = tv(lbl + if (cdDate == date) "  ✓" else "", 15f)
            tt.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            row.addView(tt)
            row.addView(tv(dayNames[Dates.jsDayOfWeek(Dates.parse(date))], 13f, Glass.muted(this@MainActivity)))
            row.setOnClickListener {
                cdDate = date; Sfx.tick()
                returnToSheet(buildCountdownSheet(existing))
            }
            col.addView(row)
        }
        quickRow("📅", "Today", Dates.today())
        quickRow("🌅", "Tomorrow", Dates.addDays(Dates.today(), 1))
        quickRow("⏭️", "+30 days", Dates.addDays(Dates.today(), 30))
        quickRow("📆", "+90 days", Dates.addDays(Dates.today(), 90))
        quickRow("🗓", "+1 year", Dates.addDays(Dates.today(), 365))
        val base = Dates.parse(cdDate)
        for (mOff in 0..2) {
            val mc = base.clone() as java.util.Calendar
            mc.set(java.util.Calendar.DAY_OF_MONTH, 1)
            mc.add(java.util.Calendar.MONTH, mOff)
            col.addView(cdMonthGrid(mc.get(java.util.Calendar.YEAR), mc.get(java.util.Calendar.MONTH), existing))
        }
        val back = ghostBtn("← Back") {
            if (!popSheet()) returnToSheet(buildCountdownSheet(existing))
        }
        back.layoutParams = lp(t = dp(10f))
        col.addView(back)
        return col
    }

    /** One month grid for the countdown picker (qaMonthGrid's shape,
     *  driven by cdDate instead of the Quick Add draft). */
    private fun cdMonthGrid(year: Int, month: Int, existing: CountdownItem?): View {
        val wrap = LinearLayout(this); wrap.orientation = LinearLayout.VERTICAL
        val monthNames = arrayOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
        val mt = tv(monthNames[month] + " " + year, 15f, Glass.text(this@MainActivity), true)
        mt.layoutParams = lp(t = dp(14f), b = dp(6f))
        wrap.addView(mt)
        val dowRow = LinearLayout(this); dowRow.orientation = LinearLayout.HORIZONTAL
        listOf("M", "T", "W", "T", "F", "S", "S").forEach { d ->
            val t = tv(d, 11f, Glass.muted(this@MainActivity), true); t.gravity = Gravity.CENTER
            t.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            dowRow.addView(t)
        }
        wrap.addView(dowRow)
        val first = java.util.Calendar.getInstance(); first.set(year, month, 1, 0, 0, 0)
        val startDow = (Dates.jsDayOfWeek(first) + 6) % 7 // Monday-first
        val dim = first.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
        var day = 1
        for (week in 0 until 6) {
            if (day > dim) break
            val rowL = LinearLayout(this); rowL.orientation = LinearLayout.HORIZONTAL
            for (dowI in 0 until 7) {
                val cell = LinearLayout(this); cell.orientation = LinearLayout.VERTICAL
                cell.gravity = Gravity.CENTER_HORIZONTAL
                cell.layoutParams = LinearLayout.LayoutParams(0, dp(46f), 1f)
                if (!(week == 0 && dowI < startDow) && day <= dim) {
                    val ds = year.toString() + "-" + (month + 1).toString().padStart(2, '0') + "-" + day.toString().padStart(2, '0')
                    val isToday = ds == Dates.today()
                    val isSel = ds == cdDate
                    val numWrap = FrameLayout(this)
                    numWrap.layoutParams = LinearLayout.LayoutParams(dp(30f), dp(30f))
                    if (isToday || isSel) numWrap.background = ovalDrawable(if (isV5()) V5.ACCENT else TD_RED)
                    val num = tv(day.toString(), 14f,
                        if (isToday || isSel) 0xFFFFFFFF.toInt()
                        else if (ds < Dates.today()) TD_GREY else Glass.text(this@MainActivity), isToday)
                    num.gravity = Gravity.CENTER
                    num.layoutParams = FrameLayout.LayoutParams(-1, -1)
                    numWrap.addView(num)
                    cell.addView(numWrap)
                    val dd = ds
                    cell.setOnClickListener {
                        cdDate = dd; Sfx.tick()
                        returnToSheet(buildCountdownSheet(existing))
                    }
                    day++
                }
                rowL.addView(cell)
            }
            wrap.addView(rowL)
        }
        return wrap
    }

    private fun showCountdownUndo(c: CountdownItem) {
        undoBar.removeAllViews()
        val lbl = tv("Deleted countdown \"" + c.title + "\"", 13f)
        lbl.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(10f) }
        undoBar.addView(lbl)
        val btn = tv("UNDO", 15f, Glass.gold(this@MainActivity), true)
        btn.setPadding(dp(8f), dp(10f), dp(8f), dp(10f))
        btn.setOnClickListener {
            Store.restoreCountdown(c)
            undoBar.visibility = View.GONE
            render(); toast("Restored ↩️")
        }
        undoBar.addView(btn)
        undoHide?.let { handler.removeCallbacks(it) }
        undoHide = Runnable { undoBar.visibility = View.GONE }
        undoBar.visibility = View.VISIBLE
        handler.postDelayed(undoHide!!, 5000)
    }

    /* ---------------- v5 stats pack (SHADOW-THREE §3) ---------------- */

    private fun statCardV5(big: String, small: String): LinearLayout {
        val c = LinearLayout(this); c.orientation = LinearLayout.VERTICAL
        c.background = v5CardBg(V5.R_TILE)
        c.setPadding(dp(14f), dp(14f), dp(14f), dp(14f))
        c.layoutParams = lp(b = dp(12f))
        c.addView(tv(big, 20f, V5.TEXT, true))
        val s = tv(small, 12f, V5.MUTED)
        s.layoutParams = lp(t = dp(2f))
        c.addView(s)
        return c
    }

    private fun renderStatsPack() {
        val today = Dates.today()
        val comps = Store.state.completions
        content.addView(dayGroup("INSIGHTS"))
        // Editorial caption: rule-based, first match wins (§3.5).
        val from30 = Dates.addDays(today, -29)
        val done30 = Db.outcomesInRange(from30, today, "done").size
        val missed30 = Db.outcomesInRange(from30, today, "missed").size
        val decided = done30 + missed30
        val rate = if (decided > 0) done30 * 100 / decided else null
        val streak = Stats.currentStreak(comps, today)
        val caption = when {
            streak >= 7 -> "Kept to your plan for " + streak + " days — this is the comeback working."
            rate != null && rate >= 80 -> "A strong month. Protect the streak."
            rate != null && rate >= 40 -> "Half the plan, all of the direction. Tighten one day at a time."
            rate != null -> "A light stretch — plenty of breathing room. One Must today changes it."
            else -> "Your plan starts with today's first tick."
        }
        val capCard = prodCard()
        capCard.addView(tv(caption, 15f, V5.TEXT, true))
        content.addView(capCard)

        // Annual heatmap with year pager (§3.1).
        val hm = prodCard()
        hm.addView(tv("Completions heatmap", 15f, V5.MUTED, true))
        val hrow = LinearLayout(this); hrow.orientation = LinearLayout.HORIZONTAL
        hrow.gravity = Gravity.CENTER_VERTICAL
        hrow.layoutParams = lp(t = dp(6f))
        val hprev = tv("‹", 22f, V5.TEXT, true)
        hprev.setPadding(dp(8f), dp(2f), dp(8f), dp(2f))
        hprev.setOnClickListener { statsYearSel--; heatCaption = ""; render() }
        val ht = tv(statsYearSel.toString(), 16f, V5.TEXT, true)
        ht.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        ht.gravity = Gravity.CENTER
        val curYear = Dates.parse(today).get(java.util.Calendar.YEAR)
        val hnext = tv("›", 22f, V5.TEXT, true)
        hnext.setPadding(dp(8f), dp(2f), dp(8f), dp(2f))
        hnext.alpha = if (statsYearSel >= curYear) 0.35f else 1f
        if (statsYearSel < curYear) hnext.setOnClickListener { statsYearSel++; heatCaption = ""; render() }
        hrow.addView(hprev); hrow.addView(ht); hrow.addView(hnext)
        hm.addView(hrow)
        val heat = HeatmapView(this)
        heat.setData(statsYearSel, Db.doneCountByDay(statsYearSel.toString() + "-01-01", statsYearSel.toString() + "-12-31"))
        heat.onCellTap = { d ->
            val n = Db.doneCountByDay(d, d)[d] ?: 0
            heatCaption = fmtMonDay(d) + " — " + n + " done"
            render()
        }
        heat.layoutParams = lp(t = dp(8f))
        hm.addView(heat)
        if (heatCaption.isNotEmpty()) {
            val hc = tv(heatCaption, 13f, V5.MUTED)
            hc.layoutParams = lp(t = dp(8f))
            hm.addView(hc)
        }
        content.addView(hm)

        // Daily Completed chart with week pager (§3.2).
        val dc = prodCard()
        dc.addView(tv("Daily completed", 15f, V5.MUTED, true))
        val drow = LinearLayout(this); drow.orientation = LinearLayout.HORIZONTAL
        drow.gravity = Gravity.CENTER_VERTICAL
        drow.layoutParams = lp(t = dp(6f))
        val dprev = tv("‹", 22f, V5.TEXT, true)
        dprev.setPadding(dp(8f), dp(2f), dp(8f), dp(2f))
        dprev.setOnClickListener { statsWeekStartSel = Dates.addDays(statsWeekStartSel, -7); render() }
        val dt = tv(fmtMonDay(statsWeekStartSel) + " – " + fmtMonDay(Dates.addDays(statsWeekStartSel, 6)), 15f, V5.TEXT, true)
        dt.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        dt.gravity = Gravity.CENTER
        val curWs = Stats.weekStart(today)
        val dnext = tv("›", 22f, V5.TEXT, true)
        dnext.setPadding(dp(8f), dp(2f), dp(8f), dp(2f))
        dnext.alpha = if (statsWeekStartSel >= curWs) 0.35f else 1f
        if (statsWeekStartSel < curWs) dnext.setOnClickListener { statsWeekStartSel = Dates.addDays(statsWeekStartSel, 7); render() }
        drow.addView(dprev); drow.addView(dt); drow.addView(dnext)
        dc.addView(drow)
        val byDay = Db.doneCountByDay(statsWeekStartSel, Dates.addDays(statsWeekStartSel, 6))
        val bars = DailyBarsView(this)
        bars.counts = (0..6).map { byDay[Dates.addDays(statsWeekStartSel, it)] ?: 0 }
        bars.fraction = 0f
        bars.layoutParams = lp(t = dp(6f))
        dc.addView(bars)
        content.addView(dc)
        bars.post {
            val anim = ValueAnimator.ofFloat(0f, 1f)
            anim.duration = 300
            anim.addUpdateListener { bars.fraction = it.animatedValue as Float; bars.invalidate() }
            anim.start()
        }

        // Most Productive Day + Completion Rate tiles (§3.3/§3.4).
        val byDow = IntArray(8)
        Db.doneCountByDay(Dates.addDays(today, -83), today).forEach { (d, n) -> byDow[Dates.dayOfWeek(d)] += n }
        var bestDow = 0
        var bestN = 0
        for (d in 1..7) if (byDow[d] > bestN) { bestN = byDow[d]; bestDow = d }
        val dowNames = arrayOf("", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
        val trow = LinearLayout(this); trow.orientation = LinearLayout.HORIZONTAL
        val mpd = statCardV5(if (bestDow == 0) "—" else dowNames[bestDow], "most productive day")
        mpd.layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(10f) }
        val crate = statCardV5(if (rate != null) rate.toString() + "%" else "No data yet", "completion rate · 30 days")
        crate.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        trow.addView(mpd); trow.addView(crate)
        content.addView(trow)

        // Tasks in Next 7 Days (§3.6).
        content.addView(sectionHeader("Tasks in Next 7 Days"))
        val upcoming = Store.sortTasks(Store.state.tasks.filter {
            it.dueDate != null && it.dueDate!! > today && it.dueDate!! <= Dates.addDays(today, 7)
        }).take(5)
        if (upcoming.isEmpty()) {
            content.addView(tv("Nothing scheduled in the next 7 days.", 13f, V5.MUTED))
        } else {
            upcoming.forEach { t ->
                val c = v5TaskCard(t, showProject = true, compact = true)
                c.layoutParams = lp(b = dp(10f))
                content.addView(c)
            }
        }
    }

    /* ---------------- v5 Browse (SHADOW-FOUR §3.4) ------------------- */

    private fun projectTile(p: ProjectItem, open: Int, wide: Boolean): LinearLayout {
        val color = try { android.graphics.Color.parseColor(p.color) } catch (e: Exception) { V5.FAINT }
        val done = Store.state.completions.count { it.projectId == p.id }
        val tile = LinearLayout(this); tile.orientation = LinearLayout.VERTICAL
        tile.background = roundedRect(V5.tint(color, 36), V5.R_CARD, V5.tint(color, 77))
        tile.setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
        if (wide) tile.layoutParams = lp(t = dp(2f))
        tile.addView(tv(p.name, 16f, V5.TEXT, true))
        val s = tv(open.toString() + " open · " + done + " done", 12f, V5.MUTED)
        s.layoutParams = lp(t = dp(4f))
        tile.addView(s)
        tile.setOnClickListener { sub = "project:" + p.id; render() }
        return tile
    }

    private fun renderBrowseV5() {
        // Profile header: karma ring avatar + name + bell/gear.
        val prof = LinearLayout(this); prof.orientation = LinearLayout.HORIZONTAL
        prof.gravity = Gravity.CENTER_VERTICAL
        prof.setPadding(0, dp(4f), 0, dp(12f))
        val avatarWrap = FrameLayout(this)
        avatarWrap.layoutParams = LinearLayout.LayoutParams(dp(44f), dp(44f)).apply { rightMargin = dp(12f) }
        val ring = FocusRing(this)
        ring.ringColor = V5.ACCENT
        ring.trackColor = V5.tint(V5.ACCENT, 46)
        ring.fraction = Karma.levelFor(Store.state.karmaPoints).progress
        ring.layoutParams = FrameLayout.LayoutParams(-1, -1)
        avatarWrap.addView(ring)
        val av = tv("S", 17f, V5.ACCENT_BRAND, true)
        av.gravity = Gravity.CENTER
        av.background = ovalDrawable(V5.ACCENT_TINT)
        av.layoutParams = FrameLayout.LayoutParams(dp(34f), dp(34f)).apply { gravity = Gravity.CENTER }
        avatarWrap.addView(av)
        prof.addView(avatarWrap)
        val nm = tv("Shadow", 20f, V5.TEXT, true)
        nm.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        prof.addView(nm)
        val bell = tv("🔔", 20f)
        bell.gravity = Gravity.CENTER
        bell.layoutParams = LinearLayout.LayoutParams(dp(40f), dp(40f))
        bell.contentDescription = "Activity"
        bell.setOnClickListener { sub = "history"; render() }
        prof.addView(bell)
        val gear = tv("⚙", 22f, V5.TEXT)
        gear.gravity = Gravity.CENTER
        gear.layoutParams = LinearLayout.LayoutParams(dp(40f), dp(40f))
        gear.contentDescription = "Settings"
        gear.setOnClickListener { showSheet(appearanceCard()) }
        prof.addView(gear)
        content.addView(prof)

        // Search ghost pill.
        val search = LinearLayout(this); search.orientation = LinearLayout.HORIZONTAL
        search.gravity = Gravity.CENTER_VERTICAL
        search.background = roundedRect(V5.CARD, 999f, V5.HAIRLINE)
        search.setPadding(dp(16f), 0, dp(16f), 0)
        search.layoutParams = lp(h = dp(48f), b = dp(6f))
        search.addView(tv("⌕", 17f, V5.MUTED))
        val st = tv("Search tasks", 14f, V5.PLACEHOLDER)
        st.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8f) }
        search.addView(st)
        search.setOnClickListener { sub = "search"; render() }
        content.addView(search)

        // Project mosaic: busiest project wide, the rest 2-up, each
        // tile tinted with its project colour.
        val pHead = LinearLayout(this); pHead.orientation = LinearLayout.HORIZONTAL
        pHead.gravity = Gravity.CENTER_VERTICAL
        pHead.layoutParams = lp(t = dp(14f), b = dp(10f))
        val pT = tv("Projects", 20f, V5.TEXT, true)
        pT.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        pHead.addView(pT)
        val pAdd = tv("+", 22f, V5.TEXT)
        pAdd.setPadding(dp(10f), dp(2f), dp(6f), dp(2f))
        pAdd.contentDescription = "New project"
        pAdd.setOnClickListener { promptText("Project name:") { name -> Store.projByName(name); Store.save(); render() } }
        pHead.addView(pAdd)
        content.addView(pHead)
        val projects = Store.state.projects
            .map { p -> p to Store.state.tasks.count { it.projectId == p.id } }
            .sortedByDescending { it.second }
        if (projects.isEmpty()) {
            content.addView(tv("No projects yet — tap + to add one.", 13f, V5.MUTED))
        } else {
            content.addView(projectTile(projects[0].first, projects[0].second, wide = true))
            var i = 1
            while (i < projects.size) {
                val rowL = LinearLayout(this); rowL.orientation = LinearLayout.HORIZONTAL
                rowL.layoutParams = lp(t = dp(10f))
                val a = projectTile(projects[i].first, projects[i].second, wide = false)
                a.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                rowL.addView(a)
                if (i + 1 < projects.size) {
                    val b = projectTile(projects[i + 1].first, projects[i + 1].second, wide = false)
                    b.layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(10f) }
                    rowL.addView(b)
                    i += 2
                } else i += 1
                content.addView(rowL)
            }
        }
        val newTile = LinearLayout(this); newTile.orientation = LinearLayout.HORIZONTAL
        newTile.gravity = Gravity.CENTER
        newTile.background = roundedRect(V5.CARD, V5.R_CARD, V5.HAIRLINE)
        newTile.setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
        newTile.layoutParams = lp(t = dp(10f))
        newTile.addView(tv("＋ New project", 14f, V5.MUTED, true))
        newTile.setOnClickListener { promptText("Project name:") { name -> Store.projByName(name); Store.save(); render() } }
        content.addView(newTile)

        // Modules container — every module is reachable here even when
        // it is not in the dock (§7).
        content.addView(dayGroup("MODULES"))
        content.addView(groupedMenuCard(listOf(
            browseRow("⭐", V5.P2, "Starred", Store.state.tasks.count { it.starred }.toString()) { sub = "starred"; render() },
            browseRow("▦", V5.ACCENT_BRAND, "Filters & Labels", null) { sub = "filterslabels"; render() },
            browseRow("▤", V5.ACCENT_BRAND, "Templates", null) { showSheet(buildTemplatesSheet()) },
            browseRow("🔥", V5.ACCENT_BRAND, "Habits", null) { sub = "habits"; render() },
            browseRow("⊞", V5.ACCENT_BRAND, "Matrix", null) { sub = "matrix"; render() },
            browseRow("⏳", V5.ACCENT_BRAND, "Countdowns", null) { sub = "countdowns"; render() },
            browseRow("◎", V5.ACCENT_BRAND, "Focus", null) { sub = "focus"; render() },
            browseRow("🤖", V5.ACCENT_BRAND, "Smart Plan", null) { sub = "smartplan"; render() },
            browseRow("🕘", V5.ACCENT_BRAND, "Reporting", null) { sub = "history"; render() },
            browseRow("💾", V5.ACCENT_BRAND, "Backups & Sync", null) { sub = "backups"; render() }
        )))
        content.addView(dayGroup("SETTINGS"))
        content.addView(groupedMenuCard(listOf(
            browseRow("▤", V5.TEXT, "Display", null) { showDisplaySheet() },
            browseRow("▦", V5.TEXT, "Dock modules", null) { sub = "dock"; render() },
            browseRow("⚙", V5.TEXT, "Appearance & sounds", null) { showSheet(appearanceCard()) },
            browseRow("?", V5.TEXT, "Help & resources", null) { showSheet(buildHelpSheet()) }
        )))
        val about = LinearLayout(this); about.orientation = LinearLayout.VERTICAL
        about.background = v5CardBg()
        about.setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
        about.layoutParams = lp(t = dp(12f), b = dp(10f))
        about.addView(tv("GhostBlade Task", 16f, V5.TEXT, true))
        val ab = tv("Kotlin edition · data lives on this device · reminders fire even when the app is closed and survive reboot.", 13f, V5.MUTED)
        ab.layoutParams = lp(t = dp(4f))
        about.addView(ab)
        content.addView(about)
    }

    /* ---------------- Quick Add templates sheet (§8.3) --------------- */

    private fun buildQaTemplates(): View {
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
        col.addView(tv("Templates", 17f, Glass.text(this@MainActivity), true))
        col.addView(dayGroup("CHECKLISTS"))
        if (Store.state.checklistTemplates.isEmpty()) {
            col.addView(tv("No checklist templates yet.", 13f, Glass.muted(this@MainActivity)))
        }
        Store.state.checklistTemplates.forEach { tpl ->
            val row = tv("▤  " + tpl.name + "  ·  " + tpl.items.size + " items", 15f)
            row.setPadding(dp(4f), dp(12f), dp(4f), dp(12f))
            row.setOnClickListener {
                val pid = if (qaProjectSet) qaProjectId
                    else qaPreviewParse().projectName?.let { Store.projByName(it).id }
                val t = Store.applyChecklistTemplate(tpl.id, pid)
                if (t != null) { Sfx.add(); toast("Checklist added as a task") }
                returnToQuickAdd()
                render()
            }
            col.addView(row)
        }
        col.addView(dayGroup("PROJECT TEMPLATES"))
        if (Store.state.templates.isEmpty()) {
            col.addView(tv("No project templates yet.", 13f, Glass.muted(this@MainActivity)))
        }
        Store.state.templates.forEach { tpl ->
            val row = tv("▤  " + tpl.name + "  →  " + tpl.projectName, 15f)
            row.setPadding(dp(4f), dp(12f), dp(4f), dp(12f))
            row.setOnClickListener {
                val np = Store.applyTemplate(tpl.id)
                closeSheet()
                if (np != null) { sub = "project:" + np.id; toast("Project created from template") }
                render()
            }
            col.addView(row)
        }
        val back = ghostBtn("← Back") { if (!popSheet()) returnToQuickAdd() }
        back.layoutParams = lp(t = dp(10f))
        col.addView(back)
        return col
    }

    private fun renderCalendar() {
        val year = calCursor.get(java.util.Calendar.YEAR)
        val month = calCursor.get(java.util.Calendar.MONTH)
        val monthNames = arrayOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
        val head = LinearLayout(this); head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val prev = tv("‹", 26f, Glass.text(this@MainActivity), true); prev.setPadding(dp(10f), dp(2f), dp(10f), dp(2f))
        prev.setOnClickListener { calCursor.add(java.util.Calendar.MONTH, -1); render() }
        val title = tv("${monthNames[month]} $year", 17f, Glass.text(this@MainActivity), true)
        title.layoutParams = LinearLayout.LayoutParams(0, -2, 1f); title.gravity = Gravity.CENTER
        // §3.6.1: month + year at T_TITLE under v5.
        if (isV5()) title.textSize = 24f
        val next = tv("›", 26f, Glass.text(this@MainActivity), true); next.setPadding(dp(10f), dp(2f), dp(10f), dp(2f))
        next.setOnClickListener { calCursor.add(java.util.Calendar.MONTH, 1); render() }
        head.addView(prev); head.addView(title); head.addView(next)
        if (isV5()) {
            // §3.6.1: "Today" is a ghost chip (POPUP pill), not chipBtn.
            val todayChip = tv("Today", 13f, V5.TEXT, true)
            todayChip.gravity = Gravity.CENTER
            todayChip.background = roundedRect(V5.POPUP, 999f, V5.HAIRLINE)
            todayChip.setPadding(dp(14f), dp(7f), dp(14f), dp(7f))
            todayChip.contentDescription = "Jump to today"
            todayChip.setOnClickListener {
                calCursor = java.util.Calendar.getInstance()
                selDay = Dates.today()
                Sfx.tick(); render()
            }
            todayChip.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8f) }
            head.addView(todayChip)
        }
        content.addView(head)

        val calCard = card()
        val dow = LinearLayout(this); dow.orientation = LinearLayout.HORIZONTAL
        listOf("M", "T", "W", "T", "F", "S", "S").forEach { d ->
            val t = tv(d, 11f, Glass.muted(this@MainActivity), true); t.gravity = Gravity.CENTER
            t.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            dow.addView(t)
        }
        calCard.addView(dow)
        val first = java.util.Calendar.getInstance()
        first.set(year, month, 1, 0, 0, 0)
        val startDow = (Dates.jsDayOfWeek(first) + 6) % 7
        val daysInMonth = first.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
        var day = 1
        for (week in 0 until 6) {
            if (day > daysInMonth) break
            val rowL = LinearLayout(this); rowL.orientation = LinearLayout.HORIZONTAL
            for (dowI in 0 until 7) {
                val cell = tv("", 13f); cell.gravity = Gravity.CENTER
                cell.layoutParams = LinearLayout.LayoutParams(0, dp(44f), 1f)
                if (week == 0 && dowI < startDow || day > daysInMonth) {
                    // blank cell
                } else {
                    val ds = year.toString() + "-" + (month + 1).toString().padStart(2, '0') + "-" + day.toString().padStart(2, '0')
                    val n = Store.state.tasks.count { it.dueDate == ds }
                    cell.text = day.toString() + if (n > 0) "\n" + "•".repeat(minOf(n, 3)) else ""
                    cell.textSize = 13f
                    cell.setTextColor(if (Dates.isOverdue(TaskItem(id = "", title = "", dueDate = ds))) Glass.overdue(this@MainActivity) else Glass.text(this@MainActivity))
                    when {
                        ds == selDay -> {
                            if (isV5()) {
                                // Selected day = solid accent circle, white
                                // number (SHADOW-FOUR §3.6).
                                cell.background = ovalDrawable(V5.ACCENT)
                                cell.setTextColor(0xFFFFFFFF.toInt())
                            } else cell.background = Glass.chip(this, true)
                        }
                        ds == Dates.today() -> {
                            val g = android.graphics.drawable.GradientDrawable()
                            g.cornerRadius = dp(12f).toFloat()
                            g.setStroke(dp(1.5f), if (isV5()) V5.ACCENT
                                else if (Glass.isWhite(this@MainActivity)) Accents.acc2(this@MainActivity) else Accents.base(this@MainActivity))
                            cell.background = g
                        }
                    }
                    val dd = ds
                    cell.setOnClickListener { Sfx.tick(); selDay = dd; render() }
                    day++
                }
                rowL.addView(cell)
            }
            calCard.addView(rowL)
        }
        content.addView(calCard)
        val sd = selDay
        if (sd != null) {
            if (isV5()) {
                // §3.6.3: the selected-day label is T_SECTION.
                val dl = tv(fmtDayLong(sd), 20f, V5.TEXT, true)
                dl.layoutParams = lp(t = dp(16f), b = dp(8f))
                content.addView(dl)
            } else {
                content.addView(dayGroup((if (sd == Dates.today()) "TODAY · " else "") + Dates.fmtDay(sd).uppercase()))
            }
            val list = Store.sortTasks(Store.state.tasks.filter { it.dueDate == sd })
            if (list.isEmpty()) content.addView(emptyView("🌤️",
                if (isV5()) "A free day — take it easy, or plan something." else "Nothing scheduled this day."))
            else list.forEach { content.addView(taskRow(it)) }
            val add = accentBtn("＋ Add task on this day") { openQuickAdd(sd) }
            content.addView(add)
        } else {
            content.addView(tv("Tap a day to see its tasks. Dots = scheduled tasks.", 13f, Glass.muted(this@MainActivity)))
        }
    }

    /* ----- Focus timer ----- */

    private fun fmtClock(sec: Int): String =
        (sec / 60).toString().padStart(2, '0') + ":" + (sec % 60).toString().padStart(2, '0')

    private fun renderFocus() {
        val c = card()
        c.addView(tv("Focus on", 13f, Glass.muted(this@MainActivity), true))
        val names = listOf("— pick a task —") + Store.state.tasks.map { it.title }
        val ids = listOf<String?>(null) + Store.state.tasks.map { it.id }
        val sp = spinner(names, ids.indexOf(focusTaskId).coerceAtLeast(0))
        sp.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                // Fires on first layout too — only react to real changes, and
                // never render() from here (that rebuilds the spinner -> loop).
                val newId = ids[pos]
                if (newId == focusTaskId) return
                focusTaskId = newId
                val t = newId?.let { Store.byId(it) }
                if (t?.durationMin != null && !focusRunning) {
                    focusTotal = t.durationMin!! * 60
                    focusLeft = focusTotal
                    focusTimeText?.text = fmtClock(focusLeft)
                    focusRing?.fraction = 1f
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        c.addView(sp)
        val chipsRow = LinearLayout(this); chipsRow.orientation = LinearLayout.HORIZONTAL
        listOf(10, 25, 45, 60).forEach { m ->
            val on = focusTotal == m * 60
            val ch = if (isV5()) {
                // §2.8 sheet-chip grammar: on = ACCENT_TINT fill +
                // ACCENT_BRAND text + ACCENT_TINT_DEEP border; off =
                // POPUP + MUTED (Glass.sheetChip's v5 branch).
                val c = tv(m.toString() + "m", 13f, if (on) V5.ACCENT_BRAND else V5.MUTED, on)
                c.background = Glass.sheetChip(this, on)
                c.setPadding(dp(11f), dp(8f), dp(11f), dp(8f))
                c.setOnClickListener {
                    focusTotal = m * 60
                    if (!focusRunning) focusLeft = focusTotal
                    render()
                }
                c
            } else chipBtn(m.toString() + "m", on) {
                focusTotal = m * 60
                if (!focusRunning) focusLeft = focusTotal
                render()
            }
            ch.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6f) }
            chipsRow.addView(ch)
        }
        c.addView(chipsRow)

        val ringWrap = FrameLayout(this)
        ringWrap.layoutParams = lp(t = dp(16f), b = dp(6f))
        val ring = FocusRing(this)
        ring.ringColor = Accents.base(this)
        ring.trackColor = if (Glass.isWhite(this)) android.graphics.Color.argb(28, 20, 20, 35) else android.graphics.Color.argb(30, 255, 255, 255)
        ring.fraction = if (focusTotal > 0) focusLeft.toFloat() / focusTotal else 0f
        ring.layoutParams = FrameLayout.LayoutParams(dp(190f), dp(190f)).apply { gravity = Gravity.CENTER }
        focusRing = ring
        ringWrap.addView(ring)
        val timeT = tv(fmtClock(focusLeft), 40f, Glass.text(this@MainActivity), true)
        timeT.gravity = Gravity.CENTER
        timeT.layoutParams = FrameLayout.LayoutParams(-1, -1)
        focusTimeText = timeT
        ringWrap.addView(timeT)
        c.addView(ringWrap)
        val stateT = tv(if (focusRunning) "Focusing…" else "Ready when you are", 13f, Glass.muted(this@MainActivity))
        stateT.gravity = Gravity.CENTER
        focusStateText = stateT
        c.addView(stateT)

        val btnRow = LinearLayout(this); btnRow.orientation = LinearLayout.HORIZONTAL
        btnRow.layoutParams = lp(t = dp(12f))
        val main = accentBtn(if (focusRunning) "⏸ Pause" else "▶ Start focus") {
            if (focusRunning) pauseFocus() else startFocus()
            render()
        }
        main.layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(8f) }
        val stop = ghostBtn("⏹ Stop") {
            pauseFocus(); focusLeft = focusTotal; render()
        }
        stop.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        btnRow.addView(main); btnRow.addView(stop)
        c.addView(btnRow)
        content.addView(c)
        content.addView(tv("Tip: set a task duration in its editor and the timer picks it up automatically. Leaving this screen pauses the timer.", 13f, Glass.muted(this@MainActivity)))
    }

    private fun startFocus() {
        focusRunning = true
        focusEndAt = System.currentTimeMillis() + focusLeft * 1000L
        focusTicker?.let { handler.removeCallbacks(it) }
        val ticker = object : Runnable {
            override fun run() {
                focusLeft = Math.ceil((focusEndAt - System.currentTimeMillis()) / 1000.0).toInt().coerceAtLeast(0)
                focusTimeText?.text = fmtClock(focusLeft)
                focusRing?.fraction = if (focusTotal > 0) focusLeft.toFloat() / focusTotal else 0f
                if (focusLeft <= 0) {
                    focusRunning = false
                    focusLeft = focusTotal
                    Store.logActivity("focus", "Focus session finished")
                    Store.save()
                    Sfx.complete()
                    if (Build.VERSION.SDK_INT >= 26) {
                        try {
                            val vib = getSystemService(VIBRATOR_SERVICE) as? android.os.Vibrator
                            vib?.vibrate(android.os.VibrationEffect.createOneShot(250, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                        } catch (e: Exception) {}
                    }
                    toast("Focus complete 🎉")
                    if (sub == "focus") render()
                    return
                }
                handler.postDelayed(this, 250)
            }
        }
        focusTicker = ticker
        handler.postDelayed(ticker, 250)
    }

    private fun pauseFocus() {
        if (focusRunning) {
            focusLeft = Math.ceil((focusEndAt - System.currentTimeMillis()) / 1000.0).toInt().coerceAtLeast(0)
            focusRunning = false
        }
        focusTicker?.let { handler.removeCallbacks(it) }
        focusTicker = null
    }

    /* ----- Stats ----- */

    private fun statCard(big: String, smallTxt: String): LinearLayout {
        val c = card(13)
        c.addView(tv(big, 22f, Glass.text(this@MainActivity), true))
        c.addView(tv(smallTxt, 13f, Glass.muted(this@MainActivity)))
        return c
    }

    /* ----- Productivity (Todoist shape, B2) ----- */

    private fun prodCard(): LinearLayout {
        val c = LinearLayout(this); c.orientation = LinearLayout.VERTICAL
        c.background = roundedRect(cardFill(), 16f)
        c.setPadding(dp(20f), dp(18f), dp(20f), dp(18f))
        c.layoutParams = lp(b = dp(12f))
        return c
    }

    /** One horizontal chart row (ANALYSIS §7): a GREY bar proportional
     *  to count/max — or only a thin axis tick when the count is zero —
     *  the right-side axis segment, and the label AFTER the bar. */
    private fun prodBarRow(count: Int, max: Int, labelText: String, labelWidthDp: Float): View {
        val frame = FrameLayout(this)
        frame.layoutParams = lp(t = dp(5f), b = dp(5f))
        val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val zone = LinearLayout(this); zone.orientation = LinearLayout.HORIZONTAL
        zone.gravity = Gravity.CENTER_VERTICAL
        zone.layoutParams = LinearLayout.LayoutParams(0, dp(16f), 1f)
        if (count <= 0) {
            val tick = View(this)
            tick.background = roundedRect(Todoist.CHART_BAR, 1f)
            tick.layoutParams = LinearLayout.LayoutParams(dp(2f), dp(14f))
            zone.addView(tick)
            val spacer = View(this)
            spacer.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            zone.addView(spacer)
        } else {
            val bar = View(this)
            bar.background = roundedRect(Todoist.CHART_BAR, 2f)
            bar.layoutParams = LinearLayout.LayoutParams(0, -1, count.toFloat())
            zone.addView(bar)
            val rest = max - count
            if (rest > 0) {
                val spacer = View(this)
                spacer.layoutParams = LinearLayout.LayoutParams(0, -2, rest.toFloat())
                zone.addView(spacer)
            }
        }
        row.addView(zone)
        val lbl = tv(labelText, 14f, TD_GREY)
        lbl.layoutParams = LinearLayout.LayoutParams(dp(labelWidthDp), -2).apply { leftMargin = dp(10f) }
        row.addView(lbl)
        frame.addView(row)
        val axis = View(this)
        axis.setBackgroundColor(android.graphics.Color.argb(90, 255, 255, 255))
        val ap = FrameLayout.LayoutParams(1, -1)
        ap.gravity = Gravity.END
        ap.rightMargin = dp(labelWidthDp) + dp(10f)
        axis.layoutParams = ap
        frame.addView(axis)
        return frame
    }

    /** Goal card: big "x/goal tasks", supportive line, salmon Edit goal,
     *  and the progress ring (arc from 12 o'clock, clockwise) with its
     *  centre graphic — GREY medal (Daily) / GREY trophy (Weekly) /
     *  RED pin (Karma, via renderStats' own card). */
    private fun goalRingCard(title: String, done: Int, goal: Int, fraction: Float, message: String, centreKind: Int, edit: () -> Unit): LinearLayout {
        val c = prodCard()
        c.addView(tv(title, 17f, Glass.text(this@MainActivity), true))
        val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.layoutParams = lp(t = dp(14f))
        val left = LinearLayout(this); left.orientation = LinearLayout.VERTICAL
        left.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        val big = LinearLayout(this); big.orientation = LinearLayout.HORIZONTAL
        big.gravity = Gravity.BOTTOM
        big.addView(tv("$done/$goal", 22f, Glass.text(this@MainActivity), true))
        val unit = tv(" tasks", 17f)
        unit.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(1f) }
        big.addView(unit)
        left.addView(big)
        val msg = tv(message, 15f, 0xFFC8C8C8.toInt())
        msg.layoutParams = lp(t = dp(6f))
        left.addView(msg)
        val editT = tv("Edit goal", 15f, TD_SALMON)
        editT.setPadding(0, dp(6f), dp(8f), 0)
        editT.contentDescription = "Edit goal"
        editT.setOnClickListener { edit() }
        left.addView(editT)
        row.addView(left)
        val ring = GoalRingView(this, fraction, TD_RED, Todoist.RING_TRACK, centreKind)
        ring.layoutParams = LinearLayout.LayoutParams(dp(88f), dp(88f))
        ring.contentDescription = title + " progress ring"
        row.addView(ring)
        c.addView(row)
        return c
    }

    private fun showEditGoalSheet(daily: Boolean) {
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
        col.addView(tv(if (daily) "Daily goal" else "Weekly goal", 17f, Glass.text(this@MainActivity), true))
        val inp = input("Tasks per " + if (daily) "day" else "week",
            (if (daily) Store.state.dailyGoal else Store.state.weeklyGoal).toString())
        inp.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        col.addView(inp)
        val save = accentBtn("Save goal") {
            val n = inp.text.toString().trim().toIntOrNull()
            if (n == null || n < 1) { toast("Enter a number from 1 to 999"); return@accentBtn }
            if (daily) Store.setDailyGoal(n) else Store.setWeeklyGoal(n)
            Sfx.tick(); closeSheet(); render()
        }
        col.addView(save)
        showSheet(col)
    }

    /** Segmented control (§2.10): TRACK pill, selected segment one
     *  step lighter (#2E2E35) with TEXT, unselected MUTED — selection
     *  by lightness, never accent. */
    private fun v5Segmented(options: List<Pair<String, String>>, selected: String,
                            onPick: (String) -> Unit): LinearLayout {
        val track = LinearLayout(this); track.orientation = LinearLayout.HORIZONTAL
        track.background = roundedRect(V5.TRACK, 999f)
        track.setPadding(dp(3f), dp(3f), dp(3f), dp(3f))
        track.layoutParams = LinearLayout.LayoutParams(-1, dp(36f))
        options.forEach { (id, lbl) ->
            val on = id == selected
            val seg = tv(lbl, 13f, if (on) V5.TEXT else V5.MUTED, on)
            seg.gravity = Gravity.CENTER
            if (on) seg.background = roundedRect(0xFF2E2E35.toInt(), 999f)
            seg.layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
            seg.setOnClickListener { if (!on) onPick(id) }
            track.addView(seg)
        }
        return track
    }

    /** One §3.7.4 summary tile: caps label, T_NUMBER value, optional
     *  delta/sub line. */
    private fun v5SummaryTile(labelText: String, value: String, sub: String?): LinearLayout {
        val t = LinearLayout(this); t.orientation = LinearLayout.VERTICAL
        t.background = v5CardBg(V5.R_TILE)
        t.setPadding(dp(14f), dp(14f), dp(14f), dp(14f))
        val lb = tv(labelText.uppercase(), 11f, V5.MUTED, true)
        lb.letterSpacing = 0.06f
        t.addView(lb)
        val v = tv(value, 24f, V5.TEXT, true)
        v.layoutParams = lp(t = dp(6f))
        t.addView(v)
        if (sub != null) {
            val s = tv(sub, 11f, V5.MUTED)
            s.layoutParams = lp(t = dp(2f))
            t.addView(s)
        }
        return t
    }

    /** One §3.7.5 goal-ring tile: number-centred ring + label + sub. */
    private fun v5GoalRingTile(labelText: String, fraction: Float, arc: Int, sub: String,
                               click: () -> Unit): LinearLayout {
        val t = LinearLayout(this); t.orientation = LinearLayout.VERTICAL
        t.gravity = Gravity.CENTER_HORIZONTAL
        t.background = v5CardBg(V5.R_TILE)
        t.setPadding(dp(14f), dp(14f), dp(14f), dp(14f))
        val ring = V5RingView(this)
        ring.fraction = fraction
        ring.arcColor = arc
        ring.layoutParams = LinearLayout.LayoutParams(dp(64f), dp(64f))
        t.addView(ring)
        val lb = tv(labelText, 12f, V5.TEXT, true)
        lb.layoutParams = lp(t = dp(8f))
        t.addView(lb)
        t.addView(tv(sub, 11f, V5.MUTED))
        t.setOnClickListener { click() }
        return t
    }

    /** Monthly completion buckets, oldest → newest, from the earliest
     *  completion's month through the current month (capped at 36). */
    private fun monthlySeries(comps: List<CompletionItem>, today: String): List<Pair<String, Int>> {
        val byMonth = HashMap<String, Int>()
        comps.forEach { c -> val k = c.date.substring(0, 7); byMonth[k] = (byMonth[k] ?: 0) + 1 }
        val startKey = (comps.minOfOrNull { it.date } ?: today).substring(0, 7)
        val endKey = today.substring(0, 7)
        val out = mutableListOf<Pair<String, Int>>()
        var cur = startKey + "-01"
        while (cur.substring(0, 7) <= endKey && out.size < 36) {
            val k = cur.substring(0, 7)
            out.add(k to (byMonth[k] ?: 0))
            val cal = Dates.parse(cur)
            cal.add(java.util.Calendar.MONTH, 1)
            cur = Dates.dstr(cal)
        }
        return out
    }

    private fun movingAvg(values: List<Int>, window: Int): List<Float> =
        values.mapIndexed { i, _ ->
            val from = maxOf(0, i - window + 1)
            values.subList(from, i + 1).average().toFloat()
        }

    /** Productivity, v5 (§3.7): range segmented control → Canvas
     *  line+bar chart card → 2×2 summary grid (with THE accent-filled
     *  Karma tile) → goal rings row → Shadow Three's stats pack below.
     *  The Todoist-shaped Daily/Weekly/Karma tabs below this function
     *  remain the rollback skins' screen (CHECK1 P2-4). */
    private fun renderStatsV5() {
        val today = Dates.today()
        val comps = Store.state.completions
        val seg = v5Segmented(
            listOf("week" to "Week", "month" to "Month", "year" to "Year", "all" to "All Time"),
            statsRange) { statsRange = it; Sfx.tick(); render() }
        seg.layoutParams = LinearLayout.LayoutParams(-1, dp(36f)).apply { topMargin = dp(4f) }
        content.addView(seg)

        val series: List<Pair<String, Int>> = when (statsRange) {
            "month" -> Stats.lastNDaysCounts(comps, today, 30).reversed()
            "year" -> Stats.lastNWeeksCounts(comps, today, 52).reversed()
            "all" -> monthlySeries(comps, today)
            else -> Stats.lastNDaysCounts(comps, today, 7).reversed()
        }
        val values = series.map { it.second }
        val monAbbr = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        val dayLetters = arrayOf("S", "M", "T", "W", "T", "F", "S")
        val labels: List<String> = when (statsRange) {
            "month" -> series.mapIndexed { i, (d, _) ->
                val dom = Dates.parse(d).get(java.util.Calendar.DAY_OF_MONTH)
                if (i % 5 == 0 || i == series.size - 1) dom.toString() else ""
            }
            "year" -> series.mapIndexed { i, (d, _) ->
                val m = Dates.parse(d).get(java.util.Calendar.MONTH)
                val prevM = if (i == 0) -1 else Dates.parse(series[i - 1].first).get(java.util.Calendar.MONTH)
                if (m != prevM) monAbbr[m] else ""
            }
            "all" -> series.mapIndexed { i, (k, _) ->
                val m = k.substring(5, 7).toInt() - 1
                if (m == 0 || i == 0) monAbbr[m] + " '" + k.substring(2, 4) else monAbbr[m]
            }
            else -> series.map { (d, _) -> dayLetters[Dates.jsDayOfWeek(Dates.parse(d))] }
        }
        // Trend line: for Week it is Karma.trend() itself (§3.7.3's
        // named source — the same 7 values as the bars); longer ranges
        // smooth the bars into a trend with a moving average.
        val line: List<Float> = when (statsRange) {
            "month" -> movingAvg(values, 5)
            "year" -> movingAvg(values, 4)
            "all" -> movingAvg(values, 3)
            else -> Karma.trend(comps, today).reversed().map { it.toFloat() }
        }
        val chartCard = LinearLayout(this); chartCard.orientation = LinearLayout.VERTICAL
        chartCard.background = v5CardBg()
        chartCard.setPadding(dp(16f), dp(14f), dp(16f), dp(12f))
        chartCard.layoutParams = lp(t = dp(16f))
        val chart = StatsChartView(this)
        chart.bars = values
        chart.line = line
        chart.labels = labels
        chartCard.addView(chart)
        content.addView(chartCard)

        // Summary 2×2 (§3.7.4).
        val total = values.sum()
        val deltaTxt: String? = when (statsRange) {
            "week", "month" -> {
                val days = if (statsRange == "week") 7 else 30
                val prev = comps.count {
                    it.date >= Dates.addDays(today, -2 * days + 1) && it.date < Dates.addDays(today, -days + 1)
                }
                val d = total - prev
                val lbl = if (statsRange == "week") "last week" else "last month"
                when { d > 0 -> "+$d vs $lbl"; d < 0 -> "$d vs $lbl"; else -> "Same as $lbl" }
            }
            "year" -> {
                val thisMonday = Stats.weekStart(today)
                val prev = comps.count {
                    it.date >= Dates.addDays(thisMonday, -7 * 104) && it.date < Dates.addDays(thisMonday, -7 * 52)
                }
                val d = total - prev
                when { d > 0 -> "+$d vs last year"; d < 0 -> "$d vs last year"; else -> "Same as last year" }
            }
            else -> "all time"
        }
        val grid = LinearLayout(this); grid.orientation = LinearLayout.VERTICAL
        grid.layoutParams = lp(t = dp(12f))
        val rowA = LinearLayout(this); rowA.orientation = LinearLayout.HORIZONTAL
        val completedTile = v5SummaryTile("Completed", total.toString(), deltaTxt)
        completedTile.layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(12f) }
        rowA.addView(completedTile)
        // §3.7.4's second tile is "Focus time" — but the app keeps NO
        // focus-session data anywhere (Models/Store/Db have no focus
        // totals; the timer is ephemeral), so the tile honestly shows
        // Best streak from the on-device Db instead of a fake number.
        val streakTile = v5SummaryTile("Best streak", Db.bestStreak().toString(), "days · all time")
        streakTile.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        rowA.addView(streakTile)
        grid.addView(rowA)
        val rowB = LinearLayout(this); rowB.orientation = LinearLayout.HORIZONTAL
        rowB.layoutParams = lp(t = dp(12f))
        val dayStreakTile = v5SummaryTile("Day streak", Stats.currentStreak(comps, today).toString(), "days in a row")
        dayStreakTile.layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(12f) }
        rowB.addView(dayStreakTile)
        // Karma — THE one accent-filled tile (§1.3 budgeted slot).
        val info = Karma.levelFor(Store.state.karmaPoints)
        val kt = LinearLayout(this); kt.orientation = LinearLayout.VERTICAL
        kt.background = roundedRect(V5.ACCENT, V5.R_TILE)
        kt.setPadding(dp(14f), dp(14f), dp(14f), dp(14f))
        kt.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        val klb = tv("KARMA", 11f, android.graphics.Color.argb(166, 255, 255, 255), true)
        klb.letterSpacing = 0.06f
        kt.addView(klb)
        val kv = tv(info.name, 24f, 0xFFFFFFFF.toInt(), true)
        kv.layoutParams = lp(t = dp(6f))
        kt.addView(kv)
        val ptsStr = Store.state.karmaPoints.toString()
        val suffix = if (info.nextName != null) " pts · " + info.pointsToNext + " to " + info.nextName
            else " pts · top level"
        val span = android.text.SpannableString(ptsStr + suffix)
        span.setSpan(android.text.style.ForegroundColorSpan(android.graphics.Color.argb(166, 255, 255, 255)),
            ptsStr.length, ptsStr.length + suffix.length, 0)
        val ksub = tv("", 11f, 0xFFFFFFFF.toInt())
        ksub.text = span
        ksub.layoutParams = lp(t = dp(2f))
        kt.addView(ksub)
        rowB.addView(kt)
        grid.addView(rowB)
        content.addView(grid)

        // Goal rings row (§3.7.5): daily ring ACCENT, weekly DONE.
        val gp = Stats.goalProgress(comps, today, Store.state.dailyGoal, Store.state.weeklyGoal)
        val rings = LinearLayout(this); rings.orientation = LinearLayout.HORIZONTAL
        rings.layoutParams = lp(t = dp(12f))
        val dailyTile = v5GoalRingTile("Daily goal", gp.dailyFraction, V5.ACCENT,
            if (gp.dailyGoal > 0) gp.dailyDone.toString() + " of " + gp.dailyGoal + " today"
            else "No goal set — tap to set") { showEditGoalSheet(true) }
        dailyTile.layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(12f) }
        rings.addView(dailyTile)
        val weeklyTile = v5GoalRingTile("Weekly goal", gp.weeklyFraction, V5.DONE,
            if (gp.weeklyGoal > 0) gp.weeklyDone.toString() + " of " + gp.weeklyGoal + " this week"
            else "No goal set — tap to set") { showEditGoalSheet(false) }
        weeklyTile.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        rings.addView(weeklyTile)
        content.addView(rings)

        // Shadow Three's stats pack (heatmap, daily chart, productive
        // day, completion rate, next 7 days) stays reachable below.
        renderStatsPack()
    }

    private fun renderStats() {
        if (isV5()) { renderStatsV5(); return }
        // Two-tone shell: the app header stays on #212121; the content
        // area is the warm brown Todoist.PROD_BG (ANALYSIS §7).
        content.setBackgroundColor(Todoist.PROD_BG)
        val today = Dates.today()
        val comps = Store.state.completions

        // Profile block — on the brown background, no card.
        val prof = LinearLayout(this); prof.orientation = LinearLayout.HORIZONTAL
        prof.gravity = Gravity.CENTER_VERTICAL
        prof.setPadding(dp(4f), dp(6f), dp(4f), dp(14f))
        val av = tv("S", 20f, 0xFFFFFFFF.toInt(), true); av.gravity = Gravity.CENTER
        av.background = ovalDrawable(0xFF3A3A44.toInt())
        av.layoutParams = LinearLayout.LayoutParams(dp(52f), dp(52f)).apply { rightMargin = dp(12f) }
        prof.addView(av)
        val pcol = LinearLayout(this); pcol.orientation = LinearLayout.VERTICAL
        pcol.addView(tv("Shadow", 18f, Glass.text(this@MainActivity), true))
        val earliest = comps.minOfOrNull { it.date }
        val psub = tv(
            if (earliest != null) "${comps.size} completed tasks since " + fmtMonDay(earliest)
            else "No completed tasks yet",
            15f, TD_SALMON)
        psub.layoutParams = lp(t = dp(2f))
        pcol.addView(psub)
        prof.addView(pcol)
        content.addView(prof)

        // Tabs: selected = salmon text + a short word-width rounded red
        // underline bar (not full-width).
        val tabs = LinearLayout(this); tabs.orientation = LinearLayout.HORIZONTAL
        tabs.layoutParams = lp(b = dp(14f))
        listOf("daily" to "Daily", "weekly" to "Weekly", "karma" to "Karma").forEach { (id, lbl) ->
            val on = prodTab == id
            val colT = LinearLayout(this); colT.orientation = LinearLayout.VERTICAL
            colT.gravity = Gravity.CENTER_HORIZONTAL
            colT.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            colT.addView(tv(lbl, 17f, if (on) TD_SALMON else TD_GREY))
            val barU = View(this)
            barU.background = roundedRect(TD_SALMON, 2f)
            barU.layoutParams = LinearLayout.LayoutParams(dp(44f), dp(4f)).apply { topMargin = dp(8f) }
            barU.visibility = if (on) View.VISIBLE else View.INVISIBLE
            colT.addView(barU)
            colT.setOnClickListener { prodTab = id; Sfx.tick(); render() }
            tabs.addView(colT)
        }
        content.addView(tabs)

        val gp = Stats.goalProgress(comps, today, Store.state.dailyGoal, Store.state.weeklyGoal)
        when (prodTab) {
            "weekly" -> {
                content.addView(goalRingCard("Weekly goal", gp.weeklyDone, gp.weeklyGoal, gp.weeklyFraction,
                    when {
                        gp.weeklyGoal > 0 && gp.weeklyDone >= gp.weeklyGoal -> "Goal smashed — amazing work!"
                        gp.weeklyDone == 0 -> "A fresh week — one task at a time."
                        else -> "Keep up the momentum!"
                    }, 1) { showEditGoalSheet(false) })
                val sc = prodCard()
                sc.addView(tv("Weekly streak", 17f, Glass.text(this@MainActivity), true))
                val sv = tv(Stats.weeklyStreak(comps, today, Store.state.weeklyGoal).toString() + " weeks", 16f, 0xFFC8C8C8.toInt())
                sv.layoutParams = lp(t = dp(6f))
                sc.addView(sv)
                content.addView(sc)
                val chart = prodCard()
                chart.addView(tv("Completed in the last 4 weeks", 17f, Glass.text(this@MainActivity), true))
                // Today-first, exactly as Stats returns it (Correction
                // Round 1). Weekly rows carry NO labels — bare counts.
                val weeks = Stats.lastNWeeksCounts(comps, today, 4)
                val maxW = Math.max(1, weeks.maxOfOrNull { it.second } ?: 1)
                weeks.forEach { (_, n) -> chart.addView(prodBarRow(n, maxW, n.toString(), 36f)) }
                content.addView(chart)
            }
            "karma" -> {
                val info = Karma.levelFor(Store.state.karmaPoints)
                val lc = prodCard()
                lc.addView(tv("Karma level", 17f, Glass.text(this@MainActivity), true))
                val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL
                row.gravity = Gravity.CENTER_VERTICAL
                row.layoutParams = lp(t = dp(14f))
                val left = LinearLayout(this); left.orientation = LinearLayout.VERTICAL
                left.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                val big = LinearLayout(this); big.orientation = LinearLayout.HORIZONTAL
                big.gravity = Gravity.BOTTOM
                big.addView(tv(info.name, 19f, Glass.text(this@MainActivity), true))
                big.addView(tv(" (" + Store.state.karmaPoints + ")", 19f))
                left.addView(big)
                val sub2 = tv(
                    if (info.nextName != null) "${info.pointsToNext} left to get to " + info.nextName
                    else "Top level reached — nothing above Grandmaster",
                    15f, 0xFFC8C8C8.toInt())
                sub2.layoutParams = lp(t = dp(6f))
                left.addView(sub2)
                val about = tv("About Karma levels", 15f, TD_SALMON)
                about.setPadding(0, dp(6f), dp(8f), 0)
                about.setOnClickListener {
                    AlertDialog.Builder(this).setTitle("Karma levels")
                        .setMessage("Beginner 0 · Novice 500 · Intermediate 2,000 · Proficient 5,000 · Expert 10,000 · Master 20,000 · Grandmaster 50,000.\n\nCompleting tasks earns points by priority: P1 = 50, P2 = 30, P3 = 20, P4 = 10. Points are never deducted.")
                        .setPositiveButton("OK", null).show()
                }
                left.addView(about)
                row.addView(left)
                val ring = GoalRingView(this, info.progress, TD_RED, Todoist.RING_TRACK, 2)
                ring.layoutParams = LinearLayout.LayoutParams(dp(88f), dp(88f))
                ring.contentDescription = "Karma level progress ring"
                row.addView(ring)
                lc.addView(row)
                content.addView(lc)

                val tc = prodCard()
                tc.addView(tv("Karma trend", 17f, Glass.text(this@MainActivity), true))
                // Karma.trend is today-first (Correction Round 1); the
                // chart's time axis runs oldest → newest, so it is
                // reversed for plotting only.
                val trend = Karma.trend(comps, today).reversed()
                val chartView = KarmaTrendView(this, trend)
                chartView.layoutParams = lp(t = dp(10f), b = dp(6f))
                chartView.layoutParams.height = dp(170f)
                tc.addView(chartView)
                val letters = LinearLayout(this); letters.orientation = LinearLayout.HORIZONTAL
                val dayLetters = arrayOf("S", "M", "T", "W", "T", "F", "S")
                (6 downTo 0).forEach { back ->
                    val d = Dates.parse(Dates.addDays(today, -back))
                    val lt = tv(dayLetters[Dates.jsDayOfWeek(d)], 13f, TD_GREY)
                    lt.gravity = Gravity.CENTER
                    lt.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                    letters.addView(lt)
                }
                tc.addView(letters)
                content.addView(tc)
            }
            else -> {
                content.addView(goalRingCard("Goal progress", gp.dailyDone, gp.dailyGoal, gp.dailyFraction,
                    when {
                        gp.dailyGoal > 0 && gp.dailyDone >= gp.dailyGoal -> "Goal smashed — amazing work!"
                        gp.dailyDone == 0 -> "A fresh start — one task at a time."
                        else -> "You've got some great momentum!"
                    }, 0) { showEditGoalSheet(true) })
                val sc = prodCard()
                sc.addView(tv("Current streak", 17f, Glass.text(this@MainActivity), true))
                val sv = tv(Stats.currentStreak(comps, today).toString() + " days", 16f, 0xFFC8C8C8.toInt())
                sv.layoutParams = lp(t = dp(6f))
                sc.addView(sv)
                content.addView(sc)
                val chart = prodCard()
                chart.addView(tv("Completed in the last 7 days", 17f, Glass.text(this@MainActivity), true))
                // Rows TODAY-FIRST, exactly as Stats returns them.
                val days = Stats.lastNDaysCounts(comps, today, 7)
                val maxD = Math.max(1, days.maxOfOrNull { it.second } ?: 1)
                val dayLetters = arrayOf("S", "M", "T", "W", "T", "F", "S")
                days.forEach { (date, n) ->
                    val letter = dayLetters[Dates.jsDayOfWeek(Dates.parse(date))]
                    chart.addView(prodBarRow(n, maxD, "$letter · $n", 64f))
                }
                content.addView(chart)
            }
        }
        // v5 stats pack (SHADOW-THREE-DESIGN §3): editorial caption,
        // annual heatmap, daily chart, productive day, completion rate,
        // next-7-days — appended below the Todoist-shaped tabs.
        if (isV5()) renderStatsPack()
    }

    /* ----- Performance (database-backed) ----- */

    private var perfRange = 7

    private fun renderPerformance() {
        val chipsRow = LinearLayout(this); chipsRow.orientation = LinearLayout.HORIZONTAL
        chipsRow.layoutParams = lp(b = dp(10f))
        listOf(7 to "7 days", 30 to "30 days", 60 to "2 months", 365 to "1 year").forEach { (days, lbl) ->
            val ch = chipBtn(lbl, perfRange == days) { perfRange = days; render() }
            ch.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6f) }
            chipsRow.addView(ch)
        }
        content.addView(chipsRow)

        val today = Dates.today()
        val from = Dates.addDays(today, -(perfRange - 1))
        val done = Db.completionsInRange(from, today)
        val missed = Db.outcomesInRange(from, today, "missed")
        val decided = done.size + missed.size
        val rate = if (decided > 0) done.size * 100 / decided else null
        val active = Db.activeDays(from, today)
        val streakRate = active * 100 / perfRange

        val g1 = LinearLayout(this); g1.orientation = LinearLayout.HORIZONTAL
        val c1 = statCard(if (rate != null) "$rate%" else "—", "completion rate")
        c1.layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(10f) }
        val c2 = statCard(done.size.toString(), "tasks done")
        c2.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        g1.addView(c1); g1.addView(c2)
        content.addView(g1)
        val g2 = LinearLayout(this); g2.orientation = LinearLayout.HORIZONTAL
        val c3 = statCard(missed.size.toString(), "tasks missed")
        c3.layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(10f) }
        val c4 = statCard("$streakRate%", "streak rate · $active/$perfRange days active")
        c4.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        g2.addView(c3); g2.addView(c4)
        content.addView(g2)
        val g3 = LinearLayout(this); g3.orientation = LinearLayout.HORIZONTAL
        val c5 = statCard("🔥 " + Store.currentStreak(), "current streak (days)")
        c5.layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(10f) }
        val c6 = statCard("🏆 " + Db.bestStreak(), "best streak (days)")
        c6.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        g3.addView(c5); g3.addView(c6)
        content.addView(g3)

        // chart, bucketed by range
        val byDay = Db.doneCountByDay(from, today)
        val buckets = perfBuckets(perfRange, byDay)
        val chart = card()
        chart.addView(tv(
            when (perfRange) {
                7 -> "Completions per day"
                30 -> "Completions per day"
                60 -> "Completions per week"
                else -> "Completions per month"
            }, 14.5f, Glass.text(this@MainActivity), true
        ))
        val maxB = Math.max(1, buckets.maxOfOrNull { it.second } ?: 1)
        val bars = LinearLayout(this); bars.orientation = LinearLayout.HORIZONTAL
        bars.gravity = Gravity.BOTTOM
        bars.layoutParams = lp(t = dp(12f))
        buckets.forEach { (lbl, n) ->
            val colL = LinearLayout(this); colL.orientation = LinearLayout.VERTICAL
            colL.gravity = Gravity.CENTER_HORIZONTAL
            colL.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            if (perfRange <= 30) colL.addView(tv(n.toString(), 11f, Glass.muted(this@MainActivity), true))
            val bar = View(this)
            val h = dp(6f) + (dp(64f) * n / maxB)
            bar.layoutParams = LinearLayout.LayoutParams(-1, h).apply {
                topMargin = dp(3f); bottomMargin = dp(3f); leftMargin = dp(1f); rightMargin = dp(1f)
            }
            val g = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Accents.acc2(this), Accents.acc1(this))
            )
            g.cornerRadius = dp(4f).toFloat()
            bar.background = g
            colL.addView(bar)
            colL.addView(tv(lbl, 11f, Glass.muted(this@MainActivity)))
            bars.addView(colL)
        }
        chart.addView(bars)
        content.addView(chart)

        // per-project
        val projRows = Db.doneByProject(from, today)
        if (projRows.isNotEmpty()) {
            val pc = card()
            pc.addView(tv("Done by project", 15f, Glass.text(this@MainActivity), true))
            projRows.take(6).forEach { (pid, n) ->
                val name = Store.state.projects.find { it.id == pid }?.name ?: "Inbox / deleted"
                val rowL = LinearLayout(this); rowL.orientation = LinearLayout.HORIZONTAL
                rowL.layoutParams = lp(t = dp(6f))
                val nm = tv("# $name", 15f); nm.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                rowL.addView(nm); rowL.addView(tv(n.toString(), 15f, Glass.text(this@MainActivity), true))
                pc.addView(rowL)
            }
            content.addView(pc)
        }

        // done list
        content.addView(dayGroup("✅ DONE — ${done.size}"))
        if (done.isEmpty()) content.addView(tv("Nothing completed in this period yet.", 13f, Glass.muted(this@MainActivity)))
        var lastDate = ""
        done.take(80).forEach { c ->
            if (c.date != lastDate) {
                lastDate = c.date
                content.addView(tv(if (c.date == today) "Today" else Dates.fmtDay(c.date), 13f, if (Glass.isWhite(this@MainActivity)) Accents.acc2(this@MainActivity) else Accents.acc1(this@MainActivity), true))
            }
            val proj = Store.state.projects.find { it.id == c.projectId }
            content.addView(tv("✅  " + c.title + if (proj != null) "   #" + proj.name else "", 15f))
        }
        if (done.size > 80) content.addView(tv("…and ${done.size - 80} more", 13f, Glass.muted(this@MainActivity)))

        // missed list
        content.addView(dayGroup("✗ MISSED — ${missed.size}"))
        if (missed.isEmpty()) content.addView(tv("No missed tasks in this period. Clean record 🎉", 13f, Glass.muted(this@MainActivity)))
        missed.take(80).forEach { m ->
            val proj = Store.state.projects.find { it.id == m.projectId }
            val whenLbl = if (m.date == Dates.addDays(today, -1)) "Yesterday" else Dates.fmtDay(m.date)
            content.addView(tv("✗  " + m.title + "   ·  " + whenLbl + if (proj != null) "   #" + proj.name else "", 15f, Glass.muted(this@MainActivity)))
        }
        if (missed.size > 80) content.addView(tv("…and ${missed.size - 80} more", 13f, Glass.muted(this@MainActivity)))

        content.addView(tv("Stored in your on-device database (SQLite). Missed days are recorded automatically — including every day a recurring task came due and wasn't done. History grows from your real usage.", 13f, Glass.muted(this@MainActivity)))
    }

    private fun perfBuckets(range: Int, byDay: Map<String, Int>): List<Pair<String, Int>> {
        val today = Dates.today()
        val dayNames = arrayOf("Su", "Mo", "Tu", "We", "Th", "Fr", "Sa")
        return when {
            range <= 30 -> (range - 1 downTo 0).map { off ->
                val d = Dates.addDays(today, -off)
                val lbl = if (range == 7) dayNames[Dates.jsDayOfWeek(Dates.parse(d))]
                else Dates.parse(d).get(java.util.Calendar.DAY_OF_MONTH).toString()
                lbl to (byDay[d] ?: 0)
            }
            range == 60 -> (8 downTo 0).map { w ->
                val end = Dates.addDays(today, -(w * 7))
                var sum = 0
                for (i in 0 until 7) sum += byDay[Dates.addDays(end, -i)] ?: 0
                Dates.fmtDay(Dates.addDays(end, -6)).substring(4) to sum
            }
            else -> (11 downTo 0).map { mOff ->
                val cal = java.util.Calendar.getInstance()
                cal.add(java.util.Calendar.MONTH, -mOff)
                val y = cal.get(java.util.Calendar.YEAR)
                val mo = cal.get(java.util.Calendar.MONTH)
                var sum = 0
                for ((d, n) in byDay) {
                    val dc = Dates.parse(d)
                    if (dc.get(java.util.Calendar.YEAR) == y && dc.get(java.util.Calendar.MONTH) == mo) sum += n
                }
                arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")[mo] to sum
            }
        }
    }

    /* ----- Insights ----- */

    private fun renderInsights() {
        if (Store.state.projects.isEmpty()) {
            content.addView(emptyView("💡", "Create a project first (Quick Add with #Name)."))
            return
        }
        data class Row(val p: ProjectItem, val open: Int, val done7: Int, val pct: Float)
        val rows = Store.state.projects.map { p ->
            val open = Store.state.tasks.count { it.projectId == p.id }
            val done7 = Store.state.completions.count { c ->
                c.projectId == p.id && c.date > Dates.addDays(Dates.today(), -7) && c.date <= Dates.today()
            }
            Row(p, open, done7, if (open + done7 > 0) done7.toFloat() / (open + done7) else 0f)
        }.sortedByDescending { it.open + it.done7 }
        val busiest = rows.firstOrNull()
        if (busiest != null && busiest.open + busiest.done7 > 0) {
            val c = card()
            c.addView(tv("🏆 Busiest project", 13f, Glass.gold(this@MainActivity), true))
            val b = tv("#" + busiest.p.name, 17f, Glass.text(this@MainActivity), true); b.layoutParams = lp(t = dp(3f))
            c.addView(b)
            c.addView(tv("${busiest.open} open · ${busiest.done7} done this week", 13f, Glass.muted(this@MainActivity)))
            content.addView(c)
        }
        rows.forEach { r ->
            val c = card(13)
            val headL = LinearLayout(this); headL.orientation = LinearLayout.HORIZONTAL
            val nm = tv("#" + r.p.name, 15f, Glass.text(this@MainActivity), true); nm.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            headL.addView(nm)
            headL.addView(tv("${(r.pct * 100).toInt()}% weekly completion", 13f, Glass.muted(this@MainActivity)))
            c.addView(headL)
            c.addView(tv("${r.open} open · ${r.done7} done in the last 7 days", 13f, Glass.muted(this@MainActivity)))
            c.addView(progressBar(r.pct))
            content.addView(c)
        }
    }

    /* ----- History / Reporting ----- */

    /** How many activity entries the Reporting screen has paged in so
     *  far. The log itself is capped at 100,000 (Store.logActivity); the
     *  screen pages through it 150 at a time instead of truncating the
     *  presentation at 150 (Correction Round 2, F-1). */
    private var historyShown = 150

    private fun historyDayLabel(day: String): String = when (day) {
        Dates.today() -> "Today"
        Dates.addDays(Dates.today(), -1) -> "Yesterday"
        else -> Dates.fmtDay(day)
    }

    /** One Reporting row: coloured badge disc (✓ completed / + added /
     *  ✎ date-changed, per the reference activity log), entry text + time,
     *  and the entry's project right-aligned when it has one. */
    private fun activityRow(a: ActivityItem): LinearLayout {
        val rowL = LinearLayout(this); rowL.orientation = LinearLayout.HORIZONTAL
        rowL.gravity = Gravity.CENTER_VERTICAL
        rowL.setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
        val badgeSpec = when (a.type) {
            "complete" -> "✓" to Glass.GREEN
            "create" -> "+" to Glass.GREEN
            "reschedule" -> "✎" to 0xFFE79B2E.toInt()
            "delete" -> "✕" to Glass.overdue(this)
            "focus" -> "◎" to TD_GREY
            else -> "•" to TD_GREY
        }
        val badge = tv(badgeSpec.first, 13f, 0xFFFFFFFF.toInt(), true)
        badge.gravity = Gravity.CENTER
        badge.background = ovalDrawable(badgeSpec.second)
        badge.layoutParams = LinearLayout.LayoutParams(dp(26f), dp(26f))
        badge.contentDescription = a.type
        rowL.addView(badge)
        val colL = LinearLayout(this); colL.orientation = LinearLayout.VERTICAL
        colL.layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12f) }
        colL.addView(tv(a.text, 15f))
        colL.addView(tv(fmtWhen(a.at), 13f, Glass.muted(this@MainActivity)))
        rowL.addView(colL)
        val pr = a.projectId?.let { pid -> Store.state.projects.find { it.id == pid } }
        if (pr != null) {
            val right = tv(pr.name + " #", 13f, Glass.muted(this@MainActivity))
            right.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8f) }
            rowL.addView(right)
        }
        return rowL
    }

    private fun renderHistory() {
        if (Store.state.activities.isEmpty()) {
            content.addView(emptyView("🕘", "Nothing yet — your activity will appear here."))
            return
        }
        // Day-grouped Reporting (Correction Round 2, F-1): groups come
        // from Team Two's Stats.groupActivitiesByDay — newest day first,
        // entries in log order within the day.
        val shown = Store.state.activities.take(historyShown)
        Stats.groupActivitiesByDay(shown).forEach { (day, items) ->
            content.addView(sectionHeader(historyDayLabel(day)))
            val g = LinearLayout(this); g.orientation = LinearLayout.VERTICAL
            g.background = roundedRect(cardFill(), 14f); g.layoutParams = lp(b = dp(10f))
            items.forEachIndexed { i, a ->
                if (i > 0) {
                    val h = hairline()
                    h.layoutParams = LinearLayout.LayoutParams(-1, Math.max(1, dp(0.5f))).apply { leftMargin = dp(16f); rightMargin = dp(16f) }
                    g.addView(h)
                }
                g.addView(activityRow(a))
            }
            content.addView(g)
        }
        if (Store.state.activities.size > shown.size) {
            val more = ghostBtn("Show older activity") { historyShown += 150; render() }
            more.layoutParams = lp(t = dp(4f))
            content.addView(more)
        }
    }

    /* ----- Ramble ----- */

    private fun renderRamble() {
        val c = card()
        c.addView(tv("Say it or type it — one task per line. Dates, #projects, @labels and p1 work in every line.", 13f, Glass.muted(this@MainActivity)))
        val ta = input("e.g.\nCall mom tomorrow @family\nBuy milk #Shopping\nGym at 7am every day p2", rambleText, multi = true)
        ta.minLines = 5
        ta.gravity = Gravity.TOP
        ta.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(e: Editable?) { rambleText = e?.toString() ?: "" }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        c.addView(ta)
        val mic = ghostBtn("🎙️ Start listening") {
            if (Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 91)
                toast("Allow microphone, then tap again")
            } else {
                try {
                    val i = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    i.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    i.putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Speak your tasks")
                    startActivityForResult(i, 57)
                } catch (e: Exception) {
                    toast("Voice input isn't available on this device — typing works too")
                }
            }
        }
        mic.layoutParams = lp(b = dp(10f))
        c.addView(mic)
        val add = accentBtn("Add all tasks") {
            val lines = ta.text.toString().split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) { toast("Nothing to add yet"); return@accentBtn }
            lines.forEach { line -> Store.addTaskFromParse(Parser.parse(line)) }
            rambleText = ""
            Store.save()
            Sfx.add()
            toast("Added ${lines.size} tasks ✅")
            view = "today"; sub = null; render()
        }
        c.addView(add)
        content.addView(c)
    }

    /* ----- Assist ----- */

    private fun extractLines(text: String): List<String> =
        text.split("\n").map { raw ->
            var line = raw.trim()
            line = line.replace(Regex("""^[\s•\-*▪◦]+"""), "")
            line = line.replace(Regex("""^\[[ xX]\]\s*"""), "")
            line = line.replace(Regex("""^\d{1,2}[.)]\s*"""), "")
            line = line.replace(Regex("""\s+by\s+(monday|tuesday|wednesday|thursday|friday|saturday|sunday|tomorrow|today|next week)\s*$""", RegexOption.IGNORE_CASE), "")
            line.trim()
        }.filter { it.isNotEmpty() }

    private fun renderAssist() {
        val c = card()
        c.addView(tv("Paste an email, message or brain-dump — one task per line. GhostBlade extracts clean tasks and keeps dates, projects and priorities.", 13f, Glass.muted(this@MainActivity)))
        val ta = input("Paste text here…", assistText, multi = true)
        ta.minLines = 6
        ta.gravity = Gravity.TOP
        ta.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(e: Editable?) { assistText = e?.toString() ?: "" }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        c.addView(ta)
        val ex = accentBtn("Extract tasks") {
            val lines = extractLines(ta.text.toString())
            if (lines.isEmpty()) { toast("Nothing found — paste some text first"); return@accentBtn }
            lines.forEach { line -> Store.addTaskFromParse(Parser.parse(line)) }
            assistText = ""
            Store.save()
            Sfx.add()
            toast("Extracted ${lines.size} tasks ✅")
            view = "today"; sub = null; render()
        }
        c.addView(ex)
        content.addView(c)
        content.addView(dayGroup("BREAK A TASK DOWN"))
        content.addView(tv("Big task? Let GhostBlade draft the steps — you can edit them in the task editor.", 13f, Glass.muted(this@MainActivity)))
        Store.state.tasks.take(12).forEach { t ->
            content.addView(menuItem("📝", t.title, null, null) {
                breakdownFor(t.title).forEach { x -> t.subtasks.add(Subtask(uid(), x, false)) }
                Store.save()
                Sfx.add()
                toast("Subtasks added to \"${t.title}\" 🤖")
            })
        }
    }

    /* ----- Backups & Sync ----- */

    private fun writeDownload(name: String, text: String) {
        try {
            // Where the file actually landed, for the toast.
            val where: String
            if (Build.VERSION.SDK_INT >= 29) {
                val cv = android.content.ContentValues()
                cv.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                cv.put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/json")
                cv.put(android.provider.MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                    ?: throw Exception("insert failed")
                val os = contentResolver.openOutputStream(uri) ?: throw Exception("open failed")
                os.write(text.toByteArray(Charsets.UTF_8)); os.close()
                where = "Downloads"
            } else {
                // API 24–28: writing to the public Downloads folder needs
                // WRITE_EXTERNAL_STORAGE, which the app never requests, so
                // that write always failed with "Could not save file". The
                // app's own external Downloads folder needs no permission.
                val dir = getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
                    ?: throw Exception("no external dir")
                val f = File(dir, name)
                val os = FileOutputStream(f)
                os.write(text.toByteArray(Charsets.UTF_8)); os.close()
                where = "app storage"
            }
            toast("Saved to $where: $name")
        } catch (e: Exception) {
            toast("Could not save file")
        }
    }

    private fun renderBackups() {
        val info = card()
        info.addView(tv("💾 Automatic daily backups", 15f, Glass.text(this@MainActivity), true))
        info.addView(tv("GhostBlade keeps a snapshot of your data on this device every day (up to 10). No account, no internet — this app has no internet permission at all.", 13f, Glass.muted(this@MainActivity)))
        content.addView(info)
        val c = card()
        c.addView(tv("Sync between devices", 15f, Glass.text(this@MainActivity), true))
        c.addView(tv("Export a sync file here, copy it to your other device (USB, Bluetooth, any chat app), then Import it there. Your data moves — nothing touches a server.", 13f, Glass.muted(this@MainActivity)))
        val exp = accentBtn("⬇️ Export sync file") { writeDownload("ghostblade-sync.json", Store.snapshot()) }
        exp.layoutParams = lp(t = dp(10f), b = dp(8f))
        c.addView(exp)
        val imp = ghostBtn("⬆️ Import sync file") {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            i.type = "*/*"
            i.addCategory(Intent.CATEGORY_OPENABLE)
            startActivityForResult(i, 56)
        }
        imp.layoutParams = lp(b = dp(8f))
        c.addView(imp)
        val copy = ghostBtn("📋 Copy all data as JSON") {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("GhostBlade Task data", Store.snapshot()))
            toast("Copied — keep it somewhere safe")
        }
        c.addView(copy)
        content.addView(c)
        val mk = ghostBtn("＋ Create backup now") {
            Store.state.backups.add(0, BackupItem(uid(), System.currentTimeMillis(), false, Store.snapshot()))
            Store.logActivity("backup", "Manual backup created")
            Store.save(); render(); toast("Backup created 💾")
        }
        content.addView(mk)
        content.addView(dayGroup("SAVED BACKUPS"))
        if (Store.state.backups.isEmpty()) content.addView(tv("No backups yet.", 13f, Glass.muted(this@MainActivity)))
        Store.state.backups.forEach { b ->
            val rowC = card(12)
            val rowL = LinearLayout(this); rowL.orientation = LinearLayout.HORIZONTAL
            rowL.gravity = Gravity.CENTER_VERTICAL
            val colL = LinearLayout(this); colL.orientation = LinearLayout.VERTICAL
            colL.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            colL.addView(tv(fmtWhen(b.at), 15f))
            colL.addView(tv(if (b.auto) "Automatic" else "Manual", 13f, Glass.muted(this@MainActivity)))
            rowL.addView(colL)
            val restore = chipBtn("Restore", false) {
                AlertDialog.Builder(this)
                    .setTitle("Restore this backup?")
                    .setMessage("Your current tasks will be replaced by the backup from ${fmtWhen(b.at)}.")
                    .setPositiveButton("Restore") { _, _ ->
                        if (Store.importData(b.data)) { toast("Backup restored ✅"); view = "today"; sub = null; render() }
                        else toast("That backup looks damaged")
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            rowL.addView(restore)
            val delB = tv("✕", 15f, Glass.muted(this@MainActivity))
            delB.setPadding(dp(10f), dp(6f), dp(2f), dp(6f))
            delB.setOnClickListener {
                Store.state.backups.removeAll { it.id == b.id }
                Store.save(); render()
            }
            rowL.addView(delB)
            rowC.addView(rowL)
            content.addView(rowC)
        }
    }

    /* ----- Smart Plan ----- */

    private fun renderSmartPlan() {
        val items = smartPlanItems()
        val intro = card()
        intro.addView(tv("🤖 Smart Plan", 16f, Glass.text(this@MainActivity), true))
        intro.addView(tv("An offline planner: today's tasks in priority order, starting at the next quarter-hour, using each task's duration (25 min default) with 10-minute breaks. No cloud, no AI bill — just a solid plan.", 13f, Glass.muted(this@MainActivity)))
        content.addView(intro)
        if (items.isEmpty()) {
            content.addView(emptyView("🌤️", "No tasks scheduled for today — nothing to plan."))
            return
        }
        var totalMin = 0
        items.forEach { (t, start, dur) ->
            totalMin += dur
            val rowL = LinearLayout(this); rowL.orientation = LinearLayout.HORIZONTAL
            rowL.gravity = Gravity.CENTER_VERTICAL
            rowL.background = Glass.panel(this, 20f)
            rowL.setPadding(dp(13f), dp(11f), dp(13f), dp(11f))
            rowL.layoutParams = lp(b = dp(8f))
            val time = tv(start, 15f, if (Glass.isWhite(this@MainActivity)) Accents.acc2(this@MainActivity) else Accents.acc1(this@MainActivity), true)
            time.layoutParams = LinearLayout.LayoutParams(dp(56f), -2)
            rowL.addView(time)
            val colL = LinearLayout(this); colL.orientation = LinearLayout.VERTICAL
            colL.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            colL.addView(tv(t.title, 15f))
            colL.addView(tv("P${t.priority} · $dur min", 13f, Glass.muted(this@MainActivity)))
            rowL.addView(colL)
            content.addView(rowL)
        }
        content.addView(tv("Total focused time: ${totalMin / 60}h ${totalMin % 60}m (plus breaks)", 13f, Glass.muted(this@MainActivity)))
        val apply = accentBtn("Apply this plan") {
            items.forEach { (t, start, _) ->
                t.dueTime = start
                t.dueDate = Dates.today()
                if (t.reminderOffsetMin == null) t.reminderOffsetMin = 0
                Reminders.sync(this, t)
            }
            Store.save()
            Sfx.add()
            toast("Plan applied — reminders set 🔔")
            sub = null; view = "today"; render()
        }
        apply.layoutParams = lp(t = dp(12f))
        content.addView(apply)
    }

    private fun smartPlanItems(): List<Triple<TaskItem, String, Int>> {
        val list = Store.state.tasks
            .filter { it.dueDate == Dates.today() || Dates.isOverdue(it) }
            .sortedWith(compareBy<TaskItem> { it.priority }.thenByDescending { it.durationMin ?: 25 })
        val now = java.util.Calendar.getInstance()
        val cur = java.util.Calendar.getInstance()
        cur.set(java.util.Calendar.SECOND, 0); cur.set(java.util.Calendar.MILLISECOND, 0)
        val minsNow = cur.get(java.util.Calendar.MINUTE)
        val rounded = Math.ceil((minsNow + 1) / 15.0).toInt() * 15
        cur.add(java.util.Calendar.MINUTE, rounded - minsNow)
        if (cur.before(now)) cur.add(java.util.Calendar.MINUTE, 15)
        fun fmt(c: java.util.Calendar) =
            c.get(java.util.Calendar.HOUR_OF_DAY).toString().padStart(2, '0') + ":" +
                c.get(java.util.Calendar.MINUTE).toString().padStart(2, '0')
        return list.map { t ->
            val dur = t.durationMin ?: 25
            val start = fmt(cur)
            cur.add(java.util.Calendar.MINUTE, dur + 10)
            Triple(t, start, dur)
        }
    }

    /* ----- Project board ----- */

    private fun renderBoard(p: ProjectItem) {
        if (isV5()) { renderBoardV5(p); return }
        val cols = listOf("todo" to "To do", "doing" to "Doing", "done" to "Done")
        val board = LinearLayout(this); board.orientation = LinearLayout.HORIZONTAL
        cols.forEach { (status, name) ->
            val colL = LinearLayout(this); colL.orientation = LinearLayout.VERTICAL
            colL.background = Glass.panel(this, 20f)
            colL.setPadding(dp(6f), dp(8f), dp(6f), dp(8f))
            colL.layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply {
                rightMargin = if (status != "done") dp(8f) else 0
            }
            val headT = tv(name.uppercase(), 11f, Glass.muted(this@MainActivity), true); headT.gravity = Gravity.CENTER
            colL.addView(headT)
            val tasks = Store.state.tasks.filter { it.projectId == p.id && it.status == status }
            if (tasks.isEmpty()) {
                val e = tv("—", 13f, Glass.muted(this@MainActivity)); e.gravity = Gravity.CENTER
                colL.addView(e)
            }
            tasks.forEach { t ->
                val cardL = LinearLayout(this); cardL.orientation = LinearLayout.VERTICAL
                cardL.background = Glass.panel(this, 12f)
                cardL.setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
                cardL.layoutParams = lp(t = dp(8f))
                cardL.addView(tv(t.title, 13f))
                val meta = mutableListOf<String>()
                if (t.priority < 4) meta.add("P" + t.priority)
                if (t.dueDate != null) meta.add(if (t.dueDate == Dates.today()) "Today" else Dates.fmtDay(t.dueDate!!))
                if (meta.isNotEmpty()) cardL.addView(tv(meta.joinToString(" · "), 11f, Glass.muted(this@MainActivity)))
                val btns = LinearLayout(this); btns.orientation = LinearLayout.HORIZONTAL
                btns.layoutParams = lp(t = dp(6f))
                if (status != "todo") {
                    val b = tv("◀", 13f); b.setPadding(dp(4f), dp(2f), dp(8f), dp(2f))
                    b.setOnClickListener {
                        t.status = if (status == "done") "doing" else "todo"
                        Store.save(); Sfx.tick(); render()
                    }
                    btns.addView(b)
                }
                if (status == "done") {
                    val b = tv("✓", 13f, Glass.GREEN, true); b.setPadding(dp(4f), dp(2f), dp(4f), dp(2f))
                    b.setOnClickListener {
                        val msg = Store.completeTask(t.id)
                        Sfx.complete(); toast(msg); render()
                    }
                    btns.addView(b)
                } else {
                    val b = tv("▶", 13f); b.setPadding(dp(4f), dp(2f), dp(4f), dp(2f))
                    b.setOnClickListener {
                        t.status = if (status == "todo") "doing" else "done"
                        Store.save(); Sfx.tick(); render()
                    }
                    btns.addView(b)
                }
                cardL.addView(btns)
                cardL.setOnClickListener { openEditor(t.id) }
                colL.addView(cardL)
            }
            board.addView(colL)
        }
        content.addView(board)
        content.addView(tv("Move tasks with ◀ ▶. In the Done column, ✓ completes the task for real.", 12.5f, Glass.muted(this@MainActivity)))
    }

    /** Board, v5: the columns keep v4.0's status data behaviour
     *  (To do / Doing / Done + ◀ ▶ moves), but every card is the §2.6
     *  compact task-card variant — left bar, ring checkbox, "!" badge,
     *  due chip — never a one-off restyle (CHECK1 P2-6). Column header
     *  = name + count (§3.5.4's chip, adapted to status columns). */
    private fun renderBoardV5(p: ProjectItem) {
        val cols = listOf("todo" to "To do", "doing" to "Doing", "done" to "Done")
        val board = LinearLayout(this); board.orientation = LinearLayout.HORIZONTAL
        cols.forEach { (status, name) ->
            val tasks = Store.state.tasks.filter { it.projectId == p.id && it.status == status }
            val colL = LinearLayout(this); colL.orientation = LinearLayout.VERTICAL
            colL.background = v5CardBg()
            colL.setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
            // §3.5.4 geometry: 272dp columns, 12dp gaps, horizontal
            // scroll — the §2.6 card anatomy needs the width.
            colL.layoutParams = LinearLayout.LayoutParams(dp(272f), -2).apply {
                rightMargin = if (status != "done") dp(12f) else 0
            }
            val head = LinearLayout(this); head.orientation = LinearLayout.HORIZONTAL
            head.gravity = Gravity.CENTER_VERTICAL
            head.layoutParams = lp(b = dp(8f))
            val ht = tv(name, 13f, V5.TEXT, true)
            ht.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            head.addView(ht)
            head.addView(tv(tasks.size.toString(), 12f, V5.FAINT))
            colL.addView(head)
            if (tasks.isEmpty()) {
                val e = tv("—", 13f, V5.FAINT); e.gravity = Gravity.CENTER
                colL.addView(e)
            }
            tasks.forEach { t ->
                val cardV = v5TaskCard(t, showProject = false, compact = true)
                cardV.layoutParams = lp(b = dp(10f))
                colL.addView(cardV)
                val btns = LinearLayout(this); btns.orientation = LinearLayout.HORIZONTAL
                btns.gravity = Gravity.END
                btns.layoutParams = lp(b = dp(10f))
                fun moveBtn(glyph: String, desc: String, act: () -> Unit) {
                    val b = tv(glyph, 13f, V5.TEXT)
                    b.gravity = Gravity.CENTER
                    b.background = roundedRect(V5.POPUP, 999f)
                    b.layoutParams = LinearLayout.LayoutParams(dp(28f), dp(28f)).apply { leftMargin = dp(6f) }
                    b.contentDescription = desc
                    b.setOnClickListener { act() }
                    btns.addView(b)
                }
                if (status != "todo") {
                    moveBtn("◀", "Move back") {
                        t.status = if (status == "done") "doing" else "todo"
                        Store.save(); Sfx.tick(); render()
                    }
                }
                if (status == "done") {
                    moveBtn("✓", "Complete task") {
                        val msg = Store.completeTask(t.id)
                        Sfx.complete(); toast(msg); render()
                    }
                } else {
                    moveBtn("▶", "Move forward") {
                        t.status = if (status == "todo") "doing" else "done"
                        Store.save(); Sfx.tick(); render()
                    }
                }
                colL.addView(btns)
            }
            board.addView(colL)
        }
        val hscroll = HorizontalScrollView(this)
        hscroll.isHorizontalScrollBarEnabled = false
        hscroll.addView(board)
        content.addView(hscroll)
        val note = tv("Move tasks with ◀ ▶. In the Done column, ✓ completes the task for real.", 12f, V5.MUTED)
        note.layoutParams = lp(t = dp(10f))
        content.addView(note)
    }

    /* ---------------- location capture ---------------- */

    private fun captureLocation(task: TaskItem) {
        if (Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 77)
            toast("Allow location permission, then tap again")
            return
        }
        val lm = getSystemService(LOCATION_SERVICE) as? LocationManager
        if (lm == null) { toast("Location unavailable"); return }
        toast("Getting your location…")
        // One-shot: the same listener is registered with both providers,
        // so without this the first fix would attach the location twice
        // (double toast + double sheet rebuild).
        var done = false
        try {
            var best: Location? = null
            for (p in lm.getProviders(true)) {
                val l = lm.getLastKnownLocation(p)
                if (l != null && (best == null || l.time > best!!.time)) best = l
            }
            val b = best
            if (b != null && System.currentTimeMillis() - b.time < 10 * 60 * 1000) {
                applyLocation(task, b.latitude, b.longitude)
                return
            }
            val listener = object : LocationListener {
                override fun onLocationChanged(l: Location) {
                    if (done) return
                    done = true
                    try { lm.removeUpdates(this) } catch (e: Exception) {}
                    applyLocation(task, l.latitude, l.longitude)
                }
                override fun onStatusChanged(p: String?, s: Int, bundle: Bundle?) {}
                override fun onProviderEnabled(p: String) {}
                override fun onProviderDisabled(p: String) {}
            }
            var any = false
            try { lm.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, listener, Looper.getMainLooper()); any = true } catch (e: Exception) {}
            try { lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, listener, Looper.getMainLooper()); any = true } catch (e: Exception) {}
            if (!any) toast("Could not get location — check GPS")
        } catch (e: SecurityException) {
            toast("Location permission needed")
        }
    }

    private fun applyLocation(task: TaskItem, lat: Double, lng: Double) {
        task.locLat = lat; task.locLng = lng
        Reminders.syncGeo(this, task)
        Store.save()
        // Only rebuild the editor if it is still the open sheet for this
        // task — a late location fix must not resurrect a sheet the user
        // already closed. The location itself is already saved either way.
        if (editingId == task.id && sheetRoot.visibility == View.VISIBLE) showSheet(buildEditor(task))
        toast("Location reminder set 📍")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (sheetRoot.visibility == View.VISIBLE) {
            // Step back through sub-sheets first (Quick Add → Date picker → …);
            // only close when at the root sheet, with the editor discard guard.
            if (!popSheet()) attemptCloseSheet()
            return
        }
        if (selectMode) { exitSelectMode(); return }
        if (sub != null) { sub = null; render(); return }
        super.onBackPressed()
    }
}

/** Productivity goal ring (B2): track + arc starting at 12 o'clock,
 *  sweeping clockwise with a rounded cap, and a centre graphic drawn
 *  on Canvas — kind 0 = GREY medal, 1 = GREY trophy, 2 = RED karma pin
 *  (ANALYSIS §7–§9: only the Karma pin is red). */
class GoalRingView(
    ctx: android.content.Context,
    private val fraction: Float,
    private val arcColor: Int,
    private val trackColor: Int,
    private val centreKind: Int
) : View(ctx) {
    private val density = ctx.resources.displayMetrics.density
    private fun dpf(v: Float) = v * density
    private val trackPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = dpf(5f)
        color = trackColor
    }
    private val arcPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = dpf(5f)
        strokeCap = android.graphics.Paint.Cap.ROUND
        color = arcColor
    }
    private val greyPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFB0B0B0.toInt()
    }
    private val greyStroke = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = dpf(3f)
        color = 0xFFB0B0B0.toInt()
        strokeCap = android.graphics.Paint.Cap.ROUND
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        val inset = dpf(5f)
        val rect = android.graphics.RectF(inset, inset, width - inset, height - inset)
        canvas.drawArc(rect, 0f, 360f, false, trackPaint)
        if (fraction > 0f) canvas.drawArc(rect, -90f, 360f * fraction.coerceIn(0f, 1f), false, arcPaint)
        val cx = width / 2f
        val cy = height / 2f
        val r = Math.min(width, height) * 0.19f
        when (centreKind) {
            0 -> {
                // Medal: two ribbon strokes above a ring with a check.
                canvas.drawLine(cx - r * 0.55f, cy - r * 1.15f, cx - r * 0.12f, cy - r * 0.30f, greyStroke)
                canvas.drawLine(cx + r * 0.55f, cy - r * 1.15f, cx + r * 0.12f, cy - r * 0.30f, greyStroke)
                greyStroke.style = android.graphics.Paint.Style.STROKE
                canvas.drawCircle(cx, cy + r * 0.28f, r * 0.62f, greyStroke)
                canvas.drawLine(cx - r * 0.30f, cy + r * 0.28f, cx - r * 0.05f, cy + r * 0.52f, greyStroke)
                canvas.drawLine(cx - r * 0.05f, cy + r * 0.52f, cx + r * 0.34f, cy - r * 0.05f, greyStroke)
            }
            1 -> {
                // Trophy: filled cup + handles + stem + base.
                greyPaint.style = android.graphics.Paint.Style.FILL
                val cup = android.graphics.RectF(cx - r, cy - r * 0.95f, cx + r, cy + r * 0.05f)
                canvas.drawRoundRect(cup, dpf(3f), dpf(3f), greyPaint)
                greyStroke.style = android.graphics.Paint.Style.STROKE
                canvas.drawArc(android.graphics.RectF(cx - r * 1.45f, cy - r * 0.85f, cx - r * 0.55f, cy - r * 0.05f), 90f, 180f, false, greyStroke)
                canvas.drawArc(android.graphics.RectF(cx + r * 0.55f, cy - r * 0.85f, cx + r * 1.45f, cy - r * 0.05f), -90f, 180f, false, greyStroke)
                canvas.drawRect(cx - r * 0.16f, cy + r * 0.05f, cx + r * 0.16f, cy + r * 0.55f, greyPaint)
                canvas.drawRect(cx - r * 0.55f, cy + r * 0.55f, cx + r * 0.55f, cy + r * 0.82f, greyPaint)
            }
            else -> {
                // Karma pin: solid red teardrop with a white centre dot.
                val pin = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    style = android.graphics.Paint.Style.FILL
                    color = 0xFFE56864.toInt()
                }
                canvas.drawCircle(cx, cy - r * 0.22f, r * 0.74f, pin)
                val path = android.graphics.Path()
                path.moveTo(cx - r * 0.60f, cy + r * 0.18f)
                path.lineTo(cx + r * 0.60f, cy + r * 0.18f)
                path.lineTo(cx, cy + r * 1.10f)
                path.close()
                canvas.drawPath(path, pin)
                val dot = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    style = android.graphics.Paint.Style.FILL
                    color = 0xFFFFFFFF.toInt()
                }
                canvas.drawCircle(cx, cy - r * 0.22f, r * 0.30f, dot)
            }
        }
    }
}

/** Karma trend area chart (B2, ANALYSIS §9): translucent dark-red
 *  fill under the curve, a salmon dot on every point, faint vertical
 *  gridlines dropping from the dots, and the TWO-TONE line — bright
 *  salmon over the first segments, the rest of the curve carried by
 *  the fill's top edge only. Values arrive oldest → newest. */
class KarmaTrendView(
    ctx: android.content.Context,
    private val values: List<Int>
) : View(ctx) {
    private val density = ctx.resources.displayMetrics.density
    private fun dpf(v: Float) = v * density

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        if (values.isEmpty()) return
        val padL = dpf(10f); val padR = dpf(10f)
        val padT = dpf(16f); val padB = dpf(12f)
        val w = width - padL - padR
        val h = height - padT - padB
        val maxV = Math.max(1, values.maxOrNull() ?: 1)
        val pts = values.mapIndexed { i, v ->
            val x = padL + if (values.size == 1) w / 2f else w * i / (values.size - 1)
            val y = padT + (1f - v.toFloat() / maxV) * h
            x to y
        }
        val baseline = padT + h
        // Area fill.
        val fill = android.graphics.Path()
        fill.moveTo(pts.first().first, pts.first().second)
        for (i in 1 until pts.size) fill.lineTo(pts[i].first, pts[i].second)
        fill.lineTo(pts.last().first, baseline)
        fill.lineTo(pts.first().first, baseline)
        fill.close()
        val fillPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.FILL
            color = Todoist.KARMA_FILL
        }
        canvas.drawPath(fill, fillPaint)
        // Faint drop-lines from each dot to the baseline.
        val grid = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = dpf(1f)
            color = android.graphics.Color.argb(36, 255, 255, 255)
        }
        pts.forEach { (x, y) -> canvas.drawLine(x, y, x, baseline, grid) }
        // Rest of the curve: only the fill's boundary, a muted edge.
        if (pts.size > 3) {
            val edge = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = dpf(2f)
                strokeJoin = android.graphics.Paint.Join.ROUND
                color = 0xFF6B4A4A.toInt()
            }
            val p2 = android.graphics.Path()
            p2.moveTo(pts[2].first, pts[2].second)
            for (i in 3 until pts.size) p2.lineTo(pts[i].first, pts[i].second)
            canvas.drawPath(p2, edge)
        }
        // Bright segment: points 0 → 2 in vivid salmon.
        if (pts.size >= 2) {
            val bright = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = dpf(3f)
                strokeCap = android.graphics.Paint.Cap.ROUND
                strokeJoin = android.graphics.Paint.Join.ROUND
                color = Todoist.SALMON
            }
            val p1 = android.graphics.Path()
            p1.moveTo(pts[0].first, pts[0].second)
            for (i in 1 until minOf(3, pts.size)) p1.lineTo(pts[i].first, pts[i].second)
            canvas.drawPath(p1, bright)
        }
        // Dots on every point.
        val dotPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.FILL
            color = Todoist.SALMON
        }
        pts.forEach { (x, y) -> canvas.drawCircle(x, y, dpf(4.5f), dotPaint) }
    }
}
