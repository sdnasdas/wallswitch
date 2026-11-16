package io.github.sdnasdas.wallswitch;

import android.net.Uri;
import android.provider.DocumentsContract;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.button.MaterialButton;

/**
 * 备份 / 还原 / 导出 / 清理冗余那一簇，含首启还原引导与元数据抢救提示。
 * 通用进度弹窗与图片缓存清理仍由 Activity 提供（还原要清缓存、盖进度）。
 */
final class BackupController {

    private final MainActivity a;

    BackupController(MainActivity a) {
        this.a = a;
    }

    void pickExportDir() {
        a.exportDirLauncher.launch(null);
    }

    void launchRestorePicker() {
        a.restoreZipLauncher.launch(new String[]{"application/zip", "*/*"});
    }

    /** 元数据抢救结果提示：把留档位置与捡回/丢弃计数说清楚。 */
    void maybeShowMetaNotice() {
        final MetaFiles.Notice notice = MetaFiles.takeNotice();
        if (notice == null) {
            return;
        }
        String dir = a.getFilesDir().getAbsolutePath();
        String msg;
        if (notice.salvaged) {
            msg = a.getString(R.string.meta_notice_salvaged, notice.fileName, dir + "/" + notice.fileName,
                    notice.kept, notice.dropped,
                    notice.archivePath == null ? a.getString(R.string.meta_archive_failed) : notice.archivePath);
        } else {
            msg = a.getString(R.string.meta_notice_lost, notice.fileName, dir + "/" + notice.fileName,
                    notice.archivePath == null ? a.getString(R.string.meta_archive_failed) : notice.archivePath);
        }
        new AppAlertDialogBuilder(a)
                .setTitle(R.string.meta_notice_title)
                .setMessage(msg)
                .setPositiveButton(R.string.confirm, null)
                .show();
    }

    /**
     * 首次启动引导：看起来像新装机（没写过库文件、库与壁纸都是空的）就引导从备份包还原。
     * 后台扫一遍默认目录（下载/wallswitch）：有备份包就列出来让用户点选一个（选中后走
     * 与手动选包完全相同的 inspect → 确认 → 还原链路）；没有就只给「手动选包 / 跳过」。
     * 只问一次（markPromptShown 落本机 key，且这个 key 不参与还原）；
     * 设置里那一行「从备份包还原」永久留着，什么时候想还原都可以。
     */
    void maybePromptRestore() {
        if (BackupStore.promptShown(a) || !BackupStore.looksLikeFreshInstall(a)) {
            return;
        }
        BackupStore.markPromptShown(a);
        new Thread(() -> {
            final java.util.List<BackupStore.BackupCandidate> found =
                    BackupStore.scanDefaultBackups(a.getApplicationContext());
            a.runOnUiThread(() -> {
                if (a.isFinishing() || a.isDestroyed()) {
                    return;
                }
                showRestoreOnboarding(found);
            });
        }, "restore-scan").start();
    }

    /** 首次引导弹窗：扫到备份包就列出来（点哪条还原哪条），否则给「授权默认目录 / 手动选包 / 跳过」。 */
    void showRestoreOnboarding(final java.util.List<BackupStore.BackupCandidate> found) {
        if (found.isEmpty()) {
            // 没找到备份包：一句话说明 + 三个竖排胶囊按钮（授权默认目录 / 手动选包 / 跳过）
            LinearLayout box = new LinearLayout(a);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(a.dp(20), a.dp(4), a.dp(20), a.dp(8));
            final AlertDialog dialog = new AppAlertDialogBuilder(a)
                    .setTitle(R.string.restore_ask_title)
                    .setMessage(R.string.restore_not_found)
                    .setView(box)
                    .create();
            box.addView(restoreCapsuleButton(dialog, R.string.restore_ask_grant,
                    a::launchRestoreTreePicker));
            box.addView(restoreCapsuleButton(dialog, R.string.restore_ask_go,
                    () -> a.restoreZipLauncher.launch(new String[]{"application/zip", "*/*"})));
            box.addView(restoreCapsuleButton(dialog, R.string.restore_ask_no, () -> { }));
            dialog.show();
            return;
        }
        // 列表弹窗不配 setMessage：配了列表会被量成零高（项目约定）
        final CharSequence[] labels = new CharSequence[found.size()];
        for (int i = 0; i < found.size(); i++) {
            labels[i] = backupCandidateLabel(found.get(i));
        }
        new AppAlertDialogBuilder(a)
                .setTitle(a.getString(R.string.restore_pick_title, found.size()))
                .setItems(labels, (dialog, which) -> onRestoreZipPicked(found.get(which).uri))
                .setNeutralButton(R.string.restore_ask_go, (dialog, which) ->
                        a.restoreZipLauncher.launch(new String[]{"application/zip", "*/*"}))
                .setNegativeButton(R.string.restore_ask_no, null)
                .show();
    }

    /** 引导弹窗的竖排胶囊按钮：点击后先关弹窗再执行动作。 */
    private MaterialButton restoreCapsuleButton(final AlertDialog dialog, int textRes,
            final Runnable action) {
        MaterialButton b = new MaterialButton(a);
        b.setText(textRes);
        b.setCornerRadius(a.dp(24));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = a.dp(8);
        b.setLayoutParams(lp);
        b.setOnClickListener(v -> {
            dialog.dismiss();
            action.run();
        });
        return b;
    }

    /** 拉起 SAF 目录选择器并尽量定位到默认目录（下载/wallswitch），让用户把目录授给我们。 */
    void launchRestoreTreePicker() {
        Uri initial = DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents", "primary:Download/wallswitch");
        try {
            a.restoreTreeLauncher.launch(initial);
        } catch (Exception e) {
            a.restoreTreeLauncher.launch(null);
        }
    }

    /** 目录授权回来：顺手存成导出目录，然后按 SAF 列目录里的备份包，有就直接进列表弹窗。 */
    void onRestoreTreePicked(Uri treeUri) {
        if (treeUri == null) {
            return;
        }
        WallpaperExporter.setTreeUri(a, treeUri);
        a.refreshSettings();
        Toast.makeText(a, R.string.export_dir_set, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final java.util.List<BackupStore.BackupCandidate> found =
                    BackupStore.scanTreeBackups(a.getApplicationContext(), treeUri);
            a.runOnUiThread(() -> {
                if (a.isFinishing() || a.isDestroyed()) {
                    return;
                }
                if (found.isEmpty()) {
                    Toast.makeText(a, R.string.restore_tree_empty, Toast.LENGTH_LONG).show();
                } else {
                    showRestoreOnboarding(found);
                }
            });
        }, "restore-tree-scan").start();
    }

    /** 列表项文案：从 wallswitch-backup-yyyyMMdd-HHmmss.zip 里抠时间，附大小；解析不了就显示原文件名。 */
    private String backupCandidateLabel(BackupStore.BackupCandidate c) {
        String time = null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("wallswitch-backup-(\\d{8})-(\\d{6})\\.zip").matcher(c.name);
        if (m.matches()) {
            try {
                java.util.Date d = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss",
                        java.util.Locale.US).parse(m.group(1) + "-" + m.group(2));
                time = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",
                        java.util.Locale.US).format(d);
            } catch (Exception ignored) {
            }
        }
        String size = c.size >= 1024 * 1024
                ? (c.size / (1024 * 1024)) + " MB"
                : Math.max(1, c.size / 1024) + " KB";
        return (time == null ? c.name : time) + " · " + size;
    }

    /** 选一个导出目录（SAF）：选完记住并申请持久化授权。 */
    void onExportDirPicked(Uri uri) {
        if (uri == null) {
            return;
        }
        WallpaperExporter.setTreeUri(a, uri);
        a.refreshSettings();
        Toast.makeText(a, R.string.export_dir_set, Toast.LENGTH_SHORT).show();
    }

    /** 取消导出目录（不清除已经导出的文件）。 */
    void confirmClearExportDir() {
        if (!WallpaperExporter.isConfigured(a)) {
            Toast.makeText(a, R.string.export_dir_unset, Toast.LENGTH_SHORT).show();
            return;
        }
        new AppAlertDialogBuilder(a)
                .setTitle(R.string.export_dir_title)
                .setMessage(R.string.export_dir_clear_msg)
                .setPositiveButton(R.string.confirm, (dialog, which) -> {
                    WallpaperExporter.clear(a);
                    a.refreshSettings();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** 把库内全部壁纸导出到导出目录（自定义 SAF 目录或默认目录，纯 IO，放后台线程）。 */
    void exportAllWallpapers() {
        if (!WallpaperExporter.canExport(a)) {
            Toast.makeText(a, R.string.export_need_dir, Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(a, R.string.export_running, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final int count = WallpaperExporter.exportAll(a);
            a.runOnUiThread(() -> {
                if (a.isFinishing() || a.isDestroyed()) {
                    return;
                }
                Toast.makeText(a, a.getString(R.string.export_done, count),
                        Toast.LENGTH_LONG).show();
            });
        }, "wallpaper-export-all").start();
    }

    /**
     * 清理冗余文件：后台扫出孤儿与双格式残留（留 JPEG 删 PNG），弹确认窗报完分类计数才动手。
     * 双格式那批的格式标记在 deleteRedundant 里作废，网格角标重算，不用刷网格。
     */
    void startPrune() {
        Toast.makeText(a, R.string.prune_scanning, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final WallpaperStore.RedundantScan scan =
                    WallpaperStore.scanRedundant(a.getApplicationContext());
            a.runOnUiThread(() -> {
                if (a.isFinishing() || a.isDestroyed()) {
                    return;
                }
                if (scan.count() == 0) {
                    Toast.makeText(a, R.string.prune_none, Toast.LENGTH_SHORT).show();
                    return;
                }
                new AppAlertDialogBuilder(a)
                        .setTitle(R.string.prune_title)
                        .setMessage(a.getString(R.string.prune_confirm, scan.count(),
                                scan.bytes() / (1024 * 1024), scan.fullDupes, scan.fullOrphans,
                                scan.originals, scan.thumbs))
                        .setPositiveButton(R.string.prune_go, (dialog, which) -> runPrune(scan))
                        .setNegativeButton(R.string.cancel, null)
                        .show();
            });
        }, "prune-scan").start();
    }

    private void runPrune(WallpaperStore.RedundantScan scan) {
        new Thread(() -> {
            final int deleted = WallpaperStore.deleteRedundant(scan);
            a.runOnUiThread(() -> {
                if (a.isFinishing() || a.isDestroyed()) {
                    return;
                }
                Toast.makeText(a, a.getString(R.string.prune_done, deleted,
                        scan.bytes() / (1024 * 1024)), Toast.LENGTH_LONG).show();
            });
        }, "prune-delete").start();
    }

    /** 备份整库：一个 zip 装下图、缩略图、两个 json 和 prefs，写进导出目录（没设自定义目录则落默认目录）。 */
    void startBackup() {
        if (!WallpaperExporter.canExport(a)) {
            Toast.makeText(a, R.string.export_need_dir, Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(a, R.string.backup_running, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final BackupStore.BackupResult r = BackupStore.backup(a.getApplicationContext());
            a.runOnUiThread(() -> {
                if (a.isFinishing() || a.isDestroyed()) {
                    return;
                }
                if (r.error != null) {
                    Toast.makeText(a, a.getString(R.string.backup_failed, r.error),
                            Toast.LENGTH_LONG).show();
                    return;
                }
                a.prefs.edit().putString("backup_last_name", r.name)
                        .putLong("backup_last_at", System.currentTimeMillis()).apply();
                a.refreshSettings();
                Toast.makeText(a, a.getString(R.string.backup_done, r.name, r.entries,
                        r.images, r.originals, r.thumbs), Toast.LENGTH_LONG).show();
            });
        }, "lib-backup").start();
    }

    /** 选中备份包后先回显现场，确认了才动本机数据。 */
    void onRestoreZipPicked(Uri uri) {
        if (uri == null) {
            return;
        }
        a.showProgress(R.string.restore_title, R.string.restore_reading);
        new Thread(() -> {
            final BackupStore.Manifest m = BackupStore.inspect(a.getApplicationContext(), uri);
            a.runOnUiThread(() -> {
                a.dismissTakeoverProgress();
                if (a.isFinishing() || a.isDestroyed()) {
                    return;
                }
                if (m == null) {
                    Toast.makeText(a, R.string.restore_bad_zip, Toast.LENGTH_LONG).show();
                    return;
                }
                confirmRestore(uri, m);
            });
        }, "backup-inspect").start();
    }

    void confirmRestore(Uri uri, BackupStore.Manifest m) {
        int local = WallpaperStore.load(a).size();
        new AppAlertDialogBuilder(a)
                .setTitle(R.string.restore_title)
                .setMessage(a.getString(R.string.restore_confirm,
                        m.backupTime == null || m.backupTime.isEmpty() ? "未知" : m.backupTime,
                        m.appVersion == null || m.appVersion.isEmpty() ? "未知" : m.appVersion,
                        m.libraryItems, m.images, m.originals, m.thumbs, m.bytes / (1024 * 1024), local))
                .setPositiveButton(R.string.restore_go, (dialog, which) -> runRestore(uri))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /**
     * 执行还原。还原后不必自己重排定时：onResume 本来就会 scheduleAll + 补切各走一遍
     * （自愈那条路），recreate 之后正好落到它上面。
     */
    void runRestore(Uri uri) {
        a.clearImages();
        Toast.makeText(a, R.string.restore_running, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final BackupStore.RestoreResult r = BackupStore.restore(a.getApplicationContext(), uri);
            // 还原是整批覆盖成品图文件，而且包里的 id 可能跟本机同名 —— 格式标记只能全抹，
            // 否则网格角标会继续报还原前那一档
            WallpaperStore.clearFullFormatMarks();
            a.runOnUiThread(() -> {
                if (a.isFinishing() || a.isDestroyed()) {
                    return;
                }
                a.clearImages();
                if (r.error != null) {
                    a.refreshCurrentPage();
                    Toast.makeText(a, a.getString(R.string.restore_failed, r.error),
                            Toast.LENGTH_LONG).show();
                    return;
                }
                Toast.makeText(a, a.getString(R.string.restore_done, r.items, r.prefsKeys,
                        r.images, r.droppedItems), Toast.LENGTH_LONG).show();
                a.recreate();
            });
        }, "lib-restore").start();
    }
}