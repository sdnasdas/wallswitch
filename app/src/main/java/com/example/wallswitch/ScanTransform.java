package com.example.wallswitch;

/**
 * 取景预览的矩阵数学。
 *
 * <p>麻烦在于 TextureView 会先把相机缓冲<strong>非等比</strong>拉满整个 view，所以「旋转一下」并不够：
 * 必须先按 buffer/view 的比例把两根轴各自校正回等比，再转传感器角度，才能得到正立、不变形、
 * 且正好盖满取景框的画面。返回 Android {@code Matrix.setValues} 要的九个数，约定是
 * {@code x' = m[0]x + m[1]y + m[2]}、{@code y' = m[3]x + m[4]y + m[5]}。
 *
 * <p>只用 float 运算、不 import android：这是全案里唯一一旦画歪就只能靠肉眼在真机上查的部分，
 * 所以先在 {@code check/LanSyncTest.java} 里锁住三条不变式 —— 一个相机像素在屏上必须是正方形
 * （不变形）、两轴必须正交（没被切歪）、旋转后的内容必须盖满取景框（不留黑边）。
 * 界面侧按 {@link #suggestedRatio} 把取景框摆成转置后的宽高比，数学就落在夹具验过的那一种情形上。
 */
public final class ScanTransform {

    private ScanTransform() {
    }

    /** 取景框该取的宽高比（{@code "w:h"} 字面量，给布局 dimensionRatio 用）。 */
    public static String suggestedRatio(int bufW, int bufH, int degrees) {
        boolean swap = Math.abs(Math.sin(Math.toRadians(degrees))) > 0.5;
        int w = swap ? bufH : bufW;
        int h = swap ? bufW : bufH;
        int g = gcd(w, h);
        return (w / g) + ":" + (h / g);
    }

    static int gcd(int a, int b) {
        return b == 0 ? a : gcd(b, a % b);
    }

    /**
     * @param viewW   取景框实测宽（已按 {@link #suggestedRatio} 摆好）
     * @param viewH   取景框实测高
     * @param bufW    相机缓冲宽
     * @param bufH    相机缓冲高
     * @param degrees {@code CameraCharacteristics.SENSOR_ORIENTATION}（竖屏手持真机是 90）
     * @return 九个数，直接喂 {@code Matrix.setValues}
     */
    public static float[] fillRotate(float viewW, float viewH, float bufW, float bufH, int degrees) {
        double rad = Math.toRadians(degrees);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        boolean swap = Math.abs(sin) > 0.5;                       // 90/270 要把两轴转置
        // 转置后内容占 (s*bufH) 宽 × (s*bufW) 高，要盖住 viewW × viewH，所以 s 取更严的那条要求
        double s = Math.max(viewW / (swap ? bufH : bufW), viewH / (swap ? bufW : bufH));
        // 已被系统非等比拉伸过的两轴各自校正回等比：一个 buffer 像素横向占 viewW/bufW、纵向占 viewH/bufH，
        // 想让它们最终都等于 s，前置缩放就得是 ax = s*bufW/viewW、ay = s*bufH/viewH。
        double ax = s * bufW / viewW;
        double ay = s * bufH / viewH;
        double cx = viewW / 2.0;
        double cy = viewH / 2.0;
        // M = T(cx,cy) · R(degrees) · S(ax,ay) · T(-cx,-cy)
        double m0 = cos * ax;
        double m1 = -sin * ay;
        double m3 = sin * ax;
        double m4 = cos * ay;
        return new float[]{(float) m0, (float) m1, (float) (cx - m0 * cx - m1 * cy),
                (float) m3, (float) m4, (float) (cy - m3 * cx - m4 * cy), 0f, 0f, 1f};
    }
}
