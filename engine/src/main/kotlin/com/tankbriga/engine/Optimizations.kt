package com.tankbriga.engine

import java.util.Stack

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
        val path = PathPool.borrow()
        for (i in 0 until actualSize) {
            path.add(VectorPool.borrow(buffer[i].x, buffer[i].y))
        }
        return path
    }

    fun getResult(): ShotResult? {
        val result = lastResult ?: return null
        val path = getPath()
        return when (result) {
            is ShotResult.TerrainHit -> result.copy(path = path)
            is ShotResult.TankHit -> result.copy(path = path)
            is ShotResult.Miss -> result.copy(path = path)
        }
    }
    
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
    private const val MAX_SIZE = 10000
    private val pool = Array(MAX_SIZE) { Vector2(0f, 0f) }
    private var head = MAX_SIZE

    @Synchronized
    fun borrow(x: Float, y: Float): Vector2 {
        val v = if (head > 0) pool[--head] else Vector2(x, y)
        v.x = x
        v.y = y
        return v
    }

    @Synchronized
    fun release(v: Vector2) {
        if (head < MAX_SIZE) {
            pool[head++] = v
        }
    }
}

/**
 * Object Pool for ArrayList<Vector2> to avoid allocating new lists on every shot simulation.
 */
object PathPool {
    private val pool = Stack<ArrayList<Vector2>>()

    @Synchronized
    fun borrow(): ArrayList<Vector2> {
        return if (pool.isNotEmpty()) pool.pop() else ArrayList(800)
    }

    @Synchronized
    fun release(path: List<Vector2>) {
        if (path is ArrayList<Vector2>) {
            path.forEach { VectorPool.release(it) }
            path.clear()
            pool.push(path)
        }
    }
}
