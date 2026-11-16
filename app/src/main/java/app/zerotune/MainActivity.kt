package app.zerotune

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.RippleDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.ScrollView
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PorterDuff
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.LayoutInflater
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import java.util.concurrent.Executors
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        /** Плитка открывает экран с этим флагом, когда нужно разрешение VPN: сразу показываем системный диалог. */
        const val EXTRA_CONNECT = "app.zerotune.CONNECT"
    }

    private lateinit var power: ImageView
    private lateinit var powerAnim: PowerAnim
    private var appliedStamp = 0
    private var pendingUntil = 0L   // пока true — показываем «Подключение…», даже если сервис ещё не ответил
    private var offUntil = 0L       // сразу после «выключить» показываем выключенное состояние
    private var statsShown = false

    // ---- проверка пинга серверов ----
    private val pingPool = Executors.newFixedThreadPool(4)
    private val pings = HashMap<String, Int>()        // id сервера -> мс, NetCheck.FAIL или PING_CHECKING
    private val pingAt = HashMap<String, Long>()      // когда проверяли в последний раз
    private val pingViews = HashMap<String, TextView>()
    private val PING_CHECKING = -2
    private lateinit var status: TextView
    private lateinit var statusSub: TextView
    private lateinit var battery: TextView
    private lateinit var serversList: LinearLayout
    private lateinit var serversEmpty: TextView
    private lateinit var statsRow: View
    private lateinit var statDown: TextView
    private lateinit var statUp: TextView
    private lateinit var statPing: TextView

    private val ui = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() { render(); ui.postDelayed(this, 1000) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Lang.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Theme.apply(this)
        appliedStamp = Theme.stamp(this)
        AppLog.init(applicationContext)

        power = findViewById(R.id.btnPower)
        powerAnim = PowerAnim(power, findViewById(R.id.ringA), findViewById(R.id.ringB))
        power.setOnClickListener { onToggle() }
        findViewById<TextView>(R.id.btnPaste).setOnClickListener { pasteFromClipboard() }
        findViewById<TextView>(R.id.btnAddManual).setOnClickListener { showAddDialog() }
        status = findViewById(R.id.status)
        statusSub = findViewById(R.id.statusSub)
        battery = findViewById(R.id.batteryStatus)
        serversList = findViewById(R.id.serversList)
        serversEmpty = findViewById(R.id.serversEmpty)
        statsRow = findViewById(R.id.statsRow)
        statDown = findViewById(R.id.statDown)
        statUp = findViewById(R.id.statUp)
        statPing = findViewById(R.id.statPing)

        findViewById<TextView>(R.id.appVersion).text = getString(R.string.app_version_line, AppInfo.versionName(this))

        // Язык (слева сверху) и «О приложении»
        val btnLang = findViewById<TextView>(R.id.btnLang)
        btnLang.text = "🌐 ${Lang.current(this).uppercase()}"
        btnLang.setOnClickListener { v -> showLangPopup(v) }
        findViewById<View>(R.id.btnAbout).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }

        // Быстрый туннель (hev-socks5-tunnel)
        val cbHev = findViewById<CheckBox>(R.id.cbHev)
        val hevInfo = findViewById<TextView>(R.id.hevInfo)
        val hevLib = Engine.hevAvailable()
        cbHev.isChecked = Store.useHev(this)
        hevInfo.text = getString(if (hevLib) R.string.hev_found else R.string.hev_missing)
        cbHev.setOnCheckedChangeListener { _, checked ->
            Store.setUseHev(this, checked)
            AppLog.i("Settings", "быстрый туннель (hev): ${if (checked) "вкл" else "выкл"}")
            if (TunnelVpnService.isUp(this)) {
                TunnelVpnService.start(this) // перезапуск в выбранном режиме
                toast(getString(R.string.toast_mode_changed))
            }
        }

        // MTU
        val etMtu = findViewById<EditText>(R.id.etMtu)
        etMtu.setText(Store.mtu(this).toString())
        findViewById<TextView>(R.id.btnMtu).setOnClickListener {
            val v = etMtu.text.toString().toIntOrNull()
            if (v == null || v < 1280 || v > 1500) {
                toast(getString(R.string.toast_mtu_range))
            } else {
                Store.setMtu(this, v)
                AppLog.i("Settings", "MTU: $v")
                if (TunnelVpnService.isUp(this)) {
                    TunnelVpnService.start(this) // перезапуск с новым MTU
                    toast(getString(R.string.toast_mtu_restart, v))
                } else {
                    toast(getString(R.string.toast_mtu_saved, v))
                }
            }
        }

        // Уведомление
        val cbNotif = findViewById<CheckBox>(R.id.cbNotif)
        val cbNotifStats = findViewById<CheckBox>(R.id.cbNotifStats)
        cbNotif.isChecked = Store.notifEnabled(this)
        cbNotifStats.isChecked = Store.notifStats(this)
        cbNotifStats.isEnabled = cbNotif.isChecked
        cbNotif.setOnCheckedChangeListener { _, checked ->
            Store.setNotifEnabled(this, checked)
            cbNotifStats.isEnabled = checked
            TunnelVpnService.instance?.updateNotification(force = true)
        }
        cbNotifStats.setOnCheckedChangeListener { _, checked ->
            Store.setNotifStats(this, checked)
            TunnelVpnService.instance?.updateNotification(force = true)
        }
        findViewById<TextView>(R.id.btnNotifSystem).setOnClickListener { openNotificationSettings() }

        findViewById<View>(R.id.btnTheme).setOnClickListener {
            startActivity(Intent(this, ThemeActivity::class.java))
        }
        findViewById<View>(R.id.btnRouting).setOnClickListener {
            startActivity(Intent(this, RoutingActivity::class.java))
        }
        findViewById<TextView>(R.id.btnBattery).setOnClickListener { HuaweiHelper.requestIgnoreBattery(this) }
        findViewById<TextView>(R.id.btnAutostart).setOnClickListener { HuaweiHelper.openAutostart(this) }
        findViewById<TextView>(R.id.btnDiag).setOnClickListener { HuaweiHelper.showDiagnostics(this) }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }

        renderServers()
        findViewById<TextView>(R.id.btnPingAll).apply {
            text = "↻ " + getString(R.string.ping_check)
            setOnClickListener { checkAllPings(force = true) }
        }
        setupTabs(savedInstanceState)
        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", tab)
        outState.putInt("logRank", logRank)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (tab != 0) showTab(0) else super.onBackPressed()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i != null && i.getBooleanExtra(EXTRA_CONNECT, false)) {
            i.removeExtra(EXTRA_CONNECT)
            if (!TunnelVpnService.isUp(this)) onToggle()
        }
    }

    override fun onResume() {
        super.onResume()
        // Цвета поменялись на экране «Оформление» — пересоздаём экран с новой темой
        if (Theme.stamp(this) != appliedStamp) { recreate(); return }
        renderServers()
        checkAllPings(force = false)
        ui.post(poll)
        if (tab == 1) { AppLog.listener = { scheduleLogRender() }; renderLogs(true) }
    }

    override fun onPause() {
        AppLog.listener = null
        ui.removeCallbacks(logRender)
        ui.removeCallbacks(poll)
        powerAnim.stop() // не крутим анимации в фоне
        TunnelVpnService.refreshTile(this) // уходим из приложения — плитка должна показывать актуальное
        super.onPause()
    }

    // ---- серверы ----

    private fun renderServers() {
        val list = Store.profiles(this)
        val activeId = Store.activeProfile(this)?.id
        serversList.removeAllViews()
        pingViews.clear()
        serversEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE

        val inflater = LayoutInflater.from(this)
        val pal = Theme.palette(this)
        for (p in list) {
            val row = inflater.inflate(R.layout.item_server, serversList, false)
            val isActive = p.id == activeId
            val info = ServerInfo.parse(p.link)

            Theme.recolor(this, row, pal)
            row.background = Theme.server(this, pal, isActive)
            row.findViewById<View>(R.id.serverDot).background = Theme.dot(this, pal, isActive)
            row.findViewById<TextView>(R.id.serverName).text = p.name
            row.findViewById<TextView>(R.id.serverAddr).text = info?.address ?: ""
            row.findViewById<TextView>(R.id.serverTags).text = info?.tags?.joinToString(" · ") ?: ""

            val pingView = row.findViewById<TextView>(R.id.serverPing)
            pingViews[p.id] = pingView
            paintPing(pingView, pings[p.id])
            pingView.setOnClickListener { checkPing(p) }

            row.setOnClickListener { selectServer(p) }
            row.findViewById<View>(R.id.serverDelete).setOnClickListener { confirmDelete(p) }
            serversList.addView(row)
        }
    }

    private fun selectServer(p: Profile) {
        if (Store.activeProfile(this)?.id == p.id) return
        AppLog.i("Servers", "выбран сервер «${p.name}»")
        Store.setActiveId(this, p.id)
        renderServers()
        render()
        if (TunnelVpnService.isUp(this)) {
            TunnelVpnService.start(this) // переподключение к выбранному серверу
            toast(getString(R.string.toast_server_switch, p.name))
        }
    }

    private fun confirmDelete(p: Profile) {
        AppDialog.show(
            this,
            title = getString(R.string.dlg_delete_title),
            message = p.name,
            positive = getString(R.string.dlg_delete),
            negative = getString(R.string.dlg_cancel),
            danger = true
        ) {
            val wasActive = Store.activeProfile(this)?.id == p.id
            AppLog.i("Servers", "удалён сервер «${p.name}»")
            Store.removeProfile(this, p.id)
            renderServers()
            render()
            if (wasActive && TunnelVpnService.isUp(this)) {
                if (Store.getLink(this).isEmpty()) TunnelVpnService.stop(this)
                else TunnelVpnService.start(this)
            }
        }
    }

    private fun pasteFromClipboard() {
        val cm = getSystemService(ClipboardManager::class.java)
        val text = try {
            val clip = cm.primaryClip
            if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this).toString() else ""
        } catch (e: Exception) {
            ""
        }
        if (text.isBlank()) {
            toast(getString(R.string.toast_clip_empty))
            return
        }
        importText(text)
    }

    private fun showAddDialog() {
        val pal = Theme.palette(this)
        val input = EditText(this).apply {
            hint = getString(R.string.hint_add)
            setHintTextColor(pal.textSecondary)
            setTextColor(pal.textPrimary)
            textSize = 14f
            minLines = 3
            maxLines = 6
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            background = Theme.input(this@MainActivity, pal)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        AppDialog.show(
            this,
            title = getString(R.string.dlg_add_title),
            content = input,
            positive = getString(R.string.dlg_add),
            negative = getString(R.string.dlg_cancel),
            keyboard = true
        ) { importText(input.text.toString()) }
    }

    private fun importText(text: String) {
        val r = Importer.import(this, text)
        AppLog.i("Servers", "импорт: добавлено ${r.added}, дубликатов ${r.duplicates}, неверных ${r.invalid}")
        val msg = when {
            r.added == 1 -> getString(R.string.import_added, r.firstAdded?.name ?: "")
            r.added > 1 -> {
                val dups = if (r.duplicates > 0) getString(R.string.import_dups_suffix, r.duplicates) else ""
                getString(R.string.import_many, r.added, dups)
            }
            r.duplicates > 0 -> getString(R.string.import_dup)
            r.invalid > 0 -> getString(R.string.import_invalid)
            else -> getString(R.string.import_none)
        }
        toast(msg)
        renderServers()
        render()
    }

    // ---- состояние ----

    private fun render() {
        val now = SystemClock.elapsedRealtime()
        val pal = Theme.palette(this)
        val active = Store.activeProfile(this)
        val accent = pal.accent

        // Как только сервис подтвердил остановку — снимаем «притворное» выключено, чтобы кнопка не мигала
        if (offUntil > 0 && !TunnelVpnService.isUp(this)) offUntil = 0
        val on = TunnelVpnService.isUp(this) && now >= offUntil
        if (on) pendingUntil = 0
        val connecting = !on && now >= offUntil &&
            (TunnelVpnService.isConnecting(this) || now < pendingUntil)

        powerAnim.apply(
            if (on) PowerAnim.ON else if (connecting) PowerAnim.CONNECTING else PowerAnim.OFF,
            pal, Theme.stamp(this), true
        )
        status.text = when {
            on -> getString(R.string.st_connected)
            connecting -> getString(R.string.st_connecting)
            else -> getString(R.string.status_off)
        }
        status.setTextColor(if (on || connecting) pal.power else pal.textPrimary)
        statusSub.text = when {
            on || connecting -> getString(R.string.st_server, active?.name ?: "—")
            active == null -> getString(R.string.st_add_server)
            Store.lastError(this).isNotEmpty() && !Store.wantOn(this) -> getString(R.string.st_error_diag)
            else -> getString(R.string.status_hint)
        }

        // Скорость и пинг показываем только при подключении; появляются плавно
        if (on && !statsShown) {
            statsShown = true
            statsRow.visibility = View.VISIBLE
            if (Store.animations(this)) {
                statsRow.alpha = 0f
                statsRow.translationY = dp(16).toFloat()
                statsRow.animate().alpha(1f).translationY(0f).setDuration(300).start()
            }
        } else if (!on && statsShown) {
            statsShown = false
            statsRow.animate().cancel()
            statsRow.alpha = 1f
            statsRow.translationY = 0f
            statsRow.visibility = View.GONE
        }
        if (on) {
            val counters = TunnelStats.supported
            statDown.text = if (counters) Fmt.speed(this, TunnelStats.downBps) else "—"
            statUp.text = if (counters) Fmt.speed(this, TunnelStats.upBps) else "—"
            statPing.text = Fmt.ping(this, TunnelStats.pingMs)
        }

        val r = Store.routing(this)
        val appPart = when (r.appMode) {
            1 -> getString(R.string.route_apps_only, r.apps.size)
            2 -> getString(R.string.route_apps_except, r.apps.size)
            else -> getString(R.string.route_apps_all)
        }
        val sitePart = getString(if (r.siteMode == 1) R.string.route_sites_list else if (r.ruDirect) R.string.route_sites_ru else R.string.route_sites_all)
        findViewById<TextView>(R.id.routingSummary).text = "$appPart · $sitePart"

        val ok = HuaweiHelper.isIgnoringBatteryOptimizations(this)
        battery.text = getString(if (ok) R.string.battery_ok else R.string.battery_bad)
        battery.setTextColor(if (ok) accent else pal.warn)
    }

    private fun onToggle() {
        power.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        AppLog.i("UI", "кнопка питания (сейчас: ${TunnelVpnService.state})")
        if (TunnelVpnService.isUp(this) || TunnelVpnService.isConnecting(this)) {
            pendingUntil = 0
            offUntil = SystemClock.elapsedRealtime() + 4000
            TunnelVpnService.stop(this)
            TunnelVpnService.refreshTile(this)
            render()
            return
        }
        if (Store.getLink(this).isEmpty()) { toast(getString(R.string.toast_add_first)); return }
        val prep = VpnService.prepare(this)
        if (prep != null) startActivityForResult(prep, 1) else startTunnel()
    }

    private fun startTunnel() {
        offUntil = 0
        pendingUntil = SystemClock.elapsedRealtime() + 6000
        TunnelVpnService.start(this)
        TunnelVpnService.refreshTile(this)
        render()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 1 && resultCode == RESULT_OK) startTunnel()
    }

    /** Открывает системные настройки канала уведомления, где его можно выключить совсем. */
    private fun openNotificationSettings() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                val channel = if (Store.notifEnabled(this)) TunnelVpnService.CH_ID else TunnelVpnService.CH_MIN_ID
                startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, channel))
            } else {
                HuaweiHelper.openAppSettings(this)
            }
        } catch (e: Exception) {
            // Канала ещё нет (туннель не запускался) или экран недоступен — открываем настройки приложения
            try { HuaweiHelper.openAppSettings(this) } catch (_: Exception) { toast(getString(R.string.toast_no_settings)) }
        }
    }

    /** Выбор языка в стиле приложения: карточка с темными/светлыми цветами темы и галочкой у текущего языка. */
    private fun showLangPopup(anchor: View) {
        val pal = Theme.palette(this)
        val cur = Lang.current(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.card(this@MainActivity, pal)
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        val pop = PopupWindow(box, dp(180), LinearLayout.LayoutParams.WRAP_CONTENT, true)
        pop.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        pop.isOutsideTouchable = true
        pop.elevation = dp(10).toFloat()

        for ((code, label) in listOf(Lang.RU to R.string.lang_ru, Lang.EN to R.string.lang_en)) {
            val selected = code == cur
            val row = TextView(this).apply {
                text = if (selected) "✓  ${getString(label)}" else "     ${getString(label)}"
                textSize = 15f
                setTextColor(if (selected) pal.accent else pal.textPrimary)
                if (selected) setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), 0, dp(14), 0)
                background = Theme.button(this@MainActivity, pal).also { it.alpha = if (selected) 255 else 0 }
                setOnClickListener {
                    pop.dismiss()
                    setLanguage(code)
                }
            }
            box.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)).apply {
                if (box.childCount > 0) topMargin = dp(4)
            })
        }

        pop.showAsDropDown(anchor, 0, dp(6))
        if (Store.animations(this)) {
            box.alpha = 0f
            box.scaleX = 0.92f; box.scaleY = 0.92f
            box.pivotX = 0f; box.pivotY = 0f
            box.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).start()
        }
    }

    /** Окрашивает пинг: быстро — зелёный, средне — жёлтый, долго или нет ответа — красный. */
    private fun paintPing(tv: TextView, ms: Int?) {
        val pal = Theme.palette(this)
        when {
            ms == null -> { tv.text = "—"; tv.setTextColor(pal.textSecondary) }
            ms == PING_CHECKING -> { tv.text = "…"; tv.setTextColor(pal.textSecondary) }
            ms == NetCheck.FAIL -> { tv.text = getString(R.string.ping_fail); tv.setTextColor(0xFFEF4444.toInt()) }
            else -> {
                tv.text = getString(R.string.ping_ms, ms)
                tv.setTextColor(when {
                    ms < 150 -> 0xFF2ECC71.toInt()
                    ms < 400 -> pal.warn
                    else -> 0xFFEF4444.toInt()
                })
            }
        }
    }

    private fun checkPing(p: Profile) {
        val info = ServerInfo.parse(p.link) ?: return
        if (info.host.isEmpty() || info.port <= 0) return // свой JSON-конфиг: адрес неизвестен
        pings[p.id] = PING_CHECKING
        pingViews[p.id]?.let { paintPing(it, PING_CHECKING) }
        try {
            pingPool.execute {
                val ms = NetCheck.tcpPing(info.host, info.port)
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    pings[p.id] = ms
                    pingAt[p.id] = SystemClock.elapsedRealtime()
                    AppLog.d("Ping", "«${p.name}»: ${if (ms >= 0) "$ms мс" else "недоступен"}")
                    pingViews[p.id]?.let { paintPing(it, ms) }
                }
            }
        } catch (_: Exception) { /* экран закрывается */ }
    }

    /** Проверяет все серверы параллельно. Без force не чаще раза в 30 секунд на сервер. */
    private fun checkAllPings(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        for (p in Store.profiles(this)) {
            val last = pingAt[p.id] ?: 0L
            if (!force && last != 0L && now - last < 30_000) continue
            if (pings[p.id] == PING_CHECKING && !force) continue
            checkPing(p)
        }
    }

    override fun onDestroy() {
        pingPool.shutdownNow()
        super.onDestroy()
    }

    private fun setLanguage(code: String) {
        if (code == Lang.current(this)) return
        AppLog.i("UI", "язык: $code")
        Store.setLang(this, code)
        TunnelVpnService.instance?.updateNotification(force = true)
        TunnelVpnService.refreshTile(this)
        recreate()
    }

    // ---- вкладки и логи ----

    private var tab = 0
    private var logRank = 0
    @Volatile private var logPosted = false
    private val logRender = Runnable { logPosted = false; renderLogs(false) }

    private fun setupTabs(state: Bundle?) {
        findViewById<View>(R.id.navMain).setOnClickListener { showTab(0) }
        findViewById<View>(R.id.navLogs).setOnClickListener { showTab(1) }

        val cb = findViewById<CheckBox>(R.id.cbLogVerbose)
        cb.isChecked = Store.verboseLog(this)
        cb.setOnCheckedChangeListener { _, on ->
            Store.setVerboseLog(this, on)
            AppLog.i("Logs", "подробный режим ядра: ${if (on) "вкл" else "выкл"}")
            toast(getString(if (on) R.string.logs_verbose_on else R.string.logs_verbose_off))
        }
        findViewById<View>(R.id.logFilterAll).setOnClickListener { setLogFilter(0) }
        findViewById<View>(R.id.logFilterWarn).setOnClickListener { setLogFilter(AppLog.LEVEL_W) }
        findViewById<View>(R.id.logFilterErr).setOnClickListener { setLogFilter(AppLog.LEVEL_E) }
        findViewById<View>(R.id.btnLogCopy).setOnClickListener { copyLogs() }
        findViewById<View>(R.id.btnLogShare).setOnClickListener { shareLogs() }
        findViewById<View>(R.id.btnLogClear).setOnClickListener { confirmClearLogs() }

        logRank = state?.getInt("logRank", 0) ?: 0
        paintLogFilters()
        showTab(state?.getInt("tab", 0) ?: 0, animate = false)
    }

    private fun showTab(i: Int, animate: Boolean = true) {
        tab = i
        val main = findViewById<View>(R.id.tabMain)
        val logs = findViewById<View>(R.id.tabLogs)
        main.visibility = if (i == 0) View.VISIBLE else View.GONE
        logs.visibility = if (i == 1) View.VISIBLE else View.GONE
        paintNav()
        if (i == 1) {
            AppLog.listener = { scheduleLogRender() }
            renderLogs(true)
        } else {
            AppLog.listener = null
        }
        if (animate && Store.animations(this)) {
            val v = if (i == 0) main else logs
            v.alpha = 0f
            v.animate().alpha(1f).setDuration(160).start()
        }
    }

    /** Нижнее меню: активная вкладка цветом акцента, остальные приглушены. */
    private fun paintNav() {
        val pal = Theme.palette(this)
        findViewById<View>(R.id.navDivider).setBackgroundColor(pal.stroke)
        findViewById<View>(R.id.bottomNav).setBackgroundColor(pal.bg)
        val ripple = if (pal.light) 0x22000000 else 0x33FFFFFF
        fun item(box: Int, icon: Int, label: Int, on: Boolean) {
            val c = if (on) pal.accent else pal.textSecondary
            findViewById<ImageView>(icon).setColorFilter(c)
            findViewById<TextView>(label).apply {
                setTextColor(c)
                setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
            }
            findViewById<View>(box).background =
                RippleDrawable(ColorStateList.valueOf(ripple), null, ColorDrawable(Color.WHITE))
        }
        item(R.id.navMain, R.id.navMainIcon, R.id.navMainLabel, tab == 0)
        item(R.id.navLogs, R.id.navLogsIcon, R.id.navLogsLabel, tab == 1)
    }

    private fun setLogFilter(rank: Int) {
        logRank = rank
        paintLogFilters()
        renderLogs(true)
    }

    private fun paintLogFilters() {
        val pal = Theme.palette(this)
        val chips = listOf(R.id.logFilterAll to 0, R.id.logFilterWarn to AppLog.LEVEL_W, R.id.logFilterErr to AppLog.LEVEL_E)
        for ((id, rank) in chips) {
            val v = findViewById<TextView>(id)
            val on = logRank == rank
            v.background = if (on) Theme.accentButton(this, pal) else Theme.button(this, pal)
            v.setTextColor(if (on) pal.onAccent else pal.textPrimary)
        }
    }

    private fun scheduleLogRender() {
        if (logPosted) return
        logPosted = true
        ui.postDelayed(logRender, 400)
    }

    /** Перерисовывает список. Прокручивает вниз, если пользователь и так был внизу (или [toEnd]). */
    private fun renderLogs(toEnd: Boolean) {
        val tv = findViewById<TextView>(R.id.logText)
        // Пока пользователь выделяет текст, не трогаем его, иначе выделение собьётся
        if (!toEnd && tv.hasSelection()) return
        val sc = findViewById<ScrollView>(R.id.logScroll)
        val empty = findViewById<TextView>(R.id.logEmpty)
        val pal = Theme.palette(this)
        val list = AppLog.entries(logRank).takeLast(500)
        empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE

        val child = sc.getChildAt(0)
        val atBottom = toEnd || child == null || child.bottom - (sc.height + sc.scrollY) < dp(48)

        val sb = SpannableStringBuilder()
        for (e in list) {
            val start = sb.length
            sb.append(e.text)
            val color = when (e.level) {
                'E' -> 0xFFFF6B6B.toInt()
                'W' -> pal.warn
                'D' -> pal.textSecondary
                else -> pal.textPrimary
            }
            sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append('\n')
        }
        tv.text = sb
        if (atBottom) sc.post { sc.scrollTo(0, sc.getChildAt(0)?.height ?: 0) }
    }

    private fun copyLogs() {
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("ZeroTune log", AppLog.exportText(this, logRank)))
        toast(getString(R.string.logs_copied))
    }

    private fun shareLogs() {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "ZeroTune log")
            .putExtra(Intent.EXTRA_TEXT, AppLog.exportText(this, logRank))
        try {
            startActivity(Intent.createChooser(send, getString(R.string.logs_share_title)))
        } catch (e: Exception) {
            AppLog.w("Logs", "не удалось открыть «Поделиться»", e)
        }
    }

    private fun confirmClearLogs() {
        AppDialog.show(
            this,
            title = getString(R.string.logs_clear_title),
            message = getString(R.string.logs_clear_msg),
            positive = getString(R.string.logs_clear),
            negative = getString(R.string.dlg_cancel),
            danger = true
        ) {
            AppLog.clear()
            AppLog.i("Logs", "логи очищены")
            renderLogs(true)
            toast(getString(R.string.logs_cleared))
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
