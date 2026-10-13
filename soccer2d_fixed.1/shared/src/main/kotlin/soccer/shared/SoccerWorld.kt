package soccer.shared

import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.physics.box2d.*
import kotlin.math.hypot

/**
 * Server va client bir xil kodni ishlatadi.
 * withBall = true  -> server (to'p + gol)
 * withBall = false -> client prediction (faqat o'z o'yinchisi)
 */
class SoccerWorld(private val withBall: Boolean) {
    val world = World(Vector2(0f, 0f), true)
    val players = HashMap<Int, Body>()
    private val kickCd = IntArray(C.MAX_ID)     // boxing/allocation yo'q
    private val teamOf = IntArray(C.MAX_ID)
    private val slotOf = IntArray(C.MAX_ID)     // jamoadagi o'rin: 0 yoki 1
    private val ready = BooleanArray(C.MAX_ID)
    var ball: Body? = null
    var scoreA = 0
    var scoreB = 0
    var tick = 0
    @Volatile var state = State.LOBBY
    var stateTicks = 0
    var matchSeconds = C.MATCH_S            // xona o'yin vaqti (sekund)
    var matchTicks = C.MATCH_S * 60
    private var ballStuckTicks = 0

    // ---- bot AI uchun umumiy holat ----
    /** To'pni "olib yurayotgan" bot id'si (yo'q bo'lsa -1). Rakib tegsa yoki bot pas/zarba bersa bo'shaydi. */
    var carrierId = -1
    val noGrabUntil = IntArray(C.MAX_ID)      // bu tikkacha bot to'pni qayta ola olmaydi (pasdan/olib qo'yilgandan keyin)
    val noTackleUntil = IntArray(C.MAX_ID)    // to'pdan ayrilgan bot bu tikkacha to'pni qayta tortib ololmaydi
    val plans = arrayOf(TeamPlan(), TeamPlan())

    init {
        buildWalls()
        if (withBall) buildBall()
    }

    private fun buildWalls() {
        val body = world.createBody(BodyDef())
        fun edge(x1: Float, y1: Float, x2: Float, y2: Float) {
            val s = EdgeShape(); s.set(x1, y1, x2, y2)
            val fd = FixtureDef(); fd.shape = s; fd.restitution = 0.8f; fd.friction = 0f
            body.createFixture(fd); s.dispose()
        }
        val w = C.FIELD_W; val h = C.FIELD_H; val d = C.GOAL_DEPTH
        val g1 = (h - C.GOAL_W) / 2f; val g2 = g1 + C.GOAL_W
        // burchaklari kesilgan maydon: to'p va o'yinchi 90 gradusli burchakda tiqilib qolmaydi
        val c = C.CORNER_CUT
        edge(c, 0f, w - c, 0f); edge(c, h, w - c, h)
        edge(0f, c, 0f, g1); edge(0f, g2, 0f, h - c)
        edge(w, c, w, g1); edge(w, g2, w, h - c)
        edge(0f, c, c, 0f); edge(w - c, 0f, w, c)
        edge(0f, h - c, c, h); edge(w - c, h, w, h - c)
        // darvoza cho'ntaklari
        edge(0f, g1, -d, g1); edge(-d, g1, -d, g2); edge(-d, g2, 0f, g2)
        edge(w, g1, w + d, g1); edge(w + d, g1, w + d, g2); edge(w + d, g2, w, g2)
    }

    private fun buildBall() {
        val bd = BodyDef(); bd.type = BodyDef.BodyType.DynamicBody
        bd.bullet = true; bd.linearDamping = 0.8f
        bd.position.set(C.FIELD_W / 2f, C.FIELD_H / 2f)
        val b = world.createBody(bd)
        val s = CircleShape(); s.radius = C.BALL_R
        val fd = FixtureDef(); fd.shape = s; fd.density = 0.5f; fd.restitution = 0.7f; fd.friction = 0f
        b.createFixture(fd); s.dispose()
        ball = b
    }

    fun spawn(id: Int, team: Int, slot: Int): Vector2 {
        val x = if (team == 0) C.FIELD_W * 0.25f else C.FIELD_W * 0.75f
        val y = C.FIELD_H / 2f + (slot - 0.5f) * 5f      // jamoadagi o'rin: ustma-ust tushmaydi
        return Vector2(x, y)
    }

    /** Jamoadagi bo'sh birinchi o'rin (yo'q bo'lsa -1) */
    fun firstFreeSlot(team: Int): Int {
        for (s in 0 until C.TEAM_SIZE) if (!slotTaken(team, s, -1)) return s
        return -1
    }

    /** Shu jamoa/o'rinni boshqa (except bo'lmagan) o'yinchi band qilganmi */
    fun slotTaken(team: Int, slot: Int, except: Int): Boolean {
        for (id in 0 until C.MAX_ID)
            if (id != except && players.containsKey(id) && teamOf[id] == team && slotOf[id] == slot) return true
        return false
    }

    /** Shu o'rindagi o'yinchi id'si (yo'q bo'lsa -1) */
    fun occupant(team: Int, slot: Int): Int {
        for (id in 0 until C.MAX_ID)
            if (players.containsKey(id) && teamOf[id] == team && slotOf[id] == slot) return id
        return -1
    }

    fun slotOfId(id: Int) = if (id in 0 until C.MAX_ID) slotOf[id] else 0

    fun addPlayer(id: Int, team: Int, slot: Int = firstFreeSlot(team)) {
        if (id !in 0 until C.MAX_ID || players.containsKey(id)) return
        if (slot !in 0 until C.TEAM_SIZE || slotTaken(team, slot, id)) return
        val bd = BodyDef(); bd.type = BodyDef.BodyType.DynamicBody
        bd.fixedRotation = true; bd.linearDamping = 5f
        bd.position.set(spawn(id, team, slot))
        val b = world.createBody(bd)
        val s = CircleShape(); s.radius = C.PLAYER_R
        val fd = FixtureDef(); fd.shape = s; fd.density = 1f; fd.restitution = 0.2f; fd.friction = 0f
        b.createFixture(fd); s.dispose()
        players[id] = b; kickCd[id] = 0; teamOf[id] = team; slotOf[id] = slot; ready[id] = false
    }

    fun removePlayer(id: Int) {
        players.remove(id)?.let { world.destroyBody(it) }
        kickCd[id] = 0; ready[id] = false
        if (carrierId == id) carrierId = -1
    }

    /**
     * Inson harakati. action = SHOOT/PASS, power = KickPower darajasi (0..3).
     * SHOOT: yo'nalish - joystick (aniq bo'lsa), aks holda o'yinchidan to'pga. Quvvat impulsga ko'paytiriladi.
     * PASS: jamoadoshga (joystick yo'nalishiga qarab), quvvat tezlikka ko'paytiriladi.
     */
    fun applyInput(id: Int, mx: Float, my: Float, action: Int, power: Int = 0) {
        val p = players[id] ?: return
        var dx = mx; var dy = my
        val len = hypot(dx, dy)
        if (len > 1f) { dx /= len; dy /= len }
        val v = p.linearVelocity
        p.setLinearVelocity(
            v.x + (dx * C.MAX_SPEED - v.x) * 0.25f,
            v.y + (dy * C.MAX_SPEED - v.y) * 0.25f
        )
        val ball = ball
        if (action != Act.NONE && ball != null && kickCd[id] == 0) {
            val bx = ball.position.x - p.position.x
            val by = ball.position.y - p.position.y
            val dist = hypot(bx, by)
            if (dist < C.KICK_RANGE && dist > 0.001f) {
                val lvl = power.coerceIn(0, 3)
                if (action == Act.SHOOT) {
                    val aiming = hypot(dx, dy) > 0.3f
                    val ax = if (aiming) dx / hypot(dx, dy) else bx / dist
                    val ay = if (aiming) dy / hypot(dx, dy) else by / dist
                    val imp = C.KICK_IMPULSE * KickPower.SHOOT[lvl]
                    ball.applyLinearImpulse(
                        ax * imp, ay * imp,
                        ball.worldCenter.x, ball.worldCenter.y, true
                    )
                } else pass(id, p, ball, dx, dy, KickPower.PASS[lvl])
                kickCd[id] = C.KICK_COOLDOWN_TICKS
            }
        }
    }

    /** Uzatma: jostick yo'nalishiga eng mos sherik (yo'nalish bo'lmasa eng yaqini). Sherik bo'lmasa - oldinga. */
    private fun pass(id: Int, p: Body, ball: Body, aimX: Float, aimY: Float, k: Float) {
        val aiming = hypot(aimX, aimY) > 0.3f
        var best: Body? = null
        var bestScore = -1e9f
        for ((oid, ob) in players) {
            if (oid == id || teamOf[oid] != teamOf[id]) continue
            val tx = ob.position.x - ball.position.x
            val ty = ob.position.y - ball.position.y
            val dist = hypot(tx, ty).coerceAtLeast(0.1f)
            val score = if (aiming) (tx * aimX + ty * aimY) / dist * 10f - dist * 0.05f else -dist
            if (score > bestScore) { bestScore = score; best = ob }
        }
        val dirX: Float; val dirY: Float; val speed: Float
        if (best != null) {
            val tx = best.position.x - ball.position.x
            val ty = best.position.y - ball.position.y
            val dist = hypot(tx, ty).coerceAtLeast(0.1f)
            dirX = tx / dist; dirY = ty / dist
            speed = ((dist + 2f) * k).coerceIn(4f, 14f)      // sherikka yetib boradigan tezlik, quvvatga bog'liq
        } else {
            var fx = if (aiming) aimX else ball.position.x - p.position.x
            var fy = if (aiming) aimY else ball.position.y - p.position.y
            val fl = hypot(fx, fy).coerceAtLeast(0.001f)
            dirX = fx / fl; dirY = fy / fl; speed = 7f * k
        }
        ball.setLinearVelocity(dirX * speed, dirY * speed)
    }

    /** Qat'iy fixed timestep. Gdx.graphics.deltaTime ISHLATILMAYDI. */
    fun step() {
        tick++
        for (i in kickCd.indices) if (kickCd[i] > 0) kickCd[i]--
        val b = ball
        if (b == null) { world.step(C.DT, 8, 3); return }   // client prediction

        // ---- Match state machine (faqat server) ----
        when (state) {
            State.LOBBY -> if (canStart()) {
                scoreA = 0; scoreB = 0; matchTicks = matchSeconds * 60
                startCountdown(C.COUNTDOWN_S)
            }
            State.COUNTDOWN -> if (--stateTicks <= 0) state = State.PLAYING
            State.PLAYING -> {
                if (players.size < C.MIN_PLAYERS) { backToLobby(); return }
                world.step(C.DT, 8, 3)
                enforceLimits(b)
                unstickBall(b)
                val over = --matchTicks <= 0
                val goalB = b.position.x < 0f
                val goalA = b.position.x > C.FIELD_W
                if (goalB) scoreB++ else if (goalA) scoreA++      // oxirgi tikdagi gol ham sanaladi
                if (over) { state = State.END; stateTicks = C.END_S * 60 }
                else if (goalA || goalB) goalScored()
            }
            State.GOAL -> if (--stateTicks <= 0) startCountdown(2)
            State.END -> if (--stateTicks <= 0) backToLobby()
        }
    }

    /** Anti-cheat / sanity: tezlik va pozitsiya chegaralari (faqat server). */
    private fun enforceLimits(ball: Body) {
        val maxP = C.MAX_SPEED * C.SPEED_TOLERANCE
        val m = C.GOAL_DEPTH + 1f
        for ((id, p) in players) {
            val v = p.linearVelocity
            val sp = hypot(v.x, v.y)
            if (sp > maxP) p.setLinearVelocity(v.x / sp * maxP, v.y / sp * maxP)
            val pos = p.position
            if (pos.x < -m || pos.x > C.FIELD_W + m || pos.y < -1f || pos.y > C.FIELD_H + 1f ||
                pos.x.isNaN() || pos.y.isNaN()) {
                p.setTransform(spawn(id, teamOf[id], slotOf[id]), 0f); p.setLinearVelocity(0f, 0f)
            }
        }
        val bv = ball.linearVelocity
        val bs = hypot(bv.x, bv.y)
        if (bs > C.BALL_MAX_SPEED) ball.setLinearVelocity(bv.x / bs * C.BALL_MAX_SPEED, bv.y / bs * C.BALL_MAX_SPEED)
    }

    // ---- jamoa tanlash (Select Teams) ----
    fun teamOfId(id: Int) = teamOf[id]

    fun kickReady(id: Int) = id in 0 until C.MAX_ID && kickCd[id] == 0

    /**
     * Bot zarbasi/pasi: yo'nalish va tezlikni bot o'zi belgilaydi (inson xatosi - aniqlik - BotBrain'da).
     * To'p yetarlicha yaqin bo'lsa va zarba tiklanishi tugagan bo'lsa ishlaydi.
     */
    fun botKick(id: Int, dx: Float, dy: Float, speed: Float): Boolean {
        val p = players[id] ?: return false
        val b = ball ?: return false
        if (kickCd[id] != 0) return false
        if (hypot(b.position.x - p.position.x, b.position.y - p.position.y) > C.KICK_RANGE + 0.35f) return false
        val l = hypot(dx, dy).coerceAtLeast(0.001f)
        b.setLinearVelocity(dx / l * speed, dy / l * speed)
        kickCd[id] = C.KICK_COOLDOWN_TICKS
        if (carrierId == id) carrierId = -1
        noGrabUntil[id] = tick + 18
        return true
    }

    fun teamCount(team: Int): Int {
        var n = 0
        for (id in 0 until C.MAX_ID) if (players.containsKey(id) && teamOf[id] == team) n++
        return n
    }

    /** O'rinni egallash (jamoa + o'rin). Band o'rinni olib bo'lmaydi. */
    fun setSeat(id: Int, team: Int, slot: Int): Boolean {
        val body = players[id] ?: return false
        if (team !in 0..1 || slot !in 0 until C.TEAM_SIZE) return false
        if (teamOf[id] == team && slotOf[id] == slot) return true
        if (slotTaken(team, slot, id)) return false
        teamOf[id] = team; slotOf[id] = slot; ready[id] = false
        body.setTransform(spawn(id, team, slot), 0f)
        body.setLinearVelocity(0f, 0f)
        return true
    }

    fun setReady(id: Int, r: Boolean) { if (players.containsKey(id)) ready[id] = r }
    fun isReady(id: Int) = id in 0 until C.MAX_ID && ready[id]

    /** Match boshlanishi: ikkala jamoada kamida 1 o'yinchi va hamma START bosgan */
    private fun canStart(): Boolean {
        var t0 = 0; var t1 = 0
        for (id in 0 until C.MAX_ID) {
            if (!players.containsKey(id)) continue
            if (!ready[id]) return false
            if (teamOf[id] == 0) t0++ else t1++
        }
        return t0 >= 1 && t1 >= 1
    }

    /** To'p devor/burchak yonida tiqilib qolsa (tezligi past) - maydon ichiga qaytariladi */
    private fun unstickBall(b: Body) {
        val bx = b.position.x; val by = b.position.y
        val g1 = (C.FIELD_H - C.GOAL_W) / 2f
        val m = 2.8f
        val inMouth = by > g1 && by < g1 + C.GOAL_W          // darvoza oldida to'p turishi - normal
        val near = by < m || by > C.FIELD_H - m || ((bx < m || bx > C.FIELD_W - m) && !inMouth)
        val v = b.linearVelocity
        if (near && hypot(v.x, v.y) < 0.9f) ballStuckTicks++ else ballStuckTicks = 0
        if (ballStuckTicks > C.BALL_STUCK_TICKS) {
            ballStuckTicks = 0
            b.setTransform(bx.coerceIn(4.5f, C.FIELD_W - 4.5f), by.coerceIn(4.5f, C.FIELD_H - 4.5f), 0f)
            b.setLinearVelocity(0f, 0f)
        }
    }

    val playing get() = state == State.PLAYING

    fun timerSeconds(): Int {
        if (state == State.LOBBY) return matchSeconds     // lobby'da: o'yin vaqti (klient ko'rsatadi)
        val t = if (state == State.PLAYING) matchTicks else stateTicks
        return (t + 59) / 60
    }

    private fun startCountdown(sec: Int) {
        resetBall(); state = State.COUNTDOWN; stateTicks = sec * 60
    }

    private fun goalScored() { state = State.GOAL; stateTicks = C.GOAL_PAUSE_S * 60 }

    /** Qayta o'ynash: jamoalar saqlanadi, hisob nolga tushadi, sanash boshlanadi */
    fun restartMatch() {
        for (i in ready.indices) ready[i] = false
        scoreA = 0; scoreB = 0; matchTicks = matchSeconds * 60
        startCountdown(C.COUNTDOWN_S)
    }

    fun backToLobby() {
        resetBall(); scoreA = 0; scoreB = 0; state = State.LOBBY
        for (i in ready.indices) ready[i] = false      // yangi match: qaytadan tayyor bo'lish kerak
    }

    fun resetBall() {
        val b = ball ?: return
        carrierId = -1
        b.setTransform(C.FIELD_W / 2f, C.FIELD_H / 2f, 0f)
        b.setLinearVelocity(0f, 0f)
        for ((id, body) in players) {
            body.setTransform(spawn(id, teamOf[id], slotOf[id]), 0f)
            body.setLinearVelocity(0f, 0f)
        }
    }
}
