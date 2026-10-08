package com.google.zxing;

import java.util.Map;

/** 本地类型检查桩。 */
public final class MultiFormatReader {
    public MultiFormatReader() {
    }

    public Result decode(BinaryBitmap image) throws NotFoundException {
        throw new UnsupportedOperationException();
    }

    public Result decode(BinaryBitmap image, Map<DecodeHintType, ?> hints) throws NotFoundException {
        throw new UnsupportedOperationException();
    }
}
