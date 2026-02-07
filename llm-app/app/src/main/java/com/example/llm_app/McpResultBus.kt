package com.example.llm_app

import java.util.concurrent.ConcurrentHashMap

object McpResultBus {
    private val callbacks = ConcurrentHashMap<String, (String) -> Unit>()

    fun register(requestId: String, cb: (String) -> Unit) {
        callbacks[requestId] = cb
    }

    fun deliver(requestId: String, resultJson: String) {
        callbacks.remove(requestId)?.invoke(resultJson)
    }
}
