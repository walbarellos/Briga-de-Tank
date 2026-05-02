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
    companion object { const val TURN_SECONDS = 45 }
    private var timerJob: Job? = null
    val remainingSeconds = AtomicInteger(TURN_SECONDS)

    var onTimeout: (() -> Unit)? = null
    var onTick: ((Int) -> Unit)? = null

    /**
     * Starts the mobile-friendly countdown for the current turn.
     */
    fun startCountdown() {
        timerJob?.cancel()
        remainingSeconds.set(TURN_SECONDS)
        
        timerJob = scope.launch {
            while (remainingSeconds.get() > 0) {
                onTick?.invoke(remainingSeconds.get())
                delay(1000)
                remainingSeconds.decrementAndGet()
            }
            onTick?.invoke(0) // Ensure 0 is emitted
            onTimeout?.invoke()
        }
    }

    fun stopCountdown() {
        timerJob?.cancel()
    }

    /**
     * Moves the match to the next player's turn.
     */
    fun nextTurn() {
        val alive = gameState.getAlivePlayersSorted()
        if (alive.isEmpty()) return

        gameState.turnNumber++
        val index = ((gameState.turnNumber - 1) % alive.size).coerceAtLeast(0)
        gameState.currentTurnPlayerId = alive[index].id
        
        // Deterministic Gunbound-like wind.
        DeterministicRng.init(gameState.lobbyWord, gameState.turnNumber)
        gameState.windState = DeterministicRng.windVectorForTurn()
        gameState.wind = gameState.windState.horizontalComponent()
        
        gameState.phase = MatchPhase.PLAYER_TURN
        startCountdown()
    }
}
