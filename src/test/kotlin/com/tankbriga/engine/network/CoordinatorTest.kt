package com.tankbriga.engine.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class CoordinatorTest {

    @Test
    fun `Election should elect player with lowest ID when coordinator dies`() {
        val players = mutableListOf<Byte>(0, 1, 2, 3)
        val node1 = CoordinatorNode(1, "LOBBY", players)
        
        // Simulate heartbeats for everyone except current coord (0)
        node1.onHeartbeatReceived(1)
        node1.onHeartbeatReceived(2)
        node1.onHeartbeatReceived(3)
        
        // At T=0, node 1 is not coord
        assertFalse(node1.isCoordinator)
        
        // Force health check (simulating coord 0 timed out)
        node1.checkCoordinatorHealth()
        
        // Node 1 should be the new coord because 0 is dead and 1 is the next lowest ID
        assertTrue(node1.isCoordinator)
    }

    @Test
    fun `Coordinator should sequence turns correctly`() {
        val players = mutableListOf<Byte>(1, 2)
        val node = CoordinatorNode(1, "LOBBY", players)
        
        // Force election so node 1 becomes coord
        node.onHeartbeatReceived(1)
        node.onHeartbeatReceived(2)
        node.checkCoordinatorHealth()
        assertTrue(node.isCoordinator)
        
        // Turn sequencing should happen without crashing
        node.startNextTurn()
        node.startNextTurn()
    }
}
