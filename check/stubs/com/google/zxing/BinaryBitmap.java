package com.google.zxing;

import com.google.zxing.common.BitMatrix;

/** 本地类型检查桩：3.4 之后取点阵是 getBlackMatrix()，老的 getBitMatrix() 已经没了。 */
public final class BinaryBitmap {
    public BinaryBitmap(Binarizer binarizer) {
    }

    public BitMatrix getBlackMatrix() throws NotFoundException {
        throw new UnsupportedOperationException();
    }
}
