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
import kotlin.concurrent.thread

/**
 * Bosh menyu: faqat MULTIPLAYER va VS BOTS.
 * MULTIPLAYER: chapda katta CREATE MATCH, o'ngda JOIN BY IP ADDRESS (+ oldin kiritilgan va Wi-Fi'da topilgan manzillar).
 */
class MenuScreen(private val game: Game) : ScreenAdapter() {
    private enum class Page { MAIN, MULTI, SETTINGS }

    private class Btn(var label: String, val green: Boolean, val scale: Float, val action: () -> Unit) {
        var x = 0f; var y = 0f; var w = 0f; var h = 0f
        fun hit(px: Float, py: Float) = px in x..(x + w) && py in y..(y + h)
    }

    private var page = Page.MAIN
    private val prefs = Gdx.app.getPreferences("soccer")
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

    // ---- oldin kiritilgan IP manzillar (eng yangisi birinchi) ----
    private val hosts: MutableList<String> = (prefs.getString("hosts", "") ?: "")
        .split(',').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()

    private fun rememberHost(ip: String) {
        hosts.remove(ip); hosts.add(0, ip)
        while (hosts.size > 6) hosts.removeAt(hosts.size - 1)
        prefs.putString("hosts", hosts.joinToString(",")); prefs.flush()
    }

    // ---- Wi-Fi'da avtomatik topilgan o'yinlar ----
    private val scanner = LanScanner()
    private var lanVersion = -1
    private var joinList = ArrayList<Btn>()

    private val mainButtons = listOf(
        Btn("MULTIPLAYER", true, 2.4f) { page = Page.MULTI; scanner.start(); lanVersion = -1 },
        Btn("VS BOTS", true, 2.4f) { playBots() }
    )
    private val createBtn = Btn("CREATE MATCH", true, 3.0f) { createMatch() }
    private val joinBtn = Btn("JOIN BY IP ADDRESS", false, 1.8f) {
        ask("Host IP address (e.g. 192.168.1.5)", hosts.firstOrNull() ?: "") { t -> if (t.isNotBlank()) joinTo(t.trim()) }
    }
    private val backBtn = Btn("<", false, 2.6f) { scanner.stop(); page = Page.MAIN }

    // ---- sozlamalar ----
    private val joyBtn = Btn("", false, 1.7f) { Settings.joySens = Settings.cycle(Settings.joySens, Settings.JOY_STEPS); refreshSettingsLabels() }
    private val btnBtn = Btn("", false, 1.7f) { Settings.btnScale = Settings.cycle(Settings.btnScale, Settings.BTN_STEPS); refreshSettingsLabels() }
    private val handBtn = Btn("", false, 1.7f) { Settings.leftHanded = !Settings.leftHanded; refreshSettingsLabels() }
    private val soundBtn = Btn("", false, 1.7f) { Settings.sound = !Settings.sound; refreshSettingsLabels() }
    private val tipsBtn = Btn("", false, 1.7f) { Settings.tipsSeen = false; refreshSettingsLabels(); status = "Tips show again in the next match" }
    private val nameBtn = Btn("", false, 1.7f) { askName() }
    private val settingsList = listOf(nameBtn, joyBtn, btnBtn, handBtn, soundBtn, tipsBtn)
    private var nameAsked = false

    /** Ism so'rash: telefon klaviaturasi bilan */
    private fun askName() {
        Gdx.input.getTextInput(object : Input.TextInputListener {
            override fun input(text: String) {
                Settings.playerName = text
                refreshSettingsLabels()
            }
            override fun canceled() {}
        }, "Your name", Settings.playerName, "up to 12 characters")
    }
    private val settingsBtn = Btn("SETTINGS", false, 1.8f) { page = Page.SETTINGS; refreshSettingsLabels() }

    private fun refreshSettingsLabels() {
        joyBtn.label = "JOYSTICK: ${(Settings.joySens * 100).toInt()}%"
        btnBtn.label = "BUTTON SIZE: ${(Settings.btnScale * 100).toInt()}%"
        handBtn.label = if (Settings.leftHanded) "HAND: LEFT" else "HAND: RIGHT"
        soundBtn.label = if (Settings.sound) "SOUND: ON" else "SOUND: OFF"
        tipsBtn.label = "SHOW TIPS AGAIN"
        nameBtn.label = "NAME: ${Settings.playerName.ifBlank { "-" }}"
    }

    private fun placeSettings(sw: Float, sh: Float) {
        if (page == Page.SETTINGS) {
            backBtn.apply { w = sh * 0.11f; h = sh * 0.11f; x = sw * 0.03f; y = sh * 0.86f }
            settingsList.forEachIndexed { i, b -> b.apply { w = sw * 0.44f; h = sh * 0.095f; x = sw * 0.28f; y = sh * (0.70f - 0.12f * i) } }
        } else if (page == Page.MAIN) {
            settingsBtn.apply { w = sw * 0.26f; h = sh * 0.085f; x = sw / 2f - w / 2f; y = sh * 0.14f }
        }
    }

    init {
        Gdx.input.inputProcessor = null
        Gdx.input.setCatchKey(Input.Keys.BACK, true)
    }

    override fun resize(width: Int, height: Int) {
        cam.setToOrtho(false, width.toFloat(), height.toFloat())
        u = height / 1080f
    }

    private fun open(host: String, code: Int, local: Boolean, info: String?, myIp: String?) {
        scanner.stop()
        Gdx.app.postRunnable { game.setScreen(GameScreen(game, host, code, local, info, myIp)); dispose() }
    }

    /** IP bo'yicha qo'shilish: shu serverdagi (boshliq yaratgan) xonaga. Manzil keyingi safar variant sifatida ko'rinadi. */
    private fun joinTo(ip: String) { rememberHost(ip); open(ip, -2, false, null, null) }

    /** CREATE MATCH: server shu telefonda, o'yin lobbi ochiladi (START bosilmaguncha boshlanmaydi) */
    private fun createMatch() {
        if (busy) return
        busy = true; status = "Creating match..."
        thread {
            val ok = LocalServer.start(botCount = 0, loopbackOnly = false)
            Gdx.app.postRunnable {
                busy = false
                if (ok) {
                    status = ""
                    val ip = LocalServer.localIp()
                    open("127.0.0.1", 0, true,
                        if (ip != null) "Friends: MULTIPLAYER > JOIN BY IP ADDRESS" else "No Wi-Fi found: connect to Wi-Fi (or turn on hotspot)", ip)
                } else status = "Cannot create match (port ${C.PORT} busy?)"
            }
        }
    }

    /** Offline: server faqat shu telefonda (loopback). START bosilganda bo'sh o'rinlarni botlar to'ldiradi */
    private fun playBots() {
        if (busy) return
        busy = true; status = "Starting..."
        thread {
            val ok = LocalServer.start(botCount = 0, loopbackOnly = true)
            Gdx.app.postRunnable {
                busy = false
                if (ok) { status = ""; open("127.0.0.1", 0, true, null, null) }
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

    /** O'ng tomondagi ro'yxat: avval Wi-Fi'da topilganlar, keyin oldin kiritilganlar (takrorlarsiz, 4 tagacha) */
    private fun rebuildJoinList() {
        joinList = ArrayList()
        val seen = HashSet<String>()
        for (f in scanner.results) {
            if (joinList.size >= 4) break
            if (!seen.add(f.ip)) continue
            joinList.add(Btn("${f.name}   ${f.ip}", true, 1.4f) { joinTo(f.ip) })
        }
        for (ip in hosts) {
            if (joinList.size >= 4) break
            if (!seen.add(ip)) continue
            joinList.add(Btn(ip, false, 1.5f) { joinTo(ip) })
        }
        lanVersion = scanner.version
    }

    private fun layout(sw: Float, sh: Float) {
        val cx = sw / 2f
        if (page == Page.MAIN) {
            val bw = sw * 0.27f; val bh = sh * 0.17f; val gap = sw * 0.02f
            mainButtons[0].apply { w = bw; h = bh; x = cx - bw - gap / 2f; y = sh * 0.30f }
            mainButtons[1].apply { w = bw; h = bh; x = cx + gap / 2f; y = sh * 0.30f }
        } else {
            createBtn.apply { x = sw * 0.05f; y = sh * 0.12f; w = sw * 0.43f; h = sh * 0.68f }
            joinBtn.apply { x = sw * 0.52f; y = sh * 0.60f; w = sw * 0.43f; h = sh * 0.20f }
            joinList.forEachIndexed { i, b -> b.x = sw * 0.52f; b.w = sw * 0.43f; b.h = sh * 0.095f; b.y = sh * (0.46f - 0.115f * i) }
            backBtn.apply { w = sh * 0.11f; h = sh * 0.11f; x = sw * 0.03f; y = sh * 0.86f }
        }
    }

    private fun drawBtn(b: Btn) {
        batch.draw(if (b.green) rGreen else rDark, b.x, b.y, b.w, b.h)
        font.data.setScale(b.scale * u)
        font.color = Color.WHITE
        font.draw(batch, b.label, b.x, b.y + b.h / 2f + font.capHeight / 2f, b.w, Align.center, false)
    }

    private fun label(s: String, y: Float, scale: Float, x: Float, w: Float, a: Float = 0.9f) {
        font.data.setScale(scale * u)
        font.color = Color(1f, 1f, 1f, a)
        font.draw(batch, s, x, y, w, Align.center, false)
    }

    override fun render(delta: Float) {
        val sw = Gdx.graphics.width.toFloat(); val sh = Gdx.graphics.height.toFloat()
        if (page == Page.MULTI && lanVersion != scanner.version) rebuildJoinList()
        layout(sw, sh)
        placeSettings(sw, sh)
        if (!nameAsked) { nameAsked = true; if (Settings.playerName.isBlank()) askName() }

        if (Gdx.input.isKeyJustPressed(Input.Keys.BACK)) {
            if (page == Page.MAIN) Gdx.app.exit() else backBtn.action()
        } else if (Gdx.input.justTouched() && !asking && !busy) {
            val tx = Gdx.input.x.toFloat(); val ty = sh - Gdx.input.y
            val list: List<Btn> = when (page) {
                Page.MAIN -> mainButtons + settingsBtn
                Page.SETTINGS -> settingsList + backBtn
                Page.MULTI -> listOf(createBtn, joinBtn, backBtn) + joinList
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

        if (page == Page.MAIN) {
            val lw = sw * 0.34f; val lh = lw * rLogo.regionHeight / rLogo.regionWidth
            batch.draw(rLogo, sw / 2f - lw / 2f, sh * 0.93f - lh, lw, lh)
            val r = sh * 0.07f
            batch.draw(rBlue, sw * 0.22f, sh * 0.55f, r * 2f, r * 2f)
            batch.draw(rRed, sw * 0.78f - r * 2f, sh * 0.55f, r * 2f, r * 2f)
            batch.draw(rBall, sw / 2f - r * 0.5f, gh * 0.8f, r, r)
            for (b in mainButtons) drawBtn(b)
            drawBtn(settingsBtn)
        } else if (page == Page.SETTINGS) {
            label("SETTINGS", sh * 0.95f, 2.8f, 0f, sw, 1f)
            drawBtn(backBtn)
            for (b in settingsList) drawBtn(b)
        } else {
            label("MULTIPLAYER", sh * 0.95f, 2.8f, 0f, sw, 1f)
            drawBtn(backBtn)

            // chap yarmi: katta CREATE MATCH
            drawBtn(createBtn)
            label("Host a match on this phone.", createBtn.y + createBtn.h * 0.36f, 1.3f, createBtn.x, createBtn.w, 0.9f)
            label("Friends join with your IP address.", createBtn.y + createBtn.h * 0.28f, 1.3f, createBtn.x, createBtn.w, 0.9f)
            label("You press START when everyone is in.", createBtn.y + createBtn.h * 0.20f, 1.3f, createBtn.x, createBtn.w, 0.9f)

            // o'ng yarmi: IP bo'yicha qo'shilish + oldingi / topilgan manzillar
            drawBtn(joinBtn)
            val rx = sw * 0.52f; val rw = sw * 0.43f
            if (joinList.isEmpty()) {
                label(if (scanner.scanning) "Looking for games on your Wi-Fi..." else "No recent games yet.", sh * 0.50f, 1.2f, rx, rw, 0.8f)
            } else {
                label("Nearby and recent", sh * 0.575f, 1.2f, rx, rw, 0.8f)
                for (b in joinList) drawBtn(b)
            }
        }

        if (status.isNotEmpty()) label(status, sh * 0.05f, 1.3f, 0f, sw)
        batch.end()
    }

    override fun dispose() { scanner.stop(); batch.dispose(); font.dispose(); atlas.dispose(); grass.dispose() }
}
