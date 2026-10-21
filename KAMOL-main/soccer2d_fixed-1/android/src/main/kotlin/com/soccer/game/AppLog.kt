package com.soccer.game

import android.content.Context
import android.content.Intent
import android.os.Build
import soccer.shared.C
import soccer.shared.SLog
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Xatolar va muhim hodisalar logi: `log.txt` (Android/data/com.soccer.game/files/).
 * Yozuv fon oqimida (o'yinni sekinlashtirmaydi), 256 KB dan oshsa log.old.txt ga o'tadi,
 * ishlamay qolish (crash) ham yoziladi. Takrorlanuvchi xabarlar yig'ib yoziladi.
 */
object AppLog {
    private const val MAX_BYTES = 256 * 1024L
    private val lock = Any()
    private var file: File? = null
    private val exec = Executors.newSingleThreadExecutor { Thread(it, "applog").apply { isDaemon = true } }
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)     // faqat exec oqimida ishlatiladi

    @Volatile var errorCount = 0           // shu sessiyadagi xatolar soni
        private set

    // takrorlanishni bostirish (faqat exec oqimida)
    private var lastKey = ""
    private var lastAt = 0L
    private var suppressed = 0

    fun init(ctx: Context) {
        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        file = File(dir, "log.txt")
        SLog.sink = { level, tag, msg, t -> write(level, tag, msg, t) }

        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { th, ex ->
            errorCount++
            try { append(format(System.currentTimeMillis(), 'F', "Crash", th.name, "Uncaught exception", android.util.Log.getStackTraceString(ex))) } catch (_: Throwable) {}
            prev?.uncaughtException(th, ex)
        }

        val pi = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0) } catch (_: Exception) { null }
        val code = pi?.let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong() } ?: 0L
        write('I', "App", "==== session start: 2v2 Football ${pi?.versionName ?: "?"} (build $code) | " +
            "${Build.MANUFACTURER} ${Build.MODEL} | Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) | protocol ${C.PROTOCOL} ====", null)
    }

    fun i(tag: String, msg: String) = write('I', tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = write('W', tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = write('E', tag, msg, t)

    fun write(level: Char, tag: String, msg: String, t: Throwable?) {
        val now = System.currentTimeMillis()
        val th = Thread.currentThread().name
        if (level == 'E' || level == 'F') errorCount++
        try {
            val prio = when (level) { 'E', 'F' -> android.util.Log.ERROR; 'W' -> android.util.Log.WARN; else -> android.util.Log.INFO }
            android.util.Log.println(prio, "2v2/$tag", msg)           // logcat'ga ham
        } catch (_: Throwable) {}
        val trace = t?.let { android.util.Log.getStackTraceString(it) }
        try { exec.execute { emit(now, level, tag, th, msg, trace) } } catch (_: Throwable) {}
    }

    private fun emit(now: Long, level: Char, tag: String, th: String, msg: String, trace: String?) {
        val key = "$level$tag$msg"
        if (key == lastKey && now - lastAt < 3000) { suppressed++; lastAt = now; return }
        if (suppressed > 0) append(format(now, 'I', "Log", "applog", "(previous message repeated $suppressed more times)", null))
        suppressed = 0; lastKey = key; lastAt = now
        append(format(now, level, tag, th, msg, trace))
    }

    private fun format(now: Long, level: Char, tag: String, th: String, msg: String, trace: String?): String {
        val sb = StringBuilder(160)
        sb.append(fmt.format(Date(now))).append(' ').append(level).append('/').append(tag)
            .append(" [").append(th).append("] ").append(msg).append('\n')
        if (trace != null) sb.append(trace).append('\n')
        return sb.toString()
    }

    private fun append(text: String) {
        val f = file ?: return
        synchronized(lock) {
            try {
                if (f.length() > MAX_BYTES) {                         // aylantirish: eski log saqlanadi
                    val old = File(f.parentFile, "log.old.txt")
                    old.delete(); f.renameTo(old)
                }
                f.appendText(text)
            } catch (_: Throwable) {}
        }
    }

    private fun flush() { try { exec.submit {}.get(1, TimeUnit.SECONDS) } catch (_: Throwable) {} }

    /** Log faylining oxirgi qismi (ulashish/nusxalash uchun) */
    fun read(maxBytes: Int = 60_000): String {
        flush()
        val f = file ?: return ""
        return synchronized(lock) {
            try {
                if (!f.exists()) "" else RandomAccessFile(f, "r").use { raf ->
                    val len = raf.length(); val start = maxOf(0L, len - maxBytes)
                    raf.seek(start)
                    val b = ByteArray((len - start).toInt()); raf.readFully(b)
                    String(b, Charsets.UTF_8)
                }
            } catch (_: Throwable) { "" }
        }
    }

    fun clear() {
        flush()
        synchronized(lock) {
            file?.delete(); file?.parentFile?.let { File(it, "log.old.txt").delete() }
        }
        errorCount = 0
        write('I', "App", "log cleared", null)
    }

    fun sizeKb(): Long { flush(); return (file?.length() ?: 0L) / 1024 }

    /** Foydalanuvchiga ko'rsatiladigan yo'l */
    fun shownPath(): String {
        val p = file?.absolutePath ?: return "log.txt"
        val i = p.indexOf("Android/data")
        return if (i >= 0) p.substring(i) else p
    }

    /** Telefondagi "Ulashish" oynasi orqali (Telegram, pochta...) logni yuborish */
    fun share() {
        val ctx = AppCtx.app ?: return
        val text = read(60_000)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "2v2 Football log")
            putExtra(Intent.EXTRA_TEXT, if (text.isEmpty()) "(log is empty)" else text)
        }
        try { ctx.startActivity(Intent.createChooser(send, "Share log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (e: Exception) { e("Log", "share failed", e) }
    }
}
