package io.github.sdnasdas.wallswitch;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.os.Build;
import android.os.Trace;
import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;

import androidx.transition.ChangeImageTransform;
import androidx.transition.Transition;
import androidx.transition.TransitionManager;
import androidx.transition.TransitionSet;

import com.google.android.material.transition.MaterialFadeThrough;

final class UiMotion {
    private final Activity activity;
    private final ViewGroup root;
    private final Requests deferred = new Requests();
    private final java.util.Map<View, Integer> accessibility = new java.util.IdentityHashMap<>();
    private final java.util.Map<View, java.lang.ref.WeakReference<View>> returnFocus = new java.util.IdentityHashMap<>();
    private Navigation running;
    private final Requests pending = new Requests();
    private final Object tabRequest = new Object();
    private boolean settling;
    private View currentTab;
    private View[] layers = new View[0];
    private boolean drainPosted;
    private boolean closed;
    private int traceCookie;
    private final Runnable drain = this::drainOne;

    static final class Requests {
        private final java.util.LinkedHashMap<Object, Runnable> values = new java.util.LinkedHashMap<>();

        void put(Object key, Runnable action) {
            values.put(key, action);
        }

        void remove(Object key) {
            values.remove(key);
        }

        boolean isEmpty() {
            return values.isEmpty();
        }

        Runnable take() {
            return values.isEmpty() ? null : values.remove(values.keySet().iterator().next());
        }

        void clear() {
            values.clear();
        }
    }

    static final class ContentBindings {
        private record Bound(long generation, int position) { }
        private long generation;
        private final java.util.WeakHashMap<Object, Bound> values = new java.util.WeakHashMap<>();

        void changed() {
            generation++;
        }

        void bound(Object view, int position) {
            values.put(view, new Bound(generation, position));
        }

        boolean matches(Object view, int position) {
            return new Bound(generation, position).equals(values.get(view));
        }
    }

    UiMotion(Activity activity) {
        this.activity = activity;
        root = activity.findViewById(android.R.id.content);
    }

    void setLayers(View... views) {
        layers = views;
        for (View view : views) {
            accessibility.put(view, view.getImportantForAccessibility());
        }
        syncAccessibility();
    }

    boolean isRunning() {
        return running != null;
    }

    boolean isAnimating() {
        return running != null && running.animator != null;
    }

    void whenIdle(Object key, Runnable action) {
        if (!closed) {
            deferred.put(key, action);
            scheduleDrain();
        }
    }

    void discard(Object key) {
        deferred.remove(key);
    }

    private void scheduleDrain() {
        if (!closed && running == null && !deferred.isEmpty() && !drainPosted) {
            drainPosted = true;
            root.postOnAnimation(drain);
        }
    }

    private void drainOne() {
        drainPosted = false;
        if (closed || running != null || deferred.isEmpty()) {
            return;
        }
        long deadline = System.nanoTime() + 4_000_000L;
        do {
            deferred.take().run();
        } while (!closed && running == null && !deferred.isEmpty() && System.nanoTime() < deadline);
        scheduleDrain();
    }

    void setTab(View target, Runnable prepare) {
        finish();
        prepare.run();
        if (currentTab != null && currentTab != target) {
            currentTab.setVisibility(View.GONE);
        }
        currentTab = target;
        reset(target, View.VISIBLE);
        syncAccessibility();
    }

    void restore(View target) {
        reset(target, View.VISIBLE);
        syncAccessibility();
    }

    void replace(View target, Runnable prepare, java.util.function.BooleanSupplier ready) {
        if (closed) {
            return;
        }
        if (running != null) {
            if (running.tab && (target == running.front || target == running.back)) {
                pending.remove(tabRequest);
                if (target == running.front) running.enterWhenReady(ready);
                else running.towards(false);
            } else {
                pending.put(tabRequest, () -> replace(target, prepare, ready));
            }
            return;
        }
        if (target == currentTab) {
            prepare.run();
            return;
        }
        prepare.run();
        ViewGroup host = (ViewGroup) target.getParent();
        boolean forward = currentTab == null
                || host.indexOfChild(target) > host.indexOfChild(currentTab);
        Navigation next = new Navigation(currentTab, target, true, forward, ready);
        running = next;
        next.prepare(true);
    }

    void show(View target, java.util.function.BooleanSupplier ready) {
        if (closed) {
            return;
        }
        if (running != null) {
            if (!running.tab && running.front == target) {
                pending.remove(target);
                running.enterWhenReady(ready);
            } else {
                pending.put(target, () -> show(target, ready));
            }
            return;
        }
        if (target.getVisibility() == View.VISIBLE) {
            return;
        }
        Navigation next = new Navigation(null, target, false, true, ready);
        running = next;
        next.prepare(true);
    }

    void show(View target) {
        show(target, () -> true);
    }

    void hide(View target) {
        if (closed) {
            return;
        }
        if (running != null) {
            if (!running.tab && running.front == target) {
                pending.remove(target);
                running.towards(false);
            } else {
                pending.put(target, () -> hide(target));
            }
            return;
        }
        if (target.getVisibility() != View.VISIBLE) {
            return;
        }
        Navigation next = new Navigation(null, target, false, true, () -> true);
        running = next;
        next.prepare(false);
    }

    private void syncAccessibility() {
        int top = 0;
        for (int i = 1; i < layers.length; i++) {
            if (layers[i].getVisibility() == View.VISIBLE) {
                top = i;
            }
        }
        for (int i = 0; i < layers.length; i++) {
            View view = layers[i];
            view.setImportantForAccessibility(i < top
                    ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS : accessibility.get(view));
        }
    }

    static float pageTranslation(boolean tab, float offset, float progress) {
        return tab ? 0f : offset * (1f - progress);
    }

    static int pageDuration(boolean tab, boolean enter, int enterDuration, int exitDuration) {
        return tab || enter ? enterDuration : exitDuration;
    }

    private static void reset(View view, int visibility) {
        view.setVisibility(visibility);
        view.setAlpha(1f);
        view.setTranslationX(0f);
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

    void finish() {
        settling = true;
        if (running != null) {
            running.commit(running.entering);
        }
        while (!pending.isEmpty() && !closed) {
            pending.take().run();
            if (running != null) {
                running.commit(running.entering);
            }
        }
        settling = false;
        finishPreview(activity);
    }

    private void nextRequest() {
        while (!settling && running == null && !pending.isEmpty() && !closed) {
            pending.take().run();
        }
        scheduleDrain();
    }

    void close() {
        finish();
        closed = true;
        deferred.clear();
        root.removeCallbacks(drain);
        drainPosted = false;
        for (java.util.Map.Entry<View, Integer> entry : accessibility.entrySet()) {
            entry.getKey().setImportantForAccessibility(entry.getValue());
        }
        accessibility.clear();
        returnFocus.clear();
    }

    static void finishPreview(Activity activity) {
        TransitionManager.endTransitions(activity.findViewById(android.R.id.content));
    }

    private final class Navigation {
        final View back;
        final View front;
        final boolean tab;
        java.util.function.BooleanSupplier ready;
        final float offset;
        final float oldZ;
        final int oldAccessibility;
        final int backAccessibility;
        final int cookie = ++traceCookie;
        final ViewGroup parent;
        final View shield;
        final View focused;
        ViewTreeObserver observer;
        ViewTreeObserver.OnPreDrawListener preDraw;
        ValueAnimator animator;
        float progress;
        boolean entering;
        boolean traced;
        boolean movementStarted;

        Navigation(View back, View front, boolean tab, boolean forward,
                   java.util.function.BooleanSupplier ready) {
            this.back = back;
            this.front = front;
            this.tab = tab;
            this.ready = ready;
            float distance = activity.getResources().getDisplayMetrics().widthPixels
                    * activity.getResources().getInteger(R.integer.ui_motion_distance_percent) / 100f;
            offset = (forward != (root.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL))
                    ? distance : -distance;
            oldZ = front.getTranslationZ();
            oldAccessibility = front.getImportantForAccessibility();
            backAccessibility = back == null ? View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                    : back.getImportantForAccessibility();
            parent = (ViewGroup) front.getParent();
            if (!tab && front.getVisibility() != View.VISIBLE) {
                returnFocus.put(front, new java.lang.ref.WeakReference<>(activity.getCurrentFocus()));
            }
            java.lang.ref.WeakReference<View> previousFocus = returnFocus.get(front);
            focused = tab ? activity.getCurrentFocus()
                    : previousFocus == null ? null : previousFocus.get();
            shield = new View(activity);
            shield.setClickable(true);
            shield.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void prepare(boolean enter) {
            entering = enter;
            if (Build.VERSION.SDK_INT >= 29) {
                Trace.beginAsyncSection("ui.navigation", cookie);
                traced = true;
            }
            if (!root.isLaidOut() || !ValueAnimator.areAnimatorsEnabled()) {
                commit(enter);
                return;
            }
            if (tab) {
                float z = front.getZ();
                for (int i = 0; i < parent.getChildCount(); i++) {
                    z = Math.max(z, parent.getChildAt(i).getZ());
                }
                front.setTranslationZ(z + 1f - front.getElevation());
            }
            progress = enter ? 0f : 1f;
            front.setVisibility(View.VISIBLE);
            applyProgress();
            syncAccessibility();
            if (back != null) {
                back.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            }
            front.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            shield.setTranslationZ(Math.max(front.getZ(), 1f) + 1f);
            parent.addView(shield, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            awaitReady();
        }

        void enterWhenReady(java.util.function.BooleanSupplier ready) {
            this.ready = ready;
            if (ready.getAsBoolean()) {
                towards(true);
                return;
            }
            if (animator != null) {
                animator.removeAllListeners();
                animator.cancel();
                animator = null;
            }
            entering = true;
            // 未换绑时隐藏旧内容，但保留行程以便就绪后续走。
            front.setAlpha(0f);
            awaitReady();
        }

        private void awaitReady() {
            if (preDraw != null) return;
            observer = front.getViewTreeObserver();
            preDraw = () -> {
                if (running != this || !ready.getAsBoolean() || !front.isLaidOut()) {
                    return true;
                }
                removePreDraw();
                applyProgress();
                Trace.beginSection("ui.navigation.ready");
                towards(entering);
                Trace.endSection();
                return true;
            };
            observer.addOnPreDrawListener(preDraw);
            front.requestLayout();
        }

        void towards(boolean enter) {
            if (entering == enter && animator != null) {
                return;
            }
            entering = enter;
            if (preDraw != null) {
                if (!enter && progress == 0f) {
                    commit(false);
                }
                return;
            }
            if (animator != null) {
                animator.removeAllListeners();
                animator.cancel();
            }
            float target = enter ? 1f : 0f;
            if (!ValueAnimator.areAnimatorsEnabled() || progress == target) {
                commit(enter);
                return;
            }
            int duration = pageDuration(tab, enter,
                    activity.getResources().getInteger(R.integer.ui_motion_enter_duration),
                    activity.getResources().getInteger(R.integer.ui_motion_exit_duration));
            animator = ValueAnimator.ofFloat(progress, target);
            animator.setDuration(Math.max(1, Math.round(duration * Math.abs(target - progress))));
            animator.setInterpolator(tab ? new LinearInterpolator()
                    : enter ? new DecelerateInterpolator() : new AccelerateInterpolator());
            animator.addUpdateListener(value -> {
                float next = (float) value.getAnimatedValue();
                if (!movementStarted && next != progress) {
                    movementStarted = true;
                    Trace.beginSection("ui.navigation.first_frame");
                    Trace.endSection();
                }
                progress = next;
                applyProgress();
            });
            animator.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator animation) {
                    if (running == Navigation.this) {
                        commit(entering);
                    }
                }
            });
            animator.start();
        }

        void applyProgress() {
            // 底页保持实底，上层alpha使内容交叉混合，避免两层同时降透明度时漏底。
            front.setAlpha(progress);
            front.setTranslationX(pageTranslation(tab, offset, progress));
        }

        void removePreDraw() {
            if (preDraw != null) {
                if (observer != null && observer.isAlive()) {
                    observer.removeOnPreDrawListener(preDraw);
                }
                ViewTreeObserver current = front.getViewTreeObserver();
                if (current != observer && current.isAlive()) {
                    current.removeOnPreDrawListener(preDraw);
                }
                preDraw = null;
                observer = null;
            }
        }

        void commit(boolean enter) {
            removePreDraw();
            if (animator != null) {
                animator.removeAllListeners();
                animator.cancel();
                animator = null;
            }
            reset(front, enter ? View.VISIBLE : View.GONE);
            front.setTranslationZ(oldZ);
            front.setImportantForAccessibility(oldAccessibility);
            if (tab) {
                if (back != null) {
                    reset(back, enter ? View.GONE : View.VISIBLE);
                    back.setImportantForAccessibility(backAccessibility);
                }
                currentTab = enter ? front : back;
            }
            parent.removeView(shield);
            running = null;
            if (traced && Build.VERSION.SDK_INT >= 29) {
                Trace.endAsyncSection("ui.navigation", cookie);
                traced = false;
            }
            syncAccessibility();
            if (!enter) {
                returnFocus.remove(front);
                if (focused != null && focused.isShown()) {
                    focused.requestFocus();
                }
            }
            nextRequest();
        }
    }
}
