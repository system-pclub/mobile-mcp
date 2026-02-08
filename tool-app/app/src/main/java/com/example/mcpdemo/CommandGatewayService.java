package com.example.mcpdemo;

import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONObject;
import org.json.JSONException;

public class CommandGatewayService extends Service {

    private ClockInManager clockInManager;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        long s0Ns = SystemClock.elapsedRealtimeNanos();

        if (intent == null) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        // 1️⃣ Get MCP command JSON
        String commandJson = intent.getStringExtra("mcp_command_json");
        String requestId = intent.getStringExtra("mcp_request_id");
        PendingIntent callback = intent.getParcelableExtra("mcp_callback");
        String traceCapability = intent.getStringExtra("trace_capability");
        int runIndex = intent.getIntExtra("trace_run_index", -1);
        if (traceCapability == null || traceCapability.isEmpty()) {
            traceCapability = "unknown";
        }

        LatencyTraceLogger.logMark(this, requestId == null ? "unknown" : requestId, traceCapability, runIndex, "S0", s0Ns, true, null, null);

        if (commandJson == null || requestId == null || callback == null) {
            Log.e("MCPDemo", "Missing command/requestId/callback");
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        Log.d("MCPDemo", "Received MCP command: " + commandJson);

        String resultJson;
        JSONObject result = new JSONObject();
        long s1StartNs = -1L;
        long s1EndNs = -1L;
        long s2StartNs = -1L;
        long s2EndNs = -1L;
        String capabilityId = traceCapability;

        try {
            s1StartNs = SystemClock.elapsedRealtimeNanos();
            JSONObject json = new JSONObject(commandJson);
            capabilityId = json.optString("capability", traceCapability);
            s1EndNs = SystemClock.elapsedRealtimeNanos();
            LatencyTraceLogger.logSpan(this, requestId, capabilityId, runIndex, "S1", s1StartNs, s1EndNs, true, null, null);

            s2StartNs = SystemClock.elapsedRealtimeNanos();
            switch (capabilityId) {
                case "clock_in_today":
                    long c1StartNs = SystemClock.elapsedRealtimeNanos();
                    notifyActivityToClick();
                    long c1EndNs = SystemClock.elapsedRealtimeNanos();
                    LatencyTraceLogger.logSpan(
                            this, requestId, capabilityId, runIndex,
                            "S2_CLOCK_BROADCAST_CLICK", c1StartNs, c1EndNs, true, null, null
                    );

                    long c2StartNs = SystemClock.elapsedRealtimeNanos();
                    result.put("status", "success");
                    result.put("message", "Clock in successfully!");
                    long c2EndNs = SystemClock.elapsedRealtimeNanos();
                    LatencyTraceLogger.logSpan(
                            this, requestId, capabilityId, runIndex,
                            "S2_CLOCK_BUILD_SUCCESS_JSON", c2StartNs, c2EndNs, true, null, null
                    );
                    break;
                case "query_clock_in":
                    result = handleQueryClockIn(json, requestId, capabilityId, runIndex);
                    break;
                case "make_up_clock_in":
                    result = handleMakeUpClockIn(json, requestId, capabilityId, runIndex);
                    break;
                default:
                    Log.e("MCP", "Received unknown capability ID: " + capabilityId);
                    result.put("status", "error");
                    result.put("message", "Unknown capability ID: " + capabilityId);
                    break;
            }
            s2EndNs = SystemClock.elapsedRealtimeNanos();
            LatencyTraceLogger.logSpan(this, requestId, capabilityId, runIndex, "S2", s2StartNs, s2EndNs, true, null, null);
            resultJson = result.toString();
        } catch (Exception e) {
            Log.e("MCP", "JSON parsing or execution exception", e);
            long nowNs = SystemClock.elapsedRealtimeNanos();
            if (s1StartNs > 0L && s1EndNs < 0L) {
                s1EndNs = nowNs;
                LatencyTraceLogger.logSpan(this, requestId, capabilityId, runIndex, "S1", s1StartNs, s1EndNs, false, e.getMessage(), null);
            }
            if (s2StartNs > 0L && s2EndNs < 0L) {
                s2EndNs = nowNs;
                LatencyTraceLogger.logSpan(this, requestId, capabilityId, runIndex, "S2", s2StartNs, s2EndNs, false, e.getMessage(), null);
            }
            try {
                result.put("status", "error");
                result.put("message", e.getMessage());
                resultJson = result.toString();
            } catch (JSONException jsonException) {
                resultJson = "{\"status\":\"error\", \"message\":\"" + e.getMessage() + "\"}";
            }
        }

        long s3aNs = SystemClock.elapsedRealtimeNanos();
        Intent back = new Intent();
        back.putExtra("mcp_request_id", requestId);
        back.putExtra("result_json", resultJson);
        back.putExtra("trace_capability", capabilityId);
        back.putExtra("trace_run_index", runIndex);
        back.putExtra("trace_s0_ns", s0Ns);
        back.putExtra("trace_s1_start_ns", s1StartNs);
        back.putExtra("trace_s1_end_ns", s1EndNs);
        back.putExtra("trace_s2_start_ns", s2StartNs);
        back.putExtra("trace_s2_end_ns", s2EndNs);
        back.putExtra("trace_s3a_ns", s3aNs);
        back.putExtra("trace_s3b_ns", -1L);
        back.putExtra("trace_phase", "result");

        boolean callbackOk = true;
        try {
            callback.send(this, 0, back);
        } catch (PendingIntent.CanceledException e) {
            callbackOk = false;
            Log.e("MCPDemo", "Callback canceled", e);
        }
        long s3bNs = SystemClock.elapsedRealtimeNanos();
        LatencyTraceLogger.logSpan(this, requestId, capabilityId, runIndex, "S3", s3aNs, s3bNs, callbackOk, callbackOk ? null : "callback canceled", null);

        // Send a second trace-only callback so llm-app can compute T8 using real S3b.
        Intent traceOnly = new Intent();
        traceOnly.putExtra("mcp_request_id", requestId);
        traceOnly.putExtra("trace_only", true);
        traceOnly.putExtra("trace_capability", capabilityId);
        traceOnly.putExtra("trace_run_index", runIndex);
        traceOnly.putExtra("trace_s3a_ns", s3aNs);
        traceOnly.putExtra("trace_s3b_ns", s3bNs);
        traceOnly.putExtra("trace_phase", "s3");
        try {
            callback.send(this, 0, traceOnly);
        } catch (PendingIntent.CanceledException e) {
            Log.e("MCPDemo", "Trace callback canceled", e);
        }

        // 4️⃣ Stop service instance
        stopSelf(startId);

        return START_NOT_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        clockInManager = new ClockInManager(this);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // broadcast to MainActivity
    private void notifyActivityToClick() {
        Intent intent = new Intent("ACTION_AI_CLICK");
        // 限制广播只发给自己，符合 Android 安全规范
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    /**
     * handle query_clock_in
     *
     * @return JSONObject containing the result
     */
    private JSONObject handleQueryClockIn(
            JSONObject json,
            String requestId,
            String capabilityId,
            int runIndex
    ) throws JSONException {
        long q1StartNs = SystemClock.elapsedRealtimeNanos();
        String date = json.optString("date", "");
        long q1EndNs = SystemClock.elapsedRealtimeNanos();
        LatencyTraceLogger.logSpan(
                this, requestId, capabilityId, runIndex,
                "S2_QUERY_READ_DATE", q1StartNs, q1EndNs, true, null, null
        );

        long q2StartNs = SystemClock.elapsedRealtimeNanos();
        boolean hasClockedIn = clockInManager.hasClockedIn(date);
        long q2EndNs = SystemClock.elapsedRealtimeNanos();
        LatencyTraceLogger.logSpan(
                this, requestId, capabilityId, runIndex,
                "S2_QUERY_HAS_CLOCKED_IN", q2StartNs, q2EndNs, true, null, null
        );

        Log.d("MCP", "Query " + date + " clock-in status: " + hasClockedIn);

        long q3StartNs = SystemClock.elapsedRealtimeNanos();
        JSONObject response = new JSONObject();
        response.put("status", "success");
        JSONObject data = new JSONObject();
        data.put("date", date);
        data.put("has_clocked_in", hasClockedIn);
        if (hasClockedIn) {
            response.put("message", "Has clocked in.");
        } else {
            response.put("message", "Hasn't clocked in.");
        }
        response.put("data", data);
        long q3EndNs = SystemClock.elapsedRealtimeNanos();
        LatencyTraceLogger.logSpan(
                this, requestId, capabilityId, runIndex,
                "S2_QUERY_BUILD_RESULT_JSON", q3StartNs, q3EndNs, true, null, null
        );
        return response;
    }

    /**
     * handle make_up_clock_in
     *
     * @return JSONObject containing the result
     */
    private JSONObject handleMakeUpClockIn(
            JSONObject json,
            String requestId,
            String capabilityId,
            int runIndex
    ) throws JSONException {
        long m1StartNs = SystemClock.elapsedRealtimeNanos();
        String date = json.optString("date", "");
        long m1EndNs = SystemClock.elapsedRealtimeNanos();
        LatencyTraceLogger.logSpan(
                this, requestId, capabilityId, runIndex,
                "S2_MAKEUP_READ_DATE", m1StartNs, m1EndNs, true, null, null
        );

        long m2StartNs = SystemClock.elapsedRealtimeNanos();
        clockInManager.clockInDate(date);
        long m2EndNs = SystemClock.elapsedRealtimeNanos();
        LatencyTraceLogger.logSpan(
                this, requestId, capabilityId, runIndex,
                "S2_MAKEUP_WRITE_CLOCK_IN", m2StartNs, m2EndNs, true, null, null
        );

        Log.d("MCP", "Make up clock-in for " + date);

        // 发送广播通知 UI 更新
        long m3StartNs = SystemClock.elapsedRealtimeNanos();
        Intent intent = new Intent("ACTION_MAKE_UP_CLOCK_IN_SUCCESS");
        intent.putExtra("date", date);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
        long m3EndNs = SystemClock.elapsedRealtimeNanos();
        LatencyTraceLogger.logSpan(
                this, requestId, capabilityId, runIndex,
                "S2_MAKEUP_BROADCAST_UI_REFRESH", m3StartNs, m3EndNs, true, null, null
        );

        long m4StartNs = SystemClock.elapsedRealtimeNanos();
        JSONObject response = new JSONObject();
        response.put("status", "success");
        response.put("message", "Make up clock-in successful for " + date); // Unify: add message to the outermost layer

        JSONObject data = new JSONObject();
        data.put("date", date);
        response.put("data", data);
        long m4EndNs = SystemClock.elapsedRealtimeNanos();
        LatencyTraceLogger.logSpan(
                this, requestId, capabilityId, runIndex,
                "S2_MAKEUP_BUILD_RESULT_JSON", m4StartNs, m4EndNs, true, null, null
        );
        return response;
    }
}
