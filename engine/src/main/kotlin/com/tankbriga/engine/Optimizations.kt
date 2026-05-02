package com.tankbriga.engine

/**
 * High-performance cache for pre-calculated trajectories.
 * Prevents redundant simulation runs when aim hasn't changed.
 */
class TrajectoryCache(private val maxSteps: Int = 800) {
    private val buffer = Array(maxSteps) { Vector2(0f, 0f) }
    private var actualSize = 0
    private var lastAction: ActionPacket? = null
    private var lastWindKey: Int? = null
    private var lastResult: ShotResult? = null

    /**
     * Checks if the simulation needs to be re-run.
     */
    fun isValid(action: ActionPacket, windKey: Int): Boolean {
        return lastAction?.let { it.angleTenths == action.angleTenths &&
                                  it.power == action.power &&
                                  it.shotType == action.shotType &&
                                  it.playerId == action.playerId &&
                                  lastWindKey == windKey } ?: false
    }

    fun update(action: ActionPacket, windKey: Int, result: ShotResult) {
        lastAction = action
        lastWindKey = windKey
        lastResult = result
        actualSize = result.path.size.coerceAtMost(maxSteps)
        for (i in 0 until actualSize) {
            val p = result.path[i]
            buffer[i].set(p.x, p.y)
        }
    }

    fun getPath(): List<Vector2> {
        return buffer.take(actualSize)
    }

    fun getResult(): ShotResult? = lastResult
    
    fun clear() {
        lastAction = null
        lastWindKey = null
        lastResult = null
        actualSize = 0
    }
}

/**
 * Object Pool for Vector2 to avoid heap pressure and GC pauses during 60Hz simulations.
 */
object VectorPool {
    private val pool = Array(2048) { Vector2(0f, 0f) }
    private var index = 0

    fun borrow(x: Float, y: Float): Vector2 {
        val v = pool[index]
        v.x = x
        v.y = y
        index = (index + 1) % pool.size
        return v
    }
}
