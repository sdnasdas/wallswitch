package com.example.wallswitch;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Rect;
import android.net.Uri;
import android.os.SystemClock;
import android.util.DisplayMetrics;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 壁纸库存储层。
 * 目录约定（均在 App 私有 filesDir 下）：
 * - wallpapers/ 成品图，文件名 &lt;uuid&gt;.png 或 &lt;uuid&gt;.jpg（两档由用户在裁剪页挑，一张只有其中一份）
 * - thumbs/     缩略图，同名，最长边 256px
 * - inbox/      待编辑收件箱，文件名 &lt;uuid&gt;，保留原始字节
 * - originals/  原图（重复调整的源），文件名 &lt;uuid&gt;，保留原始字节；历史数据可能没有
 * 元数据：filesDir/library.json，JSON 数组，每个元素 {"id":"&lt;uuid&gt;","lib_id":"&lt;库 uuid&gt;"}
 * 条目另可带 src_w/src_h/crop_l/crop_t/crop_r/crop_b —— 上次成品图在原图坐标下的裁剪矩形
 *
 * <h3>归属可以是多个库（v3.11x 整理功能）</h3>
 * 一份图片文件可以同时挂在几个壁纸库下，各库看到的是<b>同一份像素</b>：
 * 在 A 库点格子重编（挪位置、重新裁）改的就是那一份，B 库那张跟着一起变，构图参数也共用
 * （原图字节一样，所以记在原图坐标下的矩形对两边都成立）。因此：
 * <ul>
 *   <li>「复制到别的库」只是多挂一个归属，不拷文件、不占磁盘；「移动」= 从这个库摘、挂到那个库。</li>
 *   <li>只有归属被摘空（一张不再挂在任何库）才删三份文件；删一个库同理 —— 只摘掉那个库的归属，
 *       还挂在别的库的图与元数据都留着。</li>
 *   <li>权威是条目里的 {@code libIds} 这份集合。{@code lib_id} 退化成「主归属 = 集合第一个」，
 *       只为降级回滚留着（老版本只认这一个键，读回来这张就落在它挂的第一个库里，不会出现
 *       文件在、界面进不去的孤儿）。所以 {@code lib_ids} 只在归属多于一个时才写，
 *       单归属的老文件格式一个字节都不变。</li>
 * </ul>
 */
public class WallpaperStore {

    // 目录名常量
    private static final String DIR_FULL = "wallpapers";
    private static final String DIR_THUMB = "thumbs";
    private static final String DIR_INBOX = "inbox";
    // 原图目录：用户从相册选的那张的原始字节，重复调整的源。文件名跟 id 一样、不带扩展名 ——
    // 读它的一方（BitmapFactory / BitmapRegionDecoder）都靠嗅探格式，不用扩展名猜
    private static final String DIR_ORIGINAL = "originals";
    // 元数据文件名
    private static final String LIB_FILE = "library.json";
    // 待编辑项标题（导入时的原始文件名，确认导入后写入元数据）临时存储
    private static final String TITLE_PREFS = "pending_titles";
    // 通用设置偏好（与其它模块共用 settings 文件）：缩略图一次性升级标记
    private static final String PREFS_NAME = "settings";
    // 缩略图升级标记：老版本存的是「长边 256 的等比缩略图」，两列方格上会被放大 2 倍多发虚
    private static final String THUMB_REGEN_KEY = "thumb_regen_v315";
    // 缩略图的 JPEG 压缩质量（缩略图只用于列表显示，没必要无损）
    private static final int JPEG_QUALITY = 90;
    // 成品图两档扩展名：无损 PNG 与 JPEG q90，用户在裁剪页那粒胶囊里挑（见下面的 FULL_JPEG_KEY）。
    // 变量名里的 FULL/LEGACY 是历史留下的：早期成品图一律 .jpg，v3.6 起默认改 PNG，v3.121 起两档随用户选，
    // 所以 LEGACY 那份现在只是「老条目与新选 JPEG 的那档」，不再是"待兼容的旧数据"。
    private static final String FULL_EXT = ".png";
    private static final String LEGACY_FULL_EXT = ".jpg";
    // 成品图格式：用户在裁剪页「确认」前那粒胶囊里选，默认 JPEG（无损 PNG 是可切的那一档）。
    // 读侧不用改 —— getFullFile 先认 .png 再回退 .jpg，而引擎、锁屏 setStream、备份、导出都只经它拿文件，
    // 解码方靠嗅探认格式。键存在通用 settings 里，选一次就记住，下次进裁剪页仍是上次那一档。
    private static final String FULL_JPEG_KEY = "full_jpeg";
    private static final int FULL_JPEG_QUALITY = 90;
    // 成品图格式标记（网格右上角那粒角标用）：进程内记着「这张盘上是哪一份」，免得每帧绑格子都摸盘。
    // 键是 id（UUID 不复用，条目删了留下的键只是内存垃圾）。失效点跟着写盘走：writeFull 写完直接填对，
    // deleteFullFiles 抹掉；还原是整批覆盖文件，由调用方 clearFullFormatMarks()。
    private static final ConcurrentHashMap<String, Boolean> FULL_JPEG_MARKS = new ConcurrentHashMap<>();

    // 实况标记（网格左上角那粒 LIVE 角标用）：判法与引擎播实况同一套 —— 原图里能不能定位到
    // 实况段。进程内记着免得每帧绑格子都读盘；id 是 uuid，新入库天然没有陈旧 entry，
    // 整批还原走 clearFullFormatMarks 一并清。
    private static final ConcurrentHashMap<String, Boolean> LIVE_MARKS = new ConcurrentHashMap<>();
    // 壁纸分辨率下限：小屏机型也至少按这个处理
    private static final int MIN_WALLPAPER_DIM = 2048;
    // 壁纸分辨率上限：控内存（ARGB_8888 下约 4.6M 像素 ≈ 18MB）
    private static final int MAX_WALLPAPER_DIM = 4096;
    // 缩略图边长上下限：实际值按两列方格在屏幕上的显示宽度算（见 thumbSide）
    private static final int THUMB_MIN_SIDE = 384;
    private static final int THUMB_MAX_SIDE = 768;

    /** 屏幕长边：壁纸要铺满整屏，这就是「不被放大」所需的最小长边。 */
    public static int screenLongSide(Context context) {
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        return Math.max(dm.widthPixels, dm.heightPixels);
    }

    /**
     * 壁纸处理的分辨率上限（解码 / 导出 / 上屏统一用它）。
     * 下限 2048 保住小屏机型质量；上限按屏幕长边抬高 —— 写死 2048 而屏幕长边是 2800 时，
     * 存下来的图会被上屏放大 1.37 倍，肉眼可见发虚。封顶 4096 控制内存。
     */
    public static int maxWallpaperDim(Context context) {
        return Math.max(MIN_WALLPAPER_DIM, Math.min(screenLongSide(context), MAX_WALLPAPER_DIM));
    }

    /**
     * 壁纸条目元数据：id 为图片文件标识，libIds 为它挂在的壁纸库集合（可多于一库），title 为壁纸标题（可空）。
     *
     * <p>{@link #libId} 不是第二份真相：它恒等于 {@code libIds} 的第一个（集合空时为空串），
     * 存在的唯一理由是老版本只认 library.json 里的 {@code lib_id} —— 装回旧包时这张仍落在
     * 它挂着的一个库里，而不是被 {@code lib_id} 为空的分支整个塞进默认库。
     */
    public static class Item {
        public String id;
        public String libId;
        /** 挂在的库 id 集合，按挂上的先后排；判定归属一律用它（{@link #inLib}），别再用 libId 比等。 */
        public final List<String> libIds = new ArrayList<>();
        // 壁纸标题：默认取导入时的原文件名（去扩展名），可在预览弹窗里改；历史数据可能为空
        public String title;
        // 上次成品图是从原图的哪块矩形导出的。坐标一律是**原图像素坐标**，与导出用的区域解码同一套，
        // 不另立归一化约定 —— 少一次换算就少一处能错的地方。
        // srcWidth/srcHeight <= 0 就是「没有参数」，此时那四个 crop 值不参与任何判断。
        public int srcWidth;
        public int srcHeight;
        public float cropLeft;
        public float cropTop;
        public float cropRight;
        public float cropBottom;

        /** 有没有可复原的构图参数（尺寸有效 + 矩形不退化）。 */
        public boolean hasCropRect() {
            return srcWidth > 0 && srcHeight > 0 && cropRight > cropLeft && cropBottom > cropTop;
        }

        /** 这张挂不挂在某个库里（多归属下的唯一正确问法）。 */
        public boolean inLib(String libId) {
            return libId != null && libIds.contains(libId);
        }

        /** 挂上某个库（已经在就不动）。 */
        public void joinLib(String libId) {
            if (libId != null && !libId.isEmpty() && !libIds.contains(libId)) {
                libIds.add(libId);
            }
            syncPrimaryLib();
        }

        /**
         * 从某个库摘下来。
         *
         * @return true = 摘完不再挂任何库（调用方得把这张连文件一起清掉，并处理各库指针）
         */
        public boolean leaveLib(String libId) {
            libIds.remove(libId);
            syncPrimaryLib();
            return libIds.isEmpty();
        }

        /** 主归属跟着集合第一个走，两处不能各说各话。 */
        private void syncPrimaryLib() {
            libId = libIds.isEmpty() ? "" : libIds.get(0);
        }
    }

    /**
     * library.json 的解析缓存。<b>文件是权威，这份缓存是派生物</b>：
     * 命中条件是「文件长度 + 修改时间」跟上次读时一致，所以别的写路径（备份还原、别的线程）
     * 换过文件，指纹就变、下一次自然重读。
     *
     * <p>为什么要它：一次手动切换的链路里这份表被解析 6~8 遍（{@code loadByLib}、{@code getTitle}、
     * {@code TimerScheduler.switchDetail → getTitle}、{@code StatusNotifier → slotLib}、
     * 小组件 {@code scopeThumbId}、引擎 {@code drawCurrent}），而 {@code libAdapter.setItems}
     * 刷新库列表时是「每个库各解析一遍」。全都在同一进程内（Activity / Service / Worker / Receiver
     * 同一个进程，备份还原也是），所以一个 static 缓存加指纹判失效就够，不需要跨进程一致。
     *
     * <p>为什么交出去的是新 {@code ArrayList} 而不是缓存本身：调用方会往列表里增删条目，
     * 共享同一个 List 就把缓存改掉了。条目对象是共享的，这靠一条<b>不变量</b>兜着：
     * 改了 {@code Item} 的字段就必须跟着调 {@link #saveLibrary}（现在三个改写点
     * {@code setTitle}、{@code saveCropRect}、{@code migrateLegacyToLib} 都是改完立刻存），
     * 写完回填缓存 —— 缓存里就是盘上的内容。<b>以后谁加一个"改字段不存"的调用点，就会读到
     * 一份文件里并不存在的状态</b>，这条是这份缓存唯一的前提，别破。
     */
    private static volatile List<Item> cachedItems;
    private static volatile String cachedStamp;

    /** 自记探针：读盘+解析的次数、命中缓存的次数、累计耗时、最近一次的条目数与文件字节数。 */
    public static final AtomicLong metaParses = new AtomicLong();
    public static final AtomicLong metaCacheHits = new AtomicLong();
    public static final AtomicLong metaParseMs = new AtomicLong();
    public static volatile long metaBytes;
    public static volatile int metaItems;

    /** 缓存指纹：长度 + 修改时间。原子写是 rename 换文件，所以这两项一定跟着变。 */
    private static String fileStamp(File file) {
        return file.length() + ":" + file.lastModified();
    }

    /**
     * 让解析缓存立刻作废。
     *
     * <p>给<b>不走 {@link #saveLibrary} 的写手</b>用：备份还原是把包里的 `library.json` 直接
     * `extract` 到 filesDir 的（`BackupStore` 的还原循环里那条分支），指纹理论上会因为长度/时间变化
     * 自己失效，但那是在赌修改时间的精度 —— 换一个明确的作废调用，不靠时序。
     */
    public static void invalidateCache() {
        cachedItems = null;
        cachedStamp = null;
    }

    /** 从 library.json 读取壁纸库列表，文件不存在时返回空列表。 */
    public static List<Item> load(Context context) {
        List<Item> result = new ArrayList<>();
        File libFile = new File(context.getFilesDir(), LIB_FILE);
        if (!libFile.exists()) {
            // 文件没了就把缓存一起抹掉：留着的话，"文件不存在"和"缓存里有内容"会同时成立
            cachedItems = null;
            cachedStamp = null;
            return result;
        }
        String stamp = fileStamp(libFile);
        List<Item> cached = cachedItems;
        if (cached != null && stamp.equals(cachedStamp)) {
            metaCacheHits.incrementAndGet();
            return new ArrayList<>(cached);
        }
        JSONArray arr;
        byte[] raw;
        long t0 = SystemClock.elapsedRealtime();
        try {
            raw = Files.readAllBytes(libFile.toPath());
            arr = MetaFiles.readJson(context, LIB_FILE, raw);
        } catch (Exception | OutOfMemoryError ignored) {
            // 读不动或整份解析不出（MetaFiles 的退让救回也救不了）：不缓存，下次照样重来一遍
            return result;
        }
        metaParseMs.addAndGet(SystemClock.elapsedRealtime() - t0);
        metaParses.incrementAndGet();
        metaBytes = raw.length;
        try {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                Item item = new Item();
                item.id = obj.getString("id");
                item.libId = obj.optString("lib_id", "");
                // 归属集合：多于一个才有这个键（见类注释），老文件/老包读不到就按 lib_id 单归属接上
                JSONArray belongs = obj.optJSONArray("lib_ids");
                if (belongs != null) {
                    for (int b = 0; b < belongs.length(); b++) {
                        String lid = belongs.optString(b, "");
                        if (!lid.isEmpty() && !item.libIds.contains(lid)) {
                            item.libIds.add(lid);
                        }
                    }
                }
                if (item.libIds.isEmpty() && !item.libId.isEmpty()) {
                    item.libIds.add(item.libId);
                } else if (!item.libIds.isEmpty()) {
                    item.libId = item.libIds.get(0);
                }
                item.title = obj.optString("title", "");
                // 裁剪矩形：旧文件/旧包读不出就是 0，按「没有参数」处理
                item.srcWidth = obj.optInt("src_w", 0);
                item.srcHeight = obj.optInt("src_h", 0);
                item.cropLeft = (float) obj.optDouble("crop_l", 0d);
                item.cropTop = (float) obj.optDouble("crop_t", 0d);
                item.cropRight = (float) obj.optDouble("crop_r", 0d);
                item.cropBottom = (float) obj.optDouble("crop_b", 0d);
                result.add(item);
            }
        } catch (Exception ignored) {
            // 数组本身已经过解析（或前缀救回），单个条目缺 id 属于历史脏数据，跳过该条
        }
        metaItems = result.size();
        cachedItems = result;
        cachedStamp = stamp;
        return new ArrayList<>(result);
    }

    /** 把相册选中的图片原样复制进收件箱，返回仅带 id 与标题的条目（尚未入库）。失败时清理半截文件后再抛出。 */
    public static Item importToInbox(Context context, Uri uri) throws Exception {
        String id = UUID.randomUUID().toString();
        File inboxDir = new File(context.getFilesDir(), DIR_INBOX);
        if (!inboxDir.exists()) {
            inboxDir.mkdirs();
        }
        File target = new File(inboxDir, id);
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) {
                throw new IllegalStateException("内容提供者返回空流");
            }
            Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            // 复制中断会残留半截文件，必须清掉，否则会被当成待编辑项反复弹编辑页再解码失败
            try {
                Files.deleteIfExists(target.toPath());
            } catch (Exception ignored) {
            }
            throw e;
        }
        // 用原文件名（去扩展名）作为壁纸标题默认值，确认导入时写进元数据
        String title = stripExtension(displayName(context, uri));
        putPendingTitle(context, id, title);
        Item item = new Item();
        item.id = id;
        item.title = title;
        return item;
    }

    /** 直链下载的单文件上限（64MB）：再大基本不是壁纸，也防坏链接把流量拖穿。 */
    private static final long URL_MAX_BYTES = 64L * 1024 * 1024;

    /**
     * 从网络直链下载图片原始字节进收件箱 —— 不经过相册，与相册导入共用同一条待编辑管线。
     * 不解码不转码，进来的字节就是 `originals/&lt;id&gt;` 以后要留存的原图字节。
     * 标题取 URL 路径末段文件名（去扩展名与参数）；路径里没有名字（raw 链接带参数等）退通用占位。
     * 失败（打不开 / HTTP 错误 / 空响应 / 超上限）清掉半截文件后再抛出 —— 收件箱脏文件会被
     * 当成待编辑项反复弹编辑页又解码失败，与 {@link #importToInbox} 同一条纪律。
     */
    public static Item importUrlToInbox(Context context, String url) throws Exception {
        String id = UUID.randomUUID().toString();
        File inboxDir = new File(context.getFilesDir(), DIR_INBOX);
        if (!inboxDir.exists()) {
            inboxDir.mkdirs();
        }
        File target = new File(inboxDir, id);
        // 收件箱恢复只看完整文件，不能在下载未完时把半截 MP4 交给裁剪页。
        File staging = new File(context.getCacheDir(), "url-" + id + ".download");
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        // 坏链入最怕挂着不回：连接 10s、读 15s，超时就当失败，别让用户对着转圈的进度干等
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(15_000);
        conn.setInstanceFollowRedirects(true);
        try {
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code);
            }
            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(staging)) {
                byte[] buffer = new byte[64 * 1024];
                long total = 0;
                int n;
                while ((n = in.read(buffer)) > 0) {
                    total += n;
                    if (total > URL_MAX_BYTES) {
                        throw new IOException("超过 64MB 上限");
                    }
                    out.write(buffer, 0, n);
                }
                out.flush();
                if (total == 0) {
                    throw new IOException("响应为空");
                }
            }
        } catch (Exception e) {
            try {
                Files.deleteIfExists(staging.toPath());
            } catch (Exception ignored) {
            }
            throw e;
        } finally {
            conn.disconnect();
        }
        // URL 路径末段当默认标题：去 query/fragment 已由 getPath 拿掉，再照相册导入去扩展名；
        // 图床常见百分号编码（中文文件名），解码还原，解不动就用原样
        String path = new URL(url).getPath();
        String name = path == null ? "" : path.substring(path.lastIndexOf('/') + 1);
        try {
            name = java.net.URLDecoder.decode(name, "UTF-8");
        } catch (Exception ignored) {
        }
        String title = stripExtension(name);
        if (title == null || title.isEmpty()) {
            title = "网络图片";
        }
        putPendingTitle(context, id, title);
        try {
            Files.move(staging.toPath(), target.toPath());
        } catch (Exception e) {
            Files.deleteIfExists(staging.toPath());
            cancelImport(context, id);
            throw e;
        }
        Item item = new Item();
        item.id = id;
        item.title = title;
        return item;
    }

    /**
     * 给一张已入库的存量壁纸补原图：把相册 URI 的原始字节复制进 originals/&lt;id&gt;。
     * 只服务历史数据 —— 新导入的走 confirmImport 里的移动，不需要这个动作。
     * 复制失败（授权失效、流断了）返回 false 并清掉半截文件，绝不留下一个「存在但解不开」的原图。
     */
    public static boolean linkOriginal(Context context, String id, Uri uri) {
        if (id == null || id.isEmpty() || uri == null) {
            return false;
        }
        File target = getOriginalFile(context, id);
        try {
            target.getParentFile().mkdirs();
        } catch (Exception ignored) {
        }
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) {
                return false;
            }
            Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return target.isFile() && target.length() > 0L;
        } catch (Exception | OutOfMemoryError e) {
            try {
                Files.deleteIfExists(target.toPath());
            } catch (Exception ignored) {
            }
            return false;
        }
    }

    /** 读取相册 URI 的原始文件名（OpenableColumns.DISPLAY_NAME），读不到返回空串。 */
    public static String displayName(Context context, Uri uri) {
        try (android.database.Cursor cursor = context.getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null) {
                        return name;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    /** 去掉文件扩展名（标题里不带 .jpg 之类后缀）。 */
    private static String stripExtension(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** 取出某待编辑项的标题（确认导入时使用），取出后不清除（取消导入时由 cancelImport 清理）。 */
    private static String pendingTitle(Context context, String inboxId) {
        return context.getSharedPreferences(TITLE_PREFS, Context.MODE_PRIVATE)
                .getString(inboxId, "");
    }

    /** 记录某待编辑项的标题。 */
    private static void putPendingTitle(Context context, String inboxId, String title) {
        context.getSharedPreferences(TITLE_PREFS, Context.MODE_PRIVATE).edit()
                .putString(inboxId, title == null ? "" : title)
                .apply();
    }

    /** 修改某张壁纸的标题（预览弹窗里改，空串表示未命名）。 */
    public static void setTitle(Context context, String id, String title) {
        try {
            List<Item> items = load(context);
            boolean changed = false;
            for (Item item : items) {
                if (item.id.equals(id)) {
                    item.title = title == null ? "" : title;
                    changed = true;
                }
            }
            if (changed) {
                saveLibrary(context, items);
            }
        } catch (Exception ignored) {
        }
    }

    /** 读取某张壁纸的标题，无记录/未命名返回空串。 */
    public static String getTitle(Context context, String id) {
        try {
            for (Item item : load(context)) {
                if (item.id.equals(id)) {
                    return item.title == null ? "" : item.title;
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    /**
     * 记下这次成品图是从原图的哪块矩形导出的（原图像素坐标），供下次进编辑页复原取景框。
     * 只在「本次编辑的源确实是原图」时调用：源是成品图时那块矩形的坐标意义是成品图坐标，
     * 跟 src_w/src_h 想记的原图尺寸不同源，读回来会给出错误构图。
     * 读-改-写整份 library.json：这条只有编辑页确认那一下触发，属用户手速级并发，不额外加锁。
     */
    public static void saveCropRect(Context context, String id, int srcWidth, int srcHeight,
                                    float left, float top, float right, float bottom) {
        if (id == null || id.isEmpty() || srcWidth <= 0 || srcHeight <= 0) {
            return;
        }
        try {
            List<Item> items = load(context);
            boolean changed = false;
            for (Item item : items) {
                if (!item.id.equals(id)) {
                    continue;
                }
                item.srcWidth = srcWidth;
                item.srcHeight = srcHeight;
                item.cropLeft = left;
                item.cropTop = top;
                item.cropRight = right;
                item.cropBottom = bottom;
                changed = true;
            }
            if (changed) {
                saveLibrary(context, items);
            }
        } catch (Exception ignored) {
        }
    }

    /** 成品图存 JPEG 还是无损 PNG（裁剪页那粒胶囊选，默认 JPEG）。 */
    public static boolean fullPrefersJpeg(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(FULL_JPEG_KEY, true);
    }

    /** 记住用户选的那一档。只影响此后写入的成品图，已入库的一张都不动。 */
    public static void setFullPrefersJpeg(Context context, boolean jpeg) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(FULL_JPEG_KEY, jpeg).apply();
    }

    /**
     * 写成品图：按 {@link #fullPrefersJpeg} 选 JPEG q90 或无损 PNG。
     *
     * <p>写完必须删掉另一种扩展名那份：读侧 {@code getFullFile} 是先认 .png 再回退 .jpg，
     * 留着旧的那份就把刚写的这张盖住了 —— 换了格式重编时尤其致命（屏上看的还是改动前那张），
     * 而且白占一份空间。新导入、分叉、重编三处落盘都走这里，所以扩展名只在这一处决定。
     */
    private static void writeFull(Context context, String id, Bitmap bitmap) throws IOException {
        File fullDir = new File(context.getFilesDir(), DIR_FULL);
        if (!fullDir.exists()) {
            fullDir.mkdirs();
        }
        boolean jpeg = fullPrefersJpeg(context);
        FileOutputStream fullOut = new FileOutputStream(
                new File(fullDir, id + (jpeg ? LEGACY_FULL_EXT : FULL_EXT)));
        bitmap.compress(jpeg ? Bitmap.CompressFormat.JPEG : Bitmap.CompressFormat.PNG,
                jpeg ? FULL_JPEG_QUALITY : 100, fullOut);
        fullOut.close();
        Files.deleteIfExists(
                new File(fullDir, id + (jpeg ? FULL_EXT : LEGACY_FULL_EXT)).toPath());
        // 刚写完，格式是确定的事实，直接填进标记表（省掉绑格子时那一次摸盘）
        FULL_JPEG_MARKS.put(id, jpeg);
    }

    /**
     * 这张的成品图是不是 JPEG（网格右上角那粒角标用）。盘上两种都没有时返回 null ——
     * 那种格子点开是黑图，角标宁可什么都不画，也别猜一个看着对的。
     *
     * <p>优先序必须跟 {@code getFullFile} 一样先认 {@code .png}：万一两种并存（半途失手过），
     * 角标要说的是「上屏实际读到的那一份」，而不是盘上碰巧存在过的某一份。
     */
    public static Boolean fullIsJpeg(Context context, String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        Boolean cached = FULL_JPEG_MARKS.get(id);
        if (cached != null) {
            return cached;
        }
        File dir = new File(context.getFilesDir(), DIR_FULL);
        boolean jpeg;
        if (new File(dir, id + FULL_EXT).isFile()) {
            jpeg = false;
        } else if (new File(dir, id + LEGACY_FULL_EXT).isFile()) {
            jpeg = true;
        } else {
            return null;
        }
        FULL_JPEG_MARKS.put(id, jpeg);
        return jpeg;
    }

    /** 抹掉全部格式标记：只有「整批覆盖成品图文件」的那条路需要它（从备份包还原）。 */
    public static void clearFullFormatMarks() {
        LIVE_MARKS.clear();
        FULL_JPEG_MARKS.clear();
    }

    /**
     * 这张有没有实况段（网格左上角那粒 LIVE 角标用）。判法直接交给引擎播实况的
     * {@link com.example.wallswitch.gl.MotionSource#locate} —— 三层识别（小红书尾部 LIVE_&lt;N&gt; / XMP 头 / JPEG EOI 后扫
     * ftyp）与上屏判定共用，永远同一个答案。原图是新导入永远齐的；存量老壁纸没有原图，
     * 就当没有实况。文件改过不用管：MotionSource 的缓存指纹带 mtime，自动重算。
     */
    public static boolean isLive(Context context, String id) {
        if (id == null || id.isEmpty()) {
            return false;
        }
        Boolean cached = LIVE_MARKS.get(id);
        if (cached != null) {
            return cached;
        }
        boolean live = false;
        try {
            File original = getOriginalFile(context, id);
            live = original.isFile()
                    && com.example.wallswitch.gl.MotionSource.locate(original) != null;
        } catch (Exception ignored) {
        }
        LIVE_MARKS.put(id, live);
        return live;
    }

    /** 确认导入：保存成品图与缩略图、按所属壁纸库追加元数据，并把收件箱那份原图搬进 originals/。 */
    public static Item confirmImport(Context context, String inboxId, Bitmap edited, String libId) throws Exception {
        Bitmap bitmap = edited;
        if (bitmap == null) {
            File sourceFile = getInboxFile(context, inboxId);
            bitmap = decodeBounded(sourceFile, maxWallpaperDim(context));
        }
        if (bitmap == null) {
            throw new IllegalStateException("图片解码失败");
        }
        String id = inboxId;
        writeFull(context, id, bitmap);
        // 保存缩略图（中心正方形，边长按屏幕上的方格宽度取）
        writeThumb(context, id, bitmap);
        // 追加元数据（归属指定壁纸库，标题取导入时记录的原文件名）
        List<Item> items = load(context);
        Item item = new Item();
        item.id = id;
        item.joinLib(libId);
        item.title = pendingTitle(context, inboxId);
        items.add(item);
        saveLibrary(context, items);
        // 这里以前会「顺手导出一份到导出目录」，v3.89 起删掉：导出目录改成只有两个手动入口
        // （设置页「立即导出全部壁纸」与「备份整库」）。自动导出会让用户在自己没动手的情况下
        // 往那个目录里塞图，而且重编过的壁纸并不会更新那一份，只会叠副本。
        // 原图搬进 originals/ 长期留存（重复调整的源）：用移动而不是复制，省一次全量 IO，
        // 也不会让收件箱留下已经入库的孤儿文件。搬不走（极少数 ROM 的权限限制）就退回原来的
        // 删除语义 —— 元数据与成品图此时都已落盘，这一步失败不该让调用方误报「保存失败」。
        File inboxFile = getInboxFile(context, inboxId);
        File originalFile = getOriginalFile(context, id);
        try {
            originalFile.getParentFile().mkdirs();
            Files.move(inboxFile.toPath(), originalFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception | OutOfMemoryError e) {
            try {
                Files.deleteIfExists(inboxFile.toPath());
            } catch (Exception ignored) {
            }
        }
        return item;
    }

    /**
     * 重编分叉：一张挂在几个库里时「只改这个库」那一条。用这次导出的成品图<b>新造一条壁纸</b>
     * （新 id、新成品图、新缩略图，原图字节拷一份过来）挂到当前库，同时把原来那条从当前库摘掉
     * —— 原来那条留给别的库，它们看到的还是改动前的取景。
     *
     * <p>为什么原图要拷而不是共用：原图是按 id 存在 {@code originals/<id>} 的，两个 id 共用一份就得
     * 引入引用计数，备份包「每张三个文件」的布局也会跟着变形。拷一次的代价是那张原图字节在盘上
     * 多存一遍（几 MB），换来的是这两张以后能各自分头取景、各自重复调整。
     *
     * <p>顺序是先写文件、后写表（与摘除那条路相反）：这里最坏的失手方式是留下几个没人引用的文件，
     * 而反过来一旦整表写失败就成「记录在、点开是黑图」。
     *
     * @return 新建那条的 id；前置条件不成立（不是多归属、当前库对不上、没有可保存的图）时返回
     *         null，调用方据此退回覆盖，不丢用户这次的编辑
     */
    public static String forkIntoLib(Context context, String srcId, Bitmap result, String libId)
            throws Exception {
        Item src = get(context, srcId);
        if (src == null || result == null || libId == null || libId.isEmpty()
                || !src.inLib(libId) || src.libIds.size() < 2) {
            return null;
        }
        String newId = UUID.randomUUID().toString();
        writeFull(context, newId, result);
        writeThumb(context, newId, result);
        File srcOriginal = getOriginalFile(context, srcId);
        if (srcOriginal.isFile() && srcOriginal.length() > 0L) {
            File freshOriginal = getOriginalFile(context, newId);
            try {
                freshOriginal.getParentFile().mkdirs();
                Files.copy(srcOriginal.toPath(), freshOriginal.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception | OutOfMemoryError e) {
                // 半截原图比没有原图更糟（解不开还占地方），清掉后退成「缺原图的存量壁纸」
                try {
                    Files.deleteIfExists(freshOriginal.toPath());
                } catch (Exception ignored) {
                }
            }
        }
        // 新条目挂上当前库 + 原来那条从当前库摘掉：必须同一次改写落定，
        // 分两次写的话中途被杀就留下「两张都在本库」或「两张都不在本库」
        List<Item> items = load(context);
        Item fresh = new Item();
        fresh.id = newId;
        fresh.joinLib(libId);
        fresh.title = src.title;
        items.add(fresh);
        for (Item item : items) {
            if (item.id.equals(srcId)) {
                item.leaveLib(libId);
            }
        }
        saveLibrary(context, items);
        return newId;
    }

    /**
     * 用裁剪结果覆盖一张已入库壁纸的全图与缩略图（保留 id、标题与归属库）。
     * 点壁纸格重新裁剪后调用；缩略图一并重生成，列表下次刷新显示新图。
     */
    public static void overwrite(Context context, String id, Bitmap bitmap) throws IOException {
        if (bitmap == null) {
            throw new IOException("没有可保存的图像");
        }
        writeFull(context, id, bitmap);
        writeThumb(context, id, bitmap);
    }

    /** 取消导入：删除收件箱中的待编辑文件与其中的标题记录。 */
    public static void cancelImport(Context context, String inboxId) {
        try {
            File inboxFile = getInboxFile(context, inboxId);
            Files.deleteIfExists(inboxFile.toPath());
        } catch (Exception ignored) {
        }
        context.getSharedPreferences(TITLE_PREFS, Context.MODE_PRIVATE).edit()
                .remove(inboxId)
                .apply();
    }

    /** 列出收件箱中待编辑的文件 id（按名称排序，保证顺序稳定）。 */
    public static List<String> pendingInbox(Context context) {
        List<String> ids = new ArrayList<>();
        File inboxDir = new File(context.getFilesDir(), DIR_INBOX);
        File[] files = inboxDir.listFiles();
        if (files == null) {
            return ids;
        }
        for (File file : files) {
            // 空文件是复制中断残留的半截文件，无法解码，直接清理并跳过
            if (file.length() <= 0L) {
                try {
                    Files.deleteIfExists(file.toPath());
                } catch (Exception ignored) {
                }
                continue;
            }
            ids.add(file.getName());
        }
        Collections.sort(ids);
        return ids;
    }

    /**
     * 彻底删除一张壁纸：三份文件与记录一起没，<b>所有库</b>里都不再出现它。
     * 只想让它从某一个库消失（还挂在别的库时不碰文件）用 {@link #removeFromLib}。
     */
    public static void delete(Context context, String id) {
        try {
            destroyStoredFiles(context, id);
            List<Item> items = load(context);
            List<Item> remain = new ArrayList<>();
            for (Item item : items) {
                if (!item.id.equals(id)) {
                    remain.add(item);
                }
            }
            saveLibrary(context, remain);
            // 顺带清掉可能残留的待编辑标题记录
            context.getSharedPreferences(TITLE_PREFS, Context.MODE_PRIVATE).edit()
                    .remove(id)
                    .apply();
        } catch (Exception ignored) {
        }
    }

    /**
     * 删掉一张壁纸的三份文件（成品图 / 缩略图 / 原图）。
     * 只在「这张不再挂任何库」时调用 —— 多归属下这些文件是各库共用的，提前删就让别的库也黑图。
     * 实况段就躺在原图字节尾部，所以它跟着原图一起走，不需要单独清理。
     */
    private static void destroyStoredFiles(Context context, String id) throws IOException {
        deleteFullFiles(context, id);
        try {
            Files.deleteIfExists(getThumbFile(context, id).toPath());
            // 原图跟着条目一起走，否则留下的孤儿文件既占地方（几百 KB～几 MB）又再也认不回来
            Files.deleteIfExists(getOriginalFile(context, id).toPath());
        } catch (Exception | OutOfMemoryError ignored) {
        }
    }

    /** 列出指定壁纸库内的全部壁纸（按添加顺序）。 */
    public static List<Item> loadByLib(Context context, String libId) {
        List<Item> result = new ArrayList<>();
        for (Item item : load(context)) {
            if (item.inLib(libId)) {
                result.add(item);
            }
        }
        return result;
    }

    /**
     * 各库的张数（一次遍历算完，省得按库循环各解析一遍 library.json）。
     * 共享的那张在它挂着的每个库里各数一次 —— 「这一库里能看到几张」就是用户要的那个数，
     * 不是「手机上有几种不同的图」。
     */
    public static Map<String, Integer> libMemberCounts(Context context) {
        Map<String, Integer> counts = new HashMap<>();
        for (Item item : load(context)) {
            for (String libId : item.libIds) {
                Integer seen = counts.get(libId);
                counts.put(libId, seen == null ? 1 : seen + 1);
            }
        }
        return counts;
    }

    /**
     * 把若干张壁纸<b>复制</b>到某个库（多归属下这就是「再挂一个归属」，零文件 IO）。
     * 已经在这个库里的跳过。
     *
     * @return 真正新增的张数（调用方拿 ids.size() 减它就是「本来就在」的张数）
     */
    public static int addToLib(Context context, List<String> ids, String libId) throws Exception {
        if (ids == null || ids.isEmpty() || libId == null || libId.isEmpty()) {
            return 0;
        }
        List<Item> items = load(context);
        int added = 0;
        for (Item item : items) {
            if (ids.contains(item.id) && !item.inLib(libId)) {
                item.joinLib(libId);
                added++;
            }
        }
        if (added > 0) {
            saveLibrary(context, items);
        }
        return added;
    }

    /**
     * 把若干张壁纸从 fromLibId <b>移动</b>到 toLibId：摘掉来源的归属、挂上目标的归属。
     * 摘与挂是同一次改写，所以没有任何一张会因为移动而变成孤儿（返回值恒为 0，
     * 留着只是让三条动作共用一个收尾口径）。
     *
     * @return 因本次操作而彻底消失的张数（移动恒为 0）
     */
    public static int moveToLib(Context context, List<String> ids, String fromLibId, String toLibId)
            throws Exception {
        return changeMembership(context, ids, fromLibId, toLibId);
    }

    /**
     * 把若干张壁纸从某个库<b>移除</b>（这一个库不再看得到它们）。
     * 只挂在这一个库的那些会被彻底删掉（成品图、缩略图、原图三份文件）。
     *
     * @return 被彻底删掉的张数
     */
    public static int removeFromLib(Context context, List<String> ids, String fromLibId)
            throws Exception {
        return changeMembership(context, ids, fromLibId, null);
    }

    /**
     * 摘除前的预演，给确认弹窗算账用。
     *
     * @return {@code {会被彻底删掉的张数, 还挂在别的库、只是这个库不再显示的张数}}
     */
    public static int[] previewRemovalFromLib(Context context, List<String> ids, String fromLibId) {
        int destroyed = 0;
        int shared = 0;
        if (ids == null || fromLibId == null) {
            return new int[]{0, 0};
        }
        for (Item item : load(context)) {
            if (!ids.contains(item.id) || !item.inLib(fromLibId)) {
                continue;
            }
            // 归属多于一个 = 摘掉这一个还有地方看它
            if (item.libIds.size() > 1) {
                shared++;
            } else {
                destroyed++;
            }
        }
        return new int[]{destroyed, shared};
    }

    /**
     * 移动与移除的共同主体：一次读-改-写整份表，只在真改动时落盘。toLibId 传 null = 只摘不挂。
     *
     * <p>移动过去的那几条写回时挪到<b>表尾</b>（只移动做，移除不动原位）：库内的顺序就是它们在
     * library.json 里出现的先后，留在原位等于插进目标库队列的中间，把那个库「顺序模式下次从第几张
     * 接着放」的索引整体后移一位 —— 到点会跳过或重复一张。挪到表尾就和导入那一条链路完全一致。
     */
    private static int changeMembership(Context context, List<String> ids, String fromLibId,
                                        String toLibId) throws Exception {
        if (ids == null || ids.isEmpty() || fromLibId == null || fromLibId.isEmpty()) {
            return 0;
        }
        boolean move = toLibId != null && !toLibId.isEmpty();
        List<Item> items = load(context);
        List<Item> keep = new ArrayList<>();
        List<Item> moved = new ArrayList<>();
        List<String> orphaned = new ArrayList<>();
        boolean changed = false;
        for (Item item : items) {
            if (ids.contains(item.id) && item.inLib(fromLibId)) {
                changed = true;
                item.leaveLib(fromLibId);
                if (move) {
                    // 先挂目标库再判归属空不空：移动过去的那张永远不会变成孤儿
                    if (!item.inLib(toLibId)) {
                        item.joinLib(toLibId);
                    }
                    moved.add(item);
                    continue;
                }
                if (item.libIds.isEmpty()) {
                    // 只挂在这一个库里、这回又没别处去：记录不再进表，文件排到表写成功之后才删
                    orphaned.add(item.id);
                    continue;
                }
            }
            keep.add(item);
        }
        if (!changed) {
            return 0;
        }
        keep.addAll(moved);
        // 先写表再删文件：反过来的话整表写失败就成「记录还在、点开是黑图」——
        // 而这份表是唯一真相，宁可留下几个没人引用的文件（占地方，但不骗界面）
        saveLibrary(context, keep);
        for (String id : orphaned) {
            try {
                destroyStoredFiles(context, id);
            } catch (Exception | OutOfMemoryError ignored) {
                // 表里已经没有这张了，文件删不掉就只是白占地方，不该让整个整理动作报失败
            }
            context.getSharedPreferences(TITLE_PREFS, Context.MODE_PRIVATE).edit()
                    .remove(id)
                    .apply();
        }
        return orphaned.size();
    }

    /**
     * 删库时清归属：从被删的库里摘掉这些壁纸，<b>还挂在别的库的一张都不动</b>
     * （文件与记录都留着，那个库照常轮播）；只挂在这个库的才连带删三份文件。
     */
    public static void deleteByLib(Context context, String libId) {
        try {
            List<Item> items = load(context);
            List<Item> remain = new ArrayList<>();
            List<String> orphaned = new ArrayList<>();
            boolean changed = false;
            for (Item item : items) {
                if (!item.inLib(libId)) {
                    remain.add(item);
                    continue;
                }
                changed = true;
                if (item.leaveLib(libId)) {
                    // 这库是它唯一的归属：记录连同三份文件一起没，跟删掉一张同口径
                    orphaned.add(item.id);
                } else {
                    remain.add(item);
                }
            }
            if (!changed) {
                return;
            }
            saveLibrary(context, remain);
            for (String id : orphaned) {
                destroyStoredFiles(context, id);
            }
        } catch (Exception ignored) {
        }
    }

    /** 旧版迁移：把没有归属的历史壁纸全部归入指定库（仅补空值，幂等）。 */
    public static void migrateLegacyToLib(Context context, String libId) {
        try {
            List<Item> items = load(context);
            boolean changed = false;
            for (Item item : items) {
                if (item.libIds.isEmpty()) {
                    item.joinLib(libId);
                    changed = true;
                }
            }
            if (changed) {
                saveLibrary(context, items);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 读取某张壁纸的缩略图，读不到返回 null。
     * 缩略图文件缺失或解不开时**就地退回从全图生成**（不写盘，避免在主线程写文件）——
     * 老版本留下的缺图会因此不再显示成占位图标（占位图标只留给「库里确实没有壁纸」这种情况）。
     */
    public static Bitmap getThumb(Context context, String id) {
        Bitmap thumb = BitmapFactory.decodeFile(getThumbFile(context, id).getAbsolutePath());
        if (thumb != null) {
            return thumb;
        }
        File full = getFullFile(context, id);
        if (!full.exists()) {
            return null;
        }
        int side = thumbSide(context);
        Bitmap src = decodeBounded(full, side * 2);
        Bitmap square = centerSquare(src);
        Bitmap scaled = scaleToFit(square, side);
        if (src != null && src != square && !src.isRecycled()) {
            src.recycle();
        }
        if (square != null && square != scaled && square != src && !square.isRecycled()) {
            square.recycle();
        }
        return scaled;
    }

    /**
     * 小组件专用的小缩略图：按 {@code maxSide} 降采样，优先读 thumbs/ 里现成的正方形 JPEG，
     * 缺图时退回全图（同样只解码到 {@code maxSide}，不写盘）。
     *
     * <p>为什么不让小组件直接用 {@link #getThumb}：那份是给两列方格看的 384~768px 正方形，
     * 解码出来是 0.6~2.3MB 的位图，而 RemoteViews 要经 Binder 递交给桌面、单次事务上限约 1MB ——
     * 超了不抛异常，表现是小组件静默不更新。小组件一格最大 84dp（2.75 密度约 231px），
     * 调用方按格子像素要，最多夹到 256px（v3.98 从 192 抬上来，账见
     * {@code WidgetConsoleProvider.THUMB_MAX_PX}）。
     */
    public static Bitmap getWidgetThumb(Context context, String id, int maxSide) {
        Bitmap src = decodeBounded(getThumbFile(context, id), maxSide);
        if (src == null) {
            File full = getFullFile(context, id);
            src = full != null && full.exists() ? decodeBounded(full, maxSide) : null;
        }
        if (src == null) {
            return null;
        }
        // 一律交正方形出去：现成的 thumbs/ 本来就是中心方图，但缺图退回全图时解出来是竖长条，
        // 小组件那一格是正方形 —— 非方图要么被 fitCenter 留出上下白条，要么被 centerCrop 砍头尾
        Bitmap square = centerSquare(src);
        if (square != src && !src.isRecycled()) {
            src.recycle();
        }
        return square;
    }

    /**
     * 缩略图边长：按「两列方格」在屏幕上的实际显示宽度取。
     * 老版本固定长边 256px，在 1080p 屏上单格约 540px —— 放大 2 倍多，肉眼看就是马赛克；
     * 现在跟着屏幕走（方形 JPEG，边长 540 约 40~80KB），给上下限控内存。
     */
    public static int thumbSide(Context context) {
        int cell = context.getResources().getDisplayMetrics().widthPixels / 2;
        return Math.max(THUMB_MIN_SIDE, Math.min(cell, THUMB_MAX_SIDE));
    }

    /** 中心正方形裁剪：库行缩略图与两列方格都是 centerCrop 正方形，存正方形最省内存又不失真。 */
    private static Bitmap centerSquare(Bitmap src) {
        if (src == null) {
            return null;
        }
        int w = src.getWidth();
        int h = src.getHeight();
        int side = Math.min(w, h);
        if (w == side && h == side) {
            return src;
        }
        return Bitmap.createBitmap(src, (w - side) / 2, (h - side) / 2, side, side);
    }

    /**
     * 生成并写入某张壁纸的缩略图（中心正方形 + 按屏幕取的边长）。
     * 只回收自己新建的中间位图，入参位图归调用方管（scaleToFit 没缩时返回的就是入参本身）。
     */
    private static void writeThumb(Context context, String id, Bitmap bitmap) throws IOException {
        if (bitmap == null) {
            return;
        }
        File dir = new File(context.getFilesDir(), DIR_THUMB);
        if (!dir.exists() && !dir.mkdirs()) {
            return;
        }
        Bitmap square = centerSquare(bitmap);
        Bitmap thumb = scaleToFit(square, thumbSide(context));
        if (thumb != null) {
            FileOutputStream out = new FileOutputStream(getThumbFile(context, id));
            thumb.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out);
            out.close();
        }
        if (square != null && square != bitmap) {
            square.recycle();
        }
        if (thumb != null && thumb != square && thumb != bitmap) {
            thumb.recycle();
        }
    }

    /**
     * 一次性把老规格缩略图重做成新规格（v3.15：256px 等比 → 中心正方形 + 按屏幕取边长）。
     * 只挑「缺失 / 不是正方形 / 边长不足」的重做，逐张判断所以中途被杀下次启动接着做；
     * 整轮跑完写标记，之后不再扫描。含解码与编码，调用方必须放后台线程。
     */
    public static void regenerateThumbs(Context context) {
        if (context == null) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (prefs.getBoolean(THUMB_REGEN_KEY, false)) {
            return;
        }
        int side = thumbSide(context);
        for (Item item : load(context)) {
            File thumbFile = getThumbFile(context, item.id);
            if (!needsThumbRegen(thumbFile, side)) {
                continue;
            }
            File full = getFullFile(context, item.id);
            if (!full.exists()) {
                continue;
            }
            try {
                Bitmap src = decodeBounded(full, side * 2);
                writeThumb(context, item.id, src);
                if (src != null && !src.isRecycled()) {
                    src.recycle();
                }
            } catch (Exception | OutOfMemoryError ignored) {
            }
        }
        prefs.edit().putBoolean(THUMB_REGEN_KEY, true).apply();
    }

    /** 缩略图要不要重做：文件不存在、解不出尺寸、不是正方形、或边长明显不足。 */
    private static boolean needsThumbRegen(File thumbFile, int side) {
        if (!thumbFile.exists()) {
            return true;
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(thumbFile.getAbsolutePath(), bounds);
        return bounds.outWidth <= 0 || bounds.outHeight <= 0
                || bounds.outWidth != bounds.outHeight
                || bounds.outWidth < side;
    }

    /** 按 id 查壁纸元数据，查不到返回 null。 */
    public static Item get(Context context, String id) {
        for (Item item : load(context)) {
            if (item.id.equals(id)) {
                return item;
            }
        }
        return null;
    }

    /** 获取某张壁纸的全图文件（新数据是无损 PNG，历史数据是 JPEG，都兼容）。 */
    public static File getFullFile(Context context, String id) {
        File dir = new File(context.getFilesDir(), DIR_FULL);
        File current = new File(dir, id + FULL_EXT);
        if (current.exists()) {
            return current;
        }
        File legacy = new File(dir, id + LEGACY_FULL_EXT);
        return legacy.exists() ? legacy : current;
    }

    /** 删除某张壁纸的全图（PNG 与历史 JPEG 都要删，避免切到无损后留下孤儿文件）。 */
    private static void deleteFullFiles(Context context, String id) throws IOException {
        File dir = new File(context.getFilesDir(), DIR_FULL);
        Files.deleteIfExists(new File(dir, id + FULL_EXT).toPath());
        Files.deleteIfExists(new File(dir, id + LEGACY_FULL_EXT).toPath());
        FULL_JPEG_MARKS.remove(id);
    }

    /** 获取收件箱中的待编辑文件。 */
    public static File getInboxFile(Context context, String inboxId) {
        File dir = new File(context.getFilesDir(), DIR_INBOX);
        return new File(dir, inboxId);
    }

    /** 某张壁纸的原图文件（可能不存在：历史数据没留存，或 confirmImport 那一步移动失败）。 */
    public static File getOriginalFile(Context context, String id) {
        return new File(new File(context.getFilesDir(), DIR_ORIGINAL), id);
    }

    /** 原图在不在。空文件按「没有」算 —— 那是复制中断的半截文件，解不开也裁不了。 */
    public static boolean hasOriginal(Context context, String id) {
        if (id == null || id.isEmpty()) {
            return false;
        }
        File f = getOriginalFile(context, id);
        return f.isFile() && f.length() > 0L;
    }

    /** 获取某张壁纸的缩略图文件。 */
    private static File getThumbFile(Context context, String id) {
        File dir = new File(context.getFilesDir(), DIR_THUMB);
        return new File(dir, id + ".jpg");
    }

    /** 按最长边上限解码文件位图，超大图自动降采样防 OOM；解码失败返回 null。 */
    static Bitmap decodeBounded(File file, int maxDim) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = calcSampleSize(bounds.outWidth, bounds.outHeight, maxDim);
        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        Bitmap scaled = scaleToFit(bitmap, maxDim);
        // 缩放会产生第二份位图，原始那份要立刻回收，否则大图会瞬时占两份内存
        if (scaled != bitmap && bitmap != null && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
        return scaled;
    }

    /** 计算 inSampleSize（2 的幂），保证解码后最长边不超过 maxDim。 */
    private static int calcSampleSize(int width, int height, int maxDim) {
        long longest = Math.max(width, height);
        int sample = 1;
        // 只要「再放粗一倍后最长边仍不低于 maxDim」就继续放粗，保证解码结果不小于目标分辨率。
        // 老逻辑是「一旦低于 maxDim 就停」，会把 8981 的源图解成 2245（2 的幂只能取 1/2/4…，
        // 4 就掉到 2245、2 又太大），于是裁剪取景框被放大、导出连屏幕分辨率都达不到。
        while (sample * 2L * maxDim <= longest) {
            sample *= 2;
        }
        // 像素预算兜底：超过堆预算就继续放粗，宁可略糊也不能 OOM
        long budget = decodePixelBudget();
        while ((long) (width / sample) * (height / sample) > budget) {
            sample *= 2;
        }
        return sample;
    }

    /** 解码像素预算：应用堆上限的 1/4 再按 4 字节/像素折算，下限 4MP、上限 24MP。 */
    private static long decodePixelBudget() {
        long pixels = Runtime.getRuntime().maxMemory() / 16;
        if (pixels < 4L * 1024 * 1024) {
            return 4L * 1024 * 1024;
        }
        return Math.min(pixels, 24L * 1024 * 1024);
    }

    /**
     * 解码裁剪页源图：在<b>预览</b>预算内取最小的 2 的幂采样（采样越小越清晰），解码后不再二次缩小。
     *
     * <p>预算为什么不再是"堆的 1/16、封顶 24MP"：v3.9 起导出走 {@link #decodeRegion} 回原图取那块
     * 原生像素，这张预览位图只剩下两件事——在屏上显示、算取景框矩形，它的像素数<b>不进成品图</b>。
     * 按老预算，README 里那张 6736×8981 的原图会以 sample=2 解成 15MP（约 60MB）常驻编辑页，
     * 连导几张就是几次几十兆的分配与回收；换成 {@link #cropSourceBudget} 后同一张走 sample=4，
     * 约 3.8MP（15MB）。留 2 倍屏幕的余量，为的是铺满那一档之后稍微捏一点还有真实像素。
     *
     * <p>区域解码拿不到的格式（HEIF）需要更多像素，那一路由 {@link #decodeRegion} 内部的整图兜底
     * 自己按区域算采样，不再依赖这张预览图的大小。
     */
    public static Bitmap decodeCropSource(Context context, File file) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        long budget = cropSourceBudget(context);
        int sample = 1;
        while (pixelsAt(bounds.outWidth, bounds.outHeight, sample) > budget) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
    }

    /**
     * 裁剪页预览的像素预算：屏幕像素的 2 倍，但仍不超过 {@link #decodePixelBudget()}，
     * 免得在小屏机上把这条链的 OOM 保险一并削掉。
     */
    private static long cropSourceBudget(Context context) {
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        return Math.min(decodePixelBudget(), 2L * dm.widthPixels * dm.heightPixels);
    }

    /**
     * 区域解码拿不到 {@code BitmapRegionDecoder} 时的第二条（HEIF 首当其冲——整图能解能显示，
     * 区域解码器却不认）：整图按「区域缩到 maxDim 后仍有足够真实像素」的 2 的幂采样解一张，
     * 再把区域映射到那张图上裁下来。
     *
     * <p>为什么在存储层单开一条、而不是让调用方退回"从预览图裁"：预览图现在只有屏幕 2 倍像素，
     * 捏着放大过就撑不起 maxDim，成品图会跟着掉分辨率。坐标换算跟 {@link #decodeRegion} 同一套口径
     * （两轴各按「解码图 / 原图」比例），两条并排放着，不在调用方另立第二套换算。
     */
    private static Bitmap decodeRegionFromWhole(File file, Rect region, int maxDim) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        int fullW = bounds.outWidth;
        int fullH = bounds.outHeight;
        if (fullW <= 0 || fullH <= 0) {
            return null;
        }
        int longest = Math.max(region.width(), region.height());
        int sample = 1;
        // 放粗条件与 decodeRegion 一致：再粗一倍后区域仍不低于 maxDim 就继续放粗
        while (sample * 2L * maxDim <= longest) {
            sample *= 2;
        }
        // 第二条上限按「整图」算，跟 decodeRegion 按「区域」算不一样：这条兜底解码的是整张图而不是那一块。
        // 少了它，一张 6736×8981 的 HEIF 在铺满那一档会按 sample=1 解出 60MP（约 240MB）——直接 OOM
        long budget = decodePixelBudget();
        while (pixelsAt(fullW, fullH, sample) > budget) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap full = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        if (full == null) {
            return null;
        }
        Bitmap scaled = null;
        try {
            float kx = full.getWidth() / (float) fullW;
            float ky = full.getHeight() / (float) fullH;
            Rect src = new Rect(
                    Math.max(0, Math.round(region.left * kx)),
                    Math.max(0, Math.round(region.top * ky)),
                    Math.min(full.getWidth(), Math.round(region.right * kx)),
                    Math.min(full.getHeight(), Math.round(region.bottom * ky)));
            if (src.width() <= 0 || src.height() <= 0) {
                return null;
            }
            // 矩形正好等于整张图时 createBitmap 直接返回同一实例（省一次几十 MB 的拷贝），
            // 所以回收要按身份判：这份像素是不是归 full 管、是不是就是最终要交出去的那份
            Bitmap sub = Bitmap.createBitmap(full, src.left, src.top, src.width(), src.height());
            scaled = scaleToFit(sub, maxDim);
            if (sub != full && sub != scaled && !sub.isRecycled()) {
                sub.recycle();
            }
            return scaled;
        } catch (Exception | OutOfMemoryError e) {
            return null;
        } finally {
            // scaled 就是 full 时（小图、没采样也没缩放）不能回收，否则交出的是废位图
            if (scaled != full && !full.isRecycled()) {
                full.recycle();
            }
        }
    }

    /**
     * 只解码原图里的某个区域：导出时按实际取景框回原图取那一块，
     * 这样「放大多少倍」都能拿到原图分辨率，不再依赖事先猜测的放大余量。
     *
     * 采样取「该区域最长边不低于 maxDim」的最小 2 的幂（越小越清晰），解完再精确缩到 maxDim。
     * 区域解码器不认的格式（HEIF）改走 {@link #decodeRegionFromWhole}，不再向调用方报缺；
     * 只有区域越界、文件读不动这类情况才返回 null，调用方回退到「从内存位图裁」。
     */
    public static Bitmap decodeRegion(File file, Rect region, int maxDim) {
        if (file == null || region == null || region.width() <= 0 || region.height() <= 0) {
            return null;
        }
        BitmapRegionDecoder decoder = null;
        try {
            decoder = BitmapRegionDecoder.newInstance(file.getAbsolutePath(), false);
            if (decoder == null) {
                return decodeRegionFromWhole(file, region, maxDim);
            }
            int longest = Math.max(region.width(), region.height());
            int sample = 1;
            while (sample * 2L * maxDim <= longest) {
                sample *= 2;
            }
            long budget = decodePixelBudget();
            while (pixelsAt(region.width(), region.height(), sample) > budget) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap regionBitmap = decoder.decodeRegion(region, opts);
            if (regionBitmap == null) {
                return null;
            }
            Bitmap scaled = scaleToFit(regionBitmap, maxDim);
            if (scaled != regionBitmap && !regionBitmap.isRecycled()) {
                regionBitmap.recycle();
            }
            return scaled;
        } catch (Exception | OutOfMemoryError e) {
            return null;
        } finally {
            if (decoder != null) {
                decoder.recycle();
            }
        }
    }

    /** 按采样倍数估算解码后的像素数（向上取整，宁可保守）。 */
    private static long pixelsAt(int width, int height, int sample) {
        return (long) Math.ceil(width / (double) sample) * (long) Math.ceil(height / (double) sample);
    }

    /** 等比缩放位图使最长边不超过 maxDim；位图为空或无需缩放时原样返回。 */
    private static Bitmap scaleToFit(Bitmap src, int maxDim) {
        if (src == null) {
            return null;
        }
        int longest = Math.max(src.getWidth(), src.getHeight());
        if (longest <= maxDim) {
            return src;
        }
        float scale = (float) maxDim / longest;
        int newW = Math.round(src.getWidth() * scale);
        int newH = Math.round(src.getHeight() * scale);
        return Bitmap.createScaledBitmap(src, newW, newH, true);
    }

    /** 整表写回 library.json。BackupStore 还原时也走这里，保证字段序列化只有一处定义。 */
    public static void saveItems(Context context, List<Item> items) throws Exception {
        saveLibrary(context, items);
    }

    /** 矩形坐标保留两位小数：0.01 像素的精度对复原取景框足够，又不至于让 library.json 啰嗦。 */
    private static double round2(float v) {
        return Math.round(v * 100d) / 100d;
    }

    /** 把列表写回 library.json（原子写：临时文件刷盘后 rename，不再有半截状态）。 */
    private static void saveLibrary(Context context, List<Item> items) throws Exception {
        JSONArray arr = new JSONArray();
        for (Item item : items) {
            JSONObject obj = new JSONObject();
            obj.put("id", item.id);
            obj.put("lib_id", item.libId == null ? "" : item.libId);
            // 归属多于一个才写这份集合：单归属的文件字节跟老版本一模一样，
            // 老版本读到不认识的 lib_ids 会直接忽略、只看 lib_id（= 第一个归属），降级回滚不丢图
            if (item.libIds.size() > 1) {
                obj.put("lib_ids", new JSONArray(item.libIds));
            }
            obj.put("title", item.title == null ? "" : item.title);
            // 有参数才写这六个键：老条目读出来 srcWidth=0，写回时不带，文件不虚胖；
            // 旧版本读到不认识的键会直接忽略，所以降级回滚不会崩
            if (item.hasCropRect()) {
                obj.put("src_w", item.srcWidth);
                obj.put("src_h", item.srcHeight);
                obj.put("crop_l", round2(item.cropLeft));
                obj.put("crop_t", round2(item.cropTop));
                obj.put("crop_r", round2(item.cropRight));
                obj.put("crop_b", round2(item.cropBottom));
            }
            arr.put(obj);
        }
        MetaFiles.writeJson(context, LIB_FILE, arr.toString());
        // 落盘成功才回填缓存（写失败抛出去，走不到这两行）：交出去的字节就是这份表，
        // 所以缓存与文件此刻必然一致，不用等下一次指纹比对再重读
        File libFile = new File(context.getFilesDir(), LIB_FILE);
        cachedItems = new ArrayList<>(items);
        cachedStamp = fileStamp(libFile);
        metaItems = items.size();
        metaBytes = libFile.length();
    }
}
