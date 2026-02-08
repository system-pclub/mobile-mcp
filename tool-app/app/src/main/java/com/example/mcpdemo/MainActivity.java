package com.example.mcpdemo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private Button myButton;
    private TextView tvStatus;
    private TextView tvConsecutive;
    private TextView tvMonthTitle;
    private GridLayout calendarGrid;
    private TextView tvLog;

    private ClockInManager clockInManager;
    private Calendar currentCalendar;
    private SimpleDateFormat monthFormat;
    private SimpleDateFormat dayFormat;
    private SimpleDateFormat logTimeFormat;

    // Broadcast receiver: listens for Service commands (for AI remote triggering)
    private final BroadcastReceiver aiCommandReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if ("ACTION_AI_CLICK".equals(action)) {
                if (myButton != null) {
                    myButton.performClick();
                }
            } else if ("ACTION_MAKE_UP_CLOCK_IN_SUCCESS".equals(action)) {
                String date = intent.getStringExtra("date");
                refreshCalendar();
                updateClockInStatus();
                addLog("Make-up clock-in successful: " + date);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize components
        myButton = findViewById(R.id.btn_action);
        tvStatus = findViewById(R.id.tv_status);
        tvConsecutive = findViewById(R.id.tv_consecutive);
        tvMonthTitle = findViewById(R.id.tv_month_title);
        calendarGrid = findViewById(R.id.calendar_grid);
        tvLog = findViewById(R.id.tv_log);
        Button btnPrevMonth = findViewById(R.id.btn_prev_month);
        Button btnNextMonth = findViewById(R.id.btn_next_month);

        // Initialize clock-in manager
        clockInManager = new ClockInManager(this);
        // Reset data on each launch
        clockInManager.resetClockInData();

        // Initialize date formatters
        monthFormat = new SimpleDateFormat("MMM yyyy", Locale.getDefault());
        dayFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
        logTimeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

        // Initialize calendar
        currentCalendar = Calendar.getInstance();

        // Set clock-in button click logic
        myButton.setOnClickListener(v -> {
            if (clockInManager.hasClockedInToday()) {
                addLog("Already clocked in today");
                Toast.makeText(MainActivity.this, "You have already clocked in today", Toast.LENGTH_SHORT).show();
            } else {
                clockInManager.clockInToday();
                updateClockInStatus();
                refreshCalendar();
                addLog("Clocked in successfully today");
            }
        });

        // Previous month button
        btnPrevMonth.setOnClickListener(v -> {
            currentCalendar.add(Calendar.MONTH, -1);
            refreshCalendar();
        });

        // Next month button
        btnNextMonth.setOnClickListener(v -> {
            currentCalendar.add(Calendar.MONTH, 1);
            refreshCalendar();
        });

        // 初始化显示
        updateClockInStatus();
        refreshCalendar();
        addLog("Application started");

        // Register AI command broadcast receiver
        IntentFilter filter = new IntentFilter();
        filter.addAction("ACTION_AI_CLICK");
        filter.addAction("ACTION_MAKE_UP_CLOCK_IN_SUCCESS");
        ContextCompat.registerReceiver(this, aiCommandReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    /**
     * Add a log entry
     */
    private void addLog(String message) {
        String currentTime = logTimeFormat.format(new Date());
        String logMessage = currentTime + ": " + message + "\n";
        tvLog.append(logMessage);
    }

    /**
     * Update clock-in status display
     */
    private void updateClockInStatus() {
        if (clockInManager.hasClockedInToday()) {
            tvStatus.setText(R.string.today_clocked);
            tvStatus.setTextColor(Color.parseColor("#1E8E3E"));
        } else {
            tvStatus.setText(R.string.today_not_clocked);
            tvStatus.setTextColor(Color.parseColor("#D93025"));
        }

        int consecutive = clockInManager.getConsecutiveClockInDays();
        tvConsecutive.setText(String.format(getString(R.string.consecutive_days), consecutive));
    }

    /**
     * Refresh calendar display
     */
    private void refreshCalendar() {
        tvMonthTitle.setText(monthFormat.format(currentCalendar.getTime()));
        calendarGrid.removeAllViews();

        int year = currentCalendar.get(Calendar.YEAR);
        int month = currentCalendar.get(Calendar.MONTH) + 1;
        HashMap<Integer, Boolean> clockInData = clockInManager.getMonthClockInData(year, month);

        Calendar cal = (Calendar) currentCalendar.clone();
        cal.set(Calendar.DAY_OF_MONTH, 1);
        int firstDayOfWeek = cal.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY;
        if (firstDayOfWeek < 0) firstDayOfWeek += 7;

        int lastDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH);

        for (int i = 0; i < firstDayOfWeek; i++) {
            calendarGrid.addView(createDayView("", false, false, false));
        }

        for (int day = 1; day <= lastDay; day++) {
            boolean isClockedIn = Boolean.TRUE.equals(clockInData.get(day));
            boolean isToday = isToday(year, month, day);
            boolean isPast = isPast(year, month, day);

            TextView dayView = createDayView(String.valueOf(day), isClockedIn, isToday, isPast);

            // Only set long-press make-up for past unchecked dates
            if (isPast && !isClockedIn) {
                final int finalDay = day;
                dayView.setOnLongClickListener(v -> {
                    showMakeUpClockInDialog(year, month, finalDay);
                    return true;
                });
            }
            calendarGrid.addView(dayView);
        }
    }

    /**
     * Create a day view with styling based on status
     */
    private TextView createDayView(String day, boolean isClockedIn, boolean isToday, boolean isPast) {
        TextView textView = new TextView(this);
        textView.setText(day);
        textView.setTextSize(14);
        textView.setGravity(android.view.Gravity.CENTER);
        
        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = 0;
        params.height = 120; // Fixed height for a better grid look
        params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        params.setMargins(4, 4, 4, 4);
        textView.setLayoutParams(params);

        if (!day.isEmpty()) {
            if (isToday) {
                textView.setTextColor(Color.WHITE);
                textView.setBackgroundColor(isClockedIn ? Color.parseColor("#34A853") : Color.parseColor("#4285F4"));
            } else if (isClockedIn) {
                textView.setTextColor(Color.parseColor("#1E8E3E"));
                textView.setBackgroundColor(Color.parseColor("#E6F4EA"));
            } else if (isPast) {
                textView.setTextColor(Color.parseColor("#3C4043"));
                textView.setBackgroundColor(Color.parseColor("#F1F3F4"));
            } else { // Future
                textView.setTextColor(Color.parseColor("#70757A"));
                textView.setBackgroundColor(Color.TRANSPARENT);
            }
        }
        return textView;
    }

    private boolean isToday(int year, int month, int day) {
        Calendar today = Calendar.getInstance();
        return today.get(Calendar.YEAR) == year && today.get(Calendar.MONTH) + 1 == month && today.get(Calendar.DAY_OF_MONTH) == day;
    }

    private boolean isPast(int year, int month, int day) {
        Calendar target = Calendar.getInstance();
        target.set(year, month - 1, day);
        Calendar today = Calendar.getInstance();
        target.set(Calendar.HOUR_OF_DAY, 0);
        target.set(Calendar.MINUTE, 0);
        target.set(Calendar.SECOND, 0);
        target.set(Calendar.MILLISECOND, 0);
        today.set(Calendar.HOUR_OF_DAY, 0);
        today.set(Calendar.MINUTE, 0);
        today.set(Calendar.SECOND, 0);
        today.set(Calendar.MILLISECOND, 0);
        return target.before(today);
    }

    /**
     * Show make-up clock-in dialog
     */
    private void showMakeUpClockInDialog(int year, int month, int day) {
        Calendar cal = Calendar.getInstance();
        cal.set(year, month - 1, day);
        String dateStr = dayFormat.format(cal.getTime());

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Make-up Clock-in")
                .setMessage("Confirm make-up clock-in for " + dateStr + "?")
                .setPositiveButton("Confirm", (dialog, which) -> {
                    clockInManager.clockInDate(dateStr);
                    refreshCalendar();
                    addLog("Make-up clock-in successful: " + dateStr);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(aiCommandReceiver);
    }
}
