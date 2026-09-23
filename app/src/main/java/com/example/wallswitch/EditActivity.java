package com.example.wallswitch;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.util.List;

/**
 * 添加时编辑页：全屏手势裁剪，确认后按当前手势导出并入库（默认桌面+锁屏都应用），
 * 取消则丢弃收件箱文件。
 */
public class EditActivity extends AppCompatActivity {

    // 收件箱 id 的 Intent extra key
    public static final String EXTRA_INBOX_ID = "inbox_id";
    // 目标壁纸库 id 的 Intent extra key
    public static final String EXTRA_LIB_ID = "lib_id";
    // 解码与导出尺寸上限
    private static final int MAX_DIM = 2048;

    private CropView cropView;
    private String inboxId;
    private String libId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit);
        // 全面屏/刘海屏适配：裁剪页按钮区避开底部手势条、顶部避开状态栏
        InsetsHelper.apply(this, R.id.edit_root);
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
        reportCropInitIfBroken(bitmap);
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
     * 临时诊断：只有在裁剪视图的矩阵没能初始化时才把「视图尺寸 / 图片尺寸 / 缩放区间」
     * 追加到顶部提示行；正常情况下这行文案原样不变。
     * 用于定位「捏合无反应 + 拖动挪不动 + 导出等于原图」这类静默失效（无任何报错）。
     * 确认问题解决后可以整段删掉。
     */
    private void reportCropInitIfBroken(Bitmap bitmap) {
        TextView hint = findViewById(R.id.tv_edit_hint);
        if (hint == null) {
            return;
        }
        final int bmpW = bitmap.getWidth();
        final int bmpH = bitmap.getHeight();
        hint.postDelayed(() -> {
            if (isFinishing() || cropView.isMatrixReady()) {
                return;
            }
            hint.setText(getString(R.string.edit_hint)
                    + "\n[诊断] 裁剪未初始化：视图 " + cropView.getWidth() + "×" + cropView.getHeight()
                    + "，图片 " + bmpW + "×" + bmpH
                    + "，缩放 " + cropView.debugScaleRange());
        }, 500L);
    }
}
