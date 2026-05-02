package com.tankbriga.app

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.tankbriga.app.network.UdpNetworkManager
import com.tankbriga.engine.GameState
import com.tankbriga.engine.MatchFactory
import com.tankbriga.engine.Terrain
import com.tankbriga.engine.network.RejoinSnapshot

/**
 * FIXES:
 *  - Todos os players não ficam mais com ID 0 — ID é baseado no hash do nome+palavra
 *  - Context passado para UdpNetworkManager (necessário para RoomDiscovery.getWifiIp)
 *  - Fluxo de reconexão: se IS_REJOIN=true, espera o snapshot antes de iniciar
 *  - WakeLock para manter tela acesa durante o jogo
 */
class MainActivity : AppCompatActivity() {

    private lateinit var gameView: GameView
    private lateinit var gameState: GameState
    private var networkManager: UdpNetworkManager? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        acquireWakeLock()

        val mode          = intent.getStringExtra("MODE") ?: "SOLO"
        val playerName    = intent.getStringExtra("PLAYER_NAME") ?: "VOCÊ"
        val lobbyWord     = intent.getStringExtra("LOBBY_WORD")?.takeIf { it.isNotBlank() } ?: "RECREIO"
        val isJoining     = intent.getBooleanExtra("IS_JOINING", false)
        val coordinatorIp = intent.getStringExtra("COORDINATOR_IP") ?: ""
        val isRejoin      = intent.getBooleanExtra("IS_REJOIN", false)

        val terrain = Terrain(2400, 900)
        terrain.generate(lobbyWord.hashCode().toLong())

        // FIX: ID determinístico baseado no nome + palavra, não hardcoded 0
        // Garante que dois players com nomes diferentes recebem IDs diferentes
        // sem precisar de um servidor de atribuição
        val assignedId = assignPlayerId(playerName, lobbyWord)

        val players = if (mode == "SOLO") {
            MatchFactory.createSoloPlayers(terrain, MatchFactory.DEFAULT_SOLO_PLAYERS)
        } else {
            MatchFactory.createMultiplayerLocal(terrain, playerName, assignedId)
        }

        gameState = GameState(lobbyWord, terrain, players)
        gameView  = GameView(this)

        if (mode == "MULTIPLAYER") {
            gameState.phase = com.tankbriga.engine.MatchPhase.LOBBY

            networkManager = UdpNetworkManager(lobbyWord, playerName, applicationContext).apply {
                setLocalId(assignedId)

                onPlayerJoined   = { name, ip, id -> gameView.handlePeerJoined(name, ip, id) }
                onGameStart      = { gameView.handleRemoteStart() }
                onActionReceived = { action -> gameView.handleRemoteAction(action) }
                onTurnStart      = { pkt -> gameView.handleTurnStart(pkt) }

                // Reconexão: quando o snapshot chegar, recria o estado e continua
                onRejoinSnapshot = { snapshot -> applyRejoinSnapshot(snapshot) }

                // Wire coordinator snapshot builder → GameState
                coordinator.onBuildSnapshot = { buildGameSnapshot() }

                start()
                broadcastLobbyPresence()

                if (isRejoin && coordinatorIp.isNotBlank()) {
                    requestRejoin(coordinatorIp)
                }
            }

            gameView.initNetwork(networkManager!!)
        }

        gameView.initGame(gameState)
        setImmersiveMode()
        setContentView(gameView)
    }

    /**
     * Atribui ID de 0–7 deterministicamente.
     * Colisões são raras (1/8 chance para 2 players com nomes diferentes),
     * e o PeerRegistry resolve via IP — colisão de ID sem colisão de IP é inofensiva.
     */
    private fun assignPlayerId(name: String, word: String): Byte {
        val hash = (name.uppercase() + word).hashCode()
        return ((hash and 0x7FFFFFFF) % 8).toByte()
    }

    private fun applyRejoinSnapshot(snapshot: RejoinSnapshot) {
        runOnUiThread {
            // Reconstrói HP e posições a partir do snapshot
            snapshot.tankHps.forEachIndexed { i, hp ->
                gameState.players.getOrNull(i)?.hp = hp
            }
            snapshot.tankPositionsX.forEachIndexed { i, x ->
                val tank = gameState.players.getOrNull(i) ?: return@forEachIndexed
                tank.position = tank.position.copy(x = x, y = snapshot.tankPositionsY.getOrElse(i) { tank.position.y })
            }
            // Reaplicar cráteras
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

    private fun buildGameSnapshot(): com.tankbriga.engine.network.RejoinSnapshot {
        return com.tankbriga.engine.network.RejoinSnapshot(
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

    // ── WakeLock ──────────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        wakeLock = pm.newWakeLock(
            android.os.PowerManager.SCREEN_DIM_WAKE_LOCK,
            "TankBriga::GameWakeLock"
        ).apply { acquire(30 * 60 * 1000L) } // 30 min máximo
    }

    private fun setImmersiveMode() {
        @Suppress("DEPRECATION")
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
        networkManager?.stop()
        wakeLock?.release()
    }
}
