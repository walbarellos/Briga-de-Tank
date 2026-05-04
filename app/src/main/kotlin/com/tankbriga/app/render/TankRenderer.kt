package com.tankbriga.app.render

import android.content.Context
import android.graphics.*
import com.tankbriga.engine.Tank
import kotlin.math.cos
import kotlin.math.sin

/** Renders individual tanks with sprites or procedural fallback. */
class TankRenderer(context: Context? = null) {
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val barrelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        setColor(Color.WHITE)
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        setColor(Color.WHITE)
        textSize = 22f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val tagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        setColor(Color.argb(210, 0, 0, 0))
        style = Paint.Style.FILL
    }
    private val hpBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { setColor(Color.argb(170, 0, 0, 0)) }
    private val hpFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { setColor(Color.rgb(70, 220, 100)) }
    private val humanAura = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        setColor(Color.argb(120, 80, 190, 255))
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val turnAura = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        setColor(Color.YELLOW)
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private var bodyBitmap: Bitmap? = null
    private var barrelBitmap: Bitmap? = null

    init {
        // Placeholder for future sprite loading
        // context?.let {
        //    bodyBitmap = BitmapFactory.decodeResource(it.resources, R.drawable.tank_body)
        //    barrelBitmap = BitmapFactory.decodeResource(it.resources, R.drawable.tank_barrel)
        // }
    }

    fun draw(
        canvas: Canvas,
        tank: Tank,
        color: Int,
        isCurrentTurn: Boolean,
        aimAngle: Float = 45f,
        isHuman: Boolean = !tank.isBot
    ) {
        val x = tank.position.x
        
        // Fix 4 — Idle animation do tank
        val isIdle = !isCurrentTurn && tank.hp > 0
        val idleOffset = if (isIdle) (sin(System.currentTimeMillis() / 200.0) * 1.5).toFloat() else 0f
        val y = tank.position.y + idleOffset

        // Markers
        if (isHuman) canvas.drawCircle(x, y - 4f, 28f, humanAura)
        if (isCurrentTurn) canvas.drawCircle(x, y - 4f, 34f, turnAura)

        // Draw Tank Body
        bodyBitmap?.let { bmp ->
            // Drawing sprite logic (skipped for now as bitmaps are null)
        } ?: run {
            // Procedural Fallback - Scaled up to match 18f radius
            bodyPaint.setColor(color)
            val body = RectF(x - 24f, y - 12f, x + 24f, y + 12f)
            canvas.drawRoundRect(body, 8f, 8f, bodyPaint)
            
            // Draw Tread details
            bodyPaint.color = Color.BLACK
            bodyPaint.alpha = 100
            canvas.drawRect(x - 24f, y + 8f, x + 24f, y + 13f, bodyPaint)
            canvas.drawRect(x - 24f, y - 13f, x + 24f, y - 8f, bodyPaint)
            bodyPaint.alpha = 255
        }

        // Draw Barrel
        val angle = if (isCurrentTurn) aimAngle else 35f
        val rad = Math.toRadians(angle.toDouble()).toFloat()
        val barrelLen = if (isCurrentTurn) 40f else 28f
        val bx = x
        val by = y - 8f
        
        barrelBitmap?.let { bmp ->
           // Drawing sprite logic
        } ?: run {
            barrelPaint.strokeWidth = 8f
            canvas.drawLine(bx, by, bx + cos(rad) * barrelLen, by - sin(rad) * barrelLen, barrelPaint)
            // Barrel tip
            barrelPaint.style = Paint.Style.FILL
            canvas.drawCircle(bx + cos(rad) * barrelLen, by - sin(rad) * barrelLen, 5f, barrelPaint)
            barrelPaint.style = Paint.Style.STROKE
        }

        // HP Bar
        val hpWidth = 60f
        val hpTop = y - 55f
        canvas.drawRoundRect(RectF(x - hpWidth / 2f, hpTop, x + hpWidth / 2f, hpTop + 8f), 4f, 4f, hpBg)
        canvas.drawRoundRect(RectF(x - hpWidth / 2f, hpTop, x - hpWidth / 2f + hpWidth * (tank.hp / 100f), hpTop + 8f), 4f, 4f, hpFill)

        // Label/Name
        val label = if (isHuman) "VOCÊ" else tank.name
        val labelWidth = textPaint.measureText(label) + 16f
        val tag = RectF(x - labelWidth / 2f, y - 80f, x + labelWidth / 2f, y - 56f)
        canvas.drawRoundRect(tag, 10f, 10f, tagPaint)
        
        textPaint.setColor(if (isHuman) Color.rgb(120, 215, 255) else Color.WHITE)
        canvas.drawText(label, x, y - 61f, textPaint)
        textPaint.setColor(Color.WHITE)

        // Turn Pointer
        if (isCurrentTurn) {
            val pointerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { setColor(Color.YELLOW) }
            val path = Path().apply {
                moveTo(x, y - 105f)
                lineTo(x - 12f, y - 85f)
                lineTo(x + 12f, y - 85f)
                close()
            }
            canvas.drawPath(path, pointerPaint)
        }
    }
}
