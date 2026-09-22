package androidx.work;

import android.content.Context;

/** 本地类型检查桩：真实类来自 androidx.work 库。 */
public abstract class WorkManager {

    public static WorkManager getInstance(Context context) {
        return null;
    }

    public abstract void enqueueUniquePeriodicWork(String uniqueWorkName,
            ExistingPeriodicWorkPolicy existingPeriodicWorkPolicy, PeriodicWorkRequest periodicWork);

    public abstract void cancelUniqueWork(String uniqueWorkName);
}