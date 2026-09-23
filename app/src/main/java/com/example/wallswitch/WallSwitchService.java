package com.example.wallswitch;

import android.app.Activity;
import android.app.WallpaperInfo;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.service.wallpaper.WallpaperService;
import android.view.SurfaceHolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.List;
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
 * 已知风险：用户/主题商店若把壁纸换成别的，引擎被停用，Switcher 自动回退到
 * 静态 setBitmap 链路（功能不中断，但幽灵图风险回来）；主界面会提示引擎未激活。
 */
public class WallSwitchService extends WallpaperService {

    /** 活着的引擎实例（系统可能同时存在预览引擎与正式引擎，通知时全部刷新）。 */
    private static final List<WallEngine> ENGINES = new CopyOnWriteArrayList<>();
    /** 解码 + 绘制共用的后台线程，避免阻塞系统壁纸回调线程。 */
    private static final ExecutorService DRAW_EXECUTOR = Executors.newSingleThreadExecutor();

    /** 当前桌面是否由本引擎渲染（用户已把本 App 设为动态壁纸）。 */
    public static boolean isActive(Context ctx) {
        WallpaperInfo info = WallpaperManager.getInstance(ctx).getWallpaperInfo();
        return info != null
                && info.getComponent().equals(new ComponentName(ctx, WallSwitchService.class));
    }

    /** 壁纸内容变化（Switcher.next 已推进指针）后调用，通知所有活引擎重绘。 */
    public static void notifyWallpaperChanged() {
        for (WallEngine engine : ENGINES) {
            engine.scheduleDraw();
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

    // ==================== 引擎开关：保存/还原用户原来的桌面壁纸 ====================

    /** 关掉引擎时要还原的壁纸存档文件。 */
    public static File previousWallpaperFile(Context ctx) {
        return new File(ctx.getFilesDir(), "previous_wallpaper.png");
    }

    /**
     * 保存「当前系统桌面壁纸」，供关掉引擎后还原。
     * 引擎已经激活时不保存 —— 那时读回来的是我们自己的画面，存它没意义。
     * 纯 IO + 绘图，调用方放后台线程。
     */
    public static boolean saveCurrentWallpaper(Context ctx) {
        if (isActive(ctx)) {
            return false;
        }
        Drawable drawable;
        try {
            WallpaperManager wm = WallpaperManager.getInstance(ctx);
            // 清掉进程内缓存，确保读到的是当前真正生效的那张
            wm.forgetLoadedWallpaper();
            drawable = wm.getDrawable();
        } catch (Exception e) {
            return false;
        }
        if (drawable == null) {
            return false;
        }
        int w = drawable.getIntrinsicWidth();
        int h = drawable.getIntrinsicHeight();
        if (w <= 0 || h <= 0) {
            return false;
        }
        Bitmap bitmap = null;
        try {
            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            drawable.setBounds(0, 0, w, h);
            drawable.draw(canvas);
            try (OutputStream out = new FileOutputStream(previousWallpaperFile(ctx))) {
                return bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
        } catch (Exception | OutOfMemoryError e) {
            return false;
        } finally {
            if (bitmap != null) {
                bitmap.recycle();
            }
        }
    }

    /**
     * 关掉引擎：把存档的桌面壁纸设回去（setBitmap 会同时解除动态壁纸）；
     * 没有存档就 clear()，退回系统默认。纯 IO，调用方放后台线程。
     */
    public static boolean restoreSavedWallpaper(Context ctx) {
        try {
            WallpaperManager wm = WallpaperManager.getInstance(ctx);
            File saved = previousWallpaperFile(ctx);
            if (saved.exists()) {
                Bitmap bitmap = BitmapFactory.decodeFile(saved.getAbsolutePath());
                if (bitmap != null) {
                    wm.setBitmap(bitmap);
                    bitmap.recycle();
                    return true;
                }
            }
            wm.clear();
            return true;
        } catch (Exception | OutOfMemoryError e) {
            return false;
        }
    }
    @Override
    public Engine onCreateEngine() {
        return new WallEngine();
    }

    /** 引擎：把"当前壁纸"居中裁剪绘制到系统给的 Surface 上。 */
    class WallEngine extends Engine {

        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        /** Surface 是否可用（onSurfaceCreated ~ onSurfaceDestroyed 之间）。 */
        private volatile boolean surfaceReady;

        @Override
        public void onCreate(SurfaceHolder surfaceHolder) {
            super.onCreate(surfaceHolder);
            ENGINES.add(this);
        }

        @Override
        public void onDestroy() {
            ENGINES.remove(this);
            super.onDestroy();
        }

        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder);
            surfaceReady = true;
            scheduleDraw();
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            surfaceReady = true;
            scheduleDraw();
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            surfaceReady = false;
            super.onSurfaceDestroyed(holder);
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);
            // 等价 Muzei 的 ReloadWhenVisible：亮屏回到可见时按最新指针重绘一次，
            // 灭屏期间错过的切换在这一刻补上
            if (visible) {
                scheduleDraw();
            }
        }

        /** 后台线程解码并绘制；Surface 竞态销毁等异常吞掉，等下一次重绘机会。 */
        void scheduleDraw() {
            DRAW_EXECUTOR.execute(this::drawCurrentSafely);
        }

        private void drawCurrentSafely() {
            try {
                drawCurrent();
            } catch (Throwable ignored) {
            }
        }

        private void drawCurrent() {
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
            Bitmap bmp = loadCurrentBitmap(w, h);
            Canvas canvas = null;
            try {
                canvas = holder.lockCanvas();
                if (canvas == null) {
                    return;
                }
                // 底色与 App 背景一致；bmp 为 null（空库/解码失败）时保持纯底色而非系统兜底图
                canvas.drawColor(0xFF1A1A1A);
                if (bmp != null) {
                    // 居中裁剪铺满（centerCrop）：目标矩形故意放大，画布自动裁掉超出部分
                    float scale = Math.max(w / (float) bmp.getWidth(), h / (float) bmp.getHeight());
                    int dw = Math.round(bmp.getWidth() * scale);
                    int dh = Math.round(bmp.getHeight() * scale);
                    Rect dst = new Rect((w - dw) / 2, (h - dh) / 2,
                            (w - dw) / 2 + dw, (h - dh) / 2 + dh);
                    canvas.drawBitmap(bmp, null, dst, paint);
                }
            } finally {
                if (canvas != null) {
                    holder.unlockCanvasAndPost(canvas);
                }
            }
            if (bmp != null) {
                bmp.recycle();
            }
        }

        /** 按"桌面"范围启用库的当前指针解码壁纸；有库无指针时推进一张。 */
        private Bitmap loadCurrentBitmap(int targetW, int targetH) {
            Context ctx = WallSwitchService.this;
            LibraryStore.Library lib = LibraryStore.enabledLibForScope(ctx, true);
            if (lib == null) {
                return null;
            }
            File file = currentFile(ctx, lib.id);
            if (file == null && !WallpaperStore.loadByLib(ctx, lib.id).isEmpty()) {
                // 引擎首次接管：库里还没有"当前"指针，推进一张
                Switcher.next(ctx, lib.id, true);
                file = currentFile(ctx, lib.id);
            }
            if (file == null) {
                return null;
            }
            // 按 Surface 实际尺寸解码，但不超过统一的壁纸分辨率上限（防 OOM）
            int maxDim = Math.min(Math.max(targetW, targetH), WallpaperStore.maxWallpaperDim(ctx));
            return WallpaperStore.decodeBounded(file, maxDim);
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
    }
}
