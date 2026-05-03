package com.tankbriga.app.network

import com.tankbriga.engine.network.PktType
import com.tankbriga.engine.network.SecureEnvelope
import com.tankbriga.engine.network.SessionSecurity
import java.net.DatagramPacket
import java.net.InetAddress

class PacketIngressGate(
    private val peerRegistry: PeerRegistry,
    private val localPlayerId: () -> Byte
) {
    fun classify(pkt: DatagramPacket): PacketIngress {
        val raw = pkt.data.copyOf(pkt.length)
        val ip = pkt.address.hostAddress ?: return PacketIngress.Ignore

        val echoEnvelope = SecureEnvelope.fromBinary(raw)
        if (echoEnvelope?.playerId == localPlayerId()) return PacketIngress.Ignore

        if (raw.isNotEmpty() && raw[0] == PktType.REJOIN_REQ.id) {
            return PacketIngress.Rejoin(raw, ip)
        }

        val envelope = SecureEnvelope.fromBinary(raw) ?: return PacketIngress.Ignore
        if (!SessionSecurity.verify(envelope.payload, envelope.playerId, envelope.seq, envelope.signature)) {
            return PacketIngress.Ignore
        }
        if (!peerRegistry.validateSequence(envelope.playerId, envelope.seq)) {
            return PacketIngress.Ignore
        }

        val payload = envelope.payload
        return when (payload.firstOrNull()) {
            PktType.JOIN_REQ.id -> PacketIngress.JoinRequest(pkt.address, envelope.playerId, payload)
            PktType.JOIN_ACK.id -> PacketIngress.JoinAck(envelope, pkt.address, ip, payload)
            else -> {
                if (peerRegistry.isAuthorized(envelope.playerId, ip)) {
                    PacketIngress.Routed(envelope, pkt.address)
                } else {
                    PacketIngress.Ignore
                }
            }
        }
    }
}

sealed interface PacketIngress {
    data object Ignore : PacketIngress
    data class Rejoin(val raw: ByteArray, val ip: String) : PacketIngress
    data class JoinRequest(val address: InetAddress, val playerId: Byte, val payload: ByteArray) : PacketIngress
    data class JoinAck(val envelope: SecureEnvelope, val address: InetAddress, val ip: String, val payload: ByteArray) : PacketIngress
    data class Routed(val envelope: SecureEnvelope, val address: InetAddress) : PacketIngress
}
