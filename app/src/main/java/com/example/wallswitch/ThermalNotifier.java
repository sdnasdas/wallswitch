package com.example.wallswitch;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.util.Locale;

/**
 * 温度这件事的两处露出：
 * <ol>
 *   <li>常驻通知「一键设置」开关旁那行电池温度 —— 这里只负责「整度变了才叫 {@link StatusNotifier} 重发」，
 *       真正读数与上色在 {@link StatusNotifier#bindTemperature}。整度而不是 0.1℃：电池广播很密，
 *       每跳 0.1℃ 就重发一次通知等于持续唤醒 SystemUI，正是走秒倒计时当年发热的那条老路。</li>
 *   <li>温度闸门（{@link WallSwitchService#isMotionGated}）合上 / 放开时各发一条提醒：
 *       合上 = 电池到 41℃（或系统温控严重档兜底），循环档的桌面实况停在封面不再续播；
 *       放开 = 电池回落到 39℃ 以下，引擎当场接上。</li>
 * </ol>
 *
 * <p>闸门以电池温度为主判据、2℃ 迟滞（41 停 / 39 恢复），所以这里看到的「暂停 / 恢复」与通知上
 * 那行数字是同一个信号；系统温控档位只在严重档兜底，不会出现「36℃ 就停」那种错位。
 *
 * <p>只在「循环播放」档提醒：按住播放那档闸门不拦任何东西，报了反而误导。
 * 合上那条发过才发放开那条（持久化一个标记位），所以不会凭空冒出一条「已解除」。
 * 两条共用一个通知 id：放开那条直接顶掉合上那条 —— 限制已经不在了，留着旧的那张只会让人以为还在限。
 *
 * <p>调用方都是主线程（热档位监听、电池广播），这里只读写一个 SharedPreferences 标记位与发一条通知，
 * 都是轻活。
 */
final class ThermalNotifier {

    private static final String CHANNEL = "thermal_notice";
    /** 固定 id：避开常驻通知 1500/1501 与切换留痕 2000 起的自增段。 */
    private static final int NOTIFY_ID = 1600;
    private static final String PREFS_NAME = "settings";
    /** 「合上」那条已经发过、还没等到「放开」。 */
    private static final String KEY_NOTICED = "thermal_gate_noticed";

    /** 常驻通知上一次画的是几度；整度变了才重发。进程内缓存，进程重启后第一次广播必然刷一次。 */
    private static int lastShownDegrees = Integer.MIN_VALUE;

    private ThermalNotifier() {
    }

    /** 0.1℃ 单位 → 显示用的整度（四舍五入）。常驻通知与「变没变」的判据必须用同一个换算。 */
    static int degrees(int tenths) {
        return Math.round(tenths / 10f);
    }

    /** 电池广播（ACTION_BATTERY_CHANGED）来了一次。 */
    static void onBatteryTemp(Context ctx, int tenths) {
        int shown = tenths < 0 ? Integer.MIN_VALUE : degrees(tenths);
        if (shown != lastShownDegrees) {
            lastShownDegrees = shown;
            StatusNotifier.update(ctx);
        }
        // API 26~28 的闸门就是电池温度；29+ 这一步只是兜底复核，档位变化本身由热监听报
        onGateInputChanged(ctx);
    }

    /** 闸门的输入（热档位或电池温度）可能变了：看是不是刚合上 / 刚放开。 */
    static void onGateInputChanged(Context ctx) {
        boolean gated = WallSwitchService.isMotionGated(ctx);
        SharedPreferences sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean noticed = sp.getBoolean(KEY_NOTICED, false);
        if (gated && !noticed && loopMode(ctx)) {
            sp.edit().putBoolean(KEY_NOTICED, true).apply();
            post(ctx, true);
            StatusNotifier.update(ctx);
        } else if (!gated && noticed) {
            sp.edit().putBoolean(KEY_NOTICED, false).apply();
            post(ctx, false);
            // 温度已经回落：别让桌面实况干等闸门那 30 秒一次的重查，当场接上
            WallSwitchService.notifyThermalGateLifted();
            StatusNotifier.update(ctx);
        }
    }

    /** 常驻通知那行温度要不要标成「实况暂停」：循环档 + 闸门合着。 */
    static boolean isRestricting(Context ctx) {
        return loopMode(ctx) && WallSwitchService.isMotionGated(ctx);
    }

    private static boolean loopMode(Context ctx) {
        return WallSwitchService.MOTION_LOOP.equals(WallSwitchService.motionMode(ctx));
    }

    /** 0.1℃ → 「41.2」；读不到给「--」。提醒正文用一位小数，常驻那行用整度。 */
    private static String tempText(int tenths) {
        return tenths < 0 ? "--" : String.format(Locale.ROOT, "%.1f", tenths / 10f);
    }

    private static void post(Context ctx, boolean restricted) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || !nm.areNotificationsEnabled()) {
            return;
        }
        try {
            NotificationChannel channel = new NotificationChannel(CHANNEL,
                    ctx.getString(R.string.thermal_channel), NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription(ctx.getString(R.string.thermal_channel_desc));
            nm.createNotificationChannel(channel);
            String temp = tempText(WallSwitchService.readBatteryTempTenths(ctx));
            String title = ctx.getString(restricted ? R.string.thermal_on_title : R.string.thermal_off_title);
            String text = ctx.getString(restricted ? R.string.thermal_on_text : R.string.thermal_off_text, temp);
            Intent open = new Intent(ctx, MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pending = PendingIntent.getActivity(ctx, 0, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            nm.notify(NOTIFY_ID, new Notification.Builder(ctx, CHANNEL)
                    .setSmallIcon(R.drawable.ic_notif_thermo)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setWhen(System.currentTimeMillis())
                    .setShowWhen(true)
                    .setAutoCancel(true)
                    .setContentIntent(pending)
                    .build());
        } catch (Exception ignored) {
        }
    }
}
