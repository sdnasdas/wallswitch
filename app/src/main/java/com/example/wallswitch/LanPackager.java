package com.example.wallswitch;

import java.io.File;
import java.io.FileInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 「一组条目 → 一个 zip 流」的唯一实现：备份（写 SAF 导出目录）与局域网共享（写 cache）都走这里。
 *
 * <p>为什么要抽出来：出现第二个打包写手，就等于备份格式有两处定义，改一处会静默漂 ——
 * 本项目在「library.json 的序列化只能一处定义」上吃过一次亏。条目清单由
 * {@code BackupStore.collectEntries} 统一给，这里只管搬字节。
 *
 * <p>本类只用 java.io / java.util / java.security，不 import 任何 android 类型，
 * 因此 {@code check/LanSyncTest.java} 能直接编译这份真源码跑断言（同 MotionSource 的理由：
 * 复刻算式就有两处定义，两处定义就会漂）。
 *
 * <p>压缩档用 {@link Deflater#BEST_SPEED}：包内大头是无损 PNG，DEFLATE 到底只差 2~5%
 * （BackupStore 的原注释已承认这点），拿几十秒 CPU 换那 2~5% 体积在手机上明显亏。
 * 不用 STORE：STORE 要求 putNextEntry 之前把 size 和 crc 填全，等于每个文件要多整读一遍，
 * 省下的 CPU 又被多出来的那一遍 IO 吃回去。实测数字见 docs/lan-sync-plan.md §9。
 *
 * <p>SHA-256 在字节离开本进程的最后一刻顺手算掉（{@link DigestCount}），不额外读第二遍。
 */
public final class LanPackager {

    static final int DEFLATE_LEVEL = Deflater.BEST_SPEED;
    static final int BUFFER_BYTES = 64 * 1024;

    private LanPackager() {
    }

    /** 一个待打包条目：要么来自文件，要么来自内存字节（manifest.json 是后者）。 */
    public static final class Entry {
        public final String name;
        public final File src;
        public final byte[] data;

        public Entry(String name, File src) {
            this.name = name;
            this.src = src;
            this.data = null;
        }

        public Entry(String name, byte[] data) {
            this.name = name;
            this.src = null;
            this.data = data;
        }
    }

    /** 打包结果：进了包的条目数、整包字节、整包摘要。 */
    public static final class Result {
        public int entries;
        public long zipBytes;
        public String sha256;
    }

    /**
     * 数一遍 + 摘要一遍。write(byte[],int,int) 必须自己实现：FilterOutputStream 的默认实现
     * 是按字节循环调 write(int)，几百 MB 会慢到离谱。
     */
    private static final class DigestCount extends FilterOutputStream {
        private final MessageDigest digest;
        long written;

        DigestCount(OutputStream out, MessageDigest digest) {
            super(out);
            this.digest = digest;
        }

        @Override
        public void write(int one) throws IOException {
            out.write(one);
            digest.update((byte) one);
            written++;
        }

        @Override
        public void write(byte[] buf, int off, int len) throws IOException {
            out.write(buf, off, len);
            digest.update(buf, off, len);
            written += len;
        }

        @Override
        public void flush() throws IOException {
            out.flush();
        }

        byte[] sha() {
            return digest.digest();
        }
    }

    /**
     * 打包到 out。out 由调用方负责关闭，本方法只 finish + flush。
     * 找不到源文件的条目静默跳过 —— 与 BackupStore 原行为一致：存量老壁纸可能没有 originals/，
     * 那只是以后不能重复调整，不是错误。
     */
    public static Result pack(List<Entry> entries, OutputStream out) throws Exception {
        DigestCount sink = new DigestCount(out, MessageDigest.getInstance("SHA-256"));
        ZipOutputStream zout = new ZipOutputStream(sink);
        zout.setLevel(DEFLATE_LEVEL);
        Result r = new Result();
        byte[] buf = new byte[BUFFER_BYTES];
        for (Entry e : entries) {
            if (e.data == null && (e.src == null || !e.src.isFile())) {
                continue;
            }
            zout.putNextEntry(new ZipEntry(e.name));
            if (e.data != null) {
                if (e.data.length > 0) {
                    zout.write(e.data, 0, e.data.length);
                }
            } else {
                try (InputStream in = new FileInputStream(e.src)) {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        zout.write(buf, 0, n);
                    }
                }
            }
            zout.closeEntry();
            r.entries++;
        }
        zout.finish();
        zout.flush();
        r.zipBytes = sink.written;
        r.sha256 = hex(sink.sha());
        return r;
    }

    /** 复用一个现成的包（24 小时内打过）时只算摘要，一遍顺序读。 */
    public static String sha256Of(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] buf = new byte[BUFFER_BYTES];
        try (InputStream in = new FileInputStream(file)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        return hex(md.digest());
    }

    /** 小写十六进制，收端比对时按 equalsIgnoreCase 处理。 */
    static String hex(byte[] raw) {
        StringBuilder sb = new StringBuilder(raw.length * 2);
        for (byte b : raw) {
            String s = Integer.toHexString(b & 0xFF);
            if (s.length() < 2) {
                sb.append('0');
            }
            sb.append(s);
        }
        return sb.toString();
    }
}
