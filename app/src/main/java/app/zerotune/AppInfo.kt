package app.zerotune

import android.content.Context

object AppInfo {
    // ---- данные для экрана «О приложении»: правь здесь ----

    /** Имя автора, как оно будет показано в приложении. */
    const val AUTHOR = "nap_men"

    /** Ссылка на репозиторий с исходным кодом. Пока пусто — кнопка скрыта. Вставь ссылку, например https://github.com/ник/zerotune */
    const val SOURCE_URL = "https://github.com/nap-men/ZeroTune"


    /** Версия из build.gradle.kts (versionName), например 1.1.0. */
    @Suppress("DEPRECATION")
    fun versionName(c: Context): String = try {
        c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }
}
