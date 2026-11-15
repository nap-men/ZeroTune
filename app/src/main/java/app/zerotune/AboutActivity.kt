package app.zerotune

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Отдельный экран «О приложении»: версия, описание, автор, пометка про ИИ и ссылка на исходный код. */
class AboutActivity : Activity() {

    private lateinit var pal: Palette

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Lang.wrap(newBase))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = sp
        setTextColor(color)
        setLineSpacing(dp(2).toFloat(), 1f)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun lp(h: Int, top: Int = 0) =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h).apply { topMargin = dp(top) }

    private fun card(vararg views: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Theme.card(this@AboutActivity, pal)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        views.forEach { addView(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pal = Theme.palette(this)
        Theme.applyWindow(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(28))
        }
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundColor(pal.bg)
            addView(root)
        })

        root.addView(text(getString(R.string.back), 15f, pal.accent).apply {
            setPadding(0, dp(4), 0, dp(8))
            setOnClickListener { finish() }
        })
        root.addView(text(getString(R.string.about_title), 26f, pal.textPrimary, true))

        // приложение
        val appBox = card(text(getString(R.string.about_version, AppInfo.versionName(this)), 16f, pal.textPrimary, true))
        appBox.addView(text(getString(R.string.about_desc), 13f, pal.textSecondary), lp(-2, 8))
        appBox.addView(text(getString(R.string.about_credits), 13f, pal.textSecondary), lp(-2, 10))
        root.addView(appBox, lp(-2, 18))

        // автор
        val author = text(getString(R.string.about_author, AppInfo.AUTHOR), 16f, pal.textPrimary, true)
        val authorText = text(getString(R.string.about_author_text), 13f, pal.textSecondary)
        val box = card(author)
        box.addView(authorText, lp(-2, 8))
        root.addView(box, lp(-2, 14))

        // ИИ
        val ai = card()
        ai.addView(text(getString(R.string.about_ai), 13f, pal.textSecondary))
        root.addView(ai, lp(-2, 14))

        // исходный код
        if (AppInfo.SOURCE_URL.isNotEmpty()) {
            root.addView(text(getString(R.string.source_btn), 14f, pal.textPrimary).apply {
                gravity = Gravity.CENTER
                background = Theme.button(this@AboutActivity, pal)
                setOnClickListener {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AppInfo.SOURCE_URL)))
                    } catch (e: Exception) {
                        Toast.makeText(this@AboutActivity, getString(R.string.toast_no_link), Toast.LENGTH_SHORT).show()
                    }
                }
            }, lp(dp(46), 14))
        }
    }
}
