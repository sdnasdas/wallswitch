package com.google.zxing;

/** 本地类型检查桩：真库构造是包私有，桩这边不需要 new 它。 */
public abstract class ReaderException extends Exception {
    ReaderException() {
    }
}
