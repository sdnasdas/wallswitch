package com.example.wallswitch;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.work.Data;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 定时切换调度（WorkManager 周期任务，参照 Muzei 等成熟应用的做法）：
 * 系统强制最小间隔 15 分钟；任务批量合并执行、Doze 中自动推迟、重启/覆盖安装后自动恢复，省电且可靠。
 * 定时总开关关闭时不排定任何任务。
 *
 * 倒计时数据来源：WorkManager 公开 API {@link WorkInfo#getNextScheduleTimeMillis()}——注意它只表示
 * 「最早具备运行条件的时刻」（受系统调度/Doze 影响，真实执行几乎不会恰好在这一刻，也可能已是过去时间），
 * 因此小组件显示的是「预计」倒计时，并在等待系统调度时改显示“待切换”。
 */
public class TimerScheduler {

    // WorkManager 唯一任务名前缀
    private static final String WORK_PREFIX = "switch_";
    // SharedPreferences 文件名与「定时切换」总开关 key（默认关闭：关闭时不排定任何任务）
    private static final String PREFS_NAME = "settings";
    private static final String KEY_TIMER_ENABLED = "timer_enabled";
    // 下次触发时间（wall clock 毫秒）的 key 前缀，供小组件倒计时显示
    private static final String KEY_NEXT_TRIGGER_PREFIX = "next_trigger_";
    // 查询 WorkManager 任务状态的单线程执行器（ListenableFuture 回调，避免占用主线程）
    private static final Executor EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "timer-sync");
        thread.setDaemon(true);
        return thread;
    });

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
            // 只补一个保守估计（首次排定或记录缺失时）；真实值由 syncFromWorkManager 用 WorkManager 校准，
            // 避免每次打开应用都把倒计时往后推一个间隔
            if (recordedTrigger(ctx, libId) <= 0) {
                applyTrigger(ctx, libId, System.currentTimeMillis() + seconds * 1000L);
            }
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

    /** 按当前设置重排所有启用库的定时，并用 WorkManager 的真实调度时间校准倒计时（回到应用时自愈调用）。 */
    public static void scheduleAll(Context ctx) {
        if (!isTimerEnabled(ctx)) {
            return;
        }
        for (LibraryStore.Library lib : LibraryStore.load(ctx)) {
            if (lib.enabled) {
                schedule(ctx, lib.id);
            }
        }
        syncFromWorkManager(ctx);
    }

    /** 取消所有库的定时任务（含已停用库，清掉历史遗留）。 */
    public static void cancelAll(Context ctx) {
        for (LibraryStore.Library lib : LibraryStore.load(ctx)) {
            cancel(ctx, lib.id);
        }
    }

    /** 切换完成后记录下次触发时间并刷新小组件（Worker 每次跑完调用：下次约在 当前 + 间隔）。 */
    public static void noteTrigger(Context ctx, String libId) {
        LibraryStore.Library lib = LibraryStore.get(ctx, libId);
        if (lib == null) {
            return;
        }
        applyTrigger(ctx, libId, System.currentTimeMillis() + intervalSeconds(lib) * 1000L);
        // 随即用 WorkManager 的调度时间校准（监听器执行在后台线程）
        syncFromWorkManager(ctx);
    }

    /**
     * 向 WorkManager 查询各库周期任务的状态与「最早可运行时间」，写入本地记录并刷新小组件。
     * 顺带自愈：任务不在排队（被系统/厂商清理、被取消）时自动重新排定。
     */
    public static void syncFromWorkManager(Context ctx) {
        if (!isTimerEnabled(ctx)) {
            return;
        }
        WorkManager workManager;
        try {
            workManager = WorkManager.getInstance(ctx);
        } catch (Exception e) {
            return;
        }
        // 回调在后台线程执行，统一改用 Application Context，避免长期持有 Activity
        final Context app = ctx.getApplicationContext();
        for (LibraryStore.Library lib : LibraryStore.load(app)) {
            if (!lib.enabled) {
                continue;
            }
            String libId = lib.id;
            try {
                ListenableFuture<List<WorkInfo>> future =
                        workManager.getWorkInfosForUniqueWork(WORK_PREFIX + libId);
                future.addListener(() -> onQueried(app, libId, future), EXECUTOR);
            } catch (Exception ignored) {
            }
        }
    }

    /** WorkManager 查询回调（后台线程）：ENQUEUED/RUNNING 时取其最早可运行时间，否则重新排定。 */
    private static void onQueried(Context ctx, String libId, ListenableFuture<List<WorkInfo>> future) {
        boolean enqueued = false;
        long trigger = -1L;
        try {
            List<WorkInfo> infos = future.get();
            if (infos != null) {
                for (WorkInfo info : infos) {
                    WorkInfo.State state = info.getState();
                    if (state != WorkInfo.State.ENQUEUED && state != WorkInfo.State.RUNNING) {
                        continue;
                    }
                    enqueued = true;
                    long t = info.getNextScheduleTimeMillis();
                    // 合法值才采纳（可能为 Long.MAX_VALUE 表示未排定，也可能已是过去时间）
                    if (t > 0 && t < Long.MAX_VALUE && (trigger <= 0 || t < trigger)) {
                        trigger = t;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        if (!enqueued) {
            // 任务被系统或厂商清理：重新排定（schedule 内部不再回调本方法，无循环风险）
            schedule(ctx, libId);
            return;
        }
        if (trigger > 0) {
            applyTrigger(ctx, libId, trigger);
        }
    }

    /** 写入下次触发时间并刷新小组件（值未变化时不动，避免无意义的刷新）。 */
    private static void applyTrigger(Context ctx, String libId, long triggerMillis) {
        if (recordedTrigger(ctx, libId) == triggerMillis) {
            return;
        }
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putLong(KEY_NEXT_TRIGGER_PREFIX + libId, triggerMillis)
                .apply();
        WidgetProvider.updateWidget(ctx);
    }

    /** 已记录的下次触发时间（毫秒），无记录返回 -1。 */
    private static long recordedTrigger(Context ctx, String libId) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getLong(KEY_NEXT_TRIGGER_PREFIX + libId, -1L);
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