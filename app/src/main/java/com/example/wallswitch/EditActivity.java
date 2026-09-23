package com.example.wallswitch;

import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.util.List;

/**
 * 添加时编辑页：全屏手势裁剪，确认后按当前手势导出并入库（默认桌面+锁屏都应用），
 * 取消则丢弃收件箱文件。
 *
 * v3.4 起裁剪区铺满整屏：取景框比例 = 屏幕比例 = 壁纸上屏区域，预览与实况一致。
 * v3.5 起右上角提供两个「桌面图标预览」开关：
 *   网格图标 = 内置示意网格（无需准备，近似）；
 *   图片图标 = 叠加自己的首页截图（已抠图，只留图标与文字）。首页截图是<b>全局设置</b>，
 *   只需在设置抽屉里设一次并保存在应用目录，这里直接读取，不用每次重选；
 *   若还没设过，点该按钮会直接让你选一张，选完也会顺手存成全局底图。
 */
public class EditActivity extends AppCompatActivity {

    // 收件箱 id 的 Intent extra key
    public static final String EXTRA_INBOX_ID = "inbox_id";
    // 目标壁纸库 id 的 Intent extra key
    public static final String EXTRA_LIB_ID = "lib_id";
    // 解码与导出尺寸上限见 WallpaperStore.maxWallpaperDim（按屏幕长边自适应，避免上屏被放大）
    // 预览开关的关闭态底色（半透明黑）；开启态用品牌色
    private static final int TOGGLE_OFF_COLOR = 0x8C000000;

    private CropView cropView;
    // 本次编辑用的分辨率上限（onCreate 里按屏幕长边算好，解码与导出共用同一个值）
    private int maxDim;
    private String inboxId;
    private String libId;

    private LauncherPreviewOverlay overlay;
    private ImageButton toggleMock;
    private ImageButton toggleShot;
    private ActivityResultLauncher<PickVisualMediaRequest> shotLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit);
        // 桌面图标预览浮层：需要真实系统栏高度，才能把状态栏与 Dock 画在实际上屏的位置
        overlay = findViewById(R.id.launcher_overlay);
        toggleMock = findViewById(R.id.btn_icon_preview);
        toggleShot = findViewById(R.id.btn_shot_overlay);
        shotLauncher = registerForActivityResult(
                new ActivityResultContracts.PickVisualMedia(), this::onShotPicked);
        // 全面屏/刘海屏适配：只让浮层（提示与按钮）避开状态栏与底部手势条；
        // 裁剪区本身保持满屏，取景框比例才等于壁纸上屏区域（否则预览与实况不一致）
        InsetsHelper.apply(this, R.id.edit_controls,
                (left, top, right, bottom) -> {
                    if (overlay != null) {
                        overlay.setInsets(top, bottom);
                    }
                });
        setupLauncherPreview();
        // 已设过全局底图就直接加载好，右上角按钮一点即可叠加，不用再选图
        loadSavedOverlayAsync();
        inboxId = getIntent().getStringExtra(EXTRA_INBOX_ID);
        libId = getIntent().getStringExtra(EXTRA_LIB_ID);
        if (libId == null || LibraryStore.get(this, libId) == null) {
            // 未指定或库已删除：回退到第一个库，没有库则建默认库
            List<LibraryStore.Library> libs = LibraryStore.load(this);
            libId = libs.isEmpty() ? LibraryStore.create(this, null).id : libs.get(0).id;
        }
        maxDim = WallpaperStore.maxWallpaperDim(this);
        cropView = findViewById(R.id.crop_view);
        // 解码收件箱原图（超采样防 OOM）交给手势视图
        File inboxFile = WallpaperStore.getInboxFile(this, inboxId);
        Bitmap bitmap = WallpaperStore.decodeBounded(inboxFile, maxDim);
        if (bitmap == null) {
            Toast.makeText(this, R.string.decode_failed, Toast.LENGTH_SHORT).show();
            WallpaperStore.cancelImport(this, inboxId);
            finish();
            return;
        }
        cropView.setBitmap(bitmap);
        Button btnConfirm = findViewById(R.id.btn_confirm);
        btnConfirm.setOnClickListener(v -> onConfirm());
        Button btnCancel = findViewById(R.id.btn_cancel);
        btnCancel.setOnClickListener(v -> onCancel());
    }

    /** 确认：按当前手势导出裁剪结果并入库，返回主页后 onResume 继续处理下一张。 */
    private void onConfirm() {
        Bitmap result = cropView.export(maxDim);
        if (result == null) {
            Toast.makeText(this, R.string.save_failed, Toast.LENGTH_SHORT).show();
            WallpaperStore.cancelImport(this, inboxId);
            finish();
            return;
        }
        try {
            WallpaperStore.confirmImport(this, inboxId, result, libId);
        } catch (Exception e) {
            Toast.makeText(this, R.string.save_failed, Toast.LENGTH_SHORT).show();
        }
        finish();
    }

    /** 取消：丢弃收件箱文件并返回。 */
    private void onCancel() {
        WallpaperStore.cancelImport(this, inboxId);
        finish();
    }

    /**
     * 「桌面图标预览」两个开关：
     * 左（网格）= 显示/隐藏内置示意网格；
     * 右（图片）= 显示/隐藏首页图标层的叠加；全局底图还没设过时，先去相册选一张。
     */
    private void setupLauncherPreview() {
        if (overlay == null || toggleMock == null || toggleShot == null) {
            return;
        }
        refreshPreviewToggles();
        toggleMock.setOnClickListener(v -> {
            boolean mockShowing = overlay.getVisibility() == View.VISIBLE
                    && overlay.getMode() == LauncherPreviewOverlay.MODE_MOCK;
            if (mockShowing) {
                overlay.setVisibility(View.GONE);
            } else {
                overlay.setMode(LauncherPreviewOverlay.MODE_MOCK);
                overlay.setVisibility(View.VISIBLE);
            }
            refreshPreviewToggles();
        });
        toggleShot.setOnClickListener(v -> {
            boolean shotShowing = overlay.getVisibility() == View.VISIBLE
                    && overlay.getMode() == LauncherPreviewOverlay.MODE_SCREENSHOT
                    && overlay.hasScreenshot();
            if (shotShowing) {
                overlay.setVisibility(View.GONE);
                refreshPreviewToggles();
                return;
            }
            if (overlay.hasScreenshot()) {
                // 已经加载好全局底图，直接叠上
                overlay.setMode(LauncherPreviewOverlay.MODE_SCREENSHOT);
                overlay.setVisibility(View.VISIBLE);
                refreshPreviewToggles();
                return;
            }
            PickVisualMediaRequest.Builder builder = new PickVisualMediaRequest.Builder();
            builder.setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE);
            shotLauncher.launch(builder.build());
        });
    }

    /** 开关底色反映当前叠加状态：网格亮 = 示意网格模式；图片亮 = 首页图标层模式。 */
    private void refreshPreviewToggles() {
        if (overlay == null || toggleMock == null || toggleShot == null) {
            return;
        }
        boolean showing = overlay.getVisibility() == View.VISIBLE;
        boolean shotMode = showing && overlay.getMode() == LauncherPreviewOverlay.MODE_SCREENSHOT
                && overlay.hasScreenshot();
        int onColor = getColor(R.color.brand);
        toggleMock.setBackgroundTintList(
                ColorStateList.valueOf(showing && !shotMode ? onColor : TOGGLE_OFF_COLOR));
        toggleShot.setBackgroundTintList(
                ColorStateList.valueOf(shotMode ? onColor : TOGGLE_OFF_COLOR));
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
                refreshPreviewToggles();
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
            // 顺手存成全局底图：以后进裁剪页（或换设备重装后重设一次）都不用再选
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
                overlay.setMode(LauncherPreviewOverlay.MODE_SCREENSHOT);
                overlay.setVisibility(View.VISIBLE);
                refreshPreviewToggles();
                if (saved) {
                    Toast.makeText(this, R.string.launcher_overlay_saved, Toast.LENGTH_SHORT).show();
                }
            });
        }, "overlay-key").start();
    }
}
