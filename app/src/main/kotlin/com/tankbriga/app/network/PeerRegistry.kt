package com.tankbriga.app.network

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Registro de peers autorizados.
 *
 * FIXES:
 *  - Sequência em Int (Short overflow em 32767 quebrava todo o tráfego)
 *  - lockRoster não bloqueia reconnect — reconnect tem caminho próprio via allowRejoin()
 *  - addPeer(force=true) para re-autenticar player que voltou
 */
class PeerRegistry {
    // id → ip
    private val peers = ConcurrentHashMap<Byte, String>()
    // id → última seq recebida (Int, not Short — avoids overflow)
    private val sequences = ConcurrentHashMap<Byte, Int>()
    private var matchStarted = false

    fun addPeer(id: Byte, ip: String, force: Boolean = false) {
        if (force || (!matchStarted && peers.size < 8)) {
            peers[id] = ip
            sequences.putIfAbsent(id, -1) // -1 accepts any initial seq
        }
    }

    fun isAuthorized(id: Byte, ip: String): Boolean = peers[id] == ip

    /**
     * Validates strictly increasing sequence.
     * FIX: use Int to support long matches without overflow.
     */
    fun validateSequence(id: Byte, seq: Short): Boolean {
        val seqInt = seq.toInt() and 0xFFFF // treat as unsigned 16-bit
        val last = sequences[id] ?: -1

        // Handle wrap-around
        val isValid = if (last > 60000 && seqInt < 1000) {
            true // legitimate wrap-around
        } else {
            seqInt > last
        }

        if (isValid) sequences[id] = seqInt
        return isValid
    }

    /** Closes roster for new entrants but doesn't block rejoin. */
    fun lockRoster() { matchStarted = true }

    /**
     * Re-authenticates a peer returning after disconnect.
     */
    fun allowRejoin(id: Byte, newIp: String) {
        peers[id] = newIp
        // Reset sequence to accept packets from new socket
        sequences[id] = -1
    }

    fun getPeerIp(id: Byte): String? = peers[id]
    fun getAllIps(): List<String> = peers.values.toList()

    fun clear() {
        peers.clear()
        sequences.clear()
        matchStarted = false
    }
}

/**
 * Rate limiter by IP.
 */
class RateLimiter(private val maxPerSecond: Int = 25) {
    private data class Bucket(val count: AtomicInteger = AtomicInteger(0), val windowStart: AtomicLong = AtomicLong(0))
    private val buckets = ConcurrentHashMap<String, Bucket>()

    fun allow(ip: String): Boolean {
        val now = System.currentTimeMillis()
        val bucket = buckets.getOrPut(ip) { Bucket() }

        val windowStart = bucket.windowStart.get()
        if (now - windowStart >= 1000) {
            if (bucket.windowStart.compareAndSet(windowStart, now)) {
                bucket.count.set(0)
            }
        }

        return bucket.count.incrementAndGet() <= maxPerSecond
    }
}
