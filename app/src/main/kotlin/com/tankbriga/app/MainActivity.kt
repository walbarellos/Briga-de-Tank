package com.tankbriga.app

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.tankbriga.app.network.UdpNetworkManager
import com.tankbriga.engine.*
import com.tankbriga.engine.network.LobbySnapshot
import com.tankbriga.engine.network.PlayerSlot
import com.tankbriga.engine.network.RejoinSnapshot
import com.tankbriga.app.network.RoomDiscovery
import kotlinx.coroutines.*

/**
 * FINAL SYNC REPAIR:
 *  - Unifies player list management between MainActivity and GameView.
 *  - Forces Coordinator to start the first turn.
 *  - Prevents local turn advancement in multiplayer mode.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var gameView: GameView
    private lateinit var gameState: GameState
    private var networkManager: UdpNetworkManager? = null
    private var roomDiscovery: RoomDiscovery? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            acquireWakeLock()

            val mode          = intent.getStringExtra("MODE") ?: "SOLO"
            val playerName    = intent.getStringExtra("PLAYER_NAME") ?: "VOCÊ"
            val lobbyWord     = intent.getStringExtra("LOBBY_WORD")?.takeIf { it.isNotBlank() } ?: "RECREIO"
            val isJoining     = intent.getBooleanExtra("IS_JOINING", false)
            val coordinatorIp = intent.getStringExtra("COORDINATOR_IP") ?: ""
            val isRejoin      = intent.getBooleanExtra("IS_REJOIN", false)

            val terrain = Terrain(2400, 900)
            val biome = Biome.values().random()
            terrain.generate(lobbyWord.hashCode().toLong(), biome)

            val assignedId = if (mode == "MULTIPLAYER" && !isJoining && !isRejoin) {
                0.toByte()
            } else {
                ((assignJoinerId(playerName, lobbyWord).toInt() and 0x7F) % 7 + 1).toByte()
            }

            gameState = GameState(lobbyWord, terrain, mutableListOf())
            gameState.addPlayer(assignedId, playerName, isBot = false, color = tankColorForId(assignedId))

            if (mode == "SOLO") {
                gameState.phase = MatchPhase.PLAYER_TURN
                // Add 3 random bots with distinct IDs
                val botNames = listOf("Soldado Recruta", "Sargento Blindado", "General de Aço")
                botNames.forEachIndexed { i, name ->
                    // Use simple incremental IDs that don't clash with assignedId
                    val botId = ((assignedId.toInt() + i + 1) % 8).toByte()
                    gameState.addPlayer(botId, name, isBot = true, color = tankColorForId(botId))
                }
            }

            gameView  = GameView(this)
            gameView.setLocalPlayerId(assignedId)

            if (mode == "MULTIPLAYER") {
                gameState.phase = MatchPhase.LOBBY
                networkManager = UdpNetworkManager(lobbyWord, playerName, applicationContext).apply {
                    setLocalId(assignedId)
                    
                    onPlayerJoined = { name, ip, id ->
                        runOnUiThread {
                            // Unified: Both UI and State updated here
                            gameView.handlePeerJoined(name, ip, id)
                            gameState.addPlayer(id, name, isBot = false, color = tankColorForId(id))
                            roomDiscovery?.updatePlayerCount(gameState.players.size)
                            if (networkManager?.isCoordinator() == true) {
                                networkManager?.sendLobbySnapshot(buildLobbySnapshot())
                            }
                        }
                    }

                    onGameStart = {
                        runOnUiThread {
                            gameState.phase = MatchPhase.PLAYER_TURN
                            // ONLY coordinator advances the turn autoritatively
                            if (coordinator.isCoordinator) {
                                networkManager?.sendLobbySnapshot(buildLobbySnapshot())
                                coordinator.startNextTurn()
                            }
                            gameView.handleRemoteStart()
                        }
                    }

                    onActionReceived = { action -> runOnUiThread { gameView.handleRemoteAction(action) } }
                    onTurnStart      = { pkt -> runOnUiThread { gameView.handleTurnStart(pkt) } }
                    onLobbySnapshot  = { snapshot -> applyLobbySnapshot(snapshot) }
                    onShotResolved   = { packet -> runOnUiThread { gameView.handleShotResolved(packet) } }
                    onAimState       = { packet -> runOnUiThread { gameView.handleAimState(packet) } }
                    onDebugChanged   = { text -> runOnUiThread { gameView.handleNetworkDebug(text) } }
                    onLocalIdAssigned = { oldId, newId ->
                        runOnUiThread {
                            gameState.players.removeAll { it.id == oldId }
                            gameState.addPlayer(newId, playerName, isBot = false, color = tankColorForId(newId))
                            gameView.setLocalPlayerId(newId)
                        }
                    }
                    onRejoinSnapshot = { snapshot -> applyRejoinSnapshot(snapshot) }
                    coordinator.onBuildSnapshot = { buildGameSnapshot() }
                    start()
                }

                if (!isJoining && !isRejoin) {
                    roomDiscovery = RoomDiscovery(lobbyWord, applicationContext)
                    roomDiscovery?.startAnnouncing(1, 45) 
                    activityScope.launch {
                        delay(45000)
                        if (gameState.phase == MatchPhase.LOBBY) networkManager?.sendStartSignal()
                    }
                } else if (isJoining && coordinatorIp.isNotBlank()) {
                    networkManager?.broadcastLobbyPresence() 
                }

                gameView.initNetwork(networkManager!!)
            }

            gameView.initGame(gameState)
            setImmersiveMode()
            setContentView(gameView)
        } catch (e: Exception) {
            Log.e("TankBriga", "Crash in MainActivity.onCreate", e)
            Toast.makeText(this, "Falha ao iniciar partida: ${e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun tankColorForId(id: Byte): Int {
        val colors = intArrayOf(0xFF33B5E5.toInt(), 0xFFFF4444.toInt(), 0xFFAA66CC.toInt(), 0xFF99CC00.toInt(), 0xFFFFBB33.toInt(), 0xFF00DDFF.toInt(), 0xFFFF8800.toInt(), 0xFF00FF00.toInt())
        return colors[id.toInt() % colors.size]
    }

    private fun assignJoinerId(name: String, word: String): Byte {
        val hash = (name.uppercase() + word).hashCode()
        return (1 + ((hash and 0x7FFFFFFF) % 7)).toByte()
    }

    private fun applyRejoinSnapshot(snapshot: RejoinSnapshot) {
        runOnUiThread {
            snapshot.tankHps.forEachIndexed { i, hp ->
                gameState.players.getOrNull(i)?.hp = hp
            }
            snapshot.tankPositionsX.forEachIndexed { i, x ->
                val tank = gameState.players.getOrNull(i) ?: return@forEachIndexed
                tank.position = tank.position.copy(x = x, y = snapshot.tankPositionsY.getOrElse(i) { tank.position.y })
            }
            snapshot.craterCentersX.forEachIndexed { i, cx ->
                val cy = snapshot.craterCentersY.getOrElse(i) { 0 }
                val r  = snapshot.craterRadii.getOrElse(i) { 20 }
                gameState.terrain.circleErode(cx, cy, r)
            }
            gameState.turnNumber     = snapshot.turnNumber.toInt()
            gameState.currentTurnPlayerId = snapshot.currentTurnPlayerId
            gameState.phase          = com.tankbriga.engine.MatchPhase.PLAYER_TURN
            gameView.onRejoinComplete()
        }
    }

    private fun applyLobbySnapshot(snapshot: LobbySnapshot) {
        if (snapshot.lobbyWord != gameState.lobbyWord) return
        runOnUiThread {
            snapshot.players.forEach { slot ->
                gameState.addPlayer(slot.id, slot.name, isBot = false, color = slot.color).apply {
                    hp = slot.hp
                    position = Vector2(slot.x, slot.y)
                }
                gameView.handlePeerJoined(slot.name, "slot-${slot.id}", slot.id)
            }
        }
    }

    private fun buildLobbySnapshot(): LobbySnapshot {
        return LobbySnapshot(
            lobbyWord = gameState.lobbyWord,
            hostId = 0,
            players = synchronized(gameState.players) { gameState.players.toList() }.sortedBy { it.id.toInt() }.map { tank ->
                PlayerSlot(
                    id = tank.id,
                    name = tank.name,
                    color = tank.color,
                    hp = tank.hp,
                    x = tank.position.x,
                    y = tank.position.y
                )
            }
        )
    }

    private fun buildGameSnapshot(): RejoinSnapshot {
        return RejoinSnapshot(
            turnNumber            = gameState.turnNumber.toShort(),
            currentTurnPlayerId   = gameState.currentTurnPlayerId,
            tankHps               = gameState.players.map { it.hp },
            tankPositionsX        = gameState.players.map { it.position.x },
            tankPositionsY        = gameState.players.map { it.position.y },
            craterCentersX        = gameState.terrain.getCraterCentersX(),
            craterCentersY        = gameState.terrain.getCraterCentersY(),
            craterRadii           = gameState.terrain.getCraterRadii()
        )
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        wakeLock = pm.newWakeLock(android.os.PowerManager.SCREEN_DIM_WAKE_LOCK, "TankBriga::GameWakeLock").apply { acquire(30 * 60 * 1000L) }
    }

    private fun setImmersiveMode() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel()
        networkManager?.stop()
        roomDiscovery?.stop()
        wakeLock?.release()
    }
}
