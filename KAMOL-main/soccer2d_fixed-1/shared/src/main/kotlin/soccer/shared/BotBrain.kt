package soccer.shared

import com.badlogic.gdx.physics.box2d.Body
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Jamoa bo'yicha har tikda bir marta hisoblanadigan umumiy vaziyat (hamma botlar bir xil rasmni ko'radi). */
class TeamPlan {
    var tick = -1
    var poss = LOOSE       // MINE / THEIRS / LOOSE (shu jamoa nuqtai nazaridan)
    var handler = -1       // to'pni boshqarayotgan o'yinchi (bot yoki odam), yo'q bo'lsa -1
    var chaser = -1        // erkin to'pga / to'p egasiga bosim qilishi kerak bo'lgan eng yaqin jamoadosh

    companion object { const val MINE = 0; const val THEIRS = 1; const val LOOSE = 2 }
}

private const val CARRY_DIST = C.PLAYER_R + C.BALL_R + 0.14f      // to'p bot oldida shu masofada "ushlab" turiladi
private const val TACKLE_BOT = 2f * C.PLAYER_R + 0.2f             // rakib bot egasining tanasiga tegdi
private const val TACKLE_BALL = C.PLAYER_R + C.BALL_R + 0.12f     // rakib to'pga tegdi
private const val GRAB_DIST = C.PLAYER_R + C.BALL_R + 0.3f        // to'pni olish masofasi
private const val SHOOT_Q = 0.42f                                  // zarba bahosi shundan yuqori bo'lsa o'zi uradi
private const val PASS_T = 0.30f                                   // pas bahosi shundan yuqori bo'lsa pas beradi

private const val R_NONE = -1
private const val R_CHASE = 0
private const val R_PRESS = 1
private const val R_SUPPORT = 2
private const val R_COVER = 3
private const val R_RUN = 4

/**
 * Realistik, muvozanatli bot (server ichida, o'yin holatini to'g'ridan-to'g'ri ko'radi).
 *
 *  - To'pga tegsa uni o'zi bilan olib yuradi (dribling) - rakib (bot yoki odam) tanasiga/to'pga tegmaguncha.
 *  - Zarba bahosini hisoblaydi (masofa, burchak, to'siqlar): gol uro'la olsa o'zi uradi, aks holda eng yaxshi
 *    joydagi jamoadoshga (bot yoki ODAM) pas beradi. Sherigi yaxshiroq vaziyatda bo'lsa - assist.
 *  - To'psiz: to'p jamoada bo'lsa - ochiq joyga chiqib pas varianti bo'ladi; yo'qotilsa - eng yaqin bosim qiladi,
 *    ikkinchisi darvoza tomonda yopib turadi.
 *  - Insoniy xatolar: reaksiya kechikishi, aniqlik xatosi (zarba/pas), og'ir teginish, ikkilanish, joylashuv xatosi.
 */
class BotBrain(private val id: Int, private val team: Int, skill: Float = 0.9f) {
    var mx = 0f
        private set
    var my = 0f
        private set
    var action = Act.NONE          // zarba/pas sim.botKick orqali bajariladi, shuning uchun doim NONE
        private set

    private val a = if (team == 0) 1f else -1f                 // hujum yo'nalishi (x o'qida)
    private val goalX = if (team == 0) C.FIELD_W else 0f       // rakib darvozasi
    private val ownX = C.FIELD_W - goalX                       // o'z darvozamiz

    // ---- "shaxsiyat": botlar bir xil emas ----
    private val topSpeed = (skill * (0.92f + 0.08f * Random.nextFloat())).coerceAtMost(0.95f)
    private val reactMin = 8 + Random.nextInt(5)               // reaksiya 0.13-0.3 s
    private val reactSpan = 6 + Random.nextInt(6)
    private val aimSd = 3.5f + Random.nextFloat() * 2f         // zarba aniqligi (gradus)
    private val passSd = 2.5f + Random.nextFloat() * 2f
    private val decisionEvery = 5 + Random.nextInt(3)

    // ---- holat ----
    private var role = R_NONE
    private var react = 0
    private var initDone = false
    private var tgtX = 0f
    private var tgtY = 0f
    private var retargetIn = 0
    private var decideIn = 0
    private var noiseX = 0f
    private var noiseY = 0f
    private var noiseIn = 0
    private var stuck = 0
    private var escape = 0
    private var escX = 0f
    private var escY = 0f
    private var freeze = 0                  // ikkilanish (tik)
    private var runTicks = 0                // pasdan keyin "give and go" yugurish
    private var protectUntil = 0            // to'pni olgandan keyin qisqa muddat himoya (ping-pong bo'lmasligi uchun)
    private var faceX = 1f
    private var faceY = 0f
    private var lastUx = 0f
    private var lastUy = 0f
    private var commit = false
    private var hasCtrl = false             // oxirgi tikda to'pga bergan tezligimiz (tashqi zarbani aniqlash uchun)
    private var ctrlVx = 0f
    private var ctrlVy = 0f
    private var commitSet = false

    // oraliq natijalar (allocation'siz)
    private var nx = 0f
    private var ny = 0f
    private var nsp = 0f
    private var segT = 0f
    private val tmp = FloatArray(2)
    private var pId = -1
    private var pTx = 0f
    private var pTy = 0f
    private var pScore = -9f
    private var pRisk = 0f
    private var pSq = 0f

    private fun rnd() = Random.nextFloat()
    private fun gauss() = (rnd() + rnd() + rnd() - 1.5f) / 0.5f     // taxminan N(0,1)

    private fun rot(x: Float, y: Float, deg: Float) {
        val r = deg * 0.0174533f; val c = cos(r); val s = sin(r)
        tmp[0] = x * c - y * s; tmp[1] = x * s + y * c
    }

    private fun distToSeg(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay
        val l2 = dx * dx + dy * dy
        val t = if (l2 < 1e-4f) 0f else ((px - ax) * dx + (py - ay) * dy) / l2
        segT = t
        val c = t.coerceIn(0f, 1f)
        return hypot(px - (ax + dx * c), py - (ay + dy * c))
    }

    // =====================================================================================
    fun think(sim: SoccerWorld) {
        val me = sim.players[id] ?: return
        val ball = sim.ball ?: return
        action = Act.NONE
        if (!sim.playing) { mx = 0f; my = 0f; stuck = 0; escape = 0; role = R_NONE; runTicks = 0; return }
        if (sim.carrierId >= 0 && !sim.players.containsKey(sim.carrierId)) sim.carrierId = -1

        val plan = plan(sim)
        if (sim.carrierId == id || tryGrab(sim, me, ball)) carry(sim, me, ball) else offBall(sim, me, ball, plan)

        if (freeze > 0) { freeze--; mx *= 0.25f; my *= 0.25f }      // ikkilanish: deyarli to'xtaydi
    }

    /** Jamoa vaziyati: kim to'pni boshqaryapti, kim bosim qilishi kerak (gisteresis bilan - rollar "titramaydi") */
    private fun plan(sim: SoccerWorld): TeamPlan {
        val p = sim.plans[team]
        if (p.tick == sim.tick) return p
        p.tick = sim.tick
        val ball = sim.ball!!
        val bx = ball.position.x; val by = ball.position.y
        val bs = hypot(ball.linearVelocity.x, ball.linearVelocity.y)

        var handler = -1
        val c = sim.carrierId
        if (c >= 0 && sim.players.containsKey(c)) handler = c
        else {
            var bd = 1.7f
            for ((oid, ob) in sim.players) {
                val d = hypot(ob.position.x - bx, ob.position.y - by)
                if (d < bd) { bd = d; handler = oid }
            }
            if (bs > 11f) handler = -1                      // tez uchib ketayotgan to'p - hech kimniki emas
        }
        val poss = if (handler < 0) TeamPlan.LOOSE else if (sim.teamOfId(handler) == team) TeamPlan.MINE else TeamPlan.THEIRS

        var tx = bx + ball.linearVelocity.x * 0.25f
        var ty = by + ball.linearVelocity.y * 0.25f
        if (poss == TeamPlan.THEIRS) sim.players[handler]?.let { tx = it.position.x; ty = it.position.y }
        var best = -1; var bestD = 1e9f
        for ((oid, ob) in sim.players) {
            if (sim.teamOfId(oid) != team) continue
            var d = hypot(ob.position.x - tx, ob.position.y - ty)
            if (oid == p.chaser) d -= 1.2f
            if (d < bestD) { bestD = d; best = oid }
        }
        p.poss = poss; p.handler = handler; p.chaser = best
        return p
    }

    // =====================================================================================
    //  TO'P BILAN
    // =====================================================================================

    /** Erkin to'pni "olish": faqat bahssiz, yetarlicha sekin va yaqin bo'lsa (birinchi teginish xatosi mumkin) */
    private fun tryGrab(sim: SoccerWorld, me: Body, ball: Body): Boolean {
        if (sim.carrierId != -1 || sim.tick < sim.noGrabUntil[id]) return false
        val px = me.position.x; val py = me.position.y
        val bx = ball.position.x; val by = ball.position.y
        val myD = hypot(bx - px, by - py)
        if (myD > GRAB_DIST) return false
        if (hypot(ball.linearVelocity.x - me.linearVelocity.x, ball.linearVelocity.y - me.linearVelocity.y) > 9.5f) return false
        for ((oid, ob) in sim.players) {
            if (oid == id) continue
            val d = hypot(ob.position.x - bx, ob.position.y - by)
            if (sim.teamOfId(oid) != team) { if (d < 1.4f) return false }               // rakib to'pga tegib turibdi: fizik kurash
            else if (d < GRAB_DIST && d < myD - 0.1f) return false                      // jamoadosh (odam ham) yaqinroq
        }
        if (rnd() > 0.6f) return false                                                  // reaksiya: 1-3 tik
        sim.carrierId = id
        protectUntil = sim.tick + 18
        hasCtrl = false
        val l = myD.coerceAtLeast(0.001f)
        faceX = (bx - px) / l; faceY = (by - py) / l
        lastUx = faceX; lastUy = faceY
        return true
    }

    private fun carry(sim: SoccerWorld, me: Body, ball: Body) {
        val px = me.position.x; val py = me.position.y
        val bx = ball.position.x; val by = ball.position.y

        var nearest = 99f
        var tackler = -1
        for ((oid, ob) in sim.players) {
            if (oid == id || sim.teamOfId(oid) == team) continue
            val dBot = hypot(ob.position.x - px, ob.position.y - py)
            val dBall = hypot(ob.position.x - bx, ob.position.y - by)
            nearest = min(nearest, dBot)
            if (tackler < 0 && sim.tick >= protectUntil && sim.tick >= sim.noTackleUntil[oid] &&
                (dBot < TACKLE_BOT || dBall < TACKLE_BALL)) tackler = oid
        }
        if (tackler >= 0) { dispossess(sim, ball, tackler); mx = 0f; my = 0f; return }      // rakib tegdi -> to'p ketdi
        // tashqi zarba (masalan, odam to'pni tepdi): to'p biz bergan tezlikdan keskin farq qilsa - nazorat yo'qoldi
        if (hasCtrl && hypot(ball.linearVelocity.x - ctrlVx, ball.linearVelocity.y - ctrlVy) > 4f) {
            sim.carrierId = -1; hasCtrl = false; sim.noGrabUntil[id] = sim.tick + 20
            mx = 0f; my = 0f; return
        }
        if (hypot(bx - px, by - py) > 2.4f) { sim.carrierId = -1; return }                  // to'p uzoqlashib ketdi

        val pressured = nearest < 3.2f
        if (--decideIn <= 0) {
            decideIn = if (pressured) 3 else decisionEvery
            if (decide(sim, me, ball, nearest, pressured)) return
        }
        dribble(sim, me, ball, pressured)
    }

    private fun dispossess(sim: SoccerWorld, ball: Body, tackler: Int) {
        sim.carrierId = -1
        sim.noGrabUntil[id] = sim.tick + 40
        sim.noTackleUntil[id] = sim.tick + 30
        val ob = sim.players[tackler] ?: return
        val dx = ball.position.x - ob.position.x; val dy = ball.position.y - ob.position.y
        val l = hypot(dx, dy).coerceAtLeast(0.01f)
        ball.setLinearVelocity(dx / l * 4.5f + ob.linearVelocity.x * 0.4f, dy / l * 4.5f + ob.linearVelocity.y * 0.4f)
    }

    /** Zarba bahosi 0..1: masofa, darvoza ko'rinish burchagi, yo'ldagi to'siqlar (rakiblar/darvozabon) */
    private fun shotQuality(sim: SoccerWorld, sx: Float, sy: Float): Float {
        val gy = C.FIELD_H / 2f
        val d = hypot(goalX - sx, gy - sy)
        val g1 = (C.FIELD_H - C.GOAL_W) / 2f; val g2 = g1 + C.GOAL_W
        val v1x = goalX - sx; val v1y = g1 - sy; val v2y = g2 - sy
        val n = hypot(v1x, v1y) * hypot(v1x, v2y)
        val ang = if (n < 1e-3f) 0f else acos(((v1x * v1x + v1y * v2y) / n).coerceIn(-1f, 1f))
        var q = 0.55f * ((16f - d) / 11f).coerceIn(0f, 1f) + 0.45f * (ang / 0.55f).coerceIn(0f, 1f)
        for ((oid, ob) in sim.players) {
            if (sim.teamOfId(oid) == team) continue
            val dist = distToSeg(ob.position.x, ob.position.y, sx, sy, goalX, gy)
            if (dist < 1.25f && segT > 0.05f && segT < 1f) {
                q -= if (hypot(ob.position.x - goalX, ob.position.y - gy) < 3.8f) 0.22f else 0.3f
            }
        }
        return q.coerceIn(0f, 1f)
    }

    /** Eng yaxshi pas varianti (jamoadosh: bot yoki odam). Natija pId/pTx/pTy/pScore/pRisk/pSq maydonlarida. */
    private fun findPass(sim: SoccerWorld, px: Float, py: Float) {
        pId = -1; pScore = -9f
        for ((oid, ob) in sim.players) {
            if (oid == id || sim.teamOfId(oid) != team) continue
            val lx = (ob.position.x + ob.linearVelocity.x * 0.4f).coerceIn(1.5f, C.FIELD_W - 1.5f)   // oldinga pas (lead)
            val ly = (ob.position.y + ob.linearVelocity.y * 0.4f).coerceIn(1.5f, C.FIELD_H - 1.5f)
            val dist = hypot(lx - px, ly - py)
            if (dist < 2.8f || dist > 24f) continue
            var risk = 0f; var open = 6f
            for ((eid, eb) in sim.players) {
                if (sim.teamOfId(eid) == team) continue
                val ex = eb.position.x; val ey = eb.position.y
                val d = distToSeg(ex, ey, px, py, lx, ly)
                if (d < 1.4f && segT > 0.05f && segT < 0.98f) risk += (1.4f - d) * 0.9f + 0.25f      // pas yo'lini to'sgan rakib
                open = min(open, hypot(ex - lx, ey - ly))
            }
            if (risk > 0.9f) continue
            val progress = ((lx - px) * a / 12f).coerceIn(-1f, 1f)
            val sq = shotQuality(sim, lx, ly)
            val human = oid < C.BOT_ID_BASE
            val score = 0.45f * progress + 0.30f * (open / 6f) + 0.55f * sq - 0.9f * risk - 0.015f * dist + (if (human) 0.08f else 0f)
            if (score > pScore) { pScore = score; pId = oid; pTx = lx; pTy = ly; pRisk = risk; pSq = sq }
        }
    }

    /** Qaror: zarba / pas / olib yurish. true = to'pga zarba berildi. */
    private fun decide(sim: SoccerWorld, me: Body, ball: Body, nearest: Float, pressured: Boolean): Boolean {
        if (freeze > 0) return false
        val px = me.position.x; val py = me.position.y
        val gy = C.FIELD_H / 2f
        val dGoal = hypot(goalX - px, gy - py)
        val q = shotQuality(sim, px, py)
        findPass(sim, px, py)
        val mate = pId >= 0

        var shoot = q >= SHOOT_Q && dGoal < 16f                                       // gol ura olsa - o'zi uradi
        if (!shoot && pressured && q >= 0.28f && dGoal < 12f) shoot = true             // bosim ostida - xavf qilib
        if (shoot && mate && pSq > q + 0.22f && pRisk < 0.35f) shoot = false           // sherigim ancha yaxshi vaziyatda - assist

        if (rnd() < 0.035f) { freeze = 6 + Random.nextInt(10); return false }          // ikkilanish
        if (shoot) return doShoot(sim, me, ball, dGoal, pressured)
        if (mate && ((pressured && pScore > -0.15f) || pScore > PASS_T)) return doPass(sim, me, ball)
        if (pressured && nearest < 1.9f && hypot(px - ownX, py - gy) < 7f) {           // o'z darvozamiz oldida - tozalash
            return sim.botKick(id, a, (py - gy) * 0.2f, 11f)
        }
        return false
    }

    private fun doShoot(sim: SoccerWorld, me: Body, ball: Body, dGoal: Float, pressured: Boolean): Boolean {
        val py = me.position.y
        val gy = C.FIELD_H / 2f
        val g1 = (C.FIELD_H - C.GOAL_W) / 2f + 0.7f
        val g2 = (C.FIELD_H + C.GOAL_W) / 2f - 0.7f
        var ky = -1f; var kd = 5f                                                      // darvozabon (darvozaga eng yaqin rakib)
        for ((oid, ob) in sim.players) {
            if (sim.teamOfId(oid) == team) continue
            val d = hypot(ob.position.x - goalX, ob.position.y - gy)
            if (d < kd) { kd = d; ky = ob.position.y }
        }
        val ty = if (ky >= 0f) (if (ky > gy) g1 else g2) else (if (py > gy) g1 else g2)   // darvozabondan uzoq tomonga
        var err = gauss() * (aimSd + dGoal * 0.25f) * (if (pressured) 1.5f else 1f)
        if (rnd() < 0.05f) err += (if (rnd() < 0.5f) 18f else -18f) * (0.6f + 0.6f * rnd())   // xato zarba
        rot(goalX - ball.position.x, ty - ball.position.y, err)
        val speed = 13.5f + 2.5f * rnd() - (if (pressured) 1f else 0f)
        val ok = sim.botKick(id, tmp[0], tmp[1], speed)
        if (ok) runTicks = 0
        return ok
    }

    private fun doPass(sim: SoccerWorld, me: Body, ball: Body): Boolean {
        val dx = pTx - ball.position.x; val dy = pTy - ball.position.y
        val dist = hypot(dx, dy)
        var err = gauss() * passSd
        var speed = (dist * 0.95f + 3f).coerceIn(6f, 13.5f)
        if (rnd() < 0.06f) { err += if (rnd() < 0.5f) 12f else -12f; speed *= 0.65f + 0.7f * rnd() }   // yomon pas
        rot(dx, dy, err)
        val ok = sim.botKick(id, tmp[0], tmp[1], speed)
        if (ok) runTicks = 40                                                          // pasdan keyin oldinga yuguradi
        return ok
    }

    /** Olib yurish: darvoza tomon, rakiblarni aylanib o'tib; to'p bot oldida "ushlab" turiladi */
    private fun dribble(sim: SoccerWorld, me: Body, ball: Body, pressured: Boolean) {
        val px = me.position.x; val py = me.position.y
        val vx = me.linearVelocity.x; val vy = me.linearVelocity.y
        val gy = C.FIELD_H / 2f
        var ux = goalX - px; var uy = gy - py
        val gl = hypot(ux, uy).coerceAtLeast(0.001f); ux /= gl; uy /= gl
        for ((oid, ob) in sim.players) {                                               // rakiblardan qochish
            if (sim.teamOfId(oid) == team) continue
            val ex = px - ob.position.x; val ey = py - ob.position.y
            val d = hypot(ex, ey)
            if (d < 4.5f && d > 0.01f) { val w = (4.5f - d) / 4.5f * 1.8f; ux += ex / d * w; uy += ey / d * w }
        }
        val m = 2.6f                                                                   // devor/burchakdan uzoqlashish
        if (px < m) ux += 1f
        if (px > C.FIELD_W - m) ux -= 1f
        if (py < m) uy += 1f
        if (py > C.FIELD_H - m) uy -= 1f
        val ul = hypot(ux, uy).coerceAtLeast(0.001f); ux /= ul; uy /= ul

        val sharp = lastUx * ux + lastUy * uy < -0.3f                                  // keskin burilish
        lastUx = ux; lastUy = uy
        faceX += (ux - faceX) * 0.25f; faceY += (uy - faceY) * 0.25f
        val fl = hypot(faceX, faceY).coerceAtLeast(0.001f)
        val fx = faceX / fl; val fy = faceY / fl

        val sp = topSpeed * (if (pressured) 0.84f else 0.88f)
        mx = ux * sp; my = uy * sp

        val tx = px + fx * CARRY_DIST; val ty = py + fy * CARRY_DIST                   // to'pni oldinda ushlab turish
        var bvx = vx + (tx - ball.position.x) * 10f
        var bvy = vy + (ty - ball.position.y) * 10f
        val bl = hypot(bvx, bvy)
        if (bl > 16f) { bvx *= 16f / bl; bvy *= 16f / bl }
        ball.setLinearVelocity(bvx, bvy)
        ctrlVx = bvx; ctrlVy = bvy; hasCtrl = true

        if ((sharp && rnd() < 0.12f) || rnd() < 0.0015f) {                             // og'ir teginish: to'p oldinga qochadi
            ball.setLinearVelocity(vx + ux * 6.5f, vy + uy * 6.5f)
            sim.carrierId = -1
            sim.noGrabUntil[id] = sim.tick + 14
        }
    }

    // =====================================================================================
    //  TO'PSIZ
    // =====================================================================================
    private fun offBall(sim: SoccerWorld, me: Body, ball: Body, plan: TeamPlan) {
        val px = me.position.x; val py = me.position.y
        val bx = ball.position.x; val by = ball.position.y
        val gy = C.FIELD_H / 2f

        val newRole = when {
            runTicks > 0 -> R_RUN
            plan.poss == TeamPlan.MINE && plan.handler >= 0 && plan.handler != id -> R_SUPPORT
            plan.poss == TeamPlan.MINE -> R_CHASE                                      // to'p menda, lekin hali olmadim
            plan.chaser == id -> if (plan.poss == TeamPlan.THEIRS) R_PRESS else R_CHASE
            else -> R_COVER
        }
        when (newRole) {
            R_RUN -> { runTicks--; nx = px + a * 6f; ny = py + (gy - py) * 0.3f; nsp = topSpeed * 0.9f }
            R_SUPPORT -> support(sim, plan)
            R_CHASE -> chase(sim, me, ball)
            R_PRESS -> press(sim, me, ball, plan)
            else -> cover(sim, ball)
        }

        if (--noiseIn <= 0) { noiseIn = 40 + Random.nextInt(40); noiseX = (rnd() - 0.5f) * 1.6f; noiseY = (rnd() - 0.5f) * 1.6f }
        val nz = if (newRole == R_CHASE || newRole == R_PRESS) 0.2f else 1f           // joylashuv xatosi (to'pni quvishda kam)
        nx = (nx + noiseX * nz).coerceIn(1.8f, C.FIELD_W - 1.8f)
        ny = (ny + noiseY * nz).coerceIn(1.8f, C.FIELD_H - 1.8f)

        if (!initDone) { tgtX = px; tgtY = py; initDone = true }
        var spd = nsp
        if (newRole != role) { role = newRole; react = reactMin + Random.nextInt(reactSpan) }   // vaziyat o'zgardi - reaksiya kechikishi
        if (react > 0) { react--; spd *= 0.6f }                                       // eski nishonga sekin harakat
        else if (newRole == R_CHASE || newRole == R_PRESS || --retargetIn <= 0) { tgtX = nx; tgtY = ny; retargetIn = decisionEvery }

        // jamoadoshdan (odam ham) uzoqlashish - to'planib qolmaslik
        var mateDist = 99f; var mateX = 0f; var mateY = 0f
        for ((oid, ob) in sim.players) {
            if (oid == id || sim.teamOfId(oid) != team) continue
            val d = hypot(px - ob.position.x, py - ob.position.y)
            if (d < mateDist) { mateDist = d; mateX = ob.position.x; mateY = ob.position.y }
        }
        var dx = tgtX - px; var dy = tgtY - py
        if (mateDist < 2.4f) { dx += (px - mateX) * 1.5f; dy += (py - mateY) * 1.5f }
        if (escape > 0) { escape--; dx = escX; dy = escY; spd = 1f }

        val len = hypot(dx, dy)
        if (len < 1.6f) spd *= 0.35f + 0.65f * len / 1.6f                              // nishonga yaqinlashganda sekinlashish
        if (len < 0.3f) { mx = 0f; my = 0f } else { mx = dx / len * spd; my = dy / len * spd }

        // qisilib qolganini aniqlash
        val sp = hypot(me.linearVelocity.x, me.linearVelocity.y)
        if (escape == 0) {
            if (len > 1.5f && sp < 0.6f) stuck++ else stuck = 0
            if (stuck > 40) {
                stuck = 0; escape = 30
                var ex = C.FIELD_W / 2f - px; var ey = gy - py
                val el = hypot(ex, ey).coerceAtLeast(0.001f); ex /= el; ey /= el
                val j = (rnd() - 0.5f) * 1.4f
                escX = ex - ey * j; escY = ey + ex * j
            }
        }
    }

    /** Erkin to'pga yo'l olish (to'p harakatini oldindan baholab) */
    private fun chase(sim: SoccerWorld, me: Body, ball: Body) {
        val px = me.position.x; val py = me.position.y
        val bx = ball.position.x; val by = ball.position.y
        val dist = hypot(bx - px, by - py)
        val lead = (dist / 8f).coerceIn(0f, 0.7f)
        nx = bx + ball.linearVelocity.x * lead; ny = by + ball.linearVelocity.y * lead
        nsp = topSpeed
        if (dist < C.KICK_RANGE && sim.carrierId == -1 && sim.kickReady(id) && rnd() < 0.08f) {   // bahsli to'p - oldinga tozalash
            for ((oid, ob) in sim.players) {
                if (sim.teamOfId(oid) != team && hypot(ob.position.x - bx, ob.position.y - by) < 2.2f) {
                    sim.botKick(id, a, (C.FIELD_H / 2f - by) * 0.15f, 11f); break
                }
            }
        }
    }

    /** To'p rakibda: eng yaqin o'yinchi bosim qiladi (jockey yoki tegib to'pni olish) */
    private fun press(sim: SoccerWorld, me: Body, ball: Body, plan: TeamPlan) {
        val px = me.position.x; val py = me.position.y
        val bx = ball.position.x; val by = ball.position.y
        val hb = sim.players[plan.handler]
        if (hb == null) { chase(sim, me, ball); return }
        val hx = hb.position.x; val hy = hb.position.y
        val d = hypot(hx - px, hy - py)
        if (plan.handler < C.BOT_ID_BASE) {                                           // odam to'p olib yuribdi: to'pga borib urib yuborish
            nx = bx; ny = by; nsp = topSpeed
            if (hypot(bx - px, by - py) < C.KICK_RANGE && sim.kickReady(id) && rnd() < 0.10f) {
                sim.botKick(id, a, (C.FIELD_H / 2f - by) * 0.15f, 11f)
            }
            return
        }
        if (d < 3.2f && !commitSet) { commit = rnd() < 0.65f; commitSet = true } else if (d >= 3.6f) commitSet = false
        if (commit && d < 3.4f) {                                                      // tegib to'pni olish (yoki xato: aldanib qolish)
            nx = hx + hb.linearVelocity.x * 0.25f; ny = hy + hb.linearVelocity.y * 0.25f; nsp = topSpeed
        } else {                                                                       // jockey: o'z darvozasi tomonda turish
            val gy = C.FIELD_H / 2f
            val ox = ownX - hx; val oy = gy - hy
            val ol = hypot(ox, oy).coerceAtLeast(0.001f)
            nx = hx + ox / ol * 2.3f; ny = hy + oy / ol * 2.3f; nsp = topSpeed * 0.95f
        }
    }

    /** To'p jamoada: to'p egasi oldida, ochiq joyda pas varianti bo'lish (kenglik bo'yicha qarama-qarshi tomonda) */
    private fun support(sim: SoccerWorld, plan: TeamPlan) {
        val hb = sim.players[plan.handler]
        if (hb == null) { nx = ownX + (C.FIELD_W / 2f - ownX) * 1.2f; ny = C.FIELD_H / 2f; nsp = topSpeed * 0.8f; return }
        val hx = hb.position.x; val hy = hb.position.y
        val gy = C.FIELD_H / 2f
        var sx = hx + a * 5.5f
        var sy = if (hy > gy) hy - 5.5f else hy + 5.5f
        sx = if (a > 0f) min(sx, C.FIELD_W * 0.78f) else maxOf(sx, C.FIELD_W * 0.22f)     // muvozanat: haddan tashqari oldinga chiqmaydi
        sx = sx.coerceIn(3f, C.FIELD_W - 3f); sy = sy.coerceIn(2.8f, C.FIELD_H - 2.8f)
        for ((oid, ob) in sim.players) {                                               // rakiblardan uzoqroq
            if (sim.teamOfId(oid) == team) continue
            val ex = sx - ob.position.x; val ey = sy - ob.position.y
            val d = hypot(ex, ey)
            if (d < 3.5f && d > 0.01f) { sx += ex / d * (3.5f - d) * 0.8f; sy += ey / d * (3.5f - d) * 0.8f }
        }
        nx = sx; ny = sy; nsp = topSpeed * 0.88f
    }

    /** Himoya joylashuvi: to'p va o'z darvozasi orasida; xavfli rakibni kuzatadi */
    private fun cover(sim: SoccerWorld, ball: Body) {
        val bx = ball.position.x; val by = ball.position.y
        val gy = C.FIELD_H / 2f
        var cx = bx + (ownX - bx) * 0.45f
        var cy = gy + (by - gy) * 0.6f
        var md = 7f; var mx2 = 0f; var my2 = 0f
        for ((oid, ob) in sim.players) {
            if (sim.teamOfId(oid) == team) continue
            val d = hypot(ob.position.x - cx, ob.position.y - cy)
            if (d < md && hypot(ob.position.x - bx, ob.position.y - by) > 1.7f) { md = d; mx2 = ob.position.x; my2 = ob.position.y }
        }
        if (md < 7f) { cx += (mx2 - cx) * 0.3f; cy += (my2 - cy) * 0.3f }
        nx = cx; ny = cy; nsp = topSpeed * 0.8f
    }
}
