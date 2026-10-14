package com.soccer.game

import com.badlogic.gdx.Game
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input as GdxInput
import com.badlogic.gdx.ScreenAdapter
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureAtlas
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.viewport.ExtendViewport
import soccer.shared.*
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow

private const val MIN_ZOOM = 0.8f
private const val MAX_ZOOM = 1.7f
private const val CONNECTING = "Connecting..."
private const val CONNECT_TIMEOUT_MS = 6000L
private val DOTS = arrayOf("", ".", "..", "...")

// ---- dizayn ranglari (2v2 Football maketi) ----
private val BG_COLOR = Color(0.03f, 0.06f, 0.14f, 1f)
private val DIM_COLOR = Color(0f, 0f, 0f, 0.62f)

/** Slot ranglari: 0 blue team = ko'k, havorang, firuza; 1 red team = qizil, to'q sariq, pushti */
private val TINTS = arrayOf(
    Color(0.12f, 0.43f, 1f, 1f), Color(0.24f, 0.78f, 1f, 1f), Color(0.12f, 0.78f, 0.75f, 1f),
    Color(0.92f, 0.16f, 0.2f, 1f), Color(1f, 0.67f, 0.12f, 1f), Color(1f, 0.4f, 0.78f, 1f)
)
private val BALL_NAMES = arrayOf("ball_blue", "ball_cyan", "ball_teal", "ball_red", "ball_orange", "ball_pink")

class GameScreen(
    private val game: Game,
    private val host: String,
    private var joinCode: Int,         // -1 quick match, 0 yangi xona, >0 kod bilan
    private val localMode: Boolean = false,   // server shu telefonda ishlayapti
    private val info: String? = null,         // host uchun: do'stlarga ko'rsatma
    private val myIp: String? = null          // host telefonning Wi-Fi IP manzili (do'stlar shuni kiritadi)
) : ScreenAdapter() {
    private class V { var id = 0; var team = 0; var slot = 0; var ready = false; var x = 0f; var y = 0f; var vx = 0f; var vy = 0f }

    // interpolatsiya natijasi: qayta ishlatiladigan obyektlar (har kadrda allocation yo'q)
    private val vs = Array(C.MAX_PLAYERS) { V() }
    private var vCount = 0
    private var ballX = 0f
    private var ballY = 0f
    private var hasFrame = false

    private val token = kotlin.random.Random.nextLong() or 1L   // sessiya (reconnect uchun)
    private var net = NetClient(host)
    private val local = SoccerWorld(withBall = false)   // faqat o'z o'yinchimiz (prediction)
    private var localId = -1
    private val pending = InputRing()
    private val snaps = ArrayList<Snapshot>()
    private var seq = 0
    private var acc = 0f
    private var joinTimer = 0f
    private var pingTimer = 0f
    private var errX = 0f
    private var errY = 0f
    private var wasPaused = false
    private var time = 0f
    private var connectStart = System.currentTimeMillis()
    private var lastReconnect = 0L
    private var everConnected = false
    private var failureLogged = false

    private val prefs = Gdx.app.getPreferences("soccer")
    private var showPing = prefs.getBoolean("showPing", true)
    private var menuOpen = false

    private val cam = OrthographicCamera()
    private val viewport = ExtendViewport(22f, 13f, cam)   // standart ko'rinish; zoom bilan kengayadi
    private val hudCam = OrthographicCamera()
    private val shapes = ShapeRenderer()
    private val batch = SpriteBatch()
    private val font = BitmapFont()
    private var u = 1f                                      // ekran balandligi / 1080
    private val pad = TouchPad()

    init {
        Gdx.input.inputProcessor = pad
        Gdx.input.setCatchKey(GdxInput.Keys.BACK, true)
    }

    private fun leave() {
        val g = game
        Gdx.app.postRunnable { g.setScreen(MenuScreen(g)); dispose() }
    }

    // ---------------- lifecycle ----------------
    override fun pause() { wasPaused = true; SLog.i("Game", "screen paused"); net.close(leave = false) }

    override fun resume() {
        if (!wasPaused) return
        wasPaused = false
        restartNet()
    }

    /** Qayta ulanish (fondan qaytganda, aloqa uzilganda yoki RETRY): token bir xil -> server o'rnimizni qaytaradi. */
    private fun restartNet() {
        SLog.i("Game", "restart network (room ${net.roomCode}, host '$host')")
        failureLogged = false
        if (net.roomCode > 0) joinCode = net.roomCode   // o'sha xonaga qaytamiz
        net.close(leave = false)
        net = NetClient(host)
        if (localId >= 0) local.removePlayer(localId)
        localId = -1; snaps.clear(); pending.clear(); errX = 0f; errY = 0f
        connectStart = System.currentTimeMillis(); joinTimer = 0f
    }

    override fun resize(width: Int, height: Int) {
        viewport.update(width, height)
        cam.position.set(C.FIELD_W / 2f, C.FIELD_H / 2f, 0f); cam.update()
        hudCam.setToOrtho(false, width.toFloat(), height.toFloat())
        u = height / 1080f
    }

    // ---------------- main loop ----------------
    override fun render(delta: Float) {
        val dt = min(delta, 0.25f)
        time += dt
        if (net.myId < 0) {                       // hali serverga ulanmagan (yoki qayta ulanayapti)
            if (Gdx.input.isKeyJustPressed(GdxInput.Keys.BACK)) { leave(); return }
            val failed = net.error != 0 || System.currentTimeMillis() - connectStart > CONNECT_TIMEOUT_MS
            if (failed && !failureLogged) {
                failureLogged = true
                SLog.w("Game", "connect failed: error=${net.error}, host='$host', waited ${System.currentTimeMillis() - connectStart} ms, local=$localMode")
            }
            if (!failed) {
                joinTimer -= dt
                if (joinTimer <= 0f) { net.send(Codec.join(joinCode, token, matchMinutes())); joinTimer = 0.5f }
            }
            drawConnect(failed)
            if (Gdx.input.justTouched()) handleConnectTouch(failed)
            return
        }
        if (net.error != 0) {                      // serverdan chiqarildik (masalan, qayta o'ynashga ovoz bermadik)
            drawConnect(true)
            if (Gdx.input.justTouched()) handleConnectTouch(true)
            return
        }
        everConnected = true
        val nowMs = System.currentTimeMillis()
        if (nowMs - net.lastRecvMs > 5000 && nowMs - lastReconnect > 3000) {
            lastReconnect = nowMs
            SLog.w("Game", "no packets from server for 5s - reconnecting")
            restartNet(); return          // aloqa uzildi -> avtomatik qayta ulanish
        }

        if (Gdx.input.isKeyJustPressed(GdxInput.Keys.BACK)) menuOpen = !menuOpen
        else if (menuOpen && Gdx.input.justTouched()) handleMenuTouch()
        else if (teamSelectVisible() && Gdx.input.justTouched()) handleTeamTouch()
        else if (endVisible() && Gdx.input.justTouched()) handleEndTouch()
        if (pad.consumePause()) menuOpen = true
        pad.enabled = !menuOpen && !teamSelectVisible() && !endVisible() && !tipsVisible()

        pollNet()

        while (true) {
            val g = net.goals.poll() ?: break
            goalFeed.addFirst(g)
            if (goalFeed.size > 4) goalFeed.removeLast()
        }
        // ovoz: holat o'zgarganda (gol, sanash, tugash)
        val stNow = snaps.lastOrNull()?.state ?: -1
        if (stNow != lastSfxState) {
            lastSfxState = stNow
            when (stNow) {
                State.GOAL -> Sfx.play("goal")
                State.COUNTDOWN, State.END -> Sfx.play("whistle")
            }
        }
        // onboarding: birinchi o'yinda maslahat ekrani bosilguncha turadi
        if (tipsVisible() && Gdx.input.justTouched()) Settings.tipsSeen = true

        if (net.myId < 0) {
            joinTimer -= dt
            if (joinTimer <= 0f) { net.send(Codec.join(joinCode, token, matchMinutes())); joinTimer = 0.5f }
        } else {
            if (localId != net.myId) { localId = net.myId; local.addPlayer(localId, net.myTeam, net.mySlot) }
            acc += dt
            while (acc >= C.DT) { stepLocal(); acc -= C.DT }
        }

        pingTimer -= dt
        if (pingTimer <= 0f) { net.send(Codec.ping(System.currentTimeMillis())); pingTimer = 1f }

        val k = 0.9f.pow(dt * 60f)
        errX *= k; errY *= k

        draw()
        if (nowMs - net.lastRecvMs > 2500) drawBanner("Connection lost - reconnecting...")
    }

    /** Client-side prediction: har fixed tick'da input'ni darhol qo'llab, serverga yuboramiz. */
    private fun stepLocal() {
        seq++
        val on = isPlaying()
        val act = pad.consumeAction()
        val mx = if (on) pad.mx else 0f
        val my = if (on) pad.my else 0f
        val action = if (on) act else Act.NONE
        val power = if (on) pad.actionPower else 0
        if (on && action != Act.NONE) Sfx.play("kick")
        pending.push(seq, mx, my, action, power)
        if (on) { local.applyInput(localId, mx, my, action, power); local.step() }
        net.sendInput(seq, mx, my, action, power)
    }

    private fun isPlaying() = snaps.lastOrNull()?.state == State.PLAYING

    private fun pollNet() {
        while (true) {
            val s = net.snaps.poll() ?: break
            snaps.add(s)
            if (snaps.size > 40) snaps.removeAt(0)
            reconcile(s)
        }
    }

    /** Server reconciliation: server holatiga o'tib, tasdiqlanmagan input'larni qayta o'ynaymiz. */
    private fun reconcile(s: Snapshot) {
        if (localId < 0) return
        val me = s.players.firstOrNull { it.id == localId } ?: return
        val body = local.players[localId] ?: return
        val px = body.position.x; val py = body.position.y

        pending.dropUpTo(me.ack)
        body.setTransform(me.x, me.y, 0f)
        body.setLinearVelocity(me.vx, me.vy)
        if (s.state == State.PLAYING) for (i in 0 until pending.size) {
            local.applyInput(localId, pending.mx(i), pending.my(i), pending.action(i), pending.power(i)); local.step()
        }

        errX += px - body.position.x
        errY += py - body.position.y
        if (hypot(errX, errY) > 2f) { errX = 0f; errY = 0f }   // katta farq -> hard snap
    }

    /** Boshqa o'yinchilar va to'p: 100ms orqada, ikki snapshot orasida lerp. Natija vs[] ga yoziladi. */
    private fun interpolate(): Boolean {
        if (snaps.isEmpty()) return false
        val rt = System.currentTimeMillis() - C.INTERP_MS
        var a = snaps[snaps.size - 1]; var b = a
        if (rt < snaps[0].recvMs) { a = snaps[0]; b = a }
        else for (i in 0 until snaps.size - 1) {
            if (snaps[i].recvMs <= rt && rt < snaps[i + 1].recvMs) { a = snaps[i]; b = snaps[i + 1]; break }
        }
        val span = (b.recvMs - a.recvMs).toFloat()
        val t = if (span <= 0f) 1f else ((rt - a.recvMs) / span).coerceIn(0f, 1f)
        ballX = a.bx + (b.bx - a.bx) * t
        ballY = a.by + (b.by - a.by) * t
        vCount = 0
        val pl = b.players; val al = a.players
        for (i in 0 until min(pl.size, vs.size)) {
            val pb = pl[i]
            var pa: PState? = null
            for (j in 0 until al.size) if (al[j].id == pb.id) { pa = al[j]; break }
            val v = vs[vCount++]
            v.id = pb.id; v.team = pb.team; v.slot = pb.slot; v.vx = pb.vx; v.vy = pb.vy; v.ready = pb.ready
            if (pa == null) { v.x = pb.x; v.y = pb.y }
            else { v.x = pa.x + (pb.x - pa.x) * t; v.y = pa.y + (pb.y - pa.y) * t }
        }
        return true
    }

    // ---------------- camera ----------------
    /** O'z o'yinchimiz markazda, to'p uzoqlashsa zoom out. Hammasi lerp bilan silliq. */
    private fun updateCamera(dt: Float) {
        var px = C.FIELD_W / 2f; var py = C.FIELD_H / 2f
        local.players[localId]?.let { px = it.position.x + errX; py = it.position.y + errY }
        val bx = if (hasFrame) ballX else px
        val by = if (hasFrame) ballY else py

        val tx = (px + (bx - px) * 0.35f).coerceIn(-2f, C.FIELD_W + 2f)
        val ty = (py + (by - py) * 0.35f).coerceIn(-2f, C.FIELD_H + 2f)

        val needHalfW = kotlin.math.abs(bx - px) * 0.65f + 5f
        val needHalfH = kotlin.math.abs(by - py) * 0.65f + 4f
        val zx = needHalfW / (viewport.worldWidth / 2f)
        val zy = needHalfH / (viewport.worldHeight / 2f)
        val tz = maxOf(zx, zy).coerceIn(MIN_ZOOM, MAX_ZOOM)

        val a = min(1f, 5f * dt)
        cam.position.x += (tx - cam.position.x) * a
        cam.position.y += (ty - cam.position.y) * a
        cam.zoom += (tz - cam.zoom) * a
        cam.update()
    }

    // ---------------- grafika (atlas) ----------------
    private val atlas = TextureAtlas(Gdx.files.internal("atlas.atlas"))
    private val field = FieldRenderer(atlas)
    private val rBalls = Array(BALL_NAMES.size) { atlas.findRegion(BALL_NAMES[it]) }
    private val rMarker = atlas.findRegion("marker")
    private val rRing = atlas.findRegion("ring")
    private val rBall = atlas.findRegion("ball")
    private val rShadow = atlas.findRegion("shadow")
    private val rWhite = atlas.findRegion("white")
    private val rPanel = atlas.findRegion("panel")
    private val rScoreBlue = atlas.findRegion("score_blue")
    private val rScoreRed = atlas.findRegion("score_red")
    private val rBtnGreen = atlas.findRegion("btn_green")
    private val rBtnDark = atlas.findRegion("btn_dark")
    private val rGoalBlue = atlas.findRegion("goal_blue")
    private val rGoalRed = atlas.findRegion("goal_red")
    private var ballRot = 0f
    private var hasLastBall = false
    private var lastBX = 0f
    private var lastBY = 0f

    private fun spr(r: TextureRegion, x: Float, y: Float, radius: Float, deg: Float = 0f) =
        batch.draw(r, x - radius, y - radius, radius, radius, radius * 2f, radius * 2f, 1f, 1f, deg)

    /** Matn: (cx, cy) markazda; scale ekran balandligiga nisbatan */
    private fun text(s: String, cx: Float, cy: Float, scale: Float, color: Color = Color.WHITE) {
        font.data.setScale(scale * u)
        font.color = color
        font.draw(batch, s, cx - 400f, cy + font.capHeight / 2f, 800f, Align.center, false)
    }

    /** Matn: (x, y) chap-yuqori burchakda */
    private fun textL(s: String, x: Float, y: Float, scale: Float, color: Color = Color.WHITE) {
        font.data.setScale(scale * u)
        font.color = color
        font.draw(batch, s, x, y)
    }

    private var scoreKey = -1; private var scoreAText = "0"; private var scoreBText = "0"
    private var timeKey = -2; private var timeText = ""
    private var pingKey = -1; private var pingText = ""
    private var roomKey = -1; private var roomText = ""
    private var modeKey = -1; private var modeText = ""
    private var msgKey = -1; private var msgText: String? = null

    private fun scoreStrs(a: Int, b: Int) {
        val k = a * 100 + b
        if (k != scoreKey) { scoreKey = k; scoreAText = "$a"; scoreBText = "$b" }
    }
    private fun timeStr(t: Int): String {
        if (t != timeKey) { timeKey = t; timeText = if (t < 0) "--:--" else "%02d:%02d".format(t / 60, t % 60) }
        return timeText
    }
    private fun pingStr(ms: Int): String {
        if (ms != pingKey) { pingKey = ms; pingText = "$ms ms" }
        return pingText
    }
    private fun roomStr(code: Int): String {
        if (code != roomKey) { roomKey = code; roomText = "Room $code" }
        return roomText
    }
    private fun modeStr(a: Int, b: Int): String {
        val k = a * 10 + b
        if (k != modeKey) { modeKey = k; modeText = "${a}v$b" }
        return modeText
    }
    private fun messageFor(s: Snapshot): String? {
        val key = ((s.state * 1000 + s.timer) * 100 + s.scoreA) * 100 + s.scoreB
        if (key != msgKey) {
            msgKey = key
            msgText = when (s.state) {
                State.LOBBY -> "Waiting for players..."
                State.COUNTDOWN -> "${s.timer}"
                State.GOAL -> "GOAL!"
                State.END -> null            // yakuniy hisob alohida katta ekranda (drawEnd)
                else -> null
            }
        }
        return msgText
    }

    /** o'yinchining jamoasidagi tartibi (id bo'yicha) -> ikonka/rang */
    private fun colorIndex(v: V): Int = (if (v.team == 0) 0 else 3) + min(v.slot, 2)

    // ---------------- pauza menyusi ----------------
    private val menuW get() = Gdx.graphics.width * 0.34f
    private val menuH get() = Gdx.graphics.height * 0.66f
    private val menuX get() = (Gdx.graphics.width - menuW) / 2f
    private val menuY get() = (Gdx.graphics.height - menuH) / 2f
    private fun menuBtnY(i: Int) = menuY + menuH * (0.64f - 0.145f * i)
    private val menuBtnH get() = menuH * 0.115f

    /** Pauza menyusi: VS BOTS da RESTART ham bor */
    private fun menuItems(): List<String> {
        val ping = if (showPing) "PING: ON" else "PING: OFF"
        return if (localMode) listOf("RESUME", "RESTART", ping, "SHARE LOG", "EXIT")
        else listOf("RESUME", ping, "SHARE LOG", "EXIT")
    }

    private fun handleMenuTouch() {
        val tx = Gdx.input.x.toFloat(); val ty = Gdx.graphics.height - Gdx.input.y.toFloat()
        val bx = menuX + menuW * 0.1f; val bw = menuW * 0.8f
        val items = menuItems()
        for (i in items.indices) {
            val by = menuBtnY(i)
            if (tx in bx..(bx + bw) && ty in by..(by + menuBtnH)) {
                when (items[i]) {
                    "RESUME" -> menuOpen = false
                    "RESTART" -> { net.send(Codec.restart()); menuOpen = false }
                    "SHARE LOG" -> AppLog.share()
                    "EXIT" -> leave()
                    else -> { showPing = !showPing; prefs.putBoolean("showPing", showPing); prefs.flush() }
                }
                return
            }
        }
    }

    // ---------------- Select Teams (lobby) ----------------
    private fun matchMinutes() = prefs.getInteger("matchMinutes", 2).coerceIn(C.MATCH_MIN, C.MATCH_MAX)

    // ---------------- o'yin tugadi: katta hisob + PLAY AGAIN / HOME ----------------
    private fun endVisible() = net.myId >= 0 && snaps.lastOrNull()?.state == State.END

    private val ewW get() = Gdx.graphics.width * 0.64f
    private val ewH get() = Gdx.graphics.height * 0.80f
    private val ewX get() = (Gdx.graphics.width - ewW) / 2f
    private val ewY get() = (Gdx.graphics.height - ewH) / 2f
    private val ebW get() = ewW * 0.40f
    private val ebH get() = ewH * 0.13f
    private val ebY get() = ewY + ewH * 0.07f
    private val ebAgainX get() = ewX + ewW * 0.07f
    private val ebHomeX get() = ewX + ewW * 0.53f

    private fun humansReady(): Int { var n = 0; for (k in 0 until vCount) if (vs[k].id < C.BOT_ID_BASE && vs[k].ready) n++; return n }
    private fun humansTotal(): Int { var n = 0; for (k in 0 until vCount) if (vs[k].id < C.BOT_ID_BASE) n++; return n }
    private var voteKey = -1; private var voteText = ""
    private fun voteStr(a: Int, b: Int): String { val k = a * 10 + b; if (k != voteKey) { voteKey = k; voteText = "Ready to play again: $a / $b" }; return voteText }

    private fun handleEndTouch() {
        val tx = Gdx.input.x.toFloat(); val ty = Gdx.graphics.height - Gdx.input.y.toFloat()
        if (ty !in ebY..(ebY + ebH)) return
        if (tx in ebHomeX..(ebHomeX + ebW)) {
            SLog.i("Game", "match finished -> home menu")
            leave()
        } else if (tx in ebAgainX..(ebAgainX + ebW) && myV()?.ready != true) {
            SLog.i("Game", "voted PLAY AGAIN")
            net.send(Codec.ready(true))
        }
    }

    private fun drawEnd(sw: Float, sh: Float) {
        val s = snaps.lastOrNull() ?: return
        scoreStrs(s.scoreA, s.scoreB)
        batch.setColor(DIM_COLOR)
        batch.draw(rWhite, 0f, 0f, sw, sh)
        batch.setColor(1f, 1f, 1f, 1f)
        batch.draw(rPanel, ewX, ewY, ewW, ewH)
        val cx = ewX + ewW / 2f
        text("FULL TIME", cx, ewY + ewH * 0.91f, 2.6f)
        text(scoreAText, cx - ewW * 0.22f, ewY + ewH * 0.67f, 11f, TINTS[0])
        text(":", cx, ewY + ewH * 0.68f, 9f)
        text(scoreBText, cx + ewW * 0.22f, ewY + ewH * 0.67f, 11f, TINTS[3])
        text("BLUE TEAM", cx - ewW * 0.22f, ewY + ewH * 0.46f, 1.5f)
        text("RED TEAM", cx + ewW * 0.22f, ewY + ewH * 0.46f, 1.5f)
        when {
            s.scoreA > s.scoreB -> text("BLUE TEAM WINS!", cx, ewY + ewH * 0.37f, 2.4f, TINTS[1])
            s.scoreB > s.scoreA -> text("RED TEAM WINS!", cx, ewY + ewH * 0.37f, 2.4f, TINTS[4])
            else -> text("DRAW", cx, ewY + ewH * 0.37f, 2.4f)
        }
        val voted = myV()?.ready == true
        text(voteStr(humansReady(), humansTotal()), cx, ewY + ewH * 0.27f, 1.2f, Color(0.8f, 0.88f, 1f, 1f))
        batch.draw(if (voted) rBtnDark else rBtnGreen, ebAgainX, ebY, ebW, ebH)
        text(if (voted) "WAITING..." else "PLAY AGAIN", ebAgainX + ebW / 2f, ebY + ebH / 2f, 1.7f)
        batch.draw(rBtnDark, ebHomeX, ebY, ebW, ebH)
        text("HOME", ebHomeX + ebW / 2f, ebY + ebH / 2f, 1.7f)
    }

    private fun teamSelectVisible() = net.myId >= 0 && snaps.lastOrNull()?.state == State.LOBBY

    private val tsW get() = Gdx.graphics.width * 0.56f
    private val tsH get() = Gdx.graphics.height * 0.84f
    private val tsX get() = (Gdx.graphics.width - tsW) / 2f
    private val tsY get() = (Gdx.graphics.height - tsH) / 2f
    private fun boxX(i: Int) = tsX + tsW * (0.05f + 0.48f * i)
    private val boxY get() = tsY + tsH * 0.37f
    private val boxW get() = tsW * 0.42f
    private val boxH get() = tsH * 0.36f
    private val startX get() = tsX + tsW * 0.25f
    private val startY get() = tsY + tsH * 0.08f
    private val startW get() = tsW * 0.5f
    private val startH get() = tsH * 0.13f
    private val mtY get() = tsY + tsH * 0.235f              // o'yin vaqti qatori
    private val mtS get() = tsH * 0.085f
    private val mtMinusX get() = tsX + tsW * 0.22f
    private val mtPlusX get() = tsX + tsW * 0.78f - mtS

    private val ipLine = if (myIp != null) "YOUR IP: $myIp" else ""
    private var matchKey = -1; private var matchText = ""
    private fun matchStr(m: Int): String { if (m != matchKey) { matchKey = m; matchText = "MATCH TIME: $m MIN" }; return matchText }

    /** Xona boshlig'i: odamlar (bot emas) ichida eng kichik id - START va o'yin vaqtini faqat u boshqaradi */
    private fun leaderId(): Int { var best = Int.MAX_VALUE; for (k in 0 until vCount) { val id = vs[k].id; if (id < C.BOT_ID_BASE && id < best) best = id }; return best }
    private fun isLeader() = localId >= 0 && leaderId() == localId

    private fun changeMatch(d: Int) {
        val cur = (snaps.lastOrNull()?.timer ?: matchMinutes() * 60) / 60
        val m = (cur + d).coerceIn(C.MATCH_MIN, C.MATCH_MAX)
        prefs.putInteger("matchMinutes", m); prefs.flush()
        net.send(Codec.matchTime(m))
    }

    private var noticeText: String? = null
    private var noticeUntil = 0L
    private var seenNoticeAt = 0L

    private fun teamCount(t: Int): Int { var n = 0; for (k in 0 until vCount) if (vs[k].team == t) n++; return n }
    private fun myV(): V? { for (k in 0 until vCount) if (vs[k].id == localId) return vs[k]; return null }

    private fun showNotice(text: String) { noticeText = text; noticeUntil = System.currentTimeMillis() + 2500 }

    /** O'rin (slot) markazi: jamoa ichida 2 o'rin, chapda va o'ngda */
    private fun seatX(team: Int, slot: Int) = boxX(team) + boxW * (0.25f + 0.5f * slot)
    private val seatY get() = boxY + boxH * 0.30f

    /** Shu o'rinni egallagan o'yinchi (yo'q bo'lsa null) */
    private fun occupantAt(team: Int, slot: Int): V? {
        for (k in 0 until vCount) { val v = vs[k]; if (v.team == team && v.slot == slot) return v }
        return null
    }

    private fun handleTeamTouch() {
        val tx = Gdx.input.x.toFloat(); val ty = Gdx.graphics.height - Gdx.input.y.toFloat()
        for (team in 0..1) for (slot in 0 until C.TEAM_SIZE) {
            val sx = seatX(team, slot)
            if (tx in (sx - boxW * 0.2f)..(sx + boxW * 0.2f) && ty in boxY..(boxY + boxH * 0.78f)) {
                val me = myV()
                val occ = occupantAt(team, slot)
                if (occ != null && occ.id != localId) showNotice("Seat is taken")
                else if (me != null && (me.team != team || me.slot != slot)) net.send(Codec.team(team * C.TEAM_SIZE + slot))
                return
            }
        }
        if (!isLeader()) return
        if (ty in mtY..(mtY + mtS)) {
            if (tx in mtMinusX..(mtMinusX + mtS)) { changeMatch(-1); return }
            if (tx in mtPlusX..(mtPlusX + mtS)) { changeMatch(1); return }
        }
        if (tx in startX..(startX + startW) && ty in startY..(startY + startH)) {
            SLog.i("Game", "host pressed START")
            net.send(Codec.ready(true))
        }
    }

    private fun drawTeamSelect(sw: Float, sh: Float) {
        if (net.noticeAt != seenNoticeAt) {
            seenNoticeAt = net.noticeAt
            showNotice(if (net.notice == 1) "Seat is taken" else "Can't change seat during a match")
        }
        batch.setColor(DIM_COLOR); batch.draw(rWhite, 0f, 0f, sw, sh); batch.setColor(1f, 1f, 1f, 1f)
        batch.draw(rPanel, tsX, tsY, tsW, tsH)
        text("SELECT TEAMS", tsX + tsW / 2f, tsY + tsH * 0.90f, 2.4f)
        if (myIp != null) text(ipLine, tsX + tsW / 2f, tsY + tsH * 0.845f, 1.9f, Color(0.5f, 0.95f, 1f, 1f))
        val lobbyInfo = lobbyInfoStr()
        if (lobbyInfo.isNotEmpty()) text(lobbyInfo, tsX + tsW / 2f, tsY + tsH * (if (myIp != null) 0.785f else 0.845f), 1.1f, Color(0.7f, 0.85f, 1f, 1f))
        val me = myV()
        for (i in 0..1) {
            val bx = boxX(i); val by = boxY
            val mine = me != null && me.team == i
            if (mine) { batch.setColor(1f, 1f, 1f, 0.95f); batch.draw(rWhite, bx - 5f * u, by - 5f * u, boxW + 10f * u, boxH + 10f * u) }
            batch.setColor(0.04f, 0.1f, 0.24f, 1f); batch.draw(rWhite, bx, by, boxW, boxH)
            if (i == 0) batch.setColor(0.1f, 0.4f, 0.9f, 1f) else batch.setColor(0.85f, 0.15f, 0.2f, 1f)
            batch.draw(rWhite, bx, by + boxH * 0.78f, boxW, boxH * 0.22f)
            batch.setColor(1f, 1f, 1f, 1f)
            text(if (i == 0) "TEAM 1" else "TEAM 2", bx + boxW / 2f, by + boxH * 0.89f, 1.5f)

            val ic = boxH * 0.30f
            for (s2 in 0 until C.TEAM_SIZE) {                          // o'rinlar: band bo'lmaganlari bo'sh halqa
                val isMine = me != null && me.team == i && me.slot == s2
                batch.setColor(1f, 1f, 1f, if (isMine) 0.95f else 0.25f)
                batch.draw(rRing, seatX(i, s2) - ic / 2f, seatY, ic, ic)
            }
            batch.setColor(1f, 1f, 1f, 1f)
            for (k in 0 until vCount) {
                val v = vs[k]
                if (v.team != i || v.slot !in 0 until C.TEAM_SIZE) continue
                val x = seatX(i, v.slot) - ic / 2f; val y = seatY
                batch.setColor(1f, 1f, 1f, 1f)
                batch.draw(rBalls[colorIndex(v)], x, y, ic, ic)
                if (v.id == localId) {
                    batch.setColor(TINTS[colorIndex(v)])
                    batch.draw(rMarker, x + ic * 0.2f, y + ic * 1.05f, ic * 0.6f, ic * 0.5f)
                }
                batch.setColor(1f, 1f, 1f, 1f)
                text(if (v.id == localId) "YOU" else if (v.id == leaderId()) "HOST" else "", x + ic / 2f, y - ic * 0.28f, 1.0f)
            }
        }
        // o'yin vaqti: hamma ko'radi, faqat boshliq o'zgartiradi
        val mins = (snaps.lastOrNull()?.timer ?: 0) / 60
        text(matchStr(mins), tsX + tsW / 2f, mtY + mtS / 2f, 1.7f)
        val leader = isLeader()
        if (leader) {
            batch.draw(rBtnDark, mtMinusX, mtY, mtS, mtS); text("-", mtMinusX + mtS / 2f, mtY + mtS / 2f, 2.2f)
            batch.draw(rBtnDark, mtPlusX, mtY, mtS, mtS); text("+", mtPlusX + mtS / 2f, mtY + mtS / 2f, 2.2f)
        }
        batch.draw(if (leader) rBtnGreen else rBtnDark, startX, startY, startW, startH)
        text(if (leader) "START" else "WAITING FOR HOST...", startX + startW / 2f, startY + startH / 2f, if (leader) 1.9f else 1.4f)

        val now = System.currentTimeMillis()
        val showNotice = noticeText != null && now < noticeUntil
        val hint = when {
            showNotice -> noticeText!!
            leader -> "After START nobody can join. Empty slots are filled with bots."
            else -> "Tap a free player seat. The host starts the match"
        }
        text(hint, tsX + tsW / 2f, tsY + tsH * 0.04f, 1.05f,
            if (showNotice) Color(1f, 0.7f, 0.2f, 1f) else Color(0.8f, 0.88f, 1f, 1f))
    }

    // ---------------- ulanish ekrani ----------------
    private val cpW get() = Gdx.graphics.width * 0.62f
    private val cpH get() = Gdx.graphics.height * 0.68f
    private val cpX get() = (Gdx.graphics.width - cpW) / 2f
    private val cpY get() = (Gdx.graphics.height - cpH) / 2f
    private val cbW get() = cpW * 0.38f
    private val cbH get() = cpH * 0.14f
    private val cbY get() = cpY + cpH * 0.07f
    private val shW get() = cbW
    private val shH get() = cbH * 0.8f
    private val shX get() = cpX + (cpW - cbW) / 2f
    private val shY get() = cpY - shH * 1.35f
    private fun retryX() = cpX + cpW * 0.08f
    private fun backX(failed: Boolean) = if (failed) cpX + cpW * 0.54f else cpX + (cpW - cbW) / 2f

    private fun handleConnectTouch(failed: Boolean) {
        val tx = Gdx.input.x.toFloat(); val ty = Gdx.graphics.height - Gdx.input.y.toFloat()
        if (failed && tx in shX..(shX + shW) && ty in shY..(shY + shH)) { AppLog.share(); return }
        if (ty !in cbY..(cbY + cbH)) return
        if (tx in backX(failed)..(backX(failed) + cbW)) leave()
        else if (failed && tx in retryX()..(retryX() + cbW)) restartNet()
    }

    /** "Connecting..." va xato ekrani: sababi va nima qilish kerakligi ko'rsatiladi (endi jim qotib qolmaydi). */
    private fun drawConnect(failed: Boolean) {
        Gdx.gl.glClearColor(BG_COLOR.r, BG_COLOR.g, BG_COLOR.b, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        Gdx.gl.glEnable(GL20.GL_BLEND)
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
        val cx = cpX + cpW / 2f
        val small = Color(0.8f, 0.88f, 1f, 1f)
        batch.projectionMatrix = hudCam.combined
        batch.begin()
        batch.draw(rPanel, cpX, cpY, cpW, cpH)
        if (!failed) {
            text(if (everConnected) "RECONNECTING" else "CONNECTING", cx, cpY + cpH * 0.80f, 2.6f)
            text(DOTS[(time * 2.5f).toInt() and 3], cx, cpY + cpH * 0.66f, 3f)
            text(if (localMode) "Starting the game on this phone" else "Server: $host", cx, cpY + cpH * 0.48f, 1.4f, small)
            batch.draw(rBtnDark, backX(false), cbY, cbW, cbH)
            text("BACK", backX(false) + cbW / 2f, cbY + cbH / 2f, 1.6f)
        } else {
            val title: String; val l1: String; val l2: String; val l3: String
            when (net.error) {
                1 -> { title = "ROOM NOT FOUND"; l1 = "Check the room code"; l2 = "and the server address."; l3 = "" }
                2 -> { title = "ROOM IS FULL"; l1 = "Try another room"; l2 = "or use QUICK MATCH."; l3 = "" }
                3 -> { title = "VERSION MISMATCH"; l1 = "Update the app on every phone"; l2 = "(host and players must have the same version)."; l3 = "" }
                5 -> { title = "MATCH ALREADY STARTED"; l1 = "The host already pressed START."; l2 = "Ask them to create a new match."; l3 = "" }
                6 -> { title = "REMOVED FROM MATCH"; l1 = "You didn't choose PLAY AGAIN,"; l2 = "so the match restarted without you."; l3 = "" }
                4 -> { title = "INVALID ADDRESS"; l1 = "Use an IP like 192.168.1.5"; l2 = "or ip:port."; l3 = "" }
                else -> { title = "CAN'T REACH SERVER"; l1 = "Same Wi-Fi as the host? Host game still running?"
                    l2 = "Is the IP correct?  ($host)"; l3 = "Internet server: allow UDP port ${C.PORT} in its firewall." }
            }
            text(title, cx, cpY + cpH * 0.82f, 2.4f, Color(1f, 0.55f, 0.35f, 1f))
            text(l1, cx, cpY + cpH * 0.62f, 1.3f, small)
            text(l2, cx, cpY + cpH * 0.50f, 1.3f, small)
            if (l3.isNotEmpty()) text(l3, cx, cpY + cpH * 0.38f, 1.3f, small)
            batch.draw(rBtnGreen, retryX(), cbY, cbW, cbH)
            text("RETRY", retryX() + cbW / 2f, cbY + cbH / 2f, 1.6f)
            batch.draw(rBtnDark, backX(true), cbY, cbW, cbH)
            text("BACK", backX(true) + cbW / 2f, cbY + cbH / 2f, 1.6f)
            batch.draw(rBtnDark, shX, shY, shW, shH)
            text("SHARE LOG", shX + shW / 2f, shY + shH / 2f, 1.3f)
        }
        batch.end()
    }

    private fun drawBanner(msg: String) {
        val sw = Gdx.graphics.width.toFloat(); val sh = Gdx.graphics.height.toFloat()
        batch.projectionMatrix = hudCam.combined
        batch.begin()
        batch.setColor(0.75f, 0.12f, 0.12f, 0.92f)
        batch.draw(rWhite, 0f, sh * 0.45f, sw, sh * 0.1f)
        batch.setColor(1f, 1f, 1f, 1f)
        text(msg, sw / 2f, sh * 0.5f, 1.8f)
        batch.end()
    }

    private var lobbyKey = -1
    private var lobbyText = ""
    private fun lobbyInfoStr(): String {
        if (net.roomCode != lobbyKey) {
            lobbyKey = net.roomCode
            lobbyText = buildString {
                if (net.roomCode > 0) append("Room ").append(net.roomCode)
                if (info != null) { if (isNotEmpty()) append("    "); append(info) }
            }
        }
        return lobbyText
    }

    private fun drawError() {
        Gdx.gl.glClearColor(BG_COLOR.r, BG_COLOR.g, BG_COLOR.b, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        val sw = Gdx.graphics.width.toFloat(); val sh = Gdx.graphics.height.toFloat()
        batch.projectionMatrix = hudCam.combined
        batch.begin()
        val msg = if (net.error == 1) "Room not found" else "Room is full"
        text(msg, sw / 2f, sh / 2f + 40f * u, 2.6f)
        text("Tap to go back", sw / 2f, sh / 2f - 40f * u, 1.7f)
        batch.end()
    }

    // ---------------- render ----------------
    private fun draw() {
        hasFrame = interpolate()
        updateCamera(Gdx.graphics.deltaTime)
        Gdx.gl.glClearColor(BG_COLOR.r, BG_COLOR.g, BG_COLOR.b, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        Gdx.gl.glEnable(GL20.GL_BLEND)
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
        val w = C.FIELD_W; val h = C.FIELD_H; val d = C.GOAL_DEPTH
        val g1 = (h - C.GOAL_W) / 2f

        field.ensureCache(shapes)              // tribunalar keshi (viewport.apply dan OLDIN)
        viewport.apply()
        batch.projectionMatrix = cam.combined

        field.drawStatic(batch, shapes, cam)

        // 3) darvozalar, soyalar, o'yinchilar, to'p
        batch.begin()
        batch.draw(rGoalBlue, -d, g1, d, C.GOAL_W)
        batch.draw(rGoalRed, w, g1, d, C.GOAL_W)
        if (hasFrame) {
            batch.setColor(1f, 1f, 1f, 0.45f)
            for (k in 0 until vCount) spr(rShadow, vs[k].x + 0.15f, vs[k].y - 0.15f, C.PLAYER_R * 1.1f)
            spr(rShadow, ballX + 0.1f, ballY - 0.1f, C.BALL_R * 1.2f)
            batch.setColor(1f, 1f, 1f, 1f)

            for (k in 0 until vCount) {
                val v = vs[k]
                var x = v.x; var y = v.y
                if (v.id == localId) {
                    val body = local.players[localId]
                    if (body != null) { x = body.position.x + errX; y = body.position.y + errY }
                }
                val ci = colorIndex(v)
                spr(rBalls[ci], x, y, C.PLAYER_R)
                // o'yinchi tepasidagi uchburchak (o'zimiznikiga sakraydi va kattaroq)
                val me = v.id == localId
                val mh = if (me) 0.62f else 0.5f
                val mw = mh * 1.14f
                val my = y + C.PLAYER_R + mh * 0.75f + (if (me) MathUtils.sin(time * 7f) * 0.1f else 0f)
                batch.setColor(TINTS[ci])
                batch.draw(rMarker, x - mw / 2f, my - mh / 2f, mw, mh)
                batch.setColor(1f, 1f, 1f, 1f)
            }

            if (hasLastBall) {
                val dx = ballX - lastBX; val dy = ballY - lastBY
                val sign = if (kotlin.math.abs(dx) >= kotlin.math.abs(dy)) (if (dx >= 0) 1f else -1f) else (if (dy >= 0) 1f else -1f)
                ballRot -= sign * hypot(dx, dy) / C.BALL_R * MathUtils.radiansToDegrees
            }
            hasLastBall = true; lastBX = ballX; lastBY = ballY
            spr(rBall, ballX, ballY, C.BALL_R, ballRot)
        }
        batch.end()

        drawAim()
        drawCharge()
        drawHud()
    }

    /**
     * Zarba/pas nishoni: o'yinchidan yo'nalish chizig'i. Quvvat ortgan sari chiziq uzunroq.
     * PASS da eng mos sherik halqa bilan ajratiladi (server ham shu sherikka uzatadi).
     */
    private fun drawAim() {
        val act = pad.chargingAction
        if (act == Act.NONE || !playingNow() || localId < 0) return
        val me = myV() ?: return
        val body = local.players[localId] ?: return
        val px = body.position.x + errX; val py = body.position.y + errY
        val lvl = pad.chargeLevel
        val strength = if (act == Act.SHOOT) KickPower.SHOOT[lvl] else KickPower.PASS[lvl]
        val aimLen = hypot(pad.mx, pad.my)
        val aiming = aimLen > 0.3f
        var dx: Float; var dy: Float
        if (aiming) { dx = pad.mx / aimLen; dy = pad.my / aimLen }
        else {
            dx = ballX - px; dy = ballY - py
            val l = hypot(dx, dy).coerceAtLeast(0.001f); dx /= l; dy /= l
        }
        var ex = px + dx * (1.5f + 2.5f * strength)
        var ey = py + dy * (1.5f + 2.5f * strength)
        var target = -1
        if (act == Act.PASS) {
            var best = -1e9f
            for (k in 0 until vCount) {
                val v = vs[k]
                if (v.team != me.team || v.id == localId) continue
                val tx = v.x - ballX; val ty = v.y - ballY
                val dist = hypot(tx, ty).coerceAtLeast(0.1f)
                val score = if (aiming) (tx * dx + ty * dy) / dist * 10f - dist * 0.05f else -dist
                if (score > best) { best = score; target = k }
            }
            if (target >= 0) { ex = vs[target].x; ey = vs[target].y }
        }
        val col = if (act == Act.SHOOT) Color(1f, 0.35f, 0.25f, 0.95f) else Color(0.35f, 0.8f, 1f, 0.95f)
        shapes.projectionMatrix = cam.combined
        shapes.begin(ShapeRenderer.ShapeType.Line)
        shapes.color = col
        shapes.line(px, py, ex, ey)
        if (target >= 0) shapes.circle(ex, ey, C.PLAYER_R + 0.35f, 24)
        shapes.end()
        shapes.begin(ShapeRenderer.ShapeType.Filled)
        shapes.color = col
        shapes.circle(ex, ey, 0.2f, 12)
        shapes.end()
    }

    private fun playingNow() = snaps.lastOrNull()?.state == State.PLAYING
    private fun tipsVisible() = !Settings.tipsSeen && playingNow()
    private var lastSfxState = -1
    /** Oxirgi gollar (eng yangisi birinchi) */
    private val goalFeed = ArrayDeque<GoalMsg>()

    /** Quvvat paneli: o'yinchi ustida. To'lsa - eng kuchli zarba/pas. */
    private fun drawCharge() {
        val act = pad.chargingAction
        if (act == Act.NONE || !playingNow() || localId < 0) return
        val body = local.players[localId] ?: return
        val px = body.position.x + errX; val py = body.position.y + errY
        val frac = (pad.chargeMs.toFloat() / TouchPad.FULL_MS).coerceIn(0f, 1f)
        val bw = 2.2f; val bh = 0.26f
        val x0 = px - bw / 2f; val y0 = py + C.PLAYER_R + 0.8f
        shapes.projectionMatrix = cam.combined
        shapes.begin(ShapeRenderer.ShapeType.Filled)
        shapes.color = Color(0f, 0f, 0f, 0.55f)
        shapes.rect(x0 - 0.05f, y0 - 0.05f, bw + 0.1f, bh + 0.1f)
        shapes.color = if (act == Act.SHOOT) Color(1f, 0.35f, 0.25f, 1f) else Color(0.35f, 0.8f, 1f, 1f)
        shapes.rect(x0, y0, bw * frac, bh)
        shapes.color = Color(1f, 1f, 1f, 0.7f)
        for (k in 1..3) shapes.rect(x0 + bw * k / 4f - 0.015f, y0, 0.03f, bh)   // quvvat darajalari belgisi
        shapes.end()
    }

    /** Joystick va PASS/SHOOT tugmalari (shakl bilan) */
    private fun drawControls() {
        shapes.projectionMatrix = hudCam.combined
        val jr = pad.radius
        val active = pad.stickActive
        val ox = if (active) pad.originX else pad.idleX
        val oy = if (active) pad.originY else pad.idleY
        val kx = if (active) pad.knobX else pad.idleX
        val ky = if (active) pad.knobY else pad.idleY
        val a = if (active) 1f else 0.6f
        shapes.begin(ShapeRenderer.ShapeType.Filled)
        shapes.color = Color(0f, 0.05f, 0.15f, 0.4f * a)
        shapes.circle(ox, oy, jr, 56)
        shapes.color = Color(0.5f, 0.85f, 1f, 0.12f * a)
        shapes.circle(ox, oy, jr * 0.66f, 40)
        shapes.color = Color(1f, 1f, 1f, 0.16f * a)
        shapes.circle(ox, oy, jr * 0.14f, 16)                // o'lik zona markazi
        shapes.color = Color(0.85f, 0.95f, 1f, 0.92f * a)
        shapes.circle(kx, ky, jr * 0.46f, 40)
        shapes.color = Color(1f, 1f, 1f, 0.6f * a)
        shapes.circle(kx - jr * 0.1f, ky + jr * 0.13f, jr * 0.16f, 16)   // yorug'lik
        shapes.end()
        shapes.begin(ShapeRenderer.ShapeType.Line)
        shapes.color = Color(0.7f, 0.92f, 1f, 0.7f * a)
        shapes.circle(ox, oy, jr, 56); shapes.circle(ox, oy, jr * 1.01f, 56)
        shapes.end()

        val br = pad.btnR
        drawButton(pad.passX, pad.passY, br, pad.passHeld, Color(0.25f, 0.85f, 1f, 1f), Color(0.05f, 0.3f, 0.55f, 1f), true)
        drawButton(pad.shootX, pad.shootY, br, pad.shootHeld, Color(1f, 0.5f, 0.3f, 1f), Color(0.5f, 0.1f, 0.08f, 1f), false)
    }

    private fun drawButton(x: Float, y: Float, r: Float, pressed: Boolean, light: Color, dark: Color, pass: Boolean) {
        val rr = r * (if (pressed) 0.93f else 1f)
        shapes.begin(ShapeRenderer.ShapeType.Filled)
        shapes.color = Color(light.r, light.g, light.b, if (pressed) 0.35f else 0.2f)
        shapes.circle(x, y, r * 1.15f, 40)                    // tashqi nur
        shapes.color = dark
        shapes.circle(x, y, rr, 48)                           // korpus
        shapes.color = light
        shapes.circle(x, y + rr * 0.06f, rr * 0.88f, 48)      // yuza
        shapes.color = Color(1f, 1f, 1f, if (pressed) 0.12f else 0.22f)
        shapes.circle(x - rr * 0.1f, y + rr * 0.32f, rr * 0.45f, 24)   // yorug'lik
        shapes.end()
        shapes.begin(ShapeRenderer.ShapeType.Line)
        shapes.color = Color(1f, 1f, 1f, 0.75f)
        shapes.circle(x, y, rr, 48)
        if (pass) {                                           // o'q: uzatma
            val ix = x; val iy = y + rr * 0.2f
            shapes.line(ix - rr * 0.4f, iy, ix + rr * 0.42f, iy)
            shapes.line(ix + rr * 0.42f, iy, ix + rr * 0.2f, iy + rr * 0.22f)
            shapes.line(ix + rr * 0.42f, iy, ix + rr * 0.2f, iy - rr * 0.22f)
        } else {                                              // to'p: zarba
            shapes.circle(x, y + rr * 0.2f, rr * 0.3f, 24)
            shapes.line(x - rr * 0.1f, y + rr * 0.2f, x + rr * 0.42f, y + rr * 0.48f)
        }
        shapes.end()
    }

    private fun drawHud() {
        val sw = Gdx.graphics.width.toFloat(); val sh = Gdx.graphics.height.toFloat()
        val s = snaps.lastOrNull()
        batch.projectionMatrix = hudCam.combined
        batch.begin()

        // ---- hisob paneli (yuqori o'rtada): ko'k | taymer | qizil ----
        val cx = sw / 2f
        val bh = sh * 0.095f; val bw = sw * 0.09f
        val top = sh - sh * 0.02f - bh
        scoreStrs(s?.scoreA ?: 0, s?.scoreB ?: 0)
        batch.draw(rScoreBlue, cx - sw * 0.04f - bw, top, bw, bh)
        batch.draw(rScoreRed, cx + sw * 0.04f, top, bw, bh)
        val cw = sw * 0.082f; val ch = bh * 1.12f
        batch.draw(rPanel, cx - cw / 2f, top - bh * 0.12f, cw, ch)
        text(scoreAText, cx - sw * 0.04f - bw / 2f + bw * 0.06f, top + bh / 2f, 3.4f)
        text(scoreBText, cx + sw * 0.04f + bw / 2f - bw * 0.06f, top + bh / 2f, 3.4f)
        text(if (s != null && s.overtime) "OVERTIME" else timeStr(if (s != null && s.state == State.PLAYING) s.timer else -1), cx, top + bh * 0.68f, 1.9f)
        var c0 = 0; var c1 = 0
        for (k in 0 until vCount) if (vs[k].team == 0) c0++ else c1++
        text(modeStr(c0, c1), cx, top + bh * 0.2f, 1.3f, Color(0.8f, 0.88f, 1f, 1f))

        // ---- jamoa panellari (yuqori burchaklar) ----
        val pw = sw * 0.14f; val ph = sh * 0.15f
        val py = sh - sh * 0.02f - ph
        val lx = sw * 0.02f
        val rx = sw - sw * 0.02f - pw - sw * 0.075f          // o'ng panel (pauza tugmasi uchun joy qoldiriladi)
        batch.draw(rPanel, lx, py, pw, ph)
        batch.draw(rPanel, rx, py, pw, ph)
        val ic = ph * 0.34f
        for (k in 0 until vCount) {
            val v = vs[k]
            var slot = 0
            for (j in 0 until vCount) if (vs[j].team == v.team && vs[j].id < v.id) slot++
            val px0 = (if (v.team == 0) lx else rx) + pw * 0.14f + slot * ic * 1.25f
            batch.draw(rBalls[colorIndex(v)], px0, py + ph * 0.5f, ic, ic)
        }
        text("BLUE TEAM", lx + pw / 2f, py + ph * 0.25f, 1.25f)
        text("RED TEAM", rx + pw / 2f, py + ph * 0.25f, 1.25f)

        // ---- pauza tugmasi ----
        val ps = pad.pauseSize
        batch.draw(rPanel, pad.pauseX, pad.pauseY, ps, ps)
        batch.draw(rWhite, pad.pauseX + ps * 0.3f, pad.pauseY + ps * 0.25f, ps * 0.14f, ps * 0.5f)
        batch.draw(rWhite, pad.pauseX + ps * 0.56f, pad.pauseY + ps * 0.25f, ps * 0.14f, ps * 0.5f)

        // ---- joystick + PASS/SHOOT (shakl bilan chizish) ----
        batch.end()
        drawControls()
        batch.begin()
        batch.setColor(1f, 1f, 1f, 1f)
        val br = pad.btnR
        text("PASS", pad.passX, pad.passY - br * 0.5f, 1.05f, Color(0.9f, 0.97f, 1f, 0.95f))
        text("SHOOT", pad.shootX, pad.shootY - br * 0.5f, 1.05f, Color(1f, 0.95f, 0.9f, 0.95f))

        // gollar tablosi: chap yuqorida, gol urgan o'yinchi nomi bilan
        for ((i, g) in goalFeed.withIndex()) {
            val who = if (g.name.isEmpty()) "Goal" else g.name
            val line = "$who ${g.minute}'" + if (g.own) " (own goal)" else ""
            val col = if (g.team == 0) Color(0.55f, 0.8f, 1f, 1f) else Color(1f, 0.6f, 0.6f, 1f)
            text(line, sw * 0.17f, sh * (0.92f - 0.055f * i), 1.35f, col)
        }

        if (tipsVisible()) {
            batch.setColor(1f, 1f, 1f, 0.95f)
            batch.draw(rPanel, sw * 0.2f, sh * 0.28f, sw * 0.6f, sh * 0.44f)
            batch.setColor(1f, 1f, 1f, 1f)
            text("HOW TO PLAY", sw / 2f, sh * 0.66f, 2.2f)
            val tips = arrayOf("Joystick: move", "Hold PASS or SHOOT, release to kick",
                "Hold longer for a stronger kick", "Get close to the ball to take it", "Tap to start")
            for ((i, t) in tips.withIndex()) text(t, sw / 2f, sh * (0.57f - 0.07f * i), 1.5f)
        }

        // ---- holat matnlari ----
        val small = Color(1f, 1f, 1f, 0.85f)
        var ty = py - sh * 0.03f
        if (showPing) {
            textL(if (net.myId < 0) CONNECTING else pingStr(net.rttMs), sw * 0.02f, ty, 1.2f, small); ty -= sh * 0.04f
        }
        if (net.roomCode > 0) { textL(roomStr(net.roomCode), sw * 0.02f, ty, 1.2f, small); ty -= sh * 0.04f }
        if (info != null) textL(info, sw * 0.02f, ty, 1.1f, small)

        if (s != null) {
            val msg = messageFor(s)
            if (msg != null && s.state != State.LOBBY) text(msg, cx, sh * 0.62f, if (s.state == State.COUNTDOWN) 7f else 4f)
        }

        if (teamSelectVisible() && !menuOpen) drawTeamSelect(sw, sh)
        if (endVisible() && !menuOpen) drawEnd(sw, sh)

        // ---- pauza oynasi ----
        if (menuOpen) {
            batch.setColor(DIM_COLOR)
            batch.draw(rWhite, 0f, 0f, sw, sh)
            batch.setColor(1f, 1f, 1f, 1f)
            batch.draw(rPanel, menuX, menuY, menuW, menuH)
            text("PAUSE", menuX + menuW / 2f, menuY + menuH * 0.90f, 2.6f)
            if (myIp != null) text(ipLine, menuX + menuW / 2f, menuY + menuH * 0.82f, 1.3f, Color(0.5f, 0.95f, 1f, 1f))
            val bx = menuX + menuW * 0.1f; val bw2 = menuW * 0.8f
            val items = menuItems()
            for (i in items.indices) {
                batch.draw(if (i == 0) rBtnGreen else rBtnDark, bx, menuBtnY(i), bw2, menuBtnH)
                text(items[i], bx + bw2 / 2f, menuBtnY(i) + menuBtnH / 2f, 1.6f)
            }
        }
        batch.end()
    }

    override fun dispose() {
        net.close(true); if (localMode) LocalServer.stop()
        field.dispose(); shapes.dispose(); batch.dispose(); font.dispose(); atlas.dispose()
    }
}
