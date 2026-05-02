package com.tankbriga.app.render

import android.graphics.Canvas
import android.graphics.RectF
import com.tankbriga.engine.Vector2
import kotlin.math.max
import kotlin.math.min

/** Camera modes for a mobile artillery game. */
enum class CameraMode { FOCUS, FREE, GENERAL, PROJECTILE }

/**
 * Manages viewport transform with clamped pan/zoom and reduced lag.
 * GENERAL is for reading the whole map; FREE is for manual drag; FOCUS tracks the active tank.
 */
class CameraController(private val screenWidth: Int, private val screenHeight: Int) {
    var posX = 0f
    var posY = 0f
    var zoom = 1.0f

    var targetX = 0f
    var targetY = 0f
    var targetZoom = 1.0f

    var mode = CameraMode.GENERAL

    private var worldWidth = screenWidth.toFloat()
    private var worldHeight = screenHeight.toFloat()
    private var shakeIntensity = 0f
    private var shakeDuration = 0

    fun setWorldBounds(width: Int, height: Int) {
        worldWidth = width.toFloat()
        worldHeight = height.toFloat()
        clampTarget()
    }

    fun update() {
        val lerpFactor = when (mode) {
            CameraMode.FREE -> 0.55f
            CameraMode.PROJECTILE -> 0.58f
            CameraMode.FOCUS -> 0.30f
            CameraMode.GENERAL -> 0.24f
        }
        clampTarget()
        posX += (targetX - posX) * lerpFactor
        posY += (targetY - posY) * lerpFactor
        zoom += (targetZoom - zoom) * lerpFactor

        if (shakeDuration > 0) shakeDuration-- else shakeIntensity = 0f
    }

    fun applyTransform(canvas: Canvas) {
        canvas.save()
        if (shakeIntensity > 0) {
            val sx = (Math.random().toFloat() - 0.5f) * shakeIntensity
            val sy = (Math.random().toFloat() - 0.5f) * shakeIntensity
            canvas.translate(sx, sy)
        }
        canvas.scale(zoom, zoom, screenWidth / 2f, screenHeight / 2f)
        canvas.translate(-posX + screenWidth / 2f, -posY + screenHeight / 2f)
    }

    fun restoreTransform(canvas: Canvas) = canvas.restore()

    fun focusOn(point: Vector2, requestedZoom: Float = 1.35f, immediate: Boolean = false) {
        targetX = point.x
        targetY = point.y
        targetZoom = requestedZoom.coerceIn(0.28f, 2.2f)
        clampTarget()
        if (immediate) jumpToTarget()
    }

    fun panByScreenDelta(dx: Float, dy: Float) {
        targetX += dx / zoom.coerceAtLeast(0.1f)
        targetY += dy / zoom.coerceAtLeast(0.1f)
        clampTarget()
        jumpToTarget()
    }

    fun fitPoints(points: List<Vector2>, padding: Float = 180f, immediate: Boolean = false) {
        if (points.isEmpty()) return
        var minX = points[0].x
        var maxX = points[0].x
        var minY = points[0].y
        var maxY = points[0].y
        points.forEach {
            minX = min(minX, it.x)
            maxX = max(maxX, it.x)
            minY = min(minY, it.y)
            maxY = max(maxY, it.y)
        }
        targetX = (minX + maxX) / 2f
        targetY = (minY + maxY) / 2f
        val widthNeeded = (maxX - minX) + padding * 2
        val heightNeeded = (maxY - minY) + padding * 2
        val zoomX = screenWidth / widthNeeded.coerceAtLeast(1f)
        val zoomY = screenHeight / heightNeeded.coerceAtLeast(1f)
        targetZoom = min(zoomX, zoomY).coerceIn(0.28f, 1.6f)
        clampTarget()
        if (immediate) jumpToTarget()
    }

    fun fitWorld(immediate: Boolean = false) {
        targetX = worldWidth / 2f
        targetY = worldHeight / 2f
        val zoomX = screenWidth / worldWidth.coerceAtLeast(1f)
        val zoomY = screenHeight / worldHeight.coerceAtLeast(1f)
        targetZoom = min(zoomX, zoomY).coerceIn(0.25f, 1.2f)
        clampTarget()
        if (immediate) jumpToTarget()
    }

    fun visibleWorldRect(): RectF {
        val halfW = screenWidth / (2f * zoom.coerceAtLeast(0.1f))
        val halfH = screenHeight / (2f * zoom.coerceAtLeast(0.1f))
        return RectF(posX - halfW, posY - halfH, posX + halfW, posY + halfH)
    }

    fun triggerShake(intensity: Float, duration: Int) {
        shakeIntensity = intensity
        shakeDuration = duration
    }

    fun zoomByFactor(factor: Float) {
        targetZoom = (targetZoom * factor).coerceIn(0.25f, 2.5f)
    }

    private fun jumpToTarget() {
        posX = targetX
        posY = targetY
        zoom = targetZoom
    }

    private fun clampTarget() {
        val halfW = screenWidth / (2f * targetZoom.coerceAtLeast(0.1f))
        val halfH = screenHeight / (2f * targetZoom.coerceAtLeast(0.1f))
        targetX = if (worldWidth <= halfW * 2f) worldWidth / 2f else targetX.coerceIn(halfW, worldWidth - halfW)
        targetY = if (worldHeight <= halfH * 2f) worldHeight / 2f else targetY.coerceIn(halfH, worldHeight - halfH)
    }
}
