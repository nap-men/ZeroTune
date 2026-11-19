package app.zerotune

import android.content.Context
import android.net.TrafficStats
import android.os.PowerManager
import android.os.Process
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Данные для экрана и уведомления. Сервис и экран живут в одном процессе, поэтому хватает обычных полей. */
object TunnelStats {
    const val PING_NONE = -1
    const val PING_FAIL = -2

    @Volatile var downBps: Long = 0              // скорость загрузки, байт/с
    @Volatile var upBps: Long = 0                // скорость отдачи, байт/с
    @Volatile var pingMs: Int = PING_NONE
    @Volatile var supported: Boolean = true      // false, если система не отдаёт счётчики трафика

    fun reset() {
        downBps = 0
        upBps = 0
        pingMs = PING_NONE
        supported = true
    }
}

object Fmt {
    /** Скорость в битах, как в speedtest: Кбит/с и Мбит/с (или Kbit/s и Mbit/s). */
    fun speed(c: Context, bytesPerSec: Long): String {
        val kbit = bytesPerSec * 8.0 / 1000.0
        return if (kbit >= 1000.0) c.tr(R.string.unit_mbit, kbit / 1000.0) else c.tr(R.string.unit_kbit, kbit)
    }

    fun ping(c: Context, ms: Int): String = when {
        ms >= 0 -> c.tr(R.string.ping_ms, ms)
        ms == TunnelStats.PING_FAIL -> c.tr(R.string.ping_fail)
        else -> "—"
    }
}

/**
 * Раз в секунду считает скорость, раз в 5 секунд мерит пинг.
 *
 * Скорость: счётчики трафика собственного приложения (TrafficStats). Ядро Xray работает внутри
 * нашего процесса, а приложение исключено из VPN, поэтому счётчики показывают реальный
 * сетевой трафик туннеля (и через сервер, и напрямую), без трафика самих приложений до туннеля.
 *
 * Пинг: время TCP-подключения к серверу (один круг туда-обратно). Меряется напрямую, мимо VPN.
 *
 * Пока экран выключен, уведомление и пинг не обновляются, чтобы не будить телефон.
 */
class StatsMonitor(private val ctx: Context, private val onUpdate: () -> Unit) {

    private val uid = Process.myUid()
    private var exec: ScheduledExecutorService? = null

    private var lastRx = 0L
    private var lastTx = 0L
    private var lastT = 0L

    @Volatile private var host = ""
    @Volatile private var port = 0
    private var resolved: InetAddress? = null
    private var resolvedHost = ""

    /** Куда мерить пинг. Вызывается при каждом (пере)запуске туннеля. */
    fun setTarget(host: String, port: Int) {
        this.host = host
        this.port = port
        TunnelStats.pingMs = TunnelStats.PING_NONE
    }

    fun start() {
        stop()
        TunnelStats.reset()
        lastRx = TrafficStats.getUidRxBytes(uid)
        lastTx = TrafficStats.getUidTxBytes(uid)
        lastT = System.nanoTime()

        val e = Executors.newScheduledThreadPool(2)
        e.scheduleWithFixedDelay({ safe { tickSpeed() } }, 1, 1, TimeUnit.SECONDS)
        e.scheduleWithFixedDelay({ safe { tickPing() } }, 1, 5, TimeUnit.SECONDS)
        exec = e
    }

    fun stop() {
        exec?.shutdownNow()
        exec = null
        TunnelStats.reset()
    }

    private inline fun safe(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            AppLog.w("Stats", "счётчики трафика", t)
        }
    }

    private fun isScreenOn(): Boolean =
        ctx.getSystemService(PowerManager::class.java).isInteractive

    private fun tickSpeed() {
        val rx = TrafficStats.getUidRxBytes(uid)
        val tx = TrafficStats.getUidTxBytes(uid)
        val now = System.nanoTime()

        val unsupported = TrafficStats.UNSUPPORTED.toLong()
        if (rx == unsupported || tx == unsupported) {
            TunnelStats.supported = false
            return
        }

        val dt = (now - lastT) / 1_000_000_000.0
        if (dt > 0.2) {
            // coerceAtLeast: после перезагрузки счётчики обнуляются, и разница может стать отрицательной
            TunnelStats.downBps = ((rx - lastRx) / dt).toLong().coerceAtLeast(0)
            TunnelStats.upBps = ((tx - lastTx) / dt).toLong().coerceAtLeast(0)
            lastRx = rx
            lastTx = tx
            lastT = now
        }
        if (isScreenOn()) onUpdate()
    }

    private fun tickPing() {
        if (!isScreenOn()) return

        val h = host
        val p = port
        if (h.isEmpty() || p <= 0) {
            TunnelStats.pingMs = TunnelStats.PING_NONE
            return
        }

        // Адрес резолвим один раз на сервер, чтобы в пинг не попадало время DNS
        val addr = if (resolvedHost == h && resolved != null) {
            resolved!!
        } else {
            try {
                InetAddress.getByName(h).also { resolved = it; resolvedHost = h }
            } catch (e: Exception) {
                TunnelStats.pingMs = TunnelStats.PING_FAIL
                onUpdate()
                return
            }
        }

        val s = Socket()
        try {
            val t0 = System.nanoTime()
            s.connect(InetSocketAddress(addr, p), 3000)
            TunnelStats.pingMs = ((System.nanoTime() - t0) / 1_000_000).toInt()
        } catch (e: Exception) {
            TunnelStats.pingMs = TunnelStats.PING_FAIL
        } finally {
            try { s.close() } catch (_: Exception) {}
        }
        onUpdate()
    }
}

/** Проверка доступности сервера до подключения: время TCP-рукопожатия с адресом и портом сервера. */
object NetCheck {
    const val FAIL = -1

    /** Время в миллисекундах или FAIL, если сервер не ответил за 3 секунды. */
    fun tcpPing(host: String, port: Int): Int {
        val s = Socket()
        return try {
            val addr = InetSocketAddress(host, port) // здесь же разрешается имя, чтобы не считать DNS в пинг
            if (addr.isUnresolved) return FAIL
            val t0 = System.nanoTime()
            s.connect(addr, 3000)
            ((System.nanoTime() - t0) / 1_000_000).toInt()
        } catch (_: Exception) {
            FAIL
        } finally {
            try { s.close() } catch (_: Exception) {}
        }
    }
}
