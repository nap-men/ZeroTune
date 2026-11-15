package app.zerotune

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.ColorUtils

/** Все цвета интерфейса, посчитанные из четырёх выбранных пользователем. */
class Palette(val bg: Int, val accent: Int, val power: Int, val select: Int) {
    val light = ColorUtils.calculateLuminance(bg) > 0.5
    val card = if (light) Color.WHITE else ColorUtils.blendARGB(bg, Color.WHITE, 0.06f)
    val stroke = if (light) ColorUtils.blendARGB(bg, Color.BLACK, 0.10f) else ColorUtils.blendARGB(bg, Color.WHITE, 0.12f)
    val input = if (light) ColorUtils.blendARGB(bg, Color.WHITE, 0.5f) else bg
    val button = if (light) ColorUtils.blendARGB(bg, Color.BLACK, 0.07f) else ColorUtils.blendARGB(bg, Color.WHITE, 0.14f)
    val offRing = if (light) ColorUtils.blendARGB(bg, Color.BLACK, 0.18f) else ColorUtils.blendARGB(bg, Color.WHITE, 0.20f)
    val textPrimary = if (light) 0xFF111827.toInt() else 0xFFF2F5F8.toInt()
    val textSecondary = if (light) 0xFF6B7280.toInt() else 0xFF8A97A6.toInt()
    val onAccent = if (ColorUtils.calculateLuminance(accent) > 0.45) 0xFF04140F.toInt() else Color.WHITE
    val warn = 0xFFF5A524.toInt()

    /** Тёмная (или светлая) заливка под цвет: фон, чуть подкрашенный им. */
    fun dim(c: Int): Int = ColorUtils.blendARGB(bg, c, if (light) 0.18f else 0.22f)
}

object Theme {

    fun palette(c: Context) =
        Palette(Store.themeBg(c), Store.themeAccent(c), Store.themePower(c), Store.themeSelect(c))

    /** Меняется при любой смене цветов: по нему экран понимает, что пора перерисоваться. */
    fun stamp(c: Context): Int =
        listOf(Store.themeBg(c), Store.themeAccent(c), Store.themePower(c), Store.themeSelect(c)).hashCode()

    private fun dpf(c: Context, v: Float) = v * c.resources.displayMetrics.density

    fun rect(c: Context, color: Int, radiusDp: Float, stroke: Int = 0, strokeDp: Float = 0f): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dpf(c, radiusDp)
            if (strokeDp > 0f) setStroke(dpf(c, strokeDp).toInt().coerceAtLeast(1), stroke)
        }

    fun oval(c: Context, fill: Int, stroke: Int = 0, strokeDp: Float = 0f): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
            if (strokeDp > 0f) setStroke(dpf(c, strokeDp).toInt().coerceAtLeast(1), stroke)
        }

    fun card(c: Context, p: Palette): Drawable = rect(c, p.card, 20f, p.stroke, 1f)
    fun input(c: Context, p: Palette): Drawable = rect(c, p.input, 12f, p.stroke, 1f)
    fun button(c: Context, p: Palette): Drawable = ripple(c, p, p.button)
    fun accentButton(c: Context, p: Palette): Drawable = ripple(c, p, p.accent)

    private fun ripple(c: Context, p: Palette, color: Int): Drawable =
        RippleDrawable(ColorStateList.valueOf(if (p.light) 0x22000000 else 0x33FFFFFF), rect(c, color, 14f), null)

    fun server(c: Context, p: Palette, selected: Boolean): Drawable =
        if (selected) rect(c, p.dim(p.select), 14f, p.select, 2f) else rect(c, p.input, 14f, p.stroke, 1f)

    fun dot(c: Context, p: Palette, on: Boolean): Drawable =
        if (on) oval(c, p.select) else oval(c, Color.TRANSPARENT, p.offRing, 2f)

    /** Цвета окна и панелей системы. */
    @Suppress("DEPRECATION")
    fun applyWindow(a: Activity) {
        val p = palette(a)
        a.window.decorView.setBackgroundColor(p.bg)
        a.window.statusBarColor = p.bg
        a.window.navigationBarColor = p.bg
        var f = a.window.decorView.systemUiVisibility
        val lightNav = if (Build.VERSION.SDK_INT >= 26) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
        f = if (p.light) f or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or lightNav
            else f and (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or lightNav).inv()
        a.window.decorView.systemUiVisibility = f
    }

    /** Вызывать после setContentView: красит окно и все вьюхи экрана. */
    fun apply(a: Activity) {
        applyWindow(a)
        val root = a.findViewById<View>(android.R.id.content) ?: return
        recolor(a, root, palette(a))
    }

    /**
     * Перекрашивает дерево вьюх. Фоны берутся из тегов "bg:..." в разметке, а цвета текста
     * подменяются по совпадению со стандартными цветами из colors.xml (они служат «образцом»).
     */
    fun recolor(c: Context, root: View, p: Palette = palette(c)) {
        walk(c, root, p, c.getColor(R.color.text_primary), c.getColor(R.color.text_secondary), c.getColor(R.color.accent))
    }

    private fun walk(c: Context, v: View, p: Palette, oPrim: Int, oSec: Int, oAcc: Int) {
        when (v.tag as? String) {
            "bg:bg_card" -> v.background = card(c, p)
            "bg:bg_input" -> v.background = input(c, p)
            "bg:bg_button" -> v.background = button(c, p)
            "bg:bg_button_accent" -> {
                v.background = accentButton(c, p)
                (v as? TextView)?.setTextColor(p.onAccent)
            }
        }
        if (v is TextView) {
            val cur = v.currentTextColor
            when (cur) {
                oPrim -> v.setTextColor(p.textPrimary)
                oSec -> v.setTextColor(p.textSecondary)
                oAcc -> v.setTextColor(p.accent)
            }
            if (v is EditText) {
                val h = v.currentHintTextColor
                when (h) {
                    oPrim -> v.setHintTextColor(p.textPrimary)
                    oSec -> v.setHintTextColor(p.textSecondary)
                }
            }
            if (v is CompoundButton) v.buttonTintList = ColorStateList.valueOf(p.accent)
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) walk(c, v.getChildAt(i), p, oPrim, oSec, oAcc)
        }
    }
}

/**
 * Анимации кнопки включения.
 *  - выключено: спокойная кнопка;
 *  - подключение: два расходящихся кольца и «дыхание» кнопки;
 *  - включено: «хлопок» при включении и мягкое свечение.
 * Цвета плавно перетекают из состояния в состояние.
 */
class PowerAnim(private val btn: ImageView, private val ringA: View, private val ringB: View) {

    companion object {
        const val OFF = 0
        const val CONNECTING = 1
        const val ON = 2
    }

    private val ctx = btn.context
    private val bg = GradientDrawable().apply { shape = GradientDrawable.OVAL }
    private var state = -1
    private var stamp = 0
    private val running = mutableListOf<Animator>()
    private var colorAnim: ValueAnimator? = null
    private var fill = 0
    private var stroke = 0
    private var icon = 0

    init {
        btn.background = bg
    }

    private fun px(v: Float) = (v * ctx.resources.displayMetrics.density).toInt()

    private fun setColors(f: Int, s: Int, i: Int) {
        fill = f; stroke = s; icon = i
        bg.setColor(f)
        bg.setStroke(px(4f), s)
        btn.setColorFilter(i, PorterDuff.Mode.SRC_IN)
    }

    private fun stopAnims() {
        running.forEach { it.cancel() }
        running.clear()
        btn.animate().cancel()
        for (r in listOf(ringA, ringB)) { r.alpha = 0f; r.scaleX = 1f; r.scaleY = 1f }
        btn.scaleX = 1f; btn.scaleY = 1f
    }

    /** Остановить всё (экран ушёл в фон). Следующий apply отрисует состояние заново. */
    fun stop() {
        colorAnim?.cancel()
        stopAnims()
        state = -1
    }

    private fun pulse(ring: View, to: Float, alphaFrom: Float, dur: Long, delay: Long) {
        ring.alpha = 0f
        val a = ObjectAnimator.ofPropertyValuesHolder(
            ring,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, to),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, to),
            PropertyValuesHolder.ofFloat(View.ALPHA, alphaFrom, 0f)
        )
        a.duration = dur
        a.startDelay = delay
        a.repeatCount = ValueAnimator.INFINITE
        a.interpolator = DecelerateInterpolator()
        running += a
        a.start()
    }

    fun apply(newState: Int, p: Palette, stampValue: Int, animated: Boolean) {
        if (newState == state && stampValue == stamp) return
        val old = state
        state = newState
        stamp = stampValue

        val tFill: Int
        val tStroke: Int
        val tIcon: Int
        when (newState) {
            ON -> { tFill = p.dim(p.power); tStroke = p.power; tIcon = p.power }
            CONNECTING -> { tFill = p.card; tStroke = p.power; tIcon = p.power }
            else -> { tFill = p.card; tStroke = p.offRing; tIcon = p.textSecondary }
        }

        val fancy = Store.animations(ctx)

        colorAnim?.cancel()
        if (animated && fancy && old != -1) {
            val f0 = fill; val s0 = stroke; val i0 = icon
            colorAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 350
                addUpdateListener {
                    val t = it.animatedFraction
                    setColors(
                        ColorUtils.blendARGB(f0, tFill, t),
                        ColorUtils.blendARGB(s0, tStroke, t),
                        ColorUtils.blendARGB(i0, tIcon, t)
                    )
                }
                start()
            }
        } else {
            setColors(tFill, tStroke, tIcon)
        }

        stopAnims()
        val ringBg = { GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
            setStroke(px(3f), p.power)
        } }
        ringA.background = ringBg()
        ringB.background = ringBg()
        if (!fancy) return

        when (newState) {
            CONNECTING -> {
                pulse(ringA, 1.4f, 0.7f, 1100, 0)
                pulse(ringB, 1.4f, 0.7f, 1100, 550)
                val breathe = ObjectAnimator.ofPropertyValuesHolder(
                    btn,
                    PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 0.94f),
                    PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 0.94f)
                )
                breathe.duration = 650
                breathe.repeatMode = ValueAnimator.REVERSE
                breathe.repeatCount = ValueAnimator.INFINITE
                breathe.interpolator = AccelerateDecelerateInterpolator()
                running += breathe
                breathe.start()
            }
            ON -> {
                if (animated && (old == OFF || old == CONNECTING)) {
                    btn.scaleX = 0.85f; btn.scaleY = 0.85f
                    btn.animate().scaleX(1f).scaleY(1f).setDuration(450)
                        .setInterpolator(OvershootInterpolator(2.2f)).start()
                }
                pulse(ringA, 1.25f, 0.35f, 2600, 0)
            }
            else -> {
                if (animated && old != -1) {
                    btn.animate().scaleX(0.9f).scaleY(0.9f).setDuration(110).withEndAction {
                        btn.animate().scaleX(1f).scaleY(1f).setDuration(260)
                            .setInterpolator(OvershootInterpolator(2f)).start()
                    }.start()
                }
            }
        }
    }
}
