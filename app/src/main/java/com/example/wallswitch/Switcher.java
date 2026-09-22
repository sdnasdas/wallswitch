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
 * 切换逻辑核心：顺序/随机切换 + 桌面/锁屏独立推进进度。
 * 进度约定（SharedPreferences "settings"，桌面/锁屏各自独立）：
 * - home_seq / lock_seq：顺序模式下一次的索引
 * - home_pool / lock_pool：随机模式下剩余未用的 id 池（JSON 数组字符串）
 * - home_current / lock_current：当前壁纸 id
 */
public class Switcher {

    // SharedPreferences 文件名
    private static final String PREFS_NAME = "settings";
    // 切换模式 key 与取值
    private static final String KEY_MODE = "mode";
    private static final String MODE_ORDER = "order";
    private static final String MODE_RANDOM = "random";
    // 桌面/锁屏启用开关 key（默认 true）
    private static final String KEY_HOME_ENABLED = "home_enabled";
    private static final String KEY_LOCK_ENABLED = "lock_enabled";
    // 顺序进度 key
    private static final String KEY_SEQ_HOME = "home_seq";
    private static final String KEY_SEQ_LOCK = "lock_seq";
    // 随机池 key
    private static final String KEY_POOL_HOME = "home_pool";
    private static final String KEY_POOL_LOCK = "lock_pool";
    // 当前壁纸 id key
    private static final String KEY_CURRENT_HOME = "home_current";
    private static final String KEY_CURRENT_LOCK = "lock_current";
    // 全图解码最长边上限（控制内存，与 WallpaperStore 一致）
    private static final int MAX_DECODE_DIM = 2048;

    /** 切换下一张壁纸：forHome 为 true 表示桌面，false 表示锁屏。返回是否成功设置到系统。 */
    public static boolean next(Context ctx, boolean forHome) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        // 对应范围被关闭时直接返回
        boolean enabled = prefs.getBoolean(KEY_HOME_ENABLED, true);
        if (!forHome) {
            enabled = prefs.getBoolean(KEY_LOCK_ENABLED, true);
        }
        if (!enabled) {
            return false;
        }
        // 候选集 = 库中勾选了对应范围的图
        List<WallpaperStore.Item> all = WallpaperStore.load(ctx);
        List<WallpaperStore.Item> candidates = new ArrayList<>();
        for (WallpaperStore.Item item : all) {
            boolean match = item.home;
            if (!forHome) {
                match = item.lock;
            }
            if (match) {
                candidates.add(item);
            }
        }
        // 无候选图可设（库空或全部未勾选）：直接返回，不 Toast（避免后台弹窗）
        if (candidates.isEmpty()) {
            return false;
        }
        // 按模式选出下一张，并推进顺序索引 / 消耗随机池
        SharedPreferences.Editor editor = prefs.edit();
        String mode = prefs.getString(KEY_MODE, MODE_ORDER);
        String nextId;
        if (MODE_RANDOM.equals(mode)) {
            nextId = pickRandom(prefs, editor, candidates, forHome);
        } else {
            nextId = pickOrder(prefs, editor, candidates, forHome);
        }
        if (nextId == null) {
            return false;
        }
        // 记录当前壁纸 id
        String keyCurrent = KEY_CURRENT_HOME;
        if (!forHome) {
            keyCurrent = KEY_CURRENT_LOCK;
        }
        editor.putString(keyCurrent, nextId);
        editor.apply();
        // 应用到系统壁纸（失败时向调用方返回 false，由界面侧给出反馈）
        boolean applied = setWallpaper(ctx, WallpaperStore.getFullFile(ctx, nextId), forHome);
        // 设置成功后刷新小组件缩略图
        if (applied) {
            WidgetProvider.updateWidget(ctx);
        }
        return applied;
    }

    /** 读取当前壁纸 id：forHome 为 true 表示桌面，false 表示锁屏；无记录返回 null。 */
    public static String getCurrent(Context ctx, boolean forHome) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String key = KEY_CURRENT_HOME;
        if (!forHome) {
            key = KEY_CURRENT_LOCK;
        }
        return prefs.getString(key, null);
    }

    /** 顺序模式：取候选中 (seq+1) % size 的图并写回 seq，返回下一张 id。 */
    private static String pickOrder(SharedPreferences prefs, SharedPreferences.Editor editor,
                                    List<WallpaperStore.Item> candidates, boolean forHome) {
        String keySeq = KEY_SEQ_HOME;
        if (!forHome) {
            keySeq = KEY_SEQ_LOCK;
        }
        int seq = prefs.getInt(keySeq, -1);
        int nextIndex = (seq + 1) % candidates.size();
        editor.putInt(keySeq, nextIndex);
        return candidates.get(nextIndex).id;
    }

    /** 随机模式：从剩余池取一张（一轮不重复、用完重置），池内失效 id 先过滤，返回下一张 id。 */
    private static String pickRandom(SharedPreferences prefs, SharedPreferences.Editor editor,
                                     List<WallpaperStore.Item> candidates, boolean forHome) {
        String keyPool = KEY_POOL_HOME;
        if (!forHome) {
            keyPool = KEY_POOL_LOCK;
        }
        // 读取现有池，过滤掉已不在候选集中的 id（图被删除或取消勾选）
        List<String> pool = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString(keyPool, "[]"));
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
        editor.putString(keyPool, new JSONArray(pool).toString());
        return nextId;
    }

    /** 应用到系统壁纸：桌面用 setBitmap，锁屏用带 FLAG_LOCK 的公开重载；返回是否成功。 */
    private static boolean setWallpaper(Context ctx, File file, boolean forHome) {
        // 解码全图（超采样防 OOM，复用存储层实现）
        Bitmap bitmap = WallpaperStore.decodeBounded(file, MAX_DECODE_DIM);
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
}
