package io.github.sdnasdas.wallswitch;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/**
 * 在线壁纸库的后台下载（v3.184）：每日一次的周期任务与「立即更新」的一次性任务共用这一个 Worker，
 * 区别只在输入里的 {@link OnlineLibraries#EXTRA_FORCE}（立即更新不受每日配额限制）。
 * 只在不计流量的网络下被调度（约束见 {@link OnlineLibraries}）。
 */
public class OnlineWorker extends Worker {

    /** 一件大图在慢网下可能要好几轮才下完；接续超过这么多次就收手，等明天的周期任务。 */
    private static final int MAX_ATTEMPTS = 6;

    public OnlineWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        boolean force = getInputData().getBoolean(OnlineLibraries.EXTRA_FORCE, false);
        boolean finished = OnlineLibraries.work(getApplicationContext(), force);
        if (finished || getRunAttemptCount() >= MAX_ATTEMPTS) {
            return Result.success();
        }
        // 时间预算用完、还有下了一半的：按退避（15 分钟线性）再来一轮，断点续传接着下
        return Result.retry();
    }
}
