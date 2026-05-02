package com.tankbriga.engine

import java.util.BitSet

/**
 * Represents the destructible game terrain using a BitSet for memory efficiency.
 * Solid pixels are 'true', void pixels are 'false'.
 */
class Terrain(val width: Int, val height: Int) {
    private val data = BitSet(width * height)
    private val craterCentersX = mutableListOf<Int>()
    private val craterCentersY = mutableListOf<Int>()
    private val craterRadii = mutableListOf<Int>()

    fun getCraterCentersX(): List<Int> = craterCentersX
    fun getCraterCentersY(): List<Int> = craterCentersY
    fun getCraterRadii(): List<Int> = craterRadii

    /**
     * Generates a random terrain based on a seed.
     * Uses a simple layered sine approach for deterministic 1D heightmap.
     */
    fun generate(seed: Long) {
        data.clear()
        val rng = java.util.Random(seed)
        val baseHeight = height * 0.6f
        val variance = height * 0.2f
        val f1 = 0.01f + rng.nextFloat() * 0.02f
        val f2 = 0.03f + rng.nextFloat() * 0.04f

        for (x in 0 until width) {
            val h = (baseHeight +
                Math.sin(x.toDouble() * f1).toFloat() * variance +
                Math.sin(x.toDouble() * f2).toFloat() * (variance * 0.3f)).toInt()

            val clampedH = h.coerceIn(0, height - 1)
            for (y in clampedH until height) setSolid(x, y, true)
        }
    }

    fun isSolid(x: Int, y: Int): Boolean {
        if (x !in 0 until width || y !in 0 until height) return false
        return data.get(y * width + x)
    }

    fun setSolid(x: Int, y: Int, solid: Boolean) {
        if (x in 0 until width && y in 0 until height) data.set(y * width + x, solid)
    }

    /** Returns the first solid pixel in a column. Used to place tanks on the terrain surface. */
    fun surfaceYAt(x: Int): Int {
        val sx = x.coerceIn(0, width - 1)
        for (y in 0 until height) if (isSolid(sx, y)) return y
        return height - 1
    }

    /**
     * Returns a conservative support surface for a tank.
     * We sample the center and two side columns. If a crater opens below the center,
     * the tank drops to the lowest available supporting point, Gunbound-style.
     */
    fun stableSurfaceYAt(centerX: Float, tankRadius: Float): Int {
        val samples = intArrayOf(
            centerX.toInt(),
            (centerX - tankRadius * 0.55f).toInt(),
            (centerX + tankRadius * 0.55f).toInt()
        )
        return samples.map { surfaceYAt(it) }.maxOrNull() ?: height - 1
    }

    fun placeOnSurface(x: Float, verticalOffset: Float = 14f): Vector2 {
        val sx = x.coerceIn(0f, (width - 1).toFloat())
        return Vector2(sx, stableSurfaceYAt(sx, verticalOffset).toFloat() - verticalOffset)
    }

    /** Erodes a circular area of the terrain and returns how many solid pixels were removed. */
    fun circleErode(centerX: Int, centerY: Int, radius: Int): Int {
        craterCentersX.add(centerX)
        craterCentersY.add(centerY)
        craterRadii.add(radius)
        var removed = 0
        val r2 = radius * radius
        for (dx in -radius..radius) {
            for (dy in -radius..radius) {
                if (dx * dx + dy * dy <= r2) {
                    val x = centerX + dx
                    val y = centerY + dy
                    if (isSolid(x, y)) {
                        setSolid(x, y, false)
                        removed++
                    }
                }
            }
        }
        return removed
    }

    /** Shrinks the playable terrain area from the edges. */
    fun shrinkTerrain(amount: Int) {
        for (y in 0 until height) {
            for (x in 0 until amount) {
                setSolid(x, y, false)
                setSolid(width - 1 - x, y, false)
            }
        }
    }

    fun getStateHash(): Int = data.hashCode()
    fun countSolidPixels(): Int = data.cardinality()
}
