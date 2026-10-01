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
import android.widget.TextView;
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
 *   <li><b>重编模式</b>（{@link #EXTRA_ITEM_ID}）：点壁纸格直接进入（v3.86 起，原来是长按浮出铅笔），
 *       源图 = 库内留存的原图（v3.85 起；缺原图的存量壁纸退回成品图，并给一次性的「关联原图」入口），
 *       打开时按 library.json 里的裁剪矩形复原上次取景框；确认后覆盖成品图（保留 id/标题/归属库），
 *       取消不动任何文件。</li>
 * </ul>
 *
 * <ul>
 *   <li>v3.4 起裁剪区铺满整屏：取景框比例 = 屏幕比例 = 壁纸上屏区域，预览与实况一致。</li>
 *   <li>v3.9 起源图解码挪到后台线程：页面立刻显示，中间显示「加载中…」（解码的是像素预算内
 *       最大的那张，主线程做会把页面卡住）。</li>
 *   <li>v3.9 起<b>单击取景区</b>切换「桌面图标预览」叠加，不再用右上角按钮。</li>
 *   <li>v3.9 起导出改成<b>区域解码</b>：拿着当前取景框的矩形回原图文件取那一块，
 *       所以放大多少倍都能导出满屏幕分辨率，不再依赖事先猜测的放大余量。</li>
 *   <li>v3.85 起重编的源从成品图换成原图，并新增 {@link CropView#restoreSourceRect} 复原取景框 ——
 *       「裁上加裁」从这一刻结束，微调是在原生像素上重导而不是在旧成品图上再裁一刀。</li>
 * </ul>
 */
public class EditActivity extends AppCompatActivity {

    // 收件箱 id 的 Intent extra key（导入模式）
    public static final String EXTRA_INBOX_ID = "inbox_id";
    // 已入库壁纸 id 的 Intent extra key（重编模式：点壁纸格进入）
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
    // 本次编辑的源是不是原图：导入模式的收件箱文件、重编模式找到 originals/ 的都算。
    // 只有源是原图时才写裁剪参数 —— 源是成品图时矩形坐标不同源，读回来会给出错误构图
    private boolean sourceIsOriginal;
    // 待复原的矩形（原图坐标）；解码回来后换算成位图坐标再交给 CropView
    private RectF pendingRestoreRect;
    private View noticeBar;
    private TextView noticeText;
    private View noticeLinkButton;
    private ActivityResultLauncher<PickVisualMediaRequest> linkOriginalLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit);
        overlay = findViewById(R.id.launcher_overlay);
        cropView = findViewById(R.id.crop_view);
        btnConfirm = findViewById(R.id.btn_confirm);
        shotLauncher = registerForActivityResult(
                new ActivityResultContracts.PickVisualMedia(), this::onShotPicked);
        // 存量壁纸补原图用的第二个选择器：与「桌面图标预览底图」那条互不干扰
        linkOriginalLauncher = registerForActivityResult(
                new ActivityResultContracts.PickVisualMedia(), this::onOriginalPicked);
        noticeBar = findViewById(R.id.edit_notice_bar);
        noticeText = findViewById(R.id.tv_edit_notice);
        noticeLinkButton = findViewById(R.id.btn_link_original);
        if (noticeLinkButton != null) {
            noticeLinkButton.setOnClickListener(v -> pickOriginalForLinking());
        }
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
        chooseSource();
        readOriginalBounds();
        startDecode();
    }

    /**
     * 决定这次编辑的源，并顺手定下「复原还是默认构图」。四种情形：
     * <ol>
     *   <li>有原图 + 参数尺寸与原图对得上 → 源=原图，复原上次构图（微调就靠这一格）</li>
     *   <li>有原图 + 没参数（刚关联上的存量壁纸）→ 源=原图，默认构图</li>
     *   <li>有原图 + 参数尺寸对不上（换过原图）→ 源=原图，默认构图，提示位置已作废</li>
     *   <li>没原图（历史数据）→ 源=成品图，等同今天的行为，给「关联原图」按钮</li>
     * </ol>
     * 导入模式的收件箱文件本身就是原图，落进第 1/2 格（第一次多半没参数）。
     * 第 2、3 格的区分要等解码回来才知道参数是不是写给这张原图的，所以提示在
     * {@link #applyPendingRestore()} 里发。
     */
    private void chooseSource() {
        if (itemId == null) {
            sourceFile = WallpaperStore.getInboxFile(this, inboxId);
            sourceIsOriginal = true;
            return;
        }
        if (WallpaperStore.hasOriginal(this, itemId)) {
            sourceFile = WallpaperStore.getOriginalFile(this, itemId);
            sourceIsOriginal = true;
            WallpaperStore.Item item = WallpaperStore.get(this, itemId);
            if (item != null && item.hasCropRect()) {
                pendingRestoreRect = new RectF(item.cropLeft, item.cropTop,
                        item.cropRight, item.cropBottom);
            }
            return;
        }
        sourceFile = WallpaperStore.getFullFile(this, itemId);
        sourceIsOriginal = false;
        pendingRestoreRect = null;
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
                applyPendingRestore();
            });
        }, "crop-decode").start();
    }

    /**
     * 复原取景框。尺寸校验放在这里：{@link #readOriginalBounds()} 读的是当前 sourceFile 的文件头，
     * 「那六个参数是不是写给眼前这张原图的」只有在这儿能判定。
     * 对不上就当作没有参数（默认构图），并把提示换成「位置已作废」—— 不单独清除，
     * 因为下一次确认会用新原图的尺寸覆盖写回，一次编辑就自好了。
     */
    private void applyPendingRestore() {
        if (pendingRestoreRect == null) {
            // 没参数：有原图就安静地用默认构图，没原图才需要提示并给「关联原图」按钮
            showNotice(sourceIsOriginal ? 0 : R.string.edit_notice_no_original, !sourceIsOriginal);
            return;
        }
        if (originalWidth <= 0 || originalHeight <= 0
                || cropView.getSourceWidth() <= 0 || cropView.getSourceHeight() <= 0) {
            showNotice(sourceIsOriginal ? 0 : R.string.edit_notice_no_original, !sourceIsOriginal);
            return;
        }
        WallpaperStore.Item item = itemId == null ? null : WallpaperStore.get(this, itemId);
        if (item == null || item.srcWidth != originalWidth || item.srcHeight != originalHeight) {
            pendingRestoreRect = null;
            showNotice(R.string.edit_notice_original_changed, false);
            return;
        }
        // 原图坐标 → 位图坐标：两轴各按「位图 / 原图」换算（与导出那次的换算反方向）
        float kx = cropView.getSourceWidth() / (float) originalWidth;
        float ky = cropView.getSourceHeight() / (float) originalHeight;
        cropView.restoreSourceRect(new RectF(
                pendingRestoreRect.left * kx, pendingRestoreRect.top * ky,
                pendingRestoreRect.right * kx, pendingRestoreRect.bottom * ky));
        pendingRestoreRect = null;
    }

    /** 顶部提示条：resId 为 0 表示不提示 —— 有原图、正常进编辑时别多占一块屏幕。 */
    private void showNotice(int resId, boolean withLinkButton) {
        if (noticeBar == null || noticeText == null) {
            return;
        }
        if (resId == 0) {
            noticeBar.setVisibility(View.GONE);
            return;
        }
        noticeText.setText(resId);
        noticeBar.setVisibility(View.VISIBLE);
        if (noticeLinkButton != null) {
            noticeLinkButton.setVisibility(withLinkButton ? View.VISIBLE : View.GONE);
        }
    }

    /**
     * 把当前可见区域换算回**原图坐标**存下来，供下次进编辑页复原取景框。
     * 只在源是原图时写：换算用的是「原图 / 解码位图」的比例，源换成成品图时这套比例对不上，
     * 记下来的矩形读回来会给出错误构图。
     */
    private void saveCropRectIfNeeded(String id) {
        if (!sourceIsOriginal || originalWidth <= 0 || originalHeight <= 0) {
            return;
        }
        int srcW = cropView.getSourceWidth();
        int srcH = cropView.getSourceHeight();
        if (srcW <= 0 || srcH <= 0) {
            return;
        }
        RectF visible = new RectF();
        if (!cropView.getVisibleSourceRect(visible)) {
            return;
        }
        // 与 exportByRegion 同一套换算（两轴分别算，避开 inSampleSize 向上取整的偏差），再夹回原图范围
        float kx = originalWidth / (float) srcW;
        float ky = originalHeight / (float) srcH;
        float left = Math.max(0f, Math.min(originalWidth, visible.left * kx));
        float top = Math.max(0f, Math.min(originalHeight, visible.top * ky));
        float right = Math.max(0f, Math.min(originalWidth, visible.right * kx));
        float bottom = Math.max(0f, Math.min(originalHeight, visible.bottom * ky));
        if (right <= left || bottom <= top) {
            return;
        }
        WallpaperStore.saveCropRect(this, id, originalWidth, originalHeight, left, top, right, bottom);
    }

    /**
     * 提示条上的「关联原图」：开相册选一张，选完就地换成原图源。
     * <b>临时功能</b> —— 只为用户手里那批 v3.8x 之前入库、没留原图的存量壁纸，新壁纸一律由
     * confirmImport 自动留存原图，用不到这个入口。用户已说过以后要把它删掉，删除清单见
     * `docs/reedit-from-original-design.md` 的「§6.1 以后删这一条时要动哪几处」。
     */
    private void pickOriginalForLinking() {
        if (itemId == null || linkOriginalLauncher == null) {
            return;
        }
        PickVisualMediaRequest.Builder builder = new PickVisualMediaRequest.Builder();
        builder.setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE);
        linkOriginalLauncher.launch(builder.build());
    }

    /**
     * 关联回来：原始字节复制进 originals/ → 源换成原图 → 参数作废（历史成品图不知道自己是从哪儿裁的）
     * → 重新解码、从默认构图开始，接着裁接着保存。
     * 复制放后台线程（整张图的 IO，可能几 MB），完成时页面可能已经关了，所以只用了 Application 上下文。
     */
    private void onOriginalPicked(Uri uri) {
        if (uri == null || itemId == null) {
            return;
        }
        setLoading(true);
        final Context appCtx = getApplicationContext();
        new Thread(() -> {
            final boolean linked = WallpaperStore.linkOriginal(appCtx, itemId, uri);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (!linked) {
                    setLoading(false);
                    Toast.makeText(this, R.string.link_original_failed, Toast.LENGTH_SHORT).show();
                    return;
                }
                sourceFile = WallpaperStore.getOriginalFile(this, itemId);
                sourceIsOriginal = true;
                pendingRestoreRect = null;
                readOriginalBounds();
                startDecode();
            });
        }, "link-original").start();
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
            // 记下这次的可见矩形，下次点格子进来就落回这里。必须排在入库/覆盖之后：
            // 导入模式的条目是 confirmImport 刚建的那一条，提前写会找不到条目
            saveCropRectIfNeeded(itemId != null ? itemId : inboxId);
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
