package io.github.sdnasdas.wallswitch;

import android.content.Context;

import androidx.work.Worker;
import androidx.work.WorkerParameters;

/**
 * 时段换库 Worker：时段边界到点由 WorkManager 唤起（后台线程）。
 * 只做两件事：把「此刻所属时段的目标库」应用到该范围（幂等，晚醒跨段也收敛正确），
 * 再排下一条边界 —— 自续链，见 {@link LibScheduler}。
 */
public class LibScheduleWorker extends Worker {

    // 输入数据：要换库的范围（true=桌面，false=锁屏）
    public static final String EXTRA_FOR_HOME = "for_home";

    public LibScheduleWorker(Context context, WorkerParameters params) {
        super(context, params);
    }

    @Override
    public Result doWork() {
        if (!getInputData().getKeyValueMap().containsKey(EXTRA_FOR_HOME)) {
            return Result.success();
        }
        boolean forHome = getInputData().getBoolean(EXTRA_FOR_HOME, true);
        LibScheduler.onBoundaryFired(getApplicationContext(), forHome);
        return Result.success();
    }
}
