package com.tankbriga.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Runs the deterministic physics simulation for a shot.
 * Optimized for zero-allocation performance and high FPS.
 */
class SimulationOrchestrator(
    private val terrain: Terrain,
    private val tanks: List<Tank>
) {
    private val dt = 1f / 60f
    private val gravity = 650.0f // Calibrated for pixels/s^2
    private val cache = TrajectoryCache(800)
    private var currentWind: WindState = WindState.zero()

    fun setWind(wind: WindState) {
        currentWind = wind
    }

    /**
     * Simulates the complete trajectory. Uses cache if inputs are identical.
     */
    fun simulateShot(action: ActionPacket): ShotResult {
        val windKey = currentWind.cacheKey()
        if (cache.isValid(action, windKey)) {
            return cache.getResult() ?: ShotResult.Miss(cache.getPath(), action.angleTenths / 10f)
        }

        val result = runIntegration(action, currentWind)
        cache.update(action, windKey, result)
        return result
    }

    private fun runIntegration(action: ActionPacket, wind: WindState): ShotResult {
        val shotAngle = action.angleTenths / 10f
        val angleRad = shotAngle * 3.14159265f / 180f
        val shooter = tanks.find { it.id == action.playerId } ?: return ShotResult.Miss(emptyList(), shotAngle)
        val config = ShotRegistry.getById(when(action.shotType.toInt()) {
            0 -> "bullet"
            1 -> "bomb"
            2 -> "ricochet"
            3 -> "cluster"
            4 -> "vertical"
            else -> "bullet"
        })

        var vx = action.power * Math.cos(angleRad.toDouble()).toFloat() * (config.speed / 100f)
        var vy = -action.power * Math.sin(angleRad.toDouble()).toFloat() * (config.speed / 100f)
        
        // Offset spawn to barrel tip (radius + 20px) to prevent self-collision
        val spawnOffset = shooter.radius + 20f
        var px = shooter.position.x + Math.cos(angleRad.toDouble()).toFloat() * spawnOffset
        var py = shooter.position.y - Math.sin(angleRad.toDouble()).toFloat() * spawnOffset
        
        val path = ArrayList<Vector2>(800)
        path.add(VectorPool.borrow(px, py))

        var bouncesLeft = config.bounces
        val windAccX = wind.xComponent() * 20.0f * dt
        val windAccY = wind.yComponent() * 10.0f * dt
        val gravAcc = gravity * dt

        for (step in 0 until 800) {
            if (config.trailType == TrailType.VERTICAL && vy > 0) vx = 0f

            vx += windAccX
            vy += gravAcc + windAccY
            px += vx * dt
            py += vy * dt
            
            // Use VectorPool to avoid allocations
            path.add(VectorPool.borrow(px, py))

            if (px < 0 || px >= terrain.width || py >= terrain.height) return ShotResult.Miss(path, shotAngle)

            val ipx = px.toInt()
            val ipy = py.toInt()
            
            if (terrain.isSolid(ipx, ipy)) {
                if (bouncesLeft > 0) {
                    val normal = estimateTerrainNormal(ipx, ipy)
                    // Reflection math without object creation
                    val dot = vx * normal.x + vy * normal.y
                    vx = (vx - 2f * dot * normal.x) * 0.8f
                    vy = (vy - 2f * dot * normal.y) * 0.8f
                    bouncesLeft--
                    px += normal.x * 2f
                    py += normal.y * 2f
                } else {
                    return ShotResult.TerrainHit(path, shotAngle, Vector2(px, py), action.shotType)
                }
            }

            // Spatial Fast-Path: Only check tanks within 100px of projectile
            for (tank in tanks) {
                if (step > 10 || tank.id != shooter.id) {
                    val dx = px - tank.position.x
                    val dy = py - tank.position.y
                    if (dx*dx + dy*dy <= (tank.radius + config.radius*0.2f)*(tank.radius + config.radius*0.2f)) {
                         return ShotResult.TankHit(path, shotAngle, tank.id, Vector2(px, py), action.shotType)
                    }
                }
            }
        }
        return ShotResult.Miss(path, shotAngle)
    }

    /**
     * Simple normal estimation by sampling surrounding pixels.
     * Crucial for Ricochet.
     */
    private fun estimateTerrainNormal(px: Int, py: Int): Vector2 {
        var nx = 0f
        var ny = 0f
        
        if (!terrain.isSolid(px - 1, py)) nx -= 1f
        if (!terrain.isSolid(px + 1, py)) nx += 1f
        if (!terrain.isSolid(px, py - 1)) ny -= 1f
        if (!terrain.isSolid(px, py + 1)) ny += 1f
        
        // If embedded completely, push up
        if (nx == 0f && ny == 0f) return Vector2.UP
        
        return Vector2(nx, ny).normalized()
    }
}
