package com.example.wallswitch;

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

    private ActivityResultLauncher<PickVisualMediaRequest> pickLauncher;
    // 通知权限请求（Android 13+ 自动切换提示需要 POST_NOTIFICATIONS）
    private ActivityResultLauncher<String> notifyPermissionLauncher;
    /** 读取系统壁纸存档用（Android 13+ 是 READ_MEDIA_IMAGES，低版本 READ_EXTERNAL_STORAGE） */
    private ActivityResultLauncher<String> savePermLauncher;
    // 全局「桌面图标预览底图」的单张选图（选一次、抠图后存应用目录，之后裁剪页直接复用）
    private ActivityResultLauncher<PickVisualMediaRequest> overlayLauncher;
    // 设置页「视频转 Live」的单段选视频（选完交裁剪页，产出直接进相册，不落壁纸库）
    private ActivityResultLauncher<PickVisualMediaRequest> liveVideoLauncher;
    // SAF 导出目录选择（ACTION_OPEN_DOCUMENT_TREE）
    private ActivityResultLauncher<Uri> exportDirLauncher;
    // 还原：用户直接指一个备份包（ACTION_OPEN_DOCUMENT，每次现授临时读权限，不依赖卸载前那个目录授权）
    private ActivityResultLauncher<String[]> restoreZipLauncher;
    private SharedPreferences prefs;
    /** 保存当前壁纸 / 读备份包时的不可关闭进度弹窗（showProgress/dismissTakeoverProgress 管理）。 */
    private androidx.appcompat.app.AlertDialog takeoverProgress;
    // 库详情正在看的库 id（也兼作「上次查看的库」，导入壁纸时的目标库）
    private String currentLibId;
    private String openingInboxId;
    /** ImportActivity 交过来的这一批收件箱 id；onResume / onNewIntent 消费一次。 */
    private ArrayList<String> sharedImportIds;
    /** 多张导入的选择弹窗或批量落盘正在进行：压住 onResume 的逐张自动打开。 */
    private boolean bulkChoiceShowing;
    private static volatile boolean bulkImportRunning;
    private androidx.appcompat.app.AlertDialog bulkProgress;
    private TextView bulkProgressText;
    private volatile boolean bulkStopRequested;
    /** 整理动作的三档（{@link #applyOrganizeChange} 的 kind）：复制只挂归属，移动摘挂各一次，移出可能删文件。 */
    private static final int ORGANIZE_COPY = 1;
    private static final int ORGANIZE_MOVE = 2;
    private static final int ORGANIZE_REMOVE = 3;

    // ---- 页面骨架 ----
    private BottomNavigationView bottomNav;
    private View pageNow, pageGallery, pageLibrary, pageSettings, pageSettingsSecondary,
            pageDetail, pageLog, selectionBar;
    private int currentTab;
    private UiMotion motion;
    private UiImages images;
    private boolean detailOpen;
    private boolean logOpen;
    private String settingsSecondaryKey;
    private String settingsSecondaryTitle;
    /** 图库那一栏现在停在库详情（true）还是库卡片网格（false）。 */
    private boolean libraryOpen;
    /** 程序化拨一键设置开关时置真，别把刷新当成用户拨动又去钉/退一遍。 */
    private boolean pinnedCardRefresh;
    /** 最近一次系统栏避让尺寸（左、上、右、下），底部那档随底栏显隐换人垫。 */
    private final int[] barInsets = new int[4];

    // ---- 此刻 ----
    private ScopeViews homeViews, lockViews;
    private BottomSheetDialog quickSheet;
    private List<LibraryStore.Library> quickLibs = new ArrayList<>();
    private QuickPickAdapter quickPickAdapter;
    private String quickLibId;

    // ---- 图库 / 库详情 / 单张详情 ----
    private MaterialToolbar galleryToolbar, libraryToolbar, detailToolbar;
    private RecyclerView galleryRecycler, libraryRecycler;
    private LibAdapter libAdapter;
    private WallpaperAdapter wallpaperAdapter;
    private GridLayoutManager wallpaperLayout;
    private int wallpaperColumns = 2;
    private int wallpaperFilter;
    private String detailLibId;
    private boolean detailUsesFilter;
    private boolean settingWallpaper;
    private ItemTouchHelper libDragHelper;
    private ViewPager2 detailPager;
    private DetailAdapter detailAdapter;

    // ---- 全屏切换记录页 ----
    private MaterialToolbar logToolbar;
    private RecyclerView logRecycler;
    private MaterialButtonToggleGroup logScopeToggle;
    private MaterialButton logOrderButton;
    private ImageButton logJumpButton;
    private boolean logJumpToTop = true;
    private LogCardAdapter logAdapter;
    private final String[] logContents = {"", ""};
    private final String[] logDisplayed = {"", ""};
    private int logScope;
    private boolean logNewestFirst;
    // 打开/关闭/重新读取都推进：后台日志读取与缩略图解码回来时据此丢掉过期结果
    private int logPageToken;
    private Map<String, List<SwitchLog.EntryRef>> logRefs = new HashMap<>();
    private boolean bindingLogControls;

    private record LogSnapshot(String[] contents, String[] displayed, List<List<LogRow>> rows,
                               Map<String, List<SwitchLog.EntryRef>> refs, boolean hasAny) { }

    // ---- 单张详情的实况播放（默认不播：点顶栏实况键播一遍，或长按画面按住期间循环播） ----
    private TextureView liveView;
    private MediaPlayer livePlayer;
    private Surface liveSurface;
    // 正在播（或正准备播）的那张；null = 没在播
    private String liveId;
    // 长按触发的：松手就停、按住期间循环
    private boolean liveHeld;
    // 每次起播/停播都 +1，后台准备回来时对不上就作废（翻页、关详情、连点都靠它挡掉迟到的那一轮）
    private int liveToken;
    // 后台算好的起播参数，等 TextureView 的 Surface 就绪后再用
    private LiveSpec pendingLive;

    // ---- 单张详情的桌面图标预览（单击画面开关，与裁剪页同一张全局底图） ----
    private LauncherPreviewOverlay detailOverlay;
    private boolean detailPreview;
    // 还没设过底图时单击画面会去相册选；选完回来要接着把预览打开，而从设置页选的不用
    private boolean overlayPickForDetail;

    // 缩略图（方形 JPEG）缓存与大图（预览卡 / 详情页按屏降采样的成品图）缓存
    private final LruCache<String, Bitmap> thumbs = new LruCache<String, Bitmap>(16 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private final LruCache<String, Bitmap> previews = new LruCache<String, Bitmap>(48 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private final ExecutorService imageLoader = Executors.newFixedThreadPool(2);
    // 日志看图的旧解码路径也要服从图片修订；只在主线程提交缓存。
    private long imageEpoch;
    private long imageClearEpoch;
    private final Map<String, Long> imageRevisions = new HashMap<>();
    private final java.util.WeakHashMap<View, CellInfo> cellInfos = new java.util.WeakHashMap<>();

    // ---- 检查更新 ----
    private String updateStatus = "";
    private final Handler updateTick = new Handler(Looper.getMainLooper());
    // 本次更新的目标（弹窗确认下载时记下）：下载失败后点这一行直接按原目标重下
    private String lastUpdateSource;
    private String lastUpdateVersion;

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

        bottomNav = findViewById(R.id.bottom_nav);
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
        checkUpdateOnLaunch();

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
        super.onDestroy();
        stopLive();
        images.close();
        motion.close();
        updateTick.removeCallbacksAndMessages(null);
        imageLoader.shutdownNow();
    }

    /**
     * 系统栏避让：页面那一层只垫上、左、右；底部交给 BottomNavigationView 自己按导航条加内边距
     * （它默认就会这么做，根布局再垫一次就成了双份）。多选底栏顶替导航时自己补底部；
     * 图库多选/排序、库内多选把底部导航整条收起来，
     * 这时没人垫底部，交回 main_root 自己垫（见 {@link #applyBottomInset}）。
     * 库详情、设置二级页、日志与单张详情是独立覆盖层，各自处理 Insets，不改变一级页布局。
     */
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
            selectionBar.setPadding(0, dp(6), 0, dp(6) + b[3]);
            return insets;
        });
    }

    /** 底栏（底部导航或多选动作条）在就由它垫导航条，两条都收起来时 main_root 自己垫。 */
    private void applyBottomInset() {
        boolean barShown = bottomNav.getVisibility() == View.VISIBLE
                || selectionBar.getVisibility() == View.VISIBLE;
        findViewById(R.id.main_root).setPadding(
                barInsets[0], barInsets[1], barInsets[2], barShown ? 0 : barInsets[3]);
    }


    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ==================== 导航 ====================

    private void setupNavigation() {
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
        if (libAdapter.mode != LibAdapter.NORMAL) {
            exitGalleryMode();
        }
        View target = id == R.id.nav_gallery ? pageGallery
                : id == R.id.nav_settings ? pageSettings : pageNow;
        currentTab = id;
        updateBottomNav();
        Runnable prepare = () -> {
            android.os.Trace.beginSection("ui.page.prepare");
            try {
                if (id == R.id.nav_gallery) {
                    refreshLibs();
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
                return id != R.id.nav_gallery || libAdapter.prepareVisible();
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
    private void updateBottomNav() {
        // 进程重建时设置页的 Fragment 会在 super.onCreate 里就跑完 onCreatePreferences，
        // 那时骨架还没 findViewById、两个 adapter 也还没建，先放过（onCreate 末尾会再算一次）。
        if (bottomNav == null || libAdapter == null || wallpaperAdapter == null) {
            return;
        }
        boolean secondary = libAdapter.mode != LibAdapter.NORMAL
                || wallpaperAdapter.isOrganizeMode();
        bottomNav.setVisibility(secondary ? View.GONE : View.VISIBLE);
        applyBottomInset();
    }

    private void openLibrary(String libId) {
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

    private void closeLibrary() {
        exitOrganizeMode();
        closeDetail();
        libraryOpen = false;
        refreshLibs();
        motion.hide(pageLibrary);
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
                if (libAdapter.mode != LibAdapter.NORMAL) {
                    exitGalleryMode();
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
    private void refreshCurrentPage() {
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
        // 打开应用同样是“设备活跃”时机：后台补跑被 Doze/ROM 冻结而漏掉的定时切换
        new Thread(() -> {
            final boolean did = TimerScheduler.catchUp(getApplicationContext());
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
        UpdateDownloader.pruneInstalled(this);
        bindUpdateState();
        if (consumeSharedImport()) {
            return;
        }
        List<String> pending = WallpaperStore.pendingInbox(this);
        if (!pending.isEmpty()) {
            // 批量落盘/选择弹窗进行中：别让 onResume 抢先弹逐张编辑页
            if (bulkChoiceShowing || bulkImportRunning) {
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

    private void evictImages(String id) {
        imageEpoch++;
        imageRevisions.put(id, imageRevision(id) + 1);
        images.evict(id);
        if (id.equals(liveId)) {
            stopLive();
        }
    }

    private long imageRevision(String id) {
        return imageRevisions.getOrDefault(id, 0L);
    }

    private void clearImages() {
        imageEpoch++;
        imageClearEpoch++;
        imageRevisions.clear();
        cellInfos.clear();
        stopLive();
        images.clear();
    }

    // ==================== 图片加载 / 列表快照 ====================

    private void unbindImageTree(View view) {
        images.unbind(view);
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                unbindImageTree(group.getChildAt(i));
            }
        }
    }

    /** detach 不是 recycle：缓存 holder 重新挂上时重新订阅，但保留已显示的同 id 位图。 */
    private abstract class ImageAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private RecyclerView recycler;
        private boolean shown;
        private final UiMotion.ContentBindings bindings = new UiMotion.ContentBindings();

        ImageAdapter() {
            registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
                @Override public void onChanged() { bindings.changed(); }
                @Override public void onItemRangeChanged(int start, int count) { bindings.changed(); }
                @Override public void onItemRangeInserted(int start, int count) { bindings.changed(); }
                @Override public void onItemRangeRemoved(int start, int count) { bindings.changed(); }
                @Override public void onItemRangeMoved(int from, int to, int count) { bindings.changed(); }
            });
        }

        private boolean hasImageBindings(View view) {
            if (view instanceof ImageView image && view.getTag() instanceof String id
                    && !images.isBound(image, id)) return false;
            if (view instanceof ViewGroup group) {
                for (int i = 0; i < group.getChildCount(); i++) {
                    if (!hasImageBindings(group.getChildAt(i))) return false;
                }
            }
            return true;
        }

        abstract void bindRow(RecyclerView.ViewHolder holder, int position);

        @Override public final void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            bindRow(holder, position);
            bindings.bound(holder.itemView, position);
        }

        boolean prepareVisible() {
            if (recycler == null || !recycler.isLaidOut() || recycler.isComputingLayout()
                    || recycler.hasPendingAdapterUpdates() || recycler.isLayoutRequested()) return false;
            for (int i = 0; i < recycler.getChildCount(); i++) {
                RecyclerView.ViewHolder holder = recycler.getChildViewHolder(recycler.getChildAt(i));
                int position = holder.getBindingAdapterPosition();
                if (position == RecyclerView.NO_POSITION || position >= getItemCount()) return false;
                if (!bindings.matches(holder.itemView, position) || !hasImageBindings(holder.itemView)) {
                    onBindViewHolder(holder, position);
                }
            }
            return !recycler.hasPendingAdapterUpdates() && !recycler.isLayoutRequested();
        }
        private final android.view.ViewTreeObserver.OnGlobalLayoutListener visibility = () -> {
            if (recycler == null || shown == recycler.isShown()) return;
            shown = recycler.isShown();
            if (shown) rebindVisible();
            else unbindImageTree(recycler);
        };

        @Override public void onAttachedToRecyclerView(@NonNull RecyclerView rv) {
            recycler = rv;
            rv.setItemAnimator(null);
            shown = rv.isShown();
            rv.getViewTreeObserver().addOnGlobalLayoutListener(visibility);
        }

        @Override public void onDetachedFromRecyclerView(@NonNull RecyclerView rv) {
            rv.getViewTreeObserver().removeOnGlobalLayoutListener(visibility);
            unbindImageTree(rv);
            recycler = null;
        }

        @Override public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
            unbindImageTree(holder.itemView);
        }

        @Override public void onViewDetachedFromWindow(@NonNull RecyclerView.ViewHolder holder) {
            unbindImageTree(holder.itemView);
        }

        @Override public void onViewAttachedToWindow(@NonNull RecyclerView.ViewHolder holder) {
            int position = holder.getBindingAdapterPosition();
            if (position != RecyclerView.NO_POSITION && position < getItemCount()) {
                onBindViewHolder(holder, position);
            }
        }

        void rebindVisible() {
            if (recycler == null) return;
            motion.whenIdle(this, () -> {
                if (recycler == null || !recycler.isShown()) return;
                for (int i = 0; i < recycler.getChildCount(); i++) {
                    onViewAttachedToWindow(recycler.getChildViewHolder(recycler.getChildAt(i)));
                }
            });
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
    private record DisplayRow(String id, List<?> fields) { }

    private static DisplayRow displayRow(String id, Object... fields) {
        return new DisplayRow(id, java.util.Arrays.asList(fields));
    }

    private List<DisplayRow> wallpaperRows(List<WallpaperStore.Item> items) {
        List<DisplayRow> rows = new ArrayList<>();
        for (WallpaperStore.Item item : items) {
            rows.add(displayRow(item.id, item.title, item.libId, new ArrayList<>(item.libIds),
                    item.srcWidth, item.srcHeight, item.cropLeft, item.cropTop, item.cropRight,
                    item.cropBottom, imageRevision(item.id), imageClearEpoch,
                    Switcher.inUseWhere(this, item.id)));
        }
        return rows;
    }

    private static DiffUtil.DiffResult diffRows(List<DisplayRow> before, List<DisplayRow> after) {
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
    private static Bitmap decodeFullHalf(Context app, String id) {
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
                if (id != null) {
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
    private void refreshTakeoverUi() {
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
    private void refreshTakeoverStatus() {
        refreshCurrentPage();
    }

    /** 最近切换里那一行：两面最近一条记录的时间。纯读文件，放后台线程。 */
    private void refreshSwitchLogRow() {
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
    private boolean ensureTakeoverForScope(boolean forHome) {
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
        tvInterval.setText(slotIntervalDisplay(forHome));
        content.findViewById(R.id.row_interval).setOnClickListener(
                v -> showIntervalPicker(forHome, tvInterval));
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

    // ==================== 图库 ====================

    private void setupGalleryPage() {
        galleryToolbar = findViewById(R.id.toolbar_gallery);
        galleryRecycler = findViewById(R.id.recycler_gallery);
        libAdapter = new LibAdapter();
        galleryRecycler.setLayoutManager(new GridLayoutManager(this, 2));
        galleryRecycler.setAdapter(libAdapter);
        galleryToolbar.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_new_lib) {
                showNewLibDialog();
            } else if (id == R.id.action_reorder) {
                setGalleryMode(LibAdapter.REORDER);
            } else if (id == R.id.action_select_libs) {
                setGalleryMode(LibAdapter.SELECT);
            } else if (id == R.id.action_delete_libs) {
                confirmDeleteSelectedLibs();
            } else if (id == R.id.action_reorder_done) {
                exitGalleryMode();
            }
            return true;
        });
        libDragHelper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN | ItemTouchHelper.START | ItemTouchHelper.END, 0) {
            @Override
            public boolean isLongPressDragEnabled() {
                return false;
            }

            @Override
            public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder from,
                                  @NonNull RecyclerView.ViewHolder to) {
                return libAdapter.mode == LibAdapter.REORDER
                        && libAdapter.move(from.getBindingAdapterPosition(), to.getBindingAdapterPosition());
            }

            @Override
            public void clearView(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder holder) {
                super.clearView(rv, holder);
                if (libAdapter.mode == LibAdapter.REORDER) {
                    LibraryStore.reorder(MainActivity.this, libAdapter.currentIds());
                }
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder holder, int direction) {
            }
        });
        libDragHelper.attachToRecyclerView(galleryRecycler);
        updateGalleryToolbar();
    }

    /** 刷新库卡片（张数、封面、桌面/锁屏标签）。 */
    private void refreshLibs() {
        libAdapter.setItems(LibraryStore.load(this));
    }

    private void setGalleryMode(int mode) {
        libAdapter.setMode(mode);
        updateBottomNav();
        updateGalleryToolbar();
    }

    private void exitGalleryMode() {
        if (libAdapter.mode == LibAdapter.NORMAL) {
            return;
        }
        setGalleryMode(LibAdapter.NORMAL);
    }

    /** 顶栏随三态换：普通 = 新建 + ⋯；多选 = ✕ + 已选 N 个 + 删除；排序 = ✕ + 提示 + ✓。 */
    private void updateGalleryToolbar() {
        Menu menu = galleryToolbar.getMenu();
        int mode = libAdapter.mode;
        menu.setGroupVisible(R.id.group_gallery_normal, mode == LibAdapter.NORMAL);
        menu.setGroupVisible(R.id.group_gallery_select, mode == LibAdapter.SELECT);
        menu.setGroupVisible(R.id.group_gallery_reorder, mode == LibAdapter.REORDER);
        findViewById(R.id.tv_gallery_hint).setVisibility(mode == LibAdapter.REORDER ? View.VISIBLE : View.GONE);
        if (mode == LibAdapter.NORMAL) {
            galleryToolbar.setNavigationIcon(null);
            galleryToolbar.setTitle(R.string.tab_gallery);
        } else {
            galleryToolbar.setNavigationIcon(R.drawable.ic_close);
            galleryToolbar.setNavigationOnClickListener(v -> exitGalleryMode());
            galleryToolbar.setTitle(mode == LibAdapter.SELECT
                    ? getString(R.string.gallery_select_count, libAdapter.selectedIds.size())
                    : getString(R.string.gallery_reorder));
        }
    }

    /** 多选删库：先弹二次确认（库与库里的壁纸一并删除），确认后逐库删除。没勾选时只提示。 */
    private void confirmDeleteSelectedLibs() {
        final List<LibraryStore.Library> selected = libAdapter.selectedLibs();
        if (selected.isEmpty()) {
            Toast.makeText(this, R.string.lib_delete_none, Toast.LENGTH_SHORT).show();
            return;
        }
        confirmDeleteLibs(selected);
    }

    private void confirmDeleteLibs(final List<LibraryStore.Library> libs) {
        AlertDialog dialog = new AppAlertDialogBuilder(this)
                .setTitle(getString(R.string.lib_delete_batch_title, libs.size()))
                .setMessage(R.string.lib_delete_batch_msg)
                .setPositiveButton(R.string.delete, (d, which) -> deleteLibs(libs))
                .setNegativeButton(R.string.cancel, null)
                .show();
        Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        positive.setTextColor(MaterialColors.getColor(positive, androidx.appcompat.R.attr.colorError));
    }

    /** 批量删库：连带清库内壁纸文件、切换进度与定时任务（都在 LibraryStore.delete 内部）。 */
    private void deleteLibs(List<LibraryStore.Library> libs) {
        List<WallpaperStore.Item> before = WallpaperStore.load(this);
        boolean removedCurrent = false;
        for (LibraryStore.Library lib : libs) {
            LibraryStore.delete(this, lib.id);
            if (lib.id.equals(currentLibId())) {
                removedCurrent = true;
            }
        }
        Set<String> alive = new HashSet<>();
        for (WallpaperStore.Item item : WallpaperStore.load(this)) {
            alive.add(item.id);
        }
        for (WallpaperStore.Item item : before) {
            if (!alive.contains(item.id)) {
                evictImages(item.id);
            }
        }
        if (removedCurrent) {
            currentLibId = null;
            prefs.edit().remove(LibraryStore.KEY_CURRENT_LIB).apply();
            if (libraryOpen) {
                closeLibrary();
            }
        }
        exitGalleryMode();
        refreshLibs();
        refreshTimerStatus();
        // 删除在用的库会改变「谁在被接管」，同步一次接管状态
        syncTakeoverAsync();
    }

    /** 库卡片网格：末尾一格「新建库」（排序与多选态下不显示）。 */
    private class LibAdapter extends ImageAdapter {

        static final int NORMAL = 0;
        static final int SELECT = 1;
        static final int REORDER = 2;
        private static final int TYPE_LIB = 0;
        private static final int TYPE_NEW = 1;

        private final class Row {
            final LibraryStore.Library lib;
            final int count;
            final String thumbId;
            final DisplayRow snapshot;

            Row(LibraryStore.Library lib, int count, String thumbId) {
                this.lib = lib;
                this.count = count;
                this.thumbId = thumbId;
                snapshot = displayRow(lib.id, lib.name, count, thumbId, imageRevision(thumbId), imageClearEpoch,
                        LibraryStore.ownsScope(MainActivity.this, lib.id, true),
                        LibraryStore.ownsScope(MainActivity.this, lib.id, false));
            }
        }

        private final List<Row> rows = new ArrayList<>();
        int mode = NORMAL;
        final Set<String> selectedIds = new HashSet<>();

        void setItems(List<LibraryStore.Library> libs) {
            List<DisplayRow> before = rowSnapshots();
            List<Row> next = new ArrayList<>();
            Map<String, Integer> counts = WallpaperStore.libMemberCounts(MainActivity.this);
            for (LibraryStore.Library lib : libs) {
                Integer n = counts.get(lib.id);
                next.add(new Row(lib, n == null ? 0 : n, libCoverId(lib)));
            }
            rows.clear();
            rows.addAll(next);
            selectedIds.retainAll(currentIds());
            List<DisplayRow> after = rowSnapshots();
            if (!before.equals(after)) diffRows(before, after).dispatchUpdatesTo(this);
            if (mode == SELECT) {
                updateGalleryToolbar();
            }
        }

        private List<DisplayRow> rowSnapshots() {
            List<DisplayRow> out = new ArrayList<>();
            for (Row row : rows) out.add(row.snapshot);
            if (mode == NORMAL) out.add(displayRow("new-library"));
            return out;
        }

        void setMode(int newMode) {
            mode = newMode;
            selectedIds.clear();
            notifyDataSetChanged();
        }

        List<String> currentIds() {
            List<String> ids = new ArrayList<>();
            for (Row row : rows) {
                ids.add(row.lib.id);
            }
            return ids;
        }

        List<LibraryStore.Library> selectedLibs() {
            List<LibraryStore.Library> out = new ArrayList<>();
            for (Row row : rows) {
                if (selectedIds.contains(row.lib.id)) {
                    out.add(row.lib);
                }
            }
            return out;
        }

        boolean move(int from, int to) {
            if (from < 0 || to < 0 || from >= rows.size() || to >= rows.size()) {
                return false;
            }
            rows.add(to, rows.remove(from));
            notifyItemMoved(from, to);
            return true;
        }

        /** 槽位变更后标签要跟上；库详情的头部（设为桌面/锁屏两颗键）同一处刷。 */
        void refreshBadges() {
            notifyDataSetChanged();
            if (wallpaperAdapter != null) {
                wallpaperAdapter.refreshHeader();
            }
        }

        private void toggle(String libId) {
            if (!selectedIds.remove(libId)) {
                selectedIds.add(libId);
            }
            notifyDataSetChanged();
            updateGalleryToolbar();
        }

        @Override
        public int getItemViewType(int position) {
            return position < rows.size() ? TYPE_LIB : TYPE_NEW;
        }

        @Override
        public int getItemCount() {
            return rows.size() + (mode == NORMAL ? 1 : 0);
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            int layout = viewType == TYPE_LIB ? R.layout.item_gallery_lib : R.layout.item_gallery_new;
            View view = LayoutInflater.from(MainActivity.this).inflate(layout, parent, false);
            return new RecyclerView.ViewHolder(view) {
            };
        }

        @Override
        void bindRow(RecyclerView.ViewHolder holder, int position) {
            View item = holder.itemView;
            if (getItemViewType(position) == TYPE_NEW) {
                // 与旁边的库卡片同高：库卡片现在只剩正方形封面（库名与张数压在封面上）
                int cell = (galleryRecycler.getWidth() > 0 ? galleryRecycler.getWidth()
                        : getResources().getDisplayMetrics().widthPixels) / 2 - dp(22);
                item.findViewById(R.id.new_lib_box).setMinimumHeight(cell);
                item.setOnClickListener(v -> showNewLibDialog());
                return;
            }
            final Row row = rows.get(position);
            final LibraryStore.Library lib = row.lib;
            MaterialCardView card = (MaterialCardView) item;
            ImageView cover = item.findViewById(R.id.img_lib_cover);
            if (row.thumbId != null) {
                cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
                images.thumbnail(cover, row.thumbId);
            } else {
                images.unbind(cover);
                cover.setTag(null);
                cover.setScaleType(ImageView.ScaleType.CENTER);
                cover.setImageResource(R.drawable.ic_tab_wallpaper);
                cover.setImageTintList(ColorStateList.valueOf(MaterialColors.getColor(cover,
                        com.google.android.material.R.attr.colorOnSurfaceVariant)));
            }
            ((TextView) item.findViewById(R.id.tv_lib_name)).setText(lib.name);
            // 封面角上只写数字，不带「张壁纸」；读屏仍念完整说法
            TextView countView = item.findViewById(R.id.tv_lib_count);
            countView.setText(String.valueOf(row.count));
            countView.setContentDescription(getString(R.string.lib_count, row.count));
            item.findViewById(R.id.badge_lib_home).setVisibility(
                    LibraryStore.ownsScope(MainActivity.this, lib.id, true) ? View.VISIBLE : View.GONE);
            item.findViewById(R.id.badge_lib_lock).setVisibility(
                    LibraryStore.ownsScope(MainActivity.this, lib.id, false) ? View.VISIBLE : View.GONE);
            item.findViewById(R.id.iv_lib_drag).setVisibility(mode == REORDER ? View.VISIBLE : View.GONE);
            card.setChecked(mode == SELECT && selectedIds.contains(lib.id));
            // 蓝勾自带颜色：XML 里写 checkedIconTint=@null 会被 MaterialCardView 退回成主题色，只能在这里清掉
            card.setCheckedIconTint(null);
            card.setOnClickListener(v -> {
                if (mode == SELECT) {
                    toggle(lib.id);
                } else if (mode == NORMAL) {
                    openLibrary(lib.id);
                }
            });
            card.setOnLongClickListener(v -> {
                if (mode == REORDER) {
                    return false;
                }
                if (mode == NORMAL) {
                    setGalleryMode(SELECT);
                }
                toggle(lib.id);
                return true;
            });
            // 排序态：按下就拎起来（不用再等长按），松手落位写回 libraries.json
            card.setOnTouchListener((v, event) -> {
                if (mode == REORDER && event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    libDragHelper.startDrag(holder);
                    return true;
                }
                return false;
            });
        }
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
    private void refreshList() {
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
    private String currentLibId() {
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
        boolean selected = wallpaperAdapter.selectedCount() > 0;
        findViewById(R.id.sel_move).setEnabled(selected);
        findViewById(R.id.sel_copy).setEnabled(selected);
        findViewById(R.id.sel_remove).setEnabled(selected);
        findViewById(R.id.sel_night).setEnabled(selected);
        bindCoverButton();
        bindNightButton();
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
        findViewById(R.id.sel_move).setOnClickListener(
                v -> showOrganizeTargetDialog(true, wallpaperAdapter.selectedIdsInOrder()));
        findViewById(R.id.sel_copy).setOnClickListener(
                v -> showOrganizeTargetDialog(false, wallpaperAdapter.selectedIdsInOrder()));
        findViewById(R.id.sel_remove).setOnClickListener(
                v -> confirmRemoveSelected(wallpaperAdapter.selectedIdsInOrder()));
        findViewById(R.id.sel_cover).setOnClickListener(v -> toggleCoverForSelection());
        findViewById(R.id.sel_night).setOnClickListener(v -> toggleNightForSelection());
    }

    /** 恰好勾一张时「设为封面」可点；勾的那张已经是本库封面时文案换成「取消封面」。 */
    private void bindCoverButton() {
        Button cover = findViewById(R.id.sel_cover);
        boolean single = wallpaperAdapter.selectedCount() == 1;
        cover.setEnabled(single);
        boolean isCover = single && wallpaperAdapter.selectedIdsInOrder().get(0)
                .equals(LibraryStore.coverId(this, currentLibId()));
        cover.setText(isCover ? R.string.sel_uncover : R.string.sel_cover);
    }

    /** 「夜间禁用」按勾选集合的整体状态显示：勾的全都禁用了，文案换成「解除禁用」（点了就是解除）。 */
    private void bindNightButton() {
        Button night = findViewById(R.id.sel_night);
        List<String> ids = wallpaperAdapter.selectedIdsInOrder();
        boolean allOff = !ids.isEmpty();
        for (String id : ids) {
            WallpaperStore.Item item = WallpaperStore.get(this, id);
            if (item == null || !item.nightOff) {
                allOff = false;
                break;
            }
        }
        night.setText(allOff ? R.string.sel_night_off : R.string.sel_night);
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
    private class WallpaperAdapter extends ImageAdapter {

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
            v.setVisibility(lib == null || organizeMode ? View.GONE : View.VISIBLE);
            if (lib == null || organizeMode) {
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
                if (liveId != null) {
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

    private String itemActionLibId() {
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

    private WallpaperStore.Item detailItem() {
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
    private void refreshDetail() {
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
        if (liveId != null && (now == null || !liveId.equals(now.id))) {
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
        refreshLiveMenu();
    }

    /** 详情页翻页：每页一张成品图按屏幕长边降采样，先放缩略图占位。 */
    private class DetailAdapter extends ImageAdapter {

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
                if (liveHeld && (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)) {
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
    private void enterDetailPreview() {
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

    /** 起播所需的一切（后台算好、主线程用）：实况段字节区间 + 播放层的位置尺寸 + 画面裁切矩阵。 */
    private static final class LiveSpec {
        String id;
        File original;
        long offset;
        long length;
        boolean loop;
        int left, top, width, height;
        Matrix transform;
    }

    private void setupLiveView() {
        liveView = findViewById(R.id.texture_live);
        liveView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture st, int w, int h) {
                LiveSpec spec = pendingLive;
                pendingLive = null;
                if (spec != null && spec.id.equals(liveId)) {
                    beginLivePlayback(spec, st);
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture st, int w, int h) {
            }

            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture st) {
                releaseLivePlayer();
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(@NonNull SurfaceTexture st) {
                // 兜底显形：有的机型不发 RENDERING_START，那就以「真有新帧到了」为准淡入
                if (liveId != null && livePlayer != null && liveView.getAlpha() == 0f) {
                    liveView.animate().alpha(1f).setDuration(120).start();
                }
            }
        });
    }

    /** 顶栏实况键：只有带实况段的那张才露出来；播放中换成暂停图标，点它停。 */
    private void bindLiveMenu(boolean live) {
        MenuItem item = detailToolbar.getMenu().findItem(R.id.action_detail_live);
        if (item == null) {
            return;
        }
        item.setVisible(live);
        boolean playing = liveId != null;
        item.setIcon(playing ? R.drawable.ic_pause : R.drawable.ic_motion_photo);
        item.setTitle(playing ? R.string.detail_live_stop : R.string.detail_live_play);
    }

    private void refreshLiveMenu() {
        WallpaperStore.Item item = detailItem();
        if (!detailOpen || item == null) {
            images.unbind(detailToolbar);
            bindLiveMenu(false);
            return;
        }
        images.unbind(detailToolbar);
        detailToolbar.setTag(item.id);
        Boolean live = images.cachedLive(item.id);
        // 未知时仍可点；startLive 已有后台 locate + liveToken，不能丢掉用户请求。
        bindLiveMenu(liveId != null || !Boolean.FALSE.equals(live));
        if (live == null) {
            images.metadata(detailToolbar, item.id, (owner, info) -> {
                WallpaperStore.Item current = detailItem();
                if (detailOpen && current != null && current.id.equals(owner.getTag())) {
                    bindLiveMenu(liveId != null || info.live);
                }
            });
        }
    }

    /**
     * 播这张的实况段。读盘与量尺寸放后台：定位实况段（读原图头尾）、读成品图宽高、读视频帧宽高与旋转。
     * 播放层摆在成品图 fitCenter 后的显示矩形上，画面按「原图坐标裁剪矩形 → 归一化 → 再按播放层比例
     * centerCrop」裁出来 —— 与桌面引擎播实况是同一套取景（见 WallSwitchService#cropRectOf），
     * 所以这里看到的就是上屏后会动的那一块。
     */
    private void startLive(WallpaperStore.Item item, boolean held) {
        if (!detailOpen) return;
        stopLive();
        final int token = liveToken;
        liveId = item.id;
        liveHeld = held;
        refreshLiveMenu();
        final int viewW = detailPager.getWidth();
        final int viewH = detailPager.getHeight();
        final float[] norm = item.hasCropRect()
                ? com.example.wallswitch.gl.MotionSource.normalizedRect(new float[]{
                        item.cropLeft, item.cropTop, item.cropRight, item.cropBottom},
                        item.srcWidth, item.srcHeight)
                : null;
        final Context app = getApplicationContext();
        final String id = item.id;
        final boolean fill = detailPreview;
        imageLoader.execute(() -> {
            LiveSpec spec = prepareLive(app, id, held, norm, viewW, viewH, fill);
            runOnUiThread(() -> {
                if (token != liveToken || isDestroyed()) {
                    return;
                }
                if (spec == null) {
                    stopLive();
                    Toast.makeText(this, R.string.detail_live_failed, Toast.LENGTH_SHORT).show();
                    return;
                }
                showLiveLayer(spec);
            });
        });
    }

    /** 后台：算出起播参数，任何一步不成立返回 null。 */
    private static LiveSpec prepareLive(Context ctx, String id, boolean loop, float[] norm,
                                        int viewW, int viewH, boolean fill) {
        if (viewW <= 0 || viewH <= 0) {
            return null;
        }
        File original = WallpaperStore.getOriginalFile(ctx, id);
        long[] range = com.example.wallswitch.gl.MotionSource.locate(original);
        if (range == null || range.length < 2 || range[1] <= 0) {
            return null;
        }
        // 成品图宽高：播放层要和静态图那块显示区域严丝合缝
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(WallpaperStore.getFullFile(ctx, id).getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        // 视频帧转正后的宽高（rotation-degrees 已由缓冲区变换承担，比例要按转正后的算）
        int frameW, frameH;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try (FileInputStream in = new FileInputStream(original)) {
            retriever.setDataSource(in.getFD(), range[0], range[1]);
            int codedW = parseIntOr(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int codedH = parseIntOr(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            int rotation = parseIntOr(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
            int[] size = com.example.wallswitch.gl.MotionSource.displaySize(codedW, codedH, rotation);
            frameW = size[0];
            frameH = size[1];
        } catch (Exception | OutOfMemoryError e) {
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
        if (frameW <= 0 || frameH <= 0) {
            return null;
        }
        // 成品图在页面里的显示矩形：平时 fitCenter；桌面预览时 centerCrop（可能比页面大，边距为负）
        float scale = fill
                ? Math.max(viewW / (float) bounds.outWidth, viewH / (float) bounds.outHeight)
                : Math.min(viewW / (float) bounds.outWidth, viewH / (float) bounds.outHeight);
        int dispW = Math.max(1, Math.round(bounds.outWidth * scale));
        int dispH = Math.max(1, Math.round(bounds.outHeight * scale));
        LiveSpec spec = new LiveSpec();
        spec.id = id;
        spec.original = original;
        spec.offset = range[0];
        spec.length = range[1];
        spec.loop = loop;
        spec.width = dispW;
        spec.height = dispH;
        spec.left = (viewW - dispW) / 2;
        spec.top = (viewH - dispH) / 2;
        spec.transform = liveTransform(norm, frameW, frameH, dispW, dispH);
        return spec;
    }

    /**
     * TextureView 默认把整帧拉伸铺满自身；这里算一个矩阵，让「取景框那块、再按播放层比例 centerCrop」
     * 正好铺满播放层。坐标全用 [0,1] 比例（v 从顶部起算），与 MotionSource#cropForViewport 同一套口径。
     */
    private static Matrix liveTransform(float[] norm, int frameW, int frameH, int viewW, int viewH) {
        float u0 = 0f, t0 = 0f, u1 = 1f, t1 = 1f;
        if (norm != null && norm.length == 4) {
            u0 = norm[0];
            t0 = norm[1];
            u1 = norm[2];
            t1 = norm[3];
        }
        float spanU = Math.max(u1 - u0, 1e-4f);
        float spanT = Math.max(t1 - t0, 1e-4f);
        float subAspect = (spanU * frameW) / (spanT * frameH);
        float vpAspect = viewW / (float) viewH;
        spanU *= Math.min(1f, vpAspect / subAspect);
        spanT *= Math.min(1f, subAspect / vpAspect);
        float a = (u0 + u1) * 0.5f - spanU * 0.5f;
        float c = (t0 + t1) * 0.5f - spanT * 0.5f;
        float sx = 1f / spanU;
        float sy = 1f / spanT;
        Matrix m = new Matrix();
        m.setScale(sx, sy);
        m.postTranslate(-a * viewW * sx, -c * viewH * sy);
        return m;
    }

    private static int parseIntOr(String s) {
        try {
            return s == null ? 0 : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 主线程：把播放层摆到成品图的显示矩形上；Surface 就绪后再起播放器。 */
    private void showLiveLayer(LiveSpec spec) {
        android.widget.FrameLayout.LayoutParams lp =
                (android.widget.FrameLayout.LayoutParams) liveView.getLayoutParams();
        lp.width = spec.width;
        lp.height = spec.height;
        lp.leftMargin = spec.left;
        lp.topMargin = spec.top;
        liveView.setLayoutParams(lp);
        liveView.setTransform(spec.transform);
        // 首帧出来前透明：先盖一块黑再出画面会闪
        liveView.setAlpha(0f);
        // TextureView 设成 GONE 并不会销毁它的 SurfaceTexture（只有从窗口上拆下来才销毁），
        // 所以第二次起播时 isAvailable() 仍是 true、onSurfaceTextureAvailable 也不会再回调。
        // 以前这里要求「已经 VISIBLE 且 available」才直接播，否则挂 pending 等回调 —— 第一次播完收成 GONE 后，
        // 再播就落进等回调那一支、永远等不来，只能杀进程。现在只看 available：有就直接播
        liveView.setVisibility(View.VISIBLE);
        if (liveView.isAvailable()) {
            beginLivePlayback(spec, liveView.getSurfaceTexture());
        } else {
            pendingLive = spec;
        }
    }

    private void beginLivePlayback(LiveSpec spec, SurfaceTexture st) {
        releaseLivePlayer();
        final int token = liveToken;
        MediaPlayer player = new MediaPlayer();
        try (FileInputStream in = new FileInputStream(spec.original)) {
            // setDataSource 会自己 dup 一份 fd，流在这里关掉不影响播放
            player.setDataSource(in.getFD(), spec.offset, spec.length);
        } catch (Exception e) {
            player.release();
            stopLive();
            Toast.makeText(this, R.string.detail_live_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        liveSurface = new Surface(st);
        player.setSurface(liveSurface);
        // 与桌面引擎一致：实况段那条音轨不播
        player.setVolume(0f, 0f);
        player.setLooping(spec.loop);
        player.setOnPreparedListener(mp -> {
            if (token == liveToken) {
                mp.start();
            }
        });
        player.setOnInfoListener((mp, what, extra) -> {
            if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && token == liveToken) {
                liveView.animate().alpha(1f).setDuration(120).start();
            }
            return false;
        });
        player.setOnCompletionListener(mp -> {
            // 点键播的那一遍播完就退回静态图；长按是循环的，走不到这里
            if (token == liveToken) {
                stopLive();
            }
        });
        player.setOnErrorListener((mp, what, extra) -> {
            if (token == liveToken) {
                stopLive();
                Toast.makeText(this, R.string.detail_live_failed, Toast.LENGTH_SHORT).show();
            }
            return true;
        });
        livePlayer = player;
        try {
            player.prepareAsync();
        } catch (Exception e) {
            stopLive();
            Toast.makeText(this, R.string.detail_live_failed, Toast.LENGTH_SHORT).show();
        }
    }

    /** 停播并收起播放层（任何时候都可以调，没在播就只是把状态归零）。 */
    private void stopLive() {
        liveToken++;
        boolean wasPlaying = liveId != null;
        liveId = null;
        liveHeld = false;
        pendingLive = null;
        releaseLivePlayer();
        if (liveView != null) {
            liveView.animate().cancel();
            liveView.setAlpha(0f);
            liveView.setVisibility(View.GONE);
        }
        if (wasPlaying && detailToolbar != null) {
            refreshLiveMenu();
        }
    }

    private void releaseLivePlayer() {
        if (livePlayer != null) {
            try {
                livePlayer.release();
            } catch (Exception ignored) {
            }
            livePlayer = null;
        }
        if (liveSurface != null) {
            liveSurface.release();
            liveSurface = null;
        }
    }

    // ==================== 设置页的入口（SettingsFragment 调这些） ====================

    /** 设置页只在露着时现算；切过去时 showTab 会刷一遍，不在后台白读日志文件。 */
    private void refreshSettings() {
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

    private void refreshLauncherOverlayRow() {
        refreshSettings();
    }

    private void refreshExportRows() {
        refreshSettings();
    }

    private void refreshBackupRow() {
        refreshSettings();
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
        exportDirLauncher.launch(null);
    }

    void launchRestorePicker() {
        restoreZipLauncher.launch(new String[]{"application/zip", "*/*"});
    }

    String updateStatus() {
        return updateStatus;
    }

    private void setUpdateStatus(int res) {
        setUpdateStatus(getString(res));
    }

    private void setUpdateStatus(String text) {
        updateStatus = text;
        androidx.fragment.app.Fragment f = getSupportFragmentManager().findFragmentById(R.id.settings_container);
        if (f instanceof SettingsFragment) {
            ((SettingsFragment) f).refreshUpdateRow();
        }
        androidx.fragment.app.Fragment secondary = getSupportFragmentManager()
                .findFragmentById(R.id.settings_secondary_container);
        if (secondary instanceof SettingsFragment) {
            ((SettingsFragment) secondary).refreshUpdateRow();
        }
    }

    /** 启动时静默检查一次（「自动检查更新」开着才查）：只在发现新版时打扰，失败静默。 */
    private void checkUpdateOnLaunch() {
        UpdateDownloader.pruneInstalled(this);
        bindUpdateState();
        if (!UpdateChecker.autoCheck(this)) {
            return;
        }
        UpdateChecker.checkAsync(this, (info, error) -> {
            if (error == null && info != null && !isFinishing() && !isDestroyed()
                    && UpdateDownloader.state(this) != UpdateDownloader.DOWNLOADING
                    && UpdateChecker.isNewer(info.version, UpdateChecker.localVersion(this))) {
                showUpdateDialog(info, UpdateChecker.source(this));
            }
        });
    }

    /** 「检查更新」那一行点下去干什么，完全由下载状态决定：下载中问要不要取消、有包就装、其余去检查。 */
    void onUpdateRowTapped() {
        int state = UpdateDownloader.state(this);
        if (state == UpdateDownloader.DOWNLOADING) {
            new AppAlertDialogBuilder(this)
                    .setTitle(R.string.update_check_title)
                    .setMessage(getString(R.string.update_downloading, UpdateDownloader.percent()))
                    .setPositiveButton(R.string.update_cancel, (d, which) -> {
                        UpdateDownloader.cancel();
                        // 目标一并忘掉：取消后再点这一行是重新检查，而不是照原目标续下
                        lastUpdateSource = lastUpdateVersion = null;
                        bindUpdateState();
                    })
                    .setNegativeButton(R.string.close, null)
                    .show();
            return;
        }
        if (state == UpdateDownloader.READY) {
            installReadyPackage();
            return;
        }
        if (state == UpdateDownloader.FAILED && lastUpdateVersion != null) {
            startUpdateDownload(lastUpdateSource, lastUpdateVersion);
            return;
        }
        checkUpdateNow();
    }

    /** 按下载状态刷那一行；只有下载中才留一个 300ms 的轮询，其余状态立刻停手。 */
    private void bindUpdateState() {
        int state = UpdateDownloader.state(this);
        updateTick.removeCallbacks(updatePoller);
        if (state == UpdateDownloader.DOWNLOADING) {
            setUpdateStatus(getString(R.string.update_downloading_tap, UpdateDownloader.percent()));
            updateTick.postDelayed(updatePoller, 300);
            return;
        }
        if (state == UpdateDownloader.READY) {
            setUpdateStatus(getString(R.string.update_ready, UpdateDownloader.readyVersion(this)));
        } else if (state == UpdateDownloader.FAILED) {
            setUpdateStatus(R.string.update_failed);
        } else {
            setUpdateStatus(getString(R.string.update_local_version, UpdateChecker.localVersion(this)));
        }
    }

    /** 电池优化白名单行：设置页那一行自己读，这里只是给老调用点留个名字。 */
    private void refreshBatteryRow() {
        refreshSettings();
    }

    /** 新建库弹窗：建好直接进它的库详情（接着就是「添加」）。 */
    private void showNewLibDialog() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        new AppAlertDialogBuilder(this)
                .setTitle(R.string.lib_new)
                .setView(wrapInput(input, R.string.lib_name_hint))
                .setPositiveButton(R.string.confirm, (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    LibraryStore.Library created = LibraryStore.create(MainActivity.this, name);
                    refreshLibs();
                    if (created != null) {
                        openLibrary(created.id);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
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
        final MetaFiles.Notice notice = MetaFiles.takeNotice();
        if (notice == null) {
            return;
        }
        String dir = getFilesDir().getAbsolutePath();
        String msg;
        if (notice.salvaged) {
            msg = getString(R.string.meta_notice_salvaged, notice.fileName, dir + "/" + notice.fileName,
                    notice.kept, notice.dropped,
                    notice.archivePath == null ? getString(R.string.meta_archive_failed) : notice.archivePath);
        } else {
            msg = getString(R.string.meta_notice_lost, notice.fileName, dir + "/" + notice.fileName,
                    notice.archivePath == null ? getString(R.string.meta_archive_failed) : notice.archivePath);
        }
        new AppAlertDialogBuilder(this)
                .setTitle(R.string.meta_notice_title)
                .setMessage(msg)
                .setPositiveButton(R.string.confirm, null)
                .show();
    }

    /**
     * 看起来像新装机（没写过库文件、库与壁纸都是空的）就问一句要不要从备份包还原。
     * 只问一次（markPromptShown 落本机 key，且这个 key 不参与还原）；
     * 抽屉里那一行「从备份包还原」永久留着，什么时候想还原都可以。
     */
    void maybePromptRestore() {
        if (BackupStore.promptShown(this) || !BackupStore.looksLikeFreshInstall(this)) {
            return;
        }
        BackupStore.markPromptShown(this);
        new AppAlertDialogBuilder(this)
                .setTitle(R.string.restore_ask_title)
                .setMessage(R.string.restore_ask_msg)
                .setPositiveButton(R.string.restore_ask_go, (dialog, which) ->
                        restoreZipLauncher.launch(new String[]{"application/zip", "*/*"}))
                .setNegativeButton(R.string.restore_ask_no, null)
                .show();
    }

    /** 拿本机那个已下好的包装一遍：没授权先跳授权页并说明，包被系统清理了就刷回「检查更新」。 */
    void installReadyPackage() {
        if (!UpdateDownloader.hasInstallPermission(this)) {
            UpdateDownloader.openInstallPermissionSettings(this);
            Toast.makeText(this, R.string.update_need_install_perm, Toast.LENGTH_LONG).show();
            return;
        }
        if (!UpdateDownloader.install(this)) {
            Toast.makeText(this, R.string.update_pkg_gone, Toast.LENGTH_SHORT).show();
            bindUpdateState();
        }
    }

    /**
     * 下载完成后的提示窗：点「立即安装」走与那一行完全相同的安装链路，
     * 点「稍后安装」或按返回都只是关掉——包还在本机，之后回抽屉点那一行随时能装。
     */
    void showInstallDialog() {
        String version = UpdateDownloader.readyVersion(this);
        if (version == null) {
            return;
        }
        new AppAlertDialogBuilder(this)
                .setTitle(R.string.update_done_title)
                .setMessage(getString(R.string.update_done_msg, version))
                .setPositiveButton(R.string.update_install_now,
                        (d, which) -> installReadyPackage())
                .setNegativeButton(R.string.update_install_later, null)
                .show();
    }

    /** 手动检查：结果只改这一行的文字，发现新版才弹窗。 */
    void checkUpdateNow() {
        String source = UpdateChecker.source(this);
        setUpdateStatus(R.string.update_checking);
        UpdateChecker.checkAsync(this, source, (info, error) -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            if (error != null || info == null) {
                setUpdateStatus(R.string.update_check_failed);
                // 把具体死因亮给用户（网络不通/HTTP 错误一眼可辨），不再只有干巴巴的失败
                Toast.makeText(this, getString(R.string.update_check_failed)
                        + "：" + (error != null ? error.toString() : "无返回"), Toast.LENGTH_LONG).show();
                return;
            }
            if (!UpdateChecker.isNewer(info.version, UpdateChecker.localVersion(this))) {
                setUpdateStatus(R.string.update_latest);
                return;
            }
            setUpdateStatus(getString(R.string.update_new_title) + " v" + info.version);
            showUpdateDialog(info, source);
        });
    }

    /**
     * 新版弹窗。本机已经有这个包了（上回下完没装，重进 App 又查到新版）时主按钮直接给
     * 「立即安装」，并把文案对上本机那个包的版本；否则确认后开始下载。
     */
    void showUpdateDialog(UpdateChecker.Info info, String source) {
        String ready = UpdateDownloader.readyVersion(this);
        // 本机包不比远端那一版旧才走安装，免得文案写 v3.75、装进去的是旧的 v3.74
        boolean installFirst = ready != null && !UpdateChecker.isNewer(info.version, ready);
        new AppAlertDialogBuilder(this)
                .setTitle(R.string.update_new_title)
                .setMessage(getString(installFirst
                                ? R.string.update_new_ready_msg : R.string.update_new_msg,
                        installFirst ? ready : info.version, UpdateChecker.localVersion(this)))
                .setPositiveButton(installFirst ? R.string.update_install_now
                                : R.string.update_download,
                        (d, which) -> {
                            if (installFirst) {
                                installReadyPackage();
                            } else {
                                startUpdateDownload(source, info.version);
                            }
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 记下目标版本后开下：进度条马上转起来，失败时这一行也认得该重下哪个版本。 */
    void startUpdateDownload(String source, String version) {
        lastUpdateSource = source;
        lastUpdateVersion = version;
        UpdateDownloader.start(this, source, version);
        bindUpdateState();
    }

    /** 下载进度轮询：状态一离开下载中就收尾刷行；下完弹小窗问要不要立刻装，失败只 Toast。 */
    private final Runnable updatePoller = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            int state = UpdateDownloader.state(MainActivity.this);
            if (state == UpdateDownloader.DOWNLOADING) {
                bindUpdateState();
                return;
            }
            bindUpdateState();
            if (state == UpdateDownloader.READY) {
                showInstallDialog();
            } else if (state == UpdateDownloader.FAILED) {
                Toast.makeText(MainActivity.this, R.string.update_failed,
                        Toast.LENGTH_SHORT).show();
            }
        }
    };

    /** 确保通知可用：Android 13+ 申请权限；系统级关闭时提示并跳到通知设置页。 */
    void ensureNotificationsEnabled() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifyPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            return;
        }
        if (!SwitchNotifier.canNotify(this)) {
            Toast.makeText(this, R.string.notify_disabled_hint, Toast.LENGTH_LONG).show();
            openNotificationSettings();
        }
    }

    /** 通知权限申请结果：未授权时说明后果（自动切换提示将看不到）。 */
    void onNotifyPermissionResult(boolean granted) {
        if (!granted) {
            Toast.makeText(this, R.string.notify_permission_denied, Toast.LENGTH_LONG).show();
        }
    }

    /** 存档权限申请结果：授权就继续保存当前壁纸，拒绝则放弃。 */
    void onSavePermissionResult(boolean granted) {
        if (granted) {
            doSaveCurrentWallpaper();
        } else {
            Toast.makeText(this, "未授权照片读取，无法保存当前壁纸", Toast.LENGTH_LONG).show();
            refreshTakeoverUi();
        }
    }

    /** 跳转本应用的系统通知设置页（华为/荣耀在此重新允许通知）。 */
    void openNotificationSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(intent);
        } catch (Exception ignored) {
        }
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
     * 整理态点「从本库移除」：只有它可能把图从手机上没掉，所以只有它可能要二次确认，
     * 而且把账算明：几张只挂在这个库里（移出 = 成品图、缩略图、原图一起没），
     * 几张还挂在别的库里（移出 = 这一页不再显示）。
     *
     * <p>预演出「一张都不会删」时直接办、不弹窗 —— 那时候它和移动一样是可逆的（复制回来就完），
     * 拦一道确认只是白让人多点一下。
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
        if (preview[0] == 0) {
            applyOrganizeChange(ORGANIZE_REMOVE, ids, src, null, null);
            return;
        }
        String msg = preview[1] == 0
                ? getString(R.string.organize_remove_msg_all, preview[0])
                : getString(R.string.organize_remove_msg_mixed, preview[0], preview[1]);
        new AppAlertDialogBuilder(this)
                .setTitle(getString(R.string.organize_remove_title, ids.size(), lib.name))
                .setMessage(msg)
                .setPositiveButton(R.string.confirm, (dialog, which) ->
                        applyOrganizeChange(ORGANIZE_REMOVE, ids, src, null, null))
                .setNegativeButton(R.string.cancel, null)
                .show();
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

    /** 库选择列表：第 0 项「不切换」（清空本范围），其余带各自缩略图；当前占位库打勾。 */
    void showLibPickerDialog(final boolean forHome, final View settingsContent) {
        final List<LibraryStore.Library> libs = LibraryStore.load(this);
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
        libAdapter.refreshBadges();
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

    /** 该范围的设置摘要行：模式 · 每 X（卡片与弹窗共用）；回桌面模式的桌面显示触发方式而非间隔。 */
    String slotIntervalDisplay(boolean forHome) {
        boolean random = LibraryStore.MODE_RANDOM.equals(LibraryStore.scopeMode(this, forHome));
        String modeText = getString(random ? R.string.mode_random : R.string.mode_order);
        if (LibraryStore.TRIGGER_RETURN.equals(LibraryStore.scopeTriggerMode(this, forHome))) {
            return modeText + " · " + getString(R.string.trigger_mode_return);
        }
        return modeText + " · " + getString(R.string.slot_every_prefix)
                + formatInterval(LibraryStore.scopeIntervalSeconds(this, forHome));
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
        // 回桌面模式没有定时格子：显示触发方式与上次切换时刻（v3.152）
        if (LibraryStore.TRIGGER_RETURN.equals(LibraryStore.scopeTriggerMode(this, forHome))) {
            long lastReturn = Switcher.lastReturnSwitch(this);
            return lastReturn > 0
                    ? getString(R.string.timer_status_return, formatClock(lastReturn))
                    : getString(R.string.timer_status_return_never);
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
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    /** 检查电池优化白名单：未忽略且未提示过时弹一次引导。 */
    void maybePromptBattery() {
        if (isIgnoringBatteryOptimizations()) {
            return;
        }
        if (prefs.getBoolean("battery_prompted", false)) {
            return;
        }
        prefs.edit().putBoolean("battery_prompted", true).apply();
        new AppAlertDialogBuilder(this)
                .setTitle(R.string.battery_prompt_title)
                .setMessage(R.string.battery_prompt_msg)
                .setPositiveButton(R.string.confirm, (dialog, which) -> requestIgnoreBattery())
                // 荣耀/华为还需在应用详情里开启「自启动/后台运行」
                .setNeutralButton(R.string.app_details, (dialog, which) -> openAppDetails())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** 打开本应用的系统详情页（荣耀/华为在此开启自启动、后台运行白名单）。 */
    void openAppDetails() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception ignored) {
        }
    }

    /** 跳转系统「忽略电池优化」授权页（个别机型不支持该 action 时静默）。 */
    void requestIgnoreBattery() {
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception ignored) {
        }
    }

    /** 打开系统相册多选。 */
    void launchPicker() {
        PickVisualMediaRequest.Builder builder = new PickVisualMediaRequest.Builder();
        builder.setMediaType(ActivityResultContracts.PickVisualMedia.ImageAndVideo.INSTANCE);
        pickLauncher.launch(builder.build());
    }

    /**
     * 多选回调：后台线程逐张复制进收件箱（主线程同步复制多张大图易卡顿、易瞬断失败），
     * 完成后按真实成功/失败数量提示，并打开第一张待编辑项。
     */
    void onPicked(List<Uri> uris) {
        if (uris == null || uris.isEmpty()) {
            return;
        }
        View btnAdd = findViewById(R.id.btn_add);
        btnAdd.setEnabled(false);
        // 已经进了某个库再导入：库就是眼前这个，记到待编辑项上，编辑页不再问
        final String targetLib = libraryOpen ? currentLibId() : null;
        new Thread(() -> {
            // 批量复制跑后台优先级：调度器偏向小核低频，同样的活发热明显低，代价是略慢
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            int success = 0;
            int failed = 0;
            ArrayList<String> imported = new ArrayList<>();
            for (Uri uri : uris) {
                try {
                    imported.add(WallpaperStore.importToInbox(this, uri).id);
                    success++;
                } catch (Exception e) {
                    failed++;
                }
            }
            List<String> pending = WallpaperStore.pendingInbox(this);
            // lambda 捕获要求实际 final：先把计数定稿
            final int okCount = success;
            final int failCount = failed;
            final ArrayList<String> importedIds = imported;
            runOnUiThread(() -> {
                btnAdd.setEnabled(true);
                if (failCount > 0) {
                    if (okCount > 0) {
                        Toast.makeText(this, getString(R.string.import_partial, okCount, failCount),
                                Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, R.string.import_all_failed, Toast.LENGTH_LONG).show();
                    }
                } else {
                    Toast.makeText(this, getString(R.string.import_done, okCount),
                            Toast.LENGTH_SHORT).show();
                }
                if (targetLib != null) {
                    for (String id : importedIds) {
                        WallpaperStore.putPendingTarget(this, id, targetLib);
                    }
                }
                if (!importedIds.isEmpty()) {
                    openEdit(importedIds.get(0));
                } else if (!pending.isEmpty()) {
                    openEdit(pending.get(0));
                }
            });
        }, "inbox-import").start();
    }

    /** 外部导入页交过来的 id 列表；取完把 extra 抹掉，避免旋转重建再消费一次。 */
    private void readSharedImportIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        ArrayList<String> ids = intent.getStringArrayListExtra(ImportActivity.EXTRA_IMPORTED_IDS);
        if (ids == null) {
            return;
        }
        intent.removeExtra(ImportActivity.EXTRA_IMPORTED_IDS);
        if (!ids.isEmpty()) {
            sharedImportIds = ids;
        }
    }

    /**
     * 处理一批外部导入。单张直接进裁剪页；多张先问「逐张取景 / 全部默认导入」。
     * 返回 true 表示这次 onResume 已经被这批导入占用，别再走逐张自动打开的兜底。
     */
    private boolean consumeSharedImport() {
        if (sharedImportIds == null) {
            return false;
        }
        if (bulkChoiceShowing || bulkImportRunning) {
            // 上一批还在收：先留着，下次进前台再处理，别叠两个弹窗
            return true;
        }
        ArrayList<String> ids = sharedImportIds;
        sharedImportIds = null;
        List<String> pending = WallpaperStore.pendingInbox(this);
        ids.retainAll(pending);
        if (ids.isEmpty()) {
            return false;
        }
        if (ids.size() == 1) {
            // 分享进来时人正停在一个库详情里：那个库就是明确目标，不必再问
            String targetLib = libraryOpen ? currentLibId() : null;
            if (targetLib != null) {
                WallpaperStore.putPendingTarget(this, ids.get(0), targetLib);
            }
            openEdit(ids.get(0));
            return true;
        }
        checkAndShowBatchChoice(ids);
        return true;
    }

    /** 批量里夹了 MP4 就不能默认收（要选封面与 3 秒区间），退回现有逐张流程。 */
    private void checkAndShowBatchChoice(ArrayList<String> ids) {
        bulkChoiceShowing = true;
        final String targetLib = libraryOpen ? currentLibId() : null;
        if (targetLib != null) {
            for (String id : ids) {
                WallpaperStore.putPendingTarget(this, id, targetLib);
            }
        }
        final Context app = getApplicationContext();
        new Thread(() -> {
            boolean video = false;
            for (String id : ids) {
                try {
                    if (LivePhotoFiles.isMp4(WallpaperStore.getInboxFile(app, id))) {
                        video = true;
                        break;
                    }
                } catch (Exception ignored) {
                }
            }
            final boolean hasVideo = video;
            runOnUiThread(() -> {
                bulkChoiceShowing = false;
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (hasVideo) {
                    openEdit(ids.get(0));
                    return;
                }
                showBatchChoice(ids, targetLib);
            });
        }, "import-batch-check").start();
    }

    private void showBatchChoice(ArrayList<String> ids, String targetLib) {
        bulkChoiceShowing = true;
        new AppAlertDialogBuilder(this)
                .setTitle(getString(R.string.import_batch_title, ids.size()))
                .setMessage(R.string.import_batch_message)
                .setPositiveButton(R.string.import_batch_all_default, (dialog, which) -> {
                    if (targetLib != null) {
                        // 人就在这个库里分享进来的：批量默认直接用这个库
                        bulkChoiceShowing = false;
                        confirmPendingDefaults(ids, targetLib);
                        return;
                    }
                    // 批量默认没有取景这一步；库仍然让用户自己选，取消就退回这个选择
                    LibChooser.show(this, getString(R.string.import_pick_lib_title),
                            libId -> {
                                bulkChoiceShowing = false;
                                confirmPendingDefaults(ids, libId);
                            },
                            () -> {
                                if (!isFinishing() && !isDestroyed()) {
                                    showBatchChoice(ids, null);
                                }
                            });
                })
                .setNegativeButton(R.string.import_batch_one_by_one, (dialog, which) -> {
                    bulkChoiceShowing = false;
                    openEdit(ids.get(0));
                })
                .setCancelable(false)
                .show();
    }

    /**
     * 全部按默认取景导入：先按裁剪页 fit-cover 的同一几何从原图中心裁一块（窗口比例），再走
     * confirmImport；这样详情页 fitCenter 看到的也是上屏那块，不会露出整张原图。裁完把矩形写回
     * src_w/crop_*，下次「调整取景」恢复到同一构图。目标库由选库弹窗传进来，这里不再回退
     * 最近操作的库。
     */
    private void confirmPendingDefaults(ArrayList<String> ids, String chosenLibId) {
        final String libId = chosenLibId;
        final Context app = getApplicationContext();
        final ArrayList<String> batch = new ArrayList<>(ids);
        final int count = batch.size();
        final int[] window = windowSize();
        final int maxDim = WallpaperStore.maxWallpaperDim(app);
        // 这批已经由用户选过库：逐条记上，后面失败/停止再打开时不再重复问
        for (String id : batch) {
            WallpaperStore.putPendingTarget(app, id, libId);
        }
        bulkStopRequested = false;
        showBulkProgress(count);
        bulkImportRunning = true;
        new Thread(() -> {
            // 50 张就是 50 次解码+编码，串行跑后台优先级，别并发抬峰值
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            int ok = 0;
            int fail = 0;
            try {
                for (String id : batch) {
                    if (bulkStopRequested) {
                        break;
                    }
                    File inbox = WallpaperStore.getInboxFile(app, id);
                    BitmapFactory.Options bounds = new BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(inbox.getAbsolutePath(), bounds);
                    Rect region = centerCoverRect(bounds.outWidth, bounds.outHeight,
                            window[0], window[1]);
                    Bitmap cropped = region == null ? null
                            : WallpaperStore.decodeRegion(inbox, region, maxDim);
                    try {
                        WallpaperStore.confirmImport(app, id, cropped, libId);
                        if (cropped != null && region != null) {
                            WallpaperStore.saveCropRect(app, id, bounds.outWidth, bounds.outHeight,
                                    region.left, region.top, region.right, region.bottom);
                        }
                        ok++;
                    } catch (Exception | OutOfMemoryError e) {
                        fail++;
                    } finally {
                        if (cropped != null && !cropped.isRecycled()) {
                            cropped.recycle();
                        }
                    }
                    final int completed = ok + fail;
                    runOnUiThread(() -> {
                        if (bulkProgressText != null && !isFinishing() && !isDestroyed()) {
                            bulkProgressText.setText(getString(
                                    R.string.import_progress_count, completed, count));
                        }
                    });
                }
            } finally {
                bulkImportRunning = false;
            }
            final int okCount = ok;
            final int left = count - ok;
            runOnUiThread(() -> {
                dismissBulkProgress();
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (left == 0) {
                    Toast.makeText(this, getString(R.string.import_batch_done, okCount),
                            Toast.LENGTH_LONG).show();
                } else if (okCount == 0) {
                    Toast.makeText(this, getString(R.string.import_batch_none, left),
                            Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, getString(R.string.import_batch_left, okCount, left),
                            Toast.LENGTH_LONG).show();
                }
                refreshCurrentPage();
            });
        }, "import-batch-confirm").start();
    }

    /** 批量落盘进度：带 N/M 计数和「停止」，停止后当前这张跑完就收手，剩余留在收件箱。 */
    private void showBulkProgress(int total) {
        dismissBulkProgress();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        int pad = (int) (getResources().getDisplayMetrics().density * 20);
        box.setPadding(pad, pad, pad, pad);
        ProgressBar bar = new ProgressBar(this);
        box.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        bulkProgressText = new TextView(this);
        bulkProgressText.setText(getString(R.string.import_progress_count, 0, total));
        bulkProgressText.setPadding(pad / 2, 0, 0, 0);
        bulkProgressText.setTextColor(getColor(R.color.text_primary));
        box.addView(bulkProgressText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        bulkProgress = new AppAlertDialogBuilder(this)
                .setTitle(R.string.import_batch_progress_title)
                .setView(box)
                .setNegativeButton(R.string.import_batch_stop,
                        (dialog, which) -> bulkStopRequested = true)
                .setCancelable(false)
                .create();
        bulkProgress.show();
    }

    private void dismissBulkProgress() {
        if (bulkProgress != null) {
            try {
                bulkProgress.dismiss();
            } catch (Exception ignored) {
            }
            bulkProgress = null;
        }
        bulkProgressText = null;
    }

    /** 当前窗口的实际像素尺寸；裁剪页取景区始终满屏，批量默认也按它算画幅。 */
    @SuppressWarnings("deprecation")
    private int[] windowSize() {
        View decor = getWindow().getDecorView();
        if (decor.getWidth() > 0 && decor.getHeight() > 0) {
            return new int[]{decor.getWidth(), decor.getHeight()};
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Rect bounds = getWindowManager().getCurrentWindowMetrics().getBounds();
            return new int[]{bounds.width(), bounds.height()};
        }
        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        return new int[]{dm.widthPixels, dm.heightPixels};
    }

    /**
     * 与 {@link CropView} 的 fit-cover 默认构图同一几何：原图居中、按窗口比例取一块。
     * 原图更宽就左右裁，更高就上下裁；返回原图像素坐标。
     */
    private static Rect centerCoverRect(int srcW, int srcH, int viewW, int viewH) {
        if (srcW <= 0 || srcH <= 0 || viewW <= 0 || viewH <= 0) {
            return null;
        }
        if ((long) srcW * viewH > (long) viewW * srcH) {
            int w = Math.min(srcW, Math.max(1, Math.round(srcH * (float) viewW / viewH)));
            int left = (srcW - w) / 2;
            return new Rect(left, 0, left + w, srcH);
        }
        int h = Math.min(srcH, Math.max(1, Math.round(srcW * (float) viewH / viewW)));
        int top = (srcH - h) / 2;
        return new Rect(0, top, srcW, top + h);
    }

    /** 进程重启后收件箱也走同一分流，避免 MP4 被当成图片解码后删掉。 */
    void openEdit(String inboxId) {
        if (openingInboxId != null) {
            return;
        }
        openingInboxId = inboxId;
        new Thread(() -> {
            boolean video;
            try {
                video = LivePhotoFiles.isMp4(WallpaperStore.getInboxFile(this, inboxId));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    openingInboxId = null;
                    WallpaperStore.cancelImport(this, inboxId);
                    if (!isFinishing() && !isDestroyed()) {
                        Toast.makeText(this, R.string.url_download_failed, Toast.LENGTH_LONG).show();
                    }
                });
                return;
            }
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()
                        || !getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                    openingInboxId = null;
                    return;
                }
                if (!video) {
                    openingInboxId = null;
                    launchInboxEditor(inboxId, false);
                    return;
                }
                new AppAlertDialogBuilder(this)
                        .setTitle(R.string.video_detected_title)
                        .setMessage(R.string.video_detected_message)
                        .setNegativeButton(R.string.video_no, (dialog, which) -> cancelVideoInbox(inboxId))
                        .setPositiveButton(R.string.video_yes, (dialog, which) -> {
                            openingInboxId = null;
                            launchInboxEditor(inboxId, true);
                        })
                        .setOnCancelListener(dialog -> cancelVideoInbox(inboxId))
                        .show();
            });
        }, "inbox-type").start();
    }

    void cancelVideoInbox(String inboxId) {
        WallpaperStore.cancelImport(this, inboxId);
        openingInboxId = null;
        List<String> pending = WallpaperStore.pendingInbox(this);
        if (!pending.isEmpty()) {
            openEdit(pending.get(0));
        }
    }

    void launchInboxEditor(String inboxId, boolean video) {
        Intent intent = new Intent(this, video ? VideoTrimActivity.class : EditActivity.class);
        intent.putExtra(EditActivity.EXTRA_INBOX_ID, inboxId);
        startActivity(intent);
    }

    /**
     * 图片直链输入框：粘贴（不关窗，贴上还能接着改）/ 确认（校验 http(s) 前缀，不过就留着窗）/ 取消。
     * 三颗按钮排「粘贴 / 取消 / 确认」—— 粘贴离输入框动作最近，确认在最右拇指落点。
     */
    void showLinkInputDialog() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        AlertDialog dialog = new AppAlertDialogBuilder(this)
                .setTitle(R.string.url_dialog_title)
                .setView(wrapInput(input, R.string.url_input_hint))
                .setNeutralButton(R.string.paste, null)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.confirm, null)
                .create();
        // 带回调的按钮默认一点就关窗：粘贴必须留着窗才能看到贴进去的内容，校验不过也不该白关一次
        dialog.setOnShowListener(d -> {
            Button paste = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            Button ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            paste.setOnClickListener(v -> pasteClipboardInto(input));
            ok.setOnClickListener(v -> {
                String url = input.getText().toString().trim();
                if (!(url.startsWith("http://") || url.startsWith("https://"))) {
                    Toast.makeText(this, R.string.url_invalid, Toast.LENGTH_LONG).show();
                    return;
                }
                dialog.dismiss();
                downloadFromUrl(url);
            });
        });
        dialog.show();
        input.requestFocus();
    }

    /** 把剪贴板文本贴进输入框；剪贴板是空的只提示，不动输入框内容。 */
    void pasteClipboardInto(EditText input) {
        try {
            ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = manager == null ? null : manager.getPrimaryClip();
            CharSequence text = (clip == null || clip.getItemCount() == 0)
                    ? null : clip.getItemAt(0).getText();
            if (text == null || text.toString().trim().isEmpty()) {
                Toast.makeText(this, R.string.url_paste_empty, Toast.LENGTH_SHORT).show();
                return;
            }
            input.setText(text.toString().trim());
            input.setSelection(input.getText().length());
        } catch (Exception ignored) {
        }
    }

    /** 下载只写收件箱，入库仍由用户在编辑页确认。 */
    void downloadFromUrl(String url) {
        View btnAdd = findViewById(R.id.btn_add);
        btnAdd.setEnabled(false);
        // 从库里发起：这条就落到眼前这个库
        final String targetLib = libraryOpen ? currentLibId() : null;
        new Thread(() -> {
            String id = null;
            try {
                id = WallpaperStore.importUrlToInbox(this, url).id;
            } catch (Exception ignored) {
            }
            final String inboxId = id;
            runOnUiThread(() -> {
                btnAdd.setEnabled(true);
                if (inboxId == null) {
                    Toast.makeText(this, R.string.url_download_failed, Toast.LENGTH_LONG).show();
                    return;
                }
                Toast.makeText(this, R.string.url_download_done, Toast.LENGTH_SHORT).show();
                if (targetLib != null) {
                    WallpaperStore.putPendingTarget(this, inboxId, targetLib);
                }
                openEdit(inboxId);
            });
        }, "url-download").start();
    }

    /**
     * 当值闸门：这张正被某个库的桌面/锁屏当值，或被「一键设置」钉着时，重编与彻底删除都不放行。
     * 弹一句「正在应用：桌面、锁屏、一键设置」，只给一颗「知道了」。
     *
     * <p>刻意不留「我就要改」那颗出口：留了就等于没拦，而这条限制要保的正是
     * 「屏幕上那张 == 库里那张」这件事。怎么处理（切一张当值、改选图钉那张）不在这里教，
     * 弹窗只报原因 —— 文案一长就没人读。
     *
     * <p>整理态的<b>解除关联</b>与移动/复制不走这里 —— 它们只改归属表，一个字节都不碰。
     */
    boolean blockWhileInUse(WallpaperStore.Item item) {
        String where = Switcher.inUseWhere(this, item.id);
        if (where == null) {
            return false;
        }
        new AppAlertDialogBuilder(this)
                .setTitle(R.string.in_use_title)
                .setMessage(getString(R.string.in_use_msg, where))
                .setPositiveButton(R.string.in_use_ok, null)
                .show();
        return true;
    }

    /** 打开编辑页重新裁剪一张已入库壁纸（点壁纸格直接进入；确认后覆盖成品图，源是原图）。 */
    void openEditItem(WallpaperStore.Item item) {
        if (blockWhileInUse(item)) {
            return;
        }
        Intent intent = new Intent(this, EditActivity.class);
        intent.putExtra(EditActivity.EXTRA_ITEM_ID, item.id);
        // 从哪个库点进来的也带上：那张挂在几个库里时，编辑页要问「只改这个库」还是「同步修改」，
        // 分叉出来的新条目得有个明确归属的库（没有它就只能一律覆盖）
        String libId = itemActionLibId();
        if (libId != null) {
            intent.putExtra(EditActivity.EXTRA_LIB_ID, libId);
        }
        startActivity(intent);
    }

    /** 关闭接管的唯一途径说明（点感叹号图标时弹）。 */
    void showTakeoverInfoDialog() {
        new AppAlertDialogBuilder(this)
                .setTitle(R.string.takeover_info_title)
                .setMessage(R.string.takeover_info_msg)
                .setPositiveButton(R.string.takeover_info_open_settings, (d, which) -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_SET_WALLPAPER));
                    } catch (Exception ignored) {
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * 启用：直接拉起系统动态壁纸选择器（本应用没有"关闭"入口，所以不需要任何存档步骤；
     * 要备份系统壁纸请用抽屉里的「保存当前壁纸」）。
     */
    void enableTakeover() {
        WallSwitchService.openActivator(this);
    }

    /**
     * 抽屉「保存当前壁纸」入口：接管中不需要权限（图就在库里），
     * 未接管要读系统壁纸，申请照片读取权限后直接读（旧版「所有文件访问」链路 v3.127 撤掉）。
     */
    void startSaveCurrentWallpaper() {
        if (TakeoverManager.isHomeTakenOver(this)) {
            doSaveCurrentWallpaper();
            return;
        }
        // Android 13+ 用照片权限（READ_MEDIA_IMAGES），更低版本对应 READ_EXTERNAL_STORAGE。
        // 个别 ROM（v3.28 那台 MagicOS）照片权限折算不成 WallpaperManager 认的
        // READ_EXTERNAL_STORAGE 会读不到 —— 读不到时 readWallpaperBitmap 的双路死因
        // 会跟着保存失败的 Toast 带回来，到底行不行交给这次真机实测。
        String perm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.READ_MEDIA_IMAGES
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
            savePermLauncher.launch(perm);
            return;
        }
        doSaveCurrentWallpaper();
    }

    /** 后台执行保存，全程盖不可关闭的进度弹窗，结果用 Toast 告知。 */
    void doSaveCurrentWallpaper() {
        showProgress(R.string.save_wallpaper_title, R.string.save_wallpaper_working);
        new Thread(() -> {
            final String fail = TakeoverManager.saveCurrentWallpaper(this);
            runOnUiThread(() -> {
                dismissTakeoverProgress();
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                CharSequence msg = fail.isEmpty()
                        ? getString(R.string.save_wallpaper_done)
                        : getString(R.string.save_wallpaper_failed, fail);
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            });
        }, "save-wallpaper").start();
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
     * 把接管落地一次（对齐当前启用库：锁屏库 → 设锁屏；桌面有库但引擎没激活 → 提示去启用）。
     * 放后台线程，避免系统调用卡 UI。
     */
    void syncTakeoverAsync() {
        new Thread(() -> {
            final int result = TakeoverManager.apply(this);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (result == TakeoverManager.RESULT_NEED_ACTIVATION) {
                    Toast.makeText(this, R.string.takeover_need_activate, Toast.LENGTH_LONG).show();
                }
                refreshTakeoverUi();
            });
        }, "takeover-sync").start();
    }

    /**
     * 设置页「视频转 Live」入口：挑一段视频交裁剪页。
     * 这一档不限片段长度，产出直接写相册（Pictures/WallSwitch），不进壁纸库、也不占收件箱。
     */
    void pickLiveConverterVideo() {
        PickVisualMediaRequest.Builder builder = new PickVisualMediaRequest.Builder();
        builder.setMediaType(ActivityResultContracts.PickVisualMedia.VideoOnly.INSTANCE);
        liveVideoLauncher.launch(builder.build());
    }

    /** 视频到手就开裁剪页；读权限跟着这次结果走，所以裁剪页第一步先把整段复制进缓存。 */
    void onLiveVideoPicked(Uri uri) {
        if (uri == null) {
            return;
        }
        Intent intent = new Intent(this, VideoTrimActivity.class);
        intent.putExtra(VideoTrimActivity.EXTRA_SOURCE_URI, uri);
        startActivity(intent);
    }

    /** 选一张首页截图作为全局「桌面图标预览底图」（抠图后存应用目录，之后不用再选）。 */
    void pickLauncherOverlay() {
        PickVisualMediaRequest.Builder builder = new PickVisualMediaRequest.Builder();
        builder.setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE);
        overlayLauncher.launch(builder.build());
    }

    /** 选好后的处理：后台抠图（把截图自带的壁纸变透明）→ 存成全局底图 → 回显状态。 */
    void onOverlayPicked(Uri uri) {
        if (uri == null) {
            overlayPickForDetail = false;
            return;
        }
        Toast.makeText(this, R.string.launcher_overlay_processing, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final Bitmap keyed = LauncherPreviewOverlay.keyedFromUri(this, uri);
            final boolean saved = keyed != null && LauncherPreviewOverlay.save(this, keyed);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                Toast.makeText(this,
                        saved ? R.string.launcher_overlay_saved : R.string.launcher_overlay_failed,
                        Toast.LENGTH_SHORT).show();
                refreshLauncherOverlayRow();
                // 是在单张详情里单击画面触发的选图：选好就直接把预览打开
                boolean forDetail = overlayPickForDetail;
                overlayPickForDetail = false;
                if (forDetail && keyed != null && pageDetail.getVisibility() == View.VISIBLE) {
                    detailOverlay.setScreenshot(keyed);
                    enterDetailPreview();
                }
            });
        }, "overlay-key").start();
    }

    /** 清除全局预览底图（抽屉里长按）。 */
    void confirmClearLauncherOverlay() {
        if (!LauncherPreviewOverlay.overlayFile(this).exists()) {
            Toast.makeText(this, R.string.launcher_overlay_unset, Toast.LENGTH_SHORT).show();
            return;
        }
        new AppAlertDialogBuilder(this)
                .setTitle(R.string.launcher_overlay_title)
                .setMessage(R.string.launcher_overlay_clear_msg)
                .setPositiveButton(R.string.confirm, (dialog, which) -> {
                    LauncherPreviewOverlay.clear(MainActivity.this);
                    refreshLauncherOverlayRow();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** 已设置底图后点按抽屉行：浮出查看/更换/清除菜单（长按仍是清除快捷方式）。 */
    void showLauncherOverlayMenu() {
        new AppAlertDialogBuilder(this)
                .setTitle(R.string.launcher_overlay_title)
                .setItems(new CharSequence[]{
                        getString(R.string.launcher_overlay_action_view),
                        getString(R.string.launcher_overlay_action_change),
                        getString(R.string.launcher_overlay_action_clear)},
                        (dialog, which) -> {
                            if (which == 0) {
                                showLauncherOverlayPreview();
                            } else if (which == 1) {
                                pickLauncherOverlay();
                            } else {
                                confirmClearLauncherOverlay();
                            }
                        })
                .show();
    }

    /** 预览对话框：在棋盘格衬底上展示抠好的透明底图，透明区域一眼可见。 */
    void showLauncherOverlayPreview() {
        ImageView image = new ImageView(this);
        image.setLayoutParams(new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (280 * getResources().getDisplayMetrics().density)));
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setPadding((int) (16 * getResources().getDisplayMetrics().density),
                (int) (16 * getResources().getDisplayMetrics().density),
                (int) (16 * getResources().getDisplayMetrics().density),
                (int) (16 * getResources().getDisplayMetrics().density));
        image.setBackground(checkerboard());
        ScrollView scroll = new ScrollView(this);
        scroll.addView(image);
        AlertDialog dialog = new AppAlertDialogBuilder(this)
                .setTitle(R.string.launcher_overlay_title)
                .setView(scroll)
                .setNegativeButton(R.string.close, null)
                .show();
        new Thread(() -> {
            final Bitmap saved = LauncherPreviewOverlay.loadSaved(this);
            runOnUiThread(() -> {
                if (saved == null) {
                    dialog.dismiss();
                    Toast.makeText(this, R.string.launcher_overlay_load_failed, Toast.LENGTH_SHORT).show();
                    return;
                }
                image.setImageBitmap(saved);
            });
        }, "overlay-preview").start();
    }

    /** 灰白棋盘格衬底（2×2 平铺单元），用来显出底图的透明区域。 */
    private Drawable checkerboard() {
        float cell = 12 * getResources().getDisplayMetrics().density;
        int size = (int) (cell * 2);
        Bitmap tile = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(tile);
        canvas.drawColor(0xFFF2F2F2);
        Paint gray = new Paint();
        gray.setColor(0xFFD9D9D9);
        canvas.drawRect(0, 0, cell, cell, gray);
        canvas.drawRect(cell, cell, size, size, gray);
        BitmapDrawable drawable = new BitmapDrawable(getResources(), tile);
        drawable.setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
        return drawable;
    }

    /** 选一个导出目录（SAF）：选完记住并申请持久化授权。 */
    void onExportDirPicked(Uri uri) {
        if (uri == null) {
            return;
        }
        WallpaperExporter.setTreeUri(this, uri);
        refreshExportRows();
        Toast.makeText(this, R.string.export_dir_set, Toast.LENGTH_SHORT).show();
    }

    /** 取消导出目录（不清除已经导出的文件）。 */
    void confirmClearExportDir() {
        if (!WallpaperExporter.isConfigured(this)) {
            Toast.makeText(this, R.string.export_dir_unset, Toast.LENGTH_SHORT).show();
            return;
        }
        new AppAlertDialogBuilder(this)
                .setTitle(R.string.export_dir_title)
                .setMessage(R.string.export_dir_clear_msg)
                .setPositiveButton(R.string.confirm, (dialog, which) -> {
                    WallpaperExporter.clear(MainActivity.this);
                    refreshExportRows();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** 把库内全部壁纸导出到该目录（纯 IO，放后台线程）。 */
    void exportAllWallpapers() {
        if (!WallpaperExporter.isConfigured(this)) {
            Toast.makeText(this, R.string.export_need_dir, Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, R.string.export_running, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final int count = WallpaperExporter.exportAll(this);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                Toast.makeText(MainActivity.this, getString(R.string.export_done, count),
                        Toast.LENGTH_LONG).show();
            });
        }, "wallpaper-export-all").start();
    }

    /** 备份整库：一个 zip 装下图、缩略图、两个 json 和 prefs，写进导出目录。 */
    void startBackup() {
        if (!WallpaperExporter.isConfigured(this)) {
            Toast.makeText(this, R.string.export_need_dir, Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, R.string.backup_running, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final BackupStore.BackupResult r = BackupStore.backup(getApplicationContext());
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (r.error != null) {
                    Toast.makeText(this, getString(R.string.backup_failed, r.error),
                            Toast.LENGTH_LONG).show();
                    return;
                }
                prefs.edit().putString("backup_last_name", r.name)
                        .putLong("backup_last_at", System.currentTimeMillis()).apply();
                refreshBackupRow();
                Toast.makeText(this, getString(R.string.backup_done, r.name, r.entries,
                        r.images, r.originals, r.thumbs), Toast.LENGTH_LONG).show();
            });
        }, "lib-backup").start();
    }

    /** 选中备份包后先回显现场，确认了才动本机数据。 */
    void onRestoreZipPicked(Uri uri) {
        if (uri == null) {
            return;
        }
        showProgress(R.string.restore_title, R.string.restore_reading);
        new Thread(() -> {
            final BackupStore.Manifest m = BackupStore.inspect(getApplicationContext(), uri);
            runOnUiThread(() -> {
                dismissTakeoverProgress();
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (m == null) {
                    Toast.makeText(this, R.string.restore_bad_zip, Toast.LENGTH_LONG).show();
                    return;
                }
                confirmRestore(uri, m);
            });
        }, "backup-inspect").start();
    }

    void confirmRestore(Uri uri, BackupStore.Manifest m) {
        int local = WallpaperStore.load(this).size();
        new AppAlertDialogBuilder(this)
                .setTitle(R.string.restore_title)
                .setMessage(getString(R.string.restore_confirm,
                        m.backupTime == null || m.backupTime.isEmpty() ? "未知" : m.backupTime,
                        m.appVersion == null || m.appVersion.isEmpty() ? "未知" : m.appVersion,
                        m.libraryItems, m.images, m.originals, m.thumbs, m.bytes / (1024 * 1024), local))
                .setPositiveButton(R.string.restore_go, (dialog, which) -> runRestore(uri))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /**
     * 执行还原。还原后不必自己重排定时：onResume 本来就会 scheduleAll + 补切各走一遍
     * （自愈那条路），recreate 之后正好落到它上面。
     */
    void runRestore(Uri uri) {
        clearImages();
        Toast.makeText(this, R.string.restore_running, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final BackupStore.RestoreResult r = BackupStore.restore(getApplicationContext(), uri);
            // 还原是整批覆盖成品图文件，而且包里的 id 可能跟本机同名 —— 格式标记只能全抹，
            // 否则网格角标会继续报还原前那一档
            WallpaperStore.clearFullFormatMarks();
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                clearImages();
                if (r.error != null) {
                    refreshCurrentPage();
                    Toast.makeText(this, getString(R.string.restore_failed, r.error),
                            Toast.LENGTH_LONG).show();
                    return;
                }
                Toast.makeText(this, getString(R.string.restore_done, r.items, r.prefsKeys,
                        r.images, r.droppedItems), Toast.LENGTH_LONG).show();
                recreate();
            });
        }, "lib-restore").start();
    }

    /**
     * 清空日志的确认窗（抽屉里长按那一行、日志弹窗里点「清空」都走这一处）：两面一起删，
     * 日志删了不可回，所以这一发要拦；老格式记录不会自动改写，留着一堆旧格式照样难看。
     *
     * @param logDialog 从日志弹窗点进来时传那个窗：确认清了顺手关掉它（里面显示的已经是没了的旧账）；
     *                  在确认窗按取消则什么都不动，日志窗还开着
     */
    void confirmClearSwitchLog(AlertDialog logDialog) {
        new AppAlertDialogBuilder(this)
                .setTitle(R.string.log_clear_title)
                .setMessage(R.string.log_clear_msg)
                .setPositiveButton(R.string.confirm, (dialog, which) -> new Thread(() -> {
                    SwitchLog.clear(getApplicationContext());
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        if (logDialog != null && logDialog.isShowing()) {
                            logDialog.dismiss();
                        }
                        logPageToken++;
                        logContents[0] = logContents[1] = "";
                        logDisplayed[0] = logDisplayed[1] = "";
                        logRefs = new HashMap<>();
                        logToolbar.getMenu().findItem(R.id.action_log_copy).setEnabled(false);
                        if (logOpen) {
                            showSwitchLog();
                        }
                        Toast.makeText(this, R.string.log_clear_done, Toast.LENGTH_SHORT).show();
                        refreshSwitchLogRow();
                    });
                }, "log-clear").start())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** 独立切换记录页的静态控件只绑一次；正文数据每次打开时后台现读。 */
    private void setupSwitchLogPage() {
        logToolbar = findViewById(R.id.toolbar_log);
        logRecycler = findViewById(R.id.recycler_log);
        logScopeToggle = findViewById(R.id.log_scope_toggle);
        logOrderButton = findViewById(R.id.btn_log_order);
        logJumpButton = findViewById(R.id.btn_log_jump);
        logAdapter = new LogCardAdapter();
        logRecycler.setLayoutManager(new LinearLayoutManager(this));
        logRecycler.setAdapter(logAdapter);
        logRecycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (rv.getScrollState() == RecyclerView.SCROLL_STATE_DRAGGING && dy != 0) {
                    // 上滑是在往下看，给“到底”；下滑是在往上翻，给“到顶”
                    showLogJump(dy < 0);
                }
            }
        });
        logJumpButton.setOnClickListener(v -> {
            if (logAdapter == null) {
                return;
            }
            int target = logJumpToTop ? 0 : Math.max(0, logAdapter.getItemCount() - 1);
            logRecycler.smoothScrollToPosition(target);
            logJumpButton.setVisibility(View.GONE);
        });
        logToolbar.setNavigationOnClickListener(v -> closeSwitchLogPage());
        logToolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_log_copy) {
                copySwitchLog(logDisplayed[logScope]);
            } else if (item.getItemId() == R.id.action_log_clear) {
                confirmClearSwitchLog(null);
            }
            return true;
        });
        logScopeToggle.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked || bindingLogControls || !logOpen) {
                return;
            }
            logScope = checkedId == R.id.log_scope_lock ? 1 : 0;
            bindSwitchLogPage();
        });
        // 一个按钮点按切换：文字显示当前顺序，图标用上下双箭头
        logOrderButton.setOnClickListener(v -> {
            if (!logOpen) return;
            logNewestFirst = !logNewestFirst;
            SwitchLog.setNewestFirst(this, logNewestFirst);
            updateLogOrderButton();
            bindSwitchLogPage();
        });
    }

    private void updateLogOrderButton() {
        if (logOrderButton == null) {
            return;
        }
        logOrderButton.setText(logNewestFirst ? R.string.log_order_desc : R.string.log_order_asc);
        logOrderButton.setContentDescription(getString(R.string.log_order_toggle_desc,
                getString(logNewestFirst ? R.string.log_order_desc : R.string.log_order_asc)));
    }

    private void showLogJump(boolean toTop) {
        if (logJumpButton == null || logAdapter == null
                || !(logRecycler.getLayoutManager() instanceof LinearLayoutManager)) {
            return;
        }
        LinearLayoutManager lm = (LinearLayoutManager) logRecycler.getLayoutManager();
        int count = logAdapter.getItemCount();
        if (count <= 0
                || (toTop && lm.findFirstVisibleItemPosition() <= 0)
                || (!toTop && lm.findLastVisibleItemPosition() >= count - 1)) {
            logJumpButton.setVisibility(View.GONE);
            return;
        }
        logJumpToTop = toTop;
        logJumpButton.setImageResource(toTop ? R.drawable.ic_arrow_upward : R.drawable.ic_arrow_downward);
        logJumpButton.setContentDescription(getString(toTop
                ? R.string.log_jump_top : R.string.log_jump_bottom));
        logJumpButton.setVisibility(View.VISIBLE);
    }

    /** 后台构造两范围的显示快照；只有字符串/模型，没有 View 操作。 */
    private static LogSnapshot prepareLogSnapshot(Context app, String[] contents,
            Map<String, List<SwitchLog.EntryRef>> refs, boolean newestFirst) {
        String[] displayed = new String[2];
        List<List<LogRow>> rows = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            displayed[i] = SwitchLog.stripRefs(SwitchLog.reorder(contents[i], newestFirst));
            rows.add(buildLogRows(app, displayed[i], refs));
        }
        return new LogSnapshot(contents, displayed, rows, refs,
                !contents[0].trim().isEmpty() || !contents[1].trim().isEmpty());
    }

    private void openSwitchLogPage(LogSnapshot snapshot) {
        logOpen = true;
        bindingLogControls = true;
        logScopeToggle.check(logScope == 0 ? R.id.log_scope_home : R.id.log_scope_lock);
        bindingLogControls = false;
        updateLogOrderButton();
        submitSwitchLogPage(snapshot);
        motion.show(pageLog, logAdapter::prepareVisible);
        logAdapter.rebindVisible();
    }

    private void setLogLoading() {
        unbindImageTree(logRecycler);
        logRecycler.setVisibility(View.INVISIBLE);
        logJumpButton.setVisibility(View.GONE);
        logToolbar.getMenu().findItem(R.id.action_log_copy).setEnabled(false);
    }

    private void bindSwitchLogPage() {
        if (!logOpen) return;
        final int request = ++logPageToken;
        final int scope = logScope;
        final boolean newestFirst = logNewestFirst;
        final String[] contents = logContents.clone();
        final Map<String, List<SwitchLog.EntryRef>> refs = logRefs;
        final Context app = getApplicationContext();
        setLogLoading();
        imageLoader.execute(() -> {
            LogSnapshot snapshot = prepareLogSnapshot(app, contents, refs, newestFirst);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || !logOpen || request != logPageToken
                        || scope != logScope || newestFirst != logNewestFirst) return;
                motion.whenIdle("log-snapshot", () -> {
                    if (!isFinishing() && !isDestroyed() && logOpen && request == logPageToken
                            && scope == logScope && newestFirst == logNewestFirst) {
                        submitSwitchLogPage(snapshot);
                    }
                });
            });
        });
    }

    private void submitSwitchLogPage(LogSnapshot snapshot) {
        System.arraycopy(snapshot.contents, 0, logContents, 0, 2);
        System.arraycopy(snapshot.displayed, 0, logDisplayed, 0, 2);
        logRefs = snapshot.refs;
        logAdapter.setRows(snapshot.rows.get(logScope));
        logRecycler.setVisibility(View.VISIBLE);
        int latest = logNewestFirst ? 0 : Math.max(0, logAdapter.getItemCount() - 1);
        logRecycler.scrollToPosition(latest);
        logJumpButton.setVisibility(View.GONE);
        Menu menu = logToolbar.getMenu();
        menu.findItem(R.id.action_log_copy).setEnabled(!logDisplayed[logScope].trim().isEmpty());
        menu.findItem(R.id.action_log_clear).setEnabled(snapshot.hasAny);
    }

    private void closeSwitchLogPage() {
        logOpen = false;
        logPageToken++;
        unbindImageTree(pageLog);
        motion.hide(pageLog);
        logJumpButton.setVisibility(View.GONE);
    }

    /** 日志读盘、排序、剥离 refs 和建模都完成后才打开第一页。 */
    void showSwitchLog() {
        final int request = ++logPageToken;
        if (!logOpen) {
            logScope = 0;
            logNewestFirst = SwitchLog.newestFirst(this);
        } else {
            setLogLoading();
        }
        final int scope = logScope;
        final boolean newestFirst = logNewestFirst;
        final Context app = getApplicationContext();
        new Thread(() -> {
            final String home = SwitchLog.readAllText(app, true);
            final String lock = SwitchLog.readAllText(app, false);
            final Map<String, List<SwitchLog.EntryRef>> refs = SwitchLog.refIndex(home, lock);
            final LogSnapshot snapshot = prepareLogSnapshot(app, new String[]{home, lock}, refs, newestFirst);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || request != logPageToken
                        || scope != logScope || newestFirst != logNewestFirst) return;
                motion.whenIdle("log-snapshot", () -> {
                    if (isFinishing() || isDestroyed() || request != logPageToken
                            || scope != logScope || newestFirst != logNewestFirst) return;
                    openSwitchLogPage(snapshot);
                });
            });
        }, "log-read").start();
    }

    /** 卡片日志的一行：段头 / 日期 / 一条带边框的记录。文件本身仍是原来的纯文本格式，只改显示。 */
    private static final class LogRow {
        static final int HEADER = 0;
        static final int DATE = 1;
        static final int ENTRY = 2;
        static final int CONFIG = 3;
        static final int EMPTY = 4;

        int type;
        String time = "";
        String title = "";
        String subtitle = "";
        String meta = "";
        String kind = "";
        /** 这条记录切到的那张的 id（行尾隐藏段第一把）；老行没有，卡片就不画小缩略图。 */
        String thumbId;
        SwitchLog.RecordLine record;
        List<SwitchLog.EntryRef> refs;
    }

    /**
     * 新版日志窗：文件仍逐行追加，显示时拆成「版本段 → 日期 → 一条一张描边卡」。
     * 时间固定在左边、主要动作/壁纸标题在右边，库与范围、间隔与误差各自一层，不再让一整段等宽文本
     * 自己折行。壁纸记录卡仍可点开缩略图；复制仍复制屏上这面、按当前顺序排好的纯文本。
     */
    void showSwitchLogCardsDialog(String home, String lock,
                                  Map<String, List<SwitchLog.EntryRef>> refs) {
        final String[] contents = {home == null ? "" : home, lock == null ? "" : lock};
        final String[] displayed = new String[2];
        final int[] shown = {0};
        final boolean[] newestFirst = {SwitchLog.newestFirst(this)};
        final boolean empty = contents[0].trim().isEmpty() && contents[1].trim().isEmpty();
        final int pad = dp(16);

        RecyclerView list = new RecyclerView(this);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setClipToPadding(false);
        list.setPadding(pad, dp(4), pad, dp(16));
        LogCardAdapter adapter = new LogCardAdapter();
        list.setAdapter(adapter);

        TabLayout tabs = new TabLayout(this);
        tabs.addTab(tabs.newTab().setText(R.string.log_tab_home));
        tabs.addTab(tabs.newTab().setText(R.string.log_tab_lock));

        TextView tvOrder = new TextView(this);
        tvOrder.setText(R.string.log_view_order);
        tvOrder.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        CompoundButton swOrder = new MaterialSwitch(this);
        swOrder.setChecked(newestFirst[0]);
        LinearLayout orderRow = new LinearLayout(this);
        orderRow.setOrientation(LinearLayout.HORIZONTAL);
        orderRow.setGravity(Gravity.CENTER_VERTICAL);
        orderRow.setPadding(pad, dp(4), pad, dp(4));
        orderRow.addView(tvOrder, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        orderRow.addView(swOrder);

        Runnable rebind = () -> {
            for (int i = 0; i < 2; i++) {
                displayed[i] = SwitchLog.stripRefs(
                        SwitchLog.reorder(contents[i], newestFirst[0]));
            }
            adapter.setRows(buildLogRows(getApplicationContext(), displayed[shown[0]], refs));
            list.scrollToPosition(0);
        };
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                shown[0] = tab.getPosition();
                adapter.setRows(buildLogRows(getApplicationContext(), displayed[shown[0]], refs));
                list.scrollToPosition(0);
            }

            @Override public void onTabUnselected(TabLayout.Tab tab) { }
            @Override public void onTabReselected(TabLayout.Tab tab) { list.scrollToPosition(0); }
        });
        swOrder.setOnCheckedChangeListener((buttonView, checked) -> {
            newestFirst[0] = checked;
            SwitchLog.setNewestFirst(MainActivity.this, checked);
            rebind.run();
        });

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (getResources().getDisplayMetrics().heightPixels * 0.66f)));
        column.addView(tabs);
        column.addView(orderRow);
        column.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        rebind.run();

        AlertDialog.Builder builder = new AppAlertDialogBuilder(this)
                .setTitle(R.string.log_view_title)
                .setView(column)
                .setNegativeButton(R.string.cancel, null);
        if (!empty) {
            builder.setNeutralButton(R.string.log_view_copy,
                    (dialog, which) -> copySwitchLog(displayed[shown[0]]));
            builder.setPositiveButton(R.string.log_view_clear, null);
        }
        AlertDialog dialog = builder.show();
        adapter.logDialog = dialog;
        if (!empty) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener(v -> confirmClearSwitchLog(dialog));
        }
    }

    /** 显示文本 → 卡片模型；只读旧格式，不改变 SwitchLog 的落盘/备份兼容性。 */
    private static List<LogRow> buildLogRows(Context app, String content,
                                           Map<String, List<SwitchLog.EntryRef>> refs) {
        List<LogRow> out = new ArrayList<>();
        if (content == null || content.trim().isEmpty()) {
            LogRow row = new LogRow();
            row.type = LogRow.EMPTY;
            row.title = app.getString(R.string.log_view_empty);
            out.add(row);
            return out;
        }
        for (String raw : content.split("\\n")) {
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            String text = line.trim();
            if (text.isEmpty()) {
                continue;
            }
            LogRow row = new LogRow();
            if (text.startsWith("=== ")) {
                row.type = LogRow.HEADER;
                row.title = trimEquals(text)
                        .replace(" · 桌面 · ", " · ")
                        .replace(" · 锁屏 · ", " · ");
                if (!row.title.startsWith("v") && !row.title.startsWith("V")) {
                    row.title = "v" + row.title;
                }
            } else if (isLogDate(text)) {
                row.type = LogRow.DATE;
                row.title = formatLogDate(text);
            } else {
                row.time = text.length() >= 5 ? text.substring(0, 5) : "";
                String body = text.length() > 5 ? text.substring(5).trim() : text;
                row.record = SwitchLog.parseRecordLine(app, text);
                if (row.record != null) {
                    row.type = LogRow.ENTRY;
                    row.refs = refs == null ? null : refs.get(row.record.line);
                    if (row.refs != null && !row.refs.isEmpty()) {
                        row.thumbId = row.refs.get(0).wallpaperId;
                    }
                    int firstOpen = body.indexOf('「');
                    int lastClose = body.lastIndexOf('」');
                    String scope = firstOpen > 0 ? body.substring(0, firstOpen).trim() : "";
                    if (row.record.title != null) {
                        // 老格式：第二对引号里是壁纸标题，库名跟着范围走副标题
                        row.title = row.record.title;
                        row.subtitle = row.record.libName == null || row.record.libName.isEmpty()
                                ? scope : scope + " · " + row.record.libName;
                    } else {
                        // 新格式：唯一一对引号是库名；标题已下线，主位就让给库名
                        row.title = row.record.libName;
                        row.subtitle = scope;
                    }
                    row.meta = lastClose >= 0 && lastClose + 1 < body.length()
                            ? trimLogDecor(body.substring(lastClose + 1)) : "";
                    row.kind = row.meta.contains("手动") ? "手动" : "自动";
                } else {
                    row.type = LogRow.CONFIG;
                    formatConfigLogRow(row, trimLogDecor(body));
                }
            }
            out.add(row);
        }
        return out;
    }

    /** 把配置原文拆成人能扫读的标题/说明/时间信息，不改落盘文字。 */
    private static void formatConfigLogRow(LogRow row, String body) {
        String text = body == null ? "" : body.trim();
        if (text.startsWith("暂停：") || text.startsWith("暂停:")) {
            row.title = "暂停当前壁纸";
            row.subtitle = text.substring(3).trim();
            row.kind = "暂停";
            return;
        }
        if (text.startsWith("继续")) {
            row.title = "继续自动切换";
            int dot = text.indexOf('·');
            row.meta = dot >= 0 ? text.substring(dot + 1).trim() : "";
            row.kind = "继续";
            return;
        }
        if (text.startsWith("换库")) {
            int dot = text.indexOf('·');
            String target = dot >= 0 ? text.substring(2, dot).trim() : text.substring(2).trim();
            row.title = "已换到" + target + "库";
            row.meta = dot >= 0 ? text.substring(dot + 1).trim() : "";
            row.kind = "换库";
            return;
        }
        row.title = text;
        if (text.contains("间隔")) row.kind = "间隔";
        else if (text.contains("随机") || text.contains("顺序")) row.kind = "模式";
        else if (text.contains("清空") || text.startsWith("停")) row.kind = "停止";
        else row.kind = "设置";
    }

    private static String formatLogDate(String raw) {
        try {
            Date date = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(raw);
            return date == null ? raw : new SimpleDateFormat("M 月 d 日　EEEE", Locale.CHINA).format(date);
        } catch (Exception ignored) {
            return raw;
        }
    }

    private static boolean isLogDate(String text) {
        if (text.length() != 10 || text.charAt(4) != '-' || text.charAt(7) != '-') {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (i != 4 && i != 7 && !Character.isDigit(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static String trimEquals(String text) {
        int start = 0, end = text.length();
        while (start < end && (text.charAt(start) == '=' || Character.isWhitespace(text.charAt(start)))) start++;
        while (end > start && (text.charAt(end - 1) == '=' || Character.isWhitespace(text.charAt(end - 1)))) end--;
        return text.substring(start, end);
    }

    private static String trimLogDecor(String text) {
        String out = text == null ? "" : text.trim();
        boolean changed = true;
        while (changed && !out.isEmpty()) {
            changed = false;
            if (out.startsWith("——")) { out = out.substring(2).trim(); changed = true; }
            if (out.endsWith("——")) { out = out.substring(0, out.length() - 2).trim(); changed = true; }
            if ((out.startsWith("（") && out.endsWith("）"))
                    || (out.startsWith("(") && out.endsWith(")"))) {
                out = out.substring(1, out.length() - 1).trim();
                changed = true;
            }
        }
        return out;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** 日志类型角标配色：手动/自动/换库/间隔/模式/暂停/继续/停止/设置。 */
    private void styleLogBadge(TextView badge, String kind) {
        int bgRes;
        int fgRes;
        switch (kind) {
            case "手动":
                bgRes = R.color.log_manual_bg;
                fgRes = R.color.log_manual_fg;
                break;
            case "自动":
                bgRes = R.color.log_auto_bg;
                fgRes = R.color.log_auto_fg;
                break;
            case "换库":
                bgRes = R.color.log_lib_bg;
                fgRes = R.color.log_lib_fg;
                break;
            case "间隔":
                bgRes = R.color.log_interval_bg;
                fgRes = R.color.log_interval_fg;
                break;
            case "模式":
                bgRes = R.color.log_mode_bg;
                fgRes = R.color.log_mode_fg;
                break;
            case "暂停":
                bgRes = R.color.log_pause_bg;
                fgRes = R.color.log_pause_fg;
                break;
            case "继续":
                bgRes = R.color.log_resume_bg;
                fgRes = R.color.log_resume_fg;
                break;
            case "停止":
                bgRes = R.color.log_stop_bg;
                fgRes = R.color.log_stop_fg;
                break;
            default:
                bgRes = R.color.log_setting_bg;
                fgRes = R.color.log_setting_fg;
                break;
        }
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(getColor(bgRes));
        pill.setCornerRadius(dp(8));
        badge.setBackground(pill);
        badge.setTextColor(getColor(fgRes));
    }

    private final class LogCardAdapter extends ImageAdapter {
        private List<LogRow> rows = new ArrayList<>();
        private AlertDialog logDialog;

        void setRows(List<LogRow> value) {
            rows = value == null ? new ArrayList<>() : value;
            notifyDataSetChanged();
        }

        @Override public int getItemCount() { return rows.size(); }
        @Override public int getItemViewType(int position) { return rows.get(position).type; }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            if (type == LogRow.HEADER || type == LogRow.DATE || type == LogRow.EMPTY) {
                TextView text = new TextView(MainActivity.this);
                text.setPadding(dp(4), type == LogRow.DATE ? dp(14) : dp(10), dp(4), dp(6));
                text.setTextColor(MaterialColors.getColor(text, type == LogRow.HEADER
                        ? com.google.android.material.R.attr.colorOnPrimaryContainer
                        : com.google.android.material.R.attr.colorOnSurfaceVariant));
                text.setTextAppearance(type == LogRow.HEADER
                        ? com.google.android.material.R.style.TextAppearance_Material3_LabelLarge
                        : com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
                if (type == LogRow.HEADER) {
                    GradientDrawable capsule = new GradientDrawable();
                    capsule.setColor(MaterialColors.getColor(text,
                            com.google.android.material.R.attr.colorPrimaryContainer));
                    capsule.setCornerRadius(dp(10));
                    text.setBackground(capsule);
                    text.setPadding(dp(10), dp(6), dp(10), dp(6));
                    RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    lp.setMargins(dp(4), dp(8), dp(4), dp(4));
                    text.setLayoutParams(lp);
                }
                return new RecyclerView.ViewHolder(text) { };
            }
            MaterialCardView card = new MaterialCardView(MainActivity.this);
            card.setRadius(dp(14));
            card.setStrokeWidth(dp(1));
            card.setStrokeColor(MaterialColors.getColor(card,
                    com.google.android.material.R.attr.colorOutlineVariant));
            card.setCardBackgroundColor(MaterialColors.getColor(card,
                    com.google.android.material.R.attr.colorSurfaceContainerLow));
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.TOP);
            row.setPadding(dp(14), dp(12), dp(14), dp(12));
            // 左列：时间在上，下面一颗小缩略图（该行切到的那张；比点开弹窗的图小得多）。
            // 与网格共用 UiImages 缩略图订阅，不在绑定时读盘。
            LinearLayout left = new LinearLayout(MainActivity.this);
            left.setOrientation(LinearLayout.VERTICAL);
            TextView time = new TextView(MainActivity.this);
            time.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall);
            time.setTextColor(MaterialColors.getColor(time,
                    com.google.android.material.R.attr.colorOnSurface));
            left.addView(time, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            ImageView thumb = new ImageView(MainActivity.this);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.setClipToOutline(true);
            thumb.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(8));
                }
            });
            LinearLayout.LayoutParams thumbLp = new LinearLayout.LayoutParams(dp(44), dp(44));
            thumbLp.topMargin = dp(6);
            thumb.setVisibility(View.GONE);
            left.addView(thumb, thumbLp);
            row.addView(left, new LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout words = new LinearLayout(MainActivity.this);
            words.setOrientation(LinearLayout.VERTICAL);
            TextView title = new TextView(MainActivity.this);
            title.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
            title.setTextColor(MaterialColors.getColor(title,
                    com.google.android.material.R.attr.colorOnSurface));
            TextView subtitle = new TextView(MainActivity.this);
            subtitle.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
            subtitle.setTextColor(MaterialColors.getColor(subtitle,
                    com.google.android.material.R.attr.colorOnSurfaceVariant));
            TextView meta = new TextView(MainActivity.this);
            meta.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
            meta.setTextColor(MaterialColors.getColor(meta,
                    com.google.android.material.R.attr.colorOnSurfaceVariant));
            meta.setPadding(0, dp(4), 0, 0);
            words.addView(title);
            words.addView(subtitle);
            words.addView(meta);
            row.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView badge = new TextView(MainActivity.this);
            badge.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelSmall);
            badge.setGravity(Gravity.CENTER);
            badge.setPadding(dp(8), dp(4), dp(8), dp(4));
            LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            badgeLp.setMarginStart(dp(8));
            row.addView(badge, badgeLp);
            card.addView(row);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(4), 0, dp(4));
            card.setLayoutParams(lp);
            return new LogCardHolder(card, time, thumb, title, subtitle, meta, badge);
        }

        @Override
        void bindRow(RecyclerView.ViewHolder holder, int position) {
            LogRow item = rows.get(position);
            if (!(holder instanceof LogCardHolder)) {
                ((TextView) holder.itemView).setText(item.title);
                return;
            }
            LogCardHolder h = (LogCardHolder) holder;
            h.time.setText(item.time);
            h.thumb.setVisibility(item.thumbId == null ? View.GONE : View.VISIBLE);
            images.thumbnail(h.thumb, item.thumbId);
            h.title.setText(item.title);
            h.subtitle.setText(item.subtitle);
            h.subtitle.setVisibility(item.subtitle.isEmpty() ? View.GONE : View.VISIBLE);
            h.meta.setText(item.meta);
            h.meta.setVisibility(item.meta.isEmpty() ? View.GONE : View.VISIBLE);
            h.badge.setText(item.kind);
            h.badge.setVisibility(item.kind.isEmpty() ? View.GONE : View.VISIBLE);
            if (!item.kind.isEmpty()) {
                styleLogBadge(h.badge, item.kind);
            }
            boolean clickable = item.record != null;
            h.card.setClickable(clickable);
            h.card.setFocusable(clickable);
            h.card.setRippleColor(ColorStateList.valueOf(MaterialColors.getColor(h.card,
                    com.google.android.material.R.attr.colorSecondaryContainer)));
            h.card.setOnClickListener(clickable
                    ? v -> showLogThumbnail(item.record, item.refs, logDialog) : null);
        }
    }

    private static final class LogCardHolder extends RecyclerView.ViewHolder {
        final MaterialCardView card;
        final TextView time, title, subtitle, meta, badge;
        final ImageView thumb;

        LogCardHolder(MaterialCardView card, TextView time, ImageView thumb, TextView title,
                      TextView subtitle, TextView meta, TextView badge) {
            super(card);
            this.card = card;
            this.time = time;
            this.thumb = thumb;
            this.title = title;
            this.subtitle = subtitle;
            this.meta = meta;
            this.badge = badge;
        }
    }

    /**
     * 日志弹窗：顶部 桌面/锁屏 两个 tab（两份文件各记各的账，混在一起看只会看错），
     * tab 下面一行「最新在前」开关管展示顺序（默认开，只影响显示、文件照旧按时间追加），
     * 正文是等宽、可长按选中的可滚动区。
     * 底部三个键：取消／复制／清空——复制复制的是屏上这一面（含当前顺序），清空是两面一起清。
     */
    void showSwitchLogDialog(String home, String lock,
                                     Map<String, List<SwitchLog.EntryRef>> refs) {
        final String[] contents = {home == null ? "" : home, lock == null ? "" : lock};
        final String[] displayed = new String[contents.length];
        final int[] shown = {0};
        final boolean[] newestFirst = {SwitchLog.newestFirst(this)};
        final boolean empty = contents[0].trim().isEmpty() && contents[1].trim().isEmpty();
        final float density = getResources().getDisplayMetrics().density;

        final TextView body = new TextView(this);
        body.setTextSize(12f);
        body.setTextIsSelectable(true);
        body.setTypeface(Typeface.MONOSPACE);
        int pad = (int) (16 * density);
        body.setPadding(pad, pad, pad, pad);

        final ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        final TabLayout tabs = new TabLayout(this);
        tabs.addTab(tabs.newTab().setText(R.string.log_tab_home));
        tabs.addTab(tabs.newTab().setText(R.string.log_tab_lock));
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                shown[0] = tab.getPosition();
                bindLogBody(body, displayed[shown[0]]);
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });

        // 按当前顺序把两面各重排一遍并刷正文；翻完从头看，免得停在旧顺序的中间
        Runnable rebind = () -> {
            for (int i = 0; i < contents.length; i++) {
                displayed[i] = SwitchLog.stripRefs(
                        SwitchLog.reorder(contents[i], newestFirst[0]));
            }
            bindLogBody(body, displayed[shown[0]]);
            scroll.scrollTo(0, 0);
        };

        TextView tvOrder = new TextView(this);
        tvOrder.setText(R.string.log_view_order);
        tvOrder.setTextSize(14f);
        CompoundButton swOrder = new MaterialSwitch(this);
        swOrder.setChecked(newestFirst[0]);
        swOrder.setOnCheckedChangeListener((buttonView, isChecked) -> {
            newestFirst[0] = isChecked;
            // 顺序是全局偏好：下次点开照旧，不用每次重挑
            SwitchLog.setNewestFirst(MainActivity.this, isChecked);
            rebind.run();
        });
        LinearLayout orderRow = new LinearLayout(this);
        orderRow.setOrientation(LinearLayout.HORIZONTAL);
        orderRow.setGravity(Gravity.CENTER_VERTICAL);
        orderRow.setPadding(pad, (int) (6 * density), pad, (int) (6 * density));
        orderRow.addView(tvOrder, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        orderRow.addView(swOrder);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        // 固定高度上限：日志长了在弹窗内部滚动，不会把弹窗撑到超出屏幕
        int maxHeight = (int) (getResources().getDisplayMetrics().heightPixels * 0.6f);
        column.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, maxHeight));
        column.addView(tabs);
        column.addView(orderRow);
        column.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        rebind.run();

        // 用父类型接收：MaterialAlertDialogBuilder 只对 setTitle/setView 做了协变覆盖，
        // setNegativeButton 继承自 AlertDialog.Builder，链式表达式静态类型是 Builder
        AlertDialog.Builder builder = new AppAlertDialogBuilder(this)
                .setTitle(R.string.log_view_title)
                .setView(column)
                .setNegativeButton(R.string.cancel, null);
        if (!empty) {
            builder.setNeutralButton(R.string.log_view_copy,
                    (dialog, which) -> copySwitchLog(displayed[shown[0]]));
            builder.setPositiveButton(R.string.log_view_clear, null);
        }
        AlertDialog dialog = builder.show();
        if (!empty) {
            // 盖掉「清空」那点下去的默认行为（默认会把日志窗一起关掉）：这里只是先浮出确认窗，
            // 用户在确认窗按取消时日志得还留在屏上
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener(v -> confirmClearSwitchLog(dialog));
        }
        bindLogRecordClicks(body, dialog, refs);
    }

    /** 弹窗正文换内容（切 tab、翻顺序时都用）：空的那一面给一句说明，不留白屏。 */
    void bindLogBody(TextView body, String content) {
        body.setText(content == null || content.trim().isEmpty()
                ? getString(R.string.log_view_empty) : content);
    }

    /**
     * 点日志正文里的记录行 → 小窗看那张的缩略图（与网格一格同大）。
     *
     * <p>正文是一个可长按选中的 TextView：不做 ClickableSpan（它和 {@code setTextIsSelectable}
     * 互斥，还会把外层 ScrollView 的竖向拖动吃掉），而是在这里旁听触摸事件、按坐标落回逻辑行，
     * 再自己判一次「这算不算点击」——移动超过容差、按下超过长按时长、或本来正选着字，都不算。
     */
    void bindLogRecordClicks(TextView body, AlertDialog logDialog,
                                     Map<String, List<SwitchLog.EntryRef>> refs) {
        final int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        final long longPress = ViewConfiguration.getLongPressTimeout();
        final float[] downAt = new float[2];
        final long[] downTime = new long[1];
        final boolean[] dragged = new boolean[1];
        final boolean[] selecting = new boolean[1];
        body.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downAt[0] = event.getX();
                    downAt[1] = event.getY();
                    downTime[0] = event.getEventTime();
                    dragged[0] = false;
                    // 已经有选区时这一下是在取消选中，不顺手开图
                    selecting[0] = body.hasSelection();
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (!dragged[0] && (Math.abs(event.getX() - downAt[0]) > slop
                            || Math.abs(event.getY() - downAt[1]) > slop)) {
                        dragged[0] = true;
                    }
                    break;
                case MotionEvent.ACTION_UP:
                    if (!dragged[0] && !selecting[0]
                            && event.getEventTime() - downTime[0] < longPress) {
                        SwitchLog.RecordLine record =
                                recordLineAt(body, event.getX(), event.getY());
                        if (record != null) {
                            List<SwitchLog.EntryRef> exact =
                                    refs == null ? null : refs.get(record.line);
                            showLogThumbnail(record, exact, logDialog);
                        }
                    }
                    dragged[0] = false;
                    break;
                case MotionEvent.ACTION_CANCEL:
                    dragged[0] = false;
                    break;
                default:
                    break;
            }
            return false;
        });
    }

    /** 触摸点落在哪个逻辑行（折行后按 \n 归并，不按视觉行），是记录行就拆出库名与标题。 */
    private SwitchLog.RecordLine recordLineAt(TextView body, float x, float y) {
        Layout layout = body.getLayout();
        if (layout == null) {
            return null;
        }
        // Layout 的坐标不含 TextView 的 padding，先把四个方向的内边距去掉
        float localY = y - body.getTotalPaddingTop();
        if (localY < 0 || localY >= layout.getHeight()) {
            return null;
        }
        int visualLine = layout.getLineForVertical((int) localY);
        float localX = Math.max(0f,
                Math.min(x - body.getTotalPaddingLeft(), layout.getWidth() - 1f));
        CharSequence text = body.getText();
        int hit = layout.getOffsetForHorizontal(visualLine, localX);
        if (hit < 0) {
            return null;
        }
        int offset = Math.min(hit, text.length());
        int start = offset;
        while (start > 0 && text.charAt(start - 1) != '\n') {
            start--;
        }
        int end = offset;
        while (end < text.length() && text.charAt(end) != '\n') {
            end++;
        }
        return SwitchLog.parseRecordLine(this, text.subSequence(start, end).toString());
    }

    /**
     * 记录行 → 看图小窗。行尾藏着壁纸 id，直接按 id 取图（库改名都不影响）；
     * v3.131 之前的老行没有 id 段，无从反查，点了就提示找不到。
     * 显示的是<b>成品图宽高各半</b>的完整构图（不是方形缩略图），挂 previews 大图缓存，
     * 解码放后台线程，解完再弹；半尺寸解不动时退回网格那颗方形缩略图。
     */
    void showLogThumbnail(SwitchLog.RecordLine record,
                                  List<SwitchLog.EntryRef> exact, AlertDialog logDialog) {
        final Context app = getApplicationContext();
        final int pageToken = logPageToken;
        final int pageScope = logScope;
        final long epoch = imageEpoch;
        new Thread(() -> {
            List<Bitmap> thumbs = new ArrayList<>();
            Map<String, Bitmap> decoded = new HashMap<>();
            Set<String> seen = new HashSet<>();
            for (SwitchLog.EntryRef ref : exact == null ? new ArrayList<SwitchLog.EntryRef>() : exact) {
                if (ref.wallpaperId == null || ref.wallpaperId.isEmpty()
                        || !seen.add(ref.wallpaperId)) {
                    continue;
                }
                WallpaperStore.Item item = WallpaperStore.get(app, ref.wallpaperId);
                if (item == null) {
                    continue;
                }
                String key = "log_" + item.id;
                Bitmap thumb = previews.get(key);
                if (thumb == null) {
                    thumb = decodeFullHalf(app, item.id);
                    if (thumb == null) {
                        thumb = WallpaperStore.getThumb(app, item.id);
                    } else {
                        decoded.put(key, thumb);
                    }
                }
                if (thumb != null && !containsSameThumb(thumbs, thumb)) {
                    thumbs.add(thumb);
                }
            }
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || epoch != imageEpoch) {
                    return;
                }
                for (Map.Entry<String, Bitmap> entry : decoded.entrySet()) {
                    previews.put(entry.getKey(), entry.getValue());
                }
                // 旧弹窗或新独立页只要入口还在屏上就允许出缩略图；都关了就不往别处飞图
                boolean dialogOpen = logDialog != null && logDialog.isShowing();
                boolean pageOpen = logOpen && pageToken == logPageToken && pageScope == logScope;
                if (!dialogOpen && !pageOpen) {
                    return;
                }
                if (thumbs.isEmpty()) {
                    Toast.makeText(this, R.string.log_thumb_gone, Toast.LENGTH_SHORT).show();
                    return;
                }
                showThumbnailWindow(thumbs);
            });
        }, "log-thumb").start();
    }

    /** 只摆图的小窗：宽与网格一格同大、高按各自宽高比，圆角 12dp（与网格的 RoundedGrid 一致）。 */
    void showThumbnailWindow(List<Bitmap> thumbs) {
        float density = getResources().getDisplayMetrics().density;
        // 与网格一格的显示宽度一致：屏宽 − 两侧 12dp 内边距 − 每格 4dp×2 外边距，再对半
        int cell = Math.max(1,
                (getResources().getDisplayMetrics().widthPixels - (int) (40 * density)) / 2);
        int gap = (int) (8 * density);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        for (Bitmap thumb : thumbs) {
            ShapeableImageView image = new ShapeableImageView(this);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setContentDescription(getString(R.string.thumb_desc));
            image.setShapeAppearanceModel(ShapeAppearanceModel.builder()
                    .setAllCornerSizes(12 * density).build());
            int h = thumb.getWidth() > 0
                    ? Math.round(cell * thumb.getHeight() / (float) thumb.getWidth()) : cell;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cell, h);
            if (row.getChildCount() > 0) {
                lp.setMarginStart(gap);
            }
            row.addView(image, lp);
            image.setImageBitmap(thumb);
        }
        int width = cell;
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.addView(row);
        if (thumbs.size() > 1) {
            // 多张同名时窗口按候选数加宽，放到屏幕放不下为止，再横向滑
            int needed = thumbs.size() * cell + (thumbs.size() - 1) * gap;
            int max = Math.max(cell, getResources().getDisplayMetrics().widthPixels
                    - (int) (64 * density));
            width = Math.min(needed, max);
            scroll.setLayoutParams(new ViewGroup.LayoutParams(
                    width, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.addView(scroll);
        // 对话框自带最小宽度与 24dp 背景内缩，照默认量出来两边都是白：把背景让掉、内缩归零，
        // 再把窗宽压到图本身（多张时是那一排的宽）
        AlertDialog dialog = new AppAlertDialogBuilder(this)
                .setBackground(new ColorDrawable(Color.TRANSPARENT))
                .setBackgroundInsetStart(0)
                .setBackgroundInsetTop(0)
                .setBackgroundInsetEnd(0)
                .setBackgroundInsetBottom(0)
                .setView(content)
                .show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    /** 两张缩略图是不是同一张图：复制出来的副本视觉上一致，合并成一份，不并排出两张一样的。 */
    private static boolean containsSameThumb(List<Bitmap> thumbs, Bitmap candidate) {
        for (Bitmap thumb : thumbs) {
            if (thumb.sameAs(candidate)) {
                return true;
            }
        }
        return false;
    }

    /** 复制日志全文到剪贴板。 */
    void copySwitchLog(String content) {
        try {
            ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (manager == null) {
                return;
            }
            manager.setPrimaryClip(ClipData.newPlainText(getString(R.string.log_view_title), content));
            Toast.makeText(this, R.string.log_view_copied, Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {
        }
    }

    private void showSetWallpaperDialog(WallpaperStore.Item item) {
        String libId = itemActionLibId();
        LibraryStore.Library lib = libId == null ? null : LibraryStore.get(this, libId);
        if (lib == null) {
            return;
        }
        CharSequence[] options = getResources().getTextArray(R.array.design_apply_targets);
        new AppAlertDialogBuilder(this)
                .setTitle(R.string.design_set_wallpaper)
                .setItems(options, (dialog, which) -> {
                    boolean home = which != 1;
                    boolean lock = which != 0;
                    if (!ensureTakeoverForScope(home)) {
                        return;
                    }
                    if ((home && !LibraryStore.ownsScope(this, lib.id, true))
                            || (lock && !LibraryStore.ownsScope(this, lib.id, false))) {
                        CharSequence scope = home && lock
                                ? getString(R.string.design_scope_both) : options[which];
                        new AppAlertDialogBuilder(this)
                                .setTitle(R.string.design_change_library)
                                .setMessage(getString(R.string.design_change_library_message,
                                        scope, lib.name))
                                .setPositiveButton(R.string.confirm,
                                        (d, w) -> applyWallpaper(item, lib.id, home, lock))
                                .setNegativeButton(R.string.cancel, null)
                                .show();
                    } else {
                        applyWallpaper(item, lib.id, home, lock);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void applyWallpaper(WallpaperStore.Item item, String libId, boolean home, boolean lock) {
        if (settingWallpaper) {
            return;
        }
        settingWallpaper = true;
        findViewById(R.id.btn_detail_home).setEnabled(false);
        Context app = getApplicationContext();
        new Thread(() -> {
            StringBuilder result = new StringBuilder();
            for (boolean forHome : new boolean[]{true, false}) {
                if (forHome ? !home : !lock) {
                    continue;
                }
                String scope = getString(forHome ? R.string.now_scope_home : R.string.now_scope_lock);
                if (LibraryStore.get(app, libId) == null || WallpaperStore.get(app, item.id) == null) {
                    result.append(scope).append(getString(R.string.design_apply_missing)).append('\n');
                    continue;
                }
                if (!LibraryStore.ownsScope(app, libId, forHome)) {
                    LibraryStore.setSlotLib(app, forHome, libId);
                }
                boolean ok = Switcher.setCurrent(app, libId, item.id, forHome);
                if (ok) {
                    TimerScheduler.restartScope(app, forHome);
                }
                result.append(scope).append("：").append(ok ? getString(R.string.design_apply_done)
                        : Switcher.errorText(app, Switcher.lastError())).append('\n');
            }
            runOnUiThread(() -> {
                settingWallpaper = false;
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                findViewById(R.id.btn_detail_home).setEnabled(true);
                Toast.makeText(this, result.toString().trim(), Toast.LENGTH_LONG).show();
                refreshTakeoverStatus();
                refreshDetail();
            });
        }, "set-wallpaper").start();
    }

    /**
     * 长按浮出的红垃圾桶。多归属之后这一按的破坏面比过去大：删的是那几库共用的那一份文件，
     * 别的库里那张也一起没，所以只在「还挂在别的库」时拦一道，把两个出口摆明给他选；
     * 只挂在本库的照旧一键直接删（v3.115 之前的手感不动）。
     *
     * <p>「解除本库关联」走的是整理态那一条移出链路（那张还挂在别的库，所以一张图都不会被删），
     * 图钉跟随与逐归属的当前指针核对都在里面，不在这里立第二份收尾。
     */
    void handleTrashClick(final WallpaperStore.Item item) {
        // 彻底删除会连成品图文件一起没，当值那张不许动（下面的「解除本库关联」不碰文件，照旧放行）
        if (blockWhileInUse(item)) {
            return;
        }
        final String src = itemActionLibId();
        if (src == null) {
            return;
        }
        StringBuilder others = new StringBuilder();
        int otherCount = 0;
        for (String libId : item.libIds) {
            if (libId.equals(src)) {
                continue;
            }
            LibraryStore.Library lib = LibraryStore.get(this, libId);
            if (lib == null) {
                // 归属指向一个已经不在了的库：不该数进「还挂在哪儿」里，那种条目还原时会兜底
                continue;
            }
            if (others.length() > 0) {
                others.append("、");
            }
            others.append("「").append(lib.name).append("」");
            otherCount++;
        }
        if (others.length() == 0) {
            deleteWallpaperEverywhere(item);
            return;
        }
        AlertDialog dialog = new AppAlertDialogBuilder(this)
                .setTitle(R.string.delete_confirm_title)
                // 个数只数点得出名字的那些：连同本库，文案里的数字与列出的库必须对得上
                .setMessage(getString(R.string.delete_confirm_msg,
                        others.toString(), otherCount + 1))
                .setPositiveButton(R.string.delete_purge, (d, which) -> deleteWallpaperEverywhere(item))
                .setNegativeButton(R.string.delete_unlink,
                        (d, which) -> applyOrganizeChange(ORGANIZE_REMOVE,
                                java.util.Collections.singletonList(item.id), src, null, null))
                .setNeutralButton(R.string.cancel, null)
                .show();
        // 破坏性那一条不该长成主按钮的强调色：照批量删库确认钮的压法，红字 + 红垃圾桶
        Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        positive.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_delete, 0, 0, 0);
        positive.setCompoundDrawableTintList(ColorStateList.valueOf(getColor(R.color.danger)));
        positive.setTextColor(getColor(R.color.danger));
    }

    /**
     * 彻底删除一张：所有库的记录连同三份文件一起没（成品图 / 缩略图 / 原图）。
     * 它挂在几个库里，那几个库的「屏上这张」指针就得各自核对一遍 —— 一张同时占着桌面和锁屏两面时，
     * 只按主归属清会漏掉另一面。锁屏那一路是阻塞的系统调用，放后台线程、用 Application 上下文。
     */
    void deleteWallpaperEverywhere(WallpaperStore.Item item) {
        final List<String> owners = new ArrayList<>(item.libIds);
        WallpaperStore.delete(this, item.id);
        evictImages(item.id);
        refreshList();
        new Thread(() -> {
            for (String owner : owners) {
                Switcher.reapplyIfCurrent(getApplicationContext(), owner, item.id);
            }
        }, "reapply-wallpaper").start();
    }

    /**
     * 清除一键设置的设定：只让通知上那颗开关不再画，<b>不还原壁纸</b>；撤回快照也一并丢掉
     * （入口没了就退不回去了，所以这句要说清）。想让轮播回来是按那颗暂停键，不是清这里。
     */
    void clearPinned() {
        PinnedWallpaper.clear(this);
        refreshSettings();
        refreshPinnedCard();
        StatusNotifier.update(this);
        Toast.makeText(this, R.string.pinned_cleared, Toast.LENGTH_SHORT).show();
    }

    /** 两级选图的第一级：这张在哪个库里。选完直接进第二级。 */
    void showPinnedPicker() {
        final List<LibraryStore.Library> libs = LibraryStore.load(this);
        if (libs.isEmpty()) {
            Toast.makeText(this, R.string.pinned_no_lib, Toast.LENGTH_SHORT).show();
            return;
        }
        new AppAlertDialogBuilder(this)
                .setTitle(R.string.pinned_lib_title)
                .setAdapter(new PinLibPickerAdapter(libs, PinnedWallpaper.libId(this)),
                        (dialog, which) -> showPinnedWallpaperPicker(libs.get(which).id))
                .setNegativeButton(R.string.close, null)
                .show();
    }

    /**
     * 两级选图的第二级：这个库里的哪一张。点一行就落 prefs，<b>不在这里上屏</b> ——
     * 上屏归通知上那颗开关，一次点按办一件事；选完顺手切一张等于替他做了一个他没按的动作。
     */
    void showPinnedWallpaperPicker(final String libId) {
        final List<WallpaperStore.Item> items = WallpaperStore.loadByLib(this, libId);
        if (items.isEmpty()) {
            Toast.makeText(this, R.string.pinned_lib_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        // 勾只画在「pin 正指向这个库」时的那一张上；指向别的库时整列都不打勾
        String pinnedLibId = PinnedWallpaper.libId(this);
        final String liveId = libId.equals(pinnedLibId) ? PinnedWallpaper.wallpaperId(this) : null;
        new AppAlertDialogBuilder(this)
                .setTitle(R.string.pinned_pic_title)
                .setAdapter(new PinWallpaperAdapter(items, liveId), (dialog, which) -> {
                    PinnedWallpaper.set(this, libId, items.get(which).id);
                    refreshSettings();
                    refreshPinnedCard();
                    // 那颗开关要当场跟上（没配→画出来、已钉↔没钉都靠这次重发），别等下次切图才画
                    StatusNotifier.update(this);
                    Toast.makeText(this, R.string.pinned_saved, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.close, null)
                .show();
    }

    /** 一键设置两级列表共用的一行（现成的 {@code item_slot_lib}：缩略图 + 主标题 + 副标题 + 选中勾）。 */
    private View newPinRow(View convertView, ViewGroup parent) {
        return convertView != null ? convertView
                : LayoutInflater.from(this).inflate(R.layout.item_slot_lib, parent, false);
    }

    /** 此刻页一键设置卡的状态刷新：开关是否钉着、副标题三态（未设置 / 已配未钉 / 已钉住）。 */
    private void refreshPinnedCard() {
        final Context app = getApplicationContext();
        new Thread(() -> {
            final boolean configured = PinnedWallpaper.isConfigured(app);
            final boolean pinned = configured && PinnedWallpaper.canUndo(app);
            final String lib = configured ? PinnedWallpaper.label(app) : null;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                pinnedCardRefresh = true;
                MaterialSwitch sw = findViewById(R.id.switch_pinned);
                sw.setChecked(pinned);
                sw.setEnabled(configured);
                pinnedCardRefresh = false;
                TextView tv = findViewById(R.id.tv_pinned);
                if (!configured || lib == null) {
                    tv.setText(R.string.pinned_unset);
                } else if (pinned) {
                    tv.setText(R.string.now_pinned_on);
                } else {
                    tv.setText(getString(R.string.now_pinned_ready, lib));
                }
            });
        }, "pinned-card").start();
    }

    /** 卡身点按 = 配置入口：没配直接选图；配过了给「挑一张 / 清除」，与设置页那行同款。 */
    private void onPinnedCardTapped() {
        if (!PinnedWallpaper.isConfigured(this)) {
            showPinnedPicker();
            return;
        }
        new AppAlertDialogBuilder(this)
                .setTitle(R.string.pinned_title)
                .setItems(new CharSequence[]{getString(R.string.pinned_pick), getString(R.string.pinned_clear)},
                        (d, which) -> {
                            if (which == 0) {
                                showPinnedPicker();
                            } else {
                                clearPinned();
                            }
                        })
                .show();
    }

    /** 右侧开关 = 执行键，与通知栏那颗同一件事：拨上去钉（apply），拨回去退（undo）。 */
    private void onPinnedSwitchToggled(boolean on) {
        if (pinnedCardRefresh) {
            return;
        }
        if (!PinnedWallpaper.isConfigured(this)) {
            showPinnedPicker();
            refreshPinnedCard();
            return;
        }
        if (on == PinnedWallpaper.canUndo(this)) {
            refreshPinnedCard();
            return;
        }
        // 钉/退都会写锁屏静态存档，是阻塞系统调用；与通知那颗一样放后台跑，落定后再刷两态
        final Context app = getApplicationContext();
        new Thread(() -> {
            String error = on ? PinnedWallpaper.apply(app) : PinnedWallpaper.undo(app);
            if (error == null) {
                StatusNotifier.update(app);
            }
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (error != null) {
                    Toast.makeText(this, PinnedWallpaper.errorText(this, error), Toast.LENGTH_LONG).show();
                }
                refreshPinnedCard();
            });
        }, "pinned-card").start();
    }

    /**
     * 第一级：一行一个库，pin 现在指向的那个打勾。
     * 张数在构造时一次遍历算完（{@code loadByLib} 内部是「整份 library.json 读一遍再筛」，
     * 按行调用就是库数 × 整份解析），与 {@code LibPickerActivity} 同一笔账。
     */
    private class PinLibPickerAdapter extends BaseAdapter {

        private final List<LibraryStore.Library> libs;
        private final String liveLibId;
        private final Map<String, Integer> counts = new HashMap<>();

        PinLibPickerAdapter(List<LibraryStore.Library> libs, String liveLibId) {
            this.libs = libs;
            this.liveLibId = liveLibId;
            counts.putAll(WallpaperStore.libMemberCounts(MainActivity.this));
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
            Integer count = counts.get(lib.id);
            ((TextView) row.findViewById(R.id.tv_pick_name)).setText(lib.name);
            ((TextView) row.findViewById(R.id.tv_pick_sub))
                    .setText(getString(R.string.lib_count, count == null ? 0 : count));
            // 代表图沿用库卡与选库弹窗那套（这一面在屏的那张优先、其次另一面、再次库内第一张），
            // 不再立第三种取法
            bindThumb((ImageView) row.findViewById(R.id.img_pick_thumb),
                    libCoverId(lib), R.drawable.ic_tab_wallpaper, 10);
            row.findViewById(R.id.iv_pick_check).setVisibility(
                    lib.id.equals(liveLibId) ? View.VISIBLE : View.INVISIBLE);
            return row;
        }
    }

    /**
     * 第二级：这一库里的每一张。副标题是<b>原图</b>尺寸（{@code library.json} 里现成的字段，
     * 不必再读一次文件头）；没记过参数的留空。
     * 缩略图<b>不预解全部</b> —— 一个库可能上千张，开弹窗时一次解完等于自己把自己卡住；
     * 沿用首页网格那套 LruCache 现解（同一个缓存，多半滚动时已经热了）。
     */
    private class PinWallpaperAdapter extends BaseAdapter {

        private final List<WallpaperStore.Item> items;
        private final String liveId;

        PinWallpaperAdapter(List<WallpaperStore.Item> items, String liveId) {
            this.items = items;
            this.liveId = liveId;
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @NonNull
        @Override
        public View getView(int position, View convertView, @NonNull ViewGroup parent) {
            View row = newPinRow(convertView, parent);
            WallpaperStore.Item item = items.get(position);
            // 标题已下线：这行只剩缩略图 + 原图尺寸，名字那格藏掉
            row.findViewById(R.id.tv_pick_name).setVisibility(View.GONE);
            ((TextView) row.findViewById(R.id.tv_pick_sub)).setText(item.srcWidth > 0 && item.srcHeight > 0
                    ? getString(R.string.pinned_pic_sub, item.srcWidth, item.srcHeight)
                    : "");
            bindThumb((ImageView) row.findViewById(R.id.img_pick_thumb),
                    item.id, R.drawable.ic_tab_wallpaper, 10);
            row.findViewById(R.id.iv_pick_check).setVisibility(
                    item.id.equals(liveId) ? View.VISIBLE : View.INVISIBLE);
            return row;
        }
    }

    /** 弹窗输入框统一套一层 TextInputLayout（M3 描边 + 浮动提示），返回可直接 setView 的容器。 */
    private TextInputLayout wrapInput(EditText input, int hintRes) {
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
            sub.setText(getString(R.string.lib_count,
                    WallpaperStore.loadByLib(MainActivity.this, lib.id).size()));
            bindThumb(thumb, slotThumbId(lib.id, forHome), R.drawable.ic_tab_wallpaper, 10);
            check.setVisibility(lib.id.equals(liveId) ? View.VISIBLE : View.INVISIBLE);
            return row;
        }
    }
}
