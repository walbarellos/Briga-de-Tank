package com.tankbriga.app.render

import android.graphics.*
import com.tankbriga.engine.Terrain

/**
 * Optimized terrain renderer backed by a bitmap.
 * Features a dual-layer look (Grass on top, Soil below) with partial redraws.
 */
class TerrainRenderer(private val terrain: Terrain) {
    private var terrainBitmap: Bitmap? = null
    private var terrainCanvas: Canvas? = null
    
    private val grassPaint = Paint().apply {
        setColor(Color.parseColor("#4CAF50"))
        style = Paint.Style.FILL
    }
    private val soilPaint = Paint().apply {
        setColor(Color.parseColor("#795548"))
        style = Paint.Style.FILL
    }
    private val dirtShadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        setColor(Color.argb(95, 14, 10, 5))
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val craterMarks = mutableListOf<CraterMark>()

    private data class CraterMark(val x: Float, val y: Float, val radius: Float, var life: Int)

    fun initialize() {
        terrainBitmap = Bitmap.createBitmap(terrain.width, terrain.height, Bitmap.Config.ARGB_8888)
        terrainCanvas = Canvas(terrainBitmap!!)
        redrawFull()
    }

    /**
     * Optimized sync: only redraws the columns affected by an explosion.
     * If no parameters are provided, it performs a full redraw.
     */
    fun syncFromTerrain(centerX: Int? = null, centerY: Int? = null, radius: Int? = null) {
        if (centerX == null || radius == null) {
            redrawFull()
            return
        }

        // Partial redraw: only columns in the [centerX - radius, centerX + radius] range
        val canvas = terrainCanvas ?: return
        val startX = (centerX - radius - 5).coerceIn(0, terrain.width - 1)
        val endX = (centerX + radius + 5).coerceIn(0, terrain.width - 1)
        
        // Clear affected column area
        val clearPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        canvas.drawRect(startX.toFloat(), 0f, endX + 1f, terrain.height.toFloat(), clearPaint)

        for (x in startX..endX) {
            var colStartY: Int? = null
            for (y in 0 until terrain.height) {
                val solid = terrain.isSolid(x, y)
                if (solid && colStartY == null) {
                    colStartY = y
                } else if (!solid && colStartY != null) {
                    drawColumn(canvas, x, colStartY, y)
                    colStartY = null
                }
            }
            if (colStartY != null) drawColumn(canvas, x, colStartY, terrain.height)
        }

        if (centerY != null) {
            craterMarks += CraterMark(centerX.toFloat(), centerY.toFloat(), radius.toFloat(), 90)
            while (craterMarks.size > 8) craterMarks.removeAt(0)
        }
    }

    fun onErode(centerX: Int, centerY: Int, radius: Int) = syncFromTerrain(centerX, centerY, radius)

    private fun redrawFull() {
        val canvas = terrainCanvas ?: return
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        for (x in 0 until terrain.width) {
            var startY: Int? = null
            for (y in 0 until terrain.height) {
                val solid = terrain.isSolid(x, y)
                if (solid && startY == null) {
                    startY = y
                } else if (!solid && startY != null) {
                    drawColumn(canvas, x, startY, y)
                    startY = null
                }
            }
            if (startY != null) drawColumn(canvas, x, startY, terrain.height)
        }
    }

    private fun drawColumn(canvas: Canvas, x: Int, startY: Int, endY: Int) {
        val fx = x.toFloat()
        // Draw Grass (top 8 pixels of a column)
        val grassHeight = 8
        val grassEnd = (startY + grassHeight).coerceAtMost(endY)
        canvas.drawRect(fx, startY.toFloat(), fx + 1f, grassEnd.toFloat(), grassPaint)
        
        // Draw Soil (the rest)
        if (grassEnd < endY) {
            canvas.drawRect(fx, grassEnd.toFloat(), fx + 1f, endY.toFloat(), soilPaint)
        }
    }

    fun draw(canvas: Canvas) {
        terrainBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        for (mark in craterMarks) {
            val alpha = (mark.life / 90f * 120f).toInt().coerceIn(0, 120)
            dirtShadePaint.setAlpha(alpha)
            canvas.drawCircle(mark.x, mark.y, mark.radius, dirtShadePaint)
            mark.life--
        }
        craterMarks.removeAll { it.life <= 0 }
    }
}
