package com.tankbriga.engine.network

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.*

/**
 * Room discovery via UDP multicast (primary) + broadcast (fallback).
 *
 * FIX: InetAddress.getLocalHost() no Android retorna 127.0.0.1.
 * Correto: lê WifiManager.connectionInfo.ipAddress.
 */
class RoomDiscovery(
    private val lobbyWord: String,
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    private val MULTICAST_ADDR = "239.255.0.1"
    private val DISCOVERY_PORT = 45678
    private val group = InetAddress.getByName(MULTICAST_ADDR)

    private var listeningJob: Job? = null
    private var announcingJob: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private fun acquireMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) return
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wm.createMulticastLock("TankBrigaDiscovery").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) { }
    }

    // ── IP resolution (FIX crítico) ───────────────────────────────────────────

    /**
     * Obtém o IP real do Wi-Fi, não o loopback.
     * Fallback para NetworkInterface scan se WifiManager não disponível.
     */
    fun getWifiIp(): String {
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ip = wm.connectionInfo.ipAddress
            if (ip != 0) {
                String.format(
                    "%d.%d.%d.%d",
                    ip and 0xFF, (ip shr 8) and 0xFF,
                    (ip shr 16) and 0xFF, (ip shr 24) and 0xFF
                )
            } else {
                getIpFromNetworkInterface() ?: "127.0.0.1"
            }
        } catch (e: Exception) {
            getIpFromNetworkInterface() ?: "127.0.0.1"
        }
    }

    private fun getIpFromNetworkInterface(): String? {
        return NetworkInterface.getNetworkInterfaces()?.asSequence()
            ?.flatMap { it.inetAddresses.asSequence() }
            ?.firstOrNull { addr ->
                !addr.isLoopbackAddress &&
                addr is Inet4Address &&
                addr.hostAddress?.startsWith("192.") == true ||
                addr.hostAddress?.startsWith("10.") == true ||
                addr.hostAddress?.startsWith("172.") == true
            }?.hostAddress
    }

    // ── Announcing ────────────────────────────────────────────────────────────

    fun startAnnouncing(playerCount: Int, countdown: Int) {
        acquireMulticastLock()
        announcingJob?.cancel()
        val myIp = getWifiIp()

        announcingJob = scope.launch {
            val announce = RoomAnnounce(lobbyWord, myIp, 45679, playerCount, countdown)
            val payload = Json.encodeToString(announce).toByteArray()

            // Tenta multicast primeiro, fallback para broadcast
            val mcSocket = try {
                DatagramSocket().apply { broadcast = true }
            } catch (e: Exception) { null }

            while (isActive) {
                try {
                    // Multicast (funciona na maioria das redes)
                    mcSocket?.send(DatagramPacket(payload, payload.size, group, DISCOVERY_PORT))

                    // Broadcast simultâneo como fallback (captura redes sem multicast)
                    val broadcastAddr = InetAddress.getByName("255.255.255.255")
                    mcSocket?.send(DatagramPacket(payload, payload.size, broadcastAddr, DISCOVERY_PORT))
                } catch (e: Exception) { /* rede não disponível ainda, tenta de novo */ }

                delay(1500) // 1.5s — mais responsivo que 2s na entrada do lobbies
            }
            mcSocket?.close()
        }
    }

    // ── Listening ─────────────────────────────────────────────────────────────

    fun startListening(onRoomFound: (RoomAnnounce) -> Unit) {
        acquireMulticastLock()
        listeningJob?.cancel()

        listeningJob = scope.launch {
            // Escuta tanto multicast quanto unicast/broadcast na mesma porta
            val socket = MulticastSocket(DISCOVERY_PORT).apply {
                try { joinGroup(group) } catch (e: Exception) { /* rede sem multicast, ok */ }
                soTimeout = 800
            }

            val buf = ByteArray(1024)
            while (isActive) {
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    socket.receive(pkt)

                    val data = String(pkt.data, 0, pkt.length)
                    val announce = runCatching { Json.decodeFromString<RoomAnnounce>(data) }.getOrNull()
                        ?: continue

                    if (announce.lobbyWord == lobbyWord) {
                        withContext(Dispatchers.Main) { onRoomFound(announce) }
                    }
                } catch (_: SocketTimeoutException) {
                    // Normal — só tenta de novo
                } catch (_: Exception) { }
            }

            try { socket.leaveGroup(group) } catch (_: Exception) { }
            socket.close()
        }
    }

    fun stop() {
        announcingJob?.cancel()
        listeningJob?.cancel()
        try {
            multicastLock?.release()
        } catch (e: Exception) { }
        multicastLock = null
    }
}
