package com.example.wallswitch.gl;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.os.SystemClock;

import com.example.wallswitch.WallSwitchService;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.atomic.AtomicLong;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * GL 壁纸渲染器（Muzei 思路的简化版）：一张纹理画满屏（centerCrop），过渡动画由 GPU 逐帧混合。
 *
 * 线程模型：所有 GL 调用都发生在 GL 线程（GLWallpaperService 的 GLThread 保证），
 * setImage/setVisible 由引擎在其它线程调用——setImage 必须经 GLEngine.queueEvent 进入 GL 线程，
 * setVisible 只写 volatile 标志并发渲染请求，任意线程可调。
 *
 * 纹理生命周期：onSurfaceCreated 会让全部纹理失效（GL 惯例，不区分上下文是否真的重建），
 * 此处清空 id 并触发 replayHook，由引擎按"当前壁纸 key"重放上传（等价 Muzei 的
 * queuedImageLoader 重放）。CPU 侧位图始终由引擎持有，本类上传后不回收调用方的 current 图，
 * 仅 setImage 返回 true 时由调用方回收一次性的 blurred 图。
 */
public class WallpaperRenderer implements GLSurfaceView.Renderer {

    private static final int MODE_NONE = 0;
    private static final int MODE_FADE = 1;
    private static final int MODE_BLUR = 2;
    private static final long FADE_MS = 500L;
    private static final long BLUR_MS = 700L;
    /** 模糊过渡中"旧图变糊"阶段占比，其余为"糊图上浮现新图"（与旧 Canvas 版观感一致）。 */
    private static final float BLUR_PHASE_SPLIT = 0.4f;

    private static final String VERTEX_SHADER =
            "attribute vec2 aCorner;\n"
            + "uniform vec2 uTexSpan;\n"
            + "varying vec2 vTexCoord;\n"
            + "void main() {\n"
            + "  gl_Position = vec4(aCorner, 0.0, 1.0);\n"
            // 位图经 texImage2D 上传后上下颠倒（首行在 v=0），此处一并翻转：
            // 屏幕底边（aCorner.y=-1）取 v 靠 1（位图末行），中心裁剪由 uTexSpan 控制
            + "  vTexCoord = vec2(0.5 + aCorner.x * 0.5 * uTexSpan.x,\n"
            + "                    0.5 - aCorner.y * 0.5 * uTexSpan.y);\n"
            + "}\n";

    private static final String FRAGMENT_SHADER =
            "precision mediump float;\n"
            + "uniform sampler2D uTexture;\n"
            + "uniform float uAlpha;\n"
            + "varying vec2 vTexCoord;\n"
            + "void main() {\n"
            + "  gl_FragColor = texture2D(uTexture, vTexCoord) * uAlpha;\n"
            + "}\n";

    private final Context appContext;
    /** 请求画一帧（RENDERMODE_WHEN_DIRTY 下的脏标记），由引擎提供（GLEngine::requestRender）。 */
    private final Runnable requestRenderHook;
    /** 纹理全部失效后由引擎按当前 key 重放上传。 */
    private final Runnable replayHook;

    private int program;
    private int aCornerLoc;
    private int uTexSpanLoc;
    private int uAlphaLoc;
    private int uTextureLoc;
    private final FloatBuffer quadBuf;

    private int currentTex;
    private int nextTex;
    private int blurTex;
    private float currentAspect = 1f;
    private float nextAspect = 1f;
    private float blurAspect = 1f;
    private int viewportW;
    private int viewportH;
    private volatile boolean visible;
    private int mode = MODE_NONE;
    private long transitionStart;
    private long transitionDuration;

    public WallpaperRenderer(Context appContext, Runnable requestRenderHook, Runnable replayHook) {
        this.appContext = appContext;
        this.requestRenderHook = requestRenderHook;
        this.replayHook = replayHook;
        quadBuf = ByteBuffer.allocateDirect(4 * 2 * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        // TRIANGLE_STRIP 四角：(-1,-1) (1,-1) (-1,1) (1,1)，铺满裁剪空间，裁剪靠纹理坐标
        quadBuf.put(new float[]{-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f});
        quadBuf.position(0);
    }

    /**
     * 上传新图并按需启动过渡。返回 false 表示当前不可见、已跳过
     * （引擎会在恢复可见时经 replayHook 重放，调用方据此决定 blurred 图的回收责任）。
     */
    public boolean setImage(Bitmap bmp, Bitmap blurred, boolean animate) {
        if (!visible || bmp == null || bmp.isRecycled()) {
            return false;
        }
        if (animate && currentTex != 0) {
            // 过渡期间旧画面就是 currentTex，无需 CPU 侧旧图
            deleteTex(nextTex);
            nextTex = upload(bmp);
            nextAspect = aspectOf(bmp);
            if (blurred != null && !blurred.isRecycled()) {
                deleteTex(blurTex);
                blurTex = upload(blurred);
                blurAspect = aspectOf(blurred);
                mode = MODE_BLUR;
                transitionDuration = BLUR_MS;
            } else {
                mode = MODE_FADE;
                transitionDuration = FADE_MS;
            }
            transitionStart = SystemClock.elapsedRealtime();
        } else {
            // 非过渡替换（首次显示/恢复重放）：丢掉可能残留的过渡状态
            deleteTex(nextTex);
            nextTex = 0;
            deleteTex(blurTex);
            blurTex = 0;
            deleteTex(currentTex);
            currentTex = upload(bmp);
            currentAspect = aspectOf(bmp);
            mode = MODE_NONE;
        }
        requestRenderHook.run();
        return true;
    }

    /** 任意线程可调：不可见期间 GLThread 本就暂停，这里只挡住恢复可见后的多余请求。 */
    public void setVisible(boolean v) {
        visible = v;
        if (v && currentTex != 0) {
            requestRenderHook.run();
        }
    }

    /** 引擎 onDestroy 时调用（GL 线程）：删纹理与程序。 */
    public void release() {
        deleteTex(currentTex);
        currentTex = 0;
        deleteTex(nextTex);
        nextTex = 0;
        deleteTex(blurTex);
        blurTex = 0;
        if (program != 0) {
            GLES20.glDeleteProgram(program);
            program = 0;
        }
    }

    /** 故障画面：清成纯底色（与 App 背景一致），丢弃旧纹理防止"过期图"残留。 */
    public void clearToBackground() {
        if (!visible) {
            return;
        }
        deleteTex(currentTex);
        currentTex = 0;
        deleteTex(nextTex);
        nextTex = 0;
        deleteTex(blurTex);
        blurTex = 0;
        mode = MODE_NONE;
        requestRenderHook.run();
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        try {
            // 纹理与着色器程序随上下文一起失效：全部清掉，引擎经 replayHook 重放上传
            deleteTex(currentTex);
            currentTex = 0;
            deleteTex(nextTex);
            nextTex = 0;
            deleteTex(blurTex);
            blurTex = 0;
            mode = MODE_NONE;
            buildProgram();
            replayHook.run();
        } catch (Throwable t) {
            reportFailure(t);
        }
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        try {
            viewportW = width;
            viewportH = height;
            GLES20.glViewport(0, 0, width, height);
            requestRenderHook.run();
        } catch (Throwable t) {
            reportFailure(t);
        }
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        try {
            drawFrame();
        } catch (Throwable t) {
            reportFailure(t);
        }
    }

    private void drawFrame() {
        WallSwitchService.perfFrames.incrementAndGet();
        GLES20.glClearColor(0x1A / 255f, 0x1A / 255f, 0x1A / 255f, 1f);
        GLES20.glClear(GL10.GL_COLOR_BUFFER_BIT);
        if (currentTex == 0) {
            return;
        }
        GLES20.glUseProgram(program);
        quadBuf.position(0);
        GLES20.glVertexAttribPointer(aCornerLoc, 2, GL10.GL_FLOAT, false, 8, quadBuf);
        GLES20.glEnableVertexAttribArray(aCornerLoc);
        GLES20.glUniform1i(uTextureLoc, 0);
        GLES20.glActiveTexture(GL10.GL_TEXTURE0);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GL10.GL_SRC_ALPHA, GL10.GL_ONE_MINUS_SRC_ALPHA);

        if (mode != MODE_NONE) {
            float t = (SystemClock.elapsedRealtime() - transitionStart) / (float) transitionDuration;
            if (t >= 1f) {
                // 新图扶正：删旧 current 与模糊图，停掉重复渲染请求
                deleteTex(currentTex);
                deleteTex(blurTex);
                blurTex = 0;
                currentTex = nextTex;
                nextTex = 0;
                currentAspect = nextAspect;
                mode = MODE_NONE;
                drawTex(currentTex, currentAspect, 1f);
                return;
            }
            if (mode == MODE_BLUR) {
                if (t < BLUR_PHASE_SPLIT) {
                    drawTex(currentTex, currentAspect, 1f);
                    drawTex(blurTex, blurAspect, t / BLUR_PHASE_SPLIT);
                } else {
                    drawTex(blurTex, blurAspect, 1f);
                    float tail = (t - BLUR_PHASE_SPLIT) / (1f - BLUR_PHASE_SPLIT);
                    drawTex(nextTex, nextAspect, tail);
                }
            } else {
                drawTex(currentTex, currentAspect, 1f);
                drawTex(nextTex, nextAspect, t);
            }
            // 过渡未结束：持续请求下一帧（进度由时间戳算出，不 sleep）
            requestRenderHook.run();
            return;
        }
        drawTex(currentTex, currentAspect, 1f);
    }

    /** 画一张纹理铺满视口（centerCrop：按宽高比裁掉超出部分，alpha 混合）。 */
    private void drawTex(int tex, float imageAspect, float alpha) {
        if (tex == 0 || viewportW <= 0 || viewportH <= 0) {
            return;
        }
        float vpAspect = viewportW / (float) viewportH;
        float uSpan = Math.min(1f, vpAspect / imageAspect);
        float vSpan = Math.min(1f, imageAspect / vpAspect);
        GLES20.glUniform2f(uTexSpanLoc, uSpan, vSpan);
        GLES20.glUniform1f(uAlphaLoc, alpha);
        GLES20.glBindTexture(GL10.GL_TEXTURE_2D, tex);
        GLES20.glDrawArrays(GL10.GL_TRIANGLE_STRIP, 0, 4);
    }

    private int upload(Bitmap bmp) {
        int[] ids = new int[1];
        GLES20.glGenTextures(1, ids, 0);
        int tex = ids[0];
        GLES20.glBindTexture(GL10.GL_TEXTURE_2D, tex);
        GLES20.glTexParameteri(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_MIN_FILTER, GL10.GL_LINEAR);
        GLES20.glTexParameteri(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_MAG_FILTER, GL10.GL_LINEAR);
        GLES20.glTexParameteri(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_WRAP_S, GL10.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_WRAP_T, GL10.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GL10.GL_TEXTURE_2D, 0, bmp, 0);
        WallSwitchService.perfUploads.incrementAndGet();
        return tex;
    }

    private void buildProgram() {
        if (program != 0) {
            GLES20.glDeleteProgram(program);
            program = 0;
        }
        int vs = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
        int fs = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
        program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vs);
        GLES20.glAttachShader(program, fs);
        GLES20.glLinkProgram(program);
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
        int[] status = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0);
        if (status[0] == 0) {
            String log = GLES20.glGetProgramInfoLog(program);
            GLES20.glDeleteProgram(program);
            program = 0;
            throw new IllegalStateException("shader link failed: " + log);
        }
        aCornerLoc = GLES20.glGetAttribLocation(program, "aCorner");
        uTexSpanLoc = GLES20.glGetUniformLocation(program, "uTexSpan");
        uAlphaLoc = GLES20.glGetUniformLocation(program, "uAlpha");
        uTextureLoc = GLES20.glGetUniformLocation(program, "uTexture");
    }

    private static int compileShader(int type, String src) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, src);
        GLES20.glCompileShader(shader);
        int[] status = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new IllegalStateException("shader compile failed: " + log);
        }
        return shader;
    }

    private static void deleteTex(int tex) {
        if (tex != 0) {
            GLES20.glDeleteTextures(1, new int[]{tex}, 0);
        }
    }

    private static float aspectOf(Bitmap bmp) {
        return bmp.getHeight() > 0 ? bmp.getWidth() / (float) bmp.getHeight() : 1f;
    }

    private void reportFailure(Throwable t) {
        WallSwitchService.onRendererFailure(appContext, t);
    }
}
