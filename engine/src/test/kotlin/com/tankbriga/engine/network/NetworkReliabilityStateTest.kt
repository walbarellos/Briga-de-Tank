package com.tankbriga.engine.network

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkReliabilityStateTest {

    @Test
    fun `ack tracker requires all expected peers per channel`() {
        val tracker = NetworkAckTracker()
        val expected = listOf<Byte>(1, 2, 3)

        tracker.beginLobby("RECREIO")
        tracker.recordAck(1, PktType.LOBBY_ACK, AckPacket(PktType.LOBBY_ACK.id, 0, 0), "RECREIO")
        tracker.recordAck(2, PktType.LOBBY_ACK, AckPacket(PktType.LOBBY_ACK.id, 0, 0), "RECREIO")
        assertFalse(tracker.hasLobbyAcks(expected))
        tracker.recordAck(3, PktType.LOBBY_ACK, AckPacket(PktType.LOBBY_ACK.id, 0, 0), "RECREIO")
        assertTrue(tracker.hasLobbyAcks(expected))

        tracker.beginTurn(4)
        tracker.recordAck(1, PktType.TURN_ACK, AckPacket(PktType.TURN_ACK.id, 3, 0), "RECREIO")
        assertFalse(tracker.hasTurnAcks(listOf(1)))
        tracker.recordAck(1, PktType.TURN_ACK, AckPacket(PktType.TURN_ACK.id, 4, 0), "RECREIO")
        assertTrue(tracker.hasTurnAcks(listOf(1)))
    }

    @Test
    fun `deduplicator separates action direction but blocks exact duplicates`() {
        val deduplicator = PacketDeduplicator()

        assertTrue(deduplicator.firstTurn(1))
        assertFalse(deduplicator.firstTurn(1))

        assertTrue(deduplicator.firstAction(2, 10, 1, forwarded = false))
        assertFalse(deduplicator.firstAction(2, 10, 1, forwarded = false))
        assertTrue(deduplicator.firstAction(2, 10, 1, forwarded = true))

        assertTrue(deduplicator.firstResolve(2, 10, 1))
        assertFalse(deduplicator.firstResolve(2, 10, 1))
    }
}
