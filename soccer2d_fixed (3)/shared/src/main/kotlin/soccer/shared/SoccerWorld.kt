package soccer.shared

import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.physics.box2d.*
import kotlin.math.hypot

/**
 * Server va client bir xil kodni ishlatadi.
 * withBall = true  -> server (to'p + gol)
 * withBall = false -> client prediction (faqat o'z o'yinchisi)
 */
/** Gol hodisasi: team = golni urgan (hujumchi) jamoa; scorerId -1 bo'lsa noma'lum */
class GoalEvent(val team: Int, val scorerId: Int, val own: Boolean, val minute: Int)

class SoccerWorld(private val withBall: Boolean) {
    val world = World(Vector2(0f, 0f), true)
    val players = HashMap<Int, Body>()
    private val kickCd = IntArray(C.MAX_ID)     // boxing/allocation yo'q
    private val teamOf = IntArray(C.MAX_ID)
    private val slotOf = IntArray(C.MAX_ID)     // jamoadagi o'rin: 0 yoki 1
    private val faceX = FloatArray(C.MAX_ID) { 1f }   // o'yinchi qaragan yo'nalish (to'p shu tomonda turadi)
    private val faceY = FloatArray(C.MAX_ID)
    private val ready = BooleanArray(C.MAX_ID)
    var ball: Body? = null
    var scoreA = 0
    var scoreB = 0
    var tick = 0
    @Volatile var state = State.LOBBY
    var stateTicks = 0
    var matchSeconds = C.MATCH_S            // xona o'yin vaqti (sekund)
    var matchTicks = C.MATCH_S * 60
    /** Durang bo'lsa: vaqt tugagach ham o'yin davom etadi, birinchi gol g'alaba (oltin gol) */
    var overtime = false
    /** Oxirgi to'pga tegkan o'yinchi (gol kimga yozilishi uchun) */
    var lastTouch = -1
    /** Yangi gol bo'lsa - server buni o'qib, klientlarga yuboradi, so'ng null qiladi */
    var goalEvent: GoalEvent? = null
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
        fun chain(pts: FloatArray) {
            val cs = com.badlogic.gdx.physics.box2d.ChainShape()
            cs.createChain(pts)
            val fd = FixtureDef(); fd.shape = cs; fd.restitution = 0.8f; fd.friction = 0f
            body.createFixture(fd); cs.dispose()
        }
        // yumaloq burchaklar: har burchak - yarim doira (ChainShape, ichki qirralarsiz)
        val c = C.CORNER_CUT
        edge(c, 0f, w - c, 0f); edge(c, h, w - c, h)
        edge(0f, c, 0f, g1); edge(0f, g2, 0f, h - c)
        edge(w, c, w, g1); edge(w, g2, w, h - c)
        chain(cornerArc(c, c, c, 180f, 270f)); chain(cornerArc(w - c, c, c, 270f, 360f))
        chain(cornerArc(w - c, h - c, c, 0f, 90f)); chain(cornerArc(c, h - c, c, 90f, 180f))
        // darvoza cho'ntaklari
        edge(0f, g1, -d, g1); edge(-d, g1, -d, g2); edge(-d, g2, 0f, g2)
        edge(w, g1, w + d, g1); edge(w + d, g1, w + d, g2); edge(w + d, g2, w, g2)
    }

    /** Burchak yoyi: (cx,cy) markazdan r radius, a0..a1 gradusda, 8 segment */
    private fun cornerArc(cx: Float, cy: Float, r: Float, a0: Float, a1: Float): FloatArray {
        val n = 8
        val out = FloatArray((n + 1) * 2)
        for (i in 0..n) {
            val a = Math.toRadians((a0 + (a1 - a0) * i / n).toDouble())
            out[i * 2] = cx + r * kotlin.math.cos(a).toFloat()
            out[i * 2 + 1] = cy + r * kotlin.math.sin(a).toFloat()
        }
        return out
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
                val aiming = hypot(dx, dy) > 0.3f
                if (action == Act.SHOOT) humanShoot(id, p, ball, dx, dy, aiming, KickPower.SHOOT[lvl])
                else humanPass(id, p, ball, dx, dy, aiming, KickPower.PASS[lvl])
            }
        }
    }

    /** Zarba/pasning yagona ijrosi: tezlik to'g'ridan-to'g'ri beriladi (botlar bilan bir xil, oldindan aytiladigan natija). */
    private fun kickBall(id: Int, b: Body, dx: Float, dy: Float, speed: Float) {
        val l = hypot(dx, dy).coerceAtLeast(0.001f)
        b.setLinearVelocity(dx / l * speed, dy / l * speed)
        if (carrierId == id) carrierId = -1
        noGrabUntil[id] = tick + 18
        kickCd[id] = C.KICK_COOLDOWN_TICKS
        lastTouch = id
    }

    /**
     * Zarba. Yo'nalish: joystik (aniq bo'lsa), aks holda to'pga qarab.
     * Gol yordami: yo'nalish raqib darvozasiga ~22 gradus ichida bo'lsa, to'p darvoza markaziga tortiladi.
     * Tezlik quvvatga bog'liq: yengil ~14, kuchli ~30 (maks).
     */
    private fun humanShoot(id: Int, p: Body, b: Body, aimX: Float, aimY: Float, aiming: Boolean, k: Float) {
        var dx = if (aiming) aimX else b.position.x - p.position.x
        var dy = if (aiming) aimY else b.position.y - p.position.y
        val attackX = if (teamOf[id] == 0) C.FIELD_W else 0f
        val gx = attackX - b.position.x
        val gy = C.FIELD_H / 2f - b.position.y
        val gl = hypot(gx, gy)
        val l = hypot(dx, dy).coerceAtLeast(0.001f)
        if (gl > 0.5f && gl < 14f) {
            val cos = (dx * gx + dy * gy) / (l * gl)
            if (cos > 0.927f) { dx = gx; dy = gy }          // ~22 gradus ichida: darvozaga tortadi
        }
        kickBall(id, b, dx, dy, (18f * k).coerceAtMost(30f))
    }

    /**
     * Pas. Sherik tanlash: joystik yo'nalishiga yaqin va yo'li ochiq sherik.
     * Sherikning harakatini hisobga oladi (kelish nuqtasiga qarab uzatadi). Yo'lda raqib bo'lsa - ball pastroq.
     * Tezlik masofaga va quvvatga bog'liq. Sherik bo'lmasa - joystik yo'nalishi bo'ylab.
     */
    private fun humanPass(id: Int, p: Body, b: Body, aimX: Float, aimY: Float, aiming: Boolean, k: Float) {
        val myTeam = teamOf[id]
        val passSpeed = (14f * k).coerceIn(8f, 26f)
        var bestX = 0f; var bestY = 0f; var bestScore = -1e9f; var found = false
        for ((oid, ob) in players) {
            if (oid == id || teamOf[oid] != myTeam) continue
            val d0 = hypot(ob.position.x - b.position.x, ob.position.y - b.position.y)
            val t = d0 / passSpeed                                   // uzatma yetib borguncha
            val tx = ob.position.x + ob.linearVelocity.x * t * 0.8f  // sherik kelish nuqtasi
            val ty = ob.position.y + ob.linearVelocity.y * t * 0.8f
            val dx = tx - b.position.x; val dy = ty - b.position.y
            val dist = hypot(dx, dy).coerceAtLeast(0.1f)
            var score = if (aiming) (dx * aimX + dy * aimY) / dist * 10f - dist * 0.05f else -dist
            if (laneBlocked(myTeam, b.position.x, b.position.y, tx, ty)) score -= 6f
            if (score > bestScore) { bestScore = score; bestX = tx; bestY = ty; found = true }
        }
        if (found) {
            val dx = bestX - b.position.x; val dy = bestY - b.position.y
            val dist = hypot(dx, dy).coerceAtLeast(0.1f)
            kickBall(id, b, dx, dy, ((dist + 2f) * k).coerceIn(6f, 24f))
        } else {
            val dx = if (aiming) aimX else b.position.x - p.position.x
            val dy = if (aiming) aimY else b.position.y - p.position.y
            kickBall(id, b, dx, dy, 9f * k)
        }
    }

    /** Pas yo'lida (B -> T) raqib bormi */
    private fun laneBlocked(myTeam: Int, bx: Float, by: Float, tx: Float, ty: Float): Boolean {
        val vx = tx - bx; val vy = ty - by
        val len2 = (vx * vx + vy * vy).coerceAtLeast(0.001f)
        for ((oid, ob) in players) {
            if (teamOf[oid] == myTeam) continue
            val ox = ob.position.x - bx; val oy = ob.position.y - by
            val t = ((ox * vx + oy * vy) / len2).coerceIn(0f, 1f)
            val cx = bx + vx * t; val cy = by + vy * t
            if (hypot(ob.position.x - cx, ob.position.y - cy) < 1.4f) return true
        }
        return false
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
                scoreA = 0; scoreB = 0; matchTicks = matchSeconds * 60; overtime = false
                startCountdown(C.COUNTDOWN_S)
            }
            State.COUNTDOWN -> if (--stateTicks <= 0) state = State.PLAYING
            State.PLAYING -> {
                if (players.size < C.MIN_PLAYERS) { backToLobby(); return }
                world.step(C.DT, 8, 3)
                enforceLimits(b)
                updatePossession(b)
                unstickBall(b)
                if (!overtime) matchTicks--
                val goalB = b.position.x < 0f
                val goalA = b.position.x > C.FIELD_W
                val goal = goalA || goalB
                if (goal) { if (goalB) scoreB++ else scoreA++; recordGoal(goalA) }   // oxirgi tikdagi gol ham sanaladi
                if (overtime && goal) { endMatch(); return }        // oltin gol: overtime shu zahoti tugaydi
                if (!overtime && matchTicks <= 0) {
                    if (scoreA != scoreB) { endMatch(); return }
                    overtime = true                                 // durang: birinchi gol bilan hal bo'ladi
                }
                if (goal) goalScored()
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
        lastTouch = id
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
    /**
     * To'pni ushlab olish: o'yinchi to'pga yaqin bo'lsa, to'p uning oldida "ushlanadi" va u bilan birga harakatlanadi.
     * Raqib yaqinlashsa to'pni olishi mumkin. Zarba yoki pas bilan ushlanish tugaydi.
     */
    private fun updatePossession(b: Body) {
        val grab = C.PLAYER_R + C.BALL_R + 0.3f
        val c = carrierId
        if (c >= 0) {
            val p = players[c]
            if (p == null || hypot(b.position.x - p.position.x, b.position.y - p.position.y) > C.KICK_RANGE + 0.6f) {
                carrierId = -1
            } else {
                var stolen = false
                for ((oid, ob) in players) {
                    if (oid == c || teamOf[oid] == teamOf[c] || tick < noGrabUntil[oid]) continue
                    if (hypot(b.position.x - ob.position.x, b.position.y - ob.position.y) < grab) { stolen = true; break }
                }
                if (!stolen) {
                    val v = p.linearVelocity
                    val sp = hypot(v.x, v.y)
                    if (sp > 0.6f) { faceX[c] = v.x / sp; faceY[c] = v.y / sp }
                    val cd = C.PLAYER_R + C.BALL_R + 0.14f
                    b.setTransform(p.position.x + faceX[c] * cd, p.position.y + faceY[c] * cd, 0f)
                    b.setLinearVelocity(v.x, v.y)
                    return
                }
                carrierId = -1                       // raqib oldi - to'p erkin
            }
        }
        var best = -1
        var bestD = grab
        for ((id, p) in players) {
            if (tick < noGrabUntil[id]) continue
            val d = hypot(b.position.x - p.position.x, b.position.y - p.position.y)
            if (d < bestD) { bestD = d; best = id }
        }
        if (best >= 0) {
            val p = players[best]!!
            val dx = b.position.x - p.position.x; val dy = b.position.y - p.position.y
            val l = hypot(dx, dy).coerceAtLeast(0.001f)
            faceX[best] = dx / l; faceY[best] = dy / l
            carrierId = best
            lastTouch = best
        }
    }

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

    /** Gol kimga yozilishi: oxirgi tegkan o'yinchi. Raqib jamoasining o'yinchisi bo'lsa - own goal. */
    private fun recordGoal(goalA: Boolean) {
        val team = if (goalA) 0 else 1          // o'ng darvoza -> team 0 hujum qilgan
        val lt = lastTouch
        val known = lt >= 0 && players.containsKey(lt)
        val minute = (matchSeconds * 60 - matchTicks) / 3600
        goalEvent = GoalEvent(team, if (known) lt else -1, known && teamOf[lt] != team, minute.coerceIn(0, 99))
    }

    private fun endMatch() {
        state = State.END; stateTicks = C.END_S * 60; overtime = false
        for (i in ready.indices) ready[i] = false
    }

    private fun goalScored() { state = State.GOAL; stateTicks = C.GOAL_PAUSE_S * 60 }

    /** Qayta o'ynash: jamoalar saqlanadi, hisob nolga tushadi, sanash boshlanadi */
    fun restartMatch() {
        for (i in ready.indices) ready[i] = false
        scoreA = 0; scoreB = 0; matchTicks = matchSeconds * 60; overtime = false
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
