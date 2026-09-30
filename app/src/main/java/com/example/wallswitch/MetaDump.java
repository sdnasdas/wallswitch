package com.example.wallswitch;

import android.content.Context;
import android.os.Build;
import android.util.DisplayMetrics;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 把「一次性要交出去排查」的那几份元数据原样复制到不需要 root 就能取的地方。
 *
 * 为什么落两处：
 * - App 专属外部目录（Android/data/包名/files/...）：文件管理器直接能翻到，不需要任何运行时权限，
 *   也不需要事先设过导出目录；注意这目录会随卸载被删，所以它只是"取出来"的通道，不是持久化。
 * - SAF 导出目录：用户已经选过目录时顺手也放一份，方便直接从相册/文件 App 分享出来。
 *
 * 只复制不改动：全部按原字节读出来写出去，绝不解析、绝不重排 —— 这份东西的价值就在于
 * 它是手机上此刻真实落盘的那份（含可能的半截状态）。
 */
public final class MetaDump {

    // 落在 App 专属外部目录下的这层，文件管理器里一眼能找到
    private static final String SUB_DIR = "wallswitch-meta";
    private static final String STAMP_PREFS = "meta_dump_seq";

    private MetaDump() {
    }

    /** 一份导出结果：落点、成功复制的文件、失败/缺失的说明。 */
    public static final class Result {
        public String dirPath;
        // 落点目录名：文件管理器里从「Android/data/包名/files」进去就是这一层，Toast 里报这个短名
        public String dirName = SUB_DIR;
        public final List<String> written = new ArrayList<>();
        public final List<String> missing = new ArrayList<>();
        public boolean treeCopyOk;
        public String treeNote;
    }

    /**
     * 走一遍导出。纯 IO + 文件复制，调用方必须放后台线程。
     * shared_prefs 里的 xml 不在这里复制（见 {@link #prefsPaths}，那些文件由系统持有、随时可能被回写）。
     */
    public static Result run(Context context) {
        Result r = new Result();
        File base = context.getExternalFilesDir(SUB_DIR);
        if (base == null || (!base.exists() && !base.mkdirs())) {
            r.treeNote = "外部专属目录不可用（getExternalFilesDir 返回空）";
            return r;
        }
        r.dirPath = base.getAbsolutePath();

        copyInto(context, new File(context.getFilesDir(), "library.json"), base, r);
        copyInto(context, new File(context.getFilesDir(), "libraries.json"), base, r);
        copyInto(context, LauncherPreviewOverlay.overlayFile(context), base, r);
        copyInto(context, SwitchLog.logFile(context, true), base, r);
        copyInto(context, SwitchLog.logFile(context, false), base, r);
        writeManifest(context, base, r);

        if (WallpaperExporter.isConfigured(context)) {
            int ok = 0, total = 0;
            for (File f : listOfFiles(base)) {
                if (!f.isFile()) {
                    continue;
                }
                total++;
                if (WallpaperExporter.exportNamedFile(context, f)) {
                    ok++;
                }
            }
            r.treeCopyOk = total > 0 && ok == total;
            r.treeNote = "导出目录「" + WallpaperExporter.displayName(context) + "」里放了 " + ok + "/" + total + " 份";
        } else {
            r.treeNote = "没设导出目录，只在外部专属目录里（文件管理器能翻到）";
        }
        return r;
    }

    /**
     * settings.xml / wallswitch.xml 的真身路径，只写进 manifest 供人工确认，不复制：
     * 系统随时可能把内存里的偏好回写覆盖它，从外面拷一份没有"此刻状态"的保证。
     */
    public static String prefsPaths(Context context) {
        return new File(new File(context.getFilesDir().getParentFile(), "shared_prefs"), "settings.xml")
                .getAbsolutePath() + " , "
                + new File(new File(context.getFilesDir().getParentFile(), "shared_prefs"), "wallswitch.xml")
                .getAbsolutePath();
    }

    private static void copyInto(Context context, File src, File dstDir, Result r) {
        if (src == null || !src.exists()) {
            r.missing.add(src == null ? "null" : src.getName());
            return;
        }
        File dst = new File(dstDir, src.getName());
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            out.flush();
            r.written.add(src.getName() + " (" + src.length() + "B)");
        } catch (Exception e) {
            r.missing.add(src.getName() + " 复制失败:" + e.getClass().getSimpleName());
        }
    }

    /** 一份现场快照：版本、机器、每个文件的大小与 mtime，用于对上是哪一版、什么时候写的。 */
    private static void writeManifest(Context context, File dstDir, Result r) {
        JSONObject o = new JSONObject();
        try {
            o.put("dumped_at", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
            o.put("seq", bumpSeq(context));
            o.put("app_version", versionName(context));
            o.put("model", Build.MANUFACTURER + " " + Build.MODEL);
            o.put("android", Build.VERSION.SDK_INT + " / " + Build.VERSION.RELEASE);
            DisplayMetrics dm = context.getResources().getDisplayMetrics();
            o.put("screen", dm.widthPixels + "x" + dm.heightPixels + " @" + dm.densityDpi);
            o.put("thumb_side", WallpaperStore.thumbSide(context));
            o.put("files_dir", context.getFilesDir().getAbsolutePath());
            o.put("prefs_paths", prefsPaths(context));
            JSONArray files = new JSONArray();
            for (File f : listOfFiles(dstDir)) {
                if (!f.isFile() || f.getName().equals("manifest.json")) {
                    continue;
                }
                try {
                    JSONObject one = new JSONObject();
                    one.put("name", f.getName());
                    one.put("bytes", f.length());
                    one.put("mtime", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                            .format(new Date(f.lastModified())));
                    files.put(one);
                } catch (Exception ignored) {
                }
            }
            o.put("files", files);
            o.put("library_items", WallpaperStore.load(context).size());
            o.put("libraries", LibraryStore.load(context).size());
            File dst = new File(dstDir, "manifest.json");
            try (OutputStream out = new FileOutputStream(dst)) {
                out.write(o.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            r.written.add("manifest.json (" + dst.length() + "B)");
        } catch (Exception e) {
            r.missing.add("manifest.json 写入失败:" + e.getClass().getSimpleName());
        }
    }

    private static File[] listOfFiles(File dir) {
        File[] listed = dir == null ? null : dir.listFiles();
        return listed == null ? new File[0] : listed;
    }

    private static int bumpSeq(Context context) {
        android.content.SharedPreferences prefs =
                context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        int next = prefs.getInt(STAMP_PREFS, 0) + 1;
        prefs.edit().putInt(STAMP_PREFS, next).apply();
        return next;
    }

    private static String versionName(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }
}
