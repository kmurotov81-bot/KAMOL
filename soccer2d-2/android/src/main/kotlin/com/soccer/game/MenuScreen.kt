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
import soccer.shared.C
import soccer.shared.SLog
import kotlin.concurrent.thread

/** 2v2 FOOTBALL menyu: PLAY -> o'yin turi (online / Wi-Fi qidiruv / host / botlar); SETTINGS (server); EXIT. */
class MenuScreen(private val game: Game) : ScreenAdapter() {
    private enum class Page { MAIN, MODES, LAN, SETTINGS }

    private class Btn(var label: String, val green: Boolean, val scale: Float, val action: () -> Unit) {
        var x = 0f; var y = 0f; var w = 0f; var h = 0f
        fun hit(px: Float, py: Float) = px in x..(x + w) && py in y..(y + h)
    }

    private var page = Page.MAIN
    private val prefs = Gdx.app.getPreferences("soccer")
    private var host = prefs.getString("host", "")          // bo'sh: hali kiritilmagan (10.0.2.2 faqat emulator uchun)
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

    private val scanner = LanScanner()
    private var lanVersion = -1
    private var lanBtns = ArrayList<Btn>()
    private var shownHost = "\u0000"

    private val mainButtons = listOf(
        Btn("PLAY", true, 3.2f) { page = Page.MODES },
        Btn("SETTINGS", false, 1.8f) { refreshLogInfo(); page = Page.SETTINGS },
        Btn("EXIT", false, 1.8f) { Gdx.app.exit() }
    )
    private val serverBtn = Btn("", false, 1.5f) { askServer(null) }
    private val modeButtons = listOf(
        Btn("VS BOTS", true, 2f) { playBots() },
        Btn("QUICK MATCH", false, 2f) { withServer { open(host, -1, false, null) } },
        Btn("CREATE ROOM", false, 2f) { withServer { open(host, 0, false, null) } },
        Btn("JOIN BY CODE", false, 2f) {
            withServer { ask("Room code", "") { t -> t.trim().toIntOrNull()?.let { open(host, it, false, null) } } }
        },
        Btn("FIND ON WI-FI", false, 1.8f) { page = Page.LAN; scanner.start() },
        Btn("HOST ON THIS PHONE", false, 1.6f) { hostLocal() }
    )
    private val scanAgain = Btn("SCAN AGAIN", true, 1.6f) { scanner.start() }
    private val settingsButtons = listOf(
        Btn("SERVER ADDRESS", false, 1.5f) { askServer(null) },
        Btn("SHARE LOG", true, 1.6f) { AppLog.share() },
        Btn("COPY LOG", false, 1.6f) {
            Gdx.app.clipboard.contents = AppLog.read(60_000)
            status = "Log copied to clipboard"
        },
        Btn("CLEAR LOG", false, 1.6f) { AppLog.clear(); refreshLogInfo(); status = "Log cleared" }
    )
    private var logInfo = ""
    private var logPath = ""
    private fun refreshLogInfo() {
        logPath = AppLog.shownPath()
        logInfo = "${AppLog.sizeKb()} KB   |   errors this session: ${AppLog.errorCount}"
    }

    private val backBtn = Btn("<", false, 2.6f) {
        if (page == Page.LAN) { scanner.stop(); page = Page.MODES } else page = Page.MAIN
    }

    init {
        Gdx.input.inputProcessor = null
        Gdx.input.setCatchKey(Input.Keys.BACK, true)
    }

    override fun resize(width: Int, height: Int) {
        cam.setToOrtho(false, width.toFloat(), height.toFloat())
        u = height / 1080f
    }

    private fun saveHost(h: String) { host = h.trim(); prefs.putString("host", host); prefs.flush() }

    private fun open(host: String, code: Int, local: Boolean, info: String?) {
        scanner.stop()
        Gdx.app.postRunnable { game.setScreen(GameScreen(game, host, code, local, info)); dispose() }
    }

    /** Server manzili kiritilmagan bo'lsa avval so'raydi, keyin amalni bajaradi */
    private fun withServer(then: () -> Unit) { if (host.isBlank()) askServer(then) else then() }

    private fun askServer(then: (() -> Unit)?) {
        ask("Server address (e.g. 192.168.1.5 or 192.168.1.5:7777)", host) { t ->
            saveHost(t)
            if (host.isNotEmpty()) then?.invoke()
        }
    }

    /** LAN host: server shu telefonda; do'stlar FIND ON WI-FI orqali topadi yoki IP kiritadi */
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
                        if (ip != null) "Friends: FIND ON WI-FI  or enter IP $ip" else "No Wi-Fi found: connect to Wi-Fi (or turn on hotspot) first")
                } else status = "Cannot start server (port ${C.PORT} busy?)"
            }
        }
    }

    /** Offline: server faqat shu telefonda (loopback). Select Teams'da START bosilganda bo'sh o'rinlarni botlar to'ldiradi */
    private fun playBots() {
        if (busy) return
        busy = true; status = "Starting..."
        thread {
            val ok = LocalServer.start(botCount = 0, loopbackOnly = true)
            Gdx.app.postRunnable {
                busy = false
                if (ok) { status = ""; open("127.0.0.1", -1, true, null) }
                else status = "Cannot start local game (port ${C.PORT} busy?)"
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

    private fun rebuildLan() {
        lanBtns = ArrayList()
        for (f in scanner.results.take(4)) {
            val label = "${f.name}    ${f.ip}    ${f.humans}/${C.TEAM_SIZE * 2}" + if (f.compatible) "" else "    (update app)"
            lanBtns.add(Btn(label, true, 1.5f) { saveHost(f.ip); open(f.ip, -1, false, null) })
        }
        lanVersion = scanner.version
    }

    private fun layout(sw: Float, sh: Float) {
        val cx = sw / 2f
        when (page) {
            Page.MAIN -> {
                val play = mainButtons[0]
                play.w = sw * 0.30f; play.h = sh * 0.14f; play.x = cx - play.w / 2f; play.y = sh * 0.34f
                val sbw = sw * 0.145f; val sbh = sh * 0.10f; val gap = sw * 0.01f
                mainButtons[1].apply { w = sbw; h = sbh; x = cx - sbw - gap / 2f; y = sh * 0.19f }
                mainButtons[2].apply { w = sbw; h = sbh; x = cx + gap / 2f; y = sh * 0.19f }
            }
            Page.MODES -> {
                serverBtn.apply { w = sw * 0.62f; h = sh * 0.085f; x = cx - w / 2f; y = sh * 0.71f }
                val bw = sw * 0.30f; val bh = sh * 0.125f; val gx = sw * 0.02f
                val rows = floatArrayOf(sh * 0.54f, sh * 0.54f - sh * 0.15f, sh * 0.54f - sh * 0.30f)
                modeButtons[0].apply { w = bw; h = bh; x = cx - bw - gx / 2f; y = rows[0] }
                modeButtons[1].apply { w = bw; h = bh; x = cx + gx / 2f; y = rows[0] }
                modeButtons[2].apply { w = bw; h = bh; x = cx - bw - gx / 2f; y = rows[1] }
                modeButtons[3].apply { w = bw; h = bh; x = cx + gx / 2f; y = rows[1] }
                modeButtons[4].apply { w = bw; h = bh; x = cx - bw - gx / 2f; y = rows[2] }
                modeButtons[5].apply { w = bw; h = bh; x = cx + gx / 2f; y = rows[2] }
            }
            Page.SETTINGS -> {
                val bw = sw * 0.30f; val bh = sh * 0.12f; val gx = sw * 0.02f
                settingsButtons[0].apply { w = bw; h = bh; x = cx - bw - gx / 2f; y = sh * 0.46f }
                settingsButtons[1].apply { w = bw; h = bh; x = cx + gx / 2f; y = sh * 0.46f }
                settingsButtons[2].apply { w = bw; h = bh; x = cx - bw - gx / 2f; y = sh * 0.30f }
                settingsButtons[3].apply { w = bw; h = bh; x = cx + gx / 2f; y = sh * 0.30f }
            }
            Page.LAN -> {
                lanBtns.forEachIndexed { i, b -> b.w = sw * 0.64f; b.h = sh * 0.095f; b.x = cx - b.w / 2f; b.y = sh * (0.62f - 0.12f * i) }
                scanAgain.apply { w = sw * 0.24f; h = sh * 0.09f; x = cx - w / 2f; y = sh * 0.07f }
            }
        }
        if (page != Page.MAIN) backBtn.apply { w = sh * 0.11f; h = sh * 0.11f; x = sw * 0.03f; y = sh * 0.86f }
    }

    private fun drawBtn(b: Btn) {
        batch.draw(if (b.green) rGreen else rDark, b.x, b.y, b.w, b.h)
        font.data.setScale(b.scale * u)
        font.color = Color.WHITE
        font.draw(batch, b.label, b.x, b.y + b.h / 2f + font.capHeight / 2f, b.w, Align.center, false)
    }

    private fun label(s: String, y: Float, scale: Float, sw: Float, a: Float = 0.9f) {
        font.data.setScale(scale * u)
        font.color = Color(1f, 1f, 1f, a)
        font.draw(batch, s, 0f, y, sw, Align.center, false)
    }

    override fun render(delta: Float) {
        val sw = Gdx.graphics.width.toFloat(); val sh = Gdx.graphics.height.toFloat()
        if (page == Page.LAN && lanVersion != scanner.version) rebuildLan()
        if (host != shownHost) {
            shownHost = host
            serverBtn.label = if (host.isBlank()) "SERVER: not set  (tap to enter)" else "SERVER: $host   (tap to change)"
        }
        layout(sw, sh)

        if (Gdx.input.isKeyJustPressed(Input.Keys.BACK)) {
            if (page == Page.MAIN) Gdx.app.exit() else backBtn.action()
        } else if (Gdx.input.justTouched() && !asking && !busy) {
            val tx = Gdx.input.x.toFloat(); val ty = sh - Gdx.input.y
            val list: List<Btn> = when (page) {
                Page.MAIN -> mainButtons
                Page.MODES -> modeButtons + serverBtn + backBtn
                Page.LAN -> lanBtns + scanAgain + backBtn
                Page.SETTINGS -> settingsButtons + backBtn
            }
            list.firstOrNull { it.hit(tx, ty) }?.action?.invoke()
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

        when (page) {
            Page.MAIN -> {
                val lw = sw * 0.34f; val lh = lw * rLogo.regionHeight / rLogo.regionWidth
                batch.draw(rLogo, sw / 2f - lw / 2f, sh * 0.93f - lh, lw, lh)
                val r = sh * 0.07f
                batch.draw(rBlue, sw * 0.22f, sh * 0.55f, r * 2f, r * 2f)
                batch.draw(rRed, sw * 0.78f - r * 2f, sh * 0.55f, r * 2f, r * 2f)
                batch.draw(rBall, sw / 2f - r * 0.5f, gh * 0.8f, r, r)
                for (b in mainButtons) drawBtn(b)
            }
            Page.MODES -> {
                label("SELECT MODE", sh * 0.95f, 2.8f, sw, 1f)
                label("Same Wi-Fi: FIND ON WI-FI or HOST     |     Internet: enter a server address", sh * 0.865f, 1.15f, sw, 0.75f)
                drawBtn(backBtn); drawBtn(serverBtn)
                for (b in modeButtons) drawBtn(b)
            }
            Page.SETTINGS -> {
                label("SETTINGS", sh * 0.95f, 2.8f, sw, 1f)
                drawBtn(backBtn)
                label(if (host.isBlank()) "Server: not set" else "Server: $host", sh * 0.80f, 1.3f, sw)
                label("Log file: $logPath", sh * 0.74f, 1.1f, sw, 0.8f)
                label(logInfo, sh * 0.69f, 1.1f, sw, 0.8f)
                for (b in settingsButtons) drawBtn(b)
                label("After an error: SHARE LOG and send it to the developer.", sh * 0.17f, 1.15f, sw, 0.75f)
            }
            Page.LAN -> {
                label("FIND ON WI-FI", sh * 0.95f, 2.8f, sw, 1f)
                drawBtn(backBtn)
                val msg = when {
                    scanner.scanning && lanBtns.isEmpty() -> "Scanning your Wi-Fi..."
                    lanBtns.isEmpty() -> "No games found. Join the same Wi-Fi as the host\nand ask them to press HOST ON THIS PHONE."
                    scanner.scanning -> "Scanning... tap a game to join"
                    else -> "Tap a game to join"
                }
                label(msg, sh * 0.84f, 1.35f, sw, 0.9f)
                for (b in lanBtns) drawBtn(b)
                drawBtn(scanAgain)
            }
        }

        if (status.isNotEmpty()) label(status, sh * 0.05f, 1.3f, sw)
        batch.end()
    }

    override fun dispose() { scanner.stop(); batch.dispose(); font.dispose(); atlas.dispose(); grass.dispose() }
}
