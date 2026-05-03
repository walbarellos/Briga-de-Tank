package com.tankbriga.engine

import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages the turn timing and transitions.
 */
class TurnManager(
    private val gameState: GameState,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {
    companion object {
        const val TURN_SECONDS = 30
    }

    private var countdownJob: Job? = null
    internal val remainingSeconds = AtomicInteger(TURN_SECONDS)

    var onTick: ((Int) -> Unit)? = null
    var onTimeout: (() -> Unit)? = null

    fun startCountdown(durationMs: Int = TURN_SECONDS * 1000) {
        stopCountdown()
        val seconds = (durationMs / 1000f).toInt().coerceAtLeast(1)
        remainingSeconds.set(seconds)
        countdownJob = scope.launch {
            while (isActive && remainingSeconds.get() > 0) {
                onTick?.invoke(remainingSeconds.get())
                delay(1000)
                remainingSeconds.decrementAndGet()
            }
            if (remainingSeconds.get() <= 0) {
                onTimeout?.invoke()
            }
        }
    }

    fun stopCountdown() {
        countdownJob?.cancel()
    }

    fun nextTurn() {
        stopCountdown()
        gameState.turnNumber++
        val alive = gameState.getAlivePlayersSorted()
        if (alive.isEmpty()) return

        gameState.currentTurnPlayerId = alive[(gameState.turnNumber - 1) % alive.size].id
        
        // Use deterministic wind
        DeterministicRng.init(gameState.lobbyWord, gameState.turnNumber)
        val windValue = DeterministicRng.windForTurn()
        gameState.windState.setWind(windValue)
        
        gameState.phase = MatchPhase.PLAYER_TURN
        startCountdown()
    }
}
