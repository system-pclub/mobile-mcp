package com.example.mcpdemo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private Button myButton;

    // BroadcastReceiver to listen for commands from the service
    private final BroadcastReceiver commandReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (CommandGatewayService.ACTION_PERFORM_CHECK_IN.equals(intent.getAction())) {
                // Perform a click on the button when the command is received
                if (myButton != null) {
                    myButton.performClick();
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Get view components
        myButton = findViewById(R.id.btn_action);
        TextView logTextView = findViewById(R.id.log);
        TextView dateTextView = findViewById(R.id.tv_date);
        TextView greetingTextView = findViewById(R.id.tv_greeting);

        // Set current date
        String currentDate = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
        dateTextView.setText(currentDate);

        // Set the click listener for the check-in button
        myButton.setOnClickListener(v -> {
            String currentTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
            String logMessage = currentTime + " " + getString(R.string.check_in_success) + "\n";
            logTextView.append(logMessage);
            Toast.makeText(this, getString(R.string.check_in_success), Toast.LENGTH_SHORT).show();
        });

        // Register the BroadcastReceiver
        IntentFilter filter = new IntentFilter(CommandGatewayService.ACTION_PERFORM_CHECK_IN);
        ContextCompat.registerReceiver(this, commandReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Unregister the receiver to prevent memory leaks
        unregisterReceiver(commandReceiver);
    }
}