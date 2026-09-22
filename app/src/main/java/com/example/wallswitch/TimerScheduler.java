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
    // 每库「上次自动切换时间」（毫秒）与「上次结果」的 key 前缀
    private static final String KEY_LAST_RUN_PREFIX = "last_run_";
    private static final String KEY_LAST_RESULT_PREFIX = "last_result_";
    // 上次执行结果：成功
    public static final String RESULT_OK = "ok";
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

    /** 为指定库安排定时切换，并用 WorkManager 的真实调度时间校准倒计时；总开关关闭/库未启用则不安排。 */
    public static void schedule(Context ctx, String libId) {
        scheduleInternal(ctx, libId);
        syncFromWorkManager(ctx);
    }

    /** 只负责把周期任务交给 WorkManager（不再写估算值：倒计时一律以 WorkManager 的调度时间为准）。 */
    private static void scheduleInternal(Context ctx, String libId) {
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
                // 循环内只排定，最后统一查一次，避免 N 个任务各触发一轮查询
                scheduleInternal(ctx, lib.id);
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

    /** 切换完成后由 Worker 调用：执行一次切换并记账（下次触发时间、结果、小组件刷新）。 */
    public static boolean runNow(Context ctx, String libId) {
        LibraryStore.Library lib = LibraryStore.get(ctx, libId);
        if (lib == null || !lib.enabled) {
            return false;
        }
        // 按库覆盖的范围逐个切换（Switcher 内部会校验范围勾选与库内是否有壁纸）
        boolean okHome = Switcher.next(ctx, libId, true);
        boolean okLock = Switcher.next(ctx, libId, false);
        boolean ok = okHome || okLock;
        long now = System.currentTimeMillis();
        String result = ok ? RESULT_OK : Switcher.lastError();
        // 无论成败都把下次触发时间前移到 当前+间隔：成功即进入下一轮，失败也避免每次刷新都重试
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putLong(KEY_LAST_RUN_PREFIX + libId, now)
                .putString(KEY_LAST_RESULT_PREFIX + libId, result == null ? "failed" : result)
                .putLong(KEY_NEXT_TRIGGER_PREFIX + libId, now + intervalSeconds(lib) * 1000L)
                .apply();
        WidgetProvider.updateWidget(ctx);
        // 与 WorkManager 的真实调度时间对齐（它的值更旧且本轮已执行时不会被采纳）
        syncFromWorkManager(ctx);
        return ok;
    }

    /**
     * 该库本轮是否“已到点且尚未执行”——Worker 与补切共用，避免两边重复切换。
     * 从未排定过（无记录）时，仅在从未执行过的情况下视为到点（对应周期任务的首次立即执行）。
     */
    public static boolean isDue(Context ctx, String libId) {
        long due = recordedTrigger(ctx, libId);
        long last = lastRun(ctx, libId);
        if (due <= 0) {
            return last == 0;
        }
        return due <= System.currentTimeMillis() && last < due;
    }

    /**
     * 补切：系统没能按时执行 WorkManager 任务时（Doze 延后、ROM 冻结后台），
     * 在“设备活跃”的时机（桌面刷新小组件、开机、打开应用）把漏掉的那一轮补上。
     * 必须在后台线程调用；返回是否执行了补切。
     */
    public static boolean catchUp(Context ctx) {
        if (!isTimerEnabled(ctx)) {
            return false;
        }
        boolean did = false;
        for (LibraryStore.Library lib : LibraryStore.load(ctx)) {
            if (!lib.enabled) {
                continue;
            }
            if (isDue(ctx, lib.id)) {
                did |= runNow(ctx, lib.id);
            }
        }
        return did;
    }

    /** 上次执行自动切换（含补切）的时间，无记录返回 0。 */
    public static long lastRun(Context ctx, String libId) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getLong(KEY_LAST_RUN_PREFIX + libId, 0L);
    }

    /** 上次执行结果："ok"、失败原因（异常/解码失败），从未执行返回 null。 */
    public static String lastResult(Context ctx, String libId) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_LAST_RESULT_PREFIX + libId, null);
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
            // 任务被系统或厂商清理：重新排定（走 scheduleInternal，不回调查询，无循环风险）
            scheduleInternal(ctx, libId);
            return;
        }
        // 只采纳比“上次执行时间”更晚的调度值：避免补切成功后，被 WorkManager 里那个过期的
        // 周期时间覆盖回去，导致小组件一直显示“待切换”甚至重复补切
        if (trigger > 0 && trigger > lastRun(ctx, libId)) {
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