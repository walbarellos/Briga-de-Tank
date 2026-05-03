package com.tankbriga.engine.network

import com.tankbriga.engine.ActionPacket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MultiplayerProtocolFlowTest {

    private class FakeTransport(private val players: Set<Byte>) : NetworkTransport {
        val inbox = players.associateWith { mutableListOf<ByteArray>() }

        override fun send(targetPlayerId: Byte, payload: ByteArray) {
            inbox.getValue(targetPlayerId).add(payload)
        }

        override fun broadcast(payload: ByteArray, excludePlayerIds: Set<Byte>) {
            players.filterNot { it in excludePlayerIds }.forEach { send(it, payload) }
        }
    }

    @Test
    fun `host plus three clients receive lobby turn action and resolve packets`() {
        val players = setOf<Byte>(0, 1, 2, 3)
        val transport = FakeTransport(players)

        val lobby = LobbySnapshot(
            lobbyWord = "RECREIO",
            hostId = 0,
            players = players.map { id ->
                PlayerSlot(id, "P$id", id.toInt(), 100, id.toFloat() * 100f, 200f)
            }
        )
        transport.broadcast(ProtocolPayloads.json(PktType.LOBBY_SNAPSHOT, lobby), excludePlayerIds = setOf(0))
        players.minus(0).forEach { transport.send(0, ProtocolPayloads.ack(PktType.LOBBY_ACK, 0, 0)) }

        val turn = TurnStartPacket(playerId = 0, windValue = 0.2f, turnNumber = 1, serverStartMs = 10_000L)
        transport.broadcast(ProtocolPayloads.json(PktType.TURN_START, turn), excludePlayerIds = setOf(0))
        players.minus(0).forEach { transport.send(0, ProtocolPayloads.ack(PktType.TURN_ACK, 1, 0)) }

        val action = ActionPacket(0, 450, 70, 0, 0, 7)
        transport.broadcast(ProtocolPayloads.action(PktType.ACTION_FWRD, action), excludePlayerIds = setOf(0))
        players.minus(0).forEach { transport.send(0, ProtocolPayloads.ack(PktType.ACTION_ACK, 1, 7)) }

        val resolve = ShotResolvePacket(
            shotId = 7,
            turnNumber = 1,
            shooterId = 0,
            resultType = 1,
            impactX = 300f,
            impactY = 400f,
            shotType = 0,
            shotAngle = 45f,
            directTankId = null,
            craterRadius = 40,
            tankIds = players.toList(),
            tankHps = players.map { 100 },
            tankPositionsX = players.map { it.toFloat() * 100f },
            tankPositionsY = players.map { 200f },
            eliminated = emptyList()
        )
        transport.broadcast(ProtocolPayloads.json(PktType.SHOT_RESOLVE, resolve), excludePlayerIds = setOf(0))
        players.minus(0).forEach { transport.send(0, ProtocolPayloads.ack(PktType.RESOLVE_ACK, 1, 7)) }

        players.minus(0).forEach { id ->
            val types = transport.inbox.getValue(id).map { it.first() }
            assertEquals(listOf(PktType.LOBBY_SNAPSHOT.id, PktType.TURN_START.id, PktType.ACTION_FWRD.id, PktType.SHOT_RESOLVE.id), types)
        }

        val hostAckTypes = transport.inbox.getValue(0).map { it.first() }
        assertTrue(hostAckTypes.containsAll(listOf(PktType.LOBBY_ACK.id, PktType.TURN_ACK.id, PktType.ACTION_ACK.id, PktType.RESOLVE_ACK.id)))
    }

}
