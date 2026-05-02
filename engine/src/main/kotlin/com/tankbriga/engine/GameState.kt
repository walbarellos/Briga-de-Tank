package com.tankbriga.engine

/** The various phases of a match lifecycle. */
enum class MatchPhase {
    LOBBY,
    ROUND_START,
    WIND_ANNOUNCE,
    PLAYER_TURN,
    FLIGHT,
    IMPACT_RESOLVE,
    KILL_CHECK,
    GAME_OVER,
    RESULTS
}

/** Global match state container. */
class GameState(
    val lobbyWord: String,
    val terrain: Terrain,
    val players: MutableList<Tank> = mutableListOf()
) {
    var phase: MatchPhase = MatchPhase.LOBBY
    var turnNumber: Int = 0
    var currentTurnPlayerId: Byte = -1
    var wind: Float = 0f
    var windState: WindState = WindState.zero()

    val eliminationOrder = mutableListOf<Byte>()
    var winnerId: Byte? = null

    fun getAlivePlayersSorted(): List<Tank> = players.filter { it.hp > 0 }.sortedBy { it.position.x }

    fun currentPlayer(): Tank? = players.find { it.id == currentTurnPlayerId }
    fun humanPlayer(): Tank? = players.firstOrNull { !it.isBot }

    fun recordElimination(playerId: Byte) {
        if (!eliminationOrder.contains(playerId)) eliminationOrder.add(playerId)
        val alive = players.filter { it.hp > 0 }
        if (alive.size <= 1) {
            winnerId = alive.firstOrNull()?.id
            phase = MatchPhase.RESULTS
        }
    }

    /**
     * Resolves a Gunbound-like explosion: crater first, splash damage second,
     * gravity/falling third. This is the central hook that makes terrain destruction
     * affect gameplay instead of being only a visual effect.
     */
    fun applyExplosion(
        impactPoint: Vector2,
        shotType: Byte,
        shotAngle: Float, // Correctly pass angle for bonus
        directTankId: Byte? = null
    ): ImpactReport {
        val config = shotConfig(shotType)
        val craterRadius = config.radius
        terrain.circleErode(impactPoint.x.toInt(), impactPoint.y.toInt(), craterRadius)

        val damages = mutableListOf<DamageReport>()
        val eliminated = mutableSetOf<Byte>()
        val blastRadius = craterRadius * 1.65f

        // HIGH ANGLE BONUS: +50% Damage for angles > 70
        val isHighAngle = shotAngle > 70f
        val damageMultiplier = if (isHighAngle) 1.5f else 1.0f

        players.filter { it.hp > 0 }.forEach { tank ->
            val distance = tank.position.distanceTo(impactPoint)
            val direct = directTankId == tank.id
            if (distance <= blastRadius || direct) {
                val falloff = (1f - distance / blastRadius).coerceIn(0f, 1f)
                val minimumDirect = if (direct) 0.88f else 0f
                val factor = maxOf(falloff, minimumDirect)
                
                val baseDamage = (config.damage * factor)
                val finalDamage = (baseDamage * damageMultiplier).toInt().coerceAtLeast(if (direct) 1 else 0)

                if (finalDamage > 0) {
                    val oldHp = tank.hp
                    tank.hp = (tank.hp - finalDamage).coerceAtLeast(0)
                    damages += DamageReport(tank.id, oldHp, tank.hp, finalDamage, direct, distance, isHighAngle)
                    if (tank.hp <= 0) eliminated += tank.id
                }
            }
        }

        val falls = settleTanksAfterTerrainChange()
        falls.forEach { if (players.find { tank -> tank.id == it.tankId }?.hp == 0) eliminated += it.tankId }
        eliminated.forEach { recordElimination(it) }

        return ImpactReport(impactPoint, shotType, craterRadius, damages, falls, eliminated.toList())
    }

    /** Legacy adapter kept for older tests/specs. */
    fun applyImpact(impact: ShotResult.TerrainHit): ImpactReport = applyExplosion(impact.impactPoint, impact.shotType, impact.angle, null)

    /** Drops tanks after craters open below their feet. */
    fun settleTanksAfterTerrainChange(): List<FallReport> {
        val reports = mutableListOf<FallReport>()
        players.filter { it.hp > 0 }.forEach { tank ->
            val surface = terrain.stableSurfaceYAt(tank.position.x, tank.radius)
            val targetY = surface.toFloat() - tank.radius
            val fall = targetY - tank.position.y
            val buriedCorrection = tank.position.y - targetY
            if (fall > 2f) {
                val oldY = tank.position.y
                tank.position = tank.position.copy(y = targetY)
                val fallDamage = ((fall - 70f) / 22f).toInt().coerceAtLeast(0)
                if (fallDamage > 0) {
                    tank.hp = (tank.hp - fallDamage).coerceAtLeast(0)
                    if (tank.hp <= 0) recordElimination(tank.id)
                }
                reports += FallReport(tank.id, oldY, targetY, fall, fallDamage)
            } else if (buriedCorrection > 4f) {
                tank.position = tank.position.copy(y = targetY)
            }
        }
        return reports
    }

    private fun shotConfig(type: Byte): ShotConfig = ShotRegistry.getById(when (type.toInt()) {
        0 -> "bullet"
        1 -> "bomb"
        2 -> "ricochet"
        3 -> "cluster"
        4 -> "vertical"
        else -> "bullet"
    })
}
