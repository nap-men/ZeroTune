package app.zerotune

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Оформление: готовые темы, свои цвета (фон, акцент, кнопка, выделенный сервер) и анимации. */
class ThemeActivity : Activity() {

    private class Preset(val nameRes: Int, val name: String?, val bg: Int, val accent: Int)

    private val presets = listOf(
        Preset(R.string.preset_emerald, null, 0xFF0E1318.toInt(), 0xFF14C58B.toInt()),
        Preset(R.string.preset_ocean, null, 0xFF0B1220.toInt(), 0xFF3B82F6.toInt()),
        Preset(R.string.preset_violet, null, 0xFF130F1F.toInt(), 0xFF8B5CF6.toInt()),
        Preset(R.string.preset_sunset, null, 0xFF1A1210.toInt(), 0xFFFF8A3D.toInt()),
        Preset(R.string.preset_rose, null, 0xFF1A0F16.toInt(), 0xFFF43F5E.toInt()),
        Preset(0, "AMOLED", 0xFF000000.toInt(), 0xFF22D3EE.toInt()),
        Preset(R.string.preset_light, null, 0xFFF3F5F8.toInt(), 0xFF0EA371.toInt())
    )

    private val bgSwatches = intArrayOf(
        0xFF000000.toInt(), 0xFF0E1318.toInt(), 0xFF0B1220.toInt(), 0xFF130F1F.toInt(),
        0xFF1A1210.toInt(), 0xFF1A0F16.toInt(), 0xFF0F1A14.toInt(), 0xFF14161A.toInt(),
        0xFF1F2937.toInt(), 0xFF111827.toInt(), 0xFF1E1B2E.toInt(), 0xFF0D1F22.toInt(),
        0xFFF3F5F8.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFF7ED.toInt(), 0xFFEFF6FF.toInt()
    )

    private val accentSwatches = intArrayOf(
        0xFF14C58B.toInt(), 0xFF22D3EE.toInt(), 0xFF3B82F6.toInt(), 0xFF6366F1.toInt(),
        0xFF8B5CF6.toInt(), 0xFFD946EF.toInt(), 0xFFF43F5E.toInt(), 0xFFEF4444.toInt(),
        0xFFFF8A3D.toInt(), 0xFFF5C518.toInt(), 0xFFA3E635.toInt(), 0xFF2DD4BF.toInt(),
        0xFF0EA371.toInt(), 0xFF38BDF8.toInt(), 0xFFFB7185.toInt(), 0xFFFFFFFF.toInt()
    )

    private lateinit var pal: Palette

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Lang.wrap(newBase))
    }

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = sp
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun lp(w: Int, h: Int, top: Int = 0) =
        LinearLayout.LayoutParams(w, h).apply { topMargin = dp(top) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pal = Theme.palette(this)
        Theme.applyWindow(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(28))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundColor(pal.bg)
            addView(root)
        }
        setContentView(scroll)

        root.addView(text(getString(R.string.back), 15f, pal.accent).apply {
            setPadding(0, dp(4), 0, dp(8))
            setOnClickListener { finish() }
        })
        root.addView(text(getString(R.string.theme_title), 26f, pal.textPrimary, true))

        // ---- готовые темы ----
        root.addView(text(getString(R.string.presets_title), 16f, pal.textPrimary, true), lp(-2, -2, 20))
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val curBg = Store.themeBg(this)
        val curAcc = Store.themeAccent(this)
        for (pr in presets) {
            val selected = pr.bg == curBg && pr.accent == curAcc
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(6), dp(6), dp(6), dp(6))
                setOnClickListener {
                    Store.setThemeAll(this@ThemeActivity, pr.bg, pr.accent, pr.accent, pr.accent)
                    recreate()
                }
            }
            chip.addView(View(this).apply {
                background = Theme.oval(this@ThemeActivity, pr.bg, pr.accent, if (selected) 5f else 3f)
            }, LinearLayout.LayoutParams(dp(52), dp(52)))
            chip.addView(text(pr.name ?: getString(pr.nameRes), 11f, if (selected) pal.accent else pal.textSecondary, selected),
                lp(-2, -2, 4))
            chips.addView(chip, LinearLayout.LayoutParams(dp(76), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
        }, lp(-1, -2, 6))

        // ---- свои цвета ----
        val colors = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.card(this@ThemeActivity, pal)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        colors.addView(text(getString(R.string.custom_colors), 16f, pal.textPrimary, true))
        colorRow(colors, getString(R.string.c_bg), pal.bg) { pickColor(getString(R.string.d_bg), pal.bg, bgSwatches) { Store.setThemeBg(this, it) } }
        colorRow(colors, getString(R.string.c_accent), pal.accent) {
            pickColor(getString(R.string.d_accent), pal.accent, accentSwatches) { Store.setThemeAccent(this, it) }
        }
        colorRow(colors, getString(R.string.c_power), pal.power) {
            pickColor(getString(R.string.d_power), pal.power, accentSwatches) { Store.setThemePower(this, it) }
        }
        colorRow(colors, getString(R.string.c_select), pal.select) {
            pickColor(getString(R.string.d_select), pal.select, accentSwatches) { Store.setThemeSelect(this, it) }
        }
        root.addView(colors, lp(-1, -2, 18))

        // ---- анимации ----
        val anim = CheckBox(this).apply {
            text = getString(R.string.anim_cb)
            setTextColor(pal.textPrimary)
            textSize = 14f
            buttonTintList = android.content.res.ColorStateList.valueOf(pal.accent)
            isChecked = Store.animations(this@ThemeActivity)
            setOnCheckedChangeListener { _, v -> Store.setAnimations(this@ThemeActivity, v) }
        }
        val animCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.card(this@ThemeActivity, pal)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            addView(anim)
        }
        root.addView(animCard, lp(-1, -2, 16))

        // ---- сброс ----
        root.addView(text(getString(R.string.reset_theme), 14f, pal.textPrimary).apply {
            gravity = Gravity.CENTER
            background = Theme.button(this@ThemeActivity, pal)
            setOnClickListener {
                Store.resetTheme(this@ThemeActivity)
                recreate()
            }
        }, lp(-1, dp(46), 16))
    }

    private fun colorRow(parent: LinearLayout, label: String, color: Int, onClick: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { onClick() }
        }
        row.addView(text(label, 14f, pal.textPrimary), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(View(this).apply {
            background = Theme.oval(this@ThemeActivity, color, pal.offRing, 2f)
        }, LinearLayout.LayoutParams(dp(32), dp(32)))
        parent.addView(row, lp(-1, dp(52)))
    }

    /** Диалог выбора цвета в стиле приложения (круг, палитра, «Мои цвета»). */
    private fun pickColor(title: String, current: Int, swatches: IntArray, onPick: (Int) -> Unit) {
        ColorPickerDialog(this, title, current, swatches) { c ->
            onPick(c)
            recreate()
        }.show()
    }
}
