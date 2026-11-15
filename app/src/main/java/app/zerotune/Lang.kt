package app.zerotune

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import java.util.Locale

/**
 * Язык интерфейса: русский или английский. Выбирается кнопкой на главном экране и хранится в настройках.
 * Пока выбор не сделан, берём язык системы (русский — русский, любой другой — английский).
 */
object Lang {
    const val RU = "ru"
    const val EN = "en"

    private val cache = HashMap<String, Context>()

    fun current(c: Context): String {
        val saved = Store.lang(c)
        if (saved == RU || saved == EN) return saved
        val sys = Resources.getSystem().configuration.locales.get(0).language
        return if (sys == RU) RU else EN
    }

    private fun build(base: Context, code: String): Context {
        val loc = Locale(code)
        Locale.setDefault(loc)
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(loc)
        return base.createConfigurationContext(cfg)
    }

    /** Для attachBaseContext экранов: разметка и getString сразу получают нужный язык. */
    fun wrap(base: Context): Context = build(base, current(base))

    /** Для сервиса, плитки и обычных классов: контекст с нужным языком (кэшируется). */
    @Synchronized
    fun localized(c: Context): Context {
        val app = c.applicationContext ?: c
        val code = current(app)
        return cache.getOrPut(code) { build(app, code) }
    }
}

/** Строка на выбранном языке. Работает в любом контексте (сервис, плитка, экран). */
fun Context.tr(id: Int, vararg args: Any): String = Lang.localized(this).getString(id, *args)
