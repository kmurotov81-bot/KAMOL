package soccer.server

import soccer.shared.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread
import kotlin.random.Random

private class Client(val id: Int, @Volatile var team: Int, @Volatile var addr: SocketAddress, val token: Long) {
    @Volatile var lastCtl = 0L        // TEAM/READY so'rovlarini cheklash
    val queue = ConcurrentLinkedQueue<Input>()   // input buffer (jitter)
    @Volatile var last = Input(0, 0f, 0f, 0)
    @Volatile var ack = 0
    @Volatile var seen = System.currentTimeMillis()
    // anti-cheat holati (faqat receive thread)
    var lastSeq = 0
    var winStart = 0L
    var winCount = 0
    var violations = 0
}

private class BotPlayer(val id: Int, val team: Int) { val brain = BotBrain(id, team) }

/** Bitta match xonasi: o'z fizika dunyosi, o'yinchilari va state machine'i bor. */
private class Room(
    val code: Int,
    val public: Boolean,
    private val addrRoom: ConcurrentHashMap<SocketAddress, Room>,
    private val send: (ByteArray, SocketAddress) -> Unit
) {
    val clients = ConcurrentHashMap<SocketAddress, Client>()
    private val actions = ConcurrentLinkedQueue<() -> Unit>()
    val sim = SoccerWorld(withBall = true)
    @Volatile var emptySince = System.currentTimeMillis()
    private val bots = ArrayList<BotPlayer>()
    private var prevState = State.LOBBY

    private val pendingJoins = java.util.concurrent.atomic.AtomicInteger(0)
    fun hasSpace() = clients.size + pendingJoins.get() < C.MAX_PLAYERS

    /**
     * Yangi o'yinchi. Jamoa o'yin oqimida (sim bilan bir xil threadda) tanlanadi, shunda
     * jamoa limiti (TEAM_SIZE) poyga holatlarisiz saqlanadi. Boshida o'yinchilari kam jamoa beriladi.
     */
    fun add(addr: SocketAddress, token: Long) {
        pendingJoins.incrementAndGet()
        addrRoom[addr] = this
        actions.add {
            pendingJoins.decrementAndGet()
            // odamlar soni (botlar hisobga olinmaydi): o'yin ketayotgan bo'lsa bot o'rniga kiradi
            val h0 = clients.values.count { it.team == 0 }; val h1 = clients.values.count { it.team == 1 }
            if (h0 >= C.TEAM_SIZE && h1 >= C.TEAM_SIZE) {
                addrRoom.remove(addr); send(Codec.error(2), addr)
            } else {
                var id = 1
                while (clients.values.any { it.id == id }) id++
                val team = when { h0 >= C.TEAM_SIZE -> 1; h1 >= C.TEAM_SIZE -> 0; h0 <= h1 -> 0; else -> 1 }
                if (sim.teamCount(team) >= C.TEAM_SIZE) removeOneBot(team)
                val c = Client(id, team, addr, token)
                clients[addr] = c
                sim.addPlayer(id, team)
                SLog.i("Server", "[$code] join id=$id team=$team $addr")
                send(Codec.welcome(id, team, code), addr)
            }
        }
    }

    private fun throttled(c: Client): Boolean {
        val now = System.currentTimeMillis()
        if (now - c.lastCtl < 100) return true
        c.lastCtl = now
        return false
    }

    /** Jamoa tanlash: faqat LOBBY'da, va jamoada joy bo'lsa (TEAM_SIZE) */
    fun requestTeam(c: Client, team: Int) {
        if (throttled(c)) return
        actions.add {
            if (clients.containsKey(c.addr)) {
                if (sim.state != State.LOBBY) send(Codec.notice(2), c.addr)
                else if (team in 0..1 && team != c.team) {
                    if (sim.setTeam(c.id, team)) c.team = team else send(Codec.notice(1), c.addr)
                }
            }
        }
    }

    fun requestReady(c: Client, ready: Boolean) {
        if (throttled(c)) return
        actions.add { if (clients.containsKey(c.addr) && sim.state == State.LOBBY) sim.setReady(c.id, ready) }
    }

    /** Reconnect: o'sha o'yinchi (token bir xil) yangi manzildan qaytdi - o'rni saqlanadi. */
    fun rebind(c: Client, newAddr: SocketAddress) {
        clients.remove(c.addr); addrRoom.remove(c.addr)
        c.addr = newAddr
        c.queue.clear()
        c.seen = System.currentTimeMillis()
        clients[newAddr] = c; addrRoom[newAddr] = this
    }

    fun remove(c: Client) {
        if (clients.remove(c.addr) != null) {
            addrRoom.remove(c.addr)
            actions.add {
                sim.removePlayer(c.id)
                if (sim.state != State.LOBBY && clients.isNotEmpty()) fillBots()   // bo'shagan joyni bot egallaydi
            }
        }
    }

    /** Bo'sh o'rinlarni botlar bilan to'ldiradi (har jamoada TEAM_SIZE gacha). Botlar doim "tayyor". */
    private fun fillBots() {
        for (t in 0..1) {
            var have = sim.teamCount(t)
            while (have < C.TEAM_SIZE) {
                var id = C.BOT_ID_BASE
                while (sim.players.containsKey(id) && id < C.MAX_ID - 1) id++
                sim.addPlayer(id, t)
                sim.setReady(id, true)
                bots.add(BotPlayer(id, t))
                have++
            }
        }
    }

    private fun removeOneBot(team: Int) {
        val b = bots.firstOrNull { it.team == team } ?: return
        sim.removePlayer(b.id); bots.remove(b)
    }

    private fun clearBots() {
        for (b in bots) sim.removePlayer(b.id)
        bots.clear()
    }

    fun tick() {
        while (true) { (actions.poll() ?: break).invoke() }
        if (clients.isEmpty()) {
            if (bots.isNotEmpty() || sim.state != State.LOBBY) { clearBots(); sim.backToLobby(); prevState = State.LOBBY }
            return
        }
        val now = System.currentTimeMillis()
        emptySince = now
        for (c in clients.values) {
            if (now - c.seen > C.TIMEOUT_MS) { remove(c); SLog.i("Server", "[$code] timeout id=${c.id}"); continue }
            val inp = c.queue.poll()
            if (inp != null) { c.last = inp; c.ack = inp.seq }
            val u = c.last
            if (sim.playing) sim.applyInput(c.id, u.mx, u.my, u.action)
        }
        // hamma odam START bosdi -> kam qolgan o'rinlar botlar bilan to'ldiriladi va match boshlanadi
        if (sim.state == State.LOBBY && bots.isEmpty() && clients.values.all { sim.isReady(it.id) }) fillBots()
        for (b in bots) {
            b.brain.think(sim)
            if (sim.playing) sim.applyInput(b.id, b.brain.mx, b.brain.my, b.brain.action)
        }
        sim.step()
        if (prevState != State.LOBBY && sim.state == State.LOBBY) clearBots()   // match tugadi -> yana Select Teams
        prevState = sim.state
        if (sim.tick % C.SNAP_EVERY == 0) broadcast()
    }

    private fun broadcast() {
        val b = sim.ball!!
        val list = ArrayList<PState>(clients.size + bots.size)
        for (c in clients.values) {
            val body = sim.players[c.id] ?: continue
            list.add(PState(c.id, c.team, body.position.x, body.position.y,
                body.linearVelocity.x, body.linearVelocity.y, c.ack, sim.isReady(c.id)))
        }
        for (bp in bots) {
            val body = sim.players[bp.id] ?: continue
            list.add(PState(bp.id, bp.team, body.position.x, body.position.y,
                body.linearVelocity.x, body.linearVelocity.y, 0, true))
        }
        val snap = Snapshot(sim.tick, b.position.x, b.position.y,
            b.linearVelocity.x, b.linearVelocity.y, sim.scoreA, sim.scoreB,
            sim.state, sim.timerSeconds(), list)
        val data = Codec.encodeSnap(snap)
        for (c in clients.values) send(data, c.addr)
    }
}

/** loopbackOnly = true: faqat shu qurilmadan ulanish mumkin (offline / botlar rejimi). */
class GameServer(
    private val netSim: NetSim = NetSim(0f, 0L, 0L),
    loopbackOnly: Boolean = false
) {
    // reuseAddress: ilova qayta ochilganda port "band" bo'lib qolmasligi uchun
    private val socket = DatagramSocket(null as SocketAddress?).apply {
        reuseAddress = true
        bind(if (loopbackOnly) InetSocketAddress(InetAddress.getByName("127.0.0.1"), C.PORT) else InetSocketAddress(C.PORT))
    }
    /** LAN qidiruvda ko'rinadigan nom */
    @Volatile var serverName = "Server"
    @Volatile private var running = true

    fun stop() { running = false; socket.close() }

    private val rooms = ConcurrentHashMap<Int, Room>()
    private val addrRoom = ConcurrentHashMap<SocketAddress, Room>()
    private val banned = ConcurrentHashMap<InetAddress, Long>()

    fun run() {
        SLog.i("Server", "Server UDP :${C.PORT} da ishlayapti")
        thread(isDaemon = true, name = "recv") { receiveLoop() }

        var prev = System.nanoTime()
        var acc = 0.0
        var lastCleanup = 0L
        while (running) {
            val now = System.nanoTime()
            acc += (now - prev) / 1e9
            prev = now
            if (acc > 0.25) acc = 0.25
            while (acc >= C.DT) { for (r in rooms.values) r.tick(); acc -= C.DT }

            val ms = System.currentTimeMillis()
            if (ms - lastCleanup > 1000) {          // bo'sh xonalarni 30s dan keyin o'chirish
                lastCleanup = ms
                rooms.values.removeIf { it.clients.isEmpty() && ms - it.emptySince > 30_000 }
            }
            Thread.sleep(1)
        }
    }

    private fun sendTo(data: ByteArray, addr: SocketAddress) {
        if (netSim.enabled) netSim.deliver { rawSend(data, addr) } else rawSend(data, addr)
    }

    private fun rawSend(data: ByteArray, addr: SocketAddress) {
        try { socket.send(DatagramPacket(data, data.size, addr)) } catch (e: java.io.IOException) { SLog.w("Server", "send to $addr failed: ${e.message}") }
    }

    private fun receiveLoop() {
        val buf = ByteArray(512)
        while (running) {
            val pkt = DatagramPacket(buf, buf.size)
            try { socket.receive(pkt) } catch (e: java.io.IOException) { if (!running) return else continue }
            if (pkt.length < 1) continue
            val addr = pkt.socketAddress
            if (netSim.enabled) {
                val copy = buf.copyOf(pkt.length)           // bufer qayta ishlatiladi
                netSim.deliver { handle(copy, copy.size, addr) }
            } else handle(buf, pkt.length, addr)
        }
    }

    private fun handle(buf: ByteArray, len: Int, addr: SocketAddress) {
        val b = ByteBuffer.wrap(buf, 0, len)
            try {
                when (b.get().toInt()) {
                    Op.JOIN -> if (b.remaining() >= 12) {
                    val code = b.int; val token = b.long
                    onJoin(addr, code, token, if (b.remaining() >= 1) b.get().toInt() else 0)
                }
                    Op.INPUT -> {
                        val room = addrRoom[addr]
                        val c = room?.clients?.get(addr)
                        if (room != null && c != null) onInput(room, c, Codec.decodeInput(b))
                    }
                    Op.PING -> sendTo(Codec.pong(b.long), addr)
                Op.DISCOVER -> sendTo(Codec.info(serverName, rooms.values.sumOf { it.clients.size }), addr)
                    Op.TEAM -> {
                        val room = addrRoom[addr]; val c = room?.clients?.get(addr)
                        if (room != null && c != null && b.remaining() >= 1) room.requestTeam(c, b.get().toInt())
                    }
                    Op.READY -> {
                        val room = addrRoom[addr]; val c = room?.clients?.get(addr)
                        if (room != null && c != null && b.remaining() >= 1) room.requestReady(c, b.get().toInt() != 0)
                    }
                    Op.LEAVE -> {
                        val room = addrRoom[addr]
                        room?.clients?.get(addr)?.let { room.remove(it) }
                    }
                }
            } catch (e: Exception) { SLog.w("Server", "bad packet (${len} bytes) from $addr", e) }
    }

    private fun newRoom(public: Boolean): Room {
        var code: Int
        do { code = Random.nextInt(1000, 10000) } while (rooms.containsKey(code))
        val r = Room(code, public, addrRoom, ::sendTo)
        rooms[code] = r
        SLog.i("Server", "Yangi xona $code (public=$public)")
        return r
    }

    private fun onJoin(addr: SocketAddress, code: Int, token: Long, version: Int) {
        if (version != C.PROTOCOL) { sendTo(Codec.error(3), addr); return }   // eski/yangi ilova
        val existing = addrRoom[addr]
        if (existing != null) {            // takroriy JOIN (WELCOME yo'qolgan bo'lishi mumkin)
            existing.clients[addr]?.let { c ->
                c.seen = System.currentTimeMillis()
                sendTo(Codec.welcome(c.id, c.team, existing.code), addr)
            }
            return
        }
        if (token != 0L) {
            for (r in rooms.values) {
                val c = r.clients.values.firstOrNull { it.token == token } ?: continue
                r.rebind(c, addr)
                SLog.i("Server", "[${r.code}] rejoin id=${c.id} $addr")
                sendTo(Codec.welcome(c.id, c.team, r.code), addr)
                return
            }
        }
        if (isBanned(addr)) return
        val room: Room? = when {
            code == 0 -> newRoom(public = false)
            code < 0 -> rooms.values.filter { it.public && it.hasSpace() }
                .maxByOrNull { it.clients.size } ?: newRoom(public = true)
            else -> rooms[code]
        }
        if (room == null) { sendTo(Codec.error(1), addr); return }
        if (!room.hasSpace()) { sendTo(Codec.error(2), addr); return }
        room.add(addr, token)          // WELCOME o'yin oqimida (jamoa aniq bo'lgach) yuboriladi
    }

    /** Client yuborgan HAR BIR input tekshiriladi: faqat input qabul qilinadi, pozitsiya emas. */
    private fun onInput(room: Room, c: Client, raw: Input) {
        val now = System.currentTimeMillis()
        c.seen = now
        if (!raw.mx.isFinite() || !raw.my.isFinite()) { violation(room, c, "NaN input"); return }
        if (raw.seq <= c.lastSeq) return                       // eski/takroriy paket (UDP reorder)
        if (c.lastSeq != 0 && raw.seq - c.lastSeq > 600) { violation(room, c, "seq jump"); return }
        if (now - c.winStart > 1000) {                         // 1s oyna: rate limit
            if (c.winCount <= C.MAX_INPUTS_PER_SEC && c.violations > 0) c.violations--
            c.winStart = now; c.winCount = 0
        }
        if (++c.winCount > C.MAX_INPUTS_PER_SEC) { violation(room, c, "input flood"); return }
        c.lastSeq = raw.seq
        if (c.queue.size < 8) {
            c.queue.add(Input(raw.seq, raw.mx.coerceIn(-1f, 1f), raw.my.coerceIn(-1f, 1f), raw.action.coerceIn(0, 2)))
        }
    }

    private fun violation(room: Room, c: Client, why: String) {
        c.violations++
        SLog.i("Server", "[${room.code}] violation id=${c.id}: $why (${c.violations})")
        if (c.violations >= C.KICK_AFTER_VIOLATIONS) {
            room.remove(c)
            (c.addr as? InetSocketAddress)?.address?.let { banned[it] = System.currentTimeMillis() + C.BAN_MS }
            SLog.i("Server", "[${room.code}] KICK id=${c.id}")
        }
    }

    private fun isBanned(addr: SocketAddress): Boolean {
        val ip = (addr as? InetSocketAddress)?.address ?: return false
        val until = banned[ip] ?: return false
        if (System.currentTimeMillis() > until) { banned.remove(ip); return false }
        return true
    }
}
