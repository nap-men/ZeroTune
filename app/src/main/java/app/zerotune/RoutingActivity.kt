package app.zerotune

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast

/** Настройки маршрутизации: какие приложения и какие сайты идут через VPN. */
class RoutingActivity : Activity() {

    private lateinit var appGroup: RadioGroup
    private lateinit var siteGroup: RadioGroup
    private lateinit var btnPick: TextView
    private lateinit var cbRu: CheckBox
    private lateinit var etDirect: EditText
    private lateinit var etProxy: EditText
    private lateinit var etBlock: EditText

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Lang.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_routing)
        Theme.apply(this)

        appGroup = findViewById(R.id.appGroup)
        siteGroup = findViewById(R.id.siteGroup)
        btnPick = findViewById(R.id.btnPick)
        cbRu = findViewById(R.id.cbRu)
        etDirect = findViewById(R.id.etDirect)
        etProxy = findViewById(R.id.etProxy)
        etBlock = findViewById(R.id.etBlock)

        val r = Store.routing(this)
        appGroup.check(when (r.appMode) {
            1 -> R.id.rbAppOnly
            2 -> R.id.rbAppExcept
            else -> R.id.rbAppAll
        })
        siteGroup.check(if (r.siteMode == 1) R.id.rbSiteOnly else R.id.rbSiteAll)
        cbRu.isChecked = r.ruDirect
        etDirect.setText(r.direct)
        etProxy.setText(r.proxy)
        etBlock.setText(r.block)

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        btnPick.setOnClickListener { startActivity(Intent(this, AppPickerActivity::class.java)) }
        findViewById<TextView>(R.id.btnSaveRouting).setOnClickListener { save() }
    }

    override fun onResume() {
        super.onResume()
        // список приложений мог измениться на экране выбора
        val n = Store.apps(this).size
        btnPick.text = if (n == 0) getString(R.string.pick_apps) else getString(R.string.pick_apps_n, n)
    }

    private fun save() {
        val appMode = when (appGroup.checkedRadioButtonId) {
            R.id.rbAppOnly -> 1
            R.id.rbAppExcept -> 2
            else -> 0
        }
        val siteMode = if (siteGroup.checkedRadioButtonId == R.id.rbSiteOnly) 1 else 0

        if (appMode != 0 && Store.apps(this).isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_pick_first), Toast.LENGTH_SHORT).show()
            return
        }

        Store.saveRouting(this, Routing(
            appMode = appMode,
            apps = Store.apps(this),
            siteMode = siteMode,
            direct = etDirect.text.toString(),
            proxy = etProxy.text.toString(),
            block = etBlock.text.toString(),
            ruDirect = cbRu.isChecked
        ))

        // Если туннель запущен — перезапускаем его, чтобы применились новые правила
        if (TunnelVpnService.isUp(this)) {
            TunnelVpnService.start(this)
            Toast.makeText(this, getString(R.string.saved_restart), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, getString(R.string.saved), Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
