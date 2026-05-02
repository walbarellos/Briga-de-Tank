package com.tankbriga.engine.network

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Handles HMAC signing and verification for network packets.
 * Prevents unauthorized users from interfering.
 */
object SessionSecurity {
    private const val ALGO = "HmacSHA256"
    private var sharedSecret: SecretKeySpec? = null

    /** Initializes the session key based on room word. */
    fun init(roomWord: String) {
        // Normalize word and create a secret key
        val key = roomWord.trim().uppercase().toByteArray()
        sharedSecret = SecretKeySpec(key, ALGO)
    }

    /** Generates an 8-byte truncation of HMAC-SHA256 for a payload. */
    fun sign(data: ByteArray, playerId: Byte, seq: Short): Long {
        val secret = sharedSecret ?: return 0L
        val mac = Mac.getInstance(ALGO).apply { init(secret) }
        
        // Header fields included in the signature to prevent tampering
        mac.update(playerId)
        mac.update((seq.toInt() shr 8).toByte())
        mac.update((seq.toInt() and 0xFF).toByte())
        
        val fullHmac = mac.doFinal(data)
        var result = 0L
        for (i in 0 until 8) {
            result = (result shl 8) or (fullHmac[i].toLong() and 0xFF)
        }
        return result
    }

    /** Verifies a packet signature. */
    fun verify(data: ByteArray, playerId: Byte, seq: Short, signature: Long): Boolean {
        val expected = sign(data, playerId, seq)
        return expected == signature
    }

    /** Generates a truly random Room ID. */
    fun generateRoomId(): Int = (Math.random() * Int.MAX_VALUE).toInt()
}
