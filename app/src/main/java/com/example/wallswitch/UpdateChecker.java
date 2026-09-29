package com.example.wallswitch;

import android.content.Context;
import android.content.SharedPreferences;
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
 * 检查更新：对接 Gitee / GitHub 双源的滚动 Release（tag 固定 latest，CI 每次 push 覆盖同一个 asset）。
 *
 * 来源选择存 SharedPreferences（{@link #source}/{@link #setSource}），两个源的 Release 标题
 * 都是「latest（v3.27 · build 45）」格式——CI 构建时把 app/build.gradle 的 versionName 写进
 * 标题，而版本号约定每个提交都递增，所以标题里的 v 版本就是可靠的比对依据。
 *
 * 比对方式：版本号按「.」分段逐段数值比较（3.10 > 3.9，不能按字符串比）。
 *
 * 下载不在本类：这里只负责查版本与提供直链（{@link #apkUrl}），
 * 下载与安装入口见 {@link UpdateDownloader}。
 *
 * 网络用的是系统 HttpURLConnection：全项目刻意只此几个 GET 请求，为此引入
 * OkHttp 类网络库不值当（agents.md 的零依赖约定优先）。
 */
public final class UpdateChecker {

    /** 更新源：gitee（国内直连，正式渠道）/ github（需代理，调试渠道）。 */
    public static final String SRC_GITEE = "gitee";
    public static final String SRC_GITHUB = "github";
    /** 默认走 Gitee（迁移 CI 的初衷就是国内免代理）。 */
    public static final String DEFAULT_SOURCE = SRC_GITEE;
    private static final String PREFS_NAME = "wallswitch";
    private static final String KEY_SOURCE = "update_source";

    // ---- Gitee（lxrzyt/wallsw，公开仓库，接口免令牌）----
    private static final String GITEE_API =
            "https://gitee.com/api/v5/repos/lxrzyt/wallsw/releases/tags/latest";
    private static final String GITEE_PAGE =
            "https://gitee.com/lxrzyt/wallsw/releases/latest";
    private static final String GITEE_APK =
            "https://gitee.com/lxrzyt/wallsw/releases/download/latest/app-debug.apk";

    // ---- GitHub（sdnasdas/wallswitch，需代理）----
    /** 滚动 Release 的 GitHub API 地址（公开接口；国内网络下 api.github.com 经常不通，作首选）。 */
    private static final String GITHUB_API =
            "https://api.github.com/repos/sdnasdas/wallswitch/releases/tags/latest";
    /**
     * 兜底通道：release 页面（github.com 主域，国内可达性明显好于 api 子域，用户实测能直链下载）。
     * 页面 HTML 含 Release 标题「latest（v3.29 · build 60）」，用全角括号定位版本号。
     */
    private static final String GITHUB_PAGE =
            "https://github.com/sdnasdas/wallswitch/releases/latest";
    private static final String GITHUB_APK =
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

    /** 当前更新源（未设置过时取默认）。 */
    public static String source(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String s = sp.getString(KEY_SOURCE, DEFAULT_SOURCE);
        return SRC_GITHUB.equals(s) ? SRC_GITHUB : SRC_GITEE;
    }

    /** 保存更新源。 */
    public static void setSource(Context ctx, String source) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(KEY_SOURCE, SRC_GITHUB.equals(source) ? SRC_GITHUB : SRC_GITEE)
                .apply();
    }

    /** 后台发起检查（source 取当前设置），结果回主线程。 */
    public static void checkAsync(Context context, Callback callback) {
        checkAsync(context, source(context), callback);
    }

    /** 后台发起检查（指定源），结果回主线程。 */
    public static void checkAsync(Context context, String source, Callback callback) {
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            Info info = null;
            Exception error = null;
            try {
                info = fetchLatest(source);
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

    static String apkUrl(String source) {
        return SRC_GITHUB.equals(source) ? GITHUB_APK : GITEE_APK;
    }

    /** 首选 API；API 子域不通时兜底抓 release 页面（GitHub 国内常态，Gitee 同构）。 */
    private static Info fetchLatest(String source) throws Exception {
        boolean github = SRC_GITHUB.equals(source);
        String apiUrl = github ? GITHUB_API : GITEE_API;
        String pageUrl = github ? GITHUB_PAGE : GITEE_PAGE;
        try {
            return fetchViaApi(apiUrl);
        } catch (Exception apiError) {
            try {
                return fetchViaPage(pageUrl);
            } catch (Exception ignored) {
                // 两条通道都失败时上报首选通道的异常（更贴近真实死因）
                throw apiError;
            }
        }
    }

    private static Info fetchViaApi(String apiUrl) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(apiUrl).openConnection();
        try {
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);
            conn.setRequestProperty("Accept", "application/json");
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
    private static Info fetchViaPage(String pageUrl) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(pageUrl).openConnection();
        try {
            // releases/latest 会 302 到具体 tag 页，同协议跳转 HttpURLConnection 自动跟随
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
