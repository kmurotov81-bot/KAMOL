package com.soccer.game

import com.badlogic.gdx.Gdx
import kotlin.math.abs

/** Sozlamalar (SharedPreferences). Menyuda o'zgaradi, o'yin ekranida o'qiladi. */
object Settings {
    val JOY_STEPS = floatArrayOf(0.7f, 0.85f, 1f, 1.15f, 1.3f)
    val BTN_STEPS = floatArrayOf(0.8f, 1f, 1.2f)

    private fun p() = Gdx.app.getPreferences("soccer")

    /** Joystik sezgirligi (1.0 = standart) */
    var joySens: Float
        get() = p().getFloat("joySens", 1f)
        set(v) { p().putFloat("joySens", v); p().flush() }

    /** PASS/SHOOT tugmalari o'lchami (1.0 = standart) */
    var btnScale: Float
        get() = p().getFloat("btnScale", 1f)
        set(v) { p().putFloat("btnScale", v); p().flush() }

    /** Chap qo'l: joystik o'ngda, tugmalar chapda */
    var leftHanded: Boolean
        get() = p().getBoolean("leftHanded", false)
        set(v) { p().putBoolean("leftHanded", v); p().flush() }

    var sound: Boolean
        get() = p().getBoolean("sound", true)
        set(v) { p().putBoolean("sound", v); p().flush() }

    /** O'yinchi nomi (bo'sh bo'lsa - menyuda so'raladi) */
    var playerName: String
        get() = p().getString("playerName", "")
        set(v) { p().putString("playerName", cleanName(v)); p().flush() }

    fun cleanName(s: String): String = s.trim().take(12).ifBlank { "Player" }

    fun cycle(cur: Float, steps: FloatArray): Float {
        val i = steps.indexOfFirst { abs(it - cur) < 0.01f }
        return steps[(i + 1) % steps.size]
    }
}
