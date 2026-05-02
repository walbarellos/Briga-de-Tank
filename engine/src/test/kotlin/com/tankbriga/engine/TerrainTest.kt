package com.tankbriga.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class TerrainTest {

    @Test
    fun `Terrain generation should be deterministic for the same seed`() {
        val t1 = Terrain(800, 480)
        val t2 = Terrain(800, 480)
        val seed = 12345L

        t1.generate(seed)
        t2.generate(seed)

        assertEquals(t1.getStateHash(), t2.getStateHash(), "Hash must be identical for same seed")
        assertEquals(t1.countSolidPixels(), t2.countSolidPixels())
    }

    @Test
    fun `Terrain generation should be different for different seeds`() {
        val t1 = Terrain(800, 480)
        val t2 = Terrain(800, 480)

        t1.generate(11111L)
        t2.generate(22222L)

        assertNotEquals(t1.getStateHash(), t2.getStateHash(), "Hash must be different for different seeds")
    }

    @Test
    fun `Erosion should remove pixels correctly`() {
        val t = Terrain(100, 100)
        // Fill a 10x10 area
        for (x in 40..60) {
            for (y in 40..60) {
                t.setSolid(x, y, true)
            }
        }
        
        val initialCount = t.countSolidPixels()
        
        // Erode center
        t.circleErode(50, 50, 5)
        
        assertFalse(t.isSolid(50, 50), "Center pixel should be void")
        assertTrue(t.countSolidPixels() < initialCount, "Pixel count should decrease")
    }

    @Test
    fun `Erosion should be bit-perfect across instances`() {
        val t1 = Terrain(100, 100)
        val t2 = Terrain(100, 100)
        val seed = 99L

        t1.generate(seed)
        t2.generate(seed)

        t1.circleErode(50, 50, 10)
        t2.circleErode(50, 50, 10)

        assertEquals(t1.getStateHash(), t2.getStateHash(), "Eroded terrain must still match across devices")
    }
}
