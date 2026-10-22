package com.example.wallswitch;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class VideoFrameTimesTest {
    @Test public void nearEndNeverSelectsDiscardedNextFrame() {
        VideoFrameTimes frames = new VideoFrameTimes(new long[]{0, 33_333, 66_667, 100_000});
        assertEquals(66_667, frames.nearest(99_000, 0, 100));
    }
    @Test public void nearStartNeverSelectsDiscardedPreviousFrame() {
        VideoFrameTimes frames = new VideoFrameTimes(new long[]{0, 33_333, 66_667, 100_000});
        assertEquals(66_667, frames.nearest(34_000, 34, 101));
    }
    @Test public void retainsExactSourceFrameTimestamp() {
        VideoFrameTimes frames = new VideoFrameTimes(new long[]{0, 33_333, 66_667, 100_000});
        assertEquals(33_333, frames.nearest(33_333, 0, 100));
    }
    @Test public void sortsPresentationTimesForBFrames() {
        VideoFrameTimes frames = new VideoFrameTimes(new long[]{0, 100_000, 33_333, 66_667});
        assertEquals(66_667, frames.nearest(65_000, 0, 100));
    }
    @Test public void emptyFrameIntervalIsNotExportable() {
        VideoFrameTimes frames = new VideoFrameTimes(new long[]{0, 33_333, 66_667});
        assertEquals(-1, frames.nearest(10_000, 10, 20));
    }
    @Test public void emptySourceIsNotExportable() {
        assertEquals(-1, new VideoFrameTimes(new long[0]).nearest(0, 0, 100));
    }
}
