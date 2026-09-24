package com.example.wallswitch;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.util.List;

/**
 * 编辑页：全屏手势裁剪。两种模式：
 * <ul>
 *   <li><b>导入模式</b>（{@link #EXTRA_INBOX_ID}）：添加时从收件箱取源图，确认后按当前手势导出并入库，
 *       取消则丢弃收件箱文件。</li>
 *   <li><b>重编模式</b>（{@link #EXTRA_ITEM_ID}）：长按壁纸格的「编辑铅笔」进入，
 *       源图 = 库内已存的全图，确认后覆盖原图（保留 id/标题/归属库），取消不动任何文件。</li>
 * </ul>
 *
 * <ul>
 *   <li>v3.4 起裁剪区铺满整屏：取景框比例 = 屏幕比例 = 壁纸上屏区域，预览与实况一致。</li>
 *   <li>v3.9 起源图解码挪到后台线程：页面立刻显示，中间显示「加载中…」（解码的是像素预算内
 *       最大的那张，主线程做会把页面卡住）。</li>
 *   <li>v3.9 起<b>单击取景区</b>切换「桌面图标预览」叠加，不再用右上角按钮。</li>
 *   <li>v3.9 起导出改成<b>区域解码</b>：拿着当前取景框的矩形回原图文件取那一块，
 *       所以放大多少倍都能导出满屏幕分辨率，不再依赖事先猜测的放大余量。</li>
 * </ul>
 */
public class EditActivity extends AppCompatActivity {

    // 收件箱 id 的 Intent extra key（导入模式）
    public static final String EXTRA_INBOX_ID = "inbox_id";
    // 已入库壁纸 id 的 Intent extra key（重编模式：长按壁纸格的编辑铅笔）
    public static final String EXTRA_ITEM_ID = "item_id";
    // 目标壁纸库 id 的 Intent extra key（仅导入模式用）
    public static final String EXTRA_LIB_ID = "lib_id";

    private CropView cropView;
    private Button btnConfirm;
    private LauncherPreviewOverlay overlay;
    private ActivityResultLauncher<PickVisualMediaRequest> shotLauncher;

    private String inboxId;
    private String itemId;
    private String libId;
    // 裁剪源图文件：导入模式 = 收件箱文件；重编模式 = 库内全图
    private File sourceFile;
    // 原图像素尺寸（区域解码要把取景框映射回原图坐标）
    private int originalWidth;
    private int originalHeight;
    // 导出上限：屏幕长边（比屏幕分辨率更大没有意义，系统还要再缩一次）
    private int maxDim;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit);
        overlay = findViewById(R.id.launcher_overlay);
        cropView = findViewById(R.id.crop_view);
        btnConfirm = findViewById(R.id.btn_confirm);
        shotLauncher = registerForActivityResult(
                new ActivityResultContracts.PickVisualMedia(), this::onShotPicked);
        // 全面屏/刘海屏适配：只让浮层（提示 / 加载中 / 按钮）避开状态栏与底部手势条；
        // 裁剪区本身保持满屏，取景框比例才等于壁纸上屏区域（否则预览与实况不一致）
        InsetsHelper.apply(this, R.id.edit_controls);
        setupLauncherPreview();
        // 已设过全局底图就直接加载好，单击即可叠加，不用再选图
        loadSavedOverlayAsync();
        maxDim = WallpaperStore.maxWallpaperDim(this);
        inboxId = getIntent().getStringExtra(EXTRA_INBOX_ID);
        itemId = getIntent().getStringExtra(EXTRA_ITEM_ID);
        libId = getIntent().getStringExtra(EXTRA_LIB_ID);
        if (itemId == null && (libId == null || LibraryStore.get(this, libId) == null)) {
            // 仅导入模式需要目标库：未指定或库已删除时回退到第一个库，没有库则建默认库
            List<LibraryStore.Library> libs = LibraryStore.load(this);
            libId = libs.isEmpty() ? LibraryStore.create(this, null).id : libs.get(0).id;
        }
        btnConfirm.setOnClickListener(v -> onConfirm());
        findViewById(R.id.btn_cancel).setOnClickListener(v -> onCancel());
        sourceFile = itemId != null
                ? WallpaperStore.getFullFile(this, itemId)
                : WallpaperStore.getInboxFile(this, inboxId);
        readOriginalBounds();
        startDecode();
    }

    /** 读原图像素尺寸（只读文件头，很快）：区域解码要把取景框映射回原图坐标。 */
    private void readOriginalBounds() {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(sourceFile.getAbsolutePath(), bounds);
        originalWidth = bounds.outWidth;
        originalHeight = bounds.outHeight;
    }

    /** 后台解码源图；期间显示「加载中…」并禁用确认。 */
    private void startDecode() {
        setLoading(true);
        new Thread(() -> {
            final Bitmap decoded = WallpaperStore.decodeCropSource(sourceFile);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    if (decoded != null) {
                        decoded.recycle();
                    }
                    return;
                }
                setLoading(false);
                if (decoded == null) {
                    Toast.makeText(this, R.string.decode_failed, Toast.LENGTH_SHORT).show();
                    if (itemId == null) {
                        WallpaperStore.cancelImport(this, inboxId);
                    }
                    finish();
                    return;
                }
                cropView.setBitmap(decoded);
            });
        }, "crop-decode").start();
    }

    /** 切换「加载中」状态：加载中隐藏不了图，也没图可导，所以同时把确认置灰。 */
    private void setLoading(boolean loading) {
        View box = findViewById(R.id.loading_box);
        if (box != null) {
            box.setVisibility(loading ? View.VISIBLE : View.GONE);
        }
        if (btnConfirm != null) {
            btnConfirm.setEnabled(!loading);
        }
    }

    /**
     * 确认：按当前取景框做区域解码（回原图取那一块）。
     * 导入模式入库为新壁纸；重编模式覆盖原壁纸文件（保留 id/标题/归属库）。
     * 区域解码拿不到（格式不支持等）时回退到「从内存位图裁」，再不行才报失败。
     */
    private void onConfirm() {
        Bitmap result = exportByRegion();
        if (result == null) {
            result = cropView.export(maxDim);
        }
        if (result == null) {
            Toast.makeText(this, R.string.save_failed, Toast.LENGTH_SHORT).show();
            if (itemId == null) {
                WallpaperStore.cancelImport(this, inboxId);
            }
            finish();
            return;
        }
        final Context appCtx = getApplicationContext();
        try {
            if (itemId != null) {
                WallpaperStore.overwrite(this, itemId, result);
                // 覆盖后立刻把改动推上屏（这张若是桌面/锁屏的当前壁纸），不等下一次切换。
                // 锁屏路径要解码 + setBitmap 系统调用，放后台线程；线程会跑在 finish() 之后，
                // 所以只能用 Application 上下文（拿 Activity 当 Context 会被 lint 判 context leak）
                new Thread(() -> {
                    WallpaperStore.Item item = WallpaperStore.get(appCtx, itemId);
                    if (item != null && item.libId != null && !item.libId.isEmpty()) {
                        Switcher.reapplyIfCurrent(appCtx, item.libId, itemId);
                    }
                }, "reapply-wallpaper").start();
            } else {
                WallpaperStore.confirmImport(this, inboxId, result, libId);
            }
        } catch (Exception e) {
            Toast.makeText(this, R.string.save_failed, Toast.LENGTH_SHORT).show();
        }
        finish();
    }

    /**
     * 区域解码导出：把「当前可见区域」从解码图坐标映射回原图坐标，直接从原图取那一块。
     * 放得越大，可见区域在原图上越小 —— 但取的是原图原生像素，所以始终能给出满屏幕分辨率，
     * 不再受「事先解码了一张多大的图」限制。
     */
    private Bitmap exportByRegion() {
        if (originalWidth <= 0 || originalHeight <= 0) {
            return null;
        }
        int srcW = cropView.getSourceWidth();
        int srcH = cropView.getSourceHeight();
        if (srcW <= 0 || srcH <= 0) {
            return null;
        }
        RectF visible = new RectF();
        if (!cropView.getVisibleSourceRect(visible)) {
            return null;
        }
        // 解码图 → 原图：两轴各自换算，避开 inSampleSize 向上取整带来的偏差
        float kx = originalWidth / (float) srcW;
        float ky = originalHeight / (float) srcH;
        Rect region = new Rect(
                Math.max(0, (int) Math.floor(visible.left * kx)),
                Math.max(0, (int) Math.floor(visible.top * ky)),
                Math.min(originalWidth, (int) Math.ceil(visible.right * kx)),
                Math.min(originalHeight, (int) Math.ceil(visible.bottom * ky)));
        if (region.width() <= 0 || region.height() <= 0) {
            return null;
        }
        return WallpaperStore.decodeRegion(sourceFile, region, maxDim);
    }

    /** 取消：导入模式丢弃收件箱文件；重编模式不动任何文件，直接返回。 */
    private void onCancel() {
        if (itemId == null) {
            WallpaperStore.cancelImport(this, inboxId);
        }
        finish();
    }

    /**
     * 「桌面图标预览」：单击取景区切换叠加。
     * 还没设过全局底图时，第一次单击会去相册选一张（选完存成全局底图，以后不用再选）。
     */
    private void setupLauncherPreview() {
        if (cropView == null || overlay == null) {
            return;
        }
        cropView.setOnTapListener(this::toggleLauncherPreview);
    }

    private void toggleLauncherPreview() {
        if (overlay == null) {
            return;
        }
        if (overlay.getVisibility() == View.VISIBLE) {
            overlay.setVisibility(View.GONE);
            return;
        }
        if (overlay.hasScreenshot()) {
            overlay.setVisibility(View.VISIBLE);
            return;
        }
        PickVisualMediaRequest.Builder builder = new PickVisualMediaRequest.Builder();
        builder.setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE);
        shotLauncher.launch(builder.build());
    }

    /** 读取全局底图（解码可能几十毫秒，放后台线程）。 */
    private void loadSavedOverlayAsync() {
        if (overlay == null) {
            return;
        }
        new Thread(() -> {
            final Bitmap saved = LauncherPreviewOverlay.loadSaved(EditActivity.this);
            if (saved == null) {
                return;
            }
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || overlay == null) {
                    saved.recycle();
                    return;
                }
                overlay.setScreenshot(saved);
            });
        }, "overlay-load").start();
    }

    /** 页内直接选图（还没设过全局底图时）：后台抠图 → 存成全局底图 → 叠加显示。 */
    private void onShotPicked(Uri uri) {
        if (uri == null) {
            return;
        }
        Toast.makeText(this, R.string.launcher_overlay_processing, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final Bitmap keyed = LauncherPreviewOverlay.keyedFromUri(this, uri);
            // 顺手存成全局底图：以后进裁剪页就不用再选
            final boolean saved = keyed != null && LauncherPreviewOverlay.save(this, keyed);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || overlay == null) {
                    if (keyed != null) {
                        keyed.recycle();
                    }
                    return;
                }
                if (keyed == null) {
                    Toast.makeText(this, R.string.launcher_overlay_failed, Toast.LENGTH_SHORT).show();
                    return;
                }
                overlay.setScreenshot(keyed);
                overlay.setVisibility(View.VISIBLE);
                if (saved) {
                    Toast.makeText(this, R.string.launcher_overlay_saved, Toast.LENGTH_SHORT).show();
                }
            });
        }, "overlay-key").start();
    }
}
