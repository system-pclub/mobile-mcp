package com.example.mcpdemo;

import android.content.Context;
import android.content.SharedPreferences;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;

/**
 * Clock In Manager: Handles storage and query of clock-in data
 */
public class ClockInManager {

    private static final String PREF_NAME = "clock_in_data";
    private static final String KEY_PREFIX = "clock_in_";
    private static final String DATE_FORMAT = "yyyy-MM-dd";
    private final SharedPreferences mSharedPreferences;
    private final SimpleDateFormat mDateFormat;

    public ClockInManager(Context context) {
        this.mSharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        this.mDateFormat = new SimpleDateFormat(DATE_FORMAT, Locale.getDefault());
    }

    /**
     * Reset/Clear all clock-in data
     */
    public void resetClockInData() {
        mSharedPreferences.edit().clear().apply();
    }

    /**
     * Get today's date string
     */
    private String getTodayString() {
        return mDateFormat.format(new Date());
    }

    /**
     * Record clock-in (default for today)
     */
    public void clockInToday() {
        clockInDate(getTodayString());
    }

    /**
     * Record clock-in for a specific date
     *
     * @param dateString Date string, format: yyyy-MM-dd
     */
    public void clockInDate(String dateString) {
        mSharedPreferences.edit()
                .putBoolean(KEY_PREFIX + dateString, true)
                .apply();
    }

    /**
     * Query if clocked in on a specific date
     *
     * @param dateString Date string, format: yyyy-MM-dd
     * @return true if clocked in, false otherwise
     */
    public boolean hasClockedIn(String dateString) {
        return mSharedPreferences.getBoolean(KEY_PREFIX + dateString, false);
    }

    /**
     * Query if clocked in today
     */
    public boolean hasClockedInToday() {
        return hasClockedIn(getTodayString());
    }

    /**
     * Get clock-in calendar data for a specific month
     *
     * @param year Year
     * @param month Month (1-12)
     * @return Returns a HashMap, key is day (1-31), value is whether clocked in
     */
    public HashMap<Integer, Boolean> getMonthClockInData(int year, int month) {
        HashMap<Integer, Boolean> result = new HashMap<>();
        
        Calendar calendar = Calendar.getInstance();
        calendar.set(year, month - 1, 1);
        
        int lastDay = calendar.getActualMaximum(Calendar.DAY_OF_MONTH);
        
        for (int day = 1; day <= lastDay; day++) {
            calendar.set(Calendar.DAY_OF_MONTH, day);
            String dateString = mDateFormat.format(calendar.getTime());
            result.put(day, hasClockedIn(dateString));
        }
        
        return result;
    }


    /**
     * Get consecutive clock-in days
     */
    public int getConsecutiveClockInDays() {
        int count = 0;
        Calendar calendar = Calendar.getInstance();
        
        while (true) {
            String dateString = mDateFormat.format(calendar.getTime());
            if (hasClockedIn(dateString)) {
                count++;
                calendar.add(Calendar.DAY_OF_MONTH, -1);
            } else {
                break;
            }
        }
        
        return count;
    }
}
