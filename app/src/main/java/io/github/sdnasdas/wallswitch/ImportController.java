package io.github.sdnasdas.wallswitch;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Process;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 导入管线那一簇：相册多选 / 外部分享 / 图片直链下载 → 收件箱 → 逐张取景或批量默认导入。
 * 批量落盘的状态（进度弹窗、停止标记、运行中标记）都收在这里；Activity 只留生命周期入口的委托。
 */
final class ImportController {

    private final MainActivity a;
    /** ImportActivity 交过来的这一批收件箱 id；onResume / onNewIntent 消费一次。 */
    private ArrayList<String> sharedImportIds;
    /** 多张导入的选择弹窗或批量落盘正在进行：压住 onResume 的逐张自动打开。 */
    private boolean bulkChoiceShowing;
    private static volatile boolean bulkImportRunning;
    private AlertDialog bulkProgress;
    private TextView bulkProgressText;
    private volatile boolean bulkStopRequested;
    private String openingInboxId;

    ImportController(MainActivity a) {
        this.a = a;
    }

    /** 批量选择弹窗或批量落盘正在进行。 */
    boolean busy() {
        return bulkChoiceShowing || bulkImportRunning;
    }

    /** 打开系统相册多选。 */
    void launchPicker() {
        PickVisualMediaRequest.Builder builder = new PickVisualMediaRequest.Builder();
        builder.setMediaType(ActivityResultContracts.PickVisualMedia.ImageAndVideo.INSTANCE);
        a.pickLauncher.launch(builder.build());
    }

    /**
     * 多选回调：后台线程逐张复制进收件箱（主线程同步复制多张大图易卡顿、易瞬断失败），
     * 完成后按真实成功/失败数量提示，并打开第一张待编辑项。
     */
    void onPicked(List<Uri> uris) {
        if (uris == null || uris.isEmpty()) {
            return;
        }
        View btnAdd = a.findViewById(R.id.btn_add);
        btnAdd.setEnabled(false);
        // 已经进了某个库再导入：库就是眼前这个，记到待编辑项上，编辑页不再问
        final String targetLib = a.libraryOpen ? a.currentLibId() : null;
        new Thread(() -> {
            // 批量复制跑后台优先级：调度器偏向小核低频，同样的活发热明显低，代价是略慢
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            int success = 0;
            int failed = 0;
            ArrayList<String> imported = new ArrayList<>();
            for (Uri uri : uris) {
                try {
                    imported.add(WallpaperStore.importToInbox(a, uri).id);
                    success++;
                } catch (Exception e) {
                    failed++;
                }
            }
            List<String> pending = WallpaperStore.pendingInbox(a);
            // lambda 捕获要求实际 final：先把计数定稿
            final int okCount = success;
            final int failCount = failed;
            final ArrayList<String> importedIds = imported;
            a.runOnUiThread(() -> {
                btnAdd.setEnabled(true);
                if (failCount > 0) {
                    if (okCount > 0) {
                        Toast.makeText(a, a.getString(R.string.import_partial, okCount, failCount),
                                Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(a, R.string.import_all_failed, Toast.LENGTH_LONG).show();
                    }
                } else {
                    Toast.makeText(a, a.getString(R.string.import_done, okCount),
                            Toast.LENGTH_SHORT).show();
                }
                if (targetLib != null) {
                    for (String id : importedIds) {
                        WallpaperStore.putPendingTarget(a, id, targetLib);
                    }
                }
                if (!importedIds.isEmpty()) {
                    openEdit(importedIds.get(0));
                } else if (!pending.isEmpty()) {
                    openEdit(pending.get(0));
                }
            });
        }, "inbox-import").start();
    }

    /** 外部导入页交过来的 id 列表；取完把 extra 抹掉，避免旋转重建再消费一次。 */
    void readSharedIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        ArrayList<String> ids = intent.getStringArrayListExtra(ImportActivity.EXTRA_IMPORTED_IDS);
        if (ids == null) {
            return;
        }
        intent.removeExtra(ImportActivity.EXTRA_IMPORTED_IDS);
        if (!ids.isEmpty()) {
            sharedImportIds = ids;
        }
    }

    /**
     * 处理一批外部导入。单张直接进裁剪页；多张先问「逐张取景 / 全部默认导入」。
     * 返回 true 表示这次 onResume 已经被这批导入占用，别再走逐张自动打开的兜底。
     */
    boolean consumeSharedImport() {
        if (sharedImportIds == null) {
            return false;
        }
        if (bulkChoiceShowing || bulkImportRunning) {
            // 上一批还在收：先留着，下次进前台再处理，别叠两个弹窗
            return true;
        }
        ArrayList<String> ids = sharedImportIds;
        sharedImportIds = null;
        List<String> pending = WallpaperStore.pendingInbox(a);
        ids.retainAll(pending);
        if (ids.isEmpty()) {
            return false;
        }
        if (ids.size() == 1) {
            // 分享进来时人正停在一个库详情里：那个库就是明确目标，不必再问
            String targetLib = a.libraryOpen ? a.currentLibId() : null;
            if (targetLib != null) {
                WallpaperStore.putPendingTarget(a, ids.get(0), targetLib);
            }
            openEdit(ids.get(0));
            return true;
        }
        checkAndShowBatchChoice(ids);
        return true;
    }

    /** 批量里夹了 MP4 就不能默认收（要选封面与 3 秒区间），退回现有逐张流程。 */
    private void checkAndShowBatchChoice(ArrayList<String> ids) {
        bulkChoiceShowing = true;
        final String targetLib = a.libraryOpen ? a.currentLibId() : null;
        if (targetLib != null) {
            for (String id : ids) {
                WallpaperStore.putPendingTarget(a, id, targetLib);
            }
        }
        final Context app = a.getApplicationContext();
        new Thread(() -> {
            boolean video = false;
            for (String id : ids) {
                try {
                    if (LivePhotoFiles.isMp4(WallpaperStore.getInboxFile(app, id))) {
                        video = true;
                        break;
                    }
                } catch (Exception ignored) {
                }
            }
            final boolean hasVideo = video;
            a.runOnUiThread(() -> {
                bulkChoiceShowing = false;
                if (a.isFinishing() || a.isDestroyed()) {
                    return;
                }
                if (hasVideo) {
                    openEdit(ids.get(0));
                    return;
                }
                showBatchChoice(ids, targetLib);
            });
        }, "import-batch-check").start();
    }

    private void showBatchChoice(ArrayList<String> ids, String targetLib) {
        bulkChoiceShowing = true;
        new AppAlertDialogBuilder(a)
                .setTitle(a.getString(R.string.import_batch_title, ids.size()))
                .setMessage(R.string.import_batch_message)
                .setPositiveButton(R.string.import_batch_all_default, (dialog, which) -> {
                    if (targetLib != null) {
                        // 人就在这个库里分享进来的：批量默认直接用这个库
                        bulkChoiceShowing = false;
                        confirmPendingDefaults(ids, targetLib);
                        return;
                    }
                    // 批量默认没有取景这一步；库仍然让用户自己选，取消就退回这个选择
                    LibChooser.show(a, a.getString(R.string.import_pick_lib_title),
                            libId -> {
                                bulkChoiceShowing = false;
                                confirmPendingDefaults(ids, libId);
                            },
                            () -> {
                                if (!a.isFinishing() && !a.isDestroyed()) {
                                    showBatchChoice(ids, null);
                                }
                            });
                })
                .setNegativeButton(R.string.import_batch_one_by_one, (dialog, which) -> {
                    bulkChoiceShowing = false;
                    openEdit(ids.get(0));
                })
                .setCancelable(false)
                .show();
    }

    /**
     * 全部按默认取景导入：先按裁剪页 fit-cover 的同一几何从原图中心裁一块（窗口比例），再走
     * confirmImport；这样详情页 fitCenter 看到的也是上屏那块，不会露出整张原图。裁完把矩形写回
     * src_w/crop_*，下次「调整取景」恢复到同一构图。目标库由选库弹窗传进来，这里不再回退
     * 最近操作的库。
     */
    private void confirmPendingDefaults(ArrayList<String> ids, String chosenLibId) {
        final String libId = chosenLibId;
        final Context app = a.getApplicationContext();
        final ArrayList<String> batch = new ArrayList<>(ids);
        final int count = batch.size();
        final int[] window = windowSize();
        final int maxDim = WallpaperStore.maxWallpaperDim(app);
        // 这批已经由用户选过库：逐条记上，后面失败/停止再打开时不再重复问
        for (String id : batch) {
            WallpaperStore.putPendingTarget(app, id, libId);
        }
        bulkStopRequested = false;
        showBulkProgress(count);
        bulkImportRunning = true;
        new Thread(() -> {
            // 50 张就是 50 次解码+编码，串行跑后台优先级，别并发抬峰值
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            int ok = 0;
            int fail = 0;
            try {
                for (String id : batch) {
                    if (bulkStopRequested) {
                        break;
                    }
                    File inbox = WallpaperStore.getInboxFile(app, id);
                    BitmapFactory.Options bounds = new BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(inbox.getAbsolutePath(), bounds);
                    Rect region = centerCoverRect(bounds.outWidth, bounds.outHeight,
                            window[0], window[1]);
                    Bitmap cropped = region == null ? null
                            : WallpaperStore.decodeRegion(inbox, region, maxDim);
                    try {
                        WallpaperStore.confirmImport(app, id, cropped, libId);
                        if (cropped != null && region != null) {
                            WallpaperStore.saveCropRect(app, id, bounds.outWidth, bounds.outHeight,
                                    region.left, region.top, region.right, region.bottom);
                        }
                        ok++;
                    } catch (Exception | OutOfMemoryError e) {
                        fail++;
                    } finally {
                        if (cropped != null && !cropped.isRecycled()) {
                            cropped.recycle();
                        }
                    }
                    final int completed = ok + fail;
                    a.runOnUiThread(() -> {
                        if (bulkProgressText != null && !a.isFinishing() && !a.isDestroyed()) {
                            bulkProgressText.setText(a.getString(
                                    R.string.import_progress_count, completed, count));
                        }
                    });
                }
            } finally {
                bulkImportRunning = false;
            }
            final int okCount = ok;
            final int left = count - ok;
            a.runOnUiThread(() -> {
                dismissBulkProgress();
                if (a.isFinishing() || a.isDestroyed()) {
                    return;
                }
                if (left == 0) {
                    Toast.makeText(a, a.getString(R.string.import_batch_done, okCount),
                            Toast.LENGTH_LONG).show();
                } else if (okCount == 0) {
                    Toast.makeText(a, a.getString(R.string.import_batch_none, left),
                            Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(a, a.getString(R.string.import_batch_left, okCount, left),
                            Toast.LENGTH_LONG).show();
                }
                a.refreshCurrentPage();
            });
        }, "import-batch-confirm").start();
    }

    /** 批量落盘进度：带 N/M 计数和「停止」，停止后当前这张跑完就收手，剩余留在收件箱。 */
    private void showBulkProgress(int total) {
        dismissBulkProgress();
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        int pad = (int) (a.getResources().getDisplayMetrics().density * 20);
        box.setPadding(pad, pad, pad, pad);
        ProgressBar bar = new ProgressBar(a);
        box.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        bulkProgressText = new TextView(a);
        bulkProgressText.setText(a.getString(R.string.import_progress_count, 0, total));
        bulkProgressText.setPadding(pad / 2, 0, 0, 0);
        bulkProgressText.setTextColor(a.getColor(R.color.text_primary));
        box.addView(bulkProgressText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        bulkProgress = new AppAlertDialogBuilder(a)
                .setTitle(R.string.import_batch_progress_title)
                .setView(box)
                .setNegativeButton(R.string.import_batch_stop,
                        (dialog, which) -> bulkStopRequested = true)
                .setCancelable(false)
                .create();
        bulkProgress.show();
    }

    private void dismissBulkProgress() {
        if (bulkProgress != null) {
            try {
                bulkProgress.dismiss();
            } catch (Exception ignored) {
            }
            bulkProgress = null;
        }
        bulkProgressText = null;
    }

    /** 当前窗口的实际像素尺寸；裁剪页取景区始终满屏，批量默认也按它算画幅。 */
    @SuppressWarnings("deprecation")
    private int[] windowSize() {
        View decor = a.getWindow().getDecorView();
        if (decor.getWidth() > 0 && decor.getHeight() > 0) {
            return new int[]{decor.getWidth(), decor.getHeight()};
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Rect bounds = a.getWindowManager().getCurrentWindowMetrics().getBounds();
            return new int[]{bounds.width(), bounds.height()};
        }
        DisplayMetrics dm = new DisplayMetrics();
        a.getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        return new int[]{dm.widthPixels, dm.heightPixels};
    }

    /**
     * 与 {@link CropView} 的 fit-cover 默认构图同一几何：原图居中、按窗口比例取一块。
     * 原图更宽就左右裁，更高就上下裁；返回原图像素坐标。
     */
    private static Rect centerCoverRect(int srcW, int srcH, int viewW, int viewH) {
        if (srcW <= 0 || srcH <= 0 || viewW <= 0 || viewH <= 0) {
            return null;
        }
        if ((long) srcW * viewH > (long) viewW * srcH) {
            int w = Math.min(srcW, Math.max(1, Math.round(srcH * (float) viewW / viewH)));
            int left = (srcW - w) / 2;
            return new Rect(left, 0, left + w, srcH);
        }
        int h = Math.min(srcH, Math.max(1, Math.round(srcW * (float) viewH / viewW)));
        int top = (srcH - h) / 2;
        return new Rect(0, top, srcW, top + h);
    }

    /** 进程重启后收件箱也走同一分流，避免 MP4 被当成图片解码后删掉。 */
    void openEdit(String inboxId) {
        if (openingInboxId != null) {
            return;
        }
        openingInboxId = inboxId;
        new Thread(() -> {
            boolean video;
            try {
                video = LivePhotoFiles.isMp4(WallpaperStore.getInboxFile(a, inboxId));
            } catch (Exception e) {
                a.runOnUiThread(() -> {
                    openingInboxId = null;
                    WallpaperStore.cancelImport(a, inboxId);
                    if (!a.isFinishing() && !a.isDestroyed()) {
                        Toast.makeText(a, R.string.url_download_failed, Toast.LENGTH_LONG).show();
                    }
                });
                return;
            }
            a.runOnUiThread(() -> {
                if (a.isFinishing() || a.isDestroyed()
                        || !a.getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                    openingInboxId = null;
                    return;
                }
                if (!video) {
                    openingInboxId = null;
                    launchInboxEditor(inboxId, false);
                    return;
                }
                new AppAlertDialogBuilder(a)
                        .setTitle(R.string.video_detected_title)
                        .setMessage(R.string.video_detected_message)
                        .setNegativeButton(R.string.video_no, (dialog, which) -> cancelVideoInbox(inboxId))
                        .setPositiveButton(R.string.video_yes, (dialog, which) -> {
                            openingInboxId = null;
                            launchInboxEditor(inboxId, true);
                        })
                        .setOnCancelListener(dialog -> cancelVideoInbox(inboxId))
                        .show();
            });
        }, "inbox-type").start();
    }

    void cancelVideoInbox(String inboxId) {
        WallpaperStore.cancelImport(a, inboxId);
        openingInboxId = null;
        List<String> pending = WallpaperStore.pendingInbox(a);
        if (!pending.isEmpty()) {
            openEdit(pending.get(0));
        }
    }

    void launchInboxEditor(String inboxId, boolean video) {
        Intent intent = new Intent(a, video ? VideoTrimActivity.class : EditActivity.class);
        intent.putExtra(EditActivity.EXTRA_INBOX_ID, inboxId);
        a.startActivity(intent);
    }

    /**
     * 图片直链输入框：粘贴（不关窗，贴上还能接着改）/ 确认（校验 http(s) 前缀，不过就留着窗）/ 取消。
     * 三颗按钮排「粘贴 / 取消 / 确认」—— 粘贴离输入框动作最近，确认在最右拇指落点。
     */
    void showLinkInputDialog() {
        EditText input = new EditText(a);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        AlertDialog dialog = new AppAlertDialogBuilder(a)
                .setTitle(R.string.url_dialog_title)
                .setView(a.wrapInput(input, R.string.url_input_hint))
                .setNeutralButton(R.string.paste, null)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.confirm, null)
                .create();
        // 带回调的按钮默认一点就关窗：粘贴必须留着窗才能看到贴进去的内容，校验不过也不该白关一次
        dialog.setOnShowListener(d -> {
            Button paste = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            Button ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            paste.setOnClickListener(v -> pasteClipboardInto(input));
            ok.setOnClickListener(v -> {
                String url = input.getText().toString().trim();
                if (!(url.startsWith("http://") || url.startsWith("https://"))) {
                    Toast.makeText(a, R.string.url_invalid, Toast.LENGTH_LONG).show();
                    return;
                }
                dialog.dismiss();
                downloadFromUrl(url);
            });
        });
        dialog.show();
        input.requestFocus();
    }

    /** 把剪贴板文本贴进输入框；剪贴板是空的只提示，不动输入框内容。 */
    void pasteClipboardInto(EditText input) {
        try {
            ClipboardManager manager = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = manager == null ? null : manager.getPrimaryClip();
            CharSequence text = (clip == null || clip.getItemCount() == 0)
                    ? null : clip.getItemAt(0).getText();
            if (text == null || text.toString().trim().isEmpty()) {
                Toast.makeText(a, R.string.url_paste_empty, Toast.LENGTH_SHORT).show();
                return;
            }
            input.setText(text.toString().trim());
            input.setSelection(input.getText().length());
        } catch (Exception ignored) {
        }
    }

    /** 下载只写收件箱，入库仍由用户在编辑页确认。 */
    void downloadFromUrl(String url) {
        View btnAdd = a.findViewById(R.id.btn_add);
        btnAdd.setEnabled(false);
        // 从库里发起：这条就落到眼前这个库
        final String targetLib = a.libraryOpen ? a.currentLibId() : null;
        new Thread(() -> {
            String id = null;
            try {
                id = WallpaperStore.importUrlToInbox(a, url).id;
            } catch (Exception ignored) {
            }
            final String inboxId = id;
            a.runOnUiThread(() -> {
                btnAdd.setEnabled(true);
                if (inboxId == null) {
                    Toast.makeText(a, R.string.url_download_failed, Toast.LENGTH_LONG).show();
                    return;
                }
                Toast.makeText(a, R.string.url_download_done, Toast.LENGTH_SHORT).show();
                if (targetLib != null) {
                    WallpaperStore.putPendingTarget(a, inboxId, targetLib);
                }
                openEdit(inboxId);
            });
        }, "url-download").start();
    }

    /**
     * 当值闸门：这张正被某个库的桌面/锁屏当值，或被「一键设置」钉着时，重编与彻底删除都不放行。
     * 弹一句「正在应用：桌面、锁屏、一键设置」，只给一颗「知道了」。
     *
     * <p>刻意不留「我就要改」那颗出口：留了就等于没拦，而这条限制要保的正是
     * 「屏幕上那张 == 库里那张」这件事。怎么处理（切一张当值、改选图钉那张）不在这里教，
     * 弹窗只报原因 —— 文案一长就没人读。
     *
     * <p>整理态的<b>解除关联</b>与移动/复制不走这里 —— 它们只改归属表，一个字节都不碰。
     */
    boolean blockWhileInUse(WallpaperStore.Item item) {
        String where = Switcher.inUseWhere(a, item.id);
        if (where == null) {
            return false;
        }
        new AppAlertDialogBuilder(a)
                .setTitle(R.string.in_use_title)
                .setMessage(a.getString(R.string.in_use_msg, where))
                .setPositiveButton(R.string.in_use_ok, null)
                .show();
        return true;
    }

    /** 打开编辑页重新裁剪一张已入库壁纸（点壁纸格直接进入；确认后覆盖成品图，源是原图）。 */
    void openEditItem(WallpaperStore.Item item) {
        if (blockWhileInUse(item)) {
            return;
        }
        Intent intent = new Intent(a, EditActivity.class);
        intent.putExtra(EditActivity.EXTRA_ITEM_ID, item.id);
        // 从哪个库点进来的也带上：那张挂在几个库里时，编辑页要问「只改这个库」还是「同步修改」，
        // 分叉出来的新条目得有个明确归属的库（没有它就只能一律覆盖）
        String libId = a.itemActionLibId();
        if (libId != null) {
            intent.putExtra(EditActivity.EXTRA_LIB_ID, libId);
        }
        a.startActivity(intent);
    }
}