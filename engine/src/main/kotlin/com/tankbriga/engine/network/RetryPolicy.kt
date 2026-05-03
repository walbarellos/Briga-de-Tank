package com.tankbriga.engine.network

data class RetryPolicy(
    val attempts: Int,
    val delayMs: Long
)

object MultiplayerRetryPolicies {
    val LOBBY_SNAPSHOT = RetryPolicy(attempts = 4, delayMs = 300)
    val TURN_START = RetryPolicy(attempts = 4, delayMs = 350)
    val ACTION_FORWARD = RetryPolicy(attempts = 4, delayMs = 250)
    val SHOT_RESOLVE = RetryPolicy(attempts = 5, delayMs = 300)
    const val START_WAIT_FOR_LOBBY_ACK_MS: Long = 1500
}
