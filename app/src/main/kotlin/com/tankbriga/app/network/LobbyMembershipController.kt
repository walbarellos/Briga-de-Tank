package com.tankbriga.app.network

import com.tankbriga.engine.network.CoordinatorNode
import com.tankbriga.engine.network.ProtocolPayloads
import java.net.InetAddress

class LobbyMembershipController(
    private val localName: String,
    private val peerRegistry: PeerRegistry,
    private val coordinator: CoordinatorNode,
    private val localPlayerId: () -> Byte,
    private val setLocalPlayerId: (Byte) -> Unit,
    private val onReliableAck: (Short) -> Unit,
    private val sendSecuredTo: (ByteArray, InetAddress) -> Unit,
    private val onLocalIdAssigned: (oldId: Byte, newId: Byte) -> Unit,
    private val onPlayerJoined: (name: String, ip: String, id: Byte) -> Unit
) {
    fun handleJoinRequest(address: InetAddress, playerId: Byte, payload: ByteArray) {
        val name = if (payload.size > 1) String(payload, 1, payload.size - 1) else "P$playerId"
        val ip = address.hostAddress ?: return
        val assignedId = if (coordinator.isCoordinator) allocatePlayerId(playerId, ip) else playerId

        if (!peerRegistry.isAuthorized(assignedId, ip)) {
            peerRegistry.addPeer(assignedId, ip)
            coordinator.registerPlayer(assignedId)
            onPlayerJoined(name, ip, assignedId)
        }

        if (!coordinator.isCoordinator) return
        sendSecuredTo(ProtocolPayloads.joinAck(assignedId, localName), address)
    }

    fun handleJoinAck(ingress: PacketIngress.JoinAck) {
        val payload = ingress.payload
        val envelope = ingress.envelope
        onReliableAck(readShort(payload, 1))

        val assignedId = payload.getOrNull(3)
            ?.takeIf { it.toInt() in 1..7 }
            ?: localPlayerId()

        if (assignedId != localPlayerId()) {
            val oldId = localPlayerId()
            setLocalPlayerId(assignedId)
            onLocalIdAssigned(oldId, assignedId)
        }

        val nameOffset = if (payload.size > 4 && payload[3].toInt() in 1..7) 4 else 3
        val name = if (payload.size > nameOffset) {
            String(payload, nameOffset, payload.size - nameOffset)
        } else {
            "P${envelope.playerId}"
        }

        if (!peerRegistry.isAuthorized(envelope.playerId, ingress.ip)) {
            peerRegistry.addPeer(envelope.playerId, ingress.ip)
            coordinator.registerPlayer(envelope.playerId)
            onPlayerJoined(name, ingress.ip, envelope.playerId)
        }
    }

    private fun allocatePlayerId(requestedId: Byte, ip: String): Byte {
        val requested = requestedId.toInt()
        if (requested in 1..7) {
            val currentIp = peerRegistry.getPeerIp(requestedId)
            if (currentIp == null || currentIp == ip) return requestedId
        }
        return (1..7)
            .map { it.toByte() }
            .firstOrNull { peerRegistry.getPeerIp(it) == null }
            ?: requestedId
    }

    private fun readShort(buf: ByteArray, offset: Int): Short =
        (((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)).toShort()
}
