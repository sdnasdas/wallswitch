package io.github.sdnasdas.wallswitch;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.format.Formatter;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebMessage;
import android.webkit.WebMessagePort;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WallpaperBrowserActivity extends AppCompatActivity {

    private static final String HOME = "https://haowallpaper.com/mobileView";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private WebView web;
    private MaterialToolbar toolbar;
    private ProgressBar pageProgress;
    private ProgressBar downloadProgress;
    private TextView downloadStatus;
    private View downloadPanel;
    private Download active;
    private boolean confirmingDownload;
    private AlertDialog confirmation;
    private int documentGeneration;
    private String preparedBlobToken;
    private int preparedBlobDocument;

    private static final class Download {
        final String id = UUID.randomUUID().toString();
        final WallpaperDownload network = new WallpaperDownload();
        final File file;
        final String page;
        final String title;
        volatile boolean cancelled;
        FileOutputStream stream;
        WebMessagePort port;
        WebMessagePort peer;
        long total;
        long bytes;
        long lastProgress;
        Runnable timeout;
        boolean awaitingMessage = true;
        String blobToken;
        int document;

        Download(Context app, String page, String title) {
            file = new File(app.getCacheDir(), "browser-" + id + ".download");
            this.page = page;
            this.title = title;
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_wallpaper_browser);
        InsetsHelper.apply(this, R.id.browser_root);
        toolbar = findViewById(R.id.browser_toolbar);
        pageProgress = findViewById(R.id.browser_page_progress);
        downloadPanel = findViewById(R.id.browser_download_panel);
        downloadStatus = findViewById(R.id.browser_download_status);
        downloadProgress = findViewById(R.id.browser_download_progress);
        web = findViewById(R.id.wallpaper_webview);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSafeBrowsingEnabled(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                String url = request.getUrl().toString();
                if (WallpaperDownload.isHttps(url)) return false;
                Toast.makeText(WallpaperBrowserActivity.this,
                        R.string.browse_unsupported_download, Toast.LENGTH_SHORT).show();
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                documentGeneration++;
                if (confirmation != null) confirmation.dismiss();
                pageProgress.setVisibility(View.VISIBLE);
                if (active != null && active.port != null) cancelDownload();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageProgress.setVisibility(View.GONE);
                String host = Uri.parse(url).getHost();
                toolbar.setSubtitle(host == null ? getString(R.string.browse_provider) : host);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    pageProgress.setVisibility(View.GONE);
                    Toast.makeText(WallpaperBrowserActivity.this,
                            R.string.browse_page_failed, Toast.LENGTH_LONG).show();
                }
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int progress) {
                pageProgress.setProgress(progress);
            }
        });
        web.setDownloadListener(this::confirmDownload);
        web.setOnLongClickListener(view -> {
            WebView.HitTestResult hit = web.getHitTestResult();
            if (hit == null) return false;
            if (hit.getType() == WebView.HitTestResult.IMAGE_TYPE) {
                confirmDownload(hit.getExtra(), web.getSettings().getUserAgentString(),
                        null, "image/*", -1);
                return true;
            }
            if (hit.getType() == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                int document = documentGeneration;
                Handler imageHandler = new Handler(Looper.getMainLooper(), message -> {
                    if (isFinishing() || isDestroyed() || document != documentGeneration) return true;
                    String source = message.getData().getString("src");
                    if (source != null) {
                        confirmDownload(source, web.getSettings().getUserAgentString(),
                                null, "image/*", -1);
                    }
                    return true;
                });
                // 带链接的图片命中结果是链接，必须取 src，不能把详情页当图片下载。
                web.requestFocusNodeHref(imageHandler.obtainMessage());
                return true;
            }
            return false;
        });
        toolbar.setNavigationOnClickListener(v -> leave());
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_browser_refresh) web.reload();
            if (item.getItemId() == R.id.action_browser_external) openExternal();
            return true;
        });
        findViewById(R.id.browser_cancel_download).setOnClickListener(v -> cancelDownload());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (web.canGoBack()) web.goBack();
                else leave();
            }
        });
        if (state == null || web.restoreState(state) == null) web.loadUrl(HOME);
    }

    private void confirmDownload(String url, String userAgent, String disposition,
                                 String mimeType, long length) {
        if (active != null || confirmingDownload) {
            Toast.makeText(this, R.string.browse_download_busy, Toast.LENGTH_SHORT).show();
            return;
        }
        String page = web.getUrl();
        boolean blob = WallpaperDownload.isPageImage(url) && WallpaperDownload.isHttps(page);
        if ((!WallpaperDownload.isHttps(url) && !blob) || length > WallpaperDownload.MAX_BYTES) {
            Toast.makeText(this, R.string.browse_unsupported_download, Toast.LENGTH_LONG).show();
            return;
        }
        String title = web.getTitle();
        String filename = blob ? getString(R.string.browse_selected_image)
                : URLUtil.guessFileName(url, disposition, mimeType);
        if (blob && !prepareBlob(url)) return;
        final int document = documentGeneration;
        confirmingDownload = true;
        confirmation = new AppAlertDialogBuilder(this)
                .setTitle(R.string.browse_download_title)
                .setMessage(getString(R.string.browse_download_message, filename))
                .setPositiveButton(R.string.browse_download_start, (dialog, which) -> {
                    if (isFinishing() || isDestroyed() || document != documentGeneration) return;
                    Download task = new Download(getApplicationContext(), page, title);
                    task.document = document;
                    task.blobToken = preparedBlobToken;
                    preparedBlobToken = null;
                    active = task;
                    downloadPanel.setVisibility(View.VISIBLE);
                    downloadProgress.setIndeterminate(true);
                    downloadStatus.setText(R.string.browse_downloading);
                    if (blob) startBlob(task);
                    else startNetwork(task, url, userAgent);
                })
                .setNegativeButton(R.string.cancel, null)
                .setOnDismissListener(dialog -> {
                    confirmingDownload = false;
                    confirmation = null;
                    releaseBlob(preparedBlobToken, preparedBlobDocument);
                    preparedBlobToken = null;
                })
                .show();
    }

    private void startNetwork(Download task, String url, String userAgent) {
        String cookie = CookieManager.getInstance().getCookie(url);
        Context app = getApplicationContext();
        io.execute(() -> {
            try {
                task.network.download(url, userAgent, cookie, task.page, task.file,
                        (bytes, total) -> publishProgress(task, bytes, total));
                finishFile(app, task);
            } catch (Exception e) {
                fail(task);
            }
        });
    }

    private void publishProgress(Download task, long bytes, long total) {
        long now = SystemClock.elapsedRealtime();
        if (now - task.lastProgress < 100 && bytes != total) return;
        task.lastProgress = now;
        main.post(() -> {
            if (active != task || task.cancelled) return;
            boolean known = total > 0;
            downloadProgress.setIndeterminate(!known);
            String received = Formatter.formatFileSize(this, bytes);
            if (known) {
                int percent = (int) Math.min(100, bytes * 100 / total);
                downloadProgress.setProgress(percent);
                downloadStatus.setText(getString(R.string.browse_download_progress,
                        received, Formatter.formatFileSize(this, total), percent));
            } else {
                downloadStatus.setText(getString(R.string.browse_download_bytes, received));
            }
        });
    }

    private boolean prepareBlob(String address) {
        String token = UUID.randomUUID().toString();
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream in = getAssets().open("browser-download.js")) {
                byte[] buffer = new byte[4096];
                int length;
                while ((length = in.read(buffer)) != -1) bytes.write(buffer, 0, length);
            }
            String script = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
            preparedBlobToken = token;
            preparedBlobDocument = documentGeneration;
            // 网站会很快撤销 blob 地址，先在原文档留住文件；确认前不传原生端口、不写本地。
            web.evaluateJavascript(script + "(" + JSONObject.quote(token) + ","
                    + JSONObject.quote(address) + "," + WallpaperDownload.MAX_BYTES + ");", null);
            return true;
        } catch (IOException e) {
            Toast.makeText(this, R.string.browse_download_failed, Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private void releaseBlob(String token, int document) {
        if (token != null && document == documentGeneration) {
            web.evaluateJavascript("window.dispatchEvent(new Event(" + JSONObject.quote(token) + "));", null);
        }
    }

    private void startBlob(Download task) {
        try {
            Uri page = Uri.parse(task.page);
            Uri origin = new Uri.Builder().scheme(page.getScheme())
                    .encodedAuthority(page.getEncodedAuthority()).build();
            WebMessagePort[] channel = web.createWebMessageChannel();
            task.port = channel[0];
            task.peer = channel[1];
            task.port.setWebMessageCallback(new WebMessagePort.WebMessageCallback() {
                @Override
                public void onMessage(WebMessagePort port, WebMessage message) {
                    acceptBlobMessage(task, message.getData());
                }
            });
            web.postWebMessage(new WebMessage(task.blobToken, new WebMessagePort[]{task.peer}), origin);
            armBlobTimeout(task);
        } catch (Exception e) {
            fail(task);
        }
    }

    private void armBlobTimeout(Download task) {
        if (task.timeout != null) main.removeCallbacks(task.timeout);
        task.timeout = () -> fail(task);
        main.postDelayed(task.timeout, 30_000);
    }

    private void acceptBlobMessage(Download task, String raw) {
        if (active != task || task.cancelled) return;
        try {
            if (!task.awaitingMessage || raw == null || raw.length() > 100_000) {
                throw new IOException("无效消息");
            }
            task.awaitingMessage = false;
            JSONObject message = new JSONObject(raw);
            String type = message.getString("type");
            if ("begin".equals(type)) {
                if (task.total != 0) throw new IOException("重复的文件头");
                task.total = message.getLong("size");
                if (task.total <= 0 || task.total > WallpaperDownload.MAX_BYTES) {
                    throw new IOException("文件超过上限");
                }
                io.execute(() -> {
                    try {
                        if (task.cancelled) return;
                        task.stream = new FileOutputStream(task.file);
                        nextBlobChunk(task);
                    } catch (Exception e) {
                        fail(task);
                    }
                });
            } else if ("chunk".equals(type)) {
                if (task.total == 0) throw new IOException("缺少文件头");
                String encoded = message.getString("data");
                io.execute(() -> {
                    try {
                        if (task.cancelled) return;
                        byte[] bytes = Base64.decode(encoded, Base64.NO_WRAP);
                        if (task.stream == null || bytes.length == 0 || bytes.length > 65536
                                || task.bytes + bytes.length > task.total) {
                            throw new IOException("无效文件分块");
                        }
                        task.stream.write(bytes);
                        task.bytes += bytes.length;
                        publishProgress(task, task.bytes, task.total);
                        nextBlobChunk(task);
                    } catch (Exception e) {
                        fail(task);
                    }
                });
            } else if ("end".equals(type)) {
                io.execute(() -> {
                    try {
                        if (task.cancelled) return;
                        if (task.stream == null || task.bytes != task.total) {
                            throw new IOException("文件不完整");
                        }
                        task.stream.close();
                        task.stream = null;
                        finishFile(getApplicationContext(), task);
                    } catch (Exception e) {
                        fail(task);
                    }
                });
            } else {
                throw new IOException("网页下载失败");
            }
            armBlobTimeout(task);
        } catch (Exception e) {
            fail(task);
        }
    }

    private void nextBlobChunk(Download task) {
        main.post(() -> {
            if (active != task || task.cancelled) return;
            try {
                task.awaitingMessage = true;
                task.port.postMessage(new WebMessage("next"));
            } catch (Exception e) {
                fail(task);
            }
        });
    }

    private void finishFile(Context app, Download task) throws IOException {
        if (task.cancelled) return;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(task.file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("不是图片");
        File inbox = WallpaperStore.getInboxFile(app, task.id);
        Files.createDirectories(inbox.getParentFile().toPath());
        Files.move(task.file.toPath(), inbox.toPath());
        main.post(() -> {
            if (active != task || task.cancelled || isFinishing() || isDestroyed()) return;
            active = null;
            closePort(task);
            downloadPanel.setVisibility(View.GONE);
            Intent edit = new Intent(this, EditActivity.class);
            edit.putExtra(EditActivity.EXTRA_INBOX_ID, task.id);
            edit.putExtra(EditActivity.EXTRA_SOURCE_TITLE, task.title);
            edit.putExtra(EditActivity.EXTRA_SOURCE_URL, task.page);
            startActivity(edit);
        });
    }

    private void fail(Download task) {
        main.post(() -> {
            if (active != task || task.cancelled) return;
            cancelDownload();
            Toast.makeText(this, R.string.browse_download_failed, Toast.LENGTH_LONG).show();
        });
    }

    private void cancelDownload() {
        Download task = active;
        if (task == null) return;
        active = null;
        task.cancelled = true;
        task.network.cancel();
        closePort(task);
        downloadPanel.setVisibility(View.GONE);
        Context app = getApplicationContext();
        io.execute(() -> {
            try {
                if (task.stream != null) task.stream.close();
                Files.deleteIfExists(task.file.toPath());
                WallpaperStore.cancelImport(app, task.id);
            } catch (IOException ignored) {
            }
        });
    }

    private void closePort(Download task) {
        if (task.timeout != null) main.removeCallbacks(task.timeout);
        if (task.port != null) task.port.close();
        releaseBlob(task.blobToken, task.document);
    }

    private void leave() {
        if (active == null) {
            finish();
            return;
        }
        new AppAlertDialogBuilder(this)
                .setTitle(R.string.browse_leave_title)
                .setMessage(R.string.browse_leave_message)
                .setPositiveButton(R.string.browse_leave, (dialog, which) -> {
                    cancelDownload();
                    finish();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void openExternal() {
        String url = web.getUrl();
        if (!WallpaperDownload.isHttps(url)) url = HOME;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, R.string.source_link_failed, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        web.saveState(state);
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onDestroy() {
        if (confirmation != null) confirmation.dismiss();
        cancelDownload();
        ((ViewGroup) web.getParent()).removeView(web);
        web.destroy();
        io.shutdown();
        super.onDestroy();
    }
}
