package com.example.mcpdemo;

import android.content.Context;
import android.content.SharedPreferences;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;

/**
 * 打卡管理器：处理打卡数据的存储和查询
 */
public class CheckInManager {

    private static final String PREF_NAME = "check_in_data";
    private static final String KEY_PREFIX = "check_in_";
    private static final String DATE_FORMAT = "yyyy-MM-dd";
    private final SharedPreferences mSharedPreferences;
    private final SimpleDateFormat mDateFormat;

    public CheckInManager(Context context) {
        this.mSharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        this.mDateFormat = new SimpleDateFormat(DATE_FORMAT, Locale.getDefault());
    }

    /**
     * 重置/清空所有打卡数据
     */
    public void resetCheckInData() {
        mSharedPreferences.edit().clear().apply();
    }

    /**
     * 获取今天的日期字符串
     */
    private String getTodayString() {
        return mDateFormat.format(new Date());
    }

    /**
     * 记录打卡（默认为今天）
     */
    public void checkInToday() {
        checkInDate(getTodayString());
    }

    /**
     * 记录指定日期的打卡
     *
     * @param dateString 日期字符串，格式：yyyy-MM-dd
     */
    public void checkInDate(String dateString) {
        mSharedPreferences.edit()
                .putBoolean(KEY_PREFIX + dateString, true)
                .apply();
    }

    /**
     * 查询指定日期是否打卡
     *
     * @param dateString 日期字符串，格式：yyyy-MM-dd
     * @return true 如果已打卡，false 如果未打卡
     */
    public boolean hasCheckedIn(String dateString) {
        return mSharedPreferences.getBoolean(KEY_PREFIX + dateString, false);
    }

    /**
     * 查询今天是否打卡
     */
    public boolean hasCheckedInToday() {
        return hasCheckedIn(getTodayString());
    }

    /**
     * 获取指定月份的打卡日历数据
     *
     * @param year 年份
     * @param month 月份（1-12）
     * @return 返回一个 HashMap，key 是日期（1-31），value 是是否打卡
     */
    public HashMap<Integer, Boolean> getMonthCheckInData(int year, int month) {
        HashMap<Integer, Boolean> result = new HashMap<>();
        
        Calendar calendar = Calendar.getInstance();
        calendar.set(year, month - 1, 1);
        
        int lastDay = calendar.getActualMaximum(Calendar.DAY_OF_MONTH);
        
        for (int day = 1; day <= lastDay; day++) {
            calendar.set(Calendar.DAY_OF_MONTH, day);
            String dateString = mDateFormat.format(calendar.getTime());
            result.put(day, hasCheckedIn(dateString));
        }
        
        return result;
    }


    /**
     * 获取连续打卡天数
     */
    public int getConsecutiveCheckInDays() {
        int count = 0;
        Calendar calendar = Calendar.getInstance();
        
        while (true) {
            String dateString = mDateFormat.format(calendar.getTime());
            if (hasCheckedIn(dateString)) {
                count++;
                calendar.add(Calendar.DAY_OF_MONTH, -1);
            } else {
                break;
            }
        }
        
        return count;
    }
}
