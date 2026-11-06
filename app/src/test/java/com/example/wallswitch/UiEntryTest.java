package com.example.wallswitch;

import org.junit.Test;

import static org.junit.Assert.*;

public class UiEntryTest {
    @Test public void tabCrossfadeNeverMovesInEitherDirection() {
        for (float offset : new float[]{-300f, 300f}) {
            for (float progress : new float[]{0f, 0.25f, 0.5f, 0.75f, 1f}) {
                assertEquals(0f, UiMotion.pageTranslation(true, offset, progress), 0f);
            }
        }
    }

    @Test public void secondaryPagesKeepTheirCoverTranslation() {
        assertEquals(300f, UiMotion.pageTranslation(false, 300f, 0f), 0f);
        assertEquals(150f, UiMotion.pageTranslation(false, 300f, 0.5f), 0f);
        assertEquals(-150f, UiMotion.pageTranslation(false, -300f, 0.5f), 0f);
        assertEquals(0f, UiMotion.pageTranslation(false, 300f, 1f), 0f);
    }

    @Test public void tabCrossfadeUsesSameDurationForwardAndBack() {
        assertEquals(300, UiMotion.pageDuration(true, true, 300, 150));
        assertEquals(300, UiMotion.pageDuration(true, false, 300, 150));
        assertEquals(300, UiMotion.pageDuration(false, true, 300, 150));
        assertEquals(150, UiMotion.pageDuration(false, false, 300, 150));
    }

    @Test public void newPageCannotUseBindingsFromPreviousDataEvenAtSamePosition() {
        UiMotion.ContentBindings bindings = new UiMotion.ContentBindings();
        Object header = new Object();
        bindings.bound(header, 0);
        assertTrue(bindings.matches(header, 0));
        bindings.changed();
        assertFalse(bindings.matches(header, 0));
        bindings.bound(header, 0);
        assertTrue(bindings.matches(header, 0));
    }

    @Test public void recycledViewMustMatchItsCurrentPosition() {
        UiMotion.ContentBindings bindings = new UiMotion.ContentBindings();
        Object view = new Object();
        bindings.bound(view, 1);
        assertFalse(bindings.matches(view, 2));
        bindings.bound(view, 2);
        assertTrue(bindings.matches(view, 2));
        assertFalse(bindings.matches(view, 1));
    }

    @Test public void unchangedDataCanReuseCurrentBindingsWithoutRebindingEachFrame() {
        UiMotion.ContentBindings bindings = new UiMotion.ContentBindings();
        Object first = new Object();
        Object second = new Object();
        assertFalse(bindings.matches(first, 0));
        bindings.bound(first, 0);
        bindings.bound(second, 1);
        assertTrue(bindings.matches(first, 0));
        assertTrue(bindings.matches(second, 1));
        bindings.changed();
        bindings.bound(first, 0);
        assertTrue(bindings.matches(first, 0));
        assertFalse(bindings.matches(second, 1));
    }
}
