package com.example.wallswitch;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;

import android.support.v4.media.session.MediaSessionCompat;
import androidx.core.app.NotificationCompat;
import androidx.media.app.NotificationCompat.MediaStyle;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * 常驻「音乐播放器样式」切换通知：下拉通知栏常驻一条，显示当前桌面壁纸库、
 * 正在显示的壁纸标题与缩略图封面、下次自动切换的走秒倒计时，并提供
 * 「上一张 / 下一张」按钮直接切图（{@link NotifActionReceiver}）。
 *
 * 用 MediaStyle + MediaSession（网易云等音乐 App 的同款机制）：系统按媒体卡片渲染，
 * 按钮行与封面默认可见，收起/展开一个样；倒计时在头部区域走秒（Chronometer）。
 * 刻意不设 PlaybackState：伪装「正在播放」虽然能让系统渲染倒计时进度条，但 MagicOS
 * 会改用自己的播放模板（出现无功能的暂停键、吃掉按钮行），还会挤掉真正的音乐 App
 * 的媒体卡片——进度条只能放弃。MediaSession 只为渲染样式存在，进程级单例。
 *
 * 为什么常驻（setOngoing）：当前壁纸与切换节奏是用户想随时瞄一眼的状态，
 * 混在「到点通知」的历次记录里会被冲掉；ongoing 不会被一键清理清掉
 * （长按仍可单独移除，移除后由开机/周期任务/回到应用等刷新点自动补回；
 * 荣耀 ROM 的一键清理若仍会清掉，靠同样的刷新点自愈）。
 *
 * 只服务桌面范围（与桌面小组件同一套语义）：桌面启用库换人/停用即整条消失。
 *
 * 与 {@link SwitchNotifier} 互补：那边是「每次切换发一条留痕记录」（独立 id 互不覆盖），
 * 这边是「永远只有一条、内容随状态覆盖更新」（固定 id）。
 *
 * update() 可在任意线程调用：内部转到单线程后台执行器（缩略图解码是磁盘 IO），
 * 串行执行保证后到的状态覆盖先到的。
 */
public class StatusNotifier {

    // 通知渠道 id：常驻状态类通知独立建渠道，IMPORTANCE_LOW 静音不弹横幅
    private static final String CHANNEL = "home_status";
    // 固定通知 id（覆盖写）：避开 SwitchNotifier 的 ID_BASE=2000 自增段
    private static final int NOTIFY_ID = 1500;
    // 开关存储（与其它设置共用 settings），默认开
    private static final String PREFS_NAME = "settings";
    private static final String KEY_ENABLED = "status_notify";

    // 进程级 MediaSession：只为让通知按媒体卡片渲染（挂 token），不承载真实播放
    private static MediaSessionCompat session;

    // 缩略图解码与通知构建收口到后台（仿 TimerScheduler.EXECUTOR），主线程调用也安全
    private static final Executor EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "status-notif");
        thread.setDaemon(true);
        return thread;
    });

    /** 常驻通知开关是否开启（默认开）。 */
    public static boolean isEnabled(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, true);
    }

    /** 设置常驻通知开关；关闭时立刻移除已发出的通知。 */
    public static void setEnabled(Context ctx, boolean enabled) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_ENABLED, enabled)
                .apply();
        if (!enabled) {
            cancel(ctx);
        }
    }

    /**
     * 按当前状态刷新常驻通知：无启用桌面库/开关关 → 移除；否则按最新状态重发（幂等）。
     * 所有成功上屏与触发时间变化的路径都会调它，保证通知始终反映最新状态。
     */
    public static void update(Context ctx) {
        final Context app = ctx.getApplicationContext();
        EXECUTOR.execute(() -> {
            try {
                updateNow(app);
            } catch (Exception ignored) {
            }
        });
    }

    private static void updateNow(Context ctx) {
        if (!isEnabled(ctx)) {
            cancel(ctx);
            return;
        }
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || !nm.areNotificationsEnabled()) {
            return;
        }
        ensureChannel(ctx, nm);
        LibraryStore.Library lib = LibraryStore.enabledLibForScope(ctx, true);
        if (lib == null) {
            cancel(ctx);
            return;
        }
        try {
            nm.notify(NOTIFY_ID, build(ctx, lib));
        } catch (Exception ignored) {
        }
    }

    /** 构建通知：标题=当前壁纸标题、正文=库名，大图标=缩略图封面，动作=上一张/下一张。 */
    private static Notification build(Context ctx, LibraryStore.Library lib) {
        String currentId = Switcher.getCurrent(ctx, lib.id, true);
        String title = currentId == null ? null : WallpaperStore.getTitle(ctx, currentId);
        if (title == null || title.isEmpty()) {
            title = ctx.getString(R.string.untitled);
        }
        Bitmap cover = currentId == null ? null : WallpaperStore.getThumb(ctx, currentId);
        Intent open = new Intent(ctx, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPending = PendingIntent.getActivity(ctx, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_widget_switch)
                .setContentTitle(title)
                .setContentText(lib.name == null ? "" : lib.name)
                .setLargeIcon(cover)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(openPending)
                .addAction(new NotificationCompat.Action.Builder(R.drawable.ic_notif_prev,
                        ctx.getString(R.string.notif_action_prev),
                        actionPending(ctx, NotifActionReceiver.ACTION_PREV, 1)).build())
                .addAction(new NotificationCompat.Action.Builder(R.drawable.ic_notif_next,
                        ctx.getString(R.string.notif_action_next),
                        actionPending(ctx, NotifActionReceiver.ACTION_NEXT, 2)).build());
        // 倒计时：通知的 when 直接用墙钟触发时间——通知模板由系统从 when 渲染 Chronometer，
        // 不需要小组件那种 elapsedRealtime 换算（小组件用 Chronometer 控件才要自己算 base）
        long trigger = TimerScheduler.libTrigger(ctx, lib.id);
        if (trigger > System.currentTimeMillis()) {
            builder.setUsesChronometer(true)
                    .setChronometerCountDown(true)
                    .setWhen(trigger)
                    .setShowWhen(true)
                    .setSubText(ctx.getString(R.string.notify_status_next));
        } else {
            // 到点未执行（Doze/省电推迟）：倒计时已失效，改显「待切换」，与小组件一致
            builder.setUsesChronometer(false)
                    .setShowWhen(false)
                    .setSubText(ctx.getString(R.string.widget_waiting));
        }
        // 两个动作都放进收起态的按钮行：不展开也能直接切图
        builder.setStyle(new MediaStyle()
                .setMediaSession(mediaSession(ctx).getSessionToken())
                .setShowActionsInCompactView(0, 1));
        return builder.build();
    }

    /** 进程级 MediaSession 懒加载（用应用上下文，避免持有 Activity）。 */
    private static MediaSessionCompat mediaSession(Context ctx) {
        if (session == null) {
            session = new MediaSessionCompat(ctx.getApplicationContext(), "wallswitch_status");
        }
        return session;
    }

    /** 上一张/下一张按钮的广播 PendingIntent（接收在 NotifActionReceiver）。 */
    private static PendingIntent actionPending(Context ctx, String action, int requestCode) {
        Intent intent = new Intent(ctx, NotifActionReceiver.class);
        intent.setAction(action);
        return PendingIntent.getBroadcast(ctx, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** 移除常驻通知（开关关闭/无启用桌面库时调用）。 */
    public static void cancel(Context ctx) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.cancel(NOTIFY_ID);
        }
    }

    /** 创建通知渠道（幂等，重复创建同 id 不会重置用户改动过的重要性）。 */
    private static void ensureChannel(Context ctx, NotificationManager nm) {
        NotificationChannel channel = new NotificationChannel(CHANNEL,
                ctx.getString(R.string.notify_channel_status), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(ctx.getString(R.string.notify_channel_status_desc));
        try {
            nm.createNotificationChannel(channel);
        } catch (Exception ignored) {
        }
    }
}
