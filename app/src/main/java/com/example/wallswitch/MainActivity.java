package com.example.wallswitch;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Filter;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 主页：多壁纸库管理——库选择/新建/删除，每库设置（启用开关、桌面/锁屏范围、
 * 顺序/随机模式、切换间隔（分钟，最小 15）），添加壁纸到当前库、库内壁纸列表、手动切换测试。
 */
public class MainActivity extends AppCompatActivity {

    // SharedPreferences 文件名
    private static final String PREFS_NAME = "settings";
    // 相册单次多选上限
    private static final int MAX_PICK = 50;
    // 预览解码的最长边上限（与 WallpaperStore/Switcher 保持一致，防 OOM）
    private static final int PREVIEW_MAX_DIM = 2048;
    // 通知权限提示是否已弹过的记录 key（避免每次打开应用都打扰）
    private static final String KEY_NOTIFY_PROMPTED = "notify_prompted";

    private ActivityResultLauncher<PickVisualMediaRequest> pickLauncher;
    // 通知权限请求（Android 13+ 自动切换提示需要 POST_NOTIFICATIONS）
    private ActivityResultLauncher<String> notifyPermissionLauncher;
    private RecyclerView recycler;
    private Adapter adapter;
    private SharedPreferences prefs;
    // 当前选中的壁纸库 id
    private String currentLibId;
    // 说明：v3.0 把库选择从系统 Spinner 换成暴露式下拉（AutoCompleteTextView）。
    // 下拉的“点击选中”只在用户点选时回调，程序化 setText 不会触发，
    // 因此旧版用于抑制回填回调的 suppressLibCallback 开关已不再需要。

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        // 全面屏/刘海屏适配：内容避开状态栏、刘海与底部手势条（Android 15 强制边到边）
        InsetsHelper.apply(this, R.id.main_root);
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        // 注册系统相册多选（PickVisualMedia，无需存储权限）
        pickLauncher = registerForActivityResult(
                new ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK),
                this::onPicked);
        // 注册通知权限请求（Android 13+）：未授权时自动切换通知发不出来
        notifyPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                this::onNotifyPermissionResult);
        recycler = findViewById(R.id.recycler);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new Adapter();
        recycler.setAdapter(adapter);
        setupTabs();
        setupLibViews();
        setupButtons();
        setupTimerSwitch();
        setupNotifySwitch();
        // 电池优化引导（荣耀等机型避免后台被杀）
        maybePromptBattery();
        // 标题显示版本号：便于确认手机上安装的是哪一版（每次提交都会递增）
        showVersionInTitle();
    }

    /** 标题栏显示「应用名 v版本名」，版本取自安装包信息（与 build.gradle 的 versionName 一致）。 */
    private void showVersionInTitle() {
        String version = null;
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        if (version == null || version.isEmpty()) {
            return;
        }
        Toolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            toolbar.setTitle(getString(R.string.title_with_version, getString(R.string.app_name), version));
        }
    }

    /** 底部双 Tab：壁纸 / 设置。两个页面用 visibility 切换，不引入 ViewPager2 依赖。 */
    private void setupTabs() {
        BottomNavigationView nav = findViewById(R.id.bottom_nav);
        if (nav == null) {
            return;
        }
        nav.setOnItemSelectedListener(item -> {
            boolean showWallpapers = item.getItemId() == R.id.nav_wallpapers;
            findViewById(R.id.page_wallpapers).setVisibility(showWallpapers ? View.VISIBLE : View.GONE);
            findViewById(R.id.page_settings).setVisibility(showWallpapers ? View.GONE : View.VISIBLE);
            // 切到设置页时同步一次状态（可能刚在壁纸页改动过库/壁纸）
            if (!showWallpapers) {
                refreshLibSettings();
                refreshTimerStatus();
                refreshBatteryButton();
            }
            return true;
        });
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
                        refreshList();
                    }
                });
            }
        }, "app-catchup").start();
        // 同步刷新桌面小组件（库的启用状态、当前壁纸可能已变化）
        WidgetProvider.updateWidget(this);
        // 引擎模式状态（桌面是否由本 App 的动态壁纸引擎直接渲染）
        ((TextView) findViewById(R.id.tv_engine)).setText(
                WallSwitchService.isActive(this) ? R.string.engine_active : R.string.engine_hint);
        List<String> pending = WallpaperStore.pendingInbox(this);
        if (!pending.isEmpty()) {
            // 还有待编辑项：继续逐张处理
            openEdit(pending.get(0), currentLibId());
            return;
        }
        refreshAll();
    }

    /** 当前库 id（未初始化时从持久化恢复）。 */
    private String currentLibId() {
        if (currentLibId == null) {
            currentLibId = prefs.getString(LibraryStore.KEY_CURRENT_LIB, null);
        }
        return currentLibId;
    }

    /** 当前选中的库，可能为 null（库列表为空时）。 */
    private LibraryStore.Library currentLib() {
        String id = currentLibId();
        if (id == null) {
            return null;
        }
        return LibraryStore.get(this, id);
    }

    /** 刷新库列表 Spinner、当前库设置区与壁纸列表。 */
    private void refreshAll() {
        List<LibraryStore.Library> libs = LibraryStore.load(this);
        if (libs.isEmpty()) {
            LibraryStore.create(this, null);
            libs = LibraryStore.load(this);
        }
        // 校正当前库选择（被删除时回退到第一个）
        String saved = currentLibId();
        boolean found = false;
        for (LibraryStore.Library lib : libs) {
            if (lib.id.equals(saved)) {
                found = true;
                break;
            }
        }
        if (!found) {
            currentLibId = libs.get(0).id;
            prefs.edit().putString(LibraryStore.KEY_CURRENT_LIB, currentLibId).apply();
        }
        // 回填库选择下拉：程序化 setText(text, false) 不会触发 onItemClick，无需抑制回调
        AutoCompleteTextView selector = findViewById(R.id.lib_selector);
        List<String> names = new ArrayList<>();
        String currentName = null;
        for (LibraryStore.Library lib : libs) {
            names.add(lib.name);
            if (lib.id.equals(currentLibId)) {
                currentName = lib.name;
            }
        }
        selector.setAdapter(new NoFilterAdapter(this, names));
        if (currentName != null) {
            selector.setText(currentName, false);
        }
        refreshLibSettings();
        refreshList();
        // 定时状态行与电池优化状态（判断后台是否能被唤醒）
        refreshTimerStatus();
        refreshBatteryButton();
    }

    /** 初始化库选择下拉与顶栏菜单（新建库常驻图标 / 删除库收在溢出菜单）。 */
    private void setupLibViews() {
        AutoCompleteTextView selector = findViewById(R.id.lib_selector);
        // 输出框不可输入（inputType=none），点一下直接展开全部库；
        // threshold=0 保证不输入内容也能展开，过滤已由 NoFilterAdapter 关闭
        selector.setThreshold(0);
        selector.setOnClickListener(v -> selector.showDropDown());
        selector.setOnItemClickListener((parent, view, position, id) -> {
            List<LibraryStore.Library> libs = LibraryStore.load(MainActivity.this);
            if (position < 0 || position >= libs.size()) {
                return;
            }
            String newId = libs.get(position).id;
            if (newId.equals(currentLibId)) {
                return;
            }
            currentLibId = newId;
            prefs.edit().putString(LibraryStore.KEY_CURRENT_LIB, currentLibId).apply();
            refreshLibSettings();
            refreshList();
        });
        Toolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            toolbar.setOnMenuItemClickListener(item -> {
                int id = item.getItemId();
                if (id == R.id.action_lib_new) {
                    showNewLibDialog();
                    return true;
                }
                if (id == R.id.action_lib_delete) {
                    confirmDeleteLib();
                    return true;
                }
                return false;
            });
        }
    }

    /** 手动切换/添加/电池按钮。 */
    private void setupButtons() {
        findViewById(R.id.btn_add).setOnClickListener(v -> launchPicker());
        findViewById(R.id.btn_switch_home).setOnClickListener(v -> switchAndToast(true));
        findViewById(R.id.btn_switch_lock).setOnClickListener(v -> switchAndToast(false));
        findViewById(R.id.btn_battery).setOnClickListener(v -> requestIgnoreBattery());
        // v2.0：引擎模式引导——打开系统动态壁纸选择器并预选本引擎
        findViewById(R.id.btn_engine).setOnClickListener(v -> WallSwitchService.openActivator(this));
        // 电池优化是否已允许，直接显示在按钮上（决定后台定时能否被系统唤醒）
        refreshBatteryButton();
    }

    /** 定时切换总开关：关闭时不排定任何定时任务（手动切换与小组件不受影响）。 */
    private void setupTimerSwitch() {
        CompoundButton swTimer = findViewById(R.id.sw_timer_enabled);
        swTimer.setOnCheckedChangeListener(null);
        swTimer.setChecked(TimerScheduler.isTimerEnabled(this));
        swTimer.setOnCheckedChangeListener((buttonView, isChecked) -> {
            TimerScheduler.setTimerEnabled(MainActivity.this, isChecked);
            refreshTimerStatus();
        });
    }

    /**
     * 自动切换提示开关：自动切换（定时与补切）完成后发系统通知——成功静音留痕、失败弹横幅。
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

    /** 回填当前库的设置区（启用开关、范围、模式、间隔），并绑定监听。 */
    private void refreshLibSettings() {
        CompoundButton swEnabled = findViewById(R.id.sw_lib_enabled);
        CompoundButton swHome = findViewById(R.id.sw_lib_home);
        CompoundButton swLock = findViewById(R.id.sw_lib_lock);
        RadioGroup rgMode = findViewById(R.id.rg_mode);
        TextView tvInterval = findViewById(R.id.tv_interval);
        View rowInterval = findViewById(R.id.row_interval);
        LibraryStore.Library lib = currentLib();
        // 先置空监听再回填，避免 setChecked 触发意外写回
        swEnabled.setOnCheckedChangeListener(null);
        swHome.setOnCheckedChangeListener(null);
        swLock.setOnCheckedChangeListener(null);
        rgMode.setOnCheckedChangeListener(null);
        if (lib == null) {
            swEnabled.setChecked(false);
            swHome.setChecked(false);
            swLock.setChecked(false);
            tvInterval.setText(R.string.lib_interval);
            return;
        }
        swEnabled.setChecked(lib.enabled);
        swHome.setChecked(lib.home);
        swLock.setChecked(lib.lock);
        rgMode.check(LibraryStore.MODE_RANDOM.equals(lib.mode) ? R.id.rb_random : R.id.rb_order);
        // 行标签已独立显示「切换间隔」，这里只显示值本身（可点整行修改）
        tvInterval.setText(formatInterval(lib.intervalSeconds));
        // 启用开关：启用失败（未选范围）时回退并提示
        swEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
            boolean ok = LibraryStore.setEnabled(MainActivity.this, currentLibId(), isChecked);
            if (!ok) {
                swEnabled.setChecked(false);
                Toast.makeText(MainActivity.this, R.string.lib_scope_none, Toast.LENGTH_SHORT).show();
            }
            refreshTimerStatus();
        });
        // 范围勾选（启用中的库修改范围会应用互斥约束）
        swHome.setOnCheckedChangeListener((buttonView, isChecked) -> {
            LibraryStore.Library target = currentLib();
            if (target != null) {
                LibraryStore.setScope(MainActivity.this, target.id, isChecked, swLock.isChecked());
            }
            refreshTimerStatus();
        });
        swLock.setOnCheckedChangeListener((buttonView, isChecked) -> {
            LibraryStore.Library target = currentLib();
            if (target != null) {
                LibraryStore.setScope(MainActivity.this, target.id, swHome.isChecked(), isChecked);
            }
            refreshTimerStatus();
        });
        // 切换模式（顺序/随机，按库保存）
        rgMode.setOnCheckedChangeListener((group, checkedId) ->
                LibraryStore.setMode(MainActivity.this, currentLibId(),
                        checkedId == R.id.rb_random ? LibraryStore.MODE_RANDOM : LibraryStore.MODE_ORDER));
        // 切换间隔（分钟级，最小 15）：整行可点
        rowInterval.setOnClickListener(v -> showIntervalDialog());
    }

    /** 弹窗输入切换间隔（分钟，最小 15），确认后写回并重排启用中的定时。 */
    private void showIntervalDialog() {
        LibraryStore.Library lib = currentLib();
        if (lib == null) {
            return;
        }
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint(R.string.interval_hint);
        // 历史值可能是秒级（旧版本遗留），这里换算成分钟并保证 ≥15
        input.setText(String.valueOf(Math.max(LibraryStore.MIN_INTERVAL_SECONDS / 60,
                lib.intervalSeconds / 60)));
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        wrapper.setPadding(padding, 0, padding, 0);
        wrapper.addView(input);
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.lib_interval);
        builder.setMessage(R.string.interval_hint);
        builder.setView(wrapper);
        builder.setPositiveButton(R.string.confirm, (dialog, which) -> {
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
                refreshLibSettings();
                refreshTimerStatus();
            }
        });
        builder.setNegativeButton(R.string.cancel, null);
        builder.show();
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
     * 定时状态行：上次执行结果 + 下次预计时间；若已过预计时间仍未见系统执行，
     * 说明被 Doze/ROM 冻结拦住了（等待调度，点亮屏幕/刷新小组件/打开本应用会自动补切）。
     */
    private void refreshTimerStatus() {
        TextView tv = findViewById(R.id.tv_timer_status);
        if (tv == null) {
            return;
        }
        if (!TimerScheduler.isTimerEnabled(this)) {
            tv.setText(R.string.timer_status_off);
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

    /** 电池优化按钮文案：直接显示当前是否已允许（决定后台定时能否被系统唤醒）。 */
    private void refreshBatteryButton() {
        Button btn = findViewById(R.id.btn_battery);
        if (btn != null) {
            btn.setText(isIgnoringBatteryOptimizations()
                    ? R.string.battery_allowed : R.string.battery_denied);
        }
    }

    /** 是否已允许忽略电池优化。 */
    private boolean isIgnoringBatteryOptimizations() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    /** 新建库弹窗。 */
    private void showNewLibDialog() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint(R.string.lib_name_hint);
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        wrapper.setPadding(padding, 0, padding, 0);
        wrapper.addView(input);
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.lib_new);
        builder.setView(wrapper);
        builder.setPositiveButton(R.string.confirm, (dialog, which) -> {
            String name = input.getText().toString().trim();
            LibraryStore.Library lib = LibraryStore.create(MainActivity.this,
                    name.isEmpty() ? null : name);
            currentLibId = lib.id;
            prefs.edit().putString(LibraryStore.KEY_CURRENT_LIB, currentLibId).apply();
            refreshAll();
        });
        builder.setNegativeButton(R.string.cancel, null);
        builder.show();
    }

    /** 删除当前库前弹确认框（库内壁纸一并删除）。 */
    private void confirmDeleteLib() {
        LibraryStore.Library lib = currentLib();
        if (lib == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.lib_delete);
        builder.setMessage(getString(R.string.lib_delete_msg, lib.name));
        builder.setPositiveButton(R.string.confirm, (dialog, which) -> {
            LibraryStore.delete(MainActivity.this, lib.id);
            currentLibId = null;
            refreshAll();
        });
        builder.setNegativeButton(R.string.cancel, null);
        builder.show();
    }

    /** 手动切换：作用于该范围当前启用的库（与小组件、定时行为一致）。
     *  切换含大图解码、系统调用与失败时的延迟重试（Switcher 内部），放后台线程避免卡 UI。 */
    private void switchAndToast(final boolean forHome) {
        final LibraryStore.Library lib = LibraryStore.enabledLibForScope(this, forHome);
        if (lib == null) {
            Toast.makeText(this, R.string.switch_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        final Context app = getApplicationContext();
        new Thread(() -> {
            final boolean ok = Switcher.next(app, lib.id, forHome);
            runOnUiThread(() -> {
                if (ok) {
                    Toast.makeText(this, R.string.switch_done, Toast.LENGTH_SHORT).show();
                } else {
                    // 带上具体失败原因（如桌面被动态壁纸占用），方便用户对症处理
                    Toast.makeText(this, Switcher.errorText(this, Switcher.lastError()),
                            Toast.LENGTH_LONG).show();
                }
            });
        }, "manual-switch").start();
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
        Button btnAdd = findViewById(R.id.btn_add);
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

    /** 刷新壁纸列表（当前库内的壁纸），并同步空状态显隐。 */
    private void refreshList() {
        String libId = currentLibId();
        List<WallpaperStore.Item> items = libId == null
                ? new ArrayList<>() : WallpaperStore.loadByLib(this, libId);
        adapter.setItems(items);
        // 库内没有壁纸时给出空状态引导，避免一片留白且不知道下一步做什么
        View empty = findViewById(R.id.empty_state);
        if (empty != null) {
            empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        }
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
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.battery_prompt_title);
        builder.setMessage(R.string.battery_prompt_msg);
        builder.setPositiveButton(R.string.confirm, (dialog, which) -> requestIgnoreBattery());
        // 荣耀/华为还需在应用详情里开启「自启动/后台运行」
        builder.setNeutralButton(R.string.app_details, (dialog, which) -> openAppDetails());
        builder.setNegativeButton(R.string.cancel, null);
        builder.show();
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

    /** 删除前弹确认框。 */
    private void confirmDelete(WallpaperStore.Item item) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.delete_confirm_title);
        builder.setMessage(R.string.delete_confirm_msg);
        builder.setPositiveButton(R.string.confirm, (dialog, which) -> {
            WallpaperStore.delete(this, item.id);
            refreshList();
        });
        builder.setNegativeButton(R.string.cancel, null);
        builder.show();
    }

    /**
     * 点击缩略图：后台解码库内全图并弹窗预览。
     * 预览按图片原始比例整张显示（不裁剪、不补黑边，超高可滚动），
     * 下方显示「标题 + 该文件在库中的真实像素尺寸」——用于判断库里存的到底是一张完整图，
     * 还是被裁过/比例不对（例如只有屏幕上那一块）的结果图。
     * 弹窗里的「改标题」可直接重命名（通知会带上这个标题）。
     */
    private void showPreview(WallpaperStore.Item item) {
        View content = LayoutInflater.from(this).inflate(R.layout.dialog_preview, null, false);
        ImageView preview = content.findViewById(R.id.img_preview);
        TextView info = content.findViewById(R.id.tv_preview_info);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.preview_title)
                .setView(content)
                .setNeutralButton(R.string.rename_title, (d, which) -> showRenameDialog(item))
                .setPositiveButton(R.string.close, null)
                .create();
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
            final Bitmap bitmap = WallpaperStore.decodeBounded(file, PREVIEW_MAX_DIM);
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
                info.setText(previewInfoText(item, width, height));
            });
        }, "thumb-preview").start();
    }

    /** 预览信息行：标题 + 实际像素尺寸。 */
    private String previewInfoText(WallpaperStore.Item item, int width, int height) {
        String title = item.title == null || item.title.isEmpty() ? getString(R.string.untitled) : item.title;
        return getString(R.string.title_label, title) + "\n" + getString(R.string.preview_info, width, height);
    }

    /** 重命名壁纸标题（通知里会带上这个标题，便于区分切到了哪张）。 */
    private void showRenameDialog(WallpaperStore.Item item) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint(R.string.rename_hint);
        input.setText(item.title == null ? "" : item.title);
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        wrapper.setPadding(padding, 0, padding, 0);
        wrapper.addView(input);
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.rename_title);
        builder.setView(wrapper);
        builder.setPositiveButton(R.string.confirm, (dialog, which) -> {
            WallpaperStore.setTitle(this, item.id, input.getText().toString().trim());
            refreshList();
        });
        builder.setNegativeButton(R.string.cancel, null);
        builder.show();
    }

    /**
     * 库选择下拉的适配器：关闭 AutoCompleteTextView 的输入过滤。
     * 默认的 ArrayAdapter 过滤器会按当前显示文本（即当前库名）过滤选项，
     * 导致下拉里只剩当前这一个库；这里让过滤原样返回全部条目。
     */
    private static class NoFilterAdapter extends ArrayAdapter<String> {

        private final List<String> all;
        private final Filter passThrough = new Filter() {
            @Override
            protected FilterResults performFiltering(CharSequence constraint) {
                FilterResults results = new FilterResults();
                results.values = all;
                results.count = all.size();
                return results;
            }

            @Override
            protected void publishResults(CharSequence constraint, FilterResults results) {
                notifyDataSetChanged();
            }
        };

        NoFilterAdapter(Context context, List<String> items) {
            super(context, android.R.layout.simple_list_item_1, items);
            this.all = new ArrayList<>(items);
        }

        @NonNull
        @Override
        public Filter getFilter() {
            return passThrough;
        }
    }

    /** 壁纸列表适配器（当前库内的壁纸）。 */
    private class Adapter extends RecyclerView.Adapter<ViewHolder> {

        private List<WallpaperStore.Item> items = new ArrayList<>();

        void setItems(List<WallpaperStore.Item> newItems) {
            items = newItems;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(MainActivity.this);
            View view = inflater.inflate(R.layout.item_wallpaper, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            final WallpaperStore.Item item = items.get(position);
            Bitmap thumb = WallpaperStore.getThumb(MainActivity.this, item.id);
            holder.imgThumb.setImageBitmap(thumb);
            // 缩略图旁显示壁纸标题（未命名则用占位文案）
            holder.tvTitle.setText(item.title == null || item.title.isEmpty()
                    ? getString(R.string.untitled) : item.title);
            // 点缩略图看全图与真实像素尺寸（列表缩略图太小，无法判断图是否被裁过/比例是否正常）
            holder.imgThumb.setOnClickListener(v -> showPreview(item));
            holder.btnDelete.setOnClickListener(v -> confirmDelete(item));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    /** 列表项视图持有者。 */
    private static class ViewHolder extends RecyclerView.ViewHolder {

        final ImageView imgThumb;
        final TextView tvTitle;
        final ImageButton btnDelete;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            imgThumb = itemView.findViewById(R.id.img_thumb);
            tvTitle = itemView.findViewById(R.id.tv_wallpaper_title);
            btnDelete = itemView.findViewById(R.id.btn_delete);
        }
    }
}