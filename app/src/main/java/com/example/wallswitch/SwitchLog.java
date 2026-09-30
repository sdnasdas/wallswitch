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
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * 切换日志（桌面、锁屏各一份文件，各记各的账）。
 *
 * 三个数各管一件事，别再互相顶包：
 * <ul>
 *   <li><b>间隔</b> = 距上一条「重置过计时」的记录过了多少分钟。重置计时的有：任何一次上屏
 *       （自动/手动/换库）、改间隔那一次重排；不重置的有：改模式（格子没动）、清空（那是终止）。</li>
 *   <li><b>误差</b> = 实际执行时刻 − 当时排定的下次触发时刻（就是小组件上那个「预计」）。只有自动那条算。</li>
 *   <li><b>下次约</b> = 这次操作重排之后的 now + 间隔。手动、换库、改间隔、改模式四类行带。</li>
 * </ul>
 * 以前两个范围共用一个「上一条时间」，于是桌面刚记一条、锁屏紧接着记一条时，锁屏那行会被算成
 * 「间隔 1 分钟」——看着像 1 分钟切了一张，其实只是日志串了范围（v3.10 起就是这样，不是调度问题）。
 *
 * 格式：一条一行，段头讲清版本/范围/设置/模式，换天时插一行日期，配置变更当场插一行标记：
 * <pre>
 * === v3.67 · 桌面 · 每 60 分钟 · 随机切换 ===
 *
 * 2026-09-30
 *   01:35  雪山  （间隔 16分 · 手动 · 下次约 02:35）
 *   02:38  海岸  （间隔 63分，误差 3分）
 *   03:00  —— 间隔 60→90 分钟 · 距上张走了 22 分 · 下次约 04:30 ——
 *   04:33  林间  （间隔 93分，误差 3分）
 *   05:40  —— 换库「自然」· 距上张走了 67 分 · 下次约 07:10 ——
 *   05:52  —— 改为顺序切换 · 下次约 07:10 不变 ——
 *   07:14  溪谷  （间隔 94分，误差 4分）
 *   07:20  —— 停：桌面槽位已清空 ——
 * </pre>
 * 每个版本段的第一条不带括号（那会儿没有可比的上一条）。
 *
 * 文件写在应用私有目录 {@code files/switch_log_home.txt} / {@code switch_log_lock.txt}；
 * 设了导出目录就同名各同步一份。
 */
public final class SwitchLog {

    private static final String PREFS_NAME = "settings";
    // 以下三套键都按范围加 _h/_l 后缀（与调度侧 last_run_/next_trigger_ 的后缀口径一致）
    private static final String KEY_VERSION_PREFIX = "switch_log_version_";
    // 上一条正文记录的时间：管空行与日期行，也是标记行里「距上张走了多少分」的基准
    private static final String KEY_TIME_PREFIX = "switch_log_time_";
    // 本轮计时的起算点：只有上屏和改间隔重排会推进它
    private static final String KEY_ANCHOR_PREFIX = "switch_log_anchor_";
    // v3.67 及以前两个范围共用的一套，拆开后没人读，只在清空时一并处理
    private static final String LEGACY_VERSION = "switch_log_version";
    private static final String LEGACY_TIME = "switch_log_time";

    private static final String FILE_HOME = "switch_log_home.txt";
    private static final String FILE_LOCK = "switch_log_lock.txt";
    private static final String FILE_LEGACY = "switch_log.txt";

    /** 主线程也能调的那几个写入（手动/换库/配置标记）都排到这里，界面不等文件 IO。 */
    private static final Executor EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "switch-log");
        thread.setDaemon(true);
        return thread;
    });

    private SwitchLog() {
    }

    private static String suffix(boolean forHome) {
        return forHome ? "_h" : "_l";
    }

    /** 某范围的日志文件名（打包/还原按这个名字取）。 */
    public static String fileName(boolean forHome) {
        return forHome ? FILE_HOME : FILE_LOCK;
    }

    /** 某范围的日志文件（应用私有目录）。 */
    public static File logFile(Context ctx, boolean forHome) {
        return new File(ctx.getFilesDir(), fileName(forHome));
    }

    /**
     * 记一次「定时自动切换」成功。同步写文件：调用方本来就在 Worker/补切的后台线程里，
     * 排队写会在进程被回收时丢掉刚发生的这一条。
     *
     * @param detail          这次切到哪张的可读描述（见 TimerScheduler.switchDetail）
     * @param plannedMillis   这次切换之前排定的触发时刻（覆盖 next_trigger 前先读出来），无记录传 -1
     */
    public static void recordAuto(Context ctx, boolean forHome, String detail, long plannedMillis) {
        if (ctx == null) {
            return;
        }
        long now = System.currentTimeMillis();
        String parens = "";
        if (!firstEntry(ctx, forHome)) {
            long gap = minutesBetween(anchor(ctx, forHome), now);
            if (plannedMillis > 0L) {
                long drift = Math.round((now - plannedMillis) / 60000.0);
                parens = ctx.getString(R.string.log_suffix_auto, gap, drift);
            } else {
                parens = ctx.getString(R.string.log_suffix_gap_only, gap);
            }
        }
        write(ctx, forHome, now, entryLine(ctx, now, detail, parens), true);
    }

    /**
     * 记一次「用户主动把一张新图弄上了屏」：卡片双击、通知的上一张/下一张、小组件点按、
     * 长按设为主页、删掉屏上那张后自动推进的那一次（都收口在 TimerScheduler.restartScope）。
     * 异步写，主线程调用安全。
     *
     * @param tag             这一条是什么（「手动」），已由调用方取好文案
     * @param nextTriggerMillis 重排后的下次触发时刻，用于「下次约」
     */
    public static void recordManual(Context ctx, boolean forHome, String detail,
                                    String tag, long nextTriggerMillis) {
        if (ctx == null) {
            return;
        }
        final long now = System.currentTimeMillis();
        final Context app = ctx.getApplicationContext();
        EXECUTOR.execute(() -> {
            String gapPart = firstEntry(app, forHome)
                    ? "" : app.getString(R.string.log_gap_part, minutesBetween(anchor(app, forHome), now));
            String parens = nextTriggerMillis > 0L
                    ? app.getString(R.string.log_suffix_manual,
                            gapPart, tag, TimerScheduler.clockText(app, nextTriggerMillis))
                    // 暂停中手动切一张：没有「下次」可承诺（任务已撤），只标这一张停在这儿
                    : app.getString(R.string.log_suffix_manual_paused, gapPart, tag);
            write(app, forHome, now, entryLine(app, now, detail, parens), true);
        });
    }

    /** 记一行「改了切换间隔」：它重排了定时，所以本轮起算点挪到此刻。异步写。 */
    public static void recordIntervalChange(Context ctx, boolean forHome,
                                            int oldSeconds, int newSeconds, long nextTriggerMillis) {
        recordConfig(ctx, forHome, true, (c, prevTime, now) ->
                c.getString(R.string.log_config_interval, oldSeconds / 60, newSeconds / 60,
                        walkedPart(c, prevTime, now), TimerScheduler.clockText(c, nextTriggerMillis)));
    }

    /** 记一行「改了切换模式」：格子没动，所以不重排、起算点也不挪。异步写。 */
    public static void recordModeChange(Context ctx, boolean forHome, String modeText,
                                        long nextTriggerMillis) {
        recordConfig(ctx, forHome, false, (c, prevTime, now) ->
                nextTriggerMillis > 0L
                        ? c.getString(R.string.log_config_mode, modeText,
                                TimerScheduler.clockText(c, nextTriggerMillis))
                        : c.getString(R.string.log_config_mode_no_next, modeText));
    }

    /**
     * 记一行「该范围换了库」。它和手动切换一样把计时重新起算（所以 reanchor），但这一刻新库
     * 还没真的上屏（接管同步在另一个线程里跑），所以只写库名、不写壁纸标题。异步写。
     */
    public static void recordSwitchLib(Context ctx, boolean forHome, String libName,
                                       long nextTriggerMillis) {
        recordConfig(ctx, forHome, true, (c, prevTime, now) ->
                c.getString(R.string.log_config_switch_lib, libName,
                        walkedPart(c, prevTime, now),
                        TimerScheduler.clockText(c, nextTriggerMillis)));
    }

    /** 记一行「该范围停了」（槽位被清空）。异步写。 */
    public static void recordStopped(Context ctx, boolean forHome) {
        recordConfig(ctx, forHome, false, (c, prevTime, now) ->
                c.getString(R.string.log_config_stopped,
                        c.getString(forHome ? R.string.scope_home : R.string.scope_lock)));
    }

    /** 记一行「这一面暂停了」：任务撤掉、屏上这张停住，所以不挪计时起点。异步写。 */
    public static void recordPaused(Context ctx, boolean forHome, String title) {
        recordConfig(ctx, forHome, false, (c, prevTime, now) ->
                c.getString(R.string.log_config_paused, title));
    }

    /** 记一行「继续自动切换」：从这一刻重新起算，所以挪计时起点。异步写。 */
    public static void recordResumed(Context ctx, boolean forHome, long nextTriggerMillis) {
        recordConfig(ctx, forHome, true, (c, prevTime, now) ->
                c.getString(R.string.log_config_resumed,
                        TimerScheduler.clockText(c, nextTriggerMillis)));
    }

    /** 标记行里的「距上张走了多少分」；这一面还没有过记录（刚清空/刚装）时整段不写，免得冒 0 分。 */
    private static String walkedPart(Context ctx, long prevTime, long now) {
        return prevTime <= 0L
                ? "" : ctx.getString(R.string.log_gap_walked, minutesBetween(prevTime, now));
    }

    /** 配置标记行的公共流程：拼文案 → 落文件；reanchor 见 {@link #write}。 */
    private interface ConfigText {
        String build(Context ctx, long prevTime, long now);
    }

    private static void recordConfig(Context ctx, boolean forHome, boolean reanchor, ConfigText text) {
        if (ctx == null) {
            return;
        }
        final long now = System.currentTimeMillis();
        final Context app = ctx.getApplicationContext();
        EXECUTOR.execute(() -> {
            long prevTime = prefs(app).getLong(KEY_TIME_PREFIX + suffix(forHome), 0L);
            String body = app.getString(R.string.log_config_line, timeOf(now), text.build(app, prevTime, now));
            write(app, forHome, now, body, reanchor);
        });
    }

    /** 该范围还有没有可比的前一条：版本段第一条、或清空过（起算点被抹掉）都没有。 */
    private static boolean firstEntry(Context ctx, boolean forHome) {
        SharedPreferences prefs = prefs(ctx);
        String suffix = suffix(forHome);
        return !appVersion(ctx).equals(prefs.getString(KEY_VERSION_PREFIX + suffix, null))
                || prefs.getLong(KEY_ANCHOR_PREFIX + suffix, 0L) <= 0L;
    }

    private static long anchor(Context ctx, boolean forHome) {
        return prefs(ctx).getLong(KEY_ANCHOR_PREFIX + suffix(forHome), 0L);
    }

    /**
     * 抹掉某范围的计时起点：该范围停了（槽位清空），下次再上屏时不该跟几天前那条比间隔。
     * 纯 prefs 写，任何线程可调。
     */
    public static void resetAnchor(Context ctx, boolean forHome) {
        if (ctx == null) {
            return;
        }
        prefs(ctx).edit().remove(KEY_ANCHOR_PREFIX + suffix(forHome)).apply();
    }

    /** 落一行正文：需要时补空行 + 版本段头 + 日期行，写完推进记账时间并按需同步导出目录。 */
    private static void write(Context ctx, boolean forHome, long now, String body, boolean reanchor) {
        SharedPreferences prefs = prefs(ctx);
        String suffix = suffix(forHome);
        String version = appVersion(ctx);
        boolean newBlock = !version.equals(prefs.getString(KEY_VERSION_PREFIX + suffix, null));
        long lastTime = prefs.getLong(KEY_TIME_PREFIX + suffix, 0L);
        File file = logFile(ctx, forHome);

        StringBuilder sb = new StringBuilder();
        if (newBlock) {
            if (file.exists() && file.length() > 0) {
                sb.append('\n');
            }
            sb.append(ctx.getString(R.string.log_block_header, version,
                    ctx.getString(forHome ? R.string.scope_home : R.string.scope_lock),
                    Math.max(1, LibraryStore.scopeIntervalSeconds(ctx, forHome) / 60),
                    ctx.getString(LibraryStore.MODE_RANDOM.equals(LibraryStore.scopeMode(ctx, forHome))
                            ? R.string.mode_random : R.string.mode_order))).append('\n');
            sb.append('\n').append(dateOf(now)).append('\n');
        } else if (lastTime <= 0L || !sameDay(lastTime, now)) {
            sb.append('\n').append(dateOf(now)).append('\n');
        }
        sb.append(body).append('\n');

        append(file, sb.toString());
        SharedPreferences.Editor editor = prefs.edit()
                .putString(KEY_VERSION_PREFIX + suffix, version)
                .putLong(KEY_TIME_PREFIX + suffix, now);
        if (reanchor) {
            editor.putLong(KEY_ANCHOR_PREFIX + suffix, now);
        }
        editor.apply();
        // 设了导出目录就同名同步一份；没设则 writeTextFile 自己会直接返回
        WallpaperExporter.writeTextFile(ctx, fileName(forHome), "text/plain", readAllText(ctx, forHome));
    }

    private static String entryLine(Context ctx, long now, String detail, String parens) {
        return ctx.getString(R.string.log_entry_line, timeOf(now), detail, parens);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static long minutesBetween(long from, long to) {
        return from <= 0L ? 0L : Math.max(0L, Math.round((to - from) / 60000.0));
    }

    private static boolean sameDay(long a, long b) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date(a))
                .equals(new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date(b)));
    }

    private static String dateOf(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date(millis));
    }

    private static String timeOf(long millis) {
        return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(millis));
    }

    /** 某范围日志全文（没有或读不到返回空串）。纯 IO，调用方放后台线程。 */
    public static String readAllText(Context ctx, boolean forHome) {
        try {
            return new String(Files.readAllBytes(logFile(ctx, forHome).toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 某范围最近一条记录的时间（MM-dd HH:mm），没有则 null。取记账偏好值，不去正文里截字符串。
     * 纯读，调用方放后台线程。
     */
    public static String latestTimeLabel(Context ctx, boolean forHome) {
        if (ctx == null) {
            return null;
        }
        long last = prefs(ctx).getLong(KEY_TIME_PREFIX + suffix(forHome), 0L);
        return last > 0L
                ? new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(new Date(last))
                : null;
    }

    /**
     * 清空两个范围的日志：私有目录两份文件、三套按范围键，外加 v3.67 及以前那份共用的
     * {@code switch_log.txt} 和它的两个键（拆开后没人再读写，留着只会让人以为还有记录）。
     * 导出目录里的同名副本一并覆盖成空。纯 IO。
     */
    public static void clear(Context ctx) {
        if (ctx == null) {
            return;
        }
        for (String name : new String[]{FILE_HOME, FILE_LOCK, FILE_LEGACY}) {
            try {
                Files.deleteIfExists(new File(ctx.getFilesDir(), name).toPath());
            } catch (Exception ignored) {
            }
            WallpaperExporter.writeTextFile(ctx, name, "text/plain", "");
        }
        SharedPreferences.Editor editor = prefs(ctx).edit();
        for (boolean forHome : new boolean[]{true, false}) {
            String suffix = suffix(forHome);
            editor.remove(KEY_VERSION_PREFIX + suffix)
                    .remove(KEY_TIME_PREFIX + suffix)
                    .remove(KEY_ANCHOR_PREFIX + suffix);
        }
        editor.remove(LEGACY_VERSION).remove(LEGACY_TIME).apply();
    }

    private static void append(File file, String text) {
        try (OutputStream out = new FileOutputStream(file, true)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
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
