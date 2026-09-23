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
 * 右上角一个「桌面图标预览」开关：叠加自己的首页截图（已抠图，只留图标与文字），
 * 用来判断构图主体会不会被桌面图标挡住。首页截图是**全局设置**，只需在设置抽屉里设一次并保存在
 * 应用目录，这里直接读取；若还没设过，点该按钮会直接让你选一张，选完也会顺手存成全局底图。
 */
public class EditActivity extends AppCompatActivity {

    // 收件箱 id 的 Intent extra key
    public static final String EXTRA_INBOX_ID = "inbox_id";
    // 目标壁纸库 id 的 Intent extra key
    public static final String EXTRA_LIB_ID = "lib_id";
    // 导出尺寸上限：按屏幕长边自适应（见 WallpaperStore.maxWallpaperDim）
    private static final int MAX_DIM_FALLBACK = 2048;
    // 预览开关的关闭态底色（半透明黑）；开启态用品牌色
    private static final int TOGGLE_OFF_COLOR = 0x8C000000;

    private CropView cropView;
    private String inboxId;
    private String libId;

    // 导出上限（onCreate 里按屏幕长边算好；导出比屏幕分辨率更大没有意义，系统还要再缩一次）
    private int maxDim = MAX_DIM_FALLBACK;

    private LauncherPreviewOverlay overlay;
    private ImageButton toggleShot;
    private ActivityResultLauncher<PickVisualMediaRequest> shotLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit);
        overlay = findViewById(R.id.launcher_overlay);
        toggleShot = findViewById(R.id.btn_shot_overlay);
        shotLauncher = registerForActivityResult(
                new ActivityResultContracts.PickVisualMedia(), this::onShotPicked);
        // 全面屏/刘海屏适配：只让浮层（提示与按钮）避开状态栏与底部手势条；
        // 裁剪区本身保持满屏，取景框比例才等于壁纸上屏区域（否则预览与实况不一致）
        InsetsHelper.apply(this, R.id.edit_controls);
        setupLauncherPreview();
        // 已设过全局底图就直接加载好，右上角按钮一点即可叠加，不用再选图
        loadSavedOverlayAsync();
        maxDim = WallpaperStore.maxWallpaperDim(this);
        inboxId = getIntent().getStringExtra(EXTRA_INBOX_ID);
        libId = getIntent().getStringExtra(EXTRA_LIB_ID);
        if (libId == null || LibraryStore.get(this, libId) == null) {
            // 未指定或库已删除：回退到第一个库，没有库则建默认库
            List<LibraryStore.Library> libs = LibraryStore.load(this);
            libId = libs.isEmpty() ? LibraryStore.create(this, null).id : libs.get(0).id;
        }
        cropView = findViewById(R.id.crop_view);
        // 源图按「像素预算内尽量解大」解码（见 decodeCropSource）：这是清晰度的关键，
        // 用固定上限解码会把大图的信息白白扔掉一大半
        File inboxFile = WallpaperStore.getInboxFile(this, inboxId);
        Bitmap bitmap = WallpaperStore.decodeCropSource(inboxFile);
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
     * 「桌面图标预览」开关：显示/隐藏首页图标层的叠加；
     * 全局底图还没设过时，先去相册选一张（选完会存成全局底图，以后不用再选）。
     */
    private void setupLauncherPreview() {
        if (overlay == null || toggleShot == null) {
            return;
        }
        refreshShotToggle();
        toggleShot.setOnClickListener(v -> {
            if (overlay.getVisibility() == View.VISIBLE) {
                overlay.setVisibility(View.GONE);
                refreshShotToggle();
                return;
            }
            if (overlay.hasScreenshot()) {
                // 已经加载好全局底图，直接叠上
                overlay.setVisibility(View.VISIBLE);
                refreshShotToggle();
                return;
            }
            PickVisualMediaRequest.Builder builder = new PickVisualMediaRequest.Builder();
            builder.setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE);
            shotLauncher.launch(builder.build());
        });
    }

    /** 开关底色反映当前是否在叠加：亮 = 正在叠加首页图标层。 */
    private void refreshShotToggle() {
        if (overlay == null || toggleShot == null) {
            return;
        }
        boolean showing = overlay.getVisibility() == View.VISIBLE && overlay.hasScreenshot();
        toggleShot.setBackgroundTintList(ColorStateList.valueOf(
                showing ? getColor(R.color.brand) : TOGGLE_OFF_COLOR));
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
                refreshShotToggle();
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
                refreshShotToggle();
                if (saved) {
                    Toast.makeText(this, R.string.launcher_overlay_saved, Toast.LENGTH_SHORT).show();
                }
            });
        }, "overlay-key").start();
    }
}
