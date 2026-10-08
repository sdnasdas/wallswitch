package com.google.zxing;

/** 本地类型检查桩。注意真库把它放在 com.google.zxing 根包，不是 .planar 子包。 */
public final class PlanarYUVLuminanceSource extends LuminanceSource {
    public PlanarYUVLuminanceSource(byte[] yuvData, int dataWidth, int dataHeight, int left,
                                    int top, int width, int height, boolean reverseHorizontal) {
        super(width, height);
    }

    @Override
    public byte[] getRow(int y, byte[] row) {
        throw new UnsupportedOperationException();
    }

    @Override
    public byte[] getMatrix() {
        throw new UnsupportedOperationException();
    }
}
