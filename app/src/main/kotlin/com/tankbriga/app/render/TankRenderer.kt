package com.tankbriga.app.render

import android.graphics.*
import com.tankbriga.engine.Tank
import kotlin.math.cos
import kotlin.math.sin

/** Renders individual tanks with readable mobile scale and clear human/current markers. */
class TankRenderer {
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

    fun draw(
        canvas: Canvas,
        tank: Tank,
        color: Int,
        isCurrentTurn: Boolean,
        aimAngle: Float = 45f,
        isHuman: Boolean = !tank.isBot
    ) {
        bodyPaint.setColor(color)
        val x = tank.position.x
        val y = tank.position.y

        if (isHuman) canvas.drawCircle(x, y - 4f, 28f, humanAura)
        if (isCurrentTurn) canvas.drawCircle(x, y - 4f, 34f, turnAura)

        val body = RectF(x - 16f, y - 10f, x + 16f, y + 8f)
        canvas.drawRoundRect(body, 6f, 6f, bodyPaint)
        canvas.drawCircle(x - 8f, y + 8f, 5f, bodyPaint)
        canvas.drawCircle(x + 8f, y + 8f, 5f, bodyPaint)

        val angle = if (isCurrentTurn) aimAngle else 35f
        val rad = Math.toRadians(angle.toDouble()).toFloat()
        val barrelLen = if (isCurrentTurn) 31f else 20f
        canvas.drawLine(x, y - 8f, x + cos(rad) * barrelLen, y - 8f - sin(rad) * barrelLen, barrelPaint)

        val hpWidth = 46f
        val hpTop = y - 45f
        canvas.drawRoundRect(RectF(x - hpWidth / 2f, hpTop, x + hpWidth / 2f, hpTop + 8f), 4f, 4f, hpBg)
        canvas.drawRoundRect(RectF(x - hpWidth / 2f, hpTop, x - hpWidth / 2f + hpWidth * (tank.hp / 100f), hpTop + 8f), 4f, 4f, hpFill)

        val label = if (isHuman) "VOCÊ" else tank.name
        val labelWidth = textPaint.measureText(label) + 14f
        val tag = RectF(x - labelWidth / 2f, y - 75f, x + labelWidth / 2f, y - 53f)
        canvas.drawRoundRect(tag, 8f, 8f, tagPaint)
        
        textPaint.setColor(if (isHuman) Color.rgb(120, 215, 255) else Color.WHITE)
        canvas.drawText(label, x, y - 58f, textPaint)
        textPaint.setColor(Color.WHITE)

        if (isCurrentTurn) {
            val pointerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { setColor(Color.YELLOW) }
            val path = Path().apply {
                moveTo(x, y - 98f)
                lineTo(x - 10f, y - 80f)
                lineTo(x + 10f, y - 80f)
                close()
            }
            canvas.drawPath(path, pointerPaint)
        }
    }
}
