package com.google.common.util.concurrent;

import java.util.concurrent.Executor;
import java.util.concurrent.Future;

/** 本地类型检查桩：真实类来自 com.google.guava:listenablefuture（work-runtime 的 compile 依赖）。 */
public interface ListenableFuture<V> extends Future<V> {

    void addListener(Runnable listener, Executor executor);
}