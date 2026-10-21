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
    const val TEAM = 9     // client -> server: o'rin tanlash, qiymat = jamoa*2 + o'rin (0..3)
    const val READY = 10   // client -> server: tayyorman (START)
    const val NOTICE = 11  // server -> client: 1 = o'rin band, 2 = o'yin paytida o'zgartirib bo'lmaydi
    const val ERROR = 8    // 1 = xona topilmadi, 2 = xona to'la, 3 = versiya mos emas, 5 = o'yin allaqachon boshlangan, 6 = qayta o'ynashga ovoz bermagani uchun chiqarildi
    const val MATCHTIME = 14 // client -> server: o'yin vaqti, daqiqa (faqat boshliq, faqat lobby)
    const val DISCOVER = 12 // client -> broadcast: LAN'dagi serverlarni qidirish
    const val INFO = 13     // server -> client: server nomi va odamlar soni
    const val RESTART = 15  // client -> server: VS BOTS da o'yinni noldan boshlash
    const val NAME = 16    // client -> server: o'yinchi nomi (12 belgigacha)
    const val GOAL = 17    // server -> client: gol xabari (jamoa, gol urgan, own goal, daqiqa)
}

/** action: Act.*, power: KickPower darajasi 0..3 (faqat zarba/pas uchun) */
data class Input(val seq: Int, val mx: Float, val my: Float, val action: Int, val power: Int = 0)

class PState(
    val id: Int, val team: Int, val slot: Int,
    val x: Float, val y: Float, val vx: Float, val vy: Float,
    val ack: Int,
    val ready: Boolean = false
)

/** Gol xabari: scorer bo'sh bo'lsa - nomsiz; bot golida name = "BOT" */
data class GoalMsg(val team: Int, val name: String, val own: Boolean, val minute: Int)

class ServerInfo(val protocol: Int, val humans: Int, val name: String)

class Snapshot(
    val tick: Int,
    val bx: Float, val by: Float, val bvx: Float, val bvy: Float,
    val scoreA: Int, val scoreB: Int,
    val state: Int, val timer: Int,
    val players: List<PState>,
    val overtime: Boolean = false,
    var recvMs: Long = 0L
)

/** Oddiy binary protokol (protobuf o'rniga, MVP uchun yetarli). */
object Codec {
    /** INPUT paketi uzunligi: op(1) seq(4) mx(4) my(4) action(1) power(1) */
    const val INPUT_LEN = 15

    /** code: -1 = quick match, 0 = yangi xona yaratish, >0 = xona kodi bilan qo'shilish */
    /** token: sessiya identifikatori - reconnect'da o'sha o'yinchi o'rnini qaytarish uchun */
    /** minutes: yangi xona yaratilsa o'yin vaqti (1..15); 0 = server standarti */
    fun join(code: Int, token: Long, minutes: Int = 0): ByteArray =
        ByteBuffer.allocate(15).put(Op.JOIN.toByte()).putInt(code).putLong(token).put(C.PROTOCOL.toByte()).put(minutes.toByte()).array()

    fun discover() = byteArrayOf(Op.DISCOVER.toByte(), C.PROTOCOL.toByte())

    fun info(name: String, humans: Int): ByteArray {
        var nb = name.toByteArray(Charsets.UTF_8)
        if (nb.size > 24) nb = nb.copyOf(24)
        return ByteBuffer.allocate(4 + nb.size)
            .put(Op.INFO.toByte()).put(C.PROTOCOL.toByte()).put(humans.toByte()).put(nb.size.toByte()).put(nb).array()
    }

    /** op bayt o'qilgandan KEYIN */
    fun decodeInfo(b: ByteBuffer): ServerInfo {
        val proto = b.get().toInt(); val humans = b.get().toInt(); val n = b.get().toInt() and 0xff
        val bytes = ByteArray(minOf(n, b.remaining())); b.get(bytes)
        return ServerInfo(proto, humans, String(bytes, Charsets.UTF_8))
    }
    fun error(reason: Int) = byteArrayOf(Op.ERROR.toByte(), reason.toByte())
    fun leave() = byteArrayOf(Op.LEAVE.toByte())
    fun restart() = byteArrayOf(Op.RESTART.toByte())

    private fun nameBytes(s: String): ByteArray {
        val nb = s.trim().take(12).toByteArray(Charsets.UTF_8)
        return if (nb.size > 48) nb.copyOf(48) else nb
    }

    fun playerName(s: String): ByteArray {
        val nb = nameBytes(s)
        return ByteBuffer.allocate(2 + nb.size).put(Op.NAME.toByte()).put(nb.size.toByte()).put(nb).array()
    }

    /** op baytdan KEYIN */
    fun decodePlayerName(b: ByteBuffer): String = readStr(b).trim().ifBlank { "Player" }

    fun goalMsg(team: Int, name: String, own: Boolean, minute: Int): ByteArray {
        val nb = nameBytes(name)
        return ByteBuffer.allocate(5 + nb.size)
            .put(Op.GOAL.toByte()).put(team.toByte()).put((if (own) 1 else 0).toByte())
            .put(minute.toByte()).put(nb.size.toByte()).put(nb).array()
    }

    /** op baytdan KEYIN */
    fun decodeGoal(b: ByteBuffer): GoalMsg {
        val team = b.get().toInt(); val own = b.get().toInt() != 0; val minute = b.get().toInt()
        return GoalMsg(team, readStr(b), own, minute)
    }

    private fun readStr(b: ByteBuffer): String {
        val n = b.get().toInt() and 0xff
        val bytes = ByteArray(minOf(n, b.remaining())); b.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }
    /** seat = jamoa * 2 + o'rin (0..3) */
    fun team(seat: Int) = byteArrayOf(Op.TEAM.toByte(), seat.toByte())
    fun matchTime(minutes: Int) = byteArrayOf(Op.MATCHTIME.toByte(), minutes.toByte())
    fun ready(r: Boolean) = byteArrayOf(Op.READY.toByte(), if (r) 1 else 0)
    fun notice(code: Int) = byteArrayOf(Op.NOTICE.toByte(), code.toByte())

    fun welcome(id: Int, team: Int, slot: Int, room: Int): ByteArray =
        ByteBuffer.allocate(8).put(Op.WELCOME.toByte()).put(id.toByte()).put(team.toByte()).put(slot.toByte()).putInt(room).array()

    fun input(seq: Int, mx: Float, my: Float, action: Int, power: Int = 0): ByteArray {
        val b = ByteBuffer.allocate(INPUT_LEN)
        b.put(Op.INPUT.toByte()).putInt(seq).putFloat(mx).putFloat(my)
        b.put(action.toByte()).put(power.toByte())
        return b.array()
    }

    /** Allocation'siz yozish (INPUT paketi 60Hz da yuboriladi, massiv qayta ishlatiladi). */
    fun writeInput(a: ByteArray, seq: Int, mx: Float, my: Float, action: Int, power: Int = 0) {
        a[0] = Op.INPUT.toByte()
        putInt(a, 1, seq); putInt(a, 5, mx.toRawBits()); putInt(a, 9, my.toRawBits())
        a[13] = action.toByte(); a[14] = power.toByte()
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
    fun decodeInput(b: ByteBuffer): Input {
        val seq = b.int; val mx = b.float; val my = b.float
        val action = b.get().toInt(); val power = b.get().toInt()
        return Input(seq, mx, my, action, power)
    }

    /** Snapshot: header 27 bayt, har o'yinchi 24 bayt */
    fun encodeSnap(s: Snapshot): ByteArray {
        val b = ByteBuffer.allocate(1 + 4 + 16 + 3 + 3 + s.players.size * 24)
        b.put(Op.SNAP.toByte()).putInt(s.tick)
        b.putFloat(s.bx).putFloat(s.by).putFloat(s.bvx).putFloat(s.bvy)
        b.put(s.scoreA.toByte()).put(s.scoreB.toByte()).put(s.players.size.toByte())
        b.put((s.state or (if (s.overtime) 0x80 else 0)).toByte()).putShort(s.timer.toShort())
        for (p in s.players) {
            b.put(p.id.toByte()).put(p.team.toByte()).put(p.slot.toByte())
            b.putFloat(p.x).putFloat(p.y).putFloat(p.vx).putFloat(p.vy).putInt(p.ack)
            b.put(if (p.ready) 1 else 0)
        }
        return b.array()
    }

    fun decodeSnap(b: ByteBuffer): Snapshot {
        val tick = b.int
        val bx = b.float; val by = b.float; val bvx = b.float; val bvy = b.float
        val sa = b.get().toInt(); val sb = b.get().toInt(); val n = b.get().toInt()
        val rawState = b.get().toInt(); val state = rawState and 0x7F; val overtime = rawState and 0x80 != 0
        val timer = b.short.toInt()
        val list = ArrayList<PState>(n)
        repeat(n) {
            val id = b.get().toInt(); val team = b.get().toInt(); val slot = b.get().toInt()
            list.add(PState(id, team, slot,
                b.float, b.float, b.float, b.float, b.int, b.get().toInt() != 0))
        }
        return Snapshot(tick, bx, by, bvx, bvy, sa, sb, state, timer, list, overtime)
    }
}
