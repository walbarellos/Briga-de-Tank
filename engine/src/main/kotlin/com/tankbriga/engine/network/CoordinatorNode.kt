package com.tankbriga.engine.network

import com.tankbriga.engine.DeterministicRng
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * AUTHORITATIVE TURN SEQUENCER.
 * In a Mesh network, one node (Coordinator) manages the clock and turn order.
 */
class CoordinatorNode(
    var myId: Byte,
    private val lobbyWord: String,
    private val players: MutableList<Byte>,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {
    private var currentCoordinatorId: Byte = myId
    private var epoch: Short = 0
    private val lastHeartbeat = mutableMapOf<Byte, Long>()
    
    // Authorization: only these players are allowed to fire
    val authorizedPlayers = mutableSetOf<Byte>()

    var currentTurnPlayerId: Byte = -1
        private set
    var turnNumber: Short = 0
        private set

    private var timeoutJob: Job? = null
    private var heartbeatJob: Job? = null
    private var watchdogJob: Job? = null
    var failoverEnabled: Boolean = false

    val isCoordinator: Boolean get() = (myId == currentCoordinatorId)

    var onBroadcast: ((ByteArray) -> Unit)? = null
    var onTurnStart: ((TurnStartPacket) -> Unit)? = null
    var onPanicFire: ((Byte) -> Unit)? = null
    var onBuildSnapshot: (() -> RejoinSnapshot)? = null

    fun start() {
        val initialPlayers = players.toList()
        authorizedPlayers.clear()
        registerPlayer(myId)
        initialPlayers.forEach { registerPlayer(it) }
        
        updateCoordinator()
        startHeartbeatLoop()
        startWatchdogLoop()
    }

    fun setInitialCoordinator(id: Byte) {
        if (!players.contains(id)) players.add(id)
        players.sort()
        currentCoordinatorId = id
        lastHeartbeat.putIfAbsent(id, System.currentTimeMillis())
    }

    fun registerPlayer(playerId: Byte) {
        if (authorizedPlayers.add(playerId)) {
            if (!players.contains(playerId)) {
                players.add(playerId)
                players.sort()
            }
        }
        lastHeartbeat.putIfAbsent(playerId, System.currentTimeMillis())
        updateCoordinator()
    }

    private fun updateCoordinator() {
        val sorted = authorizedPlayers.toList().sorted()
        currentCoordinatorId = sorted.firstOrNull() ?: myId
    }

    fun stop() {
        timeoutJob?.cancel()
        heartbeatJob?.cancel()
        watchdogJob?.cancel()
    }

    private fun startHeartbeatLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                val payload = byteArrayOf(PktType.HEARTBEAT.id, myId)
                onBroadcast?.invoke(payload)
                lastHeartbeat[myId] = System.currentTimeMillis()
                delay(1000)
            }
        }
    }

    fun onHeartbeatReceived(playerId: Byte) {
        lastHeartbeat[playerId] = System.currentTimeMillis()
        registerPlayer(playerId)
    }

    private fun startWatchdogLoop() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive) {
                checkCoordinatorHealth()
                delay(1500)
            }
        }
    }

    internal fun checkCoordinatorHealth() {
        if (!failoverEnabled) return
        val now = System.currentTimeMillis()
        val coordinatorAlive = lastHeartbeat[currentCoordinatorId]?.let { now - it < 4000 } ?: (currentCoordinatorId == myId)
        if (!coordinatorAlive) electNewCoordinator()
    }

    private fun electNewCoordinator() {
        val now = System.currentTimeMillis()
        val activePlayers = authorizedPlayers.filter {
            lastHeartbeat[it]?.let { t -> now - t < 5000 } ?: (it == myId)
        }.sorted()
        
        val candidate = activePlayers.firstOrNull() ?: myId
        if (candidate == currentCoordinatorId) return

        currentCoordinatorId = candidate
        epoch++

        val msg = Json.encodeToString(CoordElectedPacket(candidate, epoch)).toByteArray()
        val payload = ByteArray(1 + msg.size)
        payload[0] = PktType.COORD_ELECTED.id
        System.arraycopy(msg, 0, payload, 1, msg.size)
        onBroadcast?.invoke(payload)

        if (isCoordinator) {
            scope.launch {
                delay(500)
                startNextTurn()
            }
        }
    }

    fun onCoordElected(pkt: CoordElectedPacket) {
        if (pkt.epoch >= epoch) {
            epoch = pkt.epoch
            currentCoordinatorId = pkt.newCoordId
            timeoutJob?.cancel()
        }
    }

    fun onTurnStarted(pkt: TurnStartPacket) {
        turnNumber = pkt.turnNumber
        currentTurnPlayerId = pkt.playerId
        timeoutJob?.cancel()
    }

    /**
     * Advances the match clock. MUST be consistent on all devices.
     * We sort by ID to ensure identical order regardless of map position.
     */
    fun startNextTurn() {
        if (!isCoordinator) return
        
        turnNumber++
        val sortedPlayers = authorizedPlayers.toList().sorted()
        if (sortedPlayers.isEmpty()) return

        currentTurnPlayerId = sortedPlayers[((turnNumber - 1) % sortedPlayers.size).toInt()]

        // Use turnNumber as seed for wind consistency
        DeterministicRng.init(lobbyWord, turnNumber.toInt())
        val wind = DeterministicRng.windForTurn()

        val pkt = TurnStartPacket(
            playerId = currentTurnPlayerId,
            windValue = wind,
            turnNumber = turnNumber,
            serverStartMs = System.currentTimeMillis()
        )
        val json = Json.encodeToString(pkt).toByteArray()
        val payload = ByteArray(1 + json.size)
        payload[0] = PktType.TURN_START.id
        System.arraycopy(json, 0, payload, 1, json.size)
        
        onBroadcast?.invoke(payload)
        onTurnStart?.invoke(pkt)

        // PANIC TIMER: 18s (15s game + 3s network buffer)
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(18_000)
            triggerPanicFire(currentTurnPlayerId)
        }
    }

    fun onActionReceived() {
        timeoutJob?.cancel()
    }

    private fun triggerPanicFire(playerId: Byte) {
        if (!isCoordinator) return
        DeterministicRng.init(lobbyWord, turnNumber.toInt() + 999)
        val randomAngle = (45 + DeterministicRng.nextFloat() * 45).toInt().toShort()
        val randomPower = (40 + (DeterministicRng.nextFloat() * 30)).toInt().toByte()

        val action = com.tankbriga.engine.ActionPacket(playerId, (randomAngle * 10).toShort(), randomPower, 0, 0, turnNumber)
        val bin = action.toBinary()
        val payload = ByteArray(1 + bin.size)
        payload[0] = PktType.ACTION_FWRD.id // Coordinator-forced action
        System.arraycopy(bin, 0, payload, 1, bin.size)
        onBroadcast?.invoke(payload)
    }

    fun onPlayerEliminated(id: Byte) {
        lastHeartbeat.remove(id)
        // In this simple mesh, we keep them in authorizedPlayers to keep the index logic stable,
        // but startNextTurn will skip them if we add HP checks.
    }

    fun onRejoinRequest(req: RejoinRequest, targetIp: String, sendUnicast: (ByteArray, String) -> Unit) {
        if (!isCoordinator) return
        if (req.lobbyWord != lobbyWord) {
            sendUnicast(byteArrayOf(PktType.REJOIN_DENIED.id), targetIp)
            return
        }
        val snapshotJson = onBuildSnapshot?.invoke() ?: return
        val json = Json.encodeToString(snapshotJson).toByteArray()
        val payload = ByteArray(1 + json.size)
        payload[0] = PktType.REJOIN_ACK.id
        System.arraycopy(json, 0, payload, 1, json.size)
        sendUnicast(payload, targetIp)
        lastHeartbeat[req.playerId] = System.currentTimeMillis()
    }
}
