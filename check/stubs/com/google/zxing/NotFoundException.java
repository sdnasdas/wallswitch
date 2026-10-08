package com.google.zxing;

/** 本地类型检查桩：3.5.3 里 decode 只声明抛 NotFoundException（Format/Checksum 不再出现在签名上）。 */
public final class NotFoundException extends ReaderException {
    public static NotFoundException getNotFoundInstance() {
        throw new UnsupportedOperationException();
    }
}
