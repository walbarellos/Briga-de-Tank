package com.tankbriga.engine

import kotlin.test.Test
import kotlin.test.assertEquals

class DeterministicTest {

    @Test
    fun `RNG should produce identical values for the same lobby word and turn`() {
        val word = "RECREIO"
        val turn = 1

        DeterministicRng.init(word, turn)
        val sequence1 = List(5) { DeterministicRng.nextFloat() }

        DeterministicRng.init(word, turn)
        val sequence2 = List(5) { DeterministicRng.nextFloat() }

        assertEquals(sequence1, sequence2, "RNG sequences MUST be identical for the same seed")
    }

    @Test
    fun `Vector math should be consistent`() {
        val v1 = Vector2(10f, 0f)
        val v2 = Vector2(0f, 10f)
        val sum = v1 + v2
        
        assertEquals(10f, sum.x)
        assertEquals(10f, sum.y)
        assertEquals(14.1421356f, sum.length(), 0.0001f)
    }

    @Test
    fun `Reflection should calculate correct bounce vector`() {
        val velocity = Vector2(10f, 10f) // Down-Right
        val floorNormal = Vector2(0f, -1f) // Pointing Up
        
        val bounce = velocity.reflect(floorNormal)
        
        assertEquals(10f, bounce.x)
        assertEquals(-10f, bounce.y) // Up-Right
    }
}
