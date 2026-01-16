package com.example.mcpdemo;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;
import org.json.JSONObject;

public class CommandGatewayService extends Service {

    // 实现 AIDL 接口
    private final ICommandGateway.Stub binder = new ICommandGateway.Stub() {
        @Override
        public void invoke(String commandJson) {
            Log.d("MCP", "收到指令: " + commandJson);

            // 判空检查，增加健壮性
            if (commandJson == null) return;

            try {
                // 1. 解析 JSON
                JSONObject json = new JSONObject(commandJson);
                String capabilityId = json.optString("capability");

                // 2. 路由分发
                switch (capabilityId) {
                    case "click_main_button":
                        // 3. 执行逻辑
                        notifyActivityToClick();
                        break;
                    default:
                        Log.e("MCP", "收到未知能力ID: " + capabilityId);
                }
            } catch (Exception e) {
                Log.e("MCP", "JSON解析异常", e);
                e.printStackTrace();
            }
        }
    };

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
}