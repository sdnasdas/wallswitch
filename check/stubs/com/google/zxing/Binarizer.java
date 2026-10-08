package com.google.zxing;

import com.google.zxing.common.BitMatrix;

/** 本地类型检查桩：只声明本项目会碰到的成员（getBlackRow/createBinarizer 没用到就不写，桩多一处多一处漂移）。 */
public abstract class Binarizer {
    protected Binarizer(LuminanceSource source) {
    }

    public abstract BitMatrix getBlackMatrix() throws NotFoundException;
}
