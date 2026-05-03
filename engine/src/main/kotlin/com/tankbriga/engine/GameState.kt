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

/**
 * Orchestrates the full state of a TankBriga match.
 */
class GameState(
    val lobbyWord: String,
    val terrain: Terrain,
    val players: MutableList<Tank> = mutableListOf()
) {
    var phase: MatchPhase = MatchPhase.ROUND_START
    var turnNumber: Int = 0
    var currentTurnPlayerId: Byte = 0
    val windState = WindState(0f, 0f)
    var winnerId: Byte? = null
    private val eliminationOrder = mutableListOf<Byte>()

    fun getAlivePlayersSorted(): List<Tank> = players.filter { it.hp > 0 }.sortedBy { it.id.toInt() }

    fun currentPlayer(): Tank? = players.find { it.id == currentTurnPlayerId }
    fun humanPlayer(): Tank? = players.firstOrNull { !it.isBot }

    /**
     * Adds a player with distributed random spawn positioning.
     * Prevents clustering by using high-entropy seeding.
     */
    fun addPlayer(id: Byte, name: String, isBot: Boolean, color: Int): Tank {
        synchronized(players) {
            val existing = players.find { it.id == id }
            if (existing != null) return existing

            // High-Entropy Seeding: mix ID, lobbyWord, nanoTime and a random long
            val highEntropySeed = (id.toLong() * 31) + 
                                 lobbyWord.hashCode().toLong() + 
                                 System.nanoTime() + 
                                 java.util.Random().nextLong()
                                 
            val rng = java.util.Random(highEntropySeed)
            
            // Map is 2400px wide. Use a shuffle-based approach to pick a unique zone
            // for the current players count, but keep it simple for now with a 
            // wider random range to avoid the 'same sector' feeling.
            val minX = 100f
            val maxX = terrain.width - 100f
            
            // Pick a truly random X within the full map range
            val spawnX = (minX + rng.nextFloat() * (maxX - minX)).coerceIn(minX, maxX)
            val spawnY = terrain.stableSurfaceYAt(spawnX.toInt(), 18f).toFloat() - 18f

            val newTank = Tank(id, Vector2(spawnX, spawnY), hp = 100, name = name, isBot = isBot, color = color)
            players.add(newTank)
            players.sortBy { it.id.toInt() }
            return newTank
        }
    }

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
        shotAngle: Float,
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
            val surfaceY = terrain.stableSurfaceYAt(tank.position.x.toInt(), tank.radius).toFloat() - tank.radius
            if (surfaceY > tank.position.y) {
                val dist = surfaceY - tank.position.y
                val oldY = tank.position.y
                tank.position = tank.position.copy(y = surfaceY)
                val dmg = if (dist > 50) (dist * 0.15f).toInt() else 0
                if (dmg > 0) tank.hp = (tank.hp - dmg).coerceAtLeast(0)
                reports += FallReport(tank.id, oldY, surfaceY, dist, dmg)
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
