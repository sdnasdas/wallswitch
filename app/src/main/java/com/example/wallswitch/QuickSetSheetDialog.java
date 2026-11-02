package com.example.wallswitch;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

final class QuickSetSheetDialog extends BottomSheetDialog {
    static final String KEY_DISMISS_THRESHOLD_DP = "quick_sheet_dismiss_threshold_dp";
    static final int DEFAULT_THRESHOLD_DP = 100;
    static final int MIN_THRESHOLD_DP = 20;
    static final int MAX_THRESHOLD_DP = 300;
    static final int THRESHOLD_STEP_DP = 10;

    private final View panel;
    private final View grid;
    private final DragGesture gesture;
    private final int[] location = new int[2];
    private boolean settling;

    QuickSetSheetDialog(Context context, View content) {
        super(context);
        setContentView(content);
        panel = findViewById(com.google.android.material.R.id.design_bottom_sheet);
        grid = content.findViewById(R.id.rv_quick_picks);
        gesture = new DragGesture(thresholdDp(context) * context.getResources().getDisplayMetrics().density,
                ViewConfiguration.get(context).getScaledTouchSlop());
        getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
        getBehavior().setSkipCollapsed(true);
        getBehavior().setDraggable(false);
    }

    static int thresholdDp(Context context) {
        int value = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getInt(KEY_DISMISS_THRESHOLD_DP, DEFAULT_THRESHOLD_DP);
        return Math.max(MIN_THRESHOLD_DP, Math.min(MAX_THRESHOLD_DP, value))
                / THRESHOLD_STEP_DP * THRESHOLD_STEP_DP;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (settling) {
            return true;
        }
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            boolean onGrid = grid.getVisibility() == View.VISIBLE && containsTouch(grid, event);
            gesture.begin(event.getRawX(), event.getRawY(), containsTouch(panel, event)
                    && (!onGrid || !grid.canScrollVertically(-1)));
        } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
            boolean pulling = gesture.isPulling();
            gesture.cancel();
            if (pulling) {
                settle(false);
                return true;
            }
        } else if (action == MotionEvent.ACTION_MOVE) {
            boolean wasPulling = gesture.isPulling();
            gesture.move(event.getRawX(), event.getRawY());
            if (gesture.isPulling()) {
                if (!wasPulling) {
                    // 接管时取消原控件的点击与滚动，后续事件只交给抽屉。
                    MotionEvent cancel = MotionEvent.obtain(event);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    super.dispatchTouchEvent(cancel);
                    cancel.recycle();
                }
                panel.setTranslationY(gesture.offset());
                return true;
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (gesture.isPulling()) {
                if (action == MotionEvent.ACTION_UP) {
                    gesture.move(event.getRawX(), event.getRawY());
                    panel.setTranslationY(gesture.offset());
                }
                boolean close = action == MotionEvent.ACTION_UP && gesture.shouldDismiss();
                gesture.cancel();
                settle(close);
                return true;
            }
            gesture.cancel();
        }
        return super.dispatchTouchEvent(event);
    }

    private boolean containsTouch(View view, MotionEvent event) {
        view.getLocationOnScreen(location);
        return event.getRawX() >= location[0] && event.getRawX() < location[0] + view.getWidth()
                && event.getRawY() >= location[1] && event.getRawY() < location[1] + view.getHeight();
    }

    private void settle(boolean close) {
        if (!close && panel.getTranslationY() == 0f) {
            return;
        }
        settling = true;
        float end = close ? panel.getHeight() : 0f;
        float distance = Math.abs(end - panel.getTranslationY());
        // 与原生拖拽的零速度收尾一致，按剩余距离计时并以五次曲线减速。
        long duration = Math.min(600, Math.round(256f * (1f + distance / panel.getHeight())));
        panel.animate().translationY(end)
                .setDuration(duration)
                .setInterpolator(new DecelerateInterpolator(2.5f))
                .withEndAction(() -> {
                    settling = false;
                    if (close) {
                        dismiss();
                    }
                }).start();
    }

    static final class DragGesture {
        private final float threshold;
        private final float touchSlop;
        private float startX;
        private float startY;
        private float offset;
        private boolean eligible;
        private boolean pulling;

        DragGesture(float threshold, float touchSlop) {
            this.threshold = threshold;
            this.touchSlop = touchSlop;
        }

        void begin(float x, float y, boolean canPull) {
            startX = x;
            startY = y;
            offset = 0f;
            eligible = canPull;
            pulling = false;
        }

        void move(float x, float y) {
            if (!eligible) {
                return;
            }
            float dx = Math.abs(x - startX);
            float dy = y - startY;
            if (!pulling) {
                if (Math.max(dx, Math.abs(dy)) <= touchSlop) {
                    return;
                }
                if (dy <= dx) {
                    eligible = false;
                    return;
                }
                pulling = true;
            }
            offset = Math.max(0f, dy - threshold);
        }

        void cancel() {
            eligible = false;
            offset = 0f;
        }

        boolean isPulling() {
            return pulling;
        }

        float offset() {
            return offset;
        }

        boolean shouldDismiss() {
            return eligible && pulling && offset > 0f;
        }
    }
}
