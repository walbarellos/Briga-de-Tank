package com.tankbriga.engine.network

import com.tankbriga.engine.DeterministicRng
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Sequenciador de turnos e detector de falhas.
 *
 * FIXES:
 *  - Heartbeat loop agora realmente envia pacotes (antes era só ler)
 *  - startNextTurn() agora chama onBroadcast (antes só fazia println)
 *  - triggerPanicFire() implementado (antes era comentário)
 *  - electNewCoordinator() agora notifica os peers via onBroadcast
 *  - Suporte a REJOIN: player que voltou recebe snapshot e continua no turno
 */
class CoordinatorNode(
    val myId: Byte,
    private val lobbyWord: String,
    private val players: MutableList<Byte>,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {
    private var currentCoordinatorId: Byte = players.firstOrNull() ?: 0
    private var epoch: Short = 0
    private val lastHeartbeat = mutableMapOf<Byte, Long>()

    var currentTurnPlayerId: Byte = -1
        private set
    var turnNumber: Short = 0
        private set

    private var timeoutJob: Job? = null
    private var heartbeatJob: Job? = null
    private var watchdogJob: Job? = null

    var isCoordinator: Boolean = (myId == currentCoordinatorId)
        private set

    // Callbacks injetados pelo UdpNetworkManager
    var onBroadcast: ((ByteArray) -> Unit)? = null
    var onTurnStart: ((TurnStartPacket) -> Unit)? = null      // para atualizar UI local
    var onPanicFire: ((Byte) -> Unit)? = null                 // para disparar ação automática

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun start() {
        startHeartbeatLoop()
        startWatchdogLoop()
    }

    fun stop() {
        timeoutJob?.cancel()
        heartbeatJob?.cancel()
        watchdogJob?.cancel()
    }

    // ── Heartbeat: envia a cada 1s, detecta queda em 3s ──────────────────────

    private fun startHeartbeatLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                val payload = byteArrayOf(PktType.HEARTBEAT.id, myId)
                onBroadcast?.invoke(payload)
                lastHeartbeat[myId] = System.currentTimeMillis() // conta o próprio
                delay(1000)
            }
        }
    }

    fun onHeartbeatReceived(playerId: Byte) {
        lastHeartbeat[playerId] = System.currentTimeMillis()
        if (!players.contains(playerId)) players.add(playerId)
    }

    // ── Watchdog: verifica coordinator a cada 1.5s ────────────────────────────

    private fun startWatchdogLoop() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive) {
                checkCoordinatorHealth()
                delay(1500)
            }
        }
    }

    private fun checkCoordinatorHealth() {
        val now = System.currentTimeMillis()
        val coordinatorAlive = lastHeartbeat[currentCoordinatorId]?.let { now - it < 3000 } ?: false

        if (!coordinatorAlive && currentCoordinatorId != myId) {
            electNewCoordinator()
        }
    }

    private fun electNewCoordinator() {
        val now = System.currentTimeMillis()
        val activePlayers = players.filter {
            lastHeartbeat[it]?.let { t -> now - t < 5000 } ?: (it == myId)
        }
        val candidate = activePlayers.minOrNull() ?: myId
        if (candidate == currentCoordinatorId) return // nada mudou

        currentCoordinatorId = candidate
        isCoordinator = (myId == candidate)
        epoch++

        // Notifica todos os peers da eleição
        val msg = Json.encodeToString(CoordElectedPacket(candidate, epoch)).toByteArray()
        val payload = ByteArray(1 + msg.size)
        payload[0] = PktType.COORD_ELECTED.id
        System.arraycopy(msg, 0, payload, 1, msg.size)
        onBroadcast?.invoke(payload)

        if (isCoordinator) {
            // Coordinator novo: agenda o próximo turno se o antigo tinha um turno aberto
            scope.launch {
                delay(500) // grace period para os peers processarem a eleição
                startNextTurn()
            }
        }
    }

    fun onCoordElected(pkt: CoordElectedPacket) {
        if (pkt.epoch > epoch) { // aceita só epochs maiores (evita split-brain)
            epoch = pkt.epoch
            currentCoordinatorId = pkt.newCoordId
            isCoordinator = (myId == pkt.newCoordId)
            timeoutJob?.cancel() // cancela qualquer timeout local
        }
    }

    // ── Turn management ────────────────────────────────────────────────────────

    fun startNextTurn() {
        if (!isCoordinator) return

        turnNumber++

        // Rotação apenas entre players VIVOS (não apenas index cego)
        val alivePlayers = players.filter { aliveIds.contains(it) }
        if (alivePlayers.isEmpty()) return

        currentTurnPlayerId = alivePlayers[(turnNumber % alivePlayers.size).toInt()]

        DeterministicRng.init(lobbyWord, turnNumber.toInt())
        val wind = DeterministicRng.windForTurn()

        val pkt = TurnStartPacket(currentTurnPlayerId, wind, turnNumber)
        val json = Json.encodeToString(pkt).toByteArray()
        val payload = ByteArray(1 + json.size)
        payload[0] = PktType.TURN_START.id
        System.arraycopy(json, 0, payload, 1, json.size)
        onBroadcast?.invoke(payload)
        onTurnStart?.invoke(pkt)

        // Timer de 15s — panic fire se expirar
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(15_000)
            triggerPanicFire(currentTurnPlayerId)
        }
    }

    fun onActionReceived() {
        // Player atirou — cancela o timeout
        timeoutJob?.cancel()
    }

    private fun triggerPanicFire(playerId: Byte) {
        if (!isCoordinator) return

        // Gera ângulo/potência aleatórios com o RNG determinístico
        // Todos os devices chegam ao mesmo valor porque usam o mesmo seed do turno
        DeterministicRng.init(lobbyWord, turnNumber.toInt() + 999)
        val randomAngle = (DeterministicRng.nextFloat() * 1800).toInt().toShort()
        val randomPower = (30 + (DeterministicRng.nextFloat() * 40)).toInt().toByte()

        val action = com.tankbriga.engine.ActionPacket(
            playerId = playerId,
            angleTenths = randomAngle,
            power = randomPower,
            shotType = 0,
            moveDir = 0,
            seq = nextSeq()
        )

        // Broadcast o ACTION como se fosse do player que deixou passar
        val bin = action.toBinary()
        val payload = ByteArray(1 + bin.size)
        payload[0] = PktType.ACTION.id
        System.arraycopy(bin, 0, payload, 1, bin.size)
        onBroadcast?.invoke(payload)
        onPanicFire?.invoke(playerId)
    }

    // ── Reconexão ─────────────────────────────────────────────────────────────

    // IDs dos players vivos (atualizado pelo GameState via callback)
    val aliveIds = mutableSetOf<Byte>().also { it.addAll(players) }

    fun onPlayerEliminated(id: Byte) { aliveIds.remove(id) }

    /**
     * Processa pedido de rejoin.
     * Retorna snapshot se a palavra bater e o player estava na partida.
     * Chama onBroadcast com REJOIN_ACK ou REJOIN_DENIED.
     */
    fun onRejoinRequest(req: RejoinRequest, targetIp: String, sendUnicast: (ByteArray, String) -> Unit) {
        if (!isCoordinator) return

        val validWord = req.lobbyWord == lobbyWord
        val wasPlayer = players.contains(req.playerId)

        if (!validWord || !wasPlayer) {
            sendUnicast(byteArrayOf(PktType.REJOIN_DENIED.id), targetIp)
            return
        }

        // Re-autentica o peer (será re-adicionado pelo PeerRegistry no UdpNetworkManager)
        val snapshotJson = buildSnapshot()
        val json = Json.encodeToString(snapshotJson).toByteArray()
        val payload = ByteArray(1 + json.size)
        payload[0] = PktType.REJOIN_ACK.id
        System.arraycopy(json, 0, payload, 1, json.size)
        sendUnicast(payload, targetIp)

        // Re-adiciona heartbeat para não trigger eleição
        lastHeartbeat[req.playerId] = System.currentTimeMillis()
    }

    // Esse callback é injetado pelo GameState para montar o snapshot real
    var onBuildSnapshot: (() -> RejoinSnapshot)? = null

    private fun buildSnapshot(): RejoinSnapshot {
        return onBuildSnapshot?.invoke() ?: RejoinSnapshot(
            turnNumber, currentTurnPlayerId,
            emptyList(), emptyList(), emptyList(),
            emptyList(), emptyList(), emptyList()
        )
    }

    // ── Utils ─────────────────────────────────────────────────────────────────

    private var seqCounter: Short = 0
    private fun nextSeq(): Short { seqCounter++; return seqCounter }
}
