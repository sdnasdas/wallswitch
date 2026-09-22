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
 * 壁纸库管理：每个库包含多张壁纸、影响范围（桌面/锁屏，可单选或双选）、
 * 启用开关、秒级切换间隔与切换模式（顺序/随机）。
 * 启用约束（同一范围最多一个启用库）：
 * - 未选择任何范围的库不能启用；
 * - 启用某库时自动停用与之范围重叠的其他启用库（任一方为双范围即视为重叠）。
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
    // 当前选中库 id 的 key
    public static final String KEY_CURRENT_LIB = "current_lib";
    // 切换模式取值
    public static final String MODE_ORDER = "order";
    public static final String MODE_RANDOM = "random";
    // 默认切换间隔（秒）
    public static final int DEFAULT_INTERVAL_SECONDS = 1800;

    /** 壁纸库元数据。 */
    public static class Library {
        public String id;
        public String name;
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

    /** 按 id 查找库，找不到返回 null。 */
    public static Library get(Context ctx, String libId) {
        for (Library lib : load(ctx)) {
            if (lib.id.equals(libId)) {
                return lib;
            }
        }
        return null;
    }

    /** 新建库：默认单选桌面范围、停用、顺序模式；名称留空时自动命名。 */
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

    /** 删除库：连同库内壁纸、切换进度一并清除，并取消其定时任务。 */
    public static void delete(Context ctx, String libId) {
        List<Library> libs = load(ctx);
        List<Library> remain = new ArrayList<>();
        for (Library lib : libs) {
            if (lib.id.equals(libId)) {
                if (lib.enabled) {
                    AlarmScheduler.cancel(ctx, libId);
                }
            } else {
                remain.add(lib);
            }
        }
        saveList(ctx, remain);
        WallpaperStore.deleteByLib(ctx, libId);
        Switcher.clearProgress(ctx, libId);
    }

    /**
     * 启用/停用库（应用范围互斥约束），返回是否成功。
     * 启用时：未选范围返回 false；自动停用范围重叠的其他启用库（任一方双范围即视为重叠）。
     */
    public static boolean setEnabled(Context ctx, String libId, boolean enable) {
        List<Library> libs = load(ctx);
        Library target = null;
        for (Library lib : libs) {
            if (lib.id.equals(libId)) {
                target = lib;
            }
        }
        if (target == null) {
            return false;
        }
        if (enable) {
            if (!target.home && !target.lock) {
                return false;
            }
            for (Library other : libs) {
                if (other.id.equals(libId) || !other.enabled) {
                    continue;
                }
                boolean overlap = (target.home && target.lock) || (other.home && other.lock)
                        || (target.home && other.home) || (target.lock && other.lock);
                if (overlap) {
                    other.enabled = false;
                    AlarmScheduler.cancel(ctx, other.id);
                }
            }
        }
        target.enabled = enable;
        saveList(ctx, libs);
        if (enable) {
            AlarmScheduler.schedule(ctx, libId);
        } else {
            AlarmScheduler.cancel(ctx, libId);
        }
        return true;
    }

    /**
     * 修改库的影响范围。启用中的库：范围清空则自动停用；
     * 新增范围与其他启用库冲突时自动停用对方（规则与 setEnabled 一致）。
     */
    public static void setScope(Context ctx, String libId, boolean home, boolean lock) {
        List<Library> libs = load(ctx);
        Library target = null;
        for (Library lib : libs) {
            if (lib.id.equals(libId)) {
                target = lib;
            }
        }
        if (target == null) {
            return;
        }
        target.home = home;
        target.lock = lock;
        if (target.enabled) {
            if (!home && !lock) {
                target.enabled = false;
                AlarmScheduler.cancel(ctx, libId);
            } else {
                for (Library other : libs) {
                    if (other.id.equals(libId) || !other.enabled) {
                        continue;
                    }
                    boolean overlap = (home && lock) || (other.home && other.lock)
                            || (home && other.home) || (lock && other.lock);
                    if (overlap) {
                        other.enabled = false;
                        AlarmScheduler.cancel(ctx, other.id);
                    }
                }
                AlarmScheduler.schedule(ctx, libId);
            }
        }
        saveList(ctx, libs);
    }

    /** 修改切换间隔（秒），启用中的库立即重排定时。 */
    public static void setInterval(Context ctx, String libId, int seconds) {
        List<Library> libs = load(ctx);
        boolean enabled = false;
        for (Library lib : libs) {
            if (lib.id.equals(libId)) {
                lib.intervalSeconds = Math.max(1, seconds);
                enabled = lib.enabled;
            }
        }
        saveList(ctx, libs);
        if (enabled) {
            AlarmScheduler.schedule(ctx, libId);
        }
    }

    /** 修改切换模式（顺序/随机）。 */
    public static void setMode(Context ctx, String libId, String mode) {
        List<Library> libs = load(ctx);
        for (Library lib : libs) {
            if (lib.id.equals(libId)) {
                lib.mode = mode;
            }
        }
        saveList(ctx, libs);
    }

    /** 返回当前启用的、覆盖对应范围的库（每个范围至多一个），没有则返回 null。 */
    public static Library enabledLibForScope(Context ctx, boolean forHome) {
        for (Library lib : load(ctx)) {
            if (lib.enabled && (forHome ? lib.home : lib.lock)) {
                return lib;
            }
        }
        return null;
    }
}