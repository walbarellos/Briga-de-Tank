package com.tankbriga.engine

import kotlin.random.Random

/**
 * A ballistic AI with varied skill levels.
 */
class AngledBot(val botId: Byte) {

    /**
     * Skill levels: 
     * 0: Amateur (Large error, fixed power)
     * 1: Sniper (Low error, calculated power)
     * 2: Bomber (Uses Bombs, high arch)
     */
    private val skillLevel = (botId.toInt() % 3)

    fun decideAction(myPos: Vector2, targetPos: Vector2, wind: WindState): ActionPacket {
        val dx = targetPos.x - myPos.x
        val dy = targetPos.y - myPos.y
        
        // Base angle based on distance and arch
        var targetAngle = if (dx > 0) 450 else 1350
        if (skillLevel == 2) targetAngle = if (dx > 0) 700 else 1100 // High bomber arch
        
        // Wind compensation (basic but effective for Gunbound feel)
        val headwind = wind.horizontalComponent() * (if (dx > 0) 1 else -1)
        targetAngle += (headwind * -18f).toInt()
        
        // Error margin based on skill
        val errorRange = when(skillLevel) {
            1 -> 25 // Snipers are precise
            2 -> 45 // Bombers take big swings
            else -> 65 // Amateurs miss a lot
        }
        val noise = Random.nextInt(-errorRange, errorRange)
        val finalAngle = (targetAngle + noise).coerceIn(100, 1700).toShort()
        
        // Varied power based on distance
        val distFactor = Math.abs(dx) / 1200f
        val basePower = (65 + distFactor * 35).coerceIn(40.0f, 95.0f).toInt()
        val finalPower = (basePower + Random.nextInt(-5, 5)).toByte()
        
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
