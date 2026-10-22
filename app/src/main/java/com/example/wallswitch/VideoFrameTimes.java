package com.example.wallswitch;

import java.util.Arrays;

public final class VideoFrameTimes {
    private final long[] timestampsUs;

    public VideoFrameTimes(long[] timestampsUs) {
        this.timestampsUs = timestampsUs.clone();
        Arrays.sort(this.timestampsUs);
    }

    public long nearest(long requestedUs, long startMs, long endMs) {
        int first = lowerBound(startMs * 1000);
        int end = lowerBound(endMs * 1000);
        if (first >= end) {
            return -1;
        }
        int next = Math.max(first, Math.min(lowerBound(requestedUs), end - 1));
        int previous = Math.max(first, next - 1);
        return Math.abs(timestampsUs[previous] - requestedUs) <= Math.abs(timestampsUs[next] - requestedUs)
                ? timestampsUs[previous] : timestampsUs[next];
    }

    private int lowerBound(long value) {
        int low = 0;
        int high = timestampsUs.length;
        while (low < high) {
            int middle = low + (high - low) / 2;
            if (timestampsUs[middle] < value) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }
}
