package com.example.wallswitch;

import android.app.WallpaperManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * 「接管壁纸」总开关：App 级的接管意图，以及把意图落到系统上的策略。
 *
 * <h3>语义</h3>
 * 打开 = App 接管。每个范围能不能接管，由「有没有启用的库」决定：
 * <ul>
 *   <li>桌面：有启用库 → 交给动态壁纸引擎；没有 → <b>回退系统</b>（还原接管前那张并解除引擎）</li>
 *   <li>锁屏：有启用库 → App 用 setBitmap 设锁屏；没有 → <b>回退系统</b>（还原接管前那张）</li>
 * </ul>
 * 关闭 = 两个范围都还原成接管前的样子。
 *
 * <h3>为什么「意图」与「实际」要分开</h3>
 * 普通 App 没有 SET_WALLPAPER_COMPONENT 权限，桌面接管必须由用户在系统选择器里确认一次。
 * 所以 {@link #apply} 只做「能做的做掉、不该接管的还原」，需要用户确认时返回
 * {@link #RESULT_NEED_ACTIVATION}，由界面拉起选择器；用户取消时界面把意图关掉（开关回关）。
 *
 * <h3>接管情况判定用系统真值</h3>
 * 桌面看 {@link WallSwitchService#isActive}；锁屏看 getWallpaperId(FLAG_LOCK) 是否等于我们设进去时
 * 系统返回的那个 id（记在偏好里比对）—— 不是"我以为接管了"。
 *
 * 除 {@link #isEnabled}/{@link #setEnabled}/{@link #isHomeTakenOver}/{@link #isLockTakenOver}
 * 之外的方法都涉及系统调用或 IO，调用方应放在后台线程。
 */
public final class TakeoverManager {

    private static final String PREFS_NAME = "settings";
    private static final String KEY_ENABLED = "takeover_enabled";
    private static final String KEY_LOCK_ID = "takeover_lock_id";

    /** apply 结果：无需改动。 */
    public static final int RESULT_NONE = 0;
    /** apply 结果：已按意图调整完毕。 */
    public static final int RESULT_OK = 1;
    /** apply 结果：桌面该接管但引擎未激活，需要用户在系统选择器里确认。 */
    public static final int RESULT_NEED_ACTIVATION = 2;

    private TakeoverManager() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // ==================== 接管意图（开关） ====================

    /** 接管意图：用户是否打开了「接管壁纸」总开关。 */
    public static boolean isEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, enabled).apply();
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
     * 按「接管意图 + 当前启用库」把系统调成应有的样子（幂等，可反复调用）。
     *
     * @return {@link #RESULT_NEED_ACTIVATION} 需要用户确认激活引擎；
     *         {@link #RESULT_OK} 已调整；{@link #RESULT_NONE} 无需改动
     */
    public static int apply(Context ctx) {
        boolean intent = isEnabled(ctx);
        LibraryStore.Library homeLib = LibraryStore.enabledLibForScope(ctx, true);
        LibraryStore.Library lockLib = LibraryStore.enabledLibForScope(ctx, false);
        boolean changed = false;

        // ---- 桌面：有启用库才接管，否则还原回系统 ----
        boolean wantHome = intent && homeLib != null;
        if (isHomeTakenOver(ctx)) {
            if (!wantHome) {
                changed |= restoreHome(ctx);
            }
        } else if (wantHome) {
            // 该接管但引擎没激活：只能由用户在系统选择器里确认
            return RESULT_NEED_ACTIVATION;
        }

        // ---- 锁屏：有启用库就设成我们的图；没有时只在「引擎没盖着锁屏」的前提下才还原 ----
        // 引擎一激活就同时盖住桌面和锁屏：没设锁屏库时锁屏已被引擎顺带接管，
        // 这时还原/clear 锁屏没意义（画面上还是引擎），还会让接管状态显示错
        boolean wantLock = intent && lockLib != null;
        if (wantLock) {
            changed |= applyLock(ctx, lockLib);
        } else if (isLockTakenOver(ctx) && !isHomeTakenOver(ctx)) {
            changed |= restoreLock(ctx);
        }
        return changed ? RESULT_OK : RESULT_NONE;
    }

    /**
     * 打开接管前调用：把「接管前」的桌面与锁屏壁纸各存一份（各自有才存），
     * 关闭或某范围没有启用库时用来还原。纯 IO，调用方放后台线程。
     */
    public static void savePreviousWallpapers(Context ctx) {
        // 已经由我们接管的范围不存：那时读回来的是我们自己的画面，存它没意义
        if (!isHomeTakenOver(ctx)) {
            saveWallpaper(ctx, WallpaperManager.FLAG_SYSTEM, previousHomeFile(ctx));
        }
        if (!isLockTakenOver(ctx)) {
            saveWallpaper(ctx, WallpaperManager.FLAG_LOCK, previousLockFile(ctx));
        }
    }

    /** 关闭接管：两个范围都还原成接管前的样子。纯 IO，调用方放后台线程。 */
    public static boolean release(Context ctx) {
        boolean okHome = restoreHome(ctx);
        boolean okLock = restoreLock(ctx);
        prefs(ctx).edit().remove(KEY_LOCK_ID).apply();
        return okHome && okLock;
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

    /** 接管前的桌面壁纸存档。 */
    private static File previousHomeFile(Context ctx) {
        return new File(ctx.getFilesDir(), "previous_home.png");
    }

    /** 接管前的锁屏壁纸存档。 */
    private static File previousLockFile(Context ctx) {
        return new File(ctx.getFilesDir(), "previous_lock.png");
    }

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

    /** 还原桌面：有存档就设回去，没有就 clear() 退回系统默认（都会解除动态壁纸）。 */
    private static boolean restoreHome(Context ctx) {
        try {
            WallpaperManager wm = WallpaperManager.getInstance(ctx);
            File saved = previousHomeFile(ctx);
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

    /** 还原锁屏：有存档就设回去，没有就 clear(FLAG_LOCK) 让它跟随系统壁纸。 */
    private static boolean restoreLock(Context ctx) {
        try {
            WallpaperManager wm = WallpaperManager.getInstance(ctx);
            File saved = previousLockFile(ctx);
            if (saved.exists()) {
                Bitmap bitmap = BitmapFactory.decodeFile(saved.getAbsolutePath());
                if (bitmap != null) {
                    wm.setBitmap(bitmap, null, true, WallpaperManager.FLAG_LOCK);
                    bitmap.recycle();
                    return true;
                }
            }
            wm.clear(WallpaperManager.FLAG_LOCK);
            return true;
        } catch (Exception | OutOfMemoryError e) {
            return false;
        }
    }

    /** 把系统当前某个范围的壁纸画成 PNG 存到 file。 */
    private static boolean saveWallpaper(Context ctx, int which, File file) {
        Drawable drawable;
        try {
            WallpaperManager wm = WallpaperManager.getInstance(ctx);
            // 清掉进程内缓存，确保读到当前真正生效的那张
            wm.forgetLoadedWallpaper();
            drawable = wm.getDrawable(which);
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
            try (OutputStream out = new FileOutputStream(file)) {
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
}
