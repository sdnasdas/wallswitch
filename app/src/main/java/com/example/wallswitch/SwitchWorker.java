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

    // 输入数据：要切换的壁纸库 id
    public static final String EXTRA_LIB_ID = "lib_id";

    public SwitchWorker(Context context, WorkerParameters params) {
        super(context, params);
    }

    @Override
    public Result doWork() {
        String libId = getInputData().getString(EXTRA_LIB_ID);
        if (libId == null) {
            return Result.success();
        }
        // 切换该库覆盖的范围（Switcher 内部会校验库启用状态与范围勾选）
        Switcher.next(getApplicationContext(), libId, true);
        Switcher.next(getApplicationContext(), libId, false);
        // 刷新小组件倒计时（WorkManager 的下次触发时间约为当前+间隔）
        TimerScheduler.noteTrigger(getApplicationContext(), libId);
        return Result.success();
    }
}