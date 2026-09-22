package com.example.wallswitch;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import java.util.Calendar;

/**
 * 定时任务管理：AlarmManager 调度定时切换（一次一排，触发后由 AlarmReceiver 重排）。
 * 频率约定（SharedPreferences "settings"，key timer_type）：
 * - off：不切换；30 / 60 / 360 / custom：间隔分钟；daily：每天固定时刻
 */
public class AlarmScheduler {

    // SharedPreferences 文件名
    private static final String PREFS_NAME = "settings";
    // 定时类型 key 与取值
    public static final String KEY_TIMER_TYPE = "timer_type";
    public static final String TIMER_OFF = "off";
    public static final String TIMER_30 = "30";
    public static final String TIMER_60 = "60";
    public static final String TIMER_360 = "360";
    public static final String TIMER_DAILY = "daily";
    public static final String TIMER_CUSTOM = "custom";
    // 自定义间隔与每天时刻 key
    public static final String KEY_CUSTOM_MINUTES = "custom_minutes";
    public static final String KEY_DAILY_HOUR = "daily_hour";
    public static final String KEY_DAILY_MINUTE = "daily_minute";
    // 闹钟请求码（schedule 与 cancel 保持一致）
    private static final int REQUEST_CODE = 1001;
    // 自定义间隔缺省分钟数
    private static final int DEFAULT_CUSTOM_MINUTES = 30;

    /** 按当前设置安排下一次定时切换；类型为 off 时不安排。 */
    public static void schedule(Context ctx) {
        // 先取消旧闹钟，避免重复
        cancel(ctx);
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String type = prefs.getString(KEY_TIMER_TYPE, TIMER_OFF);
        long trigger = -1;
        if (TIMER_30.equals(type) || TIMER_60.equals(type) || TIMER_360.equals(type)
                || TIMER_CUSTOM.equals(type)) {
            // 间隔型：30 / 60 / 360 分钟或自定义分钟数
            int minutes = DEFAULT_CUSTOM_MINUTES;
            if (TIMER_30.equals(type)) {
                minutes = 30;
            } else if (TIMER_60.equals(type)) {
                minutes = 60;
            } else if (TIMER_360.equals(type)) {
                minutes = 360;
            } else {
                minutes = prefs.getInt(KEY_CUSTOM_MINUTES, DEFAULT_CUSTOM_MINUTES);
            }
            trigger = System.currentTimeMillis() + minutes * 60_000L;
        } else if (TIMER_DAILY.equals(type)) {
            // 每天固定时刻：取下一个 daily_hour:daily_minute（今日已过则 +1 天）
            int hour = prefs.getInt(KEY_DAILY_HOUR, 8);
            int minute = prefs.getInt(KEY_DAILY_MINUTE, 0);
            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.HOUR_OF_DAY, hour);
            cal.set(Calendar.MINUTE, minute);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            if (cal.getTimeInMillis() <= System.currentTimeMillis()) {
                cal.add(Calendar.DAY_OF_YEAR, 1);
            }
            trigger = cal.getTimeInMillis();
        } else {
            // off 或未知值：不安排
            return;
        }
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        if (am == null) {
            return;
        }
        Intent intent = new Intent(ctx, AlarmReceiver.class);
        intent.setAction(AlarmReceiver.ACTION_ALARM);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        // 精确闹钟权限被拒（Android 12+ 需用户授权）时降级为非精确闹钟，可能有小延迟但功能可用
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC, trigger, pi);
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC, trigger, pi);
        }
    }

    /** 取消已安排的定时切换。 */
    public static void cancel(Context ctx) {
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        if (am == null) {
            return;
        }
        Intent intent = new Intent(ctx, AlarmReceiver.class);
        intent.setAction(AlarmReceiver.ACTION_ALARM);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.cancel(pi);
    }
}
