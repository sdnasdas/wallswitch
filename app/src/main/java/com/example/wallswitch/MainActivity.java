package com.example.wallswitch;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * 主页：多壁纸库管理——库选择/新建/删除，每库设置（启用开关、桌面/锁屏范围、
 * 顺序/随机模式、秒级切换间隔），添加壁纸到当前库、库内壁纸列表、手动切换测试。
 */
public class MainActivity extends AppCompatActivity {

    // SharedPreferences 文件名
    private static final String PREFS_NAME = "settings";
    // 相册单次多选上限
    private static final int MAX_PICK = 50;

    private ActivityResultLauncher<PickVisualMediaRequest> pickLauncher;
    private RecyclerView recycler;
    private Adapter adapter;
    private SharedPreferences prefs;
    // 当前选中的壁纸库 id
    private String currentLibId;
    // Spinner 数据回填期间抑制选择回调，避免误触发切换库
    private boolean suppressLibCallback = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        // 注册系统相册多选（PickVisualMedia，无需存储权限）
        pickLauncher = registerForActivityResult(
                new ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK),
                this::onPicked);
        recycler = findViewById(R.id.recycler);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new Adapter();
        recycler.setAdapter(adapter);
        setupLibViews();
        setupButtons();
        // 电池优化引导（荣耀等机型避免后台被杀）
        maybePromptBattery();
    }

    @Override
    protected void onResume() {
        super.onResume();
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
        // 回填库 Spinner
        Spinner spinner = findViewById(R.id.spinner_libs);
        List<String> names = new ArrayList<>();
        int selected = 0;
        for (int i = 0; i < libs.size(); i++) {
            names.add(libs.get(i).name);
            if (libs.get(i).id.equals(currentLibId)) {
                selected = i;
            }
        }
        suppressLibCallback = true;
        ArrayAdapter<String> arrayAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, names);
        arrayAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(arrayAdapter);
        spinner.setSelection(selected, false);
        // 数据绑定完成后再恢复回调（post 保证在本次事件循环之后执行）
        spinner.post(() -> suppressLibCallback = false);
        refreshLibSettings();
        refreshList();
    }

    /** 初始化库选择器与新建/删除按钮。 */
    private void setupLibViews() {
        Spinner spinner = findViewById(R.id.spinner_libs);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (suppressLibCallback) {
                    return;
                }
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
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        findViewById(R.id.btn_lib_new).setOnClickListener(v -> showNewLibDialog());
        findViewById(R.id.btn_lib_delete).setOnClickListener(v -> confirmDeleteLib());
    }

    /** 手动切换/添加/电池按钮。 */
    private void setupButtons() {
        findViewById(R.id.btn_add).setOnClickListener(v -> launchPicker());
        findViewById(R.id.btn_switch_home).setOnClickListener(v -> switchAndToast(true));
        findViewById(R.id.btn_switch_lock).setOnClickListener(v -> switchAndToast(false));
        findViewById(R.id.btn_battery).setOnClickListener(v -> requestIgnoreBattery());
    }

    /** 回填当前库的设置区（启用开关、范围、模式、间隔），并绑定监听。 */
    private void refreshLibSettings() {
        Switch swEnabled = findViewById(R.id.sw_lib_enabled);
        CheckBox cbHome = findViewById(R.id.cb_lib_home);
        CheckBox cbLock = findViewById(R.id.cb_lib_lock);
        RadioGroup rgMode = findViewById(R.id.rg_mode);
        TextView tvInterval = findViewById(R.id.tv_interval);
        LibraryStore.Library lib = currentLib();
        // 先置空监听再回填，避免 setChecked 触发意外写回
        swEnabled.setOnCheckedChangeListener(null);
        cbHome.setOnCheckedChangeListener(null);
        cbLock.setOnCheckedChangeListener(null);
        rgMode.setOnCheckedChangeListener(null);
        if (lib == null) {
            swEnabled.setChecked(false);
            cbHome.setChecked(false);
            cbLock.setChecked(false);
            tvInterval.setText(R.string.lib_interval);
            return;
        }
        swEnabled.setChecked(lib.enabled);
        cbHome.setChecked(lib.home);
        cbLock.setChecked(lib.lock);
        rgMode.check(LibraryStore.MODE_RANDOM.equals(lib.mode) ? R.id.rb_random : R.id.rb_order);
        tvInterval.setText(getString(R.string.lib_interval) + "：" + formatInterval(lib.intervalSeconds));
        // 启用开关：启用失败（未选范围）时回退并提示
        swEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
            boolean ok = LibraryStore.setEnabled(MainActivity.this, currentLibId(), isChecked);
            if (!ok) {
                swEnabled.setChecked(false);
                Toast.makeText(MainActivity.this, R.string.lib_scope_none, Toast.LENGTH_SHORT).show();
            }
        });
        // 范围勾选（启用中的库修改范围会应用互斥约束）
        cbHome.setOnCheckedChangeListener((buttonView, isChecked) -> {
            LibraryStore.Library target = currentLib();
            if (target != null) {
                LibraryStore.setScope(MainActivity.this, target.id, isChecked, cbLock.isChecked());
            }
        });
        cbLock.setOnCheckedChangeListener((buttonView, isChecked) -> {
            LibraryStore.Library target = currentLib();
            if (target != null) {
                LibraryStore.setScope(MainActivity.this, target.id, cbHome.isChecked(), isChecked);
            }
        });
        // 切换模式（顺序/随机，按库保存）
        rgMode.setOnCheckedChangeListener((group, checkedId) ->
                LibraryStore.setMode(MainActivity.this, currentLibId(),
                        checkedId == R.id.rb_random ? LibraryStore.MODE_RANDOM : LibraryStore.MODE_ORDER));
        // 切换间隔（秒级）
        tvInterval.setOnClickListener(v -> showIntervalDialog());
    }

    /** 弹窗输入切换间隔（秒），确认后写回并重排启用中的定时。 */
    private void showIntervalDialog() {
        LibraryStore.Library lib = currentLib();
        if (lib == null) {
            return;
        }
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint(R.string.interval_hint);
        input.setText(String.valueOf(lib.intervalSeconds));
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        wrapper.setPadding(padding, 0, padding, 0);
        wrapper.addView(input);
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.lib_interval);
        builder.setView(wrapper);
        builder.setPositiveButton(R.string.confirm, (dialog, which) -> {
            int seconds = 0;
            try {
                seconds = Integer.parseInt(input.getText().toString().trim());
            } catch (NumberFormatException ignored) {
            }
            if (seconds > 0) {
                LibraryStore.setInterval(MainActivity.this, lib.id, seconds);
                refreshLibSettings();
            }
        });
        builder.setNegativeButton(R.string.cancel, null);
        builder.show();
    }

    /** 间隔展示：60 的整数倍显示分钟，其余显示「X分Y秒」或「X秒」。 */
    private String formatInterval(int seconds) {
        if (seconds >= 60 && seconds % 60 == 0) {
            return (seconds / 60) + "分钟";
        }
        if (seconds >= 60) {
            return (seconds / 60) + "分" + (seconds % 60) + "秒";
        }
        return seconds + "秒";
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

    /** 手动切换：作用于该范围当前启用的库（与小组件、定时行为一致）。 */
    private void switchAndToast(boolean forHome) {
        LibraryStore.Library lib = LibraryStore.enabledLibForScope(this, forHome);
        boolean ok = lib != null && Switcher.next(this, lib.id, forHome);
        if (ok) {
            Toast.makeText(this, R.string.switch_done, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, R.string.switch_failed, Toast.LENGTH_SHORT).show();
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

    /** 刷新壁纸列表（当前库内的壁纸）。 */
    private void refreshList() {
        String libId = currentLibId();
        List<WallpaperStore.Item> items = libId == null
                ? new ArrayList<>() : WallpaperStore.loadByLib(this, libId);
        adapter.setItems(items);
    }

    /** 检查电池优化白名单：未忽略且未提示过时弹一次引导。 */
    private void maybePromptBattery() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm.isIgnoringBatteryOptimizations(getPackageName())) {
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
        builder.setNegativeButton(R.string.cancel, null);
        builder.show();
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
        final ImageButton btnDelete;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            imgThumb = itemView.findViewById(R.id.img_thumb);
            btnDelete = itemView.findViewById(R.id.btn_delete);
        }
    }
}