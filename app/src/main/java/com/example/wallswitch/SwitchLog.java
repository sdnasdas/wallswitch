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
 * 格式：版本号变化时开新的一段，段与段之间空一行；每条两行（时间行 + 实际间隔行，缩进对齐），
 * 条目之间也空一行；每段第一条不带间隔（它是这个版本第一次切换）：
 * <pre>
 * v3.15
 * 2026-09-24 14:33  桌面「海边」
 *
 * 2026-09-24 14:48  桌面「城市」
 *   实际间隔 15 分钟 · 设置 15 分钟 · 随机
 * </pre>
 * 第一行：时间 + 这次切了哪个范围、轮到哪张（两个范围各自推进时写成「桌面「A」  /  锁屏「B」」）。
 * 第二行：「实际间隔」是两次切换真实相差的分钟数（看有没有漂移）；「设置」是库里配的间隔与顺序/随机。
 *
 * 文件写在应用私有目录 {@code files/switch_log.txt}；如果设了导出目录，会**同名同步一份**过去，
 * 方便用文件管理器直接查看。
 */
public final class SwitchLog {

    private static final String PREFS_NAME = "settings";
    private static final String KEY_VERSION = "switch_log_version";
    private static final String KEY_TIME = "switch_log_time";
    private static final String FILE_NAME = "switch_log.txt";

    private SwitchLog() {
    }

    /** 日志文件（应用私有目录）。 */
    public static File logFile(Context ctx) {
        return new File(ctx.getFilesDir(), FILE_NAME);
    }

    /**
     * 记一次「定时自动切换」。纯 IO，调用方放后台线程（TimerScheduler 本来就在 Worker 线程里跑）。
     *
     * @param detail 这次切了哪个范围、轮到哪张的可读描述（见 TimerScheduler.switchDetail）；
     *               传 null 则只记时间
     */
    public static void recordAuto(Context ctx, LibraryStore.Library lib, String detail) {
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
        } else if (lastTime > 0L) {
            // 条目之间空一行（时间行 + 间隔行是一组，不拆开）
            sb.append('\n');
        }
        sb.append(format(now));
        if (detail != null && !detail.isEmpty()) {
            sb.append("  ").append(detail);
        }
        sb.append('\n');
        if (!newBlock && lastTime > 0L) {
            long minutes = Math.max(1L, Math.round((now - lastTime) / 60000.0));
            String mode = ctx.getString(LibraryStore.MODE_RANDOM.equals(lib.mode)
                    ? R.string.mode_random : R.string.mode_order);
            // 缩进两格：一眼看出这行属于上面那次切换
            sb.append("  ").append(ctx.getString(R.string.log_interval_line,
                    minutes, Math.max(1, lib.intervalSeconds / 60), mode)).append('\n');
        }

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
     * 最近一条记录的时间（yyyy-MM-dd HH:mm）；没有记录返回 null。
     * 直接取记账时写下的偏好值 —— 不再去日志正文里截字符串（正文格式改过一次，解析方式太脆）。
     * 供设置抽屉那一行回显——不开弹窗也知道有没有记录、最后一条是什么时候。
     */
    public static String latestTimeLabel(Context ctx) {
        if (ctx == null) {
            return null;
        }
        long last = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getLong(KEY_TIME, 0L);
        return last > 0L ? format(last) : null;
    }

    /** 清空日志：删私有目录里的文件、清掉记账时间戳，并把导出目录里的同名副本覆盖成空。纯 IO。 */
    public static void clear(Context ctx) {
        if (ctx == null) {
            return;
        }
        try {
            Files.deleteIfExists(logFile(ctx).toPath());
        } catch (Exception ignored) {
        }
        // 记账时间戳一并清掉：下次记录会重新开一个版本段，抽屉那行也回到「还没有记录」
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .remove(KEY_VERSION)
                .remove(KEY_TIME)
                .apply();
        WallpaperExporter.writeTextFile(ctx, FILE_NAME, "text/plain", "");
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
