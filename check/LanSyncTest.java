package com.example.wallswitch;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.zip.ZipFile;

/**
 * 局域网同步的纯 JVM 夹具：直接编译生产源码（LanPackager / LanPair / LanClient / LanHttp /
 * ScanTransform）跑断言，不复刻算式 —— 复刻就有两处定义、就会漂，那是 MotionSource 注释里
 * 点名的老毛病。跑法见 check/lan.cmd。
 *
 * <p>BackupStore 与两个 Activity 依赖 Context，JVM 跑不了；它们身上真正有风险的那条
 * （copyToCache 把包复制给自己）由 {@link #trap()} 用同样的三行 IO 形状锁住必要性。
 */
public class LanSyncTest {

    static int pass = 0, fail = 0;

    /** BackupStore 里 copyToCache 的目标名，照抄一份在这里当常量用（改它要两边一起改）。 */
    static final String CACHE_ZIP_NAME = "restore.zip";

    /** 真的 sha256 是 64 位十六进制；info 里塞短的会被「没法验」那条守卫挡掉。 */
    static final String SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    static void ok(String name, boolean cond) {
        if (cond) {
            pass++;
            System.out.println("  PASS  " + name);
        } else {
            fail++;
            System.out.println("  FAIL  " + name);
        }
    }

    static void eq(String name, Object want, Object got) {
        ok(name + "  want=" + want + " got=" + got, String.valueOf(want).equals(String.valueOf(got)));
    }

    static File tmpDir(String tag) throws Exception {
        File d = Files.createTempDirectory("lan-" + tag).toFile();
        d.deleteOnExit();
        return d;
    }

    static File write(File dir, String name, byte[] data) throws Exception {
        File f = new File(dir, name);
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(data);
        }
        return f;
    }

    static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        in.close();
        return bos.toByteArray();
    }

    static byte[] sha256(byte[] raw) throws Exception {
        return java.security.MessageDigest.getInstance("SHA-256").digest(raw);
    }

    interface Throwing {
        void run() throws Exception;
    }

    static boolean throwsIo(Throwing t) {
        try {
            t.run();
            return false;
        } catch (java.io.IOException expected) {
            return true;
        } catch (Exception other) {
            return false;
        }
    }

    // ===== A2：打包 =====
    static void packager() throws Exception {
        System.out.println("\n--- LanPackager ---");
        File dir = tmpDir("pack");
        byte[] b = new byte[400000];
        new Random(7).nextBytes(b);
        write(dir, "a.txt", "hello".getBytes(StandardCharsets.UTF_8));
        write(dir, "b.bin", b);

        List<LanPackager.Entry> es = new ArrayList<>();
        es.add(new LanPackager.Entry("manifest.json", "{\"x\":1}".getBytes(StandardCharsets.UTF_8)));
        es.add(new LanPackager.Entry("wallpapers/a.png", new File(dir, "a.txt")));
        es.add(new LanPackager.Entry("wallpapers/b.bin", new File(dir, "b.bin")));
        es.add(new LanPackager.Entry("originals/nope", new File(dir, "does-not-exist")));
        es.add(new LanPackager.Entry("thumbs/empty.jpg", new byte[0]));

        File zip = new File(dir, "out.zip");
        LanPackager.Result r;
        try (FileOutputStream out = new FileOutputStream(zip)) {
            r = LanPackager.pack(es, out);
        }
        ok("缺源文件的条目静默跳过，其余 4 条进包", r.entries == 4);
        ok("整包字节数与摘要都填了", r.zipBytes > 0 && r.sha256 != null && r.sha256.length() == 64);
        try (ZipFile zf = new ZipFile(zip)) {
            eq("zip 条目数", 4, zf.size());
            ok("内存条目读得回", new String(readAll(zf.getInputStream(zf.getEntry("manifest.json"))),
                    StandardCharsets.UTF_8).equals("{\"x\":1}"));
            ok("二进制条目逐字节相等", Arrays.equals(
                    readAll(zf.getInputStream(zf.getEntry("wallpapers/b.bin"))), b));
            ok("空条目也进包", zf.getEntry("thumbs/empty.jpg") != null);
        }
        File zip2 = new File(dir, "out2.zip");
        LanPackager.Result r2;
        try (FileOutputStream out = new FileOutputStream(zip2)) {
            r2 = LanPackager.pack(es, out);
        }
        ok("同输入两次摘要相同（锁住「摘要属于整包、不属于逐条目」）", r.sha256.equals(r2.sha256));
        eq("对现成的包单独算的摘要与打包时顺手算的一致", r.sha256, LanPackager.sha256Of(zip));
        ok("zipBytes 等于文件实际长度", r.zipBytes == zip.length());
    }

    // ===== A3：copyToCache 自覆盖陷阱 =====
    static void trap() throws Exception {
        System.out.println("\n--- copyToCache 自覆盖陷阱 ---");
        File dir = tmpDir("trap");
        File same = write(dir, CACHE_ZIP_NAME, new byte[200000]);
        long got;
        try (InputStream in = new FileInputStream(same);
             FileOutputStream out = new FileOutputStream(same)) {   // 这一行先把文件截成 0
            out.write(readAll(in));
            out.flush();
            got = same.length();
        }
        eq("同一个文件既当输入又当输出，只剩", 0L, got);

        File src = write(dir, "src.zip", new byte[200000]);
        File copy = new File(dir, "copy.zip");
        try (InputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(copy)) {
            out.write(readAll(in));
        }
        eq("换成两个不同的名字就完好", 200000L, copy.length());
        ok("收端落盘名必须与 cache 副本名不同（局域网那条链不能用 restore.zip）",
                !"lan-receive.zip".equals(CACHE_ZIP_NAME));
    }

    // ===== A4：配对信息 =====
    static void pair() {
        System.out.println("\n--- LanPair 地址与口令 ---");
        eq("地址形状", "http://192.168.1.23:49317/7f2k9zq1",
                LanPair.buildUrl("192.168.1.23", 49317, "7f2k9zq1"));
        LanPair.Target t = LanPair.parse("  http://10.0.0.5:8080/ab2cd3ef  ");
        ok("带 scheme 能解", t.error == null && t.host.equals("10.0.0.5")
                && t.port == 8080 && t.token.equals("ab2cd3ef"));
        ok("省略 http:// 也认（手输兜底那条）", LanPair.parse("10.0.0.5:8080/ab2cd3ef").error == null);
        t = LanPair.parse("HTTP://10.0.0.5:8080/AB2CD3EF/");
        ok("大小写与结尾斜杠容忍", t.error == null && t.token.equals("ab2cd3ef"));
        ok("缺端口报错", LanPair.parse("10.0.0.5/ab2cd3ef").error != null);
        ok("端口越界报错", LanPair.parse("10.0.0.5:99999/ab2cd3ef").error != null);
        ok("口令太短报错", LanPair.parse("10.0.0.5:8080/ab").error != null);
        ok("带 @ 报错", LanPair.parse("u@10.0.0.5:8080/ab2cd3ef").error != null);
        ok("多一段路径报错", LanPair.parse("10.0.0.5:8080/ab2cd3ef/file").error != null);
        ok("非 http scheme 报错", LanPair.parse("ftp://10.0.0.5:80/ab2cd3ef").error != null);
        ok("完全不像地址的，报错而不是崩", LanPair.parse("随便一句话").error != null);
        ok("空输入报错而不是崩", LanPair.parse(null).error != null);
        ok("口令 8 位且不含易混字符", LanPair.randomToken()
                .matches("[abcdefghjkmnpqrstuvwxyz23456789]{8}"));
        ok("两次口令不同", !LanPair.randomToken().equals(LanPair.randomToken()));

        System.out.println("--- LanPair info ---");
        String raw = LanPair.encodeInfo(LanPair.PROTOCOL_V, "lan-share.zip", "2026-10-07 14:22:31",
                38, 38, 36, 38, 631244800L, SHA, "3.96");
        LanPair.Info i = LanPair.parseInfo(raw);
        ok("info 往返", i.error == null && i.protocolV == 1 && i.items == 38 && i.originals == 36
                && i.bytes == 631244800L && i.sha256.equals(SHA) && i.images == 38
                && i.thumbs == 38 && i.appVersion.equals("3.96")
                && i.time.equals("2026-10-07 14:22:31"));
        ok("协议版本不符要拒绝", LanPair.parseInfo("v=99\nbytes=1\nsha256=" + SHA).error != null);
        ok("缺大小要拒绝", LanPair.parseInfo("v=1\nsha256=" + SHA).error != null);
        ok("摘要太短要拒绝（真 sha256 是 64 位十六进制，短了就是没给全）",
                LanPair.parseInfo("v=1\nbytes=1\nsha256=ab").error != null);
        ok("数字字段读不出要拒绝", LanPair.parseInfo("v=1\nbytes=abc\nsha256=" + SHA).error != null);
        ok("未知字段忽略（前向兼容）",
                LanPair.parseInfo("v=1\nbytes=1\nsha256=" + SHA + "\nweird=x").error == null);
        ok("空文本报错", LanPair.parseInfo("").error != null);
        ok("值里塞换行不会伪造成多一行字段",
                LanPair.parseInfo(LanPair.encodeInfo(1, "a\nbytes=99", "t", 1, 1, 1, 1, 5L,
                        SHA, "3")).bytes == 5L);

        System.out.println("--- LanPair 本机地址挑选 ---");
        List<String> in = Arrays.asList("127.0.0.1", "0:0:0:0:0:0:0:1%lo", "169.254.88.7",
                "192.168.1.23", "192.168.43.1", "10.12.0.9", "172.16.4.4", "8.8.8.8");
        eq("丢 loopback / IPv6 / 链路本地 / 公网，顺序保留",
                "[192.168.1.23, 192.168.43.1, 10.12.0.9, 172.16.4.4]",
                LanPair.filterCandidates(in).toString());
        eq("热点网关优先（另一台机连它时必然通）", "192.168.43.1", LanPair.prefer(in));
        eq("没热点时家用段优先", "192.168.1.23",
                LanPair.prefer(Arrays.asList("10.12.0.9", "192.168.1.23")));
        eq("172 段只认 16~31", "[]",
                LanPair.filterCandidates(Arrays.asList("172.15.1.1", "172.32.1.1")).toString());
        eq("八位组越界不算地址", "[]",
                LanPair.filterCandidates(Arrays.asList("192.168.1.999")).toString());
        eq("带前导零的怪写法不算地址", "[]",
                LanPair.filterCandidates(Arrays.asList("192.168.01.9")).toString());
        eq("全被滤掉时返回空串而不是 null", "", LanPair.prefer(Arrays.asList("127.0.0.1")));

        System.out.println("--- LanPair.qrPixels ---");
        boolean[] rows = new boolean[16];
        rows[0] = true;      // 模块 (0,0) 黑 → 像素 [10,15)
        rows[15] = true;     // 模块 (3,3) 黑 → 像素 [25,30)
        int[] px = LanPair.qrPixels(rows, 4, 4, 5, 2);      // 边长 (4+2*2)*5 = 40
        eq("边长 =（模块 + 2×留白）×每模块像素", 40 * 40, px.length);
        eq("左上留白是白", 0xFFFFFFFF, px[0]);
        eq("首个模块左上角是黑", 0xFF000000, px[10 * 40 + 10]);
        eq("首个模块右下角仍是黑", 0xFF000000, px[14 * 40 + 14]);
        eq("末个模块右下角是黑", 0xFF000000, px[29 * 40 + 29]);
        eq("内容一结束就是白（留白从 30 起）", 0xFFFFFFFF, px[30 * 40 + 30]);
        eq("没点着的格子是白", 0xFFFFFFFF, px[10 * 40 + 30]);
        eq("界面侧算边长", 40, LanPair.qrSidePixels(4, 5, 2));
    }

    // ===== A5：客户端头部解析 =====
    static void clientHead() throws Exception {
        System.out.println("\n--- LanClient 头部解析 ---");
        byte[] raw = ("HTTP/1.1 200 OK\r\nContent-Type: application/zip\r\nContent-Length: 12\r\n"
                + "Connection: close\r\n\r\n" + "\r\n\r\nabcdefghij")
                .getBytes(StandardCharsets.US_ASCII);
        InputStream in = new java.io.ByteArrayInputStream(raw);
        LanClient.Head h = LanClient.readHead(in);
        eq("状态", 200, h.status);
        eq("长度", 12L, h.contentLength);
        eq("头部读完，流正好停在 body 第一个字节", "\r\n\r\nabcdefghij",
                new String(readAll(in), StandardCharsets.US_ASCII));

        byte[] nf = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n"
                .getBytes(StandardCharsets.US_ASCII);
        eq("404 解得出", 404,
                LanClient.readHead(new java.io.ByteArrayInputStream(nf)).status);
        ok("状态行残缺要抛而不是崩", throwsIo(() -> LanClient.readHead(
                new java.io.ByteArrayInputStream("garbage".getBytes(StandardCharsets.US_ASCII)))));
        ok("头部没有结尾空行要抛", throwsIo(() -> LanClient.readHead(
                new java.io.ByteArrayInputStream("HTTP/1.1 200 OK\r\nX: y"
                        .getBytes(StandardCharsets.US_ASCII)))));
        ok("404 的文案提出口令或停止共享", LanClient.statusText(404).contains("停止共享"));
        ok("405 的文案提到只读", LanClient.statusText(405).contains("读"));
        ok("状态行不是数字要抛", throwsIo(() -> LanClient.parse("HTTP/1.1 xx OK")));
    }

    // ===== A6：服务端往返（两端都跑真码） =====
    static LanHttp.Server serve(File zip, String sha, int items) throws Exception {
        String info = LanPair.encodeInfo(LanPair.PROTOCOL_V, zip.getName(),
                "2026-10-07 14:22:31", items, items, items, items, zip.length(), sha, "3.96");
        LanHttp.Server s = new LanHttp.Server("abcdefgh", info, new LanHttp.FilePayload(zip));
        s.start();
        return s;
    }

    static boolean connectFails(int port) {
        try (Socket s = new Socket()) {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 800);
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    static long headLength(int port) throws Exception {
        try (Socket s = new Socket()) {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 2000);
            s.setSoTimeout(3000);
            s.getOutputStream().write("HEAD /abcdefgh/file HTTP/1.1\r\nHost: x\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
            s.getOutputStream().flush();
            return LanClient.readHead(s.getInputStream()).contentLength;
        }
    }

    static void serverRoundTrip() throws Exception {
        System.out.println("\n--- LanHttp 服务端往返 ---");
        File dir = tmpDir("srv");
        byte[] body = new byte[70000];
        new Random(11).nextBytes(body);
        body[0] = 0x0D;
        body[1] = 0x0A;
        body[2] = 0x0D;
        body[3] = 0x0A;                       // 以 CRLFCRLF 开头的 body：头体切分的陷阱
        File zip = write(dir, "lan-share.zip", body);
        String sha = LanPackager.hex(sha256(body));
        LanHttp.Server s = serve(zip, sha, 3);
        LanPair.Target t = LanPair.parse("127.0.0.1:" + s.getPort() + "/abcdefgh");
        File dst = new File(dir, "lan-receive.zip");
        long[] seen = new long[2];
        try {
            LanPair.Info i = LanPair.parseInfo(LanClient.fetchInfo(t, 5000));
            ok("info 取到并解出：" + i.error, i.error == null && i.items == 3 && i.bytes == body.length);

            LanClient.Result r = LanClient.download(t, i, dst, 5000, 30_000,
                    (done, total) -> {
                        seen[0] = done;
                        seen[1] = total;
                    });
            ok("下载成功：" + r.error, r.error == null);
            eq("落盘字节数一致", body.length, dst.length());
            ok("摘要一致", sha.equalsIgnoreCase(r.sha256));
            ok("进度报得出总数", seen[1] == body.length);
            ok(".part 不留残", !new File(dst.getPath() + ".part").exists());
            ok("收下的包不叫 " + CACHE_ZIP_NAME, !new File(dir, CACHE_ZIP_NAME).exists());

            LanPair.Target wrong = LanPair.parse("127.0.0.1:" + s.getPort() + "/zzzzzzzz");
            ok("口令不对时报的是 404 那句", throwsIo(() -> LanClient.fetchInfo(wrong, 3000)));
            try {
                LanClient.fetchInfo(wrong, 3000);
            } catch (java.io.IOException e) {
                ok("口令不对的文案就是「没这个共享」那句", e.getMessage().contains("停止共享"));
            }
            ok("路径穿越在服务侧就被挡（含点号一律不认）",
                    LanHttp.what("/abcdefgh/../file", "abcdefgh") == null);
            ok("百分号编码的探测也不认",
                    LanHttp.what("/abcdefgh/%2e%2e/file", "abcdefgh") == null);
            ok("info 之外的子路径不认", LanHttp.what("/abcdefgh/nope", "abcdefgh") == null);
            eq("HEAD 只问长度不搬体", body.length, headLength(s.getPort()));
            ok("第二次连接仍服务（服务不是一次性）",
                    LanPair.parseInfo(LanClient.fetchInfo(t, 3000)).error == null);
        } finally {
            s.close();
        }
        ok("close 之后端口不再监听", connectFails(s.getPort()));

        System.out.println("--- 摘要不符要丢掉整包 ---");
        String fake = LanPair.encodeInfo(LanPair.PROTOCOL_V, "lan-share.zip", "t",
                3, 3, 3, 3, body.length, "0000000000000000000000000000000000000000000000000000000000000000",
                "3.96");
        LanHttp.Server f = new LanHttp.Server("abcdefgh", fake, new LanHttp.FilePayload(zip));
        f.start();
        try {
            LanPair.Target t2 = LanPair.parse("127.0.0.1:" + f.getPort() + "/abcdefgh");
            LanPair.Info i2 = LanPair.parseInfo(LanClient.fetchInfo(t2, 3000));
            ok("假摘要也算合法的 info（问题只能在收完时发现）", i2.error == null);
            File dst2 = new File(dir, "lan-receive2.zip");
            LanClient.Result r2 = LanClient.download(t2, i2, dst2, 5000, 30_000, null);
            ok("摘要不符要报错：" + r2.error, r2.error != null);
            ok("摘要不符不留目标文件", !dst2.exists());
            ok("摘要不符不留 .part", !new File(dst2.getPath() + ".part").exists());
        } finally {
            f.close();
        }
    }

    // ===== B3：预览矩阵 =====
    static float[] apply(float[] m, float x, float y) {
        return new float[]{m[0] * x + m[1] * y + m[2], m[3] * x + m[4] * y + m[5]};
    }

    static float[] lin(float[] m, float x, float y) {
        return new float[]{m[0] * x + m[1] * y, m[3] * x + m[4] * y};
    }

    /** 取景框 1080×1440（正是 suggestedRatio(640,480,90) = 3:4），缓冲 640×480，传感器 90 度。 */
    static void transform() {
        System.out.println("\n--- ScanTransform ---");
        float[] m = ScanTransform.fillRotate(1080f, 1440f, 640f, 480f, 90);
        float[] e1 = lin(m, 1080f / 640f, 0f);        // 一个 buffer 像素的横向跨度（已被系统拉伸过）
        float[] e2 = lin(m, 0f, 1440f / 480f);        // 纵向跨度
        double l1 = Math.hypot(e1[0], e1[1]);
        double l2 = Math.hypot(e2[0], e2[1]);
        ok("一个相机像素在屏上是正方形（不变形）", Math.abs(l1 - l2) < 1e-4);
        ok("两轴正交（没被切歪）", Math.abs(e1[0] * e2[0] + e1[1] * e2[1]) < 1e-4 * l1 * l2);
        eq("等比缩放量 = max(1080/480, 1440/640)", 2.25, Math.round(l1 * 1000) / 1000.0);
        float[] c = apply(m, 540f, 720f);
        ok("内容中心仍落在取景框中心", Math.abs(c[0] - 540f) < 0.01f && Math.abs(c[1] - 720f) < 0.01f);
        float[][] corners = {{0, 0}, {1080, 0}, {0, 1440}, {1080, 1440}};
        float minX = 1e9f, maxX = -1e9f, minY = 1e9f, maxY = -1e9f;
        for (float[] q : corners) {
            float[] p = apply(m, q[0], q[1]);
            minX = Math.min(minX, p[0]);
            maxX = Math.max(maxX, p[0]);
            minY = Math.min(minY, p[1]);
            maxY = Math.max(maxY, p[1]);
        }
        ok("旋转后的内容正好盖满取景框（不留黑边）",
                minX <= 0.01f && maxX >= 1079.99f && minY <= 0.01f && maxY >= 1439.99f);
        ok("取景框该取 3:4", "3:4".equals(ScanTransform.suggestedRatio(640, 480, 90)));
        ok("传感器 0 度时取景框取 4:3", "4:3".equals(ScanTransform.suggestedRatio(640, 480, 0)));
        // 取景框没按建议摆（拿整个竖屏当取景框）时，仍然不能变形、仍然要盖满
        float[] m2 = ScanTransform.fillRotate(1080f, 2400f, 640f, 480f, 90);
        float[] g1 = lin(m2, 1080f / 640f, 0f), g2 = lin(m2, 0f, 2400f / 480f);
        ok("取景框比例不对时也不变形",
                Math.abs(Math.hypot(g1[0], g1[1]) - Math.hypot(g2[0], g2[1])) < 1e-4);
        ok("取景框比例不对时竖着刚好盖满", Math.abs(apply(m2, 540f, 1200f)[1] - 1200f) < 0.01f);
    }

    public static void main(String[] args) throws Exception {
        packager();
        trap();
        pair();
        clientHead();
        serverRoundTrip();
        transform();
        System.out.println("\n== lan sync: " + pass + " passed, " + fail + " failed ==");
        if (fail > 0) {
            System.exit(1);
        }
    }
}
