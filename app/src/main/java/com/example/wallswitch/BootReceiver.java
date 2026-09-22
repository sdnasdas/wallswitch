package com.example.wallswitch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 开机 / 覆盖安装后：重排定时并补上关机期间漏掉的那一轮。
 * （WorkManager 自身也会恢复周期任务，这里额外做「补切」，因为定时到点却在关机/冻结期间没人执行）
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        final Context app = context.getApplicationContext();
        // 先重排定时（个别 ROM 会清掉调度器任务）
        TimerScheduler.scheduleAll(app);
        final PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                TimerScheduler.catchUp(app);
            } catch (Exception ignored) {
            } finally {
                pending.finish();
            }
        }, "boot-catchup").start();
    }
}