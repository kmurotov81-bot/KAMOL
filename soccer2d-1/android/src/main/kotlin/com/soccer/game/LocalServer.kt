package com.soccer.game

import soccer.server.Bot
import soccer.server.GameServer
import soccer.shared.C
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.concurrent.thread

/** Telefonning ichida o'yin serveri (va ixtiyoriy botlar) ni ishga tushiradi. */
object LocalServer {
    private var server: GameServer? = null
    private val bots = ArrayList<Bot>()

    /**
     * loopbackOnly = true  -> offline (faqat shu telefon), botlar bilan
     * loopbackOnly = false -> LAN host: do'stlar Wi-Fi orqali ulanadi
     * false qaytarsa: port band yoki server ochilmadi.
     */
    @Synchronized
    fun start(botCount: Int, loopbackOnly: Boolean): Boolean {
        stop()
        return try {
            val s = GameServer(loopbackOnly = loopbackOnly)
            server = s
            thread(isDaemon = true, name = "local-server") { s.run() }
            repeat(botCount) { bots.add(Bot(C.PORT, it, startDelayMs = 700L).also { b -> b.start() }) }
            true
        } catch (e: Exception) {
            server = null
            false
        }
    }

    @Synchronized
    fun stop() {
        bots.forEach { it.stop() }
        bots.clear()
        server?.stop()
        server = null
    }

    /** Wi-Fi/hotspot'dagi lokal IPv4 manzil (do'stlar shuni kiritadi) */
    fun localIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress && !it.isLoopbackAddress }
            ?.hostAddress
    } catch (_: Exception) { null }
}
