package com.tankbriga.engine

enum class TrailType { BULLET, BOMB, RICOCHET, CLUSTER, VERTICAL }

data class ShotConfig(
    val id: String,
    val speed: Float,
    val radius: Int,
    val damage: Int,
    val bounces: Int,
    val trailType: TrailType
)

object ShotRegistry {
    // CALIBRATED SPEEDS for 2400px wide map (Pixels Per Second).
    // A power of 100 will now cross the map in ~2-3 seconds.
    val BULLET = ShotConfig("bullet", 1400.0f, 32, 35, 0, TrailType.BULLET)
    val BOMB = ShotConfig("bomb", 900.0f, 65, 60, 0, TrailType.BOMB)
    val RICOCHET = ShotConfig("ricochet", 1600.0f, 24, 25, 2, TrailType.RICOCHET)
    val CLUSTER = ShotConfig("cluster", 1100.0f, 22, 20, 0, TrailType.CLUSTER)
    val VERTICAL = ShotConfig("vertical", 1200.0f, 48, 45, 0, TrailType.VERTICAL)
    
    val all = listOf(BULLET, BOMB, RICOCHET, CLUSTER, VERTICAL)
    
    fun getById(id: String) = all.find { it.id == id } ?: BULLET
}
