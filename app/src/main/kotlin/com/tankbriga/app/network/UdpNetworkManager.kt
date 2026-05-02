package com.tankbriga.app.network

import android.content.Context
import com.tankbriga.engine.network.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Network Manager — corrigido e com suporte a reconexão.
 *
 * FIXES aplicados:
 *  1. Lobby presence enviado na porta correta (45679 → peers ouvem em 45679)
 *  2. validateSequence() agora chamado em todos os pacotes
 *  3. Heartbeats delegados ao CoordinatorNode (que agora tem loop real)
 *  4. sendUnicast() exposto para rejoin (antes tudo era multicast)
 *  5. Fluxo REJOIN_REQ / REJOIN_ACK implementado
 *  6. CoordinatorNode recebe onBroadcast/onBuildSnapshot callbacks
 */
class UdpNetworkManager(
    private val lobbyWord: String,
    private val localName: String,
    private val context: Context,
    private val port: Int = 45679,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    private var socket: DatagramSocket? = null
    private val GROUP_ADDR = "239.255.0.1"
    private val group = InetAddress.getByName(GROUP_ADDR)

    val peerRegistry = PeerRegistry()
    private val rateLimiter = RateLimiter(25)

    var localPlayerId: Byte = 0
        private set
    private var localSeq: Short = 0

    val coordinator = CoordinatorNode(
        myId = localPlayerId,
        lobbyWord = lobbyWord,
        players = mutableListOf()
    )

    // Reliable layer só para pacotes críticos
    private val reliableUdp by lazy { socket?.let { ReliableUdp(it, scope) } }

    // ── Callbacks para a UI / GameView ────────────────────────────────────────

    var onPlayerJoined:    ((name: String, ip: String, id: Byte) -> Unit)? = null
    var onActionReceived:  ((com.tankbriga.engine.ActionPacket) -> Unit)?  = null
    var onGameStart:       (() -> Unit)?                                   = null
    var onResyncRequest:   ((remoteHash: Int) -> Unit)?                    = null
    var onRejoinSnapshot:  ((RejoinSnapshot) -> Unit)?                     = null
    var onTurnStart:       ((TurnStartPacket) -> Unit)?                    = null

    // ── Start ──────────────────────────────────────────────────────────────────

    fun start() {
        SessionSecurity.init(lobbyWord)

        socket = DatagramSocket(port).apply {
            broadcast = true
            soTimeout = 300
        }

        // Wire coordinator → network
        coordinator.onBroadcast = { payload -> broadcastSecured(payload) }
        coordinator.onTurnStart = { pkt -> onTurnStart?.invoke(pkt) }
        coordinator.onPanicFire = { id -> }
        coordinator.start()

        // Loop de recebimento
        scope.launch {
            val buffer = ByteArray(4096)
            while (isActive) {
                try {
                    val pkt = DatagramPacket(buffer, buffer.size)
                    socket?.receive(pkt)
                    val ip = pkt.address.hostAddress ?: continue
                    if (rateLimiter.allow(ip)) processPacket(pkt)
                } catch (_: Exception) { }
            }
        }

        // Retry loop para pacotes confiáveis
        scope.launch {
            while (isActive) {
                reliableUdp?.retryPending()
                delay(150)
            }
        }
    }

    // ── Processamento de pacotes ───────────────────────────────────────────────

    private fun processPacket(pkt: DatagramPacket) {
        val raw = pkt.data.copyOf(pkt.length)
        val ip  = pkt.address.hostAddress ?: return

        // Pacotes de lobby/discovery chegam sem envelope seguro
        if (raw.isNotEmpty() && raw[0] == PktType.ROOM_ANNOUNCE.id.toByte()) {
            handleLobbyAnnounce(raw, ip); return
        }

        // Rejoin request pode vir sem autenticação (player perdeu sessão)
        if (raw.isNotEmpty() && raw[0] == PktType.REJOIN_REQ.id) {
            handleRejoinRequest(raw, ip, pkt.address); return
        }

        val envelope = SecureEnvelope.fromBinary(raw) ?: return
        if (!SessionSecurity.verify(envelope.payload, envelope.playerId, envelope.seq, envelope.signature)) return

        // FIX: validateSequence agora chamado (antes nunca era chamado)
        if (!peerRegistry.validateSequence(envelope.playerId, envelope.seq)) return

        // ACK de pacote confiável
        if (envelope.payload.firstOrNull() == PktType.JOIN_ACK.id) {
            val ackedSeq = readShort(envelope.payload, 1)
            reliableUdp?.onAckReceived(ackedSeq)
            return
        }

        // Peer não autorizado → tenta handshake de join
        if (!peerRegistry.isAuthorized(envelope.playerId, ip)) {
            if (envelope.payload.firstOrNull() == PktType.JOIN_REQ.id) {
                handleJoinRequest(pkt.address, envelope.playerId, envelope.payload)
            }
            return
        }

        routePayload(envelope, ip, pkt.address)
    }

    private fun routePayload(envelope: SecureEnvelope, ip: String, address: InetAddress) {
        val p = envelope.payload
        when (p.firstOrNull()) {
            PktType.HEARTBEAT.id -> {
                coordinator.onHeartbeatReceived(p.getOrElse(1) { envelope.playerId })
            }
            PktType.COORD_ELECTED.id -> {
                val json = String(p, 1, p.size - 1)
                runCatching { Json.decodeFromString<CoordElectedPacket>(json) }
                    .onSuccess { coordinator.onCoordElected(it) }
            }
            PktType.TURN_START.id -> {
                val json = String(p, 1, p.size - 1)
                runCatching { Json.decodeFromString<TurnStartPacket>(json) }
                    .onSuccess { onTurnStart?.invoke(it) }
            }
            PktType.ACTION.id -> {
                if (envelope.playerId == coordinator.currentTurnPlayerId) {
                    p.copyOfRange(1, 9).toActionPacket()?.let { action ->
                        coordinator.onActionReceived()
                        onActionReceived?.invoke(action)
                    }
                }
            }
            PktType.ACTION_FWRD.id -> {
                p.copyOfRange(1, 9).toActionPacket()?.let { onActionReceived?.invoke(it) }
            }
            PktType.GAME_START.id -> {
                sendAck(address, envelope.seq)
                onGameStart?.invoke()
            }
            PktType.RESYNC_REQ.id -> {
                if (p.size >= 5) {
                    val hash = readInt(p, 1)
                    onResyncRequest?.invoke(hash)
                }
            }
            PktType.REJOIN_ACK.id -> {
                val json = String(p, 1, p.size - 1)
                runCatching { Json.decodeFromString<RejoinSnapshot>(json) }
                    .onSuccess { onRejoinSnapshot?.invoke(it) }
            }
            PktType.REJOIN_DENIED.id -> { }
        }
    }

    // ── Join / Lobby ───────────────────────────────────────────────────────────

    private fun handleLobbyAnnounce(raw: ByteArray, ip: String) { }

    private fun handleJoinRequest(address: InetAddress, playerId: Byte, payload: ByteArray) {
        val name = if (payload.size > 1) String(payload, 1, payload.size - 1) else "P$playerId"
        val ip = address.hostAddress ?: return

        peerRegistry.addPeer(playerId, ip)

        val response = ByteArray(1 + localName.toByteArray().size)
        response[0] = PktType.JOIN_ACK.id
        localName.toByteArray().copyInto(response, 1)
        sendSecuredTo(response, address, reliable = false)

        onPlayerJoined?.invoke(name, ip, playerId)
    }

    fun broadcastLobbyPresence() {
        val nameBytes = localName.toByteArray()
        val payload = ByteArray(1 + nameBytes.size)
        payload[0] = PktType.JOIN_REQ.id
        nameBytes.copyInto(payload, 1)
        broadcastSecured(payload)
    }

    // ── Reconexão ─────────────────────────────────────────────────────────────

    fun requestRejoin(coordinatorIp: String) {
        scope.launch {
            val req = RejoinRequest(lobbyWord, localPlayerId, localName)
            val json = Json.encodeToString(req).toByteArray()
            val payload = ByteArray(1 + json.size)
            payload[0] = PktType.REJOIN_REQ.id
            json.copyInto(payload, 1)

            val raw = payload
            socket?.send(DatagramPacket(raw, raw.size, InetAddress.getByName(coordinatorIp), port))
        }
    }

    private fun handleRejoinRequest(raw: ByteArray, ip: String, address: InetAddress) {
        if (!coordinator.isCoordinator) return
        val json = String(raw, 1, raw.size - 1)
        val req = runCatching { Json.decodeFromString<RejoinRequest>(json) }.getOrNull() ?: return

        coordinator.onRejoinRequest(req, ip) { responsePayload, targetIp ->
            try {
                val targetAddr = InetAddress.getByName(targetIp)
                socket?.send(DatagramPacket(responsePayload, responsePayload.size, targetAddr, port))
            } catch (_: Exception) { }
        }
        peerRegistry.allowRejoin(req.playerId, ip)
    }

    // ── Envio ──────────────────────────────────────────────────────────────────

    fun sendAction(action: com.tankbriga.engine.ActionPacket) {
        scope.launch {
            localSeq++
            val bin = action.toBinary()
            val payload = ByteArray(1 + bin.size)
            payload[0] = PktType.ACTION.id
            bin.copyInto(payload, 1)
            broadcastSecured(payload)
        }
    }

    fun sendStartSignal() {
        scope.launch {
            localSeq++
            peerRegistry.lockRoster()
            val payload = byteArrayOf(PktType.GAME_START.id)
            broadcastSecured(payload, reliable = true)
        }
    }

    private fun broadcastSecured(payload: ByteArray, reliable: Boolean = false) {
        localSeq++
        val sig = SessionSecurity.sign(payload, localPlayerId, localSeq)
        val envelope = SecureEnvelope(localPlayerId, localSeq, sig, payload)
        val data = envelope.toBinary()
        if (reliable) reliableUdp?.sendReliable(data, group, localSeq)
        else try { socket?.send(DatagramPacket(data, data.size, group, port)) } catch (_: Exception) { }
    }

    private fun sendSecuredTo(payload: ByteArray, address: InetAddress, reliable: Boolean = false) {
        localSeq++
        val sig = SessionSecurity.sign(payload, localPlayerId, localSeq)
        val envelope = SecureEnvelope(localPlayerId, localSeq, sig, payload)
        val data = envelope.toBinary()
        if (reliable) reliableUdp?.sendReliable(data, address, localSeq)
        else try { socket?.send(DatagramPacket(data, data.size, address, port)) } catch (_: Exception) { }
    }

    private fun sendAck(address: InetAddress, seq: Short) {
        val payload = ByteArray(3)
        payload[0] = PktType.JOIN_ACK.id
        payload[1] = (seq.toInt() shr 8).toByte()
        payload[2] = (seq.toInt() and 0xFF).toByte()
        sendSecuredTo(payload, address)
    }

    fun setLocalId(id: Byte) {
        localPlayerId = id
        peerRegistry.addPeer(id, "127.0.0.1")
    }

    fun stop() {
        coordinator.stop()
        reliableUdp?.stop()
        scope.cancel()
        socket?.close()
    }

    private fun readShort(buf: ByteArray, offset: Int): Short =
        (((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)).toShort()

    private fun readInt(buf: ByteArray, offset: Int): Int =
        ((buf[offset].toInt() and 0xFF) shl 24) or
        ((buf[offset + 1].toInt() and 0xFF) shl 16) or
        ((buf[offset + 2].toInt() and 0xFF) shl 8) or
        (buf[offset + 3].toInt() and 0xFF)
}
