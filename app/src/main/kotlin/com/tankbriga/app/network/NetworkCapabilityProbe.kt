package com.tankbriga.app.network

import kotlinx.coroutines.*
import java.net.*

/**
 * Diagnóstico de capacidade de rede.
 *
 * FIX: O probe anterior tentava receber o próprio multicast com receive() blocante
 * no mesmo thread do send — nunca funcionava. Agora usa dois sockets separados:
 * um para enviar, outro para receber, em coroutines paralelas com timeout real.
 */
class NetworkCapabilityProbe(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    data class NetReport(
        val wifiActive: Boolean,
        val multicastWorking: Boolean,
        val broadcastWorking: Boolean,
        val clientIsolationDetected: Boolean,
        val recommendedMode: TransportMode,
        val diagMessage: String
    )

    enum class TransportMode {
        LAN_AUTO,       // Multicast funciona — caminho ideal
        BROADCAST_ONLY, // Só broadcast — funciona mas menos eficiente
        QR_UNICAST,     // Client isolation — precisa QR code para trocar IPs
        HOTSPOT,        // Tudo bloqueado — criar hotspot no celular
        SOLO            // Sem Wi-Fi
    }

    suspend fun runFullProbe(): NetReport = withContext(Dispatchers.IO) {
        val hasWifi = hasWifiConnection()
        if (!hasWifi) {
            return@withContext NetReport(false, false, false, false, TransportMode.SOLO, "Sem Wi-Fi")
        }

        val multicast  = testMulticast()
        val broadcast  = testBroadcast()
        val clientIso  = !multicast && !broadcast

        val mode = when {
            multicast               -> TransportMode.LAN_AUTO
            broadcast               -> TransportMode.BROADCAST_ONLY
            else                    -> TransportMode.QR_UNICAST
        }

        val msg = when (mode) {
            TransportMode.LAN_AUTO        -> "Rede aberta — multicast OK"
            TransportMode.BROADCAST_ONLY  -> "Multicast bloqueado, broadcast OK"
            TransportMode.QR_UNICAST      -> "Rede institucional — use QR Code"
            else                          -> "Sem rede detectada"
        }

        NetReport(true, multicast, broadcast, clientIso, mode, msg)
    }

    /**
     * Lança um listener ANTES de enviar, em paralelo.
     * Timeout de 1.5s para o pacote dar volta via multicast.
     */
    private suspend fun testMulticast(): Boolean = withContext(Dispatchers.IO) {
        val PROBE_PORT = 45670
        val group = InetAddress.getByName("239.255.0.1")
        val received = CompletableDeferred<Boolean>()

        // Receiver — começa antes do send
        val listenerJob = launch {
            try {
                MulticastSocket(PROBE_PORT).use { sock ->
                    sock.joinGroup(group)
                    sock.soTimeout = 1500
                    val buf = ByteArray(64)
                    val pkt = DatagramPacket(buf, buf.size)
                    sock.receive(pkt) // aguarda o echo
                    if (String(pkt.data, 0, pkt.length) == "PROBE_MC") {
                        received.complete(true)
                    }
                }
            } catch (e: Exception) {
                received.complete(false)
            }
        }

        // Sender — manda 50ms depois para o listener estar pronto
        delay(50)
        try {
            DatagramSocket().use { sock ->
                val msg = "PROBE_MC".toByteArray()
                sock.send(DatagramPacket(msg, msg.size, group, PROBE_PORT))
            }
        } catch (e: Exception) {
            listenerJob.cancel()
            return@withContext false
        }

        val result = withTimeoutOrNull(1500) { received.await() } ?: false
        listenerJob.cancel()
        result
    }

    private suspend fun testBroadcast(): Boolean = withContext(Dispatchers.IO) {
        val PROBE_PORT = 45671
        val received = CompletableDeferred<Boolean>()

        val listenerJob = launch {
            try {
                DatagramSocket(PROBE_PORT).use { sock ->
                    sock.soTimeout = 1500
                    val buf = ByteArray(64)
                    val pkt = DatagramPacket(buf, buf.size)
                    sock.receive(pkt)
                    if (String(pkt.data, 0, pkt.length) == "PROBE_BC") {
                        received.complete(true)
                    }
                }
            } catch (e: Exception) {
                received.complete(false)
            }
        }

        delay(50)
        try {
            DatagramSocket().use { sock ->
                sock.broadcast = true
                val msg = "PROBE_BC".toByteArray()
                sock.send(DatagramPacket(msg, msg.size, InetAddress.getByName("255.255.255.255"), PROBE_PORT))
            }
        } catch (e: Exception) {
            listenerJob.cancel()
            return@withContext false
        }

        val result = withTimeoutOrNull(1500) { received.await() } ?: false
        listenerJob.cancel()
        result
    }

    private fun hasWifiConnection(): Boolean {
        return try {
            NetworkInterface.getNetworkInterfaces()?.asSequence()
                ?.any { iface ->
                    iface.name.startsWith("wlan") && iface.isUp && !iface.isLoopback
                } ?: false
        } catch (_: Exception) { false }
    }
}
