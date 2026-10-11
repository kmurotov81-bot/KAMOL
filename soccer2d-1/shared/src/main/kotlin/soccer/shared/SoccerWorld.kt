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
    private val ready = BooleanArray(C.MAX_ID)
    var ball: Body? = null
    var scoreA = 0
    var scoreB = 0
    var tick = 0
    var state = State.LOBBY
    var stateTicks = 0
    var matchTicks = C.MATCH_S * 60

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
        edge(0f, 0f, w, 0f); edge(0f, h, w, h)
        edge(0f, 0f, 0f, g1); edge(0f, g2, 0f, h)
        edge(w, 0f, w, g1); edge(w, g2, w, h)
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

    fun spawn(id: Int, team: Int): Vector2 {
        val x = if (team == 0) C.FIELD_W * 0.25f else C.FIELD_W * 0.75f
        val y = C.FIELD_H / 2f + ((id % 5) - 2) * 2f
        return Vector2(x, y)
    }

    fun addPlayer(id: Int, team: Int) {
        if (id !in 0 until C.MAX_ID || players.containsKey(id)) return
        val bd = BodyDef(); bd.type = BodyDef.BodyType.DynamicBody
        bd.fixedRotation = true; bd.linearDamping = 5f
        bd.position.set(spawn(id, team))
        val b = world.createBody(bd)
        val s = CircleShape(); s.radius = C.PLAYER_R
        val fd = FixtureDef(); fd.shape = s; fd.density = 1f; fd.restitution = 0.2f; fd.friction = 0f
        b.createFixture(fd); s.dispose()
        players[id] = b; kickCd[id] = 0; teamOf[id] = team; ready[id] = false
    }

    fun removePlayer(id: Int) {
        players.remove(id)?.let { world.destroyBody(it) }
        kickCd[id] = 0; ready[id] = false
    }

    fun applyInput(id: Int, mx: Float, my: Float, action: Int) {
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
                if (action == Act.SHOOT) {
                    ball.applyLinearImpulse(
                        bx / dist * C.KICK_IMPULSE, by / dist * C.KICK_IMPULSE,
                        ball.worldCenter.x, ball.worldCenter.y, true
                    )
                } else pass(id, p, ball, dx, dy)
                kickCd[id] = C.KICK_COOLDOWN_TICKS
            }
        }
    }

    /** Uzatma: jostick yo'nalishiga eng mos sherik (yo'nalish bo'lmasa eng yaqini). Sherik bo'lmasa - oldinga. */
    private fun pass(id: Int, p: Body, ball: Body, aimX: Float, aimY: Float) {
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
            speed = (dist + 2f).coerceIn(4f, 12f)             // sherikka yetib boradigan tezlik
        } else {
            var fx = if (aiming) aimX else ball.position.x - p.position.x
            var fy = if (aiming) aimY else ball.position.y - p.position.y
            val fl = hypot(fx, fy).coerceAtLeast(0.001f)
            dirX = fx / fl; dirY = fy / fl; speed = 7f
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
                scoreA = 0; scoreB = 0; matchTicks = C.MATCH_S * 60
                startCountdown(C.COUNTDOWN_S)
            }
            State.COUNTDOWN -> if (--stateTicks <= 0) state = State.PLAYING
            State.PLAYING -> {
                if (players.size < C.MIN_PLAYERS) { backToLobby(); return }
                world.step(C.DT, 8, 3)
                enforceLimits(b)
                if (--matchTicks <= 0) { state = State.END; stateTicks = C.END_S * 60 }
                else if (b.position.x < 0f) { scoreB++; goalScored() }
                else if (b.position.x > C.FIELD_W) { scoreA++; goalScored() }
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
                p.setTransform(spawn(id, teamOf[id]), 0f); p.setLinearVelocity(0f, 0f)
            }
        }
        val bv = ball.linearVelocity
        val bs = hypot(bv.x, bv.y)
        if (bs > C.BALL_MAX_SPEED) ball.setLinearVelocity(bv.x / bs * C.BALL_MAX_SPEED, bv.y / bs * C.BALL_MAX_SPEED)
    }

    // ---- jamoa tanlash (Select Teams) ----
    fun teamCount(team: Int): Int {
        var n = 0
        for (id in 0 until C.MAX_ID) if (players.containsKey(id) && teamOf[id] == team) n++
        return n
    }

    /** Jamoa to'lgan bo'lsa (TEAM_SIZE) false qaytaradi - boshqa o'yinchi u jamoani tanlay olmaydi. */
    fun setTeam(id: Int, team: Int): Boolean {
        val body = players[id] ?: return false
        if (team !in 0..1) return false
        if (teamOf[id] == team) return true
        if (teamCount(team) >= C.TEAM_SIZE) return false
        teamOf[id] = team
        ready[id] = false
        body.setTransform(spawn(id, team), 0f)
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

    val playing get() = state == State.PLAYING

    fun timerSeconds(): Int {
        val t = if (state == State.PLAYING) matchTicks else stateTicks
        return (t + 59) / 60
    }

    private fun startCountdown(sec: Int) {
        resetBall(); state = State.COUNTDOWN; stateTicks = sec * 60
    }

    private fun goalScored() { state = State.GOAL; stateTicks = C.GOAL_PAUSE_S * 60 }

    private fun backToLobby() {
        resetBall(); scoreA = 0; scoreB = 0; state = State.LOBBY
        for (i in ready.indices) ready[i] = false      // yangi match: qaytadan tayyor bo'lish kerak
    }

    fun resetBall() {
        val b = ball ?: return
        b.setTransform(C.FIELD_W / 2f, C.FIELD_H / 2f, 0f)
        b.setLinearVelocity(0f, 0f)
        for ((id, body) in players) {
            body.setTransform(spawn(id, teamOf[id]), 0f)
            body.setLinearVelocity(0f, 0f)
        }
    }
}
