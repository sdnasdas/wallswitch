package com.example.wallswitch;

import android.app.WallpaperManager;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.ContentValues;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 接管状态与落地策略（对齐 Muzei：没有开关，状态 = 系统壁纸是否就是本 App）。
 *
 * <h3>语义</h3>
 * <ul>
 *   <li>桌面：引擎已激活（{@link WallSwitchService#isActive}）就是接管中；没激活就需要用户
 *       去系统选择器确认一次（{@link #RESULT_NEED_ACTIVATION}）。没有启用库时引擎显示纯色。</li>
 *   <li>锁屏：有独立锁屏库 → 用 setBitmap 设成那张；没有 → 不动（引擎已盖住锁屏）。</li>
 *   <li>关闭途径只有一条：用户在系统设置里换成别的壁纸。本 App <b>任何路径都不再改写系统壁纸</b>
 *       （历史上 clear()/还原存档曾把系统壁纸写坏，已彻底移除）。</li>
 * </ul>
 *
 * <h3>状态判定用系统真值</h3>
 * 桌面看 {@link WallSwitchService#isActive}；锁屏看 getWallpaperId(FLAG_LOCK) 是否等于我们
 * 设进去时系统返回的那个 id（记在偏好里比对）—— 不是"我以为接管了"，也就没有"开关显示开、
 * 实际没接管"这类假状态。
 *
 * {@link #isEnabled} 是上面两个真值的派生（供切换/定时等链路做统一守卫）。
 * 除 {@link #isEnabled}/{@link #isHomeTakenOver}/{@link #isLockTakenOver} 之外的方法都涉及
 * 系统调用或 IO，调用方应放在后台线程。
 */
public final class TakeoverManager {

    private static final String PREFS_NAME = "settings";
    private static final String KEY_LOCK_ID = "takeover_lock_id";

    private static final String SAVED_DIR = Environment.DIRECTORY_PICTURES + "/WallSwitch";
    /** 保存结果日志文件（公共 Download/WallSwitch 目录，供不用 adb 时查看）。 */
    private static final String SAVE_LOG_NAME = "takeover_save_log.txt";
    private static final String SAVE_LOG_DIR = Environment.DIRECTORY_DOWNLOADS + "/WallSwitch";

    private static final String LOG_TAG = "TakeoverManager";

    /** apply 结果：无需改动。 */
    public static final int RESULT_NONE = 0;
    /** apply 结果：已按当前启用库调整完毕。 */
    public static final int RESULT_OK = 1;
    /** apply 结果：有启用库但引擎未激活，需要用户去系统选择器确认激活。 */
    public static final int RESULT_NEED_ACTIVATION = 2;

    private TakeoverManager() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // ==================== 接管状态（全部来自系统真值） ====================

    /** 是否处于接管中：桌面由本 App 引擎渲染，或锁屏是本 App 设的。 */
    public static boolean isEnabled(Context ctx) {
        return isHomeTakenOver(ctx) || isLockTakenOver(ctx);
    }

    // ==================== 接管情况（系统真值） ====================

    /** 桌面当前是否由本 App（动态壁纸引擎）接管。 */
    public static boolean isHomeTakenOver(Context ctx) {
        return WallSwitchService.isActive(ctx);
    }

    /** 锁屏当前是否由本 App 接管（与我们设进去时系统返回的壁纸 id 比对）。 */
    public static boolean isLockTakenOver(Context ctx) {
        int ours = prefs(ctx).getInt(KEY_LOCK_ID, 0);
        if (ours == 0) {
            return false;
        }
        try {
            return WallpaperManager.getInstance(ctx).getWallpaperId(WallpaperManager.FLAG_LOCK) == ours;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 锁屏是否正被桌面引擎「顺带接管」：没设过独立锁屏壁纸、但动态壁纸引擎已激活。
     * 引擎一激活就同时盖住桌面和锁屏 —— 没设锁屏库时锁屏其实已经是我们的，
     * 界面上这一态同样显示 WallPaper（与独立锁屏壁纸的区分只在实现里）。
     */
    public static boolean isLockTakenByEngine(Context ctx) {
        return !isLockTakenOver(ctx) && WallSwitchService.isActive(ctx);
    }

    // ==================== 落地 ====================

    /**
     * 按「当前启用库 + 系统真值」把系统调成应有的样子（幂等，可反复调用）。
     * 只做两件事：锁屏按锁屏库设置；桌面有库但引擎没激活时提示去系统选择器。
     * <b>不还原、不 clear</b> —— 关掉接管的唯一方式是用户在系统设置里换壁纸。
     *
     * @return {@link #RESULT_NEED_ACTIVATION} 需要用户去系统选择器确认激活引擎；
     *         {@link #RESULT_OK} 已调整；{@link #RESULT_NONE} 无需改动
     */
    public static int apply(Context ctx) {
        LibraryStore.Library homeLib = LibraryStore.enabledLibForScope(ctx, true);
        LibraryStore.Library lockLib = LibraryStore.enabledLibForScope(ctx, false);

        // ---- 桌面：有启用库但引擎未激活 → 请用户去系统选择器确认 ----
        if (homeLib != null && !isHomeTakenOver(ctx)) {
            return RESULT_NEED_ACTIVATION;
        }

        // ---- 锁屏：有锁屏库就设成那张；没有就不动（引擎已盖住锁屏） ----
        if (lockLib != null) {
            return applyLock(ctx, lockLib) ? RESULT_OK : RESULT_NONE;
        }
        return RESULT_NONE;
    }

    /**
     * 抽屉「保存当前壁纸」（智能选源，纯 IO，调用方放后台线程）：
     * <ul>
     *   <li>接管中：导出引擎正在显示的那张（库文件已是无损 PNG，直接复制进相册，必定拿得到）</li>
     *   <li>未接管：把系统桌面/锁屏壁纸备份到相册（仅作人工留档，App 不会再去还原它）</li>
     * </ul>
     *
     * @return 空串表示成功；否则是失败描述
     */
    public static String saveCurrentWallpaper(Context ctx) {
        StringBuilder log = new StringBuilder();
        String stamp = stamp();
        log.append("保存时间：").append(stamp).append('\n');
        if (isHomeTakenOver(ctx)) {
            log.append("来源：引擎正在显示的壁纸（接管中）\n");
            String fail = exportCurrentLibraryImage(ctx, stamp, log);
            writeSaveLog(ctx, log.toString());
            return fail;
        }
        log.append("来源：系统壁纸（未接管）\n");
        StringBuilder fail = new StringBuilder();
        String homeName = "wallswitch_" + stamp + "_home.png";
        String r = saveWallpaper(ctx, WallpaperManager.FLAG_SYSTEM, homeName);
        if (r.isEmpty()) {
            log.append("桌面壁纸：已存 ").append(homeName).append('\n');
        } else {
            log.append("桌面壁纸：失败 — ").append(r).append('\n');
            fail.append("桌面：").append(r);
        }
        String lockName = "wallswitch_" + stamp + "_lock.png";
        if (!hasSeparateLockWallpaper(ctx) || isLockTakenOver(ctx)) {
            // 锁屏跟随桌面（没单独设），或锁屏本来就是我们设的：没有"用户的锁屏壁纸"可备份
            log.append("锁屏壁纸：跳过（无独立锁屏壁纸）\n");
        } else {
            String rl = saveWallpaper(ctx, WallpaperManager.FLAG_LOCK, lockName);
            if (rl.isEmpty()) {
                log.append("锁屏壁纸：已存 ").append(lockName).append('\n');
            } else {
                log.append("锁屏壁纸：失败 — ").append(rl).append('\n');
                if (fail.length() > 0) {
                    fail.append("；");
                }
                fail.append("锁屏：").append(rl);
            }
        }
        writeSaveLog(ctx, log.toString());
        return fail.toString();
    }

    /** 系统是否单独设过锁屏壁纸（与桌面不同）——只有不同才存在"用户的锁屏壁纸"可备份。 */
    private static boolean hasSeparateLockWallpaper(Context ctx) {
        try {
            WallpaperManager wm = WallpaperManager.getInstance(ctx);
            return wm.getWallpaperId(WallpaperManager.FLAG_LOCK)
                    != wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM);
        } catch (Exception e) {
            return true;
        }
    }

    /** 接管中保存：把库中当前指针指向的图（=引擎正在显示的）复制进相册。 */
    private static String exportCurrentLibraryImage(Context ctx, String stamp, StringBuilder log) {
        LibraryStore.Library lib = LibraryStore.enabledLibForScope(ctx, true);
        if (lib == null) {
            return "没有启用中的桌面库";
        }
        String id = Switcher.getCurrent(ctx, lib.id, true);
        File full = id == null ? null : WallpaperStore.getFullFile(ctx, id);
        if (full == null || !full.exists()) {
            return "没有正在显示的壁纸可保存";
        }
        String name = "wallswitch_" + stamp + ".png";
        try (InputStream in = new FileInputStream(full)) {
            boolean written;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                written = saveStreamToAlbum(ctx, name, in);
            } else {
                File outFile = new File(ctx.getFilesDir(), name);
                try (OutputStream out = new FileOutputStream(outFile)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                    written = true;
                }
            }
            log.append(written ? "已导出 " + name + "\n" : "导出失败（MediaStore 未写入）\n");
            return written ? "" : "写入相册失败";
        } catch (Exception | OutOfMemoryError e) {
            log.append("导出异常：").append(e).append('\n');
            return "导出异常：" + e;
        }
    }

    /** 时间戳（文件名用，如 20260928_153005）。 */
    private static String stamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
    }

    /**
     * 把某张图设为锁屏壁纸，并记下系统返回的壁纸 id（用于判定「锁屏是否由本 App 接管」）。
     *
     * @return 系统返回的新壁纸 id；0 表示失败
     */
    public static int setLockFromFile(Context ctx, File file) {
        if (file == null || !file.exists()) {
            return 0;
        }
        Bitmap bitmap = WallpaperStore.decodeBounded(file, WallpaperStore.maxWallpaperDim(ctx));
        if (bitmap == null) {
            return 0;
        }
        try {
            WallpaperManager wm = WallpaperManager.getInstance(ctx);
            // 必须显式传 FLAG_LOCK：简化版 setBitmap(bitmap) 会连锁屏一起改
            int newId = wm.setBitmap(bitmap, null, true, WallpaperManager.FLAG_LOCK);
            if (newId != 0) {
                prefs(ctx).edit().putInt(KEY_LOCK_ID, newId).apply();
            }
            return newId;
        } catch (Exception e) {
            return 0;
        } finally {
            bitmap.recycle();
        }
    }

    // ==================== 内部实现 ====================

    /** 保证锁屏显示为该库当前那一张（没有指针就推进一张）。 */
    private static boolean applyLock(Context ctx, LibraryStore.Library lib) {
        String currentId = Switcher.getCurrent(ctx, lib.id, false);
        File file = currentId == null ? null : WallpaperStore.getFullFile(ctx, currentId);
        if (file == null || !file.exists()) {
            // 还没有指针（或文件丢了）：推进一张，Switcher 内部会走 setLockFromFile
            Switcher.next(ctx, lib.id, false);
            currentId = Switcher.getCurrent(ctx, lib.id, false);
            file = currentId == null ? null : WallpaperStore.getFullFile(ctx, currentId);
            if (file == null || !file.exists()) {
                return false;
            }
        }
        // 已经是我们的图就不必重设（幂等，避免每次打开 App 都重设一次锁屏）
        if (isLockTakenOver(ctx)) {
            return false;
        }
        return setLockFromFile(ctx, file) != 0;
    }

    /** 在指定 MediaStore 集合的子目录里按文件名找本 App 写入的条目（WallSwitchService 的公共日志也复用）。 */
    static Uri findEntry(Context ctx, Uri collection, String name, String dir) {
        try (Cursor c = ctx.getContentResolver().query(
                collection,
                new String[]{MediaStore.MediaColumns._ID},
                MediaStore.MediaColumns.DISPLAY_NAME + "=? AND "
                        + MediaStore.MediaColumns.RELATIVE_PATH + "=?",
                new String[]{name, dir + "/"},
                null)) {
            if (c != null && c.moveToFirst()) {
                return ContentUris.withAppendedId(collection, c.getLong(0));
            }
        } catch (Exception e) {
            // 查询失败按没有条目处理
        }
        return null;
    }

    /** 把保存结果日志写到公共 Download/WallSwitch（低版本写内部存储），尽力而为。 */
    private static void writeSaveLog(Context ctx, String content) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentResolver cr = ctx.getContentResolver();
                Uri existing = findEntry(ctx, MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        SAVE_LOG_NAME, SAVE_LOG_DIR);
                if (existing != null) {
                    cr.delete(existing, null, null);
                }
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, SAVE_LOG_NAME);
                values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, SAVE_LOG_DIR);
                Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) {
                    Log.e(LOG_TAG, "MediaStore 插入日志文件失败");
                    return;
                }
                try (OutputStream out = cr.openOutputStream(uri)) {
                    if (out != null) {
                        out.write(content.getBytes(StandardCharsets.UTF_8));
                    }
                }
            } else {
                try (OutputStream out = new FileOutputStream(
                        new File(ctx.getFilesDir(), SAVE_LOG_NAME))) {
                    out.write(content.getBytes(StandardCharsets.UTF_8));
                }
            }
        } catch (Exception e) {
            Log.e(LOG_TAG, "写保存日志失败", e);
        }
    }

    /** 把系统当前某个范围的壁纸画成 PNG 存进公共相册（低版本存内部存储）。 */
    private static String saveWallpaper(Context ctx, int which, String name) {
        StringBuilder why = new StringBuilder();
        Bitmap bitmap = readWallpaperBitmap(ctx, which, why);
        if (bitmap == null) {
            Log.e(LOG_TAG, "读不到壁纸(" + which + ")：" + why);
            return "读不到当前壁纸 — " + why;
        }
        try {
            boolean written;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                written = saveToMediaStore(ctx, name, bitmap);
            } else {
                try (OutputStream out = new FileOutputStream(new File(ctx.getFilesDir(), name))) {
                    written = bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                }
            }
            if (!written) {
                Log.e(LOG_TAG, "写入 " + name + " 失败");
                return "写入失败（PNG 压缩或 MediaStore 写入未成功）";
            }
            Log.i(LOG_TAG, "已存 " + name + "（" + bitmap.getWidth() + "x" + bitmap.getHeight() + "）");
            return "";
        } catch (Exception | OutOfMemoryError e) {
            Log.e(LOG_TAG, "保存 " + name + " 抛异常", e);
            return "保存异常：" + e;
        } finally {
            bitmap.recycle();
        }
    }

    /**
     * 多路兜底读当前壁纸：不同 ROM 拦的点不同（MagicOS 对 getDrawable 死认
     * READ_EXTERNAL_STORAGE，授予照片权限也不折算），依次试到成功为止。
     *
     * @param why 全部失败时通过它带回每条路的具体死因
     */
    private static Bitmap readWallpaperBitmap(Context ctx, int which, StringBuilder why) {
        WallpaperManager wm = WallpaperManager.getInstance(ctx);
        // 清掉进程内缓存，确保读到当前真正生效的那张
        wm.forgetLoadedWallpaper();

        // 路 1：getDrawable（标准路径，画成新 Bitmap）
        try {
            Drawable drawable = wm.getDrawable(which);
            if (drawable != null) {
                int w = drawable.getIntrinsicWidth();
                int h = drawable.getIntrinsicHeight();
                if (w > 0 && h > 0) {
                    Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    Canvas canvas = new Canvas(bitmap);
                    drawable.setBounds(0, 0, w, h);
                    drawable.draw(canvas);
                    Log.i(LOG_TAG, "读壁纸(" + which + ")成功：getDrawable");
                    return bitmap;
                }
                why.append("getDrawable 尺寸 ").append(w).append("x").append(h).append("；");
            } else {
                why.append("getDrawable 返回 null；");
            }
        } catch (Throwable t) {
            Log.w(LOG_TAG, "getDrawable(" + which + ") 失败", t);
            why.append("getDrawable 异常 ").append(t).append("；");
        }

        // 路 2：getWallpaperFile（拿原始文件描述符直接解码，绕过 drawable 那层的权限检查）
        try {
            ParcelFileDescriptor pfd = wm.getWallpaperFile(which);
            if (pfd != null) {
                Bitmap bitmap = BitmapFactory.decodeFileDescriptor(pfd.getFileDescriptor());
                try {
                    pfd.close();
                } catch (IOException ignored) {
                }
                if (bitmap != null) {
                    Log.i(LOG_TAG, "读壁纸(" + which + ")成功：getWallpaperFile");
                    return bitmap;
                }
                why.append("getWallpaperFile 解码失败；");
            } else {
                why.append("getWallpaperFile 返回 null；");
            }
        } catch (Throwable t) {
            Log.w(LOG_TAG, "getWallpaperFile(" + which + ") 失败", t);
            why.append("getWallpaperFile 异常 ").append(t).append("；");
        }

        return null;
    }

    /** 把输入流原样写进公共相册（库里的全图已是无损 PNG，接管中保存时直接复制字节）。 */
    private static boolean saveStreamToAlbum(Context ctx, String name, InputStream in) {
        ContentResolver cr = ctx.getContentResolver();
        Uri existing = findEntry(ctx, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, name, SAVED_DIR);
        if (existing != null) {
            try {
                cr.delete(existing, null, null);
            } catch (Exception ignored) {
            }
        }
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "image/png");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, SAVED_DIR);
        Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            return false;
        }
        try (OutputStream out = cr.openOutputStream(uri)) {
            if (out == null) {
                return false;
            }
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean saveToMediaStore(Context ctx, String name, Bitmap bitmap) {
        ContentResolver cr = ctx.getContentResolver();
        // 同名旧存档先删掉，避免 MediaStore 自动改名出现 (1) 副本
        Uri existing = findEntry(ctx, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, name, SAVED_DIR);
        if (existing != null) {
            try {
                cr.delete(existing, null, null);
            } catch (Exception ignored) {
            }
        }
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "image/png");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, SAVED_DIR);
        Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            return false;
        }
        try (OutputStream out = cr.openOutputStream(uri)) {
            if (out == null) {
                return false;
            }
            return bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (Exception e) {
            return false;
        }
    }
}
