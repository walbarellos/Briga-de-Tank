package com.tankbriga.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SimulationTest {

    @Test
    fun `Simulation should be completely deterministic across instances`() {
        val lobbyWord = "TANKS2026"
        val turnNumber = 5
        
        // Instance 1
        val terrain1 = Terrain(800, 480)
        terrain1.generate(lobbyWord.hashCode().toLong())
        val tanks1 = listOf(
            Tank(0, Vector2(100f, 300f)),
            Tank(1, Vector2(700f, 300f))
        )
        val sim1 = SimulationOrchestrator(terrain1, tanks1)
        DeterministicRng.init(lobbyWord, turnNumber)
        val action1 = ActionPacket(0, 450, 80, 0, 0, 1) // 45.0 degrees, power 80, bullet
        val result1 = sim1.simulateShot(action1)

        // Instance 2
        val terrain2 = Terrain(800, 480)
        terrain2.generate(lobbyWord.hashCode().toLong())
        val tanks2 = listOf(
            Tank(0, Vector2(100f, 300f)),
            Tank(1, Vector2(700f, 300f))
        )
        val sim2 = SimulationOrchestrator(terrain2, tanks2)
        DeterministicRng.init(lobbyWord, turnNumber)
        val action2 = ActionPacket(0, 450, 80, 0, 0, 1)
        val result2 = sim2.simulateShot(action2)

        // Verify paths are identical
        assertEquals(result1.path.size, result2.path.size, "Path lengths must be identical")
        for (i in result1.path.indices) {
            assertEquals(result1.path[i].x, result2.path[i].x)
            assertEquals(result1.path[i].y, result2.path[i].y)
        }
        
        // Verify outcome is identical
        assertEquals(result1::class, result2::class, "Outcomes must be of the same type")
    }

    @Test
    fun `Vertical shot should stop horizontal movement at apex`() {
        val terrain = Terrain(800, 480)
        val tanks = listOf(Tank(0, Vector2(100f, 300f)))
        val sim = SimulationOrchestrator(terrain, tanks)
        
        DeterministicRng.init("TEST", 1)
        // 4 = Vertical shot
        val action = ActionPacket(0, 450, 100, 4, 0, 1) 
        val result = sim.simulateShot(action)
        
        // Find apex
        var apexIndex = 0
        for (i in 1 until result.path.size) {
            if (result.path[i].y > result.path[i-1].y) {
                apexIndex = i - 1
                break
            }
        }
        
        // After apex, x should barely change
        if (apexIndex > 0 && apexIndex + 10 < result.path.size) {
            val apexX = result.path[apexIndex].x
            val dropX = result.path[apexIndex + 10].x
            assertTrue(Math.abs(apexX - dropX) < 5f, "Vertical shot should drop straight down after apex")
        }
    }
}
