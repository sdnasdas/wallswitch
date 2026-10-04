package com.example.wallswitch.gl;

import java.nio.charset.StandardCharsets;

/**
 * 实况段定位与取景框换算（本期唯一一处「写错也不报错」的字节算术）。
 *
 * <p>和 CropRectTest / BackupRoundtripTest 不同，这里**不复刻算式，直接编译生产源码**：
 * {@link MotionSource} 刻意只用 java.io / java.util，不 import 任何 android 类型，
 * 所以夹具能把真文件一起 javac 进来跑断言。复刻就有两处定义、就会漂，能避免就避免。
 *
 * <p>样本 B 是真机数据：小红书存下来的实况，走微信「文件」通道取回，2,140,607 字节，
 * JPEG 封面 1440x1920（1,341,120 字节，含 EOI）+ MP4 799,447 字节 + 40 字节 trailer
 * {@code "0:1000" + "LIVE_799447"}。下面这些数字全是从那个文件上实测出来的，别顺手改。
 *
 * <p>跑法：{@code check/motion.cmd}。输出保持纯 ASCII —— 本机控制台是 GBK。
 */
public class MotionParseTest {

    static int pass = 0;
    static int fail = 0;

    /** 样本 B 的真实数字。 */
    static final long SAMPLE_SIZE = 2140607L;
    static final long SAMPLE_VIDEO_START = 1341120L;
    static final long SAMPLE_VIDEO_LEN = 799447L;

    public static void main(String[] args) {
        trailerRealSample();
        trailerRejects();
        xmpBothOrdersAndLegacy();
        ftypScan();
        cropRectCrossResolution();
        cropForViewport();
        System.out.println();
        System.out.println("== MotionSource: " + pass + " passed, " + fail + " failed ==");
        if (fail > 0) {
            System.exit(1);
        }
    }

    // ---- ① 尾部 trailer ---------------------------------------------------------------

    static void trailerRealSample() {
        long[] r = MotionSource.parseTrailer(bytes(trailerOf("LIVE_" + SAMPLE_VIDEO_LEN)),
                SAMPLE_SIZE);
        range("trailer/真样本", r, SAMPLE_VIDEO_START, SAMPLE_VIDEO_LEN);
        System.out.println("  [ok]   trailer/真样本  起点 = 文件大小 - 40 - N = "
                + (SAMPLE_SIZE - 40 - SAMPLE_VIDEO_LEN));
        pass++;
    }

    static void trailerRejects() {
        // 普通 JPEG 的最后 40 字节（熵编码数据），不该被认成 trailer
        byte[] junk = new byte[40];
        for (int i = 0; i < 40; i++) {
            junk[i] = (byte) ((i * 37 + 11) & 0xFF);
        }
        nul("trailer/无 LIVE_ 的普通图", MotionSource.parseTrailer(junk, SAMPLE_SIZE));
        nul("trailer/长度不足 40", MotionSource.parseTrailer(new byte[39], SAMPLE_SIZE));
        // N 大到会吃掉整个 JPEG —— 必须拒，不能返回一个把图片切掉的区间
        nul("trailer/N 超出文件", MotionSource.parseTrailer(
                bytes(trailerOf("LIVE_99999999")), SAMPLE_SIZE));
        nul("trailer/N 为 0", MotionSource.parseTrailer(
                bytes(trailerOf("LIVE_0")), SAMPLE_SIZE));
        nul("trailer/N 非数字", MotionSource.parseTrailer(
                bytes(trailerOf("LIVE_")), SAMPLE_SIZE));
        nul("trailer/文件比 trailer 还短", MotionSource.parseTrailer(
                bytes(trailerOf("LIVE_100")), 20L));
    }

    // ---- ② XMP（Google 动态照片 1.0 与旧 Microvideo） -----------------------------------

    static void xmpBothOrdersAndLegacy() {
        String xmp = "<x:xmpmeta><Container:Directory><Seq>"
                + "<li><rdf:Seq>"
                + "<rdf:li Item:Semantic=\"Primary\" Item:Mime=\"image/jpeg\"/>"
                + "<rdf:li Item:Semantic=\"MotionPhoto\" Item:Mime=\"video/mp4\""
                + " Item:Length=\"" + SAMPLE_VIDEO_LEN + "\"/>"
                + "</rdf:Seq></li></Container:Directory></x:xmpmeta>";
        long[] r = MotionSource.parseXmp(bytes(xmp), SAMPLE_SIZE);
        range("xmp/Length 在 Semantic 之后", r, SAMPLE_SIZE - SAMPLE_VIDEO_LEN, SAMPLE_VIDEO_LEN);

        String rev = "<rdf:li Item:Mime=\"video/mp4\" Item:Length=\"" + SAMPLE_VIDEO_LEN
                + "\" Item:Semantic=\"MotionPhoto\"/>";
        range("xmp/Length 在前", MotionSource.parseXmp(bytes(rev), SAMPLE_SIZE),
                SAMPLE_SIZE - SAMPLE_VIDEO_LEN, SAMPLE_VIDEO_LEN);

        String legacy = "<Camera:MicroVideo>1</Camera:MicroVideo>"
                + "<Camera:MicroVideoOffset>100000</Camera:MicroVideoOffset>";
        range("xmp/旧 MicroVideoOffset 元素写法", MotionSource.parseXmp(bytes(legacy), SAMPLE_SIZE),
                SAMPLE_SIZE - 100000, 100000);

        String legacyAttr = "<GCamera:MicroVideoOffset=\"100000\"/>";
        range("xmp/旧 MicroVideoOffset 属性写法",
                MotionSource.parseXmp(bytes(legacyAttr), SAMPLE_SIZE),
                SAMPLE_SIZE - 100000, 100000);

        // 属性顺序不规范：Semantic 与 Length 不同标签 —— 认「最后一个 Item:Length」（视频总是末条）
        String loose = "<Item:Semantic=\"MotionPhoto\"/>"
                + "<rdf:li Item:Mime=\"image/jpeg\" Item:Length=\"500\"/>"
                + "<rdf:li Item:Mime=\"video/mp4\" Item:Length=\"100000\"/>";
        range("xmp/顺序不规范取最后一个 Length",
                MotionSource.parseXmp(bytes(loose), SAMPLE_SIZE), SAMPLE_SIZE - 100000, 100000);

        // 只有 Item:Length、全文没提 MotionPhoto —— 不猜，直接当没有实况
        nul("xmp/有 Length 但没提 MotionPhoto", MotionSource.parseXmp(
                bytes("<rdf:li Item:Mime=\"image/jpeg\" Item:Length=\"500\"/>"), SAMPLE_SIZE));

        nul("xmp/没有实况字段", MotionSource.parseXmp(
                bytes("<x:xmpmeta><exif:Make>HONOR</exif:Make></x:xmpmeta>"), SAMPLE_SIZE));
    }

    // ---- ③ 扫 ftyp ---------------------------------------------------------------------

    static void ftypScan() {
        byte[] jpeg = new byte[1000];
        jpeg[0] = (byte) 0xFF;
        jpeg[1] = (byte) 0xD8;
        jpeg[998] = (byte) 0xFF;
        jpeg[999] = (byte) 0xD9;                       // EOI 在最后两字节
        byte[] mp4 = new byte[64];
        mp4[4] = 'f';
        mp4[5] = 't';
        mp4[6] = 'y';
        mp4[7] = 'p';
        byte[] all = concat(jpeg, mp4);
        long[] r = MotionSource.scanFtyp(all, 0);
        range("ftyp/EOI 后紧跟 MP4", r, 1000, -1);

        // 熵编码里出现一个假的 FFD9 不算干扰：取第一个 EOI，视频段里的那个不该被当锚点
        byte[] withFake = concat(jpeg, mp4, new byte[]{(byte) 0xFF, (byte) 0xD9});
        long[] r2 = MotionSource.scanFtyp(withFake, 0);
        range("ftyp/视频里也有 FFD9", r2, 1000, -1);

        nul("ftyp/EOI 之后 64 字节内没有 ftyp",
                MotionSource.scanFtyp(concat(jpeg, new byte[80]), 0));
    }

    // ---- ④ 取景框：跨分辨率必须一致 ------------------------------------------------------

    static void cropRectCrossResolution() {
        // 原图 1440x1920 上取 (360,480)-(1080,1440)：归一化后与帧的实际分辨率无关
        float[] n = MotionSource.normalizedRect(new float[]{360f, 480f, 1080f, 1440f},
                1440, 1920);
        near("rect/u0", 0.25f, n[0]);
        near("rect/vTop0", 0.25f, n[1]);
        near("rect/u1", 0.75f, n[2]);
        near("rect/vTop1", 0.75f, n[3]);
        // 退化的与越界的都要拒
        nul("rect/右<=左", MotionSource.normalizedRect(new float[]{900f, 0f, 900f, 100f}, 1440, 1920));
        nul("rect/尺寸为 0", MotionSource.normalizedRect(new float[]{0f, 0f, 10f, 10f}, 0, 1920));
        // 关键不变量：同一个矩形搬到 1080x1440 的实况帧上，比例一模一样（真样本里
        // 封面帧 1440x1920、视频段 1080x1440，像素矩形直接搬就会错位）
        float[] same = MotionSource.normalizedRect(new float[]{270f, 360f, 810f, 1080f},
                1080, 1440);
        for (int i = 0; i < 4; i++) {
            near("rect/跨分辨率一致[" + i + "]", n[i], same[i]);
        }
    }

    static void cropForViewport() {
        // 子矩形是正方形（0.5*1080 x 0.5*1440 不等）→ 用 1080x1440 帧、视口 1264x2800
        float[] n = MotionSource.normalizedRect(new float[]{360f, 480f, 1080f, 1440f}, 1440, 1920);
        float vp = 1264f / 2800f;
        float[] cs = MotionSource.cropForViewport(n, 1080, 1440, vp);
        // center 的 v 从底部起算：vTop 中心 0.5 → vBottom 中心也是 0.5
        near("vp/centerU", 0.5f, cs[0]);
        near("vp/centerV", 0.5f, cs[1]);
        // 子矩形像素宽高比 = (0.5*1080)/(0.5*1440) = 0.75，视口 0.4514 更窄
        // → 高度铺满（spanV 不缩），宽度按比例裁掉
        float subAspect = (0.5f * 1080f) / (0.5f * 1440f);
        near("vp/spanV 不缩", 0.5f, cs[3]);
        near("vp/spanU 按比例收", 0.5f * vp / subAspect, cs[2]);
        // 整帧 + 视口正好同比例 → 两轴都不收
        float[] full = MotionSource.cropForViewport(null, 1080, 1440, 0.75f);
        near("vp/整帧 spanU", 1f, full[2]);
        near("vp/整帧 spanV", 1f, full[3]);
        // 尺寸未知（还没拿到 format）时不能崩，退化成不裁
        float[] unknown = MotionSource.cropForViewport(n, 0, 0, vp);
        near("vp/尺寸未知 spanU", 0.5f, unknown[2]);
    }

    // ---- 断言小工具 ----------------------------------------------------------------------

    static void range(String what, long[] got, long expectStart, long expectLen) {
        if (got == null || got.length != 2 || got[0] != expectStart || got[1] != expectLen) {
            bad(what, "{" + expectStart + "," + expectLen + "}",
                    got == null ? "null" : java.util.Arrays.toString(got));
            return;
        }
        ok(what);
    }

    static void nul(String what, long[] got) {
        if (got == null) {
            ok(what);
        } else {
            bad(what, "null", java.util.Arrays.toString(got));
        }
    }

    static void nul(String what, float[] got) {
        if (got == null) {
            ok(what);
        } else {
            bad(what, "null", java.util.Arrays.toString(got));
        }
    }

    static void near(String what, float expect, float actual) {
        if (Math.abs(expect - actual) <= 1e-4f) {
            ok(what);
        } else {
            bad(what, String.valueOf(expect), String.valueOf(actual));
        }
    }

    static void ok(String what) {
        pass++;
        System.out.println("  [ok]   " + what);
    }

    static void bad(String what, String expect, String actual) {
        fail++;
        System.out.println("  [FAIL] " + what + "  期望 " + expect + " 实得 " + actual);
    }

    static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    /** 真 trailer 的形状：两个 20 字节、空格补齐的字段，共 40 字节。 */
    static String trailerOf(String liveField) {
        return pad("0:1000", 20) + pad(liveField, 20);
    }

    static String pad(String s, int width) {
        if (s.length() >= width) {
            return s.substring(0, width);
        }
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < width) {
            sb.append(' ');
        }
        return sb.toString();
    }

    static byte[] concat(byte[] a, byte[]... rest) {
        int n = a.length;
        for (byte[] b : rest) {
            n += b.length;
        }
        byte[] out = new byte[n];
        System.arraycopy(a, 0, out, 0, a.length);
        int at = a.length;
        for (byte[] b : rest) {
            System.arraycopy(b, 0, out, at, b.length);
            at += b.length;
        }
        return out;
    }
}
