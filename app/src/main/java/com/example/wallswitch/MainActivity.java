package com.example.wallswitch;

import android.app.TimePickerDialog;
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
 * 主页：壁纸库列表（缩略图 + 桌面/锁屏范围勾选 + 删除）、添加壁纸、
 * 手动切换桌面/锁屏壁纸按钮与切换模式（顺序/随机）选择。
 */
public class MainActivity extends AppCompatActivity {

    // SharedPreferences 文件名与 key 约定
    private static final String PREFS_NAME = "settings";
    private static final String KEY_MODE = "mode";
    private static final String MODE_ORDER = "order";
    private static final String MODE_RANDOM = "random";
    // 定时类型 key 与取值（与 AlarmScheduler 的 prefs 约定保持一致）
    private static final String KEY_TIMER_TYPE = "timer_type";
    private static final String TIMER_OFF = "off";
    private static final String TIMER_30 = "30";
    private static final String TIMER_60 = "60";
    private static final String TIMER_360 = "360";
    private static final String TIMER_DAILY = "daily";
    private static final String TIMER_CUSTOM = "custom";
    // 定时类型取值顺序（与 R.array.timer_options 一一对应）
    private static final String[] TIMER_TYPES = {TIMER_OFF, TIMER_30, TIMER_60, TIMER_360, TIMER_DAILY, TIMER_CUSTOM};
    // 自定义间隔与每天时刻 key
    private static final String KEY_CUSTOM_MINUTES = "custom_minutes";
    private static final String KEY_DAILY_HOUR = "daily_hour";
    private static final String KEY_DAILY_MINUTE = "daily_minute";
    // 桌面/锁屏启用开关与电池提示 key（开关默认 true，电池提示默认 false）
    private static final String KEY_HOME_ENABLED = "home_enabled";
    private static final String KEY_LOCK_ENABLED = "lock_enabled";
    private static final String KEY_BATTERY_PROMPTED = "battery_prompted";
    // 相册单次多选上限
    private static final int MAX_PICK = 50;

    private ActivityResultLauncher<PickVisualMediaRequest> pickLauncher;
    private RecyclerView recycler;
    private Adapter adapter;
    private SharedPreferences prefs;
    // 当前生效的定时类型（用于回显判断与取消输入时恢复）
    private String lastTimerType = TIMER_OFF;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        // 注册系统相册多选（PickVisualMedia，无需存储权限）
        pickLauncher = registerForActivityResult(
                new ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK),
                uris -> onPicked(uris));
        adapter = new Adapter();
        recycler = findViewById(R.id.recycler);
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        layoutManager.setOrientation(LinearLayoutManager.VERTICAL);
        recycler.setLayoutManager(layoutManager);
        recycler.setAdapter(adapter);
        setupModeViews();
        setupButtons();
        setupEnabledViews();
        setupTimerViews();
        maybePromptBattery();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 收件箱有待编辑项时优先进入编辑页（每次确认/取消后回到这里继续处理下一张）
        List<String> pending = WallpaperStore.pendingInbox(this);
        if (!pending.isEmpty()) {
            openEdit(pending.get(0));
            return;
        }
        refreshList();
    }

    /** 初始化切换模式选择（选中即写 prefs）。 */
    private void setupModeViews() {
        RadioGroup rgMode = findViewById(R.id.rg_mode);
        String mode = prefs.getString(KEY_MODE, MODE_ORDER);
        if (MODE_RANDOM.equals(mode)) {
            rgMode.check(R.id.rb_random);
        } else {
            rgMode.check(R.id.rb_order);
        }
        rgMode.setOnCheckedChangeListener((group, checkedId) -> {
            String newMode = MODE_ORDER;
            if (checkedId == R.id.rb_random) {
                newMode = MODE_RANDOM;
            }
            SharedPreferences.Editor editor = prefs.edit();
            editor.putString(KEY_MODE, newMode);
            editor.apply();
        });
    }

    /** 初始化顶部操作按钮。 */
    private void setupButtons() {
        Button btnAdd = findViewById(R.id.btn_add);
        btnAdd.setOnClickListener(v -> launchPicker());
        Button btnHome = findViewById(R.id.btn_switch_home);
        btnHome.setOnClickListener(v -> Switcher.next(this, true));
        Button btnLock = findViewById(R.id.btn_switch_lock);
        btnLock.setOnClickListener(v -> Switcher.next(this, false));
        Button btnBattery = findViewById(R.id.btn_battery);
        btnBattery.setOnClickListener(v -> requestIgnoreBattery());
    }

    /** 初始化桌面/锁屏启用开关（默认开启）。 */
    private void setupEnabledViews() {
        Switch swHome = findViewById(R.id.sw_home_enabled);
        Switch swLock = findViewById(R.id.sw_lock_enabled);
        swHome.setChecked(prefs.getBoolean(KEY_HOME_ENABLED, true));
        swLock.setChecked(prefs.getBoolean(KEY_LOCK_ENABLED, true));
        swHome.setOnCheckedChangeListener((buttonView, isChecked) -> {
            SharedPreferences.Editor editor = prefs.edit();
            editor.putBoolean(KEY_HOME_ENABLED, isChecked);
            editor.apply();
        });
        swLock.setOnCheckedChangeListener((buttonView, isChecked) -> {
            SharedPreferences.Editor editor = prefs.edit();
            editor.putBoolean(KEY_LOCK_ENABLED, isChecked);
            editor.apply();
        });
    }

    /** 初始化定时频率选择：按 prefs 回显，用户选择后写 prefs 并立即重排闹钟。 */
    private void setupTimerViews() {
        lastTimerType = prefs.getString(KEY_TIMER_TYPE, TIMER_OFF);
        Spinner spinner = findViewById(R.id.spinner_timer);
        ArrayAdapter<CharSequence> arrayAdapter = ArrayAdapter.createFromResource(this,
                R.array.timer_options, android.R.layout.simple_spinner_item);
        arrayAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(arrayAdapter);
        // 按 prefs 回显选中项
        for (int i = 0; i < TIMER_TYPES.length; i++) {
            if (TIMER_TYPES[i].equals(lastTimerType)) {
                spinner.setSelection(i);
                break;
            }
        }
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String type = TIMER_TYPES[position];
                // 回显触发的回调或与当前生效类型相同：视为无变化，避免误弹输入框
                if (type.equals(lastTimerType)) {
                    return;
                }
                onTimerTypeSelected(type);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    /** 处理用户选择的定时类型：间隔型直接生效，自定义/每天先弹输入确认。 */
    private void onTimerTypeSelected(String type) {
        if (TIMER_CUSTOM.equals(type)) {
            showCustomMinutesDialog();
        } else if (TIMER_DAILY.equals(type)) {
            showDailyTimePicker();
        } else {
            applyTimerType(type);
        }
    }

    /** 写入定时类型并立即重排闹钟。 */
    private void applyTimerType(String type) {
        lastTimerType = type;
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(KEY_TIMER_TYPE, type);
        editor.apply();
        AlarmScheduler.schedule(this);
    }

    /** 弹出自定义间隔输入框（单位分钟），确认后写入并生效。 */
    private void showCustomMinutesDialog() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint(R.string.custom_minutes_hint);
        input.setText(String.valueOf(prefs.getInt(KEY_CUSTOM_MINUTES, 30)));
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setPadding(padding, 0, padding, 0);
        wrapper.addView(input);
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.timer_custom);
        builder.setView(wrapper);
        builder.setPositiveButton(R.string.confirm, (dialog, which) -> {
            int minutes = 0;
            try {
                minutes = Integer.parseInt(input.getText().toString().trim());
            } catch (NumberFormatException ignored) {
            }
            // 输入无效：不修改设置，恢复原选中项
            if (minutes <= 0) {
                restoreTimerSpinner();
                return;
            }
            SharedPreferences.Editor editor = prefs.edit();
            editor.putInt(KEY_CUSTOM_MINUTES, minutes);
            editor.apply();
            applyTimerType(TIMER_CUSTOM);
        });
        builder.setNegativeButton(R.string.cancel, null);
        // 取消按钮、返回键关闭都会走 dismiss，统一在此恢复 Spinner 回显
        builder.setOnDismissListener(dialog -> restoreTimerSpinner());
        builder.show();
    }

    /** 弹出每天时刻选择器（默认读 daily_hour/daily_minute，即 8:00），确认后写入并生效。 */
    private void showDailyTimePicker() {
        int hour = prefs.getInt(KEY_DAILY_HOUR, 8);
        int minute = prefs.getInt(KEY_DAILY_MINUTE, 0);
        TimePickerDialog dialog = new TimePickerDialog(this, (view, h, m) -> {
            SharedPreferences.Editor editor = prefs.edit();
            editor.putInt(KEY_DAILY_HOUR, h);
            editor.putInt(KEY_DAILY_MINUTE, m);
            editor.apply();
            applyTimerType(TIMER_DAILY);
        }, hour, minute, true);
        dialog.setOnCancelListener(d -> restoreTimerSpinner());
        dialog.show();
    }

    /** 把定时 Spinner 恢复为当前生效的类型（取消输入时调用）。 */
    private void restoreTimerSpinner() {
        Spinner spinner = findViewById(R.id.spinner_timer);
        for (int i = 0; i < TIMER_TYPES.length; i++) {
            if (TIMER_TYPES[i].equals(lastTimerType)) {
                spinner.setSelection(i);
                break;
            }
        }
    }

    /** 检查电池优化白名单：未忽略且未提示过时弹一次引导。 */
    private void maybePromptBattery() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm.isIgnoringBatteryOptimizations(getPackageName())) {
            return;
        }
        if (prefs.getBoolean(KEY_BATTERY_PROMPTED, false)) {
            return;
        }
        SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean(KEY_BATTERY_PROMPTED, true);
        editor.apply();
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

    /** 打开系统相册多选。 */
    private void launchPicker() {
        PickVisualMediaRequest.Builder builder = new PickVisualMediaRequest.Builder();
        builder.setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE);
        PickVisualMediaRequest request = builder.build();
        pickLauncher.launch(request);
    }

    /** 多选回调：逐张复制进收件箱，然后打开第一张待编辑项。 */
    private void onPicked(List<Uri> uris) {
        if (uris == null || uris.isEmpty()) {
            return;
        }
        int failed = 0;
        for (Uri uri : uris) {
            try {
                WallpaperStore.importToInbox(this, uri);
            } catch (Exception e) {
                failed++;
            }
        }
        if (failed > 0) {
            Toast.makeText(this, R.string.import_failed, Toast.LENGTH_SHORT).show();
        }
        List<String> pending = WallpaperStore.pendingInbox(this);
        if (!pending.isEmpty()) {
            openEdit(pending.get(0));
        }
    }

    /** 打开编辑页处理某个收件箱文件。 */
    private void openEdit(String inboxId) {
        Intent intent = new Intent(this, EditActivity.class);
        intent.putExtra(EditActivity.EXTRA_INBOX_ID, inboxId);
        startActivity(intent);
    }

    /** 刷新壁纸库列表。 */
    private void refreshList() {
        List<WallpaperStore.Item> items = WallpaperStore.load(this);
        adapter.setItems(items);
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

    /** 壁纸库列表适配器。 */
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
            // 先置空监听再回填勾选状态，避免触发意外写回
            holder.cbHome.setOnCheckedChangeListener(null);
            holder.cbLock.setOnCheckedChangeListener(null);
            holder.cbHome.setChecked(item.home);
            holder.cbLock.setChecked(item.lock);
            holder.cbHome.setOnCheckedChangeListener((buttonView, isChecked) -> {
                item.home = isChecked;
                WallpaperStore.setScope(MainActivity.this, item.id, item.home, item.lock);
            });
            holder.cbLock.setOnCheckedChangeListener((buttonView, isChecked) -> {
                item.lock = isChecked;
                WallpaperStore.setScope(MainActivity.this, item.id, item.home, item.lock);
            });
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
        final CheckBox cbHome;
        final CheckBox cbLock;
        final ImageButton btnDelete;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            imgThumb = itemView.findViewById(R.id.img_thumb);
            cbHome = itemView.findViewById(R.id.cb_home);
            cbLock = itemView.findViewById(R.id.cb_lock);
            btnDelete = itemView.findViewById(R.id.btn_delete);
        }
    }
}
