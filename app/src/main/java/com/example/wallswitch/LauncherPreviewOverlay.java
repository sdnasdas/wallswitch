package com.example.wallswitch;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * 「桌面图标预览」浮层：在裁剪页的取景框上叠加「首页会占掉哪些区域」，用来判断构图主体
 * 会不会被桌面图标挡住。两种模式：
 *
 * <ul>
 *   <li>{@link #MODE_MOCK}：内置示意网格 —— 不需要任何准备，但只是近似。</li>
 *   <li>{@link #MODE_SCREENSHOT}：叠加用户自己选的首页截图 —— 完全准确。
 *       截图本身就是同比例整屏，按 centerCrop 铺满视图后与取景框像素对齐，
 *       所以「截图里被图标盖住的位置」就是「上屏后被盖住的位置」。</li>
 * </ul>
 *
 * 示意网格的准确度说明：状态栏与 Dock 的纵向位置用真实系统栏 inset 计算（准）；
 * 列数按实测的 4 列（格宽 = 屏宽/4，图标格内居中）；行数由可用高度推算。
 * 真实首页顶部往往还有时钟小组件、底部还有 Dock，所以只是示意 —— 要精确就用截图模式。
 *
 * 本 View 不可点击，不会拦截触摸，裁剪区的捏合/拖动照常可用。
 */
public class LauncherPreviewOverlay extends View {

    /** 示意网格模式。 */
    public static final int MODE_MOCK = 0;
    /** 用户首页截图模式。 */
    public static final int MODE_SCREENSHOT = 1;

    /** 实测列数：荣耀 MagicOS 首页为 4 列（格宽 = 屏宽/4，图标在格内居中）。 */
    private static final int COLUMNS = 4;
    /** Dock 栏图标个数。 */
    private static final int DOCK_ITEMS = 4;
    /** 首页截图叠加时的不透明度（够看清图标，又能看到壁纸本身）。 */
    private static final int SCREENSHOT_ALPHA = 150;

    private final Paint iconFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bandFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF rect = new RectF();
    private final Rect dst = new Rect();

    private int mode = MODE_MOCK;
    private Bitmap screenshot;
    private int statusBarInset;
    private int navBarInset;

    public LauncherPreviewOverlay(Context context) {
        this(context, null);
    }

    public LauncherPreviewOverlay(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public LauncherPreviewOverlay(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        float density = getResources().getDisplayMetrics().density;
        // 半透明白 + 深色描边：在任何明暗照片上都看得清
        iconFill.setColor(0x40FFFFFF);
        iconStroke.setColor(0x99000000);
        iconStroke.setStyle(Paint.Style.STROKE);
        iconStroke.setStrokeWidth(density);
        bandFill.setColor(0x2EFFFFFF);
        imagePaint.setAlpha(SCREENSHOT_ALPHA);
    }

    /** 传入真实系统栏高度，保证状态栏与 Dock 的纵向位置与实际上屏一致。 */
    public void setInsets(int statusBar, int navBar) {
        statusBarInset = statusBar;
        navBarInset = navBar;
        invalidate();
    }

    public void setMode(int newMode) {
        mode = newMode;
        invalidate();
    }

    public int getMode() {
        return mode;
    }

    /** 设置用户自己的首页截图（MODE_SCREENSHOT 下生效）；传 null 表示清除。 */
    public void setScreenshot(Bitmap bitmap) {
        screenshot = bitmap;
        invalidate();
    }

    public boolean hasScreenshot() {
        return screenshot != null;
    }

    /** 图标边长：优先用 Launcher 偏好尺寸，并保证不超过格宽的 60%。 */
    private int iconSizePx() {
        ActivityManager am = (ActivityManager) getContext().getSystemService(Context.ACTIVITY_SERVICE);
        int size = am == null ? 0 : am.getLauncherLargeIconSize();
        if (size <= 0) {
            size = (int) (48 * getResources().getDisplayMetrics().density);
        }
        return size;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        if (mode == MODE_SCREENSHOT && screenshot != null && !screenshot.isRecycled()) {
            drawScreenshot(canvas, w, h);
            return;
        }
        drawMock(canvas, w, h);
    }

    /** 首页截图：centerCrop 铺满取景框（截图与取景框同为整屏同比例，故像素对齐）。 */
    private void drawScreenshot(Canvas canvas, int w, int h) {
        float scale = Math.max(w / (float) screenshot.getWidth(), h / (float) screenshot.getHeight());
        int dw = Math.round(screenshot.getWidth() * scale);
        int dh = Math.round(screenshot.getHeight() * scale);
        int left = (w - dw) / 2;
        int top = (h - dh) / 2;
        dst.set(left, top, left + dw, top + dh);
        canvas.drawBitmap(screenshot, null, dst, imagePaint);
    }

    /** 内置示意网格。 */
    private void drawMock(Canvas canvas, int w, int h) {
        float density = getResources().getDisplayMetrics().density;
        int cell = w / COLUMNS;
        int icon = Math.min(iconSizePx(), (int) (cell * 0.6f));
        float side = 0f;
        float labelHeight = 5 * density;
        float rowHeight = icon + labelHeight + 18 * density;
        float dockHeight = icon + 20 * density;
        float top = statusBarInset + 6 * density;
        float bottom = h - navBarInset - dockHeight - 6 * density;

        // 图标格：4 列，图标在每格内居中；下方一条名称占位
        for (float cy = top; cy + icon + labelHeight <= bottom; cy += rowHeight) {
            for (int c = 0; c < COLUMNS; c++) {
                float cx = side + cell * c + (cell - icon) / 2f;
                rect.set(cx, cy, cx + icon, cy + icon);
                canvas.drawRoundRect(rect, icon * 0.28f, icon * 0.28f, iconFill);
                canvas.drawRoundRect(rect, icon * 0.28f, icon * 0.28f, iconStroke);
                float lw = icon * 0.5f;
                rect.set(cx + (icon - lw) / 2f, cy + icon + 4 * density,
                        cx + (icon + lw) / 2f, cy + icon + 4 * density + labelHeight);
                canvas.drawRoundRect(rect, labelHeight / 2f, labelHeight / 2f, iconFill);
            }
        }

        // Dock 栏
        float dockTop = h - navBarInset - dockHeight;
        rect.set(side, dockTop, w - side, dockTop + dockHeight);
        canvas.drawRoundRect(rect, 24 * density, 24 * density, iconFill);
        canvas.drawRoundRect(rect, 24 * density, 24 * density, iconStroke);
        float dockCell = (w - side * 2) / DOCK_ITEMS;
        for (int i = 0; i < DOCK_ITEMS; i++) {
            float cx = side + dockCell * i + (dockCell - icon) / 2f;
            float cy = dockTop + (dockHeight - icon) / 2f;
            rect.set(cx, cy, cx + icon, cy + icon);
            canvas.drawRoundRect(rect, icon * 0.28f, icon * 0.28f, iconStroke);
        }

        // 状态栏
        rect.set(0, 0, w, statusBarInset);
        canvas.drawRect(rect, bandFill);
    }
}
