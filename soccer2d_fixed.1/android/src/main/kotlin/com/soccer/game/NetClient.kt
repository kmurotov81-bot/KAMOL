package com.soccer.game

import soccer.shared.*
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

/**
 * Barcha socket amallari fon oqimlarida (Androidda main thread'da tarmoq mumkin emas).
 * Socket ulanmagan (connect() yo'q): shunda serverdan kelgan "port yopiq" (ICMP) xatosi socket'ni o'ldirmaydi
 * va server bir necha manzildan javob bersa ham paketlar yo'qolmaydi.
 */
class NetClient(hostSpec: String) {
    @Volatile var myId = -1
    @Volatile var myTeam = 0
    @Volatile var mySlot = 0          // jamoadagi o'rin (0/1)
    @Volatile var roomCode = 0
    @Volatile var notice = 0
    @Volatile var noticeAt = 0L
    @Volatile var error = 0          // 1 = xona topilmadi, 2 = xona to'la, 3 = versiya mos emas, 4 = manzil noto'g'ri
    @Volatile var rttMs = 0
    @Volatile var lastRecvMs = System.currentTimeMillis()   // serverdan oxirgi paket vaqti (aloqa uzilganini bilish uchun)
    @Volatile private var closed = false
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var target: InetSocketAddress? = null

    val snaps = ConcurrentLinkedQueue<Snapshot>()
    private val out = ArrayBlockingQueue<ByteArray>(256)
    private val inputPool = ConcurrentLinkedQueue<ByteArray>()

    init {
        thread(isDaemon = true, name = "net") {
            SLog.i("Net", "connecting to '$hostSpec'")
            val spec = HostSpec.parse(hostSpec)
            val tgt: InetSocketAddress? = try { spec?.let { InetSocketAddress(it.host, it.port) } } catch (_: Exception) { null }
            if (tgt == null || tgt.isUnresolved) { SLog.e("Net", "invalid or unresolved server address '$hostSpec'"); error = 4; return@thread }
            val s = try { DatagramSocket() } catch (e: Exception) { SLog.e("Net", "cannot open UDP socket", e); error = 4; return@thread }
            socket = s; target = tgt

            thread(isDaemon = true, name = "net-send") {
                val pk = DatagramPacket(ByteArray(0), 0, tgt)          // qayta ishlatiladi
                while (!closed) {
                    val d = try { out.take() } catch (_: InterruptedException) { break }
                    try { pk.setData(d, 0, d.size); pk.socketAddress = tgt; s.send(pk) } catch (e: IOException) { SLog.w("Net", "send failed: ${e.message}") }
                    if (d.size == Codec.INPUT_LEN && d[0].toInt() == Op.INPUT) inputPool.offer(d)
                }
            }

            val buf = ByteArray(1024)
            while (!closed) {
                val p = DatagramPacket(buf, buf.size)
                try { s.receive(p) } catch (e: IOException) { if (closed) break else { SLog.w("Net", "receive failed: ${e.message}"); Thread.sleep(50); continue } }
                lastRecvMs = System.currentTimeMillis()
                try {
                    val b = ByteBuffer.wrap(buf, 0, p.length)
                    when (b.get().toInt()) {
                        Op.WELCOME -> { val id = b.get().toInt(); myTeam = b.get().toInt(); mySlot = b.get().toInt(); roomCode = b.int; myId = id
                            SLog.i("Net", "joined room $roomCode as player $id (team $myTeam slot $mySlot) on '$hostSpec'") }
                        Op.ERROR -> { error = b.get().toInt(); SLog.w("Net", "server rejected join: error code $error") }
                        Op.NOTICE -> { notice = b.get().toInt(); noticeAt = System.currentTimeMillis() }
                        Op.SNAP -> snaps.add(Codec.decodeSnap(b).also { it.recvMs = System.currentTimeMillis() })
                        Op.PONG -> rttMs = (System.currentTimeMillis() - b.long).toInt()
                    }
                } catch (e: Exception) { SLog.w("Net", "bad packet (${p.length} bytes)", e) }
            }
        }
    }

    fun send(data: ByteArray) { if (!closed) out.offer(data) }

    fun sendInput(seq: Int, mx: Float, my: Float, action: Int, power: Int) {
        if (closed) return
        val a = inputPool.poll() ?: ByteArray(Codec.INPUT_LEN)
        Codec.writeInput(a, seq, mx, my, action, power)
        if (!out.offer(a)) inputPool.offer(a)
    }

    /** leave = false: ilova fonga o'tganda - server o'rnimizni TIMEOUT_MS gacha saqlaydi. */
    fun close(leave: Boolean = true) {
        if (closed) return
        val s = socket; val t = target
        thread {
            if (leave && s != null && t != null) try { s.send(DatagramPacket(Codec.leave(), 1, t)) } catch (_: Exception) {}
            closed = true
            s?.close()
        }
    }
}
