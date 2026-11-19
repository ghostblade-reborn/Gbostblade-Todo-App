package com.ghostblade.task

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
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
    private val handler = android.os.Handler(Looper.getMainLooper())
    private var undoHide: Runnable? = null

    private fun dp(v: Float) = Glass.dp(this, v)

    /* ---------------- view builders ---------------- */

    private fun tv(text: String, size: Float = 15f, color: Int = Glass.TEXT, bold: Boolean = false): TextView {
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
        e.setTextColor(Glass.TEXT)
        e.setHintTextColor(Glass.MUTED)
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
        val l = tv(text, 12.5f, Glass.MUTED)
        l.layoutParams = lp(t = dp(8f), b = dp(4f))
        return l
    }

    private fun dayGroup(text: String): TextView {
        val l = tv(text, 12.5f, Glass.MUTED, true)
        l.layoutParams = lp(t = dp(16f), b = dp(8f))
        return l
    }

    private fun emptyView(emoji: String, text: String): TextView {
        val t = tv("$emoji\n$text", 14f, Glass.MUTED)
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
        root.addView(column, FrameLayout.LayoutParams(-1, -1))

        // header (glass card)
        val header = LinearLayout(this)
        header.orientation = LinearLayout.VERTICAL
        header.background = Glass.panel(this, 26f)
        header.setPadding(dp(16f), dp(14f), dp(16f), dp(12f))
        val hp = FrameLayout.LayoutParams(-1, -2)
        hp.setMargins(dp(12f), dp(10f), dp(12f), dp(4f))
        header.layoutParams = hp
        headerTitle = tv("Today", 22f, Glass.TEXT, true)
        headerSub = tv("", 13f, Glass.MUTED)
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
        root.addView(undoBar)

        sheetRoot = FrameLayout(this)
        sheetRoot.visibility = View.GONE
        root.addView(sheetRoot, FrameLayout.LayoutParams(-1, -1))

        setContentView(root)
    }

    /* ---------------- render ---------------- */

    private val TITLES = mapOf("inbox" to "Inbox", "today" to "Today", "upcoming" to "Upcoming", "browse" to "Browse")

    private fun render() {
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
            "stats" to "Productivity", "search" to "Search", "focus" to "Focus Timer"
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
            val lbT = tv(lbl, 10.5f, if (on) Glass.TEXT else Glass.MUTED, on); lbT.gravity = Gravity.CENTER
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
            val m = tv(meta, 12.5f, Glass.MUTED)
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
        c.addView(tv("$icon $greet, Sir", 15.5f, Glass.TEXT, true))
        val over = Store.state.tasks.count { Dates.isOverdue(it) }
        val todayN = Store.state.tasks.count { it.dueDate == Dates.today() }
        val sub2 = tv(
            "$todayN tasks scheduled today" + if (over > 0) " · $over overdue" else " · nothing overdue 🎉",
            13f, Glass.MUTED
        )
        sub2.layoutParams = lp(t = dp(4f))
        c.addView(sub2)
        val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL
        row.layoutParams = lp(t = dp(10f))
        val plan = ghostBtn("🤖 Smart plan my day") { toast("Smart Plan arrives in Phase 3") }
        plan.layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(8f) }
        val focus = ghostBtn("🎯 Focus timer") { toast("Focus Timer arrives in Phase 3") }
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
        if (smallTxt != null) col.addView(tv(smallTxt, 12.5f, Glass.MUTED))
        row.addView(col)
        if (tag != null) row.addView(tv(tag, 11f, Glass.GOLD, true))
        row.setOnClickListener { Sfx.tap(); click() }
        attachPressFx(row)
        return row
    }

    private fun renderBrowse() {
        content.addView(menuItem("🔍", "Search tasks", "Find anything, fast", null) { sub = "search"; render() })
        content.addView(dayGroup("PROJECTS"))
        if (Store.state.projects.isEmpty()) content.addView(tv("No projects yet — add one with #Name in Quick Add.", 13f, Glass.MUTED))
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
        if (Store.state.labels.isEmpty()) content.addView(tv("Tag any task with @label in Quick Add — e.g. \"Call mom @family\".", 13f, Glass.MUTED))
        Store.state.labels.forEach { l ->
            val n = Store.state.tasks.count { it.labelIds.contains(l.id) }
            content.addView(menuItem("🏷️", "@" + l.name, "$n open tasks", null) { sub = "label:" + l.id; render() })
        }
        content.addView(dayGroup("PRO — ALL UNLOCKED"))
        val pro = listOf(
            arrayOf("calendar", "🗓️", "Calendar view", "Month grid of your tasks"),
            arrayOf("focus", "🎯", "Focus timer", "Pomodoro countdown for any task"),
            arrayOf("stats", "📈", "Productivity", "Streak, weekly chart, totals"),
            arrayOf("insights", "💡", "Project insights", "Progress & trends per project"),
            arrayOf("history", "🕘", "Full history", "Every creation & completion"),
            arrayOf("ramble", "🎙️", "Ramble", "Speak or type many tasks at once"),
            arrayOf("assist", "🤖", "Task & Email Assist", "Paste text → tasks · break tasks down"),
            arrayOf("backups", "💾", "Backups & Sync", "Auto snapshots · sync file · export · restore")
        )
        pro.forEach { (k, ic, n, d) -> content.addView(menuItem(ic, n, d, "PRO") { sub = k; render() }) }
        content.addView(appearanceCard())
        val about = card()
        about.addView(tv("GhostBlade Task", 15.5f, Glass.TEXT, true))
        val ab = tv("Kotlin edition · data lives on this device · reminders fire even when the app is closed and survive reboot.", 13f, Glass.MUTED)
        ab.layoutParams = lp(t = dp(4f))
        about.addView(ab)
        content.addView(about)
    }

    private fun appearanceCard(): LinearLayout {
        val c = card()
        c.addView(tv("🪟 Appearance", 15.5f, Glass.TEXT, true))
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
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
                val list = Store.sortTasks(Store.state.tasks.filter { it.projectId == p.id })
                renderTaskList(list, "📁", "No open tasks in this project.")
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
            else -> content.addView(emptyView("🛠️", "This screen arrives in Phase 3 of the Kotlin build."))
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

    private fun showSheet(sheetContent: View) {
        sheetRoot.removeAllViews()
        val scrim = View(this)
        scrim.setBackgroundColor(0x73020208)
        scrim.setOnClickListener { closeSheet() }
        sheetRoot.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.background = Glass.panel(this, 30f, strong = true)
        panel.setPadding(dp(18f), dp(14f), dp(18f), dp(18f))
        val pp = FrameLayout.LayoutParams(-1, -2)
        pp.gravity = Gravity.BOTTOM
        pp.setMargins(dp(8f), 0, dp(8f), dp(8f))
        panel.layoutParams = pp
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

    /* ---------------- quick add ---------------- */

    private fun openQuickAdd(presetDate: String?) {
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
        col.addView(tv("Quick Add", 17f, Glass.TEXT, true))
        val inp = input("e.g. Gym tomorrow at 7am every day #Health p2 for 45m")
        if (presetDate != null) inp.setTag(presetDate)
        col.addView(inp)
        val chips = tv("", 12.5f, Glass.MUTED)
        col.addView(chips)
        fun submit() {
            val raw = inp.text.toString().trim()
            if (raw.isEmpty()) return
            val p = Parser.parse(raw)
            if (p.title.isEmpty()) { toast("Add a title too"); return }
            val base = TaskItem(id = uid(), title = p.title)
            if (presetDate != null && p.dueDate == null) base.dueDate = presetDate
            Store.addTaskFromParse(p, base)
            Sfx.add(); closeSheet(); render(); toast("Task added ✅")
        }
        inp.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(e: Editable?) {
                val p = Parser.parse(e?.toString() ?: "")
                chips.text = Parser.describe(p).joinToString("   ")
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        inp.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { submit(); true } else false
        }
        val add = accentBtn("Add task") { submit() }
        col.addView(add)
        showSheet(col)
        inp.requestFocus()
    }

    /* ---------------- editor ---------------- */

    private fun openEditor(id: String) {
        val t = Store.byId(id) ?: return
        editingId = id
        showSheet(buildEditor(t))
    }

    private fun buildEditor(t: TaskItem): View {
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
        col.addView(tv("Edit task", 17f, Glass.TEXT, true))
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

        col.addView(label("Location reminder"))
        if (t.locLat != null) {
            col.addView(tv("📍 Set — you'll be reminded when you arrive at the saved place.", 13f, Glass.MUTED))
            val clear = ghostBtn("Clear location reminder") {
                t.locLat = null; t.locLng = null
                LocationReceiver.disarm(this, t.id)
                Store.save(); showSheet(buildEditor(t)); toast("Location reminder cleared")
            }
            col.addView(clear)
        } else {
            val setBtn = ghostBtn("📍 Remind me at this place") { captureLocation(t) }
            col.addView(setBtn)
            col.addView(tv("Stand at the place (home, office, gym), tap the button — the app remembers it and reminds you when you arrive.", 12.5f, Glass.MUTED))
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
            box.setOnClickListener { st.done = !st.done; Sfx.tick(); Store.save(); showSheet(buildEditor(t)) }
            row.addView(box)
            val stT = tv(st.title, 14.5f, if (st.done) Glass.MUTED else Glass.TEXT)
            if (st.done) stT.paintFlags = stT.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            stT.layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(9f) }
            row.addView(stT)
            val del = tv("✕", 14f, Glass.MUTED)
            del.setOnClickListener { t.subtasks.removeAll { it.id == st.id }; Store.save(); showSheet(buildEditor(t)) }
            row.addView(del)
            col.addView(row)
        }
        val subRow = LinearLayout(this); subRow.orientation = LinearLayout.HORIZONTAL
        val subIn = input("Add a subtask"); subIn.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        val subAdd = ghostBtn("Add") {
            val v = subIn.text.toString().trim()
            if (v.isNotEmpty()) { t.subtasks.add(Subtask(uid(), v, false)); Store.save(); showSheet(buildEditor(t)) }
        }
        subRow.addView(subIn); subRow.addView(subAdd)
        col.addView(subRow)
        val breakdown = ghostBtn("🤖 Break it down (auto subtasks)") {
            breakdownFor(t.title).forEach { x -> t.subtasks.add(Subtask(uid(), x, false)) }
            Store.save(); showSheet(buildEditor(t)); toast("Subtasks added 🤖")
        }
        breakdown.layoutParams = lp(t = dp(6f), b = dp(10f))
        col.addView(breakdown)

        val save = accentBtn("Save") {
            t.title = edTitle.text.toString().trim().ifEmpty { t.title }
            t.notes = edNotes.text.toString()
            t.dueDate = edDate.text.toString().trim().ifEmpty { null }
            t.dueTime = edTime.text.toString().trim().ifEmpty { null }
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
