package com.example.wallswitch;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.work.Data;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/**
 * 定时切换调度（WorkManager 周期任务，参照 Muzei 等成熟应用的做法）：
 * 系统强制最小间隔 15 分钟；任务批量合并执行、Doze 中自动推迟、重启/覆盖安装后自动恢复，省电且可靠。
 * 定时总开关关闭时不排定任何任务。每次排定都会把下次触发时间写入 prefs，供小组件倒计时显示。
 */
public class TimerScheduler {

    // WorkManager 唯一任务名前缀
    private static final String WORK_PREFIX = "switch_";
    // SharedPreferences 文件名与「定时切换」总开关 key（默认关闭：关闭时不排定任何任务）
    private static final String PREFS_NAME = "settings";
    private static final String KEY_TIMER_ENABLED = "timer_enabled";
    // 下次触发时间（wall clock 毫秒）的 key 前缀，供小组件倒计时显示
    private static final String KEY_NEXT_TRIGGER_PREFIX = "next_trigger_";

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

    /** 该库的定时间隔（秒）：取系统下限（15 分钟）与库设置的较大值，兼容旧版本的秒级历史值。 */
    private static int intervalSeconds(LibraryStore.Library lib) {
        int seconds = lib.intervalSeconds > 0 ? lib.intervalSeconds : LibraryStore.DEFAULT_INTERVAL_SECONDS;
        return Math.max(LibraryStore.MIN_INTERVAL_SECONDS, seconds);
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
        int seconds = intervalSeconds(lib);
        try {
            Data data = new Data.Builder().putString(SwitchWorker.EXTRA_LIB_ID, libId).build();
            PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                    SwitchWorker.class, seconds, TimeUnit.SECONDS)
                    .setInputData(data)
                    .build();
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                    WORK_PREFIX + libId, ExistingPeriodicWorkPolicy.UPDATE, request);
            noteTrigger(ctx, libId);
        } catch (Exception ignored) {
        }
    }

    /** 取消指定库的定时任务，并清除倒计时记录。 */
    public static void cancel(Context ctx, String libId) {
        try {
            WorkManager.getInstance(ctx).cancelUniqueWork(WORK_PREFIX + libId);
        } catch (Exception ignored) {
        }
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .remove(KEY_NEXT_TRIGGER_PREFIX + libId)
                .apply();
        WidgetProvider.updateWidget(ctx);
    }

    /** 按当前设置重排所有启用库的定时（回到应用时自愈调用；总开关关闭时不做任何事）。 */
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

    /** 记录下次触发时间并刷新小组件倒计时（排定或切换后调用）。 */
    public static void noteTrigger(Context ctx, String libId) {
        LibraryStore.Library lib = LibraryStore.get(ctx, libId);
        if (lib == null) {
            return;
        }
        int seconds = intervalSeconds(lib);
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putLong(KEY_NEXT_TRIGGER_PREFIX + libId,
                        System.currentTimeMillis() + seconds * 1000L)
                .apply();
        WidgetProvider.updateWidget(ctx);
    }

    /** 小组件用：所有启用库中最近的下次触发时间（毫秒），无则 null；总开关关闭时返回 null。 */
    public static Long nextTrigger(Context ctx) {
        if (!isTimerEnabled(ctx)) {
            return null;
        }
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        Long best = null;
        for (LibraryStore.Library lib : LibraryStore.load(ctx)) {
            if (!lib.enabled) {
                continue;
            }
            long t = prefs.getLong(KEY_NEXT_TRIGGER_PREFIX + lib.id, -1L);
            if (t >= 0 && (best == null || t < best)) {
                best = t;
            }
        }
        return best;
    }
}