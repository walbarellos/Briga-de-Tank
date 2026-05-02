package com.tankbriga.engine

import kotlin.test.Test
import kotlin.test.assertTrue

class GunboundTerrainImpactTest {
    private fun flatTerrain(): Terrain {
        val terrain = Terrain(220, 220)
        for (x in 0 until terrain.width) {
            for (y in 110 until terrain.height) terrain.setSolid(x, y, true)
        }
        return terrain
    }

    @Test
    fun `explosion must destroy terrain pixels`() {
        val terrain = flatTerrain()
        val tank = Tank(0, terrain.placeOnSurface(110f, 14f), hp = 100, radius = 14f, name = "VOCÊ")
        val state = GameState("TEST", terrain, mutableListOf(tank))
        val before = terrain.countSolidPixels()

        val report = state.applyExplosion(Vector2(110f, 110f), shotType = 1, directTankId = null)

        assertTrue(terrain.countSolidPixels() < before, "Bomb impact must open a visible crater")
        assertTrue(report.craterRadius >= 40, "Bomb crater must be large enough to read on mobile")
    }

    @Test
    fun `tank above destroyed terrain must fall to new surface`() {
        val terrain = flatTerrain()
        val tank = Tank(0, terrain.placeOnSurface(110f, 14f), hp = 100, radius = 14f, name = "VOCÊ")
        val state = GameState("TEST", terrain, mutableListOf(tank))
        val oldY = tank.position.y

        val report = state.applyExplosion(Vector2(110f, 110f), shotType = 1, directTankId = null)

        assertTrue(tank.position.y > oldY, "Tank must drop when the crater removes the ground under it")
        assertTrue(report.falls.any { it.tankId == tank.id }, "Fall report must explain who fell")
    }
}
