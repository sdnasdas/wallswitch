package io.github.sdnasdas.wallswitch;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 按时段自动换库的调度（v3.191）：每个范围一条一次性任务（libsched_h / libsched_l），
 * initialDelay = 距下一个时段边界的毫秒数；醒来换库后再排下一条 —— 自续链，等于「响完再定下一个闹钟」。
 * 走 WorkManager 而不是 AlarmManager（项目在 5599033 已整体废弃精确闹钟）：
 * 批量合并、Doze 中自动推迟、重启 / 覆盖安装自动恢复；深睡期间晚到的边界由
 * 「设备活跃时机」的 {@link #catchUp} 补换（开应用 / 刷小组件 / 亮屏回桌面），
 * 与定时切换「宁可晚，绝不早」的口径一致 —— 换库发生在息屏里用户看不见，亮屏瞬间补上即可。
 *
 * <p>到点动作是幂等的：记录「本时段开始边界已执行」，只有新时段才再换一次。
 * 深睡期间晚到的边界会在设备活跃时补上；执行后用户手动换库不会被补切覆盖，
 * 到下个时段边界才恢复自动换库。换库走
 * {@link LibraryStore#setSlotLib}（自带「换库」日志行与库内定时重排），桌面由引擎标脏重绘，
 * 锁屏走 {@link TakeoverManager#applyLockOnly} 跟随。
 */
public class LibScheduler {

    // WorkManager 任务名前缀 + 范围后缀（h/l），每个范围至多一条一次性任务
    private static final String WORK_PREFIX = "libsched_";
    // SharedPreferences 文件名（与 TimerScheduler/LibraryStore 同一份 settings）
    private static final String PREFS_NAME = "settings";
    // 已排定边界时刻（墙钟毫秒）的指纹：相同就不再 enqueue（REPLACE 无谓重排）；
    // 任务被 ROM 清掉时边界已过，下次 reschedule/catchUp 算出不同的边界会重新排上（自愈）
    private static final String KEY_NEXT_PREFIX = "libsched_next_";
    // 当前时段已执行到的开始边界：补切只认「新时段」，不再持续强制目标库。
    private static final String KEY_APPLIED_PREFIX = "libsched_applied_";

    private static String suffix(boolean forHome) {
        return forHome ? "_h" : "_l";
    }

    /** 手动换库或自动执行后，把当前时段标记为已处理；之后补切不再覆盖用户选择。 */
    static synchronized void markCurrentApplied(Context ctx, boolean forHome) {
        long start = LibScheduleStore.currentStartMillis(ctx, forHome, System.currentTimeMillis());
        if (start < 0) {
            return;
        }
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String key = KEY_APPLIED_PREFIX + suffix(forHome);
        if (start > prefs.getLong(key, -1L)) {
            prefs.edit().putLong(key, start).apply();
        }
    }

    /** 编辑时段配置后，当前时段重新允许执行一次。 */
    static synchronized void clearApplied(Context ctx, boolean forHome) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .remove(KEY_APPLIED_PREFIX + suffix(forHome)).apply();
    }

    /**
     * 按当前时段配置排定（或撤掉）该范围的下一次边界任务。主线程调用
     * （WorkManager 的排与撤是进程内 IPC，跟其它设置写点一样放主线程，见 TimerScheduler 同款注释）。
     * 闸门：槽位没库 / 暂停中 / 没有配置 → 撤任务（暂停期间零唤醒，与定时档同一口径）。
     */
    public static void reschedule(Context ctx, boolean forHome) {
        List<LibScheduleStore.Rule> rules = LibScheduleStore.load(ctx, forHome);
        String libId = LibraryStore.slotLibId(ctx, forHome);
        if (rules.isEmpty() || libId == null || libId.isEmpty()
                || LibraryStore.slotPaused(ctx, forHome)) {
            cancel(ctx, forHome);
            return;
        }
        long now = System.currentTimeMillis();
        long next = LibScheduleStore.nextBoundaryMillis(ctx, forHome, now);
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (next < 0) {
            cancel(ctx, forHome);
            return;
        }
        if (prefs.getLong(KEY_NEXT_PREFIX + suffix(forHome), -1L) == next) {
            return;    // 这条边界已经排着，不重排
        }
        try {
            Data data = new Data.Builder()
                    .putBoolean(LibScheduleWorker.EXTRA_FOR_HOME, forHome).build();
            WorkManager.getInstance(ctx).enqueueUniqueWork(WORK_PREFIX + suffix(forHome),
                    ExistingWorkPolicy.REPLACE,
                    new OneTimeWorkRequest.Builder(LibScheduleWorker.class)
                            .setInitialDelay(Math.max(1L, next - now), TimeUnit.MILLISECONDS)
                            .setInputData(data)
                            .build());
            prefs.edit().putLong(KEY_NEXT_PREFIX + suffix(forHome), next).apply();
        } catch (Exception ignored) {
        }
    }

    /** 撤掉该范围的边界任务并清指纹（不清时段配置本身，配置由 UI / 清槽位管理）。 */
    public static void cancel(Context ctx, boolean forHome) {
        try {
            WorkManager.getInstance(ctx).cancelUniqueWork(WORK_PREFIX + suffix(forHome));
        } catch (Exception ignored) {
        }
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .remove(KEY_NEXT_PREFIX + suffix(forHome))
                .apply();
    }

    /** 两面各排一次（指纹相同则不动）：开机 / 回到应用时的自愈入口。主线程调用。 */
    public static void rescheduleAll(Context ctx) {
        for (boolean forHome : new boolean[]{true, false}) {
            reschedule(ctx, forHome);
        }
    }

    /** Worker 到点入口：先把「此刻所属时段的目标库」应用上去，再排下一条边界。后台线程调用。 */
    public static void onBoundaryFired(Context ctx, boolean forHome) {
        applySchedule(ctx, forHome);
        reschedule(ctx, forHome);
    }

    /**
     * 设备活跃时机的补换：只处理「当前时段尚未执行过」的新边界。
     * 已执行过的时段不再比较当前库，因此手动换库不会被覆盖。后台线程调用。
     */
    public static boolean catchUp(Context ctx) {
        boolean did = false;
        for (boolean forHome : new boolean[]{true, false}) {
            did |= applySchedule(ctx, forHome);
        }
        return did;
    }

    /**
     * 把「此刻所属时段的目标库」应用到这一面：每个时段只执行一次。
     * 目标时段不存在 / 槽位没库 / 暂停中 / 本时段已执行 都不动。后台线程调用。
     */
    private static boolean applySchedule(Context ctx, boolean forHome) {
        long now = System.currentTimeMillis();
        String target = LibScheduleStore.currentLibId(ctx, forHome,
                LibScheduleStore.minutesOfDay(now));
        long segmentStart = LibScheduleStore.currentStartMillis(ctx, forHome, now);
        if (target == null || segmentStart < 0) {
            return false;
        }
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String appliedKey = KEY_APPLIED_PREFIX + suffix(forHome);
        if (prefs.getLong(appliedKey, -1L) >= segmentStart) {
            return false;
        }
        synchronized (LibScheduler.class) {
            // Worker 与引擎可见回调可能同时醒：进闸后重新核对边界与执行标记。
            target = LibScheduleStore.currentLibId(ctx, forHome,
                    LibScheduleStore.minutesOfDay(now));
            segmentStart = LibScheduleStore.currentStartMillis(ctx, forHome, now);
            if (target == null || segmentStart < 0
                    || prefs.getLong(appliedKey, -1L) >= segmentStart) {
                return false;
            }
            String live = LibraryStore.slotLibId(ctx, forHome);
            if (live == null || live.isEmpty() || LibraryStore.slotPaused(ctx, forHome)) {
                return false;
            }
            boolean changed = !live.equals(target);
            if (changed) {
                // 自带「换库」日志行（SwitchLog.recordSwitchLib）与库内定时从此刻重新起算
                LibraryStore.setSlotLib(ctx, forHome, target);
            }
            // 即使当前已经是目标库，也要记下本段已执行；否则下一次库不一致时又会被补切纠正。
            markCurrentApplied(ctx, forHome);
            if (changed) {
                if (forHome) {
                    // 桌面由引擎画：标脏让新库那张立刻上屏（与 applySlotLib 同款，引擎没激活就是空响）
                    WallSwitchService.notifyWallpaperChanged();
                }
                // 锁屏静态档跟随新库（只碰锁屏：apply 会被「桌面引擎未激活」挡住，见其注释）
                TakeoverManager.applyLockOnly(ctx);
                WidgetProvider.updateWidget(ctx);
                StatusNotifier.update(ctx);
            }
            return changed;
        }
    }
}
