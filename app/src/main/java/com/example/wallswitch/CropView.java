package com.example.wallswitch;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;

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
    /** 绘制画笔：必须开双线性过滤 —— 传 null 的 Paint 等于最近邻缩放，预览与导出都会明显锯齿。 */
    private final Paint drawPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private float minScale = 1f;
    private float maxScale = 1f;
    // 矩阵是否已按当前位图与视图尺寸初始化过。
    // 这个标记是必需的：resetLayout 一旦在位图/尺寸还没就绪时提前返回，
    // minScale/maxScale 会停在字段初值 1f/1f、matrix 停在单位矩阵，
    // 表现就是「捏合被钳死在 1 倍、拖动挪不动、导出等于原图」且完全无报错。
    private boolean matrixReady = false;
    // 上一次触点坐标（拖动用）
    private float lastX = 0f;
    private float lastY = 0f;
    private final ScaleGestureDetector scaleDetector;
    // 单击判定：区分「轻点」与「拖动/缩放」，轻点用于切换桌面图标预览叠加
    private static final long TAP_MAX_MS = 300L;
    private final int touchSlop;
    private float downX = 0f;
    private float downY = 0f;
    private long downTime = 0L;
    private boolean tapMoved = false;
    private OnTapListener tapListener;

    /** 单击回调（一次没有拖动、没有缩放的轻点）。 */
    public interface OnTapListener {
        void onTap();
    }

    public void setOnTapListener(OnTapListener listener) {
        this.tapListener = listener;
    }

    public CropView(Context context) {
        this(context, null);
    }

    public CropView(Context context, AttributeSet attrs) {
        super(context, attrs);
        ScaleGestureDetector.SimpleOnScaleGestureListener listener = new ScaleListener();
        scaleDetector = new ScaleGestureDetector(context, listener);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    /** 设置待编辑图片并按 fit-cover 重置。 */
    public void setBitmap(Bitmap bitmap) {
        this.bitmap = bitmap;
        matrixReady = false;
        resetLayout();
        // 尺寸可能已经就绪但位图是后到的，主动走一次布局，保证 resetLayout 能拿到有效尺寸
        requestLayout();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        matrixReady = false;
        resetLayout();
    }

    /** fit-cover 初始化：等比放大到覆盖视图并居中，确定缩放范围。 */
    private void resetLayout() {
        if (bitmap == null || getWidth() == 0 || getHeight() == 0) {
            // 条件不满足时明确标记为「未就绪」，交给 onDraw / onTouchEvent 兜底重试
            matrixReady = false;
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
        matrixReady = true;
        invalidate();
    }

    /** 兜底：只要位图与视图尺寸都就绪，就一定把矩阵初始化好（幂等）。 */
    private void ensureMatrixReady() {
        if (!matrixReady) {
            resetLayout();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (bitmap == null) {
            return false;
        }
        // 手势进来时先自查一次，避免首次绘制前触摸导致整页手势失效
        ensureMatrixReady();
        boolean handled = scaleDetector.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = event.getX();
                lastY = event.getY();
                downX = lastX;
                downY = lastY;
                downTime = SystemClock.uptimeMillis();
                tapMoved = false;
                handled = true;
                break;
            case MotionEvent.ACTION_MOVE:
                if (Math.abs(event.getX() - downX) > touchSlop
                        || Math.abs(event.getY() - downY) > touchSlop) {
                    tapMoved = true;
                }
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
            case MotionEvent.ACTION_UP:
                // 轻点（没移动、没缩放、时间够短）→ 通知外层切换预览叠加
                if (!tapMoved && !scaleDetector.isInProgress() && tapListener != null
                        && SystemClock.uptimeMillis() - downTime < TAP_MAX_MS) {
                    tapListener.onTap();
                }
                break;
            default:
                break;
        }
        return handled;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bitmap == null) {
            return;
        }
        // 兜底：不论什么原因导致矩阵没初始化，在真正绘制前补上，
        // 否则会出现「图片不铺满 + 捏合无反应 + 导出等于原图」这种静默失效
        ensureMatrixReady();
        canvas.drawBitmap(bitmap, matrix, drawPaint);
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

    /** 当前位图的宽度（解码后尺寸，不是原图尺寸）。 */
    public int getSourceWidth() {
        return bitmap == null ? 0 : bitmap.getWidth();
    }

    /** 当前位图的高度（解码后尺寸，不是原图尺寸）。 */
    public int getSourceHeight() {
        return bitmap == null ? 0 : bitmap.getHeight();
    }

    /**
     * 当前可见区域在<b>当前位图</b>坐标下的矩形。
     * 导出时用它映射回原图坐标去做区域解码，这样放大多少倍都能拿到原图分辨率。
     * 位图未就绪或矩阵不可逆时返回 false。
     */
    public boolean getVisibleSourceRect(RectF out) {
        if (bitmap == null || out == null) {
            return false;
        }
        ensureMatrixReady();
        Matrix inverse = new Matrix();
        if (!matrix.invert(inverse)) {
            return false;
        }
        out.set(0f, 0f, getWidth(), getHeight());
        inverse.mapRect(out);
        return out.intersect(0f, 0f, bitmap.getWidth(), bitmap.getHeight()) && !out.isEmpty();
    }

    /**
     * 按当前手势把可见区域导出为最长边不超过 maxDim 的位图。
     * 可见区域经矩阵逆变换映射回原图坐标，取交集防浮点误差，再缩放绘制到新位图。
     */
    public Bitmap export(int maxDim) {
        if (bitmap == null) {
            return null;
        }
        // 导出前再确认一次矩阵已就绪，保证导出的就是用户当前看到的构图
        ensureMatrixReady();
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
        canvas.drawBitmap(bitmap, drawMatrix, drawPaint);
        return result;
    }
}
