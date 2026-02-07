package com.example.llm_app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class McpResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val requestId = intent.getStringExtra("mcp_request_id").orEmpty()
        val resultJson = intent.getStringExtra("result_json").orEmpty()

        Log.d("MCP_RESULT", "requestId=$requestId result=$resultJson")

        // Forward to an in-memory callback registry (see below)
        McpResultBus.deliver(requestId, resultJson)
    }
}
