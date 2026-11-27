package io.github.sdnasdas.wallswitch;

import org.junit.Test;
import static org.junit.Assert.*;

public class VideoSelectionTest {
    @Test public void defaultsToEntireVideo() {
        VideoSelection selection = new VideoSelection(10_000);
        assertEquals(0, selection.startMs());
        assertEquals(10_000, selection.endMs());
        assertEquals(0, selection.coverMs());
    }

    @Test public void selectsEntireShortVideo() {
        VideoSelection selection = new VideoSelection(750);
        assertEquals(750, selection.endMs());
        selection.setCover(750);
        assertEquals(749, selection.coverMs());
    }

    @Test public void movingEndKeepsStart() {
        VideoSelection selection = new VideoSelection(10_000);
        selection.setEnd(8500);
        assertEquals(0, selection.startMs());
        assertEquals(8500, selection.endMs());
        assertEquals(0, selection.coverMs());
    }

    @Test public void movingStartPastTheEndKeepsRangeNonempty() {
        VideoSelection selection = new VideoSelection(10_000);
        selection.setEnd(5000);
        selection.setStart(9000);
        assertEquals(9000, selection.startMs());
        assertEquals(9001, selection.endMs());
        selection.setEnd(10_000);
        assertEquals(9000, selection.startMs());
        assertEquals(10_000, selection.endMs());
    }

    @Test public void coverStaysInsideSelectedRange() {
        VideoSelection selection = new VideoSelection(10_000);
        selection.setCover(1000);
        selection.setStart(1500);
        assertEquals(1500, selection.coverMs());
        selection.setCover(9000);
        assertEquals(9000, selection.coverMs());
        selection.setEnd(2000);
        assertEquals(1999, selection.coverMs());
    }

    @Test public void clampsOutOfBoundsAndKeepsNonemptyRange() {
        VideoSelection selection = new VideoSelection(1000);
        selection.setStart(-50);
        selection.setEnd(-50);
        assertEquals(0, selection.startMs());
        assertEquals(1, selection.endMs());
        selection.setStart(5000);
        selection.setEnd(5000);
        assertEquals(999, selection.startMs());
        assertEquals(1000, selection.endMs());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnknownDuration() { new VideoSelection(0); }
}
