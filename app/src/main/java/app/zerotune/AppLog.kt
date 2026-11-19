package app.zerotune

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Журнал событий приложения и ядра. Живёт в памяти (последние [MAX_LINES] строк) и дублируется в файл,
 * чтобы после убийства процесса системой можно было посмотреть, что происходило.
 *
 * Что здесь НЕ пишется: ссылки-ключи, UUID и адреса серверов. А при копировании и отправке всё равно
 * дополнительно скрываются ключи, UUID, публичные IP и адрес активного сервера ([redact]).
 */
object AppLog {

    private const val MAX_LINES = 1000
    private const val FILE_MAX = 256 * 1024
    private const val FILE_NAME = "zerotune.log"

    const val LEVEL_D = 0
    const val LEVEL_I = 1
    const val LEVEL_W = 2
    const val LEVEL_E = 3

    class Entry(val level: Char, val text: String) {
        val rank: Int get() = when (level) { 'E' -> LEVEL_E; 'W' -> LEVEL_W; 'I' -> LEVEL_I; else -> LEVEL_D }
    }

    private val lock = Any()
    private val lines = ArrayDeque<Entry>()
    private val secrets = LinkedHashSet<String>()
    private var file: File? = null
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "zerotune-log").apply { isDaemon = true } }

    private val fmt = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }

    /** Подписка экрана логов: вызывается из любого потока при новой записи. */
    @Volatile var listener: (() -> Unit)? = null

    /** Подключает файл журнала и подтягивает записи прошлого запуска. Можно вызывать сколько угодно раз. */
    fun init(c: Context) {
        synchronized(lock) {
            if (file != null) return
            val f = File(c.applicationContext.filesDir, FILE_NAME)
            file = f
            val old = ArrayList<Entry>()
            try {
                if (f.exists()) {
                    for (l in f.readLines()) {
                        if (l.length > 14) old += Entry(l[13], l)
                    }
                }
            } catch (_: Throwable) { /* нет файла или он повреждён — начинаем с чистого листа */ }
            val tail = if (old.size > MAX_LINES) old.subList(old.size - MAX_LINES, old.size) else old
            lines.addAll(0, tail)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        i("App", "—— ZeroTune ${AppInfo.versionName(c)} · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL} ——")
    }

    fun d(tag: String, msg: String) = add('D', tag, msg, null, true)
    fun i(tag: String, msg: String) = add('I', tag, msg, null, true)
    fun w(tag: String, msg: String, t: Throwable? = null) = add('W', tag, msg, t, true)
    fun e(tag: String, msg: String, t: Throwable? = null) = add('E', tag, msg, t, true)

    /** Строка из журнала ядра Xray: уровень берём из «[Warning]» и т.п. В файл не пишем (там могут быть адреса сайтов). */
    fun core(raw: String) {
        val m = coreRe.matchEntire(raw.trim()) ?: run {
            if (raw.isNotBlank()) add('I', "xray", raw.trim(), null, false)
            return
        }
        val level = when (m.groupValues[1].lowercase()) {
            "error" -> 'E'
            "warning" -> 'W'
            "info" -> 'I'
            else -> 'D'
        }
        add(level, "xray", m.groupValues[2], null, false)
    }

    private val coreRe = Regex("""^(?:\d{4}/\d{2}/\d{2} \d{2}:\d{2}:\d{2}(?:\.\d+)? )?\[(\w+)] (.*)$""")

    private fun add(level: Char, tag: String, msg: String, t: Throwable?, persist: Boolean) {
        val body = if (t == null) msg else "$msg: ${describe(t)}"
        val text = "${fmt.get()!!.format(Date())} $level/$tag: $body"
        try {
            when (level) {
                'E' -> Log.e("ZeroTune", "$tag: $body")
                'W' -> Log.w("ZeroTune", "$tag: $body")
                'I' -> Log.i("ZeroTune", "$tag: $body")
                else -> Log.d("ZeroTune", "$tag: $body")
            }
        } catch (_: Throwable) { /* в юнит-тестах Log недоступен */ }
        synchronized(lock) {
            lines.addLast(Entry(level, text))
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        if (persist) writeToFile(text)
        listener?.invoke()
    }

    private fun describe(t: Throwable): String {
        val root = generateSequence(t) { it.cause }.last()
        return if (root === t) t.toString() else "$t ← $root"
    }

    private fun writeToFile(text: String) {
        val f = file ?: return
        try {
            io.execute {
                try {
                    if (f.length() > FILE_MAX) {
                        val keep = f.readLines().takeLast(MAX_LINES / 2)
                        f.writeText(keep.joinToString("\n") + "\n")
                    }
                    f.appendText(text + "\n")
                } catch (_: Throwable) { /* журнал не должен ронять приложение */ }
            }
        } catch (_: RejectedExecutionException) {}
    }

    /** Записи не ниже [minRank] (0 — все), самые свежие последними. */
    fun entries(minRank: Int = 0): List<Entry> = synchronized(lock) { lines.filter { it.rank >= minRank } }

    fun clear() {
        synchronized(lock) { lines.clear() }
        val f = file
        try { io.execute { try { f?.writeText("") } catch (_: Throwable) {} } } catch (_: RejectedExecutionException) {}
        listener?.invoke()
    }

    // ---- скрытие личных данных ----

    /** Запоминает строку (адрес сервера и т.п.), которую нужно прятать при копировании и отправке логов. */
    fun addSecret(s: String?) {
        val v = s?.trim().orEmpty()
        if (v.length < 4) return
        synchronized(lock) { secrets += v }
    }

    private val linkRe = Regex("""(vless|vmess|trojan|ss)://[^\s"']+""", RegexOption.IGNORE_CASE)
    private val uuidRe = Regex("""\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b""")
    private val ipv4Re = Regex("""\b(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})\b""")
    private val ipv6Re = Regex("""\b[0-9a-fA-F]{1,4}(?::[0-9a-fA-F]{0,4}){3,7}\b""")

    /** Убирает из текста всё, что нельзя показывать посторонним. Публичные IP оставляют только первый октет. */
    fun redact(s: String): String {
        var t = s
        for (secret in synchronized(lock) { secrets.toList() }) t = t.replace(secret, "<server>")
        t = linkRe.replace(t) { "${it.groupValues[1]}://***" }
        t = uuidRe.replace(t, "<uuid>")
        t = ipv4Re.replace(t) { m ->
            val a = m.groupValues[1].toIntOrNull() ?: return@replace m.value
            val b = m.groupValues[2].toIntOrNull() ?: return@replace m.value
            val local = a == 10 || a == 127 || a == 0 || (a == 192 && b == 168) || (a == 172 && b in 16..31) || (a == 169 && b == 254)
            if (local) m.value else "$a.x.x.x"
        }
        t = ipv6Re.replace(t) { m -> if (m.value.count { it == ':' } >= 3) "<ipv6>" else m.value }
        return t
    }

    /** Готовый текст для копирования и отправки: заголовок + записи, всё с скрытыми личными данными. */
    fun exportText(c: Context, minRank: Int = 0): String {
        val list = entries(minRank).takeLast(800)
        val head = "ZeroTune ${AppInfo.versionName(c)} · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}\n" +
            "Lines: ${list.size}\n\n"
        return redact(head + list.joinToString("\n") { it.text })
    }
}

/**
 * Читает файл журнала ядра Xray по мере появления новых строк и отдаёт их в [AppLog.core].
 * Работает в фоновом потоке, останавливается через [stop].
 */
class CoreLogTail(private val file: File) {

    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({
            var pos = 0L
            val carry = StringBuilder()
            while (running) {
                try {
                    if (file.exists() && file.length() > pos) {
                        RandomAccessFile(file, "r").use { raf ->
                            raf.seek(pos)
                            val buf = ByteArray((file.length() - pos).coerceAtMost(64 * 1024).toInt())
                            val n = raf.read(buf)
                            if (n > 0) {
                                pos += n
                                carry.append(String(buf, 0, n, Charsets.UTF_8))
                            }
                        }
                        var nl = carry.indexOf("\n")
                        while (nl >= 0) {
                            AppLog.core(carry.substring(0, nl))
                            carry.delete(0, nl + 1)
                            nl = carry.indexOf("\n")
                        }
                    } else if (file.exists() && file.length() < pos) {
                        pos = 0 // файл пересоздали
                    }
                } catch (_: Throwable) { /* читаем дальше */ }
                try { Thread.sleep(500) } catch (_: InterruptedException) { break }
            }
        }, "zerotune-core-log").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }
}
