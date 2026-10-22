package com.soccer.game

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.audio.Sound
import soccer.shared.SLog

/** Oddiy ovoz effektlari. Fayl topilmasa yoki audio ishlamasa - jim o'tadi (o'yin to'xtamaydi). */
object Sfx {
    private val cache = HashMap<String, Sound?>()

    fun play(name: String) {
        if (!Settings.sound) return
        try {
            val s = cache.getOrPut(name) {
                try { Gdx.audio.newSound(Gdx.files.internal("sfx/$name.wav")) } catch (e: Exception) { null }
            }
            s?.play(0.7f)
        } catch (e: Exception) {
            SLog.w("Sfx", "play '$name' failed", e)
        }
    }

    fun dispose() { cache.values.forEach { it?.dispose() }; cache.clear() }
}
