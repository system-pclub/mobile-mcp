package com.example.mcpdemo;

import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import org.json.JSONObject;
import org.json.JSONException;

public class CommandGatewayService extends Service {

    private ClockInManager clockInManager;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        // Get MCP command JSON
        String commandJson = intent.getStringExtra("mcp_command_json");
        String requestId = intent.getStringExtra("mcp_request_id");
        PendingIntent callback = intent.getParcelableExtra("mcp_callback");

        if (commandJson == null || requestId == null || callback == null) {
            Log.e("MCPDemo", "Missing command/requestId/callback");
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        Log.d("MCPDemo", "Received MCP command: " + commandJson);

        String resultJson;
        // Execute MCP capability

        JSONObject result = new JSONObject();

        try {
            // 1. Parse JSON
            JSONObject json = new JSONObject(commandJson);
            String capabilityId = json.optString("capability");

            // 2. Route and dispatch
            switch (capabilityId) {
                case "clock_in_today":
                    // 3. Execute
                    notifyActivityToClick();
                    result.put("status", "success");
                    result.put("message", "Clock in successfully!");
                    break;
                case "query_clock_in":
                    result = handleQueryClockIn(json);
                    break;
                case "make_up_clock_in":
                    result = handleMakeUpClockIn(json);
                    break;
                default:
                    Log.e("MCP", "Received unknown capability ID: " + capabilityId);
                    result.put("status", "error");
                    result.put("message", "Unknown capability ID: " + capabilityId);
                    break;
            }
            resultJson = result.toString();
        } catch (Exception e) {
            Log.e("MCP", "JSON parsing or execution exception", e);
            try {
                result.put("status", "error");
                result.put("message", e.getMessage());
                resultJson = result.toString();
            } catch (JSONException jsonException) {
                resultJson = "{\"status\":\"error\", \"message\":\"" + e.getMessage() + "\"}";
            }
        }

        // Send result back to LLM-app
        Intent back = new Intent();
        back.putExtra("mcp_request_id", requestId);
        back.putExtra("result_json", resultJson);

        try {
            callback.send(this, 0, back);
        } catch (PendingIntent.CanceledException e) {
            Log.e("MCPDemo", "Callback canceled", e);
        }
        // Stop service instance
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

    // Send broadcast to MainActivity
    private void notifyActivityToClick() {
        Intent intent = new Intent("ACTION_AI_CLICK");
        // Restrict broadcast to own package for security
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    /**
     * Handle query clock-in command
     *
     * @return JSONObject containing the result
     */
    private JSONObject handleQueryClockIn(JSONObject json) throws JSONException {
        String date = json.optString("date", "");
        boolean hasClockedIn = clockInManager.hasClockedIn(date);

        Log.d("MCP", "Query " + date + " clock-in status: " + hasClockedIn);

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
        return response;
    }

    /**
     * Handle make-up clock-in command
     *
     * @return JSONObject containing the result
     */
    private JSONObject handleMakeUpClockIn(JSONObject json) throws JSONException {
        String date = json.optString("date", "");
        clockInManager.clockInDate(date);

        Log.d("MCP", "Make up clock-in for " + date);

        // Send broadcast to notify UI update
        Intent intent = new Intent("ACTION_MAKE_UP_CLOCK_IN_SUCCESS");
        intent.putExtra("date", date);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);

        JSONObject response = new JSONObject();
        response.put("status", "success");
        response.put("message", "Make up clock-in successful for " + date); // Unify: add message to the outermost layer

        JSONObject data = new JSONObject();
        data.put("date", date);
        response.put("data", data);
        return response;
    }
}