package io.github.sdnasdas.wallswitch;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.Toast;

import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;

/**
 * 全局「桌面图标预览底图」那一簇：选图抠图、查看/更换/清除菜单、棋盘格预览。
 * 在单张详情里单击画面触发的选图，选好回显时还要把详情预览打开（与 DetailController 协作）。
 */
final class LauncherOverlayController {

    private final MainActivity a;

    LauncherOverlayController(MainActivity a) {
        this.a = a;
    }

    /** 选一张首页截图作为全局「桌面图标预览底图」（抠图后存应用目录，之后不用再选）。 */
    void pick() {
        PickVisualMediaRequest.Builder builder = new PickVisualMediaRequest.Builder();
        builder.setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE);
        a.overlayLauncher.launch(builder.build());
    }

    /** 选好后的处理：后台抠图（把截图自带的壁纸变透明）→ 存成全局底图 → 回显状态。 */
    void onPicked(Uri uri) {
        if (uri == null) {
            a.overlayPickForDetail = false;
            return;
        }
        Toast.makeText(a, R.string.launcher_overlay_processing, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final Bitmap keyed = LauncherPreviewOverlay.keyedFromUri(a, uri);
            final boolean saved = keyed != null && LauncherPreviewOverlay.save(a, keyed);
            a.runOnUiThread(() -> {
                if (a.isFinishing() || a.isDestroyed()) {
                    return;
                }
                Toast.makeText(a,
                        saved ? R.string.launcher_overlay_saved : R.string.launcher_overlay_failed,
                        Toast.LENGTH_SHORT).show();
                a.refreshSettings();
                // 是在单张详情里单击画面触发的选图：选好就直接把预览打开
                boolean forDetail = a.overlayPickForDetail;
                a.overlayPickForDetail = false;
                if (forDetail && keyed != null && a.pageDetail.getVisibility() == View.VISIBLE) {
                    a.detailOverlay.setScreenshot(keyed);
                    a.enterDetailPreview();
                }
            });
        }, "overlay-key").start();
    }

    /** 清除全局预览底图（设置里长按）。 */
    void confirmClear() {
        if (!LauncherPreviewOverlay.overlayFile(a).exists()) {
            Toast.makeText(a, R.string.launcher_overlay_unset, Toast.LENGTH_SHORT).show();
            return;
        }
        new AppAlertDialogBuilder(a)
                .setTitle(R.string.launcher_overlay_title)
                .setMessage(R.string.launcher_overlay_clear_msg)
                .setPositiveButton(R.string.confirm, (dialog, which) -> {
                    LauncherPreviewOverlay.clear(a);
                    a.refreshSettings();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** 已设置底图后点按设置行：浮出查看/更换/清除菜单（长按仍是清除快捷方式）。 */
    void showMenu() {
        new AppAlertDialogBuilder(a)
                .setTitle(R.string.launcher_overlay_title)
                .setItems(new CharSequence[]{
                        a.getString(R.string.launcher_overlay_action_view),
                        a.getString(R.string.launcher_overlay_action_change),
                        a.getString(R.string.launcher_overlay_action_clear)},
                        (dialog, which) -> {
                            if (which == 0) {
                                showPreview();
                            } else if (which == 1) {
                                pick();
                            } else {
                                confirmClear();
                            }
                        })
                .show();
    }

    /** 预览对话框：在棋盘格衬底上展示抠好的透明底图，透明区域一眼可见。 */
    void showPreview() {
        ImageView image = new ImageView(a);
        image.setLayoutParams(new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (280 * a.getResources().getDisplayMetrics().density)));
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setPadding((int) (16 * a.getResources().getDisplayMetrics().density),
                (int) (16 * a.getResources().getDisplayMetrics().density),
                (int) (16 * a.getResources().getDisplayMetrics().density),
                (int) (16 * a.getResources().getDisplayMetrics().density));
        image.setBackground(checkerboard());
        ScrollView scroll = new ScrollView(a);
        scroll.addView(image);
        AlertDialog dialog = new AppAlertDialogBuilder(a)
                .setTitle(R.string.launcher_overlay_title)
                .setView(scroll)
                .setNegativeButton(R.string.close, null)
                .show();
        new Thread(() -> {
            final Bitmap saved = LauncherPreviewOverlay.loadSaved(a);
            a.runOnUiThread(() -> {
                if (saved == null) {
                    dialog.dismiss();
                    Toast.makeText(a, R.string.launcher_overlay_load_failed, Toast.LENGTH_SHORT).show();
                    return;
                }
                image.setImageBitmap(saved);
            });
        }, "overlay-preview").start();
    }

    /** 灰白棋盘格衬底（2×2 平铺单元），用来显出底图的透明区域。 */
    private Drawable checkerboard() {
        float cell = 12 * a.getResources().getDisplayMetrics().density;
        int size = (int) (cell * 2);
        Bitmap tile = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(tile);
        canvas.drawColor(0xFFF2F2F2);
        Paint gray = new Paint();
        gray.setColor(0xFFD9D9D9);
        canvas.drawRect(0, 0, cell, cell, gray);
        canvas.drawRect(cell, cell, size, size, gray);
        BitmapDrawable drawable = new BitmapDrawable(a.getResources(), tile);
        drawable.setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
        return drawable;
    }
}