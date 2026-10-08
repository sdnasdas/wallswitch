package com.example.wallswitch;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
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
 * zip 条目走 DEFLATED（ZipOutputStream 的默认）：无损 PNG 再压只差 2~5%，但换掉了
 * 「STORE 必须预先填 size + crc」这条要求 —— 那意味着每张图要多读一整遍才能算出 CRC。
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
    /** {@link #copyToCache} 的落地名。局域网收到的包**绝不能**叫这个（见 {@link #localZip}）。 */
    private static final String CACHE_ZIP = "restore.zip";

    /**
     * 把包解析成本地文件：SAF 上的要整份搬到 cache（ZipFile 要随机读，ZipInputStream 拿不到中央目录）；
     * 已经是本地文件的直接用 —— 拿 copyToCache 复制「自己就是目标」的文件会先把输入截成 0 字节，
     * 现象是还原失败却报那句含糊的「读不到这个包」（check/LanSyncTest.trap 锁着这条）。
     */
    private static File localZip(Context context, Uri zipUri) {
        if (zipUri != null && "file".equals(zipUri.getScheme())) {
            File f = new File(zipUri.getPath());
            if (f.isFile()) {
                return f;
            }
        }
        return copyToCache(context, zipUri);
    }

    /** 只有 cache 里那份副本归我们删；调用方自己的文件（局域网收下的包）不动。 */
    private static boolean isCacheCopy(Context context, File f) {
        return f != null && f.getParentFile() != null
                && f.getParentFile().equals(context.getCacheDir());
    }

    private BackupStore() {
    }

    /** 备份结果：文件名与打包份数；失败时 error 非空。 */
    public static final class BackupResult {
        public String name;
        public int images;
        public int originals;
        public int thumbs;
        public int entries;
        public long zipBytes;      // 整包字节（压缩之后）
        public String sha256;      // 整包摘要，局域网共享直接拿去报给对端
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

    /** 打包写到导出目录。纯 IO，调用方必须放后台线程。 */
    public static BackupResult backup(Context context) {
        BackupResult r = new BackupResult();
        Uri tree = WallpaperExporter.treeUri(context);
        if (tree == null) {
            r.error = "没设导出目录";
            return r;
        }
        String name = "wallswitch-backup-"
                + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".zip";
        Uri doc = createDoc(context, tree, name);
        if (doc == null) {
            r.error = "建文件失败";
            return r;
        }
        OutputStream out = null;
        try {
            java.util.List<LanPackager.Entry> entries = collectEntries(context, r);
            out = context.getContentResolver().openOutputStream(doc, "wt");
            if (out == null) {
                r.error = "打不开那个文件";
                deleteQuietly(context, doc);
                return r;
            }
            LanPackager.Result p = LanPackager.pack(entries, out);
            r.zipBytes = p.zipBytes;
            r.sha256 = p.sha256;
            r.error = null;
            r.name = name;
        } catch (Exception | OutOfMemoryError e) {
            r.error = "打包失败：" + e.getClass().getSimpleName();
            deleteQuietly(context, doc);
        } finally {
            closeQuietly(out);
        }
        return r;
    }

    /**
     * 局域网共享：把整库打进 cache（不进用户的导出目录，也不要求先设导出目录）。
     * 纯 IO，调用方必须放后台线程。
     */
    public static BackupResult backupToCache(Context context, File dst) {
        BackupResult r = new BackupResult();
        try {
            java.util.List<LanPackager.Entry> entries = collectEntries(context, r);
            File dir = dst.getParentFile();
            if (dir != null) {
                dir.mkdirs();
            }
            OutputStream out = new FileOutputStream(dst);
            LanPackager.Result p;
            try {
                p = LanPackager.pack(entries, out);
            } finally {
                closeQuietly(out);
            }
            r.name = dst.getName();
            r.zipBytes = p.zipBytes;
            r.sha256 = p.sha256;
        } catch (Exception | OutOfMemoryError e) {
            r.error = "打包失败：" + e.getClass().getSimpleName();
            if (dst.exists() && dst.length() == 0L) {
                dst.delete();     // 半截的空包留着只会让下次「复用现成包」的判断被骗
            }
        }
        return r;
    }

    /**
     * 组出「一个包该有哪些条目」—— 这是备份格式的**唯一**写手：{@link #backup}（写 SAF）与
     * {@link #backupToCache}（写 cache）都只经由这里。出现第二个打包写手就等于格式有两处定义，
     * 改一处会静默漂，本项目在 library.json 的序列化上吃过一次这个亏。
     *
     * <p>条目顺序、名字、内容跟 v3.76 定稿时完全一致：本功能不新增条目、不给 manifest.json 加字段。
     */
    public static java.util.List<LanPackager.Entry> collectEntries(Context context,
                                                                  BackupResult r) throws Exception {
        java.util.List<File> images = listFiles(new File(context.getFilesDir(), "wallpapers"));
        java.util.List<File> originals = listFiles(new File(context.getFilesDir(), "originals"));
        java.util.List<File> thumbs = listFiles(new File(context.getFilesDir(), "thumbs"));
        int items = WallpaperStore.load(context).size();
        if (r != null) {
            r.images = images.size();
            r.originals = originals.size();
            r.thumbs = thumbs.size();
            r.entries = items;
        }
        java.util.List<LanPackager.Entry> out = new java.util.ArrayList<>();
        out.add(new LanPackager.Entry("manifest.json",
                manifestBytes(context, items, images, originals, thumbs)));
        out.add(new LanPackager.Entry("library.json", new File(context.getFilesDir(), "library.json")));
        out.add(new LanPackager.Entry("libraries.json",
                new File(context.getFilesDir(), "libraries.json")));
        for (File f : images) {
            out.add(new LanPackager.Entry(DIR_IN_ZIP + f.getName(), f));
        }
        for (File f : originals) {
            // 原图落回同名文件；缺了只是以后不能重复调整，不是错误，所以 Entry 那边会静默跳过
            out.add(new LanPackager.Entry(ORIGINAL_DIR_IN_ZIP + f.getName(), f));
        }
        for (File f : thumbs) {
            out.add(new LanPackager.Entry(THUMB_DIR_IN_ZIP + f.getName(), f));
        }
        out.add(new LanPackager.Entry(LauncherPreviewOverlay.overlayFileName(),
                LauncherPreviewOverlay.overlayFile(context)));
        out.add(new LanPackager.Entry(SwitchLog.fileName(true), SwitchLog.logFile(context, true)));
        out.add(new LanPackager.Entry(SwitchLog.fileName(false), SwitchLog.logFile(context, false)));
        // shared_prefs 里我们自己的那几个 xml（系统持有文件，只读不改）
        for (String prefsName : new String[]{"settings", "wallswitch"}) {
            out.add(new LanPackager.Entry(PREFS_DIR_IN_ZIP + prefsName + ".xml",
                    prefsFile(context, prefsName)));
        }
        return out;
    }

    /** 读包里的 manifest 与张数（还原前的回显）。需要本地临时文件，走后台线程。 */
    public static Manifest inspect(Context context, Uri zipUri) {
        File tmp = localZip(context, zipUri);
        if (tmp == null) {
            return null;
        }
        boolean temp = isCacheCopy(context, tmp);
        ZipFile zip = null;
        try {
            zip = new ZipFile(tmp);
            Manifest m = new Manifest();
            java.util.Enumeration<? extends ZipEntry> es = zip.entries();
            long total = 0;
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
                    fillFromManifest(m, readAll(zip, e));
                }
            }
            m.bytes = total;
            return m;
        } catch (Exception | OutOfMemoryError e) {
            return null;
        } finally {
            closeQuietly(zip);
            if (temp) {
                tmp.delete();     // 只有我们自己的 cache 副本归这里删，调用方的文件不动
            }
        }
    }

    /**
     * 解包还原：图片/缩略图/两个 json/底图/日志落 filesDir，prefs xml 逐条写回 SharedPreferences。
     * 同 id 以包为准整文件覆盖。纯 IO，调用方必须放后台线程。
     */
    public static RestoreResult restore(Context context, Uri zipUri) {
        RestoreResult r = new RestoreResult();
        java.util.Set<String> restoredLibIds = new java.util.HashSet<>();
        File tmp = localZip(context, zipUri);
        if (tmp == null) {
            r.error = "读不到这个包";
            return r;
        }
        boolean temp = isCacheCopy(context, tmp);
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
            dropMissingImages(context);
            retagUnknownLibs(context, restoredLibIds);
            r.items = WallpaperStore.load(context).size();
            r.error = null;
        } catch (Exception | OutOfMemoryError e) {
            r.error = "还原失败：" + e.getClass().getSimpleName();
        } finally {
            closeQuietly(zip);
            if (temp) {
                tmp.delete();     // 局域网收下的那份是调用方的文件，不归这里删
            }
        }
        return r;
    }

    /** 看起来像新装机（从没写过库文件）时，才值得问一句要不要还原。 */
    public static boolean looksLikeFreshInstall(Context context) {
        return !new File(context.getFilesDir(), "library.json").exists()
                && LibraryStore.load(context).isEmpty()
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

    /**
     * manifest.json 的字节。十个字段一个不改、不加 —— 局域网共享用的是同一个包，
     * 加了字段就等于同时改备份格式（那要单开一版，见 docs/lan-sync-plan.md §8）。
     */
    private static byte[] manifestBytes(Context context, int items, java.util.List<File> images,
                                        java.util.List<File> originals,
                                        java.util.List<File> thumbs) throws Exception {
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
        o.put("library_items", items);
        o.put("images", images.size());
        o.put("image_bytes", imageBytes);
        o.put("originals", originals.size());
        o.put("original_bytes", originalBytes);
        o.put("thumbs", thumbs.size());
        o.put("thumb_bytes", thumbBytes);
        o.put("libraries", LibraryStore.load(context).size());
        return o.toString(2).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 归属兜底：新装机第一次启动会自己建一个「默认库」（随机新 id），包里的壁纸指向的是旧库 id，
     * 对不上就全落在一个不存在的库里 = 文件在、界面上进不去也管不了。
     * 以「包里那份 libraries.json」为准（它整体覆盖了自建的默认库），把指向未知库的条目
     * 改挂到包里真实存在的那个库上；只有真出现未知归属时才改写 library.json。
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
                if (item.libId == null || item.libId.isEmpty() || !libIds.contains(item.libId)) {
                    item.libId = fallback;
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
     * 写回必须走 {@link WallpaperStore#saveItems} —— 它自己那份只写 id/lib_id/title 的副本已经删掉了，
     * 留着就会在还原时把裁剪参数（src_w/src_h/crop_*）整表抹掉。
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

    /** 只给 SAF 上的包用：ZipFile 要随机读，得先落到本地临时文件（见 {@link #localZip}）。 */
    private static File copyToCache(Context context, Uri zipUri) {
        File dst = new File(context.getCacheDir(), CACHE_ZIP);
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

    private static Uri createDoc(Context context, Uri tree, String name) {
        try {
            Uri parent = DocumentsContract.buildDocumentUriUsingTree(
                    tree, DocumentsContract.getTreeDocumentId(tree));
            return DocumentsContract.createDocument(context.getContentResolver(), parent,
                    "application/zip", name);
        } catch (Exception e) {
            return null;
        }
    }

    private static void deleteQuietly(Context context, Uri doc) {
        try {
            DocumentsContract.deleteDocument(context.getContentResolver(), doc);
        } catch (Exception ignored) {
        }
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
