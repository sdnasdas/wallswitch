package com.example.wallswitch;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;
import android.util.AttributeSet;
import android.view.View;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 「桌面图标预览」浮层：把用户自己的首页截图（已抠图，只留图标与文字）按整屏比例叠在裁剪取景框上，
 * 用来判断构图主体会不会被桌面图标挡住。
 *
 * 为什么只有截图这一种模式：早期还有一个「内置示意网格」，但它的列数/行距只能靠猜（那 4 列是从一张
 * 截图量出来的），换设备或换桌面布局就不准；而截图叠加是按整屏同比例对齐的，像素级准确 —— 索性去掉。
 *
 * 截图自带的壁纸会被 {@link #extractForeground} 抠成透明，只留图标与文字，
 * 否则两层壁纸叠在一起会发灰。截图与取景框同为整屏同比例，按 centerCrop 铺满后像素对齐。
 *
 * 首页截图是全局设置，只需设一次（见 {@link #save}／{@link #loadSaved}），之后每次进裁剪页直接可用。
 *
 * 本 View 不可点击，不会拦截触摸，裁剪区的捏合/拖动照常可用。
 */
public class LauncherPreviewOverlay extends View {

    /**
     * 抠图用的工作分辨率（长边）。与小尺寸原型验证过的分辨率一致：
     * 再大只是更清晰，对「看图标挡住哪」没有增量，却会线性推高 CPU 与内存。
     */
    private static final int WORK_LONG_SIDE = 720;
    /** 选进来的原图解码上限（防 OOM），抠图前会再缩到 WORK_LONG_SIDE。 */
    private static final int DECODE_MAX_DIM = 2048;
    /** 抠出来的图标叠加时的最大不透明度：看得清图标，同时还能透出壁纸。 */
    private static final int FOREGROUND_ALPHA = 210;
    /** 细节强度下限：低于此值视为平滑壁纸 → 全透明。 */
    private static final float DETAIL_LO = 8f;
    /** 细节强度上限：高于此值视为图标/文字 → 全不透明。 */
    private static final float DETAIL_HI = 28f;

    /** 全局预览底图的文件名（应用私有目录，卸载即清除）。 */
    private static final String OVERLAY_FILE = "launcher_overlay.png";

    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect dst = new Rect();

    private Bitmap screenshot;

    public LauncherPreviewOverlay(Context context) {
        this(context, null);
    }

    public LauncherPreviewOverlay(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public LauncherPreviewOverlay(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    // ==================== 全局底图的存取 ====================

    /** 全局预览底图文件（存在应用私有目录，卸载即清除）。 */
    public static File overlayFile(Context context) {
        return new File(context.getFilesDir(), OVERLAY_FILE);
    }

    /** 读取全局预览底图；没设置过或文件损坏返回 null。 */
    public static Bitmap loadSaved(Context context) {
        File file = overlayFile(context);
        if (!file.exists()) {
            return null;
        }
        return BitmapFactory.decodeFile(file.getAbsolutePath());
    }

    /** 把抠好的图层存成全局预览底图（覆盖旧的）。先写临时文件再改名，避免写坏后旧图也丢了。 */
    public static boolean save(Context context, Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) {
            return false;
        }
        File tmp = new File(context.getFilesDir(), OVERLAY_FILE + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                return false;
            }
        } catch (Exception e) {
            return false;
        }
        File dstFile = overlayFile(context);
        if (dstFile.exists() && !dstFile.delete()) {
            return false;
        }
        return tmp.renameTo(dstFile);
    }

    /** 清除全局预览底图。 */
    public static void clear(Context context) {
        File file = overlayFile(context);
        if (file.exists()) {
            file.delete();
        }
    }

    /**
     * 从相册 Uri 读图并抠图，返回可直接保存/叠加的图层。
     * 先复制到缓存文件再采样解码（复用 WallpaperStore.decodeBounded 防 OOM），最后抠图。
     * 纯 IO + 像素运算，调用方必须放在后台线程。
     */
    public static Bitmap keyedFromUri(Context context, Uri uri) {
        if (uri == null) {
            return null;
        }
        File tmp = new File(context.getCacheDir(), "launcher_overlay_src.tmp");
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(tmp)) {
            if (in == null) {
                return null;
            }
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        } catch (Exception e) {
            return null;
        }
        Bitmap decoded = WallpaperStore.decodeBounded(tmp, DECODE_MAX_DIM);
        if (decoded == null) {
            return null;
        }
        Bitmap keyed = extractForeground(decoded);
        decoded.recycle();
        return keyed;
    }

    // ==================== 视图状态 ====================

    /** 设置抠好的首页图标层；传 null 表示清除。 */
    public void setScreenshot(Bitmap bitmap) {
        Bitmap old = screenshot;
        screenshot = bitmap;
        if (old != null && old != bitmap && !old.isRecycled()) {
            old.recycle();
        }
        invalidate();
    }

    public boolean hasScreenshot() {
        return screenshot != null;
    }

    // ==================== 抠图 ====================

    /**
     * 把首页截图抠成「只留图标与文字」的叠加层：截图上自带的壁纸会变成透明。
     *
     * 判据是<b>局部细节强度</b> —— 图标与文字有锐利边缘和内部细节，壁纸是平滑渐变。
     * 三步：① 3×3 均值模糊去掉 JPEG 压缩噪点（否则 8×8 块噪声会被误判成边缘）；
     * ② 取与 8 邻域的最大亮度差作为细节强度，线性映射成不透明度；
     * ③ 5×5 最大值膨胀（形态学膨胀）填满图标内部的平坦区域，否则纯色图标会只剩一圈描边。
     *
     * 纯像素运算，调用方必须放在后台线程。
     *
     * @param src 解码后的首页截图
     * @return 带逐像素透明度的新位图；src 为空时返回 null
     */
    public static Bitmap extractForeground(Bitmap src) {
        if (src == null || src.isRecycled()) {
            return null;
        }
        int sw = src.getWidth();
        int sh = src.getHeight();
        float scale = Math.min(1f, WORK_LONG_SIDE / (float) Math.max(sw, sh));
        int w = Math.max(8, Math.round(sw * scale));
        int h = Math.max(8, Math.round(sh * scale));
        Bitmap work = Bitmap.createScaledBitmap(src, w, h, true);
        int n = w * h;
        int[] px = new int[n];
        work.getPixels(px, 0, w, 0, 0, w, h);
        if (work != src) {
            work.recycle();
        }

        // 亮度
        float[] lum = new float[n];
        for (int i = 0; i < n; i++) {
            int c = px[i];
            lum[i] = 0.299f * ((c >> 16) & 0xFF) + 0.587f * ((c >> 8) & 0xFF) + 0.114f * (c & 0xFF);
        }

        // ① 3x3 均值模糊
        float[] blur = new float[n];
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                float s = 0f;
                for (int dy = -1; dy <= 1; dy++) {
                    int row = (y + dy) * w + x;
                    s += lum[row - 1] + lum[row] + lum[row + 1];
                }
                blur[y * w + x] = s / 9f;
            }
        }

        // ② 局部细节强度 → 不透明度
        float[] mask = new float[n];
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int idx = y * w + x;
                float center = blur[idx];
                float detail = 0f;
                for (int dy = -1; dy <= 1; dy++) {
                    int row = idx + dy * w;
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dy == 0 && dx == 0) {
                            continue;
                        }
                        float v = Math.abs(center - blur[row + dx]);
                        if (v > detail) {
                            detail = v;
                        }
                    }
                }
                float a = (detail - DETAIL_LO) / (DETAIL_HI - DETAIL_LO);
                mask[idx] = a < 0f ? 0f : (a > 1f ? 1f : a);
            }
        }

        // ③ 5x5 最大值膨胀 + 合成 ARGB
        int[] out = new int[n];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float m = 0f;
                for (int dy = -2; dy <= 2; dy++) {
                    int yy = y + dy;
                    if (yy < 0 || yy >= h) {
                        continue;
                    }
                    int row = yy * w;
                    for (int dx = -2; dx <= 2; dx++) {
                        int xx = x + dx;
                        if (xx < 0 || xx >= w) {
                            continue;
                        }
                        float v = mask[row + xx];
                        if (v > m) {
                            m = v;
                        }
                    }
                }
                int idx = y * w + x;
                out[idx] = m <= 0f
                        ? 0
                        : ((px[idx] & 0x00FFFFFF) | (((int) (m * FOREGROUND_ALPHA)) << 24));
            }
        }
        Bitmap result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        result.setPixels(out, 0, w, 0, 0, w, h);
        return result;
    }

    // ==================== 绘制 ====================

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0 || screenshot == null || screenshot.isRecycled()) {
            return;
        }
        // centerCrop 铺满取景框（截图与取景框同为整屏同比例，故像素对齐）。
        // 透明度已经逐像素烘进位图（见 extractForeground），这里用满不透明度绘制即可。
        float scale = Math.max(w / (float) screenshot.getWidth(), h / (float) screenshot.getHeight());
        int dw = Math.round(screenshot.getWidth() * scale);
        int dh = Math.round(screenshot.getHeight() * scale);
        int left = (w - dw) / 2;
        int top = (h - dh) / 2;
        dst.set(left, top, left + dw, top + dh);
        canvas.drawBitmap(screenshot, null, dst, imagePaint);
    }
}
