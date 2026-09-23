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
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * 添加时编辑页：全屏手势裁剪，确认后按当前手势导出并入库（默认桌面+锁屏都应用），
 * 取消则丢弃收件箱文件。
 *
 * v3.4 起裁剪区铺满整屏：取景框比例 = 屏幕比例 = 壁纸上屏区域，预览与实况一致。
 * v3.5 起右上角提供两个「桌面图标预览」开关：
 *   网格图标 = 内置示意网格（无需准备，近似）；
 *   图片图标 = 选一张自己的首页截图叠加（截图与取景框同为整屏同比例，像素对齐，完全准确）。
 */
public class EditActivity extends AppCompatActivity {

    // 收件箱 id 的 Intent extra key
    public static final String EXTRA_INBOX_ID = "inbox_id";
    // 目标壁纸库 id 的 Intent extra key
    public static final String EXTRA_LIB_ID = "lib_id";
    // 解码与导出尺寸上限
    private static final int MAX_DIM = 2048;
    // 预览开关的关闭态底色（半透明黑）；开启态用品牌色
    private static final int TOGGLE_OFF_COLOR = 0x8C000000;
    // 首页截图的缓存文件名
    private static final String SHOT_CACHE_NAME = "launcher_shot.tmp";

    private CropView cropView;
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
        inboxId = getIntent().getStringExtra(EXTRA_INBOX_ID);
        libId = getIntent().getStringExtra(EXTRA_LIB_ID);
        if (libId == null || LibraryStore.get(this, libId) == null) {
            // 未指定或库已删除：回退到第一个库，没有库则建默认库
            List<LibraryStore.Library> libs = LibraryStore.load(this);
            libId = libs.isEmpty() ? LibraryStore.create(this, null).id : libs.get(0).id;
        }
        cropView = findViewById(R.id.crop_view);
        // 解码收件箱原图（超采样防 OOM）交给手势视图
        File inboxFile = WallpaperStore.getInboxFile(this, inboxId);
        Bitmap bitmap = WallpaperStore.decodeBounded(inboxFile, MAX_DIM);
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
        Bitmap result = cropView.export(MAX_DIM);
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
     * 右（图片）= 显示/隐藏自己首页截图的叠加，还没有截图时先去相册选一张。
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
            PickVisualMediaRequest.Builder builder = new PickVisualMediaRequest.Builder();
            builder.setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE);
            shotLauncher.launch(builder.build());
        });
    }

    /** 开关底色反映当前叠加状态：网格亮 = 示意网格模式；图片亮 = 首页截图叠加模式。 */
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

    /** 选好首页截图：后台复制到缓存目录再采样解码，回主线程叠加显示。 */
    private void onShotPicked(Uri uri) {
        if (uri == null) {
            return;
        }
        new Thread(() -> {
            final Bitmap shot = loadScreenshot(uri);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || overlay == null) {
                    return;
                }
                if (shot == null) {
                    Toast.makeText(this, R.string.preview_failed, Toast.LENGTH_SHORT).show();
                    return;
                }
                overlay.setScreenshot(shot);
                overlay.setMode(LauncherPreviewOverlay.MODE_SCREENSHOT);
                overlay.setVisibility(View.VISIBLE);
                refreshPreviewToggles();
            });
        }, "shot-load").start();
    }

    /** 把选中的图片复制到缓存目录并采样解码（复用 decodeBounded 的采样逻辑防 OOM）。 */
    private Bitmap loadScreenshot(Uri uri) {
        File tmp = new File(getCacheDir(), SHOT_CACHE_NAME);
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(tmp)) {
            if (in == null) {
                return null;
            }
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        } catch (Exception e) {
            return null;
        }
        return WallpaperStore.decodeBounded(tmp, MAX_DIM);
    }
}
