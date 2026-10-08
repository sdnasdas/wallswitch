package com.google.zxing;

/** 本地类型检查桩：真实类来自 zxing core（签名按 3.5.3 的 javap 输出抄）。 */
public abstract class LuminanceSource {
    protected LuminanceSource(int width, int height) {
    }

    public abstract byte[] getRow(int y, byte[] row);

    public abstract byte[] getMatrix();

    public final int getWidth() {
        return 0;
    }

    public final int getHeight() {
        return 0;
    }
}
