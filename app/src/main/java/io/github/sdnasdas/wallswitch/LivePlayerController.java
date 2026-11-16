package io.github.sdnasdas.wallswitch;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.view.MenuItem;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileInputStream;

/**
 * 单张详情的实况播放那一簇：默认不播，点顶栏实况键播一遍，或长按画面按住期间循环播。
 * 播放器/播放层/起播参数都收在这里；详情页提供画面与取景，Activity 管生命周期里的停播。
 */
final class LivePlayerController {

    private final MainActivity a;
    private TextureView liveView;
    private MediaPlayer livePlayer;
    private Surface liveSurface;
    // 正在播（或正准备播）的那张；null = 没在播
    private String liveId;
    // 长按触发的：松手就停、按住期间循环
    private boolean liveHeld;
    // 每次起播/停播都 +1，后台准备回来时对不上就作废（翻页、关详情、连点都靠它挡掉迟到的那一轮）
    private int liveToken;
    // 后台算好的起播参数，等 TextureView 的 Surface 就绪后再用
    private LiveSpec pendingLive;

    LivePlayerController(MainActivity a) {
        this.a = a;
    }

    /** 正在播（或正准备播）的那张 id；null = 没在播。 */
    String playingId() {
        return liveId;
    }

    boolean isPlaying() {
        return liveId != null;
    }

    /** 是否长按触发的循环播（松手要停）。 */
    boolean isHeld() {
        return liveHeld;
    }

    /** 起播所需的一切（后台算好、主线程用）：实况段字节区间 + 播放层的位置尺寸 + 画面裁切矩阵。 */
    private static final class LiveSpec {
        String id;
        File original;
        long offset;
        long length;
        boolean loop;
        int left, top, width, height;
        Matrix transform;
    }

    void setupLiveView() {
        liveView = a.findViewById(R.id.texture_live);
        liveView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture st, int w, int h) {
                LiveSpec spec = pendingLive;
                pendingLive = null;
                if (spec != null && spec.id.equals(liveId)) {
                    beginLivePlayback(spec, st);
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture st, int w, int h) {
            }

            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture st) {
                releaseLivePlayer();
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(@NonNull SurfaceTexture st) {
                // 兜底显形：有的机型不发 RENDERING_START，那就以「真有新帧到了」为准淡入
                if (liveId != null && livePlayer != null && liveView.getAlpha() == 0f) {
                    liveView.animate().alpha(1f).setDuration(120).start();
                }
            }
        });
    }

    /** 顶栏实况键：只有带实况段的那张才露出来；播放中换成暂停图标，点它停。 */
    private void bindLiveMenu(boolean live) {
        MenuItem item = a.detailToolbar.getMenu().findItem(R.id.action_detail_live);
        if (item == null) {
            return;
        }
        item.setVisible(live);
        boolean playing = liveId != null;
        item.setIcon(playing ? R.drawable.ic_pause : R.drawable.ic_motion_photo);
        item.setTitle(playing ? R.string.detail_live_stop : R.string.detail_live_play);
    }

    void refreshLiveMenu() {
        WallpaperStore.Item item = a.detailItem();
        if (!a.detailOpen || item == null) {
            a.images.unbind(a.detailToolbar);
            bindLiveMenu(false);
            return;
        }
        a.images.unbind(a.detailToolbar);
        a.detailToolbar.setTag(item.id);
        Boolean live = a.images.cachedLive(item.id);
        // 未知时仍可点；startLive 已有后台 locate + liveToken，不能丢掉用户请求。
        bindLiveMenu(liveId != null || !Boolean.FALSE.equals(live));
        if (live == null) {
            a.images.metadata(a.detailToolbar, item.id, (owner, info) -> {
                WallpaperStore.Item current = a.detailItem();
                if (a.detailOpen && current != null && current.id.equals(owner.getTag())) {
                    bindLiveMenu(liveId != null || info.live);
                }
            });
        }
    }

    /**
     * 播这张的实况段。读盘与量尺寸放后台：定位实况段（读原图头尾）、读成品图宽高、读视频帧宽高与旋转。
     * 播放层摆在成品图 fitCenter 后的显示矩形上，画面按「原图坐标裁剪矩形 → 归一化 → 再按播放层比例
     * centerCrop」裁出来 —— 与桌面引擎播实况是同一套取景（见 WallSwitchService#cropRectOf），
     * 所以这里看到的就是上屏后会动的那一块。
     */
    void startLive(WallpaperStore.Item item, boolean held) {
        if (!a.detailOpen) return;
        stopLive();
        final int token = liveToken;
        liveId = item.id;
        liveHeld = held;
        refreshLiveMenu();
        final int viewW = a.detailPager.getWidth();
        final int viewH = a.detailPager.getHeight();
        final float[] norm = item.hasCropRect()
                ? io.github.sdnasdas.wallswitch.gl.MotionSource.normalizedRect(new float[]{
                        item.cropLeft, item.cropTop, item.cropRight, item.cropBottom},
                        item.srcWidth, item.srcHeight)
                : null;
        final Context app = a.getApplicationContext();
        final String id = item.id;
        final boolean fill = a.detailPreview;
        a.imageLoader.execute(() -> {
            LiveSpec spec = prepareLive(app, id, held, norm, viewW, viewH, fill);
            a.runOnUiThread(() -> {
                if (token != liveToken || a.isDestroyed()) {
                    return;
                }
                if (spec == null) {
                    stopLive();
                    Toast.makeText(a, R.string.detail_live_failed, Toast.LENGTH_SHORT).show();
                    return;
                }
                showLiveLayer(spec);
            });
        });
    }

    /** 后台：算出起播参数，任何一步不成立返回 null。 */
    private static LiveSpec prepareLive(Context ctx, String id, boolean loop, float[] norm,
                                        int viewW, int viewH, boolean fill) {
        if (viewW <= 0 || viewH <= 0) {
            return null;
        }
        File original = WallpaperStore.getOriginalFile(ctx, id);
        long[] range = io.github.sdnasdas.wallswitch.gl.MotionSource.locate(original);
        if (range == null || range.length < 2 || range[1] <= 0) {
            return null;
        }
        // 成品图宽高：播放层要和静态图那块显示区域严丝合缝
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(WallpaperStore.getFullFile(ctx, id).getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        // 视频帧转正后的宽高（rotation-degrees 已由缓冲区变换承担，比例要按转正后的算）
        int frameW, frameH;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try (FileInputStream in = new FileInputStream(original)) {
            retriever.setDataSource(in.getFD(), range[0], range[1]);
            int codedW = parseIntOr(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int codedH = parseIntOr(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            int rotation = parseIntOr(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
            int[] size = io.github.sdnasdas.wallswitch.gl.MotionSource.displaySize(codedW, codedH, rotation);
            frameW = size[0];
            frameH = size[1];
        } catch (Exception | OutOfMemoryError e) {
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
        if (frameW <= 0 || frameH <= 0) {
            return null;
        }
        // 成品图在页面里的显示矩形：平时 fitCenter；桌面预览时 centerCrop（可能比页面大，边距为负）
        float scale = fill
                ? Math.max(viewW / (float) bounds.outWidth, viewH / (float) bounds.outHeight)
                : Math.min(viewW / (float) bounds.outWidth, viewH / (float) bounds.outHeight);
        int dispW = Math.max(1, Math.round(bounds.outWidth * scale));
        int dispH = Math.max(1, Math.round(bounds.outHeight * scale));
        LiveSpec spec = new LiveSpec();
        spec.id = id;
        spec.original = original;
        spec.offset = range[0];
        spec.length = range[1];
        spec.loop = loop;
        spec.width = dispW;
        spec.height = dispH;
        spec.left = (viewW - dispW) / 2;
        spec.top = (viewH - dispH) / 2;
        spec.transform = liveTransform(norm, frameW, frameH, dispW, dispH);
        return spec;
    }

    /**
     * TextureView 默认把整帧拉伸铺满自身；这里算一个矩阵，让「取景框那块、再按播放层比例 centerCrop」
     * 正好铺满播放层。坐标全用 [0,1] 比例（v 从顶部起算），与 MotionSource#cropForViewport 同一套口径。
     */
    private static Matrix liveTransform(float[] norm, int frameW, int frameH, int viewW, int viewH) {
        float u0 = 0f, t0 = 0f, u1 = 1f, t1 = 1f;
        if (norm != null && norm.length == 4) {
            u0 = norm[0];
            t0 = norm[1];
            u1 = norm[2];
            t1 = norm[3];
        }
        float spanU = Math.max(u1 - u0, 1e-4f);
        float spanT = Math.max(t1 - t0, 1e-4f);
        float subAspect = (spanU * frameW) / (spanT * frameH);
        float vpAspect = viewW / (float) viewH;
        spanU *= Math.min(1f, vpAspect / subAspect);
        spanT *= Math.min(1f, subAspect / vpAspect);
        float c = (t0 + t1) * 0.5f - spanT * 0.5f;
        float sx = 1f / spanU;
        float sy = 1f / spanT;
        Matrix m = new Matrix();
        m.setScale(sx, sy);
        m.postTranslate(-((u0 + u1) * 0.5f - spanU * 0.5f) * viewW * sx, -c * viewH * sy);
        return m;
    }

    private static int parseIntOr(String s) {
        try {
            return s == null ? 0 : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 主线程：把播放层摆到成品图的显示矩形上；Surface 就绪后再起播放器。 */
    private void showLiveLayer(LiveSpec spec) {
        android.widget.FrameLayout.LayoutParams lp =
                (android.widget.FrameLayout.LayoutParams) liveView.getLayoutParams();
        lp.width = spec.width;
        lp.height = spec.height;
        lp.leftMargin = spec.left;
        lp.topMargin = spec.top;
        liveView.setLayoutParams(lp);
        liveView.setTransform(spec.transform);
        // 首帧出来前透明：先盖一块黑再出画面会闪
        liveView.setAlpha(0f);
        // TextureView 设成 GONE 并不会销毁它的 SurfaceTexture（只有从窗口上拆下来才销毁），
        // 所以第二次起播时 isAvailable() 仍是 true、onSurfaceTextureAvailable 也不会再回调。
        // 以前这里要求「已经 VISIBLE 且 available」才直接播，否则挂 pending 等回调 —— 第一次播完收成 GONE 后，
        // 再播就落进等回调那一支、永远等不来，只能杀进程。现在只看 available：有就直接播
        liveView.setVisibility(View.VISIBLE);
        if (liveView.isAvailable()) {
            beginLivePlayback(spec, liveView.getSurfaceTexture());
        } else {
            pendingLive = spec;
        }
    }

    private void beginLivePlayback(LiveSpec spec, SurfaceTexture st) {
        releaseLivePlayer();
        final int token = liveToken;
        MediaPlayer player = new MediaPlayer();
        try (FileInputStream in = new FileInputStream(spec.original)) {
            // setDataSource 会自己 dup 一份 fd，流在这里关掉不影响播放
            player.setDataSource(in.getFD(), spec.offset, spec.length);
        } catch (Exception e) {
            player.release();
            stopLive();
            Toast.makeText(a, R.string.detail_live_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        liveSurface = new Surface(st);
        player.setSurface(liveSurface);
        // 与桌面引擎一致：实况段那条音轨不播
        player.setVolume(0f, 0f);
        player.setLooping(spec.loop);
        player.setOnPreparedListener(mp -> {
            if (token == liveToken) {
                mp.start();
            }
        });
        player.setOnInfoListener((mp, what, extra) -> {
            if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && token == liveToken) {
                liveView.animate().alpha(1f).setDuration(120).start();
            }
            return false;
        });
        player.setOnCompletionListener(mp -> {
            // 点键播的那一遍播完就退回静态图；长按是循环的，走不到这里
            if (token == liveToken) {
                stopLive();
            }
        });
        player.setOnErrorListener((mp, what, extra) -> {
            if (token == liveToken) {
                stopLive();
                Toast.makeText(a, R.string.detail_live_failed, Toast.LENGTH_SHORT).show();
            }
            return true;
        });
        livePlayer = player;
        try {
            player.prepareAsync();
        } catch (Exception e) {
            stopLive();
            Toast.makeText(a, R.string.detail_live_failed, Toast.LENGTH_SHORT).show();
        }
    }

    /** 停播并收起播放层（任何时候都可以调，没在播就只是把状态归零）。 */
    void stopLive() {
        liveToken++;
        boolean wasPlaying = liveId != null;
        liveId = null;
        liveHeld = false;
        pendingLive = null;
        releaseLivePlayer();
        if (liveView != null) {
            liveView.animate().cancel();
            liveView.setAlpha(0f);
            liveView.setVisibility(View.GONE);
        }
        if (wasPlaying && a.detailToolbar != null) {
            refreshLiveMenu();
        }
    }

    private void releaseLivePlayer() {
        if (livePlayer != null) {
            try {
                livePlayer.release();
            } catch (Exception ignored) {
            }
            livePlayer = null;
        }
        if (liveSurface != null) {
            liveSurface.release();
            liveSurface = null;
        }
    }

    /**
     * 设置页「视频转 Live」入口：挑一段视频交裁剪页。
     * 这一档不限片段长度，产出直接写相册（Pictures/WallSwitch），不进壁纸库、也不占收件箱。
     */
    void pickLiveConverterVideo() {
        PickVisualMediaRequest.Builder builder = new PickVisualMediaRequest.Builder();
        builder.setMediaType(ActivityResultContracts.PickVisualMedia.VideoOnly.INSTANCE);
        a.liveVideoLauncher.launch(builder.build());
    }

    /** 视频到手就开裁剪页；读权限跟着这次结果走，所以裁剪页第一步先把整段复制进缓存。 */
    void onLiveVideoPicked(Uri uri) {
        if (uri == null) {
            return;
        }
        android.content.Intent intent = new android.content.Intent(a, VideoTrimActivity.class);
        intent.putExtra(VideoTrimActivity.EXTRA_SOURCE_URI, uri);
        a.startActivity(intent);
    }
}