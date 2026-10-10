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
 * 常驻「音乐播放器样式」切换通知：封面 + 「一键设置」那颗开关（占掉原来显示壁纸标题的那一行）
 * + 「库名 · 模式 · 每 X · 下次时间」+ 一颗「桌面/锁屏」角标 + 上一张/暂停·继续/下一张三颗键
 * （{@link NotifActionReceiver}）。开关那件事归 {@link PinnedWallpaper}。
 *
 * <h3>一条通知，作用面靠角标翻</h3>
 * v3.80 试过"桌面与锁屏各一条、各一个渠道"（好在系统里分别调横幅与锁屏显示），实际用下来还是嫌两条啰嗦，
 * v3.81 收回一条：卡片上那颗角标写着现在这三颗键打在哪个面上，点它翻面并当场重画。
 * 合并的代价要说清：按渠道分屏显示（桌面那条关锁屏、锁屏那条关横幅）这套手法没有了，
 * 而且他给桌面渠道关掉的「锁屏显示」会继续作用在这条唯一的通知上——想在锁屏上翻面操作，得去系统里把它打开。
 * 渠道 id 沿用旧的 "home_status" 没改：他已经在旧渠道上调过的设置不能因为升级而失效；
 * 显示名改成「壁纸切换状态」只对全新安装生效（Android 不会重命名已存在的渠道）。
 * 锁屏那条的 id 1501 每次刷新顺手 cancel 一次，免得升级后留一条永远不动的僵尸卡；它的渠道 "lock_status"
 * 第一次刷新时删掉（一个一次性标记位）。原本打算留着不删，怕抹掉他调过的设置——真机反馈是他在通知管理里
 * 看到这条「锁屏切换状态」，问"这个通知控制了什么，开关好像没影响"：一条不再发东西的渠道在设置里就是
 * 划不掉的噪音，删掉才对。注意 Android 会挡住"删了重建同 id 渠道"约 24 小时（防 App 借重建重置用户设置），
 * 真要恢复双通知得换个新渠道 id。
 *
 * <h3>切换中（loading）</h3>
 * 锁屏那面走 {@code TakeoverManager.setLockFromFile} → 全图解码 + 系统写盘，真机要几秒；这期间通知上
 * 没有任何反馈，他会以为没按着。所以 {@link #showBusy} 把封面糊成毛玻璃（{@link #frost} 缩了再拉 + 一层
 * 半透 veil）并在正中盖一颗转圈，不加文字（v3.82 第一版放在副标题行配「切换中…」文字，真机反馈
 * "文字多余、缩略图倒是提前换了"；v3.83 先改成转圈盖封面，他再要了毛玻璃），
 * 同时四颗键摘掉点击并把图标染成次级色（{@code setOnClickPendingIntent(id, null)} 在 AOSP 里就是
 * setClickable(false)）。糊掉是说得通的：{@code applyById} 第一件事就写 {@code _current}，
 * 慢的是后面的上屏，所以切换中显示的标题/封面本来就是"即将上屏那一张"——糊掉正好读成"这张还没落定"。
 * 快路径（桌面）不该闪一下 loading，所以由 {@link NotifActionReceiver} 用 400ms 门槛决定画不画：
 * 400ms 内跑完就一次都不画。跑完一定要落回正常态，那段收尾逻辑写在 NotifActionReceiver 里。
 * 转圈与封面同框（40dp 里居中），整张卡高度不受影响，还是 128dp。
 *
 * <h3>为什么不用系统媒体卡片（MediaStyle + MediaSession）</h3>
 * MagicOS 通知栏同时只显示一张媒体卡片——带媒体会话的通知会把音乐 App 的卡挤掉，
 * 而且伪装「正在播放」换进度条还会被系统换成自带暂停键的播放模板、吃掉按钮行。
 * 自绘布局零冲突、按钮常显，观感随深浅色（复用应用自己的 text/divider 颜色）。
 *
 * <h3>「固定展开」到底靠什么成立</h3>
 * 只给 {@code setCustomContentView} 一份视图，在 AOSP 上就等于不可展开：SystemUI 的判据是
 * {@code row.setExpandable(expanded != null)}，没有"展开态"那份 RemoteViews 就没有可展开的东西。
 * 但还有第二道闸：收起态的通知会被裁到 146dp（AOSP dimens 的 notification_min_height_increased，
 * targetSdk ≥ 31 走这一档），超出的部分藏起来、MagicOS 于是给一个展开入口——上一版 164dp 就是这么栽的。
 * 所以这一版把整张卡（含系统那条约 30dp 的头部）压到约 128dp：没有被裁的东西，展开这一步就没有存在理由。
 * 箭头画不画是 ROM 的事，我们保证的是"点开也不会多出任何东西"。
 *
 * <h3>下次切换时间</h3>
 * 静态短句（「下次 21:45」），由切换/改设置时刷新。早期用走秒 Chronometer：真机实测它每秒唤醒
 * SystemUI 重绘通知，是持续发热的主要来源，已废弃为默认；只有在设置里打开「走秒」开关时才用
 * Chronometer（与小组件同一个开关 {@code tickingCountdown}）。
 *
 * <h3>为什么常驻（setOngoing）</h3>
 * 当前壁纸与切换节奏是用户想随时瞄一眼的状态，混在「到点通知」的历次记录里会被冲掉；
 * ongoing 不会被一键清理清掉（长按仍可单独移除，移除后由开机/周期任务/回到应用等刷新点自动补回）。
 *
 * <p>与 {@link SwitchNotifier} 互补：那边是「每次切换发一条留痕记录」（独立 id 互不覆盖），
 * 这边是「永远只有一条、内容随状态覆盖更新」（固定 id）。
 *
 * <p>{@code update()} 可在任意线程调用：内部转到单线程后台执行器（封面解码是磁盘 IO），
 * 串行执行保证后到的状态覆盖先到的。
 */
public class StatusNotifier {

    // 渠道 id 沿用 v3.80 之前那个（用户在这上面调过的重要性/横幅/锁屏设置要接着生效）
    private static final String CHANNEL = "home_status";
    // 固定通知 id（覆盖写）：避开 SwitchNotifier 的 ID_BASE=2000 自增段
    private static final int NOTIFY_ID = 1500;
    // v3.80 那条锁屏通知的后事：id 要 cancel 干净，渠道也要撤掉——它已经不再发东西，
    // 留在系统通知设置里就是一条划不掉的空条目（他会来问"这条管什么"，因为开关确实没影响）
    private static final int LEGACY_LOCK_NOTIFY_ID = 1501;
    private static final String LEGACY_LOCK_CHANNEL = "lock_status";
    private static final String KEY_LEGACY_CHANNEL_CLEANED = "legacy_lock_channel_removed";
    // 开关存储（与其它设置共用 settings），默认开
    private static final String PREFS_NAME = "settings";
    private static final String KEY_ENABLED = "status_notify";
    // 三颗键现在打在哪一面：true = 桌面。翻面只由通知上那颗角标改它
    private static final String KEY_SCOPE_HOME = "notif_scope_home";

    // 四颗键的 requestCode：只剩一条通知，不再需要"动作 × 范围"各占一个号
    private static final int REQ_PREV = 1;
    private static final int REQ_NEXT = 2;
    private static final int REQ_PAUSE = 3;
    private static final int REQ_SCOPE = 4;
    // 「一键设置」那颗开关：钉与撤共用这一个号（动作在点击时现读，见 NotifActionReceiver）
    private static final int REQ_PIN = 5;
    // 切换中那张封面糊完之后的长边像素：40dp 的显示尺寸用得上 96px 就够用
    private static final int FROST_PX = 96;

    // 缩略图解码与通知构建收口到后台（仿 TimerScheduler.EXECUTOR），主线程调用也安全
    private static final Executor EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "status-notif");
        thread.setDaemon(true);
        return thread;
    });

    /** 常驻通知开关是否开启（默认开）。 */
    public static boolean isEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_ENABLED, true);
    }

    /** 设置常驻通知开关；关闭时立刻把通知撤掉。 */
    public static void setEnabled(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, enabled).apply();
        if (!enabled) {
            cancel(ctx);
        }
    }

    /** 三颗键当前作用的那一面（true = 桌面）。会自愈：存的那一面已经没库、另一面有 → 挪过去并落盘。 */
    public static boolean currentScope(Context ctx) {
        SharedPreferences sp = prefs(ctx);
        boolean forHome = sp.getBoolean(KEY_SCOPE_HOME, true);
        if (LibraryStore.slotLib(ctx, forHome) == null && LibraryStore.slotLib(ctx, !forHome) != null) {
            forHome = !forHome;
            sp.edit().putBoolean(KEY_SCOPE_HOME, forHome).apply();
        }
        return forHome;
    }

    /** 翻作用面。另一面没设库时什么都不做——那颗角标点下去没得切，就保持原样，不做假动作。 */
    public static void toggleScope(Context ctx) {
        boolean next = !currentScope(ctx);
        if (LibraryStore.slotLib(ctx, next) == null) {
            return;
        }
        prefs(ctx).edit().putBoolean(KEY_SCOPE_HOME, next).apply();
    }

    /**
     * 按当前状态刷新常驻通知：两面都没库/开关关 → 撤掉；否则按最新状态重发（幂等）。
     * 所有成功上屏与触发时间变化的路径都会调它，保证通知始终反映最新状态。
     */
    public static void update(Context ctx) {
        render(ctx, false);
    }

    /**
     * 把通知切成「切换中」态（转圈 + 四颗键不可点）。只在动作真的慢的时候由 {@link NotifActionReceiver}
     * 调，跑完必须再调一次 {@link #update} 落回正常态。与 update 排同一个后台执行器，先后顺序可靠。
     */
    public static void showBusy(Context ctx) {
        render(ctx, true);
    }

    private static void render(Context ctx, boolean busy) {
        final Context app = ctx.getApplicationContext();
        EXECUTOR.execute(() -> {
            try {
                updateNotification(app, busy);
            } catch (Exception ignored) {
            }
        });
    }

    private static void updateNotification(Context ctx, boolean busy) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        // v3.80 的锁屏那条不会再被重发，但升级那一刻它还挂在屏上：撤干净（不存在时是空操作）
        nm.cancel(LEGACY_LOCK_NOTIFY_ID);
        if (!isEnabled(ctx) || !nm.areNotificationsEnabled()) {
            // 开关关了或系统通知总闸关了：撤掉，别留一条停在过去某时刻的僵尸卡
            nm.cancel(NOTIFY_ID);
            return;
        }
        ensureChannel(ctx, nm);
        boolean forHome = currentScope(ctx);
        LibraryStore.Library lib = LibraryStore.slotLib(ctx, forHome);
        if (lib == null) {
            // 两面都没库（currentScope 已把能切的那面自愈过来，走到这里就是真没内容可显示）
            nm.cancel(NOTIFY_ID);
            return;
        }
        nm.notify(NOTIFY_ID, build(ctx, forHome, lib, busy));
    }

    /** 移除常驻通知（开关关闭时调用）。 */
    public static void cancel(Context ctx) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        nm.cancel(NOTIFY_ID);
        nm.cancel(LEGACY_LOCK_NOTIFY_ID);
    }

    /** 构建通知：一份完整视图（不提供展开态），小图标用全透明替身（见 notif_icon_transparent）。 */
    private static Notification build(Context ctx, boolean forHome, LibraryStore.Library lib, boolean busy) {
        String currentId = Switcher.getCurrent(ctx, lib.id, forHome);
        Bitmap cover = currentId == null ? null : WallpaperStore.getThumb(ctx, currentId);
        RemoteViews views = buildViews(ctx, forHome, lib, cover, busy);
        Intent open = new Intent(ctx, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPending = PendingIntent.getActivity(ctx, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        // DecoratedCustomViewStyle：让系统包一层标准头部，正文用我们的布局
        return new Notification.Builder(ctx, CHANNEL)
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

    /** 通知正文视图：封面 + 「一键设置」那颗开关 + 库/模式/间隔/下次切换 + 作用面角标 + 三颗键。 */
    private static RemoteViews buildViews(Context ctx, boolean forHome, LibraryStore.Library lib,
            Bitmap cover, boolean busy) {
        RemoteViews views = new RemoteViews(ctx.getPackageName(), R.layout.notification_status);
        views.setTextViewText(R.id.notif_lib, summaryLine(ctx, forHome, lib));
        if (cover != null) {
            views.setImageViewBitmap(R.id.notif_cover, busy ? frost(cover) : cover);
            views.setViewVisibility(R.id.notif_cover, View.VISIBLE);
        } else {
            views.setViewVisibility(R.id.notif_cover, View.GONE);
        }
        // 切换中：封面糊掉 + 盖一层半透 veil + 转圈，不加文字。封面此时显示的已经是"即将上屏那一张"——
        // applyById 第一件事就是写 _current，慢的是后面的那次静态存档写入（真机反馈与代码一致），
        // 糊掉正好读成"这张还没落定"，比在副标题行写「切换中…」更对得上事实
        views.setViewVisibility(R.id.notif_busy, busy ? View.VISIBLE : View.GONE);
        views.setViewVisibility(R.id.notif_cover_frost, busy && cover != null ? View.VISIBLE : View.GONE);
        bindTimerLine(ctx, views, forHome);
        boolean paused = LibraryStore.slotPaused(ctx, forHome);
        views.setImageViewResource(R.id.notif_pause,
                paused ? R.drawable.ic_play : R.drawable.ic_pause);
        // 图标是黑色 vector，RemoteViews 不走主题 tint，手动按深浅色染成正文色；切换中染成次级色装成"按不动"
        int tint = ctx.getColor(busy ? R.color.text_secondary : R.color.text_primary);
        views.setInt(R.id.notif_prev, "setColorFilter", tint);
        views.setInt(R.id.notif_pause, "setColorFilter", tint);
        views.setInt(R.id.notif_next, "setColorFilter", tint);
        // 作用面角标：文案就是那一面的名字，颜色桌面走主色、锁屏走紫（两个色都有夜间变体）
        views.setTextViewText(R.id.notif_scope,
                ctx.getString(forHome ? R.string.scope_home : R.string.scope_lock));
        views.setTextColor(R.id.notif_scope,
                ctx.getColor(forHome ? R.color.brand : R.color.notif_scope_lock));
        // busy 时四颗键一律传 null：AOSP 的 setOnClickPendingIntent(id, null) 会改成 setClickable(false)，
        // 也就是"点了没反应"，正是切换中要的效果（免得连点排出一串活）
        views.setOnClickPendingIntent(R.id.notif_scope,
                keyPending(ctx, busy, REQ_SCOPE, NotifActionReceiver.ACTION_SCOPE));
        views.setOnClickPendingIntent(R.id.notif_prev,
                keyPending(ctx, busy, REQ_PREV, NotifActionReceiver.ACTION_PREV));
        views.setOnClickPendingIntent(R.id.notif_pause,
                keyPending(ctx, busy, REQ_PAUSE, NotifActionReceiver.ACTION_PAUSE));
        views.setOnClickPendingIntent(R.id.notif_next,
                keyPending(ctx, busy, REQ_NEXT, NotifActionReceiver.ACTION_NEXT));
        bindOneTap(ctx, views, busy);
        return views;
    }

    /**
     * 「一键设置」那颗开关（占掉原来壁纸标题那一行，紧挨封面）：抽屉里没选过图就整颗 GONE ——
     * 这一行不留标题、也不摆别的东西，位置由封面那 40dp 兜住，所以整卡高度与升级前一分不差。
     *
     * <p>没有配对的说明文字：状态全靠「钮在左/在右 + 轨道空心/实心」读。点击 PendingIntent 就挂在这颗
     * ImageView 本身上，可点范围 = 可见范围 36×22dp（用户明确要求不要撑大，也不 push 到行尾）。
     * 钉还是撤仍在点击时现读 {@link PinnedWallpaper#canUndo}，这里只负责画对档位 ——
     * 与那颗作用面角标同一套"显示与动作同源"的规矩。
     *
     * <p>切换中只把点击锁掉（传 null），图形不上滤镜：那份图是 layer-list 的两块实心色，
     * setColorFilter 会把轨道与圆钮压成同一个颜色（等于把状态抹了）；"还在忙"这件事由封面那层
     * 毛玻璃 + 转圈说。
     */
    private static void bindOneTap(Context ctx, RemoteViews views, boolean busy) {
        if (!PinnedWallpaper.isConfigured(ctx)) {
            views.setViewVisibility(R.id.notif_one_tap_switch, View.GONE);
            return;
        }
        views.setViewVisibility(R.id.notif_one_tap_switch, View.VISIBLE);
        views.setImageViewResource(R.id.notif_one_tap_switch,
                PinnedWallpaper.canUndo(ctx) ? R.drawable.notif_switch_on : R.drawable.notif_switch_off);
        views.setOnClickPendingIntent(R.id.notif_one_tap_switch,
                keyPending(ctx, busy, REQ_PIN, NotifActionReceiver.ACTION_PIN));
    }

    /** 「库名 · 模式 · 每 X」一行（作用面不再写在这里——它已经是第二行那颗角标）。 */
    private static String summaryLine(Context ctx, boolean forHome, LibraryStore.Library lib) {
        boolean random = LibraryStore.MODE_RANDOM.equals(LibraryStore.scopeMode(ctx, forHome));
        return (lib.name == null ? "" : lib.name)
                + " · " + ctx.getString(random ? R.string.mode_random : R.string.mode_order)
                + " · " + ctx.getString(R.string.slot_every_prefix)
                + intervalText(LibraryStore.scopeIntervalSeconds(ctx, forHome));
    }

    /** 时间那一行：暂停中直说"已暂停"，否则走秒倒计时或静态短句「下次 21:45」。 */
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
                        ctx.getString(R.string.next_switch_short, TimerScheduler.clockText(ctx, trigger)));
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
     * 四颗键的广播 PendingIntent（接收在 NotifActionReceiver）；{@code busy} 为真时返回 null，
     * 也就是把那颗键在切换中锁掉。
     * 范围不再塞进 extras：三颗键在点击时现读 {@link #currentScope}，这样"通知上显示哪一面"和
     * "键打在哪一面"只有一个来源——升级前发出去、还带着旧 extras 的 PendingIntent 也就不会把键打到别的面去。
     */
    private static PendingIntent keyPending(Context ctx, boolean busy, int requestCode, String action) {
        if (busy) {
            return null;
        }
        Intent intent = new Intent(ctx, NotifActionReceiver.class);
        intent.setAction(action);
        return PendingIntent.getBroadcast(ctx, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /**
     * 创建渠道（幂等，重复创建同 id 不会重置用户改动过的重要性/锁屏/横幅设置）。
     * 顺带一次性删掉 v3.80 遗留的锁屏渠道：它不再发任何东西，留着就是在通知管理里多一条管不着任何事的条目。
     */
    private static void ensureChannel(Context ctx, NotificationManager nm) {
        NotificationChannel channel = new NotificationChannel(CHANNEL,
                ctx.getString(R.string.notify_channel_status),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(ctx.getString(R.string.notify_channel_status_desc));
        try {
            nm.createNotificationChannel(channel);
        } catch (Exception ignored) {
        }
        SharedPreferences sp = prefs(ctx);
        if (sp.getBoolean(KEY_LEGACY_CHANNEL_CLEANED, false)) {
            return;
        }
        try {
            nm.deleteNotificationChannel(LEGACY_LOCK_CHANNEL);
            sp.edit().putBoolean(KEY_LEGACY_CHANNEL_CLEANED, true).apply();
        } catch (Exception ignored) {
        }
    }

    /**
     * 切换中封面的"毛"：先缩到 1/6 再双线性拉回来，等效一层高斯模糊——40dp 的图上看不出与真模糊的差别，
     * 而 RemoteViews 里本来也拿不到 RenderEffect（SystemUI 不会给我们子 view 加特效）。
     * 顺手把成品压到长边 96px（{@link #FROST_PX}）：正常封面是 384~768px，糊完这张只有 96px，
     * 所以切换中那次跨进程反而比平时更小（Binder 单次事务约 1MB，见 WidgetConsoleProvider 顶部注释）。
     */
    private static Bitmap frost(Bitmap src) {
        int longSide = Math.max(src.getWidth(), src.getHeight());
        int w = Math.max(1, Math.round(src.getWidth() * FROST_PX / (float) longSide));
        int h = Math.max(1, Math.round(src.getHeight() * FROST_PX / (float) longSide));
        Bitmap small = Bitmap.createScaledBitmap(src, Math.max(1, w / 6), Math.max(1, h / 6), false);
        Bitmap out = Bitmap.createScaledBitmap(small, w, h, true);
        if (out != small && !small.isRecycled()) {
            small.recycle();
        }
        return out;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
