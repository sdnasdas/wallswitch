package com.example.wallswitch;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;

/**
 * 添加时编辑页：全屏手势裁剪，确认后按当前手势导出并入库（默认桌面+锁屏都应用），
 * 取消则丢弃收件箱文件。
 */
public class EditActivity extends AppCompatActivity {

    // 收件箱 id 的 Intent extra key
    public static final String EXTRA_INBOX_ID = "inbox_id";
    // 解码与导出尺寸上限
    private static final int MAX_DIM = 2048;

    private CropView cropView;
    private String inboxId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit);
        inboxId = getIntent().getStringExtra(EXTRA_INBOX_ID);
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
            WallpaperStore.confirmImport(this, inboxId, result, true, true);
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
}
