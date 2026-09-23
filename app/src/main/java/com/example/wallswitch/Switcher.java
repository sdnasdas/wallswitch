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
 *
 * <h3>两条上屏链路（v3.9 起）</h3>
 * <ul>
 *   <li><b>桌面</b>：只走动态壁纸引擎（{@link WallSwitchService}）。切图 = 推进指针 +
 *       通知引擎重绘，不经系统静态壁纸链路 —— 从架构上根治 MagicOS 的"幽灵图"。
 *       引擎未激活时<b>不做任何切换</b>，只把 {@code engine_inactive} 交给界面提示去开开关
 *       （按需求删除了原来的静态 setBitmap 兜底：那条链路只保证"系统服务存好了图"，
 *       最终显示依赖 Launcher 二次加载，正是幽灵图的来源）。</li>
 *   <li><b>锁屏</b>：引擎只认"系统壁纸"这一件事，同时盖住桌面和锁屏，无法单独控制锁屏；
 *       所以锁屏保留一次 {@code setBitmap(FLAG_LOCK)}。桌面与锁屏因此可以各自独立设置。</li>
 * </ul>
 */
public class Switcher {

    // SharedPreferences 文件名
    private static final String PREFS_NAME = "settings";
    // 最近一次解码结果缓存：小图库高频切换时避免反复解码同一张图（上限 1 张，控制内存）
    private static String cachedName;
    private static Bitmap cachedBitmap;

    // 最近一次设置壁纸的失败原因（供界面/日志显示，用于区分"没执行"和"执行了但失败"）
    private static volatile String lastError;

    // 最近一次成功应用的壁纸标题（按范围分别记录，供自动切换通知显示"切到了哪张"）
    private static volatile String lastTitleHome;
    private static volatile String lastTitleLock;

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
     * 库未启用或未覆盖对应范围时不切换。返回是否成功上屏。
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
        boolean applied;
        if (forHome) {
            // 桌面只走引擎：没激活就没法切，如实报错让界面提示去开开关
            if (!WallSwitchService.isActive(ctx)) {
                lastError = "engine_inactive";
                return false;
            }
            // 切图 = 推进指针 + 通知引擎重绘（引擎在后台线程解码并直接画上 Surface）
            WallSwitchService.notifyWallpaperChanged();
            applied = true;
        } else {
            // 锁屏：引擎管不到，只能走这一次静态调用
            applied = setLockWallpaper(ctx, WallpaperStore.getFullFile(ctx, nextId));
        }
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
     * 锁屏壁纸：必须用带 which 的重载显式指定 FLAG_LOCK（简化版 setBitmap(bitmap) 会同时改桌面+锁屏）。
     * 这里只保留这一条静态调用；原来针对桌面的三道/四重校验与延迟重试已随静态链路一并删除。
     */
    private static boolean setLockWallpaper(Context ctx, File file) {
        Bitmap bitmap = loadBitmap(ctx, file);
        if (bitmap == null) {
            lastError = "decode_failed";
            return false;
        }
        try {
            WallpaperManager wm = WallpaperManager.getInstance(ctx);
            int newId = wm.setBitmap(bitmap, null, true, WallpaperManager.FLAG_LOCK);
            if (newId == 0) {
                // 系统没接受（个别机型限制后台设置锁屏壁纸）
                lastError = "not_applied";
                return false;
            }
            lastError = null;
            return true;
        } catch (Exception e) {
            // 如缺 SET_WALLPAPER 权限、机型限制：记录异常便于界面提示
            lastError = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
            return false;
        }
    }

    /** 把内部错误码转成可读文案（定时状态行与手动/小组件切换失败提示共用）。 */
    public static String errorText(Context ctx, String code) {
        if (code == null || "failed".equals(code)) {
            return ctx.getString(R.string.status_failed_unknown);
        }
        if ("engine_inactive".equals(code)) {
            return ctx.getString(R.string.status_engine_inactive);
        }
        if ("decode_failed".equals(code)) {
            return ctx.getString(R.string.status_decode_failed);
        }
        if ("not_applied".equals(code)) {
            return ctx.getString(R.string.status_not_applied);
        }
        // 异常类名等原始信息直接透出，便于定位
        return code;
    }

    /** 解码壁纸并做单张缓存（超采样防 OOM，复用存储层实现）。 */
    private static Bitmap loadBitmap(Context context, File file) {
        String name = file.getName();
        if (name.equals(cachedName) && cachedBitmap != null && !cachedBitmap.isRecycled()) {
            return cachedBitmap;
        }
        Bitmap bitmap = WallpaperStore.decodeBounded(file, WallpaperStore.maxWallpaperDim(context));
        if (bitmap != null) {
            cachedName = name;
            cachedBitmap = bitmap;
        }
        return bitmap;
    }
}
