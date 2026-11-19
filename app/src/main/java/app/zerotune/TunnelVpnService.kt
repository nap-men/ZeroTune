package app.zerotune

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.os.PowerManager
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * VPN-сервис. Держится как foreground, перезапускает ядро при смене сети
 * и по таймеру проверяет, что ядро живо (watchdog).
 */
class TunnelVpnService : VpnService() {

    companion object {
        const val ACTION_START = "app.zerotune.START"
        const val ACTION_STOP = "app.zerotune.STOP"

        /** Обычный канал уведомления и тихий (минимальная важность). */
        const val CH_ID = "tunnel"
        const val CH_MIN_ID = "tunnel_min"

        private const val NOTIF_ID = 1
        private const val WATCHDOG_MS = 20_000L
        /** Сколько подряд неудачных запусков терпим, прежде чем остановиться (иначе цикл перезапусков до бесконечности). */
        private const val MAX_FAILURES = 3

        /** Работающий сервис (тот же процесс, что и экран): нужен, чтобы сразу обновить уведомление после смены настроек. */
        @Volatile var instance: TunnelVpnService? = null

        /**
         * Состояние туннеля живёт в памяти процесса, а не в настройках на диске: если систему убила
         * процесс, оно само сбрасывается в OFF, и «фантомного» включено не бывает.
         */
        @Volatile var state: TunnelState = TunnelState.OFF
            private set

        /** Подписка плитки: пока шторка открыта, плитка обновляется сразу при смене состояния. */
        @Volatile var onStateChanged: (() -> Unit)? = null

        private val mainHandler = Handler(Looper.getMainLooper())

        /** Туннель работает. Параметр оставлен, чтобы не менять вызовы в экранах. */
        @Suppress("UNUSED_PARAMETER")
        fun isUp(c: Context): Boolean = state == TunnelState.ON

        /** Туннель поднимается или переподключается. */
        @Suppress("UNUSED_PARAMETER")
        fun isConnecting(c: Context): Boolean = state == TunnelState.CONNECTING

        private fun setState(c: Context, s: TunnelState) {
            if (state == s) return
            state = s
            mainHandler.post { onStateChanged?.invoke() }
            refreshTile(c.applicationContext)
        }

        fun start(c: Context) {
            val i = Intent(c, TunnelVpnService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
        }

        fun stop(c: Context) {
            c.startService(Intent(c, TunnelVpnService::class.java).setAction(ACTION_STOP))
        }

        fun refreshTile(c: Context) {
            TileService.requestListeningState(c, ComponentName(c, TunnelTileService::class.java))
        }
    }

    private val ui = Handler(Looper.getMainLooper())
    /**
     * Подъём и остановка туннеля идут в отдельном потоке: запуск ядра и hev занимает время, и в главном
     * потоке он замораживал бы интерфейс (кнопка «залипала», анимации вставали).
     */
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var starting = false

    @Volatile private var engine: Engine? = null
    @Volatile private var tun: ParcelFileDescriptor? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var lastNetwork: Network? = null

    @Volatile private var monitor: StatsMonitor? = null
    @Volatile private var active = false
    private val notifLock = Any()
    private var lastNotifKey = ""
    private var failures = 0
    private var lastLaunchAt = 0L
    private val netRestart = Runnable { restartCore() }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (active && Store.wantOn(this@TunnelVpnService)) {
                if (engine?.isRunning != true && !starting) {
                    Log.w("ZeroTune", "watchdog: ядро не работает, перезапуск")
                    restartCore()
                }
                if (active) ui.postDelayed(this, WATCHDOG_MS)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Store.setWantOn(this, false)
            shutdown(startId)
            return START_NOT_STICKY
        }
        // После startForegroundService на Android 8+ нужно успеть вызвать startForeground (5 секунд),
        // иначе система роняет приложение — поэтому вызываем его до любых проверок
        startForeground(NOTIF_ID, notification())
        // START или перезапуск системой (intent == null) — поднимаем, если пользователь этого хочет
        if (intent == null && !Store.wantOn(this)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        Store.setWantOn(this, true)
        failures = 0
        active = true
        if (state == TunnelState.OFF) setState(this, TunnelState.CONNECTING)
        ensureInfra()
        restartCore()
        return START_STICKY
    }

    /** Ставит (пере)запуск туннеля в очередь рабочего потока. */
    private fun restartCore() {
        if (!active) return
        try {
            worker.execute {
                starting = true
                try { doRestart() }
                catch (t: Throwable) { Log.e("ZeroTune", "restart crashed", t) } // исключение в потоке уронило бы всё приложение
                finally { starting = false }
            }
        } catch (_: RejectedExecutionException) { /* сервис уже уничтожается */ }
    }

    @Synchronized
    private fun doRestart() {
        // Отложенные перезапуски (смена сети, watchdog) могут сработать уже после остановки
        if (!active) return
        // После быстрого «стоп → старт» очистка предыдущей остановки могла снять наблюдателей — ставим заново
        ensureInfra()
        if (state == TunnelState.OFF) setState(this, TunnelState.CONNECTING)
        try {
            Store.setLastError(this, "")
            val link = Store.getLink(this)
            // Свой JSON-конфиг не содержит SOCKS-входа для hev, поэтому с ним работает только встроенный режим
            val wantHev = Store.useHev(this) && !link.trimStart().startsWith("{") && Engine.hevAvailable()
            try {
                launch(link, wantHev)
            } catch (t: Throwable) {
                if (!wantHev) throw t
                // hev не поднялся — не оставляем пользователя без VPN, откатываемся на встроенный туннель Xray
                Log.w("ZeroTune", "hev не запустился, откат на встроенный режим", t)
                Store.setLastError(this, tr(R.string.err_hev_fallback, Engine.describe(this, t)))
                launch(link, false)
            }
            failures = 0
        } catch (t: Throwable) {
            Log.e("ZeroTune", "start failed", t)
            Store.setLastError(this, Engine.describe(this, t))
            Store.setRunning(this, false)
            if (active) setState(this, TunnelState.CONNECTING) // ждём следующую попытку
            failures++
            if (failures >= MAX_FAILURES) {
                // Не получается несколько раз подряд — останавливаемся, а не крутим перезапуски
                Store.setWantOn(this, false)
                ui.post { Toast.makeText(this, tr(R.string.toast_failed), Toast.LENGTH_LONG).show() }
                shutdown()
                return
            }
        }
        refreshTile(this)
    }

    /** Поднимает туннель с нуля в выбранном режиме. При любой ошибке бросает исключение, ничего не оставляя висеть. */
    private fun launch(link: String, hev: Boolean) {
        val old = engine; engine = null
        stopEngine(old)
        tun?.close(); tun = null

        val routing = Store.routing(this)
        val mtu = Store.mtu(this)
        val config = ConfigBuilder.build(link, routing, mtu, hev)
        val b = Builder()
            .setSession("ZeroTune")
            .setMtu(mtu)
            .addAddress("10.10.14.1", 30)
            .addAddress("fd00::1", 126)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .addDnsServer("1.1.1.1")
            .addDnsServer("8.8.8.8")
            .setBlocking(false)
        applyAppRouting(b, routing)
        if (Build.VERSION.SDK_INT >= 29) b.setMetered(false)

        val pfd = b.establish() ?: error(tr(R.string.err_vpn_perm))
        tun = pfd

        val e = Engine(this)
        try {
            e.start(config, pfd.fd, mtu, hev)
        } catch (t: Throwable) {
            e.stop()
            tun?.close(); tun = null
            throw t
        }
        // Пока поднимали туннель, его могли выключить или VPN мог перехватить другой клиент
        if (!active) {
            stopEngine(e)
            tun?.close(); tun = null
            return
        }
        engine = e
        lastLaunchAt = SystemClock.elapsedRealtime()

        Store.setRunning(this, true)
        setState(this, TunnelState.ON)

        // Пинг меряем до адреса выбранного сервера
        val si = ServerInfo.parse(link)
        monitor?.setTarget(si?.host ?: "", si?.port ?: 0)
        updateNotification(force = true)
    }

    /**
     * Какие приложения идут через туннель. Свой пакет всегда исключён — иначе сокеты ядра
     * попадали бы обратно в туннель и получилась бы петля.
     * addAllowedApplication и addDisallowedApplication смешивать нельзя.
     */
    private fun applyAppRouting(b: Builder, r: Routing) {
        val others = r.apps.filter { it != packageName }
        if (r.appMode == 1 && others.isNotEmpty()) {
            var added = 0
            for (pkg in others) {
                try { b.addAllowedApplication(pkg); added++ } catch (_: Exception) { /* приложение удалено */ }
            }
            if (added > 0) return
        }
        try { b.addDisallowedApplication(packageName) } catch (_: Exception) {}
        if (r.appMode == 2) {
            for (pkg in others) {
                try { b.addDisallowedApplication(pkg) } catch (_: Exception) { /* приложение удалено */ }
            }
        }
    }

    private fun registerNetworkWatcher() {
        if (netCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Чужой VPN — это тоже «сеть»: его включение и отключение не повод перезапускаться
                // (иначе при переключении с другого VPN на ZeroTune туннель дёргается в цикле)
                val caps = cm.getNetworkCapabilities(network)
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return

                // Wi‑Fi <-> мобильная сеть: ядру нужно пересоздать сокеты
                val changed = lastNetwork != null && lastNetwork != network
                lastNetwork = network
                if (!changed || !Store.wantOn(this@TunnelVpnService)) return

                // Сразу после запуска сеть ещё «шатается» (система гасит прежний VPN) — это не смена сети
                if (SystemClock.elapsedRealtime() - lastLaunchAt < 5_000) return

                ui.removeCallbacks(netRestart)
                ui.postDelayed(netRestart, 800)
            }
        }
        netCallback = cb
        cm.registerDefaultNetworkCallback(cb)
    }

    private val infraLock = Any()

    /**
     * Всё, что должно жить, пока туннель включён: wake lock, монитор скорости, слежение за сетью, watchdog.
     * Можно вызывать сколько угодно раз.
     */
    private fun ensureInfra() {
        synchronized(infraLock) {
            acquireWakeLock()
            ensureMonitor()
            registerNetworkWatcher()
            ui.removeCallbacks(watchdog)
            ui.postDelayed(watchdog, WATCHDOG_MS)
        }
    }

    /**
     * Останавливает ядро, но ждёт не дольше 4 секунд: когда VPN перехватывает другой клиент, остановка
     * иногда зависает, и без ограничения всё приложение «залипало» бы вместе с ней.
     */
    private fun stopEngine(e: Engine?) {
        if (e == null) return
        val t = Thread({ try { e.stop() } catch (_: Throwable) {} }, "zerotune-stop")
        t.isDaemon = true
        t.start()
        try { t.join(4000) } catch (_: InterruptedException) {}
    }

    /** Останавливает всё, что держит туннель. Безопасно вызывать повторно. Флаг active выставляет вызывающий. */
    private fun cleanup() {
        synchronized(infraLock) {
            monitor?.stop(); monitor = null
            ui.removeCallbacks(watchdog)
            ui.removeCallbacks(netRestart)
            netCallback?.let {
                try { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } catch (_: Exception) {}
            }
            netCallback = null
            lastNetwork = null
            wakeLock?.let { if (it.isHeld) it.release() }
        }
        val e = engine; engine = null
        stopEngine(e)
        tun?.close(); tun = null
        Store.setRunning(this, false)
    }

    private fun shutdown(startId: Int = -1) {
        // «Выключено» показываем сразу, не дожидаясь остановки ядра (она может занять время или зависнуть)
        synchronized(notifLock) { active = false }
        Store.setRunning(this, false)
        setState(this, TunnelState.OFF)

        val finish = Runnable {
            refreshTile(this)
            // Если за это время туннель уже включили снова — сервис не трогаем
            if (!active) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                if (startId >= 0) stopSelf(startId) else stopSelf()
            }
        }
        try {
            worker.execute {
                try { cleanup() }
                catch (t: Throwable) { Log.w("ZeroTune", "cleanup failed", t) }
                finally { ui.post(finish) }
            }
        } catch (_: RejectedExecutionException) {
            ui.post(finish)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zerotune:tunnel").apply {
            setReferenceCounted(false); acquire()
        }
    }

    private fun ensureMonitor() {
        if (monitor != null) return
        monitor = StatsMonitor(this) { updateNotification() }.also { it.start() }
    }

    // ---- уведомление ----

    /** Что показывать в уведомлении. По ключу решаем, нужно ли его публиковать заново. */
    private class NotifContent(val full: Boolean, val title: String, val text: String) {
        val key: String get() = "$full|$title|$text"
    }

    private fun notifContent(): NotifContent {
        // Тихий режим: Android не разрешает VPN-сервису работать совсем без уведомления,
        // поэтому остаётся минимальное — без текста и без обновлений
        if (!Store.notifEnabled(this)) return NotifContent(false, tr(R.string.notif_running), "")

        val server = Store.activeProfile(this)?.name ?: "ZeroTune"
        val text = when {
            !Store.notifStats(this) -> tr(R.string.st_connected)
            TunnelStats.supported ->
                tr(R.string.notif_stats_line, Fmt.speed(this, TunnelStats.downBps), Fmt.speed(this, TunnelStats.upBps), Fmt.ping(this, TunnelStats.pingMs))
            else -> tr(R.string.notif_ping_only, Fmt.ping(this, TunnelStats.pingMs))
        }
        return NotifContent(true, "ZeroTune · $server", text)
    }

    private fun notification(): Notification {
        val c = notifContent()
        val nm = getSystemService(NotificationManager::class.java)
        val channel = if (c.full) CH_ID else CH_MIN_ID
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                if (c.full) NotificationChannel(CH_ID, tr(R.string.ch_tunnel), NotificationManager.IMPORTANCE_LOW)
                else NotificationChannel(CH_MIN_ID, tr(R.string.ch_quiet), NotificationManager.IMPORTANCE_MIN)
            )
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, TunnelVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)

        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channel)
                else Notification.Builder(this)
        b.setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(c.title)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
        if (c.text.isNotEmpty()) b.setContentText(c.text)
        if (c.full) {
            b.addAction(Notification.Action.Builder(null, tr(R.string.notif_stop), stop).build())
        } else if (Build.VERSION.SDK_INT < 26) {
            applyMinPriority(b)
        }
        return b.build()
    }

    // До Android 8 важность задаётся приоритетом уведомления, а не каналом
    @Suppress("DEPRECATION")
    private fun applyMinPriority(b: Notification.Builder) {
        b.setPriority(Notification.PRIORITY_MIN)
    }

    /** Обновляет уведомление: раз в секунду из монитора и сразу после смены настроек. */
    fun updateNotification(force: Boolean = false) {
        synchronized(notifLock) {
            if (!active) return
            val key = notifContent().key
            if (!force && key == lastNotifKey) return
            lastNotifKey = key
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification())
        }
    }

    override fun onRevoke() { // пользователь отключил VPN в системе или его перехватило другое приложение
        Store.setWantOn(this, false)
        shutdown()
    }

    override fun onDestroy() {
        // Систему могла остановить сервис без нашей команды: освобождаем ядро, TUN и wake lock
        instance = null
        synchronized(notifLock) { active = false }
        Store.setRunning(this, false)
        setState(this, TunnelState.OFF)
        try { worker.execute { cleanup() } } catch (_: RejectedExecutionException) {}
        worker.shutdown()
        super.onDestroy()
    }
}

/** Состояние туннеля для плитки и экрана. */
enum class TunnelState { OFF, CONNECTING, ON }
