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

    private class Btn(var label: String, var green: Boolean, val scale: Float, val action: () -> Unit) {
        var x = 0f; var y = 0f; var w = 0f; var h = 0f
        fun hit(px: Float, py: Float) = px in x..(x + w) && py in y..(y + h)
    }

    private var page = Page.MAIN
    private val prefs = Gdx.app.getPreferences("soccer")
    private val cam = OrthographicCamera()
    private val batch = SpriteBatch()
    private val shapes = com.badlogic.gdx.graphics.glutils.ShapeRenderer()
    private val font = BitmapFont().also { f -> for (r in f.regions) r.texture.setFilter(com.badlogic.gdx.graphics.Texture.TextureFilter.Linear, com.badlogic.gdx.graphics.Texture.TextureFilter.Linear) }
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
    private val nameBtn = Btn("", false, 1.5f) { askName() }
    private val joyOpts = Settings.JOY_STEPS.map { v -> Btn("${(v * 100).toInt()}%", false, 1.4f) { Settings.joySens = v; refreshSettingsLabels() } }
    private val btnOpts = Settings.BTN_STEPS.map { v -> Btn("${(v * 100).toInt()}%", false, 1.4f) { Settings.btnScale = v; refreshSettingsLabels() } }
    private val handR = Btn("RIGHT", false, 1.4f) { Settings.leftHanded = false; refreshSettingsLabels() }
    private val handL = Btn("LEFT", false, 1.4f) { Settings.leftHanded = true; refreshSettingsLabels() }
    private val soundOn = Btn("ON", false, 1.4f) { Settings.sound = true; refreshSettingsLabels() }
    private val soundOff = Btn("OFF", false, 1.4f) { Settings.sound = false; refreshSettingsLabels() }
    private val settingsList = listOf(nameBtn) + joyOpts + btnOpts + listOf(handR, handL, soundOn, soundOff)
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
        nameBtn.label = Settings.playerName.ifBlank { "Enter name" }
        joyOpts.forEachIndexed { i, b -> b.green = kotlin.math.abs(Settings.JOY_STEPS[i] - Settings.joySens) < 0.01f }
        btnOpts.forEachIndexed { i, b -> b.green = kotlin.math.abs(Settings.BTN_STEPS[i] - Settings.btnScale) < 0.01f }
        handR.green = !Settings.leftHanded
        handL.green = Settings.leftHanded
        soundOn.green = Settings.sound
        soundOff.green = !Settings.sound
    }

    private fun placeSettings(sw: Float, sh: Float) {
        if (page == Page.SETTINGS) {
            backBtn.apply { w = sh * 0.11f; h = sh * 0.11f; x = sw * 0.03f; y = sh * 0.86f }
            val px = sw * 0.06f; val pw = sw * 0.88f
            val inX = px + pw * 0.08f; val inW = pw * 0.84f
            nameBtn.apply { x = inX; y = sh * 0.72f; w = inW; h = sh * 0.07f }
            joyOpts.forEachIndexed { i, b -> val g = inW * 0.02f; val bw = (inW - g * 4f) / 5f; b.apply { x = inX + i * (bw + g); y = sh * 0.59f; w = bw; h = sh * 0.07f } }
            btnOpts.forEachIndexed { i, b -> val g = inW * 0.02f; val bw = (inW - g * 2f) / 3f; b.apply { x = inX + i * (bw + g); y = sh * 0.44f; w = bw; h = sh * 0.07f } }
            handR.apply { x = inX; y = sh * 0.20f; w = inW * 0.48f; h = sh * 0.18f }
            handL.apply { x = inX + inW * 0.52f; y = sh * 0.20f; w = inW * 0.48f; h = sh * 0.18f }
            soundOn.apply { x = inX + inW * 0.55f; y = sh * 0.085f; w = inW * 0.2f; h = sh * 0.06f }
            soundOff.apply { x = inX + inW * 0.77f; y = sh * 0.085f; w = inW * 0.2f; h = sh * 0.06f }
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
            // 3 ta karta: chapda VS BOTS, o'rtada MULTIPLAYER (biroz baland), o'ngda SETTINGS
            val cw = sw * 0.27f; val ch = sh * 0.50f; val gap = sw * 0.03f
            val x0 = (sw - (cw * 3f + gap * 2f)) / 2f; val y0 = sh * 0.20f
            mainButtons[1].apply { w = cw; h = ch; x = x0; y = y0 }
            mainButtons[0].apply { w = cw; h = ch * 1.06f; x = x0 + cw + gap; y = y0 }
            settingsBtn.apply { w = cw; h = ch; x = x0 + 2f * (cw + gap); y = y0 }
        } else {
            val cw = sw * 0.43f; val cy0 = sh * 0.33f; val chh = sh * 0.49f
            createBtn.apply { x = sw * 0.05f + cw * 0.1f; y = cy0 + chh * 0.05f; w = cw * 0.8f; h = chh * 0.14f }
            joinBtn.apply { x = sw * 0.52f + cw * 0.1f; y = cy0 + chh * 0.05f; w = cw * 0.8f; h = chh * 0.14f }
            joinList.forEachIndexed { i, b -> b.x = sw * 0.52f + cw * 0.1f; b.w = cw * 0.8f; b.h = sh * 0.06f; b.y = sh * (0.21f - 0.07f * i) }
            backBtn.apply { w = sh * 0.11f; h = sh * 0.11f; x = sw * 0.03f; y = sh * 0.86f }
        }
    }

    /** Bosh sahifa kartasi: rang, yuqori rasm qismi, pastki matn qismi */
    private class CardSpec(val btn: Btn, val glow: Color, val top: Color, val icon: Int, val title: String, val sub: String)

    private fun cardSpecs() = listOf(
        CardSpec(mainButtons[1], Color(0.2f, 0.6f, 1f, 1f), Color(0.05f, 0.3f, 0.75f, 1f), 0, "VS BOTS", "Play against AI\nand improve your skills."),
        CardSpec(mainButtons[0], Color(1f, 0.55f, 0.1f, 1f), Color(0.3f, 0.12f, 0.02f, 1f), 1, "MULTIPLAYER", "Play with your friends\nor online players."),
        CardSpec(settingsBtn, Color(0.6f, 0.3f, 1f, 1f), Color(0.22f, 0.08f, 0.45f, 1f), 2, "SETTINGS", "Customize your game\nexperience.")
    )

    private fun drawCards(sw: Float, sh: Float) {
        shapes.projectionMatrix = cam.combined
        shapes.begin(com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType.Filled)
        for (c in cardSpecs()) drawCardShape(c)
        shapes.end()
    }

    private fun drawCardShape(c: CardSpec) {
        val b = c.btn; val x = b.x; val y = b.y; val w = b.w; val h = b.h
        shapes.color = Color(c.glow.r, c.glow.g, c.glow.b, 0.35f); shapes.rect(x - 7f, y - 7f, w + 14f, h + 14f)
        shapes.color = c.glow; shapes.rect(x - 3f, y - 3f, w + 6f, h + 6f)
        shapes.color = c.top; shapes.rect(x, y + h * 0.36f, w, h * 0.64f)
        shapes.color = Color(0.04f, 0.06f, 0.14f, 1f); shapes.rect(x, y, w, h * 0.36f)
        val cx = x + w / 2f; val cy = y + h * 0.66f; val s = w * 0.22f
        when (c.icon) {
            0 -> { // VS BOTS: ko'k to'p, robot, futbol to'pi
                shapes.color = Color(0.25f, 0.7f, 1f, 1f); shapes.circle(cx - s * 0.35f, cy - s * 0.1f, s * 0.7f, 32)
                shapes.color = Color(0.9f, 0.95f, 1f, 1f); shapes.circle(cx + s * 0.6f, cy + s * 0.3f, s * 0.5f, 32)
                shapes.color = Color(1f, 0.2f, 0.2f, 1f); shapes.circle(cx + s * 0.6f, cy + s * 0.32f, s * 0.12f, 16)
                shapes.color = Color(0.1f, 0.1f, 0.12f, 1f); shapes.circle(cx + s * 0.05f, cy - s * 0.5f, s * 0.3f, 24)
                shapes.color = Color(1f, 1f, 1f, 1f); shapes.circle(cx + s * 0.05f, cy - s * 0.5f, s * 0.22f, 24)
            }
            1 -> { // MULTIPLAYER: ko'k va qizil to'plar (VS yozuvi pastda)
                shapes.color = Color(0.2f, 0.6f, 1f, 1f); shapes.circle(cx - s * 0.7f, cy - s * 0.2f, s * 0.55f, 32); shapes.circle(cx - s * 0.2f, cy - s * 0.45f, s * 0.45f, 32)
                shapes.color = Color(1f, 0.2f, 0.2f, 1f); shapes.circle(cx + s * 0.35f, cy - s * 0.2f, s * 0.55f, 32); shapes.circle(cx + s * 0.85f, cy - s * 0.45f, s * 0.45f, 32)
            }
            else -> { // SETTINGS: tishli g'ildirak va futbol to'pi
                shapes.color = Color(0.85f, 0.87f, 0.95f, 1f); shapes.circle(cx, cy, s * 0.85f, 32)
                for (k in 0 until 8) {
                    val a = Math.toRadians(k * 45.0)
                    val px = cx + kotlin.math.cos(a).toFloat() * s * 0.95f; val py = cy + kotlin.math.sin(a).toFloat() * s * 0.95f
                    shapes.rect(px - s * 0.14f, py - s * 0.14f, s * 0.28f, s * 0.28f)
                }
                shapes.color = c.top; shapes.circle(cx, cy, s * 0.45f, 32)
                shapes.color = Color(1f, 1f, 1f, 1f); shapes.circle(cx, cy, s * 0.3f, 24)
            }
        }
    }

    private fun drawCardLabels() {
        for (c in cardSpecs()) {
            val b = c.btn
            label(c.title, b.y + b.h * 0.30f, 2.4f, b.x, b.w, 1f)
            for ((i, ln) in c.sub.split("\n").withIndex()) label(ln, b.y + b.h * (0.21f - 0.07f * i), 1.35f, b.x, b.w, 0.9f)
            if (c.icon == 1) label("VS", b.y + b.h * 0.62f, 2.0f, b.x, b.w, 1f)
        }
    }

    /** Qo'shimcha: chapga tekislangan matn */
    private fun labelLeft(s: String, x: Float, y: Float, scale: Float) {
        font.data.setScale(scale * u)
        font.color = Color(1f, 1f, 1f, 0.92f)
        font.draw(batch, s, x, y)
    }

    private fun drawSettingsPanel(sw: Float, sh: Float) {
        shapes.projectionMatrix = cam.combined
        shapes.begin(com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType.Filled)
        val px = sw * 0.06f; val pw = sw * 0.88f; val py = sh * 0.08f; val ph = sh * 0.84f
        shapes.color = Color(0.2f, 0.6f, 1f, 0.35f); shapes.rect(px - 6f, py - 6f, pw + 12f, ph + 12f)
        shapes.color = Color(0.1f, 0.5f, 1f, 1f); shapes.rect(px - 3f, py - 3f, pw + 6f, ph + 6f)
        shapes.color = Color(0.03f, 0.07f, 0.18f, 0.97f); shapes.rect(px, py, pw, ph)
        val sel = if (Settings.leftHanded) handL else handR
        for (b in listOf(handR, handL)) {
            if (b === sel) { shapes.color = Color(0.35f, 0.75f, 1f, 1f); shapes.rect(b.x - 3f, b.y - 3f, b.w + 6f, b.h + 6f) }
            shapes.color = Color(0.06f, 0.12f, 0.3f, 1f); shapes.rect(b.x, b.y, b.w, b.h)
        }
        drawHandIcon(handR.x, handR.y, handR.w, handR.h, false)
        drawHandIcon(handL.x, handL.y, handL.w, handL.h, true)
        shapes.end()
    }

    /** Qo'l tartibi ko'rinishi: chap qo'l = tugmalar chapda, joystik o'ngda */
    private fun drawHandIcon(x: Float, y: Float, w: Float, h: Float, left: Boolean) {
        val cy = y + h * 0.52f
        val jx = if (left) x + w * 0.78f else x + w * 0.22f
        shapes.color = Color(0.2f, 0.6f, 1f, 1f); shapes.circle(jx, cy, h * 0.2f, 32)
        val pAx = if (left) x + w * 0.22f else x + w * 0.56f
        val sAx = if (left) x + w * 0.44f else x + w * 0.78f
        shapes.color = Color(0.2f, 0.9f, 0.3f, 1f); shapes.circle(pAx, cy, h * 0.12f, 24)
        shapes.color = Color(0.95f, 0.2f, 0.2f, 1f); shapes.circle(sAx, cy, h * 0.12f, 24)
    }

    private fun drawSettingsContent(sw: Float, sh: Float) {
        val inX = nameBtn.x
        labelLeft("NAME:", inX, sh * 0.82f, 1.5f)
        drawBtn(nameBtn)
        labelLeft("JOYSTICK SENSITIVITY:", inX, sh * 0.685f, 1.4f)
        for (b in joyOpts) drawBtn(b)
        labelLeft("BUTTON SIZE:", inX, sh * 0.535f, 1.4f)
        for (b in btnOpts) drawBtn(b)
        labelLeft("HAND (CONTROL LAYOUT):", inX, sh * 0.405f, 1.4f)
        label("RIGHT", handR.y + handR.h * 0.12f, 1.5f, handR.x, handR.w, 0.9f)
        label("LEFT", handL.y + handL.h * 0.12f, 1.5f, handL.x, handL.w, 0.9f)
        labelLeft("SOUND:", inX, sh * 0.165f, 1.4f)
        drawBtn(soundOn)
        drawBtn(soundOff)
    }

    private fun drawMultiCards(sw: Float, sh: Float) {
        shapes.projectionMatrix = cam.combined
        shapes.begin(com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType.Filled)
        val cw = sw * 0.43f; val cy0 = sh * 0.33f; val chh = sh * 0.49f
        drawCardBox(sw * 0.05f, cy0, cw, chh, Color(0.2f, 0.8f, 1f, 1f), Color(0.05f, 0.3f, 0.8f, 1f), true)
        drawCardBox(sw * 0.52f, cy0, cw, chh, Color(0.75f, 0.35f, 1f, 1f), Color(0.3f, 0.08f, 0.6f, 1f), false)
        shapes.end()
    }

    private fun drawCardBox(x: Float, y: Float, w: Float, h: Float, glow: Color, top: Color, create: Boolean) {
        shapes.color = Color(glow.r, glow.g, glow.b, 0.35f); shapes.rect(x - 7f, y - 7f, w + 14f, h + 14f)
        shapes.color = glow; shapes.rect(x - 3f, y - 3f, w + 6f, h + 6f)
        shapes.color = top; shapes.rect(x, y + h * 0.42f, w, h * 0.58f)
        shapes.color = Color(0.04f, 0.06f, 0.14f, 1f); shapes.rect(x, y, w, h * 0.42f)
        val cx = x + w / 2f; val cy = y + h * 0.72f; val s = h * 0.12f
        if (create) {
            shapes.color = Color(0.15f, 0.55f, 1f, 1f); shapes.circle(cx - s * 1.1f, cy - s * 0.2f, s * 1.6f, 40)
            shapes.color = Color(0.95f, 0.97f, 1f, 1f); shapes.circle(cx + s * 1.1f, cy - s * 0.5f, s * 0.9f, 32)
            shapes.color = Color(0.1f, 0.1f, 0.12f, 1f); shapes.circle(cx + s * 1.1f, cy - s * 0.5f, s * 0.4f, 20)
        } else {
            shapes.color = Color(1f, 0.2f, 0.2f, 1f); shapes.circle(cx - s * 1.1f, cy - s * 0.2f, s * 1.6f, 40)
            shapes.color = Color(0.85f, 0.9f, 1f, 1f); shapes.rect(cx + s * 0.2f, cy - s * 0.9f, s * 2.6f, s * 1.0f)
            shapes.color = Color(0.2f, 0.3f, 0.6f, 1f)
            for (k in 0 until 4) shapes.circle(cx + s * (0.6f + k * 0.6f), cy - s * 0.4f, s * 0.12f, 12)
        }
    }

    private fun drawMultiContent(sw: Float, sh: Float) {
        val cw = sw * 0.43f; val cy0 = sh * 0.33f; val chh = sh * 0.49f
        val lx = sw * 0.05f; val rx = sw * 0.52f
        label("CREATE GAME", cy0 + chh * 0.39f, 2.1f, lx, cw, 1f)
        label("Host a game and invite", cy0 + chh * 0.27f, 1.3f, lx, cw, 0.9f)
        label("your friends to join.", cy0 + chh * 0.215f, 1.3f, lx, cw, 0.9f)
        label("JOIN BY IP ADDRESS", cy0 + chh * 0.39f, 2.1f, rx, cw, 1f)
        label("Enter your friend's IP address", cy0 + chh * 0.27f, 1.3f, rx, cw, 0.9f)
        label("to join their game.", cy0 + chh * 0.215f, 1.3f, rx, cw, 0.9f)
        drawBtn(createBtn)
        drawBtn(joinBtn)
        if (joinList.isEmpty()) {
            label(if (scanner.scanning) "Looking for games on your Wi-Fi..." else "No recent games yet.", sh * 0.30f, 1.2f, rx, cw, 0.8f)
        } else {
            label("Nearby and recent", sh * 0.30f, 1.2f, rx, cw, 0.8f)
            for (b in joinList) drawBtn(b)
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
            // logotip chap yuqorida (uy belgisi va coin yo'q)
            val lw = sw * 0.26f; val lh = lw * rLogo.regionHeight / rLogo.regionWidth
            batch.draw(rLogo, sw * 0.04f, sh * 0.93f - lh, lw, lh)
            val r = sh * 0.07f
            batch.draw(rBall, sw / 2f - r * 0.5f, gh * 0.8f, r, r)
            batch.end()
            drawCards(sw, sh)
            batch.begin()
            drawCardLabels()
        } else if (page == Page.SETTINGS) {
            batch.end()
            drawSettingsPanel(sw, sh)
            batch.begin()
            label("SETTINGS", sh * 0.93f, 2.8f, 0f, sw, 1f)
            drawBtn(backBtn)
            drawSettingsContent(sw, sh)
        } else {
            batch.end()
            drawMultiCards(sw, sh)
            batch.begin()
            label("MULTIPLAYER", sh * 0.95f, 2.8f, 0f, sw, 1f)
            label("PLAY WITH YOUR FRIENDS", sh * 0.885f, 1.2f, 0f, sw, 0.8f)
            drawBtn(backBtn)
            drawMultiContent(sw, sh)
        }

        if (status.isNotEmpty()) label(status, sh * 0.05f, 1.3f, 0f, sw)
        batch.end()
    }

    override fun dispose() { scanner.stop(); batch.dispose(); shapes.dispose(); font.dispose(); atlas.dispose(); grass.dispose() }
}
