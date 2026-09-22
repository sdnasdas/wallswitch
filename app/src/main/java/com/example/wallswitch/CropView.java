package com.example.wallswitch;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/**
 * 手势裁剪视图：双指捏合缩放（ScaleGestureDetector）+ 单指拖动。
 * 初始按 fit-cover 等比放大到覆盖视图并居中；
 * 边界钳制保证图片任一边不小于视图对应边、图片边缘不进入视图内部（不露黑边）。
 */
public class CropView extends View {

    // 最大缩放倍数（相对初始覆盖比例）
    private static final float MAX_SCALE_FACTOR = 8f;

    private Bitmap bitmap;
    private final Matrix matrix = new Matrix();
    private float minScale = 1f;
    private float maxScale = 1f;
    // 上一次触点坐标（拖动用）
    private float lastX = 0f;
    private float lastY = 0f;
    private final ScaleGestureDetector scaleDetector;

    public CropView(Context context) {
        this(context, null);
    }

    public CropView(Context context, AttributeSet attrs) {
        super(context, attrs);
        ScaleGestureDetector.SimpleOnScaleGestureListener listener = new ScaleListener();
        scaleDetector = new ScaleGestureDetector(context, listener);
    }

    /** 设置待编辑图片并按 fit-cover 重置。 */
    public void setBitmap(Bitmap bitmap) {
        this.bitmap = bitmap;
        resetLayout();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        resetLayout();
    }

    /** fit-cover 初始化：等比放大到覆盖视图并居中，确定缩放范围。 */
    private void resetLayout() {
        if (bitmap == null || getWidth() == 0 || getHeight() == 0) {
            return;
        }
        float viewW = getWidth();
        float viewH = getHeight();
        float bmpW = bitmap.getWidth();
        float bmpH = bitmap.getHeight();
        float coverScale = Math.max(viewW / bmpW, viewH / bmpH);
        minScale = coverScale;
        maxScale = coverScale * MAX_SCALE_FACTOR;
        matrix.reset();
        matrix.setScale(coverScale, coverScale);
        float drawW = bmpW * coverScale;
        float drawH = bmpH * coverScale;
        matrix.postTranslate((viewW - drawW) / 2f, (viewH - drawH) / 2f);
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (bitmap == null) {
            return false;
        }
        boolean handled = scaleDetector.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = event.getX();
                lastY = event.getY();
                handled = true;
                break;
            case MotionEvent.ACTION_MOVE:
                // 双指缩放进行中不做拖动
                if (!scaleDetector.isInProgress()) {
                    float dx = event.getX() - lastX;
                    float dy = event.getY() - lastY;
                    matrix.postTranslate(dx, dy);
                    clampTranslate();
                    invalidate();
                }
                lastX = event.getX();
                lastY = event.getY();
                handled = true;
                break;
            default:
                break;
        }
        return handled;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bitmap != null) {
            canvas.drawBitmap(bitmap, matrix, null);
        }
    }

    /** 双指缩放回调：围绕手势焦点缩放并钳制范围与边界。 */
    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            float current = getCurrentScale();
            float target = current * detector.getScaleFactor();
            // 缩放范围钳制：[初始覆盖比例, 初始比例×8]
            target = Math.max(minScale, target);
            target = Math.min(maxScale, target);
            float applied = target / current;
            matrix.postScale(applied, applied, detector.getFocusX(), detector.getFocusY());
            clampTranslate();
            invalidate();
            return true;
        }
    }

    /** 当前缩放比例（无旋转，取 MSCALE_X）。 */
    private float getCurrentScale() {
        float[] values = new float[9];
        matrix.getValues(values);
        return values[Matrix.MSCALE_X];
    }

    /** 平移钳制：图片边缘不得进入视图内部（不露背景）。 */
    private void clampTranslate() {
        if (bitmap == null) {
            return;
        }
        RectF rect = new RectF(0f, 0f, bitmap.getWidth(), bitmap.getHeight());
        matrix.mapRect(rect);
        float viewW = getWidth();
        float viewH = getHeight();
        float dx = 0f;
        float dy = 0f;
        if (rect.left > 0f) {
            dx = -rect.left;
        } else if (rect.right < viewW) {
            dx = viewW - rect.right;
        }
        if (rect.top > 0f) {
            dy = -rect.top;
        } else if (rect.bottom < viewH) {
            dy = viewH - rect.bottom;
        }
        if (dx != 0f || dy != 0f) {
            matrix.postTranslate(dx, dy);
        }
    }

    /**
     * 按当前手势把可见区域导出为最长边不超过 maxDim 的位图。
     * 可见区域经矩阵逆变换映射回原图坐标，取交集防浮点误差，再缩放绘制到新位图。
     */
    public Bitmap export(int maxDim) {
        if (bitmap == null) {
            return null;
        }
        Matrix inverse = new Matrix();
        matrix.invert(inverse);
        RectF visible = new RectF(0f, 0f, getWidth(), getHeight());
        inverse.mapRect(visible);
        // 裁回原图范围，防止浮点误差越界
        RectF src = new RectF(visible);
        boolean overlap = src.intersect(0f, 0f, bitmap.getWidth(), bitmap.getHeight());
        if (!overlap || src.isEmpty()) {
            return null;
        }
        int srcW = Math.round(src.width());
        int srcH = Math.round(src.height());
        int longest = Math.max(srcW, srcH);
        float outScale = 1f;
        if (longest > maxDim) {
            outScale = (float) maxDim / longest;
        }
        int outW = Math.max(1, Math.round(srcW * outScale));
        int outH = Math.max(1, Math.round(srcH * outScale));
        Bitmap result = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        // postScale 语义为 M' = S·M：先把源矩形左上角平移对齐原点，再等比缩放到目标尺寸
        Matrix drawMatrix = new Matrix();
        drawMatrix.setTranslate(-src.left, -src.top);
        drawMatrix.postScale(outScale, outScale);
        canvas.drawBitmap(bitmap, drawMatrix, null);
        return result;
    }
}
