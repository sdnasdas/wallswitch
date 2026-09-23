package com.example.wallswitch;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

/**
 * 左滑露出右侧操作按钮的行容器（「左滑显示删除」）。
 *
 * 布局约定：<b>第一个</b>子 View 是操作区（贴右、定宽），<b>第二个</b>是内容区（整行卡片）。
 * FrameLayout 按声明顺序绘制，所以内容区在上层盖住操作区；内容区整体左移后，右侧操作区露出来。
 *
 * 手势策略：只有横向位移明显大于纵向位移时才拦截，否则交给 RecyclerView 正常纵向滚动，
 * 避免「想滚列表结果把行滑开了」。
 */
public class SwipeRevealLayout extends FrameLayout {

    /** 行被滑开时回调，用于让列表收起其它已滑开的行。 */
    public interface OnRevealListener {
        void onRevealed(SwipeRevealLayout layout);
    }

    private static final long SETTLE_DURATION = 150L;

    private View actions;
    private View content;
    private int touchSlop;
    private float downX;
    private float downY;
    private float startTranslation;
    private boolean dragging;
    private OnRevealListener revealListener;

    public SwipeRevealLayout(Context context) {
        this(context, null);
    }

    public SwipeRevealLayout(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SwipeRevealLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        if (getChildCount() >= 2) {
            actions = getChildAt(0);
            content = getChildAt(1);
        }
    }

    public void setOnRevealListener(OnRevealListener listener) {
        this.revealListener = listener;
    }

    /** 内容区当前是否处于滑开状态。 */
    public boolean isOpen() {
        return content != null && content.getTranslationX() != 0f;
    }

    /** 收起本行（已收起时不做任何事）。 */
    public void close() {
        if (content == null || content.getTranslationX() == 0f) {
            return;
        }
        content.animate().translationX(0f).setDuration(SETTLE_DURATION).start();
    }

    private int actionWidth() {
        return actions == null ? 0 : actions.getWidth();
    }

    /** 松手后按滑过一半与否，吸附到「完全滑开」或「完全收起」。 */
    private void settle() {
        if (content == null) {
            return;
        }
        int width = actionWidth();
        float half = -width / 2f;
        boolean open = content.getTranslationX() < half;
        content.animate().translationX(open ? -width : 0f).setDuration(SETTLE_DURATION).start();
        if (open && revealListener != null) {
            revealListener.onRevealed(this);
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (content == null || actionWidth() == 0) {
            return false;
        }
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                dragging = false;
                startTranslation = content.getTranslationX();
                break;
            case MotionEvent.ACTION_MOVE:
                float dx = ev.getX() - downX;
                float dy = ev.getY() - downY;
                // 横向位移占优才接管，否则让列表纵向滚动
                if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy)) {
                    dragging = true;
                    return true;
                }
                break;
            default:
                break;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (content == null || !dragging) {
            return super.onTouchEvent(ev);
        }
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE: {
                float target = startTranslation + (ev.getX() - downX);
                float min = -actionWidth();
                if (target > 0f) {
                    target = 0f;
                } else if (target < min) {
                    target = min;
                }
                content.setTranslationX(target);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                settle();
                return true;
            default:
                return true;
        }
    }
}
