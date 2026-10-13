package com.soccer.game

import android.net.wifi.WifiManager
import soccer.server.Bot
import soccer.server.GameServer
import soccer.shared.C
import soccer.shared.SLog
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.concurrent.thread

/** Telefonning ichida o'yin serveri (va ixtiyoriy test botlari) ni ishga tushiradi. */
object LocalServer {
    private var server: GameServer? = null
    private val bots = ArrayList<Bot>()
    private var lock: WifiManager.MulticastLock? = null

    /**
     * loopbackOnly = true  -> offline (faqat shu telefon)
     * loopbackOnly = false -> LAN host: do'stlar Wi-Fi orqali ulanadi (qidiruvda ko'rinadi)
     * false qaytarsa: port band yoki server ochilmadi.
     */
    @Synchronized
    fun start(botCount: Int, loopbackOnly: Boolean): Boolean {
        stop()
        return try {
            val s = GameServer(loopbackOnly = loopbackOnly)
            s.serverName = android.os.Build.MODEL ?: "Phone"
            server = s
            if (!loopbackOnly) lock = AppCtx.multicastLock()      // broadcast so'rovlarini qabul qilish uchun
            SLog.i("LocalServer", "started (loopbackOnly=$loopbackOnly, name=${s.serverName})")
            thread(isDaemon = true, name = "local-server") {
                try { s.run() } catch (t: Throwable) { SLog.e("LocalServer", "server crashed", t) }
            }
            repeat(botCount) { bots.add(Bot(C.PORT, it, startDelayMs = 700L).also { b -> b.start() }) }
            true
        } catch (e: Exception) {
            SLog.e("LocalServer", "cannot start server (loopbackOnly=$loopbackOnly) - port ${C.PORT} busy?", e)
            server = null
            false
        }
    }

    @Synchronized
    fun stop() {
        if (server != null) SLog.i("LocalServer", "stopped")
        bots.forEach { it.stop() }
        bots.clear()
        server?.stop()
        server = null
        try { lock?.release() } catch (_: Exception) {}
        lock = null
    }

    /**
     * Do'stlar kiritadigan lokal IPv4: Wi-Fi/hotspot interfeysi birinchi (mobil internet "rmnet" va VPN emas),
     * aks holda birinchi topilgan lokal manzil.
     */
    fun localIp(): String? = try {
        var best: String? = null
        var bestPrio = Int.MAX_VALUE
        val nis = NetworkInterface.getNetworkInterfaces()
        if (nis != null) for (ni in nis) {
            if (!ni.isUp || ni.isLoopback) continue
            val n = ni.name.lowercase()
            val prio = when {
                n.startsWith("wlan") || n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("wifi") -> 0
                n.startsWith("eth") -> 1
                n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("tun") ||
                    n.startsWith("p2p") || n.startsWith("dummy") -> 9
                else -> 5
            }
            for (a in ni.inetAddresses) {
                if (a is Inet4Address && !a.isLoopbackAddress && a.isSiteLocalAddress && prio < bestPrio) {
                    best = a.hostAddress; bestPrio = prio
                }
            }
        }
        best
    } catch (_: Exception) { null }
}
