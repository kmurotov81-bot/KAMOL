package soccer.server

import soccer.shared.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import kotlin.concurrent.thread
import kotlin.math.hypot
import kotlin.random.Random

/**
 * Bot: haqiqiy UDP client kabi quick match'ga qo'shiladi (loopback orqali).
 * AI: to'pning rakib darvozasiga qarama-qarshi tomoniga o'tadi, keyin to'pni darvoza tomon uradi.
 */
class Bot(private val port: Int, private val n: Int, private val skill: Float = 0.85f, private val startDelayMs: Long = 0L) {
    @Volatile private var running = true
    @Volatile private var socket: DatagramSocket? = null

    fun stop() { running = false; socket?.close() }

    fun start() = thread(isDaemon = true, name = "bot$n") {
        try { if (startDelayMs > 0) Thread.sleep(startDelayMs); if (running) run() } catch (_: Exception) { /* to'xtatilganda socket yopiladi */ }
    }

    private fun run() {
        val s = DatagramSocket(); socket = s
        s.connect(InetAddress.getByName("127.0.0.1"), port)
        s.soTimeout = 2
        val token = Random.nextLong() or 1L
        val buf = ByteArray(1024)
        var myId = -1; var myTeam = 0
        var lastJoin = 0L; var seq = 0
        var mx = 0f; var my = 0f; var kick = false
        var next = System.nanoTime()
        fun send(d: ByteArray) = s.send(DatagramPacket(d, d.size))

        while (running) {
            try {
                val p = DatagramPacket(buf, buf.size)
                s.receive(p)
                when (buf[0].toInt()) {
                    Op.WELCOME -> { myId = buf[1].toInt(); myTeam = buf[2].toInt() }
                    Op.SNAP -> {
                        val b = ByteBuffer.wrap(buf, 1, p.length - 1)
                        val snap = Codec.decodeSnap(b)
                        val me = snap.players.firstOrNull { it.id == myId }
                        if (snap.state == State.LOBBY && me != null && !me.ready) send(Codec.ready(true))
                        if (snap.state != State.PLAYING || me == null) { mx = 0f; my = 0f; kick = false }
                        else {
                            val goalX = if (myTeam == 0) C.FIELD_W else 0f
                            var gx = goalX - snap.bx; var gy = C.FIELD_H / 2f - snap.by
                            val gl = hypot(gx, gy).coerceAtLeast(0.001f); gx /= gl; gy /= gl
                            val dist = hypot(snap.bx - me.x, snap.by - me.y)
                            // dot < 0: biz to'pning darvozadan teskari tomonida (urishga qulay joyda)
                            val behind = (me.x - snap.bx) * gx + (me.y - snap.by) * gy < 0f
                            val tx: Float; val ty: Float
                            if (dist < 2.2f && behind) { tx = snap.bx; ty = snap.by }
                            else { tx = snap.bx - gx * 1.2f; ty = snap.by - gy * 1.2f }
                            val dx = tx - me.x; val dy = ty - me.y
                            val dl = hypot(dx, dy).coerceAtLeast(0.001f)
                            mx = dx / dl * skill; my = dy / dl * skill
                            kick = dist < C.KICK_RANGE && behind
                        }
                    }
                }
            } catch (_: SocketTimeoutException) {}

            val ms = System.currentTimeMillis()
            if (myId < 0) {
                if (ms - lastJoin > 500) { send(Codec.join(-1, token)); lastJoin = ms }
                continue
            }
            val now = System.nanoTime()
            while (next <= now) {
                seq++
                send(Codec.input(seq, mx, my, if (kick) Act.SHOOT else Act.NONE))
                next += 16_666_667L
            }
        }
    }
}
