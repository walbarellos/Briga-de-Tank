package com.tankbriga.app

import android.content.Context
import android.graphics.*
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.tankbriga.app.audio.AudioSynthesizer
import com.tankbriga.app.network.UdpNetworkManager
import com.tankbriga.app.render.*
import com.tankbriga.engine.*
import com.tankbriga.engine.network.AimStatePacket
import com.tankbriga.engine.network.LobbySnapshot
import com.tankbriga.engine.network.PlayerSlot
import com.tankbriga.engine.network.ShotResolvePacket
import kotlinx.coroutines.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class GameView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback {

    private var renderThread: RenderThread? = null
    private var gameState: GameState? = null
    private var turnManager: TurnManager? = null
    private var simulationOrchestrator: SimulationOrchestrator? = null
    private val viewScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

    private var currentAngle = 45f
    private var currentPower = 70f
    private var isCharging = false
    private var isScrollingCamera = false
    private var activeControl = HudControl.NONE
    private var chargeStartMs = 0L
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var turnSeconds = TurnManager.TURN_SECONDS
    private var localPlayerId: Byte = 0

    private var ghostPath: List<Vector2>? = null
    private var lastGhostAtMs = 0L
    private var lastGhostAngle = -999f
    private var lastGhostPower = -999f
    private var botTurnStarted = -1

    private var activeShotPath: List<Vector2>? = null
    private var activeShotResult: ShotResult? = null
    private var activeShotIndex = 0
    private var flightResolvePosted = false
    private var lastShooterId: Byte = -1
    private var pendingShotResolve: ShotResolvePacket? = null
    private val remoteAimAngles = mutableMapOf<Byte, Float>()
    private val remoteAimPowers = mutableMapOf<Byte, Float>()
    private var lastAimBroadcastMs = 0L
    private var lastBroadcastAngle = -999f
    private var lastBroadcastPower = -999f
    private var localShotSeq: Short = 0
    private var activeShotId: Short = 0
    private var debugOverlay: String? = null
    private var debugOverlayEnabled = true

    private var hudMessage: String? = null
    private var hudMessageUntilMs = 0L
    private var lastImpactPoint: Vector2? = null
    private var lastImpactUntilMs = 0L

    private val tankRenderer = TankRenderer()
    private var terrainRenderer: TerrainRenderer? = null
    private val hudRenderer = HUDRenderer()
    private val particles = ParticleSystem()
    private var camera: CameraController? = null
    private var scaleDetector: ScaleGestureDetector? = null
    private var angleRepeatJob: Job? = null
    private var networkManager: UdpNetworkManager? = null
    private val remotePlayers = mutableMapOf<String, String>() // IP to Name

    private val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        setColor(Color.WHITE)
        setAlpha(95)
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
    }
    private val shotTrailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        setColor(Color.rgb(255, 225, 120))
        setAlpha(180)
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
    }
    private val projectilePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        setColor(Color.rgb(255, 245, 170))
        style = Paint.Style.FILL
    }
    private val impactPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        setColor(Color.rgb(255, 110, 70))
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    init {
        holder.addCallback(this)
        isFocusable = true
        scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (camera?.mode == CameraMode.FREE) {
                    camera?.zoomByFactor(detector.scaleFactor)
                    return true
                }
                return false
            }
        })
    }

    fun initNetwork(mgr: UdpNetworkManager) {
        this.networkManager = mgr
    }

    fun setLocalPlayerId(id: Byte) {
        this.localPlayerId = id
    }

    fun handlePeerJoined(name: String, ip: String, id: Byte) {
        val state = gameState ?: return
        if (state.phase != MatchPhase.LOBBY) return
        if (id == localPlayerId) return
        remotePlayers[ip] = name
        post {
            AudioSynthesizer.playTurnStart()
            showMessage("$name entrou na sala!", 1500)
        }
    }

    fun handleRemoteStart() {
        val state = gameState ?: return
        if (state.phase == MatchPhase.PLAYER_TURN) {
            post { 
                // Multiplayer Start: Banner only, TurnStart packet will drive actual logic
                syncWindIntoSimulation()
                showTurnBanner()
            }
        }
    }

    fun handleRemoteAction(action: ActionPacket) {
        post { fireAction(action) }
    }

    fun handleShotResolved(packet: ShotResolvePacket) {
        post {
            val state = gameState ?: return@post
            if (state.phase == MatchPhase.FLIGHT && lastShooterId == packet.shooterId && activeShotId == packet.shotId) {
                pendingShotResolve = packet
            } else {
                applyAuthoritativeResolve(packet, showEffects = true)
            }
        }
    }

    fun handleNetworkDebug(text: String) {
        debugOverlay = text
    }

    fun handleAimState(packet: AimStatePacket) {
        if (packet.playerId == localPlayerId) return
        val oldAngle = remoteAimAngles[packet.playerId]
        remoteAimAngles[packet.playerId] = packet.angle
        remoteAimPowers[packet.playerId] = packet.power
        if (oldAngle == null || abs(oldAngle - packet.angle) >= 1f) {
            AudioSynthesizer.playTick()
        }
    }

    fun handleTurnStart(pkt: com.tankbriga.engine.network.TurnStartPacket) {
        val state = gameState ?: return
        post {
            state.currentTurnPlayerId = pkt.playerId
            state.windState.setWind(pkt.windValue)
            state.turnNumber = pkt.turnNumber.toInt()
            state.phase = MatchPhase.PLAYER_TURN
            remoteAimAngles.clear()
            remoteAimPowers.clear()
            
            val elapsed = if (pkt.serverStartMs > 0L) {
                (System.currentTimeMillis() - pkt.serverStartMs).coerceAtLeast(0L)
            } else {
                0L
            }
            val remainingMs = (pkt.timerMs - elapsed).toInt().coerceAtLeast(1000)
            turnManager?.startCountdown(remainingMs)
            
            syncWindIntoSimulation()
            focusCurrentTankSoftly()
            showTurnBanner()
        }
    }

    fun onRejoinComplete() {
        post {
            syncWindIntoSimulation()
            focusCurrentTankSoftly()
            showMessage("RECONECTADO COM SUCESSO!", 2000)
        }
    }

    private fun tankColorForId(id: Byte): Int {
        val colors = intArrayOf(0xFF33B5E5.toInt(), 0xFFFF4444.toInt(), 0xFFAA66CC.toInt(), 0xFF99CC00.toInt(), 0xFFFFBB33.toInt(), 0xFF00DDFF.toInt())
        return colors[id.toInt() % colors.size]
    }

    fun initGame(state: GameState) {
        this.gameState = state
        this.turnManager = TurnManager(state).apply {
            onTick = { seconds -> post { turnSeconds = seconds } }
            onTimeout = { post { passTurnOnTimeout() } }
        }
        this.simulationOrchestrator = SimulationOrchestrator(state.terrain, state.players)
        
        if (state.phase != MatchPhase.LOBBY) {
            turnManager?.nextTurn()
            syncWindIntoSimulation()
            showTurnBanner()
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        renderThread = RenderThread(holder, this).apply {
            running = true
            start()
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        renderThread?.running = false
        renderThread?.join()
        viewScope.cancel()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val state = gameState ?: return true
        scaleDetector?.onTouchEvent(event)
        
        val x = event.x
        val y = event.y
        val humanTurn = state.currentTurnPlayerId == localPlayerId && state.phase == MatchPhase.PLAYER_TURN
        val isHost = localPlayerId == 0.toByte()

        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2 && y < height * 0.35f) {
                    debugOverlayEnabled = !debugOverlayEnabled
                    showMessage(if (debugOverlayEnabled) "Debug multiplayer ligado" else "Debug multiplayer oculto", 900)
                    return true
                }
            }
            MotionEvent.ACTION_DOWN -> {
                if (event.pointerCount >= 2 && y < height * 0.35f) {
                    debugOverlayEnabled = !debugOverlayEnabled
                    showMessage(if (debugOverlayEnabled) "Debug multiplayer ligado" else "Debug multiplayer oculto", 900)
                    return true
                }
                activeControl = hudRenderer.hitTest(x, y, width, height)
                
                when (activeControl) {
                    HudControl.CAMERA -> {
                        toggleCameraMode()
                        return true
                    }
                    HudControl.FIRE -> if (humanTurn) startCharging()
                    HudControl.POWER_BAR -> if (humanTurn) {
                        currentPower = hudRenderer.powerFromTouch(x, width, height)
                        broadcastAimState(force = true)
                        showMessage("Força ${currentPower.toInt()} — mire e toque FOGO", 1100)
                    }
                    HudControl.AIM -> if (humanTurn) {
                        setAngleFromTouch(y)
                        showMessage("Mirando: ${currentAngle.toInt()}°", 700)
                    }
                    HudControl.ANGLE_UP -> if (humanTurn) {
                        startAngleRepeat(1f)
                        AudioSynthesizer.playTick()
                    }
                    HudControl.ANGLE_DOWN -> if (humanTurn) {
                        startAngleRepeat(-1f)
                        AudioSynthesizer.playTick()
                    }
                    HudControl.START_GAME -> if (state.phase == MatchPhase.LOBBY && isHost) {
                        networkManager?.sendLobbySnapshot(buildLobbySnapshot(state))
                        viewScope.launch {
                            delay(900)
                            networkManager?.sendStartSignal()
                        }
                    }
                    HudControl.NONE -> {
                        isScrollingCamera = true
                    }
                }
                lastTouchX = x
                lastTouchY = y
                updateGhostPath(force = true)
            }

            MotionEvent.ACTION_MOVE -> {
                when (activeControl) {
                    HudControl.AIM -> if (humanTurn) setAngleFromTouch(y)
                    HudControl.POWER_BAR -> if (humanTurn) {
                        currentPower = hudRenderer.powerFromTouch(x, width, height)
                        broadcastAimState()
                    }
                    HudControl.FIRE -> Unit
                    HudControl.ANGLE_UP, HudControl.ANGLE_DOWN -> Unit
                    HudControl.START_GAME -> Unit
                    HudControl.NONE -> if (isScrollingCamera && camera?.mode == CameraMode.FREE) {
                        camera?.panByScreenDelta(lastTouchX - x, lastTouchY - y)
                    }
                    HudControl.CAMERA -> Unit
                }
                lastTouchX = x
                lastTouchY = y
                if (activeControl != HudControl.NONE) updateGhostPath()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                stopAngleRepeat()
                if (activeControl == HudControl.FIRE && isCharging && humanTurn) {
                    updateChargePower()
                    val deliberateHold = System.currentTimeMillis() - chargeStartMs >= 130L
                    val releasedOnFire = hudRenderer.isInsideFire(x, y, width, height)
                    if (releasedOnFire && deliberateHold && currentPower >= 8f) {
                        fireHumanShot()
                    } else {
                        showMessage("Tiro cancelado — segure FOGO para carregar", 1300)
                    }
                }
                isCharging = false
                isScrollingCamera = false
                activeControl = HudControl.NONE
            }
        }
        return true
    }

    private fun startAngleRepeat(delta: Float) {
        stopAngleRepeat()
        angleRepeatJob = viewScope.launch {
            currentAngle = (currentAngle + delta).coerceIn(5f, 175f)
            vibrate(10)
            updateGhostPath(force = true)
            broadcastAimState(force = true)
            delay(350)
            while (isActive) {
                currentAngle = (currentAngle + delta).coerceIn(5f, 175f)
                vibrate(6)
                updateGhostPath(force = true)
                broadcastAimState()
                delay(60)
            }
        }
    }

    private fun stopAngleRepeat() {
        angleRepeatJob?.cancel()
        angleRepeatJob = null
    }

    private fun startCharging() {
        isCharging = true
        currentPower = 0f
        chargeStartMs = System.currentTimeMillis()
        showMessage("Carregando força... solte em FOGO", 900)
        vibrate(18)
        broadcastAimState(force = true)
    }

    private var lastChargeSoundMs = 0L

    private fun updateChargePower() {
        if (!isCharging) return
        val elapsed = (System.currentTimeMillis() - chargeStartMs).coerceAtLeast(0L)
        currentPower = (elapsed / 1850f * 100f).coerceIn(0f, 100f)
        
        val now = System.currentTimeMillis()
        if (now - lastChargeSoundMs > 100L) {
            AudioSynthesizer.playCharge(currentPower)
            lastChargeSoundMs = now
        }
        broadcastAimState()
    }

    private fun setAngleFromTouch(y: Float) {
        val old = currentAngle
        currentAngle = hudRenderer.angleFromTouch(y, width, height)
        if (abs(currentAngle - old) >= 1.0f) {
            AudioSynthesizer.playTick()
            checkSnaps(old, currentAngle)
            broadcastAimState()
        }
    }

    private fun broadcastAimState(force: Boolean = false) {
        val state = gameState ?: return
        val mgr = networkManager ?: return
        if (state.phase != MatchPhase.PLAYER_TURN || state.currentTurnPlayerId != localPlayerId) return

        val now = System.currentTimeMillis()
        if (!force &&
            now - lastAimBroadcastMs < 80L &&
            abs(currentAngle - lastBroadcastAngle) < 0.8f &&
            abs(currentPower - lastBroadcastPower) < 2f
        ) return

        lastAimBroadcastMs = now
        lastBroadcastAngle = currentAngle
        lastBroadcastPower = currentPower
        mgr.sendAimState(
            AimStatePacket(
                playerId = localPlayerId,
                angle = currentAngle,
                power = currentPower,
                charging = isCharging,
                turnNumber = state.turnNumber.toShort()
            )
        )
    }

    private fun updateGhostPath(force: Boolean = false) {
        val state = gameState ?: return
        val orchestrator = simulationOrchestrator ?: return
        if (state.phase != MatchPhase.PLAYER_TURN) return
        if (state.currentTurnPlayerId != localPlayerId) return

        val now = System.currentTimeMillis()
        val previewPower = 100f
        
        if (!force && now - lastGhostAtMs < 32L && abs(currentAngle - lastGhostAngle) < 0.2f) return

        lastGhostAtMs = now
        lastGhostAngle = currentAngle
        lastGhostPower = previewPower
        val mockAction = ActionPacket(
            localPlayerId,
            (currentAngle * 10).toInt().toShort(),
            previewPower.toInt().toByte(),
            0,
            0,
            0
        )
        orchestrator.setWind(state.windState)
        ghostPath = orchestrator.simulateShot(mockAction).path
    }

    private fun toggleCameraMode() {
        val cam = camera ?: return
        val state = gameState ?: return
        cam.mode = when (cam.mode) {
            CameraMode.GENERAL -> CameraMode.FOCUS
            CameraMode.FOCUS -> CameraMode.FREE
            CameraMode.FREE -> CameraMode.GENERAL
            CameraMode.PROJECTILE -> CameraMode.GENERAL
        }
        when (cam.mode) {
            CameraMode.GENERAL -> cam.fitPoints(state.players.filter { it.hp > 0 }.map { it.position }, padding = 270f)
            CameraMode.FOCUS -> state.players.find { it.id == state.currentTurnPlayerId }?.let { cam.focusOn(it.position, 1.15f) }
            CameraMode.FREE -> showMessage("Câmera livre: arraste e faça zoom", 1200)
            CameraMode.PROJECTILE -> Unit
        }
        vibrate(24)
    }

    private fun checkSnaps(old: Float, new: Float) {
        val snaps = floatArrayOf(20f, 30f, 40f, 45f, 70f, 90f, 135f)
        for (s in snaps) if ((old < s && new >= s) || (old > s && new <= s)) vibrate(8)
    }

    private fun fireHumanShot() {
        if (gameState == null) return
        localShotSeq = (localShotSeq + 1).toShort()
        val action = ActionPacket(localPlayerId, (currentAngle * 10).toInt().toShort(), currentPower.toInt().toByte(), 0, 0, localShotSeq)
        broadcastAimState(force = true)
        networkManager?.sendAction(action)
        showMessage("Aguardando autorização do host...", 900)
    }

    private fun buildLobbySnapshot(state: GameState): LobbySnapshot {
        return LobbySnapshot(
            lobbyWord = state.lobbyWord,
            hostId = 0,
            players = synchronized(state.players) { state.players.toList() }.sortedBy { it.id.toInt() }.map { tank ->
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

    private fun passTurnOnTimeout() {
        val state = gameState ?: return
        if (state.phase == MatchPhase.PLAYER_TURN && state.currentTurnPlayerId == localPlayerId) {
            showMessage("Tempo esgotado — passou a vez", 1500)
            turnManager?.stopCountdown()
            // Notify network manager for authoritative turn advance
            if (networkManager != null) {
                networkManager?.notifyImpactResolved()
            } else {
                turnManager?.nextTurn()
                syncWindIntoSimulation()
                currentPower = 70f
                ghostPath = null
                focusCurrentTankSoftly()
                showTurnBanner()
            }
        }
    }

    private fun maybeAutoPlayBotTurn() {
        val state = gameState ?: return
        if (state.phase != MatchPhase.PLAYER_TURN) return
        val bot = state.currentPlayer() ?: return
        if (!bot.isBot || botTurnStarted == state.turnNumber) return
        botTurnStarted = state.turnNumber
        showMessage("${bot.name} está mirando...", 900)
        viewScope.launch {
            delay(700)
            if (state.phase != MatchPhase.PLAYER_TURN || state.currentTurnPlayerId != bot.id) return@launch
            val target = state.players.filter { it.hp > 0 && it.id != bot.id }.minByOrNull { it.position.distanceTo(bot.position) } ?: return@launch
            val action = AngledBot(bot.id).decideAction(bot.position, target.position, state.windState)
            currentAngle = action.angleTenths / 10f
            currentPower = action.power.toFloat()
            fireAction(action)
        }
    }

    private fun fireAction(action: ActionPacket) {
        val state = gameState ?: return
        val orchestrator = simulationOrchestrator ?: return
        
        // Relax checks: if the packet came, show it.
        if (state.phase == MatchPhase.RESULTS) return
        if (state.phase == MatchPhase.FLIGHT && lastShooterId == action.playerId) return

        syncWindIntoSimulation()
        turnManager?.stopCountdown()
        ghostPath = null
        state.phase = MatchPhase.FLIGHT
        camera?.mode = CameraMode.PROJECTILE
        activeShotPath = null
        activeShotResult = null
        activeShotIndex = 0
        flightResolvePosted = false
        lastShooterId = action.playerId
        activeShotId = action.seq
        pendingShotResolve = null
        val shooterName = state.players.find { it.id == action.playerId }?.name ?: "Jogador"
        showMessage("$shooterName atirou!", 1000)
        vibrate(45)
        AudioSynthesizer.playShot()

        viewScope.launch {
            val result = withContext(Dispatchers.Default) { orchestrator.simulateShot(action) }
            activeShotPath = result.path
            activeShotResult = result
            activeShotIndex = 0
            flightResolvePosted = false
        }
    }

    private fun updateFlightAnimation() {
        val state = gameState ?: return
        if (state.phase != MatchPhase.FLIGHT) return
        val path = activeShotPath ?: return
        val result = activeShotResult ?: return
        if (path.isEmpty()) return

        val step = when {
            path.size > 700 -> 8
            path.size > 400 -> 6
            else -> 5
        }
        activeShotIndex = min(path.lastIndex, activeShotIndex + step)
        val projectile = path[activeShotIndex]
        camera?.focusOn(projectile, 1.1f)

        if (activeShotIndex >= path.lastIndex && !flightResolvePosted) {
            flightResolvePosted = true
            postDelayed({ resolveImpact(result) }, 450L)
        }
    }

    private fun resolveImpact(result: ShotResult) {
        val state = gameState ?: return
        val mgr = networkManager
        activeShotPath = null
        activeShotResult = null
        activeShotIndex = 0
        flightResolvePosted = false
        state.phase = MatchPhase.IMPACT_RESOLVE

        if (mgr != null && !mgr.isCoordinator()) {
            val impact = impactPointFor(result, state)
            camera?.focusOn(impact, 1.35f)
            AudioSynthesizer.playMiss()
            vibrate(60)
            showMessage("Aguardando resultado do host...", 1200)

            pendingShotResolve?.takeIf { it.shooterId == lastShooterId && it.shotId == activeShotId }?.let {
                pendingShotResolve = null
                applyAuthoritativeResolve(it, showEffects = true)
            }
            return
        }

        when (result) {
            is ShotResult.TerrainHit -> resolveExplosion(result.impactPoint, result.shotType, result.angle, null, result)
            is ShotResult.TankHit -> resolveExplosion(result.impactPoint, result.shotType, result.angle, result.hitTankId, result)
            is ShotResult.Miss -> {
                showMessage("ERROU — tiro fora do mapa", 1500)
                AudioSynthesizer.playMiss()
                vibrate(100)
                camera?.fitPoints(state.players.filter { it.hp > 0 }.map { it.position }, padding = 270f)
                maybeBroadcastShotResolve(result, null)
            }
        }

        pendingShotResolve?.takeIf { it.shooterId == lastShooterId && it.shotId == activeShotId }?.let {
            pendingShotResolve = null
            applyAuthoritativeResolve(it, showEffects = true)
        }

        if (state.phase != MatchPhase.RESULTS) {
            postDelayed({
                // MULTIPLAYER FIX: Notify network coordinator that local animation is done.
                if (networkManager != null) {
                    networkManager?.notifyImpactResolved()
                } else {
                    turnManager?.nextTurn()
                    syncWindIntoSimulation()
                    currentPower = 70f
                    focusCurrentTankSoftly()
                    showTurnBanner()
                }
            }, 900L)
        } else {
            val winner = state.winnerId?.let { id -> state.players.find { it.id == id }?.name } ?: "ninguém"
            showMessage("FIM DE JOGO — venceu: $winner", 5000)
        }
    }

    private fun impactPointFor(result: ShotResult, state: GameState): Vector2 {
        return when (result) {
            is ShotResult.TerrainHit -> result.impactPoint
            is ShotResult.TankHit -> result.impactPoint
            is ShotResult.Miss -> result.path.lastOrNull()
                ?: state.players.find { it.id == lastShooterId }?.position
                ?: Vector2(0f, 0f)
        }
    }

    private fun resolveExplosion(impactPoint: Vector2, shotType: Byte, shotAngle: Float, directTankId: Byte?, rawResult: ShotResult): ImpactReport? {
        val state = gameState ?: return null
        val report = state.applyExplosion(impactPoint, shotType, shotAngle, directTankId)
        terrainRenderer?.syncFromTerrain(impactPoint.x.toInt(), impactPoint.y.toInt(), report.craterRadius)
        particles.emitExplosion(impactPoint.x, impactPoint.y, report.craterRadius)
        lastImpactPoint = impactPoint
        lastImpactUntilMs = System.currentTimeMillis() + 1700L

        if (report.hitSomeone) {
            AudioSynthesizer.playTankHit()
            val hitPlayer = report.damages.any { it.tankId == localPlayerId }
            if (hitPlayer) vibrate(300) else vibrate(60)
        } else {
            AudioSynthesizer.playMiss()
            vibrate(100)
        }

        if (report.eliminated.isNotEmpty()) AudioSynthesizer.playDeath()

        camera?.focusOn(impactPoint, 1.45f)
        camera?.triggerShake(if (report.hitSomeone) 30f else 20f, 20)
        showMessage(feedbackFor(rawResult, report), 2300)
        maybeBroadcastShotResolve(rawResult, report)
        return report
    }

    private fun maybeBroadcastShotResolve(result: ShotResult, report: ImpactReport?) {
        val state = gameState ?: return
        val mgr = networkManager ?: return
        if (!mgr.isCoordinator()) return

        val impact = when (result) {
            is ShotResult.TerrainHit -> result.impactPoint
            is ShotResult.TankHit -> result.impactPoint
            is ShotResult.Miss -> result.path.lastOrNull() ?: state.players.find { it.id == lastShooterId }?.position ?: Vector2(0f, 0f)
        }
        val shotType = when (result) {
            is ShotResult.TerrainHit -> result.shotType
            is ShotResult.TankHit -> result.shotType
            is ShotResult.Miss -> 0
        }
        val directTankId = when (result) {
            is ShotResult.TankHit -> result.hitTankId
            else -> null
        }
        val resultType = when (result) {
            is ShotResult.Miss -> 0
            is ShotResult.TerrainHit -> 1
            is ShotResult.TankHit -> 2
        }.toByte()

        mgr.sendShotResolve(
            ShotResolvePacket(
                turnNumber = state.turnNumber.toShort(),
                shotId = activeShotId,
                shooterId = lastShooterId,
                resultType = resultType,
                impactX = impact.x,
                impactY = impact.y,
                shotType = shotType,
                shotAngle = result.angle,
                directTankId = directTankId,
                craterRadius = report?.craterRadius ?: 0,
                tankIds = state.players.map { it.id },
                tankHps = state.players.map { it.hp },
                tankPositionsX = state.players.map { it.position.x },
                tankPositionsY = state.players.map { it.position.y },
                eliminated = report?.eliminated ?: emptyList()
            )
        )
    }

    private fun applyAuthoritativeResolve(packet: ShotResolvePacket, showEffects: Boolean) {
        val state = gameState ?: return
        val impact = Vector2(packet.impactX, packet.impactY)
        if (state.phase != MatchPhase.RESULTS) state.phase = MatchPhase.IMPACT_RESOLVE

        if (packet.resultType != 0.toByte() && packet.craterRadius > 0 && showEffects) {
            state.terrain.circleErode(packet.impactX.toInt(), packet.impactY.toInt(), packet.craterRadius)
            terrainRenderer?.syncFromTerrain(packet.impactX.toInt(), packet.impactY.toInt(), packet.craterRadius)
            particles.emitExplosion(packet.impactX, packet.impactY, packet.craterRadius)
            lastImpactPoint = impact
            lastImpactUntilMs = System.currentTimeMillis() + 1700L
            camera?.focusOn(impact, 1.45f)
            camera?.triggerShake(24f, 18)
            AudioSynthesizer.playTankHit()
        }

        packet.tankIds.forEachIndexed { index, id ->
            val tank = state.players.find { it.id == id } ?: return@forEachIndexed
            tank.hp = packet.tankHps.getOrElse(index) { tank.hp }
            val x = packet.tankPositionsX.getOrElse(index) { tank.position.x }
            val y = packet.tankPositionsY.getOrElse(index) { tank.position.y }
            tank.position = Vector2(x, y)
        }

        if (packet.tankIds.contains(localPlayerId)) {
            val localIndex = packet.tankIds.indexOf(localPlayerId)
            val localHp = packet.tankHps.getOrNull(localIndex)
            if (localHp != null && localHp <= 0) vibrate(300)
        }
    }

    private fun feedbackFor(result: ShotResult, report: ImpactReport): String {
        val state = gameState ?: return "Impacto!"
        fun name(id: Byte) = state.players.find { it.id == id }?.name ?: "P$id"
        val shooter = name(lastShooterId)
        val damaged = report.damages.filter { it.damage > 0 }
        val bestDamage = damaged.maxOfOrNull { it.damage } ?: 0
        val firstTarget = damaged.firstOrNull()?.tankId?.let { name(it) }

        return when {
            report.hasDirectHit && bestDamage >= 45 -> "TIRO PRO! $shooter acertou $firstTarget (-$bestDamage)"
            report.hasDirectHit -> "ACERTO DIRETO! $firstTarget sofreu -$bestDamage"
            damaged.isNotEmpty() && bestDamage >= 25 -> "BOM TIRO! Splash em $firstTarget (-$bestDamage)"
            damaged.isNotEmpty() -> "TIRO AMADOR, mas pegou $firstTarget (-$bestDamage)"
            report.madeSomeoneFall -> {
                val fallen = report.falls.joinToString { name(it.tankId) }
                "CRATERA! $fallen caiu com o terreno"
            }
            result is ShotResult.TerrainHit -> "TIRO AMADOR — abriu cratera, sem dano"
            else -> "ERROU — sem impacto útil"
        }
    }

    private fun syncWindIntoSimulation() {
        val state = gameState ?: return
        simulationOrchestrator?.setWind(state.windState)
    }

    private fun focusCurrentTankSoftly() {
        val state = gameState ?: return
        val tank = state.players.find { it.id == state.currentTurnPlayerId } ?: return
        camera?.mode = CameraMode.FOCUS
        camera?.focusOn(tank.position, if (tank.isBot) 1.0f else 1.18f)
    }

    private fun showTurnBanner() {
        val state = gameState ?: return
        val current = state.players.find { it.id == state.currentTurnPlayerId } ?: return
        val text = if (current.id == localPlayerId) "SUA VEZ — mire com calma" else "VEZ DE ${current.name}"
        showMessage(text, 1600)
        AudioSynthesizer.playTurnStart()
    }

    private fun showMessage(text: String, durationMs: Long) {
        hudMessage = text
        hudMessageUntilMs = System.currentTimeMillis() + durationMs
    }

    private fun visibleMessage(): String? {
        val now = System.currentTimeMillis()
        if (now > hudMessageUntilMs) hudMessage = null
        return hudMessage
    }

    private var skyPaint: Paint? = null
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { setColor(Color.WHITE); setAlpha(140) }
    private var stars: List<Vector2>? = null

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        val state = gameState ?: return
        camera = CameraController(width, height).apply {
            setWorldBounds(state.terrain.width, state.terrain.height)
            mode = CameraMode.GENERAL
            fitPoints(state.players.map { it.position }, padding = 240f, immediate = true)
        }
        
        val skyGradient = LinearGradient(0f, 0f, 0f, height.toFloat(), Color.rgb(4, 8, 16), Color.rgb(20, 30, 60), Shader.TileMode.CLAMP)
        skyPaint = Paint().apply { shader = skyGradient }
        stars = List(120) { Vector2((Math.random() * state.terrain.width).toFloat(), (Math.random() * state.terrain.height).toFloat()) }
    }

    private fun vibrate(ms: Long) {
        if (android.os.Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createOneShot(ms, 150))
        else vibrator.vibrate(ms)
    }

    fun drawGame(canvas: Canvas) {
        val state = gameState ?: return
        val cam = camera ?: return
        if (terrainRenderer == null) terrainRenderer = TerrainRenderer(state.terrain).apply { initialize() }
        val playersSnapshot = synchronized(state.players) { state.players.toList() }

        updateChargePower()
        maybeAutoPlayBotTurn()
        updateFlightAnimation()
        particles.update()

        cam.update()
        skyPaint?.let { canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), it) }
        cam.applyTransform(canvas)
        stars?.forEach { canvas.drawCircle(it.x, it.y, 1.5f, starPaint) }
        terrainRenderer?.draw(canvas)
        drawGhostTrajectory(canvas, state)
        drawActiveProjectile(canvas)
        drawImpactMarker(canvas)

        playersSnapshot.forEach { tank ->
            if (tank.hp > 0) {
                val aimAngle = if (tank.id == localPlayerId) currentAngle else remoteAimAngles[tank.id] ?: currentAngle
                tankRenderer.draw(canvas, tank, tank.color, tank.id == state.currentTurnPlayerId, aimAngle, tank.id == localPlayerId)
            }
        }
        particles.draw(canvas)
        cam.restoreTransform(canvas)

        hudRenderer.draw(
            canvas,
            width,
            height,
            state.windState,
            currentPower,
            currentAngle,
            state.phase.name,
            cam.mode.name,
            playersSnapshot,
            state.terrain.width,
            state.terrain.height,
            cam,
            playersSnapshot.find { it.id == state.currentTurnPlayerId },
            turnSeconds,
            visibleMessage(),
            isCharging,
            isHost = (localPlayerId == 0.toByte())
        )
        drawDebugOverlay(canvas)
    }

    private fun drawDebugOverlay(canvas: Canvas) {
        if (!debugOverlayEnabled) return
        val text = debugOverlay ?: return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(190, 0, 0, 0)
            style = Paint.Style.FILL
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(180, 255, 180)
            textSize = 18f
            typeface = Typeface.MONOSPACE
        }
        val lines = text.lines()
        val width = (lines.maxOfOrNull { textPaint.measureText(it) } ?: 0f) + 24f
        val height = lines.size * 22f + 18f
        val rect = RectF(12f, 170f, 12f + width, 170f + height)
        canvas.drawRoundRect(rect, 8f, 8f, paint)
        lines.forEachIndexed { i, line ->
            canvas.drawText(line, rect.left + 12f, rect.top + 24f + i * 22f, textPaint)
        }
    }

    private fun drawGhostTrajectory(canvas: Canvas, state: GameState) {
        if (state.phase != MatchPhase.PLAYER_TURN || state.currentTurnPlayerId != localPlayerId) return
        val path = ghostPath ?: return
        if (path.isEmpty()) return
        val dp = Path()
        dp.moveTo(path[0].x, path[0].y)
        path.forEach { dp.lineTo(it.x, it.y) }
        canvas.drawPath(dp, ghostPaint)
    }

    private fun drawActiveProjectile(canvas: Canvas) {
        val path = activeShotPath ?: return
        if (path.isEmpty()) return
        val index = activeShotIndex.coerceIn(0, path.lastIndex)
        val start = max(0, index - 80)
        val trail = Path()
        trail.moveTo(path[start].x, path[start].y)
        for (i in start..index) trail.lineTo(path[i].x, path[i].y)
        canvas.drawPath(trail, shotTrailPaint)
        
        val p = path[index]
        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { 
            setColor(Color.rgb(255, 255, 200))
            setAlpha(120)
        }
        canvas.drawCircle(p.x, p.y, 14f, glowPaint)
        canvas.drawCircle(p.x, p.y, 8f, projectilePaint)
    }

    private fun drawImpactMarker(canvas: Canvas) {
        val point = lastImpactPoint ?: return
        val remaining = lastImpactUntilMs - System.currentTimeMillis()
        if (remaining <= 0L) return
        impactPaint.setAlpha((remaining / 1700f * 220f).toInt().coerceIn(0, 220))
        val radius = 18f + (1f - remaining / 1700f) * 28f
        canvas.drawCircle(point.x, point.y, radius, impactPaint)
        impactPaint.setAlpha(255)
    }
}

class RenderThread(private val surfaceHolder: SurfaceHolder, private val gameView: GameView) : Thread() {
    var running = false
    override fun run() {
        while (running) {
            val start = System.currentTimeMillis()
            var c: Canvas? = null
            try {
                c = surfaceHolder.lockCanvas()
                if (c != null) synchronized(surfaceHolder) { gameView.drawGame(c) }
            } catch (_: Exception) {
            } finally {
                if (c != null) surfaceHolder.unlockCanvasAndPost(c)
            }
            val sleep = (16 - (System.currentTimeMillis() - start)).coerceAtLeast(1)
            try { sleep(sleep) } catch (_: Exception) {}
        }
    }
}
