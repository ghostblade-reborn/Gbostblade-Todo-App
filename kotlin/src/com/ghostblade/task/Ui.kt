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
import java.io.File

/** Appearance preferences (glass style, accent, wallpaper, sounds). */
object Prefs {
    const val NAME = "gbt_prefs"
    fun prefs(ctx: Context) = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    fun glass(ctx: Context): String = prefs(ctx).getString("glass", "default") ?: "default"
    fun accent(ctx: Context): String = prefs(ctx).getString("accent", "crimson") ?: "crimson"
    fun wallpaper(ctx: Context): String = prefs(ctx).getString("wallpaper", "aurora") ?: "aurora"
    /** aurora (colour wallpapers) | black (Apple Music dark) | white (frosted light). */
    fun theme(ctx: Context): String = prefs(ctx).getString("theme", "aurora") ?: "aurora"
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

/** Liquid Glass look on native views: translucent fill + specular top line +
 *  soft rim, layered over the aurora/photo wallpaper behind.
 *
 *  Design tokens (Alpha P2-1/P2-2, v3.6): radius scale follows outer − 6
 *  nesting — page card 24 → inner card 18 → chip 12; standalone: input 14,
 *  row 20, card 24, sheet 30, nav 34. Type scale: Display 40 · Title 24 ·
 *  Headline 17 · Section 16 · Body 15 · Secondary 13 · Caption 11.
 *  (Glyph-only sizes — icons, chevrons, the FAB "+" — sit outside the scale.) */
object Glass {
    fun dp(ctx: Context, v: Float): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    fun isWhite(ctx: Context) = Prefs.theme(ctx) == "white"
    fun isBlack(ctx: Context) = Prefs.theme(ctx) == "black"

    /** Theme-aware primary text colour. */
    fun text(ctx: Context): Int = if (isWhite(ctx)) 0xFF131318.toInt() else TEXT

    /** Theme-aware secondary text colour; the Red text option turns it into
     *  the accent colour (Apple Music's red artist/detail lines). */
    fun muted(ctx: Context): Int = when {
        Prefs.redText(ctx) && isWhite(ctx) -> Accents.acc2(ctx)
        Prefs.redText(ctx) -> Accents.base(ctx)
        isWhite(ctx) -> 0xFF62626D.toInt()
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

    fun checkCircle(ctx: Context, priority: Int, filled: Boolean): Drawable {
        val g = GradientDrawable()
        g.shape = GradientDrawable.OVAL
        val color = when (priority) {
            1 -> Accents.base(ctx)
            2 -> 0xFFFF9F0A.toInt()
            3 -> 0xFF0A84FF.toInt()
            else -> if (isWhite(ctx)) Color.argb(185, 40, 40, 55) else Color.argb(160, 255, 255, 255)
        }
        if (filled) g.setColor(0xFF30D158.toInt()) else {
            g.setColor(
                if (priority == 1) Accents.soft(ctx)
                else if (isWhite(ctx)) Color.argb(20, 20, 20, 35)
                else Color.argb(16, 255, 255, 255)
            )
            g.setStroke(dp(ctx, 2f), color)
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

    val TEXT = 0xFFF5F5F7.toInt()
    val MUTED = 0xFFB9B9C9.toInt()
    val GOLD = 0xFFFFD60A.toInt()
    val GREEN = 0xFF30D158.toInt()
    val OVERDUE = 0xFFFF6961.toInt()
    val SURFACE_DARK = ColorDrawable(0xFF06060A.toInt())
}
