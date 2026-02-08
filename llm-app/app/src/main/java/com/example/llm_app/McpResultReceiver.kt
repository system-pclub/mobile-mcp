package com.example.llm_app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap

private object CallbackSyncStore {
    private val receiveNsMap = ConcurrentHashMap<String, Long>()

    fun putReceiveNs(requestId: String, receiveNs: Long) {
        if (requestId.isNotBlank()) {
            receiveNsMap[requestId] = receiveNs
        }
    }

    fun takeReceiveNs(requestId: String): Long {
        return receiveNsMap.remove(requestId) ?: -1L
    }
}

class McpResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val requestId = intent.getStringExtra("mcp_request_id").orEmpty()
        val capability = intent.getStringExtra("trace_capability").orEmpty().ifBlank { "unknown" }
        val runIndex = intent.getIntExtra("trace_run_index", -1)
        val traceOnly = intent.getBooleanExtra("trace_only", false)

        if (traceOnly) {
            val s3aNs = intent.getLongExtra("trace_s3a_ns", -1L)
            val s3bNs = intent.getLongExtra("trace_s3b_ns", -1L)
            if (s3aNs > 0 && s3bNs > 0) {
                LatencyTraceLogger.logSpan(
                    context = context,
                    requestId = requestId,
                    capability = capability,
                    runIndex = runIndex,
                    step = "S3",
                    startNs = s3aNs,
                    endNs = s3bNs
                )
                val resultReceiveNs = CallbackSyncStore.takeReceiveNs(requestId)
                if (resultReceiveNs > 0L) {
                    LatencyTraceLogger.logSpan(
                        context = context,
                        requestId = requestId,
                        capability = capability,
                        runIndex = runIndex,
                        step = "T8",
                        startNs = s3bNs,
                        endNs = resultReceiveNs
                    )
                }
            }
            return
        }

        val resultJson = intent.getStringExtra("result_json").orEmpty()
        val receiveNs = SystemClock.elapsedRealtimeNanos()
        val s0Ns = intent.getLongExtra("trace_s0_ns", -1L)
        val s1StartNs = intent.getLongExtra("trace_s1_start_ns", -1L)
        val s1EndNs = intent.getLongExtra("trace_s1_end_ns", -1L)
        val s2StartNs = intent.getLongExtra("trace_s2_start_ns", -1L)
        val s2EndNs = intent.getLongExtra("trace_s2_end_ns", -1L)
        val s3aNs = intent.getLongExtra("trace_s3a_ns", -1L)

        LatencyTraceLogger.logMark(
            context = context,
            requestId = requestId,
            capability = capability,
            runIndex = runIndex,
            step = "T8a",
            timestampNs = receiveNs,
            extra = null
        )
        CallbackSyncStore.putReceiveNs(requestId, receiveNs)

        McpResultBus.deliver(
            McpCallbackPayload(
                requestId = requestId,
                resultJson = resultJson,
                receiveNs = receiveNs,
                serviceS0Ns = s0Ns,
                serviceS1StartNs = s1StartNs,
                serviceS1EndNs = s1EndNs,
                serviceS2StartNs = s2StartNs,
                serviceS2EndNs = s2EndNs,
                serviceS3aNs = s3aNs,
                serviceS3bNs = -1L,
                capability = capability,
                runIndex = runIndex
            )
        )
    }
}
