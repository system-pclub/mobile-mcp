package com.example.llm_app

import java.util.concurrent.ConcurrentHashMap

data class McpCallbackPayload(
    val requestId: String,
    val resultJson: String,
    val receiveNs: Long,
    val serviceS0Ns: Long,
    val serviceS1StartNs: Long,
    val serviceS1EndNs: Long,
    val serviceS2StartNs: Long,
    val serviceS2EndNs: Long,
    val serviceS3aNs: Long,
    val serviceS3bNs: Long,
    val capability: String,
    val runIndex: Int
)

object McpResultBus {
    private val callbacks = ConcurrentHashMap<String, (McpCallbackPayload) -> Unit>()

    fun register(requestId: String, cb: (McpCallbackPayload) -> Unit) {
        callbacks[requestId] = cb
    }

    fun deliver(payload: McpCallbackPayload) {
        callbacks.remove(payload.requestId)?.invoke(payload)
    }
}
