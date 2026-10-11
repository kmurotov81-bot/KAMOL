package com.soccer.game

import soccer.shared.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ArrayBlockingQueue
import kotlin.concurrent.thread

/** Barcha socket amallari fon oqimlarida (Androidda main thread'da tarmoq mumkin emas). */
class NetClient(host: String) {
    @Volatile var myId = -1
    @Volatile var myTeam = 0
    @Volatile var roomCode = 0
    @Volatile var notice = 0
    @Volatile var noticeAt = 0L
    @Volatile var error = 0          // 1 = xona topilmadi, 2 = xona to'la
    @Volatile var rttMs = 0
    @Volatile private var closed = false
    @Volatile private var socket: DatagramSocket? = null

    val snaps = ConcurrentLinkedQueue<Snapshot>()
    private val out = ArrayBlockingQueue<ByteArray>(256)
    private val inputPool = ConcurrentLinkedQueue<ByteArray>()

    init {
        thread(isDaemon = true, name = "net") {
            try {
                val s = DatagramSocket()
                s.connect(InetSocketAddress(host, C.PORT))
                socket = s
                thread(isDaemon = true, name = "net-send") {
                    try {
                        val pk = DatagramPacket(ByteArray(0), 0)      // qayta ishlatiladi
                        while (!closed) {
                            val d = out.take()
                            pk.setData(d, 0, d.size)
                            s.send(pk)
                            if (d.size == 14 && d[0].toInt() == Op.INPUT) inputPool.offer(d)
                        }
                    } catch (_: Exception) {}
                }
                val buf = ByteArray(1024)
                while (!closed) {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    val b = ByteBuffer.wrap(buf, 0, p.length)
                    when (b.get().toInt()) {
                        Op.WELCOME -> { val id = b.get().toInt(); myTeam = b.get().toInt(); roomCode = b.int; myId = id }
                        Op.ERROR -> error = b.get().toInt()
                        Op.NOTICE -> { notice = b.get().toInt(); noticeAt = System.currentTimeMillis() }
                        Op.SNAP -> snaps.add(Codec.decodeSnap(b).also { it.recvMs = System.currentTimeMillis() })
                        Op.PONG -> rttMs = (System.currentTimeMillis() - b.long).toInt()
                    }
                }
            } catch (_: Exception) {}
        }
    }

    fun send(data: ByteArray) { if (!closed) out.offer(data) }

    fun sendInput(seq: Int, mx: Float, my: Float, action: Int) {
        if (closed) return
        val a = inputPool.poll() ?: ByteArray(14)
        Codec.writeInput(a, seq, mx, my, action)
        if (!out.offer(a)) inputPool.offer(a)
    }

    /** leave = false: ilova fonga o'tganda - server o'rnimizni TIMEOUT_MS gacha saqlaydi. */
    fun close(leave: Boolean = true) {
        if (closed) return
        val s = socket
        thread {
            if (leave) try { s?.send(DatagramPacket(Codec.leave(), 1)) } catch (_: Exception) {}
            closed = true
            s?.close()
        }
    }
}
