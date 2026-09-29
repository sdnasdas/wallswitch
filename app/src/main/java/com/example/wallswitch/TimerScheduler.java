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
 *
 * v3.60 起调度单位从「库」改成「范围槽位」：桌面、锁屏各一条周期任务（任务名 switch_h / switch_l），
 * 间隔取该槽位自己的设置（LibraryStore.scopeIntervalSeconds），槽位没库就取消该任务。
 *
 * 倒计时数据来源：WorkManager 公开 API {@link WorkInfo#getNextScheduleTimeMillis()}——注意它只表示
 * 「最早具备运行条件的时刻」（受系统调度/Doze 影响，真实执行几乎不会恰好在这一刻，也可能已是过去时间），
 * 因此小组件显示的是「预计」倒计时，并在等待系统调度时改显示“待切换”。
 */
public class TimerScheduler {

    // WorkManager 任务名前缀 + 范围后缀（h/l），每个范围至多一条任务
    private static final String WORK_PREFIX = "switch_";
    // SharedPreferences 文件名
    private static final String PREFS_NAME = "settings";
    // 下次触发时间（wall clock 毫秒）的 key 前缀，供小组件倒计时显示（按范围记录，+h/l 后缀）
    private static final String KEY_NEXT_TRIGGER_PREFIX = "next_trigger_";
    // 每范围「上次自动切换时间」（毫秒）与「上次结果」的 key 前缀
    private static final String KEY_LAST_RUN_PREFIX = "last_run_";
    private static final String KEY_LAST_RESULT_PREFIX = "last_result_";
    // 每范围「上次排定时的指纹」（库id|间隔秒），决定重排该用 KEEP 还是 UPDATE
    private static final String KEY_SPEC_PREFIX = "slot_spec_";
    // 上次执行结果：成功
    public static final String RESULT_OK = "ok";
    // 查询 WorkManager 任务状态的单线程执行器（ListenableFuture 回调，避免占用主线程）
    private static final Executor EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "timer-sync");
        thread.setDaemon(true);
        return thread;
    });

    /** 范围的记录/任务名后缀（与 Switcher 进度键的 h/l 约定一致）。 */
    private static String suffix(boolean forHome) {
        return forHome ? "_h" : "_l";
    }

    /**
     * 定时切换是否生效。v3.14 起恒为 true：不再有全局总开关，
     * 行为 = 槽位有库 ⇒ 到点自动切；槽位清空即停。
     */
    public static boolean isTimerEnabled(Context ctx) {
        return true;
    }

    /** 触发时刻的时钟文案（如 15:42），跟随系统的 12/24 小时制设置。 */
    public static String clockText(Context ctx, long millis) {
        return android.text.format.DateFormat.getTimeFormat(ctx).format(new java.util.Date(millis));
    }

    // 「走秒倒计时」开关：界面、常驻通知、小组件三处都读这一个存取器，保证显示与实际一致
    // （历史坑：开关显示关、功能却在跑，多因各处默认值/来源不一致）。默认关：
    // 静态时间已经够用，而走秒会每秒唤醒一次 SystemUI/桌面重绘。
    private static final String KEY_TICKING = "ticking_countdown";

    /** 是否使用走秒倒计时（false = 静态「预计下次切换时间」）。 */
    public static boolean tickingCountdown(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_TICKING, false);
    }

    /** 设置倒计时样式：true = 走秒，false = 静态。 */
    public static void setTickingCountdown(Context ctx, boolean ticking) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_TICKING, ticking).apply();
    }

    /** 该范围的定时间隔（秒）：取系统下限（15 分钟）与槽位设置的较大值。 */
    private static int intervalSeconds(Context ctx, boolean forHome) {
        return Math.max(LibraryStore.MIN_INTERVAL_SECONDS,
                LibraryStore.scopeIntervalSeconds(ctx, forHome));
    }

    /** 为指定范围安排（或取消）定时切换，并用 WorkManager 的真实调度时间校准倒计时。 */
    public static void scheduleScope(Context ctx, boolean forHome) {
        scheduleInternal(ctx, forHome);
        syncFromWorkManager(ctx);
    }

    /** 只负责把该范围的周期任务交给 WorkManager（槽位没库则取消任务；不再写估算值）。
     *  排定策略按「库id|间隔」指纹决定：指纹没变用 KEEP —— 不碰系统里那张格子的起算时刻；
     *  指纹变了才 UPDATE。此前每次回到应用都 UPDATE，而 UPDATE 等于「从现在重新起算」，
     *  于是每打开一次 App，自动切换就被推后一整轮（间隔内开得越勤越不来）。 */
    private static void scheduleInternal(Context ctx, boolean forHome) {
        scheduleInternal(ctx, forHome, false);
    }

    /** force = true 时无视指纹强制 UPDATE（手动切换后要重新起算周期就走这条）。 */
    private static void scheduleInternal(Context ctx, boolean forHome, boolean force) {
        String libId = LibraryStore.slotLibId(ctx, forHome);
        if (libId == null || libId.isEmpty()) {
            cancelScope(ctx, forHome);
            return;
        }
        int seconds = intervalSeconds(ctx, forHome);
        String spec = libId + "|" + seconds;
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        ExistingPeriodicWorkPolicy policy =
                !force && spec.equals(prefs.getString(KEY_SPEC_PREFIX + suffix(forHome), null))
                        ? ExistingPeriodicWorkPolicy.KEEP : ExistingPeriodicWorkPolicy.UPDATE;
        try {
            Data data = new Data.Builder()
                    .putBoolean(SwitchWorker.EXTRA_FOR_HOME, forHome).build();
            PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                    SwitchWorker.class, seconds, TimeUnit.SECONDS)
                    .setInputData(data)
                    .build();
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                    WORK_PREFIX + suffix(forHome), policy, request);
            // KEEP 那条分支在任务已被系统清理时等于重新排一个，两种情况下这份指纹都该记下
            prefs.edit().putString(KEY_SPEC_PREFIX + suffix(forHome), spec).apply();
        } catch (Exception ignored) {
        }
    }

    /**
     * 手动上屏一张之后调用（卡片双击、通知的上一张/下一张、小组件点按、长按设为主页、
     * 删掉正在屏上的那张）：该范围的下一轮从此刻重新起算，免得刚手动切完紧接着又被定时切一张。
     * 只写这本账 + 强制重排周期，不做 WorkManager 状态查询（刚算出来的值查回来还是它，白跑一次异步往返）。
     */
    public static void restartScope(Context ctx, boolean forHome) {
        String libId = LibraryStore.slotLibId(ctx, forHome);
        if (libId == null || libId.isEmpty()) {
            // 这个范围本来就没排定，没什么可重排的
            return;
        }
        long now = System.currentTimeMillis();
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putLong(KEY_LAST_RUN_PREFIX + suffix(forHome), now)
                .putLong(KEY_NEXT_TRIGGER_PREFIX + suffix(forHome),
                        now + intervalSeconds(ctx, forHome) * 1000L)
                .apply();
        scheduleInternal(ctx, forHome, true);
        WidgetProvider.updateWidget(ctx);
        StatusNotifier.update(ctx);
    }

    /** 取消指定范围的定时任务，并清除该范围的倒计时记录。 */
    public static void cancelScope(Context ctx, boolean forHome) {
        try {
            WorkManager.getInstance(ctx).cancelUniqueWork(WORK_PREFIX + suffix(forHome));
        } catch (Exception ignored) {
        }
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .remove(KEY_NEXT_TRIGGER_PREFIX + suffix(forHome))
                // 指纹一起清掉：以后重新占上同一个库要当作"变了"，强制重新起算
                .remove(KEY_SPEC_PREFIX + suffix(forHome))
                .apply();
        WidgetProvider.updateWidget(ctx);
        StatusNotifier.update(ctx);
    }

    /** 取消某个库 id 名下的旧版周期任务（按库排任务时代遗留的 switch_&lt;libId&gt;）。 */
    public static void cancelLegacyLibWork(Context ctx, String libId) {
        try {
            WorkManager.getInstance(ctx).cancelUniqueWork(WORK_PREFIX + libId);
        } catch (Exception ignored) {
        }
    }

    /** 按当前槽位设置重排两个范围的定时，并用 WorkManager 的真实调度时间校准倒计时（回到应用时自愈调用）。 */
    public static void scheduleAll(Context ctx) {
        if (!isTimerEnabled(ctx)) {
            return;
        }
        for (boolean forHome : new boolean[]{true, false}) {
            // 只排定，最后统一查一次，避免两个任务各触发一轮查询
            scheduleInternal(ctx, forHome);
        }
        syncFromWorkManager(ctx);
    }

    /** 取消两个范围的定时任务（清空所有槽位时用）。 */
    public static void cancelAll(Context ctx) {
        for (boolean forHome : new boolean[]{true, false}) {
            cancelScope(ctx, forHome);
        }
    }

    /**
     * 到点后由 Worker/补切调用：执行该范围的一次切换并记账
     * （下次触发时间、结果、小组件刷新）。返回是否成功上屏。
     */
    public static boolean runNow(Context ctx, boolean forHome) {
        String libId = LibraryStore.slotLibId(ctx, forHome);
        if (libId == null || libId.isEmpty()) {
            return false;
        }
        LibraryStore.Library lib = LibraryStore.get(ctx, libId);
        if (lib == null) {
            return false;
        }
        // 「开启接管」关着 = 本 App 不接管系统壁纸：定时任务完全不动系统（不发通知、不记日志），
        // 但仍按间隔推进记账，免得每个周期都白跑一次、状态行还停在上次的旧结果
        if (!TakeoverManager.isEnabled(ctx)) {
            long idle = System.currentTimeMillis();
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                    .putLong(KEY_LAST_RUN_PREFIX + suffix(forHome), idle)
                    .putString(KEY_LAST_RESULT_PREFIX + suffix(forHome), "takeover_off")
                    .putLong(KEY_NEXT_TRIGGER_PREFIX + suffix(forHome),
                            idle + intervalSeconds(ctx, forHome) * 1000L)
                    .apply();
            return false;
        }
        // 桌面：引擎未激活时不做任何事 —— 桌面静态兜底已按需求删除。
        // 关键：这不算「失败」，否则每个定时周期都会弹一条失败横幅，纯噪音。
        boolean nothingToDo = forHome && !WallSwitchService.isActive(ctx);
        boolean ok = !nothingToDo && Switcher.next(ctx, lib.id, forHome);
        long now = System.currentTimeMillis();
        String result = nothingToDo ? "engine_inactive" : (ok ? RESULT_OK : Switcher.lastError());
        // 自动切换提示：用户通常不在场，统一发系统通知（成功静音留痕、失败弹横幅），
        // 手动切换/小组件点击仍由界面侧用 Toast 即时反馈，不走这里
        if (!nothingToDo) {
            SwitchNotifier.notifyResult(ctx, forHome, lib, ok, result);
            // 日志只记「定时自动切换」成功的那次（手动切换与小组件点按不记）
            if (ok) {
                SwitchLog.recordAuto(ctx, forHome, switchDetail(ctx, lib.id, forHome));
            }
        }
        // 无论成败都把下次触发时间前移到 当前+间隔：成功即进入下一轮，失败也避免每次刷新都重试
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putLong(KEY_LAST_RUN_PREFIX + suffix(forHome), now)
                .putString(KEY_LAST_RESULT_PREFIX + suffix(forHome), result == null ? "failed" : result)
                .putLong(KEY_NEXT_TRIGGER_PREFIX + suffix(forHome),
                        now + intervalSeconds(ctx, forHome) * 1000L)
                .apply();
        WidgetProvider.updateWidget(ctx);
        StatusNotifier.update(ctx);
        // 与 WorkManager 的真实调度时间对齐（它的值更旧且本轮已执行时不会被采纳）
        syncFromWorkManager(ctx);
        return ok;
    }

    /**
     * 日志第一行里那段「切到哪张」的可读描述：取该范围切换后的当前壁纸标题。
     * 找不到标题时用「未命名」占位。
     */
    private static String switchDetail(Context ctx, String libId, boolean forHome) {
        return ctx.getString(R.string.log_detail_scope,
                ctx.getString(forHome ? R.string.scope_home : R.string.scope_lock),
                currentTitle(ctx, libId, forHome));
    }

    /** 某个范围此刻在屏上的壁纸标题（指针没设或标题为空时给占位文案）。 */
    private static String currentTitle(Context ctx, String libId, boolean forHome) {
        String id = Switcher.getCurrent(ctx, libId, forHome);
        String title = id == null ? null : WallpaperStore.getTitle(ctx, id);
        return title == null || title.isEmpty() ? ctx.getString(R.string.untitled) : title;
    }

    /**
     * 该范围本轮是否“已到点且尚未执行”——Worker 与补切共用，避免两边重复切换。
     * 从未排定过（无记录）时，仅在从未执行过的情况下视为到点（对应周期任务的首次立即执行）。
     */
    public static boolean isDue(Context ctx, boolean forHome) {
        long due = recordedTrigger(ctx, forHome);
        long last = lastRun(ctx, forHome);
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
        for (boolean forHome : new boolean[]{true, false}) {
            if (LibraryStore.slotLibId(ctx, forHome) != null && isDue(ctx, forHome)) {
                did |= runNow(ctx, forHome);
            }
        }
        return did;
    }

    /** 上次执行该范围自动切换（含补切）的时间，无记录返回 0。 */
    public static long lastRun(Context ctx, boolean forHome) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getLong(KEY_LAST_RUN_PREFIX + suffix(forHome), 0L);
    }

    /** 上次执行结果："ok"、失败原因（异常/解码失败），从未执行返回 null。 */
    public static String lastResult(Context ctx, boolean forHome) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_LAST_RESULT_PREFIX + suffix(forHome), null);
    }

    /**
     * 向 WorkManager 查询两个范围周期任务的状态与「最早可运行时间」，写入本地记录并刷新小组件。
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
        for (boolean forHome : new boolean[]{true, false}) {
            if (LibraryStore.slotLibId(app, forHome) == null) {
                continue;
            }
            try {
                ListenableFuture<List<WorkInfo>> future =
                        workManager.getWorkInfosForUniqueWork(WORK_PREFIX + suffix(forHome));
                future.addListener(() -> onQueried(app, forHome, future), EXECUTOR);
            } catch (Exception ignored) {
            }
        }
    }

    /** WorkManager 查询回调（后台线程）：ENQUEUED/RUNNING 时取其最早可运行时间，否则重新排定。 */
    private static void onQueried(Context ctx, boolean forHome, ListenableFuture<List<WorkInfo>> future) {
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
            scheduleInternal(ctx, forHome);
            return;
        }
        // 只采纳比“上次执行时间”更晚的调度值：避免补切成功后，被 WorkManager 里那个过期的
        // 周期时间覆盖回去，导致小组件一直显示“待切换”甚至重复补切
        if (trigger > 0 && trigger > lastRun(ctx, forHome)) {
            applyTrigger(ctx, forHome, trigger);
        }
    }

    /** 写入某范围的下次触发时间并刷新小组件（值未变化时不动，避免无意义的刷新）。 */
    private static void applyTrigger(Context ctx, boolean forHome, long triggerMillis) {
        if (recordedTrigger(ctx, forHome) == triggerMillis) {
            return;
        }
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putLong(KEY_NEXT_TRIGGER_PREFIX + suffix(forHome), triggerMillis)
                .apply();
        WidgetProvider.updateWidget(ctx);
        StatusNotifier.update(ctx);
    }

    /** 已记录的某范围下次触发时间（毫秒），无记录返回 -1。 */
    private static long recordedTrigger(Context ctx, boolean forHome) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getLong(KEY_NEXT_TRIGGER_PREFIX + suffix(forHome), -1L);
    }

    /** 常驻通知用：某范围已记录的下次触发时间（毫秒），无记录返回 -1。 */
    public static long scopeTrigger(Context ctx, boolean forHome) {
        return recordedTrigger(ctx, forHome);
    }

    /** 小组件用：两个范围中最近的下次触发时间（毫秒），无则 null。 */
    public static Long nextTrigger(Context ctx) {
        if (!isTimerEnabled(ctx)) {
            return null;
        }
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        Long best = null;
        for (boolean forHome : new boolean[]{true, false}) {
            long t = prefs.getLong(KEY_NEXT_TRIGGER_PREFIX + suffix(forHome), -1L);
            if (t >= 0 && (best == null || t < best)) {
                best = t;
            }
        }
        return best;
    }
}
