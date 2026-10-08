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
 * 桌面控制台小组件的渲染与动作收口：<b>两种格位共用这一份代码</b> —— 竖版 {@code widget_console}
 * （内部 2 列 × 3 行，向桌面要 2×2）与横版 {@code widget_console_wide}（内部 3 列 × 2 行，向桌面要
 * 3×2，壳见 {@link WidgetConsoleWideProvider}）。六格内容是同一套：选库、当前范围缩略图（点按打开
 * App）、范围翻面（桌面 ⇄ 锁屏）、上一张、暂停/继续、下一张；两份布局的 view id 逐个对齐，
 * 所以 {@link #buildViews} 只多收一份 spec（布局资源 + 那一带的格子算式）。
 *
 * <h3>作用范围</h3>
 * 除翻面外五格都作用在<b>当前范围</b>这一面，翻面键一按整卡换一面
 * （范围态在 {@link LibraryStore#widgetScopeHome}，默认桌面，缩略图跟着翻）。
 * 代价要说清：范围停在锁屏时，桌面那一面在小组件里没有入口，六格塞不下双套；桌面的自动切换照旧在跑，
 * 只是手动干预得把范围翻回来。
 *
 * <h3>翻面那一格为什么不再配文字</h3>
 * v3.95 及之前是「小图标 + 桌面/锁屏两字」，当时的理由是"两面没有天然的区分符号"。现在换成
 * 上下两张横卡片（{@code ic_scope_card_home} / {@code ic_scope_card_lock}）：两个可选项一次画全，
 * 当前那一张整块实心、另一张只描边，实心那张里再抠一个房子/锁的透明洞写明是哪一面 —— 状态主要靠
 * "哪张实心"传（不含需要认的小图形，格子缩到多小都读得出），洞只是把约定再标一遍。
 * 中间那一档用过竖胶囊，真机截图上太瘦（胶囊只占格宽的 22%）所以换掉，见那两个文件顶上的账。
 * 两态各一份文件：实心块画在上半还是下半是<b>形状</b>信息，而 RemoteViews 只能整张换 drawable，
 * 没有改子路径透明度的 API。
 *
 * <h3>格子边长是算出来的（v3.95），两种格位各带一套常数</h3>
 * 布局里每个格子都是 {@code wrap_content} + 一个 44dp 地板值，真正的边长在 {@link #buildViews} 里
 * 按桌面<b>申报</b>的槽位宽高算：{@link #cellDpFor} 读 {@code getAppWidgetOptions}，横向扣掉
 * {@code hSpareDp} 除以列数、纵向扣掉 {@code vSpareDp} 除以行数，取小的那个当<b>正方</b>格边长，
 * 夹进 28~84dp；读不到（空或 0）就落该档的地板 44dp。竖版开销 32/26dp，横版 50/20dp —— 差额来自
 * 列间距的个数不同（账写在两份布局文件顶上）。<b>六格必须等大</b>，所以刻意不用 layout_weight：
 * 桌面给的 3×2 槽位长宽比不是 1.5（横格比竖格宽），正方格按"两向里小的那个"算，必然有一个方向
 * 画不满，剩下的是透明边、露出壁纸；占位是不是 3×2 由 dp 请求决定，与卡片画多宽无关。
 * 上限从 64dp 抬到 84dp 是 v3.98 的事：真机截图量出来桌面给了约 300×194dp 的槽位、卡片只画了
 * 242×148dp，两个方向同时空着，说明是<b>上限</b>在夹而不是算式不够 —— 算式本身不会撑出槽位
 * （(槽宽−开销)/列数 与 (槽高−开销)/行数 取小，代回去必然 ≤ 槽位），所以抬上限只是把"本来给得起
 * 的空间"用起来，代价是缩略图要跟着解大一点（见 {@code THUMB_MAX_PX}）。
 * 为什么撑得动：RemoteViews 没有 {@code setLayoutParams}、也没有权重接口
 * （android-34 与 android-35 的 android.jar 都 javap 过），但 {@code View.setMinimumWidth/Height}
 * 是 public，而 ImageView / FrameLayout 的 onMeasure 都吃 suggested minimum，
 * 于是 {@code setInt(id, "setMinimumWidth", px)} 就定得住边长。
 * 前提条件别改坏：<b>格子里的图绝不能用 {@code match_parent}</b> —— wrap_content 的父配
 * match_parent 的子，子会被量成父拿到的全部空间，整张卡直接撑爆槽位（v3.94 之前那版缩略图就是
 * match_parent，改成 wrap_content + 地板值正是为了这条）。
 *
 * <h3>一条队列、一个闸门就够（两种组件同时摆着也不会漏刷）</h3>
 * {@link #render} 每跑一次就把 {@link #SPECS} 里<b>两份</b>视图都递交一遍，所以共用的 {@code QUEUED}
 * 合并掉的只是"同一轮里重复排的那次渲染"，不会因为另一个组件类抢不到闸门而停在旧画面；没摆上桌面的
 * 那一份在 {@code getAppWidgetIds} 就返回，连缩略图都不解。给两个 provider 各配一条队列一个闸门的
 * 写法为什么不要：桌面里两个都摆着时会并行解码，而且动作广播、切换语义会跟着长成两套。
 * 点按目标一律写死<b>本类</b>（{@link #actionIntent}），横版那格的广播也送到这里，横竖共用
 * {@link #handleAction} —— PendingIntent 的匹配看的是 Intent 的 component + action，不是 requestCode，
 * 所以两种布局共用 11~16 这六个号不会串。
 *
 * <h3>与 1×1 那格的分工</h3>
 * 1×1 点一下 = 桌面与锁屏各自切一张；本控制台是"盯着一面手动操作"。
 * 动作语义与常驻通知的上一张/下一张同一套（{@link Switcher#prev}/{@link Switcher#next}
 * + 切完 {@code restartScope}），守卫也在 Switcher 里收口，这里不另立规矩 ——
 * 锁屏那一面同样走得通：1×1 那格早就在广播里调 {@code Switcher.next(app, lock.id, false)}。
 *
 * <h3>为什么"打开 App"占一格实位</h3>
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
 * 解码只发生在切换/暂停/换库/翻面/深浅色翻档/开机恢复这些已有事件上，一次是一回一两百 px 的小 JPEG
 * 解码（边长按格子算，最多夹到 {@code THUMB_MAX_PX}），
 * 且带一个 {@code QUEUED} 闸门把连发并成一次渲染（代价换确定性：见 {@code scopeThumb} 为什么不缓存）。
 * 两种格位<b>共用这一条队列</b>，所以最坏情况是"桌面上横竖各摆一个"时一次事件串行递交两份视图、
 * 解两回图（同一张缩略图，两份布局的格子边长可能不同 → 各解各的尺寸）；只摆一个时另一份在
 * {@code getAppWidgetIds} 那步就返回，成本是零。
 * 范围停在锁屏时每一张要全尺寸解成品图 + {@code setBitmap}，与通知那三颗键、1×1 那格同价，
 * 不是新增的开销类型；翻面本身只多一次小 JPEG 解码。
 * 横版开了 {@code resizeMode}：每次拖动改尺寸会走一次 {@code onAppWidgetOptionsChanged} → 排一次渲染，
 * 拖动过程中连发由同一个闸门并掉，落定才是那一次真正的解码。
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
    // 缩略图那格 = 打开 App（v3.91 之前这一格是选库入口）
    private static final int REQ_OPEN_APP = 11;
    private static final int REQ_PAUSE = 12;
    private static final int REQ_PREV = 13;
    private static final int REQ_NEXT = 14;
    // 另两格：选库、范围翻面
    private static final int REQ_LIB = 15;
    private static final int REQ_SCOPE = 16;

    // ===== 格子边长是算出来的（v3.95）：按桌面申报的槽位宽高，取横竖两向里"放得下"的那个 =====
    // 每种格位自带：向哪个 provider 递交、用哪份布局、横竖各几格、两向各自的固定开销 dp。
    // 竖版：横向 2 格 = 内边距 8 + 外侧留白 6 + 列间距 18 → 32dp；纵向 3 行 = 内边距 8 + 6×3 → 26dp。
    // 横版：横向 3 格 = 内边距 8 + 外侧留白 6 + 两处列间距 36 → 50dp；纵向 2 行 = 内边距 8 + 6×2 → 20dp。
    private static final Spec SPEC_CONSOLE = new Spec("竖", WidgetConsoleProvider.class,
            R.layout.widget_console, 2, 3, 32, 26);
    private static final Spec SPEC_CONSOLE_WIDE = new Spec("横", WidgetConsoleWideProvider.class,
            R.layout.widget_console_wide, 3, 2, 50, 20);
    /** 渲染时依次递交这两份视图；没摆上桌面的那份在 getAppWidgetIds 那步就跳过，连解码都不做。 */
    private static final Spec[] SPECS = {SPEC_CONSOLE, SPEC_CONSOLE_WIDE};

    // 下限 28dp 只兜"槽位薄到算不出可用格子"这种病态情况 —— 正常情况下边长就是算出来的那个，
    // 哪怕它小。<b>这个下限不能抬到 40</b>：夹具 check/WidgetCellSizeTest 跑过，176×133dp 的槽位
    // 算出来是 35dp，夹到 40 就变成卡片 146dp 高、比槽位还高 13dp —— 又回到 v3.79 那种被切。
    // 宁可格子小一点，也不能撑出去。
    // 上限 84dp：v3.97 之前是 64，真机截图量出来桌面给约 300×194dp 的槽位、卡片只画 242×148dp，
    // 两个方向同时空 = 上限在夹（不是算式不够），抬到 84 让横版正好铺满 3×2 那一档槽位。
    // 抬上限不会撑出槽位：边长是 min((槽宽−横开销)/列数, (槽高−纵开销)/行数)，代回去恒 ≤ 槽位。
    // 再往上（96dp 起）就要桌面给到 338×212dp 才吃得满，那已经超出这台机器的 3×2，多出来只会是
    // 透明边，白占地方 —— 所以到 84 收手。
    private static final int CELL_MIN_DP = 28;
    private static final int CELL_MAX_DP = 84;
    // 读不到申报值时的地板（与两份布局里写的 minWidth/minHeight 同一个数）。
    // 44dp 格画出来：竖版是 120×158dp 的卡，横版是 182×108dp 的卡 —— 等于假定"任何桌面至少给这么大"，
    // 这台机器的 2 行怎么也有 133dp，所以兜得住；真要遇到更薄又不报数的桌面，这一档会切，
    // 是已知让位于简单性的取舍
    private static final int CELL_FLOOR_DP = 44;
    // 格子里的图标只占格子一半多一点（v3.94 之前是 52dp 的格里放 28dp 的图 = 0.54），跟着格子缩放。
    // v3.97 起翻面格不再压一行「桌面/锁屏」，所以它不再单列一档比例（原来那个 0.3），六格统一这一个数
    private static final float ICON_RATIO = 0.54f;

    /**
     * 一种格位 = 一份布局 + 一套格子算式 + 一个递交目标。两份 spec 共用同一个 buildViews，
     * 靠的是两份布局的 view id 逐个对齐 —— 新加一格时两份布局要用同一个 id，否则另一份会
     * 在同一行 setSize/setOnClickPendingIntent 上把视图写给不存在的 id（RemoteViews 不报错，
     * 只是那一格静默不动）。
     */
    private static final class Spec {
        final String label;
        final Class<?> provider;
        final int layoutRes;
        final int cols;
        final int rows;
        final int hSpareDp;
        final int vSpareDp;

        Spec(String label, Class<?> provider, int layoutRes, int cols, int rows,
             int hSpareDp, int vSpareDp) {
            this.label = label;
            this.provider = provider;
            this.layoutRes = layoutRes;
            this.cols = cols;
            this.rows = rows;
            this.hSpareDp = hSpareDp;
            this.vSpareDp = vSpareDp;
        }
    }

    // 缩略图解码边长的硬夹。不能直接用 WallpaperStore.getThumb() 的结果 ——
    // 那是 384~768px 正方形（解码出来 0.6~2.3MB），而 RemoteViews 经 Binder 递交、单次事务约 1MB，
    // 超了的表现是小组件静默不更新（不报错）
    private static final int THUMB_MIN_PX = 96;
    // 上限 192 → 256（v3.98）：格子边长上限从 64dp 抬到 84dp 之后，2.75 密度下 84dp = 231px，
    // 再夹在 192 就等于把图放大 1.2 倍显示（糊）。Binder 账：256×256×4 = 262KB，
    // 圆角那一步会瞬时多留一份同尺寸的位图（约 524KB 峰值），仍远在单次事务约 1MB 之下。
    private static final int THUMB_MAX_PX = 256;
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
            // 想配库就去点选库那一格（横竖两版都有这一格）
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

    /** 渲染并递交所有已放置的实例（后台线程执行）。两种格位各递一份，没摆的那份直接跳过。 */
    private static void render(Context ctx) {
        AppWidgetManager manager = AppWidgetManager.getInstance(ctx);
        for (Spec spec : SPECS) {
            ComponentName component = new ComponentName(ctx, spec.provider);
            int[] ids = manager.getAppWidgetIds(component);
            if (ids == null || ids.length == 0) {
                continue;
            }
            // 一次 updateAppWidget(component, ...) 会把该组件的所有实例刷成同一份视图，所以边长只能取一个：
            // 按"报得最小的那个实例"算，宁可小的那个四周多露透明边，也不能大的那个撑出去被切。
            // 注意是<b>同一份组件内</b>比大小：横竖两份组件各自的槽位互不相干，
            // 不能让 2×2 那个实例的槽位去决定 3×2 的格子大小。
            int cellDp = CELL_FLOOR_DP;
            for (int id : ids) {
                cellDp = Math.min(cellDp, cellDpFor(manager, id, spec));
            }
            manager.updateAppWidget(component, buildViews(ctx, spec, cellDp));
        }
    }

    /** 这个实例的槽位放得下多大的正方格（dp）；读不到申报值就回地板值。 */
    private static int cellDpFor(AppWidgetManager manager, int id, Spec spec) {
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
        int cell = Math.min((width - spec.hSpareDp) / spec.cols, (height - spec.vSpareDp) / spec.rows);
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

    /**
     * 【一次性探针，验完删】把桌面报回来的四个原始数与算式结果拼成一段文字，由 App 开屏时弹出来。
     *
     * <p>要定的是：MagicOS 的 {@code OPTION_APPWIDGET_MIN_*} 到底是"<b>当前占位</b>"还是
     * "<b>最小能缩到那一档</b>"。真机反馈是「拉到整屏卡片也不变大、缩到 2×2 卡片会变小」，
     * 而 {@link #slotDp} 取的是 MIN/MAX 里<b>小的</b>那个 —— 只有后者能解释这个组合
     * （当前占位的话拉宽必然变大）。答案决定改哪一处：改取 MAX、改回读当前占位的别的口径、
     * 或者把 {@code resizeMode} 关回 none。
     *
     * <p>删除清单：本方法、{@code Spec#label}（只为这行输出而加）、MainActivity 里调它并弹 Toast 那一段。
     */
    static String diagnose(Context ctx) {
        AppWidgetManager manager = AppWidgetManager.getInstance(ctx);
        StringBuilder sb = new StringBuilder();
        for (Spec spec : SPECS) {
            int[] ids = manager.getAppWidgetIds(new ComponentName(ctx, spec.provider));
            if (ids == null || ids.length == 0) {
                continue;
            }
            for (int id : ids) {
                Bundle o;
                try {
                    o = manager.getAppWidgetOptions(id);
                } catch (Exception ignored) {
                    o = null;
                }
                if (o == null) {
                    sb.append(spec.label).append(" bundle=null\n");
                    continue;
                }
                int minW = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH);
                int maxW = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH);
                int minH = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT);
                int maxH = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT);
                int w = slotDp(o, AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,
                        AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH);
                int h = slotDp(o, AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,
                        AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT);
                sb.append(spec.label).append(" 实例").append(id).append('\n')
                        .append("min ").append(minW).append('x').append(minH)
                        .append("  max ").append(maxW).append('x').append(maxH).append('\n')
                        .append("取 ").append(w).append('x').append(h)
                        .append(" -> 格 ").append(cellDpFor(manager, id, spec)).append("dp\n");
            }
        }
        return sb.toString();
    }

    /** 构建六格视图：深浅色两档外观 + 图标态 + 当前范围的缩略图 + 各格的点按目标 + 算出来的格子边长。
     *  横竖两种格位走同一段代码：布局资源、列数、行数、开销都由 spec 带进来，两份布局的 view id 逐个对齐。 */
    private static RemoteViews buildViews(Context ctx, Spec spec, int cellDp) {
        RemoteViews views = new RemoteViews(ctx.getPackageName(), spec.layoutRes);
        float density = ctx.getResources().getDisplayMetrics().density;
        int cellPx = Math.round(cellDp * density);
        int iconPx = Math.round(cellPx * ICON_RATIO);
        // 边长下发：六格各自定成同一个正方值，格子里的图按同一比例跟着缩放。
        // setMinimumWidth/Height 是 View 的 public setter，ImageView 与 FrameLayout 的 onMeasure
        // 都吃 suggested minimum，所以 wrap_content 的格子就能定住（详见类注释那条）
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
        setSize(views, R.id.widget_scope_ic, iconPx);
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
        // 翻面那一格：整张换"上下两张卡片"drawable —— 当前那一面整块实心、另一面只描边，
        // 实心那块里再抠一个房子/锁的透明洞（洞必须透明不能涂白，原因见那两个文件顶上；
        // 两份矢量的两张卡片外沿完全同位，翻面时图标不会跳）
        views.setImageViewResource(R.id.widget_scope_ic,
                forHome ? R.drawable.ic_scope_card_home : R.drawable.ic_scope_card_lock);
        views.setInt(R.id.widget_scope_ic, "setColorFilter", ink);
        Bitmap thumb = scopeThumb(ctx, forHome, cellPx);
        if (thumb != null) {
            views.setImageViewBitmap(R.id.widget_thumb, thumb);
        } else {
            // 这一面没库 / 库里没图：灰色方块（点它照样进 App，配库去点选库那一格）
            views.setImageViewResource(R.id.widget_thumb,
                    night ? R.drawable.widget_thumb_empty_night : R.drawable.widget_thumb_empty);
        }
        // 缩略图那格 = 打开 App（竖版在左上、横版在第一行中间）。与 1×1 那格
        // "没配库时点按打开应用"同一条 intent（不加 flag，走已验证过的路径）
        views.setOnClickPendingIntent(R.id.widget_cell_thumb, PendingIntent.getActivity(ctx,
                REQ_OPEN_APP, new Intent(ctx, MainActivity.class), piFlags()));
        // 选库那格开选库页：小组件里弹不出列表（RemoteViews 没有下拉、也不认触摸），
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
     * 上限 256px 是硬约束（RemoteViews 经 Binder 递交，单次事务约 1MB，超了不报错、
     * 只是小组件静默不更新）；格子算到 84dp 上限时，2.75 密度下是 231px，落在区间内；
     * 更高密度的机器上会被这里夹回 256px，代价只是图比显示尺寸略糊一点。
     * 源图本身是 384~768px 的方形 JPEG，所以 256 仍是降采样、不是放大重采样。
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
