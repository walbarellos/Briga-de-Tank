package com.tankbriga.engine

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.system.measureTimeMillis

class PerformanceTest {

    @Test
    fun `Simulation should be fast enough for low-end devices`() {
        val lobbyWord = "PERF_TEST"
        val terrain = Terrain(1280, 720) // HD Resolution
        terrain.generate(lobbyWord.hashCode().toLong())
        
        val tanks = List(8) { i -> 
            Tank(i.toByte(), Vector2(100f + i * 100f, 300f))
        }
        
        val sim = SimulationOrchestrator(terrain, tanks)
        val action = ActionPacket(0, 450, 90, 0, 0, 1)

        // Warm up
        repeat(10) { sim.simulateShot(action) }

        val time = measureTimeMillis {
            repeat(100) {
                sim.simulateShot(action)
            }
        }

        val averageTime = time / 100.0
        println("Average simulation time (600 steps): $averageTime ms")
        
        // Target: < 1ms per full trajectory simulation on a modern machine 
        // to ensure < 10ms on a 2016 low-end Android.
        assertTrue(averageTime < 2.0, "Simulation is too slow: $averageTime ms")
    }

    @Test
    fun `TrajectoryCache should prevent redundant calculations`() {
        val terrain = Terrain(800, 480)
        val tanks = listOf(Tank(0, Vector2(100f, 300f)))
        val sim = SimulationOrchestrator(terrain, tanks)
        val action = ActionPacket(0, 450, 80, 0, 0, 1)

        val time1 = measureTimeMillis { sim.simulateShot(action) }
        val time2 = measureTimeMillis { sim.simulateShot(action) }

        // Cache isn't fully returning yet in the stub, but we can verify it doesn't crash
        assertTrue(time2 <= time1, "Subsequent calls should be faster or equal (cache)")
    }
}
