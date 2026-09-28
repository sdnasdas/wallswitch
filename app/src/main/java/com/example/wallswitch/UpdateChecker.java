package com.example.wallswitch;

import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 检查更新：对接 GitHub 滚动 Release（tag 固定 latest，CI 每次 push 覆盖同一个 asset）。
 *
 * 版本来源：Release 标题形如「latest（v3.27 · build 45）」——build.yml 在每次构建时
 * 把 app/build.gradle 的 versionName 写进标题，而版本号约定每个提交都递增，
 * 所以标题里的 v 版本就是可靠的比对依据（比资产文件名/日期可靠，那些是固定不变的）。
 *
 * 比对方式：版本号按「.」分段逐段数值比较（3.10 > 3.9，不能按字符串比）。
 *
 * 下载：交给系统 DownloadManager 后台下载到公共 Download/WallSwitch/，
 * 通知栏可看进度，完成后点通知即进安装页；DownloadManager 不可用时回退浏览器直链。
 *
 * 网络用的是系统 HttpURLConnection：全项目刻意只此一个 GET 请求，为此引入
 * OkHttp 类网络库不值当（agents.md 的零依赖约定优先）。
 */
public final class UpdateChecker {

    /** 滚动 Release 的 GitHub API 地址（公开接口；国内网络下 api.github.com 经常不通，作首选）。 */
    private static final String RELEASES_API =
            "https://api.github.com/repos/sdnasdas/wallswitch/releases/tags/latest";
    /**
     * 兜底通道：release 页面（github.com 主域，国内可达性明显好于 api 子域，用户实测能直链下载）。
     * 页面 HTML 含 Release 标题「latest（v3.29 · build 60）」，用全角括号定位版本号。
     */
    private static final String RELEASES_PAGE =
            "https://github.com/sdnasdas/wallswitch/releases/latest";
    /** APK 直链（CI 固定 asset 名，永远指向最新构建）。 */
    private static final String APK_URL =
            "https://github.com/sdnasdas/wallswitch/releases/download/latest/app-debug.apk";
    /** 从标题/页面里抠版本号（v3.27 / v3.10 都能匹配）。 */
    private static final Pattern VERSION_PATTERN = Pattern.compile("v(\\d+(?:\\.\\d+)*)");
    /** 页面兜底专用：Release 标题的版本号都紧跟全角括号，避免误匹配页面里其它版本串。 */
    private static final Pattern PAGE_VERSION_PATTERN = Pattern.compile("（v(\\d+(?:\\.\\d+)*)");

    /** 远端版本信息。 */
    public static class Info {
        /** 解析出的版本号，如 "3.27"。 */
        public final String version;
        /** Release 标题原文（含 build 号，弹窗展示用）。 */
        public final String title;

        Info(String version, String title) {
            this.version = version;
            this.title = title;
        }
    }

    /** 检查结果回调（主线程回调；info 与 error 至少一个非空）。 */
    public interface Callback {
        void onResult(Info info, Exception error);
    }

    private UpdateChecker() {
    }

    /** 后台发起检查，结果回主线程。 */
    public static void checkAsync(Context context, Callback callback) {
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            Info info = null;
            Exception error = null;
            try {
                info = fetchLatest();
            } catch (Exception e) {
                error = e;
            }
            final Info fInfo = info;
            final Exception fError = error;
            new Handler(Looper.getMainLooper()).post(() -> callback.onResult(fInfo, fError));
        }, "update-check").start();
    }

    /** 本机 versionName，取不到时按 "0" 处理（总认为需要更新，不会更糟）。 */
    public static String localVersion(Context ctx) {
        try {
            return ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "0";
        }
    }

    /** 远端版本是否比本机新：分段数值比较，段数不足按 0 补齐。 */
    public static boolean isNewer(String remote, String local) {
        int[] a = parseSegments(remote);
        int[] b = parseSegments(local);
        int len = Math.max(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) {
                return x > y;
            }
        }
        return false;
    }

    /** 用系统 DownloadManager 下载最新 APK（公共 Download/WallSwitch/，点完成通知即装）。 */
    public static void downloadApk(Context ctx) {
        try {
            DownloadManager dm =
                    (DownloadManager) ctx.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) {
                openInBrowser(ctx);
                return;
            }
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(APK_URL));
            req.setTitle(ctx.getString(R.string.app_name) + " " + localVersion(ctx) + " 安装包");
            req.setDescription(ctx.getString(R.string.update_download_desc));
            req.setMimeType("application/vnd.android.package-archive");
            req.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS, "WallSwitch/app-debug.apk");
            dm.enqueue(req);
        } catch (Exception e) {
            openInBrowser(ctx);
        }
    }

    /** DownloadManager 走不通时的兜底：浏览器打开直链。 */
    private static void openInBrowser(Context ctx) {
        try {
            android.content.Intent intent = new android.content.Intent(
                    android.content.Intent.ACTION_VIEW, Uri.parse(APK_URL));
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
        } catch (Exception ignored) {
        }
    }

    /** 首选 API；api.github.com 不通（国内常态）时兜底抓 github.com 的 release 页面。 */
    private static Info fetchLatest() throws Exception {
        try {
            return fetchViaApi();
        } catch (Exception apiError) {
            try {
                return fetchViaPage();
            } catch (Exception ignored) {
                // 两条通道都失败时上报首选通道的异常（更贴近真实死因）
                throw apiError;
            }
        }
    }

    private static Info fetchViaApi() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(RELEASES_API).openConnection();
        try {
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            // GitHub API 要求带 User-Agent
            conn.setRequestProperty("User-Agent", "WallSwitch-App");
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new java.io.IOException("HTTP " + code);
            }
            JSONObject root = new JSONObject(readAll(conn));
            String title = root.optString("name", "");
            Matcher m = VERSION_PATTERN.matcher(title);
            if (!m.find()) {
                throw new java.io.IOException("版本号解析失败: " + title);
            }
            return new Info(m.group(1), title);
        } finally {
            conn.disconnect();
        }
    }

    /** 兜底：抓 release 页面 HTML，按全角括号定位标题里的版本号。 */
    private static Info fetchViaPage() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(RELEASES_PAGE).openConnection();
        try {
            // releases/latest 会 302 到 /tag/latest，同协议跳转 HttpURLConnection 自动跟随
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);
            conn.setRequestProperty("User-Agent", "WallSwitch-App");
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new java.io.IOException("HTTP " + code);
            }
            String html = readAll(conn);
            Matcher m = PAGE_VERSION_PATTERN.matcher(html);
            if (!m.find()) {
                throw new java.io.IOException("页面无版本号");
            }
            return new Info(m.group(1), "v" + m.group(1));
        } finally {
            conn.disconnect();
        }
    }

    /** 读尽响应体（UTF-8）。 */
    private static String readAll(HttpURLConnection conn) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    private static int[] parseSegments(String version) {
        if (version == null || version.isEmpty()) {
            return new int[]{0};
        }
        String[] parts = version.split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }
}
