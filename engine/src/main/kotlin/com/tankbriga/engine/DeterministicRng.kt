package com.tankbriga.engine

import kotlin.math.absoluteValue

/**
 * Deterministic Random Number Generator using a Linear Congruential Generator (LCG).
 * Ensures identical results across all platforms (JVM/Android) given the same seed.
 */
object DeterministicRng {
    private var seed: Long = 0

    /**
     * Initializes the seed based on the lobby word and turn sequence.
     */
    fun init(lobbyWord: String, turnNumber: Int) {
        // Use a multiplier to spread the bits of the hashCode
        seed = (lobbyWord.hashCode().toLong() * 2862933555777941757L + 1L) xor turnNumber.toLong()
    }

    /**
     * Internal step for the LCG algorithm.
     */
    private fun next(): Int {
        seed = seed * 6364136223846793005L + 1442695040888963407L
        return (seed ushr 33).toInt()
    }

    /**
     * Returns a float between 0.0 and 1.0.
     */
    fun nextFloat(): Float {
        return (next().absoluteValue.toFloat() / Int.MAX_VALUE.toFloat())
    }

    /**
     * Legacy horizontal wind in the range [-10.0, 10.0].
     */
    fun windForTurn(): Float {
        return nextFloat() * 20f - 10f
    }

    /**
     * Gunbound-like 360° wind: speed plus direction.
     */
    fun windVectorForTurn(): WindState {
        val speed = nextFloat() * 10f
        val direction = nextFloat() * 360f
        return WindState(speed, direction)
    }
    
    /**
     * Returns a random integer in the range [min, max].
     */
    fun nextInt(min: Int, max: Int): Int {
        if (min >= max) return min
        return min + (next().absoluteValue % (max - min + 1))
    }
}
