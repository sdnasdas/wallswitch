package com.example.wallswitch;

import android.content.Context;

/**
 * 槽位卡片的四套配色（聚合设置弹窗里那四个色块当场挑）。
 *
 * 全局一份、桌面/锁屏两面同色：滑块一次只显示一面，两面上不同色只会让人以为设置也是各存一份。
 * D（墨）是唯一深色的，三行文字与图标都得翻白，所以文字色也归这里管，别让布局各调各的。
 */
final class SlotTheme {

    static final int PAPER = 0;
    static final int LILAC = 1;
    static final int MINT = 2;
    static final int INK = 3;
    static final int COUNT = 4;

    private static final String PREFS_NAME = "settings";
    private static final String KEY = "slot_theme";

    private static final int[] BG = {R.color.slot_a_bg, R.color.slot_b_bg,
            R.color.slot_c_bg, R.color.slot_d_bg};
    private static final int[] STROKE = {R.color.slot_a_stroke, R.color.slot_b_stroke,
            R.color.slot_c_stroke, R.color.slot_d_stroke};
    // 没图时缩略图位的底色：深色那套要压暗，否则卡片里顶着一块浅灰
    private static final int[] THUMB_BG = {R.color.divider, R.color.divider,
            R.color.divider, R.color.slot_d_thumb_bg};
    private static final int[] SCOPE_TEXT = {R.color.brand, R.color.brand,
            R.color.brand, R.color.slot_d_text};
    private static final int[] NAME_TEXT = {R.color.text_primary, R.color.text_primary,
            R.color.text_primary, R.color.slot_d_text};
    private static final int[] DIM_TEXT = {R.color.text_secondary, R.color.text_secondary,
            R.color.text_secondary, R.color.slot_d_text_dim};

    private SlotTheme() {
    }

    static int current(Context ctx) {
        int saved = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY, PAPER);
        return saved >= 0 && saved < COUNT ? saved : PAPER;
    }

    static void set(Context ctx, int theme) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putInt(KEY, theme).apply();
    }

    static int bg(Context ctx, int theme) {
        return ctx.getColor(BG[at(theme)]);
    }

    static int stroke(Context ctx, int theme) {
        return ctx.getColor(STROKE[at(theme)]);
    }

    static int thumbBg(Context ctx, int theme) {
        return ctx.getColor(THUMB_BG[at(theme)]);
    }

    static int scopeText(Context ctx, int theme) {
        return ctx.getColor(SCOPE_TEXT[at(theme)]);
    }

    static int nameText(Context ctx, int theme) {
        return ctx.getColor(NAME_TEXT[at(theme)]);
    }

    /** 副标题、感叹号、暂停键这一档次要色（深色那套要提亮才看得见）。 */
    static int dimText(Context ctx, int theme) {
        return ctx.getColor(DIM_TEXT[at(theme)]);
    }

    /** 色块要按选中的那套显示，越界一律当默认（老数据/以后加配色都不会崩）。 */
    private static int at(int theme) {
        return theme >= 0 && theme < COUNT ? theme : PAPER;
    }
}
