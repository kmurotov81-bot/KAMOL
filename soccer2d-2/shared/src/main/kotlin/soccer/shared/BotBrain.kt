package soccer.shared

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random

/**
 * Server ichidagi bot (tarmoq yo'q, o'yin holatini to'g'ridan-to'g'ri ko'radi).
 * Rollar: jamoadagi to'pga eng yaqin o'yinchi - HUJUMCHI (to'pni quvadi va rakib darvozasi tomon uradi);
 * qolganlari - HIMOYACHI (to'p bilan o'z darvozasi orasida turadi). Shuning uchun hamma bitta
 * to'pga yig'ilib, burchakda tiqilib qolmaydi. Qisilib qolsa - markazga chiqib ketish (escape).
 */
class BotBrain(private val id: Int, private val team: Int, private val skill: Float = 0.9f) {
    var mx = 0f; private set
    var my = 0f; private set
    var action = Act.NONE; private set
    private var stuck = 0
    private var escape = 0
    private var escX = 0f
    private var escY = 0f

    fun think(sim: SoccerWorld) {
        val me = sim.players[id] ?: return
        val ball = sim.ball ?: return
        action = Act.NONE
        if (!sim.playing) { mx = 0f; my = 0f; stuck = 0; escape = 0; return }

        val w = C.FIELD_W; val h = C.FIELD_H
        val px = me.position.x; val py = me.position.y
        val bx = ball.position.x; val by = ball.position.y
        val goalX = if (team == 0) w else 0f
        val ownX = w - goalX
        val myDist = hypot(bx - px, by - py)

        // rol: to'pga eng yaqin sherik bormi?
        var chaser = true
        var mateDist = 99f; var mateX = 0f; var mateY = 0f
        for ((oid, ob) in sim.players) {
            if (oid == id || sim.teamOfId(oid) != team) continue
            val d = hypot(bx - ob.position.x, by - ob.position.y)
            if (d < myDist - 0.05f || (abs(d - myDist) <= 0.05f && oid < id)) chaser = false
            val dm = hypot(px - ob.position.x, py - ob.position.y)
            if (dm < mateDist) { mateDist = dm; mateX = ob.position.x; mateY = ob.position.y }
        }

        // to'pdan rakib darvozasi tomon yo'nalish
        var gx = goalX - bx; var gy = h / 2f - by
        val gl = hypot(gx, gy).coerceAtLeast(0.001f); gx /= gl; gy /= gl
        val behind = (px - bx) * gx + (py - by) * gy < 0f
        val nearWall = bx < 1.8f || bx > w - 1.8f || by < 1.8f || by > h - 1.8f

        var tx: Float; var ty: Float; var speed = skill
        if (chaser) {
            if (nearWall || (myDist < 2.4f && behind)) { tx = bx; ty = by }   // devor yonida: to'g'ridan-to'g'ri
            else { tx = bx - gx * 1.3f; ty = by - gy * 1.3f }                  // to'pning orqasiga o'tamiz
        } else {
            tx = ownX + (bx - ownX) * 0.4f
            ty = h / 2f + (by - h / 2f) * 0.6f
            speed = skill * 0.75f
        }
        val m = 1.4f
        tx = tx.coerceIn(m, w - m); ty = ty.coerceIn(m, h - m)

        var dx = tx - px; var dy = ty - py
        if (mateDist < 2.2f) { dx += (px - mateX) * 1.5f; dy += (py - mateY) * 1.5f }   // sherikdan uzoqlashish
        if (escape > 0) { escape--; dx = escX; dy = escY; speed = 1f }

        val len = hypot(dx, dy)
        if (len < 0.35f) { mx = 0f; my = 0f } else { mx = dx / len * speed; my = dy / len * speed }

        // qisilib qolganini aniqlash
        val sp = hypot(me.linearVelocity.x, me.linearVelocity.y)
        if (escape == 0) {
            if (len > 1.5f && sp < 0.6f) stuck++ else stuck = 0
            if (stuck > 40) {
                stuck = 0; escape = 30
                var ex = w / 2f - px; var ey = h / 2f - py
                val el = hypot(ex, ey).coerceAtLeast(0.001f)
                ex /= el; ey /= el
                val j = (Random.nextFloat() - 0.5f) * 1.4f          // yon tomonga biroz og'ish
                escX = ex - ey * j; escY = ey + ex * j
            }
        }

        // zarba: to'p yaqin va rakib darvozasi tomonga qaragan bo'lsa (devor yonida - tozalash uchun yumshoqroq)
        if (chaser && myDist < C.KICK_RANGE && myDist > 0.001f) {
            val ax = (bx - px) / myDist; val ay = (by - py) / myDist
            val thr = if (nearWall) -0.2f else 0.5f
            if (ax * gx + ay * gy > thr) action = Act.SHOOT
        }
    }
}
