package io.github.sdnasdas.wallswitch;

import android.content.ContentUris;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 整库备份包：把「重装后能原样接回来」需要的所有东西打成一个 zip，放进用户选定的导出目录（SAF）。
 *
 * 为什么必须打成一个包而不是散着复制：卸载后唯一存活的是图片本体，而 id、库归属、标题、
 * 槽位分配、间隔、定时锚点全在 filesDir 的两个 json 与 shared_prefs 里 —— 只复制图片的导出，
 * 重装后 App 一样不认。
 *
 * 包里有什么（对应「卸载即丢」的那几样，逐项查实）：
 * - prefs/settings.xml            槽位→库、间隔、锚点、暂停标记、当前指针与历史、配色、各类开关、更新源地址…
 *   prefs/wallswitch.xml          检查更新的状态
 * - library.json / libraries.json 每张壁纸的 id/库/标题、库列表本身
 * - wallpapers/*.{png,jpg}        成品图原始字节（不解码不重压）
 * - originals/*                 原图原始字节：重复调整的源。v3.8x 之前的包没这条，还原时跳过
 * - thumbs/*.jpg                  缩略图：派生数据但一定要带 —— 缺了的话冷路径在 UI 线程解全图
 *                                  （onBindViewHolder → thumbFor → getThumb），还原后第一次进列表是一帧冻几百毫秒
 * - launcher_overlay.png          桌面图标预览底图，用户自制，重造不出来
 * - switch_log_home/_lock.txt     切换日志
 * - manifest.json                 版本、时间、张数、字节数，还原前回显用
 * 不带：inbox/（半成品）、cache/、databases/（WorkManager 的内部表，不在我们手里，靠槽位+锚点重排）。
 *
 * zip 条目走 DEFLATED（ZipOutputStream 的默认）：PNG 那份再压只差 2~5%，但换掉了
 * 「STORE 必须预先填 size + crc」这条要求 —— 那意味着每张图要多读一整遍才能算出 CRC。
 * （JPEG 那份本来就压不动，多花的那点 CPU 只是白转一圈，不亏体积。）
 */
public final class BackupStore {

    private static final String PREFS_NAME = "settings";
    // 还原时要保住的本机 key：导出目录 uri 的字符串能随包回来，但 SAF 持久化授权是系统按 UID 发的、
    // 卸载即失效，把它写回来会让那一行显示"已设置"而实际点不动；
    // restore_prompt_shown 是本机弹没弹过小窗，meta_dump_seq 只出现在还带快照导出那几版做出来的旧包里。
    private static final String KEY_TREE_URI = "export_tree_uri";
    private static final String KEY_PROMPT_SHOWN = "restore_prompt_shown";
    private static final String KEY_DUMP_SEQ = "meta_dump_seq";
    private static final String DIR_IN_ZIP = "wallpapers/";
    private static final String THUMB_DIR_IN_ZIP = "thumbs/";
    // 原图（重复调整的源）。v3.8x 之前的包没这个目录，还原时读到就跳过 —— 不能因为缺它而报错
    private static final String ORIGINAL_DIR_IN_ZIP = "originals/";
    private static final String PREFS_DIR_IN_ZIP = "prefs/";

    private BackupStore() {
    }

    /** 备份结果：文件名与打包份数；失败时 error 非空。 */
    public static final class BackupResult {
        public String name;
        public int images;
        public int originals;
        public int thumbs;
        public int entries;
        public String error;
    }

    /** 包里的现场（还原前给用户看一眼，别盲覆盖现有库）。 */
    public static final class Manifest {
        public int images;
        public int originals;
        public int thumbs;
        public int libraryItems;
        public int libraries;
        public long bytes;
        public String appVersion;
        public String backupTime;
    }

    /** 还原结果。 */
    public static final class RestoreResult {
        public int images;
        public int items;
        public int prefsKeys;       // 从包里写回的 SharedPreferences key 数
        public int droppedItems;   // 包里有元数据但缺图片文件，被丢弃的条目数
        public String error;
    }

    /** 打包写到导出目录（自定义 SAF 目录优先，没设则落默认目录 下载/wallswitch）。纯 IO，调用方必须放后台线程。 */
    public static BackupResult backup(Context context) {
        BackupResult r = new BackupResult();
        if (!WallpaperExporter.canExport(context)) {
            r.error = "没设导出目录";
            return r;
        }
        String name = "wallswitch-backup-"
                + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".zip";
        Uri doc = WallpaperExporter.openDocForWrite(context, name, "application/zip");
        if (doc == null) {
            r.error = "建文件失败";
            return r;
        }
        java.util.zip.ZipOutputStream zout = null;
        try {
            zout = new java.util.zip.ZipOutputStream(
                    context.getContentResolver().openOutputStream(doc, "wt"));
            // 内容清单先算一次，好写进 manifest
            java.util.List<File> images = listFiles(new File(context.getFilesDir(), "wallpapers"));
            java.util.List<File> originals = listFiles(new File(context.getFilesDir(), "originals"));
            java.util.List<File> thumbs = listFiles(new File(context.getFilesDir(), "thumbs"));
            r.images = images.size();
            r.originals = originals.size();
            r.thumbs = thumbs.size();
            r.entries = WallpaperStore.load(context).size();

            writeManifest(context, zout, r, images, thumbs, originals);
            writeFile(zout, new File(context.getFilesDir(), "library.json"), "library.json");
            writeFile(zout, new File(context.getFilesDir(), "libraries.json"), "libraries.json");
            for (File f : images) {
                writeFile(zout, f, DIR_IN_ZIP + f.getName());
            }
            for (File f : originals) {
                writeFile(zout, f, ORIGINAL_DIR_IN_ZIP + f.getName());
            }
            for (File f : thumbs) {
                writeFile(zout, f, THUMB_DIR_IN_ZIP + f.getName());
            }
            writeFile(zout, LauncherPreviewOverlay.overlayFile(context),
                    LauncherPreviewOverlay.overlayFileName());
            writeFile(zout, SwitchLog.logFile(context, true), SwitchLog.fileName(true));
            writeFile(zout, SwitchLog.logFile(context, false), SwitchLog.fileName(false));
            // shared_prefs 里我们自己的那几个 xml（系统持有文件，只读不改）
            for (String prefsName : new String[]{"settings", "wallswitch"}) {
                writeFile(zout, prefsFile(context, prefsName),
                        PREFS_DIR_IN_ZIP + prefsName + ".xml");
            }
            zout.finish();
            r.error = null;
            r.name = name;
        } catch (Exception | OutOfMemoryError e) {
            r.error = "打包失败：" + e.getClass().getSimpleName();
            deleteQuietly(context, doc);
        } finally {
            closeQuietly(zout);
        }
        return r;
    }

    /** 读包里的 manifest 与张数（还原前的回显）。需要本地临时文件，走后台线程。 */
    public static Manifest inspect(Context context, Uri zipUri) {
        File tmp = copyToCache(context, zipUri);
        if (tmp == null) {
            return null;
        }
        ZipFile zip = null;
        try {
            zip = new ZipFile(tmp);
            Manifest m = new Manifest();
            java.util.Enumeration<? extends ZipEntry> es = zip.entries();
            long total = 0;
            byte[] manifestRaw = null;
            while (es.hasMoreElements()) {
                ZipEntry e = es.nextElement();
                if (e.isDirectory()) {
                    continue;
                }
                total += Math.max(0, e.getSize());
                String n = e.getName();
                if (n.startsWith(DIR_IN_ZIP) && !n.equals(DIR_IN_ZIP)) {
                    m.images++;
                } else if (n.startsWith(ORIGINAL_DIR_IN_ZIP)) {
                    m.originals++;
                } else if (n.startsWith(THUMB_DIR_IN_ZIP)) {
                    m.thumbs++;
                } else if (n.equals("library.json")) {
                    m.libraryItems = readJsonLength(zip, e);
                } else if (n.equals("libraries.json")) {
                    m.libraries = readJsonLength(zip, e);
                } else if (n.equals("manifest.json")) {
                    // 只存字节，循环结束再填 —— manifest.json 是打包时第一个写入的条目，
                    // 循环里就调 fillFromManifest 会把清单计数预填进去，后面扫描再累加一遍，
                    // 成品图/原图在回显里翻倍（缩略图不预填所以一直是对的）
                    manifestRaw = readAll(zip, e);
                }
            }
            if (manifestRaw != null) {
                fillFromManifest(m, manifestRaw);
            }
            m.bytes = total;
            return m;
        } catch (Exception | OutOfMemoryError e) {
            return null;
        } finally {
            closeQuietly(zip);
            tmp.delete();
        }
    }

    /**
     * 解包还原：图片/缩略图/两个 json/底图/日志落 filesDir，prefs xml 逐条写回 SharedPreferences。
     * 同 id 以包为准整文件覆盖。纯 IO，调用方必须放后台线程。
     */
    public static RestoreResult restore(Context context, Uri zipUri) {
        RestoreResult r = new RestoreResult();
        java.util.Set<String> restoredLibIds = new java.util.HashSet<>();
        File tmp = copyToCache(context, zipUri);
        if (tmp == null) {
            r.error = "读不到这个包";
            return r;
        }
        ZipFile zip = null;
        try {
            zip = new ZipFile(tmp);
            new File(context.getFilesDir(), "wallpapers").mkdirs();
            new File(context.getFilesDir(), "originals").mkdirs();
            new File(context.getFilesDir(), "thumbs").mkdirs();
            java.util.Enumeration<? extends ZipEntry> es = zip.entries();
            while (es.hasMoreElements()) {
                ZipEntry e = es.nextElement();
                if (e.isDirectory()) {
                    continue;
                }
                String n = e.getName();
                if (n.startsWith(DIR_IN_ZIP)) {
                    extract(context, zip, e, new File(context.getFilesDir(), "wallpapers"), safeName(n));
                    r.images++;
                } else if (n.startsWith(ORIGINAL_DIR_IN_ZIP)) {
                    // 原图落回同名文件；老包不会走到这条分支（缺原图只是以后不能重复调整，不是错误）
                    extract(context, zip, e, new File(context.getFilesDir(), "originals"), safeName(n));
                } else if (n.startsWith(THUMB_DIR_IN_ZIP)) {
                    extract(context, zip, e, new File(context.getFilesDir(), "thumbs"), safeName(n));
                } else if (n.equals("library.json")) {
                    extract(context, zip, e, context.getFilesDir(), n);
                } else if (n.equals("libraries.json")) {
                    collectLibIds(readAll(zip, e), restoredLibIds);
                    extract(context, zip, e, context.getFilesDir(), n);
                } else if (n.equals(LauncherPreviewOverlay.overlayFileName())) {
                    extract(context, zip, e, context.getFilesDir(), n);
                } else if (n.equals(SwitchLog.fileName(true)) || n.equals(SwitchLog.fileName(false))) {
                    extract(context, zip, e, context.getFilesDir(), n);
                } else if (n.startsWith(PREFS_DIR_IN_ZIP) && n.endsWith(".xml")) {
                    r.prefsKeys += applyPrefs(context, n, readAll(zip, e));
                }
            }
            // 上面那条 library.json 是直接 extract 到 filesDir 的，绕过了 WallpaperStore.saveLibrary
            // 的缓存回填 —— 这里明着作废一次，不靠"长度/修改时间总会变"去赌
            WallpaperStore.invalidateCache();
            dropMissingImages(context);
            retagUnknownLibs(context, restoredLibIds);
            r.items = WallpaperStore.load(context).size();
            r.error = null;
        } catch (Exception | OutOfMemoryError e) {
            r.error = "还原失败：" + e.getClass().getSimpleName();
        } finally {
            closeQuietly(zip);
            tmp.delete();
        }
        return r;
    }

    /** 看起来像新装机（从没写过壁纸库文件）时，才值得问一句要不要还原。 */
    public static boolean looksLikeFreshInstall(Context context) {
        // 库列表不能参与判定：LibraryStore.load 首跑就自动创建「默认库」，「库为空」永远成立不了
        return !new File(context.getFilesDir(), "library.json").exists()
                && WallpaperStore.load(context).isEmpty();
    }

    public static boolean promptShown(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_PROMPT_SHOWN, false);
    }

    public static void markPromptShown(Context context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_PROMPT_SHOWN, true).apply();
    }

    // ===== 内部 =====

    private static File prefsFile(Context context, String name) {
        return new File(new File(context.getFilesDir().getParentFile(), "shared_prefs"), name + ".xml");
    }

    private static void writeManifest(Context context, java.util.zip.ZipOutputStream zout,
                                      BackupResult r, java.util.List<File> images,
                                      java.util.List<File> thumbs,
                                      java.util.List<File> originals) throws Exception {
        long imageBytes = 0;
        for (File f : images) {
            imageBytes += f.length();
        }
        long originalBytes = 0;
        for (File f : originals) {
            originalBytes += f.length();
        }
        long thumbBytes = 0;
        for (File f : thumbs) {
            thumbBytes += f.length();
        }
        JSONObject o = new JSONObject();
        o.put("app_version", versionName(context));
        o.put("backup_time", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
        o.put("library_items", r.entries);
        o.put("images", images.size());
        o.put("image_bytes", imageBytes);
        o.put("originals", originals.size());
        o.put("original_bytes", originalBytes);
        o.put("thumbs", thumbs.size());
        o.put("thumb_bytes", thumbBytes);
        o.put("libraries", LibraryStore.load(context).size());
        ZipEntry e = new ZipEntry("manifest.json");
        byte[] bytes = o.toString(2).getBytes(StandardCharsets.UTF_8);
        zout.putNextEntry(e);
        zout.write(bytes);
        zout.closeEntry();
    }

    private static void writeFile(java.util.zip.ZipOutputStream zout,
                                  File src, String entryName) throws Exception {
        if (src == null || !src.exists() || !src.isFile()) {
            return;
        }
        // 不显式设 STORED：STORE 要求 putNextEntry 之前就把 size 和 crc 填全（没地方预先算整张图的
        // CRC 除非再整份读一遍），交给 ZipOutputStream 走默认的 DEFLATED 更简单；
        // 无损 PNG 压完只差 2~5%，多出来的那点 CPU 换掉一次全量重读是划算的。（JPEG 那份压不动也不会变大。）
        ZipEntry e = new ZipEntry(entryName);
        zout.putNextEntry(e);
        try (InputStream in = new FileInputStream(src)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                zout.write(buffer, 0, read);
            }
        }
        zout.closeEntry();
    }

    /**
     * 归属兜底：新装机第一次启动会自己建一个「默认库」（随机新 id），包里的壁纸指向的是旧库 id，
     * 对不上就全落在一个不存在的库里 = 文件在、界面上进不去也管不了。
     * 以「包里那份 libraries.json」为准（它整体覆盖了自建的默认库），把归属集合里指向未知库的那些
     * 摘掉；摘到一张都不剩才挂到包里真实存在的那个库上。只有真出现未知归属时才改写 library.json。
     * 多归属（一张挂在好几个库）在这里只是逐条判归属，共享的那份图不会因为某个库对不上而被删。
     */
    private static void retagUnknownLibs(Context context, java.util.Set<String> libIds) {
        try {
            java.util.List<WallpaperStore.Item> items = WallpaperStore.load(context);
            if (items.isEmpty() || libIds.isEmpty()) {
                return;
            }
            String fallback = null;
            for (String id : libIds) {
                fallback = id;
                break;
            }
            boolean changed = false;
            for (WallpaperStore.Item item : items) {
                for (String lid : new java.util.ArrayList<>(item.libIds)) {
                    if (!libIds.contains(lid)) {
                        item.leaveLib(lid);
                        changed = true;
                    }
                }
                if (item.libIds.isEmpty()) {
                    item.joinLib(fallback);
                    changed = true;
                }
            }
            if (changed) {
                // 同样必须走 WallpaperStore.saveItems：这条是还原路径上第二处会整表重写 library.json 的地方
                WallpaperStore.saveItems(context, items);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * library.json 里有记录、盘上没有图片文件的条目：丢掉并计数，否则会留下点开黑图的死条目。
     * 写回必须走 {@link WallpaperStore#saveItems} —— 留着旧副本就会在还原时把裁剪参数
     * （src_w/src_h/crop_*）整表抹掉。
     */
    private static void dropMissingImages(Context context) {
        try {
            java.util.List<WallpaperStore.Item> items = WallpaperStore.load(context);
            java.util.List<WallpaperStore.Item> keep = new java.util.ArrayList<>();
            int dropped = 0;
            for (WallpaperStore.Item item : items) {
                File full = WallpaperStore.getFullFile(context, item.id);
                if (full.exists()) {
                    keep.add(item);
                } else {
                    dropped++;
                }
            }
            if (dropped > 0) {
                WallpaperStore.saveItems(context, keep);
            }
        } catch (Exception ignored) {
        }
    }

    /** 从包里的 libraries.json 取库 id（解析不动就什么也不加，后续按「无库」不处理）。 */
    private static void collectLibIds(byte[] raw, java.util.Set<String> into) {
        try {
            JSONArray arr = new JSONArray(new String(raw, StandardCharsets.UTF_8));
            for (int i = 0; i < arr.length(); i++) {
                String id = arr.getJSONObject(i).optString("id", null);
                if (id != null && !id.isEmpty()) {
                    into.add(id);
                }
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * prefs/settings.xml 逐条写回 SharedPreferences —— 绝不能把 xml 直接拷进 shared_prefs：
     * 进程里 SharedPreferences 有内存缓存，外部盖文件会让它读到错位数据，严重时崩。
     */
    private static int applyPrefs(Context context, String entryName, byte[] raw) {
        String prefsName = entryName.substring(entryName.lastIndexOf('/') + 1)
                .replace(".xml", "");
        if (!"settings".equals(prefsName) && !"wallswitch".equals(prefsName)) {
            return 0;
        }
        SharedPreferences.Editor editor =
                context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit();
        int applied = parseInto(raw, editor);
        editor.apply();
        return applied;
    }

    /**
     * 解析 SharedPreferences 落盘的 xml：根是 `<preferences>`（旧版）或 `<map name=...>`（v2），
     * 里面直接挂 `<string|int|long|float|boolean|hash name=...>`。
     * 两种给值的写法都要接住：`<string>值</string>` 走文本节点，而
     * `<int name="x" value="1800" />` 把值放在 **value 属性**里、没有文本节点 ——
     * 只读文本节点会对 int/long/boolean 拿到空串，Integer.parseInt("") 抛掉，
     * 整个数值类 key 会一声不吭地还原不上。
     * set / string-set / string-list / map 是容器，里面的 <string> 是集合成员而不是键，
     * 不判嵌套深度会把成员当顶层 key 写回去。
     */
    private static int parseInto(byte[] raw, SharedPreferences.Editor editor) {
        int applied = 0;
        try {
            XmlPullParser p = Xml.newPullParser();
            p.setInput(new java.io.ByteArrayInputStream(raw), null);
            String name = null;
            String type = null;
            String attrValue = null;
            StringBuilder text = new StringBuilder();
            int depth = 0;
            int event = p.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                String tag = p.getName();
                if (event == XmlPullParser.START_TAG && tag != null) {
                    boolean valueTag = isValueTag(tag);
                    if (valueTag && depth == 1) {
                        name = p.getAttributeValue(null, "name");
                        attrValue = p.getAttributeValue(null, "value");
                        type = tag;
                        text.setLength(0);
                    } else {
                        if (valueTag) {
                            type = null;
                            name = null;
                        }
                        depth++;
                    }
                } else if (event == XmlPullParser.TEXT && type != null) {
                    text.append(p.getText());
                } else if (event == XmlPullParser.END_TAG && tag != null) {
                    if (!isValueTag(tag) && depth > 0) {
                        depth--;
                    }
                    // START_TAG 分支里不再多调一次 next()：那会把紧随其后的 TEXT 吃掉
                    if (type != null && depth == 1) {
                        applied += putValue(editor, name, type,
                                attrValue != null ? attrValue : text.toString());
                        type = null;
                        name = null;
                        attrValue = null;
                    }
                }
                event = p.next();
            }
        } catch (Exception ignored) {
        }
        return applied;
    }

    /** 直接的键值标签（含 deprecated 的 hash）；带 _front/_back 后缀的是有序集合成员，不算。 */
    private static boolean isValueTag(String tag) {
        if (tag.indexOf('_') >= 0) {
            return false;
        }
        return "string".equals(tag) || "int".equals(tag) || "long".equals(tag)
                || "float".equals(tag) || "boolean".equals(tag) || "hash".equals(tag);
    }

    private static int putValue(SharedPreferences.Editor editor, String name, String type, String value) {
        if (name == null || name.isEmpty() || type == null) {
            return 0;
        }
        if (KEY_TREE_URI.equals(name) || KEY_PROMPT_SHOWN.equals(name) || KEY_DUMP_SEQ.equals(name)) {
            return 0;   // 本机状态不能被旧包拽回去
        }
        try {
            if ("int".equals(type)) {
                editor.putInt(name, Integer.parseInt(value));
            } else if ("long".equals(type)) {
                editor.putLong(name, Long.parseLong(value));
            } else if ("float".equals(type)) {
                editor.putFloat(name, Float.parseFloat(value));
            } else if ("boolean".equals(type)) {
                editor.putBoolean(name, Boolean.parseBoolean(value));
            } else {
                // string 与 hash（deprecated 的 String 存储形态，值仍是字符串）都按字符串写
                editor.putString(name, value);
            }
            return 1;
        } catch (Exception e) {
            return 0;
        }
    }

    /** 把 SAF 上的包整份搬到 cache，好让 ZipFile 用随机读（ZipInputStream 拿不到中央目录）。 */
    private static File copyToCache(Context context, Uri zipUri) {
        File dst = new File(context.getCacheDir(), "restore.zip");
        try (InputStream in = context.getContentResolver().openInputStream(zipUri);
             OutputStream out = new FileOutputStream(dst)) {
            if (in == null) {
                return null;
            }
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            out.flush();
            return dst;
        } catch (Exception | OutOfMemoryError e) {
            dst.delete();
            return null;
        }
    }

    private static void extract(Context context, ZipFile zip, ZipEntry e, File dir, String name)
            throws Exception {
        File dst = new File(dir, name);
        try (InputStream in = zip.getInputStream(e);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            out.flush();
        }
    }

    private static byte[] readAll(ZipFile zip, ZipEntry e) throws Exception {
        try (InputStream in = zip.getInputStream(e)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                bos.write(buffer, 0, read);
            }
            return bos.toByteArray();
        }
    }

    private static int readJsonLength(ZipFile zip, ZipEntry e) {
        try {
            return new JSONArray(new String(readAll(zip, e), StandardCharsets.UTF_8)).length();
        } catch (Exception ex) {
            return -1;
        }
    }

    /** 包里没有 manifest.json（或它读不出）时，用已数到的条目数补上回显要用的字段。 */
    private static void fillFromManifest(Manifest m, byte[] raw) {
        try {
            JSONObject o = new JSONObject(new String(raw, StandardCharsets.UTF_8));
            m.appVersion = o.optString("app_version");
            m.backupTime = o.optString("backup_time");
            if (m.libraryItems < 0) {
                m.libraryItems = o.optInt("library_items", -1);
            }
            if (m.images <= 0) {
                m.images = o.optInt("images", 0);
            }
            if (m.originals <= 0) {
                // 老包压根没这个键，读出来就是 0：界面上显示「0 张原图」正好说明为什么还原后不能重复调整
                m.originals = o.optInt("originals", 0);
            }
        } catch (Exception ignored) {
        }
    }

    private static void deleteQuietly(Context context, Uri doc) {
        try {
            // 默认目录的文档归 MediaStore 管，DocumentsContract 删不动，按 authority 分路
            if ("media".equals(doc.getAuthority())) {
                context.getContentResolver().delete(doc, null, null);
            } else {
                DocumentsContract.deleteDocument(context.getContentResolver(), doc);
            }
        } catch (Exception ignored) {
        }
    }

    /** 默认目录里扫到的一个备份包（首次启动引导的列表项）。 */
    public static final class BackupCandidate {
        public final Uri uri;
        public final String name;
        public final long size;
        /** 是否在默认目录（下载/wallswitch）里，用于置顶排序。 */
        public final boolean inDefaultDir;

        BackupCandidate(Uri uri, String name, long size, boolean inDefaultDir) {
            this.uri = uri;
            this.name = name;
            this.size = size;
            this.inDefaultDir = inDefaultDir;
        }
    }

    /**
     * 扫默认目录（下载/wallswitch）里的备份包，新的在前。纯查询，量大不了，但走 MediaStore，
     * 调用方放后台线程。默认目录不可用（Android 10 以下）时返回空表。
     * 荣耀实测卸载会清 MediaStore 条目、共享存储也会挡住直读 —— 三条通道都空时，
     * 由引导弹窗引导用户走 SAF 授权（scanTreeBackups），那条路不受属主限制。
     */
    public static java.util.List<BackupCandidate> scanDefaultBackups(Context context) {
        java.util.List<BackupCandidate> out = new java.util.ArrayList<>();
        if (!WallpaperExporter.supportsDefaultDir()) {
            return out;
        }
        collectFromCollection(context,
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL), out);
        if (out.isEmpty()) {
            collectFromCollection(context,
                    MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), out);
        }
        if (out.isEmpty()) {
            collectFromFileSystem(out);
        }
        // 默认目录里的排前面，其余按文件名（自带时间戳）倒序 —— 不依赖厂商的排序支持
        out.sort((a, b) -> a.inDefaultDir != b.inDefaultDir
                ? (a.inDefaultDir ? -1 : 1) : b.name.compareTo(a.name));
        return out;
    }

    /** 从 MediaStore 某个集合按文件名粗筛（不筛路径，厂商对 RELATIVE_PATH 过滤支持不可靠）。 */
    private static void collectFromCollection(Context context, Uri collection,
            java.util.List<BackupCandidate> out) {
        try (Cursor c = context.getContentResolver().query(collection,
                new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.RELATIVE_PATH},
                MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?",
                new String[]{"wallswitch-backup-%.zip"}, null)) {
            if (c != null) {
                while (c.moveToNext()) {
                    out.add(new BackupCandidate(
                            ContentUris.withAppendedId(collection, c.getLong(0)),
                            c.getString(1), c.getLong(2),
                            WallpaperExporter.DEFAULT_RELATIVE_PATH.equals(c.getString(3))));
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** 兜底：直接读 /sdcard/Download/wallswitch。能否读得动取决于系统对共享存储的放权限度。 */
    private static void collectFromFileSystem(java.util.List<BackupCandidate> out) {
        try {
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), "wallswitch");
            File[] files = dir.listFiles((d, n) ->
                    n.startsWith("wallswitch-backup-") && n.endsWith(".zip"));
            if (files != null) {
                for (File f : files) {
                    out.add(new BackupCandidate(Uri.fromFile(f), f.getName(), f.length(), true));
                }
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 通过 SAF 目录授权列出树里的备份包。SAF 授权不受「属主」限制，卸载清了 MediaStore 条目、
     * 共享存储挡住直读都不影响 —— 只要用户亲手把目录授给我们就能列出。授到上一级
     * （Download）时会自动钻进 wallswitch 子目录找。
     */
    public static java.util.List<BackupCandidate> scanTreeBackups(Context context, Uri treeUri) {
        java.util.List<BackupCandidate> out = new java.util.ArrayList<>();
        try {
            String rootDocId = DocumentsContract.getTreeDocumentId(treeUri);
            // 授的是上一级（Download 之类）时，先找 wallswitch 子目录
            String dirDocId = findChildDir(context, treeUri, rootDocId, "wallswitch");
            if (dirDocId == null && rootDocId != null && rootDocId.endsWith(":Download/wallswitch")) {
                dirDocId = rootDocId;
            }
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri,
                    dirDocId == null ? rootDocId : dirDocId);
            try (Cursor c = context.getContentResolver().query(children,
                    new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                            DocumentsContract.Document.COLUMN_SIZE},
                    null, null, null)) {
                while (c != null && c.moveToNext()) {
                    String name = c.getString(1);
                    if (name == null || !name.startsWith("wallswitch-backup-")
                            || !name.endsWith(".zip")) {
                        continue;
                    }
                    out.add(new BackupCandidate(
                            DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0)),
                            name, c.getLong(2), true));
                }
            }
        } catch (Exception ignored) {
        }
        out.sort((a, b) -> b.name.compareTo(a.name));
        return out;
    }

    /** 在树的某层里找一个指定名字的子目录，找到返回它的 documentId，没有返回 null。 */
    private static String findChildDir(Context context, Uri treeUri, String parentDocId,
            String name) {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId);
        try (Cursor c = context.getContentResolver().query(children,
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE},
                null, null, null)) {
            while (c != null && c.moveToNext()) {
                if (name.equals(c.getString(1))
                        && DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2))) {
                    return c.getString(0);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static java.util.List<File> listFiles(File dir) {
        java.util.List<File> out = new java.util.ArrayList<>();
        for (File f : listOfFiles(dir)) {
            if (f.isFile()) {
                out.add(f);
            }
        }
        return out;
    }

    private static File[] listOfFiles(File dir) {
        File[] listed = dir == null ? null : dir.listFiles();
        return listed == null ? new File[0] : listed;
    }

    /** 包里的条目名可能带路径，只取最后一段，防止写到 filesDir 之外。 */
    private static String safeName(String entryName) {
        String n = entryName.replace('\\', '/');
        int slash = n.lastIndexOf('/');
        return slash >= 0 ? n.substring(slash + 1) : n;
    }

    private static String versionName(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
            }
        }
    }
}
