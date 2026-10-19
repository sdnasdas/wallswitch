package com.example.wallswitch;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

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
    // 目标壁纸库 id 的 Intent extra key：导入模式是「入哪个库」；v3.117 起重编模式也带上，
    // 表示「从哪个库进来的」——挂在几个库里的那张要分叉，得点名往哪个库挂新条目
    public static final String EXTRA_LIB_ID = "lib_id";

    private CropView cropView;
    private Button btnConfirm;
    private LauncherPreviewOverlay overlay;
    private ActivityResultLauncher<PickVisualMediaRequest> shotLauncher;

    private String inboxId;
    private String itemId;
    private String libId;
    /**
     * 这一次编辑落盘（新增或覆盖）的是哪一张，由 {@link #onConfirm()} 写好。
     *
     * <p>为什么要有它：MainActivity 回来时要把这张的缩略图缓存失效掉，否则格子里停在旧图。
     * 原先是无条件把两个缩略图缓存整份清空，于是批量导入时每回一次 App 就把整屏格子在主线程
     * 重解一遍 JPEG —— 只有这一次动过的那一张需要重解。
     * 用 static 而不是 {@code setResult}：这里是普通 {@code startActivity} 进来的（编辑页可能由
     * 收件箱队列连着开好几轮，见 MainActivity#onResume 那条 pending 分支），没有回调可接。
     * volatile：写它的可能是确认那一下的线程，读它在另一个 Activity 的 onResume。
     */
    static volatile String lastTouchedId;
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
    /** 确认落盘是否在跑：跑着的时候再次确认/取消/关联原图都不接新活。 */
    private volatile boolean confirmRunning;

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
        bindFullFormatPill();
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
     * 成品图格式那粒胶囊：点一下换到另一档并立刻记住，「确认」落盘时按它写。
     * 只管此后写入的成品图，已入库的一张都不动 —— 想换那张的格式就重编一次。
     */
    private void bindFullFormatPill() {
        TextView pill = findViewById(R.id.btn_full_format);
        if (pill == null) {
            return;
        }
        pill.setOnClickListener(v -> {
            boolean jpeg = !WallpaperStore.fullPrefersJpeg(this);
            WallpaperStore.setFullPrefersJpeg(this, jpeg);
            paintFullFormatPill(pill, jpeg);
        });
        paintFullFormatPill(pill, WallpaperStore.fullPrefersJpeg(this));
    }

    private void paintFullFormatPill(TextView pill, boolean jpeg) {
        pill.setText(getString(R.string.edit_full_format,
                getString(jpeg ? R.string.fmt_full_jpeg : R.string.fmt_full_png)));
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
            // 批量导入时会连开好几轮编辑页，解码跑后台优先级压峰值功耗（发热），略慢一点点
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            final Bitmap decoded = WallpaperStore.decodeCropSource(EditActivity.this, sourceFile);
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
    private void saveCropRectIfNeeded(String id, RectF visible, int srcW, int srcH) {
        if (!sourceIsOriginal || originalWidth <= 0 || originalHeight <= 0) {
            return;
        }
        if (srcW <= 0 || srcH <= 0) {
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
        if (confirmRunning || itemId == null || linkOriginalLauncher == null) {
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

    /** 取消按钮置灰：确认落盘跑着的几秒里不接取消，避免与后台线程抢收件箱文件。 */
    private void setCancelEnabled(boolean enabled) {
        View cancel = findViewById(R.id.btn_cancel);
        if (cancel != null) {
            cancel.setEnabled(enabled);
        }
    }

    /**
     * 确认：按当前取景框做区域解码（回原图取那一块）。
     * 导入模式入库为新壁纸；重编模式覆盖原壁纸文件（保留 id/标题/归属库）。
     * 区域解码拿不到（格式不支持等）时回退到「从内存位图裁」，再不行才报失败。
     *
     * <p>重编一张<b>还挂在别的库</b>的壁纸时先问一句（v3.117）：覆盖改的是那几库共用的一份成品图，
     * 一次点按会连带把别的库那张也换掉，所以给两个出口 —— 同步修改 / 只改这个库（另存一条新的）。
     * 这一问插在落盘链起跑之前：选完才量取景框、才动文件，取消就是什么都没做。
     *
     * <p>v3.114 起整条落盘链（区域解码 + 成品图编码 + 缩略图）搬后台线程：这是数秒级重活，
     * 主线程陪跑就是「点确认卡两三秒」。取景框在主线程量好再交给后台 ——
     * {@link #exportByRegion}/{@link #saveCropRectIfNeeded} 都要读视图状态，后台线程不摸视图。
     */
    private void onConfirm() {
        if (confirmRunning) {
            return;
        }
        // 当值复检（与网格那两道闸门同一个判据，这里是现读）：从进页面到点确认这段窗口里，
        // 定时任务可能刚好把这张切成当值 —— 落盘早已搬后台，这条并发是真能撞上的。
        // 新导入时 itemId 为 null，这张还不存在于任何指针里，自然放行。
        String inUse = Switcher.inUseWhere(this, itemId);
        if (inUse != null) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.in_use_title)
                    .setMessage(getString(R.string.in_use_msg, inUse))
                    .setPositiveButton(R.string.in_use_ok, null)
                    .show();
            return;
        }
        List<String> otherNames = sharedOtherLibNames();
        if (otherNames != null) {
            showDivergeChoice(otherNames);
            return;
        }
        startConfirm(false);
    }

    /**
     * 这次重编是不是「一张挂在几个库、改动会让别处跟着变」：是就返回<b>别的</b>那些库的名字
     * （弹窗要用），否则返回 null（直接按老行为落盘）。
     *
     * <p>靠现读而不是进页面时那份快照：他在裁剪页期间可能去整理过归属。
     * 当前库定不下来（老入口没带 lib_id）也返回 null —— 分叉需要一个明确的「挂到哪个库」。
     */
    private List<String> sharedOtherLibNames() {
        if (itemId == null || libId == null || libId.isEmpty()) {
            return null;
        }
        WallpaperStore.Item item = WallpaperStore.get(this, itemId);
        if (item == null || item.libIds.size() < 2 || !item.inLib(libId)) {
            return null;
        }
        List<String> names = new ArrayList<>();
        for (String owner : item.libIds) {
            if (owner.equals(libId)) {
                continue;
            }
            LibraryStore.Library lib = LibraryStore.get(this, owner);
            if (lib != null) {
                names.add(lib.name == null ? "" : lib.name);
            }
        }
        return names.isEmpty() ? null : names;
    }

    /** 分叉那一步的选择窗：两个出口各讲后果，点外面或返回 = 这次不落盘、留在裁剪页继续调。 */
    private void showDivergeChoice(List<String> otherNames) {
        StringBuilder joined = new StringBuilder();
        for (String name : otherNames) {
            if (joined.length() > 0) {
                joined.append("、");
            }
            joined.append("「").append(name).append("」");
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.diverge_title)
                .setMessage(getString(R.string.diverge_msg, joined.toString(), otherNames.size() + 1))
                .setPositiveButton(R.string.diverge_sync, (dialog, which) -> startConfirm(false))
                .setNegativeButton(R.string.diverge_fork, (dialog, which) -> startConfirm(true))
                .setNeutralButton(R.string.cancel, null)
                .show();
    }

    /** 真正起跑落盘链（取景框在这里量，必须主线程）。{@code fork} = 只改这个库，另存一条新的。 */
    private void startConfirm(boolean fork) {
        if (confirmRunning) {
            return;
        }
        confirmRunning = true;
        setLoading(true);
        setCancelEnabled(false);
        RectF visible = new RectF();
        boolean hasVisible = cropView.getVisibleSourceRect(visible);
        int srcW = cropView.getSourceWidth();
        int srcH = cropView.getSourceHeight();
        final Context appCtx = getApplicationContext();
        new Thread(() -> {
            // 落盘链（区域解码 + 编码 + 缩略图）是数秒级重活，后台优先级换更低的发热
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            confirmInBackground(appCtx, visible, hasVisible, srcW, srcH, fork);
        }, "edit-confirm").start();
    }

    /**
     * 分叉后把「一键设置」钉住的那张也跟着换成新那条（只在本库是 pin 所在库时）。
     * 不换的话下次按通知上那颗开关会走 {@code inLibrary} 判定失败报「那张已不在库里」——
     * 而本库里这张已经是新那条了，只是 id 换了。别的库的 pin 不动，它们钉的确实是原来那张。
     */
    private static void retargetPinnedTo(Context ctx, String libId, String fromId, String toId) {
        if (libId.equals(PinnedWallpaper.libId(ctx))
                && fromId.equals(PinnedWallpaper.wallpaperId(ctx))) {
            PinnedWallpaper.set(ctx, libId, toId);
            StatusNotifier.update(ctx);
        }
    }

    /** 落盘链主体（后台线程）：导出 → 入库/覆盖/分叉 → 记构图，收尾回主线程报结果并关页。 */
    private void confirmInBackground(Context appCtx, RectF visible, boolean hasVisible,
                                     int srcW, int srcH, boolean fork) {
        Bitmap result = hasVisible ? exportByRegion(visible, srcW, srcH) : null;
        if (result == null) {
            result = cropFallbackOnUi();
        }
        boolean saved = false;
        String touchedId = null;
        try {
            if (result == null) {
                // 两条导出路都失败：导入模式把收件箱丢掉，与取消同一口径
                if (itemId == null) {
                    WallpaperStore.cancelImport(appCtx, inboxId);
                }
            } else if (itemId != null) {
                // fork 前置条件不成立（归属在这期间被整理过、当前库对不上）时返回 null，退回覆盖，
                // 不该把用户这次已经调好的取景丢掉
                String freshId = fork ? WallpaperStore.forkIntoLib(appCtx, itemId, result, libId) : null;
                if (freshId != null) {
                    // 分叉：本库里这条已经是新造的那张；原来那张的文件与记录都留给别的库，
                    // 所以别处的屏不用重上屏，只有本库要把指针从旧条目挪到新条目
                    touchedId = freshId;
                    saved = true;
                    final String fromId = itemId;
                    final String toId = freshId;
                    new Thread(() -> {
                        Switcher.retargetCurrent(appCtx, libId, fromId, toId);
                        retargetPinnedTo(appCtx, libId, fromId, toId);
                    }, "reapply-wallpaper").start();
                } else {
                    WallpaperStore.overwrite(appCtx, itemId, result);
                    touchedId = itemId;
                    saved = true;
                    // 覆盖后立刻把改动推上屏（这张若是桌面/锁屏的当前壁纸），不等下一次切换。
                    // 锁屏路径是阻塞的系统调用，放独立线程；用的是 Application 上下文
                    //（拿 Activity 当 Context 会被 lint 判 context leak）
                    new Thread(() -> {
                        WallpaperStore.Item item = WallpaperStore.get(appCtx, itemId);
                        if (item != null) {
                            // 一张现在可能挂在好几个库里，而「当前壁纸」指针是按库各存一份的：
                            // 它同时在 A 库（桌面）和 B 库（锁屏）当屏上那张时，两边都得跟上这次重编
                            for (String owner : new java.util.ArrayList<>(item.libIds)) {
                                Switcher.reapplyIfCurrent(appCtx, owner, itemId);
                            }
                        }
                    }, "reapply-wallpaper").start();
                }
            } else {
                WallpaperStore.confirmImport(appCtx, inboxId, result, libId);
                touchedId = inboxId;
                saved = true;
            }
            // 记下这次的可见矩形，下次点格子进来就落回这里。必须排在入库/覆盖之后：
            // 导入模式的条目是 confirmImport 刚建的那一条，提前写会找不到条目
            if (saved) {
                saveCropRectIfNeeded(touchedId, visible, srcW, srcH);
            }
        } catch (Exception e) {
            saved = false;
        } finally {
            // 成品图那份位图是这一次新建的，编码完再没人引用；留到 GC 就等于多占一会儿几十 MB
            if (result != null && !result.isRecycled()) {
                result.recycle();
            }
        }
        // 落盘成功了才说"这张变过"：失败时缩略图压根没重写，让缓存跟着失效只会白解一次
        if (saved) {
            lastTouchedId = touchedId;
        }
        final boolean ok = saved;
        runOnUiThread(() -> {
            confirmRunning = false;
            if (isFinishing() || isDestroyed()) {
                return;
            }
            if (!ok) {
                Toast.makeText(this, R.string.save_failed, Toast.LENGTH_SHORT).show();
            }
            finish();
        });
    }

    /**
     * 内存裁剪回退：要摸 cropView 的位图与矩阵，跳回主线程做（latch 同步等它跑完）。
     * 页面已销毁（确认途中用户按了返回）时返回 null，由失败分支收口。
     */
    private Bitmap cropFallbackOnUi() {
        final Bitmap[] out = new Bitmap[1];
        final CountDownLatch done = new CountDownLatch(1);
        runOnUiThread(() -> {
            try {
                if (!isFinishing() && !isDestroyed()) {
                    out[0] = cropView.export(maxDim);
                }
            } finally {
                done.countDown();
            }
        });
        try {
            done.await();
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        return out[0];
    }

    /**
     * 区域解码导出：把「当前可见区域」从解码图坐标映射回原图坐标，直接从原图取那一块。
     * 放得越大，可见区域在原图上越小 —— 但取的是原图原生像素，所以始终能给出满屏幕分辨率，
     * 不再受「事先解码了一张多大的图」限制。取景框与解码图尺寸由调用方（主线程）量好传入。
     */
    private Bitmap exportByRegion(RectF visible, int srcW, int srcH) {
        if (originalWidth <= 0 || originalHeight <= 0 || srcW <= 0 || srcH <= 0) {
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
        if (confirmRunning) {
            // 落盘跑着的时候不接取消：几秒后确认链自己收尾，页面照关
            return;
        }
        if (itemId == null) {
            WallpaperStore.cancelImport(this, inboxId);
        }
        finish();
    }

    @Override
    protected void onDestroy() {
        // 预览那张大位图没人再画了，主动还掉（正常路径下它只是随 Activity 一起变垃圾，等 GC）
        if (cropView != null) {
            cropView.releaseBitmap();
        }
        super.onDestroy();
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
                    // 位图归全局缓存管（下一张的编辑页还要复用），页面没了也不能回收
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
