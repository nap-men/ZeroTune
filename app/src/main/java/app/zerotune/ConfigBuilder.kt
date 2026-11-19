package app.zerotune

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * Превращает vless://... в JSON-конфиг Xray вместе с правилами маршрутизации.
 * Если на входе уже JSON — возвращает как есть (маршрутизация из приложения тогда не применяется).
 * Поддержано: tcp / ws / grpc, security none / tls / reality, flow xtls-rprx-vision.
 */
object ConfigBuilder {

    private val RU_PRESET = listOf(
        "domain:ru", "domain:su", "domain:xn--p1ai",
        "domain:yandex.net", "domain:yandex.com", "domain:yastatic.net",
        "domain:vk.com", "domain:vk.me", "domain:userapi.com", "domain:vkuser.net",
        "domain:mycdn.me"
    )

    private val PRIVATE_IPS = listOf(
        "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16", "127.0.0.0/8"
    )

    private val ipv4Re = Regex("""^\d{1,3}(\.\d{1,3}){3}(/\d{1,2})?$""")
    private val ipv6Re = Regex("""^[0-9a-fA-F:.]+(/\d{1,3})?$""")
    private val prefixes = listOf("domain:", "full:", "regexp:", "keyword:", "geosite:", "geoip:")

    /** Локальный SOCKS-порт, на который hev-socks5-tunnel отдаёт трафик из VPN. */
    const val SOCKS_PORT = 10808

    /**
     * @param hev true — Xray слушает локальный SOCKS, а пакеты из VPN в него переправляет
     * нативный hev-socks5-tunnel (быстрее); false — встроенный TUN-вход Xray (gVisor).
     */
    fun build(input: String, routing: Routing = Routing(), mtu: Int = 1500, hev: Boolean = false,
              logPath: String? = null, verbose: Boolean = false): String {
        val s = input.trim()
        if (s.startsWith("{")) return s
        require(s.startsWith("vless://")) { "Only a vless:// link or a ready JSON config is supported" }
        return buildVless(s, routing, mtu, hev, logPath, verbose)
    }

    /** IPv6 или подсеть: минимум два двоеточия и только hex-символы. Так `example.com:8080` остаётся доменом. */
    private fun isIpv6(raw: String): Boolean =
        raw.count { it == ':' } >= 2 && ipv6Re.matches(raw) && prefixes.none { raw.startsWith(it) }

    /** Разбирает список из строк: домены и IP/CIDR. */
    private fun parseList(text: String): Pair<List<String>, List<String>> {
        val domains = mutableListOf<String>()
        val ips = mutableListOf<String>()
        for (line in text.lines()) {
            val raw = line.trim()
            if (raw.isEmpty() || raw.startsWith("#")) continue
            if (ipv4Re.matches(raw) || isIpv6(raw)) {
                ips += raw
                continue
            }
            if (prefixes.any { raw.startsWith(it) }) {
                domains += raw
                continue
            }
            var d = raw.lowercase()
                .removePrefix("https://").removePrefix("http://")
                .substringBefore("/").substringBefore(":")
                .removePrefix("*.").removePrefix(".")
            if (d.isNotEmpty()) domains += "domain:$d"
        }
        return domains to ips
    }

    private fun rules(tag: String, domains: List<String>, ips: List<String>): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        if (domains.isNotEmpty()) out += JSONObject()
            .put("type", "field").put("domain", JSONArray(domains)).put("outboundTag", tag)
        if (ips.isNotEmpty()) out += JSONObject()
            .put("type", "field").put("ip", JSONArray(ips)).put("outboundTag", tag)
        return out
    }

    /**
     * Журнал ядра. Обычный режим: только предупреждения и ошибки в файл, журнал доступа выключен.
     * Подробный: ещё и журнал доступа (какие адреса открывались), поэтому включается только вручную.
     */
    private fun buildLog(path: String?, verbose: Boolean): JSONObject {
        val log = JSONObject().put("loglevel", if (verbose) "info" else "warning")
        if (path != null) {
            log.put("error", path)
            log.put("access", if (verbose) path else "none")
        }
        return log
    }

    private fun buildRouting(r: Routing): JSONObject {
        val (blockD, blockI) = parseList(r.block)
        val (directD, directI) = parseList(r.direct)
        val (proxyD, proxyI) = parseList(r.proxy)

        val list = JSONArray()
        // 0. QUIC (UDP 443) блокируем: браузеры сами переходят на обычный TCP,
        //    а через прокси QUIC часто даёт «полузагруженные» сайты
        list.put(JSONObject().put("type", "field")
            .put("network", "udp").put("port", "443").put("outboundTag", "block"))
        // 1. блокировка — самый высокий приоритет
        rules("block", blockD, blockI).forEach { list.put(it) }
        // 2. локальные сети всегда напрямую
        list.put(JSONObject().put("type", "field")
            .put("ip", JSONArray(PRIVATE_IPS)).put("outboundTag", "direct"))
        // 3. пользовательские списки
        rules("direct", directD, directI).forEach { list.put(it) }
        rules("proxy", proxyD, proxyI).forEach { list.put(it) }
        // 4. российский пресет (самый низкий приоритет среди правил, поэтому его можно переопределить)
        if (r.ruDirect && r.siteMode == 0) {
            rules("direct", RU_PRESET, emptyList()).forEach { list.put(it) }
        }
        // 5. всё остальное
        val final = if (r.siteMode == 1) "direct" else "proxy"
        list.put(JSONObject().put("type", "field")
            .put("network", "tcp,udp").put("outboundTag", final))

        return JSONObject().put("domainStrategy", "AsIs").put("rules", list)
    }

    private fun buildVless(link: String, routing: Routing, mtu: Int, hev: Boolean, logPath: String?, verbose: Boolean): String {
        val u = Uri.parse(link)
        val id = u.userInfo ?: error("no UUID")
        val host = u.host ?: error("no address")
        val port = if (u.port > 0) u.port else 443
        fun q(k: String) = u.getQueryParameter(k)

        val net = q("type") ?: "tcp"
        val security = q("security") ?: "none"

        val user = JSONObject()
            .put("id", id)
            .put("encryption", q("encryption") ?: "none")
            .apply { q("flow")?.takeIf { it.isNotEmpty() }?.let { put("flow", it) } }

        val stream = JSONObject().put("network", net).put("security", security)

        when (security) {
            "reality" -> stream.put("realitySettings", JSONObject()
                .put("serverName", q("sni") ?: host)
                .put("fingerprint", q("fp") ?: "chrome")
                .put("publicKey", q("pbk") ?: "")
                .put("shortId", q("sid") ?: "")
                .put("spiderX", q("spx") ?: ""))
            "tls" -> stream.put("tlsSettings", JSONObject()
                .put("serverName", q("sni") ?: host)
                .put("fingerprint", q("fp") ?: "chrome")
                .apply { q("alpn")?.let { put("alpn", JSONArray(it.split(","))) } })
        }

        when (net) {
            "ws" -> stream.put("wsSettings", JSONObject()
                .put("path", q("path") ?: "/")
                .put("headers", JSONObject().put("Host", q("host") ?: host)))
            "grpc" -> stream.put("grpcSettings", JSONObject()
                .put("serviceName", q("serviceName") ?: ""))
        }

        val proxy = JSONObject()
            .put("tag", "proxy")
            .put("protocol", "vless")
            .put("settings", JSONObject().put("vnext", JSONArray().put(JSONObject()
                .put("address", host).put("port", port)
                .put("users", JSONArray().put(user)))))
            .put("streamSettings", stream)
            // mux выключен намеренно: с Reality + vision он только режет скорость
            .put("mux", JSONObject().put("enabled", false))

        val direct = JSONObject().put("tag", "direct").put("protocol", "freedom")
        val block = JSONObject().put("tag", "block").put("protocol", "blackhole")

        // Sniffing нужен, чтобы правила по доменам работали для трафика из туннеля.
        val sniffing = JSONObject()
            .put("enabled", true)
            .put("destOverride", JSONArray().put("http").put("tls").put("quic"))
        val inbound = if (hev) {
            JSONObject()
                .put("tag", "socks-in")
                .put("listen", "127.0.0.1")
                .put("port", SOCKS_PORT)
                .put("protocol", "socks")
                .put("settings", JSONObject().put("auth", "noauth").put("udp", true).put("userLevel", 8))
                .put("sniffing", sniffing)
        } else {
            JSONObject()
                .put("tag", "tun-in")
                .put("protocol", "tun")
                .put("settings", JSONObject().put("name", "xray0").put("MTU", mtu))
                .put("sniffing", sniffing)
        }

        val dns = JSONObject().put("servers", JSONArray().put("1.1.1.1").put("8.8.8.8"))

        return JSONObject()
            .put("log", buildLog(logPath, verbose))
            .put("dns", dns)
            .put("inbounds", JSONArray().put(inbound))
            // proxy первым — он же исходящий по умолчанию
            .put("outbounds", JSONArray().put(proxy).put(direct).put(block))
            .put("routing", buildRouting(routing))
            .toString()
    }
}
