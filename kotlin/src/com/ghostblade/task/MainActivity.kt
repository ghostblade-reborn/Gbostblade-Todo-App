package com.ghostblade.task

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
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
    private var editingId: String? = null
    private var lastDeleted: Pair<TaskItem, Int>? = null

    /* Phase 3 screen state */
    private var boardMode = false
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
        val c = tv(label, 12.5f)
        c.background = Glass.chip(this, on)
        c.setPadding(dp(11f), dp(5f), dp(11f), dp(5f))
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
        val b = tv(label, 14.5f)
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
        val ad = ArrayAdapter(this, android.R.layout.simple_spinner_item, items)
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        sp.adapter = ad
        sp.setSelection(selected.coerceIn(0, items.size - 1))
        sp.background = Glass.panel(this, 13f)
        sp.layoutParams = lp(b = dp(10f))
        return sp
    }

    private fun label(text: String): TextView {
        val l = tv(text, 12.5f, Glass.muted(this@MainActivity))
        l.layoutParams = lp(t = dp(8f), b = dp(4f))
        return l
    }

    private fun dayGroup(text: String): TextView {
        val l = tv(text, 12.5f, Glass.muted(this@MainActivity), true)
        l.layoutParams = lp(t = dp(16f), b = dp(8f))
        return l
    }

    private fun emptyView(emoji: String, text: String): TextView {
        val t = tv("$emoji\n$text", 14f, Glass.muted(this@MainActivity))
        t.gravity = Gravity.CENTER
        t.setLineSpacing(dp(4f).toFloat(), 1.2f)
        t.setPadding(dp(20f), dp(38f), dp(20f), dp(38f))
        return t
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
        LocationReceiver.rearmAll(this)
        buildRoot()
        render()
    }

    private fun buildRoot() {
        root = FrameLayout(this)
        wallpaperHolder = FrameLayout(this)
        wallpaperHolder.addView(Glass.wallpaperView(this), FrameLayout.LayoutParams(-1, -1))
        root.addView(wallpaperHolder, FrameLayout.LayoutParams(-1, -1))

        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        mainColumn = column
        root.addView(column, FrameLayout.LayoutParams(-1, -1))

        // header (glass card)
        val header = LinearLayout(this)
        header.orientation = LinearLayout.VERTICAL
        headerCard = header
        header.background = Glass.panel(this, 26f)
        header.setPadding(dp(16f), dp(14f), dp(16f), dp(12f))
        val hp = FrameLayout.LayoutParams(-1, -2)
        hp.setMargins(dp(12f), dp(10f), dp(12f), dp(4f))
        header.layoutParams = hp
        Glass.elevate(this, header, 26f, 10f)
        headerTitle = tv("Today", 22f, Glass.text(this@MainActivity), true)
        headerSub = tv("", 13f, Glass.muted(this@MainActivity))
        headerSub.layoutParams = lp(t = dp(2f))
        val progWrap = FrameLayout(this)
        progWrap.background = Glass.chip(this, false)
        val pw = LinearLayout.LayoutParams(-1, dp(6f)); pw.topMargin = dp(10f)
        progWrap.layoutParams = pw
        headerProgWrap = progWrap
        headerProg = View(this)
        headerProg.background = Glass.accentGradient(this, 3f)
        progWrap.addView(headerProg, FrameLayout.LayoutParams(0, -1))
        header.addView(headerTitle); header.addView(headerSub); header.addView(progWrap)
        val headerHolder = FrameLayout(this)
        headerHolder.addView(header)
        column.addView(headerHolder)

        val scroll = ScrollView(this)
        scroll.layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(14f), dp(6f), dp(14f), dp(120f))
        scroll.addView(content)
        column.addView(scroll)

        // floating glass tab bar
        navBar = LinearLayout(this)
        navBar.orientation = LinearLayout.HORIZONTAL
        navBar.background = Glass.panel(this, 34f, strong = true)
        navBar.setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
        val np = FrameLayout.LayoutParams(-1, -2)
        np.gravity = Gravity.BOTTOM
        np.setMargins(dp(14f), 0, dp(14f), dp(12f))
        navBar.layoutParams = np
        Glass.elevate(this, navBar, 34f, 16f)
        root.addView(navBar)

        // FAB
        val fab = tv("+", 30f, 0xFFFFFFFF.toInt(), true)
        fab.gravity = Gravity.CENTER
        fab.background = Glass.accentGradient(this, 999f)
        val fp = FrameLayout.LayoutParams(dp(57f), dp(57f))
        fp.gravity = Gravity.BOTTOM or Gravity.END
        fp.setMargins(0, 0, dp(18f), dp(92f))
        fab.layoutParams = fp
        fab.setOnClickListener { openQuickAdd(null) }
        fabBtn = fab
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
        content.removeAllViews()
        val s = sub
        when {
            s != null -> renderSub(s)
            view == "today" -> renderToday()
            view == "inbox" -> renderTaskList(Store.sortTasks(Store.state.tasks.filter { it.projectId == null }), "📥", "Inbox is empty.")
            view == "upcoming" -> renderUpcoming()
            view == "browse" -> renderBrowse()
        }
        // entrance animation: directional slide + stagger
        val order = listOf("inbox", "today", "upcoming", "browse")
        var dx = 0f
        if (s == null && view != lastTab && order.contains(view) && order.contains(lastTab)) {
            dx = if (order.indexOf(view) > order.indexOf(lastTab)) 40f else -40f
        }
        if (order.contains(view) && s == null) lastTab = view
        content.alpha = 0f
        content.translationX = dx
        content.animate().alpha(1f).translationX(0f).setDuration(320).start()
        for (i in 0 until content.childCount) {
            val ch = content.getChildAt(i)
            ch.alpha = 0f; ch.translationY = dp(10f).toFloat()
            ch.animate().alpha(1f).translationY(0f).setStartDelay((i * 30).toLong().coerceAtMost(420)).setDuration(380).start()
        }
        renderNav()
    }

    private fun renderHeader() {
        val s = sub
        headerProgWrap.visibility = View.GONE
        when {
            s != null -> {
                headerTitle.text = subTitle(s)
                headerSub.text = ""
            }
            view == "today" -> {
                headerTitle.text = "Today"
                val done = Store.completionsOn(Dates.today())
                val total = done + Store.state.tasks.count { it.dueDate == Dates.today() || Dates.isOverdue(it) }
                val planned = Store.state.tasks.filter { it.dueDate == Dates.today() }.sumOf { it.durationMin ?: 0 }
                headerSub.text = Dates.fmtDay(Dates.today()) + " · $done done · ${total - done} to go" +
                    (if (planned > 0) " · ⏱ ${planned}m planned" else "") + " · 🔥 ${Store.currentStreak()} day streak"
                headerProgWrap.visibility = View.VISIBLE
                val pct = if (total > 0) done.toFloat() / total else 0f
                headerProgWrap.post {
                    val w = (headerProgWrap.width * pct).toInt()
                    headerProg.layoutParams = FrameLayout.LayoutParams(w, -1)
                }
            }
            view == "inbox" -> {
                headerTitle.text = "Inbox"
                headerSub.text = "${Store.state.tasks.count { it.projectId == null }} tasks with no project"
            }
            view == "upcoming" -> {
                headerTitle.text = "Upcoming"
                headerSub.text = "${Store.state.tasks.count { it.dueDate != null && it.dueDate!! > Dates.today() }} future tasks"
            }
            else -> {
                headerTitle.text = "Browse"
                headerSub.text = "${Store.state.projects.size} projects · ${Store.state.tasks.size} open tasks"
            }
        }
    }

    private fun subTitle(s: String): String {
        if (s.startsWith("filter:")) return "Filter · " + (Store.state.filters.find { it.id == s.substring(7) }?.name ?: "")
        if (s.startsWith("project:")) return "# " + (Store.state.projects.find { it.id == s.substring(8) }?.name ?: "")
        if (s.startsWith("label:")) return "@ " + (Store.state.labels.find { it.id == s.substring(6) }?.name ?: "")
        return mapOf(
            "calendar" to "Calendar", "ramble" to "Ramble", "assist" to "Task & Email Assist",
            "history" to "History", "insights" to "Project Insights", "backups" to "Backups & Sync",
            "stats" to "Productivity", "search" to "Search", "focus" to "Focus Timer",
            "smartplan" to "Smart Plan", "performance" to "Performance"
        )[s] ?: "GhostBlade Task"
    }

    private fun renderNav() {
        navBar.removeAllViews()
        val items = listOf(
            Triple("inbox", "📥", "Inbox"), Triple("today", "📅", "Today"),
            Triple("upcoming", "🗓️", "Upcoming"), Triple("browse", "🔍", "Browse")
        )
        items.forEach { (v, ic, lbl) ->
            val on = sub == null && view == v
            val b = LinearLayout(this)
            b.orientation = LinearLayout.VERTICAL
            b.gravity = Gravity.CENTER
            b.setPadding(dp(14f), dp(7f), dp(14f), dp(7f))
            if (on) b.background = Glass.chip(this, true)
            val icT = tv(ic, 19f); icT.gravity = Gravity.CENTER
            val lbT = tv(lbl, 10.5f, if (on) Glass.text(this@MainActivity) else Glass.muted(this@MainActivity), on); lbT.gravity = Gravity.CENTER
            b.addView(icT); b.addView(lbT)
            b.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            b.setOnClickListener {
                Sfx.nav()
                view = v; sub = null; render()
            }
            navBar.addView(b)
        }
    }

    /* ---------------- task rows ---------------- */

    private fun metaText(t: TaskItem): String {
        val bits = mutableListOf<String>()
        if (t.dueDate != null) {
            bits.add(when {
                Dates.isOverdue(t) -> "⚠ " + Dates.fmtDay(t.dueDate!!)
                t.dueDate == Dates.today() -> "Today"
                t.dueDate == Dates.addDays(Dates.today(), 1) -> "Tomorrow"
                else -> Dates.fmtDay(t.dueDate!!)
            })
        }
        if (t.dueTime != null) bits.add("🕐 " + t.dueTime)
        if (t.recurrence != null) bits.add("🔁")
        if (t.durationMin != null) bits.add("⏱ " + t.durationMin + "m")
        val p = Store.state.projects.find { it.id == t.projectId }
        if (p != null) bits.add("#" + p.name)
        t.labelIds.forEach { id -> Store.state.labels.find { it.id == id }?.let { bits.add("@" + it.name) } }
        if (t.subtasks.isNotEmpty()) bits.add("☰ " + t.subtasks.count { it.done } + "/" + t.subtasks.size)
        if (t.priority < 4) bits.add("P" + t.priority)
        if (t.reminderOffsetMin != null) bits.add("🔔")
        if (t.locLat != null) bits.add("📍")
        return bits.joinToString("   ")
    }

    private fun taskRow(t: TaskItem): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.background = Glass.panel(this, 20f)
        row.setPadding(dp(13f), dp(12f), dp(13f), dp(12f))
        row.layoutParams = lp(b = dp(9f))
        row.gravity = Gravity.TOP

        val check = tv("", 13f, 0xFFFFFFFF.toInt(), true)
        check.gravity = Gravity.CENTER
        check.background = Glass.checkCircle(this, t.priority, false)
        check.layoutParams = LinearLayout.LayoutParams(dp(23f), dp(23f)).apply { topMargin = dp(1f) }
        check.setOnClickListener { completeWithAnim(t, row, check) }
        row.addView(check)

        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        body.layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(11f) }
        val title = tv(t.title, 15.5f)
        body.addView(title)
        val meta = metaText(t)
        if (meta.isNotEmpty()) {
            val m = tv(meta, 12.5f, Glass.muted(this@MainActivity))
            m.layoutParams = lp(t = dp(3f))
            body.addView(m)
        }
        body.setOnClickListener { openEditor(t.id) }
        row.addView(body)
        attachPressFx(row)
        return row
    }

    private fun attachPressFx(v: View) {
        v.setOnTouchListener { view2, ev ->
            when (ev.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    view2.animate().scaleX(0.97f).scaleY(0.97f).setDuration(150).start()
                    handler.postDelayed({
                        if (view2.isPressed) { view2.animate().scaleX(0.94f).scaleY(0.94f).setDuration(200).start(); Sfx.hold() }
                    }, 160)
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                    view2.animate().scaleX(1f).scaleY(1f).setDuration(220).start()
            }
            false
        }
    }

    private fun completeWithAnim(t: TaskItem, row: View, check: TextView) {
        check.background = Glass.checkCircle(this, t.priority, true)
        check.text = "✓"
        Sfx.complete()
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                val vib = getSystemService(VIBRATOR_SERVICE) as? android.os.Vibrator
                vib?.vibrate(android.os.VibrationEffect.createOneShot(15, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } catch (e: Exception) {}
        }
        row.animate().alpha(0f).translationX(dp(40f).toFloat()).setDuration(300).setStartDelay(120)
            .withEndAction {
                val msg = Store.completeTask(t.id)
                if (msg.isNotEmpty()) toast(msg)
                render()
            }.start()
    }

    private fun renderTaskList(list: List<TaskItem>, emoji: String, emptyText: String) {
        if (list.isEmpty()) content.addView(emptyView(emoji, emptyText))
        else list.forEach { content.addView(taskRow(it)) }
    }

    private fun renderToday() {
        content.addView(briefCard())
        val list = Store.sortTasks(Store.state.tasks.filter { it.dueDate == Dates.today() || Dates.isOverdue(it) })
        if (list.isEmpty()) content.addView(emptyView("🎉", "All clear for today.\nTap + to add a task — try \"Gym tomorrow at 7am every day\"."))
        else list.forEach { content.addView(taskRow(it)) }
    }

    private fun briefCard(): LinearLayout {
        val c = card()
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val greet = if (h < 12) "Good morning" else if (h < 17) "Good afternoon" else "Good evening"
        val icon = if (h < 12) "☀️" else if (h < 17) "🌤️" else "🌙"
        c.addView(tv("$icon $greet, Sir", 15.5f, Glass.text(this@MainActivity), true))
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

    private fun renderUpcoming() {
        val fut = Store.sortTasks(Store.state.tasks.filter { it.dueDate != null && it.dueDate!! > Dates.today() })
        if (fut.isEmpty()) {
            content.addView(emptyView("🗓️", "Nothing coming up yet."))
            return
        }
        var last = ""
        fut.forEach { t ->
            if (t.dueDate != last) {
                last = t.dueDate!!
                val lbl = Dates.fmtDay(last) + if (last == Dates.addDays(Dates.today(), 1)) " · Tomorrow" else ""
                content.addView(dayGroup(lbl.uppercase()))
            }
            content.addView(taskRow(t))
        }
    }

    /* ---------------- browse ---------------- */

    private fun menuItem(icon: String, title: String, smallTxt: String?, tag: String?, click: () -> Unit): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.background = Glass.panel(this, 22f)
        row.setPadding(dp(14f), dp(13f), dp(14f), dp(13f))
        row.layoutParams = lp(b = dp(8f))
        val ic = tv(icon, 19f); ic.layoutParams = LinearLayout.LayoutParams(dp(30f), -2)
        row.addView(ic)
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
        col.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        col.addView(tv(title, 15f))
        if (smallTxt != null) col.addView(tv(smallTxt, 12.5f, Glass.muted(this@MainActivity)))
        row.addView(col)
        if (tag != null) row.addView(tv(tag, 11f, Glass.GOLD, true))
        row.setOnClickListener { Sfx.tap(); click() }
        attachPressFx(row)
        return row
    }

    private fun renderBrowse() {
        content.addView(menuItem("🔍", "Search tasks", "Find anything, fast", null) { sub = "search"; render() })
        content.addView(dayGroup("PROJECTS"))
        if (Store.state.projects.isEmpty()) content.addView(tv("No projects yet — add one with #Name in Quick Add.", 13f, Glass.muted(this@MainActivity)))
        Store.state.projects.forEach { p ->
            val n = Store.state.tasks.count { it.projectId == p.id }
            content.addView(menuItem("📁", p.name, "$n open tasks", null) { sub = "project:" + p.id; render() })
        }
        content.addView(menuItem("➕", "New project", null, null) { promptText("Project name:") { name -> Store.projByName(name); Store.save(); render() } })
        content.addView(dayGroup("FILTERS"))
        Store.state.filters.forEach { f ->
            content.addView(menuItem("🔎", f.name, f.query, null) { sub = "filter:" + f.id; render() })
        }
        content.addView(menuItem("➕", "New filter", "e.g. today & p1, #Work, @home, no date", null) {
            promptText("Filter name:") { name ->
                promptText("Filter query:") { q ->
                    Store.state.filters.add(FilterItem(uid(), name, q)); Store.save(); render()
                }
            }
        })
        content.addView(dayGroup("LABELS"))
        if (Store.state.labels.isEmpty()) content.addView(tv("Tag any task with @label in Quick Add — e.g. \"Call mom @family\".", 13f, Glass.muted(this@MainActivity)))
        Store.state.labels.forEach { l ->
            val n = Store.state.tasks.count { it.labelIds.contains(l.id) }
            content.addView(menuItem("🏷️", "@" + l.name, "$n open tasks", null) { sub = "label:" + l.id; render() })
        }
        content.addView(dayGroup("PRO — ALL UNLOCKED"))
        val pro = listOf(
            arrayOf("calendar", "🗓️", "Calendar view", "Month grid of your tasks"),
            arrayOf("focus", "🎯", "Focus timer", "Pomodoro countdown for any task"),
            arrayOf("stats", "📈", "Productivity", "Streak, weekly chart, totals"),
            arrayOf("performance", "📊", "Performance", "7d · 30d · 2m · 1y — done vs missed, streak rate"),
            arrayOf("insights", "💡", "Project insights", "Progress & trends per project"),
            arrayOf("history", "🕘", "Full history", "Every creation & completion"),
            arrayOf("ramble", "🎙️", "Ramble", "Speak or type many tasks at once"),
            arrayOf("assist", "🤖", "Task & Email Assist", "Paste text → tasks · break tasks down"),
            arrayOf("backups", "💾", "Backups & Sync", "Auto snapshots · sync file · export · restore")
        )
        pro.forEach { (k, ic, n, d) -> content.addView(menuItem(ic, n, d, "PRO") { sub = k; render() }) }
        content.addView(appearanceCard())
        val about = card()
        about.addView(tv("GhostBlade Task", 15.5f, Glass.text(this@MainActivity), true))
        val ab = tv("Kotlin edition · data lives on this device · reminders fire even when the app is closed and survive reboot.", 13f, Glass.muted(this@MainActivity))
        ab.layoutParams = lp(t = dp(4f))
        about.addView(ab)
        content.addView(about)
    }

    private fun appearanceCard(): LinearLayout {
        val c = card()
        c.addView(tv("🪟 Appearance", 15.5f, Glass.text(this@MainActivity), true))
        c.addView(label("Theme"))
        val tRow = LinearLayout(this); tRow.orientation = LinearLayout.HORIZONTAL
        listOf("aurora" to "🌌 Aurora", "black" to "⬛ Black", "white" to "⬜ White").forEach { (v, l) ->
            val ch = chipBtn(l, Prefs.theme(this) == v) { Prefs.set(this, "theme", v); applyTheme() }
            ch.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6f) }
            tRow.addView(ch)
        }
        c.addView(tRow)
        val tNote = tv("Black = Apple Music dark · White = frosted light · Aurora = colour wallpapers below", 12f, Glass.muted(this@MainActivity))
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
        val rNote = tv("Detail lines (dates, subtitles, meta) in your accent colour — the Apple Music look. Pairs best with crimson or pink.", 12f, Glass.muted(this@MainActivity))
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

    /** Status bar follows the theme: dark icons on the white frost theme. */
    private fun applyStatusBar() {
        val white = Glass.isWhite(this)
        window.statusBarColor = if (white) 0xFFF2F2F6.toInt() else 0xFF06060A.toInt()
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (white) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else 0
    }

    /** Re-skin the persistent chrome (header, nav, undo bar) after a theme
     *  change; screens and sheets pick the theme up as they render. */
    private fun applyTheme() {
        refreshWallpaper()
        headerCard.background = Glass.panel(this, 26f)
        navBar.background = Glass.panel(this, 34f, strong = true)
        undoBar.background = Glass.panel(this, 22f, strong = true)
        applyStatusBar()
        render()
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
                Store.addTaskFromParse(Parser.parse(spoken))
                Store.save()
                Sfx.add()
                toast("Added from voice 🎙️")
                view = "today"; sub = null; render()
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
                val p = Store.state.projects.find { it.id == s.substring(8) }
                if (p == null) { content.addView(emptyView("📁", "Project not found.")); return }
                val toggle = LinearLayout(this); toggle.orientation = LinearLayout.HORIZONTAL
                toggle.layoutParams = lp(b = dp(10f))
                val lBtn = chipBtn("☰ List", !boardMode) { boardMode = false; render() }
                val bBtn = chipBtn("▦ Board", boardMode) { boardMode = true; render() }
                lBtn.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6f) }
                toggle.addView(lBtn); toggle.addView(bBtn)
                content.addView(toggle)
                if (boardMode) renderBoard(p)
                else {
                    val list = Store.sortTasks(Store.state.tasks.filter { it.projectId == p.id })
                    renderTaskList(list, "📁", "No open tasks in this project.")
                }
            }
            s.startsWith("filter:") -> {
                val f = Store.state.filters.find { it.id == s.substring(7) }
                if (f == null) { content.addView(emptyView("🔎", "Filter not found.")); return }
                val list = Store.sortTasks(Store.state.tasks.filter { Filters.eval(Store.state, it, f.query) })
                renderTaskList(list, "🔎", "No tasks match \"${f.query}\".")
                val del = tv("Delete this filter", 15f, Glass.OVERDUE)
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
                val del = tv("Delete this label", 15f, Glass.OVERDUE)
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

    private fun renderSearch() {
        val inputE = input("Search tasks & notes…")
        val results = LinearLayout(this); results.orientation = LinearLayout.VERTICAL
        results.addView(emptyView("🔍", "Type to search across all your tasks."))
        inputE.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(e: Editable?) {
                val q = e?.toString()?.trim()?.lowercase() ?: ""
                results.removeAllViews()
                if (q.isEmpty()) { results.addView(emptyView("🔍", "Type to search across all your tasks.")); return }
                val list = Store.sortTasks(Store.state.tasks.filter { (it.title + " " + it.notes).lowercase().contains(q) })
                if (list.isEmpty()) results.addView(emptyView("🫥", "No tasks match \"$q\"."))
                else list.forEach { results.addView(taskRow(it)) }
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        content.addView(inputE)
        content.addView(results)
        inputE.requestFocus()
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
        if (Build.VERSION.SDK_INT < 31) return
        try { wallpaperHolder.setRenderEffect(frostEffect(18f, 1.22f)) } catch (e: Exception) { /* visual only */ }
    }

    /** Heavy frost over everything behind a sheet: a double-pass blur for
     *  depth + a saturation lift — the iOS recipe. Closing the sheet restores
     *  the permanent wallpaper frost. */
    private fun setBackdropBlur(on: Boolean) {
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

    private fun showSheet(sheetContent: View) {
        sheetRoot.removeAllViews()
        val scrim = View(this)
        scrim.setBackgroundColor(0xB3020208.toInt())
        scrim.setOnClickListener { closeSheet() }
        sheetRoot.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.background = Glass.sheetPanel(this, 30f)
        panel.setPadding(dp(18f), dp(14f), dp(18f), dp(18f))
        val pp = FrameLayout.LayoutParams(-1, -2)
        pp.gravity = Gravity.BOTTOM
        pp.setMargins(dp(8f), 0, dp(8f), dp(8f))
        panel.layoutParams = pp
        Glass.elevate(this, panel, 30f, 28f)
        val handle = View(this)
        val hg = android.graphics.drawable.GradientDrawable()
        hg.cornerRadius = dp(3f).toFloat()
        hg.setColor(0x59FFFFFF)
        handle.background = hg
        handle.layoutParams = LinearLayout.LayoutParams(dp(38f), dp(5f)).apply {
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
        panel.translationY = dp(60f).toFloat()
        panel.alpha = 0.4f
        panel.animate().translationY(0f).alpha(1f).setDuration(380).start()
    }

    private fun closeSheet() {
        if (sheetRoot.visibility != View.VISIBLE) return
        Sfx.close()
        sheetRoot.visibility = View.GONE
        sheetRoot.removeAllViews()
        fabBtn.visibility = View.VISIBLE
        navBar.visibility = View.VISIBLE
        if (undoWasVisible) { undoBar.visibility = View.VISIBLE; undoWasVisible = false }
        setBackdropBlur(false)
        editingId = null
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
    private var qaCursor: java.util.Calendar = java.util.Calendar.getInstance()
    private var qaUpdating = false

    private fun openQuickAdd(presetDate: String?) {
        qaText = ""
        qaPresetDate = presetDate
        qaDate = presetDate
        qaTime = null
        qaRecurrence = null; qaRecSet = false
        qaPriority = null
        qaProjectSet = false; qaProjectId = null
        qaReminder = null; qaRemSet = false
        qaLabelIds = mutableSetOf()
        qaNotes = ""; qaShowNotes = false
        qaLocLat = null; qaLocLng = null
        qaCursor = java.util.Calendar.getInstance()
        showSheet(buildQuickAdd())
    }

    private fun qaPreviewParse(): Parser.Parsed {
        val p = Parser.parse(qaText)
        if (qaDate != null) p.dueDate = qaDate
        if (qaTime != null) p.dueTime = qaTime
        if (qaRecSet) p.recurrence = qaRecurrence
        if (qaPriority != null) p.priority = qaPriority!!
        if (qaProjectSet && qaProjectId != null) p.projectName = Store.state.projects.find { it.id == qaProjectId }?.name
        return p
    }

    private fun qaPill(label: String, on: Boolean, click: () -> Unit): TextView {
        val b = tv(label, 13f, Glass.text(this@MainActivity), on)
        b.background = Glass.sheetChip(this, on)
        b.setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
        b.setOnClickListener { click() }
        b.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(7f) }
        return b
    }

    private fun buildQuickAdd(): View {
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
        var notesIn: EditText? = null
        val inp = input("e.g. Fix bike tire this weekend")
        inp.setText(qaText)
        inp.setSelection(inp.text.length)
        // input row: text field + red send button beside it (Todoist layout)
        val inputRow = LinearLayout(this); inputRow.orientation = LinearLayout.HORIZONTAL
        inputRow.gravity = Gravity.CENTER_VERTICAL
        inp.layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(8f) }
        inputRow.addView(inp)
        val send = tv("↑", 20f, 0xFFFFFFFF.toInt(), true)
        send.gravity = Gravity.CENTER
        send.background = Glass.accentGradient(this, 999f)
        send.layoutParams = LinearLayout.LayoutParams(dp(44f), dp(44f))
        send.setOnClickListener { qaSubmit(inp, notesIn) }
        inputRow.addView(send)
        col.addView(inputRow)
        val chips = tv("", 12.5f, Glass.muted(this@MainActivity))
        chips.layoutParams = lp(b = dp(4f))
        col.addView(chips)
        val popupBox = LinearLayout(this); popupBox.orientation = LinearLayout.VERTICAL
        popupBox.background = Glass.panel(this, 18f, strong = true)
        popupBox.setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
        popupBox.layoutParams = lp(b = dp(8f))
        popupBox.visibility = View.GONE
        col.addView(popupBox)
        if (qaShowNotes) {
            notesIn = input("Description / notes", qaNotes, multi = true)
            col.addView(notesIn)
        }
        fun refreshChips() {
            chips.text = Parser.describe(qaPreviewParse()).joinToString("   ") +
                (if (qaRemSet) "   🔔" else "") + (if (qaLocLat != null) "   📍" else "") +
                (if (qaLabelIds.isNotEmpty()) "   🏷️ " + qaLabelIds.size else "")
        }
        refreshChips()
        inp.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(e: Editable?) {
                if (qaUpdating) return
                qaText = e?.toString() ?: ""
                refreshChips()
                qaSuggestionPopup(popupBox, inp)
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        inp.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { qaSubmit(inp, notesIn); true } else false
        }
        // toolbar: + · Date · Priority · Reminders · Project (scrolls sideways)
        val bar = LinearLayout(this); bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.layoutParams = LinearLayout.LayoutParams(-2, -2)
        bar.addView(qaPill("＋", false) { showSheet(buildQaPlusMenu()) })
        val dateLbl = when (qaDate) {
            null -> "📅 Date"
            Dates.today() -> "📅 Today"
            Dates.addDays(Dates.today(), 1) -> "📅 Tomorrow"
            else -> "📅 " + Dates.fmtDay(qaDate!!)
        }
        bar.addView(qaPill(dateLbl, qaDate != null) { showSheet(buildQaDatePicker()) })
        bar.addView(qaPill(if (qaPriority != null) "⚑ P" + qaPriority else "⚑ Priority", qaPriority != null) { showSheet(buildQaPriority()) })
        bar.addView(qaPill("⏰ Reminders", qaRemSet) { showSheet(buildQaReminders()) })
        val projName = if (qaProjectSet) qaProjectId?.let { id -> Store.state.projects.find { it.id == id }?.name } ?: "Inbox" else "Inbox"
        bar.addView(qaPill("▣ $projName", qaProjectSet) { showSheet(buildQaProjects()) })
        val barScroll = HorizontalScrollView(this)
        barScroll.isHorizontalScrollBarEnabled = false
        barScroll.layoutParams = lp(t = dp(6f))
        barScroll.addView(bar)
        col.addView(barScroll)
        col.post { inp.requestFocus() }
        return col
    }

    private fun qaSuggestionPopup(popupBox: LinearLayout, inp: EditText) {
        val lastWord = qaText.split(" ", "\n").lastOrNull() ?: ""
        popupBox.removeAllViews()
        when {
            lastWord.startsWith("#") -> {
                val q = lastWord.substring(1).lowercase()
                val matches = Store.state.projects.filter { q.isEmpty() || it.name.lowercase().contains(q) }
                if (matches.isEmpty()) { popupBox.visibility = View.GONE; return }
                popupBox.addView(tv("MY PROJECTS", 11f, Glass.muted(this@MainActivity), true))
                matches.forEach { p ->
                    val row = tv("#  " + p.name, 14.5f)
                    row.setPadding(dp(8f), dp(9f), dp(8f), dp(9f))
                    row.setOnClickListener {
                        qaProjectSet = true; qaProjectId = p.id
                        qaUpdating = true
                        qaText = qaText.removeSuffix(lastWord).trimEnd()
                        inp.setText(qaText); inp.setSelection(qaText.length)
                        qaUpdating = false
                        popupBox.visibility = View.GONE
                        showSheet(buildQuickAdd())
                    }
                    popupBox.addView(row)
                }
                popupBox.visibility = View.VISIBLE
            }
            qaText.contains("!!") && qaPriority == null -> {
                popupBox.addView(tv("PRIORITY", 11f, Glass.muted(this@MainActivity), true))
                val flags = listOf(1 to 0xFFFF453A.toInt(), 2 to 0xFFFF9F0A.toInt(), 3 to 0xFF0A84FF.toInt(), 4 to 0xFF98989F.toInt())
                flags.forEach { (n, color) ->
                    val row = tv("⚑  Priority $n", 14.5f, color)
                    row.setPadding(dp(8f), dp(9f), dp(8f), dp(9f))
                    row.setOnClickListener {
                        qaPriority = n
                        qaUpdating = true
                        qaText = qaText.replace("!!", "").replace(Regex("""\s+"""), " ")
                        inp.setText(qaText); inp.setSelection(qaText.length)
                        qaUpdating = false
                        popupBox.visibility = View.GONE
                        showSheet(buildQuickAdd())
                    }
                    popupBox.addView(row)
                }
                popupBox.visibility = View.VISIBLE
            }
            else -> popupBox.visibility = View.GONE
        }
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
        qaLabelIds.forEach { if (!t.labelIds.contains(it)) t.labelIds.add(it) }
        if (qaNotes.isNotEmpty()) t.notes = qaNotes
        if (qaLocLat != null) { t.locLat = qaLocLat; t.locLng = qaLocLng }
        Reminders.sync(this, t)
        Reminders.syncGeo(this, t)
        Store.save()
        Sfx.add(); closeSheet(); render(); toast("Task added ✅")
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
        if (tag != null) row.addView(tv(tag, 11f, Glass.GOLD, true))
        row.setOnClickListener { click() }
        return row
    }

    private fun buildQaPlusMenu(): View {
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
        col.addView(qaMenuRow("✂️", "Extract tasks…", null) { closeSheet(); sub = "assist"; render() })
        col.addView(qaMenuRow("☰", "Description", null) { qaShowNotes = true; showSheet(buildQuickAdd()) })
        col.addView(qaMenuRow("🏷️", "Labels", null) { showSheet(buildQaLabels()) })
        col.addView(qaMenuRow("🎯", "Deadline", "PRO") { showSheet(buildQaDatePicker()) })
        col.addView(qaMenuRow("📍", "Location", "PRO") { captureLocationQa() })
        val back = ghostBtn("← Back") { showSheet(buildQuickAdd()) }
        back.layoutParams = lp(t = dp(10f))
        col.addView(back)
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
        val done = accentBtn("Done") { showSheet(buildQuickAdd()) }
        col.addView(done)
        return col
    }

    /* ----- Quick Add: priority ----- */

    private fun buildQaPriority(): View {
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
        col.addView(tv("Priority", 16f, Glass.text(this@MainActivity), true))
        val flags = listOf(1 to 0xFFFF453A.toInt(), 2 to 0xFFFF9F0A.toInt(), 3 to 0xFF0A84FF.toInt(), 4 to 0xFF98989F.toInt())
        flags.forEach { (n, color) ->
            val row = tv("⚑  Priority $n" + if (qaPriority == n) "  ✓" else "", 15.5f, color)
            row.setPadding(dp(4f), dp(12f), dp(4f), dp(12f))
            row.setOnClickListener { qaPriority = n; Sfx.tick(); showSheet(buildQuickAdd()) }
            col.addView(row)
        }
        return col
    }

    /* ----- Quick Add: projects ----- */

    private fun buildQaProjects(): View {
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
        col.addView(tv("My Projects", 16f, Glass.text(this@MainActivity), true))
        val inboxRow = tv("▣  Inbox" + if (qaProjectSet && qaProjectId == null) "  ✓" else "", 15f)
        inboxRow.setPadding(dp(4f), dp(12f), dp(4f), dp(12f))
        inboxRow.setOnClickListener { qaProjectSet = true; qaProjectId = null; showSheet(buildQuickAdd()) }
        col.addView(inboxRow)
        Store.state.projects.forEach { p ->
            val row = tv("#  " + p.name + if (qaProjectSet && qaProjectId == p.id) "  ✓" else "", 15f)
            row.setPadding(dp(4f), dp(12f), dp(4f), dp(12f))
            row.setOnClickListener { qaProjectSet = true; qaProjectId = p.id; showSheet(buildQuickAdd()) }
            col.addView(row)
        }
        val add = ghostBtn("＋ New project") {
            promptText("Project name:") { name ->
                val p = Store.projByName(name)
                qaProjectSet = true; qaProjectId = p.id
                Store.save()
                showSheet(buildQuickAdd())
            }
        }
        add.layoutParams = lp(t = dp(8f))
        col.addView(add)
        return col
    }

    /* ----- Quick Add: reminders ----- */

    private fun buildQaReminders(): View {
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
        col.addView(tv("Reminders", 16f, Glass.text(this@MainActivity), true))
        val parsed = Parser.parse(qaText)
        if (qaDate == null && parsed.dueDate == null) {
            val hint = tv("Add a day and time to the task first — pick a date, then choose a reminder.", 13f, Glass.OVERDUE)
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
            row.setOnClickListener { qaRemSet = true; qaReminder = v; Sfx.tick(); showSheet(buildQuickAdd()) }
            col.addView(row)
        }
        return col
    }

    /* ----- Quick Add: date picker ----- */

    private fun buildQaDatePicker(): View {
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
        col.addView(tv("Date", 17f, Glass.text(this@MainActivity), true))
        val dayNames = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        fun quickRow(icon: String, lbl: String, date: String) {
            val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(4f), dp(12f), dp(4f), dp(12f))
            val ic = tv(icon, 16f); ic.layoutParams = LinearLayout.LayoutParams(dp(34f), -2)
            row.addView(ic)
            val tt = tv(lbl + if (qaDate == date) "  ✓" else "", 15f)
            tt.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            row.addView(tt)
            row.addView(tv(dayNames[Dates.jsDayOfWeek(Dates.parse(date))], 13f, Glass.muted(this@MainActivity)))
            row.setOnClickListener { qaDate = date; Sfx.tick(); showSheet(buildQaDatePicker()) }
            col.addView(row)
        }
        quickRow("📅", "Today", Dates.today())
        quickRow("🌅", "Tomorrow", Dates.addDays(Dates.today(), 1))
        quickRow("⏭️", "Next week", Dates.addDays(Dates.today(), 7))
        if (qaDate != null) {
            val clr = tv("✕  Clear date", 14.5f, Glass.OVERDUE)
            clr.setPadding(dp(4f), dp(10f), dp(4f), dp(10f))
            clr.setOnClickListener { qaDate = null; qaTime = null; showSheet(buildQaDatePicker()) }
            col.addView(clr)
        }
        // month grid
        val monthNames = arrayOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
        val head = LinearLayout(this); head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.layoutParams = lp(t = dp(10f), b = dp(4f))
        val prev = tv("‹", 22f, Glass.text(this@MainActivity), true); prev.setPadding(dp(8f), dp(2f), dp(8f), dp(2f))
        prev.setOnClickListener { qaCursor.add(java.util.Calendar.MONTH, -1); showSheet(buildQaDatePicker()) }
        val mt = tv(monthNames[qaCursor.get(java.util.Calendar.MONTH)] + " " + qaCursor.get(java.util.Calendar.YEAR), 14.5f, Glass.text(this@MainActivity), true)
        mt.layoutParams = LinearLayout.LayoutParams(0, -2, 1f); mt.gravity = Gravity.CENTER
        val next = tv("›", 22f, Glass.text(this@MainActivity), true); next.setPadding(dp(8f), dp(2f), dp(8f), dp(2f))
        next.setOnClickListener { qaCursor.add(java.util.Calendar.MONTH, 1); showSheet(buildQaDatePicker()) }
        head.addView(prev); head.addView(mt); head.addView(next)
        col.addView(head)
        val dowRow = LinearLayout(this); dowRow.orientation = LinearLayout.HORIZONTAL
        listOf("M", "T", "W", "T", "F", "S", "S").forEach { d ->
            val t = tv(d, 11f, Glass.muted(this@MainActivity), true); t.gravity = Gravity.CENTER
            t.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            dowRow.addView(t)
        }
        col.addView(dowRow)
        val y = qaCursor.get(java.util.Calendar.YEAR)
        val mo = qaCursor.get(java.util.Calendar.MONTH)
        val first = java.util.Calendar.getInstance(); first.set(y, mo, 1, 0, 0, 0)
        val startDow = (Dates.jsDayOfWeek(first) + 6) % 7 // Monday-first
        val dim = first.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
        var day = 1
        for (week in 0 until 6) {
            if (day > dim) break
            val rowL = LinearLayout(this); rowL.orientation = LinearLayout.HORIZONTAL
            for (dowI in 0 until 7) {
                val cell = tv("", 13.5f); cell.gravity = Gravity.CENTER
                cell.layoutParams = LinearLayout.LayoutParams(0, dp(38f), 1f)
                if (!(week == 0 && dowI < startDow) && day <= dim) {
                    val ds = y.toString() + "-" + (mo + 1).toString().padStart(2, '0') + "-" + day.toString().padStart(2, '0')
                    cell.text = day.toString()
                    when {
                        ds == qaDate -> { cell.background = Glass.accentGradient(this, 999f); cell.setTextColor(0xFFFFFFFF.toInt()) }
                        ds == Dates.today() -> cell.setTextColor(Accents.base(this))
                    }
                    val dd = ds
                    cell.setOnClickListener { qaDate = dd; Sfx.tick(); showSheet(buildQaDatePicker()) }
                    day++
                }
                rowL.addView(cell)
            }
            col.addView(rowL)
        }
        // time + repeat rows
        val timeRow = qaMenuRow("🕐", if (qaTime != null) "Time · " + qaTime else "Add time", null) { showSheet(buildQaTimePicker()) }
        col.addView(timeRow)
        val recLbl = when {
            !qaRecSet || qaRecurrence == null -> "Repeat"
            qaRecurrence == "daily" -> "Repeat · Daily"
            qaRecurrence == "weekly" -> "Repeat · Weekly"
            qaRecurrence == "monthly" -> "Repeat · Monthly"
            else -> "Repeat · " + qaRecurrence
        }
        col.addView(qaMenuRow("🔁", recLbl, null) { showSheet(buildQaRepeat()) })
        val save = accentBtn("Save") { showSheet(buildQuickAdd()) }
        save.layoutParams = lp(t = dp(10f))
        col.addView(save)
        return col
    }

    private fun buildQaTimePicker(): View {
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
        col.addView(tv("Time", 16f, Glass.text(this@MainActivity), true))
        val inp = input("HH:mm — e.g. 18:30", qaTime ?: "")
        col.addView(inp)
        val chipsRow = LinearLayout(this); chipsRow.orientation = LinearLayout.HORIZONTAL
        listOf("07:00", "09:00", "12:00", "18:00", "21:00").forEach { tm ->
            val ch = chipBtn(tm, qaTime == tm) { qaTime = tm; showSheet(buildQaDatePicker()) }
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
            showSheet(buildQaDatePicker())
        }
        save.layoutParams = lp(t = dp(10f), b = dp(6f))
        col.addView(save)
        if (qaTime != null) {
            val clr = ghostBtn("Clear time") { qaTime = null; showSheet(buildQaDatePicker()) }
            col.addView(clr)
        }
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
            val row = tv("🔁  $lbl" + if (on) "  ✓" else "", 15f)
            row.setPadding(dp(4f), dp(11f), dp(4f), dp(11f))
            row.setOnClickListener {
                qaRecSet = true; qaRecurrence = v
                if (v != null && v.startsWith("weekly:") && qaDate == null) {
                    qaDate = Dates.nextWeekday(v.substringAfter(':').toInt())
                }
                Sfx.tick()
                showSheet(buildQaDatePicker())
            }
            col.addView(row)
        }
        return col
    }

    /* ----- Quick Add: location capture ----- */

    private fun captureLocationQa() {
        if (Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 77)
            toast("Allow location permission, then tap again")
            showSheet(buildQuickAdd())
            return
        }
        val lm = getSystemService(LOCATION_SERVICE) as? LocationManager
        if (lm == null) { toast("Location unavailable"); showSheet(buildQuickAdd()); return }
        toast("Getting your location…")
        fun apply(lat: Double, lng: Double) {
            qaLocLat = lat; qaLocLng = lng
            toast("Location attached 📍")
            showSheet(buildQuickAdd())
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
                override fun onLocationChanged(l: Location) = apply(l.latitude, l.longitude)
                override fun onStatusChanged(p: String?, s: Int, bundle: Bundle?) {}
                override fun onProviderEnabled(p: String) {}
                override fun onProviderDisabled(p: String) {}
            }
            var any = false
            try { lm.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, listener, Looper.getMainLooper()); any = true } catch (e: Exception) {}
            try { lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, listener, Looper.getMainLooper()); any = true } catch (e: Exception) {}
            if (!any) { toast("Could not get location — check GPS"); showSheet(buildQuickAdd()) }
        } catch (e: SecurityException) {
            toast("Location permission needed")
            showSheet(buildQuickAdd())
        }
    }

    /* ---------------- editor ---------------- */

    private fun openEditor(id: String) {
        val t = Store.byId(id) ?: return
        editingId = id
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

        col.addView(label("Duration (minutes)"))
        val edDur = input("e.g. 30", t.durationMin?.toString() ?: ""); col.addView(edDur)

        val remLabels = listOf("No reminder", "At due time", "10 min before", "30 min before", "1 hour before", "1 day before")
        val remValues = listOf<Int?>(null, 0, 10, 30, 60, 1440)
        col.addView(label("Reminder (fires even if app is closed)"))
        val edRem = spinner(remLabels, remValues.indexOf(t.reminderOffsetMin).coerceAtLeast(0)); col.addView(edRem)

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
            t.durationMin = edDur.text.toString().trim().toIntOrNull()?.takeIf { it > 0 }
            t.reminderOffsetMin = remValues[edRem.selectedItemPosition]
        }

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
            col.addView(tv("Stand at the place (home, office, gym), tap the button — the app remembers it and reminds you when you arrive.", 12.5f, Glass.muted(this@MainActivity)))
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
            val stT = tv(st.title, 14.5f, if (st.done) Glass.muted(this@MainActivity) else Glass.text(this@MainActivity))
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
            t.durationMin = edDur.text.toString().trim().toIntOrNull()?.takeIf { it > 0 }
            t.reminderOffsetMin = remValues[edRem.selectedItemPosition]
            Reminders.sync(this, t)
            Reminders.syncGeo(this, t)
            Store.save()
            Sfx.tick(); closeSheet(); render(); toast("Saved ✅")
        }
        col.addView(save)
        val del = tv("Delete task", 15f, Glass.OVERDUE)
        del.gravity = Gravity.CENTER
        del.setPadding(dp(12f), dp(14f), dp(12f), dp(6f))
        del.setOnClickListener {
            Sfx.del()
            val removed = Store.deleteTask(t.id)
            closeSheet(); render()
            if (removed != null) {
                val idx = Store.state.tasks.indexOfFirst { it.id == t.id }
                showUndo(removed, idx)
            }
        }
        col.addView(del)
        return col
    }

    private fun showUndo(task: TaskItem, @Suppress("UNUSED_PARAMETER") ignoredIndex: Int) {
        lastDeleted = task to 0
        undoBar.removeAllViews()
        val lbl = tv("Deleted \"${task.title}\"", 13.5f)
        lbl.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(10f) }
        undoBar.addView(lbl)
        val btn = tv("UNDO", 14f, Glass.GOLD, true)
        btn.setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
        btn.setOnClickListener {
            val (task2, _) = lastDeleted ?: return@setOnClickListener
            Store.state.tasks.add(task2)
            Reminders.sync(this, task2); Reminders.syncGeo(this, task2)
            lastDeleted = null
            undoBar.visibility = View.GONE
            Store.save(); render(); toast("Restored ↩️")
        }
        undoBar.addView(btn)
        undoBar.visibility = View.VISIBLE
        undoHide?.let { handler.removeCallbacks(it) }
        undoHide = Runnable { undoBar.visibility = View.GONE; lastDeleted = null }
        handler.postDelayed(undoHide!!, 5000)
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
        wrap.post { fill.layoutParams = FrameLayout.LayoutParams((wrap.width * pct).toInt(), dp(7f)) }
        return wrap
    }

    /* ----- Calendar ----- */

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
        val next = tv("›", 26f, Glass.text(this@MainActivity), true); next.setPadding(dp(10f), dp(2f), dp(10f), dp(2f))
        next.setOnClickListener { calCursor.add(java.util.Calendar.MONTH, 1); render() }
        head.addView(prev); head.addView(title); head.addView(next)
        content.addView(head)

        val calCard = card()
        val dow = LinearLayout(this); dow.orientation = LinearLayout.HORIZONTAL
        listOf("S", "M", "T", "W", "T", "F", "S").forEach { d ->
            val t = tv(d, 11.5f, Glass.muted(this@MainActivity), true); t.gravity = Gravity.CENTER
            t.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            dow.addView(t)
        }
        calCard.addView(dow)
        val first = java.util.Calendar.getInstance()
        first.set(year, month, 1, 0, 0, 0)
        val startDow = Dates.jsDayOfWeek(first)
        val daysInMonth = first.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
        var day = 1
        for (week in 0 until 6) {
            if (day > daysInMonth) break
            val rowL = LinearLayout(this); rowL.orientation = LinearLayout.HORIZONTAL
            for (dowI in 0 until 7) {
                val cell = tv("", 13.5f); cell.gravity = Gravity.CENTER
                cell.layoutParams = LinearLayout.LayoutParams(0, dp(42f), 1f)
                if (week == 0 && dowI < startDow || day > daysInMonth) {
                    // blank cell
                } else {
                    val ds = year.toString() + "-" + (month + 1).toString().padStart(2, '0') + "-" + day.toString().padStart(2, '0')
                    val n = Store.state.tasks.count { it.dueDate == ds }
                    cell.text = day.toString() + if (n > 0) "\n" + "•".repeat(minOf(n, 3)) else ""
                    cell.textSize = 13.5f
                    cell.setTextColor(if (Dates.isOverdue(TaskItem(id = "", title = "", dueDate = ds))) Glass.OVERDUE else Glass.text(this@MainActivity))
                    when {
                        ds == selDay -> cell.background = Glass.chip(this, true)
                        ds == Dates.today() -> {
                            val g = android.graphics.drawable.GradientDrawable()
                            g.cornerRadius = dp(12f).toFloat()
                            g.setStroke(dp(1.5f), Accents.base(this))
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
            content.addView(dayGroup((if (sd == Dates.today()) "TODAY · " else "") + Dates.fmtDay(sd).uppercase()))
            val list = Store.sortTasks(Store.state.tasks.filter { it.dueDate == sd })
            if (list.isEmpty()) content.addView(emptyView("🌤️", "Nothing scheduled this day."))
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
            val ch = chipBtn(m.toString() + "m", focusTotal == m * 60) {
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
        val stateT = tv(if (focusRunning) "Focusing…" else "Ready when you are", 13.5f, Glass.muted(this@MainActivity))
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
        content.addView(tv("Tip: set a task duration in its editor and the timer picks it up automatically. Leaving this screen pauses the timer.", 12.5f, Glass.muted(this@MainActivity)))
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
        c.addView(tv(big, 21f, Glass.text(this@MainActivity), true))
        c.addView(tv(smallTxt, 12f, Glass.muted(this@MainActivity)))
        return c
    }

    private fun renderStats() {
        val grid1 = LinearLayout(this); grid1.orientation = LinearLayout.HORIZONTAL
        val sc1 = statCard("🔥 " + Store.currentStreak(), "day streak")
        sc1.layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(9f) }
        val sc2 = statCard(Store.completionsOn(Dates.today()).toString(), "done today")
        sc2.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        grid1.addView(sc1); grid1.addView(sc2)
        content.addView(grid1)
        val last7 = (6 downTo 0).sumOf { Store.completionsOn(Dates.addDays(Dates.today(), -it)) }
        val grid2 = LinearLayout(this); grid2.orientation = LinearLayout.HORIZONTAL
        val sc3 = statCard(last7.toString(), "done in 7 days")
        sc3.layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(9f) }
        val sc4 = statCard(Store.state.completions.size.toString(), "all-time done")
        sc4.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        grid2.addView(sc3); grid2.addView(sc4)
        content.addView(grid2)

        val chart = card()
        chart.addView(tv("Last 7 days", 14.5f, Glass.text(this@MainActivity), true))
        val counts = (6 downTo 0).map { Store.completionsOn(Dates.addDays(Dates.today(), -it)) }
        val max = Math.max(1, counts.maxOrNull() ?: 1)
        val bars = LinearLayout(this); bars.orientation = LinearLayout.HORIZONTAL
        bars.gravity = Gravity.BOTTOM
        bars.layoutParams = lp(t = dp(12f))
        val dayNames = arrayOf("Su", "Mo", "Tu", "We", "Th", "Fr", "Sa")
        counts.forEachIndexed { i, n ->
            val colL = LinearLayout(this); colL.orientation = LinearLayout.VERTICAL
            colL.gravity = Gravity.CENTER_HORIZONTAL
            colL.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            colL.addView(tv(n.toString(), 11.5f, Glass.muted(this@MainActivity), true))
            val bar = View(this)
            val h = dp(8f) + (dp(74f) * n / max)
            bar.layoutParams = LinearLayout.LayoutParams(dp(20f), h).apply { topMargin = dp(4f); bottomMargin = dp(4f) }
            val g = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Accents.acc2(this), Accents.acc1(this))
            )
            g.cornerRadius = dp(6f).toFloat()
            bar.background = g
            colL.addView(bar)
            val d = Dates.parse(Dates.addDays(Dates.today(), -(6 - i)))
            colL.addView(tv(dayNames[Dates.jsDayOfWeek(d)], 11f, Glass.muted(this@MainActivity)))
            bars.addView(colL)
        }
        chart.addView(bars)
        content.addView(chart)

        val per = card()
        per.addView(tv("Completions by project", 14.5f, Glass.text(this@MainActivity), true))
        val byProj = Store.state.projects.map { p ->
            p.name to Store.state.completions.count { it.projectId == p.id }
        }.filter { it.second > 0 }.sortedByDescending { it.second }
        if (byProj.isEmpty()) per.addView(tv("Complete a task and it will show up here.", 13f, Glass.muted(this@MainActivity)))
        byProj.forEach { (name, n) ->
            val rowL = LinearLayout(this); rowL.orientation = LinearLayout.HORIZONTAL
            rowL.layoutParams = lp(t = dp(6f))
            val nm = tv("# $name", 14f); nm.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            rowL.addView(nm); rowL.addView(tv(n.toString(), 14f, Glass.text(this@MainActivity), true))
            per.addView(rowL)
        }
        content.addView(per)
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
        c1.layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(9f) }
        val c2 = statCard(done.size.toString(), "tasks done")
        c2.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        g1.addView(c1); g1.addView(c2)
        content.addView(g1)
        val g2 = LinearLayout(this); g2.orientation = LinearLayout.HORIZONTAL
        val c3 = statCard(missed.size.toString(), "tasks missed")
        c3.layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(9f) }
        val c4 = statCard("$streakRate%", "streak rate · $active/$perfRange days active")
        c4.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        g2.addView(c3); g2.addView(c4)
        content.addView(g2)
        val g3 = LinearLayout(this); g3.orientation = LinearLayout.HORIZONTAL
        val c5 = statCard("🔥 " + Store.currentStreak(), "current streak (days)")
        c5.layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(9f) }
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
            if (perfRange <= 30) colL.addView(tv(n.toString(), 10f, Glass.muted(this@MainActivity), true))
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
            colL.addView(tv(lbl, 9.5f, Glass.muted(this@MainActivity)))
            bars.addView(colL)
        }
        chart.addView(bars)
        content.addView(chart)

        // per-project
        val projRows = Db.doneByProject(from, today)
        if (projRows.isNotEmpty()) {
            val pc = card()
            pc.addView(tv("Done by project", 14.5f, Glass.text(this@MainActivity), true))
            projRows.take(6).forEach { (pid, n) ->
                val name = Store.state.projects.find { it.id == pid }?.name ?: "Inbox / deleted"
                val rowL = LinearLayout(this); rowL.orientation = LinearLayout.HORIZONTAL
                rowL.layoutParams = lp(t = dp(6f))
                val nm = tv("# $name", 14f); nm.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                rowL.addView(nm); rowL.addView(tv(n.toString(), 14f, Glass.text(this@MainActivity), true))
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
                content.addView(tv(if (c.date == today) "Today" else Dates.fmtDay(c.date), 12.5f, Accents.acc1(this), true))
            }
            val proj = Store.state.projects.find { it.id == c.projectId }
            content.addView(tv("✅  " + c.title + if (proj != null) "   #" + proj.name else "", 14f))
        }
        if (done.size > 80) content.addView(tv("…and ${done.size - 80} more", 12.5f, Glass.muted(this@MainActivity)))

        // missed list
        content.addView(dayGroup("✗ MISSED — ${missed.size}"))
        if (missed.isEmpty()) content.addView(tv("No missed tasks in this period. Clean record 🎉", 13f, Glass.muted(this@MainActivity)))
        missed.take(80).forEach { m ->
            val proj = Store.state.projects.find { it.id == m.projectId }
            val whenLbl = if (m.date == Dates.addDays(today, -1)) "Yesterday" else Dates.fmtDay(m.date)
            content.addView(tv("✗  " + m.title + "   ·  " + whenLbl + if (proj != null) "   #" + proj.name else "", 14f, Glass.muted(this@MainActivity)))
        }
        if (missed.size > 80) content.addView(tv("…and ${missed.size - 80} more", 12.5f, Glass.muted(this@MainActivity)))

        content.addView(tv("Stored in your on-device database (SQLite). Missed days are recorded automatically — including every day a recurring task came due and wasn't done. History grows from your real usage.", 12f, Glass.muted(this@MainActivity)))
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
            c.addView(tv("🏆 Busiest project", 13f, Glass.GOLD, true))
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
            headL.addView(tv("${(r.pct * 100).toInt()}% weekly completion", 12.5f, Glass.muted(this@MainActivity)))
            c.addView(headL)
            c.addView(tv("${r.open} open · ${r.done7} done in the last 7 days", 13f, Glass.muted(this@MainActivity)))
            c.addView(progressBar(r.pct))
            content.addView(c)
        }
    }

    /* ----- History ----- */

    private fun renderHistory() {
        if (Store.state.activities.isEmpty()) {
            content.addView(emptyView("🕘", "Nothing yet — your activity will appear here."))
            return
        }
        val c = card(8)
        Store.state.activities.take(150).forEach { a ->
            val rowL = LinearLayout(this); rowL.orientation = LinearLayout.HORIZONTAL
            rowL.setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
            val icon = when (a.type) {
                "create" -> "➕"; "complete" -> "✅"; "delete" -> "🗑️"
                "seed" -> "🌱"; "backup" -> "💾"; "focus" -> "🎯"; else -> "•"
            }
            val ic = tv(icon, 15f); ic.layoutParams = LinearLayout.LayoutParams(dp(30f), -2)
            rowL.addView(ic)
            val colL = LinearLayout(this); colL.orientation = LinearLayout.VERTICAL
            colL.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            colL.addView(tv(a.text, 14f))
            colL.addView(tv(fmtWhen(a.at), 12f, Glass.muted(this@MainActivity)))
            rowL.addView(colL)
            c.addView(rowL)
        }
        content.addView(c)
    }

    /* ----- Ramble ----- */

    private fun renderRamble() {
        val c = card()
        c.addView(tv("Say it or type it — one task per line. Dates, #projects, @labels and p1 work in every line.", 13.5f, Glass.muted(this@MainActivity)))
        val ta = input("e.g.\nCall mom tomorrow @family\nBuy milk #Shopping\nGym at 7am every day p2", "", multi = true)
        ta.minLines = 5
        ta.gravity = Gravity.TOP
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
        c.addView(tv("Paste an email, message or brain-dump — one task per line. GhostBlade extracts clean tasks and keeps dates, projects and priorities.", 13.5f, Glass.muted(this@MainActivity)))
        val ta = input("Paste text here…", "", multi = true)
        ta.minLines = 6
        ta.gravity = Gravity.TOP
        c.addView(ta)
        val ex = accentBtn("Extract tasks") {
            val lines = extractLines(ta.text.toString())
            if (lines.isEmpty()) { toast("Nothing found — paste some text first"); return@accentBtn }
            lines.forEach { line -> Store.addTaskFromParse(Parser.parse(line)) }
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
            if (Build.VERSION.SDK_INT >= 29) {
                val cv = android.content.ContentValues()
                cv.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                cv.put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/json")
                cv.put(android.provider.MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                    ?: throw Exception("insert failed")
                val os = contentResolver.openOutputStream(uri) ?: throw Exception("open failed")
                os.write(text.toByteArray(Charsets.UTF_8)); os.close()
            } else {
                val f = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), name)
                val os = FileOutputStream(f)
                os.write(text.toByteArray(Charsets.UTF_8)); os.close()
            }
            toast("Saved to Downloads: $name")
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
            colL.addView(tv(fmtWhen(b.at), 14.5f))
            colL.addView(tv(if (b.auto) "Automatic" else "Manual", 12.5f, Glass.muted(this@MainActivity)))
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
        intro.addView(tv("🤖 Smart Plan", 15.5f, Glass.text(this@MainActivity), true))
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
            rowL.background = Glass.panel(this, 18f)
            rowL.setPadding(dp(13f), dp(11f), dp(13f), dp(11f))
            rowL.layoutParams = lp(b = dp(8f))
            val time = tv(start, 14.5f, Accents.acc1(this), true)
            time.layoutParams = LinearLayout.LayoutParams(dp(56f), -2)
            rowL.addView(time)
            val colL = LinearLayout(this); colL.orientation = LinearLayout.VERTICAL
            colL.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            colL.addView(tv(t.title, 14.5f))
            colL.addView(tv("P${t.priority} · $dur min", 12f, Glass.muted(this@MainActivity)))
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
        val cols = listOf("todo" to "To do", "doing" to "Doing", "done" to "Done")
        val board = LinearLayout(this); board.orientation = LinearLayout.HORIZONTAL
        cols.forEach { (status, name) ->
            val colL = LinearLayout(this); colL.orientation = LinearLayout.VERTICAL
            colL.background = Glass.panel(this, 18f)
            colL.setPadding(dp(6f), dp(8f), dp(6f), dp(8f))
            colL.layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply {
                rightMargin = if (status != "done") dp(7f) else 0
            }
            val headT = tv(name.uppercase(), 11f, Glass.muted(this@MainActivity), true); headT.gravity = Gravity.CENTER
            colL.addView(headT)
            val tasks = Store.state.tasks.filter { it.projectId == p.id && it.status == status }
            if (tasks.isEmpty()) {
                val e = tv("—", 12f, Glass.muted(this@MainActivity)); e.gravity = Gravity.CENTER
                colL.addView(e)
            }
            tasks.forEach { t ->
                val cardL = LinearLayout(this); cardL.orientation = LinearLayout.VERTICAL
                cardL.background = Glass.panel(this, 14f)
                cardL.setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
                cardL.layoutParams = lp(t = dp(7f))
                cardL.addView(tv(t.title, 12.5f))
                val meta = mutableListOf<String>()
                if (t.priority < 4) meta.add("P" + t.priority)
                if (t.dueDate != null) meta.add(if (t.dueDate == Dates.today()) "Today" else Dates.fmtDay(t.dueDate!!))
                if (meta.isNotEmpty()) cardL.addView(tv(meta.joinToString(" · "), 10.5f, Glass.muted(this@MainActivity)))
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
                override fun onLocationChanged(l: Location) = applyLocation(task, l.latitude, l.longitude)
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
        showSheet(buildEditor(task))
        toast("Location reminder set 📍")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (sheetRoot.visibility == View.VISIBLE) { closeSheet(); return }
        if (sub != null) { sub = null; render(); return }
        super.onBackPressed()
    }
}
