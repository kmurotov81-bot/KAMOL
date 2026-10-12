package com.soccer.game

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.InputAdapter
import soccer.shared.Act
import kotlin.math.hypot

/**
 * Chap yarim: joystick. O'ng pastda: PASS va SHOOT tugmalari. O'ng yuqorida: pauza.
 * Hamma o'lcham ekran balandligiga nisbatan (turli telefonlarda bir xil ko'rinadi).
 */
class TouchPad : InputAdapter() {
    @Volatile var enabled = true
        set(v) { field = v; if (!v) release() }

    @Volatile var mx = 0f
    @Volatile var my = 0f
    var stickActive = false
    var originX = 0f; var originY = 0f
    var knobX = 0f; var knobY = 0f

    // geometriya (piksel)
    private val w get() = Gdx.graphics.width.toFloat()
    private val h get() = Gdx.graphics.height.toFloat()
    val radius get() = h * 0.13f
    val idleX get() = w * 0.13f
    val idleY get() = h * 0.22f
    val btnR get() = h * 0.115f
    val shootX get() = w * 0.91f
    val shootY get() = h * 0.20f
    val passX get() = w * 0.794f
    val passY get() = h * 0.16f
    val pauseSize get() = h * 0.09f
    val pauseX get() = w - w * 0.02f - pauseSize
    val pauseY get() = h - h * 0.03f - pauseSize

    private var stickPtr = -1
    private var shootPtr = -1
    private var passPtr = -1
    private var shootLatch = false
    private var passLatch = false
    private var pauseReq = false

    val shootHeld get() = shootPtr != -1
    val passHeld get() = passPtr != -1

    /** Bu tick uchun harakat: SHOOT bosib turilsa takrorlanadi, PASS faqat bosilganda */
    fun consumeAction(): Int {
        val a = when {
            shootLatch || shootPtr != -1 -> Act.SHOOT
            passLatch -> Act.PASS
            else -> Act.NONE
        }
        shootLatch = false; passLatch = false
        return a
    }

    fun consumePause(): Boolean { val p = pauseReq; pauseReq = false; return p }

    private fun release() {
        stickPtr = -1; shootPtr = -1; passPtr = -1
        stickActive = false; mx = 0f; my = 0f
        shootLatch = false; passLatch = false
    }

    override fun touchDown(x: Int, y: Int, pointer: Int, button: Int): Boolean {
        if (!enabled) return true
        val sx = x.toFloat(); val sy = h - y.toFloat()
        if (sx in pauseX..(pauseX + pauseSize) && sy in pauseY..(pauseY + pauseSize)) { pauseReq = true; return true }
        if (sx < w / 2f) {
            if (stickPtr == -1) {
                stickPtr = pointer; stickActive = true
                originX = sx; originY = sy
                update(sx, sy)
            }
        } else {
            // eng yaqin tugma hal qiladi (barmoq biroz adashsa ham)
            val dS = hypot(sx - shootX, sy - shootY)
            val dP = hypot(sx - passX, sy - passY)
            if (dS <= dP) { if (shootPtr == -1) { shootPtr = pointer; shootLatch = true } }
            else if (passPtr == -1) { passPtr = pointer; passLatch = true }
        }
        return true
    }

    override fun touchDragged(x: Int, y: Int, pointer: Int): Boolean {
        if (enabled && pointer == stickPtr) update(x.toFloat(), h - y.toFloat())
        return true
    }

    override fun touchUp(x: Int, y: Int, pointer: Int, button: Int): Boolean {
        if (pointer == stickPtr) { stickPtr = -1; stickActive = false; mx = 0f; my = 0f }
        if (pointer == shootPtr) shootPtr = -1
        if (pointer == passPtr) passPtr = -1
        return true
    }

    private fun update(x: Float, y: Float) {
        var dx = (x - originX) / radius
        var dy = (y - originY) / radius
        val len = hypot(dx, dy)
        if (len > 1f) { dx /= len; dy /= len }
        if (len < 0.1f) { mx = 0f; my = 0f } else { mx = dx; my = dy }
        knobX = originX + dx * radius
        knobY = originY + dy * radius
    }
}
