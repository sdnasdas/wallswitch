package com.example.wallswitch;

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
        // 「开启接管」关着 = 本 App 不接管系统壁纸：手动切换、定时切换、小组件点按一律不写系统。
        // 这里是所有上屏路径的唯一收口（桌面引擎与锁屏 setBitmap 都在下面），拦一道即可全覆盖。
        if (!TakeoverManager.isEnabled(ctx)) {
            lastError = "takeover_off";
            // 别让通知/状态行沿用上一轮的旧标题，否则看起来像切成功了
            lastTitleHome = null;
            lastTitleLock = null;
            return false;
        }
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
        // 随机模式要记「上一张」，先留住当前这张的 id
        String oldCurrent = getCurrent(ctx, libId, forHome);
        String nextId;
        if (LibraryStore.MODE_RANDOM.equals(lib.mode)) {
            nextId = pickRandom(prefs, base, candidates);
        } else {
            nextId = pickOrder(prefs, base, candidates);
        }
        if (nextId == null) {
            return false;
        }
        boolean applied = applyById(ctx, libId, nextId, forHome);
        // 随机模式记一张「上一张」（常驻通知的回退按钮用，见 prev）：成功上屏才写，
        // 避免 engine_inactive 时把没上屏的图当成旧壁纸；顺序模式用 _seq 直接回退，不用它
        if (applied && forHome && LibraryStore.MODE_RANDOM.equals(lib.mode)) {
            prefs.edit().putString(base + "_prev", oldCurrent == null ? "" : oldCurrent).apply();
        }
        return applied;
    }

    /**
     * 回退到上一张（常驻通知的「上一张」按钮用）：顺序模式把 seq 拨回一位（天然循环，
     * 库里只有一张时等于重上屏自己）；随机模式与 {@code _prev} 互换——next 成功时会把
     * 旧 current 写进 _prev，所以回退的正是上一张真实显示过的图（只支持回一张，无历史栈）。
     * 守卫与 {@link #next} 一致；返回是否成功上屏。
     */
    public static boolean prev(Context ctx, String libId, boolean forHome) {
        if (!TakeoverManager.isEnabled(ctx)) {
            lastError = "takeover_off";
            lastTitleHome = null;
            lastTitleLock = null;
            return false;
        }
        LibraryStore.Library lib = LibraryStore.get(ctx, libId);
        if (lib == null || !lib.enabled) {
            return false;
        }
        if (forHome ? !lib.home : !lib.lock) {
            return false;
        }
        List<WallpaperStore.Item> candidates = WallpaperStore.loadByLib(ctx, libId);
        if (candidates.isEmpty()) {
            return false;
        }
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String base = progressBase(libId, forHome);
        if (LibraryStore.MODE_RANDOM.equals(lib.mode)) {
            String prevId = prefs.getString(base + "_prev", null);
            // 无记录（还没切过第二张）或那张已被删：没有可回退的目标，不做任何事
            if (prevId == null || prevId.isEmpty() || findByIndex(candidates, prevId) < 0) {
                return false;
            }
            String current = getCurrent(ctx, libId, forHome);
            // 互换：再按一次「上一张」就回到刚才这张（只回一张的语义）
            prefs.edit().putString(base + "_prev", current == null ? "" : current).apply();
            // 被换下的 current 不再在屏上，塞回本轮池头部，接下来的随机还能抽到它
            if (current != null) {
                poolPushHead(prefs, base, current);
            }
            return applyById(ctx, libId, prevId, forHome);
        }
        int seq = prefs.getInt(base + "_seq", -1);
        int prevIndex = (seq - 1 + candidates.size()) % candidates.size();
        prefs.edit().putInt(base + "_seq", prevIndex).apply();
        return applyById(ctx, libId, candidates.get(prevIndex).id, forHome);
    }

    /** 随机池头插一个 id（prev 回退后把换下的图放回本轮池），解析容错与 pickRandom 相同。 */
    private static void poolPushHead(SharedPreferences prefs, String base, String id) {
        List<String> pool = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString(base + "_pool", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                pool.add(arr.getString(i));
            }
        } catch (Exception ignored) {
        }
        pool.remove(id);
        pool.add(0, id);
        prefs.edit().putString(base + "_pool", new JSONArray(pool).toString()).apply();
    }

    /**
     * 把库里指定的一张设为该范围当前壁纸（库内长按浮出的「设为首页」用）：
     * 指针移过去并立刻上屏，同时把顺序进度拨到它、清掉随机池 —— 之后自动切换就从这张往下走。
     * 校验与 {@link #next} 相同（接管开关 / 库启用 / 覆盖该范围 / 这张确实在这个库里），
     * 失败原因写进 {@link #lastError()} 供界面提示。
     */
    public static boolean setCurrent(Context ctx, String libId, String wallpaperId, boolean forHome) {
        if (!TakeoverManager.isEnabled(ctx)) {
            lastError = "takeover_off";
            // 别让通知/状态行沿用上一轮的旧标题，否则看起来像切成功了
            lastTitleHome = null;
            lastTitleLock = null;
            return false;
        }
        LibraryStore.Library lib = LibraryStore.get(ctx, libId);
        if (lib == null || !lib.enabled || (forHome ? !lib.home : !lib.lock)) {
            return false;
        }
        List<WallpaperStore.Item> candidates = WallpaperStore.loadByLib(ctx, libId);
        int index = findByIndex(candidates, wallpaperId);
        if (index < 0) {
            return false;
        }
        String base = progressBase(libId, forHome);
        // 进度对齐到这张：顺序模式下次从它后面继续；随机池清空重建，免得紧接着又抽到同一张
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putInt(base + "_seq", index)
                .remove(base + "_pool")
                .apply();
        boolean applied = applyById(ctx, libId, wallpaperId, forHome);
        // 手动指定也是一次切换：随机模式同样记下「上一张」，供常驻通知回退到指定前的那张
        if (applied && forHome && LibraryStore.MODE_RANDOM.equals(lib.mode)) {
            String oldCurrent = getCurrent(ctx, libId, forHome);
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                    .putString(base + "_prev", oldCurrent == null ? "" : oldCurrent).apply();
        }
        return applied;
    }

    /**
     * 指针落定 + 上屏（{@link #next} 与 {@link #setCurrent} 共用）：
     * 写 _current、按范围上屏、记下这次切到的标题、刷新小组件。
     * 桌面走引擎（未激活时如实报 engine_inactive）；锁屏走一次 setBitmap。
     */
    private static boolean applyById(Context ctx, String libId, String wallpaperId, boolean forHome) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(progressBase(libId, forHome) + "_current", wallpaperId).apply();
        boolean applied;
        if (forHome) {
            // 桌面只走引擎：没激活就没法切，如实报错让界面提示去开开关
            if (!WallSwitchService.isActive(ctx)) {
                lastError = "engine_inactive";
                // 本轮桌面没切，别让通知沿用上一轮的旧标题（否则看起来像切成功了）
                lastTitleHome = null;
                return false;
            }
            // 切图 = 推进指针 + 通知引擎重绘（引擎在后台线程解码并直接画上 Surface）
            WallSwitchService.notifyWallpaperChanged();
            applied = true;
        } else {
            // 锁屏：引擎管不到，只能走这一条静态调用（由 TakeoverManager 统一设置并记下 id）
            applied = TakeoverManager.setLockFromFile(ctx,
                    WallpaperStore.getFullFile(ctx, wallpaperId)) != 0;
            lastError = applied ? null : "not_applied";
        }
        // 记录本次切到的壁纸标题（成功才有意义），供自动切换通知显示"切到了哪张"
        String appliedTitle = applied ? WallpaperStore.getTitle(ctx, wallpaperId) : null;
        if (forHome) {
            lastTitleHome = appliedTitle;
        } else {
            lastTitleLock = appliedTitle;
        }
        // 设置成功后刷新小组件缩略图与常驻通知（桌面范围才由通知展示）
        if (applied) {
            WidgetProvider.updateWidget(ctx);
            if (forHome) {
                StatusNotifier.update(ctx);
            }
        }
        return applied;
    }

    /** 读取某库某范围的当前壁纸 id：无记录返回 null。 */
    public static String getCurrent(Context ctx, String libId, boolean forHome) {
        String base = progressBase(libId, forHome);
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(base + "_current", null);
    }

    /**
     * 覆盖/删除某张壁纸后，把改动推上屏（v3.14）：该图若是某范围（桌面/锁屏）的当前壁纸——
     * 覆盖：指针不变，按新文件内容重新上屏（桌面通知引擎重绘，锁屏重新 setBitmap）；
     * 删除：清掉指向它的指针，再推进到库里下一张。
     * 该库未启用 / 不覆盖该范围 / 接管总开关关着时只清指针、不上屏（那时它本来也不在屏上）。
     * 锁屏路径涉及解码与系统调用，调用方放后台线程。
     */
    public static void reapplyIfCurrent(Context ctx, String libId, String wallpaperId) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        for (boolean forHome : new boolean[]{true, false}) {
            String current = getCurrent(ctx, libId, forHome);
            if (current == null || !current.equals(wallpaperId)) {
                continue;
            }
            File full = WallpaperStore.getFullFile(ctx, wallpaperId);
            boolean deleted = full == null || !full.exists();
            LibraryStore.Library lib = LibraryStore.get(ctx, libId);
            boolean onScreen = TakeoverManager.isEnabled(ctx) && lib != null && lib.enabled
                    && (forHome ? lib.home : lib.lock);
            if (deleted) {
                // 指针不能指向已删的图；还在屏上则推进到下一张（候选集已不含它）
                prefs.edit().remove(progressBase(libId, forHome) + "_current").apply();
                if (onScreen) {
                    next(ctx, libId, forHome);
                }
            } else if (onScreen) {
                // 覆盖：指针不变，按新文件内容重新上屏
                if (forHome) {
                    if (WallSwitchService.isActive(ctx)) {
                        // 引擎每次绘制都重新解码文件，通知即可
                        WallSwitchService.notifyWallpaperChanged();
                    }
                } else {
                    // 系统里存的是 setBitmap(FLAG_LOCK) 时的位图副本，必须重设一次
                    TakeoverManager.setLockFromFile(ctx, full);
                }
            }
        }
        // 覆盖路径不走 applyById（指针没动），通知里的大图标/标题要单独刷一次
        StatusNotifier.update(ctx);
    }

    /** 清除某库的切换进度（删除库时调用）。 */
    public static void clearProgress(Context ctx, String libId) {
        SharedPreferences.Editor editor = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
        for (boolean forHome : new boolean[]{true, false}) {
            String base = progressBase(libId, forHome);
            editor.remove(base + "_seq");
            editor.remove(base + "_pool");
            editor.remove(base + "_prev");
            editor.remove(base + "_current");
        }
        editor.apply();
    }

    /** 在候选集里找 id 的下标，找不到返回 -1。 */
    private static int findByIndex(List<WallpaperStore.Item> candidates, String id) {
        for (int i = 0; i < candidates.size(); i++) {
            if (candidates.get(i).id.equals(id)) {
                return i;
            }
        }
        return -1;
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
        if ("takeover_off".equals(code)) {
            return ctx.getString(R.string.status_takeover_off);
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
