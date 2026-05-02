package com.tankbriga.engine

/**
 * Represents the intention of a player to fire a shot.
 * This is the ONLY data transmitted over the network during gameplay.
 * Size: 8 Bytes
 */
data class ActionPacket(
    val playerId: Byte,
    val angleTenths: Short, // angle * 10 (0.0 to 180.0)
    val power: Byte,        // 0 to 100
    val shotType: Byte,     // ID mapping to ShotConfig
    val moveDir: Byte,      // -1 (Left), 0 (None), 1 (Right)
    val seq: Short          // Sequence for reliable delivery dedup
)

/** A simple DTO for Tank state in the physics engine. */
data class Tank(
    val id: Byte,
    var position: Vector2,
    var hp: Int = 100,
    val radius: Float = 10f, // Bounding box/circle for collisions
    val name: String = "P",
    val isBot: Boolean = false,
    val color: Int = 0xFFFFFFFF.toInt()
)

/** Per-tank damage produced by an explosion. */
data class DamageReport(
    val tankId: Byte,
    val oldHp: Int,
    val newHp: Int,
    val damage: Int,
    val directHit: Boolean,
    val distanceToImpact: Float,
    val highAngleBonus: Boolean = false
)

/** Per-tank gravity settling after terrain was destroyed. */
data class FallReport(
    val tankId: Byte,
    val fromY: Float,
    val toY: Float,
    val fallDistance: Float,
    val fallDamage: Int
)

/** Complete outcome after resolving a crater/explosion. */
data class ImpactReport(
    val impactPoint: Vector2,
    val shotType: Byte,
    val craterRadius: Int,
    val damages: List<DamageReport>,
    val falls: List<FallReport>,
    val eliminated: List<Byte>
) {
    val totalDamage: Int get() = damages.sumOf { it.damage } + falls.sumOf { it.fallDamage }
    val hasDirectHit: Boolean get() = damages.any { it.directHit && it.damage > 0 }
    val hitSomeone: Boolean get() = damages.any { it.damage > 0 }
    val madeSomeoneFall: Boolean get() = falls.isNotEmpty()
}

/**
 * The possible outcomes of a simulated shot.
 * Now carries the 'angle' for damage bonus calculation.
 */
sealed class ShotResult {
    abstract val path: List<Vector2>
    abstract val angle: Float

    data class TerrainHit(
        override val path: List<Vector2>,
        override val angle: Float,
        val impactPoint: Vector2,
        val shotType: Byte
    ) : ShotResult()

    data class TankHit(
        override val path: List<Vector2>,
        override val angle: Float,
        val hitTankId: Byte,
        val impactPoint: Vector2,
        val shotType: Byte
    ) : ShotResult()

    data class Miss(
        override val path: List<Vector2>,
        override val angle: Float
    ) : ShotResult()
}
