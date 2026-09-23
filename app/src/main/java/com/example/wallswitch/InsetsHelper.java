package com.example.wallswitch;

import android.app.Activity;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;

/**
 * 全面屏/刘海屏（边到边）适配。
 * targetSdk 35 在 Android 15 上被强制 edge-to-edge：内容直接画到状态栏/刘海/手势条底下，
 * 不处理 WindowInsets 就会出现顶部遮挡。这里统一改成“内容延伸到全屏 + 按系统条 insets
 * 给根布局加 padding”，让 Android 8~15 行为一致，内容始终避开状态栏、刘海与底部手势条。
 */
public final class InsetsHelper {

    private InsetsHelper() {
    }

    /**
     * 在 setContentView 之后调用。
     *
     * @param activity 目标 Activity
     * @param rootId   内容根布局 id（安全区 padding 加在它上面）
     */
    public static void apply(Activity activity, int rootId) {
        // 统一关闭 decor 自动避让：任何版本下内容都按边到边布局，
        // 安全区完全由下面的 insets padding 处理，避免新旧版本行为不一致/重复内边距
        // 导航栏透明时系统会默认叠加半透明对比度遮罩（API 29+），关闭以保持与页面底色一致。
        // 状态栏无对应 API，不需要处理。
        if (Build.VERSION.SDK_INT >= 29) {
            activity.getWindow().setNavigationBarContrastEnforced(false);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            activity.getWindow().setDecorFitsSystemWindows(false);
        } else {
            activity.getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        final View root = activity.findViewById(rootId);
        if (root == null) {
            return;
        }
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            final int left, top, right, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                // systemBars 含状态栏/导航栏，displayCutout 含刘海挖孔安全区
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                left = bars.left;
                top = bars.top;
                right = bars.right;
                bottom = bars.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(left, top, right, bottom);
            // 不消费，继续分发给子视图（如列表需要可自行再用）
            return insets;
        });
    }
}
