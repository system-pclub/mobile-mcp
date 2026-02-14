package com.example.mcpdemo;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.Handler;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;

public class CommandGatewayService extends Service {

    private ClockInManager clockInManager;

    public static final int MSG_INVOKE = 1;
    public static final int MSG_RESULT = 2;

    private final Messenger messenger = new Messenger(
            new IncomingHandler(Looper.getMainLooper(), this)
    );

    @Override
    public IBinder onBind(Intent intent) {
        return messenger.getBinder();
    }

    private static class IncomingHandler extends Handler {
        private final CommandGatewayService service;

        IncomingHandler(Looper looper, CommandGatewayService svc) {
            super(looper);
            this.service = svc;
        }

        @Override
        public void handleMessage(Message msg) {
            if (msg.what != MSG_INVOKE) {
                super.handleMessage(msg);
                return;
            }

            Bundle data = msg.getData();
            if (data == null) return;

            String requestStr = data.getString("request", "");
            Log.d("MCPDemo", "Received MCP request: " + requestStr);
            String resultJson;
            JSONObject result = new JSONObject();

            JSONObject requestObj;
            try {
                requestObj = new JSONObject(requestStr);
            } catch (JSONException e) {
                Log.e("MCPDemo", "request is not json");
                return;
            }

            String requestId = requestObj.optString("id");
            if (requestId.isEmpty()) {
                Log.e("MCPDemo", "Missing request id");
                return;
            }

            try {
                // 1. Parse JSON
                JSONObject commandJson = requestObj.optJSONObject("capability");
                if (commandJson == null) {
                    Log.e("MCPDemo", "Missing capability");
                    return;
                }
                String capabilityId = commandJson.optString("id");

                // 2. Route by capability
                switch (capabilityId) {
                    case "clock_in_today":
                        // 3. Execute business logic
                        service.notifyActivityToClick();
                        result.put("status", "success");
                        result.put("message", "Clock in successfully!");
                        break;
                    case "query_clock_in":
                        result = service.handleQueryClockIn(commandJson);
                        break;
                    case "make_up_clock_in":
                        result = service.handleMakeUpClockIn(commandJson);
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

            // Reply to the caller via msg.replyTo
            Messenger replyTo = msg.replyTo;
            if (replyTo != null) {
                Message reply = Message.obtain(null, MSG_RESULT);
                Bundle out = new Bundle();
                out.putString("mcp_request_id", requestId);
                out.putString("response", resultJson);
                reply.setData(out);

                try {
                    replyTo.send(reply);
                } catch (RemoteException e) {
                    Log.e("MCPDemo", "Failed to reply", e);
                }
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        clockInManager = new ClockInManager(this);
    }

    // Send broadcast to MainActivity
    private void notifyActivityToClick() {
        Intent intent = new Intent("ACTION_AI_CLICK");
        // Restrict broadcast to this app for Android security compliance
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    /**
     * handle query_clock_in
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
     * handle make_up_clock_in
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

        // Send broadcast to notify UI refresh
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
