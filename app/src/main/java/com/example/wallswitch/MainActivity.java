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
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.util.LruCache;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputLayout;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

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
    // 全局「桌面图标预览底图」的单张选图（选一次、抠图后存应用目录，之后裁剪页直接复用）
    private ActivityResultLauncher<PickVisualMediaRequest> overlayLauncher;
    // SAF 导出目录选择（ACTION_OPEN_DOCUMENT_TREE）
    private ActivityResultLauncher<Uri> exportDirLauncher;
    // 接管开关是否处于「等待用户在系统选择器里确认」的状态（用来判断用户是否点了取消）
    private boolean pendingEngineActivation = false;
    private RecyclerView recycler;
    // 两页共用一个 RecyclerView：库列表页 = LibAdapter + LinearLayoutManager，
    // 壁纸网格页 = WallpaperAdapter + GridLayoutManager(2)
    private LibAdapter libAdapter;
    private WallpaperAdapter wallpaperAdapter;
    private SharedPreferences prefs;
    // 当前页：true = 壁纸网格页；false = 库列表页（首页）
    private boolean showingWallpapers = false;
    // 壁纸页正在看的库 id（也兼作「上次查看的库」，导入壁纸时的默认目标库）
    private String currentLibId;
    // 正在就地改名的库 id（null = 没有；返回键用它判断「取消改名」而不是退出）
    private String renamingLibId;

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
        overlayLauncher = registerForActivityResult(
                new ActivityResultContracts.PickVisualMedia(), this::onOverlayPicked);
        exportDirLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocumentTree(), this::onExportDirPicked);
        recycler = findViewById(R.id.recycler);
        libAdapter = new LibAdapter();
        wallpaperAdapter = new WallpaperAdapter();
        setupBackPressed();
        setupDrawer();
        setupButtons();
        setupNotifySwitch();
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
                if (wallpaperAdapter.hideRevealed() || libAdapter.hideRevealed()) {
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
                // 库列表页：交回系统默认行为（退出 App）
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
            }
        });
    }

    /** 库列表页（首页）：☰ 开抽屉 + 固定标题 WallPaper + ＋ 新建库。 */
    private void showLibPage() {
        showingWallpapers = false;
        libAdapter.hideRevealed();
        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(R.string.app_title);
        toolbar.setNavigationIcon(R.drawable.ic_menu);
        toolbar.setNavigationOnClickListener(v -> openDrawer());
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setAdapter(libAdapter);
        ((Button) findViewById(R.id.btn_add)).setText(R.string.lib_new);
        refreshLibs();
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
    }

    /** 刷新库列表与空状态。 */
    private void refreshLibs() {
        List<LibraryStore.Library> libs = LibraryStore.load(this);
        libAdapter.setItems(libs);
        updateEmptyState(libs.isEmpty(), R.string.empty_libs, R.string.empty_libs_hint);
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
        // 从系统选择器返回：用户没确认就把接管意图关掉（开关自动回关）
        onReturnFromActivator();
        setupTakeoverSwitch();
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
                refreshBatteryRow();
                refreshLauncherOverlayRow();
                refreshExportRows();
                refreshSwitchLogRow();
                syncTakeoverAsync();
            }
        });
        // 「桌面图标预览底图」：点按选/换一张自己的首页截图，长按清除。全局只设一次。
        View overlayRow = findViewById(R.id.row_launcher_overlay);
        if (overlayRow != null) {
            overlayRow.setOnClickListener(v -> pickLauncherOverlay());
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
        setupTakeoverSwitch();
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
     * 删库二次确认（长按库行浮出的红垃圾桶触发）：文案写明库里的 N 张壁纸会一并删除；
     * 确认按钮统一成红色垃圾桶图标（无文字）。
     */
    private void confirmDeleteLib(final LibraryStore.Library lib) {
        final int count = WallpaperStore.loadByLib(this, lib.id).size();
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.lib_delete_title, lib.name))
                .setMessage(getString(R.string.lib_delete_msg, count))
                .setPositiveButton(" ", (d, which) -> deleteLib(lib))
                .setNegativeButton(R.string.cancel, null)
                .show();
        // 确认按钮换成红色垃圾桶图标（决策：删除按钮统一图标、不带文字）
        Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        positive.setText("");
        positive.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_delete, 0, 0, 0);
        positive.setCompoundDrawableTintList(ColorStateList.valueOf(getColor(R.color.danger)));
    }

    /** 删库：连带清库内壁纸文件、切换进度与定时任务（都在 LibraryStore.delete 内部）。 */
    private void deleteLib(LibraryStore.Library lib) {
        LibraryStore.delete(this, lib.id);
        if (lib.id.equals(currentLibId())) {
            currentLibId = null;
            prefs.edit().remove(LibraryStore.KEY_CURRENT_LIB).apply();
        }
        if (showingWallpapers) {
            showLibPage();
        } else {
            refreshLibs();
        }
        refreshTimerStatus();
        // 删除启用中的库会改变「谁在被接管」，同步一次接管状态
        syncTakeoverAsync();
    }


    /**
     * 库行齿轮：该库的设置弹窗 —— 作用范围（桌面/锁屏单选）、切换模式（顺序/随机）、
     * 切换间隔（整行可点弹输入框）、立即切换一张（手动切换入口）。
     * 改动即保存；同范围互斥等规则都在 LibraryStore 里。
     */
    private void showLibSettingsDialog(final LibraryStore.Library lib) {
        View content = LayoutInflater.from(this).inflate(R.layout.dialog_lib_settings, null, false);
        RadioGroup rgScope = content.findViewById(R.id.rg_scope);
        RadioGroup rgMode = content.findViewById(R.id.rg_mode);
        final TextView tvInterval = content.findViewById(R.id.tv_interval);
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(lib.name)
                .setView(content)
                .setNegativeButton(R.string.close, null)
                .show();
        // 回填（先填值再挂监听，程序化 check 不触发写回）
        if (lib.home && !lib.lock) {
            rgScope.check(R.id.rb_scope_home);
        } else if (lib.lock && !lib.home) {
            rgScope.check(R.id.rb_scope_lock);
        }
        rgMode.check(LibraryStore.MODE_RANDOM.equals(lib.mode) ? R.id.rb_random : R.id.rb_order);
        tvInterval.setText(formatInterval(lib.intervalSeconds));
        // 范围：单选；改动即保存（范围互斥、启用库重排定时都在 setScope 内部）
        rgScope.setOnCheckedChangeListener((group, checkedId) -> {
            boolean home = checkedId == R.id.rb_scope_home;
            LibraryStore.Library latest = LibraryStore.get(MainActivity.this, lib.id);
            if (latest == null) {
                return;
            }
            boolean curHome = latest.home && !latest.lock;
            boolean curLock = latest.lock && !latest.home;
            if (home ? curHome : curLock) {
                return;
            }
            LibraryStore.setScope(MainActivity.this, lib.id, home, !home);
            Toast.makeText(MainActivity.this,
                    getString(R.string.scope_saved, getString(home ? R.string.scope_home : R.string.scope_lock)),
                    Toast.LENGTH_SHORT).show();
            refreshLibs();
            refreshTimerStatus();
            syncTakeoverAsync();
        });
        // 切换模式（顺序/随机，按库保存）
        rgMode.setOnCheckedChangeListener((group, checkedId) ->
                LibraryStore.setMode(MainActivity.this, lib.id,
                        checkedId == R.id.rb_random ? LibraryStore.MODE_RANDOM : LibraryStore.MODE_ORDER));
        // 切换间隔（分钟级，最小 15）：整行可点
        content.findViewById(R.id.row_interval).setOnClickListener(v -> showIntervalDialog(lib, tvInterval));
        // 立即切换一张（手动切换入口）
        content.findViewById(R.id.row_switch_now).setOnClickListener(v -> {
            dialog.dismiss();
            switchLibNow(lib);
        });
    }

    /**
     * 库行「启用」开关的处理：要**启用**时先查有没有同范围的重叠库 —— 有就先问一句
     * （规则是同一范围只能有一个库负责，继续启用会停用对方），用户取消则把开关扳回去。
     */
    private void onLibEnableToggled(LibHolder holder, LibraryStore.Library lib, boolean isChecked) {
        if (isChecked) {
            List<LibraryStore.Library> conflicts = LibraryStore.enabledConflicts(this, lib.id);
            if (!conflicts.isEmpty()) {
                confirmEnableLib(holder, lib, conflicts);
                return;
            }
        }
        applyLibEnable(lib, isChecked);
    }

    /** 真正写回启用状态（含失败提示与整体刷新）。 */
    private void applyLibEnable(LibraryStore.Library lib, boolean isChecked) {
        boolean ok = LibraryStore.setEnabled(this, lib.id, isChecked);
        if (!ok) {
            Toast.makeText(this, R.string.lib_scope_none, Toast.LENGTH_SHORT).show();
        }
        // 同范围互斥会改动其他行的开关状态，整体重绑
        refreshLibs();
        refreshTimerStatus();
        syncTakeoverAsync();
    }

    /** 启用会顶掉同范围的启用库：列出名字确认；取消则把开关扳回库的真实状态（什么都不写）。 */
    private void confirmEnableLib(final LibHolder holder, final LibraryStore.Library lib,
            final List<LibraryStore.Library> conflicts) {
        StringBuilder names = new StringBuilder();
        for (LibraryStore.Library other : conflicts) {
            if (names.length() > 0) {
                names.append("、");
            }
            names.append("「").append(other.name).append("」");
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.lib_enable_conflict_title)
                .setMessage(getString(R.string.lib_enable_conflict_msg,
                        names.toString(), scopeLabel(lib), lib.name))
                .setPositiveButton(R.string.lib_enable_conflict_ok,
                        (dialog, which) -> applyLibEnable(lib, true))
                .setNegativeButton(R.string.cancel, (dialog, which) -> {
                    holder.swEnabled.setOnCheckedChangeListener(null);
                    holder.swEnabled.setChecked(lib.enabled);
                    holder.swEnabled.setOnCheckedChangeListener((buttonView, isChecked) ->
                            onLibEnableToggled(holder, lib, isChecked));
                })
                .show();
    }

    /** 库的作用范围文案（新 UI 是桌面/锁屏单选，老数据可能双范围、也可能没勾）。 */
    private String scopeLabel(LibraryStore.Library lib) {
        if (lib.home && lib.lock) {
            return getString(R.string.scope_home_lock);
        }
        if (lib.home) {
            return getString(R.string.scope_home);
        }
        if (lib.lock) {
            return getString(R.string.scope_lock);
        }
        return getString(R.string.lib_scope_none);
    }

    /** 弹窗输入切换间隔（分钟，最小 15），确认后写回并重排启用中的定时。 */
    private void showIntervalDialog(final LibraryStore.Library lib, final TextView tvInterval) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        // 历史值可能是秒级（旧版本遗留），这里换算成分钟并保证 ≥15
        input.setText(String.valueOf(Math.max(LibraryStore.MIN_INTERVAL_SECONDS / 60,
                lib.intervalSeconds / 60)));
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.lib_interval)
                .setView(wrapInput(input, R.string.interval_hint))
                .setPositiveButton(R.string.confirm, (dialog, which) -> {
                    int minutes = 0;
                    try {
                        minutes = Integer.parseInt(input.getText().toString().trim());
                    } catch (NumberFormatException ignored) {
                    }
                    if (minutes > 0) {
                        if (minutes < LibraryStore.MIN_INTERVAL_SECONDS / 60) {
                            // WorkManager 省电方案的系统下限：不足 15 分钟会被抬到 15 分钟
                            Toast.makeText(MainActivity.this, R.string.interval_min_toast,
                                    Toast.LENGTH_SHORT).show();
                            minutes = LibraryStore.MIN_INTERVAL_SECONDS / 60;
                        }
                        LibraryStore.setInterval(MainActivity.this, lib.id, minutes * 60);
                        tvInterval.setText(formatInterval(minutes * 60));
                        refreshTimerStatus();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
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
     * 手动切换该库一张（库设置弹窗「立即切换一张」入口）：
     * 库只设了一个范围就直接切那个范围；双范围（老数据）先问切哪边；没设范围提示先设。
     * 接管总开关关着时不写系统（拦截仍在 Switcher.next 开头），这里直接说清并把抽屉推开。
     */
    private void switchLibNow(final LibraryStore.Library lib) {
        if (!TakeoverManager.isEnabled(this)) {
            Toast.makeText(this, R.string.status_takeover_off, Toast.LENGTH_LONG).show();
            openDrawer();
            return;
        }
        boolean home = lib.home;
        boolean lock = lib.lock;
        if (home && !lock) {
            switchAndToast(lib, true);
            return;
        }
        if (lock && !home) {
            switchAndToast(lib, false);
            return;
        }
        if (!home && !lock) {
            Toast.makeText(this, R.string.lib_scope_none, Toast.LENGTH_SHORT).show();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.switch_pick_scope)
                .setItems(new CharSequence[]{getString(R.string.scope_home), getString(R.string.scope_lock)},
                        (dialog, which) -> switchAndToast(lib, which == 0))
                .setNegativeButton(R.string.cancel, null)
                .show();
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
                } else {
                    // 带上具体失败原因（如桌面被动态壁纸占用），方便对症处理
                    Toast.makeText(this, Switcher.errorText(this, Switcher.lastError()),
                            Toast.LENGTH_LONG).show();
                }
            });
        }, "manual-switch").start();
    }


    /**
     * 定时状态行：上次执行结果 + 下次预计时间；若已过预计时间仍未见系统执行，
     * 说明被 Doze/ROM 冻结拦住了（等待调度，点亮屏幕/刷新小组件/打开本应用会自动补切）。
     * v3.14 起没有全局定时开关：库启用 + 设了间隔即到点自动切。
     */
    private void refreshTimerStatus() {
        TextView tv = findViewById(R.id.tv_timer_status);
        if (tv == null) {
            return;
        }
        String libId = enabledLibId();
        Long next = TimerScheduler.nextTrigger(this);
        if (libId == null || next == null) {
            tv.setText(R.string.timer_status_none);
            return;
        }
        String result = TimerScheduler.lastResult(this, libId);
        String resultText;
        if (TimerScheduler.RESULT_OK.equals(result)) {
            resultText = getString(R.string.status_ok);
        } else if (result == null) {
            resultText = getString(R.string.status_never);
        } else {
            // 失败原因统一走可读文案映射（解码失败/系统未应用/被动态壁纸占用等）
            resultText = Switcher.errorText(this, result);
        }
        long now = System.currentTimeMillis();
        if (next <= now) {
            tv.setText(getString(R.string.timer_status_overdue,
                    formatInterval((int) Math.max(60, (now - next) / 1000))));
        } else {
            long last = TimerScheduler.lastRun(this, libId);
            String lastText = last > 0 ? formatClock(last) : getString(R.string.status_never);
            tv.setText(getString(R.string.timer_status_on, lastText, resultText, formatClock(next)));
        }
    }

    /** 某个启用库的 id（状态展示用）：桌面优先，其次锁屏；无启用库返回 null。 */
    private String enabledLibId() {
        LibraryStore.Library lib = LibraryStore.enabledLibForScope(this, true);
        if (lib == null) {
            lib = LibraryStore.enabledLibForScope(this, false);
        }
        return lib == null ? null : lib.id;
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
     * 「接管壁纸」总开关。开关 = 接管<b>意图</b>；桌面/锁屏各自是否真的被接管，看下面两行系统真值。
     * 打开时先存下接管前的桌面/锁屏壁纸；桌面接管需要用户在系统选择器里确认一次，
     * 取消则开关自动回关（见 onReturnFromActivator）。
     */
    private void setupTakeoverSwitch() {
        CompoundButton sw = findViewById(R.id.sw_takeover);
        if (sw == null) {
            return;
        }
        sw.setOnCheckedChangeListener(null);
        sw.setChecked(TakeoverManager.isEnabled(this));
        sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                enableTakeover();
            } else {
                disableTakeover();
            }
        });
        refreshTakeoverStatus();
    }

    /** 打开接管：先存接管前的壁纸，再落地（桌面可能需要用户在系统选择器里确认一次）。 */
    private void enableTakeover() {
        Toast.makeText(this, R.string.takeover_saving, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            TakeoverManager.savePreviousWallpapers(this);
            TakeoverManager.setEnabled(this, true);
            final int result = TakeoverManager.apply(this);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (result == TakeoverManager.RESULT_NEED_ACTIVATION) {
                    // 普通 App 没有 SET_WALLPAPER_COMPONENT 权限，桌面接管必须由用户在系统界面确认
                    pendingEngineActivation = true;
                    WallSwitchService.openActivator(this);
                } else {
                    Toast.makeText(this, R.string.takeover_on, Toast.LENGTH_SHORT).show();
                }
                setupTakeoverSwitch();
            });
        }, "takeover-on").start();
    }

    /** 关闭接管：桌面与锁屏都还原成接管前的样子。 */
    private void disableTakeover() {
        new Thread(() -> {
            TakeoverManager.setEnabled(this, false);
            final boolean ok = TakeoverManager.release(this);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                Toast.makeText(this, ok ? R.string.takeover_off : R.string.takeover_restore_failed,
                        Toast.LENGTH_SHORT).show();
                setupTakeoverSwitch();
            });
        }, "takeover-off").start();
    }


    /** 从系统选择器返回：用户没确认就把接管意图关掉（开关自动回关），并把已做的改动还原。 */
    private void onReturnFromActivator() {
        if (!pendingEngineActivation) {
            return;
        }
        pendingEngineActivation = false;
        if (TakeoverManager.isHomeTakenOver(this)) {
            Toast.makeText(this, R.string.takeover_on, Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(() -> {
            TakeoverManager.setEnabled(this, false);
            TakeoverManager.release(this);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                Toast.makeText(this, R.string.takeover_cancelled, Toast.LENGTH_LONG).show();
                setupTakeoverSwitch();
            });
        }, "takeover-cancel").start();
    }

    /**
     * 把接管意图与实际同步一次（例如刚停用/删除了库：没启用库的范围要回退给系统）。
     * 放后台线程，避免系统调用卡 UI。
     */
    private void syncTakeoverAsync() {
        new Thread(() -> {
            TakeoverManager.apply(this);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                setupTakeoverSwitch();
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
     * + 启用开关 + 齿轮；长按行浮出红垃圾桶（点垃圾桶二次确认后删库）。
     */
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
        // 长按浮出红垃圾桶的行下标（同一时间只允许一行；-1 = 没有）
        private int revealed = -1;

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
            // 数据已换，之前浮出的行不复存在（视图会被回收），标记一并清掉；
            // 改名中的 EditText 也会被重绑回 TextView，所以改名编辑态同步清掉
            // （否则返回键会多拦一次「取消改名」，用户要按两次才退出）
            revealed = -1;
            renamingLibId = null;
            notifyDataSetChanged();
        }

        void clearThumbs() {
            thumbs.evictAll();
        }

        /** 收起长按浮出的红垃圾桶；返回是否有东西被收起（供返回键链路用）。 */
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
            // 长按浮出状态：开关与齿轮收起，行尾露出红垃圾桶
            boolean isRevealed = position == revealed;
            holder.swEnabled.setVisibility(isRevealed ? View.GONE : View.VISIBLE);
            holder.btnSettings.setVisibility(isRevealed ? View.GONE : View.VISIBLE);
            holder.btnDelete.setVisibility(isRevealed ? View.VISIBLE : View.GONE);
            // 启用/停用：沿用 LibraryStore 的同范围互斥规则；失败（未设范围）回退并提示。
            // 启用前若与同范围的启用库冲突 → 先弹确认，不静默把对方关掉
            holder.swEnabled.setOnCheckedChangeListener(null);
            holder.swEnabled.setChecked(lib.enabled);
            holder.swEnabled.setOnCheckedChangeListener((buttonView, isChecked) ->
                    onLibEnableToggled(holder, lib, isChecked));
            // 点库名 → 就地改名；点行内其他位置 → 进该库壁纸页
            holder.tvName.setOnClickListener(v -> enterRename(holder, lib));
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
                if (hideRevealed()) {
                    return;
                }
                showWallpaperPage(lib.id);
            });
            holder.card.setOnLongClickListener(v -> {
                int old = revealed;
                revealed = position;
                if (old >= 0) {
                    notifyItemChanged(old);
                }
                notifyItemChanged(position);
                return true;
            });
            holder.btnSettings.setOnClickListener(v -> showLibSettingsDialog(lib));
            holder.btnDelete.setOnClickListener(v -> {
                hideRevealed();
                confirmDeleteLib(lib);
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
        final CompoundButton swEnabled;
        final View btnSettings;
        final View btnDelete;

        LibHolder(@NonNull View itemView) {
            super(itemView);
            card = itemView;
            imgThumb = itemView.findViewById(R.id.img_lib_thumb);
            tvName = itemView.findViewById(R.id.tv_lib_name);
            etName = itemView.findViewById(R.id.et_lib_name);
            tvCount = itemView.findViewById(R.id.tv_lib_count);
            swEnabled = itemView.findViewById(R.id.sw_lib_enabled);
            btnSettings = itemView.findViewById(R.id.btn_lib_settings);
            btnDelete = itemView.findViewById(R.id.btn_lib_delete);
        }
    }

    /** 壁纸格视图持有者。 */
    private static class WpHolder extends RecyclerView.ViewHolder {

        final ImageView imgThumb;
        final TextView tvTitle;
        final View actions;
        final View btnEdit;
        final View btnDelete;

        WpHolder(@NonNull View itemView) {
            super(itemView);
            imgThumb = itemView.findViewById(R.id.img_thumb);
            tvTitle = itemView.findViewById(R.id.tv_wallpaper_title);
            actions = itemView.findViewById(R.id.item_actions);
            btnEdit = itemView.findViewById(R.id.btn_edit);
            btnDelete = itemView.findViewById(R.id.btn_delete);
        }
    }
}

