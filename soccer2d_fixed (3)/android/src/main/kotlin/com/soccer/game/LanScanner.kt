package com.soccer.game

import soccer.shared.C
import soccer.shared.Codec
import soccer.shared.Op
import soccer.shared.SLog
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** Bitta Wi-Fi'dagi serverlarni (boshqa telefon "HOST ON THIS PHONE" bosgan) IP kiritmasdan topadi. */
class LanScanner {
    class Found(val ip: String, val name: String, val humans: Int, val compatible: Boolean)

    val results = CopyOnWriteArrayList<Found>()
    @Volatile var scanning = false
    @Volatile var version = 0          // natijalar o'zgarganda oshadi (UI qayta quradi)
    @Volatile private var token = 0

    @Synchronized
    fun start() {
        val my = ++token
        results.clear(); version++; scanning = true
        thread(isDaemon = true, name = "lan-scan") {
            val lock = AppCtx.multicastLock()
            var s: DatagramSocket? = null
            try {
                s = DatagramSocket()
                s.broadcast = true
                s.soTimeout = 250
                val targets = broadcastTargets()
                val probe = Codec.discover()
                val end = System.currentTimeMillis() + 3500
                var nextSend = 0L
                val buf = ByteArray(512)
                while (token == my && System.currentTimeMillis() < end) {
                    val now = System.currentTimeMillis()
                    if (now >= nextSend) {                       // so'rov bir necha marta yuboriladi (paket yo'qolishi mumkin)
                        for (t in targets) try { s.send(DatagramPacket(probe, probe.size, t, C.PORT)) } catch (_: Exception) {}
                        nextSend = now + 700
                    }
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        s.receive(p)
                        if (p.length > 1 && buf[0].toInt() == Op.INFO) {
                            val info = Codec.decodeInfo(ByteBuffer.wrap(buf, 1, p.length - 1))
                            val ip = p.address.hostAddress ?: continue
                            if (results.none { it.ip == ip }) {
                                SLog.i("Lan", "found $ip '${info.name}' players=${info.humans} protocol=${info.protocol}")
                                results.add(Found(ip, info.name.ifBlank { "Game" }, info.humans, info.protocol == C.PROTOCOL))
                                version++
                            }
                        }
                    } catch (_: SocketTimeoutException) {}
                }
            } catch (e: Exception) {
                SLog.e("Lan", "scan failed", e)
            } finally {
                s?.close(); lock?.release()
                if (token == my) { scanning = false; version++ }
            }
        }
    }

    @Synchronized
    fun stop() { token++; scanning = false }

    private fun broadcastTargets(): List<InetAddress> {
        val list = ArrayList<InetAddress>()
        try { list.add(InetAddress.getByName("255.255.255.255")) } catch (_: Exception) {}
        try {
            val nis = NetworkInterface.getNetworkInterfaces()
            if (nis != null) for (ni in nis) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) ia.broadcast?.let { if (!list.contains(it)) list.add(it) }
            }
        } catch (_: Exception) {}
        return list
    }
}
