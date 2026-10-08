package com.google.zxing.common;

import com.google.zxing.Binarizer;
import com.google.zxing.LuminanceSource;
import com.google.zxing.NotFoundException;

/** 本地类型检查桩：真库里它 extends GlobalHistogramBinarizer，本项目不依赖那条继承链。 */
public final class HybridBinarizer extends Binarizer {
    public HybridBinarizer(LuminanceSource source) {
        super(source);
    }

    @Override
    public BitMatrix getBlackMatrix() throws NotFoundException {
        throw new UnsupportedOperationException();
    }
}
