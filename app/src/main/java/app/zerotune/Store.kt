package app.zerotune

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Сохранённый сервер: название для списка и сам ключ (vless:// или JSON). */
data class Profile(val id: String, val name: String, val link: String)

/** Настройки маршрутизации. */
data class Routing(
    /** 0 — все приложения через VPN, 1 — только выбранные, 2 — все, кроме выбранных */
    val appMode: Int = 0,
    val apps: Set<String> = emptySet(),
    /** 0 — всё через VPN, кроме списка «напрямую»; 1 — через VPN только сайты из списка «через VPN» */
    val siteMode: Int = 0,
    val direct: String = "",
    val proxy: String = "",
    val block: String = "",
    /** Российские домены (.ru, .su, .рф и крупные сервисы) идут напрямую */
    val ruDirect: Boolean = false
)

/** Простое хранилище: ключ, флаги состояния и маршрутизация. */
object Store {
    private fun p(c: Context) = c.getSharedPreferences("zerotune", Context.MODE_PRIVATE)

    // ---- серверы (профили) ----

    fun newId(): String = java.util.UUID.randomUUID().toString()

    @Synchronized
    fun profiles(c: Context): List<Profile> {
        val s = p(c)
        val raw = s.getString("profiles", null)
        if (raw == null) {
            // Миграция: в версиях до 1.1.0 хранился один ключ
            val old = s.getString("link", "") ?: ""
            if (old.isNotBlank()) {
                val prof = Profile(newId(), ServerInfo.parse(old)?.name ?: "Server", old.trim())
                saveProfiles(c, listOf(prof))
                setActiveId(c, prof.id)
                return listOf(prof)
            }
            return emptyList()
        }
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Profile(o.getString("id"), o.getString("name"), o.getString("link"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun saveProfiles(c: Context, list: List<Profile>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("link", it.link)) }
        p(c).edit().putString("profiles", arr.toString()).apply()
    }

    fun activeId(c: Context): String = p(c).getString("active_id", "") ?: ""
    fun setActiveId(c: Context, id: String) = p(c).edit().putString("active_id", id).apply()

    /** Выбранный сервер; если выбранного нет (удалён) — первый в списке. */
    fun activeProfile(c: Context): Profile? {
        val list = profiles(c)
        return list.firstOrNull { it.id == activeId(c) } ?: list.firstOrNull()
    }

    /** Ключ выбранного сервера. Им пользуются сервис и плитка. */
    fun getLink(c: Context): String = activeProfile(c)?.link ?: ""

    /** Добавляет сервер, если такого ключа ещё нет. Возвращает null, если это повтор. */
    @Synchronized
    fun addProfile(c: Context, name: String, link: String): Profile? {
        val list = profiles(c)
        val clean = link.trim()
        if (list.any { it.link == clean }) return null
        val prof = Profile(newId(), name, clean)
        saveProfiles(c, list + prof)
        if (list.isEmpty()) setActiveId(c, prof.id)
        return prof
    }

    @Synchronized
    fun removeProfile(c: Context, id: String) {
        val wasActive = activeProfile(c)?.id == id
        val rest = profiles(c).filter { it.id != id }
        saveProfiles(c, rest)
        if (wasActive) setActiveId(c, rest.firstOrNull()?.id ?: "")
    }

    fun isRunning(c: Context): Boolean = p(c).getBoolean("running", false)
    fun setRunning(c: Context, v: Boolean) = p(c).edit().putBoolean("running", v).apply()

    /** Пользователь сам нажал "стоп" — тогда watchdog не должен поднимать туннель обратно. */
    fun wantOn(c: Context): Boolean = p(c).getBoolean("want_on", false)
    fun setWantOn(c: Context, v: Boolean) = p(c).edit().putBoolean("want_on", v).apply()

    /** Последняя ошибка запуска туннеля (для диагностики). Пустая строка — ошибок не было. */
    fun lastError(c: Context): String = p(c).getString("last_error", "") ?: ""
    fun setLastError(c: Context, v: String) = p(c).edit().putString("last_error", v).apply()

    /** MTU туннеля. Минимум 1280 (требование IPv6), по умолчанию 1500. */
    fun mtu(c: Context): Int = p(c).getInt("mtu", 1500).coerceIn(1280, 1500)
    fun setMtu(c: Context, v: Int) = p(c).edit().putInt("mtu", v.coerceIn(1280, 1500)).apply()

    /** Быстрый туннель hev-socks5-tunnel (если библиотека есть в приложении). */
    fun useHev(c: Context): Boolean = p(c).getBoolean("use_hev", true)
    fun setUseHev(c: Context, v: Boolean) = p(c).edit().putBoolean("use_hev", v).apply()

    // ---- язык ----

    /** "ru", "en" или пустая строка (язык ещё не выбран — берём язык системы). */
    fun lang(c: Context): String = p(c).getString("lang", "") ?: ""
    fun setLang(c: Context, v: String) = p(c).edit().putString("lang", v).apply()

    // ---- оформление ----

    private val DEF_BG = 0xFF0E1318.toInt()
    private val DEF_ACCENT = 0xFF14C58B.toInt()

    fun themeBg(c: Context): Int = p(c).getInt("th_bg", DEF_BG)
    fun themeAccent(c: Context): Int = p(c).getInt("th_accent", DEF_ACCENT)
    fun themePower(c: Context): Int = p(c).getInt("th_power", DEF_ACCENT)
    fun themeSelect(c: Context): Int = p(c).getInt("th_select", DEF_ACCENT)
    fun setThemeBg(c: Context, v: Int) = p(c).edit().putInt("th_bg", v).apply()
    fun setThemeAccent(c: Context, v: Int) = p(c).edit().putInt("th_accent", v).apply()
    fun setThemePower(c: Context, v: Int) = p(c).edit().putInt("th_power", v).apply()
    fun setThemeSelect(c: Context, v: Int) = p(c).edit().putInt("th_select", v).apply()

    fun setThemeAll(c: Context, bg: Int, accent: Int, power: Int, select: Int) {
        p(c).edit().putInt("th_bg", bg).putInt("th_accent", accent)
            .putInt("th_power", power).putInt("th_select", select).apply()
    }

    fun resetTheme(c: Context) {
        p(c).edit().remove("th_bg").remove("th_accent").remove("th_power").remove("th_select").apply()
    }

    /** Свои цвета пользователя для выбора цвета (новые сверху). */
    fun customColors(c: Context): List<Int> =
        (p(c).getString("th_custom", "") ?: "").split(",").mapNotNull { it.trim().toLongOrNull()?.toInt() }

    private fun saveCustomColors(c: Context, l: List<Int>) {
        p(c).edit().putString("th_custom", l.joinToString(",") { (it.toLong() and 0xFFFFFFFFL).toString() }).apply()
    }

    /** false, если такой цвет уже есть. */
    fun addCustomColor(c: Context, v: Int): Boolean {
        val l = customColors(c)
        if (l.any { (it and 0xFFFFFF) == (v and 0xFFFFFF) }) return false
        saveCustomColors(c, (listOf(v or 0xFF000000.toInt()) + l).take(40))
        return true
    }

    fun removeCustomColor(c: Context, v: Int) {
        saveCustomColors(c, customColors(c).filter { (it and 0xFFFFFF) != (v and 0xFFFFFF) })
    }

    /** Анимации кнопки включения. */
    fun animations(c: Context): Boolean = p(c).getBoolean("th_anim", true)
    fun setAnimations(c: Context, v: Boolean) = p(c).edit().putBoolean("th_anim", v).apply()

    // ---- уведомление ----

    /** true — обычное уведомление; false — тихое (минимальная важность, без текста). */
    fun notifEnabled(c: Context): Boolean = p(c).getBoolean("notif_enabled", true)
    fun setNotifEnabled(c: Context, v: Boolean) = p(c).edit().putBoolean("notif_enabled", v).apply()

    /** Показывать ли скорость и пинг в уведомлении. */
    fun notifStats(c: Context): Boolean = p(c).getBoolean("notif_stats", true)
    fun setNotifStats(c: Context, v: Boolean) = p(c).edit().putBoolean("notif_stats", v).apply()

    // ---- маршрутизация ----

    fun apps(c: Context): Set<String> =
        (p(c).getStringSet("r_apps", emptySet()) ?: emptySet()).toSet()

    fun setApps(c: Context, v: Set<String>) =
        p(c).edit().putStringSet("r_apps", HashSet(v)).apply()

    fun routing(c: Context): Routing {
        val s = p(c)
        return Routing(
            appMode = s.getInt("r_app_mode", 0),
            apps = apps(c),
            siteMode = s.getInt("r_site_mode", 0),
            direct = s.getString("r_direct", "") ?: "",
            proxy = s.getString("r_proxy", "") ?: "",
            block = s.getString("r_block", "") ?: "",
            ruDirect = s.getBoolean("r_ru", false)
        )
    }

    fun saveRouting(c: Context, r: Routing) {
        p(c).edit()
            .putInt("r_app_mode", r.appMode)
            .putInt("r_site_mode", r.siteMode)
            .putString("r_direct", r.direct)
            .putString("r_proxy", r.proxy)
            .putString("r_block", r.block)
            .putBoolean("r_ru", r.ruDirect)
            .apply()
    }
}
