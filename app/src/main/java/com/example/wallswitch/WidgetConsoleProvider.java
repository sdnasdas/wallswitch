package com.example.wallswitch;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.widget.RemoteViews;
import android.widget.Toast;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 桌面控制台小组件：2 列 × 3 行、共六格 —— 左上 = 当前范围那一面的缩略图（点按打开 App）、
 * 右上 = 暂停/继续（只换图标）、左下 = 上一张、右下 = 下一张、
 * 第三行左 = 选库（弹 {@link LibPickerActivity}）、第三行右 = 范围翻面（桌面 ⇄ 锁屏）。
 *
 * <h3>作用范围</h3>
 * 暂停/上一张/下一张/选库这四格都作用在<b>当前范围</b>这一面，翻面键一按整卡换一面
 * （范围态在 {@link LibraryStore#widgetScopeHome}，默认桌面，缩略图跟着翻）。
 * v3.91 及之前这四格只认桌面。代价要说清：范围停在锁屏时，桌面那一面在小组件里没有入口，
 * 六格塞不下双套；桌面的自动切换照旧在跑，只是手动干预得把范围翻回来。
 *
 * <h3>格子边长是算出来的（v3.95）</h3>
 * 布局里每个格子都是 {@code wrap_content} + 一个 44dp 地板值，真正的边长在 {@link #buildViews} 里
 * 按桌面<b>申报</b>的槽位宽高算：{@link #cellDpFor} 读 {@code getAppWidgetOptions}，横向扣掉 32dp
 * 开销除以 2 格、纵向扣掉 26dp 除以 3 行，取小的那个当正方格边长，夹进 28~64dp。
 * 所以换设备、换桌面、改网格密度都不用改这里 —— 读到的数变了格子跟着变；读不到（空或 0）
 * 就落 44dp 地板，比 v3.94 那两档少一份要同步的布局，也更保守。
 * 为什么撑得动：RemoteViews 没有 {@code setLayoutParams}、也没有权重接口
 * （android-34 与 android-35 的 android.jar 都 javap 过），但 {@code View.setMinimumWidth/Height}
 * 是 public，而 ImageView / FrameLayout / LinearLayout 的 onMeasure 都吃 suggested minimum，
 * 于是 {@code setInt(id, "setMinimumWidth", px)} 就定得住边长。
 * 前提条件别改坏：<b>格子里的图绝不能用 {@code match_parent}</b> —— wrap_content 的父配
 * match_parent 的子，子会被量成父拿到的全部空间，整张卡直接撑爆槽位（v3.94 之前那版
 * 缩略图就是 match_parent，改成 wrap_content + 地板值正是为了这条）。
 *
 * <h3>与 1×1 那格的分工</h3>
 * 1×1 点一下 = 桌面与锁屏各自切一张；本控制台是"盯着一面手动操作"。
 * 动作语义与常驻通知的上一张/下一张同一套（{@link Switcher#prev}/{@link Switcher#next}
 * + 切完 {@code restartScope}），守卫也在 Switcher 里收口，这里不另立规矩 ——
 * 锁屏那一面同样走得通：1×1 那格早就在广播里调 {@code Switcher.next(app, lock.id, false)}。
 *
 * <h3>为什么"进入 App"占左上那一格</h3>
 * RemoteViews <b>没有长按 API</b>（android-34 的 android.jar 里只有 setOnClickPendingIntent /
 * setPendingIntentTemplate / setOnClickFillInIntent，长按只有集合控件那套
 * setOnItemLongClickPendingIntent，得配 RemoteViewsService 的列表）。所以"长按=开 App"这条路不成立，
 * 只能占一格实位；v3.92 起这一格就是缩略图本身（原来第三行那条横键腾出来给了选库与翻面两格）。
 *
 * <h3>为什么渲染要挪到后台线程</h3>
 * {@code onUpdate}/{@code onReceive} 跑在广播主线程上（Receiver 有 10 秒上限），而缩略图要读文件解码。
 * 所以刷新统一走 {@link #scheduleUpdate}：单线程队列里解码 + 递交（{@code updateAppWidget} 线程安全），
 * 并带一个 {@code queued} 闸门把连发的事件并成一次渲染。1×1 那格不含位图，仍在原调用点同步刷。
 *
 * <h3>发热账</h3>
 * {@code updatePeriodMillis=0}：不接 1×1 那个"30 分钟刷一次"的补切时机，免得同一刻补切跑两遍；
 * 不放 Chronometer（走秒会每秒驱动桌面重绘，真机实测是发热来源之一）；
 * 解码只发生在切换/暂停/换库/翻面/深浅色翻档/开机恢复这些已有事件上，一次是一回 192px 小 JPEG 解码，
 * 且带一个 {@code QUEUED} 闸门把连发并成一次渲染（代价换确定性：见 {@code scopeThumb} 为什么不缓存）。
 * 范围停在锁屏时每一张要全尺寸解成品图 + {@code setBitmap}，与通知那三颗键、1×1 那格同价，
 * 不是新增的开销类型；翻面本身只多一次 192px 解码。
 */
public class WidgetConsoleProvider extends AppWidgetProvider {

    /** 下一张（当前范围）。 */
    public static final String ACTION_NEXT = "com.example.wallswitch.CONSOLE_NEXT";
    /** 上一张（当前范围）。 */
    public static final String ACTION_PREV = "com.example.wallswitch.CONSOLE_PREV";
    /** 暂停 / 继续当前范围的自动切换。 */
    public static final String ACTION_PAUSE = "com.example.wallswitch.CONSOLE_PAUSE";
    /** 翻面：整卡的作用范围在桌面 ⇄ 锁屏之间换。 */
    public static final String ACTION_SCOPE = "com.example.wallswitch.CONSOLE_SCOPE";

    // PendingIntent requestCode：六格各占一个。共号会被 FLAG_UPDATE_CURRENT 合并 ——
    // 后建的那条把前一条的 Intent 覆盖掉，两格按下去变成同一个动作
    // 左上缩略图 = 打开 App（v3.91 之前这一格是选库入口）
    private static final int REQ_OPEN_APP = 11;
    private static final int REQ_PAUSE = 12;
    private static final int REQ_PREV = 13;
    private static final int REQ_NEXT = 14;
    // 第三行两格：选库、范围翻面
    private static final int REQ_LIB = 15;
    private static final int REQ_SCOPE = 16;

    // ===== 格子边长是算出来的（v3.95）：按桌面申报的槽位宽高，取横竖两向里"放得下"的那个 =====
    // 横向要放 2 格，固定开销 = 卡片内边距 4×2 + 两格外侧留白 3×2 + 列间距 18 = 32dp；
    // 纵向要放 3 格，固定开销 = 内边距 8 + 三行上下留白 6×3 = 26dp。
    private static final int H_SPARE_DP = 32;
    private static final int V_SPARE_DP = 26;
    private static final int COLS = 2;
    private static final int ROWS = 3;
    // 上限 64dp：再大就白占桌面、解码也顶到 Binder 余量。
    // 下限 28dp 只兜"槽位薄到算不出可用格子"这种病态情况 —— 正常情况下边长就是算出来的那个，
    // 哪怕它小。<b>这个下限不能抬到 40</b>：夹具 check/WidgetCellSizeTest 跑过，176×133dp 的槽位
    // 算出来是 35dp，夹到 40 就变成卡片 146dp 高、比槽位还高 13dp —— 又回到 v3.79 那种被切。
    // 宁可格子小一点，也不能撑出去。
    private static final int CELL_MIN_DP = 28;
    private static final int CELL_MAX_DP = 64;
    // 读不到申报值时的地板（与 widget_console.xml 里写的 minWidth/minHeight 同一个数）。
    // 44dp 格画出来是 120×158dp 的卡，等于假定"任何桌面至少给这么大"—— 这台机器的 2 行怎么也有
    // 133dp，所以兜得住；真要遇到更薄又不报数的桌面，这一档会切，是已知让位于简单性的取舍
    private static final int CELL_FLOOR_DP = 44;
    // 格子里的图标只占格子一半多一点（v3.94 之前是 52dp 的格里放 28dp 的图 = 0.54），跟着格子缩放
    private static final float ICON_RATIO = 0.54f;
    // 翻面那一格的图标更小一档：下面还压着一行「桌面/锁屏」
    private static final float SCOPE_ICON_RATIO = 0.3f;

    // 缩略图解码边长的硬夹。不能直接用 WallpaperStore.getThumb() 的结果 ——
    // 那是 384~768px 正方形（解码出来 0.6~2.3MB），而 RemoteViews 经 Binder 递交、单次事务约 1MB，
    // 超了的表现是小组件静默不更新（不报错）
    private static final int THUMB_MIN_PX = 96;
    private static final int THUMB_MAX_PX = 192;
    // 缩略图圆角（与壁纸网格里 RoundedGrid 的观感对齐）
    private static final float THUMB_CORNER_DP = 10f;

    /** 渲染队列：单线程串行，避免两张并发递交互相盖。 */
    private static final ExecutorService RENDER = Executors.newSingleThreadExecutor(runnable ->
            new Thread(runnable, "widget-console"));
    /** 已排队标记：渲染期间的重复请求直接合并掉（渲染读的是当时的最新状态）。 */
    private static final AtomicBoolean QUEUED = new AtomicBoolean(false);

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        scheduleUpdate(context);
    }

    /**
     * 桌面给的槽位变了（刚放置、换桌面、改网格密度、旋转都会走这里）：重挑一档。
     * 本类不参与补切，所以这里只需排一次渲染。
     */
    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager appWidgetManager,
                                          int appWidgetId, Bundle newOptions) {
        scheduleUpdate(context);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (ACTION_NEXT.equals(action) || ACTION_PREV.equals(action) || ACTION_PAUSE.equals(action)
                || ACTION_SCOPE.equals(action)) {
            // 切换含大图解码与引擎标脏，不能放广播主线程：仿 1×1 那格用 goAsync 起后台线程
            //（翻面虽只写一个 key + 重画一次，也走这条路：重画要解码缩略图，同样不能留在主线程）
            final PendingResult pending = goAsync();
            final Context app = context.getApplicationContext();
            final String act = action;
            new Thread(() -> {
                try {
                    handleAction(app, act);
                } catch (Exception ignored) {
                } finally {
                    pending.finish();
                }
            }, "widget-console-action").start();
            return;
        }
        // 其余（含 APPWIDGET_UPDATE）交给 super：会回调 onUpdate，本类不参与补切
        super.onReceive(context, intent);
    }

    /** 排一次渲染（幂等、合并连发）。任何线程都可调用。 */
    static void scheduleUpdate(Context ctx) {
        final Context app = ctx.getApplicationContext();
        if (QUEUED.compareAndSet(false, true)) {
            RENDER.execute(() -> {
                try {
                    render(app);
                } catch (Exception ignored) {
                } finally {
                    // 闸门在渲染之后才开：渲染过程中又变了状态的话会再排一次，不会停在旧画面上
                    QUEUED.set(false);
                }
            });
        }
    }

    /** 点按动作（后台线程执行）：除翻面外都作用在当前范围，语义与常驻通知按钮 / 1×1 那格一致。 */
    private static void handleAction(Context app, String action) {
        if (ACTION_SCOPE.equals(action)) {
            // 翻面只改一个显示态：不碰槽位、不碰定时、不上屏，重画一次整卡即可
            //（四格的动作与缩略图都在 buildViews 里按范围现读）
            LibraryStore.setWidgetScopeHome(app, !LibraryStore.widgetScopeHome(app));
            scheduleUpdate(app);
            return;
        }
        boolean forHome = LibraryStore.widgetScopeHome(app);
        if (ACTION_PAUSE.equals(action)) {
            // 暂停/继续整面：撤任务或重新起算、日志、刷小组件与常驻通知都在 setPaused 里收口
            TimerScheduler.setPaused(app, forHome, !LibraryStore.slotPaused(app, forHome));
            return;
        }
        LibraryStore.Library lib = LibraryStore.slotLib(app, forHome);
        if (lib == null) {
            // 这一面没库：上一张/下一张没有可切的对象。空着不提示是刻意的（两面同口径）——
            // 想配库就去点第三行左边那一格（那里是选库入口）
            return;
        }
        boolean ok = ACTION_NEXT.equals(action)
                ? Switcher.next(app, lib.id, forHome)
                : Switcher.prev(app, lib.id, forHome);
        if (ok) {
            // 手动切了一张 = 这一轮从此刻重新起算（与卡片双击、通知按钮、小组件点按同一口径）
            TimerScheduler.restartScope(app, forHome);
            return;
        }
        String reason = Switcher.lastError();
        if (reason != null) {
            // lastError 为 null 是"无副作用的空操作"（如上一张链路已空），与通知按钮同一口径不弹提示
            final String text = Switcher.errorText(app, reason);
            new Handler(Looper.getMainLooper()).post(() ->
                    Toast.makeText(app, text, Toast.LENGTH_SHORT).show());
        }
    }

    /** 渲染并递交所有已放置的实例（后台线程执行）。一个小组件都没摆时直接返回，连解码都不做。 */
    private static void render(Context ctx) {
        AppWidgetManager manager = AppWidgetManager.getInstance(ctx);
        ComponentName component = new ComponentName(ctx, WidgetConsoleProvider.class);
        int[] ids = manager.getAppWidgetIds(component);
        if (ids == null || ids.length == 0) {
            return;
        }
        // 一次 updateAppWidget(component, ...) 会把所有实例刷成同一份视图，所以边长只能取一个：
        // 按"报得最小的那个实例"算，宁可小的那个四周多露透明边，也不能大的那个撑出去被切
        int cellDp = CELL_FLOOR_DP;
        for (int id : ids) {
            cellDp = Math.min(cellDp, cellDpFor(manager, id));
        }
        manager.updateAppWidget(component, buildViews(ctx, cellDp));
    }

    /** 这个实例的槽位放得下多大的正方格（dp）；读不到申报值就回地板值。 */
    private static int cellDpFor(AppWidgetManager manager, int id) {
        Bundle options;
        try {
            options = manager.getAppWidgetOptions(id);
        } catch (Exception ignored) {
            return CELL_FLOOR_DP;
        }
        if (options == null) {
            return CELL_FLOOR_DP;
        }
        int width = slotDp(options, AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,
                AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH);
        int height = slotDp(options, AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,
                AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT);
        if (width <= 0 || height <= 0) {
            return CELL_FLOOR_DP;
        }
        int cell = Math.min((width - H_SPARE_DP) / COLS, (height - V_SPARE_DP) / ROWS);
        return Math.max(CELL_MIN_DP, Math.min(cell, CELL_MAX_DP));
    }

    /**
     * MIN 与 MAX 两个键里取"稳拿得到的那个"：只填了一个就用那个，两个都填取<b>小的</b> ——
     * 按大的算等于向桌面要它没打算给的空间，v3.79 那次 214dp 的卡片被切掉一截就是这么来的。
     */
    private static int slotDp(Bundle options, String minKey, String maxKey) {
        int min = options.getInt(minKey);
        int max = options.getInt(maxKey);
        if (min <= 0) {
            return max;
        }
        if (max <= 0) {
            return min;
        }
        return Math.min(min, max);
    }

    /** 构建六格视图：深浅色两档外观 + 图标态 + 当前范围的缩略图 + 各格的点按目标 + 算出来的格子边长。 */
    private static RemoteViews buildViews(Context ctx, int cellDp) {
        RemoteViews views = new RemoteViews(ctx.getPackageName(), R.layout.widget_console);
        float density = ctx.getResources().getDisplayMetrics().density;
        int cellPx = Math.round(cellDp * density);
        int iconPx = Math.round(cellPx * ICON_RATIO);
        int scopeIconPx = Math.round(cellPx * SCOPE_ICON_RATIO);
        // 边长下发：六格各自定成同一个正方值，格子里的图按同一比例跟着缩放。
        // setMinimumWidth/Height 是 View 的 public setter，ImageView 与两个布局的 onMeasure
        // 都吃 suggested minimum，所以 wrap_content 的格子这样就能定住（详见类注释那条）
        setSize(views, R.id.widget_cell_thumb, cellPx);
        setSize(views, R.id.widget_cell_pause, cellPx);
        setSize(views, R.id.widget_cell_prev, cellPx);
        setSize(views, R.id.widget_cell_next, cellPx);
        setSize(views, R.id.widget_cell_lib, cellPx);
        setSize(views, R.id.widget_cell_scope, cellPx);
        setSize(views, R.id.widget_thumb, cellPx);
        setSize(views, R.id.widget_pause, iconPx);
        setSize(views, R.id.widget_prev, iconPx);
        setSize(views, R.id.widget_next, iconPx);
        setSize(views, R.id.widget_lib_ic, iconPx);
        setSize(views, R.id.widget_scope_ic, scopeIconPx);
        boolean night = isNight(ctx);
        // 整卡读同一个范围：暂停图标与缩略图必须和四格动作指的是同一面，
        // 分头现读会出现"图标显示锁屏暂停中、按下去切的是桌面"这种自相矛盾
        boolean forHome = LibraryStore.widgetScopeHome(ctx);
        // 底色按档显式挑，不靠 values-night 自动翻（原因见 colors.xml 那段）
        int cardBg = night ? R.drawable.widget_bg_night : R.drawable.widget_bg;
        int cellBg = night ? R.drawable.widget_cell_bg_night : R.drawable.widget_cell_bg;
        views.setInt(R.id.widget_card, "setBackgroundResource", cardBg);
        views.setInt(R.id.widget_cell_thumb, "setBackgroundResource", cellBg);
        views.setInt(R.id.widget_cell_pause, "setBackgroundResource", cellBg);
        views.setInt(R.id.widget_cell_prev, "setBackgroundResource", cellBg);
        views.setInt(R.id.widget_cell_next, "setBackgroundResource", cellBg);
        views.setInt(R.id.widget_cell_lib, "setBackgroundResource", cellBg);
        views.setInt(R.id.widget_cell_scope, "setBackgroundResource", cellBg);
        views.setImageViewResource(R.id.widget_pause,
                LibraryStore.slotPaused(ctx, forHome) ? R.drawable.ic_play : R.drawable.ic_pause);
        // 图标本体是黑色 vector，颜色用 setColorFilter 现挑；文字同理走 setTextColor。
        // 刻意不在布局里写 android:tint：两者都作用在同一个 Drawable 上、互相覆盖，行为不透明
        //（常驻通知那三个图标也是这么处理的）
        int ink = ctx.getColor(night ? R.color.widget_ink_night : R.color.widget_ink);
        views.setInt(R.id.widget_pause, "setColorFilter", ink);
        views.setInt(R.id.widget_prev, "setColorFilter", ink);
        views.setInt(R.id.widget_next, "setColorFilter", ink);
        views.setInt(R.id.widget_lib_ic, "setColorFilter", ink);
        // 翻面那一格：图标 + 两字，两样都跟着范围换（光给图标分不出谁是桌面谁是锁屏）
        views.setImageViewResource(R.id.widget_scope_ic,
                forHome ? R.drawable.ic_home : R.drawable.ic_lock);
        views.setInt(R.id.widget_scope_ic, "setColorFilter", ink);
        views.setTextViewText(R.id.widget_scope_label,
                ctx.getString(forHome ? R.string.scope_home : R.string.scope_lock));
        views.setTextColor(R.id.widget_scope_label, ink);
        Bitmap thumb = scopeThumb(ctx, forHome, cellPx);
        if (thumb != null) {
            views.setImageViewBitmap(R.id.widget_thumb, thumb);
        } else {
            // 这一面没库 / 库里没图：灰色方块（点它照样进 App，选库去第三行那一格）
            views.setImageViewResource(R.id.widget_thumb,
                    night ? R.drawable.widget_thumb_empty_night : R.drawable.widget_thumb_empty);
        }
        // 左上缩略图 = 打开 App。与 1×1 那格"没配库时点按打开应用"同一条 intent
        //（不加 flag，走已验证过的路径）
        views.setOnClickPendingIntent(R.id.widget_cell_thumb, PendingIntent.getActivity(ctx,
                REQ_OPEN_APP, new Intent(ctx, MainActivity.class), piFlags()));
        // 第三行左格开选库页：小组件里弹不出列表（RemoteViews 没有下拉、也不认触摸），
        // 只能借一个只弹窗、没有界面的 Activity —— 见 LibPickerActivity。
        // 范围写进 extra：这一页要知道自己是给哪一面挑库（RemoteViews 弹不出带参列表，
        // 参数只能在建 PendingIntent 时钉死，而每次翻档都会重建这一条）
        Intent picker = new Intent(ctx, LibPickerActivity.class)
                .putExtra(LibPickerActivity.EXTRA_FOR_HOME, forHome);
        // 独立 taskAffinity 要配 NEW_TASK 才生效：不加的话这一页会被并进 App 已有的任务，
        // 「不把 MainActivity 顶上来」就白写了（manifest 里那条 taskAffinity="" 就是为它准备的）
        picker.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        views.setOnClickPendingIntent(R.id.widget_cell_lib, PendingIntent.getActivity(
                ctx, REQ_LIB, picker, piFlags()));
        views.setOnClickPendingIntent(R.id.widget_cell_pause,
                actionIntent(ctx, REQ_PAUSE, ACTION_PAUSE));
        views.setOnClickPendingIntent(R.id.widget_cell_prev,
                actionIntent(ctx, REQ_PREV, ACTION_PREV));
        views.setOnClickPendingIntent(R.id.widget_cell_next,
                actionIntent(ctx, REQ_NEXT, ACTION_NEXT));
        views.setOnClickPendingIntent(R.id.widget_cell_scope,
                actionIntent(ctx, REQ_SCOPE, ACTION_SCOPE));
        return views;
    }

    /**
     * 系统当前是否深色模式：读本进程的 {@code uiMode}。
     * 本 App 没有应用内深浅色开关（全项目无 setDefaultNightMode），所以这个值就等于系统设置；
     * 且它是在**我们自己进程**里解析的，与桌面重画小组件时用的是哪一档配置无关。
     */
    static boolean isNight(Context ctx) {
        int night = ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return night == Configuration.UI_MODE_NIGHT_YES;
    }

    /** 一个动作对应一条广播 PendingIntent（组件写死本类，不依赖 intent-filter 匹配）。 */
    private static PendingIntent actionIntent(Context ctx, int requestCode, String action) {
        Intent intent = new Intent(ctx, WidgetConsoleProvider.class);
        intent.setAction(action);
        return PendingIntent.getBroadcast(ctx, requestCode, intent, piFlags());
    }

    /** targetSdk 35 起必须显式声明可变性；这些 Intent 的 extras 全由本 App 写好，不可变即可。 */
    private static int piFlags() {
        return PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
    }

    /**
     * 屏上该显示哪张：该范围槽位的当前指针；指针还没落地（新占槽的库第一次被选）就退回库里第一张。
     * <b>纯读</b> —— 引擎自己那套"推进指针自愈"（WallSwitchService.drawCurrent）是有副作用的，
     * 小组件渲染不能替用户切一张。
     */
    private static String scopeThumbId(Context ctx, boolean forHome) {
        LibraryStore.Library lib = LibraryStore.slotLib(ctx, forHome);
        if (lib == null) {
            return null;
        }
        String current = Switcher.getCurrent(ctx, lib.id, forHome);
        if (current != null) {
            return current;
        }
        List<WallpaperStore.Item> items = WallpaperStore.loadByLib(ctx, lib.id);
        return items.isEmpty() ? null : items.get(0).id;
    }

    /**
     * 取一格大小的圆角缩略图；拿不到返回 null，由调用方显示空态灰块。
     * <b>刻意不做缓存</b>：按壁纸 id 缓存会在「覆盖当前这张」后停在旧图上
     * （id 没变、文件变了，缓存永远命中）。一次一两百 px 的小 JPEG 解码是几毫秒且在后台线程，
     * 而带 QUEUED 闸门的渲染本来就把连发事件并成一次 —— 省这一点不值得换一个错画面。
     * 翻面也走这条路（多解一次），为的是缩略图与四格动作指的是同一面。
     */
    private static Bitmap scopeThumb(Context ctx, boolean forHome, int cellPx) {
        String id = scopeThumbId(ctx, forHome);
        if (id == null) {
            return null;
        }
        Bitmap src = WallpaperStore.getWidgetThumb(ctx, id, thumbSide(cellPx));
        return src == null ? null : roundCorners(ctx, src);
    }

    /**
     * 缩略图解码边长：就用算出来的那个格子像素，再夹进 Binder 安全的区间。
     * 上限 192px 是硬约束（RemoteViews 经 Binder 递交，单次事务约 1MB，超了不报错、
     * 只是小组件静默不更新）；格子算到 64dp 上限时，2.75 密度下是 176px，仍在区间内，
     * 更高密度的机器上会被这里夹回 192px，代价只是图比显示尺寸略糊一点。
     */
    private static int thumbSide(int cellPx) {
        return Math.max(THUMB_MIN_PX, Math.min(cellPx, THUMB_MAX_PX));
    }

    /**
     * 定住一个 view 的边长（正方）。RemoteViews 没有 setLayoutParams，但
     * {@code View.setMinimumWidth/setMinimumHeight} 是 public setter，而 ImageView / FrameLayout /
     * LinearLayout 的 onMeasure 都吃 suggested minimum —— 配布局里的 wrap_content 就能把格子撑到指定像素。
     * 只在"变大"这个方向上起作用：布局里的 44dp 地板值兜住读不到申报数的场景，不会塌成图标的 24dp。
     */
    private static void setSize(RemoteViews views, int viewId, int px) {
        views.setInt(viewId, "setMinimumWidth", px);
        views.setInt(viewId, "setMinimumHeight", px);
    }

    /**
     * 把方图裁出圆角：RemoteViews 只会按 ImageView 的尺寸缩放位图，不会跟着背景的圆角裁剪，
     * 所以圆角必须画进位图本身。中间那份直角的立刻回收（同一张图瞬时不占两份）。
     */
    private static Bitmap roundCorners(Context ctx, Bitmap src) {
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        float radius = THUMB_CORNER_DP * dm.density;
        Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Path clip = new Path();
        clip.addRoundRect(new RectF(0, 0, src.getWidth(), src.getHeight()), radius, radius,
                Path.Direction.CW);
        canvas.clipPath(clip);
        canvas.drawBitmap(src, 0f, 0f, new Paint(Paint.FILTER_BITMAP_FLAG));
        if (!src.isRecycled()) {
            src.recycle();
        }
        return out;
    }
}
