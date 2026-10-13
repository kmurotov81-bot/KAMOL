package soccer.shared

import com.badlogic.gdx.physics.box2d.Box2D
import com.badlogic.gdx.utils.GdxNativesLoader
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.math.hypot

class SoccerWorldTest {
    @BeforeTest fun natives() { GdxNativesLoader.load(); Box2D.init() }

    private fun started(): SoccerWorld {
        val w = SoccerWorld(withBall = true)
        w.addPlayer(1, 0); w.addPlayer(2, 1)
        w.setReady(1, true); w.setReady(2, true)
        w.step()                                   // LOBBY -> COUNTDOWN
        repeat(C.COUNTDOWN_S * 60 + 2) { w.step() } // COUNTDOWN -> PLAYING
        assertTrue(w.playing)
        return w
    }

    @Test fun seatsAreExclusive() {
        val w = SoccerWorld(withBall = true)
        w.addPlayer(1, 0, 0); w.addPlayer(2, 0, 1); w.addPlayer(3, 1, 0)
        assertFalse(w.setSeat(3, 0, 0))            // o'rin band
        assertTrue(w.setSeat(3, 1, 1))              // bo'sh o'rin
        assertEquals(1, w.slotOfId(3))
        assertEquals(-1, w.firstFreeSlot(0))
        assertEquals(3, w.occupant(1, 1))
    }

    @Test fun harderShotWithMorePower() {
        fun speedAt(level: Int): Float {
            val w = started()
            w.ball!!.setTransform(10f, 9f, 0f); w.ball!!.setLinearVelocity(0f, 0f)
            w.players[1]!!.setTransform(9f, 9f, 0f)      // to'p 1 m o'ngda
            w.applyInput(1, 1f, 0f, Act.SHOOT, level)
            val v = w.ball!!.linearVelocity
            return hypot(v.x, v.y)
        }
        assertTrue(speedAt(3) > speedAt(0))
    }

    @Test fun goalOnLastTickIsCounted() {
        val w = started()
        w.matchTicks = 1
        w.ball!!.setTransform(C.FIELD_W + 0.5f, C.FIELD_H / 2f, 0f)
        w.ball!!.setLinearVelocity(0f, 0f)
        w.step()
        assertEquals(1, w.scoreA)
        assertEquals(State.END, w.state)
    }

    @Test fun goalCountsAndPauses() {
        val w = started()
        w.ball!!.setTransform(-0.5f, C.FIELD_H / 2f, 0f)
        w.step()
        assertEquals(1, w.scoreB)
        assertEquals(State.GOAL, w.state)
    }
}
