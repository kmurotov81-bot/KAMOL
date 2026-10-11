package soccer.shared

import java.nio.ByteBuffer

object Op {
    const val JOIN = 1
    const val INPUT = 2
    const val PING = 3
    const val PONG = 4
    const val WELCOME = 5
    const val SNAP = 6
    const val LEAVE = 7
    const val TEAM = 9     // client -> server: jamoa tanlash (0/1)
    const val READY = 10   // client -> server: tayyorman (START)
    const val NOTICE = 11 // server -> client: 1 = jamoa to'la, 2 = o'yin paytida jamoa almashtirib bo'lmaydi
    const val ERROR = 8   // 1 = xona topilmadi, 2 = xona to'la
}

data class Input(val seq: Int, val mx: Float, val my: Float, val action: Int)   // action: Act.*

class PState(
    val id: Int, val team: Int,
    val x: Float, val y: Float, val vx: Float, val vy: Float,
    val ack: Int,
    val ready: Boolean = false
)

class Snapshot(
    val tick: Int,
    val bx: Float, val by: Float, val bvx: Float, val bvy: Float,
    val scoreA: Int, val scoreB: Int,
    val state: Int, val timer: Int,
    val players: List<PState>,
    var recvMs: Long = 0L
)

/** Oddiy binary protokol (protobuf o'rniga, MVP uchun yetarli). */
object Codec {
    /** code: -1 = quick match, 0 = yangi xona yaratish, >0 = xona kodi bilan qo'shilish */
    /** token: sessiya identifikatori - reconnect'da o'sha o'yinchi o'rnini qaytarish uchun */
    fun join(code: Int, token: Long): ByteArray =
        ByteBuffer.allocate(13).put(Op.JOIN.toByte()).putInt(code).putLong(token).array()
    fun error(reason: Int) = byteArrayOf(Op.ERROR.toByte(), reason.toByte())
    fun leave() = byteArrayOf(Op.LEAVE.toByte())
    fun team(t: Int) = byteArrayOf(Op.TEAM.toByte(), t.toByte())
    fun ready(r: Boolean) = byteArrayOf(Op.READY.toByte(), if (r) 1 else 0)
    fun notice(code: Int) = byteArrayOf(Op.NOTICE.toByte(), code.toByte())

    fun welcome(id: Int, team: Int, room: Int): ByteArray =
        ByteBuffer.allocate(7).put(Op.WELCOME.toByte()).put(id.toByte()).put(team.toByte()).putInt(room).array()

    fun input(seq: Int, mx: Float, my: Float, action: Int): ByteArray {
        val b = ByteBuffer.allocate(14)
        b.put(Op.INPUT.toByte()).putInt(seq).putFloat(mx).putFloat(my)
        b.put(action.toByte())
        return b.array()
    }

    /** Allocation'siz yozish (INPUT paketi 60Hz da yuboriladi, massiv qayta ishlatiladi). */
    fun writeInput(a: ByteArray, seq: Int, mx: Float, my: Float, action: Int) {
        a[0] = Op.INPUT.toByte()
        putInt(a, 1, seq); putInt(a, 5, mx.toRawBits()); putInt(a, 9, my.toRawBits())
        a[13] = action.toByte()
    }

    private fun putInt(a: ByteArray, o: Int, v: Int) {
        a[o] = (v ushr 24).toByte(); a[o + 1] = (v ushr 16).toByte()
        a[o + 2] = (v ushr 8).toByte(); a[o + 3] = v.toByte()
    }

    fun ping(ms: Long): ByteArray =
        ByteBuffer.allocate(9).put(Op.PING.toByte()).putLong(ms).array()

    fun pong(ms: Long): ByteArray =
        ByteBuffer.allocate(9).put(Op.PONG.toByte()).putLong(ms).array()

    /** op baytdan KEYIN o'qiladi */
    fun decodeInput(b: ByteBuffer) =
        Input(b.int, b.float, b.float, b.get().toInt())

    fun encodeSnap(s: Snapshot): ByteArray {
        val b = ByteBuffer.allocate(1 + 4 + 16 + 3 + 3 + s.players.size * 27)
        b.put(Op.SNAP.toByte()).putInt(s.tick)
        b.putFloat(s.bx).putFloat(s.by).putFloat(s.bvx).putFloat(s.bvy)
        b.put(s.scoreA.toByte()).put(s.scoreB.toByte()).put(s.players.size.toByte())
        b.put(s.state.toByte()).putShort(s.timer.toShort())
        for (p in s.players) {
            b.put(p.id.toByte()).put(p.team.toByte())
            b.putFloat(p.x).putFloat(p.y).putFloat(p.vx).putFloat(p.vy).putInt(p.ack)
            b.put(if (p.ready) 1 else 0)
        }
        return b.array()
    }

    fun decodeSnap(b: ByteBuffer): Snapshot {
        val tick = b.int
        val bx = b.float; val by = b.float; val bvx = b.float; val bvy = b.float
        val sa = b.get().toInt(); val sb = b.get().toInt(); val n = b.get().toInt()
        val state = b.get().toInt(); val timer = b.short.toInt()
        val list = ArrayList<PState>(n)
        repeat(n) {
            list.add(PState(b.get().toInt(), b.get().toInt(),
                b.float, b.float, b.float, b.float, b.int, b.get().toInt() != 0))
        }
        return Snapshot(tick, bx, by, bvx, bvy, sa, sb, state, timer, list)
    }
}
