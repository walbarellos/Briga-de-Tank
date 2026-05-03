package com.tankbriga.app.render

import android.graphics.Canvas
import android.graphics.RectF
import com.tankbriga.engine.Vector2
import kotlin.math.max
import kotlin.math.min

/** 
 * New Camera Modes:
 * - FOLLOW: Auto-focus on current player and tracks projectile.
 * - OVERVIEW: Wide view showing all active tanks.
 * - FREE: Manual control (Pinch-to-zoom + Pan). Does NOT follow the shot.
 */
enum class CameraMode { FOLLOW, OVERVIEW, FREE }

/**
 * Manages viewport transform with clamped pan/zoom.
 */
class CameraController(private val screenWidth: Int, private val screenHeight: Int) {
    var posX = 0f
    var posY = 0f
    var zoom = 1.0f

    var targetX = 0f
    var targetY = 0f
    var targetZoom = 1.0f

    var mode = CameraMode.FOLLOW

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
            CameraMode.FREE -> 0.45f
            CameraMode.FOLLOW -> 0.35f
            CameraMode.OVERVIEW -> 0.20f
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

    /** 
     * Focus on a point. In FREE mode, this is ignored unless 'force' is true 
     * (e.g., when the player explicitly snaps the camera or it's their turn start).
     */
    fun focusOn(point: Vector2, requestedZoom: Float = 1.35f, immediate: Boolean = false, force: Boolean = false) {
        if (mode == CameraMode.FREE && !force) return
        
        targetX = point.x
        targetY = point.y
        // Expanded range: 0.15f allows seeing much more of the 2400px world
        targetZoom = requestedZoom.coerceIn(0.15f, 2.5f)
        clampTarget()
        if (immediate) jumpToTarget()
    }

    fun panByScreenDelta(dx: Float, dy: Float) {
        if (mode != CameraMode.FREE) return // Manual pan only in FREE mode
        
        targetX += dx / zoom.coerceAtLeast(0.05f)
        targetY += dy / zoom.coerceAtLeast(0.05f)
        clampTarget()
        jumpToTarget()
    }

    fun fitPoints(points: List<Vector2>, padding: Float = 180f, immediate: Boolean = false, force: Boolean = false) {
        if (mode == CameraMode.FREE && !force) return
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
        // Allow zooming out enough to see all points
        targetZoom = min(zoomX, zoomY).coerceIn(0.15f, 1.8f)
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

    /** 
     * Handles Pinch-to-zoom. Changes mode to FREE to prevent fighting with auto-focus.
     */
    fun zoomByFactor(factor: Float) {
        mode = CameraMode.FREE
        // Minimum zoom of 0.15f allows seeing roughly 3000px width on a 720p screen
        targetZoom = (targetZoom * factor).coerceIn(0.15f, 2.5f)
    }

    private fun jumpToTarget() {
        posX = targetX
        posY = targetY
        zoom = targetZoom
    }

    private fun clampTarget() {
        val halfW = screenWidth / (2f * targetZoom.coerceAtLeast(0.01f))
        val halfH = screenHeight / (2f * targetZoom.coerceAtLeast(0.01f))
        
        // Horizontal clamping: if zoomed out enough to see more than the world, center it
        if (worldWidth <= halfW * 2f) {
            targetX = worldWidth / 2f
        } else {
            targetX = targetX.coerceIn(halfW, worldWidth - halfW)
        }
        
        // Vertical clamping
        if (worldHeight <= halfH * 2f) {
            targetY = worldHeight / 2f
        } else {
            targetY = targetY.coerceIn(halfH, worldHeight - halfH)
        }
    }
}
