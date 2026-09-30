package com.example.wallswitch;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.Settings;
import android.util.Log;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * App 内自管的更新包下载：APK 下到本 App 的 cache 目录，下载进度记在内存里、
 * 「已下载待安装」记进 prefs，装好后由 {@link #pruneInstalled} 认领并清掉。
 *
 * 为什么不用系统 DownloadManager：进度与完成提示全在系统那条通知里，通知被划掉（或被
 * ROM 折叠）之后用户既看不到进度也不知道包落在哪，只能重下。这里把这三件事收进 App 界面。
 *
 * 下载刻意不走 WorkManager：包只有一两兆，进程活着就能下完；真被回收了，重进 App 点一下即续。
 */
public final class UpdateDownloader {

    /** 没有进行中的任务，也没已下载的包。 */
    public static final int IDLE = 0;
    /** 下载中，{@link #percent()} 是当前百分比。 */
    public static final int DOWNLOADING = 1;
    /** 包已在本机，点按即拉安装页。 */
    public static final int READY = 2;
    /** 上一次下载失败，点按重试。 */
    public static final int FAILED = 3;

    private static final String PREFS_NAME = "wallswitch";
    private static final String KEY_READY_VERSION = "update_ready_version";
    private static final String APK_NAME = "wallswitch-update.apk";
    /** 更新包单独住 cache 下一个子目录：FileProvider 只授权这个目录，不把整个缓存暴露给安装页。 */
    private static final String APK_DIR = "apk";
    /** FileProvider 的 authority 后缀（与 AndroidManifest 里 provider 声明必须一致）。 */
    private static final String PROVIDER_AUTHORITY = ".fileprovider";
    private static final String MIME_APK = "application/vnd.android.package-archive";
    private static final String LOG_TAG = "UpdateDownloader";

    /** 进程内实时状态与百分比：下载线程写、界面线程读。 */
    private static volatile int liveState = IDLE;
    private static volatile int livePercent;
    /** 取消标记：界面线程置位，下载线程在读取循环里逐块自查（自己收尾、自己清临时文件）。 */
    private static volatile boolean cancelRequested;

    private UpdateDownloader() {
    }

    /** 更新包落在 cache 的子目录里（App 私有、不要存储权限；被系统清理了也只是重下一次）。 */
    public static File apkFile(Context ctx) {
        return new File(apkDir(ctx), APK_NAME);
    }

    /** 更新包专属子目录：FileProvider 只共享这一个目录（见 res/xml/file_paths.xml）。 */
    private static File apkDir(Context ctx) {
        return new File(ctx.getCacheDir(), APK_DIR);
    }

    /** 当前下载百分比（0-100；服务端不给 Content-Length 时一直停在 0）。 */
    public static int percent() {
        return livePercent;
    }

    /** 已下载好但还没装的包对应的版本；包不在（含从没下过）返回 null。 */
    public static String readyVersion(Context ctx) {
        File f = apkFile(ctx);
        if (!f.exists() || f.length() <= 0) {
            return null;
        }
        return prefs(ctx).getString(KEY_READY_VERSION, null);
    }

    /** 界面用状态：下载中优先，其次看包在不在，最后才认内存里的失败标记。 */
    public static int state(Context ctx) {
        if (liveState == DOWNLOADING) {
            return DOWNLOADING;
        }
        if (readyVersion(ctx) != null) {
            return READY;
        }
        return liveState == FAILED ? FAILED : IDLE;
    }

    /** 系统是否已允许本 App 发起安装（未允许时点安装只会跳授权页）。 */
    public static boolean hasInstallPermission(Context ctx) {
        try {
            return ctx.getPackageManager().canRequestPackageInstalls();
        } catch (Exception e) {
            return false;
        }
    }

    /** 跳「允许安装未知应用」授权页（非商店渠道绕不过这一步，交给用户开一次）。 */
    public static void openInstallPermissionSettings(Activity act) {
        try {
            act.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + act.getPackageName())));
        } catch (Exception e) {
            Log.w(LOG_TAG, "跳安装授权页失败", e);
        }
    }

    /** 开始下载（{@code version} 是这次要下的版本号，装完据此清账）；已在下载中则忽略。 */
    public static void start(Context ctx, String source, String version) {
        if (liveState == DOWNLOADING) {
            return;
        }
        final Context app = ctx.getApplicationContext();
        cancelRequested = false;
        liveState = DOWNLOADING;
        livePercent = 0;
        new Thread(() -> download(app, UpdateChecker.apkUrl(source), version),
                "apk-download").start();
    }

    /**
     * 取消进行中的下载：置标记后立刻返回，下载线程在下一块读隙里自己作废——
     * 关连接、删 .tmp、状态归零都由那根线程办完，界面上进度那一行随后刷回「检查更新」。
     * 没在下就什么都不做（重复点、或刚好下完时都不留副作用）。
     */
    public static void cancel() {
        if (liveState == DOWNLOADING) {
            cancelRequested = true;
        }
    }

    /**
     * 拉起系统安装页（经 FileProvider 临时授权只读）。
     *
     * @return false 表示包已经不在了，调用方应把这一行刷回「检查更新」
     */
    public static boolean install(Activity act) {
        String version = readyVersion(act);
        if (version == null) {
            return false;
        }
        try {
            Uri uri = FileProvider.getUriForFile(act,
                    act.getPackageName() + PROVIDER_AUTHORITY, apkFile(act));
            act.startActivity(new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, MIME_APK)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (Exception e) {
            Log.w(LOG_TAG, "拉起安装页失败", e);
            return false;
        }
    }

    /**
     * 冷启动清账：cache 里那个包已经装上去了（版本不再比本机新），说明这次更新已落地，
     * 删文件加清记账，界面回到「检查更新」，不至于一直挂着个用不着的安装入口。
     */
    public static void pruneInstalled(Context ctx) {
        String ready = readyVersion(ctx);
        if (ready == null || UpdateChecker.isNewer(ready, UpdateChecker.localVersion(ctx))) {
            return;
        }
        apkFile(ctx).delete();
        prefs(ctx).edit().remove(KEY_READY_VERSION).apply();
        liveState = IDLE;
    }

    /** 后台线程下载：先写 .tmp 再改名，避免半个文件被当成可安装的包。 */
    private static void download(Context ctx, String url, String version) {
        apkDir(ctx).mkdirs();
        File tmp = new File(apkDir(ctx), APK_NAME + ".tmp");
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(15_000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "WallSwitch-App");
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new IOException("HTTP " + code);
            }
            long total = conn.getContentLength();
            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16384];
                long done = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (cancelRequested) {
                        // 取消在这根线程里自首：作废连接、抹掉半截文件、状态归零，不走失败分支
                        conn.disconnect();
                        tmp.delete();
                        livePercent = 0;
                        liveState = IDLE;
                        Log.i(LOG_TAG, "下载已取消，临时包已删");
                        return;
                    }
                    out.write(buf, 0, n);
                    done += n;
                    if (total > 0) {
                        livePercent = (int) (done * 100 / total);
                    }
                }
            }
            if (tmp.length() <= 0) {
                throw new IOException("下载内容为空");
            }
            apkFile(ctx).delete();
            if (!tmp.renameTo(apkFile(ctx))) {
                throw new IOException("改名失败");
            }
            prefs(ctx).edit().putString(KEY_READY_VERSION, version).apply();
            livePercent = 100;
            liveState = READY;
        } catch (Exception | OutOfMemoryError e) {
            tmp.delete();
            Log.w(LOG_TAG, "更新包下载失败", e);
            liveState = FAILED;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
