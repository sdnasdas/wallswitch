package androidx.work;

import java.util.concurrent.TimeUnit;

/** 本地类型检查桩：真实类来自 androidx.work 库。 */
public class PeriodicWorkRequest extends WorkRequest {

    public static class Builder {

        public Builder(Class<? extends Worker> workerClass, long repeatInterval,
                       TimeUnit repeatIntervalTimeUnit) {
        }

        public Builder setInputData(Data data) {
            return this;
        }

        /** 桩：真实类里声明在 WorkRequest.Builder 上，返回 B（这里就是 Builder）。 */
        public Builder setInitialDelay(long delay, TimeUnit timeUnit) {
            return this;
        }

        public PeriodicWorkRequest build() {
            return new PeriodicWorkRequest();
        }
    }
}