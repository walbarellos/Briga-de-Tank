package com.tankbriga.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Gunbound-like wind model.
 *
 * directionDegrees follows the visual wind clock used by artillery games:
 * 0°   = wind blowing to the right
 * 90°  = wind blowing upward
 * 180° = wind blowing to the left
 * 270° = wind blowing downward
 */
data class WindState(
    var speed: Float,
    var directionDegrees: Float
) {
    fun setWind(v: Float) {
        speed = kotlin.math.abs(v)
        directionDegrees = if (v >= 0) 0f else 180f
    }
    private val radians: Double
        get() = directionDegrees.toDouble() * PI / 180.0

    fun xComponent(): Float = (cos(radians) * speed).toFloat()

    /** Android world coordinates grow downward, so upward wind is negative Y. */
    fun yComponent(): Float = (-sin(radians) * speed).toFloat()

    fun horizontalComponent(): Float = xComponent()

    fun cacheKey(): Int = speed.times(10f).roundToInt() * 1000 + directionDegrees.roundToInt()

    fun arrow(): String = when (((directionDegrees.roundToInt() % 360) + 360) % 360) {
        in 338..359, in 0..22 -> "→"
        in 23..67 -> "↗"
        in 68..112 -> "↑"
        in 113..157 -> "↖"
        in 158..202 -> "←"
        in 203..247 -> "↙"
        in 248..292 -> "↓"
        else -> "↘"
    }

    companion object {
        fun zero() = WindState(0f, 0f)
    }
}

/**
 * Reference assistant extracted from classic Gunbound teaching charts.
 * It does not aim for the player; it gives stable, readable hints for mobile UX.
 */
object GunboundAimReference {
    data class FixedAnglePower(
        val screenDistance: Float,
        val angle20: Float,
        val angle30: Float,
        val angle40: Float,
        val angle70: Float
    )

    /**
     * Wind-0 basic power table read from the supplied reference image.
     * screenDistance is an approximate "screen fraction" distance marker.
     */
    val windZeroTable = listOf(
        FixedAnglePower(0.25f, 0.9f, 0.7f, 0.6f, 0.9f),
        FixedAnglePower(0.40f, 1.3f, 1.1f, 1.0f, 1.3f),
        FixedAnglePower(0.55f, 1.5f, 1.3f, 1.2f, 1.6f),
        FixedAnglePower(0.70f, 1.8f, 1.6f, 1.5f, 2.0f),
        FixedAnglePower(0.85f, 2.0f, 1.8f, 1.7f, 2.2f),
        FixedAnglePower(1.00f, 2.2f, 2.0f, 1.9f, 2.4f),
        FixedAnglePower(1.15f, 2.3f, 2.1f, 2.0f, 2.5f),
        FixedAnglePower(1.30f, 2.4f, 2.2f, 2.1f, 2.7f),
        FixedAnglePower(1.45f, 2.5f, 2.3f, 2.2f, 2.8f)
    )

    /** Full-power angle bands extracted from the circular reference image. */
    fun fullPowerForAngle(angleDegrees: Float): Float = when (angleDegrees) {
        in 85f..89f -> 2.85f
        in 81f..85f -> 2.90f
        in 76f..80f -> 2.95f
        in 71f..75f -> 3.00f
        in 68f..71f -> 3.05f
        in 64f..68f -> 3.10f
        in 60f..64f -> 3.20f
        in 55f..60f -> 3.25f
        in 50f..55f -> 3.30f
        else -> 0f
    }

    /**
     * Mobile-friendly wind correction coefficient inspired by the circular chart.
     * Positive means the player should add a little power/angle against the wind;
     * negative means reduce. The value is intentionally small to teach gradually.
     */
    fun windCorrection(wind: WindState, shotAngleDegrees: Float): Float {
        if (wind.speed <= 0.05f) return 0f
        val shotRad = shotAngleDegrees * PI.toFloat() / 180f
        val shotX = cos(shotRad)
        val shotY = -sin(shotRad)
        val headwind = -(wind.xComponent() * shotX + wind.yComponent() * shotY)
        return (headwind / 10f).coerceIn(-1f, 1f) * 0.55f
    }

    fun nearestWindZeroPower(distanceFraction: Float, angle: Int): Float {
        val row = windZeroTable.minBy { kotlin.math.abs(it.screenDistance - distanceFraction) }
        return when (angle) {
            20 -> row.angle20
            30 -> row.angle30
            40 -> row.angle40
            70 -> row.angle70
            else -> row.angle40
        }
    }
}
