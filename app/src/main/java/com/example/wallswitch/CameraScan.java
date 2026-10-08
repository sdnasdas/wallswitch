package com.example.wallswitch;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 扫码用的相机：只把 640×480 级的 Y 平面按每 250ms 一次的节奏喂给 zxing，不拍照、不录像、不存帧。
 *
 * <p>为什么不引 CameraX：本项目只用「预览流 + 取帧」这一件事，CameraX 要带进 preview/camera2/lifecycle
 * 三四个 artifact，`check.cmd` 就得跟着多补一片桩（桩只能锁签名、锁不住行为，多一个桩多一处漂移）。
 *
 * <p>发热与耗流的收口：分辨率挑「面积 ≥ 640×480 的最小档并夹到 1280×720」，解码节流 250ms
 * （其余帧拿到就 close，不进解码器），扫到 / 页面 onPause / 退出 三条路径都会走到 {@link #stop()}。
 *
 * <p>解码不旋转帧：QR 本身没有方向性，zxing 的定位图案对 90° 旋转免疫，所以喂原始 YUV 就行；
 * 屏幕上的预览才需要转（那是 {@link ScanTransform} 的事，画歪不影响识别）。
 * 竖屏手持时 {@code SENSOR_ORIENTATION} 真机是 90，Activity 已锁竖屏，所以只处理这一种。
 */
public final class CameraScan {

    /** 解出来的文本与失败原因都在主线程回调。 */
    public interface Callbacks {
        void onText(String text);

        void onFail(String why);
    }

    static final long DECODE_GAP_MS = 250;
    static final int TARGET_AREA = 640 * 480;
    static final int MAX_AREA = 1280 * 720;

    private final Context context;
    private final TextureView view;
    private final Callbacks callbacks;
    private final Handler main = new Handler(android.os.Looper.getMainLooper());
    private final MultiFormatReader decoder = new MultiFormatReader();
    private final Map<DecodeHintType, Object> hints =
            Collections.singletonMap(DecodeHintType.POSSIBLE_FORMATS,
                    Arrays.asList(com.google.zxing.BarcodeFormat.QR_CODE));

    private HandlerThread thread;
    private Handler bg;
    private CameraDevice device;
    private CameraCaptureSession session;
    private ImageReader reader;
    private Size bufSize;
    private int sensorAngle = 90;
    private long lastDecodeAt;
    private volatile boolean stopped;

    public CameraScan(Context context, TextureView view, Callbacks callbacks) {
        this.context = context;
        this.view = view;
        this.callbacks = callbacks;
    }

    /** 要求 CAMERA 权限已授；TextureView 还没准备好时会等 onSurfaceTextureAvailable。 */
    public void start() {
        stopped = false;
        thread = new HandlerThread("lan-scan");
        thread.start();
        bg = new Handler(thread.getLooper());
        if (view.isAvailable()) {
            openCamera();
        } else {
            view.setSurfaceTextureListener(listener);
        }
    }

    private final TextureView.SurfaceTextureListener listener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
            if (!stopped) {
                openCamera();
            }
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
            applyTransform();
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture st) {
        }
    };

    private void openCamera() {
        CameraManager mgr = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (mgr == null) {
            fail("这台机没有可用的相机服务");
            return;
        }
        try {
            String chosen = null;
            for (String id : mgr.getCameraIdList()) {
                CameraCharacteristics c = mgr.getCameraCharacteristics(id);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    chosen = id;
                    Integer orient = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
                    sensorAngle = orient == null ? 90 : orient;
                    StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                    if (map != null) {
                        bufSize = pickSize(map.getOutputSizes(android.graphics.ImageFormat.YUV_420_888));
                    }
                    break;
                }
            }
            if (chosen == null) {
                fail("找不到后置相机");
                return;
            }
            if (bufSize == null) {
                bufSize = new Size(640, 480);
            }
            applyTransform();
            mgr.openCamera(chosen, deviceCallback, bg);
        } catch (Exception | OutOfMemoryError e) {
            fail(e.getClass().getSimpleName());
        }
    }

    /** 挑「面积 ≥ 640×480 中最小的一档」，并夹到 1280×720：解一屏上的码用不着更多像素。 */
    static Size pickSize(Size[] sizes) {
        if (sizes == null || sizes.length == 0) {
            return null;
        }
        Size best = null;
        for (Size s : sizes) {
            long area = (long) s.getWidth() * s.getHeight();
            if (area < TARGET_AREA || area > MAX_AREA) {
                continue;
            }
            if (best == null || area < (long) best.getWidth() * best.getHeight()) {
                best = s;
            }
        }
        if (best != null) {
            return best;
        }
        // 没有落在区间里的（奇怪的驱动），取最小的那一档，总比拿 4K 强
        for (Size s : sizes) {
            if (best == null || (long) s.getWidth() * s.getHeight()
                    < (long) best.getWidth() * best.getHeight()) {
                best = s;
            }
        }
        return best;
    }

    /**
     * 把取景框摆成缓冲转置后的比例（3:4），再套 {@link ScanTransform} 算出的矩阵。
     *
     * <p>高度夹到屏高的 62%：整块 3:4 会把下面的按钮顶到屏幕外，得让人不翻页就能点「手动输入」。
     * 夹小之后内容不再「刚好」盖满，而是竖向超出被切掉 —— 矩阵仍是不变形的（夹具里
     * 「取景框比例不对时也不变形/仍盖满」那两条验的就是这种偏离），中心也对得上，
     * 所以只是取景范围小了，不会画歪。
     */
    private void applyTransform() {
        int viewW = view.getWidth();
        if (viewW <= 0 || bufSize == null) {
            return;
        }
        String ratio = ScanTransform.suggestedRatio(bufSize.getWidth(), bufSize.getHeight(),
                sensorAngle);
        String[] wh = ratio.split(":");
        int wanted = (int) (viewW * Float.parseFloat(wh[1]) / Float.parseFloat(wh[0]));
        android.graphics.Point screen = new android.graphics.Point();
        android.view.WindowManager wm =
                (android.view.WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        int cap = Integer.MAX_VALUE;
        if (wm != null) {
            wm.getDefaultDisplay().getRealSize(screen);
            cap = (int) (screen.y * 0.62f);
        }
        int viewH = Math.max(wanted / 2, Math.min(wanted, cap));
        android.view.ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (lp != null && lp.height != viewH) {
            lp.height = viewH;
            view.setLayoutParams(lp);
        }
        float[] values = ScanTransform.fillRotate(viewW, viewH,
                bufSize.getWidth(), bufSize.getHeight(), sensorAngle);
        Matrix m = new Matrix();
        m.setValues(values);
        view.setTransform(m);
    }

    private final CameraDevice.StateCallback deviceCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice cam) {
            if (stopped) {
                cam.close();
                return;
            }
            device = cam;
            buildSession();
        }

        @Override
        public void onDisconnected(CameraDevice cam) {
            cam.close();
            fail("相机被别的应用占着");
        }

        @Override
        public void onError(CameraDevice cam, int error) {
            cam.close();
            fail("相机打不开（错误码 " + error + "）");
        }
    };

    private void buildSession() {
        SurfaceTexture st = view.getSurfaceTexture();
        if (st == null || device == null) {
            return;
        }
        st.setDefaultBufferSize(bufSize.getWidth(), bufSize.getHeight());
        reader = ImageReader.newInstance(bufSize.getWidth(), bufSize.getHeight(),
                android.graphics.ImageFormat.YUV_420_888, 2);
        reader.setOnImageAvailableListener(onImage, bg);
        final Surface preview = new Surface(st);
        try {
            CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            b.addTarget(preview);
            b.addTarget(reader.getSurface());
            // 连续视频对焦：扫屏幕上的码不需要微距，但固定焦距在 20~40 厘米会发虚
            b.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            List<Surface> outputs = Arrays.asList(preview, reader.getSurface());
            final CaptureRequest request = b.build();
            device.createCaptureSession(outputs, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession s) {
                    session = s;
                    try {
                        s.setRepeatingRequest(request, null, bg);
                    } catch (Exception | OutOfMemoryError e) {
                        fail(e.getClass().getSimpleName());
                    }
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession s) {
                    fail("相机预览配置失败");
                }
            }, bg);
        } catch (Exception | OutOfMemoryError e) {
            fail(e.getClass().getSimpleName());
        }
    }

    private final ImageReader.OnImageAvailableListener onImage = source -> {
        Image img = source.acquireLatestImage();
        if (img == null) {
            return;
        }
        try {
            if (stopped) {
                return;
            }
            long now = SystemClock.uptimeMillis();
            if (now - lastDecodeAt < DECODE_GAP_MS) {
                return;                     // 节流：这一帧直接丢，不进解码器
            }
            lastDecodeAt = now;
            String text = decodeOnce(img);
            if (text != null && !text.isEmpty()) {
                deliverText(text);
            }
        } catch (Exception | OutOfMemoryError e) {
            // 解不出来是常态（没对准、虚焦），静默等下一帧
        } finally {
            img.close();
        }
    };

    /**
     * 扫到之后的交接：置停、回主线程交文本、关相机。
     * 单独成方法是因为 {@link #onImage} 是字段初始化器 —— 在里面直接读 final 字段 {@code callbacks}
     * 会被 javac 判成「构造尚未完成，可能没初始化」。走一层方法就没这个问题，语义也更清楚。
     */
    private void deliverText(String text) {
        stopped = true;
        main.post(() -> {
            callbacks.onText(text);
            stop();                     // 扫到就关相机，不等用户点下一步
        });
    }

    /**
     * Y 平面（rowStride 可能大于 width，所以 dataWidth 传 rowStride，裁剪矩形仍用真实宽高）。
     * 解不出时 zxing 抛的三个异常都归「这帧没码」，由调用方吞掉。
     */
    private String decodeOnce(Image img) throws Exception {
        Image.Plane p = img.getPlanes()[0];
        ByteBuffer bb = p.getBuffer();
        byte[] data = new byte[bb.remaining()];
        bb.get(data);
        int w = img.getWidth();
        int h = img.getHeight();
        PlanarYUVLuminanceSource src = new PlanarYUVLuminanceSource(data, p.getRowStride(), h,
                0, 0, w, h, false);
        Result r = decoder.decode(new BinaryBitmap(new HybridBinarizer(src)), hints);
        return r == null ? null : r.getText();
    }

    private void fail(String why) {
        main.post(() -> callbacks.onFail(why));
    }

    /** 幂等：任何时刻都能调，页面 onPause/onDestroy 必须调到（相机开着很耗电也很热）。 */
    public void stop() {
        stopped = true;
        CameraCaptureSession s = session;
        session = null;
        if (s != null) {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
        CameraDevice d = device;
        device = null;
        if (d != null) {
            try {
                d.close();
            } catch (Exception ignored) {
            }
        }
        ImageReader r = reader;
        reader = null;
        if (r != null) {
            try {
                r.close();
            } catch (Exception ignored) {
            }
        }
        HandlerThread t = thread;
        thread = null;
        bg = null;
        if (t != null) {
            try {
                t.quitSafely();
            } catch (Exception ignored) {
            }
        }
    }
}
