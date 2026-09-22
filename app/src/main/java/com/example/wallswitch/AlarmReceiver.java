package com.example.wallswitch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 定时触发接收器：闹钟到点同时推进桌面与锁屏，并重排下一次定时。
 * Manifest 中 exported=false、无 intent-filter，靠 PendingIntent 显式 Intent 触发。
 */
public class AlarmReceiver extends BroadcastReceiver {

    // 闹钟触发 action
    public static final String ACTION_ALARM = "com.example.wallswitch.ALARM";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION_ALARM.equals(intent.getAction())) {
            return;
        }
        // 桌面与锁屏各自独立推进（受 enabled 与勾选范围控制）
        Switcher.next(context, true);
        Switcher.next(context, false);
        // 一次一排：触发后重新安排下一次
        AlarmScheduler.schedule(context);
    }
}
