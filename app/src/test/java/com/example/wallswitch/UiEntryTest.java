package com.example.wallswitch;

import org.junit.Test;

import static org.junit.Assert.*;

public class UiEntryTest {
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
