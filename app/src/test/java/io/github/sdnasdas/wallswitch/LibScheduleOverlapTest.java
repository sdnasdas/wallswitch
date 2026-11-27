package io.github.sdnasdas.wallswitch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Calendar;

public class LibScheduleOverlapTest {

    @Test
    public void ordinaryIntervalsDetectPartialOverlap() {
        assertTrue(LibScheduleStore.overlaps(7 * 60, 9 * 60, 8 * 60, 10 * 60));
        assertTrue(LibScheduleStore.overlaps(7 * 60, 12 * 60, 8 * 60, 9 * 60));
        assertFalse(LibScheduleStore.overlaps(7 * 60, 8 * 60, 9 * 60, 10 * 60));
    }

    @Test
    public void adjacentIntervalsDoNotOverlap() {
        assertFalse(LibScheduleStore.overlaps(7 * 60, 8 * 60, 8 * 60, 9 * 60));
        assertFalse(LibScheduleStore.overlaps(8 * 60, 9 * 60, 7 * 60, 8 * 60));
    }

    @Test
    public void crossMidnightIntervalOverlapsBothSides() {
        assertTrue(LibScheduleStore.overlaps(23 * 60, 7 * 60, 6 * 60, 8 * 60));
        assertTrue(LibScheduleStore.overlaps(23 * 60, 7 * 60, 0, 60));
        assertTrue(LibScheduleStore.overlaps(0, 60, 23 * 60, 7 * 60));
        assertTrue(LibScheduleStore.overlaps(23 * 60, 7 * 60, 0, 6 * 60));
    }

    @Test
    public void crossMidnightIntervalHonorsExclusiveEnd() {
        assertFalse(LibScheduleStore.overlaps(23 * 60, 7 * 60, 7 * 60, 8 * 60));
        assertFalse(LibScheduleStore.overlaps(22 * 60, 23 * 60, 23 * 60, 7 * 60));
        assertFalse(LibScheduleStore.overlaps(12 * 60, 13 * 60, 23 * 60, 7 * 60));
    }

    @Test
    public void twoCrossMidnightIntervalsOverlap() {
        assertTrue(LibScheduleStore.overlaps(23 * 60, 7 * 60, 22 * 60, 6 * 60));
        assertTrue(LibScheduleStore.overlaps(23 * 60, 7 * 60, 23 * 60, 7 * 60));
    }

    @Test
    public void emptyIntervalsDoNotOverlap() {
        assertFalse(LibScheduleStore.overlaps(7 * 60, 7 * 60, 6 * 60, 8 * 60));
        assertFalse(LibScheduleStore.overlaps(6 * 60, 8 * 60, 7 * 60, 7 * 60));
    }

    @Test
    public void daytimeSegmentStartsToday() {
        LibScheduleStore.Rule rule = new LibScheduleStore.Rule(7 * 60, 9 * 60, "lib");
        assertEquals(at(2026, Calendar.NOVEMBER, 24, 7, 0),
                LibScheduleStore.segmentStartMillis(rule,
                        at(2026, Calendar.NOVEMBER, 24, 8, 30)));
    }

    @Test
    public void crossMidnightSegmentAfterMidnightStartsYesterday() {
        LibScheduleStore.Rule rule = new LibScheduleStore.Rule(23 * 60, 7 * 60, "lib");
        assertEquals(at(2026, Calendar.NOVEMBER, 23, 23, 0),
                LibScheduleStore.segmentStartMillis(rule,
                        at(2026, Calendar.NOVEMBER, 24, 2, 0)));
    }

    @Test
    public void crossMidnightSegmentEndIsExclusive() {
        LibScheduleStore.Rule rule = new LibScheduleStore.Rule(23 * 60, 7 * 60, "lib");
        assertEquals(-1L, LibScheduleStore.segmentStartMillis(rule,
                at(2026, Calendar.NOVEMBER, 24, 7, 0)));
    }

    private static long at(int year, int month, int day, int hour, int minute) {
        Calendar cal = Calendar.getInstance();
        cal.clear();
        cal.set(year, month, day, hour, minute, 0);
        return cal.getTimeInMillis();
    }
}
