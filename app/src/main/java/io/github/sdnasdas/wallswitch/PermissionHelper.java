package io.github.sdnasdas.wallswitch;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Toast;

/**
 * 通知 / 存档权限与电池优化白名单那一簇。
 * 权限申请的 Launcher 仍注册在 Activity（生命周期要求），结果回调经 Activity 委托到这里。
 */
final class PermissionHelper {

    private final MainActivity a;

    PermissionHelper(MainActivity a) {
        this.a = a;
    }

    /** 确保通知可用：Android 13+ 申请权限；系统级关闭时提示并跳到通知设置页。 */
    void ensureNotificationsEnabled() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && a.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            a.notifyPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            return;
        }
        if (!SwitchNotifier.canNotify(a)) {
            Toast.makeText(a, R.string.notify_disabled_hint, Toast.LENGTH_LONG).show();
            openNotificationSettings();
        }
    }

    /** 通知权限申请结果：未授权时说明后果（自动切换提示将看不到）。 */
    void onNotifyPermissionResult(boolean granted) {
        if (!granted) {
            Toast.makeText(a, R.string.notify_permission_denied, Toast.LENGTH_LONG).show();
        }
    }

    /** 存档权限申请结果：授权就继续保存当前壁纸，拒绝则放弃。 */
    void onSavePermissionResult(boolean granted) {
        if (granted) {
            a.doSaveCurrentWallpaper();
        } else {
            Toast.makeText(a, "未授权照片读取，无法保存当前壁纸", Toast.LENGTH_LONG).show();
            a.refreshTakeoverUi();
        }
    }

    /** 跳转本应用的系统通知设置页（华为/荣耀在此重新允许通知）。 */
    void openNotificationSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, a.getPackageName());
            a.startActivity(intent);
        } catch (Exception ignored) {
        }
    }

    /** 是否已允许忽略电池优化。 */
    boolean isIgnoringBatteryOptimizations() {
        PowerManager pm = (PowerManager) a.getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(a.getPackageName());
    }

    /** 检查电池优化白名单：未忽略且未提示过时弹一次引导。 */
    void maybePromptBattery() {
        if (isIgnoringBatteryOptimizations()) {
            return;
        }
        if (a.prefs.getBoolean("battery_prompted", false)) {
            return;
        }
        a.prefs.edit().putBoolean("battery_prompted", true).apply();
        new AppAlertDialogBuilder(a)
                .setTitle(R.string.battery_prompt_title)
                .setMessage(R.string.battery_prompt_msg)
                .setPositiveButton(R.string.confirm, (dialog, which) -> requestIgnoreBattery())
                // 荣耀/华为还需在应用详情里开启「自启动/后台运行」
                .setNeutralButton(R.string.app_details, (dialog, which) -> openAppDetails())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** 打开本应用的系统详情页（荣耀/华为在此开启自启动、后台运行白名单）。 */
    void openAppDetails() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + a.getPackageName()));
            a.startActivity(intent);
        } catch (Exception ignored) {
        }
    }

    /** 跳转系统「忽略电池优化」授权页（个别机型不支持该 action 时静默）。 */
    void requestIgnoreBattery() {
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + a.getPackageName()));
            a.startActivity(intent);
        } catch (Exception ignored) {
        }
    }
}