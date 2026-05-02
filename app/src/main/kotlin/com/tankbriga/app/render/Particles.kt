package com.tankbriga.app.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

data class Particle(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    var life: Int,
    var maxLife: Int,
    var color: Int,
    var size: Float,
    var gravity: Float = 0.2f
)

/**
 * High-performance, zero-allocation particle system.
 * Features varied explosion patterns optimized for visibility against space.
 */
class ParticleSystem(private val maxParticles: Int = 400) {
    private val particles = Array(maxParticles) { Particle(0f, 0f, 0f, 0f, 0, 0, 0, 0f) }
    private val paint = Paint().apply { style = Paint.Style.FILL }
    private var lastEmittedIndex = 0
    private val rng = Random(System.currentTimeMillis())

    private val neonColors = intArrayOf(
        Color.rgb(255, 255, 50),  // Neon Yellow
        Color.rgb(255, 100, 30),  // Bright Orange
        Color.rgb(50, 200, 255),  // Electric Blue
        Color.rgb(255, 255, 255), // Pure White
        Color.rgb(255, 50, 200)   // Hot Pink
    )

    fun emit(x: Float, y: Float, vx: Float, vy: Float, life: Int, color: Int, size: Float, gravity: Float = 0.2f) {
        for (i in 0 until maxParticles) {
            val idx = (lastEmittedIndex + i) % maxParticles
            if (particles[idx].life <= 0) {
                val p = particles[idx]
                p.x = x
                p.y = y
                p.vx = vx
                p.vy = vy
                p.life = life
                p.maxLife = life
                p.color = color
                p.size = size
                p.gravity = gravity
                lastEmittedIndex = (idx + 1) % maxParticles
                return
            }
        }
    }

    /** Triggers a random explosion pattern based on impact radius and type. */
    fun emitExplosion(x: Float, y: Float, radius: Int) {
        val pattern = rng.nextInt(3)
        when (pattern) {
            0 -> emitClassicBurst(x, y, radius)
            1 -> emitShockwave(x, y, radius)
            2 -> emitDebrisShower(x, y, radius)
        }
    }

    private fun emitClassicBurst(x: Float, y: Float, radius: Int) {
        val count = (radius * 1.5f).toInt().coerceIn(30, 100)
        repeat(count) { i ->
            val angle = (Math.PI * 2.0 * i / count).toFloat()
            val speed = 2.0f + rng.nextFloat() * 6.0f
            val color = neonColors[rng.nextInt(neonColors.size)]
            emit(x, y, cos(angle) * speed, sin(angle) * speed - 1.5f, 20 + rng.nextInt(25), color, 2.0f + rng.nextFloat() * 4f)
        }
    }

    private fun emitShockwave(x: Float, y: Float, radius: Int) {
        val count = 40
        repeat(count) { i ->
            val angle = (Math.PI * 2.0 * i / count).toFloat()
            val speed = 8.0f // Constant fast speed for shockwave look
            val color = Color.WHITE
            emit(x, y, cos(angle) * speed, sin(angle) * speed, 12, color, 3f, gravity = 0f)
        }
        // Small center embers
        repeat(20) {
            emit(x, y, (rng.nextFloat() - 0.5f) * 4f, (rng.nextFloat() - 0.5f) * 4f, 40, Color.YELLOW, 2f)
        }
    }

    private fun emitDebrisShower(x: Float, y: Float, radius: Int) {
        repeat(50) {
            val vx = (rng.nextFloat() - 0.5f) * 12f
            val vy = -(rng.nextFloat() * 14f) // Shoot upwards
            val color = if (rng.nextBoolean()) Color.rgb(150, 100, 50) else Color.rgb(200, 200, 180)
            emit(x, y, vx, vy, 40 + rng.nextInt(30), color, 4f + rng.nextFloat() * 3f, gravity = 0.4f)
        }
    }

    fun update() {
        for (p in particles) {
            if (p.life > 0) {
                p.x += p.vx
                p.y += p.vy
                p.vy += p.gravity
                p.life--
            }
        }
    }

    fun draw(canvas: Canvas) {
        for (p in particles) {
            if (p.life > 0) {
                val alpha = (p.life.toFloat() / p.maxLife.toFloat() * 255).toInt()
                paint.setColor(p.color)
                paint.setAlpha(alpha)
                canvas.drawRect(p.x - p.size, p.y - p.size, p.x + p.size, p.y + p.size, paint)
            }
        }
        paint.setAlpha(255)
    }
}
