package com.tankbriga.engine

import java.util.BitSet

enum class Biome { LUSH_VALLEY, MARTIAN_DESERT, FROZEN_TUNDRA, VOLCANIC_CRAG }

/**
 * Represents the destructible game terrain using a BitSet for memory efficiency.
 */
class Terrain(val width: Int, val height: Int) {
    private val data = BitSet(width * height)
    private val craterCentersX = mutableListOf<Int>()
    private val craterCentersY = mutableListOf<Int>()
    private val craterRadii = mutableListOf<Int>()

    // Surface cache: surfaceCache[x] = first solid y in column x.
    // Eliminates O(height) scan in surfaceYAt — now O(1).
    val surfaceCache = IntArray(width) { height - 1 }
    private var surfaceCacheValid = false

    // Shared RNG — avoids new Random() per circleErode call (was GC pressure)
    private val erodeRng = java.util.Random(0L)
    
    var currentBiome: Biome = Biome.LUSH_VALLEY
        private set

    fun getCraterCentersX(): List<Int> = craterCentersX
    fun getCraterCentersY(): List<Int> = craterCentersY
    fun getCraterRadii(): List<Int> = craterRadii

    /**
     * Generates a themed terrain based on a seed and biome.
     */
    fun generate(seed: Long, biome: Biome = Biome.LUSH_VALLEY) {
        data.clear()
        craterCentersX.clear()
        craterCentersY.clear()
        craterRadii.clear()
        surfaceCacheValid = false
        currentBiome = biome
        val rng = java.util.Random(seed)
        
        // Calibrate based on biome
        val baseHeight = when(biome) {
            Biome.VOLCANIC_CRAG -> height * 0.55f
            else -> height * 0.65f
        }
        val hillIntensity = when(biome) {
            Biome.VOLCANIC_CRAG -> height * 0.18f // More aggressive
            Biome.MARTIAN_DESERT -> height * 0.08f // Flatter
            else -> height * 0.12f
        }
        
        val f1 = 0.002f + rng.nextFloat() * 0.003f
        val f2 = 0.008f + rng.nextFloat() * 0.005f
        val f3 = 0.025f + rng.nextFloat() * 0.015f

        for (x in 0 until width) {
            val h = (baseHeight +
                Math.sin(x.toDouble() * f1).toFloat() * hillIntensity +
                Math.sin(x.toDouble() * f2).toFloat() * (hillIntensity * 0.4f) +
                Math.sin(x.toDouble() * f3).toFloat() * (hillIntensity * 0.15f)).toInt()

            val clampedH = h.coerceIn((height * 0.3f).toInt(), height - 1)
            for (y in clampedH until height) setSolid(x, y, true)
            surfaceCache[x] = clampedH
        }
        surfaceCacheValid = true
    }

    // Inline bounds check — avoids IntRange allocation from `x !in 0 until width`
    fun isSolid(x: Int, y: Int): Boolean {
        if (x < 0 || x >= width || y < 0 || y >= height) return false
        return data.get(y * width + x)
    }

    fun setSolid(x: Int, y: Int, solid: Boolean) {
        if (x < 0 || x >= width || y < 0 || y >= height) return
        data.set(y * width + x, solid)
    }

    /** O(1) surface lookup using cache. Falls back to scan if cache invalid. */
    fun surfaceYAt(x: Int): Int {
        val sx = x.coerceIn(0, width - 1)
        if (surfaceCacheValid) return surfaceCache[sx]
        for (y in 0 until height) if (isSolid(sx, y)) return y
        return height - 1
    }

    /**
     * Returns a conservative support surface for a tank.
     */
    fun stableSurfaceYAt(centerX: Int, tankRadius: Float): Int {
        val samples = intArrayOf(
            centerX,
            (centerX - tankRadius * 0.55f).toInt(),
            (centerX + tankRadius * 0.55f).toInt()
        )
        return samples.map { surfaceYAt(it) }.maxOrNull() ?: height - 1
    }

    fun placeOnSurface(x: Float, verticalOffset: Float = 14f): Vector2 {
        val sx = x.coerceIn(0f, (width - 1).toFloat()).toInt()
        return Vector2(sx.toFloat(), stableSurfaceYAt(sx, verticalOffset).toFloat() - verticalOffset)
    }

    /** 
     * Erodes an area with organic noise (jagged edges) and returns pixels removed.
     * Use a local RNG seeded by position to maintain determinism.
     */
    fun circleErode(centerX: Int, centerY: Int, radius: Int): Int {
        craterCentersX.add(centerX)
        craterCentersY.add(centerY)
        craterRadii.add(radius)

        var removed = 0
        val r2 = (radius * radius).toFloat()
        erodeRng.setSeed((centerX xor centerY).toLong())  // deterministic but reuses instance

        for (dx in -radius - 5..radius + 5) {
            for (dy in -radius - 5..radius + 5) {
                val distSq = (dx * dx + dy * dy).toFloat()
                val noise = if (distSq > r2 * 0.65f) erodeRng.nextFloat() * (radius * 3.5f) else 0f
                if (distSq <= r2 + noise) {
                    val x = centerX + dx
                    val y = centerY + dy
                    if (isSolid(x, y)) {
                        setSolid(x, y, false)
                        removed++
                        // Update surface cache for this column
                        if (surfaceCacheValid && x in 0 until width) {
                            if (y <= surfaceCache[x]) {
                                // Surface was removed — rescan this column from y down
                                var newSurface = height - 1
                                for (sy in y until height) {
                                    if (isSolid(x, sy)) { newSurface = sy; break }
                                }
                                surfaceCache[x] = newSurface
                            }
                        }
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
