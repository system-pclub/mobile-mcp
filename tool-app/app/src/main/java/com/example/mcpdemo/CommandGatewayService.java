package com.example.mcpdemo;

import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import org.json.JSONArray;
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

        String requestStr = intent.getStringExtra("request");
        PendingIntent callback = intent.getParcelableExtra("mcp_callback");

        if (callback == null || requestStr == null) {
            Log.e("MCPDemo", "Missing request/callback");
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        // 1. Parse JSON
        JSONObject requestObj;
        try {
            requestObj = new JSONObject(requestStr);
        } catch (JSONException e) {
            Log.e("MCPDemo", "request is not json");
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        String requestId = requestObj.optString("id");
        if (requestId.isEmpty()) {
            Log.e("MCPDemo", "Missing request id");
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        String resultJson;
        // Execute MCP capability
        JSONObject result = new JSONObject();

        try {
            JSONObject commandJson = requestObj.optJSONObject("capability");
            if (commandJson == null) {
                Log.e("MCPDemo", "Missing capability");
                stopSelf(startId);
                return START_NOT_STICKY;
            }

            Log.d("MCPDemo", "Received MCP command: " + commandJson);
            String capabilityId = commandJson.optString("id");

            // 2. Route and dispatch
            switch (capabilityId) {
                case "clock_in_today":
                    // 3. Execute
                    notifyActivityToClick();
                    result.put("status", "success");
                    result.put("message", "Clock in successfully!");
                    break;
                case "query_clock_in":
                    result = handleQueryClockIn(commandJson);
                    break;
                case "make_up_clock_in":
                    result = handleMakeUpClockIn(commandJson);
                    break;
                default:
                    Log.e("MCP", "Received unknown capability ID: " + capabilityId);
                    result.put("status", "failure");
                    result.put("message", "Unknown capability ID: " + capabilityId);
                    break;
            }
            result.put("id", requestId);
            resultJson = result.toString();
        } catch (Exception e) {
            Log.e("MCP", "JSON parsing or execution exception", e);
            try {
                result.put("id", requestId);
                result.put("status", "failure");
                result.put("message", e.getMessage());
                resultJson = result.toString();
            } catch (JSONException jsonException) {
                resultJson = "{\"id\":\"" + requestId + ", \"status\":\"failure\", \"message\":\"" + e.getMessage() + "\"}";
            }
        }

        // Send result back to LLM-app
        Intent back = new Intent();
        back.putExtra("mcp_request_id", requestId);
        back.putExtra("response", resultJson);

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
        JSONObject response = new JSONObject();
        String argsStr = json.optString("args");
        JSONObject args = new JSONObject(argsStr);
        JSONObject capabilityRes = new JSONObject();
        capabilityRes.put("id", json.optString("id"));
        String date = args.optString("date", "");
        if (date.isEmpty()) {
            Log.e("MCPDemo", "Missing args");
            response.put("status", "failure");
            response.put("message", "Missing args.");
            return response;
        }

        boolean hasClockedIn = clockInManager.hasClockedIn(date);
        Log.d("MCP", "Query " + date + " clock-in status: " + hasClockedIn);

        response.put("status", "success");
        JSONArray output = new JSONArray();
        JSONObject dateObj = new JSONObject();
        dateObj.put("name", "date");
        dateObj.put("type", "string");
        dateObj.put("value", date);
        output.put(dateObj);
        JSONObject boolObj = new JSONObject();
        boolObj.put("name", "has_clocked_in");
        boolObj.put("type", "boolean");
        boolObj.put("value", hasClockedIn);
        output.put(boolObj);
        if (hasClockedIn) {
            response.put("message", "Has clocked in.");
        } else {
            response.put("message", "Hasn't clocked in.");
        }
        capabilityRes.put("output", output);
        response.put("capability", capabilityRes);
        return response;
    }

    /**
     * Handle make-up clock-in command
     *
     * @return JSONObject containing the result
     */
    private JSONObject handleMakeUpClockIn(JSONObject json) throws JSONException {
        JSONObject response = new JSONObject();
        String argsStr = json.optString("args");
        JSONObject args = new JSONObject(argsStr);
        JSONObject capabilityRes = new JSONObject();
        capabilityRes.put("id", json.optString("id"));
        String date = json.optString("date", "");
        if (date.isEmpty()) {
            Log.e("MCPDemo", "Missing args");
            response.put("status", "failure");
            response.put("message", "Missing args.");
            return response;
        }

        clockInManager.clockInDate(date);
        Log.d("MCP", "Make up clock-in for " + date);

        // Send broadcast to notify UI update
        Intent intent = new Intent("ACTION_MAKE_UP_CLOCK_IN_SUCCESS");
        intent.putExtra("date", date);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);

        response.put("status", "success");
        response.put("message", "Make up clock-in successful for " + date); // Unify: add message to the outermost layer

        JSONArray output = new JSONArray();
        JSONObject dateObj = new JSONObject();
        dateObj.put("name", "date");
        dateObj.put("type", "string");
        dateObj.put("value", date);
        output.put(dateObj);
        JSONObject boolObj = new JSONObject();
        boolObj.put("name", "success");
        boolObj.put("type", "boolean");
        boolObj.put("value", "true");
        output.put(boolObj);

        capabilityRes.put("output", output);
        response.put("capability", capabilityRes);
        return response;
    }
}