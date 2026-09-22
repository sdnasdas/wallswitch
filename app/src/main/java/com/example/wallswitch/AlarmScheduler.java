package com.example.wallswitch;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.work.Data;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/**
 * 定时切换调度：为每个启用的壁纸库独立调度，双后端（参照 Muzei 等成熟应用的做法）：
 * - 间隔 ≥ 15 分钟：WorkManager 周期任务（系统批量合并执行、Doze 自动推迟、
 *   应用更新/重启后自动恢复，省电且可靠；系统强制最小间隔 15 分钟）
 * - 间隔 < 15 分钟：AlarmManager 精确闹钟（测试模式；<60 秒时仅在亮屏执行，见 AlarmReceiver）
 * 定时总开关关闭时不排定任何任务。
 */
public class AlarmScheduler {

    // Intent extra：要切换的壁纸库 id（AlarmManager 路径）
    public static final String EXTRA_LIB_ID = "lib_id";
    // 短间隔阈值（秒）：低于该值不用 allow-while-idle 版本，避免 Doze 期间被反复唤醒持续发热
    private static final int WAKE_IDLE_THRESHOLD_SECONDS = 60;
    // WorkManager 周期任务的系统最小间隔（秒）
    private static final int WORK_MIN_INTERVAL_SECONDS = 15 * 60;
    // WorkManager 唯一任务名前缀
    private static final String WORK_PREFIX = "switch_";
    // SharedPreferences 文件名与「定时切换」总开关 key（默认关闭：关闭时不排定任何任务）
    private static final String PREFS_NAME = "settings";
    private static final String KEY_TIMER_ENABLED = "timer_enabled";

    /** 定时切换是否已开启（默认关闭）。 */
    public static boolean isTimerEnabled(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_TIMER_ENABLED, false);
    }

    /** 设置定时切换总开关：关闭时取消全部定时任务，开启时按各库间隔立即重排。 */
    public static void setTimerEnabled(Context ctx, boolean enabled) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_TIMER_ENABLED, enabled)
                .apply();
        if (enabled) {
            scheduleAll(ctx);
        } else {
            cancelAll(ctx);
        }
    }

    /** 为指定库安排定时切换；总开关关闭、库不存在或未启用时不安排。 */
    public static void schedule(Context ctx, String libId) {
        if (!isTimerEnabled(ctx)) {
            return;
        }
        LibraryStore.Library lib = LibraryStore.get(ctx, libId);
        if (lib == null || !lib.enabled) {
            return;
        }
        int seconds = lib.intervalSeconds > 0 ? lib.intervalSeconds : LibraryStore.DEFAULT_INTERVAL_SECONDS;
        if (seconds >= WORK_MIN_INTERVAL_SECONDS) {
            scheduleWork(ctx, libId, seconds);
        } else {
            scheduleAlarm(ctx, libId, seconds);
        }
    }

    /** 取消指定库的定时任务（闹钟与 WorkManager 任务都取消）。 */
    public static void cancel(Context ctx, String libId) {
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        if (am != null) {
            am.cancel(pending(ctx, libId));
        }
        try {
            WorkManager.getInstance(ctx).cancelUniqueWork(WORK_PREFIX + libId);
        } catch (Exception ignored) {
        }
    }

    /** 按当前设置重排所有启用库的定时（开机自启、应用更新、回到应用时调用；总开关关闭时不做任何事）。 */
    public static void scheduleAll(Context ctx) {
        if (!isTimerEnabled(ctx)) {
            return;
        }
        for (LibraryStore.Library lib : LibraryStore.load(ctx)) {
            if (lib.enabled) {
                schedule(ctx, lib.id);
            }
        }
    }

    /** 取消所有库的定时任务（含已停用库，清掉历史遗留）。 */
    public static void cancelAll(Context ctx) {
        for (LibraryStore.Library lib : LibraryStore.load(ctx)) {
            cancel(ctx, lib.id);
        }
    }

    /** WorkManager 路径：≥15 分钟周期切换（唯一任务名，重排即更新；先清掉闹钟路径残留）。 */
    private static void scheduleWork(Context ctx, String libId, int seconds) {
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        if (am != null) {
            am.cancel(pending(ctx, libId));
        }
        try {
            Data data = new Data.Builder().putString(SwitchWorker.EXTRA_LIB_ID, libId).build();
            PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                    SwitchWorker.class, seconds, TimeUnit.SECONDS)
                    .setInputData(data)
                    .build();
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                    WORK_PREFIX + libId, ExistingPeriodicWorkPolicy.UPDATE, request);
        } catch (Exception ignored) {
        }
    }

    /** AlarmManager 路径：<15 分钟的测试间隔（先清掉 WorkManager 路径残留）。 */
    private static void scheduleAlarm(Context ctx, String libId, int seconds) {
        try {
            WorkManager.getInstance(ctx).cancelUniqueWork(WORK_PREFIX + libId);
        } catch (Exception ignored) {
        }
        long trigger = System.currentTimeMillis() + seconds * 1000L;
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        if (am == null) {
            return;
        }
        PendingIntent pi = pending(ctx, libId);
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            // 精确闹钟权限被拒（Android 12+ 需用户授权）时降级为非精确闹钟，可能有小延迟但功能可用
            am.setAndAllowWhileIdle(AlarmManager.RTC, trigger, pi);
        } else if (seconds < WAKE_IDLE_THRESHOLD_SECONDS) {
            // 秒级测试间隔（<60 秒）：普通精确闹钟，不唤醒 Doze 中的设备（配合 AlarmReceiver 亮屏判断）
            am.setExact(AlarmManager.RTC, trigger, pi);
        } else {
            // 1~15 分钟：允许在 Doze 中唤醒
            am.setExactAndAllowWhileIdle(AlarmManager.RTC, trigger, pi);
        }
    }

    /** 构建指定库的闹钟 PendingIntent：请求码 = 库 id 哈希（少量库几乎不会碰撞）。 */
    private static PendingIntent pending(Context ctx, String libId) {
        int requestCode = libId.hashCode() & 0x7fffffff;
        Intent intent = new Intent(ctx, AlarmReceiver.class);
        intent.setAction(AlarmReceiver.ACTION_ALARM);
        intent.putExtra(EXTRA_LIB_ID, libId);
        return PendingIntent.getBroadcast(ctx, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}