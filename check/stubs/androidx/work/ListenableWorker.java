package androidx.work;

import android.content.Context;

/** 本地类型检查桩：真实类来自 androidx.work 库（Result 是其内部类）。 */
public abstract class ListenableWorker {

    public ListenableWorker(Context context, WorkerParameters params) {
    }

    public abstract Result doWork();

    public Context getApplicationContext() {
        return null;
    }

    public Data getInputData() {
        return null;
    }

    /** 真实 API 中 Result 是 ListenableWorker 的静态内部类。 */
    public static class Result {

        public static Result success() {
            return new Result();
        }

        public static Result failure() {
            return new Result();
        }

        public static Result retry() {
            return new Result();
        }
    }
}