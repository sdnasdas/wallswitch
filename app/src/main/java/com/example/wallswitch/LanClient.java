package com.example.wallswitch;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * 收端。用**裸 Socket** 而不是 HttpURLConnection：Android 从 targetSdk 28 起默认禁明文 HTTP，
 * 走那个栈就得为它动网络安全配置；而那条明文策略只约束 HTTP 客户端与 WebView，不拦
 * {@code java.net.Socket}。发端仍是标准 HTTP/1.1，所以那行地址贴进浏览器照样能下载
 * —— 兜底那条路是白留的，不多花一行代码。
 *
 * <p>只用 java.*，check/LanSyncTest.java 拿它对着真服务跑往返（客户端排在服务端前面，
 * 这样 A6 的往返测试两端都跑真码，不在夹具里复刻第二个客户端）。
 *
 * <p>三条落盘规矩：先写 {@code .part} 验过再改名（半截文件绝不能被当成完整的包）；
 * 摘要不符删干净；任何异常路径都不留残。定稿里不做断点续传。
 */
public final class LanClient {

    static final int COPY = 64 * 1024;
    static final int HEAD_LIMIT = 64 * 1024;

    private LanClient() {
    }

    /** 响应头里我们关心的两项。 */
    public static final class Head {
        public int status;
        public long contentLength = -1;
    }

    /** 下载进度（在服务/工作线程上回调，界面自己 post 回主线程）。 */
    public interface Progress {
        void onBytes(long done, long total);
    }

    public static final class Result {
        public long bytes;
        public String sha256;
        public String error;
    }

    /**
     * 读到空行为止，返回时流的位置正好停在 body 第一个字节。
     *
     * <p>绝不能用 BufferedReader 读头 —— 它会预读并吞掉紧随其后的二进制字节。做法是攒字节直到认出
     * 完整的 {@code \r\n\r\n}，再把这 4 字节从头部剔掉：逐行状态机很容易写成「认完一行就归零」，
     * 于是那个空行永远等不到（夹具里以 CRLFCRLF 开头的 body、以及「头部没结尾要抛」两条，
     * 就是冲这个写法来的）。
     */
    public static Head readHead(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(512);
        int b;
        int tail = 0;                       // 已连续匹配上的 "\r\n\r\n" 前缀长度
        while ((b = in.read()) >= 0) {
            bos.write(b);
            if ((tail == 0 && b == '\r') || (tail == 1 && b == '\n')
                    || (tail == 2 && b == '\r') || (tail == 3 && b == '\n')) {
                tail++;
            } else {
                tail = b == '\r' ? 1 : 0;
            }
            if (tail == 4) {
                byte[] all = bos.toByteArray();
                return parse(new String(all, 0, all.length - 4, StandardCharsets.US_ASCII));
            }
            if (bos.size() > HEAD_LIMIT) {
                throw new IOException("对方头部过长");
            }
        }
        throw new IOException("对方在发完头之前就断了");
    }

    /** 头部文本（不含结尾空行）→ 状态码与 Content-Length。 */
    static Head parse(String headBlock) throws IOException {
        String[] lines = headBlock.split("\r\n");
        if (lines.length == 0 || lines[0].isEmpty()) {
            throw new IOException("对方没给状态行");
        }
        String[] parts = lines[0].split(" ");
        if (parts.length < 2) {
            throw new IOException("状态行看不懂：" + lines[0]);
        }
        Head h = new Head();
        try {
            h.status = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new IOException("状态码不是数字：" + parts[1]);
        }
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon <= 0) {
                continue;
            }
            if (lines[i].substring(0, colon).trim().toLowerCase(Locale.US).equals("content-length")) {
                try {
                    h.contentLength = Long.parseLong(lines[i].substring(colon + 1).trim());
                } catch (NumberFormatException e) {
                    throw new IOException("对方给的 Content-Length 看不懂");
                }
            }
        }
        return h;
    }

    /** 把状态码翻译成「用户下一步该干什么」，不许出现「出错了」这种含糊话。 */
    static String statusText(int status) {
        if (status == 404) {
            return "对方没有这个共享：口令不对，或者那边已经点了「停止共享」";
        }
        if (status == 405) {
            return "对方只接受读取";
        }
        return "对方回了 " + status;
    }

    static String requestLine(String method, String host, int port, String path) {
        return method + " " + path + " HTTP/1.1\r\nHost: " + host + ":" + port + "\r\n"
                + "Connection: close\r\n\r\n";
    }

    /** 取 info 响应体。连不上/状态不对都抛 IOException，消息已是给用户看的那句。 */
    public static String fetchInfo(LanPair.Target t, int connectMs) throws IOException {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(t.host, t.port), connectMs);
            s.setSoTimeout(Math.max(connectMs, 15_000));
            OutputStream out = s.getOutputStream();
            out.write(requestLine("GET", t.host, t.port, "/" + t.token + "/info")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = s.getInputStream();
            Head h = readHead(in);
            if (h.status != 200) {
                throw new IOException(statusText(h.status));
            }
            return new String(readExact(in, h.contentLength), StandardCharsets.UTF_8);
        }
    }

    /** 读满 expect 字节；expect&lt;0 读到 EOF。短了就抛「短了」，绝不静默少收。 */
    static byte[] readExact(InputStream in, long expect) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(
                expect > 0 && expect < (1 << 20) ? (int) expect : 8192);
        byte[] buf = new byte[COPY];
        long done = 0;
        int n;
        while (expect < 0 || done < expect) {
            n = in.read(buf, 0, expect > 0 ? (int) Math.min(buf.length, expect - done) : buf.length);
            if (n < 0) {
                break;
            }
            bos.write(buf, 0, n);
            done += n;
        }
        if (expect >= 0 && done != expect) {
            throw new IOException("body 短了：" + done + " / " + expect);
        }
        return bos.toByteArray();
    }

    /**
     * 把包收进 dst。{@code info} 带着期望摘要进来（不做进程级全局状态，两处下载不会互相串）。
     * 返回的 error 非空 = 没收到，且盘上不留残。
     */
    public static Result download(LanPair.Target t, LanPair.Info info, File dst,
                                  int connectMs, int readMs, Progress progress) {
        Result r = new Result();
        File part = new File(dst.getPath() + ".part");
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            long total;
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(t.host, t.port), connectMs);
                s.setSoTimeout(readMs);
                OutputStream out = s.getOutputStream();
                out.write(requestLine("GET", t.host, t.port, "/" + t.token + "/file")
                        .getBytes(StandardCharsets.US_ASCII));
                out.flush();
                InputStream in = s.getInputStream();
                Head h = readHead(in);
                if (h.status != 200) {
                    r.error = statusText(h.status);
                    return r;
                }
                total = h.contentLength;
                if (dst.getParentFile() != null) {
                    dst.getParentFile().mkdirs();
                }
                try (FileOutputStream file = new FileOutputStream(part)) {
                    byte[] buf = new byte[COPY];
                    long done = 0;
                    int n;
                    while (total < 0 || done < total) {
                        n = in.read(buf, 0, total > 0
                                ? (int) Math.min(buf.length, total - done) : buf.length);
                        if (n < 0) {
                            break;
                        }
                        file.write(buf, 0, n);
                        md.update(buf, 0, n);
                        done += n;
                        if (progress != null) {
                            progress.onBytes(done, total);
                        }
                    }
                    file.flush();
                    r.bytes = done;
                }
            }
            if (info != null && info.bytes > 0 && r.bytes != info.bytes) {
                r.error = "传输中断：收到 " + r.bytes + " 字节，应有 " + info.bytes
                        + " 字节，再点一次重收整包";
                return r;
            }
            r.sha256 = LanPackager.hex(md.digest());
            if (info != null && info.sha256 != null && !info.sha256.equalsIgnoreCase(r.sha256)) {
                r.error = "包在路上下了个坏版本（校验摘要不符），已丢掉，重收一次";
                return r;
            }
            if (dst.exists() && !dst.delete()) {
                r.error = "上次收的那份删不掉，先清出空间再收";
                return r;
            }
            if (!part.renameTo(dst)) {
                r.error = "收好了但改不了名（磁盘可能满了）";
                return r;
            }
            return r;
        } catch (SocketTimeoutException e) {
            r.error = "等对方回话超时：两台机多半不在同一个 Wi-Fi，或被路由器隔离"
                    + "（改成旧机开热点、这台连它）";
            return r;
        } catch (java.net.ConnectException e) {
            r.error = "连不上那个端口：对方可能已经点了「停止共享」，或者这个码过期了";
            return r;
        } catch (IOException | OutOfMemoryError e) {
            r.error = "收的时候出事：" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : "（" + e.getMessage() + "）");
            return r;
        } catch (Exception e) {
            r.error = "收的时候出事：" + e.getClass().getSimpleName();
            return r;
        } finally {
            if (r.error != null) {
                part.delete();
            }
        }
    }
}
