package com.example.wallswitch;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 壁纸库管理：每个库包含多张壁纸与名称。
 * v3.60 起「谁负责哪个范围」从库属性改为两个**范围槽位**（桌面/锁屏各一）：
 * 槽位记录占位库 id、切换模式与切换间隔，存在 prefs（键见 slotKey），
 * 槽位为空 = 该范围不切换。同一库可同时占两个槽（两范围各自推进进度）。
 * 库对象上的 home/lock/enabled/mode/intervalSeconds 为遗留字段：仅做 JSON 往返
 * 与一次性迁移取值，界面不再提供入口、逻辑不再读取（保证可回滚降级）。
 * 存储：filesDir/libraries.json，元素字段 {"id","name","home","lock","enabled","interval_seconds","mode"}
 * 切换进度按库隔离，键名约定见 Switcher.progressBase。
 */
public class LibraryStore {

    // 库元数据文件名
    private static final String LIBS_FILE = "libraries.json";
    // SharedPreferences 文件名
    private static final String PREFS_NAME = "settings";
    // 旧版数据迁移标记
    private static final String KEY_MIGRATED = "lib_migrated_v2";
    // 范围槽位化迁移标记（老 enabled+范围 → 槽位，一次性）
    private static final String KEY_SCOPE_MIGRATED = "scope_slots_v359";
    // 当前选中库 id 的 key
    public static final String KEY_CURRENT_LIB = "current_lib";
    // 切换模式取值
    public static final String MODE_ORDER = "order";
    public static final String MODE_RANDOM = "random";
    // 默认切换间隔（秒）
    public static final int DEFAULT_INTERVAL_SECONDS = 1800;
    // 最短切换间隔（秒）：WorkManager 省电方案的系统下限为 15 分钟
    public static final int MIN_INTERVAL_SECONDS = 15 * 60;

    /** 壁纸库元数据。 */
    public static class Library {
        public String id;
        public String name;
        // ===== 以下为遗留字段（见类注释），只为 JSON 往返保留 =====
        public boolean home;
        public boolean lock;
        public boolean enabled;
        public int intervalSeconds;
        public String mode;
    }

    /** 读取库列表；文件不存在时创建默认库（双范围、启用），并迁移旧版单库数据。 */
    public static List<Library> load(Context ctx) {
        List<Library> libs = new ArrayList<>();
        File file = new File(ctx.getFilesDir(), LIBS_FILE);
        if (file.exists()) {
            try {
                byte[] bytes = Files.readAllBytes(file.toPath());
                JSONArray arr = new JSONArray(new String(bytes, "UTF-8"));
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    Library lib = new Library();
                    lib.id = o.getString("id");
                    lib.name = o.optString("name", "库");
                    lib.home = o.optBoolean("home", true);
                    lib.lock = o.optBoolean("lock", false);
                    lib.enabled = o.optBoolean("enabled", false);
                    lib.intervalSeconds = o.optInt("interval_seconds", DEFAULT_INTERVAL_SECONDS);
                    lib.mode = o.optString("mode", MODE_ORDER);
                    libs.add(lib);
                }
            } catch (Exception ignored) {
            }
            migrateSlotsIfNeeded(ctx, libs);
            return libs;
        }
        // 首次运行：创建默认库并迁移旧版数据
        Library def = new Library();
        def.id = UUID.randomUUID().toString();
        def.name = "默认库";
        def.home = true;
        def.lock = true;
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        def.enabled = prefs.getBoolean("home_enabled", true) || prefs.getBoolean("lock_enabled", true);
        def.intervalSeconds = DEFAULT_INTERVAL_SECONDS;
        def.mode = prefs.getString("mode", MODE_ORDER);
        libs.add(def);
        saveList(ctx, libs);
        if (!prefs.getBoolean(KEY_MIGRATED, false)) {
            migrateProgress(prefs, def.id);
            WallpaperStore.migrateLegacyToLib(ctx, def.id);
            prefs.edit().putBoolean(KEY_MIGRATED, true).apply();
        }
        migrateSlotsIfNeeded(ctx, libs);
        return libs;
    }

    /** 把旧版全局进度键（home_seq 等）复制到默认库的按库进度键下。 */
    private static void migrateProgress(SharedPreferences prefs, String libId) {
        SharedPreferences.Editor editor = prefs.edit();
        String homeBase = Switcher.progressBase(libId, true);
        String lockBase = Switcher.progressBase(libId, false);
        if (prefs.contains("home_seq")) {
            editor.putInt(homeBase + "_seq", prefs.getInt("home_seq", -1));
        }
        if (prefs.contains("home_pool")) {
            editor.putString(homeBase + "_pool", prefs.getString("home_pool", "[]"));
        }
        if (prefs.contains("home_current")) {
            editor.putString(homeBase + "_current", prefs.getString("home_current", null));
        }
        if (prefs.contains("lock_seq")) {
            editor.putInt(lockBase + "_seq", prefs.getInt("lock_seq", -1));
        }
        if (prefs.contains("lock_pool")) {
            editor.putString(lockBase + "_pool", prefs.getString("lock_pool", "[]"));
        }
        if (prefs.contains("lock_current")) {
            editor.putString(lockBase + "_current", prefs.getString("lock_current", null));
        }
        editor.apply();
    }

    /**
     * 范围槽位化一次性迁移（v3.60）：槽位为空时按老「启用库 + 覆盖范围」填上，
     * 并把该库的模式/间隔抄进范围配置（体验无缝衔接）；
     * 顺带取消遗留的按库命名周期任务、按范围重新排定。
     */
    private static void migrateSlotsIfNeeded(Context ctx, List<Library> libs) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (prefs.getBoolean(KEY_SCOPE_MIGRATED, false)) {
            return;
        }
        SharedPreferences.Editor editor = prefs.edit();
        for (boolean forHome : new boolean[]{true, false}) {
            String key = slotKey(forHome);
            if (prefs.getString(key + "_lib", null) != null) {
                continue;
            }
            for (Library lib : libs) {
                if (lib.enabled && (forHome ? lib.home : lib.lock)) {
                    editor.putString(key + "_lib", lib.id);
                    editor.putString(key + "_mode",
                            MODE_RANDOM.equals(lib.mode) ? MODE_RANDOM : MODE_ORDER);
                    editor.putInt(key + "_interval", clampInterval(
                            lib.intervalSeconds > 0 ? lib.intervalSeconds : DEFAULT_INTERVAL_SECONDS));
                    break;
                }
            }
        }
        editor.putBoolean(KEY_SCOPE_MIGRATED, true).apply();
        for (Library lib : libs) {
            TimerScheduler.cancelLegacyLibWork(ctx, lib.id);
        }
        TimerScheduler.scheduleScope(ctx, true);
        TimerScheduler.scheduleScope(ctx, false);
    }

    /** 把库列表写回 libraries.json。 */
    public static void saveList(Context ctx, List<Library> libs) {
        try {
            JSONArray arr = new JSONArray();
            for (Library lib : libs) {
                JSONObject o = new JSONObject();
                o.put("id", lib.id);
                o.put("name", lib.name);
                o.put("home", lib.home);
                o.put("lock", lib.lock);
                o.put("enabled", lib.enabled);
                o.put("interval_seconds", lib.intervalSeconds);
                o.put("mode", lib.mode);
                arr.put(o);
            }
            File file = new File(ctx.getFilesDir(), LIBS_FILE);
            Files.write(file.toPath(), arr.toString().getBytes("UTF-8"));
        } catch (Exception ignored) {
        }
    }

    /** 按 id 顺序重排库列表（首页拖拽排序落位后调用）。从磁盘重读再排，避免用界面上的旧快照覆盖掉新状态。 */
    public static void reorder(Context ctx, List<String> idsInOrder) {
        List<Library> libs = load(ctx);
        List<Library> result = new ArrayList<>();
        for (String id : idsInOrder) {
            for (Library lib : libs) {
                if (lib.id.equals(id)) {
                    result.add(lib);
                    break;
                }
            }
        }
        // 磁盘上新出现的库（理论上不该有）按原相对顺序排到末尾，保证不丢库
        for (Library lib : libs) {
            if (!idsInOrder.contains(lib.id)) {
                result.add(lib);
            }
        }
        saveList(ctx, result);
    }

    /** 按 id 查找库，找不到返回 null。 */
    public static Library get(Context ctx, String libId) {
        for (Library lib : load(ctx)) {
            if (lib.id.equals(libId)) {
                return lib;
            }
        }
        return null;
    }

    /** 新建库：名称留空时自动命名；不再带范围/启用属性。 */
    public static Library create(Context ctx, String name) {
        List<Library> libs = load(ctx);
        Library lib = new Library();
        lib.id = UUID.randomUUID().toString();
        lib.name = (name == null || name.trim().isEmpty()) ? ("库" + (libs.size() + 1)) : name.trim();
        lib.home = true;
        lib.lock = false;
        lib.enabled = false;
        lib.intervalSeconds = DEFAULT_INTERVAL_SECONDS;
        lib.mode = MODE_ORDER;
        libs.add(lib);
        saveList(ctx, libs);
        return lib;
    }

    /** 删除库：连同库内壁纸、切换进度一并清除；占了槽位的腾出槽位（内部会重排该范围定时）。 */
    public static void delete(Context ctx, String libId) {
        List<Library> libs = load(ctx);
        List<Library> remain = new ArrayList<>();
        for (Library lib : libs) {
            if (!lib.id.equals(libId)) {
                remain.add(lib);
            }
        }
        saveList(ctx, remain);
        WallpaperStore.deleteByLib(ctx, libId);
        Switcher.clearProgress(ctx, libId);
        for (boolean forHome : new boolean[]{true, false}) {
            if (ownsScope(ctx, libId, forHome)) {
                setSlotLib(ctx, forHome, null);
            }
        }
        TimerScheduler.cancelLegacyLibWork(ctx, libId);
    }

    // ==================== 范围槽位（v3.60） ====================

    /** 槽位 prefs 键前缀：_lib 占位库、_mode 切换模式、_interval 切换间隔（秒）。 */
    private static String slotKey(boolean forHome) {
        return forHome ? "slot_home" : "slot_lock";
    }

    private static int clampInterval(int seconds) {
        return Math.max(MIN_INTERVAL_SECONDS, seconds);
    }

    /** 该范围槽位占位库 id；未设置返回 null（该范围不切换）。 */
    public static String slotLibId(Context ctx, boolean forHome) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(slotKey(forHome) + "_lib", null);
    }

    /** 该范围槽位的占位库（按 id 现查）；未设置或库已删返回 null。 */
    public static Library slotLib(Context ctx, boolean forHome) {
        String id = slotLibId(ctx, forHome);
        return id == null ? null : get(ctx, id);
    }

    /** 该库是否占着指定范围（所有切换链路的准入判断）。 */
    public static boolean ownsScope(Context ctx, String libId, boolean forHome) {
        String id = slotLibId(ctx, forHome);
        return id != null && id.equals(libId);
    }

    /** 该范围占位库的切换模式（未设置返回顺序）。 */
    public static String scopeMode(Context ctx, boolean forHome) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(slotKey(forHome) + "_mode", MODE_ORDER);
    }

    /** 改该范围的切换模式（顺序/随机）。模式不在重排指纹里，所以格子不动、下次触发时刻不变。 */
    public static void setScopeMode(Context ctx, boolean forHome, String mode) {
        String normalized = MODE_RANDOM.equals(mode) ? MODE_RANDOM : MODE_ORDER;
        if (normalized.equals(scopeMode(ctx, forHome))) {
            return;
        }
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(slotKey(forHome) + "_mode", normalized)
                .apply();
        SwitchLog.recordModeChange(ctx, forHome,
                ctx.getString(MODE_RANDOM.equals(normalized) ? R.string.mode_random : R.string.mode_order),
                TimerScheduler.scopeTrigger(ctx, forHome));
    }

    /** 该范围占位库的切换间隔（秒，下限 15 分钟）。 */
    public static int scopeIntervalSeconds(Context ctx, boolean forHome) {
        return clampInterval(ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(slotKey(forHome) + "_interval", DEFAULT_INTERVAL_SECONDS));
    }

    /**
     * 改该范围的切换间隔（秒，下限 15 分钟），立即重排该范围定时。
     * 有库时按「从现在重新起算」处理：当场写 last_run / next_trigger 再强制 UPDATE。
     * 以前只排任务、把这本账留给随后那次异步的 WorkManager 查询去挪，查询一失败旧时刻就留在
     * 原地，下一次补切会照着它多切一张（刚改完间隔转眼又被切掉）。
     */
    public static void setScopeInterval(Context ctx, boolean forHome, int seconds) {
        int clamped = clampInterval(seconds);
        int oldSeconds = scopeIntervalSeconds(ctx, forHome);
        if (clamped == oldSeconds) {
            // 滚轮点了确定但没动值：不该白重排一轮，也不该在日志里插一条假标记
            return;
        }
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putInt(slotKey(forHome) + "_interval", clamped)
                .apply();
        String libId = slotLibId(ctx, forHome);
        if (libId == null || libId.isEmpty()) {
            TimerScheduler.scheduleScope(ctx, forHome);
            return;
        }
        long next = TimerScheduler.restartScope(ctx, forHome, null);
        SwitchLog.recordIntervalChange(ctx, forHome, oldSeconds, clamped, next);
    }

    /**
     * 该范围是否暂停自动切换：屏上这张停住，周期任务整个撤掉（暂停期间零唤醒）。
     * 只影响定时，不影响手动切换 —— 暂停中手动切一张 = 换一张继续停着。
     */
    public static boolean slotPaused(Context ctx, boolean forHome) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(slotKey(forHome) + "_paused", false);
    }

    /** 写暂停标记。撤任务/重新起算与日志都在 TimerScheduler.setPaused 里收口。 */
    public static void setSlotPaused(Context ctx, boolean forHome, boolean paused) {
        SharedPreferences.Editor editor = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit();
        if (paused) {
            editor.putBoolean(slotKey(forHome) + "_paused", true);
        } else {
            editor.remove(slotKey(forHome) + "_paused");
        }
        editor.apply();
    }

    /**
     * 让某库占上该范围（libId 传 null/空 = 清空，该范围停止切换）。
     * 空槽首次占位时沿用该库的库级模式/间隔（老数据体验衔接）；换占时保留该范围已有配置。
     * 变更后重排该范围定时并刷新小组件/常驻通知。
     */
    public static void setSlotLib(Context ctx, boolean forHome, String libId) {
        String trimmed = libId == null ? "" : libId.trim();
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        if (trimmed.isEmpty()) {
            editor.remove(slotKey(forHome) + "_lib");
        } else {
            boolean wasEmpty = prefs.getString(slotKey(forHome) + "_lib", null) == null;
            editor.putString(slotKey(forHome) + "_lib", trimmed);
            if (wasEmpty) {
                Library lib = get(ctx, trimmed);
                if (lib != null) {
                    editor.putString(slotKey(forHome) + "_mode",
                            MODE_RANDOM.equals(lib.mode) ? MODE_RANDOM : MODE_ORDER);
                    editor.putInt(slotKey(forHome) + "_interval", clampInterval(
                            lib.intervalSeconds > 0 ? lib.intervalSeconds : DEFAULT_INTERVAL_SECONDS));
                }
            }
        }
        editor.apply();
        // 两条分支各自的重排入口（cancelScope / restartScope）内部都会刷小组件与常驻通知，这里不再重复刷
        if (trimmed.isEmpty()) {
            TimerScheduler.cancelScope(ctx, forHome);
            SwitchLog.recordStopped(ctx, forHome);
            // 停了就没有"本轮计时"：抹掉日志的起算点，免得以后重新占库去跟几天前那条比间隔
            SwitchLog.resetAnchor(ctx, forHome);
        } else {
            // 换库=该范围换了一张图（上屏由调用方的接管同步去做），所以按「重新起算」同一本账处理；
            // 这一刻新库的指针还没落地，日志里只标库名、不标壁纸标题
            long next = TimerScheduler.restartScope(ctx, forHome, null);
            Library target = get(ctx, trimmed);
            SwitchLog.recordSwitchLib(ctx, forHome,
                    target == null ? "" : target.name, next);
        }
    }

    /** 重命名库（库行点库名就地改名的写回入口；留空则保留原名）。 */
    public static void setName(Context ctx, String libId, String name) {
        List<Library> libs = load(ctx);
        for (Library lib : libs) {
            if (lib.id.equals(libId)) {
                String trimmed = name == null ? "" : name.trim();
                if (!trimmed.isEmpty()) {
                    lib.name = trimmed;
                }
            }
        }
        saveList(ctx, libs);
    }
}
