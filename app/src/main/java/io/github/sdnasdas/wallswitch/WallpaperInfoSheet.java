package io.github.sdnasdas.wallswitch;

import android.app.Activity;
import android.graphics.Bitmap;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.Date;

/**
 * 「详细信息」底部面板（v3.188，库内多选「更多」里进）：后台读完 {@link WallpaperInfo} 再弹，
 * 分五组显示用户勾定的十八项。读不到的项显示「—」，不拿别的数据凑。
 */
final class WallpaperInfoSheet {

    private WallpaperInfoSheet() {
    }

    static void show(final Activity activity, final WallpaperStore.Item item) {
        final android.content.Context app = activity.getApplicationContext();
        new Thread(() -> {
            final WallpaperInfo info = WallpaperInfo.collect(app, item);
            final Bitmap thumb = WallpaperStore.getThumb(app, item.id);
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) {
                    return;
                }
                present(activity, info, thumb);
            });
        }, "wallpaper-info").start();
    }

    private static void present(Activity a, WallpaperInfo info, Bitmap thumb) {
        BottomSheetDialog sheet = new BottomSheetDialog(a);
        View content = LayoutInflater.from(a).inflate(R.layout.sheet_wallpaper_info, null, false);
        ImageView thumbView = content.findViewById(R.id.info_thumb);
        if (thumb != null) {
            thumbView.setImageBitmap(thumb);
        }
        String summary = a.getString(info.live ? R.string.info_live_yes : R.string.info_live_no);
        if (info.fullWidth > 0) {
            summary += " · " + a.getString(R.string.info_size_value, info.fullWidth, info.fullHeight);
        }
        ((TextView) content.findViewById(R.id.info_summary)).setText(summary);
        LinearLayout groups = content.findViewById(R.id.info_groups);
        String unknown = a.getString(R.string.info_unknown);

        LinearLayout image = group(a, groups, R.string.info_group_image);
        row(a, image, R.string.info_full_size, size(a, info.fullWidth, info.fullHeight));
        row(a, image, R.string.info_full_format, orDash(a, info.fullFormat));
        row(a, image, R.string.info_full_bytes, info.fullBytes > 0
                ? WallpaperInfo.formatBytes(info.fullBytes) : unknown);
        if (info.originalKept) {
            row(a, image, R.string.info_original_size, size(a, info.originalWidth, info.originalHeight));
            row(a, image, R.string.info_original_format, orDash(a, info.originalFormat));
            row(a, image, R.string.info_original_bytes, WallpaperInfo.formatBytes(info.originalBytes));
        } else {
            String missing = a.getString(R.string.info_original_missing);
            row(a, image, R.string.info_original_size, missing);
            row(a, image, R.string.info_original_format, missing);
            row(a, image, R.string.info_original_bytes, missing);
        }

        LinearLayout live = group(a, groups, R.string.info_group_live);
        row(a, live, R.string.info_is_live,
                a.getString(info.live ? R.string.info_live_yes : R.string.info_live_no));
        if (info.live) {
            row(a, live, R.string.info_live_duration, info.liveDurationUs > 0
                    ? a.getString(R.string.info_live_duration_value,
                    WallpaperInfo.formatSeconds(info.liveDurationUs)) : unknown);
            row(a, live, R.string.info_live_size, size(a, info.liveWidth, info.liveHeight));
            row(a, live, R.string.info_live_fps, info.liveFps > 0f
                    ? a.getString(R.string.info_live_fps_value, WallpaperInfo.roundFps(info.liveFps))
                    : unknown);
        }

        LinearLayout display = group(a, groups, R.string.info_group_display);
        Boolean light = info.isLight();
        row(a, display, R.string.info_tone, light == null ? unknown
                : a.getString(light ? R.string.info_tone_light : R.string.info_tone_dark));
        row(a, display, R.string.info_night_off,
                a.getString(info.nightOff ? R.string.info_yes : R.string.info_no));

        LinearLayout belong = group(a, groups, R.string.info_group_belong);
        row(a, belong, R.string.info_libs, info.libNames.isEmpty() ? unknown
                : android.text.TextUtils.join("、", info.libNames));
        row(a, belong, R.string.info_in_use, info.inUse == null
                ? a.getString(R.string.info_not_in_use) : info.inUse);
        row(a, belong, R.string.info_favorite,
                a.getString(info.favorite ? R.string.info_favorite_yes : R.string.info_favorite_no));

        LinearLayout record = group(a, groups, R.string.info_group_record);
        row(a, record, R.string.info_imported, info.importedAt > 0 ? time(a, info.importedAt) : unknown);
        row(a, record, R.string.info_shown,
                a.getString(R.string.info_shown_value, info.shownHome, info.shownLock));
        row(a, record, R.string.info_last_shown, info.lastShown > 0 ? time(a, info.lastShown) : unknown);

        sheet.setContentView(content);
        sheet.show();
    }

    private static LinearLayout group(Activity a, LinearLayout parent, int title) {
        TextView header = new TextView(a);
        header.setText(title);
        header.setTextAppearance(R.style.TextAppearance_Ws_Section);
        int pad = dp(a, 4);
        header.setPadding(pad, dp(a, 10), pad, dp(a, 6));
        parent.addView(header);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.info_group_bg);
        box.setPadding(0, dp(a, 2), 0, dp(a, 2));
        parent.addView(box, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }

    private static void row(Activity a, LinearLayout box, int label, CharSequence value) {
        View row = LayoutInflater.from(a).inflate(R.layout.item_info_row, box, false);
        ((TextView) row.findViewById(R.id.info_label)).setText(label);
        ((TextView) row.findViewById(R.id.info_value)).setText(value);
        box.addView(row);
    }

    private static String size(Activity a, int w, int h) {
        return w > 0 && h > 0 ? a.getString(R.string.info_size_value, w, h) : a.getString(R.string.info_unknown);
    }

    private static String orDash(Activity a, String value) {
        return value == null || value.isEmpty() ? a.getString(R.string.info_unknown) : value;
    }

    /** 跟随系统 12/24 小时制的「年-月-日 时:分」。 */
    private static String time(Activity a, long millis) {
        String pattern = DateFormat.is24HourFormat(a) ? "yyyy-MM-dd HH:mm" : "yyyy-MM-dd h:mm a";
        return new java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault()).format(new Date(millis));
    }

    private static int dp(Activity a, int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }
}
