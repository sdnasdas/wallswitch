package com.example.wallswitch;

import android.content.Context;

import androidx.work.Data;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/**
 * 壁纸切换 Worker：由 WorkManager 按周期（≥15 分钟）在后台线程执行。
 * 相比 AlarmManager：系统批量合并执行、Doze 中自动推迟、应用更新/重启后自动恢复，省电且可靠。
 */
public class SwitchWorker extends Worker {

    // 输入数据：要切换的范围（true=桌面，false=锁屏）；v3.60 起调度单位从库改成范围槽位
    public static final String EXTRA_FOR_HOME = "for_home";

    public SwitchWorker(Context context, WorkerParameters params) {
        super(context, params);
    }

    @Override
    public Result doWork() {
        // 旧版按库排定的任务可能还在系统排队：输入里没有范围键就什么都不做（迁移时会取消它）。
        // 注意：WorkManager 2.9.1 的 Data 没有公开的 hasKey()，只能查 getKeyValueMap()
        if (!getInputData().getKeyValueMap().containsKey(EXTRA_FOR_HOME)) {
            return Result.success();
        }
        boolean forHome = getInputData().getBoolean(EXTRA_FOR_HOME, true);
        // 闹钟归 WorkManager：它醒 = 该切一张了。这里只挡「刚刚已经切过」（补切撞车、重排后立刻醒），
        // 不再判到点没到点 —— 挡掉这一格只会让下一次白等将近一整轮
        if (TimerScheduler.justSwitched(getApplicationContext(), forHome)) {
            return Result.success();
        }
        // 执行该范围槽位库的切换并记账（前移下次触发时间、记录结果、刷新小组件倒计时）
        TimerScheduler.runNow(getApplicationContext(), forHome);
        return Result.success();
    }
}