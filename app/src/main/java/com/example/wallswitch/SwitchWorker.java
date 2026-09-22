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
        // 只在“确实到点且本轮未执行”时切换：避免与“补切”（小组件刷新/开机/打开应用）重复切一次
        if (!TimerScheduler.isDue(getApplicationContext(), libId)) {
            return Result.success();
        }
        // 执行该库覆盖范围的切换并记账（前移下次触发时间、记录结果、刷新小组件倒计时）
        TimerScheduler.runNow(getApplicationContext(), libId);
        return Result.success();
    }
}