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

// ---- dizayn ranglari (2v2 Football maketi) ----
private val BG_COLOR = Color(0.03f, 0.06f, 0.14f, 1f)
private val FRAME_COLOR = Color(0.06f, 0.11f, 0.25f, 1f)
private val LINE_COLOR = Color(0.85f, 1f, 0.92f, 0.85f)
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
    private val info: String? = null          // host uchun: do'stlarga ko'rsatma
) : ScreenAdapter() {
    private class V { var id = 0; var team = 0; var ready = false; var x = 0f; var y = 0f; var vx = 0f; var vy = 0f }

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
    override fun pause() { wasPaused = true; net.close(leave = false) }

    override fun resume() {
        if (!wasPaused) return
        wasPaused = false
        if (net.roomCode > 0) joinCode = net.roomCode   // o'sha xonaga qaytamiz
        net = NetClient(host)          // reconnect, keyin to'liq snapshot keladi
        if (localId >= 0) local.removePlayer(localId)
        localId = -1; snaps.clear(); pending.clear(); errX = 0f; errY = 0f
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
        if (net.error != 0) { drawError(); if (Gdx.input.justTouched()) leave(); return }

        if (Gdx.input.isKeyJustPressed(GdxInput.Keys.BACK)) menuOpen = !menuOpen
        else if (menuOpen && Gdx.input.justTouched()) handleMenuTouch()
        else if (teamSelectVisible() && Gdx.input.justTouched()) handleTeamTouch()
        if (pad.consumePause()) menuOpen = true
        pad.enabled = !menuOpen && !teamSelectVisible()

        pollNet()

        if (net.myId < 0) {
            joinTimer -= dt
            if (joinTimer <= 0f) { net.send(Codec.join(joinCode, token)); joinTimer = 0.5f }
        } else {
            if (localId != net.myId) { localId = net.myId; local.addPlayer(localId, net.myTeam) }
            acc += dt
            while (acc >= C.DT) { stepLocal(); acc -= C.DT }
        }

        pingTimer -= dt
        if (pingTimer <= 0f) { net.send(Codec.ping(System.currentTimeMillis())); pingTimer = 1f }

        val k = 0.9f.pow(dt * 60f)
        errX *= k; errY *= k

        draw()
    }

    /** Client-side prediction: har fixed tick'da input'ni darhol qo'llab, serverga yuboramiz. */
    private fun stepLocal() {
        seq++
        val on = isPlaying()
        val act = pad.consumeAction()
        val mx = if (on) pad.mx else 0f
        val my = if (on) pad.my else 0f
        val action = if (on) act else Act.NONE
        pending.push(seq, mx, my, action)
        if (on) { local.applyInput(localId, mx, my, action); local.step() }
        net.sendInput(seq, mx, my, action)
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
            local.applyInput(localId, pending.mx(i), pending.my(i), pending.action(i)); local.step()
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
            v.id = pb.id; v.team = pb.team; v.vx = pb.vx; v.vy = pb.vy; v.ready = pb.ready
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
    private val grass = Texture(Gdx.files.internal("grass.png")).apply {
        setWrap(Texture.TextureWrap.Repeat, Texture.TextureWrap.Repeat)
        setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
    }
    private val grassRegion = TextureRegion(grass)
    private val rBalls = Array(BALL_NAMES.size) { atlas.findRegion(BALL_NAMES[it]) }
    private val rMarker = atlas.findRegion("marker")
    private val rRing = atlas.findRegion("ring")
    private val rBall = atlas.findRegion("ball")
    private val rShadow = atlas.findRegion("shadow")
    private val rWhite = atlas.findRegion("white")
    private val rJoyBase = atlas.findRegion("joy_base")
    private val rJoyKnob = atlas.findRegion("joy_knob")
    private val rShoot = atlas.findRegion("btn_shoot")
    private val rPass = atlas.findRegion("btn_pass")
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

    private fun drawGrass(x: Float, y: Float, w: Float, h: Float) {
        val tile = 6f
        grassRegion.setRegion(x / tile, y / tile, (x + w) / tile, (y + h) / tile)
        batch.draw(grassRegion, x, y, w, h)
    }

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
                State.END -> if (s.scoreA == s.scoreB) "Draw ${s.scoreA}:${s.scoreB}"
                             else (if (s.scoreA > s.scoreB) "BLUE" else "RED") + " WINS ${s.scoreA}:${s.scoreB}"
                else -> null
            }
        }
        return msgText
    }

    /** o'yinchining jamoasidagi tartibi (id bo'yicha) -> ikonka/rang */
    private fun colorIndex(v: V): Int {
        var slot = 0
        for (j in 0 until vCount) if (vs[j].team == v.team && vs[j].id < v.id) slot++
        return (if (v.team == 0) 0 else 3) + min(slot, 2)
    }

    // ---------------- pauza menyusi ----------------
    private val menuW get() = Gdx.graphics.width * 0.34f
    private val menuH get() = Gdx.graphics.height * 0.66f
    private val menuX get() = (Gdx.graphics.width - menuW) / 2f
    private val menuY get() = (Gdx.graphics.height - menuH) / 2f
    private fun menuBtnY(i: Int) = menuY + menuH * (0.58f - 0.22f * i)
    private val menuBtnH get() = menuH * 0.17f

    private fun handleMenuTouch() {
        val tx = Gdx.input.x.toFloat(); val ty = Gdx.graphics.height - Gdx.input.y.toFloat()
        val bx = menuX + menuW * 0.1f; val bw = menuW * 0.8f
        for (i in 0..2) {
            val by = menuBtnY(i)
            if (tx in bx..(bx + bw) && ty in by..(by + menuBtnH)) {
                when (i) {
                    0 -> menuOpen = false
                    1 -> { showPing = !showPing; prefs.putBoolean("showPing", showPing); prefs.flush() }
                    2 -> leave()
                }
                return
            }
        }
    }

    // ---------------- Select Teams (lobby) ----------------
    private fun teamSelectVisible() = net.myId >= 0 && snaps.lastOrNull()?.state == State.LOBBY

    private val tsW get() = Gdx.graphics.width * 0.52f
    private val tsH get() = Gdx.graphics.height * 0.68f
    private val tsX get() = (Gdx.graphics.width - tsW) / 2f
    private val tsY get() = (Gdx.graphics.height - tsH) / 2f
    private fun boxX(i: Int) = tsX + tsW * (0.05f + 0.48f * i)
    private val boxY get() = tsY + tsH * 0.30f
    private val boxW get() = tsW * 0.42f
    private val boxH get() = tsH * 0.46f
    private val startX get() = tsX + tsW * 0.25f
    private val startY get() = tsY + tsH * 0.06f
    private val startW get() = tsW * 0.5f
    private val startH get() = tsH * 0.16f

    private var noticeText: String? = null
    private var noticeUntil = 0L
    private var seenNoticeAt = 0L

    private fun teamCount(t: Int): Int { var n = 0; for (k in 0 until vCount) if (vs[k].team == t) n++; return n }
    private fun myV(): V? { for (k in 0 until vCount) if (vs[k].id == localId) return vs[k]; return null }

    private fun showNotice(text: String) { noticeText = text; noticeUntil = System.currentTimeMillis() + 2500 }

    private fun handleTeamTouch() {
        val tx = Gdx.input.x.toFloat(); val ty = Gdx.graphics.height - Gdx.input.y.toFloat()
        for (i in 0..1) if (tx in boxX(i)..(boxX(i) + boxW) && ty in boxY..(boxY + boxH)) {
            val me = myV()
            if (me != null && me.team != i) {
                // to'la jamoani tanlab bo'lmaydi (server ham tekshiradi)
                if (teamCount(i) >= C.TEAM_SIZE) showNotice("Team is full") else net.send(Codec.team(i))
            }
            return
        }
        if (tx in startX..(startX + startW) && ty in startY..(startY + startH)) {
            net.send(Codec.ready(!(myV()?.ready ?: false)))
        }
    }

    private fun drawTeamSelect(sw: Float, sh: Float) {
        if (net.noticeAt != seenNoticeAt) {
            seenNoticeAt = net.noticeAt
            showNotice(if (net.notice == 1) "Team is full" else "Can't change team during a match")
        }
        batch.setColor(DIM_COLOR); batch.draw(rWhite, 0f, 0f, sw, sh); batch.setColor(1f, 1f, 1f, 1f)
        batch.draw(rPanel, tsX, tsY, tsW, tsH)
        text("SELECT TEAMS", tsX + tsW / 2f, tsY + tsH * 0.90f, 2.4f)
        val me = myV()
        for (i in 0..1) {
            val bx = boxX(i); val by = boxY
            val mine = me != null && me.team == i
            val full = teamCount(i) >= C.TEAM_SIZE
            if (mine) { batch.setColor(1f, 1f, 1f, 0.95f); batch.draw(rWhite, bx - 5f * u, by - 5f * u, boxW + 10f * u, boxH + 10f * u) }
            batch.setColor(0.04f, 0.1f, 0.24f, 1f); batch.draw(rWhite, bx, by, boxW, boxH)
            if (i == 0) batch.setColor(0.1f, 0.4f, 0.9f, 1f) else batch.setColor(0.85f, 0.15f, 0.2f, 1f)
            batch.draw(rWhite, bx, by + boxH * 0.78f, boxW, boxH * 0.22f)
            batch.setColor(1f, 1f, 1f, 1f)
            text(if (i == 0) "TEAM 1" else "TEAM 2", bx + boxW / 2f, by + boxH * 0.89f, 1.5f)

            val ic = boxH * 0.30f
            for (s2 in 0 until C.TEAM_SIZE) {                          // bo'sh joylar
                batch.setColor(1f, 1f, 1f, 0.25f)
                batch.draw(rRing, bx + boxW * (0.25f + 0.5f * s2) - ic / 2f, by + boxH * 0.30f, ic, ic)
            }
            batch.setColor(1f, 1f, 1f, 1f)
            for (k in 0 until vCount) {
                val v = vs[k]
                if (v.team != i) continue
                var sl = 0
                for (j in 0 until vCount) if (vs[j].team == i && vs[j].id < v.id) sl++
                if (sl >= C.TEAM_SIZE) continue
                val x = bx + boxW * (0.25f + 0.5f * sl) - ic / 2f; val y = by + boxH * 0.30f
                batch.setColor(1f, 1f, 1f, if (v.ready) 1f else 0.55f)
                batch.draw(rBalls[colorIndex(v)], x, y, ic, ic)
                if (v.id == localId) {
                    batch.setColor(TINTS[colorIndex(v)])
                    batch.draw(rMarker, x + ic * 0.2f, y + ic * 1.05f, ic * 0.6f, ic * 0.5f)
                }
                batch.setColor(1f, 1f, 1f, 1f)
                text(if (v.id == localId) "YOU" else if (v.ready) "READY" else "...", x + ic / 2f, y - ic * 0.28f, 1.0f)
            }
            if (full && !mine) {
                batch.setColor(0f, 0f, 0f, 0.55f); batch.draw(rWhite, bx, by, boxW, boxH); batch.setColor(1f, 1f, 1f, 1f)
                text("FULL", bx + boxW / 2f, by + boxH * 0.45f, 2f)
            }
        }
        val ready = me?.ready ?: false
        batch.draw(if (ready) rBtnDark else rBtnGreen, startX, startY, startW, startH)
        text(if (ready) "WAITING..." else "START", startX + startW / 2f, startY + startH / 2f, 1.8f)

        val now = System.currentTimeMillis()
        val hint = when {
            noticeText != null && now < noticeUntil -> noticeText!!
            teamCount(0) == 0 || teamCount(1) == 0 -> "Waiting for a player in each team"
            else -> "Press START when everyone is ready"
        }
        text(hint, tsX + tsW / 2f, tsY + tsH * 0.255f, 1.1f,
            if (noticeText != null && now < noticeUntil) Color(1f, 0.7f, 0.2f, 1f) else Color(0.8f, 0.88f, 1f, 1f))
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

        viewport.apply()
        batch.projectionMatrix = cam.combined

        // 1) stadion ramkasi va o't
        batch.begin()
        batch.setColor(FRAME_COLOR)
        batch.draw(rWhite, -d - 1.2f, -1.4f, w + 2f * d + 2.4f, h + 2.8f)
        batch.setColor(1f, 1f, 1f, 1f)
        drawGrass(0f, 0f, w, h)
        batch.end()

        // 2) maydon chiziqlari
        shapes.projectionMatrix = cam.combined
        shapes.begin(ShapeRenderer.ShapeType.Line)
        shapes.color = LINE_COLOR
        shapes.rect(0f, 0f, w, h)
        shapes.line(w / 2f, 0f, w / 2f, h)
        shapes.circle(w / 2f, h / 2f, 3f, 48)
        val pb = 4.5f
        shapes.rect(0f, g1 - 1.5f, pb, C.GOAL_W + 3f)                 // jarima maydoni
        shapes.rect(w - pb, g1 - 1.5f, pb, C.GOAL_W + 3f)
        val sb = 1.8f
        shapes.rect(0f, g1 - 0.4f, sb, C.GOAL_W + 0.8f)               // darvoza maydoni
        shapes.rect(w - sb, g1 - 0.4f, sb, C.GOAL_W + 0.8f)
        shapes.arc(pb, h / 2f, 2.2f, -55f, 110f, 24)                  // yarim doiralar
        shapes.arc(w - pb, h / 2f, 2.2f, 125f, 110f, 24)
        shapes.end()
        shapes.begin(ShapeRenderer.ShapeType.Filled)
        shapes.color = LINE_COLOR
        shapes.circle(w / 2f, h / 2f, 0.14f, 10)
        shapes.end()

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

        drawHud()
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
        text(timeStr(if (s != null && s.state == State.PLAYING) s.timer else -1), cx, top + bh * 0.68f, 1.9f)
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

        // ---- joystick ----
        val jr = pad.radius
        if (pad.stickActive) {
            batch.setColor(1f, 1f, 1f, 0.95f)
            batch.draw(rJoyBase, pad.originX - jr, pad.originY - jr, jr * 2f, jr * 2f)
            batch.draw(rJoyKnob, pad.knobX - jr * 0.48f, pad.knobY - jr * 0.48f, jr * 0.96f, jr * 0.96f)
        } else {
            batch.setColor(1f, 1f, 1f, 0.7f)
            batch.draw(rJoyBase, pad.idleX - jr, pad.idleY - jr, jr * 2f, jr * 2f)
            batch.draw(rJoyKnob, pad.idleX - jr * 0.48f, pad.idleY - jr * 0.48f, jr * 0.96f, jr * 0.96f)
        }

        // ---- PASS / SHOOT ----
        val br = pad.btnR
        val ps2 = if (pad.passHeld) 0.9f else 1f
        val ss2 = if (pad.shootHeld) 0.9f else 1f
        batch.setColor(1f, 1f, 1f, 0.95f)
        batch.draw(rPass, pad.passX - br * ps2, pad.passY - br * ps2, br * 2f * ps2, br * 2f * ps2)
        batch.draw(rShoot, pad.shootX - br * ss2, pad.shootY - br * ss2, br * 2f * ss2, br * 2f * ss2)
        batch.setColor(1f, 1f, 1f, 1f)
        text("PASS", pad.passX, pad.passY - br * 0.55f, 1.15f)
        text("SHOOT", pad.shootX, pad.shootY - br * 0.55f, 1.15f)

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

        // ---- pauza oynasi ----
        if (menuOpen) {
            batch.setColor(DIM_COLOR)
            batch.draw(rWhite, 0f, 0f, sw, sh)
            batch.setColor(1f, 1f, 1f, 1f)
            batch.draw(rPanel, menuX, menuY, menuW, menuH)
            text("PAUSE", menuX + menuW / 2f, menuY + menuH * 0.88f, 2.6f)
            val bx = menuX + menuW * 0.1f; val bw2 = menuW * 0.8f
            batch.draw(rBtnGreen, bx, menuBtnY(0), bw2, menuBtnH)
            batch.draw(rBtnDark, bx, menuBtnY(1), bw2, menuBtnH)
            batch.draw(rBtnDark, bx, menuBtnY(2), bw2, menuBtnH)
            text("RESUME", bx + bw2 / 2f, menuBtnY(0) + menuBtnH / 2f, 1.7f)
            text(if (showPing) "PING: ON" else "PING: OFF", bx + bw2 / 2f, menuBtnY(1) + menuBtnH / 2f, 1.7f)
            text("EXIT", bx + bw2 / 2f, menuBtnY(2) + menuBtnH / 2f, 1.7f)
        }
        batch.end()
    }

    override fun dispose() {
        net.close(true); if (localMode) LocalServer.stop()
        shapes.dispose(); batch.dispose(); font.dispose(); atlas.dispose(); grass.dispose()
    }
}
