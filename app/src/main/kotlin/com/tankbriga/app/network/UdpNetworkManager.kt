package com.tankbriga.app.network

import android.content.Context
import android.util.Log
import com.tankbriga.engine.network.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.SocketTimeoutException

/** Coordinates multiplayer services while delegating transport, routing, membership and reliability. */
class UdpNetworkManager(
    private val lobbyWord: String,
    private val localName: String,
    private val context: Context,
    private val port: Int = 45679,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    private val transport = AndroidUdpTransport(context, port)

    val peerRegistry = PeerRegistry()
    private val rateLimiter = RateLimiter(30)
    private val ingressGate = PacketIngressGate(peerRegistry) { localPlayerId }

    var localPlayerId: Byte = 0
        private set
    private var isLobbyLoopActive = false
    private val ackTracker = NetworkAckTracker()
    private val deduplicator = PacketDeduplicator()
    private val debugState = NetworkDebugState()
    private val retryBroadcaster = RetryBroadcaster(scope)
    private val reliableUdp by lazy { 
        transport.socket?.let { 
            ReliableUdp(
                it, 
                scope, 
                onRttMeasured = { rtt -> debugState.updateRtt(rtt) },
                onPacketLost = { debugState.packetsLost++ }
            ) 
        } 
    }
    private val secureSender = SecurePacketSender(
        transport = transport,
        reliableUdp = { reliableUdp },
        localPlayerId = { localPlayerId },
        onPacketSent = { debugState.packetsSent++ }
    )

    val coordinator = CoordinatorNode(
        myId = localPlayerId,
        lobbyWord = lobbyWord,
        players = mutableListOf()
    )

    private val gameplayRouter = GameplayPacketRouter(
        lobbyWord = lobbyWord,
        coordinator = coordinator,
        ackTracker = ackTracker,
        deduplicator = deduplicator,
        debugState = debugState,
        isActionTurnAcceptable = { isActionTurnAcceptable(it) },
        sendProtocolAck = { address, type, turnNumber, shotId -> sendProtocolAck(address, type, turnNumber, shotId) },
        sendTransportAck = { address, seq -> sendAck(address, seq) },
        stopLobbyPresence = { stopLobbyPresenceLoop() },
        lockRoster = { peerRegistry.lockRoster() },
        publishDebug = { publishDebug() },
        forwardAction = { sendActionForward(it) },
        onActionReceived = { onActionReceived?.invoke(it) },
        onGameStart = { onGameStart?.invoke() },
        onTurnStart = { onTurnStart?.invoke(it) },
        onLobbySnapshot = { onLobbySnapshot?.invoke(it) },
        onShotResolved = { onShotResolved?.invoke(it) },
        onAimState = { onAimState?.invoke(it) },
        onRejoinSnapshot = { onRejoinSnapshot?.invoke(it) }
    )
    private val lobbyMembership = LobbyMembershipController(
        localName = localName,
        peerRegistry = peerRegistry,
        coordinator = coordinator,
        localPlayerId = { localPlayerId },
        setLocalPlayerId = { setLocalId(it) },
        onReliableAck = { reliableUdp?.onAckReceived(it) },
        sendSecuredTo = { payload, address -> secureSender.sendTo(payload, address) },
        onLocalIdAssigned = { oldId, newId -> onLocalIdAssigned?.invoke(oldId, newId) },
        onPlayerJoined = { name, ip, id -> onPlayerJoined?.invoke(name, ip, id) }
    )
    private val rejoinController = RejoinController(
        lobbyWord = lobbyWord,
        localName = localName,
        peerRegistry = peerRegistry,
        coordinator = coordinator,
        localPlayerId = { localPlayerId },
        sendRawTo = { payload, address -> transport.sendTo(payload, address) }
    )
    var onPlayerJoined:    ((name: String, ip: String, id: Byte) -> Unit)? = null
    var onActionReceived:  ((com.tankbriga.engine.ActionPacket) -> Unit)?  = null
    var onGameStart:       (() -> Unit)?                                   = null
    var onResyncRequest:   ((remoteHash: Int) -> Unit)?                    = null
    var onRejoinSnapshot:  ((RejoinSnapshot) -> Unit)?                     = null
    var onTurnStart:       ((TurnStartPacket) -> Unit)?                    = null
    var onLobbySnapshot:   ((LobbySnapshot) -> Unit)?                      = null
    var onShotResolved:    ((ShotResolvePacket) -> Unit)?                  = null
    var onLocalIdAssigned: ((oldId: Byte, newId: Byte) -> Unit)?           = null
    var onAimState:        ((AimStatePacket) -> Unit)?                     = null
    var onDebugChanged:    ((String) -> Unit)?                             = null

    fun start() {
        SessionSecurity.init(lobbyWord)
        transport.start()

        coordinator.onBroadcast = { payload ->
            if (payload.firstOrNull() == PktType.TURN_START.id) broadcastTurnStartReliably(payload)
            else secureSender.broadcast(payload)
        }
        coordinator.onTurnStart = { pkt -> onTurnStart?.invoke(pkt) }
        coordinator.start()

        // Receiver loop
        scope.launch {
            val buffer = ByteArray(4096)
            while (isActive) {
                try {
                    val pkt = transport.receive(buffer) ?: continue
                    val ip = pkt.address.hostAddress ?: continue
                    if (rateLimiter.allow(ip)) processPacket(pkt)
                } catch (_: SocketTimeoutException) {
                } catch (e: Exception) {
                    Log.w("TankBriga", "UDP receive loop error", e)
                }
            }
        }

        // Reliable retry loop
        scope.launch {
            while (isActive) {
                reliableUdp?.retryPending()
                delay(150)
            }
        }

        startLobbyPresenceLoop()
    }

    private fun startLobbyPresenceLoop() {
        isLobbyLoopActive = true
        scope.launch {
            while (isLobbyLoopActive && isActive) {
                broadcastLobbyPresence()
                delay(2000)
            }
        }
    }

    fun stopLobbyPresenceLoop() {
        isLobbyLoopActive = false
    }

    private fun processPacket(pkt: DatagramPacket) {
        when (val ingress = ingressGate.classify(pkt)) {
            PacketIngress.Ignore -> return
            is PacketIngress.Rejoin -> rejoinController.handle(ingress.raw, ingress.ip)
            is PacketIngress.JoinRequest -> lobbyMembership.handleJoinRequest(ingress.address, ingress.playerId, ingress.payload)
            is PacketIngress.JoinAck -> lobbyMembership.handleJoinAck(ingress)
            is PacketIngress.Routed -> gameplayRouter.route(ingress.envelope, ingress.address)
        }
    }

    fun broadcastLobbyPresence() {
        secureSender.broadcast(ProtocolPayloads.joinRequest(localName))
    }

    fun requestRejoin(coordinatorIp: String) {
        scope.launch {
            rejoinController.request(coordinatorIp)
        }
    }

    fun sendAction(action: com.tankbriga.engine.ActionPacket) {
        scope.launch {
            val payload = ProtocolPayloads.action(PktType.ACTION, action)
            if (coordinator.isCoordinator && isActionTurnAcceptable(action.playerId)) {
                coordinator.onActionReceived()
                sendActionForward(action)
                onActionReceived?.invoke(action)
            } else {
                secureSender.broadcast(payload)
                sendPayloadToPeers(payload, excludeIds = setOf(localPlayerId))
            }
        }
    }

    private fun sendActionForward(action: com.tankbriga.engine.ActionPacket) {
        val payload = ProtocolPayloads.action(PktType.ACTION_FWRD, action)
        val expected = expectedAckPeerIds().filter { it != action.playerId }
        ackTracker.beginAction(action.seq)
        debugState.lastActionInfo = "SEND FWD P${action.playerId}#${action.seq}"
        publishDebug()
        retryBroadcaster.launch(
            policy = MultiplayerRetryPolicies.ACTION_FORWARD,
            sendOnce = {
                secureSender.broadcast(payload)
                sendPayloadToPeers(payload, excludeIds = setOf(localPlayerId))
            },
            isComplete = { ackTracker.hasActionAcks(action.seq, expected) }
        )
    }

    private fun isActionTurnAcceptable(playerId: Byte): Boolean {
        val current = coordinator.currentTurnPlayerId
        return current == (-1).toByte() || current == playerId
    }

    fun sendAimState(packet: AimStatePacket) {
        scope.launch {
            val payload = ProtocolPayloads.aimState(packet)
            secureSender.broadcast(payload)
        }
    }

    fun sendStartSignal() {
        scope.launch {
            val deadline = System.currentTimeMillis() + MultiplayerRetryPolicies.START_WAIT_FOR_LOBBY_ACK_MS
            while (expectedAckPeerIds().isNotEmpty() &&
                !ackTracker.hasLobbyAcks(expectedAckPeerIds()) &&
                System.currentTimeMillis() < deadline
            ) {
                delay(100)
            }
            peerRegistry.lockRoster()
            stopLobbyPresenceLoop()
            val payload = byteArrayOf(PktType.GAME_START.id)
            secureSender.broadcast(payload, reliable = true)
            delay(100)
            onGameStart?.invoke()
        }
    }

    fun sendLobbySnapshot(snapshot: LobbySnapshot) {
        scope.launch {
            val payload = ProtocolPayloads.json(PktType.LOBBY_SNAPSHOT, snapshot)
            ackTracker.beginLobby(snapshot.lobbyWord)
            debugState.lastLobbyInfo = "SEND players=${snapshot.players.size}"
            publishDebug()
            retryBroadcaster.launch(
                policy = MultiplayerRetryPolicies.LOBBY_SNAPSHOT,
                sendOnce = {
                    secureSender.broadcast(payload)
                    sendPayloadToPeers(payload, excludeIds = setOf(localPlayerId))
                },
                isComplete = { ackTracker.hasLobbyAcks(expectedAckPeerIds()) }
            )
        }
    }

    fun sendShotResolve(packet: ShotResolvePacket) {
        scope.launch {
            val payload = ProtocolPayloads.json(PktType.SHOT_RESOLVE, packet)
            val expected = expectedAckPeerIds()
            ackTracker.beginResolve(packet.shotId)
            debugState.lastResolveInfo = "SEND P${packet.shooterId}#${packet.shotId}"
            publishDebug()
            retryBroadcaster.launch(
                policy = MultiplayerRetryPolicies.SHOT_RESOLVE,
                sendOnce = {
                    secureSender.broadcast(payload)
                    sendPayloadToPeers(payload, excludeIds = setOf(localPlayerId))
                },
                isComplete = { ackTracker.hasResolveAcks(packet.shotId, expected) }
            )
        }
    }

    fun isCoordinator(): Boolean = coordinator.isCoordinator

    /** Called by GameView when an impact is fully resolved to advance turn. */
    fun notifyImpactResolved() {
        if (coordinator.isCoordinator) {
            scope.launch {
                delay(500)
                coordinator.startNextTurn()
            }
        }
    }

    private fun broadcastTurnStartReliably(payload: ByteArray) {
        val json = String(payload, 1, payload.size - 1)
        val pkt = runCatching { Json.decodeFromString<TurnStartPacket>(json) }.getOrNull()
        ackTracker.beginTurn(pkt?.turnNumber ?: ackTracker.pendingTurnNumber)
        debugState.lastTurnStartInfo = "SEND T${ackTracker.pendingTurnNumber}:P${pkt?.playerId}"
        publishDebug()

        retryBroadcaster.launch(
            policy = MultiplayerRetryPolicies.TURN_START,
            sendOnce = {
                secureSender.broadcast(payload)
                sendPayloadToPeers(payload, excludeIds = setOf(localPlayerId))
            },
            isComplete = { ackTracker.hasTurnAcks(expectedAckPeerIds()) }
        )
    }

    private fun sendPayloadToPeers(payload: ByteArray, excludeIds: Set<Byte> = emptySet()) {
        peerRegistry.getAllPeerIds()
            .filterNot { it in excludeIds }
            .forEach { id ->
                val ip = peerRegistry.getPeerIp(id) ?: return@forEach
                if (ip == "127.0.0.1") return@forEach
                try { secureSender.sendTo(payload, InetAddress.getByName(ip)) } catch (_: Exception) { }
            }
    }

    private fun sendProtocolAck(address: InetAddress, type: PktType, turnNumber: Short, shotId: Short) {
        secureSender.sendTo(ProtocolPayloads.ack(type, turnNumber, shotId), address)
    }

    private fun expectedAckPeerIds(): List<Byte> =
        peerRegistry.getAllPeerIds().filter { id -> id != localPlayerId && peerRegistry.getPeerIp(id) != "127.0.0.1" }

    private fun publishDebug() {
        val expected = expectedAckPeerIds()
        onDebugChanged?.invoke(debugState.render(
            localPlayerId = localPlayerId,
            isCoordinator = coordinator.isCoordinator,
            currentTurnPlayerId = coordinator.currentTurnPlayerId,
            turnNumber = coordinator.turnNumber,
            expectedPeers = expected.size,
            acks = ackTracker
        ))
    }

    private fun sendAck(address: InetAddress, seq: Short) {
        secureSender.sendTo(ProtocolPayloads.transportAck(seq), address)
    }

    fun setLocalId(id: Byte) {
        localPlayerId = id
        coordinator.myId = id
        coordinator.registerPlayer(id)
        coordinator.setInitialCoordinator(0)
        peerRegistry.addPeer(id, "127.0.0.1")
    }

    fun stop() {
        stopLobbyPresenceLoop()
        coordinator.stop()
        reliableUdp?.stop()
        scope.cancel()
        transport.stop()
    }
}
