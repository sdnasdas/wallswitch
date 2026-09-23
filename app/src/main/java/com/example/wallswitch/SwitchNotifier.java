package com.example.wallswitch;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * 自动切换结果提示：统一走系统通知（下拉通知栏可见、可保留回看）。
 *
 * 为什么不用 Toast：自动切换（定时 Worker 与各种时机的补切）用户往往不在场，
 * 而 Toast 是几秒即逝的浮层——息屏/锁屏期间没有窗口承载，错过就查不到；
 * 官方文档也建议「应用在后台运行、希望用户知晓时改用通知」。
 * 另外在华为/荣耀等机型上，关闭本应用的通知权限会连带 Toast 也弹不出来，通知更可靠。
 *
 * 打扰等级用两个渠道区分（渠道重要性创建后应用不可修改，因此一开始就分开建）：
 * - 成功：IMPORTANCE_LOW（静音、不弹横幅），只在通知栏留一条记录；
 * - 失败：IMPORTANCE_HIGH（会弹横幅提醒），避免「静默失败」。
 * 首页有「自动切换提示」开关，可整体关闭。
 *
 * 每条通知使用**独立 id**（自增序号），因此**互不覆盖、全部保留**在通知栏，便于回溯验证；
 * 同时设置 group 让它们归到一组，避免刷屏。
 *
 * 注意：手动切换与小组件点击是用户主动操作，仍由调用方用 Toast 立即反馈，不走这里。
 */
public class SwitchNotifier {

    // 通知渠道 id（成功/失败分开，便于分别设定打扰等级）
    private static final String CHANNEL_OK = "auto_switch_ok";
    private static final String CHANNEL_FAIL = "auto_switch_fail";
    // 通知分组：同组通知在通知栏折叠展示，但每条都保留
    private static final String GROUP = "auto_switch";
    // 开关存储（与定时开关共用 settings）
    private static final String PREFS_NAME = "settings";
    private static final String KEY_ENABLED = "auto_switch_notify";
    // 通知 id 自增序号（保证每条通知独立、不被覆盖）
    private static final String KEY_SEQ = "auto_switch_notify_seq";
    // 通知 id 基数（避免与其它通知 id 冲突）
    private static final int ID_BASE = 2000;

    /** 自动切换提示是否开启（默认开启）。 */
    public static boolean isEnabled(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, true);
    }

    /** 设置自动切换提示开关。 */
    public static void setEnabled(Context ctx, boolean enabled) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_ENABLED, enabled)
                .apply();
    }

    /** 系统层面通知是否可用（被关闭时静默跳过，不产生无效调用）。 */
    public static boolean canNotify(Context ctx) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        return nm != null && nm.areNotificationsEnabled();
    }

    /**
     * 自动切换完成后调用：发一条结果通知，**每条独立保留**（后一次不覆盖前一次）。
     *
     * @param lib       被切换的壁纸库（用于显示库名）
     * @param ok        是否切换成功
     * @param errorCode 失败原因码（成功时可为 null）
     */
    public static void notifyResult(Context ctx, LibraryStore.Library lib, boolean ok, String errorCode) {
        if (!isEnabled(ctx) || lib == null) {
            return;
        }
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || !nm.areNotificationsEnabled()) {
            return;
        }
        ensureChannels(ctx, nm);
        String title = ctx.getString(ok ? R.string.notify_ok_title : R.string.notify_fail_title);
        CharSequence text = ok ? successText(ctx, lib) : Switcher.errorText(ctx, errorCode);
        Intent intent = new Intent(ctx, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending = PendingIntent.getActivity(ctx, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(ctx, ok ? CHANNEL_OK : CHANNEL_FAIL)
                .setSmallIcon(R.drawable.ic_widget_switch)
                .setContentTitle(title)
                .setContentText(text)
                // 标题可能较长（壁纸标题 + 库名），用大文本样式完整展示
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setGroup(GROUP)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)
                .setAutoCancel(true)
                .setContentIntent(pending);
        try {
            nm.notify(nextNotifyId(ctx), builder.build());
        } catch (Exception ignored) {
        }
    }

    /** 成功提示正文：库「库名」｜桌面：标题｜锁屏：标题（未覆盖的范围不显示，失败的范围标「未切换」）。 */
    private static CharSequence successText(Context ctx, LibraryStore.Library lib) {
        StringBuilder sb = new StringBuilder(ctx.getString(R.string.notify_ok_text, lib.name == null ? "" : lib.name));
        if (lib.home) {
            sb.append(scopePart(ctx, R.string.notify_ok_home, R.string.notify_ok_home_skipped,
                    Switcher.lastAppliedTitle(true)));
        }
        if (lib.lock) {
            sb.append(scopePart(ctx, R.string.notify_ok_lock, R.string.notify_ok_lock_skipped,
                    Switcher.lastAppliedTitle(false)));
        }
        return sb.toString();
    }

    /** 单个范围的通知片段：本轮没切成功写「未切换」，切成功但没标题写「未命名」。 */
    private static String scopePart(Context ctx, int appliedRes, int skippedRes, String title) {
        if (title == null) {
            return ctx.getString(skippedRes);
        }
        return ctx.getString(appliedRes, title.isEmpty() ? ctx.getString(R.string.untitled) : title);
    }

    /** 下一条通知的独立 id（自增序号，保证历次通知都保留在通知栏）。 */
    private static int nextNotifyId(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        int seq = prefs.getInt(KEY_SEQ, 0) + 1;
        prefs.edit().putInt(KEY_SEQ, seq).apply();
        // 序号自然回绕，避免与历史 id 冲突（历史通知早已被用户或系统清理）
        return ID_BASE + (seq % 1_000_000);
    }

    /** 创建通知渠道（幂等，重复创建同 id 不会重置用户改动过的重要性）。 */
    private static void ensureChannels(Context ctx, NotificationManager nm) {
        NotificationChannel ok = new NotificationChannel(CHANNEL_OK,
                ctx.getString(R.string.notify_channel_ok), NotificationManager.IMPORTANCE_LOW);
        ok.setDescription(ctx.getString(R.string.notify_channel_ok_desc));
        NotificationChannel fail = new NotificationChannel(CHANNEL_FAIL,
                ctx.getString(R.string.notify_channel_fail), NotificationManager.IMPORTANCE_HIGH);
        fail.setDescription(ctx.getString(R.string.notify_channel_fail_desc));
        try {
            nm.createNotificationChannel(ok);
            nm.createNotificationChannel(fail);
        } catch (Exception ignored) {
        }
    }
}
