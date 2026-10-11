package com.soccer.game

import com.badlogic.gdx.Game
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.ScreenAdapter
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureAtlas
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.utils.Align
import kotlin.concurrent.thread

/** 2v2 FOOTBALL bosh menyu: PLAY -> o'yin turi; SETTINGS (server IP); EXIT. */
class MenuScreen(private val game: Game) : ScreenAdapter() {
    private enum class Page { MAIN, MODES }

    private class Btn(val label: String, val green: Boolean, val scale: Float, val action: () -> Unit) {
        var x = 0f; var y = 0f; var w = 0f; var h = 0f
        fun hit(px: Float, py: Float) = px in x..(x + w) && py in y..(y + h)
    }

    private var page = Page.MAIN
    private val prefs = Gdx.app.getPreferences("soccer")
    private var host = prefs.getString("host", "10.0.2.2")
    private val cam = OrthographicCamera()
    private val batch = SpriteBatch()
    private val font = BitmapFont()
    private val atlas = TextureAtlas(Gdx.files.internal("atlas.atlas"))
    private val grass = Texture(Gdx.files.internal("grass.png")).apply {
        setWrap(Texture.TextureWrap.Repeat, Texture.TextureWrap.Repeat)
        setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
    }
    private val grassRegion = TextureRegion(grass)
    private val rGreen = atlas.findRegion("btn_green")
    private val rDark = atlas.findRegion("btn_dark")
    private val rWhite = atlas.findRegion("white")
    private val rLogo = atlas.findRegion("logo")
    private val rBlue = atlas.findRegion("ball_blue")
    private val rRed = atlas.findRegion("ball_red")
    private val rBall = atlas.findRegion("ball")

    private var asking = false
    @Volatile private var busy = false
    private var status = ""
    private var u = 1f

    private val mainButtons = listOf(
        Btn("PLAY", true, 3.2f) { page = Page.MODES },
        Btn("SETTINGS", false, 1.8f) {
            ask("Server IP (for online play)", host) { t -> host = t.trim(); prefs.putString("host", host); prefs.flush() }
        },
        Btn("EXIT", false, 1.8f) { Gdx.app.exit() }
    )
    private val modeButtons = listOf(
        Btn("VS BOTS", true, 2f) { playBots() },
        Btn("QUICK MATCH", false, 2f) { open(host, -1, false, null) },
        Btn("CREATE ROOM", false, 2f) { open(host, 0, false, null) },
        Btn("JOIN BY CODE", false, 2f) {
            ask("Room code", "") { t -> t.trim().toIntOrNull()?.let { open(host, it, false, null) } }
        },
        Btn("HOST ON THIS PHONE", false, 1.8f) { hostLocal() }
    )
    private val backBtn = Btn("<", false, 2.6f) { page = Page.MAIN }

    init {
        Gdx.input.inputProcessor = null
        Gdx.input.setCatchKey(Input.Keys.BACK, true)
    }

    override fun resize(width: Int, height: Int) {
        cam.setToOrtho(false, width.toFloat(), height.toFloat())
        u = height / 1080f
    }

    private fun open(host: String, code: Int, local: Boolean, info: String?) {
        Gdx.app.postRunnable { game.setScreen(GameScreen(game, host, code, local, info)); dispose() }
    }

    /** LAN host: server shu telefonda; do'stlar IP'ni SETTINGS'ga kiritib QUICK MATCH bosadi */
    private fun hostLocal() {
        if (busy) return
        busy = true; status = "Starting server..."
        thread {
            val ok = LocalServer.start(botCount = 0, loopbackOnly = false)
            Gdx.app.postRunnable {
                busy = false
                if (ok) {
                    val ip = LocalServer.localIp()
                    status = ""
                    open("127.0.0.1", -1, true,
                        if (ip != null) "Friends: SETTINGS > $ip (same Wi-Fi)" else "Connect to Wi-Fi to invite friends")
                } else status = "Cannot start server (port ${soccer.shared.C.PORT} busy?)"
            }
        }
    }

    /** Offline: server faqat shu telefonda (loopback), 3 ta bot -> 2v2 */
    private fun playBots() {
        if (busy) return
        busy = true; status = "Starting..."
        thread {
            val ok = LocalServer.start(botCount = 3, loopbackOnly = true)
            Gdx.app.postRunnable {
                busy = false
                if (ok) { status = ""; open("127.0.0.1", -1, true, null) }
                else status = "Cannot start local game (port ${soccer.shared.C.PORT} busy?)"
            }
        }
    }

    private fun ask(title: String, def: String, onText: (String) -> Unit) {
        if (asking) return
        asking = true
        Gdx.input.getTextInput(object : Input.TextInputListener {
            override fun input(text: String) { asking = false; Gdx.app.postRunnable { onText(text) } }
            override fun canceled() { asking = false }
        }, title, def, "")
    }

    private fun layout(sw: Float, sh: Float) {
        val cx = sw / 2f
        if (page == Page.MAIN) {
            val play = mainButtons[0]
            play.w = sw * 0.30f; play.h = sh * 0.14f; play.x = cx - play.w / 2f; play.y = sh * 0.34f
            val sbw = sw * 0.145f; val sbh = sh * 0.10f; val gap = sw * 0.01f
            mainButtons[1].apply { w = sbw; h = sbh; x = cx - sbw - gap / 2f; y = sh * 0.19f }
            mainButtons[2].apply { w = sbw; h = sbh; x = cx + gap / 2f; y = sh * 0.19f }
        } else {
            val bw = sw * 0.30f; val bh = sh * 0.13f; val gx = sw * 0.02f
            val rows = floatArrayOf(sh * 0.58f, sh * 0.58f - sh * 0.16f, sh * 0.58f - sh * 0.32f)
            modeButtons[0].apply { w = bw; h = bh; x = cx - bw - gx / 2f; y = rows[0] }
            modeButtons[1].apply { w = bw; h = bh; x = cx + gx / 2f; y = rows[0] }
            modeButtons[2].apply { w = bw; h = bh; x = cx - bw - gx / 2f; y = rows[1] }
            modeButtons[3].apply { w = bw; h = bh; x = cx + gx / 2f; y = rows[1] }
            modeButtons[4].apply { w = bw * 1.2f; h = bh; x = cx - bw * 0.6f; y = rows[2] }
            backBtn.apply { w = sh * 0.11f; h = sh * 0.11f; x = sw * 0.03f; y = sh * 0.86f }
        }
    }

    private fun drawBtn(b: Btn) {
        batch.draw(if (b.green) rGreen else rDark, b.x, b.y, b.w, b.h)
        font.data.setScale(b.scale * u)
        font.color = Color.WHITE
        font.draw(batch, b.label, b.x, b.y + b.h / 2f + font.capHeight / 2f, b.w, Align.center, false)
    }

    override fun render(delta: Float) {
        val sw = Gdx.graphics.width.toFloat(); val sh = Gdx.graphics.height.toFloat()
        layout(sw, sh)

        if (Gdx.input.isKeyJustPressed(Input.Keys.BACK)) {
            if (page == Page.MODES) page = Page.MAIN else Gdx.app.exit()
        } else if (Gdx.input.justTouched() && !asking && !busy) {
            val tx = Gdx.input.x.toFloat(); val ty = sh - Gdx.input.y
            val list = if (page == Page.MAIN) mainButtons else modeButtons
            val hit = list.firstOrNull { it.hit(tx, ty) } ?: if (page == Page.MODES && backBtn.hit(tx, ty)) backBtn else null
            hit?.action?.invoke()
        }
        layout(sw, sh)

        Gdx.gl.glClearColor(0.01f, 0.07f, 0.2f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        Gdx.gl.glEnable(GL20.GL_BLEND)
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
        batch.projectionMatrix = cam.combined
        batch.begin()

        // pastda stadion o'ti (maketdagi kabi)
        val gh = sh * 0.28f; val tile = sh * 0.3f
        batch.setColor(0.75f, 0.75f, 0.8f, 1f)
        grassRegion.setRegion(0f, 0f, sw / tile, gh / tile)
        batch.draw(grassRegion, 0f, 0f, sw, gh)
        batch.setColor(1f, 1f, 1f, 0.35f)
        batch.draw(rWhite, 0f, gh - 3f, sw, 4f)
        batch.draw(rWhite, 0f, gh * 0.45f, sw, 3f)
        batch.setColor(1f, 1f, 1f, 1f)

        if (page == Page.MAIN) {
            val lw = sw * 0.34f; val lh = lw * rLogo.regionHeight / rLogo.regionWidth
            batch.draw(rLogo, sw / 2f - lw / 2f, sh * 0.93f - lh, lw, lh)
            // bezak: ko'k va qizil o'yinchi
            val r = sh * 0.07f
            batch.draw(rBlue, sw * 0.22f, sh * 0.55f, r * 2f, r * 2f)
            batch.draw(rRed, sw * 0.78f - r * 2f, sh * 0.55f, r * 2f, r * 2f)
            batch.draw(rBall, sw / 2f - r * 0.5f, gh * 0.8f, r, r)
            for (b in mainButtons) drawBtn(b)
        } else {
            font.data.setScale(2.8f * u); font.color = Color.WHITE
            font.draw(batch, "SELECT MODE", 0f, sh * 0.93f, sw, Align.center, false)
            drawBtn(backBtn)
            for (b in modeButtons) drawBtn(b)
        }

        font.data.setScale(1.3f * u)
        font.color = Color(1f, 1f, 1f, 0.9f)
        font.draw(batch, if (status.isNotEmpty()) status else "Server: $host", 0f, sh * 0.05f, sw, Align.center, false)
        batch.end()
    }

    override fun dispose() { batch.dispose(); font.dispose(); atlas.dispose(); grass.dispose() }
}
