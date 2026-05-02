package com.tankbriga.app.render

import android.graphics.*
import com.tankbriga.engine.GunboundAimReference
import com.tankbriga.engine.Tank
import com.tankbriga.engine.WindState
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Compact mobile controls. Keeps world visible and separates CAM, AIM, POWER and FIRE. */
enum class HudControl { NONE, CAMERA, AIM, FIRE, POWER_BAR, ANGLE_UP, ANGLE_DOWN }

class HUDRenderer {
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        setColor(Color.WHITE)
        isFakeBoldText = true
        textAlign = Paint.Align.LEFT
    }
    private val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        setColor(Color.WHITE)
        setAlpha(220)
        textAlign = Paint.Align.LEFT
    }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        setColor(Color.WHITE)
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    private val timerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        setColor(Color.WHITE)
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    private val anglePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        setColor(Color.rgb(255, 214, 70))
        isFakeBoldText = true
        textAlign = Paint.Align.LEFT
        setShadowLayer(12f, 0f, 0f, Color.RED)
    }
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        setColor(Color.argb(135, 10, 18, 28))
        style = Paint.Style.FILL
    }
    private val controlPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        setColor(Color.argb(120, 255, 255, 255))
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        setColor(Color.argb(230, 255, 255, 255))
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val bluePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { setColor(Color.rgb(55, 138, 221)) }
    private val redPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { setColor(Color.rgb(230, 70, 70)) }
    private val yellowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { setColor(Color.rgb(255, 214, 70)) }
    private val greenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { setColor(Color.rgb(80, 220, 120)) }

    fun hitTest(x: Float, y: Float, width: Int, height: Int): HudControl {
        val s = uiScale(width, height)
        if (cameraRect(s).contains(x, y)) return HudControl.CAMERA

        val fire = fireCircle(width, height, s)
        val dx = x - fire.x
        val dy = y - fire.y
        if (dx * dx + dy * dy <= fire.r * fire.r) return HudControl.FIRE

        if (aimRect(width, height, s).contains(x, y)) return HudControl.AIM
        if (angleUpRect(width, height, s).contains(x, y)) return HudControl.ANGLE_UP
        if (angleDownRect(width, height, s).contains(x, y)) return HudControl.ANGLE_DOWN
        if (powerRect(width, height, s).contains(x, y)) return HudControl.POWER_BAR
        return HudControl.NONE
    }

    fun isInsideFire(x: Float, y: Float, width: Int, height: Int): Boolean {
        val s = uiScale(width, height)
        val fire = fireCircle(width, height, s)
        val dx = x - fire.x
        val dy = y - fire.y
        return dx * dx + dy * dy <= fire.r * fire.r
    }

    fun powerFromTouch(x: Float, width: Int, height: Int): Float {
        val s = uiScale(width, height)
        val rect = powerRect(width, height, s)
        return (((x - rect.left) / rect.width()) * 100f).coerceIn(0f, 100f)
    }

    fun angleFromTouch(y: Float, width: Int, height: Int): Float {
        val s = uiScale(width, height)
        val rect = aimRect(width, height, s)
        val usableTop = rect.top + 18f * s
        val usableHeight = (rect.height() - 36f * s).coerceAtLeast(1f)
        val t = ((y - usableTop) / usableHeight).coerceIn(0f, 1f)
        return (175f - t * 170f).coerceIn(5f, 175f)
    }

    fun draw(
        canvas: Canvas,
        width: Int,
        height: Int,
        wind: WindState,
        power: Float,
        angle: Float,
        phase: String,
        camMode: String,
        players: List<Tank>,
        terrainWidth: Int,
        terrainHeight: Int,
        camera: CameraController?,
        currentPlayer: Tank?,
        remainingSeconds: Int,
        message: String?,
        isCharging: Boolean
    ) {
        val s = uiScale(width, height)
        textPaint.textSize = 18f * s
        smallPaint.textSize = 12f * s

        drawTopStatus(canvas, width, s, wind, phase, camMode, currentPlayer, angle)
        drawCameraButton(canvas, s)
        drawTimerHex(canvas, width, s, remainingSeconds)
        drawMiniMap(canvas, width, s, players, terrainWidth, terrainHeight, camera)
        drawAimSlider(canvas, width, height, s, angle)
        drawAngleButtons(canvas, width, height, s)
        drawPower(canvas, width, height, s, power, isCharging)
        if (phase == "LOBBY") drawLobbyOverlay(canvas, width, height, s, players)
        if (!message.isNullOrBlank()) drawMessage(canvas, width, height, s, message)
    }

    private fun drawLobbyOverlay(canvas: Canvas, width: Int, height: Int, s: Float, players: List<Tank>) {
        val w = width.toFloat()
        val h = height.toFloat()
        
        // Darken background
        canvas.drawRect(0f, 0f, w, h, Paint().apply { color = Color.BLACK; alpha = 180 })
        
        centerPaint.textSize = 32f * s
        canvas.drawText("SALA DE ESPERA", w / 2f, h * 0.25f, centerPaint)
        
        smallPaint.textSize = 16f * s
        canvas.drawText("Aguardando amigos na mesma rede WiFi...", w / 2f, h * 0.32f, smallPaint)
        
        // List Players
        val startY = h * 0.45f
        players.forEachIndexed { i, p ->
            textPaint.setColor(p.color)
            textPaint.textSize = 22f * s
            canvas.drawText("${i+1}. ${p.name}", w / 2f - 100f * s, startY + i * 40f * s, textPaint)
        }
        
        if (players.size >= 2) {
            yellowPaint.textSize = 18f * s
            canvas.drawText("AGUARDANDO HOST INICIAR...", w / 2f, h * 0.85f, centerPaint.apply { setColor(Color.YELLOW) })
        }
    }

    private fun drawTopStatus(
        canvas: Canvas,
        width: Int,
        s: Float,
        wind: WindState,
        phase: String,
        camMode: String,
        currentPlayer: Tank?,
        angle: Float
    ) {
        val hasBonus = angle > 70
        val panelWidth = 165f * s
        val panelHeight = if (hasBonus) 104f * s else 82f * s
        val rect = RectF(10f * s, 8f * s, 10f * s + panelWidth, 8f * s + panelHeight)
        
        canvas.drawRoundRect(rect, 14f * s, 14f * s, panelPaint)
        canvas.drawRoundRect(rect, 14f * s, 14f * s, strokePaint)

        val turnLabel = currentPlayer?.let { if (it.isBot) it.name else "SUA VEZ" } ?: phase
        textPaint.textSize = 21f * s
        textPaint.setColor(if (currentPlayer?.isBot == false) Color.rgb(120, 215, 255) else Color.WHITE)
        canvas.drawText(turnLabel, rect.left + 12f * s, rect.top + 28f * s, textPaint)
        
        textPaint.setColor(Color.WHITE)
        smallPaint.textSize = 14f * s
        canvas.drawText("VENTO ${wind.arrow()}", rect.left + 12f * s, rect.top + 52f * s, smallPaint)

        anglePaint.textSize = 24f * s
        val angleText = "ÂNGULO: ${angle.toInt()}°"
        canvas.drawText(angleText, rect.left + 12f * s, rect.top + 78f * s, anglePaint)
        
        if (hasBonus) {
            smallPaint.setColor(Color.rgb(255, 100, 100))
            smallPaint.textSize = 13f * s
            canvas.drawText("DANO BÔNUS +50%", rect.left + 12f * s, rect.top + 98f * s, smallPaint)
            smallPaint.setColor(Color.WHITE)
        }
    }

    private fun drawTimerHex(canvas: Canvas, width: Int, s: Float, seconds: Int) {
        val size = 70f * s
        val left = width - size - 14f * s
        val top = 12f * s
        val cx = left + size / 2f
        val cy = top + size / 2f
        timerPaint.setColor(if (seconds <= 5) Color.rgb(255, 70, 70) else Color.argb(135, 10, 18, 28))
        canvas.drawCircle(cx, cy, size / 2f, timerPaint)
        canvas.drawCircle(cx, cy, size / 2f, strokePaint)
        timerPaint.setColor(Color.WHITE)
        timerPaint.textSize = 32f * s
        canvas.drawText(seconds.toString(), cx, cy + 11f * s, timerPaint)
        smallPaint.textSize = 11f * s
        canvas.drawText("TEMPO", left + 8f * s, top - 8f * s, smallPaint)
    }

    private fun drawMessage(canvas: Canvas, width: Int, height: Int, s: Float, message: String) {
        centerPaint.textSize = 24f * s
        val textWidth = centerPaint.measureText(message).coerceAtMost(width * 0.8f)
        val rect = RectF(width / 2f - textWidth / 2f - 20f * s, height * 0.22f, width / 2f + textWidth / 2f + 20f * s, height * 0.22f + 50f * s)
        canvas.drawRoundRect(rect, 16f * s, 16f * s, panelPaint)
        canvas.drawRoundRect(rect, 16f * s, 16f * s, strokePaint)
        canvas.drawText(message, width / 2f, rect.centerY() + 8f * s, centerPaint)
    }

    private fun drawCameraButton(canvas: Canvas, s: Float) {
        val rect = cameraRect(s)
        canvas.drawRoundRect(rect, 14f * s, 14f * s, controlPaint)
        canvas.drawRoundRect(rect, 14f * s, 14f * s, strokePaint)
        textPaint.textSize = 15f * s
        canvas.drawText("CAM", rect.left + 12f * s, rect.top + 27f * s, textPaint)
    }

    private fun drawMiniMap(canvas: Canvas, width: Int, s: Float, players: List<Tank>, terrainWidth: Int, terrainHeight: Int, camera: CameraController?) {
        val rect = RectF(width * 0.38f, 12f * s, width * 0.62f, 62f * s)
        canvas.drawRoundRect(rect, 12f * s, 12f * s, panelPaint)
        canvas.drawRoundRect(rect, 12f * s, 12f * s, strokePaint)
        players.forEach { tank ->
            val px = rect.left + (tank.position.x / terrainWidth.coerceAtLeast(1)) * rect.width()
            val py = rect.top + (tank.position.y / terrainHeight.coerceAtLeast(1)) * rect.height()
            if (tank.hp > 0) canvas.drawCircle(px, py, if (tank.isBot) 3.0f * s else 5.0f * s, if (tank.isBot) redPaint else bluePaint)
        }
        camera?.visibleWorldRect()?.let { view ->
            val vx1 = rect.left + (view.left / terrainWidth.coerceAtLeast(1)) * rect.width()
            val vx2 = rect.left + (view.right / terrainWidth.coerceAtLeast(1)) * rect.width()
            val vy1 = rect.top + (view.top / terrainHeight.coerceAtLeast(1)) * rect.height()
            val vy2 = rect.top + (view.bottom / terrainHeight.coerceAtLeast(1)) * rect.height()
            canvas.drawRect(vx1, vy1, vx2, vy2, strokePaint)
        }
    }

    private fun drawAimSlider(canvas: Canvas, width: Int, height: Int, s: Float, angle: Float) {
        val rect = aimRect(width, height, s)
        canvas.drawRoundRect(rect, 16f * s, 16f * s, panelPaint)
        canvas.drawRoundRect(rect, 16f * s, 16f * s, strokePaint)
        val trackX = rect.centerX()
        canvas.drawLine(trackX, rect.top + 18f * s, trackX, rect.bottom - 18f * s, strokePaint)
        val t = ((175f - angle) / 170f).coerceIn(0f, 1f)
        val y = rect.top + 18f * s + t * (rect.height() - 36f * s)
        canvas.drawCircle(trackX, y, 11f * s, yellowPaint)
        smallPaint.textSize = 11f * s
        canvas.drawText("MIRA", rect.left + 5f * s, rect.top - 8f * s, smallPaint)
    }

    private fun drawAngleButtons(canvas: Canvas, width: Int, height: Int, s: Float) {
        val up = angleUpRect(width, height, s)
        val down = angleDownRect(width, height, s)
        canvas.drawRoundRect(up, 12f * s, 12f * s, controlPaint)
        canvas.drawRoundRect(up, 12f * s, 12f * s, strokePaint)
        canvas.drawText("+", up.centerX(), up.centerY() + 8f * s, centerPaint.apply { textSize = 28f * s })
        canvas.drawRoundRect(down, 12f * s, 12f * s, controlPaint)
        canvas.drawRoundRect(down, 12f * s, 12f * s, strokePaint)
        canvas.drawText("-", down.centerX(), down.centerY() + 6f * s, centerPaint.apply { textSize = 28f * s })
    }

    private fun drawPower(canvas: Canvas, width: Int, height: Int, s: Float, power: Float, isCharging: Boolean) {
        val rect = powerRect(width, height, s)
        canvas.drawRoundRect(rect, 14f * s, 14f * s, panelPaint)
        canvas.drawRoundRect(rect, 14f * s, 14f * s, strokePaint)
        val fillPaint = if (isCharging) yellowPaint else bluePaint
        val fill = RectF(rect.left + 7f * s, rect.top + 7f * s, rect.left + 7f * s + (rect.width() - 14f * s) * (power / 100f), rect.bottom - 7f * s)
        canvas.drawRoundRect(fill, 9f * s, 9f * s, fillPaint)
        smallPaint.textSize = 12f * s
        canvas.drawText("FORÇA ${power.toInt()}", rect.left + 10f * s, rect.top - 8f * s, smallPaint)

        val fire = fireCircle(width, height, s)
        val firePaint = if (isCharging) yellowPaint else controlPaint
        canvas.drawCircle(fire.x, fire.y, fire.r, firePaint)
        canvas.drawCircle(fire.x, fire.y, fire.r, strokePaint)
        centerPaint.textSize = 13f * s
        canvas.drawText(if (isCharging) "SOLTE" else "FOGO", fire.x, fire.y + 5f * s, centerPaint)
    }

    private fun uiScale(width: Int, height: Int): Float = (min(width, height) / 720f).coerceIn(0.72f, 1.10f)

    private fun cameraRect(s: Float) = RectF(16f * s, 110f * s, 82f * s, 154f * s)
    private fun aimRect(width: Int, height: Int, s: Float) = RectF(18f * s, 185f * s, 64f * s, height - 200f * s)
    private fun angleUpRect(width: Int, height: Int, s: Float) = RectF(18f * s, height - 180f * s, 64f * s, height - 130f * s)
    private fun angleDownRect(width: Int, height: Int, s: Float) = RectF(18f * s, height - 110f * s, 64f * s, height - 60f * s)
    private fun powerRect(width: Int, height: Int, s: Float) = RectF(width * 0.24f, height - 64f * s, width * 0.70f, height - 30f * s)

    private data class Circle(val x: Float, val y: Float, val r: Float)
    private fun fireCircle(width: Int, height: Int, s: Float) = Circle(width - 62f * s, height - 62f * s, 36f * s)
}
