package com.example.wallswitch;

import android.app.WallpaperInfo;
import android.app.WallpaperManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;

import org.json.JSONArray;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 切换逻辑核心：按「壁纸库 + 范围（桌面/锁屏）」切换，进度按库隔离。
 * 进度键（SharedPreferences "settings"）由 progressBase 生成：
 * - p_&lt;libId&gt;_h_seq/_pool/_current：该库桌面范围的顺序索引/随机池/当前壁纸
 * - p_&lt;libId&gt;_l_seq/_pool/_current：该库锁屏范围
 */
public class Switcher {

    // SharedPreferences 文件名
    private static final String PREFS_NAME = "settings";
    // 全图解码最长边上限（控制内存，与 WallpaperStore 一致）
    private static final int MAX_DECODE_DIM = 2048;
    // 最近一次解码结果缓存：小图库高频切换时避免反复解码同一张图（上限 1 张，控制内存）
    private static String cachedName;
    private static Bitmap cachedBitmap;

    // 最近一次设置壁纸的失败原因（供界面/日志显示，用于区分“没执行”和“执行了但失败”）
    private static volatile String lastError;

    // 最近一次成功应用的壁纸标题（按范围分别记录，供自动切换通知显示"切到了哪张"）
    private static volatile String lastTitleHome;
    private static volatile String lastTitleLock;

    // 第 4 重校验（读回系统实际渲染的壁纸图比对）：壁纸 Drawable 重绘的最长边上限（只需采样，不用原尺寸）
    private static final int VERIFY_RENDER_MAX = 256;
    // 比对时的采样边长（两图中心区域统一缩放到该尺寸逐像素比较）
    private static final int VERIFY_SAMPLE_SIZE = 32;
    // 平均单通道色差容忍上限（0~255）：系统会重新缩放/压缩需留余量；渲染层兜底图与目标图通常差异巨大
    private static final double VERIFY_MAX_DIFF = 40.0;
    // 第 4 重校验不通过时的延迟重试次数与间隔（渲染层偶发抽风，延迟重设一次通常即恢复）
    private static final int APPLY_MAX_RETRY = 2;
    private static final long APPLY_RETRY_DELAY_MS = 500L;

    /** 最近一次设置壁纸的失败原因，null 表示最近一次没有失败。 */
    public static String lastError() {
        return lastError;
    }

    /** 最近一次应用的壁纸标题（forHome 区分桌面/锁屏）：本轮未成功应用返回 null，已应用但未命名返回空串。 */
    public static String lastAppliedTitle(boolean forHome) {
        return forHome ? lastTitleHome : lastTitleLock;
    }

    /** 生成某库某范围的进度键前缀（LibraryStore 迁移也使用）。 */
    public static String progressBase(String libId, boolean forHome) {
        return "p_" + libId + (forHome ? "_h" : "_l");
    }

    /**
     * 切换指定库的下一张壁纸：forHome 为 true 表示桌面，false 表示锁屏。
     * 库未启用或未覆盖对应范围时不切换。返回是否成功设置到系统。
     */
    public static boolean next(Context ctx, String libId, boolean forHome) {
        LibraryStore.Library lib = LibraryStore.get(ctx, libId);
        if (lib == null || !lib.enabled) {
            return false;
        }
        if (forHome ? !lib.home : !lib.lock) {
            return false;
        }
        // 候选集 = 该库内的全部壁纸
        List<WallpaperStore.Item> candidates = WallpaperStore.loadByLib(ctx, libId);
        if (candidates.isEmpty()) {
            return false;
        }
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String base = progressBase(libId, forHome);
        String nextId;
        if (LibraryStore.MODE_RANDOM.equals(lib.mode)) {
            nextId = pickRandom(prefs, base, candidates);
        } else {
            nextId = pickOrder(prefs, base, candidates);
        }
        if (nextId == null) {
            return false;
        }
        // 记录当前壁纸 id
        prefs.edit().putString(base + "_current", nextId).apply();
        // 应用到系统壁纸（失败时向调用方返回 false，由界面侧给出反馈）
        boolean applied = setWallpaper(ctx, WallpaperStore.getFullFile(ctx, nextId), forHome);
        // 记录本次切到的壁纸标题（成功才有意义），供自动切换通知显示"切到了哪张"
        String appliedTitle = applied ? WallpaperStore.getTitle(ctx, nextId) : null;
        if (forHome) {
            lastTitleHome = appliedTitle;
        } else {
            lastTitleLock = appliedTitle;
        }
        // 设置成功后刷新小组件缩略图
        if (applied) {
            WidgetProvider.updateWidget(ctx);
        }
        return applied;
    }

    /** 读取某库某范围的当前壁纸 id：无记录返回 null。 */
    public static String getCurrent(Context ctx, String libId, boolean forHome) {
        String base = progressBase(libId, forHome);
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(base + "_current", null);
    }

    /** 清除某库的切换进度（删除库时调用）。 */
    public static void clearProgress(Context ctx, String libId) {
        SharedPreferences.Editor editor = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
        for (boolean forHome : new boolean[]{true, false}) {
            String base = progressBase(libId, forHome);
            editor.remove(base + "_seq");
            editor.remove(base + "_pool");
            editor.remove(base + "_current");
        }
        editor.apply();
    }

    /** 顺序模式：取 (seq+1) % size 的图并写回 seq，返回下一张 id。 */
    private static String pickOrder(SharedPreferences prefs, String base,
                                    List<WallpaperStore.Item> candidates) {
        int seq = prefs.getInt(base + "_seq", -1);
        int nextIndex = (seq + 1) % candidates.size();
        prefs.edit().putInt(base + "_seq", nextIndex).apply();
        return candidates.get(nextIndex).id;
    }

    /** 随机模式：从剩余池取一张（该库一轮不重复、用完重置），池内失效 id 先过滤。 */
    private static String pickRandom(SharedPreferences prefs, String base,
                                     List<WallpaperStore.Item> candidates) {
        List<String> pool = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString(base + "_pool", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                String id = arr.getString(i);
                for (WallpaperStore.Item item : candidates) {
                    if (item.id.equals(id)) {
                        pool.add(id);
                        break;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        // 池为空则用候选全集重置（打乱顺序保证随机）
        if (pool.isEmpty()) {
            for (WallpaperStore.Item item : candidates) {
                pool.add(item.id);
            }
            Collections.shuffle(pool);
        }
        // 取出第一张并从池中移除后写回，保证一轮内不重复
        String nextId = pool.remove(0);
        prefs.edit().putString(base + "_pool", new JSONArray(pool).toString()).apply();
        return nextId;
    }

    /**
     * 应用到系统壁纸：必须用带 which 的重载分别指定范围（简化版 setBitmap(bitmap) 会同时改桌面+锁屏）。
     * 成功判定做四重校验，避免「返回成功但桌面没变/渲染成别的图」的假象：
     * 1. setBitmap 返回的新壁纸 ID 为 0 → 系统未接受；
     * 2. 设置前后系统壁纸 ID 不变 → 被 ROM 静默忽略（主题/杂志锁屏/省电策略拦截）；
     * 3. 设置后动态壁纸仍在运行 → 静态图被动态壁纸盖住（如 Muzei 卸载残留/其他动态壁纸）；
     * 4. 读回系统实际供给渲染的壁纸图，与刚设置的图比对中心区域采样（仅桌面：
     *    锁屏壁纸在 API 34 前无公开读回 API，只做前三重）。
     * 第 4 重不通过时延迟重设重试（最多 {@link #APPLY_MAX_RETRY} 次）：切换瞬间桌面不在前台时，
     * 渲染层偶发加载失败、回落到内置兜底图（系统壁纸设置里看不到的深色图），重设一次通常即恢复；
     * 重试无效才报 apply_mismatch，让通知不再报假成功。
     * 注意：重试会短暂 sleep，调用方必须在后台线程执行（UI 线程调用会卡顿）。
     */
    private static boolean setWallpaper(Context ctx, File file, boolean forHome) {
        Bitmap bitmap = loadBitmap(file);
        if (bitmap == null) {
            lastError = "decode_failed";
            return false;
        }
        try {
            WallpaperManager wm = WallpaperManager.getInstance(ctx);
            int which = forHome ? WallpaperManager.FLAG_SYSTEM : WallpaperManager.FLAG_LOCK;
            for (int attempt = 0; attempt <= APPLY_MAX_RETRY; attempt++) {
                if (attempt > 0) {
                    // 渲染层抽风通常是一过性的：稍等片刻再重设
                    try {
                        Thread.sleep(APPLY_RETRY_DELAY_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                // 设置前的系统壁纸 ID：用于校验系统是否真的换上了新图
                int beforeId = wm.getWallpaperId(which);
                // 注意：wm.setBitmap(bitmap) 内部等价于 FLAG_SYSTEM | FLAG_LOCK，会连锁屏一起改，所以必须显式传 which
                int newId = wm.setBitmap(bitmap, null, true, which);
                if (newId == 0 || wm.getWallpaperId(which) == beforeId) {
                    // 系统没接受或静默忽略：若当前挂着动态壁纸（Muzei 等），它大概率是元凶
                    WallpaperInfo live = wm.getWallpaperInfo();
                    lastError = live != null ? "live_wallpaper:" + live.getPackageName() : "not_applied";
                    return false;
                }
                WallpaperInfo live = wm.getWallpaperInfo();
                if (live != null) {
                    // 壁纸已写入系统，但桌面仍被动态壁纸接管渲染，用户看不到静态图
                    lastError = "live_wallpaper:" + live.getPackageName();
                    return false;
                }
                // 锁屏壁纸读不回渲染图（API 34 前无公开 API），只能做到前三重校验
                if (!forHome || verifyHomeApplied(wm, bitmap)) {
                    lastError = null;
                    return true;
                }
            }
            // 壁纸已写入系统（ID 已变），但读回的渲染图始终对不上：
            // Launcher/渲染层加载失败、桌面实际显示的是内置兜底图，如实报失败
            lastError = "apply_mismatch";
            return false;
        } catch (Exception e) {
            // 失败（如缺 SET_WALLPAPER 权限、机型限制后台设置壁纸）记录下来，便于界面提示
            lastError = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
            return false;
        }
    }

    /**
     * 第 4 重校验（仅桌面）：读回系统当前实际供给渲染的壁纸，与刚设置的图比对中心区域采样。
     * 读回失败/无法采样时保守放行——这只是附加校验，不能因个别机型不支持而误伤正常切换；
     * 只有明确读到一张完全不同的图（如渲染层的内置兜底深色图）才判定不通过。
     */
    private static boolean verifyHomeApplied(WallpaperManager wm, Bitmap expected) {
        Drawable drawable;
        try {
            // 清掉 WallpaperManager 的进程内缓存，强制重新读回，避免拿到换壁纸之前的旧图
            wm.forgetLoadedWallpaper();
            drawable = wm.getDrawable();
        } catch (Exception e) {
            return true;
        }
        if (drawable == null) {
            return true;
        }
        Bitmap actual = drawableToBitmap(drawable);
        if (actual == null) {
            return true;
        }
        double diff = centerDiff(expected, actual);
        actual.recycle();
        return diff <= VERIFY_MAX_DIFF;
    }

    /** 把壁纸 Drawable 画到限尺寸 Bitmap（只需中心采样，最长边 {@link #VERIFY_RENDER_MAX} 足够，防 OOM）。 */
    private static Bitmap drawableToBitmap(Drawable drawable) {
        int w = drawable.getIntrinsicWidth();
        int h = drawable.getIntrinsicHeight();
        if (w <= 0 || h <= 0) {
            return null;
        }
        float scale = Math.min(1f, VERIFY_RENDER_MAX / (float) Math.max(w, h));
        int bw = Math.max(1, Math.round(w * scale));
        int bh = Math.max(1, Math.round(h * scale));
        try {
            Bitmap bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            drawable.setBounds(0, 0, bw, bh);
            drawable.draw(canvas);
            return bitmap;
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    /** 两图中心 50% 区域（避开系统按屏幕比例裁剪的边缘）缩到采样尺寸后的平均单通道色差（0~255）。 */
    private static double centerDiff(Bitmap a, Bitmap b) {
        Bitmap sa = centerSample(a);
        Bitmap sb = centerSample(b);
        int count = VERIFY_SAMPLE_SIZE * VERIFY_SAMPLE_SIZE;
        int[] pa = new int[count];
        int[] pb = new int[count];
        sa.getPixels(pa, 0, VERIFY_SAMPLE_SIZE, 0, 0, VERIFY_SAMPLE_SIZE, VERIFY_SAMPLE_SIZE);
        sb.getPixels(pb, 0, VERIFY_SAMPLE_SIZE, 0, 0, VERIFY_SAMPLE_SIZE, VERIFY_SAMPLE_SIZE);
        sa.recycle();
        sb.recycle();
        long sum = 0;
        for (int i = 0; i < count; i++) {
            sum += Math.abs(((pa[i] >> 16) & 0xFF) - ((pb[i] >> 16) & 0xFF))
                    + Math.abs(((pa[i] >> 8) & 0xFF) - ((pb[i] >> 8) & 0xFF))
                    + Math.abs((pa[i] & 0xFF) - (pb[i] & 0xFF));
        }
        return sum / (3.0 * count);
    }

    /** 取中心 50% 正方形区域并缩放到采样尺寸（返回的永远是新 Bitmap，调用方负责回收）。 */
    private static Bitmap centerSample(Bitmap src) {
        int side = Math.max(1, Math.min(src.getWidth(), src.getHeight()) / 2);
        Bitmap crop = Bitmap.createBitmap(src,
                (src.getWidth() - side) / 2, (src.getHeight() - side) / 2, side, side);
        Bitmap sample = Bitmap.createScaledBitmap(crop, VERIFY_SAMPLE_SIZE, VERIFY_SAMPLE_SIZE, true);
        // createBitmap/createScaledBitmap 尺寸不变时可能直接返回原图：绝不能回收传入的 src（解码缓存还在用）
        if (crop != src && crop != sample) {
            crop.recycle();
        }
        return sample;
    }

    /** 把内部错误码转成可读文案（定时状态行与手动/小组件切换失败提示共用）。 */
    public static String errorText(Context ctx, String code) {
        if (code == null || "failed".equals(code)) {
            return ctx.getString(R.string.status_failed_unknown);
        }
        if ("decode_failed".equals(code)) {
            return ctx.getString(R.string.status_decode_failed);
        }
        if ("not_applied".equals(code)) {
            return ctx.getString(R.string.status_not_applied);
        }
        if ("apply_mismatch".equals(code)) {
            return ctx.getString(R.string.status_apply_mismatch);
        }
        if (code.startsWith("live_wallpaper:")) {
            return ctx.getString(R.string.status_live_wallpaper,
                    code.substring("live_wallpaper:".length()));
        }
        // 异常类名等原始信息直接透出，便于定位
        return code;
    }

    /** 解码壁纸并做单张缓存（超采样防 OOM，复用存储层实现）。 */
    private static Bitmap loadBitmap(File file) {
        String name = file.getName();
        if (name.equals(cachedName) && cachedBitmap != null && !cachedBitmap.isRecycled()) {
            return cachedBitmap;
        }
        Bitmap bitmap = WallpaperStore.decodeBounded(file, MAX_DECODE_DIM);
        if (bitmap != null) {
            cachedName = name;
            cachedBitmap = bitmap;
        }
        return bitmap;
    }
}