package com.example.wallswitch;

import android.app.Activity;
import android.app.WallpaperInfo;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.SurfaceHolder;

import com.example.wallswitch.gl.WallpaperRenderer;

import net.rbgrn.android.glwallpaperservice.GLWallpaperService;

import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 动态壁纸引擎（Muzei 的 OpenGL 渲染路径，vendor 其 gl-wallpaper 模块；Canvas 软件画布已废弃）。
 *
 * 为什么需要它：静态方案 WallpaperManager.setBitmap 只保证"系统服务存好了图"，
 * 最终显示依赖 Launcher/渲染层二次加载——荣耀 MagicOS 上 Launcher 不在前台时
 * 偶发加载失败并回落到内置兜底图（"幽灵图"），且无任何回调（见 Switcher 第四道校验）。
 * 引擎模式下系统只提供一块 Surface，解码与绘制全在本进程内闭环：
 * 解码失败就保持旧画面，不存在"系统给的第三张图"，幽灵图从架构上消失。
 *
 * 为什么必须是 OpenGL：v3.37 及之前用 lockCanvas 软件画布，每次切换的过渡动画
 * ≈31 帧全屏 CPU 绘制（约 800MB 内存搬运），真机实测挂上就温、频繁手动切换发热明显；
 * v3.28 曾试 lockHardwareCanvas（Skia GPU 档位）→ MagicOS 上黑屏 + 系统侧持续空转剧热
 * 且不抛任何异常，该 API 已永久拉黑；Muzei（GL，EGL 自管表面 + GPU 逐帧混合）
 * 在同一台设备上怎么切都不热。故 v3.38 起照抄 Muzei 的 GL 路径。
 *
 * 工作方式：
 * - 用户把本 App 设为动态壁纸后，桌面（及未单独设置静态锁屏时的锁屏）由本引擎绘制；
 * - Switcher.next 检测到引擎激活时不再调 setBitmap，改为推进壁纸指针后调
 *   notifyWallpaperChanged()，引擎在后台线程解码、经 queueEvent 上传纹理并由 GL 画帧；
 * - onSurfaceCreated / onVisibilityChanged(true) 也会按当前指针重放上传，
 *   覆盖灭屏亮屏、Surface 重建、进程重建后的恢复（等价 Muzei 的 queuedImageLoader 重放）。
 *
 * 性能模型（对标 Muzei 的"纹理常驻"）：
 * - 解码结果常驻内存缓存（key = 壁纸 id + Surface 尺寸），亮屏/回桌面等触发的重放
 *   直接复用缓存、零磁盘解码；只有壁纸内容或尺寸变化才真正解码一次。
 * - 绘制走 GLES20：一张纹理铺满视口（centerCrop），RENDERMODE_WHEN_DIRTY 下只在
 *   脏时画帧，静止时零渲染；切换过渡（淡入/模糊）由 GPU 逐帧混合，无 CPU 全屏搬运。
 *
 * 性能自记：每次真正解码（≈ 每次壁纸内容变化）后把累计统计重写到
 * 公共 Download/WallSwitch/engine_stats.txt，无需 adb 即可直观核对
 * 「纹理上传次数 ≈ 切换次数」与 GL 帧数是否异常。
 *
 * 已知风险：用户/主题商店若把壁纸换成别的，引擎被停用，Switcher 自动回退到
 * 静态 setBitmap 链路（功能不中断，但幽灵图风险回来）；主界面会提示引擎未激活。
 * GL 初始化/绘制异常无回退开关，全部写 engine_crash.txt 与 engine_stats.txt 取证。
 */
public class WallSwitchService extends GLWallpaperService {

    // 与其它组件共用的设置存储
    public static final String PREFS_NAME = "settings";
    /** 切换动画效果的设置 key：{@link #TRANSITION_FADE} / {@link #TRANSITION_BLUR} / {@link #TRANSITION_OFF} */
    public static final String KEY_TRANSITION = "transition_effect";
    /** 切换动画：交叉淡入（默认） */
    public static final String TRANSITION_FADE = "fade";
    /** 切换动画：旧图渐糊、新图浮现 */
    public static final String TRANSITION_BLUR = "blur";
    /** 切换动画：无，立即切换 */
    public static final String TRANSITION_OFF = "off";

    /** 活着的引擎实例（系统可能同时存在预览引擎与正式引擎，通知时全部刷新）。 */
    private static final List<WallEngine> ENGINES = new CopyOnWriteArrayList<>();

    /**
     * 上一次看到的深浅色档（进程内共享，多个 Engine 也只刷一次）。
     * 用途见 {@link WallEngine#onConfigurationChanged}：深浅色翻档时重发小组件与常驻通知。
     */
    private static int lastNightMode = -1;
    /** 解码 + 纹理上传调度共用的后台线程，避免阻塞系统壁纸回调线程。 */
    private static final ExecutorService DRAW_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "engine-draw");
        thread.setDaemon(true);
        return thread;
    });

    // 性能自记累计值：帧数/上传次数在 GL 线程递增（原子类），其余只在 DRAW_EXECUTOR 单线程读写
    /** GL 帧数（RENDERMODE_WHEN_DIRTY：仅画面变化时计帧，静止时不涨是正常）。 */
    public static final AtomicLong perfFrames = new AtomicLong();
    /** 纹理上传次数（应 ≈ 切换次数 + 灭屏亮屏/回桌面/Surface 重建的恢复次数）。 */
    public static final AtomicLong perfUploads = new AtomicLong();
    private static long perfCacheHits = 0;
    private static long perfDecodes = 0;
    private static long perfDecodeMs = 0;
    private static final SimpleDateFormat PERF_TIME =
            new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault());

    /**
     * 当前桌面是否由本引擎渲染（用户已把本 App 设为动态壁纸）。
     * 按**包名**比对（Muzei 同款做法，见其 WallpaperAnalytics.kt）：比精确组件名更稳 ——
     * 部分 ROM（如 MagicOS）上报的壁纸组件可能带别名/包装，精确比较会误判成"未接管"，
     * 那正是"壁纸是活的、App 却显示未启用"这类假状态的来源。
     */
    public static boolean isActive(Context ctx) {
        WallpaperInfo info = WallpaperManager.getInstance(ctx).getWallpaperInfo();
        return info != null && ctx.getPackageName().equals(info.getPackageName());
    }

    /** 壁纸内容变化（Switcher.next 已推进指针）后调用，通知所有活引擎带过渡动画重绘。 */
    public static void notifyWallpaperChanged() {
        for (WallEngine engine : ENGINES) {
            engine.scheduleDraw(true);
        }
    }

    /** 打开系统动态壁纸选择器并预选本引擎，引导用户激活引擎模式。 */
    public static void openActivator(Activity activity) {
        Intent intent = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
        // 等价 WallpaperManager.EXTRA_CHANGING_LIVE_WALLPAPER（本地类型检查的 android.jar 缺该常量，用字面量）
        intent.putExtra("android.service.wallpaper.extra.LIVE_WALLPAPER_COMPONENT",
                new ComponentName(activity, WallSwitchService.class));
        activity.startActivity(intent);
    }

    /** 读切换动画设置（缺省淡入）。 */
    public static String transitionEffect(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_TRANSITION, TRANSITION_FADE);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // 记下当前深浅色档：之后只有真的翻档才重发小组件与通知（见 WallEngine#onConfigurationChanged）
        lastNightMode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        // 引擎进程任何线程崩溃都落一份堆栈到公共目录：黑屏/发热/反复重启这类"现象级故障"
        // 没有取证就无法定位（用户不用 adb）
        try {
            final Context app = getApplicationContext();
            final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
                recordEngineCrash(app, e);
                if (prev != null) {
                    prev.uncaughtException(t, e);
                }
            });
        } catch (Exception ignored) {
        }
    }

    /**
     * 深浅色翻档时把小组件与常驻通知重发一次。
     *
     * <p>为什么要有这一钩：两档颜色都是我们在自己进程里算好递过去的（见 WidgetConsoleProvider#isNight），
     * 不重发就会一直停在旧档；而"桌面在深浅色切换时会不会自己重画已摆着的小组件"各家不保证。
     * 挂在 Service 上（Engine 没有 onConfigurationChanged，只有 Service 那份 ComponentCallbacks 有）
     * 是因为本服务作为动态壁纸常驻，翻档必然收到这个回调，比等用户打开 App 才纠正要主动。
     *
     * <p>发热账：只在 uiMode 的深浅色位真的变了时才跑，一次是"两回小 JPEG 解码 + 两次递交"，
     * 属于用户动作那一级（和手动切一张同量级），不是持续负载。
     */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        int night = newConfig.uiMode & Configuration.UI_MODE_NIGHT_MASK;
        if (night == lastNightMode) {
            return;
        }
        lastNightMode = night;
        WidgetProvider.updateWidget(this);
        StatusNotifier.update(this);
    }

    @Override
    public Engine onCreateEngine() {
        return new WallEngine();
    }

    /** 引擎：把"当前壁纸"居中裁剪画到 GL 表面（解码缓存常驻，纹理按 key 复用/重放）。 */
    class WallEngine extends GLEngine {

        private WallpaperRenderer renderer;
        /** Surface 是否可用（onSurfaceCreated ~ onSurfaceDestroyed 之间）。 */
        private volatile boolean surfaceReady;
        /** 解码结果缓存：key = 壁纸id#宽x高，命中即免解码。只在 DRAW_EXECUTOR 线程读写。 */
        private Bitmap cachedBitmap;
        private String cachedKey;
        /** 已成功上传到 GPU 的缓存 key；GL 线程（纹理失效重放）与 DRAW_EXECUTOR 读写，故 volatile。 */
        private volatile String uploadedKey;
        /** 引擎自动推进指针却仍读不到文件的连续次数（防打转，成功解析到文件即清零）。 */
        private int autoAdvanceFails;

        @Override
        public void onCreate(SurfaceHolder surfaceHolder) {
            super.onCreate(surfaceHolder);
            ENGINES.add(this);
            // EGL 配置照抄 Muzei：alpha=0（不透明表面）是它不发热的关键，务必勿改；
            // RENDERMODE_WHEN_DIRTY 只在脏时画帧，静止时零渲染
            renderer = new WallpaperRenderer(getApplicationContext(),
                    this::requestRender, this::replayCurrentImage);
            setEGLContextClientVersion(2);
            setEGLConfigChooser(8, 8, 8, 0, 0, 0);
            setRenderer(renderer);
            setRenderMode(RENDERMODE_WHEN_DIRTY);
            requestRender();
        }

        @Override
        public void onDestroy() {
            ENGINES.remove(this);
            if (renderer != null) {
                queueEvent(renderer::release);
            }
            // 缓存释放走引擎线程：主线程直接 recycle 会与正在进行的解码/上传竞态
            DRAW_EXECUTOR.execute(this::releaseCache);
            super.onDestroy();
        }

        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder);
            surfaceReady = true;
            scheduleDraw(false);
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            surfaceReady = true;
            scheduleDraw(false);
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            surfaceReady = false;
            super.onSurfaceDestroyed(holder);
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);
            // 等价 Muzei 的 ReloadWhenVisible：亮屏回到可见时按最新指针重放，
            // 灭屏期间错过的切换在这一刻补上；有缓存时这是一次零解码的纹理重放
            if (renderer != null) {
                renderer.setVisible(visible);
            }
            if (visible) {
                scheduleDraw(false);
            }
        }

        /** GL 线程回调：纹理全部失效后按当前 key 重放上传（等价 Muzei 的 queuedImageLoader 重放）。 */
        private void replayCurrentImage() {
            uploadedKey = null;
            scheduleDraw(false);
        }

        /**
         * 后台线程解码并上传纹理；异常吞掉前先取证，等下一次重放机会。
         * afterSwitch = 本次由「壁纸内容切换」触发，允许播过渡动画；
         * 亮屏/回桌面等恢复场景传 false，只做快速重放。
         */
        void scheduleDraw(boolean afterSwitch) {
            DRAW_EXECUTOR.execute(() -> drawCurrentSafely(afterSwitch));
        }

        private void drawCurrentSafely(boolean afterSwitch) {
            try {
                drawCurrent(afterSwitch);
            } catch (Throwable t) {
                recordEngineCrash(WallSwitchService.this, t);
            }
        }

        private void drawCurrent(boolean afterSwitch) {
            if (!surfaceReady) {
                return;
            }
            SurfaceHolder holder = getSurfaceHolder();
            Rect frame = holder.getSurfaceFrame();
            int w = frame.width();
            int h = frame.height();
            if (w <= 0 || h <= 0) {
                return;
            }

            Context ctx = WallSwitchService.this;
            LibraryStore.Library lib = LibraryStore.slotLib(ctx, true);
            String key = null;
            Bitmap fresh = null;
            if (lib != null) {
                String id = Switcher.getCurrent(ctx, lib.id, true);
                File file = currentFile(ctx, lib.id);
                boolean fromFallback = false;
                if (id == null || file == null) {
                    // 指针空/文件丢。两条处置，职责分开：
                    // ① 正常推进（含接管校验、历史链、随机池）—— 有副作用（写指针/刷通知），
                    //    连续失败 3 次就停手，避免「推进→仍缺失→通知→再推进」空转（v3.28 教训）；
                    // ② 推进被拦（v3.31 真机：接管开关关着，next 直接拒绝）或失败时，
                    //    直接显示库里第一张 —— 不动指针、不算切换、不刷通知，纯「有图先亮」：
                    //    引擎的任务就是显示壁纸，黑屏本身就是故障，不该被开关状态陪葬
                    List<WallpaperStore.Item> items = WallpaperStore.loadByLib(ctx, lib.id);
                    if (!items.isEmpty()) {
                        if (autoAdvanceFails < 3) {
                            // 引擎自愈也真的换了一张上图：按「换了就顺延」的一刀切口径重起算这一轮
                            // （传 null 不写「手动」正文行，它不是用户动作）
                            if (Switcher.next(ctx, lib.id, true)) {
                                TimerScheduler.restartScope(ctx, true, null);
                            }
                            id = Switcher.getCurrent(ctx, lib.id, true);
                            file = currentFile(ctx, lib.id);
                            if (id == null || file == null) {
                                autoAdvanceFails++;
                            }
                        }
                        if (id == null || file == null) {
                            WallpaperStore.Item first = items.get(0);
                            File f = WallpaperStore.getFullFile(ctx, first.id);
                            if (f != null && f.exists()) {
                                id = first.id;
                                file = f;
                                fromFallback = true;
                            }
                        }
                    }
                }
                if (id != null && file != null) {
                    // 兜底命中不算「推进成功」：不清零，免得每帧都再试一次带副作用的推进
                    if (!fromFallback) {
                        autoAdvanceFails = 0;
                    }
                    // 缓存 key：壁纸内容 + 目标尺寸（尺寸变化即重新解码）
                    key = id + "#" + w + "x" + h;
                    if (key.equals(cachedKey) && cachedBitmap != null && !cachedBitmap.isRecycled()) {
                        perfCacheHits++;
                        // 正常重放也按 5 分钟限频落盘：GL 帧数/上传次数随时间怎么涨，
                        // 是判断显示是否异常的依据
                        flushPerfStatsRateLimited(ctx, NORMAL_FLUSH_INTERVAL_MS);
                        if (!key.equals(uploadedKey)) {
                            // 纹理不在了（Surface 重建/恢复可见后的重放）：从缓存直接上传，零解码
                            uploadToRenderer(null, false, key);
                        } else {
                            // 内容与纹理都已就位：RENDERMODE_WHEN_DIRTY 下只需标脏，零成本
                            requestRender();
                        }
                        return;
                    }
                    // 按 Surface 实际尺寸解码，但不超过统一的壁纸分辨率上限（防 OOM）
                    int maxDim = Math.min(Math.max(w, h), WallpaperStore.maxWallpaperDim(ctx));
                    long t0 = SystemClock.elapsedRealtime();
                    fresh = WallpaperStore.decodeBounded(file, maxDim);
                    long decodeMs = SystemClock.elapsedRealtime() - t0;
                    perfDecodes++;
                    perfDecodeMs += decodeMs;
                }
            }

            if (fresh == null) {
                // 空库/解码失败：保持纯底色而非系统兜底图（缓存保留，等库恢复后可复用）。
                // 故障态也要 30 秒限频落盘计数与诊断 —— v3.28 的教训：只在解码时落盘，
                // 引擎一旦坏掉反而一个数字都看不到
                lastFaultDiag = faultDiag(ctx, lib);
                drawFaultColor();
                flushPerfStatsRateLimited(ctx, FAULT_FLUSH_INTERVAL_MS);
                return;
            }
            lastFaultDiag = null;

            // 准备过渡素材：旧画面在过渡期间由 currentTex（GPU）承载，CPU 侧只需模糊版
            String effect = transitionEffect(ctx);
            boolean animate = afterSwitch && !TRANSITION_OFF.equals(effect)
                    && cachedBitmap != null && !cachedBitmap.isRecycled();
            Bitmap fromBlur = null;
            if (animate && TRANSITION_BLUR.equals(effect)) {
                fromBlur = makeBlurred(cachedBitmap);
            }
            Bitmap old = cachedBitmap;
            cachedBitmap = fresh;
            cachedKey = key;
            if (old != null && !old.isRecycled()) {
                old.recycle();
            }
            uploadToRenderer(fromBlur, animate, key);
            // 正常解码后落盘：纹理上传次数 ≈ 切换次数是 GL 引擎健康的核心判据
            flushPerfStats(ctx);
        }

        /** 经 queueEvent 在 GL 线程上传纹理（过渡动画由渲染器驱动，引擎不再 sleep 逐帧）。 */
        private void uploadToRenderer(Bitmap blurred, boolean animate, String key) {
            Bitmap bmp = cachedBitmap;
            queueEvent(() -> {
                boolean shown = renderer.setImage(bmp, blurred, animate);
                // blurred 是一次性素材：上传完成（或因不可见被跳过）后即可回收
                if (blurred != null && !blurred.isRecycled()) {
                    blurred.recycle();
                }
                uploadedKey = shown ? key : null;
            });
        }

        /** 故障画面：清成纯底色（与 App 背景一致），丢弃旧纹理防止"过期图"残留。 */
        private void drawFaultColor() {
            queueEvent(() -> {
                renderer.clearToBackground();
                uploadedKey = null;
            });
        }

        /** 当前指针指向的壁纸文件；无指针或文件已丢失返回 null。 */
        private File currentFile(Context ctx, String libId) {
            String id = Switcher.getCurrent(ctx, libId, true);
            if (id == null) {
                return null;
            }
            File file = WallpaperStore.getFullFile(ctx, id);
            return file != null && file.exists() ? file : null;
        }

        /** 释放缓存（引擎销毁时调用；Surface 销毁不释放，亮屏回来还能用）。 */
        private void releaseCache() {
            if (cachedBitmap != null) {
                cachedBitmap.recycle();
                cachedBitmap = null;
            }
            cachedKey = null;
        }
    }

    /**
     * 一次性预模糊：把旧图缩小到约 1/8（~300px 级），纯 Java 盒式模糊三轮，
     * 结果作为模糊纹理上传，GPU 放大回屏即得柔焦效果。总耗时毫秒级，避免逐帧高斯。
     */
    private static Bitmap makeBlurred(Bitmap src) {
        try {
            int bw = Math.max(1, src.getWidth() / 8);
            int bh = Math.max(1, src.getHeight() / 8);
            Bitmap small = Bitmap.createScaledBitmap(src, bw, bh, true);
            if (small == src) {
                // 缩放结果是同一实例（尺寸恰好相等）时拷贝一份，避免模糊污染缓存原图
                small = src.copy(src.getConfig(), false);
                if (small == null) {
                    return null;
                }
            }
            boxBlur(small, 4, 3);
            return small;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 盒式模糊：水平 + 垂直两个方向的滑动窗口均值，重复 passes 轮近似高斯。 */
    private static void boxBlur(Bitmap bmp, int radius, int passes) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int[] px = new int[w * h];
        int[] tmp = new int[px.length];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        for (int p = 0; p < passes; p++) {
            blurPass(px, tmp, w, h, radius, true);
            blurPass(tmp, px, w, h, radius, false);
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h);
    }

    /** 一趟盒式模糊：horizontal=true 沿 x 方向均值，结果写入 out。 */
    private static void blurPass(int[] in, int[] out, int w, int h, int radius, boolean horizontal) {
        int span = radius * 2 + 1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int sumA = 0, sumR = 0, sumG = 0, sumB = 0;
                for (int k = -radius; k <= radius; k++) {
                    int xx = horizontal ? Math.min(w - 1, Math.max(0, x + k)) : x;
                    int yy = horizontal ? y : Math.min(h - 1, Math.max(0, y + k));
                    int c = in[yy * w + xx];
                    sumA += (c >>> 24) & 0xFF;
                    sumR += (c >>> 16) & 0xFF;
                    sumG += (c >>> 8) & 0xFF;
                    sumB += c & 0xFF;
                }
                out[y * w + x] = (sumA / span) << 24 | (sumR / span) << 16
                        | (sumG / span) << 8 | (sumB / span);
            }
        }
    }

    /** 引擎线程/进程崩溃取证：堆栈写公共目录，5 秒限频防崩溃循环刷爆存储。 */
    private static synchronized void recordEngineCrash(Context ctx, Throwable t) {
        try {
            long now = SystemClock.elapsedRealtime();
            if (now - lastCrashWriteMs < 5_000) {
                return;
            }
            lastCrashWriteMs = now;
            writePublicText(ctx, "engine_crash.txt",
                    PERF_TIME.format(new Date()) + "\n"
                            + android.util.Log.getStackTraceString(t) + "\n");
        } catch (Exception ignored) {
        }
    }

    private static long lastCrashWriteMs;

    /**
     * 渲染器 GL 线程异常（EGL 初始化失败/着色器编译失败/绘制抛错）：没有 Canvas 回退路径，
     * 失败必须可见——崩溃取证（5 秒限频）+ 故障诊断行 + 30 秒限频落盘。
     */
    public static void onRendererFailure(Context ctx, Throwable t) {
        lastFaultDiag = "GL 渲染异常: " + t;
        recordEngineCrash(ctx, t);
        flushPerfStatsRateLimited(ctx, FAULT_FLUSH_INTERVAL_MS);
    }

    /** 故障态限频落盘间隔（30 秒）：故障时计数几乎不涨，也要留下现场。 */
    private static final long FAULT_FLUSH_INTERVAL_MS = 30_000L;
    /** 正常态限频落盘间隔（5 分钟）：够密到能算出重绘频率，又不给存储添负担。 */
    private static final long NORMAL_FLUSH_INTERVAL_MS = 5 * 60_000L;

    /** 限频落盘：距上次写文件不足 intervalMs 就跳过（MediaStore 重写有成本）。 */
    private static synchronized void flushPerfStatsRateLimited(Context ctx, long intervalMs) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastStateFlushMs < intervalMs) {
            return;
        }
        lastStateFlushMs = now;
        flushPerfStats(ctx);
    }

    private static long lastStateFlushMs;
    /** 最近一次「读不到壁纸」的具体原因（绘制现场捕获，随性能自记落盘）。 */
    private static volatile String lastFaultDiag;

    /** 「引擎读不到当前壁纸」的具体原因：无启用库 / 指针空 / 指针指向的文件丢失 / 库空。 */
    private static String faultDiag(Context ctx, LibraryStore.Library lib) {
        try {
            if (lib == null) {
                int total = LibraryStore.load(ctx).size();
                return "无启用的桌面库（共 " + total + " 个库，均未启用或不覆盖桌面）";
            }
            String current = Switcher.getCurrent(ctx, lib.id, true);
            if (current == null) {
                return "库「" + lib.name + "」无当前指针（条目 "
                        + WallpaperStore.loadByLib(ctx, lib.id).size() + " 张）";
            }
            File file = WallpaperStore.getFullFile(ctx, current);
            return "库「" + lib.name + "」指针指向 " + current + "，文件 "
                    + (file != null && file.exists() ? "存在" : "丢失") + "（条目 "
                    + WallpaperStore.loadByLib(ctx, lib.id).size() + " 张）";
        } catch (Exception e) {
            return "诊断失败: " + e;
        }
    }

    /** 解码（≈壁纸内容变化）后重写性能自记文件：纹理上传次数应≈切换次数。 */
    private static void flushPerfStats(Context ctx) {
        try {
            String content = "WallSwitch 引擎性能自记（正常=每次壁纸内容变化后重写；故障态=每 30 秒重写）\n"
                    + "更新时间: " + PERF_TIME.format(new Date()) + "\n"
                    + "累计GL帧: " + perfFrames.get() + " 帧"
                    + "（RENDERMODE_WHEN_DIRTY：仅画面变化时计帧，静止时不涨是正常）\n"
                    + "纹理上传: " + perfUploads.get() + " 次"
                    + "（应≈切换次数 + 灭屏亮屏/回桌面/Surface 重建的恢复次数）\n"
                    + "缓存命中(零解码): " + perfCacheHits + " 次\n"
                    + "真实解码: " + perfDecodes + " 次, 共 " + perfDecodeMs + " ms\n"
                    + "故障诊断: " + (lastFaultDiag != null ? lastFaultDiag : "无（当前正常显示中）") + "\n"
                    + (lastFaultDiag != null
                    ? "判读: 引擎读不到当前壁纸或 GL 渲染异常（异常态）\n"
                    : "判读: 正常（解码次数应≈切换次数，纹理上传应略多于解码）\n");
            writePublicText(ctx, "engine_stats.txt", content);
        } catch (Exception ignored) {
        }
    }

    /** 把文本写到公共 Download/WallSwitch（低版本写内部存储），尽力而为。 */
    private static void writePublicText(Context ctx, String name, String content) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                String dir = "Download/WallSwitch";
                ContentResolver cr = ctx.getContentResolver();
                Uri existing = TakeoverManager.findEntry(ctx,
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, name, dir);
                if (existing != null) {
                    cr.delete(existing, null, null);
                }
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, dir);
                Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) {
                    return;
                }
                try (OutputStream out = cr.openOutputStream(uri)) {
                    if (out != null) {
                        out.write(content.getBytes(StandardCharsets.UTF_8));
                    }
                }
            } else {
                File dir = new File(ctx.getFilesDir(), "WallSwitch");
                if (dir.exists() || dir.mkdirs()) {
                    try (OutputStream out = new java.io.FileOutputStream(new File(dir, name))) {
                        out.write(content.getBytes(StandardCharsets.UTF_8));
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }
}
