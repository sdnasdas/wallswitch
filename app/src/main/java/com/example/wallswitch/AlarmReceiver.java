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
        String libId = intent.getStringExtra(AlarmScheduler.EXTRA_LIB_ID);
        if (libId == null) {
            return;
        }
        // 切换该库覆盖的范围（桌面/锁屏，Switcher 内部会校验库启用状态与范围勾选）
        Switcher.next(context, libId, true);
        Switcher.next(context, libId, false);
        // 一次一排：触发后重新安排该库的下一次定时
        AlarmScheduler.schedule(context, libId);
    }
}
