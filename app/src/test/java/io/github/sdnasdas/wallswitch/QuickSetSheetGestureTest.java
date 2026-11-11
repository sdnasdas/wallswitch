package io.github.sdnasdas.wallswitch;

import org.junit.Test;

import static org.junit.Assert.*;

public class QuickSetSheetGestureTest {
    private final QuickSetSheetDialog.DragGesture gesture =
            new QuickSetSheetDialog.DragGesture(100f, 8f);

    @Test public void tapAndJitterStayWithTheControl() {
        gesture.begin(20f, 200f, true);
        gesture.move(23f, 204f);
        assertFalse(gesture.isPulling());
        assertEquals(0f, gesture.offset(), 0f);
        assertFalse(gesture.shouldDismiss());
    }

    @Test public void downwardSwipeCancelsClicksBeforeMovingThePanel() {
        gesture.begin(20f, 200f, true);
        gesture.move(20f, 250f);
        assertTrue(gesture.isPulling());
        assertEquals(0f, gesture.offset(), 0f);
        assertFalse(gesture.shouldDismiss());
    }

    @Test public void exactlyAtThresholdStaysExpanded() {
        gesture.begin(20f, 200f, true);
        gesture.move(20f, 300f);
        assertEquals(0f, gesture.offset(), 0f);
        assertFalse(gesture.shouldDismiss());
    }

    @Test public void onlyTheDistancePastThresholdMovesThePanel() {
        gesture.begin(20f, 200f, true);
        gesture.move(20f, 301f);
        assertEquals(1f, gesture.offset(), 0f);
        assertTrue(gesture.shouldDismiss());
        gesture.move(20f, 345f);
        assertEquals(45f, gesture.offset(), 0f);
    }

    @Test public void retreatingToThresholdCancelsDismissal() {
        gesture.begin(20f, 200f, true);
        gesture.move(20f, 380f);
        gesture.move(20f, 300f);
        assertTrue(gesture.isPulling());
        assertEquals(0f, gesture.offset(), 0f);
        assertFalse(gesture.shouldDismiss());
    }

    @Test public void retreatingBelowThresholdCancelsDismissal() {
        gesture.begin(20f, 200f, true);
        gesture.move(20f, 380f);
        gesture.move(20f, 270f);
        assertEquals(0f, gesture.offset(), 0f);
        assertFalse(gesture.shouldDismiss());
    }

    @Test public void retreatingAboveStartNeverMovesThePanelUpward() {
        gesture.begin(20f, 200f, true);
        gesture.move(20f, 380f);
        gesture.move(20f, 150f);
        assertEquals(0f, gesture.offset(), 0f);
        assertFalse(gesture.shouldDismiss());
    }

    @Test public void aPartialRetreatStillDismissesWhenPastThreshold() {
        gesture.begin(20f, 200f, true);
        gesture.move(20f, 400f);
        gesture.move(20f, 320f);
        assertEquals(20f, gesture.offset(), 0f);
        assertTrue(gesture.shouldDismiss());
    }

    @Test public void downwardMovementCanResumeAfterRetreat() {
        gesture.begin(20f, 200f, true);
        gesture.move(20f, 380f);
        gesture.move(20f, 280f);
        gesture.move(20f, 340f);
        assertEquals(40f, gesture.offset(), 0f);
        assertTrue(gesture.shouldDismiss());
    }

    @Test public void scrollingGestureCannotBecomeDismissalOnReachingTheTop() {
        gesture.begin(20f, 200f, false);
        gesture.move(20f, 350f);
        gesture.move(20f, 900f);
        assertFalse(gesture.isPulling());
        assertEquals(0f, gesture.offset(), 0f);
        assertFalse(gesture.shouldDismiss());
    }

    @Test public void aNewGestureAtTheTopCanDismiss() {
        gesture.begin(20f, 200f, false);
        gesture.move(20f, 500f);
        gesture.begin(20f, 200f, true);
        gesture.move(20f, 350f);
        assertEquals(50f, gesture.offset(), 0f);
        assertTrue(gesture.shouldDismiss());
    }

    @Test public void upwardScrollCannotTurnIntoDismissalWithoutLifting() {
        gesture.begin(20f, 200f, true);
        gesture.move(20f, 150f);
        gesture.move(20f, 500f);
        assertFalse(gesture.isPulling());
        assertFalse(gesture.shouldDismiss());
    }

    @Test public void horizontalSwipeCannotTurnIntoDismissal() {
        gesture.begin(20f, 200f, true);
        gesture.move(60f, 210f);
        gesture.move(60f, 500f);
        assertFalse(gesture.isPulling());
        assertFalse(gesture.shouldDismiss());
    }

    @Test public void tinyUpwardJitterDoesNotBlockADownwardPull() {
        gesture.begin(20f, 200f, true);
        gesture.move(20f, 198f);
        gesture.move(20f, 320f);
        assertTrue(gesture.shouldDismiss());
    }

    @Test public void cancelledOrMultitouchPullCannotResumeUntilNextDown() {
        gesture.begin(20f, 200f, true);
        gesture.move(20f, 380f);
        gesture.cancel();
        gesture.move(20f, 600f);
        assertTrue(gesture.isPulling());
        assertEquals(0f, gesture.offset(), 0f);
        assertFalse(gesture.shouldDismiss());
        gesture.begin(20f, 200f, true);
        assertFalse(gesture.isPulling());
        gesture.move(20f, 320f);
        assertTrue(gesture.shouldDismiss());
    }

    @Test public void aFreshTapDoesNotInheritThePreviousPull() {
        gesture.begin(20f, 200f, true);
        gesture.move(20f, 380f);
        gesture.begin(50f, 600f, true);
        assertFalse(gesture.isPulling());
        assertEquals(0f, gesture.offset(), 0f);
        assertFalse(gesture.shouldDismiss());
    }

    @Test public void thresholdUsesTheConfiguredPixelDensity() {
        QuickSetSheetDialog.DragGesture dense = new QuickSetSheetDialog.DragGesture(300f, 24f);
        dense.begin(0f, 0f, true);
        dense.move(0f, 300f);
        assertEquals(0f, dense.offset(), 0f);
        assertFalse(dense.shouldDismiss());
        dense.move(0f, 330f);
        assertEquals(30f, dense.offset(), 0f);
        assertTrue(dense.shouldDismiss());
    }

    @Test public void defaultThresholdRemains100Dp() {
        assertEquals(100, QuickSetSheetDialog.DEFAULT_THRESHOLD_DP);
        assertTrue(QuickSetSheetDialog.DEFAULT_THRESHOLD_DP >= QuickSetSheetDialog.MIN_THRESHOLD_DP);
        assertTrue(QuickSetSheetDialog.DEFAULT_THRESHOLD_DP <= QuickSetSheetDialog.MAX_THRESHOLD_DP);
        assertEquals(0, (QuickSetSheetDialog.DEFAULT_THRESHOLD_DP - QuickSetSheetDialog.MIN_THRESHOLD_DP)
                % QuickSetSheetDialog.THRESHOLD_STEP_DP);
    }

    @Test public void higherThresholdControlsBothStartAndRetreat() {
        QuickSetSheetDialog.DragGesture configured = new QuickSetSheetDialog.DragGesture(150f, 8f);
        configured.begin(20f, 200f, true);
        configured.move(20f, 320f);
        assertEquals(0f, configured.offset(), 0f);
        assertFalse(configured.shouldDismiss());
        configured.move(20f, 370f);
        assertEquals(20f, configured.offset(), 0f);
        assertTrue(configured.shouldDismiss());
        configured.move(20f, 350f);
        assertEquals(0f, configured.offset(), 0f);
        assertFalse(configured.shouldDismiss());
    }

    @Test public void allPickerValuesKeepTheSameRuleAtDifferentDensities() {
        for (int dp = QuickSetSheetDialog.MIN_THRESHOLD_DP;
                dp <= QuickSetSheetDialog.MAX_THRESHOLD_DP; dp += QuickSetSheetDialog.THRESHOLD_STEP_DP) {
            for (float density : new float[]{1f, 2.75f, 3.5f}) {
                float threshold = dp * density;
                QuickSetSheetDialog.DragGesture configured =
                        new QuickSetSheetDialog.DragGesture(threshold, 8f * density);
                configured.begin(0f, 0f, true);
                configured.move(0f, threshold);
                assertEquals(0f, configured.offset(), 0f);
                assertFalse(configured.shouldDismiss());
                configured.move(0f, threshold + density);
                assertEquals(density, configured.offset(), 0.001f);
                assertTrue(configured.shouldDismiss());
                configured.move(0f, threshold - density);
                assertEquals(0f, configured.offset(), 0f);
                assertFalse(configured.shouldDismiss());
            }
        }
    }

    @Test public void changingThresholdDoesNotBypassGridScrollIsolation() {
        QuickSetSheetDialog.DragGesture configured =
                new QuickSetSheetDialog.DragGesture(QuickSetSheetDialog.MAX_THRESHOLD_DP, 8f);
        configured.begin(0f, 0f, false);
        configured.move(0f, 500f);
        assertEquals(0f, configured.offset(), 0f);
        assertFalse(configured.shouldDismiss());
        configured.begin(0f, 0f, true);
        configured.move(0f, 500f);
        assertEquals(500f - QuickSetSheetDialog.MAX_THRESHOLD_DP, configured.offset(), 0f);
        assertTrue(configured.shouldDismiss());
    }
}
