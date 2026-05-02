package com.tankbriga.engine.network

import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * A lightweight reliability layer over raw UDP for critical packets.
 * Ensures packets like GAME_START or ELIM are delivered.
 */
class ReliableUdp(
    private val socket: DatagramSocket,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    private val pending = ConcurrentHashMap<Short, PendingPacket>()
    private var retryJob: Job? = null

    data class PendingPacket(
        val payload: ByteArray,
        val target: InetAddress,
        val port: Int,
        val sentAt: Long,
        var retries: Int = 0
    )

    init {
        startRetryLoop()
    }

    private fun startRetryLoop() {
        retryJob = scope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                val toRetry = pending.entries.filter { now - it.value.sentAt > 200 }
                
                for ((seq, p) in toRetry) {
                    if (p.retries >= 5) {
                        println("ReliableUDP: Packet $seq failed after 5 retries to ${p.target}")
                        pending.remove(seq)
                        continue
                    }
                    
                    try {
                        socket.send(DatagramPacket(p.payload, p.payload.size, p.target, p.port))
                        p.retries++
                        // Update sentAt to wait for next interval
                        pending[seq] = p.copy(sentAt = now)
                    } catch (e: Exception) {
                        // Socket error
                    }
                }
                delay(100)
            }
        }
    }

    /**
     * Sends a packet and expects an ACK.
     */
    fun sendReliable(payload: ByteArray, target: InetAddress, seq: Short, port: Int = 45679) {
        val p = PendingPacket(payload, target, port, System.currentTimeMillis())
        pending[seq] = p
        try {
            socket.send(DatagramPacket(payload, payload.size, target, port))
        } catch (e: Exception) { }
    }

    /**
     * Call this when an ACK packet is received.
     */
    fun onAckReceived(seq: Short) {
        pending.remove(seq)
    }

    fun stop() {
        retryJob?.cancel()
    }
    
    fun hasPending(): Boolean = pending.isNotEmpty()

    /**
     * Manual retry trigger for pending packets.
     */
    fun retryPending() {
        val now = System.currentTimeMillis()
        val toRetry = pending.entries.filter { now - it.value.sentAt > 200 }
        for ((seq, p) in toRetry) {
            if (p.retries >= 5) {
                pending.remove(seq)
                continue
            }
            try {
                socket.send(DatagramPacket(p.payload, p.payload.size, p.target, p.port))
                p.retries++
                pending[seq] = p.copy(sentAt = now)
            } catch (e: Exception) { }
        }
    }
}
