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
 * 桌面控制台小组件：2 列 × 3 行、共五格 —— 左上 = 当前壁纸缩略图（点按开选库页）、
 * 右上 = 暂停/继续（只换图标）、左下 = 上一张、右下 = 下一张、第三行一整条 = 进入 App。
 *
 * <h3>与 1×1 那格的分工</h3>
 * 1×1 点一下 = 桌面与锁屏各自切一张；本控制台<b>只作用桌面这一面</b>（锁屏仍回 App 里设）。
 * 动作语义与常驻通知的上一张/下一张同一套（{@link Switcher#prev}/{@link Switcher#next}
 * + 切完 {@code restartScope}），守卫也在 Switcher 里收口，这里不另立规矩。
 *
 * <h3>为什么"进入 App"要占一整格</h3>
 * RemoteViews <b>没有长按 API</b>（android-34 的 android.jar 里只有 setOnClickPendingIntent /
 * setPendingIntentTemplate / setOnClickFillInIntent，长按只有集合控件那套
 * setOnItemLongClickPendingIntent，得配 RemoteViewsService 的列表）。所以"长按=开 App"这条路不成立，
 * 只能给一个实位的格子。
 *
 * <h3>为什么渲染要挪到后台线程</h3>
 * {@code onUpdate}/{@code onReceive} 跑在广播主线程上（Receiver 有 10 秒上限），而缩略图要读文件解码。
 * 所以刷新统一走 {@link #scheduleUpdate}：单线程队列里解码 + 递交（{@code updateAppWidget} 线程安全），
 * 并带一个 {@code queued} 闸门把连发的事件并成一次渲染。1×1 那格不含位图，仍在原调用点同步刷。
 *
 * <h3>发热账</h3>
 * {@code updatePeriodMillis=0}：不接 1×1 那个"30 分钟刷一次"的补切时机，免得同一刻补切跑两遍；
 * 不放 Chronometer（走秒会每秒驱动桌面重绘，真机实测是发热来源之一）；
 * 解码只发生在切换/暂停/换库/放置/深浅色翻档/开机恢复这些已有事件上，一次是一回 192px 小 JPEG 解码，
 * 且带一个 {@code QUEUED} 闸门把连发并成一次渲染（代价换确定性：见 {@code homeThumb} 为什么不缓存）。
 */
public class WidgetConsoleProvider extends AppWidgetProvider {

    /** 下一张（桌面范围）。 */
    public static final String ACTION_NEXT = "com.example.wallswitch.CONSOLE_NEXT";
    /** 上一张（桌面范围）。 */
    public static final String ACTION_PREV = "com.example.wallswitch.CONSOLE_PREV";
    /** 暂停 / 继续桌面的自动切换。 */
    public static final String ACTION_PAUSE = "com.example.wallswitch.CONSOLE_PAUSE";

    // PendingIntent requestCode：五格各占一个。共号会被 FLAG_UPDATE_CURRENT 合并 ——
    // 后建的那条把前一条的 Intent 覆盖掉，五格按下去变成同一个动作
    private static final int REQ_LIB = 11;
    private static final int REQ_PAUSE = 12;
    private static final int REQ_PREV = 13;
    private static final int REQ_NEXT = 14;
    // 第三行那条「进入 App」
    private static final int REQ_OPEN_APP = 15;

    // 缩略图边长：与布局里写死的格子同宽（52dp，见 widget_console.xml 顶部：62dp 那版在真机上
    // 被 2 行高的槽位切掉了底部那条，整卡缩到 130×170dp）。再硬夹上限。
    // 不能直接用 WallpaperStore.getThumb() 的结果 —— 那是 384~768px 正方形（解码出来 0.6~2.3MB），
    // 而 RemoteViews 经 Binder 递交、单次事务约 1MB，超了的表现是小组件静默不更新（不报错）
    private static final int THUMB_CELL_DP = 52;
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

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (ACTION_NEXT.equals(action) || ACTION_PREV.equals(action) || ACTION_PAUSE.equals(action)) {
            // 切换含大图解码与引擎标脏，不能放广播主线程：仿 1×1 那格用 goAsync 起后台线程
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

    /** 点按动作（后台线程执行）：只作用桌面范围，语义与常驻通知按钮 / 1×1 那格一致。 */
    private static void handleAction(Context app, String action) {
        if (ACTION_PAUSE.equals(action)) {
            // 暂停/继续整面：撤任务或重新起算、日志、刷小组件与常驻通知都在 setPaused 里收口
            TimerScheduler.setPaused(app, true, !LibraryStore.slotPaused(app, true));
            return;
        }
        LibraryStore.Library lib = LibraryStore.slotLib(app, true);
        if (lib == null) {
            // 桌面范围没库：上一张/下一张没有可切的对象。空着不提示是刻意的 ——
            // 想配库就去点缩略图那一格（那里是选库入口）
            return;
        }
        boolean ok = ACTION_NEXT.equals(action)
                ? Switcher.next(app, lib.id, true)
                : Switcher.prev(app, lib.id, true);
        if (ok) {
            // 手动切了一张 = 这一轮从此刻重新起算（与卡片双击、通知按钮、小组件点按同一口径）
            TimerScheduler.restartScope(app, true);
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
        manager.updateAppWidget(component, buildViews(ctx));
    }

    /** 构建五格视图：两档外观 + 图标态 + 缩略图 + 各格的点按目标。 */
    private static RemoteViews buildViews(Context ctx) {
        RemoteViews views = new RemoteViews(ctx.getPackageName(), R.layout.widget_console);
        boolean night = isNight(ctx);
        // 底色按档显式挑，不靠 values-night 自动翻（原因见 colors.xml 那段）
        int cardBg = night ? R.drawable.widget_bg_night : R.drawable.widget_bg;
        int cellBg = night ? R.drawable.widget_cell_bg_night : R.drawable.widget_cell_bg;
        views.setInt(R.id.widget_card, "setBackgroundResource", cardBg);
        views.setInt(R.id.widget_cell_lib, "setBackgroundResource", cellBg);
        views.setInt(R.id.widget_cell_pause, "setBackgroundResource", cellBg);
        views.setInt(R.id.widget_cell_prev, "setBackgroundResource", cellBg);
        views.setInt(R.id.widget_cell_next, "setBackgroundResource", cellBg);
        views.setInt(R.id.widget_cell_open, "setBackgroundResource", cellBg);
        views.setImageViewResource(R.id.widget_pause,
                LibraryStore.slotPaused(ctx, true) ? R.drawable.ic_play : R.drawable.ic_pause);
        // 图标本体是黑色 vector，颜色用 setColorFilter 现挑；文字同理走 setTextColor。
        // 刻意不在布局里写 android:tint：两者都作用在同一个 Drawable 上、互相覆盖，行为不透明
        //（常驻通知那三个图标也是这么处理的）
        int ink = ctx.getColor(night ? R.color.widget_ink_night : R.color.widget_ink);
        views.setInt(R.id.widget_pause, "setColorFilter", ink);
        views.setInt(R.id.widget_prev, "setColorFilter", ink);
        views.setInt(R.id.widget_next, "setColorFilter", ink);
        views.setInt(R.id.widget_open_ic, "setColorFilter", ink);
        views.setTextColor(R.id.widget_open_label, ink);
        Bitmap thumb = homeThumb(ctx);
        if (thumb != null) {
            views.setImageViewBitmap(R.id.widget_thumb, thumb);
        } else {
            // 没库 / 库里没图：灰色方块（点它照样能去选库）
            views.setImageViewResource(R.id.widget_thumb,
                    night ? R.drawable.widget_thumb_empty_night : R.drawable.widget_thumb_empty);
        }
        // 缩略图那格开选库页：小组件里弹不出列表（RemoteViews 没有下拉、也不认触摸），
        // 只能借一个只弹窗、没有界面的 Activity —— 见 LibPickerActivity
        Intent picker = new Intent(ctx, LibPickerActivity.class);
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
        // 第三行：进 App。与 1×1 那格"没配库时点按打开应用"同一条 intent（不加 flag，走已验证过的路径）
        views.setOnClickPendingIntent(R.id.widget_cell_open, PendingIntent.getActivity(ctx,
                REQ_OPEN_APP, new Intent(ctx, MainActivity.class), piFlags()));
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
     * 屏上该显示哪张：桌面槽当前指针；指针还没落地（新占槽的库第一次被选）就退回库里第一张。
     * <b>纯读</b> —— 引擎自己那套"推进指针自愈"（WallSwitchService.drawCurrent）是有副作用的，
     * 小组件渲染不能替用户切一张。
     */
    private static String homeThumbId(Context ctx) {
        LibraryStore.Library lib = LibraryStore.slotLib(ctx, true);
        if (lib == null) {
            return null;
        }
        String current = Switcher.getCurrent(ctx, lib.id, true);
        if (current != null) {
            return current;
        }
        List<WallpaperStore.Item> items = WallpaperStore.loadByLib(ctx, lib.id);
        return items.isEmpty() ? null : items.get(0).id;
    }

    /**
     * 取一格大小的圆角缩略图；拿不到返回 null，由调用方显示空态灰块。
     * <b>刻意不做缓存</b>：按壁纸 id 缓存会在「覆盖当前这张」后停在旧图上
     * （id 没变、文件变了，缓存永远命中）。一次 192px 的小 JPEG 解码是几毫秒且在后台线程，
     * 而带 QUEUED 闸门的渲染本来就把连发事件并成一次 —— 省这一点不值得换一个错画面。
     */
    private static Bitmap homeThumb(Context ctx) {
        String id = homeThumbId(ctx);
        if (id == null) {
            return null;
        }
        Bitmap src = WallpaperStore.getWidgetThumb(ctx, id, thumbSide(ctx));
        return src == null ? null : roundCorners(ctx, src);
    }

    /** 一格需要多少像素：按密度折算，再夹进 Binder 安全的区间。 */
    private static int thumbSide(Context ctx) {
        float density = ctx.getResources().getDisplayMetrics().density;
        int side = Math.round(THUMB_CELL_DP * density);
        return Math.max(THUMB_MIN_PX, Math.min(side, THUMB_MAX_PX));
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
