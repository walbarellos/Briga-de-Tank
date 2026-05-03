package com.tankbriga.engine

import kotlinx.coroutines.runBlocking
import kotlin.test.*

class GameLoopTest {

    @Test
    fun `Turn order should follow player ids`() {
        val terrain = Terrain(800, 480)
        val tanks = mutableListOf(
            Tank(0, Vector2(500f, 300f)), // Right
            Tank(1, Vector2(100f, 300f)), // Left
            Tank(2, Vector2(300f, 300f))  // Middle
        )
        val gameState = GameState("TEST", terrain, tanks)
        
        val sorted = gameState.getAlivePlayersSorted()
        assertEquals(0, sorted[0].id, "First player must be the lowest id")
        assertEquals(1, sorted[1].id, "Second player must be the next id")
        assertEquals(2, sorted[2].id, "Third player must be the highest id")
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
        
        turnManager.startCountdown(1000)
        
        kotlinx.coroutines.delay(1200)
        assertTrue(timeoutTriggered, "Timeout callback must be triggered after delay")
    }
}
