package com.example.wallswitch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 开机自启接收器：开机后按持久化设置恢复定时切换任务。
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        // 开机、以及每次覆盖安装新版本（系统会清空该应用的闹钟）后，按原设置重排所有启用库的定时
        AlarmScheduler.scheduleAll(context);
    }
}
