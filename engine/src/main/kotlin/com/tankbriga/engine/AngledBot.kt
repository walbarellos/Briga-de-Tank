package com.tankbriga.engine

import kotlin.math.abs

/**
 * A ballistic AI with varied skill levels.
 */
class AngledBot(val botId: Byte) {

    private val rng = java.util.Random()

    /**
     * Skill levels: 
     * 0: Amateur (Large error)
     * 1: Sniper (Low error)
     * 2: Bomber (Uses Bombs, high arch)
     */
    private val skillLevel = (botId.toInt() % 3)

    fun decideAction(myPos: Vector2, targetPos: Vector2, wind: WindState): ActionPacket {
        val dx = targetPos.x - myPos.x
        
        // Varied power based on distance
        val distFactor = abs(dx) / 1200f
        val targetPower = (65f + distFactor * 35f).coerceIn(40.0f, 95.0f)
        
        // Find perfect angle using iterative physics simulation
        var bestAngle = if (dx > 0) 45f else 135f
        var bestDist = Float.MAX_VALUE
        
        val dt = 1f / 60f
        val gravAcc = 650f * dt
        val windAccX = wind.xComponent() * 20f * dt
        val windAccY = wind.yComponent() * 10f * dt
        
        val startAng = if (dx > 0) 5 else 95
        val endAng = if (dx > 0) 85 else 175
        
        for (a in startAng..endAng) {
            val rad = Math.toRadians(a.toDouble())
            var vx = targetPower * kotlin.math.cos(rad).toFloat()
            var vy = -targetPower * kotlin.math.sin(rad).toFloat()
            var px = myPos.x
            var py = myPos.y
            var minD = Float.MAX_VALUE
            for (step in 0..400) {
                vx += windAccX
                vy += gravAcc + windAccY
                px += vx * dt
                py += vy * dt
                val d = (px - targetPos.x)*(px - targetPos.x) + (py - targetPos.y)*(py - targetPos.y)
                if (d < minD) minD = d
                if (py > targetPos.y + 100f) break // passed target
            }
            if (minD < bestDist) {
                bestDist = minD
                bestAngle = a.toFloat()
            }
        }
        
        // Error margin based on skill using Gaussian distribution
        val difficultySpread = when(skillLevel) {
            1 -> 1.5f // Snipers are precise
            2 -> 3.5f // Bombers
            else -> 6.0f // Amateurs miss a lot
        }
        
        val error = (rng.nextGaussian() * difficultySpread).toFloat()
        val finalAngle = ((bestAngle + error) * 10f).toInt().coerceIn(100, 1700).toShort()
        
        val powerError = (rng.nextGaussian() * (difficultySpread / 2f)).toFloat()
        val finalPower = (targetPower + powerError).toInt().coerceIn(1, 100).toByte()
        
        return ActionPacket(
            playerId = botId,
            angleTenths = finalAngle,
            power = finalPower,
            shotType = if (skillLevel == 2) 1.toByte() else 0.toByte(), // Bombers use bombs
            moveDir = 0,
            seq = 0
        )
    }
}
