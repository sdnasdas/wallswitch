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

        public PeriodicWorkRequest build() {
            return new PeriodicWorkRequest();
        }
    }
}