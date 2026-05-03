package com.tankbriga.app.render

import android.graphics.*
import com.tankbriga.engine.Biome
import com.tankbriga.engine.Terrain

/**
 * Optimized terrain renderer backed by a bitmap.
 * Supports dynamic themes based on Biomes.
 */
class TerrainRenderer(private val terrain: Terrain) {
    private var terrainBitmap: Bitmap? = null
    private var terrainCanvas: Canvas? = null
    
    private val grassPaint = Paint().apply { style = Paint.Style.FILL }
    private val soilPaint = Paint().apply { style = Paint.Style.FILL }
    private val rockPaint = Paint().apply { style = Paint.Style.FILL }
    
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
        updateColors()
        redrawFull()
    }

    private fun updateColors() {
        when(terrain.currentBiome) {
            Biome.LUSH_VALLEY -> {
                grassPaint.color = Color.parseColor("#7CB342") // Lush Green
                soilPaint.color = Color.parseColor("#5D4037")  // Earth Brown
                rockPaint.color = Color.parseColor("#455A64")  // Slate Rock
            }
            Biome.MARTIAN_DESERT -> {
                grassPaint.color = Color.parseColor("#D84315") // Deep Red Dust
                soilPaint.color = Color.parseColor("#BF360C")  // Martian Soil
                rockPaint.color = Color.parseColor("#3E2723")  // Dark Volcanic Rock
            }
            Biome.FROZEN_TUNDRA -> {
                grassPaint.color = Color.parseColor("#E0F7FA") // Ice/Snow
                soilPaint.color = Color.parseColor("#80DEEA")  // Blue Frost
                rockPaint.color = Color.parseColor("#006064")  // Deep Frozen Stone
            }
            Biome.VOLCANIC_CRAG -> {
                grassPaint.color = Color.parseColor("#212121") // Ash/Obsidian
                soilPaint.color = Color.parseColor("#4E342E")  // Burnt Earth
                rockPaint.color = Color.parseColor("#BF360C")  // Molten deep rock
            }
        }
    }

    fun syncFromTerrain(centerX: Int? = null, centerY: Int? = null, radius: Int? = null) {
        if (centerX == null || radius == null) {
            updateColors()
            redrawFull()
            return
        }

        val canvas = terrainCanvas ?: return
        val startX = (centerX - radius - 8).coerceIn(0, terrain.width - 1)
        val endX = (centerX + radius + 8).coerceIn(0, terrain.width - 1)
        
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
            while (craterMarks.size > 12) craterMarks.removeAt(0)
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
        val grassHeight = 12
        val soilHeight = 45
        
        val grassEnd = (startY + grassHeight).coerceAtMost(endY)
        val soilEnd = (startY + soilHeight).coerceAtMost(endY)
        
        canvas.drawRect(fx, startY.toFloat(), fx + 1f, grassEnd.toFloat(), grassPaint)
        if (grassEnd < soilEnd) {
            canvas.drawRect(fx, grassEnd.toFloat(), fx + 1f, soilEnd.toFloat(), soilPaint)
        }
        if (soilEnd < endY) {
            canvas.drawRect(fx, soilEnd.toFloat(), fx + 1f, endY.toFloat(), rockPaint)
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
