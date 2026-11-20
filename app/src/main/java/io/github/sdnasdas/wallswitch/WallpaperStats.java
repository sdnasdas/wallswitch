package io.github.sdnasdas.wallswitch;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 每张壁纸的上屏统计（v3.188，详细信息面板用）：桌面、锁屏各自的上屏次数与最近一次上屏时刻。
 *
 * <p>为什么不写进 library.json：每次切换都要记一笔（灭屏切换可能一天几十次），整份表重写太重，
 * 还会让「改字段必须立刻 saveLibrary」那条缓存不变量多一个高频写手。放在 {@code settings} 偏好里，
 * 备份包本来就带着这份 xml（{@link BackupStore}），还原也一起回来，不新增条目。
 *
 * <p>键：{@code st_<h|l>_n_<id>}（次数）、{@code st_<h|l>_t_<id>}（墙钟毫秒）。
 * 只从 {@link Switcher} 的统一上屏出口记，图被彻底删除时由 {@link WallpaperStore} 清掉。
 * v3.188 之前的上屏没有记录，老壁纸从 0 开始算。
 */
public final class WallpaperStats {

    private static final String PREFS_NAME = "settings";

    private WallpaperStats() {
    }

    /** 记一次上屏（成功之后才调）。 */
    public static void recordShown(Context ctx, String wallpaperId, boolean forHome) {
        if (wallpaperId == null || wallpaperId.isEmpty()) {
            return;
        }
        SharedPreferences prefs = prefs(ctx);
        String count = countKey(wallpaperId, forHome);
        prefs.edit()
                .putInt(count, prefs.getInt(count, 0) + 1)
                .putLong(timeKey(wallpaperId, forHome), System.currentTimeMillis())
                .apply();
    }

    public static int shownCount(Context ctx, String wallpaperId, boolean forHome) {
        return prefs(ctx).getInt(countKey(wallpaperId, forHome), 0);
    }

    /** 这一面最近一次上屏时刻；从没上过返回 0。 */
    public static long lastShown(Context ctx, String wallpaperId, boolean forHome) {
        return prefs(ctx).getLong(timeKey(wallpaperId, forHome), 0L);
    }

    /** 重编分叉：新造的那条接着用原来那张的统计（在用户眼里还是同一张图）。 */
    public static void copy(Context ctx, String fromId, String toId) {
        SharedPreferences prefs = prefs(ctx);
        SharedPreferences.Editor editor = prefs.edit();
        for (boolean forHome : new boolean[]{true, false}) {
            int count = prefs.getInt(countKey(fromId, forHome), 0);
            long time = prefs.getLong(timeKey(fromId, forHome), 0L);
            if (count > 0) {
                editor.putInt(countKey(toId, forHome), count);
            }
            if (time > 0L) {
                editor.putLong(timeKey(toId, forHome), time);
            }
        }
        editor.apply();
    }

    /** 图被彻底删除时清掉它的统计，免得偏好里攒下一堆认不回来的键。 */
    public static void forget(Context ctx, String wallpaperId) {
        SharedPreferences.Editor editor = prefs(ctx).edit();
        for (boolean forHome : new boolean[]{true, false}) {
            editor.remove(countKey(wallpaperId, forHome));
            editor.remove(timeKey(wallpaperId, forHome));
        }
        editor.apply();
    }

    static String countKey(String wallpaperId, boolean forHome) {
        return "st_" + (forHome ? "h" : "l") + "_n_" + wallpaperId;
    }

    static String timeKey(String wallpaperId, boolean forHome) {
        return "st_" + (forHome ? "h" : "l") + "_t_" + wallpaperId;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
