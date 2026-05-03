package com.tankbriga.engine.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class RetryBroadcaster(private val scope: CoroutineScope) {
    fun launch(policy: RetryPolicy, sendOnce: () -> Unit, isComplete: () -> Boolean) {
        scope.launch retryJob@{
            repeat(policy.attempts) {
                sendOnce()
                delay(policy.delayMs)
                if (isComplete()) return@retryJob
            }
        }
    }
}
