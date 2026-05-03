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

    fun render(localPlayerId: Byte, isCoordinator: Boolean, currentTurnPlayerId: Byte, turnNumber: Short, expectedPeers: Int, acks: NetworkAckTracker): String {
        val role = if (isCoordinator) "HOST" else "JOIN"
        return "id=$localPlayerId $role turn=$currentTurnPlayerId #$turnNumber\n" +
            "LOBBY $lastLobbyInfo ack=${acks.lobbyAckCount()}/$expectedPeers\n" +
            "TURN $lastTurnStartInfo ack=${acks.turnAckCount()}/$expectedPeers\n" +
            "ACTION $lastActionInfo ack=${acks.latestActionAckCount()}/$expectedPeers AIM $lastAimInfo\n" +
            "RESOLVE $lastResolveInfo ack=${acks.latestResolveAckCount()}/$expectedPeers"
    }
}
