package io.github.sdnasdas.wallswitch;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.widget.Toast;

/**
 * 桌面接管与「保存当前壁纸」那一簇。通用进度弹窗（showProgress/dismissTakeoverProgress）
 * 仍由 Activity 持有，备份/还原也用同一套。
 */
final class TakeoverController {

    private final MainActivity a;

    TakeoverController(MainActivity a) {
        this.a = a;
    }

    /** 关闭接管的唯一途径说明（点感叹号图标时弹）。 */
    void showInfoDialog() {
        new AppAlertDialogBuilder(a)
                .setTitle(R.string.takeover_info_title)
                .setMessage(R.string.takeover_info_msg)
                .setPositiveButton(R.string.takeover_info_open_settings, (d, which) -> {
                    try {
                        a.startActivity(new Intent(Intent.ACTION_SET_WALLPAPER));
                    } catch (Exception ignored) {
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * 启用：直接拉起系统动态壁纸选择器（本应用没有"关闭"入口，所以不需要任何存档步骤；
     * 要备份系统壁纸请用设置里的「保存当前壁纸」）。
     */
    void enable() {
        WallSwitchService.openActivator(a);
    }

    /**
     * 「保存当前壁纸」入口：接管中不需要权限（图就在库里），
     * 未接管要读系统壁纸，申请照片读取权限后直接读（旧版「所有文件访问」链路 v3.127 撤掉）。
     */
    void startSaveCurrentWallpaper() {
        if (TakeoverManager.isHomeTakenOver(a)) {
            doSaveCurrentWallpaper();
            return;
        }
        // Android 13+ 用照片权限（READ_MEDIA_IMAGES），更低版本对应 READ_EXTERNAL_STORAGE。
        // 个别 ROM（v3.28 那台 MagicOS）照片权限折算不成 WallpaperManager 认的
        // READ_EXTERNAL_STORAGE 会读不到 —— 读不到时 readWallpaperBitmap 的双路死因
        // 会跟着保存失败的 Toast 带回来，到底行不行交给这次真机实测。
        String perm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.READ_MEDIA_IMAGES
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (a.checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
            a.savePermLauncher.launch(perm);
            return;
        }
        doSaveCurrentWallpaper();
    }

    /** 后台执行保存，全程盖不可关闭的进度弹窗，结果用 Toast 告知。 */
    void doSaveCurrentWallpaper() {
        a.showProgress(R.string.save_wallpaper_title, R.string.save_wallpaper_working);
        new Thread(() -> {
            final String fail = TakeoverManager.saveCurrentWallpaper(a);
            a.runOnUiThread(() -> {
                a.dismissTakeoverProgress();
                if (a.isFinishing() || a.isDestroyed()) {
                    return;
                }
                CharSequence msg = fail.isEmpty()
                        ? a.getString(R.string.save_wallpaper_done)
                        : a.getString(R.string.save_wallpaper_failed, fail);
                Toast.makeText(a, msg, Toast.LENGTH_LONG).show();
            });
        }, "save-wallpaper").start();
    }

    /**
     * 把接管落地一次（对齐当前启用库：锁屏库 → 设锁屏；桌面有库但引擎没激活 → 提示去启用）。
     * 放后台线程，避免系统调用卡 UI。
     */
    void syncAsync() {
        new Thread(() -> {
            final int result = TakeoverManager.apply(a);
            a.runOnUiThread(() -> {
                if (a.isFinishing() || a.isDestroyed()) {
                    return;
                }
                if (result == TakeoverManager.RESULT_NEED_ACTIVATION) {
                    Toast.makeText(a, R.string.takeover_need_activate, Toast.LENGTH_LONG).show();
                }
                a.refreshTakeoverUi();
            });
        }, "takeover-sync").start();
    }
}