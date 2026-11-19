package app.zerotune

import android.content.Context
import com.v2ray.ang.service.TProxyService
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.io.File
import java.util.zip.ZipFile

/**
 * Вся работа с ядром Xray и туннелем собрана здесь.
 *
 * Два режима:
 *  - hev = true:  Xray слушает локальный SOCKS, а пакеты из VPN в него переправляет нативный
 *                 hev-socks5-tunnel (так делает v2rayNG, обычно быстрее);
 *  - hev = false: встроенный TUN-вход Xray (userspace-стек gVisor).
 *
 * Если у твоей сборки libv2ray.aar другие имена методов — правится только этот файл.
 */
class Engine(private val ctx: Context) {

    companion object {
        private const val TUN_IPV4 = "10.10.14.1"
        private const val TUN_IPV6 = "fd00::1"

        private var hevChecked = false
        private var hevOk = false

        @Volatile private var hevThrowable: Throwable? = null

        /** Почему библиотека hev не загрузилась (для экрана диагностики), на языке интерфейса. Пусто, если всё в порядке. */
        fun hevError(c: Context): String = hevThrowable?.let { describe(c, it) } ?: ""

        /**
         * Есть ли рабочая библиотека hev-socks5-tunnel. Проверяем один раз: при повторной проверке
         * Java выдаёт только «Could not initialize class» и настоящая причина теряется.
         */
        @Synchronized
        fun hevAvailable(): Boolean {
            if (!hevChecked) {
                hevChecked = true
                hevOk = try {
                    Class.forName("com.v2ray.ang.service.TProxyService") // выполнит System.loadLibrary
                    true
                } catch (t: Throwable) {
                    hevThrowable = t
                    AppLog.w("Engine", "hev-socks5-tunnel не загрузился", t)
                    false
                }
            }
            return hevOk
        }

        /** Короткое объяснение ошибки загрузки: что случилось и что с этим делать. */
        fun describe(c: Context, t: Throwable): String {
            val root = generateSequence(t) { it.cause }.last()
            val m = (root.message ?: t.message ?: "").take(300)
            val hint = when {
                root is ClassNotFoundException ->
                    c.tr(R.string.hev_err_class)
                m.contains("e_machine") || m.contains("32-bit") || m.contains("wrong ELF") ->
                    c.tr(R.string.hev_err_arch)
                m.contains("dlopen failed") && m.contains("not found") ->
                    c.tr(R.string.hev_err_notfound)
                root is NoSuchMethodError || m.contains("RegisterNatives") || m.contains("JNI_OnLoad") ->
                    c.tr(R.string.hev_err_jni)
                else -> ""
            }
            val base = "${root.javaClass.simpleName}: $m"
            return if (hint.isEmpty()) base else "$hint\n  ($base)"
        }

        /** Лежит ли .so внутри установленного APK (учитываем split-APK от Android Studio). */
        fun packagedHev(c: Context): String = try {
            val ai = c.applicationInfo
            val files = listOf(ai.sourceDir) + (ai.splitSourceDirs?.toList() ?: emptyList())
            val found = files.flatMap { f ->
                ZipFile(f).use { z -> z.entries().asSequence().map { it.name }.filter { it.contains("hev-socks5") }.toList() }
            }
            if (found.isEmpty()) c.tr(R.string.packaged_none) else found.joinToString()
        } catch (t: Throwable) {
            c.tr(R.string.packaged_fail)
        }
    }

    private var controller: CoreController? = null
    private var hevRunning = false

    private val handler = object : CoreCallbackHandler {
        override fun startup(): Long = 0
        override fun shutdown(): Long = 0
        override fun onEmitStatus(code: Long, msg: String?): Long {
            if (!msg.isNullOrBlank()) AppLog.d("Core", msg)
            return 0
        }
    }

    val isRunning: Boolean
        get() {
            val coreOk = try { controller?.isRunning == true } catch (e: Throwable) { false }
            if (!coreOk) return false
            if (!hevRunning) return true
            return try { TProxyService.isRunning() } catch (e: Throwable) { true }
        }

    fun start(configJson: String, tunFd: Int, mtu: Int, hev: Boolean) {
        AppLog.i("Engine", "запуск ядра Xray (${if (hev) "через hev-socks5-tunnel" else "встроенный TUN"}, MTU $mtu)")
        Libv2ray.initCoreEnv(ctx.filesDir.absolutePath, "")
        val c = Libv2ray.newCoreController(handler)
        // В режиме hev ядро не должно трогать TUN, поэтому fd = 0
        c.startLoop(configJson, if (hev) 0 else tunFd)
        controller = c

        if (hev) {
            val yaml = File(ctx.filesDir, "hev-socks5-tunnel.yaml")
            yaml.writeText(hevConfig(mtu))
            val ok = TProxyService.start(yaml.absolutePath, tunFd)
            AppLog.i("Engine", "hev-socks5-tunnel: запуск → ${if (ok) "ok" else "ошибка"}")
            // Без hev в этом режиме туннеля нет вовсе, поэтому ошибку отдаём наверх (сервис откатится на встроенный режим)
            if (!ok) throw IllegalStateException("hev-socks5-tunnel failed to start (start returned false)")
            hevRunning = true
        }
    }

    fun stop() {
        if (hevRunning) {
            try { TProxyService.stop() } catch (e: Throwable) { AppLog.w("Engine", "hev stop", e) }
            hevRunning = false
        }
        try { controller?.stopLoop() } catch (e: Throwable) { AppLog.w("Engine", "остановка ядра", e) }
        controller = null
    }

    private fun hevConfig(mtu: Int): String = """
        tunnel:
          mtu: $mtu
          ipv4: $TUN_IPV4
          ipv6: '$TUN_IPV6'
        socks5:
          port: ${ConfigBuilder.SOCKS_PORT}
          address: 127.0.0.1
          udp: 'udp'
        misc:
          tcp-read-write-timeout: 300000
          udp-read-write-timeout: 60000
          log-level: error
    """.trimIndent() + "\n"
}
