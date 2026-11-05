package com.example.wallswitch;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.color.MaterialColors;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 设置页（v3.134，原左侧抽屉里那六张卡片）。
 *
 * <p>条目全都 {@code persistent=false}：值还存在各自的老 key 里，这里只负责现读现写，
 * 所以备份包、还原、通知与小组件读的那几处一个字都不用动。
 * 要用到系统选择器（导出目录、底图、备份包）或要弹进度窗的动作，交回 {@link MainActivity} 去办 ——
 * 那几个 launcher 注册在 Activity 上，设置页只是入口之一（此刻页、启动提示也在用）。
 */
public class SettingsFragment extends PreferenceFragmentCompat {

    private static final String ARG_SCREEN = "settings_screen_key";
    private static final String SYSTEM_SCREEN = "settings_system";

    private final List<List<Preference>> visualGroups = new ArrayList<>();
    private final List<PreferenceCategory> groupHeaders = new ArrayList<>();
    private PreferenceScreen homeScreen;
    private GroupCardDecoration groupDecoration;

    static SettingsFragment secondary(String screenKey) {
        SettingsFragment fragment = new SettingsFragment();
        Bundle args = new Bundle();
        args.putString(ARG_SCREEN, screenKey);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.preferences, null);
        homeScreen = getPreferenceScreen();
        Bundle args = getArguments();
        String key = args == null ? null : args.getString(ARG_SCREEN);
        if (key == null) {
            // 「系统与权限」只是那一页的容器：首页进它走顶部「壁纸接管」那一行，不留第二颗门
            Preference system = homeScreen.findPreference(SYSTEM_SCREEN);
            if (system != null) {
                system.setVisible(false);
            }
        }
        Preference target = key == null ? null : homeScreen.findPreference(key);
        applyScreen(target instanceof PreferenceScreen ? (PreferenceScreen) target : homeScreen);
    }

    private void bindActions() {
        click("takeover_status", () -> {
            Preference screen = homeScreen.findPreference(SYSTEM_SCREEN);
            if (screen instanceof PreferenceScreen) {
                openSecondary((PreferenceScreen) screen);
            }
        });
        click("takeover", () -> host().onTakeoverRowTapped());
        click("battery", () -> host().requestIgnoreBattery());
        click("app_details", () -> host().openAppDetails());
        click("save_wallpaper", () -> host().startSaveCurrentWallpaper());
        toggle("auto_notify", on -> {
            SwitchNotifier.setEnabled(requireContext(), on);
            if (on) {
                host().ensureNotificationsEnabled();
            }
        });
        toggle("status_notify", on -> {
            StatusNotifier.setEnabled(requireContext(), on);
            if (on) {
                host().ensureNotificationsEnabled();
                StatusNotifier.update(requireContext());
            }
        });
        toggle("ticking", on -> {
            TimerScheduler.setTickingCountdown(requireContext(), on);
            // 立即按新样式重建两处显示，免得"设置改了、通知/小组件还是旧样式"
            StatusNotifier.update(requireContext());
            WidgetProvider.updateWidget(requireContext());
        });
        click("pinned", this::onPinnedTapped);
        click("transition", this::pickTransition);
        click("motion", this::pickMotion);
        click(QuickSetSheetDialog.KEY_DISMISS_THRESHOLD_DP, this::pickQuickSheetThreshold);
        click("launcher_overlay", () -> {
            if (LauncherPreviewOverlay.overlayFile(requireContext()).exists()) {
                host().showLauncherOverlayMenu();
            } else {
                host().pickLauncherOverlay();
            }
        });
        click("export_dir", this::onExportDirTapped);
        click("live_converter", () -> host().pickLiveConverterVideo());
        click("export_now", () -> host().exportAllWallpapers());
        click("backup", () -> host().startBackup());
        click("restore", () -> host().launchRestorePicker());
        click("update_source", this::pickUpdateSource);
        toggle("auto_update", on -> UpdateChecker.setAutoCheck(requireContext(), on));
        click("update", () -> host().onUpdateRowTapped());
    }

    @Override
    public void onNavigateToScreen(@NonNull PreferenceScreen preferenceScreen) {
        openSecondary(preferenceScreen);
    }

    private void openSecondary(PreferenceScreen screen) {
        host().openSettingsSecondary(screen.getKey(), screen.getTitle());
    }

    private void applyScreen(PreferenceScreen screen) {
        setPreferenceScreen(screen);
        configureGroupedPresentation();
        bindActions();
        refreshAll();
    }

    private void configureGroupedPresentation() {
        visualGroups.clear();
        groupHeaders.clear();
        if (getPreferenceScreen() != null) {
            collectVisualGroups(getPreferenceScreen());
        }
    }

    private void collectVisualGroups(PreferenceGroup group) {
        List<Preference> rows = new ArrayList<>();
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference child = group.getPreference(i);
            if (!child.isVisible()) {
                continue;
            }
            if (child instanceof PreferenceCategory) {
                if (!rows.isEmpty()) {
                    visualGroups.add(rows);
                    rows = new ArrayList<>();
                }
                groupHeaders.add((PreferenceCategory) child);
                collectVisualGroups((PreferenceCategory) child);
            } else {
                // 子 PreferenceScreen 是当前页的一行，不把它的内容画进首页卡片。
                rows.add(child);
                if (!(child instanceof SwitchPreferenceCompat) && child.isSelectable()) {
                    child.setWidgetLayoutResource(R.layout.pref_chevron_widget);
                }
            }
        }
        if (!rows.isEmpty()) {
            visualGroups.add(rows);
        }
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        setDivider(null);
        int side = dp(16);
        RecyclerView list = getListView();
        list.setVerticalScrollBarEnabled(false);
        list.setHorizontalScrollBarEnabled(false);
        list.setAlpha(1f);
        list.setTranslationX(0f);
        list.setPadding(side, 0, side, dp(24));
        list.setClipToPadding(false);
        list.setItemAnimator(null);
    }

    @Override
    public void onBindPreferences() {
        super.onBindPreferences();
        RecyclerView list = getListView();
        list.setVerticalScrollBarEnabled(false);
        list.setHorizontalScrollBarEnabled(false);
        if (groupDecoration != null) {
            list.removeItemDecoration(groupDecoration);
        }
        RecyclerView.Adapter<?> adapter = list.getAdapter();
        if (adapter instanceof PreferenceGroup.PreferencePositionCallback) {
            groupDecoration = new GroupCardDecoration(
                    (PreferenceGroup.PreferencePositionCallback) adapter);
            list.addItemDecoration(groupDecoration);
        }
        tintPreferenceIcons(getPreferenceScreen(), list.getContext().getColor(R.color.icon_neutral));
        refreshAll();
    }

    @Override
    public void onDestroyView() {
        if (groupDecoration != null) {
            getListView().removeItemDecoration(groupDecoration);
            groupDecoration = null;
        }
        super.onDestroyView();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** 在 RecyclerView 子项背后画整组圆角面与组内分隔线，不碰每一行自己的 ripple/无障碍行为。 */
    private final class GroupCardDecoration extends RecyclerView.ItemDecoration {
        private final PreferenceGroup.PreferencePositionCallback positions;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint divider = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final float radius = dp(22);

        GroupCardDecoration(PreferenceGroup.PreferencePositionCallback positions) {
            this.positions = positions;
            View anchor = getView();
            int fillColor = anchor == null
                    ? requireContext().getColor(R.color.card_bg)
                    : MaterialColors.getColor(anchor,
                            com.google.android.material.R.attr.colorSurfaceContainerLow);
            int dividerColor = anchor == null
                    ? requireContext().getColor(R.color.m3_outline_variant)
                    : MaterialColors.getColor(anchor,
                            com.google.android.material.R.attr.colorOutlineVariant);
            fill.setColor(fillColor);
            divider.setColor(dividerColor);
            divider.setStrokeWidth(Math.max(1, dp(0.5f)));
        }

        @Override
        public void getItemOffsets(@NonNull Rect outRect, @NonNull View view,
                                   @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
            outRect.set(0, 0, 0, 0);
            int position = parent.getChildAdapterPosition(view);
            if (position == RecyclerView.NO_POSITION) {
                return;
            }
            if (position == 0) {
                outRect.top = dp(16);
                return;
            }
            for (PreferenceCategory header : groupHeaders) {
                if (position == positions.getPreferenceAdapterPosition(header)) {
                    outRect.top = dp(16);
                    return;
                }
            }
        }

        @Override
        public void onDraw(@NonNull Canvas canvas, @NonNull RecyclerView parent,
                           @NonNull RecyclerView.State state) {
            super.onDraw(canvas, parent, state);
            for (List<Preference> rows : visualGroups) {
                int start = positions.getPreferenceAdapterPosition(rows.get(0));
                int end = positions.getPreferenceAdapterPosition(rows.get(rows.size() - 1));
                if (start < 0 || end < start) {
                    continue;
                }
                int left = Integer.MAX_VALUE, top = Integer.MAX_VALUE;
                int right = Integer.MIN_VALUE, bottom = Integer.MIN_VALUE;
                for (int i = 0; i < parent.getChildCount(); i++) {
                    View child = parent.getChildAt(i);
                    int pos = parent.getChildAdapterPosition(child);
                    if (pos >= start && pos <= end) {
                        left = Math.min(left, child.getLeft());
                        top = Math.min(top, child.getTop());
                        right = Math.max(right, child.getRight());
                        bottom = Math.max(bottom, child.getBottom());
                    }
                }
                if (top == Integer.MAX_VALUE) {
                    continue;
                }
                rect.set(left, top, right, bottom);
                canvas.drawRoundRect(rect, radius, radius, fill);
                // 分隔线从标题列起，不横穿图标；最后一行不画。
                for (int i = 0; i < parent.getChildCount(); i++) {
                    View child = parent.getChildAt(i);
                    int pos = parent.getChildAdapterPosition(child);
                    if (pos >= start && pos < end) {
                        float y = child.getBottom();
                        canvas.drawLine(left + dp(64), y, right - dp(12), y, divider);
                    }
                }
            }
        }
    }

    private void tintPreferenceIcons(Preference preference, int color) {
        if (preference == null) {
            return;
        }
        Drawable icon = preference.getIcon();
        if (icon != null) {
            icon = DrawableCompat.wrap(icon.mutate());
            DrawableCompat.setTint(icon, color);
            preference.setIcon(icon);
        }
        if (preference instanceof PreferenceGroup) {
            PreferenceGroup group = (PreferenceGroup) preference;
            for (int i = 0; i < group.getPreferenceCount(); i++) {
                tintPreferenceIcons(group.getPreference(i), color);
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshAll();
    }

    private MainActivity host() {
        return (MainActivity) requireActivity();
    }

    private void click(String key, Runnable action) {
        Preference p = findPreference(key);
        if (p != null) {
            p.setOnPreferenceClickListener(pref -> {
                action.run();
                return true;
            });
        }
    }

    private void toggle(String key, Consumer<Boolean> action) {
        Preference p = findPreference(key);
        if (p != null) {
            p.setOnPreferenceChangeListener((pref, value) -> {
                action.accept((Boolean) value);
                return true;
            });
        }
    }

    private void summary(String key, CharSequence text) {
        Preference p = findPreference(key);
        if (p != null) {
            p.setSummary(text);
        }
    }

    private void checked(String key, boolean on) {
        Preference p = findPreference(key);
        if (p instanceof SwitchPreferenceCompat) {
            ((SwitchPreferenceCompat) p).setChecked(on);
        }
    }

    /** 每一行的状态现算一遍（回到前台、动作做完、Activity 主动通知时都走这里）。 */
    public void refreshAll() {
        if (!isAdded() || !(getActivity() instanceof MainActivity)) {
            return;
        }
        Context ctx = requireContext();
        boolean home = TakeoverManager.isHomeTakenOver(ctx);
        boolean lock = TakeoverManager.isLockTakenOver(ctx) || TakeoverManager.isLockTakenByEngine(ctx);
        String takeover = getString(R.string.pref_takeover_sum,
                getString(home ? R.string.takeover_by_app : R.string.takeover_by_system),
                getString(lock ? R.string.takeover_by_app : R.string.takeover_by_system));
        summary("takeover", takeover);
        summary("takeover_status", takeover);
        summary("battery", getString(host().isIgnoringBatteryOptimizations()
                ? R.string.battery_state_on : R.string.battery_state_off));
        checked("auto_notify", SwitchNotifier.isEnabled(ctx));
        checked("status_notify", StatusNotifier.isEnabled(ctx));
        checked("ticking", TimerScheduler.tickingCountdown(ctx));
        String pin = PinnedWallpaper.label(ctx);
        summary("pinned", pin == null ? getString(R.string.settings_state_unset) : pin);
        String transition = transitionLabel();
        String motion = motionLabel();
        summary("transition", transition);
        summary("motion", motion);
        summary("settings_motion", getString(R.string.settings_design_motion_summary, transition, motion));
        int threshold = QuickSetSheetDialog.thresholdDp(ctx);
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        summary(QuickSetSheetDialog.KEY_DISMISS_THRESHOLD_DP,
                getString(R.string.settings_quick_threshold_summary, threshold,
                        Math.round(threshold * metrics.density * 100f / metrics.heightPixels)));
        summary("launcher_overlay", getString(LauncherPreviewOverlay.overlayFile(ctx).exists()
                ? R.string.launcher_overlay_set : R.string.settings_state_unset));
        summary("export_dir", WallpaperExporter.isConfigured(ctx)
                ? getString(R.string.export_dir_set_state, WallpaperExporter.displayName(ctx))
                : getString(R.string.settings_state_unset));
        // 存相册用的是 MediaStore RELATIVE_PATH（Android 10 起才有），低版本直接灰掉这一行
        Preference liveConverter = findPreference("live_converter");
        if (liveConverter != null) {
            boolean supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
            liveConverter.setEnabled(supported);
            // 灰掉时那行是「为什么点不动」的状态，留着；能用时不写说明，裁剪页里已有去向与不进库的提示
            liveConverter.setSummary(supported ? null : getString(R.string.live_converter_need_q));
        }
        SharedPreferences prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE);
        long at = prefs.getLong("backup_last_at", 0L);
        String name = prefs.getString("backup_last_name", "");
        summary("backup", at == 0L || name == null || name.isEmpty()
                ? null
                : getString(R.string.backup_last, TimerScheduler.clockText(ctx, at), name));
        summary("update_source", getString(UpdateChecker.SRC_GITHUB.equals(UpdateChecker.source(ctx))
                ? R.string.update_source_github : R.string.update_source_gitee));
        checked("auto_update", UpdateChecker.autoCheck(ctx));
        refreshUpdateRow();
        String version = "v" + UpdateChecker.localVersion(ctx);
        summary("version", version);
        summary("settings_about", version);
    }

    /** 检查更新那一行：下载进度轮询每 300ms 刷它一次，所以单独拎出来，不连带整页重算。 */
    public void refreshUpdateRow() {
        if (isAdded() && getActivity() instanceof MainActivity) {
            summary("update", host().updateStatus());
        }
    }

    /** 一键设置：没选过直接进两级选图；选过了给「换一张 / 清除」两条明面出口（原来清除藏在长按里）。 */
    private void onPinnedTapped() {
        if (!PinnedWallpaper.isConfigured(requireContext())) {
            host().showPinnedPicker();
            return;
        }
        new AppAlertDialogBuilder(requireContext())
                .setTitle(R.string.pinned_title)
                .setItems(new CharSequence[]{getString(R.string.pinned_pick), getString(R.string.pinned_clear)},
                        (d, which) -> {
                            if (which == 0) {
                                host().showPinnedPicker();
                            } else {
                                host().clearPinned();
                            }
                        })
                .show();
    }

    /** 导出目录：没设直接选；设过了给「更换目录 / 取消导出目录」（原来取消藏在长按里）。 */
    private void onExportDirTapped() {
        if (!WallpaperExporter.isConfigured(requireContext())) {
            host().pickExportDir();
            return;
        }
        new AppAlertDialogBuilder(requireContext())
                .setTitle(R.string.export_dir_title)
                .setItems(new CharSequence[]{getString(R.string.export_dir_change),
                                getString(R.string.export_dir_cancel)},
                        (d, which) -> {
                            if (which == 0) {
                                host().pickExportDir();
                            } else {
                                host().confirmClearExportDir();
                            }
                        })
                .show();
    }

    // ==================== 切换动画 ====================

    private static final String[] TRANSITION_VALUES = {
            WallSwitchService.TRANSITION_FADE,
            WallSwitchService.TRANSITION_BLUR,
            WallSwitchService.TRANSITION_OFF
    };

    private String[] transitionLabels() {
        return new String[]{getString(R.string.transition_fade), getString(R.string.transition_blur),
                getString(R.string.transition_off)};
    }

    private String transitionLabel() {
        String cur = WallSwitchService.transitionEffect(requireContext());
        String[] labels = transitionLabels();
        for (int i = 0; i < TRANSITION_VALUES.length; i++) {
            if (TRANSITION_VALUES[i].equals(cur)) {
                return labels[i];
            }
        }
        return labels[0];
    }

    /** 值存 settings（与引擎共用 {@link WallSwitchService#KEY_TRANSITION}），即时生效。 */
    private void pickTransition() {
        String cur = WallSwitchService.transitionEffect(requireContext());
        int checked = WallSwitchService.TRANSITION_OFF.equals(cur) ? 2
                : WallSwitchService.TRANSITION_BLUR.equals(cur) ? 1 : 0;
        new AppAlertDialogBuilder(requireContext())
                .setTitle(R.string.transition_effect_title)
                .setSingleChoiceItems(transitionLabels(), checked, (d, which) -> {
                    settings().edit().putString(WallSwitchService.KEY_TRANSITION,
                            TRANSITION_VALUES[which]).apply();
                    refreshAll();
                    d.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ==================== 实况播放 ====================

    private String motionLabel() {
        Context ctx = requireContext();
        if (!WallSwitchService.MOTION_LOOP.equals(WallSwitchService.motionMode(ctx))) {
            return getString(R.string.motion_hold);
        }
        // 循环档把间隔一起显示出来：只写「循环播放」的话，改完间隔界面看不出有没有生效
        long gap = WallSwitchService.motionLoopGapMs(ctx);
        return gap <= 0 ? getString(R.string.motion_loop_no_gap)
                : getString(R.string.motion_loop_with_gap, gapLabel(gap));
    }

    /** 毫秒说成人话：整秒不带小数，半秒带；0 由调用方单独处理。 */
    private static String gapLabel(long ms) {
        if (ms % 1000L == 0) {
            return String.valueOf(ms / 1000L);
        }
        return String.valueOf(ms / 1000f);
    }

    /**
     * 实况播放方式（按住 / 循环）。默认按住播放 —— 循环档是可见期间 60 帧/秒常驻，得用户自己显式选。
     * 选了循环紧接着问间隔；那一步取消则整件事不生效（档也不切）。
     */
    private void pickMotion() {
        final String before = WallSwitchService.motionMode(requireContext());
        final String[] values = {WallSwitchService.MOTION_HOLD, WallSwitchService.MOTION_LOOP};
        String[] labels = {getString(R.string.motion_hold), getString(R.string.motion_loop)};
        new AppAlertDialogBuilder(requireContext())
                .setTitle(R.string.motion_mode_title)
                .setSingleChoiceItems(labels, WallSwitchService.MOTION_LOOP.equals(before) ? 1 : 0,
                        (d, which) -> {
                            d.dismiss();
                            if (!WallSwitchService.MOTION_LOOP.equals(values[which])) {
                                applyMotionMode(values[which]);
                                return;
                            }
                            askMotionLoopGap(before);
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * 循环档的每轮间隔：0~30 秒滚轮（同一个 {@link MainActivity#wheelColumn}）。
     * 不用单选列表：带说明的单选弹窗在真机上会把列表量成零高。
     */
    private void askMotionLoopGap(String beforeMode) {
        Context ctx = requireContext();
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(pad, pad / 2, pad, 0);
        int current = (int) Math.min(30L, Math.max(0L,
                WallSwitchService.motionLoopGapMs(ctx) / 1000L));
        final NumberPicker picker = new NumberPicker(ctx);
        picker.setMinValue(0);
        picker.setMaxValue(30);
        picker.setWrapSelectorWheel(false);
        picker.setValue(current);
        row.addView(host().wheelColumn(picker, getString(R.string.motion_gap_unit)));
        new AppAlertDialogBuilder(ctx)
                .setTitle(R.string.motion_gap_title)
                .setView(row)
                .setPositiveButton(R.string.confirm, (dialog, which) -> {
                    settings().edit().putLong(WallSwitchService.KEY_MOTION_LOOP_GAP,
                            picker.getValue() * 1000L).apply();
                    applyMotionMode(WallSwitchService.MOTION_LOOP);
                })
                .setNegativeButton(R.string.cancel, (d, which) -> applyMotionMode(beforeMode))
                .setOnCancelListener(d -> applyMotionMode(beforeMode))
                .show();
    }

    /** 写档 + 刷界面 + 立刻通知引擎。 */
    private void applyMotionMode(String mode) {
        settings().edit().putString(WallSwitchService.KEY_MOTION_MODE, mode).apply();
        WallSwitchService.notifyMotionModeChanged();
        // 发热时刚换到循环档：闸门已经合着，当场补发「已暂停」提醒并让常驻通知那行温度换色
        ThermalNotifier.onGateInputChanged(requireContext().getApplicationContext());
        StatusNotifier.update(requireContext());
        refreshAll();
    }

    // ==================== 更新源 ====================

    /** 下载进行中锁源：不然下一半切了源，剩下的半截包属于另一座仓库，只能作废重下。 */
    private void pickUpdateSource() {
        Context ctx = requireContext();
        if (UpdateDownloader.state(ctx) == UpdateDownloader.DOWNLOADING) {
            Toast.makeText(ctx, R.string.update_source_busy, Toast.LENGTH_SHORT).show();
            return;
        }
        final String[] values = {UpdateChecker.SRC_GITEE, UpdateChecker.SRC_GITHUB};
        String[] labels = {getString(R.string.update_source_gitee), getString(R.string.update_source_github)};
        new AppAlertDialogBuilder(ctx)
                .setTitle(R.string.update_source_title)
                .setSingleChoiceItems(labels,
                        UpdateChecker.SRC_GITHUB.equals(UpdateChecker.source(ctx)) ? 1 : 0,
                        (d, which) -> {
                            UpdateChecker.setSource(ctx, values[which]);
                            refreshAll();
                            d.dismiss();
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void pickQuickSheetThreshold() {
        Context ctx = requireContext();
        int min = QuickSetSheetDialog.MIN_THRESHOLD_DP;
        int step = QuickSetSheetDialog.THRESHOLD_STEP_DP;
        int count = (QuickSetSheetDialog.MAX_THRESHOLD_DP - min) / step + 1;
        String[] values = new String[count];
        for (int i = 0; i < count; i++) {
            values[i] = String.valueOf(min + i * step);
        }
        NumberPicker picker = new NumberPicker(ctx);
        picker.setMinValue(0);
        picker.setMaxValue(count - 1);
        picker.setDisplayedValues(values);
        picker.setWrapSelectorWheel(false);
        picker.setValue((QuickSetSheetDialog.thresholdDp(ctx) - min) / step);
        int pad = dp(16);
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(pad, pad / 2, pad, 0);
        row.addView(host().wheelColumn(picker, getString(R.string.settings_quick_threshold_unit)));
        new AppAlertDialogBuilder(ctx)
                .setTitle(R.string.settings_quick_threshold_title)
                .setMessage(R.string.settings_quick_threshold_description)
                .setView(row)
                .setPositiveButton(R.string.confirm, (dialog, which) -> {
                    picker.clearFocus();
                    settings().edit().putInt(QuickSetSheetDialog.KEY_DISMISS_THRESHOLD_DP,
                            min + picker.getValue() * step).apply();
                    refreshAll();
                })
                .setNeutralButton(R.string.settings_quick_threshold_reset, (dialog, which) -> {
                    settings().edit().remove(QuickSetSheetDialog.KEY_DISMISS_THRESHOLD_DP).apply();
                    refreshAll();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private SharedPreferences settings() {
        return requireContext().getSharedPreferences("settings", Context.MODE_PRIVATE);
    }
}
