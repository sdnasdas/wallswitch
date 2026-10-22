package com.example.wallswitch;

import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.transformer.Codec;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.DefaultEncoderFactory;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.Transformer;
import androidx.media3.ui.PlayerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.slider.RangeSlider;
import com.google.android.material.slider.Slider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@OptIn(markerClass = UnstableApi.class)
public final class VideoTrimActivity extends AppCompatActivity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(() -> {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        r.run();
    }, "video-live-files"));
    private final List<Bitmap> thumbnails = new ArrayList<>();
    private PlayerView playerView;
    private ImageView coverImage;
    private RangeSlider rangeSlider;
    private Slider coverSlider;
    private TextView rangeLabel;
    private TextView coverLabel;
    private TextView status;
    private View busy;
    private View confirm;
    private TextView play;
    private View coverMarker;
    private LinearLayout thumbnailStrip;
    private ExoPlayer player;
    private Transformer transformer;
    private VideoSelection selection;
    private VideoFrameTimes frameTimes;
    private long selectedCoverUs = -1;
    private Bitmap coverBitmap;
    private File source;
    private File scratch;
    private File pendingLive;
    private String inboxId;
    private String libId;
    private Bundle restored;
    private boolean binding;
    private boolean exporting;
    private boolean previewing;
    private boolean started;
    private boolean handedOff;
    private volatile boolean closed;
    private volatile int coverRequest;
    private final Runnable coverTick = this::loadCover;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_video_trim);
        InsetsHelper.apply(this, R.id.video_trim_root);
        restored = state;
        inboxId = getIntent().getStringExtra(EditActivity.EXTRA_INBOX_ID);
        libId = getIntent().getStringExtra(EditActivity.EXTRA_LIB_ID);
        if (inboxId == null) {
            finish();
            return;
        }
        source = WallpaperStore.getInboxFile(this, inboxId);
        try {
            scratch = Files.createTempDirectory(getCacheDir().toPath(), "video-live-").toFile();
        } catch (IOException e) {
            Toast.makeText(this, getString(R.string.video_trim_failed, e.getMessage()), Toast.LENGTH_LONG).show();
            cancelImport();
            return;
        }
        Toolbar toolbar = findViewById(R.id.video_trim_toolbar);
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        toolbar.setNavigationContentDescription(R.string.cancel);
        toolbar.setNavigationOnClickListener(v -> cancelImport());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { cancelImport(); }
        });
        playerView = findViewById(R.id.video_trim_player);
        coverImage = findViewById(R.id.video_trim_cover_image);
        rangeSlider = findViewById(R.id.video_trim_range_slider);
        coverSlider = findViewById(R.id.video_trim_cover_slider);
        rangeLabel = findViewById(R.id.video_trim_range_label);
        coverLabel = findViewById(R.id.video_trim_cover_label);
        status = findViewById(R.id.video_trim_status);
        busy = findViewById(R.id.video_trim_busy);
        confirm = findViewById(R.id.video_trim_confirm);
        play = findViewById(R.id.video_trim_play);
        coverMarker = findViewById(R.id.video_trim_cover_marker);
        thumbnailStrip = findViewById(R.id.video_trim_thumbnails);
        GradientDrawable window = new GradientDrawable();
        window.setColor(0x221e88e5);
        window.setCornerRadius(4 * getResources().getDisplayMetrics().density);
        window.setStroke(Math.max(1, Math.round(2 * getResources().getDisplayMetrics().density)),
                getColor(R.color.brand));
        findViewById(R.id.video_trim_range_window).setBackground(window);
        adaptLayout();
        rangeSlider.addOnChangeListener((slider, value, fromUser) -> {
            if (binding || !fromUser || selection == null) {
                return;
            }
            stopPreview();
            long time = Math.round(value * selection.durationMs());
            if (value == slider.getValues().get(0)) {
                selection.setStart(time);
            } else {
                selection.setEnd(time);
            }
            bindSelection();
            requestCover();
        });
        coverSlider.addOnChangeListener((slider, value, fromUser) -> {
            if (binding || !fromUser || selection == null) {
                return;
            }
            stopPreview();
            selection.setCover(selection.startMs()
                    + Math.round(value * (selection.endMs() - selection.startMs() - 1)));
            bindSelection();
            requestCover();
        });
        rangeSlider.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> positionTimeline());
        findViewById(R.id.video_trim_cancel).setOnClickListener(v -> cancelImport());
        play.setOnClickListener(v -> togglePreview());
        confirm.setOnClickListener(v -> generateLive());
        worker.execute(this::readVideo);
    }

    private void cancelImport() {
        closed = true;
        WallpaperStore.cancelImport(this, inboxId);
        finish();
    }

    private void adaptLayout() {
        if (getResources().getConfiguration().orientation != Configuration.ORIENTATION_LANDSCAPE) {
            return;
        }
        LinearLayout body = findViewById(R.id.video_trim_body);
        body.setOrientation(LinearLayout.HORIZONTAL);
        findViewById(R.id.video_trim_preview).setLayoutParams(
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));
        findViewById(R.id.video_trim_panel).setLayoutParams(
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));
    }

    private void readVideo() {
        List<Bitmap> frames = new ArrayList<>();
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(source.getAbsolutePath());
            if (!"yes".equals(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO))) {
                throw new IOException("文件中没有视频轨");
            }
            long duration = Long.parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            VideoSelection initial = new VideoSelection(duration);
            VideoFrameTimes timestamps = readFrameTimes();
            if (restored != null) {
                initial.setEnd(restored.getLong("end", initial.endMs()));
                initial.setStart(restored.getLong("start", initial.startMs()));
                initial.setCover(restored.getLong("cover", initial.coverMs()));
            }
            for (int i = 0; i < 8 && !closed; i++) {
                frames.add(frameAtUs(retriever, (duration - 1) * 1000 * i / 7, 160));
            }
            handler.post(() -> {
                if (closed) {
                    recycle(frames);
                    return;
                }
                selection = initial;
                frameTimes = timestamps;
                thumbnails.addAll(frames);
                for (Bitmap bitmap : frames) {
                    ImageView image = new ImageView(this);
                    image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    image.setImageBitmap(bitmap);
                    image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                    thumbnailStrip.addView(image, new LinearLayout.LayoutParams(0,
                            LinearLayout.LayoutParams.MATCH_PARENT, 1));
                }
                bindSelection();
                setExporting(false);
                if (started) {
                    createPlayer();
                }
                requestCover();
            });
        } catch (Exception | OutOfMemoryError e) {
            recycle(frames);
            handler.post(() -> showFailure(e));
        } finally {
            closeRetriever(retriever);
        }
    }

    private VideoFrameTimes readFrameTimes() throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(source.getAbsolutePath());
            int track = -1;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    track = i;
                    break;
                }
            }
            if (track < 0) {
                throw new IOException("文件中没有视频轨");
            }
            extractor.selectTrack(track);
            List<Long> times = new ArrayList<>();
            long time;
            while (!closed && (time = extractor.getSampleTime()) != -1) {
                if (time >= 0) {
                    times.add(time);
                }
                extractor.advance();
            }
            long[] values = new long[times.size()];
            for (int i = 0; i < values.length; i++) {
                values[i] = times.get(i);
            }
            return new VideoFrameTimes(values);
        } finally {
            extractor.release();
        }
    }

    private void bindSelection() {
        selectedCoverUs = frameTimes.nearest(selection.coverMs() * 1000, selection.startMs(), selection.endMs());
        if (selectedCoverUs >= 0) {
            selection.setCover(selectedCoverUs / 1000);
        }
        binding = true;
        rangeSlider.setValues(selection.startMs() / (float) selection.durationMs(),
                selection.endMs() / (float) selection.durationMs());
        long spanUs = (selection.endMs() - selection.startMs()) * 1000 - 1;
        coverSlider.setValue(selectedCoverUs < 0 ? 0
                : (selectedCoverUs - selection.startMs() * 1000) / (float) spanUs);
        binding = false;
        rangeLabel.setText(getString(R.string.video_trim_range_time,
                selection.startMs() / 1000d, selection.endMs() / 1000d,
                (selection.endMs() - selection.startMs()) / 1000d));
        coverLabel.setText(selectedCoverUs < 0 ? "—"
                : getString(R.string.video_trim_seconds, selectedCoverUs / 1_000_000d));
        positionTimeline();
        setExporting(exporting);
    }

    private void positionTimeline() {
        int padding = rangeSlider.getTrackSidePadding();
        FrameLayout.LayoutParams strip = (FrameLayout.LayoutParams) thumbnailStrip.getLayoutParams();
        strip.leftMargin = padding;
        strip.rightMargin = padding;
        thumbnailStrip.setLayoutParams(strip);
        if (selection != null) {
            int width = Math.max(0, rangeSlider.getWidth() - 2 * padding);
            int left = Math.round(width * selection.startMs() / (float) selection.durationMs());
            int right = Math.round(width * selection.endMs() / (float) selection.durationMs());
            positionOverlay(R.id.video_trim_shade_left, padding, left);
            positionOverlay(R.id.video_trim_shade_right, padding + right, width - right);
            positionOverlay(R.id.video_trim_range_window, padding + left, Math.max(1, right - left));
            coverMarker.setVisibility(selectedCoverUs < 0 ? View.INVISIBLE : View.VISIBLE);
            coverMarker.setTranslationX(padding + width * Math.max(0, selectedCoverUs)
                    / (selection.durationMs() * 1000f));
        }
    }

    private void positionOverlay(int id, int left, int width) {
        View view = findViewById(id);
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) view.getLayoutParams();
        params.width = width;
        view.setLayoutParams(params);
        view.setTranslationX(left);
    }

    private void createPlayer() {
        if (player != null || exporting || selection == null) {
            return;
        }
        player = new ExoPlayer.Builder(this).build();
        player.setSeekParameters(SeekParameters.EXACT);
        player.setVolume(0);
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build());
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_ENDED) {
                    stopPreview();
                }
            }
            @Override public void onPlayerError(PlaybackException error) {
                stopPreview();
                showFailure(error);
            }
        });
        playerView.setPlayer(player);
    }

    private MediaItem clipItem() {
        return new MediaItem.Builder().setUri(Uri.fromFile(source))
                .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(selection.startMs()).setEndPositionMs(selection.endMs()).build())
                .build();
    }

    private void togglePreview() {
        if (previewing) {
            stopPreview();
            return;
        }
        createPlayer();
        if (player == null) {
            return;
        }
        previewing = true;
        coverImage.setVisibility(View.INVISIBLE);
        play.setText(R.string.video_trim_pause);
        player.setMediaItem(clipItem());
        player.prepare();
        player.play();
    }

    private void stopPreview() {
        previewing = false;
        if (player != null) {
            player.pause();
        }
        if (coverImage != null) {
            coverImage.setVisibility(View.VISIBLE);
            play.setText(R.string.video_trim_play);
        }
    }

    private void releasePlayer() {
        stopPreview();
        if (player != null) {
            playerView.setPlayer(null);
            player.release();
            player = null;
        }
    }

    private void requestCover() {
        coverRequest++;
        handler.removeCallbacks(coverTick);
        handler.postDelayed(coverTick, 100);
    }

    private void loadCover() {
        if (selection == null || selectedCoverUs < 0 || closed || exporting) {
            return;
        }
        int request = coverRequest;
        long timeUs = selectedCoverUs;
        worker.execute(() -> {
            if (closed || request != coverRequest) {
                return;
            }
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            Bitmap frame = null;
            try {
                retriever.setDataSource(source.getAbsolutePath());
                frame = frameAtUs(retriever, timeUs, 1080);
                Bitmap result = frame;
                handler.post(() -> {
                    if (closed || request != coverRequest) {
                        result.recycle();
                        return;
                    }
                    Bitmap old = coverBitmap;
                    coverBitmap = result;
                    coverImage.setImageBitmap(result);
                    if (old != null) {
                        old.recycle();
                    }
                });
            } catch (Exception | OutOfMemoryError e) {
                if (frame != null) {
                    frame.recycle();
                }
                handler.post(() -> {
                    if (request == coverRequest) {
                        showFailure(e);
                    }
                });
            } finally {
                closeRetriever(retriever);
            }
        });
    }

    private void generateLive() {
        if (selection == null || selectedCoverUs < 0 || exporting) {
            return;
        }
        releasePlayer();
        coverRequest++;
        handler.removeCallbacks(coverTick);
        setExporting(true);
        // 等上一轮系统提帧释放解码器，再启动导出，避免同时占用两个硬解码器。
        worker.execute(() -> handler.post(this::startExport));
    }

    private void startExport() {
        if (closed || isFinishing() || !exporting) {
            return;
        }
        File video = new File(scratch, "clip.mp4");
        try {
            Files.deleteIfExists(video.toPath());
            Codec.EncoderFactory encoder = new DefaultEncoderFactory.Builder(this).build();
            // 任意起点必须重编视频；仅重封装会被关键帧对齐而保留用户没选的画面。
            Codec.EncoderFactory exactEncoder = new Codec.EncoderFactory() {
                @Override public Codec createForAudioEncoding(Format format) throws ExportException {
                    return encoder.createForAudioEncoding(format);
                }
                @Override public Codec createForVideoEncoding(Format format) throws ExportException {
                    return encoder.createForVideoEncoding(format);
                }
                @Override public boolean videoNeedsEncoding() { return true; }
            };
            EditedMediaItem edited = new EditedMediaItem.Builder(clipItem()).setRemoveAudio(true).build();
            Composition composition = new Composition.Builder(new EditedMediaItemSequence(edited))
                    .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL).build();
            long sourceCoverUs = selectedCoverUs;
            long coverTime = (sourceCoverUs - selection.startMs() * 1000) / 1000;
            transformer = new Transformer.Builder(this)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setEncoderFactory(exactEncoder)
                    .addListener(new Transformer.Listener() {
                        @Override public void onCompleted(Composition composition, ExportResult result) {
                            transformer = null;
                            if (!closed && !isFinishing()) {
                                worker.execute(() -> packageLive(video, sourceCoverUs, coverTime));
                            }
                        }
                        @Override public void onError(Composition composition, ExportResult result, ExportException error) {
                            transformer = null;
                            showFailure(error);
                        }
                    }).build();
            transformer.start(composition, video.getAbsolutePath());
        } catch (Exception | OutOfMemoryError e) {
            if (transformer != null) {
                transformer.cancel();
                transformer = null;
            }
            showFailure(e);
        }
    }

    private void packageLive(File video, long sourceCoverUs, long coverTime) {
        if (closed) {
            return;
        }
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        Bitmap bitmap = null;
        try {
            retriever.setDataSource(video.getAbsolutePath());
            long duration = Long.parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            if (duration <= 0 || duration > VideoSelection.MAX_DURATION_MS + 50) {
                throw new IOException("导出片段时长异常");
            }
            retriever.setDataSource(source.getAbsolutePath());
            bitmap = frameAtUs(retriever, sourceCoverUs, WallpaperStore.maxWallpaperDim(this));
            File jpeg = new File(scratch, "cover.jpg");
            try (FileOutputStream out = new FileOutputStream(jpeg)) {
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)) {
                    throw new IOException("封面生成失败");
                }
            }
            File live = new File(scratch, "live.jpg");
            LivePhotoFiles.write(jpeg, video, live, Math.min(coverTime, duration - 1));
            handler.post(() -> handOff(live));
        } catch (Exception | OutOfMemoryError e) {
            handler.post(() -> showFailure(e));
        } finally {
            if (bitmap != null) {
                bitmap.recycle();
            }
            closeRetriever(retriever);
        }
    }

    private void handOff(File live) {
        if (closed || isFinishing()) {
            return;
        }
        if (!started) {
            pendingLive = live;
            return;
        }
        pendingLive = null;
        try {
            Files.move(live.toPath(), source.toPath(), StandardCopyOption.REPLACE_EXISTING);
            Intent intent = new Intent(this, EditActivity.class);
            intent.putExtra(EditActivity.EXTRA_INBOX_ID, inboxId);
            intent.putExtra(EditActivity.EXTRA_LIB_ID, libId);
            startActivity(intent);
            handedOff = true;
            finish();
        } catch (Exception e) {
            showFailure(e);
        }
    }

    private void setExporting(boolean value) {
        exporting = value;
        boolean editable = selection != null && !value;
        boolean hasFrame = selectedCoverUs >= 0;
        confirm.setEnabled(editable && hasFrame);
        play.setEnabled(editable && hasFrame);
        rangeSlider.setEnabled(editable);
        coverSlider.setEnabled(editable && hasFrame);
        busy.setVisibility(value ? View.VISIBLE : View.GONE);
        status.setText(value ? R.string.video_trim_exporting
                : editable && !hasFrame ? R.string.video_trim_no_frames : R.string.video_trim_hint);
    }

    private void showFailure(Throwable error) {
        if (closed || isFinishing()) {
            return;
        }
        setExporting(false);
        if (started) {
            createPlayer();
        }
        String message = error.getMessage();
        status.setText(getString(R.string.video_trim_failed,
                message == null ? error.getClass().getSimpleName() : message));
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.video_trim_title)
                .setMessage(status.getText())
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private static Bitmap frameAtUs(MediaMetadataRetriever retriever, long timeUs, int maxSide) throws IOException {
        Bitmap bitmap;
        if (Build.VERSION.SDK_INT >= 27) {
            // 系统提帧已转正；方形边界框等比缩放，不能把横向编码宽高当作竖向帧的边界。
            bitmap = retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST,
                    maxSide, maxSide);
        } else {
            bitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST);
            if (bitmap != null && Math.max(bitmap.getWidth(), bitmap.getHeight()) > maxSide) {
                float scale = maxSide / (float) Math.max(bitmap.getWidth(), bitmap.getHeight());
                Bitmap resized = Bitmap.createScaledBitmap(bitmap, Math.max(1, Math.round(bitmap.getWidth() * scale)),
                        Math.max(1, Math.round(bitmap.getHeight() * scale)), true);
                bitmap.recycle();
                bitmap = resized;
            }
        }
        if (bitmap == null) {
            throw new IOException("无法提取视频帧");
        }
        return bitmap;
    }

    private static void closeRetriever(MediaMetadataRetriever retriever) {
        try {
            retriever.release();
        } catch (Exception ignored) {
        }
    }

    private static void recycle(List<Bitmap> bitmaps) {
        for (Bitmap bitmap : bitmaps) {
            bitmap.recycle();
        }
    }

    @Override protected void onStart() {
        super.onStart();
        started = true;
        if (pendingLive != null) {
            handOff(pendingLive);
        } else if (playerView != null) {
            createPlayer();
        }
    }

    @Override protected void onStop() {
        started = false;
        if (playerView != null) {
            releasePlayer();
        }
        super.onStop();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        if (selection != null) {
            out.putLong("start", selection.startMs());
            out.putLong("end", selection.endMs());
            out.putLong("cover", selection.coverMs());
        }
        super.onSaveInstanceState(out);
    }

    @Override protected void onDestroy() {
        closed = true;
        coverRequest++;
        handler.removeCallbacks(coverTick);
        if (transformer != null) {
            transformer.cancel();
            transformer = null;
        }
        if (playerView != null) {
            releasePlayer();
            coverImage.setImageDrawable(null);
            thumbnailStrip.removeAllViews();
        }
        if (coverBitmap != null) {
            coverBitmap.recycle();
        }
        recycle(thumbnails);
        if (inboxId != null && isFinishing() && !handedOff) {
            WallpaperStore.cancelImport(this, inboxId);
        }
        File directory = scratch;
        worker.execute(() -> {
            if (directory != null) {
                File[] files = directory.listFiles();
                if (files != null) {
                    for (File file : files) {
                        file.delete();
                    }
                }
                directory.delete();
            }
        });
        worker.shutdown();
        super.onDestroy();
    }
}
