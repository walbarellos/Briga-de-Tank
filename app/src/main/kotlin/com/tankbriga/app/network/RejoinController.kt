package com.tankbriga.app.network

import com.tankbriga.engine.network.CoordinatorNode
import com.tankbriga.engine.network.ProtocolPayloads
import com.tankbriga.engine.network.RejoinRequest
import kotlinx.serialization.json.Json
import java.net.InetAddress

class RejoinController(
    private val lobbyWord: String,
    private val localName: String,
    private val peerRegistry: PeerRegistry,
    private val coordinator: CoordinatorNode,
    private val localPlayerId: () -> Byte,
    private val sendRawTo: (ByteArray, InetAddress) -> Unit
) {
    fun request(coordinatorIp: String) {
        val req = RejoinRequest(lobbyWord, localPlayerId(), localName)
        sendRawTo(ProtocolPayloads.rejoinRequest(req), InetAddress.getByName(coordinatorIp))
    }

    fun handle(raw: ByteArray, ip: String) {
        if (!coordinator.isCoordinator) return
        val req = decodeRequest(raw) ?: return
        coordinator.onRejoinRequest(req, ip) { responsePayload, targetIp ->
            runCatching { sendRawTo(responsePayload, InetAddress.getByName(targetIp)) }
        }
        peerRegistry.allowRejoin(req.playerId, ip)
    }

    private fun decodeRequest(raw: ByteArray): RejoinRequest? {
        if (raw.size <= 1) return null
        return runCatching {
            Json.decodeFromString<RejoinRequest>(String(raw, 1, raw.size - 1))
        }.getOrNull()
    }
}
