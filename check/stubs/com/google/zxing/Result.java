package com.google.zxing;

/** 本地类型检查桩：只留 getText()，点位与元数据本项目不取。 */
public final class Result {
    public String getText() {
        throw new UnsupportedOperationException();
    }
}
