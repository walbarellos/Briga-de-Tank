package com.tankbriga.engine

import kotlin.math.sqrt

/**
 * A 2D Vector with mutable fields for high-performance pooling and zero-allocation logic.
 */
data class Vector2(var x: Float, var y: Float) {
    operator fun plus(other: Vector2) = Vector2(x + other.x, y + other.y)
    operator fun minus(other: Vector2) = Vector2(x - other.x, y - other.y)
    operator fun times(scalar: Float) = Vector2(x * scalar, y * scalar)
    operator fun div(scalar: Float) = if (scalar != 0f) Vector2(x / scalar, y / scalar) else Vector2(0f, 0f)

    fun set(nx: Float, ny: Float): Vector2 {
        this.x = nx
        this.y = ny
        return this
    }

    fun length() = sqrt((x * x + y * y).toDouble()).toFloat()

    fun normalized(): Vector2 {
        val len = length()
        return if (len > 0) this / len else Vector2(0f, 0f)
    }

    fun dot(other: Vector2): Float = x * other.x + y * other.y

    fun reflect(normal: Vector2): Vector2 {
        val n = normal.normalized()
        val d = this
        return d - n * (2f * d.dot(n))
    }

    fun distanceTo(other: Vector2): Float = (other - this).length()

    companion object {
        val ZERO = Vector2(0f, 0f)
        val UP = Vector2(0f, -1f)
        val DOWN = Vector2(0f, 1f)
        val LEFT = Vector2(-1f, 0f)
        val RIGHT = Vector2(1f, 0f)
    }
}
