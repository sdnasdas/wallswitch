package com.example.wallswitch.gl;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.graphics.SurfaceTexture;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import javax.microedition.khronos.opengles.GL10;

/**
 * 实况段播放器：{@code originals/<id>} 里那段 MP4 → 硬解 → 一块外部纹理（OES）。
 *
 * <p>线程模型（三块地方，各管各的，别混）：
 * <ul>
 *   <li><b>GL 线程</b>：构造（要把 SurfaceTexture 绑到已生成的 OES 纹理上）、
 *       {@link #updateTexImage()}、{@link #release()}；</li>
 *   <li><b>解码线程</b>（本类内部那条）：MediaExtractor/MediaCodec 的全部调用，
 *       以及它们自己的 release —— 谁开的谁关，避免跨线程释放竞态；</li>
 *   <li><b>主线程</b>：{@code onFrameAvailable} 回调默认投递到主 Looper，
 *       那里只做「置标志 + 请求画一帧」两件事，不碰 GL、不碰 codec。</li>
 * </ul>
 *
 * <p>为什么不用 {@code MediaPlayer}：它要一个 View/SurfaceHolder 才好控制，且没法只解码不显示；
 * 走 MediaCodec 才能「只挑视频轨」—— 实况段自带一条 AAC 音轨，壁纸出声不可接受，
 * 不选那条轨就从根本上不会响，比起了声再静音干净。
 *
 * <p>发热口径：抽帧节奏由解码线程按 {@code presentationTimeUs} 推给 Surface，
 * GL 侧仍是 {@code RENDERMODE_WHEN_DIRTY}，每来一帧被 {@code requestRender} 标脏一次；
 * 不引定时器、不改 CONTINUOUSLY。停止后解码器整个拆掉（不是暂停），常驻负载归零。
 */
public final class MotionPlayer {

    private static final String TAG = "MotionPlayer";
    /** 单次 dequeue 的等待：太长停不干净，太短空转。 */
    private static final int DEQUEUE_TIMEOUT_US = 10_000;

    private final int oesTextureId;
    private final SurfaceTexture surfaceTexture;
    private final Surface surface;

    private final AtomicBoolean newFrame = new AtomicBoolean();
    private volatile boolean stopRequested;
    private volatile boolean finished = true;
    private volatile String lastError;
    private volatile Runnable finishCallback;
    /** 解码出来的帧实际尺寸：取景框要按它算 centerCrop，0 表示还没拿到 format。 */
    private volatile int frameWidth;
    private volatile int frameHeight;
    /** 只在 GL 线程读写（release 的唯一入口在渲染器的 GL 分支里）。 */
    private boolean textureDeleted;
    private final AtomicLong framesShown = new AtomicLong();
    /** 解码线程退出后回主线程标脏用（GL 的 requestRender 本来就允许任意线程调）。 */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** 解码线程句柄：只在 start/stop 里判活，不做 join（不许阻塞 GL 线程）。 */
    private volatile Thread worker;

    /**
     * 必须在 **GL 线程**构造：自己生成一块外部纹理并把 SurfaceTexture 挂上去
     * （SurfaceTexture 要求建它时纹理已绑在 {@code GL_TEXTURE_EXTERNAL_OES} 目标上）。
     *
     * <p>纹理随本播放器一起生老病死，不由渲染器代管 —— 否则「上一段还在收尾、
     * 下一段已经建好新的 SurfaceTexture」时，两个 SurfaceTexture 会共用同一个纹理号。
     */
    public MotionPlayer() {
        this.oesTextureId = genOesTexture();
        this.surfaceTexture = new SurfaceTexture(oesTextureId);
        this.surface = new Surface(this.surfaceTexture);
    }

    private static int genOesTexture() {
        int[] ids = new int[1];
        GLES20.glGenTextures(1, ids, 0);
        int tex = ids[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GL10.GL_TEXTURE_MIN_FILTER, GL10.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GL10.GL_TEXTURE_MAG_FILTER, GL10.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GL10.GL_TEXTURE_WRAP_S, GL10.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GL10.GL_TEXTURE_WRAP_T, GL10.GL_CLAMP_TO_EDGE);
        return tex;
    }

    public int getTexture() {
        return oesTextureId;
    }

    /** 取走「有新帧」标志（GL 线程调）。 */
    public boolean consumeNewFrameFlag() {
        return newFrame.compareAndSet(true, false);
    }

    public boolean isAlive() {
        return !finished;
    }

    public long framesShown() {
        return framesShown.get();
    }

    public int getFrameWidth() {
        return frameWidth;
    }

    public int getFrameHeight() {
        return frameHeight;
    }

    public String lastError() {
        return lastError;
    }

    /** GL 线程：把最新一帧取到外部纹理上。调用前需自行 bind OES 纹理。 */
    public void updateTexImage() {
        try {
            surfaceTexture.updateTexImage();
        } catch (Throwable t) {
            lastError = "updateTexImage: " + t;
        }
    }

    /** GL 线程：读纹理变换矩阵（视频帧的朝向由它给，不靠猜）。 */
    public void getTransformMatrix(float[] out16) {
        try {
            surfaceTexture.getTransformMatrix(out16);
        } catch (Throwable t) {
            lastError = "getTransformMatrix: " + t;
        }
    }

    /**
     * 起播一段实况，**播完一遍就收尾**（已经在播时重复调用直接返回 false）。
     *
     * <p>本类不管循环：循环由 {@code WallpaperRenderer} 在收到 {@code onFinished} 后
     * 重开一个新播放器来实现。别在这儿加回「EOS → flush → seekTo(0) → start()」那套 ——
     * 真机上它只播一遍就停，原因见 {@link #decodeLoop} 里 EOS 分支那段注释。
     *
     * @param file             原图文件
     * @param offset           实况段起点（{@link MotionSource#locate} 给的）
     * @param length           实况段长度
     * @param onFrameAvailable 来帧回调（投递在主线程，只做标脏）
     * @param onFinished       解码线程彻底退出后的通知（同样投到主线程）。
     *                         没有它会出现「播完后不再有来帧、于是再也没人标脏」的死局：
     *                         画面会永远停在最后一帧视频上，回不到静帧。
     */
    public boolean start(File file, long offset, long length,
                         Runnable onFrameAvailable, Runnable onFinished) {
        if (file == null || offset < 0 || length <= 0 || !finished) {
            return false;
        }
        this.finishCallback = onFinished;
        stopRequested = false;
        finished = false;
        lastError = null;
        frameWidth = 0;
        frameHeight = 0;
        framesShown.set(0);
        surfaceTexture.setOnFrameAvailableListener(st -> {
            newFrame.set(true);
            if (onFrameAvailable != null) {
                onFrameAvailable.run();
            }
        });
        worker = new Thread(() -> decodeLoop(file, offset, length), "engine-motion");
        worker.setDaemon(true);
        worker.start();
        return true;
    }

    /**
     * 请求停止（任意线程，立即返回）。真正的释放动作在解码线程收尾时做，
     * 做完会把 {@link #isAlive()} 变成 false，由 GL 线程在下一帧里调 {@link #release()}。
     */
    public void stop() {
        stopRequested = true;
    }

    /** GL 线程、且必须在 {@link #isAlive()} 为 false 之后调用：释放 Surface 与纹理。 */
    public void release() {
        try {
            surfaceTexture.setOnFrameAvailableListener(null);
        } catch (Throwable ignored) {
        }
        try {
            surfaceTexture.release();
        } catch (Throwable ignored) {
        }
        try {
            surface.release();
        } catch (Throwable ignored) {
        }
        if (!textureDeleted) {
            textureDeleted = true;
            GLES20.glDeleteTextures(1, new int[]{oesTextureId}, 0);
        }
    }

    // ---- 解码线程 ------------------------------------------------------------------------

    private void decodeLoop(File file, long offset, long length) {
        MediaExtractor extractor = null;
        MediaCodec codec = null;
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(file, "r");
            extractor = new MediaExtractor();
            extractor.setDataSource(raf.getFD(), offset, length);
            int track = pickVideoTrack(extractor);
            if (track < 0) {
                lastError = "实况段里没有视频轨";
                return;
            }
            extractor.selectTrack(track);
            MediaFormat format = extractor.getTrackFormat(track);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime == null || !mime.startsWith("video/")) {
                lastError = "轨道类型不是视频: " + mime;
                return;
            }
            int width = optInt(format, MediaFormat.KEY_WIDTH, 0);
            int height = optInt(format, MediaFormat.KEY_HEIGHT, 0);
            frameWidth = width;
            frameHeight = height;
            if (width > 0 && height > 0) {
                surfaceTexture.setDefaultBufferSize(width, height);
            }
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, surface, null, 0);
            codec.start();

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            /**
             * 起播锚点：第一帧真正到手的那一刻。
             * 不能拿「线程启动」当锚 —— 解码器出第一帧本身要 100ms 量级，
             * 用启动时刻的话开头那十几帧的应显示时间全已过期，会先 burst 一下再进正常速度。
             */
            long startNs = 0L;
            boolean inputDone = false;
            while (!stopRequested) {
                if (!inputDone) {
                    int inIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US);
                    if (inIndex >= 0) {
                        ByteBuffer inBuf = codec.getInputBuffer(inIndex);
                        int read = inBuf == null ? -1 : extractor.readSampleData(inBuf, 0);
                        if (read < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(inIndex, 0, read,
                                    extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                int outIndex = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US);
                if (outIndex >= 0) {
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        // 播完一遍就收。循环不在这里做（不 flush + seekTo 回头）——
                        // 真机上那半截不生效，表现是「循环档只播一遍」。改由 GL 线程
                        // 在收到收尾通知后重开一轮，走「全新 extractor + 全新 codec」
                        // 这条已经被证明能用的路。
                        codec.releaseOutputBuffer(outIndex, false);
                        break;
                    }
                    // 节奏必须自己等：SurfaceTexture 没有显示时钟，缓冲区一入队就立刻
                    // onFrameAvailable，releaseOutputBuffer 传时间戳对它**不起作用**
                    // （只有绑在显示硬件上的 SurfaceView Surface 才认）。不等的话 162 帧
                    // 会在半秒内喷完 —— 真机上就是用户说的「速度特别快」。
                    // 这一睡还会把上游 dequeueInputBuffer 反压住，解码不会一路狂跑。
                    if (startNs == 0L) {
                        startNs = System.nanoTime();
                    }
                    long waitNs = startNs + info.presentationTimeUs * 1000L - System.nanoTime();
                    if (waitNs > 0 && !sleepUntil(waitNs)) {
                        break;
                    }
                    codec.releaseOutputBuffer(outIndex, true);
                    framesShown.incrementAndGet();
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat fresh = codec.getOutputFormat();
                    int w = optInt(fresh, MediaFormat.KEY_WIDTH, width);
                    int h = optInt(fresh, MediaFormat.KEY_HEIGHT, height);
                    if (w > 0 && h > 0) {
                        frameWidth = w;
                        frameHeight = h;
                        surfaceTexture.setDefaultBufferSize(w, h);
                    }
                }
            }
        } catch (Throwable t) {
            lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
            Log.w(TAG, "实况解码失败 " + file.getName(), t);
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (Throwable ignored) {
                }
                try {
                    codec.release();
                } catch (Throwable ignored) {
                }
            }
            if (extractor != null) {
                try {
                    extractor.release();
                } catch (Throwable ignored) {
                }
            }
            try {
                if (raf != null) {
                    raf.close();
                }
            } catch (Throwable ignored) {
            }
            stopRequested = true;
            finished = true;
            // 顺序要紧：先置 finished，再标脏，GL 线程下一帧才会走「释放 + 回静帧」那条路
            final Runnable done = finishCallback;
            if (done != null) {
                mainHandler.post(done);
            }
        }
    }

    /**
     * 睡到该帧的应显示时刻。睡完发现已被要求停播就返回 false，让上层立刻收尾 ——
     * 一帧的间隔只有 16.7ms，停播最多多等这一格，不会拖住释放。
     *
     * <p>这条线程是本类自己起的解码线程，睡它不碰 GL 线程也不碰主线程。
     */
    private boolean sleepUntil(long nanos) {
        try {
            Thread.sleep(nanos / 1_000_000L, (int) (nanos % 1_000_000L));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IllegalArgumentException e) {
            return !stopRequested;   // 纳秒参数越界（理论到不去），当作没睡
        }
        return !stopRequested;
    }

    private static int pickVideoTrack(MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            try {
                String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    return i;
                }
            } catch (Throwable ignored) {
            }
        }
        return -1;
    }

    private static int optInt(MediaFormat format, String key, int fallback) {
        try {
            return format.getInteger(key);
        } catch (Throwable ignored) {
            return fallback;
        }
    }
}
