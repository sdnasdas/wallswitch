package androidx.work;

import android.content.Context;

/** 本地类型检查桩：真实类来自 androidx.work 库（Worker 继承 ListenableWorker）。 */
public abstract class Worker extends ListenableWorker {

    public Worker(Context context, WorkerParameters params) {
        super(context, params);
    }
}