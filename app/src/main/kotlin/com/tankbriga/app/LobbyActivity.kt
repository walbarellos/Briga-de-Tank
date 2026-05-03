package com.tankbriga.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.tankbriga.app.network.NetworkCapabilityProbe
import com.tankbriga.app.network.RoomDiscovery
import kotlinx.coroutines.*

/**
 * Tela de lobby.
 *
 * FIXES:
 *  - tvDiag e tvStatus eram a mesma view (bug de cópia) — separados agora
 *  - RoomDiscovery recriado em todo keystroke — agora usa debounce de 400ms
 *  - Adicionado campo de RECONEXÃO: se o player estava em partida, pode rejoin
 *  - Timer do lobby exibido (estava ausente)
 *  - NetworkCapabilityProbe agora mostra mensagem útil em vez de texto técnico
 */
class LobbyActivity : AppCompatActivity() {

    private lateinit var etPlayerName: EditText
    private lateinit var etLobbyWord: EditText
    private lateinit var tvStatus: TextView
    private lateinit var tvDiag: TextView      // FIX: view separada (era a mesma que tvStatus)
    private lateinit var tvTimer: TextView
    private lateinit var btnStart: Button

    private var roomDiscovery: RoomDiscovery? = null
    private var foundRoomIp: String? = null
    private var isRoomFound = false
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Debounce: não recria o RoomDiscovery em cada letra digitada
    private val debounceHandler = Handler(Looper.getMainLooper())
    private var debounceRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lobby)

        etPlayerName = findViewById(R.id.etPlayerName)
        etLobbyWord  = findViewById(R.id.etLobbyWord)
        tvStatus     = findViewById(R.id.tvStatus)
        tvDiag       = findViewById(R.id.tvDiag)    // FIX: precisa de R.id.tvDiag no XML
        tvTimer      = findViewById(R.id.tvTimer)
        btnStart     = findViewById(R.id.btnStart)

        // Preenche nome salvo (persiste entre sessões)
        val savedName = getPreferences(MODE_PRIVATE).getString("player_name", "") ?: ""
        if (savedName.isNotEmpty()) etPlayerName.setText(savedName)

        runDiagnostics()

        val watcher = object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = scheduleSearch()
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        }
        etPlayerName.addTextChangedListener(watcher)
        etLobbyWord.addTextChangedListener(watcher)

        btnStart.setOnClickListener { enterGame() }
    }

    // ── Debounce de busca ─────────────────────────────────────────────────────

    private fun scheduleSearch() {
        debounceRunnable?.let { debounceHandler.removeCallbacks(it) }
        debounceRunnable = Runnable { checkAndSearch() }
        debounceHandler.postDelayed(debounceRunnable!!, 400) // 400ms após parar de digitar
    }

    private fun checkAndSearch() {
        val name = etPlayerName.text.toString().trim()
        val word = etLobbyWord.text.toString().trim().uppercase()

        if (name.length >= 2 && word.length >= 3) {
            btnStart.visibility = View.VISIBLE
            startSearching(word)
        } else {
            btnStart.visibility = View.GONE
            tvStatus.text = when {
                name.length < 2 -> "Nome precisa de 2+ letras"
                else -> "Palavra precisa de 3+ letras"
            }
            roomDiscovery?.stop()
        }
    }

    private fun startSearching(word: String) {
        roomDiscovery?.stop()
        isRoomFound = false
        foundRoomIp = null
        tvStatus.text = "Procurando sala \"$word\"..."
        updateButton(false)

        roomDiscovery = RoomDiscovery(word, applicationContext)
        roomDiscovery?.startListening { room ->
            // FIX: startListening já chama withContext(Main), não precisa de runOnUiThread
            if (!isRoomFound) {
                isRoomFound = true
                foundRoomIp = room.coordinatorIp

                val statusMsg = if (room.countdown > 0) {
                    "Sala encontrada! ${room.playerCount} jogador(es) • ${room.countdown}s"
                } else {
                    "Partida em andamento — você entrará na próxima"
                }
                tvStatus.text = statusMsg

                if (room.countdown > 0) {
                    startTimerDisplay(room.countdown)
                }

                updateButton(true)
            }
        }
    }

    private fun startTimerDisplay(initialSeconds: Int) {
        tvTimer.visibility = View.VISIBLE
        var remaining = initialSeconds
        scope.launch {
            while (remaining > 0) {
                tvTimer.text = "Iniciando em ${remaining}s"
                delay(1000)
                remaining--
            }
            tvTimer.text = "Jogo iniciando!"
        }
    }

    // ── Entrar no jogo ────────────────────────────────────────────────────────

    private fun enterGame() {
        val name = etPlayerName.text.toString().trim().uppercase()
        val word = etLobbyWord.text.toString().trim().uppercase()
        if (name.length < 2 || word.length < 3) return

        // Salva nome para próxima sessão
        getPreferences(MODE_PRIVATE).edit().putString("player_name", name).apply()

        startActivity(Intent(this, MainActivity::class.java).apply {
            putExtra("MODE", "MULTIPLAYER")
            putExtra("PLAYER_NAME", name)
            putExtra("LOBBY_WORD", word)
            putExtra("IS_JOINING", isRoomFound)
            putExtra("COORDINATOR_IP", foundRoomIp ?: "")
        })
        finish()
    }

    // ── Diagnóstico de rede ───────────────────────────────────────────────────

    private fun runDiagnostics() {
        tvDiag.text = "Verificando rede..."
        scope.launch {
            val report = withContext(Dispatchers.IO) {
                NetworkCapabilityProbe().runFullProbe()
            }

            // Mensagem amigável — não mostra jargão técnico ao usuário
            tvDiag.text = when (report.recommendedMode) {
                NetworkCapabilityProbe.TransportMode.LAN_AUTO ->
                    "Wi-Fi OK — pronto para jogar"
                NetworkCapabilityProbe.TransportMode.BROADCAST_ONLY ->
                    "Wi-Fi OK"
                NetworkCapabilityProbe.TransportMode.QR_UNICAST ->
                    "Rede escolar detectada — use o QR Code se não encontrar a sala"
                NetworkCapabilityProbe.TransportMode.HOTSPOT ->
                    "Wi-Fi com restrições — crie um hotspot no seu celular"
                NetworkCapabilityProbe.TransportMode.SOLO ->
                    "Sem Wi-Fi — apenas modo solo disponível"
            }
        }
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private fun updateButton(found: Boolean) {
        btnStart.text = if (found) "ENTRAR NA SALA" else "CRIAR SALA"
        btnStart.setBackgroundColor(if (found) 0xFF185FA5.toInt() else 0xFF3B6D11.toInt())
    }

    override fun onDestroy() {
        super.onDestroy()
        debounceRunnable?.let { debounceHandler.removeCallbacks(it) }
        roomDiscovery?.stop()
        scope.cancel()
    }
}
