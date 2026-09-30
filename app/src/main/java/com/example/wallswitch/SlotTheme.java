package com.example.wallswitch;

import android.content.Context;
import android.graphics.Color;

/**
 * 槽位卡片的配色：用户在聚合设置里挑「色相 + 浓淡」，这里负责把它摊成一整套颜色。
 *
 * 只给色相条挑不出纸白也挑不出墨色（那两头都是高饱和的纯色），所以浓淡那条同时管饱和与明度：
 * 0 → 近白的暖纸，100 → 压深的墨，中间是马卡龙到正色的过渡。
 * 描边由同色相压暗一档得来；浓淡过了暗色线就把三行文字翻白 —— 任意颜色都不会挑出「看不见字」的组合。
 * 全局一份、两面同色：滑块一次只显示一面，两面上不同色只会让人以为设置也各存一份。
 *
 * HSL 换算自己写（不碰 Color.HSLToColor：那是 API 26 的新方法，本地类型检查用的 android.jar
 * 里查不到，写了也验不了）。
 */
final class SlotTheme {

    static final int DEFAULT_HUE = 30;
    static final int DEFAULT_TONE = 0;
    static final int HUE_MAX = 359;
    static final int TONE_MAX = 100;
    /** 明度低于这条就算「深色底」，文字翻白。 */
    private static final float DARK_LIGHTNESS = 0.55f;

    private static final String PREFS_NAME = "settings";
    private static final String KEY_HUE = "slot_hue";
    private static final String KEY_TONE = "slot_tone";

    private SlotTheme() {
    }

    static int hue(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_HUE, DEFAULT_HUE);
    }

    static int tone(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_TONE, DEFAULT_TONE);
    }

    static void set(Context ctx, int hue, int tone) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putInt(KEY_HUE, hue)
                .putInt(KEY_TONE, tone)
                .apply();
    }

    /** 底色。 */
    static int color(int hue, int tone) {
        return hslToRgb(hue, saturation(tone), lightness(tone));
    }

    /** 描边：同色相、饱和略提、明度压一档（深底反过来提一档，否则边界会糊进底色）。 */
    static int stroke(int hue, int tone) {
        float l = lightness(tone);
        return hslToRgb(hue, Math.min(0.8f, saturation(tone) * 1.15f),
                dark(tone) ? Math.min(0.98f, l + 0.08f) : Math.max(0.1f, l - 0.10f));
    }

    /** 深浅只看浓淡（明度是它单调函数），不用把颜色再解回去。 */
    static boolean dark(int tone) {
        return lightness(tone) < DARK_LIGHTNESS;
    }

    /** 范围小标题：浅底沿用品牌蓝，深底翻白（蓝在深底上对比不够）。 */
    static int scopeText(Context ctx, int tone) {
        return ctx.getColor(dark(tone) ? R.color.slot_text_on_dark : R.color.brand);
    }

    static int nameText(Context ctx, int tone) {
        return ctx.getColor(dark(tone) ? R.color.slot_text_on_dark : R.color.text_primary);
    }

    /** 副标题、感叹号、暂停键这一档次要色。 */
    static int dimText(Context ctx, int tone) {
        return ctx.getColor(dark(tone) ? R.color.slot_text_dim_on_dark : R.color.text_secondary);
    }

    /** 没图时缩略图位的底色：浅底用分隔线灰，深底沿同色相提亮一档（不清会顶着一块刺眼的浅灰）。 */
    static int thumbBg(Context ctx, int hue, int tone) {
        if (!dark(tone)) {
            return ctx.getColor(R.color.divider);
        }
        return hslToRgb(hue, saturation(tone), Math.min(0.55f, lightness(tone) + 0.12f));
    }

    private static float saturation(int tone) {
        return 0.14f + (tone / (float) TONE_MAX) * 0.51f;
    }

    private static float lightness(int tone) {
        return 0.96f - (tone / (float) TONE_MAX) * 0.62f;
    }

    /** HSL → RGB（h 取 0..360，s/l 取 0..1）。 */
    private static int hslToRgb(float h, float s, float l) {
        float c = (1 - Math.abs(2 * l - 1)) * s;
        float hp = (((h % 360) + 360) % 360) / 60f;
        float x = c * (1 - Math.abs(hp % 2 - 1));
        float r, g, b;
        if (hp < 1) {
            r = c; g = x; b = 0;
        } else if (hp < 2) {
            r = x; g = c; b = 0;
        } else if (hp < 3) {
            r = 0; g = c; b = x;
        } else if (hp < 4) {
            r = 0; g = x; b = c;
        } else if (hp < 5) {
            r = x; g = 0; b = c;
        } else {
            r = c; g = 0; b = x;
        }
        float m = l - c / 2;
        return Color.rgb(Math.round((r + m) * 255), Math.round((g + m) * 255), Math.round((b + m) * 255));
    }
}
