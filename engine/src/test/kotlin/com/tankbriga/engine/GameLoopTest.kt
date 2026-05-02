package com.tankbriga.engine

import kotlinx.coroutines.runBlocking
import kotlin.test.*

class GameLoopTest {

    @Test
    fun `Turn order should follow Left-to-Right positions`() {
        val terrain = Terrain(800, 480)
        val tanks = mutableListOf(
            Tank(0, Vector2(500f, 300f)), // Right
            Tank(1, Vector2(100f, 300f)), // Left
            Tank(2, Vector2(300f, 300f))  // Middle
        )
        val gameState = GameState("TEST", terrain, tanks)
        
        val sorted = gameState.getAlivePlayersSorted()
        assertEquals(1, sorted[0].id, "First player must be the one at x=100")
        assertEquals(2, sorted[1].id, "Second player must be the one at x=300")
        assertEquals(0, sorted[2].id, "Third player must be the one at x=500")
    }

    @Test
    fun `TurnManager should cycle between alive players`() {
        val terrain = Terrain(800, 480)
        val tanks = mutableListOf(
            Tank(1, Vector2(100f, 300f)),
            Tank(2, Vector2(300f, 300f))
        )
        val gameState = GameState("TEST", terrain, tanks)
        val turnManager = TurnManager(gameState)
        
        turnManager.nextTurn()
        val firstPlayer = gameState.currentTurnPlayerId
        
        turnManager.nextTurn()
        val secondPlayer = gameState.currentTurnPlayerId
        
        assertNotEquals(firstPlayer, secondPlayer, "Turns must alternate")
        
        turnManager.nextTurn()
        assertEquals(firstPlayer, gameState.currentTurnPlayerId, "Turns must cycle back")
    }

    @Test
    fun `Timer timeout should trigger callback`() = runBlocking {
        val terrain = Terrain(800, 480)
        val tanks = mutableListOf(Tank(1, Vector2(100f, 300f)))
        val gameState = GameState("TEST", terrain, tanks)
        val turnManager = TurnManager(gameState)
        
        var timeoutTriggered = false
        turnManager.onTimeout = { timeoutTriggered = true }
        
        // Manual override for test speed
        turnManager.remainingSeconds.set(1)
        turnManager.startCountdown()
        
        kotlinx.coroutines.delay(1200)
        assertTrue(timeoutTriggered, "Timeout callback must be triggered after delay")
    }
}
