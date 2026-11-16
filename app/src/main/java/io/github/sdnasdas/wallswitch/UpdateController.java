package io.github.sdnasdas.wallswitch;

import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/**
 * 检查更新 / 下载 / 安装那一簇（原 MainActivity「检查更新」分节）。
 * 状态与轮询都收在这里，Activity 只留 {@code updateStatus()/onUpdateRowTapped()} 两个委托给设置页调。
 */
final class UpdateController {

    private final MainActivity a;
    private String status = "";
    private final Handler tick = new Handler(Looper.getMainLooper());
    // 本次更新的目标（弹窗确认下载时记下）：下载失败后点这一行直接按原目标重下
    private String lastSource;
    private String lastVersion;

    UpdateController(MainActivity a) {
        this.a = a;
    }

    String status() {
        return status;
    }

    private void setStatus(int res) {
        setStatus(a.getString(res));
    }

    private void setStatus(String text) {
        status = text;
        androidx.fragment.app.Fragment f = a.getSupportFragmentManager().findFragmentById(R.id.settings_container);
        if (f instanceof SettingsFragment) {
            ((SettingsFragment) f).refreshUpdateRow();
        }
        androidx.fragment.app.Fragment secondary = a.getSupportFragmentManager()
                .findFragmentById(R.id.settings_secondary_container);
        if (secondary instanceof SettingsFragment) {
            ((SettingsFragment) secondary).refreshUpdateRow();
        }
    }

    /** 启动时静默检查一次（「自动检查更新」开着才查）：只在发现新版时打扰，失败静默。 */
    void checkOnLaunch() {
        UpdateDownloader.pruneInstalled(a);
        bindState();
        if (!UpdateChecker.autoCheck(a)) {
            return;
        }
        UpdateChecker.checkAsync(a, (info, error) -> {
            if (error == null && info != null && !a.isFinishing() && !a.isDestroyed()
                    && UpdateDownloader.state(a) != UpdateDownloader.DOWNLOADING
                    && UpdateChecker.isNewer(info.version, UpdateChecker.localVersion(a))) {
                showUpdateDialog(info, UpdateChecker.source(a));
            }
        });
    }

    /** 「检查更新」那一行点下去干什么，完全由下载状态决定：下载中问要不要取消、有包就装、其余去检查。 */
    void onRowTapped() {
        int state = UpdateDownloader.state(a);
        if (state == UpdateDownloader.DOWNLOADING) {
            new AppAlertDialogBuilder(a)
                    .setTitle(R.string.update_check_title)
                    .setMessage(a.getString(R.string.update_downloading, UpdateDownloader.percent()))
                    .setPositiveButton(R.string.update_cancel, (d, which) -> {
                        UpdateDownloader.cancel();
                        // 目标一并忘掉：取消后再点这一行是重新检查，而不是照原目标续下
                        lastSource = lastVersion = null;
                        bindState();
                    })
                    .setNegativeButton(R.string.close, null)
                    .show();
            return;
        }
        if (state == UpdateDownloader.READY) {
            installReadyPackage();
            return;
        }
        if (state == UpdateDownloader.FAILED && lastVersion != null) {
            startDownload(lastSource, lastVersion);
            return;
        }
        checkNow();
    }

    /** 按下载状态刷那一行；只有下载中才留一个 300ms 的轮询，其余状态立刻停手。 */
    private void bindState() {
        int state = UpdateDownloader.state(a);
        tick.removeCallbacks(poller);
        if (state == UpdateDownloader.DOWNLOADING) {
            setStatus(a.getString(R.string.update_downloading_tap, UpdateDownloader.percent()));
            tick.postDelayed(poller, 300);
            return;
        }
        if (state == UpdateDownloader.READY) {
            setStatus(a.getString(R.string.update_ready, UpdateDownloader.readyVersion(a)));
        } else if (state == UpdateDownloader.FAILED) {
            setStatus(R.string.update_failed);
        } else {
            setStatus(a.getString(R.string.update_local_version, UpdateChecker.localVersion(a)));
        }
    }

    void installReadyPackage() {
        if (!UpdateDownloader.hasInstallPermission(a)) {
            UpdateDownloader.openInstallPermissionSettings(a);
            Toast.makeText(a, R.string.update_need_install_perm, Toast.LENGTH_LONG).show();
            return;
        }
        if (!UpdateDownloader.install(a)) {
            Toast.makeText(a, R.string.update_pkg_gone, Toast.LENGTH_SHORT).show();
            bindState();
        }
    }

    /**
     * 下载完成后的提示窗：点「立即安装」走与那一行完全相同的安装链路，
     * 点「稍后安装」或按返回都只是关掉——包还在本机，之后回设置点那一行随时能装。
     */
    void showInstallDialog() {
        String version = UpdateDownloader.readyVersion(a);
        if (version == null) {
            return;
        }
        new AppAlertDialogBuilder(a)
                .setTitle(R.string.update_done_title)
                .setMessage(a.getString(R.string.update_done_msg, version))
                .setPositiveButton(R.string.update_install_now,
                        (d, which) -> installReadyPackage())
                .setNegativeButton(R.string.update_install_later, null)
                .show();
    }

    /** 手动检查：结果只改这一行的文字，发现新版才弹窗。 */
    void checkNow() {
        String source = UpdateChecker.source(a);
        setStatus(R.string.update_checking);
        UpdateChecker.checkAsync(a, source, (info, error) -> {
            if (a.isFinishing() || a.isDestroyed()) {
                return;
            }
            if (error != null || info == null) {
                setStatus(R.string.update_check_failed);
                // 把具体死因亮给用户（网络不通/HTTP 错误一眼可辨），不再只有干巴巴的失败
                Toast.makeText(a, a.getString(R.string.update_check_failed)
                        + "：" + (error != null ? error.toString() : "无返回"), Toast.LENGTH_LONG).show();
                return;
            }
            if (!UpdateChecker.isNewer(info.version, UpdateChecker.localVersion(a))) {
                setStatus(R.string.update_latest);
                return;
            }
            setStatus(a.getString(R.string.update_new_title) + " v" + info.version);
            showUpdateDialog(info, source);
        });
    }

    /**
     * 新版弹窗。本机已经有这个包了（上回下完没装，重进 App 又查到新版）时主按钮直接给
     * 「立即安装」，并把文案对上本机那个包的版本；否则确认后开始下载。
     */
    void showUpdateDialog(UpdateChecker.Info info, String source) {
        String ready = UpdateDownloader.readyVersion(a);
        // 本机包不比远端那一版旧才走安装，免得文案写 v3.75、装进去的是旧的 v3.74
        boolean installFirst = ready != null && !UpdateChecker.isNewer(info.version, ready);
        new AppAlertDialogBuilder(a)
                .setTitle(R.string.update_new_title)
                .setMessage(a.getString(installFirst
                                ? R.string.update_new_ready_msg : R.string.update_new_msg,
                        installFirst ? ready : info.version, UpdateChecker.localVersion(a)))
                .setPositiveButton(installFirst ? R.string.update_install_now
                                : R.string.update_download,
                        (d, which) -> {
                            if (installFirst) {
                                installReadyPackage();
                            } else {
                                startDownload(source, info.version);
                            }
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 记下目标版本后开下：进度条马上转起来，失败时这一行也认得该重下哪个版本。 */
    void startDownload(String source, String version) {
        lastSource = source;
        lastVersion = version;
        UpdateDownloader.start(a, source, version);
        bindState();
    }

    /** 下载进度轮询：状态一离开下载中就收尾刷行；下完弹小窗问要不要立刻装，失败只 Toast。 */
    private final Runnable poller = new Runnable() {
        @Override
        public void run() {
            if (a.isFinishing() || a.isDestroyed()) {
                return;
            }
            int state = UpdateDownloader.state(a);
            if (state == UpdateDownloader.DOWNLOADING) {
                bindState();
                return;
            }
            bindState();
            if (state == UpdateDownloader.READY) {
                showInstallDialog();
            } else if (state == UpdateDownloader.FAILED) {
                Toast.makeText(a, R.string.update_failed,
                        Toast.LENGTH_SHORT).show();
            }
        }
    };

    /** 从安装页/安装授权页返回：清掉已装包，再按当前下载状态重刷那一行。 */
    void refreshOnResume() {
        UpdateDownloader.pruneInstalled(a);
        bindState();
    }

    void dispose() {
        tick.removeCallbacksAndMessages(null);
    }
}