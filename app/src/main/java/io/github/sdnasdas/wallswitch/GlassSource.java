package io.github.sdnasdas.wallswitch;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RecordingCanvas;
import android.graphics.RenderNode;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

/** 把一级页内容录进一份 RenderNode，供 {@link LiquidGlassView} 在自己身上折射重绘。 */
public class GlassSource extends FrameLayout {

    private RenderNode node;
    private View listener;

    public GlassSource(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    RenderNode node() {
        return node;
    }

    void setListener(View glass) {
        listener = glass;
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        if (Build.VERSION.SDK_INT < 33 || !canvas.isHardwareAccelerated()) {
            super.dispatchDraw(canvas);
            return;
        }
        if (node == null) {
            node = new RenderNode("glass-source");
        }
        node.setPosition(0, 0, getWidth(), getHeight());
        RecordingCanvas recording = node.beginRecording();
        super.dispatchDraw(recording);
        node.endRecording();
        canvas.drawRenderNode(node);
        notifyGlass();
    }

    // 子页只重录自己的显示列表时本层 dispatchDraw 不会跑，玻璃得在这里跟着重画。
    @Override
    public void onDescendantInvalidated(View child, View target) {
        super.onDescendantInvalidated(child, target);
        notifyGlass();
    }

    private void notifyGlass() {
        if (listener != null && listener.getVisibility() == VISIBLE) {
            listener.invalidate();
        }
    }
}
