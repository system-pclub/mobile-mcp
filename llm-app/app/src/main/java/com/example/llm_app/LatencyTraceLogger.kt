package com.example.llm_app

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

object LatencyTraceLogger {
    private const val FILE_NAME = "latency_trace.jsonl"
    private const val TAG = "LatencyTrace"
    private const val SOURCE = "llm-app"
    private val lock = Any()

    fun clear(context: Context) {
        synchronized(lock) {
            File(context.filesDir, FILE_NAME).writeText("")
        }
    }

    fun logMeta(context: Context, fields: JSONObject) {
        val obj = JSONObject()
            .put("record_type", "meta")
            .put("source", SOURCE)
            .put("wall_time_ms", System.currentTimeMillis())
        mergeExtra(obj, fields)
        append(context, obj)
    }

    fun logMark(
        context: Context,
        requestId: String,
        capability: String,
        runIndex: Int,
        step: String,
        timestampNs: Long,
        success: Boolean = true,
        error: String? = null,
        extra: JSONObject? = null
    ) {
        val obj = JSONObject()
            .put("record_type", "step")
            .put("source", SOURCE)
            .put("request_id", requestId)
            .put("capability", capability)
            .put("run_index", runIndex)
            .put("step", step)
            .put("start_ns", timestampNs)
            .put("end_ns", timestampNs)
            .put("duration_ms", 0.0)
            .put("success", success)
            .put("error", error ?: JSONObject.NULL)
            .put("wall_time_ms", System.currentTimeMillis())
        if (extra != null) mergeExtra(obj, extra)
        append(context, obj)
    }

    fun logSpan(
        context: Context,
        requestId: String,
        capability: String,
        runIndex: Int,
        step: String,
        startNs: Long,
        endNs: Long,
        success: Boolean = true,
        error: String? = null,
        extra: JSONObject? = null
    ) {
        val durationMs = (endNs - startNs) / 1_000_000.0
        val obj = JSONObject()
            .put("record_type", "step")
            .put("source", SOURCE)
            .put("request_id", requestId)
            .put("capability", capability)
            .put("run_index", runIndex)
            .put("step", step)
            .put("start_ns", startNs)
            .put("end_ns", endNs)
            .put("duration_ms", durationMs)
            .put("success", success)
            .put("error", error ?: JSONObject.NULL)
            .put("wall_time_ms", System.currentTimeMillis())
        if (extra != null) mergeExtra(obj, extra)
        append(context, obj)
    }

    private fun mergeExtra(target: JSONObject, extra: JSONObject) {
        val keys = extra.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            target.put(key, extra.get(key))
        }
    }

    private fun append(context: Context, obj: JSONObject) {
        synchronized(lock) {
            val file = File(context.filesDir, FILE_NAME)
            file.appendText(obj.toString() + "\n")
        }
        Log.d(TAG, obj.toString())
    }
}
