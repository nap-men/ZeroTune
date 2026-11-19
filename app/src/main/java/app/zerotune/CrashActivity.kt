package app.zerotune

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Locale

/** Запись о вылете: пишется при необработанном исключении и показывается при следующем запуске. */
object CrashReport {
    private const val FILE = "crash.txt"

    fun install(c: Context) {
        val app = c.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val text = ("ZeroTune ${AppInfo.versionName(app)} · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · " +
                    "${Build.MANUFACTURER} ${Build.MODEL}\nПоток: ${t.name}\n\n$sw").take(8000)
                File(app.filesDir, FILE).writeText(text)
                AppLog.e("Crash", "необработанное исключение в потоке ${t.name}", e)
            } catch (_: Throwable) { /* обработчик сам не должен падать */ }
            prev?.uncaughtException(t, e)
        }
    }

    fun pending(c: Context): Boolean = File(c.filesDir, FILE).exists()

    fun read(c: Context): String = try { File(c.filesDir, FILE).readText() } catch (_: Throwable) { "" }

    fun clear(c: Context) { try { File(c.filesDir, FILE).delete() } catch (_: Throwable) {} }
}

/**
 * Экран «ZeroTune вылетел». Специально не зависит от темы, языка и настроек приложения (причиной вылета
 * может быть как раз что-то из них): цвета и тексты заданы прямо здесь.
 */
class CrashActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ru = Locale.getDefault().language == "ru"
        val text = AppLog.redact(CrashReport.read(this))
        fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
        fun pill(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(14).toFloat() }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0E1318.toInt())
            setPadding(dp(20), dp(36), dp(20), dp(16))
        }
        root.addView(TextView(this).apply {
            this.text = if (ru) "ZeroTune вылетел" else "ZeroTune crashed"
            textSize = 24f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            this.text = if (ru) "Скопируй текст ниже и отправь автору (nap-men), так ошибку проще найти. Ключи и адреса серверов скрыты."
            else "Copy the text below and send it to the author (nap-men) so the bug is easier to find. Keys and server addresses are hidden."
            textSize = 14f
            setTextColor(0xFF9AA7B2.toInt())
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        val body = TextView(this).apply {
            this.text = text.ifBlank { if (ru) "Подробностей нет." else "No details." }
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(0xFFDDE3E8.toInt())
            setTextIsSelectable(true)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        root.addView(ScrollView(this).apply {
            background = pill(0xFF161D24.toInt())
            addView(body)
        }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(14) })

        fun button(label: String, main: Boolean, click: () -> Unit) = TextView(this).apply {
            this.text = label
            gravity = Gravity.CENTER
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (main) 0xFF04251C.toInt() else 0xFFFFFFFF.toInt())
            background = pill(if (main) 0xFF14C58B.toInt() else 0xFF1F2933.toInt())
            setOnClickListener { click() }
        }

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button(if (ru) "Копировать" else "Copy", true) {
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("ZeroTune crash", text))
            Toast.makeText(this, if (ru) "Скопировано" else "Copied", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        row.addView(button(if (ru) "Поделиться" else "Share", false) {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "ZeroTune crash").putExtra(Intent.EXTRA_TEXT, text)
            try { startActivity(Intent.createChooser(send, "ZeroTune")) } catch (_: Exception) {}
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(10) })
        root.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        root.addView(button(if (ru) "Продолжить" else "Continue", false) {
            CrashReport.clear(this)
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) })

        setContentView(root)
    }
}
