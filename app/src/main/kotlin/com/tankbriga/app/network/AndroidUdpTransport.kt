package com.tankbriga.app.network

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket

class AndroidUdpTransport(
    private val context: Context,
    private val port: Int,
    private val groupAddress: String = "239.255.0.1"
) {
    private val group = InetAddress.getByName(groupAddress)
    private val broadcastAddr = InetAddress.getByName("255.255.255.255")
    private var multicastLock: WifiManager.MulticastLock? = null

    var socket: MulticastSocket? = null
        private set

    fun start() {
        acquireMulticastLock()
        socket = MulticastSocket(port).apply {
            try {
                joinGroup(group)
            } catch (e: Exception) {
                Log.w("TankBriga", "Multicast join failed, keeping broadcast fallback", e)
            }
            broadcast = true
            soTimeout = 300
        }
    }

    fun receive(buffer: ByteArray): DatagramPacket? {
        val pkt = DatagramPacket(buffer, buffer.size)
        socket?.receive(pkt)
        return pkt
    }

    fun sendMulticast(data: ByteArray) {
        send(data, group)
    }

    fun sendBroadcast(data: ByteArray) {
        send(data, broadcastAddr)
    }

    fun sendTo(data: ByteArray, address: InetAddress) {
        send(data, address)
    }

    fun reliableGroupAddress(): InetAddress = group

    fun stop() {
        try {
            socket?.leaveGroup(group)
        } catch (_: Exception) {
        }
        socket?.close()
        socket = null
        try {
            multicastLock?.release()
        } catch (_: Exception) {
        }
        multicastLock = null
    }

    private fun send(data: ByteArray, address: InetAddress) {
        try {
            socket?.send(DatagramPacket(data, data.size, address, port))
        } catch (_: Exception) {
        }
    }

    private fun acquireMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) return
            val wm = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wm.createMulticastLock("TankBrigaGame").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w("TankBriga", "Failed to acquire multicast lock", e)
        }
    }
}
