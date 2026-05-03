package com.tankbriga.engine.network

/**
 * Drops duplicate gameplay packets using stable protocol keys.
 */
class PacketDeduplicator {
    private val seenTurns = mutableSetOf<Short>()
    private val seenActions = mutableSetOf<ActionKey>()
    private val seenResolves = mutableSetOf<ResolveKey>()

    fun firstTurn(turnNumber: Short): Boolean = seenTurns.add(turnNumber)

    fun firstAction(playerId: Byte, seq: Short, turnNumber: Short, forwarded: Boolean): Boolean =
        seenActions.add(ActionKey(playerId, seq, turnNumber, forwarded))

    fun firstResolve(shooterId: Byte, shotId: Short, turnNumber: Short): Boolean =
        seenResolves.add(ResolveKey(shooterId, shotId, turnNumber))

    private data class ActionKey(
        val playerId: Byte,
        val seq: Short,
        val turnNumber: Short,
        val forwarded: Boolean
    )

    private data class ResolveKey(
        val shooterId: Byte,
        val shotId: Short,
        val turnNumber: Short
    )
}
