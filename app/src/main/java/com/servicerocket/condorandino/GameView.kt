package com.servicerocket.condorandino

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A Flappy-Bird-style game starring an Andean (Chilean) condor.
 *
 * Everything is drawn procedurally on a [Canvas], so the game ships with no
 * bitmap assets. Tap to make the condor flap and climb; gravity pulls it back
 * down. Thread the condor through the gaps between the Andes rock columns to
 * score. One collision ends the run.
 */
class GameView(context: Context) : SurfaceView(context), Runnable, SurfaceHolder.Callback {

    private enum class State { READY, PLAYING, GAME_OVER }

    // --- Threading / lifecycle ---------------------------------------------
    private var gameThread: Thread? = null
    @Volatile private var running = false
    private var surfaceReady = false
    private var foreground = false

    // --- Geometry (set once the surface size is known) ---------------------
    private var w = 0f
    private var h = 0f
    private val density = resources.displayMetrics.density

    // --- Game state --------------------------------------------------------
    private var state = State.READY
    private var score = 0
    private var highScore = 0

    // --- Condor ------------------------------------------------------------
    private var condorX = 0f
    private var condorY = 0f
    private var condorVel = 0f
    private var condorRadius = 0f
    private var wingPhase = 0f          // drives the wing-flap animation
    private var wingSpeed = 6f          // radians/sec, faster right after a flap

    // --- Physics (all scaled to screen height so it plays the same anywhere) ---
    private var gravity = 0f
    private var flapVelocity = 0f
    private var scrollSpeed = 0f

    // --- Obstacles (Andean rock columns) -----------------------------------
    private class Obstacle(var x: Float, var gapCenter: Float, var scored: Boolean = false)
    private val obstacles = ArrayList<Obstacle>()
    private var obstacleWidth = 0f
    private var gapHeight = 0f
    private var spacingX = 0f
    private var groundHeight = 0f

    // --- Parallax background -----------------------------------------------
    private var mountainOffsetFar = 0f
    private var mountainOffsetNear = 0f
    private val clouds = ArrayList<FloatArray>() // [x, y, scale]

    // --- Paints ------------------------------------------------------------
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 0, 0, 0)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private var skyShader: Shader? = null

    private val prefs = context.getSharedPreferences("condor_prefs", Context.MODE_PRIVATE)

    init {
        holder.addCallback(this)
        isFocusable = true
        highScore = prefs.getInt("high_score", 0)
    }

    private fun dp(v: Float) = v * density

    // -----------------------------------------------------------------------
    // Surface lifecycle
    // -----------------------------------------------------------------------
    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        startThreadIfReady()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        w = width.toFloat()
        h = height.toFloat()
        configureGeometry()
        startThreadIfReady()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        stopThread()
    }

    fun resume() {
        foreground = true
        startThreadIfReady()
    }

    fun pause() {
        foreground = false
        stopThread()
    }

    private fun startThreadIfReady() {
        if (foreground && surfaceReady && gameThread == null && w > 0f) {
            running = true
            gameThread = Thread(this).also { it.start() }
        }
    }

    private fun stopThread() {
        running = false
        gameThread?.let { t ->
            var retry = true
            while (retry) {
                try {
                    t.join()
                    retry = false
                } catch (_: InterruptedException) {
                    // try again
                }
            }
        }
        gameThread = null
    }

    // -----------------------------------------------------------------------
    // Setup
    // -----------------------------------------------------------------------
    private fun configureGeometry() {
        groundHeight = h * 0.10f
        condorRadius = h * 0.030f
        condorX = w * 0.28f

        gravity = h * 2.7f          // px / s^2
        flapVelocity = h * 0.80f    // upward velocity applied on a tap
        scrollSpeed = w * 0.52f     // px / s

        obstacleWidth = w * 0.19f
        gapHeight = h * 0.32f
        spacingX = w * 0.70f

        skyShader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(
                Color.rgb(0x3A, 0x8F, 0xCB),  // deep sky
                Color.rgb(0x7E, 0xC4, 0xE8),  // mid sky
                Color.rgb(0xCF, 0xEC, 0xF7)   // pale horizon
            ),
            floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP
        )

        resetClouds()
        resetGame()
    }

    private fun resetClouds() {
        clouds.clear()
        val rnd = java.util.Random(7)
        repeat(5) {
            val cx = rnd.nextFloat() * w
            val cy = h * (0.08f + rnd.nextFloat() * 0.45f)
            val scale = 0.6f + rnd.nextFloat() * 0.9f
            clouds.add(floatArrayOf(cx, cy, scale))
        }
    }

    private fun resetGame() {
        state = State.READY
        score = 0
        condorY = h * 0.42f
        condorVel = 0f
        obstacles.clear()
        // Pre-spawn a few obstacles to the right of the screen.
        var startX = w + obstacleWidth
        repeat(4) {
            obstacles.add(Obstacle(startX, randomGapCenter()))
            startX += spacingX
        }
    }

    private fun randomGapCenter(): Float {
        val minC = gapHeight * 0.5f + h * 0.06f
        val maxC = (h - groundHeight) - gapHeight * 0.5f - h * 0.04f
        return minC + Math.random().toFloat() * (maxC - minC)
    }

    // -----------------------------------------------------------------------
    // Input
    // -----------------------------------------------------------------------
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            onTap()
            return true
        }
        return super.onTouchEvent(event)
    }

    private fun onTap() {
        when (state) {
            State.READY -> {
                state = State.PLAYING
                flap()
            }
            State.PLAYING -> flap()
            State.GAME_OVER -> resetGame()
        }
    }

    private fun flap() {
        condorVel = -flapVelocity
        wingSpeed = 22f // beat the wings quickly right after a flap
    }

    // -----------------------------------------------------------------------
    // Game loop
    // -----------------------------------------------------------------------
    override fun run() {
        var last = System.nanoTime()
        while (running) {
            val now = System.nanoTime()
            var dt = (now - last) / 1_000_000_000f
            last = now
            if (dt > 0.05f) dt = 0.05f // clamp after stalls so physics stays sane

            update(dt)
            render()

            // Cap to roughly 60 FPS.
            val frameMs = (System.nanoTime() - now) / 1_000_000f
            val sleep = (16.6f - frameMs).toLong()
            if (sleep > 0) {
                try {
                    Thread.sleep(sleep)
                } catch (_: InterruptedException) {
                }
            }
        }
    }

    private fun update(dt: Float) {
        // Animate wings & a gentle parallax even on the menu.
        wingPhase += wingSpeed * dt
        wingSpeed = max(6f, wingSpeed - 30f * dt) // decay back to a calm glide
        mountainOffsetFar = (mountainOffsetFar + scrollSpeed * 0.15f * dt) % w
        mountainOffsetNear = (mountainOffsetNear + scrollSpeed * 0.35f * dt) % w
        for (c in clouds) {
            c[0] -= scrollSpeed * 0.20f * dt
            if (c[0] < -w * 0.25f) c[0] = w + w * 0.1f
        }

        when (state) {
            State.READY -> {
                // Idle bob so the condor looks alive on the start screen.
                condorY = h * 0.42f + sin(wingPhase * 0.6f) * h * 0.012f
            }
            State.PLAYING -> updatePlaying(dt)
            State.GAME_OVER -> {
                // Let the condor drop to the ground after a crash.
                condorVel += gravity * dt
                condorY += condorVel * dt
                val floor = h - groundHeight - condorRadius
                if (condorY > floor) {
                    condorY = floor
                    condorVel = 0f
                }
            }
        }
    }

    private fun updatePlaying(dt: Float) {
        condorVel += gravity * dt
        condorY += condorVel * dt

        // Move obstacles and award a point once the condor clears each one.
        for (o in obstacles) {
            o.x -= scrollSpeed * dt
            if (!o.scored && o.x + obstacleWidth < condorX) {
                o.scored = true
                score++
            }
        }
        // Drop off-screen columns and append new ones to keep the field full.
        obstacles.removeAll { it.x + obstacleWidth < 0f }
        while (obstacles.size < 5) {
            val rightmost = obstacles.maxByOrNull { it.x }?.x ?: w
            obstacles.add(Obstacle(rightmost + spacingX, randomGapCenter()))
        }

        checkCollisions()
    }

    private fun checkCollisions() {
        // Ceiling / ground.
        if (condorY - condorRadius < 0f) {
            condorY = condorRadius
            condorVel = 0f
        }
        if (condorY + condorRadius >= h - groundHeight) {
            endGame()
            return
        }
        // Obstacle columns: treat the condor as a circle vs. two rectangles.
        for (o in obstacles) {
            val left = o.x
            val right = o.x + obstacleWidth
            if (condorX + condorRadius < left || condorX - condorRadius > right) continue
            val gapTop = o.gapCenter - gapHeight / 2f
            val gapBottom = o.gapCenter + gapHeight / 2f
            if (condorY - condorRadius < gapTop || condorY + condorRadius > gapBottom) {
                endGame()
                return
            }
        }
    }

    private fun endGame() {
        if (state == State.PLAYING) {
            state = State.GAME_OVER
            if (score > highScore) {
                highScore = score
                prefs.edit().putInt("high_score", highScore).apply()
            }
        }
    }

    // -----------------------------------------------------------------------
    // Rendering
    // -----------------------------------------------------------------------
    private fun render() {
        val holder = holder
        if (!holder.surface.isValid) return
        val canvas = holder.lockCanvas() ?: return
        try {
            drawSky(canvas)
            drawMountains(canvas)
            drawClouds(canvas)
            drawObstacles(canvas)
            drawGround(canvas)
            drawCondor(canvas)
            drawHud(canvas)
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    private fun drawSky(canvas: Canvas) {
        paint.shader = skyShader
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
    }

    private fun drawMountains(canvas: Canvas) {
        // Two parallax ridgelines of the Andes.
        drawRidge(canvas, mountainOffsetFar, h * 0.62f, h * 0.16f, Color.rgb(0x6E, 0x8B, 0xA6))
        drawRidge(canvas, mountainOffsetNear, h * 0.70f, h * 0.22f, Color.rgb(0x55, 0x6E, 0x86))
    }

    private fun drawRidge(canvas: Canvas, offset: Float, baseY: Float, amp: Float, color: Int) {
        paint.color = color
        val path = Path()
        path.moveTo(-w, h)
        val step = w / 6f
        var x = -offset - w
        path.lineTo(x, baseY)
        var i = 0
        while (x < w * 2) {
            val peakY = baseY - (if (i % 2 == 0) amp else amp * 0.6f)
            path.lineTo(x + step / 2f, peakY)
            path.lineTo(x + step, baseY)
            x += step
            i++
        }
        path.lineTo(x, h)
        path.close()
        canvas.drawPath(path, paint)

        // A little snow on the higher peaks.
        paint.color = Color.argb(220, 245, 248, 252)
        x = -offset - w
        i = 0
        while (x < w * 2) {
            if (i % 2 == 0) {
                val peakX = x + step / 2f
                val peakY = baseY - amp
                val snow = Path()
                snow.moveTo(peakX - step * 0.16f, peakY + amp * 0.22f)
                snow.lineTo(peakX, peakY)
                snow.lineTo(peakX + step * 0.16f, peakY + amp * 0.22f)
                snow.lineTo(peakX + step * 0.05f, peakY + amp * 0.16f)
                snow.lineTo(peakX, peakY + amp * 0.26f)
                snow.lineTo(peakX - step * 0.05f, peakY + amp * 0.16f)
                snow.close()
                canvas.drawPath(snow, paint)
            }
            x += step
            i++
        }
    }

    private fun drawClouds(canvas: Canvas) {
        paint.color = Color.argb(210, 255, 255, 255)
        for (c in clouds) {
            val cx = c[0]
            val cy = c[1]
            val s = c[2] * h * 0.04f
            canvas.drawCircle(cx, cy, s, paint)
            canvas.drawCircle(cx + s * 1.1f, cy + s * 0.2f, s * 0.8f, paint)
            canvas.drawCircle(cx - s * 1.1f, cy + s * 0.25f, s * 0.7f, paint)
            canvas.drawCircle(cx + s * 0.3f, cy - s * 0.5f, s * 0.75f, paint)
        }
    }

    private fun drawObstacles(canvas: Canvas) {
        for (o in obstacles) {
            val gapTop = o.gapCenter - gapHeight / 2f
            val gapBottom = o.gapCenter + gapHeight / 2f
            drawRockColumn(canvas, o.x, 0f, gapTop, fromTop = true)
            drawRockColumn(canvas, o.x, gapBottom, h - groundHeight, fromTop = false)
        }
    }

    private fun drawRockColumn(canvas: Canvas, x: Float, top: Float, bottom: Float, fromTop: Boolean) {
        val rect = RectF(x, top, x + obstacleWidth, bottom)
        // Rock body.
        paint.color = Color.rgb(0x7B, 0x5E, 0x46)
        canvas.drawRect(rect, paint)
        // Shaded right edge for depth.
        paint.color = Color.rgb(0x5E, 0x46, 0x33)
        canvas.drawRect(x + obstacleWidth * 0.72f, top, x + obstacleWidth, bottom, paint)
        // Lit left edge.
        paint.color = Color.rgb(0x96, 0x76, 0x59)
        canvas.drawRect(x, top, x + obstacleWidth * 0.16f, bottom, paint)

        // A chunky "cap" of rock with a snowy tip facing the gap.
        val capH = h * 0.035f
        val capRect: RectF
        val snowRect: RectF
        if (fromTop) {
            capRect = RectF(x - obstacleWidth * 0.06f, bottom - capH, x + obstacleWidth * 1.06f, bottom)
            snowRect = RectF(capRect.left, bottom - capH * 0.35f, capRect.right, bottom)
        } else {
            capRect = RectF(x - obstacleWidth * 0.06f, top, x + obstacleWidth * 1.06f, top + capH)
            snowRect = RectF(capRect.left, top, capRect.right, top + capH * 0.35f)
        }
        paint.color = Color.rgb(0x6B, 0x50, 0x3B)
        canvas.drawRect(capRect, paint)
        paint.color = Color.argb(235, 246, 249, 252)
        canvas.drawRect(snowRect, paint)
    }

    private fun drawGround(canvas: Canvas) {
        val top = h - groundHeight
        paint.color = Color.rgb(0xC2, 0x9B, 0x5E) // arid Andes soil
        canvas.drawRect(0f, top, w, h, paint)
        paint.color = Color.rgb(0x8F, 0xA8, 0x55) // scrubby green strip
        canvas.drawRect(0f, top, w, top + groundHeight * 0.22f, paint)
        // Simple dashed texture line.
        paint.color = Color.argb(60, 0, 0, 0)
        paint.strokeWidth = dp(2f)
        var x = -(mountainOffsetNear % (w / 10f))
        while (x < w) {
            canvas.drawLine(x, top + groundHeight * 0.5f, x + w / 20f, top + groundHeight * 0.5f, paint)
            x += w / 10f
        }
    }

    private fun drawCondor(canvas: Canvas) {
        val cx = condorX
        val cy = condorY
        val r = condorRadius

        // Tilt with vertical velocity: nose up when rising, dive when falling.
        val tilt = max(-25f, min(60f, condorVel / (flapVelocity) * 35f))
        canvas.save()
        canvas.rotate(tilt, cx, cy)

        // Wing flap factor in [-1, 1].
        val flap = sin(wingPhase)
        val wingSpan = r * 3.4f
        val wingLift = flap * r * 1.1f

        // --- Wings (drawn behind the body) ---
        paint.color = Color.rgb(0x1A, 0x1A, 0x1A)
        // Left wing.
        var wing = Path()
        wing.moveTo(cx, cy)
        wing.quadTo(cx - wingSpan * 0.5f, cy - wingLift - r * 0.4f, cx - wingSpan, cy - wingLift * 0.4f)
        wing.quadTo(cx - wingSpan * 0.5f, cy + r * 0.5f, cx, cy + r * 0.3f)
        wing.close()
        canvas.drawPath(wing, paint)
        // Right wing.
        wing = Path()
        wing.moveTo(cx, cy)
        wing.quadTo(cx + wingSpan * 0.5f, cy - wingLift - r * 0.4f, cx + wingSpan, cy - wingLift * 0.4f)
        wing.quadTo(cx + wingSpan * 0.5f, cy + r * 0.5f, cx, cy + r * 0.3f)
        wing.close()
        canvas.drawPath(wing, paint)

        // White flight-feather band along the wings (the condor's signature).
        paint.color = Color.argb(235, 245, 245, 245)
        paint.strokeWidth = r * 0.35f
        paint.style = Paint.Style.STROKE
        canvas.drawLine(cx - wingSpan * 0.85f, cy - wingLift * 0.4f, cx - r * 0.4f, cy + r * 0.05f, paint)
        canvas.drawLine(cx + wingSpan * 0.85f, cy - wingLift * 0.4f, cx + r * 0.4f, cy + r * 0.05f, paint)
        paint.style = Paint.Style.FILL

        // --- Body ---
        paint.color = Color.rgb(0x20, 0x20, 0x20)
        canvas.drawOval(RectF(cx - r * 1.1f, cy - r * 0.85f, cx + r * 1.3f, cy + r * 0.95f), paint)

        // Tail.
        val tail = Path()
        tail.moveTo(cx - r * 0.9f, cy)
        tail.lineTo(cx - r * 1.9f, cy - r * 0.35f)
        tail.lineTo(cx - r * 1.9f, cy + r * 0.45f)
        tail.close()
        canvas.drawPath(tail, paint)

        // White neck ruff.
        paint.color = Color.WHITE
        canvas.drawCircle(cx + r * 0.95f, cy - r * 0.45f, r * 0.55f, paint)

        // Bald head (the Andean condor's head is unfeathered).
        paint.color = Color.rgb(0xC8, 0x85, 0x5A)
        canvas.drawCircle(cx + r * 1.35f, cy - r * 0.8f, r * 0.42f, paint)

        // Caruncle (the comb on a male condor).
        paint.color = Color.rgb(0x9B, 0x5A, 0x3C)
        val comb = Path()
        comb.moveTo(cx + r * 1.2f, cy - r * 1.18f)
        comb.quadTo(cx + r * 1.5f, cy - r * 1.7f, cx + r * 1.72f, cy - r * 1.1f)
        comb.close()
        canvas.drawPath(comb, paint)

        // Beak (hooked).
        paint.color = Color.rgb(0xE8, 0xD2, 0xA8)
        val beak = Path()
        beak.moveTo(cx + r * 1.7f, cy - r * 0.85f)
        beak.lineTo(cx + r * 2.15f, cy - r * 0.6f)
        beak.quadTo(cx + r * 1.95f, cy - r * 0.45f, cx + r * 1.65f, cy - r * 0.55f)
        beak.close()
        canvas.drawPath(beak, paint)

        // Eye.
        paint.color = Color.BLACK
        canvas.drawCircle(cx + r * 1.45f, cy - r * 0.92f, r * 0.10f, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(cx + r * 1.42f, cy - r * 0.96f, r * 0.035f, paint)

        canvas.restore()
    }

    private fun drawHud(canvas: Canvas) {
        when (state) {
            State.READY -> {
                textPaint.textSize = h * 0.055f
                drawCenteredText(canvas, "CÓNDOR ANDINO", w / 2f, h * 0.20f)
                textPaint.textSize = h * 0.030f
                drawCenteredText(canvas, "Toca para volar", w / 2f, h * 0.28f)
                if (highScore > 0) {
                    textPaint.textSize = h * 0.026f
                    drawCenteredText(canvas, "Mejor: $highScore", w / 2f, h * 0.33f)
                }
            }
            State.PLAYING -> {
                textPaint.textSize = h * 0.075f
                drawCenteredText(canvas, score.toString(), w / 2f, h * 0.14f)
            }
            State.GAME_OVER -> {
                textPaint.textSize = h * 0.075f
                drawCenteredText(canvas, score.toString(), w / 2f, h * 0.14f)

                // Panel.
                val panel = RectF(w * 0.15f, h * 0.34f, w * 0.85f, h * 0.56f)
                paint.color = Color.argb(190, 30, 40, 55)
                canvas.drawRoundRect(panel, dp(16f), dp(16f), paint)

                textPaint.textSize = h * 0.045f
                drawCenteredText(canvas, "¡Game Over!", w / 2f, h * 0.40f)
                textPaint.textSize = h * 0.030f
                drawCenteredText(canvas, "Puntaje: $score", w / 2f, h * 0.46f)
                drawCenteredText(canvas, "Mejor: $highScore", w / 2f, h * 0.50f)
                textPaint.textSize = h * 0.026f
                drawCenteredText(canvas, "Toca para reintentar", w / 2f, h * 0.545f)
            }
        }
    }

    private fun drawCenteredText(canvas: Canvas, text: String, cx: Float, cy: Float) {
        shadowPaint.textSize = textPaint.textSize
        val off = textPaint.textSize * 0.04f
        canvas.drawText(text, cx + off, cy + off, shadowPaint)
        canvas.drawText(text, cx, cy, textPaint)
    }
}
