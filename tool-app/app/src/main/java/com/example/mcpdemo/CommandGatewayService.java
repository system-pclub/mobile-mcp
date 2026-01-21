package com.example.mcpdemo;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import org.json.JSONObject;
import org.json.JSONException;

public class CommandGatewayService extends Service {

    private CheckInManager checkInManager;

    // 实现 AIDL 接口
    private final ICommandGateway.Stub binder = new ICommandGateway.Stub() {
        @Override
        public String invoke(String commandJson) throws RemoteException {
            Log.d("MCP", "收到指令: " + commandJson);
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
                    case "check_in_today":
                        // 3. 执行逻辑
                        notifyActivityToClick();
                        response.put("status", "success");
                        response.put("message", "Check in successfully!");
                        break;
                    case "query_check_in":
                        response = handleQueryCheckIn(json);
                        break;
                    case "make_up_check_in":
                        response = handleMakeUpCheckIn(json);
                        break;
                    default:
                        Log.e("MCP", "收到未知能力ID: " + capabilityId);
                        response.put("status", "error");
                        response.put("message", "Unknown capability ID: " + capabilityId);
                        break;
                }
            } catch (Exception e) {
                Log.e("MCP", "JSON解析或执行异常", e);
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
        checkInManager = new CheckInManager(this);
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
    private JSONObject handleQueryCheckIn(JSONObject json) throws JSONException {
        String date = json.optString("date", "");
        boolean hasCheckedIn = checkInManager.hasCheckedIn(date);

        Log.d("MCP", "查询 " + date + " 打卡状态: " + hasCheckedIn);

        JSONObject response = new JSONObject();
        response.put("status", "success");
        JSONObject data = new JSONObject();
        data.put("date", date);
        data.put("has_checked_in", hasCheckedIn);
        if (hasCheckedIn) {
            response.put("message", "Has checked in.");
        } else {
            response.put("message", "Hasn't checked in.");
        }
        response.put("data", data);
        return response;
    }

    /**
     * 处理补卡命令
     *
     * @return JSONObject containing the result
     */
    private JSONObject handleMakeUpCheckIn(JSONObject json) throws JSONException {
        String date = json.optString("date", "");
        checkInManager.checkInDate(date);

        Log.d("MCP", "为 " + date + " 补卡");

        // 发送广播通知 UI 更新
        Intent intent = new Intent("ACTION_MAKE_UP_CHECK_IN_SUCCESS");
        intent.putExtra("date", date);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);

        JSONObject response = new JSONObject();
        response.put("status", "success");
        JSONObject data = new JSONObject();
        data.put("date", date);
        data.put("message", "Make up check-in successful for " + date);
        response.put("data", data);
        return response;
    }
}
