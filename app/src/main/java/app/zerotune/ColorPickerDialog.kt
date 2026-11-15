package app.zerotune

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/** Цветовой круг: угол — оттенок, расстояние от центра — насыщенность (яркость задаёт ValueBarView). */
class ColorWheelView(ctx: Context) : View(ctx) {
    var hue = 0f
    var sat = 0f
    var value = 1f
    var onChange: (() -> Unit)? = null
    var onRelease: (() -> Unit)? = null

    private val huePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val satPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0x66000000; strokeWidth = dp(5f)
    }
    private val thumbRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = dp(3f)
    }
    private var cx = 0f
    private var cy = 0f
    private var r = 0f

    private fun dp(v: Float) = v * resources.displayMetrics.density

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cx = w / 2f
        cy = h / 2f
        r = min(w, h) / 2f - dp(10f)
        huePaint.shader = SweepGradient(
            cx, cy,
            intArrayOf(
                0xFFFF0000.toInt(), 0xFFFFFF00.toInt(), 0xFF00FF00.toInt(), 0xFF00FFFF.toInt(),
                0xFF0000FF.toInt(), 0xFFFF00FF.toInt(), 0xFFFF0000.toInt()
            ),
            null
        )
        satPaint.shader = RadialGradient(cx, cy, r, Color.WHITE, 0x00FFFFFF, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawCircle(cx, cy, r, huePaint)
        canvas.drawCircle(cx, cy, r, satPaint)
        dimPaint.color = Color.argb(((1f - value) * 255).toInt(), 0, 0, 0)
        canvas.drawCircle(cx, cy, r, dimPaint)

        val a = Math.toRadians(hue.toDouble())
        val tx = cx + (cos(a) * sat * r).toFloat()
        val ty = cy + (sin(a) * sat * r).toFloat()
        thumbFill.color = Color.HSVToColor(floatArrayOf(hue, sat, value))
        canvas.drawCircle(tx, ty, dp(11f), thumbFill)
        canvas.drawCircle(tx, ty, dp(11f), thumbShadow)
        canvas.drawCircle(tx, ty, dp(11f), thumbRing)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val dx = e.x - cx
                val dy = e.y - cy
                sat = min(1f, hypot(dx, dy) / r)
                var deg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                if (deg < 0f) deg += 360f
                hue = deg
                invalidate()
                onChange?.invoke()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onRelease?.invoke()
        }
        return true
    }
}

/** Ползунок яркости: от чёрного до чистого цвета выбранного оттенка. */
class ValueBarView(ctx: Context) : View(ctx) {
    var value = 1f
    var onChange: (() -> Unit)? = null
    var onRelease: (() -> Unit)? = null
    private var hue = 0f
    private var sat = 0f

    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0x66000000; strokeWidth = dp(5f)
    }
    private val thumbRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = dp(3f)
    }
    private val rect = RectF()

    private fun dp(v: Float) = v * resources.displayMetrics.density

    fun setBase(h: Float, s: Float) {
        hue = h; sat = s
        invalidate()
    }

    private fun left() = dp(16f)
    private fun right() = width - dp(16f)

    override fun onDraw(canvas: Canvas) {
        val l = left()
        val rr = right()
        val cy = height / 2f
        track.shader = LinearGradient(l, 0f, rr, 0f, Color.BLACK, Color.HSVToColor(floatArrayOf(hue, sat, 1f)), Shader.TileMode.CLAMP)
        rect.set(l, cy - dp(9f), rr, cy + dp(9f))
        canvas.drawRoundRect(rect, dp(9f), dp(9f), track)

        val x = l + value * (rr - l)
        thumbFill.color = Color.HSVToColor(floatArrayOf(hue, sat, value))
        canvas.drawCircle(x, cy, dp(12f), thumbFill)
        canvas.drawCircle(x, cy, dp(12f), thumbShadow)
        canvas.drawCircle(x, cy, dp(12f), thumbRing)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                value = ((e.x - left()) / (right() - left())).coerceIn(0f, 1f)
                invalidate()
                onChange?.invoke()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onRelease?.invoke()
        }
        return true
    }
}

/**
 * Выбор цвета в стиле приложения: круг, яркость, ввод кода, готовая палитра (листается)
 * и «Мои цвета» — свои цвета, которые можно добавлять плюсом и удалять долгим нажатием.
 */
class ColorPickerDialog(
    private val a: Activity,
    private val title: String,
    initial: Int,
    private val swatches: IntArray,
    private val onPick: (Int) -> Unit
) {
    private val pal = Theme.palette(a)
    private val hsv = FloatArray(3).also { Color.colorToHSV(initial, it) }
    private var updatingHex = false

    private lateinit var wheel: ColorWheelView
    private lateinit var bar: ValueBarView
    private lateinit var preview: View
    private lateinit var hex: EditText
    private lateinit var paletteRow: LinearLayout
    private lateinit var savedRow: LinearLayout

    private fun dp(v: Int) = (v * a.resources.displayMetrics.density).toInt()
    private fun current(): Int = Color.HSVToColor(hsv)
    private fun same(x: Int, y: Int) = (x and 0xFFFFFF) == (y and 0xFFFFFF)

    private fun label(s: String) = TextView(a).apply {
        text = s
        textSize = 13f
        setTextColor(pal.textSecondary)
        setTypeface(typeface, Typeface.BOLD)
    }

    private fun lp(w: Int, h: Int, top: Int = 0) =
        LinearLayout.LayoutParams(w, h).apply { topMargin = dp(top) }

    /** Всё, что зависит от выбранного цвета, кроме рядов плиток. */
    private fun syncLight() {
        wheel.hue = hsv[0]; wheel.sat = hsv[1]; wheel.value = hsv[2]
        wheel.invalidate()
        bar.setBase(hsv[0], hsv[1])
        bar.value = hsv[2]
        preview.background = Theme.oval(a, current(), pal.offRing, 2f)
        updatingHex = true
        hex.setText(String.format("#%06X", current() and 0xFFFFFF))
        updatingHex = false
    }

    private fun setColor(c: Int) {
        Color.colorToHSV(c, hsv)
        syncLight()
        renderChips()
    }

    private fun chip(color: Int, onClick: () -> Unit, onLong: (() -> Unit)? = null): View {
        val sel = same(color, current())
        return View(a).apply {
            background = Theme.oval(a, color, if (sel) pal.textPrimary else pal.stroke, if (sel) 3f else 1f)
            setOnClickListener { onClick() }
            // без вибрации: ни системной при долгом нажатии, ни своей
            isHapticFeedbackEnabled = false
            if (onLong != null) setOnLongClickListener {
                onLong()
                true
            }
        }
    }

    private fun chipParams() = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
        setMargins(dp(4), dp(4), dp(4), dp(4))
    }

    private fun renderChips() {
        paletteRow.removeAllViews()
        for (c in swatches) paletteRow.addView(chip(c, { setColor(c) }), chipParams())

        savedRow.removeAllViews()
        val plus = TextView(a).apply {
            text = "+"
            gravity = Gravity.CENTER
            textSize = 22f
            setTextColor(pal.accent)
            background = Theme.oval(a, Color.TRANSPARENT, pal.accent, 2f)
            setOnClickListener {
                if (Store.addCustomColor(a, current())) {
                    toast(a.getString(R.string.color_saved))
                    renderChips()
                } else toast(a.getString(R.string.color_exists))
            }
        }
        savedRow.addView(plus, chipParams())
        for (c in Store.customColors(a)) {
            savedRow.addView(chip(c, { setColor(c) }, {
                Store.removeCustomColor(a, c)
                toast(a.getString(R.string.color_removed))
                renderChips()
            }), chipParams())
        }
    }

    private fun toast(s: String) = Toast.makeText(a, s, Toast.LENGTH_SHORT).show()

    private fun scrollRow(row: LinearLayout) = HorizontalScrollView(a).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(row)
    }

    fun show() {
        val dlg = Dialog(a)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val card = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.card(a, pal)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        val titleRow = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(a).apply {
            text = title
            textSize = 18f
            setTextColor(pal.textPrimary)
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        // «!» в кружке: открывает подсказку, как пользоваться окном
        titleRow.addView(TextView(a).apply {
            text = "!"
            gravity = Gravity.CENTER
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(pal.accent)
            background = Theme.oval(a, Color.TRANSPARENT, pal.accent, 2f)
            setOnClickListener { showHelp() }
        }, LinearLayout.LayoutParams(dp(32), dp(32)))
        card.addView(titleRow)

        wheel = ColorWheelView(a)
        card.addView(wheel, lp(dp(250), dp(250), 10).apply { gravity = Gravity.CENTER_HORIZONTAL })

        bar = ValueBarView(a)
        card.addView(bar, lp(LinearLayout.LayoutParams.MATCH_PARENT, dp(40), 2))

        // превью + код цвета
        val codeRow = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        preview = View(a)
        codeRow.addView(preview, LinearLayout.LayoutParams(dp(40), dp(40)))
        hex = EditText(a).apply {
            hint = "#RRGGBB"
            setHintTextColor(pal.textSecondary)
            setTextColor(pal.textPrimary)
            textSize = 15f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            filters = arrayOf(InputFilter.LengthFilter(7))
            background = Theme.input(a, pal)
            setPadding(dp(14), 0, dp(14), 0)
        }
        codeRow.addView(hex, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(12) })
        card.addView(codeRow, lp(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 6))

        // ряды плиток
        card.addView(label(a.getString(R.string.pick_palette)), lp(-2, -2, 14))
        paletteRow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        card.addView(scrollRow(paletteRow), lp(-1, -2, 4))

        card.addView(label(a.getString(R.string.pick_my_colors)), lp(-2, -2, 10))
        savedRow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        card.addView(scrollRow(savedRow), lp(-1, -2, 4))
        card.addView(TextView(a).apply {
            text = a.getString(R.string.pick_hold_delete)
            textSize = 11f
            setTextColor(pal.textSecondary)
        }, lp(-2, -2, 2))

        // кнопки
        val buttons = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(TextView(a).apply {
            text = a.getString(R.string.dlg_cancel)
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(pal.textPrimary)
            background = Theme.button(a, pal)
            setOnClickListener { dlg.dismiss() }
        }, LinearLayout.LayoutParams(0, dp(46), 1f))
        buttons.addView(TextView(a).apply {
            text = a.getString(R.string.apply)
            gravity = Gravity.CENTER
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(pal.onAccent)
            background = Theme.accentButton(a, pal)
            setOnClickListener {
                dlg.dismiss()
                onPick(current())
            }
        }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(10) })
        card.addView(buttons, lp(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 16))

        // связи
        wheel.onChange = {
            hsv[0] = wheel.hue; hsv[1] = wheel.sat
            syncLightFromWheel()
        }
        wheel.onRelease = { renderChips() }
        bar.onChange = {
            hsv[2] = bar.value
            wheel.value = bar.value
            wheel.invalidate()
            preview.background = Theme.oval(a, current(), pal.offRing, 2f)
            writeHex()
        }
        bar.onRelease = { renderChips() }
        hex.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (updatingHex) return
                val t = s.toString().trim().removePrefix("#")
                if (t.length != 6) return
                try {
                    val c = Color.parseColor("#$t")
                    Color.colorToHSV(c, hsv)
                    wheel.hue = hsv[0]; wheel.sat = hsv[1]; wheel.value = hsv[2]
                    wheel.invalidate()
                    bar.setBase(hsv[0], hsv[1]); bar.value = hsv[2]; bar.invalidate()
                    preview.background = Theme.oval(a, current(), pal.offRing, 2f)
                    renderChips()
                } catch (_: Exception) { /* неполный или неверный код */ }
            }
        })

        syncLight()
        renderChips()

        dlg.setContentView(ScrollView(a).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(card)
        })
        dlg.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout((a.resources.displayMetrics.widthPixels * 0.92f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dlg.show()

        if (Store.animations(a)) {
            card.alpha = 0f
            card.scaleX = 0.94f; card.scaleY = 0.94f
            card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start()
        }
    }

    /** Всплывающая подсказка: что делает каждый элемент окна. */
    private fun showHelp() {
        val dlg = Dialog(a)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.card(a, pal)
            setPadding(dp(18), dp(18), dp(18), dp(16))
        }
        box.addView(TextView(a).apply {
            text = a.getString(R.string.help_title)
            textSize = 18f
            setTextColor(pal.textPrimary)
            setTypeface(typeface, Typeface.BOLD)
        })
        val items = listOf(
            R.string.help_wheel_t to R.string.help_wheel,
            R.string.help_bright_t to R.string.help_bright,
            R.string.help_hex_t to R.string.help_hex,
            R.string.help_palette_t to R.string.help_palette,
            R.string.help_save_t to R.string.help_save,
            R.string.help_delete_t to R.string.help_delete,
            R.string.help_apply_t to R.string.help_apply
        )
        for ((head, body) in items) {
            box.addView(TextView(a).apply {
                text = a.getString(head)
                textSize = 14f
                setTextColor(pal.accent)
                setTypeface(typeface, Typeface.BOLD)
            }, lp(-2, -2, 12))
            box.addView(TextView(a).apply {
                text = a.getString(body)
                textSize = 13f
                setTextColor(pal.textSecondary)
                setLineSpacing(dp(2).toFloat(), 1f)
            }, lp(-2, -2, 2))
        }
        box.addView(TextView(a).apply {
            text = a.getString(R.string.help_ok)
            gravity = Gravity.CENTER
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(pal.onAccent)
            background = Theme.accentButton(a, pal)
            setOnClickListener { dlg.dismiss() }
        }, lp(LinearLayout.LayoutParams.MATCH_PARENT, dp(46), 18))

        dlg.setContentView(ScrollView(a).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(box)
        })
        dlg.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout((a.resources.displayMetrics.widthPixels * 0.9f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
        }
        dlg.show()
        if (Store.animations(a)) {
            box.alpha = 0f
            box.scaleX = 0.94f; box.scaleY = 0.94f
            box.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).start()
        }
    }

    private fun syncLightFromWheel() {
        bar.setBase(hsv[0], hsv[1])
        preview.background = Theme.oval(a, current(), pal.offRing, 2f)
        writeHex()
    }

    private fun writeHex() {
        updatingHex = true
        hex.setText(String.format("#%06X", current() and 0xFFFFFF))
        updatingHex = false
    }
}
