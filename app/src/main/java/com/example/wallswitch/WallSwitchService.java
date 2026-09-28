package com.example.wallswitch;

import android.app.Activity;
import android.app.WallpaperInfo;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.service.wallpaper.WallpaperService;
import android.view.SurfaceHolder;

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

/**
 * 动态壁纸引擎（借鉴 Muzei 的直接渲染方案；只做静态图，用 Canvas 而非 OpenGL）。
 *
 * 为什么需要它：静态方案 WallpaperManager.setBitmap 只保证"系统服务存好了图"，
 * 最终显示依赖 Launcher/渲染层二次加载——荣耀 MagicOS 上 Launcher 不在前台时
 * 偶发加载失败并回落到内置兜底图（"幽灵图"），且无任何回调（见 Switcher 第四道校验）。
 * 引擎模式下系统只提供一块 Surface，解码与绘制全在本进程内闭环：
 * 解码失败就保持旧画面，不存在"系统给的第三张图"，幽灵图从架构上消失。
 *
 * 工作方式：
 * - 用户把本 App 设为动态壁纸后，桌面（及未单独设置静态锁屏时的锁屏）由本引擎绘制；
 * - Switcher.next 检测到引擎激活时不再调 setBitmap，改为推进壁纸指针后调
 *   notifyWallpaperChanged()，引擎在后台线程解码并直接画上 Surface；
 * - onSurfaceCreated / onVisibilityChanged(true) 也会按当前指针重绘，
 *   覆盖灭屏亮屏、Surface 重建、进程重建后的恢复（等价 Muzei 的 queuedImageLoader 重放）。
 *
 * 性能模型（对标 Muzei 的"纹理常驻"）：
 * - 解码结果常驻内存缓存（key = 壁纸 id + Surface 尺寸），亮屏/回桌面等触发的重绘
 *   直接复用缓存、零磁盘解码；只有壁纸内容或尺寸变化才真正解码一次。
 * - 绘制走 lockHardwareCanvas 硬件画布，同一张图重复绘制由 GPU 纹理缓存承接。
 * - 切换壁纸时的过渡动画（设置项：淡入/模糊过渡/无）只在切换的瞬间跑几百毫秒，
 *   模糊过渡的模糊版是切换时一次性预生成的（缩小 + 盒式模糊），逐帧只做透明度叠加。
 *
 * 性能自记：每次真正解码（≈ 每次壁纸内容变化）后把累计统计重写到
 * 公共 Download/WallSwitch/engine_stats.txt，无需 adb 即可直观核对
 * 「缓存命中远多于解码」是否成立。
 *
 * 已知风险：用户/主题商店若把壁纸换成别的，引擎被停用，Switcher 自动回退到
 * 静态 setBitmap 链路（功能不中断，但幽灵图风险回来）；主界面会提示引擎未激活。
 */
public class WallSwitchService extends WallpaperService {

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
    /** 解码 + 绘制共用的后台线程，避免阻塞系统壁纸回调线程；过渡动画的逐帧驱动也在这条线程上。 */
    private static final ExecutorService DRAW_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "engine-draw");
        thread.setDaemon(true);
        return thread;
    });

    // 性能自记累计值：只在 DRAW_EXECUTOR 单线程上读写，无并发问题
    private static long perfDraws = 0;
    private static long perfCacheHits = 0;
    private static long perfDecodes = 0;
    private static long perfDecodeMs = 0;
    private static final SimpleDateFormat PERF_TIME =
            new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault());

    /** 当前桌面是否由本引擎渲染（用户已把本 App 设为动态壁纸）。 */
    public static boolean isActive(Context ctx) {
        WallpaperInfo info = WallpaperManager.getInstance(ctx).getWallpaperInfo();
        return info != null
                && info.getComponent().equals(new ComponentName(ctx, WallSwitchService.class));
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
    public Engine onCreateEngine() {
        return new WallEngine();
    }

    /** 引擎：把"当前壁纸"居中裁剪绘制到系统给的 Surface 上（解码结果常驻缓存）。 */
    class WallEngine extends Engine {

        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        private final Paint alphaPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        /** Surface 是否可用（onSurfaceCreated ~ onSurfaceDestroyed 之间）。 */
        private volatile boolean surfaceReady;
        /** 过渡动画被打断（Surface 销毁/引擎销毁）时置位，逐帧循环每帧检查。 */
        private volatile boolean transitionCancel;
        /** 解码结果缓存：key = 壁纸id#宽x高，命中即免解码。只在 DRAW_EXECUTOR 线程读写。 */
        private Bitmap cachedBitmap;
        private String cachedKey;

        @Override
        public void onCreate(SurfaceHolder surfaceHolder) {
            super.onCreate(surfaceHolder);
            ENGINES.add(this);
        }

        @Override
        public void onDestroy() {
            ENGINES.remove(this);
            transitionCancel = true;
            releaseCache();
            super.onDestroy();
        }

        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder);
            surfaceReady = true;
            transitionCancel = false;
            scheduleDraw(false);
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            surfaceReady = true;
            transitionCancel = false;
            scheduleDraw(false);
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            surfaceReady = false;
            transitionCancel = true;
            super.onSurfaceDestroyed(holder);
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);
            // 等价 Muzei 的 ReloadWhenVisible：亮屏回到可见时按最新指针重绘一次，
            // 灭屏期间错过的切换在这一刻补上；有缓存时这只是一次零解码的快速重绘
            if (visible) {
                transitionCancel = false;
                scheduleDraw(false);
            }
        }

        /**
         * 后台线程解码并绘制；Surface 竞态销毁等异常吞掉，等下一次重绘机会。
         * afterSwitch = 本次重绘由「壁纸内容切换」触发，允许播过渡动画；
         * 亮屏/回桌面等恢复场景传 false，只做快速重绘。
         */
        void scheduleDraw(boolean afterSwitch) {
            DRAW_EXECUTOR.execute(() -> drawCurrentSafely(afterSwitch));
        }

        private void drawCurrentSafely(boolean afterSwitch) {
            try {
                drawCurrent(afterSwitch);
            } catch (Throwable ignored) {
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
            LibraryStore.Library lib = LibraryStore.enabledLibForScope(ctx, true);
            String key = null;
            Bitmap fresh = null;
            if (lib != null) {
                String id = Switcher.getCurrent(ctx, lib.id, true);
                File file = currentFile(ctx, lib.id);
                if (file == null && !WallpaperStore.loadByLib(ctx, lib.id).isEmpty()) {
                    // 引擎首次接管：库里还没有"当前"指针，推进一张
                    Switcher.next(ctx, lib.id, true);
                    id = Switcher.getCurrent(ctx, lib.id, true);
                    file = currentFile(ctx, lib.id);
                }
                if (id != null && file != null) {
                    // 缓存 key：壁纸内容 + 目标尺寸（尺寸变化即重新解码）
                    key = id + "#" + w + "x" + h;
                    if (key.equals(cachedKey) && cachedBitmap != null && !cachedBitmap.isRecycled()) {
                        // 快速路径：内容与尺寸都没变，零解码直接重绘（GPU 走纹理缓存）
                        perfCacheHits++;
                        perfDraws++;
                        drawBitmap(holder, w, h, cachedBitmap, 255);
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
                // 空库/解码失败：保持纯底色而非系统兜底图（缓存保留，等库恢复后可复用）
                perfDraws++;
                drawColor(holder);
                return;
            }

            // 准备过渡素材：旧图与旧图模糊版（模糊效果用，切换时一次性预生成）
            String effect = transitionEffect(ctx);
            boolean animate = afterSwitch && !TRANSITION_OFF.equals(effect)
                    && cachedBitmap != null && !cachedBitmap.isRecycled();
            Bitmap from = null;
            Bitmap fromBlur = null;
            if (animate) {
                from = cachedBitmap;
                if (TRANSITION_BLUR.equals(effect)) {
                    fromBlur = makeBlurred(from);
                }
            } else if (cachedBitmap != null) {
                cachedBitmap.recycle();
            }
            cachedBitmap = fresh;
            cachedKey = key;
            perfDraws++;
            flushPerfStats(ctx);

            if (from != null) {
                runTransition(holder, w, h, from, fromBlur, effect);
            } else {
                drawBitmap(holder, w, h, fresh, 255);
            }
        }

        /**
         * 过渡动画逐帧驱动（在 DRAW_EXECUTOR 线程上 sleep 循环，约 60fps）。
         * 淡入：旧图打底、新图透明度爬升；模糊过渡：先旧图清晰→模糊（前 40%），
         * 再模糊旧图上淡入新图（后 60%）。每帧只做透明度叠加，无逐帧滤镜计算。
         * 期间排队的其它重绘任务会在循环结束后自然执行（快速路径）。
         */
        private void runTransition(SurfaceHolder holder, int w, int h,
                Bitmap from, Bitmap fromBlur, String effect) {
            boolean blur = TRANSITION_BLUR.equals(effect) && fromBlur != null;
            long duration = blur ? 700L : 500L;
            long start = SystemClock.elapsedRealtime();
            try {
                while (true) {
                    float t = (SystemClock.elapsedRealtime() - start) / (float) duration;
                    if (t >= 1f || !surfaceReady || transitionCancel) {
                        break;
                    }
                    Canvas canvas = null;
                    try {
                        canvas = holder.lockHardwareCanvas();
                        if (canvas != null) {
                            canvas.drawColor(0xFF1A1A1A);
                            Bitmap to = cachedBitmap;
                            if (blur) {
                                if (t < 0.4f) {
                                    drawBitmap(canvas, w, h, from, 255);
                                    drawBitmap(canvas, w, h, fromBlur, (int) (255 * (t / 0.4f)));
                                } else {
                                    drawBitmap(canvas, w, h, fromBlur, 255);
                                    drawBitmap(canvas, w, h, to, (int) (255 * ((t - 0.4f) / 0.6f)));
                                }
                            } else {
                                drawBitmap(canvas, w, h, from, 255);
                                drawBitmap(canvas, w, h, to, (int) (255 * t));
                            }
                        }
                    } finally {
                        if (canvas != null) {
                            holder.unlockCanvasAndPost(canvas);
                        }
                    }
                    SystemClock.sleep(16);
                }
            } finally {
                from.recycle();
                if (fromBlur != null) {
                    fromBlur.recycle();
                }
                if (surfaceReady && !transitionCancel && cachedBitmap != null) {
                    drawBitmap(holder, w, h, cachedBitmap, 255);
                }
            }
        }

        /** 底色铺满（bmp 缺失时的兜底画面，与 App 背景一致）。 */
        private void drawColor(SurfaceHolder holder) {
            Canvas canvas = null;
            try {
                canvas = holder.lockHardwareCanvas();
                if (canvas != null) {
                    canvas.drawColor(0xFF1A1A1A);
                }
            } finally {
                if (canvas != null) {
                    holder.unlockCanvasAndPost(canvas);
                }
            }
        }

        /** 单图居中裁剪铺满一帧（centerCrop：目标矩形放大，画布自动裁掉超出部分）。 */
        private void drawBitmap(SurfaceHolder holder, int w, int h, Bitmap bmp, int alpha) {
            Canvas canvas = null;
            try {
                canvas = holder.lockHardwareCanvas();
                if (canvas != null) {
                    canvas.drawColor(0xFF1A1A1A);
                    drawBitmap(canvas, w, h, bmp, alpha);
                }
            } finally {
                if (canvas != null) {
                    holder.unlockCanvasAndPost(canvas);
                }
            }
        }

        private void drawBitmap(Canvas canvas, int w, int h, Bitmap bmp, int alpha) {
            if (bmp == null || bmp.isRecycled() || alpha <= 0) {
                return;
            }
            float scale = Math.max(w / (float) bmp.getWidth(), h / (float) bmp.getHeight());
            int dw = Math.round(bmp.getWidth() * scale);
            int dh = Math.round(bmp.getHeight() * scale);
            Rect dst = new Rect((w - dw) / 2, (h - dh) / 2,
                    (w - dw) / 2 + dw, (h - dh) / 2 + dh);
            if (alpha >= 255) {
                canvas.drawBitmap(bmp, null, dst, paint);
            } else {
                alphaPaint.setAlpha(alpha);
                canvas.drawBitmap(bmp, null, dst, alphaPaint);
            }
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
     * 绘制时放大回屏（FILTER_BITMAP）即得柔焦效果。总耗时毫秒级，避免逐帧高斯。
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

    /** 解码（≈壁纸内容变化）后重写性能自记文件：缓存命中应远多于解码。 */
    private static void flushPerfStats(Context ctx) {
        try {
            String content = "WallSwitch 引擎性能自记（每次壁纸内容变化后重写）\n"
                    + "更新时间: " + PERF_TIME.format(new Date()) + "\n"
                    + "累计绘制: " + perfDraws + " 次\n"
                    + "缓存命中(零解码): " + perfCacheHits + " 次\n"
                    + "真实解码: " + perfDecodes + " 次, 共 " + perfDecodeMs + " ms\n";
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
