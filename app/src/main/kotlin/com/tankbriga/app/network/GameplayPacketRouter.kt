package com.tankbriga.app.network

import com.tankbriga.engine.ActionPacket
import com.tankbriga.engine.network.*
import kotlinx.serialization.json.Json
import java.net.InetAddress

class GameplayPacketRouter(
    private val lobbyWord: String,
    private val coordinator: CoordinatorNode,
    private val ackTracker: NetworkAckTracker,
    private val deduplicator: PacketDeduplicator,
    private val debugState: NetworkDebugState,
    private val isActionTurnAcceptable: (Byte) -> Boolean,
    private val sendProtocolAck: (InetAddress, PktType, Short, Short) -> Unit,
    private val sendTransportAck: (InetAddress, Short) -> Unit,
    private val stopLobbyPresence: () -> Unit,
    private val lockRoster: () -> Unit,
    private val publishDebug: () -> Unit,
    private val forwardAction: (ActionPacket) -> Unit,
    private val onActionReceived: (ActionPacket) -> Unit,
    private val onGameStart: () -> Unit,
    private val onTurnStart: (TurnStartPacket) -> Unit,
    private val onLobbySnapshot: (LobbySnapshot) -> Unit,
    private val onShotResolved: (ShotResolvePacket) -> Unit,
    private val onAimState: (AimStatePacket) -> Unit,
    private val onRejoinSnapshot: (RejoinSnapshot) -> Unit
) {
    fun route(envelope: SecureEnvelope, address: InetAddress) {
        val p = envelope.payload
        when (p.firstOrNull()) {
            PktType.HEARTBEAT.id -> coordinator.onHeartbeatReceived(p.getOrElse(1) { envelope.playerId })
            PktType.COORD_ELECTED.id -> decodeJson<CoordElectedPacket>(p)?.let { coordinator.onCoordElected(it) }
            PktType.TURN_START.id -> handleTurnStart(p, address)
            PktType.ACTION.id -> handleAction(p, envelope, address)
            PktType.ACTION_FWRD.id -> handleActionForward(p, address)
            PktType.GAME_START.id -> handleGameStart(address, envelope.seq)
            PktType.LOBBY_SNAPSHOT.id -> handleLobbySnapshot(p, address)
            PktType.SHOT_RESOLVE.id -> handleShotResolve(p, address)
            PktType.AIM_STATE.id -> handleAimState(p)
            PktType.LOBBY_ACK.id, PktType.TURN_ACK.id, PktType.ACTION_ACK.id, PktType.RESOLVE_ACK.id -> handleAck(p, envelope)
            PktType.REJOIN_ACK.id -> decodeJson<RejoinSnapshot>(p)?.let(onRejoinSnapshot)
        }
    }

    private fun handleTurnStart(payload: ByteArray, address: InetAddress) {
        val pkt = decodeJson<TurnStartPacket>(payload) ?: return
        val firstSeen = deduplicator.firstTurn(pkt.turnNumber)
        coordinator.onTurnStarted(pkt)
        coordinator.registerPlayer(pkt.playerId)
        debugState.lastTurnStartInfo = "T${pkt.turnNumber}:P${pkt.playerId}"
        sendProtocolAck(address, PktType.TURN_ACK, pkt.turnNumber, 0)
        publishDebug()
        if (firstSeen) onTurnStart(pkt)
    }

    private fun handleAction(payload: ByteArray, envelope: SecureEnvelope, address: InetAddress) {
        val action = payload.copyOfRange(1, 9).toActionPacket() ?: return
        if (action.playerId != envelope.playerId) return
        if (!deduplicator.firstAction(action.playerId, action.seq, coordinator.turnNumber, forwarded = false)) return
        if (!isActionTurnAcceptable(action.playerId)) return

        coordinator.onActionReceived()
        debugState.lastActionInfo = "P${action.playerId}#${action.seq}"
        sendProtocolAck(address, PktType.ACTION_ACK, coordinator.turnNumber, action.seq)
        publishDebug()
        onActionReceived(action)
        if (coordinator.isCoordinator) forwardAction(action)
    }

    private fun handleActionForward(payload: ByteArray, address: InetAddress) {
        val action = payload.copyOfRange(1, 9).toActionPacket() ?: return
        if (!deduplicator.firstAction(action.playerId, action.seq, coordinator.turnNumber, forwarded = true)) {
            sendProtocolAck(address, PktType.ACTION_ACK, coordinator.turnNumber, action.seq)
            return
        }

        coordinator.onActionReceived()
        debugState.lastActionInfo = "FWD P${action.playerId}#${action.seq}"
        sendProtocolAck(address, PktType.ACTION_ACK, coordinator.turnNumber, action.seq)
        publishDebug()
        onActionReceived(action)
    }

    private fun handleGameStart(address: InetAddress, seq: Short) {
        stopLobbyPresence()
        lockRoster()
        sendTransportAck(address, seq)
        onGameStart()
    }

    private fun handleLobbySnapshot(payload: ByteArray, address: InetAddress) {
        val snapshot = decodeJson<LobbySnapshot>(payload) ?: return
        snapshot.players.forEach { slot -> coordinator.registerPlayer(slot.id) }
        debugState.lastLobbyInfo = "players=${snapshot.players.size}"
        sendProtocolAck(address, PktType.LOBBY_ACK, coordinator.turnNumber, 0)
        publishDebug()
        onLobbySnapshot(snapshot)
    }

    private fun handleShotResolve(payload: ByteArray, address: InetAddress) {
        val resolve = decodeJson<ShotResolvePacket>(payload) ?: return
        if (!deduplicator.firstResolve(resolve.shooterId, resolve.shotId, resolve.turnNumber)) {
            sendProtocolAck(address, PktType.RESOLVE_ACK, resolve.turnNumber, resolve.shotId)
            return
        }

        debugState.lastResolveInfo = "P${resolve.shooterId}#${resolve.shotId}"
        sendProtocolAck(address, PktType.RESOLVE_ACK, resolve.turnNumber, resolve.shotId)
        publishDebug()
        onShotResolved(resolve)
    }

    private fun handleAimState(payload: ByteArray) {
        val aim = decodeJson<AimStatePacket>(payload) ?: return
        debugState.lastAimInfo = "P${aim.playerId}:${aim.angle.toInt()}/${aim.power.toInt()}"
        publishDebug()
        onAimState(aim)
    }

    private fun handleAck(payload: ByteArray, envelope: SecureEnvelope) {
        val ack = decodeJson<AckPacket>(payload) ?: return
        val ackType = PktType.entries.firstOrNull { it.id == payload.first() } ?: return
        ackTracker.recordAck(envelope.playerId, ackType, ack, lobbyWord)
        publishDebug()
    }

    private inline fun <reified T> decodeJson(payload: ByteArray): T? {
        if (payload.size <= 1) return null
        return runCatching {
            Json.decodeFromString<T>(String(payload, 1, payload.size - 1))
        }.getOrNull()
    }
}
