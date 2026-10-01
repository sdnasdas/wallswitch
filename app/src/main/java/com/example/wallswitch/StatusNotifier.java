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
 * 常驻「音乐播放器样式」切换通知：封面 + 当前这张的标题 + 「范围 · 库名 · 模式 · 每 X」
 * + 下次切换时间 + 上一张/暂停·继续/下一张三颗键（{@link NotifActionReceiver}）。
 *
 * <h3>桌面与锁屏各一条、各一个渠道</h3>
 * v3.80 起从"只服务桌面"改成按范围各建一条：两条通知走两个渠道（桌面切换状态 / 锁屏切换状态），
 * 用户可以在系统通知设置里分别调——例如桌面那条关掉锁屏显示、锁屏那条关掉横幅，
 * 于是锁屏时只看得到锁屏那条、使用时只看得到桌面那条。哪一面的槽位没库，那一条就不存在（撤掉）。
 * 桌面渠道的 id 沿用旧的 "home_status" 没改：他已经在旧渠道上调过的设置不能因为升级而失效。
 *
 * <h3>为什么自绘 RemoteViews 而不是系统媒体卡片（MediaStyle + MediaSession）</h3>
 * MagicOS 通知栏同时只显示一张媒体卡片——带媒体会话的通知会把音乐 App 的卡挤掉，
 * 而且伪装「正在播放」换进度条还会被系统换成自带暂停键的播放模板、吃掉按钮行。
 * 自绘布局零冲突、按钮常显，观感随深浅色（复用应用自己的 text/divider 颜色）。
 *
 * <h3>固定展开：只给一份视图</h3>
 * 以前给"收起态 + 展开态"两份，系统在右上角加那个 ⌄ 展开箭头、还得先展开才按得到。
 * 现在只给 {@code setCustomContentView} 一份完整布局，箭头消失、高度恒定。
 *
 * <h3>下次切换时间</h3>
 * 静态文案（「预计下次切换时间：15:42」），由切换/改设置时刷新。早期用走秒 Chronometer：
 * 真机实测它每秒唤醒 SystemUI 重绘通知，是持续发热的主要来源，已废弃为默认；
 * 只有在设置里打开「走秒」开关时才用 Chronometer（与小组件同一个开关 {@code tickingCountdown}）。
 *
 * <h3>为什么常驻（setOngoing）</h3>
 * 当前壁纸与切换节奏是用户想随时瞄一眼的状态，混在「到点通知」的历次记录里会被冲掉；
 * ongoing 不会被一键清理清掉（长按仍可单独移除，移除后由开机/周期任务/回到应用等刷新点自动补回）。
 *
 * <p>与 {@link SwitchNotifier} 互补：那边是「每次切换发一条留痕记录」（独立 id 互不覆盖），
 * 这边是「每个范围永远只有一条、内容随状态覆盖更新」（固定 id）。
 *
 * <p>{@code update()} 可在任意线程调用：内部转到单线程后台执行器（两封封面解码都是磁盘 IO），
 * 串行执行保证后到的状态覆盖先到的。
 */
public class StatusNotifier {

    // 通知渠道 id：桌面沿用旧值（不让已生效的用户设置落空），锁屏新增
    private static final String CHANNEL_HOME = "home_status";
    private static final String CHANNEL_LOCK = "lock_status";
    // 固定通知 id（各自覆盖写）：避开 SwitchNotifier 的 ID_BASE=2000 自增段
    private static final int NOTIFY_ID_HOME = 1500;
    private static final int NOTIFY_ID_LOCK = 1501;
    // 开关存储（与其它设置共用 settings），默认开；一个开关管两条（单独关某一条走系统里的渠道开关）
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

    /** 设置常驻通知开关；关闭时立刻把两条都撤掉。 */
    public static void setEnabled(Context ctx, boolean enabled) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_ENABLED, enabled)
                .apply();
        if (!enabled) {
            cancel(ctx);
        }
    }

    /**
     * 按当前状态刷新两个范围的常驻通知：该范围没库/开关关 → 撤那一条；否则按最新状态重发（幂等）。
     * 所有成功上屏与触发时间变化的路径都会调它，保证通知始终反映最新状态。
     */
    public static void update(Context ctx) {
        final Context app = ctx.getApplicationContext();
        EXECUTOR.execute(() -> {
            // 两条一起刷：桌面切一张时锁屏那条不会自己动，反之也一样
            //（代价是每次多解一封缩略图，后台线程、几毫秒级，比当年走秒那种每秒重绘低两个量级）
            safeUpdate(app, true);
            safeUpdate(app, false);
        });
    }

    private static void safeUpdate(Context app, boolean forHome) {
        try {
            updateScope(app, forHome);
        } catch (Exception ignored) {
        }
    }

    private static void updateScope(Context ctx, boolean forHome) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        if (!isEnabled(ctx) || !nm.areNotificationsEnabled()) {
            // 开关关了或系统通知总闸关了：把这一条撤掉，别留一条停在过去某时刻的僵尸卡
            nm.cancel(notifyId(forHome));
            return;
        }
        ensureChannel(ctx, nm, forHome);
        LibraryStore.Library lib = LibraryStore.slotLib(ctx, forHome);
        if (lib == null) {
            nm.cancel(notifyId(forHome));
            return;
        }
        nm.notify(notifyId(forHome), build(ctx, forHome, lib));
    }

    private static int notifyId(boolean forHome) {
        return forHome ? NOTIFY_ID_HOME : NOTIFY_ID_LOCK;
    }

    private static String channel(boolean forHome) {
        return forHome ? CHANNEL_HOME : CHANNEL_LOCK;
    }

    /** 移除两条常驻通知（开关关闭时调用）。 */
    public static void cancel(Context ctx) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        nm.cancel(NOTIFY_ID_HOME);
        nm.cancel(NOTIFY_ID_LOCK);
    }

    /** 构建某范围的通知：一份完整视图（固定展开），小图标用全透明替身（见 notif_icon_transparent）。 */
    private static Notification build(Context ctx, boolean forHome, LibraryStore.Library lib) {
        String currentId = Switcher.getCurrent(ctx, lib.id, forHome);
        String title = currentId == null ? null : WallpaperStore.getTitle(ctx, currentId);
        if (title == null || title.isEmpty()) {
            title = ctx.getString(R.string.untitled);
        }
        Bitmap cover = currentId == null ? null : WallpaperStore.getThumb(ctx, currentId);
        RemoteViews views = buildViews(ctx, forHome, lib, title, cover);
        Intent open = new Intent(ctx, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPending = PendingIntent.getActivity(ctx, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        // DecoratedCustomViewStyle：让系统包一层标准头部，正文用我们的布局
        return new Notification.Builder(ctx, channel(forHome))
                .setSmallIcon(R.drawable.notif_icon_transparent)
                .setCustomContentView(views)
                .setStyle(new Notification.DecoratedCustomViewStyle())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(openPending)
                .build();
    }

    /** 通知正文视图：封面 + 标题 + 范围/库/模式/间隔 + 下次切换时间 + 三颗键。 */
    private static RemoteViews buildViews(Context ctx, boolean forHome, LibraryStore.Library lib,
            String title, Bitmap cover) {
        RemoteViews views = new RemoteViews(ctx.getPackageName(), R.layout.notification_status);
        views.setTextViewText(R.id.notif_title, title);
        views.setTextViewText(R.id.notif_lib, summaryLine(ctx, forHome, lib));
        if (cover != null) {
            views.setImageViewBitmap(R.id.notif_cover, cover);
            views.setViewVisibility(R.id.notif_cover, View.VISIBLE);
        } else {
            views.setViewVisibility(R.id.notif_cover, View.GONE);
        }
        bindTimerLine(ctx, views, forHome);
        boolean paused = LibraryStore.slotPaused(ctx, forHome);
        views.setImageViewResource(R.id.notif_pause,
                paused ? R.drawable.ic_play : R.drawable.ic_pause);
        // 图标是黑色 vector，RemoteViews 不走主题 tint，手动按深浅色染成正文色
        int tint = ctx.getColor(R.color.text_primary);
        views.setInt(R.id.notif_prev, "setColorFilter", tint);
        views.setInt(R.id.notif_pause, "setColorFilter", tint);
        views.setInt(R.id.notif_next, "setColorFilter", tint);
        views.setOnClickPendingIntent(R.id.notif_prev, actionPending(ctx, 1,
                NotifActionReceiver.ACTION_PREV, forHome));
        views.setOnClickPendingIntent(R.id.notif_pause, actionPending(ctx, 3,
                NotifActionReceiver.ACTION_PAUSE, forHome));
        views.setOnClickPendingIntent(R.id.notif_next, actionPending(ctx, 2,
                NotifActionReceiver.ACTION_NEXT, forHome));
        return views;
    }

    /** 「范围 · 库名 · 模式 · 每 X」一行（范围写在最前：两条通知长得很像，靠它分辨）。 */
    private static String summaryLine(Context ctx, boolean forHome, LibraryStore.Library lib) {
        boolean random = LibraryStore.MODE_RANDOM.equals(LibraryStore.scopeMode(ctx, forHome));
        return ctx.getString(forHome ? R.string.scope_home : R.string.scope_lock)
                + " · " + (lib.name == null ? "" : lib.name)
                + " · " + ctx.getString(random ? R.string.mode_random : R.string.mode_order)
                + " · " + ctx.getString(R.string.slot_every_prefix)
                + intervalText(LibraryStore.scopeIntervalSeconds(ctx, forHome));
    }

    /** 时间那一行：暂停中直说"已暂停"，否则走秒倒计时或静态「预计下次切换时间：HH:mm」。 */
    private static void bindTimerLine(Context ctx, RemoteViews views, boolean forHome) {
        long trigger = TimerScheduler.scopeTrigger(ctx, forHome);
        long now = System.currentTimeMillis();
        if (LibraryStore.slotPaused(ctx, forHome)) {
            views.setViewVisibility(R.id.notif_timer, View.GONE);
            views.setViewVisibility(R.id.notif_waiting, View.VISIBLE);
            views.setTextViewText(R.id.notif_waiting, ctx.getString(R.string.slot_paused_label));
        } else if (trigger > now) {
            views.setViewVisibility(R.id.notif_timer, View.VISIBLE);
            views.setViewVisibility(R.id.notif_waiting, View.GONE);
            if (TimerScheduler.tickingCountdown(ctx)) {
                // Chronometer 的 base 走开机计时（elapsedRealtime），与小组件换算一致
                long base = SystemClock.elapsedRealtime() + (trigger - now);
                views.setChronometer(R.id.notif_timer, base, null, true);
                views.setChronometerCountDown(R.id.notif_timer, true);
            } else {
                views.setTextViewText(R.id.notif_timer,
                        ctx.getString(R.string.next_switch_at, TimerScheduler.clockText(ctx, trigger)));
            }
        } else {
            views.setViewVisibility(R.id.notif_timer, View.GONE);
            views.setViewVisibility(R.id.notif_waiting, View.VISIBLE);
        }
    }

    /** 间隔的中文读法（与 App 内卡片那行一致：整分钟说「30分钟」，带秒说「1分30秒」）。 */
    private static String intervalText(int seconds) {
        if (seconds >= 60 && seconds % 60 == 0) {
            return (seconds / 60) + "分钟";
        }
        if (seconds >= 60) {
            return (seconds / 60) + "分" + (seconds % 60) + "秒";
        }
        return seconds + "秒";
    }

    /**
     * 三颗键的广播 PendingIntent（接收在 NotifActionReceiver）。
     * requestCode 必须「动作 × 范围」各占一个：两条通知的 prev 若共用一个号，
     * FLAG_UPDATE_CURRENT 会让后建的那条把前一条的 extras 覆盖掉，锁屏的键就去切桌面了。
     */
    private static PendingIntent actionPending(Context ctx, int actionCode, String action, boolean forHome) {
        Intent intent = new Intent(ctx, NotifActionReceiver.class);
        intent.setAction(action);
        intent.putExtra(NotifActionReceiver.EXTRA_FOR_HOME, forHome);
        return PendingIntent.getBroadcast(ctx, actionCode + (forHome ? 0 : 10), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** 创建两个渠道（幂等，重复创建同 id 不会重置用户改动过的重要性/锁屏/横幅设置）。 */
    private static void ensureChannel(Context ctx, NotificationManager nm, boolean forHome) {
        NotificationChannel channel = new NotificationChannel(channel(forHome),
                ctx.getString(forHome ? R.string.notify_channel_status : R.string.notify_channel_status_lock),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(ctx.getString(forHome
                ? R.string.notify_channel_status_desc : R.string.notify_channel_status_lock_desc));
        try {
            nm.createNotificationChannel(channel);
        } catch (Exception ignored) {
        }
    }
}
