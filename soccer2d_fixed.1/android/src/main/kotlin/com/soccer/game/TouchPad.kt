package com.soccer.game

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.InputAdapter
import soccer.shared.Act
import kotlin.math.hypot

/**
 * Chap yarim: joystick. O'ng pastda: PASS va SHOOT tugmalari. O'ng yuqorida: pauza.
 * PASS/SHOOT: tugmani bosib turing - qancha uzoq bossangiz, zarba/pas shuncha kuchli bo'ladi.
 * Hamma o'lcham ekran balandligiga nisbatan (turli telefonlarda bir xil ko'rinadi).
 */
class TouchPad : InputAdapter() {
    companion object {
        /** Bosib turish davomiyligi -> quvvat darajasi (0..3) */
        fun levelFor(ms: Long) = when {
            ms < 150L -> 0
            ms < 350L -> 1
            ms < 700L -> 2
            else -> 3
        }
        /** Quvvat paneli to'liq bo'ladigan vaqt */
        const val FULL_MS = 700L
    }

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
    private var shootDownAt = 0L
    private var passDownAt = 0L
    private var pendingAction = Act.NONE
    private var pendingPower = 0
    private var pauseReq = false

    /** Oxirgi zarba/pasning quvvat darajasi; consumeAction() dan keyin o'qiladi */
    var actionPower = 0
        private set

    val shootHeld get() = shootPtr != -1
    val passHeld get() = passPtr != -1

    /** Hozir bosib turilayotgan tugma (yo'q bo'lsa NONE) - nishon va quvvat paneli uchun */
    val chargingAction get() = when {
        shootPtr != -1 -> Act.SHOOT
        passPtr != -1 -> Act.PASS
        else -> Act.NONE
    }

    /** Hozirgi bosib turish davomiyligi (ms) */
    val chargeMs: Long get() = when {
        shootPtr != -1 -> System.currentTimeMillis() - shootDownAt
        passPtr != -1 -> System.currentTimeMillis() - passDownAt
        else -> 0L
    }
    val chargeLevel get() = levelFor(chargeMs)

    /** Bu tick uchun harakat: tugma qo'yib yuborilganda bir marta chiqadi (quvvati actionPower da) */
    fun consumeAction(): Int {
        val a = pendingAction
        actionPower = pendingPower
        pendingAction = Act.NONE
        return a
    }

    fun consumePause(): Boolean { val p = pauseReq; pauseReq = false; return p }

    private fun release() {
        stickPtr = -1; shootPtr = -1; passPtr = -1
        stickActive = false; mx = 0f; my = 0f
        pendingAction = Act.NONE
    }

    override fun touchDown(x: Int, y: Int, pointer: Int, button: Int): Boolean {
        if (!enabled) return true
        val sx = x.toFloat(); val sy = h - y.toFloat()
        val now = System.currentTimeMillis()
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
            if (dS <= dP) { if (shootPtr == -1) { shootPtr = pointer; shootDownAt = now } }
            else if (passPtr == -1) { passPtr = pointer; passDownAt = now }
        }
        return true
    }

    override fun touchDragged(x: Int, y: Int, pointer: Int): Boolean {
        if (enabled && pointer == stickPtr) update(x.toFloat(), h - y.toFloat())
        return true
    }

    /** Tugma qo'yib yuborildi: zarba/pas shu paytda bajariladi, quvvat bosib turish vaqtidan olinadi */
    override fun touchUp(x: Int, y: Int, pointer: Int, button: Int): Boolean {
        val now = System.currentTimeMillis()
        if (pointer == stickPtr) { stickPtr = -1; stickActive = false; mx = 0f; my = 0f }
        if (pointer == shootPtr) {
            pendingAction = Act.SHOOT; pendingPower = levelFor(now - shootDownAt); shootPtr = -1
        }
        if (pointer == passPtr) {
            pendingAction = Act.PASS; pendingPower = levelFor(now - passDownAt); passPtr = -1
        }
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
