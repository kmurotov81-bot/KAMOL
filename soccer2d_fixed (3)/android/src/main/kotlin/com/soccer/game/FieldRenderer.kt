package com.soccer.game

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureAtlas
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.graphics.glutils.FrameBuffer
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.math.MathUtils
import soccer.shared.C

private val FRAME_COLOR = Color(0.06f, 0.11f, 0.25f, 1f)
private val LINE_COLOR = Color(0.85f, 1f, 0.92f, 0.85f)

/** Tribunalar fon koordinatalarida: x -20..w+20, y -10..h+10 */
private const val SX0 = -20f
private const val SY0 = -10f
private const val STANDS_PPU = 16f      // tribunalar teksturasi: birlik boshiga piksel (katta emas - xotira uchun)

/**
 * Maydon foni. Tribunalar (yuzlab kvadrat) bir marta FrameBuffer'ga chiziladi va keyin bitta tekstura sifatida chiziladi.
 * Maydon chiziqlari, burchaklar va o't har kadr chiziladi: ular kam (~50 segment) va yuqori aniqlik kerak.
 */
class FieldRenderer(atlas: TextureAtlas) {
    private val SW = C.FIELD_W + 40f
    private val SH = C.FIELD_H + 20f
    private val rWhite = atlas.findRegion("white")
    private val grass = Texture(Gdx.files.internal("grass.png")).apply {
        setWrap(Texture.TextureWrap.Repeat, Texture.TextureWrap.Repeat)
        setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
    }
    private val grassRegion = TextureRegion(grass)
    private var standsFbo: FrameBuffer? = null
    private var standsRegion: TextureRegion? = null

    /** Tribunalar keshini tayyorlaydi (GL kontekst kerak; render boshida, viewport.apply dan oldin) */
    fun ensureCache(shapes: ShapeRenderer) {
        if (standsRegion != null) return
        val fbo = FrameBuffer(Pixmap.Format.RGBA8888, (SW * STANDS_PPU).toInt(), (SH * STANDS_PPU).toInt(), false)
        val c = OrthographicCamera()
        c.setToOrtho(false, SW, SH)
        c.position.set(SX0 + SW / 2f, SY0 + SH / 2f, 0f)
        c.update()
        fbo.begin()
        Gdx.gl.glClearColor(0f, 0f, 0f, 0f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        drawStandsShapes(shapes, c)
        fbo.end()
        standsFbo = fbo
        standsRegion = TextureRegion(fbo.colorBufferTexture).apply { flip(false, true) }
    }

    private fun drawStands(batch: SpriteBatch, cam: OrthographicCamera) {
        val r = standsRegion ?: return
        batch.projectionMatrix = cam.combined
        batch.begin()
        batch.draw(r, SX0, SY0, SW, SH)
        batch.end()
    }

    /** Statik maydon: tribunalar (keshdan), o't, burchaklar, chiziqlar, darvoza og'zi. O'yinchilar va to'p bu yerda emas. */
    fun drawStatic(batch: SpriteBatch, shapes: ShapeRenderer, cam: OrthographicCamera) {
        val w = C.FIELD_W; val h = C.FIELD_H; val d = C.GOAL_DEPTH
        val g1 = (h - C.GOAL_W) / 2f
        drawStands(batch, cam)
        // 1) stadion ramkasi va o't
        batch.begin()
        batch.setColor(FRAME_COLOR)
        batch.draw(rWhite, -d - 1.2f, -1.4f, w + 2f * d + 2.4f, h + 2.8f)
        batch.setColor(1f, 1f, 1f, 1f)
        drawGrass(batch, 0f, 0f, w, h)
        batch.end()

        // burchaklarni "kesish": o'ydagi devorlar bilan mos (to'p burchakda tiqilib qolmaydi)
        shapes.projectionMatrix = cam.combined
        shapes.begin(ShapeRenderer.ShapeType.Filled)
        shapes.color = FRAME_COLOR
        val cc0 = C.CORNER_CUT
        cornerFill(shapes, -0.05f, -0.05f, cc0, cc0, cc0, 180f)
        cornerFill(shapes, w + 0.05f, -0.05f, w - cc0, cc0, cc0, 270f)
        cornerFill(shapes, w + 0.05f, h + 0.05f, w - cc0, h - cc0, cc0, 0f)
        cornerFill(shapes, -0.05f, h + 0.05f, cc0, h - cc0, cc0, 90f)
        shapes.end()

        // 2) maydon chiziqlari
        shapes.projectionMatrix = cam.combined
        shapes.begin(ShapeRenderer.ShapeType.Line)
        shapes.color = LINE_COLOR
        val cc = C.CORNER_CUT
        // to'g'ri chiziqlar: darvoza og'zi (g1..g2) ochiq qoladi
        shapes.line(cc, 0f, w - cc, 0f); shapes.line(cc, h, w - cc, h)
        shapes.line(0f, cc, 0f, g1); shapes.line(0f, g2, 0f, h - cc)
        shapes.line(w, cc, w, g1); shapes.line(w, g2, w, h - cc)
        // yumaloq burchaklar (yoy)
        arcLine(shapes, cc, cc, cc, 180f, 90f, 12); arcLine(shapes, w - cc, cc, cc, 270f, 90f, 12)
        arcLine(shapes, w - cc, h - cc, cc, 0f, 90f, 12); arcLine(shapes, cc, h - cc, cc, 90f, 90f, 12)
        shapes.line(w / 2f, 0f, w / 2f, h)
        shapes.circle(w / 2f, h / 2f, 3f, 48)
        val pb = 4.5f; val sb = 1.8f
        // jarima va darvoza maydoni: faqat 3 tomoni (chegara chizig'i ustiga chizilmaydi)
        boxLines(shapes, 0f, g1 - 1.5f, pb, g1 + C.GOAL_W + 1.5f); boxLines(shapes, w, g1 - 1.5f, w - pb, g1 + C.GOAL_W + 1.5f)
        boxLines(shapes, 0f, g1 - 0.4f, sb, g1 + C.GOAL_W + 0.4f); boxLines(shapes, w, g1 - 0.4f, w - sb, g1 + C.GOAL_W + 0.4f)
        arcLine(shapes, pb, h / 2f, 2.2f, -55f, 110f, 24)
        arcLine(shapes, w - pb, h / 2f, 2.2f, 125f, 110f, 24)
        shapes.end()
        shapes.begin(ShapeRenderer.ShapeType.Filled)
        shapes.color = LINE_COLOR
        shapes.circle(w / 2f, h / 2f, 0.14f, 10)
        shapes.end()

    }

    private fun drawGrass(batch: SpriteBatch, x: Float, y: Float, w: Float, h: Float) {
        val tile = 6f
        grassRegion.setRegion(x / tile, y / tile, (x + w) / tile, (y + h) / tile)
        batch.draw(grassRegion, x, y, w, h)
    }

    /** Tribunalar, reklama bandi va tomoshabinlar (faqat ko'rinish uchun) - FrameBuffer'ga bir marta chiziladi */
    private fun drawStandsShapes(shapes: ShapeRenderer, cam: OrthographicCamera) {
        val w = C.FIELD_W; val h = C.FIELD_H
        val x0 = -20f; val x1 = w + 20f
        shapes.projectionMatrix = cam.combined
        shapes.begin(ShapeRenderer.ShapeType.Filled)
        // tribuna asoslari
        shapes.color = Color(0.05f, 0.09f, 0.2f, 1f)
        shapes.rect(x0, -9f, x1 - x0, 7.5f)
        shapes.rect(x0, h + 1.5f, x1 - x0, 7.5f)
        // reklama bandi: rangli segmentlar
        val ad = arrayOf(Color(0.12f, 0.43f, 1f, 1f), Color(0.92f, 0.2f, 0.22f, 1f), Color(1f, 0.75f, 0.15f, 1f), Color(0.95f, 0.97f, 1f, 1f))
        var i = 0
        var x = x0
        while (x < x1) {
            shapes.color = ad[i % ad.size]
            shapes.rect(x, -1.5f, 3f, 0.55f)
            shapes.rect(x, h + 0.95f, 3f, 0.55f)
            x += 3f; i++
        }
        // tomoshabinlar: 4 qator, kvadrat nuqtalar (rang bo'yicha tasodifiy, lekin barqaror)
        val crowd = arrayOf(Color(0.85f, 0.3f, 0.3f, 1f), Color(0.95f, 0.85f, 0.4f, 1f), Color(0.4f, 0.75f, 1f, 1f), Color(0.9f, 0.9f, 0.95f, 1f), Color(0.55f, 0.85f, 0.5f, 1f))
        var k = 0
        for (r in 0 until 4) {
            var cx = x0 + 0.2f
            while (cx < x1) {
                shapes.color = crowd[(k * 7 + r * 3) % crowd.size]
                shapes.rect(cx, -2.6f - r * 0.95f, 0.26f, 0.26f)
                shapes.rect(cx + 0.2f, h + 2.6f + r * 0.95f, 0.26f, 0.26f)
                cx += 0.85f; k++
            }
        }
        // yon tomonlar (darvozalar orqasi)
        shapes.color = Color(0.05f, 0.09f, 0.2f, 1f)
        shapes.rect(x0, -1.4f, -C.GOAL_DEPTH - x0 - 1f, h + 2.8f)
        shapes.rect(w + C.GOAL_DEPTH + 1f, -1.4f, x1 - (w + C.GOAL_DEPTH + 1f), h + 2.8f)
        for (fx in listOf(-15f, w + 15f)) for (fy in listOf(-7f, h + 7f)) {
            shapes.color = Color(0.25f, 0.28f, 0.33f, 1f); shapes.rect(fx - 0.2f, fy - 1.2f, 0.4f, 2.4f)
            shapes.color = Color(1f, 1f, 0.85f, 1f); shapes.rect(fx - 1f, fy + 1f, 2f, 0.7f)
        }
        shapes.end()
    }

    /** Quti chizig'i: chegara tomoni chizilmaydi (darvoza og'zi ochiq) */
    private fun boxLines(shapes: ShapeRenderer, edgeX: Float, y0: Float, innerX: Float, y1: Float) {
        shapes.line(edgeX, y0, innerX, y0); shapes.line(innerX, y0, innerX, y1); shapes.line(innerX, y1, edgeX, y1)
    }

    /** Burchak: tashqi nuqtadan yoy gacha uchburchaklar (maydondan tashqarini bo'yaydi) */
    private fun cornerFill(shapes: ShapeRenderer, ox: Float, oy: Float, cx: Float, cy: Float, r: Float, startDeg: Float) {
        val seg = 10
        var px = cx + MathUtils.cosDeg(startDeg) * r; var py = cy + MathUtils.sinDeg(startDeg) * r
        for (i in 1..seg) {
            val a = startDeg + 90f * i / seg
            val x = cx + MathUtils.cosDeg(a) * r; val y = cy + MathUtils.sinDeg(a) * r
            shapes.triangle(ox, oy, px, py, x, y); px = x; py = y
        }
    }

    /** Yarim doira: ShapeRenderer.arc() Line rejimida markazga radial chiziqlar ham chizadi, shuning uchun segmentlar bilan. */
    private fun arcLine(shapes: ShapeRenderer, cx: Float, cy: Float, r: Float, startDeg: Float, sweepDeg: Float, seg: Int) {
        var px = cx + MathUtils.cosDeg(startDeg) * r
        var py = cy + MathUtils.sinDeg(startDeg) * r
        for (i in 1..seg) {
            val a = startDeg + sweepDeg * i / seg
            val x = cx + MathUtils.cosDeg(a) * r
            val y = cy + MathUtils.sinDeg(a) * r
            shapes.line(px, py, x, y); px = x; py = y
        }
    }

    fun dispose() {
        standsFbo?.dispose()
        grass.dispose()
    }
}
