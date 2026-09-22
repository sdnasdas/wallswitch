package com.example.wallswitch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;

/**
 * 定时触发接收器：闹钟到点同时推进桌面与锁屏，并重排下一次定时。
 * Manifest 中 exported=false、无 intent-filter，靠 PendingIntent 显式 Intent 触发。
 */
public class AlarmReceiver extends BroadcastReceiver {

    // 闹钟触发 action
    public static final String ACTION_ALARM = "com.example.wallswitch.ALARM";
    // 短间隔阈值（秒）：秒级测试间隔在息屏时不切换，避免口袋里持续解码+设置壁纸造成发热耗电
    private static final int SHORT_INTERVAL_SECONDS = 60;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION_ALARM.equals(intent.getAction())) {
            return;
        }
        String libId = intent.getStringExtra(AlarmScheduler.EXTRA_LIB_ID);
        if (libId == null) {
            return;
        }
        // 秒级间隔仅在亮屏时切换（测试用）；常规长间隔不受影响
        LibraryStore.Library lib = LibraryStore.get(context, libId);
        boolean shortInterval = lib != null && lib.intervalSeconds < SHORT_INTERVAL_SECONDS;
        if (!shortInterval || isScreenOn(context)) {
            // 切换该库覆盖的范围（桌面/锁屏，Switcher 内部会校验库启用状态与范围勾选）
            Switcher.next(context, libId, true);
            Switcher.next(context, libId, false);
        }
        // 一次一排：触发后重新安排该库的下一次定时
        AlarmScheduler.schedule(context, libId);
    }

    /** 屏幕是否处于亮屏/交互状态。 */
    private boolean isScreenOn(Context ctx) {
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isInteractive();
    }
}
