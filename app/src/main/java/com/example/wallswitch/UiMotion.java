package com.example.wallswitch;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.transition.ChangeImageTransform;
import androidx.transition.Transition;
import androidx.transition.TransitionManager;
import androidx.transition.TransitionSet;

import com.google.android.material.transition.FadeThroughProvider;
import com.google.android.material.transition.MaterialFadeThrough;
import com.google.android.material.transition.MaterialSharedAxis;
import com.google.android.material.transition.SlideDistanceProvider;

final class UiMotion {
    private UiMotion() {
    }

    static void navigate(Activity activity, boolean forward, View... targets) {
        sharedAxis(activity, MaterialSharedAxis.X, forward, targets);
    }

    private static void sharedAxis(Activity activity, int axis, boolean forward, View... targets) {
        MaterialSharedAxis transition = new MaterialSharedAxis(axis, forward);
        // 位移按屏宽取，不是固定 dp：小滑距只剩淡入淡出，看不出「新页从右缘推进来」
        ((SlideDistanceProvider) transition.getPrimaryAnimatorProvider()).setSlideDistance(
                Math.round(activity.getResources().getDisplayMetrics().widthPixels
                        * activity.getResources().getInteger(R.integer.ui_motion_distance_percent)
                        / 100f));
        ((FadeThroughProvider) transition.getSecondaryAnimatorProvider()).setProgressThreshold(
                fadeThreshold(activity));
        for (View target : targets) {
            transition.addTarget(target);
        }
        begin(activity, transition);
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
        TransitionManager.endTransitions(activity.findViewById(android.R.id.content));
    }
}
