package com.example.mcpdemo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
//import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private Button myButton;

    // 广播接收器：听到 Service 喊话就执行
    private final BroadcastReceiver aiCommandReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // 安全检查：确认动作对不对
            if ("ACTION_AI_CLICK".equals(intent.getAction())) {
                if (myButton != null) {
                    // 模拟点击，效果和手指按下去一样
                    myButton.performClick();
                    Toast.makeText(context, "AI 远程触发了点击！", Toast.LENGTH_SHORT).show();
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        myButton = findViewById(R.id.btn_action);

        // 设置手动点击逻辑
        myButton.setOnClickListener(v -> {
            Toast.makeText(this, "手动点击了按钮", Toast.LENGTH_SHORT).show();
        });

        // 注册广播
        IntentFilter filter = new IntentFilter("ACTION_AI_CLICK");

        // RECEIVER_NOT_EXPORTED 表示这个广播只接收自己 App 发出的，防黑客
        ContextCompat.registerReceiver(
                this,
                aiCommandReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
        );
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 注销，防止内存泄漏
        unregisterReceiver(aiCommandReceiver);
    }
}