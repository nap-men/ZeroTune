package app.zerotune

import android.app.Activity
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Помощники для Huawei/Honor (EMUI, HarmonyOS).
 *
 * У Huawei нет публичного API для экранов «Запуск приложений» и «Автозапуск», а их внутренние
 * адреса меняются от версии к версии. Поэтому: пробуем несколько известных адресов, и если
 * ни один не открылся — показываем инструкцию и открываем экран «Батарея».
 */
object HuaweiHelper {

    private class Candidate(val label: String, val intent: Intent)

    private fun comp(pkg: String, cls: String) =
        Intent().setComponent(ComponentName(pkg, cls)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Известные адреса экранов. Часть из них на новых версиях системы не открывается. */
    private fun candidates(c: Context): List<Candidate> = listOf(
        // action для «Запуска приложений» на EMUI 10+ (не везде работает)
        Candidate(c.tr(R.string.cand_action),
            Intent("huawei.intent.action.HSM_BOOTAPP_MANAGER").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)),
        Candidate(c.tr(R.string.cand_autostart),
            comp("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")),
        Candidate(c.tr(R.string.cand_startup_ctl),
            comp("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity")),
        Candidate(c.tr(R.string.cand_protected),
            comp("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")),
        Candidate(c.tr(R.string.cand_power),
            comp("com.huawei.systemmanager", "com.huawei.systemmanager.power.ui.HwPowerManagerActivity")),
        // Honor после разделения с Huawei; названия предположительные
        Candidate(c.tr(R.string.cand_honor_auto),
            comp("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity")),
        Candidate(c.tr(R.string.cand_honor_power),
            comp("com.hihonor.systemmanager", "com.hihonor.systemmanager.power.ui.HwPowerManagerActivity"))
    )

    fun isHuawei(): Boolean {
        val m = Build.MANUFACTURER.lowercase()
        val b = Build.BRAND.lowercase()
        return m.contains("huawei") || m.contains("honor") || b.contains("huawei") || b.contains("honor")
    }

    fun isIgnoringBatteryOptimizations(c: Context): Boolean =
        c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName)

    /** Системный диалог "не ограничивать батарею". */
    fun requestIgnoreBattery(c: Context) {
        val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${c.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { c.startActivity(i) } catch (_: Exception) { openBatterySettings(c) }
    }

    /** Пробует открыть экран запуска/автозапуска. Если не вышло — показывает инструкцию. */
    fun openAutostart(a: Activity) {
        for (cand in candidates(a)) {
            try {
                a.startActivity(cand.intent)
                return
            } catch (_: Exception) {
                // Нет такого экрана или он закрыт для сторонних приложений — пробуем следующий
            }
        }
        showManualInstructions(a)
    }

    private fun showManualInstructions(a: Activity) {
        AppDialog.show(
            a,
            title = a.tr(R.string.launch_manager),
            message = a.tr(R.string.manual_msg),
            positive = a.tr(R.string.manual_open_apps),
            negative = a.tr(R.string.dlg_close)
        ) { openAppsSettings(a) }
    }

    /** Экран «Приложения»: на HarmonyOS отсюда доступен «Диспетчер запуска». */
    fun openAppsSettings(c: Context) {
        val tries = listOf(
            Intent(Settings.ACTION_APPLICATION_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (i in tries) {
            try {
                c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) { /* следующий */ }
        }
    }

    /** Экран «Батарея» (на старых EMUI из него доступен пункт «Запуск приложений»). */
    fun openBatterySettings(c: Context) {
        val tries = listOf(
            Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (i in tries) {
            try {
                c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) { /* следующий */ }
        }
    }

    // ---- диагностика ----

    private fun prop(key: String): String = try {
        val p = Runtime.getRuntime().exec(arrayOf("getprop", key))
        p.inputStream.bufferedReader().readText().trim().ifEmpty { "—" }
    } catch (_: Exception) { "n/a" }

    /** Окно с параметрами устройства: его можно сфотографировать и прислать разработчику. */
    fun showDiagnostics(a: Activity) {
        val nm = a.getSystemService(NotificationManager::class.java)
        val pm = a.packageManager
        val sb = StringBuilder()
        fun yn(v: Boolean) = a.tr(if (v) R.string.yes else R.string.no)
        sb.appendLine(a.tr(R.string.diag_app, AppInfo.versionName(a), Store.profiles(a).size))
        sb.appendLine(a.tr(R.string.diag_device, Build.MANUFACTURER, Build.MODEL, Build.BRAND))
        sb.appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("EMUI: ${prop("ro.build.version.emui")}")
        sb.appendLine("HarmonyOS: ${prop("hw_sc.build.platform.version")}")
        sb.appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
        sb.appendLine()
        sb.appendLine(a.tr(R.string.diag_state,
            yn(TunnelVpnService.instance != null), yn(Store.wantOn(a)),
            yn(TunnelVpnService.isUp(a)), yn(TunnelVpnService.isConnecting(a))))
        sb.appendLine(a.tr(R.string.diag_battery, yn(isIgnoringBatteryOptimizations(a))))
        sb.appendLine(a.tr(R.string.diag_notif, yn(nm.areNotificationsEnabled())))
        val hevOk = Engine.hevAvailable()
        sb.appendLine(a.tr(R.string.diag_hev, a.tr(if (hevOk) R.string.hev_loaded else R.string.hev_not_loaded)))
        if (!hevOk) sb.appendLine(a.tr(R.string.diag_reason, Engine.hevError(a)))
        sb.appendLine(a.tr(R.string.diag_apk, Engine.packagedHev(a)))
        sb.appendLine(a.tr(R.string.diag_hev_on, yn(Store.useHev(a))))
        val err = Store.lastError(a)
        if (err.isNotEmpty()) sb.appendLine(a.tr(R.string.diag_last_error, err))
        sb.appendLine()
        sb.appendLine(a.tr(R.string.diag_screens))
        for (cand in candidates(a)) {
            val ok = cand.intent.resolveActivity(pm) != null
            sb.appendLine("${if (ok) "✓" else "✗"} ${cand.label}")
        }
        AppDialog.show(
            a,
            title = a.tr(R.string.diag_title),
            message = sb.toString(),
            positive = a.tr(R.string.dlg_close),
            monospace = true
        )
    }

    fun openAppSettings(c: Context) {
        c.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${c.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
