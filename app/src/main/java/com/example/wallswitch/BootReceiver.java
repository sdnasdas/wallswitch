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
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        // 设置持久化在 SharedPreferences，重启后按原设置重排所有启用库的定时
        AlarmScheduler.scheduleAll(context);
    }
}
