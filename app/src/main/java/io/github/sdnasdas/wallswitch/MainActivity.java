package io.github.sdnasdas.wallswitch;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.text.InputType;
import android.text.Layout;
import android.util.DisplayMetrics;
import android.util.LruCache;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.splashscreen.SplashScreen;
import androidx.core.view.WindowCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.imageview.ShapeableImageView;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.shape.ShapeAppearanceModel;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.textfield.TextInputLayout;

import java.io.File;
import java.io.FileInputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 主界面 v3.134：单 Activity + 底部三栏。
 * <ul>
 *   <li><b>此刻</b>：桌面 / 锁屏两张竖向预览卡（在屏那张成品图），各带上一张 / 暂停 / 下一张，
 *       点预览图开这一面的设置面板；没配好时顶上是三步引导；下面是快捷设置与最近切换。</li>
 *   <li><b>图库</b>：库卡片两列网格，点进库详情（三列壁纸网格 + 「设为桌面 / 设为锁屏」），
 *       点一张进全屏单张详情（调整取景 / 设为桌面 / 改标题 / 删除）；长按进多选。</li>
 *   <li><b>设置</b>：{@link SettingsFragment}（原左侧抽屉）。</li>
 * </ul>
 * 原来藏在手势里的操作都换成了看得见的入口：双击切换 → 三颗键；拖头像设库 → 「设为桌面」；
 * 点库名改名 → 铅笔；长按排序 → 「⋯ → 调整顺序」；删除态 → 长按多选；长按三颗圆键 → 详情页动作条；
 * 长按 ＋ → 来源面板；抽屉里的长按清除 → 点开后的「清除」。
 */
public class MainActivity extends AppCompatActivity {

    // SharedPreferences 文件名
    private static final String PREFS_NAME = "settings";
    // 相册单次多选上限
    private static final int MAX_PICK = 50;
    // 通知权限提示是否已弹过的记录 key（避免每次打开应用都打扰）
    private static final String KEY_NOTIFY_PROMPTED = "notify_prompted";
    private static final String STATE_TAB = "tab";
    private static final String STATE_LIB_OPEN = "lib_open";
    private static final String STATE_SETTINGS_SCREEN = "settings_screen";
    private static final String STATE_SETTINGS_TITLE = "settings_title";
    // 底部三栏外观：悬浮胶囊 / 贴底经典（设置页可切换，默认经典）
    private static final String KEY_BOTTOM_NAV_CAPSULE = "bottom_nav_capsule";
    private static final int CAPSULE_NAV_HEIGHT_DP = 58;

    ActivityResultLauncher<PickVisualMediaRequest> pickLauncher;
    // 相册多选回调转给 ImportController（注册仍须在 Activity 生命周期内）
    // 通知权限请求（Android 13+ 自动切换提示需要 POST_NOTIFICATIONS）
    ActivityResultLauncher<String> notifyPermissionLauncher;
    /** 读取系统壁纸存档用（Android 13+ 是 READ_MEDIA_IMAGES，低版本 READ_EXTERNAL_STORAGE） */
    ActivityResultLauncher<String> savePermLauncher;
    // 全局「桌面图标预览底图」的单张选图（选一次、抠图后存应用目录，之后裁剪页直接复用）
    ActivityResultLauncher<PickVisualMediaRequest> overlayLauncher;
    // 设置页「视频转 Live」的单段选视频（选完交裁剪页，产出直接进相册，不落壁纸库）
    ActivityResultLauncher<PickVisualMediaRequest> liveVideoLauncher;
    // SAF 导出目录选择（ACTION_OPEN_DOCUMENT_TREE）
    ActivityResultLauncher<Uri> exportDirLauncher;
    // 还原：用户直接指一个备份包（ACTION_OPEN_DOCUMENT，每次现授临时读权限，不依赖卸载前那个目录授权）
    ActivityResultLauncher<String[]> restoreZipLauncher;
    // 还原兜底：让用户把默认目录授给我们（SAF 目录授权不受属主限制，卸载清了 MediaStore 条目也能列出）
    ActivityResultLauncher<Uri> restoreTreeLauncher;
    SharedPreferences prefs;
    /** 保存当前壁纸 / 读备份包时的不可关闭进度弹窗（showProgress/dismissTakeoverProgress 管理）。 */
    private androidx.appcompat.app.AlertDialog takeoverProgress;
    // 库详情正在看的库 id（也兼作「上次查看的库」，导入壁纸时的目标库）
    private String currentLibId;
    /** 整理动作的三档（{@link #applyOrganizeChange} 的 kind）：复制只挂归属，移动摘挂各一次，移出可能删文件。 */
    static final int ORGANIZE_COPY = 1;
    static final int ORGANIZE_MOVE = 2;
    static final int ORGANIZE_REMOVE = 3;

    // ---- 页面骨架 ----
    private BottomNavigationView bottomNav;
    private LiquidGlassView navGlass;
    View pageNow, pageGallery, pageLibrary, pageSettings, pageSettingsSecondary,
            pageDetail, pageLog, selectionBar;
    private int currentTab;
    UiMotion motion;
    UiImages images;
    boolean detailOpen;
    boolean logOpen;
    private String settingsSecondaryKey;
    private String settingsSecondaryTitle;
    /** 图库那一栏现在停在库详情（true）还是库卡片网格（false）。 */
    boolean libraryOpen;
    /** 最近一次系统栏避让尺寸（左、上、右、下），底部那档随底栏显隐换人垫。 */
    private final int[] barInsets = new int[4];
    /** 底栏当前是否悬浮胶囊样式（设置页可切换）。 */
    private boolean capsuleNav;
    private int classicNavMinimumHeight;
    private boolean classicNavIndicator;
    private int classicNavTextActive;
    private int classicNavTextInactive;
    private int classicNavIconSize;
    private int classicNavItemPaddingTop;
    private int classicNavItemPaddingBottom;

    // ---- 此刻 ----
    private ScopeViews homeViews, lockViews;
    private BottomSheetDialog quickSheet;
    private List<LibraryStore.Library> quickLibs = new ArrayList<>();
    private QuickPickAdapter quickPickAdapter;
    private String quickLibId;

    // ---- 图库 / 库详情 / 单张详情 ----
    MaterialToolbar libraryToolbar, detailToolbar;
    private RecyclerView libraryRecycler;
    WallpaperAdapter wallpaperAdapter;
    private GridLayoutManager wallpaperLayout;
    private int wallpaperColumns = 2;
    private int wallpaperFilter;
    private String detailLibId;
    private boolean detailUsesFilter;
    /** 正在后台上屏（设为桌面/锁屏）：详情与快捷设置共用，期间禁掉重复触发。 */
    boolean settingWallpaper;
    ViewPager2 detailPager;
    private DetailAdapter detailAdapter;

    // ---- 全屏切换记录页（控件/快照/卡片 adapter 都在 SwitchLogController） ----
    final SwitchLogController log = new SwitchLogController(this);

    // ---- 单张详情的实况播放（播放器/播放层/起播参数都在 LivePlayerController） ----
    final LivePlayerController live = new LivePlayerController(this);

    // ---- 单张详情的桌面图标预览（单击画面开关，与裁剪页同一张全局底图） ----
    LauncherPreviewOverlay detailOverlay;
    boolean detailPreview;
    // 还没设过底图时单击画面会去相册选；选完回来要接着把预览打开，而从设置页选的不用
    boolean overlayPickForDetail;

    // 缩略图（方形 JPEG）缓存与大图（预览卡 / 详情页按屏降采样的成品图）缓存
    private final LruCache<String, Bitmap> thumbs = new LruCache<String, Bitmap>(16 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    final LruCache<String, Bitmap> previews = new LruCache<String, Bitmap>(48 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    final ExecutorService imageLoader = Executors.newFixedThreadPool(2);
    // 日志看图的旧解码路径也要服从图片修订；只在主线程提交缓存。
    long imageEpoch;
    long imageClearEpoch;
    private final Map<String, Long> imageRevisions = new HashMap<>();
    private final java.util.WeakHashMap<View, CellInfo> cellInfos = new java.util.WeakHashMap<>();

    // ---- 检查更新（状态/轮询/弹窗都在 UpdateController） ----
    final UpdateController update = new UpdateController(this);
    // ---- 通知/存档权限与电池优化白名单 ----
    final PermissionHelper perm = new PermissionHelper(this);
    // ---- 桌面接管与保存当前壁纸 ----
    final TakeoverController takeover = new TakeoverController(this);
    // ---- 桌面图标预览底图 ----
    final LauncherOverlayController overlay = new LauncherOverlayController(this);
    // ---- 导入管线（相册/分享/直链/批量） ----
    final ImportController importer = new ImportController(this);
    // ---- 备份/还原/导出/清理 ----
    final BackupController backup = new BackupController(this);
    // ---- 设为壁纸/删除/一键设置 ----
    final WallpaperActions actions = new WallpaperActions(this);
    // ---- 图库页（库卡片网格/三态/排序/删库） ----
    final GalleryController gallery = new GalleryController(this);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        readSharedImportIntent(getIntent());
        // 注册系统相册多选（PickVisualMedia，无需存储权限）
        pickLauncher = registerForActivityResult(
                new ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK),
                this::onPicked);
        notifyPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                this::onNotifyPermissionResult);
        savePermLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                this::onSavePermissionResult);
        overlayLauncher = registerForActivityResult(
                new ActivityResultContracts.PickVisualMedia(), this::onOverlayPicked);
        liveVideoLauncher = registerForActivityResult(
                new ActivityResultContracts.PickVisualMedia(), this::onLiveVideoPicked);
        exportDirLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocumentTree(), this::onExportDirPicked);
        restoreZipLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), this::onRestoreZipPicked);
        restoreTreeLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocumentTree(), this::onRestoreTreePicked);

        bottomNav = findViewById(R.id.bottom_nav);
        navGlass = findViewById(R.id.nav_glass);
        pageNow = findViewById(R.id.page_now);
        pageGallery = findViewById(R.id.page_gallery);
        pageLibrary = findViewById(R.id.page_library);
        pageSettings = findViewById(R.id.page_settings);
        pageSettingsSecondary = findViewById(R.id.page_settings_secondary);
        pageLog = findViewById(R.id.page_switch_log);
        pageDetail = findViewById(R.id.page_detail);
        selectionBar = findViewById(R.id.selection_bar);
        currentTab = R.id.nav_now;
        motion = new UiMotion(this);
        images = new UiImages(this, imageLoader, thumbs, previews, motion::whenIdle, motion::discard);
        motion.setLayers(findViewById(R.id.main_root), pageLibrary, pageSettingsSecondary, pageLog, pageDetail);
        motion.setTab(pageNow, () -> { });

        setupInsets();
        setupNowPage();
        setupGalleryPage();
        setupLibraryPage();
        setupDetailPage();
        setupSwitchLogPage();
        setupSettingsSecondaryPage();
        setupSelectionBar();
        setupBackPressed();
        if (getSupportFragmentManager().findFragmentById(R.id.settings_container) == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.settings_container, new SettingsFragment())
                    .commitNow();
        }

        // 默认开启且尚未授权时，首次打开应用请求一次通知权限（已请求过就不再打扰）
        if (SwitchNotifier.isEnabled(this) && !prefs.getBoolean(KEY_NOTIFY_PROMPTED, false)
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            prefs.edit().putBoolean(KEY_NOTIFY_PROMPTED, true).apply();
            notifyPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
        // 电池优化引导（荣耀等机型避免后台被杀）
        maybePromptBattery();
        maybeShowMetaNotice();
        maybePromptRestore();
        update.checkOnLaunch();

        int tab = savedInstanceState == null ? R.id.nav_now
                : savedInstanceState.getInt(STATE_TAB, R.id.nav_now);
        bottomNav.setSelectedItemId(tab);
        showTab(tab, false);
        setupNavigation();
        if (savedInstanceState != null) {
            if (tab == R.id.nav_gallery && savedInstanceState.getBoolean(STATE_LIB_OPEN, false)
                    && currentLib() != null) {
                libraryOpen = true;
                refreshList();
                motion.restore(pageLibrary);
            }
            if (tab == R.id.nav_settings) {
                settingsSecondaryKey = savedInstanceState.getString(STATE_SETTINGS_SCREEN);
                settingsSecondaryTitle = savedInstanceState.getString(STATE_SETTINGS_TITLE);
                if (settingsSecondaryKey != null) {
                    ensureSettingsSecondary(settingsSecondaryKey);
                    setSettingsSecondaryTitle(settingsSecondaryTitle);
                    motion.restore(pageSettingsSecondary);
                }
            }
        }

        // 老版本缩略图（长边 256 的等比图）在方格上会被放大发虚：
        // 后台一次性重做成「中心正方形 + 按屏幕取边长」，做完清缓存重绑一次
        new Thread(() -> {
            List<String> changed = WallpaperStore.regenerateThumbs(getApplicationContext());
            if (!changed.isEmpty()) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    for (String id : changed) {
                        evictImages(id);
                    }
                    motion.whenIdle("refresh", this::refreshCurrentPage);
                });
            }
        }, "thumb-regen").start();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_TAB, currentTab);
        outState.putBoolean(STATE_LIB_OPEN, libraryOpen);
        outState.putString(STATE_SETTINGS_SCREEN, settingsSecondaryKey);
        outState.putString(STATE_SETTINGS_TITLE, settingsSecondaryTitle);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        readSharedImportIntent(intent);
        // 已经在前台时不会再走 onResume（分屏多窗口下可能如此），这就在这里接着处理
        if (getLifecycle().getCurrentState().isAtLeast(
                androidx.lifecycle.Lifecycle.State.RESUMED)) {
            consumeSharedImport();
        }
    }

    @Override
    protected void onPause() {
        // 离开前台（进裁剪页、切走、锁屏）就把实况停掉，播放器与解码器一并释放
        stopLive();
        super.onPause();
    }

    @Override
    protected void onStop() {
        motion.finish();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        // 「更多」菜单开着时重建（切深色、改字号）不能留下挂在旧窗口上的 PopupWindow
        dismissSelectionMore();
        super.onDestroy();
        stopLive();
        images.close();
        motion.close();
        update.dispose();
        imageLoader.shutdownNow();
    }

    // 经典底栏负责导航条内边距，胶囊在浮层留出系统栏间距。
    private void setupInsets() {
        InsetsHelper.edgeToEdge(this);
        final View detailTop = findViewById(R.id.detail_top);
        final View detailBottom = findViewById(R.id.detail_bottom);
        // <include android:id="page_switch_log"> 会覆盖被包含布局根上的 log_root id，
        // 不能再按 log_root 查（会是 null，第一次分发 insets 就启动崩溃）。pageLog 就是那棵根。
        final View logRoot = pageLog;
        findViewById(R.id.app_root).setOnApplyWindowInsetsListener((v, insets) -> {
            int[] b = InsetsHelper.bars(insets);
            System.arraycopy(b, 0, barInsets, 0, 4);
            applyBottomInset();
            detailTop.setPadding(b[0], b[1], b[2], 0);
            detailBottom.setPadding(b[0], dp(32), b[2], b[3]);
            pageLibrary.setPadding(b[0], b[1], b[2], b[3]);
            pageSettingsSecondary.setPadding(b[0], b[1], b[2], b[3]);
            logRoot.setPadding(b[0], b[1], b[2], b[3]);
            // 悬浮胶囊离手势条 16dp：底边距随系统栏高度补
            ViewGroup.MarginLayoutParams sel = (ViewGroup.MarginLayoutParams) selectionBar.getLayoutParams();
            if (sel.bottomMargin != b[3] + dp(16)) {
                sel.bottomMargin = b[3] + dp(16);
                selectionBar.setLayoutParams(sel);
            }
            return insets;
        });
    }

    // 浮层的避让留在滚动内容里，不能用页面 padding 再造一块固定底区。
    private void applyBottomInset() {
        boolean barShown = bottomNav.getVisibility() == View.VISIBLE
                || selectionBar.getVisibility() == View.VISIBLE;
        findViewById(R.id.main_root).setPadding(
                barInsets[0], barInsets[1], barInsets[2], barShown ? 0 : barInsets[3]);
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) navGlass.getLayoutParams();
        int bottomMargin = capsuleNav ? barInsets[3] + dp(12) : 0;
        if (lp.bottomMargin != bottomMargin) {
            lp.bottomMargin = bottomMargin;
            navGlass.setLayoutParams(lp);
        }
        int glass = capsuleNav && bottomNav.getVisibility() == View.VISIBLE ? View.VISIBLE : View.GONE;
        if (navGlass.getVisibility() != glass) {
            navGlass.setVisibility(glass);
        }
        int inset = floatingNavInset();
        View nowScroll = findViewById(R.id.scroll_now);
        if (nowScroll.getPaddingBottom() != inset) {
            nowScroll.setPadding(nowScroll.getPaddingLeft(), nowScroll.getPaddingTop(),
                    nowScroll.getPaddingRight(), inset);
        }
        if (gallery.isInitialized()) {
            gallery.setBottomInset(inset);
        }
        androidx.fragment.app.Fragment settings = getSupportFragmentManager()
                .findFragmentById(R.id.settings_container);
        if (settings instanceof SettingsFragment) {
            ((SettingsFragment) settings).updateBottomPadding();
        }
    }

    int floatingNavInset() {
        if (!capsuleNav || bottomNav.getVisibility() != View.VISIBLE) {
            return 0;
        }
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) navGlass.getLayoutParams();
        return Math.max(dp(CAPSULE_NAV_HEIGHT_DP), bottomNav.getHeight()) + lp.bottomMargin;
    }

    /** 设置页那颗开关：底部三栏用悬浮胶囊还是贴底经典。 */
    boolean capsuleNavEnabled() {
        return prefs.getBoolean(KEY_BOTTOM_NAV_CAPSULE, false);
    }

    void applyBottomNavStyle() {
        capsuleNav = capsuleNavEnabled();
        ViewGroup parent = (ViewGroup) bottomNav.getParent();
        ViewGroup target = capsuleNav ? navGlass : findViewById(R.id.main_column);
        ViewGroup.LayoutParams lp;
        if (capsuleNav) {
            lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bottomNav.setBackground(null);
            bottomNav.setElevation(0f);
            bottomNav.setItemTextAppearanceActive(R.style.TextAppearance_Ws_NavLabel);
            bottomNav.setItemTextAppearanceInactive(R.style.TextAppearance_Ws_NavLabel);
            bottomNav.setItemTextAppearanceActiveBoldEnabled(false);
            bottomNav.setItemIconTintList(getColorStateList(R.color.bottom_nav_capsule_icon));
            bottomNav.setItemTextColor(getColorStateList(R.color.bottom_nav_capsule_text));
            bottomNav.setItemActiveIndicatorEnabled(false);
            bottomNav.setMinimumHeight(dp(CAPSULE_NAV_HEIGHT_DP));
            bottomNav.setItemIconSize(dp(28));
            // 图标贴顶、文字贴底，间距 = 高度 - 上下内边距；按真机截图量出约 7dp，上下留白相等
            bottomNav.setItemPaddingTop(dp(6.5f));
            bottomNav.setItemPaddingBottom(dp(11.5f));
        } else {
            lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bottomNav.setBackgroundColor(MaterialColors.getColor(bottomNav,
                    com.google.android.material.R.attr.colorSurfaceContainer));
            bottomNav.setElevation(0f);
            bottomNav.setItemTextAppearanceActive(classicNavTextActive);
            bottomNav.setItemTextAppearanceInactive(classicNavTextInactive);
            bottomNav.setItemTextAppearanceActiveBoldEnabled(true);
            bottomNav.setItemIconTintList(getColorStateList(R.color.bottom_nav_icon));
            bottomNav.setItemTextColor(getColorStateList(R.color.text_primary));
            bottomNav.setItemActiveIndicatorEnabled(classicNavIndicator);
            bottomNav.setMinimumHeight(classicNavMinimumHeight);
            bottomNav.setItemIconSize(classicNavIconSize);
            bottomNav.setItemPaddingTop(classicNavItemPaddingTop);
            bottomNav.setItemPaddingBottom(classicNavItemPaddingBottom);
        }
        if (parent != target) {
            parent.removeView(bottomNav);
            target.addView(bottomNav, lp);
        } else {
            bottomNav.setLayoutParams(lp);
        }
        bottomNav.setPadding(bottomNav.getPaddingLeft(), bottomNav.getPaddingTop(),
                bottomNav.getPaddingRight(), capsuleNav ? 0 : barInsets[3]);
        Menu navMenu = bottomNav.getMenu();
        navMenu.findItem(R.id.nav_now).setIcon(capsuleNav ? R.drawable.ic_nav_now_filled : R.drawable.ic_nav_now);
        navMenu.findItem(R.id.nav_gallery).setIcon(
                capsuleNav ? R.drawable.ic_nav_gallery_filled : R.drawable.ic_photo_library);
        navMenu.findItem(R.id.nav_settings).setIcon(
                capsuleNav ? R.drawable.ic_nav_settings_filled : R.drawable.ic_settings);
        bottomNav.requestApplyInsets();
        applyBottomInset();
    }

    /** 设置页拨动「悬浮胶囊底栏」：落 key 并即时换外观。 */
    void setCapsuleNavEnabled(boolean on) {
        prefs.edit().putBoolean(KEY_BOTTOM_NAV_CAPSULE, on).apply();
        applyBottomNavStyle();
    }


    int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ==================== 导航 ====================

    private void setupNavigation() {
        navGlass.setSource(findViewById(R.id.page_host));
        classicNavIndicator = bottomNav.isItemActiveIndicatorEnabled();
        classicNavTextActive = bottomNav.getItemTextAppearanceActive();
        classicNavTextInactive = bottomNav.getItemTextAppearanceInactive();
        classicNavIconSize = bottomNav.getItemIconSize();
        classicNavMinimumHeight = bottomNav.getMinimumHeight();
        classicNavItemPaddingTop = bottomNav.getItemPaddingTop();
        classicNavItemPaddingBottom = bottomNav.getItemPaddingBottom();
        bottomNav.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (b - t != ob - ot) {
                applyBottomInset();
            }
        });
        bottomNav.setOnItemSelectedListener(item -> {
            showTab(item.getItemId(), true);
            return true;
        });
        // 在库详情里再点一次「图库」= 回库卡片网格
        bottomNav.setOnItemReselectedListener(item -> {
            if (item.getItemId() == R.id.nav_gallery && libraryOpen) {
                closeLibrary();
            }
        });
        bottomNav.setOnApplyWindowInsetsListener((v, insets) -> {
            int[] b = InsetsHelper.bars(insets);
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(),
                    capsuleNav ? 0 : b[3]);
            return insets;
        });
        applyBottomNavStyle();
    }

    private void showTab(int id, boolean animate) {
        if (currentTab != id) {
            if (settingsSecondaryKey != null) {
                closeSettingsSecondary();
            }
            if (libraryOpen) {
                closeLibrary();
            }
        }
        if (wallpaperAdapter.isOrganizeMode()) {
            exitOrganizeMode();
        }
        if (gallery.inMode()) {
            gallery.exitGalleryMode();
        }
        View target = id == R.id.nav_gallery ? pageGallery
                : id == R.id.nav_settings ? pageSettings : pageNow;
        currentTab = id;
        updateBottomNav();
        Runnable prepare = () -> {
            android.os.Trace.beginSection("ui.page.prepare");
            try {
                if (id == R.id.nav_gallery) {
                    gallery.refreshLibs();
                } else if (id == R.id.nav_settings) {
                    androidx.fragment.app.Fragment fragment = getSupportFragmentManager()
                            .findFragmentById(R.id.settings_container);
                    if (fragment instanceof SettingsFragment) {
                        ((SettingsFragment) fragment).refreshAll();
                    }
                } else {
                    refreshNowPage();
                }
            } finally {
                android.os.Trace.endSection();
            }
        };
        if (animate) {
            motion.replace(target, prepare, () -> {
                if (id == R.id.nav_settings) return settingsReady(R.id.settings_container);
                return id != R.id.nav_gallery || gallery.prepareVisible();
            });
        } else {
            motion.setTab(target, prepare);
        }
    }

    private boolean settingsReady(int container) {
        androidx.fragment.app.Fragment fragment = getSupportFragmentManager().findFragmentById(container);
        return fragment instanceof SettingsFragment && ((SettingsFragment) fragment).isContentReady();
    }

    /** 图库多选/排序、库内多选用动作模式顶替底部三栏；全屏二级覆盖页不改变它。 */
    void updateBottomNav() {
        // 进程重建时设置页的 Fragment 会在 super.onCreate 里就跑完 onCreatePreferences，
        // 那时骨架还没 findViewById、两个 adapter 也还没建，先放过（onCreate 末尾会再算一次）。
        if (bottomNav == null || !gallery.isInitialized() || wallpaperAdapter == null) {
            return;
        }
        boolean secondary = gallery.inMode()
                || wallpaperAdapter.isOrganizeMode();
        bottomNav.setVisibility(secondary ? View.GONE : View.VISIBLE);
        applyBottomInset();
    }

    void openLibrary(String libId) {
        if (LibraryStore.get(this, libId) == null) {
            return;
        }
        if (currentTab != R.id.nav_gallery) {
            showTab(R.id.nav_gallery, false);
            bottomNav.setSelectedItemId(R.id.nav_gallery);
        }
        currentLibId = libId;
        wallpaperFilter = 0;
        prefs.edit().putString(LibraryStore.KEY_CURRENT_LIB, libId).apply();
        libraryOpen = true;
        libraryRecycler.scrollToPosition(0);
        refreshList();
        libraryRecycler.post(this::syncAddFab);
        motion.show(pageLibrary, wallpaperAdapter::isEntryReady);
    }

    void closeLibrary() {
        exitOrganizeMode();
        closeDetail();
        libraryOpen = false;
        refreshLibs();
        motion.hide(pageLibrary);
    }

    void onCurrentLibraryDeleted() {
        currentLibId = null;
        prefs.edit().remove(LibraryStore.KEY_CURRENT_LIB).apply();
        if (libraryOpen) {
            closeLibrary();
        }
    }

    private void setupSettingsSecondaryPage() {
        MaterialToolbar toolbar = findViewById(R.id.toolbar_settings_secondary);
        toolbar.setNavigationOnClickListener(v -> closeSettingsSecondary());
    }

    void openSettingsSecondary(String key, CharSequence title) {
        if (key == null || key.equals(settingsSecondaryKey)) {
            return;
        }
        settingsSecondaryKey = key;
        settingsSecondaryTitle = title == null ? "" : title.toString();
        ensureSettingsSecondary(key);
        setSettingsSecondaryTitle(settingsSecondaryTitle);
        motion.show(pageSettingsSecondary, () -> settingsReady(R.id.settings_secondary_container));
    }

    private void ensureSettingsSecondary(String key) {
        androidx.fragment.app.Fragment fragment = getSupportFragmentManager()
                .findFragmentById(R.id.settings_secondary_container);
        if (fragment instanceof SettingsFragment) {
            ((SettingsFragment) fragment).showScreen(key);
        } else {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.settings_secondary_container, SettingsFragment.secondary(key))
                    .commitNow();
        }
    }

    private void setSettingsSecondaryTitle(CharSequence title) {
        ((MaterialToolbar) findViewById(R.id.toolbar_settings_secondary)).setTitle(title);
    }

    private void closeSettingsSecondary() {
        settingsSecondaryKey = null;
        settingsSecondaryTitle = null;
        motion.hide(pageSettingsSecondary);
        motion.whenIdle("settings-home", this::refreshSettings);
    }

    /**
     * 返回键/返回手势的分层：详情 → 多选 / 排序 → 库详情回图库 → 非「此刻」栏回「此刻」→ 交回系统。
     */
    private void setupBackPressed() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (detailOpen) {
                    if (detailPreview) {
                        exitDetailPreview();
                        return;
                    }
                    closeDetail();
                    return;
                }
                if (logOpen) {
                    closeSwitchLogPage();
                    return;
                }
                if (wallpaperAdapter.isOrganizeMode()) {
                    exitOrganizeMode();
                    return;
                }
                if (gallery.inMode()) {
                    gallery.exitGalleryMode();
                    return;
                }
                if (currentTab == R.id.nav_gallery && libraryOpen) {
                    closeLibrary();
                    return;
                }
                if (settingsSecondaryKey != null) {
                    closeSettingsSecondary();
                    return;
                }
                if (currentTab != R.id.nav_now) {
                    bottomNav.setSelectedItemId(R.id.nav_now);
                    return;
                }
                // 交回系统默认行为（退出 App）。默认动作若只是把任务切到后台、没销毁本实例
                // （部分 ROM 如此），这个回调必须立刻恢复启用，否则分层永久失效
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
                setEnabled(true);
            }
        });
    }

    /** 按当前所在页刷新：库详情先看库还在不在。 */
    void refreshCurrentPage() {
        // 删除/移走当前详情必须先处理；普通页面刷新等导航空闲后按 key 合并。
        if (detailOpen) {
            pruneDetailItems();
        }
        if (motion.isRunning()) {
            motion.whenIdle("refresh", this::refreshCurrentPage);
            return;
        }
        if (currentTab == R.id.nav_gallery) {
            if (libraryOpen) {
                if (currentLib() == null) {
                    closeLibrary();
                    return;
                }
                refreshList();
            } else {
                refreshLibs();
            }
        } else if (currentTab == R.id.nav_settings) {
            refreshSettings();
        } else {
            refreshNowPage();
        }
        if (detailOpen && !(currentTab == R.id.nav_gallery && libraryOpen)) {
            refreshDetail();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 自愈：每次回到应用都核对一遍排定（槽位配置没变时走 KEEP，不碰系统里已有的周期格子）
        TimerScheduler.scheduleAll(this);
        // 时段换库的边界任务同样核对一遍（指纹相同不重排；被 ROM 清掉时这里补上）
        LibScheduler.rescheduleAll(this);
        // 打开应用同样是“设备活跃”时机：后台补跑被 Doze/ROM 冻结而漏掉的定时切换
        new Thread(() -> {
            final boolean did = TimerScheduler.catchUp(getApplicationContext())
                    | LibScheduler.catchUp(getApplicationContext());
            if (did) {
                runOnUiThread(() -> {
                    if (!isFinishing()) {
                        refreshCurrentPage();
                    }
                });
            }
        }, "app-catchup").start();
        WidgetProvider.updateWidget(this);
        // 通知不跨重启存活、也可能被 ROM 清掉：回到应用时兜底补发/刷新常驻通知
        StatusNotifier.update(this);
        // 通知上那颗一键设置开关可能在后台被人拨过：回来对一次此刻页卡片的态
        refreshPinnedCard();
        // 从安装页/安装授权页返回：更新那一行按当前下载状态重刷
        update.refreshOnResume();
        if (consumeSharedImport()) {
            return;
        }
        List<String> pending = WallpaperStore.pendingInbox(this);
        if (!pending.isEmpty()) {
            // 批量落盘/选择弹窗进行中：别让 onResume 抢先弹逐张编辑页
            if (importer.busy()) {
                return;
            }
            // 还有待编辑项：继续逐张处理（取景完成后在编辑页里选库）
            openEdit(pending.get(0));
            return;
        }
        // 裁剪页这次动过哪一张，就只重解那一张（缩略图与大图两份缓存都摘掉）
        String touched = EditActivity.lastTouchedId;
        if (touched != null) {
            EditActivity.lastTouchedId = null;
            evictImages(touched);
        }
        refreshCurrentPage();
    }

    void evictImages(String id) {
        imageEpoch++;
        imageRevisions.put(id, imageRevision(id) + 1);
        images.evict(id);
        if (id.equals(live.playingId())) {
            stopLive();
        }
    }

    long imageRevision(String id) {
        return imageRevisions.getOrDefault(id, 0L);
    }

    void clearImages() {
        imageEpoch++;
        imageClearEpoch++;
        imageRevisions.clear();
        cellInfos.clear();
        stopLive();
        images.clear();
    }

    // ==================== 图片加载 / 列表快照 ====================

    void unbindImageTree(View view) {
        images.unbind(view);
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                unbindImageTree(group.getChildAt(i));
            }
        }
    }

    private record CellInfo(String id, long revision, UiImages.Info info) { }

    private void bindCellInfo(View owner, String id, boolean organize,
                              java.util.function.BiConsumer<View, UiImages.Info> apply) {
        CellInfo cached = cellInfos.get(owner);
        if (!id.equals(owner.getTag()) || cached == null || !id.equals(cached.id)
                || cached.revision != imageRevision(id)) {
            cellInfos.remove(owner);
            owner.findViewById(R.id.tv_full_format).setVisibility(View.GONE);
            owner.findViewById(R.id.tv_live_badge).setVisibility(View.GONE);
        } else {
            applyCellInfo(owner, cached.info, organize);
        }
        images.metadata(owner, id, apply);
    }

    private void applyCellInfo(View owner, UiImages.Info info, boolean organize) {
        String id = (String) owner.getTag();
        cellInfos.put(owner, new CellInfo(id, imageRevision(id), info));
        TextView format = owner.findViewById(R.id.tv_full_format);
        format.setVisibility(organize || info.jpeg == null ? View.GONE : View.VISIBLE);
        if (info.jpeg != null) {
            format.setText(info.jpeg ? R.string.fmt_badge_jpeg : R.string.fmt_badge_png);
        }
        owner.findViewById(R.id.tv_live_badge).setVisibility(info.live ? View.VISIBLE : View.GONE);
    }

    /** 不保存可变 Item/Library 引用，避免同 id 的标题、归属和封面变化漏刷。 */
    record DisplayRow(String id, List<?> fields) { }

    static DisplayRow displayRow(String id, Object... fields) {
        return new DisplayRow(id, java.util.Arrays.asList(fields));
    }

    private List<DisplayRow> wallpaperRows(List<WallpaperStore.Item> items) {
        List<DisplayRow> rows = new ArrayList<>();
        for (WallpaperStore.Item item : items) {
            rows.add(displayRow(item.id, item.title, item.libId, new ArrayList<>(item.libIds),
                    item.srcWidth, item.srcHeight, item.cropLeft, item.cropTop, item.cropRight,
                    item.cropBottom, imageRevision(item.id), imageClearEpoch,
                    Switcher.inUseWhere(this, item.id), item.favorite));
        }
        return rows;
    }

    static DiffUtil.DiffResult diffRows(List<DisplayRow> before, List<DisplayRow> after) {
        return DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return before.size(); }
            @Override public int getNewListSize() { return after.size(); }
            @Override public boolean areItemsTheSame(int oldPos, int newPos) {
                return before.get(oldPos).id.equals(after.get(newPos).id);
            }
            @Override public boolean areContentsTheSame(int oldPos, int newPos) {
                return before.get(oldPos).equals(after.get(newPos));
            }
        });
    }

    /** 按长边上限做 2 的幂降采样解码（只为看，不写盘）。 */
    private static Bitmap decodeBounded(File file, int maxSide) {
        if (file == null || !file.isFile()) {
            return null;
        }
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            int longSide = Math.max(bounds.outWidth, bounds.outHeight);
            if (longSide <= 0) {
                return null;
            }
            int sample = 1;
            while (longSide / (sample * 2) >= maxSide) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            return BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        } catch (OutOfMemoryError | Exception e) {
            return null;
        }
    }

    /** 成品图宽高各半解码（点日志看图小窗用，长边约 1400）：读头算出一半再喂 decodeBounded。 */
    static Bitmap decodeFullHalf(Context app, String id) {
        File file = WallpaperStore.getFullFile(app, id);
        if (file == null || !file.isFile()) {
            return null;
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        int longSide = Math.max(bounds.outWidth, bounds.outHeight);
        return longSide <= 0 ? null : decodeBounded(file, longSide / 2);
    }

    // ==================== 此刻 ====================

    /** 此刻页的一面（桌面 / 锁屏共用一份布局）。 */
    private final class ScopeViews {
        final boolean forHome;
        final MaterialCardView card;
        final ImageView thumb, scopeIcon;
        final TextView scope, lib, next, desc;
        final MaterialButton prev, pause, nextBtn;

        ScopeViews(View root, boolean forHome) {
            this.forHome = forHome;
            card = (MaterialCardView) root;
            thumb = root.findViewById(R.id.img_now_thumb);
            scopeIcon = root.findViewById(R.id.img_now_scope);
            scope = root.findViewById(R.id.tv_now_scope);
            lib = root.findViewById(R.id.tv_now_lib);
            next = root.findViewById(R.id.tv_now_next);
            desc = root.findViewById(R.id.tv_now_desc);
            prev = root.findViewById(R.id.btn_now_prev);
            pause = root.findViewById(R.id.btn_now_pause);
            nextBtn = root.findViewById(R.id.btn_now_next);
            root.findViewById(R.id.now_preview).setOnClickListener(v -> openScopeDetail(forHome));
            root.findViewById(R.id.btn_now_settings).setOnClickListener(v -> showSlotSettingsSheet(forHome));
            lib.setOnClickListener(v -> {
                String id = LibraryStore.slotLibId(MainActivity.this, forHome);
                // 「全部壁纸」不是一个能进的库（图库里没有它的详情页），点它等同于去改这一面的设置
                if (id != null && !LibraryStore.isAll(id)) {
                    openLibrary(id);
                } else {
                    showSlotSettingsSheet(forHome);
                }
            });
            prev.setOnClickListener(v -> slotSwitchPrev(forHome));
            pause.setOnClickListener(v -> toggleSlotPause(forHome));
            nextBtn.setOnClickListener(v -> slotSwitchNow(forHome));
        }
    }

    private void setupNowPage() {
        MaterialToolbar toolbar = findViewById(R.id.toolbar_now);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_log) {
                showSwitchLog();
            } else if (item.getItemId() == R.id.action_takeover_info) {
                showTakeoverInfoDialog();
            }
            return true;
        });
        homeViews = new ScopeViews(findViewById(R.id.now_home), true);
        lockViews = new ScopeViews(findViewById(R.id.now_lock), false);
        applyNowCardHeight();
        findViewById(R.id.guide_btn_1).setOnClickListener(v -> {
            bottomNav.setSelectedItemId(R.id.nav_gallery);
            if (LibraryStore.load(this).isEmpty()) {
                showNewLibDialog();
            }
        });
        findViewById(R.id.guide_btn_2).setOnClickListener(v -> showSlotSettingsSheet(true));
        findViewById(R.id.guide_btn_3).setOnClickListener(v -> enableTakeover());
        findViewById(R.id.card_quick_set).setOnClickListener(v -> showQuickSetSheet());
        findViewById(R.id.card_pinned).setOnClickListener(v -> onPinnedCardTapped());
        ((MaterialSwitch) findViewById(R.id.switch_pinned)).setOnCheckedChangeListener(
                (b, on) -> onPinnedSwitchToggled(on));
        findViewById(R.id.card_recent).setOnClickListener(v -> showSwitchLog());
    }

    /** 两张卡按屏幕高宽比取高：宽度铺满两列，高度 = 卡宽 × 屏幕高/宽，夹在 300–420dp。 */
    private void applyNowCardHeight() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        if (dm.widthPixels <= 0 || dm.heightPixels <= 0) {
            return;
        }
        int cardWidth = Math.max(1, (dm.widthPixels - dp(32) - dp(12)) / 2);
        int height = Math.round(cardWidth * (float) dm.heightPixels / dm.widthPixels);
        height = Math.max(dp(300), Math.min(height, dp(420)));
        setNowCardHeight(R.id.now_home, height);
        setNowCardHeight(R.id.now_lock, height);
    }

    private void setNowCardHeight(int id, int height) {
        View card = findViewById(id);
        ViewGroup.LayoutParams params = card.getLayoutParams();
        if (params != null && params.height != height) {
            params.height = height;
            card.setLayoutParams(params);
        }
    }

    private void refreshNowPage() {
        refreshSlotCards();
        refreshTakeoverUi();
        refreshTimerStatus();
        refreshSwitchLogRow();
        refreshPinnedCard();
    }

    /** 两面预览卡重绑（老名字留着：切换、改槽位、改配色之后各处都调它）。 */
    private void refreshSlotCards() {
        if (homeViews == null) {
            return;
        }
        bindScope(homeViews);
        bindScope(lockViews);
    }

    /** 绑一面：整图卡片，字与控件固定在中性白；图跟着该面在屏那张走。 */
    private void bindScope(ScopeViews v) {
        int inkColor = getColor(R.color.image_ink);
        int dimColor = getColor(R.color.image_ink_dim);
        ColorStateList ink = ColorStateList.valueOf(inkColor);
        v.card.setStrokeWidth(0);
        v.scope.setTextColor(inkColor);
        v.lib.setTextColor(inkColor);
        v.next.setTextColor(dimColor);
        v.desc.setTextColor(dimColor);
        v.scopeIcon.setImageResource(v.forHome ? R.drawable.ic_home : R.drawable.ic_lock);
        v.scopeIcon.setImageTintList(ink);
        v.prev.setIconTint(ink);
        v.pause.setIconTint(ink);
        v.nextBtn.setIconTint(ink);
        ((ImageView) v.card.findViewById(R.id.btn_now_settings)).setImageTintList(ink);
        v.lib.setCompoundDrawableTintList(ink);
        v.scope.setText(v.forHome ? R.string.now_scope_home : R.string.now_scope_lock);
        boolean paused = LibraryStore.slotPaused(this, v.forHome);
        v.pause.setIconResource(paused ? R.drawable.ic_play : R.drawable.ic_pause);
        v.pause.setContentDescription(getString(paused ? R.string.design_resume_switch : R.string.design_pause_switch));
        LibraryStore.Library lib = LibraryStore.slotLib(this, v.forHome);
        float controlAlpha = lib == null ? 0.35f : 1f;
        v.prev.setEnabled(lib != null);
        v.pause.setEnabled(lib != null);
        v.nextBtn.setEnabled(lib != null);
        v.prev.setAlpha(controlAlpha);
        v.pause.setAlpha(controlAlpha);
        v.nextBtn.setAlpha(controlAlpha);
        if (lib == null) {
            v.lib.setText(R.string.now_unset_lib);
            v.next.setText(R.string.now_unset_desc);
            v.desc.setText(R.string.slot_unset);
            v.thumb.setTag(null);
            v.thumb.setScaleType(ImageView.ScaleType.CENTER);
            v.thumb.setImageResource(v.forHome ? R.drawable.ic_home : R.drawable.ic_lock);
            v.thumb.setImageTintList(ColorStateList.valueOf(dimColor));
            return;
        }
        v.lib.setText(lib.name);
        v.next.setText(nextLine(v.forHome));
        v.desc.setText(slotIntervalDisplay(v.forHome));
        String id = slotThumbId(lib.id, v.forHome);
        v.thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (id == null) {
            v.thumb.setTag(null);
            v.thumb.setImageTintList(null);
            v.thumb.setImageDrawable(null);
            return;
        }
        images.full(v.thumb, id, "p:", getResources().getDisplayMetrics().widthPixels);
    }

    /** 预览卡上那行：已暂停 / 下次 HH:mm / 过点等补切。 */
    private String nextLine(boolean forHome) {
        if (LibraryStore.slotPaused(this, forHome)) {
            return getString(R.string.slot_paused_label);
        }
        // 事件档没有定时格子，「尚未排定」会让人以为没生效
        if (LibraryStore.slotLibId(this, forHome) != null && LibraryStore.isEventTrigger(this, forHome)) {
            return getString(forHome ? R.string.now_next_return : R.string.now_next_screen_off);
        }
        long next = TimerScheduler.scopeTrigger(this, forHome);
        if (next < 0) {
            return getString(R.string.now_next_none);
        }
        if (next <= System.currentTimeMillis()) {
            return getString(R.string.now_next_overdue);
        }
        return getString(R.string.now_next_at, formatClock(next));
    }

    /**
     * 暂停键：撤掉/重排这一面的周期任务，屏上那张原样停着。
     * WorkManager 的撤与排是进程内 IPC，跟其它设置写点一样放主线程调用。
     */
    private void toggleSlotPause(final boolean forHome) {
        if (LibraryStore.slotLib(this, forHome) == null) {
            Toast.makeText(this, R.string.slot_none_toast, Toast.LENGTH_SHORT).show();
            return;
        }
        final boolean paused = !LibraryStore.slotPaused(this, forHome);
        TimerScheduler.setPaused(getApplicationContext(), forHome, paused);
        refreshSlotCards();
        refreshTimerStatus();
        if (paused) {
            Toast.makeText(this, R.string.slot_paused_toast, Toast.LENGTH_SHORT).show();
        } else {
            long next = TimerScheduler.scopeTrigger(this, forHome);
            Toast.makeText(this, getString(R.string.slot_resumed_toast,
                    TimerScheduler.clockText(this, next)), Toast.LENGTH_SHORT).show();
        }
    }

    /** 下一张：槽位没库先提示去点预览图选库。 */
    private void slotSwitchNow(boolean forHome) {
        LibraryStore.Library lib = LibraryStore.slotLib(this, forHome);
        if (lib == null) {
            Toast.makeText(this, R.string.slot_none_toast, Toast.LENGTH_SHORT).show();
            return;
        }
        switchAndToast(lib, forHome);
    }

    /** 上一张（原来只有通知栏有这颗键）：随机模式没有可退的上一张时静默不动，与通知那颗一致。 */
    private void slotSwitchPrev(final boolean forHome) {
        final LibraryStore.Library lib = LibraryStore.slotLib(this, forHome);
        if (lib == null) {
            Toast.makeText(this, R.string.slot_none_toast, Toast.LENGTH_SHORT).show();
            return;
        }
        final Context app = getApplicationContext();
        new Thread(() -> {
            final boolean ok = Switcher.prev(app, lib.id, forHome);
            if (ok) {
                TimerScheduler.restartScope(app, forHome);
            }
            final String error = ok ? null : Switcher.lastError();
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (ok) {
                    Toast.makeText(this, R.string.switch_done, Toast.LENGTH_SHORT).show();
                    refreshNowPage();
                } else if (error != null) {
                    Toast.makeText(this, Switcher.errorText(this, error), Toast.LENGTH_LONG).show();
                }
            });
        }, "manual-prev").start();
    }

    /** 接管状态：顶栏副标题 + 三步引导 + 设置页那一行（状态全读系统真值）。 */
    void refreshTakeoverUi() {
        boolean active = TakeoverManager.isHomeTakenOver(this);
        MaterialToolbar toolbar = findViewById(R.id.toolbar_now);
        toolbar.setSubtitle(active ? R.string.takeover_chip_on : R.string.takeover_chip_off);
        toolbar.setSubtitleTextColor(MaterialColors.getColor(toolbar, active
                ? androidx.appcompat.R.attr.colorPrimary
                : com.google.android.material.R.attr.colorOnSurfaceVariant));
        boolean s1 = false;
        for (Integer n : WallpaperStore.libMemberCounts(this).values()) {
            if (n != null && n > 0) {
                s1 = true;
                break;
            }
        }
        boolean s2 = LibraryStore.slotLibId(this, true) != null;
        findViewById(R.id.card_guide).setVisibility(s1 && s2 && active ? View.GONE : View.VISIBLE);
        bindGuideStep(R.id.guide_icon_1, R.id.guide_btn_1, s1);
        bindGuideStep(R.id.guide_icon_2, R.id.guide_btn_2, s2);
        bindGuideStep(R.id.guide_icon_3, R.id.guide_btn_3, active);
        refreshSettings();
    }

    private void bindGuideStep(int iconId, int buttonId, boolean done) {
        ImageView icon = findViewById(iconId);
        icon.setImageResource(done ? R.drawable.ic_check_circle : R.drawable.ic_circle_outline);
        icon.setImageTintList(ColorStateList.valueOf(MaterialColors.getColor(icon, done
                ? androidx.appcompat.R.attr.colorPrimary : com.google.android.material.R.attr.colorOutline)));
        findViewById(buttonId).setVisibility(done ? View.INVISIBLE : View.VISIBLE);
    }

    /** 切换 / 设为桌面之后：在屏那张变了，此刻页预览、格子的「使用中」与详情页那行都要跟上。 */
    void refreshTakeoverStatus() {
        refreshCurrentPage();
    }

    /** 最近切换里那一行：两面最近一条记录的时间。纯读文件，放后台线程。 */
    void refreshSwitchLogRow() {
        final TextView state = findViewById(R.id.tv_switch_log);
        new Thread(() -> {
            final Context app = getApplicationContext();
            final String home = SwitchLog.latestTimeLabel(app, true);
            final String lock = SwitchLog.latestTimeLabel(app, false);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                state.setText(home == null && lock == null
                        ? getString(R.string.log_view_empty_row)
                        : getString(R.string.log_view_latest,
                                home == null ? getString(R.string.log_view_none) : home,
                                lock == null ? getString(R.string.log_view_none) : lock));
            });
        }, "log-row").start();
        refreshSettings();
    }

    // ==================== 快捷设置 ====================

    /**
     * 此刻页的快捷设置：任意库、任意一张，应用到桌面或锁屏。
     * 应用时若这张不在该面正在用的库里，先把该面的活动库换成它所在的库，再设为当值。
     */
    private void showQuickSetSheet() {
        if (quickSheet != null) {
            return;
        }
        quickLibs = LibraryStore.load(this);
        final View content = LayoutInflater.from(this).inflate(R.layout.dialog_quick_set, null, false);
        final BottomSheetDialog sheet = new QuickSetSheetDialog(this, content);
        quickSheet = sheet;

        quickPickAdapter = new QuickPickAdapter();
        RecyclerView picks = content.findViewById(R.id.rv_quick_picks);
        picks.setLayoutManager(new GridLayoutManager(this, 3));
        picks.setAdapter(quickPickAdapter);
        quickPickAdapter.onPick = () -> updateQuickApplyButton(content);

        content.findViewById(R.id.row_quick_lib).setOnClickListener(v -> showQuickLibPicker(content));

        if (quickLibs.isEmpty()) {
            content.findViewById(R.id.tv_quick_lib_heading).setVisibility(View.GONE);
            content.findViewById(R.id.row_quick_lib).setVisibility(View.GONE);
            picks.setVisibility(View.GONE);
            content.findViewById(R.id.btn_quick_apply).setVisibility(View.GONE);
            TextView empty = content.findViewById(R.id.tv_quick_empty);
            empty.setText(R.string.design_quick_set_no_lib);
            empty.setVisibility(View.VISIBLE);
        } else {
            String preferred = LibraryStore.slotLibId(this, true);
            boolean preferredExists = false;
            for (LibraryStore.Library lib : quickLibs) {
                if (lib.id.equals(preferred)) {
                    preferredExists = true;
                    break;
                }
            }
            loadQuickLib(content, preferredExists ? preferred : quickLibs.get(0).id);
        }

        content.findViewById(R.id.btn_quick_apply).setOnClickListener(v -> {
            if (settingWallpaper) {
                return;
            }
            WallpaperStore.Item item = quickPickAdapter.selected();
            if (item == null) {
                Toast.makeText(this, R.string.design_quick_set_need_pick, Toast.LENGTH_SHORT).show();
                return;
            }
            final String libId = quickLibId;
            new AppAlertDialogBuilder(this)
                    .setTitle(R.string.design_quick_set_apply)
                    .setItems(R.array.design_apply_targets, (dialog, which) -> {
                        boolean home = which != 1;
                        boolean lock = which != 0;
                        if (settingWallpaper || !ensureTakeoverForScope(home)) {
                            return;
                        }
                        sheet.dismiss();
                        applyWallpaper(item, libId, home, lock);
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        });

        sheet.setOnDismissListener(d -> {
            if (quickSheet == sheet) {
                quickSheet = null;
            }
            refreshCurrentPage();
        });
        sheet.show();
    }

    /** 当前库那一行：缩略图 + 库名 + 箭头，点开与卡片设置同一套库列表。 */
    private void bindQuickLibRow(View content) {
        TextView name = content.findViewById(R.id.tv_quick_lib_value);
        ImageView thumb = content.findViewById(R.id.img_quick_lib_thumb);
        LibraryStore.Library lib = quickLibId == null ? null : LibraryStore.get(this, quickLibId);
        if (lib == null) {
            name.setText(R.string.design_quick_set_none);
            bindThumb(thumb, null, R.drawable.ic_tab_wallpaper, 10);
            return;
        }
        name.setText(lib.name);
        bindThumb(thumb, libCoverId(lib), R.drawable.ic_tab_wallpaper, 10);
    }

    /** 选库：与卡片设置一样用缩略图 + 库名 + 张数的竖列表。 */
    private void showQuickLibPicker(View content) {
        final List<LibraryStore.Library> libs = LibraryStore.load(this);
        if (libs.isEmpty()) {
            return;
        }
        new AppAlertDialogBuilder(this)
                .setTitle(R.string.slot_pick_lib_title)
                .setAdapter(new QuickLibPickerAdapter(libs), (dialog, which) ->
                        loadQuickLib(content, libs.get(which).id))
                .setNegativeButton(R.string.close, null)
                .show();
    }

    private void loadQuickLib(View content, String libId) {
        quickLibId = libId;
        List<WallpaperStore.Item> items = WallpaperStore.loadByLib(this, libId);
        quickPickAdapter.setItems(items);
        boolean empty = items.isEmpty();
        content.findViewById(R.id.rv_quick_picks).setVisibility(empty ? View.GONE : View.VISIBLE);
        TextView emptyView = content.findViewById(R.id.tv_quick_empty);
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        emptyView.setText(R.string.design_quick_set_empty_lib);
        bindQuickLibRow(content);
        updateQuickApplyButton(content);
    }

    private void updateQuickApplyButton(View content) {
        WallpaperStore.Item item = quickPickAdapter == null ? null : quickPickAdapter.selected();
        MaterialButton apply = content.findViewById(R.id.btn_quick_apply);
        apply.setEnabled(item != null);
    }

    /** 设为壁纸前的接管前置条件；详情页的「设为壁纸」也走这一份判断。 */
    boolean ensureTakeoverForScope(boolean forHome) {
        if (!TakeoverManager.isEnabled(this)
                || (forHome && !TakeoverManager.isHomeTakenOver(this))) {
            new AppAlertDialogBuilder(this)
                    .setTitle(R.string.design_enable_first)
                    .setMessage(R.string.design_enable_first_message)
                    .setPositiveButton(R.string.guide_go_enable, (d, w) -> enableTakeover())
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            return false;
        }
        return true;
    }

    /** 快设壁纸格：复用库详情的格子（缩略图 / 格式角标 / 实况角标 / 使用中），选中画勾与描边。 */
    private class QuickPickAdapter extends ImageAdapter {

        QuickPickAdapter() { super(MainActivity.this); }

        private final List<WallpaperStore.Item> items = new ArrayList<>();
        private String selectedId;
        private Runnable onPick;

        void setItems(List<WallpaperStore.Item> newItems) {
            items.clear();
            items.addAll(newItems);
            selectedId = items.isEmpty() ? null : items.get(0).id;
            notifyDataSetChanged();
        }

        WallpaperStore.Item selected() {
            for (WallpaperStore.Item item : items) {
                if (item.id.equals(selectedId)) {
                    return item;
                }
            }
            return null;
        }

        int indexOfSelected() {
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).id.equals(selectedId)) {
                    return i;
                }
            }
            return -1;
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new RecyclerView.ViewHolder(LayoutInflater.from(MainActivity.this)
                    .inflate(R.layout.item_wallpaper, parent, false)) {
            };
        }

        @Override
        void bindRow(RecyclerView.ViewHolder holder, int position) {
            View v = holder.itemView;
            final WallpaperStore.Item item = items.get(position);
            ImageView img = v.findViewById(R.id.img_thumb);
            images.thumbnail(img, item.id);
            img.setImageAlpha(255);
            bindCellInfo(v, item.id, false,
                    (owner, info) -> applyCellInfo(owner, info, false));
            TextView inUse = v.findViewById(R.id.tv_in_use);
            inUse.setVisibility(Switcher.inUseWhere(MainActivity.this, item.id) != null
                    ? View.VISIBLE : View.GONE);
            inUse.setText(R.string.in_use_badge);
            v.findViewById(R.id.iv_fav_badge).setVisibility(item.favorite ? View.VISIBLE : View.GONE);
            boolean selected = item.id.equals(selectedId);
            v.findViewById(R.id.iv_selected_check).setVisibility(selected ? View.VISIBLE : View.GONE);
            v.setOnClickListener(x -> {
                int pos = holder.getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION || pos >= items.size()) {
                    return;
                }
                selectedId = items.get(pos).id;
                notifyDataSetChanged();
                if (onPick != null) {
                    onPick.run();
                }
            });
        }
    }

    /** 快设选库列表：与卡片设置同一行式，只列库、不列「不切换」。 */
    private class QuickLibPickerAdapter extends BaseAdapter {

        private final List<LibraryStore.Library> libs;
        private final Map<String, Integer> counts = WallpaperStore.libMemberCounts(MainActivity.this);

        QuickLibPickerAdapter(List<LibraryStore.Library> libs) {
            this.libs = libs;
        }

        @Override
        public int getCount() {
            return libs.size();
        }

        @Override
        public Object getItem(int position) {
            return libs.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @NonNull
        @Override
        public View getView(int position, View convertView, @NonNull ViewGroup parent) {
            View row = convertView != null ? convertView
                    : LayoutInflater.from(MainActivity.this)
                            .inflate(R.layout.item_slot_lib, parent, false);
            LibraryStore.Library lib = libs.get(position);
            ((TextView) row.findViewById(R.id.tv_pick_name)).setText(lib.name);
            Integer count = counts.get(lib.id);
            ((TextView) row.findViewById(R.id.tv_pick_sub)).setText(getString(R.string.lib_count,
                    count == null ? 0 : count));
            bindThumb((ImageView) row.findViewById(R.id.img_pick_thumb),
                    libCoverId(lib), R.drawable.ic_tab_wallpaper, 10);
            row.findViewById(R.id.iv_pick_check).setVisibility(
                    lib.id.equals(quickLibId) ? View.VISIBLE : View.INVISIBLE);
            return row;
        }
    }

    // ==================== 一面的设置面板 ====================

    /**
     * 一面的设置底部面板（原来单击槽位卡弹的那个窗）：壁纸库、顺序/随机、间隔、暂停。改动即保存。
     */
    private void showSlotSettingsSheet(final boolean forHome) {
        final BottomSheetDialog sheet = new BottomSheetDialog(this);
        final View content = LayoutInflater.from(this).inflate(R.layout.dialog_slot_settings, null, false);
        sheet.setContentView(content);
        int titleRes = forHome ? R.string.slot_home_title : R.string.slot_lock_title;
        ((TextView) content.findViewById(R.id.tv_sheet_title)).setText(titleRes);
        bindSlotLibRow(content, forHome);
        content.findViewById(R.id.row_slot_lib).setOnClickListener(
                v -> showLibPickerDialog(forHome, content));
        // 按时段换库（v3.191）：摘要行 + 点进二级配置
        bindTimeLibRow(content, forHome);
        content.findViewById(R.id.row_time_lib).setOnClickListener(
                v -> showTimeLibSheet(forHome, content));
        MaterialButtonToggleGroup mode = content.findViewById(R.id.toggle_mode);
        mode.check(LibraryStore.MODE_RANDOM.equals(LibraryStore.scopeMode(this, forHome))
                ? R.id.btn_mode_random : R.id.btn_mode_order);
        // 先 check 再挂监听，程序化 check 不触发写回；模式只影响副标题，不必重排定时
        mode.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) {
                return;
            }
            LibraryStore.setScopeMode(this, forHome, checkedId == R.id.btn_mode_random
                    ? LibraryStore.MODE_RANDOM : LibraryStore.MODE_ORDER);
            refreshSlotCards();
        });
        final TextView tvInterval = content.findViewById(R.id.tv_interval);
        final TextView tvIntervalLabel = content.findViewById(R.id.tv_interval_label);
        final TextView tvTriggerHint = content.findViewById(R.id.tv_trigger_hint);
        bindTriggerRows(forHome, tvIntervalLabel, tvInterval, tvTriggerHint);
        content.findViewById(R.id.row_interval).setOnClickListener(v -> {
            // 同一行两用：定时档调「切换间隔」，事件档调「最小间隔」
            if (LibraryStore.isEventTrigger(this, forHome)) {
                showMinGapPicker(forHome, tvInterval);
            } else {
                showIntervalPicker(forHome, tvInterval);
            }
        });
        // 触发方式（v3.170 从设置页挪进来）：右边那颗按面填字，先 check 再挂监听
        ((Button) content.findViewById(R.id.btn_trigger_event)).setText(forHome
                ? R.string.trigger_short_return : R.string.trigger_short_screen_off);
        MaterialButtonToggleGroup trigger = content.findViewById(R.id.toggle_trigger);
        trigger.check(LibraryStore.isEventTrigger(this, forHome)
                ? R.id.btn_trigger_event : R.id.btn_trigger_timer);
        trigger.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) {
                return;
            }
            LibraryStore.setScopeTriggerMode(this, forHome, checkedId == R.id.btn_trigger_event
                    ? LibraryStore.eventTrigger(forHome) : LibraryStore.TRIGGER_TIMER);
            bindTriggerRows(forHome, tvIntervalLabel, tvInterval, tvTriggerHint);
            refreshTimerStatus();
            refreshSlotCards();
        });
        View clear = content.findViewById(R.id.btn_slot_clear);
        clear.setVisibility(LibraryStore.slotLibId(this, forHome) == null ? View.GONE : View.VISIBLE);
        clear.setOnClickListener(v -> {
            sheet.dismiss();
            confirmSlotLib(forHome, null, null);
        });
        content.findViewById(R.id.btn_slot_done).setOnClickListener(v -> sheet.dismiss());
        MaterialSwitch pause = content.findViewById(R.id.sw_slot_pause);
        pause.setEnabled(LibraryStore.slotLibId(this, forHome) != null);
        pause.setChecked(LibraryStore.slotPaused(this, forHome));
        pause.setOnCheckedChangeListener((button, checked) -> {
            if (checked != LibraryStore.slotPaused(this, forHome)) {
                toggleSlotPause(forHome);
            }
        });
        sheet.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
        sheet.getBehavior().setSkipCollapsed(true);
        sheet.setOnDismissListener(d -> refreshCurrentPage());
        sheet.show();
    }

    // ==================== 按时段换库（v3.191） ====================

    /** 面板里「按时段换库」那一行的摘要：关 / 07:00 山景 · 23:00 星空（多于两条折叠成「等N段」）。 */
    private void bindTimeLibRow(View content, boolean forHome) {
        TextView tv = content.findViewById(R.id.tv_time_lib_value);
        tv.setText(timeLibSummary(forHome));
    }

    /** 摘要文案：无配置 = 「关」。 */
    String timeLibSummary(boolean forHome) {
        List<LibScheduleStore.Rule> rules = LibScheduleStore.load(this, forHome);
        if (rules.isEmpty()) {
            return getString(R.string.time_lib_off);
        }
        StringBuilder sb = new StringBuilder();
        int shown = Math.min(2, rules.size());
        for (int i = 0; i < shown; i++) {
            LibScheduleStore.Rule r = rules.get(i);
            if (sb.length() > 0) {
                sb.append(getString(R.string.time_lib_summary_sep));
            }
            sb.append(timeText(r.start)).append(' ').append(libNameOf(r.libId));
        }
        if (rules.size() > shown) {
            sb.append(getString(R.string.time_lib_more, rules.size() - shown));
        }
        return sb.toString();
    }

    /** 时刻文案：当天分钟数 → HH:mm。 */
    private String timeText(int minutes) {
        return String.format(Locale.getDefault(), "%02d:%02d", minutes / 60, minutes % 60);
    }

    /** 库名（「全部壁纸」也认；库已删返回空串）。 */
    private String libNameOf(String libId) {
        LibraryStore.Library lib = LibraryStore.get(this, libId);
        return lib == null ? "" : lib.name;
    }

    /**
     * 二级配置面板：时段列表（点行编辑、行尾删除键移除）+「添加时段」。改动即保存并续排边界任务。
     * 关面板时若动过配置、且「此刻所属时段的目标库」与当前库不同，弹一次标准的换库确认
     * （confirmSlotLib，与手动选库同一链路）—— 让配置当场可见地生效，而不是干等下一个边界。
     */
    private void showTimeLibSheet(final boolean forHome, final View slotSheetContent) {
        if (LibraryStore.slotLibId(this, forHome) == null) {
            Toast.makeText(this, R.string.time_lib_need_slot, Toast.LENGTH_SHORT).show();
            return;
        }
        final BottomSheetDialog sheet = new BottomSheetDialog(this);
        final View content = LayoutInflater.from(this).inflate(R.layout.dialog_time_lib, null, false);
        sheet.setContentView(content);
        // 工作副本：改一条存一次；dirty 记录本次动过配置
        final List<LibScheduleStore.Rule> rules = new ArrayList<>(LibScheduleStore.load(this, forHome));
        final boolean[] dirty = {false};
        final LinearLayout box = content.findViewById(R.id.box_time_rules);
        final View emptyHint = content.findViewById(R.id.tv_time_rules_empty);
        final Runnable[] render = new Runnable[1];
        final Runnable persist = () -> {
            LibScheduleStore.save(this, forHome, rules);
            // WorkManager 的排是进程内 IPC，主线程调用（与其它设置写点同口径）
            LibScheduler.reschedule(this, forHome);
            bindTimeLibRow(slotSheetContent, forHome);
            refreshSlotCards();
        };
        render[0] = () -> {
            box.removeAllViews();
            emptyHint.setVisibility(rules.isEmpty() ? View.VISIBLE : View.GONE);
            for (int i = 0; i < rules.size(); i++) {
                final int index = i;
                LibScheduleStore.Rule r = rules.get(i);
                View row = LayoutInflater.from(MainActivity.this)
                        .inflate(R.layout.item_time_rule, box, false);
                ((TextView) row.findViewById(R.id.tv_rule_time)).setText(getString(
                        R.string.time_lib_rule_time, timeText(r.start), timeText(r.end)));
                ((TextView) row.findViewById(R.id.tv_rule_lib)).setText(libNameOf(r.libId));
                row.setOnClickListener(v ->
                        showTimeRuleDialog(forHome, rules, index, dirty, persist, render[0]));
                row.findViewById(R.id.iv_rule_delete).setOnClickListener(v -> {
                    new AppAlertDialogBuilder(MainActivity.this)
                            .setTitle(R.string.time_lib_delete_title)
                            .setMessage(getString(R.string.time_lib_delete_msg,
                                    timeText(r.start), timeText(r.end), libNameOf(r.libId)))
                            .setPositiveButton(R.string.confirm, (d, w) -> {
                                rules.remove(index);
                                dirty[0] = true;
                                persist.run();
                                render[0].run();
                            })
                            .setNegativeButton(R.string.cancel, null)
                            .show();
                });
                box.addView(row);
            }
        };
        render[0].run();
        content.findViewById(R.id.btn_time_add).setOnClickListener(v ->
                showTimeRuleDialog(forHome, rules, -1, dirty, persist, render[0]));
        content.findViewById(R.id.btn_time_done).setOnClickListener(v -> sheet.dismiss());
        sheet.setOnDismissListener(d -> {
            if (!dirty[0]) {
                return;
            }
            String target = LibScheduleStore.currentLibId(this, forHome,
                    LibScheduleStore.minutesOfDay(System.currentTimeMillis()));
            String live = LibraryStore.slotLibId(this, forHome);
            if (target != null && !target.equals(live) && !LibraryStore.slotPaused(this, forHome)) {
                confirmSlotLib(forHome, target, slotSheetContent);
            }
        });
        sheet.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
        sheet.getBehavior().setSkipCollapsed(true);
        sheet.show();
    }

    /** 新增/编辑时段：主弹窗显示时间字段与库列表，点时间字段再开滚轮。 */
    private void showTimeRuleDialog(boolean forHome, final List<LibScheduleStore.Rule> rules,
            final int index, final boolean[] dirty, final Runnable persist, final Runnable render) {
        final boolean isNew = index < 0;
        final LibScheduleStore.Rule draft = isNew
                ? new LibScheduleStore.Rule(7 * 60, 23 * 60, null)
                : new LibScheduleStore.Rule(rules.get(index).start, rules.get(index).end,
                        rules.get(index).libId);
        final View content = LayoutInflater.from(this)
                .inflate(R.layout.dialog_time_rule_fields, null, false);
        final TextView startTime = content.findViewById(R.id.tv_rule_start_time);
        final TextView endTime = content.findViewById(R.id.tv_rule_end_time);
        final TextView timeError = content.findViewById(R.id.tv_rule_time_error);
        final Runnable refreshTimes = () -> {
            startTime.setText(timeText(draft.start));
            endTime.setText(timeText(draft.end));
        };
        refreshTimes.run();

        final LinearLayout libBox = content.findViewById(R.id.box_rule_libs);
        final List<LibraryStore.Library> libs = LibraryStore.loadWithAll(this);
        for (LibraryStore.Library lib : libs) {
            View row = LayoutInflater.from(this).inflate(R.layout.item_time_rule_lib, libBox, false);
            ((TextView) row.findViewById(R.id.tv_rule_lib_name)).setText(lib.name);
            int count = WallpaperStore.loadByLib(this, lib.id).size();
            ((TextView) row.findViewById(R.id.tv_rule_lib_sub)).setText(
                    LibraryStore.isAll(lib.id)
                            ? getString(R.string.lib_all_sub, count)
                            : getString(R.string.lib_count, count));
            bindThumb((ImageView) row.findViewById(R.id.img_rule_lib_thumb),
                    slotThumbId(lib.id, forHome), R.drawable.ic_tab_wallpaper, 8);
            row.setTag(lib.id);
            libBox.addView(row);
        }
        final Runnable refreshLibSelection = () -> {
            for (int i = 0; i < libBox.getChildCount(); i++) {
                View row = libBox.getChildAt(i);
                row.findViewById(R.id.iv_rule_lib_radio).setVisibility(
                        row.getTag().equals(draft.libId) ? View.VISIBLE : View.INVISIBLE);
            }
        };
        refreshLibSelection.run();
        for (int i = 0; i < libBox.getChildCount(); i++) {
            final String libId = (String) libBox.getChildAt(i).getTag();
            libBox.getChildAt(i).setOnClickListener(v -> {
                draft.libId = libId;
                refreshLibSelection.run();
            });
        }

        AlertDialog dialog = new AppAlertDialogBuilder(this)
                .setTitle(isNew ? R.string.time_lib_new_title : R.string.time_lib_edit_title)
                .setView(content)
                .setPositiveButton(R.string.confirm, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.show();
        final Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        final Runnable validateTime = () -> {
            String error = timeRuleValidationError(rules, index, draft.start, draft.end);
            timeError.setText(error);
            timeError.setVisibility(error == null ? View.GONE : View.VISIBLE);
            positive.setEnabled(error == null);
        };
        final Runnable onTimeChanged = () -> {
            refreshTimes.run();
            validateTime.run();
        };
        content.findViewById(R.id.field_rule_start).setOnClickListener(v ->
                showTimeWheelPicker(rules, index, draft, true, onTimeChanged));
        content.findViewById(R.id.field_rule_end).setOnClickListener(v ->
                showTimeWheelPicker(rules, index, draft, false, onTimeChanged));
        validateTime.run();
        positive.setOnClickListener(v -> {
            if (timeRuleValidationError(rules, index, draft.start, draft.end) != null) {
                validateTime.run();
                return;
            }
            if (draft.libId == null) {
                Toast.makeText(this, R.string.time_lib_no_lib, Toast.LENGTH_SHORT).show();
                return;
            }
            if (isNew) {
                rules.add(draft);
            } else {
                rules.set(index, draft);
            }
            dirty[0] = true;
            persist.run();
            render.run();
            dialog.dismiss();
        });
    }

    /** 单个时刻的滚轮弹窗：变化即校验，冲突时禁用「完成」。 */
    private void showTimeWheelPicker(final List<LibScheduleStore.Rule> rules, final int index,
            final LibScheduleStore.Rule draft, final boolean editingStart, final Runnable onChanged) {
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        int pad8 = (int) (8 * getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, pad8);
        final NumberPicker[] wheels = timeWheels(editingStart ? draft.start : draft.end);
        box.addView(ruleWheelRow(wheels));
        final TextView timeError = new TextView(this);
        timeError.setTextSize(12);
        timeError.setTextColor(getColor(R.color.danger));
        timeError.setPadding(0, pad8, 0, 0);
        timeError.setVisibility(View.GONE);
        box.addView(timeError);

        AlertDialog dialog = new AppAlertDialogBuilder(this)
                .setTitle(editingStart ? R.string.time_lib_pick_start_title : R.string.time_lib_pick_end_title)
                .setView(box)
                .setPositiveButton(R.string.done, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.show();
        final Button done = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        final Runnable validate = () -> {
            int chosen = wheels[0].getValue() * 60 + wheels[1].getValue() * 15;
            int start = editingStart ? chosen : draft.start;
            int end = editingStart ? draft.end : chosen;
            String error = timeRuleValidationError(rules, index, start, end);
            timeError.setText(error);
            timeError.setVisibility(error == null ? View.GONE : View.VISIBLE);
            done.setEnabled(error == null);
        };
        NumberPicker.OnValueChangeListener listener = (picker, oldValue, newValue) -> validate.run();
        for (NumberPicker wheel : wheels) {
            wheel.setOnValueChangedListener(listener);
        }
        validate.run();
        done.setOnClickListener(v -> {
            if (!done.isEnabled()) {
                return;
            }
            int chosen = wheels[0].getValue() * 60 + wheels[1].getValue() * 15;
            if (editingStart) {
                draft.start = chosen;
            } else {
                draft.end = chosen;
            }
            onChanged.run();
            dialog.dismiss();
        });
    }

    private String timeRuleValidationError(List<LibScheduleStore.Rule> rules, int index,
            int start, int end) {
        if (start == end) {
            return getString(R.string.time_lib_same_hint);
        }
        for (int i = 0; i < rules.size(); i++) {
            if (i == index) {
                continue;
            }
            LibScheduleStore.Rule other = rules.get(i);
            if (LibScheduleStore.overlaps(start, end, other.start, other.end)) {
                return getString(R.string.time_lib_overlap_hint,
                        timeText(other.start), timeText(other.end), libNameOf(other.libId));
            }
        }
        return null;
    }

    /** 一对「时 / 分」滚轮（分钟按 15 步进，与间隔滚轮同口径）。 */
    private NumberPicker[] timeWheels(int minutes) {
        NumberPicker hour = new NumberPicker(this);
        hour.setMinValue(0);
        hour.setMaxValue(23);
        hour.setWrapSelectorWheel(true);
        hour.setFormatter(value -> String.format(Locale.getDefault(), "%02d", value));
        hour.setValue(minutes / 60);
        NumberPicker minute = new NumberPicker(this);
        minute.setMinValue(0);
        minute.setMaxValue(3);
        minute.setWrapSelectorWheel(true);
        minute.setDisplayedValues(new String[]{"00", "15", "30", "45"});
        minute.setValue((minutes % 60) / 15);
        return new NumberPicker[]{hour, minute};
    }

    /** 一组「时 / 分」滚轮排成一行（复用间隔滚轮的 wheelColumn 列样式）。 */
    private LinearLayout ruleWheelRow(NumberPicker[] wheels) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.addView(wheelColumn(wheels[0], getString(R.string.interval_hour)));
        row.addView(wheelColumn(wheels[1], getString(R.string.interval_minute)));
        return row;
    }

    // ==================== 图库 ====================

    void setupGalleryPage() {
        gallery.setupGalleryPage();
    }

    void refreshLibs() {
        gallery.refreshLibs();
    }

    void confirmDeleteLibs(final List<LibraryStore.Library> libs) {
        gallery.confirmDeleteLibs(libs);
    }

    // ==================== 库详情 ====================

    private void setupLibraryPage() {
        libraryToolbar = findViewById(R.id.toolbar_library);
        libraryRecycler = findViewById(R.id.recycler_library);
        wallpaperAdapter = new WallpaperAdapter();
        wallpaperColumns = prefs.getInt("wallpaper_columns", 2) == 3 ? 3 : 2;
        wallpaperLayout = new GridLayoutManager(this, wallpaperColumns);
        wallpaperLayout.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return position == 0 ? wallpaperColumns : 1;
            }
        });
        libraryRecycler.setLayoutManager(wallpaperLayout);
        libraryRecycler.setAdapter(wallpaperAdapter);
        libraryToolbar.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_rename_lib) {
                showRenameLibDialog();
            } else if (id == R.id.action_select_wallpapers) {
                enterOrganizeMode();
            } else if (id == R.id.action_delete_lib) {
                LibraryStore.Library lib = currentLib();
                if (lib != null) {
                    confirmDeleteLibs(Collections.singletonList(lib));
                }
            } else if (id == R.id.action_select_all) {
                wallpaperAdapter.toggleSelectAll();
            }
            return true;
        });
        findViewById(R.id.btn_add).setOnClickListener(v -> showAddSourceSheet());
        setupAddFab();
        updateWallpaperOrganizeToolbar();
    }

    /**
     * 「添加」那颗键的两件事：
     * 一是网格往下滚就收成圆钮、回到顶部再摊开长条（三列时一格只有一百来 dp，长条压着右下角那格太显眼）；
     * 二是网格底部按它实测高度留位，而不是写死 96dp —— 大字体 / 大显示尺寸下这颗键会更高，
     * 写死的话滚到底最后一排仍被压住。
     */
    private void setupAddFab() {
        final ExtendedFloatingActionButton add = findViewById(R.id.btn_add);
        add.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            // 收起/摊开只变宽不变高，这里不会来回抖；相等就不再 setPadding，免得层层触发重排
            int reserve = add.getHeight() + dp(32);
            if (add.getHeight() > 0 && libraryRecycler.getPaddingBottom() != reserve) {
                libraryRecycler.setPadding(libraryRecycler.getPaddingStart(),
                        libraryRecycler.getPaddingTop(), libraryRecycler.getPaddingEnd(), reserve);
            }
        });
        libraryRecycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                syncAddFab();
            }
        });
    }

    /** 顶部摊开、离开顶部收起。EFAB 自己会忽略重复的 extend/shrink，可以每帧照调。 */
    private void syncAddFab() {
        ExtendedFloatingActionButton add = findViewById(R.id.btn_add);
        if (libraryRecycler.canScrollVertically(-1)) {
            add.shrink();
        } else {
            add.extend();
        }
    }

    /** 刷新库详情（头部 + 网格），单张详情开着的话一起跟上。 */
    void refreshList() {
        pruneDetailItems();
        LibraryStore.Library lib = currentLib();
        List<WallpaperStore.Item> items = lib == null
                ? new ArrayList<>() : WallpaperStore.loadByLib(this, lib.id);
        wallpaperAdapter.setItems(items);
        updateWallpaperOrganizeToolbar();
        // 删/移之后列表可能短到滚不动了，这时不会再来滚动回调，收起的「添加」得自己摊回去
        libraryRecycler.post(this::syncAddFab);
        // 筛选态下 setItems 是异步发布的，这一拍 items() 还是旧的；刷详情交给它的回调，
        // 否则会闪一下已经删掉/移走的那张
        if (wallpaperFilter == 0) {
            refreshDetail();
        }
    }

    /** 当前库 id（未初始化时从持久化恢复；指的是库详情正在看的库）。 */
    String currentLibId() {
        if (currentLibId == null) {
            currentLibId = prefs.getString(LibraryStore.KEY_CURRENT_LIB, null);
        }
        return currentLibId;
    }

    /** 库详情正在看的库，可能为 null。 */
    private LibraryStore.Library currentLib() {
        String id = currentLibId();
        return id == null ? null : LibraryStore.get(this, id);
    }

    /** 改库名（原来是点库名就地改，现在是顶栏铅笔开小窗）；留空保留原名。 */
    private void showRenameLibDialog() {
        final LibraryStore.Library lib = currentLib();
        if (lib == null) {
            return;
        }
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(lib.name);
        input.selectAll();
        new AppAlertDialogBuilder(this)
                .setTitle(R.string.lib_rename)
                .setView(wrapInput(input, R.string.lib_name_hint))
                .setPositiveButton(R.string.confirm, (dialog, which) -> {
                    LibraryStore.setName(this, lib.id, input.getText().toString());
                    refreshList();
                    refreshSlotCards();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
        input.requestFocus();
    }

    /** 进多选：底部动作条（移动到 / 复制到 / 移出）顶上，「添加」收起。 */
    private void enterOrganizeMode() {
        wallpaperAdapter.setOrganizeMode(true);
        ((ExtendedFloatingActionButton) findViewById(R.id.btn_add)).hide();
        selectionBar.setVisibility(View.VISIBLE);
        updateBottomNav();
        updateWallpaperOrganizeToolbar();
    }

    /** 退多选：勾选清空，动作条与「添加」回到原样。 */
    private void exitOrganizeMode() {
        if (!wallpaperAdapter.isOrganizeMode()) {
            return;
        }
        wallpaperAdapter.setOrganizeMode(false);
        ((ExtendedFloatingActionButton) findViewById(R.id.btn_add)).show();
        selectionBar.setVisibility(View.GONE);
        updateBottomNav();
        updateWallpaperOrganizeToolbar();
    }

    /** 顶栏随多选态刷新：普通 = ← 库名 + 铅笔 + ⋯；多选 = ✕ + 已选 N 张 + 全选。 */
    private void updateWallpaperOrganizeToolbar() {
        if (libraryToolbar == null) {
            return;
        }
        boolean organize = wallpaperAdapter.isOrganizeMode();
        int count = wallpaperAdapter.selectedCount();
        boolean selected = count > 0;
        // 悬浮栏只有图标：不可点的那几颗压到 38% 透明度，ImageButton 自己不会变灰
        bindSelectionButton(R.id.sel_fav, selected);
        bindSelectionButton(R.id.sel_save, selected);
        bindSelectionButton(R.id.sel_set_wallpaper, count == 1);
        bindSelectionButton(R.id.sel_remove, selected);
        bindSelectionButton(R.id.sel_more, selected);
        bindFavoriteButton();
        if (!organize || !selected) {
            dismissSelectionMore();
        }
        Menu menu = libraryToolbar.getMenu();
        menu.setGroupVisible(R.id.group_library_normal, !organize);
        menu.setGroupVisible(R.id.group_library_select, organize);
        if (organize) {
            libraryToolbar.setNavigationIcon(R.drawable.ic_close);
            libraryToolbar.setNavigationOnClickListener(v -> exitOrganizeMode());
            libraryToolbar.setTitle(getString(R.string.sel_count, wallpaperAdapter.selectedCount()));
        } else {
            libraryToolbar.setNavigationIcon(R.drawable.ic_back);
            libraryToolbar.setNavigationOnClickListener(v -> closeLibrary());
            LibraryStore.Library lib = currentLib();
            libraryToolbar.setTitle(lib == null ? "" : lib.name);
        }
    }

    private void setupSelectionBar() {
        findViewById(R.id.sel_fav).setOnClickListener(v -> toggleFavoriteForSelection());
        findViewById(R.id.sel_save).setOnClickListener(v -> saveSelectionToAlbum());
        findViewById(R.id.sel_set_wallpaper).setOnClickListener(v -> setWallpaperForSelection());
        findViewById(R.id.sel_remove).setOnClickListener(
                v -> confirmRemoveSelected(wallpaperAdapter.selectedIdsInOrder()));
        findViewById(R.id.sel_more).setOnClickListener(v -> showSelectionMore(v));
    }

    private void bindSelectionButton(int id, boolean enabled) {
        View button = findViewById(id);
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.32f);
    }

    /** 勾的全都收藏了：心形实心，再点就是取消收藏；否则空心，点了把没收藏的那些都收藏上。 */
    private boolean selectionAllFavorite() {
        List<String> ids = wallpaperAdapter.selectedIdsInOrder();
        if (ids.isEmpty()) {
            return false;
        }
        for (String id : ids) {
            WallpaperStore.Item item = WallpaperStore.get(this, id);
            if (item == null || !item.favorite) {
                return false;
            }
        }
        return true;
    }

    private void bindFavoriteButton() {
        ImageButton fav = findViewById(R.id.sel_fav);
        boolean all = selectionAllFavorite();
        fav.setImageResource(all ? R.drawable.ic_sel_heart_filled : R.drawable.ic_sel_heart);
        CharSequence label = getString(all ? R.string.sel_unfav : R.string.sel_fav);
        fav.setContentDescription(label);
        fav.setTooltipText(label);
    }

    /**
     * 批量收藏 / 取消收藏：勾的里面还有没收藏的就一律收藏，全都收藏了就一律取消。
     * 只改 library.json 的 fav 字段；做完<b>不退出多选</b>，心形当场变实心/空心就是反馈。
     */
    private void toggleFavoriteForSelection() {
        List<String> ids = wallpaperAdapter.selectedIdsInOrder();
        if (ids.isEmpty()) {
            return;
        }
        boolean target = !selectionAllFavorite();
        List<WallpaperStore.Item> items = WallpaperStore.load(this);
        for (WallpaperStore.Item item : items) {
            if (ids.contains(item.id)) {
                item.favorite = target;
            }
        }
        try {
            WallpaperStore.saveItems(this, items);
        } catch (Exception e) {
            Toast.makeText(this, R.string.organize_failed, Toast.LENGTH_LONG).show();
            return;
        }
        bindFavoriteButton();
        Toast.makeText(this, target ? R.string.fav_done : R.string.unfav_done, Toast.LENGTH_SHORT).show();
        // 格子右下角的粉心当场跟上（DisplayRow 带着 favorite，只重绑变了的那几格）
        refreshList();
    }

    /**
     * 保存：先问存哪一份（成品图 / 原图，v3.189），再把勾的那几张的原始字节存进相册 Pictures/WallSwitch。
     * 同一张同一份再存是覆盖，不叠副本；原图名字带 _original，和成品图分开。
     * 选原图时没留存原图的老壁纸跳过并报数。弹窗是纯列表（不配 setMessage，否则列表被量成零高）。
     */
    private void saveSelectionToAlbum() {
        final List<String> ids = wallpaperAdapter.selectedIdsInOrder();
        if (ids.isEmpty()) {
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Toast.makeText(this, R.string.album_save_unsupported, Toast.LENGTH_LONG).show();
            return;
        }
        new AppAlertDialogBuilder(this)
                .setTitle(R.string.save_pick_title)
                .setItems(new CharSequence[]{getString(R.string.save_pick_full),
                        getString(R.string.save_pick_original), getString(R.string.save_pick_both)},
                        (d, which) -> saveToAlbum(ids, which != 1, which != 0))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** @param full 存成品图；@param original 存原图（「均下载」两个都为真） */
    private void saveToAlbum(final List<String> ids, final boolean full, final boolean original) {
        final Context app = getApplicationContext();
        Toast.makeText(this, getString(R.string.album_save_running, ids.size()), Toast.LENGTH_SHORT).show();
        exitOrganizeMode();
        new Thread(() -> {
            int fullOk = 0;
            int originalOk = 0;
            int missing = 0;
            int failed = 0;
            for (String id : ids) {
                String tag = id.substring(0, Math.min(8, id.length()));
                if (full) {
                    File file = WallpaperStore.getFullFile(app, id);
                    boolean png = file.getName().endsWith(".png");
                    if (file.isFile() && saveFileToAlbum(app, file,
                            "wallswitch_" + tag + (png ? ".png" : ".jpg"), png ? "image/png" : "image/jpeg")) {
                        fullOk++;
                    } else {
                        failed++;
                    }
                }
                if (original) {
                    File file = WallpaperStore.getOriginalFile(app, id);
                    if (!file.isFile() || file.length() == 0L) {
                        missing++;
                    } else {
                        String[] type = WallpaperInfo.albumTypeOf(WallpaperInfo.sniffFileFormat(file));
                        if (saveFileToAlbum(app, file, "wallswitch_" + tag + "_original" + type[0], type[1])) {
                            originalOk++;
                        } else {
                            failed++;
                        }
                    }
                }
            }
            final int savedFull = fullOk;
            final int savedOriginal = originalOk;
            final int saved = fullOk + originalOk;
            final int skipped = missing;
            final int failedCount = failed;
            runOnUiThread(() -> {
                String text;
                if (full && original && saved > 0 && failedCount == 0) {
                    // 均下载：两份分开报数（缺原图的老壁纸只少了原图那一份）
                    text = getString(R.string.album_save_both_done, savedFull, savedOriginal);
                } else if (saved == 0 && skipped > 0 && failedCount == 0) {
                    text = getString(R.string.album_save_none_original);
                } else if (saved == 0) {
                    text = getString(R.string.album_save_failed);
                } else if (failedCount > 0) {
                    text = getString(R.string.album_save_partial, saved, failedCount + skipped);
                } else if (skipped > 0) {
                    text = getString(R.string.album_save_no_original, saved, skipped);
                } else {
                    text = getString(R.string.album_save_done, saved);
                }
                Toast.makeText(app, text, Toast.LENGTH_LONG).show();
            });
        }, "save-selection").start();
    }

    private static boolean saveFileToAlbum(Context app, File file, String name, String mime) {
        try (FileInputStream in = new FileInputStream(file)) {
            return TakeoverManager.saveStreamToAlbum(app, name, mime, in) != null;
        } catch (Exception | OutOfMemoryError e) {
            return false;
        }
    }

    /** 设为壁纸：恰好勾一张时可点，走单张预览页那个「桌面 / 锁屏 / 均应用」弹窗。 */
    private void setWallpaperForSelection() {
        List<String> ids = wallpaperAdapter.selectedIdsInOrder();
        if (ids.size() != 1) {
            return;
        }
        WallpaperStore.Item item = WallpaperStore.get(this, ids.get(0));
        if (item == null) {
            return;
        }
        exitOrganizeMode();
        showSetWallpaperDialog(item);
    }

    private android.widget.PopupWindow selectionMore;
    private long selectionMoreDismissedAt;

    private void dismissSelectionMore() {
        if (selectionMore != null) {
            selectionMore.dismiss();
            selectionMore = null;
        }
    }

    /**
     * 「更多」：向上弹出 移动到 / 复制到 / 设为封面 / 夜间禁用 / 详细信息。
     * 设为封面与详细信息只在恰好勾一张时可点；封面、夜间两行的文案按勾选的现状取反。
     * 展开期间那颗「更多」变实心，收起复原。
     */
    private void showSelectionMore(View anchor) {
        if (selectionMore != null) {
            dismissSelectionMore();
            return;
        }
        // 菜单开着时点「更多」：触摸先在窗外把菜单收掉，紧接着这次点击又会把它打开 —— 刚收起就当是在收
        if (android.os.SystemClock.uptimeMillis() - selectionMoreDismissedAt < 300) {
            return;
        }
        final List<String> ids = wallpaperAdapter.selectedIdsInOrder();
        if (ids.isEmpty()) {
            return;
        }
        View content = LayoutInflater.from(this).inflate(R.layout.popup_selection_more, null, false);
        // inflate 时没有父容器，根上的 220dp 不会生效：宽度在这里给死
        final android.widget.PopupWindow popup = new android.widget.PopupWindow(content,
                dp(220), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        // 底放在窗口上（不是内容上），elevation 才有轮廓可投阴影
        popup.setBackgroundDrawable(getDrawable(R.drawable.popup_menu_background));
        popup.setElevation(dp(8));
        popup.setOutsideTouchable(true);
        boolean single = ids.size() == 1;
        boolean isCover = single && ids.get(0).equals(LibraryStore.coverId(this, currentLibId()));
        TextView cover = content.findViewById(R.id.more_cover);
        cover.setText(isCover ? R.string.sel_uncover : R.string.sel_cover);
        TextView night = content.findViewById(R.id.more_night);
        night.setText(selectionAllNightOff(ids) ? R.string.sel_night_off : R.string.sel_night);
        for (int id : new int[]{R.id.more_cover, R.id.more_info}) {
            View row = content.findViewById(id);
            row.setEnabled(single);
            row.setAlpha(single ? 1f : 0.38f);
        }
        content.findViewById(R.id.more_move).setOnClickListener(v -> {
            dismissSelectionMore();
            showOrganizeTargetDialog(true, wallpaperAdapter.selectedIdsInOrder());
        });
        content.findViewById(R.id.more_copy).setOnClickListener(v -> {
            dismissSelectionMore();
            showOrganizeTargetDialog(false, wallpaperAdapter.selectedIdsInOrder());
        });
        cover.setOnClickListener(v -> {
            dismissSelectionMore();
            toggleCoverForSelection();
        });
        night.setOnClickListener(v -> {
            dismissSelectionMore();
            toggleNightForSelection();
        });
        content.findViewById(R.id.more_info).setOnClickListener(v -> {
            dismissSelectionMore();
            List<String> now = wallpaperAdapter.selectedIdsInOrder();
            WallpaperStore.Item item = now.size() == 1 ? WallpaperStore.get(this, now.get(0)) : null;
            if (item != null) {
                WallpaperInfoSheet.show(this, item);
            }
        });
        final ImageButton more = (ImageButton) anchor;
        more.setBackgroundResource(R.drawable.sel_more_open_bg);
        more.setImageTintList(ColorStateList.valueOf(MaterialColors.getColor(more,
                com.google.android.material.R.attr.colorSurface)));
        popup.setOnDismissListener(() -> {
            if (selectionMore == popup) {
                selectionMore = null;
            }
            selectionMoreDismissedAt = android.os.SystemClock.uptimeMillis();
            android.util.TypedValue ripple = new android.util.TypedValue();
            getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true);
            more.setBackgroundResource(ripple.resourceId);
            more.setImageTintList(ColorStateList.valueOf(MaterialColors.getColor(more,
                    com.google.android.material.R.attr.colorOnSurface)));
        });
        // 贴着悬浮栏右上方弹出：右边与悬浮栏右缘对齐，底边比悬浮栏顶高 8dp
        content.measure(View.MeasureSpec.makeMeasureSpec(dp(220), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.UNSPECIFIED);
        View bar = findViewById(R.id.selection_bar);
        int[] barPos = new int[2];
        bar.getLocationInWindow(barPos);
        View root = findViewById(R.id.app_root);
        int x = barPos[0] + bar.getWidth() - content.getMeasuredWidth();
        int y = barPos[1] - content.getMeasuredHeight() - dp(8);
        selectionMore = popup;
        popup.showAtLocation(root, Gravity.TOP | Gravity.START, Math.max(0, x), Math.max(0, y));
    }

    private boolean selectionAllNightOff(List<String> ids) {
        if (ids.isEmpty()) {
            return false;
        }
        for (String id : ids) {
            WallpaperStore.Item item = WallpaperStore.get(this, id);
            if (item == null || !item.nightOff) {
                return false;
            }
        }
        return true;
    }

    /**
     * 批量打/摘「夜间禁用」标（v3.152）：勾选集合里还有没禁用的就一律禁用，全都禁用了就一律解除。
     * 只改 library.json 的 night_off 字段，不动文件、不动指针 —— 屏上那张被禁用了也不当场换，
     * 下一轮切换（或白天切回）自然修正。
     */
    private void toggleNightForSelection() {
        List<String> ids = wallpaperAdapter.selectedIdsInOrder();
        if (ids.isEmpty()) {
            return;
        }
        List<WallpaperStore.Item> items = WallpaperStore.load(this);
        boolean targetOff = false;
        for (WallpaperStore.Item item : items) {
            if (ids.contains(item.id) && !item.nightOff) {
                targetOff = true;
                break;
            }
        }
        for (WallpaperStore.Item item : items) {
            if (ids.contains(item.id)) {
                item.nightOff = targetOff;
            }
        }
        try {
            WallpaperStore.saveItems(this, items);
        } catch (Exception e) {
            Toast.makeText(this, R.string.organize_failed, Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, getString(targetOff ? R.string.night_mark_done
                : R.string.night_unmark_done, ids.size()), Toast.LENGTH_SHORT).show();
        exitOrganizeMode();
    }

    /**
     * 把勾的那一张设为当前库封面（已经是就取消，恢复自动挑选）。只改 libraries.json 的 cover 字段，
     * 图库卡片、库头部横幅与各个选库列表都是现读。做完退出多选，头部横幅当场换。
     */
    private void toggleCoverForSelection() {
        List<String> ids = wallpaperAdapter.selectedIdsInOrder();
        LibraryStore.Library lib = currentLib();
        if (ids.size() != 1 || lib == null) {
            return;
        }
        String id = ids.get(0);
        boolean unset = id.equals(LibraryStore.coverId(this, lib.id));
        LibraryStore.setCover(this, lib.id, unset ? null : id);
        Toast.makeText(this, unset ? getString(R.string.cover_unset_done)
                : getString(R.string.cover_set_done, lib.name), Toast.LENGTH_SHORT).show();
        exitOrganizeMode();
        wallpaperAdapter.refreshHeader();
    }

    /** 「添加」的来源面板：相册（图片与 MP4 混选）/ 图片或视频链接。 */
    private void showAddSourceSheet() {
        final BottomSheetDialog sheet = new BottomSheetDialog(this);
        View content = LayoutInflater.from(this).inflate(R.layout.sheet_add_source, null, false);
        sheet.setContentView(content);
        LibraryStore.Library target = currentLib();
        TextView hint = content.findViewById(R.id.tv_add_target);
        hint.setText(target == null ? getString(R.string.design_import_note)
                : getString(R.string.design_import_target, target.name) + "\n"
                        + getString(R.string.design_import_note));
        content.findViewById(R.id.row_add_album).setOnClickListener(v -> {
            sheet.dismiss();
            launchPicker();
        });
        content.findViewById(R.id.row_add_link).setOnClickListener(v -> {
            sheet.dismiss();
            showLinkInputDialog();
        });
        sheet.show();
    }

    /** 头部两颗键：这一面在用这个库 = 「使用中」（点开那一面的设置面板），否则 = 「设为…」（二次确认）。 */
    private void bindScopeButton(MaterialButton button, final boolean forHome, final String libId) {
        boolean using = LibraryStore.ownsScope(this, libId, forHome);
        button.setText(using ? (forHome ? R.string.lib_using_home : R.string.lib_using_lock)
                : (forHome ? R.string.lib_set_home : R.string.lib_set_lock));
        button.setIconResource(using ? R.drawable.ic_check : (forHome ? R.drawable.ic_home : R.drawable.ic_lock));
        int primary = MaterialColors.getColor(button, androidx.appcompat.R.attr.colorPrimary);
        if (using) {
            button.setBackgroundTintList(ColorStateList.valueOf(MaterialColors.getColor(button,
                    com.google.android.material.R.attr.colorSecondaryContainer)));
            int on = MaterialColors.getColor(button, com.google.android.material.R.attr.colorOnSecondaryContainer);
            button.setTextColor(on);
            button.setIconTint(ColorStateList.valueOf(on));
            button.setStrokeWidth(0);
        } else {
            button.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
            button.setTextColor(primary);
            button.setIconTint(ColorStateList.valueOf(primary));
            button.setStrokeWidth(dp(1));
            button.setStrokeColor(ColorStateList.valueOf(MaterialColors.getColor(button,
                    com.google.android.material.R.attr.colorOutline)));
        }
        button.setOnClickListener(v -> {
            if (LibraryStore.ownsScope(this, libId, forHome)) {
                showSlotSettingsSheet(forHome);
            } else {
                confirmSlotLib(forHome, libId, null);
            }
        });
    }

    /**
     * 库详情适配器：第 0 格是头部（占满三列），其余是三列正方形壁纸格。
     * 点格 = 单张详情（多选态 = 勾选）；长按 = 进多选并勾上这张。
     */
    class WallpaperAdapter extends ImageAdapter {

        WallpaperAdapter() { super(MainActivity.this); }

        private static final int TYPE_HEADER = 0;
        private static final int TYPE_CELL = 1;
        private List<WallpaperStore.Item> allItems = new ArrayList<>();
        private List<WallpaperStore.Item> items = new ArrayList<>();
        private List<DisplayRow> snapshots = Collections.singletonList(displayRow("library-header"));
        private int filterGeneration;
        private String entryLibraryId;
        private String entryCoverId;
        private boolean organizeMode;
        // 勾选按壁纸 id 记，不按下标：一次整理会把选中的那几张从这一页摘走，下标会串位
        private final Set<String> selectedIds = new HashSet<>();

        List<WallpaperStore.Item> items() {
            return items;
        }

        void setItems(List<WallpaperStore.Item> newItems) {
            allItems = new ArrayList<>(newItems);
            final int generation = ++filterGeneration;
            final int filter = wallpaperFilter;
            if (filter == 0) {
                publishItems(newItems);
                return;
            }
            imageLoader.execute(() -> {
                List<WallpaperStore.Item> filtered = new ArrayList<>();
                for (WallpaperStore.Item item : newItems) {
                    if (WallpaperStore.isLive(MainActivity.this, item.id) == (filter == 1)) {
                        filtered.add(item);
                    }
                }
                runOnUiThread(() -> {
                    if (isDestroyed() || generation != filterGeneration) return;
                    motion.whenIdle("wallpaper-filter", () -> {
                        if (!isDestroyed() && generation == filterGeneration) {
                            publishItems(filtered);
                            updateWallpaperOrganizeToolbar();
                            refreshDetail();
                        }
                    });
                });
            });
        }

        private void publishItems(List<WallpaperStore.Item> visible) {
            List<DisplayRow> next = new ArrayList<>();
            LibraryStore.Library lib = currentLib();
            String cover = lib == null ? null : libCoverId(lib);
            entryLibraryId = lib == null ? null : lib.id;
            entryCoverId = cover;
            next.add(displayRow("library-header", lib == null ? null : lib.id,
                    lib == null ? null : lib.name, cover, imageRevision(cover), imageClearEpoch, allItems.size(),
                    visible.isEmpty(), wallpaperFilter,
                    lib != null && LibraryStore.ownsScope(MainActivity.this, lib.id, true),
                    lib != null && LibraryStore.ownsScope(MainActivity.this, lib.id, false)));
            next.addAll(wallpaperRows(visible));
            List<DisplayRow> before = snapshots;
            snapshots = next;
            items = new ArrayList<>(visible);
            Set<String> alive = new HashSet<>();
            for (WallpaperStore.Item item : visible) {
                alive.add(item.id);
            }
            selectedIds.retainAll(alive);
            if (!before.equals(next)) diffRows(before, next).dispatchUpdatesTo(this);
        }

        void refreshHeader() {
            publishItems(items);
        }

        boolean isEntryReady() {
            if (!prepareVisible()) return false;
            RecyclerView.ViewHolder header = libraryRecycler.findViewHolderForAdapterPosition(0);
            return header != null && entryLibraryId != null
                    && entryLibraryId.equals(currentLibId())
                    && entryLibraryId.equals(header.itemView.getTag())
                    && images.isBound(header.itemView.findViewById(R.id.img_header_cover), entryCoverId);
        }

        boolean isOrganizeMode() {
            return organizeMode;
        }

        int selectedCount() {
            return selectedIds.size();
        }

        /** 勾中的那几张 id，按这一页的显示顺序给（弹窗与报数都用它）。 */
        List<String> selectedIdsInOrder() {
            List<String> ids = new ArrayList<>();
            for (WallpaperStore.Item item : items) {
                if (selectedIds.contains(item.id)) {
                    ids.add(item.id);
                }
            }
            return ids;
        }

        void setOrganizeMode(boolean on) {
            organizeMode = on;
            selectedIds.clear();
            notifyDataSetChanged();
        }

        void toggleSelectAll() {
            if (selectedIds.size() == items.size()) {
                selectedIds.clear();
            } else {
                for (WallpaperStore.Item item : items) {
                    selectedIds.add(item.id);
                }
            }
            notifyDataSetChanged();
            updateWallpaperOrganizeToolbar();
        }

        private void toggleSelect(WallpaperStore.Item item, int adapterPosition) {
            if (!selectedIds.remove(item.id)) {
                selectedIds.add(item.id);
            }
            notifyItemChanged(adapterPosition);
            updateWallpaperOrganizeToolbar();
        }

        @Override
        public int getItemViewType(int position) {
            return position == 0 ? TYPE_HEADER : TYPE_CELL;
        }

        @Override
        public int getItemCount() {
            return items.size() + 1;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            int layout = viewType == TYPE_HEADER ? R.layout.item_library_header : R.layout.item_wallpaper;
            View view = LayoutInflater.from(MainActivity.this).inflate(layout, parent, false);
            return new RecyclerView.ViewHolder(view) {
            };
        }

        @Override
        void bindRow(RecyclerView.ViewHolder holder, int position) {
            View v = holder.itemView;
            if (position == 0) {
                bindHeader(v);
                return;
            }
            final WallpaperStore.Item item = items.get(position - 1);
            ImageView img = v.findViewById(R.id.img_thumb);
            images.thumbnail(img, item.id);
            // 回填读取此刻的多选态，不捕获旧 itemView 或绑定时的 mode。
            bindCellInfo(v, item.id, organizeMode,
                    (owner, info) -> applyCellInfo(owner, info, organizeMode));
            TextView inUse = v.findViewById(R.id.tv_in_use);
            inUse.setVisibility(Switcher.inUseWhere(MainActivity.this, item.id) != null
                    ? View.VISIBLE : View.GONE);
            inUse.setText(R.string.in_use_badge);
            v.findViewById(R.id.iv_fav_badge).setVisibility(item.favorite ? View.VISIBLE : View.GONE);
            boolean selected = selectedIds.contains(item.id);
            // 与图库库卡片同一枚蓝勾：只在勾中时出现，没勾的格子不放空心框
            v.findViewById(R.id.iv_selected_check).setVisibility(
                    organizeMode && selected ? View.VISIBLE : View.GONE);
            v.setOnClickListener(x -> {
                int pos = holder.getBindingAdapterPosition();
                if (pos <= 0) {
                    return;
                }
                if (organizeMode) {
                    toggleSelect(item, pos);
                } else {
                    openDetail(pos - 1);
                }
            });
            v.setOnLongClickListener(x -> {
                int pos = holder.getBindingAdapterPosition();
                if (pos <= 0) {
                    return false;
                }
                if (!organizeMode) {
                    enterOrganizeMode();
                }
                toggleSelect(item, pos);
                return true;
            });
        }

        private void bindHeader(View v) {
            LibraryStore.Library lib = currentLib();
            v.setTag(lib == null ? null : lib.id);
            // 多选时头部照常显示：列表项设 GONE 仍会按原高度占位，以前那块就成了一大片空白；
            // 保留头部还能让格子不跳位，长按的那张始终在手指下面
            v.setVisibility(lib == null ? View.GONE : View.VISIBLE);
            if (lib == null) {
                unbindImageTree(v);
                return;
            }
            ImageView cover = v.findViewById(R.id.img_header_cover);
            String coverId = entryCoverId;
            if (coverId != null) {
                images.full(cover, coverId, "p:", getResources().getDisplayMetrics().widthPixels);
            } else {
                images.unbind(cover);
                cover.setTag(null);
                cover.setImageDrawable(null);
            }
            ((TextView) v.findViewById(R.id.tv_header_name)).setText(lib.name);
            ((TextView) v.findViewById(R.id.tv_header_count)).setText(getString(R.string.lib_count, allItems.size()));
            bindScopeButton(v.findViewById(R.id.btn_lib_home), true, lib.id);
            bindScopeButton(v.findViewById(R.id.btn_lib_lock), false, lib.id);
            // 多选期间这两颗不可点（一次只做一件事：要么整理选中的图，要么换这个库的去向）
            for (int id : new int[]{R.id.btn_lib_home, R.id.btn_lib_lock}) {
                View scope = v.findViewById(id);
                scope.setEnabled(!organizeMode);
                scope.setAlpha(organizeMode ? 0.38f : 1f);
            }
            MaterialButtonToggleGroup filters = v.findViewById(R.id.wallpaper_filters);
            filters.clearOnButtonCheckedListeners();
            filters.check(wallpaperFilter == 1 ? R.id.filter_live
                    : wallpaperFilter == 2 ? R.id.filter_static : R.id.filter_all);
            filters.addOnButtonCheckedListener((group, checkedId, checked) -> {
                if (!checked) {
                    return;
                }
                wallpaperFilter = checkedId == R.id.filter_live ? 1 : checkedId == R.id.filter_static ? 2 : 0;
                setItems(allItems);
            });
            MaterialButton columns = v.findViewById(R.id.btn_grid_columns);
            columns.setText(getString(R.string.design_grid_columns, wallpaperColumns));
            columns.setOnClickListener(view -> {
                wallpaperColumns = wallpaperColumns == 2 ? 3 : 2;
                prefs.edit().putInt("wallpaper_columns", wallpaperColumns).apply();
                wallpaperLayout.setSpanCount(wallpaperColumns);
                columns.setText(getString(R.string.design_grid_columns, wallpaperColumns));
                // 换成三列后整列变矮，可能一下就滚不动了，同样要把「添加」摊回去
                libraryRecycler.post(MainActivity.this::syncAddFab);
            });
            TextView empty = v.findViewById(R.id.tv_lib_empty);
            empty.setText(allItems.isEmpty() ? R.string.empty_wallpapers_full : R.string.design_filter_empty);
            empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    // ==================== 单张详情 ====================

    private void setupDetailPage() {
        detailToolbar = findViewById(R.id.toolbar_detail);
        detailPager = findViewById(R.id.pager_detail);
        detailAdapter = new DetailAdapter();
        detailPager.setAdapter(detailAdapter);
        detailPager.setOffscreenPageLimit(1);
        detailPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                // 翻走了就停：实况层是盖在「当前那页」上的，留着会盖到下一张上面
                stopLive();
                bindDetailChrome();
            }
        });
        setupLiveView();
        detailOverlay = findViewById(R.id.detail_launcher_overlay);
        detailToolbar.setNavigationOnClickListener(v -> closeDetail());
        detailToolbar.setOnMenuItemClickListener(item -> {
            WallpaperStore.Item current = detailItem();
            if (current == null) {
                return true;
            }
            List<String> one = Collections.singletonList(current.id);
            int id = item.getItemId();
            if (id == R.id.action_detail_live) {
                if (live.isPlaying()) {
                    stopLive();
                } else {
                    startLive(current, false);
                }
            } else if (id == R.id.action_detail_move) {
                showOrganizeTargetDialog(true, one);
            } else if (id == R.id.action_detail_copy) {
                showOrganizeTargetDialog(false, one);
            } else if (id == R.id.action_detail_unlink) {
                confirmRemoveSelected(one);
            } else if (id == R.id.action_detail_preview) {
                toggleDetailPreview();
            } else if (id == R.id.action_detail_delete) {
                handleTrashClick(current);
            }
            return true;
        });
        findViewById(R.id.btn_detail_crop).setOnClickListener(v -> {
            WallpaperStore.Item item = detailItem();
            if (item != null) {
                openEditItem(item);
            }
        });
        findViewById(R.id.btn_detail_home).setOnClickListener(v -> {
            WallpaperStore.Item item = detailItem();
            if (item != null && !settingWallpaper) {
                showSetWallpaperDialog(item);
            }
        });
        findViewById(R.id.tv_detail_credit).setOnClickListener(v -> {
            WallpaperStore.Item item = detailItem();
            if (item == null || !WallpaperDownload.isHttps(item.link)) {
                return;
            }
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(item.link)));
            } catch (Exception e) {
                Toast.makeText(this, R.string.source_link_failed, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void openScopeDetail(boolean forHome) {
        LibraryStore.Library lib = LibraryStore.slotLib(this, forHome);
        if (lib == null) {
            showSlotSettingsSheet(forHome);
            return;
        }
        String id = slotThumbId(lib.id, forHome);
        List<WallpaperStore.Item> items = WallpaperStore.loadByLib(this, lib.id);
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id.equals(id)) {
                openDetail(items, lib.id, i, false);
                return;
            }
        }
        Toast.makeText(this, R.string.design_no_preview, Toast.LENGTH_SHORT).show();
    }

    String itemActionLibId() {
        return pageDetail.getVisibility() == View.VISIBLE ? detailLibId : currentLibId();
    }

    private void openDetail(int index) {
        openDetail(new ArrayList<>(wallpaperAdapter.items()), currentLibId(), index, true);
    }

    private void openDetail(List<WallpaperStore.Item> items, String libId, int index, boolean filtered) {
        if (items.isEmpty() || index < 0 || index >= items.size()) {
            return;
        }
        detailOpen = true;
        detailLibId = libId;
        detailUsesFilter = filtered;
        detailAdapter.setItems(items);
        detailPager.setCurrentItem(index, false);
        motion.show(pageDetail, detailAdapter::isEntryReady);
        // 黑底上状态栏图标得是浅色
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                .setAppearanceLightStatusBars(false);
        bindDetailChrome();
    }

    private void closeDetail() {
        if (!detailOpen) {
            return;
        }
        detailOpen = false;
        stopLive();
        unbindImageTree(pageDetail);
        if (detailPreview) {
            exitDetailPreview(false);
        }
        motion.hide(pageDetail);
        boolean night = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                .setAppearanceLightStatusBars(!night);
    }

    WallpaperStore.Item detailItem() {
        int pos = detailPager.getCurrentItem();
        List<WallpaperStore.Item> items = detailAdapter.items;
        return pos >= 0 && pos < items.size() ? items.get(pos) : null;
    }

    /** 筛选在后台尚未完成时也先摘走已删除/移出的详情，不能等动画或筛选回调。 */
    private void pruneDetailItems() {
        if (!detailOpen) return;
        Map<String, WallpaperStore.Item> alive = new HashMap<>();
        for (WallpaperStore.Item item : WallpaperStore.loadByLib(this, detailLibId)) {
            alive.put(item.id, item);
        }
        List<WallpaperStore.Item> remaining = new ArrayList<>();
        for (WallpaperStore.Item item : detailAdapter.items) {
            if (alive.containsKey(item.id)) remaining.add(alive.get(item.id));
        }
        if (remaining.size() != detailAdapter.items.size()) updateDetailItems(remaining);
    }

    /** 列表变了（改标题、删除、移走）：停在原来那张上，它没了就停在同一位置的下一张，一张不剩就关掉。 */
    void refreshDetail() {
        if (!detailOpen) return;
        updateDetailItems(detailUsesFilter ? new ArrayList<>(wallpaperAdapter.items())
                : WallpaperStore.loadByLib(this, detailLibId));
    }

    private void updateDetailItems(List<WallpaperStore.Item> items) {
        WallpaperStore.Item before = detailItem();
        int oldPos = detailPager.getCurrentItem();
        if (items.isEmpty()) {
            closeDetail();
            return;
        }
        int pos = Math.min(oldPos, items.size() - 1);
        if (before != null) {
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).id.equals(before.id)) {
                    pos = i;
                    break;
                }
            }
        }
        detailAdapter.setItems(items);
        detailPager.setCurrentItem(pos, false);
        WallpaperStore.Item now = detailItem();
        if (live.isPlaying() && (now == null || !live.playingId().equals(now.id))) {
            stopLive();
        }
        bindDetailChrome();
    }

    /** 顶栏页码 + 当值那张的编辑权限。 */
    private void bindDetailChrome() {
        WallpaperStore.Item item = detailItem();
        if (item == null) {
            return;
        }
        detailToolbar.setTitle(getString(R.string.detail_position,
                detailPager.getCurrentItem() + 1, detailAdapter.items.size()));
        // 当值那张改不动也删不掉（闸门在 blockWhileInUse）：直接把两个入口置灰，别让人点了才知道
        boolean editable = Switcher.inUseWhere(this, item.id) == null;
        findViewById(R.id.btn_detail_crop).setEnabled(editable);
        MenuItem delete = detailToolbar.getMenu().findItem(R.id.action_detail_delete);
        if (delete != null) {
            delete.setEnabled(editable);
        }
        // 从「全部壁纸」打开时没有「本库」：移动 / 从本库移出无从谈起，收起；复制到某个库照常可用
        boolean fromAll = LibraryStore.isAll(detailLibId);
        MenuItem move = detailToolbar.getMenu().findItem(R.id.action_detail_move);
        if (move != null) {
            move.setVisible(!fromAll);
        }
        MenuItem unlink = detailToolbar.getMenu().findItem(R.id.action_detail_unlink);
        if (unlink != null) {
            unlink.setVisible(!fromAll);
        }
        TextView credit = findViewById(R.id.tv_detail_credit);
        if (item.credit == null) {
            credit.setVisibility(View.GONE);
        } else {
            credit.setText(!WallpaperDownload.isHttps(item.link) ? item.credit
                    : getString(R.string.source_credit_with_link, item.credit));
            credit.setVisibility(View.VISIBLE);
        }
        refreshLiveMenu();
    }

    /** 详情页翻页：每页一张成品图按屏幕长边降采样，先放缩略图占位。 */
    private class DetailAdapter extends ImageAdapter {

        DetailAdapter() { super(MainActivity.this); }

        List<WallpaperStore.Item> items = new ArrayList<>();
        private List<DisplayRow> snapshots = new ArrayList<>();

        boolean isEntryReady() {
            if (!prepareVisible() || detailPager.isLayoutRequested()) return false;
            WallpaperStore.Item item = detailItem();
            RecyclerView recycler = (RecyclerView) detailPager.getChildAt(0);
            RecyclerView.ViewHolder holder = recycler.findViewHolderForAdapterPosition(detailPager.getCurrentItem());
            return item != null && holder != null && images.isBound((ImageView) holder.itemView, item.id);
        }

        void setItems(List<WallpaperStore.Item> newItems) {
            List<DisplayRow> next = wallpaperRows(newItems);
            List<DisplayRow> before = snapshots;
            snapshots = next;
            items = new ArrayList<>(newItems);
            if (!before.equals(next)) diffRows(before, next).dispatchUpdatesTo(this);
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        @Override
        public void onViewAttachedToWindow(@NonNull RecyclerView.ViewHolder holder) {
            super.onViewAttachedToWindow(holder);
            // 从缓存里直接拿回来的页重新订阅图片，并恢复预览铺法。
            ((ImageView) holder.itemView).setScaleType(detailPreview
                    ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(MainActivity.this).inflate(R.layout.item_detail_page, parent, false);
            return new RecyclerView.ViewHolder(view) {
            };
        }

        @Override
        void bindRow(RecyclerView.ViewHolder holder, int position) {
            String id = items.get(position).id;
            int maxSide = Math.max(getResources().getDisplayMetrics().widthPixels,
                    getResources().getDisplayMetrics().heightPixels);
            images.full((ImageView) holder.itemView, id, "d:", maxSide);
            // 桌面预览时按桌面的方式铺（centerCrop，和引擎上屏一样），平时整张看全（fitCenter）
            ((ImageView) holder.itemView).setScaleType(detailPreview
                    ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
            // 单击画面：开关桌面图标预览（不用进裁剪页）
            holder.itemView.setOnClickListener(v -> toggleDetailPreview());
            // 长按画面播实况：按住期间循环，松手即停。不是实况的那张不吃长按（返回 false，什么都不发生）
            holder.itemView.setOnLongClickListener(v -> {
                WallpaperStore.Item current = detailItem();
                if (!detailOpen || current == null || !current.id.equals(v.getTag())
                        || Boolean.FALSE.equals(images.cachedLive(current.id))) {
                    return false;
                }
                // 按住看实况时不让 ViewPager2 把这次手势抢去翻页
                if (v.getParent() != null) {
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                }
                startLive(current, true);
                return true;
            });
            holder.itemView.setOnTouchListener((v, event) -> {
                int action = event.getActionMasked();
                if (live.isHeld() && (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)) {
                    stopLive();
                }
                // 不吃事件：长按判定仍交给 View 自己
                return false;
            });
        }
    }

    // ==================== 单张详情：桌面图标预览 ====================

    /**
     * 单击画面：开关桌面图标预览。与裁剪页那个单击是同一个功能、同一张全局底图，
     * 区别只是这里不用进裁剪页就能看「这张上桌面后会被图标挡住哪」。
     * 还没设过底图时先去相册选一张首页截图（选完存成全局底图，回来直接打开预览）。
     */
    private void toggleDetailPreview() {
        if (detailPreview) {
            exitDetailPreview();
            return;
        }
        if (!LauncherPreviewOverlay.overlayFile(this).exists()) {
            overlayPickForDetail = true;
            pickLauncherOverlay();
            return;
        }
        if (!detailOverlay.hasScreenshot()) {
            final Context app = getApplicationContext();
            imageLoader.execute(() -> {
                // loadSaved 给的是共享缓存实例，不许回收
                final Bitmap saved = LauncherPreviewOverlay.loadSaved(app);
                runOnUiThread(() -> {
                    if (isDestroyed() || pageDetail.getVisibility() != View.VISIBLE) {
                        return;
                    }
                    if (saved == null) {
                        Toast.makeText(this, R.string.launcher_overlay_load_failed, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    detailOverlay.setScreenshot(saved);
                    enterDetailPreview();
                });
            });
            return;
        }
        enterDetailPreview();
    }

    /** 进预览：收起顶栏、底栏与系统栏（截图里自带状态栏图标，真状态栏再叠一层就重了），画面改按桌面那样铺满。 */
    void enterDetailPreview() {
        if (detailPreview) {
            return;
        }
        // 实况层是按「整张看全」的位置摆的，铺法一换就对不上，先停掉；预览里长按再播会按新铺法摆
        stopLive();
        UiMotion.preview(this, detailPager.getChildAt(0), detailOverlay,
                findViewById(R.id.detail_top), findViewById(R.id.detail_bottom));
        detailPreview = true;
        detailOverlay.setVisibility(View.VISIBLE);
        findViewById(R.id.detail_top).setVisibility(View.GONE);
        findViewById(R.id.detail_bottom).setVisibility(View.GONE);
        // 系统栏藏起来后，默认的刘海模式会把窗口从挖孔那一条往下挪，画面和底图就错位了；
        // 预览期间改成贴着短边铺满（和状态栏露着时的位置一致）
        setCutoutShortEdges(true);
        androidx.core.view.WindowInsetsControllerCompat bars =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        bars.setSystemBarsBehavior(
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        bars.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars());
        applyDetailScaleType();
    }

    private void exitDetailPreview() {
        exitDetailPreview(true);
    }

    private void exitDetailPreview(boolean animate) {
        if (!detailPreview) {
            return;
        }
        stopLive();
        if (animate) {
            UiMotion.preview(this, detailPager.getChildAt(0), detailOverlay,
                    findViewById(R.id.detail_top), findViewById(R.id.detail_bottom));
        } else {
            UiMotion.finishPreview(this);
        }
        detailPreview = false;
        detailOverlay.setVisibility(View.GONE);
        findViewById(R.id.detail_top).setVisibility(View.VISIBLE);
        findViewById(R.id.detail_bottom).setVisibility(View.VISIBLE);
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                .show(androidx.core.view.WindowInsetsCompat.Type.systemBars());
        setCutoutShortEdges(false);
        applyDetailScaleType();
        if (detailOpen) {
            refreshLiveMenu();
            detailAdapter.rebindVisible();
        }
    }

    private void setCutoutShortEdges(boolean on) {
        if (Build.VERSION.SDK_INT < 28) {
            return;
        }
        android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.layoutInDisplayCutoutMode = on
                ? android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                : android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
        getWindow().setAttributes(lp);
    }

    /** 已经摆在屏上的那几页（当前页 + 左右预载的）就地换铺法，不重新绑定、不重解图。 */
    private void applyDetailScaleType() {
        ImageView.ScaleType type = detailPreview
                ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER;
        View inner = detailPager.getChildAt(0);
        if (inner instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) inner;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child instanceof ImageView) {
                    ((ImageView) child).setScaleType(type);
                }
            }
        }
    }

    // ==================== 单张详情：实况播放 ====================

    void setupLiveView() {
        live.setupLiveView();
    }

    void refreshLiveMenu() {
        live.refreshLiveMenu();
    }

    void startLive(WallpaperStore.Item item, boolean held) {
        live.startLive(item, held);
    }

    /** 停播并收起播放层（任何时候都可以调，没在播就只是把状态归零）。 */
    void stopLive() {
        live.stopLive();
    }

    // ==================== 设置页的入口（SettingsFragment 调这些） ====================

    /** 设置页只在露着时现算；切过去时 showTab 会刷一遍，不在后台白读日志文件。 */
    void refreshSettings() {
        if (currentTab != R.id.nav_settings) {
            return;
        }
        int container = settingsSecondaryKey != null
                ? R.id.settings_secondary_container : R.id.settings_container;
        androidx.fragment.app.Fragment f = getSupportFragmentManager().findFragmentById(container);
        if (f instanceof SettingsFragment) {
            ((SettingsFragment) f).refreshAll();
        }
    }

    boolean isActiveSettings(SettingsFragment fragment) {
        if (currentTab != R.id.nav_settings) {
            return false;
        }
        int container = settingsSecondaryKey == null
                ? R.id.settings_container : R.id.settings_secondary_container;
        return fragment == getSupportFragmentManager().findFragmentById(container);
    }

    void publishSettingsRefresh(Object key, Runnable action) {
        if (motion != null && motion.isAnimating()) {
            motion.whenIdle(key, action);
        } else {
            action.run();
        }
    }

    /** 接管那一行：已接管 = 说明怎么关；未接管 = 去系统选择器启用。 */
    void onTakeoverRowTapped() {
        if (TakeoverManager.isHomeTakenOver(this)) {
            showTakeoverInfoDialog();
        } else {
            enableTakeover();
        }
    }

    void pickExportDir() {
        backup.pickExportDir();
    }

    void launchRestorePicker() {
        backup.launchRestorePicker();
    }

    String updateStatus() {
        return update.status();
    }

    void onUpdateRowTapped() {
        update.onRowTapped();
    }

    /** 电池优化白名单行：设置页那一行自己读，这里只是给老调用点留个名字。 */
    private void refreshBatteryRow() {
        refreshSettings();
    }

    /** 新建库弹窗：建好直接进它的库详情（接着就是「添加」）。 */
    private void showNewLibDialog() {
        gallery.showNewLibDialog();
    }

    /** 滚轮选择器的一列：上下滚轮 + 底部「时/分/秒」标签（设置页的循环间隔也用它）。 */
    LinearLayout wheelColumn(NumberPicker picker, String label) {
        InsetsHelper.hideScrollbars(picker);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER);
        column.addView(picker);
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(getColor(R.color.text_secondary));
        tv.setTextSize(12);
        tv.setGravity(Gravity.CENTER);
        column.addView(tv);
        return column;
    }

    // ==================== 以下沿用 v3.133 的实现（逻辑不变） ====================

    /** 占位图标用默认次级灰的简写。 */
    void bindThumb(ImageView view, String wallpaperId, int placeholderRes, int padDp) {
        bindThumb(view, wallpaperId, placeholderRes, padDp, getColor(R.color.text_secondary));
    }

    /**
     * 缩略图位统一绑定：有图清掉占位用的内边距与着色（不清会把真图按 SRC_IN 染成剪影），
     * 没图退回指定线性图标（按 padDp 居中、用 placeholderTint 着色）。
     * 回收复用时两条路径都显式设置，状态才不串。
     */
    void bindThumb(ImageView view, String wallpaperId, int placeholderRes, int padDp,
                           int placeholderTint) {
        if (view == null) {
            return;
        }
        if (wallpaperId != null) {
            view.setPadding(0, 0, 0, 0);
            images.thumbnail(view, wallpaperId);
        } else {
            images.unbind(view);
            view.setTag(null);
            int pad = (int) (padDp * getResources().getDisplayMetrics().density);
            view.setPadding(pad, pad, pad, pad);
            view.setImageTintList(ColorStateList.valueOf(placeholderTint));
            view.setImageResource(placeholderRes);
        }
    }

    /** 某库某范围的代表图：该范围在屏的那张优先，其次另一范围，再次库内第一张；空库 null。 */
    /**
     * 库的「代表图」：用户在单张详情里指定过封面、且那张还在库里就用它，否则退回 {@link #slotThumbId}。
     * 只用在「把库当一个整体展示」的地方（图库卡片、库详情头部、各个选库列表）；
     * 此刻页两张卡、切换设置那一行画的是「这一面现在在屏的那张」，仍走 slotThumbId，不受封面影响。
     */
    String libCoverId(LibraryStore.Library lib) {
        if (lib.cover != null) {
            WallpaperStore.Item item = WallpaperStore.get(this, lib.cover);
            if (item != null && item.inLib(lib.id)) {
                return lib.cover;
            }
        }
        return slotThumbId(lib.id, true);
    }

    String slotThumbId(String libId, boolean forHome) {
        String id = Switcher.getCurrent(this, libId, forHome);
        if (id == null) {
            id = Switcher.getCurrent(this, libId, !forHome);
        }
        if (id == null) {
            List<WallpaperStore.Item> items = WallpaperStore.loadByLib(this, libId);
            if (!items.isEmpty()) {
                id = items.get(0).id;
            }
        }
        return id;
    }

    /**
     * 元数据文件出过事时如实告诉用户（MetaFiles 在读 library.json / libraries.json 时登记）。
     * 救回来的一律自动写回；整份读不动的原样留在原地并另存留档，
     * 让用户把留档拷走手工捡 id/标题，而不是静默显示「库是空的」。
     */
    void maybeShowMetaNotice() {
        backup.maybeShowMetaNotice();
    }

    void maybePromptRestore() {
        backup.maybePromptRestore();
    }

    void launchRestoreTreePicker() {
        backup.launchRestoreTreePicker();
    }

    void onRestoreTreePicked(Uri treeUri) {
        backup.onRestoreTreePicked(treeUri);
    }

    void ensureNotificationsEnabled() {
        perm.ensureNotificationsEnabled();
    }

    void onNotifyPermissionResult(boolean granted) {
        perm.onNotifyPermissionResult(granted);
    }

    void onSavePermissionResult(boolean granted) {
        perm.onSavePermissionResult(granted);
    }

    /**
     * 整理态点「移动到其它库…／复制到其它库…」：先问去哪个库。
     * 两个动作都不二次确认 —— 多归属下它们只是给这张多挂/少挂一个归属，零磁盘、随时能搬回来；
     * 会真删东西的只有「从本库移除」，那条走 {@link #confirmRemoveSelected}。
     */
    void showOrganizeTargetDialog(final boolean move, final List<String> ids) {
        if (ids.isEmpty()) {
            Toast.makeText(this, R.string.organize_none, Toast.LENGTH_SHORT).show();
            return;
        }
        final String src = itemActionLibId();
        if (src == null || LibraryStore.get(this, src) == null) {
            return;
        }
        final List<LibraryStore.Library> libs = LibraryStore.load(this);
        boolean hasOther = false;
        for (LibraryStore.Library lib : libs) {
            if (!lib.id.equals(src)) {
                hasOther = true;
                break;
            }
        }
        if (!hasOther) {
            Toast.makeText(this, R.string.organize_no_other_lib, Toast.LENGTH_LONG).show();
            return;
        }
        new AppAlertDialogBuilder(this)
                .setTitle(move ? R.string.organize_move_title : R.string.organize_copy_title)
                .setAdapter(new OrganizeLibAdapter(libs, src, ids), (dialog, which) -> {
                    LibraryStore.Library target = libs.get(which);
                    if (target.id.equals(src)) {
                        // 这一行是压暗的当前库，理论上点不到；真点到了就如实说一句，弹窗不收
                        Toast.makeText(this, R.string.organize_same_lib, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    applyOrganizeChange(move ? ORGANIZE_MOVE : ORGANIZE_COPY,
                            ids, src, target.id, target.name);
                })
                .setNegativeButton(R.string.close, null)
                .show();
    }

    /**
     * 整理态点「移出本库」：一律二次确认（v3.189 用户要求，删不删文件都问），并把账算明：
     * 几张只挂在这个库里（移出 = 成品图、缩略图、原图一起没），几张还挂在别的库里（移出 = 这一页不再显示）。
     */
    void confirmRemoveSelected(final List<String> ids) {
        if (ids.isEmpty()) {
            Toast.makeText(this, R.string.organize_none, Toast.LENGTH_SHORT).show();
            return;
        }
        final String src = itemActionLibId();
        LibraryStore.Library lib = src == null ? null : LibraryStore.get(this, src);
        if (lib == null) {
            return;
        }
        int[] preview = WallpaperStore.previewRemovalFromLib(this, ids, src);
        // v3.189 起不管删不删文件都要确认：以前「一张都不会删」时直接办，用户要求移出也先问一句
        String msg = preview[0] == 0
                ? getString(R.string.organize_remove_msg_shared, ids.size(), lib.name)
                : preview[1] == 0
                ? getString(R.string.organize_remove_msg_all, preview[0])
                : getString(R.string.organize_remove_msg_mixed, preview[0], preview[1]);
        AlertDialog dialog = new AppAlertDialogBuilder(this)
                .setTitle(getString(R.string.organize_remove_title, ids.size(), lib.name))
                .setMessage(msg)
                .setPositiveButton(R.string.organize_remove_confirm, (d, which) ->
                        applyOrganizeChange(ORGANIZE_REMOVE, ids, src, null, null))
                .setNegativeButton(R.string.cancel, null)
                .show();
        // 会删文件的那种用红字，提醒这一下找不回来
        if (preview[0] > 0) {
            Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            positive.setTextColor(getColor(R.color.danger));
        }
    }

    /**
     * 三条整理动作的共同落点：一次元数据改写（{@code library.json} 整表原子重写，图片文件不动，
     * 只有摘到「不再挂任何库」才删那三份），然后收整理态、刷网格，并把「这张刚从哪个库被摘走」
     * 的当前指针各自核对一遍。
     *
     * <p>收尾与长按垃圾桶同一条链路：摘走的若是那个库屏上那一张，清指针并推进下一张上屏，
     * 真推进了就连带重排那一轮的定时（都在 {@link Switcher#reapplyIfCurrent} 内部）。
     * 锁屏那一路是阻塞的系统调用，所以照旧放后台线程、用 Application 上下文。
     */
    void applyOrganizeChange(int kind, List<String> ids, String fromLibId,
                                     String toLibId, String toName) {
        int destroyed = 0;
        int affected = ids.size();
        try {
            if (kind == ORGANIZE_COPY) {
                // 已经在目标库里的那些不该被计入「复制了几张」，不然报数比实际动的大
                affected = WallpaperStore.addToLib(this, ids, toLibId);
            } else if (kind == ORGANIZE_MOVE) {
                destroyed = WallpaperStore.moveToLib(this, ids, fromLibId, toLibId);
            } else {
                destroyed = WallpaperStore.removeFromLib(this, ids, fromLibId);
            }
        } catch (Exception | OutOfMemoryError e) {
            Toast.makeText(this, R.string.organize_failed, Toast.LENGTH_LONG).show();
            return;
        }
        if (kind != ORGANIZE_COPY) {
            repointPinnedAwayFrom(fromLibId, ids);
        }
        if (destroyed > 0) {
            for (String id : ids) {
                if (WallpaperStore.get(this, id) == null) {
                    evictImages(id);
                }
            }
        }
        exitOrganizeMode();
        refreshList();
        final List<String> touched = new ArrayList<>(ids);
        final String src = fromLibId;
        new Thread(() -> {
            for (String id : touched) {
                Switcher.reapplyIfCurrent(getApplicationContext(), src, id);
            }
        }, "reapply-organize").start();
        if (kind == ORGANIZE_COPY) {
            Toast.makeText(this, affected == 0
                    ? getString(R.string.organize_copied_all_already, ids.size(), toName)
                    : (affected == ids.size()
                            ? getString(R.string.organize_copied, affected, toName)
                            : getString(R.string.organize_copied_skipped, affected, toName,
                                    ids.size() - affected)), Toast.LENGTH_SHORT).show();
        } else if (kind == ORGANIZE_MOVE) {
            Toast.makeText(this, getString(R.string.organize_moved, ids.size(), toName),
                    Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, destroyed == 0
                    ? getString(R.string.organize_removed_shared, ids.size())
                    : getString(R.string.organize_removed, ids.size(), destroyed),
                    Toast.LENGTH_SHORT).show();
        }
        // 收尾就这两下，与长按删一张同口径：这一页的网格跟上，库行的张数与槽位卡片的代表图
        // 等回库列表页时 applyLibPage 各自重算（壁纸页上那排卡片本来就是收起的，不在这里白刷）
    }

    /**
     * 图钉跟着走：一键设置钉住的那张刚从某个库被摘走、但还挂在别的库里，就把 {@code pin_lib}
     * 改成它剩下的第一个归属。不改的话下次按通知上那颗开关会走 {@code inLibrary} 判定失败，
     * 报「那张已不在库里」，而图明明还在手机上、那个库也还看得到它。
     *
     * <p>整张彻底没了（归属摘空）就照旧不管：与现在删一张的口径一致，那次是真没图可钉了。
     */
    void repointPinnedAwayFrom(String fromLibId, List<String> ids) {
        String pinId = PinnedWallpaper.wallpaperId(this);
        if (pinId == null || !ids.contains(pinId)
                || !fromLibId.equals(PinnedWallpaper.libId(this))) {
            return;
        }
        WallpaperStore.Item after = WallpaperStore.get(this, pinId);
        if (after == null || after.libIds.isEmpty()) {
            return;
        }
        PinnedWallpaper.set(this, after.libId, pinId);
        refreshSettings();
        StatusNotifier.update(this);
    }

    /**
     * 整理动作的选库列表：行样式沿用一键设置第一级那套（代表图 + 库名 + 张数）。
     * 正在看的这个库压暗、不可选（移动和复制都是往别的库挂/摘归属，挂到自己头上没有意义）；
     * 副标题顺带说清「这次选里的有几张已经在这个库里」，免得点了复制才发现是白点。
     */
    private class OrganizeLibAdapter extends BaseAdapter {

        private final List<LibraryStore.Library> libs;
        private final String srcLibId;
        private final Map<String, Integer> counts = new HashMap<>();
        private final Map<String, Integer> already = new HashMap<>();

        OrganizeLibAdapter(List<LibraryStore.Library> libs, String srcLibId, List<String> ids) {
            this.libs = libs;
            this.srcLibId = srcLibId;
            counts.putAll(WallpaperStore.libMemberCounts(MainActivity.this));
            for (WallpaperStore.Item item : WallpaperStore.load(MainActivity.this)) {
                if (!ids.contains(item.id)) {
                    continue;
                }
                // 共享的一张在它挂着的每个库里都算「已经在」，逐归属数
                for (String owner : item.libIds) {
                    Integer seen = already.get(owner);
                    already.put(owner, seen == null ? 1 : seen + 1);
                }
            }
        }

        @Override
        public int getCount() {
            return libs.size();
        }

        @Override
        public Object getItem(int position) {
            return libs.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @NonNull
        @Override
        public View getView(int position, View convertView, @NonNull ViewGroup parent) {
            View row = newPinRow(convertView, parent);
            LibraryStore.Library lib = libs.get(position);
            int count = counts.get(lib.id) == null ? 0 : counts.get(lib.id);
            int had = already.get(lib.id) == null ? 0 : already.get(lib.id);
            ((TextView) row.findViewById(R.id.tv_pick_name)).setText(lib.name);
            ((TextView) row.findViewById(R.id.tv_pick_sub)).setText(
                    lib.id.equals(srcLibId)
                            ? getString(R.string.organize_lib_sub_current, count)
                            : (had > 0
                                    ? getString(R.string.organize_lib_sub_already, count, had)
                                    : getString(R.string.lib_count, count)));
            bindThumb((ImageView) row.findViewById(R.id.img_pick_thumb),
                    libCoverId(lib), R.drawable.ic_tab_wallpaper, 10);
            // 这一列是 ListView 复用的视图，两档都得显式赋值，不然滚回来还带着上一行的状态
            row.findViewById(R.id.iv_pick_check).setVisibility(View.INVISIBLE);
            row.setAlpha(lib.id.equals(srcLibId) ? 0.4f : 1f);
            return row;
        }
    }

    /** 弹窗里「当前库」那一行的回填（选中库后要重新绑定，缩略图跟着换）。 */
    void bindSlotLibRow(View content, boolean forHome) {
        TextView row = content.findViewById(R.id.tv_slot_lib);
        ImageView thumb = content.findViewById(R.id.img_slot_lib_thumb);
        LibraryStore.Library lib = LibraryStore.slotLib(this, forHome);
        if (lib == null) {
            row.setText(R.string.slot_lib_none);
            bindThumb(thumb, null, R.drawable.ic_tab_wallpaper, 10);
            return;
        }
        row.setText(lib.name);
        bindThumb(thumb, slotThumbId(lib.id, forHome), R.drawable.ic_tab_wallpaper, 10);
    }

    /** 库选择列表：第 0 项「不切换」（清空本范围），第 1 项「全部壁纸」（全局播放），其余带各自缩略图；当前占位库打勾。 */
    void showLibPickerDialog(final boolean forHome, final View settingsContent) {
        final List<LibraryStore.Library> libs = LibraryStore.loadWithAll(this);
        new AppAlertDialogBuilder(this)
                .setTitle(R.string.slot_pick_lib_title)
                .setAdapter(new SlotLibPickerAdapter(libs, forHome), (dialog, which) -> {
                    String newId = which == 0 ? null : libs.get(which - 1).id;
                    String liveId = LibraryStore.slotLibId(this, forHome);
                    if (newId == null ? liveId == null : newId.equals(liveId)) {
                        return;
                    }
                    // 列表点选只是「选中」，落库前再确认一次（同一范围只认一个库）
                    confirmSlotLib(forHome, newId, settingsContent);
                })
                .setNegativeButton(R.string.close, null)
                .show();
    }

    /** 范围内库变更：槽位写入 + 受影响的两处界面刷新（角标与卡片），接管状态同步一次。 */
    void applySlotLib(boolean forHome, String libId) {
        LibraryStore.setSlotLib(this, forHome, libId);
        // 桌面这一面由引擎在画：换了占位库要主动标脏，否则得等下次亮屏（onVisibilityChanged 重放）
        // 才换成新库那张 —— 刚选完看不出动，像没生效。锁屏那面走静态存档，由下面的 syncTakeover 负责。
        // 引擎没被系统选中时 ENGINES 是空的，这一声就是空响，不必额外判断。
        if (forHome) {
            WallSwitchService.notifyWallpaperChanged();
        }
        gallery.refreshBadges();
        refreshTimerStatus();
        refreshSlotCards();
        syncTakeoverAsync();
    }

    /** 选库的二次确认：中性文案说明要改成哪个库（清空那档另说一句会停止切换）。 */
    void confirmSlotLib(final boolean forHome, final String newLibId,
                                final View settingsContent) {
        String slotName = getString(forHome ? R.string.slot_home_title : R.string.slot_lock_title);
        String message;
        if (newLibId == null) {
            message = getString(R.string.slot_confirm_clear, slotName);
        } else {
            LibraryStore.Library target = LibraryStore.get(this, newLibId);
            message = getString(R.string.slot_confirm_msg, slotName,
                    target == null ? "" : target.name);
        }
        new AppAlertDialogBuilder(this)
                .setTitle(R.string.slot_confirm_title)
                .setMessage(message)
                .setPositiveButton(R.string.confirm, (dialog, which) -> {
                    applySlotLib(forHome, newLibId);
                    // 拖拽落位那条路径没有开着的弹窗要重绑，只有设置弹窗里选库时才回填那一行
                    if (settingsContent != null) {
                        bindSlotLibRow(settingsContent, forHome);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** 该范围的设置摘要行：模式 · 每 X（卡片用）；事件档（回桌面 / 灭屏）显示触发方式而非间隔。 */
    String slotIntervalDisplay(boolean forHome) {
        boolean random = LibraryStore.MODE_RANDOM.equals(LibraryStore.scopeMode(this, forHome));
        String modeText = getString(random ? R.string.mode_random : R.string.mode_order);
        if (LibraryStore.isEventTrigger(this, forHome)) {
            return modeText + " · " + getString(
                    LibraryStore.triggerLabelRes(LibraryStore.scopeTriggerMode(this, forHome)));
        }
        return modeText + " · " + getString(R.string.slot_every_prefix)
                + formatInterval(LibraryStore.scopeIntervalSeconds(this, forHome));
    }

    /** 面板里「切换间隔 / 最小间隔」那一行右侧的值。 */
    String slotRowValue(boolean forHome) {
        if (LibraryStore.isEventTrigger(this, forHome)) {
            int gap = Switcher.eventMinGapMinutes(this, forHome);
            return gap <= 0 ? getString(R.string.min_gap_every) : getString(R.string.min_gap_value, gap);
        }
        return slotIntervalDisplay(forHome);
    }

    /** 按当前触发方式重填面板里那一行的标题/值和事件档的前提说明。 */
    private void bindTriggerRows(boolean forHome, TextView label, TextView value, TextView hint) {
        boolean event = LibraryStore.isEventTrigger(this, forHome);
        label.setText(event ? R.string.min_gap_label : R.string.lib_interval_label);
        value.setText(slotRowValue(forHome));
        hint.setVisibility(event ? View.VISIBLE : View.GONE);
        hint.setText(forHome ? R.string.trigger_hint_return : R.string.trigger_hint_screen_off);
    }

    /** 事件档最小间隔：0~60 分钟滚轮（0 = 每次都换），两面各存各的。 */
    void showMinGapPicker(final boolean forHome, final TextView tvValue) {
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(pad, pad / 2, pad, 0);
        final NumberPicker picker = new NumberPicker(this);
        picker.setMinValue(0);
        picker.setMaxValue(60);
        picker.setWrapSelectorWheel(false);
        picker.setValue(Math.min(60, Switcher.eventMinGapMinutes(this, forHome)));
        row.addView(wheelColumn(picker, getString(R.string.return_gap_unit)));
        new AppAlertDialogBuilder(this)
                .setTitle(forHome ? R.string.return_gap_title : R.string.screen_off_gap_title)
                .setView(row)
                .setPositiveButton(R.string.confirm, (dialog, which) -> {
                    Switcher.setEventMinGapMinutes(MainActivity.this, forHome, picker.getValue());
                    tvValue.setText(slotRowValue(forHome));
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /**
     * 间隔滚轮选择器（时/分上下滑）：分钟按 15 步进（00/15/30/45），下限 15 分钟、上限 23:45。
     */
    void showIntervalPicker(final boolean forHome, final TextView tvInterval) {
        int minutesTotal = Math.max(15, LibraryStore.scopeIntervalSeconds(this, forHome) / 60);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(pad, pad / 2, pad, 0);
        final NumberPicker hourPicker = new NumberPicker(this);
        hourPicker.setMinValue(0);
        hourPicker.setMaxValue(23);
        hourPicker.setWrapSelectorWheel(true);
        hourPicker.setFormatter(value -> String.format(Locale.getDefault(), "%02d", value));
        hourPicker.setValue(minutesTotal / 60);
        final NumberPicker minutePicker = new NumberPicker(this);
        minutePicker.setMinValue(0);
        minutePicker.setMaxValue(3);
        minutePicker.setWrapSelectorWheel(true);
        minutePicker.setDisplayedValues(new String[]{"00", "15", "30", "45"});
        minutePicker.setValue((minutesTotal % 60) / 15);
        row.addView(wheelColumn(hourPicker, getString(R.string.interval_hour)));
        row.addView(wheelColumn(minutePicker, getString(R.string.interval_minute)));
        new AppAlertDialogBuilder(this)
                .setTitle(R.string.lib_interval)
                .setView(row)
                .setPositiveButton(R.string.confirm, (dialog, which) -> {
                    int seconds = (hourPicker.getValue() * 60 + minutePicker.getValue() * 15) * 60;
                    LibraryStore.setScopeInterval(MainActivity.this, forHome, seconds);
                    tvInterval.setText(slotIntervalDisplay(forHome));
                    refreshTimerStatus();
                    refreshSlotCards();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }


    /** 间隔展示：60 的整数倍显示分钟，其余显示「X分Y秒」或「X秒」（兼容历史秒级值）。 */
    String formatInterval(int seconds) {
        if (seconds >= 60 && seconds % 60 == 0) {
            return (seconds / 60) + "分钟";
        }
        if (seconds >= 60) {
            return (seconds / 60) + "分" + (seconds % 60) + "秒";
        }
        return seconds + "秒";
    }

    /**
     * 手动切换：按该库自己的进度推进一张并上屏（与小组件、定时共用 Switcher.next 收口）。
     * 切换含大图解码、系统调用与失败时的延迟重试（Switcher 内部），放后台线程避免卡 UI。
     */
    void switchAndToast(final LibraryStore.Library lib, final boolean forHome) {
        final Context app = getApplicationContext();
        new Thread(() -> {
            final boolean ok = Switcher.next(app, lib.id, forHome);
            if (ok) {
                // 手动切完 = 这一轮已经切过一次，定时从此刻重新起算（免得刚切完紧接着又被自动切）
                TimerScheduler.restartScope(app, forHome);
            }
            runOnUiThread(() -> {
                if (ok) {
                    Toast.makeText(this, R.string.switch_done, Toast.LENGTH_SHORT).show();
                    // 切完锁屏后「桌面/锁屏：WallPaper / 系统」这行会变，立刻刷新，别等下次进应用
                    refreshTakeoverStatus();
                    // 下次预计时间也被重排了，状态行同步刷新
                    refreshTimerStatus();
                    // 范围卡片的缩略图 = 该范围在屏那张，切完就该换
                    refreshSlotCards();
                } else {
                    // 带上具体失败原因（如桌面被动态壁纸占用），方便对症处理
                    Toast.makeText(this, Switcher.errorText(this, Switcher.lastError()),
                            Toast.LENGTH_LONG).show();
                }
            });
        }, "manual-switch").start();
    }

    /**
     * 定时状态行：桌面/锁屏各一行「上次执行结果 + 下次预计时间」；若已过预计时间仍未见系统执行，
     * 说明被 Doze/ROM 冻结拦住了（等待调度，点亮屏幕/刷新小组件/打开本应用会自动补切）。
     * v3.60 起按范围槽位展示：没设库的范围不显示，两个都空显示引导文案。
     */
    void refreshTimerStatus() {
        TextView tv = findViewById(R.id.tv_timer_status);
        if (tv == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (boolean forHome : new boolean[]{true, false}) {
            if (LibraryStore.slotLibId(this, forHome) == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(getString(forHome ? R.string.scope_home : R.string.scope_lock))
                    .append("：").append(scopeTimerLine(forHome));
        }
        if (sb.length() == 0) {
            tv.setText(R.string.timer_status_none);
            return;
        }
        tv.setText(sb.toString());
    }

    /** 单个范围的定时状态文案：上次 时间（结果）｜下次预计 时间；过点未执行给补切提示。 */
    String scopeTimerLine(boolean forHome) {
        if (LibraryStore.slotPaused(this, forHome)) {
            // 暂停的那一面没有「下次」：任务已撤，倒计时留着只会骗人
            return getString(R.string.slot_paused_label);
        }
        // 事件档没有定时格子：显示触发方式与上次由它换图的时刻（v3.152 回桌面 / v3.170 灭屏）
        if (LibraryStore.isEventTrigger(this, forHome)) {
            long lastEvent = Switcher.lastEventSwitch(this, forHome);
            if (forHome) {
                return lastEvent > 0
                        ? getString(R.string.timer_status_return, formatClock(lastEvent))
                        : getString(R.string.timer_status_return_never);
            }
            return lastEvent > 0
                    ? getString(R.string.timer_status_screen_off, formatClock(lastEvent))
                    : getString(R.string.timer_status_screen_off_never);
        }
        long next = TimerScheduler.scopeTrigger(this, forHome);
        long now = System.currentTimeMillis();
        if (next >= 0 && next <= now) {
            return getString(R.string.timer_status_overdue,
                    formatInterval((int) Math.max(60, (now - next) / 1000)));
        }
        String result = TimerScheduler.lastResult(this, forHome);
        String resultText;
        if (TimerScheduler.RESULT_OK.equals(result)) {
            resultText = getString(R.string.status_ok);
        } else if (result == null) {
            resultText = getString(R.string.status_never);
        } else {
            // 失败原因统一走可读文案映射（解码失败/系统未应用/被动态壁纸占用等）
            resultText = Switcher.errorText(this, result);
        }
        long last = TimerScheduler.lastRun(this, forHome);
        String lastText = last > 0 ? formatClock(last) : getString(R.string.status_never);
        String nextText = next >= 0 ? formatClock(next) : getString(R.string.status_never);
        return getString(R.string.timer_status_on, lastText, resultText, nextText);
    }

    /** 时间戳 → HH:mm。 */
    String formatClock(long millis) {
        return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(millis));
    }

    /** 是否已允许忽略电池优化。 */
    boolean isIgnoringBatteryOptimizations() {
        return perm.isIgnoringBatteryOptimizations();
    }

    /** 检查电池优化白名单：未忽略且未提示过时弹一次引导。 */
    void maybePromptBattery() {
        perm.maybePromptBattery();
    }

    /** 打开本应用的系统详情页（荣耀/华为在此开启自启动、后台运行白名单）。 */
    void openAppDetails() {
        perm.openAppDetails();
    }

    /** 跳转系统「忽略电池优化」授权页（个别机型不支持该 action 时静默）。 */
    void requestIgnoreBattery() {
        perm.requestIgnoreBattery();
    }

    /** 打开系统相册多选。 */
    void launchPicker() {
        importer.launchPicker();
    }

    void onPicked(List<Uri> uris) {
        importer.onPicked(uris);
    }

    /** 外部导入页交过来的 id 列表；取完把 extra 抹掉，避免旋转重建再消费一次。 */
    private void readSharedImportIntent(Intent intent) {
        importer.readSharedIntent(intent);
    }

    private boolean consumeSharedImport() {
        return importer.consumeSharedImport();
    }

    /** 进程重启后收件箱也走同一分流，避免 MP4 被当成图片解码后删掉。 */
    void openEdit(String inboxId) {
        importer.openEdit(inboxId);
    }

    void cancelVideoInbox(String inboxId) {
        importer.cancelVideoInbox(inboxId);
    }

    void launchInboxEditor(String inboxId, boolean video) {
        importer.launchInboxEditor(inboxId, video);
    }

    void showLinkInputDialog() {
        importer.showLinkInputDialog();
    }

    void pasteClipboardInto(EditText input) {
        importer.pasteClipboardInto(input);
    }

    void downloadFromUrl(String url) {
        importer.downloadFromUrl(url);
    }

    boolean blockWhileInUse(WallpaperStore.Item item) {
        return importer.blockWhileInUse(item);
    }

    /** 打开编辑页重新裁剪一张已入库壁纸（点壁纸格直接进入；确认后覆盖成品图，源是原图）。 */
    void openEditItem(WallpaperStore.Item item) {
        importer.openEditItem(item);
    }

    void showTakeoverInfoDialog() {
        takeover.showInfoDialog();
    }

    void enableTakeover() {
        takeover.enable();
    }

    void startSaveCurrentWallpaper() {
        takeover.startSaveCurrentWallpaper();
    }

    void doSaveCurrentWallpaper() {
        takeover.doSaveCurrentWallpaper();
    }

    void syncTakeoverAsync() {
        takeover.syncAsync();
    }

    /** 进度弹窗：转圈提示、不可关闭（假进度；存档/导出全程只有几秒）。 */
    void showProgress(int titleRes, int msgRes) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int pad = (int) (getResources().getDisplayMetrics().density * 20);
        box.setPadding(pad, pad, pad, pad);
        ProgressBar bar = new ProgressBar(this);
        box.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView tv = new TextView(this);
        tv.setText(msgRes);
        tv.setPadding(pad / 2, 0, 0, 0);
        tv.setTextColor(getColor(R.color.text_primary));
        box.addView(tv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        takeoverProgress = new AppAlertDialogBuilder(this)
                .setTitle(titleRes)
                .setView(box)
                .setCancelable(false)
                .create();
        takeoverProgress.show();
    }

    /** 收起进度弹窗（容错：Activity 已销毁时 dismiss 会抛，吞掉即可）。 */
    void dismissTakeoverProgress() {
        if (takeoverProgress != null) {
            try {
                takeoverProgress.dismiss();
            } catch (Exception ignored) {
            }
            takeoverProgress = null;
        }
    }

    /**
     * 设置页「视频转 Live」入口：挑一段视频交裁剪页。
     * 这一档不限片段长度，产出直接写相册（Pictures/WallSwitch），不进壁纸库、也不占收件箱。
     */
    void pickLiveConverterVideo() {
        live.pickLiveConverterVideo();
    }

    /** 视频到手就开裁剪页；读权限跟着这次结果走，所以裁剪页第一步先把整段复制进缓存。 */
    void onLiveVideoPicked(Uri uri) {
        live.onLiveVideoPicked(uri);
    }

    void pickLauncherOverlay() {
        overlay.pick();
    }

    void onOverlayPicked(Uri uri) {
        overlay.onPicked(uri);
    }

    void confirmClearLauncherOverlay() {
        overlay.confirmClear();
    }

    void showLauncherOverlayMenu() {
        overlay.showMenu();
    }

    void showLauncherOverlayPreview() {
        overlay.showPreview();
    }

    /** 选一个导出目录（SAF）：选完记住并申请持久化授权。 */
    void onExportDirPicked(Uri uri) {
        backup.onExportDirPicked(uri);
    }

    /** 取消导出目录（不清除已经导出的文件）。 */
    void confirmClearExportDir() {
        backup.confirmClearExportDir();
    }

    /** 把库内全部壁纸导出到导出目录（自定义 SAF 目录或默认目录，纯 IO，放后台线程）。 */
    void exportAllWallpapers() {
        backup.exportAllWallpapers();
    }

    void startPrune() {
        backup.startPrune();
    }

    /** 备份整库：一个 zip 装下图、缩略图、两个 json 和 prefs，写进导出目录（没设自定义目录则落默认目录）。 */
    void startBackup() {
        backup.startBackup();
    }

    /** 选中备份包后先回显现场，确认了才动本机数据。 */
    void onRestoreZipPicked(Uri uri) {
        backup.onRestoreZipPicked(uri);
    }

    /**
     * 清空日志的确认窗（抽屉里长按那一行、日志弹窗里点「清空」都走这一处）：两面一起删，
     * 日志删了不可回，所以这一发要拦；老格式记录不会自动改写，留着一堆旧格式照样难看。
     *
     * @param logDialog 从日志弹窗点进来时传那个窗：确认清了顺手关掉它（里面显示的已经是没了的旧账）；
     *                  在确认窗按取消则什么都不动，日志窗还开着
     */
    void confirmClearSwitchLog(AlertDialog logDialog) {
        log.confirmClearSwitchLog(logDialog);
    }

    void setupSwitchLogPage() {
        log.setupSwitchLogPage();
    }

    void showSwitchLog() {
        log.showSwitchLog();
    }

    void closeSwitchLogPage() {
        log.closeSwitchLogPage();
    }

    void copySwitchLog(String content) {
        log.copySwitchLog(content);
    }

    int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    void showSetWallpaperDialog(WallpaperStore.Item item) {
        actions.showSetWallpaperDialog(item);
    }

    void applyWallpaper(WallpaperStore.Item item, String libId, boolean home, boolean lock) {
        actions.applyWallpaper(item, libId, home, lock);
    }

    /** 选库列表共用的一行（现成的 {@code item_slot_lib}：缩略图 + 主标题 + 副标题 + 选中勾）。 */
    private View newPinRow(View convertView, ViewGroup parent) {
        return convertView != null ? convertView
                : LayoutInflater.from(this).inflate(R.layout.item_slot_lib, parent, false);
    }

    void handleTrashClick(final WallpaperStore.Item item) {
        actions.handleTrashClick(item);
    }

    void deleteWallpaperEverywhere(WallpaperStore.Item item) {
        actions.deleteWallpaperEverywhere(item);
    }

    void clearPinned() {
        actions.clearPinned();
    }

    /** 两级选图的第一级：这张在哪个库里。选完直接进第二级。 */
    void showPinnedPicker() {
        actions.showPinnedPicker();
    }

    /** 此刻页一键设置卡的状态刷新：开关是否钉着、副标题三态（未设置 / 已配未钉 / 已钉住）。 */
    void refreshPinnedCard() {
        actions.refreshPinnedCard();
    }

    /** 卡身点按 = 配置入口：没配直接选图；配过了给「挑一张 / 清除」，与设置页那行同款。 */
    void onPinnedCardTapped() {
        actions.onPinnedCardTapped();
    }

    /** 右侧开关 = 执行键，与通知栏那颗同一件事：拨上去钉（apply），拨回去退（undo）。 */
    void onPinnedSwitchToggled(boolean on) {
        actions.onPinnedSwitchToggled(on);
    }

    /** 弹窗输入框统一套一层 TextInputLayout（M3 描边 + 浮动提示），返回可直接 setView 的容器。 */
    TextInputLayout wrapInput(EditText input, int hintRes) {
        TextInputLayout wrapper = new TextInputLayout(this);
        wrapper.setHint(getString(hintRes));
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        wrapper.setPadding(padding, 0, padding, 0);
        input.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        wrapper.addView(input);
        return wrapper;
    }

    /**
     * 库选择列表的适配器（范围设置弹窗点「壁纸库」后弹出）：
     * 行 = 该范围的代表缩略图 + 库名 + 张数，当前占位库右侧打勾；第 0 项固定是「不切换」。
     * 走 ListView 复用，只解码可见行，库多了也不卡。
     */
    private class SlotLibPickerAdapter extends BaseAdapter {

        private final List<LibraryStore.Library> libs;
        private final boolean forHome;
        // 打开列表时的占位库：整个列表的勾都按它画，不必每行重读 prefs
        private final String liveId;

        SlotLibPickerAdapter(List<LibraryStore.Library> libs, boolean forHome) {
            this.libs = libs;
            this.forHome = forHome;
            this.liveId = LibraryStore.slotLibId(MainActivity.this, forHome);
        }

        @Override
        public int getCount() {
            return libs.size() + 1;
        }

        @Override
        public Object getItem(int position) {
            return position == 0 ? null : libs.get(position - 1);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @NonNull
        @Override
        public View getView(int position, View convertView, @NonNull ViewGroup parent) {
            View row = convertView != null ? convertView
                    : LayoutInflater.from(MainActivity.this)
                            .inflate(R.layout.item_slot_lib, parent, false);
            TextView name = row.findViewById(R.id.tv_pick_name);
            TextView sub = row.findViewById(R.id.tv_pick_sub);
            ImageView thumb = row.findViewById(R.id.img_pick_thumb);
            View check = row.findViewById(R.id.iv_pick_check);
            if (position == 0) {
                name.setText(R.string.slot_lib_none);
                sub.setText(R.string.slot_none_sub);
                bindThumb(thumb, null, R.drawable.ic_tab_wallpaper, 10);
                check.setVisibility(liveId == null ? View.VISIBLE : View.INVISIBLE);
                return row;
            }
            LibraryStore.Library lib = libs.get(position - 1);
            name.setText(lib.name);
            int count = WallpaperStore.loadByLib(MainActivity.this, lib.id).size();
            sub.setText(LibraryStore.isAll(lib.id)
                    ? getString(R.string.lib_all_sub, count)
                    : getString(R.string.lib_count, count));
            bindThumb(thumb, slotThumbId(lib.id, forHome), R.drawable.ic_tab_wallpaper, 10);
            check.setVisibility(lib.id.equals(liveId) ? View.VISIBLE : View.INVISIBLE);
            return row;
        }
    }
}
