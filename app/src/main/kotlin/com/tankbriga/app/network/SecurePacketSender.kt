package com.tankbriga.app.network

import com.tankbriga.engine.network.ReliableUdp
import com.tankbriga.engine.network.SecureEnvelope
import com.tankbriga.engine.network.SessionSecurity
import java.net.InetAddress

class SecurePacketSender(
    private val transport: AndroidUdpTransport,
    private val reliableUdp: () -> ReliableUdp?,
    private val localPlayerId: () -> Byte,
    private val onPacketSent: (() -> Unit)? = null
) {
    private var localSeq: Short = 0

    fun broadcast(payload: ByteArray, reliable: Boolean = false) {
        val seq = nextSeq()
        val data = envelope(payload, seq)
        onPacketSent?.invoke()

        if (reliable) {
            reliableUdp()?.sendReliable(data, transport.reliableGroupAddress(), seq)
            transport.sendBroadcast(data)
        } else {
            transport.sendMulticast(data)
            transport.sendBroadcast(data)
        }
    }

    fun sendTo(payload: ByteArray, address: InetAddress, reliable: Boolean = false) {
        val seq = nextSeq()
        val data = envelope(payload, seq)
        onPacketSent?.invoke()
        if (reliable) reliableUdp()?.sendReliable(data, address, seq)
        else transport.sendTo(data, address)
    }

    private fun envelope(payload: ByteArray, seq: Short): ByteArray {
        val playerId = localPlayerId()
        val sig = SessionSecurity.sign(payload, playerId, seq)
        return SecureEnvelope(playerId, seq, sig, payload).toBinary()
    }

    private fun nextSeq(): Short {
        localSeq++
        return localSeq
    }
}
