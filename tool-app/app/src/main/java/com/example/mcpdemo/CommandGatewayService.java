package com.example.mcpdemo;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import org.json.JSONObject;
import org.json.JSONException;

public class CommandGatewayService extends Service {

    private ClockInManager clockInManager;

    // 实现 AIDL 接口
    private final ICommandGateway.Stub binder = new ICommandGateway.Stub() {
        @Override
        public String invoke(String commandJson) throws RemoteException {
            Log.d("MCP", "Received command: " + commandJson);
            JSONObject response = new JSONObject();

            // 判空检查，增加健壮性
            if (commandJson == null) {
                try {
                    response.put("status", "error");
                    response.put("message", "commandJson is null");
                } catch (JSONException e) {
                   // This should not happen
                }
                return response.toString();
            }

            try {
                // 1. 解析 JSON
                JSONObject json = new JSONObject(commandJson);
                String capabilityId = json.optString("capability");

                // 2. 路由分发
                switch (capabilityId) {
                    case "clock_in_today":
                        // 3. 执行逻辑
                        notifyActivityToClick();
                        response.put("status", "success");
                        response.put("message", "Clock in successfully!");
                        break;
                    case "query_clock_in":
                        response = handleQueryClockIn(json);
                        break;
                    case "make_up_clock_in":
                        response = handleMakeUpClockIn(json);
                        break;
                    default:
                        Log.e("MCP", "Received unknown capability ID: " + capabilityId);
                        response.put("status", "error");
                        response.put("message", "Unknown capability ID: " + capabilityId);
                        break;
                }
            } catch (Exception e) {
                Log.e("MCP", "JSON parsing or execution exception", e);
                try {
                    response.put("status", "error");
                    response.put("message", e.getMessage());
                } catch (JSONException jsonException) {
                    return "{\"status\":\"error\", \"message\":\"" + e.getMessage() + "\"}";
                }
            }
            return response.toString();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        clockInManager = new ClockInManager(this);
    }

    @Override
    public IBinder onBind(Intent intent) {
        // 当大模型 APP 连接时，返回这个 binder 接口
        return binder;
    }

    // 发送广播给 MainActivity
    private void notifyActivityToClick() {
        Intent intent = new Intent("ACTION_AI_CLICK");
        // 限制广播只发给自己，符合 Android 安全规范
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    /**
     * 处理查询打卡命令
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
     * 处理补卡命令
     *
     * @return JSONObject containing the result
     */
    private JSONObject handleMakeUpClockIn(JSONObject json) throws JSONException {
        String date = json.optString("date", "");
        clockInManager.clockInDate(date);

        Log.d("MCP", "Make up clock-in for " + date);

        // 发送广播通知 UI 更新
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