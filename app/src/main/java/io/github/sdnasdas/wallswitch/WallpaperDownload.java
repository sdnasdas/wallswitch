package io.github.sdnasdas.wallswitch;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

final class WallpaperDownload {

    static final long MAX_BYTES = 64L * 1024 * 1024;
    private volatile boolean cancelled;
    private volatile HttpURLConnection connection;

    interface Progress {
        void update(long downloaded, long total);
    }

    static boolean isHttps(String value) {
        if (value == null) return false;
        try {
            URL url = new URL(value);
            return "https".equalsIgnoreCase(url.getProtocol())
                    && !url.getHost().isEmpty() && url.getUserInfo() == null;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean isPageImage(String value) {
        return value != null && value.length() <= MAX_BYTES * 4 / 3 + 1024
                && (value.startsWith("blob:") || value.startsWith("data:image/"));
    }

    static boolean sameOrigin(URL a, URL b) {
        int ap = a.getPort() < 0 ? a.getDefaultPort() : a.getPort();
        int bp = b.getPort() < 0 ? b.getDefaultPort() : b.getPort();
        return a.getProtocol().equalsIgnoreCase(b.getProtocol())
                && a.getHost().equalsIgnoreCase(b.getHost()) && ap == bp;
    }

    static String referrer(String page, URL target) throws IOException {
        if (!isHttps(page)) return null;
        URL origin = new URL(page);
        if (sameOrigin(origin, target)) {
            return new URL(origin.getProtocol(), origin.getHost(), origin.getPort(),
                    origin.getFile()).toExternalForm();
        }
        return new URL(origin.getProtocol(), origin.getHost(), origin.getPort(), "/").toExternalForm();
    }

    void cancel() {
        cancelled = true;
        HttpURLConnection active = connection;
        if (active != null) active.disconnect();
    }

    void download(String address, String userAgent, String cookie, String page,
                  File target, Progress progress) throws IOException {
        if (!isHttps(address)) throw new IOException("不支持的下载地址");
        URL initial = new URL(address);
        URL current = initial;
        boolean completed = false;
        try {
            for (int redirects = 0; redirects <= 5; redirects++) {
                if (cancelled) throw new IOException("已取消");
                HttpURLConnection conn = (HttpURLConnection) current.openConnection();
                connection = conn;
                conn.setConnectTimeout(15_000);
                conn.setReadTimeout(30_000);
                conn.setInstanceFollowRedirects(false);
                conn.setRequestProperty("Accept-Encoding", "identity");
                if (userAgent != null) conn.setRequestProperty("User-Agent", userAgent);
                // 重定向到别的站时不得带走原站的登录 Cookie。
                if (cookie != null && sameOrigin(initial, current)) {
                    conn.setRequestProperty("Cookie", cookie);
                }
                String referer = referrer(page, current);
                if (referer != null) conn.setRequestProperty("Referer", referer);
                try {
                    if (cancelled) throw new IOException("已取消");
                    int status = conn.getResponseCode();
                    if (status == 301 || status == 302 || status == 303
                            || status == 307 || status == 308) {
                        String location = conn.getHeaderField("Location");
                        if (location == null) throw new IOException("重定向缺少地址");
                        current = new URL(current, location);
                        if (!isHttps(current.toExternalForm())) throw new IOException("不安全的重定向");
                        continue;
                    }
                    if (status != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + status);
                    long expected = conn.getContentLengthLong();
                    if (expected > MAX_BYTES) throw new IOException("文件超过 64MB");
                    try (InputStream in = conn.getInputStream();
                         FileOutputStream out = new FileOutputStream(target)) {
                        long count = 0;
                        byte[] buffer = new byte[64 * 1024];
                        int n;
                        while ((n = in.read(buffer)) != -1) {
                            if (cancelled) throw new IOException("已取消");
                            count += n;
                            if (count > MAX_BYTES) throw new IOException("文件超过 64MB");
                            out.write(buffer, 0, n);
                            progress.update(count, expected);
                        }
                        if (cancelled || count == 0 || (expected >= 0 && count != expected)) {
                            throw new IOException("文件未下载完整");
                        }
                    }
                    completed = true;
                    return;
                } finally {
                    conn.disconnect();
                    connection = null;
                }
            }
            throw new IOException("重定向过多");
        } finally {
            if (!completed) java.nio.file.Files.deleteIfExists(target.toPath());
        }
    }
}
