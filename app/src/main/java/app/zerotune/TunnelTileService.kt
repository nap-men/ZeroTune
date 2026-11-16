package app.zerotune

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Плитка для шторки: тап — включить/выключить туннель. */
class TunnelTileService : TileService() {

    private val ui = Handler(Looper.getMainLooper())
    private val rerender = Runnable { render() }
    private var listening = false
    private var lastKey = ""

    /** Пока шторка открыта, раз в секунду сверяем плитку с реальным состоянием: не зависит от того, дошёл ли callback. */
    private val poll = object : Runnable {
        override fun run() {
            if (!listening) return
            render()
            ui.postDelayed(this, 1000)
        }
    }

    override fun onTileAdded() = render()

    override fun onStartListening() {
        // Пока шторка открыта, плитка сама следит за туннелем: включили в приложении, перехватил другой клиент — видно сразу
        listening = true
        lastKey = ""
        TunnelVpnService.onStateChanged = { render() }
        render()
        ui.removeCallbacks(poll)
        ui.postDelayed(poll, 1000)
    }

    override fun onStopListening() {
        listening = false
        TunnelVpnService.onStateChanged = null
        ui.removeCallbacks(rerender)
        ui.removeCallbacks(poll)
    }

    override fun onDestroy() {
        listening = false
        TunnelVpnService.onStateChanged = null
        ui.removeCallbacks(rerender)
        ui.removeCallbacks(poll)
        super.onDestroy()
    }

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
    }

    override fun onClick() {
        AppLog.i("Tile", "нажатие на плитку (сейчас: ${TunnelVpnService.state})")
        if (TunnelVpnService.isUp(this) || TunnelVpnService.isConnecting(this)) {
            paint(Tile.STATE_INACTIVE, tr(R.string.tile_stopping))
            try { TunnelVpnService.stop(this) } catch (t: Throwable) { AppLog.w("Tile", "stop", t) }
            scheduleRerender()
            return
        }
        if (Store.getLink(this).isEmpty()) {
            openApp(connect = false); return
        }
        // Первый раз нужно системное разрешение VPN — его можно получить только из Activity
        if (VpnService.prepare(this) != null) {
            openApp(connect = true); return
        }
        paint(Tile.STATE_ACTIVE, tr(R.string.st_connecting))
        try {
            TunnelVpnService.start(this)
        } catch (t: Throwable) {
            AppLog.w("Tile", "start", t)
            openApp(connect = true); return
        }
        scheduleRerender()
    }

    /** Сервис сам просит плитку обновиться, но на некоторых прошивках это не срабатывает — подстраховываемся. */
    private fun scheduleRerender() {
        ui.removeCallbacks(rerender)
        ui.postDelayed(rerender, 1500)
        ui.postDelayed(rerender, 4000)
    }

    private fun paint(state: Int, subtitle: String) {
        val t = qsTile ?: return
        val key = "$state|$subtitle"
        if (key == lastKey) return
        lastKey = key
        t.state = state
        t.label = "ZeroTune"
        if (Build.VERSION.SDK_INT >= 29) t.subtitle = subtitle
        t.updateTile()
    }

    private fun render() {
        when {
            TunnelVpnService.isUp(this) ->
                paint(Tile.STATE_ACTIVE, Store.activeProfile(this)?.name ?: tr(R.string.tile_on))
            TunnelVpnService.isConnecting(this) ->
                paint(Tile.STATE_ACTIVE, tr(R.string.st_connecting))
            else ->
                paint(Tile.STATE_INACTIVE, tr(R.string.tile_off))
        }
    }

    @Suppress("DEPRECATION")
    private fun openApp(connect: Boolean) {
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_CONNECT, connect)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            startActivityAndCollapse(i)
        }
    }
}
