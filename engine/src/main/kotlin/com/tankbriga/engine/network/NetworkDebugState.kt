package com.tankbriga.engine.network

/**
 * Aggregates the small set of multiplayer diagnostics rendered by the in-game overlay.
 */
class NetworkDebugState {
    var lastLobbyInfo: String = "-"
    var lastTurnStartInfo: String = "-"
    var lastActionInfo: String = "-"
    var lastAimInfo: String = "-"
    var lastResolveInfo: String = "-"
    
    var avgRtt: Long = 0
    var packetsSent: Long = 0
    var packetsLost: Long = 0

    fun updateRtt(rtt: Long) {
        if (avgRtt == 0L) {
            avgRtt = rtt
        } else {
            avgRtt = (avgRtt * 7 + rtt) / 8 // Exponential moving average
        }
    }

    fun render(localPlayerId: Byte, isCoordinator: Boolean, currentTurnPlayerId: Byte, turnNumber: Short, expectedPeers: Int, acks: NetworkAckTracker): String {
        val role = if (isCoordinator) "HOST" else "JOIN"
        val lossPct = if (packetsSent > 0) (packetsLost * 100 / packetsSent) else 0
        return "id=$localPlayerId $role turn=$currentTurnPlayerId #$turnNumber RTT=${avgRtt}ms Loss=${lossPct}%\n" +
            "LOBBY $lastLobbyInfo ack=${acks.lobbyAckCount()}/$expectedPeers\n" +
            "TURN $lastTurnStartInfo ack=${acks.turnAckCount()}/$expectedPeers\n" +
            "ACTION $lastActionInfo ack=${acks.latestActionAckCount()}/$expectedPeers AIM $lastAimInfo\n" +
            "RESOLVE $lastResolveInfo ack=${acks.latestResolveAckCount()}/$expectedPeers"
    }
}
