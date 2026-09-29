package com.example.wallswitch;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.util.LruCache;
import android.view.Gravity;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
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
import androidx.appcompat.widget.Toolbar;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputLayout;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 主页 v3.14：层级导航 —— 首页 = 壁纸库列表，点库行进该库的两列正方形网格壁纸页。
 * 单 Activity + 页状态切换（一个 RecyclerView 换 Adapter + LayoutManager）：
 * <ul>
 *   <li>库列表页：☰ 开抽屉 + 固定标题 WallPaper；行 = 缩略图（该库当前壁纸）+ 库名
 *       （点按就地改名）+ N 张壁纸 + 启用开关 + 齿轮（范围/模式/间隔/立即切换）；
 *       长按行浮出红垃圾桶（删库二次确认）；右下角 ＋ 新建库。</li>
 *   <li>壁纸网格页：← 返回 + 库名；两列 1:1 正方形网格；点格预览，长按浮出
 *       铅笔（进裁剪编辑页）/ 红垃圾桶（直接删，不确认）；右下角 ＋ 添加壁纸；
 *       返回键/返回手势回库列表（在库列表页返回才退出 App）。</li>
 * </ul>
 * 左侧设置抽屉只留全局项：接管情况（一个总开关 + 桌面/锁屏两行状态）、自动切换
 * （到点通知 + 上次/下次时间）、导出与日志、桌面图标预览、引擎与后台。
 */
public class MainActivity extends AppCompatActivity {

    // SharedPreferences 文件名
    private static final String PREFS_NAME = "settings";
    // 相册单次多选上限
    private static final int MAX_PICK = 50;
    // 通知权限提示是否已弹过的记录 key（避免每次打开应用都打扰）
    private static final String KEY_NOTIFY_PROMPTED = "notify_prompted";

    private ActivityResultLauncher<PickVisualMediaRequest> pickLauncher;
    // 通知权限请求（Android 13+ 自动切换提示需要 POST_NOTIFICATIONS）
    private ActivityResultLauncher<String> notifyPermissionLauncher;
    /** 读取系统壁纸存档用（Android 13+ 是 READ_MEDIA_IMAGES，低版本 READ_EXTERNAL_STORAGE） */
    private ActivityResultLauncher<String> savePermLauncher;
    /** Android 11+ 跳系统设置开「所有文件访问」，回来后检查结果继续开启接管 */
    private ActivityResultLauncher<Intent> allFilesLauncher;
    // 全局「桌面图标预览底图」的单张选图（选一次、抠图后存应用目录，之后裁剪页直接复用）
    private ActivityResultLauncher<PickVisualMediaRequest> overlayLauncher;
    // SAF 导出目录选择（ACTION_OPEN_DOCUMENT_TREE）
    private ActivityResultLauncher<Uri> exportDirLauncher;
    // 接管开关是否处于「等待用户在系统选择器里确认」的状态（用来判断用户是否点了取消）
    private RecyclerView recycler;
    // 两页共用一个 RecyclerView：库列表页 = LibAdapter + LinearLayoutManager，
    // 壁纸网格页 = WallpaperAdapter + GridLayoutManager(2)
    private LibAdapter libAdapter;
    private WallpaperAdapter wallpaperAdapter;
    private SharedPreferences prefs;
    /** 保存当前壁纸时的不可关闭进度弹窗（showProgress/dismissTakeoverProgress 管理）。 */
    private androidx.appcompat.app.AlertDialog takeoverProgress;
    // 当前页：true = 壁纸网格页；false = 库列表页（首页）
    private boolean showingWallpapers = false;
    // 壁纸页正在看的库 id（也兼作「上次查看的库」，导入壁纸时的默认目标库）
    private String currentLibId;
    // 正在就地改名的库 id（null = 没有；返回键用它判断「取消改名」而不是退出）
    private String renamingLibId;
    // 顶栏删除按钮（库页专用）：普通态 = 垃圾桶进删除态，删除态 = 勾图标确认批量删除
    private MenuItem libDeleteItem;
    // v3.60 范围槽位卡片（只在库列表页显示）：桌面/锁屏各一张，点卡片开聚合设置、闪电马上切一张
    private View slotCards;
    private TextView tvSlotHomeName, tvSlotHomeDesc, tvSlotLockName, tvSlotLockDesc;
    private ImageView imgSlotHomeThumb, imgSlotLockThumb;
    private View cardSlotHome, cardSlotLock;
    private View highlightedSlotCard;
    // 库行排序拖拽（长按行的空白处手动 startDrag 起来的那套）
    private ItemTouchHelper libDragHelper;
    // 长按头像拖出的浮动头像：跟随手指，松手落在范围卡片上 = 把该库设成那个范围
    private ImageView avatarFloat;
    private View avatarOrigin;
    private String avatarDragLibId;
    private float avatarGrabX, avatarGrabY, avatarLastRawX, avatarLastRawY;
    private int[] avatarContentLoc;
    // 拖起时缓存的 [桌面, 锁屏] 窗口矩形（left, top, right, bottom），避免每帧查位置
    private int[][] slotRects;
    // 检查更新那一行：状态文字 + App 内下载进度条（进度只在下载中轮询刷新，不下即时停）
    private TextView tvUpdateState;
    private ProgressBar pbUpdate;
    private final Handler updateTick = new Handler(Looper.getMainLooper());
    // 本次更新的目标（弹窗确认下载时记下）：下载失败后点这一行直接按原目标重下
    private String lastUpdateSource;
    private String lastUpdateVersion;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        // 全面屏/刘海屏适配：内容避开状态栏、刘海与底部手势条（Android 15 强制边到边）
        InsetsHelper.apply(this, R.id.main_root);
        // 左侧抽屉是独立面板（不在 main_root 里），也要避开状态栏与底部手势条
        InsetsHelper.apply(this, R.id.drawer_content);
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        // 注册系统相册多选（PickVisualMedia，无需存储权限）
        pickLauncher = registerForActivityResult(
                new ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK),
                this::onPicked);
        // 注册通知权限请求（Android 13+）：未授权时自动切换通知发不出来
        notifyPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                this::onNotifyPermissionResult);
        savePermLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                this::onSavePermissionResult);
        allFilesLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> onAllFilesAccessResult());
        overlayLauncher = registerForActivityResult(
                new ActivityResultContracts.PickVisualMedia(), this::onOverlayPicked);
        exportDirLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocumentTree(), this::onExportDirPicked);
        recycler = findViewById(R.id.recycler);
        libAdapter = new LibAdapter();
        wallpaperAdapter = new WallpaperAdapter();
        setupLibDeleteMenu();
        setupLibDrag();
        setupSlotCards();
        setupBackPressed();
        setupDrawer();
        setupButtons();
        setupNotifySwitch();
        setupStatusNotifySwitch();
        setupTransitionEffect();
        setupTickingSwitch();
        setupUpdateSource();
        setupUpdateCheck();
        // 电池优化引导（荣耀等机型避免后台被杀）
        maybePromptBattery();
        refreshVersion();
        // 首页 = 库列表
        showLibPage();
        setupSwipeToOpenDrawer();
        // 老版本缩略图（长边 256 的等比图）在两列方格上会被放大 2 倍多发虚：
        // 后台一次性重做成「中心正方形 + 按屏幕取边长」，做完清缓存重绑一次，这次启动就能看到清晰图
        new Thread(() -> {
            WallpaperStore.regenerateThumbs(getApplicationContext());
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                wallpaperAdapter.clearThumbs();
                libAdapter.clearThumbs();
                refreshCurrentPage();
            });
        }, "thumb-regen").start();
    }

    /**
     * 返回键/返回手势的分层处理：抽屉开着先关抽屉 → 有浮出图标先收起 →
     * 壁纸网格页回库列表 → 库列表页才交回系统（退出 App）。
     */
    private void setupBackPressed() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                DrawerLayout drawer = findViewById(R.id.drawer_layout);
                if (drawer != null && drawer.isDrawerOpen(Gravity.START)) {
                    drawer.closeDrawer(Gravity.START);
                    return;
                }
                if (libAdapter.isDeleteMode()) {
                    // 删除态：返回键 = 退出删除态（而不是退 App）
                    exitLibDeleteMode();
                    return;
                }
                if (wallpaperAdapter.hideRevealed()) {
                    return;
                }
                // 正在就地改名：返回 = 取消改名并收起输入法，而不是退出 App
                if (renamingLibId != null) {
                    cancelRename();
                    return;
                }
                if (showingWallpapers) {
                    showLibPage();
                    return;
                }
                // 库列表页：交回系统默认行为（退出 App）。默认动作若只是把任务切到后台、
                // 没销毁本实例（部分 ROM 如此），这个回调必须立刻恢复启用 —— 否则分层
                // 永久失效：下次在壁纸页按返回、或抽屉开着按返回，会跳过「收抽屉 /
                // 回库列表」直接退回桌面（冷启动又正常，所以时好时坏）。
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
                setEnabled(true);
            }
        });
    }

    /** 库列表页（首页）：☰ 开抽屉 + 固定标题 WallPaper + ＋ 新建库 + 顶栏删除按钮（长按行拖拽排序）。 */
    private void showLibPage() {
        showingWallpapers = false;
        exitLibDeleteMode();
        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(R.string.app_title);
        toolbar.setNavigationIcon(R.drawable.ic_menu);
        toolbar.setNavigationOnClickListener(v -> openDrawer());
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setAdapter(libAdapter);
        ((Button) findViewById(R.id.btn_add)).setText(R.string.lib_new);
        refreshLibs();
        refreshSlotCards();
        updateLibDeleteToolbar();
    }

    /** 壁纸网格页：← 返回库列表 + 库名 + ＋ 添加壁纸，两列正方形网格。 */
    private void showWallpaperPage(String libId) {
        LibraryStore.Library lib = LibraryStore.get(this, libId);
        if (lib == null) {
            return;
        }
        showingWallpapers = true;
        currentLibId = libId;
        // 离开库列表页：改名编辑态随被替换掉的视图一起消失，标记不能留脏值
        renamingLibId = null;
        prefs.edit().putString(LibraryStore.KEY_CURRENT_LIB, libId).apply();
        wallpaperAdapter.hideRevealed();
        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(lib.name);
        toolbar.setNavigationIcon(R.drawable.ic_back);
        toolbar.setNavigationOnClickListener(v -> showLibPage());
        recycler.setLayoutManager(new GridLayoutManager(this, 2));
        recycler.setAdapter(wallpaperAdapter);
        ((Button) findViewById(R.id.btn_add)).setText(R.string.add_wallpaper);
        refreshList();
        refreshSlotCards();
        // 删除按钮是库页专属，进壁纸页要收起来
        updateLibDeleteToolbar();
    }

    /** 刷新库列表与空状态。 */
    private void refreshLibs() {
        List<LibraryStore.Library> libs = LibraryStore.load(this);
        libAdapter.setItems(libs);
        updateEmptyState(libs.isEmpty(), R.string.empty_libs, R.string.empty_libs_hint);
    }

    /** 范围卡片接线（一次性）：卡片/齿轮 = 聚合设置弹窗；闪电 = 该范围马上切一张。 */
    private void setupSlotCards() {
        slotCards = findViewById(R.id.slot_cards);
        tvSlotHomeName = findViewById(R.id.tv_slot_home_name);
        tvSlotHomeDesc = findViewById(R.id.tv_slot_home_desc);
        tvSlotLockName = findViewById(R.id.tv_slot_lock_name);
        tvSlotLockDesc = findViewById(R.id.tv_slot_lock_desc);
        imgSlotHomeThumb = findViewById(R.id.img_slot_home_thumb);
        imgSlotLockThumb = findViewById(R.id.img_slot_lock_thumb);
        cardSlotHome = findViewById(R.id.card_slot_home);
        cardSlotLock = findViewById(R.id.card_slot_lock);
        attachCardGestures(cardSlotHome, true);
        attachCardGestures(cardSlotLock, false);
        findViewById(R.id.btn_slot_home_settings).setOnClickListener(v -> showSlotSettingsDialog(true));
        findViewById(R.id.btn_slot_lock_settings).setOnClickListener(v -> showSlotSettingsDialog(false));
        findViewById(R.id.btn_slot_home_switch).setOnClickListener(v -> slotSwitchNow(true));
        findViewById(R.id.btn_slot_lock_switch).setOnClickListener(v -> slotSwitchNow(false));
    }

    /**
     * 范围卡片的手势：单击 = 开聚合设置弹窗，双击 = 该范围马上切一张。
     * 两种手势得在这里自己分发：若让卡片照常响应点击，双击的第一下也会把弹窗顶出来。
     * 代价是单击开弹窗要等约 300ms（等系统确认这不是双击），区分单击/双击绕不开。
     */
    private void attachCardGestures(View card, boolean forHome) {
        GestureDetector detector = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onSingleTapConfirmed(MotionEvent e) {
                        showSlotSettingsDialog(forHome);
                        return true;
                    }

                    @Override
                    public boolean onDoubleTap(MotionEvent e) {
                        slotSwitchNow(forHome);
                        return true;
                    }
                });
        card.setOnTouchListener((v, event) -> {
            // 事件被这里消费后 View 不再自动维护按压态，手动置位否则点下去没有水波纹
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                v.setPressed(true);
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                v.setPressed(false);
            }
            return detector.onTouchEvent(event);
        });
    }

    /** 卡片上的「马上切换一张」：槽位没库先提示去卡片设置。 */
    private void slotSwitchNow(boolean forHome) {
        LibraryStore.Library lib = LibraryStore.slotLib(this, forHome);
        if (lib == null) {
            Toast.makeText(this, R.string.slot_none_toast, Toast.LENGTH_SHORT).show();
            return;
        }
        switchAndToast(lib, forHome);
    }

    /** 刷新两张范围卡片：范围名是固定小标题，下面两行跟着槽位走（未设置 / 库名 + 模式·间隔）。 */
    private void refreshSlotCards() {
        if (slotCards == null) {
            return;
        }
        slotCards.setVisibility(showingWallpapers ? View.GONE : View.VISIBLE);
        if (showingWallpapers) {
            return;
        }
        bindSlotCard(true, tvSlotHomeName, tvSlotHomeDesc, imgSlotHomeThumb);
        bindSlotCard(false, tvSlotLockName, tvSlotLockDesc, imgSlotLockThumb);
    }

    private void bindSlotCard(boolean forHome, TextView name, TextView desc, ImageView thumb) {
        int placeholder = forHome ? R.drawable.ic_home : R.drawable.ic_lock;
        LibraryStore.Library lib = LibraryStore.slotLib(this, forHome);
        if (lib == null) {
            name.setText(R.string.slot_none_name);
            desc.setText(R.string.slot_unset);
            bindThumb(thumb, null, placeholder, 30);
            return;
        }
        name.setText(lib.name);
        desc.setText(slotIntervalDisplay(forHome));
        bindThumb(thumb, slotThumbId(lib.id, forHome), placeholder, 30);
    }

    /**
     * 缩略图位统一绑定：有图清掉占位用的内边距与着色（不清会把真图按 SRC_IN 染成剪影），
     * 没图退回指定线性图标（按 padDp 居中）。回收复用时两条路径都显式设置，状态才不串。
     */
    private void bindThumb(ImageView view, String wallpaperId, int placeholderRes, int padDp) {
        if (view == null) {
            return;
        }
        Bitmap bmp = wallpaperId == null ? null : libAdapter.thumbFor(wallpaperId);
        if (bmp != null) {
            view.setPadding(0, 0, 0, 0);
            view.setImageTintList(null);
            view.setImageBitmap(bmp);
        } else {
            int pad = (int) (padDp * getResources().getDisplayMetrics().density);
            view.setPadding(pad, pad, pad, pad);
            view.setImageTintList(ColorStateList.valueOf(getColor(R.color.text_secondary)));
            view.setImageResource(placeholderRes);
        }
    }

    /** 某库某范围的代表图：该范围在屏的那张优先，其次另一范围，再次库内第一张；空库 null。 */
    private String slotThumbId(String libId, boolean forHome) {
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

    /** 刷新壁纸网格（当前库内的壁纸）与空状态。 */
    private void refreshList() {
        String libId = currentLibId();
        List<WallpaperStore.Item> items = libId == null
                ? new ArrayList<>() : WallpaperStore.loadByLib(this, libId);
        wallpaperAdapter.setItems(items);
        updateEmptyState(items.isEmpty(), R.string.empty_wallpapers, R.string.empty_wallpapers_hint);
    }

    /** 空状态显隐与文案（两页共用同一块视图）。 */
    private void updateEmptyState(boolean empty, int titleRes, int hintRes) {
        View emptyView = findViewById(R.id.empty_state);
        if (emptyView == null) {
            return;
        }
        ((TextView) findViewById(R.id.tv_empty_title)).setText(titleRes);
        ((TextView) findViewById(R.id.tv_empty_hint)).setText(hintRes);
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    /** 当前库 id（未初始化时从持久化恢复；指的是壁纸页正在看的库）。 */
    private String currentLibId() {
        if (currentLibId == null) {
            currentLibId = prefs.getString(LibraryStore.KEY_CURRENT_LIB, null);
        }
        return currentLibId;
    }

    /** 壁纸页正在看的库，可能为 null。 */
    private LibraryStore.Library currentLib() {
        String id = currentLibId();
        if (id == null) {
            return null;
        }
        return LibraryStore.get(this, id);
    }


    @Override
    protected void onResume() {
        super.onResume();
        // 自愈：每次回到应用都按当前设置重排定时（WorkManager 任务本身可自动恢复，这里兜底）
        TimerScheduler.scheduleAll(this);
        // 打开应用同样是“设备活跃”时机：后台补跑被 Doze/ROM 冻结而漏掉的定时切换
        new Thread(() -> {
            final boolean did = TimerScheduler.catchUp(getApplicationContext());
            if (did) {
                runOnUiThread(() -> {
                    if (!isFinishing()) {
                        refreshTimerStatus();
                        refreshCurrentPage();
                    }
                });
            }
        }, "app-catchup").start();
        // 同步刷新桌面小组件（库的启用状态、当前壁纸可能已变化）
        WidgetProvider.updateWidget(this);
        // 通知不跨重启存活、也可能被 ROM 清掉：回到应用时兜底补发/刷新常驻通知
        StatusNotifier.update(this);
        // 从系统选择器返回：用户没确认就把接管意图关掉（开关自动回关）
        refreshTakeoverUi();
        // 从安装页/安装授权页返回：更新那一行按当前下载状态重刷（可能已经下完待装，或包已装上）
        UpdateDownloader.pruneInstalled(this);
        bindUpdateState();
        List<String> pending = WallpaperStore.pendingInbox(this);
        if (!pending.isEmpty()) {
            // 还有待编辑项：继续逐张处理（目标库为空时编辑页自己回退到第一个库）
            openEdit(pending.get(0), currentLibId());
            return;
        }
        // 可能在裁剪页覆盖过图：缩略图缓存清掉再刷，避免显示旧图
        wallpaperAdapter.clearThumbs();
        libAdapter.clearThumbs();
        refreshCurrentPage();
    }

    /** 按当前所在页刷新：壁纸页先看库还在不在（可能已被长按删掉）。 */
    private void refreshCurrentPage() {
        if (showingWallpapers) {
            LibraryStore.Library lib = currentLib();
            if (lib == null) {
                showLibPage();
                return;
            }
            ((Toolbar) findViewById(R.id.toolbar)).setTitle(lib.name);
            refreshList();
        } else {
            refreshLibs();
        }
    }

    /** 左侧设置抽屉：顶栏 ☰ 打开（showLibPage 里绑定），右滑手势由 DrawerLayout 自带。 */
    private void setupDrawer() {
        DrawerLayout drawer = findViewById(R.id.drawer_layout);
        if (drawer == null) {
            return;
        }
        // 抽屉每次打开都同步一次全局设置状态
        drawer.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
            @Override
            public void onDrawerOpened(View drawerView) {
                refreshTimerStatus();
                refreshSlotCards();
                refreshBatteryRow();
                refreshLauncherOverlayRow();
                refreshExportRows();
                refreshSwitchLogRow();
                syncTakeoverAsync();
            }
        });
        // 「桌面图标预览底图」：未设置时点按直接选图；已设置时点按浮出查看/更换/清除，长按仍为快捷清除
        View overlayRow = findViewById(R.id.row_launcher_overlay);
        if (overlayRow != null) {
            overlayRow.setOnClickListener(v -> {
                if (LauncherPreviewOverlay.overlayFile(this).exists()) {
                    showLauncherOverlayMenu();
                } else {
                    pickLauncherOverlay();
                }
            });
            overlayRow.setOnLongClickListener(v -> {
                confirmClearLauncherOverlay();
                return true;
            });
        }
        // 导出目录：点按选/换一个文件夹（SAF），长按取消；下一行是「立即导出全部壁纸」
        View exportDirRow = findViewById(R.id.row_export_dir);
        if (exportDirRow != null) {
            exportDirRow.setOnClickListener(v -> exportDirLauncher.launch(null));
            exportDirRow.setOnLongClickListener(v -> {
                confirmClearExportDir();
                return true;
            });
        }
        View exportNowRow = findViewById(R.id.row_export_now);
        if (exportNowRow != null) {
            exportNowRow.setOnClickListener(v -> exportAllWallpapers());
        }
        // 保存当前壁纸：接管中导出引擎正在显示的那张；未接管备份系统壁纸（并记为可还原存档）
        View saveWallpaperRow = findViewById(R.id.row_save_wallpaper);
        if (saveWallpaperRow != null) {
            saveWallpaperRow.setOnClickListener(v -> startSaveCurrentWallpaper());
        }
        // 切换日志：点开直接读私有目录那个文件弹窗展示（不必先设导出目录再去文件管理器翻）；
        // 长按清空（老版本写的记录是旧格式，换新格式后一般想清掉重记）
        View logRow = findViewById(R.id.row_switch_log);
        if (logRow != null) {
            logRow.setOnClickListener(v -> showSwitchLog());
            logRow.setOnLongClickListener(v -> {
                confirmClearSwitchLog();
                return true;
            });
        }
    }

    /** 打开左侧设置抽屉（☰ 按钮、右滑手势与「去开接管」提示共用）。 */
    private void openDrawer() {
        DrawerLayout drawer = findViewById(R.id.drawer_layout);
        if (drawer != null) {
            drawer.openDrawer(Gravity.START);
        }
    }

    /**
     * 首页（库列表）整页右滑打开抽屉。
     * 光靠 DrawerLayout 自带的边缘手势在全面屏上很难用：屏幕左边缘那条已经被系统「返回」手势占了
     * （系统手势优先），手指稍往中间一点就拉不出抽屉，还会被当成点击进库。
     * 这里只**观察**手势（从不拦截），横向为主、方向向右、距离够就开抽屉；竖向滑动完全交给列表滚动。
     */
    private void setupSwipeToOpenDrawer() {
        final float threshold = 48 * getResources().getDisplayMetrics().density;
        recycler.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
            private float downX;
            private float downY;

            @Override
            public boolean onInterceptTouchEvent(RecyclerView view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getX();
                        downY = event.getY();
                        break;
                    case MotionEvent.ACTION_UP:
                        float dx = event.getX() - downX;
                        float dy = event.getY() - downY;
                        // 只在库列表页生效：壁纸页右滑的直觉是「回上一级」，不抢那个手势
                        if (!showingWallpapers && dx > threshold && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                            openDrawer();
                        }
                        break;
                    default:
                        break;
                }
                return false;
            }
        });
    }

    /** 版本号行：顶栏标题固定为 WallPaper，版本挪到抽屉顶部以便确认手机上装的是哪一版。 */
    private void refreshVersion() {
        TextView version = findViewById(R.id.tv_version);
        if (version == null) {
            return;
        }
        String name = null;
        try {
            name = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        if (name != null && !name.isEmpty()) {
            version.setText(getString(R.string.title_with_version, getString(R.string.app_name), name));
        }
    }

    /** 右下角 ＋（库页 = 新建库 / 壁纸页 = 添加壁纸）与电池行、接管开关。 */
    private void setupButtons() {
        findViewById(R.id.btn_add).setOnClickListener(v -> {
            if (showingWallpapers) {
                launchPicker();
            } else {
                showNewLibDialog();
            }
        });
        View batteryRow = findViewById(R.id.row_battery);
        if (batteryRow != null) {
            batteryRow.setOnClickListener(v -> requestIgnoreBattery());
        }
        // 顶部横幅的「启用」：拉起系统动态壁纸选择器（与 Muzei 的启用入口一致）
        View activate = findViewById(R.id.btn_activate);
        if (activate != null) {
            activate.setOnClickListener(v -> enableTakeover());
        }
        refreshTakeoverUi();
        refreshBatteryRow();
    }


    /**
     * 到点通知开关：自动切换（定时与补切）完成后发系统通知——成功静音留痕、失败弹横幅。
     * 打开时先确保通知可用，否则开关开了也看不到任何提示。
     */
    private void setupNotifySwitch() {
        CompoundButton swNotify = findViewById(R.id.sw_auto_notify);
        swNotify.setOnCheckedChangeListener(null);
        swNotify.setChecked(SwitchNotifier.isEnabled(this));
        swNotify.setOnCheckedChangeListener((buttonView, isChecked) -> {
            SwitchNotifier.setEnabled(MainActivity.this, isChecked);
            if (isChecked) {
                ensureNotificationsEnabled();
            }
        });
        // 默认开启且尚未授权时，首次打开应用请求一次通知权限（已请求过就不再打扰）
        if (SwitchNotifier.isEnabled(this) && !prefs.getBoolean(KEY_NOTIFY_PROMPTED, false)
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            prefs.edit().putBoolean(KEY_NOTIFY_PROMPTED, true).apply();
            notifyPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    /**
     * 常驻切换通知开关：通知栏常驻一条「音乐播放器样式」的状态通知（库名+壁纸+倒计时+上一张/下一张）。
     * 打开时同样先确保通知可用；打开后立刻按当前状态补发一条。
     */
    private void setupStatusNotifySwitch() {
        CompoundButton swStatus = findViewById(R.id.sw_status_notify);
        swStatus.setOnCheckedChangeListener(null);
        swStatus.setChecked(StatusNotifier.isEnabled(this));
        swStatus.setOnCheckedChangeListener((buttonView, isChecked) -> {
            StatusNotifier.setEnabled(MainActivity.this, isChecked);
            if (isChecked) {
                ensureNotificationsEnabled();
                StatusNotifier.update(this);
            }
        });
    }

    /**
     * 切换动画：引擎模式换壁纸时的过渡效果（淡入/模糊过渡/无动画），点行弹出单选。
     * 值存 settings（与引擎共用 {@link WallSwitchService#KEY_TRANSITION}），即时生效。
     */
    private void setupTransitionEffect() {
        LinearLayout row = findViewById(R.id.row_transition);
        TextView tv = findViewById(R.id.tv_transition_effect);
        String[] values = {
                WallSwitchService.TRANSITION_FADE,
                WallSwitchService.TRANSITION_BLUR,
                WallSwitchService.TRANSITION_OFF
        };
        String[] labels = {
                getString(R.string.transition_fade),
                getString(R.string.transition_blur),
                getString(R.string.transition_off)
        };
        Runnable refresh = () -> {
            String cur = WallSwitchService.transitionEffect(this);
            for (int i = 0; i < values.length; i++) {
                if (values[i].equals(cur)) {
                    tv.setText(labels[i]);
                    return;
                }
            }
            tv.setText(labels[0]);
        };
        refresh.run();
        row.setOnClickListener(v -> {
            String cur = WallSwitchService.transitionEffect(this);
            int checked = WallSwitchService.TRANSITION_OFF.equals(cur) ? 2
                    : WallSwitchService.TRANSITION_BLUR.equals(cur) ? 1 : 0;
            new AlertDialog.Builder(this)
                    .setTitle(R.string.transition_effect_title)
                    .setSingleChoiceItems(labels, checked, (d, which) -> {
                        prefs.edit().putString(WallSwitchService.KEY_TRANSITION, values[which]).apply();
                        refresh.run();
                        d.dismiss();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        });
    }

    /**
     * 更新源选择：设置页点「更新源」弹单选（Gitee 国内直连 / GitHub 需代理），存 prefs。
     * 检查更新与下载都按所选源走对应仓库的滚动 Release。
     */
    private void setupUpdateSource() {
        TextView tvSource = findViewById(R.id.tv_update_source);
        Runnable refresh = () -> tvSource.setText(
                UpdateChecker.SRC_GITHUB.equals(UpdateChecker.source(this))
                        ? R.string.update_source_github : R.string.update_source_gitee);
        refresh.run();
        findViewById(R.id.row_update_source).setOnClickListener(v -> {
            String cur = UpdateChecker.source(this);
            String[] values = {UpdateChecker.SRC_GITEE, UpdateChecker.SRC_GITHUB};
            String[] labels = {getString(R.string.update_source_gitee),
                    getString(R.string.update_source_github)};
            new AlertDialog.Builder(this)
                    .setTitle(R.string.update_source_title)
                    .setSingleChoiceItems(labels,
                            UpdateChecker.SRC_GITHUB.equals(cur) ? 1 : 0,
                            (d, which) -> {
                                UpdateChecker.setSource(this, values[which]);
                                refresh.run();
                                d.dismiss();
                            })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        });
    }

    /**
     * 检查更新：设置页手动入口 + 启动时静默检查（有新版才弹窗，失败不打扰）。
     * 下载走 App 内链路（{@link UpdateDownloader}）：进度条与「点按安装」都收在这一行上，
     * 不再挂系统下载器的通知——那条通知一被划掉，安装包就找不回来了。
     */
    private void setupUpdateCheck() {
        tvUpdateState = findViewById(R.id.tv_update_state);
        pbUpdate = findViewById(R.id.pb_update);
        findViewById(R.id.row_update).setOnClickListener(v -> onUpdateRowTapped());
        // 上回下的包已经装上了就清账，别让这一行一直挂着「点按安装」
        UpdateDownloader.pruneInstalled(this);
        bindUpdateState();
        // 启动静默检查：只在发现新版时打扰，失败静默（走当前所选源）
        UpdateChecker.checkAsync(this, (info, error) -> {
            if (error == null && info != null && !isFinishing() && !isDestroyed()
                    && UpdateChecker.isNewer(info.version, UpdateChecker.localVersion(this))) {
                showUpdateDialog(info, UpdateChecker.source(this));
            }
        });
    }

    /** 这一行点下去干什么，完全由下载状态决定：下载中不理、有包就装、其余去检查。 */
    private void onUpdateRowTapped() {
        int state = UpdateDownloader.state(this);
        if (state == UpdateDownloader.DOWNLOADING) {
            return;
        }
        if (state == UpdateDownloader.READY) {
            if (!UpdateDownloader.hasInstallPermission(this)) {
                UpdateDownloader.openInstallPermissionSettings(this);
                Toast.makeText(this, R.string.update_need_install_perm, Toast.LENGTH_LONG).show();
                return;
            }
            if (!UpdateDownloader.install(this)) {
                Toast.makeText(this, R.string.update_pkg_gone, Toast.LENGTH_SHORT).show();
                bindUpdateState();
            }
            return;
        }
        if (state == UpdateDownloader.FAILED && lastUpdateVersion != null) {
            // 上回下到一半失败：直接按原目标重下，不用再走一遍检查
            startUpdateDownload(lastUpdateSource, lastUpdateVersion);
            return;
        }
        checkUpdateNow();
    }

    /** 手动检查：结果只改这一行的文字，发现新版才弹窗。 */
    private void checkUpdateNow() {
        String source = UpdateChecker.source(this);
        tvUpdateState.setText(R.string.update_checking);
        UpdateChecker.checkAsync(this, source, (info, error) -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            if (error != null || info == null) {
                tvUpdateState.setText(R.string.update_check_failed);
                // 把具体死因亮给用户（网络不通/HTTP 错误一眼可辨），不再只有干巴巴的失败
                Toast.makeText(this, getString(R.string.update_check_failed)
                        + "：" + (error != null ? error.toString() : "无返回"), Toast.LENGTH_LONG).show();
                return;
            }
            if (!UpdateChecker.isNewer(info.version, UpdateChecker.localVersion(this))) {
                tvUpdateState.setText(R.string.update_latest);
                return;
            }
            tvUpdateState.setText(getString(R.string.update_new_title) + " v" + info.version);
            showUpdateDialog(info, source);
        });
    }

    /** 新版弹窗：确认后 App 内开始下载，下完再点这一行拉安装页。 */
    private void showUpdateDialog(UpdateChecker.Info info, String source) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.update_new_title)
                .setMessage(getString(R.string.update_new_msg,
                        info.version, UpdateChecker.localVersion(this)))
                .setPositiveButton(R.string.update_download,
                        (d, which) -> startUpdateDownload(source, info.version))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 记下目标版本后开下：进度条马上转起来，失败时这一行也认得该重下哪个版本。 */
    private void startUpdateDownload(String source, String version) {
        lastUpdateSource = source;
        lastUpdateVersion = version;
        UpdateDownloader.start(this, source, version);
        bindUpdateState();
    }

    /** 按下载状态刷这一行；只有下载中才留一个 300ms 的轮询，其余状态立刻停手（不白耗）。 */
    private void bindUpdateState() {
        int state = UpdateDownloader.state(this);
        updateTick.removeCallbacks(updatePoller);
        if (state == UpdateDownloader.DOWNLOADING) {
            pbUpdate.setVisibility(View.VISIBLE);
            pbUpdate.setProgress(UpdateDownloader.percent());
            tvUpdateState.setText(getString(R.string.update_downloading,
                    UpdateDownloader.percent()));
            updateTick.postDelayed(updatePoller, 300);
            return;
        }
        pbUpdate.setVisibility(View.GONE);
        if (state == UpdateDownloader.READY) {
            tvUpdateState.setText(getString(R.string.update_ready,
                    UpdateDownloader.readyVersion(this)));
        } else if (state == UpdateDownloader.FAILED) {
            tvUpdateState.setText(R.string.update_failed);
        } else {
            tvUpdateState.setText(getString(R.string.update_local_version,
                    UpdateChecker.localVersion(this)));
        }
    }

    /** 下载进度轮询：状态一离开下载中就收尾刷行，并告诉用户下一步是点这一行装。 */
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
                Toast.makeText(MainActivity.this, R.string.update_ready_toast,
                        Toast.LENGTH_LONG).show();
            } else if (state == UpdateDownloader.FAILED) {
                Toast.makeText(MainActivity.this, R.string.update_failed,
                        Toast.LENGTH_SHORT).show();
            }
        }
    };

    /**
     * 走秒倒计时开关：控制常驻通知与小组件里的时间显示样式（走秒 / 静态）。
     * 三处（本开关、通知、小组件）都走 {@link TimerScheduler#tickingCountdown} 同一个存取器，
     * 默认值也只有一处定义，避免出现"开关显示关、实际却在走秒"这类不一致。
     */
    private void setupTickingSwitch() {
        CompoundButton sw = findViewById(R.id.sw_ticking);
        if (sw == null) {
            return;
        }
        sw.setOnCheckedChangeListener(null);
        sw.setChecked(TimerScheduler.tickingCountdown(this));
        sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
            TimerScheduler.setTickingCountdown(MainActivity.this, isChecked);
            // 立即按新样式重建两处显示，免得"设置改了、通知/小组件还是旧样式"
            StatusNotifier.update(this);
            WidgetProvider.updateWidget(this);
        });
    }

    /** 确保通知可用：Android 13+ 申请权限；系统级关闭时提示并跳到通知设置页。 */
    private void ensureNotificationsEnabled() {
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
    private void onNotifyPermissionResult(boolean granted) {
        if (!granted) {
            Toast.makeText(this, R.string.notify_permission_denied, Toast.LENGTH_LONG).show();
        }
    }

    /** 存档权限申请结果：授权就继续保存当前壁纸，拒绝则放弃。 */
    private void onSavePermissionResult(boolean granted) {
        if (granted) {
            doSaveCurrentWallpaper();
        } else {
            Toast.makeText(this, "未授权读取壁纸，无法备份当前壁纸，已取消开启接管", Toast.LENGTH_LONG).show();
            refreshTakeoverUi();
        }
    }

    /** 跳转本应用的系统通知设置页（华为/荣耀在此重新允许通知）。 */
    private void openNotificationSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(intent);
        } catch (Exception ignored) {
        }
    }

    /** 新建库弹窗（库列表页右下角 ＋）。 */
    private void showNewLibDialog() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.lib_new)
                .setView(wrapInput(input, R.string.lib_name_hint))
                .setPositiveButton(R.string.confirm, (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    LibraryStore.create(MainActivity.this, name);
                    refreshLibs();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /**
     * 顶栏删除按钮：只给库页用（壁纸页隐藏）。普通态是灰色垃圾桶，点一下进删除态；
     * 删除态变勾图标，点击 = 对勾选项二次确认。菜单用代码加（本地类型检查的 R 桩不含 menu 资源）。
     */
    private void setupLibDeleteMenu() {
        Toolbar toolbar = findViewById(R.id.toolbar);
        libDeleteItem = toolbar.getMenu().add(0, 0, 0, R.string.lib_delete_mode_title);
        libDeleteItem.setIcon(R.drawable.ic_delete);
        libDeleteItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        libDeleteItem.setVisible(false);
        libDeleteItem.setOnMenuItemClickListener(item -> {
            if (libAdapter.isDeleteMode()) {
                confirmDeleteSelectedLibs();
            } else {
                enterLibDeleteMode();
            }
            return true;
        });
    }

    /**
     * 库行拖拽（v3.61 起分两种起手，都由调用方手动 startDrag，不用 ItemTouchHelper 的自动长按）：
     * 1) 长按行的空白/文字区 = 排序，落位后把新顺序写回 libraries.json；
     * 2) 长按行的头像 = 拖出半透明浮动头像，松手落在哪张范围卡片上就把该库设成那个范围。
     * 分工靠「谁是这次的触摸目标」实现：头像自己吃掉 DOWN，长按就只触发它的回调，不会连带触发行排序。
     */
    private void setupLibDrag() {
        libDragHelper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            @Override
            public boolean isLongPressDragEnabled() {
                // 关掉自动长按：排序改由库行的长按回调手动 startDrag，头像那条长按走浮动头像
                return false;
            }

            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView,
                                  @NonNull RecyclerView.ViewHolder from,
                                  @NonNull RecyclerView.ViewHolder to) {
                if (showingWallpapers) {
                    return false;
                }
                return libAdapter.move(from.getBindingAdapterPosition(), to.getBindingAdapterPosition());
            }

            @Override
            public void clearView(@NonNull RecyclerView recyclerView,
                                  @NonNull RecyclerView.ViewHolder viewHolder) {
                super.clearView(recyclerView, viewHolder);
                if (!showingWallpapers) {
                    LibraryStore.reorder(MainActivity.this, libAdapter.currentIds());
                }
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
            }
        });
        libDragHelper.attachToRecyclerView(recycler);
    }

    /**
     * 长按头像起手：把头像复制成一个半透明浮动图钉在内容层上，跟着手指走；
     * 原头像压暗表示「被拿起来了」，命中的范围卡片降透明度表示「松手就给我」。
     */
    private void startAvatarDrag(LibHolder holder, String libId) {
        Drawable icon = holder.imgThumb.getDrawable();
        if (icon == null || libId == null) {
            return;
        }
        ViewGroup content = findViewById(android.R.id.content);
        int[] thumbLoc = new int[2];
        int[] contentLoc = new int[2];
        holder.imgThumb.getLocationInWindow(thumbLoc);
        content.getLocationInWindow(contentLoc);
        avatarContentLoc = contentLoc;
        avatarOrigin = holder.imgThumb;
        avatarOrigin.setAlpha(0.3f);
        avatarDragLibId = libId;
        avatarGrabX = avatarLastRawX - thumbLoc[0];
        avatarGrabY = avatarLastRawY - thumbLoc[1];
        ImageView floatView = new ImageView(this);
        floatView.setImageDrawable(icon);
        floatView.setAlpha(0.75f);
        content.addView(floatView, new ViewGroup.LayoutParams(
                holder.imgThumb.getWidth(), holder.imgThumb.getHeight()));
        floatView.setX(thumbLoc[0] - contentLoc[0]);
        floatView.setY(thumbLoc[1] - contentLoc[1]);
        avatarFloat = floatView;
        cacheSlotCardRects();
        // 拖拽期间别让列表抢走手势：否则手指一动就是滚列表，浮动头像会断流
        recycler.requestDisallowInterceptTouchEvent(true);
    }

    /** 浮动头像跟随手指（只在拖起来之后调用）。 */
    private void moveAvatarFloat(float rawX, float rawY) {
        avatarFloat.setX(rawX - avatarContentLoc[0] - avatarGrabX);
        avatarFloat.setY(rawY - avatarContentLoc[1] - avatarGrabY);
        highlightSlotCard(slotCardAt(rawX, rawY));
    }

    /** 松手收尾：命中卡片就走二次确认，然后清掉浮动头像与各种临时状态。 */
    private void finishAvatarDrag(boolean commit, float rawX, float rawY) {
        String libId = avatarDragLibId;
        View card = commit ? slotCardAt(rawX, rawY) : null;
        if (avatarFloat != null) {
            ((ViewGroup) avatarFloat.getParent()).removeView(avatarFloat);
            avatarFloat = null;
        }
        if (avatarOrigin != null) {
            avatarOrigin.setAlpha(1f);
            avatarOrigin = null;
        }
        avatarDragLibId = null;
        highlightSlotCard(null);
        recycler.requestDisallowInterceptTouchEvent(false);
        if (card == null || libId == null) {
            return;
        }
        boolean forHome = card == cardSlotHome;
        if (LibraryStore.ownsScope(this, libId, forHome)) {
            Toast.makeText(this, R.string.slot_already_set, Toast.LENGTH_SHORT).show();
            return;
        }
        confirmSlotLib(forHome, libId, null);
    }

    /** 拖起来时缓存两张卡片的窗口矩形，之后每帧只比数值，不再逐事件查位置。 */
    private void cacheSlotCardRects() {
        slotRects = new int[][]{cardWindowRect(cardSlotHome), cardWindowRect(cardSlotLock)};
    }

    private int[] cardWindowRect(View card) {
        if (card == null || card.getVisibility() != View.VISIBLE) {
            return null;
        }
        int[] loc = new int[2];
        card.getLocationInWindow(loc);
        return new int[]{loc[0], loc[1], loc[0] + card.getWidth(), loc[1] + card.getHeight()};
    }

    /** 手指落点命中哪张范围卡片（浮动头像松手时用）。 */
    private View slotCardAt(float rawX, float rawY) {
        if (slotRects == null) {
            return null;
        }
        for (int i = 0; i < slotRects.length; i++) {
            int[] r = slotRects[i];
            if (r != null && rawX >= r[0] && rawX <= r[2] && rawY >= r[1] && rawY <= r[3]) {
                return i == 0 ? cardSlotHome : cardSlotLock;
            }
        }
        return null;
    }

    /** 拖到卡片上时给该卡片半透明高亮（只在目标变化时改属性，别每个 MOVE 事件都刷一遍）。 */
    private void highlightSlotCard(View card) {
        if (highlightedSlotCard == card) {
            return;
        }
        if (highlightedSlotCard != null) {
            highlightedSlotCard.setAlpha(1f);
        }
        highlightedSlotCard = card;
        if (card != null) {
            card.setAlpha(0.6f);
        }
    }

    private void enterLibDeleteMode() {
        libAdapter.setDeleteMode(true);
        findViewById(R.id.btn_add).setVisibility(View.GONE);
        updateLibDeleteToolbar();
    }

    private void exitLibDeleteMode() {
        if (!libAdapter.isDeleteMode()) {
            return;
        }
        libAdapter.setDeleteMode(false);
        findViewById(R.id.btn_add).setVisibility(View.VISIBLE);
        updateLibDeleteToolbar();
    }

    /** 顶栏随页面/删除态刷新：壁纸页隐藏按钮；删除态换勾图标，标题显示已选数。 */
    private void updateLibDeleteToolbar() {
        if (libDeleteItem == null) {
            return;
        }
        Toolbar toolbar = findViewById(R.id.toolbar);
        boolean deleteMode = libAdapter.isDeleteMode();
        libDeleteItem.setVisible(!showingWallpapers);
        libDeleteItem.setIcon(deleteMode ? R.drawable.ic_check : R.drawable.ic_delete);
        libDeleteItem.setTitle(getString(
                deleteMode ? R.string.lib_delete_confirm : R.string.lib_delete_mode_title));
        if (!showingWallpapers) {
            toolbar.setTitle(deleteMode
                    ? getString(R.string.lib_delete_mode_count, libAdapter.selectedCount())
                    : getString(R.string.app_title));
        }
    }

    /**
     * 删除态点勾：先弹二次确认（文案写明库数与总壁纸数，确认按钮统一红色垃圾桶图标），
     * 确认后逐库删除。没勾选时只提示不动作。
     */
    private void confirmDeleteSelectedLibs() {
        List<LibraryStore.Library> selected = libAdapter.selectedLibs();
        if (selected.isEmpty()) {
            Toast.makeText(this, R.string.lib_delete_none, Toast.LENGTH_SHORT).show();
            return;
        }
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.lib_delete_batch_title, selected.size()))
                .setMessage(R.string.lib_delete_batch_msg)
                .setPositiveButton(" ", (d, which) -> deleteLibs(selected))
                .setNegativeButton(R.string.cancel, null)
                .show();
        // 确认按钮统一红色垃圾桶图标（沿用原删库确认的样式）
        Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        positive.setText("");
        positive.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_delete, 0, 0, 0);
        positive.setCompoundDrawableTintList(ColorStateList.valueOf(getColor(R.color.danger)));
    }

    /** 批量删库：连带清库内壁纸文件、切换进度与定时任务（都在 LibraryStore.delete 内部）。 */
    private void deleteLibs(List<LibraryStore.Library> libs) {
        boolean removedCurrent = false;
        for (LibraryStore.Library lib : libs) {
            LibraryStore.delete(this, lib.id);
            if (lib.id.equals(currentLibId())) {
                removedCurrent = true;
            }
        }
        if (removedCurrent) {
            currentLibId = null;
            prefs.edit().remove(LibraryStore.KEY_CURRENT_LIB).apply();
        }
        exitLibDeleteMode();
        refreshLibs();
        refreshTimerStatus();
        // 删除启用中的库会改变「谁在被接管」，同步一次接管状态
        syncTakeoverAsync();
    }


    /**
     * 范围卡片齿轮：本范围的聚合设置弹窗 —— 壁纸库（点条目弹出带缩略图的列表）、切换模式（顺序/随机）、
     * 切换间隔（整行可点，弹时/分滚轮）。改动即保存；槽位写入都在 LibraryStore 里。
     */
    private void showSlotSettingsDialog(final boolean forHome) {
        final View content = LayoutInflater.from(this).inflate(R.layout.dialog_slot_settings, null, false);
        bindSlotLibRow(content, forHome);
        content.findViewById(R.id.row_slot_lib).setOnClickListener(
                v -> showLibPickerDialog(forHome, content));
        RadioGroup rgMode = content.findViewById(R.id.rg_mode);
        final TextView tvInterval = content.findViewById(R.id.tv_interval);
        tvInterval.setText(slotIntervalDisplay(forHome));
        new MaterialAlertDialogBuilder(this)
                .setTitle(forHome ? R.string.slot_home_title : R.string.slot_lock_title)
                .setView(content)
                .setNegativeButton(R.string.close, null)
                .show();
        // 回填模式（先 check 再挂监听，程序化 check 不触发写回）
        rgMode.check(LibraryStore.MODE_RANDOM.equals(LibraryStore.scopeMode(this, forHome))
                ? R.id.rb_random : R.id.rb_order);
        // 模式只影响卡片副标题（间隔不变、定时不动），不必重排定时也不必重绘库列表
        rgMode.setOnCheckedChangeListener((group, checkedId) -> {
            LibraryStore.setScopeMode(MainActivity.this, forHome,
                    checkedId == R.id.rb_random
                            ? LibraryStore.MODE_RANDOM : LibraryStore.MODE_ORDER);
            refreshSlotCards();
        });
        // 切换间隔：整行可点，弹时/分滚轮
        content.findViewById(R.id.row_interval).setOnClickListener(
                v -> showIntervalPicker(forHome, tvInterval));
    }

    /** 弹窗里「当前库」那一行的回填（选中库后要重新绑定，缩略图跟着换）。 */
    private void bindSlotLibRow(View content, boolean forHome) {
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
    private void showLibPickerDialog(final boolean forHome, final View settingsContent) {
        final List<LibraryStore.Library> libs = LibraryStore.load(this);
        new MaterialAlertDialogBuilder(this)
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
    private void applySlotLib(boolean forHome, String libId) {
        LibraryStore.setSlotLib(this, forHome, libId);
        libAdapter.refreshBadges();
        refreshTimerStatus();
        refreshSlotCards();
        syncTakeoverAsync();
    }

    /** 选库的二次确认：中性文案说明要改成哪个库（清空那档另说一句会停止切换）。 */
    private void confirmSlotLib(final boolean forHome, final String newLibId,
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
        new MaterialAlertDialogBuilder(this)
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

    /** 该范围的设置摘要行：模式 · 每 X（卡片与弹窗共用）。 */
    private String slotIntervalDisplay(boolean forHome) {
        boolean random = LibraryStore.MODE_RANDOM.equals(LibraryStore.scopeMode(this, forHome));
        return getString(random ? R.string.mode_random : R.string.mode_order)
                + " · " + getString(R.string.slot_every_prefix)
                + formatInterval(LibraryStore.scopeIntervalSeconds(this, forHome));
    }

    /**
     * 间隔滚轮选择器（时/分上下滑）：分钟按 15 步进（00/15/30/45），下限 15 分钟、上限 23:45。
     */
    private void showIntervalPicker(final boolean forHome, final TextView tvInterval) {
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
        new MaterialAlertDialogBuilder(this)
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

    /** 滚轮选择器的一列：上下滚轮 + 底部「时/分」标签。 */
    private LinearLayout wheelColumn(NumberPicker picker, String label) {
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

    /** 间隔展示：60 的整数倍显示分钟，其余显示「X分Y秒」或「X秒」（兼容历史秒级值）。 */
    private String formatInterval(int seconds) {
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
    private void switchAndToast(final LibraryStore.Library lib, final boolean forHome) {
        final Context app = getApplicationContext();
        new Thread(() -> {
            final boolean ok = Switcher.next(app, lib.id, forHome);
            runOnUiThread(() -> {
                if (ok) {
                    Toast.makeText(this, R.string.switch_done, Toast.LENGTH_SHORT).show();
                    // 切完锁屏后「锁屏：WallPaper / 系统」这行会变，立刻刷新，别等下次进应用
                    refreshTakeoverStatus();
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
    private void refreshTimerStatus() {
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
    private String scopeTimerLine(boolean forHome) {
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
    private String formatClock(long millis) {
        return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(millis));
    }

    /** 电池优化白名单行的状态：直接显示当前是否已允许（决定后台定时能否被系统唤醒）。 */
    private void refreshBatteryRow() {
        TextView tv = findViewById(R.id.tv_battery);
        if (tv != null) {
            tv.setText(isIgnoringBatteryOptimizations()
                    ? R.string.battery_state_on : R.string.battery_state_off);
        }
    }

    /** 是否已允许忽略电池优化。 */
    private boolean isIgnoringBatteryOptimizations() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    /** 检查电池优化白名单：未忽略且未提示过时弹一次引导。 */
    private void maybePromptBattery() {
        if (isIgnoringBatteryOptimizations()) {
            return;
        }
        if (prefs.getBoolean("battery_prompted", false)) {
            return;
        }
        prefs.edit().putBoolean("battery_prompted", true).apply();
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.battery_prompt_title)
                .setMessage(R.string.battery_prompt_msg)
                .setPositiveButton(R.string.confirm, (dialog, which) -> requestIgnoreBattery())
                // 荣耀/华为还需在应用详情里开启「自启动/后台运行」
                .setNeutralButton(R.string.app_details, (dialog, which) -> openAppDetails())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** 打开本应用的系统详情页（荣耀/华为在此开启自启动、后台运行白名单）。 */
    private void openAppDetails() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception ignored) {
        }
    }

    /** 跳转系统「忽略电池优化」授权页（个别机型不支持该 action 时静默）。 */
    private void requestIgnoreBattery() {
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception ignored) {
        }
    }


    /** 打开系统相册多选。 */
    private void launchPicker() {
        PickVisualMediaRequest.Builder builder = new PickVisualMediaRequest.Builder();
        builder.setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE);
        pickLauncher.launch(builder.build());
    }

    /**
     * 多选回调：后台线程逐张复制进收件箱（主线程同步复制多张大图易卡顿、易瞬断失败），
     * 完成后按真实成功/失败数量提示，并打开第一张待编辑项。
     */
    private void onPicked(List<Uri> uris) {
        if (uris == null || uris.isEmpty()) {
            return;
        }
        View btnAdd = findViewById(R.id.btn_add);
        btnAdd.setEnabled(false);
        new Thread(() -> {
            int success = 0;
            int failed = 0;
            for (Uri uri : uris) {
                try {
                    WallpaperStore.importToInbox(this, uri);
                    success++;
                } catch (Exception e) {
                    failed++;
                }
            }
            List<String> pending = WallpaperStore.pendingInbox(this);
            // lambda 捕获要求实际 final：先把计数定稿
            final int okCount = success;
            final int failCount = failed;
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
                if (!pending.isEmpty()) {
                    openEdit(pending.get(0), currentLibId());
                }
            });
        }, "inbox-import").start();
    }

    /** 打开编辑页处理某个收件箱文件（确认后入库到指定库）。 */
    private void openEdit(String inboxId, String libId) {
        Intent intent = new Intent(this, EditActivity.class);
        intent.putExtra(EditActivity.EXTRA_INBOX_ID, inboxId);
        intent.putExtra(EditActivity.EXTRA_LIB_ID, libId);
        startActivity(intent);
    }

    /** 打开编辑页重新裁剪一张已入库壁纸（长按壁纸格的铅笔；确认后覆盖原图）。 */
    private void openEditItem(WallpaperStore.Item item) {
        Intent intent = new Intent(this, EditActivity.class);
        intent.putExtra(EditActivity.EXTRA_ITEM_ID, item.id);
        startActivity(intent);
    }

    /**
     * 刷新接管相关界面（对齐 Muzei：没有开关，状态即系统真值）。
     * 顶部横幅在"未启用"时给出启用入口；已启用但没有启用库时提示会显示纯色。
     * 状态行读系统真值，所以用户在系统设置里换了壁纸后回到应用就会自动反映。
     */
    private void refreshTakeoverUi() {
        boolean active = TakeoverManager.isHomeTakenOver(this);
        TextView state = findViewById(R.id.tv_takeover_state);
        if (state != null) {
            state.setText(active ? R.string.takeover_state_on : R.string.takeover_state_off);
            state.setTextColor(getColor(active ? R.color.brand : R.color.text_secondary));
        }
        View info = findViewById(R.id.iv_takeover_info);
        if (info != null) {
            info.setOnClickListener(v -> showTakeoverInfoDialog());
        }
        View banner = findViewById(R.id.banner_takeover);
        TextView bannerText = findViewById(R.id.tv_banner_text);
        View activate = findViewById(R.id.btn_activate);
        if (banner != null && bannerText != null) {
            LibraryStore.Library homeLib = LibraryStore.slotLib(this, true);
            if (!active) {
                banner.setVisibility(View.VISIBLE);
                bannerText.setText(R.string.banner_not_activated);
                if (activate != null) {
                    activate.setVisibility(View.VISIBLE);
                }
            } else if (homeLib == null) {
                banner.setVisibility(View.VISIBLE);
                bannerText.setText(R.string.banner_no_library);
                // 这种情况要去的是库列表（就在下面），不需要"启用"按钮
                if (activate != null) {
                    activate.setVisibility(View.GONE);
                }
            } else {
                banner.setVisibility(View.GONE);
            }
        }
        refreshTakeoverStatus();
    }

    /** 关闭接管的唯一途径说明（点感叹号图标时弹）。 */
    private void showTakeoverInfoDialog() {
        new MaterialAlertDialogBuilder(this)
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
    private void enableTakeover() {
        WallSwitchService.openActivator(this);
    }

    /** 跳系统设置页开「所有文件访问」；部分 ROM 不支持直达本 App 时退回总列表页。 */
    private void requestAllFilesAccess() {
        Toast.makeText(this, "需要「所有文件访问」权限来读取当前系统壁纸，请允许后返回", Toast.LENGTH_LONG).show();
        try {
            allFilesLauncher.launch(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            allFilesLauncher.launch(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
    }

    /** 从「所有文件访问」设置页返回：已开启就继续保存当前壁纸，没开就取消。 */
    private void onAllFilesAccessResult() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            doSaveCurrentWallpaper();
        } else {
            Toast.makeText(this, "未开启「所有文件访问」，无法读取当前系统壁纸", Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 抽屉「保存当前壁纸」入口：接管中不需要权限（图就在库里），
     * 未接管要读系统壁纸，需要「所有文件访问」（Android 11+）/ 读存储权限（更低版本）。
     */
    private void startSaveCurrentWallpaper() {
        if (TakeoverManager.isHomeTakenOver(this)) {
            doSaveCurrentWallpaper();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 13+ 的照片权限折算不了 WallpaperManager 要的 READ_EXTERNAL_STORAGE
            // （真机实测 MagicOS），只能走「所有文件访问」隐式获得（v3.28 实验证实必需）
            if (!Environment.isExternalStorageManager()) {
                requestAllFilesAccess();
                return;
            }
            doSaveCurrentWallpaper();
            return;
        }
        String perm = Manifest.permission.READ_EXTERNAL_STORAGE;
        if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
            savePermLauncher.launch(perm);
            return;
        }
        doSaveCurrentWallpaper();
    }

    /** 后台执行保存，全程盖不可关闭的进度弹窗，结果用 Toast 告知。 */
    private void doSaveCurrentWallpaper() {
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
    private void showProgress(int titleRes, int msgRes) {
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
        takeoverProgress = new MaterialAlertDialogBuilder(this)
                .setTitle(titleRes)
                .setView(box)
                .setCancelable(false)
                .create();
        takeoverProgress.show();
    }

    /** 收起进度弹窗（容错：Activity 已销毁时 dismiss 会抛，吞掉即可）。 */
    private void dismissTakeoverProgress() {
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
    private void syncTakeoverAsync() {
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
     * 接管情况（系统真值）：桌面看引擎是否生效；锁屏三态 —— 设过独立锁屏壁纸 → WallPaper，
     * 否则引擎激活（顺带盖住锁屏）→ WallPaper，否则 → 系统。取值只写 WallPaper / 系统。
     */
    private void refreshTakeoverStatus() {
        setScopeStatus(R.id.tv_takeover_home, TakeoverManager.isHomeTakenOver(this));
        boolean lockByApp = TakeoverManager.isLockTakenOver(this)
                || TakeoverManager.isLockTakenByEngine(this);
        setScopeStatus(R.id.tv_takeover_lock, lockByApp);
    }

    /** 一行范围状态：值只写 WallPaper / 系统（WallPaper 用主色，系统用次级灰）。 */
    private void setScopeStatus(int viewId, boolean byApp) {
        TextView tv = findViewById(viewId);
        if (tv == null) {
            return;
        }
        tv.setText(byApp ? R.string.takeover_by_app : R.string.takeover_by_system);
        tv.setTextColor(getColor(byApp ? R.color.brand : R.color.text_secondary));
    }

    /** 选一张首页截图作为全局「桌面图标预览底图」（抠图后存应用目录，之后不用再选）。 */
    private void pickLauncherOverlay() {
        PickVisualMediaRequest.Builder builder = new PickVisualMediaRequest.Builder();
        builder.setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE);
        overlayLauncher.launch(builder.build());
    }

    /** 选好后的处理：后台抠图（把截图自带的壁纸变透明）→ 存成全局底图 → 回显状态。 */
    private void onOverlayPicked(Uri uri) {
        if (uri == null) {
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
            });
        }, "overlay-key").start();
    }

    /** 清除全局预览底图（抽屉里长按）。 */
    private void confirmClearLauncherOverlay() {
        if (!LauncherPreviewOverlay.overlayFile(this).exists()) {
            Toast.makeText(this, R.string.launcher_overlay_unset, Toast.LENGTH_SHORT).show();
            return;
        }
        new MaterialAlertDialogBuilder(this)
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
    private void showLauncherOverlayMenu() {
        new MaterialAlertDialogBuilder(this)
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
    private void showLauncherOverlayPreview() {
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
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
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

    /** 抽屉里那行的状态文案：已设置 / 未设置。 */
    private void refreshLauncherOverlayRow() {
        TextView state = findViewById(R.id.tv_launcher_overlay);
        if (state == null) {
            return;
        }
        state.setText(LauncherPreviewOverlay.overlayFile(this).exists()
                ? R.string.launcher_overlay_set : R.string.launcher_overlay_unset);
    }


    /** 选一个导出目录（SAF）：选完记住并申请持久化授权。 */
    private void onExportDirPicked(Uri uri) {
        if (uri == null) {
            return;
        }
        WallpaperExporter.setTreeUri(this, uri);
        refreshExportRows();
        Toast.makeText(this, R.string.export_dir_set, Toast.LENGTH_SHORT).show();
    }

    /** 取消导出目录（不清除已经导出的文件）。 */
    private void confirmClearExportDir() {
        if (!WallpaperExporter.isConfigured(this)) {
            Toast.makeText(this, R.string.export_dir_unset, Toast.LENGTH_SHORT).show();
            return;
        }
        new MaterialAlertDialogBuilder(this)
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
    private void exportAllWallpapers() {
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

    /** 导出目录那行的状态回显。 */
    private void refreshExportRows() {
        TextView state = findViewById(R.id.tv_export_dir);
        if (state == null) {
            return;
        }
        state.setText(WallpaperExporter.isConfigured(this)
                ? getString(R.string.export_dir_set_state, WallpaperExporter.displayName(this))
                : getString(R.string.export_dir_unset));
    }

    /** 切换日志那行的状态回显：最近一条记录的时间（没有则提示还没有记录）。纯读文件，放后台线程。 */
    /** 日志行长按：清空全部记录（老格式记录不会自动改写，留着一堆旧格式照样难看）。 */
    private void confirmClearSwitchLog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.log_clear_title)
                .setMessage(R.string.log_clear_msg)
                .setPositiveButton(R.string.confirm, (dialog, which) -> new Thread(() -> {
                    SwitchLog.clear(getApplicationContext());
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        Toast.makeText(this, R.string.log_clear_done, Toast.LENGTH_SHORT).show();
                        refreshSwitchLogRow();
                    });
                }, "log-clear").start())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void refreshSwitchLogRow() {
        final TextView state = findViewById(R.id.tv_switch_log);
        if (state == null) {
            return;
        }
        new Thread(() -> {
            final String latest = SwitchLog.latestTimeLabel(getApplicationContext());
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                state.setText(latest == null
                        ? getString(R.string.log_view_empty_row)
                        : getString(R.string.log_view_latest, latest));
            });
        }, "log-row").start();
    }

    /** 读日志文件并弹窗展示。纯 IO 放后台线程，读完回主线程弹。 */
    private void showSwitchLog() {
        new Thread(() -> {
            final String content = SwitchLog.readAllText(getApplicationContext());
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                showSwitchLogDialog(content);
            });
        }, "log-read").start();
    }

    /**
     * 日志弹窗：可滚动、可长按选中的等宽正文；非空时给一个「复制」，
     * 方便直接粘到聊天里（比导出到文件夹再翻文件管理器省事）。
     */
    private void showSwitchLogDialog(String content) {
        final boolean empty = content == null || content.trim().isEmpty();
        TextView body = new TextView(this);
        body.setText(empty ? getString(R.string.log_view_empty) : content);
        body.setTextSize(12f);
        body.setTextIsSelectable(true);
        body.setTypeface(Typeface.MONOSPACE);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        body.setPadding(pad, pad, pad, pad);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        // 固定高度上限，日志长了在弹窗内部滚动，不会把弹窗撑到超出屏幕
        int maxHeight = (int) (getResources().getDisplayMetrics().heightPixels * 0.6f);
        scroll.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, maxHeight));

        // 用父类型接收：MaterialAlertDialogBuilder 只对 setTitle/setView 做了协变覆盖，
        // setNegativeButton 继承自 AlertDialog.Builder，链式表达式静态类型是 Builder
        AlertDialog.Builder builder = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.log_view_title)
                .setView(scroll)
                .setNegativeButton(R.string.cancel, null);
        if (!empty) {
            builder.setNeutralButton(R.string.log_view_copy, (dialog, which) -> copySwitchLog(content));
        }
        builder.show();
    }

    /** 复制日志全文到剪贴板。 */
    private void copySwitchLog(String content) {
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


    /**
     * 点击壁纸格：后台解码库内全图并弹窗预览。
     * 预览按图片原始比例整张显示（不裁剪、不补黑边，超高可滚动），
     * 下方显示「标题 + 该文件在库中的真实像素尺寸」——用于判断库里存的到底是一张完整图，
     * 还是被裁过/比例不对（例如只有屏幕上那一块）的结果图。
     * 弹窗里的标题可直接点击就地改名（通知会带上这个标题）。
     */
    private void showPreview(WallpaperStore.Item item) {
        View content = LayoutInflater.from(this).inflate(R.layout.dialog_preview, null, false);
        ImageView preview = content.findViewById(R.id.img_preview);
        TextView info = content.findViewById(R.id.tv_preview_info);
        final TextView titleView = content.findViewById(R.id.tv_preview_title);
        final EditText titleInput = content.findViewById(R.id.et_preview_title);
        titleView.setText(itemTitle(item));
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(content)
                .setPositiveButton(R.string.close, null)
                .create();
        // 点标题（或铅笔）→ 与库行一致的就地改名：TextView 换成 EditText，回车或失焦提交
        View.OnClickListener startRename = v -> {
            titleView.setVisibility(View.GONE);
            titleInput.setVisibility(View.VISIBLE);
            titleInput.setText(item.title == null ? "" : item.title);
            titleInput.setSelection(titleInput.getText().length());
            titleInput.requestFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(titleInput, InputMethodManager.SHOW_IMPLICIT);
            }
        };
        titleView.setOnClickListener(startRename);
        content.findViewById(R.id.btn_preview_rename).setOnClickListener(startRename);
        titleInput.setOnEditorActionListener((v, actionId, event) -> {
            commitPreviewTitle(item, titleView, titleInput);
            return true;
        });
        titleInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                commitPreviewTitle(item, titleView, titleInput);
            }
        });
        dialog.show();
        // 大图解码要几百毫秒，放后台线程，避免点一下卡住列表
        new Thread(() -> {
            File file = WallpaperStore.getFullFile(this, item.id);
            // 只读图片头拿真实尺寸：预览图会被限制在 2048 内，不能代表库内实际保存的尺寸
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            final int width = bounds.outWidth;
            final int height = bounds.outHeight;
            final Bitmap bitmap = WallpaperStore.decodeBounded(file, WallpaperStore.maxWallpaperDim(this));
            runOnUiThread(() -> {
                // 解码期间弹窗可能已被关闭，或页面已退出：不再回填
                if (isFinishing() || isDestroyed() || !dialog.isShowing()) {
                    return;
                }
                if (bitmap == null || width <= 0 || height <= 0) {
                    info.setText(R.string.preview_failed);
                    return;
                }
                preview.setImageBitmap(bitmap);
                info.setText(getString(R.string.preview_info, width, height));
            });
        }, "thumb-preview").start();
    }

    /**
     * 库内长按浮出的「设为首页」：把这张壁纸立刻设为**桌面**壁纸，并把该库的桌面指针移到这里
     * （之后自动切换就从它往下走）。库没启用桌面范围时只提示，不静默改范围 ——
     * 改范围会连带停用同范围的库，那种事该由用户在库设置里决定。
     * 含引擎通知 / 解码与系统调用，放后台线程。
     */
    private void setAsHomeWallpaper(final WallpaperStore.Item item) {
        if (!TakeoverManager.isEnabled(this)) {
            Toast.makeText(this, R.string.status_takeover_off, Toast.LENGTH_LONG).show();
            return;
        }
        LibraryStore.Library lib = LibraryStore.get(this, item.libId);
        if (lib == null || !LibraryStore.ownsScope(this, lib.id, true)) {
            Toast.makeText(this, R.string.set_as_home_unavailable, Toast.LENGTH_LONG).show();
            return;
        }
        final Context app = getApplicationContext();
        new Thread(() -> {
            final boolean ok = Switcher.setCurrent(app, lib.id, item.id, true);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                Toast.makeText(this, ok ? getString(R.string.set_as_home_done)
                        : Switcher.errorText(this, Switcher.lastError()), Toast.LENGTH_SHORT).show();
                if (ok) {
                    // 「桌面：WallPaper / 系统」这行可能因此变化，立刻刷新，别等下次进应用
                    refreshTakeoverStatus();
                }
            });
        }, "set-home").start();
    }

    /** 壁纸显示名（未命名则用占位文案）。 */
    private String itemTitle(WallpaperStore.Item item) {
        return item.title == null || item.title.isEmpty() ? getString(R.string.untitled) : item.title;
    }

    /**
     * 提交预览弹窗里的就地改名（通知里会带上这个标题，便于区分切到了哪张）。
     * 留空 = 未命名；已在展示态时（可见性不是输入框）忽略重复回调，避免编辑器动作与失焦各提交一次。
     */
    private void commitPreviewTitle(WallpaperStore.Item item, TextView titleView, EditText input) {
        if (input.getVisibility() != View.VISIBLE) {
            return;
        }
        String newTitle = input.getText().toString().trim();
        WallpaperStore.setTitle(this, item.id, newTitle);
        // 本地快照同步，避免同一弹窗里再次改名时回填旧值
        item.title = newTitle;
        titleView.setText(itemTitle(item));
        titleInputToTitle(titleView, input);
        refreshList();
    }

    /** 改名输入框收回展示态（EditText 隐藏后输入法会自动收起）。 */
    private void titleInputToTitle(TextView titleView, EditText input) {
        input.setVisibility(View.GONE);
        titleView.setVisibility(View.VISIBLE);
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


    /** 点库名进入就地改名：TextView 与 EditText 互换可见性，弹起软键盘。 */
    private void enterRename(LibHolder holder, LibraryStore.Library lib) {
        renamingLibId = lib.id;
        holder.tvName.setVisibility(View.GONE);
        holder.etName.setVisibility(View.VISIBLE);
        holder.etName.setText(lib.name);
        holder.etName.setSelection(0, lib.name == null ? 0 : lib.name.length());
        holder.etName.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(holder.etName, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    /** 提交就地改名并退出编辑态（写回 LibraryStore；留空则保留原名）。 */
    private void commitRename(LibHolder holder, LibraryStore.Library lib) {
        // 以防编辑器 action 与失焦回调各触发一次造成重复提交
        if (renamingLibId == null || !renamingLibId.equals(lib.id)) {
            return;
        }
        renamingLibId = null;
        LibraryStore.setName(this, lib.id, holder.etName.getText().toString());
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(holder.etName.getWindowToken(), 0);
        }
        // 可能正处于焦点变化回调中：推迟到下一帧再整体重绑，避免回调过程中回收正在编辑的视图
        recycler.post(this::refreshLibs);
    }

    /** 取消就地改名（不写回），收起输入法并恢复原库名显示。 */
    private void cancelRename() {
        renamingLibId = null;
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(recycler.getWindowToken(), 0);
        }
        // 重绑一次，把 EditText 收起、库名 TextView 恢复原值
        refreshLibs();
    }

    /**
     * 库列表适配器（首页）：缩略图（该库当前壁纸）+ 库名（点按就地改名）+ N 张壁纸
     * + 启用开关 + 齿轮；长按行 = 拖拽排序（ItemTouchHelper 驱动，落位写回磁盘）；
     * 删除态（顶栏垃圾桶进入）下开关/齿轮换成行尾复选框，点行即勾选。
     */
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

    private class LibAdapter extends RecyclerView.Adapter<LibHolder> {

        // 每行的预计算数据：setItems 时一次性算好张数与缩略图来源，onBindViewHolder 不再做文件 IO
        private static class Row {
            final LibraryStore.Library lib;
            final int count;
            // 缩略图来源 id：该库当前壁纸（桌面指针优先，其次锁屏，再次库内第一张）；空库为 null
            final String thumbId;

            Row(LibraryStore.Library lib, int count, String thumbId) {
                this.lib = lib;
                this.count = count;
                this.thumbId = thumbId;
            }
        }

        private final List<Row> rows = new ArrayList<>();
        // 删除态（顶栏垃圾桶进入）：行尾显示复选框、点行勾选，勾图标确认批量删除
        private boolean deleteMode;
        private final Set<String> selectedIds = new HashSet<>();

        // 缩略图内存缓存（上限 8MB）：每次刷新都重新解码会掉帧。
        // v3.15 缩略图边长跟着屏幕（单张 0.5~1MB），上限跟着调大才够铺满可见行
        private final LruCache<String, Bitmap> thumbs = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount();
            }
        };

        /** 取缩略图：先查缓存，未命中再解码并存入。 */
        private Bitmap thumbFor(String id) {
            Bitmap cached = thumbs.get(id);
            if (cached != null) {
                return cached;
            }
            Bitmap decoded = WallpaperStore.getThumb(MainActivity.this, id);
            if (decoded != null) {
                thumbs.put(id, decoded);
            }
            return decoded;
        }

        void setItems(List<LibraryStore.Library> newItems) {
            rows.clear();
            for (LibraryStore.Library lib : newItems) {
                // 每个库只在刷新时读一次库内列表（张数 + 缩略图来源），滚动时不再反复读 JSON
                List<WallpaperStore.Item> wallpapers =
                        WallpaperStore.loadByLib(MainActivity.this, lib.id);
                String thumbId = Switcher.getCurrent(MainActivity.this, lib.id, true);
                if (thumbId == null) {
                    thumbId = Switcher.getCurrent(MainActivity.this, lib.id, false);
                }
                if (thumbId == null && !wallpapers.isEmpty()) {
                    thumbId = wallpapers.get(0).id;
                }
                rows.add(new Row(lib, wallpapers.size(), thumbId));
            }
            // 数据已换，之前的勾选不复存在（视图会被回收），标记一并清掉；
            // 改名中的 EditText 也会被重绑回 TextView，所以改名编辑态同步清掉
            // （否则返回键会多拦一次「取消改名」，用户要按两次才退出）
            selectedIds.clear();
            renamingLibId = null;
            notifyDataSetChanged();
            updateLibDeleteToolbar();
        }

        void clearThumbs() {
            thumbs.evictAll();
        }

        boolean isDeleteMode() {
            return deleteMode;
        }

        int selectedCount() {
            return selectedIds.size();
        }

        /** 进入/退出删除态（退出时清空勾选）。 */
        void setDeleteMode(boolean on) {
            deleteMode = on;
            selectedIds.clear();
            notifyDataSetChanged();
        }

        List<LibraryStore.Library> selectedLibs() {
            List<LibraryStore.Library> result = new ArrayList<>();
            for (Row row : rows) {
                if (selectedIds.contains(row.lib.id)) {
                    result.add(row.lib);
                }
            }
            return result;
        }

        /** 当前行顺序对应的库 id 列表（拖拽落位后写回磁盘用）。 */
        List<String> currentIds() {
            List<String> ids = new ArrayList<>();
            for (Row row : rows) {
                ids.add(row.lib.id);
            }
            return ids;
        }

        /** 拖拽换位：只动内存与动画，落盘由 ItemTouchHelper 的 clearView 统一做。 */
        boolean move(int from, int to) {
            if (from < 0 || to < 0 || from >= rows.size() || to >= rows.size()) {
                return false;
            }
            rows.add(to, rows.remove(from));
            notifyItemMoved(from, to);
            return true;
        }

        /**
         * 只刷新行上的「桌面/锁屏」角标：槽位变更后逐行重绑即可。
         * 别用 setItems —— 那会为每个库重读一遍壁纸 JSON 并整表重绘，纯浪费（发热来源之一）。
         */
        void refreshBadges() {
            for (int i = 0; i < rows.size(); i++) {
                notifyItemChanged(i);
            }
        }

        @NonNull
        @Override
        public LibHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(MainActivity.this)
                    .inflate(R.layout.item_library, parent, false);
            return new LibHolder(view);
        }


        @Override
        public void onBindViewHolder(@NonNull LibHolder holder, int position) {
            final Row row = rows.get(position);
            final LibraryStore.Library lib = row.lib;
            Bitmap thumb = row.thumbId == null ? null : thumbFor(row.thumbId);
            if (thumb != null) {
                // 布局里那个 tint 是给占位图标用的，不清掉的话它会把真图也按 SRC_IN 染成单一剪影
                // （看起来就像「这个库没有图」）；占位与真图两条路径都必须显式设置，回收复用才不会串状态
                holder.imgThumb.setPadding(0, 0, 0, 0);
                holder.imgThumb.setImageTintList(null);
                holder.imgThumb.setImageBitmap(thumb);
            } else {
                int pad = (int) (12 * getResources().getDisplayMetrics().density);
                holder.imgThumb.setPadding(pad, pad, pad, pad);
                holder.imgThumb.setImageTintList(
                        ColorStateList.valueOf(getColor(R.color.text_secondary)));
                holder.imgThumb.setImageResource(R.drawable.ic_tab_wallpaper);
            }
            holder.tvName.setText(lib.name);
            holder.tvName.setVisibility(View.VISIBLE);
            holder.etName.setVisibility(View.GONE);
            holder.tvCount.setText(getString(R.string.lib_count, row.count));
            // v3.60：库不再有启用/范围属性——被哪个范围槽位引用就显示哪个小标签
            holder.badgeHome.setVisibility(
                    LibraryStore.ownsScope(MainActivity.this, lib.id, true) ? View.VISIBLE : View.GONE);
            holder.badgeLock.setVisibility(
                    LibraryStore.ownsScope(MainActivity.this, lib.id, false) ? View.VISIBLE : View.GONE);
            // 删除态：标签收起，行尾露出复选框（点行任意处即可勾选）
            holder.cbDelete.setVisibility(deleteMode ? View.VISIBLE : View.GONE);
            if (deleteMode) {
                holder.badgeHome.setVisibility(View.GONE);
                holder.badgeLock.setVisibility(View.GONE);
            }
            holder.cbDelete.setOnCheckedChangeListener(null);
            holder.cbDelete.setChecked(selectedIds.contains(lib.id));
            if (deleteMode) {
                holder.cbDelete.setOnCheckedChangeListener((v, checked) -> {
                    if (checked) {
                        selectedIds.add(lib.id);
                    } else {
                        selectedIds.remove(lib.id);
                    }
                    updateLibDeleteToolbar();
                });
            }
            // 点库名 → 就地改名（删除态不响应）；点行内其他位置 → 进该库壁纸页 / 勾选
            holder.tvName.setOnClickListener(v -> {
                if (!deleteMode) {
                    enterRename(holder, lib);
                }
            });
            holder.etName.setOnEditorActionListener((v, actionId, event) -> {
                commitRename(holder, lib);
                return true;
            });
            holder.etName.setOnFocusChangeListener((v, hasFocus) -> {
                if (!hasFocus) {
                    commitRename(holder, lib);
                }
            });
            holder.card.setOnClickListener(v -> {
                if (!deleteMode) {
                    showWallpaperPage(lib.id);
                    return;
                }
                if (selectedIds.contains(lib.id)) {
                    selectedIds.remove(lib.id);
                } else {
                    selectedIds.add(lib.id);
                }
                notifyItemChanged(holder.getBindingAdapterPosition());
                updateLibDeleteToolbar();
            });
            // 长按行的空白/文字区 = 排序拖拽（ItemTouchHelper 的自动长按已关，这里手动起手）
            holder.card.setOnLongClickListener(v -> {
                if (deleteMode || showingWallpapers) {
                    return false;
                }
                libDragHelper.startDrag(holder);
                return true;
            });
            // 长按头像 = 拖出浮动头像去设范围；头像自己吃掉点击，所以点头像仍是进该库的壁纸页
            holder.imgThumb.setOnClickListener(v -> {
                if (!deleteMode) {
                    showWallpaperPage(lib.id);
                }
            });
            holder.imgThumb.setOnLongClickListener(v -> {
                if (deleteMode) {
                    return false;
                }
                startAvatarDrag(holder, lib.id);
                return true;
            });
            // 头像吃掉 DOWN，所以这次手势的 MOVE/UP 都会回到这里 —— 浮动头像就靠它跟手
            holder.imgThumb.setOnTouchListener((v, event) -> {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    avatarLastRawX = event.getRawX();
                    avatarLastRawY = event.getRawY();
                } else if (action == MotionEvent.ACTION_MOVE && avatarFloat != null) {
                    avatarLastRawX = event.getRawX();
                    avatarLastRawY = event.getRawY();
                    moveAvatarFloat(avatarLastRawX, avatarLastRawY);
                } else if (avatarFloat != null && (action == MotionEvent.ACTION_UP
                        || action == MotionEvent.ACTION_CANCEL)) {
                    finishAvatarDrag(action == MotionEvent.ACTION_UP,
                            event.getRawX(), event.getRawY());
                    return true;    // 拖过了就不算点击，别把壁纸页顶出来
                }
                return false;
            });
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }
    }


    /**
     * 壁纸网格适配器（两列正方形）：点格预览；长按浮出铅笔（进裁剪编辑页）/ 红垃圾桶
     * （直接删，不确认）两个圆形图标，点空白处或返回手势收起。
     */
    private class WallpaperAdapter extends RecyclerView.Adapter<WpHolder> {

        private List<WallpaperStore.Item> items = new ArrayList<>();
        // 长按浮出操作图标的格子下标（同一时间只允许一格；-1 = 没有）
        private int revealed = -1;

        // 缩略图内存缓存（上限 8MB）：网格一屏要绑 6~8 张，每次刷新都重新解码会掉帧。
        // v3.15 缩略图边长跟着屏幕（单张 0.5~1MB），上限跟着调大才够铺满可见格
        private final LruCache<String, Bitmap> thumbs = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount();
            }
        };

        /** 取缩略图：先查缓存，未命中再解码并存入。 */
        private Bitmap thumbFor(String id) {
            Bitmap cached = thumbs.get(id);
            if (cached != null) {
                return cached;
            }
            Bitmap decoded = WallpaperStore.getThumb(MainActivity.this, id);
            if (decoded != null) {
                thumbs.put(id, decoded);
            }
            return decoded;
        }

        void setItems(List<WallpaperStore.Item> newItems) {
            items = newItems;
            revealed = -1;
            notifyDataSetChanged();
        }

        void clearThumbs() {
            thumbs.evictAll();
        }

        /** 收起长按浮出的操作图标；返回是否有东西被收起（供返回键链路用）。 */
        boolean hideRevealed() {
            if (revealed < 0) {
                return false;
            }
            int old = revealed;
            revealed = -1;
            notifyItemChanged(old);
            return true;
        }

        @NonNull
        @Override
        public WpHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(MainActivity.this)
                    .inflate(R.layout.item_wallpaper, parent, false);
            return new WpHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull WpHolder holder, int position) {
            final WallpaperStore.Item item = items.get(position);
            holder.imgThumb.setImageBitmap(thumbFor(item.id));
            // 格子下方显示壁纸标题（未命名则用占位文案）
            holder.tvTitle.setText(itemTitle(item));
            holder.actions.setVisibility(position == revealed ? View.VISIBLE : View.GONE);
            // 点格：有浮出图标时先收起（相当于取消），否则进预览
            holder.itemView.setOnClickListener(v -> {
                if (hideRevealed()) {
                    return;
                }
                showPreview(item);
            });
            // 长按浮出编辑/删除图标
            holder.itemView.setOnLongClickListener(v -> {
                int old = revealed;
                revealed = position;
                if (old >= 0) {
                    notifyItemChanged(old);
                }
                notifyItemChanged(position);
                return true;
            });
            // 铅笔 → 进裁剪编辑页（重编模式：确认后覆盖原图）
            holder.btnEdit.setOnClickListener(v -> {
                hideRevealed();
                openEditItem(item);
            });
            // 房子 → 把这张直接设为桌面（首页）壁纸
            holder.btnSetHome.setOnClickListener(v -> {
                hideRevealed();
                setAsHomeWallpaper(item);
            });
            // 红垃圾桶 → 直接删除，不再确认
            holder.btnDelete.setOnClickListener(v -> {
                revealed = -1;
                WallpaperStore.delete(MainActivity.this, item.id);
                refreshList();
                // 删掉的若是桌面/锁屏的当前壁纸，立刻清指针并推进到下一张上屏
                // （锁屏路径要解码 + setBitmap 系统调用，放后台线程；用 Application 上下文，
                //  线程可能在 Activity 销毁后才跑完）
                new Thread(() -> Switcher.reapplyIfCurrent(getApplicationContext(), item.libId, item.id),
                        "reapply-wallpaper").start();
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    /** 库行视图持有者。 */
    private static class LibHolder extends RecyclerView.ViewHolder {

        final View card;
        final ImageView imgThumb;
        final TextView tvName;
        final EditText etName;
        final TextView tvCount;
        final View badgeHome;
        final View badgeLock;
        final CheckBox cbDelete;

        LibHolder(@NonNull View itemView) {
            super(itemView);
            card = itemView;
            imgThumb = itemView.findViewById(R.id.img_lib_thumb);
            tvName = itemView.findViewById(R.id.tv_lib_name);
            etName = itemView.findViewById(R.id.et_lib_name);
            tvCount = itemView.findViewById(R.id.tv_lib_count);
            badgeHome = itemView.findViewById(R.id.badge_lib_home);
            badgeLock = itemView.findViewById(R.id.badge_lib_lock);
            cbDelete = itemView.findViewById(R.id.cb_lib_delete);
        }
    }

    /** 壁纸格视图持有者。 */
    private static class WpHolder extends RecyclerView.ViewHolder {

        final ImageView imgThumb;
        final TextView tvTitle;
        final View actions;
        final View btnEdit;
        final View btnSetHome;
        final View btnDelete;

        WpHolder(@NonNull View itemView) {
            super(itemView);
            imgThumb = itemView.findViewById(R.id.img_thumb);
            tvTitle = itemView.findViewById(R.id.tv_wallpaper_title);
            actions = itemView.findViewById(R.id.item_actions);
            btnEdit = itemView.findViewById(R.id.btn_edit);
            btnSetHome = itemView.findViewById(R.id.btn_set_home);
            btnDelete = itemView.findViewById(R.id.btn_delete);
        }
    }
}

