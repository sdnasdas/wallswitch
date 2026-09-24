package com.example.wallswitch;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.View;
import android.widget.RemoteViews;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * 常驻「音乐播放器样式」切换通知：下拉通知栏常驻一条，显示当前桌面壁纸库、
 * 正在显示的壁纸标题与缩略图封面、下次自动切换的走秒倒计时，并提供
 * 「上一张 / 下一张」按钮直接切图（{@link NotifActionReceiver}）。
 *
 * 为什么自绘 RemoteViews 而不是系统媒体卡片（MediaStyle + MediaSession）：
 * MagicOS 通知栏同时只显示一张媒体卡片——带媒体会话的通知会把音乐 App 的卡挤掉，
 * 而且伪装「正在播放」换进度条还会被系统换成自带暂停键的播放模板、吃掉按钮行。
 * 自绘布局零冲突、按钮常显，观感随深浅色（复用应用自己的 text/divider 颜色）。
 *
 * 倒计时用 Chronometer 控件：base 换算与桌面小组件一致（elapsedRealtime），
 * 由 SystemUI 渲染走秒，不耗电、进程被杀也在走；到点未执行（Doze 推迟）时
 * 改显「待切换」，与小组件的处理一致。
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

    /** 构建通知：标题=当前壁纸标题、副行=库名+走秒倒计时，封面=缩略图，按钮=上一张/下一张。 */
    private static Notification build(Context ctx, LibraryStore.Library lib) {
        String currentId = Switcher.getCurrent(ctx, lib.id, true);
        String title = currentId == null ? null : WallpaperStore.getTitle(ctx, currentId);
        if (title == null || title.isEmpty()) {
            title = ctx.getString(R.string.untitled);
        }
        Bitmap cover = currentId == null ? null : WallpaperStore.getThumb(ctx, currentId);
        // 收起态与展开态共用一套内容：收起显示小图标按钮（高度受限），展开换成大按钮
        RemoteViews collapsed = buildViews(ctx, lib, title, cover);
        collapsed.setViewVisibility(R.id.notif_icon_actions, View.VISIBLE);
        collapsed.setViewVisibility(R.id.notif_pill_actions, View.GONE);
        RemoteViews expanded = buildViews(ctx, lib, title, cover);
        expanded.setViewVisibility(R.id.notif_icon_actions, View.GONE);
        expanded.setViewVisibility(R.id.notif_pill_actions, View.VISIBLE);
        Intent open = new Intent(ctx, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPending = PendingIntent.getActivity(ctx, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        // DecoratedCustomViewStyle：让系统包一层标准头部（应用名等），正文用我们的布局
        return new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_widget_switch)
                .setCustomContentView(collapsed)
                .setCustomBigContentView(expanded)
                .setStyle(new Notification.DecoratedCustomViewStyle())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(openPending)
                .build();
    }

    /**
     * 构建通知正文视图（收起/展开两份共用）：内容一致，只有按钮形态由调用方切换。
     * RemoteViews 不带主题，文字颜色直接引用应用颜色资源（自带 values-night 夜间变体）。
     */
    private static RemoteViews buildViews(Context ctx, LibraryStore.Library lib,
            String title, Bitmap cover) {
        RemoteViews views = new RemoteViews(ctx.getPackageName(), R.layout.notification_status);
        views.setTextViewText(R.id.notif_title, title);
        views.setTextViewText(R.id.notif_lib, lib.name == null ? "" : lib.name);
        if (cover != null) {
            views.setImageViewBitmap(R.id.notif_cover, cover);
        } else {
            views.setViewVisibility(R.id.notif_cover, View.GONE);
        }
        // 倒计时：Chronometer 的 base 用开机计时（elapsedRealtime），与小组件换算一致；
        // 通知的 when 走墙钟是模板字段，RemoteViews 自绘 Chronometer 必须换算
        long trigger = TimerScheduler.libTrigger(ctx, lib.id);
        long now = System.currentTimeMillis();
        if (trigger > now) {
            long base = SystemClock.elapsedRealtime() + (trigger - now);
            views.setViewVisibility(R.id.notif_timer, View.VISIBLE);
            views.setViewVisibility(R.id.notif_waiting, View.GONE);
            views.setChronometer(R.id.notif_timer, base, null, true);
            views.setChronometerCountDown(R.id.notif_timer, true);
        } else {
            // 到点未执行（Doze/省电推迟）：倒计时已失效，改显「待切换」，与小组件一致
            views.setViewVisibility(R.id.notif_timer, View.GONE);
            views.setViewVisibility(R.id.notif_waiting, View.VISIBLE);
        }
        // 图标是黑色 vector，RemoteViews 不走主题 tint，手动按深浅色染成正文色
        int tint = ctx.getColor(R.color.text_primary);
        views.setInt(R.id.notif_prev_ic, "setColorFilter", tint);
        views.setInt(R.id.notif_next_ic, "setColorFilter", tint);
        // 上一张/下一张：两种形态的按钮挂同一组 PendingIntent
        PendingIntent prev = actionPending(ctx, NotifActionReceiver.ACTION_PREV, 1);
        PendingIntent next = actionPending(ctx, NotifActionReceiver.ACTION_NEXT, 2);
        views.setOnClickPendingIntent(R.id.notif_prev_ic, prev);
        views.setOnClickPendingIntent(R.id.notif_next_ic, next);
        views.setOnClickPendingIntent(R.id.notif_prev_pill, prev);
        views.setOnClickPendingIntent(R.id.notif_next_pill, next);
        return views;
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
