package app.zerotune

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Диалог в стиле приложения (вместо системного AlertDialog): карточка цвета темы, кнопки как в остальном интерфейсе. */
object AppDialog {

    fun show(
        a: Activity,
        title: String,
        message: String? = null,
        content: View? = null,
        positive: String,
        negative: String? = null,
        danger: Boolean = false,
        monospace: Boolean = false,
        keyboard: Boolean = false,
        onPositive: (() -> Unit)? = null
    ): Dialog {
        val pal = Theme.palette(a)
        fun dp(v: Int) = (v * a.resources.displayMetrics.density).toInt()

        val dlg = Dialog(a)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.card(a, pal)
            setPadding(dp(18), dp(18), dp(18), dp(16))
        }
        box.addView(TextView(a).apply {
            text = title
            textSize = 18f
            setTextColor(pal.textPrimary)
            setTypeface(typeface, Typeface.BOLD)
        })
        if (!message.isNullOrEmpty()) {
            box.addView(TextView(a).apply {
                text = message
                textSize = if (monospace) 12.5f else 14f
                setTextColor(pal.textSecondary)
                setLineSpacing(dp(2).toFloat(), 1f)
                if (monospace) typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }
        if (content != null) {
            box.addView(content, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }

        val buttons = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        fun button(text: String, main: Boolean, click: () -> Unit) = TextView(a).apply {
            this.text = text
            gravity = Gravity.CENTER
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            if (main) {
                if (danger) {
                    setTextColor(0xFF1A1000.toInt())
                    background = Theme.rect(a, pal.warn, 14f)
                } else {
                    setTextColor(pal.onAccent)
                    background = Theme.accentButton(a, pal)
                }
            } else {
                setTextColor(pal.textPrimary)
                background = Theme.button(a, pal)
            }
            setOnClickListener { click() }
        }
        if (negative != null) {
            buttons.addView(button(negative, false) { dlg.dismiss() },
                LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(10) })
        }
        buttons.addView(button(positive, true) {
            dlg.dismiss()
            onPositive?.invoke()
        }, LinearLayout.LayoutParams(0, dp(46), 1f))
        box.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })

        dlg.setContentView(ScrollView(a).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(box)
        })
        dlg.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout((a.resources.displayMetrics.widthPixels * 0.9f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
            setSoftInputMode(
                if (keyboard) WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                else WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            )
        }
        dlg.show()
        if (Store.animations(a)) {
            box.alpha = 0f
            box.scaleX = 0.94f; box.scaleY = 0.94f
            box.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).start()
        }
        return dlg
    }
}
