package com.tankbriga.engine.network

/**
 * Small transport boundary for deterministic multiplayer tests.
 *
 * The Android UDP implementation still owns socket details, but gameplay/protocol tests
 * can use this contract without depending on Android or real Wi-Fi.
 */
interface NetworkTransport {
    fun send(targetPlayerId: Byte, payload: ByteArray)
    fun broadcast(payload: ByteArray, excludePlayerIds: Set<Byte> = emptySet())
}
