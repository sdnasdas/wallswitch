package io.github.sdnasdas.wallswitch;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.imageview.ShapeableImageView;
import com.google.android.material.shape.ShapeAppearanceModel;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 全屏切换记录页那一簇：卡片式日志（版本段 → 日期 → 一条一张描边卡）、桌面/锁屏两面切换、
 * 顺序翻转、复制、清空、点记录看成品图小窗。文件仍是原来的纯文本格式，只改显示。
 * 旧版等宽文本弹窗（showSwitchLogDialog / showSwitchLogCardsDialog 及其触摸取行辅助）已无调用方，随本次拆分一并下线。
 */
final class SwitchLogController {

    private final MainActivity a;
    private MaterialToolbar logToolbar;
    private RecyclerView logRecycler;
    private MaterialButtonToggleGroup logScopeToggle;
    private MaterialButton logOrderButton;
    private android.widget.ImageButton logJumpButton;
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

    SwitchLogController(MainActivity a) {
        this.a = a;
    }

    private record LogSnapshot(String[] contents, String[] displayed, List<List<LogRow>> rows,
                               Map<String, List<SwitchLog.EntryRef>> refs, boolean hasAny) { }

    void confirmClearSwitchLog(AlertDialog logDialog) {
        new AppAlertDialogBuilder(a)
                .setTitle(R.string.log_clear_title)
                .setMessage(R.string.log_clear_msg)
                .setPositiveButton(R.string.confirm, (dialog, which) -> new Thread(() -> {
                    SwitchLog.clear(a.getApplicationContext());
                    a.runOnUiThread(() -> {
                        if (a.isFinishing() || a.isDestroyed()) {
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
                        if (a.logOpen) {
                            showSwitchLog();
                        }
                        Toast.makeText(a, R.string.log_clear_done, Toast.LENGTH_SHORT).show();
                        a.refreshSwitchLogRow();
                    });
                }, "log-clear").start())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** 独立切换记录页的静态控件只绑一次；正文数据每次打开时后台现读。 */
    void setupSwitchLogPage() {
        logToolbar = a.findViewById(R.id.toolbar_log);
        logRecycler = a.findViewById(R.id.recycler_log);
        logScopeToggle = a.findViewById(R.id.log_scope_toggle);
        logOrderButton = a.findViewById(R.id.btn_log_order);
        logJumpButton = a.findViewById(R.id.btn_log_jump);
        logAdapter = new LogCardAdapter();
        logRecycler.setLayoutManager(new LinearLayoutManager(a));
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
            if (!isChecked || bindingLogControls || !a.logOpen) {
                return;
            }
            logScope = checkedId == R.id.log_scope_lock ? 1 : 0;
            bindSwitchLogPage();
        });
        // 一个按钮点按切换：文字显示当前顺序，图标用上下双箭头
        logOrderButton.setOnClickListener(v -> {
            if (!a.logOpen) return;
            logNewestFirst = !logNewestFirst;
            SwitchLog.setNewestFirst(a, logNewestFirst);
            updateLogOrderButton();
            bindSwitchLogPage();
        });
    }

    private void updateLogOrderButton() {
        if (logOrderButton == null) {
            return;
        }
        logOrderButton.setText(logNewestFirst ? R.string.log_order_desc : R.string.log_order_asc);
        logOrderButton.setContentDescription(a.getString(R.string.log_order_toggle_desc,
                a.getString(logNewestFirst ? R.string.log_order_desc : R.string.log_order_asc)));
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
        logJumpButton.setContentDescription(a.getString(toTop
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
        a.logOpen = true;
        bindingLogControls = true;
        logScopeToggle.check(logScope == 0 ? R.id.log_scope_home : R.id.log_scope_lock);
        bindingLogControls = false;
        updateLogOrderButton();
        submitSwitchLogPage(snapshot);
        a.motion.show(a.pageLog, logAdapter::prepareVisible);
        logAdapter.rebindVisible();
    }

    private void setLogLoading() {
        a.unbindImageTree(logRecycler);
        logRecycler.setVisibility(View.INVISIBLE);
        logJumpButton.setVisibility(View.GONE);
        logToolbar.getMenu().findItem(R.id.action_log_copy).setEnabled(false);
    }

    private void bindSwitchLogPage() {
        if (!a.logOpen) return;
        final int request = ++logPageToken;
        final int scope = logScope;
        final boolean newestFirst = logNewestFirst;
        final String[] contents = logContents.clone();
        final Map<String, List<SwitchLog.EntryRef>> refs = logRefs;
        final Context app = a.getApplicationContext();
        setLogLoading();
        a.imageLoader.execute(() -> {
            LogSnapshot snapshot = prepareLogSnapshot(app, contents, refs, newestFirst);
            a.runOnUiThread(() -> {
                if (a.isFinishing() || a.isDestroyed() || !a.logOpen || request != logPageToken
                        || scope != logScope || newestFirst != logNewestFirst) return;
                a.motion.whenIdle("log-snapshot", () -> {
                    if (!a.isFinishing() && !a.isDestroyed() && a.logOpen && request == logPageToken
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

    void closeSwitchLogPage() {
        a.logOpen = false;
        logPageToken++;
        a.unbindImageTree(a.pageLog);
        a.motion.hide(a.pageLog);
        logJumpButton.setVisibility(View.GONE);
    }

    /** 日志读盘、排序、剥离 refs 和建模都完成后才打开第一页。 */
    void showSwitchLog() {
        final int request = ++logPageToken;
        if (!a.logOpen) {
            logScope = 0;
            logNewestFirst = SwitchLog.newestFirst(a);
        } else {
            setLogLoading();
        }
        final int scope = logScope;
        final boolean newestFirst = logNewestFirst;
        final Context app = a.getApplicationContext();
        new Thread(() -> {
            final String home = SwitchLog.readAllText(app, true);
            final String lock = SwitchLog.readAllText(app, false);
            final Map<String, List<SwitchLog.EntryRef>> refs = SwitchLog.refIndex(home, lock);
            final LogSnapshot snapshot = prepareLogSnapshot(app, new String[]{home, lock}, refs, newestFirst);
            a.runOnUiThread(() -> {
                if (a.isFinishing() || a.isDestroyed() || request != logPageToken
                        || scope != logScope || newestFirst != logNewestFirst) return;
                a.motion.whenIdle("log-snapshot", () -> {
                    if (a.isFinishing() || a.isDestroyed() || request != logPageToken
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
        pill.setColor(a.getColor(bgRes));
        pill.setCornerRadius(a.dp(8));
        badge.setBackground(pill);
        badge.setTextColor(a.getColor(fgRes));
    }

    private final class LogCardAdapter extends ImageAdapter {
        LogCardAdapter() { super(SwitchLogController.this.a); }
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
                TextView text = new TextView(a);
                text.setPadding(a.dp(4), type == LogRow.DATE ? a.dp(14) : a.dp(10), a.dp(4), a.dp(6));
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
                    capsule.setCornerRadius(a.dp(10));
                    text.setBackground(capsule);
                    text.setPadding(a.dp(10), a.dp(6), a.dp(10), a.dp(6));
                    RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    lp.setMargins(a.dp(4), a.dp(8), a.dp(4), a.dp(4));
                    text.setLayoutParams(lp);
                }
                return new RecyclerView.ViewHolder(text) { };
            }
            MaterialCardView card = new MaterialCardView(a);
            card.setRadius(a.dp(14));
            card.setStrokeWidth(a.dp(1));
            card.setStrokeColor(MaterialColors.getColor(card,
                    com.google.android.material.R.attr.colorOutlineVariant));
            card.setCardBackgroundColor(MaterialColors.getColor(card,
                    com.google.android.material.R.attr.colorSurfaceContainerLow));
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.TOP);
            row.setPadding(a.dp(14), a.dp(12), a.dp(14), a.dp(12));
            // 左列：时间在上，下面一颗小缩略图（该行切到的那张；比点开弹窗的图小得多）。
            // 与网格共用 UiImages 缩略图订阅，不在绑定时读盘。
            LinearLayout left = new LinearLayout(a);
            left.setOrientation(LinearLayout.VERTICAL);
            TextView time = new TextView(a);
            time.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall);
            time.setTextColor(MaterialColors.getColor(time,
                    com.google.android.material.R.attr.colorOnSurface));
            left.addView(time, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            ImageView thumb = new ImageView(a);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.setClipToOutline(true);
            thumb.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), a.dp(8));
                }
            });
            LinearLayout.LayoutParams thumbLp = new LinearLayout.LayoutParams(a.dp(44), a.dp(44));
            thumbLp.topMargin = a.dp(6);
            thumb.setVisibility(View.GONE);
            left.addView(thumb, thumbLp);
            row.addView(left, new LinearLayout.LayoutParams(a.dp(56), ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout words = new LinearLayout(a);
            words.setOrientation(LinearLayout.VERTICAL);
            TextView title = new TextView(a);
            title.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
            title.setTextColor(MaterialColors.getColor(title,
                    com.google.android.material.R.attr.colorOnSurface));
            TextView subtitle = new TextView(a);
            subtitle.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
            subtitle.setTextColor(MaterialColors.getColor(subtitle,
                    com.google.android.material.R.attr.colorOnSurfaceVariant));
            TextView meta = new TextView(a);
            meta.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
            meta.setTextColor(MaterialColors.getColor(meta,
                    com.google.android.material.R.attr.colorOnSurfaceVariant));
            meta.setPadding(0, a.dp(4), 0, 0);
            words.addView(title);
            words.addView(subtitle);
            words.addView(meta);
            row.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView badge = new TextView(a);
            badge.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelSmall);
            badge.setGravity(Gravity.CENTER);
            badge.setPadding(a.dp(8), a.dp(4), a.dp(8), a.dp(4));
            LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            badgeLp.setMarginStart(a.dp(8));
            row.addView(badge, badgeLp);
            card.addView(row);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, a.dp(4), 0, a.dp(4));
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
            a.images.thumbnail(h.thumb, item.thumbId);
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
     * 记录行 → 看图小窗。行尾藏着壁纸 id，直接按 id 取图（库改名都不影响）；
     * v3.131 之前的老行没有 id 段，无从反查，点了就提示找不到。
     * 显示的是<b>成品图宽高各半</b>的完整构图（不是方形缩略图），挂 previews 大图缓存，
     * 解码放后台线程，解完再弹；半尺寸解不动时退回网格那颗方形缩略图。
     */
    void showLogThumbnail(SwitchLog.RecordLine record,
                          List<SwitchLog.EntryRef> exact, AlertDialog logDialog) {
        final Context app = a.getApplicationContext();
        final int pageToken = logPageToken;
        final int pageScope = logScope;
        final long epoch = a.imageEpoch;
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
                Bitmap thumb = a.previews.get(key);
                if (thumb == null) {
                    thumb = a.decodeFullHalf(app, item.id);
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
            a.runOnUiThread(() -> {
                if (a.isFinishing() || a.isDestroyed() || epoch != a.imageEpoch) {
                    return;
                }
                for (Map.Entry<String, Bitmap> entry : decoded.entrySet()) {
                    a.previews.put(entry.getKey(), entry.getValue());
                }
                // 旧弹窗或新独立页只要入口还在屏上就允许出缩略图；都关了就不往别处飞图
                boolean dialogOpen = logDialog != null && logDialog.isShowing();
                boolean pageOpen = a.logOpen && pageToken == logPageToken && pageScope == logScope;
                if (!dialogOpen && !pageOpen) {
                    return;
                }
                if (thumbs.isEmpty()) {
                    Toast.makeText(a, R.string.log_thumb_gone, Toast.LENGTH_SHORT).show();
                    return;
                }
                showThumbnailWindow(thumbs);
            });
        }, "log-thumb").start();
    }

    /** 只摆图的小窗：宽与网格一格同大、高按各自宽高比，圆角 12dp（与网格的 RoundedGrid 一致）。 */
    void showThumbnailWindow(List<Bitmap> thumbs) {
        float density = a.getResources().getDisplayMetrics().density;
        // 与网格一格的显示宽度一致：屏宽 − 两侧 12dp 内边距 − 每格 4dp×2 外边距，再对半
        int cell = Math.max(1,
                (a.getResources().getDisplayMetrics().widthPixels - (int) (40 * density)) / 2);
        int gap = (int) (8 * density);
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        for (Bitmap thumb : thumbs) {
            ShapeableImageView image = new ShapeableImageView(a);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setContentDescription(a.getString(R.string.thumb_desc));
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
        HorizontalScrollView scroll = new HorizontalScrollView(a);
        scroll.addView(row);
        if (thumbs.size() > 1) {
            // 多张同名时窗口按候选数加宽，放到屏幕放不下为止，再横向滑
            int needed = thumbs.size() * cell + (thumbs.size() - 1) * gap;
            int max = Math.max(cell, a.getResources().getDisplayMetrics().widthPixels
                    - (int) (64 * density));
            width = Math.min(needed, max);
            scroll.setLayoutParams(new ViewGroup.LayoutParams(
                    width, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        LinearLayout content = new LinearLayout(a);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.addView(scroll);
        // 对话框自带最小宽度与 24dp 背景内缩，照默认量出来两边都是白：把背景让掉、内缩归零，
        // 再把窗宽压到图本身（多张时是那一排的宽）
        AlertDialog dialog = new AppAlertDialogBuilder(a)
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
            ClipboardManager manager = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
            if (manager == null) {
                return;
            }
            manager.setPrimaryClip(ClipData.newPlainText(a.getString(R.string.log_view_title), content));
            Toast.makeText(a, R.string.log_view_copied, Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {
        }
    }
}