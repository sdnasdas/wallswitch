package com.example.wallswitch;

public final class VideoSelection {
    public static final long MAX_DURATION_MS = 3000;
    private final long durationMs;
    private long startMs;
    private long endMs;
    private long coverMs;

    public VideoSelection(long durationMs) {
        if (durationMs <= 0) {
            throw new IllegalArgumentException("视频时长无效");
        }
        this.durationMs = durationMs;
        endMs = Math.min(durationMs, MAX_DURATION_MS);
    }

    public long durationMs() { return durationMs; }
    public long startMs() { return startMs; }
    public long endMs() { return endMs; }
    public long coverMs() { return coverMs; }

    public void setStart(long value) {
        startMs = Math.max(0, Math.min(value, durationMs - 1));
        endMs = Math.max(startMs + 1, Math.min(endMs, startMs + MAX_DURATION_MS));
        setCover(coverMs);
    }

    public void setEnd(long value) {
        endMs = Math.max(1, Math.min(value, durationMs));
        startMs = Math.max(0, Math.min(startMs, endMs - 1));
        startMs = Math.max(startMs, endMs - MAX_DURATION_MS);
        setCover(coverMs);
    }

    public void setCover(long value) {
        coverMs = Math.max(startMs, Math.min(value, endMs - 1));
    }
}
