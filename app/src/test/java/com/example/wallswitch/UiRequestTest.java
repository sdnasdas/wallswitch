package com.example.wallswitch;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class UiRequestTest {
    @Test public void repeatedTabKeepsOnlyLastTargetWithoutDroppingOverlayReturns() {
        UiMotion.Requests requests = new UiMotion.Requests();
        Object tab = new Object();
        Object detail = new Object();
        Object library = new Object();
        List<String> events = new ArrayList<>();
        requests.put(detail, () -> events.add("close-detail"));
        requests.put(tab, () -> events.add("gallery"));
        requests.put(library, () -> events.add("close-library"));
        requests.put(tab, () -> events.add("home"));
        while (!requests.isEmpty()) requests.take().run();
        assertEquals(List.of("close-detail", "home", "close-library"), events);
    }

    @Test public void reversedRequestRemovesOnlyItsOwnPendingTarget() {
        UiMotion.Requests requests = new UiMotion.Requests();
        Object overlay = new Object();
        Object tab = new Object();
        List<String> events = new ArrayList<>();
        requests.put(overlay, () -> events.add("overlay"));
        requests.put(tab, () -> events.add("tab"));
        requests.remove(overlay);
        requests.take().run();
        assertEquals(List.of("tab"), events);
        assertTrue(requests.isEmpty());
    }

    @Test public void clearingRequestsDiscardsCallbacks() {
        UiMotion.Requests requests = new UiMotion.Requests();
        requests.put(new Object(), () -> fail("discarded callback"));
        requests.clear();
        assertTrue(requests.isEmpty());
        assertNull(requests.take());
    }

    @Test public void replacingSameImageIdInvalidatesOldRevisionOnly() {
        UiImages.Revisions revisions = new UiImages.Revisions();
        long epoch = revisions.epoch();
        revisions.evict("first");
        assertFalse(revisions.matches("first", 0, epoch));
        assertTrue(revisions.matches("first", 1, epoch));
        assertTrue(revisions.matches("second", 0, epoch));
    }

    @Test public void restoringLibraryInvalidatesAllOldRequestsIncludingSameIds() {
        UiImages.Revisions revisions = new UiImages.Revisions();
        long epoch = revisions.epoch();
        revisions.evict("first");
        revisions.clear();
        assertFalse(revisions.matches("first", 1, epoch));
        assertFalse(revisions.matches("second", 0, epoch));
        assertTrue(revisions.matches("first", 0, revisions.epoch()));
    }

    @Test public void evictionAfterRestoreCannotReviveAnOlderRequest() {
        UiImages.Revisions revisions = new UiImages.Revisions();
        revisions.evict("image");
        long oldEpoch = revisions.epoch();
        revisions.clear();
        revisions.evict("image");
        assertFalse(revisions.matches("image", 1, oldEpoch));
        assertTrue(revisions.matches("image", 1, revisions.epoch()));
    }
}
