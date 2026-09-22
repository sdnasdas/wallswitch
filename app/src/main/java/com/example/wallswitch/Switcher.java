package com.example.wallswitch;

import android.app.WallpaperManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;

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

    /** 应用到系统壁纸：桌面用 setBitmap，锁屏用带 FLAG_LOCK 的公开重载；返回是否成功。 */
    private static boolean setWallpaper(Context ctx, File file, boolean forHome) {
        Bitmap bitmap = loadBitmap(file);
        if (bitmap == null) {
            return false;
        }
        try {
            WallpaperManager wm = WallpaperManager.getInstance(ctx);
            if (forHome) {
                wm.setBitmap(bitmap);
            } else {
                // minSdk 26，可直接调用带 which 参数的公开重载
                wm.setBitmap(bitmap, null, true, WallpaperManager.FLAG_LOCK);
            }
            return true;
        } catch (Exception e) {
            // 失败（如缺 SET_WALLPAPER 权限、机型锁屏受限）返回 false，不再完全静默
            return false;
        }
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