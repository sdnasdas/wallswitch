package io.github.sdnasdas.wallswitch;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 库内多选「更多 → 详细信息」那张面板的数据（v3.188）。显示哪几项由用户在确认稿里勾定：
 * 成品图尺寸/格式/大小、原图尺寸/格式/大小、是否实况/时长/分辨率/帧率、浅色或深色、夜间禁用、
 * 所在的库、正在使用、是否收藏、导入时间、上屏次数、最近一次上屏。
 *
 * <p>{@link #collect} 要读文件头、解实况段的轨道信息，<b>必须在后台线程调</b>；
 * 读不到的项一律留空（界面显示「—」），不拿别的数据去凑。下面几个格式化小函数是纯函数，单测直接测。
 */
final class WallpaperInfo {

    // ---- 成品图 ----
    int fullWidth;
    int fullHeight;
    String fullFormat;
    long fullBytes;
    // ---- 原图（originals/<id>）：缺原图的存量老壁纸 originalKept = false ----
    boolean originalKept;
    int originalWidth;
    int originalHeight;
    String originalFormat;
    long originalBytes;
    // ---- 实况段（原图尾部那段视频） ----
    boolean live;
    long liveDurationUs;
    int liveWidth;
    int liveHeight;
    float liveFps;
    // ---- 其它 ----
    int bright = -1;
    boolean nightOff;
    final List<String> libNames = new ArrayList<>();
    String inUse;
    boolean favorite;
    long importedAt;
    int shownHome;
    int shownLock;
    long lastShown;

    private WallpaperInfo() {
    }

    static WallpaperInfo collect(Context ctx, WallpaperStore.Item item) {
        WallpaperInfo info = new WallpaperInfo();
        File full = WallpaperStore.getFullFile(ctx, item.id);
        if (full.isFile()) {
            int[] size = bounds(full);
            info.fullWidth = size[0];
            info.fullHeight = size[1];
            info.fullBytes = full.length();
            info.fullFormat = full.getName().endsWith(".png") ? "PNG" : "JPEG";
        }
        File original = WallpaperStore.getOriginalFile(ctx, item.id);
        if (original.isFile() && original.length() > 0L) {
            info.originalKept = true;
            info.originalBytes = original.length();
            info.originalFormat = sniffFormat(readHead(original));
            if (item.srcWidth > 0 && item.srcHeight > 0) {
                // 裁剪矩形就是按原图像素记的，那一对宽高比解码更可靠（HEIC 在老系统上解不出边界）
                info.originalWidth = item.srcWidth;
                info.originalHeight = item.srcHeight;
            } else {
                int[] size = bounds(original);
                info.originalWidth = size[0];
                info.originalHeight = size[1];
            }
            readLive(original, info);
        }
        WallpaperStore.Item probe = new WallpaperStore.Item();
        probe.id = item.id;
        probe.bright = item.bright;
        // 存量老条目没算过亮度：在一份副本上现算，不去改缓存里那条共享的条目
        WallpaperStore.probeBrightness(ctx, probe);
        info.bright = probe.bright;
        info.nightOff = item.nightOff;
        for (String libId : item.libIds) {
            LibraryStore.Library lib = LibraryStore.get(ctx, libId);
            if (lib != null && lib.name != null) {
                info.libNames.add(lib.name);
            }
        }
        info.inUse = Switcher.inUseWhere(ctx, item.id);
        info.favorite = item.favorite;
        info.importedAt = item.importedAt;
        info.shownHome = WallpaperStats.shownCount(ctx, item.id, true);
        info.shownLock = WallpaperStats.shownCount(ctx, item.id, false);
        info.lastShown = Math.max(WallpaperStats.lastShown(ctx, item.id, true),
                WallpaperStats.lastShown(ctx, item.id, false));
        return info;
    }

    /** 浅色还是深色：与夜间过滤同一条亮度线；没算出亮度返回 null。 */
    Boolean isLight() {
        return bright < 0 ? null : bright >= Switcher.NIGHT_BRIGHT_THRESHOLD;
    }

    private static int[] bounds(File file) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        try {
            BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        } catch (Exception | OutOfMemoryError ignored) {
        }
        return new int[]{Math.max(0, opts.outWidth), Math.max(0, opts.outHeight)};
    }

    private static byte[] readHead(File file) {
        byte[] head = new byte[16];
        try (InputStream in = new FileInputStream(file)) {
            int read = 0;
            while (read < head.length) {
                int n = in.read(head, read, head.length - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            if (read < head.length) {
                byte[] shorter = new byte[read];
                System.arraycopy(head, 0, shorter, 0, read);
                return shorter;
            }
        } catch (Exception ignored) {
            return new byte[0];
        }
        return head;
    }

    /** 实况段的时长、分辨率（转正后）、帧率。轨道里没写帧率时按样本数 / 时长算。 */
    private static void readLive(File original, WallpaperInfo info) {
        long[] range = io.github.sdnasdas.wallswitch.gl.MotionSource.locate(original);
        if (range == null) {
            return;
        }
        info.live = true;
        MediaExtractor extractor = new MediaExtractor();
        try (RandomAccessFile raf = new RandomAccessFile(original, "r")) {
            extractor.setDataSource(raf.getFD(), range[0], range[1]);
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime == null || !mime.startsWith("video/")) {
                    continue;
                }
                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    info.liveDurationUs = format.getLong(MediaFormat.KEY_DURATION);
                }
                int w = intOf(format, MediaFormat.KEY_WIDTH);
                int h = intOf(format, MediaFormat.KEY_HEIGHT);
                int rotation = intOf(format, MediaFormat.KEY_ROTATION);
                int[] shown = io.github.sdnasdas.wallswitch.gl.MotionSource.displaySize(w, h, rotation);
                info.liveWidth = shown[0];
                info.liveHeight = shown[1];
                info.liveFps = fpsOf(format);
                if (info.liveFps <= 0f && info.liveDurationUs > 0L) {
                    extractor.selectTrack(i);
                    int samples = 0;
                    while (extractor.getSampleTime() >= 0 && samples < 10_000) {
                        samples++;
                        extractor.advance();
                    }
                    info.liveFps = samples * 1_000_000f / info.liveDurationUs;
                }
                break;
            }
        } catch (Exception | OutOfMemoryError ignored) {
        } finally {
            extractor.release();
        }
    }

    private static int intOf(MediaFormat format, String key) {
        try {
            return format.containsKey(key) ? format.getInteger(key) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static float fpsOf(MediaFormat format) {
        if (!format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
            return 0f;
        }
        // 有的封装写 int、有的写 float，两种都试
        try {
            return format.getInteger(MediaFormat.KEY_FRAME_RATE);
        } catch (Exception e) {
            try {
                return format.getFloat(MediaFormat.KEY_FRAME_RATE);
            } catch (Exception ignored) {
                return 0f;
            }
        }
    }

    // ==================== 纯函数（单测覆盖） ====================

    /** 按文件头认原图格式；认不出返回 null（界面显示「—」）。 */
    static String sniffFormat(byte[] h) {
        if (h == null) {
            return null;
        }
        if (h.length >= 3 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF) {
            return "JPEG";
        }
        if (h.length >= 8 && (h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G') {
            return "PNG";
        }
        if (h.length >= 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F'
                && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P') {
            return "WebP";
        }
        if (h.length >= 4 && h[0] == 'G' && h[1] == 'I' && h[2] == 'F' && h[3] == '8') {
            return "GIF";
        }
        if (h.length >= 12 && h[4] == 'f' && h[5] == 't' && h[6] == 'y' && h[7] == 'p') {
            String brand = new String(h, 8, 4, java.nio.charset.StandardCharsets.US_ASCII);
            if (brand.startsWith("avi")) {
                return "AVIF";
            }
            if (brand.startsWith("hei") || brand.startsWith("hev") || brand.equals("mif1")
                    || brand.equals("msf1")) {
                return "HEIC";
            }
        }
        return null;
    }

    /** 原图存进相册时用的扩展名与 MIME（按文件头认；认不出就当 JPEG，相册大多按内容解码）。 */
    static String[] albumTypeOf(String format) {
        if ("PNG".equals(format)) {
            return new String[]{".png", "image/png"};
        }
        if ("WebP".equals(format)) {
            return new String[]{".webp", "image/webp"};
        }
        if ("GIF".equals(format)) {
            return new String[]{".gif", "image/gif"};
        }
        if ("HEIC".equals(format)) {
            return new String[]{".heic", "image/heic"};
        }
        if ("AVIF".equals(format)) {
            return new String[]{".avif", "image/avif"};
        }
        return new String[]{".jpg", "image/jpeg"};
    }

    static String sniffFileFormat(File file) {
        return sniffFormat(readHead(file));
    }

    /** 文件大小：1024 进位，保留一位小数（「1.8 MB」「320 KB」）。 */
    static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024) {
            return Math.round(bytes / 1024f) + " KB";
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024f * 1024f));
    }

    /** 实况时长：一位小数的秒（「2.7 秒」里的「2.7」）。 */
    static String formatSeconds(long durationUs) {
        return String.format(Locale.ROOT, "%.1f", durationUs / 1_000_000f);
    }

    /** 帧率取整（59.94 显示成 60）。 */
    static int roundFps(float fps) {
        return Math.round(fps);
    }
}
