package com.ghostblade.task

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import java.io.File

/** Appearance preferences (glass style, accent, wallpaper, sounds). */
object Prefs {
    const val NAME = "gbt_prefs"
    fun prefs(ctx: Context) = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    fun glass(ctx: Context): String = prefs(ctx).getString("glass", "default") ?: "default"
    fun accent(ctx: Context): String = prefs(ctx).getString("accent", "crimson") ?: "crimson"
    fun wallpaper(ctx: Context): String = prefs(ctx).getString("wallpaper", "aurora") ?: "aurora"
    /** todoist (DEFAULT: flat Todoist dark, see the Todoist tokens) | aurora
     *  (colour wallpapers) | black (Apple Music dark) | white (frosted light).
     *  Only an explicitly stored choice overrides the Todoist default, so
     *  existing users who picked a theme keep it. */
    fun theme(ctx: Context): String = prefs(ctx).getString("theme", "todoist") ?: "todoist"
    /** Apple Music-style red secondary text. */
    fun redText(ctx: Context): Boolean = prefs(ctx).getBoolean("red_text", false)
    fun set(ctx: Context, key: String, value: String) = prefs(ctx).edit().putString(key, value).apply()
    fun setBool(ctx: Context, key: String, value: Boolean) = prefs(ctx).edit().putBoolean(key, value).apply()
}

/** Accent palettes: [base, acc1 (light), acc2 (deep)]. */
object Accents {
    val MAP = mapOf(
        "crimson" to intArrayOf(0xFFFF453A.toInt(), 0xFFFF6A5E.toInt(), 0xFFE44332.toInt()),
        "blue" to intArrayOf(0xFF2F9DFF.toInt(), 0xFF4DA3FF.toInt(), 0xFF0A6CFF.toInt()),
        "violet" to intArrayOf(0xFFBF5AF2.toInt(), 0xFFCF7BFF.toInt(), 0xFF8E2DE0.toInt()),
        "green" to intArrayOf(0xFF30D158.toInt(), 0xFF4BE08A.toInt(), 0xFF0DA35A.toInt()),
        "gold" to intArrayOf(0xFFFFB340.toInt(), 0xFFFFCF3F.toInt(), 0xFFF08C0A.toInt()),
        "pink" to intArrayOf(0xFFFF4D7E.toInt(), 0xFFFF6AB5.toInt(), 0xFFE0215C.toInt())
    )
    fun base(ctx: Context): Int = MAP[Prefs.accent(ctx)]?.get(0) ?: MAP["crimson"]!![0]
    fun acc1(ctx: Context): Int = MAP[Prefs.accent(ctx)]?.get(1) ?: MAP["crimson"]!![1]
    fun acc2(ctx: Context): Int = MAP[Prefs.accent(ctx)]?.get(2) ?: MAP["crimson"]!![2]
    fun soft(ctx: Context): Int = Color.argb(46, Color.red(base(ctx)), Color.green(base(ctx)), Color.blue(base(ctx)))
}

/** Todoist design tokens — pixel-sampled from Sir's reference screenshots
 *  (TEAMONE-ANALYSIS-SCREENS.md §0.1; sampled values win over nominal brand
 *  values). This is the DEFAULT appearance: Prefs.theme() defaults to
 *  "todoist". The legacy Aurora/Black/White glass themes and the Accents
 *  picker are untouched and keep working when explicitly selected.
 *
 *  Helper index for the MainActivity implementer (all in Glass below):
 *   - groupedPanel(ctx, radiusDp) / groupedContainer(ctx, rows, insetDp, radiusDp)
 *       flat #282828 grouped list, radius on the GROUP only, 1px hairlines.
 *   - flatCard(ctx, radiusDp, fill) — flat solid card drawable.
 *   - checkCircle(ctx, priority, filled) — priority ring (P1 FIXED red).
 *   - qaChipBg(ctx, on) — Quick Add chip (rounded RECTANGLE, #3F3F3F).
 *   - accentSquareBg(ctx, radiusDp) — FAB + Ramble + Send shared shape.
 *   - navBarBg(ctx) / navPillBg(ctx) — flat nav bar + selected pill.
 *   - todoistSheetBg(ctx, radiusDp, fill) / dragHandle(ctx) — sheets.
 *   - popupBg(ctx, radiusDp) — translucent popup card (overflow menu).
 *   - crossfade(from, to, ms) / fadeIn(v, ms) — NO slide, alpha only.
 *  Colour logic: Todoist.priorityColor(p), Todoist.dateColor(dueDate). */
object Todoist {
    val BG = 0xFF212121.toInt()          // screens (lists, Display)
    val SHEET = 0xFF1F1F1F.toInt()       // Quick Add sheet / paywall bg
    val CARD = 0xFF282828.toInt()        // cards / grouped containers / popups
    val POPUP = 0xFF2C2C2C.toInt()       // "+" popup (elevation-lit card)
    val NAV = 0xFF262626.toInt()         // bottom nav bar fill
    val NAV_PILL = 0xFF472524.toInt()    // selected nav pill (icon only)
    val RED = 0xFFD04349.toInt()         // rendered primary red (FAB/Done/send)
    val RED_BRAND = 0xFFE44332.toInt()   // nominal brand red (light-theme ref)
    val SALMON = 0xFFE56864.toInt()      // text red: Reschedule, Edit goal, nav label, overdue
    val CHIP = 0xFF3F3F3F.toInt()        // Quick Add chip fill
    val PROD_BG = 0xFF322F2A.toInt()     // Productivity content background
    val EMPHASIS = 0xFF363636.toInt()    // Browse emphasis banner (Try Pro slot)
    val SCRIM_LIST = 0xFF141414.toInt()  // list dimmed behind Quick Add
    val HAIRLINE = 0xFF1F1F1F.toInt()    // divider inside #282828 groups
    val TEXT = 0xFFFFFFFF.toInt()        // primary text on Todoist dark
    val MUTED = 0xFFB0B0B0.toInt()       // secondary text (neutral, no blue tint)
    val FAINT = 0xFF9E9E9E.toInt()       // counts / unselected nav labels
    val PLACEHOLDER = 0xFF8A8A8A.toInt() // Quick Add placeholder
    val HANDLE = 0xFF595959.toInt()      // sheet drag handle
    val PRO_ORANGE = 0xFFEF7E18.toInt()
    val PRO_CTA_ORANGE = 0xFFF48418.toInt()
    val PRO_COL_BG = 0xFF412B06.toInt()
    val KARMA_FILL = 0xFF3A2A2A.toInt()  // karma trend area fill
    val RING_TRACK = 0xFF525252.toInt()  // goal ring track
    val CHART_BAR = 0xFF8A8A8A.toInt()   // productivity bars (grey, not red)

    // Priority colours (sampled ranges in analysis §0.1; single values chosen):
    val P1 = 0xFFE44332.toInt()  // red — FIXED, never the user accent
    val P2 = 0xFFFF9F0A.toInt()  // orange
    val P3 = 0xFF4A90D9.toInt()  // blue
    val P4 = 0xFF9E9E9E.toInt()  // grey

    // Semantic date colours:
    val DATE_TODAY = 0xFF2FB457.toInt()    // green
    val DATE_TOMORROW = 0xFFE79B2E.toInt() // amber
    val DATE_OVERDUE = SALMON              // salmon red
    val DATE_NONE = FAINT                  // grey (future / no date)

    fun priorityColor(priority: Int): Int = when (priority) {
        1 -> P1; 2 -> P2; 3 -> P3; else -> P4
    }

    /** Faint priority tint INSIDE the unchecked checkbox circle
     *  (strongest for P1 — analysis §1: a translucent dark-red fill). */
    fun priorityFill(priority: Int): Int = when (priority) {
        1 -> Color.argb(64, 228, 67, 50)
        2 -> Color.argb(44, 255, 159, 10)
        3 -> Color.argb(44, 74, 144, 217)
        else -> Color.TRANSPARENT
    }

    /** Semantic colour for a due-date segment: green Today, amber Tomorrow,
     *  salmon overdue, grey future/none. dueDate is "yyyy-MM-dd" or null. */
    fun dateColor(dueDate: String?): Int {
        if (dueDate == null) return DATE_NONE
        val today = Dates.today()
        return when {
            dueDate < today -> DATE_OVERDUE
            dueDate == today -> DATE_TODAY
            dueDate == Dates.addDays(today, 1) -> DATE_TOMORROW
            else -> DATE_NONE
        }
    }
}

/** Liquid Glass look on native views: translucent fill + specular top line +
 *  soft rim, layered over the aurora/photo wallpaper behind.
 *
 *  Design tokens (Alpha P2-1/P2-2, v3.6): radius scale follows outer − 6
 *  nesting — page card 24 → inner card 18 → chip 12; standalone: input 14,
 *  row 20, card 24, sheet 30, nav 34. Type scale: Display 40 · Title 24 ·
 *  Headline 17 · Section 16 · Body 15 · Secondary 13 · Caption 11.
 *  (Glyph-only sizes — icons, chevrons, the FAB "+" — sit outside the scale.)
 *
 *  DEFAULT theme is now Todoist (see the Todoist token object above):
 *  panel/sheetPanel/accentGradient/chip/sheetChip/wallpaperView return the
 *  flat Todoist surfaces when Prefs.theme() == "todoist"; the glass code
 *  below them serves the explicit Aurora/Black/White themes unchanged. */
object Glass {
    fun dp(ctx: Context, v: Float): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    fun isWhite(ctx: Context) = Prefs.theme(ctx) == "white"
    fun isBlack(ctx: Context) = Prefs.theme(ctx) == "black"
    fun isTodoist(ctx: Context) = Prefs.theme(ctx) == "todoist"

    /** Theme-aware primary text colour. */
    fun text(ctx: Context): Int = when {
        isWhite(ctx) -> 0xFF131318.toInt()
        isTodoist(ctx) -> Todoist.TEXT
        else -> TEXT
    }

    /** Theme-aware secondary text colour; the Red text option turns it into
     *  the accent colour (Apple Music's red artist/detail lines). */
    fun muted(ctx: Context): Int = when {
        Prefs.redText(ctx) && isWhite(ctx) -> Accents.acc2(ctx)
        Prefs.redText(ctx) && isTodoist(ctx) -> Todoist.SALMON
        Prefs.redText(ctx) -> Accents.base(ctx)
        isWhite(ctx) -> 0xFF62626D.toInt()
        isTodoist(ctx) -> Todoist.MUTED
        else -> MUTED
    }

    /** Theme-aware gold (PRO tags, trophies, UNDO): deep gold on White,
     *  where the bright GOLD constant washes out to ~1.4:1. */
    fun gold(ctx: Context): Int = if (isWhite(ctx)) 0xFF8A6D00.toInt() else GOLD

    /** Theme-aware overdue / destructive red: deeper on White (~4.6:1). */
    fun overdue(ctx: Context): Int = if (isWhite(ctx)) 0xFFD92C1F.toInt() else OVERDUE

    /** Glass panel background: contrast underlay + milky sheen + rim,
     *  themed for Aurora / Black / White appearances. */
    fun panel(ctx: Context, radiusDp: Float = 22f, strong: Boolean = false): Drawable {
        val r = dp(ctx, radiusDp).toFloat()
        if (isTodoist(ctx)) {
            // Flat Todoist card: solid #282828, no glass, no gradient, no rim.
            // (Nav bar wants Todoist.NAV instead — use navBarBg() there.)
            val flat = GradientDrawable()
            flat.cornerRadius = r
            flat.setColor(Todoist.CARD)
            return flat
        }
        if (isWhite(ctx)) {
            val base = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    Color.argb(if (strong) 244 else 230, 255, 255, 255),
                    Color.argb(if (strong) 238 else 218, 251, 251, 253),
                    Color.argb(if (strong) 232 else 208, 245, 245, 249)
                )
            )
            base.cornerRadius = r
            base.setStroke(dp(ctx, 1f), Color.argb(54, 25, 25, 40))
            return base
        }
        val glassAlpha = when {
            isBlack(ctx) -> 16
            Prefs.glass(ctx) == "clear" -> 22
            Prefs.glass(ctx) == "tinted" -> 150
            else -> 42
        }
        val base = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                Color.argb(if (strong) glassAlpha + 90 else glassAlpha + 30, 255, 255, 255),
                Color.argb(if (strong) glassAlpha + 70 else glassAlpha + 8, 255, 255, 255),
                Color.argb(10, 255, 255, 255)
            )
        )
        base.cornerRadius = r
        base.setStroke(dp(ctx, 1f), Color.argb(if (Prefs.glass(ctx) == "clear") 96 else 76, 255, 255, 255))
        val under = GradientDrawable()
        under.cornerRadius = r
        under.setColor(
            if (isBlack(ctx)) Color.argb(if (strong) 244 else 234, 14, 14, 18)
            // On API <31 there is no RenderEffect frost under the panels, so
            // the wallpaper shows through sharp — a stronger underlay keeps
            // Aurora panels readable there. API 31+ renders as before.
            else Color.argb(if (strong) 150 else if (Build.VERSION.SDK_INT < 31) 128 else 88, 10, 11, 18)
        )
        return LayerDrawable(arrayOf(under, base))
    }

    /** Sheet surface: frosted glass — solid enough to read over any
     *  wallpaper, translucent enough for the blurred backdrop to glow through. */
    fun sheetPanel(ctx: Context, radiusDp: Float = 30f): Drawable {
        val r = dp(ctx, radiusDp).toFloat()
        if (isTodoist(ctx)) {
            // Flat Todoist sheet: solid #1F1F1F (Quick Add / paywall shade).
            // The Display sheet wants Todoist.BG instead — todoistSheetBg().
            val flat = GradientDrawable()
            flat.cornerRadius = r
            flat.setColor(Todoist.SHEET)
            return flat
        }
        if (isWhite(ctx)) {
            val base = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFBFFFFFF.toInt(), 0xF5F0F1F5.toInt())
            )
            base.cornerRadius = r
            base.setStroke(dp(ctx, 1f), Color.argb(58, 25, 25, 40))
            val sheen = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(110, 255, 255, 255), Color.argb(0, 255, 255, 255))
            )
            sheen.cornerRadius = r
            return LayerDrawable(arrayOf(base, sheen))
        }
        val base = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            if (isBlack(ctx)) intArrayOf(0xFB1E1E22.toInt(), 0xFB121214.toInt())
            else intArrayOf(0xE8242C3E.toInt(), 0xE8161C2A.toInt())
        )
        base.cornerRadius = r
        base.setStroke(dp(ctx, 1f), Color.argb(96, 255, 255, 255))
        val sheen = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.argb(52, 255, 255, 255), Color.argb(0, 255, 255, 255))
        )
        sheen.cornerRadius = r
        return LayerDrawable(arrayOf(base, sheen))
    }

    /** Soft rounded drop shadow — the depth cue that makes glass feel premium. */
    fun elevate(ctx: Context, v: View, radiusDp: Float, elevDp: Float) {
        v.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                if (view.width > 0 && view.height > 0)
                    outline.setRoundRect(0, 0, view.width, view.height, dp(ctx, radiusDp).toFloat())
            }
        }
        v.elevation = dp(ctx, elevDp).toFloat()
    }

    fun accentGradient(ctx: Context, radiusDp: Float): Drawable {
        if (isTodoist(ctx)) {
            // Flat solid Todoist red — no gradient on Todoist surfaces.
            val flat = GradientDrawable()
            flat.cornerRadius = dp(ctx, radiusDp).toFloat()
            flat.setColor(Todoist.RED)
            return flat
        }
        val g = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Accents.acc1(ctx), Accents.acc2(ctx))
        )
        g.cornerRadius = dp(ctx, radiusDp).toFloat()
        g.setStroke(dp(ctx, 1f), Color.argb(120, 255, 255, 255))
        return g
    }

    fun chip(ctx: Context, on: Boolean): Drawable {
        val g = GradientDrawable()
        g.cornerRadius = dp(ctx, 14f).toFloat()
        if (isTodoist(ctx)) {
            // on = selected nav pill (#472524 oval); off = flat chip grey.
            // Prefer navPillBg()/qaChipBg() in new code — this branch only
            // keeps legacy chip() call sites on-palette in the Todoist theme.
            if (on) {
                g.cornerRadius = dp(ctx, 999f).toFloat()
                g.setColor(Todoist.NAV_PILL)
            } else {
                g.setColor(Todoist.CHIP)
            }
            return g
        }
        if (isWhite(ctx)) {
            if (on) {
                g.setColor(Color.argb(58, 20, 20, 35))
                g.setStroke(dp(ctx, 1f), Color.argb(115, 20, 20, 35))
            } else {
                g.setColor(Color.argb(30, 20, 20, 35))
                g.setStroke(dp(ctx, 1f), Color.argb(58, 20, 20, 35))
            }
            return g
        }
        if (on) {
            g.setColor(Color.argb(88, 255, 255, 255))
            g.setStroke(dp(ctx, 1f), Color.argb(175, 255, 255, 255))
        } else {
            g.setColor(Color.argb(50, 255, 255, 255))
            g.setStroke(dp(ctx, 1f), Color.argb(72, 255, 255, 255))
        }
        return g
    }

    /** High-contrast pill for sheets: solid dark slate (or accent when on),
     *  so sheet controls never wash out over the frosted backdrop. */
    fun sheetChip(ctx: Context, on: Boolean): Drawable {
        val g = GradientDrawable()
        g.cornerRadius = dp(ctx, 999f).toFloat()
        if (isTodoist(ctx)) {
            // Binding correction: QA chips are ROUNDED RECTANGLES (#3F3F3F,
            // ~11dp radius), never full pills. "on" adds a salmon rim only —
            // the state colour lives in the chip's text/icon (green date).
            g.cornerRadius = dp(ctx, 11f).toFloat()
            g.setColor(Todoist.CHIP)
            if (on) g.setStroke(dp(ctx, 1f), Todoist.SALMON)
            return g
        }
        if (isWhite(ctx) && !on) {
            g.setColor(0xFFE8E8ED.toInt())
            g.setStroke(dp(ctx, 1f), Color.argb(50, 20, 20, 35))
            return g
        }
        if (on) {
            val a = Accents.base(ctx)
            g.setColor(Color.argb(115, Color.red(a), Color.green(a), Color.blue(a)))
            g.setStroke(dp(ctx, 1f), Color.argb(210, Color.red(a), Color.green(a), Color.blue(a)))
        } else {
            g.setColor(Color.argb(190, 38, 44, 60))
            g.setStroke(dp(ctx, 1f), Color.argb(100, 255, 255, 255))
        }
        return g
    }

    /** Checkbox: ring colour = PRIORITY colour in EVERY theme — P1 is the
     *  FIXED Todoist red (Todoist.P1), never the user accent (Monitoring
     *  binding correction). Unchecked circles carry a faint tint of their
     *  priority colour inside; ring 3dp for P1–P3, 2dp for P4.
     *  Checked (filled): solid priority colour — OUR documented choice:
     *  the reference recording contains NO completion animation and no
     *  completion ground truth, so we keep only this subtle fill
     *  (previously iOS green #30D158 in every theme). */
    fun checkCircle(ctx: Context, priority: Int, filled: Boolean): Drawable {
        val g = GradientDrawable()
        g.shape = GradientDrawable.OVAL
        val ring = Todoist.priorityColor(priority)
        if (filled) {
            g.setColor(ring)
        } else {
            g.setColor(
                if (priority == 4 && isWhite(ctx)) Color.argb(20, 20, 20, 35)
                else Todoist.priorityFill(priority)
            )
            g.setStroke(dp(ctx, if (priority in 1..3) 3f else 2f), ring)
        }
        return g
    }

    /** Full-screen wallpaper: preset aurora gradients or the user's photo. */
    fun wallpaperView(ctx: Context): View {
        val wp = Prefs.wallpaper(ctx)
        if (wp == "custom") {
            val f = File(ctx.filesDir, "wallpaper.jpg")
            if (f.exists()) {
                val bmp = BitmapFactory.decodeFile(f.absolutePath)
                if (bmp != null) {
                    val iv = ImageView(ctx)
                    iv.setImageBitmap(bmp)
                    iv.scaleType = ImageView.ScaleType.CENTER_CROP
                    return iv
                }
            }
        }
        // Theme backgrounds (a custom photo, handled above, always wins).
        if (Prefs.theme(ctx) == "todoist") {
            // Flat Todoist screen background #212121 — no wallpaper, no blobs.
            val v = View(ctx)
            v.background = ColorDrawable(Todoist.BG)
            return v
        }
        if (Prefs.theme(ctx) == "black") {
            val v = View(ctx)
            v.background = ColorDrawable(0xFF000000.toInt())
            return v
        }
        if (Prefs.theme(ctx) == "white") {
            val v = View(ctx)
            val wpx = ctx.resources.displayMetrics.widthPixels.toFloat()
            val layers = ArrayList<Drawable>()
            layers.add(GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFFF9F9FC.toInt(), 0xFFEEEFF4.toInt(), 0xFFF6F1F6.toInt())))
            val wb = listOf(
                floatArrayOf(0.10f, 0.08f, 0.85f) to 0x66FF9EBB.toInt(),
                floatArrayOf(0.95f, 0.34f, 0.90f) to 0x669EC8FF.toInt(),
                floatArrayOf(0.06f, 0.84f, 0.75f) to 0x66C89EFF.toInt(),
                floatArrayOf(0.85f, 0.92f, 0.70f) to 0x59FFE9A8.toInt())
            for ((geo, col) in wb) {
                val g = GradientDrawable()
                g.shape = GradientDrawable.RECTANGLE
                g.gradientType = GradientDrawable.RADIAL_GRADIENT
                g.setGradientCenter(geo[0], geo[1])
                g.setGradientRadius(wpx * geo[2])
                g.colors = intArrayOf(col, Color.TRANSPARENT)
                layers.add(g)
            }
            v.background = LayerDrawable(layers.toTypedArray())
            return v
        }
        val colors = when (wp) {
            "sunset" -> intArrayOf(0xFF2A1030.toInt(), 0xFF4A1230.toInt(), 0xFF160A18.toInt(), 0xFF06060A.toInt())
            "ocean" -> intArrayOf(0xFF06283D.toInt(), 0xFF07305E.toInt(), 0xFF04101E.toInt(), 0xFF06060A.toInt())
            "forest" -> intArrayOf(0xFF0B2E1A.toInt(), 0xFF0A3D24.toInt(), 0xFF07130C.toInt(), 0xFF06060A.toInt())
            "crimson" -> intArrayOf(0xFF3D0B12.toInt(), 0xFF4A0E18.toInt(), 0xFF170608.toInt(), 0xFF06060A.toInt())
            "mono" -> intArrayOf(0xFF17171F.toInt(), 0xFF101016.toInt(), 0xFF0C0C10.toInt(), 0xFF06060A.toInt())
            else -> intArrayOf(0xFF1B1B3A.toInt(), 0xFF0E2A4A.toInt(), 0xFF2A0E2E.toInt(), 0xFF06060A.toInt())
        }
        // Soft aurora colour blobs over the base gradient — with the
        // wallpaper layer's permanent frost blur, every glass panel above
        // them reads as real frosted glass. (cx, cy, radiusFactor, argb)
        val blobs: List<Pair<FloatArray, Int>> = when (wp) {
            "sunset" -> listOf(
                floatArrayOf(0.12f, 0.10f, 0.78f) to 0x99FF7A3C.toInt(),
                floatArrayOf(0.92f, 0.36f, 0.85f) to 0x99FF2E63.toInt(),
                floatArrayOf(0.08f, 0.86f, 0.70f) to 0x8A8A2BE0.toInt())
            "ocean" -> listOf(
                floatArrayOf(0.10f, 0.12f, 0.80f) to 0x8A19B8D8.toInt(),
                floatArrayOf(0.92f, 0.40f, 0.85f) to 0x8A2F6DFF.toInt(),
                floatArrayOf(0.15f, 0.88f, 0.70f) to 0x7319D8A6.toInt())
            "forest" -> listOf(
                floatArrayOf(0.12f, 0.10f, 0.78f) to 0x8A2ED573.toInt(),
                floatArrayOf(0.90f, 0.38f, 0.85f) to 0x8A0E9E6E.toInt(),
                floatArrayOf(0.10f, 0.86f, 0.70f) to 0x73B8D819.toInt())
            "crimson" -> listOf(
                floatArrayOf(0.12f, 0.10f, 0.78f) to 0x99FF3B4E.toInt(),
                floatArrayOf(0.92f, 0.38f, 0.85f) to 0x8AFF7A2F.toInt(),
                floatArrayOf(0.08f, 0.86f, 0.70f) to 0x8AA61E4D.toInt())
            "mono" -> listOf(
                floatArrayOf(0.12f, 0.10f, 0.78f) to 0x5C6E7FA6.toInt(),
                floatArrayOf(0.92f, 0.40f, 0.85f) to 0x52565B78.toInt(),
                floatArrayOf(0.10f, 0.88f, 0.70f) to 0x47424A63.toInt())
            else -> listOf(
                floatArrayOf(0.12f, 0.10f, 0.80f) to 0x997B2FF7.toInt(),
                floatArrayOf(0.92f, 0.38f, 0.88f) to 0x992F7DFF.toInt(),
                floatArrayOf(0.08f, 0.86f, 0.72f) to 0x8AFF2E88.toInt())
        }
        val v = View(ctx)
        val wpx = ctx.resources.displayMetrics.widthPixels.toFloat()
        val layers = ArrayList<Drawable>()
        layers.add(GradientDrawable(GradientDrawable.Orientation.TL_BR, colors))
        for ((geo, col) in blobs) {
            val g = GradientDrawable()
            g.shape = GradientDrawable.RECTANGLE
            g.gradientType = GradientDrawable.RADIAL_GRADIENT
            g.setGradientCenter(geo[0], geo[1])
            g.setGradientRadius(wpx * geo[2])
            g.colors = intArrayOf(col, Color.TRANSPARENT)
            layers.add(g)
        }
        v.background = LayerDrawable(layers.toTypedArray())
        return v
    }

    /* ---------------- Todoist components (Team One Impl-A) ----------------
     * Additive helpers for the MainActivity implementer; the legacy glass
     * helpers above are unchanged for the Aurora/Black/White themes.
     * All colours come from the Todoist token object. */

    /** Flat solid drawable — the base of every Todoist surface. */
    fun flatDrawable(ctx: Context, fill: Int, radiusDp: Float): Drawable {
        val g = GradientDrawable()
        g.cornerRadius = dp(ctx, radiusDp).toFloat()
        g.setColor(fill)
        return g
    }

    /** Flat card drawable (#282828 by default, radius ~16dp on groups). */
    fun flatCard(ctx: Context, radiusDp: Float = 16f, fill: Int = Todoist.CARD): Drawable =
        flatDrawable(ctx, fill, radiusDp)

    /** Grouped-container background: ONE flat #282828 panel whose OUTER
     *  corners carry the radius; rows inside it stay square (analysis §1). */
    fun groupedPanel(ctx: Context, radiusDp: Float = 16f): Drawable =
        flatDrawable(ctx, Todoist.CARD, radiusDp)

    /** 1px hairline divider view for inside a grouped container. */
    fun groupDivider(ctx: Context, insetDp: Float = 50f): View {
        val v = View(ctx)
        v.background = ColorDrawable(Todoist.HAIRLINE)
        v.layoutParams = LinearLayout.LayoutParams(-1, 1).apply { leftMargin = dp(ctx, insetDp) }
        return v
    }

    /** Builds the Todoist grouped list: a flat #282828 LinearLayout with
     *  rounded OUTER corners (clipped), the given rows stacked inside, and
     *  a 1px hairline between consecutive rows. Rows keep their own padding;
     *  give them NO background/margins of their own. dividerInsetDp aligns
     *  the hairline with row titles (16 padding + 22 checkbox + 12 gap). */
    fun groupedContainer(ctx: Context, rows: List<View>, dividerInsetDp: Float = 50f, radiusDp: Float = 16f): LinearLayout {
        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.VERTICAL
        box.background = groupedPanel(ctx, radiusDp)
        box.clipToOutline = true
        elevate(ctx, box, radiusDp, 0f)
        rows.forEachIndexed { i, row ->
            if (row.layoutParams == null)
                row.layoutParams = LinearLayout.LayoutParams(-1, -2)
            box.addView(row)
            if (i < rows.size - 1) box.addView(groupDivider(ctx, dividerInsetDp))
        }
        return box
    }

    /** Quick Add chip background: rounded RECTANGLE, #3F3F3F, ~11dp radius.
     *  `on` adds a salmon rim only — state colour goes on text/icon. */
    fun qaChipBg(ctx: Context, on: Boolean = false): Drawable {
        val g = GradientDrawable()
        g.cornerRadius = dp(ctx, 11f).toFloat()
        g.setColor(Todoist.CHIP)
        if (on) g.setStroke(dp(ctx, 1f), Todoist.SALMON)
        return g
    }

    /** Rounded-square accent button: the ONE shape shared by the FAB and
     *  the Quick Add Ramble/Send buttons — solid #D04349, ~12dp radius. */
    fun accentSquareBg(ctx: Context, radiusDp: Float = 12f): Drawable =
        flatDrawable(ctx, Todoist.RED, radiusDp)

    /** Bottom nav bar background: flat #262626, attached, no radius. */
    fun navBarBg(ctx: Context): Drawable = ColorDrawable(Todoist.NAV)

    /** Selected nav pill: fully-rounded oval #472524 behind the ICON only;
     *  the label sits below it (salmon when selected). Crossfade it — never
     *  slide it (binding correction). */
    fun navPillBg(ctx: Context): Drawable {
        val g = GradientDrawable()
        g.cornerRadius = dp(ctx, 999f).toFloat()
        g.setColor(Todoist.NAV_PILL)
        return g
    }

    /** Sheet surface: solid fill (SHEET #1F1F1F for Quick Add; pass
     *  Todoist.BG for the full-height Display sheet), radius ~28dp. */
    fun todoistSheetBg(ctx: Context, radiusDp: Float = 28f, fill: Int = Todoist.SHEET): Drawable =
        flatDrawable(ctx, fill, radiusDp)

    /** Drag-handle drawable: grey pill (handle view is ~36x4dp). */
    fun dragHandleBg(ctx: Context): Drawable {
        val g = GradientDrawable()
        g.cornerRadius = dp(ctx, 999f).toFloat()
        g.setColor(Todoist.HANDLE)
        return g
    }

    /** Drag handle view, 36x4dp, for a LinearLayout sheet column. */
    fun dragHandle(ctx: Context): View {
        val v = View(ctx)
        v.background = dragHandleBg(ctx)
        v.layoutParams = LinearLayout.LayoutParams(dp(ctx, 36f), dp(ctx, 4f))
        return v
    }

    /** Popup card (overflow ⋮ menu): TRANSLUCENT dark — the list ghosts
     *  through at ~91% opacity (analysis §3) — radius ~16dp. The priority /
     *  project popups are opaque instead: use flatCard(CARD or POPUP). */
    fun popupBg(ctx: Context, radiusDp: Float = 16f): Drawable {
        val g = GradientDrawable()
        g.cornerRadius = dp(ctx, radiusDp).toFloat()
        g.setColor(Color.argb(232, 28, 28, 28))
        return g
    }

    /** Crossfade helper — the ONLY sanctioned tab/nav transition: alpha
     *  only, NO lateral slide anywhere (binding correction). Fades `to`
     *  in over `from`; `from` ends GONE with its alpha restored for reuse. */
    fun crossfade(from: View?, to: View, durationMs: Long = 200L) {
        to.visibility = View.VISIBLE
        to.alpha = 0f
        to.animate().alpha(1f).setDuration(durationMs).start()
        if (from != null && from !== to) {
            from.animate().alpha(0f).setDuration(durationMs).withEndAction {
                from.visibility = View.GONE
                from.alpha = 1f
            }.start()
        }
    }

    /** Simple alpha fade-in (section pop-in after a tab crossfade). */
    fun fadeIn(v: View, durationMs: Long = 200L) {
        v.alpha = 0f
        v.animate().alpha(1f).setDuration(durationMs).start()
    }

    val TEXT = 0xFFF5F5F7.toInt()
    val MUTED = 0xFFB9B9C9.toInt()
    val GOLD = 0xFFFFD60A.toInt()
    val GREEN = 0xFF30D158.toInt()
    val OVERDUE = 0xFFFF6961.toInt()
    val SURFACE_DARK = ColorDrawable(0xFF06060A.toInt())
}
