package com.tankbriga.engine.network

/**
 * Tracks ACKs for reliable multiplayer control packets.
 *
 * This class is deliberately free of Android/UDP so retry policies can be tested
 * without phones or Wi-Fi.
 */
class NetworkAckTracker {
    var pendingLobbyWord: String = ""
        private set
    var pendingTurnNumber: Short = 0
        private set

    private val lobbyAcks = mutableSetOf<Byte>()
    private val turnAcks = mutableSetOf<Byte>()
    private val actionAcks = mutableMapOf<Short, MutableSet<Byte>>()
    private val resolveAcks = mutableMapOf<Short, MutableSet<Byte>>()

    fun beginLobby(lobbyWord: String) {
        pendingLobbyWord = lobbyWord
        lobbyAcks.clear()
    }

    fun beginTurn(turnNumber: Short) {
        pendingTurnNumber = turnNumber
        turnAcks.clear()
    }

    fun beginAction(shotId: Short) {
        actionAcks[shotId] = mutableSetOf()
    }

    fun beginResolve(shotId: Short) {
        resolveAcks[shotId] = mutableSetOf()
    }

    fun recordAck(fromPlayerId: Byte, kind: PktType, ack: AckPacket, lobbyWord: String) {
        when (kind) {
            PktType.LOBBY_ACK -> if (pendingLobbyWord == lobbyWord) lobbyAcks.add(fromPlayerId)
            PktType.TURN_ACK -> if (ack.turnNumber == pendingTurnNumber) turnAcks.add(fromPlayerId)
            PktType.ACTION_ACK -> actionAcks.getOrPut(ack.shotId) { mutableSetOf() }.add(fromPlayerId)
            PktType.RESOLVE_ACK -> resolveAcks.getOrPut(ack.shotId) { mutableSetOf() }.add(fromPlayerId)
            else -> Unit
        }
    }

    fun hasLobbyAcks(expected: Collection<Byte>): Boolean = expected.all { it in lobbyAcks }
    fun hasTurnAcks(expected: Collection<Byte>): Boolean = expected.all { it in turnAcks }
    fun hasActionAcks(shotId: Short, expected: Collection<Byte>): Boolean =
        expected.all { id -> actionAcks[shotId]?.contains(id) == true }

    fun hasResolveAcks(shotId: Short, expected: Collection<Byte>): Boolean =
        expected.all { id -> resolveAcks[shotId]?.contains(id) == true }

    fun lobbyAckCount(): Int = lobbyAcks.size
    fun turnAckCount(): Int = turnAcks.size
    fun latestActionAckCount(): Int = actionAcks.entries.maxByOrNull { it.key.toInt() }?.value?.size ?: 0
    fun latestResolveAckCount(): Int = resolveAcks.entries.maxByOrNull { it.key.toInt() }?.value?.size ?: 0
}
