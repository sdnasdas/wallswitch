package io.github.sdnasdas.wallswitch;

public final class VideoSelection {
    /** 壁纸档的片段上限：实况段要跟着桌面引擎逐帧解，上屏那张只取 3 秒。 */
    public static final long MAX_DURATION_MS = 3000;
    private final long durationMs;
    private final long maxDurationMs;
    private long startMs;
    private long endMs;
    private long coverMs;

    public VideoSelection(long durationMs) {
        this(durationMs, MAX_DURATION_MS);
    }

    /** @param maxDurationMs 片段上限，{@code <= 0} 表示不限（独立转 Live 那条用整段也行） */
    public VideoSelection(long durationMs, long maxDurationMs) {
        if (durationMs <= 0) {
            throw new IllegalArgumentException("视频时长无效");
        }
        this.durationMs = durationMs;
        this.maxDurationMs = maxDurationMs;
        endMs = maxDurationMs <= 0 ? durationMs : Math.min(durationMs, maxDurationMs);
    }

    public long durationMs() { return durationMs; }
    public long startMs() { return startMs; }
    public long endMs() { return endMs; }
    public long coverMs() { return coverMs; }

    public void setStart(long value) {
        startMs = Math.max(0, Math.min(value, durationMs - 1));
        endMs = Math.max(startMs + 1, Math.min(endMs, maxEndAfter(startMs)));
        setCover(coverMs);
    }

    public void setEnd(long value) {
        endMs = Math.max(1, Math.min(value, durationMs));
        startMs = Math.max(0, Math.min(startMs, endMs - 1));
        startMs = Math.max(startMs, minStartBefore(endMs));
        setCover(coverMs);
    }

    public void setCover(long value) {
        coverMs = Math.max(startMs, Math.min(value, endMs - 1));
    }

    /** 给定起点时最远能到哪儿；不限时长时就是视频末尾。 */
    private long maxEndAfter(long start) {
        return maxDurationMs <= 0 ? durationMs : Math.min(durationMs, start + maxDurationMs);
    }

    /** 给定终点时最早能退到哪儿；不限时长时就是 0。 */
    private long minStartBefore(long end) {
        return maxDurationMs <= 0 ? 0 : Math.max(0, end - maxDurationMs);
    }
}
