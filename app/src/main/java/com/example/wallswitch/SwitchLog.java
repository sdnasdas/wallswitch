package com.example.wallswitch;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 定时自动切换的日志（持久化到文件）。**只在「定时到点自动切换」成功时**记一条，
 * 手动切换与小组件点按不记。
 *
 * 格式：版本号变化时开新的一段，段与段之间空一行；每段第一条不带间隔（它是这个版本第一次切换）：
 * <pre>
 * v3.10
 * 切换时间：2026-09-24 09:15
 * 切换时间：2026-09-24 09:30  间隔上次切换时间：15分钟（当前定时切换设置为15分钟顺序切换）
 *
 * v3.11
 * 切换时间：2026-09-24 10:10
 * </pre>
 * 「间隔上次切换时间」是<b>实际</b>间隔（用于看有没有漂移）；括号里是<b>配置</b>的间隔与顺序/随机。
 *
 * 文件写在应用私有目录 {@code files/switch_log.txt}；如果设了导出目录，会**同名同步一份**过去，
 * 方便用文件管理器直接查看。
 */
public final class SwitchLog {

    private static final String PREFS_NAME = "settings";
    private static final String KEY_VERSION = "switch_log_version";
    private static final String KEY_TIME = "switch_log_time";
    private static final String FILE_NAME = "switch_log.txt";
    /** yyyy-MM-dd HH:mm 的长度，用于从一行里截出「切换时间」的值。 */
    private static final int TIME_TEXT_LEN = 16;

    private SwitchLog() {
    }

    /** 日志文件（应用私有目录）。 */
    public static File logFile(Context ctx) {
        return new File(ctx.getFilesDir(), FILE_NAME);
    }

    /**
     * 记一次「定时自动切换」。纯 IO，调用方放后台线程（TimerScheduler 本来就在 Worker 线程里跑）。
     */
    public static void recordAuto(Context ctx, LibraryStore.Library lib) {
        if (ctx == null || lib == null) {
            return;
        }
        long now = System.currentTimeMillis();
        String version = appVersion(ctx);
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String lastVersion = prefs.getString(KEY_VERSION, null);
        long lastTime = prefs.getLong(KEY_TIME, 0L);
        boolean newBlock = !version.equals(lastVersion);
        File file = logFile(ctx);
        boolean empty = !file.exists() || file.length() == 0;

        StringBuilder sb = new StringBuilder();
        if (newBlock && !empty) {
            // 版本之间空一行
            sb.append('\n');
        }
        if (newBlock) {
            sb.append('v').append(version).append('\n');
        }
        sb.append(ctx.getString(R.string.log_switch_time, format(now)));
        if (!newBlock && lastTime > 0L) {
            long minutes = Math.max(1L, Math.round((now - lastTime) / 60000.0));
            String mode = ctx.getString(LibraryStore.MODE_RANDOM.equals(lib.mode)
                    ? R.string.mode_random : R.string.mode_order);
            sb.append(ctx.getString(R.string.log_switch_interval,
                    minutes, Math.max(1, lib.intervalSeconds / 60), mode));
        }
        sb.append('\n');

        append(ctx, sb.toString());
        prefs.edit().putString(KEY_VERSION, version).putLong(KEY_TIME, now).apply();
        // 设了导出目录就同名同步一份，方便用文件管理器看；没设则什么都不做
        WallpaperExporter.writeTextFile(ctx, FILE_NAME, "text/plain", readAllText(ctx));
    }

    /** 日志全文（没有或读不到返回空串）。供界面直接展示，不必先导出到文件夹。纯 IO，调用方放后台线程。 */
    public static String readAllText(Context ctx) {
        try {
            return new String(Files.readAllBytes(logFile(ctx).toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 最近一条记录里的「切换时间」值（yyyy-MM-dd HH:mm）；没有日志返回 null。
     * 供设置抽屉那一行回显——不开弹窗也知道有没有记录、最后一条是什么时候。
     */
    public static String latestTimeLabel(Context ctx) {
        String all = readAllText(ctx);
        if (all.isEmpty()) {
            return null;
        }
        // 前缀取自字符串资源，避免把「切换时间：」写死在两处
        String prefix = ctx.getString(R.string.log_switch_time, "");
        String[] lines = all.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            int at = line.indexOf(prefix);
            if (at < 0 || line.length() < at + prefix.length() + TIME_TEXT_LEN) {
                continue;
            }
            return line.substring(at + prefix.length(), at + prefix.length() + TIME_TEXT_LEN);
        }
        return null;
    }

    private static void append(Context ctx, String text) {
        try (OutputStream out = new FileOutputStream(logFile(ctx), true)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private static String format(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date(millis));
    }

    /** 当前安装包的版本名（日志分段用）。 */
    private static String appVersion(Context ctx) {
        try {
            String version = ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0).versionName;
            return version == null || version.isEmpty() ? "unknown" : version;
        } catch (Exception e) {
            return "unknown";
        }
    }
}
