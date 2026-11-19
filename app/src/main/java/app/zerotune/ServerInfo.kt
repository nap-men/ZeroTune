package app.zerotune

import android.content.Context
import android.net.Uri

/** Разбор ключа для показа в списке: название, адрес и теги. */
object ServerInfo {

    data class Info(
        val name: String,
        val address: String,
        val tags: List<String>,
        val host: String,
        val port: Int
    )

    // Берём ссылку до конца строки: в названии (после #) могут быть пробелы
    private val linkRe = Regex("""vless://[^\r\n]+""")

    /** Достаёт из произвольного текста все vless:// ключи либо один JSON-конфиг. */
    fun extract(text: String): List<String> {
        val t = text.trim()
        if (t.startsWith("{")) return listOf(t)
        return linkRe.findAll(t)
            .map { it.value.trim().trimEnd(',', ';', '"', '\'') }
            .toList()
    }

    fun parse(link: String, jsonName: String = "JSON config"): Info? {
        val s = link.trim()
        if (s.startsWith("{")) return Info(jsonName, "JSON", listOf("JSON"), "", 0)
        if (!s.startsWith("vless://")) return null
        return try {
            val u = Uri.parse(s)
            val host = u.host ?: return null
            val port = if (u.port > 0) u.port else 443
            val name = u.fragment?.trim()?.takeIf { it.isNotEmpty() } ?: host

            val tags = mutableListOf("VLESS")
            val sec = u.getQueryParameter("security") ?: "none"
            if (sec != "none") tags += sec.uppercase()
            tags += (u.getQueryParameter("type") ?: "tcp").uppercase()
            if (u.getQueryParameter("flow")?.contains("vision") == true) tags += "Vision"

            Info(name, "$host:$port", tags, host, port)
        } catch (e: Exception) {
            null
        }
    }
}

/** Добавление серверов из текста (буфер обмена или ручной ввод). Понимает несколько ключей сразу. */
object Importer {

    data class Result(val added: Int, val duplicates: Int, val invalid: Int, val firstAdded: Profile?)

    fun import(c: Context, text: String): Result {
        var added = 0
        var dup = 0
        var bad = 0
        var first: Profile? = null

        for (key in ServerInfo.extract(text)) {
            val info = ServerInfo.parse(key, c.tr(R.string.json_config_name))
            val valid = info != null && try {
                ConfigBuilder.build(key) // проверка, что ключ собирается в конфиг
                true
            } catch (e: Exception) {
                false
            }
            if (!valid || info == null) { bad++; continue }

            val prof = Store.addProfile(c, info.name, key)
            if (prof == null) {
                dup++
            } else {
                added++
                if (first == null) first = prof
            }
        }
        return Result(added, dup, bad, first)
    }
}
