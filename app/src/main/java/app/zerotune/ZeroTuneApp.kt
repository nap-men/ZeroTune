package app.zerotune

import android.app.Application

/** Точка входа процесса: первым делом подключает перехватчик вылетов, потом журнал. Каждый шаг в своём try, чтобы сбой одного не мешал другому. */
class ZeroTuneApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try { CrashReport.install(this) } catch (_: Throwable) {}
        try { AppLog.init(this) } catch (_: Throwable) {}
    }
}
