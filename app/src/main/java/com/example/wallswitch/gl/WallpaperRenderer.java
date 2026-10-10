package com.example.wallswitch.gl;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.os.SystemClock;

import com.example.wallswitch.WallSwitchService;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.atomic.AtomicLong;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * GL 壁纸渲染器（Muzei 思路的简化版）：一张纹理画满屏（centerCrop），过渡动画由 GPU 逐帧混合。
 *
 * <p>实况（动态照片）：本类另管一条「外部纹理」通道 —— 一块
 * {@code GL_TEXTURE_EXTERNAL_OES} 纹理 + 一套 {@code samplerExternalOES} 着色器程序，
 * 帧由 {@link MotionPlayer} 的硬解线程推进。静态那套 program 与顶点着色器**一行未改**，
 * 实况只在其之上多一条互斥的绘制分支：
 * <ul>
 *   <li>播实况期间静态纹理照常保留、不删，停播立刻回到静帧；</li>
 *   <li>{@link #setImage} 与 {@link #clearToBackground} 一被调用就先停实况 ——
 *       两套画面抢同一帧缓冲没有意义，谁最后被设置谁赢；</li>
 *   <li>没有新帧时直接返回，不清屏也不重画，静止时仍是零渲染。</li>
 * </ul>
 *
 * 线程模型：所有 GL 调用都发生在 GL 线程（GLWallpaperService 的 GLThread 保证），
 * setImage/setVisible/startMotion/stopMotion 由引擎在其它线程调用——需要进 GL 的
 * 必须经 GLEngine.queueEvent，setVisible 只写 volatile 标志并发渲染请求，任意线程可调。
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

    /**
     * 实况那套顶点着色器：先按 center/span 取到「内容空间」坐标（v 从底部起算，
     * 与 SurfaceTexture 变换矩阵的输入空间一致，所以这里**不做**位图那条路的 y 翻转），
     * 再交给矩阵映射到真正的采样坐标 —— 朝向由矩阵给，不靠猜设备行为。
     */
    private static final String MOTION_VERTEX_SHADER =
            "attribute vec2 aCorner;\n"
            + "uniform mat4 uTexMatrix;\n"
            + "uniform vec2 uTexCenter;\n"
            + "uniform vec2 uTexSpan;\n"
            + "varying vec2 vTexCoord;\n"
            + "void main() {\n"
            + "  gl_Position = vec4(aCorner, 0.0, 1.0);\n"
            + "  vec2 content = uTexCenter + aCorner * 0.5 * uTexSpan;\n"
            + "  vTexCoord = vec2(uTexMatrix * vec4(content, 0.0, 1.0)).xy;\n"
            + "}\n";

    /** 外部纹理必须用 samplerExternalOES，且着色器要显式 require 那个扩展。 */
    private static final String MOTION_FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n"
            + "precision mediump float;\n"
            + "uniform samplerExternalOES uTexture;\n"
            + "varying vec2 vTexCoord;\n"
            + "void main() {\n"
            + "  gl_FragColor = texture2D(uTexture, vTexCoord);\n"
            + "}\n";

    /** 实况纹理单独占一个纹理单元，避免和静态纹理（单元 0）互相顶掉绑定。 */
    private static final int MOTION_TEXTURE_UNIT = 1;

    private final Context appContext;
    /** 请求画一帧（RENDERMODE_WHEN_DIRTY 下的脏标记），由引擎提供（GLEngine::requestRender）。 */
    private final Runnable requestRenderHook;
    /** 纹理全部失效后由引擎按当前 key 重放上传。 */
    private final Runnable replayHook;
    /**
     * 一轮实况**自然播完**时回调（在 GL 线程上调用，引擎自己切回主线程排下一轮）。
     * 渲染器不管间隔多久、也不管该不该再来一轮 —— 那是引擎的事，
     * 因为停播/离开桌面/改设置都要能取消还没到的那一轮。
     */
    private final Runnable motionCycleHook;

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

    // ---- 实况通道：以下字段只在 GL 线程读写（入口一律经 queueEvent 进来） ----------------
    /** 正在播的实况；null = 没在播。 */
    private MotionPlayer motionPlayer;
    /**
     * 画面是否已归实况独占 —— 只在**画成第一帧视频之后**才置真。
     * 没置真之前静态图必须继续画，否则起播到第一帧到手那 100~200ms 里表面是空的，
     * 会露出底下的系统静态壁纸（详见 {@link #drawFrame()}）。
     */
    private boolean motionExclusive;
    /** 循环档要重开一轮用的那段区间（{@code finishMotion} 不清它，{@code stopMotion} 清）。 */
    private File motionFile;
    private long motionOffset;
    private long motionLength;
    /** 用户要循环：每播完一轮就重开一轮。停播/不可见即置假。 */
    private boolean motionLoopWanted;
    /** 连续「一帧都没出」的轮数：挡住起播就失败时的无限重开打转。 */
    private int motionZeroCycles;
    private int motionProgram;
    private int motionACornerLoc;
    private int motionUTexMatrixLoc;
    private int motionUTexCenterLoc;
    private int motionUTexSpanLoc;
    private int motionUTexLoc;
    /** 归一化取景框 {left, top, right, bottom}（v 从顶部起算，与原图裁剪矩形同一套），null = 整帧。 */
    private float[] motionRect;
    private final float[] motionMatrix = new float[16];
    /**
     * 取景裁剪系数的缓存：一帧一次换算 = 每秒 60 次分配 float[4]，
     * 而它只跟「取景框 + 帧尺寸 + 视口尺寸」有关，一轮播放里根本不变，所以算一次用到底。
     */
    private float[] motionCrop;
    private float[] motionCropRectRef;
    private int motionCropFrameW;
    private int motionCropFrameH;
    private int motionCropVw;
    private int motionCropVh;
    /** 本次已在播的「文件#区间」，用来挡住重复的起播请求（按住时手指抖动会连发 DOWN）。 */
    private String motionKey;

    public WallpaperRenderer(Context appContext, Runnable requestRenderHook, Runnable replayHook,
                             Runnable motionCycleHook) {
        this.appContext = appContext;
        this.requestRenderHook = requestRenderHook;
        this.replayHook = replayHook;
        this.motionCycleHook = motionCycleHook;
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
        // 静态画面一旦被设置，实况就让位：两套画面抢同一帧缓冲没有意义
        stopMotion();
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

    /**
     * 起播某张壁纸的实况段（**GL 线程**，由引擎经 {@code queueEvent} 送进来）。
     *
     * @param rectNorm 归一化取景框（v 从顶部起算），来自原图坐标的裁剪矩形；null = 整帧
     * @param loop     true = 播完一遍自动重开一轮（循环档）
     * @return 是否已在播；同一区间重复请求算「已在播」，不重开解码器
     */
    public boolean startMotion(File file, long offset, long length,
                               float[] rectNorm, boolean loop) {
        if (file == null || offset < 0 || length <= 0) {
            return false;
        }
        String key = file.getName() + '#' + offset + '#' + length;
        if (key.equals(motionKey) && motionPlayer != null && motionPlayer.isAlive()) {
            return true;
        }
        if (!visible) {
            return false;
        }
        stopMotion();
        motionRect = rectNorm;
        motionCrop = null;
        motionCropRectRef = null;
        motionFile = file;
        motionOffset = offset;
        motionLength = length;
        motionLoopWanted = loop;
        motionZeroCycles = 0;
        return beginMotion();
    }

    /**
     * 真的开一轮：新建播放器（= 全新 extractor + 全新 codec）。
     * 循环档的「下一轮」也走这里，所以循环只有一种机制、一条已验过的路。
     */
    private boolean beginMotion() {
        File file = motionFile;
        if (file == null || !visible) {
            return false;
        }
        if (motionProgram == 0 && !buildMotionProgram()) {
            return false;
        }
        MotionPlayer player = new MotionPlayer();
        motionExclusive = false;
        motionKey = file.getName() + '#' + motionOffset + '#' + motionLength;
        if (!player.start(file, motionOffset, motionLength,
                requestRenderHook, this::requestRenderAgain)) {
            player.release();
            motionKey = null;
            WallSwitchService.lastMotionDiag = "起播被拒: " + player.lastError();
            return false;
        }
        motionPlayer = player;
        WallSwitchService.perfMotionStarts.incrementAndGet();
        requestRenderHook.run();
        return true;
    }

    /**
     * 请求停播（**GL 线程**，幂等）。只发停止标志并标脏；解码线程收尾后
     * 会经 {@link #requestRenderAgain} 再画一帧，那一帧里才真正释放资源并回到静帧 ——
     * 谁开的谁关，不在这里跨线程 release codec。
     */
    public void stopMotion() {
        motionKey = null;
        // 立刻把画面交还静态图，不等解码线程真的退出来 —— 它最多多睡一帧（16.7ms）才收尾
        motionExclusive = false;
        // 停播 = 循环的意图也一并取消，否则收尾那一帧会立刻把下一轮开起来
        motionLoopWanted = false;
        motionFile = null;
        motionZeroCycles = 0;
        if (motionPlayer == null) {
            return;
        }
        motionPlayer.stop();
        requestRenderHook.run();
    }

    /** 解码线程退出后（主线程）再标一次脏，保证画面从「最后一帧视频」回到静帧。 */
    private void requestRenderAgain() {
        requestRenderHook.run();
    }

    /** GL 线程：收掉已结束的播放器，把资源释放干净。 */
    private void finishMotion() {
        if (motionPlayer == null) {
            return;
        }
        String err = motionPlayer.lastError();
        if (err != null && !err.isEmpty()) {
            WallSwitchService.lastMotionDiag = err;
        }
        motionPlayer.release();
        motionPlayer = null;
        motionKey = null;
        motionCrop = null;
        motionCropRectRef = null;
        motionExclusive = false;
    }

    /** 上下文失效/销毁时用：连播放器带它自己的外部纹理一起拆掉。 */
    private void dropMotionEverything() {
        if (motionPlayer != null) {
            motionPlayer.stop();
            motionPlayer.release();
            motionPlayer = null;
        }
        motionKey = null;
        motionRect = null;
        motionCrop = null;
        motionCropRectRef = null;
        motionExclusive = false;
        motionLoopWanted = false;
        motionFile = null;
        motionZeroCycles = 0;
    }

    /** 实况那套 program 懒建：没播过实况的设备不必为它花这一次编译。 */
    private boolean buildMotionProgram() {
        try {
            int vs = compileShader(GLES20.GL_VERTEX_SHADER, MOTION_VERTEX_SHADER);
            int fs = compileShader(GLES20.GL_FRAGMENT_SHADER, MOTION_FRAGMENT_SHADER);
            int program = GLES20.glCreateProgram();
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
                WallSwitchService.lastMotionDiag = "实况着色器链接失败: " + log;
                return false;
            }
            motionProgram = program;
            motionACornerLoc = GLES20.glGetAttribLocation(program, "aCorner");
            motionUTexMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix");
            motionUTexCenterLoc = GLES20.glGetUniformLocation(program, "uTexCenter");
            motionUTexSpanLoc = GLES20.glGetUniformLocation(program, "uTexSpan");
            motionUTexLoc = GLES20.glGetUniformLocation(program, "uTexture");
            return true;
        } catch (Throwable t) {
            // compileShader 编译失败是抛异常来的；实况这条链坏掉不该把 GL 线程带崩
            WallSwitchService.lastMotionDiag = "实况着色器编译失败: " + t;
            return false;
        }
    }

    /** 引擎 onDestroy 时调用（GL 线程）：删纹理与程序。 */
    public void release() {
        dropMotionEverything();
        deleteTex(currentTex);
        currentTex = 0;
        deleteTex(nextTex);
        nextTex = 0;
        deleteTex(blurTex);
        blurTex = 0;
        if (motionProgram != 0) {
            GLES20.glDeleteProgram(motionProgram);
            motionProgram = 0;
        }
        if (program != 0) {
            GLES20.glDeleteProgram(program);
            program = 0;
        }
    }

    /** 故障画面：清成纯底色（与 App 背景一致），丢弃旧纹理防止"过期图"残留。 */
    public void clearToBackground() {
        stopMotion();
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
            // 上下文重建：外部纹理与实况那套 program 一起作废，播放器整条拆掉等重播。
            // 此刻新上下文里还没建过任何对象，所以删旧纹理号只是空操作，不会误删刚建的东西。
            dropMotionEverything();
            motionProgram = 0;
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
        MotionPlayer player = motionPlayer;
        if (player != null) {
            if (!player.isAlive()) {
                // 解码线程已收尾（播完 / 被停 / 出错）：先收资源
                boolean showedFrames = player.framesShown() > 0;
                boolean natural = player.endedAtEos();
                finishMotion();
                if (motionLoopWanted && visible && natural) {
                    // 循环档：一轮连一帧都没出就是坏在起播上，
                    // 连着三次还这样别再试了，免得在这儿无限打转
                    motionZeroCycles = showedFrames ? 0 : motionZeroCycles + 1;
                    if (motionZeroCycles > 2) {
                        motionLoopWanted = false;
                        motionFile = null;
                        motionZeroCycles = 0;
                        WallSwitchService.lastMotionDiag = "连续 3 轮一帧都没出，已停止循环重播";
                    } else {
                        // 「下一轮什么时候来」归引擎（要按间隔排，停播/离开桌面还得能撤），
                        // 渲染器只报告「这一轮自然播完了」
                        motionCycleHook.run();
                    }
                }
                // 间隔期间、以及新一轮第一帧到位之前，下面这行一直画静态图（= 封面帧），画面不留空
            } else if (player.consumeNewFrameFlag()) {
                drawMotion();
                motionExclusive = true;
                return;
            } else if (motionExclusive) {
                // 已经在放：没新帧就保持上一帧画面，不清屏也不重画
                return;
            }
            // 起了播但第一帧还没到 → 往下继续画静态图。
            // 这里以前是直接 return，等于把画面空着 100~200ms（解码器出第一帧的延迟）：
            // 表面没内容时合成器会露出底下的系统静态壁纸，而系统里那份只有
            // 静态存档写进去的**锁屏**图（桌面的静态兜底已按要求删掉），
            // 于是真机上表现为「从任何 App 回桌面，闪一下锁屏壁纸，约 1 秒后自己好」。
        }
        drawStatic();
    }

    /** 画一帧实况：外部纹理 + 变换矩阵 + 取景框 centerCrop，铺满视口。 */
    private void drawMotion() {
        MotionPlayer player = motionPlayer;
        if (player == null || motionProgram == 0 || viewportW <= 0 || viewportH <= 0) {
            return;
        }
        float[] cs = motionCrop(player);
        if (cs == null) {
            return;
        }
        // 顺序要紧：updateTexImage 作用在「当前绑到 EXTERNAL_OES 的那块纹理」上，必须先绑再取
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + MOTION_TEXTURE_UNIT);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, player.getTexture());
        player.updateTexImage();
        player.getTransformMatrix(motionMatrix);
        GLES20.glUseProgram(motionProgram);
        quadBuf.position(0);
        GLES20.glVertexAttribPointer(motionACornerLoc, 2, GL10.GL_FLOAT, false, 8, quadBuf);
        GLES20.glEnableVertexAttribArray(motionACornerLoc);
        GLES20.glUniformMatrix4fv(motionUTexMatrixLoc, 1, false, motionMatrix, 0);
        GLES20.glUniform2f(motionUTexCenterLoc, cs[0], cs[1]);
        GLES20.glUniform2f(motionUTexSpanLoc, cs[2], cs[3]);
        GLES20.glUniform1i(motionUTexLoc, MOTION_TEXTURE_UNIT);
        // 实况是 opaque 的一帧，不参与混合：Blend 开着会把底色混进来
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glDrawArrays(GL10.GL_TRIANGLE_STRIP, 0, 4);
        WallSwitchService.perfMotionFrames.incrementAndGet();
    }

    /**
     * 取景裁剪系数：只在「取景框 / 帧尺寸 / 视口尺寸」真的变了才算一次。
     * 每帧现算等于每秒分配 60 个 float[4]，而这些输入在一轮播放里根本不变。
     *
     * @return null = 帧尺寸还没拿到（format 未就绪），这一帧先不画
     */
    private float[] motionCrop(MotionPlayer player) {
        int fw = player.getFrameWidth();
        int fh = player.getFrameHeight();
        if (fw <= 0 || fh <= 0) {
            return null;
        }
        float[] cached = motionCrop;
        if (cached != null && motionCropRectRef == motionRect && motionCropFrameW == fw
                && motionCropFrameH == fh && motionCropVw == viewportW && motionCropVh == viewportH) {
            return cached;
        }
        motionCrop = MotionSource.cropForViewport(motionRect, fw, fh, viewportW / (float) viewportH);
        motionCropRectRef = motionRect;
        motionCropFrameW = fw;
        motionCropFrameH = fh;
        motionCropVw = viewportW;
        motionCropVh = viewportH;
        return motionCrop;
    }

    private void drawStatic() {
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
        // 显式回到单元 0：实况那条路会把当前单元切到 1，绑定是**按单元**的，
        // 不切回来就会把静态纹理建到单元 1 上，而 drawStatic 只认单元 0
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
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
