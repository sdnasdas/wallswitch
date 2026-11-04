package com.example.wallswitch;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;

import androidx.transition.ChangeImageTransform;
import androidx.transition.Transition;
import androidx.transition.TransitionManager;
import androidx.transition.TransitionSet;

import com.google.android.material.transition.FadeThroughProvider;
import com.google.android.material.transition.MaterialFadeThrough;

final class UiMotion {
    private static Navigation running;

    private UiMotion() {
    }

    static void navigate(Activity activity, boolean forward, View... targets) {
        finish(activity);
        View outgoing = null;
        View incoming = null;
        if (targets.length == 2) {
            outgoing = targets[0];
            incoming = targets[1];
        } else if (targets.length == 1) {
            if (targets[0].getVisibility() == View.VISIBLE) {
                outgoing = targets[0];
            } else {
                incoming = targets[0];
            }
        }
        ViewGroup root = activity.findViewById(android.R.id.content);
        if (!root.isLaidOut() || !ValueAnimator.areAnimatorsEnabled()) {
            settle(outgoing, incoming);
            return;
        }
        float distance = activity.getResources().getDisplayMetrics().widthPixels
                * activity.getResources().getInteger(R.integer.ui_motion_distance_percent) / 100f;
        float enterOffset = forward ? distance : -distance;
        if (root.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) {
            enterOffset = -enterOffset;
        }
        running = new Navigation(outgoing, incoming, enterOffset,
                activity.getResources().getInteger(R.integer.ui_motion_exit_duration),
                activity.getResources().getInteger(R.integer.ui_motion_enter_duration));
        running.start();
    }

    static void preview(Activity activity, View images, View... chrome) {
        ChangeImageTransform imageTransform = new ChangeImageTransform();
        if (images instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) images;
            for (int i = 0; i < group.getChildCount(); i++) {
                View image = group.getChildAt(i);
                if (image instanceof ImageView) {
                    imageTransform.addTarget(image);
                }
            }
        }
        begin(activity, new TransitionSet().setOrdering(TransitionSet.ORDERING_TOGETHER)
                .addTransition(fadeThrough(activity, chrome))
                .addTransition(imageTransform));
    }

    private static MaterialFadeThrough fadeThrough(Activity activity, View... targets) {
        MaterialFadeThrough transition = new MaterialFadeThrough();
        transition.getPrimaryAnimatorProvider().setProgressThreshold(fadeThreshold(activity));
        for (View target : targets) {
            transition.addTarget(target);
        }
        return transition;
    }

    private static float fadeThreshold(Activity activity) {
        return activity.getResources().getInteger(R.integer.ui_motion_exit_duration)
                / (float) activity.getResources().getInteger(R.integer.ui_motion_duration);
    }

    private static void begin(Activity activity, Transition transition) {
        ViewGroup root = activity.findViewById(android.R.id.content);
        TransitionManager.endTransitions(root);
        if (!root.isLaidOut() || !ValueAnimator.areAnimatorsEnabled()) {
            return;
        }
        transition.setDuration(activity.getResources().getInteger(R.integer.ui_motion_duration));
        TransitionManager.beginDelayedTransition(root, transition);
    }

    static void finish(Activity activity) {
        Navigation navigation = running;
        running = null;
        if (navigation != null) {
            navigation.settle();
        }
        TransitionManager.endTransitions(activity.findViewById(android.R.id.content));
    }

    private static void settle(View outgoing, View incoming) {
        if (outgoing != null) {
            outgoing.setVisibility(View.GONE);
            outgoing.setAlpha(1f);
            outgoing.setTranslationX(0f);
        }
        if (incoming != null) {
            incoming.setVisibility(View.VISIBLE);
            incoming.setAlpha(1f);
            incoming.setTranslationX(0f);
        }
    }

    private static final class Navigation {
        private final View outgoing;
        private final View incoming;
        private final float enterOffset;
        private final int exitDuration;
        private final int enterDuration;

        private Navigation(View outgoing, View incoming, float enterOffset,
                           int exitDuration, int enterDuration) {
            this.outgoing = outgoing;
            this.incoming = incoming;
            this.enterOffset = enterOffset;
            this.exitDuration = exitDuration;
            this.enterDuration = enterDuration;
        }

        private void start() {
            if (incoming != null) {
                incoming.setAlpha(0f);
                incoming.setTranslationX(enterOffset);
            }
            if (outgoing == null) {
                enter();
                return;
            }
            outgoing.setVisibility(View.VISIBLE);
            outgoing.setAlpha(1f);
            outgoing.setTranslationX(0f);
            outgoing.animate().alpha(0f).translationX(-enterOffset)
                    .setDuration(exitDuration)
                    .setInterpolator(new AccelerateInterpolator())
                    .withEndAction(() -> {
                        if (running != this) {
                            return;
                        }
                        outgoing.setVisibility(View.GONE);
                        outgoing.setAlpha(1f);
                        outgoing.setTranslationX(0f);
                        enter();
                    }).start();
        }

        private void enter() {
            if (incoming == null) {
                running = null;
                return;
            }
            incoming.setVisibility(View.VISIBLE);
            incoming.animate().alpha(1f).translationX(0f)
                    .setDuration(enterDuration)
                    .setInterpolator(new DecelerateInterpolator())
                    .withEndAction(() -> {
                        if (running == this) {
                            running = null;
                        }
                    }).start();
        }

        private void settle() {
            if (outgoing != null) {
                outgoing.animate().withEndAction(null).cancel();
            }
            if (incoming != null) {
                incoming.animate().withEndAction(null).cancel();
            }
            UiMotion.settle(outgoing, incoming);
        }
    }
}
