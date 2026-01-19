package com.example.mcpdemo;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

public class CommandGatewayService extends Service {

    private static final String TAG = "CommandGatewayService";

    // Action for the broadcast to MainActivity
    public static final String ACTION_PERFORM_CHECK_IN = "com.example.mcpdemo.ACTION_PERFORM_CHECK_IN";

    private final ICommandGateway.Stub binder = new ICommandGateway.Stub() {
        @Override
        public void invoke(String commandJson) {
            Log.d(TAG, "Received command: " + commandJson);
            try {
                JSONObject json = new JSONObject(commandJson);
                String capabilityId = json.optString("capabilityId");

                if ("check_in".equals(capabilityId)) {
                    // If the capability is 'check_in', send a broadcast to the activity.
                    Intent intent = new Intent(ACTION_PERFORM_CHECK_IN);
                    sendBroadcast(intent);
                }
            } catch (JSONException e) {
                Log.e(TAG, "Failed to parse command JSON", e);
            }
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        // Return the binder interface for clients to connect.
        return binder;
    }
}