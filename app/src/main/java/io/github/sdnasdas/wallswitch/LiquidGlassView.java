package io.github.sdnasdas.wallswitch;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RecordingCanvas;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;

import com.google.android.material.navigation.NavigationBarView;

/**
 * 胶囊底栏的液态玻璃：轻模糊 + 边缘透镜折射 + 白色高光边（Android 13+）；
 * 选中项是一块放大下方内容的玻璃，切换时滑动拉伸。低版本退回不透明的纯色胶囊。
 */
public class LiquidGlassView extends FrameLayout {

    private static final String TAG = "LiquidGlass";
    // 折射一律向胶囊内侧取样：系统只给可见区域出图层，往外取到的是空白，会画成一圈黑边。
    private static final String SHADER =
            "uniform shader content;\n"
            + "uniform float2 size;\n"
            + "uniform float radius;\n"
            + "uniform float band;\n"
            + "uniform float strength;\n"
            + "uniform float4 tint;\n"
            + "uniform float highlight;\n"
            + "uniform float shade;\n"
            + "uniform float4 lens;\n"
            + "uniform float lensMag;\n"
            + "float roundRect(float2 p, float2 h, float r) {\n"
            + "    float2 q = abs(p) - h + r;\n"
            + "    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;\n"
            + "}\n"
            + "half4 main(float2 coord) {\n"
            + "    float2 h = size * 0.5;\n"
            + "    float2 p = coord - h;\n"
            + "    float d = roundRect(p, h, radius);\n"
            + "    if (d > 0.0) {\n"
            + "        return content.eval(coord);\n"
            + "    }\n"
            + "    float2 n = float2(\n"
            + "        roundRect(p + float2(1.0, 0.0), h, radius) - roundRect(p - float2(1.0, 0.0), h, radius),\n"
            + "        roundRect(p + float2(0.0, 1.0), h, radius) - roundRect(p - float2(0.0, 1.0), h, radius));\n"
            + "    n = n / max(length(n), 0.0001);\n"
            + "    float edge = 1.0 - clamp(-d / band, 0.0, 1.0);\n"
            + "    edge = edge * edge;\n"
            + "    float2 src = coord - n * edge * strength;\n"
            + "    float2 lh = (lens.zw - lens.xy) * 0.5;\n"
            + "    float2 lc = lens.xy + lh;\n"
            + "    float lr = max(min(lh.x, lh.y), 1.0);\n"
            + "    float ld = roundRect(coord - lc, lh, min(lh.x, lh.y));\n"
            + "    if (ld < 0.0) {\n"
            + "        float k = clamp(-ld / lr, 0.0, 1.0);\n"
            + "        src = lc + (src - lc) * (1.0 - lensMag * (0.4 + 0.6 * k));\n"
            + "    }\n"
            + "    src = clamp(src, float2(0.5), size - 0.5);\n"
            + "    float4 c = float4(content.eval(src));\n"
            + "    c.rgb = mix(c.rgb, tint.rgb, tint.a);\n"
            + "    float line = 1.0 - smoothstep(0.0, 1.6, -d);\n"
            + "    float lit = clamp(dot(n, float2(-0.35, -0.94)), 0.0, 1.0);\n"
            + "    float back = clamp(dot(n, float2(0.35, 0.94)), 0.0, 1.0);\n"
            + "    c.rgb += highlight * (line * (0.55 + 0.45 * lit) + 0.15 * edge * lit);\n"
            + "    c.rgb -= shade * edge * back;\n"
            + "    return half4(half3(clamp(c.rgb, 0.0, 1.0)), 1.0);\n"
            + "}\n";

    private final Paint fallback = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fallbackRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final RectF pillRect = new RectF();
    private final float[] appliedLens = {Float.NaN, 0f, 0f, 0f, 0f};
    private final int[] here = new int[2];
    private final int[] there = new int[2];
    private GlassSource source;
    private RenderNode glassNode;
    private RuntimeShader shader;
    private RenderEffect blur;
    private int target = -1;
    private float fromX;
    private float shownX = Float.NaN;
    private float progress = 1f;
    private float stretch;
    private ValueAnimator slide;

    public LiquidGlassView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), view.getHeight() / 2f);
            }
        });
        setClipToOutline(true);
        fallback.setColor(context.getColor(R.color.nav_glass_fallback));
        fallbackRim.setStyle(Paint.Style.STROKE);
        fallbackRim.setStrokeWidth(dp(1));
        fallbackRim.setColor(context.getColor(R.color.nav_glass_fallback_rim));
        pill.setColor(context.getColor(R.color.nav_glass_pill));
    }

    void setSource(GlassSource source) {
        this.source = source;
        source.setListener(this);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        buildGlass(w, h);
    }

    private void buildGlass(int w, int h) {
        shader = null;
        appliedLens[0] = Float.NaN;
        if (Build.VERSION.SDK_INT < 33 || w == 0 || h == 0) {
            return;
        }
        Context c = getContext();
        int tint = c.getColor(R.color.nav_glass_tint);
        try {
            RuntimeShader s = new RuntimeShader(SHADER);
            s.setFloatUniform("size", w, h);
            s.setFloatUniform("radius", h / 2f);
            s.setFloatUniform("band", dp(14));
            s.setFloatUniform("strength", dp(8));
            s.setFloatUniform("tint", Color.red(tint) / 255f, Color.green(tint) / 255f,
                    Color.blue(tint) / 255f, Color.alpha(tint) / 255f);
            s.setFloatUniform("highlight", Color.alpha(c.getColor(R.color.nav_glass_highlight)) / 255f);
            s.setFloatUniform("shade", Color.alpha(c.getColor(R.color.nav_glass_shade)) / 255f);
            s.setFloatUniform("lens", 0f, 0f, 0f, 0f);
            s.setFloatUniform("lensMag", 0f);
            float radius = dp(8);
            blur = RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP);
            if (glassNode == null) {
                glassNode = new RenderNode("liquid-glass");
            }
            shader = s;
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "glass shader unavailable", e);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        boolean hasPill = updatePill(h);
        if (!drawGlass(canvas, w, h, hasPill)) {
            float inset = dp(1) / 2f;
            rect.set(inset, inset, w - inset, h - inset);
            canvas.drawRoundRect(rect, h / 2f, h / 2f, fallback);
            canvas.drawRoundRect(rect, h / 2f, h / 2f, fallbackRim);
        }
        if (hasPill) {
            float r = pillRect.height() / 2f;
            canvas.drawRoundRect(pillRect, r, r, pill);
        }
    }

    private boolean drawGlass(Canvas canvas, int w, int h, boolean hasPill) {
        if (Build.VERSION.SDK_INT < 33 || shader == null || source == null || source.node() == null
                || !canvas.isHardwareAccelerated()) {
            return false;
        }
        float mag = hasPill ? 0.05f + 0.2f * stretch : 0f;
        float l = hasPill ? pillRect.left : 0f;
        float t = hasPill ? pillRect.top : 0f;
        float r = hasPill ? pillRect.right : 0f;
        float b = hasPill ? pillRect.bottom : 0f;
        if (appliedLens[0] != l || appliedLens[1] != t || appliedLens[2] != r
                || appliedLens[3] != b || appliedLens[4] != mag) {
            shader.setFloatUniform("lens", l, t, r, b);
            shader.setFloatUniform("lensMag", mag);
            glassNode.setRenderEffect(RenderEffect.createChainEffect(
                    RenderEffect.createRuntimeShaderEffect(shader, "content"), blur));
            appliedLens[0] = l;
            appliedLens[1] = t;
            appliedLens[2] = r;
            appliedLens[3] = b;
            appliedLens[4] = mag;
        }
        getLocationInWindow(here);
        source.getLocationInWindow(there);
        glassNode.setPosition(0, 0, w, h);
        RecordingCanvas recording = glassNode.beginRecording();
        recording.drawColor(getContext().getColor(R.color.page_bg));
        recording.translate(there[0] - here[0], there[1] - here[1]);
        recording.drawRenderNode(source.node());
        glassNode.endRecording();
        canvas.drawRenderNode(glassNode);
        return true;
    }

    /** 算出选中玻璃块这一帧的位置，选中项变了就起滑动动画。没有可指的项返回 false。 */
    private boolean updatePill(int h) {
        if (getChildCount() == 0 || !(getChildAt(0) instanceof NavigationBarView nav)
                || !(nav.getChildAt(0) instanceof ViewGroup items)) {
            return false;
        }
        int checked = -1;
        Menu menu = nav.getMenu();
        for (int i = 0; i < menu.size(); i++) {
            if (menu.getItem(i).isChecked()) {
                checked = i;
                break;
            }
        }
        if (checked < 0 || checked >= items.getChildCount()) {
            return false;
        }
        View item = items.getChildAt(checked);
        if (item.getWidth() == 0) {
            return false;
        }
        float center = nav.getLeft() + items.getLeft() + item.getLeft() + item.getWidth() / 2f;
        if (checked != target) {
            target = checked;
            if (Float.isNaN(shownX) || !ValueAnimator.areAnimatorsEnabled()) {
                fromX = center;
                progress = 1f;
            } else {
                fromX = shownX;
                startSlide();
            }
        }
        float x = fromX + (center - fromX) * progress;
        shownX = x;
        float width = (item.getWidth() - dp(12)) * (1f + 0.3f * stretch);
        float height = Math.min(h - dp(2), (h - dp(8)) * (1f + 0.1f * stretch));
        pillRect.set(x - width / 2f, (h - height) / 2f, x + width / 2f, (h + height) / 2f);
        return true;
    }

    private void startSlide() {
        if (slide != null) {
            slide.cancel();
        }
        progress = 0f;
        slide = ValueAnimator.ofFloat(0f, 1f);
        slide.setDuration(380);
        slide.setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f));
        slide.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            stretch = (float) Math.sin(Math.PI * a.getAnimatedFraction());
            invalidate();
        });
        slide.start();
    }

    // 选中态变化只让底栏子项重录，这里接住后重画指示块。
    @Override
    public void onDescendantInvalidated(View child, View target) {
        super.onDescendantInvalidated(child, target);
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (slide != null) {
            slide.cancel();
            slide = null;
        }
        progress = 1f;
        stretch = 0f;
        super.onDetachedFromWindow();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
