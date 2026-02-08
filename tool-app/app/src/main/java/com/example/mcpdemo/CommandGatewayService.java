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

            String commandJson = data.getString("mcp_command_json", "");
            String requestId = data.getString("mcp_request_id", "");

            Log.d("MCPDemo", "Received MCP command: " + commandJson);
            String resultJson;
            JSONObject result = new JSONObject();

            try {
                // 1. Parse JSON
                JSONObject json = new JSONObject(commandJson);
                String capabilityId = json.optString("capability");

                // 2. Route by capability
                switch (capabilityId) {
                    case "clock_in_today":
                        // 3. Execute business logic
                        service.notifyActivityToClick();
                        result.put("status", "success");
                        result.put("message", "Clock in successfully!");
                        break;
                    case "query_clock_in":
                        result = service.handleQueryClockIn(json);
                        break;
                    case "make_up_clock_in":
                        result = service.handleMakeUpClockIn(json);
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

            // Reply to the caller via msg.replyTo
            Messenger replyTo = msg.replyTo;
            if (replyTo != null) {
                Message reply = Message.obtain(null, MSG_RESULT);
                Bundle out = new Bundle();
                out.putString("mcp_request_id", requestId);
                out.putString("result_json", resultJson);
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
     * handle make_up_clock_in
     *
     * @return JSONObject containing the result
     */
    private JSONObject handleMakeUpClockIn(JSONObject json) throws JSONException {
        String date = json.optString("date", "");
        clockInManager.clockInDate(date);

        Log.d("MCP", "Make up clock-in for " + date);

        // Send broadcast to notify UI refresh
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
