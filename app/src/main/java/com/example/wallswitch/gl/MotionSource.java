package com.example.wallswitch.gl;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 实况段定位：从**原图文件**里找出「尾部追加的那段 MP4」的字节区间。
 *
 * <p>为什么不新增第四份图片文件：实况段本来就躺在 {@code originals/<id>} 里（导入时
 * {@code Files.copy} 保留的是原始字节，尾部一段没丢，已用 sha256 与源文件逐字节比对证实）。
 * 再抽一份 {@code motions/<id>.mp4} 等于同一份字节存两遍，还要动 library.json 的序列化
 * 与备份包格式，所以这里只记「第几字节到第几字节」，播放时直接
 * {@code MediaExtractor.setDataSource(fd, offset, length)} 喂原图。
 *
 * <p>本类**只用 java.io / java.util**，不 import 任何 android 类型，
 * 因此 {@code check/MotionParseTest.java} 能直接编译这份真源码来跑断言，
 * 而不是复刻一份算式（复刻就有两处定义、就会漂）。
 *
 * <p>三层兜底，按读取成本从低到高，命中即返回：
 * <ol>
 *   <li>尾部 40 字节的 {@code LIVE_<N>} —— 小红书存下来的实况（已用真样本验过）；</li>
 *   <li>文件头 XMP 的 {@code Item:Length} / 旧字段 {@code MicroVideoOffset}
 *       —— Google 动态照片 1.0 规范（Pixel / 小米 / 三星这一系）；</li>
 *   <li>JPEG EOI 之后直接扫 {@code ftyp} —— 只写 Length 不写 Offset 的（OPPO / 一加）。</li>
 * </ol>
 * 三层都不中就当「这张没有实况」，返回 null，调用方静默走静态图，不算故障。
 */
public final class MotionSource {

    /** 小红书 trailer 的定长：两个 20 字节字段（{@code "0:1000"} 与 {@code "LIVE_<N>"}）。 */
    static final int TRAILER_BYTES = 40;
    /** 读文件头找 XMP 的窗口：XMP 一定在前几个 KB 里，64KB 足够又不会把整张图读进内存。 */
    static final int HEAD_WINDOW = 64 * 1024;
    /** 小于这个长度的文件不可能是「图 + 视频」，直接跳过，省掉两次无谓读盘。 */
    static final int MIN_FILE_BYTES = 64 * 1024;
    /** EOI 之后允许出现的填充字节上限（超过就认为不是尾部追加那种格式）。 */
    static final int FTYP_SCAN_WINDOW = 64;

    private static final Pattern P_LIVE = Pattern.compile("LIVE_(\\d{1,12})");
    /**
     * Google 动态照片的 XMP 里，条目元素是 {@code <rdf:li>}，{@code Item:} 只是**属性前缀**，
     * 所以匹配时不能拿元素名当锚点（真样本里 {@code <Item} 这种写法根本不存在）。
     * 两个方向都试一遍，因为属性顺序各家不保证。
     */
    private static final Pattern P_ITEM_LENGTH =
            Pattern.compile("Item:Semantic=\"MotionPhoto\"[^>]*Item:Length=\"(\\d{1,12})\"");
    private static final Pattern P_ITEM_LENGTH_REV =
            Pattern.compile("Item:Length=\"(\\d{1,12})\"[^>]*Item:Semantic=\"MotionPhoto\"");
    /** 兜底：只认「有 MotionPhoto 字样」的文件，取最后一个 Item:Length（视频段总是最后一个条目）。 */
    private static final Pattern P_ANY_ITEM_LENGTH =
            Pattern.compile("Item:Length=\"(\\d{1,12})\"");
    /** 旧版 Microvideo：Pixel 老机型写成元素，部分厂商写成属性，两种都收。 */
    private static final Pattern P_MICRO_VIDEO_OFFSET = Pattern.compile(
            "MicroVideoOffset\\s*=\\s*\"(\\d{1,12})\"|MicroVideoOffset[^>]*>(\\d{1,12})<");

    /** 缓存键 → 区间（null 也缓存成哨兵，免得每张静态壁纸每次上屏都读一遍盘）。 */
    private static final Map<String, long[]> CACHE = new LinkedHashMap<String, long[]>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, long[]> eldest) {
            return size() > 64;
        }
    };
    private static final long[] NONE = new long[0];

    private MotionSource() {
    }

    /**
     * 定位某张壁纸的实况段。
     *
     * @param original 原图文件（可以不存在：返回 null）
     * @return {@code {起点, 长度}}，没有实况返回 null
     */
    public static synchronized long[] locate(File original) {
        if (original == null || !original.isFile() || original.length() < MIN_FILE_BYTES) {
            return null;
        }
        long length = original.length();
        // 指纹带上长度与修改时间：编辑页「覆盖」会原地换文件，指纹变了自然重算
        String key = original.getAbsolutePath() + '#' + length + '#' + original.lastModified();
        long[] cached = CACHE.get(key);
        if (cached != null) {
            return cached == NONE ? null : cached.clone();
        }
        long[] found = locateUncached(original, length);
        CACHE.put(key, found == null ? NONE : found);
        return found == null ? null : found.clone();
    }

    private static long[] locateUncached(File file, long size) {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long[] byTrailer = parseTrailer(readTail(raf, size), size);
            if (byTrailer != null && startsWithFtyp(raf, byTrailer[0])) {
                return byTrailer;
            }
            long[] byXmp = parseXmp(readHead(raf, size), size);
            if (byXmp != null && startsWithFtyp(raf, byXmp[0])) {
                return byXmp;
            }
            return scanFtyp(raf, size);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    // ---- 三层解析（纯函数，喂字节就能断言） ----------------------------------------------

    /**
     * 第一层：尾部 40 字节里的 {@code LIVE_<N>}。
     * N 是实况段字节数，段尾正好顶到 trailer 之前，所以 {@code 起点 = 文件大小 - 40 - N}。
     *
     * @return {@code {起点, 长度}}，格式不符返回 null
     */
    static long[] parseTrailer(byte[] tail, long fileSize) {
        if (tail == null || tail.length != TRAILER_BYTES || fileSize <= TRAILER_BYTES) {
            return null;
        }
        Matcher m = P_LIVE.matcher(new String(tail, StandardCharsets.ISO_8859_1));
        if (!m.find()) {
            return null;
        }
        Long n = toLong(m.group(1));
        return n == null ? null : rangeFromEnd(fileSize - TRAILER_BYTES, n, fileSize);
    }

    /**
     * 第二层：文件头 XMP。规范 1.0 用 {@code Item:Semantic="MotionPhoto"} 那条条目上的
     * {@code Item:Length="N"}，实况段就是**最后 N 个字节**；旧版 Microvideo 用
     * {@code MicroVideoOffset}，语义同样是「从文件尾往前 N 字节是视频」。
     */
    static long[] parseXmp(byte[] head, long fileSize) {
        if (head == null || head.length == 0 || fileSize <= 0) {
            return null;
        }
        String s = new String(head, StandardCharsets.ISO_8859_1);
        Long n = firstGroup(P_ITEM_LENGTH, s);
        if (n == null) {
            n = firstGroup(P_ITEM_LENGTH_REV, s);
        }
        if (n == null && s.contains("MotionPhoto")) {
            // 属性顺序不规范的厂商：只要文件自称是 MotionPhoto，最后一个 Item:Length 就是视频
            n = lastGroup(P_ANY_ITEM_LENGTH, s);
        }
        if (n == null) {
            n = microOffset(s);
        }
        if (n == null) {
            return null;
        }
        return rangeFromEnd(fileSize, n, fileSize);
    }

    private static Long firstGroup(Pattern pattern, String s) {
        Matcher m = pattern.matcher(s);
        return m.find() ? toLong(m.group(1)) : null;
    }

    private static Long lastGroup(Pattern pattern, String s) {
        Matcher m = pattern.matcher(s);
        Long value = null;
        while (m.find()) {
            value = toLong(m.group(1));
        }
        return value;
    }

    /** 两个分支各抓一个组，谁非空用谁。 */
    private static Long microOffset(String s) {
        Matcher m = P_MICRO_VIDEO_OFFSET.matcher(s);
        while (m.find()) {
            Long value = m.group(1) != null ? toLong(m.group(1)) : toLong(m.group(2));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Long toLong(String digits) {
        if (digits == null) {
            return null;
        }
        try {
            return Long.valueOf(digits);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 由「段尾位置 + 段长」算区间，并挡住长度超出文件、把 JPEG 吃掉这类矛盾值。 */
    private static long[] rangeFromEnd(long dataEnd, long n, long fileSize) {
        if (n <= 0 || dataEnd <= 0 || n >= fileSize || n > dataEnd) {
            return null;
        }
        return new long[]{dataEnd - n, n};
    }

    /**
     * 第三层：从 JPEG 的第一个 {@code FFD9}（EOI）往后小窗口扫 {@code ftyp}。
     *
     * <p>为什么取第一个 EOI 而不是最后一个：熵编码段里 0xFF 必然被填充成 0xFF00，
     * 所以 {@code FF D9} 不可能出现在扫描数据内部，第一个就是真正的 EOI。
     * 反例是拿最后一个 EOI 去量 —— 视频字节里正好含 {@code FFD9} 时会把起点算到视频中间。
     */
    static long[] scanFtyp(byte[] window, long windowStart) {
        if (window == null || window.length < 8) {
            return null;
        }
        int eoi = -1;
        for (int i = 0; i + 1 < window.length; i++) {
            if ((window[i] & 0xFF) == 0xFF && (window[i + 1] & 0xFF) == 0xD9) {
                eoi = i + 2;
                break;
            }
        }
        if (eoi < 0) {
            return null;
        }
        int limit = Math.min(window.length, eoi + FTYP_SCAN_WINDOW);
        for (int i = eoi; i + 4 <= limit; i++) {
            if (window[i] == 'f' && window[i + 1] == 't' && window[i + 2] == 'y' && window[i + 3] == 'p') {
                long start = windowStart + i - 4;   // box 的前 4 字节是长度，起点要退回去
                return start >= 0 ? new long[]{start, -1} : null;
            }
        }
        return null;
    }

    // ---- 取景框：跨分辨率必须走归一化 ----------------------------------------------------

    /**
     * 把「原图像素坐标的裁剪矩形」换算成 [0,1] 的比例矩形。
     *
     * <p>项目里裁剪矩形一律存**原图像素坐标**（少一次换算就少一处能错的地方），
     * 那是因为它服务的对象是同一张原图的区域解码。实况段是例外：
     * 真样本里封面帧 1440x1920、视频段 1080x1440，**两者分辨率不同**，
     * 像素矩形直接搬过去就错位，所以只有这一处做归一化。
     *
     * @param rect4 {@code {left, top, right, bottom}}，原图像素坐标
     * @return {@code {u0, vTop0, u1, vTop1}}（v 从**顶部**起算），参数不合法返回 null
     */
    public static float[] normalizedRect(float[] rect4, int srcWidth, int srcHeight) {
        if (rect4 == null || rect4.length != 4 || srcWidth <= 0 || srcHeight <= 0) {
            return null;
        }
        float l = clamp01(rect4[0] / srcWidth);
        float t = clamp01(rect4[1] / srcHeight);
        float r = clamp01(rect4[2] / srcWidth);
        float b = clamp01(rect4[3] / srcHeight);
        if (r <= l || b <= t) {
            return null;
        }
        return new float[]{l, t, r, b};
    }

    /**
     * 算出「把某帧画面的这块区域铺满视口」所需的纹理坐标中心与跨度。
     *
     * <p>坐标系约定（与 {@link WallpaperRenderer} 里实况那套顶点着色器对齐）：
     * 传入的 {@code normTopLeft} 的 v 从**顶部**起算（跟位图/裁剪矩形一致），
     * 返回的 center/span 的 v 从**底部**起算（GL 纹理与 SurfaceTexture 变换矩阵的输入空间），
     * 换算就是 {@code v_bottom = 1 - v_top}，只做这一次，别处不再翻。
     *
     * @param frameW    这一帧的实际宽（视频帧是 1080，位图路径用不到）
     * @param vpAspect  视口宽高比（宽/高）
     * @return {@code {centerU, centerV, spanU, spanV}}，参数不合法返回整帧居中
     */
    public static float[] cropForViewport(float[] normTopLeft, int frameW, int frameH, float vpAspect) {
        float u0 = 0f, u1 = 1f, t0 = 0f, t1 = 1f;
        if (normTopLeft != null && normTopLeft.length == 4) {
            u0 = normTopLeft[0];
            t0 = normTopLeft[1];
            u1 = normTopLeft[2];
            t1 = normTopLeft[3];
        }
        float spanU = Math.max(u1 - u0, 1e-6f);
        float spanT = Math.max(t1 - t0, 1e-6f);
        // 转成 v 从底部起算：矩形的 top 边对应大的 v_bottom
        float vLow = 1f - t1;
        float vHigh = 1f - t0;
        float centerV = (vLow + vHigh) * 0.5f;
        float centerU = (u0 + u1) * 0.5f;
        if (frameW <= 0 || frameH <= 0 || vpAspect <= 0f) {
            return new float[]{centerU, centerV, spanU, spanT};
        }
        // 子矩形在帧里的像素宽高比，决定 centerCrop 往哪边收
        float subAspect = (spanU * frameW) / (spanT * frameH);
        float cropX = Math.min(1f, vpAspect / subAspect);
        float cropY = Math.min(1f, subAspect / vpAspect);
        return new float[]{centerU, centerV, spanU * cropX, spanT * cropY};
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    // ---- 读盘小工具 ----------------------------------------------------------------------

    private static byte[] readTail(RandomAccessFile raf, long size) throws Exception {
        if (size < TRAILER_BYTES) {
            return new byte[0];
        }
        byte[] buf = new byte[TRAILER_BYTES];
        raf.seek(size - TRAILER_BYTES);
        raf.readFully(buf);
        return buf;
    }

    private static byte[] readHead(RandomAccessFile raf, long size) throws Exception {
        int n = (int) Math.min(size, HEAD_WINDOW);
        byte[] buf = new byte[n];
        raf.seek(0);
        raf.readFully(buf);
        return buf;
    }

    /** 校验候选起点确实是 MP4 box 的头（4 字节长度 + "ftyp"），不中就当这一层没命中。 */
    private static boolean startsWithFtyp(RandomAccessFile raf, long start) {
        if (start < 0) {
            return false;
        }
        try {
            byte[] head = new byte[8];
            raf.seek(start);
            raf.readFully(head);
            return head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p';
        } catch (Exception e) {
            return false;
        }
    }

    /** 第三层要就地算出长度（没有元数据可问），所以单独给一个带文件句柄的版本。 */
    private static long[] scanFtyp(RandomAccessFile raf, long size) throws Exception {
        int window = (int) Math.min(size, 2L * 1024 * 1024);
        byte[] buf = new byte[window];
        raf.seek(0);
        raf.readFully(buf);
        long[] found = scanFtyp(buf, 0);
        if (found == null) {
            return null;
        }
        long start = found[0];
        if (start <= 0 || start >= size) {
            return null;
        }
        // 这一层拿不到声明长度，只能「一直到文件尾」；实况段本来就顶到 EOF（或顶到 trailer，
        // 那 40 字节在 mdat 之后，MediaExtractor 按 moov 里的样本偏移读，多读不到东西）
        return new long[]{start, size - start};
    }
}
